# Round 68 — 独立回放模型 / 真 3D 重复 / 抠像 / 右侧面板

> 版本仍为 `0.99` 后缀更新，不迭代到 1.0。
> 本轮对应新任务：独立 3D 回放、3D 重复、抠像、右侧调整面板、AE 摄像机、性能/设置、
> 主题质感，以及「回放抠像」与「bbsae 3D 模型」的分离。
> 最终大版本提交：`dea7e0c`。

## 1. bbsae 独立回放模型

### 数据
- `CompLayer.formData`：BBS Form 的 JSON 字符串；
- `CompLayer.modelForm()` / `setModelForm()` 解析、保存；
- `CompLayer.independent`：
  - `true` = bbsae 3D 模型，只在合成相机渲染；
  - `false` = 真实影片回放抠像，读取影片帧。

### 渲染
- `ReplayActorPass.renderModel`：
  - `StubEntity` + BBS Form，不依赖 film/replay；
  - 透明 + depth FBO；
  - `CompCamera` / ortho 投影；
  - 图层平移/旋转/缩放/父级通过 `CompScene.world` 生效；
  - 真实世界不生成实体，只在 bbsae 相机里可见。
- `LayerContentRenderer`：
  - 有模型 → model pass；
  - `independent` 但未选模型 → 显示「回放模型：请在图层页选择模型」，不回退影片 plate；
  - `independent=false` → 旧影片抠像路径。

### UI
- 「图层」页新增「独立模型」，打开 BBS `UIFormPalette`；
- 左栏新增「回放」页，分两段：
  - bbsae 3D 模型（虚拟相机）
  - 回放抠像（真实影片平面）
- 新建模型 / 新建抠像 / 换模型 / 删除；
- 选中行会同步选中普通时间轴轨道，模型关键帧直接用 bbsae 时间轴编辑；
- 旧抠像层提供「转为独立回放模型（推荐）」。

真机截图：`docs/verify/round65-independent-model.png`、`round69-replay-list.png`。

## 2. 真 3D 重复

之前 normal composition 的重复是在投影后的平面图上做屏幕重采样，所以仍是 2D。

- 新增 `Replicate3D`：把 `am_repeat / radial / scatter / grid / line` 的 copy 计算成
  layer-local 3D 变换；
- `CompRenderer.renderBaked3D` 直接绘制 N 个真实 3D quad；
- 已绘制的 replicator 加入 `consumedEffectIds`，后续 effect 循环不再二次应用；
- 3D 工作区复用同一套 copy 数学。

真机截图：`docs/verify/round67-repeat3d.png`（4 个带透视的独立平面）。

## 3. 回放抠像与 3D 模型分离

- `addLayer("replay")` 恢复为 **回放抠像**：2D、全屏、`independent=false`；
- `addIndependentReplay()` 创建 **bbsae 3D 模型**：3D、400×400、选择 BBS 模型；
- 左栏「回放」页分两段列出，各自新建/删除，不混用默认行为。

## 4. 抠像 alpha / 深度根因

`ReplayEntityMask` 的 coverage pass 之前 **关闭了 GL_BLEND**：
透明贴图仍会写 `alpha=1`，蒙版实际是模型整块矩形/贴图背景。

修复：

- coverage pass 启用标准 alpha blending；
- 只让真实可见的模型像素写 alpha；
- 关闭 coverage 的深度写入，只保留世界深度测试：
  近处身体部位不再把后面部位判成背景，避免身体透明洞；
- 保存/恢复完整 blend src/dst（含 alpha 通道）；
- batcher 路径也要求 `isCurrentFrame()` 和 `SceneColor.frameSerial()` 配对。

真机验证（真实影片 `export_test`，只显示实体层、隐藏世界 plate、洋红底）：

- `time=2.0`：演员位置 A，洋红背景，无世界帧矩形；
- `time=6.0`：演员位置 B，背景仍只有洋红，无上一帧残留；
- 截图：`docs/verify/round70-matte.png`、`round71-matte-motion.png`。

## 5. 轨道右侧调整面板

- 抽屉用矩阵平移整个面板：底板、文字、数值、按钮一起滑动；
- 快速面板高度扣除关键帧抽屉高度，不再遮挡下方轨道；
- 面板已打开时，编辑期间不会因为鼠标按下而隐藏；只有面板尚未打开时才延迟布局；
- `subMouseReleased` + 隐藏时清 `resizing`，修复「调完还在调」。

## 6. AE 摄像机

- `CompViewControls.dolly` 保留 `cameraZ` 的符号：负 Z 相机 dolly 后仍在后方；
- near/far clamp 对负侧同样生效；
- `orbit` 不再把 look-at 摄像机强制改成 free camera，目标点不变；
- 回归测试：`CompViewControlsCameraZTest`、`CompViewControlsOrbitTest`。

## 7. 性能与设置

项目设置新增：

- 模型像素比例：0 = 自动（合成高度 × 0.12，限制 8–64）；
- 模型渲染比例：0.25–1.0，降低独立模型 FBO 分辨率；
- 模型阴影：独立模型是否绘制 BBS 自阴影。

## 8. 界面质感（跟随 BBS 主题色）

- `CompUi.accent()` 继续返回 `BBSSettings.primaryColor()`，没有固定品牌色；
- 按钮 fill 使用很浅的上下渐变；
- 1 px 顶部内高光 + 底部内阴影；
- PRIMARY hover 叠一层 **主题色** 微弱光晕；
- 快速面板使用 4 px 阶梯圆角和外侧软阴影，全部用 `batcher.box`，避免
  `dropCircleShadow` 连续调用触发 `Already building!`；
- 保留 `UiMotion` 弹簧 hover、press、tab 下划线、抽屉动画。

旧版固定蓝色截图 `round66-apple-ui.png` 仅作对照，当前版本已恢复主题色。

## 9. 验证

- 全量：**150 suites / 947 tests / 0 failures / 0 errors / 1 skipped**。
- 候选包：`bbsae-0.99-hotfix13.jar`，已安装到正式 modpack；
  SHA-256 `8BAB81A9A81F42953FFB363C46948045A35B3FED7D40AC443F1161CCD2B047F9`。
- GitHub 大版本提交：`dea7e0c`。

## 10. 未完成

- 「独立回放列表」目前按图层管理模型，不是单个面板里的多 actor 时间轴；
- 旧影片抠像层的原生 matte 仍保留，复杂半透明/多层模型需要更多实测；
- AE 摄像机其它手势还未逐个审计；
- 性能优化还停留在模型分辨率/阴影设置，没有帧缓存级优化；
- Apple 质感只覆盖按钮/快速面板/光效，尚未完整重排所有面板。
