# Round 68 — 独立回放模型 / 真 3D 重复 / 调整抽屉 / 摄像机 Z

> 版本仍为 `0.99` 后缀。本轮直接把回放从「读取真实影片回放」改成 bbsae 自己的模型层，
> 并修复真 3D 重复、右侧调整面板和 AE 摄像机 Z 轴。

## 1. 独立 bbsae 回放模型（新任务 1/4）

### 数据
- `CompLayer.formData`：BBS Form 的 JSON 字符串；`CompLayer.modelForm()` /
  `setModelForm()` 双向解析、保存。
- `CompLayer.independent`：新加的 replay 层默认 `true`，旧工程缺省 `false`（保持影片抠像）。
- 新 replay 层默认 3D、400×400、名称「回放模型」，不再自动用全屏 world plate；
- 项目设置新增「模型像素比例」「模型阴影」「模型渲染比例」：控制独立模型的 3D 分辨率/性能和阴影。

### 渲染
- `ReplayActorPass.renderModel`：
  - `StubEntity` + `layer.modelForm()`，不依赖 film/replay；
  - 透明 + depth-enabled FBO，`CompCamera`/ortho 投影；
  - 层的平移/旋转/缩放/父级通过 `CompScene.world` 生效；
  - 没有世界 plate、没有抠像遮罩，因此不会携带背景，也不会因世界深度穿模透明；
  - 只在合成相机中可见，真实世界不生成实体。
- `LayerContentRenderer`：独立模型优先走 model pass；未选模型时显示「回放模型：请在图层页选择模型」，
  不回退到真实影片 plate。

### UI
- 「图层」页新增「独立模型」行，按钮打开 BBS `UIFormPalette`；
- 选择后保存到 `formData`，可被 Gson 持久化；
- 清除模型回到空模型状态；
- 左栏新增独立「回放」页：分成两个明确区域：
  - **bbsae 3D 模型（虚拟相机）**：独立回放层，选择 BBS 模型，可用 bbsae 时间轴 K 帧；
  - **回放抠像（真实影片平面）**：从真实影片帧抠出的平面动画，与 3D 模型互不混用；
  - 新建模型 / 新建抠像 / 换模型 / 删除；
  - 选中行会同步选中普通时间轴轨道。
- 旧层在「图层」页提供「转为独立回放模型（推荐）」：选一个 BBS 模型后切换为
  `independent=true`，从此不再读取真实影片/抠像遮罩，避免背景残留和深度穿模。
- 真机验证：`-PbbsaeView=actorlist`，截图 `docs/verify/round69-replay-list.png`。

### 真机验证
- `-PbbsaeView=independent`：独立 MobForm 鸡成功渲染在合成相机中，截图
  `docs/verify/round65-independent-model.png`。

## 2. 真 3D 重复（新任务 2）

之前 normal composition 的重复是在**投影后的平面图**上做屏幕重采样，所以看起来仍是 2D。

- 新增 `Replicate3D`：把 `am_repeat / radial / scatter / grid / line` 的 copy 计算为
  **layer-local 3D 变换**；
- `CompRenderer.renderBaked3D` 直接把这些变换乘到图层 world 矩阵，绘制 N 个真实 3D quad；
- 已绘制的 replicator 加入 `context.consumedEffectIds`，后续 effect 循环不再对它做屏幕重采样；
- 3D 工作区复用同一套 copy 数学（`CompViewportRenderer`）。
- 真机验证：`-PbbsaeView=repeat3d` 显示 4 个带透视的独立平面，
  截图 `docs/verify/round67-repeat3d.png`。

## 3. 回放抠像与 3D 模型分离（新任务 8）

- `addLayer("replay")` 恢复为 **回放抠像**：全屏、2D、读取真实影片帧；
- `addIndependentReplay()` 才创建 **bbsae 3D 模型**：`independent=true`、3D、400×400、
  打开 BBS 模型库；
- 左栏「回放」页分两段列出，各自新建/删除，不再把两种功能混成一个默认行为。

### 抠像 alpha 根因修复
`ReplayEntityMask` 的 coverage pass 之前 **关闭了 blend**，透明贴图仍然写入 alpha=1，
所以蒙版实际是模型的整块矩形/贴图背景。现在：

- coverage pass 启用标准 alpha blending；
- 只让真实可见的模型像素写入 alpha；
- 关闭 coverage 的深度写入，只保留世界深度测试：近处身体部位不再把后面部位判成背景，
  避免抠像身体出现透明洞；
- 保存/恢复完整的 blend src/dst（含 alpha 通道），避免污染后续 UI/渲染；
- 深度遮挡仍保留。

这是「抠出的图像携带背景」「模型身体部分透明」的直接根因修复之一。

### 真机抠像验证
`-PbbsaeView=filmreplay -PbbsaeFilmId=export_test`，只显示实体层、世界 plate 隐藏、底色洋红：

- 截图中模型周围是洋红底色，没有世界帧矩形背景；
- 模型轮廓被独立抠出，说明 coverage alpha 不再把整块贴图写成不透明；
- 同一场景在 `time=2.0` 与 `time=6.0` 各拍一张：演员位置发生移动，周围始终是洋红底色，
  没有上一帧的矩形/背景残留；
- 截图 `docs/verify/round70-matte.png`、`docs/verify/round71-matte-motion.png`。

### 附：UI 圆角实现移除
BBS `Batcher2D.dropCircleShadow` 的 4 参版本不会结束自己的 BufferBuilder，连续画 4 个圆角会触发
`IllegalStateException: Already building!`。已删除 `UiGfx.roundedBox`，快速面板恢复矩形底板，
避免合成器一打开就崩。

## 4. 轨道右侧调整面板（新任务 3/9）

- 抽屉改为 **矩阵平移整个面板**，文字、数值、按钮和底板一起滑动，不再出现
  "底板动了、文字没动"；
- 快速面板高度扣除关键帧抽屉高度，不再覆盖下方轨道；
- 编辑期间不再因为鼠标按下而隐藏面板；只有面板尚未打开时才延迟布局；
- `subMouseReleased` + 隐藏时清 `resizing`，修复「调完还在调」。

- `UILayerQuickPanel` 增加右侧抽屉进度：打开时从右侧滑出，关闭时滑回右侧后再隐藏；
- `subMouseReleased` 显式结束宽度拖动，`setVisible(false)` 也会清掉 `resizing`，
  解决「调完数据还在继续调」的卡住状态；
- 抽屉渲染时临时平移 `area.x`，渲染后恢复最终位置，点击命中仍按最终面板计算。

## 5. AE 摄像机 Z（新任务 5）

- `CompViewControls.dolly` 保留 `cameraZ` 的符号：相机在合成平面后方（负 Z）时，
  dolly 仍留在后方，不再突然跳到正 Z；
- `orbit` 不再强行把 look-at 摄像机改成 free camera，保持目标点与相机类型；
- near/far clamp 对负侧同样生效；
- 新增 `CompViewControlsCameraZTest` / `CompViewControlsOrbitTest`。

## 6. 美术风格（新任务 7，改为跟随 BBS 主题）

- **不固定 accent**：`CompUi.accent()` 继续返回 `BBSSettings.primaryColor()`；
- 质感通过材料而不是改色：
  - `UICompButton` 的 fill 改为很浅的上下渐变（顶部提亮 3.5%，底部压暗 5.5%）；
  - 1 px 顶部内高光 + 底部内阴影；
  - 快速面板使用 4 px 阶梯圆角 + 外侧软阴影（纯 batched box，避免 BBS 圆形 builder 的
    `Already building!` 崩溃）；
  - hover 时保留黑色软阴影，PRIMARY 额外叠一层 **主题色** 的微弱光晕；
  - 快速面板增加顶部高光、底部渐变暗角和主题色氛围光；
- 动效仍由 `UiMotion` 弹簧驱动：hover lift、press inset、tab underline、面板入场；
- 真机截图 `docs/verify/round66-apple-ui.png` 仍是旧版固定蓝色，**仅作第一版对照**；
  新版本已恢复 BBS 主题色，尚未重拍截图（避免频繁客户端测试）。

## 7. 验证

- 全量测试：**150 suites / 947 tests / 0 failures / 0 errors / 1 skipped**。
- 定向：`CompViewControlsCameraZTest`、`ReplayActorMappingTest`、`ExtrudeEffectTest`、
  `GlStateGuardSrgbTest`、`EffectRepeatTest`。
- 真机截图：
  - 独立模型：`docs/verify/round65-independent-model.png`
  - 真 3D 重复：`docs/verify/round67-repeat3d.png`
  - Apple 蓝 accent：`docs/verify/round66-apple-ui.png`（旧版固定色，仅对照）
  - bbsae 独立回放列表：`docs/verify/round69-replay-list.png`
  - 抠像真机（洋红底、仅实体）：`docs/verify/round70-matte.png`
  - 抠像运动对比（time=6.0）：`docs/verify/round71-matte-motion.png`
- 候选包：`bbsae-0.99-hotfix13.jar`，已安装到正式 modpack；
  SHA-256 `8BAB81A9A81F42953FFB363C46948045A35B3FED7D40AC443F1161CCD2B047F9`。

## 8. 未完成

- 「独立回放列表」目前是 per-layer 模型，不是多 actor 的独立列表面板；下一步加多模型列表和
  各自的时间轴轨道。
- 旧影片抠像层仍保留原 matte 路径；独立模型层已不走 matte。
- 右侧抽屉动画尚未做独立截图验证。
- AM 逐项像素对齐和完整 Apple 圆角/排版仍是后续项。
