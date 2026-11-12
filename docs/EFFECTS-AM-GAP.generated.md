# AM 参数对齐差异清单（自动生成）

对比对象：`_research/am/effects.json`（AM apk 提取的 277 条非 internal 定义）与 `comp/AmEffectLibrary.java`（数据驱动效果库，158 条定义）。
参数 `kind` 覆盖：本项目 `AmEffectDef` 直接表达 `spinner/slider/color`；AM 的 `point/selector/texture` 在移植时被拆成两个 float 键或数值模式键，这属于既定的模式差异，不算缺失（但在第 2 节逐条标出）。

## 0. 规模

| 指标 | 数量 |
| --- | --- |
| AM 非 internal 定义（去重后 token） | 277 |
| 本项目 AM 定义 | 158 |
| 自动匹配（同名/别名） | 124 |
| 缺失：deprecated 旧版本 | 70 |
| 缺失：非 shader（图层属性/复制器） | 20 |
| 缺失：3D 图元（本项目 MeshRenderer） | 19 |
| 缺失：同族旧版本（boxblur→boxblur3 等） | 8 |
| 缺失：可执行 shader 效果（候选） | 37 |

## 1. 已匹配（124 条）的参数差异

### `am_bend` ↔ `bendwarp`（弯曲）

- `amount`: min: 本项目 -1 / AM -180；max: 本项目 1 / AM 180；default: 本项目 0.25 / AM 0；step: 本项目 0.01 / AM 0.1

### `am_box_blur` ↔ `boxblur3`（方框模糊）

- `strength`: max: 本项目 100 / AM 2；default: 本项目 8 / AM 0.15；step: 本项目 1 / AM 0.001

### `am_cartoon` ↔ `cartoon`（卡通）

- `strength`: min: 本项目 0 / AM 1；max: 本项目 1 / AM 100；default: 本项目 1 / AM 20

### `am_circular_ripple` ↔ `circularripple2`（圆形波纹）

- `frequency`: min: 本项目 1 / AM 0；max: 本项目 200 / AM 100；default: 本项目 60 / AM 20；step: 本项目 1 / AM 0.01
- `strength`: min: 本项目 -100 / AM -1；max: 本项目 100 / AM 1；default: 本项目 12 / AM 0.025；step: 本项目 0.5 / AM 0.001
- `phase`: min: 本项目 -180 / AM -1000；max: 本项目 180 / AM 1000；step: 本项目 1 / AM 0.01
- `radius`: min: 本项目 0.05 / AM 0；max: 本项目 1.5 / AM 0.8；default: 本项目 0.9 / AM 0.3
- `feather`: min: 本项目 0 / AM 0.001；default: 本项目 0.3 / AM 0.1；step: 本项目 0.01 / AM 0.001

### `am_clouds` ↔ `clouds3`（云雾）

- `scale`: min: 本项目 0.2 / AM 0.01；max: 本项目 20 / AM 50；default: 本项目 3 / AM 1；step: 本项目 0.1 / AM 0.01
- `scale`: min: 本项目 0.2 / AM 0.1；max: 本项目 20 / AM 50；default: 本项目 3 / AM 1；step: 本项目 0.1 / AM 0.01
- `seed`: max: 本项目 9999 / AM 5；step: 本项目 1 / AM 0.01

### `am_contour_grad` ↔ `contourgrad`（等高线渐变）

- `alpha`: default: 本项目 0.9 / AM 1

### `am_contour_lines` ↔ `contourstrips`（等高线）

- `thickness`: min: 本项目 0.01 / AM 0；default: 本项目 0.18 / AM 0.005；step: 本项目 0.01 / AM 0.001
- `phase`: min: 本项目 -1 / AM -1000；max: 本项目 1 / AM 1000

### `am_curl` ↔ `curl`（卷曲）

- `radius`: min: 本项目 0.01 / AM 0；max: 本项目 1.5 / AM 50；default: 本项目 0.6 / AM 1；step: 本项目 0.01 / AM 0.001

### `am_dots` ↔ `dots2`（点阵）

- `alpha`: default: 本项目 0.9 / AM 1；step: 本项目 0.01 / AM 0.0025

### `am_edge_glow` ↔ `edgeglow`（发光边缘）

- `strength`: max: 本项目 3 / AM 2；default: 本项目 1 / AM 0.1

### `am_feather` ↔ `feather`（羽化）

- `strength`: max: 本项目 1 / AM 2；default: 本项目 1 / AM 0.15；step: 本项目 0.01 / AM 0.005

### `am_flicker` ↔ `flicker2`（闪烁）

- `seed`: max: 本项目 9999 / AM 10

### `am_flip` ↔ `flip3`（翻转）

- `angle`: min: 本项目 0 / AM -3600；max: 本项目 360 / AM 3600；step: 本项目 1 / AM 0.1
- `axis`: max: 本项目 1 / AM 3600

### `am_four_color_gradient` ↔ `fourcolorgradient`（四色渐变）

- `alpha`: default: 本项目 1 / AM 0.75

### `am_fractal_warp` ↔ `fractalwarp4`（分形扭曲）

- `scale`: min: 本项目 0.5 / AM 0.1；max: 本项目 40 / AM 4；default: 本项目 6 / AM 1；step: 本项目 0.1 / AM 0.01

### `am_gaussian_blur` ↔ `gaussianblur`（高斯模糊）

- `strength`: max: 本项目 150 / AM 2；default: 本项目 12 / AM 0.15；step: 本项目 1 / AM 0.005

### `am_glass` ↔ `glass2`（玻璃）

- `shine`: max: 本项目 4 / AM 1；default: 本项目 1.2 / AM 0.8

### `am_glow` ↔ `glow`（发光）

- `radius`: default: 本项目 12 / AM 100；step: 本项目 0.5 / AM 0.1

### `am_gradient_displace` ↔ `gradientdisplace`（渐变置换）

- `strength`: min: 本项目 0 / AM -100；max: 本项目 400 / AM 100；default: 本项目 60 / AM 0.1；step: 本项目 1 / AM 0.001

### `am_gradient_overlay` ↔ `gradientoverlay2`（渐变叠加）

- `angle`: min: 本项目 -180 / AM 0；max: 本项目 180 / AM 3600；default: 本项目 90 / AM 0；step: 本项目 1 / AM 0.5
- `scale`: min: 本项目 0.05 / AM 0.1；max: 本项目 4 / AM 10
- `alpha`: default: 本项目 1 / AM 0.75；step: 本项目 0.01 / AM 0.001

### `am_hex_array` ↔ `hexarray`（六边形阵列）

- `size`: default: 本项目 36 / AM 10

### `am_hex_shift` ↔ `hexshift`（六边形位移）

- `size`: default: 本项目 40 / AM 10
- `seed`: max: 本项目 9999 / AM 5；step: 本项目 1 / AM 0.01

### `am_hex_tile` ↔ `hextile`（六边形平铺）

- `size`: default: 本项目 40 / AM 30

### `am_highlight_shadow` ↔ `highlightshadow`（高光与阴影）

- `highlights`: min: 本项目 -1 / AM 0；max: 本项目 1 / AM 2；default: 本项目 0 / AM 1
- `shadows`: min: 本项目 -1 / AM 0；max: 本项目 1 / AM 2；default: 本项目 0 / AM 1

### `am_hue_shift` ↔ `hueshift`（色相偏移）

- `hue`: default: 本项目 30 / AM 0
- `hue`: max: 本项目 360 / AM 3600；default: 本项目 30 / AM 0

### `am_inner_blur` ↔ `innerblur`（内模糊）

- `strength`: default: 本项目 1 / AM 0.15；step: 本项目 0.01 / AM 0.005

### `am_inner_glow` ↔ `inner-glow`（内发光）

- `radius`: default: 本项目 12 / AM 50；step: 本项目 0.5 / AM 0.1

### `am_jitter` ↔ `jitter`（抖动）

- `seed`: max: 本项目 9999 / AM 10

### `am_lens_blur` ↔ `lensblur2`（镜头模糊）

- `radius`: max: 本项目 80 / AM 1；default: 本项目 12 / AM 0.15；step: 本项目 1 / AM 0.005

### `am_line_repeat` ↔ `line`（线条重复）

- `count`: min: 本项目 1 / AM 0；max: 本项目 32 / AM 150；default: 本项目 4 / AM 5

### `am_long_shadow` ↔ `longshadow2`（长阴影）

- `size`: max: 本项目 600 / AM 1；default: 本项目 60 / AM 0.15；step: 本项目 1 / AM 0.001
- `angle`: min: 本项目 -180 / AM 0；max: 本项目 180 / AM 3600；step: 本项目 1 / AM 0.5
- `alpha`: default: 本项目 0.7 / AM 0.1；step: 本项目 0.01 / AM 0.001
- `solidity`: default: 本项目 0.6 / AM 1；step: 本项目 0.01 / AM 0.001
- `fade`: default: 本项目 1 / AM 0；step: 本项目 0.01 / AM 0.001

### `am_mask_blur` ↔ `maskblur`（遮罩模糊）

- `strength`: default: 本项目 1 / AM 0.35；step: 本项目 0.01 / AM 0.005

### `am_mirror_warp` ↔ `mirror`（镜像）

- `strength`: min: 本项目 0 / AM -2；max: 本项目 1 / AM 2；default: 本项目 0.5 / AM 0
- `alpha`: step: 本项目 0.01 / AM 0.001

### `am_pinch_bulge` ↔ `pinchbulge`（收缩与膨胀）

- `strength`: default: 本项目 0.4 / AM -0.9
- `radius`: min: 本项目 0.01 / AM 0；max: 本项目 1.5 / AM 2.5；default: 本项目 0.5 / AM 0.3；step: 本项目 0.01 / AM 0.005

### `am_pixelate` ↔ `pixelate2`（像素化）

- `size`: default: 本项目 16 / AM 20
- `saturation`: min: 本项目 -1 / AM 0

### `am_polar` ↔ `polar`（极坐标）

- `angle`: min: 本项目 -180 / AM 0；max: 本项目 180 / AM 3600；step: 本项目 1 / AM 0.5
- `scale`: min: 本项目 0.05 / AM 0.01；max: 本项目 2 / AM 8；default: 本项目 0.5 / AM 1
- `repeat`: max: 本项目 8 / AM 16

### `am_pulsate` ↔ `pulsate`（脉动缩放）

- `phase`: max: 本项目 6.2832 / AM 2

### `am_pulse_opacity` ↔ `pulseopacity2`（脉动不透明度）

- `phase`: max: 本项目 6.2832 / AM 2

### `am_radial_rays` ↔ `radialrays`（径向光线）

- `alpha`: default: 本项目 0.8 / AM 1

### `am_radial_shadow` ↔ `radialshadow`（径向阴影）

- `size`: max: 本项目 300 / AM 1；default: 本项目 30 / AM 0.15；step: 本项目 1 / AM 0.001
- `alpha`: default: 本项目 0.6 / AM 0.1；step: 本项目 0.01 / AM 0.001
- `fade`: default: 本项目 0.5 / AM 0；step: 本项目 0.01 / AM 0.001

### `am_radial_wipe` ↔ `radialwipe`（径向擦除（角度））

- `progress`: max: 本项目 1 / AM 360；default: 本项目 0.5 / AM 45；step: 本项目 0.01 / AM 1

### `am_random_displace` ↔ `randomdisplace`（随机位移）

- `seed`: max: 本项目 9999 / AM 5；step: 本项目 1 / AM 0.01
- `seed`: max: 本项目 9999 / AM 10

### `am_repeat` ↔ `repeat`（重复）

- `count`: min: 本项目 1 / AM 0；max: 本项目 32 / AM 9999；default: 本项目 5 / AM 10
- `angle`: step: 本项目 0.5 / AM 0.1
- `scale`: min: 本项目 0.01 / AM 0
- `alpha`: step: 本项目 0.01 / AM 0.001

### `am_repeat_radial` ↔ `radial`（环形重复）

- `count`: min: 本项目 1 / AM 0；max: 本项目 32 / AM 150
- `orientation`: step: 本项目 0.5 / AM 1
- `sweep`: step: 本项目 0.5 / AM 1
- `angle`: step: 本项目 0.5 / AM 1
- `scale`: min: 本项目 0.01 / AM 0
- `alpha`: step: 本项目 0.01 / AM 0.001

### `am_repeat_scatter` ↔ `scatter`（散布重复）

- `count`: min: 本项目 1 / AM 0；max: 本项目 32 / AM 150；default: 本项目 12 / AM 20
- `evolution`: min: 本项目 -999 / AM 0；max: 本项目 999 / AM 2000
- `angle`: step: 本项目 0.5 / AM 1
- `scale`: min: 本项目 0.01 / AM 0
- `alpha`: step: 本项目 0.01 / AM 0.001

### `am_ridges_2` ↔ `ridges2`（山脊 2）

- `scale`: min: 本项目 0.1 / AM 0.01

### `am_rotate_tile` ↔ `tilerotate2`（旋转平铺）

- `angle`: min: 本项目 -180 / AM 0；max: 本项目 180 / AM 3600；step: 本项目 1 / AM 0.5

### `am_sat_vib` ↔ `satvib`（饱和度与自然饱和度）

- `saturation`: max: 本项目 2 / AM 1；step: 本项目 0.01 / AM 0.005

### `am_smooth_edges` ↔ `smoothedges`（平滑边缘）

- `strength`: max: 本项目 1 / AM 2；default: 本项目 1 / AM 0.02

### `am_spin_blur` ↔ `spinblur`（旋转模糊）

- `angle`: step: 本项目 0.1 / AM 0.01

### `am_starfield` ↔ `starfield`（星空）

- `speed`: min: 本项目 0 / AM 0.001；max: 本项目 20 / AM 0.5；default: 本项目 3 / AM 0.01
- `brightness`: min: 本项目 0 / AM 0.0005；max: 本项目 3 / AM 0.005；default: 本项目 1 / AM 0.0015

### `am_stripes` ↔ `stripes`（条纹）

- `width`: min: 本项目 0.01 / AM 0；max: 本项目 0.99 / AM 1
- `angle`: min: 本项目 -180 / AM 0；max: 本项目 180 / AM 3600；default: 本项目 0 / AM 45
- `phase`: min: 本项目 -1 / AM 0；max: 本项目 1 / AM 1000

### `am_swing` ↔ `swing`（摇摆）

- `phase`: max: 本项目 6.2832 / AM 2

### `am_swirl` ↔ `swirl`（旋转扭曲）

- `radius`: min: 本项目 0.01 / AM 0；max: 本项目 1.5 / AM 0.8；default: 本项目 0.5 / AM 0.3

### `am_temperature` ↔ `colortemperature`（色温）

- `temperature`: min: 本项目 -1 / AM 1；max: 本项目 1 / AM 40；default: 本项目 0 / AM 6.5
- `strength`: step: 本项目 0.01 / AM 0.001

### `am_tile` ↔ `tile`（平铺）

- `scale`: default: 本项目 2 / AM 1

### `am_turbulent_displace_3` ↔ `turbulentdisplace3`（湍流置换 3）

- `scale`: min: 本项目 0.01 / AM 0.1

### `am_warp_blur` ↔ `warpblur`（湍流模糊）

- `scale`: min: 本项目 0.01 / AM 0.1

### `am_wave_warp` ↔ `wavewarp`（波浪扭曲）

- `phase`: max: 本项目 500 / AM 1000

### `am_zoom_blur` ↔ `zoomblur2`（径向模糊）

- `strength`: min: 本项目 -1 / AM 0；default: 本项目 0.25 / AM 0.15

## 2. 已匹配但 AM 参数未暴露（参数表缺口）

只列 AM 直接类型（spinner/slider/color）且个数差 ≥ 2 的效果；`point` 参数在本项目按 `_x/_y` 两个键暴露，不计入。

| 本项目 id | AM id | AM 参数 | 同名暴露 | AM 有而本项目未同名的参数 |
| --- | --- | --- | --- | --- |
| `am_bend` | `bendwarp` | 4 | 1 | `highLimit`, `lowLimit`, `offset` |
| `am_checker_dissolve` | `checkerdissolve2` | 4 | 0 | `amount`, `fade`, `seed`, `size` |
| `am_chroma_key` | `chromakey` | 3 | 0 | `feather`, `keyColor`, `threshold` |
| `am_clouds` | `clouds3` | 7 | 2 | `color1`, `color2`, `intensity`, `octaves` |
| `am_color_balance` | `colorbalance` | 9 | 0 | `crHigh`, `crLow`, `crMid`, `mgHigh`, `mgLow`, `mgMid`, `ybHigh`, `ybLow` |
| `am_color_gain` | `coloradj` | 5 | 0 | `brightness`, `contrast`, `hue`, `saturation`, `vib` |
| `am_contour` | `contour` | 7 | 1 | `color`, `count`, `fill`, `offset`, `phase` |
| `am_contour_grad` | `contourgrad` | 6 | 1 | `color1`, `color2`, `fill`, `offset` |
| `am_contour_lines` | `contourstrips` | 10 | 2 | `amount`, `angle`, `color1`, `color2`, `lightAngle`, `lightColor`, `lineColor` |
| `am_curl` | `curl` | 5 | 1 | `circleAlpha`, `circleColor`, `offset`, `stretch` |
| `am_dots` | `dots2` | 11 | 1 | `color`, `color2`, `density`, `feather`, `fill`, `phase`, `radius`, `scale` |
| `am_edge_glow` | `edgeglow` | 10 | 1 | `blend`, `brightness`, `fillColor`, `gamma`, `smoothing`, `spread`, `threshold`, `tint` |
| `am_electric_edges` | `electricedges` | 22 | 10 | `color1`, `color2`, `contourAngle`, `contourCount`, `contourOffset`, `distOffs`, `glowAlpha`, `maskColor` |
| `am_energy_beam` | `energybeam` | 18 | 0 | `alpha`, `blur`, `clip`, `color1`, `color2`, `color3`, `evolution`, `freq` |
| `am_fade` | `fade` | 2 | 0 | `inTime`, `outTime` |
| `am_fill_behind` | `fillbehind` | 15 | 0 | `alpha`, `bgScale`, `brightness`, `edgeColor`, `edgeStrength`, `feather`, `fgAlpha`, `fgScale` |
| `am_flicker` | `flicker2` | 3 | 1 | `freq`, `mag` |
| `am_flip` | `flip3` | 12 | 2 | `ambient`, `crop`, `lightColor`, `lightDepth`, `lightIntensity`, `shine`, `solidColor`, `spec` |
| `am_four_color_gradient` | `fourcolorgradient` | 6 | 1 | `blend`, `color1`, `color2`, `color3`, `color4` |
| `am_fractal_warp` | `fractalwarp4` | 4 | 1 | `intensity`, `mag`, `octaves` |
| `am_glass` | `glass2` | 10 | 1 | `angle`, `color_d`, `color_s`, `elevation`, `fill`, `intensity`, `radius`, `refract` |
| `am_glow` | `glow` | 5 | 1 | `alpha`, `color`, `hardness`, `quality` |
| `am_glow_scan` | `glowscan` | 8 | 0 | `alpha`, `blend`, `fill`, `fillColor`, `power`, `radius`, `strength`, `threshold` |
| `am_gradient_map` | `gradientmap` | 2 | 0 | `color1`, `color2` |
| `am_gradient_overlay` | `gradientoverlay2` | 5 | 3 | `color1`, `color2` |
| `am_grid_lines` | `grid2` | 4 | 0 | `color`, `smoothing`, `spacing` |
| `am_grid_repeat` | `grid` | 15 | 0 | `alpha`, `angle`, `blend`, `count`, `easeIn`, `easeOut`, `end`, `fillColor` |
| `am_halftone_dots` | `halftonedots` | 6 | 0 | `amount`, `angle`, `color1`, `color2`, `phase`, `strength` |
| `am_halftone_lines` | `halftonelines` | 6 | 0 | `amount`, `angle`, `color1`, `color2`, `phase`, `strength` |
| `am_hex_array` | `hexarray` | 10 | 1 | `alpha`, `angle`, `color1`, `color2`, `color3`, `feather`, `lineWidth`, `stretch` |
| `am_hex_shift` | `hexshift` | 4 | 2 | `angle`, `strength` |
| `am_hex_tile` | `hextile` | 11 | 1 | `angle`, `color1`, `color2`, `color3`, `feather`, `lineWidth`, `stretch`, `test` |
| `am_inner_glow` | `inner-glow` | 5 | 1 | `alpha`, `color`, `hardness`, `quality` |
| `am_jitter` | `jitter` | 6 | 1 | `angle`, `freq`, `mag`, `slack`, `zjitter` |
| `am_lens_blur` | `lensblur2` | 4 | 1 | `cx`, `cy`, `strength` |
| `am_lens_flare` | `lensflare` | 6 | 0 | `alpha`, `brightness`, `color`, `pointCount`, `radius`, `shine` |
| `am_line_repeat` | `line` | 14 | 1 | `alpha`, `angle`, `blend`, `easeIn`, `easeOut`, `end`, `fillColor`, `overlap` |
| `am_long_shadow` | `longshadow2` | 7 | 5 | `color`, `fill` |
| `am_luma_key` | `lumakey3` | 3 | 0 | `feather`, `highThreshold`, `lowThreshold` |
| `am_matte_fringe` | `mattefringe` | 8 | 0 | `excludeColor1`, `excludeColor2`, `excludeColor3`, `feather`, `thickness`, `threshold1`, `threshold2`, `threshold3` |
| `am_mirror_warp` | `mirror` | 4 | 2 | `offset`, `radius` |
| `am_omino_glass` | `ominoglass` | 17 | 8 | `featureSize`, `lightAmbient`, `lightColor`, `lightDepth`, `lightIntensity`, `lightShine`, `lightSpec`, `roughnessScale` |
| `am_palette_map` | `palettemap` | 9 | 0 | `alpha`, `color1`, `color2`, `color3`, `color4`, `color5`, `color6`, `color7` |
| `am_pixelate` | `pixelate2` | 5 | 3 | `angle`, `threshold` |
| `am_polar` | `polar` | 7 | 3 | `crop`, `feather`, `radius` |
| `am_pulsate` | `pulsate` | 4 | 1 | `freq`, `maxsize`, `minsize` |
| `am_pulse_opacity` | `pulseopacity2` | 3 | 1 | `freq`, `strength` |
| `am_radial_rays` | `radialrays` | 12 | 1 | `angle`, `bendFrequency`, `bendPhase`, `bendStrength`, `color`, `feather`, `innerRadius`, `outerRadius` |
| `am_radial_shadow` | `radialshadow` | 9 | 3 | `color`, `fill`, `lightColor`, `lightIntensity`, `lightSize`, `solidity` |
| `am_radial_wipe` | `radialwipe` | 6 | 1 | `end`, `feather`, `offset`, `repeatCount`, `start` |
| `am_random_displace` | `randomdisplace` | 7 | 1 | `evolution`, `freq`, `mag`, `scatter` |
| `am_repeat_radial` | `radial` | 19 | 7 | `baseScale`, `blend`, `easeIn`, `easeOut`, `end`, `fillColor`, `overlap`, `phase` |
| `am_repeat_scatter` | `scatter` | 17 | 6 | `blend`, `easeIn`, `easeOut`, `end`, `fillColor`, `overlap`, `phase`, `scatterSeed` |
| `am_replace_color` | `replacecolor` | 5 | 0 | `alpha`, `feather`, `newcolor`, `oldcolor`, `threshold` |
| `am_rgb_gamma` | `rgb-gamma` | 3 | 0 | `blueGamma`, `greenGamma`, `redGamma` |
| `am_rgb_mixer` | `rgb-mixer` | 5 | 2 | `bmix`, `gmix`, `rmix` |
| `am_ridges_2` | `ridges2` | 8 | 5 | `color1`, `color2` |
| `am_rotate_tile` | `tilerotate2` | 6 | 1 | `amount`, `offset`, `rotation`, `seed`, `strength` |
| `am_sat_vib` | `satvib` | 5 | 1 | `brightness`, `contrast`, `hue`, `vib` |
| `am_scale_assist` | `scaleassist` | 2 | 0 | `damp`, `scale` |
| `am_shape_field` | `shapefield` | 15 | 7 | `angleVariance`, `color1`, `color2`, `color3`, `layerCount`, `shapeAngle`, `shapeSize`, `sizeVariance` |
| `am_simplestars_2` | `simplestars2` | 7 | 3 | `color`, `starCount`, `starFeather`, `starSize` |
| `am_smooth_bevel` | `smoothbevel` | 10 | 8 | `color_d`, `color_s` |
| `am_soft_glow` | `softglow` | 8 | 6 | `color`, `outsideAlpha` |
| `am_solid_matte` | `solidmatte` | 2 | 0 | `alpha`, `color` |
| `am_spectral_map` | `spectralmap` | 8 | 5 | `blueColor`, `greenColor`, `redColor` |
| `am_spin_blur` | `spinblur` | 4 | 1 | `cx`, `cy`, `radius` |
| `am_spot_color` | `spotcolor` | 4 | 0 | `color`, `feather`, `saturation`, `threshold` |
| `am_squeeze` | `squeeze` | 2 | 0 | `radius`, `strength` |
| `am_starfield` | `starfield` | 8 | 2 | `blue`, `darkmatter`, `green`, `strength`, `userColor`, `zoom` |
| `am_stripes` | `stripes` | 6 | 3 | `amount`, `color`, `smoothing` |
| `am_stroke_taper` | `stroketaper` | 3 | 0 | `end`, `squeeze`, `start` |
| `am_swing` | `swing` | 4 | 1 | `a1`, `a2`, `freq` |
| `am_tile` | `tile` | 3 | 1 | `angle`, `phase` |
| `am_volumetric_clouds` | `volumetricclouds` | 6 | 3 | `densityParam`, `lightColor`, `shadowColor` |
| `am_voronoi_2` | `voronoi2` | 8 | 4 | `color`, `color2`, `lineColor`, `lineWidth` |
| `am_vortex_blur` | `vortexblur` | 2 | 0 | `radius`, `strength` |
| `am_wave_warp` | `wavewarp` | 5 | 1 | `xamp`, `xspace`, `yamp`, `yspace` |
| `am_zoom_blur` | `zoomblur2` | 4 | 1 | `cx`, `cy`, `radius` |

## 3. 缺失效果分类

### 3.1 deprecated 旧版本（不补）（70 条）

`outlinebad`、`outlinebasic`、`shadow`、`test`、`test2`、`test3`、`testblur`、`cube`、`flip`、`flip2`、`heart`、`spherize`、`triprism`、`boxblur`、`boxblur2`、`fastboxblur`、`lensblur`、`motionblur`、`motionblur3`、`zoomblur`、`brightcont`、`bumpmap`、`colorhot2`、`colortint`、`colortune`、`gradientoverlay`、`layernormtest`、`longshadow`、`circularripple`、`displacemap`、`displacemap2`、`fractalwarp`、`fractalwarp2`、`fractalwarp3`、`glass`、`pixelate`、`swirl2`、`swirl3`、`tilerotate`、`tileshift`、`turbulentdisplace`、`turbulentdisplace2`、`differencematte`、`lumakey`、`lumakey2`、`wipe`、`blink`、`checkerdissolve`、`flicker`、`pulseopacity`、`checker`、`clouds`、`clouds2`、`dots`、`irridescence`、`noise`、`noise2`、`ridges`、`simplestars`、`starfield2`、`turbulence`、`voronoi`、`facemotion`、`movealongpath`、`movealongpath2`、`oscillate`、`oscillate2`、`perlindisplace`、`shake`、`stretch`

### 3.2 同族旧版本（已有新版本，不补）（8 条）

`motionblur4`、`spinblur2`、`zoomblur3`、`radialshadow2`、`spotcolor2`、`wavewarp2`、`blocknoise2`、`starfield3`

### 3.3 非 shader 效果（AM 用图层属性/复制器实现）（20 条）

`parenthelper`、`timequant`、`progress`、`strokecolor`、`echokf`、`path`、`counter`、`textprogress`、`textrand`、`textspacing`、`texttransform`、`timecode`、`growparts`、`morphpath`、`movealongpath3`、`oscillate3`、`pulsate2`、`shake2`、`shakeparts`、`swing2`

### 3.4 3D 图元（本项目由 `s3d_*` + MeshRenderer 实现）（19 条）

`box`、`cube2`、`cylinder`、`ellipsoid`、`extrude`、`heart2`、`hexprism`、`hollowbox`、`octahedron`、`pagecurl`、`plus`、`pyramid`、`ring`、`spherize2`、`starpoly`、`starprism`、`torus`、`triprism2`、`tunnel`

### 3.5 可执行 shader 效果：真正缺失（候选批次）（37 条）

| AM id | 类别 | 参数数 | 备注 |
| --- | --- | --- | --- |
| `chromaticvortexblur` | blur | 7 | 高优先级 |
| `mosaic` | blur | 4 | 高优先级 |
| `dblur` | blur | 3 | 高优先级 |
| `sharpen` | blur | 3 | 高优先级 |
| `bumpmap2` | color | 24 | 高优先级 |
| `rays` | color | 10 | 高优先级 |
| `exposure` | color | 5 | 高优先级 |
| `threshold` | color | 4 | 高优先级 |
| `brightcont2` | color | 3 | 高优先级 |
| `posterize` | color | 3 | 高优先级 |
| `invert` | color | 1 | 高优先级 |
| `displacemaprad` | distort | 8 | 高优先级 |
| `vrcombined` | distort | 4 | 高优先级 |
| `vrplayer` | distort | 4 | 高优先级 |
| `360-reorient-sphere` | distort | 1 | 高优先级 |
| `dissolve` | opacity | 3 |  |
| `transform` | transform | 7 |  |
| `offset` | transform | 4 |  |
| `stretch2` | transform | 3 |  |
| `chromakey-adv` | matte | 21 |  |
| `vignette` | matte | 7 |  |
| `lumastamper` | matte | 5 |  |
| `spill-cleanup` | matte | 5 |  |
| `wipe2` | matte | 5 |  |
| `mattechoke` | matte | 4 |  |
| `halftonedotscmyk` | drawing | 6 |  |
| `findedges` | drawing | 3 |  |
| `grunge` | procedural | 135 |  |
| `lightning` | procedural | 16 |  |
| `ominodiff` | procedural | 15 |  |
| `turbulence2` | procedural | 15 |  |
| `ribbon` | procedural | 11 |  |
| `star` | procedural | 9 |  |
| `checker2` | procedural | 7 |  |
| `irridescence2` | procedural | 7 |  |
| `magnifybg` | other | 4 |  |
| `lift` | - | 3 |  |

## 4. 本项目有、AM 中未找到同名定义（34 条）

这些条目多为本项目自研/EffectCraft 参考实现，或历史别名尚未登记：

`am_bevel`、`am_block_dissolve`、`am_bokeh`、`am_border`、`am_chromatic_aberration`、`am_chromatic_blur`、`am_color_bands`、`am_color_separate`、`am_desaturate`、`am_duotone`、`am_edge_light`、`am_gloss`、`am_gradient_mask`、`am_halftone_cmyk`、`am_heat_map`、`am_inner_shadow`、`am_light_leak`、`am_light_wrap`、`am_liquify`、`am_monochrome`、`am_oil_paint`、`am_pixel_dissolve`、`am_radial_gradient`、`am_reflection`、`am_ripple_pulse`、`am_sepia`、`am_sketch`、`am_skew`、`am_slice_glitch`、`am_speed_lines`、`am_split_tone`、`am_star_shape`、`am_stretch_axis`、`am_tilt_shift`
