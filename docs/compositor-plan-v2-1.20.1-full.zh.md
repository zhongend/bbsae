# BBS FS 合成器 v2：1.20.1 / 独立编辑器 / 完整小 AE

> 这版不是 v1 的补丁，是针对你三个决定的改写：
> **1.20.1 Fabric**、**独立编辑界面但沿用 BBS UI**、**一开始就冲完整小 AE**。
>
> 调研对象：
> - `Wemppy4/bbs-fs` 的 `1.20.1` 分支（BBS 2.7 / Minecraft 1.20.1 / Addon API v2）
> - `busyflow/bbs-pov-addon`（BBS-POV 1.0.1，353 个 Java 文件，MIT）
>
> 文中源码路径均相对对应仓库根目录。

---

## 0. 先给你结论

### 0.1 1.20.1 能不能做？

**能，而且和 1.20.4 的差别比想象中小。**

我核对了 1.20.1 分支：

- `BBSApi.VERSION = 2`，`RegisterFrameOverlaysEvent`、`RegisterCameraClipsEvent`、`RegisterClipPanelsEvent`、`RegisterDashboardPanelsEvent`、`RegisterKeyframeEditorsEvent`、`RegisterValueWidgetsEvent` 都在。
- `FrameOverlays` 的调用位置、`VideoRecorder` 的 `GL_BGR` 读回、`WorldRenderEvents.LAST` 的录制时机，和 master 一致。
- 1.20.1 分支的 `gradle.properties`：MC `1.20.1`、yarn `1.20.1+build.10`、loader `0.16.14`、Fabric API `0.92.1+1.20.1`、BBS `2.7`、Java 17。
- BBS-POV 已经在 1.20.1 + BBS 2.7 上跑通，它的构建方式（Loom 1.6 + Gradle 8.6 + 本地 BBS jar）可以直接抄。

所以 1.20.1 不是风险，**风险在“完整小 AE”的 UI 和渲染量级**。

### 0.2 独立编辑界面怎么做？

**推荐：把编辑器写成 host-agnostic 的 `UICompositorEditor`，先挂在 BBS Dashboard 的一个独立标签页里；需要 POV 那种“和影片并排”的体验时，再把同一个编辑器挂到 `UIFilmPanel` 的第三个编辑器页签。**

- Dashboard 标签页：走官方 `RegisterDashboardPanelsEvent`，不写 Mixin，最稳，真正的“独立编辑界面”。
- Film 页签：抄 POV 的 `UIFilmPanelPovMixin`，一个很窄的 Mixin，把编辑器塞进 Camera / Replay / Compositor 三页签。不是第一版必须，但很值得做。
- 编辑器本身不依赖宿主：它只认 `CompEditorContext`（工程、合成、时间源、预览源、撤销），宿主只负责“把它放到哪”。

这样既满足“独立”，又保留 POV 式的影片内工作流。

### 0.3 “完整小 AE”是什么量级？

**一句话：不是 3 个月的插件。单人全职、顺利的话 14–18 个月；按现实排期应该按 18–24 个月做预算；兼职 3–5 年；团队 2–3 人 10–14 个月。完整 AE 的长尾永远做不完，也不该做。**

“完整小 AE”我定义为：

> **屏幕空间合成的完整核心**：工程/合成/预合成、图层体系、关键帧/曲线、蒙版、文字/形状、效果、调整层/轨道遮罩/图层样式、表达式、时间重映射、跟踪绑定、深度、渲染队列/多输出/Alpha。
>
> 不包含：完整 3D 渲染器、C4D/Cineware、3D Camera Tracker、AI Roto/内容识别填充、239 个效果、VR、协作、网络渲染。

这不是“先做 HUD 再慢慢长”；这是“从第一天就按完整核心设计”，只是实现必须分波次。**架构可以全量，代码不能一天写完。**

---

## 1. 1.20.1：事实、构建、边界

### 1.1 渲染/导出链路没有变

1.20.1 分支里：

- `BBSRendering.onWorldRenderEnd()` 仍然在 `renderWorld` 返回时调用 `FrameOverlays.render(...)`：`src/client/java/mchorse/bbs_mod/client/BBSRendering.java:411-436`
- `onRenderBeforeScreen()` 仍然把 framebuffer blit 到导出纹理：同文件 `:443-492`
- `BBSModClient` 仍然在 `WorldRenderEvents.LAST` 调 `recordFrame()`：`src/client/java/mchorse/bbs_mod/BBSModClient.java:773-779`
- `VideoRecorder` 仍然 `GL_BGR` 读回：`src/client/java/mchorse/bbs_mod/utils/VideoRecorder.java:354`

**结论：v1 里那条“FrameOverlays → 导出纹理 → ffmpeg”的主链路，在 1.20.1 上原样成立。** P0 仍然要用实际导出验证，但不需要重新论证。

### 1.2 构建：抄 POV，不要抄 BBS 主仓

BBS 主仓和 POV 的构建方式不同：

- POV：Loom 1.6 + Gradle 8.6 + JDK 17 + 本地 BBS jar，在 1.20.1 上已经跑通。
- 当前 `bbsae` 工程：**Loom 1.15.5 + Gradle 9.2 + JDK 21**（Loom 1.15 要求 JVM 21，编译目标仍是 Java 17）。这和环境里已有的 Gradle/Loom 缓存一致，构建最快。
- 两种都能用。下面给的是 POV 式配置，方便理解；实际仓库以 `build.gradle` / `gradle.properties` / `gradle-wrapper.properties` 为准。

```groovy
plugins {
    id 'fabric-loom' version '1.6-SNAPSHOT'
}

dependencies {
    minecraft "com.mojang:minecraft:1.20.1"
    mappings "net.fabricmc:yarn:1.20.1+build.10:v2"
    modImplementation "net.fabricmc:fabric-loader:0.16.14"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.92.1+1.20.1"

    modImplementation files("libs/bbs-2.7-1.20.1.jar")
    // 需要测 Iris 时再加：
    // modCompileOnly files("libs/iris-1.7.2+1.20.1.jar")
}
```

`gradle.properties`：

```properties
minecraft_version=1.20.1
yarn_mappings=1.20.1+build.10
loader_version=0.16.14
fabric_version=0.92.1+1.20.1
bbs_version=2.7-1.20.1
mod_version=0.1.0
```

`fabric.mod.json`：

```json
{
  "schemaVersion": 1,
  "id": "bbscomp",
  "version": "${version}",
  "name": "BBS Compositor",
  "environment": "client",
  "entrypoints": {
    "bbs-client-addon": ["dev.bbscomp.BbsCompClientAddon"]
  },
  "mixins": ["bbscomp.mixins.json"],
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "~1.20.1",
    "java": ">=17",
    "bbs": ">=2.7-1.20.1"
  }
}
```

要点：

- 用 `bbs-client-addon` 入口，`BBSAddonMod` + `@Subscribe` 注册事件，不要像 POV 那样只写 `client` 入口再到处直接调 `BBSMod`。
- `libs/bbs-2.7-1.20.1.jar` 从 Modrinth 下载（BBS FS 2.7-1.20.1）。
- `transitive = false` / 本地 jar 都行；本地 jar 更接近 POV 的验证路径。
- Loom 缓存陷阱：BBS jar 文件名不变时，Loom 可能用旧 remap；POV 用 `fabric.loom.override-loom-version-check=true` + 本地 jar 绕开。Addon 模板里那段“jar 比缓存新就清缓存”的脚本也可以抄。
- P0 没有 Mixin 时，`fabric.mod.json` 里的 `"mixins"` 要么删掉，要么放一个空的 `bbscomp.mixins.json`；不要留一个不存在的配置路径。
- `gradle.properties` 建议加 `fabric.loom.override-loom-version-check=true` 和 `org.gradle.jvmargs=-Xmx2G`。

### 1.3 1.20.1 的额外注意

- Iris 1.7.2 的 depth/ framebuffer 行为和 1.20.4 不完全一样；POV 的 `PovHandDepthManager` 里有大量“Iris 开/关”分支，说明这块要实测。
- 1.20.1 的 `DrawContext`/`RenderSystem` 与 1.20.4 有差异，但 BBS 自己已经抹平；Addon 只要不碰 vanilla 渲染细节，影响不大。
- 1.20.1 的 Fabric API 是 `0.92.x`，不要用 1.20.4 的 `0.91.3`。
- 目标 Java 17；不要用 21 的语言特性。

---

## 2. BBS-POV 拆解：抄什么，不抄什么

POV 是 1.20.1 + BBS 2.7 的成熟 addon，353 个 Java 文件，MIT。它是最好的“BBS 内独立编辑器”参考，但不是架构模板。

### 2.1 POV 是怎么做的

- **入口**：`PovAddon implements ClientModInitializer`，`environment: client`，不用 BBS Addon API。
- **注册**：直接 `BBSMod.getFactoryCameraClips().register(...)`、`UIClip.register(...)`、`UIKeyframeFactory.register(...)`。
- **编辑器**：`UIPovEditor extends UIElement`，通过 Mixin 注入 `UIFilmPanel` 的 `<init>`：
  - `this.main.add(editor)`、`this.panels.add(editor)`；
  - `panel.actions().editor(icon, ...)` 加一个入口图标；
  - `editor.full(cameraEditor)`，占据 Camera 编辑器同样的区域；
  - 内部用 `UIClipsPanel` + `UIKeyframeEditor` + `UIIcon` 页签 + `UILabel`，全是 BBS 原生控件。
- **数据**：自定义 `PovCameraClip` / `PovActionClip`，自定义 keyframe factory，稳定 ID，保存格式冻结。
- **渲染**：大量 Mixin（`InGameHudPovMixin`、`GameRendererHandPovMixin`、`BBSRenderingPovMixin`、`FilmStencilPickerPovMixin`）+ 自定义 FBO/深度管理（`PovHandDepthManager`）。
- **构建**：Loom 1.6 + Gradle 8.6 + `libs/bbs-2.7-1.20.1.jar`。

### 2.2 值得抄的

1. **编辑器作为 `UIFilmPanel` 的第三个页签**：Camera / Replay / POV 的交互，对合成器同样成立（Camera / Replay / Compositor）。
2. **BBS 原生控件组合**：`UIIcon` 页签、`UILabel`、`UIClipsPanel`、`UIKeyframeEditor`、`UIOverlayPanel`、`UITrackpad`、`UIButton`、`UIToggle`。
3. **自己的时间轴 + 关键帧编辑器**：POV 没有硬塞进 BBS 的 Replay 时间轴，而是自己做了一个 `UIClipsPanel` 子类 + `UIKeyframeEditor`。合成器也需要自己的 comp 时间轴。
4. **稳定 ID + 保存格式冻结**：POV 明确“不要改 clip id”，这是 addon 数据能活过版本更新的关键。
5. **本地 BBS jar 的构建方式**：1.20.1 上最省事。
6. **深度 FBO 的管理方式**：`PovHandDepthManager` 里的 `SimpleFramebuffer` + `glBlitFramebuffer` + Iris 分支，是深度 pass 的直接参考。

### 2.3 不要抄的

1. **到处直接调 BBS 内部类**：POV 有 `integration/access/bbs` 一层访问器，但仍然散。我们要把内部访问收进一个 `BbsCompCompat`。
2. **用 Mixin 画 HUD**：POV 需要拦截 vanilla HUD，所以 Mixin 多。我们的合成层用 `FrameOverlays` 就够了，不要碰 HUD。
3. **只写 `client` 入口、不用 Addon API**：Addon API 已经提供了 clips/keyframes/settings/panels 的注册事件，能用就用；Mixin 只留给“API 没有的宿主挂载”。
4. **把编辑器写死成 `UIFilmPanel` 专属**：POV 的 `UIPovEditor` 构造器直接吃 `UIFilmPanel`。我们的 `UICompositorEditor` 要吃 `CompEditorContext`，才能同时挂 Dashboard 和 Film 页签。
5. **为每个小功能加一个 Mixin**：Mixin 数量是维护成本；能通过 API/事件/组合解决的，不要 Mixin。

### 2.4 我们的策略

| 层 | 做法 |
| --- | --- |
| 数据/Clip/关键帧/设置 | 优先 Addon API 事件 |
| 编辑器宿主 | Dashboard 面板（官方 API）+ 可选 Film 页签（POV 式窄 Mixin） |
| 合成渲染 | `FrameOverlays`（官方 API），不碰 HUD |
| 深度 | 从 `mc.getFramebuffer()` blit，参考 POV 的 depth manager |
| BBS 内部类 | 全部收进 `BbsCompCompat`，按 BBS 2.7-1.20.1 锁版本 |
| 高分辨率覆盖层 | 先不做；需要时再评估一个 post-blit 窄 Mixin 或上游 API |

---

## 3. “完整小 AE”的定义

### 3.1 它不是 AE 克隆

AE 是 30 年积累的通用后期软件；我们要做的是：

> **BBS 影片的屏幕空间合成器**：BBS 负责 3D 世界、相机、角色、灯光、粒子；我们负责最终画面上的 2D/2.5D 合成层，以及和 BBS 数据绑定的“跟踪/深度/遮罩”。

所以有些 AE 能力**不应该做**，有些应该**换成 BBS 方式**。这不是偷懒，是产品定位。

### 3.2 1.0 的范围（完整核心）

1.0 必须包含：

- **工程/合成**：多合成、预合成嵌套、合成设置、标记、时间码、合成参数（Master Properties 的简化版）。
- **图层**：footage（图片/视频/序列）、纯色、文字、形状、Null、调整层、预合成；入出点、不透明度、混合模式、父子、轨道遮罩、图层样式、引导层、时间伸缩/冻结。
- **动画**：关键帧、值/速度图表、线性/贝塞尔/定格、连续贝塞尔/自动钳制、Easy Ease、时间重映射、动画预设。
- **文字**：基础属性、逐字动画器、范围选择器、摆动选择器、路径文字（1.x）、从文字创建形状（1.x）、逐字 3D（1.x）。
- **形状**：矩形/椭圆/多边形/星形、路径、合并/减去/交集/排除、修剪路径、中继器、位移路径、圆角、扭转/摆动、合并路径；绘画工具（1.x）。
- **效果**：30–40 个精选效果，覆盖 Blur/Color/Distort/Generate/Keying/Matte/Noise/Stylize/Transition/Utility/3D Channel（深度）。
- **跟踪/抠像**：chroma key、luma key、蒙版、锚点/骨骼绑定、蒙版跟踪、深度 matte/DOF/fog/displacement。
- **渲染**：渲染队列、多输出模块、Alpha、预渲染、代理、输出模板、渲染后动作、渲染设置/输出模块分离、深度通道。
- **工程**：保存/加载/迁移、资产收集、缺失资产处理、撤销/重做、复制粘贴、预设。

### 3.3 1.x 的长尾

- 2.5D 文字/形状挤出（带斜角/材质/简单光照）
- 2D 粒子系统
- 木偶/网格变形
- Roto 辅助（深度/ID 引导，不是 AI）
- 内容识别填充（深度引导的克隆/干净底）
- 文字路径、从文字创建形状
- 3D 逐字、VR 360/180
- MOGRT 式模板、协作/审阅、网络渲染
- 脚本/自动化

### 3.4 明确不做

- 完整 3D 图层/相机/灯光/C4D/Cineware：BBS 就是 3D 世界。
- 3D Camera Tracker：BBS 有相机矩阵。
- 稳定器：BBS 相机本身就是稳定的。
- 人脸跟踪：不在屏幕空间合成的核心。
- AI Roto Brush / AI 内容识别填充：没有模型/数据管线。
- 239 个效果：精选 30–40 个，留 effect registry 给社区。
- 完整 JS 表达式：接 BBS 的 MoLang/MathBuilder。
- VR/协作/网络渲染：不是 1.0。

### 3.5 工作量现实

以“1 人全职、熟悉 Java/OpenGL、能读 BBS 源码”为前提：

| 阶段 | 内容 | 量级 |
| --- | --- | --- |
| P0 | 垂直切片：overlay→导出、FBO 图、文档、Clip、编辑器壳、深度 | 4–6 周 |
| M1 | 核心合成：图层/变换/关键帧/时间轴/蒙版/文字/形状/10 效果 | 16–20 周 |
| M2 | 高级合成：预合成/调整层/遮罩/样式/逐字/表达式/跟踪/深度 | 20–24 周 |
| M3 | 输出/长尾：队列/多输出/Alpha/代理/2.5D 文字/粒子/色彩 | 16–20 周 |
| 1.0 | QA/文档/性能/示例/兼容 | 8–10 周 |
| **合计** | | **64–80 周 ≈ 15–18 个月**（顺利） |

加上 30% 缓冲，**18–24 个月**是更现实的预算。兼职（20h/周）大约 3–5 年。两个人（一个渲染/一个 UI）可以压到 10–14 个月，但 UI 仍然是瓶颈。

如果这个时间不可接受，只有三条路：砍范围、加人、或者接受“它不会真的是小 AE”。**不要相信“3 个月做个 AE”这种话。**

---

## 4. 总体架构

### 4.1 三层

```
┌─────────────────────────────────────────────────────────────┐
│ 编辑器层 (client)                                            │
│  UICompositorEditor                                         │
│  ├─ UICompPreview / UICompLayerStack / UICompInspector      │
│  ├─ UICompTimeline / UICompGraph / UICompToolbar            │
│  └─ UICompAssetPanel / UICompRenderDialog                   │
│  宿主：Dashboard 面板（官方） / Film 第三页签（POV 式 Mixin） │
├─────────────────────────────────────────────────────────────┤
│ 渲染层 (client)                                              │
│  CompRenderer                                               │
│  ├─ FboPool / GlStateGuard / ShaderLibrary                  │
│  ├─ LayerRenderer / MaskRenderer / BlendRenderer            │
│  ├─ EffectChain / TextEngine / ShapeTessellator             │
│  └─ DepthProvider / BindingResolver                         │
├─────────────────────────────────────────────────────────────┤
│ 文档层 (main)                                                │
│  CompProject / CompDocument / CompLayer / CompTrack / ...   │
│  KeyframeChannel / Interpolations / Undo / JSON storage     │
├─────────────────────────────────────────────────────────────┤
│ BBS 集成层 (client)                                          │
│  CompositionClip / CompositionOverlay / BbsCompCompat       │
│  FrameOverlays → BBS framebuffer → blit → VideoRecorder      │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 独立编辑器的两种宿主

**Host A：Dashboard 面板（推荐先做）**

```java
@Subscribe
public void onDashboardPanels(RegisterDashboardPanelsEvent event)
{
    event.dashboard.getPanels().registerPanel(
        new UICompositorPanel(event.dashboard),
        IKey.constant("Compositor"),
        Icons.FILM
    );
}
```

- 官方 API，`UIDashboardPanels.registerPanel` 是 public，BBS 自己的面板也走它。
- 全屏，独立，不需要 Mixin。
- 打开方式：Dashboard 标签页；或从 `CompositionClip` 面板的“Edit Composition”按钮 `dashboard.setPanel(panel)`。
- 缺点：和影片预览/时间轴不在同一个屏；切过去会离开 Film 面板。

**Host B：Film 第三页签（POV 式，M1 再做）**

```java
@Mixin(UIFilmPanel.class)
public abstract class UIFilmPanelCompositorMixin
{
    @Shadow public UIElement main;
    @Shadow public UIClipsPanel cameraEditor;
    @Shadow private List<UIElement> panels;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbscomp$install(UIDashboard dashboard, CallbackInfo info)
    {
        UICompositorEditor editor = new UICompositorEditor(/* context */);
        editor.full(this.cameraEditor);
        editor.setVisible(false);
        this.main.add(editor);
        this.panels.add(editor);
        // actions().editor(icon, ...) 加入口
    }
}
```

- 和 Camera / Replay / Compositor 并排，共享影片上下文。
- 需要 1 个窄 Mixin；POV 已经证明可行。
- 不是 P0 必须；P0 先用 Host A 把编辑器跑通。

**关键：`UICompositorEditor` 是一个 `UIElement`，不继承 `UIFilmPanel`，也不依赖 `UIFilmPanel`。** 它只吃：

```java
interface CompEditorContext
{
    CompProject getProject();
    CompDocument getComp();
    float getTime();
    void setTime(float time);
    boolean isPlaying();
    void play();
    void pause();
    CompRenderer getRenderer();
    UndoManager getUndo();
    /* nullable */
    Film getFilm();
    UIFilmPanel getFilmPanel();
}
```

Host A 和 Host B 各实现一个 context。这样编辑器不会被宿主绑死。

### 4.3 与影片的绑定

```
影片 .dat
└─ 相机时间轴
   └─ bbscomp:composition Clip
      ├─ project: "myfilm"        // .bbscomp 工程 id
      ├─ comp: "hud/main"         // 工程内的合成 id
      ├─ offset: 0                // tick
      ├─ timeScale: 1.0
      ├─ fit: contain
      ├─ opacity: 1.0
      ├─ blend: normal
      └─ hold: true
```

- 不碰 `.dat`；自定义 Clip 由 BBS 序列化，Addon 移除时原样保留。
- `.bbscomp` 是独立的工程文件（JSON-like + assets），可复用、可 diff、可收集。
- 一个影片可以放多个 CompositionClip；顺序由 Clip 的 `layer` 决定。
- Clip 的 `Envelope` 当整体淡入淡出。

### 4.4 导出

| 模式 | 产物 | 实现 |
| --- | --- | --- |
| Baked | MP4/MOV，BBS 画面 + comp 烧在一起 | BBS `VideoRecorder`，overlay 在 blit 前画 |
| Comp-only | ProRes 4444 / PNG 序列，带 Alpha | 自己的 RGBA FBO + ffmpeg 管道 |
| Separate passes | beauty（无 comp）+ comp alpha | 两次渲染或一次渲染分路 |
| Pre-render | comp 预渲染成视频/序列，作为 footage | 自己的 export session + 缓存 |

Baked 是默认；Comp-only/Alpha 是 M3 的“多输出模块”。

### 4.5 为什么不是独立桌面 App

- BBS 默认不输出逐帧、深度、相机包；要离线合成，先得做一套 BBS 渲染包导出。
- 预览 vs 最终两套渲染器会漂移；字体、模糊、混合、色彩只要有一点不一致，创作者就不信预览。
- 独立 App 是第二个产品。P0/P1 还没证明 BBS 内能跑通之前，投入桌面 App 是把风险放错地方。
- P3 真需要离线质量，再把 `comp/` + `render/` 抽出去做 Studio；不是现在。

---

## 5. 数据模型（完整）

### 5.1 工程 / 合成

```java
CompProject extends ValueGroup
├─ ValueString name
├─ ValueString id
├─ ValueStableList<CompDocument> comps
├─ ValueStableList<CompAsset> assets
├─ ValueGroup settings
└─ ValueGroup metadata

CompDocument extends ValueGroup
├─ ValueString id / name
├─ ValueInt width / height / fps
├─ ValueFloat duration
├─ ValueColor background
├─ ValueStableList<CompLayer> layers
├─ ValueStableList<CompTrack> tracks
├─ ValueStableList<CompMarker> markers
├─ ValueStableList<CompParameter> parameters
└─ ValueGroup settings
```

- 一个 `.bbscomp` = 一个工程（多个 comp）。这比“一个 comp 一个文件”更接近 AE，也方便预合成/模板。
- 工程文件：`project.json`（BBS `DataToString` 的 JSON-like）+ `assets/` + `fonts/` + `luts/`。
- 也可以用文件夹：`MyProject.bbsproj/project.json` + `assets/`；打包成 `.bbsae` zip。

### 5.2 图层

```java
CompLayer extends ValueGroup
├─ ValueString type            // 由 factory 决定子类
├─ ValueString name
├─ ValueBoolean visible / locked / solo / guide
├─ ValueFloat in / out / startTime
├─ ValueFloat timeScale / freeze
├─ ValueFloat opacity
├─ ValueInt blendMode
├─ ValueString parentId
├─ ValueTransform transform    // BBS：translate/scale/rotate
├─ ValueFloat anchorX / anchorY
├─ ValueGroup content          // 子类
├─ ValueStableList<CompMask> masks
├─ ValueStableList<CompEffect> effects
├─ ValueGroup styles
└─ ValueGroup tracking
```

子类：

| 类型 | content | 说明 |
| --- | --- | --- |
| `FootageLayer` | 图片/视频/序列 | BBS `Link` 或工程相对路径；视频用 BBS `VideoPlayer`/`VideoClip` 的解码 |
| `SolidLayer` | color | 纯色 |
| `TextLayer` | text/font/size/... | 接 BBS `FontManager` |
| `ShapeLayer` | path/operators/fill/stroke | 自研矢量 |
| `NullLayer` | 无 | 父级/控制 |
| `AdjustmentLayer` | 无 | 对下方累积缓冲做效果 |
| `PrecompLayer` | compId/timeRemap | 嵌套合成 |
| `BbsFormLayer`（1.x） | form | 把 BBS form 渲染成 2D 层（可选） |

### 5.3 轨道 / 关键帧

```java
CompTrack extends ValueGroup
├─ ValueString path            // "layerId/transform.position"
└─ KeyframeChannel channel     // BBS 的关键帧通道
```

- `KeyframeChannel` 自带 `type` 字段，序列化时写入 factory 类型；读回时自动恢复。
- 通道的 `id` 用 `path`，但放在 `CompTrack` 里而不是直接做 `ValueGroup` 的子 id，避免 `/` 之类字符在 data path 里的歧义。
- 关键帧插值直接用 BBS 的 `Interpolations`：`LINEAR`、`BEZIER`、`AUTO`、`AUTO_CLAMPED`、`BSPLINE`、`CONST`，以及大量 easing。
- 关键帧的 `lx/ly/rx/ry` 手柄、`KeyframeStyle`、复制/粘贴、时间缩放都可以复用 BBS 的 `Keyframe`/`KeyframeChannel`。

### 5.4 蒙版

```java
CompMask extends ValueGroup
├─ ValueString mode            // add/subtract/intersect
├─ ValueFloat opacity
├─ ValueFloat feather
├─ ValueFloat expansion
├─ ValueBoolean inverted
└─ ValueStableList<CompMaskPoint> points

CompMaskPoint extends ValueGroup
├─ ValueFloat x / y
├─ ValueFloat inX / inY / outX / outY   // 贝塞尔手柄
└─ ValueBoolean smooth
```

- 路径填充：stencil even-odd 或 nonzero。
- 羽化：可分离高斯；`feather` 以 comp 像素为单位，预览按比例缩放。
- 多蒙版：按 mode 组合到一张 R8 蒙版纹理。
- 蒙版跟踪：点绑定到锚点/跟踪器（M2）。

### 5.5 效果

```java
CompEffect extends ValueGroup
├─ ValueString type            // "blur", "glow", ...
├─ ValueBoolean enabled
└─ ValueGroup params           // 由 EffectDefinition 定义

EffectDefinition
├─ id / name / category
├─ List<EffectParam> params
├─ int passes
├─ shader source(s)
└─ resolution scale
```

- 参数类型：float/int/bool/color/point/angle/percent/gradient/curve/layer。
- UI 由 `EffectDefinition` 自动生成；渐变/曲线用自定义 widget。
- 效果链按顺序执行，ping-pong FBO；需要 backdrop 的效果（glow/drop shadow）单独处理。
- 未知效果/参数原样保留（为 addon 卸载/升级）。

### 5.6 文字 / 形状

```java
TextContent extends ValueGroup
├─ ValueString text
├─ ValueLink font
├─ ValueInt size
├─ ValueColor color
├─ ValueFloat tracking / leading
├─ ValueInt align
├─ ValueGroup stroke / shadow / background
├─ ValueStableList<TextAnimator> animators
└─ ValueBoolean box / wrap

ShapeContent extends ValueGroup
├─ ValueStableList<ShapePath> paths
├─ ValueStableList<ShapeOperator> operators
├─ ValueGroup fill / stroke
└─ ValueGroup transform
```

- 文字先用 BBS 的 `FontManager` + `TextRenderer` 做整段布局；逐字动画需要字形级布局，M2 再做。
- 形状用自研 `ShapePath` + 展平 + stencil；布尔运算用 stencil 组合；trim/repeater 是路径操作，M2。
- 蒙版和形状共用路径模型，减少一套代码。

### 5.7 绑定 / 参数

```java
CompBinding extends ValueGroup
├─ ValueString type            // camera/replay/bone/anchor/layer/expression
├─ ValueString target
├─ ValueGroup params           // offset/scale/rotation/smoothing
└─ ValueBoolean enabled

CompParameter extends ValueGroup
├─ ValueString name
├─ ValueString type            // float/color/point/bool/comp
├─ ValueGroup value
└─ ValueString description
```

- 绑定在求值时解析：BBS 相机/实体/骨骼 → 世界点 → 屏幕点。
- comp 参数暴露到 Clip/上层 comp，做模板。
- 表达式可以引用 `comp.parameters`、`layer`、`time`、`value`。

### 5.8 序列化 / 版本 / 稳定 ID

- 用 BBS 的 `ValueGroup.toData()` + `DataToString.write(file, data, true)` 输出 JSON-like。
- 根节点：
  ```json
  { "format": "bbscomp", "version": 1, "project": { ... } }
  ```
- 稳定 ID：`ValueStableList` 给 layer/mask/effect/track 分配永久 id；复制时重新分配。
- 迁移：`version` + `ICompMigration` 链；未知字段保留。
- 未知效果/轨道：保留 raw `MapType`，写回时不丢。
- 资产路径：相对工程文件；缺失时占位 + 提示。

---

## 6. 渲染器（完整）

### 6.1 Render graph

```
render(comp, time, target):
  A = acquire RGBA FBO (comp resolution)
  clear A
  for layer in comp.layers (bottom → top):
     if !layer.visible or time outside in/out: continue
     if layer is Adjustment:
        A = applyEffects(A, layer.effects)
        continue
     L = renderLayerContent(layer, time)        // RGBA FBO
     L = applyMasks(L, layer.masks)             // R8 mask → multiply
     L = applyEffects(L, layer.effects)         // ping-pong
     L = applyStyles(L, layer.styles)           // shadow/glow/stroke...
     if layer.trackMatte != null:
        M = renderMatte(layer.trackMatte)
        L = multiplyAlpha(L, M)
     A = composite(A, L, layer.blend, layer.opacity, layer.transform)
  return A
```

- 每个图层独立 FBO；混合/蒙版/效果/遮罩都变成对 RGBA 图的操作。
- 预览按面板分辨率渲染；导出按 comp 分辨率渲染；效果参数按比例缩放。
- `FboPool` 复用；每帧结束释放。
- `CompRenderCache` 缓存静态内容/预合成（时间不变时）。

### 6.2 图层内容

- **Footage**：纹理/视频帧 → 全屏 quad；支持 fit/fill/stretch、UV、时间偏移。
- **Solid**：纯色 quad。
- **Text**：`TextEngine` 排版 → 字形 quad；支持 fill/stroke/shadow/background。
- **Shape**：`ShapeTessellator` → 三角/ stencil；fill/stroke。
- **Precomp**：递归 `render(subComp, mappedTime)` → FBO；带 transform/fit。
- **Adjustment**：无内容，直接对 A 做效果。
- **Null**：不画，只提供 transform。
- **BbsFormLayer**（1.x）：离屏渲染 BBS form（可选）。

### 6.3 蒙版管线

1. 把蒙版路径展平为多边形。
2. 在 R8 FBO 上用 stencil even-odd 填充；多个蒙版按 mode 组合。
3. 羽化：可分离高斯（水平 + 垂直），半径按 comp 像素。
4. 蒙版不透明度/扩展：乘以 alpha / 距离场（扩展 M2）。
5. 把蒙版乘到图层 alpha 上。

### 6.4 混合 / 合成

- 内部 premultiplied alpha。
- 混合模式先做 12–14 个：normal、add、screen、multiply、overlay、soft light、hard light、difference、exclusion、darken、lighten、color dodge、color burn。
- 用 shader 采样 backdrop + layer；不要依赖固定管线。
- 不透明度在混合 shader 里乘。
- 轨道遮罩：alpha/luma，采样另一层的 FBO。

### 6.5 效果

- `EffectChain`：按顺序 ping-pong；每个 effect 声明 passes/resolution scale。
- 常用：
  - Blur：gaussian（H/V）、box、directional、radial、lens blur（DOF）。
  - Color：brightness/contrast、curves、levels、hue/saturation、color balance、tint、exposure、LUT。
  - Distort：displacement（texture/depth）、bulge、pinch、twist、ripple、wave、mirror、offset。
  - Generate：gradient ramp、4-color gradient、noise、fractal noise、grid、checkerboard、circle、lens flare、lightning。
  - Keying：chroma key、luma key、color range、spill suppressor、matte choker。
  - Matte：matte blur、simple choker、matte fill。
  - Noise/Grain：noise、grain、dust。
  - Stylize：glow、drop shadow、stroke、bevel、mosaic、posterize、threshold、find edges、cartoon。
  - Transition：linear wipe、radial wipe、gradient wipe、clock wipe、dissolve。
  - Utility：grow bounds、fill、alpha levels、convert color。
  - Time：echo、posterize time、time displacement。
  - 3D Channel：depth matte、DOF、fog、depth displacement。
- 效果 shader 编译失败：跳过该效果，日志报错，不崩。

### 6.6 预合成 / 调整层 / 遮罩

- 预合成：独立 comp + 独立时间；递归渲染；深度限制防循环。
- 调整层：对 A 做效果，不产生内容。
- 轨道遮罩：alpha/luma；支持“遮罩层不显示但参与遮罩”。
- 折叠变换：2D 下等价于预合成 + 变换；不做 3D 折叠。
- 连续栅格化：矢量层在缩放时重新渲染；M2。

### 6.7 文字

- `TextEngine` 包装 BBS `FontManager` / `FontRenderer` / `TextRenderer`。
- 布局：行、字形、对齐、tracking、leading、box wrap。
- 渲染：字形 quad 到 FBO；支持 fill/stroke/shadow/background。
- 逐字动画：每个字形有 transform/opacity/color/blur；范围选择器/摆动选择器。
- 限制：BBS 的 `TextRenderer` 对复杂文字 shaping（阿拉伯/印度语）支持有限；中文/拉丁基本可用。复杂排版是 1.x/不做。

### 6.8 形状

- `ShapePath`：contour + 贝塞尔点（in/out 手柄）+ closed。
- 展平：自适应细分。
- 填充：stencil even-odd（任意路径都稳）。
- 描边：polyline strip + join/cap；或用 SDF。
- 布尔：merge/subtract/intersect/exclude 用 stencil 组合。
- trim/repeater/offset/round/wiggle/twist：路径操作，M2。
- 绘画/克隆/橡皮：M3/1.x。

### 6.9 深度

- 在 `FrameOverlays` 渲染前，把 `mc.getFramebuffer()` 的 depth attachment blit 到 `R32F` 纹理。
- 线性化：`near/far` 从相机/投影拿；Iris 下先实测。
- 用途：depth matte、DOF、fog、depth displacement、depth-guided fill。
- 分辨率：窗口 framebuffer；按归一化 UV 采样，和 comp 分辨率解耦。
- 失败降级：深度效果禁用 + 提示。

### 6.10 色彩 / 分辨率

- 1.0 在 sRGB 空间做；PNG/视频素材按 sRGB 处理；混合结果和 AE 的 8-bit 默认接近。
- LUT/曲线在 M3；线性工作流/OCIO 不做。
- 预览分辨率 = 面板尺寸；导出 = comp 分辨率；效果参数按 `previewScale` 缩放。
- 4K 导出时，baked 受窗口 framebuffer 限制；comp-only 可以全分辨率。

### 6.11 GL 状态 / Iris

- `GlStateGuard`：保存/恢复 framebuffer、viewport、scissor、blend、depth、cull、shader、VAO、texture bindings、projection。
- 所有自定义 FBO/shader 都在 guard 里。
- Iris 开/关都要测；参考 POV 的 depth manager 分支。
- 出异常：捕获，跳过本帧合成，恢复状态，避免整机花屏。

---

## 7. 编辑器 UI（完整）

### 7.1 宿主与打开方式

- **Host A（P0）**：Dashboard 标签页“Compositor”。全屏，独立。
- **Host B（M1 可选）**：Film 第三页签“Compositor”，POV 式。
- **打开 comp**：
  - Dashboard：工程/合成列表，双击打开。
  - Film：CompositionClip 面板的“Edit Composition”按钮 → 打开 Host A 或 Host B，定位到 comp，时间对齐 clip。
- **多 comp 标签**：编辑器内顶部 tabs，类似 BBS 的 film tabs。

### 7.2 布局

```
┌────────────────────────────────────────────────────────────────────────────┐
│ [Project ▾] [Comp tabs: HUD | Title | +]   [Save] [Undo] [Redo] [Render]   │
├───────────────┬────────────────────────────────────────────┬───────────────┤
│ Project/      │ Preview                                    │ Inspector     │
│ Assets        │  ┌──────────────────────────────────────┐  │ [Layer]       │
│ ┌───────────┐ │  │                                      │  │ Transform     │
│ │ Comps     │ │  │        comp render (checkerboard)    │  │ Text          │
│ │ Assets    │ │  │                                      │  │ Masks         │
│ │ Fonts     │ │  └──────────────────────────────────────┘  │ Effects       │
│ └───────────┘ │  [Fit] [100%] [Quality ▾] [Safe] [Guides]  │ Styles        │
│ Layer stack   │                                            │ Tracking      │
│ ┌───────────┐ │                                            │               │
│ │ ● Title   │ │                                            │               │
│ │ ● HUD     │ │                                            │               │
│ │ ● BG      │ │                                            │               │
│ └───────────┘ │                                            │               │
├───────────────┴────────────────────────────────────────────┴───────────────┤
│ Timeline: [◀][▶] 00:00:00:00  [Dope Sheet|Graph]  [Zoom] [Snap]             │
│ ┌──────────────┬──────────────────────────────────────────────────────────┐ │
│ │ Layer/Prop   │ Ruler + layer bars + keyframes                           │ │
│ │ ▾ Title      │ ███████████████████████████                              │ │
│ │   Position   │   ◆        ◆        ◆                                    │ │
│ │   Opacity    │   ◆                 ◆                                    │ │
│ └──────────────┴──────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

### 7.3 图层栈

- 拖拽排序、重命名、颜色标签。
- 眼睛/锁/独奏/引导。
- 展开/折叠：Transform / Text / Masks / Effects / Styles / Tracking。
- 右键菜单：复制、删除、预合成、添加蒙版、添加效果、父级、轨道遮罩。
- 多选、框选、Shift/Ctrl。
- FX/蒙版/父级/遮罩徽标。
- 图层搜索/过滤。

### 7.4 预览 / 工具

- 平移/缩放/适应/100%。
- 棋盘格、安全框、参考线、网格、标尺。
- 2D gizmo：移动/缩放/旋转；锚点。
- 蒙版钢笔：点击加点、拖拽手柄、闭合、加点/删点。
- 形状钢笔/图元工具。
- 文字工具（点选/框选）。
- 手型/缩放工具。
- 吸附：网格、参考线、图层边缘、中心、安全框。
- 预览质量：Full/Half/Quarter；效果开关。
- 时间码/帧号。

### 7.5 检查器

- 按图层类型显示属性组。
- 每个属性：标签、数值/颜色/点、秒表（关键帧）、表达式按钮、重置。
- 多选时显示混合值；编辑应用到所有选中。
- 搜索属性。
- 效果列表：添加/删除/排序/开关/重置；参数自动生成。
- 蒙版列表：模式/羽化/不透明度/反转/扩展。
- 图层样式：投影/内阴影/外发光/内发光/斜面和浮雕/颜色叠加/渐变叠加/缎面/描边。
- 跟踪/绑定：类型、目标、offset、smoothing。
- 文本：字体/字号/颜色/描边/阴影/字距/行距/对齐/动画器。
- 形状：路径/运算器/填充/描边。

### 7.6 时间轴 / 曲线

- 时间标尺、播放头、工作区、循环。
- 图层条：入点/出点/持续时间；拖拽裁剪；时间伸缩/冻结。
- 关键帧行：展开图层属性；菱形/选择/拖拽/框选/复制/粘贴。
- 图表编辑器：值曲线 / 速度曲线；贝塞尔手柄；自动钳制/连续；Easy Ease。
- 多曲线叠加；归一化显示。
- 标记：合成标记/图层标记；吸附。
- 时间码显示；帧/秒切换。
- 缩放/滚动/适应。
- 右键：插值、复制/粘贴、删除、时间反向、时间缩放。

### 7.7 资产 / 工程

- 工程资产：图片、视频、音频、字体、LUT、其他 comp。
- 导入：拖拽/文件选择；BBS `RegisterImportersEvent` 可用于拖入资产。
- 资产信息：尺寸/时长/帧率/路径；替换/重链。
- 收集文件：复制到 `assets/`；打包 `.bbsae`。
- 缺失资产：占位 + 提示 + 重链。
- 工程设置：分辨率/fps/时长/背景/色彩空间。

### 7.8 渲染队列

- 队列项：工程/comp/范围/输出模块/状态。
- 渲染设置：分辨率/fps/时长/质量/代理/运动模糊/Alpha。
- 输出模块：容器/编码/码率/CRF/像素格式/色彩/音频/文件名/后动作。
- 预设：Review H.264 / Master ProRes 422 HQ / Comp Alpha ProRes 4444 / PNG Sequence。
- 队列操作：开始/暂停/取消/重试/打开文件夹/显示日志。
- 后动作：打开文件夹、显示文件、复制路径、运行命令（默认关闭）。

### 7.9 BBS 控件映射

| 需求 | BBS 控件 |
| --- | --- |
| 页签/图标 | `UIIcon`、`UITabList`、`UITextTab` |
| 按钮/开关 | `UIButton`、`UIToggle`、`UIIcon` |
| 数值/滑块 | `UITrackpad`、`UISlider`（若有） |
| 文本 | `UIText`、`UITextbox`、`UITextarea` |
| 颜色 | `UIColor`、`UIColorPicker`、`UIColorFields` |
| 列表/树 | `UIList`、`UITree`、`UIScrollView` |
| 关键帧 | `UIKeyframeEditor`、`UIKeyframes`、`UIKeyframeGraph`、`UIKeyframeDopeSheet` |
| 时间轴 | `UITimelineCanvas`、`UITimelinePanel` |
| 变换 | `UIPropTransform` |
| 菜单 | `UIContextMenu`、`UISimpleContextMenu` |
| 弹窗 | `UIOverlayPanel`、`UIMessageOverlayPanel` |
| 文件选择 | `UIDataPathList`、`UIFileLinkList`、`UIOverlayPanel` |
| 撤销 | `UndoManager`、`ValueChangeUndo` |

POV 的 `UIPovEditor` 是这些控件组合的最佳参考；纹理编辑器的 `UILayersPanel` 是图层栈参考；Film 编辑器的 `UIClipsPanel`/`UIKeyframeEditor` 是时间轴参考。

---

## 8. 功能矩阵（逐项回答你的清单）

图例：**1.0** = 完整核心；**1.x** = 长尾；**不做** = 明确放弃；**替代** = 用 BBS 方式做。

### 8.1 项目 / 合成 / 时间

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| 新建合成（分辨率/帧率/时长） | 1.0 | 工程内多 comp |
| 合成嵌套/预合成 | 1.0 | 独立时间、递归渲染、防循环 |
| 时间码/时间单位 | 1.0 | 时间码/帧/秒切换 |
| 合成标记/图层标记 | 1.0 / 1.x | 合成标记 1.0；图层标记 1.x |
| 主属性（Master Properties） | 1.0 简化 | comp 参数 + Clip 暴露 + 预设；完整 Essential Graphics 1.x |
| 标记对应镜头切点/音频节拍 | 1.0 / 1.x | 导入 BBS 相机 Clip 边界；音频节拍检测 1.x |

### 8.2 图层

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| 图层堆栈 | 1.0 | 拖拽/多选/颜色标签 |
| 类型：素材/纯色/调整/空对象/预合成 | 1.0 | |
| 文字/形状图层 | 1.0 | |
| BBS form 图层 | 1.x | 把 BBS 模型/物品渲染成 2D 层，可选 |
| 入点/出点/持续时间 | 1.0 | |
| 不透明度 | 1.0 | |
| 混合模式 | 1.0 | 12+ |
| 父子链接/空对象 | 1.0 | |
| 调整层 | 1.0 | |
| 预合成 | 1.0 | |
| 轨道遮罩 | 1.0 | alpha/luma |
| 图层样式 | 1.0 基础 / 1.x 完整 | 投影/内阴影/外发光/内发光/斜面/颜色/渐变/缎面/描边；先做 6 个 |
| 自动定向 | 1.x | 沿运动路径 |
| 时间伸缩/冻结帧 | 1.0 / 1.x | 简单伸缩 1.0；完整 1.x |
| 折叠变换/连续栅格化 | 1.x | 2D 等价实现 |
| 引导层 | 1.0 | 不参与最终渲染 |
| 3D 图层开关/渲染器 | 不做 / 替代 | BBS 负责 3D；2.5D 挤出 1.x |

### 8.3 动画

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| 属性关键帧 | 1.0 | BBS `KeyframeChannel` |
| 图表编辑器 | 1.0 | 值/速度曲线 |
| 速度/数值曲线 | 1.0 | |
| 线性/贝塞尔/定格 | 1.0 | BBS `Interpolations` |
| 连续贝塞尔/自动钳制 | 1.0 | BBS `AUTO`/`AUTO_CLAMPED` |
| 时间重映射 | 1.0 | per-layer time offset/scale；完整 remap 1.x |
| Easy Ease | 1.0 | 预设 |
| 时间伸缩 vs 时间重映射 | 1.0 | 两个独立概念 |
| 动画预设 | 1.0 基础 / 1.x 库 | 保存/应用 |
| 数据驱动动画 | 1.x | 绑定 BBS 数据/表达式 |
| 响应式设计时间 | 不做 / 1.x | 不是核心 |

### 8.4 文字

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| 字体/大小/颜色/描边/字距/行距 | 1.0 | BBS TTF/OTF |
| 逐字动画器 | 1.0 | 字形级布局 |
| 范围选择器 | 1.0 | start/end/offset/random/seed |
| 摆动选择器 | 1.x | |
| 文字路径 | 1.x | |
| 从文字创建形状 | 1.x | 需要字形轮廓 |
| 逐字符 3D 化 | 1.x | 2.5D 挤出 |

### 8.5 形状

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| 矩形/椭圆/多边形/星形 | 1.0 | |
| 合并/减去/交集/排除 | 1.0 | stencil |
| 修剪路径 | 1.0 | |
| 中继器 | 1.0 | |
| 位移路径 | 1.x | 多边形偏移 |
| 收缩和膨胀 | 1.x | |
| 圆角 | 1.0 | |
| 扭转/摆动路径/摆动变换 | 1.x | |
| 合并路径 | 1.0 | |
| 绘画（画笔/克隆/橡皮） | 1.x | |
| Roto Brush / Refine Edge | 不做 / 1.x | 蒙版 + 深度/ID 辅助 |

### 8.6 效果

| 家族 | 决定 | 说明 |
| --- | --- | --- |
| 3D Channel | 1.0 部分 | depth matte/DOF/fog；ID 1.x/不做 |
| Blur & Sharpen | 1.0 | gaussian/box/directional/radial/lens/sharpen |
| Channel | 1.x | |
| Color Correction | 1.0 | brightness/contrast/curves/levels/hue/sat/balance/tint/exposure/LUT |
| Distort | 1.0 部分 | displacement/bulge/pinch/twist/ripple/wave/mirror/offset |
| Expression Controls | 1.0 | 滑块/角度/颜色/点 |
| Generate | 1.0 部分 | gradient/noise/fractal/grid/checker/circle/lens flare；particles 1.x |
| Keying | 1.0 | chroma/luma/color range/spill/matte choker |
| Matte | 1.0 | matte blur/simple choker/matte fill |
| Noise & Grain | 1.0 | noise/grain/dust |
| Perspective | 1.x | 基础 corner pin/transform |
| Simulation | 1.x | particles；Shatter 1.x/不做 |
| Stylize | 1.0 | glow/drop shadow/stroke/bevel/mosaic/posterize/threshold/find edges/cartoon |
| Text | 1.x | |
| Time | 1.x | echo/posterize time/time displacement |
| Transition | 1.0 | wipe/dissolve |
| Utility | 1.x | |
| Obsolete | 不做 | |

**不做 239 个。** 30–40 个高质量效果 + effect registry，比 239 个半成品更有价值。

### 8.7 跟踪 / 抠像 / 稳定 / 填充

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| Keylight | 1.0 | chroma key |
| Roto Brush | 不做 / 1.x | 蒙版 + 深度/ID 辅助 |
| 内容识别填充 | 1.x / 不做 | 深度引导克隆；AI 不做 |
| 点跟踪 | 1.x | 2D 素材；3D 锚点 1.0 |
| 3D 摄像机跟踪 | 不做 | BBS 相机 |
| 变形稳定器 | 不做 | BBS 相机稳定 |
| 蒙版跟踪 | 1.0 / 1.x | 锚点 1.0；2D 跟踪 1.x |
| 面部跟踪 | 不做 | |
| 木偶工具 | 1.x | 网格变形 |
| 通道运算组合 | 1.x | |
| 差值遮罩 | 1.x | |

### 8.8 3D / 摄像机 / 灯光

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| 3D 图层基础 | 不做 | BBS |
| 3D 摄像机 | 不做 | BBS |
| 灯光 | 不做 | BBS |
| 投影/接受阴影 | 不做 | BBS |
| Cinema 4D 渲染器 | 1.x 部分 | 2.5D 文字/形状挤出；不是完整 C4D |
| 经典 3D 渲染器 | 不做 | |
| Cineware | 不做 | |
| 点兴趣 | 不做 | |

### 8.9 渲染 / 输出

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| 渲染队列 | 1.0 | |
| 多输出模块 | 1.0 | |
| 预渲染 | 1.0 | |
| 代理 | 1.0 | |
| Alpha 通道导出 | 1.0 | comp-only |
| 输出模块模板保存/加载/默认 | 1.0 | |
| 渲染后动作 | 1.0 | |
| 渲染设置/输出模块分离 | 1.0 | |
| 通道/深度选项 | 1.0 / 1.x | depth 1.0；ID 1.x |
| 代理工作流 | 1.0 | |
| 自动化渲染/网络渲染 | 1.x / 不做 | 本地 CLI 1.x；网络不做 |
| 脚本/自动化 | 1.x | |

### 8.10 其他

| 能力 | 决定 | 说明 |
| --- | --- | --- |
| VR 360/180 | 不做 / 1.x | 不是核心 |
| 颜色管理/色彩空间 | 1.0 基础 / 1.x | sRGB + LUT；线性/OCIO 1.x |
| 标记/元数据 | 1.0 | |
| MOGRT 式模板 | 1.0 基础 / 1.x | comp 参数 + 预设 |
| 协作/审阅 | 不做 | |
| 快捷键可视化编辑器 | 1.x | BBS keybinds 先够用 |

---

## 9. 实施路线（不是 MVP）

> **M1 / M2 / M3 都是 1.0 的组成部分，不是三个产品版本。** 每一波都是“1.0 的某个子系统做完了”，不是一个可以停下来的小产品。之所以分波，是因为代码有依赖顺序，不是因为范围缩水。

### P0：垂直切片（4–6 周）

**目标：证明“完整架构”能跑，不是做功能 demo。**

1. 1.20.1 工程 + BBS 2.7 jar + 启动 dev client。
2. `FrameOverlays` 测试卡：渐变 + 文字 + 图片 + 圆形蒙版 + 动画；验证 F6/面板导出。
3. FBO render graph：3 层、alpha blend、1 个蒙版、1 个 blur；Iris 开/关；`GlStateGuard`。
4. `CompProject`/`CompDocument` 最小模型 + JSON 保存/加载。
5. `CompositionClip` + `CompositionOverlay` + Clip 面板。
6. Dashboard “Compositor” 空壳 + FBO 预览 + 图层列表。
7. `UIKeyframeEditor` 接 comp track 的原型（一个属性）。
8. depth blit + 可视化。
9. 1080p/4K、窗口尺寸、性能基线。

**退出标准：**

- 10 秒 BBS 影片 + 3 层 comp 导出，时间对齐，无闪烁。
- 编辑器能打开/保存/加载 comp。
- Iris 开/关不花屏。
- depth 至少一种读法可用。
- `UIKeyframeEditor` 能驱动 comp 通道。

**Kill criteria：**

- overlay 无法稳定进导出（改 comp-only + 外部合并）。
- FBO/shader 在 Iris 下无法恢复状态。
- BBS UI 完全无法承载 comp 时间轴/曲线（改桌面 App 或大幅简化）。

### M1：核心合成（16–20 周）

- 完整文档模型：工程/多 comp/图层/轨道/蒙版/效果。
- 图层：footage/solid/text/shape/null/adjustment/precomp。
- 变换/锚点/父子/混合/入出点/时间伸缩。
- 关键帧/曲线/Easy Ease/值图表。
- 蒙版：多边形 + 羽化 + add/subtract/intersect。
- 文字：整段 + 基础样式；逐字动画在 M2。
- 形状：图元 + 填充/描边 + 圆角。
- 效果：10–12 个（blur/glow/shadow/grade/gradient/noise/vignette/wipe...）。
- 编辑器：图层栈/预览/检查器/时间轴/曲线/资产/撤销/保存。
- Baked 导出。
- 可选：Film 第三页签宿主。

**退出：** 30 秒片子，标题/HUD/角标三类 comp，1080p60，编辑器可用。

### M2：高级合成（20–24 周）

- 预合成/嵌套/时间重映射。
- 调整层/轨道遮罩/图层样式。
- 逐字动画器 + 范围/摆动选择器。
- 形状运算器：trim/repeater/offset/round/twist/wiggle/merge。
- 表达式控制器（MoLang 子集）。
- 跟踪绑定：replay/bone/anchor；蒙版跟踪。
- 深度：matte/DOF/fog/displacement。
- 文本路径/从文字创建形状（可后置）。
- 代理/预渲染。

**退出：** 60 秒片子，预合成 + 遮罩转场 + 跟踪标签 + 景深，稳定导出。

### M3：输出 / 长尾（16–20 周）

- 渲染队列 + 多输出 + Alpha + 输出模板 + 后动作。
- 2.5D 文字/形状挤出。
- 2D 粒子。
- 色彩/LUT。
- 预设/模板/示例工程。
- 性能优化/兼容矩阵/文档。

**退出：** 1.0 功能矩阵完成；baked + comp alpha 两套输出；队列可连续跑。

### 1.0：打磨（8–10 周）

- QA、崩溃/边界、缺失资产、坏 JSON、shader 失败。
- 性能：1080p/4K、10–30 层、效果预算。
- 文档/教程/示例。
- 发布（Modrinth/CurseForge/GitHub）。

---

## 10. 文件 / 类结构

```
src/main/java/dev/bbscomp/
├─ comp/
│  ├─ CompProject.java
│  ├─ CompDocument.java
│  ├─ CompLayer.java
│  ├─ layers/{FootageLayer,SolidLayer,TextLayer,ShapeLayer,NullLayer,AdjustmentLayer,PrecompLayer}.java
│  ├─ CompTrack.java
│  ├─ CompMask.java / CompMaskPoint.java
│  ├─ CompEffect.java
│  ├─ CompMarker.java
│  ├─ CompParameter.java
│  ├─ CompBinding.java
│  ├─ CompTime.java
│  ├─ CompEvaluator.java
│  ├─ CompMigration.java
│  └─ io/CompProjectIO.java
├─ effects/
│  ├─ EffectDefinition.java / EffectParam.java / EffectRegistry.java
│  └─ builtin/...
├─ text/
│  ├─ TextContent.java / TextLayout.java / TextAnimator.java
│  └─ TextSelector.java
├─ shape/
│  ├─ ShapePath.java / ShapePoint.java / ShapeOperator.java
│  └─ ShapeTessellator.java
└─ bbs/
   └─ CompositionClip.java          // data + clip registration (main-safe)

src/client/java/dev/bbscomp/
├─ bbs/
│  ├─ CompositionOverlay.java
│  ├─ CompositionClipPanel.java
│  ├─ BbsCompCompat.java
│  ├─ CompAssetLoader.java
│  └─ CompBindingResolver.java
├─ render/
│  ├─ CompRenderer.java
│  ├─ CompRenderContext.java
│  ├─ FboPool.java
│  ├─ GlStateGuard.java
│  ├─ ShaderLibrary.java
│  ├─ LayerRenderer.java
│  ├─ MaskRenderer.java
│  ├─ BlendRenderer.java
│  ├─ EffectChain.java
│  ├─ TextEngine.java
│  ├─ ShapeRenderer.java
│  ├─ DepthProvider.java
│  └─ CompRenderCache.java
├─ ui/
│  ├─ UICompositorPanel.java        // Host A
│  ├─ UICompositorEditor.java       // host-agnostic
│  ├─ CompEditorContext.java
│  ├─ UICompToolbar.java
│  ├─ UICompPreview.java
│  ├─ UICompLayerStack.java
│  ├─ UICompInspector.java
│  ├─ UICompTimeline.java
│  ├─ UICompGraph.java
│  ├─ UICompAssetPanel.java
│  ├─ UICompRenderDialog.java
│  ├─ UICompMaskEditor.java
│  └─ widgets/...
├─ export/
│  ├─ CompExportSession.java
│  ├─ OutputModule.java
│  ├─ RenderQueue.java
│  ├─ CompOnlyPass.java
│  └─ FfmpegProcess.java
└─ mixin/
   └─ UIFilmPanelCompositorMixin.java   // Host B, optional
```

命名建议：

- mod id `bbscomp`；包 `dev.bbscomp`；Clip id `bbscomp:composition`。
- 工程文件 `.bbscomp`；打包 `.bbsae`。
- 版本 `0.x` 到 1.0；保存格式 `format/version` 独立于 mod 版本。

---

## 11. 风险与验证

| 风险 | 可能性 | 影响 | 验证/缓解 |
| --- | --- | --- | --- |
| BBS UI 承载不了完整 AE 时间轴/曲线 | 中高 | 致命 | P0 用 `UIKeyframeEditor` 试；不行就 fork/port 图编辑器（MIT）或自定义时间轴 |
| Iris 下 GL 状态/FBO 冲突 | 中 | 高 | P0 开关 shader；`GlStateGuard`；参考 POV depth manager |
| 深度读不到/精度差 | 中 | 中 | P0 depth blit；失败则深度效果降级 1.x |
| 实时性能不够 | 高 | 高 | 预览降分辨率/效果质量；导出慢可接受；comp-only 离线 |
| 文字 shaping/逐字布局 | 中高 | 中 | 1.0 整段 + 简单逐字；复杂排版 1.x |
| 形状布尔/trim/repeater | 中 | 中 | stencil 先做布尔；路径操作分阶段 |
| 2D 跟踪/蒙版跟踪 | 中 | 中 | 先做 BBS 锚点绑定；2D 跟踪 1.x |
| BBS 更新破坏内部类 | 高 | 中 | `BbsCompCompat` + 锁 2.7-1.20.1 + CI |
| 范围膨胀 | 高 | 高 | 按 1.0/1.x/不做 表；每波次验收 |
| 4K 文字软 | 高 | 中 | 文档说明；comp-only 全分辨率；post-blit 1.x |
| 缺少 Alpha/ProRes | 低 | 中 | comp-only 自己写 ffmpeg 管道 |
| 资产/工程丢失 | 中 | 高 | 自动保存/备份/收集/占位 |
| 许可 | 低 | 低 | BBS/POV 都是 MIT；保留版权 |

**最需要先验证的 6 件事：**

1. FrameOverlays 在 1.20.1 F6/面板导出里都出现，坐标/时间正确。
2. 自定义 FBO + shader 在 Iris 开/关下不污染 BBS。
3. depth attachment 能否 blit（Iris 开/关）。
4. `UIKeyframeEditor`/`UIKeyframes` 能否用 comp track 的 `UIKeyframeSheet` 驱动。
5. 自定义 Clip 序列化 + Addon 移除后保留。
6. 1080p/4K、窗口尺寸下的锐度/性能。

---

## 12. 前 6 周

| 周 | 任务 | 产出 |
| --- | --- | --- |
| 1 | 1.20.1 工程、BBS jar、dev client、Addon 入口 | 能启动、能注册事件 |
| 2 | FrameOverlays 测试卡；F6/面板导出；坐标/时间 | 5 秒测试视频 |
| 3 | FBO render graph（3 层/blend/mask/blur）；`GlStateGuard`；Iris 开/关 | 不花屏的合成测试卡 |
| 4 | CompProject/Document 最小模型；JSON 保存/加载；CompositionClip/Overlay | 影片里能挂 comp、能存读 |
| 5 | Dashboard “Compositor” 面板；FBO 预览；图层列表；`UIKeyframeEditor` 原型 | 能打开/编辑/预览的空壳 |
| 6 | depth blit；性能基线；P0 技术笔记；Go/No-Go | 决策 + demo |

**P0 demo：** 一个 BBS 镜头，画面里有角色/场景，上面叠 3 层 comp（图片/文字/形状，带蒙版和 blur），从 BBS 导出 MP4；编辑器能打开、改参数、保存、重开。

---

## 13. 需要你确认的

1. **时间预期**：1.0 核心现实预算 18–24 个月单人全职（顺利 14–18），你能接受吗？如果不能，你更愿意砍范围（比如先不做逐字/深度/队列），还是加人？
2. **编辑器宿主**：先做 Dashboard 独立标签页（官方 API，稳），还是一开始就做 POV 式 Film 第三页签（更贴影片，但要 Mixin）？我建议先 A 后 B。
3. **1.0 范围**：上面 8.x 表里的“1.0”你是否认？特别是：预合成/调整层/轨道遮罩/图层样式/逐字动画/表达式/跟踪/深度/队列/Alpha 都进 1.0；3D 文字/粒子/木偶/Roto 辅助进 1.x。
4. **视频素材**：1.0 要不要支持视频图层（而不只是图片序列）？支持的话要接 BBS 的 `VideoPlayer`/ffmpeg 解码，工作量 +2–3 周。
5. **UI 语言**：中文/英文/双语？

---

## 附录：关键源码/参考索引

| 主题 | 位置 |
| --- | --- |
| 1.20.1 Addon API | `bbs-fs-1.20.1/src/main/java/mchorse/bbs_mod/api/`、`src/client/java/mchorse/bbs_mod/api/` |
| FrameOverlays | `src/client/java/mchorse/bbs_mod/api/client/events/RegisterFrameOverlaysEvent.java`、`ui/film/FrameOverlays.java` |
| 覆盖层/导出时机 | `client/BBSRendering.java:411-492`、`BBSModClient.java:773-779` |
| 录制 | `utils/VideoRecorder.java:68-141`、`:320-374` |
| 影片文件 | `film/FilmManager.java:43-46` |
| 屏幕空间先例 | `camera/clips/misc/ImageClip.java`、`SubtitleClip.java` |
| 1080 虚拟坐标系 | `camera/data/Placement.java:20` |
| 关键帧/插值 | `utils/keyframes/`、`utils/interps/Interpolations.java` |
| 曲线编辑器 | `ui/framework/elements/input/keyframes/` |
| 字体 | `forms/forms/LabelForm.java`、`fonts/FontManager.java` |
| 表达式 | `math/molang/`、`camera/values/ValueExpression.java` |
| 图层 UI 先例 | `ui/dashboard/textures/layers/` |
| 渲染队列先例 | `ui/film/FilmQueueExporter.java` |
| Dashboard 面板 | `ui/dashboard/panels/UIDashboardPanel.java`、`UIDashboardPanels.java` |
| 自定义 Clip | `utils/clips/Clip.java`、`camera/clips/CameraClip.java` |
| POV 编辑器挂载 | `bbs-pov/src/main/java/Glaxium/POV/integration/mixin/bbs/UIFilmPanelPovMixin.java` |
| POV 编辑器 | `bbs-pov/src/main/java/Glaxium/POV/editor/UIPovEditor.java` |
| POV 深度管理 | `bbs-pov/src/main/java/Glaxium/POV/hand/render/PovHandDepthManager.java` |
| POV 构建 | `bbs-pov/build.gradle`、`gradle.properties` |

---

*这是 v2 方案。它假设你要把“完整小 AE”当 1.0 目标，而不是先做一个 HUD 工具。P0 的结论会改它；以源码和实测为准。*
