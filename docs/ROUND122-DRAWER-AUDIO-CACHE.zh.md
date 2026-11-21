# Round 122：关键帧抽屉无法调节大小、跨工程音频缓存串音

> 版本 `0.99-hotfix89`。本轮没有启动客户端（用户正在使用项目），全部用几何/键值单元测试和
> 全量测试验证。

## 1. 关键帧抽屉 splitter 只有 5px 行程

### 根因

这不是“手柄没画出来”，而是 **最大值和最小值几乎相等**：

- 抽屉最小高度 `KEYFRAME_DRAWER_MIN = 96`；
- 轨道区保留 `ruler(20) + 3 行(3×26) = 98`；
- 常见停靠时间轴高度约 236，内容顶 32，splitter 5：
  `max = 236 - 32 - 98 - 5 = 101`。
- 因此拖动范围只有 **96..101**，看得到 5px 手柄，实际几乎不能改变高度。

旧的“保留三行”本意是不让抽屉吃掉轨道，但它把抽屉的可调空间吃掉了；这是自相矛盾的约束。

### 修复

- 轨道区只保留 **ruler + 1 整行**（保留轨道的可识别性和一个可操作行），把其余空间交还
  给抽屉；正常时间轴下 `max` 从 101 提升到 153，形成真实拖动行程。
- 新增 `KEYFRAME_DRAWER_MIN_RESIZE_RANGE = 24`：打开抽屉时若可用行程不足 24px，调用
  `ensureKeyframeDrawerSpace()` 尝试把时间轴增高（仍受预览区最小高度约束）；仍然不足时给出
  明确状态提示，而不是静默卡死。
- `PanelResizeHandle.drag()` 改用纯函数 `resizeKeyframeDrawerHeight(start, dy, max)`；拖动时
  同时 `keyframeHandle.resize()`，让 5px 抓取区始终跟着新的接缝，而不是停在动画结束时的位置。
- `layoutKeyframeDrawer()` 末尾也同步 handle 的 `resize()`。

### 测试

新增 `KeyframeDrawerResizeTest`：

- 旧的三行 reserve 在 236px 时间轴上确实只剩 101px，最大-最小 ≤5px（把回归钉死）；
- 新 reserve 下行程 ≥40px；
- 上拖增大、下拖减小、上下越界 clamp；
- 极矮时间轴仍然不会把抽屉压到 96px 以下。

## 2. 跨工程音频缓存串音

### 根因

`CompMixCache` / `CompAudioPlayer` 的混音 key 只包含 `doc.id + duration + clip 参数`，
**不包含 project id**。两个工程都可能有 `main` 合成、都使用 `audio/bgm.wav` 时，key 相同：

- 工程 A 先混好 `audio/bgm.wav`，缓存进入 `READY`；
- 切到工程 B，`get(projectB, doc)` 得到同一个 key，直接返回工程 A 的采样；
- 结果可能是 B 播放 A 的音乐，或者因为 B 的素材不同而听起来“没有声音/声音不对”。

### 修复

- `CompMixCache.keyOf(CompProject, CompDocument)` 把 **project.id** 纳入 key；缓存读写、worker
  result 的“是否仍是最新”校验全部使用带 project 的 key。
- `CompFilmAudioExport` 的阻塞混音也走带 project 的 key。
- `CompAudioPlayer` 同样保存 `currentProjectId`，`ensureBuffer` / `tick` / 失败 key 都带 project；
  切换工程且 clip 内容相同也会重新混音。
- 旧的内容 key 仍保留给“同一工程内比较文档”的测试/调用，但缓存查找不再使用它。
- 新增 `CompMixCacheKeyTest.projectIdIsPartOfTheMixKey`。

## 验证

- `KeyframeDrawerResizeTest`、`CompMixCacheKeyTest`、`IconResourceTest`、`TextLayerBoundsTest`
  聚焦测试通过；
- 本轮未启动客户端；不会干扰正在使用的项目。
- 全量测试：**204 suites / 1237 tests / 0 failures / 0 errors / 1 skipped**；
- 构建产物 `bbsae-0.99-hotfix89.jar` 见提交记录。
