# Round 103 — 总变换关键帧、预览吸附、X/Y 缩放锁定

> 版本 `0.99-hotfix67`。按项目约定未启动客户端截图；验证来自模型/纯函数测试和全量
> headless 回归。

## 1. 总变换 / 缩放关键帧

### 1.1 为什么需要两条表示

合成器的渲染真相是**独立 XYZ 轴曲线**（`CompCurves.AXIS_*`）。这保证删除 X 不会连带
删除 Y，也允许不同轴有不同时间。但用户还需要“一次 K 住整个图形”的总开关。

因此新增一个**总变换曲线**（`CompLayer.masterTransformChannel`，`Transform` 关键帧）：

- 它是作者控制层，保存每个总关键帧的完整位置/旋转/缩放姿态；
- 渲染器仍然只读独立 XYZ 曲线；
- 总变换的每次写入都会把姿态展开到所有 XYZ 轴；
- 总变换的每次曲线编辑/删除都会把所有 XYZ 轴重建为它的镜像。

这避免了“把总变换直接当渲染通道”会造成的两个问题：逐轴曲线无法独立、旧工程迁移
会与 legacy Transform 通道混淆。

### 1.2 首次 K 总变换

如果图层已有轴动画：

1. 收集所有轴关键帧 tick 的**并集**；
2. 在每个 tick 用 `transformAt(tick)` 采样出完整姿态，建立一个总变换键；
3. 在播放头插入新的总变换键；
4. `syncAxesFromMaster()` 清空全部 XYZ 曲线，按总变换重建。

所以已有动画不会被静默清空；它被采集为总变换曲线，之后成为同步基线。

### 1.3 修改总变换后

- 原生关键帧抽屉的总变换轨道被移动/删除/改插值时，下一次鼠标释放后：
  - `syncAxesFromMaster()`；
  - 重建 keyframe sheets；
  - `save()`。
- 总变换轨道改一个键、拖时间、删除键，最终都会反映到所有 XYZ 轴。

### 1.4 修改 XYZ 后

- 任意 XYZ 子曲线被修改（原生 dope sheet、曲线编辑器、检查器数值、画布拖拽）后：
  - 指纹检测到 axis 内容变化而 master 未变；
  - 总变换轨道**从抽屉隐藏**，不能直接拖拽；
  - 链接图标出现，状态栏提示“总变换已锁定”。
- 再按总变换 K 或点链接图标：
  - 打开 `UIConfirmOverlayPanel` 风险确认；
  - 确认后打新总变换键/重置全部 XYZ；
  - 取消则什么也不改。

这实现了“调整完 XYZ 后再改总变换必须先确认，确认后所有 XYZ 同步总曲线”。

### 1.5 持久化

`CompLayer.masterTransformData` 使用与 `transformData/opacityData` 相同的
`DataToString` 通道序列化；`saveTracks/loadTracks` 读写，`duplicate()` 通过 Gson
复制，预合成/撤销快照自然携带。`clearAllTransformAxes()` 同时清除总变换。

## 2. 预览对齐吸附

### 2.1 数学

`CompSnap` 是 UI 无关的纯函数：

```text
moving anchors  = {left, centre, right} / {top, middle, bottom}
target anchors  = composition {0, w/2, w} + every other visible layer's three anchors
X / Y independent; nearest delta inside threshold wins.
```

结果包含 `dx/dy`、是否吸附和 guide 坐标。画布只把 `dx/dy` 加到本次拖动位移上，
因此不会改变图层自身数值的语义，也不会在拖动结束后留下偏移。

### 2.2 画布集成

- 开始平移时记录所选图层的**合成空间包围盒并集**；
- 拖动中用 `CompLayerOps.bounds(doc, layer, frame)` 取当前帧的其它图层包围盒
  （新增了带 frame 的重载，避免动画父级把参考线算到第 0 帧）；
- 吸附阈值 = 设置里的屏幕像素 / `fitScale()`；
- 显示黄色对齐线；
- 按住 Alt 临时关闭；设置 → 变换与吸附里可永久关闭/调距离；
- 3D 图层跳过（屏幕拖拽是反投影，平面包围盒吸附会让光标与平面不一致）。

## 3. X/Y 缩放锁定

旧行为：角点默认非等比，Shift 才等比；边缘始终只改一个轴；检查器 X/Y 完全独立。
用户只想改大小时非常容易拉变形。

新增 `CompPrefs.Data.scaleLock`（默认 `true`）：保持**当前** X:Y 比例，而不是强制 1:1。

覆盖入口：

- `CompTransformHandles.scaled(..., lockAspect)`：角点用平均因子，边缘用该边轴的因子，
  但两个 scale 都乘同一因子；
- 2D 画布角点/边缘；
- 3D 视口选择框；
- 3D 缩放 Gizmo 的 X/Y 轴（Z 轴保持单独）；
- 检查器 `setTransformComponent`：先读编辑前 pose，按 `linkedScale` 算另一轴；
- 快速面板 `quickSetScaleX/Y` 复用同一路径。

设置和「运动」页都有开关；关闭后可单独拉伸 X 或 Y。`linkedScale` 有纯函数测试。

## 4. 验证

- `CompLayerMasterTransformTest`：种子并集、K 后全轴同步、轴编辑检测、重置、快照持久化、
  清空重置。
- `CompSnapTest`：合成中心、邻边、双轴独立、阈值、最近目标、包围盒。
- `CompTransformHandlesTest`：锁定的边缘/角点行为、typed linked scale、原有测试保留。
- 全量测试：**175 套件 / 1062 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端；UI 布局/抽屉交互需实机抽检。

## 5. 已知边界

- 吸附只用于 2D 预览平移；3D 世界移动/缩放 Gizmo 不做吸附。
- 总变换轨道显示在原生关键帧抽屉中；XYZ 子曲线仍在曲线编辑器中逐轴显示/编辑。
- 原生抽屉的 Transform 轨道是否能打开值图表取决于 BBS 对该通道的支持；总变换的
  时间/键编辑与同步不依赖图表。
