# Round 97 — 外链压缩音频改 raw PCM 解码

> 版本 `0.99-hotfix61`。修复 hotfix60 中只有真实 FFmpeg 才会暴露的 bug。

## 根因

hotfix60 用：

```text
ffmpeg -i source -f wav -acodec pcm_s16le -ar 48000 -ac 2 -
```

向 stdout 输出 WAV。stdout 不可 seek，FFmpeg 把 RIFF/data 长度写成 `0xFFFFFFFF`：

- `WaveReader` 执行 `new byte[chunk.size]` 时长度为负而抛异常；
- `parseWav` 对 `size < 0` 直接返回 null；
- 结果：外链 MP3/FLAC/M4A 在导入时探测不到时长，预览/导出也没有声音。

## 修复

```text
ffmpeg -i source -vn -f s16le -acodec pcm_s16le -ar 48000 -ac 2 -
```

- stdout 是纯 PCM，没有容器头；
- 直接构造 `Wave(1, 2, 48000, 192000, 4, 16, pcm)`；
- 丢弃末尾不足一个 L/R 帧的字节；
- 仍不写工程文件、不写临时 WAV；`CompAudio.path` 保持 `external:`。

## 验证

- 全量测试：**165 套件 / 1007 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端；真机需要验证 MP3/FLAC/M4A/AAC/OPUS/WMA/AIFF 外链的：
  1. 导入时长；
  2. 时间轴波形/长度；
  3. 预览音频；
  4. 导出混音。
- 候选包：`bbsae-0.99-hotfix61.jar`（3,958,194 bytes，SHA-256
  `309001A21E0586FC20E2641FDE70E909616EC6706D933AC2648FF8A8813CB57C`）。
