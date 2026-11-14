# Round 114 — 预渲染隔离、适配全长、切分、绑定、抠像、时长与预合成尺寸

> 版本 `0.99-hotfix79`。本轮没有启动客户端截图；全部验证来自编译、纯逻辑/几何单测与源码契约测试。
> 最终全量：**190 套件 / 1168 项 / 0 failures / 0 errors / 1 skipped**。

## 1. 预渲染/虚拟回放不再出现在 BBS 原版影片编辑器

### 现象

BBS 原版影片编辑器的回放列表里出现 bbsae 合成器的每个模型图层（虚拟回放）。它们占掉列表
空间，且点选后属性面板会显示一个用户以为是“合成器图层”的隐藏对象。

### 根因

虚拟回放为了复用 BBS 原版回放编辑器，真实保存在 `Film.replays` 里；BBS 的 `UIReplayList`
直接遍历这张全表。bbsae 以前的“只显示虚拟回放”是 **文本正筛**，无法反向排除。

### 修复

- 新增 `VirtualReplayVisibility`：UI 层过滤，**不修改** `Film.replays`；保存、撤销、渲染不变。
- `UIReplayListMixin`：过滤 `refreshReplayList` 的行列表、文件夹路径与文件夹计数。
- `UIReplaysEditorMixin` / `UIFilmControllerMixin`：打开影片时不再初始选中隐藏行；
  PageUp/PageDown 跳过虚拟回放。
- `UIReplayModePanel` 在页面出现/绑定影片时 `borrow(panel)`，离开时 `release` 并刷新列表；
  bbsae 自己的回放模式仍然看到并可编辑虚拟回放。离开页面时同时把虚拟回放 `enabled` 置回
  false，避免它们以隐藏演员身份继续参与影片。

## 2. 时间轴「适配全长」真正适应全长

### 根因

`fit()` 用一次性的像素/秒快照，`ensureView()` 只检查 duration，不检查 lane 宽度。侧栏、
quick 面板、dock 分栏或窗口一改宽度，适配就失效：变窄会切掉结尾，变宽会留出空白。另外
`view()` 先于 `anchor(0)` 调用，anchor 非 0 时会把可视范围平移半屏。

### 修复

- `applyFit(Scale, duration, width)`：先 `anchor(0)` 再 `view(0,duration)`。
- `ensureView()` 记录 `knownWidth`；`fitMode` 下宽度或时长变化都会在同一帧重新适配。
- `restoreView(shift, zoom, savedWidth)` 按当前/保存的宽度比缩放 zoom，保留可见时间跨度；
  远程/不同窗口尺寸恢复不再把时间轴放大或缩在角落。
- 新增 `TimelineFitTest`：多种宽度/时长断言 `min=0`、`max=duration`、`px/s=width/duration`。

## 3. BBS 音频与影片片段切分（PR 式）

- 新增 `BbsClipSplit`：Ctrl+K / 右键「在播放头切分（Ctrl+K）」；选中优先，否则切所有与
  播放头相交的片段；切在片段边缘是空操作，不产生空撤销步。
- 快捷键注册进 BBS 按键设置，可在原版设置里改键。
- `CompositionClip.breakDownClip`：右半从相对 tick 0 开始时，把 `offset` 推进
  `切点距离 × timeScale`，合成画面在切点连续（含 0.5×/负 timeScale）。
- `manualTiming`：被切分/手动定时的片段不再被“自动覆盖整片”逻辑改回一段；删除合成片段时
  删除该合成的所有分段。
- 合成器音频轨也支持在播放头切分（Ctrl+K / 右键），右半源偏移按速度推进，淡入淡出分配到
  两端，不重复。

## 4. 合成器绑定回放在 BBS 影片编辑器里消失/错位

### 根因

- 叠加层渲染从不把当前 film 交给 `BindingResolver`；绑定只能依赖“合成器面板刚才设置过的
  全局 film”，面板关闭后解析失败。
- `CompScene` 用 `isBound()`（启用开关）而不是“本帧解析成功”来决定相机/3D 投影/排序，
  解析失败时图层被冻结为屏幕空间，原来由相机带入画面的 3D 层就“消失”了。
- 叠加层的 `CompInstance` 只有片段内时间；对拆分/变速片段用 `time*20` 当影片 tick 是错的。
- 3D 模型层在已解析绑定时又乘一次相机 view。

### 修复

- 叠加层从 `ClipContext.clips.getParent()` 取得 film，渲染期间临时设置
  `BindingResolver.setFilm/frameTick`，结束后恢复；`CompRenderContext` 记录 film 供嵌套预合成
  与 linked model 使用。
- `CompScene.cameraAffects/usePerspective/sortsIn3D` 改为判断 `boundPoint(ctx, layer) != null`：
  目标不可解析时按未绑定处理。
- `CompInstance` 增加绝对 `filmTicks`（来自 `context.ticks`）；绑定回退按真实影片 tick、相对
  回放原点、循环窗口与真实眼高投影。
- `ReplayActorPass` 对已解析绑定的模型使用屏幕空间正交投影，不再二次乘相机；叠加层里没有
  实时实体时用 replay 关键帧创建私有 stub，模型仍按关键帧动画。

## 5. 回放抠像人物破面/不完整

### 根因

- 蒙版在 `AFTER_ENTITIES` 重渲染演员时，BBS 的 `FormTranslucentQueue` 仍然激活：带半透明
  像素的模型会被拆成“立即不透明 + 世界 flush 的半透明命令”，蒙版里没有半透明部分
  （外层皮肤、披风、半透明盔甲等），看起来就是破面/缺件。
- 蒙版重渲染把 `tick + transition` 传给关键帧，`FilmEntityRenderer` 又插值一次 transition，
  姿态最多差一个 tick；世界深度比较时这些面被裁掉。
- occluded 蒙版把包含演员自己的世界深度拷进去，再以无容差 LEQUAL 重绘，姿态/深度量化差异
  会挖出面洞。

### 修复

- 蒙版与模型 pass 都 `FormTranslucentQueue.suspend()` / `restore`，所有部件立即画进自己的目标。
- 蒙版只 apply 整数 tick，sub-tick 交给 `FilmEntityRenderer` 一次。
- occluded 蒙版加 `GL_POLYGON_OFFSET_FILL`（负偏移）作为深度比较容差。
- 新增源码契约测试锁住这三条（半透明队列、单次 transition、逻辑像素投影）。

## 6. 合成器时长被拉到几万秒

### 根因

- 新工程/新回放合成直接继承 `film.calculateDuration()/20`；BBS 相机的 30 分钟占位会变成
  30 分钟画布。
- 合成片段的时长又取 `max(film.calculateDuration(), comp)`，而 film 包含这个片段本身，形成
  只能增不能减的棘轮。
- 旧数据可能把 tick 当秒写成 36 000 秒；检查器时长字段允许 36 000 秒，导出设置允许 3 600 秒，
  并且没有任何写入上限。

### 修复

- `CompDocument` 增加 `MIN_CANVAS=12`、`MAX_CANVAS=1800`、`AUTO_FILM_CAP=60`、
  `adaptiveInitialDuration()`、`clampDuration()`、`clampToMaxDuration()`。
- 新建影片工程/回放合成最多继承 60 秒；内容/音频再通过 `extendDurationToContent()` 增长。
- 加载工程时先把超上限画布（含被拉到同一结尾的图层/音频）折回 30 分钟上限，再扩展到内容。
- `CompositionClip.editorialDurationTicks()` 不计合成器片段自身，打断 film↔comp 棘轮；
  片段时长上限同为 30 分钟。
- 合成页时长字段、导出设置、同步按钮全部走 `clampDuration`；旧工程由
  `CompDurationRepair` 的“极端空尾”规则自动收缩（30 分钟画布、内容 < 5% 且 < 60 秒）。

## 7. 移动图层太灵敏 / 双击进入预合成会移动

- 时间轴拖动加入可配置死区：按下后指针移动不足阈值不写数据、不建撤销点；双击的第一下不再
  因抖动把片段挪一帧。
- 预览 2D/3D 拖动/缩放/旋转、轨道移动/裁剪/重排都使用同一阈值。
- 设置 → 变换与吸附新增：
  - 「移动拖动阈值」0–20px（默认 4px，0 = 立即拖动）；
  - 「预览中直接拖动图层」开关（关闭后预览只选择，移动仍可用角点/边柄、Gizmo 和轨道）。
- 旧 `prefs.json` 没有这些字段时通过可空字段解析为默认值，不会把布尔默认值读成 false。

## 8. 预合成添加效果后比例放大

### 根因

3D 图层加了效果后会从直接路径切到 layer-local 烘焙。烘焙的 FBO 是按上限缩小的，但正交投影
把 `rect` 又乘了一次 `scale`，而顶点坐标仍是逻辑像素：内容被放大 `1/scale`（1920 宽预览约
1.875×）并在边缘裁掉。padding 改变 `scale` 时放大倍数还会变化。

### 修复

- `CompLayerSourceCache.logicalProjection(rect)`：投影始终是逻辑像素矩形；viewport 负责缩放到
  FBO。内容、蒙版、贴图 UV 与效果空间重新一致。
- `RasterProjectionTest` 断言矩形四角精确映射 NDC ±1，并验证 padding 不会改变内容映射。
- 合成器片段拆分/删除、预合成折叠仍然保持原有行为。

## 9. 复核与剩余实机确认

新增/更新测试：

- `TimelineFitTest`(4)、`CompositionClipSplitTest`(6)、`VirtualReplayVisibilityTest`(4)、
  `RasterProjectionTest`(2)、`Round114ContractsTest`(6)、`BindingSceneTest`(+1)、
  `CompDocumentDurationTest`(+4)、`CompDurationRepairTest`(调整 2)、`CompPrefsOverlayTest`(+2)、
  `TimelineViewMemoryTest`(+2)。

仍需实机确认（headless 无法覆盖的交互）：

1. 打开 BBS 影片编辑器 → 回放列表只剩真实回放；bbsae 回放模式仍显示并可编辑虚拟回放。
2. 拖动侧栏/quick 面板改变时间轴宽度 → 适配全长仍精确贴住两端。
3. BBS 音频/视频/合成器片段 Ctrl+K 后跨越切点无跳变；Ctrl+Z 一步还原。
4. 在 BBS 影片编辑器播放绑定回放 → 合成器图层跟随且不消失；拆分片段绑定时间正确。
5. 回放抠像人物完整（含半透明外层）；被方块遮挡时仍正确遮挡。
6. 30 分钟占位影片打开新合成 → 合成时长 ≤ 60s；旧 36 000s 工程被收缩。
7. 双击预合成片段进入子合成 → 片段不移动；设置阈值/关闭预览拖动生效。
8. 3D 预合成加 glow/blur → 尺寸与未加效果时一致。

## 10. 验证

```text
> Task :test
190 suites / 1168 tests / 0 failures / 0 errors / 1 skipped
BUILD SUCCESSFUL
```

未启动客户端截图；`bbsae.mixins.json` 新增的四个 mixin 在编译期通过，仍需在下一轮真机启动时
确认 BBS 2.7 运行时注入（每个注入都带身份/存在性护栏，失效时退化为无过滤而不是破坏数据）。
