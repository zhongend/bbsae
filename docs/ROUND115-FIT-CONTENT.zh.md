# Round 115 — 适配以内容末尾为基准（轨道 + 关键帧抽屉）

> 版本 `0.99-hotfix80`。全量：**191 套件 / 1174 项 / 0 failures / 0 errors / 1 skipped**。

## 现象

「适配全长」后，时间轴/关键帧抽屉没有真正贴合用户正在编辑的内容：

- 合成画布可能有很长的空尾（旧版本甚至继承过 30 分钟的影片占位），适配后最后一条图层只占
  最左边几像素；
- 关键帧抽屉同样按画布时长适配，打开时看不到开头或只能看到一段被压扁的曲线；
- 两个视图应显示同一个区间，但轨道和抽屉各自按画布时长缩放。

## 根因

`UICompTrackTimeline.fit()` / `ensureView()` 和关键帧抽屉的五个适配入口都调用
`getDurationTicks()` / `doc.duration`。画布时长是编辑状态（可以留空尾、可以比内容长），不是
“内容末尾”。因此适配范围是 `[0, 画布时长]`，而不是用户要的 `[0, 最后一个图层/音频末尾]`。
关键帧抽屉另有 `applyState(selection)` 在重置后恢复旧的 `x_min/x_max`，会覆盖刚做的适配。

## 修复

### 1. 统一的内容末尾

`CompDocument.fitEnd()`：

- 取最后一个**可见内容层**的 `out`（忽略 `camera` 和 `null` 层，它们常被拉到整段画布）；
- 纳入最后一条音频的结束时间；
- 没有图层/音频时回退到画布时长，避免空工程被适配成 0.1 秒。

### 2. 轨道时间轴

- 新增 `UICompTrackTimeline.fitDuration()` → `doc.fitEnd()`。
- `fit()`、`ensureView()`、视图恢复都使用它；适配始终从 0 开始。
- `fitMode` 下内容末尾变化（图层拖动/裁剪/新增）会重新适配；用户手动缩放后不打扰。
- 工具栏「适配内容末尾」和反斜杠快捷键会同时适配轨道和已打开的关键帧抽屉。

### 3. 关键帧抽屉

- 新增 `UICompositorPanel.fitDurationTicks()`（`doc.fitEnd() * fps`）和唯一入口
  `fitKeyframeTimeline()`：
  ```java
  axis.anchor(0F);
  axis.view(0D, Math.max(1, fitDurationTicks()));
  ```
- 抽屉适配按钮、打开抽屉、布局获得真实宽度、`resetTimelineView`、首次 prime 全部走这个入口。
- `refreshSheets()` 中 `applyState(selection)` 恢复缓存轴后，只有在“本次确实是重置适配”时再
  适配一次；普通刷新仍保留用户缩放。

### 4. 可发现性

按钮和快捷键文案从“适配全长”改为“适配内容末尾（最后一个图层/音频）”，与实际行为一致。

## 验证

新增/更新：

- `CompDocumentDurationTest`：空工程回退画布、相机/null 不拉伸、音频计入、内容末尾正确。
- `FitContentContractsTest`(3)：轨道使用 `fitEnd()`、抽屉使用 `fitEnd()`、适配先 `anchor(0)`。
- `TimelineFitTest` 继续验证 `[0,duration]` 精确映射。

```text
191 suites / 1174 tests / 0 failures / 0 errors / 1 skipped
```

实机建议：30 分钟空尾工程 → 点击/\；最后视觉图层结束于 12s，轨道与抽屉都应从 0 显示到 12s；
拖动图层改变末尾后再次点击，适配范围随之变化；手动缩放后普通属性编辑不应重置缩放。
