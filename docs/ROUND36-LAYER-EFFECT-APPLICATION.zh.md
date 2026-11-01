# Round 36 — 图层与效果应用（0.20.3）

这一轮只回答一个问题：**一个效果应该在哪个阶段、对哪一份像素、应用几次？**
之前 0.20.0 为了修“3D 图层效果失效”，把效果从“层空间烘焙”改成了“投影后再应用”，
但层空间的烘焙其实一直在运行——于是同一效果被应用了两次。

## 1. 3D 图层：效果被应用了两次

### 1.1 实际链路

3D 图层带效果时，`renderLayerContent()` 走 `renderBaked3D()`：

```
CompLayerSourceCache.get(layer)
  -> rasterize(layer)                  // 层内容，层空间
  -> applyEffects(layer, straight, …)  // 非深度效果，层空间 + EffectSpace.ofBounds
renderBaked3D 把带效果的 raster 按相机投影
```

`CompLayerSourceCache` 的签名测试也明确写着：效果被烘焙进层空间 raster，所以效果 id/
enabled/参数/顺序都参与签名。也就是说，**非深度效果在投影前已经应用过一次**。

0.20.0 的 `renderCompToFbo()` 却把 `effectsBaked` 硬编码成 `false`，然后在投影后再次遍历
`layer.effects`。结果：

- 模糊半径 10 → 实际 20；
- 亮度 +0.2 → 实际 +0.4（或直接 clamp）；
- `gradient_ramp` 先在层空间生成一次，又被 composition 空间的渐变覆盖，于是相机一动
  渐变就从图层上“滑走”；
- 深度效果本来就在投影后应用，没有问题，但非深度效果全部重复。

### 1.2 修复

`renderLayerContent()` 现在返回 `LayerRender`：

```java
final GlFramebuffer framebuffer;
final boolean effectsBaked;   // 非深度效果已经在 raster 里
```

- `renderBaked3D` 成功 → `effectsBaked = true`；
- 源缓存失败/`renderEffects == false` → 走直接路径，`effectsBaked = false`，效果在投影后
  作为兜底运行**一次**；
- 组合循环用 `needsCompositionEffect(effectsBaked, effect)` 判断：
  - 已烘焙 + 非深度 → 跳过；
  - 已烘焙 + `depth_*` → 仍然在投影后运行（需要世界深度）；
  - 未烘焙 → 运行。

这样“层空间一次 + 深度效果一次”的语义与 `CompLayerSourceCacheTest` 的签名约定一致。

## 2. 关键帧化的效果强度没有生效

0.20.0 给每个效果加了通用参数 `global_strength`，但 `CompLayer.resolveEffect()` 只遍历
**已存在于 `effect.params` 的键**：

```java
for (Map.Entry<String, Double> entry : resolved.params.entrySet()) { ... }
```

如果用户直接给 `fx/<id>/global_strength` 打关键帧、而没有先把基础值写进 `params`，这个通道
永远不会被读取——强度关键帧看起来“打了但没用”。旧工程里的效果也没有这个键。

修复两处：

1. `CompEffect.STRENGTH = "global_strength"`；`CompEffectRegistry.create()` 给每个新效果写入
   默认值 `1.0`，所以新效果一定在 map 里。
2. `resolveEffect()` 在遍历完 map 后，扫描 `animatedPaths()` 里所有
   `fx/<effectId>/...` 通道；对不在 map 里的键补一条解析值。`global_strength` 的缺省是 1，
   未知参数缺省 0，与 `EffectRenderer` 读取缺失值的行为一致。
3. “AE 关键帧”按钮在插键前同时把当前强度写回 `effect.params`，保证第一帧之前也有正确基础值。

## 3. 预合成没有继承父 pass 的效果开关

`LayerContentRenderer.drawPrecomp()` 递归时硬编码 `renderEffects = true, renderMasks = true`。
一个深度/guide pass 明明关掉了效果，预合成又会把效果带回来（还白付一次 GPU）。

修复：`CompRenderContext` 新增 `renderEffects` / `renderMasks`，`renderCompToFbo()` 创建上下文
时写入；预合成递归读取这两个字段。

## 4. 验证

- 单测 **238/238**，0 failures / 0 errors。
- 新增 `EffectApplicationTest`：
  - 已烘焙的非深度效果不重复；
  - 深度效果在投影后仍然运行；
  - 烘焙失败时兜底只运行一次；
  - 没有基础值的 `global_strength` 关键帧能被解析。
- 版本 `0.20.3`：`bbsae-0.20.3.jar`，873998 字节，
  SHA-256 `87533104DF02D3662E5CB3CB8FECC5D93DD8D0AC7CC20408207AE2DD24026E75`。

## 5. 已知边界

- 3D 图层的**遮罩/轨道遮罩**仍然只在 2D 路径应用；把遮罩烘焙进 3D 层空间是下一轮的工作。
- 3D 图层效果在层空间 raster 上烘焙；大图层的 raster 会按 `MAX_EDGE` 缩小，像素半径类效果
  （模糊/锐化）的视觉半径随代理比例变化，这是代理分辨率的既有取舍。
