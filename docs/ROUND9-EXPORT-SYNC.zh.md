# 第九轮：导出画面与合成器同步 + 12 秒时长修复

> 现象：合成器里看得到画面，但导出的视频是 BBS 原版；合成器时长被卡在 12 秒。
> 本轮没有另写一套录像器，而是让 BBS 原生录像路径拿到和合成器完全一致的 composition。

## 1. 第一性原理：为什么“预览有、导出没有”

合成器和导出是两条不同的数据流：

```
合成器预览
  UICompositorPanel
    → CompRenderer.render(project, doc, this.time, ...)   // 直接读面板里的 doc/time

BBS 影片 / F6 导出
  BBS camera timeline
    → CompositionClip.applyClip(context, position)
    → context.clipData[bbsae:instances]
    → CompositionOverlay.render(...)
    → CompRenderer.render(project, doc, instance.time(), ...)
    → BBS framebuffer
    → BBS onRenderBeforeScreen blit
    → VideoRecorder.recordFrame()
```

所以“导出和预览一致”必须同时满足：

1. camera timeline 上**存在**一个 `bbsae:composition` clip；
2. clip 覆盖**整个**导出时间范围，且 `tick=0`、`opacity>0`；
3. clip 指向**当前** project / comp；
4. `FrameOverlays.render` 在导出时真的被调用，且没有被 UI scissor 裁掉；
5. BBS 录像器的**时长**覆盖 composition 的时长（BBS 默认相机时长是 12 秒）。

这五条里任何一条不成立，导出就会退回 BBS 原版，或者只在前几秒出现合成层。

## 2. 找到的四个根因

### 2.1 导出时长由 BBS 相机决定，而不是合成器

`UIFilmPreview.recordVideo` 里：

```java
int duration = this.panel.getData().camera.calculateDuration();
...
recorder.startRecording(duration, ...);
```

`PanelVideoExportSession` 的 `end = duration`。相机默认时长是 12 秒（240 ticks）。
以前 `CompositionClip.ensureForFilm` 只把 clip 时长设为 `max(现有, film.calculateDuration())`，
**没有把合成器自己的 duration 算进去**。于是：

- comp 设为 20 秒，clip 仍是 12 秒；
- BBS 录到 12 秒就结束；
- 12 秒之后的合成器画面全部丢失。

类似地，如果 clip 被拖到 5 秒开始，或右键 trim 到 2 秒，导出只有那一段有 comp。

后续用户看到“上限变成 20 秒”，不是 clip 的硬上限，而是 BBS 的
`PanelVideoExportSession.prepare()` 在 `BBSSettings.editorLoop == true` 时：

```java
this.end = looping && min != max ? Math.max(min, max) : this.duration;
```

把导出结束点改成了 loopMax（20 秒）。所以修根因不是把 12 改 20，而是**不要再让 BBS 的
相机/loop 决定 compositor 导出时长**。

### 2.2 `workAreaOut` 仍是 12 秒

合成器播放循环用的是 `doc.workAreaOut`，不是 `doc.duration`：

```java
float out = Math.min(doc.duration, doc.workAreaOut);
this.time += ...;
if (this.time >= out) { this.time = in + ...; }
```

旧工程的 `workAreaOut` 是默认 12。把 `doc.duration` 改成 30 后，`workAreaOut` 仍是 12，
于是预览播到 12 秒就循环，看起来“合成器只有 12 秒”。修改时长字段也没有更新它。

### 2.3 导出时 UI scissor 可能裁掉 overlay

`CompositionOverlay` 设置了投影、深度、cull、blend，但**没有关闭 scissor**。
影片编辑器的 UI 可能留下一个面板大小的 scissor；overlay 是整帧的，被 scissor 裁掉时
不会报 GL 错误，只是画面里没有合成层。预览面板走自己的 FBO，不受这个 scissor 影响，
所以表现为“合成器有、导出没有”。

### 2.4 一个坏项目会让所有 composition clip 都不画

旧 `CompositionOverlay` 把所有 instance 的渲染放在一个 `try/catch` 里。第一个 project
解析失败/NPE 时，整个 overlay 直接退出，后面本来正常的 clip 也不会被画。
这会把“某个工程坏了”放大成“整部影片没有合成器”。

### 2.5 `BBS 预览` 图层只在合成器面板里存在

`LayerContentRenderer.drawBbsPreview` 原本有一句：

```java
if (!BbsPreviewBridge.isActive()) return;
```

注释写的是“film editor / F6 导出时世界已经在合成下面，所以 BBS 预览层不应该再画”。
但这不是 WYSIWYG：合成器里 BBS 预览层是有位置的图层，可以有位移、缩放、蒙版、效果；
一导出它就直接消失，画面自然和合成器不同。用户这次的定位就是这个图层。

修复需要三件事：

1. 导出/影片预览路径也要允许 `bbs` 图层采样世界；
2. 采样源必须是**干净的当前世界帧**，不是上一帧已经烘进合成器的画面；
3. 2D overlay 不能把深度写进 BBS 世界 framebuffer，否则下一帧世界会留下上一帧的合成残影。

## 3. 修复

### 3.1 时长：`exportDurationTicks`

`CompositionClip` 新增：

```java
public static int exportDurationTicks(int filmTicks, float compDurationSeconds)
{
    int filmDuration = Math.max(20, filmTicks);
    int compDuration = compDurationSeconds > 0F
        ? Math.max(20, Math.round(compDurationSeconds * 20F) + 1)
        : 20;

    return Math.max(filmDuration, compDuration);
}
```

- 单位是 BBS tick（20/s），不是秒；
- 取 film 和 comp 的较长者；
- `ensureForFilm(film, project, comp, compDurationSeconds)` 用这个值设置 clip 时长；
- `prepareForExport` 在导出前把 clip 再正规化一次：
  `tick=0`、`duration=exportDurationTicks(...)`、`opacity=1`、`timeScale=1`、`offset=0`、`hold=true`；
- 导出对话框新增 **“导出时长（秒）”**（`CompProject.CompSettings.exportDuration`，
  0 = 使用合成时长）和 **“同步到 BBS 时间轴”** 按钮；不再要求用户只能改 composition
  duration 来试出一个能导出的长度；
- **进入工程后的“合成器设置”弹窗也有同一个“导出时长（秒）”字段**，并带
  **“把导出时长写入合成与 BBS 时间轴”** 按钮：进入工程 → 设置 → 输入 60 → 应用；
  composition、BBS clip、loopMax 和导出器都使用 60 秒；
- `startBbsExport` 用 `exportSeconds = settings.exportDuration > 0 ? 设置值 : doc.duration`，
  调用 `CompositionClip.prepareForExport(..., exportSeconds)`，并 `filmPanel.forceSave()`，
  把 clip 真正写进影片数据；
- **不再点击 BBS 的 `recordVideo` 按钮**。那个按钮内部用
  `camera.calculateDuration()` 作为时长，而且 BBS 的 `editorLoop` 为 true 时
  `PanelVideoExportSession` 会把结束点改成 loopMax。20 秒限制正是从这里来的。
  现在 compositor 直接调用：
  ```java
  recorder.startRecording(exportTicks, texture.id, videoWidth, videoHeight);
  ```
  其中 `exportTicks = round(exportSeconds * 20F) + 1`，并在导出期间关闭
  `BBSSettings.editorLoop`、结束后通过 `setFinishedListener` 恢复；
- `syncBbsLoop(exportTicks)` 同时把 BBS 的 `loopMin/loopMax` 设成导出长度，
  这样 BBS 原版录制按钮也不会再用旧的 20 秒 loop。

**根因不是“上限 12/20 秒”，而是导出时长曾经由 BBS 相机/loop 决定。** 现在时长由
“导出时长”字段（或 composition duration）决定；在对话框里填 45 秒，导出就是 45 秒，
不需要改任何常量，也不依赖 BBS 相机/loop。

### 3.2 工作区：`normalizeWorkArea`

`UICompositorPanel` 在 bind / selectReplay 时迁移旧工程：

```java
if (doc.workAreaOut <= 0F
    || (doc.duration > 12.001F && Math.abs(doc.workAreaOut - 12F) < 0.001F)
    || doc.workAreaOut > doc.duration)
{
    doc.workAreaOut = doc.duration;
}
```

修改“时长”字段时：

- `workAreaOut` 跟随时长（如果它原本就是旧的全长默认值）；
- `workAreaIn` 越界归零；
- 同步 `CompositionClip`，并 `filmPanel.forceSave()`。

### 3.3 状态：关闭/恢复 scissor，隔离单个 project 失败

`CompositionOverlay`：

- 保存 `GL_SCISSOR_TEST` 与 `GL_SCISSOR_BOX`；
- 渲染前 `RenderSystem.disableScissor()`；
- finally 恢复原 scissor；
- 每个 `CompInstance` 单独 `try/catch`，一个坏工程只跳过自己；
- 增加 `-Dbbsae.traceExport=true` 诊断：每秒打印 instance 数、ticks、customSize、
  当前 menu/panel，便于以后排查“导出没有 overlay”。

### 3.4 `BBS 预览` 图层：导出/影片预览也采样世界

- `BbsPreviewBridge` 新增 `worldCaptureRequested`、`hasBbsPreviewLayer(doc)`、
  `requestWorldCapture(...)`、`hasCapturedWorld()`、`captureWorldNow()`；
- `BbsAeClient` 把世界抓取从 `WorldRenderEvents.LAST` 改到
  `WorldRenderEvents.AFTER_TRANSLUCENT`：在世界 pass 内、BBS 的 FrameOverlays 之前，
  拿到的是干净世界帧，而不是上一帧烘好的合成；
- `LayerContentRenderer.drawBbsPreview` 不再在 `!isActive()` 时直接 return：
  只要 `SceneColor` 有抓取帧，导出/影片预览也把世界画进 `bbs` 图层；
- `CompositionOverlay` 发现 composition 里有 `bbs` 图层时请求抓取；第一帧若还没有抓取帧，
  在 overlay 前 `captureWorldNow()`；
- `CompositionOverlay` 画 2D overlay 时关闭 depth test/depth write，避免把合成深度写进
  BBS 世界 framebuffer，造成下一帧世界采样到上一帧残影。

### 3.5 相关文件

- `src/main/java/dev/bbsae/comp/CompositionClip.java`
- `src/main/java/dev/bbsae/client/ui/UICompositorPanel.java`
- `src/main/java/dev/bbsae/client/overlay/CompositionOverlay.java`
- `src/main/java/dev/bbsae/client/overlay/BbsPreviewBridge.java`
- `src/main/java/dev/bbsae/client/render/LayerContentRenderer.java`
- `src/main/java/dev/bbsae/client/render/SceneColor.java`
- `src/main/java/dev/bbsae/BbsAeAddon.java`
- `src/main/resources/assets/bbsae/assets/textures/gui/compositor_icon.png`
- `src/test/java/dev/bbsae/comp/CompModelTest.java`
- `src/test/java/dev/bbsae/IconResourceTest.java`

### 3.6 合成器图标

用户提供的 32×32 白色合成器图标被缩到 BBS 按钮的 16×16：

- 资源：`assets/bbsae/assets/textures/gui/compositor_icon.png`；
- `BbsAeAddon.ICON` 用 `Link` 指向它，`textureW/H = 16`；
- dashboard 面板注册和 `bbsae:composition` clip 工厂都用这个图标，替换了原来的
  `Icons.FILM`；
- `IconResourceTest` 检查资源存在、16×16、保留 alpha。

## 4. 验证

### 4.1 真实开发客户端

使用 `-Dbbsae.debugPreview=true -Dbbsae.debugExport=true -Dbbsae.traceExport=true`，
把 `film_export_test / main` 的合成时长改为 **20 秒**，启动真实 Minecraft 1.20.1 客户端 +
BBS 2.7，走 BBS 原生 `VideoRecorder`：

```
[21:20:53] Composition export clip prepared: compDuration=20.0s (401 ticks), filmDuration=165 ticks, clipDuration=401 ticks
[21:20:54] Recording video with following arguments: [... 2026-10-18_21-20-54.mp4]
[21:21:07] [export trace] instances=1 [film_export_test:main@6.483] ticks=129 ...
[21:21:17] [export trace] instances=1 [film_export_test:main@12.167] ticks=243 ...
[21:21:21] [export trace] instances=1 [film_export_test:main@14.817] ticks=296 ...
[21:21:28] [export trace] instances=1 [film_export_test:main@19.500] ticks=390 ...
```

`ffprobe`：

```
width=1920
height=1080
nb_frames=1202
duration=20.033333
```

抽取 15 秒帧：`docs/verify/round9-export-20s-15s.png`，画面里仍然有合成器的
panel/title/progress bar，证明 12 秒之后 composition 仍在导出的视频里。

完整 20 秒样片：`docs/verify/round9-export-20s.mp4`。

### 4.2 25 秒根因验证（不再依赖 BBS 相机/loop）

把 composition 设为 **25 秒**，Debug Export 直接使用 `debugComp.duration`：

```
Debug export started: 1920x1080 @60fps film=export_test duration=501 ticks
```

`501 ticks = 25.05s`。导出结果：

```
width=1920
height=1080
nb_frames=1502
duration=25.033333
```

22 秒抽帧：`docs/verify/round9-export-25s-22s.png`，画面里仍有合成器的
`BBS 预览` 图层 + scanlines。ffprobe 记录：
`docs/verify/round9-export-25s-ffprobe.txt`。

这条验证的意义是：**不再有 12 秒或 20 秒常量**，导出长度完全由 `doc.duration` 决定。

### 4.3 40 秒：导出对话框的显式时长

导出对话框新增 **“导出时长（秒）”** 字段（写入 `CompProject.CompSettings.exportDuration`，
0 = 使用合成时长），并新增 **“同步到 BBS 时间轴”** 按钮。

把工程设置为 `exportDuration = 40` 后走同一套 Debug Export：

```
Debug export started: 1920x1080 @60fps film=export_test duration=801 ticks
```

`801 ticks = 40.05s`。导出结果：

```
width=1920
height=1080
nb_frames=2402
duration=40.033333
```

35 秒抽帧：`docs/verify/round9-export-40s-35s.png`，画面仍有 BBS 预览层 + scanlines。
ffprobe 记录：`docs/verify/round9-export-40s-ffprobe.txt`。

这证明即使 20 秒被某处卡住，用户也可以直接在导出对话框输入 40/60/120 秒，
导出器收到的是这个值，而不是相机/loop 的值。

### 4.4 `BBS 预览` 图层导出验证

用一个只包含 `bbs_preview` 图层（1920×1080，加 `scanlines` 效果）的 composition 走同一套
真实客户端导出，抽 1 秒帧：

`docs/verify/round9-bbs-preview-layer.png`

画面是**整幅世界 + 扫描线**，不是黑屏、不是 BBS 原版，也不是上一帧合成残影。说明
`bbs` 图层在 F6 导出路径里真的采样到了干净的世界帧。

### 4.5 自动化测试

- `CompModelTest.compositionExportDurationExtendsPastTheTwelveSecondFilmDefault`：
  - 240 ticks + 20s → 401 ticks；
  - 240 ticks + 8s → 240 ticks；
  - 600 ticks + 8s → 600 ticks。
- `IconResourceTest.compositorIconIsPackagedAtButtonSize`：图标存在、16×16、保留 alpha。
- 全量 **64 / 64** 通过。

### 4.6 图标

- 用户提供的 32×32 图标缩到 16×16：`docs/verify/round9-compositor-icon.png`；
- BBS dashboard 页签和 `bbsae:composition` clip 工厂都使用 `BbsAeAddon.ICON`；
- 资源路径 `assets/bbsae/assets/textures/gui/compositor_icon.png`（BBS addon source pack
  读取的是 jar 里的 `assets/<modId>/assets/...`，不是 `assets/<modId>/textures/...`）；
- 真实客户端日志 `docs/verify/round9-icon-load.txt`：
  `Texture "bbsae:textures/gui/compositor_icon.png" was loaded!`；
- 图标作者 **超级大布叮**，项目维护者 **终end**。

## 5. 已知边界

- 导出时长不再受 BBS 相机/loop 限制；但如果 BBS 相机时间轴没有覆盖 25 秒之后的时间，
  世界画面会停在最后一帧，而 composition 继续播放。希望世界/相机也继续运动，需要延长
  BBS 相机时间轴（影片内容），不是 compositor 时长 bug。
- 同一部影片如果存在多个 replay 的 composition clip，它们仍会叠加。当前导出会正规化
  当前 project/comp 的 clip；要彻底只显示当前 replay，需要下一步做“导出时只启用当前
  composition clip”的显式开关。
- `workAreaOut` 的 12 秒迁移只针对“仍是默认值”的工程；如果你确实故意设置 12 秒循环，
  这个启发式会把它展开到 comp 全长。需要更精细的行为时，应在工程里存一个
  `workAreaCustom` 标记。
- 导出仍依赖 BBS 的 `VideoRecorder`/FFmpeg；没有改成自建录像管线，因此 BBS 的音频、
  motion blur、编码参数、ESC 取消等行为保持不变。
