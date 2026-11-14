# Round 106 — 图层副本错位、Ctrl+C/V、项目时长硬上限

> 版本 `0.99-hotfix70`。未启动客户端截图；模型/纯函数测试 + 全量回归验证。

## 1. 图层副本与原图层错位

### 根因

`CompLayerOps.duplicate` / `UICompositorPanel.duplicateLayer` 复制后调用：

```java
offsetCopy(copy, 24F, 24F);
```

它只改 `copy.x/y` 和 X/Y 轴关键帧。问题是：

- 父级图层：局部 +24px 经过父级旋转/缩放后不是屏幕上的 24px；
- 3D 图层：还需要 z/旋转/透视；
- 非 X/Y 轴（旋转、缩放、遮罩）没有同步偏移；
- 已有关键帧时基字段被忽略，偏移只落在 X/Y 曲线。

用户看到的就是“副本和原图层错位”，而不是有意的可见偏移。

### 修复

- 删除 Ctrl+D/复制按钮里的 `offsetCopy`：副本**原位**生成，与原图层严格重合。
- 右键/快捷键复制后选中副本，状态栏显示“原位，可拖动”。
- `offsetCopy` 保留为显式调用的兼容 API，不再被复制路径隐式使用。

## 2. Ctrl+C / Ctrl+V 失效

### 根因

`CompKeybinds` 只有 `layer.duplicate`，没有 `layer.copy` / `layer.paste`；
`performShortcut` 也没有对应分支，所以按键根本没有动作。

### 修复

- 新增快捷键动作：
  - `layer.copy` = Ctrl+C；
  - `layer.paste` = Ctrl+V；
  - 两种快捷键方案默认都绑定，设置页可改。
- 新增图层剪贴板：
  - Ctrl+C 复制所选图层的**完整快照**（`duplicate()`，不修改文档、不产生撤销点）；
  - Ctrl+V 通过 `CompLayerOps.paste` 原位插入独立副本，重新分配 id 和名称；
  - 粘贴时重映射选中图层之间的父子/轨道遮罩引用；指向外部仍存在的图层保持引用，
    指向已不存在的图层清空；
  - 重复 Ctrl+V 每次都是新的独立副本；
  - 剪贴板记录工程 id，切换工程后不会把旧图层粘进来。
- 右键轨道菜单同时提供复制/粘贴/原位复制。
- 粘贴调用 `doc.extendDurationToContent()`，长于旧时长的片段不会被截断。

## 3. 项目时长 12 秒硬上限

### 根因

`CompDocument.duration` 默认 12 秒，许多路径把它当作不可越过的右墙：

- 时间轴拖动：`clamp(..., 0, duration - length)`；
- 裁剪出点：`clamp(..., in, duration)`；
- 音频移动/裁剪：同样夹到 `duration`；
- 音频导入：`clip.start = min(doc.duration, start)`；
- 图层移动/裁剪模型方法自身也用 `compDuration` 兜底。

### 修复

- `CompDocument.contentEnd()`：所有图层 `in/out`（包括音轨图层）与所有音频片段
  `start + length` 的最大值。
- `CompDocument.extendDurationToContent()` / `setDurationAtLeast()`：
  - 只增长，不自动缩短；
  - 工作区出点若仍是旧时长默认值则跟随；用户显式改短的工作区保持。
- `CompLayer.moveTo` / `trimOutTo` 去掉右侧时长上限，只保留“不能早于 0”的约束。
- `UICompTrackTimeline`：
  - 单选/多选移动不再限制右边界；
  - 裁剪出点不再限制右边界；
  - 音频移动/裁剪不再限制右边界；
  - 内容增长时 `ensureViewCovers()` 扩展 X 轴可视范围，鼠标可以继续向右拖。
- 新图层、序列帧导入、视频导入、音频导入、音频 worker 探测完成后都调用扩展。
- `CompStore.hydrate()` 加载旧工程时自动扩展；`contentDurationChanged()` 同步
  BBS 循环范围和影片 `CompositionClip`。
- `tickContentDuration()` 在不按住鼠标时兜底检测内容超出。
- `commitLayerTiming()` / `nudgeSelected()` / `onTrackEdited()` 都触发同步。

## 4. 验证

- `CompDocumentDurationTest`：最后图层、最后音频、拖动/裁剪超限、不自动缩短、
  `addLayer` 自动扩展。
- `CompLayerOpsTest`：复制原位、粘贴重映射父子关系。
- `CompKeybindTest`：Ctrl+C/V 在两种方案中都有默认绑定。
- 全量测试：**176 套件 / 1071 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端截图；快捷键/拖动/音频导入建议实机抽检。

## 5. 建议实机检查

1. Ctrl+D 复制一个带父级/3D 的图层，确认副本与原图层完全重合；
2. Ctrl+C/Ctrl+V 复制粘贴，确认新图层出现、父子关系正确、重复粘贴独立；
3. 把视觉图层拖到 12 秒以后，确认时间轴继续延伸；
4. 裁剪出点到 20 秒以后；
5. 导入 30 秒音频，确认项目时长/工作区/时间轴自动变长；
6. 加载一个旧工程（内容超过 12 秒），确认打开后时长自动覆盖内容；
7. 查看 BBS 影片的 CompositionClip 是否同步到新时长。
