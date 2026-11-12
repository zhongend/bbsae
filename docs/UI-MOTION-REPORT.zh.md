# UI 动效实装报告（需求 8 · 非线性动效）

> 范围：用户需求 8「给所有页面加上非线性动画，使其操作流畅」中**独立 UI 组件**的部分。
> 不包含 `UICompositorPanel`（Lead 并行重构中）、页面/面板级过渡（`UiPageTransition` 的宿主接线）、
> 面板尺寸 tween。基准：0.92 提交后的工作区，本任务只改下列 12 个文件 + 1 个测试 + 本文档。
>
> 结论：按钮/图标按钮/tab 的按下回弹、列表/卡片错峰与 hover 插值、轨道 clip hover、吸附线 flash、
> 快捷面板分组/行揭示、色轮/曲线 hover 已实装；全部走现有 `CompPrefs.animations` + 强度/速度/光效
> 总开关，**没有新增设置项**；新增 10 个纯逻辑测试；全量回归 **96 suites / 564 tests / 0 failed**（1 skipped）。

---

## 1. 动效纪律（所有文件共同遵守）

| 规则 | 实现 |
| --- | --- |
| 每帧每宿主只 tick 一次 | 每个宿主 `render()` 开头调一次 `motion.tickFrame()`；子列表/卡片只读取通道，不各自计时 |
| 关闭动效立即归位 | 所有值都经 `UiMotion.anim/spring/tween/hover/select/intro/breathe` 取得，`animationsEnabled()==false`（含强度 0）时直接返回目标值；少数用原始时间的量（overlay 的应用脉冲、轨道吸附 flash）显式用 `CompPrefs.animating() && CompPrefs.intensity() > 0.001F` 关掉 |
| 强度可调 | 位移/缩放/光晕幅度都乘 `motion.intensity()`（0–1.5 裁剪）：按钮上浮、图标按压缩放、tab 缩放下沉、行入场位移、卡片入场位移、色轮 handle、吸附线光晕 |
| 稳定 key、不在每帧拼字符串 | 行/卡片/tab/轨道层的通道名在**建立对象时**生成一次并缓存（`EffectRow.hoverKey`、`PresetCard.hoverKey`、`UICompTabBar` 的 final key、`UICompTrackTimeline.channelKeys`、`UIEffectLibraryOverlay.tileKeys/chipKeys`、`UICompRenderJobList.keys`、`UILayerQuickPanel.Row.hoverKey`）；`UICompButton`/`UICompIconButton` 用常量 key |
| 每 60 帧 prune 一次 | 每个宿主自带 `motionFrames` 计数器，每 60 帧 `motion.prune(2F)`，清掉 2 秒未触碰的通道；长列表滚动、面板反复重建不会让通道表只增不减 |
| 命中区域不变 | 按钮/卡片/tab 只改绘制矩阵或绘制矩形，`area`（BBS 命中矩形）不动；轨道时间轴**不**动画滚动位置（会与命中测试错位） |
| 不碰 BBS 原生控件内部 | 只重写 bbsae 自有子类（`UICompButton extends UIButton` 等）的 `renderSkin`/`render`/`renderListElement`；`UIToggle/UITrackpad/UIList` 的 jar 内部一行未改 |
| 不进文档数据 | 全部是绘制层状态，不写 `CompProject`、不进 undo、不影响导出像素 |

---

## 2. 文件级清单

| 文件 | 新增/调整的动效 | 关键点 |
| --- | --- | --- |
| `UICompButton.java` | hover 上浮（原有）改为受按下/禁用/强度影响；按下时填充**内缩 1px**、填充变暗 6%、上浮取消，释放后 spring 回弹；禁用态 alpha/文字渐隐；`PressLatch` 按下状态机 | BBS 的 click 在**按下**触发，release 事件可能被重建的父级吃掉；因此既覆盖 `subMouseClicked/subMouseReleased`，又每帧用 `Window.isMouseButtonPressed(0)` 轮询兜底，按钮不会卡在按下姿态。绘制 inset 0/1px 由纯函数 `pressInset` 给出，文字位置不动 |
| `UICompIconButton.java` | hover 底板/描边按指数插值淡入；按下整体缩放 5%（pivot 为自身中心）并内缩 1px | 缩放只包住 `super.render`（icon 绘制），tooltip 由 BBS 在整棵树之后绘制，不受矩阵影响；`PressLatch` 同上 |
| `UICompTabBar.java` | 选中下划线已有插值；补 hover 淡入、按下整体缩放 4% + 下划线随按下下沉 1px、文字亮度插值 | tab 的通道 key 在 `addTab` 时生成（`selectKey/hoverKey/pressKey`）；`UITextTab` 自己会在 hover 时用 `hoverColor`，这里把它指向动画值，进入/离开都平滑 |
| `UIEffectLibrary.java` | 列表行 hover/选中 wash 插值（accent 渐变 + 选中 2px 竖条）、文字 85% 白↔白插值、行入场错峰（每行 12ms、最多 200ms、下落 2px + 淡入） | 重写 `renderListElement` 以替换 BBS `RowStyle` 的布尔绘制；行 key 在 `EffectRow` 构造时生成；`rebuild()`（搜索/折叠/应用）时 `replay()` 重新错峰 |
| `UICompRenderJobList.java` | 渲染队列状态脉冲：RENDERING/ENCODING 行左侧状态条按 `breathe` 呼吸；状态变化时闪一下；行 hover wash 插值 | 重写 `renderListElement`；按 job id 缓存 4 个通道名与上次状态；保留原 `elementToString` 文案 |
| `UIImportBrowser.java` | 打开时背板淡入 + 弹窗整体上滑 8px；目录行错峰（每行 14ms、最多 200ms、2px）；空目录文案淡入 | 只动画绘制：`MatrixStack` 包住 `super.render` 并 try/finally；行 index/滚动换算抽成纯函数 `rowIndexAt`、`clampScrollOffset` 便于测试；**关闭**过渡需宿主延迟移除（见第 5 节） |
| `UICompPresetBrowser.java` | 预设卡片错峰入场（每卡 16ms、最多 240ms、3px）+ hover 卡片底色/描边/左侧 accent rail 插值 | 新增 `PresetCard extends UIElement` 自绘卡片底板；`refresh()` 重新 `replay()`；卡片 key 在构造时生成 |
| `UICurveEditor.java` | 5 个控制点 hover 插值（半径 +1px、加软环、颜色受控过渡）；拖动点半径/亮度插值；曲线整体轻微淡入（0.6→1，避免重建闪） | 抽纯函数 `pointNear`（命中半径 6×8px）与 `pointValue`，`value(int)` 委托同一公式，绘制与命中不会漂移；控制点 key 为类常量数组 |
| `UIColorWheel.java` | 悬停时色盘出现 accent 细环、value 条描边过渡；拖动时 handle/亮度条拇指随 spring 放大（受强度） | `diskSide(w,h)` 抽成纯函数，绘制与点击共用；色盘纹理仍只烘焙一次（AWT），不新增每帧绘制 |
| `UICompTrackTimeline.java` | clip bar hover 插值（提亮 22% + 顶部高光 + 描边）；吸附线在命中新吸附点时 `flash` 短脉冲（亮环 + 加宽）；拖拽 HUD 淡入/上滑 6px | 不动画滚动位置、不动行高/时间轴；每层的 head/lane/clip 通道名按 layer id 缓存；吸附 flash 只在 `snapGuide` 变化时触发，同一吸附点每帧不重复闪 |
| `UILayerQuickPanel.java` | 分组标题/行在选中图层或 2D↔3D 切换时错峰揭示（组 20ms、行 12ms，最多 180ms，2px + 淡入）；行 label 的 hover 插值（accent 竖条 + 提亮）；顶部 accent 渐变随揭示淡入 | `UITrackpad` 是 BBS 原生控件，内部 hover 不拦；只动画本面板自绘的 label/背景。`layoutRows()` 与选中图层变化时 `replay()`；拖动 playhead 不重放 |
| `UIEffectLibraryOverlay.java` | 已有 hover/错峰/缩略图淡入/平滑滚动保留；统一 60 帧 prune；应用效果改为 `flash` 脉冲（去掉 `System.currentTimeMillis()`）；背板 fade-in；tile/chip 通道名改为重建时预生成 | 去掉了每帧 `"tile:" + index`、`"chip:" + label` 拼接与 `hover(key, hot)` 字符串比较；`hot` 从 String 改为 int `hotIndex` |

新增测试：`src/test/java/dev/bbsae/client/ui/UiFeedbackMotionTest.java`（10 个测试，见第 4 节）。

---

## 3. 性能预算与实现取舍

- **通道数有界**：每个宿主最多「可见行数 × 每行通道数」+ 少量固定通道。效果库最多约 200 行可见少量、每行 2 通道；渲染队列每 job 4 通道并在超过 256 个 job 时清缓存；轨道层通道超过 512 层时清缓存。每帧只有 `HashMap.get/put`，无排序、无分配（key 已缓存）。
- **单帧成本**：每个宿主一次 `tickFrame`（时间+dt），每通道几次浮点运算；绘制层多出的 quad 数：按钮 ≤3、行 wash ≤2、tile 与现状持平。没有新增 framebuffer / shader / GL 状态。
- **不做的事**：
  - 不 tween 面板/滚动/布局尺寸（用户拖拽必须 1:1 跟手；`UICompositorPanel` 的布局归 Lead）；
  - 不对列表滚动位置做平滑（命中测试用同一坐标，动滚动会导致点错行）；
  - 不给 BBS `UIToggle/UITrackpad/UIList` 内部做逐控件 alpha（会侵入 jar 行为且裁剪复杂）。
- **prune 节奏**：每 60 帧一次（约 1 秒 @60fps），`idle=2s`；已到达目标的通道在 2 秒内被清理。尚未到达目标的通道不会被清（`prune` 只看 `lastUsed`，可见行每帧都更新）。

---

## 4. 测试与回归

### 4.1 新增纯逻辑测试（`UiFeedbackMotionTest`，10 个）

| 测试 | 断言内容 |
| --- | --- |
| `pressLatchOwnsAndReleasesAPress` | 按下状态机：内部+启用才 latch；物理键松开/按钮被禁用时清除（丢失 release 不卡死）；外部按下不属于我；`release()` 清零 |
| `pressInsetAppearsOnlyPastHalfWay` | 内缩 0/1px 的阈值与 clamp（≥0.5→1px，超出范围不越界） |
| `liftCollapsesWhenPressedDisabledOrStatic` | 上浮在按下/禁用/强度 0 时为 0，其余线性 |
| `disabledDimIsBoundedAndMonotonic` | 禁用渐隐系数 1→0.5、clamp、单调 |
| `animationsOffSnapsEveryFeedbackChannel` | 动效关闭时 spring/anim/tween/breathe 立即返回目标（按下第一帧就在目标位、不上浮、页面 tween 直接 1、呼吸冻结），`isActive()==false` |
| `pruneCadenceKeepsHostChannelsBounded` | 按宿主模式跑 780 帧、每 60 帧 prune：稳定可见行通道保留，滚出视口的行通道被清掉，总数不随时间增长 |
| `staggerDelaysAreMonotonicAndCapped` | 错峰延迟 0 → 0.06s → 封顶 0.2s，单调；`offsetPx` 随进度单调减、`alpha` 永不为 0；`motion==null` 时进度 1 |
| `curveEditorHoverTargetsTheNearestPointWithinRadius` | 5 点曲线的端点/中点命中、半径外返回 -1、点值移动后命中框跟随、点值 clamp |
| `colorWheelDiskKeepsStripSpace` | 色盘尺寸函数（扣掉标签+value 条 30px、最小宽度、过小不可绘制） |
| `importBrowserScrollArithmeticStaysInsideContent` | 行号换算（含滚动偏移、行高 0 不除零）与滚动 clamp |

### 4.2 回归结果

命令（串行包装器）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
```

- **全量（含工作区所有并行任务测试）：96 suites / 564 tests / 0 failed / 0 errors / 1 skipped，BUILD SUCCESSFUL。**
  其中 `dev.bbsae.client.ui.*` 全部通过（含既有 `UiMotionSpringTest`、`UILayerQuickPanelTest`、`CompUiResponsiveTest`）。
- 基线 90 suites / 511 tests；新增 suite 来自本任务 1 个 + 并行任务若干。**本任务新增 1 suite / 10 tests。**
- 执行期间出现过一次并行任务（需求 3 的 `DepthPlaneGhostRegressionTest`，未跟踪文件、4 个失败）导致的临时红；
  该协作者完成修改后复跑全量已全绿，最终结果以上面为准。本任务未改 `client/render/**`，与那次失败无文件交集。
- 注意：工作区在本次执行期间被其他协作者同时修改（`UICompositorPanel`、`CompPrefs`、`CompUi`、
  `client/replay/**`、`client/render/**` 等），合并提交时请以最终工作区状态再跑一次全量。
  本任务文件与 `UiMotion/UiStagger/UiPageTransition` 均未被并行改动覆盖。

---

## 5. 需要 Lead / 宿主的接入点（未新增 CompPrefs 开关）

本任务**没有**需要 Lead 新增的 `CompPrefs` 开关：所有动效已统一受
`CompPrefs.animations`（总开关）+ `motionIntensity`（强度）+ `motionSpeed`（速度）+ `glow/shadows` 控制。
计划里提的 `buttonFeedback / listStagger / panelTransitions` 三个独立开关**暂不需要**——如果以后要拆，
宿主侧改动很小（各文件的 `renderSkin`/`renderListElement`/`render` 入口各加一个 `CompPrefs` 判断即可），
但需要 Lead 先加 `CompPrefs.Data` 字段和设置页行。

需要宿主（`UICompositorPanel`，Lead 范围）配合的点：

1. **关闭过渡**：`UIImportBrowser`、`UIEffectLibraryOverlay` 目前只做了**打开**过渡（背板淡入、内容滑入、行错峰）。
   关闭仍是宿主立即 `removeFromParent()` → 瞬切。若要关闭也淡出，需要宿主先 `setVisible(false)`/标一个关闭标志、
   延迟约 120ms 再移除（或给它一个 `beginClose(Runnable)` 钩子）。不改宿主也能用，只是关闭没有动画。
2. **页面/面板切换**：左栏三页、inspector 七页、时间轴/曲线页、聚焦/渲染模式的过渡属于 `UICompositorPanel` +
   `UiPageTransition`（已在 0.92 提供）。本任务的子组件只保证“内容出现时自己会错峰/淡入”，页面级滑动 + veil 需要 Host 接线。
3. **快捷面板显隐**：`UILayerQuickPanel` 内部已做揭示/行反馈；面板宽度的展开/收起（时间轴让位）属于布局层，
   建议用 `UiLayoutTween`（计划 6.4），不要在本面板内部改 `area`。
4. **可选共享优化（Round 3 已落地）**：prune 节奏原本由各宿主自带；Round 3 起 `UiMotion.tickFrame()`
   每 60 帧自动 `prune` 一次（见第 8 节），`UIAssetBrowser`/`UILayerLibrary` 等既有宿主无需再各自接线。
   宿主里已有的显式 `motion.prune(...)` 调用保留也无害（幂等）。

---

## 6. 剩余风险与真机验收清单

### 风险

1. **入场位移期间命中偏移 ≤3px**（仅入场 0.3 秒内）：行/卡片用矩阵位移绘制，`area` 不变，
   所以入场瞬间点击位置与绘制位置最多差 2–3px；按钮/图标按钮只改绘制矩形（内缩 1px），不受影响。
   若真机上觉得别扭，可把卡片位移降到 2px 或去掉位移只保留淡入。
2. **效果库行样式改为自绘**：不再走 BBS `RowStyle`，因此列表拖拽时的 drop-target 提示不再绘制。
   效果库本来不提供拖动排序，功能无影响；若以后要加“拖效果到轨道”，需要在那里补 drop 提示。
3. **矩阵缩放依赖 BBS 的 batcher 矩阵栈**：与 `UICompositorPanel` 的整层入场同一用法（push/translate/scale/pop 且 try/finally），
   但 UI 动效无法无头测试，需要真机确认没有 GL 状态泄漏（正常路径与异常路径都已 finally pop）。
4. **并行工作区**：执行期间工作区被多个协作者同时修改，曾出现需求 3 的 WIP 测试临时红；其修复后全量已全绿。
   合并前仍建议以最终工作区再跑一次全量（并行任务可能继续提交）。
5. **未覆盖的组件**：`UICompCurveEditor`（时间轴曲线页，约 2400 行）、`UIAssetBrowser`、`UILayerLibrary`、
   `UIStartScreen`/`UIOnboarding` 的既有动效不在本任务写域，未改（前两者无 hover 插值/无 prune；后两者已有动效）。

### 真机截图/录屏验收（交给 Lead，不要在本任务开客户端）

1. 按钮：hover 上浮 2px 内完成；按住时下沉 1px、颜色变暗；快速松开回弹；禁用按钮渐隐、不抖。
2. 图标按钮/tab：hover 底板/文字 120–220ms 淡入；按住有轻微缩放；tab 切换下划线滑入；文字不跳位。
3. 效果库：搜索/展开分类时行错峰出现；hover/选中是渐变 wash + 左侧 accent 条；滚动不粘滞。
4. 渲染队列：渲染/编码中的行状态条呼吸；完成/失败状态变化闪一下；行 hover 有反馈。
5. 导入浏览器：打开时背板淡入、弹窗上滑 8px、目录行错峰；滚动/选中/双击行为与改前一致；**关闭是否瞬切**记录一下（需宿主接入才淡出）。
6. 预设浏览器：卡片错峰出现；hover 卡片浮起；点“应用/删除”行为不变。
7. 曲线编辑器：靠近控制点出现软环、点半径 +1px；拖动点跟手；双击复位正常。
8. 色轮：悬停出现细环、value 条描边亮起；拖动时 handle 放大；双击复位正常。
9. 时间轴：clip bar hover 提亮；拖动吸附到新点时线闪一下并加宽；拖拽 HUD 淡入；**拖动/滚动位置仍 1:1 跟手，行点击不偏**。
10. 快捷面板：换选中图层时分组/行错峰揭示；行 label hover 有 accent 条；拖动 playhead 不重放。
11. 效果库浮层：打开背板淡入；应用效果时该 tile 闪一下；快速切分类/搜索无残留高亮。
12. 设置里关“启用界面动效”（或强度=0）：以上全部立即归位，无残影/半透明/偏移；关闭后再开恢复。
13. 性能：1080p、200+ 行效果库、按住拖动时间轴 60fps；长时间开关面板后 `UiMotion` 通道数不增长（可临时加 `channelCount()` 日志，不要提交）。

---

## 7. 与 `UI-PANEL-PLAN.zh.md` 第 6 节的偏差（有意）

| 计划 | 实际 | 原因 |
| --- | --- | --- |
| 在 `UiMotion` 内加 spring/tween/prune 等 | 0.92 已由前序工作完成；Round 3 已补**全局自动 prune** | `UiMotion` 在 Round 3 才授权修改，见第 8 节 |
| 新增 `CompPrefs` 的 `buttonFeedback/listStagger/panelTransitions` 开关 | **未加**，复用现有总开关 + 强度/速度 | 写域禁止改 `CompPrefs`；总开关已能覆盖“全部关掉”的诉求，拆细开关等 Lead 决定 |
| `prune` 统一在 `UiMotion` 内 | **Round 3 已实现**：`tickFrame()` 每 60 帧自动 prune，宿主无需接 | 见第 8 节 |
| `UiPageTransition` / `UiLayoutTween` 宿主接线 | 未接线 | 属于 `UICompositorPanel`（Lead 重构中）写域 |
| `UICompCurveEditor`（时间轴曲线页）hover | 未改 | 不在本任务写域，且 2400+ 行、风险高；留给后续 |

---

## 8. Round 3（0.94）：剩余页面 + UiMotion 全局 prune + 浮层关闭过渡

> 0.93 已发布后继续补齐：`UIAssetBrowser`/`UILayerLibrary` 行态、开始页/引导缺项、
> `UIImportBrowser`/`UIEffectLibraryOverlay` 关闭淡出、以及 `UiMotion` 的全局 prune。
> **没有新增 CompPrefs 开关**；没有开客户端。

### 8.1 改动文件

| 文件 | Round 3 改动 |
| --- | --- |
| `UiMotion.java` | **全局自动 prune**：`tickFrame()` 每 `PRUNE_EVERY_FRAMES=60` 帧调一次 `prune(2F)`；prune 现在同时清理 IDLE 通道、**未再被查询的 tween**（会让 `isActive()` 变陈旧）和**未被读取的 flash**；`flash()`/`flash(key,duration)` 改用注入的帧时钟（`nowNanos`）而不是 `System.nanoTime()`，flash 与其它通道同一时基、可确定性测试；`reset()` 重置计数；新增诊断 `flashCount()/tweenCount()` |
| `UiPageTransition.java` | 新增嵌套 `CloseFade`（140ms，`begin()/tick(long)/isClosing()/progress()/remaining()`）。`tick` 只在结束那一帧返回一次 `true`，重复 `begin` 被忽略；动效关闭时第一帧即结束；`begin` 时把通道预置为 0（否则 tween 会从目标值开始瞬间结束） |
| `UIImportBrowser.java` | 新增 `beginClose(Runnable onDone)` / `isClosing()`：关闭时背板淡出、弹窗下滑 10px 后回调；回调经 `context.render.postRunnable()` 在**本帧渲染结束后**执行（宿主可安全 `removeFromParent()`，不会在父级遍历子元素时改列表）；淡出期间 `box` 禁用、根节点吞掉鼠标/键盘事件（保持模态）；重复调用忽略 |
| `UIEffectLibraryOverlay.java` | 同上 `beginClose(Runnable)`/`isClosing()`（关闭时背板淡出 + 内容下滑 10px）；Esc 在关闭中不再二次触发 `onClose` |
| `UIAssetBrowser.java` | Grid：tile 的 hover/select 通道名在 `rebuild()` 里预生成（`List<String[]>`），缩略图淡入 key 按 asset path 缓存（上限 1024 清理）；`rebuild()` 时 `replay()` 错峰；分类条 Strip：13 个 chip 的选中/悬停插值（颜色/底边/文字），key 在构造时预生成 —— 均不再每帧 `"tile:"+i`/`"chip:"+i` 拼字符串。prune 由 UiMotion 全局接管 |
| `UILayerLibrary.java` | Grid：卡片 hover/select 通道名在 `setEntries()` 里预生成（`List<String[]>`）并 `replay()` 错峰；不再每帧 `"layer:"+i` 拼接 |
| `UIStartScreen.java` | 工程列表行错峰入场（18ms/行、封顶 200ms、2px + 淡入，文字颜色走 `RowStyle.textColor`）；`FormPanel`（新建工程表单）显示时从下方滑入 8px，`showForm()` 重放；按钮本身已是 `UICompButton` 有按下反馈 |
| `UIOnboarding.java` | 页内卡片错峰揭示：`pageItemDelay(index)=min(0.18, index*0.022)`（纯函数），`pageIntro(index)` 用 `pageAt` 计算，用于欢迎页清单/功能卡、布局卡、动效卡、快捷键卡、完成页摘要框（位移 + 淡入）；布局/动效/快捷键卡 hover/select 通道名改为类常量（不再每帧拼 `"layout:"+i`）；步骤圆点选中宽度/颜色插值；左右箭头 hover 淡入；`bigButton` 增加按下（hover+物理键按下→内缩 1px/变暗/回弹）并缓存 hover 通道名；prune 由 UiMotion 全局接管 |

`UiStagger.java` 无改动（本轮的页内错峰是同语义的 `pageItemDelay` + `pageIntro`，因为引导页的“显示时钟”是 `pageAt` 而不是面板 `markShown`）。

### 8.2 宿主接入点（2 处，都在 `UICompositorPanel`，本任务未改）

1. **`UIImportBrowser`**：宿主所有 `onClose` 回调改为走关闭淡出。把现有的
   ```java
   () -> { if (this.importBrowser != null) { this.importBrowser.removeFromParent(); this.importBrowser = null; } }
   ```
   改成：
   ```java
   () -> { if (this.importBrowser != null && !this.importBrowser.isClosing())
       this.importBrowser.beginClose(() -> {
           this.importBrowser.setVisible(false);
           this.importBrowser.removeFromParent();
           this.importBrowser = null;
       }); }
   ```
   即“取消/关闭按钮/确认导入后的清理”都调用同一个 lambda 即可（浏览器内部的取消按钮已经走 `onClose`，无需另外改）。回调在渲染结束后执行，宿主可直接移除；若宿主不接入，行为与 0.93 一致（瞬切）。
2. **`UIEffectLibraryOverlay`**：`openEffectLibraryOverlay(...)` 的 `onClose` lambda 同理：
   ```java
   () -> { if (this.effectLibraryOverlay != null && !this.effectLibraryOverlay.isClosing())
       this.effectLibraryOverlay.beginClose(() -> {
           this.effectLibraryOverlay.setVisible(false);
           this.effectLibraryOverlay.removeFromParent();
           this.effectLibraryOverlay = null;
       }); }
   ```
   （现有 `openEffectLibraryOverlay` 里的清理 lambda 保留原样作为 `beginClose` 的 `onDone` 内容即可。）

### 8.3 新增测试

`src/test/java/dev/bbsae/client/ui/UiMotionGlobalPruneTest.java`（8 个）：

| 测试 | 断言 |
| --- | --- |
| `autoPruneDropsIdleChannelsWithoutHostCalls` | 180 帧不触碰任何通道、且**不调用**宿主 prune：通道表清空（60 帧一次自动 sweep） |
| `autoPruneKeepsChannelsTouchedThisFrame` | 每帧更新的可见通道在 600 帧内始终存活 |
| `autoPruneDropsIdleTweenAndFlashes` | 宿主不再查询的 tween、没被读取的 flash 在 idle 窗口后被清；`isActive()` 随之变 false |
| `autoPruneBoundsChannelChurnInLongSession` | 900 帧里每帧新增一个只触碰一次的通道：数量被限制在 “常驻 + 2s 内” 的量级 |
| `closeFadeCallsFinishExactlyOnce` | 关闭进度单调、`tick` 恰好返回一次 `true`、结束后不再返回 true |
| `closeFadeSnapsWhenAnimationsAreOff` | 关动效后第一帧即结束（进度=1），不残留 |
| `closeFadeIgnoresRepeatedBegin` | Esc+点击同帧只开始一次；结束后再 `begin` 不会复活 |
| `onboardingPageDelayIsMonotonicAndCapped` | `pageItemDelay` 首项 0、负数 clamp 0、单调、封顶 0.18s |

加上既有 `UiFeedbackMotionTest`（10）+ `UiMotionSpringTest`（9），动效相关纯逻辑测试共 **27 个**。

### 8.4 Round 3 回归结果

- 串行包装器全量（含所有并行任务测试）：**110 suites / 669 tests / 0 failed / 0 errors / 1 skipped，BUILD SUCCESSFUL**。
- 动效相关：`UiMotionGlobalPruneTest`（8）+ `UiFeedbackMotionTest`（10）+ `UiMotionSpringTest`（9）共 **27 个纯逻辑测试全绿**。
- 执行期间工作区被 5 个并行任务同时修改，曾出现两类临时红（AM `unsharp_mask` 的显示名/缩略图、`CompUi` rail 图标新测试），
  均来自其他协作者的进行中改动；他们收尾后复跑全量已全绿。合并前建议再跑一次全量。

### 8.5 Round 3 风险

1. **关闭回调时机依赖 `UIRenderingContext.postRunnable`**（BBS 2.7 1.20.1 jar 已核对存在，`UIScreen` 每帧渲染后执行）。
   若宿主未接入 `beginClose`，仍走旧的瞬切路径，无行为变化。
2. **页内错峰用 `pageAt` + `System.nanoTime`**（引导页自身时钟，不走注入时钟）；动效关闭/强度 0 时 `pageIntro` 直接返回 1。
   因此它只有纯函数 `pageItemDelay` 可单测，时间曲线本身以真机为准。
3. **UIAssetBrowser/UILayerLibrary 的通道名按列表位置生成**：搜索/切分类后同一位置换成了别的素材，hover/选中会从
   上一个条目的值平滑过去（视觉上就是“淡入淡出”），不会错位到别的行；若要求严格按素材 id 持久，可后续改成 path-key。
4. **入场位移期间命中矩形仍在最终位置**（≤6px，仅 0.3 s）：快速点击落点比画面早一点，和上一轮同类风险一致。
5. **全局 prune 会清理“2 秒没被查询的 tween”**：如果某个宿主把 tween 当作跨帧但每帧不查询的状态，会被提前清理；
   现有调用方（`CloseFade`、时间轴/overlay 的 flash）都是每帧查询，未受影响。

---

## 9. Round 4（0.95）：曲线编辑器 / 关键帧抽屉

> 0.94 之前唯一没被动效覆盖的宿主是时间轴「曲线」页的 `UICompCurveEditor`（约 2400 行）。
> 本轮补齐它的 hover/选中/播放头/拖动 HUD，并检查了关键帧抽屉 `UICompositorKeyframes`。
> **没有新增 CompPrefs 开关**；没有开客户端；所有动画只改绘制，不动关键帧位置/图表缩放/滚动。

### 9.1 改动文件

| 文件 | Round 4 改动 |
| --- | --- |
| `UICompCurveEditor.java` | Graph 增加单一 `UiMotion`（`render()` 开头 tick 一次）；通道列表行 hover/选中 wash + 左侧 accent 条 + chip/文字颜色插值；曲线本体按“激活量”插值线宽（1→1.6px）与 halo（glow 时 4.4px、随激活淡入淡出，受 intensity）；关键帧 hover/选中插值（颜色渐白、外环 4.5→7px、字形 +1px，全部在 10px 命中半径内）；播放头呼吸发光（受 glow + intensity，X 永远来自模型时间）；光标十字线/数值提示淡入淡出；选中信息卡淡入上浮；拖动 key/handle 时跟随指针的 HUD（值+帧 / 影响+速度，6px 上浮）；框选矩形淡入。通道/行/关键帧通道名在首次见到时预生成（行按 id，关键帧按 identity + 序列号），不每帧拼字符串；全局 prune 自动清理滚出视口的 key 通道 |
| `UICompositorKeyframes.java` | 包裹 BBS `UIKeyframes`（dope sheet 内部不动）；自绘部分只有 `renderOverlay` 的 playhead 光标，因此只补了光标背后的呼吸光晕（与曲线页同色同节奏，受 glow + intensity，不改光标 X）。工具条按钮本来就是 `UICompButton`，自动有按下/悬停反馈 |
| `src/test/java/dev/bbsae/client/ui/UiCurveMotionTest.java` | 新增 6 个纯逻辑测试（见 9.2） |
| `UiMotion.java` / `UiPageTransition.java` | 本轮无需改动（全局 prune、CloseFade 已在 Round 3 就位） |

### 9.2 新增纯逻辑测试（6 个）

| 测试 | 断言 |
| --- | --- |
| `curveThicknessAndOpacityReachTheActiveEnds` | 曲线线宽 1→1.6、opacity 0.35→1，强调量 clamp 且单调 |
| `keyEmphasisGrowsRingAndGlyphButStaysInsideTheHitRadius` | 关键帧外环 4.5→7px（<10px 命中半径）、字形最多 +1px、intensity=0 时不长 |
| `playheadGlowFollowsBreatheAndIntensity` | 呼吸光晕 0.10–0.20、intensity 0 时为 0、输入 clamp |
| `animationsOffSnapsEveryCurveFeedbackChannel` | 关动效时 anim 第一帧即目标 → 线宽/外环/光晕立即到位、breathe 冻结在 0.5；开启时需多帧收敛 |
| `dragHudTextFormatsTheFrameOnce` | 拖动 HUD 文本格式（值 + 帧），null 不输出 "null" |
| `handleHudTextNamesDirectionInfluenceAndSpeed` | 出/入切线、影响百分比、速度格式 |

加上此前三轮：`UiCurveMotionTest`（6）+ `UiMotionGlobalPruneTest`（8）+ `UiFeedbackMotionTest`（10）+ `UiMotionSpringTest`（9）= **33 个动效纯逻辑测试**。

### 9.3 回归结果

- 串行包装器全量（含并行任务测试）：**117 suites / 699 tests / 0 failed / 0 errors / 1 skipped，BUILD SUCCESSFUL**。
- 曲线相关既有测试（`CurveInteractionTest`、`CurveSpansTest`、`CurveGeometryReproTest`）与新增 `UiCurveMotionTest` 全绿。
- 工作区仍有 5 个并行任务在改，合并前建议再跑一次全量。

### 9.4 纪律核对

- **每帧每宿主一次 tick**：`Graph.render()` 一次；`UICompositorKeyframes.renderOverlay()` 一次（BBS `UIKeyframes.render` 每帧调用一次）。
- **稳定 key**：通道行按 `"c:<channelId>"`/`"g:<group>"` 缓存；关键帧按 identity + 递增序列号缓存（`kf:N:h` / `kf:N:s`），上限 4096 后重建；滚出视口的 key 通道由 `UiMotion.tickFrame` 的自动 prune 回收。
- **animations/intensity 关立即归位**：所有量经由 `motion.anim/breathe` 推导；强度缩放加在外环半径、字形增量、曲线线宽/halo、播放头光晕、HUD 上浮/发光上。
- **不改命中区域**：外环最大 7px < 关键帧 10px 命中半径；字形最多 +1px；曲线线宽/高亮只是绘制；**关键帧位置、图表缩放到数值轴、滚动/平移完全不参与动画**。
- **不写工程数据**：所有新增代码只读 `Keyframe` 的现有字段与面板时间，不 set/insert/save。

### 9.5 Round 4 剩余风险

1. **每帧 hover 命中扫描**：`render()` 每帧调用一次 `keyAt()` 来找悬停关键帧（可见通道的所有 key，先按 X 距离早退）。500 key × 18 通道的极端工程会多几千次轻量循环/帧；曲线本身已按屏宽采样，真机应无感，但这是本轮唯一的额外 CPU 成本，若卡顿可改为“两帧一次”或复用 renderKeys 的遍历。
2. **key 通道被 prune 后重建**：滚出视口 >2s 的关键帧再次进入时，hover/选中会从 0 重新淡入（视觉上像一次轻微入场）；这是有意的回收代价，不会错位。
3. **hover 外环是方框描边**（BBS `outline` 只有矩形），在圆形/菱形键周围读作“选中框”而不是光晕；若真机觉得突兀，可换成 4 段斜线或第二层 drawDisc。
4. **拖动 HUD 只在 DRAG_KEY/DRAG_HANDLE**：平移/框选没有 HUD（框选本身有淡入），与 AE 行为一致。
5. **真机验收点**：切换通道时旧曲线淡出/新曲线淡入；关键帧 hover 环 + 选中渐白；拖动键/手柄时指针旁 HUD 6px 上浮；播放头缓慢呼吸（关 glow 后静止）；曲线页与关键帧抽屉两条光标光晕节奏一致；关动效后所有反馈一帧到位。

---

## 10. Round 5（0.96）：设置页 / 开始页 / 引导页补完

> 0.95 还剩下设置页（分类切换、长列表、页脚）和开始页工程行是瞬时的；本轮补完，
> 并按 Lead 要求在设置页接入两个新偏好：三态逐像素深度、钢笔保持顶点数。
> **没有新增 CompPrefs 字段**（两个字段由 Lead 加好，本任务只读/写值）；没有开客户端。

### 10.1 改动文件

| 文件 | Round 5 改动 |
| --- | --- |
| `UICompSettings.java` | ① 分类切换过渡：`MotionScroll extends UIScrollView` 用 `UiPageTransition(0.18s, 6px)` 做内容下滑 + veil 淡出（滚动偏移/命中矩形不动，过渡中快速点击仍落在看到的行上）；② 分类按钮：改回全部 `FLAT`，选中态由宿主自绘插值覆盖（wash 0.14、2px accent rail、下划线从 10px 收缩到 0，通道 `nav:<i>`），不再瞬切 `SELECTED` 皮肤；③ 设置行错峰：`Row`/`SectionHeader` 按加入顺序 15ms/行、封顶 200ms、4px × intensity 的绘制位移 + 淡入（矩阵只包绘制，控件命中矩形不变）；④ 分组折叠/展开：`SectionHeader` 可点击折叠，行用 `setVisible(false)` 隐藏（BBS `ColumnResizer` 本来就跳过不可见子元素，布局会合拢而不是留洞），chevron/rail/下划线用 spring(420/26) 过渡，折叠状态按 `category|label` 会话内保留；⑤ 页脚版本号 alpha 0.3→1 最后淡入。每帧宿主 tick 一次、`transition.update()` 一次；key 预生成（nav 数组、section rebuild 时、行无 key） |
| 设置页新增两个 Lead 需要的行 | 见 10.2 |
| `UIStartScreen.java` | 工程列表行改自绘：hover wash / 选中 accent 条的插值（每工程 id 缓存通道），文字错峰保留；菜单/表单按钮本来就是 `UICompButton`（按下/悬停已有反馈）；表单滑入是 Round 3 的 `FormPanel` |
| `UIOnboarding.java` | 补了步骤圆点的 hover 插值（宽度 +2px、颜色提亮，独立通道，扫过一排点读作一条轨迹）；卡片/箭头/大按钮的插值已在 Round 3 完成 |
| `UiMotion.java` / `UiPageTransition.java` / `UiStagger.java` | 本轮无需改动 |
| `src/test/java/dev/bbsae/client/ui/UiSettingsMotionTest.java` | 新增 7 个纯逻辑测试（见 10.3） |

### 10.2 Lead 要求的两个设置行（位置与行为）

1. **逐像素深度（主合成）** — 位置：设置 →「开发者」→「渲染」分组（原 0.94 的布尔 `UIToggle` **已被替换**，页面只保留这一个深度开关）。
   - 控件：`segmented(new String[]{"关闭","开启","自动（推荐）"})`，索引 0/1/2 绑定 `CompPrefs.get().perPixelDepthMode`（0.96 默认 **2 = AUTO**）。
   - 回写：`prefs.perPixelDepthMode = mode;`（布尔只作旧逻辑镜像 `perPixelDepth = (mode == 1)`）→ `CompPrefs.save()` → `CompRenderer.invalidateDepthModeCaches()`（清 RenderCache + FrameServer + Precache 一次）→ `panel.setStatus(...)` → `rebuild()` 让分段按钮显示新选中。
   - 显示兼容：读取时用 `depthModeIndex(mode, legacyOn)` —— `mode==0 && perPixelDepth==true`（0.94 旧设置文件）显示为「开启」；field 缺失的新旧 prefs 走默认 2 显示「自动（推荐）」；用户显式点一次后才写入新字段。
   - 工具提示：「自动（推荐）：只有两个相交的透视 3D 平面才逐像素比较深度；平行平面/单 3D 层/纯 2D 仍走整层排序，速度不变。开启：所有 3D 层都走逐像素深度，每个 3D 层多 3 个全屏 pass，低配机可能掉帧。关闭：回到整层排序。旧工程里 0.94 的布尔开关仍按「开启」读取」。
2. **钢笔保持顶点数（AE）** — 位置：设置 →「开发者」→ 新增「钢笔工具」分组（放在 `settings == null` 早退之前，所以没有打开工程也能改；这是全局 `CompPrefs` 偏好）。
   - 控件：`toggle("", CompPrefs.get().penKeepVertexCount, cb)`，回写 `prefs.penKeepVertexCount = value; CompPrefs.save();` 并 `panel.setStatus(...)` 说明 AE 语义。
   - 工具提示：插入/删除锚点保持顶点总数不变、靠重映射其余点完成，已有动画路径不整体重排；关闭回退旧版「新增/移除即改索引」行为。`UIPenTool` 在下一次输入时自行读取该字段，不需要宿主初始化调用。

### 10.3 新增纯逻辑测试（`UiSettingsMotionTest`，7 个）

| 测试 | 断言 |
| --- | --- |
| `rowStaggerDelayIsMonotonicAndCapped` | 行延迟 15ms/行、首行 0、负索引 0、单调、封顶 200ms |
| `staggerOffsetScalesWithProgressAndIntensity` | 行入场位移 4px×intensity、随进度单调收缩、负进度按 0% 处理 |
| `navUnderlineSlidesToZeroWhenActive` | 下划线 inset 10→0、intensity 0 时不动、越界 clamp |
| `sectionChevronMovesTwoPixelsBetweenStates` | 折叠 chevron −2→+2px、clamp |
| `footerAlphaFadesInLast` | 页脚 alpha 0.3→1、单调、clamp |
| `depthModeIndexKeepsLegacyBooleanWorking` | 三态显示索引：旧布尔 true→显示“开启”、AUTO 优先、越界 clamp |
| `animationsOffSnapsEverySettingsChannel` | 关动效时行/导航/折叠 spring 第一帧即目标 → 位移 0、下划线到位、页脚全亮 |

动效纯逻辑测试累计：7（本轮）+6（曲线）+8（全局 prune）+10（反馈）+9（spring）= **40 个**。

### 10.4 回归结果

- 串行包装器全量（含所有并行任务测试，最终）：**127 suites / 780 tests / 0 failed / 0 errors / 1 skipped，BUILD SUCCESSFUL**。
  其中 `UiSettingsMotionTest` 7/7、动效相关 5 个测试类共 40 个全绿。
- 执行期间并行任务（AM 新效果、深度 AUTO、钢笔、render-cache 审计）曾出现临时红/临时编译错误；
  均来自其他协作者进行中的改动，其收尾后复跑全量已全绿。
- 本任务未改 `client/render/**`、`comp/**`、`client/replay/**`、`CompPrefs`；只读 `CompPrefs` 新字段并调用
  深度队友新增的 `CompRenderer.invalidateDepthModeCaches()`。

### 10.5 Round 5 风险

1. **分组折叠是真实布局变化**（不是纯绘制）：折叠行用 `setVisible(false)` 隐藏并 `scroll.resize()`，BBS `ColumnResizer` 跳过不可见子元素所以不会留洞；但这是本轮唯一会改变布局的操作（用户主动折叠时），动画只加在标题 chevron/rail（spring）上，行的出现/消失本身仍是瞬时。
2. **设置行错峰只统计 `Row` 与 `SectionHeader`**：`UI.label(...)` 与整行按钮不占延迟索引，因此实际延迟比 `index×15ms` 略小（仍在 12–18ms/行语义内）。
3. **分类切换过渡只动绘制**：180ms 内内容整体偏移最多 6px，命中矩形在最终位置；快速点击可能比画面早 ≤6px 命中（与此前入场的同类取舍一致）。
4. **分类按钮选中态从 BBS `SELECTED` 皮肤改为自绘覆盖**：视觉从“整块 accent 填充”变为“淡 wash + 左 rail + 滑动下划线”，仍保持同一 UICompButton 的 hover/press 反馈；真机若觉得选中不够明显可调 `renderNavSelection` 的 alpha。
5. **旧 `perPixelDepth` 布尔不会被自动迁移**：设置页只在用户点一次三态后写 `perPixelDepthMode`；
   读取时兼容 `mode==0 && boolean==true → ON`（0.94 旧文件），字段缺失的新旧 prefs 走默认 **AUTO（2）**。
   渲染侧 gate 需继续兼容布尔 true=ON（已由 depth 队友实现并通过测试）。
6. **真机验收点**：切分类内容 6px 上滑 + veil 淡出、分类下划线滑动、设置行 12–18ms 错峰、点分组标题折叠/展开 spring、页脚最后淡入；开发者页三态深度显示旧布尔为“开启”且切换后预览缓存刷新；钢笔行开关状态保存；开始页工程行 hover/选中 wash；引导页圆点扫过有过渡；关动效后全部一帧到位。

