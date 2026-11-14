# Round 99 — 素材库试听、拖放落点动效、外链排查

> 版本 `0.99-hotfix63`。未启动客户端。

## 1. 素材库音频预览

新增 `client/audio/CompAudioPreview.java`：

- 解码复用 `CompAudioMixer.readWave`，和合成/导出完全同一套：
  - WAV/OGG 原生；
  - MP3/FLAC/M4A/AAC/OPUS/WMA/AIFF 走 FFmpeg raw PCM；
- `SoundBuffer + SoundPlayer` 播放，第二次点同一素材调用 `stop()`。

`UIAssetBrowser`：

- 新增「试听」按钮；外链音频和 BBS 音效都可试听；
- 项目分类新增外链音频条目（音效图标，`externalAudio=true`）；
- 双击外链音频 / 「加入音轨」插入时间轴；「试听」只试听；
- BBS 音效分类的「加入音轨」和「试听」分离，修复标签与实际动作不一致；
- 打开素材库时把旧工程 `layer.imagePath`/`clip.path` 的 `external:` 路径迁移进
  `CompSettings.externalAssets`。

## 2. 拖放落点动效

`UICompTrackTimeline`：

```java
showDropTarget(row, time, audio)
renderDropHint(context)
```

- 0.95 秒呼吸高亮，透明度随 progress 衰减；
- 目标行用蓝/绿填充和 outline；
- 入点 X 画 3px 插入线；
- 纯音频落点高亮音频轨，图片/视频高亮鼠标所在轨道。

限制：GLFW/原版 Screen 只有 drop 回调，没有 drag-over，因此这是“松手落点确认”，
不是拖动中的悬停预览。

## 3. 排查并修复的外链 bug

1. `externalAssets()` 设置后素材库已经 rebuild 过一次，外链条目要等下次刷新才出现；
   现在设置后立即 `refresh()`。
2. `addAudioClip` 外链后素材库不刷新；现在非空时刷新。
3. OS 拖放导入后素材库不刷新；现在刷新。
4. 扩展名判断大小写不一致；抽出 `isImageFile/isAudioFile` 并测试。
5. BBS 文件拖入可能误吞非 bbsae Screen；增加当前 Screen 与 dashboard 当前面板校验。

## 4. 验证

- 全量测试：**166 套件 / 1009 项 / 0 failures / 0 errors / 1 skipped**。
- 新增 2 个 `UIAssetBrowserAssetTypeTest` 用例。
- 未启动客户端；真机重点：
  1. 外链音频出现在项目分类、试听/停止/切换；
  2. MP3/FLAC 试听与导出；
  3. OS 拖入后轨道高亮和插入线；
  4. 旧工程外链路径迁移。
- 候选包：`bbsae-0.99-hotfix63.jar`（3,962,646 bytes，SHA-256
  `41BE9BD470DC217E85E0B6411FD90D8E0CD73FAD7E2AF25BC1B67C74089DCC8B`）。
