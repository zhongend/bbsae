# 功能矩阵（当前实现）

这份文档描述的是**代码里已经存在的功能**，不是路线图。UI 主要由 BBS 原生控件组成；自绘区域是预览视口、轨道 bar 与轨道头、秒刻度尺。

## 已实现

| 模块 | 实现 |
| --- | --- |
| 工程 | 多工程 `.bbscomp`，多合成，保存/加载/迁移，`.bak` 备份，自动缓存 |
| 合成 | 分辨率、帧率、时长、背景、工作区、合成标记、Master Parameters |
| 图层 | solid/panel/rect、bar、text、image/footage（`%04d` 序列）、shape、null、adjustment、precomp |
| 时间 | in/out、start offset、time scale、freeze、precomp offset/scale、可关键帧 time remap、layer markers |
| 变换 | position、rotation、scale、anchor、父子链 |
| 合成 | 13 种混合模式、不透明度、图层 solo/guide、调整层、轨道遮罩（alpha/luma，正/反） |
| 动画 | BBS `KeyframeChannel<Transform>`、float 通道，BBS 原生 dope sheet + graph editor、插值/右键菜单/复制粘贴；文本字号/字距/行高/描边、蒙版羽化/不透明度/扩展/点/手柄、形状 trim/repeater/旋转/路径点、效果参数均可动画 |
| 蒙版 | add/subtract/intersect/exclude、羽化、不透明度、正负扩展、反转、点/贝塞尔手柄编辑、父变换跟随、逐点动画 |
| 效果 | 53 个精选效果：Gaussian/Box/Directional/Radial Blur、Sharpen、Brightness/Contrast、Hue/Saturation、Exposure、Tint、Levels、Curves、Invert、Vignette、Noise/Grain、Gradient Ramp、Fractal Noise、Checkerboard、Grid、Circle、Displacement、Bulge、Pinch、Twist、Wave、Chroma Key、Luma Key、Glow、Drop Shadow、Stroke、Outer Glow、Mosaic、Posterize、Threshold、Find Edges、Linear/Radial Wipe、Dissolve、Depth Matte/Fog/DOF、Scanlines、Glitch、VHS、Halftone、Emboss、Mirror、Kaleidoscope |
| 图层样式 | Drop Shadow、Inner Shadow、Outer Glow、Inner Glow、Bevel、Color Overlay、Gradient Overlay、Satin、Stroke（Bevel/Satin 为近似实现） |
| 文字 | 字号、颜色、描边、字距、行距、对齐、换行、逐字 animator、range selector、wiggle、随机顺序 |
| 形状 | 矩形、椭圆、多边形、星形、贝塞尔路径、合并/减去/交集/排除、trim、repeater、圆角、填充/描边、**局部空间两段填充渐变（可关键帧角度）**、路径点编辑与逐点动画 |
| 效果参数动画 | 每个标量效果参数都能用 BBS float channel 打关键帧 |
| 表达式 | 安全子集：`+ - * / % ^`、括号、`time/frame/fps/value`、`comp.<参数>`、`layer.<id>.<属性>`、sin/cos/abs/min/max/clamp/noise/wiggle 等 |
| 动画预设 | 保存/应用图层变换、不透明度与标量属性动画；不覆盖目标层内容 |
| 数据绑定 | BBS 相机、Replay 实体（投影到屏幕）、图层目标（含父级/变换）、offset、平滑 |
| 深度 | 从当前世界 framebuffer 复制 depth，供 depth matte/fog/DOF/displacement 使用 |
| 渲染 | 每层独立 FBO、straight-alpha 合成、mask/matte/effects/styles、ping-pong 效果链、GlStateGuard、BBS BufferRenderer 缓存同步 |
| 输出 | 渲染队列（新增/移除/重试/取消/串行）、render settings 与 output module 分离、输出模块 UI 可编辑、PNG 序列、H.264 审阅、ProRes 422 HQ、ProRes 4444 Alpha、输出模板持久化、打开输出文件夹 |
| 影片集成 | 一个影片一个项目；每个 replay 一个独立 composition；打开 replay 时自动补 `bbsae:composition` clip；`ClipContext.clipData` → `FrameOverlays` → BBS recorder；不修改 `.dat` |
| BBS 预览 | 合成器面板内通过 `BbsPreviewBridge` 重新启用 BBS customSize + film runner + `FilmEditorController`：`BBS 预览` 图层与预览背景 `BBS` 开关显示真实的影片/世界帧，机位与回放随合成播放头移动 |
| UI | `UIDashboardPanel`、`UIList`、`UIKeyframeEditor`、`UISection`、`UIButton`、`UIIcon`、`UIToggle`、`UITrackpad`、`UISliderTrackpad`、`UIColor`、`UITextbox`、BBS `ContextMenuManager`；PR 式布局：左效果库 / 中预览 / 右 Effect Controls / 下轨道时间轴；四个可拖拽分区（左右栏、时间轴、关键帧抽屉）且尺寸持久化；引导页与 `.bbscomp` 工程列表；效果库支持搜索、分类树、最近使用、双击应用；轨道 bar 支持移动/裁剪/吸附/缩放/平移，BBS 关键帧编辑器在可选抽屉；顶部 Replay/影片选择 overlay；预览左上角 FX/Mask 对照开关和分辨率/FPS/FX 信息 |
| 调色 | 三个 AE 风格色轮（Lift/Gamma/Gain，中心中性、角度=色相、半径=饱和度、下方明度条）；五点主曲线（与 shader 同一 Catmull-Rom）；预设（中性/电影/暖阳/冷峻/黑白）；曝光/对比度/饱和度/自然饱和度/色温/色调均可关键帧 |
| 素材 / 音效 | 素材库 13 个分类（项目、物品、方块、实体、界面、粒子、状态、画、环境、杂项、地图、BBS、音效）；跨分类搜索；音效试听、复制 ID、打开 BBS 原版音效库；`mc:` / `bbs:` 命名空间纹理直接插入图层 |
| 引导 / 快捷键 | 开始引导页、工程列表、新建工程（名称/画布/帧率/时长）、未选工程禁止编辑；Premiere / BBS 两套快捷键，Alt+滚轮在两种方案下都缩放时间轴；设置含默认图层初始时长、外部播放显示合成、自动保存、安全框、网格、预览背景 |
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
| 代理/预渲染缓存 | 未实现；预览按合成分辨率渲染 |
| 网络渲染/协作/VR | 未实现 |
| OCIO/线性工作流/LUT | 未实现；当前在 sRGB 空间合成（straight alpha） |
| 轨道遮罩跟踪点 | 绑定的是整层/图层锚点，不是逐个遮罩点 |
| 图层标记 | 已实现模型与检查器编辑；时间轴图形标记仍由 BBS 原生组件负责 |
| 撤销/重做 | 已实现跨图层全局 undo/redo（每秒工程 JSON 快照，最多 50 步；拖动轨道期间不抓帧，松手后生成一步）；另有自动保存和 `.bak` |
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
- `test`：**63** 个测试通过（模型 23 + 效果空间 5 + 摄像机 9 + shader 2 + 扭曲像素 1 + PIXEL 计划 5 + PIXEL 像素 12 + 覆盖/数据 5 + 图标 1）。模型测试覆盖时间映射、BBS keyframe 往返、蒙版/形状/文字/时间重映射动画、表达式读取当前帧图层属性、动画预设、JSON、示例工程、轨道 move/trim/split 语义、导出时长，以及新增的 10 个运动模板。第八轮的 PIXEL 像素测试包含层空间、恒等性、颜色和万花筒对称性断言，并输出 `docs/verify/round8-pixel-effects.png`。
- 真实导出：`1920x1080 @ 60 fps`、494 帧、8.23 s；`docs/verify/round6-export-1080p60.png` 抽帧含合成器面板/标题/进度条。扭曲 shader 有真实 GL 像素断言（`ShaderDistortTest`，未跳过）。
- 开发客户端：Fabric Loader 0.16.14 + BBS 2.7-1.20.1 下启动成功，BBS Compositor 面板注册成功；预览能显示合成内容，布局为 效果库 / 预览 / Effect Controls / 轨道时间轴，无重叠；轨道头、可移动/裁剪 bar、秒刻度、播放头、工作区、FX 徽章可见；关键帧抽屉与轨道播放头对齐。截图见 `docs/ui-track-redesign.png`。
- 真实导出：使用 BBS 自带 recorder 生成了 `320x180@20fps`、39 帧的 H.264 MP4；抽帧确认合成层（面板、标题、HUD）进入视频，不只是屏幕预览。
- 最终 `bbsae-0.1.0.jar`（**402882** 字节，SHA-256 `4540BA5CF5EAF3DBEDEF2DAB54F11BD7B2602A56EB5FFB5DB23CD2E05CEBBEBC`）由 `gradlew build` 的 `remapJar` 将 Minecraft 引用重映射到 intermediary，BBS 类名保留；产物在项目根目录和 `build/libs/`。
- 第九轮新增真实 20 秒 1920×1080@60 导出（1202 帧、20.033 秒），并验证 `BBS 预览` 图层在导出路径中正常出现；证据见 `docs/verify/round9-export-20s.mp4`、`round9-bbs-preview-layer.png`。

## 已知技术风险

1. Gradle Wrapper 在当前机器上会因 `.gradle` 缓存权限/锁失败；本次通过独立 `GRADLE_USER_HOME` 和缓存 Gradle 发行版完成了构建。普通开发机仍建议按 README 使用 `gradlew.bat build`。
2. GLSL 运行时编译在部分驱动/Iris 组合下可能失败；失败会记录日志并跳过对应效果，不应导致花屏。
3. 深度 blit 在 Iris/不同 framebuffer 配置下可能不可用；不可用时深度效果自动降级。
4. 预览始终按合成分辨率渲染，重效果在 4K 合成上会明显变慢；动画蒙版和形状每帧重新 CPU 栅格化，4K 下尤其明显。后续可加半分辨率代理和 layer-local mask 纹理。
5. 预览/导出中曾出现过 Minecraft `BufferRenderer` 的 VAO 缓存与自定义 pass 不同步的问题；已通过 `BufferRenderer.resetCurrentVertexBuffer()` 修复，并在自定义 pass 前后重置。
6. 预览网格、安全区、深度和自动保存开关均已接到实际渲染/保存逻辑；UI 已做过一次完整视觉检查。普通开发机仍建议按 README 使用 `gradlew.bat build`，本机因执行沙箱无法让 TinyRemapper 直接写 zip，最终 jar 通过“重映射 class 目录 + PowerShell 重打包”生成。
