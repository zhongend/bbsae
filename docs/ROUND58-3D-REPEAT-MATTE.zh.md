# Round 58 — 3D 工作区真实重复 / 抠像同步加固

> 版本仍为 `0.99`。本轮针对需求 8、14 的第二批：工作区的重复效果不再只画一张图层方框，
> 抠像遮罩的 tick/尺寸与合成帧严格配对。

## 1. 3D 工作区里的重复效果（需求 14）

之前 `CompViewportRenderer` 对每个 3D 图层只画一张 quad。`CompLayerSourceCache` 已经把重复类效果
拆到 projected 部分（不烘焙进图层栅格），但工作区没有执行 projected 部分，所以重复效果看起来
「只局限在本图层方框」或完全不出现。

本轮新增 `drawReplicated3D`：

- 只处理第一个 projected effect 是 replicator 的 3D 图层；
- 支持 `am_repeat` / `am_repeat_radial` / `am_repeat_scatter` / `am_grid_repeat` /
  `am_line_repeat`；
- 每个复制体都是一个独立 3D quad：
  - `am_repeat`：按 `offset_x/y` 平移、按 `angle` 递增旋转、按 `scale` 递增缩放；
  - radial：按 `radius/start/sweep` 在弧上排布；
  - scatter：稳定 hash 随机分布；
  - grid：按 columns/rows/gap 在整个合成画面平铺（不再局限于图层方框）；
  - line：按 count/duty/vertical 在整个合成画面排布；
- 复制体在图层局部坐标系中计算，再由图层 world 矩阵变换，因此摄像机环绕时它们是真正的
  3D 平面，不是屏幕空间贴片；
- 单个复制体的数量上限 48，grid 上限 8×8，避免一次视口绘制过载。

正常合成/导出路径原本就使用 composition-space replicate，不受此草稿路径影响。

## 2. 抠像同步加固（需求 8）

- `ReplayEntityMask` 在 AFTER_ENTITIES 时记录 `lastActorTick`（controller tick），
  懒渲染/overlay 路径不再读实时 `controller.getTick()`；世界帧和遮罩使用同一个 tick 与
  `lastActorTransition`。
- 每帧 `beginFrame()` 重置 `lastActorTick`，避免上一帧 tick 被复用。
- `ReplayEntityKey` 遇到遮罩尺寸与世界帧不一致时 **拒绝绘制**（而不是拉伸旧遮罩），并保留
  2 秒一次的警告；尺寸恢复后才继续抠像。

## 3. 钢笔开放路径语义（需求 9）

- `MaskCache.isMatte` 不再要求 `closed`：3 个点以上的开放路径由光栅器隐式连回起点形成填充
  （AE/AM 蒙版语义）。2 个点仍视为正在绘制的线段，不产生蒙版。
- 钢笔 overlay 对开放蒙版画一条 40% 白色的隐式闭合边；开放形状路径仍保持开放。
- 更新 `MaskPathRasterTest` / `MaskGlRenderTest`：开放路径现在有填充，但 `closed` 仍参与
  缓存 key。

## 4. 3D 图层挤压体积（需求 13）

新增效果 `挤压体积 / extrude3d`（AM · 3D 形状）：

- `depth` 图层局部 Z 厚度（1..400 px，默认 60）；
- `slices` 深度切片数（2..48，默认 16）；
- `shade` 侧面明暗（0..1，默认 0.35）。

实现：
- `CompLayerSourceCache` 明确跳过 `extrude3d`，不把它压成一张平面图；
- `CompRenderer.renderBaked3D` 从背面到正面绘制 N 个图层切片，每个切片在图层局部 Z 上平移，
  并按 depth 比例变暗，最后画前盖；
- `CompViewportRenderer` 的 3D 工作区同样绘制深度切片；
- 2D 图层没有真正的 Z 体积，这个效果只在 3D 图层上生效。

## 5. 2D 回放实体的预览兜底（需求 5）

- 2D entity layer 优先使用真实世界 plate + 抠像；
- 如果 plate 不存在、遮罩不是当前帧或尺寸未就绪，不再直接显示「等待世界帧」蓝色占位，
  而是用 `ReplayActorPass.renderFlat` 以合成 ortho 渲染同一批 actor；
- 世界帧恢复后自动回到真实 plate 抠像路径。

## 6. 验证

- 全量测试：**148 suites / 944 tests / 0 failures / 0 errors / 1 skipped**。
- 定向：`EffectRepeatTest`、`EffectPipeline3DConsistencyTest`、`EffectOrderTest`、
  `Viewport3DRendererTest`、`EffectPipelineTest`、蒙版 4 个测试类全绿。
- 真机截图沿用 `docs/verify/round57-*`；本轮改动集中在工作区路径、遮罩状态和挤压效果。
