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
4. **可选共享优化**：现在 prune 节奏由各宿主自带。若希望 `UIAssetBrowser`/`UILayerLibrary` 等既有动效宿主
   （不在本任务写域）也自动 prune，最省事的是在 `UiMotion.tickFrame()` 内部每 60 帧自动 `prune` 一次
   （`UiMotion` 不在本任务写域，故未改）；这会把所有既有宿主的通道泄漏一起治好。

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
| 在 `UiMotion` 内加 spring/tween/prune 等 | 0.92 已由前序工作完成，本任务**只调用** | `UiMotion` 不在本任务写域 |
| 新增 `CompPrefs` 的 `buttonFeedback/listStagger/panelTransitions` 开关 | **未加**，复用现有总开关 + 强度/速度 | 写域禁止改 `CompPrefs`；总开关已能覆盖“全部关掉”的诉求，拆细开关等 Lead 决定 |
| `prune` 统一在 `UiMotion` 内 | 各宿主每 60 帧自行 prune | 同上；已在第 5 节建议 Lead 后续上移到 `tickFrame` |
| `UiPageTransition` / `UiLayoutTween` 宿主接线 | 未接线 | 属于 `UICompositorPanel`（Lead 重构中）写域 |
| `UICompCurveEditor`（时间轴曲线页）hover | 未改 | 不在本任务写域，且 2400+ 行、风险高；留给后续 |
