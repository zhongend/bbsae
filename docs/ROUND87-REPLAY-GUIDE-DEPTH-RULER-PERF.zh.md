# Round 87 — 回放引导坐标 / 模型深度 / 关键帧标尺 / 自由视角缓存

> 版本 `0.99-hotfix51`。只加后缀，不迭代 1.0。按用户要求**未启动客户端截图测试**；本轮全部
> 结论来自渲染路径代码、纯几何单测和隐藏 GL 上下文的像素测试。相机视角路径未改。

## 1. 环绕/自由视角的线框、文字、gizmo 错位

### 根因：两个坐标系 + 一个没有同步的世界锚点

自由/环绕画面已经是**完整的合成帧**：2D 图层在合成像素里是屏幕固定的，3D 图层由自由相机
投影，整张栅格再 letterbox 到 `freePlate`。旧引导层却把每个图层都通过 3D 相机投影，并画在
视口原点：

- 2D 文字/形状的标签和包围框于是从它描述的画面漂走（相机一转就错）；
- 合成画幅被当成一个 z=0 的 3D 四边形投影，和实际 letterbox 后的画面边界不是同一条线；
- 回放世界锚点只在相机视角的 `lookThroughCompositionCamera()` 里同步到链接的合成图层，
  自由/环绕仍用切换前的旧锚点。模型本身由合成图层渲染，gizmo/pick 却由世界锚点映射，于是
  gizmo 与模型相差一个固定位移。

### 修复

- 新增 `ReplayGuideSpace`：直接复用渲染器的 `CompScene.project`（2D 返回合成像素，3D 返回
  自由相机投影后的合成像素），再按 `plate.w/doc.width`、`plate.h/doc.height` 映射到屏幕。
  合成画幅改为**画面矩形本身的屏幕描边**，不再投影一个假 3D 四边形。
- 图层包围框/中心/文字标签全部走同一映射；旋转、缩放、父级变换由 `CompScene.world` 处理。
- `UIReplayViewport` 在自由/环绕每帧调用
  `alignSpaceToLinkedLayer(doc, time, moveCamera=false)`：世界锚点与链接图层对齐，但不移动
  用户的轨道目标。相机视角仍走原来的 `lookThroughCompositionCamera()`，未改。
- 对齐不只是平移：`ReplaySceneSpace` 新增**线性覆盖**，自由/环绕安装链接图层的完整
  `layerWorldLinear · S`（默认回放图层是 scale 3，旧映射只用 `S`，actor 越偏离
  replayAnchor，gizmo 偏移越大）。`toComp/toWorld/centerOn/ReplayViewCamera` 全部走这
  同一个线性矩阵；相机视角清空覆盖、保持原来的标量映射。
- 自由视角渲染失败回落舞台时，引导/拾取/gizmo 自动改用 `viewArea`（`freeCompositionDrawn`
  标志），不再拿 `freePlate` 去套一个没有合成画面的舞台。

### 验证

- `ReplayGuideSpaceTest`：2D 图层在相机 yaw/pitch 变化后仍落在合成像素位置；3D 图层与
  `CompCamera.ofFree` 的投影逐点一致；旋转/缩放和 plate 偏移正确。
- 相机视角代码路径未修改。

## 2. 回放实体穿透文字/形状图层

### 根因：模型被当成平面参与画家排序

`orderLayers` 和 AUTO 逐像素深度门槛只理解“平面”：平行平面用整层画家顺序就是精确的，所以
AUTO 关闭了共享深度。但回放实体是**有厚度的模型**：一个在 z=0 的文字/形状平面可以穿过它，
整层排序只能决定“整个模型在前还是在后”，无法决定“模型的哪一半在平面后”。旧模型合成还是
一张平面卡片直接 `drawInto`，完全没有深度测试。

### 修复

- `CompScene.isModelVolume()`：独立回放实体（有模型/链接回放）是深度体积，即使旧工程的
  `threeD` 开关没打开，也参与 3D 排序、相机投影和 AUTO 深度判断。
- `GlFramebuffer` 支持**可采样深度纹理**；`ReplayActorPass` 的模型目标改用该模式，保留每个
  片元的真实深度。
- 新增 `MODEL_DEPTH` shader / `DepthPlaneCompositor.compositeModel()`：把模型深度写成
  `gl_FragDepth`，与文字/形状平面共用同一深度缓冲。平面在前就逐像素遮住模型，模型在前就
  逐像素盖住平面；模型与模型之间也按真实深度互相遮挡。
- AUTO 门槛：只要画面里有一个模型体积和另一个 3D 像素图层，就启用共享深度（平面是否平行
  不再影响这个判断）。
- 旧模型的 `threeD=false` 兼容：仍按相机投影渲染并加入 3D 排序，避免老工程继续穿透。
- 2D 文字/形状层保持 AE 语义：它们是屏幕固定的，按合成器栈顺序覆盖整个模型；不参与逐像素
  深度。导出/AE 帧服务器走同一个 `CompRenderer` 路径，2D/3D 预览与导出结果一致。
- 模型颜色直接按预乘 alpha 合成（shader 内反预乘），去掉每个模型的
  `unpremultiply` 全屏 pass 和一张全尺寸 FBO。

### 验证

- `ModelVolumeDepthTest`：平行平面 + 模型触发 AUTO；单个模型不需要共享深度；旧
  `threeD=false` 模型仍进入 3D 排序/透视。
- `ModelDepthCompositeTest`（隐藏 GL）：平面在模型前方时，模型前半保留、后半被平面覆盖；
  模型半透明边缘和透明背景正确丢弃。
- 既有 `PerPixelDepthAutoModeTest` / 深度平面/效果/导出回归全部保留。

## 3. 合成器关键帧抽屉时间轴字体重叠

### 根因：装饰器里又画了一整条标尺

`UIKeyframes.rulerRenderer` 是 `TimelineRulerRenderer.render` 内部的**装饰器**，不是替换。
旧代码在装饰器里调用 `TimelineRulerRenderer.render` 画了第二条完整标尺；默认标尺随后又用
帧单位画一遍标签，两套标签叠在一起。并且 `TimeUtils.formatTime` 按 20 ticks/s 解释帧号，
高倍缩放时相邻标签都变成 `0:00`。

### 修复

- 装饰器只调用 `CompTimelineUnits.set(fps, seconds)`，不再画第二条标尺。
- 新增 `TimelineRulerRendererMixin`：在 `renderTicks` 入口用 `CompKeyframeRuler` 替换默认
  刻度/标签 pass（仅当装饰器标记了本帧的帧率）。
- `CompKeyframeRuler` 按**帧**选步长（frame 模式 1/2/5；seconds 模式换算 BBS tick 步长并要求
  整数帧），标签使用 `CompTime.rulerLabel` 的百分秒格式，相邻标签不会重复成 `0:00`；主刻度
  间距由实际标签宽度决定。
- 只在合成器的关键帧编辑器上生效，其他 BBS 编辑器保持原标尺。

### 验证

- `CompKeyframeRulerTest`：60/30 fps 的 seconds 步长、帧步长、小刻度、百分秒标签不重复。
- `CompTimeRulerTest` 继续覆盖帧/秒换算。

## 4. 性能

- **自由/环绕画面缓存**：原来自由视角每个显示帧都跑整条 `CompRenderer` 图，即使相机停住。
  现在按 `doc/comp 签名 + 帧 + 自由相机 pose + 栅格尺寸` 缓存，静止时一张纹理quad；导航/
  gizmo 拖动时用 0.75× 代理分辨率，松手后自动重烘一张高分辨率帧。
- **世界帧就绪门**：需要 BBS 世界帧的合成不会把空世界缓存住；世界 copy 未就绪时保留上一张
  好帧，就绪后按 `worldGeneration` 失效重烘。
- **模型合成**：预乘 alpha 直接在合成 shader 中反预乘，省掉每个模型一次全屏 `unpremultiply`
  pass；逐像素模型深度用两 pass（opaque 颜色+深度一次写入，半透明边缘一次 LEQUAL）而不是
  三次全屏 pass。
- **标尺**：去掉重复整条标尺绘制和重复标签，关键帧抽屉每帧少一整轮刻度/文字。

## 5. 验证与限制

- 全量测试与新增测试结果见 `CHANGELOG.md` 的 `0.99-hotfix51` 条目。
- 未启动客户端；按用户要求在真实工程中验证：
  1. 自由/环绕下文字/形状/相机引导和 gizmo 与模型对齐；
  2. 3D 文字/形状卡片与回放模型互相遮挡；2D 文字/形状仍按图层顺序覆盖；
  3. 关键帧抽屉时间轴标签不重叠、秒制/帧制单位正确；
  4. 自由视角静止/ orbiting 的帧率与清晰度切换；
  5. 2D/3D 合成预览与导出结果一致。
- 相机视角、回放编辑器相机预览路径未改；导出/AE 路径只经过同一套 `CompRenderer` 深度修复。
- 候选包：`bbsae-0.99-hotfix51.jar`（3,943,058 bytes，SHA-256
  `DE957288390337B682D8D3780C4BF7F689679AE9F4D939484DDA93E732DFD74E`）。
