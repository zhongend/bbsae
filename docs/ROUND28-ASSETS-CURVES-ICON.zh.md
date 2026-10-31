# Round 28 — 素材库滑块、AE 曲线编辑器专业级修复、插件图标（0.17.0）

> 本轮从三个用户可见的问题出发：素材库滚动条不能拖、AE 曲线编辑器（尤其贝塞尔手柄）几乎不可用、
> 项目图标没有出现在 Mod Menu / 插件图标位置。根因都不是“没做功能”，而是控件/数据模型在某个
> 不变量上断了。测试 **193/193**；1920×1080 实机截图见 `docs/verify/round28-*.png` 与
> `docs/gallery/hero.png`。

## 1. 素材库滑块不能滑动

### 根因

`UIAssetBrowser.Grid` 的滚动条是**画出来的**：`renderScrollbar()` 每帧画一条 3px 宽、全长只有
几像素的亮线，但 `subMouseClicked()` 从不判断这条线。鼠标点在滑块上，事件仍然落到下面的缩略图：
它既不会开始拖动，也不会改变 `scrollY`。所以用户看到“有滑块”，但拖它没有任何反应。

### 修复

滚动条现在是真正的控件，使用与缩略图拖拽相同的“物理按键轮询”模式（BBS 的 click/release
分发在拖出控件时不可靠）：

- 命中区 8px，滑块最小高度 24px；拖滑块时按抓取点偏移映射，不跳；
- 点轨道空白处把滑块中心移到指针（原生滚动条语义），然后继续拖；
- 拖动中每帧检查 `Window.isMouseButtonPressed(0)`，在任意位置松开都会结束，不会“粘”在鼠标上；
- 悬停/拖动时滑块变亮，并画两条 grip 线，明确它是可操作控件；
- 网格为滚动条预留了 12px，缩略图不再被滑块压住；
- 提示改为“滚轮或右侧滑块滑动”，底部计数也显示“滚轮/滑块滑动”。

## 2. AE 曲线编辑器：贝塞尔几乎不可用

这一节有 **两个独立根因**，任意一个都足以让贝塞尔“看起来坏了”。

### 2.1 根因 A：变换通道的 key 被 IdentityHashMap 错误归属

BBS 的一个 `Transform` 关键帧同时代表 **位置 X/Y、旋转、缩放 X/Y** 五条曲线。曲线编辑器为每条
曲线建了 `CompCurveChannel` 适配器，却用：

```java
owners.put(key, channel);   // IdentityHashMap<Keyframe, CompCurveChannel>
```

来反查 key 属于哪条曲线。五个 transform 适配器持有**同一个 Keyframe 对象**，后写入的
`缩放 Y` 覆盖了前面的 `位置 X`。于是：

- `channelOf(positionKey)` 返回 `缩放 Y`；
- 在位置 X 上拖动关键帧，实际修改的是 `缩放 Y` 的分量；
- 选中位置 X 的键后点“贝塞尔/缓动”，`applyEase` 过滤 `channelOf(key) == active` 得到空集合，
  **预设完全没反应**；
- 右侧“已选”卡片显示缩放 Y，而顶部读数显示位置 X，两个面板互相矛盾。

**修复：** transform key 的归属不再查 map，而是由**当前活动通道**决定：

```java
if (key.getParent() == layer.transformChannel()) {
    return active != null && active.transform ? active : firstTransformChannel();
}
return owners.get(key);   // 标量/不透明度通道仍然一对一
```

活动通道是用户点击的曲线（位置 X、旋转、缩放 Y…），因此拖拽、缓动、贝塞尔、删除、读数全部
落在同一条曲线上。

### 2.2 根因 B：拖手柄时把“指针位移”当成了“手柄绝对位置”

旧代码：

```java
key.rx = max(0.25F, dx / pxPerFrame);
key.ry = -dy / pxPerValue;
```

`dx/dy` 是**按下点到当前鼠标的位移**，不是手柄相对 key 的绝对偏移。用户按住一个已经在
`rx=5` 的手柄、只移动 1px，`rx` 立刻变成 `0.25`，`ry` 变成 0；手柄瞬间塌缩到 key 上，曲线
变成折线。于是“贝塞尔几乎不可用”。

**修复：** 按下时快照 `rx/ry/lx/ly`，拖动时做加法：

```java
key.rx = clamp(startRx + dx / pxPerFrame, 0.01, segmentLength);
key.ry = startRy + (-dy / pxPerValue);
```

入切线同理（`lx = startLx - dx/pxPerFrame`）。同时：

- 时间偏移限制在相邻段长度内，避免手柄越过前/后 key（BBS `BezierUtils` 本来也会 clamp）；
- 默认镜像（`Alt` 断开）仍由 `CompCurves.mirrorHandles` 处理；
- 状态栏实时显示“影响 % / 速度 /s”。

### 2.3 专业级可用性补齐

- **默认手柄可见**：BBS 新键的手柄是 ±5 帧、零斜率，在 60fps 全片视图里只有几像素，和“没有
  手柄”没区别。`Bezier` 预设现在把未动过的默认手柄展开为相邻段的 **33.3% 影响**，与 AE 的
  Convert to Bezier 一致；用户已经拖过的手柄不会被覆盖。
- **值域适配包含手柄**：`fitValue()` 原来只看 key 值，拉高的切线会跑到视口外，用户正在拖的
  手柄被裁掉。现在把 `value + ry` / `value + ly` 一起纳入 fit。
- **双轴平移**：中键或 Space+拖动原来只平移时间轴，现在同时平移值轴；Ctrl+滚轮缩放值轴，
  Shift+滚轮平移时间，滚轮缩放时间。
- **右键插值菜单**：在曲线上右键关键帧可直接选线性/贝塞尔/缓动/缓入/缓出/定格/自动/连续/
  删除；工具栏不再是唯一入口。
- **方向键微调**：←/→ 移动 1 帧，↑/↓ 移动 1 像素值；Shift 为 10 倍。每次按键一个撤销步骤。
- **选中读数**：图内显示“已选 N · 通道 时间 值 插值类型”，不再让用户把播放头读数误当成
  选中 key 的值。
- **手柄命中**：8px→10px，视觉手柄 7×7，命中优先于 key；只有选中的 key 才显示手柄。

## 3. 插件图标

### 根因

`BbsAeAddon.ICON` 一直指向 BBS 的 16×16 `compositor_icon.png`（dashboard 页签/片段选择器用），
但 `fabric.mod.json` **没有 `icon` 字段**，也没有 `assets/bbsae/icon.png`。因此 Fabric/Mod Menu
拿到的是默认占位图——项目提供的 compositor 图标并没有应用到“插件图标”这个位置。

### 修复

- 用项目原有的 32×32 图标（`docs/gallery/compositor-icon.png`，作者：超级大布叮）生成
  128×128 的 `src/main/resources/assets/bbsae/icon.png`：深色圆角底 + 原图白色播放框，
  在 Mod Menu 的明/暗背景上都可见；
- `fabric.mod.json` 增加 `"icon": "assets/bbsae/icon.png"`；
- BBS 内的 16×16 `compositor_icon.png` 保持不变（dashboard 页签/合成片段仍使用原图）；
- `IconResourceTest` 新增 mod icon 尺寸/透明度断言。

## 4. 验证

```text
./gradlew test --offline     193 tests, 0 failures
```

实机 1920×1080：

- `docs/verify/round28-curve2.png` — 选中位置 X 关键帧，贝塞尔手柄可见，选中读数与顶部读数一致；
- `docs/verify/round28-assets.png` — 素材库滚动条（物品分类 582 项）；
- `docs/gallery/hero.png` / `editor-overview.png` / `timeline.png` — 更新后的 GitHub 介绍图。

`docs/gallery/hero.png` 是 README 顶部主图；`editor-overview.png` 为完整编辑器视图；
`timeline.png` 只裁轨道/关键帧/3D 快捷面板。

## 5. 产物

`bbsae-0.17.0.jar`（`gradle.properties` 的 `mod_version` 已升级为 0.17.0）。
