# Round 56 — 效果库在左 / 独立效果页 / 预览缩放 / 效果底层修复

> 本轮对应需求 2、3、6、7、8、9、10、14 的第一批落地。版本仍是 `0.99`（按用户要求只加后缀，
> 不迭代到 1.0）。未完成的 4/5/11/13 在文末列出，下一轮继续。

## 1. 界面结构（需求 2）

- 默认 dock 树改为：**效果/素材/预设（左） | 预览（中） | 属性（右）**，时间轴仍在底部。
  旧默认（预览在左、效果+属性堆在右）保留为布局菜单里的「效果库在右侧（旧版布局）」。
  旧工程的 `dockLayout` 由 `CompProject.VERSION=7 + uiLayoutRevision` 迁移一次；自定义布局不覆盖。
- 检查器标签顺序改为：**调色 / 图层 / 蒙版 / 效果 / 文字 / 合成 / 输出**。
  原「效果控件」页拆成：
  - 「图层」：片段、运动/3D、不透明度、来源、父级、蒙版、表达式、跟踪器、预合成；
  - 「效果」：效果栈 + 每个效果参数 + 图层样式。
- 顶栏「效果库」按钮删除；左侧「效果库」标签就是入口，右侧的图片浏览器入口是左栏右上角
  的图片图标。`focusEffectLibrary()` 不再打开全屏覆盖层，只切回左栏。
- 左栏 tab 条/检查器 tab 条给 6 px dock splitter 内缩，避免「»」溢出按钮被分隔条吃掉点击。
- 多选时「图层」页显示多选提示，不再静默只改主图层。
- 真机截图：`docs/verify/round56-ui-effects.png`（960×540 GUI，效果库在左、预览居中、效果页在右）。

## 2. 动效（需求 7）

- `UiMotion.spring` 的阻尼从线性 `max(0,1-d*dt)` 改为 `exp(-d*dt)`：50 ms 帧下旧公式会
  `1-30*0.05 = -0.5`，导致按钮/标签在 20 Hz 翻转而不是收敛。
- `springStep` 的阻尼现在真正参与计算；新增命名弹簧 `SPRING_PANEL/PRESS/TAB` 和
  `press()/panel()`，按钮、图标按钮、tab 下划线统一用同一组物理参数。
- 新增 `EASE_OUT_QUINT` / `EASE_IN_OUT_QUART`；页面过渡改用 quint（快起、长软着陆）。
- `intro()`/`introBack()` 改读可注入的 `nowNanos` 时钟，入场动效可在无头测试中固定。

## 3. 预览自适应分辨率（需求 10）

- `CompRenderer.previewScale` 现在把 **GUI 像素 × GUI scale** 换算成设备像素：GUI scale 2 的
  960×540 面板不再按 0.5× 渲染，而是按 50% 的实际设备像素渲染（截图里的「预览 50%」）。
- 绿色预渲染帧只有在 `cacheScale >= 请求 scale` 时才使用；以前无条件把 1280×720 上采样，
  大面板会一直发虚。
- 预览信息栏显示实际预览百分比（窄预览自动隐藏，避免挤占飞行 HUD）。
- 新增 `PreviewProxyTest.previewUsesDevicePixelsForGuiScale`。

## 4. 效果底层（需求 14 第一批）

- **3D/2D 烘焙 UV 根因**：`EffectSpace.bind()` 现在向 shader 传 `CompSize`
  （`LayerX/LayerY/LayerOrigin` 所在的栅格网格）。此前 shader 用 `Size`（父合成网格）做除法，
  在 3D 烘焙里把 600 px 栅格的坐标除以 1920，扭曲/置换/玻璃/镜像全部采样到左上角。
  2D 代理预览仍用 `compWidth=1920`，所以 2D 不变；3D 烘焙与导出统一。
- 新增/修正 `CompSize` 到 DISTORT / PIXEL / REPEAT / REPLICATE / SHATTER / AM 全部 shader，
  GL 测试手写 uniform 也同步。
- **模糊上限**：`PassRenderer` 200→1024，方向模糊 200→1024，`EffectPadding` 512→1024，
  高斯/方框/发光滑杆上限 1024；BLUR shader 的 tap 数改为随半径增长（8..64 tap），
  大半径不再 25 px 一步产生梳状条纹。
- **重复上限**：replicate 的 count 32→64、行列 8→20（shader 循环同步），与 UI 滑杆上限一致。
- **扭曲**：bulge/pinch/twist 的 padding 按 `1/(1-amount)` 实际采样范围计算（旧值只有 3 px，
  3D 烘焙在图层边缘硬裁）；修掉 `smoothstep(edge0>edge1)` 的未定义行为。
- 新增 `EffectPaddingTest`、`Shader*Test`、`EffectOrientationTest` 回归均通过。

## 5. 钢笔（需求 9 第一批）

- `Set First Vertex` 的模型旋转方向修正为 `Collections.rotate(points, -shift)`，与关键帧通道的
  `new[i]=old[(i+shift)%n]` 一致；旧代码只在 `2*shift ≡ 0 (mod n)` 时正确，旧测试恰好只用
  n=4/shift=2。新增 3..8 点、所有 shift 的扫描测试。
- Shape PATH 的编辑坐标现在经过形状自身 `T(x,y)·R(rotation)`（含动画值）再投到图层空间；
  此前钢笔 overlay/hit/drag 用图层坐标、渲染却多一层形状变换，路径一旦有 x/y/rotation 就错位。
  适配器在 `values()/setPoint()/setHandle()` 边界完成双向转换。

## 6. 回放抠像/光影（需求 6/8 第一批）

- `ReplayEntityMask` 增加帧序号校验：贴图必须属于当前 world frame 且 `SceneColor` 也属于同一
  帧，否则不合成旧遮罩，改画「等待世界帧」占位。此前只检查“有 target”，失败/切影片/请求清除
  后会拿上一帧的剪影去抠新帧——运动时旧轮廓处正好露出背景。
- `SceneColor` 记录并暴露 `frameSerial`，与 mask 对齐。
- 合成/3D 视口/导出渲染包进 `BBSRendering.renderOffscreen`，Iris 下不再让 shader pack 的
  program/顶点格式串进合成 FBO；`GlStateGuard` 保存/恢复 `GL_FRAMEBUFFER_SRGB` 并在 pass 中关闭。

## 7. 未完成（下一轮）

- **需求 4**：回放编辑嵌入 bbsae 顶部模式切换（合成/回放），复用现有 `UIReplayModePanel` 但
  去掉独立 dashboard 页；需要新增 22 px 模式条并统一 Chrome。
- **需求 5/11**：回放实体在合成预览盒/3D 摄像机空间中的正确渲染；当前仍是 world plate decal，
  需要 `ReplayActorRenderer` + 合成相机矩阵。
- **需求 8 后半**：扣像 pose 快照/同帧配对之外的骨架同步（solvers/anchors）尚未完成。
- **需求 13**：3D 挤出/体积效果。
- **需求 14 后半**：3D 自由工作区的 repeat 仍是 draft 路径不跑投影效果；真正的 per-copy quad
  重绘、AM repeat 时间偏移、displace 边缘模式等。
- 需求 9 的 open-path 语义（开放路径是否参与蒙版）与旧蒙版 overlay 使用 base 值的问题。

## 8. 本轮验证

- 全量测试：**145 suites / 940 tests / 0 failures / 0 errors / 1 skipped**。
- 真机：`runClientView -PbbsaeView=demo -PbbsaeDebugDock=true -PbbsaeShot=round56-ui-effects`，
  日志 `dock children ... preview=482x344@182,24`，截图无 bbsae 异常。
