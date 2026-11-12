# 实体回放编辑模式 · 实施报告（P0–P5 · Round 2 收尾）

> 对应需求 5：主界面切换按钮；BBS 原版回放编辑搬进 bbsae 3D 空间；原版模型库；原版轨道；原版 gizmo；
> 复刻 AE 的模型导入体验。Round 2 补的是**真机可验证性 + 性能 + 空状态健壮性**。
>
> 构建方式（全队统一，串行互斥）：
>
> ```powershell
> powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
> powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 compileJava --offline
> ```
>
> 代码路径：`src/main/java/dev/bbsae/client/replay/`（新包，15 个文件）+ `BbsAeClient`（入口/注册/释放/调试钩子）。
> 测试路径：`src/test/java/dev/bbsae/client/replay/`（7 个文件，37 个 test）。
> 集成钩子：`docs/REPLAY-MODE-INTEGRATION.zh.md`（Lead 需要插入 `UICompositorPanel` 的 3 行，Round 2 未改动该文件）。

---

## 1. 分期状态

| 期 | 内容 | 状态 | 说明 |
| --- | --- | --- | --- |
| **P0** | 面板骨架 + dashboard 注册 + 回退路线 A + 纯逻辑单测 | ✅ 完成 | `UIReplayModePanel extends UIDashboardPanel`；`BbsAeClient.onDashboardPanels` 注册「实体回放」入口；顶部「原版回放编辑」一键回退。 |
| **P1** | 3D 视口渲染 film 全部 replay 模型 + 相机操作 + 点选模型 | ✅ 代码完成（Round 2 加真机钩子，待 Lead 截图验证） | 自建带深度 FBO（`ReplayModelPass`）→ `FilmEntityRenderer`；`CompViewportCamera` + comp↔world 映射；右键环绕 / Shift+右键或中键平移 / 滚轮对准光标缩放；左键 stencil 拾取选中 replay。 |
| **P2** | 原版轨道（TrackCatalog sheets → 关键帧读写） | ✅ 代码完成（待真机） | `UIKeyframeEditor` + `UIFilmKeyframes` + `UIReplaysEditor.renderRuler` + `ReplayTrackBuilder`。 |
| **P3** | 原版 gizmo 拖动写回 + undo | ✅ 代码完成（待真机） | `UIReplayViewport implements GizmoViewport` + `GizmoInteraction` + `Gizmo.INSTANCE`；写回走原版 `UIReplayPropTransform`；Ctrl+Z/Y → `filmPanel.undo()/redo()`。 |
| **P4** | 模型库 `UIFormPalette` + 自动关键帧 | ✅ 代码完成（待真机） | 「模型库」按钮 + 回放列表模型槽；`FormUtils.copy` 落 `replay.form`；重建实体并在播放头自动写位置/角度关键帧。 |
| **P5-R1** | 面板/真机清单/文档 | ✅ 完成 | 第一版报告与真机清单。 |
| **P5-R2** | **真机验证钩子 + 性能裁剪 + 空状态/健壮性** | ✅ 完成 | `ReplayDebugHook`（`-Dbbsae.debugReplayFilm` / `-Dbbsae.debugView=replay`）；`ReplayCulling` 视锥裁剪 + 选中优先；`ReplayLogLimiter` 失败日志限流；`ReplayCalibration` 标定常量可 `-D` 覆盖；空状态/错误提示；`onAppear` 自动绑定复核并修复「换影片后不回绑」。 |
| **P5-R3** | **真机验证收尾：崩溃/可见性/光照** | ✅ 本次完成（有截图证据） | 修复 ① `StackOverflowError`（列表选择回调 ↔ `scrollToReplay` 递归）；② 模型 pass 的 model-view 应为**旋转-only**（camera-relative 几何，不能带 comp 锚点平移）；③ UI pass 绑白色 lightmap（Sampler2）+ 固定 `setShaderLights`，否则模型全黑；④ 单模型 framing 最小距离 600px（只框有模型的 replay）；⑤ 验证钩子默认选中**有模型**的 replay。真机截图：`docs/verify/round48-replay-models.png`（模型有明暗 + 原版 gizmo 在模型上）。 |

---

## 2. 文件职责（15 个 replay 文件 + 入口）

### 2.1 `src/main/java/dev/bbsae/client/replay/`

| 文件 | 职责 |
| --- | --- |
| `ReplaySceneSpace.java` | **comp↔world 映射（纯 JOML）**：`(x,y,z)_comp = (S·x, −S·y, −S·z)+anchor`，默认 `flipZ=true` 取正行列式（纯旋转，不镜像）；`centerOn` 把影片某点放到 comp 原点；默认 S/flipZ 从 `ReplayCalibration`（系统属性）取；**Round 3：`linearFromWorld()`（无平移的 S，模型 pass 的旋转-only view 依赖它）**。 |
| `ReplayTime.java` | **frame↔tick 换算**：BBS 影片 tick（20/s）↔ bbsae 合成秒/帧；`phase()` 取 sub-tick。 |
| `ReplayWriteback.java` | **Replay 通道写回语义**：位置三轴同 tick 全写；`yaw=-y`；转身同时带 headYaw/bodyYaw 偏移；以 `Channel` 接口解耦。 |
| `ReplayFormSwap.java` | **模型选择落到 Form**：先 copy 再写 replay.form 槽（BBS 原版同样 copy）。 |
| `ReplayCalibration.java` | **Round 2 新增：标定/性能常量集中**，全部可 `-D` 覆盖：`bbsae.replayPixelsPerBlock`、`replayFlipZ`、`replayYawOffset`、`replayPitchOffset`、`replayCullThreshold`、`replayCullRadius`；解析严格（非法值回落默认、越界 clamp）。 |
| `ReplayCulling.java` | **Round 2 新增：视锥裁剪（纯 JOML）**：`FrustumIntersection` 对每个 replay 的世界位置做球体测试；**选中回放永远可见**；小影片（≤ 阈值）不裁剪；`shouldCull/countVisible/allVisible`。 |
| `ReplayLogLimiter.java` | **Round 2 新增：失败日志限流**：首次立刻记录，之后每 5s 一条并带 `(+N suppressed)`；时钟注入，可单测。 |
| `ReplayEditSession.java` | 编辑会话：借用唯一 `UIFilmPanel` 的 film/controller/entities/undo；playhead 用 filmPanel tick cursor；`ensureEntities/recreateEntities`；`applyPose`；双相机；`frameAll`；transport；**Round 2：`updateVisibility()` / `isReplayVisible()` / `visibleCount/culledCount/placeableCount`**；**Round 3：`frameAll` 只框有模型的 replay，单模型最小距离 `ReplayCalibration.frameMinDistance()`（默认 600px）；`hasModel(replay)`**。 |
| `ReplayViewCamera.java` | 从 `CompViewportCamera` 生成 BBS Camera（gizmo 拖动）与 MC Camera（`FilmEntityRenderer`；`setPos/setRotation` protected → 私有子类）；**Round 2：yaw/pitch 偏移走 `ReplayCalibration`**；**Round 3：`camera.view`/`ModelViewMat` 改为旋转-only（`viewport.view · linearFromWorld`，清掉平移），因为 film actor 几何是 camera-relative、BBS `Camera.view` 本身也是 rotation-only——这是模型“看不见”的根因**。 |
| `ReplayModelPass.java` | **3D 模型 pass**：私有颜色+深度 FBO → 逐可见 replay `applyPose` + `FilmEntityRenderer` → `GlStateGuard` 全量还原 → `texturedBox` blit；**Round 2：按 `session.isReplayVisible` 跳过裁剪模型、失败日志限流、`lastError/hasRecentError/lastDrawn/lastAttempted/lastSkipped` 供 HUD**；**Round 3：`ModelViewMat = rotation-only view`；UI pass 绑 16×16 全白 lightmap 到 `Sampler2`（`RenderSystem.setShaderTexture(2, …)`，BBS 粒子编辑器同款）+ 固定 `setShaderLights`，修复黑色剪影；texture 停机释放**。 |
| `ReplayPickPass.java` | **拾取 pass**：照抄 `FilmStencilPicker` 序列；模型 id = `Gizmo.STENCIL_MAX+1+replay 列表下标`（Round 2 修正：不再按「启用的第几个」计数）；原版 gizmo 句柄 id 1..MAX；**Round 2：同样只拾取可见模型、日志限流**。 |
| `ReplayTrackBuilder.java` | **轨道行装配**：`TrackCatalog.forPart` + `UIReplaysEditorUtils.buildSheets` + 原版 curated channels/分组/颜色 + disabled-sheet 过滤 + pruneTree。 |
| `UIReplayViewport.java` | **3D 视口 UI 元素**：模型/拾取/原版 gizmo 视觉 + 相机导航 + HUD；**Round 2：HUD 显示 `模型 drawn/placeable`、裁剪计数、空状态/失败原因（无回放/无模型/视野外/FBO 失败）**。 |
| `UIReplayModePanel.java` | **面板本体**：顶部工具条 + 左原版 `UIReplaysListPanel` + 中 3D 视口 + 右原版 `UIReplayPropertiesPanel` + 底部原版 `UIKeyframeEditor`；`bind`；**Round 2：`onAppear` 每次回绑 dashboard 当前影片（修复换影片后显示旧影片）、状态行空状态文案、`debugPrepare()` 验证预设**；**Round 3：`selectReplayFromList()`（列表回调，不 scroll）+ `applySelection(replay, scrollList)` + `selectingReplay` re-entrancy guard，修复 `scrollToReplay → 列表回调 → selectReplay → scrollToReplay` 的 StackOverflowError**。 |
| `ReplayDebugHook.java` | **Round 2 新增：真机验证钩子**：满足条件时在客户端 tick 里取影片（属性指定 id → 磁盘上第一个有 replay 的影片 → 内存合成 3 个原版生物 replay 的影片，不落盘），`fill` 进 dashboard 唯一 `UIFilmPanel`，再 `BbsAeClient.openReplayMode(...)` 并应用 `debugPrepare`（选 replay/seek/相机）。 |

### 2.2 改动

| 文件 | 改动 |
| --- | --- |
| `client/BbsAeClient.java` | 上一轮：`replayModePanel` 注册 + `openReplayMode(...)` + 停机释放 FBO。**Round 2：`ReplayDebugHook.install()`；`-PbbsaeView=replay` 时跳过合成器 debug 视图（避免抢面板）。** |

### 2.3 测试（7 个文件 / 38 个 test）

| 文件 | 数量 | 断言 |
| --- | --- | --- |
| `ReplaySceneSpaceTest` | 8 | 往返、1.8 格=115.2px、矩阵与点助手一致、默认映射行列式>0、flipZ=false 为镜像、centerOn、px/格可配置、`linearFromWorld` 无平移且保留 S 符号 |
| `ReplayTimeTest` | 5 | 20 tick/s、帧→tick、tick→帧、duration→≥1 tick、sub-tick phase |
| `ReplayWritebackTest` | 3 | 三轴同 tick、`yaw=-y`、head/body 偏移、多 tick 追加 |
| `ReplayFormSwapTest` | 3 | copy 后落槽、空选择不写、copy 失败不写半成品 |
| `ReplayCalibrationTest` | 6 | 默认值、属性覆盖、非法值回落、越界 clamp、严格解析器、`ReplaySceneSpace` 默认跟随属性 |
| `ReplayCullingTest` | 7 | 视锥内可见/外裁剪、选中永不裁剪、null 位置视为可见、半径保留边缘、allVisible/countVisible、阈值开关 |
| `ReplayLogLimiterTest` | 6 | 首次必记、区间内吞掉并计数、下一区间放行、suffix、reset、非正间隔 |

---

## 3. 真机验证入口（Round 2 新增，Lead 用）

### 3.0 谁跑

本任务**不开客户端**：钩子只负责「一次客户端 run 就能看到有内容的 3D 回放页」，运行与截图由 Lead 在真机验证轮执行。

### 3.1 一条命令

```powershell
# 打开「实体回放」面板并自动装入一个可渲染的影片，tick 260 截图（沿用现有 -PbbsaeShot）
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 runClientView -PbbsaeView=replay -PbbsaeShot=replay-models

# 指定真实影片：clientView 已把 -PbbsaeFilmId 转发成 -Dbbsae.debugFilmId，钩子会在该 id 真实存在时使用它
powershell ... runClientView -PbbsaeView=replay -PbbsaeFilmId=export_test -PbbsaeShot=replay-film

# 截图后自动退出（有界运行，方便脚本/自动化）
# 追加 -PbbsaeQuitAfterShot=true
```

`-PbbsaeView=replay` 会设置 `-Dbbsae.debugPreview=true -Dbbsae.debugView=replay`；`ReplayDebugHook.isRequested()`
为真时 **`BbsAeClient` 的合成器 debug 视图整段跳过**（否则它会在 tick 120 先把面板切成合成器），
而 `-PbbsaeShot` 的截图钩子不受影响。

> 说明：只有 `-PbbsaeView` / `-PbbsaeShot` / `-PbbsaeFilmId` 这类 build.gradle 里映射过的属性才能从
> Gradle 命令行透传到客户端 JVM；`bbsae.debugReplayFilm` 等直接属性需要通过 run 配置的 `vmArg`
> 或 `-Dbbsae.debugReplayFilm=...` 在客户端 JVM 参数里给出（下一轮可让 Lead 在 `clientView` 里加 4 行
> `vmArg` 映射，本任务不允许改 build.gradle）。默认自动选影片 + 合成影片已经能覆盖截图验证。

### 3.2 调试属性

| 属性 | 默认 | 作用 |
| --- | --- | --- |
| `-Dbbsae.debugReplayFilm=<id>` | 空 | 从 `BBSMod.getFilms()` 载入该影片；空则自动挑第一个有 replay 的影片。也接受 build.gradle 已转发的 `-Dbbsae.debugFilmId`（只在 id 真实存在时采用） |
| `-PbbsaeFilmId=<id>` | — | **推荐的真机用法**：clientView 已把它转发为 `-Dbbsae.debugFilmId`，钩子会用它指定真实影片（例如 `export_test`） |
| `-Dbbsae.debugView=replay` | 空 | 等价于「自动选影片」开关（配合 `-PbbsaeView=replay`） |
| `-Dbbsae.debugReplaySynth=true` | false | 强制使用内存合成影片（3 个原版生物 replay，不落盘），用于 run/ 下没有任何影片时；需客户端 JVM 参数 |
| `-Dbbsae.debugReplayTicks=60` | 60 | 第几个客户端 tick 执行（世界/玩家就绪后才执行） |
| `-Dbbsae.debugReplayTick=0` | 0 | 打开后停到的 tick（默认 0，合成影片的关键帧都在 0） |
| `-Dbbsae.debugReplaySelect=0` | 0 | 选中第几条 replay（列表下标，从 0 起） |
| `-Dbbsae.debugReplayYaw/Pitch/Distance` | 自动框选 | 覆盖自动框选后的相机姿态，用于固定机位对比截图 |
| `-Dbbsae.replayPixelsPerBlock` | 64 | comp 像素/方块（真机标定，见 §4） |
| `-Dbbsae.replayFlipZ` | true | false = Z 镜像（诊断用，会翻转背面剔除） |
| `-Dbbsae.replayYawOffset` / `replayPitchOffset` | 180 / 0 | MC Camera 朝向标定常量 |
| `-Dbbsae.replayCullThreshold` / `replayCullRadius` | 32 / 3.0 | 视锥裁剪阈值（replay 数）与包围球半径（格） |

### 3.3 期望日志（grep `Replay debug`）

```
Replay debug hook armed: film='', view='replay', opening at tick 60
Replay debug hook: picked film 'export_test' (...) / synthesized film 'bbsae_debug_replay' with 3 mob replays (not saved)
Replay debug hook: opened film='...' replays=N panel=true
Replay debug: booked select=0 tick=0 yaw=NaN pitch=NaN distance=NaN
Replay debug: playhead tick 0
Replay debug ready: film=... replays=N placeable=N selected=0 tick=0 yaw=.. pitch=.. distance=..
```

### 3.4 截图应看到什么

- 顶部「实体回放」工具条 + 状态行（影片/回放/tick；空状态时有可读原因）。
- 中间 3D 视口：存活 replay 的模型（合成影片是僵尸/骷髅/村民），HUD 第 2 行 `模型 drawn/placeable`。
- 选中模型上有 BBS 原版三轴 gizmo（移动杆 + yaw/pitch 旋转环）。
- 左侧原版回放列表、右侧原版属性面板、底部原版 dope sheet（位置/旋转行有 tick 0 关键帧）。

### 3.5 没有影片 / 没有模型时

- 无影片：面板状态行显示「没有打开的影片：先在影片编辑器打开一个影片，或从合成器点『实体回放』（调试可用 -Dbbsae.debugReplayFilm=<id>）」。
- 有影片无 replay：状态行「没有回放：点『原版回放编辑』…」；视口 HUD 同义提示。
- 有 replay 无模型：状态行 + 视口提示「回放还没有模型：选中回放后点『模型库』」。
- 模型全被裁剪：HUD「模型不在视野内：点『框选全部』或滚轮缩小」。
- FBO/渲染失败：HUD 红字「3D 渲染失败：<原因>（详细堆栈见客户端日志）」，日志限流（5s 一条 + 抑制计数）。

### 3.6 Round 3 真机结果（0.93 收尾，已出截图证据）

**命令（Lead 指定，一次运行）：**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 runClientView --offline `
  -PbbsaeView=replay -PbbsaeShot=replay-models -PbbsaeShotTicks=420 -PbbsaeQuitAfterShot=true --console=plain
```

**结果：BUILD SUCCESSFUL，截图 `run/screenshots/replay-models.png` → 复制为
[`docs/verify/round48-replay-models.png`](verify/round48-replay-models.png)（1920×1080）。**

截图里看到：

- 面板结构完整：标题「实体回放」+ 工具条、左侧原版回放列表（`minecraft:donkey`）、中间「实体回放 · 3D 空间」、
  右侧原版属性（Pick/Edit、Enabled、Replay display name、Name tag、Shadow、Miscellaneous、合成器入口）、
  底部原版 dope sheet（位置/状态/快捷栏/装备/操作 + 可见 visible/lighting/color_/pose/transform/anchor/mobId 轨道 + 0..2800 标尺）。
- **3D 视口中央可见模型（驴）**：有明暗与轮廓（耳朵、头部、身体），不是黑色剪影。
- **原版 gizmo 叠在模型上**：红/绿旋转环、红/绿移动杆、蓝色屏幕移动块 —— 即 `UIReplayPropTransform.MASK` 的 yaw/pitch + MOVE/SCREEN 句柄。
- HUD：`模型 1/1 · minecraft:donkey`、`偏航 0° 俯仰 20° 距离 600 比例 64px/格`；状态行 `影片 export_test · 回放 2/2 · tick 0/3116`。
- 日志关键行：
  `Replay debug hook: picked film 'export_test' (export_test) from 6 available` →
  `Replay debug ready: film=export_test replays=2 placeable=1 selected=1 tick=0 yaw=0.0 pitch=20.0 distance=600.0` →
  `screenshot: Saved screenshot as replay-models.png`；**无 `StackOverflowError`、无 `Replay mode could not …` 警告**。

**本轮修掉的 4 个真机问题（按发现顺序）：**

1. **崩溃**：`selectReplay` 无条件 `scrollToReplay`；BBS `UIReplayList.scrollToReplay` 会 `refreshReplayList()+pick()` 同步触发列表选择回调
   → 又回到 `selectReplay` → 无限递归 `StackOverflowError`。修复：列表回调走 `selectReplayFromList`（`scrollList=false`），
   程序化/3D 拾取走 `selectReplay`（`scrollList=true`），统一 `applySelection(...)` 里 `selectingReplay` re-entrancy guard + `try/finally`。
2. **模型不可见（几何在视锥外）**：`FilmEntityRenderer` 产出的几何是 **camera-relative 世界（blocks）**，所以
   `RenderSystem` 的模型视图必须是**旋转-only 的 block→view**（`viewport.view · linearFromWorld`，平移清零）；
   之前用带 `anchor` 平移的 `viewport.view · compFromWorld`，把模型整体推移了一个目标距离。BBS `Camera.view` 本身就是 rotation-only，
   gizmo 的 `computeWorldOrigin`/`renderInterface` 也依赖该约定（`ReplayViewCamera.rotationOnlyView`）。
3. **黑色剪影（光照）**：UI pass 没有绑世界 lightmap（实体 shader 的 Sampler2），采样为黑。修复照 BBS 粒子编辑器
   （`UIParticleSchemeRenderer`）的做法：pass 内绑 16×16 全白 lightmap 到 unit 2，并 `RenderSystem.setShaderLights(LIGHT0, LIGHT1)`；
   渲染后恢复 shader color，lightmap 纹理在 `release()` 释放。
4. **framing 把模型放进相机内部**：`camera.frame` 对单模型（近似零 bounds）给出 distance≈3；且把没有模型的 replay 也纳入 bounds
   （第一次 5590px）。修复：`frameAll` 只框有模型的 replay（无模型时才回退全部实体），并在 `camera.frame` 后把 distance 抬到
   `ReplayCalibration.frameMinDistance()`（默认 600px）；验证钩子默认选**有模型**的 replay。

**本轮未覆盖（下一轮真机）：** 模型明暗与 BBS 原版同机位/同光照的逐像素对照；`-Dbbsae.debugReplaySynth` 合成影片路径；
gizmo 实际拖动（写回关键帧 + Ctrl+Z）与模型库换模型的交互操作（静态截图只证明视觉/位置正确）；>32 replay 的裁剪性能。

---

## 4. 性能 / 标定（Round 2）

- **视锥裁剪**：`ReplayModelPass` 与 `ReplayPickPass` 只在 `ReplayCulling.shouldCull(影片 replay 数)` 为真
  （默认 >32）时使用裁剪结果；视图外且未选中的 replay 不付 `applyPose`/`FilmEntityRenderer` 的代价。
  选中回放永远画（gizmo 不消失）；小影片完全不变（无 pop-in 风险）。
- **每帧一次**：`ReplayEditSession.updateVisibility()` 由视口在模型 pass 前计算一次，颜色/拾取两个 pass 共用，
  不会出现「看得到却点不到」。
- **日志限流**：两 pass 的失败日志走 `ReplayLogLimiter`（首次立即、之后 5s 一条、带 `(+N suppressed)`）。
- **标定集中**：`ReplaySceneSpace` 的 S/flipZ 与 `ReplayViewCamera` 的 yaw/pitch 偏移都从 `ReplayCalibration`
  读取系统属性；另外 `-Dbbsae.replayFrameMinDistance`（默认 600）控制单模型自动框选的最小相机距离（真机发现 3px 会把相机放进模型里）。
- **视图约定（Round 3 真机定案，勿改）**：film actor 几何是 camera-relative blocks，`RenderSystem` 的 model-view 必须是
  **rotation-only** 的 block→view（`ReplayViewCamera.rotationOnlyView` / `ReplaySceneSpace.linearFromWorld`）；BBS `Camera.view`
  同样约定为 rotation-only（`computeWorldOrigin` 依赖）。UI pass 里 actor 用 16×16 全白 lightmap（Sampler2）渲染成全亮，
  真实世界光照留给 film preview/导出——这是编辑视口的常规做法（BBS 粒子编辑器同源）。

---

## 5. 空状态 / 错误提示（Round 2）

| 场景 | 位置 | 文案/行为 |
| --- | --- | --- |
| 面板没有绑定影片 | 面板状态行 | 「没有打开的影片…（调试可用 -Dbbsae.debugReplayFilm=<id>）」 |
| 影片无 replay | 状态行 + 视口 HUD | 「没有回放：点『原版回放编辑』…」 |
| 有 replay 无模型/实体 | 状态行 + HUD | 「回放还没有模型：选中回放后点『模型库』…」 |
| 未选中 replay | HUD | 「未选择回放：左键点 3D 模型，或在左侧列表选择」 |
| 全部被裁剪 | HUD | 「模型不在视野内：点『框选全部』或滚轮缩小」 |
| 模型尝试了但一个都没画出来 | HUD | 「模型未显示：检查回放是否启用、模型是否加载…」 |
| FBO/渲染异常 | HUD（红字） | 「3D 渲染失败：<原因>（详细堆栈见客户端日志）」，6 秒后不再显示 |
| 换影片后再次进面板 | `onAppear` | 每次都对比 dashboard film panel 的 `getData()`，变了就重新 `bind`（Round 2 修复点） |
| 面板从 dashboard bar 进入 | `onAppear` | 自动绑定 dashboard 当前影片（无需先经过合成器按钮） |

---

## 6. 验证结果

### 6.1 replay 单测

```
powershell ... gradle-run.ps1 test --offline --console=plain --tests "dev.bbsae.client.replay.*"
→ BUILD SUCCESSFUL，38 tests（7 suites）
```

### 6.2 编译

```
powershell ... gradle-run.ps1 compileJava compileTestJava --offline --console=plain
→ BUILD SUCCESSFUL
```

### 6.3 全量测试（Round 3 收尾）

```
powershell ... gradle-run.ps1 test --offline --console=plain
→ BUILD SUCCESSFUL，96 suites / 565 tests，0 failed，1 skipped
```

### 6.4 真机

```
runClientView -PbbsaeView=replay -PbbsaeShot=replay-models -PbbsaeShotTicks=420 -PbbsaeQuitAfterShot=true
→ BUILD SUCCESSFUL；截图 docs/verify/round48-replay-models.png；无 StackOverflowError
```

### 6.5 UTF-8

本次所有写入的 `.java`/`.md` 均通过严格 UTF-8 自检（只使用 write/edit 工具，不使用 shell 重定向写源码）。

---

## 7. 真机验证清单（Lead 执行）

前置：用 §3.1 的命令；或手动：进世界 → 打开一个影片 → 合成器/面板条进「实体回放」。

1. **入口/回退**
   - 合成器预览右上 PLAYER 图标 → 实体回放面板；dashboard 面板条「实体回放」也能进。
   - 顶部「原版回放编辑」→ 回到原版影片编辑器并显示原版回放编辑 dock（路线 A 兜底）。
   - 换一个影片再进面板 → 状态行显示的影片 id 随之变化（Round 2 修复的 rebind）。
2. **P1 3D 显示/相机**
   - 打开即见全部启用 replay 的模型；右键环绕 / Shift+右键或中键平移 / 滚轮缩放；HUD 数字跟随。
   - 左键点模型 → 左侧列表与右侧属性面板同步选中。
   - 「框选全部」把全部模型纳入视野；HUD `模型 drawn/placeable` 合理。
3. **P2 原版轨道**
   - 底部原版 dope sheet：菱形关键帧、右键插值菜单、复制粘贴、标尺、播放头随 tick 移动。
   - 切换 replay → 轨道行变该 replay 的 TrackCatalog 行；拖播放头 → 3D 模型姿态跟随。
4. **P3 原版 gizmo + 撤销**
   - 点中模型 → 原版三轴 gizmo（MASK 内：移动杆/屏幕块 + yaw/pitch 旋转环）。
   - 拖 Y 轴 → 模型跟手 + 轨道位置行出现关键帧；`Ctrl+Z` 回退、`Ctrl+Shift+Z`/`Ctrl+Y` 重做；
     切回原版编辑器看到同一关键帧。
5. **P4 原版模型库 / AE 式导入**
   - 「模型库」/属性面板模型槽 → BBS 原版模型库；选模型 → 3D 立即换、播放头自动出现位置/角度关键帧；Esc 退出。
   - 双击模型 → 原版模型编辑器改骨骼/材质 → 关闭 → 回放跟着变。
   - 左侧列表右键添加/复制/删除/分类/搜索 → 3D 与轨道同步。
6. **性能**
   - 大影片（>32 replay）旋转相机不掉帧；HUD 显示「裁剪 visible/total」；
     `-Dbbsae.replayCullThreshold=0` 可观察全画/裁剪差异。
7. **标定（若朝向/比例不对）**
   - 同机位与原版对照；模型前后相反 → `-Dbbsae.replayFlipZ=false` 试一次（诊断）；
   - 整体朝向偏 → `-Dbbsae.replayYawOffset`；比例偏 → `-Dbbsae.replayPixelsPerBlock`。

---

## 8. 关键设计决定（与 plan 的一致性）

1. **不 new 第二个 `UIFilmPanel` / `UIReplaysEditor` / `UIFilmController`**：借用 dashboard 唯一 filmPanel 的 film、controller entities、undo、cameraEditor（`IUIClipsDelegate`）。理由见 plan §0/R1/R2。
2. **轨道完全走原版**：`UIKeyframeEditor` + `UIFilmKeyframes(...).absolute()` + `TrackCatalog` + `UIReplaysEditorUtils.buildSheets` + `UIReplaysEditor.renderRuler`。
3. **gizmo 单宿主**：本面板激活时原版影片面板不在屏上，bbsae 视口是该帧唯一 `Gizmo.INSTANCE` 使用者；视觉与 stencil 句柄同源。
4. **撤销**：Ctrl+Z/Y 显式调 `filmPanel.undo()/redo()`；**没有**给 `film.preCallback` 赋值（plan R6）。
5. **模型选择 = BBS 原版模型库**：`UIFormPalette.open(...)`；不引入外部 3D 文件导入（BBS 无此 API，plan R8）。
6. **回退路线 A**：原版回放编辑按钮保证 100% 原版体验。
7. **Round 2 调试钩子只服务验证**：不改变正常运行路径；`fill` 在客户端 tick（主线程）里同步执行，不使用异步仓库、不 new 面板、不改磁盘（合成影片不落盘）。

---

## 9. 风险 / 已知缺口

| # | 风险 | 缓解/现状 |
| --- | --- | --- |
| R1 | **3D 模型/ gizmo 已真机可见**（`docs/verify/round48-replay-models.png`），但**未做原版同机位同光照的逐像素对照**；模型在编辑视口里是全亮 lightmap（有意如此，film preview 走世界光照）；**gizmo 拖动/换模型/撤销等交互尚未真机操作** | 下一轮真机：对照原版 + 拖 gizmo 写回 + Ctrl+Z + 模型库换模型；失败点可看 HUD/限流日志 |
| R2 | **comp↔world 手性/朝向（plan R4）** | 默认 `flipZ=true`（正行列式）；`ReplayCalibration` 全部可 `-Dbbsae.replay*` 覆盖，真机对照后只改属性 |
| R3 | **actor pass 的 view 必须是 rotation-only** | 已写入 `ReplayViewCamera.rotationOnlyView` 注释与 `ReplaySceneSpace.linearFromWorld`：camera-relative 几何 + BBS `Camera.view` 约定；不要改回带 anchor 平移的 `compFromWorld`（Round 3 的“视口空”就是它） |
| R4 | **裁剪的一帧近似** | 可见性用实体上一帧位置（`getX/Y/Z`）做视锥测试；切换/seek 后第一帧可能多画或少画一个，选中永远可见，影响可忽略 |
| R5 | **gizmo 句柄拾取有一帧延迟** | 与 BBS 原版预览同款（UI 事件早于本帧 render）；容差 `HANDLE_PICK_RADIUS=7` |
| R6 | **合成调试影片路径（`-Dbbsae.debugReplaySynth`）本轮未走真机** | 本轮用的是真实 `export_test`（2 replay / 1 placeable），自动选片 + 默认选中可玩 replay 均生效；合成影片逻辑保留为无影片兜底 |
| R7 | **fill 进 filmPanel 的副作用** | 调试用 `fill` 与用户打开影片同一路径；合成影片 id `bbsae_debug_replay` 不落盘，`BBSSettings.recentData` 可能记一条最近影片（仅 dev run 目录） |
| R8 | **原版列表/属性面板副作用** | add/dupe/delete 会同步隐藏的 `filmPanel.replayEditor`（借用唯一面板的预期结果，切回原版状态一致） |
| R9 | **全量测试** | Round 3 收尾全绿：96 suites / 565 tests / 0 failed / 1 skipped；replay 包 38 tests 全绿 |

---

## 10. 给 Lead 的交接

1. **真机验证（已完成一次）**：`gradle-run.ps1 runClientView -PbbsaeView=replay -PbbsaeShot=replay-models -PbbsaeShotTicks=420 -PbbsaeQuitAfterShot=true` →
   BUILD SUCCESSFUL，无 StackOverflowError，截图 [`docs/verify/round48-replay-models.png`](verify/round48-replay-models.png) 显示模型（有明暗）+ 原版 gizmo；
   日志 grep `Replay debug`。下一轮建议：与原版同机位对照、拖 gizmo + Ctrl+Z、模型库换模型、>32 replay 裁剪性能。
2. **仍需 Lead 插入的 3 行钩子**：`UICompositorPanel` 构造函数 `this.preview.add(this.space3DButton);` 之后（`x(1F,-160)`），见 `docs/REPLAY-MODE-INTEGRATION.zh.md`。
3. **标定**：若截图里模型朝向/比例与原版不一致，用 §3.2 的 `-D` 属性标定（不要改映射结构/不要改 rotation-only 视图约定），把最终值告诉我可以固化默认。
4. **下一步（可选）**：骨骼/姿势 gizmo（plan P3 独立子任务）、动作 clips 轨道（plan R13 深链回原版）、超大规模影片的「只同步选中 + 视野内」进一步裁剪。
