# Round 17 — 曲线编辑器 P0 + P1 实施（0.6.0）

> 承接 [`ROUND16-CURVE-EDITOR-DESIGN.zh.md`](ROUND16-CURVE-EDITOR-DESIGN.zh.md) 的方案。
> 本轮只做 **P0（正确性）+ P1（消除卡顿）**，外加用户报告的**曲线页空白 bug**。
> P2（AE 交互对齐）与 P3（VBO）未动，留给下一轮。
>
> 备份：`_backup/round16-pre/`（改动前的 `src` / `docs` / 构建文件 / `bbsae-0.5.0.jar`）。

---

## 1. 曲线页空白 bug（用户报告）

### 1.1 现象

在页面之间切换后，曲线编辑区变成一块空白，而且不会自动回到时间轴。

### 1.2 第一性原理定位

曲线区的内容全部画在一个**矩形**里，而这个矩形由 `Graph.updateAreas()` 计算：

```java
this.curveArea.set(this.listArea.ex(), this.area.y + RULER_H,
    this.area.w - this.listArea.w, this.area.h - RULER_H);   // 旧代码
```

只要 `area.h < RULER_H`（18px）或 `area.w` 为 0，`curveArea` 的高度/宽度就是**负数**。
`Batcher2D.clip()` 用这个矩形做裁剪，负高度等于"裁掉全部"：
`renderGrid` / `renderCurves` / `renderKeys` 全部被裁没，只剩父容器的底色 —— **看起来就是空白**。

为什么面积会变 0：布局是**事件驱动**的，只在"点 tab"时执行一次 `applyTimelinePage()`：

```java
trackTimeline.setVisible(tracks);
curveEditor.setVisible(!tracks);
layoutKeyframeDrawer();
this.resize();
```

而下面这些状态变化都**不会**重新推导底部条：

- 切到别的 dashboard 页面再回来（`onAppear` 只做 `ensureBound`）；
- 进入/退出预合成（`refreshComp`）；
- 切换"渲染/编辑"页（`toggleRenderView`）；
- 面板被重新挂载时尺寸变化。

于是曲线编辑器可能"可见但矩形过期/为零"，渲染出来就是空白；而且没有任何逻辑会在它不可用时把它关掉。

### 1.3 修复（三层）

1. **纯函数化**：新增 `reconcileTimelinePage()`，每帧用 `(timelinePage, 可见性, 实际矩形)` 重新推导一次。
   它是幂等的，只在状态不一致时调用 `applyTimelinePage()`；三个整数比较，成本可忽略。
2. **所有入口重新推导**：`onAppear`、`refreshComp`（进出预合成）、`toggleRenderView`。
3. **兜底钳制**：`updateAreas()` 不再产生非正矩形
   （`Math.max(1, ...)`），并且 `applyTimelinePage()` 显式 `curveEditor.resize()`，
   不等布局队列的下一帧。
4. **自动关闭**：曲线区高度 < 80px 且面板本身够高时，自动切回时间轴并提示
   "曲线区太窄，已切回时间轴" —— 这就是用户要的"会自动关闭"。

另外：切到曲线页时，底部条**一次性**长到面板高度的 55%（`applyTimelinePage(grow)`），
因为 224px 的默认高度画不下一条曲线。

---

## 2. P0 — 正确性

| # | 问题 | 修复 |
| --- | --- | --- |
| P0-1 | **拖动曲线时预览显示旧帧**：`renderCached` 先查预渲染缓存，而 `markDirty()` 只在 `save()` 里调用，拖动不写盘 | 拖动每帧调用 `panel.markCurveDirty()` → `CompPrecache.markDirty()`；绿帧立刻失效 |
| P0-2 | 曲线区的 Alt/Ctrl/Shift 用 `Window.isXxxPressed()`（dashboard 里不可靠） | 全部改走 `CompInput`（GLFW 物理键态） |
| P0-3 | 每次 `refresh()` 都重置当前通道（`channels.contains(active)` 用对象身份比较，新对象永远不在列表里） | 按 `id` 恢复当前通道 |

P0-3 是排查时顺带发现的：改一次 Inspector 数值，曲线页的活动通道就跳回"位置 X"。

---

## 3. P1 — 消除卡顿

### 3.1 曲线不再"按像素画线"

旧实现（`line()`）每 1px 步进发一个 quad，顶点数正比于**斜率**：一条 0→1080 的曲线一帧 8000+ quad。

新实现：`CurveSpans`（`client/ui/CurveSpans.java`）

- 每个屏幕列存 `[lo, hi]` 两个 short（该列覆盖的像素区间），外加一个 `flags` 字节；
- 每列的区间取 `y(column)` 与 `y(column+1)` 的 min/max，所以**陡段不会断线**；
- 绘制时把 `lo/hi` **完全相同**的连续列合并成一个矩形（平直段 1 个矩形，陡段最多 1 列 1 个）。

| 场景 | 旧 quad 数 | 新 quad 数 |
| --- | --- | --- |
| 平直曲线（600px 宽） | ~900 | 1–5 |
| 普通曲线 | ~1050 | 100–600 |
| 陡峭曲线（0→1080 / 0.5s） | ~8000 | ≤600 |

`line()` 仍用于手柄连线，但改为**按水平跨度**步进（原来是按主轴步进，竖直手柄会发几百个 quad）。

### 3.2 几何缓存 + 脏列

- `CompCurveChannel` 新增 `revision`（不参与序列化），每个写路径 `touch()`：
  `insert` / `setKeyValue` / `remove` / `removeAll`；
- 5 条变换轴共享一份 `Transform` 关键帧列表，所以另有一个 `transformEpoch`：
  改任意一轴，5 条轴一起失效（否则 Y 轴会画旧曲线）；
- 视口/数值轴/模式变化 → `viewKey` 变化 → 全量重建（600 列 ≈ 0.2ms）；
- 拖手柄/拖关键帧 → `markDataChanged(fromCol, toCol, channel)` 只重建受影响列
  （通常是 20–80 列 ≈ 0.05ms）；
- 无变化帧 → **零求值、零分配**。

### 3.3 求值不再每样本分配

`CompCurveChannel.valueAt(frame)` 原来每次调用 `layer.defaultTransform()`（`new Transform` + 3 个 `Vector3f`）。
新增 `valueAt(frame, Transform fallback)`；曲线编辑器每次重建**只取一次**默认变换，
采样时复用。500 键通道从"每帧 350 次分配"降到"每次重建 1 次"。

### 3.4 拖动只排受影响的通道

旧代码每帧对**所有通道** `raw().sort()`（18 通道 × 500 键 ≈ 8 万次比较/帧）。
新代码：

1. 收集本次拖动涉及的通道（`IdentityHashMap` 去重）；
2. `sortIfNeeded()`：先 O(n) 扫一遍 tick 是否单调，**有序就完全不排**；
3. `CompCurveChannel.setKeyValue()` 里原来那句 `transformChannel().sort()` 删掉
   （改值不会改变 tick 顺序）。

### 3.5 每帧分配清理

| 位置 | 旧 | 新 |
| --- | --- | --- |
| `renderKeys` | `channel.keys()` → List + N 个 `Key` | 直接遍历 `raw().getKeyframes()`，先按 x 剔除 |
| `renderHandles` | 同上 | 同上 |
| `keyAt` / `selectInMarquee` | 同上 | 同上，且先做 x 距离剔除 |
| `channelOf(key)` | 遍历所有通道 + 每个通道遍历所有键 | `owners` 身份表，O(1) |
| `renderList` | 每帧 `buildRows()` → N 个 `ListRow` | `rowsDirty` 标志，只在通道集合/折叠/滚动变化时重建 |
| `renderGrid` | 每帧每条线一次 `String.format` | 按 (axisMin, axisMax, step) 缓存标签数组 |
| `renderReadout` | 每帧 `String.format` + 拼接 | 按 (通道, 帧号) 缓存字符串 |

### 3.6 松手不再重建 Inspector

`endEdit()` 原来调用 `curveChanged()` → `refreshSheets()`（`removeAllSheets()` 重建全部）
+ `refreshInspector()`（`removeAll()` 重建全部控件）。

现在分两级：

- `curveChanged(false)`（拖动松手）：只重跑 `inspectorBindings`（值绑定），不重建任何控件；
- `curveChanged(true)`（加键/删键/改类型）：才 `refreshSheets` + `refreshInspector`。

### 3.7 预览与曲线解耦

1. **时间域脏区**：拖动时把受影响的时间区间告诉预览
   （`panel.setCurveDirtyRange(from, to)` → `CompRenderCache.setTimeGuard`）。
   播放头**不在**该区间时，指纹变了也直接复用上一帧
   （`reuseForTime` 还要求帧号相同，绝不会拿别的时间的帧顶替）。
2. **交互期自适应分辨率**：拖动期间 `previewScale` 乘 0.7（像素 −50%），松手恢复。
   这就是 AE 的 Adaptive Resolution。

---

## 4. 文件地图

新增：

- `src/main/java/dev/bbsae/client/ui/CurveSpans.java`（列跨度缓存）
- `src/test/java/dev/bbsae/client/ui/CurveSpansTest.java`（5 条断言）
- `src/test/java/dev/bbsae/comp/CompCurveChannelRevisionTest.java`（3 条断言）

修改：

| 文件 | 改动 |
| --- | --- |
| `comp/CompCurveChannel.java` | `revision`/`touch()`、`valueAt(frame, fallback)`、去掉 `setKeyValue` 里的 sort |
| `client/ui/UICompCurveEditor.java` | 列跨度绘制、脏列、零分配遍历、owners 表、行/标签/读数缓存、松手分级、`CompInput`、活动通道按 id 保持 |
| `client/ui/UICompositorPanel.java` | `curveChanged(boolean)`、`rebindInspectorValues()`、`markCurveDirty()`、`setCurveInteraction()`、`setCurveDirtyRange()`、`reconcileTimelinePage()`、`applyTimelinePage(grow)`、`onAppear/refreshComp/toggleRenderView` 重新推导 |
| `client/render/CompRenderCache.java` | `cachedFrame`、`setTimeGuard/clearTimeGuard/reuseForTime` |
| `client/render/CompRenderer.java` | 预渲染缓存之后加时间域短路、`setInteractionPreview()` |

---

## 5. 验证

- `gradle test`：**119/119**（新增 `CurveSpansTest` 5 + `CompCurveChannelRevisionTest` 3）。
- 新增测试钉住两条几何不变量：
  1. 陡段相邻列的区间必须相接（否则曲线是虚线）；
  2. 部分重建不得改动脏区间之外的列（否则拖动会留下旧线段）。
- `gradle remapJar --offline` → `bbsae-0.6.0.jar`。
- 中文路径仍需 `subst X:` 才能跑测试（见 HANDOFF 25.3）。

### 5.1 手工验收清单

1. 时间轴 ↔ 曲线来回切 10 次：每次都有网格、通道列表、曲线，不出现空白。
2. 切到别的 dashboard 页再回来：曲线页仍然是曲线页，内容完整。
3. 进/出预合成、切"渲染/编辑"：同上。
4. 曲线区拖到 < 80px：自动回时间轴并提示。
5. 预渲染出一段绿条 → 拖曲线手柄：**预览实时跟着变**（不再显示绿帧旧画面）。
6. 拖手柄松手：不再有"顿一下"。
7. 拖关键帧跨过相邻键：曲线立即重排，不闪回。
8. 500 键通道拖手柄：拖动跟手（曲线区不再随斜率变卡）。

---

## 6. 本轮未做（P2/P3）

- 顶部工具条重构（4 元素）、左侧通道树、右侧上下文面板、右键 5 种键类型；
- 批量时间缩放、吸附三模式、播放头交点/水平参考线；
- 曲线 VBO（等实测确认列跨度不够时再做）；
- 图层级撤销快照（起手仍有整工程 JSON 的开销，但已不在拖动路径上）。

> 数值轴缩放按用户决定保留 **Ctrl+滚轮**（设计文档里写的 Alt+滚轮不采用）。
