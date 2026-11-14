# Round 104 — 预合成在 3D 世界 / 回放编辑器中的显示与透明修复

> 版本 `0.99-hotfix68`。未启动客户端截图；用渲染路径审计 + 纯逻辑测试 + 全量回归验证。

## 1. 现象

预合成（用户界面里也叫“预渲染”）图层放进 3D 世界或回放编辑器的 3D 预览后：

- 显示的是一块不透明蓝色板，不是预合成内容；
- 背景不是透明的；
- 普通预合成的 3D 子内容位置/透视错误。

## 2. 根因一：草稿色板

`CompLayerSourceCache.rasterize()` 在原逻辑中：

```text
if ("precomp".equals(layer.type) && wantsPrecompPlate(layer, context))
    drawPrecompPlate(...);   // 蓝色不透明板 + “预合成（草稿）”
else
    LayerContentRenderer.draw(...); // 真实嵌套合成
```

`wantsPrecompPlate` 在 `context.preview && context.draftViewport` 时为 true。3D 工作区
`CompViewportRenderer` 设置 `draftViewport=true`；回放编辑器的自由/环绕视图也使用
`CompViewportRenderer`，所以两者都只看到蓝板。

蓝板是编辑器性能捷径，但它：

- 不透明，丢掉了预合成自己的 alpha；
- 不显示真实嵌套内容；
- 尺寸/位置只是 `layer.width/height` 的矩形，和预合成内部 3D 画面无关。

### 修复

删除草稿色板分支。所有模式都调用 `LayerContentRenderer.draw()`，真实嵌套合成在层局部
栅格中渲染。`wantsPrecompPlate()` 保留为审计 API，但恒返回 false，防止旧路径或第三方
调用把色板重新引回来。

## 3. 根因二：父相机双重投影

`LayerContentRenderer.drawPrecomp()` 的非折叠分支原来：

```java
CompRenderer.renderCompToFbo(context.project, sub, time, context.depth,
    context.projection, context.camera, ...);
```

在 3D 工作区里，`context.projection` 是父级自由视口的透视投影，`context.camera` 是
父视口相机。于是：

1. 嵌套合成先按父级透视相机渲染成一张图；
2. 这张图又被放进父层四边形，按同一个父级投影再投影一次。

结果 3D 子内容被投影两次：位置、大小、透视都不对；某些视角下还会落到画外，看起来像
“预合成消失了”。

### 修复

普通预合成必须先在**自己的合成空间**里压平：

```java
// 非折叠：子合成自己的相机 + 投影
CompRenderer.renderCompToFbo(..., null, null, ...);

// 折叠变换：显式继承父相机
CompScene.pushCamera(parentCamera);
CompRenderer.renderCompToFbo(..., context.projection, context.camera, ...);
```

判断抽成 `LayerContentRenderer.usesParentCameraForPrecomp(layer)`：

- `layer.collapse == true`；
- 且没有蒙版、效果、样式（否则父层像素操作必须作用于压平后的整张预合成）。

这样父层四边形只负责“把子合成图像放到父层空间”，不再二次投影嵌套 3D 相机。

## 4. 透明背景

`drawPrecomp` 原本就调用：

```java
renderCompToFbo(..., transparentStage = true)
```

`stageColor(transparentStage=true)` 返回 alpha 0，嵌套合成没有自己的不透明画布背景。
草稿色板删除后，预合成边缘/空区域的 alpha 保持透明；只有子合成自己绘制的像素才
不透明。

## 5. 回放编辑器同步

回放编辑器复用同一套渲染入口：

- 自由 / 环绕视图：`UIReplayViewport` → `CompViewportRenderer` → `CompLayerSourceCache`
  → `LayerContentRenderer.drawPrecomp`；
- 摄像机预览 / PiP：`UIReplayViewport` → `CompRenderer.renderFrameWithCamera` → 同一
  `CompRenderer` 嵌套入口；
- 导出 / BBS 影片叠加：`CompositionOverlay` → `CompRenderer`。

因此本轮只改共享的 `CompLayerSourceCache` 与 `LayerContentRenderer`，合成器预览、
3D 工作区、回放编辑器和导出自动一致；没有第二套回放专用渲染路径需要同步。

## 6. 验证

- `RenderCacheAuditTest`：
  - `wantsPrecompPlate` 在 preview/editor/export/draft 全部为 false；
  - 普通预合成 `usesParentCameraForPrecomp=false`；
  - 折叠变换为 true；带蒙版时回到 false（保持压平）。
- 全量测试：**175 套件 / 1063 项 / 0 failures / 0 errors / 1 skipped**。
- 没有客户端截图（按项目约束）；本轮涉及真实 GL 预览，建议实机抽检。

## 7. 建议实机检查

1. 合成器 2D 预览 + 3D 工作区各放一个预合成，确认两者都显示真实内容、背景透明；
2. 预合成内包含 3D 图层/摄像机，确认自由视口里位置和透视不再双重；
3. 打开「折叠变换」，确认显式继承父相机；加蒙版后确认自动压平；
4. 回放编辑器自由/环绕/摄像机预览/PiP 各看一次；
5. 导出一帧，确认与预览一致。
