# Round 120：合成器音频静音、文字默认间距、BBS 原版 gizmo

> 版本 `0.99-hotfix87`。本轮三件事都从“数据从哪里来、谁在写状态”这个第一性问题定位，而不是在
> UI 上叠补丁。全量测试 **203 suites / 1228 tests / 0 failures / 0 errors / 1 skipped**。

## 1. 合成器预览“根本没有声音”

### 根因

`CompAudioPlayer` 的状态机里，`pause()` 会把 `wanted` 清成 false。`UICompositorPanel`
的 `syncPreviewAudio()` 只有在 `previewAudioRunning == false` 或 `isActive() == false` 时才会重新
调用 `play()`；只是被暂停时，`previewAudioRunning` 仍为 true、player 仍然存在，于是:

1. 上一轮新增的 `CompFilmAudio.manage()` 在 BBS 影片/回放 runner 仍处于 `context.playing`
   时，每帧调用 `CompAudioPlayer.pause()`（为了“影片播放优先于试听”）；
2. `pause()` 把 `wanted = false`；
3. 合成器继续每帧调用 `sync()`，但 `sync()` 看到 `!wanted` 直接 return；
4. 结果不是“音量小”，而是 OpenAL 源永远不再被要求播放——合成器整页静音。

### 修复

- `BbsAeClient.isCompositorActive()`：只有合成器是当前 dashboard 面板时才为 true。
- `CompFilmAudio.manage()`：合成器可见时**完全不碰**预览播放器，只暂停它自己的影片合成源；
  删掉了那段会暂停 `CompAudioPlayer` 的代码。
- `CompAudioPlayer.sync()`：如果播放器存在但 `wanted` 被外部清掉，恢复 `wanted` 并在需要时
  `play()`。合成器只在“正在播放”时调用 `sync()`，所以不会把用户主动暂停的状态弄回来。
- 预览混音仍走 `CompMixCache`/`CompAudioPlayer`，不改音频数据本身。

### 验证

实际客户端日志（`-PbbsaeView=curve -PbbsaeEncoder=true -PbbsaePlay=true`，带外部测试音）：

```text
preview audio ready: 1152000 samples (预览音频 0.00s 已暂停) files=1
preview audio: playing=true status='播放中 · 3.76s' time=3.9250584
preview audio: playing=true status='播放中 · 5.50s' time=5.6646657
```

`playing=true` 说明 OpenAL 源确实在播放，而不是只创建了缓冲。截图见
`docs/verify/round120-audio.png`。

## 2. 文字默认间距太紧

### 根因

`CompLayer.tracking` 的默认值是 `0`，而文字检查器里的“字距”是按**合成像素**表达的。更糟的是
默认 BBS 位图字体的 `fontUnitScale` 固定为 1，同一个数值在默认字体上会按 9px 字格放大，和导入
字体的含义不一致。用户看到的“太紧凑”来自旧默认 0，而不是某个图层设置错了。

### 修复

- `CompLayer.tracking` 默认改为 **9**。
- `LayerContentRenderer.trackingInFontUnits()`：tracking 先按 `fontHeight / fontSize` 换到字体
  单位，再叠加 `fontSize / fontHeight` 的栈缩放；无论默认位图字体还是超采样导入字体，
  **1 个 tracking 单位就是 1 个合成像素**，默认 9 就是 9px 字距。
- 工程格式 `CompProject.VERSION = 8`：打开 v7 及更早工程时，把“tracking == 0 且没有
  `text/tracking` 关键帧”的文字层一次性迁移到 9；用户明确写过的非 0 值、以及有关键帧的
  tracking 原样保留。v8 工程再次加载不会再改。

### 验证

- `TextSpacingDefaultTest`：新文字层默认 9，显式值优先。
- `TextTrackingUnitsTest`：默认字体（height 7）和导入字体（height 64）在 72px 字号下，
  tracking 9 渲染后都恰好是 9px。
- `CompStoreMigrationTest.oldZeroTrackingBecomesTheNewNinePixelDefaultOnce`：v7 -> v8 只迁移一次，
  显式 0 在 v8 中保留。

## 3. gizmo：按 BBS 原版目标级联和完整手柄重做

### 上一版的问题

- 回放页虽然调用了 BBS 的 `Gizmo`，但所有目标一律使用 `UIReplayPropTransform.MASK`：
  - 骨骼/锚点只显示移动和 X/Y 旋转，缩放手柄、平面手柄、完整旋转环全部被裁掉；
  - 锚点轨道没有单独的 gizmo，拖动时实际写到了 replay root 的通道。
- 合成器里“BBS native gizmo”把空 `MatrixStack` 交给 `Gizmo.render()`；`Gizmo` 是从
  **传入栈**取摆放矩阵的，不是从 `RenderSystem` 的 model-view 取，所以它一直画在相机原点；
- gizmo 在 HUD 之前绘制，相机视角下模型锚点靠近左上角时会被 HUD 卡片盖住。

### 修复（复用 BBS 原版类，而不是自绘一个简化 gizmo）

- `ReplayEditSession.editTarget(UIKeyframeEditor)` 复刻
  `UIFilmController.getDefaultEditTarget()` 的唯一级联：
  **骨骼 → 表单锚点 → replay root**，`FilmTarget` 直接使用 BBS 的 record。
- `ReplayPickPass` 按目标选择矩阵和遮罩：
  - 骨骼：`FilmMatrices.getGizmoBoneCompositeMatrix(...)`，`HandleMask.ALL`；
  - 锚点：`FilmMatrices.getGizmoAnchorCompositeMatrix(...)`，`HandleMask.ALL`；
  - replay root：`getMatrixForRenderWithRotation(...)`，保留 BBS 的
    `UIReplayPropTransform.MASK`（root 本来就不能写缩放/滚转）。
- 拖拽方向也按同一目标构建：
  - 骨骼：`boneGizmoDrag(area, target.bone())`，同时传 local/parent 两套 frame axes；
  - 锚点：`anchorGizmoDrag(area)`，用 BBS 的 anchor composite 作 frame/rot
    axes，新增 `ANCHOR` 分支不再回落到 root 通道；
  - root：`gizmoDrag(area)` 增加 `getMatrixForRenderWithRotation(...)` 的 frame axes，
    与 BBS `buildReplayGizmoDrag` 一致。
- 视觉与 stencil 共用 `gizmoFrame()`，目标/矩阵/遮罩不可能只到达其中一侧；pick 缓存签名加入
  目标 kind/bone/space，切换骨骼/锚点/root 会强制重建。
- `Gizmo` 的完整手柄（移动、缩放、旋转、平面、球、视口环）由 BBS 原版 `Gizmo` 自己绘制，
  不在 bbsae 里重画。
- 合成器 `renderBbsNativeGizmo()`：把 `camera.view × layerWorld` 传进 `MatrixStack`，
  `Gizmo.render(gizmoStack)` 不再画在原点。
- 回放页把 gizmo 放到 HUD **之后**绘制，并在绘制前清理残留深度，避免 HUD 卡片/合成景深
  把 gizmo 整块盖住。

### 验证

客户端 gizmo 脚本仍输出：

```text
Replay gizmo capture: ... target=BONE bone=anchor ... mask=Gizmo$HandleMask@...
Replay gizmo capture: ... target=ROOT ... mask=Gizmo$HandleMask@...
Replay gizmo draw: ... placed=true center=...
Replay gizmo script: RESULT gizmoStarted=true channelsMoved=true partSelected=true layoutSplit=true
```

骨骼目标使用 `ALL` 遮罩、root 使用 BBS 的受限遮罩（日志中两个 mask 不同），
gizmo 拖动确实写回关键帧。截图见 `docs/verify/round120-gizmo.png`。

## 兼容性

- 工程格式从 7 升到 8；v8 文件在旧版 bbsae 中会按既有的“未来版本”保护拒绝写回，请用
  `0.99-hotfix87` 打开。
- 新的文字默认值只迁移“旧默认 0”；显式设置过字距的图层不会被覆盖。
