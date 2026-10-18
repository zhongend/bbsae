# 第一性原理审计（v1）

这份审计回答两个问题：**这个插件为什么这样设计**，以及**它现在是否“完美”**。结论先说：它在明确的 v1 范围内已经闭环；它不是完整 AE，也不应该假装是。下面列出我认为不能省略的推理、已经修掉的结构性问题，以及有意留在范围外的能力。

## 1. 第一性原理

### 1.1 画面从哪里来

BBS 影片的最终画面是**实时世界渲染 + FFmpeg 帧捕获**，不是离线 3D 渲染器。合成器不应该接管世界，也不应该复制一套 3D 管线。它只应该回答：

> 在这一帧已经被 BBS 画完之后、被 BBS 录进视频之前，我要叠加什么 2D/轻 3D 内容？

因此唯一的正确入口是 `RegisterFrameOverlaysEvent`。合成结果画进当前 framebuffer，BBS 的预览和 F6 导出天然一致。这个选择同时消除了“预览和导出两套渲染器漂移”的最大风险。

### 1.2 数据归谁

影片 `.dat` 是 BBS 的私有格式。合成器不应该解析或重写它。正确的边界是：

- 影片只存一个普通 CameraClip（`bbsae:composition`），里面存 project id / comp id / 时间参数；
- 合成工程存在独立的 `.bbscomp`（实际为 `.json`）里；
- 未知 Clip 在 Addon 移除后由 BBS 原样保留。

这样影片和合成器可以独立升级，合成器崩溃也不会破坏影片。

### 1.3 UI 归谁

BBS 已经提供 dashboard、list、keyframe editor、dope sheet、graph editor、trackpad、color picker、context menu、undo 输入行为。再写一套窗口框架只会产生两套交互、两套焦点和两套主题。因此：

- 按钮、开关、数值、颜色、文本、图层栈、轨道、关键帧菱形、右键菜单全部用 BBS；
- 唯一自绘的是预览视口，因为它显示的就是合成结果，不是控件。

### 1.4 渲染图的正确性边界

一个合成层必须按固定顺序处理：

```
内容 FBO
  -> straight-alpha 化
  -> 蒙版
  -> 轨道遮罩
  -> 效果链
  -> 图层样式
  -> 混合进 accumulation
```

调整层直接作用于 accumulation。预合成是一个独立时间/独立尺寸的子图，不能和父层共享时间或 FBO。

## 2. 审计中发现并修掉的结构性问题

1. **Alpha 表示不一致**：BBS 的 Batcher 往透明 FBO 画会留下 premultiplied RGB；效果链和混合却按 straight alpha 计算，旧代码会在蒙版/效果后再除一次 alpha，导致半透明边缘过亮。现在统一为 straight alpha：内容 FBO 渲染后立即 unpremultiply，所有 effect/style/composite pass 都在 straight alpha 下工作，模糊内部临时 premultiply 后再 unpremultiply；最终显示/导出也是 straight alpha。
2. **FBO pass 的混合状态错误**：全屏 shader 已经输出最终颜色，却又开着 `ONE, ONE_MINUS_SRC_ALPHA` 混合，等价于二次混合。现在所有 full-screen pass 写 FBO 时关闭 blend，只有 BBS Batcher 画内容/最终 blit 时保留正常 alpha 混合。
3. **Minecraft 状态缓存不同步**：自定义 pass 直接 `glBindVertexArray` / `glDisable(GL_BLEND)` 会让 `GlStateManager` 与 `BufferRenderer` 的缓存失真，导致 UI 出现 `Array object is not active`。现在 VAO/program/buffer/FBO/texture/blend/depth/cull 都通过 `GlStateManager`/`RenderSystem` 更新，并在自定义 pass 前后调用 `BufferRenderer.resetCurrentVertexBuffer()`。
4. **效果名不副实**：Radial Blur 曾经只是 Gaussian，Sharpen 曾经只是 Contrast；Box Blur 没有 box 权重；Inner Shadow/Glow 曾经是 Outer。现在 Radial/Box/Sharpen 有独立实现，Inner 风格有独立 shader，Gradient Overlay 是真实渐变；剩余的 Bevel/Satin 是近似，已在功能矩阵标注。
5. **效果参数没有接线**：Grid thickness、Circle radius/center、Wave amplitude、Fractal Noise octaves/contrast、Find Edges invert、Curves amount、Stroke opacity 曾经被 UI 暴露但没有真正使用。现在这些参数都进入 shader 或从注册表移除。
6. **表达式读取的是静态值**：`layer.foo.x` 以前返回基础 x，而不是当前帧插值。现在表达式按 context frame 解析 transform/opacity/scalar 属性。
7. **蒙版/形状/文字属性不可动画**：现在蒙版点、贝塞尔手柄、羽化、不透明度、正负扩展；形状路径点、trim、repeater、旋转；文字字号、字距、行高、描边都能进入 BBS 原生 float 轨道。
8. **FFmpeg 编码阻塞客户端线程**：旧实现 `process.waitFor()` 在 tick 里，编码期间游戏会卡死。现在编码进程异步启动，客户端每 tick 轮询退出码。
9. **预览网格/安全区设置没有接线**：现在 `gridSize`、`showGrid`、`showSafeArea`、`depthEnabled`、`autoSave` 都真正生效。
10. **排序/缓存边界**：Mask/Shape 缓存键现在包含当前帧解析后的几何，避免动画时复用旧纹理；precomp 和父层使用独立投影/时间。
11. **BBS 生命周期清理**：客户端停止时清理 shader/FBO/mask/shape/image/depth 缓存，而不是只留注释。

## 3. 已有证据

- `compileJava` 通过。
- 16 个测试通过：
  - 14 个 headless 模型测试（时间、插值、蒙版/形状/文字动画、时间重映射、表达式当前帧、预设、JSON、示例工程）；
  - 2 个隐藏窗口 OpenGL 测试（全部 16 个 GLSL 程序编译/链接；straight-alpha composite 像素值验证）。
- 开发客户端启动、面板注册、预览 shader 编译曾经通过；本轮按用户要求不再打开游戏/截图。
- 最终 jar 用 TinyRemapper 把 Minecraft 引用映射到 intermediary，BBS 类名保留。

## 4. 仍然不“完美”的地方

这些不是实现遗漏，而是 v1 的明确边界：

- 没有跨图层全局 undo 栈，依赖 BBS 控件行为、20 秒自动保存和 `.bak`；
- 没有代理/预渲染缓存，4K 重效果会掉预览帧率；
- 没有 OCIO/线性色彩管理/LUT；
- 没有 3D 文字/形状挤出、C4D/摄像机/灯光、木偶网格、Roto Brush、内容识别填充、绘画；
- 没有逐遮罩点跟踪；绑定的是整层锚点/相机/Replay；
- 没有 Object ID pass；
- 预合成时间重映射是 source-time 标量通道，不是完整 AE 时间重映射曲线；
- 预览 UI 还没有做最后的视觉打磨（按用户要求留到功能之后）。

## 5. 结论

在“BBS 内的屏幕空间 2D/轻 3D 合成器”这个定义下，v1 已经闭环：数据、编辑、渲染、影片集成、离线输出和错误恢复都有可验证路径。把它称为“完整 AE”是不诚实的；把它称为“BBS 创作者今天可以用的合成器”是准确的。
