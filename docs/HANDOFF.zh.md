# BBS Compositor 项目交接文档

> 给接手本项目的 AI / 开发者。  
> 项目目录：`C:\Users\Administrator\Desktop\bbsae`（当前工作副本：`bbsae - 副本 (3) - 副本 - 副本`）  
> 最后更新：2026-10-18 20:30（本地时间）
>
> **先读这个**：
> - 第四轮（效果空间 V 轴 / 摄像机基础 / BBS 预览）：第 14 节 + [`docs/ROUND4-2026-10-18.zh.md`](ROUND4-2026-10-18.zh.md)
> - 第五轮（混合模式 / 效果 alpha / 摄像机交互 / 预览折叠 / 添加图层）：第 15 节 + [`docs/ROUND5-2026-10-18.zh.md`](ROUND5-2026-10-18.zh.md)
> - 第六轮（扭曲 / 调色 / 引导页 / 素材音效 / 快捷键 / 1080p60 导出）：第 16 节 + [`docs/ROUND6-2026-10-18.zh.md`](ROUND6-2026-10-18.zh.md)
> - 第七轮（交互手柄 / 3D / 调整层 / 预合成）：第 17 节 + [`docs/ROUND7-2026-10-18.zh.md`](ROUND7-2026-10-18.zh.md)
> - 第八轮（Alight Motion PIXEL 效果 / 第一性原理审计）：第 18 节 + [`docs/ROUND8-01-AUDIT.zh.md`](ROUND8-01-AUDIT.zh.md)、[`docs/ROUND8-02-PIXEL.zh.md`](ROUND8-02-PIXEL.zh.md)、[`docs/ROUND8-03-VERIFY.zh.md`](ROUND8-03-VERIFY.zh.md)
> - 第九轮（导出画面同步 / 12 秒时长 / 合成器图标）：第 19 节 + [`docs/ROUND9-EXPORT-SYNC.zh.md`](ROUND9-EXPORT-SYNC.zh.md)

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
- SHA-256：`2856C0590099208A1D4BF4336922320945DF9FC92CA4EC21BE1C4CEAA1867E4F`
- 大小：`402706` 字节

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

### 2.3 轨道分两层：自绘 layer bar + BBS 关键帧

合成器时间轴有两层，职责不同：

1. **layer 轨道层**：`UICompTrackTimeline` 自绘。它只负责“一层一条”的 bar、轨道头（可见/锁定）、
   in/out 移动/裁剪、吸附、缩放/平移、播放头、工作区。这里没有关键帧菱形。
2. **属性关键帧层**：仍是 BBS 的 `UIKeyframeEditor` / `UIKeyframeDopeSheet` / `UIKeyframeSheet`，
   放在轨道下方的可选抽屉里。菱形、插值、图表、右键菜单、复制粘贴全部由 BBS 实现。

`UICompositorKeyframes` 只负责把 comp 的 sheet 和播放头喂给 BBS 编辑器。

**不要**自己实现关键帧菱形、图表编辑器或插值菜单；也**不要**把 layer bar 塞进 BBS 的关键帧
sheet 里（那是属性行，不是轨道）。

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

- `UICompositorPanel`：约 164 KB，主编辑器（header / 效果库 / 预览 / Effect Controls / 轨道时间轴）；
- `UICompTrackTimeline`：真正的轨道编辑器（轨道头、bar 移动/裁剪、吸附、缩放/平移、右键菜单）；
- `CompTimelineRuler`：合成自己的秒刻度尺（BBS 刻度尺是 20tick/s，不能直接用）；
- `UIEffectLibrary`：左侧效果库（搜索/分类树/最近使用/双击应用）；
- `UICompositorKeyframes`：BBS `UIKeyframes` 子类，提供 comp 的 tick/playhead；
- `UICompRenderJobList`：渲染队列列表；
- `UICompositionClip`：BBS clip inspector。

`UICompositorPanel` 当前布局：

```
header: 24px
body: y24, h(1F,-292)
  effectColumn: 190px   [效果库 | 素材]
  preview: 剩余
  inspectorColumn: 300px
    inspectorTabs: 22px (效果控件 / 图层 / 蒙版 / 文字 / 合成 / 输出)
    inspector: y22, h(1F,-22)
timeline: y(1F,-268), h250
  timelineHeader: 26px  [+ 拆分 标记 吸附 关键帧 - + 适配 …… 快捷键]
  trackTimeline: y26, h(1F,-26)  ← 真正的轨道编辑器
  keyframeDrawer: 132px，可选；打开时 trackTimeline 高 h(1F,-158)
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
    `README.md` 的 hash 已是 `B92...`；改动源码后必须更新 hash/features/status。

---

## 9. 当前最终产物

- `/bbsae-0.1.0.jar`
- SHA-256 `2856C0590099208A1D4BF4336922320945DF9FC92CA4EC21BE1C4CEAA1867E4F`
- 大小 `402706`
- 构建时间 2026-10-18 21:53
- 构建方式：`gradle build --offline`（`remapJar` 正常写 zip；本机已不再需要手工重映射）

当前源码最后一次验证：

- `test` **63/63** 通过；`jar` 由 `remapJar` 重新打包；
- 第八轮新增 `EffectRenderer.PixelPlan`、7 个 PIXEL 效果、`PixelPlanTest`、
  `ShaderPixelTest` 12 个真实 GL 像素断言；
- 第九轮新增 20 秒导出、`BBS 预览` 图层导出、`IconResourceTest`；
- 证据：`docs/verify/round8-pixel-effects.png`、`round9-export-20s-15s.png`、
  `round9-bbs-preview-layer.png`、`round9-compositor-icon.png`。

**如果接手后改源码，记得重新编译、重映射、重新打包，并更新 README hash。**

---

## 10. 一句话交接

> 合成器是一个**独立的 BBS dashboard panel**，拥有自己的 `CompRenderer` FBO 预览和独立时间线；BBS 影片编辑器保留自己的世界预览和原生轨道。不要再把合成器 overlay 到影片编辑器上；如果需要世界背景，用 clean-scene capture，而不是透明覆盖。下一步优先做合成级 Post FX / HDR Bloom、world-backed preview、多 replay comp 启用策略和导出回归验证。

---

## 11. 2026-10-18 P0 回归验证记录

详见 `docs/verify-2026-10-18.zh.md`，证据在 `docs/verify/`。

结论：

- P0-1 导出 PASS：320x180@20fps、164 帧、8.2s，合成层已烘进视频（两轮，含 image/precomp 版）。
- P0-2 预览 PASS：10 次真实客户端会话、合成器累计约 12 分钟持续渲染，mask+image+precomp+adjustment 组合无原生崩溃。
- P0-5 布局 PASS @960x540 UI（1920x1080 + GUI scale 2）：panel 0,0 960x520 / timeline 0,278 960x224 / preview 196,24 458x254。
- P0-3 未复现：全工程只有 `exportFilm()` 一处 `setPanel(filmPanel)`。
- P0-4 确认：`UICompositorPanel.info` 从未加入布局，是死字段。

两条新增注意：

1. **构建/测试必须在纯 ASCII 路径**。含中文的路径会让 Gradle 测试 worker 的 classpath argfile 失效，
   报 `Could not execute test class`；ASCII junction 也绕不过（Gradle 会解析回真实路径）。
2. 面板会在 `bind()` / `onDisappear()` 自动 `save()`；客户端运行时改 `.bbscomp` JSON 会被覆盖。
   另外复制既有图层做夹具时，`transformData` 里的关键帧会让扁平 `x/y` 失效。


---

## 12. 2026-10-18 中文版 / 交互修复

本轮针对用户反馈的界面问题做了集中修复，产物已重新打包（见第 9 节新哈希）。

### 12.1 修复的 bug

1. **右侧检查器标签切不了**
   原因：`UITabStrip` 继承 `UIScrollView`，作为 dashboard panel 的子元素时点击分发被滚动视图吞掉。
   修复：新增 `UICompTabBar`（`UIElement` + 每个 `UITextTab` 自己处理 `subMouseClicked`），
   保留同样的文字标签 + 高亮下划线的观感，点击 100% 生效。
2. **时间轴刚打开是空白，操作后才出现**
   原因：`bind()` 在面板还没布局（0x0）时就往 `UIKeyframeEditor` 里塞了 sheet，
   dope sheet 的缓存以 0 尺寸建立，之后没有任何东西触发重建。
   修复：`primeTimeline()` 在第一帧拿到真实尺寸时重新 `refreshSheets()` / `resize()` / `resetView()`；
   并在每次 `bind()` 后把 `primedWidth` 重置，保证切换回放/影片也会重建。
3. **空格不能播放/暂停**
   修复：面板级 `subKeyPressed` 处理 `GLFW_KEY_SPACE`；有输入框聚焦时不抢键。
4. **导出只有 320x180，太糊**
   原因：BBS 的 `BBSSettings.videoWidth/Height/FrameRate` 还停在预览尺寸，导出直接沿用。
   修复：导出按钮改为打开“导出视频”弹窗（1280/1920/2560/3840、16:9 / 9:16 / 1:1、24/30/60 fps），
   确认后写入 BBS 设置并调用 `UIFilmPanel.applyExportSizeToBBS()`，随后走 BBS 原生录像器
   `recordVideo.clickItself()`。没有新增任何自定义导出管线。
5. **第一次进入没有回放选择**
   修复：`bind()` 结束时弹出“选择回放”模态；不选就不进入编辑。列表为空时提示先去影片编辑器添加回放，
   并提供“打开其他影片…”和“返回影片编辑器”。

### 12.2 界面调整

- 全中文：header、标签页、检查器所有字段、42 个效果名与参数名、渲染队列状态。
- 顶栏重排：标题 / 回放选择 / 播放组（播放、上一帧、下一帧、时间码）/ 右侧（保存、导出、渲染、聚焦）。
  所有控件改为绝对定位——`row()` 会无视 `w()` 把子元素均分，正是之前顶部挤成一团的根因。
- 图层面板增加自己的标题栏，把“+”从顶栏移到图层栏内。
- 预览左上角的“效果 / 蒙版”开关改为绝对定位，不再被均分。

### 12.3 验证方式

用临时 `DevHarness`（已删除）在真实客户端里注入真实鼠标/键盘事件：
选回放 → 加图层 → 打开导出弹窗 → 取消 → 切到“效果”标签 → 按空格。
日志实测 `modal=false, page=2, playing=true`，截图见 `docs/verify/ui-*.png`。
测试：`gradle test` 16/16 通过（`JAVA_TOOL_OPTIONS=-Dfile.encoding=GBK -Dsun.jnu.encoding=GBK`
可解决中文路径下测试 worker 读不了 classpath 的问题）。

### 12.4 仍未做

- 1280x720 + GUI scale 3（640x360 UI）下的紧凑布局还没专门优化。
- 自绘的渲染队列（PNG/ProRes）仍在代码里，但 UI 的导出入口已统一走 BBS 原生录像器。


### 12.5 素材库 / 撤销 / 快捷键（同日第二轮）

- **素材库**（图层面板“素材”按钮）：分类（项目素材 / 物品 / 方块 / 实体 / 界面）+ 缩略图网格 + 搜索 + 翻页。
  项目分类扫描 `config/bbsae/projects/<id>/assets`；游戏分类直接索引客户端资源包
  （`textures/item|block|entity|gui`），通过 `mc:textures/...` 路径复用 `ImageTextureCache`，
  不复制文件。双击插入，或用“替换当前图层”。实测物品分类 582 个图标。
- **全局撤销**：每秒对工程 JSON 做快照（最多 50 步），Ctrl+Z / Ctrl+Y 恢复，覆盖所有属性编辑，
  不需要给每个控件挂钩子。快照恢复后保持当前合成与选中图层。
- **快捷键**：Ctrl+Z / Ctrl+Y / Ctrl+D / Delete / Esc。
- **图层顺序**：列表改为最上层在最上（AE 习惯），新建图层出现在顶部。
- **检查器标签行**：改为显式两列布局（`fieldRow`），不再依赖 `UI.labelRow` 的 RowResizer。
- 中文默认图层名、`[隐]/[独]/[标]` 标记、删除后选中相邻图层。

已知限制：素材缩略图使用线性采样，16×16 图标放大后偏糊；后续可按资源类型切 NEAREST。

---

## 13. 2026-10-18 轨道逻辑 / Effect Controls / 效果库（PR 式重构）

这一轮不是加按钮，而是换了信息架构。用户反馈“底下的轨道不像真正的轨道；只有选中轨道才该有透明度；要有 AE 式效果库；整体像 PR”。据此重写。

### 13.1 第一性原理：轨道是唯一选择源

旧结构把“图层列表”和“时间轴轨道”做成两个列表，选择状态由 `UICompLayerList` 驱动，时间轴只是它下面的一条 92px 预览。结果：

- 时间轴不是编辑主体，拖动/裁剪没有存在感；
- 右侧检查器永远显示“最后一次选中的图层”，没有“未选择”状态；
- 图层列表和时间轴轨道的滚动、选中、顺序是两套。

新结构：

- **轨道头就是图层列表**。左列改成“效果库 / 素材”，不再放第二份图层栈。
- 点击轨道 bar、轨道头或预览里的图层都会调用 `UICompositorPanel.selectLayer(...)`；
  `clearSelection()` 让右侧进入真正的空状态。
- `refreshLayers()` 只做“选中项仍然存在”的校验和轨道刷新，不再维护第二份列表。

### 13.2 真正的轨道编辑器 `UICompTrackTimeline`

新文件：`src/main/java/dev/bbsae/client/ui/UICompTrackTimeline.java`。

- 一行一个图层，左侧 146–212px 轨道头：类型图标、名称、可见眼睛、锁定；
- 右侧 lane：秒刻度尺（`CompTimelineRuler`）、网格、工作区外遮罩、标记、播放头；
- bar：
  - 拖动 bar 主体 = 移动整个轨道，in/out 与关键帧、图层标记一起移动；
  - 拖 bar 左缘 = 裁剪入点，`startTime` 同步补偿，画面内容不滑移；
  - 拖 bar 右缘 = 裁剪出点；
  - 拖拽按帧量化；吸附到播放头、0、合成结尾、工作区、标记、其它轨道的入出点；按住 Shift 临时关闭吸附；
  - 锁定轨道禁止拖动，右键菜单仍可解锁；
- 导航：
  - 滚轮 = 垂直滚动轨道；
  - Shift+滚轮 = 水平平移；
  - Ctrl+滚轮 = 以鼠标为锚缩放；
  - Alt+滚轮 = 行高（18–62px）；
  - 中键拖动 = 水平平移；
  - `-` / `+` / `\` = 缩放 / 适配；
- 交互细节：
  - 左键点击 ruler 拖动 = scrub，按帧暂停；
  - 点击空白 lane = 取消选择并把播放头放到点击处；
  - 双击轨道头 = 重命名（BBS `UIPromptOverlayPanel`）；
  - 右键 bar/空白 = BBS `ContextMenuManager`：重命名、复制、删除、拆分、上下移层、加效果、显示关键帧、隐藏/锁定；
  - 鼠标在裁剪边缘显示水平缩放光标，在 bar 上显示手形光标；
  - 拖动时在光标旁显示“入 / 出 / 时长”卡片；拖动期间不抓 undo 快照，松手后生成一步。

### 13.3 关键帧抽屉

- 底部主区域现在是轨道；BBS `UIKeyframeEditor` 移入 `keyframeDrawer`，默认关闭。
- 工具栏“关键帧”按钮 / `openKeyframes()` 打开；添加任何关键帧会自动打开。
- 抽屉打开时轨道区高 `h(1F,-158)`，关闭时 `h(1F,-26)`。
- 关键帧视图重置为**合成完整时长**（`xAxis.view(0, durationTicks)`），不再按关键帧包围盒缩放；否则 5s 的播放头会跑到画面外。
- 关键帧菱形、插值、右键菜单、复制粘贴仍是 BBS 原生实现，没有自绘。

### 13.4 Effect Controls（右侧默认页）

`refreshInspector()` 的页顺序改为：

| index | 页 | 内容 |
| --- | --- | --- |
| 0 | 效果控件 | 片段信息、运动、不透明度+混合、已应用效果栈、图层样式 |
| 1 | 图层 | 开关、in/out/start/timeScale、冻结、time remap、父级、轨道遮罩、表达式、类型专属、绑定、图层标记、动画预设、形状 |
| 2 | 蒙版 | 蒙版点/手柄/羽化/模式 |
| 3 | 文字 | 排版与逐字动画器 |
| 4 | 合成 | 分辨率/帧率/时长/背景、合成标记、Master Parameters |
| 5 | 输出 | 输出模块预设 |

- 效果栈每个效果一个 `UISection`：启用、上移、下移、移除、参数、颜色参数；
- 效果顺序就是渲染顺序（列表从上到下）；
- 运动/不透明度/效果参数行改为显式两列：标签 80px、字段自适应、秒表 16px；不再依赖 `row()` 均分；
- 不透明度用 `UISliderTrackpad`，有通道时实时显示当前帧值；
- 未选择轨道时“效果控件/图层/蒙版/文字”都显示空状态 + “选择最上层轨道”，不会偷偷编辑上一次的图层。

### 13.5 效果库（左侧）

新文件：`src/main/java/dev/bbsae/client/ui/UIEffectLibrary.java`。

- 搜索框 + 分类树（BBS `UIList` 的 `branch/indent/toggle` 原生树支持）；
- 42 个效果按 `CompEffectRegistry` 分类；“最近使用”最多 6 个；
- 双击效果 = 应用到所选轨道；也可选中后点“应用效果”；
- 未选择轨道时按钮禁用并提示；
- 应用后切到“效果控件”，并在状态栏提示；
- “素材”标签页保留打开素材库、导入图片、替换当前轨道素材的入口。

### 13.6 快捷键

根因：BBS 的 `UIBaseMenu.handleKey` 在 **PRESS 和 RELEASE 都会调用 `root.keyPressed`**。
旧代码没有检查 `KeyAction`，所以 Space/S 这类开关会触发两次，看起来“没反应”。现在所有动作先判断：

```java
KeyAction action = context.getKeyAction();
boolean pressed = action == KeyAction.PRESSED;
boolean repeat = action == KeyAction.REPEAT;
```

只有方向键允许 `REPEAT`，其余只在 `PRESSED` 执行。

| 键 | 动作 |
| --- | --- |
| 空格 | 播放 / 暂停 |
| J / K / L | 倒放 / 暂停 / 正放（连按 2×、4×） |
| ← / → | 上一帧 / 下一帧 |
| Shift+← / → | 5 帧 |
| Home / End | 合成开头 / 结尾 |
| ↑ / ↓ | 上一个 / 下一个剪辑点 |
| Ctrl+↑ / ↓ | 选择上一条 / 下一条轨道 |
| Alt+↑ / ↓ | 轨道上移 / 下移一层 |
| Alt+← / → | 所选轨道前后挪 1 帧 |
| Alt+Shift+← / → | 5 帧 |
| `[` / `]` | 裁剪入点 / 出点到播放头 |
| I / O | 设置工作区入点 / 出点 |
| Ctrl+Shift+I / O | 清除工作区入点 / 出点 |
| C 或 Ctrl+K | 在播放头拆分 |
| S | 吸附开关 |
| M | 添加合成标记 |
| Shift+M / Ctrl+Shift+M | 下一个 / 上一个标记 |
| = / - | 时间轴放大 / 缩小 |
| `\` | 时间轴适配 |
| F | 聚焦预览 |
| Ctrl+D | 复制轨道 |
| Delete / Backspace | 删除轨道 |
| Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y | 撤销 / 重做 |
| Ctrl+S | 保存 |
| Ctrl+M | 导出 |
| Ctrl+/ | 快捷键表 |
| Esc | 关闭弹窗 / 取消选择 |

快捷键表本身用 `modalBox + UIScrollView` 显示，不引入新窗口。

### 13.7 模型规则（`CompLayer`）

新增并测试：

- `allKeyframes()`：transform + opacity + 所有 scalar 通道；
- `shiftKeyframes(deltaFrames)` / `shiftMarkers(deltaSeconds)`；
- `moveTo(newIn, fps, duration)`：保持时长，in/out 与关键帧、标记一起走，`startTime` 不变；
- `trimInTo(newIn, fps)`：`startTime += delta`，让同一合成时间的源画面不变（不 slip）；
- `trimOutTo(newOut, fps, duration)`；
- `splitAt(time, fps, id, name)`：右半复制全部效果/蒙版/关键帧，`startTime += (time - in)`，源画面连续；左半 `out = time`。

`CompModelTest` 新增 3 个测试，当前 **19 个测试全过**（17 headless + 2 OpenGL）。

演示工程也改成不同入出点（Backdrop 0–12、Title 0–7.5、Badge 1.4–6.5 等），这样一打开就能看出“轨道是能移动/裁剪的”，而不是一排等长条。

### 13.8 验证

- `gradle test`：19/19 通过（`JAVA_TOOL_OPTIONS=-Dfile.encoding=GBK -Dsun.jnu.encoding=GBK`）。
- 真实客户端（ASCII 路径副本 `C:\bbsae-dev`，因为 BBS 在含中文路径下读 `categories.json` 会失败）：
  - 面板注册、合成器打开、预览渲染正常；
  - 轨道头/bar/选中态/眼睛/锁定/FX 徽章/秒刻度/播放头/工作区可见；
  - 实验路径：选中 Title → 加 Blur → 裁剪 out 到 8s → 2s 加不透明度关键帧 → 5s 拆分；
    结果：Title 与 Title·B 两条轨道、两个 `1 FX`、in/out 正确、关键帧抽屉打开且播放头 300 帧与 5s 对齐；
  - 无异常日志。
- 截图：`docs/ui-track-redesign.png`。

### 13.9 已知限制 / 下一步

- 轨道仍是“一层一条”，不是 PR 那种一条 lane 里放多个 clip；拆分会产生相邻两条轨道（AE 行为）。
- 还没有多选轨道、框选、剃刀工具、波纹删除、轨道分组。
- 效果库还不能拖拽到轨道；双击/按钮已可用。
- 关键帧抽屉是固定 132px，不可拖拽调整；行高只是临时状态，不写进工程。
- 工作区目前只影响播放循环和轨道遮罩显示，渲染队列仍用任务自己的 start/end。


---

## 14. 2026-10-18 第四轮：效果空间 / 摄像机 / BBS 预览（必读）

详细根因、公式、改动文件、验证证据和打包坑位见
[`docs/ROUND4-2026-10-18.zh.md`](ROUND4-2026-10-18.zh.md)。

一句话版本：

1. **渐变不跟随图层** = 合成 FBO 的 V 轴与合成 Y 轴相反，`EffectSpace` 少翻了一次 V；
   DISTORT / PIXEL 两个 shader 也有同样问题。已修 + `EffectSpaceTest` 4 个回归。
2. **摄像机问题** = 摄像机层被当成纯色层画出一个方块；手动旋转符号与 look-at 相反；
   solo 会把摄像机算进去。已修 + `CameraViewTest` 4 个回归。
3. **BBS 预览层不生效** = 合成器打开时影片面板 `leaveEditing()` 关掉了 BBS 的
   `customSize`、移除了 film runner；`BBSRendering.getTexture()` 从来没有新帧。
   **注意：第一次修的时候直接重开 `setCustomSize`，导致合成器页面整个空白
   （其他页面正常）——`setCustomSize` 是全局模式，会把世界帧 blit 到任何非影片面板的
   UI 上。最终方案是新增 `SceneColor`：世界照常渲染进主 framebuffer，
   `WorldRenderEvents.LAST` 用 `glCopyTexSubImage2D` 抓一份颜色给预览层用，
   完全不碰 `BBSRendering`。** `BbsPreviewBridge` 只负责重挂 runner、
   `AFTER_ENTITIES` 画回放、`setCursor(time*20)` 同步机位。
   BBS 预览层也改为插到图层栈最底部，并新增预览背景 “BBS” 开关。
4. **形状填充渐变**（参考 EffectCraft）：`CompShape.fillGradient/fillColor2/fillGradientAngle`，
   `ShapeCache` 用 AWT `GradientPaint` 烘进形状纹理，天然跟随变换。
5. **界面逻辑**：预览点击改为真正的命中测试（形状用光栅包围盒，其他用锚点盒，
   逆 worldMatrix 变换），点空白取消选择；锁定图层只选中、不拖动。

测试：**28/28**（CompModelTest 18 + EffectSpaceTest 4 + CameraViewTest 4 + ShaderCompileTest 2）。

实机证据：`setCustomSize customSize=true w=1920 h=1080` 与
`BBS preview layer composited a 1920x1080 BBS frame`。

**打包务必用 JDK 的 `jar --create`，不要用 `Compress-Archive`**：
后者在 Windows PS 5.1 会把条目名写成反斜杠，Fabric 加载不到类。


---

## 15. 2026-10-18 第五轮：混合模式 / 效果 / 摄像机 / 图层工作流

详细根因与验证见 [`docs/ROUND5-2026-10-18.zh.md`](ROUND5-2026-10-18.zh.md)。

1. **混合模式不可用** = `COMPOSITE` shader 没有让背景 alpha 参与混合。透明背景上
   Multiply 会乘 0 变黑、Screen 变白。修复 `mix(srcRgb, blendColor(...), b.a)`；
   检查器从“循环按钮”改为 13 项下拉。
2. **渐变吃掉形状一半** = `gradient_ramp` 用了 ColorB 的 alpha（注册表默认 0x00000000），
   把图层 alpha 乘成 0。渐变/杂色现在只换颜色、保留图层 alpha；
   旧工程 softness 回退值从 0.05 改为 1（否则是硬切）。
3. **圆形是椭圆** = UV 空间 distance；改为按像素、短边归一化。
4. **投影上下颠倒** = 屏幕 Y 与纹理 V 相反；`dropShadow` 传 `-dy`。
5. **摄像机**：新摄像机默认关闭 look-at；拖摄像机时目标点同位移跟随（只平移不旋转）；
   缩放只有一行并同步 scaleX/Y；支持表达式；预览里可用 gizmo 选中；新增重置按钮。
6. **预览开关可折叠**：效果/蒙版/BBS 折叠成一个箭头，状态存
   `CompSettings.previewControlsCollapsed`。
7. **添加图层**：轨道/轨道头右键第一项“添加图层…”打开三列 20 类型选择器；
   新增渐变/棋盘/网格/杂色（solid + 生成器效果预设）、椭圆/星形/多边形/贝塞尔、
   HUD 面板、进度条。

测试：28/28。实机 UI 回读截图：`docs/verify/compositor-round5.png`。

产物：`bbsae-0.1.0.jar`，386929 字节，
SHA-256 `DF83F07D2BC3B35ED070C51A98843CF9DA1AB7AFA05D7395A55096832434C706`。

---

## 16. 2026-10-18 第六轮：扭曲 / 调色 / 引导页 / 素材音效 / 1080p60 导出

完整根因、公式、交互与证据见
[`docs/ROUND6-2026-10-18.zh.md`](ROUND6-2026-10-18.zh.md)。

一句话版本：

1. **扭曲失效** = 参数标像素、shader 按 UV 用；`wave` 频率有 0.02 魔法系数；
   `pinch` 没有 radius 参数；半径按图层高度归一化。已改为像素单位、半对角线半径、
   `2π/wavelength`，新增 `ShaderDistortTest` 做真实 GL 像素断言。
2. **调色页**：三段色轮（Lift/Gamma/Gain）+ 五点主曲线 + 预设 + 可关键帧数值；
   新增 `UIColorWheel`、`UICurveEditor`，`GRADE` shader 增加 `Vibrance` 与
   `CurveBlack/Shadow/Mid/Highlight/White`。
3. **标签栏**：`row(2)` 会无视 `w()`，7 个中文标签挤成 42 px 且点击错位；
   `UICompTabBar` 改绝对定位。
4. **可拖拽**：左/右/时间轴/关键帧抽屉四个 splitter；关键帧高度持久化；
   短窗口钳制，避免预览与时间轴重叠。
5. **素材库**：13 分类、10 个纹理根 + BBS 命名空间、全局搜索、音效试听/复制 ID/
   打开 BBS 原版 `UISoundOverlayPanel`；`ImageTextureCache` 支持任意命名空间。
6. **引导页**：`UIStartScreen` + 工程列表 + 新建工程 + 快捷键方案；未选工程不得进入；
   从影片进入也先显示引导页并标出当前工程。
7. **快捷键**：Premiere / BBS 两套；**Alt+滚轮缩放两个方案都支持**；
   时间轴缩放锚定鼠标，播放跟随，裁剪命中区 7 px。
8. **图层时长**：入/出/时长数值框 + 1s/2s/5s/整段 + 播放头按钮；
   设置里有默认图层初始时长。
9. **运动模板**：`CompMotion` 10 个模板，在图层入点写关键帧。
10. **方块破碎**：`block_shatter` 增加 rotation/gravity/spread，shader 改逆向映射，
    裂缝是真正的空隙。
11. **合成器 clip**：标题从 `Compositor` 改为“合成器”；检查器支持选择工程、
    适应/填充/拉伸下拉、工程信息。
12. **外部回放**：`showDuringExternalPlayback`；用 `BBSRendering.isCustomSize()`
    区分编辑器/录像器与外部回放，避免导出被误判。
13. **导出**：自定义宽高 + 比例数值 + 常用比例 + 一键盘 1080p60。
    真实导出 `1920x1080 @60fps`，494 帧，8.23 s，抽帧
    `docs/verify/round6-export-1080p60.png` 含合成层。

---

## 17. 2026-10-18 第七轮：交互 / 3D 空间 / 调整层 / 预合成

完整说明见 [`docs/ROUND7-2026-10-18.zh.md`](ROUND7-2026-10-18.zh.md)。

一句话版本：

1. **预览选择框 = 真手柄**：角点等比、边单轴、顶部旋转；命中 12px；null/camera 用
   屏幕空间 26px gizmo；拖动时状态栏显示缩放百分比。
2. **调整层**：3D 排序以调整层为边界分段，调整层只影响其下已累积像素；
   调色页可一键创建"调色调整层"。
3. **预合成**：新图层自动创建真实子合成；空/未指定/循环目标会画占位框；
   双击轨道或右键进入；`precomposeSelected` 只移动选中层。
4. **摄像机**：自由摄像机 X/Y 旋转真正生效；cameraZ/z 回退；滚轮推拉、Alt+滚轮 FOV；
   近远裁剪可调；**3D 网格不再逐像素绘制**（摄像机卡顿主因）。
5. **Alt+滚轮**：直接读 GLFW 修饰键 + 横向滚轮回退，状态栏显示缩放百分比。
6. **调色**：新增色相旋转与褪色；白平衡改乘法。
7. **测试**：新增 `EffectCoverageTest`（注册表↔渲染器）与自由摄像机 yaw/pitch 用例；
   在 ASCII 路径 + ASCII JDK 下 40/40 通过。

> 测试/构建必须在纯 ASCII 路径，并且 `JAVA_HOME`、`GRADLE_USER_HOME` 也不能含中文，
> 否则 Gradle test worker 会报 `ClassNotFoundException: GradleWorkerMain`。

---

## 18. 2026-10-18 第八轮：Alight Motion PIXEL 效果 + 第一性原理审计

完整记录：

- [`ROUND8-01-AUDIT.zh.md`](ROUND8-01-AUDIT.zh.md)：第一性原理、审计方法、12 个问题；
- [`ROUND8-02-PIXEL.zh.md`](ROUND8-02-PIXEL.zh.md)：PIXEL shader 公式、层空间、参数映射；
- [`ROUND8-03-VERIFY.zh.md`](ROUND8-03-VERIFY.zh.md)：61 个测试、构建、联系表、剩余风险；
- [`ROUND8-04-PUBLISH.zh.md`](ROUND8-04-PUBLISH.zh.md)：GitHub 仓库、提交、release 资产。

一句话版本：

1. **新增 7 个效果**：`scanlines`、`glitch`、`vhs`、`halftone`、`emboss`、`mirror`、
   `kaleidoscope`，全部复用 `PIXEL` shader 的 Mode 2–8。注册表总数 **53**。
2. **层空间**：所有新模式走 `layerUv` + `localToSource` + `sampleLocal`；层外透明，
   不再 clamp 成边缘拖影。层移动/缩放时效果跟着层。
3. **修复参数死链**：`glitch.speed`、`vhs.speed`、`vhs.noise`、`depth_dof.focus`。
   `PixelPlanTest` 会逐个改参数并断言 `PixelPlan` 变化，以后再加效果不会静默失效。
4. **修复 `EffectSpace.identity()` 的 V 轴**：旧值 `LayerV=(0,1,0)` 与 `ofBounds()` 的
   `(0,-1,1)` 不一致，adjustment/3D 层效果上下翻转。新增 identity 往返测试。
5. **修复 `CompShaders.delete()`**：`rgbSplit` 没有置空。
6. **半色调对齐 AM**：横向网点数、强度、角度、行相位、网点色/纸张色；强度 0 精确恒等。
7. **镜像对齐 AM**：叠半透明镜像副本，而不是整幅折叠丢一半。
8. **测试**：61/61 通过；`ShaderPixelTest` 用真实 GL 3.2 上下文做像素断言并输出
   `docs/verify/round8-pixel-effects.png`。
9. **产物**：`bbsae-0.1.0.jar`，402706 字节，
   SHA-256 `2856C0590099208A1D4BF4336922320945DF9FC92CA4EC21BE1C4CEAA1867E4F`。
10. **新增 `PixelPlan`**：参数解析与 GL 分离；`supports()` 改为 `SUPPORTED` 集合，
    `EffectCoverageTest` 同时检查“注册表→渲染器”和“渲染器→注册表”。

### 18.1 接手后优先做的事

1. 在真实开发客户端跑一次预览 + F6 导出，重点看扫描线/半色调在缩放层上的密度；
2. 检查 53 个效果在效果库“屏幕 / 复古”分类的滚动与搜索；
3. 如果要继续扩效果，先加 `PIXEL` Mode/公式，再同步 `CompEffectRegistry`、
   `EffectRenderer.planPixel`、`PixelPlanTest`、`ShaderPixelTest` 四处；
4. 不要再用 `vUv`/`gl_FragCoord` 写层效果；统一用 `sampleLocal`。

### 18.2 本轮已知未验证

- 未启动真实 Minecraft 客户端，未重新跑 F6 MP4；本轮是 headless GL 像素测试 + 联系表。
- `depth_dof.focus` 的修复没有专门的 GL 深度测试（深度捕获在 headless 下不可用），
  只做了代码路径修正；接手后应在影片导出里复验景深。

---

## 19. 2026-10-18 第九轮：导出画面同步 / 时长根因 / 图标

完整记录：[`ROUND9-EXPORT-SYNC.zh.md`](ROUND9-EXPORT-SYNC.zh.md)。

### 19.1 根因

1. 导出时长曾由 BBS 相机/loop 决定：`UIFilmPreview.recordVideo` 用
   `camera.calculateDuration()`；`PanelVideoExportSession` 在 `editorLoop` 时又把结束点改成
   loopMax。于是 12 秒相机默认、20 秒 loop 都会截断 composition。
2. `CompositionClip` 可能只覆盖部分时间/被 trim，导出后面的画面没有 comp。
3. `workAreaOut` 仍是 12 秒，合成器预览循环在 12 秒。
4. `CompositionOverlay` 没关 UI scissor，可能整层被裁掉。
5. 一个坏 project 会中断所有 composition clip。
6. `BBS 预览` 图层在 `!BbsPreviewBridge.isActive()` 时直接 return，导出里必然消失。
7. 2D overlay 写深度，会让下一帧世界/预览层采样到上一帧残影。

### 19.2 修复

- **compositor Export 直接调用 BBS 原生 `UIFilmRecorder.startRecording`**，传入
  `round(doc.duration * 20) + 1` ticks；导出期间关闭 `BBSSettings.editorLoop`，结束后恢复。
  **不再点击 BBS 的 recordVideo 按钮，也不再依赖相机/loop 的时长。**
- `CompositionClip.ensureForFilm(..., compDuration)` 把 clip 固定到 tick 0、覆盖 comp；
  `prepareForExport` 正规化 opacity/timeScale/offset/hold 并 `forceSave()`；
- `workAreaOut` 跟随时长，迁移旧 12 秒默认；
- `CompositionOverlay` 关闭 scissor、关闭 depth test/depth write、逐 instance 隔离异常；
- `BbsPreviewBridge` 在 `AFTER_TRANSLUCENT` 抓取干净世界帧，`bbs` 图层在导出/影片预览
  也可采样 `SceneColor`；
- 用户图标 32×32 → 16×16，`BbsAeAddon.ICON` 用于 dashboard 页签和
  `bbsae:composition` clip；署名 **超级大布叮**，项目维护者 **终end**。

### 19.3 实机验证

- 20 秒 comp：1202 帧 / 20.033 秒，15 秒帧仍有 panel/title
  （`docs/verify/round9-export-20s-15s.png`）；
- **25 秒 comp：501 ticks，1502 帧 / 25.033 秒**，22 秒帧仍有 `BBS 预览`+scanlines
  （`docs/verify/round9-export-25s-22s.png`、`round9-export-25s-ffprobe.txt`）——
  证明没有 12/20 秒常量；
- 只含 `bbs` 图层的 comp 导出：`docs/verify/round9-bbs-preview-layer.png`；
- 测试 **63/63**（新增导出时长 + 图标资源测试）。

### 19.4 仍要注意

- 25 秒之后 BBS 相机没有关键帧时，世界停在最后一帧，composition 继续动；要世界继续运动，
  需要延长 BBS 相机时间轴（影片本身），不是 compositor 时长 bug。
- 多个 replay 的 composition clip 仍可能叠加；当前导出正规化当前 project/comp 的 clip。
- `workAreaOut` 的 12 秒迁移是启发式；如需保留故意的 12 秒循环，应加 `workAreaCustom`。
- 导出仍走 BBS 原生 VideoRecorder/FFmpeg，音频/编码/ESC 行为不变。
