# Round 40 — 蒙版的“排除”语义与可视编辑（0.21.2）

## 1. 根因：第一个蒙版被当成了空画布

`MaskCache.rasterize()` 原来的组合逻辑：

```java
if (!any) combined[i] = a;                 // 第一个蒙版永远当作 Add
else if (SUBTRACT) combined *= 1-a;
else if (INTERSECT) combined *= a;
else if (EXCLUDE) combined = combined + a - 2*combined*a;
else combined = combined + a - combined*a;
```

第一个蒙版的 `mode` 被丢掉了。于是：

- 单个 **相减** 蒙版显示的是蒙版**内部**，而不是把内部挖掉；
- 单个 **排除** 蒙版同样显示内部，而不是外部；
- 只有第一个是 Add/Intersect 时看起来才对。

这违反了 AE / Alight Motion 的语义：蒙版栈作用在**图层完整 alpha** 上，
第一个 Subtract/Exclude 应该让图层只剩蒙版外。

### 修复

新增两个纯函数（`MaskCache.combineFirst` / `combine`）：

```text
首蒙版:
  Add / Intersect -> a
  Subtract / Exclude -> 1 - a
后续:
  Add       -> base + a - base*a
  Subtract  -> base * (1 - a)
  Intersect -> base * a
  Exclude   -> base + a - 2*base*a
```

- 第一个 Add 仍然是“定义可见区域”，不是与全图层求并集；
- 第一个 Subtract/Exclude 是“从完整图层挖掉”；
- 后续蒙版按模式叠加；
- `inverted`、`opacity`、`feather`、`expansion` 在组合前生效。

`MaskCombineTest` 用数值断言锁住这 8 种组合，避免以后再次“排除反了”。

## 2. 蒙版在 3D 图层

0.20.4 起 3D 图层的蒙版在**层空间**烘焙：

- 点坐标是图层局部坐标，`MaskCache` 用 `world = scale · translate(-rect)` 变换到栅格；
- `feather` / `expansion` 也按栅格 scale 换算，代理分辨率下视觉大小不变；
- 蒙版在效果之前乘入，所以发光/描边可以扩散到蒙版外；
- 只有蒙版的 3D 图层也会走 `renderBaked3D()`。

## 3. 画布上直接编辑

2D/相机预览新增蒙版叠加层：

- 每条蒙版用 `CompScene.project` 投影（和选框、渲染器同一条路径），
  选中蒙版为黄色，排除蒙版为红色；
- 点显示为方块；选中点显示入/出贝塞尔手柄（蓝/粉）；
- 点击点或手柄直接拖动；
- 3D 图层用**鼠标射线与图层平面求交**（`CompCamera.unproject` + 平面法线），
  因此透视下拖动也落在蒙版的平面上；
- 拖动通过 `setScalar` 写入：蒙版点已经有 `mask/.../x` 关键帧时，会在播放头写关键帧，
  而不是只改基础值导致“拖了没反应”；
- 一次拖动一个撤销点，释放时 `save()` 并刷新检查器。

蒙版模式按钮现在左键循环、右键菜单选择“相加/相减/相交/排除”，
不再是一个看不出当前状态的盲循环按钮。

## 4. 轨道遮罩（“用作蒙版/排除”）

`applyMatteToBelow()` 的两个按钮设置的是**轨道遮罩**，不是蒙版栈。旧代码只在
`is2D` 时应用：

```java
if (is2D && layer.trackMatteEnabled ...)
```

3D 目标因此完全忽略遮罩。现在：

- 3D 目标也应用轨道遮罩（屏幕空间，在投影后）；
- 2D 遮罩层叠在 3D 目标上时，会先经过 `projectPlane` 投影，避免“遮罩在相机空间、
  目标在透视空间”的错位；
- 2D 目标保持原行为。

## 5. 验证

- 单测 **250/250**，0 failures / 0 errors。
- 新增 `MaskCombineTest`：
  - 首蒙版 Add/Subtract/Intersect/Exclude 的数值；
  - 后续蒙版的并/减/交/XOR。
- 版本 `0.21.2`：`bbsae-0.21.2.jar`，891625 字节，
  SHA-256 `AEB95FB725DCFF2D7105ECFCFEB0BEF055DB91B8122DD6CCF9F63F58A28292A6`。

## 6. 已知边界

- 自由 3D 工作台（非相机视图）目前**渲染**蒙版，但叠加点/拖动只在 2D/相机视图提供；
  自由视口使用独立的 `CompViewportCamera`，需要另一套屏幕投影/命中路径。
- 轨道遮罩在 3D 目标是屏幕空间切割；真正的层空间轨道遮罩需要把遮罩层栅格变到目标层空间，
  这是后续网格/遮罩批次的工作。
