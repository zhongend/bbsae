# Round 121：封面、合成器声音复检、3D 文字字距边界

> 版本 `0.99-hotfix88`。本轮先复检 hotfix87 的合成器音频，再按“数据从哪里来”排查新默认字距
> 对 3D 文字光栅的影响；封面同时换成了提供的图片，并补上格式测试。

## 1. 合成器声音复检

hotfix87 的修复仍然有效：

- `BbsAeClient.isCompositorActive()` 让 `CompFilmAudio` 在合成器页面不碰 `CompAudioPlayer`；
- `CompAudioPlayer.sync()` 在 `wanted` 被外部清掉时恢复播放意图。

用 final 代码重新跑真实客户端（`-PbbsaeView=curve -PbbsaeEncoder=true -PbbsaePlay=true`，
播放外部测试音）：

```text
preview audio ready: 1152000 samples ...
preview audio: playing=true status='播放中 · 3.64s' time=3.775224
preview audio: playing=true status='播放中 · 5.28s' time=5.419614
preview audio: playing=true status='播放中 · 6.74s' time=6.884364
```

`playing=true` 来自 `CompAudioPlayer.isPlaying()`，不是“日志说开始混音”。截图
`run/screenshots/round121-audio.png`。

## 2. 3D 文字被字距 9 裁切：边界估算漏了 tracking

### 根因

3D 文字不是直接画进合成尺寸的：`CompLayerSourceCache.textRect()` 先估算一个源纹理矩形，
再在 `LayerContentRenderer.drawText()` 里按这个矩形画。旧估算：

```java
estimate += (isWideCodePoint(codePoint) ? 0.98F : 0.62F) * fontSize;
```

只累加字形宽度，**没有把 `tracking * (字形数 - 1)` 算进去**。默认字距从 0 改为 9 后，长行
3D 文字比光栅宽，左右两侧被裁掉；2D 文字直接画在合成目标上，不受影响，所以这是一个只在
3D/导出源纹理路径上出现的问题。

### 修复

- `estimatedTextWidth(text, fontSize, stroke, tracking)`：按 `\n` 分行，每行在每个字形之间
  加一次 `max(0, tracking)`，最后取**最宽的一行**；负字距不缩小估算（宁可有透明边，不可裁字）。
- `textRect()` 传入 `layer.trackingAt(frame)`，与真实布局的 tracking 同源。
- 保留原三参数重载（tracking = 0），旧测试和 2D 估算不受影响。
- 新增 `TextLayerBoundsTest` 用例：正字距、多行、负字距。

## 3. 封面

- 提供的图片保存为真正的 PNG：`docs/gallery/cover.png`（1024×1024），并放在 README 顶部；
- `src/main/resources/assets/bbsae/icon.png` 由同一图缩放到 **128×128**，Mod Menu/启动器
  读取 `fabric.mod.json` 的 `icon` 字段，现在显示新封面而不是旧的播放按钮；
- `IconResourceTest.galleryCoverIsARealSquarePng` 检查文件的实际容器格式是 PNG（不是改后缀
  的 JPEG）、尺寸不小于 512 且为正方形。

## 验证

客户端音频日志见上；覆盖测试和全量测试在最终构建报告中给出。构建产物：
`bbsae-0.99-hotfix88.jar`。
