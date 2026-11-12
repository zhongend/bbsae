# Round 52 — 效果 2D/3D/导出一致性 + 默认效果面板右置 + 文字/蒙版性能（0.97）

> 第四轮「假设所有功能都有 bug」：只读审计**效果/蒙版/文字 在 2D 层 / 3D 透视层 / 3D 工作区 / AE 预览 /
> 导出 五路径**的一致性 + **每帧/每 tick 性能热点**。本轮修掉用户明确抱怨的「有些效果在 3D 模式没有
> 效果」「重复类效果被图层大小局限成蒙版」「3D 层加效果反而变糊」，并把默认布局改成「效果列表在预览
> 右侧」、文字与蒙版渲染性能按审计 P0 修复。
>
> 验证：**136 个测试套件 / 857 项测试全绿（1 skipped）**；客户端本轮跑了回放诊断（回放 undo 后 UI 消失问题仍未根治，见 §7）。

---

## 1. 效果 3D/导出一致性（审计发现，depth 实现）

### 1.1 P0：光栅缓存键不含渲染模式 → 草稿内容进导出

- `CompLayerSourceCache.signature` 只含层内容/效果/蒙版开关，不含 `context.preview` /
  `draftViewport`。在 3D 工作区看过的预合成（蓝色草稿板）或空预合成占位文字会被缓存，
  面板预览/导出继续复用那块光栅。
- 修复：signature 混入 preview+draftViewport（尺寸已天然分键）；测试
  `rasterSignatureSeparatesDraftFromEncoderModes`（preview/export/draft 三键互异）。

### 1.2 P1：同一 3D 层里效果像素单位前后分裂

- 3D 层按 projectedStart 切成「投影前（图层空间）/投影后（合成空间）」两段；但烘培上下文用
  「光栅尺寸的假合成」，前段效果把图层像素当合成像素（相机推拉/图层缩放会改变效果强度，2D 不会）。
- 修复：`EffectSpace` 增加 `refWidth/refHeight`（像素参数参考网格），烘培传入**父合成尺寸**；
  `EffectRenderer.refWidth/refHeight` 统一读参考网格（proxyScale 自动换算）；经典效果不再在
  `scaleEffectForRaster` 预缩放（避免双计），AM 可分离 blur 半径按 raster/comp 预缩放；
  `AmEffectRenderer.Size/Texel` 改用参考网格。测试 `bakeEffectSpaceUsesTheParentCompositionReferenceGrid`。

### 1.3 P1：3D matte 蒙版应用两次且坐标系错

- matte 走 `renderLayerContent`（3D 已把蒙版烘进光栅）后又无条件 `MaskCache.get + PassRenderer.mask`，
  且第二次用仿射矩阵 → 蒙版 alpha 平方、羽化变窄、整体错位。
- 修复：抽 `CompRenderer.applyLayerMask(content, layer, context, model, masksBaked, masksEnabled)`
  单一实现，主层与 track matte 共用；`masksBaked` 不再二次蒙版；未烘培 3D 透视 matte 走
  `getProjected`；尊重蒙版开关。测试 `masksApplyExactlyOnceAcrossTheBakeAndDirectPaths`。

### 1.4 P1：1024px 光栅上限作用到导出 → 3D 层导出画质腰斩

- `MAX_EDGE=1024` 本应是草稿预算，却同时卡住面板预览/绿帧/导出；加了 glow/blur padding 后 rect 变大
  会进一步降低光栅分辨率（「加效果反而更糊」）。
- 修复：新增 `rasterMaxEdge(context)`（preview=1024；非 preview = max(1024, 合成长边)）与纯函数
  `rasterScaleFor(w,h,maxEdge)`；测试 `rasterCapKeepsExportDetail`（1900px 层 preview<1、export=1）。

### 1.5 其它一致性修复

- **mask feather/expansion**：先按图层像素 clamp(0..128) 再乘 scale（分辨率无关），`maskLayerScale`
  并入图层自身缩放；2D 主路径/matte/调整层统一。（128 上限仍是性能保护，>128 需多 pass，已记录。）
- **displacement 深度图模式**在 3D 烘培被静默降级为噪声 → `requiresProjectedPassFor(CompEffect)`
  动态把「displacement + depth>0.5」排到投影后。
- **调整层蒙版**尊重「蒙版」开关。
- **绿帧**只在 `cacheScale >= 请求 scale` 时才被 AE 预览优先使用（1920 合成不再被 1280×720 绿帧抢）。
- **BBS 占位板**（等待世界帧）加 preview 门，不再进导出/AE 预览。
- **3D 层 styles 顺序**：有投影后效果时推迟 styles，与 2D「全部效果→styles」一致。
- **UV 方向守护**：新增 `TextureOrientationTest`（GL）钉住 upload/read 与全屏 pass 两条「v=0 在哪」的
  约定，文字上下颠倒类 bug 不再无守护。
- 新增 `EffectPipeline3DConsistencyTest`（16 例）：分类矩阵、3D 层每效果恰好一次、repeat/shape_field
  图外像素、comp 采样读两种不同 backdrop、mask 先于效果、空间分辨率无关、preview/export 路由一致。

### 1.6 3D 工作区的草稿契约

- 3D 工作区是逐层光栅的 **draft 渲染器**：只跑层内效果，不做合成空间 pass，也不做 track matte /
  混合模式。审计确认这正是「3D 模式没效果」的最常见来源。
- 本轮：状态栏 HUD 明确写「3D 空间 · 草稿（重复/合成采样效果、轨迹遮罩、混合模式不显示）」；
  `CompLayerSourceCache.draftApplies(type)` + 测试 `draftSkipsExactlyThePostProjectionSet` 把已知限制
  钉住，防止以后误以为回归。真正在 3D 工作区补跑投影后效果需要改写 `CompViewportRenderer` 且每层一个
  全屏 pass，列入下轮候选。

---

## 2. 默认布局：效果列表在预览右侧（需求 4）

- `CompDockSource.defaultLayout()` 改为：预览在左，效果库叠在属性栏上方、整体贴预览右侧，时间轴全宽。
  新工程/重置布局/工作区预设默认即此布局；旧工程保存的布局不受影响。
- 布局菜单新增「经典布局（效果库在左侧，旧版默认）」一键回到旧布局；所有面板仍可拖拽组合。
- 单测更新：`CompDockSourceTest`（默认结构、effectsRightLayout 兼容、缓存/持久化不回归）。

---

## 3. 文字渲染性能（审计 P0，最高单点）

- 审计：`FontCoverage.covers()` 每字形 4×stat + 2×length + canonicalPath；200 字形带描边层每帧
  3,600–5,600 次文件系统调用（Windows 20–100µs）/单层 5–40ms；`LayerContentRenderer` 每帧重建布局、
  正则 split、O(n²) wrap、每字形最多 5 次 `batcher.text`（每次 flushBatch = 独立 draw call）→ 1000
  draw call/层/帧。
- 修复：`FontCoverage` 重写热路径（drawText 入口解析一次 Coverage；IdentityHashMap<File,Coverage> 后零 IO；每字体 BMP long[1024] covered/known 位图；resolve 命中不 isFile，失败 1s TTL 负缓存；`invalidate()` 字体替换时丢弃旧 cmap；`-Dbbsae.debugTextPerf` 计数）。`LayerContentRenderer`：布局 LRU(64)（键=文本/字体 identity/字号/tracking/wrap/align/segments）；手写 split + 前缀和 wrap 消除 O(n²)（浮点加法顺序与原 lineWidth 逐位一致）；char→String 64K 表、Quaternionf 复用；绘制改为 `TextRenderer.draw()` 追加到 DrawContext vertex consumers，每层最后 `context.draw()` 一次 → GPU draw call ≈ 字体图集数（通常 1），与行数/描边/字形数无关（旧 200 字形带描边 ≈1000 draw/层/帧）。
- 验证：新增 `TextLayoutTest`（13：旧算法逐行对比、fuzz、split fuzz、缓存命中/失效/LRU、run 合并、char 复用）+ `FontCoverageIoTest`（6：二次 covers 零 IO、同路径新 File 不重读、BMP 位图、缺失文件 stat 一次）；全量 136/857 绿。

## 4. 蒙版/形状/效果解析性能（审计 P0-4/P1-7）

- 审计：蒙版缓存键含 world 矩阵 → 动画/3D 蒙版摄像机一动必 miss；每帧 AWT Area + 25MB 临时数组 +
  boxBlur（1080p 10–40ms/蒙版）；`resolveEffect` 每效果 copy（新对象+2 map）→ 300 层约 1800 copy/帧。
- 修复：`MaskCache` 三层键拆分——内容键（不含 world/frame）/ 纹理槽键（层+尺寸，不含 world）/ 上传 state（content+完整 world 4×4+projected）；摄像机/变换变化复用同一 GlTexture 重传像素，不再 new/淘汰/delete（静态蒙版 120 帧同一实例）；每层深度 8 scratch 池 + CompMask 池化 + 贝塞尔几何缓存 + 每尺寸 AWT 缓冲池（复用 BufferedImage/DataBufferInt，去 getRGB）。`ShapeCache` geometry/raster/texture 三层缓存，raster 键平移不变（纯位置 K 帧命中只改 origin），像素真变时复用同尺寸纹理。`CompLayer` 新增 `maskAtInto/shapeAtInto`、掩码路径串弱缓存、`resolveEffect` 深度 8 scratch（不再 copy+2 map+putAll）、`animatedPaths` 单次遍历、`resolveEffectInto`/`effectValue` 直读。
- 验证：新增 `MaskCacheKeyTest`(6)、`MaskRasterSemanticsTest`(9，逐像素对拍参考实现/非均匀 feather ±1px)、`ShapeCacheTest`(10，逐像素对拍旧栅格化)、`EffectResolveAllocationTest`(8，assertSame/不污染活效果)、`CacheTextureReuseGlTest`(6 GL，纹理复用与像素更新)；全量 136/857 绿。

## 5. 保存路径与偏好读取（审计 P0-3/P2 附带）

- `CompStore.saveChecked` 每次保存对旧文件做 2 次完整读取+解析（版本守卫 + .bak 源）；合并为一次读取/
  一次解析（多 MB 工程在渲染线程上省一次 parse）。
- `CompPrefs.get()` 从每帧同步取改为 volatile 快路径（首次仍同步加载），UI 每帧上百次读取不再争锁。
- 其它审计项（撤销整工程 JSON、流式写、IO 移后台、签名 revision memo）列入下轮。

## 5.1 其它效果数据修复\n\n- `AmEffectLibrary.isNeutral` 缺参数 fallback 从 0 改为定义默认值：旧工程/手改工程缺某个零参数时不再被误判中性而整体跳过（`AmEffectLibraryTest.missingParameterUsesTheDefinitionDefaultInsteadOfZero`）。\n\n## 6. 导出/工程切换安全

- 切换工程或关闭合成器面板时，若正在导出则**取消导出**（原来会在后台写一个用户看不见的工程，且
  混入切换后的编辑）；状态栏告知。导出期间同工程内编辑仍允许（AE 语义），但混版风险已写入文档。

## 7. 回放 undo 后 UI 停止绘制（仍未根治）

- 本轮 3 次客户端诊断（Run 52a/b/c）确认：undo/redo 数据全部正确；`currentScreen`/页面可见性/面积/
  dashboard 当前 panel/根子元素列表全部正常（960×540，子元素 visible=true）；仍只剩世界+gizmo。
- 已排除：屏幕关闭、页面 detach、panel 切换、页面 0 尺寸、BBS 视频尺寸被 film preview 改成 4×2
  （已加保存/恢复全局 BBS 视频设置，无效）。
- 新线索：undo 时 BBS film 的 `setCustomSize w=4 h=2` 日志 —— 旧测试影片的自定义预览尺寸极小；
  需要继续定位 BBS 预览纹理/世界渲染路径与 UI 批次的关系。
- 影响：回放 Ctrl+Z 后要重开面板（数据不丢）。已保留诊断日志与兜底（root 空重开 dashboard）。
- 证据：`docs/verify/round51-replay-*`（0.96 的 Run C/F 历史）。

## 8. 验证

| 项目 | 结果 |
| --- | --- |
| 全量测试 | **136 suites / 857 tests / 0 failed / 0 errors / 1 skipped**（0.96 基线 127/780） |
| 3D 效果一致性 | EffectPipeline3DConsistencyTest 16 + 缓存键/单位/matte/raster/feather/UV 等新增测试 |
| 文字性能 | TextLayoutTest 13 + FontCoverageIoTest 6；draw call ≈ 字体图集数（原 1000/层/帧） |
| 蒙版性能 | MaskCacheKeyTest 6 + MaskRasterSemanticsTest 9 + ShapeCacheTest 10 + EffectResolveAllocationTest 8 + CacheTextureReuseGlTest 6 |
| 客户端 | 回放诊断 3 次（数据通过、UI 问题未根治，见 §7） |

## 9. 剩余风险 / 下一轮候选

1. 回放 undo 后 UI 停止绘制（第一优先，需继续定位 BBS 预览 FBO/世界渲染与 UI 批次）。
2. 3D 工作区补跑投影后效果/matte/blend（或维持草稿契约+HUD）。
3. 撤销整工程 JSON 的 P0-3 剩余项：流式写、IO 移后台、comp 级撤销、revision 锚点。
4. PassRenderer blur 200px 上限换算、AmEffectLibrary 缺参数 fallback 默认值（写域外）。
5. 曲线编辑器零分配采样、绿帧 BitSet 缓存、每帧签名 revision memo、预缓存压缩/字节限流。
