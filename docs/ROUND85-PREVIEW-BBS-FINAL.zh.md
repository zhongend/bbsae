# Round 85 — 回放编辑器预览重构：BBS 世界帧 + 最终合成 / 干净的虚拟舞台

> 版本 `0.99-hotfix43`。只加后缀，不迭代 1.0；本轮只改**回放编辑界面的预览**。

## 1. 为什么回放预览"看起来不对"

### 1.1 相机视角没有 BBS 世界帧

`0.99-hotfix42` 为了让回放页不显示真实世界，在回放页渲染合成时用
`CompPreviewFilter` 把 `bbs` 层**整层过滤**了。相机视角因此只剩合成里的文字/形状/模型，
背景是视口自己的黑色，而不是影片画面。用户看到的"BBS 预览无法显示"就是这个：工程里的
`BBS 预览` 层被主动丢弃，而世界帧又没有别的来源。

### 1.2 自由/绕对象视角把整张 2D 合成铺进 3D 世界

同一轮里，自由视角改为直接调用合成器的 `CompViewportRenderer`。它把合成当成一个放在
3D 世界里的巨大平面来画：文字、渐变卡、3D 卡按 comp 像素铺开，相机动一下就会看到"巨大
文字 + 穿透的色块"，既不是虚拟舞台，也不是最终画面。actor 和合成层还可能互相遮挡/缩放
不一致。

### 1.3 相机姿态是手推的

`UIReplayViewport.lookThroughCompositionCamera` 以前自己从 eye/target 反算 yaw/pitch，
并强制 `ortho=false`。它丢掉了 `CompCamera` 的 roll、正交模式、near/far 和 `cameraStyle`
语义，于是"画面对了、gizmo 偏了"在带滚转或平面相机的镜头上必然出现。

### 1.4 画幅比例不对

相机视角把合成直接 blit 到整个视口矩形。视口不是 16:9 时，合成被拉伸；世界帧也没有按
影片画幅 letterbox。

### 1.5 每帧重渲整张合成

`CompPreviewFilter.active()` 会让 `CompRenderer.renderCached` 跳过所有缓存。相机视角又
每显示帧调用一次，于是"暂停不动"也在每帧重跑整条合成图；播放时钟还在 `update()`（20 Hz）
里推进，60 fps 合成看起来一跳一跳。

## 2. 本轮做的事

### 2.1 相机视角 = BBS 世界帧 + 最终合成

- `BbsPreviewBridge.setReplayWorldPreview(true)` 在回放页打开时请求一份**干净的世界帧**；
  `captureFrame` 在 `AFTER_TRANSLUCENT` 抓取，早于 BBS frame overlay，不会把合成录进
  自己的世界层。
- `setTimeOverride(session::playheadSeconds)` 让 BBS 胶片相机跟随**回放播放头**，而不是
  合成器的 `time`；相机/世界/合成三者在同一时刻。
- `renderFilmActors` 在写世界帧时临时隐藏 bbsae 虚拟回放实体：它们是合成里的演员，不是
  真实影片演员，否则每个模型都会以世界坐标"鬼影"出现在 BBS 世界帧里。
- 相机视角先按 `doc.width/doc.height` 计算合成矩形（`ReplayPreviewLayout.fit`），画黑边，
  再在矩形内以 contain 方式画世界帧（`ReplayPreviewLayout.contain`），最后把
  `CompRenderer.renderFrame(project, doc, frame, true, scale)` 的透明帧叠上去。
- 合成帧走**导出/AE 同一条 `renderFrame` 图**：文字、图片、形状、3D 卡、遮罩、特效、linked
  回放模型和 `bbs` 预览层都在；不再有"相机视角只显示一部分"的问题。
- 合成层里有 depth 效果时，预览会主动 `requestDepth(true)`，并在
  `needsWorldDepth` 满足后才烘焙，避免把无深度的帧缓存住。

### 2.2 自由/绕对象视角 = 干净的虚拟舞台

- 不再调用 `CompViewportRenderer`；改为：
  - 深色渐变 + 细网格的舞台背景；
  - `ReplayModelPass` 用 BBS 自己的 `FilmEntityRenderer` 画虚拟回放模型；
  - `ReplayCompositionOverlay` 画构图框、相机、图层中心/名称等轻量参考线。
- 整张 2D 合成不再塞进 3D 空间；要看最终画面切相机视角，或开 PiP/双窗口合成预览。
- 修正 `ReplayCalibration.frameMinDistance(pixelsPerBlock)`：自动取景的最小距离随合成
  的 px/格缩放，320×180 工程不会再让 40px 的模型退到 600px 外变成小点；显式
  `-Dbbsae.replayFrameMinDistance` 仍然优先。
- "框选全部"后同步自由相机姿态，相机↔自由视角切换不会回到取景前的默认位置。

### 2.3 相机/gizmo 完全一致

- `lookThroughCompositionCamera` 改为 `CompCamera.of(doc, time, frame)` +
  `CompViewportCamera.lookThrough(compCamera)`：eye、target、FOV、near/far、roll、正交
  全部来自同一个相机解析函数。
- `CompViewportCamera` 增加 `roll` 字段，view 基向量按 `CompCamera` 同样的约定滚转；
  `ReplayViewCamera` 把它写进 BBS `Camera.rotation.z`。
- 相机视角的 gizmo/pick 使用**合成矩形**而不是整个视口：`getGizmoArea()`、pick pass、
  `GizmoDrag` 全部走 `cameraArea`，手柄不会再落到画面外/偏一截。

### 2.4 缓存与播放

- `UIReplayViewport` 自己缓存相机视角的合成帧（`GlFramebuffer` + `CompRenderCache.signature`），
  只有当**合成帧号 / 文档签名 / 烘焙分辨率**变化时才重渲；暂停时每显示帧只画一个 textured
  quad。
- 播放时预览分辨率乘 0.85，稳定帧率；暂停时按视口设备像素全分辨率烘焙。`renderFrame`
  的 3D 层通过 `CompRenderContext.setTargetEdge` 按预览栅格烘焙，不再发软。
- 播放头从 `UIReplayModePanel.update()`（20 Hz）移到 `UIReplayViewport.render()`（显示帧），
  用单调时钟推进；60 fps 合成不再按 20 Hz 一跳一跳。
- 世界帧在合成页离开后释放（`UIReplayViewport.releasePreview`），不长期占 FBO。

### 2.5 诊断状态

- HUD 区分"相机视角 · 最终合成"和"自由/绕对象 · 虚拟世界"。
- 相机视角会显示 BBS 世界帧是否就绪、是否在同步世界/合成时间、合成渲染失败原因；
  不再只给一个黑框。

## 3. 验证

- 全量测试：**155 套件 / 966 项 / 0 failures / 0 errors / 1 skipped**。
- 真机（一次启动、两张截图；`bbsae.debugReplayViewShots` 测试钩子）：
  - `docs/verify/round91-preview-camera.png`：相机视角，BBS 世界帧按合成画幅 letterbox
    显示（世界日志 `BBS preview layer composited a 1920x1080 world frame`）；
  - `docs/verify/round91b-preview-camera.png`：同一路径下选中虚拟回放，世界帧 + 原生 gizmo
    同时在画面里；
  - `docs/verify/round92-preview-camera.png`：BBS 世界帧再次确认（320×180 测试工程，64px/格）。
- 日志：`_tools/run-round91-preview3.log`、`_tools/run-round91b-preview.log`、
  `_tools/run-round92-preview.log`。
- 相机帧/合成帧走 `CompRenderer.renderFrame`（与导出/AE 同一条图），日志中的
  `BBS preview layer composited a 1920x1080 world frame` 直接证明 `bbs` 层已经在相机视角
  的合成帧里，而不是被过滤器丢弃。
- 本地候选包：`bbsae-0.99-hotfix43.jar`（3,922,291 bytes，SHA-256
  `C1CBA6D5128423C46D0A4898BF265BD43B7FFC628663AA14509660D24E7CD9EE`）。

## 4. 仍未做

- 自由视角没有把合成层作为半透明 ghost 叠回舞台；要看合成请切相机视角或开双窗口。
- 相机视角的预览缓存按合成帧复用；暂停时同一帧内如果真实世界继续动画（多人服务器），
  BBS 预览层会保持该帧第一次抓到的世界画面，这是"帧预览"而非"实时视频"。
