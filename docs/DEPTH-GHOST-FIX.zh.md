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

## 4. 测试（task-23 后渲染相关共 11 个测试类 / 69 个用例）

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

### 4.5 导出/预览路径一致性（`PerPixelDepthExportConsistencyTest`，5 例，task-19）

**代码路径结论：导出与预览共用同一个 depth 分支，开关不会在两边产生不同路径。**

- 导出：`CompExportManager` → `CompRenderer.renderForExport` → `renderFrame(frame, transparentStage, scale)`
  → `renderToTexture(preview=false, scale=1 或 proxy, transparentStage)` → `renderCompToFbo`；
  预览：`renderCached`/`renderFrameCached` 也走 `renderToTexture` → 同一个 `renderCompToFbo`。
- depth 决策只依赖 `CompRenderer.perPixelDepthFrame(context)` / `perPixelDepthLayer(context, layer)`
  （task-19 把原来两处内联条件抽成这两个 helper，行为不变，便于无头验证），输入是
  `CompScene.spatial` / `usePerspective` + 偏好/调试开关；`preview`、`scale`、stage 颜色
  **都不参与决策**。
- 无头验证（同一 `____bug` 场景，两种分辨率）：导出尺度 768×432 与预览 proxy 尺度 384×216 下，
  depth-on、routed-on 与 depth-off 帧都是 **0 像素差**。即“打开开关”在导出与预览两边都是同一张
  画面；两个分辨率之间只剩下普通光栅化抗锯齿差异，而 depth 路径在该差异上不引入额外项
  （它与同尺度 painter 帧完全相等）。
- Alpha 导出（透明 stage，PNG/ProRes4444 路径）：stage=`0x00000000` 时 depth-on == painter 0 像素差。
- gate 独立性：`preview=true/false`、`scale=1/0.5` 的 context 得到相同 frame/layer 决策；
  `-Dbbsae.debugForceDepthPlane` / `-Dbbsae.debugNoDepthPlane` 行为不变；无头环境偏好不可达时
  `perPixelDepthRequested()` 安全返回 false（与 `CompRenderCache.signature` 同样的容错策略）。
- 局限（诚实说明）：完整 `CompRenderer` 无法无头实例化（需要 `MinecraftClient`/`Batcher2D`/渲染线程），
  所以“完整导出帧”的最终真机验证仍留给 Lead；本测试覆盖决策函数 + 两条路径在两种分辨率/透明
  stage 下的像素等价。导出与预览的其它已知差异不属于本开关：预览可因 `previewBackground` 用透明
  stage、空预合成占位/参考层是编辑期 chrome、proxy 分辨率抗锯齿——导出始终合成到文档背景或 alpha stage。

### 4.6 AUTO 策略量化评估（`PerPixelDepthAutoPolicyTest` 6 例 + `DepthPlaneCostBenchmarkTest`，task-19）

**成本（分析）**：painter 每层 = 1 个全屏合成 pass；depth 每透视 3D 层 = 4 个（深度预写、不透明颜色、
半透明颜色写入私有目标，再把私有目标合成到 accumulation），**净 +3 个全屏 pass/3D 层**；每帧额外
持有 1 个 render-size 颜色+深度目标，并多一次目标 clear（acquire/release）。

**实测**（无头隐藏 GL，NVIDIA GTX 1650，960×540，7 reps 取 min，render-only + `glFinish`；共享构建机
有并发负载，数值只作量级参考）：

| 场景 | 2 层 | 3 层 | 4 层 | 6 层 |
| --- | --- | --- | --- | --- |
| 合成阶段仅（预渲染内容，隔离 depth pass 成本） | 1.36× | 1.91× | 1.57× | 1.85× |
| 整帧（含内容生成） | 0.99× | 1.11× | 1.25× | — |

- 合成阶段额外时间约 1.6–6.5 ms/帧 @960×540（2–6 层）；内容生成占比大的真实页面整帧只增加约
  0–25%。1080p 面积约 4 倍 → 合成额外约 6–26 ms/帧（估算，视 GPU/层数）。
- 完整数据见测试写出的 `build/depth-cost-benchmark.txt`（不提交，供真机对比）。

**AUTO 判据（原型实现于测试，纯算术，无 GL）**：逐像素深度只有在「≥2 个透视层、屏幕投影 AABB
重叠、且两平面法线不平行」时才可能改变画面；平行平面（含 `____bug` 的 z=198/736 两层）或单 3D 层
时整层画家排序已经精确。测试结果：

- `____bug` 平行平面 → AUTO=false，且像素证据 painter==depth（0 像素差）；
- 交叉平面场景 → AUTO=true，且像素证据 painter≠depth（>1000 像素不同，逐像素深度真的在起作用）；
- 单透视层 / 不相交旋转层 → false；legacy 模式下的平行场景 → false。

**建议**：
1. **0.95 保持默认 `false`**（本轮未改默认，不做行为变化）。
2. 不建议无条件默认 `true`：对 `____bug` 这类平行平面工程只有 +3 全屏 pass/3D 层的成本、零视觉收益。
3. **推荐未来实现 AUTO 并作为默认**（成本低、收益精确）：平行平面/单 3D 层自动 painter（0 视觉
   差异、0 额外成本），交叉平面自动 depth。补丁设计（本轮只给设计，不改默认）：
   - `CompPrefs.Data` 增加 `perPixelDepthMode` 枚举 `AUTO | ON | OFF`（Lead/UI 写域）；
   - 在 `CompScene`（或 `CompRenderer` 新增 helper）实现 `needsPerPixelDepth(ctx)`：取所有
     `usePerspective` 层，任两层的平面法线叉积 > ε 且投影 AABB 重叠即为 true，层数 <2 直接 false；
   - `CompRenderer.perPixelDepthRequested()` 按 mode 返回：`OFF=false`，`ON=true`，
     `AUTO=needsPerPixelDepth(当前 context)`（需要把 context 传进去或在 acquire 前算一次）；
   - 设置页开关变三态（默认 AUTO），`-Dbbsae.debugForceDepthPlane` / `debugNoDepthPlane` 仍可覆盖；
   - 待 Lead 真机验证 AUTO 语义后再改为默认。

### 4.7 边界健壮性（task-19）

- **near/far 极端值 + `GL_EQUAL`**：`extremeNearFarKeepsTheEqualityPassExact` 用三组极端相机
  （near=0.1/far=1e6、near=10/far=1e7、near=0.1/far=5e6）验证 depth-on 与 painter **0 像素差**。
  结论：`DEPTH_PLANE` 的等深颜色 pass 不会因 24bit 深度量化丢片元——深度预写与颜色两遍用同一
  uniform、同一公式，算出同一位模式；极端 near/far 影响的是层与层之间的 LESS 判定精度（本场景仍与
  float 排序一致）。真正精度风险是“两层深度差小于 24bit 分辨率且顺序浮动”的极端情况，无头用例
  未复现，保留为真机抽样项。
- **组合回归** `precompTranslucentAndMultiple3dLayersCombineExactly`：父合成含 4 类层——真实嵌套
  合成作为预合成 plate（透明 stage 渲染、按 `drawPrecomp` 规则贴到图层矩形）、半透明 3D 层
  （opacity 0.5）、多个不透明 3D 形状层；depth-on / routed-on 与 painter **0 像素差**。覆盖
  「depth-on + 半透明 + 多 3D 层 + 预合成」组合。

### 4.8 AUTO 三态开关生产化（task-23）

**开关语义**（`CompPrefs.perPixelDepthMode`，Lead 加的字段；本任务只消费）：

| 值 | 行为 |
| --- | --- |
| `0` OFF | 不做逐像素深度。若旧字段 `perPixelDepth == true`（0.89 时代的偏好文件），**视为 ON**（迁移兼容） |
| `1` ON | 所有透视 3D 层走逐像素深度（与旧行为一致） |
| `2` AUTO | **只有真正需要时才开**：≥2 个透视 3D 层、屏幕投影 AABB 重叠、且两平面法线不平行 |
| 未知值 | 按 OFF 处理（前后兼容） |

开发者开关优先级不变：`-Dbbsae.debugForceDepthPlane` 强制 ON（任何 mode）；
`-Dbbsae.debugNoDepthPlane` 仍是逐层 kill switch。gate 只看帧几何 + 开关 + debug 覆盖，
`preview`/`scale`/stage 从不参与，因此导出与预览共用同一决策。

**AUTO 判据**（生产实现 `CompRenderer.needsPerPixelDepthAuto(CompRenderContext)`，由
`PerPixelDepthAutoPolicyTest` 原型迁移而来）：遍历 `visible && activeAt(time) && !soloedOut &&
CompScene.usePerspective` 的层；<2 层直接 false；任一两两比较：用
`projection · view · world` 投影图层内容矩形的四角得到 AABB（与相机 `CompCamera.project` 一致），
AABB 相交且 `|n_a · n_b| < 1-ε`（非平行）则为 true。legacy 3D 逻辑下 `usePerspective` 对所有层为
true，判据自然按“全层投影”处理，无需另分支。

**为什么这样定义**：整层画家排序按平面深度排序，对**平行平面**是精确的（`____bug` 工程就是这种
最常见情况），逐像素深度只会多付 3 个全屏 pass/层而没有像素收益；只有非平行平面在屏幕上重叠时，
深度顺序才会在同一图层内的不同像素翻转，那时才必须逐像素。AUTO 因此是“默认开但只为真正需要的帧
付费”：平行 3D 帧的渲染路径与 0.95 完全一致（0 额外 pass），交叉平面帧自动获得正确结果。

**测试**（`PerPixelDepthAutoModeTest` 9 例，GL/逻辑）：
- `autoStaysOffForTheParallelPlaneBugProject`（`____bug` 平行平面 → 不开）
- `autoTurnsOnForCrossingPlanes`（交叉平面 → 开）
- `autoStaysOffForSingleAndNonOverlappingAndFlatScenes`（单 3D 层 / 不相交旋转层 / 全 2D）
- `legacy3DLogicUsesTheSameAutoPredicate`（legacy 平行→关、交叉→开）
- `triStateGateMatchesTheDocumentedSemantics`（OFF/ON/AUTO/未知值/legacy boolean/debugForce 对照）
- `autoDecisionIgnoresPreviewAndScale`
- `autoKeepsTheBugProjectFrameIdenticalToPainterOrder`（AUTO 关时帧 = painter，0 像素差）
- `autoMatchesExplicitOnForCrossingPlanes`（AUTO 开时与强制 ON 逐像素一致；painter 帧不同 >1000 像素）
- `autoFramesMatchAtExportAndPreviewScales`（768×432 与 384×216 下 AUTO == 强制 ON，0 像素差）

`PerPixelDepthAutoPolicyTest`（6 例）保留为 AUTO 判据与像素证据测试，现在直接委托生产
`CompRenderer.needsPerPixelDepthAuto`；`PerPixelDepthExportConsistencyTest` / `DepthPlaneGhostRegressionTest`
的 gate 断言同步改为三态显式 API。

**缓存失效注意（需要 Lead UI 侧一行/一次调用）**：`perPixelDepthMode` 是显示偏好、不是文档内容，
`CompRenderCache.signature`（不在本任务写域）目前仍只哈希旧 boolean。生产 gate 在观察到 mode/boolean
变化时会清 `CompRenderCache` + `CompFrameServer`（仅在实际渲染时触发，缓存命中路径不会走到），因此
**UI 写入 mode 时请一并调用 `CompRenderCache.clear(); CompFrameServer.invalidate(); CompPrecache.clearAll();`**
（或后续把 mode 加进 signature：`h = mix(h, perPixelDepthMode)` 一行）。UI 语义建议：
下拉/三态「关闭 / 开启 / 自动（推荐）」，说明文字“自动：仅在 3D 平面相交时启用”。

## 5. 验证命令与结果

```powershell
# 定向（AUTO 三态与深度路径全部测试类）
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain --tests "dev.bbsae.client.render.PerPixelDepth*" --tests "dev.bbsae.client.render.DepthPlane*" --tests "dev.bbsae.client.render.RenderCacheAuditTest" --tests "dev.bbsae.client.render.DirectPath3DMaskPixelTest"
# BUILD SUCCESSFUL

# 全量
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
```

- 渲染相关 11 个测试类共 **69 例全绿**（重影 4 + 平面深度 4 + 健壮性 4 + 开关 7 + 路由 6 +
  导出一致性 5 + AUTO 判据 6 + AUTO 三态 9 + 成本测量 1 + 缓存审计 21 + 直接路径蒙版 2）。
- 全量（共享工作区，含其它队友测试）：最后一次全量 **125 suites / 754 tests / 0 failures / 1 skipped**
  （task-23 完成时；见下方备注）。
- 基线：0.93 前 90/511；0.94 前 110/669；0.95 前 119/722。

**备注**：全量期间共享工作区里 effects 队友的 task-22 仍在收尾，曾出现 7 个 effects 侧临时红
（EffectP1Batch/EffectPipeline/ShaderCompile/EffectLibraryNaming/EffectThumbnailResource），
与本任务无关；本任务 69 例始终全绿，最终数字以 Lead 发版前的一次全量运行为准。

## 5.2 task-19 全量结果明细

- 117 suites / 699 tests / 0 failures / 1 skipped（未开客户端；串行 wrapper）。
- 其中深度路径相关 8 个测试类共 37 例全绿；其余计数来自其他队友本轮的测试。
- 成本测量测试会写 `build/depth-cost-benchmark.txt`（含 GL renderer 标识与逐层正确性断言），
  该文件是构建产物、不提交；报告中的 §4.6 数字取自该文件。

## 5.1 编译/工作区协作备注

task-12/13 期间共享工作区出现过几次**他人并发写入**导致的临时红：pen/effects 用 PowerShell
`Set-Content -Encoding UTF8` 回写 Java 文件时带入 UTF-8 BOM（`compileTestJava` 报
`非法字符: '\ufeff'`），以及 effects 正在重构 `CompEffect`/`StyleRenderer` 时的半成品
`compileJava` 错误。均已由对应队友修复；本报告的全量结果都是在修复后重跑的稳定树结果。
本任务自己的 8 个测试类始终全绿（task-19 结束时 37/37）。

## 6. 开关与默认值建议（task-23 后）

**推荐：UI 默认值设为 `perPixelDepthMode = 2`（AUTO）**，并在 UI 里提供「关闭 / 开启 / 自动（推荐）」
三态；本任务未改 `CompPrefs` 默认值（字段由 Lead 维护）。

理由：
- AUTO 对最基本的 AE 3D 帧（平行平面、单 3D 层、全 2D）与当前默认 OFF 的渲染路径完全一致——
  0 额外 pass、0 像素差（`autoKeepsTheBugProjectFrameIdenticalToPainterOrder` 用 `____bug` 验证）；
- 只有在“非平行 3D 平面在屏幕上重叠”时 AUTO 才启用逐像素深度，那时 painter 排序本来就是错的，
  启用是纯收益（`autoMatchesExplicitOnForCrossingPlanes` 验证 AUTO == 强制 ON）；
- 导出/预览在两种分辨率下 AUTO 与强制 ON 完全一致（`autoFramesMatchAtExportAndPreviewScales`），
  不存在“编辑器对了导出错了”的风险；
- 迁移安全：旧偏好文件里 `perPixelDepth=true` 在 mode=0 时仍等价 ON，老用户行为不变。

若用户更保守，保持默认 OFF 也完全可用：想处理相交平面时在设置里切到「自动」或「开启」即可。
一句话开关说明（设置页）：**「3D 逐像素深度：关闭 / 开启 / 自动（推荐）。自动 = 只有 3D 平面相交时
才逐像素比较深度；平行平面仍走整层排序，速度与之前一样。」**

真机验收建议（Lead 每轮一次）：
1. `____bug` 工程切 AUTO：摄像机预览拖动/环绕/播放，画面应与 OFF 完全一致（无重影、无变化）；
2. 两个相交/倾斜平面工程切 AUTO：近层应逐像素正确覆盖（与「开启」一致）；
3. 切换三态后绿条/预览应立即使用新路径（若 UI 未清缓存，见 §4.8 末段的一次性清缓存调用）。

剩余风险：
- AUTO 判据是几何 AABB 近似：极端情况下（层内容矩形远大于实际像素内容、旋转导致 AABB 假重叠）
  可能多开逐像素深度——只影响性能，不影响像素正确性。
- 每帧判据 O(透视层²) 的 AABB/法线比较（层数通常个位数，开销可忽略）。
- `CompRenderCache.signature` 尚不含 mode（写域外）：UI 写入 mode 时需清一次帧缓存（§4.8 末段）。
- 每个透视 3D 层多 3 个全屏 pass + 1 次合成（仅 AUTO 判定需要或显式 ON 时）；legacy 模式下
  所有层按投影处理，AUTO 判据已覆盖。
- `GL_EQUAL` 24bit 深度在极端 near/far 下的真机抽样仍保留（task-19 已无头验证常规极端值）。

## 7. 变更文件（累计 task-7 + task-12 + task-19 + task-23）

| 文件 | 变更 |
| --- | --- |
| `src/main/java/dev/bbsae/client/render/DepthPlaneCompositor.java` | 每层清颜色；acquire/release/构造清颜色+深度；target 写入关混合；GL 状态走 GlStateManager 保存/恢复；异常路径恢复 framebuffer+viewport；池化守卫；测试注入点 `-Dbbsae.debugDepthPlaneThrow` |
| `src/main/java/dev/bbsae/client/render/GlFramebuffer.java` | `clearColorOnly()` 强制真实 `glColorMask(true)` 并同步缓存，清屏不再可能被缓存短路 |
| `src/main/java/dev/bbsae/client/render/ShaderSources.java` | 仅 `DEPTH_PLANE`：删除 `if (source.a < 0.004) discard;`（1/255 抗锯齿边），其余 shader 未动 |
| `src/main/java/dev/bbsae/client/render/CompRenderer.java` | depth gate（三态 OFF/ON/AUTO + AUTO 判据 `needsPerPixelDepthAuto` + debug 覆盖 + 显式测试重载）；导出/预览共享；`renderSizeFor`/`renderForCache(scale)`/`previewStageTransparent`/`renderForExport(frame)`；AE 世界深度复用与降级状态 API；直接路径 3D 投影蒙版 |
| `src/main/java/dev/bbsae/client/render/CompRenderContext.java` | `draftViewport`（3D 工作区草稿语义隔离） |
| `src/main/java/dev/bbsae/client/render/MaskCache.java` | `getProjected`/`alphaForProjected`（透视投影 3D 蒙版） |
| `src/main/java/dev/bbsae/client/render/CompRenderCache.java`、`CompLayerSourceCache.java` | 预合成递归指纹（深度 8 + 环栈）、编码器模式、TextContent 字段、预合成栅格指纹 |
| `src/main/java/dev/bbsae/client/render/CompPrecache.java`、`CompFrameServer.java` | 统一尺寸函数、按 scale 预渲染、尺寸守卫、帧服务器尺寸同源 |
| `src/main/java/dev/bbsae/client/render/CompScene.java` | `hasDepth(comp, frame)` 采样关键帧 z |
| `src/main/java/dev/bbsae/client/export/CompExportManager.java` | `sourceFrame` 帧映射 + 按合成帧渲染；残留帧清理 |
| `src/main/java/dev/bbsae/comp/TextContent.java` | `contentHash` / `isTimeDependent`（仅字段读取辅助，不改语义） |
| `src/test/java/dev/bbsae/client/render/DepthPlanePixelHarness.java` | 测试 harness（真实工程数据 + painter/depth/routed 三路径 + 多分辨率 + 预合成 plate + render-only 入口） |
| `src/test/java/dev/bbsae/client/render/DepthPlaneGhostRegressionTest.java` | 4 个重影像素回归（容差 0） |
| `src/test/java/dev/bbsae/client/render/DepthPlaneCompositorTest.java` | 平面深度/绘制顺序无关/半透明语义/池复用（4 例） |
| `src/test/java/dev/bbsae/client/render/DepthPlaneStateRobustnessTest.java` | 状态恢复/异常/池化健壮性（4 例） |
| `src/test/java/dev/bbsae/client/render/PerPixelDepthSwitchTest.java` | 开关两条路径 renderer 级测试（7 例） |
| `src/test/java/dev/bbsae/client/render/PerPixelDepthRoutingTest.java` | 开关路由 contract（6 例，纯逻辑） |
| `src/test/java/dev/bbsae/client/render/PerPixelDepthExportConsistencyTest.java` | 导出/预览一致性（5 例：两种分辨率 0 像素差、透明 stage、gate 独立性、极端 near/far、预合成+半透明+多 3D 层） |
| `src/test/java/dev/bbsae/client/render/PerPixelDepthAutoPolicyTest.java` | AUTO 判据与像素证据（6 例，委托生产 `needsPerPixelDepthAuto`） |
| `src/test/java/dev/bbsae/client/render/PerPixelDepthAutoModeTest.java` | 三态 gate/AUTO 行为/像素一致性（9 例） |
| `src/test/java/dev/bbsae/client/render/DepthPlaneCostBenchmarkTest.java` | 成本测量（写 `build/depth-cost-benchmark.txt`） |
| `docs/DEPTH-GHOST-FIX.zh.md` | 本报告 |

未 commit/push、未开客户端、未改版本号；`CompPrefs` 默认值未改（`perPixelDepthMode` 由 Lead 维护，
建议 UI 默认 `2`=AUTO，见 §6）。
