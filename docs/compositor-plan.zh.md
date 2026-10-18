# BBS FS 屏幕空间合成器：技术判断与分阶段计划

> 调研对象：`Wemppy4/bbs-fs` 的 `master` 分支（BBS 2.7 / Minecraft 1.20.4 / Fabric / Java 17 / Addon API v2），本地检出于提交 `13bd62d`。
> 文中源码路径均相对 BBS 仓库根目录。

---

## 0. 结论先行

**我建议做“BBS 内的合成器”，而不是“独立 AE + BBS 桥”。**

具体是：

1. **合成器是一个 BBS Addon**，有自己的 Dashboard 标签页、图层栈、时间轴、曲线编辑器、预览和渲染队列。
2. **合成器工程是独立文件**（`.bbscomp`，JSON + 资产目录），但**它和影片的绑定不是一个旁挂的 sidecar**，而是影片相机时间轴上的一个自定义 Clip（`bbscomp:composition`）。Clip 里存工程引用、时间偏移、适配方式、不透明度等。
3. **真正把合成层画进画面的，是 `RegisterFrameOverlaysEvent` 这个官方 API**。它在世界渲染结束、画面被 blit 到导出纹理之前调用；BBS 自带的图片/字幕覆盖层走的就是同一条路。也就是说：**不需要 Mixin 就能让合成层进入 BBS 的 FFmpeg 录制结果**。
4. **不碰 `.dat` 影片文件**。BBS 会把自定义 Clip 当普通数据序列化；Addon 不在时，未知 Clip 会被原样保留。这比解析/覆盖影片文件稳得多。
5. **第一阶段不是“做一个小 AE”，而是“做一个能用的 BBS Motion 工具”**：图片/纯色/文字/形状图层 + 变换/不透明度/混合 + 父子 + 关键帧/曲线 + 蒙版/羽化 + 少量高质量效果 + 和影片一起导出。先把“BBS 创作者今天就能用”的价值交付出来，再往预合成、调整层、跟踪、深度、3D 文字这些方向长。

一句话：**把 BBS 当框架用，不要把它当黑盒；把合成器做成 BBS 的一部分，而不是另起一个世界。**

---

## 1. 读源码后，需要先对齐的几个事实

### 1.1 影片工程不是 `.bbsf`，是压缩的 `.dat`

`FilmManager` 的扩展名是 `.dat`，存储用 `CompressedDataStorage`（`src/main/java/mchorse/bbs_mod/film/FilmManager.java:43-46`）。它是 BBS 自己的 `MapType` 结构，不是公开格式。

**但这不重要，因为我们不需要解析它。** 自定义 `CameraClip` 会被 BBS 的 `Clip` 序列化机制自动写进影片；Addon 被移除时，ADDONS.md 明确说 BBS 会原样保留未知的 namespaced Clip。也就是说，**“关联”这件事可以由 BBS 自己完成**，不需要我们硬啃影片格式。

### 1.2 屏幕空间覆盖层已经有官方入口，而且会被录进视频

关键链路（都在客户端）：

- `BBSModClient` 在 `WorldRenderEvents.LAST` 里调用 `videoRecorder.recordFrame()`：`src/client/java/mchorse/bbs_mod/BBSModClient.java:773-779`
- `BBSRendering.onWorldRenderEnd()` 在 `renderWorld` 返回时调用 `FrameOverlays.render(...)`：`src/client/java/mchorse/bbs_mod/client/BBSRendering.java:410-440`
- `BBSRendering.onRenderBeforeScreen()` 把当前 framebuffer blit 到导出纹理 `BBSRendering.getTexture()`：同文件 `:442-492`
- `VideoRecorder` 从这张纹理 `glGetTexImage(..., GL_BGR, ...)` 读像素，直接管道给 ffmpeg：`src/client/java/mchorse/bbs_mod/utils/VideoRecorder.java:320-374`
- 影片导出用的就是这张纹理：`WorldVideoExportSession.java:63`、`UIFilmPreview.java:213`

`FrameOverlays` 本身是一个很小的注册表（`src/client/java/mchorse/bbs_mod/ui/film/FrameOverlays.java:25-55`），`RegisterFrameOverlaysEvent` 在 API 包里（`src/client/java/mchorse/bbs_mod/api/client/events/RegisterFrameOverlaysEvent.java`）。

**源码链路显示：** 你注册一个 `IFrameOverlayRenderer`，在里面画合成结果，BBS 的普通播放、影片面板预览、F6 导出、面板导出都会带上它。POV 这类插件能做出 HUD 效果，底层机制就是这一层（BBS 自带的 `ImageClip` / `SubtitleClip` 也走它）。**P0 必须用实际导出确认，不能只靠读代码。**

> 注意：这是“画面画上去”，不是“拿到一个可编程的渲染图”。我们能拿到最终 framebuffer，但不能替换 BBS 的渲染管线。这决定了合成器是“后期叠加层”，不是“接管世界渲染”。

三个已知限制，P0 要一并验证：

- `FrameOverlays` 只在有影片相机（`PlayCameraController`）或影片面板打开时调用；普通世界录制（F4）不会画。
- Addon 的 renderer 注册在 BBS 自带的图片/字幕之后，所以合成层默认在最上层。要改顺序，得把图片放进 comp，或者等上游加优先级。
- 影片面板预览那条分支依赖 `customSize` 处于开启状态；预览时它是否一直开着，要实测。

### 1.3 导出是实时帧捕获 + ffmpeg，不是离线渲染器

`VideoRecorder.startRecording()` 把原始 BGR 帧通过管道写给 ffmpeg，参数来自设置里的字符串模板（`%WIDTH%`、`%HEIGHT%`、`%FPS%`、`%NAME%`、`%FILTERS%`、`%AUDIO_TRACK%`）：`VideoRecorder.java:68-141`。

运动模糊不是后处理，而是**提高渲染帧率 + ffmpeg `tblend/framestep` 混合**：`BBSRendering.getVideoFrameRate()` / `getMotionBlur()`（`BBSRendering.java:101-145`），`VideoRecorder.java:103-111`。

这带来两个重要结论：

- **合成层会被运动模糊一起处理**，这是好事（比在 AE 里假运动模糊更真）。
- **重效果会拖慢导出，但不会让时间轴错位**：录制是按帧推进的，不是按墙钟。帧率限制关闭时，渲染慢只会让导出耗时变长。P0 要实测确认。

### 1.4 深度和 Object ID 不在 Addon API 里

这是你的描述里最需要修正的一点。

- **深度**：API 没有暴露。但在 `onWorldRenderEnd` 时，`MinecraftClient.getFramebuffer()` 就是 BBS 的自定义 framebuffer，理论上可以把它的 depth attachment blit 到我们自己的 `R32F` 纹理。**可行，但需要 P0 实测，尤其是 Iris 开着的时候。**
- **Object ID**：源码里确实有一套 ID pass（`StencilFormFramebuffer` + `StencilMap`，`src/client/java/mchorse/bbs_mod/ui/utils/StencilFormFramebuffer.java`），但它是**编辑器点选**用的，只在影片预览的拾取流程里跑，不在导出路径上，也没有 API。想拿它做 Shatter / Roto / 内容识别填充，得改 BBS 或做很脏的 Mixin。**不建议把它放进第一阶段。**

所以：

- “绑定 3D 锚点”可以做（相机矩阵、replay/bone 矩阵是能拿到的，见 1.5）。
- “深度引导的填充 / 景深 / 深度遮罩”可以作为二期差异化，但要先证明深度可读。
- “按 Object ID 切碎画面”属于三期/研究项，不要当卖点承诺。

### 1.5 BBS 已经有一大堆“AE 基础设施”

这是“在 BBS 内做”的最大理由。源码里已经存在：

| 能力 | BBS 里有什么 | 对我们意味着什么 |
| --- | --- | --- |
| 值/属性系统 | `BaseValue`、`ValueGroup`、`ValueStableList`、`ValueFloat/Color/Transform...` | 图层属性、序列化、撤销、UI 控件可以直接复用 |
| 关键帧 | `KeyframeChannel`、`Keyframe`、`KeyframeSegment` | 关键帧存储和插值不用自己写 |
| 插值 | `Interpolations` 里有 `BEZIER`、`AUTO`、`AUTO_CLAMPED`、`BSPLINE`，以及大量 easing | “连续贝塞尔/自动钳制”不是从零做，是接上去 |
| 曲线编辑器 | `UIKeyframeGraph`、`UIKeyframeDopeSheet`、`UIKeyframes` | 数据层可复用，UI 层要适配（见风险） |
| 字体 | `LabelForm` 支持 TTF/OTF，`FontManager` 用 STB 烘字形 | 文字图层不用自己写字体栅格化 |
| 表达式 | `math/molang`、`ValueExpression`、`MathBuilder` | 表达式控制器有现成语言可接 |
| 撤销 | `UndoManager`、`ValueChangeUndo`、`IUndo` | 图层增删/属性编辑可以接 |
| 资产/纹理 | `Link`、`Textures`、`RegisterSourcePacksEvent`、`RegisterImportersEvent` | 图片/字体/LUT 的加载和拖拽导入有现成路径 |
| 编辑器外壳 | Dashboard 标签页、`RegisterDashboardPanelsEvent`、`UIDashboardPanel` | 合成器可以是一个正经的全屏标签页 |
| 图层 UI 先例 | 纹理编辑器的 `UILayersPanel`、`UILayerElement` | 证明 BBS UI 能承载图层栈这种交互 |
| 渲染队列 | `FilmQueueExporter` 已经能按打开的影片标签批量导出 | 影片级队列不用重造，二期扩展输出模块即可 |

这些不是“顺便提一下”，而是**项目能不能在合理时间内做出来的关键**。如果这些全部自己写，那就是 12-18 个月；接上去，P1 可以压到 2-3 个月量级。

### 1.6 导出分辨率受窗口 framebuffer 限制

`applyExportSizeToBBS()` 只是把“视频尺寸”设给 BBS（`UIFilmPanel.java:834`），真正的世界渲染目标还是窗口 framebuffer 的大小；`onRenderBeforeScreen()` 再用 `glBlitFramebuffer` 缩放到视频尺寸（`BBSRendering.java:462-477`）。

后果：

- 窗口 2560×1440 导出 1080p：超采样，画质好。
- 窗口 1080p 导出 4K：整张画面（包括合成层）都是放大，文字会软。
- 合成层本身可以先渲染到自己的 FBO（比如 4K），再画进 framebuffer；但最终还是要经过窗口分辨率这一关。**除非**在 blit 之后直接往 `BBSRendering.getTexture()` 里画，那就需要一个小 Mixin 或上游 API。

P1 先接受“窗口分辨率 = 合成上限”，并在 UI 里提示；P2 再决定要不要加“高分辨率覆盖层”路径。

### 1.7 默认录制链路没有 Alpha

`VideoRecorder` 读的是 `GL_BGR`，三通道（`VideoRecorder.java:354`）。BBS 的默认导出是“带背景的成片”，不是带 Alpha 的素材。

所以：

- **“BBS 画面 + 合成层”一起导出**：默认路径就能做，不需要 Alpha。
- **“只要合成层，带 Alpha”**：需要单独一条 comp-only 渲染路径，自己开 RGBA FBO、自己喂 ffmpeg（ProRes 4444 / PNG 序列）。这是二期“多输出模块”的一部分，不是 P1 的阻塞项。

---

## 2. 技术路线：为什么是“BBS 内的合成器”

### 2.1 三条候选路线

| 路线 | 做法 | 优点 | 致命问题 |
| --- | --- | --- | --- |
| A. 纯 Mixin 接管渲染 | 挂 `renderWorld` / Iris composite，自己做一套 render graph | 控制力最强 | 和 BBS/Sodium/Iris 的渲染状态强耦合，BBS 一更新就碎；开发量最大 |
| B. 独立合成器 + BBS 桥 | 桌面 App 做编辑/渲染，BBS 只负责出片/出数据 | UI/UX 最好，离线质量最高 | 需要 BBS 导出帧、深度、相机、锚点等“渲染包”；默认录制链路不提供；两套渲染器容易漂移；工作量 ×2 |
| C. BBS Addon 内合成器 | 合成器是 Addon，用 `FrameOverlays` 把结果画进帧，BBS 的 ffmpeg 直接录 | 最短路径、单一渲染器、单一工程、导出无缝 | UI 受 BBS 框架限制；重效果受实时渲染限制；深度/ID 受限 |

**我推荐 C，阶段性地把 B 作为后期“Studio/离线渲染”扩展，而不是起点。**

### 2.2 推荐架构

```
┌─────────────────────────────────────────────────────────────┐
│ BBS FS (Fabric 1.20.4)                                      │
│                                                             │
│  相机时间轴                                                 │
│   └─ bbscomp:composition Clip                               │
│        ├─ comp 引用 / offset / timeScale / fit / opacity     │
│        └─ applyClip() → ClipContext.clipData 里登记一个实例   │
│                                                             │
│  FrameOverlays (RegisterFrameOverlaysEvent)                 │
│   └─ CompositionOverlay                                     │
│        ├─ 取出本帧所有 active comp                          │
│        ├─ CompRenderer：FBO 链、蒙版、混合、效果             │
│        └─ 画进当前 framebuffer（BBS 之后会 blit + 录制）     │
│                                                             │
│  BBS VideoRecorder → ffmpeg → .mp4/.mov                     │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 合成器编辑器（BBS Dashboard 标签页）                         │
│  图层栈 │ 预览 │ 属性检查器 │ 时间轴/曲线 │ 资产 │ 渲染队列  │
│  数据：CompDocument (BaseValue/ValueGroup/KeyframeChannel)  │
│  文件：.bbscomp (JSON) + assets/ + fonts/                   │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 为什么不是 A

- BBS 的渲染已经很复杂：Sodium、Iris、DH、BBS 自己的 framebuffer 切换、motion blur 子帧、HUD 尺寸替换。Mixin 进去做一套并行 render graph，等于和这些全部为敌。
- `FrameOverlays` 已经在正确的位置：世界画完、blit 之前。你不需要改渲染循环，只需要在它给的时机画。
- 只有在“必须 blit 之后以全视频分辨率画”或“必须拿 Object ID”时，才考虑非常窄的 Mixin；而且应该先尝试上游 API。

### 2.4 为什么不是 B（作为起点）

- BBS 的默认导出是“实时录制一张纹理”，不是“逐帧渲染并落盘”。要离线合成，得先让 BBS 输出帧、深度、相机、锚点，甚至 Object ID。这些大部分不在 API 里。
- 如果桌面 App 自己渲染合成层，BBS 里还需要一个“预览版”渲染器；两个渲染器的字体、模糊、混合、色彩只要有一点不一致，创作者就会不信任预览。你要么共享渲染器（工程量大），要么接受“预览只是近似”。
- 独立 App 的 UI 当然更舒服，但它是**第二个产品**。在 P1 还没证明“BBS 画面 + 合成层能稳定一起导出”之前，投入桌面 App 是把风险放在了最不该放的地方。

### 2.5 什么情况下我会改推 B

满足任意两条，就值得把桌面 Studio 提前：

- P0 证明 `FrameOverlays` 在 Iris/Sodium 下不稳定，或者重效果在实时录制里根本跑不动。
- 你明确要服务“不打开 Minecraft 的后期/剪辑人员”，他们需要在桌面 App 里独立改合成。
- 你需要 4K/6K 超采样、离线运动模糊、EXR/ACES 这类实时链路给不了的质量。
- 你愿意接受“两套渲染器 + 一致性测试”的长期成本。

即使那样，**P1 仍然应该先在 BBS 内把数据模型、图层语义、导出链路跑通**，再把渲染器抽出去。不要反过来。

---

## 3. 架构设计

### 3.1 运行时链路：Clip → ClipContext → FrameOverlays → Recorder

这是整个项目最漂亮的一点，应该严格照 BBS 的 `ImageClip` 模式做：

1. `CompositionClip extends CameraClip`。
2. `applyClip(ClipContext context, Position position)` **不修改相机**，只把本帧的合成实例放进 `context.clipData`（`ImageClip` 就是这么干的：`src/main/java/mchorse/bbs_mod/camera/clips/misc/ImageClip.java:64-74`）。
3. `FrameOverlays` 的 renderer 从 `context.clipData` 取出所有实例，按 Clip 的 layer 顺序绘制。
4. `ClipContext` 里已经有 `relativeTick` 和 `transition`（`CameraClipContext.java:59`），合成时间就是 `(relativeTick + transition) / 20.0` 秒。**不要用墙钟，不要用 `System.currentTimeMillis()`**，否则导出会抖。
5. 影片面板预览走 `BBSRendering` 的 dashboard 分支（`:429-437`），导出走 `PlayCameraController` 分支（`:414-420`），同一套 renderer。

这个设计的额外好处：

- 合成层自动跟随相机 Clip 的入点/出点/图层/Envelope。
- 一个影片可以叠多个合成（HUD、标题、转场），顺序由 Clip 的 `layer` 决定。
- Clip 的 `Envelope` 可以直接当“合成整体淡入淡出”。
- 合成工程被替换/重载时，影片不需要改。

### 3.2 数据模型：站在 BBS 的 `BaseValue` 上

不建议自己发明一套属性系统。建议：

```
CompDocument extends ValueGroup
├─ ValueString name
├─ ValueInt width / height / fps
├─ ValueInt durationTicks
├─ ValueColor background
├─ ValueStableList<CompLayer> layers
├─ CompTracks tracks          // path -> KeyframeChannel
├─ ValueStableList<CompMarker> markers
└─ ValueGroup bindings
```

`CompLayer extends ValueGroup`：

```
├─ ValueString type           // image / solid / text / shape / null / precomp / adjustment
├─ ValueString name
├─ ValueBoolean visible / locked / solo
├─ ValueInt in / out
├─ ValueTransform transform   // BBS 自带：位移/缩放/旋转
├─ ValueFloat anchorX / anchorY
├─ ValueFloat opacity
├─ ValueInt blendMode
├─ ValueString parentId
├─ ValueGroup payload         // 按类型：text / shape / image / solid / precomp
├─ ValueStableList<CompMask> masks
└─ ValueStableList<CompEffect> effects
```

`CompTracks` 可以借鉴 `FormProperties` 的做法：一个 `Map<String, KeyframeChannel>`，key 是“layerId/propertyPath”，序列化时写成列表；读不认识的通道就原样保留（BBS 自己就是这么做的，`FormProperties.foreignTracks`）。这样：

- 关键帧存储/插值直接复用 `KeyframeChannel`。
- 曲线编辑器可以接 `UIKeyframeGraph` / `UIKeyframes`（需要适配，见 7.1）。
- 撤销可以接 `UndoManager` / `ValueChangeUndo`。
- 属性检查器可以尽量用 `UIValueMap` 从 `BaseValue` 自动生成控件。

**代价**：这套模型和 BBS 的值系统绑得比较紧，未来桌面 App 不能直接复用内存模型。解决方式是**把 `.bbscomp` JSON 当作真正的契约**：桌面 App 读同一份 JSON，自己实现模型；渲染器/shader 再看能不能共享。不要让“未来可能有的桌面 App”反过来绑架 P1 的实现。

### 3.3 渲染器：一条经典的 2D 合成链

不要一上来做“通用渲染器”。按 AE 的合成模型做一个够用的：

```
对每个 comp：
  A = 累积缓冲（RGBA，premultiplied）
  for layer in 从下到上:
    L = 图层内容渲染到自己的 FBO
    L = 应用蒙版（mask texture）
    L = 应用效果链（ping-pong FBO）
    if layer 是调整层:
        A = 对 A 应用效果链
    else:
        A = blend(A, L, mode, opacity)
  把 A 按 fit/transform 画到 BBS 帧上
```

关键实现点：

- **每个图层独立 FBO**：混合模式、蒙版、效果、轨道遮罩都变成“对一张 RGBA 图做操作”，不用和 BBS 的绘制状态纠缠。
- **蒙版**：路径填充用 stencil even-odd，得到 R8 蒙版纹理；羽化用可分离高斯；多蒙版按 add/subtract/intersect 组合。P1 先做多边形 + 羽化，P2 加贝塞尔手柄/扩展/跟踪。
- **混合模式**：normal/add/screen/multiply/overlay/soft-light 这 6 个先做；用 shader 采样 backdrop + layer，别依赖固定管线。
- **文字**：把 BBS 的 `FontRenderer`/`TextRenderer` 画进 L 的 FBO。BBS 的 TTF 支持（`LabelForm.font` / `FontManager`）能直接解决字体问题；逐字动画器需要字形级布局，P2 再做。
- **形状**：矩形/椭圆/多边形/星形先做；填充用三角化或 stencil，描边用 polyline strip；trim path/repeater 是 P2。
- **效果**：一个小型 effect registry，每个效果 = 参数 schema + shader + pass 数。P1 先做 blur、glow、drop shadow、brightness/contrast/saturation/hue、gradient ramp、tint、vignette；不要碰 239 个。
- **色彩**：P1 在 sRGB 空间做，和 AE 默认的 8-bit 工作流接近；P2 加 LUT/曲线；P3 再谈线性/OCIO。
- **状态保护**：所有自定义 GL 调用包在 `GlStateGuard` 里（bindings、blend、depth、cull、scissor、viewport、projection、shader、VAO）。Iris 开着的时候尤其重要。
- **分辨率**：预览按面板像素渲染，导出按 comp 分辨率渲染；效果参数里以“comp 像素”为单位的值，在预览时要按比例缩放，否则预览和导出不一致。

### 3.4 编辑器：一个 Dashboard 标签页

BBS 的 `RegisterDashboardPanelsEvent` 会给你 `UIDashboard`，`dashboard.getPanels().registerPanel(...)` 可以加一个全屏标签页（`src/client/java/mchorse/bbs_mod/ui/dashboard/UIDashboard.java`、`UIDashboardPanels.java:296`）。纹理编辑器已经证明了这种复杂编辑器在 BBS 里能跑。

建议布局：

```
┌──────────────────────────────────────────────────────────────┐
│ 顶栏：comp 选择 / 分辨率 / fps / 时长 / 保存 / 导出 / 队列    │
├───────────────┬──────────────────────────────┬───────────────┤
│ 图层栈         │ 预览（棋盘格/安全框/参考线）  │ 属性检查器     │
│ 眼睛/锁/独奏   │ 平移/缩放/适应                │ 变换/蒙版/效果 │
│ 拖拽排序       │                              │ 文字/形状参数  │
├───────────────┴──────────────────────────────┴───────────────┤
│ 时间轴：图层条 / 入出点 / 关键帧行 / 曲线/速率图 / 播放头      │
└──────────────────────────────────────────────────────────────┘
```

P1 的交互优先级：

1. 图层选择/拖拽排序/显示隐藏/锁定/独奏。
2. 预览平移缩放、适应窗口、棋盘格、安全框。
3. 属性检查器 + 关键帧按钮 + 曲线编辑器。
4. 时间轴拖拽入出点、拖动播放头、框选关键帧。
5. 蒙版点编辑（P1 多边形，P2 贝塞尔）。
6. 文字/形状参数面板。
7. 资产面板（图片/字体/其他 comp）。
8. 渲染对话框。

### 3.5 数据绑定：把“跟踪”变成“投影”

这是相对 AE 的结构性优势，值得单独设计。

P1 先做最简单的三种绑定：

- **Screen-locked**：图层固定在屏幕坐标，不随相机动（HUD）。
- **Replay 绑定**：把图层位置绑到某个 replay 的实体位置，投影到屏幕；可选距离缩放。
- **Camera 绑定**：图层跟随相机（比如固定在画面某个角），但可加相机旋转/抖动的反向补偿。

P2 再做：

- **Bone/Anchor 绑定**：用 `BaseFilmController.getEntities()` + `FilmMatrices`/`Anchor` 解析 3D 点，投影到 2D；支持 offset、朝向、距离衰减。
- **Mask 绑定**：蒙版点跟着锚点走（相当于 mask tracking）。
- **深度绑定**：用深度纹理做 matte/DOF/雾。

实现上要接受一个现实：**相机/骨骼数据大部分是 BBS 的内部类，不是 API**。把所有内部访问收进一个 `BbsCompat` 层，按 BBS 版本做适配；不要在 UI/渲染代码里到处 `import mchorse.bbs_mod.film.FilmMatrices`。

### 3.6 模块边界

P1 建议单 Gradle 模块，但包边界严格：

```
com.yourname.bbscomp
├─ comp/       文档模型、图层、轨道、求值（尽量不依赖 UI）
├─ render/     FBO、shader、蒙版、效果、文字/形状绘制
├─ bbs/        Clip、FrameOverlay、事件注册、BbsCompat
├─ ui/         Dashboard 面板、图层栈、检查器、时间轴、预览
└─ export/     输出模块、comp-only pass、ffmpeg 参数
```

如果未来真的做桌面 Studio，再把 `comp/` + `render/` 抽成独立模块；不要在 P1 为了“可能的未来”做多模块。

---

## 4. 分阶段计划

> 时间以“1 人全职、熟悉 Java/OpenGL、能读 BBS 源码”为前提；兼职大约 ×1.8–2.5。范围比日期重要，日期只是量级。

### P0：可行性冲刺（1–2 周）

**目标：用最小代码证明“这条路真的能走”，而不是做功能。**

要做的事：

1. 用 `docs/addon-template` 起一个 Addon，跑通 BBS dev client（`gradlew publishToMavenLocal` + 模板）。
2. 注册一个 `FrameOverlays` renderer，画一张测试卡：渐变背景 + 移动方块 + 文字 + 一张图片 + 一个圆形蒙版。
3. 验证它在四个场景都出现且对齐：
   - 游戏内播放影片（F6 前的播放）
   - 影片面板预览
   - F6 导出
   - 面板“录制视频”导出
4. 验证时间：用 `relativeTick + transition` 驱动动画，导出后逐帧检查有没有抖/漂/重复帧。
5. 验证 GL：在 overlay 里创建自己的 FBO + 简单 shader，画完恢复状态；分别在 Iris 开/关、Sodium 开/关下看有没有花屏/黑屏/状态污染。
6. 验证深度：从 `mc.getFramebuffer()` 的 depth attachment blit 到 `R32F`，做一个“深度可视化”测试卡；Iris 开/关都试。
7. 验证 UI：注册一个 Dashboard 标签页，里面显示一张由 FBO 渲染的预览纹理；确认输入/布局/缩放正常。
8. 验证 Clip：写一个最小 `CompositionClip`，存一个字符串 + 一个 `ValueInt`，在相机时间轴上加/存/读；移除 Addon 再打开影片，确认 Clip 数据还在（未知 Clip 保留）。
9. 验证分辨率：窗口 1080p 导出 1080p / 1440p / 4K，比较文字锐度，确认 1.6 的判断。
10. 写一份 P0 技术笔记：哪些通过、哪些不通过、哪些需要 Mixin/上游。

**P0 的退出标准（Go/No-Go）：**

- 测试卡能稳定进入 F6 导出和面板导出，坐标/时间正确。
- 自定义 FBO + shader 在 Iris 开/关下不破坏画面。
- 至少一种深度读取方式可用（否则深度效果降级到 P3）。
- Dashboard 标签页能正常显示 FBO 预览。
- 自定义 Clip 能序列化，Addon 移除后数据不丢。

**P0 的 Kill Criteria：**

- 覆盖层无法稳定进入导出（那就要改走“comp-only + 外部合并”，产品形态会变）。
- 自定义 GL 在 Iris 下无法恢复状态，且窄 Mixin 也救不了。
- BBS 的 UI 框架完全无法承载图层/时间轴（那就认真考虑桌面 App）。

### P1：可用的“BBS Motion”（8–12 周）

**定位：不是小 AE，是“BBS 创作者的动态 HUD/标题/角标工具”。**

范围：

- Comp：分辨率、fps、时长、透明/纯色背景、安全框。
- 图层：图片、纯色、文字、矩形/椭圆/多边形/星形、Null（父级）。
- 变换：锚点、位置、缩放、旋转、不透明度；父级链；6 种混合模式。
- 时间：入点/出点、简单时间缩放/冻结、图层 solo/lock。
- 关键帧：数值/二维/颜色；线性/贝塞尔/定格；Easy Ease 预设；值曲线图。
- 蒙版：多边形路径、羽化、add/subtract/intersect、蒙版不透明度。
- 效果：blur、glow、drop shadow、brightness/contrast/saturation/hue、gradient ramp、tint、vignette。
- 文字：TTF/OTF、字号、颜色、描边、阴影、字距/行距、对齐；暂不做逐字动画器。
- 形状：填充/描边、圆角；暂不做 trim/repeater。
- 影片集成：`CompositionClip` + 面板 + 导出；一个影片可叠多个 comp。
- 编辑器：图层栈、预览、检查器、时间轴、曲线图、资产导入、撤销/重做、保存/加载。
- 导出：BBS 默认录制（baked），1080p 目标；不做 Alpha/多输出。

**不做（明确砍掉，避免 P1 膨胀）：**

- 预合成/嵌套、调整层、轨道遮罩、图层样式、逐字动画器、表达式、时间重映射、3D 文字、粒子、深度、跟踪、渲染队列、Alpha、代理。

**P1 的退出标准：**

- 一个 30 秒的 BBS 镜头，包含标题、HUD、角标三类 comp，1080p60 导出，时间对齐，无闪烁。
- 同一个工程在另一台机器上打开（资产收集后）能正常渲染。
- Iris 开/关、Sodium 开/关都不崩、不花屏。
- 导出耗时不超过实时的 2 倍（1080p，10 层以内，效果不过分）。
- 一个没用过的人能在 10 分钟内做出“文字淡入 + 图片角标 + 蒙版圆形头像”。

### P2：合成器核心（12–16 周）

按价值排序，而不是按 AE 菜单排序：

1. **预合成/嵌套 + 调整层 + 轨道遮罩**：这是“从叠加层到合成器”的分水岭。
2. **图层样式**：drop shadow / outer glow / stroke / bevel；先用效果链实现，别做完整 AE 样式栈。
3. **形状运算器**：merge/subtract/intersect/exclude、trim path、round corners、repeater；用 stencil + 路径裁剪做。
4. **文字动画器**：range selector + 逐字 transform/opacity；wiggle selector 可以后置。
5. **表达式控制器**：接 BBS 的 Molang/MathBuilder，做 safe subset；不要引入 JS 引擎。
6. **时间重映射**：per-layer time offset/scale、freeze、reverse；完整关键帧 remap 后置。
7. **数据绑定**：replay/bone/anchor 投影、mask tracking、distance scale。
8. **深度**：depth matte、DOF、depth fog、depth-guided displacement；前提是 P0 证明深度可读。
9. **输出**：comp-only Alpha pass、输出模块预设、分离 passes（beauty / comp / matte）、渲染队列。
10. **代理/预渲染**：半分辨率预览、效果降级、缓存帧；不做网络渲染。
11. **标记/元数据**：从 BBS 相机 Clip 边界生成 comp markers；时间码显示。

**P2 的退出标准：**

- 一个 60 秒的片子，包含预合成、遮罩转场、跟踪标签、景深，能稳定导出。
- 能输出“baked MP4 + comp-only ProRes 4444 Alpha”两套文件。
- 输出模块可以保存/加载/设为默认；队列能连续跑多个 comp。
- 深度效果在 Iris 开/关下有明确的行为说明（不支持就禁用并提示，不要花屏）。

### P3：可选“Studio / 离线渲染”（8–12 周起）

只有在 P2 之后、且确实需要时才做：

- 把 `comp/` + `render/` 抽成不依赖 Minecraft 的模块。
- 桌面 App（Java/LWJGL + ImGui/NanoVG，或 WebGPU）读同一份 `.bbscomp`，做编辑/预览/离线渲染。
- BBS 侧增加“渲染包”导出：帧、深度、相机、锚点、可选 Object ID。
- 离线合成：超采样、运动模糊、EXR/ACES、渲染农场。
- 或者：向上游提一个 `FramePassesEvent`（blit 后、带 depth/ID），减少 Mixin。

**我的建议：P3 不要写进对用户的承诺。** 它是“如果 P1/P2 证明需求足够大”的扩展，不是项目成立的前提。

---

## 5. 功能取舍：AE 清单怎么砍

### 5.1 必做（P1）

| AE 能力 | 我们的做法 |
| --- | --- |
| 合成设置（分辨率/帧率/时长） | Comp 文档 + 顶栏 |
| 图层堆栈/入出点/不透明度/混合 | 核心 |
| 变换/锚点/父子/Null | 核心 |
| 关键帧/贝塞尔/定格/Easy Ease | 接 BBS `KeyframeChannel` + `Interpolations` |
| 图表编辑器（值曲线） | 接/适配 `UIKeyframeGraph`；速率图 P2 |
| 蒙版 + 羽化 | 多边形 + 高斯羽化；贝塞尔 P2 |
| 文字基础属性 | 接 BBS TTF + `TextRenderer` |
| 形状（矩形/椭圆/多边形/星形） | 基础填充/描边 |
| 混合模式 | 6 种 |
| 常用效果 | blur/glow/shadow/grade/gradient/tint/vignette |
| 和影片一起导出 | `CompositionClip` + `FrameOverlays` + BBS ffmpeg |

### 5.2 二期（P2）

预合成、调整层、轨道遮罩、图层样式、形状运算器（trim/repeater/round corners）、逐字动画器 + range selector、表达式控制器、时间重映射、replay/bone/anchor 绑定、mask tracking、深度 matte/DOF/fog/displacement、输出模块/Alpha/渲染队列、代理、标记/时间码、主属性（简化为“comp 参数暴露到 Clip”）。

### 5.3 三期/可选（P3）

2.5D 文字/形状挤出、2D 粒子、Shatter（需要深度/ID）、木偶/网格变形、LUT/色彩管理、VR 360/180、MOGRT 式模板、脚本/自动化、网络渲染。

### 5.4 建议砍掉

- **完整 3D 图层/相机/灯光/C4D 渲染器/Cineware**：BBS 就是 3D 世界，不要在合成器里再造一个。
- **3D Camera Tracker**：我们有 BBS 的相机矩阵，没必要从像素反推。
- **Roto Brush / 内容识别填充的 AI 版本**：没有模型、没有数据管线；用蒙版 + 深度 + 克隆替代。
- **239 个效果**：做 15–20 个高质量的就够；用 effect registry 让社区扩展。
- **VR/协作/网络渲染**：不是这个阶段的产品。
- **完整 JS 表达式**：接 BBS 的表达式语言，不做沙箱 JS。
- **完整 3D 渲染器**：2.5D 挤出足够标题使用。

### 5.5 用 BBS 方式替代

| AE 做法 | BBS 方式 |
| --- | --- |
| 3D 摄像机跟踪 | 绑定 BBS 相机/骨骼/锚点，直接投影 |
| 从像素反推深度 | 从 framebuffer 读深度（P2，先验证） |
| Object ID 分区 | 编辑器 stencil 拾取（不导出）；或上游 API；或深度分层近似 |
| 内容识别填充 | 深度引导的“从更远图层采样”+ 克隆 |
| C4D 文字挤出 | 多层偏移/SDF bevel 的 2.5D 挤出 |
| Master Properties | comp 参数 + Clip 上的 `ValueFloat` 暴露 + 预设 |
| 渲染队列 | BBS `FilmQueueExporter` + comp-only 队列 |
| 色彩管理 | sRGB + LUT/曲线，不上 OCIO |
| 3D 粒子 | BBS 世界粒子 + 2D 粒子只做 MG 点缀 |

---

## 6. 工程关联与导出链路

### 6.1 关联方式：Comp Clip，而不是 sidecar-only

**推荐：**

```
影片 .dat
└─ 相机时间轴
   └─ bbscomp:composition Clip
      ├─ comp: "hud/main"          // 指向 .bbscomp
      ├─ offset: 0                 // comp 内时间偏移（tick）
      ├─ timeScale: 1.0
      ├─ fit: contain              // contain/cover/stretch/none
      ├─ opacity: 1.0
      ├─ blend: normal
      └─ hold: true
```

为什么不是“旁边再放一个关联 JSON”：

- sidecar 和影片分离，移动/重命名影片就断。
- sidecar 不是 BBS 的数据，BBS 不知道它的存在；影片复制到另一台机器容易漏。
- Clip 是 BBS 自己序列化的数据，`ImageClip`/`VideoClip` 已经证明这个模式可行。
- Addon 移除时，未知 Clip 会被原样保留；重装后恢复。ADDONS.md 明确承诺了这一点。

**不推荐：**

- 直接改 `.dat`：格式私有、压缩、版本迁移复杂，收益为零。
- 把整个 comp 塞进 Clip：影片文件会巨大，comp 不能复用，编辑时加载慢。P3 可以加“Embed”选项，但默认外置。

### 6.2 `.bbscomp` 格式

建议 JSON（人可读、可 diff、可被未来桌面 App 读）：

```json
{
  "format": "bbscomp",
  "version": 1,
  "id": "hud/main",
  "name": "HUD Main",
  "width": 1920,
  "height": 1080,
  "fps": 60,
  "duration": 12.5,
  "background": { "mode": "transparent" },
  "layers": [
    {
      "id": "l_title",
      "type": "text",
      "name": "Title",
      "in": 0.0,
      "out": 4.0,
      "transform": {
        "anchor": [0.5, 0.5],
        "position": [960, 540],
        "scale": [100, 100],
        "rotation": 0
      },
      "opacity": 1.0,
      "blend": "normal",
      "parent": null,
      "text": {
        "content": "...",
        "font": "assets/fonts/title.ttf",
        "size": 96,
        "color": "#FFFFFFFF",
        "tracking": 0,
        "leading": 1.1
      },
      "masks": [],
      "effects": []
    }
  ],
  "tracks": {
    "l_title/transform.position": {
      "type": "vec2",
      "keys": [
        { "t": 0.0, "v": [960, 620], "interp": "bezier", "in": [0.2, 0.0], "out": [0.2, 1.0] }
      ]
    }
  },
  "markers": [],
  "bindings": []
}
```

原则：

- `format` + `version`，有迁移函数；未知字段保留（前向兼容）。
- 资产路径相对 comp 文件；`assets/`、`fonts/` 一起收集。
- 时间统一用秒（浮点），内部可以换 tick；导出时按目标 fps 采样。
- 轨道 key 用稳定 layer id，不用数组下标。
- 颜色统一 `#RRGGBBAA`，内部 premultiplied。

### 6.3 时间映射

```
compTime = ((clipContext.relativeTick + clipContext.transition) * timeScale + offsetTicks) / 20.0
```

- `relativeTick` 是相对 Clip 起点的 tick（`CameraClipContext.java:59`）。
- `offsetTicks` 是 Clip 上存的 comp 内偏移，单位 tick；`timeScale` 是变速。
- `transition` 是子 tick 插值；导出时 BBS 会按视频帧率推进。
- comp 自己的 fps 只影响 comp 内部采样和 comp-only 导出；baked 导出按 BBS 视频帧率采样。
- `hold`：comp 时间超出 comp 时长时，保持最后一帧；否则透明。
- 倒放/变速：P2 的时间重映射，不在 P1。

### 6.4 三种导出模式

| 模式 | 产物 | 用途 | 阶段 |
| --- | --- | --- | --- |
| **Baked** | 一个 MP4/MOV，BBS 画面 + comp 烧在一起 | 直接发布/审阅 | P1 |
| **Separate passes** | beauty（无 comp）+ comp Alpha | 进 Resolve/AE/PR 做后期 | P2 |
| **Comp-only** | 透明背景的 ProRes 4444 / PNG 序列 | 素材交付、模板复用 | P2 |

Baked 模式不需要额外编码：BBS 的 `VideoRecorder` 已经在录 `BBSRendering.getTexture()`，我们的 overlay 在 blit 之前画进去。

Separate/Comp-only 需要自己开 RGBA FBO、`glReadPixels`、喂 ffmpeg：

```
ffmpeg -f rawvideo -pix_fmt rgba -s 1920x1080 -r 60 -i - \
       -c:v prores_ks -profile:v 4 -pix_fmt yuva444p10le out.mov
```

### 6.5 输出模块与渲染队列

借 AE 的“渲染设置 / 输出模块”分离：

- **Render Settings**：分辨率、fps、时长、质量、代理、运动模糊、Alpha。
- **Output Module**：容器/编码、码率/CRF、像素格式、色彩标记、音频、文件名模板、渲染后动作。
- **Presets**：`Review H.264`、`Master ProRes 422 HQ`、`Comp Alpha ProRes 4444`、`PNG Sequence`。
- **Queue**：影片 + comp + pass + output module 的列表；串行执行；日志/失败重试/打开文件夹。
- **Post-render actions**：打开文件夹、显示文件、复制路径、运行命令（带 token，默认关闭）。

P1 只做 Baked + 一个默认输出；P2 再做队列/多输出。不要在第一阶段造一个通用转码器。

### 6.6 资产收集与迁移

- “Collect files”：把 comp 引用到的图片/字体/LUT 复制到 `assets/`，写相对路径，打包成 `.bbsae`（zip）或文件夹。
- “Relink”：comp 移动后，重新指向。
- “Missing asset”：占位图 + 明确提示，不静默丢层。
- “Version upgrade”：`version` + migration；保留未知字段。
- “Film without addon”：Clip 数据保留；打开时显示“此影片包含 bbscomp 合成，安装 Addon 以查看”，不崩。

### 6.7 失败与降级

- comp 文件缺失：Clip 仍存在，overlay 画一个红色占位框 + 文字，不崩。
- 效果 shader 编译失败：该效果跳过，图层其余部分正常，日志报错。
- 深度不可用：深度效果自动禁用并提示，不黑屏。
- GL 状态异常：捕获异常，跳过本帧合成，恢复状态，避免整机花屏。
- 性能不足：自动降级预览分辨率/效果质量；导出不降级（宁可慢）。

---

## 7. 风险与验证

### 7.1 风险表

| 风险 | 可能性 | 影响 | 验证/缓解 |
| --- | --- | --- | --- |
| Overlay 没进导出 / 顺序不对 | 低 | 致命 | P0 四个场景实测；源码链路已经很明确 |
| Iris/Sodium 下 GL 状态冲突 | 中 | 高 | P0 开关 shader 实测；`GlStateGuard`；窄 Mixin 兜底 |
| 深度读不到（尤其 Iris） | 中 | 中 | P0 depth blit 实测；失败则深度效果降级 P3 |
| `UIKeyframes` 和 Form/Track 耦合太深 | 中高 | 中 | P0 用一个假 sheet 试；不行就 fork/port 图编辑器（MIT） |
| 实时渲染性能不够 | 中 | 高 | P1 基准测试；半分辨率效果、FBO 池、缓存；导出慢可接受 |
| 文字逐字动画器难做 | 中 | 中 | P1 只做整段文字；P2 再啃字形布局 |
| BBS 更新破坏内部类 | 高 | 中 | 所有内部访问收进 `BbsCompat`；锁版本；CI 编译 |
| 工程/资产丢失 | 中 | 高 | 自动保存、备份、收集文件、缺失占位 |
| 范围膨胀 | 高 | 高 | 按 P1/P2/P3 砍；P1 明确不做列表 |
| 4K 文字软 | 高 | 中 | 文档说明；P2 评估 post-blit 高分辨率路径 |
| 许可/署名 | 低 | 低 | BBS 是 MIT；复制代码保留版权；Addon 也用 MIT |

### 7.2 最需要先验证的 6 件事

1. `FrameOverlays` 在 F6 导出和面板导出里都出现，且坐标/时间正确。
2. 自定义 FBO + shader 在 Iris 开/关下不污染 BBS 画面。
3. depth attachment 能不能 blit 出来（Iris 开/关）。
4. `UIKeyframes`/`UIKeyframeGraph` 能不能用自定义 `UIKeyframeSheet` 驱动。
5. 自定义 Clip 的序列化 + 未知 Clip 保留。
6. 1080p/4K、不同窗口大小下的锐度和性能。

---

## 8. 仓库与工程建议

### 8.1 结构

P1 单模块，包边界严格：

```
bbs-compositor/
├─ build.gradle
├─ gradle.properties          # 跟 BBS 的 MC/yarn/loader 完全一致
├─ src/main/java/<pkg>/
│  ├─ comp/                   # 文档、图层、轨道、求值
│  ├─ render/                 # FBO、shader、蒙版、效果、文字/形状
│  ├─ bbs/                    # Clip、Overlay、事件、BbsCompat
│  └─ export/                 # 输出模块、comp-only、ffmpeg
├─ src/client/java/<pkg>/ui/  # Dashboard、图层栈、检查器、时间轴、预览
└─ docs/
```

如果 P3 真的做桌面 App，再拆 `core/` + `render/`；不要在 P1 做多模块。

### 8.2 依赖与版本

- MC 1.20.4 / yarn `1.20.4+build.1` / loader `0.16.14` / Fabric API `0.91.3+1.20.4`，与 BBS 的 `gradle.properties` 完全一致。
- BBS 通过 `gradlew publishToMavenLocal` 发布，`modImplementation("mchorse:bbs:2.7-1.20.4") { transitive = false }`。
- `BBSApi.requireVersion("bbscomp", 2)` 在入口调用。
- 客户端 UI/渲染放在 `src/client/java`（像 BBS 一样开 `splitEnvironmentSourceSets()`），入口用 `bbs-client-addon`；不要在服务端加载这些类。模板默认只有一个 source set，需要照 BBS 的 `loom { splitEnvironmentSourceSets() }` 改。
- 锁 BBS 2.7.x；BBS 更新时先跑 P0 的 smoke test。
- 不要依赖 BBS 的 Sodium/Iris 传递依赖；dev client 里单独装。

### 8.3 测试

- 纯逻辑：comp 求值、时间映射、序列化 round-trip、关键帧插值（JUnit）。
- 集成：一个 `test-film` + `test-comp`，导出后用 `ffmpeg -f framemd5` 比对两次结果，确保确定性。
- 视觉：固定相机/固定 comp 的截图对比（允许小误差）。
- 兼容矩阵：Iris 开/关、Sodium 开/关、1080p/1440p/4K、16:9/21:9/竖屏。
- 泄漏：反复开关影片/编辑器，检查 FBO/纹理数量。
- 崩溃：缺资产、坏 JSON、shader 编译失败、深度不可用。

### 8.4 命名

- 项目：`bbs-compositor`（或 `BBS Motion` 作为 P1 产品名）。
- mod id：`bbscomp`；包名：`dev.<name>.bbscomp`。
- Clip id：`bbscomp:composition`。
- 文件：`.bbscomp`；打包：`.bbsae`。

---

## 9. 第一冲刺（10 天）Backlog

| 天 | 任务 | 产出 |
| --- | --- | --- |
| 1 | clone BBS、`publishToMavenLocal`、模板 Addon 跑起来 | dev client 能加载 Addon |
| 2 | 注册 `FrameOverlays`，画纯色/文字/图片测试卡 | 游戏内能看到 |
| 3 | 测试卡进 F6 导出/面板导出；检查坐标/时间 | 一段 5 秒测试视频 |
| 4 | 加 FBO + 简单 shader；Iris 开/关测试；写 `GlStateGuard` | 不花屏的测试卡 |
| 5 | depth blit + 可视化；记录 Iris 行为 | 深度截图/结论 |
| 6 | Dashboard 标签页 + FBO 预览 + 面板布局 | 能打开的合成器空壳 |
| 7 | 最小 `CompositionClip` + 序列化 + 未知 Clip 保留测试 | 影片里能看到 Clip 数据 |
| 8 | 分辨率/锐度测试（1080p/1440p/4K） | 结论 + 建议 |
| 9 | 性能基准（10 层/全屏 blur/4K） | ms/frame 表 |
| 10 | 写 P0 技术笔记 + Go/No-Go + 录 demo | 决策 |

**P0 demo 的样子：** 一个 BBS 影片镜头，画面里有 BBS 的角色/场景，上面叠一张合成测试卡（渐变、文字、图片、圆形蒙版、关键帧动画），从 BBS 导出 MP4，播放正常。

---

## 10. 需要你确认的 3 件事

1. **目标版本**：先做 MC 1.20.4（BBS master），还是必须兼容 1.20.1？后者会多一套构建/测试。
2. **投入形态**：一个人兼职、一个人全职，还是有小团队？这直接决定 P1 是 2 个月还是 6 个月。
3. **产品定位**：先做“BBS 创作者每天用的 HUD/标题工具”（我推荐），还是一开始就冲着“完整小 AE”？后者会把 P1 拖成 P3。

另外两个可选项，不影响 P0：

- 未来是否真的要做独立桌面 App？如果“一定要”，P1 的包边界要更严格，但也不必现在拆模块。
- UI 语言：中文/英文/双语？

---

## 附录：关键源码索引

| 主题 | 文件 |
| --- | --- |
| Addon 文档 | `ADDONS.md` |
| API 契约 | `api/bbs-api.txt`、`src/main/java/mchorse/bbs_mod/api/`、`src/client/java/mchorse/bbs_mod/api/` |
| 帧覆盖层 API | `src/client/java/mchorse/bbs_mod/api/client/events/RegisterFrameOverlaysEvent.java`、`src/client/java/mchorse/bbs_mod/ui/film/FrameOverlays.java` |
| 覆盖层绘制时机 | `src/client/java/mchorse/bbs_mod/client/BBSRendering.java:410-440`、`:442-492` |
| 录制/编码 | `src/client/java/mchorse/bbs_mod/utils/VideoRecorder.java:68-141`、`:320-374` |
| 世界导出 | `src/client/java/mchorse/bbs_mod/film/WorldVideoExportSession.java` |
| 面板导出 | `src/client/java/mchorse/bbs_mod/ui/film/PanelVideoExportSession.java`、`UIFilmPreview.java:213` |
| 影片文件 | `src/main/java/mchorse/bbs_mod/film/FilmManager.java:43-46` |
| 屏幕空间覆盖层先例 | `src/main/java/mchorse/bbs_mod/camera/clips/misc/ImageClip.java`、`SubtitleClip.java` |
| 1080 虚拟坐标系 | `src/main/java/mchorse/bbs_mod/camera/data/Placement.java:20` |
| 关键帧/插值 | `src/main/java/mchorse/bbs_mod/utils/keyframes/`、`src/main/java/mchorse/bbs_mod/utils/interps/Interpolations.java` |
| 曲线编辑器 | `src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/` |
| 字体 | `src/main/java/mchorse/bbs_mod/forms/forms/LabelForm.java`、`src/client/java/mchorse/bbs_mod/fonts/FontManager.java` |
| 表达式 | `src/main/java/mchorse/bbs_mod/math/molang/`、`src/main/java/mchorse/bbs_mod/camera/values/ValueExpression.java` |
| 图层 UI 先例 | `src/client/java/mchorse/bbs_mod/ui/dashboard/textures/layers/` |
| 渲染队列先例 | `src/client/java/mchorse/bbs_mod/ui/film/FilmQueueExporter.java` |
| 编辑器面板 | `src/client/java/mchorse/bbs_mod/ui/dashboard/panels/UIDashboardPanels.java` |
| 自定义 Clip | `src/main/java/mchorse/bbs_mod/utils/clips/Clip.java`、`src/main/java/mchorse/bbs_mod/camera/clips/CameraClip.java` |
| ID pass（编辑器） | `src/client/java/mchorse/bbs_mod/ui/utils/StencilFormFramebuffer.java`、`src/client/java/mchorse/bbs_mod/ui/framework/elements/utils/StencilMap.java` |

---

*这份文档是方案，不是验收清单。P0 的结论会改它；改的时候以源码和实测为准，不以这份文档为准。*
