# Round 93 — 19 项已确认 bug 修复

> 版本 `0.99-hotfix57`。最小改动；未启动客户端。

## A 组

1. **CompParenting.applyDelta**：补 `AXIS_SCALE_Y` 的 `applyAxis`，Y 缩放关键帧与 X 一样
   获得父级基变换补偿。
2. **CompViewportRenderer.drawReplayModels**：用 `Replicate3D.find/transforms` 逐 copy 渲染
   回放模型，`context.modelPixelOffset` 应用 copy，透明度 = layer.opacity × 复制器 alpha。
3. **am_reflection**：`fade = 1 - clamp((1 - mirrored.y)/u_fade,...)`。
4. **三个六边形（及 am_hex_shift）**：p 改为各向同性 `luv * LayerSize / size`；b 偏移
   `(0.5, 0.8660254)`；tile/rotate sourceUv 用 size 回算。
5. **am_grid_lines**：按原生 Type==3 的 `1 - smoothstep(0, thickness, min(g))`。
6. **am_circular_ripple**：`radians(u_phase)`。
7. **am_flicker**：删除 `*30`。
8. **am_energy_beam**：`smoothstep(0.35, 0.5, ...)`。
9. **wiggle**：`(deterministicNoise(frame * frequency, 37) * 2 - 1) * amplitude`。
10. **2D 蒙版羽化/扩展**：`applyModelMask` 的 layer-space 分支把 `maskModel`（已含
    raster scale）传给 `maskLayerScale` 会平方 raster 因子；改为未缩放 model × raster scale
    一次。`applyLayerMask` 原调用本就正确，不改。

## B 组

11. 更新 `orderLayers` 尾部与 `sortSegment` tie 注释：independent 模型/蒙版层是栈序边界。
12. **选 a**：`BLEND_MASK/EXCLUDE` 也作为 `orderLayers` 边界，3D 蒙版按栈序作用于下方
    全部图层；普通 3D 层继续深度排序。按钮文案与行为一致。
13. 删除无调用者的 `applyMatteToBelow`。
14. `CompLayer.maskPreviousBlendMode`（transient）记录原混合模式，取消时恢复。
15. `am_dots/am_halftone_lines` 间距改为像素，标签同步。
16. `am_tileshift_2` stretch X/Y 不再交叉。
17. `am_line_repeat` vertical 改为垂直条带（沿 X 变化）。
18. `CompAudioMixer.resample` 空数组提前返回。
19. `CompExpression` 一元负号优先级：`-2^2 = -4`，指数右结合。

## C 组说明

- A10 的“2D 直渲染路径”在 `applyLayerMask` 已通过 `maskLayerScale(model, maskScale)` 处理；
  实际重复缩放点在 `applyModelMask` 的 layer-space 分支（`maskModel` 已含 raster scale 又
  传一次），本轮修正。缓存状态由 `stateOf(contentHash, world, false)` 包含世界矩阵，缩放变化
  会 miss 并重栅格，无需改 contentHash（避免破坏 `MaskCacheKeyTest` 的 world-free 契约）。
- C16/C17 没有可读 AM 源码可对照，按同族 shader（`am_hex_rotate`/`am_warp_blur`、
  `am_line_repeat` 标签语义）做了一致性修正；如果后续拿到 AM 参考行为再微调。

## 回归测试

- `CompExpressionTest`：一元负号/右结合/`wiggle`。
- `CompParentingTest.bindingKeepsAnimatedYScaleIntact`。
- `CompLayer3DRunOrderTest.replayModelsKeepCompositionStackOrder` / `maskLayersKeepCompositionStackOrder`。
- `MaskLayerScaleTest`。
- 现有 `MaskRasterSemanticsTest`、`BlendModesTest`、`AmEffectLibraryTest` 等继续运行。

## 验证

- 全量测试：**165 套件 / 1005 项 / 0 failures / 0 errors / 1 skipped**。
- 候选包：`bbsae-0.99-hotfix57.jar`（3,953,128 bytes，SHA-256
  `25739590A52B18134C2A651D5E16A000CF4D6F50ED38C604F05051CB9A2E32AF`）。
