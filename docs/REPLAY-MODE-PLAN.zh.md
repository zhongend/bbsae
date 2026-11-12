# 实体回放编辑模式 · 技术调研与实施计划

> 需求（用户原话要点）：主界面加「实体回放」编辑切换按钮；照搬 BBS 原版回放编辑；bbsae 的 3D 空间里
> 所有 BBS 模型都要能 3D 显示、编辑动作，要有 BBS 原版 gizmo；模型选择直接调用 BBS 原版模型选择库；
> 切换后就是跟原版一样的编辑界面（轨道也用原版回放编辑轨道）；目标是复刻 AE 的模型导入功能。
>
> 本文只做调研，**没有修改 `src/` 下任何源码**。临时验证产物在 `_research/team/javap/`。
>
> **行号说明**：本文行号取自本次调研时的工作副本（2026-11-12）。`UICompositorPanel.java` 正被
> 其他协作者改动，**以文中给出的「可搜索锚点文本」为准**，行号只用于快速定位。
>
> 核对基线：
> - 源码：`_research/bbs-fs-1.20.1/`（`mod_version=2.7`、`minecraft_version=1.20.1`，与 jar 分支一致）
> - 签名：`libs/bbs-2.7-1.20.1.jar`，逐个 `javap -p -classpath libs/bbs-2.7-1.20.1.jar <类>`
> - 1.20.4 对照源：`_research/bbs-fs/`（`minecraft_version=1.20.4`）

---

## 0. 结论摘要（先看这 8 条）

1. **不能 `new` 第二个 `UIFilmPanel`**。`UIFilmPanel` 的布局回调会调用
   `BBSRendering.setCustomSize(...)`（`applyPreviewSizeToBBS`），正是 TEAM-CONTEXT §6 里
   「预览绝不能给 BBS 开 setCustomSize」那颗雷；而且它会再造一个 `UIFilmController`，
   与 `BbsPreviewBridge` 已经接管的那个抢世界渲染。
2. **也不能把 `UIReplaysEditor` 挂两份**。它的构造函数与 `setReplay` 会直接改写
   `filmPanel.actionEditor.setClips(...)`、把 `UIClipsPanel` 重新挂到自己身上
   （`attachActionTimeline`），并且固定 undo id `"replay_keyframe_editor"`。
3. **但「原版回放编辑」的每一块都有干净的可复用缝，其中轨道这一块完全不需要 UIFilmPanel**：
   `UIKeyframeEditor` + `UIFilmKeyframes(IUIClipsDelegate, Consumer<Keyframe>)` +
   `TrackCatalog.forPart(Form, FormProperties, String)` + `UIKeyframeSheet(TrackDescriptor)` +
   `UIReplaysEditorUtils.buildSheets(List, List)`。
4. **模型选择库可以一行接入**：`UIFormPalette.open(parent, editing, form, callback)`
   （`ui/forms/UIFormPalette.java:28/33`）只要求 parent 有 `UIContext`，与影片编辑器无关。
5. **gizmo 是单例**：`Gizmo.INSTANCE` + `FilmControllerContext.gizmoTarget/gizmoView/gizmoViewportHeight/stencil`
   + `UIReplayPropTransform`。同帧只认最后一次 `captureVisual`，所以 bbsae 的 3D 视口必须
   「独占一帧的 gizmo」。
6. **模型 3D 渲染已有现成范式**：`ReplayEntityMask.drawInto`（`client/render/ReplayEntityMask.java:660`）
   已经在私有 FBO 里用 `FilmEntityRenderer.renderEntity(FilmControllerContext.setup(...))` 画 BBS 模型。
   3D 视口只需自建一个 BBS `Camera`（`projection` 借 `CompViewportCamera`，`view` 乘一个 comp↔world 映射）。
7. **撤销只有一条正确路径**：`Film.preCallback` 这个槽已被 `UIFilmUndoHandler` 独占
   （`UIFilmPanel.java:1451-1453`），所以 Ctrl+Z 必须显式调 `filmPanel.undo()/redo()`，
   **绝不能给 `film.preCallback` 赋值**。
8. **BBS 没有 3D 模型文件导入 API**。`RegisterImportersEvent` 只覆盖 PNG/WAV/GIF
   （`importers/` 下只有 `PNGImporter`/`WAVImporter`/`GIFImporter`/`OldSkinImporter` 等），
   外部 OBJ/FBX 导入属于超范围高风险项；「AE 式模型导入」应落到
   「BBS 模型库 → 新建/替换 replay 的 Form → 自动打关键帧」。

---

## 1. 三条可行路线（先定架构，再谈实现）

| 路线 | 做法 | 达标度 | 风险 | 建议 |
| --- | --- | --- | --- | --- |
| **A. 纯切换** | 「实体回放」按钮 = `dashboard.setPanel(filmPanel)` + `filmPanel.showPanel(filmPanel.replayEditor)`，界面 100% 原版 | 「照搬原版」满分；**没有 bbsae 3D 空间** | 极低 | 作为 P0 兜底/回退开关，1 小时可做 |
| **B. bbsae 新面板 + 复用原版控件 + 自绘 3D**（推荐） | 新增 `UIReplayModePanel extends UIDashboardPanel`；内部：3D 视口（模型走 `FilmEntityRenderer`）+ 原版轨道（`UIKeyframeEditor`/`UIFilmKeyframes`/`TrackCatalog`）+ 原版 gizmo（`Gizmo.INSTANCE`）+ 原版模型库（`UIFormPalette`）+ 原版属性（`UIReplayPropTransform`/`UIReplayPropertiesPanel`） | 全部需求可落 | 中（坐标映射、gizmo 单例、GL 状态） | **主路线**，分 P0–P5 |
| **C. 挂进原版影片预览** | `RegisterPreviewOverlaysEvent` 注册一个 `UIFilmPreview` 子元素，把 bbsae 3D 视口画在原版预览上；原版轨道/列表/属性/gizmo/模型库原封不动 | 「跟原版一样」满分，且**零 UI 复制** | 低-中（要吃原版预览的输入与尺寸；模式切换是覆盖层不是面板） | 备选加速路线，可与 B 并存 |

> 推荐组合：**B 为主线**（满足「bbsae 3D 空间」这个硬要求），**A 作为降级/回退**，
> **C 作为二期加速项**（如果用户更看重「原版感」而非「合成器面板内切换」）。

---

## 2. BBS 原版影片/回放编辑器结构

### 2.1 数据模型（`src/main/java/mchorse/bbs_mod/`）

| 类 | 文件 | 关键成员（jar 已核对） |
| --- | --- | --- |
| `Film extends ValueGroup` | `film/Film.java` | `Clips camera`、`Replays replays`、`Categories replayCategories`、`FilmMarkers markers`、`calculateDuration()` |
| `Replay extends ValueGroup` | `film/replays/Replay.java` | `ValueForm form`、`ReplayKeyframes keyframes`、`FormProperties properties`、`Clips actions`、`enabled`、`category`、`label`、`nameTag`、`shadow*`、`looping`、`actor`、`fp`、`relative`、`relativeOffset`、`axesPreview(Bone)`、`getTick(int)`、`getLastKeyframeTick()`、`getName()`、`normalizeCategory(String)` |
| `ReplayKeyframes extends ValueGroup` | `film/replays/ReplayKeyframes.java` | `KeyframeChannel<Double> x/y/z/pitch/yaw/headYaw/bodyYaw/vX/vY/vZ`、`states`、`sneaking…`、`CURATED_CHANNELS`、`apply(float, IEntity)`、`preNotify(int)`、`postNotify()` |
| `FormProperties extends ValueGroup` | `film/replays/FormProperties.java` | `Map<TrackId, KeyframeChannel> tracks`、`getOrCreate(Form, TrackId)`、`applyProperties(Form, float)` |
| `Replays` | `film/replays/Replays.java` | `addReplay()`、`getById(String)`、`remove(Replay)`、`getList()` |
| `FilmControllerContext` | `film/FilmControllerContext.java` | `instance`、`setup(Map<String,IEntity>, IEntity, Replay, Camera, MatrixStack, VertexConsumerProvider, float)：85`、`.transition/.stencil/.shadow/.color/.gizmoTarget/.gizmoView/.gizmoViewportHeight/.bone2/.nameTag/.relative` |
| `FilmEntityRenderer` | `film/FilmEntityRenderer.java` | `renderEntity(FilmControllerContext)`；内部 `renderAxes：261`、`renderAnchorGizmo：340`、`renderReplayGizmo：397` |
| `BaseFilmController` | `film/BaseFilmController.java` | `Map<String,IEntity> entities`、`getTick()`、`paused`、`createEntities()`、`startRenderFrame(float)`、`render(WorldRenderContext)` |

`FilmControllerContext` 是「一次演员渲染」的全部上下文；**gizmo 的绘制/拾取开关就在它身上**，这是复用 BBS gizmo 的关键接口。

### 2.2 `UIFilmPanel`：原版影片编辑器（`ui/film/UIFilmPanel.java`）

```
UIFilmPanel extends UIDataDashboardPanel<Film> implements IFlightSupported, IUIOrbitKeysHandler, ICursor
  public UIElement main, editArea;  public UIDockLayout dock;
  public UIFilmPreview preview;     public UIFilmRecorder recorder;
  public UIClipsPanel cameraEditor; public UIReplaysEditor replayEditor; public UIClipsPanel actionEditor;
  private UIFilmController controller;   // :132
```

- 构造函数 `UIFilmPanel(UIDashboard)：167`，内部顺序很重要：
  `preview = new UIFilmPreview(this)：182` → `cameraEditor = new UIClipsPanel(this, factory)：185` →
  `replayEditor = new UIReplaysEditor(this)：193` → `actionEditor = new UIClipsPanel(...)：195` →
  `replayEditor.attachActionTimeline(actionEditor)：197`。
- 停靠面板注册（这正是「原版轨道/列表/属性」三个 dock 页）：
  `dock.addPanel(PANEL_EDIT_AREA_ID, editArea)`、`PANEL_MAIN_ID, main`、`PANEL_PREVIEW_ID, preview`、
  `PANEL_REPLAYS_LIST_ID, replayEditor.replaysList`、`PANEL_REPLAY_PROPS_ID, replayEditor.replayProperties`
  —— `:318-322`。
- 数据填充：`fillData(Film)：` 里 `cameraEditor.setClips(data.camera)`、`actionEditor.setClips(null)`、
  `replayEditor.setFilm(data)` —— `:1467-1471`。
- 撤销接线：`this.undoHandler = new UIFilmUndoHandler(this); data.preCallback(this.undoHandler::handlePreValues);`
  —— `:1451-1453`（**单槽 callback**，见 §9 R6）。
- 尺寸雷：`applyPreviewSizeToBBS()` 调 `BBSRendering.setCustomSize(...)`（`:834` 附近的
  `applyExportSizeToBBS` 同款），`UIFilmPreview.getViewport()` 还读
  `BBSRendering.getVideoWidth/Height`（`ui/film/UIFilmPreview.java:354`）。

### 2.3 `UIFilmController`（`ui/film/controller/UIFilmController.java`）

```
UIFilmController extends UIElement implements GizmoViewport
  public final UIFilmPanel panel;               // :11 (javap)
  public FilmEditorController editorController;
  public final FilmKeyframeInsertion keyframes; public final FilmRecordingController recorder;
  public final FilmStencilPicker picker;        public final OrbitFilmCameraController orbit;
  public final OrbitViewGizmo orbitGizmo;
  public StencilFormFramebuffer getStencil();   // :298
  public FilmTarget getEditTarget();            // 当前 gizmo 目标（ROOT / ANCHOR / BONE）
  public Area getGizmoArea();                   // = panel.preview.getViewport()
  public Matrix4f getGizmoProjection();
  public boolean startGizmo(UIContext, int);
```

它**已经实现 `mchorse.bbs_mod.ui.utils.GizmoViewport`**（5 个方法），所以「gizmo 宿主」这件事
BBS 有现成接口可照抄，但它的 `getGizmoArea()` 指向原版预览，bbsae 必须自己实现一个宿主（§5.5）。

### 2.4 `UIReplaysEditor`：原版回放编辑器（`ui/film/replays/UIReplaysEditor.java`）

```
UIReplaysEditor extends UIElement implements IBoneSelectionHost
  public UIReplaysListPanel replaysList;          // :96
  public UIReplayPropertiesPanel replayProperties;// :97
  public UITimelineCategoryBar iconBar;           // :110
  public UIKeyframeEditor keyframeEditor;         // :115（每次切轨道重建）
  public final UIReplayPropTransform replayTransform; // :125
  public UIReplaysEditor(UIFilmPanel filmPanel);  // :303
  public void setFilm(Film);                      // :417
  public void setReplay(Replay, boolean, OrbitReaction); // :487
  public void updateChannelsList();               // :543
```

**它对 `UIFilmPanel` 的硬依赖（逐条，改造时必须替换）**：

| 位置 | 用法 | 说明 |
| --- | --- | --- |
| `:307-308` | `new UIReplaysListPanel(filmPanel, …)`、`replayProperties.attachReplayList(...)` | 两个子面板构造函数都要 panel |
| `:423` / `:508` / `:512` | `filmPanel.getController().orbit.reset()/teleportPivotToReplay()` | 原版轨道相机 |
| `:516` | `filmPanel.actionEditor.setClips(replay.actions)` | **会改写原版面板状态** |
| `:535` | `filmPanel.getKeyframeCursor(...)`（`ICursor`） | 播放头 |
| `:638-639` | `new UIKeyframeEditor(cb -> new UIFilmKeyframes(filmPanel.cameraEditor, cb).absolute()).target(filmPanel.editArea)` | 轨道 + 属性面板宿主 |
| `:652-653` | `renderRuler(..., filmPanel.cameraEditor, film.camera, 0)`；`view.duration(() -> film.camera.calculateDuration())` | 标尺与时长 |
| `:1051-1143` | `clickViewport` 用 `filmPanel.getController().getStencil()`、`filmPanel.getCamera()`、`filmPanel.isFlying()`、`filmPanel.showPanel(this)` | 视口点击拾取 |
| `:1165-1183` | `close()`、`teleport()` 用 `filmPanel.getData()/getCursor()` | 收尾 |

### 2.5 dashboard 面板注册机制（决定「按钮放哪」）

`ui/dashboard/UIDashboard.java`：

```java
protected void registerPanels() {                       // :303
    buildStep("morphing", () -> panels.registerPanel(new UIMorphingPanel(this), …));
    buildStep("film",     () -> panels.registerPanel(new UIFilmPanel(this), …));   // :306
    …
    buildStep("add-ons",  () -> BBSMod.events.post(new RegisterDashboardPanelsEvent(this))); // :311
}
```

- 面板是**懒构建**的（`buildSteps` 队列，`buildNextStep()`），`dashboard.getPanel(UIFilmPanel.class)`
  在完全构建后总能拿到**唯一实例**。
- addon 注册入口：`RegisterDashboardPanelsEvent.dashboard.getPanels().registerPanel(panel, IKey, Icon)`。
  bbsae 现用点：`client/BbsAeClient.java:605-615`。
- `UIDashboardPanels.setPanel(UIDashboardPanel)` 是切换面板的唯一正路；`UIDashboard.open()` 会
  再次触发当前面板的 appear/disappear（bbsae 的 `BbsPreviewBridge.attach` 已为此幂等）。

### 2.6 1.20.1 / 1.20.4 / jar 三方核对

- 两棵 `src/` 源码树 **文件清单完全一致**（各 1847 文件），仅有 **24 个文件内容不同**；
  其中与本需求相关的只有 `film/BaseFilmController.java`（`updateTrackedPositionAndAngles` 多一个
  vanilla 参数）、`ui/framework/UIBaseMenu.java`（滚轮事件签名）、`ui/framework/UIScreen.java`、
  `client/BBSRendering.java`（仅注释差异）、`ui/dashboard/DashboardWarmup.java`（import 路径）。
  结论：**引用到的类在两分支间 API 等价**，读 1.20.1 源码 + jar 校验即可。
- 已逐个 `javap` 核对并留档于 `_research/team/javap/`：
  `UIFilmPanel`、`UIReplaysEditor`、`UIReplaysListPanel`、`UIReplayList`、`UIReplayPropertiesPanel`、
  `UIReplayPropTransform`、`ReplayListEntry`、`UIFilmKeyframes`、`UIPlacementGizmo`、`Gizmo`、
  `GizmoDrag`、`GizmoInteraction`、`GizmoViewport`、`UIFilmController`、`Replay`、`ReplayKeyframes`、
  `Film`、`Replays`、`BaseFilmController`、`FilmControllerContext`、`FilmEntityRenderer`、`FilmTarget`、
  `FilmMatrices`、`StencilMap`、`StencilFormFramebuffer`、`UIPropTransform`、`UIKeyframeEditor`、
  `UIKeyframes`、`UIKeyframeSheet`、`TrackCatalog`、`TrackDescriptor`、`TrackId`、`FormProperties`、
  `Camera`、`UIFormPalette`、`UIFormList`、`NestedEdit`、`UIDashboard`、`UIDashboardPanel(s)`、
  `UIDataDashboardPanel`、`UIEditorDashboardPanel`、`FormCategories`、`ModelFormRenderer`、
  `ModelInstance`、`ModelForm`、`Form`、`FormUtils`、`UIReplaysEditorUtils`、`UIFilmUndoHandler`。

---

## 3. 可复用控件清单（含「复用等级」）

等级说明：**A = 直接 new／直接调**；**B = 需要小适配（自己实现接口/提供宿主）**；
**C = 只能抄逻辑，不能实例化**。

| 能力 | 原版类 / 入口 | 宿主依赖 | 等级 |
| --- | --- | --- | --- |
| 轨道（dope sheet + 属性面板） | `UIKeyframeEditor`（`ui/framework/elements/input/keyframes/UIKeyframeEditor.java`）+ `UIFilmKeyframes(IUIClipsDelegate, Consumer<Keyframe>)`（`ui/film/utils/keyframes/UIFilmKeyframes.java:29`） | `IUIClipsDelegate`（15 方法）+ `target(UIElement)` | **A/B** |
| 轨道行数据 | `TrackCatalog.forPart(Form, FormProperties, String)：90` + `UIKeyframeSheet(TrackDescriptor)` + `UIReplaysEditorUtils.buildSheets(List, List)` | 无 | **A** |
| 轨道分类/过滤/折叠 | `TrackCategory`（`api/client/editor/`）、`TrackCategories`、`UIKeyframeSheet.Section`、`UIKeyframeDopeSheet.setExpanded(FoldState)` | 无 | **A** |
| 属性检查器（回放自身） | `UIReplayPropertiesPanel(UIFilmPanel)：82` | **要真实 `UIFilmPanel`** | B（传 bbsae 持有的实例） |
| 回放位移 gizmo 目标 | `UIReplayPropTransform`（`ui/film/replays/UIReplayPropTransform.java`） | 只要 `Replay`+`IEntity`+tick | **A** |
| 骨骼/姿势 gizmo 目标 | `UIKeyframeEditor.editor`（`UIPropTransform`）+ `UIReplaysEditorUtils.getEditableTransform(UIKeyframeEditor)` | 需要 keyframe editor | **B** |
| 姿势编辑器 | `UIPoseKeyframeFactory` → `UIPoseEditor`/`UIPoseTransforms`（`ui/utils/pose/`） | 由 keyframe factory 自动生成 | **A** |
| 动作（actions）轨道 | `UIClipsPanel(UIFilmPanel, IFactory)` + `UIReplaysEditor.attachActionTimeline(UIClipsPanel)` | **要真实 panel** | C（见 §4） |
| IK / 物理 / 动画转姿势 | `UIModelIKFormPanel`、`UIModelPhysicsFormPanel`、`UIAnimationToPoseOverlayPanel`、`UIReplaysEditorUtils.animationToPoseKeyframes(...)` | 大部分由 factory 拉起 | A/B |
| 模型选择库 | `UIFormPalette.open(UIElement parent, boolean editing, Form form, Consumer<Form> cb)`（`:28/:33`） | 任意有 `UIContext` 的 `UIElement` | **A** |
| 模型槽按钮 | `UINestedEdit(Consumer<Boolean>)` + `setForm(Form)` | 无 | **A** |
| gizmo 交互 | `GizmoInteraction`（`mouseClickedHandle/mouseClickedSphere/update/renderGizmo/renderSphereHighlight/renderReadout`） | 一个 `GizmoViewport` | **B** |
| gizmo 绘制/拾取 | `Gizmo.INSTANCE`（`start：543`、`captureVisual：667/672`、`renderStencil：981/986`、`renderInterface：709`、`reorientForSpace：1123`、`setViewportHeight：267`） | 世界/3D pass + UI pass 各一次 | **B** |
| 拾取缓冲 | `StencilFormFramebuffer`（`setup(Link)：106`、`apply()`、`pick(x,y,radius,handleMax)：201`、`unbind(StencilMap)`）+ `StencilMap` | 绑定中的 FBO | **B** |
| 播放器/相机 | `RunnerCameraController`（`filmPanel.getRunner()`）、`Camera`（`projection/view/position/rotation`） | panel 或自建 | B |

> 一句话：**除了 `UIReplaysEditor` 本身和 `UIClipsPanel`（动作轨道）之外，其余全部可复用**。

---

## 4. 「只复用控件」与「依赖整个 UIFilmPanel」的边界

### 4.1 依赖图（谁真的需要谁）

```
需要 UIFilmPanel（硬依赖）
├─ UIReplaysEditor            （构造参数；12 处内部调用，见 §2.4 表）
├─ UIReplaysListPanel         （构造参数 → UIReplayList）
├─ UIReplayList               （构造参数；addReplay/updateFilmEditor 调 panel.getController()/panel.replayEditor）
├─ UIReplayPropertiesPanel    （构造参数；预览相机按钮用 panel.preview / panel.getCamera()）
├─ UIClipsPanel               （动作轨道；构造参数）
├─ UIFilmPreview              （构造参数；且碰 BBSRendering 尺寸）
└─ UIPlacementGizmo           （构造参数；只服务 overlay clip，本需求用不到）

不需要 UIFilmPanel（可直接 new / 直接调）
├─ UIKeyframeEditor + UIKeyframes/UIFilmKeyframes      ← 只要 IUIClipsDelegate 或自己的 UIKeyframes 子类
├─ UIKeyframeSheet / TrackCatalog / UIReplaysEditorUtils（静态）
├─ UIPropTransform / UIReplayPropTransform / UIPoseEditor
├─ Gizmo（单例）/ GizmoDrag / GizmoInteraction / StencilFormFramebuffer
├─ UIFormPalette / UIFormList / UINestedEdit
├─ FilmEntityRenderer / FilmControllerContext / FilmMatrices（静态）
└─ Film / Replay / ReplayKeyframes / FormProperties（数据）
```

### 4.2 「只复用控件」的最小可行拼装（推荐做法）

```java
// 1) 轨道：BBS 原版编辑器 + 原版 film 时间轴语义
UIKeyframeEditor editor = new UIKeyframeEditor(
        cb -> new UIFilmKeyframes(filmPanel.cameraEditor, cb).absolute())   // 只用它的 IUIClipsDelegate
    .target(myInspectorColumn);                                            // 属性面板挂到自己这一栏
editor.view.rulerRenderer(ctx -> UIReplaysEditor.renderRuler(ctx, editor.view, filmPanel.cameraEditor, film.camera, 0));
editor.view.duration(() -> film.camera.calculateDuration());

// 2) 轨道行：原版 TrackCatalog 生成，原版 buildSheets 装配
List<TrackDescriptor> catalog = TrackCatalog.forPart(replay.form.get(), replay.properties, selectedPart);
List<UIKeyframeSheet> sheets = new ArrayList<>();
UIReplaysEditorUtils.buildSheets(catalog, sheets);   // + 回放自身 x/y/z、yaw… 的 curated sheet
for (UIKeyframeSheet s : sheets) editor.view.addSheet(s);

// 3) 模型库
UIFormPalette.open(this, false, replay.form.get(), form -> {
    replay.form.set(FormUtils.copy(form));
    filmPanel.getController().createEntities();
});

// 4) gizmo 目标
UIReplayPropTransform transform = new UIReplayPropTransform();
transform.syncFromReplay(replay, entity, filmPanel.getKeyframeCursor(0F));
Gizmo.INSTANCE.start(stencilIndex, mouseX, mouseY, transform, GizmoDrag.fromRenderedGizmo(camera, area));
```

这套拼装**不碰原版面板的任何字段**，只借用 `filmPanel.cameraEditor` 作为 `IUIClipsDelegate`
（它本身是只读的播放头/影片来源）。若担心耦合，可换成自写的 `IUIClipsDelegate` 适配器（15 个方法，
其中 `getFilm()` 返回 `film`、`getCursor()/setCursor()` 接 bbsae 的 `time*20`、`getCamera()` 返回视口相机、
`isRunning()` 接 bbsae 的 `playing`、`pickClip/setFlight/embedView/editMultiple` 空实现）。

### 4.3 「内嵌一个真实 UIFilmPanel / 其子控件」的可行方案与风险

如果一定要「整块原版界面」，有两条路：

**路线 C-1：内嵌真正的 `UIFilmPanel`（不推荐）**

- 做法：`UIReplayModePanel.bind()` 里 `add(new UIFilmPanel(dashboard))`，自己 `fill(film)`。
- 立刻会遇到：
  1. 它的 `dock.onLayoutSettled(this::applyPreviewSizeToBBS)`（`UIFilmPanel.java:317`）和
     `applyExportSizeToBBS()`（`:834`）会 `BBSRendering.setCustomSize(true, w, h)` ——
     世界帧被改成影片分辨率并 blit 到所有 dashboard 页（TEAM-CONTEXT §6 明令禁止）。
  2. 第二个 `UIFilmController` 会与 `BbsPreviewBridge.ensureControllerContext`（`:405`）
     抢同一个 controller 的父级；`UIFilmController` 构造时 `this.add(...)` 多个 HUD/gizmo 子元素，
     两套 gizmo 会争 `Gizmo.INSTANCE`。
  3. `fillData` 会把 `data.preCallback` 换成**新**的 `UIFilmUndoHandler`（`:1453`），
     原面板的撤销链被顶掉。
  4. 键盘：`UIFilmPanel` 注册了大量 `Keys.*`（`:246-306`），两份会同时响应。
- 结论：**不可行**，除非用 mixin 把 `applyPreviewSizeToBBS` 与 undo 接线拆开——那是另一个量级的改动。

**路线 C-2：把 `UIReplaysEditor` 作为子控件挂进 bbsae 面板（半可行，风险中等）**

- 做法（不改 BBS 源码）：
  ```java
  UIReplaysEditor re = new UIReplaysEditor(existingFilmPanel);  // 用 dashboard 里那个唯一实例
  re.relative(this).x(0).y(0).w(1F).h(1F);
  this.add(re);
  re.setFilm(film); re.setReplay(replay);
  ```
- 立刻会遇到：
  1. `attachActionTimeline` 已经用掉了 `filmPanel.actionEditor`（构造函数 `:197`），
     原版编辑器里那个 `UIClipsPanel` 只有一个父级；bbsae 这份再 `setActionsMode(true)` 会看到空区域。
     缓解：不调 `attachActionTimeline`，禁用「动作」页签（`setActionsMode` 由 `UIFilmPanel` 独占）。
  2. `re.setReplay()` 会覆盖 `filmPanel.actionEditor.setClips(...)`（`:516`）→ 原版面板切回来时状态被改。
     缓解：切回原版前 `filmPanel.replayEditor.setFilm(film)` 复位。
  3. `re.keyframeEditor` 的 undo id 固定 `"replay_keyframe_editor"`（`:641`），
     与原版共享同一撤销栈（这其实是**想要**的效果，但也意味着两份编辑器会互相 flush）。
  4. 点击视口时 `clickViewport` 走的是 `filmPanel.getController().getStencil()`（原版拾取缓冲），
     与 bbsae 3D 视口不是同一个缓冲 → **3D 空间里点模型选不中**。
     缓解：bbsae 自己处理拾取，把 `re.clickViewport` 屏蔽（子类覆写，或干脆不把鼠标事件传给它）。
  5. `showPanel(this)`（`:1095`、`:1159`）会切原版 dock 的页签（面板不可见时无副作用，但状态会变）。
- 结论：**能做，但只能得到「原版轨道+属性+列表」这三块，且动作页签残废、视口拾取对不上**；
  这也正是 §3 里把它标成 C 的原因。建议只在 P2 的**临时验证版**里用一次，正式实现走 §4.2。

---

## 5. bbsae 3D 空间里的模型渲染 / 定位 / gizmo

### 5.1 现有资产（都可直接复用或照抄）

| 资产 | 位置 | 作用 |
| --- | --- | --- |
| `CompViewportCamera` | `client/render/CompViewportCamera.java` | 3D 自由视口相机：`position/forward/right/up`、`view`、`projection`（perspective，`update()：124`）、`viewProjection`、`inverseViewProjection`；**comp 空间：像素、Y 向下、Z 朝观察者** |
| `CompViewportRenderer` | `client/render/CompViewportRenderer.java` | 3D 视口的场景 FBO（`renderScene：163`）+ 图层 pass（depth 写入）+ 一次性 blit（`draw：74`）；**ortho 投影 + 每 quad 自带世界矩阵** |
| `CompScene` | `client/render/CompScene.java` | comp 世界矩阵/父子级/深度；「世界空间就是合成像素」的权威定义（`:22-24`） |
| `ReplayEntityMask` | `client/render/ReplayEntityMask.java` | **已经在私有 FBO 里画 BBS 模型**：`captureActorState：157`（从 `FormPoseEvents.ACTOR_BEFORE` 抓相机/矩阵）、`resolveController：553`、`drawInto：660`（`replay.keyframes.apply` → `properties.applyProperties` → `FilmEntityRenderer.renderEntity`） |
| `ReplayEntityKey` | `client/render/ReplayEntityKey.java` | 覆盖度合成到图层（`drawTextured：41` / `draw：108`）；与 3D 显示无关，但示范了「世界帧→图层」的映射 |
| `BbsPreviewBridge` | `client/overlay/BbsPreviewBridge.java` | `attach：109`、`ensureControllerContext：405`（把 `UIFilmController` 挂到隐藏 carrier）、`sync：457`（拖影片播放头）、`renderFilmActors：476`（手动跑一次编辑器世界 pass） |

### 5.2 坐标映射（必须先定，否则一切对不上）

- bbsae comp：**像素**、X 右、Y 下、Z 朝观察者（`CompScene.java:22-24`）。
- BBS world：**方块**、X 东、Y 上、Z 南。
- 定义 `ReplaySceneSpace`：

```java
public final class ReplaySceneSpace {
    public float pixelsPerBlock = 64F;       // 工程设置项，默认让 1.8 格玩家 ≈ 115px
    public Vector3f anchor = new Vector3f(); // 世界原点在 comp 空间的落点
    public boolean flipZ = true;             // 朝向标定开关（见风险 R4）

    public Matrix4f compFromWorld();         // T(anchor) · S(+S,-S,±S)
    public Matrix4f worldFromComp();         // 逆
    public Vector3f toComp(Vector3d world);  // 单点版，给 UI/拖拽用
    public Camera cameraFor(CompViewportCamera view); // 见 5.3
}
```

- **符号选择**：`(x,y,z)_comp = (S·x, −S·y, ∓S·z)_world + anchor`。
  `y` 必须取反（Y 上→Y 下）；`z` 的符号决定行列式（=±S³）：
  - 取 `−S·z` → 行列式 `+S³`（纯旋转 `rotateX(180°)`），**不翻绕序、不破坏背面剔除**，
    代价是模型「前后」会相对于世界相机反转 → 用 `flipZ` 开关 + 一个 `yawOffset` 常量标定；
  - 取 `+S·z` → 行列式 `−S³`（镜像），需要 `RenderSystem.disableCull()`，容易出现「模型内外翻转」。
  **推荐前者**，并在 P1 用「与 BBS 原版预览同机位截图对比」的方式标定。

### 5.3 一次模型 pass 的完整序列（照抄 `ReplayEntityMask.drawInto` + `CompViewportRenderer.draw`）

```java
// A. 必须在 UI batcher flush 之后（否则 UI 顶点被画进 FBO）
batcher.flush();

// B. 自建 BBS Camera：投影借视口，视图乘映射
Camera camera = new Camera();
camera.projection = new Matrix4f(viewportCamera.projection);
camera.view       = new Matrix4f(viewportCamera.view).mul(sceneSpace.compFromWorld());
camera.position.set(sceneSpace.toWorld(viewportCamera.position));   // 给 GizmoSize/CameraUtils 用
camera.fov = viewportCamera.fov; camera.near = viewportCamera.near; camera.far = viewportCamera.far;

// C. 绑定目标（GlFramebuffer(w, h, true)，带深度）
GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.id);
GL11.glViewport(0, 0, w, h);
GL11.glClearColor(0,0,0,0); GL11.glClear(COLOR|DEPTH);
GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LEQUAL); GL11.glDepthMask(true);

// D. 把 BBS 要的矩阵推进 RenderSystem（FilmEntityRenderer/GizmoSize 都读它）
Matrix4f prevProj = new Matrix4f(RenderSystem.getProjectionMatrix());
MatrixStack mv = RenderSystem.getModelViewStack();
mv.push(); mv.peek().getPositionMatrix().identity(); RenderSystem.applyModelViewMatrix();
RenderSystem.setProjectionMatrix(camera.projection, VertexSorter.BY_Z);

// E. 关掉会互相打架的状态（Iris/BBS 会留残留）
GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_BLEND);
boolean queued = FormRenderLast.suspend();      // 延迟渲染必须暂停，否则落到错缓冲区

// F. 逐个 replay
for (Replay replay : film.replays.getList()) {
    IEntity entity = controller.entities.get(replay.getId());
    if (entity == null || entity.getForm() == null) continue;
    float tick = replay.getTick(controller.getTick()) + transition;   // 与 BBS 完全同相位
    replay.keyframes.apply(tick, entity);
    replay.properties.applyProperties(entity.getForm(), tick);
    RenderFrame.invalidate();
    FilmControllerContext ctx = FilmControllerContext.instance
        .setup(controller.entities, entity, replay, camera, stack, consumers, transition)
        .shadow(false, 0F).nameTag("").relative(replay.relative.get());
    if (replay == selected && showGizmo)                             // 视觉 capture
        ctx.gizmoTarget(FilmTarget.root(space)).gizmoView(camera.view).gizmoViewportHeight(area.h);
    FilmEntityRenderer.renderEntity(ctx);                            // ← 模型 + gizmo 都在这里
}

// G. 还原（一个都不能漏）
FormRenderLast.restore(queued);
RenderSystem.setProjectionMatrix(prevProj, BY_Z); mv.pop(); RenderSystem.applyModelViewMatrix();
BufferRenderer.resetCurrentVertexBuffer();      // 否则下一次世界/UI 绘制复用我们的顶点
GL11.glDepthMask(true); GL11.glDepthFunc(GL11.GL_LEQUAL); GL11.glDisable(GL11.GL_DEPTH_TEST);
// 绑回原 framebuffer + 原 viewport，然后 batcher.texturedBox(target.color.id, ...) 一次性贴到面板上
```

要点：
- **深度**：模型 pass 必须在「图层 quad 之前」或「自己独占一张 target」。
  现有 `CompViewportRenderer.renderScene` 用 `GlStateGuard.ortho(width,height)` 画图层，
  而模型需要 perspective，两者投影不同 → 推荐**两张 target**：
  `sceneTarget`（现有，图层）与 `modelTarget`（新，模型），最后按深度一次性合成；
  或 P0 先用「模型画在图层之上/之下」的简化顺序验证视觉，P2 再上双 target 深度合成。
- `transition` 必须用**影片相位**（`BbsPreviewBridge.sync` 已经在做：`runner.setCursor(seconds*20)`，
  分数部分即帧相位），不能用 MC 的 `tickDelta`（`ReplayEntityMask.java:190-199` 有完整踩坑记录）。
- `consumers` 传 `MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers()`
  或 `null`（`ReplayEntityMask.ensureCurrent` 传 `null` 也能画）。

### 5.4 定位 / 拖动 / 写回关键帧

**回放根变换（P1 先做这个）**

```java
UIReplayPropTransform transform = new UIReplayPropTransform();     // BBS 原版
transform.syncFromReplay(replay, entity, filmPanel.getKeyframeCursor(0F)); // 每帧，拖动中被内部忽略
GizmoDrag drag = GizmoDrag.fromRenderedGizmo(camera, area);        // 只吃 Camera + Area
Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, transform, drag);
```

`UIReplayPropTransform` 的写回语义（照抄即可，`ui/film/replays/UIReplayPropTransform.java`）：
- `setT` → `keyframes.x/y/z.insert(tick, …)`，并同时写 `entity.setPosition/setPrevX…`（防止 20Hz 抖动，`:137-152` 有解释）；
- `setR` → `keyframes.yaw/headYaw/bodyYaw/pitch.insert(...)`，`yaw = -y`（`:236-243`）；
- `setS` 空实现（记录没有缩放，`:217`）；`MASK` 只放行 `MOVE/SCREEN/ROTATE` + `X/Y`（`:52-55`）。
- 撤销：构造里已接 `callbacks(pre, post, end)` → `replay.keyframes.preNotify/postNotify`（`:74-78`）。

**骨骼/姿势变换（P3 再做）**

```java
UIPropTransform t = UIReplaysEditorUtils.getEditableTransform(keyframeEditor);  // 静态，只要编辑器
```
它返回 keyframe editor 当前选中关键帧的 `UIPropTransform`（姿势编辑器里那个）。
拖动仍用 `Gizmo.INSTANCE.start(...)`，但 `GizmoDrag` 需要骨骼的世界轴 → 原版用
`UIReplaysEditorUtils.buildFilmGizmoDrag(panel, camera, panel.preview.getViewport(), transform, transition)`，
它依赖 `panel.getEditTarget()/panel.preview.getViewport()/panel.getCamera()` **不可直接用**。
bbsae 侧需要自建：`GizmoDrag.fromRenderedGizmo(camera, area)` + `.setRotateAxes(...)` +
`.setJacobian(GizmoDrag.computeTranslateJacobian(transform.getTransform(), sampler))`。
→ **骨骼 gizmo 是 P3 的独立子任务**，不要塞进 P1。

### 5.5 拾取（stencil）——「点模型选中它」

照抄 `FilmStencilPicker.renderPreview`（`ui/film/controller/FilmStencilPicker.java`）的序列：

```java
Gizmo.INSTANCE.setViewportHeight(viewport.h);                 // :296 与视觉 gizmo 同尺寸
stencil.setup(Link.of("bbsae:replay_pick"));                 // :106，同一 Link 复用同一 FBO
stencilMap.setup(); stencil.apply();                         // :301-302
stencilMap.setIncrement(true);                               // 每个模型一个 id
for (Replay r : film.replays.getList()) {
    FilmEntityRenderer.renderEntity(FilmControllerContext.instance
        .setup(controller.entities, entity, r, camera, stack, consumers, transition)
        .stencil(stencilMap)                                 // ← 走拾取分支（画 id）
        .relative(r.relative.get())
        .gizmoTarget(FilmTarget.root(space))                 // 选中那个：gizmo 句柄也进 id
        .gizmoView(camera.view));
}
int x = round((mouseX - area.x) / (float) area.w * tex.width);
int y = round((1F - (mouseY - area.y) / (float) area.h) * tex.height);
stencil.pick(x, y, radius, Gizmo.STENCIL_MAX);               // :385 句柄带容差
stencil.unbind(stencilMap);                                  // :386
```

要点：
- id 空间：`FilmStencilPicker` 里 `REPLAY_STENCIL_OFFSET = Gizmo.STENCIL_MAX + 1`（`:47`），
  gizmo 句柄占 `[STENCIL_X, STENCIL_MAX]`；bbsae 沿用同样的偏移即可复用
  `Gizmo.STENCIL_MAX` 的容差拾取。
- `pick` 是 `glReadPixels`，**必须在 stencil FBO 仍绑定时调用**，之后才能 `unbind` 并恢复主 framebuffer。
- `pair = stencil.getPicked()` 返回 `(Form, bonePath)`；用 `FormUtils.getRoot(pair.a)` 与
  `replay.form.get()` 比对即可反查 replay（`FilmStencilPicker.java:144-158` 同款）。

---

## 6. 模型选择库如何从自定义 UI 打开

**入口（jar 已核对）**

```java
public static UIFormPalette open(UIElement parent, boolean editing, Form form, Consumer<Form> callback);          // :28
public static UIFormPalette open(UIElement parent, boolean editing, Form form, boolean ignore, Consumer<Form> cb); // :33
```
- `:35` `UIContext context = parent.getContext();` → **任何在 UI 树里的 `UIElement` 都能开**；
  `:39` 若 root 里已有 `UIFormPalette` 或 context 为 null 则返回 `null`（不可重入）。
- `:49-52` `palette.resetFlex().full(parent); parent.add(palette);` → **它不是 overlay，而是铺满 parent 的子元素**；
  关闭靠 `exit()`（`:122`）→ `removeFromParent()`；Esc 走 `subKeyPressed`（`:214`）。
- 内容来自全局单例：`UIFormList` 构造函数 `:137` `setupForms(BBSModClient.getFormCategories())`
  → 分类/搜索/缩略图/拖拽/复制粘贴全是原版，**不需要影片上下文**。
- 里面还有 `UIFormEditor`（`:68`、`toggleEditor：159`）：双击分类里的模型 → 进入 BBS 原版
  模型编辑器（骨骼/材质/IK/物理/动画状态）。这就是「编辑动作」的另一半。
- `callback` 在 `accept(Form)`（`:199-205`）里触发；`editing=true` 时会先进入编辑器，
  编辑器 `finish()` 之后才 `accept`。

**在 bbsae 里的用法**

```java
UIFormPalette palette = UIFormPalette.open(this, false, replay.form.get(), form -> {
    replay.form.set(FormUtils.copy(form));      // 原版也是 copy（UIReplayList.openFormEditor:1606 内部同款）
    filmPanel.getController().createEntities(); // 让 controller 重建实体
    this.markDirty();
});
if (palette == null) { /* 已经开着一个，忽略或先关 */ }
```

配套控件：`UINestedEdit(Consumer<Boolean> onEdit)` + `setForm(Form)`（`ui/forms/UINestedEdit.java:17/39`），
就是原版属性面板里那颗「模型 / 编辑」按钮组；`UIReplayPropertiesPanel` 里用的是
`this.list.openFormEditor(r.form, editing, this.pickEdit::setForm)`（`UIReplayPropertiesPanel.java:86-101`，
`UIReplayList.openFormEditor：1606`）。

**风险**：`UIFormPalette` 是 full-parent 的，会盖住 bbsae 面板全部内容（包括 3D 视口）；
它自身的 `UIFormEditor` 还会开更多 overlay（`UIColorPicker`、`UIBonePicker` 等），
所以必须保证「一次只开一个 + Esc 能退 + 关闭后清引用」，否则会出现面板卡死。

---

## 7. 分阶段实施计划（每阶段可独立验证）

> 总体约束：**不改 `UICompositorPanel.java` 超过 ~20 行**。所有新逻辑进
> `dev.bbsae.client.replay`（非 UI）与 `dev.bbsae.client.ui.replay`（UI）两个新包。
> 每个阶段结束都能 `.\gradlew.bat test --offline` 通过 + 可单独开关。

### P0 · 地基：comp↔world 映射 + 只读 3D 模型显示（约 1.5 天）

**新增**
| 文件 | 内容 |
| --- | --- |
| `src/main/java/dev/bbsae/client/replay/ReplaySceneSpace.java` | 映射（§5.2）+ `cameraFor(CompViewportCamera)`；纯 joml，可单测 |
| `src/main/java/dev/bbsae/client/replay/ReplayActorSync.java` | 取 controller（照抄 `ReplayEntityMask.resolveController` 逻辑，或把那个私有方法改成 `public static BaseFilmController controllerFor(String filmId)`）、取 `IEntity`、按 tick `apply` |
| `src/main/java/dev/bbsae/client/replay/ReplayModelPass.java` | §5.3 的 C–G 段；输出到自己的 `GlFramebuffer` |
| `src/test/java/dev/bbsae/client/replay/ReplaySceneSpaceTest.java` | 往返、1.8 格≈115px、`view*compFromWorld` 与手工投影一致 |

**改动**
| 文件 | 插入点（可搜索锚点） | 改动量 |
| --- | --- | --- |
| `client/ui/UICompositorPanel.java` | `private void renderBackground(UIContext context)`（grep 行 830）里 `CompViewportRenderer.draw(...)` 之后 | +6 行：`if (ReplayModePrefs.showModels) ReplayModelPass.draw(...)` |
| `client/ui/CompPrefs.java` | 新增 `public boolean replayModels; public float pixelsPerBlock = 64F;` | +3 行 |

**验证**：进入 3D 空间 → 看到影片里所有 replay 的模型站在各自位置；开关关闭后完全无副作用。
**回滚**：删 2 个字段 + 1 个 `if`。

### P1 · 选中 + 原版 gizmo 拖根变换（约 2 天）

**新增**
| 文件 | 内容 |
| --- | --- |
| `client/replay/ReplayGizmoHost.java` | `implements GizmoViewport`（5 方法）+ 持有 `GizmoInteraction` + `StencilFormFramebuffer`；`startGizmo` 用 `GizmoDrag.fromRenderedGizmo(camera, area)` |
| `client/replay/ReplayEditSession.java` | 选中 replay id、playhead tick、`UIReplayPropTransform`、写回与 `preNotify/postNotify` |
| `client/replay/ReplayPickPass.java` | §5.5 的 stencil pass |
| `src/test/java/.../ReplayGizmoDragTest.java` | `GizmoDrag.fromRenderedGizmo` 的屏幕位移→世界位移（纯数学，无 GL） |

**改动**：`UICompositorPanel` 里 3D 视口的鼠标路径（`viewportMouseClicked(UIContext)`，grep 行 4534）
前插一段 `if (replayMode) { host.mouseClicked(context); return true; }`（+8 行）；
`subKeyPressed(UIContext)`（内层 :4503 / 外层 :17644）里加 Ctrl+Z/Ctrl+Y → `filmPanel.undo()/redo()`（+6 行）。

**验证**：点模型 → 出现 BBS 原版三轴 gizmo（视觉与原版一致，因为就是同一份 `Gizmo`）；
拖 Y 轴 → `replay.keyframes.y` 在播放头插入关键帧；BBS 原版面板切回去能看到同样的关键帧；
Ctrl+Z 能撤销（走 `UIFilmUndoHandler`）。

### P2 · 原版轨道（时间轴）与面板切换按钮（约 3 天）

**新增**
| 文件 | 内容 |
| --- | --- |
| `client/ui/replay/UIReplayModePanel.java` | `extends UIDashboardPanel`；布局 = 3D 视口 / 轨道 / 检查器；`bind(UIFilmPanel, Film, Replay)` |
| `client/ui/replay/UIReplayTrackPanel.java` | 包住 `UIKeyframeEditor` + `UIFilmKeyframes`（§4.2 1–2 步）+ 分类按钮（`TrackCategories`） |
| `client/ui/replay/ReplaySheetBuilder.java` | 抄 `UIReplaysEditor.updateChannelsList`（`:543-795`）的 sheet 装配与过滤，去掉 `panel.*` |
| `client/ui/replay/ReplayClipDelegate.java` | 可选：自写 `IUIClipsDelegate`，就不必借 `filmPanel.cameraEditor` |
| `src/test/java/.../ReplaySheetBuilderTest.java` | 分类归并、`disabledSheets` 过滤、空轨道保留规则 |

**改动**
| 文件 | 插入点 | 改动量 |
| --- | --- | --- |
| `client/BbsAeClient.java` | `onDashboardPanels(RegisterDashboardPanelsEvent event)`（grep 行 605）里追加 `event.dashboard.getPanels().registerPanel(replayModePanel, IKey.constant("实体回放"), Icons.PLAYER)`；新增 `public static void openReplayMode(...)`（照 `openCompositorForFilm` :512 的写法） | +12 行 |
| `client/ui/UICompositorPanel.java` | `this.space3DButton = new UIIcon(Icons.SPHERE, ...)`（grep 行 4971）旁边加一个 `UIIcon(Icons.PLAYER)`；`this.preview.add(...)`（grep 行 4984） | +5 行 |

**验证**：主界面点「实体回放」→ 切到新面板（`dashboard.setPanel`）；
轨道是 BBS dope sheet（菱形、插值菜单、复制粘贴、轨道过滤、折叠）；切回「合成器」不丢状态；
`getPanel(UIFilmPanel.class)` 仍是同一个实例（打日志断言）。

### P3 · 属性检查器 + 模型库 + 姿势/骨骼 gizmo（约 3 天）

**新增**：`UIReplayInspector`（`UIReplayPropTransform` 数字栏 + enabled/looping/name +
`UINestedEdit` 模型槽 + 「选择模型…」按钮）、`UIReplayModelPicker`（包 `UIFormPalette`）、
`ReplayBoneGizmoTarget`（`GizmoDrag` 自建 + `UIReplaysEditorUtils.getEditableTransform`）。

**验证**：换模型后 3D 视口立即换；打开模型编辑器 → 改骨骼/材质 → 关闭回写；
点骨骼 → 出现骨骼 gizmo → 拖动写 pose 关键帧；`UIKeyframeEditor` 属性栏显示的是 BBS 原版
`UIPoseTransformKeyframeFactory` 字段（fix/可见性/颜色/overlay/lighting/transform）。

### P4 · 原版回放列表 + 动作轨道（约 2 天）

复用 `UIReplayList(Consumer<List<Replay>>, Consumer<Form>, UIFilmPanel)`（`:101`）与
`UIReplaysListPanel(UIFilmPanel, …)`（`:44`），用 bbsae 持有的 filmPanel 构造；
动作轨道改用**原版 `UIClipsPanel` 的替代**：只读展示 replay.actions（点击跳转回原版面板编辑），
或者干脆在 P4 提供「回到原版面板编辑动作」的深链按钮（`BbsAeClient` 的路线 A）。
**验证**：列表增删/复制/分类/搜索/拖拽排序全部可用；删除后 3D 视口同步消失。

### P5 · AE 式「模型导入」（约 2 天）

**目标语义**（不是 OBJ 导入）：从 BBS 模型库把一个模型作为**新图层/新 replay**导入当前合成视角，
并自动落在视口中心、自动打一个位置关键帧——这正是 AE「把素材拖进合成」的体验。

**新增**：`ReplayModelImport`（`BBSMod.getProvider().getLinksFromPath(Link.of("bbs:models/"), true)`
筛 `.bbs` → 构造 `ModelForm` + `model.set(link)` → 新建 `Replay`（`film.replays.addReplay()`）→
`keyframes.x/y/z.insert(tick, compToWorld(viewportCenter))`）、`UIModelCatalogOverlay`（可选自绘网格）。

**验证**：一键导入 → 模型出现在 3D 视口中心 → 立刻可拖 gizmo → 保存影片后重开仍在。

### 里程碑与顺序

```
P0 ──> P1 ──> P2 ──> P3 ──> P4
        └────────────> P5（P1 之后即可并行，因为只依赖「新建 replay + 写关键帧」）
```

---

## 8. 测试计划

**单元测试（无 GL，必须过 `gradlew test`）**

| 测试 | 断言 |
| --- | --- |
| `ReplaySceneSpaceTest` | `worldFromComp(compFromWorld(p)) == p`；`(0,1.8,0)→(0,-115.2,0)`（S=64）；相机投影与手工 `projection*view*compFromWorld*worldPoint` 一致 |
| `ReplayGizmoDragTest` | `GizmoDrag.fromRenderedGizmo` 后，屏幕 +X 拖动只改世界 X；`computeTranslateJacobian` 在旋转 90° 时把 X 映射到 Z |
| `ReplaySheetBuilderTest` | `TrackCatalog.forPart` 生成的 descriptor 全部变成 sheet；`disabledSheets` 命中即剔除；分类过滤后空轨道仍保留 `filteredOutEverything` 分支 |
| `ReplayFormApplyTest` | `FormUtils.copy(form)` 后 `replay.form.set(...)` 的路径/属性不变（纯数据，不碰渲染器） |
| `ReplayTransformWriteTest` | 模拟 `setT/setR` 后 `replay.keyframes.x/y/z` 与 `yaw/headYaw/bodyYaw/pitch` 在 tick 上各有一个关键帧，且 `yaw = -y` |

**无头 GL 测试（照 `Viewport3DRendererTest` 的 `GLFW offscreen + Assumptions` 模式）**

- `ReplayModelPassGlTest`：建 1×1 的 `GlFramebuffer`，跑一次 pass，读回中心像素 alpha > 0
  （证明 `FilmEntityRenderer` 在 bbsae 的 FBO/矩阵下真的画出了东西）。
  **注意**：`FormUtilsClient`/模型加载依赖 BBS 客户端初始化，此测试必须
  `Assumptions.assumeTrue(BBS 初始化完成)`，不可作为 CI 硬门槛（与现有 `ShaderCompileTest` 同策略）。

**真机验证步骤（交给 Lead，不要自己开客户端）**

1. 打开影片 → 打开合成器 → 3D 空间 → 应看到所有 replay 模型（P0）。
2. 点模型 → gizmo 出现；拖 Y → 模型跟手、轨道出现 y 关键帧；`Ctrl+Z` 回退（P1）。
3. 点「实体回放」→ 面板切换；轨道是原版 dope sheet；拖菱形改插值；
   右键轨道 → 过滤/动画转姿势/烘焙 IK 菜单在（P2）。
4. 换模型 → 3D 立即变；打开模型编辑器 → 改骨骼 → 关闭 → 3D 变（P3）。
5. 与 BBS 原版对照：**同一帧、同一机位**下截图，模型位置/朝向一致（R4 标定）。
6. 压力：200 replay 的影片 → 3D 视口帧率 ≮ 30（P0 的 pass 有 per-frame `replay.keyframes.apply`，
   若掉帧需加「只同步选中 + 视野内」的裁剪）。

---

## 9. 不可行 / 高风险清单

| # | 项 | 结论 | 依据 / 缓解 |
| --- | --- | --- | --- |
| R1 | 新建第二个 `UIFilmPanel` | **不可行** | `applyPreviewSizeToBBS`→`BBSRendering.setCustomSize`（`:317/:834`）；`UIFilmPreview.getViewport` 读 `BBSRendering.getVideoWidth/Height`（`:354`）；第二个 `UIFilmController` 抢世界渲染 |
| R2 | 两份 `UIReplaysEditor` | **高风险，不推荐** | 抢 `actionEditor`（`:516`）、固定 undo id（`:641`）、`showPanel(this)`（`:1095`）、`clickViewport` 用原版 stencil（`:1074`） |
| R3 | `Gizmo` 单例 | **必须单宿主** | `Gizmo.INSTANCE` 每帧只保留最后一次 `captureVisual`（`Gizmo.java:667`）；两处同帧绘制会互相覆盖位置/拾取。缓解：bbsae 模式激活时，原版 `FilmEntityRenderer` 不再 `gizmoTarget`（由 `UIBaseMenu.shouldRenderAxes` + 我们自己的 flag 控制） |
| R4 | comp↔world 手性/朝向 | **中风险，必须标定** | Y 必须翻转；Z 符号决定行列式正负（镜像会破坏背面剔除）。缓解：`rotateX(180°)` 式映射 + `flipZ`/`yawOffset` 常量 + 与原版同机位截图对比 |
| R5 | GL 状态泄漏 | **中风险，已有范式** | 必须 `FormRenderLast.suspend/restore`、`BufferRenderer.resetCurrentVertexBuffer()`、还原 projection/modelView/viewport/framebuffer（见 `ReplayEntityMask.drawInto` 的 `finally`，`:796-822`） |
| R6 | 撤销归属 | **必须走原版栈** | `Film.preCallback` 单槽被 `UIFilmUndoHandler` 占用（`UIFilmPanel:1451-1453`）；**禁止** `film.preCallback(...)`；Ctrl+Z 调 `filmPanel.undo()/redo()`（public） |
| R7 | 模型库面板生命周期 | **中风险** | `UIFormPalette` 是 full-parent 子元素且不可重入（`:39`）；其内部 `UIFormEditor` 会再开 overlay；必须保证唯一实例 + Esc 退出 + 关闭清引用 |
| R8 | 外部模型文件导入（OBJ/FBX） | **不可行（本版）** | BBS 无 3D 模型 importer API：`RegisterImportersEvent` 只对接 `importers/`（`PNGImporter`/`WAVImporter`/`GIFImporter`/`OldSkinImporter`/`ToPNGImporter`/`ToWAVImporter`）。模型只认 `.bbs`/`cubic` 体系 |
| R9 | `StencilFormFramebuffer` 读回 | **低风险** | `pick` 是 `glReadPixels`，必须在目标 FBO 仍绑定时调用；y 轴要翻（`(1 - t) * h`，`FilmStencilPicker:381-382`）；`radius` 用的是 framebuffer 像素 |
| R10 | 无头单测覆盖渲染 | **限制** | `FormUtilsClient`/模型加载要 BBS 客户端初始化；纯逻辑（映射/gizmo 数学/sheet 装配）可测，渲染只能靠无头 GL + `Assumptions` 或真机 |
| R11 | 2.7-1.20.1 API 缺口 | **无阻塞（已验证）** | 本文引用的每个类/方法都在 jar 里（`javap` 留档）；真正缺的是「addon 无法新增 `TrackKind`」「`UIReplaysEditor` 无独立构造」——这两条绕开即可，**不需要 mixin/反射** |
| R12 | 需要 mixin 才能做的部分 | **本版不需要** | 若将来要「原版面板内嵌 bbsae 3D 视口」（路线 C），需要 `RegisterPreviewOverlaysEvent`（已有 API，无需 mixin）；只有当我们要改 `UIFilmPanel` 内部布局时才需要 mixin —— 明确列为不做 |
| R13 | 动作（actions）轨道的完整复用 | **降级** | `UIClipsPanel` 需要 `UIFilmPanel` 且只有一个父级；P4 用「深链回原版面板」代替，或只读展示 |

---

## 10. 复用 BBS 类清单（给实现同学的直接清单）

```
数据/渲染
  mchorse.bbs_mod.film.Film, film.replays.Replay, ReplayKeyframes, FormProperties, Replays
  mchorse.bbs_mod.film.BaseFilmController, FilmEntityRenderer, FilmControllerContext, FilmTarget, FilmMatrices
  mchorse.bbs_mod.camera.Camera
  mchorse.bbs_mod.forms.forms.Form, ModelForm, IPosedForm; forms.FormUtils, FormUtilsClient, FormCategories
  mchorse.bbs_mod.forms.renderers.ModelFormRenderer#getModel(ModelForm)
  mchorse.bbs_mod.cubic.ModelInstance（model / animations / materialTextures / materials / config）
UI（原版轨道/属性/库）
  ui.framework.elements.input.keyframes.UIKeyframeEditor, UIKeyframes, UIKeyframeSheet
  ui.film.utils.keyframes.UIFilmKeyframes            （或自写 UIKeyframes 子类）
  ui.framework.elements.input.UIPropTransform; ui.utils.pose.UIPoseEditor
  ui.film.replays.UIReplayPropTransform, UIReplaysEditorUtils（静态）, UIReplaysEditor.renderRuler（静态）
  film.replays.tracks.TrackCatalog, TrackDescriptor, TrackId
  api.client.editor.TrackCategory/TrackCategories
  ui.forms.UIFormPalette, UIFormList, UINestedEdit
  ui.film.replays.UIReplayList, UIReplaysListPanel, UIReplayPropertiesPanel（需真实 filmPanel）
Gizmo/拾取
  ui.utils.Gizmo（INSTANCE）, GizmoDrag, GizmoInteraction, GizmoViewport, StencilFormFramebuffer
  ui.framework.elements.utils.StencilMap
宿主/生命周期
  ui.dashboard.UIDashboard, dashboard.panels.UIDashboardPanel, UIDashboardPanels
  ui.dashboard.panels.UIDataDashboardPanel
  ui.film.UIFilmPanel（只读借用：getController/getRunner/getKeyboardCursor/getData/forceSave/undo/redo）
  api.client.events.RegisterDashboardPanelsEvent, RegisterPreviewOverlaysEvent, RegisterFilmToolsEvent,
                    FilmGizmoEvents, TimelineEvents
```

---

## 11. 附：javap 核对命令

```powershell
$root = "C:\Users\Administrator\Desktop\bbsae1.0 - 副本"
$jdk  = "$root\_tools\jdk21\jdk21.0.12_12\bin"
$jar  = "$root\libs\bbs-2.7-1.20.1.jar"
& "$jdk\javap.exe" -p -classpath $jar mchorse.bbs_mod.ui.film.replays.UIReplaysEditor
# 批量核对（本次已跑）：逐个类输出到 _research/team/javap/<SimpleName>.txt
```

核对记录：`_research/team/javap/` 下 50 个 `.txt`，每个都是 `javap -p` 的完整输出。

---

## 12. 术语

- **comp 空间**：bbsae 合成坐标系，像素、Y 向下、Z 朝观察者（`CompScene.java:22-24`）。
- **world 空间**：Minecraft 世界，方块、Y 向上。
- **replay / 回放**：BBS 影片里的一个演员记录（Form + 关键帧通道 + 动作 clips）。
- **form / 模型**：BBS 的 Form 体系（`ModelForm`=`.bbs` 模型、`MobForm`=原版生物、`ItemForm`…）。
- **gizmo**：BBS 的三轴变换手柄（移动/缩放/旋转），单例 `Gizmo.INSTANCE`。
- **stencil / 拾取缓冲**：BBS 把每个可点对象画成 RGBA id 到独立 FBO，鼠标处 `glReadPixels` 反查对象。
