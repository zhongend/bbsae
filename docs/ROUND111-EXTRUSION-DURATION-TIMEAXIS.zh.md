# Round 111 — 挤出绘制顺序、效果打帧、30 分钟时间轴与大小记忆

> 版本 `0.99-hotfix76`。未启动客户端截图；全量 181 套件 / 1123 项 / 0 failures / 1 skipped。

## 1. 挤出厚度在预览里显示错误

### 现象

开启「挤出厚度」后，预览里的图层不是“有厚度的立体”，而是一块发暗、带侧面色调的
平板；原画面（文字/颜色）被盖住或明显变暗。3D 视口里则几乎看不到体积。

### 根因：画家顺序

每个切片都是一张**整层的带 alpha 贴图**，而 `renderBaked3D` 的目标只做 alpha 混合、不做
逐像素深度测试，所以绘制顺序就是可见性：

```
全部切片都在同一 (x, y) 覆盖范围；后画 → 盖住先画。
正确：远 → 近，原画面最后。
```

旧 `extrude3d` 效果的循环是 `slice = firstSlice … 0`（z=-depth → 0，远到近）。
hotfix74 的 `CompExtrusion` 对「向后」返回 `z=0, -d, …`（近到远），把最远、最暗的
侧面画到了最后——正好盖在原画面上。逐像素/混合推导：

| 顺序 | 结果 |
| --- | --- |
| z=0 先画，-60 最后画 | 远端暗色整片覆盖 → 平板 |
| -60 先画，z=0 最后画 | 原画面在最前 → 侧面只在边缘露出 |

3D 自由视口有深度测试，近处先画会让所有远端切片被深度测试丢弃 → 只剩平面。

### 修复

- `DIRECTION_BACK`：`for (i = count-1; i >= 0; i--)`，即远 → 近，原画面最后。
- `DIRECTION_FRONT` / `DIRECTION_BOTH` 原本就是远 → 近，保持不变。
- 测试改为断言“第一个切片 z 最远、最后一个切片是原画面（白色 tint）、z 单调不减”。
- 合成、关键帧预渲染、帧服务器、AE 编码、回放预览和 3D 视口共用 `CompExtrusion.slices()`。

## 2. 效果参数打关键帧跳到曲线页

### 根因

```java
if (path.startsWith("fx/") && CompPrefs.get().autoEffectCurve)
    this.showCurveFor(path);      // timelinePage = 1
```

效果参数的秒表走 `addScalarKey`，于是每次打帧底部页签都被切到「曲线」。

### 修复

- `addScalarKey` 改为 `openKeyframes()`：若当前在「曲线」页，先切回「时间轴」，再展开
  关键帧抽屉；曲线页签不再被强制切换。
- 批量「AE 关键帧」按钮同样改为展开抽屉（它本质是批量打帧）。
- 曲线入口保留且显式：效果区新增「在曲线中查看该效果」，或手动点「曲线」页签。
- 设置项文案改为「效果打帧自动打开关键帧」，不再声称会自动定位曲线。

## 3. 切换页面后时间轴变成 30 分钟

### 根因：合成时长被影片时长污染

`bind`（影片绑定）和 `selectReplay`（回放切换）都有：

```java
float duration = film.calculateDuration() / 20F;
this.doc.duration = Math.max(this.doc.duration, duration);
this.doc.workAreaOut = Math.max(this.doc.workAreaOut, duration);
```

BBS 摄像机时间轴的占位 clip 常见为 36000 ticks = 1800 秒 = 30 分钟。于是每次通过影片/
回放进入合成器，都会把一个 12 秒合成拉成 30 分钟；轨道尺与关键帧抽屉按合成时长适配，
自然显示到 30 分钟。切换页面触发重新绑定 → 又拉长一次。

### 修复

- 删除两处 `max(doc.duration, filmDuration)`；合成时长只由内容与用户编辑决定。
  导出长度不受影响：`CompositionClip.exportDurationTicks` 取影片与合成的较大值。
- 新增 `CompDurationRepair`（`comp` 包，纯函数）：

| 场景 | 指纹 | 修复值 |
| --- | --- | --- |
| 影片绑定 | 时长 ≈ 影片时长；>60s；内容结束 < 时长一半 | `max(12, contentEnd)` |
| 独立打开 | 时长 ≥600s；整分钟（±0.05s）；工作区满长；内容 <60s | `max(12, contentEnd)` |

- 修复后 `afterDurationRepair`：删除该合成的视图记忆、清空旧版视图字段、重新 `fit()`
  轨道与抽屉，避免旧“30 分钟视口”被恢复。
- 「合成」页检测到超长空尾时显示说明 + 「时长 = 内容结尾（清理空尾）」按钮。
- `.bak` 仍由保存流程维护，可用作回退。

## 4. 时间轴大小记忆

### 设计

`CompProject.CompSettings.timelineViews`：

```
compId -> "fit,shift,zoom,scrollY,keyShift,keyZoom"
```

- **轨道时间轴**与**关键帧抽屉**两条轴都记；两者是同一时间范围的两个视图。
- `fit` 作为模式保存：恢复时调用 `fit()` 而不是写死数字，内容变长后仍会自动跟随。
- 记录点：`writeEditorViewState`（每次保存/关闭）、切换回放、进入/退出预合成、切换合成、
  合成↔回放工作区、撤销前。
- 恢复点：工程打开（`restoreEditorViewState` 优先于旧版单视图字段）、上述所有切换的返回。
- 旧版 5 字段格式仍可解析；非法/0 zoom 条目被忽略，退回 `fit()`。
- 编解码在 `CompSettings.timelineView(...)` / `putTimelineView(...)`，有单测。

## 5. 顺带修复

- `duplicateComposition`：空克隆 id 不再走 `getComp("")`（该调用按设计返回第一个合成，
  会误改无关合成）。
- 旧共享预合成拆分改为按索引遍历并检查本轮新增的合成（克隆内部仍有共享对时也能修完），
  带上限保护。
- 代码里“AE 关键帧会打开曲线”的注释/提示同步更新。

## 6. 验证

- `CompExtrusionTest`：绘制顺序（远→近、原画面最后）、封盖、明暗、旧效果兼容；
- `CompDurationRepairTest`（9）：影片指纹、内容保护、独立指纹、工作区条件、非整分钟；
- `TimelineViewMemoryTest`（5）：编码回环、fit 标志、旧格式、非法值、工程保存重载；
- 全量：**181 套件 / 1123 项 / 0 failures / 0 errors / 1 skipped**。

## 7. 建议实机复验

1. 选中文字/形状 → 3D 页开启 3D 与挤出（60px），在「图层」页给旋转 X/Y 打两个不同的键，
   预览与回放预览都应看到原画面在正面、侧面按厚度着色；
2. 效果页点参数秒表 → 底部应停在「时间轴」并展开关键帧抽屉；
3. 打开一个曾经 30 分钟的旧工程 → 状态栏应提示时长已修正（或「合成」页出现清理按钮）；
4. 在时间轴缩放到某段、切换回放/预合成再回来 → 缩放保持不变；
5. 导出 2D 与 3D 各一帧，确认挤出侧面与预览一致。
