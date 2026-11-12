# 主合成「逐像素深度」重影修复报告（需求 3）

> 结论：**重影本体已定位并修复**。根因不在 `DEPTH_PLANE` 的平面/射线数学，而在
> `DepthPlaneCompositor` 复用的**颜色附件从来没有被真正清空**：一次裸 `glColorMask(false)`
> 让 `GlStateManager` 的颜色掩码缓存「以为」已经是 true，随后 `clearColorOnly()` 的
> `_colorMask(true, …)` 被缓存短路、`glClear(GL_COLOR_BUFFER_BIT)` 静默失效。逐像素深度目标
> 因此在层与层、帧与帧之间变成了「叠影画布」（palimpsest）：每次 composite 都把历史图层/历史帧的
> 轮廓再次合成到 accumulation 上，摄像机预览于是出现星形/多边形重复与条带。
>
> 修复 + 4 个像素级回归测试后，`____bug` 工程的相机/图层在同一帧、移动相机多帧、跨池复用、
> 半透明叠层 4 种场景下与「关闭逐像素深度」的整层画家路径逐像素一致（仅剩 1/255 抗锯齿差，
> 原因见 §6）。全量测试绿。

---

## 1. 现象与既有证据

- 用户工程 `run/config/bbsae/projects/____bug.json`：1920×1080，自由 BBS 摄像机
  （eye `1968.67/596.55/1370.67`，yaw `-25.74°`，pitch `-5.26°`），两个 3D 形状层：
  六边形 `polygon`（z=198.36）与五角星 `star`（z=736.85），无效果/蒙版。
- 开启主合成逐像素深度后，摄像机预览出现**星形/多边形重复、扫掠条带**；
  关掉 `DepthPlaneCompositor` 后同一工程正常。0.89 起该开关默认关闭（`CompPrefs.perPixelDepth=false`）。
- 仓库内旧证据图：`docs/verify/round72-ghost-before.png` / `round72-ghost-after.png`。
- 历史版本（0.70–0.88）里这条路径**始终开启**（无开关），所以 0.87/0.88 的摄像机预览重影与它同源；
  0.89 只是规避，不是修复。

## 2. 根因（第一性原理）

### 2.1 失败的调用链

`DepthPlaneCompositor.composite()`（修复前）：

```java
this.target.bindOnly();                                   // GlStateManager._colorMask(true,…)：cache=true，跳过
glColorMask(false,false,false,false);                     // 裸 GL：实际=false，cache 仍=true（失配！）
... 深度预写 pass ...
this.target.clearColorOnly(0);                            // 内部 bindOnly() 再 _colorMask(true,…)：
                                                          //   缓存以为已经是 true → 不发 glColorMask
                                                          //   glClear(GL_COLOR_BUFFER_BIT) → 实际 mask=false → 什么都没清
glColorMask(true,true,true,true);                          // 裸 GL 恢复实际=true
... 等深颜色 pass / 半透明 pass ...
return PassRenderer.draw(this.target, accumulation, …);   // 把「当前层 + 所有历史残留」一起合成到 accumulation
```

`GlStateManager._colorMask` 是带缓存短路的（bytecode 里先比较 `COLOR_MASK` 四元组，相等直接 return），
因此「裸 GL 改掩码 + 缓存调用恢复掩码」这一组合必然失配。**颜色附件的 clear 100% 被跳过**，
不是偶发。

### 2.2 为什么表现成「复制/条带」而不是一次性的错色

- 该 target 是**池化**的（0.72 起 `POOL` / `acquire`/`release`），`acquire`/`release` 只清深度、不清颜色；
  一帧内每个 3D 层也复用同一 target（深度必须跨层共享，颜色不应共享）。
- 于是第 N 层/第 N 帧的颜色 =「当前层像素」∪「之前所有层/帧留下的像素」。
  每层 `PassRenderer.draw(target, accumulation)` 又把这个并集整幅合成一次：
  历史轮廓被反复叠加 → 星形/多边形重复；半透明/抗锯齿边缘每叠一次就加深/加亮一次 → 条带。
- 摄像机移动、拖动参数、播放时内容每帧变化，残影按**各历史帧的位姿/尺度**留在 target 里，
  所以用户看到的是「同一图层在多个位置/尺度上的重复」，与 0.89 记录的「摄像机预览 duplicated
  star/polygon layers and stripes」完全吻合。
- 静态且全不透明的画面会「看起来基本正常」（重复的像素恰好相同），这也解释了为什么该 bug 的
  复现依赖交互/播放——之前的静态测试容易漏掉它。

## 3. 修复

### 3.1 `client/render/DepthPlaneCompositor.java`

- **每次 composite 开始时清空颜色附件**（`target.clearColorOnly(0)`），深度缓冲保留（跨层共享）；
  清屏发生在深度预写之前，颜色 pass 不再依赖「两遍之间」的清屏。
- 目标写入期间**显式关闭混合**（`GlStateManager._disableBlend()`）：target 里保存的是当前层的
  straight-alpha 像素，混合只由末尾的 `PassRenderer.draw` 做一次（混合模式/不透明度语义不变）。
- 颜色掩码全部改走 `GlStateManager._colorMask(...)`，不再制造缓存失配。
- 进入/退出时**保存并恢复**本方法会改动的 GL 状态（`GL_DEPTH_TEST`、`GL_DEPTH_FUNC`、
  `GL_DEPTH_WRITEMASK`、`GL_BLEND`），不再把深度/颜色状态泄漏给后续图层、效果和 UI 绘制。
- **acquire/release/构造时同时清颜色与深度**：池化目标交出去时是中和的，不携带上一个渲染的颜色。

### 3.2 `client/render/GlFramebuffer.java`

- `clearColorOnly()` 在清屏前先裸 `glColorMask(true,true,true,true)`，再用
  `GlStateManager._colorMask(true,true,true,true)` 把缓存同步回来。这是对所有调用者的防御：
  任何裸 `glColorMask(false,…)` 之后，颜色清屏都不会再静默失效。

### 3.3 没有改的文件（证据表明无需改）

`CompRenderer` 的 perPixelDepth 分支、`SceneDepth`、`IrisDepth`、`PassRenderer`、`CompShaders`、
`ShaderSources` 均未改动。0.87/0.88 的摄像机预览不透明舞台/世界抓帧守卫与本 bug 无冲突。

## 4. 像素级复现与回归测试

新增 `src/test/java/dev/bbsae/client/render/DepthPlaneGhostRegressionTest.java`（隐藏 GL 上下文，
JUnit `Assumptions` 无显示器时跳过），用**真实工程数据**驱动：

- 相机：`____bug.json` 的 eye/yaw/pitch/fov/near/far 逐值复制；
- 图层：同工程的两个 shape 层（shape 几何 `CompShape` 同参数），经真实 `ShapeCache`（AWT 栅格 +
  GL 上传）生成图层栅格；
- 内容 pass：按 `LayerContentRenderer` 的 3D 透视语义（`projection · view · world`、SRC_ALPHA 混合、
  `PassRenderer.unpremultiply`）渲染到合成尺寸 FBO；
- 两条路径：整层画家路径（`PassRenderer.draw`，=开关关闭）vs 逐像素深度路径（`DepthPlaneCompositor`），
  逐像素比较。

| 测试 | 断言 | 修复前 | 修复后 |
| --- | --- | --- | --- |
| `theBugProjectFrameMatchesTheDepthOffFrame` | `____bug` 相机/图层单帧与关闭深度逐像素一致 | 972 个通道值 >0，最大差 64 | 通过（≤1/255，见 §6） |
| `movingCameraFramesDoNotAccumulateGhosts` | 相机连续 3 个位姿（模拟交互/播放）逐帧与画家路径一致；target 池化复用 | 第 0 帧即 972 值、最大差 64 | 通过 |
| `aReusedTargetDoesNotKeepThePreviousCompositionsPixels` | 先合成整幅红帧 → release → acquire → 合成蓝方块；方块外必须是新舞台色 | 左边像素 `0xFFFF0000`（上一帧红）而非 `0xFF000000` | 通过 |
| `translucentLayersAreCompositedExactlyOnce` | 左右两块半透明（a=0x60）层：每像素只合成一次，与画家路径一致 | 41472 个通道值差 >2，最大 70（左半被重复叠成 0.75 覆盖） | 通过（≤2/255） |

失败时测试会把 painter/depth 两图写到 `build/depth-ghost-evidence/*.png`，便于人工比对。
既有 `DepthPlaneCompositorTest`（4 个平面深度/半透明语义/池复用测试）保持全绿。

## 5. 验证命令与结果

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain --tests "dev.bbsae.client.render.Depth*"
# BUILD SUCCESSFUL

powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
# BUILD SUCCESSFUL
```

- 定向：`DepthPlaneGhostRegressionTest` 4/4 + `DepthPlaneCompositorTest` 4/4。
- 全量（共享工作区，含 effects 队友本轮并行新增的测试）：**96 suites / 564 tests / 0 failures / 1 skipped**。
  本人新增 1 个 suite / 4 个用例；无回归。
- 基线（本轮开始前）：90 suites / 511 tests 全绿。

## 6. 已知的 1/255 残差（非重影，不阻塞）

`ShaderSources.DEPTH_PLANE` 里有一条 `if (source.a < 0.004) discard;`。图层栅格 alpha 是 1/255 步进，
`1/255 = 0.00392 < 0.004`，所以「仅 1/255 覆盖」的抗锯齿边缘像素会被深度路径丢弃，而整层路径会画出来：
`____bug` 单帧里这类边缘约 32 个像素、每通道差 1。这是 shader 细节、与重影无关。

- `ShaderSources.java` 属于 effects 队友本轮写域，**本轮未改**（曾试改验证，随后已还原，
  文件里没有本任务的改动）。
- 建议后续由 effects 侧顺手删除该行（或阈值降到 `1.0/512.0`），即可做到与关闭深度 0 差；
  删除后把本测试 `bug-project-*` 的容差从 1 收紧到 0 即可。

## 7. `CompPrefs.perPixelDepth` 默认值建议

**建议在本轮 Lead 真机验收后改回默认开启（`true`）**，但按任务要求本轮**未擅自改默认**（当前仍为 `false`）。

理由：重影的机制（池化 target 颜色不清）已被根除，且有 4 条像素回归钉住：跨帧、跨层、池复用、
半透明语义都与关闭路径一致。默认开启后，逐像素深度才能解决真正相交的 3D 平面（0.70 的初衷）。

真机验收建议（Lead 每轮一次）：
1. 打开 `____bug` 工程 → 设置开启「3D 逐像素深度」→ 摄像机预览模式下拖动/环绕摄像机、播放，
   确认无星形/多边形重复与条带；
2. 用两个相交/共面倾斜平面确认逐像素深度的收益（近层正确逐像素覆盖）；
3. 半透明 3D 层叠在远处不透明层上的观感（新语义与整层混合略有不同，属预期）。

剩余风险（都已有测试兜底，但值得真机确认一次）：
- 每个透视 3D 层多 3 个全屏 pass（深度预写 + 不透明 + 半透明）+ 1 次合成；1080p 多 3D 层的播放开销。
- 开发者设置「旧版 3D 逻辑」（`legacy3DLogic`）下所有层都会走透视/深度路径，行为与 AE 模式不同。
- `GL_EQUAL` 颜色 pass 依赖同一着色器两遍算出相同 `gl_FragDepth`（同一 draw 内相同输入，目前未见问题；
  24 位深度 + 极端 near/far 组合建议真机抽样确认）。
- 1/255 残差见 §6。

## 8. 变更文件

| 文件 | 变更 |
| --- | --- |
| `src/main/java/dev/bbsae/client/render/DepthPlaneCompositor.java` | 每层清颜色；acquire/release/构造清颜色+深度；显式关混合；GL 深度/混合状态保存恢复；用 `GlStateManager` 颜色掩码 |
| `src/main/java/dev/bbsae/client/render/GlFramebuffer.java` | `clearColorOnly()` 强制真实 `glColorMask(true)` 并同步 `GlStateManager` 缓存，清屏不再可能被缓存短路 |
| `src/test/java/dev/bbsae/client/render/DepthPlaneGhostRegressionTest.java` | 新增：真实 `____bug` 相机/图层的 4 个像素级回归（单帧、移动相机跨帧、池复用残留、半透明单次合成） |

未改：`CompRenderer`、`SceneDepth`、`IrisDepth`、`PassRenderer`、`CompShaders`、`ShaderSources`、
`CompPrefs`（默认值）、UI、`comp/**`、`gradle.properties`。未 commit/push、未开客户端。
