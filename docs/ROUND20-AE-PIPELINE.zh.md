# Round 20 — AE 管线的四块短板：磁盘帧、渲染预算、预览音频、代理分辨率（0.9.0）

> 承接 `0.8.0` 的"未做/下一轮"清单，四条全部落地：

| 0.8.0 的限制 | 0.9.0 |
| --- | --- |
| 帧服务器只缓存当前文档状态的帧，跨状态不持久化 | `CompFrameDisk`：按**内容签名**命名的磁盘 L2，跨编辑、跨重启有效 |
| 没有"渲染农场/后台线程"，每 tick 只渲染一帧，PNG 每帧落盘 | 每 tick 22 ms / 最多 6 帧；导出走 FFmpeg **原始帧管道**，不再 PNG |
| AE 预览没有声音 | `CompAudioPlayer`：混音在 worker 线程，播放走 BBS 的 OpenAL |
| 没有代理分辨率 | `encoderProxy` 1/1、3/4、1/2、1/4，徽标标出倍率，导出永远 1/1 |

产物：`bbsae-0.9.0.jar`（609 542 字节，SHA-256
`f9a4907e03b90295cab4728920ca13206cba3a911f03e932536c0fae8a13cefd`），测试 156/156。

---

## 1. 磁盘帧缓存：为什么"内容签名"就是文件名

0.8.0 的 RAM 缓存键是文档指纹，改一个值再改回来，旧帧已经不可达；关掉工程明天再开，全部重渲。
重合成（4K、十几个效果）单帧几十毫秒，这正是最该留下的东西。

第一性原理：**一帧的身份 = (文档内容, 帧号, 是否 alpha, 光栅尺寸)**。把身份写进文件名，
"失效"这个问题就不存在了——内容变了就是另一个文件，旧文件永远不会被错误命中：

```
config/bbsae/cache/frames/<project>/<comp>/<signature-hex>_<frame>_<w>x<h>.rgba
```

* 存放**原始 BGRA int**（就是 FBO 读回的原样），读取 = 一次文件读 + 一次纹理上传，
  中间没有 PNG 编解码（1080p 的 ImageIO 比简单合成还慢，会把这个缓存的意义抵消掉）。
* 只写**渲染超过 45 ms** 的帧：3 ms 的帧写盘比重算更贵。
* 512 MB 上限，按 mtime 淘汰；删到 80% 再停，避免每写一帧都扫目录。
* 读到的文件头/尺寸/帧号不匹配 → 删掉当 miss，绝不会解码出一张"看起来对但内容错"的帧。
* 换向：文件是 top-down（`readTopDown()` 的产物，也是 PNG/FFmpeg 要的顺序），
  FBO 纹理是 bottom-up，载入时按行翻转一次，这个规则只写在 `CompFrameServer.fromDisk` 一处。

设置页有"清理帧缓存"（磁盘 + 显存），不影响工程与素材。

## 2. 渲染农场：为什么不是线程，而是预算 + 管道

**GL 上下文属于渲染线程**。把 `glBindFramebuffer/glDraw*` 丢到后台线程，要么共享上下文
（Minecraft 自己都不这么做，状态会互踩），要么在后台线程里重建一套资源（材质/着色器全部失效）。
所以"后台渲染"在这个宿主里只能是两件事：

### 2.1 每 tick 的渲染预算

```java
FRAME_BUDGET_MS = 22      // 50 ms 的 tick 里给导出 22 ms
MAX_FRAMES_PER_TICK = 6   // 3 ms 的帧也不能一口气吃满 30 帧
```

旧实现是"每 tick 恰好一帧"，于是导出的墙钟速度被钉死在 20 fps：12 秒 60 fps 的合成，
即使每帧只要 3 ms，也要 36 秒才写完。现在按预算循环，直到用完时间片或帧数上限——
快的合成几秒就导出完，慢的合成也不会把客户端卡死。

### 2.2 原始帧管道（真正的提速来源）

PNG 才是导出的瓶颈：1080p 编码 30–60 ms + 落盘，比合成本身贵一个数量级。全尺寸视频模块改成

```
ffmpeg -f rawvideo -pixel_format rgba -video_size WxH -framerate fps -i pipe:0 ...
```

* `GlFramebuffer.readTopDownRgba()`：`readRawBytes()` + 按行翻转（1000 次 `arraycopy`，
  不是 200 万次带边界检查的逐像素读），通道顺序本来就是 RGBA，不换。
* 音频仍是文件（input 1）：混音在帧之前一次性完成，手工交织两条流没有意义。
* 结束 = 关闭 stdin；`pollEncoder` 等进程退出。
* **只有"输出尺寸 == 合成分辨率"才走管道**：管道是原样搬运，缩放预设需要重采样，
  偷偷缩放会破坏"预览 = 导出"，所以缩放预设继续走 PNG + 双线性；PNG 序列、保留帧同样走旧路径
  （`CompExportManager.usesPipe`，有 4 条测试钉住）。

## 3. 预览音频

* 播放的是**合成混音**（`CompAudioMixer.mix(0, duration)`），不是"某个 wav 文件"；
* 混音在 `bbsae-preview-audio` 线程做（解码 + 混 12 秒 ≈ 几十毫秒，不能卡编辑器），
  完成后在客户端线程建 `SoundBuffer`（BBS 的 OpenAL，和游戏同一个设备）；
* **播放头是权威**：`sync(time)` 每帧比较 `SoundPlayer.getPlaybackPosition()`，
  漂移 > 250 ms 或循环回卷才 seek，小漂移不动（否则会一直"哒哒"）；
* 只有一个入口 `syncPreviewAudio()`：`(playing, AE 模式, 有音频, 预览音频开关)` 推导，
  所以任何把 `playing=false` 的代码都不需要记得停音频；
* 编辑音频片段（导入/删除/改参数）→ `invalidate()`，下一次播放重新混音；
* 设置页新增"预览音频"开关；BBS 模式下不播放（那里 BBS 自己的影片音频在响，叠加会双重出声）。

## 4. 代理分辨率

* `settings.encoderProxy`：1、0.75、0.5、0.25，设置页与管线选择同一行。
* 帧服务器按 `(签名, 帧号, alpha, 代理百分比)` 做键，代理帧和全尺寸帧**不会互相命中**。
* 徽标变成 `AE 编码器 · 代理 1/2` + `帧 90 / 720`——倍率必须看得见，否则就是
  "预览糊、导出锐"的 bug 报告。
* **导出永远是 1/1**：管道只接受全尺寸，缩放预设另有路径；代理只影响预览花多少像素。

## 5. 文件地图

新增：

| 文件 | 作用 |
| --- | --- |
| `client/render/CompFrameDisk.java` | 内容寻址的磁盘帧缓存（512 MB，mtime LRU） |
| `client/audio/CompAudioPlayer.java` | 预览音频（worker 混音 + BBS OpenAL 播放） |
| `src/test/.../CompFrameDiskTest.java` | 往返、错签名、错帧号/尺寸、损坏文件、命名（6 条） |
| `src/test/.../CompExportPipeTest.java` | 管道适用条件（4 条） |

修改：

| 文件 | 改动 |
| --- | --- |
| `client/render/CompFrameServer.java` | 磁盘 L2、代理尺寸、统计（hit/miss/disk） |
| `client/render/CompRenderer.java` | `renderFrame(..., scale)`、`renderFrameCached(..., proxy)` |
| `client/render/GlFramebuffer.java` | `readTopDownRgba()`（按行翻转，无通道交换） |
| `client/export/CompExportManager.java` | 每 tick 预算、原始帧管道、`usesPipe` 判定 |
| `client/export/CompAudioMixer.java` | `toWave()`（内存 Wave 给预览用） |
| `client/ui/UICompositorPanel.java` | 代理选择、清理缓存、预览音频开关、`syncPreviewAudio`、徽标倍率 |
| `comp/CompProject.java` | `encoderProxy`、`previewAudio` |

## 6. 验证

* `test` **156/156**（本轮新增 13 条：磁盘缓存 6、管道 4、WAV 兜底 2、代理默认值 1）。
* **AE 导出（管道）实机验证**：`runClientView -PbbsaeView=curve -PbbsaeEncoder=true -PbbsaeExport=true`
  → `config/bbsae/exports/dev-effects/main_ae/main_ae.mp4`，ffmpeg.log 里
  `Input #0, rawvideo, from 'pipe:0' … rgba, 1920x1080, 60 tbr` + `frame=720`，
  即 720 帧直接从管道进来，没有一张 PNG 落盘。

* **代理分辨率实机验证**：`-PbbsaeProxy=0.5`
  → 日志 `frame server: f11 @50% 3 ms (cache 64 frames / 126 MB, hit 190 miss 64 disk 0)`：
  50% 光栅、RAM 命中远多于未命中（播放循环）；
  徽标实测为 **`AE 编码器 · 代理 1/2` / `帧 141 / 720`**。

* **预览音频**：`-PbbsaeAudio=audio/tone.wav -PbbsaePlay=true` 实机跑通，
  日志 `preview audio ready: N samples (预览音频 0.00s 播放中) files=1`。
  顺带修了一个真实问题：BBS 的 `WaveReader` 对"data 块之后没有终止块"的 WAV（python `wave`
  模块导出的就是这种）会抛 `EOFException`，导致整条音频被静默跳过。现在 `readWave` 捕获后
  用自己的最小 RIFF 解析兜底（PCM 8/16/24/32 + float，`aWavWithoutATrailingChunkStillMixes` 钉住）。

* 磁盘 L2 只在单帧 ≥45 ms 时写入，实机模型很轻（2–4 ms），所以验证走的是单元测试
  （往返、错签名、错帧号/尺寸、损坏文件、命名）。

## 7. 仍然没做（下一轮候选）

* 管道模式只有 libx264 走快速参数（未显式加 `-preset`），慢速编码仍会拖住渲染线程的写入；
  真要并行得做有界队列 + 写线程（GL 还是只能在渲染线程）。
* 磁盘帧缓存没有分工程的总量配额（只有全局 512 MB），多工程时先删别人的旧帧。
* 预览音频没有波形/电平表，也没有"跟随 BBS 影片音频"的混音总线。
