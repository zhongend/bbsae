# Round 79 — 回放编辑恢复 BBS 原版 / 相机世界 / 运动路径 / 拖拽

> 版本 `0.99-hotfix37`。只加后缀，不迭代 1.0。

## 1. 为什么回退 bbsae 自己的回放时间轴

前几轮把回放工作区做成了 bbsae 自己的图层列表 + 简化关键帧行，结果丢掉了 BBS 回放编辑的核心：

- 回放列表/属性不是原版，模型动作轨道只有 bbsae 的 x/y/z/rotation；
- 姿态、骨骼、快捷栏、装备、操作、材质等轨道无法编辑，K 帧也写不回 BBS 回放；
- 预览里没有 BBS 原生 gizmo，只有 bbsae 自己画的 handles。

本轮不再维护第二套回放数据/UI，而是把 BBS 原版回放编辑器整体放回「回放」工作区：

- 左：`UIReplaysListPanel`（增删/复制/搜索/Body parts/模型库）；
- 右：`UIReplayPropertiesPanel`；
- 下：`UIKeyframeEditor` + `UIFilmKeyframes` + `ReplayTrackBuilder`，轨道来自
  `TrackCatalog.forPart(...)` 和 `ReplayKeyframes.CURATED_CHANNELS`，与 BBS 影片编辑器一致；
- 左下的分类栏：All / Replay / Form / Pose / IK / Physics，过滤规则直接调用
  `UIReplaysEditor.categoryOf(sheet)`；不另建轨道分类表；
- 原版 `Gizmo.INSTANCE` + `GizmoInteraction` + stencil 拾取：外观、轴向、命中、写回都是 BBS
  自己的实现；
- 预览：`UIReplayViewport` 的 bbsae 虚拟世界（`ReplayModelPass` 把 film 演员绘制到私有深度
  目标），不显示实时 Minecraft 世界，也不画 bbsae 自绘 gizmo。

## 2. K 帧

- 模式栏新增 K 按钮：`UIReplayModePanel.insertKeyframeFromHost(false)`。
- 快捷键：
  - `I` / `K`：在播放头写入位置+旋转；
  - `Shift+I`：再写入姿态轨道（`UIReplaysEditorUtils.insertPoseKeyframesAtTick`）。
- 写入是原版数据：`Replay.keyframes.record(tick, entity, [position, rotation])`，包在
  `BaseValue.edit` 中，所以 undo 走 BBS film 历史；不会写到 bbsae 的平行时间轴。
- 轨道本身仍保留 `UIKeyframeEditor` 的右键工具（复制/粘贴、关键帧样式、插值、曲线、预设等）。

## 3. 相机世界里的 bbsae 实体回放

新增 `CompViewportRenderer.drawReplayModels(...)`：

1. 对每个可见、独立、`hasModelForm()` 的 bbsae 回放图层，用 `ReplayActorPass.renderModel`
   以自由相机（`camera.view` / `camera.projection`）渲染到该图层自己的深度 FBO；
2. `PassRenderer.unpremultiply` 反预乘；
3. `LayerContentRenderer.drawFullscreenActors` 把结果铺回 3D 工作区 scene target。

模型 overlay 在普通图层和 adjustment 之后绘制，和主合成器“模型永远在卡片之上”的规则一致；
2D 预览/导出仍由 `CompRenderer` 的最终模型 overlay 绘制，不受 3D 工作区代码影响。

## 4. 摄像机运动路径

- 新增 `renderCameraMotionPath2D`：2D 预览按合成坐标 x/y 绘制 eye 轨迹 + x/y 关键帧点；
  以前只有 3D 工作区画路径，切到平面/摄像机视角就消失。
- 3D 工作区现在对**所选摄像机或 active 摄像机**都画路径，选择非 active 摄像机不再丢路径。
- 2D 的 camera gizmo 也允许绘制所选摄像机（不再只画 active），方便选中第二台相机调整。

## 5. 摄像机拖拽

`CompViewControls.orbitFromStart` 改成显式 pivot/radius：

```java
orbitFromStart(camera, frame, pivot, radius,
    startYaw, startPitch, startX, startY, startZ, yawDelta, pitchDelta)
```

- 每帧都从按下时的 pivot、半径、角度重新算 eye，不读 `scalarAt("rotationY", ...)` 等当前通道；
  之前拖拽写下的关键帧会被下一帧当作“起始值”，总位移被反复叠加，越拖越快/乱转。
- BBS 飞行相机即使 `cameraLookAt` 残留为 true，也使用视线前方的 pivot，不读陈旧目标点；否则
  第一帧会把 eye 甩到旧 target 方向。
- `CompViewControlsOrbitTest` 新增带 rotation 关键帧的幂等测试：连续两次相同总位移结果完全一致，
  且 eye 到 pivot 的距离保持 900。

## 6. 验证

- 全量测试：**152 suites / 951 tests / 0 failures / 0 errors / 1 skipped**。
- 真机回放交互：`move`、`undo`、`redo`、`form`、`list` 全部通过，`undoRestored=true`。
- 截图：
  - `docs/verify/round79-bbs-replay-restored.png`：原版回放列表、属性、全轨道、原生 gizmo、
    bbsae 虚拟世界预览；
  - `docs/verify/round79-3d-camera-actors.png`：3D 相机世界中的 bbsae 实体回放；
  - `docs/verify/round79-camera-path-2d.png`：2D 预览中的摄像机运动路径。
- 候选包：`bbsae-0.99-hotfix37.jar`（3,883,663 bytes，SHA-256
  `B40055E122AC2A9ED8CD2684DFA47E933F991A53F1419A5502F429C0D295737F`）。

## 7. 仍未完成

- 回放分类栏目前只有分类过滤和全部展开/收起，BBS 的 actions 时间轴、IK bake、按键数字快捷
  切换分类等回放专属动作尚未搬到嵌入式工作区；轨道和 K 帧本身已走原版数据。
- 运动路径是折线采样（48 段），没有 BBS 世界 pass 的 ribbon 宽度/渐变；在 2D 平面视图里
  z 深度被有意压平。
- 相机工具的手势仍只覆盖 orbit / Track XY / Track Z，BBS 飞行模式之外的其它镜头手势未逐一审计。
