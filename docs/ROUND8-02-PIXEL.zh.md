# 第八轮 02：PIXEL 家族扩展（Alight Motion 风格）

> 本文件记录“先扩 shader、再同步注册表/渲染器/覆盖测试”这一步的实际实现。
> 上游审计见 [`ROUND8-01-AUDIT.zh.md`](ROUND8-01-AUDIT.zh.md)。

## 1. 为什么复用 PIXEL

项目原有的效果哲学是“少量 shader 家族 + 参数解释”，而不是一个效果一个 program：

- `COLOR`：调色/亮度/色相/噪点/暗角；
- `DISTORT`：置换/凸出/收缩/扭转/波浪；
- `PIXEL`：马赛克/查找边缘/扫描线/Glitch/VHS/半色调/浮雕/镜像/万花筒；
- `GENERATE`、`KEY`、`TRANSITION`、`DEPTH`、`SHATTER` 等各自一组。

新增 7 个屏幕/风格化效果如果各写一个 program，会带来 7 份 uniform 布局、7 次编译和 7 份状态管理。
它们都是“按 UV 重采样 + 逐像素颜色处理”，所以扩展 `PIXEL` 是最小复杂度方案。

`PIXEL` 的 `Mode` 分配：

| Mode | 效果 | id | 主要参数 |
| --- | --- | --- | --- |
| 0 | 马赛克 | `mosaic` | size |
| 1 | 查找边缘 | `find_edges` | amount, invert |
| 2 | 扫描线 | `scanlines` | opacity, spacing, offset |
| 3 | 故障 | `glitch` | amount, bands, speed, seed |
| 4 | 半色调 | `halftone` | amount, strength, angle, phase, dot_color, paper_color |
| 5 | 浮雕 | `emboss` | amount, angle |
| 6 | 镜像 | `mirror` | vertical, opacity |
| 7 | 万花筒 | `kaleidoscope` | segments, angle |
| 8 | VHS | `vhs` | amount, speed, noise |

## 2. 层空间：所有新模式的第一性原理

层内容被渲染进**合成分辨率**的 FBO，但效果必须在**层空间**执行。否则：

- 层移动时，镜像接缝留在合成中心；
- 半色调网点固定屏幕，层像在网点下面滑动；
- Glitch 条带不跟层；
- VHS 扫描线不跟层。

`EffectSpace` 提供两组映射：

```glsl
// 合成 UV -> 层局部 UV（局部 v 向下）
vec2 luv = layerUv(vUv);

// 层局部 UV -> 合成纹理 UV（纹理 v 向上）
vec2 localToSource(vec2 luv)
{
    vec2 lp = luv * LayerSize;
    vec2 comp = LayerX * lp.x + LayerY * lp.y + LayerOrigin;
    return vec2(comp.x / Size.x, 1.0 - comp.y / Size.y);
}

// 层外必须是透明，不能 clamp 成边缘拖影
vec4 sampleLocal(vec2 luv)
{
    if (any(lessThan(luv, vec2(0.0))) || any(greaterThan(luv, vec2(1.0))))
    {
        return vec4(0.0);
    }

    return texture(Source, clamp(localToSource(luv), vec2(0.0), vec2(1.0)));
}
```

关键点：

1. `LayerU/LayerV` 来自 `EffectSpace.ofBounds`，把合成 UV 映射到层局部 0..1；
2. `LayerX/LayerY/LayerOrigin` 是层局部像素到合成像素的 2×2 矩阵 + 平移；
3. **局部 v 向下，纹理 v 向上**，所以最后要 `1.0 - comp.y / Size.y`；
4. 层外直接返回透明，避免 Glitch/镜像/万花筒把边缘像素拉成拖影。

这一步同时修了 `EffectSpace.identity()` 的 V 轴 bug：旧值 `LayerV=(0,1,0)` 与
`ofBounds()` 的 `(0,-1,1)` 不一致，导致 adjustment/3D 层上的效果上下翻转。

## 3. 七个效果的实现

### 3.1 扫描线 `scanlines`

参数：`opacity`（0..1）、`spacing`（像素，2..64）、`offset`（像素，0..2000）。

```glsl
float row = luv.y * LayerSize.y + Phase;      // Phase = offset
float wave = 0.5 + 0.5 * sin(row * 2π / spacing);
rgb *= 1.0 - opacity * 0.55 * wave;
```

用平滑正弦而不是 `fract()` 硬窗口：在非整数缩放/奇数行距下，硬窗口会整行漏掉。
`offset` 可打关键帧做滚动；`spacing` 以层像素为单位，层缩放时线条一起缩放。

### 3.2 故障 `glitch`

参数：`amount`（像素）、`bands`、`speed`、`seed`。

```glsl
float tick = floor(Phase * 14.0) + seed;       // Phase = time * speed
float band = floor(clamp(luv.y, 0, 1) * bands);
float n = hash21(vec2(band + seed * 0.137, tick));
float gate = step(0.72, n);                    // 约 28% 的条带发生位移
float offset = (n - 0.5) * amount / LayerSize.x * gate;
float split = amount * 0.35 / LayerSize.x;     // RGB 分离
```

- 三个通道分别在 `uv±split` 采样，得到色差；
- 用 `Phase` 而不是原始 `Time`，**速度参数真正生效**；
- amount=0 时 offset/split 都是 0，精确恒等。

### 3.3 VHS `vhs`

参数：`amount`、`speed`、`noise`。

```glsl
float wobble = (sin(localY * 0.9 + Phase * 6.0)
             + 0.5 * sin(localY * 2.3 - Phase * 3.0)) * amount / LayerSize.x;
float split  = amount * 3.0 / LayerSize.x;
float scan   = 1.0 - amount * 0.14 * (0.5 + 0.5 * sin(localY * 1.4 + Phase * 3.0));
float grain  = (hash21(localPixel + floor(Phase * 55.0)) - 0.5) * amount * noise * 0.35;
```

- wobble + 色偏 + 扫描线 + 磁带颗粒；
- `speed` 驱动 `Phase`，`noise` 只影响颗粒；
- amount=0 时 wobble/split/scan/grain 全部归零，精确恒等（旧版 scan 固定乘 0.88，不是恒等）。

### 3.4 半色调 `halftone`

对齐 Alight Motion 的 **Halftone Dots** 属性（官方 guide）：

- Amount：横向网点数量，基于项目宽度；
- Strength：网点大小对亮度的敏感度；
- Angle：网点角度；
- Phase：交替行的错位，0.5 是典型半色调；
- Color 1 / Color 2：网点色 / 纸张色。

实现：

```glsl
float cell = Size.x / amount;                  // 项目宽度 / 横向网点数
vec2 px = luv * LayerSize;
vec2 rp = rot * px;                            // 转到网点角度
rp.x += mod(floor(rp.y / cell), 2.0) * phase * cell;   // 交错行
vec2 grid = fract(rp / cell) - 0.5;
float d = length(grid);
vec2 center = invRot * ((floor(rp / cell) + 0.5) * cell);
float luminance = luma(sampleLocal(center / LayerSize).rgb);
float radius = 0.62 * sqrt(1 - luminance) * mix(0.35, 1.0, strength);
float dotMask = 1 - smoothstep(radius - 0.05, radius + 0.01, d);
vec3 ink = mix(paper.rgb, dot.rgb, dotMask);
float inkA = source.a * mix(paper.a, dot.a, dotMask);
rgb = mix(rgb, ink, smoothstep(0.0, 0.35, strength));
alpha = mix(alpha, inkA, smoothstep(0.0, 0.35, strength));
```

要点：

- 亮度在**网点中心**采样（AM 的行为），而不是当前像素，避免高频抖动；
- Strength=0 时 `smoothstep(0,0.35,0)=0`，精确恒等；
- 纸张 alpha=0 时可以把半色调当“网点蒙版”用；
- 网点颜色/纸张颜色是独立颜色参数，会出现在 Effect Controls 的颜色行。

> 与 AM 的唯一有意识差异：`Size.x` 使用合成宽度（项目宽度），而图案位置使用层局部坐标。
> 这样层移动/缩放时网点跟层走，但密度仍按项目宽度定义。

### 3.5 浮雕 `emboss`

参数：`amount`、`angle`。

```glsl
vec2 dir = vec2(cos(angle), sin(angle)) / LayerSize;
float g = luma(sample(+dir)) - luma(sample(-dir));
float shade = clamp(0.5 + g * amount * 2.0, 0.0, 1.0);
rgb = mix(rgb, vec3(shade), clamp(amount, 0.0, 1.0));
```

- 经典灰色浮雕：方向亮度导数 + 0.5 中灰；
- amount=0 恒等；amount=1 完全灰度浮雕；amount>1 只增加浮雕对比；
- 层边缘会因为 `sampleLocal` 越界透明而出现自然描边，这是预期行为。

### 3.6 镜像 `mirror`

参数：`vertical`（0=左右，1=上下）、`opacity`。

和旧实现（整幅折叠、丢掉一半）不同，这里按 Alight Motion 的
“overlay a copy of itself mirrored” 语义做 straight-alpha over：

```glsl
vec2 muv = luv;
muv.x = abs(muv.x * 2.0 - 1.0);               // 或 muv.y
vec4 orig = sampleLocal(luv);
vec4 mir  = sampleLocal(muv);
float k = opacity * mir.a;
float outA = k + orig.a * (1.0 - k);
rgb = (mir.rgb * k + orig.rgb * orig.a * (1.0 - k)) / outA;
alpha = outA;
```

- opacity=1 时视觉上等价于折叠（镜像副本盖住原半幅）；
- opacity<1 时两半同时可见，可做“半透明镜像”；
- 接缝在层中心，层移动时跟着层走。

### 3.7 万花筒 `kaleidoscope`

参数：`segments`、`angle`。

```glsl
vec2 p = (luv - 0.5) * vec2(aspect, 1.0);
float r = length(p);
float a = atan(p.y, p.x) + radians(angle);
float sector = 2π / segments;
a = abs(mod(a, sector) - sector * 0.5);
vec2 q = vec2(cos(a), sin(a)) * r;
rgb = sampleLocal(vec2(q.x / aspect, q.y) + 0.5).rgb;
```

- 按层宽高比校正，圆不会变椭圆；
- 4 段、6 段等会产生 N 重镜像对称；
- 层外采样透明，不会把合成其他区域卷进来。

## 4. 渲染器：把参数变成 uniform

新增 `EffectRenderer.PixelPlan`：

```java
static final class PixelPlan { int mode; float amount, invert, phase, p1..p4; int dotColor, paperColor; }
static PixelPlan planPixel(CompEffect effect, CompRenderContext context)
```

`pixel()` 只做 `planPixel` → `program.set(...)` → `drawFullscreen`。
这样做的好处是**参数解析可以脱离 GL 测试**：

- `PixelPlanTest` 对七个效果的每个 `.p(...)` 参数逐个改值，断言 `signature()` 变化；
- 任一参数没被 `planPixel` 读取，测试立即失败；
- 不再依赖“看代码觉得它读了”。

`PixelPlan` 的映射：

| 效果 | Mode | Amount | Phase | Param.x | Param.y | Param.z | 颜色 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| mosaic | 0 | size | 0 | 0 | 0 | 0 | - |
| find_edges | 1 | amount | 0 | 0 | 0 | 0 | - |
| scanlines | 2 | opacity | offset | spacing | 0 | 0 | - |
| glitch | 3 | amount | time*speed | bands | seed | 0 | - |
| halftone | 4 | strength | 0 | amount | phase | angle | dot/paper |
| emboss | 5 | amount | 0 | 0 | 0 | angle | - |
| mirror | 6 | opacity | 0 | vertical | 0 | 0 | - |
| kaleidoscope | 7 | 1 | 0 | segments | 0 | angle | - |
| vhs | 8 | amount | time*speed | 0 | 0 | noise | - |

## 5. 同步的注册表与测试

- `CompEffectRegistry`：新增 `screen/retro` 分类；半色调改为 AM 属性；镜像新增 opacity；
  扫描线新增 offset；所有默认值在参数范围内。
- `EffectCoverageTest`：
  - 注册表 → 渲染器（原有）；
  - 渲染器 → 注册表（新增）；
  - 参数 min/max/default/step/重复 key（新增）；
  - 七个新效果的 JSON 往返（新增）。
- `PixelPlanTest`：5 个无 GL 测试，覆盖参数死链、时间/速度、半色调默认值。
- `ShaderPixelTest`：12 个真实 GL 3.2 像素测试，覆盖七个效果的可见行为、恒等性、
  层空间和 4 重对称；并输出 `docs/verify/round8-pixel-effects.png` 联系表。

测试总数：**61**（见 [`ROUND8-03-VERIFY.zh.md`](ROUND8-03-VERIFY.zh.md)）。

## 6. 参考

- Alight Motion Effect Guide：[Halftone Dots](https://guide.alightmotion.com/effects/halftone-dots)、
  [Halftone Lines](https://guide.alightmotion.com/effects/halftone-lines)、
  [Mirror](https://guide.alightmotion.com/effects/mirror)、
  [Kaleidoscope](https://guide.alightmotion.com/effects/kaleidoscope)。
- 项目内既有坐标空间研究：[`HANDOFF.zh.md`](HANDOFF.zh.md) 第 14 节、`EffectSpaceTest`。
