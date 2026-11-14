# Round 117 — 回放编辑器 K 帧体验：与 BBS 原版逐项对比

> 版本 `0.99-hotfix84`。本轮先读 BBS FS 2.7 源码（`_research/bbs-fs-1.20.1`）和 bbsae 的
> `client/replay`，把“原版 K 帧流程”拆成可比较的步骤，再改代码。**不是**凭截图猜。

## 1. 原版的一条完整 K 帧链路

BBS 2.7 的回放编辑不是“选中回放 → 拖 gizmo”这么简单，而是：

```
Stencil 拾取（UI/FilmStencilPicker）
  → 鼠标下是哪个 form / 哪根 bone（stencilMap.increment = true，选中演员最后画）
  → UIReplaysEditor.pickFormBone / UIReplaysEditorUtils.pickForm
      → selectBodyPart(FormUtils.getPath(form))
      → 切到 POSE 分类
      → keyframeEditor 选中该 bone 最近的键，或创建 pose 编辑器
  → gizmo 目标（UIReplaysEditorUtils.getFilmGizmoTransform）
      有 bone/属性轨：keyframeEditor 的 UIPropTransform（骨骼矩阵）
      没有：UIReplayPropTransform（整条回放的位置/朝向通道）
  → GizmoDrag（UIReplaysEditorUtils.buildFilmGizmoDrag）
      GLOBAL = 回放自身朝向
      骨骼：getGizmoBoneCompositeMatrix（局部/父级两套基）
      回放根：Ry(-bodyYaw) 的 placement
  → G/S/R 或鼠标拖拽写通道
  → I（insertPoseKeyframesAtTick / pickForm insert）写入选中骨骼或位置/旋转
```

真正决定“K 帧手感”的是三件事，而不是按钮长什么样：

1. **拾取粒度为 bone**：`StencilMap.addPicking(form, bone)` 只有在 `increment = true` 时才会
   为每根 bone 分配独立 id；否则整个 form 共用一个 id，点击永远只能选中整条回放。
2. **gizmo 目标随选中轨切换**：`getFilmGizmoTransform` 先看关键帧编辑器有没有可编辑的
   transform（pose/bone/form transform），没有才退回回放根。
3. **时间轴把选中 part 的轨道显示出来**：`selectBodyPart(path)` 后
   `TrackCatalog.forPart(form, properties, path)` 只保留该 form path 的轨道，bone 行才存在。

## 2. 本轮修复前的 bbsae 差异

| 环节 | BBS 原版 | bbsae（hotfix83） | 后果 |
| --- | --- | --- | --- |
| Stencil 拾取 | 选中演员最后画、`increment=true`、每根 bone 独立 id | 所有回放都是 `increment=false`、整个 form 一个 id | 点模型只能选整条回放，拿不到部位 |
| Alt 拾取 | Alt：其他演员高亮/可选；选中演员的 bone 仍可拾取 | 没有 Alt 分支 | 用户说的“Alt 选部位”不存在 |
| 拾取回调 | `pickFormBone → pickForm → 选中 bone 轨/键` | 只把点击当成“选择回放索引” | 部位不会体现在时间轴 |
| 部位列表 | `selectBodyPart(path)` 驱动轨道过滤 | 只有左侧 bodyParts 下拉，3D 不回写 | 必须手动去左侧选部位 |
| gizmo 目标 | 骨骼轨 → 骨骼 transform；否则回放根 | 永远是回放根 | 选中部位后 gizmo 仍在脚下 |
| gizmo 手势 | `UIPropTransform` 有 root/context，`pumpIfHidden` 驱动 | `UIReplayPropTransform` 是脱离 UI 树的私有对象，`getContext()==null`；`Gizmo.start()` 仍返回 true | **gizmo 能显示、拖动完全不动** |
| 骨骼 gizmo 位置 | `FilmMatrices.getGizmoBoneCompositeMatrix` | 只用 `getMatrixForRenderWithRotation`（脚下） | 手柄与部位错位 |
| 绕对象旋转 | 切换回放时 orbit 可 teleport 到当前对象 | orbit 只到演员脚点 | 选部位后旋转中心不对 |
| I 打帧 | 选中 bone → 该 bone 的 pose 键；否则位置/旋转 | 只有位置/旋转或整段 pose | 部位 K 帧无法只打选中骨骼 |
| 布局 | 停靠面板可拖 | 回放页内部 190/252 固定列、比例时间轴，无 splitter | 解锁布局也改不了回放页大小 |

## 3. 本轮改动（对应上面的差异）

- **拾取**：`ReplayPickPass` 把选中的演员放到最后画，`stencilMap.setIncrement(true)` 并从
  `replays.size() + offset` 开始编号。其他演员仍是 `increment=false` 的单 id。
  `pickedForm()` 暴露 `(form, bone)`。
- **Alt / 点击**：`UIReplayViewport` 左键先处理 Alt→其他演员，再处理 `(form, bone)`→部位，
  最后才是“选择整条回放”。与原版一致：Alt 点别人=切演员；点自己=选部位。
- **部位 → 轨道**：`UIReplayModePanel.selectPartFromViewport` 把 `FormUtils.getPath(form)`
  交给原来的 `UIReplaysListPanel.setBodyPartsReplay`，重建轨道后再调用原版
  `UIReplaysEditorUtils.pickForm(keyframeEditor, cursor, form, bone)`；没有复制一套 K 帧编辑器。
- **游标**：新增 `replayCursor`（`ICursor`），把原版 `pickForm` 里的 `cursor.setCursor(tick)`
  映射到回放 session 的播放头，而不是借用的影片面板游标。
- **骨骼 gizmo**：选中骨骼轨后，gizmo 目标改为关键帧编辑器的 `UIPropTransform`；
  `ReplayEditSession.boneGizmoDrag` 用骨骼的 local/parent 两套矩阵构造 `GizmoDrag`。
  `ReplayPickPass` 也把 gizmo 的 stencil 画在骨骼矩阵上，保证拾取/拖动/画面同一点。
- **gizmo 能拖**：`ReplaySessionTransform` 覆写 `getContext()`（使用页面 context），
  并保持 `isVisible()==false`，让 `GizmoInteraction` 每帧 `pumpIfHidden` 驱动手势。
- **绕对象/旋转中心**：`selectedPartWorld()` 用同一骨骼矩阵取 pivot；
  `UIReplayViewport.followSelectedActor` 在 orbit 模式使用该 pivot。
- **K 帧**：选中部位时 `I/K` 走原版 `pickForm(..., bone, true)`，只给该骨骼插入键；
  未选部位仍写位置/旋转（Shift 写整段 pose）。
- **布局**：回放页新增左右两条列 splitter 和底部时间轴 splitter，宽度/高度写
  `CompPrefs.replayListWidth/replayPropsWidth/replayTimelineHeight`。它们不依赖
  compositor 的 dock lock —— 那个锁只管理 dock panel，本来就不可能改变本页内部布局。

## 4. 验证

- `ReplayInteractionContractsTest`（4）锁住 context/gizmo、stand-in actor 持久化、
  part pick/骨骼 gizmo、内部 splitter 的契约。
- 真机（`runClientView -PbbsaeView=replay -PbbsaeReplayGizmoScript=true`，单次启动）：
  - `drag started=true`，通道 `x=0.00 → 1.50`：真实 `Gizmo.start → pump → accept` 路径写入成功；
  - `gizmoStarted=true channelsMoved=true partSelected=true layoutSplit=true`；
  - 布局日志：`list=260x236 / viewport=388x236 / props=300x236 / listSplit=6 / propsSplit=6 /
    timelineSplit=940x6`，证明三个 splitter 真实存在并改变了几何。
  - 部位骨骼验证：脚本把测试回放换成 BBS 内置 `player/steve_bends`，日志先拾取
    `bone='anchor'`，第一次 I 后 `editorBone='anchor' boneGizmo=true boneTransform=true`
    （`docs/verify/round117-gizmo-part.png`）。MobForm（测试影片里的 bridge chicken）没有骨骼轨，
    只显示 `editorBone=''`，这是数据限制不是失败。

## 5. 仍有边界

- MobForm 没有骨骼轨，Alt 点部位只能选中整个 form；需要 ModelForm/带骨骼的模型才有 bone 轨。
- 骨骼 gizmo 的 LOCAL/PARENT 帧、IK 约束提示沿用 BBS 矩阵计算，但 bbsae 时间轴不是原版
  `UIPoseKeyframeFactory` 的完整属性面板；本轮只复用原版 pick/插入与变换目标，没有重做
  pose 编辑器 UI。
