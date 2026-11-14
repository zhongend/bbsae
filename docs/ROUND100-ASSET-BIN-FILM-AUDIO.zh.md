# Round 100 — 音频播放、波形、素材箱与「影视」检查器

> 版本 `0.99-hotfix64`。本轮没有启动客户端截图（遵循项目约定：减少反复开客户端），
> 结论来自代码路径、headless 混音/波形测试、素材索引测试与全量回归。

## 1. 第一性原理：音频为什么没有声音

声音不是「文件被导入」这个事实，而是 **OpenAL 源被要求播放一段已经混好的采样**。这条链上
任何一环缺失都会表现成同一件事：导入成功、时间轴有声轨、但没有任何声音。

1. **触发条件**：`syncPreviewAudio()` 的 `enabled` 包含 `usesAeEncoder()`。默认工程是
   BBS 编码器，因此 `CompAudioPlayer.play()` 从未被调用。预览画面在走，音频轨存在，
   但没人要求播放。
2. **解码**：`toSource()` 对非 16-bit 源调用 BBS 的 `Wave.convertTo16()`。它对 8-bit
   PCM 会从 1 字节缓冲读 4 字节（`BufferUnderflow`），对 32-bit 又按整数处理。结果：
   合法的 8-bit WAV 被当成静音。
3. **重建**：改音量/音调会 `invalidate()` 释放缓冲区，但面板 `previewAudioRunning`
   仍为 true；后续帧只对 null player 调 `sync()`，不会重新混音。
4. **线程**：导入时复制、转码、整文件探测都在渲染线程，长音频会卡住点击处理数秒。
5. **登记**：外链视频没有写入 `externalAssets`，素材箱看不到，用户以为导入失败。

修复后的不变量：

```text
playing && previewAudio && hasAudio && rate == 1
        │
        ▼
CompAudioPlayer.play / sync / tick   (client thread, OpenAL)
        │  worker: CompAudioMixer.mix(doc, projectResolver)
        ▼
SoundBuffer(Wave 48k stereo) → SoundPlayer(relative) → 播放
```

`CompAudio.pitch / speed / pan / sourceDuration` 都进入同一个 `mix()`，所以预览、WAV
导出、AE 视频导出的声音一致。

## 2. 波形图

旧实现的两个错误：

1. **窗口错**：对整文件求峰值，时间轴把它横向拉满整条 bar。裁剪到中段时显示的是前奏。
2. **重复解码**：缓存键包含 bucket 数，缩放一次就重新解码整文件，且在渲染线程。

新实现：

- `CompAudioMixer.peakEnvelope(file, 8192)`：每个文件只解码一次，得到整文件包络；
- `CompAudioWaveform.peaks(file, offset, sourceLength, buckets)`：按片段窗口切包络；
- 时间轴按 `clip.length × speed` 求源窗口，循环片段重复同一段；
- 8/16/24/32-bit PCM 与 32-bit float 统一解析；
- 后台线程加载；失败按 `path+mtime+size` 记住，不每帧重试。

## 3. 「影视」页

`PAGE_FILM = 7`，检查器第 3 个标签。它回答「导入之后在哪里编辑」：

| 选中 | 内容 |
| --- | --- |
| 音频片段 | 音量、音调（半音）、速度、声像、淡入/淡出、循环、静音、入点/偏移/时长、试听、重新链接、移除 |
| 图片/视频/帧序列轨道 | 源/缺失状态、尺寸、适配、不透明度、视频速度、冻结、时间重映射、提取视频音频 |

选择音频片段会从原来的「合成」页改为自动切到「影视」页；旧代码跳到合成页，而那里没有
任何单条音频参数。

## 4. 素材箱

Premiere 项目面板的核心不是「文件列表」，而是 **身份 + 归属 + 拖放语义**：

- 身份：稳定 token（`images/a.png` / `external:C:/...`），用于图层/音频/绑定；
- 归属：`mediaBinAssignments` 只记录「在哪个箱子」，删箱不删素材；
- 拖放：
  - 空白轨道 → 按鼠标时间/行新建片段；
  - 已有图片/视频/序列轨道 → 覆盖素材，保留变换、效果、蒙版、关键帧；
  - 音频 → 音频轨；
  - 素材箱行 → 归档。

`CompMediaLibrary` 扫描 `assets` 与 `externalAssets`：
- `frames/<clip>/frame_0000.png` 聚合成一个 SEQUENCE；
- 图片/音频/视频按扩展名分类；
- 外链缺失保留为 offline。

`UIProjectBin` 是左侧「素材箱」页：顶栏导入/新建箱/旧素材库/刷新，箱列表、网格、搜索、
滚动条；双击插入；拖放解析延后到帧后执行，避免渲染中改子元素。

## 5. 导入/外链路径

`CompStore.resolveAsset()` 统一解析：

1. `external:` → 绝对路径；
2. 绝对路径（含 Windows 盘符）；
3. 项目根相对；
4. `assets/` 前缀去掉后相对 assets 根；
5. 资源 id（`mc:`/`bbs:`）返回 null，由纹理缓存处理。

`CompStore.isResourcePath()` 明确排除 Windows 盘符和 `external:`。`externalToken()`
规范化为 `external:<normalized absolute>`，登记时去重。

同名导入不再覆盖：`copyIntoProjectFile()` 生成 `name_2.png` 等唯一名。

## 6. 验证

- `gradle test`：**171 套件 / 1033 项 / 0 failures / 0 errors / 1 skipped**。
- 新增/更新的 headless 测试：
  `CompAudioTest`（速度/音调/时长语义）、`CompAudioMixerAdvancedTest`（速度不失真、
  音调保时长、声像、8-bit WAV、WAV 头探测）、`CompAudioWaveformTest`（按源窗口切片）、
  `CompMediaLibraryTest`（序列聚合/素材箱/删箱保留素材）、`CompStorePathTest`
  （盘符/外链/旧 `assets/` 前缀）。

## 7. 已知边界

- BBS 原生编码器（F6）的成片音频仍由 BBS 录像器控制，不混入合成音轨；「影视」页
  已明确提示，需要成片音频请切 AE 编码器或导出 WAV。
- 视频音轨需要在「影视」页点「提取音频到音轨」，不会自动同步视频速度到音频片段。
- 波形包络为 8192 段；极长文件在最大缩放下的细节有限。
