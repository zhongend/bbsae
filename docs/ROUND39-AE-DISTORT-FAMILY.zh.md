# Round 39 — AE 效果计划，第 2 批：扭曲（0.21.1）

> 继续 [EffectCraft](https://github.com/storytold/effectcraft) 的“族 + 定义”结构。上一批完成
> 模糊与锐化；这一批做**扭曲**中所有“单输入重采样”能表达的部分，并确保 3D 路径一致。

## 1. 本批新增 12 个

| AE 名称 | id | 模式 |
|---|---|---|
| 偏移 | `offset` | 平移，边缘裁切/平铺/镜像 |
| 极坐标 | `polar_coordinates` | 矩形↔极坐标，可交叉淡化 |
| 球面化 | `spherize` | 圆形区域内缩放 |
| 光学补偿 | `optics_compensation` | 桶形/枕形畸变 |
| CC 镜头 | `cc_lens` | 镜头式径向缩放 |
| CC 倾斜 | `cc_slant` | 按 Y 倾斜 |
| 放大 | `magnify` | 圆形区域放大，软边 |
| CC 波纹脉冲 | `cc_ripple_pulse` | 径向正弦波纹 |
| CC 弯曲 | `cc_bend` | 沿 Y 的正弦弯曲 |
| CC 分裂 | `cc_split` | 左右半幅分开 + 平移 |
| 滚动快门修复 | `rolling_shutter_repair` | 按 Y 的水平偏移 |
| 变换 | `transform` | 平移/缩放/旋转（2D） |

已有并继续可用：置换图、凸出、收缩、扭转、波浪变形、湍流置换、镜像。
`CC 弯曲器` 与 `CC 弯曲` 同一模式，`CC 分裂 2` 与 `CC 分裂` 同一模式，`CC 平铺` 由
`grid_repeat` 承担；这些在下一轮补别名定义。

## 2. 新 shader `AE_DISTORT`

一个程序、12 个模式，全部是**单输入重采样**（`sourceAt(uv)`）。

- `Edge`：0 裁切、1 平铺（`fract`）、2 镜像（`abs(mod(uv,2)-1)`）；
- `Offset` 以像素为单位，`Size` 做归一化；
- `Center`、`Radius` 用图层像素；`Scale`、`Angle` 用于变换；
- `Amount` 对 offset/polar/split 是**交叉淡化**，对其他模式是**形变强度**——这样“强度 0”
  与 `global_strength` 都不会产生意外位移。

### 3D 路径

所有模式都走 `CompLayerSourceCache` 的层空间烘焙：

- 效果在**投影之前**作用于图层栅格，所以形变跟着 3D 平面走，不会因为相机移动而滑移；
- `EffectPadding` 为偏移/半径/分裂预留支持域，形变不会在图层矩形处被截断；
- `scaleEffectForRaster` 把像素偏移/半径从图层像素换算成栅格像素，代理分辨率下视觉一致；
- 组合阶段不会重复应用（0.20.3 的 `effectsBaked` 规则）。

## 3. 中性值与强度

- `offset`：offset = 0 或 amount = 0；
- `polar`：amount = 0（交叉淡化到原图）；
- `spherize/optics/lens/slant/magnify/ripple/bend/rolling`：amount = 0；
- `split`：gap = 0 且 offset = 0；
- `transform`：offset = 0、scale = 1、angle = 0；
- 所有效果仍有 `global_strength`，0 = 原图层。

## 4. 验证

- 单测（含新增的中性值/支持域断言）通过；
- `ShaderCompileTest` 的隐藏 GL 上下文编译 `AE_DISTORT`；
- `EffectCoverageTest` 确认 12 个新定义都有渲染分支；
- 单测 **248/248**，0 failures / 0 errors。
- 版本 `0.21.1`：`bbsae-0.21.1.jar`，886795 字节，
  SHA-256 `F5A49A934C937ECDEF51985FA5FB1D0E7B56D88CB3933F80B9B60E5B74E48F90`。

## 5. 仍属于“网格/笔刷”而非单输入重采样的扭曲

下一批需要真正的网格/笔刷数据，不能用一个 `uv` 公式冒充：

- 贝塞尔曲线变形、网格变形、边角定位、CC 强力定位（需要 homography/网格求解）；
- 液化、涂抹、CC 涂抹、重塑（需要笔刷轨迹/遮罩）；
- CC 翻页、CC 斑点化、CC 流动运动、CC 网格化（需要额外几何/第二输入）；
- CC 弯曲器/分裂 2/平铺的别名定义。

这些会在“网格变形 + 边角定位”批次里做；先完成它们需要的网格/笔刷模型，而不是先加名字。
