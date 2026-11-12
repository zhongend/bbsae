# AM 效果参数对齐差异清单（人工结论）

> 数据来源：`_research/am/effects.json`（历史从 AM apk 提取，277 条非 internal 定义，含参数
> `kind/id/default/min/max/step/ticks/style`）与 `_research/am/assets/effects/*.xml`（真实 shader）。
> 本项目侧：`src/main/java/dev/bbsae/comp/AmEffectLibrary.java`（122 条数据驱动定义）。
>
> 自动生成的逐条清单见 [`EFFECTS-AM-GAP.generated.md`](EFFECTS-AM-GAP.generated.md)，由
> `_tools/am-gap/compare.py` 生成（只读分析，不改代码）：
>
> ```powershell
> python _tools\am-gap\compare.py --write     # 重新生成 generated 文件
> python _tools\am-gap\compare.py             # 直接打印（UTF-8 控制台）
> ```

## 1. 规模

| 指标 | 数量 |
| --- | --- |
| AM 非 internal 定义（去重 token） | 277 |
| 其中有可执行 shader | 180 |
| 本项目 AM 定义 | 158（0.93 +5、0.94 +13、0.95 +6、0.96 +12） |
| 自动匹配（同名/历史别名） | 124 |
| 未匹配：deprecated 旧版本 | 70 |
| 未匹配：非 shader（图层属性 / 复制器） | 20 |
| 未匹配：3D 图元（本项目 `s3d_*` + MeshRenderer） | 19 |
| 未匹配：同族旧版本（boxblur→boxblur3 等） | 8 |
| 未匹配：可执行 shader、真正缺失 | 37 |

自动匹配覆盖了大部分"效果语义"，没有覆盖的 66 条多数是成员限定/冷门 procedural（grunge、ominodiff、
voronoi、turbulence、ribbons 等）与 drawing 家族（electricedges、roughenedges、smoothbevel）。

## 2. 差异分五类（不是所有差异都该"修"）

### A. 参数类型/选项缺失（真正的 schema 缺口）

本项目 `AmEffectDef` 只有 `float`（含 slider/spinner）与 `color`；AM 还有 `point`（2 维）、`selector`
（radio/dropdown 菜单）与 `texture`（额外输入纹理）。

| AM 类型 | 本项目现状 | 影响 | 优先级 |
| --- | --- | --- | --- |
| `point` | 拆成 `_x`/`_y` 两个 float（position/center/offset 均如此） | 功能等价，只是 UI 是两个滑杆 | 低 |
| `selector` | 没有；要么固定一种行为，要么用数值 spinner 选模式 | 用户看不到 AM 的选项名（blendMode/type/edges/fadeType/colorMode/gradType 等） | **中**（调色/模糊家族） |
| `texture` | 不支持第二输入纹理 | 依赖多纹理的效果（displacemap/glass/bumpmap）用本项目自带的背景/自输入近似 | 低（语义已对齐为 `Comp`） |

selector 具体缺口（影响最大的）：
`blendMode`（glow/inner-glow/edgeglow/hextile/gradientoverlay/mirror）、`type`（mirror/flip）、
`colorMode`（hextile）、`edges`（hextile）、`fadeType`（longshadow）、`gradType`（gradientoverlay）、
`oppositeType/originType`（bendwarp）。这些在本项目里多为固定行为；需要 UI 菜单控件支持，属
`client/ui/` 与 `AmEffectDef` 的联合改动，本轮不做。

### B. 零点契约导致的语义转换（刻意，不修）

本项目有一条硬性契约（`EffectNeutralTest` / `EffectRenderer.isNeutral`）：**参数拖回恒等值必须逐像素还原**，
因此凡 AM 用"1 / 0.5 / 某默认值"表示中性的参数，本项目都换算成了"0 = 中性"：

| 效果 | AM 中性值 | 本项目 | 原因 |
| --- | --- | --- | --- |
| `am_highlight_shadow` highlights/shadows | 1（0..2） | 0（-1..1） | 0 = 原图，可打关键帧平滑过渡 |
| `am_cartoon` strength | 20（1..100） | 0（0..1） | 同上（强度替代绝对分级数） |
| `am_bend` amount | 0°（-180..180） | 0（-1..1，归一化） | 0 = 原图；角度单位在 shader 内换算 |
| `am_mirror_warp` strength | 0（-2..2） | 0（0..1） | 镜像中心由 center 控制，强度归一化 |
| `am_pinch_bulge` strength | -0.9 | 0（0..2？见参数表） | 0 = 原图 |
| `am_temperature` temperature | 6.5（1..40，kelvin 刻度） | 0（-1..1 冷暖） | 0 = 原图；见 C 节单位说明 |

结论：这些差异**不能**按 AM 数值照搬，否则"拉到 0 恢复原样"的验收会直接破。generated 文件第 1 节
列出的 min/max/default 差异，多数属于这一类或 C 类，阅读时需要对照本表。

### C. 单位转换（像素 vs 归一化）

| 参数族 | AM | 本项目 | 说明 |
| --- | --- | --- | --- |
| 纯模糊半径（gaussian/box/lens/zoom） | strength 0..2，归一化（内部乘以纹理尺寸） | 半径像素（0..150 等） | 本项目按"合成像素"标定，代理分辨率下一致；见 `EffectPadding`/`scaleEffectForRaster` |
| `glow/inner-glow/edgeglow` radius | 0..400（像素） | 0..400（本轮对齐 max；默认 12 保持可预览） | 单位一致 |
| `tile` scale | 0.005..20（1 = 原样） | 0.005..20（默认 2 保持"加效果可见"） | min/max/step 已对齐，默认值为可预览性保留 |
| `starfield` speed/brightness | 0.001..0.5 / 0.0005..0.005 | 0..20 / 0..3 | AM 是动画速率/亮度增益的归一化量；本项目按"每秒闪烁次数/亮度倍数"标定，语义更直观 |
| `pixelate` size | 1..500（像素） | 1..500（本轮对齐 max） | 单位一致 |
| `hue`（hueshift） | 0..360 / 0.5° | 0..360 / 0.5°（本轮对齐；默认 30 保持可见） | 单位一致 |
| 角度类（angle/rotation） | 0..3600/0.5°，带 ticks | 多为 -180..180/1° | 本项目 UI 无 angle 类型；数值仍可用整圈角度 |
| `colortemperature` | kelvin（6.5=中性） | 归一化冷暖（0=中性） | 见 B |

### D. 已匹配但 AM 参数没有同名暴露

generated 文件第 2 节按"AM 直接参数 vs 本项目同名键"列出。缺口最大的几组：

- `am_color_balance`：AM 是 9 条 `cr/mg/yb × low/mid/high` 滑杆；本项目用 lift/gamma/gain 三个色轮 +
  曲线，属于**另一种等价实现**，不建议混合两套。
- `am_contour / am_contour_grad / am_contour_lines / am_dots / am_edge_glow / am_energy_beam`：
  AM 各 8–22 个参数（颜色/线宽/相位/光角度…），本项目保留了视觉主参数（阈值/宽度/颜色/相位），
  细项未移植。
- `am_fill_behind / am_chroma_key-adv`：AM 的 matte 高级控制（前景 alpha/tint/gamma 等）未移植。

### E. 真正缺失的效果（候选批次）

按用户优先级（模糊/调色/变形/重复/文字）筛选 generated 3.5 的 66 条：

| 优先级 | AM id | 类别 | 参数数 | 备注 |
| --- | --- | --- | --- | --- |
| **P1（变形）✅ 0.95** | `pinchbulgeinside` / `ominoglass` / `wavewarp2` / `turbulentdisplace3` / `tileshift2` / `stretchsegment` | distort | 4–22 | 已补 6 个数据驱动 body（见 3.4） |
| **文字语义判定** | `texttransform` / `textspacing` / `textprogress` / `textrand` / `timecode` / `counter` | text | 0–16 | **判定：不做**。AM 里这些是非 shader 的"文字图层属性/逐字符动画预设"，不是像素效果；本项目已用文字内容模式（timecode/counter/random/reveal）与文字动画器实现同等能力，硬塞进效果库只会得到重复入口且需要 UI 写域。详见第 3.5 节。 |
| **P0（重复）✅ 0.93** | `repeat` / `radial` / `scatter` | repeat | 6/21/19 | 非 shader；已用 REPLICATE 机制补齐为 `am_repeat` / `am_repeat_radial` / `am_repeat_scatter`（见第 3 节） |
| **P0（重复）** | `path` | repeat | 19 | **判定：不做**。AM 的 repeat.path 是"沿图层自身路径复制"，需要路径几何 + UI 上给效果指定路径的入口；本项目效果参数域只有 float/color，UI 没有路径选择器，用"第一个 PATH 形状/蒙版"当路径是隐式约定（对 99% 图层静默无效、还会和蒙版语义冲突）。详见第 3.5 节。 |
| P0（重复） | `shapefield` ✅ 0.96 | repeat | 18 | 已补 `am_shape_field`（合成空间形状场，可越过图层矩形；见 3.6） |
| P1（模糊） | `unsharpmask` ✅ / `lensblur2` ✅ / `spinblur2` ✅ / `streakstrips` ✅ / `warpblur` ✅ / `chromaticzoomblur` ✅ | blur | 3/5/5/5/11/2 | 0.94 已补（数据驱动 body，单 pass tap 循环近似参考实现的 buffer 模糊输入） |
| P1（调色） | `rgb-mixer` ✅ / `solidcolor` ✅ / `spectralmap` ✅ / `softglow` ✅ / `darkglow` ✅ / `lightglow` ✅ / `colorhot` ✅ / `rgbsep` ✅ | color | 6/4/8/8/6/8/2/4 | 0.93–0.96 已补；`rgbsep`=0.96 的 `am_rgb_sep` |
| P1（变形） | `pinchbulge2` ✅ / `swirl4` ✅ / `hexrotate` ✅ | distort | 4/4/10 | 0.94 已补；pinchbulge2 复刻参考公式（收敛/膨胀两段），hexrotate 用六边形网格逐格旋转近似 |
| P2（drawing）| `electricedges` ✅ / `roughenedges` ✅ / `smoothbevel` ✅（0.96）| drawing | 22/12/10 | 已补 3 个；参考依赖 blur/mask buffer，这里用自采样环近似（见 3.6） |
| P2（procedural）| `voronoi2` ✅ / `ridges2` ✅ / `noise3` ✅ / `blocknoise2` ✅ / `simplestars2` ✅ / `volumetricclouds` ✅ / `halftonedotscmyk` ✅（0.96）| procedural/drawing | 4–12 | 已补 7 个（见 3.6） |
| — | `grunge`(135 参数) / `ominodiff`(15) / `lightning`(16) / `ribbon`(11) / `turbulence2`(15) / `bumpmap2`(24) / `chromakey-adv`(21) | procedural/matte/color | 11–135 | **不做**：单帧可做但要么参数表极大（grunge 135 个滑杆，UI 爆炸）要么依赖真实多 pass 光照/抠像流程，性价比低于收益；已在剩余缺口里注明 |
| P2 | `findedges` / `invert` / `posterize` / `threshold` / `dissolve` / `transform` / `offset` / `mosaic` / `sharpen` / `brightcont2` 等 | 多类 | 1–7 | 本项目已有等价经典条目（不同参数命名），不重复入库 |

## 3. 本轮已修复（参数/语义）

### 3.0 0.94 跨效果正确性修复（不属于某个新效果，但影响全部 P1/重复家族）

- **REPLICATE 直通 alpha 合成**：复制 pass 之前把直通 alpha 的源像素按预乘公式累加又按直通输出，
  半透明复制体（α=128 的红）会变成 (128,0,0,128)、opacity 0.5 时复制体发黑、文字抗锯齿边缘重复后
  发黑。现在累加在预乘空间、结尾统一 un-premultiply 成直通输出；`EffectRepeatTest` 新增两个像素测试
  （半透明单拷贝保持颜色/α、opacity 0.5 只降 α 不变色）。
- **预览 proxy 的像素单位**：三类"以合成像素为参数、却按 FBO 像素换算"的路径修正——
  ① RGB 分离的 `Texel` 改用合成 texel（之前面板 960 px 时 14 px 分离看起来 28 px）；
  ② 投影/内阴影的偏移经 `PassRenderer.draw(..., offsetReferenceWidth/Height)` 按合成尺寸归一化；
  ③ 图层样式 `inner()` 的距离/模糊半径经 `EffectRenderer.proxyScale()` 从合成像素换算到目标像素。
  `PreviewProxyTest.pixelAuthoredParametersScaleWithTheProxyTarget` + `EffectPipelineTest.
  proxyTargetKeepsPixelAuthoredEffectDistances`（1:1 vs 0.5 两档渲染同一 RGB 分离图，采样位置一致）。

### 3.1 0.93（AM P0 repeat 家族补齐）

- 新增三个数据驱动定义（`comp/AmEffectLibrary.java`），全部走 `AmEffectDef`/`CompEffectRegistry`
  机制，效果库/参数面板/关键帧通道自动获得：
  | id | 名称 | AM 对应 | 参数 | 零点契约 |
  | --- | --- | --- | --- | --- |
  | `am_repeat` | 重复 | `com.alightcreative.effects.repeat` | count, offset_x/y（像素）, angle（每复制体递增）, scale（每复制体递增）, alpha | count=1 + offset/angle=0 + scale=1 + alpha=1 → 输入原样 |
  | `am_repeat_radial` | 环形重复 | `...effects.repeat.radial` | count, radius, orientation, start_angle, sweep, base_scale, offset_x/y, angle, scale, alpha | count=1 + radius=0 + 旋转/缩放/偏移中性 + alpha=1 |
  | `am_repeat_scatter` | 散布重复 | `...effects.repeat.scatter` | count, radius, evolution, scatter_seed, offset_x/y, angle, scale, alpha | count=1 + radius=0 + 其它中性 |
- 渲染由 `EffectRenderer` 的合成空间 REPLICATE pass 承载（`ShaderSources.REPLICATE` 新增
  Mode 3/4/5：递增复制、环形复制、圆盘内随机散布）；3D 图层经 `isCompositionSpace` 在投影后执行，
  2D 直接在合成尺寸内容上执行。每个复制体按 `angle/scale` 逐份递增，`alpha` 为复制体不透明度。
- 与 AM 的已知差异：`repeat.time`（按时间偏移复制体内容）未实现——单帧全屏 pass 只能采样同一帧，
  需要多帧 feed 才能对齐；已在参数表省略而不是放一个无效滑杆。
- 测试：`EffectRepeatTest` 新增 5 个像素/契约测试（递增复制位置、环形四方位、散布越界、
  家族零点返回输入），`AmEffectLibraryTest` 新增 3 个（零点判据 / 目录与渲染器注册 / P1 调色零点）。
  缩略图重新生成（3 张）。

### 3.2 0.94（AM P1 模糊/调色/变形批：13 个）

全部数据驱动（`AmEffectDef`/`CompEffectRegistry`），无新增 shader 家族；每个效果都有显式恒等分支 +
零点契约，`EffectP1BatchTest` 逐条做"默认可见 + 中性逐像素恒等（渲染器短路与强制 shader 两条路径）"。

| 分类 | id | 名称 | 零点契约 | 与 AM 的差异 |
| --- | --- | --- | --- | --- |
| 模糊 | `am_unsharp_mask` | USM 锐化 | amount=0 | 原版模糊来自 buffer 纹理；这里 9 tap 自采样 |
| 模糊 | `am_lens_blur_2` | 镜头模糊 2（对焦） | strength=0 | 按对焦半径外的距离加权；参考的 buffer 模糊输入未复刻（旧 `am_lens_blur` 是散景盘模糊，对应 AM deprecated `lensblur`） |
| 模糊 | `am_streak_strips` | 条纹拖影 | alpha=0 | 参考的条纹相位/条带噪声简化为 sin 调制 |
| 模糊 | `am_warp_blur` | 湍流模糊 | intensity=0 | 参考的 4 向 selector 保留为数值参数（0 水平/1 垂直/2 径向/3 切向）；parr 点参数与 octaves 未暴露 |
| 模糊 | `am_chromatic_zoom_blur` | 彩色径向模糊 | strength=0 | 参考的 7 段色度权重简化为红/蓝两端 |
| 模糊 | `am_spin_blur`（既有，参数对齐） | 旋转模糊 | angle=0 | AM `spinblur2` 与本项目既有条目同为"绕中心旋转模糊"；本轮把 angle 从 -180..180 对齐为 AM 的 0..60°/0.1° 步进并补显式恒等分支，中心用既有 center_x/y |
| 调色 | `am_spectral_map` | 光谱映射 | alpha=0 | 复刻 mapChannel 三角波映射；reverse/scaleBrightness 等未暴露参数未做 |
| 调色 | `am_soft_glow` | 柔光 | strength=0 | 12 tap 环采样近似 blurBuf；outsideAlpha（需要下方合成）未做 |
| 调色 | `am_dark_glow` | 暗部发光 | strength=0 | 同上 |
| 调色 | `am_light_glow` | 亮部发光 | strength=0 | 同上 |
| 调色 | `am_color_hot` | 热色 | intensity=0 | AM 定义无参数；本项目加了 intensity/gamma/暖色三个可控参数（否则无法满足零点契约与可调性） |
| 变形 | `am_pinch_bulge_2` | 收缩与膨胀 2 | strength=0（另 radius=0 也是恒等） | 复刻参考公式（aspect、pinch/bulge 两段） |
| 变形 | `am_swirl_4` | 旋转扭曲 2 | strength=0 | 参考的径向 falloff 保留 |
| 变形 | `am_hex_rotate` | 六边形旋转 | 六项旋转/位置全 0 | 六边形网格逐格旋转 + warp/twirl 近似；参考的 stretch 点参数拆成 stretch_x/y |

测试：`EffectP1BatchTest`（隐藏 GL，3 个测试，逐条覆盖 13 个新效果 + 对齐后的 `am_spin_blur`）、
`AmEffectLibraryTest.p1BatchDeclaresItsZeroValues`；`ShaderCompileTest` 自动编译新 body；
效果缩略图生成器已修退化检查（本轮新缩略图 13 张 + `am_spin_blur` 更新 1 张）。

### 3.4 0.95（AM 剩余变形族：6 个）

| id | 名称 | 零点契约 | 与 AM 的差异 |
| --- | --- | --- | --- |
| `am_pinch_bulge_inside` | 内部收缩与膨胀 | strength=0 | 复刻参考的 inside 阻尼公式（逐像素从图层边缘羽化）；参考的 centerPoint 拆成 center_x/y |
| `am_wave_warp` | 波浪扭曲 | m2=0（波幅） | 按参考 shader 的方向/波长/双阻尼实现；AM 的 screenSpace 选项未暴露（本项目效果始终按图层空间，与其它变形一致） |
| `am_turbulent_displace_3` | 湍流置换 3 | intensity=0 | 参考的 4 方向 selector 保留为数值参数；position 点参数拆 position_x/y；octaves 控制 fbm 层数 |
| `am_tileshift_2` | 平铺错位 2 | strength=0 | 复刻随机格位移 + 采样；参考的 mirror 采样简化为按 alpha 乘回（视觉差异小） |
| `am_stretch_segment` | 拉伸段 | stretch=0 | 复刻参考的 sminCubic 平滑采样公式 |
| `am_omino_glass` | Omino 玻璃 | strength=0 | 参考版本折射**下方合成**（Comp+buffer 纹理）；本项目对图层自身像素做同样的噪声折射/色差/光照（不带额外输入纹理），因此 2D/3D 烘焙/导出行为一致。shading 下拉选择器以数值 0–3 暴露 |

测试：`EffectP1BatchTest` 的 `DISTORT_BATCH` 复用同一套"默认可见 + 中性双路径恒等"断言；
`AmEffectLibraryTest.p1BatchDeclaresItsZeroValues` 增加 6 条；缩略图 6 张入库。

### 3.4b 0.96（AM 剩余可执行 shader 批：12 个）

全部数据驱动（无新增 shader 家族），每个都有显式恒等分支 + 零点契约；`EffectP1BatchTest` 的
`SHADER_BATCH` 复用"默认可见 + 中性双路径逐像素恒等"断言；`EffectLibraryNamingTest` 已核对名称唯一。

| 分类 | id | 名称 | 零点契约 | 与 AM 的差异 |
| --- | --- | --- | --- | --- |
| 重复 | `am_shape_field` | 形状场 | alpha=0 | AM repeat 家族；本项目在**合成空间**执行（`isCompositionSpace`），形状场可越过图层矩形；参考的 blendMode/coloring 下拉未暴露 |
| 绘制 | `am_electric_edges` | 电弧边缘 | alpha=0 | 参考读 comp/buffer；这里用自采样环边缘 + fbm 电弧；`distance/thickness/complexity/segments/distOffs/disort` 全部参与计算 |
| 绘制 | `am_roughen_edges` | 粗糙边缘 | strength=0 | 参考的 buffer 边缘改成本层自采样 alpha 梯度 + fbm 位移；4 向 selector 保留为数值参数 |
| 绘制 | `am_smooth_bevel` | 平滑斜面 2 | radius=0 | 参考用 blur buffer 做高度图；这里用 8 tap 环自采样；光照方向 = 角度 + elevation 组成的 3D 光向量，diffuse/spec 各一路 |
| 生成 | `am_voronoi_2` | Voronoi 2 | alpha=0 | 3×3 F1/F2 voronoi；`lineWidth/density/seed/phase` 全部参与；额外加 `alpha` 参数以获得零点 |
| 生成 | `am_ridges_2` | 山脊 2 | alpha=0 | ridged fbm（1-|2n-1|）；stretch/position 参与采样空间；额外加 `alpha` |
| 生成 | `am_noise_3` | 噪声 3 | amount=0 | AM amount 本身就是混合量，天然零点 |
| 生成 | `am_block_noise` | 块状噪波 | amount=0 | 格子 hash 噪声；stretch/position/size/seed 参与 |
| 绘制 | `am_halftone_cmyk` | CMYK 半调 | strength=0 | 简化版 CMYK 网点（黑版权重可调）；参考的多通道半调简化为单通道网点 |
| 调色 | `am_rgb_sep` | RGB 分离 2 | strength=0 | 3 种分离模式 + 中心通道选择（数值 selector）；与经典 `rgb_split` 并存（后者是像素通道偏移，本条目是 AM 的分离语义） |
| 生成 | `am_simplestars_2` | 简易星点 2 | alpha=0 | 每格概率星点 + 柔边；starCount 作为出现概率、starSize 控制格子与星点尺寸 |
| 生成 | `am_volumetric_clouds` | 体积云 | amount=0 | fbm 云密度阈值 + 双色；额外加 `amount` 参数以获得零点（AM 无此参数） |

### 3.5 重复 `repeat.path` 与文字 6 项的可行性判定（不做，原因如下）
**`repeat.path`（沿路径复制）——不做。**
1. AM 的定义是"把图层沿一条路径重复摆放"，参数只有 count/offset/scale/alpha 外加路径相位；路径本身不是效果参数，
   而是 AM 图层"路径图层"（shape/path layer）的数据来源（repeat.path 的 19 个参数里没有路径点列表）。
2. 本项目的效果参数域是 `CompEffect.params`（float/color），**没有**路径引用类型；图层路径几何在
   `CompShape`/`CompMask`（pen 写域）里，效果侧只读它无法表达"沿哪个图层/哪条子路径"。
3. 界面（`UICompositorPanel`，本轮禁止改）没有"给效果指定路径"的入口；若退化为"自动取本图层第一个 PATH 形状/蒙版"，
   对绝大多数图层会**静默无效**，并且会与"蒙版是 alpha 运算"的既有语义冲突（用户无法预期）。
4. 结论：需要先有"效果参数引用路径"的模型 + UI 选择器（至少涉及 CompEffect 参数类型、检查器、路径数据只读接口），
   不在本轮写域内；不做假实现（不塞一个永不生效的效果）。

**文字 6 项（texttransform/textspacing/textprogress/textrand/timecode/counter）——不做。**
1. 从 `_research/am/effects.json` 看，这 6 条 `shaderPresent=false`，参数是字符/间距/进度/随机种子/时间码格式等，
   属于**图层属性动画预设**（逐字符排版/内容生成），不是像素函数；AM 里它们也作用于文字图层的排版阶段，而非栅格之后。
2. 本项目已有对应的图层能力：文字内容模式（timecode / counter / random / reveal，见 `TextContent` + 文字检查器）
   与文字动画器（`CompTextAnimator`）。把它们再包装成"效果"会造成两个入口做同一件事，且需要在检查器里加参数控件
   （`UICompositorPanel` 是禁止写域）。
3. 结论：不塞空效果；已记入第 2 节 E 表"文字语义判定"。若将来要统一入口，建议在 UI 层把现有文字属性"提升为效果行"，
   而不是在效果库里复制一份。

### 3.1b 0.93（P1 调色首批：2 个）

- `am_rgb_mixer`（RGB 混合）：AM `rgb-mixer` 的 6 参数（rmix/gmix/bmix 三色 + gamma + offset），
  恒等值 = 红→红/绿→绿/蓝→蓝三元组（与已有 `am_channel_map` 同一约定），零点契约用
  `neutralWhen` 声明在"恒等三元组 + gamma=1 + offset=0"。
- `am_solid_color`（纯色）：AM `solidcolor` 的 color + alpha + blend（0 正常/1 相乘/2 滤色），
  `alpha=0` 是严格恒等（real zeroKey）。
- 两者都走 `AmEffectDef` 数据驱动机制（无新增 shader 家族），已进效果库/参数面板/关键帧通道。

### 3.3 0.92（上一轮）

1. **重复类语义**（需求 c）：`grid_repeat` / `random_repeat` / `am_grid_repeat` / `am_line_repeat`
   从"图层内平铺"改为 AM repeat 家族语义的**合成空间图层复制**（`ShaderSources.REPLICATE`）。
   详见 [`EFFECTS-FIX-REPORT.zh.md`](EFFECTS-FIX-REPORT.zh.md) 第 3 节。
2. **范围/步进对齐**（11 处，均为"同一参数、同一单位"，不动零点契约和预览默认值）：
   - `am_hue_shift` hue：-180..180/1° → **0..360/0.5°**（AM 范围）；
   - `am_glow` radius max 80→**400**、step 1→**0.5**；`am_inner_glow` radius max 60→**400**、step→0.5；
   - `am_pixelate` size max 128→**500**；
   - `am_tile` scale 0.1..12/0.01 → **0.005..20/0.005**；
   - `am_four_color_gradient` alpha step 0.01→**0.001**；
   - `am_grid_lines` / `am_stripes` angle step 1→**0.5**（AM angle 步进）；
   - `am_hex_array` / `am_hex_shift` / `am_hex_tile` size min 4→**0.5**、step 1→**0.1**，并同步把
     shader 里的 `max(u_size, 4/1)` 下限改成 0.5（否则新范围下半段无效）。
3. **3D 支持域**：`AmEffectDef.padding` 现在真正参与 `EffectPadding`（见修复报告 a/A3），
   模糊/发光/阴影类在 3D 烘焙里有正确留白；纯模糊半径在代理栅格下按 scale 换算。

## 4. 剩余风险

1. `selector` 类型缺失是**结构性**的：要 100% 对齐 AM 的参数表，需要给 `AmEffectDef` 增加 menu 参数
   类型并让 `CompEffectRegistry`/检查器渲染下拉/单选（`UICompositorPanel` 是 Lead/UI 队友写域）。
   本轮只在数值上对齐，不假装已对齐选项。
2. 默认值刻意保留"非中性"（如 hue=30、tile scale=2）：AM 新建效果默认不动画面，而本项目的效果库
   以缩略图 + "加上就能看到"为使用方式。这是产品取舍，已在第 2 节 B 类说明；若用户明确要求 AM 的
   默认值，可以只改默认值不改 shader。
3. AM 的参数名是英文资源 key；本项目参数名是中文 UI 标签，`compare.py` 的"同名暴露"按 key 比较，
   因此第 2 节表低估了实际等价参数（例如 AM `threshold` ↔ 本项目 `range`）。表中缺项需要人工复核后再补。
