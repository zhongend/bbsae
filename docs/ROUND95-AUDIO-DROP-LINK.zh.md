# Round 95 — 音频入口、OS 拖入、链接素材、轨道落点

> 版本 `0.99-hotfix59`。未启动客户端；验证来自代码路径与全量测试。

## 1. 音频导入入口

- 左栏「素材」页新增「导入音频…」按钮，调用现有 `importAudioFile()`（OS 文件对话框 +
  游戏内浏览器回退）。
- 支持 WAV/OGG/MP3/FLAC/M4A；WAV/OGG 在 `linkExternalAssets` 开启时写 `external:`，
  其它格式经 FFmpeg 转 WAV。
- 时间轴音频轨的 `clip.start` 现在接受外部传入的落点时间。

## 2. 外部窗口拖入

### 根因

`CompFileDrop.install` 在 `BBSClientReadyEvent` 期间调用，但 `MinecraftClient.getWindow()` 仍是
null；方法直接 return，`installed` 保持 false，之后没有重试。GLFW 的 drop 回调从未装上，所以
从资源管理器拖入没有反应。

### 修复

- 不再使用自定义 GLFW drop 回调（它还会抢占 vanilla 的回调）；
- 走原版 `Screen.filesDragged` → `UIScreen.acceptFilePaths`；
- 新增 `UIScreenFileDropMixin`：
  - `acceptFilePaths` HEAD 调用 `BbsAeClient.acceptDroppedFilePaths(paths)`；
  - bbsae 合成器可见并消费时 `cancel()`，BBS importer 不执行；
  - 其它 BBS 界面原样执行。
- 删除 `CompFileDrop.install` 调用，避免重复导入。

## 3. 链接素材

- 图片拖入/导入到图层：`imageImportPath()` 在 `CompPrefs.linkExternalAssets` 开启时返回
  `external:<绝对路径>`；关闭时复制到 `assets/images`。
- 视频拖入：链接模式调用 `importVideoLink()`，按需拆帧，不复制源文件。
- 音频：WAV/OGG 直接外链；压缩格式转 WAV（BBS 播放器只支持 WAV/OGG）。
- 设置说明改为“图片/视频/WAV/OGG 直接引用原文件”。

## 4. 拖到目标轨道

`UICompTrackTimeline` 新增：

```java
containsScreen(x,y)
timeAtScreen(x)
rowAtScreen(y)
isAudioRowAt(y)
layerAtScreen(y)
```

`UICompositorPanel.handleDroppedFiles(files,x,y)`：

- 鼠标在时间轴内：`dropTime = timeAtScreen(x)`，`dropRow = rowAtScreen(y)`；
- 图片/视频层创建后 `moveLayerToDisplayIndex(layer, dropRow)`；
- 音频 `clip.start = dropTime`；
- 鼠标不在时间轴内：回退播放头/合成中心。

## 5. 验证

- 全量测试：**165 套件 / 1007 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端；请验证：
  1. 左栏「导入音频…」；
  2. 资源管理器拖入 bbsae 窗口；
  3. 拖到时间轴不同轨道/不同 X；
  4. bbsae 内 BBS importer 被屏蔽，其它 BBS 页面不受影响；
  5. 外链文件移动/删除后的解析状态。
- 候选包：`bbsae-0.99-hotfix59.jar`（3,956,151 bytes，SHA-256
  `F749193E113E0DAB84A8CAB7EC4664CAA5A2534492880FAD158B38B44D5C9296`）。
