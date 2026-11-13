# Round 84 — 回放编辑器真实图层预览 / 相机跟随 / 空格播放

> 版本 `0.99-hotfix42`。

## 1. 为什么之前仍然只有线框

回放页为了不显示真实 Minecraft 世界，之前采用了一个过粗的规则：**只要合成里存在一个 `bbs`
实时世界层，就整个跳过合成预览**，退回到只有回放 actor + 参考线的 3D pass。于是用户工程里
的图层、文字、图片、三角形、缩放后的模型都看不到，回放相机也不跟随。

修复：新增 `CompPreviewFilter`（thread-local 渲染过滤器）：

- 回放页渲染合成时 push `layer -> layer.type != "bbs"`；
- `CompRenderer` 的合成主循环、`CompViewportRenderer` 的模型/adjustment/图层/拾取循环都检查
  这个过滤器；
- BBS 实时世界层被**单帧过滤**，不修改 `CompLayer.visible`，不写工程、不污染合成器缓存；
- 除 `bbs` 层以外的所有元素（文字、图片、卡片、三角形、特效、linked 模型）都正常渲染。

`CompRenderer` 在有过滤器时也不会复用 pre-render/内容缓存，避免把带真实世界的旧帧画出来；
`ReplayEntityMask` 的世界抠像也不会在过滤器下执行。

真实合成画面已经在画时，`ReplayCompositionOverlay` 不再画各图层的边框/名称（它原来会在真实
文字上再叠一遍「Text Layer · REAL TEXT LAYER」，看起来像重复元素），只保留相机/comp frame
参考线。

![回放自由视角真实文字与图层](docs/verify/round86-real-layers.png)

## 2. 模型缩放/压缩同步

- 自由/绕对象视角直接调用 `CompViewportRenderer`，走的是合成器的 `ReplayActorPass`，会应用
  `CompLayer` 的 position/rotation/scale；
- 相机视角调用 `CompRenderer` 的最终合成，同样应用图层变换；
- 回放页不再用自己的 64px/格映射去覆盖合成器比例，`ReplayEditSession.syncSceneScale()` 使用
  与合成器相同的 `modelPixelsPerBlock`/`height*0.12`，并且**每帧在视口渲染前同步一次**：
  切换到不同分辨率的工程（如 1920x1080）后不会继续沿用上一个 320x180 工程的 22px/格。
- 真机对比：dev-effects（1920x1080）从错误的 `22px/格` 变为 `64px/格`，见
  `docs/verify/round90-dev-effects.png`；控制台另有 `Composition clip found ... project=dev-effects`。

所以合成器里把回放压缩/放大后，回放编辑器显示的是同一套变换。

## 3. 相机预览跟随

相机视角现在直接显示 `CompRenderer.renderCached` 的最终合成帧，使用的是合成文档的
**active camera**（含关键帧 eye/target/FOV），因此摄像机动、镜头就动；不再因为 bbs 层被跳过而
退回静态 actor pass。回放页的相机按钮只是切换预览方式，不改变合成相机的关键帧数据。

## 4. 空格播放与轨道指针

- 空格：`UIReplayModePanel.update()` 在页面激活时检测物理 Space 的按下沿（BBS 的
  `Keys.PLAUSE` 行为）；文本框聚焦时不拦截，避免输入空格变成播放。这样可以绕过“借用的
  BBS film panel 隐藏后它的 secretPlay 键位仍在抢 Space/或收不到键”的问题。
- `ReplayEditSession` 持有 local playhead，`ReplayFilmKeyframes` 的 `getTick/getPlayheadTick`
  直接读它，轨道指针/播放头与 actor 播放一致；
- `advancePlayback` 不再依赖会被 BBS 隐藏 panel 重置的 runner；到片尾像 BBS
  `RunnerCameraController` 一样停止，不再无限循环；
- 播放时指针保持在时间轴可见范围。

## 5. 验证

- 全量测试：**154 套件 / 956 项 / 0 failures / 0 errors / 1 skipped**（新增 `CompPreviewFilterTest`）。
- 真实图层预览：`docs/verify/round86-real-layers.png`（回放自由视角里能看到真实
  `REAL TEXT LAYER` 与 comp 图层，同时 bbs 实时世界层被过滤；截图来自带 `bbs` 层的测试工程）。
- 不同工程/缩放验证：`docs/verify/round90-dev-effects.png`（dev-effects，1920x1080；真实文字
  `文字效果 Aa 123`、渐变 shape、3D card 与模型；`64px/格`）。
- 相机同步：`docs/verify/round83-camera-sync.png`。
- 播放：`docs/verify/round85-playback.png`（timecode `00:00:02:17`）。
- 本地候选包：`bbsae-0.99-hotfix42.jar`（3,913,237 bytes，SHA-256 FC9DB65D3744901CDF1E94362AC1CACB463AE6463397DCA729A1D167F26F1B2A）。
