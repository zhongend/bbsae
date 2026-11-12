# BBS AE · 钢笔工具落地报告（用户需求 6）

> 任务：100% 复刻 AE 钢笔工具，2D/3D 都可用的蒙版路径编辑（形状路径顺带支持）。
> 结论：**P1（绘制状态机）+ P2（编辑）+ 三个既有 bug + P3（2D/3D 投影）+ P4 最低要求（动画路径保护 / 整条路径打关键帧）已落地，62 个新增自动化测试全绿。**
> 未做：AE 单一 Path 属性的关键帧、增删点后的通道重映射、逐点羽化、RotoBezier / Set First Vertex / Free Transform、AE Contents 形状组。详见 §4。

代码行号是 2026-11-12 23:xx 的快照；**所有定位请按文本锚点搜索**。

---

## 1. 文件清单

### 新增（architecture plan §6.1 的三个文件，逻辑全部搬出 19k 行大文件）

| 文件 | 职责 |
|---|---|
| `src/main/java/dev/bbsae/comp/CompPathGeometry.java` | 纯几何：三次贝塞尔求值/切线、段数（open vs closed）、最近点（64 采样+20 次二分）、保形 de Casteljau 分割、射线法内外判定、1/6 平滑切线、45° 约束、镜像/邻居钳制、包围盒。零 MC/GL 依赖，NaN/Inf/退化输入全部有防御。 |
| `src/main/java/dev/bbsae/comp/CompPenSession.java` | 纯状态机：`Target`（适配蒙版/形状路径）、`Listener`（undo/save/状态栏/缓存失效回调）、`Phase`、`Hit`+静态 `hitTest`、press/drag/release/key、闭合/断开/段上加点/转换点/删除/框选/续画。不依赖 UI/GL。 |
| `src/main/java/dev/bbsae/client/ui/UIPenTool.java` | UI 适配层：事件路由、屏幕↔图层局部投影（2D 仿射 / 3D 透视射线-平面 / 3D 工作区 `CompViewportCamera`）、叠加绘制、右键菜单、吸附、undo/存盘接线、目标创建（形状层→形状路径，其它图层→蒙版）。 |

### 修改（均在授权范围内）

| 文件 | 改动 |
|---|---|
| `comp/CompMask.java` | 新增 `public boolean closed = true`（JSON 字段 `"closed"`）；`copy()` 携带。 |
| `comp/CompShape.java` | 新增 `public boolean closed = true`（JSON 字段 `"closed"`）；`copy()` 携带。 |
| `client/render/MaskCache.java` | ① 新增 `isMatte()`：`enabled && closed && points.size() >= 3` 才参与遮罩；② `rasterize` 拆出纯 CPU `alphaFor(...)`（无头测试入口）；③ 闭合路径最后一段补上贝塞尔（原来是直线弦）；④ 修正 world 矩阵 JOML→AWT 转置；⑤ 几何 hash 加入 `closed`。 |
| `client/render/ShapeRasterizer.java` | `PATH` 尊重 `shape.closed`（开放路径不 `closePath`）；闭合路径最后一段补贝塞尔。 |
| `client/render/ShapeCache.java` | 几何 hash 加入 `closed`。 |
| `client/ui/UICompositorPanel.java` | 仅最小插入（见 §2），无重构。 |

### 新增测试（7 个类，62 个用例）

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `comp/CompPathGeometryTest` | 11 | 段数 open/closed、保形分割（每段 t=0.1/0.25/0.5/0.9）、闭合路径首尾段分割、最近点（每段正确 seg/t/距离）、退化输入、内外判定、1/6 切线规则、45° 约束、镜像钳制、包围盒、NaN 防御 |
| `comp/CompPenSessionTest` | 15 | 单击/拖动/点起点闭合、Enter/Esc 开放结束、Shift 45°、段上加点、点击末点续画、删点自动断开、动画路径禁止增删点、手柄镜像/Alt 断开/邻居钳制、角点↔平滑点、闭合/断开各一步 undo、空点请求新路径、命中优先级（顶点>手柄>段）、框选/选择 API |
| `comp/PenPathPersistenceTest` | 5 | `"closed"` JSON 往返、旧 JSON 缺字段→默认 true、字段非通道、`CompStore.snapshot` 含字段、新建默认值 |
| `client/render/MaskPathRasterTest` | 12 | 闭合方框面积、2 点不遮罩、开放路径不遮罩、闭合开关、圆/椭圆面积（贝塞尔）、羽化、扩展、world 平移、hash 含 closed、`maskAt` 保留 closed、旧工程默认、首个相减蒙版语义 |
| `client/render/MaskGlRenderTest` | 7 | **无头 GL**：闭合上传覆盖/内外像素、开放/1/2 点不产生纹理、旋转 world 面积与中心、world 旋转方向（转置回归）、三阶贝塞尔椭圆栅格、缓存命中、羽化+扩展纹理 |
| `client/render/ShapeOpenPathTest` | 6 | open 无 `SEG_CLOSE`、closed 有、开放路径隐式填充、默认/复制、hash 含 closed |
| `client/ui/PenProjectionTest` | 6 | 2D 旋转+非均匀缩放屏幕↔局部往返、2D 前向与 `CompScene.project` 一致、3D 透视倾斜图层往返、3D 图层+正交相机（`layer.threeD` 陷阱）、倾斜卡片透视梯形（近边更高）、相机后不产生垃圾值 |

---

## 2. `UICompositorPanel.java` 的插入点（16 处，均可按锚点搜索）

1. 字段：`private boolean viewportMode;` 之后 → `penTool` / `lastPenView` / `penButton`。
2. 预览右侧工具栏：`this.preview.add(this.cameraSettingsButton);` 之后 → `penButton`（`Icons.ARC`↔`Icons.LINE`，`x=1F,-138`）。
3. 预览 `render()`：`renderMaskOverlay(context)` 前加 `!penTool().isActive()`；3D/2D 分支后、`updateCursor` 前 → `penView()` + `lastPenView` + `renderOverlay`。
4. 预览内 `fitScale()` 之后 → 私有 `penView()`。
5. `applyDrag()` 首行 → `penTool().onDrag(...)`。
6. `subMouseClicked()`：`area.isInside` 之后、cameraTool 分支之前 → `!isPanelResizing() && penTool().onPress(...)`。
7. `subMouseReleased()` 首行 → `penTool().onRelease(context)`。
8. 面板级 `subKeyPressed`：startScreen 块后 → pen `onKey`（Enter/Esc/Delete）；随后 `G` 切换钢笔。
9. `selectLayer()` 早退判定后 → 换层 `penTool.forget()`。
10. `refreshComp()` 设置 doc 后 → `penTool.forget()`。
11. `enter3DSpace()` / `exit3DSpace()` 开头 → `penTool.cancel()`。
12. `addMask()`：`CompStore.uniqueId("mask", ...)` 修 id 撞车；矩形/椭圆“内容矩形”注释 + 回归断言（见 §3-c）。
13. `maskSection()`：每个蒙版 enabled/remove 行之后 → `用钢笔编辑` / `闭合路径·断开路径` / `整条路径打关键帧`。
14. `shapeSection()` 的 `PATH` 分支 → 同样三个入口。
15. 类尾 `/* pen tool bridge */` 区块 → `penTool()`、`penBeginAction`、`penSetScalar`、`penRefreshInspector`、`penSave`、`invalidatePathClosedCaches`、`toggleMaskClosed`、`toggleShapeClosed`、`togglePenTool`。
16. 与摄像机工具互斥：`togglePenTool()` 开启钢笔时若 `cameraToolActive()` 则 `setCameraTool(false)`；钢笔激活期间预览按下（含右键）全部由钢笔接管。

> 没有改 `applyLayout()` / 构造器布局段 / `leftHandle`/`rightHandle`/`timelineHandle`，与 Lead 的 dock 布局重构不冲突（插入点已交底）。

---

## 3. 三个既有 bug 的结论

### (a) `MaskCache` 只要 ≥2 点就遮罩且永远闭合 —— **已修**
- `isMatte(mask)`：`enabled && closed && points.size() >= 3`。
- 效果：钢笔画到第 2 个点时**不会再整层透明**；开放路径（Esc 结束）不参与遮罩，只在叠加层显示为开放轮廓。
- 旧工程兼容：`closed` 默认 `true`，所有旧蒙版行为不变。
- 回归：`MaskPathRasterTest.twoPointPathIsNotAMask / openPathIsNotAMask / openingAClosedPathRemovesItsMatte`、`MaskGlRenderTest.openAndDegeneratePathsProduceNoTexture`。

### (b) `CompShape.PATH` 无 `closed`、渲染强制闭合 —— **已修**
- `CompShape.closed = true`（默认保持旧工程行为）；`ShapeRasterizer.buildPath` 只在 `closed` 时 `closePath()`。
- 开放路径仍然**隐式闭合填充**（AWT `Area`，与 AE 一致），区别在描边端点/接缝与叠加轮廓。
- 回归：`ShapeOpenPathTest`（6 例）。

### (c) `addMask` 默认矩形锚点错位 —— **当前 HEAD 不存在此 bug**
- 复核：矩形 `rectangle(-w·anchorX, -h·anchorY, w, h)`、椭圆中心 `w·(0.5-anchorX)` 都是**内容矩形**，与 `LayerContentRenderer` 的 `x=-w*anchorX` 一致。
- 仍存在的是**id 撞车**（删一个再加会重复），已用 `CompStore.uniqueId("mask", ...)` 修复。
- 已在 `addMask` 加注释 + 由 §1 的持久化/遮罩测试覆盖默认蒙版几何；真机清单里有一张“新增默认矩形蒙版应正好覆盖内容”的截图项。

### 额外发现并修复的 3 个渲染 bug（钢笔上线前必须修，否则“钢笔有 bug”）
1. **闭合路径最后一段被画成直线弦**（`MaskCache` + `ShapeRasterizer`）：只循环到 `n-1` 段再 `closePath()`，最后一个带手柄的段被弦替代 —— 椭圆蒙版少了 1/4 圆弧、钢笔在平滑点闭合的路径出现折角。已补最后一段 `curveTo`。
2. **`MaskCache` 的 world 矩阵 JOML→AWT 字段顺序转置**：旋转图层上的蒙版被镜像旋转，与叠加层/内容不一致。已改为 `(m00, m01, m10, m11, m30, m31)`，并有像素级回归（`worldRotationFollowsTheSceneMatrix`）。
3. **几何 hash 不含 `closed`**（Mask/ShapeCache）：开关闭合会命中旧缓存。已补。

---

## 4. 计划 vs 实际（未完成项）

| 计划项 | 状态 | 说明 |
|---|---|---|
| P1 绘制状态机（落点/拖手柄/闭合/橡皮筋/Enter/Esc/续画/段上加点//**Shift 45°**/撤销粒度） | ✅ | 全部实现；每点/每次闭合各一步 undo |
| P2 编辑（命中/多选/框选/拖点/拖手柄镜像+Alt 断开+邻居钳制/Delete/双击·右键转换点/闭合断开） | ✅ | 框选为 **Ctrl+拖**（AE 的钢笔墨水临时选择工具）；双击 = 右键菜单转换点（见使用说明） |
| P3 2D/3D（普通预览 2D 仿射、3D 透视射线-平面、3D 工作区 `CompViewportCamera`） | ✅ | 关键：用 `CompScene.usePerspective` 而非 `layer.threeD`；3D 图层+正交相机的逆映射用“正交射线-图层平面”而非 4×4 逆 |
| P4 路径动画最低要求（已动画路径**不再**禁止增删点） | ✅（Round 2 已升级） | 见 §9：求值 → CountOp → 回写全部通道，曲线在关键帧处保形 |
| P4 “整条路径打关键帧”助手 | ✅ | 检查器按钮/右键菜单：对 6N 通道在播放头 `insert`，一步 undo |
| P4 求值→CountOp→回写全部关键帧的通道重映射 | ✅（Round 2） | 段上加点/末尾加点/删除点（含 Multi-delete）全支持；详见 §9 |
| 逐点羽化（Mask Feather 工具） | ⏳ 评估完成/未实现 | `CompMask` 只有整条蒙版的 `feather`；无 per-point 字段、渲染器也只做整层 boxBlur。实现需数据+渲染+UI 三处联动（本轮禁止 client/render），见 §12.1 |
| RotoBezier 自动切线 | ⏳ 评估完成/未实现 | `CompMaskPoint.smooth` 字段无消费者；`convertVertex` 只做一次性 1/6 切线。见 §12.3 |
| Convert To Bezier / Free Transform Points | ❌ | 依赖通道重排，后置 |
| Set First Vertex | ✅（Round 3） | 闭合路径右键菜单“设为第一顶点”；几何轮转 + 动画通道/loops 轮转，见 §11 |
| 点通道 KeyframeLoop 保真 | ✅（Round 3） | 索引搬移、新点通道、Set First Vertex 都携带 loops；见 §11 |
| 2D/3D 命中与手势 bug 审查 | ✅（Round 3） | 修复“动画路径叠加/命中/拖动用 base 值而非当前帧求值”“Shift 在屏幕+局部被约束两次”；新增命中/边界/段 t 回归；见 §11 |
| AE Contents 形状组（Group→Path/Fill/Stroke 嵌套） | ❌ | 数据模型是扁平 `shapes`，不做（计划取舍 2-A） |
| 形状检查器逐点数值行折叠（40 点=240 行） | ❌ | 未折叠；钢笔编辑不依赖它 |
| 吸附 | ⚠️ | 顶点吸附（仅其它路径顶点）+ 网格吸附（`showGrid` 时，屏幕空间 6px）；**拖拽锚点时暂不吸附** |
| 光标反馈（Pen+/闭合圆圈） | ⚠️ | 十字光标 + 悬停锚点手形；闭合点有高亮圈，无专用图标资源 |
| 快捷键表注册（`CompKeybinds`） | ⚠️ | 未改 `CompKeybinds`（文件归属外）；`G` 在面板级硬编码；Enter/Esc/Delete 在钢笔激活时优先 |

---

## 5. 剩余风险（按优先级）

1. **`closed` 不在 parked-frame / layer-bake 指纹里**：`CompRenderCache.layer()`、`CompLayerSourceCache` 的 mask/shape fingerprint 目前不含 `closed`（这些文件归 effects/后续）。过渡方案：钢笔切换闭合时 UI 层调用 `CompFrameServer.invalidate() + CompRenderCache.clear() + CompLayerSourceCache.clear() + CompPrecache.markDirty()`（`UIPenTool.closedChanged` / 面板 `invalidatePathClosedCaches`）。**长期必须把 `closed` 加进 signature**，否则 AE 帧服务器/3D 烘焙缓存可能返回旧帧。新持久化字段名：`CompMask.closed`、`CompShape.closed`（JSON key 均为 `"closed"`，默认 `true`）。
2. **渲染画面会变（修的是 bug）**：椭圆/圆形蒙版现在渲染完整 1/4 缺失部分；旋转图层蒙版不再镜像；带手柄的闭合形状路径最后一段变成曲线。真机需逐项截图核对（§6 第 10、11 项）。
3. **3D 工作区钢笔是“新路径”**：`is3DSpace()` 下点击需要先选中图层；平面侧对屏幕/相机后返回 null 并提示，不落点。
4. **性能**：拖拽时每次 `setScalar` → `save()`（去抖 600ms）+ 每帧重栅格化蒙版（`MaskCache` LRU 24，全分辨率）。本期**未做**拖拽降采样/性能基准；50 路径×40 点的叠加层为 O(路径×段×12) 屏幕投影，未测 <0.3ms 预算。
5. **动画路径语义（Round 2 起为“重映射”而非“禁止”）**：增删点会按 §9 把 6N 个标量通道重映射到新索引。保证：**每个既有动画关键帧处曲线逐像素不变**；关键帧之间是“重映射后各通道各自插值”的近似（与 AE 的整条 Path 值插值存在理论差异），若相邻通道是贝塞尔插值、误差可能可见但仅存在于两键之间。KeyframeLoop 自 Round 3 起随通道搬移/复制（见 §11）。
6. **撤销粒度**：拖拽一帧一次 `save()`（进度条去抖），一次拖拽=一次 undo（`beginAction` 只在按下时），与现有蒙版拖拽一致；**动画重映射/设为第一顶点的一次操作 = 一步 undo**（在改通道前只 `beginAction` 一次）。
7. **框选范围**：只对“当前编辑的那一条路径”框选（AE 是同一图层的所有路径）；跨路径多选未做。
8. **右键菜单文案**是 effectcraft 已验证命令集的意译，AE 画布右键逐项文本未真机核对。
9. **`G` 硬编码**：若与其他面板/输入法冲突，可在 dock 重构后迁到 `CompKeybinds`。
10. **没有持久化调试开关/环境变量**：钢笔状态全部在内存（`CompPenSession`），`forget()/cancel()` 在换层/换合成/进出 3D/关闭工具时调用，无残留。
11. ~~检查器“添加/删除点”按钮仍绕过重映射~~ → **Lead 已在 0.93 集成**（`UICompositorPanel` 现在走 `penTool().appendMaskPoint/removeMaskPoint/...`）。
12. **“Preserve Constant Vertex Count”没有开关**：AE 有设置项可关（关时不重映射其他键）。本项目当前**始终开启**（更安全、符合用户预期）；如果以后要 AE 1:1，可在 `CompPrefs`/`CompProject` 加开关（不在本轮写域）。
13. **叠加/命中现在按当前帧求值**（Round 3）：`MaskTarget/ShapeTarget` 每次读取都走 `layer.scalarAt`，因此动画路径的锚点/手柄/吸附都跟随播放头。代价是钢笔激活时每帧对可见路径做 O(点数×6) 次 `scalarAt`（小蒙版可忽略；50×40 路径同时可见时是后台开销，未做性能基准）。
14. **Set First Vertex 只支持闭合路径**（AE 对开放路径也允许“轮转起点”，但开放路径的起点是钢笔续画/闭合手势的语义锚点，轮转容易让用户困惑；当前明确拒绝并提示）。
15. **KeyframeLoop 的语义边界**：loop 是绝对时间块，随通道搬移保留；**新插入点**的 loop 从段起点分量复制（通常同一 block 共享 id）。被删点的 loop 随通道清空；若 loop 只存在于某些分量，新点只继承起点分量那一组。
16. **dope sheet/曲线通道同步需要 Lead 1 行钩子**（Round 4）：增删点/Set First Vertex 后 session 会发 `structureChanged`，`UIPenTool` 通过反射调用计划中的 `penRefreshSheets()`；面板未加桥时 sheet 行列表要到下一次选择变化才刷新（通道数据已正确）。补丁见 §12.5。
17. **残留空通道**：点被删除/索引轮转后，旧通道对象仍留在 `CompLayer.scalarChannels`（无删除 API，CompLayer 不在写域），刷新 sheet 后可能出现空行；建议后续加 `removeScalarChannel`.
18. **逐点羽化 / RotoBezier 仍未实现**（Round 4 评估，见 §12.1/§12.3）：需要数据模型 + client/render 栅格器/自动切线 + 工具 UI，属单独立项。

---

## 6. 真机验证清单（Lead 执行，不要由实现者开客户端）

> 每项建议用现成 `debugRenderCompShot(String name)` 或手动截图；括号内是期望。

1. **2D 蒙版绘制**：solid 图层上选钢笔 → 点 4 下 → 期间图层**始终可见**（第 2 点不消失）→ 点起点闭合（闭合后只显示蒙版内内容）。
2. **橡皮筋/闭合提示**：画到第 3 点移动鼠标应有虚线；靠近起点出现高亮圈。
3. **平滑点与手柄**：第 2 点按下拖动 → 拖出对称手柄；选中点显示入/出柄；按住 Alt 拖一侧 → 另一侧不动；镜像长度不超过相邻锚点距离。
4. **段上加点**：闭合路径段中间钢笔点击 → 点数 +1、轮廓不变（前后截图 diff 应为 0）；**已动画路径**上同样点击 → 点数 +1，检查 0/24/48 帧轮廓与加点前一致（重映射），撤销一步回到原点数。
5. **开放路径**：Esc 结束 → 叠加层为开放轮廓、不参与遮罩；点末点续画；右键“闭合路径”。
6. **形状路径**：形状层用钢笔 → 生成 PATH 形状（不是蒙版）；开放路径描边端点为圆头、无接缝；填充仍在。
7. **旋转/非均匀缩放图层**：2D 图层 rotation 30°、scaleX 1.5 → 钢笔点跟手；**蒙版/形状渲染与叠加层位置一致**（验证转置修复）。
8. **3D 倾斜卡片**：图层 threeD + rotationY 30° + 透视相机 → 画蒙版点跟手；旋转相机后再确认边缘仍贴合。
9. **3D 工作区**：space3D 下选中图层 → 画一段路径；相机后/侧对屏幕时不落点并有提示。
10. **椭圆蒙版回归（旧按钮）**：添加椭圆蒙版 → 应为完整椭圆（此前缺 1/4 圆弧）。
11. **相机工具互斥**：开启 AE 摄像机工具 → 点钢笔按钮 → 摄像机工具自动关闭；钢笔按下/拖动不会被摄像机工具吞掉。
12. **撤销/重做**：落 3 点 + 闭合 + 拖动一个点 → 逐次 Ctrl+Z 精确回退（每点一步、闭合一步、拖动一步）。
13. **保存/重开**：开放/闭合状态保持；中文蒙版名正常；旧工程（无 closed 字段）打开后画面与升级前一致。
14. **动画路径**：检查器“整条路径打关键帧”→ 0/24/48 帧形状连续；在段上加点 → 三个帧的轮廓不变、撤销一步；删除一个点 → 剩余点位置在所有帧保持、撤销一步。
15. **splitter 拖动**：拖动分栏时预览内不落点。
16. **性能**：40 点蒙版拖拽预览不卡顿（>30fps 代理）；退出钢笔后无每帧额外开销。
17. **动画路径 + 检查器加点**：对已打关键帧的蒙版点检查器“添加蒙版点/删除最后一点” → 关键帧处轮廓仍连续（0.93 已集成）。
18. **动画路径的叠加/命中跟随播放头（Round 3 修复）**：给蒙版点打位置关键帧，把播放头移到 24 帧 → 锚点/手柄/橡皮筋出现在渲染出来的位置；点击该位置能选中锚点（修复前命中仍用 t=0 的 base 位置）。
19. **Set First Vertex（Round 3）**：闭合蒙版选中第二个锚点 → 右键“设为第一顶点” → 闭合形状不变、起点圆点移动；动画路径在 0/24 帧轮廓不变；撤销一步。
20. **KeyframeLoop 保真（Round 3）**：给路径点通道设 loop → 在段上加点/删点/Set First Vertex → 跨 loop 区域播放时形状仍重复，新点跟随循环。
21. **Shift 约束在旋转/透视图层（Round 3 修复）**：旋转 30°、非均匀缩放的图层上 Shift+拖动锚点 → 屏幕上沿 45° 方向（修复前先屏幕约束又在局部约束一次，方向偏）。
22. **集成：钢笔中点选工具（Round 4）**：钢笔激活时按 V 或 A → 回到选择工具；Ctrl+A 仍是全选图层。
23. **集成：钢笔中删图层 / 进 precomp（Round 4）**：绘制/编辑中删除当前图层或进入 precomp → 钢笔自动退出编辑并提示，不会往已删除对象写数据；在新合成里可直接继续画。
24. **集成：摄像机工具（Round 4）**：钢笔激活时去点摄像机工具按钮 → 下一次预览帧自动关闭摄像机工具，不会出现“左键同时环绕+画点”。
25. **集成：动画路径增删点后的 dope sheet（Round 4，需 §12.5 钩子集成后验证）**：加点/删点后检查器/关键帧抽屉应出现/移除对应的 `p/<i>` 行，曲线编辑器同步；未集成钩子时形状正确但行列表可能滞后到下一次选择。

---

## 7. 验证命令与结果

统一用 Lead 的串行包装器（避免多人并发互删 `build/classes`）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 compileJava --offline --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
```

钢笔相关 12 个测试类（全量 test 会一并跑）：
`CompPathGeometryTest`、`CompPenSessionTest`、`AnimatedPathRemapTest`、`PathLoopSetFirstVertexTest`、`AnimatedPenHitTest`、`MaskKeyframeResolutionTest`、`PenPathPersistenceTest`、`MaskPathRasterTest`、`MaskGlRenderTest`、`ShapeOpenPathTest`、`PenProjectionTest`、`PenIntegrationRulesTest`。

结果：

- Round 1（2026-11-12 23:1x）`compileJava` / `compileTestJava`：BUILD SUCCESSFUL；当时 7 类 **62 tests / 0 fail**。
- Round 2（2026-11-12 23:4x）新增 `AnimatedPathRemapTest`（12 个用例），与既有 7 类合计 **74 tests / 0 fail**。
- Round 2 全量 `gradle-run.ps1 test`：**97 suites / 565 tests / 0 failures / 0 errors**。
- Round 3（2026-11-13 00:3x）新增 `PathLoopSetFirstVertexTest`（7）、`AnimatedPenHitTest`（2），并扩充 `CompPenSessionTest`（15→18：命中边界、段 t、闭合半径）、`PenProjectionTest`（6→10：旋转/透视命中、屏幕 45° 约束）。10 类合计 **90 tests / 0 fail**（`compileJava`/`compileTestJava` BUILD SUCCESSFUL）。
- Round 3 全量 `gradle-run.ps1 test`：**643 tests / 3 failed / 1 skipped**；3 个失败全部在 effects 队友进行中的新增效果（`EffectP1BatchTest` 默认值可见性、`EffectLibraryNamingTest` 唯一名、`EffectThumbnailResourceTest` 预览图缺失），与钢笔无关，已在本轮汇报里标给 Lead。
- Round 4（2026-11-13 01:0x）新增 `MaskKeyframeResolutionTest`（2：maskAt 对 feather/opacity/expansion 关键帧的 2D/3D 共用解析、逐点羽化缺失守卫）、`PenIntegrationRulesTest`（2：targetExists 失效规则、V/A 切选择工具）；扩充 `CompPenSessionTest`（18→19：`structureChanged` 通知）、`PenProjectionTest`（10→11：3D 透视下手柄拖动投影回光标）。12 类合计 **96 tests / 0 fail**。
- Round 4 全量 `gradle-run.ps1 test`：**117 suites / 699 tests / 0 failures / 0 errors**（含 effects/replay 队友本轮改动）。
- 受 `MaskCache`/`ShapeRasterizer` 改动影响的既有测试回归：`CompLayerSourceCacheTest`、`MaskCombineTest`、`EntityMaskSizingTest`、`ReplayEntityMaskRequestTest`、`CompScenePlaneDepthTest` 全绿。

---

## 8. 钢笔使用说明（可直接进 CHANGELOG / README）

**入口**：预览右上角工具栏，摄像机相关按钮下方第 6 个图标（`Icons.ARC`=钢笔已激活 / `Icons.LINE`=未激活），或按 `G` 切换。

**基本绘制**
- 空白处**单击**：加角点（开放路径；蒙版路径未闭合前不遮罩）。
- 空白处**按下并拖动**：加平滑点，拖出对称贝塞尔手柄。
- **点击起点**（8px 内）：闭合路径并结束本次绘制（蒙版此时开始遮罩）。
- **Enter**：结束路径（保持开放）。**Esc**：结束/取消当前绘制与选择。**V / A**：切回选择工具（关闭钢笔，AE 语义）。
- 钢笔激活期间删图层/进 precomp → 自动退出编辑并提示；摄像机工具会被自动关闭（两者不会同时吃鼠标）。
- **Shift+单击**：新点约束到相对上一点 45°（屏幕空间）。
- 画完后**点击开放路径末点**：从末点续画。**点击开放路径起点**（≥3 点）：闭合。
- **右键**：钢笔菜单（结束路径 / 闭合·断开 / 转角点·转平滑点 / 删除所选 / 整条路径打关键帧 / 退出钢笔）——右键不会再误入 3D 视口。

**编辑**
- 单击锚点选中（Ctrl+单击加选/减选）；**Ctrl+拖动空白**：框选锚点（仅当前路径）。
- 选中后**拖动锚点**移动；**Shift** 约束 45°。
- 拖动**入/出点手柄**：默认镜像另一端；**Alt** 断开对称只动被拖一侧；镜像长度自动钳制在相邻锚点距离内。
- **Delete/Backspace**：删除选中锚点（剩余 <3 点时自动断开闭合）。
- **Alt+单击锚点**：角点↔平滑点互转（1/6 相邻距离规则）。
- **右键 → 设为第一顶点**（闭合路径、选中单个非首锚点）：把该锚点变成路径起点，闭合形状不变；已动画路径的关键帧通道/循环一起轮转。
- 动画路径的锚点/手柄/叠加层始终显示**当前播放头求值后**的位置，命中与拖动与画面一致。

**2D / 3D**
- 2D 图层：屏幕↔图层局部走 `view2D·world` 逆矩阵，旋转/非均匀缩放/父级动画下点都跟手。
- 3D 图层（透视）：鼠标射线与图层平面求交；正交相机下的 3D 图层走正交射线，不会因为“开了 3D”就飞点。
- **3D 工作区**（球图标进入）：同样可画/可编辑，投影走工作区自由相机；平面侧对屏幕时不允许落点（状态栏提示）。

**目标选择**：选中**形状图层**时钢笔画**形状路径**（`PATH` shape）；其它有像素的图层画**蒙版**；摄像机/调整层拒绝。检查器“蒙版”/“形状”区有“用钢笔编辑”“闭合路径/断开路径”“整条路径打关键帧”按钮。

**动画路径（Round 2 起）**：路径已有逐点关键帧时**可以正常增删锚点**：
- 段上加点 → 每个既有动画键处都会把该段按 de Casteljau 分割，曲线在关键帧处保形；
- 末尾加点（续画/检查器）→ 其它关键帧取最后一段的中点（形状不变）；
- 删点 → 删除点在所有键处移除，其余点的关键帧原样保留；
- 以上操作均为**一步 undo**；关键帧之间的过渡是“重映射后逐通道插值”，与 AE 的整条 Path 值插值存在理论差异（仅影响键之间，不影响键帧画面）。
- 检查器“添加蒙版点/添加路径点/删除最后一点”目前仍直接改列表（未走重映射），待 §10 的 1 行补丁集成后同样安全。

---

## 9. Round 2：动画路径通道重映射（P4 收尾）

### 9.1 语义（对齐 AE / effectcraft）

| 操作 | 对此前已有动画的路径 |
|---|---|
| **段上加点**（钢笔点段 / 检查器 API） | 收集受影响段两条端点各 6 个通道的**所有关键时间**（并上播放头）→ 每个时间求值整条路径 → de Casteljau 分割（**播放头用用户点的参数**，其它键固定 **t=0.5**，任何参数都保形且 0.5 不会退化）→ 把新增点/两端点修改后的手柄值写回这些时间 → 未受影响的点通道**整体搬到新索引**（键、tick、插值、手柄、style、duration 全保留）。结果：**每个既有动画键处曲线逐像素不变**。 |
| **末尾加点**（续画 / 检查器） | 播放头落用户点；其它关键时间取**最后一段 t=0.5 的中点**（effectcraft `CountOp::Insert` 规则）→ 曲线在其它键处不变。 |
| **删除点**（Delete / 检查器） | 该点 6 通道清空，后面的点通道**整体下移**；剩余点在所有键处的值保持原样，路径数同步。剩余 <3 点自动断开闭合（原逻辑）。 |
| **无动画路径** | 完全走原路径（不建通道、不改行为），行为与 Round 1 逐字节一致。 |
| **有动画但拿不到通道** | 返回 false + 状态栏提示“路径动画通道不可用”，绝不半改数据。 |

### 9.2 实现位置（本轮写域内）

| 文件 | 改动 |
|---|---|
| `comp/CompPenSession.java` | 新增 `Animation` 接口（hasKey/base/valueAt/keyTicks/moveChannels/clearChannels/setKey）与 `Target.animation()`（默认 null）；`setTick()`；`remapInsertOnSegment` / `remapAppend` / `remapDelete` / `remapTicks` / `remapSplit` / `remapPointAt`；新增 `appendPoint(x,y)`、`deletePoint(index)` 检查器 API；`pressDrawing` 的追加、`insertOnSegment`、`deleteSelection` 三条路径接入重映射。 |
| `client/ui/UIPenTool.java` | 新增抽象 `ChannelAnimation`（唯一知道 `mask/<id>/p/<i>/x…` 与 `shape/<id>/p/<i>/x…` 的地方；`moveChannel` 用 `insert`+`copyOverExtra` 保真搬移）；`MaskTarget` / `ShapeTarget` 懒创建 `animation()`；`onPress`/`onKey`/`activate` 设置 `session.setTick(panel.getFrame())`；新增 4 个检查器入口 `appendMaskPoint/appendShapePoint/removeMaskPoint/removeShapePoint`。 |
| `src/test/java/dev/bbsae/comp/AnimatedPathRemapTest.java` | 12 用例（见 §7）。 |

### 9.3 已知取舍

1. **关键帧之间是近似**（见 §5-5）；键帧处精确。测试用线性插值通道验证：0/6/12/18/24 帧轮廓偏差 < 0.05px。
2. **总是开启**“保持顶点数”语义（AE 有开关）；当前没有关闭入口。
3. ~~点通道上的 KeyframeLoop 会在索引搬移时丢失~~ → **Round 3 已修**：`moveChannel` 搬移时快照并回写 loops，新插入点通道从段起点复制 loops，Set First Vertex 的通道轮转也携带 loops（见 §11）。
4. 重映射只采样**受影响段端点的 8 个通道**的关键 tick（加播放头）。其它点的通道不动，因此它们的关键 tick 不需要进入采样集。
5. 播放头不在任何键上时，新点的通道会多一个“播放头 tick”键；这是有意的（恢复用户点击位置）。
6. **新插入点的 loops 来源**：从段起点（末尾加点时为最后一个旧点）的对应分量通道复制。点通道的 loop 通常由同一 block 共享同一组 id，因此等价；若用户手工给某些分量设置了不同 loop，新点只继承起点分量那一份。

---

## 10. UI 钩子状态

**(A)(B) 检查器“添加/删除点”按钮 —— 已由 Lead 集成（0.93）**

`UICompositorPanel` 现在通过 `penTool().appendMaskPoint/removeMaskPoint/appendShapePoint/removeShapePoint` 调用（锚点：`this.penTool().appendMaskPoint(` / `this.penTool().appendShapePoint(`），已动画路径自动重映射。无需再做。

**Set First Vertex —— 无需 Lead 钩子**

Round 3 已把入口做进钢笔自己的右键菜单（`UIPenTool.showMenu`：闭合路径 + 选中单个非首锚点时显示“设为第一顶点”）。检查器没有“当前选中锚点”的概念，所以不在面板加按钮；如果以后面板要加，只需 1 行：
```java
this.smallButton("设为第一顶点", () -> this.penTool().setFirstVertex(/* 选中锚点索引 */))
```
——但面板目前拿不到锚点索引，需要先把 pen 的 selection 暴露给检查器，属于新 UI 功能，不在本轮范围。

**其余不需要 UICompositorPanel 改动**：钢笔工具/叠加/输入/快捷键/AE 菜单动作都在 `UIPenTool` 内部完成。

---

## 11. Round 3：KeyframeLoop 保真 + Set First Vertex + 2D/3D 命中审查

### 11.1 KeyframeLoop 保真

| 场景 | Round 2 行为 | Round 3 |
|---|---|---|
| 增删点导致通道索引搬移 | `moveChannel` 用 `removeAll()` 清空目标再写 keys —— loop 丢失 | 先快照 `channel.getLoops()`，写完 keys 后 `putLoop(...)` 回写；`Keyframe` 额外字段仍走 `copyOverExtra` |
| 新插入点的全新通道 | 只有 keys，没有 loop | `Animation.copyLoops(from,comp,to,comp)`：从段起点（末尾加点=最后旧点）的对应分量复制 loops；重映射/末尾加点都调用 |
| 删除点 | 被删点通道清空（loop 应消失）✔；存活通道 loop 原本会丢 | 存活通道走 `moveChannel`，loop 保留；被删点 loop 随通道删除 |
| Set First Vertex 通道轮转 | 不支持 | `Animation.rotateChannels(count, shift)` 快照 keys+loops，清空后按 `(i+shift)%n` 回写 |

接口变化：`CompPenSession.Animation` 新增 `copyLoops(...)` / `rotateChannels(...)`；`Target` 新增 `rotatePoints(shift)` / `canRotatePoints()`（默认 false，UI 适配器为 true）。
测试：`PathLoopSetFirstVertexTest` 的 3 个 loop 用例（搬移/新点复制/删除清理）。

### 11.2 Set First Vertex（AE 语义）

- 入口：钢笔右键菜单“设为第一顶点”（闭合路径 + 选中单个非首锚点）。**不需要 Lead 面板钩子**。
- 语义：闭合路径顶点轮转 `new[i]=old[(i+shift)%n]`；几何不变（闭合路径段集合只是循环移位）；
- 已动画路径：先 `animation.rotateChannels(count, shift)`（keys/插值/手柄/style/duration/loops 一起轮转），再 `target.rotatePoints(shift)` 轮转模型列表 —— 两处都用旧索引顺序，因此关键帧处几何逐像素不变；
- 拒绝：开放路径 / <3 点 / index 0（已是第一顶点）/ 目标不支持重排（`canRotatePoints()==false`，在 `beginAction` 之前判断，绝不半改）；
- 一次操作 = 一步 undo；完成后选中新 index 0。
- 测试：`setFirstVertexRotatesAnUnanimatedClosedPath` / `setFirstVertexRotatesAnimatedChannelsAndLoops` / `setFirstVertexRefuses*`。

### 11.3 2D/3D 命中与手势审查（按“假设有 bug”逐项）

| 审计项 | 结论 | 处理/测试 |
|---|---|---|
| **动画路径的叠加/命中/拖拽读取 base 而非当前帧求值** | **真 bug（通过）**：`MaskTarget/ShapeTarget` 读 `mask.points` base，播放头 ≠ 0 时锚点画在 t=0 位置、命中同错位、拖拽首次增量基于错误起点 | **已修**：适配器所有读取改为 `layer.scalarAt(path, base, panel.getFrame())`（`MaskTarget/ShapeTarget.evaluated`），叠加/命中/吸附/拖拽起点全部按当前帧；测试 `AnimatedPenHitTest`（顶点 + 手柄两类） |
| **Shift 45° 在屏幕空间 + 局部空间被约束两次** | **真 bug（通过）**：UIPenTool 先按屏幕约束并转局部，再把 `shift=true` 传给 session，session 又按局部约束 → 旋转/非均匀缩放图层上方向偏离 45° | **已修**：新增静态 `UIPenTool.constrainTo45OnScreen(...)`；应用后向 session 传 `shift=false`；拖拽只在 `session.isDraggingVertices()` 时约束（手柄拖动不受 Shift 影响）；被约束点不再吸附（约束优先）。测试：旋转+非均匀缩放的 2D、yaw30 3D 两层，屏幕角误差 < 0.5°/0.8°，屏幕长度保持 |
| 2D 仿射命中（缩放/旋转/非均匀） | 正确（命中用屏幕投影距离） | `PenProjectionTest.hitTestSelectsWhatIsUnderTheCursorOnARotatedLayer`（顶点 + 段 t） |
| 3D 透视命中（远近） | 正确（9px 是屏幕半径，不随透视缩放） | `PenProjectionTest.hitTestSelectsWhatIsUnderTheCursorOnATiltedCard`（近边/远边各测一次） |
| 段上加点 t 精度 | 正确（局部采样 + 屏幕距离二分细化） | `segmentHitParameterIsUsableForSplitting`：t=0.25 命中误差 < 0.03，可直接用于保形分割 |
| 0/1/2 点 + 闭合开关 | 正确（不抛异常、不返回非法段号；2 点开放=1 段、闭合=2 退化段） | `hitTestHandlesZeroOneAndTwoPointPaths`；`MaskPathRasterTest`/`ShapeOpenPathTest` 覆盖遮罩与栅格退化 |
| 闭合半径 | 正确（8px；超出半径不闭合、继续画） | `closingRequiresTheCursorToBeWithinTheCloseRadius` |
| 橡皮筋 | 正确（从 anchor 到鼠标；拖手柄时不画） | 与 `drawRubberBand`/session `anchorPoint` 契约一致，无独立单测（纯 UI 绘制） |
| Alt 断开手柄 | 正确（Round 1 已测：默认镜像、Alt 只动一侧、镜像邻居钳制） | `CompPenSessionTest.handleDragMirrorsButAltBreaksTheLink` |
| 3D 图层+正交相机的逆映射 | 正确（Round 1 已修，用 `usePerspective` 而非 `layer.threeD`；正交射线-平面） | `PenProjectionTest.threeDLayerUnderOrthoCameraUsesAffineInverse` |

其余未发现可复现的命中/手势问题；`CompPenSession.remapInsertOnSegment/remapAppend/remapDelete` 的边界（0/1/2 点、播放头非关键帧、闭合 wrap 段）均已被 Round 2/3 测试覆盖。

### 11.4 测试增量

- 新增 `PathLoopSetFirstVertexTest`（7）：loop 搬移/新点复制/删除清理、Set First Vertex 未动画/动画+loop/拒绝（开放、<3 点、index0、不支持重排）。
- 新增 `AnimatedPenHitTest`（2）：动画路径顶点/手柄命中必须用当前帧求值。
- `CompPenSessionTest` 15→18：命中边界（0/1/2 点、闭合退化）、段命中 t、闭合半径。
- `PenProjectionTest` 6→10：旋转 2D 命中、透视 3D 命中、屏幕 45° 约束（2D 旋转 + 3D 透视）。
- 钢笔相关 10 类合计 **90 tests / 0 fail**；全量 643 tests 中仅 effects 队友进行中的 3 个效果库用例失败（与本任务无关）。

### 11.5 已知取舍（Round 3 新增）

1. Set First Vertex 只支持闭合路径（见 §5-14）。
2. 动画路径叠加/命中按帧求值带来每帧 `scalarAt` 开销（小路径可忽略；未做性能基准，见 §5-13）。
3. 新插入点的 loop 从段起点分量继承（同一 block 通常一致，见 §5-15）。
4. 本轮仍未做：逐点羽化、RotoBezier、Free Transform、Contents 组、跨路径框选、拖拽性能降采样。

---

## 12. Round 4：逐点羽化/RotoBezier 评估 + 集成 bug 审查

### 12.1 逐点羽化（per-point feather）现状评估 —— **未实现，本轮不硬做**

| 问题 | 结论与证据 |
|---|---|
| 数据模型有逐点羽化字段吗？ | **没有**。`CompMask.CompMaskPoint` 只有 `x/y/inX/inY/outX/outY/smooth`（`comp/CompMask.java`）；`CompMask` 上只有一个 `float feather`。`MaskKeyframeResolutionTest.featherIsOneScalarPerMaskNotPerPoint` 用反射把“没有 per-point feather 字段”钉住（未来谁加了字段，这个守卫会失败提醒接渲染/检查器）。 |
| 渲染支持逐点羽化吗？ | **不支持**。`MaskCache.alphaFor` 用 `mask.feather` 对整张 mask alpha 做一次 `boxBlur`（半径 = round(feather)），与点位置无关；没有沿路径的逐点羽化宽度插值，也没有 `FeatherPoint` 数据。 |
| AE / Alight Motion 的语义 | AE 的 Mask Feather 是**逐点**的（每个锚点两侧各有羽化向量，路径内部线性插值）；Alight Motion 也提供逐边/逐点羽化。本项目当前是“整条蒙版一个羽化半径”，只能做均匀柔边。 |
| 要真正实现需要什么 | ① `CompMaskPoint` 增加羽化数据（如 `featherIn/featherOut` 或独立 `List<FeatherPoint>`）+ 序列化/迁移；② 新增标量通道 `mask/<id>/p/<i>/feather*` 并让 remap/Set First Vertex 一起轮转；③ `MaskCache` 改为“沿轮廓逐段羽化”（AWT 没有直接支持，需要导出 alpha 后用距离场/双轮廓填充，或在 3D 烘焙时用 SDF），是 client/render 的大改；④ 检查器/钢笔工具加 Feather 工具与叠加层。 |
| 本轮决定 | **不做**（写域禁止 client/render 大改；数据模型 + 渲染 + UI 三处联动，属单独立项）。建议作为需求 6 的后续条目，验收时按 §1.2 的 C 类标注“明确不做”。 |

> 顺带评估：`CompMaskPoint.smooth` 字段存在但**没有任何代码读取**（RotoBezier 的雏形）。RotoBezier（自动切线）同样未实现：没有 UI 开关、`convertVertex` 只做 1/6 规则的一次性转换，移动锚点不会重算切线。见 §12.3。

### 12.2 mask expansion / opacity 关键帧在 2D / 3D 路径都生效 —— **确认生效**

证据链（只读代码，不在本轮写域内改动）：

| 路径 | 调用 | 解析 |
|---|---|---|
| 2D 普通图层 | `client/render/CompRenderer.java:738`（`maskModel`/`context.frame`）和图层内遮罩 pass `:1400` | `MaskCache.get(layer, w, h, world, frame, scale)` → `MaskCache.java:72` `layer.maskAt(mask, frame)` |
| 3D 烘焙图层 | `client/render/CompLayerSourceCache.java:511`（`parent.frame`） | 同上 `MaskCache.get(...)` → `maskAt` |
| 轨道遮罩（track matte） | `CompRenderer.java:868` | 同上 |
| 解析实现 | `comp/CompLayer.java:1226` `maskAt` | 对 `mask/<id>/{opacity,feather,expansion}` 逐个 `scalarAt(frame)`，点坐标/手柄也按帧求值；结果拷贝，不动原数据。 |

`MaskCache.get` 在解析后把 `feather/expansion` 乘 raster scale（3D 代理分辨率），2D/3D 两条路径行为一致。回归：`MaskKeyframeResolutionTest`（0/12/24 帧的 feather/opacity/expansion 求值 + 不污染原 mask + 关闭状态拷贝）。
结论：**关键帧化蒙版标量在 2D/3D 都生效，无 bug**。`mask.enabled/mode/inverted` 不是动画通道（有意设计）。

### 12.3 RotoBezier / 3D 透视下手柄切线 —— 拖拽投影正确；RotoBezier 本身未实现

- **屏幕拖动 → 局部切线**：3D 透视下把鼠标映射回图层平面一次（`screenToLocal`），手柄向量按局部空间存；因此把手柄端点再投影回屏幕必须正好落在鼠标下。新增 `PenProjectionTest.handleDragOnATiltedCardProjectsBackUnderTheCursor`：yaw 30° 卡片，投影误差 < 0.05px；镜像手柄在局部空间严格相反，屏幕方向点积 < 0（透视会让两侧长度看起来不同，符合 AE 的局部空间语义）。
- **平滑点切线规则**：`convertVertex`/`smoothTangents` 用 1/6 相邻距离（effectcraft 同规则）；Round 3 已按帧求值，动画路径下切线基于当前帧邻居。
- **RotoBezier 自动切线：未实现**。`CompMaskPoint.smooth` 无消费者；右键“转为平滑点”只是一次性写切线，移动锚点不会重新自动切线（AE RotoBezier 会）。原因：需要每帧/每次拖动的切线重算 + UI 开关 + 与动画通道写入的交互，属新功能而非 bug。建议后续条目。

### 12.4 集成 bug 审查（能修的已修）

| # | 审查项 | 结论 | 处理/证据 |
|---|---|---|---|
| 1 | 钢笔 + 选择工具切换 | **缺口**：此前只有 `G` 关钢笔；用户按 V/A 不会退回选择工具（面板的 `tool.select` 只设状态） | **已修**：`UIPenTool.onKey` 在钢笔激活时消费无修饰的 V/A → 关闭钢笔并提示；`isSelectionToolSwitchKey` 可单测（`PenIntegrationRulesTest`）。带修饰键仍走快捷键表（Ctrl+A 全选等） |
| 2 | 钢笔过程中切图层 | 已有：`selectLayer` → `penTool.forget()`（Round 1） | 新增 `ensureSessionValid()` 防御（见 #3） |
| 3 | 钢笔过程中**删除图层/删除路径/切 precomp** | **真 bug**：`deleteLayer` 用 `setSingleSelection`（不经过 `selectLayer`）；`enterPrecomp/exitPrecomp` 直接换 `this.doc` —— 都不会通知钢笔，session 持有已删除/旧合成对象 | **已修**：静态 `UIPenTool.targetExists(doc, layer, mask, index)` + `ensureSessionValid()`（doc 内 layer 存在 + 槽位存在 + PATH 类型），在 `onPress/onDrag/onKey/renderOverlay` 入口调用；失效则 `forget()` + 状态栏提示。测试 `PenIntegrationRulesTest.targetStopsExistingWhenLayerOrPathGoesAway` |
| 4 | 钢笔与摄像机工具同时激活 | **真 bug**：面板 `toggleCameraTool` 不检查钢笔；两者同开会双吃鼠标（摄像机工具轮询在钢笔之前） | **已修**：`enforceToolExclusivity()`（钢笔激活时若 cameraToolActive → `setCameraTool(false)`），在 `renderOverlay`（每帧）/`onPress`/`onKey`/`onDrag` 调用；开启钢笔时本来就互相 disarm（Round 1） |
| 5 | precomp 内外 | 同 #3 的 doc 身份校验覆盖；进 precomp 后第一次 overlay 即失效旧 target；在新合成里可直接继续画 | 报告 §12.4-3 测试（换 doc 场景） |
| 6 | Esc / Enter / G / F 冲突 | Esc/Enter 在绘制时优先（Round 1）；G 面板级切换钢笔（绘制中按 G = 取消绘制但保留已落点，每点已 save）；**F 不消费**：F 是聚焦模式/摄像机飞行（需选中摄像机层，钢笔拒绝摄像机层，两者不会同时进入） | 无需改；V/A 新增见 #1。若未来 F 改成常用键，可在 UIPenTool.onKey 按需消费（一行） |
| 7 | 动画路径增删点后 dope sheet / 曲线通道同步 | **部分修复，需要 Lead 1 行钩子**：`CompPenSession.Listener` 新增 `structureChanged()`，session 在 段上加点/末尾加点/删除点/Set First Vertex 后通知；`UIPenTool` 收到后 `structureDirty=true` 并调用 `refreshKeyframeSheets()`（反射查找计划中的 `penRefreshSheets` 桥）。**面板目前没有这个桥**，所以 sheet 行列表要等下一次选择变化才刷新（通道数据本身已正确）。另：被搬空的旧通道对象仍留在 `CompLayer.scalarChannels`（CompLayer 无删除 API，不在写域），刷新后可能出现空行/工程里残留空通道。 | 补丁见 §12.5 |
| 8 | Set First Vertex 后选中索引 | 正确：rotate 后 `selection={0}`、`anchorPoint=count-1`；右键菜单与后续拖动/删除都按 0 号点工作；`PathLoopSetFirstVertexTest` 断言 | 无 bug |
| 9 | 钢笔中删除**当前路径所属蒙版**（不是图层） | 同 #3：`ensureSessionValid` 检查 mask/shape 槽位存在；失效即 forget | 已修 |

### 12.5 需要 Lead 的 1 行钩子（第 7 项的完整修复）

`UICompositorPanel` 的 pen bridge 区块（`/* pen tool bridge */`，`penRefreshInspector()` 附近）加：

```java
    /** Rebuilds the dope sheet / curve editor after the pen changed the channel structure. */
    void penRefreshSheets()
    {
        this.refreshSheets();
    }
```

`UIPenTool.refreshKeyframeSheets()` 已经通过反射查找这个方法：加上即生效，钢笔/编辑器两侧无需再改。未加时无副作用（catch NoSuchMethodException）。

### 12.6 Round 4 未做项与原因

| 项 | 原因 |
|---|---|
| 逐点羽化（数据 + 渲染 + UI） | 需要 client/render 的栅格器大改 + 数据模型迁移 + 检查器/工具 UI；本轮禁止改 client/render，且是三处联动的新功能，已出评估（§12.1） |
| RotoBezier 自动切线 | 需要 UI 开关 + 每帧切线重算 + 动画写入交互；`smooth` 字段是雏形但无消费者，属新功能（§12.3） |
| 空通道清理（删除点后残留的 `scalarChannels` 空条目） | `CompLayer` 无 remove 通道 API，且 CompLayer 不在写域；建议后续加 `removeScalarChannel(path)` 并在 remap/rotate 后调用 |
| F 键冲突处理 | 当前 F 不会造成双触发（见 §12.4-6）；若用户反馈再消费 |
| 3D 工作区内的 precomp/摄像机混合场景 | 依赖 #3/#5 的同一套校验；无独立代码路径 |

