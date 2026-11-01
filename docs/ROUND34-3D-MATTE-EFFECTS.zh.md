# Round 34 — 3D 图层、抠像贴合与效果过渡（0.20.0）

> 这一轮不新增“效果种类”，而是修渲染语义：抠像必须透明且跟手、3D 图层必须按同一套深度/投影
> 关系出现、效果必须有真正的“0 = 原图层”。

## 1. 回放实体：背景不透明与延迟

### 1.1 背景为什么不是透明的

`LayerContentRenderer.drawReplayEntity` 原来有一条分支：

```java
if (!layer.threeD) { ...抠像... return; }
// 3D 实体图层
drawWorldPlate(layer, batcher, texture);   // 整块世界画面，完全不透明
```

也就是说，只要实体图层是 **3D**，它就会退化成“未抠像的世界画面”，背景当然不透明。
抠像本质上是**世界相机的屏幕空间属性**，与图层自己的 z/rotationX/Y 无关；3D 变换不应该
把“世界相机已经算好的屏幕位置”再投影一次。

修复：2D/3D 实体图层统一走抠像；3D 时用 `CompScene.world` + 正交投影绘制，
让抠像保持屏幕对齐、背景透明。`ReplayEntityKey` 只输出覆盖区域，其余像素 alpha=0。

### 1.2 延迟的来源

世界演员 pass 使用的不是 Minecraft 的 `tickDelta`，而是 BBS 编辑器控制器从“秒 × 20”游标
推导出来的 **小数 tick**；mask pass 原来传的是 `MinecraftClient.getTickDelta()`。两者相位不同，
演员最多差一个 tick，抠像就会比预览里的原始视频“慢半拍”，无法完全重叠。

修复：在 `FormPoseEvents.ACTOR_BEFORE` 捕获世界演员的 `FilmControllerContext.transition`，
mask 的姿态重应用和 `FilmEntityRenderer` 都用这个值。抠像与世界帧现在使用同一个子 tick。

## 2. 3D 图层

### 2.1 3D 图层的效果为什么失效

`renderBaked3D()` 只做了一件事：把图层的 **source raster** 按相机投影画出来，**没有应用效果栈**。
但 `renderCompToFbo()` 里却写着：

```java
boolean effectsBaked = layer.threeD && hasEnabledEffects(layer);
if (effectsBaked && !depthEffect) continue;
```

它以为非深度效果已经在 bake 里应用过了，于是把所有 3D 图层的效果全部跳过。这就是
“3D 模式下效果都容易失效”的根因。

修复：`effectsBaked` 永远为 false；效果在投影之后、按 composition space 应用一次。
深度效果仍然最后应用（它们需要世界深度）。

### 2.2 视角边缘消失

`projectPlane()` 原来用一个**整块四边形**把完成图投影到 z 平面。只要有一个角落在相机背后，
整个四边形就会被裁掉—— 图层在视角边缘整体消失。

修复：把平面细分成 **16×16** 的网格（`BAKED_CELLS` 也从 8 提到 16）。每个 cell 独立投影、
独立裁剪，可见部分不会因为一个角在相机背后而消失。UV 仍按 source 的真实尺寸归一化，
代理分辨率下也不会错位。

### 2.3 3D 关系

`orderLayers()` 已把“空间相机下的 2D 图层”和 3D 图层放进同一段按 `planeDepth` 排序；
相交平面仍然受画家算法限制（整层图像没有逐像素深度）。本轮先把最影响观感的
**投影裁剪**和 **2D/3D 混排**稳定住；真正的逐像素相交需要共享深度缓冲，留作下一轮架构项。

## 3. 3D 模式下的调整图层

3D 工作台（自由视口）为了性能不跑完整合成图，所以调整图层完全没有入口。现在在
`CompViewportRenderer.renderScene()` 的最后，把所有可见、在时间范围内的调整图层的效果，
作为**全屏后处理**应用到已完成的视口图像上。等价于“调整图层覆盖摄像机界面”，
而且和最终渲染使用同一套效果 shader 与参数。

## 4. 效果强度：0 必须是原图层

### 4.1 通用强度

`CompEffect` 新增通用参数 `global_strength`（默认 1）：

- `EffectRenderer.apply()` 先算 `f(input)`，再按 `mix(input, f(input), strength)` 合成；
- `strength = 0` 直接返回 input，不做任何 pass；
- `strength = 1` 是原来的效果；
- 中间值就是真正的交叉淡化，**生成类效果**（渐变、棋盘、万花筒）也能淡入淡出；
- 参数存进同一张 `params`，因此 `fx/<id>/global_strength` 关键帧、工程序列化都不用改格式；
- 效果检查器每个效果都多一行“效果强度（0 = 原图层）”，并参与“AE 关键帧”一键打键。

### 4.2 中性参数表

`EffectRenderer.isNeutral()` 逐效果判断“当前参数是否等于原图”：

- 模糊/方向模糊/径向模糊 radius ≤ 0；锐化 amount = 0；
- 置换/膨胀/收缩/旋转扭曲/波浪/湍流 amount = 0；
- 发光 radius/opacity/intensity = 0；投影 opacity = 0；描边 width/opacity = 0；
- RGB 分离 amount = 0；线性/径向擦除、溶解 progress = 0；
- 深度雾/景深 amount = 0；扫光/扫描线/VHS/半调/浮雕/镜像的强度 = 0；
- 马赛克 size ≤ 1（1 px 块就是原图）。

这些值不会再启动一次无意义的 pass，也不会因为 shader 的 clamp（例如 mosaic 的 `max(1,size)`、
glow 的 tint）而改变原图。

## 5. 验证

- 单测 **228/228**，0 failures / 0 errors。
- 新增 `EffectNeutralTest`：模糊/发光/描边/擦除/马赛克/强度 0 等身份判断。
- 3D 效果修复、投影细分、调整图层后处理均通过编译与现有 GL 像素测试。
- 版本 `0.20.0`，构建与 SHA-256 见 README/release。

## 6. 已知边界

- 相交 3D 平面仍是画家算法（整层图像合成），逐像素相交需要共享深度缓冲。
- 自由视口对**单图层效果**仍是草稿（只有调整图层做全屏后处理）；看穿相机/最终渲染会应用
  完整效果栈。这样保留了 3D 导航的性能，同时让“调整图层覆盖摄像机”成立。
