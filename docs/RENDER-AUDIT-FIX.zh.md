# 渲染正确性审计修复（task-33 / 0.99）

> 依据 Lead 只读审计的 P0/P1 正确性项。全量 **144 suites / 922 tests / 0 failures / 1 skipped**
> （共享工作区，含 UI/effects 队友本轮测试）。未开客户端、未 commit、未改 UI/comp/export/replay。

## 1. P0-1 `PassRenderer.mask` 所有权违约（已修）

**根因**：`composite` 程序不可用时 `mask()` 先 `FboPool.release(output)` 再 `return source`。调用方视角
是「成功拿到结果、source 已用完」，于是释放 source —— 同一个 target 被池子收两次；池复用后两块内容
渲染进同一纹理（随机闪烁但无 GL 错误）。同一路径还让 `MaskCache` 以为失败也是成功。

**修复**：
- `PassRenderer.mask` 失败返回 **null**（output 释放），调用方保留自己的 content。
- 4 个主代码调用点全部判空：`CompRenderer.applyLayerMask` / track matte / `applyAdjustment`、
  `CompLayerSourceCache.applyPipelines` 蒙版段（此前 `masked != current` 会把 null 当新结果）。
- `CompShaders.init()` 改为**编译成功后**才置 `initialized`（此前先置 true，一次编译失败会让全部
  程序永久为 null，蒙版/样式静默降级）。`initialized` 以 composite/premultiply/unpremultiply 为准。

**测试**：`RenderAuditFixTest.maskSuccessReturnsANewTargetAndKeepsTheInput`（成功返回新 target、
source 不被 release）、`maskFailureReturnsNullAndKeepsTheInput`（reload 后失败返回 null、source 仍归
调用方）。

## 2. P1-6 MaskCache/ShapeCache key 缺 project/comp 作用域（已修）

**根因**：`MaskCache` slot key 和 `ShapeCache` raster key/recent 槽只用 `layer.id`。父子 precomp 各自
都有 id 相同的图层时，先渲染的一边把纹理给另一边用，state 比对失败后又每帧重栅格化。

**修复**：
- `MaskCache`：新增作用域重载 `get(..., scale, scope)` / `getProjected(..., scale, scope)`；
  `textureSlot(scope, layer, w, h)`、`cacheScope(project, comp)`；主代码调用点全部带作用域（旧无
  作用域入口保留给测试/UI 叠加层，行为不变）。
- `ShapeCache`：`get(layer, frame, scope)`；`CACHE`/`RECENT` 以 `scope + "/" + layer.id` 为槽；
  `layerSlot`/`contentKey(layer, frame, scope)`。
- 作用域 = `project.id + "/" + comp.id`（`MaskCache.cacheScope`）。

**测试**：`RenderAuditFixTest.maskSlotsAreScopedByProjectAndComposition`、
`scopedMaskTexturesDoNotCollideAcrossCompositions`、`shapeContentKeyAndSlotIncludeTheScope`；
既有 `MaskCacheKeyTest`（6）/`ShapeCacheTest`（10）/`CacheTextureReuseGlTest`（6）全绿。

## 3. P1-7 烘培 signature 缺效果参考网格（已修）

**根因**：`EffectSpace` 的像素单位参考网格 = 合成尺寸，但 `CompLayerSourceCache.signature` 不含
`comp.width/height`。改合成分辨率后 3D 层效果半径仍用旧网格，直到别的字段变化才重建。

**修复**：signature 混入 `context.comp.width/height` 与 `rasterMaxEdge(context)`（预览 1024 / 导出
合成长边；task-34 会引入 `targetEdge` 后复用）。

**测试**：`RenderAuditFixTest.rasterSignatureFollowsTheCompositionGrid`。

## 4. P1-2 外部资源内容身份进指纹（已修）

**根因**：`CompRenderCache.signature` / `CompLayerSourceCache.signature` 只哈希 `imagePath` 字符串，
替换同名图片不会失效缓存；字体只哈希 `fontName`，替换字体文件不重建字形。

**修复**：
- `ImageTextureCache.contentId(projectId, path)`：绝对路径 + mtime + size（资源包图片用命名空间
  路径身份）；`Entry.contentId` 同步记录。
- `FontCoverage.contentId(fontName)`：路径 + mtime + size（内置/缺失 → 0）。
- 两个 signature 对 image / text 图层混入上述 contentId。

**测试**：`RenderAuditFixTest.signaturesFollowImageFileContent`（同路径改字节/时间 → 帧签名与
烘培签名都变化）、`fontContentIdentityChangesWithTheFile`。

## 5. P1-3 单图层渲染异常泄漏 FBO（已修）

**根因**：`renderCompToFbo` 的图层循环里 `content`/中间目标没有异常路径释放；只有 accumulation 在
外层 finally 里被释放。一个坏图层/效果异常会永久漏掉 content（每帧重试则每帧漏一个）。

**修复**：每个图层的处理体加 `try/catch (RuntimeException | Error)`，异常时释放仍归本层所有
（`!pooled`）的 `content` 后原样抛出；正常路径的 inline release 结构与行为不变。

**验证说明**：完整渲染需要 `Batcher2D`（客户端渲染上下文），headless 无法构造真实 comp 图形，
因此没有 100 帧自动计数测试；改为代码路径守卫 + 全量回归（无 FBO 池告警）。真机抽检：坏工程
连续播放后显存不增长。**剩余**：`EffectRenderer.apply` 内部若在分配后抛异常仍可能漏中间目标
（EffectRenderer 不在 task-33 写域之外，但属同类问题，建议下一轮统一用 RAII/开域列表）。

## 6. P1-4 GlTexture 写入绑定缓存矛盾（已修）

**根因**：`allocate/upload/setLinear` 只用 `GlStateManager._bindTexture`（缓存调用）。池子删除纹理
后缓存仍命名旧 id，下一次回收同名的纹理 bind 被静默跳过，上传落到错误纹理（或 GL_INVALID_OPERATION）。

**修复**：所有写入/参数路径改走 `bindForUpdate`（缓存 + 原始 glBindTexture 双写）；
`delete()` 后把缓存归零，避免缓存命名已删除纹理。

**测试**：`RenderAuditFixTest.glTextureUploadWorksAfterDeleteAndRecreate`（8 轮删除/重建后
`glGetTexImage` 读回颜色正确、无 GL 错误）。

## 7. 变更文件

`PassRenderer.java`、`CompShaders.java`、`CompRenderer.java`、`MaskCache.java`、`ShapeCache.java`、
`CompLayerSourceCache.java`、`CompRenderCache.java`、`ImageTextureCache.java`、`FontCoverage.java`、
`GlTexture.java`、`LayerContentRenderer.java`（仅 shape 缓存作用域调用点）、
`src/test/java/dev/bbsae/client/render/RenderAuditFixTest.java`（新，9 例）。

## 8. 未做 / 已知限制

1. 异常泄漏的 100 帧自动测试受 headless 限制（见 §5），用真机抽检补。
2. `UICompositorPanel` 直接调用 `ShapeCache.get(layer, frame)`（UI 写域）：保留无作用域旧入口，
   编辑器叠加层与渲染可能各存一份（只是多一份纹理，不会串），UI 侧可改带作用域。
3. `ImageTextureCache` 资源包图片的内容身份是命名空间路径（资源重载时才变），不回读资源字节。
