# Round 41 — 投影与形变的 alpha 关联（0.21.3）

这一轮不是加功能，而是修两个“像素看起来变黑/边缘发脏”的 alpha 关联问题。

## 1. `projectPlane` 输出是预乘，却被当成直通

`projectPlane()` 把一张**直通 alpha** 的图层图，用 `batcher.texturedBox` 画进一个透明目标。
`SRC_ALPHA` 混合的结果是：

```text
target.rgb = source.rgb * source.a
target.a   = source.a
```

也就是**预乘 alpha**。但函数直接 `return target`，后续：

- 合成器把 `content` 当直通 alpha 混合 → 半透明区域变暗；
- 轨道遮罩的亮度通道读 `matte.rgb` → 读到被 alpha 乘过的颜色，亮度遮罩偏暗；
- 2D 图层在倾斜相机下尤其明显（投影后的边缘发黑）。

`renderBaked3D()` 早就做了 `PassRenderer.unpremultiply(target)`，`projectPlane()` 漏了。
修复：投影完成后统一 `unpremultiply` 一次，再返回。

影响范围：

- 2D 图层在空间相机下的 `projectPlane`；
- 2D 遮罩层投影到 3D 目标（轨道遮罩）；
- 任何调用 `projectPlane` 的路径。

## 2. `AE_DISTORT` 在直通 alpha 上重采样

几何形变是重采样。直通 alpha 的边缘像素和透明像素做双线性插值，会把透明区域的
黑色 RGB 混进边缘，产生黑边/彩边。

修复：`aeDistort` 与 `aeBlur` 一样，在进入 shader 前 `premultiply`，输出后
`unpremultiply`；shader 本身不需要改。

## 3. 验证

- 单测 **250/250**，0 failures / 0 errors。
- 版本 `0.21.3`：`bbsae-0.21.3.jar`，891689 字节，
  SHA-256 `01BAE733BE7728619EA6676C76B63F0492EDCBD179D5FCE8CF338F29FA4022D2`。
