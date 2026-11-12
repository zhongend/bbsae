# 主合成「逐像素深度」重影修复报告（需求 3）

> 结论：**重影本体已定位并修复**（0.93，task-7），本轮 task-12 收尾完成：清掉最后的 1/255
> 残差（恢复「开关打开 = 与关闭逐像素完全一致」）、补齐开关两条路径的 renderer 级测试、把 GL 状态
> 保存/恢复与池化 free list 的异常路径钉死。
>
> 根因不在 `DEPTH_PLANE` 的平面/射线数学，而在 `DepthPlaneCompositor` 复用的**颜色附件从来没有被
> 真正清空**：一次裸 `glColorMask(false)` 让 `GlStateManager` 的颜色掩码缓存「以为」已经是 true，
> 随后 `clearColorOnly()` 的 `_colorMask(true, …)` 被缓存短路、`glClear(GL_COLOR_BUFFER_BIT)`
> 静默失效。逐像素深度目标因此在层与层、帧与帧之间变成「叠影画布」：每次 composite 都把历史图层/
> 历史帧的轮廓再次合成到 accumulation 上，摄像机预览于是出现星形/多边形重复与条带。

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

`DepthPlaneCompositor.composite()`（0.93 修复前）：

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
因此「裸 GL 改掩码 + 缓存调用恢复掩码」这一组合必然失配。**颜色附件的 clear 100% 被跳过**，不是偶发。

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
- 目标写入期间**显式关闭混合**：target 里保存的是当前层 straight-alpha 像素，混合只由末尾的
  `PassRenderer.draw` 做一次（混合模式/不透明度语义不变）。
- **所有状态改动都走 `GlStateManager`**（`_enableDepthTest/_disableDepthTest`、`_depthFunc`、
  `_depthMask`、`_colorMask`、`_enableBlend/_disableBlend`），不再制造「实际状态已变、缓存还说没变」
  的失配——这正是本 bug 的起点。进入/退出时保存并恢复深度测试/深度函数/深度写掩码/混合开关。
- **异常路径**：三次 pass 若抛异常（驱动错误、程序被删除等），`finally` 先恢复全部 GL 状态，
  再把**调用者的 framebuffer 与 viewport 绑回去**（异常不会经过末尾的 `PassRenderer.draw`，
  调用方对「当前绑定了合成 target」一无所知），然后异常继续上抛；同一实例随后仍能正常 composite。
- **池化健壮性**：`acquire`/`release` 带 `pooled`/`deleted` 守卫——double release、null release、
  已删除实例的 stale release 都不会让同一个 target 进 free list 两次（否则两个后续消费者会渲染进
  同一纹理）。
- **acquire/release/构造时同时清颜色与深度**：池化目标交出去时是中和的，不携带上一个渲染的颜色。

### 3.2 `client/render/GlFramebuffer.java`

- `clearColorOnly()` 在清屏前先裸 `glColorMask(true,true,true,true)`，再用
  `GlStateManager._colorMask(true,true,true,true)` 把缓存同步回来。这是对所有调用者的防御：
  任何裸 `glColorMask(false,…)` 之后，颜色清屏都不会再静默失效。

### 3.3 `ShaderSources.DEPTH_PLANE`：1/255 残差清理（task-12）

- 删除 `if (source.a < 0.004) { discard; }`。图层栅格 alpha 是 1/255 步进
  （`1/255 = 0.003921… < 0.004`），该判断会丢掉「仅 1/255 覆盖」的抗锯齿边缘像素，而整层画家路径
  会画出来；`____bug` 单帧里约 32 个像素每通道差 1。
- 删除后 `DepthPlaneGhostRegressionTest` 的 `____bug` 两帧比较容差从 1 收紧到 **0**，
  即「开关打开 = 与关闭逐像素完全一致」。**测试没有因此放宽任何断言**：半透明层仍 ≤2/255
  （straight-alpha 合成的固有舍入），其余断言都是 0 容差。

### 3.4 没有改的文件

`CompRenderer` 的 perPixelDepth 分支、`SceneDepth`、`IrisDepth`、`PassRenderer`、`CompShaders`、
`CompPrefs`（默认值）均未改动；0.87/0.88 的摄像机预览不透明舞台/世界抓帧守卫与本 bug 无冲突。
`CompRenderer` 本身在无头环境不可实例化（需要 `MinecraftClient`/`Batcher2D`/渲染线程），
它的开关分支由 §4 的 renderer 级仿真 + 纯逻辑 contract 测试覆盖（分支条件写在测试注释里）。

## 4. 测试（task-12 后共 5 个测试类 / 25 个用例）

测试工具类 `DepthPlanePixelHarness`（`src/test/java/dev/bbsae/client/render/`）：隐藏 GL 上下文，
用真实 `____bug` 相机（数值逐值复制）与 `CompShape` 参数、真实 `ShapeCache` 栅格，按
`LayerContentRenderer` 的内容语义（`projection · model`、SRC_ALPHA、`PassRenderer.unpremultiply`）
渲染内容 FBO，再分别走：
- **painter 路径** = `PassRenderer.draw`（开关关闭的整层画家顺序）；
- **depth 路径** = `DepthPlaneCompositor.composite`（开关打开）；
- **routed 路径** = 完全复刻 `CompRenderer` 的分支：`CompScene.spatial` + 每层 `usePerspective`
  决定该层走 depth 还是 painter，整帧共享一个池化 compositor。

### 4.1 重影回归（`DepthPlaneGhostRegressionTest`，4 例，task-7）

| 测试 | 断言 | 修复前 | 现在 |
| --- | --- | --- | --- |
| `theBugProjectFrameMatchesTheDepthOffFrame` | `____bug` 相机/图层单帧 depth-on = depth-off | 972 通道值差，最大 64 | **0 容差通过** |
| `movingCameraFramesDoNotAccumulateGhosts` | 相机 3 个位姿逐帧 + 池化复用 | 第 0 帧即 972 值差 | **0 容差通过** |
| `aReusedTargetDoesNotKeepThePreviousCompositionsPixels` | 红帧 → release → acquire → 蓝方块；方块外必须是新舞台色 | 左边像素残留上一帧红 | 通过 |
| `translucentLayersAreCompositedExactlyOnce` | 两块半透明（a=0x60）层每像素只合成一次 | 41472 通道值差 >2，最大 70 | 通过（≤2/255） |

### 4.2 开关两条路径（`PerPixelDepthSwitchTest`，7 例，task-12）

| 测试 | 覆盖 |
| --- | --- |
| `switchOnMatchesSwitchOffForTheBugProject` | 同一 `____bug` 场景 off/on/painter 三份帧互相 **0 像素差** |
| `mixed2DAnd3DLayersFollowAEProjection` | AE 逻辑：2D 层保持 painter、3D 层走 depth，整帧 = painter 帧 |
| `legacy3DLogicProjectsEveryLayerThroughTheDepthPath` | 开发者旧逻辑：2D 层也被投影并走 depth，整帧 = painter 帧 |
| `switchOnIsANoOpForAnAll2DComposition` | 全 2D 帧开关打开 = 不开（不改变像素） |
| `translucentLayerOpacityMatchesThePainterPath` | 图层 opacity 0.5/0.75 的半透明 3D 层只合成一次 |
| `pooledCompositorStaysCorrectAcrossCompositionRenders` | 池化实例跨两次 composition（不同相机位姿）都 = painter 帧，且池确实交还同一实例 |
| `debugForceDepthPlaneSelectsTheSamePixelsAsThePreference` | `-Dbbsae.debugForceDepthPlane` / `-Dbbsae.debugNoDepthPlane` 行为与开关一致 |

### 4.3 路由 contract（`PerPixelDepthRoutingTest`，6 例，纯逻辑无 GL）

把 `CompRenderer` 开关分支的两个输入（`CompScene.spatial`、每层 `CompScene.usePerspective`）
按四种工程形态钉住：`____bug`（2 个 3D 层全走 depth）、AE 混合 2D+3D（只 3D 层走 depth）、
legacy 兼容（全部走 depth）、全 2D/纯平面帧（不 acquire depth target）。这些用例在无显示器环境也运行。

### 4.4 状态恢复与池化健壮性（`DepthPlaneStateRobustnessTest`，4 例，task-12）

| 测试 | 断言 |
| --- | --- |
| `glStateIsRestoredAfterAComposite` | 用「非默认」状态进入（depth test on、func GREATER、depthMask false、blend on、colorMask false），composite 后 depth func/mask 原值恢复、colorMask 全开；depth test/blend 按图的既有约定由末尾全屏合成 pass 关闭 |
| `stateAndFramebufferAreRestoredWhenAPassThrows` | 通过仅测试用的 `-Dbbsae.debugDepthPlaneThrow` 在 pass 中注入异常：异常上抛但 depth func/mask/test、blend、**调用者 framebuffer 与 viewport 全部恢复**；同一实例随后仍能正常 composite |
| `doubleReleaseNeverDuplicatesAPooledTarget` | double release + null release 后池里仍只有一份，两次 acquire 得到不同实例，第一次复用池中实例 |
| `aDeletedTargetCannotReenterThePool` | 已删除实例的 stale release 被忽略，不会把已删 target 交回调用方 |

另：`DepthPlaneCompositorTest`（4 例）继续覆盖平面深度/绘制顺序无关/半透明语义/池复用，
全绿。

## 5. 验证命令与结果

```powershell
# 定向（5 个深度路径测试类）
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain --tests "dev.bbsae.client.render.DepthPlane*" --tests "dev.bbsae.client.render.PerPixelDepth*"
# BUILD SUCCESSFUL（25 tests / 0 failed）

# 全量
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
```

- 定向：**25/25 通过**（`DepthPlaneGhostRegressionTest` 4 + `DepthPlaneCompositorTest` 4 +
  `DepthPlaneStateRobustnessTest` 4 + `PerPixelDepthSwitchTest` 7 + `PerPixelDepthRoutingTest` 6）。
- 全量（共享工作区，含其他队友本轮新增/在建的测试）：**110 suites / 669 tests / 0 failures /
  1 skipped**，全绿。task-12 新增 17 个用例（4 个健壮性 + 7 个开关路径 + 6 个路由 contract），
  并把 task-7 的 4 个重影回归容差收紧到 0。
- 基线：0.93 前 90 suites / 511 tests 全绿；task-12 开始前（0.93 已发布）为 106 suites / 643 tests。

## 5.1 编译/工作区协作备注

本轮共享工作区出现过几次**他人并发写入**导致的临时红：pen/effects 用 PowerShell
`Set-Content -Encoding UTF8` 回写测试文件时带入 UTF-8 BOM（`compileTestJava` 报
`非法字符: '\ufeff'`），以及 effects 正在重构 `CompEffect`/`StyleRenderer` 时的半成品
`compileJava` 错误。均已由对应队友修复；本报告 §5 的全量结果是在三处修复后重跑的稳定树结果。
本任务自己的 5 个测试类始终 25/25 全绿。

## 6. `CompPrefs.perPixelDepth` 默认值建议

**保持 `false`，直到用户真机确认后再改回 `true`。** 本轮按任务要求未改默认值。

一句话开关说明（给设置页/用户）：**「3D 逐像素深度」默认关闭；打开后主合成器对开启 3D 的图层
逐像素比较深度，用于正确显示相交的 3D 平面；若发现异常可随时关闭，画面回到整层排序。**

建议验收（Lead 每轮真机一次）：
1. 打开 `____bug` 工程 → 设置开启「3D 逐像素深度」→ 摄像机预览拖动/环绕/播放，
   确认无星形/多边形重复与条带；
2. 两个相交/共面倾斜平面确认逐像素收益（近层逐像素覆盖）；
3. 半透明 3D 层叠在远处不透明层上的观感（逐像素语义与整层混合略有不同，属预期）。

剩余风险（都有测试兜底，仍值得真机确认一次）：
- 每个透视 3D 层多 3 个全屏 pass + 1 次合成；1080p 多 3D 层播放的额外开销。
- 开发者设置「旧版 3D 逻辑」下所有层走透视/深度路径（`PerPixelDepthSwitchTest` 已覆盖像素一致性）。
- `GL_EQUAL` 颜色 pass 依赖同一着色器两遍算出相同 `gl_FragDepth`（同一 draw 内相同输入；24 位深度
  + 极端 near/far 建议真机抽样）。
- `FBO 不完整` 无法无头构造：`GlFramebuffer` 仅在 `-Dbbsae.debugFbo` 下检查完整性，若目标不完整
  绘制会静默失效；代码上 composite 不做完整性判定（与渲染图其它 pass 一致），已记录备查。

## 7. 变更文件（累计 task-7 + task-12）

| 文件 | 变更 |
| --- | --- |
| `src/main/java/dev/bbsae/client/render/DepthPlaneCompositor.java` | 每层清颜色；acquire/release/构造清颜色+深度；target 写入关混合；全部 GL 状态走 GlStateManager 保存/恢复；异常路径恢复 framebuffer+viewport；`pooled`/`deleted` 守卫；测试用 `-Dbbsae.debugDepthPlaneThrow` 注入点 |
| `src/main/java/dev/bbsae/client/render/GlFramebuffer.java` | `clearColorOnly()` 强制真实 `glColorMask(true)` 并同步缓存，清屏不再可能被缓存短路 |
| `src/main/java/dev/bbsae/client/render/ShaderSources.java` | 仅 `DEPTH_PLANE`：删除 `if (source.a < 0.004) discard;`（1/255 抗锯齿边），其余 shader 未动 |
| `src/test/java/dev/bbsae/client/render/DepthPlanePixelHarness.java` | 测试 harness（真实工程数据 + 内容 pass + painter/depth/routed 三路径） |
| `src/test/java/dev/bbsae/client/render/DepthPlaneGhostRegressionTest.java` | 4 个重影像素回归（容差已收紧到 0） |
| `src/test/java/dev/bbsae/client/render/PerPixelDepthSwitchTest.java` | 7 个开关两条路径 renderer 级测试 |
| `src/test/java/dev/bbsae/client/render/PerPixelDepthRoutingTest.java` | 6 个开关路由 contract（纯逻辑） |
| `src/test/java/dev/bbsae/client/render/DepthPlaneStateRobustnessTest.java` | 4 个状态恢复/异常/池化健壮性测试 |
| `docs/DEPTH-GHOST-FIX.zh.md` | 本报告 |

未 commit/push、未开客户端、未改版本号；`CompPrefs` 默认值未动。
