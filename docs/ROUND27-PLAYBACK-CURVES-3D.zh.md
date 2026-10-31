# Round 27 — 预览时钟、原版曲线选择、3D XYZ 与命名修复（0.16.0）

> 本轮基于 0.15.0 源码，先合入外部交付的 0.14.2 修复（导出管道 / 音频 / 关键帧选择 / GL
> 深度纹理 / 原生文件对话框 / 数值落盘），再修四个用户可见的问题，并顺手清掉了三个同源的
> 标量关键帧缺陷。测试 **191/191**，实机 1920×1080 截图回归通过。

## 1. 预览时间速度与 BBS 原版不一致（P0）

### 根因

`UICompositorPanel.startRenderFrame(float tickDelta)` 每渲染帧执行：

```java
this.time += tickDelta / 20F * rate;
```

这里参数来自 BBS 的 `UIDashboard.startRenderFrame(MinecraftClient.getTickDelta())`。
`getTickDelta()` **不是上一帧到这一帧的时长**，而是“距离上一个客户端 tick 已经过去了多少
个 tick 的零头”，取值始终在 `[0,1)` 之间，并且每个 tick 归零一次。把它逐帧累加，等于对一
条锯齿波求平均：

| 显示刷新率 | 每个 tick 的帧数 | 旧公式每秒推进 | 结果 |
| --- | --- | --- | --- |
| 30 fps | 1.5 | 30 × 0.5 / 20 = **0.5 s** | 慢一倍 |
| 60 fps | 3 | 60 × 0.333 / 20 = **1.0 s** | 碰巧正常 |
| 90 fps | 4.5 | 90 × 0.5 / 20 = **2.25 s** | 快 2.25 倍 |
| 144 fps | 7.2 | 144 × 0.5 / 20 = **3.6 s** | 快 3.6 倍 |

所以同一份合成在 60 Hz 显示器上“看起来是对的”，换到 144 Hz 就明显比 BBS 原版播放快。
`CompositionOverlay` 在成片/影片预览里用的是 BBS 的 tick 时间，不受影响；问题只出在合成器
自己的预览时钟，二者因此无法对齐。

### 修复

`CompTime.playbackDelta(previousNanos, nowNanos)` 用 `System.nanoTime()` 的真实秒数推进，
并夹在 `[0, 0.5s]` 内（防止 alt-tab/GC 后播放头瞬移）。`UICompositorPanel` 在
`startRenderFrame` 中维护 `lastPlayNanos`：

- 恢复播放、暂停、变速、定位、切走面板时清零，暂停时长不会被补进播放；
- 播放速率（1×/2×/4×/倒放）仍乘在真实秒数上；
- 循环仍按工作区取模。

BBS 自己的播放器以 20 tick/s 推进，因此现在“合成器 1 秒 = 影片 1 秒”。`CompTimeTest`
新增 4 个用例，包括对旧锯齿波公式的复现（144 fps > 3×、30 fps < 0.75×、60 fps ≈ 1×）。

## 2. BBS 原版效果/片段选择界面显示英文串（P1）

### 根因

BBS 的片段类型选择菜单用 `UIKeys.C_CLIP.get(link)` 取标签，键是
`bbs.ui.camera.clips.<link>`。我们的镜头片段注册为 `Link(bbsae, composition)`，BBS 语言
表里没有这个键，`L10n.getKey(key)` 会以 **键本身** 作为默认内容，于是菜单里出现：

```
bbs.ui.camera.clips.bbsae:composition
```

### 修复

`BbsAeClient` 订阅 BBS 的 `RegisterL10nEvent`，并在 `onClientReady` 兜底再写一次：

```java
l10n.getKey("bbs.ui.camera.clips.bbsae:composition").content = "合成器";
l10n.getKey("bbs.ui.camera.clips.composition").content = "合成器";
```

注意 `L10n.getKey(key, fallback)` 不会覆盖已经被 `C_CLIP.load()` 创建过的键，所以这里直接
写 `LangKey.content`。同时把 `CompositionClip.ensureForFilm` 新建片段的默认标题从
`"Compositor"` 改为 `"合成器"`，镜头时间轴和选择菜单名称一致。实机日志：

```
Clip picker localization: bbs.ui.camera.clips.bbsae:composition = 合成器
Clip picker localization: bbs.ui.camera.clips.composition = 合成器
```

## 3. 轨道右侧：3D 图层的 XYZ 编辑 + BBS 原生曲线选择（P1）

### 3.1 3D 图层 XYZ

`UILayerQuickPanel` 原来固定为 2D 五栏（X/Y/旋转/统一缩放/不透明度），3D 图层的
`z`、`rotationX`、`rotationY`、`scaleZ` 只能去检查器里滚。现在面板按选中图层自动切换：

- **2D**：位置 X/Y、旋转 Z、统一缩放、不透明度（保持原布局）。
- **3D**：位置 X/Y/**Z 深度**、旋转 **X 俯仰/Y 偏航**/Z、缩放 **X/Y/Z**、不透明度。
- 每组一个 ◆（位置/旋转/缩放/不透明度）：位置键同时写变换向量与 `z`，旋转键写旋转 Z 与
  `rotationX/Y`，缩放键写 `scaleX/Y` 与 `scaleZ`。所有写入仍走
  `transformChannel` / `scalarChannel`，与检查器、曲线编辑器同源。
- 标量通道使用 `layer.scalarAt("z"|"rotationX"|"rotationY"|"scaleZ", 当前字段值, frame)`，
  数值刷新沿用 ROUND26 的“同层同帧但值变了也刷新”逻辑。

### 3.2 BBS 原版曲线选择

“插值曲线”按钮出现在两个位置：

1. 关键帧抽屉标题栏（轨道正下方、紧邻轨道右侧）；
2. 图层快捷面板标题栏（轨道最右侧常驻）。

点击后交给 BBS 自己的 `UIInterpolationContextMenu`：同一张曲线预览图、同一组图标网格
（线性/定格/贝塞尔/自动/各种缓动/弹跳/回弹…）、同样的 v1–v4 参数和复制/粘贴。它直接修改
选中 `Keyframe` 的 `Interpolation`，并通过 `IUIKeyframeGraph.setInterpolation()` 复制到
所有选中键；没有选中键时自动选中播放头所在帧的第一个键。实现没有重写插值，也没有第二套
缓存。

截图：

- `docs/verify/round27-quick3d.png` — 3D 快捷面板的完整 XYZ 行；
- `docs/verify/round27-curve-menu.png` — BBS 原生插值菜单。

## 4. 同源缺陷：标量关键帧读到 0

修 3D XYZ 时发现三条同源问题，一并修掉：

1. **◆ 首次不可点**：`keyButton.active(...)` 要求 `layer.hasScalarChannel(path)`，导致
   Z / 俯仰 / 偏航 / Z 缩放 / 挤压 / 摄像机距离/目标/FOV 在“还没有通道”时按钮是禁用的，
   检查器里永远打不了第一颗键。现在所有可动画路径都可创建首键。
2. **插错值**：`addScalarKey` 先 `setScalar` 再读 `scalarValue`；而 `CompLayer.scalar()`
   没有列 `z`、`rotationX/Y`、`scaleZ`、`squash`、`cameraZ`、`cameraTargetX/Y/Z`、
   `cameraFov`，默认返回 0。点“距离 Z”的 ◆ 会把摄像机键到 0。现在 `scalar()` 覆盖
   `putScalar()` 的全部标量字段，检查器把当前显示值直接传入 `addScalarKey(layer, path, value)`，
   且在 `beginAction` 之后才写入，撤销检查点仍是操作前状态。
3. **快捷面板 Z 键**：`quickKeyZ` 走同一个 `scalarValue`，同样会把 z=500 的图层键到 0；
   随第 2 条一起修复。

新增 `CompModelTest.scalarFallbacksCoverEveryEditable3DAndCameraField` 覆盖全部字段。

## 5. 验证

```text
./gradlew test --offline        191 tests, 0 failures
./gradlew runClientView -PbbsaeView=quick   # 1920x1080 截图
```

实机确认：

- 快捷面板显示 `3D Card · 3D` 及位置/旋转/缩放三组 XYZ；
- 关键帧抽屉标题栏与快捷面板均出现“插值曲线”；
- 点击后 BBS 原生插值菜单正常弹出（曲线预览 + 图标网格 + 参数 + 复制/粘贴）；
- 日志中两个 clip 键均解析为“合成器”；
- 没有新增异常 / GL 错误。

## 6. 构建 / 版本

`gradle.properties` 的 `mod_version` 升级为 **0.16.0**。产物：

```
build/libs/bbsae-0.16.0.jar
```

开发机构建（Windows，中文路径）需要从 `subst` 盘运行，否则 Gradle 的
`gradle-worker-classpath*.txt` 会以 UTF-8 写入、以 GBK 读取，测试 worker 报
`ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain`：

```bat
subst B: "C:\Users\Administrator\Desktop\bbsae1.0 - 副本"
set JAVA_HOME=B:\_tools\jdk21\jdk21.0.12_12
set GRADLE_USER_HOME=B:\.gradle-home
B:\gradlew.bat test --offline
```
