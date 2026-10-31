# BBS Compositor 项目交接文档

> 给接手本项目的 AI / 开发者。  
> 项目目录：`C:\Users\Administrator\Desktop\bbsae`（当前工作副本：`bbsae - 副本 (3) - 副本 - 副本`）  
> 最后更新：2026-10-24 20:35（本地时间）
>
> **先读这个**：
> - 第四轮（效果空间 V 轴 / 摄像机基础 / BBS 预览）：第 14 节 + [`docs/ROUND4-2026-10-18.zh.md`](ROUND4-2026-10-18.zh.md)
> - 第五轮（混合模式 / 效果 alpha / 摄像机交互 / 预览折叠 / 添加图层）：第 15 节 + [`docs/ROUND5-2026-10-18.zh.md`](ROUND5-2026-10-18.zh.md)
> - 第六轮（扭曲 / 调色 / 引导页 / 素材音效 / 快捷键 / 1080p60 导出）：第 16 节 + [`docs/ROUND6-2026-10-18.zh.md`](ROUND6-2026-10-18.zh.md)
> - 第七轮（交互手柄 / 3D / 调整层 / 预合成）：第 17 节 + [`docs/ROUND7-2026-10-18.zh.md`](ROUND7-2026-10-18.zh.md)
> - 第八轮（Alight Motion PIXEL 效果 / 第一性原理审计）：第 18 节 + [`docs/ROUND8-01-AUDIT.zh.md`](ROUND8-01-AUDIT.zh.md)、[`docs/ROUND8-02-PIXEL.zh.md`](ROUND8-02-PIXEL.zh.md)、[`docs/ROUND8-03-VERIFY.zh.md`](ROUND8-03-VERIFY.zh.md)
> - 第九轮（导出画面同步 / 时长 / 合成器图标）：第 19 节 + [`docs/ROUND9-EXPORT-SYNC.zh.md`](ROUND9-EXPORT-SYNC.zh.md)
> - 第十轮（图层左侧拖拽排序）：[`docs/ROUND10-LAYER-REORDER.zh.md`](ROUND10-LAYER-REORDER.zh.md)
> - 第十一轮（素材库不可用 / 背景模糊）：[`docs/ROUND11-ASSET-LIBRARY.zh.md`](ROUND11-ASSET-LIBRARY.zh.md)
> - 第十二轮（AE 曲线编辑器 / 图层预设 / 父子级绑定修复 / 图形角与颜色，版本 0.2.0）：
>   第 22 节 + [`docs/ROUND12-CURVES-PRESETS-PARENTING.zh.md`](ROUND12-CURVES-PRESETS-PARENTING.zh.md)
> - 第十三轮（摄像机重写 / 3D 父子级 / 多选与预合成 / 性能，版本 0.3.0）：
>   第 23 节 + [`docs/ROUND13-CAMERA-MULTISELECT-PERF.zh.md`](ROUND13-CAMERA-MULTISELECT-PERF.zh.md)
> - 第十四轮（界面密度三档 / 扁平按钮 / 全量悬浮提示，版本 0.4.0）：
>   第 24 节 + [`docs/ROUND14-UI-DENSITY-FLAT.zh.md`](ROUND14-UI-DENSITY-FLAT.zh.md)
> - Photon 引用声明（仅研究思想，未包含 Photon 代码/着色器/素材）：[`PHOTON-NOTICE.md`](../PHOTON-NOTICE.md)

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

- 根目录 `bbsae-0.10.0.jar`
- `build/libs/bbsae-0.10.0.jar`
- SHA-256：`213a21e0849a733d69c915a56df37302f2aac00c0310e1c22f745aea3f756218`
- 大小：`623996` 字节

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
- 代理/预渲染缓存：**已实现（0.5.0）**，见第 25 节；预览按视口像素渲染，预渲染写磁盘缓存并显示绿条。
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
    预览已按视口像素渲染（0.5.0）；蒙版/形状仍是 CPU 栅格化。需要 layer-local mask texture、mask cache 上限、GPU 蒙版。
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

- `/bbsae-0.10.0.jar`
- SHA-256 `213a21e0849a733d69c915a56df37302f2aac00c0310e1c22f745aea3f756218`
- 大小 `623996`
- 构建时间 2026-10-31 15:05
- 构建方式：`gradle remapJar --offline`（`remapJar` 正常写 zip；本机已不再需要手工重映射）

当前源码最后一次验证：

- `test` **164/164** 通过；`jar` 由 `remapJar` 重新打包；
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
9. **产物**：`bbsae-0.1.0.jar`，406842 字节，
   SHA-256 `477DB7823351D6AA47D5359247835435442829ADDDB0961ABBB6960FFC171CF1`。
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

- 导出对话框新增 **“导出时长（秒）”**（`CompSettings.exportDuration`，0 = 合成时长）和
  **“同步到 BBS 时间轴”** 按钮；用户可以直接输入 40/60/120 秒；
- **进入工程后的“合成器设置”弹窗也有“合成 / 导出时长（秒）”字段**和
  **“应用时长到合成与 BBS 时间轴”** 按钮；不需要先打开导出对话框；
- **修复真正的 20 秒拖拽屏障**：字段回调调用 `adoptCompositionDuration(seconds)`，
  先扩展 `doc.duration`、workArea、composition clip、BBS loop，再刷新时间轴；
  之前只写 `exportDuration`，`doc.duration` 仍是 20，拖图层自然被 20 秒夹住；
- **compositor Export 直接调用 BBS 原生 `UIFilmRecorder.startRecording`**，传入
  `round(exportSeconds * 20) + 1` ticks；导出期间关闭 `BBSSettings.editorLoop`，结束后恢复，
  同时把 BBS `loopMin/loopMax` 同步到导出长度。
  **不再点击 BBS 的 recordVideo 按钮，也不再依赖相机/loop 的时长。**
- `CompositionClip.ensureForFilm(..., compDuration)` 把 clip 固定到 tick 0、覆盖 comp；
  `prepareForExport` 正规化 opacity/timeScale/offset/hold 并 `forceSave()`；
- `workAreaOut` 跟随时长，迁移旧 12 秒默认；
- `CompositionOverlay` 关闭 scissor、关闭 depth test/depth write、逐 instance 隔离异常；
- `BbsPreviewBridge` 在 `AFTER_TRANSLUCENT` 抓取干净世界帧，`bbs` 图层在导出/影片预览
  也可采样 `SceneColor`；
- 用户图标 32×32 → 16×16，`BbsAeAddon.ICON` 用于 dashboard 页签和
  `bbsae:composition` clip；资源必须在
  `assets/bbsae/assets/textures/gui/compositor_icon.png`（BBS addon source pack 的
  `assets/<modId>/assets` 约定），否则运行时会报 `couldn't be found`；
- 真实客户端已验证 `Texture "bbsae:textures/gui/compositor_icon.png" was loaded!`
  （`docs/verify/round9-icon-load.txt`）；
- 署名 **超级大布叮**，项目维护者 **终end**。

### 19.3 实机验证

- 20 秒 comp：1202 帧 / 20.033 秒，15 秒帧仍有 panel/title
  （`docs/verify/round9-export-20s-15s.png`）；
- **25 秒 comp：501 ticks，1502 帧 / 25.033 秒**，22 秒帧仍有 `BBS 预览`+scanlines
  （`docs/verify/round9-export-25s-22s.png`、`round9-export-25s-ffprobe.txt`）——
  证明没有 12/20 秒常量；
- **导出时长字段 = 40 秒：801 ticks，2402 帧 / 40.033 秒**，35 秒帧仍有画面
  （`docs/verify/round9-export-40s-35s.png`、`round9-export-40s-ffprobe.txt`）——
  这是用户可直接操作的、绕过 20 秒的入口；
- 只含 `bbs` 图层的 comp 导出：`docs/verify/round9-bbs-preview-layer.png`；
- 测试 `layerCanMovePastTwentySecondsWhenCompositionIsLonger`：comp 40 秒时，
  `in=0/out=20` 的图层可以移动到 `in=20/out=40`，证明 20 秒不是图层硬上限；
- 测试 **65/65**（导出时长 + 拖拽排序 + 图标资源）。

### 19.4 仍要注意

- 25 秒之后 BBS 相机没有关键帧时，世界停在最后一帧，composition 继续动；要世界继续运动，
  需要延长 BBS 相机时间轴（影片本身），不是 compositor 时长 bug。
- 多个 replay 的 composition clip 仍可能叠加；当前导出正规化当前 project/comp 的 clip。
- `workAreaOut` 的 12 秒迁移是启发式；如需保留故意的 12 秒循环，应加 `workAreaCustom`。
- 导出仍走 BBS 原生 VideoRecorder/FFmpeg，音频/编码/ESC 行为不变。

---

## 20. 2026-10-18 素材库热修

完整记录：[`ROUND11-ASSET-LIBRARY.zh.md`](ROUND11-ASSET-LIBRARY.zh.md)。

### 20.1 根因

素材库用 `mc:` 作为原版纹理前缀：

```
mc:textures/block/acacia_leaves.png
```

`ImageTextureCache.fromResource` 把 `mc` 直接当 `Identifier` 的 namespace：

```java
new Identifier("mc", "textures/block/acacia_leaves.png")
```

但原版资源命名空间是 `minecraft`，所以 `getResource` 永远为空。  
表现就是所有物品/方块/实体缩略图变成占位图标，插入的图层也没有纹理。

### 20.2 修复

- `fromResource` 把 `mc` 映射到 `minecraft`；
- `bbs:` 保持 `bbs`，工程图片保持文件路径；
- `UIAssetBrowser.render` 用 `InterfaceBlur.applyUnder()` 模糊背景，再叠 40% 黑遮罩；
- 面板缩到 620×400，5×3 缩略图、6 列分类，底部插入/替换/关闭在 GUI scale 2 下可见；
- 证据：`docs/verify/round11-asset-library.png`。

### 20.3 验证

- 真实客户端：原版方块缩略图正常显示，背景模糊；
- 测试 **65/65** 通过。

---

## 21. 2026-10-24 交互逻辑修复

完整审计见 [`INTERACTION-AUDIT.zh.md`](INTERACTION-AUDIT.zh.md)（8 个 P0 / 14 个 P1 / 27 个 P2）。
这一轮先修 P0 和低风险 P1，没有重写布局/效果栈紧凑列表/预览缩放/蒙版钢笔。

### 21.1 改了什么

- **预合成**：逻辑收敛到 `CompStore.precompose`。新层插回源层原索引；子层 in/out 归零、
  关键帧/标记整体平移 `-in`；父级/遮罩关系转移到新 precomp 层；新增
  `precomposeRebasesInnerTimelineAndKeepsOrderAndRelations` 回归测试。
- **关键帧**：`UICompositorKeyframes.pickKeyframe` 让播放头对齐选中键；轨道 bar 底部的
  关键帧刻度可点击并选中；关闭抽屉/切合成/关面板时清理挂在 inspector 上的关键帧属性面板；
  打关键帧不再自动弹抽屉，改为状态提示。
- **入点**：图层页的入/出点改走 `trimInTo/trimOutTo`，不再直接写 `l.in`；补“冻结时间”字段。
- **反馈**：所有直接写 `status.label` 的错误改走 `notifyError`（不会被 `render()` 每帧覆盖）；
  保存/撤销/重做有状态提示；导出只保留一个“开始导出”，允许 0 = 合成时长；素材库有
  replace 模式；空/循环预合成的占位文字只在预览出现。
- **撤销**：新增 `beginAction` 在离散命令前压入当前状态，并在下一帧记录命令后的基线；
  覆盖添加/删除/复制/排序/拆分/效果/样式/蒙版/标记/预合成等命令。
- **容器**：从影片编辑器进入不再先弹工程列表；进入预合成映射父时间；Esc 退出；时间轴工具条
  显示 `主合成 / 预合成 N` 路径；预合成 bar 显示子层数；返回时恢复父时间轴缩放；合成可改名。
- **轨道**：独显/遮罩/蒙版/样式以文字旗标显示在 bar 上；标记可点击跳转；工作区边界可拖；
  关键帧刻度可点。
- **一致性**：样式左键弹出选择器并支持上移/下移；3D 空间可退出；摄像机视图中心优先内容层；
  混合/遮罩/样式/输出等英文按钮本地化；右栏最小宽度 296px、头部 768 逻辑宽不再重叠；
  快捷键弹窗关闭按钮移到底部。

### 21.2 验证与产物

- `test` **66/66** 通过（新增 1 个预合成回归）。
- `gradlew build --offline` 成功，`remapJar` 正常写 zip。
- `bbsae-0.1.0.jar`：**413352** 字节，
  SHA-256 `19F8380628B88C21FC52FF685C536EA8A280E13FF7C70CC1D7D132DE55BCA293`。

### 21.3 仍未做（下一批）

- 效果栈仍是展开的 `UISection`，没有紧凑列表/拖拽排序；只做了加效果滚动、样式排序、
  运动模板默认折叠。
- 没有合成浏览器（列表/新建/删除/拖入）；只做了面包屑、改名、进入时间映射。
- 预览没有缩放/平移；蒙版/形状仍只能输数字。
- 关键帧刻度可选中但还不能直接拖动改时间。
- 撤销没有 Undo/Redo 按钮和动作名，只有 Ctrl+Z / Ctrl+Shift+Z。


---

## 22. 2026-10-24 第十二轮：AE 曲线 / 预设 / 父子级 / 图形角（0.2.0）

完整说明见 [`docs/ROUND12-CURVES-PRESETS-PARENTING.zh.md`](ROUND12-CURVES-PRESETS-PARENTING.zh.md)，
这里只留接手必须知道的结论。

### 22.1 新增的硬约束（不要破坏）

1. **变换曲线必须逐分量求值**。`CompLayer.transformAt()` 用 `CompCurves.transformValue()` 分别算
   x/y/rotation/scaleX/scaleY，且**返回新的 Transform**。BBS 的 `Transform.lerp` 会忽略
   `lx/ly/rx/ry`，`TransformKeyframeFactory` 还会复用同一个 `Transform` 实例——
   改回 `channel.interpolate()` 既会让曲线手柄失效，也会串图层。
2. **曲线编辑器不是第二套关键帧**。`UICompCurveEditor` 只编辑 `KeyframeChannel` 与
   `Keyframe.lx/ly/rx/ry` + `getInterpolation()`，和 BBS dope sheet 共用数据；
   任何"曲线专用"的旁路存储都是错的。
3. **父子级绑定必须换基**。`CompParenting.bind()` 做 `parentWorld⁻¹ · childWorld`，
   再把 delta 应用到基础字段**和每一个 Transform key**。直接写 `parentId` 就是本轮修掉的那个 bug。
4. **拖动父级子级要用父空间增量**（`CompParenting.toLocalDelta`），否则父级缩放/旋转时光标与元素分离。
5. **角半径路径 `shape/<id>/c/<index>` 必须放在属性 switch 之前**。`putNestedScalar`/`nestedScalar`
   的 switch 对未知键 `return true`/`return fallback`，放在后面永远走不到。
6. **形状颜色是逐形状的**。`ShapeCache.rasterize` 按顺序逐形状填充/描边并用
   `DstOut / DstIn / 双临时图` 实现相减/相交/排除；不要再合并成一个 `Area` 用单色填充。
7. **预设必须走同一条 apply 路径**。内置预设也是 `CompPresetPayload` + JSON，
   应用时对 `fx/<id>/...`、`shape/<id>/...` 通道做重映射，否则动画会丢。

### 22.2 本轮改动

- 曲线：值图表 / 速度图表、贝塞尔手柄（Alt 断开）、框选、拖键、双击加键、吸附、缩放平移、
  播放头拖动、AE 关键帧图形（方块/圆点/菱形）、8 个缓动预设（线性/贝塞尔/缓动/缓入/缓出/定格/自动/连续）+ 影响参数。
- 预设：左栏第三个页签；5 种 kind（动画/效果/样式/形状/整套）；10 个内置预设；
  用户预设存在工程里；`CompProject.VERSION` → 3。
- 父子级：`CompParenting`（换基、极分解、父空间增量、循环拒绝）；绑定/解绑都不再跳。
- 图形：`TRIANGLE` 类型、逐角半径（可关键帧）、圆角/切角、逐形状填充/描边颜色、布尔运算按钮。
- 时间轴区：顶部 **时间轴 / 曲线** 页签，视图状态存入 `settings.timelinePage`。

### 22.3 验证与产物

- `test` **88/88** 通过（新增 `CompCurvesTest` 6、`CompParentingTest` 8、`CompShapePresetTest` 8）。
- `gradle remapJar --offline` 成功。
- `bbsae-0.2.0.jar`：**476800** 字节，
  SHA-256 `1a7e7cdebaa36aee0836967971faab77628582fe2b7638f54dff5bf1768fe2bc`。
- 备份：`_backup/round12-2026-10-24/`（动工前的 src/docs/README/gradle/旧 jar）。

### 22.4 明确未做

- 3D 图层的父子级补偿（`localWorldMatrix3D` 的 Y 翻转空间未处理）。
- 预设缩略图（当前只有名称 + 类型 + 描述）。
- 曲线的复制/粘贴关键帧、整体数值偏移、与轨道选区联动。
- 父级同时有**非均匀缩放 + 旋转**时，子级的旋转/缩放只能近似（位置精确）——
  这是图层变换没有剪切通道的数学限制，不是实现偷懒。


---

## 23. 2026-10-24 第十三轮：摄像机 / 3D 父子级 / 多选 / 性能（0.3.0）

完整推导见 [`docs/ROUND13-CAMERA-MULTISELECT-PERF.zh.md`](ROUND13-CAMERA-MULTISELECT-PERF.zh.md)。

### 23.1 新增的硬约束（破坏任何一条都会让摄像机重新"不能用"）

1. **只有一个摄像机、一个场景图**。`CompCamera` 解析相机，`CompScene` 给出 local/world/model/
   projection/depth。不要在任何地方再写第二套 `localWorldMatrix3D`/`cameraView`——上一轮的 3D
   父子级错位就是两套矩阵无法互逆造成的。
2. **透视投影必须用 `Matrix4f.transformProject`**，不能用 `transformPosition`。JOML 的
   `transformPosition` 是仿射变换、不做透视除法；用它会把 3D 坐标放大成 `-230646px` 这种值。
3. **2D 视图是 3D 摄像机在 z=0 的切片**：`view2D = translate(center)·scale(focal/distance)·rotateZ(-roll)·translate(-camera)`。
   任何"2D 用 scale、3D 用 cameraZ"的双语义都会让开关 3D 时尺寸跳变。
4. **所有拾取/手柄/拖动都走 `CompScene.project` / `CompCamera.unproject`**。屏幕点先反投影回
   comp 空间，再做局部框命中；否则摄像机一推近，点选就偏。
5. **摄像机始终生效**，`cameraPreviewMode` 只控制 3D 辅助线（网格/视锥）；`camera3D` 只表示
   透视/正交。不要再把摄像机挂到某个 UI 开关上。
6. **每帧每层只算一次矩阵**（`CompRenderContext` 里的 IdentityHashMap 缓存）。渲染、排序、
   手柄、命中测试共用同一个矩阵。
7. **预览帧缓存按内容指纹失效**（`CompRenderCache`）。新增任何影响像素的字段，必须同时加进
   指纹，否则会"改了没反应"。时间按合成帧率量化。
8. **撤销快照只在文档脏时序列化**，并受 32MB 预算约束；拖拽期间跳过。
9. **多选是 `selectedIds` 集合，`selectedLayer` 只是主成员**。轨道是唯一选择源；批量操作走
   `CompLayerOps`，不要再在 UI 里各写一份。

### 23.2 本轮改动

- 摄像机重写：位置/朝向/滚转/FOV/近远、看向目标或自由俯仰偏航、透视/正交、预览内拖动/瞄准/
  推拉/改视场、视锥+目标 gizmo、检查器重排、旧工程 z/scale 兼容回退。
- 3D 父子级：统一场景图后同一套换基；`CompParenting` 增加 3D 分解（Rz·Rx·Ry）与标量通道平移。
- 多选：Ctrl 切换 / Shift 区间 / 预览多选 / 整组拖动；多选检查器页；批量预合成、编组、对齐、
  分布、显示/隐藏/锁定、复制、删除。
- 性能：帧缓存、快照去抖与内存上限、深度与世界拷贝门控、去掉每帧 GL 同步、FBO 池 32、
  每帧矩阵缓存、网格减负。

### 23.3 验证与产物

- `test` **97/97** 通过（新增/重写 `Camera3DTest` 11、`CompLayerOpsTest` 6、3D 父子级 2）。
- `gradle remapJar --offline` 成功；产物见第 9 节。
- 备份：`_backup/round12-2026-10-24/`（本轮动工前）+ `_backup/round13-final/`（本轮完成态）。

### 23.4 明确未做

- 2D 图层不跟随相机**倾斜**（只跟随平移/缩放/滚转）——"2D 贴在屏幕上"的定义。
- 父级非均匀缩放 + 旋转时，子级旋转取最接近值（位置精确）。
- 预合成没有"裁剪到选区 / 移动所有属性"选项。
- 多选整体旋转/缩放（目前按主成员计算）。
- 3D 图层内容 FBO 仍是整幅 comp 尺寸。


---

## 24. 2026-10-24 第十四轮：界面密度 / 扁平按钮 / 悬浮提示（0.4.0）

完整说明见 [`docs/ROUND14-UI-DENSITY-FLAT.zh.md`](ROUND14-UI-DENSITY-FLAT.zh.md)。
**本轮只改界面皮肤与尺寸，功能逻辑一行未动。**

### 24.1 新增的硬约束

1. **所有尺寸从 `CompUi` 取**，不要再写 `h(18)`、`w(90)`、`h(20)` 这类魔法数字：
   `CompUi.control()/row()/gap()/labelWidth()/icon()/bar()` 与 `leftWidth()/rightWidth()/timelineHeight()`。
   密度三档 + 自动跟随 BBS 的 GUI scale（scale 越小档位越大）。
2. **按钮不再用 BBS 默认皮肤**。默认 `UIButton` 会用主色填充；面板里一律用
   `UICompButton`（FLAT / PRIMARY / DANGER / GHOST）。**每个面板只能有一个 PRIMARY**。
3. **每个按钮必须有悬浮说明**。`UICompButton` 构造时按标签查 `CompUi.describe`；新增按钮文案要
   同步往词汇表里加一条，`CompUiTest` 会检查常用标签是否落到兜底文案。
4. **图标优先**：一个符号能表达的动作（+、←/→、适配、删除）用 `CompUi.iconButton`，tooltip 必填。
5. **密度切换要重建检查器**（行高在构造时确定）：`applyDensity()` 里 `refreshInspector()` 不能省。

### 24.2 本轮改动

- `CompUi`：密度表、按钮工厂、70+ 条中文提示词、强调色。
- `UICompButton` / `UICompIconButton`：扁平皮肤、hover 边框、左对齐、可选图标与右值。
- 顶栏 / 时间轴头 / 检查器 / 曲线工具条 / 预设卡片 / 设置弹窗 / 引导页 / 效果库全部扁平化。
- 设置里新增 **界面密度**（跟随 BBS / 小 / 中 / 大），存 `CompSettings.uiDensity`，随工程保存。

### 24.3 验证与产物

- `test` **104/104**（新增 `CompUiTest` 7 个）。
- `gradle remapJar --offline` 成功；产物见第 9 节。
- 备份：`_backup/round14-final/`。

### 24.4 已知限制

- 顶栏按钮尺寸在创建时确定；切换密度后建议重开面板以完全一致。
- `UISection` 标题条高度是 BBS 固定值，不随密度变化。
- 提示词为中文硬编码，等 BBS i18n 键稳定后再迁移。

---

## 25. 2026-10-24 第十五轮：BBS 原版预览 + 世界深度 / 预渲染缓存 / 性能（0.5.0）

完整推导见 [`docs/ROUND15-PREVIEW-CACHE-PERF.zh.md`](ROUND15-PREVIEW-CACHE-PERF.zh.md)。

### 25.1 新增的硬约束（破坏任何一条，用户会立刻感觉到"卡"或"预览不对"）

1. **`save()` 不写盘**。它只标记脏 + 预约写盘；写盘走 `flushSave()`。
   任何在每帧/每个鼠标事件里调用 `CompStore.save` 的新代码都是回归。
2. **预览按显示像素渲染**。`CompRenderContext.scale` 只改 render target，
   **不改坐标系**；任何新增 pass 都必须用 `context.renderWidth()/renderHeight()` 申请 FBO，
   不要再用 `context.width/height`（那是合成像素）。
3. **世界 color 与 depth 必须同帧同源**。都在 `WorldRenderEvents.LAST` 里从
   `mc.getFramebuffer().fbo` 拷贝；预览的深度效果读 `BbsPreviewBridge.depth()`，
   不要自己去 `SceneDepth.capture()`（那会读到自己刚绑定的合成 FBO，得到空深度）。
4. **预览背景的请求状态每帧从文档推导**（`BbsPreviewBridge.syncRequests`）。
   不要再把 `setBackdropRequested` 只挂在 toggle 回调上——`UIToggle.setValue` 不触发回调。
5. **绿条只能代表"当前文档"**。任何编辑都必须 `CompPrecache.markDirty()`；
   含 `BBS 预览` 图层的合成禁止预渲染。
6. **Alt+滚轮 = 轨道行高**，Ctrl/Alt+Shift+滚轮 = 时间轴缩放。
7. **曲线数值轴按当前通道**，不要退回"所有可见通道的并集"。

### 25.2 本轮改动

- 新增 `CompPrecache`（磁盘 raw+deflate、显存 LRU 16、协作式 2 帧/18ms、绿条 BitSet）。
- `CompRenderer`：`previewScale()` 预览代理、`blitTexture()`、`renderForCache()`、
  预览优先用世界深度、`renderCached` 先查预渲染缓存。
- `SceneDepth.captureFrom(fbo,w,h)`；`BbsPreviewBridge` 同时捕获 color+depth，
  新增 `syncRequests()/depth()`。
- `UICompositorPanel`：去抖保存、预览代理、摄像机滚轮方向与 Alt 检测、预渲染按钮、
  背景默认迁移（`previewBackgroundChosen`）。
- `UICompTrackTimeline`：Alt+滚轮行高、时间尺绿条。
- `UICompCurveEditor`：数值轴按通道、聚焦模式、十字线读数、更大命中半径。

### 25.3 验证与产物

- `test` **111/111**（新增 `PreviewProxyTest` 7 个）。
- 构建注意：**含中文的工程路径会让 Gradle test worker 报 `ClassNotFoundException`**。
  用 `subst X: "<工程目录>"` 映射成 ASCII 盘符即可；junction 无效。
- `gradle remapJar --offline` 成功；产物 `bbsae-0.5.0.jar`（哈希见 README）。

### 25.4 已知限制

- 3D 图层不会被世界几何遮挡（深度只提供给 `depth_*` 效果）。
- 预渲染不支持 `BBS 预览` 图层。
- 预渲染期间单帧渲染不可抢占，靠冷却让步，仍可能轻微掉帧。
- 磁盘缓存不自动清理旧工程目录。

---

## 26. 2026-10-24 第十七轮：曲线编辑器 P0+P1（0.6.0）

方案见 [`docs/ROUND16-CURVE-EDITOR-DESIGN.zh.md`](ROUND16-CURVE-EDITOR-DESIGN.zh.md)，
实施记录见 [`docs/ROUND17-CURVE-PERF.zh.md`](ROUND17-CURVE-PERF.zh.md)。
改动前备份：`_backup/round16-pre/`。

### 26.1 新增硬约束

1. **曲线不许再"按像素画线"**。几何走 `CurveSpans`（每列 `[lo,hi]`，相同区间合并成矩形）。
   `line()` 只用于手柄连线，且必须按水平跨度步进。
2. **几何缓存用显式失效**：`CompCurveChannel.touch()`（标量）与 `transformEpoch`（5 轴共享键表）。
   任何新的关键帧写路径必须 `touch()`，否则曲线画旧形状。
3. **拖动只重建脏列**（`markDataChanged(fromCol, toCol, channel)`），不许整条重建。
4. **拖动不排序全部通道**：只排本次编辑涉及且 tick 真的乱序的通道（`sortIfNeeded`）。
5. **拖动每帧 `panel.markCurveDirty()`**：预渲染缓存优先于内容指纹，不标记就会显示绿帧旧画面。
6. **松手分级刷新**：拖动 → `curveChanged(false)`（只跑值绑定）；加/删键/改类型 →
   `curveChanged(true)`（才重建 Inspector 与 keyframe sheets）。
7. **底部条是纯函数**：`reconcileTimelinePage()` 每帧从 `(page, 可见性, 矩形)` 推导；
   `Graph.updateAreas()` 永远钳制到 ≥1×1（负矩形会让裁剪吃掉全部内容 → 空白页）。
8. **曲线区修饰键走 `CompInput`**，不要用 `Window.isXxxPressed()`。
9. **数值轴缩放保持 Ctrl+滚轮**（用户确认），不要改成 AE 的 Alt+滚轮。

### 26.2 本轮改动

- `CurveSpans`（新）+ 列跨度绘制 + 脏列重建。
- `CompCurveChannel`：`revision/touch()`、`valueAt(frame, fallback)`、去掉 `setKeyValue` 的 sort。
- `UICompCurveEditor`：零分配遍历（keys/handles/keyAt/marquee）、`owners` 身份表、
  行/轴标签/读数缓存、`sortIfNeeded`、松手分级、`CompInput`、活动通道按 id 保持。
- `UICompositorPanel`：`curveChanged(boolean)`、`rebindInspectorValues()`、`markCurveDirty()`、
  `setCurveInteraction()`、`setCurveDirtyRange()`、`reconcileTimelinePage()`、
  `applyTimelinePage(grow)`、`onAppear/refreshComp/toggleRenderView` 重新推导、曲线区过窄自动回退。
- `CompRenderCache`：`cachedFrame` + 时间域守卫（`reuseForTime`）。
- `CompRenderer`：时间域短路、`setInteractionPreview()`（拖动期 0.7×）。

### 26.3 验证

- `test` **119/119**（新增 `CurveSpansTest` 5、`CompCurveChannelRevisionTest` 3）。
- `remapJar --offline` 成功；产物 `bbsae-0.6.0.jar` 548025 字节，
  SHA-256 `b4ab3d93…aef92`。

### 26.4 已知限制 / 下一轮

- 起手仍会做一次整工程 JSON 撤销快照（10–40ms），未做图层级快照（P3）。
- 顶部工具条仍是 15 个控件平铺，通道树/上下文面板/右键键类型未做（P2）。
- 曲线 VBO 未做；若 4K 下曲线区仍超 2ms 再上（P3）。

---

## 27. 2026-10-31 第十八轮：曲线可见性 / 导入 / 音视频导出 / 素材库 / 背景 / 摄像机（0.7.0）

详见 `docs/ROUND18-CURVE-IMPORT-EXPORT-CAMERA.zh.md`。这里只留结论和新的硬约束。

### 27.1 曲线"只有点点"的根因（两个，不是一个）

1. `rebuild()` 从不调用 `fitValue()`，值轴停在默认 **-100…100**。位置通道是 380…1500，
   于是每一列的 Y 都落在图矩形之外，`Batcher2D.clip()` 把整条线裁掉。
2. `rebuild()` 用 `getDurationTicks() / 24` 当视口时长，而 `getDurationTicks()` 返回的是**帧数**
   （`duration × fps`），60 fps 的 12 秒合成被当成 30 秒。

**新约束**：曲线的视口必须由数据决定 —— 换图层 `fitTime() + fitValue()`，换通道 `fitValue()`，
其余情况只在"值轴完全看不到该通道任何关键帧"时重取景（`fitValueIfInvisible()`）。
任何"采样点在图外"的情况都必须钳到边缘，不能靠裁剪隐藏。

**列几何不要再缓存**。0.6.0 的 `stamp/viewKey/脏列` 缓存在实机出现过"相邻列在无关值之间跳"，
而同样数据无游戏环境复采样完全平滑。现在每帧按图宽重采（810 列 ≈ 0.2ms），
`CurveSpans` 只当容器。**不要为了省 0.2ms 把不可证明的失效逻辑加回来。**

### 27.2 导入：不要再用 `UIFileDialogs`

原生 tinyfd 窗口在全屏游戏里可能开在窗口后面或永不回调，回调不触发时按钮就是"死的"，
而且没有异常可抓。所有导入（图片/字体/音频）统一走 `UIImportBrowser`，
复制路径统一 `copyIntoProjectImages()` / `assets/audio`，导入后 `UIAssetBrowser.selectPath()` 选中。

### 27.3 音频：混音是纯函数，区间要带 seek

`CompAudioMixer.mix(document, start, end)` 不能读全局状态。**区间导出必须带
`seek = max(0, exportStart - clipStart)`**，否则"导出 2s…3s"拿到的是文件开头。
视频混流加 `-af apad -shortest`，否则帧/采样取整会吃掉最后一帧。

输出模块的 `kind`（video/audio）是行为开关：`audio` 不渲染任何一帧。找不到预设时按 id 前缀决定，
**不要**回退到 `outputPresets.get(0)`（会把"导出音频"变成渲染视频）。

### 27.4 舞台色只有一个出口

`CompRenderer.stageColor(project, comp, preview, transparentStage)`：
BBS 底图预览 → 透明；alpha 导出 → 透明；其余 → `comp.background`。
`background` 默认不透明黑，v5 迁移把老工程从"世界底图"改回纯色一次。
**不要**在 `enter3DSpace()` 之类的地方顺手改 `previewBackground`。

### 27.5 摄像机是视图，不是内容

- `CompDocument.activeCamera()` **不看** `visible`，也**不看**入出点；只按 `activeCameraId` 和图层顺序选。
- 位置一律用 `CompLayer.cameraEye(frame)`（transform 通道），**不要**用 `scalarAt("x", layer.x)`。
- 摄像机的绘制/命中/拖动只有一份几何：`renderCameraGizmo` 与 `handleAt`；
  `layerAt()` 里摄像机分支直接调用 `handleAt()`。
- `CompCamera.of()` 判断"cameraZ 通道是否存在"，而不是"值是否为 0"。

### 27.6 验证入口

```bash
./gradlew runClientView -PbbsaeView=curve,assets     # 每 8 秒切一个视图
./gradlew runClientView -PbbsaeView=camera           # 进 3D 空间
```

`-Dbbsae.debugCurve=true`（`clientView` 默认开）会打印每帧的
`area / view / axis / fps / keys` 和前 10 列 `列@帧=值/跨度`。曲线问题先用它看数据，再怀疑绘制。

### 27.7 产物

- `bbsae-0.7.0.jar` 583134 字节
- SHA-256 `4d198356871ef5b0905725bed9d320a9346c63c47464b36bab22c8b2a72d254f`
- `test` **137/137**

---

## 28. 2026-10-31 第十九轮：AE 编码器模式（0.8.0）

详见 `docs/ROUND19-AE-ENCODER.zh.md`。

### 28.1 两条管线，一个工程

`project.settings.encoder`：

* `"bbs"`（默认）—— 合成是影片叠加层，帧由游戏产生，BBS 的 FFmpeg 录像器导出。
* `"ae"` —— 帧由合成器产生：`CompRenderer.renderFrame(project, doc, frame, alpha)`，
  时间只由帧号推出，合成分辨率，预览与导出同一个函数。

**不要**再让第三条路径（例如某个调试按钮）自己调 `renderCompToFbo` 去"导出一帧"：
`renderOffscreen` / `renderForExport` 已经是 `renderFrame` 的薄包装。

### 28.2 帧服务器

`CompFrameServer` 只是缓存，不是渲染器：

* 键 `(CompRenderCache.signature, frame, alpha)`；signature 含帧号/图层/关键帧/效果，
  所以"改文档 → 旧帧不可达"，不需要额外失效逻辑。
* 192 MB 预算，LRU，释放回 `FboPool`；`invalidate()` 在换工程、切管线时调用。
* `renderFrameCached` 先查绿条预渲染（磁盘代理），再查帧服务器。

### 28.3 风险必须写在 UI 里，不是文档里

首次切 AE 弹六条风险确认（`showEncoderRisk`），确认后写 `encoderRiskAcknowledged`；
预览右上角常驻红色徽标 `AE 编码器 / 帧 N / 总数`；导出按钮文案变 `AE 导出`。
这六条是真实语义差异（尤其"BBS 预览层在离线导出里是静态帧"），改动这些行为时要同步改文案。

### 28.4 BBS 侧不变

* 影片里播放、BBS 的 F6 导出**永远按 BBS 模式**渲染；AE 模式只影响合成器自己的预览与渲染队列。
* `BBS 预览` 图层两种模式都工作；AE 导出时是最后一帧静态画面。
* 工程文件共用；老工程没有 `encoder` 字段 → 默认 bbs（`CompEncoderModeTest` 钉住）。

### 28.5 验证入口

```bash
./gradlew runClientView -PbbsaeView=curve -PbbsaeEncoder=true   # 强制 AE 管线
```

日志会打印 `frame server: f<N> <ms> ms (cache ..., hit ..., miss ...)`：
同一帧只渲染一次，说明预览确实在走帧服务器。

### 28.6 产物

- `bbsae-0.8.0.jar` 593308 字节
- SHA-256 `dbafe7b2164c3ab64e0c3cbbd50e7a6aa2bfbaf83f68e22cc9638bc67ad5ab5b`
- `test` **143/143**

---

## 29. 2026-10-31 第二十轮：AE 管线四块短板（0.9.0）

详见 `docs/ROUND20-AE-PIPELINE.zh.md`。

### 29.1 磁盘帧缓存是"内容寻址"，不是"脏标记"

`CompFrameDisk`：文件名 = `<签名>_<帧>_<w>x<h>.rgba`，签名来自 `CompRenderCache.signature`。
**不要**给它加"失效/清理标记"——内容变了就是另一个文件，旧文件永远不可能被错误命中。
里面是**原始 ARGB int**（top-down），载入时按行翻成 bottom-up 再上传；中间不要插 PNG 编解码
（1080p ImageIO 比简单合成还慢）。只写 ≥45ms 的帧，512MB 上限，mtime 淘汰到 80%。

### 29.2 GL 只能在渲染线程，"渲染农场"= 每 tick 预算 + 原始帧管道

不要试图开共享 GL 上下文的后台线程。真正的提速来自：

* `FRAME_BUDGET_MS = 22`、`MAX_FRAMES_PER_TICK = 6`（旧实现每 tick 一帧 = 20fps 上限）；
* 全尺寸视频走 `ffmpeg -f rawvideo ... -i pipe:0`，帧经 `GlFramebuffer.readTopDownRgba()`
  （`readRawBytes` + 按行翻转，不换通道）直接写 stdin，**不再每帧 PNG**。
* `CompExportManager.usesPipe()` 的四个条件缺一不可：视频、非 PNG、非保留帧、输出尺寸 == 合成尺寸。
  缩放预设必须走 PNG（需要重采样），否则就是"导出和预览不是一张图"。

### 29.3 预览音频以播放头为准

`CompAudioPlayer`：worker 线程混音 → 客户端线程建 `SoundBuffer` → BBS OpenAL 播放。
**只有** `UICompositorPanel.syncPreviewAudio()` 一个入口，由 `(playing, AE, 有音频, 开关)` 推导；
`sync(time)` 漂移 >250ms 或循环回卷才 seek。不要在 `togglePlay/pause/shuttle` 里各写一份。

### 29.4 代理分辨率必须显式

`settings.encoderProxy` 只影响 AE 预览；帧缓存键包含它；**导出永远 1/1**；
徽标必须显示 `代理 1/2`。任何"偷偷降分辨率"的优化都会变成"预览糊、导出锐"的 bug 报告。

### 29.5 产物

- `bbsae-0.9.0.jar` 609542 字节
- SHA-256 `f9a4907e03b90295cab4728920ca13206cba3a911f03e932536c0fae8a13cefd`
- `test` **156/156**

---

## 30. 2026-10-31 第二十一轮：轨道缩放 / 音频与视频轨道（0.10.0）

### 30.1 `Scale.zoom` 是加法，单位 px/s

`xAxis.zoom(delta, min, max)` = `setZoom(getZoom() + delta)`，不是倍数。**任何**缩放都必须
`target = current * 1.35^steps` 再传 `target - current`（见 `UICompTrackTimeline.zoomTarget`）。
键盘 `=` / `-`、`Ctrl/Alt + 滚轮` 全部走 `zoomAt`，不要再直接调 `animateZoom(anchor, ±1)`。
上下限用 0.05…200000（默认 0.01…1000 对 1 秒合成不够）。
`CompTimelineZoomScaleTest` 用 BBS 真 `Scale` 钉住这个单位。

### 30.2 时间轴的行数只有一个来源

`rowCount() = rows().size() + (hasAudioRow() ? 1 : 0)`。音频轨道是**虚拟行**（`audioRowIndex()`），
与图层共用行高/滚动/时间轴。新增行类型时必须同时改：`rowAt`、`clampScroll`、`renderRows`、
`clickLane` 的分派、`reorderTarget`（音频行不接受图层拖放）。
音频片段的绘制长度 = `clip.length(comp.duration)`；导入时用 `probeDuration` 写入真实时长，
否则"时间轴画的"和"混音放的"会各算一套。

### 30.3 视频=拆帧，不是解码器

`CompVideoImport`：FFmpeg `fps=<合成帧率>,scale=w:h` → `assets/frames/<name>/frame_%04d.png`
→ `footage` 图层。**不要**引入流式解码：合成器必须能任意顺序取第 N 帧。
信框缩放（不裁剪）、最长 60 秒、名字冲突加 `_2`、worker 线程拆帧 + 客户端线程建图层、
工程切走则丢弃。

### 30.4 布局下限

`applyLayout()` 第二次钳制：左右栏按比例缩，保证预览 ≥160px。第一段钳制是各栏独立的，
在 ~360 逻辑像素宽时预览会变成负数（元素宽度 ≤0 → 整块不画）。

### 30.5 产物

- `bbsae-0.10.0.jar` 623996 字节
- SHA-256 `213a21e0849a733d69c915a56df37302f2aac00c0310e1c22f745aea3f756218`
- `test` **164/164**

---

## 31. 2026-10-31 第二十二轮：3D 视口（Blender 式自由摄像机，0.11.0）

详见 `docs/ROUND22-CAMERA-VIEWPORT.zh.md`。

### 31.1 摄像机分成两个，不是一个

| 类 | 回答的问题 | 进成片 |
| --- | --- | --- |
| `CompCamera` | 这一帧镜头看到什么 | 是 |
| `CompViewportCamera` | 编辑器现在站在哪里看 | 否 |

`viewportMode`（`settings.viewport3D`）= 自由视口；`cameraPreviewMode` = **看穿摄像机**（最终画面）。
两者不是同一个东西，**不要**再把它们合并：旧实现把"导航"实现成"移动成片镜头"，于是每次鼠标移动
都会改文档 → 指纹变 → 整条渲染图重跑，这就是"卡得根本用不了"的根。

`CompViewportCamera` 的状态是 **target + yaw/pitch/distance + fov + ortho**。
`update()` 用字段比较判断是否需要重算矩阵（不要只依赖 `matricesDirty`：`ortho` 这类公开字段
可以被 UI 直接改，第一版就因此"切正交没反应"）。

### 31.2 视口渲染必须进自己的 FBO

`CompViewportRenderer.draw(...)`：

1. `renderScene(...)` 把舞台 + 网格 + 图层四边形画进自己的 `GlFramebuffer`
   （`GlStateGuard.ortho(width, height)` + 单位 modelview，坐标就是 0..w / 0..h）；
2. 用 `Batcher2D.texturedBox` 把那张图贴回面板（和合成预览同一条"一帧如何到达面板"的路）。

**不要**把三角形直接提交到 UI 帧里。实测（探针打印 `fbo=`）会画进 FBO 1 而不是屏幕：
batcher 画的准星/图标全对，自己画的四边形全不见，且没有任何 GL 错误。

### 31.3 源纹理按"内容指纹"缓存

`CompLayerSourceCache`：图层本地坐标栅格化，最长边 1024 px，48 MB 预算，LRU。
指纹**只含**会改变像素的字段（文字/字号/颜色/图片/形状/文字动画器/当前帧），
**不含** x/y/rotation/scale/opacity/blendMode —— 那些由四边形施加。

* 转视口 / 拖图层 = 0 次栅格化；
* 改文字/形状 = 1 层栅格化；
* 预合成在草稿里画"板子"，不递归整图。

栅格化时要把 **batcher 的矩阵栈**也重置成单位阵（只重置 `RenderSystem` 的 modelview 不够，
面板的平移会再叠一次）。

### 31.4 透视四边形：分格 + 预乘

每层按屏幕对角线切 1..8 格（/110px），每格两个三角形用投影后的角，UV 取本地矩形。
平面在透视下是单应变换，分格后的仿射近似误差 <1px；整层仍是一次 draw call。
图层内容是**预乘**的（`SRC_ALPHA` 画到透明底），所以四边形按预乘合成（顶点 alpha 同时缩放 RGB/A），
**不要**再对每条图层跑 unpremultiply。

### 31.5 交互（Blender 键位）

中键环绕、Shift+中键平移、滚轮朝光标缩放；数字键盘 0 看穿/自由、1/3/7 前/右/顶（Ctrl 反向）、
5 正交、. 框住所选、Ctrl+Home 框住全部；拖摄像机本体在"过眼睛且平行于屏幕"的平面上移动
（Shift = 沿视线推拉），拖准星在目标 Z 平面重新瞄准；Ctrl+滚轮推拉镜头，Alt+滚轮改 FOV。

所有拖动都从**按下时的快照**算绝对量（`vpStartEye` / `vpStartTarget` / `dragStarts`）。
用"每帧增量"会让环绕加速、让 3D 图层的 z 每帧再加一次。

### 31.6 顺手修掉的两个真 bug

* `line()` / `coarseLine()` 原来每 2px 铺一个方块（一条 700px 视锥边 = 350 个四边形，
  网格每帧上万）。现在一次矩阵旋转 + 一个四边形。
* `CompFileDrop.install()` 在 `BBSClientReadyEvent` 里 `client.getWindow().getHandle()` 会 NPE
  （BBS 从 `onInitializeClient` 发这个事件，窗口还不存在），异常会中断 `onClientReady` 后面
  的全部注册（预览桥、导出 tick）。已改为窗口为空时直接返回。

### 31.7 验证入口

```bash
gradle.bat runClientView -PbbsaeView=camera       -Dbbsae.debugShot=viewport  -Dbbsae.debugShotTicks=210
gradle.bat runClientView -PbbsaeView=cameralook   -Dbbsae.debugShot=cameralook -Dbbsae.debugShotTicks=200
```

* `-Dbbsae.debugViewport=true`：每秒打印 `viewport: N layers … [x0,y0 x1,y1]`（第一个可见图层
  投影后的屏幕矩形 —— 用来区分"几何跑出屏幕"和"没画出来"）。
* `-Dbbsae.debugViewLock=true`：禁用视口鼠标/键盘输入，并强制每次进入都重新取景，
  用于可复现的截图（窗口在前台时会收到真实鼠标输入，否则截图姿态随机）。
* `-Dbbsae.debugShot=<name>` + `-Dbbsae.debugShotTicks=N`：第 N tick 存一张截图到 `run/screenshots/`。
* `debugView` 现在**只应用一次**（多视图列表仍按 8 秒轮换），避免反复进入/退出 3D 空间。

### 31.8 产物

- `bbsae-0.11.0.jar` 671320 字节
- SHA-256 `84103a0d6f8f5da5416596a531214001a9869a90de92ffa37ada801c44b787fa`
- `test` **174/174**
- 截图：`docs/verify/round22-viewport.png`、`docs/verify/round22-cameralook.png`

---

## 32. 2026-10-31 第二十三轮：视口可用性、3D 叠放与稳定性（0.12.0）

详见 `docs/ROUND23-VIEWPORT-FIXES.zh.md`。

### 32.1 右键才是主操作

`subMouseClicked` 在 3D 空间里优先消费**右键**：右键拖动环绕、Shift+右键平移、中键同效。
看穿摄像机状态下右键拖动会**先退出看穿**再环绕（Blender 行为）。中键在很多笔记本上不存在，
只接中键等于"视角动不了"。

### 32.2 摄像机 gizmo

* **每一台可见摄像机**都画 gizmo，激活的那台颜色正常、其余压暗；只画激活的会让第二台摄像机
  在视口里彻底消失。
* 进入 3D 空间时**先框住合成（画面居中），再只放宽距离到摄像机也进入画面**（最多 10 次迭代）。
  不要用"合成 ∪ 摄像机"的包围盒：它会把合成推到画面一侧。

### 32.3 效果必须在图层本地空间烘焙

`CompLayerSourceCache.applyEffects` 用 `EffectSpace.ofBounds(layer, world, ctx, rect…)` 显式给出
"本地像素 → 栅格像素"的映射（`world = scale(s)·translate(-x0,-y0)`），代理合成尺寸 = 栅格尺寸。
`EffectSpace.of` 对 3D 图层返回合成空间，那是给"在合成空间栅格化"的成片管线用的；
视口在图层空间栅格化，必须用本地映射，否则渐变色会随着转视角在四边形上滑动。

`EffectRenderer` 新增了接受 `EffectSpace` 的重载（原 5 参重载委托给它）。

**跳过**：`depth_*`、`shake`。蒙版/轨道遮罩/混合模式仍不参与草稿。

### 32.4 BBS 预览图层的天空

世界帧的 alpha 通道没有语义（天空处常为 0），`drawBbsPreview` 之前把它当透明度用 → 天空变黑。
现在 tint 强制 `| 0xFF000000`。**一帧影片没有透明通道。**

### 32.5 3D 叠放：平面深度，不是原点/角点

`CompScene.planeDepth(ctx, layer)`：

```
t = dot(origin - eye, n) / dot(f, n)   // n = 图层世界 Z 轴
planeDepth = -t                        // 相机空间 z，越远越负
```

* 共面图层（z=0 的绝大多数）**严格相等** → 堆栈顺序决定，和合成器一致；
* z>0 的图层从任何角度都排在前；
* 边缘视角回退到原点深度。

视口对**所有**图层按它排序（同深度用堆栈序）；`CompRenderer.sortSegment` 的 3D 槽位也用同一个键，
**预览与导出的前后关系必须一致**。

### 32.6 首次打开的空白条

`applyLayout()` 只在 `bind()` 跑一次，那时 `area` 还没有真实尺寸，`body` 用 160px 下限、
时间轴贴底 → 中间一条空白，拖一下分栏才恢复。现在 `render()` 比较 `area` 尺寸，变了就重排。

### 32.7 播放闪烁：三处状态/生命周期错误

1. **UI 顶点被画进场景 FBO**：绑定场景 FBO 之后才 flush 面板批次，会把本帧已排队的 UI 顶点
   画进 3D 纹理。现在 `CompViewportRenderer.draw` **先 flush** 再绑定。
2. **每帧申请/归还场景 FBO**：改成持久目标（尺寸变化才换），**不能 release**（否则下一层会拿到
   正在被采样的纹理）。
3. **FBO 双重释放**：`applyEffects` 自己释放输入，调用方又释放一次 → 同一个 framebuffer 进池两次。
   `FboPool.release` 现在**永久带重复释放守卫**（拒绝 + warning，`-Dbbsae.debugFbo` 附堆栈）。

### 32.8 源纹理指纹：帧号只在内容随时间变化时才计入

`isTimeDependent(layer)`：`footage`、非空 `textAnimators`、或 `animatedPaths()` 里有 `text/`、
`shape/`、`fx/` 前缀的通道。否则**不混帧号** —— 静态图层播放时重建次数为 0。
动画图层用 `staging + straight` 双目标**原地重绘**，播放期间分配次数为 0（带效果的图层除外）。

### 32.9 运动轨迹

激活摄像机沿 `CompCamera.eyeOf()` 采样 40 段折线 + 每个 transform 关键帧一个点。
轨迹是**读**动画，不存第二份路径。选中高亮、未选中压暗。

### 32.10 产物

- `bbsae-0.12.0.jar` 676590 字节
- SHA-256 `a86eabec779ed73480757bca5ec766db408f78b5b7e430fa4bb6f6e9f1a3adb7`
- `test` **175/175**
- 截图：`docs/verify/round23-viewport.png`

---

## 33. 2026-10-31 第二十四轮：视口输入、图层空间效果、蒙版、缩略图、快捷面板（0.13.0）

详见 `docs/ROUND24-VIEWPORT-MASK-QUICKPANEL.zh.md`。

### 33.1 视口输入必须轮询，不能只靠 UI 事件

`subMouseClicked` 在 BBS 里会被上下文菜单等路径吃掉，右键拖动因此完全不可用。现在
`pollViewportInput()` 每帧直接读 `Window.isMouseButtonPressed(1/2)`（GLFW：1=右键、2=中键），
在预览区内按下即开始 `ORBIT/PAN`，`Shift` = 平移，看穿状态下按下先退出看穿。

* 事件路径仍然消费 1/2（阻止右键菜单），但不作为唯一入口；
* `beginViewportDrag` 里"已在拖拽则忽略"，防止轮询与事件重复触发重置起点。

**不要**把导航只挂在任何 UI 事件上。

### 33.2 效果属于图层，不属于合成（成片管线也改了）

`EffectSpace.of()` 对 3D 图层返回合成空间，是"在合成空间栅格化"的实现细节。它让渐变跟着画面
而不是跟着图层 —— 转视角就滑。

现在 `CompRenderer.renderBaked3D()`：3D 图层若带效果，取 `CompLayerSourceCache` 的**图层空间栅格**
（已含效果），按 `model · projection` 用 8×8 网格贴到合成上，并跳过该层的效果 pass。
草稿、看穿、导出三处一致。

### 33.3 蒙版 / 排除 = 两个图层的集合运算

* 语义：`蒙版 = A ∩ B`（ALPHA 遮罩）、`排除 = A − B`（ALPHA_INVERTED）；
* 交互：形状层放在目标层**上方**，选中形状层 → 检查器"用作蒙版（交集）/ 用作排除（差集）"；
  目标 = `layers.indexOf(source) - 1`（列表 0 是最底层）；形状层自动 `guide + visible=false`；
* 草稿：在**源纹理构建时**把形状层栅格按 `inv(W_target)·W_matte` 画进目标栅格，
  用 `glBlendFunc(GL_ZERO, GL_SRC_ALPHA)`（交集）/ `GL_ZERO, GL_ONE_MINUS_SRC_ALPHA`（差集）；
* **投影必须是内容那一套**：`ortho(rect.x·s … rect.y·s)`，不是 `ortho(0..w)`，
  否则形状会落到栅格角落（第一版就是这样，蒙版看起来"没生效"）；
* 指纹里要混入两层的位移/旋转/缩放与形状层的内容签名，否则改形状不刷新。

### 33.4 效果库缩略图（AM 式）

每行左缩进 26px，在缩进里画 16×16 缩略图，图来自 `CompEffectPreview.frame(type)`（96×54 测试卡，
按类型+参数缓存）。行几何用列表自己的 `visible()` / `areaOf(i, cell)`，**并且 y 要减去
`scroll.getScroll()`**：`areaOf` 返回内容坐标（x 从 0 开始），直接用会画到面板外。

### 33.5 图层快捷面板

`UILayerQuickPanel` 停靠时间轴右侧：X/Y/Z/旋转/缩放/不透明度 + 每行 ◆ 打关键帧。
宽度 160–340 可拖，存 `settings.quickPanelWidth`；可见时 `applyLayout` 把时间轴宽度减去它。
数值回填只在"选中层或播放头变化"时做，输入中不会被覆盖。

### 33.6 草稿蒙版暂缓

`CompLayerSourceCache.DRAFT_MATTE = false`。草稿在源纹理里做交集/差集时，两层栅格尺寸不同会让
采样映射不精确、边缘出现噪点。**成片/看穿/导出**的蒙版是正确的（走原来的轨道遮罩），
草稿宁可先不显示。下一轮要么把两层栅格统一尺寸，要么在视口里改用模板缓冲。

### 33.7 产物

- `bbsae-0.13.2.jar` 689438 字节
- SHA-256 `a7121ce7b3ef900de45fb94307149a5d587680584746805e4c9ab0c232e5e306`
- `test` **175/175**
- 截图：`docs/verify/round24-viewport.png`

### 33.8 0.13.1 补充：手势必须在任何视图里都成立

实测（`-Dbbsae.debugViewport`）证明轮询有效：一次运行 26 次 `drag start button=1`，yaw 31 → -1058。
"不能环绕"的真实原因是**当时不在 3D 视口**（2D 与 3D 的预览长得一样）。

所以右键拖动改为**任何视图都生效**：2D 里先 `enter3DSpace()` 再环绕；
3D 里左键拖空白 / Alt+左键也环绕；键盘 A/D·W/S·Q/E 兜底；
2D 右下角常驻提示。诊断开关：`bbsae.debugOrbit=<度/帧>`（无鼠标也能转，用来区分输入/渲染）。

### 33.9 0.13.2：烘焙 3D 图层时 `texturedBox` 的 UV 上下角

`Batcher2D.texturedBox` 的 UV 参数是**两个角**（左上、右下，顺序与四角一致）。
`renderBaked3D` 里写成了 `(u0, vBottom, u1, vTop)`，于是 3D 图层的烘焙纹理**垂直翻转**：
横向渐变看不出，纵向/斜向渐变颜色颠倒、文字倒置。

正确写法 `(u0, vTop, u1, vBottom)`。`layerUv` 的局部 v 是 **y 向下**（由 `vz` 的符号可反推），
所以角度 90 时顶部是 ColorA —— `gradtest`/`gradlook` 两个调试视图就是钉这条的，
截图 `docs/verify/gradV-draft.png` 与 `gradV-look.png` 必须都是"上红下蓝"。

---

## 34. 2026-10-31 第二十五轮：回放实体图层（深度抠像）与系统文件对话框（0.14.0）

> 完整论述见 `docs/ROUND25-ENTITY-KEY-IMPORT.zh.md`。

### 34.1 回放实体图层：为什么必须二次渲染

世界帧里没有"这个像素属于谁"。按颜色抠在白盔甲/白墙、任意光影、半透明贴图下都会失败。
唯一稳定的身份信号是几何：在 `AFTER_ENTITIES`（BBS 画完影片实体之后）把选中的回放**再渲染一遍**
到只有深度的目标，然后

```
像素属于实体 ⟺ |linearDepth(entityDepth) − linearDepth(worldDepth)| < 容差
```

* 方块挡在前面 → 世界深度更近 → 不相等 → 抠掉；未勾选的实体挡在前面同理。遮挡是深度比较的副产品。
* 全程不比较颜色，所以换光影/材质不影响。
* 比较在**视空间（方块）**做，不在 NDC：远平面 1 个 NDC 单位是几百格。

关键实现点（改动别踩）：

| 位置 | 约束 |
| --- | --- |
| `ReplayEntityMask.render` | 必须挂在 `AFTER_ENTITIES` 且**在 `renderFilmActors` 之后**：同相机、同 pose stack、同 transition，深度才会逐像素重合 |
| `transitionOf` | `controller.paused` 时取 0，与 BBS `getTransition` 一致；否则轮廓多一圈 |
| `FormRenderLast.suspend()` | 必须包住整个 pass，否则 `render_last` 表单落不进遮罩 |
| `ensureTarget` | 尺寸必须等于 `mc.getFramebuffer().textureWidth/Height`，与 `SceneColor`/`SceneDepth` 对齐 |
| `ReplayEntityKey` | 逆 MVP 反投影 + `suv.y = 1 - uv.y`；输出**预乘** alpha（管线后面统一 un-premultiply） |
| `CompRenderer.renderCached` | 含实体/BBS 预览层的合成必须 `liveWorld` 跳过内容缓存，否则画面被冻住 |
| `CompositionOverlay` | `ensureCurrent` 用保存的相机懒渲染，保证导出第 0 帧有 matte |

3D（透视）实体图层不抠，退化为世界底板 —— matte 是屏幕空间属性，不能贴到任意平面。

### 34.2 文件导入：tinyfd 的静默失败

BBS `UIFileDialogs` 在工作线程调 tinyfd，**没有 owner 窗口**：全屏/无边框下可能开在游戏后面，
平台拒绝时直接返回 null，回调不触发 —— 按钮看起来是死的。

`NativeFileDialog` 用 `java.awt.FileDialog`（原生对话框）+ 1×1 always-on-top owner frame，
工作线程弹出；`onPick` / `onUnavailable` 两路回调把"取消"和"不可用"分开；
AWT 不可用才回落到游戏内浏览器。图片/视频/音频/字体/素材库五个入口 + 浏览器里的
"系统对话框…"全部改走它。

### 34.3 验证

* `ReplayEntityKeyPixelTest`（4 个像素级用例）：抠出、遮挡、收缩/羽化、世界帧方向。
* 实机：`replays=2 rendered=1`，遮罩覆盖 ~15 万像素；洋红舞台验证图只剩角色（洋红仅存在于截图，未进代码）。
* 测试合计 **182/182**；产物 `bbsae-0.14.1.jar`（封面与作者署名随后补入）。

---

## 35. 2026-10-31 第二十六轮：文件导入、图层库、真正的 3D 摄像机（0.15.0）

> 完整论述见 `docs/ROUND26-IMPORT-LIBRARY-CAMERA.zh.md`。改动前的完整备份在
> `_backup/v0.14.1-20261031/`（git bundle + 源码包）。

### 35.1 文件对话框：owner 才是关键

tinyfd 与 AWT 都是**无 owner** 的原生窗口，在无边框全屏下可能开在游戏后面：对话框创建了、
回调没触发、日志里没有错误 —— 按钮看起来是死的。

`Win32FileDialog` 用 JNA 调 `GetOpenFileNameW`，`hwndOwner` = `glfwGetWin32Window(handle)`。
owned window 永远在 owner 之上，这不是"尽量置顶"而是窗口管理器的不变量。JNA 由 Minecraft 自带。

三级回退：Win32 → AWT → 游戏内浏览器；**只有抛异常才算"不可用"**，返回 null 是"用户取消"。

验证方式：单独编一个 `main` 跑起来，`ffmpeg -f gdigrab` 截桌面，截图里是真正的系统对话框。
结构体错一个字段这里就会崩 —— 所以这一步同时验证了 ABI。

### 35.2 音效：事件不是路径

`SoundImport.materialize(id)`：事件 → `SoundManager.get` → `WeightedSoundSet.getSound(Random)`
→ `getLocation()` → 原版 `ResourceManager`；显式 `.ogg` 先试 `BBSMod.getProvider().getAsset`，
再试原版。落到临时文件后交给既有的 `addAudioClip`，于是预览/导出/WAV 读同一个文件。

### 35.3 图层库

`LayerCatalog`（id/名称/说明/分类/图标）+ `UILayerLibrary`（搜索 + 分类 + 卡片网格）。
`LAYER_TYPES`/`LAYER_GROUPS` 已删除，UI 与渲染器共用一份目录数据；`LayerCatalogTest` 守住。
"+" 按钮直接打开库；卡片文字按真实字宽换行**并**额外 clip（中文回退字体的 advance width
不可靠，只靠测量仍会溢出）。

### 35.4 3D 摄像机：仿射表达不了透视

摄像机本来就是 3D 的，问题在图层：非 3D 图层走 `view2D`（仿射），倾斜对它是 no-op。

关键洞察：一个 2D 图层的**成品**（内容+蒙版+效果+样式）就是 comp 空间里的一张图，也就是
z=0 平面上的一块平面；平面可以被透视投影。于是空间模式下：

```
model = world(layer)            // 不含相机
蒙版/效果/样式 照常在 comp 空间完成
最后把成品图按 z 平面投影一次
```

`CompScene.spatial(ctx)` = `camera.perspective && (camera.tilted || 任一图层 |z|>0)`，
按帧缓存。直相机仍走 `view2D`，零回归零额外开销。摄像机层的 z 不算"图层深度"（那是眼点位置）。
空间模式下平坦图层与 3D 图层一起按平面深度排序（同 z 稳定，保持堆叠顺序）。

手势：Alt+拖动 = 环绕（球面，俯仰 ±85° 防极点），其余不变。

---

## 36. 2026-11-01 第二十七轮：预览时钟 / 原版曲线 / 3D XYZ / 命名（0.16.0）

> 完整论述见 `docs/ROUND27-PLAYBACK-CURVES-3D.zh.md`。本轮先合入外部交付的 0.14.2
> 修复（导出管道/音频/关键帧选择/GL 深度纹理/原生对话框/数值落盘），再做四项用户修复。

### 36.1 预览速度：tickDelta 不是帧间隔

`UICompositorPanel.startRenderFrame(tickDelta)` 每帧加 `tickDelta / 20`。BBS 传进来的是
`MinecraftClient.getTickDelta()`，即"当前客户端 tick 已过去百分之几"，是每 tick 归零的
锯齿波，不是上一帧时长。逐帧累加的结果取决于刷新率：30 fps ≈ 0.5×、60 fps ≈ 1×、
90 fps ≈ 2.25×、144 fps ≈ 3.6×。这正是"合成器预览比 BBS 原版快"的根因。

现在 `CompTime.playbackDelta(previousNanos, nowNanos)` 用 `System.nanoTime()` 推进真实秒数，
夹在 `[0, 0.5s]`；`lastPlayNanos` 在暂停/定位/变速/切页时清零。BBS 自己的播放器是 20 tick/s，
所以两边现在是同一时间尺度。`CompTimeTest` 复现旧公式并验证 30/60/90/144/240 fps 都等于 1×。

另外，`startRenderFrame` 在世界渲染开始前被调用，而 `BbsPreviewBridge.sync` 原来在预览控件
的 render 里才执行，世界帧因此晚一帧。现在推进时间后立刻 sync；演员的 transition 也改用
`runner.getCursor(0F)` 的小数部分，而不是 `MinecraftClient.getTickDelta()`。

### 36.2 效果/片段选择界面的英文串

BBS `UIKeys.C_CLIP` 的键是 `bbs.ui.camera.clips.<link>`，缺失时回退为键本身。之前菜单里
显示 `bbs.ui.camera.clips.bbsae:composition`。`BbsAeClient` 在 `RegisterL10nEvent` 中写
`LangKey.content = "合成器"`（`getKey(key, fallback)` 不覆盖已存在的键，必须直接写 content），
并在 `onClientReady` 兜底重写；`CompositionClip` 新片段默认标题也改为"合成器"。

### 36.3 3D XYZ + BBS 原生插值曲线

- `UILayerQuickPanel` 按 `layer.threeD` 自动在 2D 五栏和 3D 十栏之间切换：位置 X/Y/Z、
  旋转 X/Y/Z、缩放 X/Y/Z、不透明度；位置/旋转/缩放各一个 ◆ 组键。
- 关键帧抽屉标题栏和快捷面板标题栏都有“插值曲线”，打开 BBS 自己的
  `UIInterpolationContextMenu`（曲线预览、全部图标、v1–v4、复制粘贴），通过
  `IUIKeyframeGraph.setInterpolation()` 应用到所有选中键；没有选中时自动选播放头所在键。
- 防回归截图：`docs/verify/round27-quick3d.png`、`round27-curve-menu.png`。

### 36.4 同源标量缺陷

1. ◆ 首键被 `hasScalarChannel()` 禁用：Z/俯仰/偏航/Z 缩放/挤压/相机距离/目标/FOV 永远
   打不了第一颗键。现在所有路径都可创建首键。
2. `CompLayer.scalar()` 未覆盖 `putScalar()` 的全部字段，`addScalarKey` 读到 0：点“距离 Z”
   的 ◆ 会把相机键到 0。现在覆盖 z/rotationX/Y/scaleZ/squash/cameraZ/cameraTargetX/Y/Z/
   cameraFov/cameraTargetX/Y，且检查器把当前显示值显式传给 `addScalarKey(layer, path, value)`。
3. `quickKeyZ` 同样走 `scalarValue`，一起修。

验证：`test` **191/191**；实机 1920×1080 截图与日志（两个词条均解析为“合成器”）通过。

---

## 37. 2026-11-01 第二十八轮：素材库滑块 / AE 曲线专业级 / 插件图标（0.17.0）

> 完整论述见 `docs/ROUND28-ASSETS-CURVES-ICON.zh.md`。

### 37.1 素材库滚动条

`UIAssetBrowser.Grid` 的滚动条以前只画不处理点击，滑块当然拖不动。现在是 8px 命中区、24px
最小滑块、轨道点击跳转、拖动时轮询物理鼠标按键（拖出控件也能结束）、悬停高亮、grip 线，
网格为它预留 12px。提示改为“滚轮或右侧滑块滑动”。

### 37.2 AE 曲线编辑器：两个根因

1. **变换通道的 key 归属错了**：五个 transform 曲线共享同一个 BBS Keyframe，`owners`
   这个 `IdentityHashMap<Keyframe, Channel>` 被最后写入的“缩放 Y”覆盖。结果拖位置 key 改的是
   缩放 Y，贝塞尔/缓动预设的 selection 过滤为空。现在 transform key 的通道由**当前活动通道**
   决定，标量/不透明度通道仍用一对一 map。
2. **拖手柄把位移当绝对偏移**：旧代码 `rx = max(0.25, dx/pxPerFrame)`，按下 1px 就把手柄塌缩
   到 0.25/0。现在按下时快照 `rx/ry/lx/ly`，拖动加增量，并按相邻段长度 clamp；默认镜像、Alt
   断开、状态栏显示影响/速度。
3. 默认 ±5 帧手柄在正常缩放下不可见：Bezier 预设展开为 33.3% 相邻段影响；值域适配把手柄
   也算进去，拉高的切线不再被裁掉。
4. 专业操作补齐：中键/Space 双轴平移、右键插值菜单、方向键 1 帧/1 像素（Shift ×10）、选中
   键读数卡、手柄 10px 命中。

### 37.3 插件图标

`fabric.mod.json` 原来没有 `icon`，Mod Menu 显示占位图。现在用项目原有 32×32 图标（超级大布叮）
生成 128×128 `assets/bbsae/icon.png`（深色圆角底 + 白色播放框），metadata 指向它；BBS 内的
16×16 仍用原图。`IconResourceTest` 同时校验两个文件。

验证：`test` **193/193**；截图 `docs/verify/round28-curve2.png`、`round28-assets.png`；新
README 主图 `docs/gallery/hero.png`、`editor-overview.png`、`timeline.png`。


---

## 38. 2026-11-01 第二十九轮：新手引导 / BBS 风格设置 / 可编辑快捷键 / 全局动效（0.18.0）

> 完整论述见 `docs/ROUND29-UI-MOTION-ONBOARDING.zh.md`。

### 38.1 新增模块

- `UiMotion`：每宿主时钟 + 通道（hover/selection/scroll/intro/flash/breathe），缓动
  `easeOut/easeInOut/easeOutBack/easeOutElastic/easeOutExpo`；所有采样读 `CompPrefs`，设置即时生效。
- `UiGfx`：渐变、柔影、强调轨、光晕；只用 `Batcher2D`，不改 GL 状态。
- `UiBackdrop`：BBS landing 风格 3 灯 + 48 微粒 + 指针柔光，供 onboarding/start screen。
- `CompPrefs`：`config/bbsae/prefs.json` 全局偏好（引导、动效、布局、快捷键覆盖）。
- `CompKeybinds`：命令表 + 两套默认 + 用户覆盖 + 录制/清除/冲突/重置。
- `UIOnboarding`：5 页引导（欢迎/布局/动效/快捷键/完成），支持鼠标、方向键、Enter、Esc、圆点跳页。
- `UICompSettings`：BBS 2.7 风格左分类/搜索 + 右滚动设置，含动效、布局、快捷键、项目、高级、引导。

### 38.2 接入点

`UICompButton`（hover 填充/浮起/阴影/选中态）、`UICompTabBar`（滑动下划线）、
`UICompositorPanel`（面板入场/顶部呼吸线/设置和引导时清 tooltip）、`UIStartScreen`（背景/卡片入场）、
`UIAssetBrowser`（逐格入场/平滑滚动/缩略图淡入）、`UILayerLibrary`、`UIEffectLibrary`、
`UILayerQuickPanel`、`UICompTrackTimeline`（选择过渡）、`UICompSettings`、`UIOnboarding`。

### 38.3 快捷键

`subKeyPressed` 已被改为 `CompKeybinds.find(...)` + `performShortcut(action)`；保留方向键/视口/
鼠标滚轮的特殊路径。旧 `Ctrl+K` 拆分、`Ctrl+Alt+K` 帮助、V/A 选择提示作为兼容别名保留。
录制在面板最前与设置浮层各检查一次，避免事件路由差异；清除写入 `cleared=true`，防止默认绑定复活。

### 38.4 验证与版本

- `test` **197/197**（`CompKeybindTest` 4 项 + 原有 193）。
- 实机 1920×1055 截图：`run/screenshots/round29-final-onboarding.png`、`round29-final-settings.png`。
- `docs/gallery/onboarding.png`、`settings.png`、`hero.png` 更新为 0.18.0。
- `gradle.properties` → `0.18.0`；README / GALLERY / features 更新。
