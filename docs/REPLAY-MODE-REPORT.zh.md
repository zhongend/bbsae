# 实体回放编辑模式 · 实施报告（P0–P4 代码已落地 · P5 文档）

> 对应需求 5：主界面切换按钮；BBS 原版回放编辑搬进 bbsae 3D 空间；原版模型库；原版轨道；原版 gizmo；
> 复刻 AE 的模型导入体验。
>
> 构建方式（2026-11-12 起全队统一，串行互斥）：
>
> ```powershell
> powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
> powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 compileJava --offline
> ```
>
> 代码路径：`src/main/java/dev/bbsae/client/replay/`（新包，11 个文件）+ `BbsAeClient`（入口/注册/释放）。
> 测试路径：`src/test/java/dev/bbsae/client/replay/`（4 个文件，18 个 test）。
> 集成钩子：`docs/REPLAY-MODE-INTEGRATION.zh.md`（Lead 需要插入 `UICompositorPanel` 的 3 行）。

---

## 1. 分期状态

| 期 | 内容 | 状态 | 说明 |
| --- | --- | --- | --- |
| **P0** | 面板骨架 + dashboard 注册 + 回退路线 A + 纯逻辑单测 | ✅ 完成 | `UIReplayModePanel extends UIDashboardPanel`；`BbsAeClient.onDashboardPanels` 注册「实体回放」入口；顶部「原版回放编辑」一键回退；18 个单测全绿。 |
| **P1** | 3D 视口渲染 film 全部 replay 模型 + 相机操作 + 点选模型 | ✅ 代码完成（真机待验） | 自建带深度附件的 FBO（`ReplayModelPass`）→ `FilmEntityRenderer.renderEntity`；相机 = `CompViewportCamera` + comp↔world 映射；右键环绕 / Shift+右键或中键平移 / 滚轮对准光标缩放；左键经 stencil 拾取选中 replay。 |
| **P2** | 原版轨道接上（选择 replay → TrackCatalog sheets → 关键帧读写） | ✅ 代码完成（真机待验） | `UIKeyframeEditor` + `UIFilmKeyframes` + `UIReplaysEditor.renderRuler` + `ReplayTrackBuilder`（`TrackCatalog.forPart` + `UIReplaysEditorUtils.buildSheets` + 原版 curated channels/sections/颜色/图标 + disabled-sheet 过滤）。 |
| **P3** | 原版 gizmo 拖动写回 + undo | ✅ 代码完成（真机待验） | `UIReplayViewport implements GizmoViewport` + `GizmoInteraction` + `Gizmo.INSTANCE`（视觉/句柄拾取都在原版 pass）；写回走原版 `UIReplayPropTransform`（x/y/z、yaw/headYaw/bodyYaw/pitch，`yaw=-y`）；Ctrl+Z/Y 走 `filmPanel.undo()/redo()`。 |
| **P4** | 模型库 `UIFormPalette` 接入 + 自动关键帧 | ✅ 代码完成（真机待验） | 顶部「模型库」按钮与回放列表的模型槽都走 BBS 原版 `UIFormPalette.open(...)`；选中后 `FormUtils.copy` 落到 replay.form（`ReplayFormSwap`），重建实体并在播放头自动写 x/y/z + 角度关键帧。 |
| **P5** | 打磨 + 本报告 + 真机清单 | ✅ 文档完成 | 本文 + 集成文档；打磨项见 §6 风险/待验。 |

---

## 2. 文件职责（11 个 replay 文件 + 入口）

### 2.1 `src/main/java/dev/bbsae/client/replay/`

| 文件 | 职责 |
| --- | --- |
| `ReplaySceneSpace.java` | **comp↔world 映射（纯 JOML）**：`(x,y,z)_comp = (S·x, −S·y, −S·z)+anchor`，默认 `flipZ=true` 取正行列式（纯旋转，不镜像、不破坏背面剔除）；`centerOn` 把影片某点放到 comp 原点；`toComp/toWorld/compFromWorld/worldFromComp`。 |
| `ReplayTime.java` | **frame↔tick 换算**：BBS 影片 tick（20/s）↔ bbsae 合成秒/帧；`phase()` 取 sub-tick；单测覆盖。 |
| `ReplayWriteback.java` | **Replay 通道写回语义**：位置三轴同 tick 全写；`yaw=-y`；转身同时带 headYaw/bodyYaw 偏移。以 `Channel` 接口解耦，单测不需要 BBS/GL。 |
| `ReplayFormSwap.java` | **模型选择落到 Form**：先 copy 再写 replay.form 槽（BBS 原版同样 copy），泛型化以便单测。 |
| `ReplayEditSession.java` | 编辑会话：借用 dashboard 唯一 `UIFilmPanel` 的 film/controller/entities/undo；playhead 用 filmPanel 的 tick cursor（共享）；`ensureEntities/recreateEntities`；`applyPose`（keyframes.apply + properties.applyProperties）；两个相机（BBS Camera 给 gizmo、MC Camera 给 `FilmEntityRenderer`）；`frameAll` 框选全部模型；播放推进（面板 transport）。 |
| `ReplayViewCamera.java` | 从 `CompViewportCamera` 生成两种相机：`mchorse.bbs_mod.camera.Camera`（view = `viewport.view · compFromWorld`，位置/投影/朝向）与 `net.minecraft.client.render.Camera`（`FilmEntityRenderer` 需要的 MC 相机，`setPos/setRotation` 是 protected，用私有子类 `ActorCamera` 调）。 |
| `ReplayModelPass.java` | **P1 3D 模型 pass**：私有 `GlFramebuffer(w,h,true)`（颜色+深度）→ 设投影/模型视图 → 逐 replay `applyPose` + `FilmControllerContext.setup(...)` + `FilmEntityRenderer.renderEntity(...)` → `GlStateGuard` 全量还原 → `batcher.texturedBox` 一次性 blit 到面板；`FormRenderLast.suspend/restore`、`BufferRenderer.resetCurrentVertexBuffer`、FBO 持久复用/停机释放。 |
| `ReplayPickPass.java` | **P1/P3 拾取 pass**：照抄 BBS `FilmStencilPicker` 序列——`StencilFormFramebuffer` + `StencilMap`，模型 id 从 `Gizmo.STENCIL_MAX+1` 起；原版 gizmo 句柄用 `Gizmo.INSTANCE.renderStencil(stack, UIReplayPropTransform.MASK)` 写入 1..STENCIL_MAX；`pickGUI(context, area, 7, STENCIL_MAX)` 读回；模型点击 → replay list index。 |
| `UIReplayViewport.java` | **3D 视口 UI 元素**：渲染背景/模型/拾取/原版 gizmo 视觉（`GizmoInteraction.renderGizmo` + sphere highlight + readout）；相机导航（轮询窗口按键，理由同合成器）；HUD（模型数/当前回放/偏航俯仰距离/手势提示）；实现 `GizmoViewport`（stencil/projection/area/startGizmo/pickGizmoForm）；`startGizmo` 用 `GizmoDrag.fromRenderedGizmo` + `FilmMatrices.getReplayWorldAxes` 建立拖动。 |
| `ReplayTrackBuilder.java` | **P2 轨道行装配**：`TrackCatalog.forPart(form, properties, part)` + `UIReplaysEditorUtils.buildSheets` + 原版 curated channels（position_rotation/states/hotbar/equipment/controls/velocity 分组、section 标题/图标/颜色）+ `disabledSheets`/`owner.disabledTracks` 过滤 + `pruneTree`。 |
| `UIReplayModePanel.java` | **面板本体**：布局 = 顶部工具条（上一帧/下一帧/播放/框选全部/模型库/原版回放编辑 + 状态行）/ 左「原版 `UIReplaysListPanel`」/ 中「3D 视口」/ 右「原版 `UIReplayPropertiesPanel`」+ 底部「原版 `UIKeyframeEditor`」；`bind(filmPanel, film, replay)`；`onAppear` 自动绑定 dashboard 影片；时间轴在控件有真实尺寸后 prime（TEAM-CONTEXT 坑）；Ctrl+Z/Y、空格、左右方向键；回退路线 A。 |

### 2.2 改动

| 文件 | 改动 |
| --- | --- |
| `client/BbsAeClient.java` | `replayModePanel` 字段；`onDashboardPanels` 里注册 `IKey.constant("实体回放")` + `Icons.PLAYER`；新增 `openReplayMode(UIDashboard, UICompositorPanel)`、`openReplayMode()`、`replayModePanel()`；`CLIENT_STOPPING` 释放 `ReplayModelPass` 的 FBO。 |

### 2.3 测试

| 文件 | 断言 |
| --- | --- |
| `ReplaySceneSpaceTest`(7) | 世界→comp→世界往返；1.8 格 = 115.2px；矩阵与点助手一致（按列主序逐元素比对）；默认映射行列式 > 0（不镜像）；`flipZ=false` 为镜像且非默认；`centerOn` 把目标放到 comp 原点；可配置 px/格。 |
| `ReplayTimeTest`(5) | 1s=20 tick；帧→最近 tick；tick→帧；duration→至少 1 tick；sub-tick phase。 |
| `ReplayWritebackTest`(3) | 位置三轴在同 tick 各一 key；转身 `yaw=-y` 且 head/body 保留偏移；第二个 tick 追加不覆盖。 |
| `ReplayFormSwapTest`(3) | 选择模型 → copy 后写入槽（不写 live form）；空选择不触碰槽；copy 失败不写半成品。 |

---

## 3. 验证结果

### 3.1 单测（本包）

```
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain --tests "dev.bbsae.client.replay.*"
→ BUILD SUCCESSFUL，18 tests
```

### 3.2 全量测试（运行时刻的树状态）

```
powershell ... gradle-run.ps1 test --offline --console=plain
→ 500 tests completed, 2 failed, 1 skipped
  EffectRepeatTest.gridRepeatDrawsCopiesOutsideTheLayerRectangle  （effects 队友在改 EffectRenderer/ShaderSources，非 replay）
  MaskGlRenderTest.initializationError                          （mask/pen 队友在改 MaskCache/环境，非 replay）
```

> 说明：这两个失败均不在 `replay` 范围（`git status` 显示 effects/pen 队友正在改对应源码与测试）；
> replay 包 18 个测试与 `compileJava` / `compileTestJava` 均为绿。若 Lead 收尾时仍失败，请以对应负责人为准。

---

## 4. 真机验证清单（交给 Lead，**不要**自己开客户端截图）

前置：进世界 → 打开一个已有影片（影片编辑器）→ 从合成器点「实体回放」或从 dashboard 面板条点「实体回放」。

1. **入口/回退**
   - 合成器预览右上出现 PLAYER 图标按钮；点它 → 切到「实体回放」面板，状态行显示 `影片 <id> · 回放 n/m · tick …`。
   - dashboard 面板条出现「实体回放」页，点它也能进（onAppear 自动绑定当前影片）。
   - 点面板顶部「原版回放编辑」→ 回到原版影片编辑器并显示原版回放编辑 dock（路线 A 兜底）。
2. **P1 3D 显示/相机**
   - 进面板即看到影片里所有启用的 replay 模型（至少当前回放），模型站在各自世界位置（换算到 comp 空间）。
   - 右键拖动环绕、Shift+右键/中键平移、滚轮缩放（对准光标）；HUD 的偏航/俯仰/距离数字跟着变。
   - 左键点模型 → 左侧列表选中对应 replay、右侧属性面板切到它。
   - 点「框选全部」→ 所有模型进视野。
3. **P2 原版轨道**
   - 底部是原版 dope sheet：菱形关键帧、右键插值菜单、复制粘贴、标尺、播放头随 tick 移动。
   - 切换 replay → 轨道行变成该 replay 的 TrackCatalog 行（位置/旋转、状态、快捷栏、装备、操作、速度 + 模型骨骼/材质/IK 行）。
   - 拖播放头 → 3D 模型姿态跟随（关键帧采样）；拖菱形改时间。
4. **P3 原版 gizmo + 撤销**
   - 点中模型 → 出现原版三轴 gizmo（移动杆/旋转环，缩放/滚球按 MASK 不出现）。
   - 拖 Y 轴 → 模型跟手，轨道位置行在播放头出现关键帧；`Ctrl+Z` 回退、`Ctrl+Shift+Z`/`Ctrl+Y` 重做（必须走原版撤销栈；切回原版影片编辑器仍应看到同一关键帧）。
   - 拖旋转环 X/Y → pitch/yaw（含 headYaw/bodyYaw 偏移）写回；再撤销。
5. **P4 原版模型库 / AE 式导入**
   - 点顶部「模型库」或右侧属性面板模型槽 → BBS 原版模型库铺满面板；选择模型 → 面板恢复、3D 立即换模型、播放头自动出现位置/角度关键帧；Esc 退出。
   - 双击模型库里的模型 → 打开原版模型编辑器改骨骼/材质 → 关闭 → 回放跟着变。
   - 从左侧列表右键「添加回放 / 复制 / 删除 / 分类 / 搜索 / 拖拽排序」→ 3D 视口与轨道同步（删除后模型消失）。
6. **与 BBS 原版对照（R4 标定）**
   - 同一影片、同一 tick、同一机位：本面板 3D 视口的模型位置/朝向应与原版影片编辑器预览（或合成器 BBS 预览层）一致。
   - 若发现模型「前后」相反，把 `ReplaySceneSpace.flipZ` 作为标定开关（默认 true = 不镜像）；若发现整体 180°，改 `ReplayViewCamera` 的 yaw/pitch 常量（界面上表现为环绕方向不对）。
7. **性能**
   - 200 replay 的影片：3D 视口帧率目标 ≥ 30；当前每帧对每个启用 replay 调一次 `applyPose`（与合成器 entity matte 同量级）。若掉帧，下一期可加「视锥/选中优先裁剪」。

---

## 5. 关键设计决定（与 plan 的一致性）

1. **不 new 第二个 `UIFilmPanel` / `UIReplaysEditor` / `UIFilmController`**：面板借用 dashboard 唯一 filmPanel 的 film、controller entities、undo、cameraEditor（`IUIClipsDelegate`）。理由见 plan §0/R1/R2。
2. **轨道完全走原版**：`UIKeyframeEditor` + `UIFilmKeyframes(...).absolute()` + `TrackCatalog` + `UIReplaysEditorUtils.buildSheets` + `UIReplaysEditor.renderRuler`；行数据/分类/过滤/折叠/右键菜单都是原版行为。
3. **gizmo 单宿主**：本面板激活时原版影片面板不在屏上（`disappear` 已停世界渲染/gizmo），bbsae 视口是该帧唯一 `Gizmo.INSTANCE` 使用者；视觉与 stencil 句柄同源（都走原版 `Gizmo`/`UIReplayPropTransform`）。
4. **撤销**：Ctrl+Z/Y 显式调 `filmPanel.undo()/redo()`；**没有**给 `film.preCallback` 赋值（plan R6）。
5. **模型选择 = BBS 原版模型库**：`UIFormPalette.open(this, false, replay.form.get(), cb)`；不引入外部 3D 文件导入 API（BBS 无此 API，plan R8）。
6. **回退路线 A**：原版回放编辑按钮保证 100% 原版体验。

---

## 6. 风险 / 已知缺口（需真机确认或下期处理）

| # | 风险 | 缓解/现状 |
| --- | --- | --- |
| R1 | **3D 模型渲染未经真机**（本环境禁止开客户端）。FBO/深度/矩阵状态恢复照抄已在生产的 `ReplayEntityMask`/`CompViewportRenderer` 范式，但模型 pass 在 UI 帧内运行是新的 | 真机按 §4.2/§4.6 验证；若模型不出现先看 `-Dbbsae.debugViewport`/FBO 尺寸与 `ReplayModelPass` 日志（失败只记一次 warn） |
| R2 | **comp↔world 手性/朝向（plan R4）**：默认 `flipZ=true`（正行列式、不镜像），z 符号/整体朝向尚未与原版截图标定 | `flipZ` + `ReplayViewCamera` yaw/pitch 常量留作标定开关；真机对照原版同机位 |
| R3 | **gizmo 句柄拾取的一帧延迟**：UI 事件先于本帧 render，因此点击用上一帧 stencil（与 BBS 原版预览同款行为） | 真机确认手感；必要时提高拾取容差（`ReplayPickPass.HANDLE_PICK_RADIUS`） |
| R4 | **`FilmEntityRenderer` 的相机是 MC Camera**，gizmo 拖动用 BBS Camera；两者由同一 viewport camera 生成 | `ReplayViewCamera` 统一生成；MC yaw 偏移 180° 近似，billboard/粒子表单的朝向可能需真机微调 |
| R5 | **播放/同步**：面板自己的 transport 推进共享 `filmPanel` cursor；原版影片面板不在屏时其 runner 不推进 | 面板 `advancePlayback` 每帧推进；切回原版时 cursor 连续 |
| R6 | **原版列表/属性面板的副作用**：`UIReplaysListPanel` 在 add/dupe/delete 时会调原版 `filmPanel.replayEditor.setReplay/updateChannelsList`（隐藏面板状态被同步） | 这是「借用唯一 filmPanel」的直接结果，隐藏面板无渲染副作用；切回原版时状态一致（真机确认） |
| R7 | **`UIFormPalette` 是铺满 parent 的子元素且不可重入**；Esc 退出；模型编辑器会再开 overlay | 面板只从两个入口打开同一实例；`open` 返回 null 时提示「模型库已经打开」 |
| R8 | **自动关键帧**：换模型后写 x/y/z + yaw/headYaw/bodyYaw/pitch（`ReplayWriteback` 语义） | 与原版 gizmo 写回同一语义；真机确认撤销能被 `UIFilmUndoHandler` 合并为一个条目 |
| R9 | **`UICompositorPanel` 入口未接**：Lead 的布局重构正在进行，本包不碰该文件 | 见 `docs/REPLAY-MODE-INTEGRATION.zh.md` 的 3 行钩子（锚点 `this.preview.add(this.space3DButton);`，空位 x=1F,-160） |
| R10 | 全量测试当前 2 个失败在 effects/mask 范围（非 replay） | 以对应负责人收尾为准；replay 测试与编译均绿 |

---

## 7. 给 Lead 的交接

1. **需要 Lead 插入**：`UICompositorPanel` 构造函数、`this.preview.add(this.space3DButton);` 之后 3 行
   （按钮 + `BbsAeClient.openReplayMode(BBSModClient.getDashboard(), this)` + `preview.add`），
   详见 `docs/REPLAY-MODE-INTEGRATION.zh.md`。
2. **不需要 Lead 改**：主界面 dashboard 入口（`BbsAeClient.onDashboardPanels` 已注册）、回退路线 A（面板按钮已实现）、`BbsAeClient` 的 `CLIENT_STOPPING` 释放。
3. **真机验证**：按 §4 清单；如发现模型朝向/位置不对，优先用 `flipZ`/yaw 常量标定（R2），不要改映射结构。
4. **下一步（若继续 P5+）**：原版回放列表/属性面板的完整复用已到位；剩余打磨点是骨骼/姿势 gizmo（plan P3 的独立子任务，`UIReplaysEditorUtils.getEditableTransform` + `GizmoDrag` 自建轴）、动作 clips 轨道（需原版 `UIClipsPanel`，plan R13 降级为深链回原版面板）、性能裁剪。
