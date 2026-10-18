# BBS Compositor 项目交接文档

> 给接手本项目的 AI / 开发者。  
> 项目目录：`C:\Users\Administrator\Desktop\bbsae`  
> 最后更新：2026-10-18 10:03（本地时间）

---

## 1. 项目是什么

**BBS Compositor (`bbsae`)** 是一个 **Minecraft 1.20.1 / Fabric** 的 **BBS FS 2.7** 客户端 addon。

它给 BBS 影片增加一个 2D/2.5D 合成器：

- 文字、形状、图片、纯色、HUD、Null、Adjustment、Precomp 图层；
- BBS 原生关键帧动画；
- 蒙版、轨道遮罩、混合模式、父子关系；
- 42 个效果与图层样式；
- BBS 相机 / Replay 实体绑定；
- BBS 影片预览与 **F6 视频导出**；
- 每个影片一个工程，每个 replay 一个独立 composition；
- 独立合成器 dashboard panel，带自己的效果预览。

产物：

- 根目录 `bbsae-0.1.0.jar`
- `build/libs/bbsae-0.1.0.jar`
- SHA-256：`C6499F896395934308B8A487488ABCD19A7FA3540C55063D8EE49839CF409AD4`
- 大小：`216872` 字节

---

## 2. 最重要的硬约束（不要重复之前的错误）

### 2.1 合成器必须是独立编辑器，**不能覆盖 BBS 影片编辑器**

上一版曾把 `UICompositorPanel` 作为 `UIFilmPanel` 的 full overlay 挂上去。结果：

- 遮住 BBS replay 列表、camera/replay 轨道、属性 dock；
- 合成器自己的预览被设成透明，实际显示的是 BBS 世界预览，不是合成 FBO；
- 两套时间/输入/保存生命周期混在一起；
- 用户明确说：**“你怎么直接把合成器界面覆盖到原版的影片编辑器界面了啊啊啊，这是不行啊。”**

当前正确结构：

```
BBS Film Editor (原版世界预览 + 原版影片轨道)
        │  CompositionClip / FrameOverlays
        ▼
BBS Compositor (独立 dashboard panel)
  Layers | Comp Preview (own FBO) | Inspector
          Timeline (BBS UIKeyframeEditor)
```

`BbsAeClient.openCompositorForFilm` / `openCompositorForClip` 必须是：

```java
dashboard.setPanel(compositorPanel);
UIScreen.open(dashboard);
```

**不要**再 `filmPanel.add(compositorPanel)` / `compositorPanel.full(filmPanel)` / 透明 overlay。

### 2.2 合成器必须有自己的效果预览

预览是 `CompRenderer` 渲染到自己的 FBO，不是：

- BBS 世界预览；
- 影片编辑器的 frame overlay；
- 一张静态截图。

当前预览路径：

```
CompDocument
 → CompRenderer.render(...)
   → 每层 FBO
   → mask / matte / effects / styles / blend
   → adjustment
 → composition FBO
 → batched textured quad 显示在 preview 区域
```

预览和导出共享 `CompRenderer`，但预览可以关闭 FX/Mask 做对照，导出始终完整。

### 2.3 轨道直接复用 BBS

轨道区必须继续使用：

- `UIKeyframeEditor`
- `UIKeyframeDopeSheet`
- `UIKeyframeSheet`
- BBS 关键帧菱形 / 插值 / 右键菜单 / 复制粘贴

本项目的 `UICompositorKeyframes` 只负责把 comp 的 sheet 喂给 BBS 编辑器。

自绘部分只有：

- 顶部 scrubber / 秒刻度 / 播放头；
- 预览视口；
- 图层选择框。

**不要**自己实现一套关键帧菱形或轨道控件。

### 2.4 不要用 HTML / MCEF

已分析过：

- BBS 预览是 OpenGL framebuffer，不是 DOM；
- MCEF 需要额外 native Chromium、输入桥、透明合成；
- 会绕开 BBS 的 keyframe/undo/F6 导出路径；
- 体积、兼容性、Iris/Sodium 组合风险不可控。

结论：**运行时 UI 全部用 BBS 原生 Java 控件**。HTML 只能做外部原型。

### 2.5 世界预览不要靠“透明 overlay”实现

如果以后用户还要“BBS 世界作为底图”：

- 正确做法是 **capture clean scene color+depth**（Photon `PostFXPreview` 的思路），把 capture 作为合成器预览的背景；
- **不是**把 comp panel 覆盖到 film panel 上；
- 也不是把 comp preview 设成透明。

当前版本**没有**实现 world-backed compositor preview；合成器预览是 comp-only。

---

## 3. 当前代码地图

### 3.1 入口

`src/main/java/dev/bbsae/BbsAeAddon.java`

- 注册 BBS addon；
- 日志。

`src/main/java/dev/bbsae/client/BbsAeClient.java`

- `BBSClientReadyEvent`：`CompStore.ensureDemo()`、tick、清理缓存；
- `RegisterDashboardPanelsEvent`：创建/注册 `UICompositorPanel`；
- `RegisterReplayActionsEvent`：Replay 属性里的 “Open Film Compositor”；
- `RegisterClipPanelsEvent`：`UICompositionClip`；
- `RegisterFrameOverlaysEvent`：`CompositionOverlay`；
- `openCompositorForFilm` / `openCompositorForClip`：**独立 dashboard panel**；
- `openCompositor()`：无影片时打开空面板。

### 3.2 数据模型

`src/main/java/dev/bbsae/comp/`

- `CompProject`：多 composition、输出 preset、master parameters；
- `CompDocument`：width/height/fps/duration/background/layers/markers；
- `CompLayer`：最大类，图层类型、变换、时间、蒙版、效果、样式、文字、形状、父子、绑定；
- `CompMask`：点/贝塞尔手柄/羽化/扩展/反转/模式；
- `CompEffect` / `CompEffectRegistry`：42 个效果；
- `CompStyle`：图层样式；
- `CompTextAnimator`：逐字 animator；
- `CompShape`：形状/trim/repeater；
- `CompBinding`：BBS 相机/Replay/图层绑定；
- `CompExpression`：安全表达式子集；
- `CompTime`：timecode；
- `CompositionClip`：BBS camera clip，把 comp 塞进 `ClipContext.clipData`；
- `CompStore`：`.bbscomp` JSON 保存/加载/迁移/`.bak`/demo；
- `CompRenderJob` / `CompOutputPreset`：渲染队列。

### 3.3 渲染

`src/main/java/dev/bbsae/client/render/`

- `CompRenderer`：主渲染图；
- `LayerContentRenderer`：solid/text/image/shape/precomp 内容；
- `MaskCache`：CPU 栅格化蒙版 + GL 上传 + 缓存；
- `PassRenderer`：blend/mask/unpremultiply；
- `EffectRenderer`：效果 pass；
- `StyleRenderer`：图层样式；
- `ShapeCache` / `ShapeRasterizer`：形状光栅化；
- `ImageTextureCache`：图片纹理；
- `SceneDepth`：世界 depth 捕获；
- `FboPool` / `GlFramebuffer` / `GlTexture` / `GlStateGuard` / `GlslProgram` / `ShaderSources`；
- `BindingResolver`：相机/Replay/图层绑定投影。

`CompRenderer` 的关键顺序：

```
for layer:
  if adjustment: apply adjustment effect chain to accumulation
  else:
    layer FBO
    → mask
    → track matte
    → effects
    → styles
    → blend into accumulation
```

预合成递归、`stack` 防循环、`GlStateGuard`、`BufferRenderer.resetCurrentVertexBuffer()` 都在。

### 3.4 UI

`src/main/java/dev/bbsae/client/ui/`

- `UICompositorPanel`：约 111 KB，主编辑器；
- `UICompositorScrubber`：大目标拖动/秒刻度/时间码/播放头；
- `UICompositorKeyframes`：BBS `UIKeyframes` 子类，提供 comp 的 tick/playhead；
- `UICompLayerList`：BBS `UIList<CompLayer>`；
- `UICompRenderJobList`：渲染队列列表；
- `UICompositionClip`：BBS clip inspector。

`UICompositorPanel` 当前布局：

```
header: 24px
body: y24, h(1F,-266)
  layers: 190px
  preview: 剩余
  inspectorColumn: 300px
    inspectorTabs: 22px (Comp/Layer/FX/Mask/Text/Out)
    inspector: y22, h(1F,-22)
timeline: y(1F,-242), h224
status: y(1F,-18), h16
```

预览：

- `CompRenderer.render(... preview=true, captureDepth=false, previewEffects, previewMasks, ...)`
- 选中层有边框/四角 handle；
- 按住左键拖动选中层；
- 有 transform 关键帧 → 在播放头写 transform key；否则改静态 x/y；
- 预览左上角 `FX` / `Mask` 开关；
- 左下角 `1920x1080 20fps 1 FX`；
- 空状态 `No keyframes` + 提示。

### 3.5 覆盖层

`src/main/java/dev/bbsae/client/overlay/CompositionOverlay.java`

这是 **BBS 影片预览/F6 导出** 的 frame overlay：

- 从 `ClipContext.clipData` 读取 `CompInstance`；
- 调 `CompRenderer.render(...)` 画到 BBS 最终画面；
- 与合成器独立 panel 不是同一层 UI。

---

## 4. 当前功能状态

### 已实现

- 影片/Replay 作用域工程：
  - 一个 film 一个 `CompProject`；
  - 每个 replay 一个 composition，id 形如 `replay_<id>`；
  - `CompositionClip` 自动补；
  - `UICompositionClip` 可改 project/comp；
  - Replay 选择器 overlay。
- 多 composition、marker、master parameters、output preset。
- 图层：solid/panel/rect/bar/text/image/footage/shape/null/adjustment/precomp。
- 时间：in/out、offset、time scale、freeze、time remap、markers。
- 变换/父子/13 blend modes/opacity/solo/guide/track matte。
- BBS `KeyframeChannel<Transform>` / float，效果参数、文字、蒙版、形状参数可动画。
- 蒙版：add/subtract/intersect/exclude、feather、opacity、expansion、invert、贝塞尔手柄、逐点动画。
- 42 效果 + 9 图层样式。
- 文字字号/颜色/描边/字距/行距/对齐/换行/逐字 animator。
- 形状矩形/椭圆/多边形/星形/贝塞尔路径/布尔/trim/repeater/圆角/填充描边。
- 表达式安全子集。
- 动画预设。
- BBS 相机/Replay/图层绑定。
- 深度 effect（预览默认不捕获 depth，导出/影片 overlay 才捕获）。
- 渲染队列：PNG 序列 / H.264 / ProRes 422 HQ / ProRes 4444 Alpha。
- BBS 原生 UI 控件。
- 独立合成器 panel（不是 film overlay）。
- 预览自己的 FBO；FX/Mask 对照开关。

### 部分实现 / 明确限制

- 2.5D 文字/形状挤出：未实现。
- 完整 3D 图层/摄像机/灯光/C4D：不在范围；BBS 提供 3D 世界。
- 3D Camera Tracker：不需要；用 BBS 相机/Replay 投影。
- 木偶/网格变形：未实现。
- Roto Brush / AI 内容填充：未实现。
- 逐字 3D、文字路径、从文字创建形状：未实现。
- 笔画/克隆/橡皮擦绘画：未实现。
- 完整时间重映射曲线：支持可关键帧 source time remap，但没有逐帧曲线 UI。
- 形状扭转/摆动/位移路径：trim/repeater/roundness/布尔/路径点已实现，其余未实现。
- MOGRT / 外部模板：未实现；只有属性动画预设。
- 代理/预渲染缓存：未实现；预览按合成分辨率全量渲染。
- 网络渲染/协作/VR：未实现。
- OCIO/线性工作流/LUT：未实现；当前 sRGB + straight alpha。
- 轨道遮罩跟踪点：绑定整层/图层锚点，不是逐遮罩点。
- 图层标记：模型和检查器已实现；时间轴图形标记仍由 BBS 原生组件负责。
- 全局 undo/redo：没有跨图层栈；依赖 20 秒自动保存和 `.bak`。
- 颜色管理/元数据：仅 comp 标记和基础设置。

---

## 5. 关键 bug 与修复历史（必须保留）

### 5.1 预览黑屏 / 红屏

- BBS screen model-view 被带进 FBO → 合成被推出画布。
  - 修复：`CompRenderer` 在渲染 FBO 前 push identity model-view，finally 恢复。
- `UIColor` 初始化会写默认颜色到 `comp.background`。
  - 修复：`UICompositorPanel.buildingInspector` guard，`colorRow` 不在构建期写回。
- 蒙版平移用了 JOML 的错误字段。
  - JOML 是列主序：2D 平移是 `m30/m31`，不是 `m03/m13`。
  - 修复：`MaskCache`、`BindingResolver` 全部改 `m30/m31`。
- 蒙版 hash 也改 `m00/m01/m10/m11/m30/m31`，避免 cache 串层。

### 5.2 蒙版预览原生崩溃

曾出现：

```
nvoglv64.dll EXCEPTION_ACCESS_VIOLATION
MaskCache.rasterize
 → GlTexture.uploadTopDown
 → glTexSubImage2D
```

根因：BBS 图片上传可能留下非零 `GL_UNPACK_ROW_LENGTH`，蒙版上传是 tightly packed，驱动越界读取。

修复：`GlTexture.upload()` 在上传前强制：

```java
GL_UNPACK_ALIGNMENT = 1;
GL_UNPACK_ROW_LENGTH = 0;
GL_UNPACK_SKIP_PIXELS = 0;
GL_UNPACK_SKIP_ROWS = 0;
```

修复后一次开发客户端会话能正常显示蒙版预览，不再原生崩溃。

### 5.3 时间轴不可用

- BBS `UIKeyframeEditor` 原生的 ruler/playhead 在某些布局下不可见；
- 增加 `UICompositorScrubber`（大目标拖动、秒刻度、时间码、播放头）；
- `UICompositorKeyframes.renderOverlay` 显式调用 `TimelineRulerRenderer.render(...)` + `UITimelineCanvas.renderCursor(...)`；
- `Fit` 重置视图，`- / +` 缩放；
- `setFrame` 暂停播放。

### 5.4 导出

- `exportFilm()` 会：
  1. `save()`；
  2. 切到 `filmPanel`；
  3. `preview.recordVideo.clickItself()`；
- 之前真实生成过 `320x180@20fps`、39 帧 H.264 MP4，抽帧确认合成层进入视频。
- **最新代码改动后没有重新跑一次完整视频导出**，接手后必须复验。

### 5.5 BBS 面板切换

调试 standalone 时出现过一次：点击时间轴后界面回到 BBS 影片面板。没有稳定复现。

接手后重点检查：

- 是否误点到 dashboard 的影片 tab；
- `UIDashboard.setPanel` 是否被其它逻辑调用；
- `UICompositorPanel` 是否在某个 `onDisappear` / save 回调后被关闭；
- 点击坐标是否落在 panel 外。

---

## 6. 构建、测试、打包

### 6.1 环境

- Minecraft 1.20.1
- Fabric Loader 0.16.14
- Fabric API 0.92.1+1.20.1
- BBS FS 2.7-1.20.1
- Java 17 target
- Gradle 9.2.0
- 构建 JDK：21（`_tools/jdk21/jdk21.0.12_12`）
- BBS jar：`libs/bbs-2.7-1.20.1.jar`

### 6.2 编译/测试

```powershell
$root='C:\Users\Administrator\Desktop\bbsae'
$env:JAVA_HOME="$root\_tools\jdk21\jdk21.0.12_12"
$env:PATH="$env:JAVA_HOME\bin;"+$env:PATH
$env:GRADLE_USER_HOME="$root\.gradle-home"
$env:TEMP="$root\.tmp"
$env:TMP="$root\.tmp"
$env:GRADLE_OPTS="-Djava.io.tmpdir=$root\.tmp"

& 'C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.2.0-bin\11i5gvueggl8a5cioxuftxrik\gradle-9.2.0\bin\gradle.bat' test --offline --console=plain
```

测试：**16 个全过**（14 headless + 2 隐藏窗口 OpenGL）。

### 6.3 为什么不能直接 `gradle build`

本机 Windows 执行沙箱不允许 TinyRemapper 直接写 zip：

```
java.io.IOException: the jar file ... can't be written
```

因此当前流程：

1. `gradle classes` / `compileJava`；
2. 用 `_tools/RemapToDir.java` 把 `build/classes/java/main` 重映射到 `build/remap-finalX`；
3. PowerShell `System.IO.Compression.ZipArchive` 重新打包 class + `build/resources/main`。

`RemapToDir` 就在 `_tools/RemapToDir.java`，编译到 `build/remap-tool`。

### 6.4 重映射命令（当前机器）

```powershell
$root='C:\Users\Administrator\Desktop\bbsae'
$tool="$root\build\remap-tool"
$in="$root\build\classes\java\main"
$out="$root\build\remap-final6"
$map="$root\.gradle\loom-cache\source_mappings\952b7c5e6f9423615c21cdbec32403531665d1aa.tiny"
$mc="$root\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-952b7c5e6f\1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2\minecraft-merged-952b7c5e6f-1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2.jar"
$bbs="$root\libs\bbs-2.7-1.20.1.jar"
# tiny-remapper + asm + mapping-io 从 .gradle-home caches 取，见上次命令
java -cp "$tool;<tiny-remapper>;<asm jars>;<mapping-io>" RemapToDir $in $out $map $mc $bbs
```

然后用 PowerShell 将 `$out` + `$root\build\resources\main` 打成 jar，复制到：

- `bbsae-0.1.0.jar`
- `build/libs/bbsae-0.1.0.jar`
- `build/libs/bbsae-0.1.0-remapped.jar`

普通开发机应优先尝试 `gradlew.bat build`。

### 6.5 开发客户端视觉验证

之前用临时 debug 入口 + `clientPreview` run config：

- `-Dbbsae.debugPreview=true`；
- 60 tick 后创建 `preview_test` film，复制 demo comp，打开 compositor；
- 用 PowerShell 截窗口。

**这些 debug 代码/run config 已从当前源码移除。**  
接手后要复验时，临时加回，验证完删除，不要进入最终 jar。

截图：

- `docs/ui-preview-standalone3.png`：独立合成器，FX/Mask 开关、选择框、时间轴。
- `docs/ui-preview-frame2.png`：BBS 影片编辑器在 1.5s 的画面（世界预览 + comp overlay；用于证明影片链路，不是合成器 UI）。
- `docs/export-frame3.png`：旧版真实导出 MP4 抽帧。

注意：demo comp 的标题/副标题 opacity 从 0 开始淡入，**在 frame 0 预览看不到文字是正常的**。要看到标题，把时间拖到约 1.5s。

---

## 7. 参考项目与研究结论

### 7.1 Photon

路径：`_research/Photon`

关键类：

- `gui/editor/FXEditor.java`
- `gui/editor/view/scene/SceneView.java`
- `gui/editor/view/FXTimelineView.java`
- `client/postfx/runtime/PostEffectStack.java`
- `client/postfx/runtime/PostFXPreview.java`
- `client/postprocessing/PhotonPostProcessing.java`
- `client/postfx/runtime/RenderGraphRuntime.java`

结论：

- Photon 是 **NeoForge 1.21.1 + LDLib2**，不能直接链接到 Fabric 1.20.1/BBS；
- 许可证 CC BY-NC-SA 4.0，限制移植/再分发；
- UI：可停靠的 hierarchy / SceneView / timeline；SceneView 有 draw mode、bloom/effects/lights 开关；
- 渲染：color+depth → PostEffectStack（per-frame requests + weight 混合）→ RenderGraph → HDR bloom mips → final；
- 编辑器用独立 `EDITOR_SCENE` 后处理栈 + clean scene capture，避免污染世界；
- 本项目只借鉴结构，不复制代码。

详见 `docs/photon-research.zh.md`。

### 7.2 EffectCraft

`_research/effectcraft`（纯 Rust/egui，不能直接复用）。

借鉴：

- Project / Composition / Layer 层级；
- 左属性树 + 右时间标尺/CTI；
- Effect Controls；
- Render Queue；
- 独立编辑器工作流。

### 7.3 BBS 参考项目

- `_research/busyflow-bbs-pov`：分栏编辑、原生 keyframe 空状态/undo、POV section；本项目借鉴 inspector tabs / timeline empty state。
- `_research/qualett-bbs-refreshed`：BBS UI 主题/mixin；没有引入全局主题。
- `_research/xavineditor-BBSVFX`：效果模块化/开关/预设；借鉴效果注册表。
- `_research/Wemppy4-bbs-fs`：BBS 1.20.1 API 行为基线。
- `docs/ui-architecture.zh.md`：HTML/MCEF 评估 + 参考项目矩阵。
- `docs/photon-research.zh.md`：Photon 深入研究。

---

## 8. 还没做的部分（按优先级）

### P0：接手后先验证 / 修稳定性

1. **重新跑一次真实视频导出**  
   最新 `CompRenderer` 增加了 previewEffects/previewMasks 参数，需确认 `CompositionOverlay` / `exportFilm` 仍走完整图。
2. **复验独立合成器 preview 不崩溃**  
   特别是 mask + image + precomp + adjustment 组合；蒙版 GL row-length 修复需要回归测试。
3. **修 standalone 面板偶发切回 film panel**  
   没稳定复现，但必须查清；不能在用户点 timeline 时把合成器关掉。
4. **清理 `info` 死字段**  
   `UICompositorPanel.info` 仍构造、被赋值，但没有加入 header；可以删掉或移到 status。
5. **确认 UI 布局在 1280x720 / GUI scale 2/3 都可用**  
   当前 header 宽度在旧版本曾溢出；现在 Focus 已移到 timeline header，但仍需实机检查。

### P1：用户明确要的“世界底图”正确方案

6. **clean scene capture 到合成器预览**  
   参考 Photon `PostFXPreview`：
   - 在 BBS 世界渲染后捕获 color+depth；
   - 只在 compositor preview 可见时 capture；
   - 作为 comp preview 的背景；
   - 不是 overlay 影片编辑器。
   - 需要处理 BBS framebuffer 绑定、viewport、Iris/Sodium 兼容。
7. **世界底图开关**  
   `Preview: Transparent / World / Black`，默认 Black；世界 capture 失败时回退 Black。

### P2：Photon 级渲染管线

8. **合成级 Post FX 栈**  
   当前 effects 是 per-layer；需要 `CompDocument.postEffects`，在 accumulation 之后执行。
9. **HDR + Bloom**  
   LDR FBO → HDR target；亮部提取、downsample mip、upsample、combine；参考 `PhotonPostProcessing`，但不能直接拷代码。
10. **Effect Graph / Render Graph**  
    当前 42 个固定 `CompEffect`；下一步是可连接 pass graph，或至少 per-effect pass 参数/条件。
11. **预览隔离选项**  
    已有 FX/Mask 开关；继续把 preview 与 export 的 render options 完全分离（代理分辨率、depth、bloom）。
12. **颜色管理**  
    sRGB → linear/OCIO/LUT；当前 straight alpha + sRGB。

### P3：合成器 UI / 工作流

13. **时间轴 layer duration bar**  
    当前只有属性 sheet；需要 comp 图层 in/out bar、marker、work area、post FX track。
14. **复用更多 BBS 原始轨道**  
    目前复用 `UIKeyframeEditor`；如果用户指的是 BBS camera/replay clip timeline，需要把 comp clip 和原生轨道结合，不能再 overlay。
15. **预览缩放/平移**  
    滚轮 zoom、middle-drag pan、Fit、100%、棋盘格 alpha。
16. **变换 gizmo**  
    目前只支持平移；需要 scale/rotate handle、anchor、snapping。
17. **图层搜索/过滤/分组**  
    当前 `UICompLayerList` 只是列表；需要按类型/名称过滤、折叠组。
18. **资产库 / 拖放导入**  
    当前 image/footage 有 Import 按钮；缺少媒体库、拖放、缩略图、替换素材。
19. **全局 undo/redo**  
    没有跨图层 undo；只有自动保存 + `.bak`。
20. **面板布局保存/恢复**  
    当前固定 header/body/timeline；没有 dockable / 用户布局持久化。

### P4：Replay / 工程管理

21. **多 replay composition clip 启用策略**  
    当前每个 replay 都可能自动补 `bbsae:composition` clip；同一时间多个 clip 会叠加。需要：
    - 只启用当前 replay 的 comp clip；
    - 或按 priority 选择；
    - UI 显示“当前 replay 的 comp / 其它 replay comp”。
22. **更完整的回放选择系统**  
    当前是 `UIListOverlayPanel` 列出 replays + saved films；下一步需要：
    - 搜索；
    - 当前项高亮；
    - 按 film 分组；
    - 新建/复制/删除 replay comp；
    - 每个 comp 记录所属 replay。
23. **工程/影片切换的生命周期**  
    切 film 时保存旧工程、加载新工程、清理 FBO/cache。

### P5：性能与效果

24. **4K 性能**  
    预览全分辨率；重效果/蒙版/形状 CPU 栅格化慢。需要半分辨率代理、layer-local mask texture、mask cache 上限。
25. **效果覆盖**  
    42 个效果是精选，不是完整 AE 306；缺少部分 distortion/transition/3D/particle 效果。
26. **深度 effect 降级**  
    Iris/framebuffer 配置不同，depth capture 可能失败；需要显式状态提示。
27. **GPU 蒙版**  
    当前 CPU `BufferedImage` 栅格化；可改 stencil/GPU mask，减少 4K 卡顿。

### P6：导出/交付

28. **真实视频导出的最终复验**  
    最新构建后重新生成 MP4，检查 comp/effect/mask 进入视频。
29. **jar 的正式构建**  
    在正常开发机执行 `gradlew build`；当前沙箱只能手动 remap/package。
30. **文档同步**  
    `README.md` 的 hash 已是 `C649...`；改动源码后必须更新 hash/features/status。

---

## 9. 当前最终产物

- `/bbsae-0.1.0.jar`
- SHA-256 `C6499F896395934308B8A487488ABCD19A7FA3540C55063D8EE49839CF409AD4`
- 大小 `216872`
- 构建时间 2026-10-18 10:20:37

当前源码最后一次编译：

- 源码：2026-10-18 10:18 左右
- class：2026-10-18 10:19 左右
- `test` 通过；`jar` 2026-10-18 10:20:37。

**如果接手后改源码，记得重新编译、重映射、重新打包，并更新 README hash。**

---

## 10. 一句话交接

> 合成器是一个**独立的 BBS dashboard panel**，拥有自己的 `CompRenderer` FBO 预览和独立时间线；BBS 影片编辑器保留自己的世界预览和原生轨道。不要再把合成器 overlay 到影片编辑器上；如果需要世界背景，用 clean-scene capture，而不是透明覆盖。下一步优先做合成级 Post FX / HDR Bloom、world-backed preview、多 replay comp 启用策略和导出回归验证。
