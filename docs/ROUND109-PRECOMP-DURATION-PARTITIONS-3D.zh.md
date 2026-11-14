# Round 109 — 独立预合成、自由时长、分区重做与 3D 挤出

> 版本 `0.99-hotfix74`。未启动客户端截图；全量 179 套件 / 1101 项 / 0 failures / 1 skipped。

## 1. 复制的预合成仍然共享？先把"复制"的语义拆对

### 现象

复制一个预合成图层后，副本和原图层播放同一份嵌套合成：改副本的子层、效果或关键帧，
原预合成一起变。

### 根因

预合成图层只存一个 `precompId` 字符串。hotfix72 已经让 **图层复制**（Ctrl+D / Ctrl+C+V /
轨道菜单）递归克隆嵌套树，但"让一个已经共享的预合成变独立"没有一个**工程级**入口：

- 旧工程可能本来就有两个预合成层指向同一个 `CompDocument`；
- 模板/库里保存的图层快照只带 `precompId`；
- 深拷贝失败时 `clonePrecompTree` 会回退成原 id —— 静默回到共享。

### 第一性原理

"复制图层"和"复制合成"是两件事：

| 操作 | 复制什么 | 结果 |
| --- | --- | --- |
| 复制图层 | 图层的变换/时间/效果 + 一份私有嵌套树 | 两个独立预合成，两个图层 |
| 复制合成 | 一份 `CompDocument`（含嵌套树） | 一个可被任意图层引用的新合成 |

因此模型层新增两个原语，UI 只在需要的地方调用：

- `CompLayerOps.duplicateComposition(project, source)`：`CompStore.duplicateDocument`
  （快照序列化 → restore，含 transient 关键帧轨道）克隆文档，再递归克隆嵌套预合成；
- `CompLayerOps.duplicatePrecompTarget(project, layer)`：克隆该层的目标并把**该层**重指向
  副本，其它层不动；
- 深拷贝失败返回空目标（渲染成空预合成占位），**不再返回原 id**。

检查器预合成区新增「复制目标预合成（独立副本）」。旧工程中共享的图层可以逐个拆开，
这也是"两个预合成都是独立的"最直接的修复路径。

## 2. 项目时长限制与拖不动

### 现象

时间轴内容到某个长度后，图层条再也拖不到更晚的位置。

### 根因（两条必须同时修）

1. `UICompTrackTimeline.fromX(int)` 返回
   `clamp(xAxis.from(x), 0, duration)`。时间轴视图适配为 `0..duration`，鼠标在屏幕上的
   最右位置就只能映射到 `duration` —— 内容越接近项目结尾，可拖范围越小。
2. 拖动帧里 `delta = fromX(mouse) - fromX(dragStartX)`：一旦视图为了跟随内容而滚动/缩放，
   `fromX(dragStartX)` 变了，图层相对指针的位置就漂移。

### 修复

- `fromX()` 去掉上界（只保 `>= 0`）；播放头等需要限制的调用自己 clamp。
- 按下时快照 `dragStartTime`，整段手势用 `当前时间 - 快照` 计算位移。
- 新增 `ensureDragViewCovers(time)`：指针越过当前视图右缘时，以**相同像素/秒**把视图
  向右平移并显示到指针时间（不是缩放），因此不会因为"边拖边缩放"产生加速或回跳。
- 拖动开始（`beginDragCheckpoint`）时冻结自动适配，避免文档增长触发 `fit()` 改变
  像素/秒；松开后，如果文档变长就重新适配，否则恢复原适配状态。Esc 取消时 `fit()` 恢复。
- `extendDurationForLayers/Audio` 继续负责 `setDurationAtLeast`，现在真的能生效。
- 音频片段的移动/裁剪使用同一条 `dragStartTime` 路径。

## 3. 滚轮手势统一

| 手势 | 轨道时间轴 | 关键帧抽屉 | 曲线编辑器 |
| --- | --- | --- | --- |
| Alt+滚轮 | 时间缩放 | 时间缩放 | 时间缩放 |
| Alt+Shift+滚轮 | 行高 | 行高 | 通道行高 |
| Ctrl+滚轮 | 时间缩放（别名） | BBS 默认 | 数值轴缩放 |
| Shift+滚轮 | 水平平移 | — | 水平平移 |
| 普通滚轮 | 垂直滚动 | 垂直滚动 | 时间缩放 |

面板级 `subMouseScrolled` 兜底会按指针位置路由：指针在抽屉里就缩放抽屉，否则缩放
轨道时间轴；`Alt+Shift` 不会被误当成时间缩放。所有提示文案（状态栏、设置页、快捷键
帮助、引导、旧帮助卡）同步更新。

## 4. 关键帧抽屉把预览压小且不复原

### 根因

`ensureKeyframeDrawerSpace()` 在时间轴太矮时重写 dock 的最外层分割比例（旧版布局则抬高
`timelinePanelHeight`），但关闭抽屉时没有任何代码把比例/高度写回去；`savePanelLayout()`
还会把增高后的布局持久化。

### 修复

- `rememberTransientTimelineLayout()`：首次增高前记住 dock 根节点或旧版时间轴高度；
- `endTransientTimelineExpansion()`：关闭抽屉、切回时间轴页、离开面板、进入专注模式、
  真实窗口尺寸变化时恢复；
- 撤销/项目切换（`applySnapshot` / `bindStandalone`）使用
  `forgetTransientTimelineExpansion()`，并把实时 settings 里的临时树换回原树，避免把临时
  布局写进工程；
- 增高时保留 `TRANSIENT_PREVIEW_FLOOR = 150px` 的预览下限，短窗口只显示可用高度并提示；
- `transientLayoutRestoring` 守卫防止"恢复 → layoutKeyframeDrawer → 再增高"的循环；
- 检查器滚动位置按「页 + 选择」记忆：`inspectorIdentity()` + `inspectorScrollMemory`。

## 5. 左右分区显示

### 根因

- 右侧检查器有 9 个分区（调色/图层/3D/影视/蒙版/效果/文字/合成/输出），一行放不下：
  `UICompTabBar` 会隐藏尾部页签，只留 «/» 溢出控件。要"调整效果"就得先想办法把
  效果页找出来，指针还要横向找滚轮/箭头 —— 这就是"每次都要滑动半天滚轮"。
- 图层页同时塞了身份、时间、父级、遮罩、表达式、来源、预合成和跟踪器，默认展开，
  内容非常长。

### 修复

- `UICompTabBar.wrap(rows)`：固定行数的网格布局。右侧 2 行（5+4），左侧 1 行；所有
  分区**始终可见**，不再有隐藏页签；宽度不足时整行等比收缩（下限 16 px）。
- `layoutWrappedTabStrips()` 统一两列的页签高度与页面起点，dock 与旧版布局共用。
- `UICompTabBar.emphasize(index, supplier)` 给 3D 分区做"开=白/关=灰"的状态色。
- 图层长页默认折叠；跟踪器移到「合成」页（`CompDocument.trackers` 是合成级数据）。

## 6. 3D 分区与平面挤出

### 设计

- 新增 `PAGE_3D`（inspector tab 3），标签在 `layer.threeD` 时白色、否则灰色；预览右侧
  也加了同一个状态的快捷图标。
- **Z / 旋转 X/Y / 缩放 Z 等留在「图层 · 运动」页**，3D 页只编辑变换表达不了的部分：
  厚度、方向、厚度颜色、取用图层颜色、侧面明暗、背面封盖。这样 K 一个 3D 移动不需要
  在两个页之间来回切。

### 几何（`CompExtrusion`，无 GL）

- `isEnabled`：开启挤出 + `threeD` + 有像素（排除 camera/null/adjustment/replay 实体）；
- `slices(layer, legacyEffect)`：返回**远 → 近**的平面列表：
  - 向后：`z = 0 → -depth`，第 0 片是原画面（tint 白，保留原色），其余按
    `1 - shade*t` 变暗；
  - 向前：`z = 0 → +depth`，最后一片是原画面；
  - 双向：以 0 为中心，两侧对称；
  - `extrudeCap=false` 时不画远端封盖（空心）；
- 旧 `extrude3d` 效果作为兼容回退；新开关打开后不再叠加旧效果。

### 渲染路径

| 路径 | 接入点 |
| --- | --- |
| 合成器预览（BBS / AE） | `CompRenderer.renderCached` / `renderFrameCached` → `renderCompToFbo` → `renderBaked3D` |
| 预渲染 / 帧服务器 / 导出 | `renderForCache` / `renderFrame` → 同一条 `renderCompToFbo` |
| 3D 自由视口 | `CompViewportRenderer.drawExtruded3D`（逐片 `Viewport3DRenderer.draw`，新增 RGB tint） |
| 回放预览（相机/环绕/自由/PiP） | `UIReplayViewport` → `CompRenderer.renderFrameWithCamera` / `renderFrame` |

`shouldBake3D` 因挤出而返回 true：没有效果/蒙版的纯色、文字、形状、图片图层也会走
layer-space 光栅路径，否则只会画一张平面 quad。`CompRenderCache.signature` 加入全部
挤出字段、`collapse`、`formData/replayId/锚点`，停住播放头调参数也会重绘。

## 7. 验证

- 新增 `CompExtrusionTest`（12）：方向与画家顺序、原画面保留、封盖、颜色来源、明暗、
  舍入、复制/保存重载、旧效果兼容、范围钳制；
- 新增 `PrecompIndependenceTest`（7）：工程深拷贝、嵌套树、私有目标、两次复制、保存
  重载、非预合成、复制/粘贴独立；
- 新增 `ExtrusionCacheSignatureTest`（3）：每个挤出字段、collapse、linked replay 都会
  让缓存帧失效；
- 全量：**179 套件 / 1101 项 / 0 failures / 0 errors / 1 skipped**。

## 8. 边界

- 挤出厚度不可关键帧（v1 静态参数）；
- 逐像素深度模式下体积按所在平面合成；
- 回放实体层不提供挤出；
- 临时增高布局在真实窗口 resize 时重新评估（分栏拖动不触发）。
