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
| P4 路径动画最低要求（已动画路径禁止增删点并提示） | ✅ | 段上加点 / Delete 均拦截，状态栏说明 |
| P4 “整条路径打关键帧”助手 | ✅ | 检查器按钮/右键菜单：对 6N 通道在播放头 `insert`，一步 undo |
| P4 求值→CountOp→回写全部关键帧的通道重映射 | ❌ | 本期不做（计划取舍 4-A 的 B 方案，风险高，见 §5-6） |
| 逐点羽化（Mask Feather 工具） | ❌ | 数据模型缺 `FeatherPoint`，单独立项 |
| RotoBezier / Convert To Bezier / Set First Vertex / Free Transform Points | ❌ | 依赖通道重排，后置 |
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
5. **动画路径语义**：逐点通道模型，增删点会错位 → 已禁止；拖动/手柄/转换点安全（走 `setScalar` 打键）。给已有动画的路径“整条路径打关键帧”会与现有通道合并（插入同帧键），符合预期但需真机确认。
6. **撤销粒度**：拖拽一帧一次 `save()`（进度条去抖），一次拖拽=一次 undo（`beginAction` 只在按下时），与现有蒙版拖拽一致；快速连续落点会被 1 秒采样合并的风险与旧行为相同。
7. **框选范围**：只对“当前编辑的那一条路径”框选（AE 是同一图层的所有路径）；跨路径多选未做。
8. **右键菜单文案**是 effectcraft 已验证命令集的意译，AE 画布右键逐项文本未真机核对。
9. **`G` 硬编码**：若与其他面板/输入法冲突，可在 dock 重构后迁到 `CompKeybinds`。
10. **没有持久化调试开关/环境变量**：钢笔状态全部在内存（`CompPenSession`），`forget()/cancel()` 在换层/换合成/进出 3D/关闭工具时调用，无残留。

---

## 6. 真机验证清单（Lead 执行，不要由实现者开客户端）

> 每项建议用现成 `debugRenderCompShot(String name)` 或手动截图；括号内是期望。

1. **2D 蒙版绘制**：solid 图层上选钢笔 → 点 4 下 → 期间图层**始终可见**（第 2 点不消失）→ 点起点闭合（闭合后只显示蒙版内内容）。
2. **橡皮筋/闭合提示**：画到第 3 点移动鼠标应有虚线；靠近起点出现高亮圈。
3. **平滑点与手柄**：第 2 点按下拖动 → 拖出对称手柄；选中点显示入/出柄；按住 Alt 拖一侧 → 另一侧不动；镜像长度不超过相邻锚点距离。
4. **段上加点**：闭合路径段中间 Ctrl/钢笔点击 → 点数 +1、轮廓不变（前后截图 diff 应为 0）；动画路径上点击 → 状态栏提示“已有动画关键帧…”，点数不变。
5. **开放路径**：Esc 结束 → 叠加层为开放轮廓、不参与遮罩；点末点续画；右键“闭合路径”。
6. **形状路径**：形状层用钢笔 → 生成 PATH 形状（不是蒙版）；开放路径描边端点为圆头、无接缝；填充仍在。
7. **旋转/非均匀缩放图层**：2D 图层 rotation 30°、scaleX 1.5 → 钢笔点跟手；**蒙版/形状渲染与叠加层位置一致**（验证转置修复）。
8. **3D 倾斜卡片**：图层 threeD + rotationY 30° + 透视相机 → 画蒙版点跟手；旋转相机后再确认边缘仍贴合。
9. **3D 工作区**：space3D 下选中图层 → 画一段路径；相机后/侧对屏幕时不落点并有提示。
10. **椭圆蒙版回归（旧按钮）**：添加椭圆蒙版 → 应为完整椭圆（此前缺 1/4 圆弧）。
11. **相机工具互斥**：开启 AE 摄像机工具 → 点钢笔按钮 → 摄像机工具自动关闭；钢笔按下/拖动不会被摄像机工具吞掉。
12. **撤销/重做**：落 3 点 + 闭合 + 拖动一个点 → 逐次 Ctrl+Z 精确回退（每点一步、闭合一步、拖动一步）。
13. **保存/重开**：开放/闭合状态保持；中文蒙版名正常；旧工程（无 closed 字段）打开后画面与升级前一致。
14. **动画路径**：检查器“整条路径打关键帧”→ 0/24/48 帧形状连续；之后尝试加点被拒绝。
15. **splitter 拖动**：拖动分栏时预览内不落点。
16. **性能**：40 点蒙版拖拽预览不卡顿（>30fps 代理）；退出钢笔后无每帧额外开销。

---

## 7. 验证命令与结果

统一用 Lead 的串行包装器（避免多人并发互删 `build/classes`）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 compileJava --offline --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain `
  --tests "dev.bbsae.comp.CompPathGeometryTest" `
  --tests "dev.bbsae.comp.CompPenSessionTest" `
  --tests "dev.bbsae.comp.PenPathPersistenceTest" `
  --tests "dev.bbsae.client.render.MaskPathRasterTest" `
  --tests "dev.bbsae.client.render.MaskGlRenderTest" `
  --tests "dev.bbsae.client.render.ShapeOpenPathTest" `
  --tests "dev.bbsae.client.ui.PenProjectionTest"
```

结果（2026-11-12 23:1x）：

- `compileJava` / `compileTestJava`：BUILD SUCCESSFUL。
- 上述 7 个类：BUILD SUCCESSFUL，**62 tests / 0 failures / 0 errors**：
  - CompPathGeometryTest 11、CompPenSessionTest 15、PenPathPersistenceTest 5、
    MaskPathRasterTest 12、MaskGlRenderTest 7、ShapeOpenPathTest 6、PenProjectionTest 6。
- 受 `MaskCache`/`ShapeRasterizer` 改动影响的既有测试回归：`CompLayerSourceCacheTest`、`MaskCombineTest`、`EntityMaskSizingTest`、`ReplayEntityMaskRequestTest`、`CompScenePlaneDepthTest` 全绿。
- 全量 `gradle-run.ps1 test`：Lead 侧 89 suites / 500 tests，剩余 6 个失败均在 effects 的 REPLICATE/padding 代码（与本任务无关）；钢笔相关无失败。

---

## 8. 钢笔使用说明（可直接进 CHANGELOG / README）

**入口**：预览右上角工具栏，摄像机相关按钮下方第 6 个图标（`Icons.ARC`=钢笔已激活 / `Icons.LINE`=未激活），或按 `G` 切换。

**基本绘制**
- 空白处**单击**：加角点（开放路径；蒙版路径未闭合前不遮罩）。
- 空白处**按下并拖动**：加平滑点，拖出对称贝塞尔手柄。
- **点击起点**（8px 内）：闭合路径并结束本次绘制（蒙版此时开始遮罩）。
- **Enter**：结束路径（保持开放）。**Esc**：结束/取消当前绘制与选择。
- **Shift+单击**：新点约束到相对上一点 45°（屏幕空间）。
- 画完后**点击开放路径末点**：从末点续画。**点击开放路径起点**（≥3 点）：闭合。
- **右键**：钢笔菜单（结束路径 / 闭合·断开 / 转角点·转平滑点 / 删除所选 / 整条路径打关键帧 / 退出钢笔）——右键不会再误入 3D 视口。

**编辑**
- 单击锚点选中（Ctrl+单击加选/减选）；**Ctrl+拖动空白**：框选锚点（仅当前路径）。
- 选中后**拖动锚点**移动；**Shift** 约束 45°。
- 拖动**入/出点手柄**：默认镜像另一端；**Alt** 断开对称只动被拖一侧；镜像长度自动钳制在相邻锚点距离内。
- **Delete/Backspace**：删除选中锚点（剩余 <3 点时自动断开闭合）。
- **Alt+单击锚点**：角点↔平滑点互转（1/6 相邻距离规则）。

**2D / 3D**
- 2D 图层：屏幕↔图层局部走 `view2D·world` 逆矩阵，旋转/非均匀缩放/父级动画下点都跟手。
- 3D 图层（透视）：鼠标射线与图层平面求交；正交相机下的 3D 图层走正交射线，不会因为“开了 3D”就飞点。
- **3D 工作区**（球图标进入）：同样可画/可编辑，投影走工作区自由相机；平面侧对屏幕时不允许落点（状态栏提示）。

**目标选择**：选中**形状图层**时钢笔画**形状路径**（`PATH` shape）；其它有像素的图层画**蒙版**；摄像机/调整层拒绝。检查器“蒙版”/“形状”区有“用钢笔编辑”“闭合路径/断开路径”“整条路径打关键帧”按钮。

**动画限制（本期）**：路径已有逐点关键帧时**禁止增删锚点**（会打乱 `p/<i>` 通道索引），状态栏会说明；拖动/手柄/转换点/整条打关键帧仍可用。
