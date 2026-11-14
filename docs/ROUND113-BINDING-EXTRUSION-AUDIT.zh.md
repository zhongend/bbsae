# Round 113 — 六项修复的复核与补修

> 版本 `0.99-hotfix78`。目标：不是“再改一遍”，而是逐项验证 hotfix76/77 是否真正解决了
> 用户报告的现象，并修掉复核中发现的新问题。未启动客户端截图；全量 **185 套件 / 1138 项 /
> 0 failures / 1 skipped**。

## 复核结论总表

| # | 用户报告 | 复核结论 | 本轮补修 |
| --- | --- | --- | --- |
| 1 | 挤出厚度预览显示错误 | 顺序修复正确；但从背面/旋转后仍会变平板 | 按相机深度排序切片（透视 w / 正交 NDC z） |
| 2 | 效果打帧跳到曲线页 | 已改抽屉；源码契约锁定 | 无（补 `RegressionContractsTest`） |
| 3 | 30 分钟时间轴 + 记忆 | 时长修复有效；抽屉关闭再打开会重置缩放 | `primeTimeline` 只在需要首次适配时重置 |
| 4 | 检测并修复 bug | 见下 | 影片变化后的时长漏修、提示被覆盖、乱码注释 |
| 5 | 绑定无法操作 + 自动创建片段 | 选择器有效；但父级/相机让绑定位置错，片段绑定按钮指向不存在的目标 | 父空间反解 + 屏幕空间绑定 + 自动建工程/合成；导出临时片段清理 |
| 6 | 预合成加效果后尺寸跳变 | 折叠不再受像素操作影响；测试覆盖 | 无（补 `CollapsedPrecompTest` 已在 hotfix77） |

## 1. 绑定位置（复核发现的新 bug）

### 根因 A：父级二次偏移

`CompScene.local()` 把 `BindingResolver` 返回的**合成空间**坐标写进图层本地平移，
`world()` 再乘父级世界矩阵。有父级的图层最终位置 = 父级变换(绑定坐标)，不是绑定坐标。

修复：`world()` 在父级存在时，把绑定点按父级世界矩阵的逆变换解回父空间：

```java
Vector3f target = new Vector3f(bound.x, bound.y, local.m32());
new Matrix4f(parentWorld).invert().transformPosition(target);
local.m30(target.x); local.m31(target.y); local.m32(target.z);
return parentWorld.mul(local);
```

### 根因 B：相机二次投影

绑定图层若是 3D，世界矩阵会再经过一次相机投影，锚点被移动。

修复：`BindingResolver.isBound(layer)` 为真时：

- `cameraAffects` → false（屏幕空间）；
- `usePerspective` → false；
- `sortsIn3D` → false（不按 3D 深度藏到几何后）。

### 根因 C：平滑被推进两次

`local()` 与 `world()` 各解析一次绑定，`BindingResolver` 的平滑状态一帧会推进两次。
新增 `CompRenderContext.bindingCache`，每层每帧只解析一次。

测试：`BindingSceneTest`（4）：父级精确落点、3D 绑定保持屏幕空间、未绑定 3D 仍跟随相机、
同帧重复取用一致。

## 2. 回放绑定的两个逻辑漏洞

- 模型级回退要求 x/y/z **三个通道都有键**，只动 X 的 replay 会静默回退到图层自身 X/Y。
  改为逐通道取值，空通道按 0。
- `BBSModClient.getFilms().getControllers()` 可能包含世界里另一部影片；现在只使用
  `BindingResolver.setFilm(...)` 传入的当前影片的 controller。

## 3. BBS 片段面板的绑定

`UICompositionClip` 的「绑定到当前回放工程」原来只计算字符串，不创建/校验目标。
现在：

1. `CompStore.loadFilmProject` 创建/加载影片工程；
2. `CompStore.ensureReplayComp` 建好当前 replay 的合成；
3. 写入 `clip.project` / `clip.comp`，保存影片；
4. 新增「选择合成…」列表（名称 · id · 尺寸 · 时长）；
5. 目标不存在时给出明确提示。

## 4. 挤出的相机相关顺序

`CompExtrusion` 返回的顺序是“默认相机由远到近”。旋转超过 90° 或自由相机从背面看时，
原画面变成最远面，最后绘制 → 体积被盖住。

修复：`CompRenderer.sortSlicesForCamera` 在绘制前按实际裁剪空间深度排序：

- 透视投影：按 clip `w` 降序（w 越大越远）；
- 正交投影：按 NDC `z` 降序（near=-1, far=+1）。

测试：`ExtrusionCameraOrderTest`（4）：正面原画面最后、背面原画面最先、正交同序、侧视不丢切片。

## 5. 关键帧抽屉的时间轴记忆

`setKeyframeOpen(true)` → `primeTimeline()` 每次重新展开都：

```java
if (first || resetTimelineView) { resetTimelineView = true; refreshSheets(); }
```

`refreshSheets()` 于是把抽屉时间轴适配到全长，覆盖 hotfix76 的记忆。现在只有
`keyframeViewNeedsFit`（新文档首次适配）才重置；用户缩放/滚动在关闭→打开后保留。

## 6. 导出临时片段

BBS 导出需要影片轨道上的片段才能采样合成。`prepareForExport` 会创建它；导出结束后原来
会永久留在轨道上。现在：

- 导出前记录 `hadClip`；
- 若原本没有、且用户没有手动添加，`recorder` 完成/取消回调里移除临时片段并保存影片。

## 7. 时长修复的两处遗漏

- `repairImplausibleLongDuration` 现在也在 film-bound 的 `bind` / `selectReplay` 中作为
  第二道指纹执行：影片时长已经变化、旧指纹（等于影片时长）失效时仍能修复。
- 修复状态不再被“合成未加入影片轨道”的提示覆盖（`durationRepairedOnBind`）。

## 8. 源码契约测试

`RegressionContractsTest`（3）直接锁住三个用户报告的回归点：

| 断言 | 防止 |
| --- | --- |
| 面板中不存在 `showCurveFor(path)` | 效果打帧又跳曲线页 |
| `CompositionClip.ensureForFilm(` 只出现 1 次 | 视图/时长路径又自动建片段 |
| 不存在 `Math.max(this.doc.duration, duration)` | 影片时长又被写进合成 |

## 9. 验证

```text
> Task :test
185 suites / 1138 tests / 0 failures / 0 errors / 1 skipped
BUILD SUCCESSFUL
```

新增：`BindingSceneTest`(4)、`ExtrusionCameraOrderTest`(4)、`RegressionContractsTest`(3)。
更新：`RenderCacheAuditTest`（折叠不再因蒙版失效）。

## 10. 仍需实机确认（无法 headless 断言的交互）

1. 绑定区选择回放实体后，拖动 replay，HUD 图层应贴住目标；父级图层也应贴住。
2. 在 BBS 影片编辑器选中合成器片段 →「绑定到当前回放工程」应立即显示工程/合成信息。
3. 3D 图层挤出后旋转 180°/自由相机绕到背面，体积仍可见。
4. 时间轴缩放到某段 → 关关键帧抽屉 → 再打开，缩放保持。
5. BBS 导出结束后，影片轨道不应多出片段（导出前没有的话）。
