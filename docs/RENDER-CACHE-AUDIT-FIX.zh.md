# 渲染/缓存/导出审计修复报告（task-21，目标 0.95）

> 只读审计在渲染管线上发现的 P1/P2 批次修复。全部无头测试验证（不开客户端）。
> 结论：**P1-A / P1-B / P1-C / P1-D 已修复；P2 ①–⑥ 全部修复。** 新增 23 个测试用例，
> 全量 **119 suites / 722 tests / 0 failures / 1 skipped**（写入时；最后一次全量见 §5）。

## 0. 修复一览

| # | 问题 | 修复 | 测试 |
| --- | --- | --- | --- |
| P1-A | 预合成内容不进缓存指纹 → 子合成编辑后父帧仍显示旧画面（AE 预览/绿条/帧服务器），导出却是新的 | `CompRenderCache.signature` 递归子合成（深度 8 + 环栈）；`CompLayerSourceCache.signature` 同样递归（预合成图层栅格缓存也会旧）；编码器模式入指纹 | `RenderCacheAuditTest` 8 例 |
| P1-B | 3D 预合成在主合成面板预览显示“草稿蓝板” | `CompRenderContext.draftViewport`（仅 3D 工作区置 true）；`CompLayerSourceCache.wantsPrecompPlate` 只在 draft 视图允许画板，面板预览/导出渲染真实嵌套合成 | `RenderCacheAuditTest` 2 例 |
| P1-C | bake 被跳过的 3D 图层（entity / 无缓存 raster 的 precomp）蒙版完全丢失 | 直接路径新增**投影蒙版**：`MaskCache.getProjected`（layer path 展平后经 `projection·view·world` 投影成 render 像素，feather/expansion 按投影比例缩放），在投影后的内容上做 `PassRenderer.mask` | `DirectPath3DMaskPixelTest` 2 例（GL 像素）+ `RenderCacheAuditTest` 2 例（CPU 投影） |
| P1-D | AE 预览/导出 `depth_*` 静默失效（无世界深度） | AE 帧（captureDepth=false）复用 `BbsPreviewBridge.depth()`；显式 captureDepth 仍取新鲜拷贝；两者都没有时记录一次日志并通过 API 暴露可见降级文案 | `RenderCacheAuditTest` 1 例 |
| P2-1 | 导出 `frames/` 残留帧 | 新导出开始前只删除 `frame_*.png`（保留 audio.wav 等其它文件） | `RenderCacheAuditTest` 1 例 |
| P2-2 | 预设 fps ≠ 合成 fps 的取整重复/丢帧 | 输出帧先映射到**合成帧号** `round((start + N/outFps) * compFps)`，`renderForExport` 增加按帧重载 | `RenderCacheAuditTest` 1 例 |
| P2-3 | 预缓存奇偶尺寸与 FBO 尺寸不一致 | 统一 `CompRenderer.renderSizeFor` + `CompPrecache.cacheScale`，预缓存按 scale 渲染；尺寸不一致时丢弃该帧并告警（不再写入错误纹理） | `RenderCacheAuditTest` 1 例 |
| P2-4 | 绿帧与 BBS 底图不一致 | `CompRenderer.previewStageTransparent`：BBS 模式且 `previewBackground==1` 时预缓存使用透明 stage（AE 模式保持编码器精确帧）；编码器模式已进指纹 | `RenderCacheAuditTest` 1 例 |
| P2-5 | TextContent 字段不进指纹 | `TextContent.contentHash` / `isTimeDependent`，加入 `CompRenderCache.layer` 与 `CompLayerSourceCache.signature`，文本图层按时间相关处理 | `RenderCacheAuditTest` 3 例 |
| P2-6 | `CompScene.hasDepth` 用静态 z，关键帧 z 无效 | 用 `layer.scalarAt("z", layer.z, frame)` 采样关键帧 z | `RenderCacheAuditTest` 1 例 |

## 1. P1-A 预合成内容进缓存指纹

**根因**：`CompRenderCache.signature` 只遍历当前 `doc.layers` 的字段，不递归 `project.comps`。
预合成内部编辑后父合成指纹不变 → 编辑器 RAM 缓存、`CompFrameServer` 的 RAM/磁盘键、绿条预渲染
全都返回旧帧；而导出每次重新跑渲染图，于是“导出是新的、预览是旧的”。

**修复**：
- `CompRenderCache.signature` 增加递归：对每个 `precomp` 图层按 `precompId` 找到子合成，
  递归哈希子合成尺寸/fps/activeCameraId/solo 和每个子图层（与 `renderCompToFbo` 相同规则：
  深度 ≤8、当前路径上的合成 id 去重，A↔B 循环只哈希到重复点即停止）。
- 子合成的帧映射 `subFrame = (frame + precompTimeOffset) * precompTimeScale` 传入递归。
- `project.settings.encoder` 也进指纹（BBS/AE 两种预览舞台不同，不能共用缓存帧）。
- `CompLayerSourceCache.signature` 同样递归：预合成图层在面板预览/导出时缓存的是**真实嵌套合成**
  的栅格，若不递归，父合成重渲后仍会从图层栅格缓存拿到旧的内嵌画面。同样的深度 8 + 环栈规则。

**测试**（`RenderCacheAuditTest`）：
- `editingAChildCompositionInvalidatesTheParentFrame`
- `editingAChildKeyframeInvalidatesTheParentFrame`
- `nestedPrecompEditsPropagateToTheTopLevel`
- `cyclicPrecompGraphsTerminateAndStaySensitive`
- `unrelatedCompositionsDoNotInvalidateTheFrame`
- `deepPrecompChainsTerminate`
- `layerSourceFingerprintSeesPrecompContent`
- `cyclicPrecompLayerSourceFingerprintTerminates`

**UI 钩子**：递归指纹让“退出预合成时手动 invalidate”**不再必需**（任何子合成编辑都会自然改变父
指纹）。若 Lead 仍希望退出预合成瞬间清掉绿条/RAM 缓存（更即时），可调用：
`CompFrameServer.invalidate();` + `CompRenderCache.clear();`（都已是 public static；
`CompPrecache` 由签名自行失效，无需清理）。建议在 `exitPrecomp` 里调用这两个即可。

## 2. P1-B 3D 预合成“草稿蓝板”泄漏进主预览

**根因**：`CompLayerSourceCache` 对 `precomp && context.preview` 直接画
`drawPrecompPlate`（“预合成（草稿）”蓝板）。合成面板预览也是 `preview=true`，所以带效果/蒙版的
3D 预合成在面板里显示假画面；3D 工作区才应该用草稿板。

**修复**：
- `CompRenderContext` 新增 `draftViewport`（默认 false）；`CompViewportRenderer.renderScene` 中
  置 true。其它预览/导出路径保持 false。
- `CompLayerSourceCache.wantsPrecompPlate(layer, context)` = `precomp && context.preview && context.draftViewport`。
  不满足时走 `LayerContentRenderer.draw(...)` → `drawPrecomp` 渲染真实嵌套合成（并进入图层局部
  栅格，效果/蒙版/样式仍按原顺序应用）。
- 导出/面板预览不再可能烘进“草稿蓝板”。

**测试**：`precompPlateIsOnlyAllowedInTheDraftViewport`、`renderContextDefaultsAreEditorFriendly`。

**代价**：带效果/蒙版的 3D 预合成在面板预览中现在每帧渲染真实嵌套合成（此前是画板），这是正确性
要求的成本；`CompLayerSourceCache` 仍会按内容签名缓存图层栅格，静止画面不重复渲染。

## 3. P1-C 3D 图层蒙版在 bake 跳过时丢失

**根因**：3D 蒙版只在 `renderBaked3D` 的图层局部栅格里应用。`shouldBake3D` 对 replay entity 层永远
返回 false；预合成在非 draft 视图下如果没有缓存 raster 也会回退直接路径 → 直接路径只处理 2D 蒙版
（`is2D` 分支），3D 图层的 masks 被完全忽略（预览/导出一致地错，但错）。

**修复**：
- `MaskCache.getProjected(layer, w, h, viewProjection, frame, scale)`：把图层局部 mask bezier 展平
  （PathIterator，flatness 0.5），逐顶点经 `projection · view · world` 投影到 render 像素（top-down
  图像坐标，与 2D mask 纹理约定一致），再走与仿射版相同的 boolean combine / feather / expansion /
  invert / opacity 管线；feather/expansion 以“投影像素/图层像素”比例缩放（`projectedMaskScale`）。
  缓存键包含投影矩阵 hash。
- `CompRenderer` 直接路径新增分支：`!is2D && !effectsBaked && masks` 时用同一
  view-projection 生成投影蒙版并 `PassRenderer.mask`，位置与 2D 蒙版相同（content 之后、效果之前）。

**测试**：
- CPU：`projectedMaskFollowsThePerspectiveCamera`、`projectedMaskFollowsARotatedLayer`
  （中心覆盖、远角不覆盖；旋转图层中心仍覆盖）。
- GL 像素：`DirectPath3DMaskPixelTest` 两例——内容按相机投影渲染，用同一矩阵投影的蒙版做
  `PassRenderer.mask`：蒙版中心保留图层 alpha、蒙版外（但仍在图层内容内）alpha 归零；旋转 28° 后
  结论相同（证明蒙版跟随图层平面而不是屏幕矩形）。

**已知限制**：mask 顶点在相机后方时按“丢弃该顶点”近似（部分越过相机平面的蒙版），与既有
直接路径其它投影近似的精度一致；未在无头测试覆盖。

## 4. P1-D AE 路径 depth_* 静默失效

**根因**：`renderToTexture` 只有 `preview=true` 时才看 `BbsPreviewBridge.depth()`；AE 预览/导出走
`renderFrame(... preview=false, captureDepth=false)` → depth 恒为 null → depth_fog/dof 是 no-op，
而 BBS 叠加导出（在真实世界内运行）有效且没有任何提示。

**修复**：
- 非 captureDepth 帧现在优先复用 `BbsPreviewBridge.depth()`（世界渲染时在 LAST/AFTER_TRANSLUCENT
  采的深度副本）。
- 显式 `captureDepth=true` 的路径（overlay/legacy）仍取新鲜拷贝，行为不变。
- 两者都没有时：记一次 WARN（含 comp id / preview 标志），并设置降级状态：
  `CompRenderer.isDepthEffectsDegraded()` / `CompRenderer.depthEffectsDegradedMessage()`
  （用户可读中文说明）/ `CompRenderer.resetDepthEffectsStatus()`（重新进入实时预览时清状态）。
- 检测入口 `CompRenderer.needsWorldDepth(project, doc)` 供 UI/测试复用。

**测试**：`depthEffectsAreDetectedForTheDegradationNotice`（depth_* 检测、`depthEnabled` 开关、
初始状态干净）。**需要 Lead 的 UI 钩子**：设置页/预览栏显示
`depthEffectsDegradedMessage()`（非 null 时显示），进入 BBS 实时预览时调用
`resetDepthEffectsStatus()`。这是唯一必须 Lead 接入的可见降级提示。

## 5. P2 附带修复

| 项 | 修复 | 测试 |
| --- | --- | --- |
| ① 导出 frames/ 残留 | 新导出 `begin()` 只删 `frame_*.png`；FFmpeg 因而不会把上次更长导出的多余帧接进本次视频 | `staleExportFramesAreRemovedButOtherFilesSurvive` |
| ② fps 取整 | `CompExportManager.sourceFrame(comp, start, N, outFps)` = `frameIndex(comp, start + N/outFps)`；`renderForExport(..., int frame, ...)` 重载直接按合成帧号渲染（不再 float time 二次取整） | `exportFrameMappingIsDeterministicWhenPresetFpsDiffers`（24/120 fps、非零起点） |
| ③ 预缓存奇偶尺寸 | 统一 `CompRenderer.renderSizeFor(doc, scale)`（`CompFrameServer.widthOf/heightOf` 也改用它）；`CompPrecache.cacheScale` + `cacheSize` 同源；`renderForCache(scale)` 直接传 scale；渲染尺寸与缓存尺寸不一致时丢弃帧 + WARN（不再写错纹理） | `rendererAndPrecacheAgreeOnTheRasterSize`（1920×1080/1917×1079/1281×721/333×187） |
| ④ 绿帧与 BBS 底图 | `CompRenderer.previewStageTransparent(project)`：BBS 编码器 + `previewBackground==1` → 预缓存用透明 stage，与实时预览的世界底图一致；AE 模式保持不透明/alpha 精确帧。编码器模式已加入指纹（P1-A） | `previewStageTransparencyFollowsTheEncoderMode` |
| ⑤ TextContent 指纹 | `TextContent.contentHash`（textMode/textRandom/textRandomSeed/textProgress/textCounterOffset/textCounterScale，null 归一到文档默认）与 `isTimeDependent`；加入 `CompRenderCache.layer` 与 `CompLayerSourceCache.signature`/`isTimeDependent` | `textContentFieldsInvalidateTheFrameFingerprint`、`textContentTimeDependenceIsDetected`、`layerSourceFingerprintSeesTextContent` |
| ⑥ keyframed z | `CompScene.hasDepth(comp, frame)` 用 `layer.scalarAt("z", layer.z, frame)` | `keyframedZPutsTheFrameIntoPerspectiveMode`（z=0 帧平面、z=60 帧 spatial） |

## 6. 测试与验证

```powershell
# 定向
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain --tests "dev.bbsae.client.render.RenderCacheAuditTest" --tests "dev.bbsae.client.render.DirectPath3DMaskPixelTest"
# BUILD SUCCESSFUL（23 tests / 0 failed）

# 全量
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
# BUILD SUCCESSFUL（119 suites / 722 tests / 0 failures / 1 skipped）
```

- 新增：`RenderCacheAuditTest`（21 例，纯逻辑）+ `DirectPath3DMaskPixelTest`（2 例，隐藏 GL 像素）。
- 全量（共享工作区，含其它队友本轮测试）：**119 suites / 722 tests / 0 failures / 1 skipped**。
- 既有深度路径 8 类 37 例保持全绿（回归）。

## 7. 需要 Lead 的 UI 钩子清单

1. **P1-D 深度降级提示（唯一必须）**：当 `CompRenderer.depthEffectsDegradedMessage() != null` 时，
   在状态栏/预览栏显示该文案；进入 BBS 实时预览（会采集世界深度）后调用
   `CompRenderer.resetDepthEffectsStatus()`。`CompRenderer.isDepthEffectsDegraded()` 可作为标记。
2. **P1-A 可选即时失效**：退出预合成（`exitPrecomp`）时可选调用
   `CompFrameServer.invalidate(); CompRenderCache.clear();`（递归指纹已保证正确性，这只是让绿条/
   RAM 帧立刻清空，不等下一次签名比对）。
3. P1-B / P1-C / P2 无需 UI 改动。

## 8. 变更文件

| 文件 | 变更 |
| --- | --- |
| `client/render/CompRenderCache.java` | 预合成递归指纹（深度 8 + 环栈）、编码器模式入指纹、TextContent 字段 |
| `client/render/CompLayerSourceCache.java` | 预合成图层栅格指纹递归；`wantsPrecompPlate`（draftViewport 限定）；TextContent 指纹/时间相关 |
| `client/render/CompRenderContext.java` | `draftViewport` 标志 |
| `client/render/CompViewportRenderer.java` | 3D 工作区 context 置 `draftViewport=true` |
| `client/render/MaskCache.java` | `getProjected` / `alphaForProjected`（投影 3D 蒙版），共用 `bezierPath`/`upload` |
| `client/render/CompRenderer.java` | 直接路径 3D 投影蒙版；AE 深度复用 bridge + 降级状态 API；`renderSizeFor`、`renderForCache(scale)`、`previewStageTransparent`、`renderForExport(frame)` |
| `client/render/CompFrameServer.java` | 尺寸函数改用 `CompRenderer.renderSizeFor` |
| `client/render/CompPrecache.java` | `cacheScale`、按 scale 渲染、尺寸不一致守卫 |
| `client/render/CompScene.java` | `hasDepth(comp, frame)` 采样关键帧 z |
| `client/export/CompExportManager.java`（仅调用点） | `sourceFrame` 映射 + 按合成帧渲染；`clearStaleFrames` |
| `comp/TextContent.java`（仅字段读取辅助） | `contentHash` / `isTimeDependent`（不改文本语义） |
| `src/test/java/dev/bbsae/client/render/RenderCacheAuditTest.java` | 新增 21 例纯逻辑审计回归 |
| `src/test/java/dev/bbsae/client/render/DirectPath3DMaskPixelTest.java` | 新增 2 例 GL 像素回归（直接路径 3D 蒙版） |

未改：UICompositorPanel/UI 文件、comp 其它文件、replay、gradle 配置。未开客户端、未 commit/push、
未改版本号。
