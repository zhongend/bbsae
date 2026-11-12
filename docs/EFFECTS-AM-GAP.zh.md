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
| 本项目 AM 定义 | 127（0.93 新增 3 个 repeat 家族 + 2 个调色条目） |
| 自动匹配（同名/历史别名） | 94 |
| 未匹配：deprecated 旧版本 | 72 |
| 未匹配：非 shader（图层属性 / 复制器） | 20 |
| 未匹配：3D 图元（本项目 `s3d_*` + MeshRenderer） | 19 |
| 未匹配：同族旧版本（boxblur→boxblur3 等） | 8 |
| 未匹配：可执行 shader、真正缺失 | 64 |

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
| **P0（重复）✅ 0.93** | `repeat` / `radial` / `scatter` | repeat | 6/21/19 | 非 shader；已用 REPLICATE 机制补齐为 `am_repeat` / `am_repeat_radial` / `am_repeat_scatter`（见第 3 节） |
| P0（重复） | `path` | repeat | 19 | 非 shader；沿**路径**复制，需要给效果提供路径几何（本项目文字/图形路径数据在 `CompShape`/排版层，不进效果参数表），本轮未做，列出接口方案后再定 |
| P0（重复） | `shapefield` | repeat | 18 | shader 版重复，可随后 |
| P1（模糊） | `unsharpmask` | blur | 5 | 本项目已有经典 `unsharp_mask`，可改造成 AM 参数版 |
| P1（模糊） | `lensblur2` / `spinblur2` / `streakstrips` / `warpblur` / `chromaticzoomblur` | blur | 6/6/5/12/3 | 镜头/旋转/条纹模糊变体 |
| P1（调色） | `rgb-mixer` ✅ / `solidcolor` ✅ | color | 6/4 | 0.93 已补：`am_rgb_mixer`、`am_solid_color`（数据驱动 body，恒等三元组/alpha=0 零点契约） |
| P1（调色） | `spectralmap` / `softglow` / `darkglow` / `lightglow` / `colorhot` | color | 9/11/9/11/1 | 待下一批（softglow/darkglow/lightglow 需 blur 基元；colorhot 无参数、零点契约需另设计） |
| P1（变形） | `pinchbulge2` / `pinchbulgeinside` / `swirl4` / `hexrotate` / `ominoglass` / `wavewarp2` / `turbulentdisplace3` / `tileshift2` / `stretchsegment` | distort | 4–23 | 已有同族条目，补新版参数即可 |
| P1（文字） | `texttransform` / `textspacing` / `textprogress` / `textrand` / `timecode` / `counter` | text | 16/2/3/6/0/2 | **非 shader**；本项目 4.34 已用"文字内容/文字动画器"实现同等能力，但不在效果库里。补进效果库需要 UI 写域（`UICompositorPanel`），本轮不做 |
| P2 | drawing 家族 `electricedges/roughenedges/smoothbevel/findedges`、procedural `voronoi2/ridges2/turbulence2/noise3/lightning/ribbon/volumetricclouds` 等 | drawing/procedural | 3–27 | 冷门/高性能开销，后续按需 |

## 3. 本轮已修复（参数/语义）

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

### 3.1b 0.93（P1 调色首批：2 个）

- `am_rgb_mixer`（RGB 混合）：AM `rgb-mixer` 的 6 参数（rmix/gmix/bmix 三色 + gamma + offset），
  恒等值 = 红→红/绿→绿/蓝→蓝三元组（与已有 `am_channel_map` 同一约定），零点契约用
  `neutralWhen` 声明在"恒等三元组 + gamma=1 + offset=0"。
- `am_solid_color`（纯色）：AM `solidcolor` 的 color + alpha + blend（0 正常/1 相乘/2 滤色），
  `alpha=0` 是严格恒等（real zeroKey）。
- 两者都走 `AmEffectDef` 数据驱动机制（无新增 shader 家族），已进效果库/参数面板/关键帧通道。

### 3.2 0.92（上一轮）

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
