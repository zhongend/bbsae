# P1-8 / P1-11：曲线采样与时间轴绿条性能审计

任务板：`task-32`（实现 subagent）。日期：2026-11-13。构建：`gradlew`（串行、`--offline --max-workers=1`），未启动客户端。

## 结论

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| 曲线编辑器每列采样 | 每采样一次 `KeyframeChannel.interpolate()`：`new KeyframeSegment` + 装箱 `Float`；800 列 × 每帧至少 1 次、速度图 2 次 | 复用单个 `KeyframeSegment` + 基本类型插值；列数 > 400 时按 stride 降采样，每通道每帧最多 ~401 次采样 |
| 键点命中/绘制 | 每帧扫描所有通道的所有关键帧；每个可见键 2 次 `UiMotion.anim()` | 按 tick 二分出可见区间；只对 hover/选中状态变化或仍在收敛的键调用 `anim()` |
| 时间轴绿条 | `coverage()` 每帧 `new BitSet()` + 遍历全部 frames | 按 (generation, 帧数, 任务 cursor, stale) 缓存；静止时重建 0 次 |
| 时间轴关键帧刻度 | 每个可见行每帧 `allKeyframes()`：`new ArrayList` + 全部通道 `addAll` | 直接遍历通道，各通道按可见 tick 区间二分；`keyframeAt()` 同样不再拷贝 |

所有新增逻辑均为纯逻辑，不需要 GL；两个性能测试均可在无客户端环境下运行。

## P1-8 曲线编辑器

改动文件：`src/main/java/dev/bbsae/client/ui/UICompCurveEditor.java`

### 1. 零分配采样器 `CurveSampler`（:381）

- 复用 **一个** `KeyframeSegment`；段游标记录上次 `lowerBound` 位置、帧与 channel revision。
  - 同一 revision 且帧单调前进时，从上次位置线性推进（最多 16 步），失败再二分；帧后退（速度图 `frame±0.5`）直接二分。
  - revision 由 `CompCurveChannel.revision()` 提供，编辑后绝不使用旧段；信任游标之前还会验证 lower-bound 不变量，因此时间轴拖动 `shiftKeyframes()`（不 bump revision）也不会复用错段。
- 只走 `KeyframeFactories.FLOAT` 且无 loop 的通道：
  - 线性/缓动等：`IInterp.context.set(...)` + `interpolation.interpolate(...)`，无装箱；
  - Bezier：`BezierUtils.get(...)`；
  - Auto / Auto Clamped：调用 BBS 公共的 `AutoBezier.handles(...)` 写入**复用**的 `double[4]`，再按同一公式计算。BBS 的 `AutoBezier.get()` 每次会 new 两个 `double[4]`，这里绕开但保持逐点一致。
- 带 loop 的通道回退到 `channel.valueAt()`（loop 会合成临时关键帧/重映射时间，属罕见路径）；回退次数计入 debug 计数。
- `SEGMENT` 只在构造时创建一次（`segmentAllocations == 1`，测试断言）。

### 2. 降采样（:283, :2085）

`sampleStride(columns, downsampling)`：≤400 列时 stride=1；更宽时 `ceil(columns/400)`，gap 由 `fillColumns()` 线性填充（NaN 保持断开）。默认开启，`-Dbbsae.curveDownsample=false` 可关闭。例如 4000 px 的图表从 4000 次插值降到 400 次；视觉上 1 px 步长不可见。

### 3. `keyAt()` 二分（:3142）

命中半径 10 px 反推 tick 区间 `[fromTick, toTick]`，每个通道只遍历该区间内的键；不再为每个通道扫描整条曲线。

### 4. `renderKeys()` 缓存强调值（:2395, :1751）

- 每帧先 `stepKeyEmphasis()`，只推进“仍在动画中”的键集合（通常只有 hovered 键）。
- 绘制时只有目标值发生变化的键才调用 `motion.anim()`；其余键读取缓存的 `KeyEmphasis`（0 分配路径，仅有 IdentityHashMap 查找）。
- 键的绘制范围同样用 tick 二分，500 键的通道滚到末尾只访问可见的十几个。
- 原来的 `keyKeys`（每个可见键每帧两次 `motion.anim`）已删除，改成按 key 的 `KeyEmphasis` 缓存，容量上限 4096，超限整体清空（与旧行为一致）。

### 5. Debug（`-Dbbsae.debugCurve`）

每 120 帧输出一行：
`curve sampling: N/frame samples=... cursor=... binary=... fallback=... avgMs=... columns=... stride=...`
`curvesamples`/`cursorHits`/`binarySearches`/`fallbackSamples` 由采样器累计；`avgMs` 是全部通道每帧采样总耗时的平均。原有的 span dump 仍在同一开关下。

## P1-11 时间轴绿条

### 1. `CompPrecache.CoverageCache`（:176, :991）

- 每个 `Cache` 持有一个 `CoverageCache`。
- key = `generation`（签名编辑/磁盘清空）+ `frames.size()`（新增帧）+ `job cursor`（预渲染进度；非任务时为 -1）+ `stale`。
- 命中直接返回同一个 `BitSet`（只读约定），不再遍历 frames。
- 静止状态（无任务、无编辑、无新帧）下每帧只有 4 次比较，重建次数 0；`coverageRebuilds()` 暴露累计重建数，`-Dbbsae.debugTimeline` 下每 120 次调用输出一次。
- `cache == null` 时返回共享空 `BitSet`，不再每帧 new。

### 2. 可见行与关键帧刻度（`UICompTrackTimeline.java` :243, :1447）

- `renderRows()` 原本已经只遍历可见行；本次把“可见行”定义抽成 `firstVisibleRow()` / `visibleRowEnd()` 并补测试，确认滚动/边界/行高为 0 时不会越界。
- `renderKeyframeTicks()` 不再 `layer.allKeyframes()`：
  - 遍历 `layer.animatedPaths()` 返回的现有通道（轴通道 + 标量通道），再补 opacity / legacy transform 通道；
  - 每个通道用 `lowerBoundTick`/`upperBoundTick` 把键裁到 bar 的可见 tick 区间；
  - 仍然保留最多 220 个刻度的上限。
- `keyframeAt()` 同样按 `mouseX ± 5 px` 的 tick 区间扫描通道；`nearestKey()` 不触碰区间外的键。
- 每帧仍会为每个可见行调用一次 `animatedPaths()`（返回一个 path 列表的拷贝）。这是有意的取舍：路径数量是通道数，而旧 `allKeyframes()` 拷贝的是所有关键帧；彻底消除需要 `CompLayer` 提供只读通道视图，但 `CompLayer` 是他人写域，未改。
- Debug（`-Dbbsae.debugTimeline`）每 120 帧输出：可见行范围、实际画的刻度数、期间绿条重建次数（静止应为 0）。

## 测试

新增 3 个测试类，全部纯逻辑、无 GL：

1. `src/test/java/dev/bbsae/client/ui/CurveSamplingPerfTest.java`
   - LINEAR / BEZIER / AUTO / AUTO_CLAMPED / CUBIC / CONST 六种插值逐点对比旧 `channel.interpolate()`，容差 `1e-6`。
   - 两个 sampler、两次采样结果完全一致；cursor 命中 > 0。
   - 外部 `channel.moveX()`（模拟轨道拖动，不 bump revision）后，游标不会复用旧段，仍与旧算法一致。
   - 6000 次采样分配量 < 64 KB（旧路径约每个采样 1 个 segment + 1 个 Float）；`segmentAllocations == 1`、fallback = 0。分配计数器不可用时跳过（`Assumptions`）。
   - 降采样开关、stride 上限 400、gap 线性填充/NaN 断开。
2. `src/test/java/dev/bbsae/client/ui/TimelineVisibleRowFilterTest.java`
   - 可见行首/末、滚动、负 scroll、行高 0、层数上限。
   - 对任意滚动偏移验证：所有像素可见的行都落在 `[first, end)` 内。
   - `lowerBoundTick`/`upperBoundTick` 与通道 tick 排序一致；可见 tick 区间只含能画出的键。
3. `src/test/java/dev/bbsae/client/render/TimelineCoverageCacheTest.java`
   - 同 key 返回同一 `BitSet`，5000 次读取重建数仍为 1。
   - 预渲染 cursor / 帧数变化失效；generation / stale 变化失效；任务停止后恢复静态 key；空 BitSet 也复用。

运行命令（Windows；此工作区含中文路径，JDK 的 `@argfile` 在非 ASCII 路径下会乱码，故经 `C:\bbsae-build` junction 运行）：

```bat
set GRADLE_USER_HOME=C:\bbsae-build\.gradle-home
set JAVA_HOME=C:\bbsae-build\_tools\jdk21\jdk21.0.12_12
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
cd /d C:\bbsae-build
gradlew.bat test --offline --max-workers=1 --init-script .tmp\perf-test-init.gradle ^
  --tests "dev.bbsae.client.ui.CurveSamplingPerfTest" ^
  --tests "dev.bbsae.client.ui.TimelineVisibleRowFilterTest" ^
  --tests "dev.bbsae.client.render.TimelineCoverageCacheTest" --no-daemon --console=plain
```

源码是 UTF-8。若 Gradle daemon 的默认编码是 GBK，`javac` 会把中文字符串按 GBK 解释并报
`非法字符`；`.tmp/perf-test-init.gradle` 已设置 `options.encoding = 'UTF-8'`，命令里再设置
`JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` 双保险。

本次结果：3 个新测试类共 **17 个测试全部通过**（Curve 6、Timeline 6、Coverage 5）。随后跑
`dev.bbsae.client.ui.*` + `dev.bbsae.comp.*Curve*` + `AnimatedPathRemapTest` 回归，**224 个测试全部通过**（0 failure / 0 error / 0 skipped）。

## 剩余热点 / 需要 Lead 的接点

1. **曲线**：
   - 带 loop 的 float 通道仍走旧的装箱路径（正确性优先）；如以后 loop 常用，可把 loop 的虚拟端点也纳入无分配采样。
   - `AutoBezier` 的公式镜像依赖 BBS 2.7 的 `AutoBezier.handles` 行为；升级 BBS 时 `CurveSamplingPerfTest` 的逐点对比会第一时间发现漂移。
   - 每通道每秒仍会计算一次 `channel.raw()` / `defaultValue()`（每通道每帧一次，不是每列），可忽略。
2. **时间轴**：
   - `animatedPaths()` 每个可见行每帧仍返回一个 `ArrayList`（元素是通道 path 字符串）。彻底消除需要 `CompLayer` 提供只读通道视图；`CompLayer` 在他人写域，本次未动。
   - `CompLayer.shiftKeyframes()` / `allKeyframes()` 仍用于拖动轨道（写路径，不在每帧渲染路径），未改。
   - 纹理被回收但 `onDisk` 尚未置位的极短窗口内，绿条可能保留一个旧 bit；要严格闭合需要一个 coverage epoch 计数器，会进一步触碰预渲染逻辑，本次按规格只使用 generation/帧数/任务 cursor。
3. **测试环境**：完整 `test` 套件本次未能跑完——共享工作区里有并行写者正在修改 `CompScene.java`（中间态缺少 `cameraAffects`/`hasPixels`），`compileJava` 在到达测试前就失败；该文件不在本任务写域。验收以 **17 个新测试**（最终代码上重跑通过）和 **224 个 UI/曲线回归测试**（更早一次的完整编译上通过）为准。历史完整套件里另有 `MaskGlRenderTest` 的 GL 初始化失败（与本次改动无关）。
4. **未做的验证**：按任务要求没有启动客户端，因此没有截图/帧时间实测；`-Dbbsae.debugCurve` 与 `-Dbbsae.debugTimeline` 的日志可在 Lead 的 runClientView 验收时直接观察（静止时 `coverageRebuilds=0`）。
5. **未触碰的写域**：`UICompositorPanel`、`CompRenderer`、`MaskCache`、`CompLayer`、`CompCurveChannel` 均未修改；`CompPrecache` 只新增了 coverage 缓存与计数器，预渲染/磁盘/GL 逻辑未改。
