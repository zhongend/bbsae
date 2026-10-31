# 功能矩阵（当前实现）

这份文档描述的是**代码里已经存在的功能**，不是路线图。UI 主要由 BBS 原生控件组成；自绘区域是预览视口、轨道 bar 与轨道头、秒刻度尺。

## 已实现

| 模块 | 实现 |
| --- | --- |
| 工程 | 多工程 `.bbscomp`，多合成，保存/加载/迁移，`.bak` 备份，自动缓存 |
| 合成 | 分辨率、帧率、时长、背景、工作区、合成标记、Master Parameters |
| 图层 | solid/panel/rect、bar、text、image/footage（`%04d` 序列）、shape、null、adjustment、precomp、bbs 预览、**回放实体（深度抠像）**；**图层库**：搜索 + 分类 + 卡片（图标/名称/一句说明），「+」直接打开，双击添加 |
| 时间 | in/out、start offset、time scale、freeze、precomp offset/scale、可关键帧 time remap、layer markers |
| 变换 | position、rotation、scale、anchor、父子链（**统一场景图**：2D/3D 同一套矩阵；换基绑定，绑定/解绑都不移动子级；3D 支持 z/rotationX/Y/scaleZ 与关键帧） |
| 合成 | 13 种混合模式、不透明度、图层 solo/guide、调整层、轨道遮罩（alpha/luma，正/反） |
| 动画 | BBS `KeyframeChannel<Transform>`、float 通道，BBS 原生 dope sheet + graph editor、插值/右键菜单/复制粘贴；文本字号/字距/行高/描边、蒙版羽化/不透明度/扩展/点/手柄、形状 trim/repeater/旋转/路径点、效果参数均可动画 |
| 蒙版 | add/subtract/intersect/exclude、羽化、不透明度、正负扩展、反转、点/贝塞尔手柄编辑、父变换跟随、逐点动画 |
| 效果 | 53 个精选效果：Gaussian/Box/Directional/Radial Blur、Sharpen、Brightness/Contrast、Hue/Saturation、Exposure、Tint、Levels、Curves、Invert、Vignette、Noise/Grain、Gradient Ramp、Fractal Noise、Checkerboard、Grid、Circle、Displacement、Bulge、Pinch、Twist、Wave、Chroma Key、Luma Key、Glow、Drop Shadow、Stroke、Outer Glow、Mosaic、Posterize、Threshold、Find Edges、Linear/Radial Wipe、Dissolve、Depth Matte/Fog/DOF、Scanlines、Glitch、VHS、Halftone、Emboss、Mirror、Kaleidoscope |
| 图层样式 | Drop Shadow、Inner Shadow、Outer Glow、Inner Glow、Bevel、Color Overlay、Gradient Overlay、Satin、Stroke（Bevel/Satin 为近似实现） |
| 文字 | 字号、颜色、描边、字距、行距、对齐、换行、逐字 animator、range selector、wiggle、随机顺序 |
| 形状 | 矩形、椭圆、**三角形**、多边形、星形、贝塞尔路径、合并/减去/交集/排除、trim、repeater、**逐角半径（矩形 4 角 / 三角与多边形按顶点，可单独关键帧、圆角或切角）**、**逐形状填充与描边颜色**、局部空间两段填充渐变（可关键帧角度）、路径点编辑与逐点动画 |
| 效果参数动画 | 每个标量效果参数都能用 BBS float channel 打关键帧 |
| 表达式 | 安全子集：`+ - * / % ^`、括号、`time/frame/fps/value`、`comp.<参数>`、`layer.<id>.<属性>`、sin/cos/abs/min/max/clamp/noise/wiggle 等 |
| 动画预设 | 保存/应用图层变换、不透明度与标量属性动画；不覆盖目标层内容 |
| 蒙版 / 排除 | 用形状层做**交集**（蒙版）或**差集**（排除）：形状放上面、选中、一个按钮作用到下一层；形状层自动变 guide 并隐藏；成片与 3D 草稿使用同一套 alpha 运算 |
| 3D 视口 | **自由视图**：编辑器有自己的环绕摄像机（右键拖动环绕（中键同效）/ Shift+拖动平移 / 滚轮朝光标缩放 / 0 看穿 / 1·3·7 前后右顶 / 5 正交 / . 框选 / Ctrl+Home 全览）；**输入直接轮询窗口**，不依赖 UI 事件分发；图层按源纹理投影成四边形，效果在图层本地空间烘焙（成片管线同样），导航不重渲整图；摄像机 gizmo + **运动轨迹与关键帧点**；左下角常驻摄像机位置/FOV/视图距离 |
| 图层快捷面板 | 时间轴右侧常驻：2D 显示 **X/Y/旋转/统一缩放/不透明度**；3D 图层自动展开为 **位置 X/Y/Z、旋转 X/Y/Z、缩放 X/Y/Z、不透明度**，每组一个 ◆ 打关键帧；顶部 **插值曲线** 打开 BBS 原生插值选择器；宽度可拖（160–340） |
| BBS 插值曲线 | 关键帧抽屉标题栏与图层快捷面板均有 **插值曲线**：调用 BBS `UIInterpolationContextMenu`（曲线预览、全部图标网格、v1–v4 参数、复制/粘贴），作用于所有选中关键帧；未选中时自动选播放头所在键 |
| 摄像机（3D） | **真正的 3D 摄像机**：位置 + 朝向 + 滚转 + FOV；视口里 **Alt+拖动 = 环绕**（绕目标点球面运动，俯仰 ±85° 防极点）、拖动 = 平移、拖准星 = 取景、滚轮 = 推拉、Alt+滚轮 = FOV；**倾斜后所有图层按各自 Z 做透视投影**（蒙版/效果仍在 comp 空间精确完成，最后把成品平面投影一次），直相机保持原来的仿射路径（零回归、零额外开销） |
| 摄像机 | **一个摄像机模型**：位置/朝向（看向目标或自由俯仰偏航）/滚转/FOV/近远；同时驱动 2D 图层（平移/缩放/滚转）与 3D 图层（透视），z=0 处 1:1 与 2D 视图逐像素一致；透视/正交可切换；预览里拖本体平移、拖准星瞄准、滚轮推拉、Alt+滚轮改视场；带视锥与目标点辅助线 |
| 多选 | 轨道 Ctrl 切换 / Shift 区间、预览 Ctrl 切换、拖动整组移动；多选检查器页；批量：合成预渲染（时间重定基、父子/遮罩关系重指向）、编组到空对象、对齐（左/中/右/上/中/下）、水平/垂直等距、显示/隐藏/锁定、复制、删除 |
| 性能 | 预览帧缓存（内容指纹 + 按合成帧率量化，静止时每帧只画一张贴图）；撤销快照按脏标记触发并限制 32MB；世界帧/深度拷贝按需；FBO 池 32；每帧每层只算一次场景矩阵 |
| 曲线编辑器 | 底部 `时间轴 / 曲线` 页签：**值图表 / 速度图表**、逐属性通道列表（位置 X/Y、旋转、缩放 X/Y、不透明度、效果参数、蒙版点、形状角）、贝塞尔手柄（默认镜像、Alt 断开、**拖动按增量、33.3% 默认影响、手柄参与值域适配**）、框选/加选、拖键改时间与数值（按帧吸附）、**中键/Space 双轴平移**、**右键插值菜单**、**方向键 1 帧/1 像素微调（Shift ×10）**、双击加键、选中键读数卡、AE 关键帧图形、8 个缓动预设 + 影响参数、Delete/Ctrl+A/F 快捷键、每手势一次撤销 |
| 图层预设 | 左栏 `预设` 页签：保存为 动画/效果/样式/形状/整套 预设，应用到任意图层；10 个内置预设（电影青橙、柔光人像、霓虹发光、复古 VHS、故障闪断、卡片投影、外发光+白描边、圆角卡片、三角徽章、圆环）；应用时效果/形状 id 与动画通道自动重映射，动画按目标入点重新对时；用户预设随工程保存 |
| 数据绑定 | BBS 相机、Replay 实体（投影到屏幕）、图层目标（含父级/变换）、offset、平滑 |
| 深度 | 从当前世界 framebuffer 复制 depth，供 depth matte/fog/DOF/displacement 使用 |
| 渲染 | 每层独立 FBO、straight-alpha 合成、mask/matte/effects/styles、ping-pong 效果链、GlStateGuard、BBS BufferRenderer 缓存同步 |
| 输出 | 渲染队列（新增/移除/重试/取消/串行）、render settings 与 output module 分离、输出模块 UI 可编辑、PNG 序列、H.264 审阅、ProRes 422 HQ、ProRes 4444 Alpha、输出模板持久化、打开输出文件夹 |
| 影片集成 | 一个影片一个项目；每个 replay 一个独立 composition；打开 replay 时自动补 `bbsae:composition` clip；`ClipContext.clipData` → `FrameOverlays` → BBS recorder；不修改 `.dat` |
| 回放实体抠像 | **`回放实体` 图层**：把选中的 BBS 回放实体按**深度**从世界帧里抠出来（背景透明），可整层做变换/蒙版/效果/混合并随工程导出 alpha；判断依据是"实体深度 = 世界深度"，因此**方块或未选中的实体挡在前面时会正确遮挡**，且不比较颜色（换光影、换材质不影响）；检查器里有"抠出所有回放实体"开关 + 逐回放勾选表（勾选/取消会自动在"全部/仅勾选"之间切换）、羽化/收缩/深度容差、遮挡开关与实时状态行 |
| BBS 预览 | 合成器面板内通过 `BbsPreviewBridge` 重新启用 BBS customSize + film runner + `FilmEditorController`：`BBS 预览` 图层与预览背景 `BBS` 开关显示真实的影片/世界帧，机位与回放随合成播放头移动 |
| UI 密度 | **三档（小/中/大）+ 自动跟随 BBS 的 GUI 缩放**：控件高度 14/16/20、行高 16/18/22、标签列 62/76/92、左右栏与时间轴宽度全部由 `CompUi` 一张表推导；设置里可切换，随工程保存 |
| UI | `UIDashboardPanel`、`UIList`、`UIKeyframeEditor`、`UISection`、`UIButton`、`UIIcon`、`UIToggle`、`UITrackpad`、`UISliderTrackpad`、`UIColor`、`UITextbox`、BBS `ContextMenuManager`；**扁平按钮 + 图标按钮**；**每个按钮都有中文悬浮说明**；PR 式布局：左效果库 / 中预览 / 右 Effect Controls / 下轨道时间轴；四个可拖拽分区且尺寸持久化；**0.18 起设置改为 BBS 风格左分类+右滚动，新增 5 页新手引导、6 个动效/光效设置、可编辑快捷键**；动效统一走 `UiMotion`（非线性 hover/入场/滚动），所有过渡只在绘制层 |
| 调色 | 三个 AE 风格色轮（Lift/Gamma/Gain，中心中性、角度=色相、半径=饱和度、下方明度条）；五点主曲线（与 shader 同一 Catmull-Rom）；预设（中性/电影/暖阳/冷峻/黑白）；曝光/对比度/饱和度/自然饱和度/色温/色调均可关键帧 |
| 文件导入 | **系统原生打开对话框**（Windows：`GetOpenFileNameW`，owner = 游戏窗口，不可能被游戏挡住；其它平台 AWT）；三级回退且严格区分「取消」与「不可用」；图片/视频/音频/字体/素材库全部走这一条；**原版音效可直接导入音频轨**（事件 → ogg → `assets/audio` → clip） |
| 素材 / 音效 | 素材库 13 个分类（项目、物品、方块、实体、界面、粒子、状态、画、环境、杂项、地图、BBS、音效）；跨分类搜索；**可拖拽的 8px 滚动条（滑块/轨道点击、拖出控件仍能松开）**；音效试听、复制 ID、打开 BBS 原版音效库；`mc:` / `bbs:` 命名空间纹理直接插入图层 |
| 引导 / 快捷键 | **新手引导 5 页**：欢迎、布局（标准/专注/紧凑/宽屏）、动效（平衡/电影感/极简）、快捷键（Premiere/BBS）、完成；背景为 BBS 原版 landing 风格的灯 + 跟随鼠标光；引导只出现一次，可从设置重播。**快捷键全部可编辑**：点击键帽录制、Esc 取消、Backspace 清除、冲突提示、恢复默认，覆盖原 Premiere/BBS 两套默认；`Ctrl+,` 设置、`Ctrl+/` 快捷键 |
| 动效 / 光效 | `UiMotion` 全局时钟 + 通道：按钮 hover/浮动/阴影、页签下划线、卡片入场、素材格入场、平滑滚动、轨道选择、面板入场、引导背景（3 灯 + 48 微粒 + 指针柔光）；设置可开关、调强度/速度、关辉光/阴影、调背景亮度；关闭后全部即时到位 |
| 运动 / 破碎 | 10 个内置运动模板（淡入淡出、四向滑入、弹入、旋转进入、呼吸缩放、漂浮），在图层入点写关键帧；`block_shatter` 支持进度、方块数、飞散、旋转、重力、种子，逆向映射出真实裂缝 |
| 轨道编辑 | `UICompTrackTimeline`：一层一轨、轨道头（类型/名称/可见/锁定）、可拖动/裁剪 bar、帧量化、吸附到播放头/标记/工作区/其它轨道、Alt+滚轮行高、中键平移、拖动 HUD、右键菜单；`CompTimelineRuler` 秒刻度；`CompLayer` 的 move/trim/split 语义 |
| 效果工作流 | 左侧效果库应用到选中轨道；右侧效果栈启用/上移/下移/移除；运动/不透明度/效果参数实时跟随播放头 |

## 部分实现 / 明确限制

| 能力 | 现状 |
| --- | --- |
| 2.5D 文字/形状挤出 | 未实现 |
| 完整 3D 图层/摄像机/灯光/C4D | 不在范围内；BBS 提供 3D 世界 |
| 3D Camera Tracker | 不需要；改为 BBS 相机/Replay 投影 |
| 木偶/网格变形 | 未实现 |
| Roto Brush / AI 内容填充 | 未实现；蒙版 + 深度 + 克隆是后续方向 |
| 逐字 3D、文字路径、从文字创建形状 | 未实现 |
| 笔画/克隆/橡皮擦绘画 | 未实现 |
| 完整时间重映射曲线 | 已支持可关键帧的 source time remap；但还没有逐帧曲线重映射 UI |
| 形状扭转/摆动/位移路径 | trim/repeater/roundness/布尔运算/路径点已实现；其余未实现 |
| 动画预设、MOGRT、Essential Graphics | 已支持属性动画预设；MOGRT 外部模板未实现 |
| 代理/预渲染缓存 | **已实现（0.5.0）**：预览按视口像素渲染（`CompRenderContext.scale`）；`预渲染` 把工作区/整段渲染到磁盘+显存缓存，时间尺显示绿条；含 BBS 预览层或深度效果的合成不可缓存 |
| 网络渲染/协作/VR | 未实现 |
| OCIO/线性工作流/LUT | 未实现；当前在 sRGB 空间合成（straight alpha） |
| 轨道遮罩跟踪点 | 绑定的是整层/图层锚点，不是逐个遮罩点 |
| 图层标记 | 已实现模型与检查器编辑；时间轴图形标记仍由 BBS 原生组件负责 |
| 撤销/重做 | 已实现跨图层全局 undo/redo：离散命令（增删图层/效果/样式/蒙版/标记/预合成等）在入口压入动作级检查点，连续编辑仍按 1 秒快照合并；最多 50 步；拖动轨道期间不抓帧，松手后生成一步；另有自动保存和 `.bak` |
| 颜色管理/元数据 | 仅合成标记和基础设置 |

## UI 约束

- 按钮：`UIButton` / `UIIcon`
- 开关：`UIToggle`
- 数值/滑块：`UITrackpad`（BBS 数值拖动输入）
- 颜色：`UIColor`
- 文本：`UITextbox`
- 图层栈：`UIList`
- 轨道行/关键帧菱形/图表编辑器/插值右键菜单：`UIKeyframeEditor` + `UIKeyframeSheet` + `UIKeyframes`
- 右键菜单：`ContextMenuManager` / `UISimpleContextMenu`
- 弹层/分区：`UISection`、`UIScrollView`、`UIDashboardPanel`
- 没有自绘窗口框架或关键帧菱形；关键帧菱形/图表编辑器仍用 BBS 的 `UIKeyframeEditor`。自绘的轨道 bar 只负责 in/out、移动/裁剪和轨道头控件；预览视口只负责显示合成结果。
- 合成器是独立 dashboard panel，**不是**覆盖在 BBS 影片编辑器上的 overlay；它有自己的预览 FBO、时间轴和保存生命周期，BBS 影片编辑器保持原有世界预览与轨道。
- 预览使用独立的 identity model-view + 正交投影渲染到 FBO，避免 BBS 面板的 screen matrix 把合成内容推出画布；蒙版变换使用 JOML 列主序的 `m30/m31` 平移。
- 预览可以关闭 effects/styles（FX）或 masks/track mattes（Mask）进行对照；导出始终使用完整渲染图。
- `GlTexture.upload` 在上传蒙版前重置 `GL_UNPACK_ROW_LENGTH / SKIP_PIXELS / SKIP_ROWS`，修复了 BBS 图片上传遗留状态导致的 `nvoglv64.dll` 原生崩溃。
- Photon 研究见 [photon-research.zh.md](photon-research.zh.md)：其代码面向 NeoForge 1.21.1/LDLib2，不能直接复用；本轮借鉴的是独立编辑器、实时预览和效果/蒙版显示开关的结构。

## 验证状态

- `compileJava`：通过。
- `test`：**193** 个测试通过（模型/曲线/音频/导出/摄像机/shader/PIXEL/覆盖数据/图标等）。包含 0.17.0 新增的贝塞尔默认手柄展开、播放时钟、3D 标量回退、Mod Menu 图标等断言。第八轮的 PIXEL 像素测试包含层空间、恒等性、颜色和万花筒对称性断言，并输出 `docs/verify/round8-pixel-effects.png`。
- 真实导出：`1920x1080 @ 60 fps`、494 帧、8.23 s；`docs/verify/round6-export-1080p60.png` 抽帧含合成器面板/标题/进度条。扭曲 shader 有真实 GL 像素断言（`ShaderDistortTest`，未跳过）。
- 开发客户端：Fabric Loader 0.16.14 + BBS 2.7-1.20.1 下启动成功，BBS Compositor 面板注册成功；预览能显示合成内容，布局为 效果库 / 预览 / Effect Controls / 轨道时间轴，无重叠；轨道头、可移动/裁剪 bar、秒刻度、播放头、工作区、FX 徽章可见；关键帧抽屉与轨道播放头对齐。截图见 `docs/ui-track-redesign.png`。
- 真实导出：使用 BBS 自带 recorder 生成了 `320x180@20fps`、39 帧的 H.264 MP4；抽帧确认合成层（面板、标题、HUD）进入视频，不只是屏幕预览。
- 最终 `bbsae-0.1.0.jar`（**406842** 字节，SHA-256 `477DB7823351D6AA47D5359247835435442829ADDDB0961ABBB6960FFC171CF1`）由 `gradlew build` 的 `remapJar` 将 Minecraft 引用重映射到 intermediary，BBS 类名保留；产物在项目根目录和 `build/libs/`。
- 第九轮新增真实 20 秒（1202 帧、20.033 秒）、25 秒（1502 帧、25.033 秒）和 **40 秒（2402 帧、40.033 秒）** 1920×1080@60 导出；导出对话框和**进入工程后的“合成器设置”**都有“导出时长（秒）”字段，直接传给 BBS 原生录像器，不再受相机 12 秒或 loop 20 秒限制。证据见 `docs/verify/round9-export-40s-ffprobe.txt`、`round9-export-40s-35s.png`、`round9-bbs-preview-layer.png`。
- 第十一轮修复素材库：`mc:` 前缀现在映射到 `minecraft` 命名空间，原版方块/物品缩略图恢复；素材库背景使用 `InterfaceBlur`，面板缩到 620×400 以适配 GUI scale 2。证据见 `docs/verify/round11-asset-library.png`。

## 已知技术风险

1. Gradle Wrapper 在当前机器上会因 `.gradle` 缓存权限/锁失败；本次通过独立 `GRADLE_USER_HOME` 和缓存 Gradle 发行版完成了构建。普通开发机仍建议按 README 使用 `gradlew.bat build`。
2. GLSL 运行时编译在部分驱动/Iris 组合下可能失败；失败会记录日志并跳过对应效果，不应导致花屏。
3. 深度 blit 在 Iris/不同 framebuffer 配置下可能不可用；不可用时深度效果自动降级。
4. 预览已按视口像素渲染（0.5.0），重效果不再是按合成分辨率全屏开销；动画蒙版和形状仍每帧 CPU 栅格化，4K 下明显。后续可加 layer-local mask 纹理与 GPU 蒙版。
5. 预览/导出中曾出现过 Minecraft `BufferRenderer` 的 VAO 缓存与自定义 pass 不同步的问题；已通过 `BufferRenderer.resetCurrentVertexBuffer()` 修复，并在自定义 pass 前后重置。
6. 预览网格、安全区、深度和自动保存开关均已接到实际渲染/保存逻辑；UI 已做过一次完整视觉检查。普通开发机仍建议按 README 使用 `gradlew.bat build`，本机因执行沙箱无法让 TinyRemapper 直接写 zip，最终 jar 通过“重映射 class 目录 + PowerShell 重打包”生成。
