# Round 50 — 第二轮审计 + 回放真机验证收尾（v0.95）

> 「假设所有功能都有 bug」第二轮：两个只读审计 subagent 分别深挖
> **时间轴/撤销/选择/持久化** 与 **3D 摄像机/投影/导出/缓存管线**，逐条给 file:line 证据；
> 修复所有 P0/P1 + 大部分 P2。同时完成实体回放交互脚本的真机验证（发现并修掉 3 层 undo 崩溃）。
>
> 验证：**119 suites / 722 tests / 0 failures / 1 skipped**（0.94: 110/669）；
> 客户端仅跑 3 次回放交互脚本（用户频率限制），其余全部无头。
>
> 构建/测试：ASCII junction + 串行 wrapper
> `powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 <task> --offline --console=plain`。

---

## 1. 时间轴 / 撤销 / 选择（交互审计修复）

审计发现（均有 file:line 证据，完整列表见审计摘要）与修复：

### 1.1 P0：Esc 取消拖动只回滚 in/out

- 拖动时 `moveTo/trimInTo` 已经移动了关键帧、图层标记、startTime；`cancelDrag` 只写回 in/out →
  按 Esc 后条回到原位但动画整体错位，下一次保存把错位写盘。
- 修复：拖动起点记录 in/out/startTime，取消时按累计位移反向 `shiftKeyframes/shiftMarkers` 后还原；
  音频按 authored duration（`-1`=自动）还原而不是屏幕上的解析长度；工作区/播放头拖动也支持取消
  （`MODE_SCRUB` 恢复播放头）。
- 同时：拖拽/微移/删除音频/工作区操作补齐 `beginAction`（一次拖动 = 一步撤销），取消时
  `discardCancelledAction()` 丢弃空检查点；锁定图层禁止用左侧抓手重排；取消拖动清双击状态。

### 1.2 P0：连续手势的 undo 基线错一帧

- `beginAction` 后曲线/钢笔/时间轴拖拽会连续修改多帧，`captureSnapshot` 的 `actionInProgress`
  分支在第一帧就把它当成"命令结束"，记录"拖了几像素"的状态为 baseline → 第一次 Ctrl+Z 只回退
  几像素，要按两次。修复：手势进行中（鼠标按下/轨道/曲线 interacting）不消费 `actionInProgress`，
  等手势结束再记录 baseline。

### 1.3 P1：关键帧抽屉的编辑完全不保存/不可撤销

- BBS `UIKeyframes.changed(Runnable)` 回调从未被设置：抽屉里拖/删/粘贴关键帧既不置 dirty、不写盘、
  不进 undo、不失效绿帧，只有 60 s autosave 兜底。
- 修复：`keyframeEditor.view.changed(() -> { rebindInspectorValues(); save(); })`（undo 粒度沿用
  1 s 采样器；BBS 没有编辑开始钩子）。

### 1.4 P1：轨道刻度点击不在 dope sheet 里选中

- `selectKeyframe/navigateKeyframe` 只 `pickKeyframe`（属性面板），dope sheet 的 KeyframeSelection
  没有更新；且 `openKeyframes()` 每次重置抽屉滑动 + 强制重建 sheet（点击刻度会重播动画、重置时间
  缩放、清空多选）。
- 修复：增加 `view.getGraph().selectKeyframe(target)`；`setKeyframeOpen` 只在 closed→open（或
  slide==0）时重播/重置；`refreshSheets` 用 BBS `cacheState()/applyState()` 保留 dope sheet 多选；
  空通道不再出现在 sheet 列表。

### 1.5 P1：撤销重置选择/视图

- undo 后多选塌成单选、音频指向旧对象图、抽屉时间缩放被拉回整片。修复：applySnapshot 前捕获
  selectedIds/audio id/KeyframeState，恢复后按 id 重建（多选、音频、抽屉选中与缩放都保留）。

### 1.6 其它

- 选择图层时若属性栏停留在「合成/输出」页，自动切回「效果控件」（人类预期）。
- 轨道行高 Alt+滚轮持久化；`applyTimelineRowHeight` 写 prefs。

---

## 2. 3D 摄像机 / 操作手感（3D 管线审计修复）

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| Track Z 第一次拖动镜头瞬移 | 推拉半径用 `\|cameraZ\|`，目标点离轴时 ≠ eye→target | 拖动起点记录 `ctStartRadius`（eye 距离）并按它推拉 |
| 按 F 进 BBS 飞行时相机瞬移 | 只同步 rotation/cameraZ，x/y 用陈旧 base | 从 `transformAt(frame)` 初始化 x/y |
| 飞行中绿帧冻结 | 飞行每帧改姿态不走 save()，`CompPrecache` 不失效 | 飞行期间每帧 `CompPrecache.markDirty()` |
| 相机层出现无效「Z 深度」行 | 通用 3D 行与「距离 Z」双入口 | 相机层隐藏 Z 深度行 |

剩余（记录为下一轮候选）：相机 gizmo/目标点拖动使用未变换的合成矩形；`defaultCameraZ` 硬编码 50°；
相机在平面背后时 2D/3D 方向相反；`hasDepth` 已改关键帧采样但 legacy 语义文案需校订。

---

## 3. 渲染缓存 / 导出 / 蒙版（3D 管线审计修复，深度队友）

- **P1-A 预合成进缓存指纹**：`CompRenderCache.signature` 递归子合成（含子层指纹/相机/solo，带
  路径环栈与深度 8 上限）；同时修 `CompLayerSourceCache` 预合成图层栅格指纹。测试 8 例
  （子层编辑/关键帧/两层嵌套/循环终止/12 层深链等）。
- **P1-B 3D 预合成"草稿蓝板"泄漏**：新增 `CompRenderContext.draftViewport`，只有 3D 工作区画板；
  面板预览/导出渲染真实嵌套合成。测试 2 例。
- **P1-C 3D 蒙版丢失**：`MaskCache.getProjected` 把图层局部贝塞尔路径经 projection·view·world
  逐顶点投影成像素蒙版（feather/expansion 按投影比例、矩阵入缓存键）；`CompRenderer` 直接路径
  （entity / 无缓存 raster 的 precomp）应用投影蒙版。GL 像素测试 2 例（含旋转 28°）。
- **P1-D AE depth_* 静默失效**：非 captureDepth 帧复用 `BbsPreviewBridge.depth()`；仍无深度时
  WARN 一次并暴露降级 API；**面板已接入**：状态栏显示中文降级原因，切到 BBS 实时底图时
  `resetDepthEffectsStatus()` 重新检测。
- **P2**：导出 frames 残留清理；输出帧号稳定映射（预设 fps≠合成 fps 不再重复/丢帧）；
  预缓存尺寸统一（奇数尺寸不一致时丢弃并 WARN）；绿帧 BBS 底图透明 stage；TextContent 字段
  （时间码/计数/打字/随机）进两套指纹；`hasDepth` 用关键帧 z 采样。
- 报告：`docs/RENDER-CACHE-AUDIT-FIX.zh.md`。

---

## 4. 实体回放交互真机验证（3 次 run 的完整记录）

脚本：`-PbbsaeView=replay` 自动打开回放页并执行 MOVE（非 0 tick，gizmo 同一路径写位置+旋转）→
UNDO → REDO → FORM（换模型）→ LIST（复制回放），4 张截图 + 通道摘要日志。

| Run | 结果 | 发现 |
| --- | --- | --- |
| A | MOVE/FORM/LIST 正常；UNDO/REDO NPE | 借用的 UIFilmPanel 未挂树，undo apply 时 `getRoot()` null |
| B | MOVE/FORM/LIST 数据正常；UNDO NPE 更深；截图 UI 空白 | undo 条目的 `uiBefore` 在"写回时"采集，也需要 root；临时挂载/卸载破坏 UI 树 |
| C | **数据全部通过**：`UNDO restored=true`、`REDO` 一致、`done failed=false` | 常驻挂载方案导致回放页 UI 不渲染（截图只有世界+gizmo） |

**最终修复**：filmPanel 挂到 **screen root 下的隐藏 holder**（不进回放页 children/layout，在
`update()` 中挂载/`disappear` 卸载），回放页 keyframe editor 使用独立 undo id；临时挂载保留为兜底。
Run C 数据层已证明 undo/redo 链路正确；**UI 可见性修复未再真机截图**（用户频率限制）→
下次真机第一检查点：标题条/列表/属性/轨道完整，再测 Ctrl+Z / 拖动 / 换模型 / 列表增删。

证据：`docs/verify/round50-replay-move.png`（Run B，UI 完整）、`round50-run2-*`（Run B）、
`round50-run3-*`（Run C 空白 UI 历史，仅作回归证据）；日志
`_tools/runclient-{replay-interact,r50b,r50c}.log`。

---

## 5. 效果 / 钢笔 / 动效 / 深度

- **效果**：6 个 AM 变形效果（pinchbulgeinside/wavewarp2/turbulentdisplace3/tileshift2/
  stretchsegment/ominoglass），零点契约 + 像素测试 + 缩略图；`repeat.path` 与文字 6 项经评估
  **不做假实现**，原因写入 `EFFECTS-AM-GAP.zh.md` §3.5。gap：146 定义 / 113 匹配 / 缺失 48。
- **钢笔**：修 3 个集成 bug（V/A 不退出钢笔、删图层/进 precomp 留下悬空 session、钢笔+摄像机工具
  同时激活）；`structureChanged` 通知 dope sheet 刷新（Lead 已加 `penRefreshSheets` 钩子）；
  mask expansion/opacity 关键帧 2D/3D 解析确认无 bug；3D 透视手柄投影回归；逐点羽化/RotoBezier
  评估后不做（报告 §12）。
- **动效**：曲线编辑器补齐 hover/选中插值、播放头呼吸、图表 HUD 淡入、拖动 HUD；关键帧抽屉光标
  光晕；动效逻辑测试 33 个。
- **深度**：导出/预览共用同一 depth 分支（5 例 0 像素差）；AUTO 策略量化（pass 成本 + 判定原型，
  默认仍 false）；near/far 极端值与预合成组合 0 像素差。

---

## 6. 验证记录

| 项目 | 结果 |
| --- | --- |
| 全量测试 | **119 suites / 722 tests / 0 failures / 1 skipped**（0.94: 110/669） |
| 深度 | 37 既有 + 23 新增（缓存审计 21 + 投影蒙版 2）全绿 |
| 回放 | 75 tests / 11 suites；Run C 数据验收全过 |
| 钢笔 | 12 类 96 tests |
| 动效 | 33 个纯逻辑测试 |
| 效果 | 6 新效果逐条零点/像素断言 |
| 客户端 | 回放交互脚本 3 次（A/B/C），仅 C 数据全过；UI 修复未再截图 |

## 7. 剩余风险 / 下一轮候选

1. 回放隐藏 holder 修复的 UI 可见性真机确认（第一优先）；gizmo 真实鼠标拖动、模型库点击、
   列表拖拽的真机交互。
2. 相机 gizmo/目标点坐标系、`defaultCameraZ` 统一 FOV、相机在平面背后的 2D/3D 镜像语义。
3. 时间轴持久化（zoom/scroll/keyframeOpen/selected ids/curve 视图）、每帧 `allKeyframes()` 与
   green-bar BitSet 的全量遍历优化（大工程 200+ 轨）。
4. `perPixelDepth` AUTO 默认策略落地（需真机性能确认）。
5. AM 剩余 48 条（棱镜/程序化/drawing 家族，性能开销大）；`repeat.path`/文字项需新参数模型。
6. 预合成编辑后 UI 立即失效（递归指纹已保证正确性，可选加显式 invalidate 以清旧绿条内存）。
