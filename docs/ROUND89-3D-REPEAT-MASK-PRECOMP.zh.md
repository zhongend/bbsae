# Round 89 — 3D 重复中心、模型蒙版、预合成摄像机/折叠、EffectCraft 蒙版模式

> 版本 `0.99-hotfix53`。未启动客户端；验证来自纯几何/组合测试、隐藏 GL 像素测试与全量测试。
> EffectCraft 参考：`_research/effectcraft/crates/render/src/masks.rs`、`lib.rs` 的
> `Collapse3d`/`collapse_into`/`collapsed` 与 `tests_collapse.rs`。只提取行为语义，未复制代码。

## 1. 3D 重复仍然有 bug

### 根因

`Replicate3D.copy(x, y, ..., cx, cy)` 内部的矩阵已经是
`translate(cx + x) · rotate · scale · translate(-cx)`。调用方却把 `cx` 又加进 `x`：

```java
// 旧：x 已经是 absolute centre
out.add(copy(cx + ox * k, cy + oy * k, ..., cx, cy));
```

结果矩阵平移变成 `(cx + offset) `，而不是 `offset`；普通 2D 图层和 3D 平面的复制体整体偏移
一个 pivot，重叠后看起来仍像“平面重采样/贴片”。独立模型 pivot 为 0，所以上一轮模型测试没有
暴露它。

### 修复

- `am_repeat`：传 `offset_x * k, offset_y * k`；
- `am_repeat_radial/scatter`：传 `cos/sin * radius + offset`；
- `am_line_repeat`：按合成单元中心定位，偏移 = `cellCenter - layer.x/y - sourceCenter`；
- `am_grid_repeat` 原本已传相对 pivot 的偏移，保持不变。

回归：

```text
centre=(100, 50), offset=16, count=3
first=(84,50) → matrix translation (-16, 0)
middle=(100,50) → (0, 0)
last=(116,50) → (+16, 0)
```

即 `Replicate3D` 的 copy 矩阵只包含相对 pivot 的 offset，由 `model.mul(copy)` 应用。

## 2. 所有图层蒙版默认生效

### 根因

`CompRenderer.renderCompToFbo` 对 `layer.independent` 直接走
`compositeIndependentModel(...); continue;`。该分支为 3D 模型目标，但从未调用
`applyLayerMask`，所以模型层上的路径蒙版完全无效。这违反“所有图层蒙版默认起作用”。

### 修复

- 新增 `applyModelMask`：3D 模型用 `MaskCache.getProjected`（与普通 3D 层一致），2D 模型用
  layer-space mask；
- 顺序改为：模型渲染 → 路径蒙版 → glitch 后处理 → depth/straight 合成；
- 关键所有权：模型 target 是 `ReplayActorPass` 的持久缓存，不能像普通 FBO 那样被
  `FboPool.release`；蒙版 pass 只返回自己的临时 FBO，合成后释放它；
- 预合成子层的蒙版仍只作用于子合成 FBO，父层的蒙版作用于整个预合成结果，符合
  “只有在预合成里面才限制在预合成里面”。

## 3. 预合成 3D 关系/位置

### 根因

`CompLayerOps.precompose` 只复制选中的非摄像机层；父级活动摄像机不进入子合成，子合成
`activeCameraId` 为空，`CompCamera.of(sub)` 回退到默认视图。分组前是透视 3D，分组后突然
变成 1:1，位置/深度关系看起来“坏了”。

### 修复

- 把父合成在选中时间点的活动摄像机复制进子合成：同 x/y/z、同关键帧、in/out 与图层一起
  rebase，并设置 `sub.activeCameraId`；
- 摄像机在组起点尚不可见时回退到 0 时刻的活动摄像机；
- 新增 **折叠变换（Collapse Transformations）** 开关：
  - 仅当预合成层没有 paths masks、effects、styles 时生效；
  - 子层 3D 渲染使用父合成摄像机（`CompScene.pushCamera(CompScene.camera(context))`），
    分组后投影与父合成一致；
  - 有蒙版/效果/样式时自动压平，避免把预合成层的 mask/effect 错误复制到每个子层；
- 该行为对应 EffectCraft `collapsed()`：`layer.switches.collapse` 且无 effects/masks/styles；
  `collapse_into` 把嵌套 comp 的 world 与父 camera 串起来。

## 4. EffectCraft 蒙版组合模式

`CompMask` 原本只有 Add/Subtract/Intersect/Exclude。EffectCraft `masks.rs` 的组合语义包含：

| 模式 | 公式 |
|---|---|
| Add | `a + b - a*b` |
| Subtract | `a * (1-b)` |
| Intersect | `a * b` |
| Lighten | `max(a,b)` |
| Darken | `min(a,b)` |
| Difference | `abs(a-b)` |
| None | `a` |

现在补齐 Lighten/Darken/Difference/None，并保持首蒙版规则：Intersect/Darken 首层当 Add，
None 首层为 0（EffectCraft `masks.rs` 同样处理）。UI 右键菜单和循环按钮同步为 8 种模式。

## 5. 验证

- 全量测试：**162 套件 / 997 项 / 0 failures / 0 errors / 1 skipped**（新增 repeat copy
  中心、precomp camera、mask combine）。
- 未启动客户端；请在工程中验证：
  1. 3D 模型 + `am_repeat` 的 copy 是否在正确位置、保持 3D 体积而不是平面贴片；
  2. 2D 图层 + repeat 的 copy 是否以图层中心对称分布；
  3. 模型图层加矩形/椭圆路径蒙版是否生效，3D 摄像机移动时蒙版是否跟随；
  4. 多选 3D 图层 → 预合成，进入子合成后 3D 透视是否与分组前一致；
  5. 打开/关闭「折叠变换」，子层是否使用父合成相机；有蒙版/效果时是否自动压平；
  6. 8 种蒙版组合模式的视觉效果；
  7. 2D/3D 预览与导出结果一致。
- 候选包：`bbsae-0.99-hotfix53.jar`（3,950,728 bytes，SHA-256
  `AA4EA39070351F24C0F8A1B35276192DF25BF669E49956CD416415E8AF1E3FA3`）。
