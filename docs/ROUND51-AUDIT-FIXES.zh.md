# Round 51 — 第三轮审计修复 + 深度/钢笔/动效收尾（0.96）

> 第三轮「假设所有功能都有 bug」：两个只读审计分别覆盖 **项目持久化/输入焦点/dock 拖拽** 与
> **导出/渲染队列/错误处理/资源生命周期**；修复全部 P0/P1（含一个会冻结客户端的管道死锁）与多数 P2。
> 同时收尾：逐像素深度 AUTO 三态（默认开）、钢笔保持顶点数/RotoBezier/求值缓存、AM 最后一批
> 12 个 shader 效果、设置页动效、编辑器视图状态持久化。
>
> 验证：**127 个测试套件 / 780 项测试全绿（1 skipped）**；客户端本轮跑了回放交互脚本（Run D/E/F 为定位 undo 后 UI 消失问题的诊断迭代），数据链路全部通过，UI 问题见 §7 说明。

---

## 1. 导出 / 渲染队列（审计发现，全部 P0/P1 已修）

### 1.1 P0：FFmpeg 管道停止读取时客户端永久冻结

- 现象：管道模式（默认 review_h264）下 FFmpeg 退出（坏 codec、盘满、奇数尺寸+yuv420p），writer
  线程死在 `raw.write`，渲染线程随后在容量 3 的 `ArrayBlockingQueue.put()` 上永久阻塞；Cancel 跑在
  同一线程 → 只能杀进程。
- 修复：`writePipe` 改为 250ms 超时的 `offer` 轮询，每轮检查 `pipeWriterError` / `pipeDead` /
  writer 存活，30s 超时抛 "FFmpeg is not consuming frames"；writer 捕获 throwable 时置
  `pipeDead` 并 `queue.clear()`；writer 循环持局部队列引用（原实现 encode() 把字段置 null 后
  writer 下一轮 NPE，吞掉 `raw.close()`，FFmpeg 永远等 stdin EOF）。
- 编码阶段：EOF 交接 5s 超时 + writer join 10s 超时 → 直接 `failJob`（不再留下"编码中 100%"）；
  `pollEncoder` 在 ENCODING 且 writer 出错时立即失败。

### 1.2 P1：编码器模式/世界层守卫被渲染队列绕过

- 现象：BBS 模式工程在「渲染」页点开始，走的是 AE 合成帧路径：不采世界底图、回放实体层为空，
  且风险确认被跳过——最直接的"预览≠导出"破坏。
- 修复：`startSelectedRenderJob/startRenderQueue` 统一走 `canRunAeQueue()`：非 AE 编码器或
  `needsBbsWorldRender()` 的工程直接拒绝并给出引导文案；忙时/任务在跑/无任务都有状态反馈。

### 1.3 P1：`alpha_prores` 是无效 id → 静默输出 H.264

- 修复：队列选择器改为真实 id `alpha_prores4444`；`CompExportManager.start` 遇到未知 preset 不再
  回退 H.264，而是任务 FAILED + 明确信息（旧工程 `alpha_prores` 做一次别名映射）；`addRenderJob`
  先校验 preset 存在。

### 1.4 P1：音频混音失败被吞

- 修复：`prepareAudio` 失败写入 `audioWarning`；视频成功时任务消息为 "Done (no audio: …)"，
  audio-only 区分"没有音频轨"与"混音失败"；`CompExportManager.log()` 依旧保留完整原因。

### 1.5 其它导出 P1/P2

- 取消/失败清理：失败分支也清理 frames；`failJob` 统一 stopEncoder+cleanup。
- 关客户端：`CompExportManager.shutdown()` 在 `CLIENT_STOPPING` 调用，不再留下孤儿 FFmpeg。
- FBO 泄漏：`renderFrame` 的 read/write 全路径包 `try/finally { FboPool.release(fbo); }`（4K 帧
  33MB，异常路径曾可 OOM）。
- `CompRenderer.clearCaches` 补 `CompFrameServer.invalidate()`（资源重载/关停时不再留悬挂 FBO）。
- 任务 id 唯一化（删中间任务后不再撞 id）；范围添加校验（start<end、越界钳制）；"移除"运行中任务
  先要求取消；"取消"明确会取消整批；开始按钮不再静默失败。
- 已知残余（写入报告）：BBS 底图 stage 在 overlay 录制路径仍与预览不同（产品决策）；音频混音失败
  仍允许视频 DONE（但已显式标注）；导出期间编辑工程量仍可能混版（未加快照锁）。

---

## 2. 持久化 / 迁移 / 输入（审计发现，P1 已修）

### 2.1 P1：损坏工程被静默换成 demo 并覆盖原文件

- 修复：`CompStore.loadOrNull(id, createIfMissing)`：文件存在但解析/校验失败 → 复制为
  `<file>.corrupt-<ts>` 并返回 null（**绝不** demo 写回）；缺失且 createIfMissing=false 返回 null；
  `load(id)` 语义保留给"新建"路径。UI 打开失败显示"已隔离为 .corrupt-* 备份"。
- 保存：`saveChecked` + `lastSaveError()`；规范化→序列化→回读校验→写 .tmp→ATOMIC_MOVE→成功后
  轮换 .bak（.bak 失败只警告，不阻断主写入）；`.tmp` 清理；未来版本拒写。
- deleteProject 一并清理 .bak/.tmp/.corrupt-*/assets；所有 comps/layers 循环 null-safe。

### 2.2 P1：保存失败完全静默

- 修复：`flushSave` 用 `saveChecked`，失败时 `notifyError` + 状态栏 + 日志（自动保存同样路径）。

### 2.3 P1：空 `dockLayout` 时分隔条拖动失效

- 根因：`CompDockSource.getRoot()` 空布局每次返回新 default 树，而 BBS 的 splitter ratio 映射按
  节点对象身份匹配 → 永远 miss，拖动无效且 push 无效 layout undo。
- 修复：`getRoot()` 首次缓存（parse 或 defaults）并复用；`reload()` 才替换。新增 3 个 dock 测试。

### 2.4 P1：关键帧选择跨图层泄漏（会删错帧）

- 现象：图层 A 框选 3 帧 → 点图层 B（同序号通道）→ B 出现"选中"高亮 → Delete 删掉 B 的帧。
- 修复：`refreshSheets` 用 `doc.id + primary layer id` 作为身份门；目标变化时不复用
  `cacheState/applyState`（BBS 按 sheet 序号套用），重建时选择清空。

### 2.5 P1：编辑器视图状态只在关闭时落盘

- 修复：`writeEditorViewState()` 在 `savePanelLayout` 与 `flushSave` 前都写入选择/抽屉/页签/
  时间轴 zoom/scroll；`KeyframeState` 只用于同目标刷新；时间轴 `isViewReady()` 守卫防止把未布局的
  默认 view 写盘；抽屉区分"用户意图"与"当前布局是否放得下"（短时间轴强制收起不再持久化 false）。
- 撤销快照屏蔽 + applySnapshot 保留这些字段（选择/抽屉/页签/zoom 不被 undo 重置）。

### 2.6 其它交互 bug

- 预合成里 Ctrl+Z 不再"困住"：applySnapshot 按 id 重建预合成面包屑（原来清空栈 + 当前 doc 仍是
  子合成 → 无返回箭头、Esc 无效）。
- undo/redo 在拖动/分栏拖拽/曲线交互中直接忽略并提示（原来替换对象图会让拖动写入孤儿对象）。
- 窗口 resize 只在 dock 编码真正变化时才排保存（原来每几百毫秒序列化整个工程）。
- 锁定图层禁用抓手重排；多选拖动保持多选并整组移动（含边界钳制、Esc 完整回滚、撤销一步）。
- `CompPrefs` 原子写 + .bak；无 Fabric 配置目录时回落到临时目录（无头测试可用）。

---

## 3. 逐像素深度 AUTO（需求 3 最终形态）

- 三态：0 关闭 / 1 开启 / **2 自动（新默认）**。AUTO 判据（生产版 `CompRenderer.needsPerPixelDepthAuto`）：
  ≥2 个透视 3D 层、投影 AABB 相交、平面法线不平行（legacy 逻辑经 `usePerspective` 自然按全层）。
- 验证：平行平面（____bug）AUTO 关且与 painter 0 像素差；交叉平面 AUTO 开且与强制 ON 0 像素差、
  与 painter 差 >1000 像素；单 3D/不相交/全 2D/legacy/未知值/旧布尔 对照；768×432 与 384×216
  两分辨率一致；9 例新测试 + 既有策略/导出一致性测试改为委托生产判据。
- 缓存：`CompRenderCache.signature` 纳入 mode + 旧布尔，切模式立即失效；设置页切换时额外
  `invalidateDepthModeCaches()`（RenderCache+FrameServer+Precache）。
- UI：设置→开发者→渲染「逐像素深度（主合成）」三态，默认「自动（推荐）」；旧工程布尔显示「开启」。

---

## 4. 钢笔（需求 6 收尾）

- **保持顶点数模式**（AE `penKeepVertexCount`，默认开）：开=路径增删点重映射全部关键帧；关=对已
  动画路径拒绝增删并提示（不产生 undo、不半改）。设置→开发者→钢笔工具可切。
- **路径求值缓存**：单条目 memo（layer/path 身份 + frame + revision + 数组长度），每帧每路径只解析
  一次 6N 分量；overlay/命中/拖拽全部数组读；4 例缓存测试。
- **RotoBezier 编辑模式**：路径级开关（JSON 字段 `rotoBezier`，默认 false），开启后按邻点 1/6 规则
  派生普通手柄字段（渲染/缓存/文件零特判），拖点/增删/闭合/Set First Vertex 自动重派生，手柄拖动
  忽略；右键菜单开关；6+1 测试。限制：动画路径只在播放头派生（报告 §13）。
- 钢笔相关 15 类 110 tests 全绿。

---

## 5. AM 效果最后一批（需求 2-d）

- 新增 12 个数据驱动效果：`am_shape_field`（合成空间形状场）、`am_electric_edges`、
  `am_roughen_edges`、`am_smooth_bevel`、`am_halftone_cmyk`、`am_voronoi_2`、`am_ridges_2`、
  `am_noise_3`、`am_block_noise`、`am_simplestars_2`、`am_volumetric_clouds`、`am_rgb_sep`。
- 每个：零点契约 + 缩略图 + 中性/像素测试（默认可见 + 中性双路径逐像素恒等）；shader 编译审计自动纳编。
- AM gap：**158 定义 / 124 匹配 / 真正缺失 37**（剩余多为参数爆炸/多 pass 或已有等价项，列于
  `docs/EFFECTS-AM-GAP.zh.md` §3.4b）。

---

## 6. 设置页/开始页/引导页动效（需求 8 收尾）

- 设置页：分类切换内容下滑 6px + veil 淡出（180ms）、导航选中态自绘插值（wash/2px rail/下划线
  10→0）、行错峰入场（15ms/行封顶 200ms）、分组折叠 spring(420/26)、页脚淡入；卡片入场与页面内容
  双时钟分离（切分类不再重播整卡）。
- 开始页工程行 hover/选中 wash 插值；引导页步骤圆点 hover 过渡；动效纯逻辑测试累计 40。

---

## 7. 验证

| 项目 | 结果 |
| --- | --- |
| 全量测试 | **127 suites / 780 tests / 0 failed / 0 errors / 1 skipped**（0.95 基线 119/722） |
| 第三轮审计新增测试 | 持久化 15 + dock 3 + prefs 8 + AUTO 9 + 钢笔 11 + 动效 7 + 效果 24 |
| 客户端 | 回放交互脚本 3 次（D/E/F）：MOVE/UNDO/REDO/FORM/LIST 数据全过（`undoRestored=true`、`done failed=false`）；**undo 后回放页 UI 消失**为 BBS film-panel undo 侧效应，未根治（见 §7） |

## 7.1 回放 undo 后 UI 消失（未解决，已如实记录）

- 现象：回放页按 Ctrl+Z/脚本 undo 后，`currentScreen` 仍是 UIScreen、页面 `visible=true`、`area=960x520`、dashboard 当前 panel 仍是 `UIReplayModePanel`，但整页 UI 不再绘制；画面只剩世界 + BBS 世界 gizmo（Run D/E/F 截图稳定复现）。
- 数据层：undo/redo 恢复值全部正确（`undoRestored=true`），问题纯在渲染/面板状态。
- 已排除：屏幕关闭、页面 detach（`getRoot()` 非空）、dashboard panel 切换（已加检测+恢复，未触发）、页面尺寸为 0。
- 已加兜底：`root==null` 时重开 dashboard；panel 被切换时 `setPanel(this)`；日志打印页面/面板状态供后续定位。
- 影响：回放模式 Ctrl+Z 后必须关闭/重开面板才能继续编辑；数据不会丢失。
- 证据：`docs/verify/round51-replay-move.png`（undo 前 UI 完整）、`round51-replay-{undo,form,list}.png`（undo 后空白，历史回归证据）。

## 8. 剩余风险 / 下一轮候选

1. 导出快照锁（导出期间编辑工程会混版）；BBS 底图 stage 在 overlay 录制路径的产品决策。
2. Esc 手势阶梯尚未覆盖预览/gizmo/分栏拖动（已有 `isPanelResizing()` 但 BBS dock 无取消 API）。
3. 工程版本迁移：缺字段的 version 仍按新版本处理（旧工程 3D 逻辑开关未自动迁移）。
4. 设置页折叠状态仅会话内；UICompTabBar 溢出是翻页而非"活动页滚入可视"。
5. 导出色彩标记/自定义输出预设选择器（队列仍固定 6 个默认模块）。
6. AM 剩余 37 条冷门项；逐点羽化/RotoBezier 动画路径逐帧派生（钢笔报告 §12/§13）。
