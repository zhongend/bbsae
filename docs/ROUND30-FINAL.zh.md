# Round 30 — 0.19.0：赞助者、BBS 飞行相机、3D 效果与自适应

> 本轮是在 0.18.0 之后的收尾轮。原则仍然是第一性：先找出“为什么这会被用户看成失效”，
> 再在渲染/数据模型层修复，而不是在 UI 上贴一个提示。

## 1. 关键帧抽屉空白（真实根因）

`layoutKeyframeDrawer()` 在切到曲线页时把 `keyframeDrawer` 隐藏，但 `keyframeOpen` 仍是
`true`。回到时间轴页时，旧实现只恢复轨道几何和 resize handle，**没有把 drawer 的
`visible` 设回 `true`**，所以按钮显示“展开”，抽屉区域却是空白，必须再点两次。

修复：drawer 的可见性现在是 `(timelinePage == 0 && keyframeOpen)` 的纯函数；每次布局都
重新设置 visible，并对刚恢复的 drawer 执行一次 `keyframeEditor.resize()` + 视图适配。

## 2. 3D 效果“全部失效”

`CompLayerSourceCache` 把 3D 图层的效果烘焙进 layer-local 源纹理，但内容指纹只包含
几何、文字、形状等，**不包含效果栈**。因此：

- 添加/删除效果、启用/禁用、改参数都不会让缓存失效；
- 3D 图层一直拿到旧的无效果纹理，看起来就是“效果无效”；
- 动画参数因为 `fx/` 通道改变了帧号，偶尔才会刷新，造成“有时有效”的错觉。

修复：

1. 指纹加入效果 id、type、enabled、顺序，以及 `resolveEffect()` 后的全部参数/颜色；
2. 3D 图层先烘焙非深度效果，再投影；`depth_*` 在投影后按合成空间执行；
3. `DEPTH` shader 在 `DepthAvailable == 0` 时**精确返回源图**，不再用 `d=0` 抠黑/全雾。

新增 `CompLayerSourceCacheTest`：验证添加效果、改参数、禁用、改颜色、换顺序都会改变指纹。

## 3. 新增效果（58 个）

| id | 名称 | 说明 |
| --- | --- | --- |
| `grid_repeat` | 网格重复 | 行列、单元缩放、间隙、旋转、内容偏移；AM 式网格重复 |
| `random_repeat` | 随机重复 | 种子稳定；每格随机旋转/缩放/位移/透明度 |
| `turbulent_displace` | 湍流置换 | 分形噪声双通道位移，层空间，像素单位 |
| `colorama` | 色彩映射 | 阴影/中间调/高光三色渐变映射 |
| `light_sweep` | 扫光 | 可关键帧位置/宽度/柔化/角度/强度 |

`ShaderRepeatTest` 在真实 GL 3.2 上验证：1×1 为恒等、2×2 每格采样一致、间隙透明、随机种子
稳定。`ShaderPixelTest` 验证扫光 band 亮度与 0 强度恒等。

## 4. BBS / AE 摄像机

`CompLayer.cameraStyle`：

- `CAMERA_AE`：原来的目标点相机；
- `CAMERA_BBS`：自由偏航/俯仰飞行相机。

检查器顶部提供两个切换按钮；BBS 模式显示飞行按钮/图标。**F** 是上下文键：选中 BBS 相机时
进入/退出飞行，否则仍是原来的专注模式。飞行每帧写入 X/Y/rotation/rotationX/rotationY/
cameraZ/cameraFov 关键帧；退出时在播放头再写一次最终 pose，所以“停在哪里，哪里就有关键帧”。

AE 相机与 BBS 相机是同一列表里的普通图层，`CompDocument.activeCameraId` 决定当前镜头；
切换风格不会删除另一套关键帧。

## 5. 自适应 UI

`CompUi` 新增纯函数：`fitTimelineHeight`、`bodyHeight`、`maxSideWidth`、`fitColumns`、
`quickWidth`。头部按宽度分级隐藏标题/回放/时间码/保存/导出/渲染/赞助；预览栏在窄预览里
自动折叠开关；设置卡片不再坚持 560×410 最小尺寸。

`CompUiResponsiveTest` 覆盖 240/320/480/720/1080 高度和 420 px 宽度。

## 6. 线性过滤

- `CompProject.CompSettings.textureFilter`：0 自动 / 1 线性 / 2 最近邻；
- `CompLayer.textureFilter`：-1 跟随工程，其余覆盖；
- `TextureFilter` 判断：≤64 px 像素素材→最近邻；≥512 px 或 ≥512²→线性；2× 以上缩小→线性；
- 预览栏 chip 和设置页都显示“自动/线性/最近邻”，设置页还显示实时检测计数；
- 绘制前设置 `GlTexture` 的 min/mag filter，因此同一帧里 16×16 图标和 4K 照片可以不同策略。

## 7. 光影兼容

- `SceneColor` 跳过 Iris shadow pass，并优先使用主 framebuffer；
- `SceneDepth` 同样跳过 shadow pass，深度 blit 失败时保持 unavailable；
- `DEPTH` shader 的 unavailable 分支改为精确 no-op；
- 预览信息栏显示 `光影 / 原版`，深度不可用时显示“深度不可用（效果自动降级）”；
- 设置 → 高级显示 BBS/Iris 的运行状态说明。

合成层仍通过 `FrameOverlays` 进入 BBS 世界帧，因此预览和导出共用同一条路径；不按颜色
抠像（回放实体用深度），所以换光影不会改变抠像依据。

## 8. 赞助者页面

`UISponsors` 是独立全屏层，不写入工程数据。代码绘制金色轨道、光束、卡片、粒子闪烁；
支持三列/单列响应式、滚轮和 Esc。名单：**星辰久梦君、血之书、Szv文森特37**。
入口：头部“赞助”按钮、设置 → 关于、CREDITS。

> 0.19.1 按用户反馈调整：删除头部“赞助”入口和全屏覆盖层，改为
> `UICompSettings → 赞助者` 分类内的 `UISponsorBoard`；卡片下只保留“感谢你的支持。”，
> 不再有每人一段激励文案。详见 [`ROUND31-UI-POLISH.zh.md`](ROUND31-UI-POLISH.zh.md)。

## 9. 验证

- `test`：**223/223**，0 failures / 0 errors。
- `ShaderCompileTest`：所有 shader（含 REPEAT）编译链接通过。
- `ShaderRepeatTest`：4 个真实 GL 像素/行为测试。
- `ShaderPixelTest`：13 个测试，含新增光扫。
- 构建环境注意：项目路径含中文时 Gradle Test Worker 会 `ClassNotFoundException`；在
  ASCII 路径副本 `C:\bbsae-test` 使用 `GRADLE_USER_HOME=C:\gradle-home` 运行，产物再复制回
  工程目录。
- 产物：`bbsae-0.19.0.jar`，849062 字节，SHA-256
  `475E6BF6E64EEEEB1212BE3EE02B80FE786EEFFE7F9CB77F859978F01FB4DD42`。

## 10. 后续仍需实机确认

自动截图验证需要启动一次客户端；本轮不把“启动成功”当作“渲染正确”的证据。下一轮应检查：

- 赞助页在 854×480 / 1920×1080 的卡片不重叠；
- BBS 飞行相机在真实 BBS 相机控制器下 W/A/S/D 和鼠标方向；
- 光影包开启时，预览信息栏状态与深度降级提示；
- 3D 图层添加 blur / grid_repeat 后立即刷新（这是签名修复的直接用户路径）。
