# 停靠式面板布局计划（BBS `UIDockLayout`，需求 8-B）

> **状态：0.93 已实现（默认启用）**。本节保留设计依据与实施记录；实现细节见文末「实施记录」。
>
> 结论先行：**BBS 2.7 自带完整的停靠布局系统** `mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout`
> （分栏拖拽、拖拽停靠、标签堆叠、隐藏面板、布局锁、布局撤销、最大化）。「面板像 BBS 原版一样拖拽组合」
> 不需要自研，直接把合成器的四个区域注册成 dock 面板即可；`UIFilmPanel` 已用这套系统（`UIFilmPanel:308-324`）。

## 1. 已核对的 API（jar `libs/bbs-2.7-1.20.1.jar`，javap 无缺口）

```java
UIDockLayout  // extends UIElement
  source(ILayoutSource) / locked(boolean) / frameless(String) / gate(Supplier<Boolean>)
  onChanged(Runnable) / onLayoutSettled(Runnable) / ensure(UnaryOperator<EditorLayoutNode>)
  addPanel(String id, UIElement panel, Icon icon, IKey label)
  mount() / refresh() / refreshVisibility() / toggleLock() / resetLayout()
  isPanelActive(String) / isAnySplitterDragging() / isLocked()
  getLayoutRoot() / applyLayoutRoot(EditorLayoutNode) / undoLayout()
  toggleMaximizeUnderCursor() / cycleDockStackTab(int) / setupFlex(boolean)
  fillHiddenPanelsMenu(ContextMenuManager)

ILayoutSource { EditorLayoutNode getRoot(); void setRoot(EditorLayoutNode);
                EditorLayoutNode getDefault(); Set<String> getHiddenPanels(); void setHiddenPanels(Set<String>); }

EditorLayoutNode
  SplitterNode(boolean horizontal, float ratio, first, second)
  PanelNode(String id) / StackNode(List<String> ids, String active)
  toData() / fromData(BaseType) / collectPanelIds(node, Set)
  copyWithInsertSplitAt(root, targetId, panelId, edge) / copyWithInsertStackAt(...)
  copyWithRemovedPanel(root, id) / copyWithStackActivePanel(root, panelId, activeId)

DataToString.toString(BaseType) / DataToString.fromString(String)   // 布局树持久化
```

## 2. 面板划分（与现有 UI 元素一一对应）

| dock id | 现有成员 | 图标 | 说明 |
| --- | --- | --- | --- |
| `effects` | `effectColumn`（效果库/素材/预设三页签） | 效果图标 | 左侧栏 |
| `preview` | `preview`（自绘预览、工具栏、摄像机按钮、钢笔叠加） | 摄像机 | 主画布 |
| `inspector` | `inspectorColumn`（效果控件/调色/图层/蒙版/文字/合成/输出） | 属性 | 右侧栏 |
| `timeline` | `timeline` + `trackTimeline` + `curveEditor` + `keyframeDrawer` + `quickPanel` | 时间轴 | 底部或侧边，内部绝对布局保持不变 |

顶部 `header`（播放/保存/设置…）与底部状态条**不进 dock**，仍由 `UICompositorPanel` 绝对布局。

## 3. 默认树（等价当前布局：左栏 | 预览+检查器 / 时间轴在底）

> BBS 语义：`SplitterNode(horizontal=true, …)` = 沿水平轴切 → 两个孩子**上下**排列（first 在上）；
> `false` = 左右排列。见 `UIDockLayout.collectLayout`（`_research/bbs-fs-1.20.1/.../UIDockLayout.java`）。

```java
new SplitterNode(true, 0.72F,                                    // 上下：上=编辑区，下=时间轴
    new SplitterNode(false, 0.13F,                               // 左右：左栏 | 主区
        new PanelNode("effects"),
        new SplitterNode(false, 0.80F,                           // 左右：预览 | 检查器
            new PanelNode("preview"),
            new PanelNode("inspector"))),
    new PanelNode("timeline"));
```

用户拖拽后保存到工程；旧工程没有布局字段 → 用默认树（`ensure()` 把后来新增的面板补进树）。

## 4. 持久化

`CompProject.CompSettings` 新增（Gson 自动读写，旧工程缺省 = 默认树）：

```java
/** UIDockLayout 的树（DataToString 序列化的字符串）；空 = 默认布局。 */
public String dockLayout = "";
/** 用户隐藏的面板 id，逗号分隔。 */
public String dockHiddenPanels = "";
/** 布局是否解锁（可拖拽）。BBS 原版默认锁住，用标题栏锁按钮解锁。 */
public boolean dockUnlocked = false;
```

新增 `client/ui/CompDockSource.java implements ILayoutSource`：
- `getRoot()` 解析 `dockLayout`（失败 → `getDefault()`）；
- `setRoot()` 写回字符串 + `onChange.run()`（面板做 debounce save，布局拖动中不每帧写盘）；
- `getHiddenPanels()/setHiddenPanels()` 解析逗号分隔；
- `defaultLayout()` 返回 §3 的树。

## 5. `UICompositorPanel` 改造步骤（尽量小）

1. 构造函数：不再把 `effectColumn/preview/inspectorColumn/timeline` 直接 `add`/`relative` 到 `this`；
   创建 `dock = new UIDockLayout().source(new CompDockSource(settings, CompDockSource::defaultLayout))`，
   `addPanel(...)` ×4，`mount()`，`dock.relative(this).x(0).y(24).w(1F).h(1F,-24-18)`；
   `this.add(header, dock, status)`。
2. `applyLayout()` 改为：
   - 计算 header/status 高度（`layoutHeader()` 保留）；
   - `dock.relative(...)`；
   - `timeline` 内部布局（`layoutKeyframeDrawer()`、quickPanel 宽度）保留；
   - 删除 `leftHandle/rightHandle/timelineHandle` 的定位（dock 自带 splitter；类可保留但不再实例化）。
3. 保留的公开成员：`preview / effectColumn / inspectorColumn / timeline / trackTimeline /
   keyframeDrawer / curveEditor / quickPanel / applyLayout() / layoutKeyframeDrawer() / resize()`。
4. 标题栏：
   - 布局锁按钮（`Icons.LOCKED/UNLOCKED`）→ `dock.toggleLock()`，写 `settings.dockUnlocked`；
   - 重置布局按钮 → `dock.resetLayout()`（设置页也可加）；
   - 隐藏面板菜单：右键标题栏 → `dock.fillHiddenPanelsMenu(menu)`（BBS 原版入口）。
5. 快捷键（可选，照抄 BBS）：`Ctrl+Tab` 切堆叠标签（`cycleDockStackTab(1)`）、`Ctrl+Shift+Z` 布局撤销、
   `Ctrl+M` 最大化悬停面板（`toggleMaximizeUnderCursor`）——先确认与合成器现有快捷键无冲突。
6. `focusButton`（专注模式）改为写 hiddenPanels：把 `effects/inspector` 隐掉 + `dock.refresh()`；
   恢复时还原。旧工程不认识 hidden 字段 → 空集。
7. 动画（需求 8-C）：面板切换/停靠后加 200–300ms 的 `UiMotion` 淡入+位移；`UIDockLayout` 自带
   tab 切换，不给 dock 内部动画打补丁，只做「面板内容出现时」的入场。

## 6. 风险与验证

- `UIDockLayout.addPanel` 会把 panel 变成 dock 的直接子元素；现有代码若仍对 panel 调 `relative(this)`
  会打架 —— 改造时全局搜 `effectColumn.relative / preview.relative / inspectorColumn.relative /
  timeline.relative` 并逐处迁移。
- 预览的 scissor/GL 状态与 dock 无冲突（预览自绘 FBO），但 `preview` 面板尺寸变化要触发
  `syncViewportSize()`（`resize()` 已在做）。
- 拖拽停靠的输入由 `UIDockLayout` 轮询窗口；预览里的右键/中键手势（AE 摄像机工具）在面板内容区，
  dock 的拖拽从标题条/标签开始，不冲突；但**解锁状态下每个面板顶部 20px 是拖拽条**，预览工具栏需下移
  （`dragStripHeightPx()`）。
- 回归测试（无头）：`CompDockSource` 的序列化/默认树/隐藏面板解析；`defaultLayout()` 收集到的 panel id
  必须恰好是四个已知 id（防手滑写错字符串）。
- 真机验证清单：拖动左栏到预览右侧成标签堆叠；拖动时间轴到顶部；分栏拖拽；锁定后不可拖；
  隐藏/恢复面板；重置布局；旧工程打开用默认布局；重启后布局保留。

---

## 实施记录（0.93）

**开关**：`CompPrefs.Data.dockLayout`（默认 true；设置 → 开发者 →「停靠式面板（BBS 原版）」，
切换后重开合成器面板生效）。关闭时回退到旧版固定三栏 splitter（原 `applyLayout` 完整保留为
`applyLegacyLayout()`）。

**文件**：

| 文件 | 作用 |
| --- | --- |
| `client/ui/CompDockSource.java` | `ILayoutSource` → `CompProject.CompSettings.dockLayout/dockHiddenPanels/dockUnlocked`；批量 flush（拖 splitter 不每帧写盘）；默认/聚焦/四套预设树；项目切换自动重载 |
| `client/ui/layout/CompositorLayout.java` | 旧版固定布局的纯函数几何（0.92 已加）；dock 模式下作为像素最小宽参考（BBS dock 用比例） |
| `UICompositorPanel` | 构造：注册 4 个面板 → `mount()`；`applyDockLayout()` 只摆 header/status/render 页；`resize()` override 里 `dock.setupFlex(true)`；`syncDockDerivedLayout()` 摆预览栏/快捷面板/关键帧抽屉；布局按钮（左键锁/解锁，右键菜单：重置/撤销/最大化/隐藏面板）；Ctrl+Alt+Z 布局撤销、Ctrl+空格 最大化；焦点模式用临时树（preview+timeline）；工作区预设映射为 dock 树 |
| `CompUi` | 顶栏右侧集群新增「布局」按钮（始终可见），`headerLayout()` 几何 + 单测防重叠 |

**与旧布局的差异（如实说明）**：
1. dock 是**比例分栏**，不是像素分栏：窗口变窄时属性栏会比旧的 296px 下限更窄（BBS 原版同款行为）。
2. 快速面板（快捷变换）保留在时间轴面板**内部**右侧，不单独注册为 dock 面板；它随选择显示/隐藏，
   拖动时间轴时跟着走。若用户需要它独立停靠，后续可注册第 5 个面板。
3. 锁定时拖动标题条无效（BBS 原版默认锁定）；解锁后每个面板顶部 20px 是拖拽条，BBS 会自动把
   面板内容下移，不需要宿主处理。
4. 布局改动不再进入文档 undo（`saveUiState()` 只触发防抖写盘，不置 `documentDirty`、不清预渲染缓存）。
