# 显存 / 预缓存审计修复（task-34 / 0.99）

> 依据 Lead 只读审计的 P0-2/P1-1/P1-5 显存与预缓存项。全量 **145 suites / 929 tests /
> 0 failures / 1 skipped**（共享工作区）。未开客户端、未 commit、未越写域（未改 CompRenderer/
> PassRenderer/MaskCache/ShapeCache/CompRenderCache/UI/comp/export/replay）。

## 1. P0-2 显存按字节预算（已修）

**根因**：`FboPool` 只有 `MAX_IDLE = 32` 的**数量**上限。32 个 4K 目标 ≈ 1 GB，而 32 个缩略图
只有 2 MB —— 数量不是显存边界；`CompPrecache` 也按「每 comp 16 张」而不是全局字节，多个打开的
合成会叠加。

**修复**：
- `FboPool`：`bytesOf()`（RGBA8 + 可选 DEPTH24 = 4/8 B/px）、`bytes()`、`idleCount()`、
  `budgetBytes()`（默认 256 MB，`-Dbbsae.fboBudgetBytes` 可配，测试运行时生效）、
  `trimTo(long)`；`release()` 在入池后按字节预算淘汰最老空闲目标（同一尺寸桶内 FIFO），
  `acquire()`/`clear()` 同步维护计数。
- `CompPrecache`：`residentBytes()`（所有 Cache 的活动纹理 + FREE 池）、
  `residentBudgetBytes()`（默认 256 MB，`-Dbbsae.precacheBudgetBytes`）、
  `enforceResidentBudget(active)`（先释放**非活动** comp 的纹理，再按 LRU 淘汰活动 comp，最后删
  FREE 池）、`releaseInactiveCaches(active)`（切换合成时调用）。

**测试**（`RenderMemoryBudgetTest`，7 例）：2 MB 预算下放/收 40 个 512² FBO → `bytes()` 不超预算
且数量上限仍生效；`trimTo(0)` 清空；`clearAll()` 后 `residentBytes()==0`。

## 2. P1-1 绿帧写盘 generation 竞态（已修）

**根因**：`writeAsync` 只捕获 `generation` 并在写前比较一次；`prepare()` 的 generation 自增 +
目录删除与写任务没有互斥。窗口内写任务可在新 signature 目录里重新创建旧帧（下次启动被当成新内容）。
另外写盘是 `Files.write` 直写，崩溃可能留半个文件。

**修复**：
- `Cache.generation`、`Frame.onDisk` 改 `volatile`。
- 帧文件名带内容 **signature**：`f%06d_%016x.rgba`；`restore()` 解析文件名，signature 与
  `meta.txt` 不符的文件直接删除/忽略（旧代帧永不被新内容复用）。
- `prepare()` 的「generation++ + 清目录 + 写 meta」与写任务共用 `synchronized (cache)`；写任务在
  锁内二次校验 generation+signature 后写**临时文件**再 ATOMIC_MOVE（不支持时退化为普通 move）。
- `writeAsync` 投递时 `onDisk` 仍由写线程置位（volatile）。

**测试**：文件名/代际逻辑由 `RenderMemoryBudgetTest` 的磁盘身份测试 + 既有 `CompFrameDiskTest`
（7 例）覆盖；`restore` 校验签名在代码路径中，真实竞态需要运行时注入（headless 无 Batcher），
见剩余风险。

## 3. P1-5 预渲染目标分辨率与 1280×720 匹配（已修）

**根因**：`rasterMaxEdge` 只有「preview → 1024 / 非 preview → 合成长边」两档。AE 预览/绿帧虽然
按 1280 渲染，但 3D 层的源栅格仍被 1024 草稿上限截断，1080p 预渲染比需要的更糊。

**修复**：`CompRenderContext.targetEdge`（0=按模式推导）+ `setTargetEdge(int)` 进程级默认值，
供构造内部上下文的 CompRenderer 入口使用：
- `CompPrecache.renderFrame` 渲染期间设 1280，结束后清零；
- `CompFrameServer.frame` 按请求帧尺寸 `max(width,height)` 设置（导出走合成尺寸）；
- 面板预览不设置 → 1024 草稿；导出（preview=false）→ 合成宽/高长边。
`CompLayerSourceCache.rasterMaxEdge` 优先读 `context.targetEdge`。

**测试**：`targetEdgeFollowsTheConsumer`（4K 文档：预览 1024 / 导出 3840 / 预渲染 1280）、
`fourKPrecacheRasterIsBoundedTo1280`（`cacheSize` ≤1280×720）。

## 4. P2-2/P2-3/P2-4 附带（已修）

- **CompFrameDisk 文件名加 alpha 位**：`sig_frame_WxH_a{0,1}.rgba`；不透明帧与 alpha 帧不再共用
  文件（BBS/AE 预览会把带 alpha 的帧画成黑底）。`CompFrameServer` 的 load/saveAsync 传
  `key.alpha`；旧无 alpha 入口保留（测试/兼容）。
- **sweep 并发**：`boolean sweeping` → `AtomicBoolean SWEEPING.compareAndSet`，多写线程不会重入
  sweep。
- **writeAsync onDisk volatile**：见 §2。
- **CACHES key hash 后缀**：`CompPrecache.cacheKey(projectId, compId)` = `safe(a)+"/"+safe(b)+"~"+hash`
  —— `safe()` 64 字符截断/标点归一会让长 id 撞目录，hash 后缀消除碰撞（`precacheKeySurvivesSafeTruncation`
  两例测试：长前缀、`a/b` vs `a`,`b/c`）。

## 5. 变更文件

`FboPool.java`、`CompPrecache.java`、`CompFrameDisk.java`、`CompFrameServer.java`、
`CompRenderContext.java`、`CompLayerSourceCache.java`（`rasterMaxEdge` 读 targetEdge）、
`src/test/java/dev/bbsae/client/render/RenderMemoryBudgetTest.java`（新，7 例）。

## 6. 剩余风险

1. `CompPrecache` 写盘竞态的**真实并发注入**测试需要可控的渲染线程/批处理上下文，headless 下用
   文件名 signature + 锁内二次校验 + 临时文件 rename 守卫；真机可用「预渲染中改参数」抽检目录
   不出现跨代帧。
2. `FboPool.trimTo` 的淘汰顺序在尺寸桶之间是 HashMap 迭代顺序，不是严格全局 LRU（同一尺寸桶内是
   FIFO）。预算是硬边界，命中率略低于理想 LRU；如需严格 LRU 可给 `GlFramebuffer` 加时间戳
   （该文件不在本轮写域）。
3. `CompPrecache` 的 4K 60 帧峰值测试在 headless 无 Batcher，无法真实渲染；改用字节预算纯逻辑 +
   `cacheSize` 上限 + 4K 文档用例验证。真机峰值可用 `residentBytes()`/`bytes()` 打点确认。
4. `CompFrameServer` 的内存缓存仍按自己的 192 MB `BUDGET_BYTES` 管理，未并入 `FboPool` 预算
   （两者目标对象不同：服务器缓存不复用池子）；若需要统一账本可在下一轮合并。
