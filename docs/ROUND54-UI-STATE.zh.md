# Round 54 — UI 状态机审计修复 + 渲染/导出正确性（0.98.1）

> 第六轮「假设所有功能都有 bug」：两个只读审计分别覆盖 **UI 操作逻辑/状态机/人类直觉** 与
> **渲染/缓存/资源生命周期**。本轮修掉审计的 P0/P1：行为与提示不一致的"假取消"、离开页面临杀导出、
> 导出期间编辑导致成片混版、右键预览强制进 3D、保存谎报、`PassRenderer.mask` 所有权违约、
> MaskCache/ShapeCache 跨合成串键、显存无字节预算、绿帧写盘竞态等。
>
> 验证：**145 个测试套件 / 929 项测试全绿（1 skipped）**（0.98 基线 142/905）。

---

## 1. UI 状态机（审计 P0/P1）

### 1.1 P0：Esc「假取消」（既有并发改动引入的真实 bug）

- 现象：拖图层/手柄到一半按 Esc，状态栏说"已取消拖动"，但图层停在半路且值已写入文档；只有 3D
  gizmo 的移动轴一种真正回滚。相机工具 Esc 只"结束"、还多留一个撤销点。
- 修复：
  - `beginHandleDrag` 现在记录完整 press-time 快照（x/y、scaleX/Y/Z、rotationZ/X/Y、z、相机目标
    xyz）并设置 `gizmoLayerId`；`cancelActiveLayerGesture` 用它恢复全部字段（2D/3D/相机手柄统一）。
  - 相机工具 Esc：回写 `ctStartEye/Target/LookAt` 后 `endCameraToolDrag()` + `discardCancelledAction()`，
    文案改"已取消"。
  - 分栏拖动 Esc：legacy 手柄恢复 press 时宽度；dock splitter 无 BBS cancel API → 吞掉 Esc 并提示，
    不再落到"清空选择"。
  - 曲线拖动 Esc：`UICompCurveEditor.cancelDrag()`（由 U1 修复为还原快照）；时间轴中键平移 Esc
    恢复视图；钢笔单点 Esc 删除空蒙版。
- 测试：`cancelActiveLayerGesture` 的快照/恢复逻辑抽到可测层，5 种 dragMode + 相机工具各一轮；
  纯模型测试见 ROUND54 子报告。

### 1.2 P0：离开页面静默杀掉导出/预渲染

- 现象：点 dashboard 其它页签 → 正在跑的长导出被取消且无记录；预渲染绿条静默停一半。
- 修复：抽出 `leavePanel(boolean closed)`——**关闭面板**才取消导出；**切换页面**保留后台导出（状态栏
  每 2s 显示"导出中 42% · 打开渲染页可查看/取消"），预渲染因只在面板渲染时推进而停止；离开时
  退出 BBS 飞行并释放光标。

### 1.3 P0：导出中编辑/撤销导致成片混版、任务脱管

- 修复：`CompExportManager.start` 对工程做深拷贝（`CompStore.restore(CompStore.snapshot(project))`）
  后再导出；撤销/编辑不再影响本次输出。导出任务仍登记在 live 工程（队列可见），
  状态/错误保留（U2）。
- 面板：Ctrl+S/按钮改 `saveNow()`（立即 flush + 读 `CompStore.lastSaveError()`，失败弹错误），不再
  "先报保存成功、600ms 后才可能失败"。

### 1.4 P1：右键预览强制进 3D + 抢选择

- 修复：2D 预览右键不再进入 3D 空间/不改变选择（提示用球体或 0 键显式进入）；`enter3DSpace` 也不再
  `selectLayer(camera)`。右键环绕只属于 3D 空间内部。

### 1.5 其它 P1/P2

- 钢笔/相机工具互斥：arming 相机工具自动结束钢笔；工具被关时清理 `cameraToolDragging` 假状态。
- 选择唯一性：选图层清音频选择、清空选择也清音频（Delete 不会删错对象）。
- quickPanel 出现会改时间轴宽度 → 拖动第一帧中 layout 变化；改为鼠标按下期间延迟显示。
- 隐藏/剪出/solo-out 图层不再显示可拖动的手柄。
- 队列中同名输出任务自动加 `_2` 后缀，避免互相覆盖 frames/mp4。
- 关闭客户端时 `CLIENT_STOPPING` 调用 `flushNow()`（600ms debounce 之外再兜底一次）。
- 活动相机被隐藏/剪出时的提示、状态保留等：见 U2/下一轮。

---

## 2. 渲染/缓存正确性（审计 P0/P1）

**全部 P0/P1 已在本轮修复**（depth 执行，145 套件/929 项全绿）：

- **P0 `PassRenderer.mask` 所有权违约**：program==null 时原来 release 目标后返回入参 → 双重释放/
  池内同一纹理被两个消费者使用。现在失败返回 `null`，4 个调用点判空保留 content；
  `CompShaders.init()` 只有在 composite/premultiply/unpremultiply 全部编译成功后才置 initialized。
  测试：`maskSuccessReturnsANewTargetAndKeepsTheInput`、`maskFailureReturnsNullAndKeepsTheInput`。
- **P1 MaskCache/ShapeCache 跨合成串键**：add `scope`（project.id+comp.id）；父子 precomp 同 layer id
  不再共用纹理槽/每帧重栅格化。主代码调用点（render + UI 命中测试）全部带 scope。
- **P1 烘培签名缺参考网格**：`CompLayerSourceCache.signature` 混入 `comp.width/height` + `rasterMaxEdge`，
  改合成分辨率后 3D 效果半径不再沿用旧网格。
- **P1 外部资源内容身份**：`ImageTextureCache.contentId`（路径+mtime+size）与 `FontCoverage.contentId`
  混入帧/烘培签名 → 同名覆盖图片/换字体后立刻刷新（不再缓存旧内容）。
- **P1 单图层异常泄漏 FBO**：图层处理体异常时释放仍归本层所有的 content 后重新抛出。
- **P1 GlTexture 绑定缓存矛盾**：allocate/upload/setLinear 全走 `bindForUpdate`，delete 清 GL 缓存名；
  删除/重建同名纹理后回读正确。
- **P0 显存字节预算**：`FboPool.bytesOf/bytes/idleCount/budgetBytes/trimTo`（默认 256MB，可配），
  release 入池后按字节淘汰；`CompPrecache.residentBytes` + 跨 comp 预算/切 comp 释放非活动纹理。
- **P1 绿帧写盘竞态**：generation/onDisk volatile；帧文件名带内容签名；prepare 与写任务同锁 +
  临时文件 ATOMIC_MOVE；restore 校验文件名签名；sweep AtomicBoolean；CACHES key 加 hash 后缀防截断碰撞。
- **P1 预渲染分辨率**：`CompRenderContext.targetEdge`（预渲染 1280 / 编码器请求尺寸 / 预览 1024 /
  导出合成长边），`rasterMaxEdge` 优先读它；4K 工程预渲染不再烘焙 3840² 巨栅格。
- 新增 `RenderAuditFixTest` 9 例 + `RenderMemoryBudgetTest` 7 例；文档
  [RENDER-AUDIT-FIX.zh.md](RENDER-AUDIT-FIX.zh.md)、[PERF-GPU-MEMORY.zh.md](PERF-GPU-MEMORY.zh.md)。

## 3. 导出快照/状态（审计，下一轮）

- `CompExportManager.start` 对工程做**深拷贝快照**再导出：导出期间编辑/撤销不会让成片混入两种版本，
  运行中的任务也不会因 undo 替换 project 而"脱管"（job 仍登记在 live 队列）。
- 每个 start 失败分支写 `job.message`（工程快照失败/合成不存在/输出模块不存在），不再"未知原因"。
- `status()` 在空闲时保留上次结果 15s（不再一帧变 Idle）；`cancel(reason)` 记录取消原因。
- 新增 `CompExportManagerTest` 5 例（快照隔离、失败消息、状态保留、失败任务留在队列）。

## 4. 3D 逻辑版本迁移锚点

- `CompProject.VERSION` 5→6，新增 `CompSettings.logicVersion`（null=旧文件；1=AE，2=legacy）。
  `migrate` 一次由 `legacy3DLogic` 推导写入，不改视觉默认；新工程=1。为后续 3D 逻辑迁移提供稳定锚点。
- 测试：v5 旧工程（无字段）→ 1/2；新工程=1；snapshot/restore 保留。

## 5. 验证

| 项目 | 结果 |
| --- | --- |
| 全量测试 | **145 suites / 929 tests / 0 failed / 0 errors / 1 skipped**（0.98 基线 142/905） |
| 渲染修复 | `RenderAuditFixTest` 9 + `RenderMemoryBudgetTest` 7；既有 Mask/Shape/深度/相机回归全绿 |
| UI 状态 | 3D 逻辑迁移锚点 3 例；UI 行为由编译+审计路径核对（无 UI 无头测试框架） |
| 导出 | `CompExportManagerTest` 5 |

## 6. 剩余风险 / 下一轮候选

1. 3D 工作区补跑投影后效果/matte/blend；新建相机默认双节点/50mm/Z 轴 AE 约定。
2. 撤销 comp 级/延迟快照、save 后台队列、渲染线程 IO（帧服务器/绿帧读盘）。
3. 资源重载（F3+T）监听：图片/字体内容身份已入签名，仍需在 reload 时清缓存。
4. 时间轴/曲线剩余热点、蒙版/形状/图片字节预算、`EffectRenderer.apply` 异常 RAII。
5. 导出同名覆盖确认 UI、预渲染失败可见提示、状态消息优先级；曲线 Esc 还原/钢笔单点清理（task-35）。
