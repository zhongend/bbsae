# Photon 研究与合成器改造结论

> **引用声明：** [Photon](https://github.com/Low-Drag-MC/Photon) by **KilaBash** 使用
> **CC BY-NC-SA 4.0**。本项目只研究其 UI 组织与渲染管线思想，**不包含/不链接 Photon 的
> 源代码、着色器、素材或数据文件**，也不是 Photon 的移植版。完整声明见
> [`PHOTON-NOTICE.md`](../PHOTON-NOTICE.md)。

## 0. 结论先行

1. **合成器必须是独立编辑器，不能覆盖 BBS 影片编辑器。** BBS 影片编辑器有自己的世界预览、镜头、replay 列表、camera/replay 轨道和属性 dock；把合成器 full overlay 盖上去会同时遮住这些内容，还会产生两套时间线/输入焦点。上一版 overlay 是错误方向，已经撤掉。
2. **合成器需要自己的效果预览。** 它不是“把 BBS 世界预览当画布”，而是：合成文档 → 自己的 FBO 渲染图 → 预览面板显示 FBO。这样图层、蒙版、效果、调整层、预合成都能在合成器内实时看到，并且与最终 F6 导出走同一条渲染图。
3. **Photon 不能直接复用代码。** 它面向 **NeoForge 1.21.1 + LDLib2 + Minecraft 1.21**，本项目是 **Fabric 1.20.1 + BBS 2.7 + Java 17/21**；而且 Photon 使用 CC BY-NC-SA 4.0 并限制移植/再分发，不能把它的代码链接进这个 jar。可复用的是 **UI 组织方式和渲染管线思想**。
4. **BBS 原生轨道仍应复用。** 合成器的轨道区继续使用 BBS 的 `UIKeyframeEditor` / `UIKeyframeDopeSheet`，不自己实现一套关键帧控件；我们只在外围加播放头、刻度、缩放和业务 sheet。

---

## 1. Photon 的 UI 逻辑

### 1.1 Editor shell：左/中/下三块 + 可停靠视图

关键类：`com.lowdragmc.photon.gui.editor.FXEditor`

```java
public final FXHierarchyView hierarchyView;
public final SceneView sceneView;
public final FXTimelineView timelineView;

this.leftWindow.getLeftTop().addView(hierarchyView);
this.centerWindow.getLeftTop().addView(sceneView);
this.bottomWindow.getLeftTop().addView(timelineView);
```

它把“编辑器”拆成可停靠的 view，而不是把所有控件塞进一个长滚动：

- **左侧**：FX 层级（对象树、资源、搜索）。
- **中央**：`SceneView`，实时预览。
- **底部**：`FXTimelineView`，非线性时间轴。
- **顶部**：菜单、资源浏览器、工具栏、编辑器快捷键。
- **浮层**：对象信息、动画曲线、shader graph、post graph 等独立 view。

这给合成器的直接启示是：**层级 / 预览 / 检查器 / 时间轴是四个独立区域**，预览永远在中间并保持可见，而不是被其它面板盖住。

### 1.2 SceneView：预览是一个真正的“场景视图”，不是一张贴图

关键类：`com.lowdragmc.photon.gui.editor.view.scene.SceneView`

它有：

- `SceneMode.PLATFORM` / `SceneMode.REAL_WORLD`：平台场景或真实世界背景；
- `DrawMode.DRAW / WIREFRAME / BOTH`；
- `bloomEnabled`、`effectsEnabled`、`dynamicLightsEnabled`；
- `SceneView.simulateTo(time)`：拖动时间轴时**重建/快进模拟**，而不是只改数字；
- 预览浮层：对象信息、动画、gizmo、cull box；
- 预览工具栏：draw mode、场景范围、bloom/effects/lights 开关。

也就是说，预览有**自己的渲染状态和显示开关**，编辑时间轴会驱动预览，导出和预览共享同一套 effect/runtime 数据。

### 1.3 Timeline：底部非线性轨道

`FXTimelineView` 是独立的底部 view：

- 动画轨道、曲线/渐变/表达式 clip；
- signal/seed/audio/speed/control 轨道；
- 录制模式、播放/暂停、逐帧；
- 时间轴是**编辑器的主时间源**，SceneView 只是消费这个时间。

合成器对应做法：

- 时间轴仍是 BBS `UIKeyframeEditor`，sheet 来自合成层属性/效果；
- 播放头/时间码/Fit/缩放围绕它；
- 任何拖动都更新同一个 `time`，预览重新渲染同一时间。

---

## 2. Photon 的渲染管线

### 2.1 分层：场景渲染 → 后处理链 → 最终输出

Photon 不是“一个 shader 画完”，而是：

1. 场景/粒子渲染到一个 **color + depth** 的 render target；
2. `PostEffectStack` 收集本帧的效果请求；
3. 按效果权重/优先级混合参数，运行 **Effect Graph / Render Graph**；
4. 内建 bloom 在 HDR mip 链上执行；
5. 最后 blit 到屏幕或导出目标。

关键类：

- `client/postfx/runtime/PostEffectStack.java`
  - 每帧 `submit(effect, params, weight)`；
  - 权重混合、优先级、独立实例；
  - **`GLOBAL`** 与 **`EDITOR_SCENE`** 两个栈：编辑器预览不会污染世界；
  - `consumeAndExecute(chainInput, doBuiltinBloom, sceneDepthTexture)`。
- `client/postfx/runtime/PostFXPreview.java`
  - 编辑器预览请求一份“干净场景”的 color+depth 拷贝；
  - 预览对着这份 capture 应用后处理，避免和世界后处理互相污染。
- `client/postprocessing/PhotonPostProcessing.java`
  - 亮部提取 → 多级 downsample mip → upsample → 与源图合成 bloom；
  - HDR target、mip 尺寸管理。
- `client/postfx/runtime/RenderGraphRuntime.java`
  - Render Graph 编译缓存；资源变更时失效重编译；
  - 嵌套 fullscreen graph 的身份检查。
- `client/postfx/runtime/PostEffectStack` + `MaskGroups` / custom depth
  - fullscreen、mask group、custom depth 的 per-object 后处理。

### 2.2 编辑器预览隔离

这是最重要的一点：

```java
PostEffectStack.GLOBAL
PostEffectStack.EDITOR_SCENE
```

两个独立 stack。编辑器 SceneView 渲染时设置 `editorSceneRendering`，请求进入 `EDITOR_SCENE`，不会让世界画面带上编辑器里的 bloom/effect；反过来世界后处理也不会影响编辑器的干净预览。

合成器也应该这样：

- **合成器预览只读自己的 comp 文档和 effect 栈**；
- 不把 BBS 影片编辑器当前的 frame overlay 状态混进来；
- 预览和 F6 导出共享 `CompRenderer`，但预览可以有 FX/Mask 显示开关。

---

## 3. BBS 的渲染管线与为什么 overlay 是错的

### 3.1 BBS 影片预览

`UIFilmPanel` 的预览是 `UIFilmPreview`，它渲染：

- 当前镜头/世界；
- replay 中的角色/模型；
- 相机 clip；
- `FrameOverlays`（后处理式的屏幕层）。

合成器通过 `CompositionClip` 往 `ClipContext.clipData` 塞 `CompInstance`，然后 `CompositionOverlay` 在 BBS 的 frame overlay 阶段调用 `CompRenderer`，把合成 FBO 画到最终画面上。

这是一条**世界画面 → 合成 overlay → BBS 输出**的链路。

### 3.2 为什么不能把合成器 UI 覆盖到影片编辑器

上一版 overlay 的做法是：

```
filmPanel
  preview (BBS world)
  main (camera/replay timeline)
  editArea
  compositorPanel (full overlay)
```

问题：

- 合成器 full overlay 盖住了 BBS 的 **camera/replay 轨道** 和 **replay 列表**；
- 合成器自己的 preview 被设成透明，实际显示的是 BBS 世界预览，不是合成器 FBO；
- 合成器的“时间”与 BBS 影片时间/轨道混在一起，用户无法分清当前编辑的是合成还是影片；
- 输入焦点、dock、面板切换、保存/导出都属于 BBS 影片编辑器，合成器作为子 overlay 没有自己的生命周期。

这不是“HTML 能不能叠加”的问题，而是**编辑器边界**问题。正确结构是：

```
BBS Film Editor (世界预览 + 影片轨道)
        │  CompositionClip / frame overlay
        ▼
Compositor Editor (独立 dashboard panel)
  Layers | Comp Preview (own FBO) | Inspector
          Timeline (BBS UIKeyframeEditor)
```

因此本轮已恢复：`dashboard.setPanel(compositorPanel)`，合成器不再挂到 `UIFilmPanel` 上。

---

## 4. 本轮实际改造

### 4.1 恢复独立合成器

- `openCompositorForFilm` / `openCompositorForClip` 重新 `dashboard.setPanel(compositorPanel)`；
- 移除 `installOverlay` 的调用路径；
- 合成器预览重新走 `CompRenderer.render(...)`，显示自己的 FBO；
- BBS 影片编辑器保持原样，不再被覆盖。

### 4.2 预览内的效果开关（Photon SceneView 思路）

预览左上角新增：

- **FX**：开关效果 + 样式；
- **Mask**：开关蒙版/轨道遮罩；
- 左下角：`1920x1080  20fps  1 FX` 之类的预览信息。

这样可以直接对比“有/无效果”，并且不会影响导出——导出始终走完整渲染图。

### 4.3 渲染图参数化

`CompRenderer.render(...)` 新增可选的 `renderEffects` / `renderMasks` 参数：

- 预览可以关闭 effects/masks；
- F6/导出使用默认 `true, true`；
- 图层 FBO → 蒙版 → track matte → effects → styles → blend → adjustment 的顺序不变；
- 预合成递归和 adjustment 仍使用同一图。

### 4.4 修复蒙版上传的原生崩溃

调试时在 `glTexSubImage2D` 出现 `nvoglv64.dll` access violation，栈为：

```
MaskCache.rasterize
 → GlTexture.uploadTopDown
 → glTexSubImage2D
```

原因是 BBS 的图片上传可能留下了非零 `GL_UNPACK_ROW_LENGTH`，而蒙版上传是 tightly packed，驱动按旧 row length 读越界。现在上传前强制：

```java
GL_UNPACK_ALIGNMENT = 1
GL_UNPACK_ROW_LENGTH = 0
GL_UNPACK_SKIP_PIXELS = 0
GL_UNPACK_SKIP_ROWS = 0
```

这保证蒙版预览不再触发原生崩溃。

---

## 5. 与 Photon 的差距 / 后续路线

当前合成器还不是 Photon 级后处理系统，差距明确：

| Photon | 当前合成器 |
| --- | --- |
| HDR color + depth、bloom mip 链 | LDR FBO；效果在图层 FBO 上逐层应用 |
| Effect Graph / Render Graph 节点编译 | 固定 `CompEffect` 列表 + `EffectRenderer` |
| 每帧 effect request + 权重混合 | 每个图层 effect 列表直接应用 |
| Editor scene / world post 两个独立 stack | 预览与导出共用 `CompRenderer`，但预览可关 FX/Mask |
| 可停靠 view、独立 shader/post graph 编辑器 | BBS dashboard 固定布局 + inspector tabs |
| 时间轴 clip/record/信号轨 | BBS 关键帧 editor + 图层 sheet |

下一步优先级：

1. **合成级 Post FX 栈**：comp 级别的 effect list，在 accumulation 之后执行，类似 Photon `PostEffectStack`；
2. **HDR + bloom**：至少加入亮部提取/降采样/升采样，让 glow/bloom 效果真正出现；
3. **Effect Graph**：把 `CompEffect` 从固定参数升级为可连接的 pass 图；
4. **预览隔离**：预览 FX/Mask 开关已经做了第一步，后续把预览渲染与导出渲染的 options 完全分离；
5. **时间轴**：继续用 BBS `UIKeyframeEditor`，但在它上方增加 comp 图层 duration bar 和 post FX clip。

---

## 6. 参考

- Photon: <https://github.com/Low-Drag-MC/Photon>
  - `gui/editor/FXEditor.java`
  - `gui/editor/view/scene/SceneView.java`
  - `gui/editor/view/FXTimelineView.java`
  - `client/postfx/runtime/PostEffectStack.java`
  - `client/postfx/runtime/PostFXPreview.java`
  - `client/postprocessing/PhotonPostProcessing.java`
  - `client/postfx/runtime/RenderGraphRuntime.java`
- BBS: <https://github.com/Wemppy4/bbs-fs>
  - `UIFilmPanel`, `UIFilmPreview`, `FrameOverlays`, `CompositionClip`, `CompRenderer`.
- EffectCraft: <https://github.com/storytold/effectcraft>
  - 时间轴 / 项目层级 / Render Queue 的信息架构。
