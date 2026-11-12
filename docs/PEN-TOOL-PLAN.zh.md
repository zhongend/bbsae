# BBS AE · 钢笔工具 100% 复刻 AE · 技术调研与落地方案

> 需求编号：用户需求 6「钢笔工具 100% 复刻 AE，2D/3D 都可用，100% 没 bug」。
> 本文只做调研与方案，**未修改 `src/` 任何源码**。临时文件仅写在 `_research/team/`（如需要）。
> 参考实现：`_research/effectcraft/`（Rust，AE 复刻项目，已本地克隆）。
> 代码行号抓取时间：2026-11-12 22:40–22:50（`UICompositorPanel.java` 当时正被其他协作者并发修改，
> 行号会漂移；**所有锚点都给了原文片段，请按文本搜索定位**）。

---

## 0. TL;DR（给 Lead 的 5 条）

1. **能做的"100% 复刻"是行为语义，不是数据模型。** 本项目蒙版/路径是「逐坐标标量通道」（`mask/<id>/p/<i>/x|y|inX|inY|outX|outY`），
   而 AE 是「一条 Path 属性 + 整条路径关键帧」。逐点通道 + 顶点增删 = 通道索引错位，必须专门处理；其余交互（单击/拖动/闭合/加点/转换/镜像/断开）
   都可以在现有模型上做到与 AE 一致。
2. **交互层几乎从零开始**：`UICompositorPanel` 目前只有「拖点 / 拖手柄」（`maskHit` → `applyMaskDrag`），
   **没有加点、没有闭合、没有橡皮筋、没有钢笔状态机、没有段命中、没有 3D 工作区编辑**。但**投影基础设施已具备**
   （`CompScene.project` / `CompScene.world` / `CompCamera.view2D|unproject` / `CompCamera.unproject`），3D 图层的屏幕↔局部换算
   在 `screenToLayerLocal` 里已有正确实现，可直接复用/外提。
3. **必须顺手修的 3 个既有 bug（否则钢笔一上线就"有 bug"）**：
   ① `MaskCache` 只要求 `points.size() >= 2` 就参与遮罩，2 点路径面积=0 → 画第 2 个点时整层会**变透明**（effectcraft 修过同名 bug：#290）；
   ② `CompShape.PATH` 永远 `closePath()`，没有 `closed` 字段 → 开放形状路径不可能实现；
   ③ `addMask` 的默认矩形用 `layer.width * (0.5F - anchorX)` 定位，锚点=0.5 时从 `(0,0)` 起画，与内容 `[-w/2, w/2]` 错开半层。
4. **推荐架构**：纯逻辑全部放进 3 个新文件 —— `comp/CompPathGeometry.java`（贝塞尔/分割/命中/镜像，零依赖可单测）、
   `comp/CompPenSession.java`（状态机，纯数据 + `PathTarget` 适配器，可无 UI 单测）、`client/ui/UIPenTool.java`（事件/叠加绘制/undo 接线）。
   对 `UICompositorPanel.java` 只做 **9 处、每处 1–8 行** 的插入（清单见 §6.3）。
5. **"100% 没 bug"在本项目的现实验收标准**见 §8.5：行为表 100% 有自动化测试、undo/存盘往返无损、2D/3D 投影与栅格偏差 ≤1px、
   性能预算（50 路径×40 点叠加 <0.3ms/帧；拖拽时蒙版降到 1/2 分辨率栅格）、以及真机截图清单一次性通过。**做不到"零 bug"，
   只能做到"零已知偏差 + 零崩溃路径 + 可回归"**，这一点必须让用户确认。

---

## 1. 目标、边界与"100% 复刻"的定义

### 1.1 三条硬边界（先讲清楚，避免后期争议）

| 边界 | AE 的做法 | 本项目的约束 | 影响 |
|---|---|---|---|
| 路径的存储 | 一条 `Mask Path` / `Path 1` 属性，值是 `{vertices, inTangents, outTangents, closed}` | 蒙版点=6 个标量通道/点；形状点=6 个标量通道/点 | 「整条路径一个关键帧」不可得，只能"给 6N 个通道同一时间打键" |
| 形状组 | Contents 树：Group→Path/Fill/Stroke/Transform，可嵌套、可多个 Path | `CompLayer.shapes` 是**扁平列表** + 逐 shape 布尔运算（`CompShape.operator`） | AE 的 Contents 组不可得，除非新数据模型（见 §9 取舍 2） |
| 蒙版羽化 | 逐点羽化（Mask Feather 工具、Feather Point） | 只有整条蒙版的 `feather` 标量 | 羽化工具不在本期范围（明确列为"未覆盖"） |

### 1.2 「100% 复刻」的可执行定义

- **A 类（必须 1:1）**：§2 行为表里所有标 ✅ 的条目，逐条有自动化测试，且交互手感（阈值、光标、橡皮筋、约束）与 AE 一致。
- **B 类（语义等价、实现不同）**：路径动画 = 多点通道键；形状组 = 扁平 shape 列表。**必须由用户确认接受**。
- **C 类（明确不做）**：逐点羽化、RotoBezier 自动切线（可后置）、Content-Aware/跟踪、形状组嵌套、轨道遮罩联动。

---

## 2. AE 钢笔工具完整行为规范（第一性原理）

来源标注：
- 【AE 官方】= Adobe 帮助/参考手册；本轮实际抓取的页面见 §10 参考链接（页面正文被导航截断，故只在能对应上的条目标注）。
- 【EC 代码】= `_research/effectcraft/crates/...`，行号见括号。
- 【EC 测试】= effectcraft 的测试用例（这些用例本身就是按 AE 行为写的）。
- 【待真机】= 我有把握但不允许"凭空猜"的细节，列入 Lead 真机核对清单。

### 2.1 工具族与快捷键

AE 的钢笔是一个 **5 槽工具组**（工具栏长按展开）【EC 代码 `crates/ui-egui/src/state.rs:59`】：

| 工具 | AE 快捷键 | 本项目建议 | 说明 |
|---|---|---|---|
| Pen（钢笔） | `G`【待真机】 | `G`（走 `CompKeybinds`，两套方案都加） | 绘制/续画/闭合 |
| Add Vertex（添加顶点） | `+`【待真机】 | `=`/`+` | 在段上加点的专用工具 |
| Delete Vertex（删除顶点） | `-`【待真机】 | `-` | 点谁删谁 |
| Convert Vertex（转换顶点） | `Shift+C`【待真机】 | 面板按钮 + `Alt` 临时切换 | 角点↔平滑点 |
| Mask Feather（蒙版羽化） | `Ctrl+Shift+F`?【待真机】 | **不做** | 需要逐点羽化数据模型 |

**临时切换（AE 官方明确）**：钢笔激活时按住 `Ctrl`（macOS `Cmd`）= 临时 Selection 工具；按 `V` 切到选择工具
（来源：AE 参考手册 "To temporarily switch from the Pen tool to the Selection tool, press V or Ctrl (Windows) or Command (Mac OS)"）。
钢笔激活时按住 `Alt` = 临时 Convert Vertex 工具（【EC 代码】`viewer.rs:1421`：`Tool::Selection | Tool::PenConvert if (mods.alt || tool == Tool::PenConvert)`）。

### 2.2 绘制状态机（核心，逐条编号，测试 ID = P-xx）

| ID | 触发 | 期望行为 | 实现要点（AE/EC 依据） |
|---|---|---|---|
| P-01 | 钢笔在空白处**单击** | 新建路径（蒙版或形状路径，见 P-20），落一个**角点**（in=out=0） | 【EC 代码】`mask.new`(1 点) / `shape.newPath`；`viewer.rs:2269-2292` |
| P-02 | 钢笔在空白处**按下并拖动** | 落一个**平滑点**：顶点固定在按下处，拖出对称手柄 `out = cursor - vertex`，`in = -out` | 【EC 代码】`viewer.rs:1775-1783` 的 `Gesture::Pen`（顶点不动，只写 in/out）；`mask.setVertex` |
| P-03 | 继续单击/拖动 | 追加顶点到路径**末尾**（开放路径） | 【EC 代码】`viewer.rs:2216-2245` |
| P-04 | 绘制中鼠标移动 | 从**最后一个顶点**到光标的**橡皮筋**预览线 | 【EC 代码】`viewer_overlays.rs:207-212` |
| P-05 | 点击**起点**（屏幕距离 <8px，且 n≥2） | **闭合**路径并结束本次绘制 | 【EC 代码】`viewer.rs:2225-2231`（`mask.setClosed`）；【EC 测试】`ui_viewer.rs:804` |
| P-06 | 按 `Enter` | 结束路径（**保持开放**），不失焦、不退出面板 | 【EC 代码】`viewer.rs:987`（`Escape || Enter` 都清 pen 状态）【待真机：AE 里 Esc 与 Enter 是否有差别】 |
| P-07 | 按 `Esc` | 结束路径（保持开放）；若**当前点还没落盘**（0 点）则取消 | 同上 |
| P-08 | 绘制中点击**任一路径段** | 在该处**插入顶点**（形状不变，de Casteljau 分割） | 【EC 代码】`viewer.rs:2257-2259` → `mask.insertVertex`；`mask.rs:389-416` |
| P-09 | 点击**开放路径的末顶点** | **续画**该路径（例如 Esc 结束后） | 【EC 代码】`viewer.rs:2248-2251` + `viewer_overlays.rs:113-119`；【EC 测试】`viewer_overlays.rs:684` |
| P-10 | 按住 `Shift` 单击 | 新段约束到相对上一顶点 **45° 倍数**（水平/垂直/对角） | 【EC 代码】`constrain_45` `viewer.rs:2302-2312`；【EC 测试】`viewer.rs:2719`、`ui_viewer.rs:769`（"#1544"） |
| P-11 | 按住 `Ctrl` 单击 | 临时=选择工具：命中顶点/手柄则进入编辑，不落新点 | 【AE 官方】+【EC 代码】`viewer.rs:1421` |
| P-12 | 按住 `Alt` 单击**顶点** | 临时=转换顶点：角点→平滑（切线沿邻居，各 1/6 距离）；平滑→角点 | 【EC 代码】`mask.rs:343-386`（`convert_vertex`，含 `d=[10,0]` 的退化兜底） |
| P-13 | 钢笔悬停在段上 | 光标变成 **Pen+**（Add Vertex 光标） | 【EC 代码】`viewer.rs:2170-2180`、`1320-1326` |
| P-14 | 钢笔悬停在顶点/手柄上 | 光标变成钢笔/转换笔（AE 另有"闭合"圆形光标） | 【EC 代码】同上；闭合光标 EC 未实现，属可选增强 |
| P-15 | 绘制中切工具/切图层/切合成 | 结束绘制（不崩、不留半截状态） | 【EC 代码】`viewer.rs:985-991` |
| P-16 | 绘制中切到 3D 工作区或隐藏预览 | 结束绘制并释放状态 | 同 P-15 |
| P-17 | 目标图层被锁定/隐藏/删除 | 禁止落点并给状态栏提示；已在拖拽中的要能安全终止 | 本项目 `maskHit` 已检查 `layer.locked` |
| P-18 | 目标层是摄像机/灯光 | **拒绝**（没有像素） | AE 语义；本项目 `camera` 层不参与合成 |
| P-19 | 落点时图层有非均匀缩放 / 旋转 / 3D 透视 | 顶点的**屏幕位置**落在光标处（局部坐标由逆矩阵算出） | §5 公式 |
| P-20 | 选中 shape 图层且"工具创建形状/蒙版"=Mask | 钢笔在形状层上画**蒙版**；否则画**形状路径**；**没有选中层**时新建形状图层 | 【EC 代码】`shape_tool.rs`（`creates_mask`）+ `viewer.rs:2269-2292` |

### 2.3 编辑（选择工具下的路径编辑）

| ID | 触发 | 期望 | 依据 |
|---|---|---|---|
| E-01 | 单击顶点 | 选中该顶点（单选）；AE 同时显示其手柄 | 【EC 代码】`viewer.rs:1500-1520`（`vertex_at` → 选中 + `Gesture::Tangent`/`Vertices`） |
| E-02 | `Shift`+单击顶点 | 加入/移出选区 | 【EC 代码】`mask.selectVertices` 的 `add/toggle`（`mask.rs:247-265`） |
| E-03 | 框选 | 命中框内的顶点被选中；框内无顶点时退回"框选图层" | 【EC 代码】`viewer.rs:1852-1870` |
| E-04 | 双击顶点 | **选中整条路径所有顶点**，并弹出自由变换框（可整体移动/缩放/旋转） | 【EC 测试】`ui_viewer.rs:804`（`#290`）；`path.freeTransform`（`paths.rs:208-281`） |
| E-05 | 拖动已选顶点 | 所有选中顶点按同一屏幕增量移动（带吸附） | 【EC 代码】`viewer.rs:1696-1706`（`Gesture::Vertices` 用 `applied` 累加，避免漂移） |
| E-06 | 拖动切线手柄 | 默认**镜像**（等长反向）；按住 `Alt` **断开对称**，只动被拖的一侧 | 【EC 代码】`viewer.rs:1724-1733`（`if !mods.alt { params[other] = [-t] }`）；本项目曲线编辑器同规则：`UICompCurveEditor.java:2522-2532` + `CompCurves.mirrorHandles`(`CompCurves.java:597-611`) |
| E-07 | 单击空白 | 取消顶点选择（图层选择保留） | 【EC 测试】`ui_viewer.rs:867`（`#513`） |
| E-08 | `Delete`/`Backspace` | 删除选中顶点；剩余 <3 点时自动 `closed=false`；<2 点时整条路径无意义（蒙版则不参与遮罩） | 【EC 代码】`mask.rs:320-339`、`apply_count_op` `mask.rs:105-107` |
| E-09 | Delete Vertex 工具单击顶点 | 删除该顶点 | 【EC 代码】`viewer.rs:1906-1913` |
| E-10 | Convert Vertex 工具单击顶点 | 角点↔平滑点互换 | 【EC 代码】`viewer.rs:1915-1926` |
| E-11 | Convert Vertex 工具**拖动**顶点 | 拖出切线（AE 行为） | 【EC 代码】`viewer.rs:1421-1424`（`Gesture::Pen` 复用）；**【EC 未做纯拖动版本，属增强】** |
| E-12 | 编辑 3D 图层上的路径 | 顶点跟随光标（射线-平面求交） | 【EC 代码】`viewer.rs:267-272`（`l2c` 含 3D view）+ 本项目 `screenToLayerLocal` |

### 2.4 蒙版 vs 形状路径：两种目标

| 维度 | 蒙版（Mask） | 形状路径（Shape Path） |
|---|---|---|
| 载体 | `CompLayer.masks: List<CompMask>` | `CompLayer.shapes: List<CompShape>`（`type == PATH`） |
| 点模型 | `CompMask.CompMaskPoint{x,y,inX,inY,outX,outY,smooth}` | `CompShape.Point{x,y,inX,inY,outX,outY}` |
| 闭合 | 数据无 `closed`；渲染**永远闭合**（`MaskCache.java:189`） | 数据无 `closed`；渲染**永远闭合**（`ShapeRasterizer.java:234`）→ 需新增字段 |
| 属性 | mode/subtract/intersect/exclude、inverted、feather、opacity、expansion | fill（纯色/渐变）、stroke（颜色/宽度）、trim、repeater、布尔 |
| 动画 | 逐点通道 + feather/opacity/expansion 通道 | 逐点通道 + 各标量通道 |
| AE 的"工具创建"开关 | Tool Creates Mask 关 + 选中形状层 → 画形状 | 同上 |

### 2.5 关键几何算法（AE 语义）

1. **加点（形状不变）**：de Casteljau 在参数 `t` 处分割三次段：
   `a=lerp(p0,p1,t), b=lerp(p1,p2,t), c=lerp(p2,p3,t), d=lerp(a,b,t), e=lerp(b,c,t), m=lerp(d,e,t)`；
   新顶点 `m`，新 `out[i]=a-p0`、`in[new]=c-p3`、`out[new]=e-m`、`in[j]=d-m`（`mask.rs:389-416`，含羽化点参数重映射）。
2. **最近点命中**：每段 64 采样 + 20 次二分细化（`mask.rs:419-463`）；屏幕容差 5–6px（【EC 代码】`viewer.rs:2257` 用 5.0，`segment_at` 用 48 采样）。
3. **角点→平滑点**：方向沿 `next-prev`，长度取 `|next-prev|/6`，`out=d, in=-d`（`mask.rs:370-378`；RotoBezier 同公式 `paths.rs:18-37`）。
4. **内部/外部判定**：把点平移到 (0.5,0.5)、1×1 覆盖度采样，`>=0.5` 为内（`mask.rs:466-477`）；纯 Java 用射线法即可（`viewer_overlays.rs:229-240`）。
5. **45° 约束**：把 `to-from` 的角度 round 到 π/4 的倍数，再投影回该方向（`viewer.rs:2302-2312`）。
6. **闭合判定**：点到首顶点屏幕距离 <8px（`viewer.rs:2225`）。

### 2.6 路径关键帧动画（本项目最大差异）

| 项 | AE | 本项目现状 | 方案 |
|---|---|---|---|
| 关键帧粒度 | 一条 `Mask Path` 属性，一个键 = 整条路径 | 6 个标量通道/点，各自打键 | 提供 `给整条路径打关键帧`（遍历 6N 通道在播放头 `insert`），并在轨道/检查器显示为"路径组" |
| 顶点数变化 | 弹窗 `Preserve Constant Vertex and Feather Point Count`，同步到其它关键帧（`mask.rs:60-119` 的 `CountOp`） | 通道索引 `p/<i>` 会整体错位 → 动画错乱 | 短期：路径存在关键帧时**禁止增删点**（状态栏说明）；长期：按"所有键时间求值→apply CountOp→回写全部通道"重映射（§7 P4） |
| 首顶点 | Set First Vertex（旋转顶点数组） | 未实现 | 可选：`mask/<id>/p/*` 通道整体按旋转顺序重排（同 CountOp 难度） |
| 插值 | 路径贝塞尔逐点插值 | 通道各自插值（BBS `Keyframe`） | 语义等价，接受 |

### 2.7 吸附 / 约束 / 网格

- AE：`Shift` 约束 45°；`View ▸ Snap to Grid / Snap to Guides` 控制网格与参考线吸附（默认开）。
- 本项目：预览里已有网格绘制（`UICompositorPanel.java:835-845`，受 `settings.showGrid` / `settings.gridSize` 控制），
  但**没有预览吸附实现**（`snapButton` 只服务时间轴 `isSnapping()`，见 `UICompositorPanel.java:5323-5329`）。
- 方案：新增 `CompPrefs.Data.penSnap`（默认 true）与 `penSnapRadiusPx`（默认 6），吸附目标优先级：
  起始顶点 > 其它路径顶点 > 网格交点（`settings.showGrid` 时）> 合成边界/中心。全部在**屏幕空间**比较，
  再换算回局部坐标（避免非均匀缩放下的"吸附距离不一致"）。

### 2.8 右键菜单（AE 的 Mask and Shape Path 命令集）

effectcraft 把这些 AE 菜单项实现成了命令（菜单路径就是 AE 的菜单路径），可 1:1 对齐【EC 代码】：

| AE 菜单项 | effectcraft 命令 | 本项目落地 |
|---|---|---|
| Layer ▸ Mask and Shape Path ▸ RotoBezier | `path.rotoBezier` (`paths.rs:451`) | 可选（自动切线，公式同 §2.5-3） |
| Layer ▸ Mask and Shape Path ▸ Convert To Bezier Path | `path.convertToBezier` | 落地（把 Roto 点展开成显式切线） |
| Layer ▸ Mask and Shape Path ▸ Set First Vertex | `path.setFirstVertex` (`paths.rs:163`) | 可选（依赖通道重排） |
| Layer ▸ Mask and Shape Path ▸ Free Transform Points | `path.freeTransform` (`paths.rs:208`) | P3（配 E-04 的自由变换框） |
| Layer ▸ Mask ▸ Mode / Feather / Feather Falloff / Motion Blur | `layer.setMask` / `layer.mask.*` | 已有（`maskSection`） |
| Layer ▸ Mask ▸ Hide Locked Masks | `layer.mask.hideLocked` | 可选 |
| Layer ▸ Mask ▸ Remove Mask | `mask.remove` | 已有（"移除"按钮） |
| 形状组 Group / Ungroup | `path.groupShapes` / `ungroupShapes` | **不做**（无 Contents 树，§9 取舍 2） |
| 钢笔在画布上的右键菜单 | EC 未实现独立画布菜单 | 建议：矩形菜单（闭合路径 / 删除顶点 / 转换点 / 断开手柄 / 结束路径），AE 的 Comp 面板右键菜单细项【待真机】 |

> 说明：AE 画布内钢笔右键菜单的**逐项文本**我无法在本次抓取的官方页面中确认（页面正文被导航截断），
> 因此按"已由 effectcraft 验证过的命令集"设计，并把具体菜单文案列为真机核对项。

### 2.9 视觉规范（AE 对照）

| 元素 | AE | 本项目建议 |
|---|---|---|
| 路径线 | 1px，蒙版色/图层标签色 | 1px，`0xAA55AAFF`（现有），选中蒙版 `0xFFFFD27A`（现有） |
| 顶点 | 小方块；选中=实心 | 现有：3.5px 方块，选中 4.5px 实心 |
| 手柄 | 选中点才显示，圆点 + 连线 | 现有：入 `0xFF7AD8FF`/出 `0xFFFF9BD2`，方块 3px → 建议改圆点以贴近 AE |
| 橡皮筋 | 1px 半透明 | 同路径色 `alpha*0.6`（EC 做法） |
| 闭合提示 | 光标变为带圆圈的钢笔 | 可选（需要新图标资源） |

---

## 3. effectcraft 实现提炼（可移植清单）

### 3.1 数据模型（**直接照搬**）

`crates/keyframe/src/value.rs:8-17`：

```rust
pub struct ShapePath { vertices: Vec<[f64;2]>, in_tangents: Vec<[f64;2]>,
                       out_tangents: Vec<[f64;2]>, closed: bool, feather: Vec<FeatherPoint> }
```

- 切线是**相对顶点**的偏移（与本项目 `inX/inY/outX/outY` 完全一致）→ **零成本对齐**。
- `fit_tangents()`（`value.rs:48-54`）：三个数组长度不一致时补齐/截断（读取脏文件时的防御）。**本项目必须照抄**：
  `CompMask.points` 是同一个对象带 6 个字段，天然不存在长度不一致，但**通道索引越界**（`mask/<id>/p/9/x` 而只有 3 个点）
  已经在 `CompLayer.putNestedScalar` 里被忽略（`CompLayer.java:620-625`），这是对的，保持。
- `segment_count()` = `closed ? n : n-1`（`value.rs:56-59`）—— 所有循环的上界都用它。
- `rect()` / `ellipse()`（kappa=0.5522847498）→ 与 `CompMask.ellipse`（`CompMask.java:62-75`，k=0.5522848）一致。

### 3.2 命令与状态机

- **一切编辑都是一条命令**（`mask.new/addVertex/setVertex/setClosed/insertVertex/convertVertex/deleteVertices/moveVertices/selectVertices`，
  `mask.rs:642-675`）。本项目对应物 = `UICompositorPanel.beginAction(name)` + 直接改数据（没有命令总线），
  所以**每条命令在 Java 侧要显式 `beginAction` 一次**，才能保证"一次操作 = 一步 undo"。
- **UI 状态机在 `viewer.rs`**：`Gesture` 枚举（`viewer.rs:120-206`）承载拖拽上下文，
  `pen_id()`（`viewer.rs:209-211`）把"正在绘制的 (layer, mask)"存在 UI 内存里；
  按下走 `pen_press`（`2200-2298`），拖动按 `Gesture` 分支（`1696-1733`），松开清状态（`1388-1394`）。
  **注意**：EC 的"正在绘制"状态是 **UI 状态、不进项目文件**，关闭面板即丢失未闭合路径 —— 本项目同样处理（`UIPenTool` 持有）。
- **undo 粒度**：EC 测试断言"每个点一步 undo"（`ui_viewer.rs:827-837`：撤销 1 次=移动回退，2 次=取消闭合，3 次=少一个点）。

### 3.3 命中测试

- `segment_at`（`viewer_overlays.rs:93-110`）：每段 48 采样，取屏幕距离最小且 < tol。
- `open_end_at`（`113-119`）：只对 `!closed` 的路径，末顶点 <8px。
- `VertexHit`（`23-33`）同时用于绘制与命中：`{layer, mask, index, pos(屏幕), tangent: Option<bool>, vertex(局部), l2c(局部→合成)}`。
  **这个结构直接决定"绘制与命中不可能不一致"**，本项目建议照抄成 `PenHit`。
- 顶点优先级：顶点命中半径会被包围盒手柄放大到 `HANDLE+2`，保证顶点永远先于包围盒（`viewer.rs:224-226`，AE #1042 行为）。
  本项目 `maskHit` 先于 `handleAt`（`UICompositorPanel.java:4400` 在 `handleAt` 之前），顺序已经对。

### 3.4 可复用的数学

| 能力 | effectcraft 位置 | 移植到 Java 的形态 |
|---|---|---|
| 三次求值 / 切线 | `viewer_overlays.rs:70-90`、`mask.rs:480-493` | `CompPathGeometry.cubicAt/segmentControl` |
| 段分割（保形） | `mask.rs:389-416` | `CompPathGeometry.splitSegment(...)` 返回新点+4 个手柄修正 |
| 最近点 | `mask.rs:419-463` | `nearestOnPath(..., samples=64, refine=20)` |
| 内外判定 | `mask.rs:466-477`、`viewer_overlays.rs:229-240` | 射线法（无需 AWT/GL） |
| 45° 约束 | `viewer.rs:2302-2312` | `constrain45` |
| 镜像/断开 | `viewer.rs:1729-1731`、`CompCurves.mirrorHandles` | `mirrorHandle(point, dragOut)` + `if(!alt)` |
| 顶点数变更同步 | `mask.rs:60-119`（`CountOp` + `sync_keys`） | 见 §2.6 的通道重映射方案 |
| 抗锯齿填充 / 描边 | `path/src/lib.rs:222-331`（tiny-skia） | 本项目已有 AWT `Area` + `BasicStroke`（`MaskCache` / `ShapeCache`），**不需要移植** |
| 布尔运算 | `path/src/boolean.rs` | 本项目用 AWT `Area` 的 add/subtract/intersect/xor，够用 |

### 3.5 effectcraft 的已知短板（不要照抄）

- 只有"点击转换"，没有"拖动转换"（E-11）。
- 没有闭合光标提示。
- 画布右键菜单没有钢笔专用项。
- `MaskPath` 的羽化点模型很完整（`FeatherPoint`），但本项目不做（§1.1）。

---

## 4. 本项目现状（逐文件，含"有什么/缺什么"）

### 4.1 数据模型

| 文件 | 关键内容 | 缺什么 |
|---|---|---|
| `comp/CompMask.java` | `id/name/enabled/mode/inverted/opacity/feather/expansion` + `List<CompMaskPoint>`；`CompMaskPoint{x,y,inX,inY,outX,outY,smooth=true}`；`rectangle()`/`ellipse()`(k=0.5522848)/`copy()` | **无 `closed`**；无"段"概念；`smooth` 字段只写不读（无任何代码消费） |
| `comp/CompShape.java` | `type` 含 `PATH`；`Point{x,y,inX,inY,outX,outY}`；`x/y/width/height/rotation/roundness/corners/operator/fill/stroke/trim/repeater` | **无 `closed`**；`width/height` 对 PATH 无意义但检查器仍显示 |
| `comp/CompLayer.java:1221-1243` | `maskAt(mask, frame)`：逐点 6 通道求值 → 返回**副本**（不动原数据） | 无路径级通道 |
| `comp/CompLayer.java:1246-1294` | `shapeAt(...)`：逐点 6 通道 + 角半径 + 各标量 | 同上 |
| `comp/CompLayer.java:1296-1304` | `maskPath(id,prop)` / `shapePath(id,prop)` 生成通道路径 | — |
| `comp/CompLayer.java:592-641` | `putNestedScalar` 解析 `mask/<id>/p/<i>/{x,y,inX,inY,outX,outY}` 与 `mask/<id>/{feather,opacity,expansion}`；索引越界**静默忽略** | 增删点后旧通道变成孤儿（不会被清理） |

### 4.2 渲染

| 文件 | 关键内容 | 缺什么 |
|---|---|---|
| `client/render/MaskCache.java` | CPU 光栅化（AWT `Path2D`+`Area`+`BasicStroke`），逐 mask 算 alpha → `combine/combineFirst` 布尔合成 → 羽化 `boxBlur` → 上传 GL；缓存 key=layer+尺寸+mask hash+world hash，`MAX=24` | ①`points.size() >= 2` 就参与（`:70`、`:171`）→ **2 点退化=整层透明**；②永远 `closePath()`（`:189`）；③hash 不含 `closed`（新增字段要加） |
| `client/render/ShapeCache.java` | 每 shape 一个 AWT path，逐 shape 上色，布尔用 `DstOut/DstIn/Src` 合成，缓存按 geometry hash | hash 不含 `closed` |
| `client/render/ShapeRasterizer.java:215-235` | `buildPath`：`moveTo(p0)` → 每点 `curveTo(prev.out, cur.in, cur)` → **`closePath()`** | 无开放路径；`trim` 用 `FlatteningPathIterator`（开放路径也能量长度，OK） |
| `client/render/CompRenderer.java:676-699` | `is2D = !usePerspective(...)` → `MaskCache.get(...maskModel, frame, maskScale)` → `PassRenderer.mask` | 3D 图层不走这里（走下面） |
| `client/render/CompLayerSourceCache.java:506-521` | 3D/代理路径：mask 烘焙进 layer raster（世界矩阵），在效果之前 | 同 MaskCache 的 `closed` 问题 |
| `client/render/CompRenderCache.java:304-325`、`CompLayerSourceCache.java:964-1110` | 两处 `signature` 都遍历 mask/shape 的全部字段 | **新增 `closed` 必须同时加进这两处**，否则"改了没反应"（TEAM-CONTEXT §6 末条） |

**结论**：渲染侧的几何入口只有一个（AWT），钢笔只需要保证写对局部坐标 + 加 `closed` 语义，**不需要新的光栅化器**。

### 4.3 现有蒙版交互（这是钢笔要接的地方）

`client/ui/UICompositorPanel.java`（快照行号）：

| 位置 | 内容 | 说明 |
|---|---|---|
| `:570-585` | `MASK_DRAG_NONE/POINT/IN/OUT/MOVE` + `maskDrag/maskEditMask/maskEditPoint/maskEditHandle/maskDragMask/maskDragPoint/maskDragStartX/Y/maskActionStarted/maskStartPoints` | 现有拖拽状态（在预览匿名内部类里） |
| `:597` | `private static final int MASK_DRAG_NONE = 0;` | 常量区 |
| `:615-640` | `render()`：`applyDrag(context)`（`:619`）→ … → `renderMaskOverlay(context)`（`:633`） | **叠加绘制与轮询的插入点** |
| `:2341-2397`（`maskContext()` / `maskProject()`） | `new CompRenderContext(project, doc, time, null,null,null)`；`maskProject` = `CompScene.project(...)` → 屏幕 | 现成的投影封装，钢笔直接复用（建议提为 `panel.projectMask(...)` 公共方法） |
| `:2398-2515`（`renderMaskOverlay`） | 逐 mask 画 12 段采样的贝塞尔轮廓（永远是闭合：`(i+1)%size`）+ 顶点方块 + 选中点的 in/out 手柄 | **缺**：橡皮筋、开放路径绘制、未选中路径的弱化、悬浮高亮、光标区提示 |
| `:2517-2612`（`maskHit`） | 9px 半径内找最近的顶点/入柄/出柄（手柄优先于顶点？—— 代码是"距离更小者胜"，先测手柄再测顶点，同距时顶点胜） | **缺**：段命中、框选、多选、双击、`pointSelected` 判定、3D 工作区分支 |
| `:2614-2683`（`applyMaskDrag`） | 鼠标按住时按 `MASK_DRAG_*` 写 `mask/<id>/p/<i>/{x,y,inX,inY,outX,outY}`，**通过 `panel.setScalar`**（`:2663-2675`）→ 已有动画通道时写关键帧 | 这是**正确范式**，钢笔必须沿用（否则动画蒙版拖不动） |
| `:2685-2704`（`endMaskDrag`） | 只有真正动过才 `save()` + `refreshInspector()`；`beginAction("编辑蒙版")` 懒触发（`:2635-2639`） | 一次拖拽 = 一步 undo，已正确 |
| `:2712-2772`（`screenToLayerLocal`） | 2D：`inverse(view2D · world)`；3D：`camera.unproject`×2 → 射线-平面求交 → `inverse(world)` | **已有 3D 支持**（注释说"仅 2D 可拖"是**过时的**：代码里没有再判 `threeD`） |
| `:2775-2860`（`handleAt`） | 包围盒手柄/旋转柄/相机 gizmo，`handleAt` 在 `maskHit` 之后被调用（`:4400` vs `:4411`） | 顺序正确：顶点优先 |
| `:4400` | `if (this.maskHit(context, selected)) return true;`（在 `subMouseClicked` 里） | **钢笔按下分支的插入点** |
| `:4716` | `subMouseReleased`：`endViewportDrag(); endMaskDrag(); endDrag();` | **钢笔松开分支的插入点** |
| `:4503` | 预览内部类的 `subKeyPressed`（W/E/R 摄像机飞行） | 可放 Enter/Esc，但面板级更稳（`:17624`） |
| `:4884` / `:4949` | `this.masksToggle = new UIToggle("蒙版", ...)` / `previewBar.add(...)` | **钢笔开关按钮的插入点** |
| `:11989-12012`（`addMask`） | `new CompMask("mask_" + (masks.size()+1))`；`rectangle(layer.width*(0.5-anchorX), ...)` | **id 可能撞车**（删一个再加就重复）→ 用 `CompStore.uniqueId`；**矩形错位**（见 §0-3③） |
| `:16037-16160`（`maskSection`） | 启用/移除/羽化/不透明度/扩展/逐点 6 个数值行/模式/反转/添加点/删除最后一点 | 逐点数值行太多（40 点=240 行 UI），**钢笔上线后可折叠**；"添加点"固定加在层中心，无意义 |
| `:15927-15957`（形状 PATH 分支） | 同样的逐点 6 数值行 + 添加/删除最后一点 | 同上 |
| `:8263`（`save()`） | 只置脏 + 去抖写盘 + `CompPrecache.markDirty()` | 钢笔每次落点都要调 |
| `:16676-16711`（`setScalar`） | 写嵌套标量 + 若有通道则 `insert(frame, value)` + `save()` | **钢笔拖拽写入的唯一正确入口** |
| `:17384-17422`（`beginAction`） | 快照前状态入 undo 栈、清 redo、重置采样基线 | 每个离散操作调一次 |
| `:17624`（面板级 `subKeyPressed`） | Esc 关弹窗/退 3D 空间、`CompKeybinds.find` 快捷键表 | 钢笔键位挂这里最稳 |

### 4.4 曲线编辑器的镜像逻辑（可复用范式）

`client/ui/UICompCurveEditor.java`：

- `DRAG_HANDLE` 拖拽（`:2103-2125` 按下，`:2478-2566` 拖动）：
  `key.rx/ry`（出）与 `key.lx/ly`（入）相对关键帧；
  **`if (!CompInput.altDown()) CompCurves.mirrorHandles(key, outgoing, oppositeMax)`**（`:2522-2532`）→ **Alt 断开对称**；
- `CompCurves.mirrorHandles(key, fromRight, oppositeMax)`（`CompCurves.java:597-611`）：`lx = clamp(rx)`, `ly = -ry`，
  并限制不得超过相邻关键帧（"手柄不得越过邻居"）。
- 拖拽是**相对按下时快照**的增量（`:2500-2520`），不是绝对量 —— 这是"手柄不飞"的关键，钢笔照抄。
- 结论：**镜像规则、Alt 断开、邻居夹紧、快照增量** 这 4 条在路径手柄上完全通用，只是把"帧/值"换成"局部 x/y"。

### 4.5 现状差距汇总

| AE 行为 | 现状 |
|---|---|
| 单击加角点 / 拖动加平滑点 | ❌ 无 |
| 橡皮筋 | ❌ 无 |
| 点击起点闭合 | ❌ 无（数据层也没有闭合概念，渲染永远闭合） |
| 段上加点 | ❌ 无 |
| 顶点选择/多选/框选/双击选整条 | ❌ 无（只有"拖到哪个点就是那个点"） |
| 删除顶点 | ❌ 无（只有检查器"删除最后一点"） |
| 角点↔平滑点转换 | ❌ 无（`smooth` 字段写了没人读） |
| 手柄镜像 / Alt 断开 | ❌ 无（拖 in 只改 in，拖 out 只改 out，**当前是完全断开的**） |
| 3D 图层上编辑 | ⚠️ 拖点已支持（`screenToLayerLocal`），但 3D 工作区不支持（`is3DSpace()` 直接 return） |
| 路径动画 | ⚠️ 逐点通道可打键，但无"整条路径"入口，且增删点会错位 |
| 形状路径（开放） | ❌ 渲染强制闭合 |

---

## 5. 2D / 3D 坐标映射（准确公式 + 现有方法 + 坑）

### 5.1 预览几何（所有换算的地基）

```java
// UICompositorPanel 预览内部类 —— fitScale() 在 :3092-3095
float scale = Math.min(area.w / (float) doc.width, area.h / (float) doc.height);
float ox = (area.w - doc.width  * scale) / 2F;
float oy = (area.h - doc.height * scale) / 2F;
```

```java
// 屏幕(面板像素) → 合成像素
float compX = (mouseX - area.x - ox) / scale;
float compY = (mouseY - area.y - oy) / scale;

// 合成像素 → 屏幕
float sx = area.x + ox + compX * scale;
float sy = area.y + oy + compY * scale;
```

> 预览是 **fit-only**（没有用户缩放/平移），所以 `scale/ox/oy` 每帧算一次即可。
> 若以后加预览缩放，必须同时改 `fitScale()` 的所有调用点。

### 5.2 合成 → 图层局部

**2D 图层 / 正交相机下的 3D 平面图层**（与 `CompScene.project` 的非透视分支严格互逆）：

```java
CompRenderContext rc = this.maskContext();                      // :2343-2347
CompCamera cam = CompScene.camera(rc);                          // CompScene.java:58-66
Matrix4f model = new Matrix4f(cam.view2D).mul(CompScene.world(rc, layer));  // 注意顺序
Vector3f local = new Vector3f(compX, compY, 0F);
new Matrix4f(model).invert().transformPosition(local);          // local.x / local.y 即蒙版坐标
```
（现有实现：`screenToLayerLocal` `:2734-2742`）

**透视 3D 图层**（射线-平面求交）：

```java
Vector3f a = cam.unproject(compX, compY, doc.width, doc.height, 0F);      // CompCamera.java:341-374
Vector3f b = cam.unproject(compX, compY, doc.width, doc.height, 1000F);
Matrix4f world = CompScene.world(rc, layer);
Vector3f origin = world.transformPosition(new Vector3f(0F, 0F, 0F));
Vector3f n = world.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();
Vector3f dir = new Vector3f(b).sub(a);
float den = dir.dot(n);
if (Math.abs(den) < 1e-5F) return null;                     // 平面侧对屏幕
float t = new Vector3f(origin).sub(a).dot(n) / den;
Vector3f hit = new Vector3f(a).fma(t, dir);
Vector3f local = new Vector3f(hit);
new Matrix4f(world).invert().transformPosition(local);
```
（现有实现：`screenToLayerLocal` `:2744-2769`）

### 5.3 图层局部 → 屏幕（唯一权威投影）

```java
Vector2f p = CompScene.project(rc, layer, lx, ly, 0F);   // CompScene.java:407-426
if (p == null) { /* 在近裁剪面之后：跳过整条路径或按需裁剪 */ }
float sx = area.x + ox + p.x * scale;
float sy = area.y + oy + p.y * scale;
```
（现有实现：`maskProject` `:2377-2391`）

`CompScene.project` 的分支：`usePerspective(ctx, layer)` → `camera.project`（透视）；否则 `camera.view2D`（仿射）。
`usePerspective` = `threeD && type!="camera" && type!="adjustment" && camera.perspective`（`CompScene.java:291-297`）。

### 5.4 3D 工作区（`is3DSpace()`）

```java
CompViewportCamera vc = this.viewport();          // :3690+，尺寸 = preview.area（:3700 / :12844-12858）
float vx = mouseX - area.x, vy = mouseY - area.y; // 视口像素 = 面板像素 - preview.area 原点
Vector3f world = vc.unprojectPlane(vx, vy, origin, n);        // CompViewportCamera.java:441-475
// 反向：Vector2f vp = vc.project(worldPoint);  屏幕 = (area.x + vp.x, area.y + vp.y)
```
（现有同类代码：`:1537-1538`、`:3873-3874`）

### 5.5 坑表（每条都真实存在）

| # | 坑 | 症状 | 处理 |
|---|---|---|---|
| 1 | 合成 Y 向下，预览纹理 V 轴相反 | — | **不要手工翻 Y**：`CompScene.project` 与 `area` 同向；TEAM-CONTEXT 里"`compY=(1-vUv.y)*h`"只适用于手写 shader 采样 comp 纹理 |
| 2 | 2D 图层漏乘 `view2D` | 相机缩放≠1 或 roll≠0 时，蒙版点整体偏移/歪斜 | 必须 `inverse(view2D · world)` |
| 3 | 用 `layer.threeD` 判透视 | 正交相机的 3D 图层被当透视算 → 点飞走；调整层永远 2D | 一律用 `CompScene.usePerspective(rc, layer)` |
| 4 | `CompScene.project` 返回 `null` | 顶点在相机后 → NPE / 巨大坐标 | 每点判空；整条路径不可见时只画屏幕内的部分 |
| 5 | 误加锚点偏移 | 蒙版相对图层偏半个尺寸 | **局部原点就是锚点**（`CompScene.local` 无锚点平移）；`anchorX/Y` 只影响内容（`LayerContentRenderer.java:93-94`：`x=-w*anchorX`） |
| 6 | 自己算父级矩阵 | 父子级动画时蒙版与像素错位 | `CompScene.world` 已含父链（`:151-188`） |
| 7 | 表达式/motion/shake/binding 改变 `local()` | 命中与实际渲染不一致 | 命中/绘制都走 `CompScene`（`:69-148` 已把这些并进 `local`） |
| 8 | 拖动中每帧重算逆矩阵 | 父级在动时顶点"追不上"鼠标 | **按下时取一次逆矩阵，整段拖动复用**（EC 的 `Gesture::Pen{inv}` / `Gesture::Vertices{inv}`） |
| 9 | 3D 图层蒙版在 `CompLayerSourceCache` 里烘焙 | 若在 `CompRenderer` 再套一次遮罩 → 双重遮罩 | 3D 走 source cache（`:506-521`），2D 走 `CompRenderer`（`:678-699`），不要同时 |
| 10 | 3D 工作区的 `area` vs 视口 | 点击偏移 | 视口相机尺寸=`preview.area.w/h`（`:3700`），像素=`mouse - area` |
| 11 | `scalarAt` 的通道优先级 | 有键时数据字段被忽略，直接改 `point.x` 无效 | 拖拽一律走 `panel.setScalar`（现成的 `applyMaskDrag` 就是范例） |
| 12 | 非均匀缩放下的手柄长度 | 屏幕上手柄方向与局部方向不一致 | 手柄必须**先算局部向量**再投影（不要用屏幕向量反推），否则 `in=-out` 在屏幕上不共线 |

---

## 6. 集成方案

### 6.1 新增文件（3 个，尽量把逻辑搬出大文件）

#### (a) `src/main/java/dev/bbsae/comp/CompPathGeometry.java`（纯几何，零依赖，可单测）

```java
public final class CompPathGeometry {
    /** 三次段控制点：p0, p0+out, p3+in, p3（closed 时 seg 可等于 n-1 且 j=0）。 */
    public static float[][] segment(float[] xs, float[] ys, float[] ix, float[] iy,
                                    float[] ox, float[] oy, boolean closed, int seg);
    public static float[] cubicAt(float[][] c, float t);                 // 求值
    public static float[] cubicTangent(float[][] c, float t);            // 归一化切线（做手柄/法线用）
    public static int segmentCount(int n, boolean closed);
    /** 最近点：64 采样 + 20 次二分；返回 {seg, t, dist} 或 null。 */
    public static float[] nearestOnPath(... , float qx, float qy);
    public static float[] pointOnPath(..., int seg, float t);
    /** 保形分割：返回新点 {x,y,inX,inY,outX,outY}，并就地修正 seg 与 j 的手柄。 */
    public static float[] splitSegment(..., int seg, float t, HandleSink sink);
    public static boolean insidePath(..., float qx, float qy);            // 射线法
    public static float[] smoothTangents(float px, float py, float cx, float cy,
                                         float nx, float ny);            // 1/6 规则，退化返回 {10,0}
    public static void mirrorHandle(float[] p, boolean dragOut);          // lx=-rx, ly=-ry
    public static float[] constrain45(float fromX, float fromY, float toX, float toY);
    public static float[] bounds(...);
}
```
> 设计要点：入参用 `float[]` 而不是对象，是因为同一套算法要服务 `CompMask.CompMaskPoint` 和 `CompShape.Point`
> 两个不相关的类（它们没有共同父类）；用 `HandleSink` 回调写回，避免装箱。**全部方法必须对 NaN/Inf/0 长段做防御。**

#### (b) `src/main/java/dev/bbsae/comp/CompPenSession.java`（状态机，无 UI/GL/BBS 依赖）

```java
public final class CompPenSession {
    public interface Target {                 // 适配 CompMask / CompShape.PATH
        int count();
        float[] point(int i);                 // {x,y,inX,inY,outX,outY}
        boolean closed();
        void setClosed(boolean closed);
        void insert(int index, float[] vertex);
        void remove(int index);
        void setHandle(int index, boolean out, float hx, float hy);
        void write(int index, String channel, float value); // 交给面板走 setScalar（动画安全）
    }
    public enum Phase { IDLE, DRAWING }
    public enum Drag  { NONE, NEW_TANGENT, VERTEX, IN_HANDLE, OUT_HANDLE, MARQUEE, FREE_XFORM }

    public boolean isDrawing();
    public void begin(Target t, boolean createClosedPath);   // 落第 1 点前调用
    public boolean press(float localX, float localY, float screenX, float screenY, Modifiers m); // 返回是否消费
    public boolean drag(float localX, float localY, float screenX, float screenY, Modifiers m);
    public boolean release(Modifiers m);
    public boolean key(int glfwKey, Modifiers m);             // Enter/Esc/Delete/Backspace
    public void endPath(boolean close);                       // 结束并清理
    public void cancel();                                     // 切工具/切层/切合成
    public PenHit hitTest(...);                               // 复用绘制时收集的 PenHit 列表
}
```
> 便于测试的关键：**`press/drag/release` 只依赖局部坐标 + 屏幕坐标 + 修饰键**，测试里用假 `Target`（数组实现）即可跑完整状态机，
> 不需要 BBS、不需要 GL、不需要 UI。

#### (c) `src/main/java/dev/bbsae/client/ui/UIPenTool.java`（UI 适配层）

职责：持有 `CompPenSession`；把 `CompMask`/`CompShape` 包成 `Target`（`write` → `panel.setScalar`，`insert/remove` → 直接改 list）；
每个离散操作前 `panel.beginAction("钢笔：落点"/"闭合路径"/"加点"/"删点"/"转换点")`；每次修改后 `panel.save()`；
`renderOverlay(context)` 画路径/顶点/手柄/橡皮棒（复用 `maskProject`）；`cursorIcon()` 返回笔形光标；`onKey`。

### 6.2 与「蒙版编辑」「形状图层」如何共存

- **一个状态机、两个目标**：`UIPenTool` 同时服务蒙版与形状路径；目标由"当前选中层 + 工具创建开关"决定（P-20）。
- **不与现有拖拽冲突**：`UIPenTool` 只在 `penMode == true` 时消费按下；`penMode == false` 时 `maskHit`/`applyMaskDrag` 原样工作。
  两者共享同一套 `PenHit` 命中列表（`UIPenTool` 每帧渲染时收集，交给 `maskHit` 复用），避免"点得中但拖不动"。
- **建议**：把现有 `maskHit` 的判定逻辑**逐步迁进** `UIPenTool.hitTest`（先加段命中/多选，再切），
  但 P0/P1 阶段保持两套并存（`penMode` 分支），降低回归风险。
- **形状图层**：钢笔只写 `CompShape.PATH` 点，不动 `width/height/rotation`（保持 identity），
  这样"shape 局部 == layer 局部"，与蒙版共用坐标；检查器的 PATH 段落继续可用。
- **检查器**：`maskSection` / shapes 的 PATH 分支各加 3 个按钮（闭合路径 / 断开手柄 / 转换为角点），
  并在 `mask.points.size() > 8` 时把逐点数值行折叠（现在 40 点 = 240 行，滚不动）。

### 6.3 `UICompositorPanel.java` 的最小插入点（9 处）

> 行号是 22:45 快照；**请按"锚点文本"搜索**。

| # | 锚点文本 | 快照行 | 插入内容 |
|---|---|---|---|
| 1 | `private static final int MASK_DRAG_NONE = 0;` | 597 | `private final UIPenTool penTool = new UIPenTool(UICompositorPanel.this);`（或延迟建）+ `private boolean penMode;` |
| 2 | `this.masksToggle = new UIToggle(IKey.constant("蒙版")...` / `this.previewBar.add(...)` | 4884 / 4949 | 加一个 `UIToggle("钢笔", …)`（或 `UICompButton` 显示当前工具），`applyPreviewBarState()` 里同步可见性 |
| 3 | `this.applyDrag(context);` | 619 | `if (this.penTool.isDragging()) { this.penTool.onDrag(context); return; }`（放在 `maskDrag` 分支**之前**） |
| 4 | `this.renderMaskOverlay(context);` | 633 | `this.penTool.renderOverlay(context);`（在 mask 叠加之后画，保证钢笔在最上层） |
| 5 | `if (this.maskHit(context, selected))` | 4400 | 之前插入 `if (this.penMode && this.penTool.onPress(context)) return true;` |
| 6 | `this.endMaskDrag();`（`subMouseReleased`） | 4716 | `this.penTool.onRelease(context);` |
| 7 | `protected boolean subKeyPressed(UIContext context)`（面板级） | 17624 | 顶部插入 `if (this.penMode && this.penTool.onKey(context)) return true;`（Enter/Esc/Delete/Backspace，注意先判 `!typing`） |
| 8 | `public void selectLayer(CompLayer layer)` / `private void refreshComp(String compId)` | 9880 / 8182 | 各自末尾 `this.penTool.cancel();`（切层/切合成必须终止绘制） |
| 9 | `private UISection maskSection(CompLayer layer)` / `if (CompShape.PATH.equals(shape.type))` | 16037 / 15927 | 加"用钢笔编辑 / 闭合路径 / 断开手柄 / 转为角点"按钮 |

补充（不改结构，只新增私有方法，可放在文件末尾 `// ---- pen ----` 区块，减少对现有代码的干扰）：

- `private float[] penScreenToLocal(CompLayer layer, float mx, float my)` —— 把 `screenToLayerLocal` 提为公共入口；
- `private float[] penLocalToScreen(CompLayer layer, float lx, float ly)` —— 包 `maskProject`；
- `private void penAddMaskPoint(CompMask mask, float lx, float ly)` 等：**不建议**在面板里写业务，全部走 `UIPenTool`。

### 6.4 序列化 / 迁移

Gson 配置是**默认反射式**（`CompStore.java:35-38`：只有 `setPrettyPrinting` + `disableHtmlEscaping`，无自定义 adapter），
且 `CompMask` / `CompShape` 都有 **public 无参构造**，所以：

| 改动 | 兼容性 | 需要做的事 |
|---|---|---|
| `CompMask.closed`（`public boolean closed = true;`） | 旧文件无此字段 → Gson 调无参构造 → 初始化为 `true` | 只用于**叠加绘制/命中/是否参与遮罩**；`MaskCache` 仍按"蒙版是区域"闭合渲染（AE 语义） |
| `CompShape.closed`（`public boolean closed = true;`） | 同上 | `ShapeRasterizer.buildPath` 按 `closed` 决定是否 `closePath()`；**填充**仍由 AWT 隐式闭合（与 AE 一致：开放路径照样填充），只有**描边端点/接缝**不同 |
| `CompPrefs.Data.penSnap / penSnapRadiusPx` | 新 key，缺省即默认值 | 无迁移 |
| `project.settings.penCreatesMask`（工具创建开关，按项目存） | 新 key | 无迁移 |

**必须同步的三处哈希/签名**（否则"改了没反应"或"缓存串帧"）：
1. `client/render/CompRenderCache.java` 的 `signature(...)`（mask 段 `:304-325`、shape 段 `:327-375`）→ 加 `closed`；
2. `client/render/CompLayerSourceCache.java` 的 `signature(...)`（`:964-1110`）→ 加 `closed`；
3. `client/render/MaskCache.java` 的 `hash(List<CompMask>)`（`:134-159`）与 `ShapeCache.hash(...)`（`:83-137`）→ 加 `closed`。

**不新增** `CompPenPath` 数据类（用户建议的 `comp/CompPenPath.java`）的原因：蒙版/形状路径的数据已经存在，
再引入一份"钢笔路径"会产生两套真相与同步问题；`CompPenSession` 只持有**UI 态**（正在画的 (map id, layer id, 索引)），
不像 AE 那样需要一个独立的 Path 值对象。如果后续要做"AE 式一条 path 属性"，那才需要新的值类型（见 §9 取舍 3）。

---

## 7. 分阶段实施计划（每阶段可编译、可测、可交付）

| 阶段 | 内容 | 交付/验收 | 预估 |
|---|---|---|---|
| **P0 地基**（不接 UI） | `CompPathGeometry` 全量 + 单测；`CompMask.closed`/`CompShape.closed` + 三处 hash/signature；`MaskCache` 的 `<3 点不参与` 修复；`ShapeRasterizer` 开放路径；`addMask` 锚点与 id 唯一性修复 | `CompPathGeometryTest`、`CompShapeOpenPathTest`、`MaskCacheDegenerateTest` 全绿；现有蒙版渲染逐像素不变（截图对比） | 1 天 |
| **P1 钢笔绘制（2D）** | `CompPenSession` + `UIPenTool`：落点/拖手柄/橡皮筋/闭合/Enter/Esc/续画/Shift 45°/段上加点/undo 粒度；预览工具栏开关；光标 | 单元测试跑完整交互序列；2D 蒙版与形状路径都能画；每点一步 undo | 2 天 |
| **P2 编辑** | 顶点选择/Shift 多选/框选/双击整条/拖顶点/拖手柄（镜像 + Alt 断开）/Delete/转换点/右键菜单；检查器按钮 | 对应测试；手柄镜像与 Alt 断开有像素级/数值级断言 | 1.5 天 |
| **P3 3D** | 3D 图层（透视）绘制与编辑（公式 §5.2/5.3，已有 `screenToLayerLocal` 可复用）；3D 工作区（`is3DSpace()`）用 `CompViewportCamera`；退化情况（侧对屏幕、相机后） | 3D 测试：倾斜卡片上的蒙版，投影后覆盖区域与解析解误差 ≤2px | 1.5 天 |
| **P4 动画与收尾** | "整条路径打关键帧"助手；增删点时的通道重映射（或先禁止并提示）；性能（拖拽降采样、缓存复用）；`docs/features.zh.md` 更新 | 动画路径增删点的回归测试；性能基准；真机截图清单 | 1.5 天 |

**风险控制**：P0 的 `closed` 字段会触碰渲染路径，必须单独一次提交 + 一次逐像素回归（用 `debugRenderCompShot` 或既有截图基线）。

---

## 8. 测试方案

### 8.1 纯几何单测（无 GL、无 Minecraft）

`src/test/java/dev/bbsae/comp/CompPathGeometryTest.java`：

| 用例 | 断言 |
|---|---|
| `split_preserves_shape` | 圆角三角形每条段在 t=0.1/0.25/0.5/0.9 分割后，新旧路径 64 等分采样点最大偏差 `< 0.01px` |
| `split_on_closed_wraps` | closed 路径最后一段（n-1→0）可分割，`closed` 保持 |
| `nearest_on_path_hits_each_segment` | 每段中点附近采样，`seg` 正确、`t≈0.5±0.02`、距离 `< 0.5px` |
| `nearest_degenerate` | 0/1/2 点、零长段、重复点 → 返回 null 或安全值，**不抛异常、不返回 NaN** |
| `inside_path` | 三角形内部/外部/边界附近（含自交路径用 even-odd 语义） |
| `mirror_and_break` | 拖出柄后 `in=-out`（长度相等、方向相反）；Alt 模式下另一侧不变 |
| `convert_corner_smooth_roundtrip` | 角点→平滑点切线=`(next-prev)/6`；平滑→角点两者都为 0 |
| `constrain45_*` | 8 个方向各一例；零位移不除零 |
| `bounds_and_empty` | 空路径返回 null；单点路径 bounds 退化 |

`src/test/java/dev/bbsae/comp/CompPenSessionTest.java`（假 `Target` = `float[]` 列表）：

| 用例 | 断言 |
|---|---|
| `click_click_drag_click_first_closes` | 4 点 + closed；第 3 点 out=(dx,dy)、in=(-dx,-dy) |
| `enter_and_esc_end_open` | 结束但 `closed=false`，且状态回 IDLE |
| `shift_constrains` | 第二点落在 45° 方向上 |
| `pen_on_segment_inserts` | 段中间点击 → 点数 +1、形状不变、选中新点 |
| `pen_on_last_vertex_resumes` | Esc 后点末点 → 继续追加 |
| `delete_vertex_clears_closed` | 3 点闭合路径删 1 点 → `closed=false` |
| `two_point_mask_is_not_a_mask` | 2 点时 `Target` 不被交给渲染（或 `<3` 判定为跳过） |
| `each_action_is_one_undo` | 交互序列 → `beginAction` 调用次数 = 离散操作数 |

### 8.2 栅格化测试（无头，不需要 GL）

**关键重构建议**：把 `MaskCache.rasterize` 拆成
`static float[] alphaFor(List<CompMask>, int w, int h, Matrix4f world)`（纯 CPU）与 `upload(...)`（GL）。
这样蒙版栅格可以在**没有 GL 上下文**的 JUnit 里断言，且生产代码仍只有一条路径（不是测试专用分支）。

| 用例（`MaskRasterTest`） | 断言 |
|---|---|
| `closed_square_area` | 100×100 里 40×40 方块，覆盖面积 1600±1 |
| `open_path_still_fills`（AE 语义） | 开放三角形仍填充（隐式闭合） |
| `two_points_no_coverage` | 2 点蒙版 → 全 0（且不参与合成） |
| `feather_monotonic` | 羽化 8px 时，从边界向内的 alpha 单调不减 |
| `expansion_grows` | expansion=+5 面积增大，-5 减小 |
| `mode_subtract_exclude_intersect` | 与 `MaskCombineTest` 的数学一致（已有 `MaskCache.combine*` 单测基础） |
| `world_matrix_translate` | world 平移 (100,0) 后覆盖区域整体右移 100 |
| `signature_changes_with_closed` | 改 `closed` → `CompRenderCache.signature` 必变（防"改了没反应"） |

`ShapeRasterTest`（`ShapeRasterizer` 纯 AWT，可完全无头）：
`path_closed_fills_and_strokes`、`path_open_strokes_without_seam`、`path_zero_points_empty`、`trim_on_open_path`。

### 8.3 3D 投影后栅格（需要真实 GL 或纯矩阵）

两层验证：
1. **纯矩阵**（无 GL）：给定相机 `fov=50, distance=800, tilt yaw=30°`、卡片在 `z=200`，
   取路径 4 个角经 `CompScene.project` 的屏幕坐标，与解析解（透视除法手算）比对 `<0.01px`；
   再用 `inverse(project)` 回推，误差 `<0.05px`（这是"点跟手"的数学保证）。
2. **GL 像素**（沿用 `ShaderPixelTest` 的隐藏窗口模式，`Assumptions.assumeTrue` 跳过无 GL 环境）：
   渲染倾斜卡片 + 蒙版，断言卡片中心 alpha>0.99、角外 alpha<0.01、边缘 1–2px 过渡带存在。

### 8.4 截图验证清单（真机一轮，Lead 执行）

用现成调试入口 `debugRenderCompShot(String name)`（`UICompositorPanel.java:9471`）逐项出图：

1. 5 点角点闭合蒙版，峰点带平滑手柄（对照 AE 同位置截图）。
2. 橡皮筋（画到第 3 点、鼠标停在半途）—— 叠加层。
3. 手柄镜像 vs 按住 Alt 断开（同一顶点两张图）。
4. 闭合路径 + 羽化 20px（边缘渐变）。
5. 相减蒙版 + 反转。
6. 开放路径（Esc 结束）+ 再次点击末点续画后的形状。
7. 在段中间加点后，路径轮廓逐像素不变（前后两张图 diff = 0）。
8. 3D 倾斜卡片（yaw 30°）上的蒙版，旋转相机后再看一次（点仍贴合）。
9. 3D 工作区（`is3DSpace`）里画的一段路径。
10. 路径动画：第 0/24/48 帧三张图，形状连续、无跳变。
11. undo/redo 各 3 步后的画面。
12. 保存 → 重开项目 → 同一帧画面（含中文蒙版名）。

### 8.5 「100% 没 bug」的现实验收标准（建议写进验收单）

1. §2 行为表 **A 类条目 100% 有自动化测试**，且每个 bug 修复都带一个"修复前会红"的用例。
2. **零已知偏差**：B 类差异（§1.2）在文档中逐条列出并由用户书面确认；否则不算通过。
3. **零崩溃/零异常路径**：空/锁定/隐藏/已删除图层、0/1/2 点路径、NaN 坐标、相机后顶点、切层中途、存盘中途、
   撤销到"点不存在"的状态 —— 全部有测试或显式防御（`try/catch` 不算，必须判空 + 早退）。
4. **undo/存盘往返无损**：`snapshot → restore → snapshot` 两次 JSON 相同；浮点几何误差 0（Gson 用 `Float.toString` 往返精确）。
5. **2D/3D 一致性**：同一局部坐标经"叠加绘制"和"栅格化"两条路径到屏幕的偏差 ≤1px（100% 缩放）、≤2px（300%）。
6. **性能预算**：50 条路径 × 40 点的叠加绘制 <0.3ms/帧；拖拽时（每次移动都重栅格）保持 ≥30fps@720p 代理；
   拖拽期间 `MaskCache` 每帧都会"淘汰 1 条 + 新建 1 条"全分辨率 mask 纹理，并临时分配
   `float[w*h]`（1080p ≈ 8MB）+ `BufferedImage`（≈ 8MB）—— 必须降采样或复用纹理，否则 GC/显存抖动会被当成"钢笔卡"（见 §9 风险 5）。
7. **真机清单 §8.4 一次性通过**（不允许"下次再修"）。

---

## 9. 风险与需要用户确认的取舍

| # | 取舍 / 风险 | 选项 | 建议 |
|---|---|---|---|
| 1 | 钢笔只做蒙版，还是也做形状路径？ | A 只蒙版（1 天省下）／B 两者共用状态机 | **B**：形状路径是"不同场景都可用"的核心（用户明确要求），代价是 `CompShape.closed` + 开放路径栅格（P0 已含） |
| 2 | 是否引入 AE 的「形体图层 / Contents 组」独立图层类型？ | A 不做（沿用扁平 shapes）／B 新数据模型 | **A**：B 是"重写形状系统"，与本需求不是同一量级；AE 的 Group/Fill/Stroke 嵌套、多个 Path 与布尔顺序都无法在扁平列表上 1:1 |
| 3 | 路径动画：接受"逐坐标通道"吗？ | A 接受 + 提供整条打键助手／B 新增 `PathValue` 通道类型（改 BBS Keyframe 泛型） | **A**；B 会触碰 BBS 关键帧体系（高风险，且 `UIKeyframeEditor` 不认识 Path 值） |
| 4 | 已动画路径上增删顶点 | A 禁止并提示（1 行判断）／B 通道重映射（求值→CountOp→回写，1–2 天） | **A 先上，B 放 P4**；否则"加个点动画全乱"会被当成 bug |
| 5 | 拖拽性能（每帧重栅格 + 24 缓存条目抖动） | A 拖拽时蒙版按 1/2 分辨率栅格／B 复用单张纹理 | **A**；B 要改 `MaskCache` 生命周期，风险更大 |
| 6 | 3D 工作区（`is3DSpace`）是否也要能画？ | A 只支持"普通预览里的 3D 图层"（透视投影已具备）／B 再加工作区投影 | **A 必做，B 视时间**；B 只是多一套 `CompViewportCamera` 投影，约 +0.5 天 |
| 7 | 快捷键 | `G/+/-` 可能与 BBS 冲突；本项目有两套方案（Premiere/BBS） | 走 `CompKeybinds`，两套都注册；`G` 冲突时给 `P` |
| 8 | 蒙版闭合语义 | A 蒙版永远按区域渲染（AE 语义），`closed` 只影响叠加显示／B 未闭合不参与遮罩 | **A**；B 会让"Esc 结束的蒙版突然不遮罩"，与 AE 不符 |
| 9 | 吸附目标与阈值 | 网格/其它路径顶点/合成边界/中心 | 先做"顶点 + 网格"，阈值 6px，可在设置里关 |
| 10 | 逐点羽化（Mask Feather 工具） | 不做 | 数据模型缺 `FeatherPoint`，单独立项 |
| 11 | RotoBezier / Set First Vertex / Free Transform Points | 后置 | 公式都有（§2.5/§3.4），但都依赖"通道重排"（取舍 4B） |
| 12 | 右键菜单文案 | 按 effectcraft 已验证的命令集设计 | 具体文案需真机核对 AE 画布右键菜单 |

---

## 10. 参考与索引

### 10.1 参考链接（本轮实际抓取；正文被导航截断，仅作条目级引用）

- Adobe AE 帮助《创建蒙版 / Create masks》 <https://helpx.adobe.com/after-effects/desktop/drawing-painting-and-paths/work-with-masks/create-masks.html>
- Adobe AE 帮助《创建形状 / Creating shapes》 <https://helpx.adobe.com/after-effects/desktop/drawing-painting-and-paths/shapes-and-shape-attributes/creating-shapes-masks.html>
- Adobe AE 帮助《管理形状路径和蒙版并对其进行动画制作》 <https://helpx.adobe.com/cn/after-effects/using/animating-shape-paths-masks.chromeless.html>
- AE 参考手册（含"Pen 工具下按 V 或 Ctrl 临时切到选择工具"） <https://help.adobe.com/archive/en/after-effects/cc/2013/after_effects_reference.pdf>
- effectcraft 仓库 <https://github.com/storytold/effectcraft>（本地 `_research/effectcraft/`）

### 10.2 effectcraft 索引（本次读过的关键位置）

| 主题 | 文件:行 |
|---|---|
| 路径数据模型 | `crates/keyframe/src/value.rs:8-151` |
| AE 顶点编辑命令（含分割/最近点/转换） | `crates/engine/src/commands/mask.rs:1-676` |
| RotoBezier / 首顶点 / 自由变换 / 组 | `crates/engine/src/commands/paths.rs:18-477` |
| 形状工具 + Tool Creates Mask | `crates/engine/src/commands/shape_tool.rs:79-381` |
| 钢笔创建形状路径 | `crates/engine/src/commands/viewer_cmds.rs:237-299` |
| Pen 交互状态机 | `crates/ui-egui/src/panels/viewer.rs:188-211, 1378-1394, 1696-1733, 2200-2312` |
| 路径叠加绘制/命中/橡皮筋 | `crates/ui-egui/src/panels/viewer_overlays.rs:20-219` |
| 工具族定义 | `crates/ui-egui/src/state.rs:11-66` |
| 羽化点 / 路径插值（未采用） | `crates/path/src/feather.rs`、`crates/path/src/interp.rs` |
| 抗锯齿填充/描边（未采用，本项目有 AWT） | `crates/path/src/lib.rs:180-331` |
| 行为测试（AE 语义） | `crates/engine/src/tests_timeline.rs:361-411`、`crates/ui-egui/tests/ui_viewer.rs:766-863`、`crates/ui-egui/src/panels/viewer_overlays.rs:684` |

### 10.3 本项目索引（快照 22:45，按文本搜索更稳）

| 主题 | 文件:行 |
|---|---|
| 蒙版数据 | `src/main/java/dev/bbsae/comp/CompMask.java:12-137` |
| 形状数据 | `src/main/java/dev/bbsae/comp/CompShape.java:10-269` |
| 逐点通道求值 | `src/main/java/dev/bbsae/comp/CompLayer.java:1221-1304` |
| 通道写入 | `src/main/java/dev/bbsae/comp/CompLayer.java:592-641` |
| 蒙版栅格 | `src/main/java/dev/bbsae/client/render/MaskCache.java:58-298` |
| 形状栅格 | `src/main/java/dev/bbsae/client/render/ShapeRasterizer.java:31-235`、`ShapeCache.java:139-252` |
| 场景/投影 | `src/main/java/dev/bbsae/client/render/CompScene.java:58-426` |
| 相机 | `src/main/java/dev/bbsae/client/render/CompCamera.java:173-405` |
| 3D 工作区相机 | `src/main/java/dev/bbsae/client/render/CompViewportCamera.java:376-475` |
| 蒙版叠加/命中/拖拽 | `src/main/java/dev/bbsae/client/ui/UICompositorPanel.java:2398-2772` |
| 预览事件/轮询/松开 | 同文件 `:615-640`、`:4400`、`:4716` |
| 预览栏 | 同文件 `:4884`、`:4949` |
| 蒙版检查器 | 同文件 `:16037-16160` |
| 形状路径检查器 | 同文件 `:15927-15957` |
| undo/存盘/写标量 | 同文件 `:8263`、`:16676`、`:17384` |
| 曲线手柄镜像范式 | `src/main/java/dev/bbsae/client/ui/UICompCurveEditor.java:2478-2566`、`CompCurves.java:597-611` |

---

## 11. 待 Lead / 用户拍板的 5 个问题（请逐条回答）

1. 钢笔**同时**支持蒙版与形状路径（取舍 1-B）—— 确认？
2. 接受"路径动画 = 逐坐标通道"，不做 AE 的单一 Path 属性（取舍 3-A）—— 确认？
3. 已动画路径上**暂时禁止增删顶点**（取舍 4-A），P4 再做重映射 —— 可接受？
4. 3D 范围：**必须**支持"普通预览里的 3D 图层"；3D 工作区（取舍 6-B）是否本期做？
5. 「100% 没 bug」按 §8.5 的 7 条量化验收（含真机截图清单）—— 是否作为验收口径？
