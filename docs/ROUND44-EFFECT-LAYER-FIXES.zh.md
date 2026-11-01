# Round 44 — 效果与图层 bug 修复（v0.22.0）

本轮不新增功能，目标只有一个：把用户反馈最多的 **效果（effects）** 和 **图层（layers）** 两个子系统
从头到尾按第一性原理重新核一遍，修掉真实缺陷。所有结论都从"这一帧的像素应该是多少 / 这个状态应该由谁
拥有"出发，而不是从"看起来像不像"出发。

测试规模：**265 个测试**（新增真实 GL 的 `EffectPipelineTest`，把 79 个效果条目逐个送进
`EffectRenderer`，并输出 `docs/verify/effect-pipeline.png` 联系表）。

## 1. 效果（效果栈 / shader 家族）

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| E1 | 模糊 / 辉光 / 投影 / 描边强度约为应有值的 8 倍，晕影在 `radius` 处被硬切 | `BLUR` 的 17 个采样点用 `fi * Radius`（fi 是采样序号），核宽度是半径的 8 倍；`EffectPadding` 只预留 `radius+2` | 采样偏移改为 `(fi/8) * Radius`，核宽 = 半径 |
| E2 | 预览越小效果越强，且预览与导出一致性差；拖曲线时效果会"跳" | shader 的 `Texel`/`Size` 用的是 FBO 尺寸，而 `EffectSpace` 用合成像素；代理缩放 <1 时所有像素参数放大 `1/scale` | 所有家族改绑 `EffectSpace.compWidth/Height`；`PassRenderer.blur` 增加显式 texel 重载 |
| E3 | 球面化 / 放大 / CC 镜头 / 光学补偿 / CC 倾斜 / CC 波纹脉冲 / 变换 / CC 径向快速模糊 的中心参数在图层偏离画面中心时"失效"，中心 Y 还上下颠倒 | AE_DISTORT / AE_BLUR 直接把 0..1 的图层坐标当合成 UV 用 | 统一经 `EffectSpace.localToCompUv` 转换（径向模糊原本就是对的） |
| E4 | 添加「蒙版收缩」会把图层整体抹掉 | `choke`（像素）被当成 0..1 alpha 阈值：默认 2 → `smoothstep(2,4,a)=0` | KEY shader 增加 8 方向 alpha 腐蚀/膨胀，`Choke` 与 `Softness` 按像素解释 |
| E5 | 擦除 / 溶解在 progress=0 时"原图"，0.001 时几乎全没了，关键帧起步会闪一帧 | 身份判断写反（shader 里 0 = 全透明，1 = 原图） | neutral 规则改为 `progress >= 1` |
| E6 | 键控后的软边透明度被乘了两次（50% → 25%） | `fragColor = vec4(c.rgb, c.a * alpha)`，而 alpha 已包含 `c.a` | 改为 `vec4(c.rgb, alpha)` |
| E7 | 暗角在默认参数下完全不可见，0.71 以上整段滑条无效 | 图层内最大距离是 0.707，默认半径却是 0.75 | 距离按角点距离 0.7071 归一化 |
| E8 | 「置换图」的噪波缩放滑条无效；方向模糊会继承上一次 box blur 的核 | shader 写死 80 圈；`directionalBlur` 不设置共享程序的 `Mode` | 使用 `Scale` 并读 `scale/size`；方向模糊显式 `Mode=0` |
| E9 | 抖动/震屏忽略效果强度（强度 0 仍在抖） | 该效果是 transform-only，像素通道直接返回 input，强度没有在 `CompScene.shakeOf` 里生效 | 在 transform 层乘强度，强度 0 跳过 |
| E10 | 扭曲类在文字/半透明边缘出现黑边 | `distort` / `directionalBlur` / `radialBlur` 未做 premultiplied 往返，直 alpha 重采样吸黑 | 与 AE 家族一致，先乘后除 |
| E11 | 外发光与辉光完全相同，且把颜色糊进图层内部 | 两者走同一 pass，且都叠加在图层之上 | 外发光画在图层**下面**；辉光保持叠加 |
| E12 | 「着色」在默认强度 1 下把图层压成纯色块 | 单色 `mix(color, tint, 1)` 会丢失全部细节 | 默认强度改为 0.5 |

## 2. 图层（合成 / 渲染图）

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| L1 | 多选 N 个图层按 Delete 只删一个；Ctrl+D 只复制一个 | 键盘路径调用的是单图层实现 | 多选走 `deleteSelection()` / `duplicateSelection()` |
| L2 | 复制「空对象 + 子级」后，副本的子级仍被原空对象驱动 | `CompLayer.duplicate()` 是 JSON 往返，`parentId`/`trackMatteId` 原样复制 | 先建立 oldId→newId 映射，再重写引用 |
| L3 | 给有变换关键帧的图层按 Ctrl+D，副本与原件完全重合 | `transformAt()` 在有关键帧时忽略基础字段，`x += 24` 无效 | 偏移同时写入 x/y 轴通道的所有关键帧值 |
| L4 | 预合成后整体 z 顺序变了 | 插入下标在删除图层**之前**计算 | 减去位于其下方的被删图层数 |
| L5 | 预合成后在画面上盖出一个不透明黑框；alpha 导出在预合成内失效 | 嵌套合成沿用了顶层舞台背景色（默认不透明黑），`transparentStage` 没有传入递归 | 嵌套合成一律透明舞台 |
| L6 | 带效果/样式/蒙版的 3D 预合成，导出画面里出现「预合成（草稿）」文字 | 视口草稿贴图与导出共用 source cache，草稿贴图没有 preview 门槛 | 导出路径拒绝草稿光栅，回退到真实嵌套渲染 |
| L7 | 2D 与 3D 图层上同一个轨道遮罩表现不同；遮罩层自身的蒙版被忽略；相机/空对象/调整层做遮罩会让图层直接消失 | 遮罩 pass 在 2D 路径位于效果之前、3D 烘焙路径位于之后；遮罩层内容没走蒙版；没有类型门槛 | 统一为 内容 → 蒙版 → 效果 → 样式 → 轨道遮罩 → 投影 → 不透明度；补遮罩层自身蒙版与类型门槛 |
| L8 | 独显只在合成图里生效：3D 工作区仍画全部图层，点选还能选中看不见的图层 | 三处各自实现可见性 | `CompDocument.hasSolo()/isSoloedOut()` 单一真相，四处共用 |
| L9 | 3D 图层上打了关键帧的蒙版不跟着动（导出也一样）；改「文本框宽度」不刷新画面 | source cache 指纹只认 `text/shape/fx` 通道，且哈希的是基础蒙版点；`textBoxWidth/Height` 不在指纹里 | 指纹加入 `mask/` 时间依赖、解析后的蒙版、文本框尺寸；预览缓存指纹同样补上 |
| L10 | 缩放填负数（镜像）时图层缩成 0.0001 px 消失 | `Math.max(0.0001, scale)` 丢掉了符号 | 符号保留的最小幅度钳制（`CompScene` / `CompParenting`） |
| L11 | `startTime` 被时间缩放乘了一次，2 倍速 + 偏移 1 s 实际从第 2 s 开始 | 公式 `(t - in + startTime) * timeScale` | 改为 `(t - in) * timeScale + startTime`；trim/split 同步补偿 |
| L12 | 带 BBS 预览层 / 实体层 / 深度效果的合成，每显示帧泄漏一张满分辨率 FBO，几十秒后显存耗尽 | `renderCached` 的 liveWorld 分支没有任何人释放 `composition` | `finally` 中归还 FboPool |
| L13 | 3D source cache 在动画图层 + 样式/蒙版时按帧泄漏 staging buffer | 复用路径在样式/蒙版产生新目标后丢弃了旧 staging | `releaseStaging` 精确释放；异常路径同样释放 |
| L14 | 参考层（guide）会被录进影片 / 烘焙进绿色预渲染帧；效果/蒙版 A/B 开关在绿帧与 3D 烘焙上无效 | `exportDepth` 只在 `renderFrame` 自增；预缓存与烘焙路径没有接线 | 预缓存与外部播放包 `beginExport/endExport`；A/B 标志进入 source cache 指纹与烘焙 |
| L15 | 池化纹理被删除后 `GlStateManager` 缓存仍然记得旧名字，下一次同名纹理上传落空（GL 1282）/ 过滤参数写到别的纹理 | 删除纹理没有同步缓存 | `GlTexture` 的绑定强制 raw + 同步缓存；`GlStateGuard` 补回 UNPACK 像素存储 |
| L16 | 隐藏/锁定/独显/时间重映射/遮罩/蒙版/标记/绑定/文字等检查框改动无法撤销、且在关闭自动保存时不落盘 | 这些控件没有调用 `save()`/`beginAction()` | 全部补上；`重置变换` 同时清空九条变换通道（含 3D） |

## 3. 编辑器（连带修复）

- 第二次添加「调色」效果可能复用同一个 `fxN` id → 改参数会写到旧效果、打关键帧会同时动两个效果：现在统一走 `CompStore.uniqueEffectId`。
- 多选状态下添加效果会加到主图层但没有参数面板（检查器显示多选页）：现在要求单选且未锁定，并给出状态提示。
- 右键点击多选中的某一行会把选区塌缩成一行，导致右键菜单里的"预合成/编组/对齐/分布/删除所选"永远不出现：现在保留选区，只移动主成员。
- 图层拖拽排序的空操作（点一下左侧把手又放下）会清空 redo 栈：先判空再 `beginAction`；排序后走 `setSingleSelection` 保持两个选择字段一致。
- 标记 id 用 `size + 1`，删除后再添加会重复：改用 `CompStore.uniqueId`。
- 删除图层没有清理指向它的 `binding.target`：现在一并清理。
- 图层库（模态）的遮罩区域点击会穿透到编辑器、网格滚动会粘住鼠标：现在整层消费输入，并用 `Window.isMouseButtonPressed` 收尾。
- 效果栈折叠状态只按效果 id 记录，折叠一个图层的第 1 个效果会折叠所有图层的第 1 个：键里加入 `layer.id`。

## 4. 文档

- README 的若干段中文在上一次编码往返中被写成乱码（GBK 误解 UTF-8），本轮按"非 ASCII 串回编码"修复，
  并逐条补回丢字节的 12 行（作者署名、赞助者段落、Settings → 赞助者、AE 摄像机、◆ 关键帧按钮 等）。
- `CREDITS.md` 重写：把三位赞助者放在最前面，明确写出"赞助支持了本插件的开发"，并列明用途
  （开发时间、真机渲染/导出验证、每轮回归测试）。
- README 顶部新增一句赞助者鸣谢，正文新增「鸣谢 / Acknowledgements」章节。

## 5. 验收

- `./gradlew test`：265 个测试全绿（含真实 GL 的 `EffectPipelineTest`、`ShaderPixelTest`、
  `ShaderCompileTest`）。
- `docs/verify/effect-pipeline.png`：79 个效果条目逐个应用后的联系表（本轮用于人工核对
  模糊半径、暗角、蒙版收缩、辉光顺序等修复）。
- `bbsae-0.22.0.jar` 由 `./gradlew build` 产出，SHA-256 见 README。
