# Round 96 — 真正外链与更多音频格式

> 版本 `0.99-hotfix60`。未启动客户端。

## 1. 项目级外链索引

新增 `CompProject.CompSettings.externalAssets: List<String>`：

- 值形如 `external:C:/Media/a.png`，仅记录绝对路径；
- 图片导入在 `linkExternalAssets` 开启时只登记，不复制；
- 素材库通过 `UIAssetBrowser.externalAssets(Supplier<List<String>>)` 列出这些外链图片；
- 缩略图继续由 `ImageTextureCache` 读取 `external:` 路径。

## 2. 图片/视频导入

- `importLibraryFile`：链接模式不再 `copyIntoProjectImages`，改为登记外链并选中素材库条目；
- `importDroppedImage` / `importFileIntoLayer` / `linkExternalImage`：链接模式写
  `external:<abs>`，登记外链；
- `importVideoLink`：视频帧按需从源文件提取，缓存写入 `config/bbsae/cache/video`，不在工程
  assets 里复制源视频；
- 关闭「链接外部素材」时仍保留原有复制/转码回退。

## 3. 更多音频格式

`CompAudioMixer.readWave`：

- WAV/OGG 原生解码不变；
- 其它格式调用 FFmpeg：`-i <source> -f wav -acodec pcm_s16le -ar 48000 -ac 2 -`，从 stdout
  读 WAV 字节，在内存中解析；不写工程文件、不产生临时 assets；
- MP3/FLAC/M4A/AAC/OPUS/WMA/AIFF/AIF 都走同一条路；
- `addAudioClip` 在链接模式下对所有这些扩展名写 `external:`，不再转换复制；
- 音频选择器、拖放、素材库按钮的扩展名列表同步。

## 4. 已知边界

- 压缩格式依赖 FFmpeg；缺失时无法解码/探测时长；
- 外链视频帧缓存位于 `config/bbsae/cache/video`，不是工程素材副本；源文件移动后需重新链接；
- 音频外部链接在素材库中暂不单独列出（音频剪辑直接进时间轴），图片外链会列出；
- 未启动客户端。

## 5. 验证

- 全量测试：**165 套件 / 1007 项 / 0 failures / 0 errors / 1 skipped**。
- 建议真机验证：
  1. 素材库导入图片后 `assets` 下无新文件，项目 JSON 出现 externalAssets；
  2. 拖入 png/mp4/wav/mp3/flac 不复制；
  3. MP3/FLAC 预览与导出音频；
  4. 源文件移动后缺失提示；
  5. BBS 原版拖入在 bbsae 外仍正常。
- 候选包：`bbsae-0.99-hotfix60.jar`（3,958,154 bytes，SHA-256
  `30DAA3C469A995A99197014E2C180C266161A25412A491B021C4A87963CBCFD3`）。
