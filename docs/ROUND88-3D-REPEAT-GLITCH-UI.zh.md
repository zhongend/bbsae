# Round 88 — 3D 重复 / Glitch 范围 / Gizmo 条 / 布局锁 / 蒙版入口 / 正方形曲度

> 版本 `0.99-hotfix52`（只加后缀）。按用户要求**未启动客户端截图测试**；结论来自渲染路径代码、
> 隐藏 GL 上下文的像素测试和纯几何/UI 布局测试。

## 1. 重复效果应用到 3D 模型仍是平面 2D

### 根因

- 独立回放模型由 `CompRenderer.compositeIndependentModel` 单独渲染，不进入普通图层的
  effect 循环；repeat 对它要么完全不生效，要么走投影后平面重采样。
- 普通 3D 层原来的 `Replicate3D.leading` 只接受“projected 段的第一个 effect 是 replicator”。
  `shake → repeat`、`am_glass → repeat` 等 chain 会回退到 composition-space 2D 重采样。

### 修复

- `Replicate3D.find` 在独立模型链中找第一个启用 replicator；
  `Replicate3D.transforms` 新增显式 center 重载，copy 变换以图层原点为中心。
- 新增 `CompRenderContext.modelPixelOffset`，在 `ReplayActorPass.modelMapping` 的
  “块→像素缩放之后、平移 replayAnchor 之前”应用；每个 copy 重渲染真实模型，不再复制
  已投影的平面图。
- `Replicate3D.leading` 在 projected 段内向后查找 replicator，`shake/glass` 等前置 projected
  effect 在 copy 合成之后执行，repeat 本身不再被二次重采样。
- 复制体仍受 `count <= 48` 和 grid 上限保护；独立模型复制后，depth/per-pixel compositing
  与单模型路径共用 `compositeModelTarget`。

### 验证

- `EffectRepeatTest.independentModelRepeatFindsCopyTransforms`：独立 replay 层 + `am_repeat`
  找到 3 个 copy，首/尾偏移分别为 -16/+16 px。
- 既有 `EffectRepeatTest` 覆盖 2D/投影路径的所有 repeat 家族。

## 2. Glitch 只出现在图层形状内

### 根因

- `glitch` 被烘焙进 3D 图层的 layer-local raster；该 raster 只覆盖图层/形状矩形 + padding，
  投影 quad 在边缘截断，位移条带看起来像被形状蒙版裁掉。
- 独立模型的 effect 根本不会被执行。

### 修复

- `CompRenderer.requiresProjectedPass` 把 `glitch` 归入 projected pass；3D 层在投影后的
  composition-size 内容上执行，可写到图层矩形之外。
- `composeIndependentModel` 新增 `applyModelPostEffects`：对独立模型只应用 source-sampling
  的 `glitch`，先 unpremultiply、在 `EffectSpace.identity` 全合成空间运行、再按 straight
  alpha 合成；生成器类不会铺满整张合成。
- 新增像素测试 `glitchDisplacesPixelsOutsideTheLayerBox`，确认 glitch 的输出 alpha 可以出现
  在源形状矩形之外。

## 3. Gizmo 模式切换条位置 / 开关

- 旧位置 `area.ey() - 100` 在短预览中就是画面中部。
- `CompUi.gizmoModeBarRect` 改为右下角锚定：`x = area.ex() - total - 8`、
  `y = area.ey() - 46`；窄预览自动缩短按钮（<48px 时显示 W/E/R 简写）。
- 新增 `CompPrefs.gizmoModeBar`（默认 true），设置 → 线框与 Gizmo 新增「Gizmo 模式切换条」；
  关闭后不渲染、不参与点击命中。

## 4. 合成器页面布局无法锁定

- 根因：`UIDockLayout.toggleLock()` 只隐藏面板 title drag handle；分栏线 `UIDraggable` 的
  匿名 `subMouseClicked` 直接 `beginSplitterDrag`，`applySplitterDrag` 也不检查
  `layoutLocked`。
- 修复：新增 `UIDockLayoutMixin`：
  - `createSplitterHandle` RETURN 给 handle 的 enabled 条件加上 `!isLocked()`；
  - `beginSplitterDrag` / `applySplitterDrag` HEAD 在锁定时 cancel。
- 顶栏「布局」按钮显示 `布局·锁 / 布局·开`，切换时立即 `savePanelLayout()`，而不是只等
  防抖 UI-state 保存；设置 → 开发者新增「锁定面板布局」开关，与按钮共享同一状态。

## 5. 蒙版 / 排除入口（AM 风格）

- 右侧「蒙版」页顶部新增「用作蒙版（交集）」「用作排除（差集）」；普通图层还会提供
  「上方形状 → 蒙版/排除」，自动找当前图层上方最近的可见形状层，调用现有
  `applyMatteToBelow`（target.trackMatteId + mode + source 隐藏 + 选中 target）。
- 手动路径蒙版（添加矩形/椭圆、逐点、羽化、扩展、钢笔）折叠到
  「手动路径蒙版（高级）」，默认不展开，降低日常蒙版操作复杂度。
- 蒙版页显示当前 matte 来源/模式和「清除蒙版 / 排除」。

## 6. 正方形 / 三角 / 形状颜色

- 形状创建行新增「正方形」；矩形新增 `roundness` 的「曲度 / 圆角」可关键帧行；三角/多边形/
  星形新增「曲度（应用到所有角）」批量行（逐角仍可单独关键帧）。
- 修复 `CompLayer.resolveShapeValues` 和 `putScalar` 对 `roundness` 使用 `clamp01` 的 bug：
  曲度单位是像素，旧代码把 >1px 的值压成 1px；现在按非负像素值处理。
- 图层页对形状层新增「形状颜色（全部形状）」，一次改整层所有形状的 fillColor；逐形状的
  填充/渐变/描边颜色仍在形状页。

## 7. 验证与限制

- 全量测试：**161 套件 / 993 项 / 0 failures / 0 errors / 1 skipped**。
- 新增/更新测试：`EffectRepeatTest`、`EffectPipeline3DConsistencyTest`（glitch 分类 + 越界
  像素）、`CompShapePresetTest`（曲度关键帧/静态 setter）、`CompUiTest`（gizmo 条几何）。
- 未启动客户端；请在真实工程中验证：
  1. 独立 3D 模型 + repeat：复制体是 3D 模型还是平面贴片；
  2. glitch 在 3D 层/独立模型上能否越过形状边缘；
  3. gizmo 模式条右下角位置、窄预览简写、设置开关；
  4. 锁定布局后分栏线是否仍能拖动；
  5. 蒙版/排除是否在右侧蒙版页可一键作用到下方图层；
  6. 正方形曲度关键帧、曲度 >1px、形状颜色批量修改；
  7. 2D/3D 预览与导出结果一致。
- 候选包：`bbsae-0.99-hotfix52.jar`（3,948,880 bytes，SHA-256
  `6B296CD5FC9C9650A7AE95A7CA625A3F6836D9A063FB5B4FE8B589654B51D4C1`）。
