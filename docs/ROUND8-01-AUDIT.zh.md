# 第八轮 01：第一性原理审计（2026-10-18）

> 接手上一位 AI 的未完成改动。本文件先回答“这个项目为什么这样工作、哪些假设一旦不成立就会出 bug”，
> 再列出本轮实际发现并修掉的问题。后续步骤见
> [`ROUND8-02-PIXEL.zh.md`](ROUND8-02-PIXEL.zh.md)、[`ROUND8-03-VERIFY.zh.md`](ROUND8-03-VERIFY.zh.md)。

## 1. 第一性原理：合成器是什么

`bbsae` 不是“在 BBS 影片编辑器上盖一层 UI”，而是一个**独立的帧生成器**：

```
BBS film / F6 recorder
  └─ RegisterFrameOverlaysEvent
       └─ CompositionOverlay
            └─ CompRenderer
                 accumulation FBO
                   for layer:
                     content FBO  (solid/text/image/shape/precomp)
                     → mask
                     → track matte
                     → effects (per layer)
                     → layer styles
                     → blend into accumulation
                   adjustment layers run effects on accumulation
```

由此得到几条**不可违反的约束**：

1. **效果是“层 FBO 的纯函数”**。输入是已经画好的 layer content，输出是同尺寸 FBO。
   因此效果参数必须全部进入 shader；否则 UI 上有滑块、画面无变化，属于“效果只存在于名字里”。
2. **效果在层空间（layer space）执行，而不是合成空间**。图层移动/缩放/旋转时，
   镜像的接缝、半色调的网点、Glitch 的条带都必须跟着层走；用 `vUv` 或 `gl_FragCoord`
   会把效果钉在合成画面上，看起来像“层在一个静态滤镜下面移动”。
3. **同一份数据要能被两条链路消费**：BBS 预览 overlay 与 F6 导出都走 `CompRenderer`；
   合成器自己的预览额外有 FX/Mask 开关。任何“只在预览里生效”的写法都是 bug。
4. **注册表与渲染器必须互为子集**。`CompEffectRegistry` 是 UI/序列化的真相，
   `EffectRenderer.supports` 是渲染能力的真相；两边任一方向缺一条，都会出现“能加不能渲”或“能渲但加不了”。
5. **零强度应当是恒等变换**。时间类效果（Glitch/VHS/扫描线偏移）在 amount=0 时必须是 no-op，
   否则用户无法用关键帧淡入淡出。
6. **GL 状态是共享的**。每个 pass 后必须恢复 FBO/viewport/program/texture；效果不能依赖上一个效果的残留状态。

## 2. 审计方法

不是“读一遍代码觉得没问题”，而是把上述约束变成可执行检查：

### 2.1 参数死链扫描

用脚本解析 `CompEffectRegistry` 里每个 `.p(...)` / `.c(...)`，
再在 `EffectRenderer` 里搜索对应字符串。发现：

| 效果 | 参数 | 状态 |
| --- | --- | --- |
| `glitch` | `speed` | 声明了，但 shader 用 `Time` 直接算 tick，速度滑块无效 |
| `vhs` | `speed` | 同上，`Phase` 完全没进 wobble |
| `vhs` | `noise` | 声明了，但 shader 的噪点只乘 `amount`，噪点滑块无效 |
| `depth_dof` | `focus` | 声明了，但 renderer 读的是 `near`/`softness`，对焦深度滑块无效 |

`shake.frequency` 的扫描告警是误报：它在 `CompRenderer` 的模型矩阵分支里消费。

### 2.2 坐标空间检查

逐一检查 PIXEL 新模式的采样来源：

- `mirror` / `kaleidoscope` 直接用 `vUv`：接缝在**合成中心**，不在层中心；
- `halftone` 用 `vUv * Size`：网点固定在合成画面；
- `glitch` 用 `vUv.y` 分带：条带固定在合成画面；
- `vhs` 用 `vUv.y * Size.y` 和 `Size.x` 算扫描线/色偏：同样不跟层。

这正是第 1 节第 2 条约束的反例。

### 2.3 恒等性检查

- `glitch` amount=0：`offset`/`split` 为 0，但 `tick`/gate 仍存在；虽然最终采样相同，属于“碰巧恒等”。
- `vhs` amount=0：`split`/`wobble` 为 0，但 `scan` 仍固定乘 0.88~1.0，**不是恒等**。
- `halftone` strength=0：仍会把 `rgb` 往黑色网点方向 mix，**不是恒等**。
- `emboss` amount=0：仍 `rgb + g*0`，恒等；但 amount=1 时输出是“原色 + 边缘”，不是经典灰色浮雕。
- `mirror` 没有 opacity 参数：只能整幅折叠，不能像 AM 那样叠加半透明镜像。

### 2.4 坐标空间基座检查

`EffectSpace.identity()` 返回的 `LayerV = (0, 1, 0)`，但 `ofBounds()` 对整幅合成返回
`LayerV = (0, -1, 1)`。也就是说 `identity()` 的 `layerUv()` 与 `localToCompUv()` 互不为逆。
它被用于 adjustment/3D/camera 层：这些层上的 PIXEL/GENERATE 效果会**上下翻转采样**。
旧测试只测了 `localToCompUv`，没有测 `layerUv` 的往返，所以一直没暴露。

### 2.5 资源生命周期检查

`CompShaders.delete()` 里 `delete(rgbSplit)` 之后没有把 `rgbSplit` 置空：

```java
... depth = shatter = grade = null;   // 漏了 rgbSplit
```

`initialized = false` 后下一次 `init()` 会覆盖它，但“已删除的 program 仍被静态字段引用”
在热重载/资源重载类逻辑里是悬空句柄，属于必须修的生命周期 bug。

### 2.6 覆盖测试的反向缺口

`EffectCoverageTest` 只检查“注册表 → 渲染器”，没有检查“渲染器 → 注册表”；
也没有检查参数的 min/max/default 是否自洽。一个 `supports()` 里多写的 id 不会被发现。

## 3. 本轮修掉的问题

| # | 问题 | 根因 | 修复 |
| --- | --- | --- | --- |
| 1 | Glitch 速度无效 | shader 用 `Time`，renderer 把 `speed` 写进 `Phase` | tick 改为 `floor(Phase * 14.0)`；`Phase = time * speed` |
| 2 | VHS 速度/噪点无效 | shader 完全没读 `Phase`，噪点只乘 amount | wobble/扫描线用 `Phase`；`noise` 进入颗粒强度 |
| 3 | VHS amount=0 不是恒等 | 扫描线亮度固定为 0.88~1.0 | 所有项乘 `amount`，amount=0 精确恒等 |
| 4 | Halftone amount=0 不是恒等 | 网点 mix 不受 strength 控制 | `ink = smoothstep(0, .35, strength)`，strength=0 精确恒等 |
| 5 | 新模式不跟层 | 用 `vUv` / `gl_FragCoord` / `Size` | 统一走 `layerUv` + `localToSource` + `sampleLocal` |
| 6 | 层外采样被 clamp 成边缘 | `sampleLocal` 只 clamp 合成 UV | 层局部 UV 越界直接返回透明，避免拖影 |
| 7 | `identity()` V 轴反了 | `LayerV` 与 `ofBounds` 不一致 | 改为 `(0, -1, 1)`，并加往返测试 |
| 8 | `depth_dof.focus` 无效 | renderer 读 `near`/`softness` | DOF 分支读 `focus` / `range` |
| 9 | `rgbSplit` 悬空引用 | delete 链漏赋值 | `... = shatter = rgbSplit = grade = null` |
| 10 | Mirror 语义与 AM 不同 | 只做折叠，没有叠加强度 | 新增 opacity，按 straight-alpha over 叠加镜像副本 |
| 11 | Halftone 语义与 AM 不同 | `cell` 像素、无 phase/颜色 | 改为 `amount`（横向网点数）、`strength`、`angle`、`phase`、`dot_color`、`paper_color` |
| 12 | 参数/覆盖测试缺口 | 只测 3 个 shader 模式 | 新增 `PixelPlanTest` + 11 个真实 GL 像素测试 + 反向覆盖/数据自洽测试 |

## 4. 修改文件

- `src/main/java/dev/bbsae/client/render/ShaderSources.java`：PIXEL 家族重写；
- `src/main/java/dev/bbsae/client/render/EffectRenderer.java`：`PixelPlan`、参数映射、DOF focus、支持集合；
- `src/main/java/dev/bbsae/client/render/EffectSpace.java`：identity V 轴；
- `src/main/java/dev/bbsae/client/render/CompShaders.java`：`rgbSplit` 清理；
- `src/main/java/dev/bbsae/comp/CompEffectRegistry.java`：扫描线 offset、半色调 AM 参数、镜像 opacity；
- `src/test/java/dev/bbsae/client/render/ShaderPixelTest.java`：共享 GL 上下文 + 真实像素断言 + 联系表；
- `src/test/java/dev/bbsae/client/render/PixelPlanTest.java`：无 GL 的参数死链测试；
- `src/test/java/dev/bbsae/client/render/EffectSpaceTest.java`：identity 往返；
- `src/test/java/dev/bbsae/comp/EffectCoverageTest.java`：双向覆盖、参数范围、JSON 往返。

## 5. 未在本轮解决的风险

- 没有启动真实 Minecraft 客户端做“肉眼 + F6 导出”回归；本轮证据是真实 OpenGL 3.2 像素测试与
  联系表（`docs/verify/round8-pixel-effects.png`）。真正的 BBS 预览仍建议按
  [`HANDOFF.zh.md`](HANDOFF.zh.md) 的开发客户端流程复验一次。
- CPU 蒙版/形状栅格化在 4K 下的性能问题依旧存在（历史限制，与本轮无关）。
- `EffectRenderer.supports` 与 `apply` 的 switch 仍是两份手写列表；测试能防止漂移，
  但彻底消除需要把 switch 改成注册表驱动的 handler map（较大重构，未在本轮做）。
