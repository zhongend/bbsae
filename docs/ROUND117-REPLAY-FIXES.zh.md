# Round 117 — BBS 原版播放 / 回放合成器 / K 帧 / 布局修复

> 版本 `0.99-hotfix84`（只加后缀，不进入 1.0）。
> 全量：**193 套件 / 1193 项 / 0 failures / 0 errors / 1 skipped**。
> 真机：一次 BBS 原版播放审计 + 一次回放页交互审计（日志与截图见第 9 节）。

本轮按用户 8 条反馈做。下面每条都先写**第一性根因**，再写改动与证据；没有把“看起来好了”当验证。

---

## 1. 合成器在 BBS 原版播放一段时间后“冻结”

### 根因（三层，缺一不可）

1. **合成时长变了，图层窗口没变。** BBS 的 `CompositionClip` 在影片时间轴上通常比合成长。
   `adoptCompositionDuration` / 设置页 / 导出时长弹窗只写 `doc.duration`，没有把
   “原来正好铺满旧画布的图层 out 点”延伸到新画布。12 秒的合成改成 40/60 秒后，所有图层
   仍在 12 秒结束，之后每帧只剩合成背景色 —— 视觉上就是画面被冻住。
2. **“保持末帧”没有保持末帧。** 叠加层只判断 `if (!hold && time > duration) continue`；
   `hold=true` 时把**超过结尾的时间**继续交给渲染器。`CompLayer.activeAt` 是
   `time >= in && time < out`，超过 `duration` 后所有图层都 inactive，画面变成空的舞台背景。
3. **外部 BBS 回放的世界帧不更新。** `BbsPreviewBridge.captureFrame` 只在
   `customSize` 或录像时复制世界帧；外部播放（非 customSize）里带 `BBS 预览`/抠像层的合成
   永远拿不到新的世界帧，那一层会停在第一帧。

### 修复

- `CompDocument.resizeDuration(seconds)`：**显式改时长**时，把 `out ≈ 旧 duration` 的图层
  延伸到新 duration；故意裁短的图层保持不动；缩短不裁剪图层。`adoptCompositionDuration`、
  设置时长按钮、导出时长字段、合成器时长字段全部改走它。
- `CompositionClip.overlayTime(doc, raw, hold)`：
  - 未超过结尾 → 原时间；
  - 超过结尾且 `hold` → 返回 `duration - 1/fps`（最后一个真实帧，`duration` 是排他结尾）；
  - 超过结尾且不 hold → `NaN`，叠加层跳过该实例，让 BBS 世界显示。
  叠加层用 `instance.filmTickAt(time)` 反算“这一帧对应的影片 tick”，联动回放动画不会跳到
  播放头之后的帧。
- `BbsPreviewBridge.captureFrame`：`worldCaptureRequested`（叠加层确实看到 BBS/实体层时才置位）
  在外部播放也触发复制；不再依赖 `customSize`/录像器。

### 证据

真机 `BbsPlaybackAudit`（合成 3 秒、clip 400 ticks、BBS 原版影片编辑器播放）日志：

```text
[export trace] hold comp=replay_c0f6ce8b rawTime=3.086 renderTime=2.95 activeLayers=15/27
[export trace] hold comp=replay_c0f6ce8b rawTime=4.163 renderTime=2.95 activeLayers=15/27
...
[export trace] hold comp=replay_c0f6ce8b rawTime=6.844 renderTime=2.95 activeLayers=15/27
```

`rawTime` 持续增长、`renderTime` 停在 2.95、15 个图层仍 active；截图 2 秒/8 秒
（`docs/verify/round117-bbs-play-2s.png`、`...-8s.png`）里合成人物仍在画面中，不是空背景。

---

## 2. bbsae 回放在 BBS 原版里动画不正常

### 根因

1. `ReplayActorPass.renderLinkedModel` 只从 `BbsPreviewBridge.film()` 取影片。合成叠加层
   （BBS 原版影片编辑器 / F6）里合成器面板并不是当前页面，bridge 没有 attach，`film()==null`，
   于是 linked replay 退回 `layer.modelForm()` 的静态 form —— 有模型但不走回放动画。
2. 虚拟回放在 BBS 原版里是 `enabled=false`，BBS 不会给它创建实体。旧代码每次渲染都
   `new StubEntity`：`age=0`、没有 `form.update`、没有 prev/current，模型永远停在第一帧。
3. 离开回放页后控制器里可能还留着虚拟回放的旧实体；旧代码优先用它，于是定格在离开时的姿态。

### 修复

- `ReplayActorPass.filmEntities(film)`：`BbsPreviewBridge.entities()` 为空时回退到
  `BBSModClient.getFilms().getController(filmId)` 的实体表；`renderLinkedModel` 优先使用
  `CompRenderContext.film`（叠加层已经通过 `BindingResolver.setFilm` 设置）。
- 私有 stand-in actor 缓存（film+replay 为 key）：跨帧保留，`advanceStub` 每个影片 tick 调
  `entity.update()` / `form.update(entity)` 推 age、limb、CEM/模型动画，再应用该 tick 的
  回放关键帧；后退/大跳重置 age；`copyPrev` 避免从 0 插值。
- 虚拟回放在 bridge 不 active（BBS 原版/导出）时**强制使用 stand-in**，忽略控制器里可能残留
  的旧实体。
- 3D/flat 实体层渲染循环改用 pose 阶段实际写入的 map（此前 stub 只写局部变量，渲染循环查
  原 map，整层被跳过）。

### 证据

同一 BBS 原版播放审计的日志出现 `Replay stub: replay=... tick=0 age=1 form=...`；2 秒与
8 秒截图（`round117-bbs-play-2s.png` / `round117-bbs-play-8s.png`）中人物手臂姿态不同
（8 秒是 hold 的 2.95 秒末帧），证明不是定格在首帧。合成预览/导出与回放编辑器共用同一
`ReplayActorPass`，所以 2D/3D 预览、影片叠加和 F6 走同一条动画路径。

---

## 3. 回放编辑器 Transform 变换无法移动回放

### 根因

`ReplayEditSession` 自己 `new UIReplayPropTransform()`，它没有挂到任何 UI 树。BBS 的
`UIPropTransform.getContext()` 通过 `getRoot()` 取 context，脱离 UI 树后始终为 `null`；
`TransformGesture.startEdit()` 在 context 为空时直接 return。但 `Gizmo.start()` 仍返回
`true`，于是页面显示“正在拖 gizmo”，实际每个鼠标移动都是空操作。

### 修复

- 新增 `ReplaySessionTransform`（继承 `UIReplayPropTransform`）：覆写 `getContext()` 使用页面
  context，保持 `isVisible()==false`，让 `GizmoInteraction` 每帧 `pumpIfHidden` 驱动手势。
- `ReplayEditSession.setContextSupplier` 由 `UIReplayModePanel` 注入 `this::getContext`。
- 新增 `UIReplayViewport.debugDragGizmo`：走真实 `Gizmo.start → pump → accept` 路径，专门防止
  “setT 能写、鼠标拖不动”的回归。

### 证据

真机日志：

```text
Replay gizmo script: before=[tick=30.00 x=0.00 y=0.00 z=0.00 ...]
Replay gizmo script: drag started=true after=[tick=30.00 x=1.50 ... pos=(1.50,0.00,0.00)]
Replay gizmo script: RESULT gizmoStarted=true channelsMoved=true ...
```

`started=true` 且 x 真的写入 1.50；截图 `round117-gizmo-before.png` / `-layout.png` 可见页面与
gizmo。

---

## 4. K 帧体验：Alt 选部位 / 绕对象 / gizmo 同步

完整源码对比见 [`ROUND117-KFRAME-DIFF.zh.md`](ROUND117-KFRAME-DIFF.zh.md)。要点：

- `ReplayPickPass` 选中演员最后画、`stencilMap.setIncrement(true)`，每根 form part/bone 独立 id；
  其他演员保持单 id。`pickedForm()` 暴露 `(form, bone)`。
- 左键顺序修正：其他演员 id → 切换演员；选中演员的部位 id → `UIReplayModePanel.selectPartFromViewport`
  → `UIReplaysListPanel.setBodyPartsReplay`（原版部位列表）→ 重建轨道 → 原版
  `UIReplaysEditorUtils.pickForm`（选中该 bone 的键/pose 编辑器）。
- `ReplayCursor` 把原版 pick 的 `setCursor` 映射到 session 播放头。
- 选中 bone 后，gizmo 目标切到关键帧编辑器的 `UIPropTransform`；`boneGizmoDrag` 使用
  `FilmMatrices.getGizmoBoneCompositeMatrix` 的 local/parent 两套帧；`ReplayPickPass` 的 gizmo
  stencil 也画在同一骨骼矩阵上。
- `selectedPartWorld()` 用同一骨骼矩阵给 orbit 提供 pivot；`followSelectedActor` 在 ORBIT 模式
  使用它。
- 部位选中后第一次 I/K 走 `pickForm(..., bone, true)`：插入该骨骼的 pose 键并打开 pose gizmo，
  之后 gizmo 自动落在骨骼上（真机日志 `editorBone='anchor' boneGizmo=true boneTransform=true`）。

---

## 5. 回放编辑器页面无法调整大小

### 根因

回放页内部是三列固定 190/252 px + 比例时间轴，**没有任何 splitter**。compositor 的“布局锁/解锁”
只管理 `UIDockLayout` 的 dock panel，回放页不是 dock panel，所以解锁布局当然改不了它。

### 修复

- 页面新增 `listSplitter` / `propsSplitter`（左右列）与 `timelineSplitter`（时间轴高度），
  使用 BBS `UIDraggable`；拖动实时改列宽/时间轴高，并在拖动结束写
  `CompPrefs.replayListWidth/replayPropsWidth/replayTimelineHeight`。
- `layoutReplayColumns()` 负责列几何并在窗口过窄时优先收缩较宽列，保证预览最小 220 px；
  `applyReplayTimelineLayout()` 统一主区/关键帧区/splitter 高度。
- splitter 的拖拽条画成可见蓝线，解锁/锁定 dock 都不影响本页（本页不是 dock）。

### 证据

真机日志（生产 layout 方法实际输出）：

```text
list=260x236@0 viewport=388x236@266 props=300x236@660
listSplit=6x236 propsSplit=6x236 timelineSplit=940x6@276 main=960x236 timelineH=220
```

截图 `docs/verify/round117-gizmo-layout.png` 可见两条竖 splitter 与时间轴上方的横 splitter。

---

## 7. 回放抠出图层的显示错误

### 根因

hotfix83 的完整抠像用“世界帧颜色 × **未遮挡** coverage”。coverage 是选中演员所有可见面的
并集，它不知道世界深度：演员站在墙/地面后时，被墙挡住的像素也在 alpha 里，世界帧在这些像素
上正是墙/地面颜色 → 抠像结果出现“墙形状的人物”/背景被抠进人物。另外热点 83 的 world 捕获
请求只扫描顶层文档，precomp 里的回放实体层不请求，抠像整层为空。

### 修复

- `ReplayEntityKey.drawCutoutOccluded`：同一 shader，使用 world-depth 测试过的 occluded coverage
  裁世界帧；自遮挡/半透明仍由世界帧本身保证，不需要写自深度。
- `LayerContentRenderer`：`layer.entityOcclusion` 为真时先走 occluded cutout，再退回 unoccluded；
  无世界帧时仍走 actor-only。
- `BbsPreviewBridge.needsWorldFrame/needsDepth/hasReplayEntityLayer` 全部递归 precomp；
  `CompositionOverlay` 的深度/蒙版请求改用递归判断。

### 证据/边界

- 真机 BBS 原版播放审计没有抠像异常日志，合成层正常；`CutoutClarityContractsTest` 增加
  “occluded cutout 必须在 unoccluded 之前”的契约。
- **剩余边界（如实说明）**：`ReplayEntityMask` 目前按“整个合成请求的实体并集”生成一张覆盖
  图；同一合成里两个不同 `entityIds` 的抠像层会共用这张并集，互相包含对方演员。要彻底消除需要
  按图层 scope 生成多张覆盖目标（下一轮），本轮先修掉“墙/背景被抠进人物”的主显示错误。

---

## 8. 回放与预合成 / 外部播放的交叉审计

本轮额外发现并修掉：

1. **precomp 内的 world/entity 层不触发世界帧/深度请求**（见 §7 递归修复）。
2. **precomp 内 linked replay 使用影片绝对 tick**：嵌套合成有自己的 offset/time scale，
   用顶层影片 tick 会让动画帧错误。新增 `CompRenderContext.nested` + `pushNested/popNested`，
   `ReplayActorPass.replayTick(context)` 在嵌套时用 `context.time*20`（与合成器预览一致），
   顶层仍用绝对 film tick（split/时间伸缩正确）。
3. **外部 BBS 回放（非 customSize）不复制世界帧**（见 §1 第 3 层），BBS 预览/抠像层不再
   永远停在第一帧。
4. **多个合成 clip / split clip**：叠加层逐个实例设置 `BindingResolver.frameTick`；hold 时用
   `filmTickAt` 反算；实体掩码按每个世界帧 serial 校验，不会拿上一帧的轮廓。
5. **虚拟回放实体残留**：离开回放页后使用 stand-in，不读取旧控制器实体（见 §2）。
6. **实体掩码的 precomp 请求深度限制**与渲染递归上限一致（8 层），`hasEntityLayer` 测试已覆盖。

---

## 9. 验证汇总

### 自动化

```text
193 suites / 1193 tests / 0 failures / 0 errors / 1 skipped
```

新增/更新测试：

- `CompModelTest`：`resizeDuration` 延伸/不裁短、hold 末帧、`filmTickAt` 反算。
- `ReplayEntityMaskRequestTest`：precomp 内 world/entity 递归。
- `CutoutClarityContractsTest`：occluded cutout 优先。
- `ReplayInteractionContractsTest`：gizmo context、stand-in 持久化、part pick/骨骼 gizmo、
  回放页 splitter。

### 真机（同一工作副本）

- 回放页：`gradlew runClientView -PbbsaeView=replay -PbbsaeReplayGizmoScript=true ...`
  → `gizmoStarted=true channelsMoved=true partSelected=true layoutSplit=true`，
  骨骼首次 K 帧后 `editorBone='anchor' boneGizmo=true boneTransform=true`。
- BBS 原版播放：`-PbbsaeBbsPlaybackAudit=true` → hold 日志 `rawTime>3 / renderTime=2.95 /
  activeLayers=15/27`；2 秒/8 秒截图人物姿态变化。
- 截图：`docs/verify/round117-gizmo-*.png`、`round117-bbs-play-*.png`。
- 产物：`bbsae-0.99-hotfix84.jar`（4,100,143 bytes，SHA-256
  `8154535CA6D61E4D6F0472FCBE182FD926DEF4AD4F5D1BD0D800129F44B8ED86`，`remapJar` 构建）。

### 尚未覆盖 / 下一轮

- 抠像多图层独立 scope（§7 边界）。
- pose 编辑器的完整属性面板（IK/材质/多骨骼选择）仍借用 BBS 工厂，bbsae 时间轴只复用
  pick/插入/gizmo 目标；MobForm 没有骨骼轨，Alt 只能选整个 form。
- 还没跑真实 F6 导出视频抽帧；本轮验证的是同一条 `CompositionOverlay` 路径的 BBS 原版
  实时播放。导出前建议再跑一次 20 秒长合成 + 回放模型的导出抽帧。
