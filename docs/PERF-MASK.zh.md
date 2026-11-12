# 性能审计 P0-4 + P1-7：蒙版 / 形状 / 效果解析

任务板：`task-30`（实现 subagent）。范围：`MaskCache`、`ShapeCache`、`CompLayer` 的
`maskAt` / `shapeAt` / `resolveEffect` 缓存相关方法，以及对应测试。**未改**
`CompRenderer` / `CompLayerSourceCache` / `EffectRenderer` / `EffectSpace` /
`FontCoverage` / `LayerContentRenderer` / UI / `build.gradle`。

## 1. 结论

| 项目 | 修复前 | 修复后 |
|---|---|---|
| 静态蒙版 120 帧 | 每帧 `maskAt` 复制 + 拼路径串，全部命中（若 world 不变） | 内容键不变，**同一 GL 纹理对象**直接返回；0 次 AWT 栅格化 |
| 摄像机/图层 transform 变化的蒙版 | `hash(world)` 进 key ⇒ 每帧 miss：新 `GlTexture` + 淘汰旧纹理 + 1080p 的 `BufferedImage`/`int[]`/`float[]`（约 25 MB/蒙版） | world 不进纹理槽 key；**同一槽内重传像素**，GL 纹理、AWT 图像、float/int 缓冲全部复用 |
| 动画蒙版（点/羽化/扩展） | 每帧新 key + 新纹理 + 新缓冲；`mask.copy()` + 每点 6 个字符串 | 每帧仍必须重栅格化，但对象分配只剩内容指纹；路径串预计算，缓冲/纹理池化 |
| 3D 投影蒙版 | key 只 hash 仿射 6 项（透视项变化可能命中过期蒙版），每帧新纹理 | state 覆盖完整 4×4；透视摄像机移动时仍重栅格（采样方式限制），但**不 churn 纹理/缓冲** |
| 形状（静态） | 每帧 hash + 命中；未命中时 `new GlTexture` | 平移不变的像素键 + 几何缓存，命中直接返回同一 `Raster` |
| 形状（位置 K 帧） | 每帧 key 变化 ⇒ 每帧 AWT + 新纹理 | 相对位置不变 ⇒ **像素缓存命中**，只平移 `originX/originY`；GL 纹理不变 |
| 形状（旋转/裁剪/颜色 K 帧） | 每帧 AWT + 新纹理 + 删旧纹理 | 仍需重栅格化；复用同一 `GlTexture`，AWT 图像/`Graphics2D` 池化 |
| `resolveEffect` | 每效果 `copy()`：新对象 + 2 个 `LinkedHashMap` + `putAll`，且 `animatedPaths()` 调 2 次（各复制一份 key 集合） | 每效果一个深度为 8 的 scratch；**同一效果重复 resolve 返回同一对象**，`params` 原地复用（不新建 Entry），`animatedPaths` 改为直接遍历 channel keySet 一次 |
| `effectValue` | 每次先 `resolveEffect`（整份 map 复制）再读一个值 | 无通道时直接读 `params`；有通道时按 `scalarAt` 直读，语义与 resolved 对象一致 |
| 蒙版/形状递归 | 无（每次新建列表/对象） | 每层 8 层深度 scratch 池（预合成递归安全） |

新增/更新测试共 **39 个**，全部通过；其中 GL 用例在无显示时自动 skip。

## 2. MaskCache

### 2.1 键拆分：内容键（world-free）与上传状态

```
contentHash = f(resolved masks: mode/inverted/opacity/feather(已 clamp+scale)/expansion/points/closed/count)
slotKey     = layer.id + ":" + width + "x" + height        // 不含 world、不含 frame
state       = mix(contentHash, matrixHash(world), projected?)
```

* `frame` 不直接进 key：静态蒙版在不同帧解析出的值相同，因此 `contentHash` 相同。
* `world` 不进入 `slotKey`：一个图层 + 一个光栅尺寸永远只有一张蒙版纹理。
* `state` 只决定「槽里的像素是否还是这个状态」；相同状态直接返回，不同状态在**同一张
  GL 纹理**上重新上传。这样摄像机每帧变化不会再触发 `new GlTexture()`、LRU 淘汰和
  `delete()`。

> 为什么像素仍然依赖 world：蒙版 pass 用内容 FBO 的 UV 采样蒙版纹理
> （`PassRenderer.mask(content, mask.texture.id, 0)`），没有 model matrix uniform。
> 真正「层空间固定分辨率栅格化 + pass 内用模型矩阵采样」需要改 `CompRenderer` /
> `PassRenderer`（非本任务写域），见 §5 接点 A。当前实现已经把 world 从**纹理槽身份**中
> 拿掉，并把 world 变化降级为「复用槽 + 重传像素」，分配/GL 对象数为 0。

### 2.2 每层 scratch 池（深度 8）

`MaskCache` 为每个 `CompLayer` 保存 `LayerScratch`（弱 key），内部 `StackScratch[8]`：

* `masks`：池化的 `CompMask`，通过新增的 `CompLayer.maskAtInto(mask, frame, target)`
  原地写入（点列表按需增长/截断）。渲染路径不再调用会复制整份蒙版的
  `maskAt()`（`maskAt` 保留为 public API，行为不变）。
* `basePaths`：层空间贝塞尔路径缓存，仅当 `geometryHash`（点 + closed）变化时重建，
  重建也是 `Path2D.Float.reset()` 后原地重填。
* `contentHash` / `geometryHash`：同一次遍历算出，不分配字符串。
* 嵌套调用（预合成递归）取深度 1..7 的独立 scratch；深度 ≥8 时退化为局部临时对象。

### 2.3 缓冲池（每尺寸一份，LRU 2）

`RasterWork` 复用：`BufferedImage TYPE_INT_ARGB` + 其 `DataBufferInt`（直接读 alpha，
不再 `getRGB` 复制第二份 `int[]`）+ `float[] combined` + `float[] alpha` +
`boxBlur` 的 `float[] temp` + 复用的 `Graphics2D`。

* 1080p 单蒙版旧的约 25 MB/帧临时数组 ⇒ 稳态 0（缓存命中）或仅重填已有数组（动画）。
* 图像清空用 `AlphaComposite.Clear`，与新建 `TYPE_INT_ARGB` 图像像素一致。
* 所有尺寸随图层光栅大小变化，池按 `width*height` 复用，最多保留 2 组。

## 3. ShapeCache

三层缓存，各自的失效域分开：

1. **geometry**（每个池化 `CompShape` 对象，弱 key）
   `ShapeRasterizer.buildShape` 的输出本身包含 rotation/translate/trim，因此几何缓存用
   一个 x=y=0、rotation=0、trim 关闭的本地副本构建**图元轮廓**。每帧只做
   `append + rotate + translate` 到复用的 `Path2D`（顺序与 `buildShape` 完全一致），
   **像素对拍容差 ≤1/255**（`ShapeCacheTest`）。
   trim 非中性时回退到 `ShapeRasterizer.buildShape(shape)`（长度裁剪逻辑保留在
   `ShapeRasterizer` 内，避免复制一份裁剪算法）。
2. **raster**（像素缓存）
   key 使用「相对第一个启用形状的位移」而不是绝对 x/y：整层平移时
   `bounds` 与像素完全不变，只需把 `originX/originY` 加上位移差返回。
   单形状/所有形状同向移动的位置动画 ⇒ 命中。
   任一形状启用 repeater 时退回绝对坐标（repeater 绕 `data.x/data.y` 旋转）。
   其余字段（旋转/trim/颜色/渐变/布尔模式/相对布局）都进 key。
3. **texture**（每层最近一次 raster）
   像素真的变化时（旋转/trim/颜色/相对布局），若新尺寸与上次相同则复用它持有的
   `GlTexture` 并 `uploadRaw`，同时把旧 cache entry 从 LRU 移除（保证不会返回被覆盖的
   像素）；尺寸变化才新建纹理。避免「每帧 new GlTexture + 淘汰 delete」。

AWT 侧同样池化：每尺寸一份 `BufferedImage` + `Graphics2D` + 直接读 `DataBufferInt`；
`clear` 用 `AlphaComposite.Clear`，随后恢复 `SrcOver` 与单位变换。

像素对拍：`ShapeCacheTest` 内联保留了旧 `rasterize` 的实现（fill/erase/intersect/
exclude/gradient），逐像素比较新旧 ARGB（容差 1/255）。覆盖：填充矩形、渐变+描边、
add/subtract/intersect/exclude、旋转矩形 + 开放贝塞尔路径、trim + repeater。

## 4. CompLayer：maskAt / shapeAt / resolveEffect

### 4.1 路径字符串弱缓存

`MASK_PATHS` / `SHAPE_PATHS` 是以 `CompMask` / `CompShape` 为弱 key 的旁路缓存，
保存 `mask/<id>/p/<i>/<field>`、`shape/<id>/c/<i>`、`shape/<id>/p/<i>/<field>` 以及
固定属性路径。校验 key 为 `(id, pointCount, cornerCount)`，编辑 id 或增删点/角时自动重建。
模型类本身**没有新增字段**，序列化不受影响。

* 40 点蒙版：旧 240 个字符串/次 → 0（首次后）。
* 形状：16 个固定属性 + 角 + 6×点数 → 0（首次后）。

### 4.2 `resolveEffect` / `resolveEffectInto` / scratch

`CompLayer` 新增：

```java
public CompEffect resolveEffectInto(CompEffect effect, float frame, CompEffect target)
```

* `resolveEffect(effect, frame)` 仍保留原签名，但返回**每效果 scratch**：
  `EFFECT_SCRATCH`（弱 key = 活效果）→ `ResolveScratch`（`CompEffect[8]` + 深度计数）。
  同一效果重复 resolve 返回同一对象；不同效果、嵌套调用互不覆盖；深度 >8 退化为新对象。
* `params` 不再 `clear()+putAll`：先删除已从活效果消失的 key，再逐项 `put`
  （已有 key 复用 `LinkedHashMap.Entry`，稳态 0 Entry 分配），最后覆盖动画值。
* `animatedPaths()` 在该方法内只遍历一次：直接遍历 `scalarChannels.keySet()`，
  不再复制 key 集合（旧代码调了 2 次，等于 2 份 `ArrayList`/效果）。
* 返回对象仍与活效果是两个对象：写入 resolved 不会污染工程数据。

`effectValue` 增加直读路径：无通道 → 直接 `effect.valueF`；有通道 → 用
`scalarAt(path, base)`，其中 base 优先级与 `resolveEffect` 完全一致
（stored value > stored null=0 > 未存储时 strength=1/其它=0），空通道也与 resolved
对象的「中性默认」语义对齐。

### 4.3 maskAt / shapeAt 的兼容

* `maskAt` / `shapeAt` 仍是「返回一份副本」的 public API（既有测试/UI 不受影响）。
* 新增 `maskAtInto` / `shapeAtInto` 供渲染路径原地写入；点/角列表按需增长，
  不替换外部持有的列表。
* 实时蒙版/形状对象不被修改（有测试锁定）。

## 5. 仍存在的 miss / 未完成项（需要 Lead 接点）

**A. 真正的 world-free 蒙版采样（最重要）**
本任务不能改 `CompRenderer`/`PassRenderer`。若要做到「层空间栅格化一次，摄像机任意移动
都命中」，需要给蒙版 pass 增加 model matrix uniform，或在 pass 内用模型矩阵采样纹理。
当前 `get`/`getProjected` 在 world 变化时仍是 **re-raster**（只是不 churn 纹理与缓冲），
3D 透视摄像机每帧移动时 CPU 栅格化成本仍在。

**B. `CompLayerSourceCache:1052` 仍调用 `maskAt`**
那是 bake 缓存签名的构造，属于他人写域；把该处改为 `maskAtInto(..., scratch)` 可消掉
每帧一份蒙版副本。`CompLayerSourceCache:537/1050`、`CompRenderer:1207/1777`、
`EffectPadding:46` 的 `resolveEffect` 调用现在已自动复用 scratch，无需改动即可获益；
若要进一步做到「零拷贝」，可改用 `resolveEffectInto(layer 自己的 scratch)`。

**C. `CompRenderCache:370` / `CompLayerSourceCache:1348` 仍调用两次 `animatedPaths()`**
不在写域；各自少一次 key 集合复制。

**D. `GlTexture.uploadTopDown` 的上传缓冲**
仍由 `GlTexture` 在每次真正上传时 `memAlloc` 一块 `w*h*4` 的直接缓冲（1080p 约 8 MB，
仅动画/变换蒙版每帧触发）。把上传缓冲池化需要改 `GlTexture`（非写域）。

**E. 动画 trim / 旋转形状**
像素键正确失效，但仍需 AWT 重栅格化（trim 走 `ShapeRasterizer.buildShape` 回退）。
可复用纹理与缓冲，但 CPU 成本和旧实现相同。

**F. 多形状层中单个形状移动**
相对位置参与 key，单形状移动会 miss（像素确实不同）。这是正确的 miss。

**G. 蒙版纹理槽上限**
`MAX = 24` 个「图层+尺寸」槽；超过 24 个同时需要蒙版的图层仍会按 LRU 淘汰并删除纹理，
与旧行为一致（但同一图层不再因动画自我淘汰）。

**H. 槽复用契约**
同一图层在**同一帧内**用两个不同 world/内容状态调用 `get`，后一次会覆盖前一次纹理的像素
（每次调用都立即消费纹理，现有 5 个调用点都是 `get → PassRenderer.mask` 紧邻）。
若未来有「同一帧持有两张蒙版纹理」的用法，需要把槽改成按状态多槽。

## 6. 测试

新增：

| 测试 | 用例数 | 覆盖 |
|---|---|---|
| `MaskCacheKeyTest` | 6 | 静态蒙版 120 帧同 key；world 不进内容键/槽；world 变化只改 state；动画蒙版按帧变 key；feather/expansion/closed 失效；scale 只在有 feather/expansion 时改变 key |
| `MaskRasterSemanticsTest` | 9 | 单蒙版/feather+expansion/负扩展+invert/四种布尔模式/首蒙版 subtract/world 变换/非均匀缩放 feather ±1px/投影蒙版/不污染源蒙版，全部与 `maskAt + alphaFor` 参考实现逐像素对拍 |
| `ShapeCacheTest` | 10 | 旧 `rasterize` 内联对拍（填充/渐变描边/四种布尔/旋转+开放路径/trim+repeater）；静态 120 帧同 key；纯平移命中且像素相同只移 origin；单形状移动/旋转/trim/颜色失效；repeater 绝对坐标 |
| `EffectResolveAllocationTest` | 8 | resolve 两次 `assertSame`；不污染活效果；不同效果不同 scratch；按帧重算；`resolveEffectInto` 复用 target；`effectValue` 直读与 resolved 一致（stored / channel-only / 空通道）；strength 中性默认 |
| `CacheTextureReuseGlTest` | 6 | 静态蒙版 120 帧同一 GL 纹理；world 变化同一槽且像素已重传；动画蒙版同槽更新像素；静态形状同一 `Raster`；位置 K 帧复用纹理、只移动 origin；90° 旋转同尺寸复用纹理 |

保留并通过：`MaskPathRasterTest`（12）、`MaskCombineTest`（2）、`ShapeOpenPathTest`（6）、
`MaskGlRenderTest`（7）、`EffectApplicationTest`、`EffectPaddingTest` 等。

验证命令（串行 wrapper，离线）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 `
  test --offline --console=plain `
  --tests "dev.bbsae.client.render.Mask*" `
  --tests "dev.bbsae.client.render.Shape*" `
  --tests "dev.bbsae.client.render.CacheTextureReuseGlTest" `
  --tests "dev.bbsae.comp.EffectResolveAllocationTest"
```

## 7. 文件

| 文件 | 变更 |
|---|---|
| `src/main/java/dev/bbsae/client/render/MaskCache.java` | 内容键/槽键拆分、每层深度 8 scratch、纹理槽复用、AWT 缓冲池、几何缓存、完整 4×4 投影 state、测试钩子；`alphaFor`/`alphaForProjected` 与生产路径共用内核 |
| `src/main/java/dev/bbsae/client/render/ShapeCache.java` | geometry/raster/texture 三层缓存、平移不变像素键 + origin 调整、纹理复用、AWT 缓冲池、测试钩子；旧 `hash(List<CompShape>)` 保留 |
| `src/main/java/dev/bbsae/comp/CompLayer.java` | `maskAtInto`/`shapeAtInto`、路径串弱缓存、`resolveEffectInto` + 每效果深度 8 scratch、`effectValue` 直读、`animatedPaths` 单次遍历；无新增字段、序列化不变 |
| `src/test/java/dev/bbsae/client/render/{MaskCacheKeyTest,MaskRasterSemanticsTest,ShapeCacheTest,CacheTextureReuseGlTest}.java` | 新增 |
| `src/test/java/dev/bbsae/comp/EffectResolveAllocationTest.java` | 新增 |
| `docs/PERF-MASK.zh.md` | 本文档 |
