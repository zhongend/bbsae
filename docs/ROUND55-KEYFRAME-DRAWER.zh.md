# Round 55 — 关键帧抽屉：打开必崩 + 打开不显示（0.99 hotfix）

> 用户报告两个症状：点「关键帧」后崩溃，以及抽屉打不开/显示不完整。堆栈直指
> `UICompositorPanel$PanelResizeHandle.drag` 的 `body.area` NPE。按第一性原理把两个症状
> 拆成三条独立的根因，全部在布局/状态层修复，而不是在按钮上贴提示。

## 1. 崩溃：dock 模式下 `body` 本来就是 null

```
java.lang.NullPointerException: Cannot read field "area" because "this.this$0.body" is null
  at UICompositorPanel$PanelResizeHandle.drag(UICompositorPanel.java:13455)
  at UICompositorPanel$PanelResizeHandle.render(UICompositorPanel.java:13434)
```

构造器在启用 BBS 停靠布局时**故意不建 legacy `body`**（`this.dock = new UIDockLayout()`，
`body` 保持 null，避免两套布局同时摆面板）。但第 4 个分隔条 `keyframeHandle` 是无条件创建的，
它属于时间轴面板，在 dock 模式下也存在：

```java
int width = UICompositorPanel.this.body.area.w;   // body == null → NPE
```

只要按住抽屉分隔条（或点到它旧的 3px 标题栏重叠区）就会在下一帧渲染时崩溃。

修复：

- 新增 `layoutBodyWidth()`：legacy 用 `body.area.w`，dock 用 `timeline.area.w`，最后兜底整面板宽度。
- 高度同样按布局引擎取：dock 用 `timeline.area.h`，legacy 用 `body` 高度/整面板高度。
- 模式 3（抽屉）的高度上限改用与 `layoutKeyframeDrawer()` 相同的 `keyframeDrawerMaxHeight()`，
  不再读取 dock 模式下无意义的 `timelinePanelHeight`。

## 2. 不显示：默认 dock 比例给不了抽屉的最小高度

960×540 UI（1920×1080、GUI scale 2，即用户日志里的环境）下，默认 dock 树是 72/28：

- 时间轴面板内容高度约 **125 px**；
- 抽屉硬性最小 96 px + 工具栏 32 px + 轨道区 + 5 px 分隔条 ≈ **189 px** 起。

旧 `layoutKeyframeDrawer()` 发现 `max < KEYFRAME_DRAWER_MIN` 时：

```java
this.keyframeOpen = false;
this.keyframeDrawerSlide = 0F;
this.setStatus("时间轴太矮，关键帧抽屉已收起…");
```

于是点「关键帧」按钮 → 抽屉被自己关掉；按钮仍显示“▸”，再点也是同样的结果；项目加载时
恢复的 `keyframeOpen` 同样被静默清掉。**这不是显示 bug，是布局没有满足控件的硬性最小高度。**

修复：让布局为抽屉让出空间，而不是拒绝显示。

- `layoutKeyframeDrawer()` 不再翻转 `keyframeOpen`；短布局下保持用户意图。
- 新增 `ensureKeyframeDrawerSpace()`：
  - dock 模式：调用 `CompDockSource.growPanelHeight()`，只改**时间轴路径上最外层的竖向
    splitter ratio**。祖先比例缩放整条分支，嵌套分割线也一起长高；同层兄弟面板保留 BBS
    的 80 px 最小值。
  - legacy 模式：抬高 `timelinePanelHeight`，仍受 `CompUi.fitTimelineHeight()` 的 62% 上限保护。
  - 通过 `layoutPass` / `drawerSpacePass` 防重入；拖动 dock 分隔条期间不抢布局；
  - 同一组几何只尝试一次，失败不会每帧重写 dock 树；
  - 成功后标记 `dockLayoutDirty`，由原有 250 ms 节流持久化。
- 轨道保留量从常量 56 px 改为**实测** `ruler 20 + 3 × rowHeight`。原 56 px 连“标尺 + 两条
  26 px 轨道”都不够，抽屉打开会盖住半条轨道；现在至少保留三条完整轨道，且跟随 Alt+滚轮的
  行高设置。
- 抽屉可见高度 = `min(preferredHeight, available)`，不再把持久化高度永久改成 96 px；
  空间不足时显示可用高度并只提示一次，窗口变高后自动恢复。

## 3. 分隔条抢夺标题栏点击 / Esc 恢复错值

- 分隔条原来放在 `y = -shownHeight - 2, h = 5`，与标题栏顶部 3 px（以及关闭按钮顶部）重叠。
  点标题栏会开始拖动，接着命中第 1 节的 NPE。现在整条分隔条位于抽屉上方
  （`y = -shownHeight - 5, h = 5`），标题栏和按钮完全可点。
- `cancelDrag()` 原来把 `startHeight`（模式 3 里其实是抽屉高度）写进 `timelinePanelHeight`，
  Esc 取消抽屉拖动会把时间轴压成 154 px。现在模式 3 单独保存 `startDrawerHeight`，只恢复
  抽屉高度并重新布局。

## 4. 验证

- 新增 `CompDockSourceTest` 5 例：默认树、时间轴在 first-child、嵌套横向/竖向分割、
  无竖向分割/已足够高、80 px 兄弟面板下限。
- 全量：**145 个测试套件 / 934 项测试，0 failures / 0 errors / 1 skipped**
  （0.99 基线 145/929，新增 5 项）。
- 真机 `runClientView -PbbsaeView=quick`（1920×1080 / GUI scale 2，即用户崩溃环境）：
  - 修复前：`dock children: ... timeline=952x125`，抽屉被强制关闭，按钮为“关键帧 ▸”。
  - 修复后：`timeline=952x289`，抽屉 154 px、上方三条完整轨道可见，
    截图 `docs/verify/round55-keyframe-drawer.png`。

构建产物：`bbsae-0.99-hotfix.jar`，**3,818,234 bytes**，SHA-256
`94DB2B78E718EA22E61037AA775A250BBF5816A2F7BD8B784B5C396291A9A12A`。已复制到
`1.20.1-BBS2.2/mods/`（原 `bbsae-0.99.jar` 备份在 `_backup/modpack-bbsae-0.99-*.jar`）。

## 5. 涉及文件

| 文件 | 改动 |
| --- | --- |
| `UICompositorPanel.java` | NPE 宽度/高度、抽屉最小空间自动增长、轨道保留量、分隔条几何、Esc 恢复 |
| `CompDockSource.java` | `growPanelHeight()`：按路径找最外层竖向 splitter 并重算 ratio |
| `CompDockSourceTest.java` | 5 个布局回归测试 |
