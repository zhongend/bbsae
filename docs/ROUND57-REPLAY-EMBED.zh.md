# Round 57 — 回放编辑嵌入主界面 / 合成相机实体

> 版本仍为 `0.99`（后缀更新，不迭代 1.0）。本轮完成需求 4，并把需求 5/11 的第一条真实渲染链路打通：
> 3D 回放实体层不再只是世界帧贴片，而是用 bbsae 合成相机投影 BBS 模型。

## 1. 回放编辑变成 bbsae 的第二个工作区（需求 4）

- 顶栏下新增 22 px 模式条：**合成 / 回放**。回放不再是 dashboard 里的独立页/独立栏，也不再有
  自己的标题栏、播放按钮和时间码。
- 进入回放：
  - `UIReplayModePanel.setEmbedded(true)` 隐藏它原来的 header，主体向上占满；
  - 合成器隐藏 dock/render/timeline，显示嵌入的回放工作区；
  - 模式条右侧提供 **播放/暂停、上一帧、下一帧、框选全部、模型库、原版回放编辑**；
  - 时间码只在回放模式显示，由 `ReplayEditSession.playhead()` 每帧格式化。
- 回到合成：
  - 回放面板 `disappear()` 交还借用的 film panel；
  - 把回放 playhead 写回合成时间（`this.time = playheadSeconds()`）；
  - 恢复合成 header 的播放/时间码，隐藏回放 transport。
- `UIReplayModePanel.runFilmHistory` 的「dashboard 被切走就恢复本页」分支在嵌入模式下跳过：
  当前 dashboard 页是合成器，这是正确状态，不是需要恢复的回放页。
- `BbsAeClient` 不再注册「实体回放」dashboard 页；`openReplayMode()` 现在只是
  `dashboard.setPanel(compositor); compositor.openReplayWorkspace()`，旧的调试脚本仍能通过
  `replayModePanel()` 拿到嵌入实例。
- 嵌入后 `ReplayInteractionScript` 的 move/undo/redo/form/list 全部通过，日志确认
  `panelArea=960x456 currentPanel=UICompositorPanel`，撤销/重做不再切页。
- 截图：`docs/verify/round57-replay-embed.png`（嵌入后的回放工作区，模式条/时间轴/3D 视口同屏）。

## 2. 3D 回放实体 = BBS 模型 × bbsae 合成相机（需求 5/11 第一条链路）

新增 `ReplayActorPass`：

1. 从 `BbsPreviewBridge` 取当前 film、`RunnerCameraContext.entities`；
2. 按 `context.time * 20` 求 tick，给每个启用的 replay 应用关键帧 + form properties；
3. 以可见 actor 的世界坐标中心为锚点，把方块映射到合成像素：
   `comp = CompScene.world(layer) · scale(px, -px, px) · (world - actorCenter)`，
   `px = clamp(compHeight*0.12, 8, 64)`。
4. 用 `CompScene.camera(context).view/projection` 作为相机，BBS 的 `FilmEntityRenderer`
   正常渲染 form（皮肤、骨骼、锚点、形态属性全走 BBS 原路径）；
5. `ReplayEntityMask.captureActorState` 在该 pass 期间被屏蔽，避免合成相机覆盖世界抠像的相机快照；
6. 结果作为全合成透明图绘制，后续效果/蒙版/不透明度仍由合成器处理。

- 只对 **3D entity layer** 启用；2D entity layer 保持 AE 语义的屏幕固定 world-plate 抠像。
- 真机验证工程：`-PbbsaeView=replay -PbbsaeReplayComp=true`，日志
  `Replay entity coverage: 1920x1080 entities=1`，截图中 3D 回放实体层在 320×180 合成里
  显示为 BBS 模型（不是世界照片），层属性/轨道/时间轴都正常。
- 截图：`docs/verify/round57-comp-actors.png`。

### 已知边界（下一轮）

- actor 的 `layer` world 矩阵（平移/旋转/缩放/父级）现在参与 actor mapping；层的
  `width/height` 不会裁剪模型，模型以图层原点为锚点。下一步可加 `entitySpace=COMP_3D` 开关让
  2D entity layer 也使用同一路径。
- 2D entity layer 仍是 world plate；如果用户希望 2D 也显示朝向相机的模型，可加一个
  `entitySpace=COMP_3D` 开关。
- 回放工作区左侧/右侧仍是 BBS 原生列表/属性面板，模式条和主体已经统一，但两栏视觉还没
  完全 bbsae 化。

## 3. 验证

- 全量测试：`145 suites / 940 tests / 0 failures / 1 skipped`（本轮最终测试）。
- 真机：
  - `-PbbsaeView=replay`：嵌入式回放页，move/undo/redo/form/list 脚本全通过；
  - `-PbbsaeView=replay -PbbsaeReplayComp=true`：3D 回放实体在合成相机中的首帧；
  - 原 `-PbbsaeView=demo` 效果库布局不变。
