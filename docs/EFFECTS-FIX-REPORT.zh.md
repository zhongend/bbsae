# 图层效果逻辑修复报告（需求 2：a/b/c/d）

> 本轮起点：0.91（72 suites / 379 tests 全绿）。所有结论来自代码路径推导 + 隐藏 GL 上下文的像素测试，
> 没有开客户端截图。构建从 ASCII junction `C:\bbsae-build` 跑（真实路径含空格/中文会让 Gradle test worker 崩）。

## 0. 一句话结论

四个用户反馈是**三个独立的第一性错误**叠加：

1. **图层本地栅格（3D 烘焙）用图层 UV 直接采样自己的纹理，漏了 FBO 的 V 翻转** →
   3D 图层上所有"会重采样内容"的效果把画面上下镜像。文字最容易被眼睛抓到，于是表现为
   "文字加效果后上下颠倒"；同一错误也让 3D 上的扭曲/位移/液态类效果采样到镜像区域，看起来"没效果"。
2. **投影后的效果仍用图层空间映射**，而它拿到的像素已经是**相机投影后的合成空间图像** →
   glass / 置换图 / 光包裹这类读合成背景的效果在 3D 透视图层上采错区域甚至完全空采样。
3. **重复类被实现成了"图层内像素平铺"**，而 AM 的 repeat 分类根本没有 shader——它是**图层复制器**。
   只有一个层大小那么大的栅格时，复制当然会被裁成"蒙版"。

另外两个放大因素：`AmEffectDef.padding` 声明了支持域但从未被消费（3D 上模糊/发光被图层矩形硬裁），
以及 AM 的纯模糊在栅格代理分辨率下半径没有按 scale 换算。

## 1. b) 文字图层加效果后上下颠倒（硬性验收）

### 根因

`AmEffectRenderer` 生成的采样函数原来有两条路：

```glsl
vec4 sampleLayer(vec2 luv)
{
    if (LocalSource == 1)                    // 3D 烘焙（CompLayerSourceCache.applyPixelOps）
        return texture(Source, clamp(luv, 0, 1));   // ← 错：把 layer UV 当纹理 UV
    return texture(Source, clamp(localToCompUv(luv), 0, 1));
}
```

- 图层内容用 **y-down ortho** 光栅化：本地 y=0（图层顶部）落在 FBO 纹理 **v=1**；
- `layerUv(vUv)` 返回的 `luv` 也是 y-down：`luv.y=0` 是顶部；
- 用 `luv` 直接做纹理坐标就变成"顶部采到 v=0"，**整层上下镜像**；
- 只有 `LocalSource=1`（正是 3D 烘焙分支）会走错路；合成路径走 `localToCompUv` 是对的。
  这就是为什么 2D 预览正常、一开 3D / 弄成 3D 图层就翻。

### 修复

删除这条捷径：`sampleLayer` 永远走 `localToCompUv`。在烘焙路径里这依然精确——
`applyPixelOps` 的代理合成尺寸就等于栅格尺寸，`LayerX/LayerY/LayerOrigin`（= scale/translate 到栅格）
把它映射回栅格 UV，并且**天然包含 padding 的偏移**；原来的捷径在带 padding 时还会按图层矩形缩放采样。

### 验证

`src/test/java/dev/bbsae/client/render/EffectOrientationTest.java`（隐藏 GL，4 个测试）：

- `compositionSpaceKeepsTheRasterOriented`：2D 合成空间，上半红带 + blur/gradient/glow 后
  质心仍在上一半（对照"不加效果"）；
- `layerLocalBakeKeepsTheRasterOriented`：3D 烘焙（代理 comp = 栅格），同上；
- `layerLocalBakeWithPaddingKeepsTheLayerRectOriented`：栅格 = 图层框 + 16px padding 时，
  图层矩形内的内容方向仍然正确（旧代码在这一项上同时有翻转 + 缩放错位）；
- `everyContentSamplingEffectKeepsTheBandOnTop`：12 个真实内容采样效果（高斯/方框模糊、渐变、
  内外发光、光包裹、玻璃、色彩增益、RGB 伽马、动感/镜头模糊）默认参数逐个过 3D 烘焙，
  红带必须留在上半。

这组测试用的"字形式"非对称 alpha 图案就是文字图层走的同一段 栅格→效果→读回 契约；
文字渲染需要 BBS 字体栈，headless 下不可用，所以用图案替代字形像素，管线完全一致。

## 2. a) 3D 模式下部分效果没有效果 / 只生效一半

按影响大小排列的真实根因：

| # | 根因 | 表现 | 修复 |
|---|------|------|------|
| A1 | 同 b 的 `LocalSource` V 翻转 | 3D 图层上重采样类效果镜像/采错区域 | 删除捷径，统一 `localToCompUv` |
| A2 | 烘焙之后的效果仍用 `EffectSpace.of(layer, world)`；但 3D 透视图层在 `renderBaked3D` 后内容已是**相机投影后的合成空间像素** | glass/置换图/光包裹等 `Comp` 读取类在 3D 上采样错位、看起来"没效果"；实体层同理 | 新增 `CompRenderer.effectsRunOnProjectedContent()`：`CompScene.usePerspective` 为真时，投影后效果统一用合成空间（identity）映射 |
| A3 | `AmEffectDef.padding`（支持域比例）从未被消费；`EffectPadding` 只认经典 id | 3D 上 AM 模糊/发光/长阴影/描边只要有内核就出不了图层矩形，被硬边裁掉 | `EffectPadding.amEffectPadding()`：声明值 × 图层长边；纯模糊按 `blurKey` 半径 +2 |
| A4 | 纯模糊（blurKey）用分离 FBO 模糊，半径是**栅格像素**；代理栅格缩小时半径视觉放大 1/scale，且 padding 只按图层像素算 | 大图层（栅格降采样）在 3D 上模糊过强/留白不够 | `CompLayerSourceCache.scaleEffectForRaster` 对 AM `blurKey` 半径乘 raster scale |
| A5 | 重复类在烘焙栅格里平铺 | 只能写到图层矩形内（见 c） | 移到合成空间 pass（见 c） |

同时把 AM 定义里明显缺失的支持域补上（`am_outline/glow/inner_glow/edge_glow/inner_shadow/gloss/
bevel/contour/matte_fringe/border/stroke_taper/feather/inner_blur/lens_flare/sketch/smooth_edges`
的 `padding` 由 0 改为 0.1–0.5 的图层长边比例）。

验证：`EffectApplicationTest.projectedLayersRunTheirPostBakeEffectsInCompositionSpace`（分类逻辑）、
`EffectPaddingTest.referenceEffectsReserveTheirDeclaredKernel` / `neutralReferenceEffectsNeedNoMargin`、
`CompLayerSourceCacheTest.rasterScaleConvertsTheReferenceBlurRadius`；A1 由上面的 4 个方向测试覆盖。

## 3. c) 重复/平铺类效果被图层大小裁成"蒙版"

### 审计（第一性原理：AM 里这个效果到底做什么）

从 `_research/am/assets/effects/*.xml` 与 `_research/am/effects.json` 直接看定义：

| 分类 | AM 证据 | 语义判定 | 本项目处理 |
|------|---------|----------|-----------|
| repeat（repeat / repeat.grid / repeat.line / repeat.radial / repeat.scatter / repeat.path） | `shaderPresent=false`，参数只有 count/offset/angle/scale/alpha 等 **图层复制** 参数 | **图层复制器，画到合成各位置，不受原图层矩形限制** | 改为合成空间 REPLICATE pass（本轮） |
| tile / tilerotate / hextile（distort） | 有 shader，`texture2DCv(inputImg.texture, l2s(scaledCoord))`，采样仍在内容内 | 图层内平铺/裁切，**不是**无限重复 | 保持图层本地 |
| mirror / kaleidoscope | 有 shader，采样 `l2s(layerSpace)`，输出取被采样像素的 alpha | 图层内折叠，**不越过图层** | 保持图层本地 |
| grid / stripes / starfield / dots / clouds（procedural） | 有 shader；`grid.xml` 是 `color * texColor.a`，`starfield.xml` 有 `if (texColor.a <= 0.0001) discard;` | 生成色只作用在图层自身像素上，**AM 自己也不越过图层 alpha** | 保持图层本地；想要整幅生成时用调整层（调整层本来就是合成空间） |
| 4 色渐变 / 径向渐变 / 渐变叠加 / 光扫等"生成+混合" | 有 shader，alpha 取输入 | 同上 | 保持图层本地 |
| 3D 图元（s3d_*） | MeshRenderer，画进栅格 | 图层自身像素 | 不变 |

结论：**只有 AM 的 repeat 分类需要"无限"**；其它"生成/镜像/网格/星空/万花筒"在 AM 语义下本来就是
图层内容效果，把它们也改成越界会违背 AE/AM 的图层语义。报告里保留这张表，避免下一轮再改错。

### 统一机制（2D/3D 同一套）

新增 `ShaderSources.REPLICATE` + `CompShaders.replicate` + `EffectRenderer.replicate`：

- 这是一个**合成空间全屏 pass**：对每个输出像素，对每个复制体做逆变换采样输入图层图像；
  复制体位置/尺寸来自 `EffectSpace.centerUv()` 与 `sizeU/sizeV`，所以每个复制体保持图层自身的
  屏幕尺寸，**只有合成边界**限制结果。
- 分类上进入 `EffectRenderer.isCompositionSpace(type)` → `CompRenderer.requiresProjectedPass` 为真：
  - 2D 图层：直接在合成尺寸的内容 FBO 上跑（本来就覆盖整幅）；
  - 3D 图层：烘焙阶段跳过（栅格只有图层矩形），投影之后在合成空间跑；
  - 3D 的复制体中心/尺寸由 `CompRenderer.projectedEffectSpace()` 投影图层四角得到（能被透视正确缩放）。
- 覆盖的效果 id：`grid_repeat`、`random_repeat`、`am_grid_repeat`、`am_line_repeat`。
  其中 `am_grid_repeat/am_line_repeat` 现在与 AM repeat 家族同样语义（复制整层），参数表保留本项目
  已验证的列/行/间距/占空比命名（完整 AM 参数表差异见 `docs/EFFECTS-AM-GAP.zh.md`）。

验证：`EffectRepeatTest`（隐藏 GL，6 个测试）：2x1 网格复制的两个复制体出现在 x=16/48
（原图层框只有 28..36）、投影路径同样复制、AM 两个重复条目默认 2x2/线性复制、零不透明度不分配 pass、
重复类永不进入图层烘焙（`requiresProjectedPass` / `firstProjectedEffect`）。

## 4. d) AM 参数对齐

见 `docs/EFFECTS-AM-GAP.zh.md`（人工结论）与 `docs/EFFECTS-AM-GAP.generated.md`
（脚本 `_tools/am-gap/compare.py` 自动生成，277 条非 internal AM 定义 × 122 条本项目 AM 定义）。
本轮修复了重复语义（c）与 3D 采样/支持域（a）；参数表的高优先级差异按
"可直接对齐 / 语义转换（零点契约）/ 单位转换 / 缺少选项 / 缺少效果" 五类归档，避免盲目把 AM
的归一化参数范围套到本项目已经按像素/零点契约重新标定的参数上。

## 5. 测试与命令

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
. .\_tools\gradle-env.ps1
.\gradlew.bat test --offline --console=plain
```

全量结果：**90 suites / 511 tests / 0 failures / 1 skipped**（基线 72 suites / 379 tests）。
构建必须从 ASCII junction `C:\bbsae-build` 跑；本轮后期改用 Lead 的串行包装器
`_tools/gradle-run.ps1`，避免多人并行删 build 目录。

本轮新增/扩展的测试类：

- `EffectOrientationTest`（新，4）：2D 合成 / 3D 烘焙 / 带 padding 的烘焙 / 12 个内容采样效果的方向
- `EffectRepeatTest`（新，6）：2D 与投影后的复制、AM 重复条目、零值、不进入烘焙
- `EffectApplicationTest`（+1）：投影后效果使用合成空间
- `EffectPaddingTest`（+2）：AM 支持域 / 中性不占留白
- `CompLayerSourceCacheTest`（+1）：AM 模糊半径的栅格缩放
- `AmEffectLibraryTest`（+1）：11 处 AM 范围/步进对齐
- `ShaderCompileTest`（+2）：`REPEAT` / `REPLICATE` 纳入全量 shader 编译

隐藏 GL 测试类的上下文纪律（本轮踩到的坑，已修）：每个 GL 测试类都会新建隐藏上下文，
静态缓存（`CompShaders` / `AmEffectRenderer` / `MeshRenderer` / `Viewport3DRenderer` / `FboPool`）
里的 GL 名字属于已销毁的上下文。**必须在编译新程序之前先清空这些缓存**（否则旧名字可能和新对象撞号，
表现成随机的 GL 1281/1282 与零点偶发不恒等）。`EffectPipelineTest` / `EffectOrientationTest` /
`EffectRepeatTest` / `MaskGlRenderTest` 现在都在 `@BeforeAll` 先清缓存、`@AfterAll` 清空离场。

## 6. 变更文件清单（本轮）

主代码：

- `client/render/AmEffectRenderer.java`：删除 layer-local `LocalSource` 采样捷径（b 的根因）
- `client/render/EffectRenderer.java`：REPLICATE 合成空间复制 pass、重复类分类、AM 重复 id 分派
- `client/render/ShaderSources.java`：新增 `REPLICATE` 全屏 shader
- `client/render/CompShaders.java`：注册/释放 `replicate`
- `client/render/EffectSpace.java`：`centerUv()`、`sizeU/sizeV`、带中心/尺寸的 identity 空间
- `client/render/CompRenderer.java`：投影后效果改用合成空间、`projectedEffectSpace()`、
  `effectsRunOnProjectedContent()`、`requiresProjectedPass` 纳入复制类
- `client/render/EffectPadding.java`：消费 `AmEffectDef.padding` + blurKey 半径（3D 支持域）
- `client/render/CompLayerSourceCache.java`：AM 模糊半径按栅格 scale 换算（保留 Lead 补的两行指纹）
- `client/render/CompRenderCache.java`：signature 加入 `perPixelDepth`（Lead 同意的需求 3 关联修复）
- `comp/AmEffectLibrary.java`：17 处支持域 padding、11 处 AM 参数范围/步进对齐、六边形下限与 shader 下限

测试：`EffectOrientationTest`（新）、`EffectRepeatTest`（新）、`EffectApplicationTest`、
`EffectPaddingTest`、`CompLayerSourceCacheTest`、`AmEffectLibraryTest`、`ShaderCompileTest`、
`MaskGlRenderTest`（只把 `initRenderThread` 包进 try/catch，修跨类初始化偶发失败）、
`EffectPipelineTest`（补跨上下文缓存清理）。

资源：重新生成 4 张重复类效果缩略图（`am_grid_repeat` / `am_line_repeat` / `grid_repeat` /
`random_repeat`），与新语义一致。

文档/工具：`docs/EFFECTS-FIX-REPORT.zh.md`（本文件）、`docs/EFFECTS-AM-GAP.zh.md`、
`docs/EFFECTS-AM-GAP.generated.md`、`_tools/am-gap/compare.py`。

**未改**：`MaskCache.java`、`ShapeCache.java`、`ShapeRasterizer.java`、`comp/CompMask.java`、
`comp/CompShape.java`、`comp/CompPathGeometry.java`、`CompPenSession.java`、`client/replay/**`、
`client/ui/UICompositorPanel.java`、`UICompSettings.java`、`UICompTrackTimeline.java`、
`client/ui/CompPrefs.java`、`CompKeybinds.java`、`client/render/CompScene.java`、`CompCamera.java`、
`comp/CompProject.java`、`gradle.properties`。

## 7. 剩余风险

1. 文字渲染本身无法 headless 验证，方向契约用非对称图案覆盖同一条栅格/效果路径；最终仍需 Lead 真机
   用"文字 + 效果"对"文字"截图确认。
2. 合成空间复制在合成尺寸 FBO 上做 8×8 上限的循环（每像素最多 64 次采样）；默认参数 4×3=12 次。
   极端参数下开销上涨，已把 UI 参数上限钳在 8×8。
3. 3D 复制体的屏幕尺寸用四个角投影得到；相机贴近图层、部分角点在相机后时退化为
   `layerSize × camera.zoom`，此时复制体尺寸是近似值。
4. AM 参数表的"名称/默认值"仍有系统性差异：本项目把 AM 的归一化强度改成了像素/零点契约参数
   （详见 gap 文档第 2 节），这是为可动画/可预览做的刻意取舍，不视为回归。
5. 重复类缩略图的测试卡是满幅图层，复制体（原尺寸）在 4×3 网格里相互重叠，缩略图比旧版更"满"；
   实际小图层场景是清晰的复制体。若要更漂亮的缩略图需要给缩略图单独构造小图层，不属于效果逻辑。
