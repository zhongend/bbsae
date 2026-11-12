# BBS AE 更新日记

## 0.95 — 2026-11-13

这一轮是第二轮系统性审计（时间轴/撤销/选择 + 3D 摄像机/投影/导出/缓存管线）与回放交互真机验证的
收尾：修掉 15+ 个影响操作的 P0/P1，补上 6 个 AM 变形效果，并为「预览=导出」补了渲染缓存/蒙版/
预合成一致性。客户端总共只跑了 3 次回放交互脚本（前两次暴露 undo 崩溃并层层修复，第三次数据全过），
其余全部无头验证。

**测试**：119 个测试套件 / 722 项测试全绿（0.94 基线 110 / 669，1 skipped）

### 时间轴 / 撤销 / 选择（第二轮交互审计）

- **Esc 取消拖动现在完整回滚**：此前只还原 in/out，关键帧、图层标记、startTime、音频 authored
  duration、工作区手柄都不回滚（拖完按 Esc 再保存 = 动画整体错位）。现在按拖动起点快照完整还原，
  并支持取消播放头拖动（scrub）。
- **连续手势的 undo 基线**：曲线/钢笔/时间轴拖拽跨多帧，`beginAction` 的第一帧被当成"命令结束"
  导致第一次 Ctrl+Z 只回退几像素。采样器现在等手势真正结束才记录基线（一次拖拽 = 一步撤销）。
- **时间轴拖动/微移/删除音频/工作区补 undo 检查点**；取消拖动会丢弃空检查点（不产生无操作撤销）。
- **抽屉里的关键帧编辑接上 save/undo/dirty**：BBS `UIKeyframes.changed` 回调此前从未设置，抽屉里
  拖/删/粘贴关键帧不落盘、不进 undo、不失效绿帧；现在接上并同步刷新属性面板。
- **关键帧选择同步**：点轨道刻度现在会真正在 dope sheet 里选中（`graph.selectKeyframe`），且
  `openKeyframes()` 不再每次重播抽屉动画/重置时间缩放；`refreshSheets` 用 BBS
  `cacheState/applyState` 保留多选。
- **撤销保留选择与视图**：undo 后按 id 重建多选图层、音频选中、抽屉的选中键与时间缩放（此前多选
  塌成单选、音频指向孤儿对象、抽屉时间轴被拉回整片）。
- 其它：锁定图层不能用左侧抓手重排；轨道行高 Alt+滚轮持久化；空通道不再出现在 dope sheet；
  重新切图层时自动把属性栏从「合成/输出」切回「效果控件」（人类预期）。

### 3D 摄像机 / 操作手感（3D 管线审计）

- **Track Z 半径错误**：推拉用 `|cameraZ|` 而不是 eye→target 距离，目标点不在 z 轴时第一次右拖
  会把眼睛瞬移数百像素；现在用拖动起点半径。
- **进入 BBS 飞行时相机瞬移**：x/y 用了 base 值而非当帧插值；现在从 `transformAt(frame)` 初始化。
- **飞行中绿帧冻结**：飞行每帧改姿态却不 markDirty，预渲染过的合成画面完全不更新、退出飞行才
  跳变；现在飞行期间持续失效预缓存。
- 相机层不再显示无效的「Z 深度」行（应使用「距离 Z」）；相机 gizmo 与目标点拖动等审计项记录在
  `docs/ROUND50-AUDIT-FIXES.zh.md` 的下一轮候选。

### 图层效果（需求 2-d 续 4）

- **6 个 AM 变形效果**：`am_pinch_bulge_inside`、`am_wave_warp`、`am_turbulent_displace_3`、
  `am_tileshift_2`、`am_stretch_segment`、`am_omino_glass`（数据驱动 + 零点契约 + 像素测试 +
  缩略图）。AM gap：146 定义 / 匹配 113 / 真正缺失降至 48。
- `repeat.path` 与文字 6 项：明确评估为「需要新的效果参数/路径引用模型 + UI」或「属于图层属性
  动画预设」，本轮**不假实现**，原因写入 `docs/EFFECTS-AM-GAP.zh.md` §3.5。

### 实体回放（需求 5 真机验证收尾）

- 新增脚本化交互验证：非 0 tick 用 gizmo 同一路径写位置+旋转 → undo/redo → 换模型 → 复制回放，
  分 4 张截图并打印通道摘要。
- 修掉 **3 层 undo 崩溃**：① 回放模式下借用的 UIFilmPanel 未挂树 → BBS undo apply 时
  `getRoot()` 为 null；② 更深的采集侧：写回时同样需要 root（`uiBefore=null` → 应用时 NPE）；
  ③ 临时挂载/卸载破坏 UI。最终用 **screen root 下的隐藏 holder 常驻挂载**（不进回放页 children）
  + 回放页独立 keyframe undo id，Run C 数据验证全过：`UNDO restored=true`、`done failed=false`。
- **残余风险**：隐藏 holder 方案尚未再真机截图验证 UI 完整性（客户端频率限制；下次真机首先确认）；
  Run C 截图只保留为历史证据。gizmo 真实鼠标拖动/模型库点击/列表拖拽的真机交互仍待补。

### 界面 / 停靠 / 响应式（需求 8 补完）

- **窄面板 tab 溢出**：`UICompTabBar` 放不下时隐藏尾部 tab 并显示「»」循环切换按钮（此前溢出 tab
  画在邻居面板上、点击会抢事件）；纯函数 `CompUi.tabStripLayout` + 单测。
- **dock 分栏热区与内容重叠**：时间轴内容在 dock 模式整体下移 6px（14px splitter 每侧 7px 热区不再
  吃掉工具条顶部点击）；素材面板滚动条内缩 6px（拖滚动条不再变成拖分栏，用户报告的 bug 类）。
- **布局菜单新增「效果库 + 属性停靠到预览右侧」**：一键切到用户要求的"效果列表在预览右侧"布局；
  `CompDockSource.effectsRightLayout` + 单测。
- 选择图层时属性栏自动回到效果控件（若停留在合成/输出页）。

### 渲染缓存 / 导出（3D 管线审计修复，详见 docs/RENDER-CACHE-AUDIT-FIX.zh.md）

- 预合成内容进入渲染缓存指纹（此前预合成内部编辑后父合成预览/绿帧显示旧帧，导出却是新的）；
- 3D 预合成层的"草稿蓝框"不再泄漏到合成面板预览；3D 蒙版在 bake 被跳过时不再静默丢失；
- AE 路径 depth 效果的可见降级/采集、导出 frames 残留清理、非合成帧率取整、预缓存尺寸一致性、
  文字内容字段进指纹、`hasDepth` 采样关键帧 z 等（见文件与测试）。

### 其它

- 逐像素深度 AUTO 策略：量化 pass 成本 + 判定原型（≥2 透视层 + AABB 重叠 + 法线不平行），
  默认仍 false，建议 AUTO 作为未来默认（`docs/DEPTH-GHOST-FIX.zh.md` §4.6）。
- 钢笔：3 个集成 bug（V/A 不退出、删除图层/进 precomp 悬空 session、钢笔+摄像机工具互斥）、
  结构变化通知 dope sheet、mask 关键帧解析测试、3D 透视手柄回归；逐点羽化/RotoBezier 评估后
  不做（报告 §12）。
- 曲线页/关键帧抽屉非线性动效收尾（hover/选中插值、播放头呼吸、拖动 HUD、33 个动效逻辑测试）。
- 真机截图：`docs/verify/round50-replay-move.png`（Run B，UI 完整；数据与 Run C 相同）、
  `round49-narrow.png`（响应式）、replay Run C 历史证据 `round50-run3-*`（空白 UI 回归，已修复）。

## 0.94 — 2026-11-13

这一轮是「按假设所有功能都有 bug」的系统性审计与修复：13 个 AM P1 效果补齐、图层效果数据层的
4 个 P1 清零、逐像素深度收尾、主界面交互层 4 个 P1 修复，以及回放/钢笔/动效的收尾批次。
所有改动都以无头像素测试或纯逻辑测试钉住，并额外跑了一次窄窗口真机截图验证响应式修复。

**测试**：110 个测试套件 / 669 项测试全绿（0.93 基线 96 / 565，1 skipped）。

### 图层效果（需求 2-d 续 + 数据层 P1）

- **AM P1 批次 13 个新效果**（全数据驱动，每个都有零点契约和像素测试）：
  - 模糊：`am_unsharp_mask`、`am_lens_blur_2`、`am_streak_strips`、`am_warp_blur`、
    `am_chromatic_zoom_blur`；既有 `am_spin_blur` 角度范围/步进对齐 AM `spinblur2`；
  - 调色：`am_spectral_map`、`am_soft_glow`、`am_dark_glow`、`am_light_glow`、`am_color_hot`；
  - 变形：`am_pinch_bulge_2`、`am_swirl_4`、`am_hex_rotate`。
  - AM gap 审计更新：AM 127 定义 / 匹配 107 / 真正缺失降至 54。
- **REPLICATE 直通 alpha 修复**：复制器家族此前把直通 alpha 当预乘使用，不透明度 0.5 会让复制体
  发黑、文字/发光软边重复后出现黑边（预览与导出同时错）。改为预乘空间累加 + 输出统一
  un-premultiply，像素测试覆盖 α=128 与 opacity=0.5。
- **预览 proxy 像素单位修复**（"预览=导出"硬承诺）：RGB 分离的 Texel、投影/内阴影偏移
  （`PassRenderer` 新增按参考尺寸归一化重载）、图层样式 `inner()` 的距离/模糊半径统一按合成
  像素换算；1:1 与 0.5 代理档渲染采样一致。
- **抖动/震屏参数失效修复**：`CompScene.shakeOf()` 此前只读关键帧通道、完全忽略滑块基值 →
  `CompLayer.effectValue(...)` 统一「基值 + 通道覆盖」，旧 `effectParam` 标 `@Deprecated` 委托。
- **动画预设不再清空/注入内容通道**：`CompAnimationPreset` 采集/应用改为白名单（排除
  `fx/ mask/ shape/ text/`），目标图层的蒙版/形状/效果动画不再被一键清掉，源层 fx 通道也不
  再注入目标。
- **曲线编辑器改 fx 关键帧写回**：`CompCurveChannel.insert/setKeyValue` 对 `fx/<id>/<param>` 镜像
  回 `CompEffect.params`；另外补齐 `shape/<id>/fillGradientAngle` 的写/读路径。
- **删除效果清理通道 + id 不再复用孤儿 id**：删除效果时清除 `fx/<id>/...` 关键帧，避免
  "幽灵轨道"与 3D 每帧重烘焙；`CompStore.uniqueEffectId` 跳过仍有通道的 id，新效果不会继承旧动画。
- 顺带：启用/禁用效果、移除调色、移除输出预设补 undo/落盘；缩略图生成器拒绝退化空白图。

### 逐像素深度（需求 3 收尾）

- 删除 `DEPTH_PLANE` 的 `if (source.a < 0.004) discard;`（会丢 alpha=1/255 抗锯齿边），
  `DepthPlaneGhostRegressionTest` 容差从 1 收紧到 **0** 仍通过。
- `DepthPlaneCompositor` 所有 GL 状态改走 `GlStateManager`（消除"实际状态变、缓存没变"的同类
  根因）；异常路径 finally 恢复深度/混合/颜色掩码并绑回调用者 framebuffer+viewport；池化
  target 增加 `pooled/deleted` 守卫防 double release / stale release。
- 新增 17 个开关路径测试（`PerPixelDepthSwitchTest` 7 + `PerPixelDepthRoutingTest` 6 +
  `DepthPlaneStateRobustnessTest` 4）：off/on/painter 三路径 0 像素差、AE 混合 2D+3D、legacy3D、
  池化跨 composition、半透明层只合成一次、强制开关行为不变。
- **设置 → 开发者新增「逐像素深度（主合成）」开关**：默认仍关闭（性能优先），打开后相交 3D 图层
  按真实像素深度遮挡；切换即时生效并清预渲染缓存。报告 `docs/DEPTH-GHOST-FIX.zh.md`。

### 主界面交互层审计修复（需求 4/7/8，4 个 P1）

审计基准 `UICompositorPanel`（约 2 万行）+ `CompUi`/`CompDockSource`/`UICompTrackTimeline`，逐条
给出代码证据后修复：

- **P1 焦点模式布局被写进工程**：进入焦点（F）会把临时树写入 `dockLayout/dockHiddenPanels` 并落盘，
  重开工程只剩预览+时间轴（"面板消失"）。修复：焦点模式期间 `CompDockSource.suspendPersist(true)`，
  退出/关面板/切工程/应用预设时恢复；undo 快照也剥离工作区字段，Ctrl+Z 不再跳布局。
- **P1 预览右上角图标列压住预览条**：图标与浮动条共用 20px 行且无碰撞处理，preview <458px 时点
  "线性：自动"会打开摄像机设置。新增 `CompUi.previewRailLayout`：图标从左侧降级、预留预览条最小
  宽度，隐藏图标不占命中；窄窗口不再误触（真机截图 `docs/verify/round49-narrow.png`）。
- **P1 关键帧抽屉把轨道区挤成负高度**：焦点/矮时间轴下轨道直接消失、抽屉盖住工具条。抽屉高度上限
  改为 `时间轴高 - 26(标题) - 56(最小轨道) - 5(手柄)`，放不下时自动收起并提示。
- **P1 Esc 优先级错误**：飞行/摄像机工具拖动/轨道拖拽中按 Esc 不取消，反而关抽屉/清选择/退预合成。
  改为显式优先级链：钢笔 → 飞行 → 摄像机工具拖动 → 轨道拖拽（新增 `cancelDrag()` 恢复原值）→
  摄像机工具关闭 → 弹层 → 抽屉 → 预合成 → 选择。
- P2 修复：切换工程前 `flushSave()`（600ms 防抖窗口内的编辑不再串写/丢失）；撤销快照保留当前
  工作区；`applyLayoutPreset` 复位焦点状态；legacy 渲染队列页高度补满；`UICompTabBar` 增加父区域
  命中守卫（窄面板溢出的 tab 不再抢邻居点击）；`UiPageTransition` 首帧不再闪黑纱；
  快捷面板宽度/时间轴页签变为持久化；顶栏「效果库/工程」按钮纳入响应式布局（窄窗不再压住播放键，
  并新增 `CompUi` 单测锁住）。
- dock 交互补齐 BBS 原版快捷键：Ctrl+Tab / Ctrl+Shift+Tab 循环堆叠标签，布局菜单同步提示。

### 实体回放（需求 5 收尾）

- 数据层修复 10 项：内容等值选错回放（`ValueGroup.equals` 陷阱 → 身份比较）、undo/换影片后的陈旧
  replay 对象写回脱管记录、等长列表变更检测不到、NaN/Inf tick 污染 runner、NaN framing 黑屏、
  坏相机矩阵导致全裁剪、pick FBO 创建失败从 UI render 抛异常、pick id 公式重复、自动关键帧错 tick、
  空状态 warn 噪音。
- 新增 `ReplayListOps`/`ReplayFraming`/`ReplayPickIds` 纯函数与 33 个测试（replay 包 71 tests）。
- 仍待真机（交互类）：真实 gizmo 拖动写回 + 原版 undo、模型库换模型、列表增删/重排 UI、
  播放头边界 seek；见 `docs/REPLAY-MODE-REPORT.zh.md` §7.1。

### 钢笔（需求 6 收尾）

- KeyframeLoop 保真：通道搬移/新增点/Set First Vertex 轮转时保留 loops。
- Set First Vertex（AE 语义）：闭合路径几何轮转 + 已动画路径 6N 通道轮转，关键帧处几何不变，1 步 undo。
- 修复 2 个真机前 bug：动画路径的叠加/命中/拖拽读 base 而非当前帧求值；Shift 45° 被屏幕+局部
  两次约束（旋转/非均匀缩放/透视图层方向偏）。
- 钢笔相关 10 个测试类 90 tests。

### 非线性动效（需求 8-C 收尾）

- `UiMotion.tickFrame()` 全局每 60 帧 prune（含闲置 tween/flash），关闭动效/强度 0 立即归位。
- 补齐资源浏览器、图层库、开始页、引导页、效果浮层/导入浏览器的入场与关闭过渡
  （`beginClose` 淡出后再移除，回调只在渲染结束后执行）。
- 动效类纯逻辑测试合计 27 个。

### 其它

- 新增验证钩子 `-PbbsaeDebugDock`（dock 布局日志）、`-PbbsaeForceDepthPlane`（逐像素深度 A/B）、
  `-PbbsaeWindow=<WxH>`（精确窗口尺寸，响应式截图用）；正常游戏不生效。
- 真机截图：`docs/verify/round49-narrow.png`（窄窗口顶栏/图标列/停靠布局降级）、
  `round48-dock-layout.png`、`round48-dock-stack.png`、`round48-depth-on.png`、`round48-replay-models.png`。
- 报告：`docs/ROUND49-AUDIT-FIXES.zh.md`、`docs/EFFECTS-FIX-REPORT.zh.md`、`docs/DEPTH-GHOST-FIX.zh.md`、
  `docs/UI-MOTION-REPORT.zh.md`、`docs/PEN-TOOL-REPORT.zh.md`、`docs/REPLAY-MODE-REPORT.zh.md`、
  `docs/EFFECTS-AM-GAP.zh.md`。

## 0.93 — 2026-11-13

这一轮把用户八条需求里剩下的硬骨头做完了：**面板像 BBS 原版一样可停靠/组合**（需求 8-B，
直接复用 BBS `UIDockLayout`）、**逐像素深度重影本体修复**（需求 3，找到颜色附件从未被真正
清空的根因）、**AM repeat 家族 + 调色首批**（需求 2-d）、**钢笔动画路径增删点安全**（需求 6 收尾）、
**实体回放真机可验证性/性能/空状态**（需求 5 收尾）、**12 个 UI 文件的非线性动效**（需求 8-C）。
所有改动都经过无头测试 + 真机截图验证。

**测试**：96 个测试套件 / 565 项测试全绿（0.92 基线 90 / 511，1 skipped）；真机截图见
`docs/verify/round48-*.png`。

### 面板停靠/组合（需求 8-B，重大 UI 改造）

- **直接复用 BBS 2.7 原生 `UIDockLayout`**（`UIFilmPanel` 同款系统，不是仿写）：效果/素材栏、
  预览、属性栏、时间轴变成 4 个可停靠面板——拖动标题条/标签即可拆分、合并成标签堆叠、
  隐藏、最大化；分栏可拖拽；布局锁可一键锁定；布局撤销/重置齐全。
- `CompDockSource`（新增）把 dock 树映射到工程文件（`dockLayout`/`dockHiddenPanels`/`dockUnlocked`），
  按帧批量 flush：拖分栏不会每帧写盘、不会产生 undo 步、不会清预渲染缓存；项目切换自动重载。
- 顶栏新增「布局」按钮（始终可见）：**左键锁定/解锁拖拽**，**右键菜单**含重置默认布局、
  撤销布局改动、最大化鼠标下窗口、恢复隐藏面板；快捷键 **Ctrl+Alt+Z** 撤销布局、
  **Ctrl+空格** 最大化鼠标下面板。
- **工作区预设映射成 dock 树**：标准/聚焦/紧凑/宽屏四个 onboarding 预设仍然有效；聚焦预览
  用临时树（预览+时间轴）且不写入工程布局；按 F 恢复。
- 快速变换面板保留在时间轴面板内部右侧（随选择显隐、随面板拖动），不单独注册成 dock 面板。
- **回退开关**：设置 → 开发者 →「停靠式面板（BBS 原版）」可关闭，退回 0.92 的固定三栏
  splitter（旧 `applyLayout` 完整保留为 `applyLegacyLayout()`），切换后重开合成器面板生效。
- 页面切换动效接线：左栏三页 / 属性七页 / 时间轴曲线页切换时有 0.16–0.18s 的非线性 veil
  （`UiPageTransition`），受 `CompPrefs.animations` 控制。
- 真机验证：`docs/verify/round48-dock-layout.png`（默认停靠布局）、
  `round48-dock-stack.png`（左栏与属性栏标签堆叠、解锁拖拽条）。
- 计划/实施记录：`docs/DOCK-LAYOUT-PLAN.zh.md`。

### 逐像素深度重影本体修复（需求 3）

- **根因不是深度平面数学**：`DepthPlaneCompositor` 复用的 FBO 颜色附件从未被真正清空。
  深度预写 pass 用裸 `glColorMask(false,…)` 后，`GlStateManager` 的 COLOR_MASK 缓存仍是 true，
  随后 `clearColorOnly` 的 `_colorMask(true,…)` 被缓存短路 → `glClear(GL_COLOR_BUFFER_BIT)`
  静默失效；target 池化复用导致「当前层 ∪ 历史所有层/帧残留」被每层末尾再合成一次。
  摄像机移动时就是星形/多边形重复、半透明边缘叠条带。
- 修复：每次 composite 前真正清颜色（深度保留跨层共享）；target 写入期间显式关混合（混合只由
  `PassRenderer.draw` 做一次）；颜色掩码改走 `GlStateManager`；保存/恢复深度/混合状态；
  `GlFramebuffer.clearColorOnly()` 先裸 `glColorMask(true)` 再同步缓存。
- 新增像素级回归 `DepthPlaneGhostRegressionTest`（4 个：静态帧 depth-on==depth-off、移动相机
  逐帧不累积、池化 target 不残留上一帧、半透明层每像素只合成一次）；修复前 4/4 失败。
- 真机 A/B 截图（强制开启逐像素深度、`____bug` 工程）：`docs/verify/round48-depth-on.png`，
  星形/多边形单帧干净无重复。`perPixelDepth` 默认值仍保持关闭，留待用户真机确认性能后开启
  （建议随后改 true；见 `docs/DEPTH-GHOST-FIX.zh.md`）。

### 图层效果：AM repeat 家族 + 调色首批（需求 2-d）

- 新增 5 个数据驱动效果（`AmEffectDef.neutralAt` 支持「恒等不是全 0」的零点契约）：
  - P0 repeat 家族：`am_repeat`（重复：count/offset/递增 angle/scale/alpha）、
    `am_repeat_radial`（环形：radius/orientation/start_angle/sweep）、
    `am_repeat_scatter`（圆盘随机散布：radius/evolution/seed）——复用合成空间 REPLICATE
    pass，2D/3D 都真正无限延伸，不受图层矩形裁剪；
  - P1 调色首批：`am_rgb_mixer`（RGB 混合/gamma/offset，红色恒等三元组零点）、
    `am_solid_color`（纯色，alpha=0 严格恒等）。
- 缩略图生成器修复：旧规则会接受「全空/近乎单色」的退化预览并落盘空白图；现在要求可见且非
  近乎单色，失败则缩小像素参数重试。重新生成 5 张新缩略图 + 修正 4 张旧退化图。
- AM gap 审计更新：AM 127 条定义、自动匹配 94、真正缺失降至 64（`docs/EFFECTS-AM-GAP.zh.md`，
  逐条清单 `.generated.md`）。未做：`repeat.path`（需路径几何接入效果参数域）、剩余模糊/变形/文字批。

### 钢笔动画路径增删点安全（需求 6 收尾）

- 已动画路径不再禁止增删点：收集受影响段两端点 8 个通道的所有关键 tick ∪ 播放头，逐 tick
  求值整条路径 → de Casteljau 保形分割 → 回写新点/手柄，未受影响点的通道对象整体搬移
  （tick/值/插值/手柄/style/duration 全保留）。**每个既有动画键处曲线逐像素不变**（测试用
  点到折线距离 < 0.05px 验证）。
- 末尾加点按 effectcraft `CountOp::Insert` 规则（其它键取最后一段 t=0.5 中点）；删点清空该点
  通道、后续索引整体下移，剩余点在键帧处值精确不变；`<3` 点自动断开闭合；每次增删点仍是一步 undo。
- 检查器按钮接入动画安全 API：`UIPenTool.appendMaskPoint/appendShapePoint/removeMaskPoint/removeShapePoint`。
- 12 个新测试（`AnimatedPathRemapTest`）；报告 `docs/PEN-TOOL-REPORT.zh.md` §9/§10。

### 实体回放模式真机验证/健壮性（需求 5 收尾）

- 新增真机验证钩子 `ReplayDebugHook`：`-PbbsaeView=replay` 时在 60 tick 自动用 dashboard 唯一
  `UIFilmPanel` 填充影片并打开实体回放页（不 new 面板、主线程同步 load），影片来源依次为
  `-Dbbsae.debugReplayFilm` → 磁盘上有 replay 的影片 → 内存合成 3 个原版生物 replay，保证
  fresh run 也能截到模型/gizmo；日志 grep `Replay debug`。
- 性能：`ReplayCulling` 视锥裁剪（>阈值才裁剪、选中回放永远画、颜色/拾取共用一次可见性）、
  `ReplayLogLimiter` 失败日志限流（5s + 抑制计数）。
- 标定：`ReplayCalibration` 集中 `pixelsPerBlock/flipZ/yawOffset/pitchOffset/cull*`，
  全部系统属性可覆盖且严格解析（非法回落/越界 clamp），HUD 显示裁剪计数。
- 空状态/健壮性：无影片/无回放/未选中/无模型/视野外/FBO 失败均有可读文案（失败红字带原因）；
  修复换影片再进面板不重绑；修复「鼠标不在视口内时截图没有 gizmo」（stencil pass 始终跑，
  读回按光标位置决定）。
- **修复 P0 崩溃**：`selectReplay` 无条件 `scrollToReplay`，而 BBS `scrollToReplay` 会同步触发
  列表选择回调 → `selectReplay` → 递归到 StackOverflowError（一点第一条 replay 就崩）。现在
  区分入口（列表回调不滚动、程序化选择滚动）+ re-entrancy guard。
- 真机验证：`docs/verify/round48-replay-models.png`（模型 + 原版三轴 gizmo + 列表/属性/dope sheet）。

### 非线性动效覆盖（需求 8-C）

- 12 个独立 UI 文件统一接入 `UiMotion`：按钮 hover 上浮/按下内缩回弹/禁用渐隐
  （含丢失 release 的物理键轮询兜底）、图标按钮缩放、tab 下划线下沉、效果库行 hover/选中插值 +
  错峰入场、渲染队列状态呼吸、导入浏览器背板淡入 + 行错峰、预设卡片错峰、曲线点/色轮 hover
  插值、clip bar hover/吸附线脉冲、快捷面板分组揭示、效果浮层统一 prune。
- 纪律：每帧每宿主只 tick 一次；`animations=false` 或强度 0 立即归位；位移/缩放乘
  `motion.intensity()`；稳定 key 不每帧拼字符串；每 60 帧 prune + 上限清理防泄漏；不改 BBS
  原生控件、不改命中区域、不写工程数据/不进 undo/不影响导出。
- 新增 10 个纯逻辑测试（`UiFeedbackMotionTest`）；报告 `docs/UI-MOTION-REPORT.zh.md`。

### 其它

- 顶栏右侧新增「布局」按钮后，`CompUi.headerLayout` 重新排布并加测试防重叠。
- `CompDockSourceTest` 覆盖供应商式 settings、项目切换 reload、预设/聚焦树只引用已注册面板。
- 开发者页新增停靠式面板开关；新增 `-PbbsaeDebugDock`/`-PbbsaeForceDepthPlane` 验证钩子
  （仅调试，正常游戏不生效）。

## 0.92 — 2026-11-12

本轮处理用户八条需求里最核心的六条：3D 摄像机逻辑、图层效果、实体回放、钢笔工具、
界面操作逻辑与面板自适应/动效基础设施。验证：90 个测试套件 / 511 项测试全绿
（0.91 基线为 72 / 379），另有真机截图验证（见 `docs/verify/round47-*.png`）。

### 3D 摄像机逻辑改成 AE 语义（需求 1）

- **只有开启「3D 图层」的图层受摄像机影响**：2D 图层始终屏幕附着，摄像机倾斜/推拉不再
  连带移动普通图层（`CompScene.usePerspective`）。旧版（0.91 之前「只要镜头有倾斜或任意
  图层 z≠0，所有图层都被投影」）收进 **设置 → 开发者 → 图层 3D 逻辑**，旧工程可一键切回；
  切换立即生效（计入 `CompRenderCache.signature` 缓存键）。
- 顺带修正 `CompScene.hasDepth`：摄像机自己的 z（推拉距离）不再被当成内容深度。此前
  「显示/隐藏摄像机行」会改变整帧是否进入 spatial 投影路径，是隐藏的开关副作用。
- **没有摄像机时右键拖动预览框不再自动创建摄像机**：进入 3D 空间 / 右键拖动 / 看穿视图
  都只显示提示；`CompCameraOps.ensureCamera(doc, time, allowCreate)` 是唯一创建守卫。
- **AE 摄像机操作**：预览右侧新增「新建摄像机」（已有摄像机时只选中它）、「AE 摄像机工具」
  （左键环绕 point-of-interest、中键 Track XY、右键 Track Z 指数推拉）与「摄像机设置」齿轮
  （打开 设置 → 开发者）。BBS 飞行摄像机自动退回 yaw/pitch 环绕。工具开启时预览不再
  选中/拖动图层，也永远不会创建摄像机。
- 设置 → 新增**开发者**页：3D 逻辑切换、无摄像机拖动自动创建（旧版兼容，默认关）、
  AE 摄像机工具开关、新摄像机默认 FOV、当前状态与动态版本号（设置页脚不再写死 0.23）。
- 测试：`CameraLogicModeTest`（AE/旧版投影语义、无摄像机手势不创建、显式创建只加一个、
  环绕半径/俯仰钳制/dolly）、`Camera3DTest` 修正（原测试靠摄像机 z 误判 spatial）。

### 图层效果逻辑修复（需求 2）

- **文字图层加效果后上下颠倒**：根因是 3D 烘焙采样分支把图层 UV 直接当纹理 UV，漏了 FBO
  的 V 翻转（2D 正常、3D 全部镜像，文字最显眼）。现在统一走 `localToCompUv`。
  `EffectOrientationTest` 用 12 个内容采样效果做像素级方向验收。
- **3D 模式下效果无效**：修 4 个根因——上述 V 翻转；投影后效果误用图层世界矩阵
  （glass/置换图/光包裹改合成空间）；`AmEffectDef.padding` 支持域从未被消费（3D 模糊/发光/
  长阴影被图层矩形硬裁）；AM 纯模糊半径未按代理栅格 scale 换算。
- **重复类效果被图层大小裁成「蒙版」**：按 AM 语义审计后，只有 repeat 分类是图层复制器
  （其余生成/平铺/镜像本就在图层内采样）。新增合成空间 `REPLICATE` 全屏 pass，
  `grid_repeat / random_repeat / am_grid_repeat / am_line_repeat` 真正复制整层横跨合成，
  3D 走投影后 pass；`EffectRepeatTest` 钉住「复制体出现在原图层框之外」。
- **AM 参数对齐**：`_tools/am-gap/compare.py` 对比 277 条 AM 定义与 122 条本项目定义，
  产出 `docs/EFFECTS-AM-GAP.zh.md`（人工结论）+ `generated.md`（逐条清单）；本轮对齐
  11 处范围/步进（hue、glow 半径、pixelate、tile、hex 系列、四色渐变 alpha、角度步进）。
- 报告：`docs/EFFECTS-FIX-REPORT.zh.md`。
- 逐像素深度：`perPixelDepth` 已纳入缓存签名（切换后预览立即刷新）；主合成逐像素深度
  仍默认关闭（0.87–0.89 的摄像机预览重影修复保留），3D 工作区的逐像素深度保留。

### 实体回放编辑模式（需求 5）

- 主界面 dashboard 面板条新增「实体回放」入口；合成器预览工具栏新增 PLAYER 按钮直达。
- 新增 `client/replay/` 包（11 个文件）：3D 视口用私有颜色+深度 FBO 通过
  `FilmEntityRenderer` 显示影片里所有 replay 模型（comp↔world 映射、Y 翻转），
  相机复用 `CompViewportCamera`（右键环绕 / Shift+右键或中键平移 / 滚轮对准光标缩放），
  左键 stencil 拾取选模型。
- **原版回放编辑照搬**：底部是 BBS 原版 `UIKeyframeEditor` + `TrackCatalog`/`UIFilmKeyframes`
  轨道（位置/状态/快捷栏/装备/操作/速度/骨骼），左侧原版回放列表、右侧原版属性面板；
  拖动播放头模型姿态跟随。
- **原版 gizmo**：`Gizmo.INSTANCE` + `UIReplayPropTransform` 写回 x/y/z 与
  yaw/headYaw/bodyYaw/pitch；Ctrl+Z/Y 走原版 `filmPanel.undo()/redo()`。
- **原版模型库**：`UIFormPalette.open(...)` 选模型 → 拷贝到 replay.form → 播放头自动打
  位置/角度关键帧（复刻 AE 的模型导入工作流）。
- 回退路线：面板顶部「原版回放编辑」一键切回 BBS 原版影片编辑器（100% 原版体验兜底）。
- 报告与真机清单：`docs/REPLAY-MODE-REPORT.zh.md`；集成钩子：
  `docs/REPLAY-MODE-INTEGRATION.zh.md`。

### AE 钢笔工具（需求 6）

- 新增 `comp/CompPathGeometry`（纯贝塞尔几何：求值/切线/最近点/保形分割/射线内外/45° 约束/
  镜像钳制）、`comp/CompPenSession`（纯状态机）、`client/ui/UIPenTool`（2D 仿射 / 3D 透视
  射线-平面 / 3D 工作区投影）。
- 复刻 AE 操作：单击角点、按下拖动平滑点（对称手柄）、点起点闭合、橡皮筋预览、Enter 结束、
  Esc 取消、Shift 45° 吸附、Alt 转换点/断开手柄、Ctrl 框选、Delete 删点、右键菜单、
  段上加点；形状图层编辑形状路径，其它图层编辑蒙版。
- 修 3 个上线前必现的既有 bug：① `MaskCache` 只要 ≥2 点就遮罩且永远闭合（画第二点整层
  变透明）→ 改为 `enabled && closed && ≥3 点`；② `CompShape.PATH` 没有 `closed` 字段、
  渲染强制闭合 → 新增 `closed`（默认 true，旧工程兼容）；③ 蒙版 id 撞车。
  另修：闭合贝塞尔最后一段被直线弦替代（椭圆蒙版缺 1/4 圆弧）、蒙版 world 矩阵
  JOML→AWT 转置（旋转图层蒙版镜像）、几何指纹不含 closed。
- 测试：7 个新类 / 62 用例（几何、状态机、持久化、蒙版栅格、无头 GL、形状开放路径、3D 投影）。
- 报告（含使用说明与真机清单）：`docs/PEN-TOOL-REPORT.zh.md`。

### 界面操作逻辑与面板基础设施（需求 4 / 8）

- **修复「拖轨道左侧面板数据时面板大小也跟着拖」**（两个根因）：
  ① 时间轴高度 splitter 原来横跨整个 body，盖住左/右分栏底部 4px（BBS 事件按子元素逆序
  派发且不看 `isVisible`），现在 `CompUi.timelineSplitterSpan` 只覆盖预览列，隐藏把手不再
  参与命中，并新增 `isPanelResizing()` 阻止同一按下的第二次手势；
  ② `UILayerQuickPanel` 宽度拖拽原来是「每帧轮询鼠标位置」而不是「按下即拥有」，拖时间轴
  数据经过它左边缘就会改宽度 → 改为 `subMouseClicked` 里命中 4px grip 才启动。
- 新增 UI 基础设施（为下一轮「面板拖拽组合」铺路）：
  - `UiMotion` 扩展：spring/tween/prune/isActive/可注入时钟，`CompPrefs.animations=false`
  全通道立即归位；
  - `UiStagger`（列表错峰入场）、`UiPageTransition`（页面滑动 + veil，不改子控件 alpha）、
  `CompositorLayout`（纯函数面板几何：最小宽/短窗/断点/splitter 命中）、
  `CompDockSource`（BBS `UIDockLayout` 布局树的工程侧持久化，批量写盘）与
  `CompSettings.dockLayout/dockHiddenPanels/dockUnlocked` 字段；
  - 计划：`docs/DOCK-LAYOUT-PLAN.zh.md`、`docs/UI-PANEL-PLAN.zh.md`
  （BBS 原版确有完整 `UIDockLayout` 拖拽停靠系统，下一轮直接复用）。
- 每个 PanelResizeHandle 增加 `visible` 命中检查：BBS 隐藏元素仍会收到点击，隐藏 splitter
  的旧矩形不会再吃掉内容区的按下。

### 构建与协作

- 真实工程路径含空格/中文会让 Gradle Test worker 的 classpath argfile 失效；统一用 ASCII
  junction `C:\bbsae-build` 跑构建，并新增 `_tools/gradle-run.ps1`（命名互斥量串行化多个
  AI 协作者的构建，避免并发互删 `build/classes`）。文档：`docs/TEAM-CONTEXT.zh.md`。

## 0.24 — 2026-11-05

本轮针对用户反馈的七个问题：分形杂色不是特效、蒙版异常、itrp（iterationRP）光影下黑屏、
绿幕抠不干净、叠加效果被残留绿幕污染、缺少跟踪器、部分特效（如波浪变形）不生效。

### 波浪 / 置换 / 湍流「不生效」的根因（问题 7）

- **DISTORT 着色器的位移量单位错误**：位移像素先被除以图层宽高、再乘 layer→comp 基（其列向量
  本来就是"合成像素/图层像素"），一个 48 px 的位移实际只移动了 ~0.1 px——**波浪变形、置换图、
  湍流置换因此全部不可见**。现在把图层像素位移直接交给 `localDeltaToComp`（旋转/缩放跟随保持不变）。
- `CompLayerSourceCache.scaleEffectForRaster` 的双重缩放一并修掉：DISTORT 家族的 amount、
  PIXEL 家族的马赛克/扫描线/故障尺寸、生成器的格子尺寸都是图层像素（着色器内部已换算），
  3D 烘焙时不再二次缩放——此前 3D 图层在代理分辨率下这些效果会随缩放变小/变大。

### 绿幕抠像在色度空间重做（问题 4 / 5）

- KEY 着色器的色度模式改为**色相差 + 饱和度门控**的色度距离：亮度与色相解耦，
  打光的绿幕、偏黄的绿都按同一色相抠掉——旧 RGB 距离对"色相相同、亮度不同"一律放行，
  这就是那层**浅透明绿幕残留**的来源（RGB 距离 ≈0.22 落在旧容差之内）。
- 新增**蒙版黑场 / 蒙版白场**两个滑杆（默认 0/1，老工程行为不变）：黑场清掉残余噪点，
  白场把不纯的屏幕色压成全透明。
- **溢色抑制重做**：只在屏幕色仍有影响的像素上压绿（matte<1），完整保留的主体绿色不动；
  抠掉/半透明像素的绿色同步清掉，后续叠加的模糊/辉光不会再把绿边重新混进画面（问题 5）。

### 分形杂色是真正的 FX（问题 1）

- `分形杂色` 移入「噪点与颗粒」分类（与 AE 一致），效果列表与图层库共用同一实现。
- 噪声域改为**图层像素空间**：缩放 = 云雾特征尺寸，宽图层不再把噪声拉成细纹。
- **演化改为逐八度旋转噪声域**：打关键帧后云雾是"变形"而不是整体平移（AE 的 Evolution 行为），
  幅度归一化后对比度/亮度在任意细节档位下语义一致。

### itrp / iterationRP 黑屏兼容（问题 3）

- 根因一：**itrp 的 final pass 输出 alpha=0**（`vec4(color, 0.0)`），而世界帧捕获是逐字节拷贝，
  所有消费方按纹理 alpha 混合——整帧因此不可见。现在 `SceneColor` 每次捕获后用新的 OPAQUE
  pass 把 alpha 归一为 1（同时洗掉其它包写入的 alpha 垃圾），世界帧永远按不透明图片绘制。
- 根因二：最终帧 mixin（`finalizeLevelRendering` TAIL）在某些 Iris 内部结构变化时绑不上，
  旧逻辑会一直回退到"合成前的主 framebuffer"= 纯黑。新增 **colortex0 回退捕获**
  （`IrisColor` 反射），Iris 存活期间至少给出真实世界画面（未 tonemap），预览读出会标注
  「光影帧（回退 · 合成前颜色）」。
- 预览信息行新增捕获状态：`光影最终帧` / `光影帧（回退 · 合成前颜色）` / `光影帧未捕获`。

### 蒙版（问题 2）

- 2D 图层与调整层的蒙版、轨道遮罩层的自身蒙版，统一改为**按渲染分辨率栅格化**
  （与 3D 烘焙路径一致）：蒙版纹理与内容 FBO 同 UV 采样，信息量不变，但动画蒙版不再每帧
  栅格化整幅 1920×1080 CPU 路径 + 盒式模糊——拖动/打关键帧时的预览卡顿即来源于此。

### 跟踪器（问题 6）

- 新增**点跟踪器**：`CompTracker`（锚点/模板半径/搜索半径/置信度/帧范围）+ `TrackerMatcher`
  （纯 Java NCC 模板匹配，含像素级单元测试）+ `TrackerEngine`（每显示帧协作式分析一帧；
  含 BBS 世界层的合成会先寻帧再分析，保证跟踪的是真实影片画面）。
- 检查器新增「跟踪器」区：新建/开始分析/停止/应用到位置/清除/删除。
  - **应用到位置**：把每帧结果按"相对锚点的位移"写入所选图层的独立 X/Y 通道关键帧
    （先迁移旧共享 Transform 通道再替换，不会与旧关键帧混叠）。
  - **表达式**：`tracker.<id>.x` / `tracker.<id>.y` 取当前帧跟踪位置，可驱动效果参数/蒙版点。
  - 分析中预览会停在分析帧上（runner 由跟踪器驱动），状态栏显示进度与丢失原因。

### 曲线编辑器 · 贝塞尔手柄

- **手柄镜像改为真正的对称镜像**：拖动一边，对边把手柄整支"点反射"过来——半径等长跟随、
  方向相反，不再只旋转（旧实现 `ly = -ry·(lx/rx)` 只镜像斜率，各自保留半径，拉一边另一边
  不会"对应远离"）。
- 旧公式里的 `lx/rx` 比例因子是"上下拖动乱跑"的根源：两杆长短悬殊（或一边被钳到最小 0.01 帧）
  时，纵向拖一下，对边把手柄会被放大几十上百倍直接飞出。现在对边严格取 `-ry / -ly`，无比例放大；
  镜像半径按对侧相邻段的段长钳制，控制点不会越过相邻关键帧把曲线翻折过去。Alt 拖动断开镜像不变。
- 新增 `CompCurvesTest.mirroredHandlesFollowRadiusSymmetrically` 钉住：等长镜像、精确取反、
  对侧段长钳制。

### 验证

- 新增 `ShaderWaveTest`（波浪位移必须达到全振幅——旧实现在此测试上会失败）、
  `ShaderChromaKeyTest`（纯绿/暗绿/黄绿全部抠净、白/红保留、溢色被压掉——黄绿即旧算法的
  残留案例）、`TrackerMatcherTest`（偏移跟随 ±1px、平坦场景置信度归零、无关内容低分、
  采样有序且插值）。抠像与匹配器数学另有 CPU 镜像验证（7 色彩用例 + clip 映射）。

### 0.24 源码应用时的追加修复

- `ShaderSources.GENERATE` 的分形杂色分支引用了不存在的 `luv`（DISTORT 着色器的变量），
  `generate` 程序编译失败，分形杂色与渐变生成器实际不可用；改为该着色器自己的 `uv`。
- `CompTracker.sampleAt` 在分析范围外夹取首/尾采样，导致 `tracker.<id>.x/y` 永远停在最后
  位置；改为按契约返回 null，回落到用户设置的锚点。
- `CompCurvesTest.mirroredHandlesFollowRadiusSymmetrically` 的最后一个期望值写反（应为 +6），
  与实现和"等长镜像、精确取反"的文档语义对齐。
- 全量 309 个测试通过；真实客户端加载 `bbsae 0.24` 并完成一次合成器截图验证。

## 0.23 — 2026-11-01

从 0.22.1 到 0.23，主要修复预览性能、曲线编辑器手感、图层库排版和缩放手柄。

### 更名

- **BBS Compositor 正式更名 BBS AE**。模组 ID 仍为 `bbsae`，旧工程和设置继续可用。

### 性能与缓存

- 帧缓存改为后台异步写入并原子落盘，渲染线程不再等待磁盘。
- 缓存键补上工程 ID 与实体/回放参数，修复切换工程或修改实体设置后画面不刷新。
- 时间轴渲染不再每帧复制图层列表；没有实体图层时跳过回放遮罩请求。

### 曲线编辑器

- 只显示真正参与贝塞尔段的手柄；密集曲线优先抓取离鼠标最近的手柄。
- 手柄拖动改为基于按下时快照的增量计算，修复松手丢最后一帧、拖动跳变和误改关键帧。
- 选择关键帧或 Shift 点选不再隐式改写曲线。

### 界面

- 图层库卡片改为自适应 1–3 列，中文描述按真实字体宽度换行，不再拥挤或被裁切。
- 赞助者「星辰久梦君」更新为 **【星辰久梦君_Star Dm Sov】**，名称和感谢语使用金色。

### 预览操作

- 边柄只缩放对应轴；角柄默认等比，按住 Shift 可单独缩放 X/Y。
- 3D 图层改用鼠标射线与图层平面求交，2D 图层使用 `view2D` 空间；透视、相机平移或缩放时手柄仍然跟手。
- 上下边柄显示垂直缩放光标，左右边柄显示水平缩放光标。

**验证：** 51 个测试套件、302 项测试全部通过。
