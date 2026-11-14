# Round 92 — 3D repeat 视口、模型层级、AM 蒙版模式、多选可见性

> 版本 `0.99-hotfix56`。未启动客户端；验证来自纯逻辑/GL 测试与全量测试。
> AM 本地参考没有可用的 Java 源码（只有 assets/patch 脚本），Mask/Exclude 的语义按 AM
> 的图层混合模式行为与现有 composite shader 的 `BLEND_MASK/BLEND_EXCLUDE` 实现对齐。

## 1. 3D 重复在视口仍然偏移/只显示一层

### 根因

- 合成/导出路径上一轮已把 copy 中心修成相对 pivot，但 `CompViewportRenderer` 里保留了一份
  独立实现：
  - `copyTransform(cx + ox*k, ...)` 再次把 pivot 加进偏移；
  - 只接受 `layer.effects.get(firstProjectedEffect)` 且必须是 replicator，`shake → repeat`
    之类的 chain 在视口直接不复制；
  - grid/line/radial/scatter 各有一份数学，容易与 `Replicate3D` 分叉。
- 结果是“导出对了、3D 工作区还是错的”，用户看到仍然像平面/偏移重复。

### 修复

- `drawReplicated3D` 直接使用：
  - `Replicate3D.leading(layer, context)`（projected 段内向后找 replicator）；
  - `Replicate3D.transforms(resolved, layer, context, source)`（与合成/导出同一套 copy 矩阵）；
  - 逐 copy `drawCopy`，alpha/opacity 一致。
- 删除 viewport 的 `copyTransform`/`hash01` 死代码，单一来源。

## 2. 回放模型穿透层级位于上方的模型

### 根因

`orderLayers` 把独立 replay 模型与 3D 平面一起按 `planeDepth`（图层原点）排序。模型是体积，
layer-origin 深度不是其真实遮挡关系；低层模型可能被排到上层模型之后，覆盖上层模型。

### 修复

`orderLayers` 将 `layer.independent` 作为 composition-stack 边界：

- 模型保持图层栈顺序；
- 逐像素深度开启时，共享深度缓冲仍按模型真实深度与平面互遮挡；
- 深度关闭时，栈顺序就是唯一正确结果；
- 新增 `replayModelsKeepCompositionStackOrder`。

## 3. 蒙版/排除要像 AM 一样作用于所有图层

### AM 语义

AM 的 Mask/Exclude 是**图层混合模式**：当前层 alpha 对“已经合成在它下面的整个 backdrop”
做 mask/exclude，而不是只给相邻下一层设 track matte。因此一个蒙版层会一次影响它下方的
所有图层。

### 修复

- 蒙版页/图层页快捷按钮改为：
  - `用作蒙版（所有下方图层）` → `BLEND_MASK`
  - `用作排除（所有下方图层）` → `BLEND_EXCLUDE`
- 源形状保持可见、可编辑，不再被隐藏，也不再强制把选择跳到目标；
- 复用现有 composite shader 的 `Blend==13/14` alpha-backdrop 路径：输出 backdrop RGB、
  alpha 乘/反乘源 alpha，天然作用于整个 accumulation；
- 3D 蒙版源若走 `DepthPlaneCompositor` 会写共享深度，让后续图层把蒙版形状当实体遮挡；
  现在 `BLEND_MASK/EXCLUDE` 强制走普通 `PassRenderer.draw`（不做平面深度写入）；
- 旧 track matte（单目标）留在高级区域，不参与快捷按钮。

## 4. 多选图层显示

- 时间轴：所有选中行画 accent 顶部/底部边框；主选金色、次选蓝色，次选再加左侧 4px 色条；
- 预览：所有选中层画选择框，主选金色、次选蓝色；
- 检查器多选区：
  - 顶部“已选 N 个图层”；
  - 每个选中项显示 `✓ 序号. 名称 · 类型`；
  - “取消全选”按钮；
- 顶部多选提示显示前 3 个图层名，不再只有数量。

## 5. 验证

- 全量测试：**163 套件 / 1000 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端；请重点验证：
  1. 3D 空间中的 repeat 与导出是否一致（含 shake→repeat chain）；
  2. 上下两个 replay 模型/模型与文字形状的遮挡；
  3. 一个形状设“蒙版/排除”后是否作用于所有下方图层，且 3D 蒙版不把形状当实体遮挡；
  4. 多选时时间轴/预览/检查器是否都能明确看出选中项与主选。
- 候选包：`bbsae-0.99-hotfix56.jar`（3,951,934 bytes，SHA-256
  `2192CFB35EB1DAEE267E8C6F1FF0C25FF1AF60A07B5A6DDDEC0B03D7E2E61B84`）。
