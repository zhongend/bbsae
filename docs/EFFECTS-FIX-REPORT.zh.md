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

## 8. Round 3 数据层修复（P1-A / B / C / D）

> 本轮只碰数据层：`client/render/CompScene.java`（仅 shake 相关）与
> `comp/{CompLayer,CompAnimationPreset,CompCurveChannel,CompStore}.java` 的对应方法/新增辅助。
> 未改 UI / GL / shader / replay / 构建脚本。全量：**110 suites / 669 tests / 0 failures / 1 skipped**。

### P1-A 抖动 / 震屏滑杆对渲染完全无效

根因：`CompScene.shakeOf()` 原来用 `CompLayer.effectParam(...)` 读 strength / amplitude / frequency /
rotation / seed，而它只是 `scalarAt("fx/<id>/<param>")`：**没有通道时直接返回调用方传的字面 fallback，
永远不读 `CompEffect.params`**。滑杆写入的正是 `params`，所以幅度、频率、旋转、种子、效果强度全都不生效，
只有"先 key 一个关键帧"才能动；不 key 时振幅永远退回 18px。

修复：

- `CompLayer#effectValue(CompEffect effect, String key, float fallback, float frame)`：先
  `resolveEffect(effect, frame)`（基值 = `params`，`fx/` 通道逐参数覆盖），再 `valueF(key, fallback)`。
  这是效果参数唯一正确的读取入口（基值 + 通道覆盖）。
- `CompScene#shakeOf()` 五个参数全部改走 `effectValue(...)`。
- `CompLayer#effectParam(String, ...)` 标注 `@Deprecated` 并改为 `effectValue` 的委托，避免旧调用点继续
  静默失效（语义从"只读通道"变成"基值 + 通道覆盖"）。

单测（`ShakeEffectValueTest`，6）：amplitude=200 且无任何通道 → `CompScene.local()` 的平移超过 50px
（旧实现最多只有 18px，所以"非 0"断言抓不到这个 bug，测试特意用位移阈值）；strength=0 冻结；
关键帧 strength 覆盖基值；只有关键帧无基值仍动画；`effectValue` 基值/覆盖/空 effect；`effectParam` 委托。

### P1-B 动画预设清空 / 注入内容通道

根因：`capture()` 直接 `copy.channels.putAll(layer.channels)`（含 fx / mask / shape / text 通道），
`applyTo()` 先 `target.channels.clear()` 再 `putAll(source.channels)`。于是"保存图层动画"会连蒙版/形状/效果
动画一起存；"应用预设"会删掉目标层自己的内容动画，并把源层的 `fx/<id>/...` 注入目标——id 对不上，形成
"幽灵动画"，还会在之后误伤同 id 的新效果。

修复：

- 新增 `CompAnimationPreset.isPresetChannel(String path)`：白名单，明确排除 `fx/`、`mask/`、`shape/`、
  `text/` 前缀，其余标量通道（transform 轴 / camera / timeRemap / squash 等）保留。
- `capture()` 只采白名单通道。
- `applyTo()` 不再全表 clear：先 `target.saveTracks()` 把运行时通道落盘（否则未保存的键会被合并丢掉），
  再把目标"非预设通道"（内容动画）收进 `preserved`，只替换预设拥有的属性通道，合并后 `loadTracks()`。
  `transformData`/`opacityData` 在 merge 之后才从源预设取，避免被 `saveTracks()` 覆盖。
- 旧预设（旧版 capture 写入了 fx 通道）在 apply 时同样过滤，不会继续污染旧工程。

单测（`CompAnimationPresetTest`，5）：白名单判定；capture 不含内容通道；apply 后目标 mask/shape/fx/text
通道原样保留、源 fx 不出现、transform/opacity 正常搬运；旧格式预设注入被过滤；空预设拒绝。

### P1-C 曲线编辑器改 fx 关键帧不写回

根因：`CompCurveChannel.insert()` / `setKeyValue()` 只调 `layer.putScalar(path, value)`，而 `putScalar`
对 `fx/` 路径是 no-op（效果参数在 `CompEffect.params`）。在曲线里拖模糊半径只会改关键帧：渲染仍读旧基值，
删光关键帧后参数回到旧值。

修复：新增私有 `CompCurveChannel#writeBaseValue(float)`：`fx/<id>/<param>` 镜像到
`layer.getEffect(id).put(param, value)`（与 `UICompositorPanel.setScalar()` 的既有处理一致，**未改 UI**）；
其余路径继续 `putScalar`。`insert` 与 `setKeyValue` 都走它。

mask/shape 支持性检查：`putScalar` 支持 mask 的全部可动画路径（feather / opacity / expansion + 每点
x/y/inX/inY/outX/outY）和 shape 的 x/y/width/height/roundness/rotation/trimStart/trimEnd/trimOffset/
repeater*/角半径/路径点；**唯一缺口是 `shape/<id>/fillGradientAngle`**：它出现在 `shapeAt()` 的读取里，
但 `putNestedScalar` / `nestedScalar` 都没有这个键（写入被 default 吞掉、曲线默认值读成 0）。属于 CompLayer
的缺口，不在本轮写域内，见剩余风险 1。

单测（`CompCurveChannelFxWritebackTest`，4）：insert / setKeyValue 后 `effect.params` 同步；删光关键帧保留
最后拖拽值；effect 已删除时 fx 通道不抛异常；mask/shape 写回回归。

### P1-D uniqueEffectId 复用被删除效果的 id

根因：`CompStore.uniqueEffectId()` 只按 effect 列表找最小空号，不看仍存在的 `fx/<id>/` 通道。删除效果后 id
被复用，新效果会"继承"旧效果的关键帧曲线（旧动画被劫持到新效果上）。

修复：新增 `CompStore.hasEffectChannels(CompLayer, id)`（同时检查运行时 `animatedPaths()` 与序列化
`channels` map，覆盖刚反序列化、尚未 `loadTracks()` 的状态），`uniqueEffectId()` 跳过仍有通道的编号。
删除效果的路径可直接复用该谓词。

单测（`CompStoreEffectIdTest`，6）：空层从 fx1 开始；已有 effect 跳过；fx/fx1、fx/fx2 通道但无 effect → fx3；
删除 effect 后 id 仍不释放；精确 id 匹配（fx10 不匹配 fx1）；序列化 map 也生效。

### 本轮变更文件 / 方法

- `client/render/CompScene.java`：`shakeOf()`（5 处读取改 `effectValue`）
- `comp/CompLayer.java`：新增 `effectValue(...)`；`effectParam(...)` 改 `@Deprecated` 委托
- `comp/CompAnimationPreset.java`：新增 `isPresetChannel(...)`；`capture()` / `applyTo()` 重写合并逻辑
- `comp/CompCurveChannel.java`：新增私有 `writeBaseValue(...)`；`insert()` / `setKeyValue()` 改走它
- `comp/CompStore.java`：新增 `hasEffectChannels(...)`；`uniqueEffectId()` 跳过有通道的 id
- 测试：`ShakeEffectValueTest`（新，6）、`CompAnimationPresetTest`（新，5）、
  `CompCurveChannelFxWritebackTest`（新，4）、`CompStoreEffectIdTest`（新，6），合计 21

### 验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 compileJava compileTestJava --offline --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --tests "dev.bbsae.client.render.ShakeEffectValueTest" --tests "dev.bbsae.comp.CompAnimationPresetTest" --tests "dev.bbsae.comp.CompCurveChannelFxWritebackTest" --tests "dev.bbsae.comp.CompStoreEffectIdTest" --offline --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
```

结果：`compileJava` / `compileTestJava` BUILD SUCCESSFUL；定向 21/21 通过；全量 **110 suites / 669 tests /
0 failures / 1 skipped**。

首次全量跑红在 `EffectLibraryNamingTest.visibleEffectNamesAreUnique`（`USM 锐化`：`unsharp_mask` 与
`am_unsharp_mask` 重名）。那是队友并行修改 `comp/AmEffectLibrary.java` + `comp/CompEffectRegistry.java`
（`am_unsharp_mask` 新增，随后 `unsharp_mask` 加入库隐藏列表）的 WIP，与本轮四项修复无关；队友补完隐藏项后
重跑全绿。

### 剩余风险

1. **`shape/<id>/fillGradientAngle`**：曲线能显示（`shapeAt()` 读它）但 `putScalar` 写不进、`scalar()`
   也读不到（默认值 0）。拖这个关键帧仍会"显示改了、基值没改"。修法是在 `CompLayer.putNestedScalar` /
   `nestedScalar` 补 `fillGradientAngle`——属于本轮写域限制外的方法，留给 Lead 决定。
2. **删除效果留下的 fx 通道不会自动清理**：`uniqueEffectId` 只是不再复用 id；通道会保留到工程被清理。
   这是"删效果不删动画"的既有刻意行为，本轮没有改变清理策略。
3. **`effectValue` 每次调用都 copy 一次 effect**：`shakeOf` 每帧最多 5 次；参数表很小，相对 GL 开销
   可忽略。若以后有热路径大量调用，可缓存一次 `resolveEffect` 结果。
4. **预设对 transform 是"整族替换"**：源预设没有某轴通道时，目标该轴通道会被清掉（基值字段保留），
   与旧行为一致；没有"只覆盖预设里存在的轴"这种部分应用语义。
5. 本轮为纯数据层 + 单测，未做客户端真机验证；建议 Lead 用"滑杆调 shake / 应用预设 / 曲线拖 fx 关键帧"
   三条路径截图确认。

## Round 3（0.94）效果 P1 批次 + 两处跨效果正确性修复

> 详细参数/差异见 [`EFFECTS-AM-GAP.zh.md`](EFFECTS-AM-GAP.zh.md) 第 3.0/3.2 节。

### 1. AM P1 模糊/调色/变形批（13 个新效果）

- 模糊（5 新 + 1 既有对齐）：`am_unsharp_mask`、`am_lens_blur_2`、`am_streak_strips`、`am_warp_blur`、
  `am_chromatic_zoom_blur`；既有 `am_spin_blur` 的 angle 范围/步进对齐 AM `spinblur2`（0..60°/0.1）
  并补显式恒等分支。旧 `unsharp_mask` 加入 `LIBRARY_HIDDEN`（AM 后继 `am_unsharp_mask`），
  保持"效果库同名唯一、隐藏 id 仍可渲染"的既有契约。
- 调色（5）：`am_spectral_map`、`am_soft_glow`、`am_dark_glow`、`am_light_glow`、`am_color_hot`。
- 变形（3）：`am_pinch_bulge_2`、`am_swirl_4`、`am_hex_rotate`。
- 全部走 `AmEffectDef` 数据驱动（无新增 shader 家族）；每个 body 都有显式恒等分支，零点契约分别用
  `zeroKey` 或 `neutralAt` 声明。`EffectP1BatchTest`（隐藏 GL）对每个条目断言"默认参数确实改变画面"
  与"中性配置在渲染器短路和强制 shader 两条路径上都逐像素还原"。

### 2. REPLICATE 直通 alpha 合成（预览与导出同时错 → 已修）

复制 pass 之前把直通 alpha 的源像素用预乘公式累加、又按直通输出：α=128 的红拷贝输出成
(128,0,0,128)，opacity=0.5 时复制体发黑，文字抗锯齿/发光软边重复后边缘发黑。
现在在预乘空间累加、结尾统一 un-premultiply；`EffectRepeatTest` 新增两个像素测试。

### 3. 预览 proxy 的像素单位（"预览=导出" → 已修）

三类"参数按合成像素、换算按 FBO 像素"的路径：RGB 分离的 `Texel`、投影/内阴影偏移
（`PassRenderer.draw` 新增 offset 参考尺寸重载）、图层样式 `inner()` 的距离/模糊半径
（`EffectRenderer.proxyScale`）。`PreviewProxyTest` 断言换算纯函数，
`EffectPipelineTest.proxyTargetKeepsPixelAuthoredEffectDistances` 用 1:1 与 0.5 两档渲染同一
RGB 分离图并断言同一相对位置采样一致。

验证：全量 `test`（串行 wrapper）**110 suites / 669 tests / 0 failures / 1 skipped**；本轮新增
`EffectP1BatchTest`（3）+ `AmEffectLibraryTest`（1）+ `EffectRepeatTest`（2）+
`PreviewProxyTest`（1）+ `EffectPipelineTest`（1）。未开客户端。

## Round 4（0.95）AM 剩余变形族 + repeat.path/文字判定

- **新增 6 个变形效果**（数据驱动 body，全部有零点契约 + 缩略图）：
  `am_pinch_bulge_inside`（内部收缩与膨胀，strength=0）、`am_wave_warp`（波浪扭曲，m2=0）、
  `am_turbulent_displace_3`（湍流置换 3，intensity=0）、`am_tileshift_2`（平铺错位 2，strength=0）、
  `am_stretch_segment`（拉伸段，stretch=0）、`am_omino_glass`（Omino 玻璃，strength=0；
  参考版折射下方合成，这里对图层自身做同样的噪声折射/色差/光照，2D/3D/导出一致）。
- **`repeat.path`：判定不做**。AM 的路径来自"路径图层"而非效果参数；本项目效果参数域没有路径引用、UI 没有
  路径选择器，用"第一个 PATH 形状/蒙版"当路径会静默无效并和蒙版语义冲突。需要先做"效果参数引用路径 + UI
  选择器"（跨 CompEffect/检查器/路径只读接口），不在本轮写域内——不做假实现。
- **文字 6 项：判定不做**。AM 里它们是非 shader 的文字图层属性/逐字符动画预设；本项目已用文字内容模式
  （timecode/counter/random/reveal）+ 文字动画器实现同等能力，再包装成效果只会产生重复入口且需要 UI 写域。
- 验证：全量 **117 suites / 699 tests / 0 failures / 1 skipped**；`EffectP1BatchTest` 的
  `DISTORT_BATCH` 复用"默认可见 + 中性双路径恒等"断言，`AmEffectLibraryTest` 增加 6 条零点断言。
  未开客户端、未 git commit。

## Round 5（0.96）AM 剩余可执行 shader 批

- **新增 12 个效果**（数据驱动 body，全部有零点契约 + 缩略图 + 中性/像素测试）：
  - 重复/形状：`am_shape_field`（形状场，合成空间执行，可越过图层矩形）。
  - 绘制：`am_electric_edges`（电弧边缘）、`am_roughen_edges`（粗糙边缘）、`am_smooth_bevel`
    （平滑斜面 2）、`am_halftone_cmyk`（CMYK 半调）。
  - 生成：`am_voronoi_2`、`am_ridges_2`、`am_noise_3`、`am_block_noise`、`am_simplestars_2`、
    `am_volumetric_clouds`。
  - 调色：`am_rgb_sep`（RGB 分离 2，AM 分离语义）。
- 参考实现依赖 blur/mask buffer 的 drawing 家族改为**自采样环 + fbm** 近似；生成类在 AM 无
  alpha/amount 控制的条目加了额外 `alpha`/`amount` 参数以获得真实零点（文档标注）。
- 名称唯一性：`am_smooth_bevel` 与既有 `am_bevel` 重名，改用"平滑斜面 2"。
- 剩余不可做项：`grunge`(135 参数) / `bumpmap2`(24) / `chromakey-adv`(21) / `lightning`(16) /
  `ribbon`(11) / `turbulence2`(15) / `ominodiff`(15) 等——参数表极大或依赖真实多 pass 光照/抠像流程，
  性价比低；`findedges/invert/posterize/threshold/dissolve/transform/offset/mosaic/sharpen` 等已有
  等价经典条目，不重复入库。重复 `repeat.path` 与文字 6 项维持 0.95 的判定（不做 + 原因见 gap 文档 3.5）。
- 验证：全量 **125 suites / 754 tests / 0 failures / 1 skipped**；`EffectP1BatchTest.SHADER_BATCH`
  （12 个）+ `AmEffectLibraryTest` 12 条零点断言；缩略图 12 张入库。未开客户端、未 git commit。

## Round 6（0.97 / task-27）五路径一致性审计修复（需求2）

> 依据 Lead 转发的只读一致性审计（2D 预览 / AE 预览 / 导出 / 3D 合成 / 3D 工作区五路径）。
> 全量 **129 suites / 798 tests / 0 failures / 1 skipped**；未开客户端、未 commit、未改
> `CompPrefs`/UI/`comp`/`AmEffectLibrary`/`PassRenderer`/`CompViewportRenderer`（写域外项已列在
> "仍需 Lead/UI"）。

### 1. 3D 层效果路径验证（审计前提纠正）

- `am_glass` / `am_light_wrap` / `am_displace_map` / `am_gradient_displace` 在定义里本来就通过
  `.comp()` 置了 `usesComp`（审计提到的 `COMPOSITION_SAMPLERS` 是冗余的，已确认无需新增渲染侧名单）。
  `AmEffectLibrary.usesComp(type)` → `requiresProjectedPass` → 3D 层烘培跳过、投影后带
  `context.backdrop` 运行，2D/3D/导出共用。
- 新增 `EffectPipeline3DConsistencyTest`（16 例）：分类矩阵（repeat 族 8、shape_field、4 个 comp
  采样、shake/depth_* 不进烘培；blur/invert/gradient/checkerboard/mirror/s3d_*/am_tile 族留在烘培）；
  3D 层栈 split 每效果恰好执行一次；fallback 全栈一次；重复/形状场在图层矩形外有像素；4 个 comp
  采样效果确实读取 backdrop（两种背景输出不同）；mask 先于效果（模糊可越出蒙版）；效果空间分辨率
  无关；preview/export 路由一致；`TextureOrientationTest`（2 例）钉住上传/全屏 pass 的 UV 上下约定。
- 文字翻转/预合成顺序：既有 `EffectOrientationTest`（4 例，2D 合成 / 3D 烘培 / 带 padding / 12 个
  内容采样效果方向）已覆盖审计关注的翻转类回归；本轮未改，记录为"已修 + 有守护"。

### 2. P0-1 光栅缓存键不含编辑器模式（已修）

根因：`CompLayerSourceCache.signature` 只混入 `renderEffects/renderMasks`，不含 `context.preview`/
`draftViewport`。3D 工作区先看过某层（草稿蓝板/BBS 预览占位）后，AE 预览/导出会复用同一光栅。
修复：signature 混入 `preview` + `draftViewport` 两个 bit（按模式分键）。
测试：`EffectPipeline3DConsistencyTest.rasterSignatureSeparatesDraftFromEncoderModes`（preview /
export / draft 三者互不相等）。

### 3. P1-2 同一 3D 层像素单位前后不一致（已修）

根因：烘培 `EffectSpace` 的参考网格是光栅宽（`compWidth`），投影后效果参考网格是合成宽；且
`scaleEffectForRaster` 只补偿 MAX_EDGE 段 —— 前段按图层像素、后段按合成像素，相机推拉/图层缩放
改变效果强度。修复：
- `EffectSpace` 新增 `refWidth/refHeight`（像素参数参考网格），`ofBounds(...)` 新增 ref 重载；
  烘培传 **父合成尺寸**；`EffectRenderer.refWidth/refHeight` 改读 ref 网格（proxyScale 自动换算）。
- 经典效果的像素参数不再在 `scaleEffectForRaster` 预缩放（改由 shader 侧 `proxyScale` 统一换算，
  避免双计）；只保留 AM 可分离 blur 的半径（直接进 FBO blur，按栅格像素计数）。
- `AmEffectRenderer` 的 `Size/Texel` 也改用 `space.refWidth/refHeight`（P3-5，AM 参考网格单一来源）。
测试：`bakeEffectSpaceUsesTheParentCompositionReferenceGrid`；`CompLayerSourceCacheTest` 的
`rasterScaleConvertsOnlyTheRawFramebufferBlurRadius` 同步新契约（经典半径不预缩放，AM 半径预缩放）。

### 4. P1-3 3D track matte 蒙版应用两次（已修）

根因：`renderLayerContent(matte)` 烘培时已把 matte 层的蒙版乘进光栅，matte 块又无条件
`MaskCache.get + PassRenderer.mask` → 覆盖率平方、边缘变暗。修复：抽
`CompRenderer.applyLayerMask(content, layer, context, model, masksBaked, masksEnabled)` 单一实现，
主层与 matte 共用；`masksBaked`（`LayerRender.effectsBaked`）时不再套第二次；未烘培的 3D 透视
matte 走 `getProjected`（与主层 direct 路径一致）；同时尊重 蒙版 A/B 开关。
测试：`masksApplyExactlyOnceAcrossTheBakeAndDirectPaths`（烘培=true 恒等、direct 一次、开关=false 恒等）。

### 5. P1-4 1024px 光栅上限作用于预览/导出（已修）

根因：`MAX_EDGE=1024` 无 preview 判断，1900 宽层导出从 ~1024 放大。修复：
`rasterMaxEdge(context)`：preview 保持 1024，非 preview（AE 预览/导出）至少取合成长边；
`rasterScaleFor(w,h,maxEdge)` 纯函数。signature 含像素尺寸，两种模式天然分键。
测试：`rasterCapKeepsExportDetail`（1900px 层：preview scale<1、export=1）。

### 6. P1-5 mask feather/expansion 分辨率语义 + 截断（已修）

根因：feather/expansion 按图层像素书写，但调用方 `scale` 是「光栅/合成像素」；且 128px 上限在
**缩放之后**的栅格像素上截断 → 预览 feather=200*0.5=100 不被截、导出 200→128，同一滑杆两种观感。
修复：`MaskCache` 先在图层像素上 `clamp(feather,0..128)` 再乘 scale（上限分辨率无关）；
`CompRenderer.maskLayerScale(model, rasterPerComp)` 把图层自身缩放并入 feather 换算（2D 主路径、
matte、调整层统一）。
说明：128 上限仍是性能保护；如要完全支持 200px feather 需改 `MaskCache` 的多 pass 半径，属于后续项。

### 7. P2-1 displacement 深度模式在烘培里退化为噪声（已修）

根因：`EffectRenderer` 深度图模式取 `context.hasDepth()`；烘培 proxy context 无 depth → `UseMap=0`。
修复：`requiresProjectedPassFor(CompEffect)`：`displacement` 且 `depth>0.5` 时强制投影后执行（动态
分类，`firstProjectedEffect`/`needsCompositionEffect` 均改用它）。
测试：`displacementDepthModeIsRoutedAfterProjection`。

### 8. P2-2 调整层蒙版无视蒙版开关（已修）

`applyAdjustment(...)` 增加 `renderMasks` 参数，蒙版块受它控制（此前只看 renderEffects）。
（调整层在 3D 工作区的合成尺寸/比例问题位于 `CompViewportRenderer`，写域外，待 Lead/UI。）

### 9. P2-4 绿帧 1280x720 被 AE 预览优先信任（已修）

`renderFrameCached` 只在 `CompPrecache.cacheScale(doc) >= 请求 scale` 时使用绿帧，否则回落到
`CompFrameServer.frame(...)` 按请求分辨率渲染。

### 10. P2-6 BBS 预览占位板进入导出（已修）

`LayerContentRenderer.drawBbsPreview` 增加 `context.preview` 门：占位蓝板与"等待世界帧"文字
只在编辑器预览绘制；导出/AE 预览渲染透明。

### 11. 顺带修复：3D 层 styles 与投影后效果的顺序

根因：烘培在效果前缀之后立刻套 styles，投影后效果再跑 → 顺序变成「效果前缀→styles→投影后效果」，
与 2D 的「全部效果→styles」不一致。修复：`CompRenderer.stylesRunAfterProjection(layer)`；烘培遇到
投影后效果时推迟 styles，合成分解 pass 在效果循环后套 styles（2D 行为保持）。
测试：`stylesRunAfterProjectionWhenTheStackHasProjectedEffects`。

### 12. 已知限制（写域外，需 Lead/UI 决策）

- **P1-1 / P2-3 3D 工作区**：`CompViewportRenderer` 只画缓存光栅 + 调整层，投影后效果（repeat 7 族、
  shape_field、4 个 comp 采样、shake、depth_*）、track matte、blend mode 在 3D 工作区不可见；且新建
  context 默认 renderEffects/renderMasks=true，不受预览条 A/B 开关控制。本轮已抽
  `CompLayerSourceCache.draftApplies(type)` + `draftSkipsExactlyThePostProjectionSet` 测试钉住该限制，
  并建议 UI 在 3D 工作区 HUD 明示"草稿不显示重复/合成类效果、matte、混合模式"；真正补跑需要改
  `CompViewportRenderer`（写域外）并承担每层一个全屏 pass 的成本。
- **P2-5 `PassRenderer.blur` 200px 上限**（`PassRenderer.java` 写域外）：上限在栅格空间，2D 导出 200
  栅格 px、3D 烘培因栅格缩放可能等价 300+ 合成 px。建议把上限换算到参考网格（`refWidth`）后再钳。
- **P2-8 `AmEffectLibrary.isNeutral` fallback=0**（comp 写域外）：缺参数时按 0 判定 → 部分效果"没参数
  就中性"被静默跳过。建议 fallback 用定义默认值；本轮未改，避免动效果定义。
- P3-1（`renderLayerContent` 未使用参数）、P3-4（`EffectSpace.of` 的 threeD 短路 vs usePerspective 双判据）
  属清理项，本轮只在有测试保障处收敛（AM 参考网格统一），未大改语义。
- 大图层（>1024px）烘培光栅仍受 MAX_EDGE 限制（预览）；导出上限已放开到合成长边。
