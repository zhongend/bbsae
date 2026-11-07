# 效果库对齐计划（AM 效果体系）

目标：把「混合模式」「效果库」「参数语义」「图标」四项做到与 Alight Motion 的使用体验一致，
并按第一性原理保证 **每个参数拉到 0 都是原样**（少数天然无零点的效果除外，见第 4 节）。

> 说明：这里参考的是 AM 对外呈现的**效果清单、参数名称/范围/默认值与视觉行为**，作为互操作性
> 与视觉对齐的依据；所有着色器与逻辑都由本项目按同样的原理**独立实现**。项目与 AM 的文件格式、
> 运行环境互不相通，代码层面不存在共享实现。

## 1. 现状盘点（本项目 v0.24）

- 混合模式：13 种（正常/相加/正片叠底/滤色/叠加/柔光/强光/差值/排除/变暗/变亮/颜色减淡/颜色加深）
- 效果：79 个受支持的 id，按「模糊与锐化 / 调色 / 变形 / 生成 / 抠像 / 风格化 / 转场 / 3D / 重复」等分组
- 图标：效果库条目目前只有文字，无缩略图（`UIEffectLibrary` 的 `EffectRow`）
- 蒙版：图层内已有 add/subtract/intersect/exclude，但未出现在「混合模式」下拉中

## 2. 已完成的第 1 批（v0.25）

### 2.1 混合模式补齐到 30 种

`CompLayer.BLEND_*` 追加 13–29，**不重排既有 id**，老工程继续有效：

| id | 模式 | id | 模式 |
| --- | --- | --- | --- |
| 13 | 蒙版 | 22 | 划分 |
| 14 | 排除（蒙版） | 23 | 较暗颜色 |
| 15 | 线性加深 | 24 | 较亮颜色 |
| 16 | 线性减淡 | 25 | 色相 |
| 17 | 线性光 | 26 | 饱和度 |
| 18 | 亮光 | 27 | 颜色 |
| 19 | 点光 | 28 | 明度 |
| 20 | 柔光叠加 | 29 | 颜色相乘 |
| 21 | 减去 | | |

- **蒙版 / 排除（蒙版）** 是 alpha 运算而不是颜色公式：底色不变，只改变覆盖范围。
  `蒙版` = 下层 alpha × 本层 alpha；`排除` = 下层 alpha × (1 − 本层 alpha)。
  这就是「把本层当选区遮住下面」和「把本层形状从下面抠掉」的原始语义。
- 颜色类公式按标准分离式混合实现：线性加深/减淡、线性光、亮光、点光、柔光叠加、减去、划分、
  较暗/较亮颜色、色相/饱和度/颜色/明度（HSU/YCbCr 分离）、颜色相乘。
- 着色器 `ShaderSources.COMPOSITE` 新增 HSV 与 YUV 双向转换；所有分支统一 clamp 到 0..1。
- 新增 `BlendModesTest`：真实 GL 下逐模式合成 1×1 像素并与公式对照（含蒙版/排除的 alpha 语义）。

### 2.2 数据驱动的效果运行时（第 2 批的基础）

新增三层，效果不再需要写进 `EffectRenderer` 的巨型 switch：

- `comp/AmEffectDef`：id、显示名、分类、参数表（float/color）、`main()` 片段、零点参数。
- `comp/AmEffectLibrary`：35 个效果的定义（色彩平衡、色温、高光与阴影、卡通、长阴影、
  弯曲、描边、收缩与膨胀、圆形波纹、万花筒、镜像、旋转扭曲、像素化、极坐标、分形扭曲、
  色彩化、替换颜色、渐变映射、径向阴影），参数名/范围/默认值与参考应用一致。
- `client/render/AmEffectRenderer`：按定义拼装完整 fragment（标准 uniform + 参数 uniform +
  共享 `luma/rgb2hsv/hsv2rgb/hash`），懒编译并缓存；`sources()` 供无头编译测试逐条验证。
- `CompEffectRegistry` 在启动时把定义复制成普通条目，因此效果库、参数滑杆、颜色选择器、
  关键帧通道全部自动获得，无需改 UI 代码。

零点契约由 `AmEffectLibrary.isNeutral` 实现：`zeroKey` / `z(...)` 声明的参数全为 0 时
直接跳过该效果（返回输入 FBO）。首批效果中 色温/卡通/长阴影/弯曲/描边 都有明确零点，
色彩平衡与高光阴影在多参数全 0 时同样判定为原图。

验证：`ShaderCompileTest` 现在会编译全部 AM 片段（含真实 GLSL 链接），
`AmEffectLibraryTest` 校验注册/渲染器/参数表/零点语义，`EffectPipelineTest` 的对照表
最后一行已经能看到这 7 个效果的真实渲染结果（`docs/verify/effect-pipeline.png`）。

### 2.3 零点审计（“拉到 0 恢复原样”）

对 79 个既有条目重新核对了中性值，补齐 17 个原本没有零点的分支：亮度/对比度、色相/饱和度、
曝光、着色、色阶、曲线、反相、暗角、噪点、颗粒、抖动、遮罩收缩、着色映射、调色（三段色轮 +
曲线）、以及全部新增效果。新增 `EffectNeutralTest`：
- 每个有零点的效果在零点必须被渲染器判定为 no-op（连 FBO 都不分配）；
- 非零值必须判定为非中性；
- 明确列出“天然无零点”的 13 个效果（生成器、抠像、色阶分离、万花筒、像素化等），
  避免以后把遗漏当成通过。

### 2.4 效果库图标

`EffectIcons` 按分类映射到 BBS 自带的图标图集（调色=色轮、变形=变形、模糊=球体、
生成=棋盘、抠像=钥匙、转场=交换、重复=结构、3D=圆柱、文字=字体…），
`UIEffectList` 覆写 `rowContentX/renderElementPart`，在每行左侧预留 16 px 图标槽，
分类表头保持原有箭头与整行文字。图标随 UI 缩放，不依赖外部图片资源。

### 2.5 检查器分组分割线

新增 `UIInspectorDivider`：每个 `UISection` 之前插入一条「亮边 + 暗边」的两像素凹槽，
在 `refreshInspector()` 组装完成后统一注入，不侵入十几个 section 构建方法。
段标题因此成为右侧面板最醒目的层级，板块边界一眼可见。

## 3. 效果库对齐（后续批次）

### 3.1 参考清单的规模

AM 2026 版的效果目录共 **312 条定义**（含 24 个混合运算、33 个内部/辅助项、若干 deprecated），
其中 **266 条带可执行着色器**。按用途分布：

| 类别 | 数量 | 类别 | 数量 |
| --- | --- | --- | --- |
| 变形（distort） | 46 | 绘制（drawing） | 18 |
| 调色（color） | 44 | 遮罩（matte） | 16 |
| 生成（procedural） | 39 | 不透明度（opacity） | 11 |
| 模糊（blur） | 27 | 重复（repeat） | 7 |
| 3D | 26 | 文字（text） | 6 |
| 变换（transform） | 25 | 其他 | 3 |

每条定义由「参数表 + 一段像素函数」组成：参数类型有 `spinner/slider/color/point/texture/menu`，
带 `min/max/default/step/ticks`，部分效果还有 `deprecated/category/tags`。

### 3.2 引擎设计（不复用 AM 的代码，只对齐参数语义）

新增一层**数据驱动的效果运行时**，把「效果 id → 参数表 + 像素函数」从 `EffectRenderer` 的巨型
switch 中拆出来：

1. `AmEffectDef`：id、显示名、分类、参数表（float/color/point/menu）、空间（layer/comp）、
   边缘留白、是否生成器、零点判据。
2. `AmEffectProgram`：按定义在运行时拼装 fragment 源码（`Source`/`Texel`/`Size`/`LayerX/Y` 等
   标准 uniform + 参数 uniform），懒编译并缓存；编译失败降级为 no-op 并在日志中报出 id。
3. `AmEffectRenderer.apply(...)`：与现有 `EffectRenderer` 相同的输入/输出契约（FBO → FBO），
   自动套用现有的 `STRENGTH` 交叉淡化，因此「全局强度 0 = 原图」对所有新效果直接成立。
4. `CompEffectRegistry`：从定义表生成 UI 条目，效果库自动获得参数滑杆/颜色选择器/关键帧通道。
5. 图标：`EffectIcon` 按分类绘制 16×16 的矢量小图标（模糊=同心圆、变形=波纹、调色=色轮、
   生成=网格…），不使用任何外部图片资源；效果库行高 17px 的布局保持不变。

### 3.3 零点契约（「拉到 0 恢复原样」）

- 每个新效果必须声明 `neutral(effect)`：主强度参数为 0 时返回输入 FBO，**不执行着色器**。
- **像素级证明**：`EffectPipelineTest.zeroValuesRestoreTheInputPixelForPixel` 用真实 GL 管线跑
  13 个代表效果（方块破碎、波浪、模糊、湍流置换、弯曲、高斯模糊、收缩膨胀、圆形波纹、旋转扭曲、
  镜像、轴向拉伸、切片故障、彩色像差），在零点逐像素比对输入 —— 不是"看起来差不多"，是严格相等。
- **接近零也不该变**：方块破碎的碎块描边原本与进度无关，进度 0.005 时所有碎块还没起飞，
  却会被描边压暗 45%。现在描边强度乘以碎块自身进度 k（`mix(1, border, k)`），
  `blockShatterNearZeroKeepsTheCellsIntact` 以 0.005 进度 + 24 级容差钉住这个回归。
- 渲染层另有 `EffectRenderer.isNeutral` 白名单与 `STRENGTH` 通用交叉淡化双保险：
  即使某个效果在 0 处数值上不严格恒等，强度 0 仍逐像素还原。
- 生成器类（渐变、棋盘、云）没有自然零点，统一用 `STRENGTH` 归零；
  个别天然无零点的效果（如极坐标展开在 0 处仍会重排像素）在参数表里标 `noZero`，
  UI 会在参数旁提示「该效果 0 值不是原图」。

### 3.4 分批落地顺序

1. **混合与透明度**：24 种混合 + 蒙版/排除（已完成；见 2.1）
2. **变形核心**（本轮新增：收缩与膨胀、圆形波纹、万花筒、镜像、旋转扭曲、像素化；
   位移贴图类还需多纹理输入，放到下一批）：bend、pinch/bulge、circular ripple、kaleidoscope、hex shift、polar、mirror、
   displace map、curl、glass、fractal warp
3. **模糊**：box/gaussian/directional/lens/radial/motion（已完成）；chromatic/mask blur 需要
   多纹理或通道逻辑，放到后续批
4. **调色**：brightness/contrast、exposure、hue/sat、color balance、temperature、levels、
   curves、posterize、threshold、gradient map（已完成）、colorize（已完成）、replace color
   （已完成）、duotone/palette、glow/shadow 家族（径向阴影已完成）
4.5 **零点审计与图标**（已完成；见 2.3 / 2.4）
4.6 **第三批**（极坐标、分形扭曲、色彩化、替换颜色、渐变映射、径向阴影；已完成）
4.7 **模糊批**（高斯/方框/动感/径向/镜头模糊；已完成，纯模糊效果走两次可分离 pass）
4.8 **第四批**（彩色像差、移轴模糊、轴向拉伸、切片故障、四色渐变、条纹、网格、星空；已完成）
4.9 **背景采样批**（置换图、渐变置换、玻璃；已完成）
    - `CompRenderContext.backdrop` 承载"当前图层下方的合成"，由 `CompRenderer` 在图层效果循环与
      调整层处理期间设置/恢复；
    - `AmEffectDef.comp()` 声明效果读取 `Comp`，渲染器绑定 backdrop（无 backdrop 时回退到自身输入）；
    - `needsCompositionEffect` 与 3D 烘焙的过滤条件都会跳过背景类效果，保证它们只在合成阶段
      **执行一次**（3D 图层不会重复应用）。
5. **生成/风格化**：checker、grid、stripes、starfield、clouds、halftone、scanline、noise grain
   （噪点与颗粒按要求保持现状）
6. **3D 与文字**：s3d-* 形状与 text 效果（依赖 3D 管线，放到最后一批）
7. **收尾**：全量 shader 编译测试、每个效果 0 值回归、效果库图标与分类排版、文档

## 4. 验证方式

- `ShaderCompileTest`：所有运行时 fragment 在隐藏 GL 上下文里编译/链接（新增效果自动纳入）。
- `EffectPipelineTest`：真实 GL 跑完整效果管线并输出对照图。
- 新增 `AmEffectNeutralTest`（随第 2 批）：对每个新效果断言 `neutral(0) == input`。
- `BlendModesTest`：混合公式逐像素对照。
- 单元测试优先于开客户端：只有 UI/交互改动才做一次真机截图验证。
