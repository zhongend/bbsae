# 功能矩阵（当前实现）

这份文档描述的是**代码里已经存在的功能**，不是路线图。UI 主要由 BBS 原生控件组成；自绘区域是预览视口、时间轴 scrubber 和图层缩略图。

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
| 效果 | 42 个精选效果：Gaussian/Box/Directional/Radial Blur、Sharpen、Brightness/Contrast、Hue/Saturation、Exposure、Tint、Levels、Curves、Invert、Vignette、Noise/Grain、Gradient Ramp、Fractal Noise、Checkerboard、Grid、Circle、Displacement、Bulge、Pinch、Twist、Wave、Chroma Key、Luma Key、Glow、Drop Shadow、Stroke、Outer Glow、Mosaic、Posterize、Threshold、Find Edges、Linear/Radial Wipe、Dissolve、Depth Matte/Fog/DOF |
| 图层样式 | Drop Shadow、Inner Shadow、Outer Glow、Inner Glow、Bevel、Color Overlay、Gradient Overlay、Satin、Stroke（Bevel/Satin 为近似实现） |
| 文字 | 字号、颜色、描边、字距、行距、对齐、换行、逐字 animator、range selector、wiggle、随机顺序 |
| 形状 | 矩形、椭圆、多边形、星形、贝塞尔路径、合并/减去/交集/排除、trim、repeater、圆角、填充/描边、路径点编辑与逐点动画 |
| 效果参数动画 | 每个标量效果参数都能用 BBS float channel 打关键帧 |
| 表达式 | 安全子集：`+ - * / % ^`、括号、`time/frame/fps/value`、`comp.<参数>`、`layer.<id>.<属性>`、sin/cos/abs/min/max/clamp/noise/wiggle 等 |
| 动画预设 | 保存/应用图层变换、不透明度与标量属性动画；不覆盖目标层内容 |
| 数据绑定 | BBS 相机、Replay 实体（投影到屏幕）、图层目标（含父级/变换）、offset、平滑 |
| 深度 | 从当前世界 framebuffer 复制 depth，供 depth matte/fog/DOF/displacement 使用 |
| 渲染 | 每层独立 FBO、straight-alpha 合成、mask/matte/effects/styles、ping-pong 效果链、GlStateGuard、BBS BufferRenderer 缓存同步 |
| 输出 | 渲染队列（新增/移除/重试/取消/串行）、render settings 与 output module 分离、输出模块 UI 可编辑、PNG 序列、H.264 审阅、ProRes 422 HQ、ProRes 4444 Alpha、输出模板持久化、打开输出文件夹 |
| 影片集成 | 一个影片一个项目；每个 replay 一个独立 composition；打开 replay 时自动补 `bbsae:composition` clip；`ClipContext.clipData` → `FrameOverlays` → BBS recorder；不修改 `.dat` |
| UI | `UIDashboardPanel`、`UIList`、`UIKeyframeEditor`、`UISection`、`UIButton`、`UIIcon`、`UIToggle`、`UITrackpad`、`UIColor`、`UITextbox`、BBS `ContextMenuManager`；顶部 Replay/影片选择 overlay；时间轴 Fit + 自绘 scrubber + BBS ruler/CTI；预览左上角 FX/Mask 对照开关和分辨率/FPS/FX 信息 |

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
| 撤销/重做 | 属性控件本身遵循 BBS 输入行为；合成器还没有跨图层的全局 undo 栈，依赖 20 秒自动保存和 `.bak` |
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
- 没有自绘窗口框架或关键帧菱形；时间轴使用 BBS 的轨道/菱形/图表编辑器。额外的 scrubber 只负责大目标拖动和可见时间刻度，预览视口只负责显示合成结果。
- 合成器是独立 dashboard panel，**不是**覆盖在 BBS 影片编辑器上的 overlay；它有自己的预览 FBO、时间轴和保存生命周期，BBS 影片编辑器保持原有世界预览与轨道。
- 预览使用独立的 identity model-view + 正交投影渲染到 FBO，避免 BBS 面板的 screen matrix 把合成内容推出画布；蒙版变换使用 JOML 列主序的 `m30/m31` 平移。
- 预览可以关闭 effects/styles（FX）或 masks/track mattes（Mask）进行对照；导出始终使用完整渲染图。
- `GlTexture.upload` 在上传蒙版前重置 `GL_UNPACK_ROW_LENGTH / SKIP_PIXELS / SKIP_ROWS`，修复了 BBS 图片上传遗留状态导致的 `nvoglv64.dll` 原生崩溃。
- Photon 研究见 [photon-research.zh.md](photon-research.zh.md)：其代码面向 NeoForge 1.21.1/LDLib2，不能直接复用；本轮借鉴的是独立编辑器、实时预览和效果/蒙版显示开关的结构。

## 验证状态

- `compileJava`：通过。
- `test`：16 个测试通过（14 个 headless 模型测试 + 2 个隐藏窗口 OpenGL 测试：全部 GLSL 编译/链接、straight-alpha 合成像素验证）。模型测试覆盖时间映射、BBS keyframe 往返、蒙版/形状/文字/时间重映射动画、表达式读取当前帧图层属性、动画预设、JSON、示例工程。
- 开发客户端：Fabric Loader 0.16.14 + BBS 2.7-1.20.1 下启动成功，BBS Compositor 面板注册成功；预览能显示合成内容，布局为 层栈 / 预览 / 检查器 / 时间轴四区，无重叠；时间轴顶部出现 Fit、scrubber、秒刻度和播放头。预览左上角 FX/Mask 开关和 `1920x1080 20fps 1 FX` 信息可见。
- 真实导出：使用 BBS 自带 recorder 生成了 `320x180@20fps`、39 帧的 H.264 MP4；抽帧确认合成层（面板、标题、HUD）进入视频，不只是屏幕预览。
- 最终 `bbsae-0.1.0.jar` 已用 TinyRemapper 将 Minecraft 引用重映射到 intermediary，BBS 类名保留；产物在项目根目录和 `build/libs/`。

## 已知技术风险

1. Gradle Wrapper 在当前机器上会因 `.gradle` 缓存权限/锁失败；本次通过独立 `GRADLE_USER_HOME` 和缓存 Gradle 发行版完成了构建。普通开发机仍建议按 README 使用 `gradlew.bat build`。
2. GLSL 运行时编译在部分驱动/Iris 组合下可能失败；失败会记录日志并跳过对应效果，不应导致花屏。
3. 深度 blit 在 Iris/不同 framebuffer 配置下可能不可用；不可用时深度效果自动降级。
4. 预览始终按合成分辨率渲染，重效果在 4K 合成上会明显变慢；动画蒙版和形状每帧重新 CPU 栅格化，4K 下尤其明显。后续可加半分辨率代理和 layer-local mask 纹理。
5. 预览/导出中曾出现过 Minecraft `BufferRenderer` 的 VAO 缓存与自定义 pass 不同步的问题；已通过 `BufferRenderer.resetCurrentVertexBuffer()` 修复，并在自定义 pass 前后重置。
6. 预览网格、安全区、深度和自动保存开关均已接到实际渲染/保存逻辑；UI 已做过一次完整视觉检查。普通开发机仍建议按 README 使用 `gradlew.bat build`，本机因执行沙箱无法让 TinyRemapper 直接写 zip，最终 jar 通过“重映射 class 目录 + PowerShell 重打包”生成。
