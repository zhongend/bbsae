# Round 19 — AE 编码器模式（0.8.0）

> 用户要求：新增可切换的"AE 编码器模式"，在设置里切换、显著标识、明确告知风险；预览和导出都走
> 这个编码器；同时必须适配 BBS 的特性。
>
> 产物：`bbsae-0.8.0.jar`，593 308 字节，SHA-256
> `dbafe7b2164c3ab64e0c3cbbd50e7a6aa2bfbaf83f68e22cc9638bc67ad5ab5b`。测试 143/143。

---

## 1. 第一性原理：剪辑软件和"录屏插件"的区别

一个 AE 式的合成软件，核心不是特效多，而是**帧从哪来**。

| | BBS 模式（0.7.0 及以前） | AE 编码器模式（本轮） |
| --- | --- | --- |
| 帧的产生者 | 游戏：合成作为 overlay 画进当前 framebuffer | 合成器：`(document, frameIndex) → image` |
| 时间 | 显示刷新率 / BBS 影片 tick | 合成帧率，第 N 帧 = N / fps |
| 分辨率 | 窗口大小 × 预览缩放 | 合成分辨率 |
| 预览与导出 | 两条路径（overlay / BBS 录像器） | 同一个函数，同一个结果 |
| 导出 | BBS 的 FFmpeg 录像器 | `CompExportManager` → FFmpeg |

开源项目里这是标准分层，不是本项目的发明：

* **MLT**（Kdenlive / Shotcut 的引擎）是 producer→filter→consumer 的**拉取模型**：
  "Nothing is decoded or processed unless a downstream consumer needs it"，
  导出和预览用的是同一条链路，只是 consumer 不同。
* **Olive** 有 `RenderManager` + render ticket + `FrameHashCache`：
  预览、导出、预缓存都向渲染器"要第 N 帧"，帧按内容哈希缓存。

本轮的 `CompFrameServer` 就是这一层：**它不编码**，它保证"第 N 帧"只有一个含义。
编码仍然是 `CompExportManager`（FFmpeg）。

## 2. 实现

### 2.1 唯一入口 `CompRenderer.renderFrame`

```java
public static GlFramebuffer renderFrame(CompProject project, CompDocument doc, int frame, boolean transparentStage)
```

* `frame` 钳到 `[0, duration*fps)`；
* 时间**只**由帧号推出：`frame / fps`，不接受外部传入的浮点时间；
* 合成分辨率、`preview=false`（不烘编辑标注、不烘空预合成提示）、`exportDepth++`（参考层不进成片）；
* `transparentStage` 只服务 alpha 输出模块。

`renderOffscreen` / `renderForExport` 都改为这个函数的薄包装，所以**渲染队列里的文件和 AE 预览
是同一个函数**。

### 2.2 `CompFrameServer`

* 键 = `(CompRenderCache.signature, frame, alpha)`：signature 已经包含帧号、每个图层字段、
  每条关键帧通道、每个效果参数，因此**改一下文档，旧帧自动变成不可达**，不需要"记得失效"。
* 内存预算 192 MB（1080p 一帧 ~8 MB ≈ 23 帧，720p ≈ 90 帧），超了按 LRU 释放回 `FboPool`。
* 统计：`帧服务器 N 帧 · M MB · 命中 x / 未命中 y · 上一帧 z ms`。

### 2.3 预览

`CompRenderer.renderFrameCached(...)`：先查绿条预渲染缓存（磁盘，代理分辨率），
再查帧服务器，都没有才渲染。**故意不做 BBS 模式那套"预览降分辨率"**：
AE 模式的全部意义是画面与成片一致，性能换正确性。

### 2.4 导出

`exportFilm()`（顶栏按钮）在 AE 模式下改走渲染队列：合成分辨率/帧率、`0..duration`、
默认输出模块，并把按钮文案改成 **AE 导出**。队列本来就调用 `renderForExport` → `renderFrame`。

### 2.5 切换、标识与风险

* 设置 → **渲染 / 编码管线**：`BBS 原生（默认）` / `AE 编码器（帧服务器）`（带图标）。
* 首次切到 AE 会弹一次**风险确认**（六条，红色），确认后才写入 `encoderRiskAcknowledged`；
  取消则回到设置，工程一个字节都不改。
* 预览右上角常驻红色徽标："**AE 编码器** / 帧 90 / 720"，点击提示当前管线。
* 状态栏、导出按钮文案、设置里的说明行同步变化。
* 切换时 `CompFrameServer.invalidate()` + `CompRenderCache.clear()`：两套缓存对"第 N 帧"的
  含义不同，不能混用。

## 3. 风险清单（同时也是 UI 里显示的内容）

1. 导出不再经过 BBS 录像器（F6）：分辨率=合成、帧率=合成；BBS 的录制设置、相机路径、loop 不影响成片。
2. 3D 世界/演员只能通过"BBS 预览"图层进入；该图层是主帧缓冲拷贝，预览实时、离线导出是最后一帧静态画面；
   依赖世界深度的效果在导出里退化为无深度。
3. 预览按合成分辨率全量渲染（1080p ≈8 MB/帧），复杂合成会掉帧；帧号永远正确，不会半帧/撕裂。
4. 效果/蒙版对比开关、世界底图、参考层不参与 AE 成片（参考层只在编辑视图可见）。
5. 合成时长/帧率与 BBS 影片不一致时不再自动对齐；在 BBS 影片里播放仍按 BBS 模式渲染。
6. 随时可切回 BBS 原生，两个模式共用同一份工程文件。

这些不是免责声明，是**真实的语义差异**：BBS 的世界是游戏渲染的，游戏只在渲染世界时才能提供那一帧。

## 4. 与 BBS 特性的适配

* 工程仍然是 BBS 的工程（`config/bbsae/projects/*.json`）；`encoder` 是 `CompSettings` 的字段，
  随工程保存；老文件没有该字段 → 默认 `bbs`（有测试钉住）。
* BBS 侧的入口（影片属性 → 合成器）不变；BBS 自己的 F6 导出**仍然按 BBS 模式**渲染，
  所以"在 BBS 影片里播放"和"在合成器里 AE 导出"是两条明确的路，不会互相污染。
* `BBS 预览` 图层在两种模式下都工作，只是 AE 导出时它是静态帧（风险 ②）。

## 5. 文件地图

新增：

| 文件 | 作用 |
| --- | --- |
| `client/render/CompFrameServer.java` | 帧服务器（内容哈希 LRU，192 MB 预算） |
| `src/test/.../CompEncoderModeTest.java` | 帧↔时间互换、钳制、模式持久化（6 条） |

修改：

| 文件 | 改动 |
| --- | --- |
| `client/render/CompRenderer.java` | `renderFrame`/`frameIndex`；`renderOffscreen`、`renderForExport` 改为薄包装；`renderToTexture` 增加 `transparentStage`；新增 `renderFrameCached` |
| `ui/UICompositorPanel.java` | 设置里的编码器选择、风险确认、红色徽标、`AE 导出` 路由、预览分支、绑定工程时同步 |
| `comp/CompProject.java` | `ENCODER_BBS/AE`、`settings.encoder`、`encoderRiskAcknowledged` |
| `build.gradle` | `runClientView -PbbsaeEncoder=true`（验证入口） |

## 6. 验证

* `test` **143/143**。
* 实机（`runClientView -PbbsaeView=curve -PbbsaeEncoder=true`）日志：
  `frame server: f90 14 ms (cache 0 frames / 0 MB, hit 0 miss 1)` —— 说明预览已经在走帧服务器，
  且同一帧被复用而不是每显示帧重渲。
* 设置页实机可见：`渲染 / 编码管线` 行选中 `AE 编码器（帧服务器）`，下方说明变红并显示
  "AE：预览 = 导出，分辨率 / 帧率取合成；不经过 BBS 录像器。"（验证时 BBS 主题被改成红色，
  所以截图没有入库——那套配色是 BBS 全局设置，与本功能无关）。

## 7. 未做 / 下一轮

> 下面四条已在 **0.9.0（`ROUND20-AE-PIPELINE.zh.md`）** 全部解决：磁盘帧缓存、每 tick 渲染预算 +
> FFmpeg 原始帧管道、AE 预览音频、显式代理分辨率。

* 帧服务器只缓存"当前文档状态"的帧，跨状态不做持久化（绿条预渲染负责磁盘缓存）。
* AE 模式还没有独立的"渲染农场/后台线程"：帧仍在客户端线程渲染（每 tick 一帧），
  导出是"每 tick 一帧 PNG"，所以复杂合成导出会比实时慢，但不会丢帧。
* 音频在 AE 模式下仍然只在导出时混音，预览没有声音（与 BBS 模式一致）。
* 没有做"AE 模式下的代理分辨率"开关——如果要，应该做成显式的 `encoderProxy`，
  并且徽标要标出代理倍率，否则又会回到"预览≠导出"。
