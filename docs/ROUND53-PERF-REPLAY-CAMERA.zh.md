# Round 53 — 回放 undo 后 UI 消失根治 + 3D 相机/图层逻辑审计 + 存档/曲线性能（0.98）

> 第五轮「假设所有功能都有 bug」。本轮最重要的成果：**根治了回放模式 Ctrl+Z 后整个仪表盘 UI 消失
> 的 P0**（数据一直正确，画面不显示的根因是 BBS film undo 路径留下 `BBSRendering` 自定义渲染尺寸，
> 把界面从窗口帧缓冲里挤掉了）。同时完成存档/撤销与曲线/时间轴性能审计修复，并对需求 1 的
> 3D 相机/图层逻辑做 AE 语义审计与修复。
>
> 验证：**142 suites / 905 tests / 0 failed / 0 errors / 1 skipped**；客户端 2 次回放真机运行（第 2 次确认 undo/redo/form/list 后 UI 完整、
> 数据全部正确）。

---

## 1. P0 根治：回放 undo/redo 后回放页 UI 停止绘制

### 1.1 现象与历史

- 0.95–0.97 期间，回放模式按 Ctrl+Z（或脚本 undo）后，屏幕只剩世界 + BBS 世界 gizmo；数据恢复
  全部正确（`undoRestored=true`），但整个回放页 UI 不再出现。
- 0.96/0.97 做了 6 次诊断：确认 `currentScreen` 打开、页面 `visible=true`、area=960×520 正常、
  dashboard 当前 panel 仍是回放页、根子元素列表/父链完整；本轮进一步确认 `UIReplayModePanel.render()`
  **确实被调用**（render 日志），所以不是"页面没渲染"，而是"画出来但看不到"。

### 1.2 根因

BBS 的 film undo 路径 `UIFilmPanel.applyUndoData → showPanel → applyPreviewSizeToBBS` 会把影片的
自定义预览尺寸写进 `BBSRendering` 的自定义渲染尺寸（日志 `[BBS film] setCustomSize customSize=true
w=4 h=2` / `w=1042 h=586`）。`BBSRendering` 在该标志为 true 时用**替换后的帧缓冲**渲染世界/预览；
undo 结束后这个标志没有被关闭，于是窗口帧缓冲只留下世界画面，dashboard 的 UI 画进了另一条渲染
目标 —— 与 0.96 曾怀疑的"BBS 视频尺寸 4×2"不同，真正要恢复的是 **BBSRendering 的自定义尺寸状态**。

### 1.3 修复

- `UIReplayModePanel.runFilmHistory(...)`：在 undo/redo 前记录 `BBSRendering.isCustomSize()`，在
  finally 中若"undo 前是 false、undo 后变成 true"则调用 `BBSRendering.setCustomSize(false)`；
  同时保留已做的 BBS 视频宽高/帧率全局设置保存恢复（borrowed film panel 不能改客户端呈现参数）。
- 保留 GL 状态日志（fbo/depth/blend）与页面 render 到达日志，便于以后 BBS 内部渲染路径变化时定位。
- **真机验证**：Run 53b 的 4 张截图（move/undo/form/list）全部显示完整回放页 UI，模型/gizmo/列表/
  属性/轨道正常；日志 `UNDO ... restored=true`、`done failed=false`。
- 证据：`docs/verify/round53-replay-{move,undo,form,list}.png`。

---

## 2. 3D 相机 / 图层 3D 逻辑（需求 1 校验）

用户原话：「图层无论是否开启 3d 效果，都会在 3d 世界中呈现出 3d 效果，这跟 AE 相机逻辑不符合」。
只读审计把问题拆成两个 P0（相机与图层）、两项排序/活动相机 P1、一项 depth 门控 P1 与一项死代码 P2。

### 2.1 P0-1：2D 层完全忽略相机（AE 语义）

- 新增 `CompScene.cameraAffects(ctx, layer)`：AE 模式 = `layer.threeD`（camera/adjustment 恒为
  false）；`legacy3DLogic=true` 时恒为 true（旧工程所有内容层继续跟相机）。
- `CompScene.model()`：受影响层 = `view · world`（透视）或 `view2D · world`（正交）；不受影响层
  = `world`（合成像素原地）。`CompScene.project()` 对 2D 层直接 world→comp 像素，不再先乘
  `view2D`。AE 下投影矩阵不变：3D 层透视、其余正交。
- 效果与旧语义一致的部分全部保留：3D 层在正交相机下仍走 `view2D + ortho`，z=0 时与透视视图
  逐像素一致（原有不变量测试继续通过）。
- 删除了 `model()` 里 `threeD && spatial && !usePerspective` 的死分支与误导注释——该组合在
  `spatial ⇒ perspective` 的前提下不可达，2D 层也不再需要 `projectPlane` 二次投影。

### 2.2 P0-2：3D 工作区的 2D 层屏幕固定

- `CompViewportRenderer.viewportFit()`：把合成矩形按预览同款 contain-fit 映射进视口
  （`min(vw/cw, vh/ch)` + 居中偏移），与预览 fit 同方向同比例。
- `screenFixedQuad()` / `layerQuad()`：2D 层四角 = fit ∘ world（不经过 orbit 相机），
  不再随轨道旋转透视、缩小或跑到相机背后；`layerQuad` 同时用于选择框和命中框，画面与可点区域
  不会再分叉。
- `drawLayers()`：相机影响的层仍走原 3D 路径（3D 层 depth-tested quad；legacy 的 2D 层因
  `threeD=false` 走投影网格）；2D 层按栈序在自身槽位用 `emitScreenQuad()` 直接以视口像素绘制
  （无 orbit 透视）。
- `sortByViewRuns()`：只有连续 `cameraAffects` 层组成 run 并参与 orbit 深度排序；2D 层保持栈位
  并打断 run（legacy 模式仍是旧的「全部内容层排序」）。
- `layerAt()`（`pick()` 保留为兼容别名）：按绘制顺序反向命中，2D 层用 fit 四边形测试，3D 层用
  orbit 投影四边形，null 仍按 10px 原点；点击顺序与画面一致。

### 2.3 P1：2D 层打断 3D run（合成排序）

- `CompScene.sortsIn3D(ctx, layer)`：AE = `threeD && hasPixels`；legacy = 内容层在 spatial 帧
  全部参与排序。
- `CompRenderer.orderLayers()` 改为按 run 排序：只有连续真实 3D 像素层按 `planeDepth` 远→近
  排序；2D/调整/无像素/null 保持槽位并打断 run；正交相机下 3D run 仍排序。稳定排序保证共面层
  继续按栈序。旧的「spatial 时把 2D 也拖进排序」逻辑只保留在 legacy 模式。

### 2.4 P1：活动相机按帧有效性解析

- `CompDocument.activeCamera(time)`：`activeCameraId` 只在该相机仍是 camera、visible、且
  `activeAt(time)` 时生效；否则回退到栈顶有效相机；都没有则返回 null，`CompCamera.of` 使用默认
  1:1 视图。隐藏/剪掉的活动相机不再「继续驱动画面」，相机选择器、gizmo 与渲染结果一致。
- 新增 `CompDocument.isUsableCamera(layer, time)` 并补充隐藏、剪出、栈顶回退、无相机默认视图
  四组测试。

### 2.5 P1：spatial / AUTO 只统计真实 3D 像素层

- `CompScene.hasPixels(layer)` 成为唯一「该层拥有像素」判据（camera/null/adjustment 为 false），
  `CompRenderer` 的私有副本已删除并改为调用它。
- `CompScene.hasDepth(ctx)`：仅统计 `threeD && visible && activeAt(frame) && !soloedOut &&
  hasPixels` 的层；2D 层残留 z、null/adjustment、被隐藏/剪掉/被 solo 掉的层、相机自身 dolly z
  都不再让整帧进入 spatial。
- `CompRenderer.needsPerPixelDepthAuto()`：同样只考虑上述真实 3D 像素层；2D 层即使屏幕包围盒
  重叠也不会触发逐像素深度（legacy 模式也一样，AUTO 是 3D 像素概念）。
- `projectPlane()` 及其两处调用（flat matte、flat layer）随 P0-1 一起删除；`is2D` 中间变量同步
  移除。`effectsRunOnProjectedContent` 注释更新为「正交内容直接在合成空间合成」。

### 2.6 新公开 API（UI 侧接法）

- `CompScene.compToLayerLocal(ctx, layer, compX, compY)`：合成像素 → 图层局部坐标（2D 用
  world 逆矩阵 + 局部 z=0 平面，3D 用相机射线与图层平面求交）。UI 应先用自己的预览 fit 把
  屏幕像素换成 comp 像素，再调用它：替代 `UICompositorPanel.screenToLayerLocal` 里
  `view2D · world` 的逆，以及 `layerAt` 里的 `camera.unproject`。
- `CompViewportRenderer.layerAt(...)`：`pick(...)` 的正式名字（`pick` 保留兼容）；UI 无需改调用
  也能得到新的 2D 屏幕固定命中规则。`CompViewportRenderer.layerQuad(...)` 仍是选择框/缩放手柄
  的唯一几何来源，已自动覆盖 2D 屏幕固定。
- `CompScene.hasPixels(layer)`、`CompScene.cameraAffects(ctx, layer)`、`CompScene.sortsIn3D(...)`、
  `CompScene.hasDepth(ctx)` 可供 UI 判断层是否可投影/可命中/应参与排序。
- **UI 侧仍待办**（本轮写域未包含 UI）：
  1. `UICompositorPanel.screenToLayerLocal` / `layerAt` 仍按 `view2D · world` 与
     `camera.unproject` 反算 2D 层；相机有 pan/zoom/roll 时会与新的 `CompScene.project` 不一致，
     应改为「预览 fit → comp 像素」+ `CompScene.compToLayerLocal`。
  2. `UIPenTool.screenToLocal` 的 2D 分支同理改为 `compToLayerLocal`；3D 工作区里 2D 层的笔
     工具屏幕位置应使用 `CompViewportRenderer.layerQuad` 的 fit 四边形。
  3. 预览内 2D 层的拖动/手柄补偿（`UICompositorPanel` 中 `view2D` 逆矩阵、`world3D` 旧假设）
     需要跟随新的 model/project 语义；否则相机平移后拖动会有偏移。
  4. `CompCameraOps.hasCamera` 的注释仍写「visible or not」，与新活动相机规则不符（写域外）。
  5. 相机预览按钮、`activeCamera` 选择器在「活动相机被隐藏/剪出」时会看到回退结果，需要按新
     规则提示用户。

---

## 3. 存档/撤销性能（性能审计 P0-3）

- `CompStore.saveChecked` 全流式：旧文件用 `JsonReader` 流式扫描（版本守卫 + `.bak` 可读性共用
  一次扫描），去掉 `toJson` String + 回读 parse；`GSON.toJson(project, Writer)` 直写 `.tmp`，再用
  流式扫描验证；`.bak` 改为 `Files.copy`。300 层/5100 键实测：saveChecked 64–66ms（旧 88–107ms），
  snapshot 33ms / 885,123 chars（旧 35–45ms / 1,242,702 chars，省 28.8%）。
- `prepareTracksForWrite`：只对「活通道 + 序列化字段全空」的层跳过 `saveTracks()`（可证 no-op），
  有键层照旧重建；`-Dbbsae.debugSave` 分段打点；`SaveIoStats` 供测试断言「0 次全量 parse、0 次
  JSON String」。新增 `SavePathPerfTest` 8 例 + 持久化回归。

---

## 4. 曲线编辑器 / 时间轴性能（性能审计 P1-8 / P1-11）

- **曲线编辑器**（P1-8）：`CurveSampler` 复用单个 `KeyframeSegment`、基本类型插值（无 Float 装箱），
  段游标 + revision/lower-bound 校验 + 二分回退；>400 列降采样；`keyAt` ±10px tick 区间二分；
  `renderKeys` 只遍历可见键、只对 hover/未收敛键调 `UiMotion.anim`。新增 `CurveSamplingPerfTest` 5 例
  （逐点对比 6 种插值、零分配、降采样/NaN）。
- **时间轴**（P1-11）：`CompPrecache.CoverageCache`（generation+帧数+cursor+stale 四值 key），静止时
  绿条重建 0 次；`renderKeyframeTicks/keyframeAt` 直接遍历通道 + 可见 tick 区间二分，移除
  `allKeyframes()` 每帧拷贝；`firstVisibleRow/visibleRowEnd` 抽取并有测试。新增 `TimelineCoverageCacheTest`
  5 + `TimelineVisibleRowFilterTest` 6。
- 剩余热点：loop 通道回退旧 `valueAt`、`animatedPaths()` 每行每帧仍拷贝、绿条 evict 极短窗口旧 bit
  （见 PERF-TIMELINE.zh.md）。

---

## 5. 验证

| 项目 | 结果 |
| --- | --- |
| 全量测试 | **142 suites / 905 tests / 0 failed / 0 errors / 1 skipped**（0.97 基线 136/857） |
| 回放真机 | 2 次（Run 53a 复现 + 53b 修复验证）：undo/redo/form/list 后 UI 完整、数据全部正确 |
| 相机/默认布局真机 | 1 次合成器截图：2D 层（文字/形状/白卡）与 3D 层同屏正常、效果列表位于预览右侧、无崩溃（`docs/verify/round53-default-layout.png`） |
| 3D 相机审计 | 44 项定向测试：Camera3DTest 18（AE/legacy 2D 投影、pan/zoom/roll、活动相机、plane depth）、CameraLogicModeTest 14（两种 3D 逻辑、hasDepth 过滤、compToLayerLocal 往返）、CompLayer3DRunOrderTest 6（2D 打断 run、正交排序、legacy 全排序）、CompViewportSortTest 6（2D 屏幕固定 quad/命中、run 排序）；另有 37 项 per-pixel depth/AUTO 回归（AutoMode 10、AutoPolicy 6、Routing 6、Switch 7、Ghost 4、State 4）全绿 |
| 存档/撤销 | SavePathPerfTest 8 + CompStorePersistenceTest 16 |
| 曲线/时间轴 | CurveSamplingPerfTest 5 + TimelineVisibleRowFilterTest 6 + TimelineCoverageCacheTest 5 |

## 6. 剩余风险 / 下一轮候选

1. 回放 fix 依赖 BBS `BBSRendering.setCustomSize` 公共 API；若未来 BBS 改了帧缓冲策略需重新验证
   （已留 GL 状态日志）。
2. 3D 工作区补跑投影后效果/matte/blend（草稿契约已明确）。
3. 撤销仍保存整个工程 JSON：本轮已降低序列化/写盘成本，但"comp 级撤销/流式后台写"仍是下一步。
4. 曲线/时间轴剩余热点：预渲染纹理淘汰、大工程可视区域裁剪（见 PERF-TIMELINE）。
