# BBS AE 更新日记

## 0.92 — 2026-11-12

本轮处理用户八条需求里最核心的六条：3D 摄像机逻辑、图层效果、实体回放、钢笔工具、
界面操作逻辑与面板自适应/动效基础设施。验证：90 个测试套件 / 511 项测试全绿
（0.91 基线为 72 / 379），另有真机截图验证（见 `docs/verify/round47-*.png`）。

### 3D 摄像机逻辑改成 AE 语义（需求 1）

- **只有开启「3D 图层」的图层受摄像机影响**：2D 图层始终屏幕附着，摄像机倾斜/推拉不再
  连带移动普通图层（`CompScene.usePerspective`）。旧版（0.91 之前「只要镜头有倾斜或任意
  图层 z≠0，所有图层都被投影」）收进 **设置 → 开发者 → 图层 3D 逻辑**，旧工程可一键切回；
  切换立即生效（计入 `CompRenderCache.signature` 缓存键）。
- 顺带修正 `CompScene.hasDepth`：摄像机自己的 z（推拉距离）不再被当成内容深度。此前
  「显示/隐藏摄像机行」会改变整帧是否进入 spatial 投影路径，是隐藏的开关副作用。
- **没有摄像机时右键拖动预览框不再自动创建摄像机**：进入 3D 空间 / 右键拖动 / 看穿视图
  都只显示提示；`CompCameraOps.ensureCamera(doc, time, allowCreate)` 是唯一创建守卫。
- **AE 摄像机操作**：预览右侧新增「新建摄像机」（已有摄像机时只选中它）、「AE 摄像机工具」
  （左键环绕 point-of-interest、中键 Track XY、右键 Track Z 指数推拉）与「摄像机设置」齿轮
  （打开 设置 → 开发者）。BBS 飞行摄像机自动退回 yaw/pitch 环绕。工具开启时预览不再
  选中/拖动图层，也永远不会创建摄像机。
- 设置 → 新增**开发者**页：3D 逻辑切换、无摄像机拖动自动创建（旧版兼容，默认关）、
  AE 摄像机工具开关、新摄像机默认 FOV、当前状态与动态版本号（设置页脚不再写死 0.23）。
- 测试：`CameraLogicModeTest`（AE/旧版投影语义、无摄像机手势不创建、显式创建只加一个、
  环绕半径/俯仰钳制/dolly）、`Camera3DTest` 修正（原测试靠摄像机 z 误判 spatial）。

### 图层效果逻辑修复（需求 2）

- **文字图层加效果后上下颠倒**：根因是 3D 烘焙采样分支把图层 UV 直接当纹理 UV，漏了 FBO
  的 V 翻转（2D 正常、3D 全部镜像，文字最显眼）。现在统一走 `localToCompUv`。
  `EffectOrientationTest` 用 12 个内容采样效果做像素级方向验收。
- **3D 模式下效果无效**：修 4 个根因——上述 V 翻转；投影后效果误用图层世界矩阵
  （glass/置换图/光包裹改合成空间）；`AmEffectDef.padding` 支持域从未被消费（3D 模糊/发光/
  长阴影被图层矩形硬裁）；AM 纯模糊半径未按代理栅格 scale 换算。
- **重复类效果被图层大小裁成「蒙版」**：按 AM 语义审计后，只有 repeat 分类是图层复制器
  （其余生成/平铺/镜像本就在图层内采样）。新增合成空间 `REPLICATE` 全屏 pass，
  `grid_repeat / random_repeat / am_grid_repeat / am_line_repeat` 真正复制整层横跨合成，
  3D 走投影后 pass；`EffectRepeatTest` 钉住「复制体出现在原图层框之外」。
- **AM 参数对齐**：`_tools/am-gap/compare.py` 对比 277 条 AM 定义与 122 条本项目定义，
  产出 `docs/EFFECTS-AM-GAP.zh.md`（人工结论）+ `generated.md`（逐条清单）；本轮对齐
  11 处范围/步进（hue、glow 半径、pixelate、tile、hex 系列、四色渐变 alpha、角度步进）。
- 报告：`docs/EFFECTS-FIX-REPORT.zh.md`。
- 逐像素深度：`perPixelDepth` 已纳入缓存签名（切换后预览立即刷新）；主合成逐像素深度
  仍默认关闭（0.87–0.89 的摄像机预览重影修复保留），3D 工作区的逐像素深度保留。

### 实体回放编辑模式（需求 5）

- 主界面 dashboard 面板条新增「实体回放」入口；合成器预览工具栏新增 PLAYER 按钮直达。
- 新增 `client/replay/` 包（11 个文件）：3D 视口用私有颜色+深度 FBO 通过
  `FilmEntityRenderer` 显示影片里所有 replay 模型（comp↔world 映射、Y 翻转），
  相机复用 `CompViewportCamera`（右键环绕 / Shift+右键或中键平移 / 滚轮对准光标缩放），
  左键 stencil 拾取选模型。
- **原版回放编辑照搬**：底部是 BBS 原版 `UIKeyframeEditor` + `TrackCatalog`/`UIFilmKeyframes`
  轨道（位置/状态/快捷栏/装备/操作/速度/骨骼），左侧原版回放列表、右侧原版属性面板；
  拖动播放头模型姿态跟随。
- **原版 gizmo**：`Gizmo.INSTANCE` + `UIReplayPropTransform` 写回 x/y/z 与
  yaw/headYaw/bodyYaw/pitch；Ctrl+Z/Y 走原版 `filmPanel.undo()/redo()`。
- **原版模型库**：`UIFormPalette.open(...)` 选模型 → 拷贝到 replay.form → 播放头自动打
  位置/角度关键帧（复刻 AE 的模型导入工作流）。
- 回退路线：面板顶部「原版回放编辑」一键切回 BBS 原版影片编辑器（100% 原版体验兜底）。
- 报告与真机清单：`docs/REPLAY-MODE-REPORT.zh.md`；集成钩子：
  `docs/REPLAY-MODE-INTEGRATION.zh.md`。

### AE 钢笔工具（需求 6）

- 新增 `comp/CompPathGeometry`（纯贝塞尔几何：求值/切线/最近点/保形分割/射线内外/45° 约束/
  镜像钳制）、`comp/CompPenSession`（纯状态机）、`client/ui/UIPenTool`（2D 仿射 / 3D 透视
  射线-平面 / 3D 工作区投影）。
- 复刻 AE 操作：单击角点、按下拖动平滑点（对称手柄）、点起点闭合、橡皮筋预览、Enter 结束、
  Esc 取消、Shift 45° 吸附、Alt 转换点/断开手柄、Ctrl 框选、Delete 删点、右键菜单、
  段上加点；形状图层编辑形状路径，其它图层编辑蒙版。
- 修 3 个上线前必现的既有 bug：① `MaskCache` 只要 ≥2 点就遮罩且永远闭合（画第二点整层
  变透明）→ 改为 `enabled && closed && ≥3 点`；② `CompShape.PATH` 没有 `closed` 字段、
  渲染强制闭合 → 新增 `closed`（默认 true，旧工程兼容）；③ 蒙版 id 撞车。
  另修：闭合贝塞尔最后一段被直线弦替代（椭圆蒙版缺 1/4 圆弧）、蒙版 world 矩阵
  JOML→AWT 转置（旋转图层蒙版镜像）、几何指纹不含 closed。
- 测试：7 个新类 / 62 用例（几何、状态机、持久化、蒙版栅格、无头 GL、形状开放路径、3D 投影）。
- 报告（含使用说明与真机清单）：`docs/PEN-TOOL-REPORT.zh.md`。

### 界面操作逻辑与面板基础设施（需求 4 / 8）

- **修复「拖轨道左侧面板数据时面板大小也跟着拖」**（两个根因）：
  ① 时间轴高度 splitter 原来横跨整个 body，盖住左/右分栏底部 4px（BBS 事件按子元素逆序
  派发且不看 `isVisible`），现在 `CompUi.timelineSplitterSpan` 只覆盖预览列，隐藏把手不再
  参与命中，并新增 `isPanelResizing()` 阻止同一按下的第二次手势；
  ② `UILayerQuickPanel` 宽度拖拽原来是「每帧轮询鼠标位置」而不是「按下即拥有」，拖时间轴
  数据经过它左边缘就会改宽度 → 改为 `subMouseClicked` 里命中 4px grip 才启动。
- 新增 UI 基础设施（为下一轮「面板拖拽组合」铺路）：
  - `UiMotion` 扩展：spring/tween/prune/isActive/可注入时钟，`CompPrefs.animations=false`
  全通道立即归位；
  - `UiStagger`（列表错峰入场）、`UiPageTransition`（页面滑动 + veil，不改子控件 alpha）、
  `CompositorLayout`（纯函数面板几何：最小宽/短窗/断点/splitter 命中）、
  `CompDockSource`（BBS `UIDockLayout` 布局树的工程侧持久化，批量写盘）与
  `CompSettings.dockLayout/dockHiddenPanels/dockUnlocked` 字段；
  - 计划：`docs/DOCK-LAYOUT-PLAN.zh.md`、`docs/UI-PANEL-PLAN.zh.md`
  （BBS 原版确有完整 `UIDockLayout` 拖拽停靠系统，下一轮直接复用）。
- 每个 PanelResizeHandle 增加 `visible` 命中检查：BBS 隐藏元素仍会收到点击，隐藏 splitter
  的旧矩形不会再吃掉内容区的按下。

### 构建与协作

- 真实工程路径含空格/中文会让 Gradle Test worker 的 classpath argfile 失效；统一用 ASCII
  junction `C:\bbsae-build` 跑构建，并新增 `_tools/gradle-run.ps1`（命名互斥量串行化多个
  AI 协作者的构建，避免并发互删 `build/classes`）。文档：`docs/TEAM-CONTEXT.zh.md`。

## 0.24 — 2026-11-05

本轮针对用户反馈的七个问题：分形杂色不是特效、蒙版异常、itrp（iterationRP）光影下黑屏、
绿幕抠不干净、叠加效果被残留绿幕污染、缺少跟踪器、部分特效（如波浪变形）不生效。

### 波浪 / 置换 / 湍流「不生效」的根因（问题 7）

- **DISTORT 着色器的位移量单位错误**：位移像素先被除以图层宽高、再乘 layer→comp 基（其列向量
  本来就是"合成像素/图层像素"），一个 48 px 的位移实际只移动了 ~0.1 px——**波浪变形、置换图、
  湍流置换因此全部不可见**。现在把图层像素位移直接交给 `localDeltaToComp`（旋转/缩放跟随保持不变）。
- `CompLayerSourceCache.scaleEffectForRaster` 的双重缩放一并修掉：DISTORT 家族的 amount、
  PIXEL 家族的马赛克/扫描线/故障尺寸、生成器的格子尺寸都是图层像素（着色器内部已换算），
  3D 烘焙时不再二次缩放——此前 3D 图层在代理分辨率下这些效果会随缩放变小/变大。

### 绿幕抠像在色度空间重做（问题 4 / 5）

- KEY 着色器的色度模式改为**色相差 + 饱和度门控**的色度距离：亮度与色相解耦，
  打光的绿幕、偏黄的绿都按同一色相抠掉——旧 RGB 距离对"色相相同、亮度不同"一律放行，
  这就是那层**浅透明绿幕残留**的来源（RGB 距离 ≈0.22 落在旧容差之内）。
- 新增**蒙版黑场 / 蒙版白场**两个滑杆（默认 0/1，老工程行为不变）：黑场清掉残余噪点，
  白场把不纯的屏幕色压成全透明。
- **溢色抑制重做**：只在屏幕色仍有影响的像素上压绿（matte<1），完整保留的主体绿色不动；
  抠掉/半透明像素的绿色同步清掉，后续叠加的模糊/辉光不会再把绿边重新混进画面（问题 5）。

### 分形杂色是真正的 FX（问题 1）

- `分形杂色` 移入「噪点与颗粒」分类（与 AE 一致），效果列表与图层库共用同一实现。
- 噪声域改为**图层像素空间**：缩放 = 云雾特征尺寸，宽图层不再把噪声拉成细纹。
- **演化改为逐八度旋转噪声域**：打关键帧后云雾是"变形"而不是整体平移（AE 的 Evolution 行为），
  幅度归一化后对比度/亮度在任意细节档位下语义一致。

### itrp / iterationRP 黑屏兼容（问题 3）

- 根因一：**itrp 的 final pass 输出 alpha=0**（`vec4(color, 0.0)`），而世界帧捕获是逐字节拷贝，
  所有消费方按纹理 alpha 混合——整帧因此不可见。现在 `SceneColor` 每次捕获后用新的 OPAQUE
  pass 把 alpha 归一为 1（同时洗掉其它包写入的 alpha 垃圾），世界帧永远按不透明图片绘制。
- 根因二：最终帧 mixin（`finalizeLevelRendering` TAIL）在某些 Iris 内部结构变化时绑不上，
  旧逻辑会一直回退到"合成前的主 framebuffer"= 纯黑。新增 **colortex0 回退捕获**
  （`IrisColor` 反射），Iris 存活期间至少给出真实世界画面（未 tonemap），预览读出会标注
  「光影帧（回退 · 合成前颜色）」。
- 预览信息行新增捕获状态：`光影最终帧` / `光影帧（回退 · 合成前颜色）` / `光影帧未捕获`。

### 蒙版（问题 2）

- 2D 图层与调整层的蒙版、轨道遮罩层的自身蒙版，统一改为**按渲染分辨率栅格化**
  （与 3D 烘焙路径一致）：蒙版纹理与内容 FBO 同 UV 采样，信息量不变，但动画蒙版不再每帧
  栅格化整幅 1920×1080 CPU 路径 + 盒式模糊——拖动/打关键帧时的预览卡顿即来源于此。

### 跟踪器（问题 6）

- 新增**点跟踪器**：`CompTracker`（锚点/模板半径/搜索半径/置信度/帧范围）+ `TrackerMatcher`
  （纯 Java NCC 模板匹配，含像素级单元测试）+ `TrackerEngine`（每显示帧协作式分析一帧；
  含 BBS 世界层的合成会先寻帧再分析，保证跟踪的是真实影片画面）。
- 检查器新增「跟踪器」区：新建/开始分析/停止/应用到位置/清除/删除。
  - **应用到位置**：把每帧结果按"相对锚点的位移"写入所选图层的独立 X/Y 通道关键帧
    （先迁移旧共享 Transform 通道再替换，不会与旧关键帧混叠）。
  - **表达式**：`tracker.<id>.x` / `tracker.<id>.y` 取当前帧跟踪位置，可驱动效果参数/蒙版点。
  - 分析中预览会停在分析帧上（runner 由跟踪器驱动），状态栏显示进度与丢失原因。

### 曲线编辑器 · 贝塞尔手柄

- **手柄镜像改为真正的对称镜像**：拖动一边，对边把手柄整支"点反射"过来——半径等长跟随、
  方向相反，不再只旋转（旧实现 `ly = -ry·(lx/rx)` 只镜像斜率，各自保留半径，拉一边另一边
  不会"对应远离"）。
- 旧公式里的 `lx/rx` 比例因子是"上下拖动乱跑"的根源：两杆长短悬殊（或一边被钳到最小 0.01 帧）
  时，纵向拖一下，对边把手柄会被放大几十上百倍直接飞出。现在对边严格取 `-ry / -ly`，无比例放大；
  镜像半径按对侧相邻段的段长钳制，控制点不会越过相邻关键帧把曲线翻折过去。Alt 拖动断开镜像不变。
- 新增 `CompCurvesTest.mirroredHandlesFollowRadiusSymmetrically` 钉住：等长镜像、精确取反、
  对侧段长钳制。

### 验证

- 新增 `ShaderWaveTest`（波浪位移必须达到全振幅——旧实现在此测试上会失败）、
  `ShaderChromaKeyTest`（纯绿/暗绿/黄绿全部抠净、白/红保留、溢色被压掉——黄绿即旧算法的
  残留案例）、`TrackerMatcherTest`（偏移跟随 ±1px、平坦场景置信度归零、无关内容低分、
  采样有序且插值）。抠像与匹配器数学另有 CPU 镜像验证（7 色彩用例 + clip 映射）。

### 0.24 源码应用时的追加修复

- `ShaderSources.GENERATE` 的分形杂色分支引用了不存在的 `luv`（DISTORT 着色器的变量），
  `generate` 程序编译失败，分形杂色与渐变生成器实际不可用；改为该着色器自己的 `uv`。
- `CompTracker.sampleAt` 在分析范围外夹取首/尾采样，导致 `tracker.<id>.x/y` 永远停在最后
  位置；改为按契约返回 null，回落到用户设置的锚点。
- `CompCurvesTest.mirroredHandlesFollowRadiusSymmetrically` 的最后一个期望值写反（应为 +6），
  与实现和"等长镜像、精确取反"的文档语义对齐。
- 全量 309 个测试通过；真实客户端加载 `bbsae 0.24` 并完成一次合成器截图验证。

## 0.23 — 2026-11-01

从 0.22.1 到 0.23，主要修复预览性能、曲线编辑器手感、图层库排版和缩放手柄。

### 更名

- **BBS Compositor 正式更名 BBS AE**。模组 ID 仍为 `bbsae`，旧工程和设置继续可用。

### 性能与缓存

- 帧缓存改为后台异步写入并原子落盘，渲染线程不再等待磁盘。
- 缓存键补上工程 ID 与实体/回放参数，修复切换工程或修改实体设置后画面不刷新。
- 时间轴渲染不再每帧复制图层列表；没有实体图层时跳过回放遮罩请求。

### 曲线编辑器

- 只显示真正参与贝塞尔段的手柄；密集曲线优先抓取离鼠标最近的手柄。
- 手柄拖动改为基于按下时快照的增量计算，修复松手丢最后一帧、拖动跳变和误改关键帧。
- 选择关键帧或 Shift 点选不再隐式改写曲线。

### 界面

- 图层库卡片改为自适应 1–3 列，中文描述按真实字体宽度换行，不再拥挤或被裁切。
- 赞助者「星辰久梦君」更新为 **【星辰久梦君_Star Dm Sov】**，名称和感谢语使用金色。

### 预览操作

- 边柄只缩放对应轴；角柄默认等比，按住 Shift 可单独缩放 X/Y。
- 3D 图层改用鼠标射线与图层平面求交，2D 图层使用 `view2D` 空间；透视、相机平移或缩放时手柄仍然跟手。
- 上下边柄显示垂直缩放光标，左右边柄显示水平缩放光标。

**验证：** 51 个测试套件、302 项测试全部通过。
