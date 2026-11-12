# UI 面板与动效改造计划（用户需求 8）

> 范围：只做调研与方案，**没有修改 `src/`**。本文是给 Lead / 实现者的可落地计划。
> 基准：2026-11-12 22:5x 的工作区。调研期间工作区仍在被并行修改（`UICompositorPanel.java` 从约 19054 行涨到 19332 行），行号只作定位起点；合并时以方法名/锚点文本为准。
>
> **先看结论**：
> 1. BBS 2.7 **确实有**完整的面板拖拽组合系统 `UIDockLayout`，不是只有 splitter；1.20.1 jar 里有这个类，`UIFilmPanel` 的 `public UIDockLayout dock` 就是证据。
> 2. 最可靠的“100% 跟原版一样”不是仿写，而是**直接复用 `UIDockLayout`**；它已经在 jar 里、是 public、`EditorLayoutNode` 也是 public。
> 3. 但 BBS 的 dock 用**比例**分栏，没有 bbsae 这种内容级最小宽度（右侧属性栏 296px）；直接套用会在 1280 宽窗口把属性栏压到约 230–250px（低于 296），所以要用**断点堆叠树**补上 bbsae 的最小宽度。
> 4. “轨道左侧面板拖拽数据时面板大小跟着变”的根因是：**时间轴高度的 4px 拖拽把手横跨整个 body，盖住了左列底部最后 4px**；BBS 事件派发从后往前（后添加的子元素优先），把手先吃掉按下，轨道行根本没收到事件。工作区已有一版未提交的半修复，本计划把它收编成结构化布局 + 回归测试。
> 5. 当前工作区有一个**不属于本任务**的编译错误（`CompPathGeometry.java:308-309`，钢笔工具并行修改），导致测试跑不起来；测试命令和预期见第 9.3 节，等它修好后必须重跑。
>
> **并行工作提示**：工作区里已经有一份 `docs/DOCK-LAYOUT-PLAN.zh.md` 和 `src/main/java/dev/bbsae/client/ui/CompDockSource.java`（B 的初版，已把 `CompSettings.dockLayout/dockHiddenPanels/dockUnlocked` 加好）。本文以它们为 B 的实施基线，**不重复实现**；本文补充的是 A/C、命中 bug、持久化/undo 分离、断点与“比例 vs 像素”取舍。两文冲突处以本文第 5.2/5.4/5.7 的修正为准。

---

## 0. 一句话路线

| 层 | 目标 | 推荐实现 | 预估 |
| --- | --- | --- | --- |
| A | 面板随窗口/界面尺寸自适应 | 抽出纯函数 `CompositorLayout`：请求尺寸 vs 生效尺寸分离、最小尺寸、短窗口不重叠、断点 | 1.5–2 天 |
| B | 所有面板可拖拽组合，操作对齐原版 | 直接复用 `UIDockLayout` + `EditorLayoutNode`；bbsae 侧只做面板注册、布局持久化、断点预设、锁定/重置/迁移 | 3–4 天 |
| C | 非线性动效（页面/面板/列表/反馈） | 扩展 `UiMotion`（spring/tween/prune）+ `UiPageTransition` + `UiLayoutTween` + 列表错峰；全部受 `CompPrefs.animations` 控制 | 2–3 天 |

依赖关系：**A 先做**（B 的断点树需要 A 的尺寸语义）；C 可在 A 之后并行。总工期约 7–9 个开发日，不含 Lead 真机验收。

---

## 1. 调研方法与证据

### 1.1 版本核对

- 目标 jar：`libs/bbs-2.7-1.20.1.jar`。
- 源码：`_research/bbs-fs-1.20.1/`（与 jar 对应）、`_research/bbs-fs/`（仓库里标注的 2.7 / 1.20.4 分支）。
- 已用 `javap` 核对以下类在 1.20.1 jar 中存在且方法匹配：
  - `mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout`
  - `...UIDockLayout$UIDockSlot` / `$DockStackInfo` / `$SplitterHandleInfo` / `$LayoutPass` / `$LayoutSnapshot` / `$UIDockStackTabs`
  - `mchorse.bbs_mod.ui.framework.elements.layout.ILayoutSource`
  - `mchorse.bbs_mod.settings.values.ui.EditorLayoutNode` 及其 `$PanelNode` / `$SplitterNode` / `$StackNode`
  - `mchorse.bbs_mod.ui.film.UIFilmPanel`（含 `public UIDockLayout dock`）
  - `mchorse.bbs_mod.ui.framework.elements.utils.UISplitter`
  - `mchorse.bbs_mod.BBSSettings`（含 `editorResizablePanels`、`editorLayoutSettings`）
- 对比结果：`UIDockLayout.java`、`UIFilmPanel.java`、`EditorLayoutNode.java` 在 `_research/bbs-fs` 与 `_research/bbs-fs-1.20.1` 中 **SHA-256 完全相同**。所以“2.7/1.20.4 源码与 1.20.1 jar 不一致”的担心，至少对这三个文件不成立。

### 1.2 验证命令

```powershell
_tools\jdk21\jdk21.0.12_12\bin\javap.exe -classpath libs\bbs-2.7-1.20.1.jar `
  mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout
_tools\jdk21\jdk21.0.12_12\bin\javap.exe -classpath libs\bbs-2.7-1.20.1.jar `
  mchorse.bbs_mod.ui.film.UIFilmPanel
_tools\jdk21\jdk21.0.12_12\bin\javap.exe -classpath libs\bbs-2.7-1.20.1.jar `
  mchorse.bbs_mod.settings.values.ui.EditorLayoutNode
```

### 1.3 本次测试尝试

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
. .\_tools\gradle-env.ps1
.\gradlew.bat test --offline --console=plain --tests "dev.bbsae.client.ui.CompUiResponsiveTest"
```

结果：**编译阶段失败**，不是测试失败：

```text
src/main/java/dev/bbsae/comp/CompPathGeometry.java:308: 错误: 无法将类 CompPathGeometry中的方法 distance应用到给定类型
src/main/java/dev/bbsae/comp/CompPathGeometry.java:309: 错误: ...
```

`CompPathGeometry.java` 是工作区里另一个并行任务（钢笔工具）的未跟踪文件；本任务没有改它。等它修好后，A 层的 `CompUiResponsiveTest` 应能直接跑。**在它修好前不要用“测试全绿”作为本计划的验收结论。**

---

## 2. BBS 原版面板系统（事实核查）

### 2.1 结构总览

```
mchorse/bbs_mod/ui/
├── framework/
│   ├── UIScreen / UIContext / UIRenderingContext
│   └── elements/
│       ├── UIElement              ← 保留树 + Flex + 事件派发
│       ├── UIPanelBase            ← 标签页容器（按钮条 + 面板）
│       ├── UIScrollView / UISection
│       ├── layout/
│       │   ├── ILayoutSource
│       │   └── UIDockLayout       ← 可拖拽组合的核心
│       └── utils/
│           ├── UIDraggable
│           └── UISplitter         ← 单缝 splitter
├── dashboard/
│   ├── UIDashboard
│   └── panels/
│       ├── UIDashboardPanel
│       ├── UIDashboardPanels      ← 任务栏 + 面板占满剩余区域
│       └── ...
└── film/
    ├── UIFilmPanel                ← 用 UIDockLayout 的影片编辑器
    └── UIFilmPreview              ← 按预览区做 aspect-fit
```

### 2.2 `UIDockLayout`：原版真的有“面板拖拽组合”

源码：`_research/bbs-fs-1.20.1/src/client/java/mchorse/bbs_mod/ui/framework/elements/layout/UIDockLayout.java`（2094 行，以下简写 `UIDockLayout.java`）。

**它是什么**：一个可复用的树形停靠布局。面板按 `EditorLayoutNode` 树排布（splitter / panel / stack），支持：

- 拖动面板标题条或 stack 标签到另一个面板的边/中心；
- 边=拆分，中心=并入同一个 tab stack；
- Shift+中心=两个面板交换；
- 拖到 dock 外缘=相对整个布局拆出一列/一行；
- 拖动标签到别的 stack 的标签上，悬停 500ms 自动展开（spring-load）；
- 拖动中 Esc 取消；
- 双击缝隙 50/50；
- Ctrl+空格 最大化鼠标下面板；右键菜单隐藏面板/恢复隐藏面板；
- Ctrl+Alt+Z 撤销布局；锁定/解锁布局；重置布局；
- 面板可“frameless”（预览这种不要边框/阴影/沟槽）。

关键常量和交互（`UIDockLayout.java`）：

| 常量/字段 | 值 | 行 | 含义 |
| --- | --- | --- | --- |
| `DRAG_STRIP_HEIGHT_PX` | 20 | 50 | 解锁后每个面板顶部拖拽条高度 |
| `SPLITTER_HANDLE_PX` | 14 | 51 | 缝隙命中宽度（视觉线 1px） |
| `DROP_EDITOR_EDGE_PX` | 16 | 56 | dock 外缘 band，拖到这里=相对整个布局拆分 |
| `DROP_PANEL_EDGE_PX` | 48 | 58 | 面板边缘 band；不超过面板 25% |
| `DROP_ROOT_RATIO` | 0.25 | 62 | 相对整个布局拆出的侧栏比例 |
| `DOCK_STACK_TABS_HEIGHT_PX` | 20 | 67 | stack 标签条高度 |
| `MIN_PANEL_SIZE_PX` | 80 | 69 | 拖动缝隙时两侧的最小像素 |
| `DRAG_START_THRESHOLD_PX` | 4 | 71 | 标签从点击变拖拽的阈值 |
| `SPRING_LOAD_DELAY_MS` | 500 | 73 | 悬停标签自动展开延迟 |
| `SPLITTER_DOUBLE_CLICK_MS` | 300 | 76 | 双击缝隙复位窗口 |
| `PANEL_GAP_PX` | 4 | 77 | 面板之间沟槽 |
| `SPLITTER_HANDLE_THICKNESS_NORM` | 0.02 | 80 | 缝隙命中厚度（比例） |

关键方法（签名与行号）：

```java
public UIDockLayout source(ILayoutSource source)                    // :135
public UIDockLayout locked(boolean locked)                          // :143
public UIDockLayout frameless(String panelId)                       // :151
public UIDockLayout gate(Supplier<Boolean> gate)                    // :158
public UIDockLayout onChanged(Runnable onChanged)                   // :166
public UIDockLayout onLayoutSettled(Runnable onLayoutSettled)       // :178
public UIDockLayout ensure(UnaryOperator<EditorLayoutNode> ensureFn)// :190
public UIDockLayout addPanel(String id, UIElement panel, Icon icon, IKey label) // :201
public void mount()                                                 // :211
public static int dragStripHeightPx()                               // :228
public UIElement getPanel(String id)                                // :233
public boolean isLocked()                                           // :240
public boolean isPanelActive(String panelId)                        // :245
public boolean isAnySplitterDragging()                              // :252
public void refreshVisibility()                                     // :266
public void refresh()                                               // :296
public void toggleLock()                                            // :305
public void resetLayout()                                           // :313
public EditorLayoutNode getLayoutRoot()                             // :325
public void applyLayoutRoot(EditorLayoutNode root)                  // :330
public boolean undoLayout()                                         // :342
public boolean toggleMaximizeUnderCursor()                          // :362
public boolean cycleDockStackTab(int offset)                        // :411
public void setupFlex(boolean resize)                               // :586
public void fillHiddenPanelsMenu(ContextMenuManager menu)           // :1581
```

布局计算在 `collectLayout(...)`（`:681-727`）里递归：splitter 按 `ratio` 切成两块，并给每条缝生成 `SplitterHandleInfo`； `setupFlex(true)`（`:586-645`）会把 slot 的 `relative(this).x/y/w/h` 重新设置，然后 `resize()`。

拖放核心：

| 方法 | 行 | 作用 |
| --- | --- | --- |
| `createPanelDragHandle(String)` | 1257 | 20px 拖拽条 + 右键菜单 |
| `updateDropTarget(int,int)` | 1281 | 目标优先级：tab → dock 外缘 → 面板边带 → 中心 |
| `applyPanelDropResult(...)` | 1226 | 中心=stack；Shift+中心=swap；边=split；root 边=root split |
| `finishPanelDrag()` | 1415 | 松手提交并清理 |
| `onTabPressed` / `onTabsReleased` / `updateTabDrag` | 1441 / 1449 / 1486 | 标签区分点击/拖拽 |
| `beginSplitterDrag` / `applySplitterDrag` | 864 / 913 | 缝隙拖动，交叉缝隙联动 |
| `consumeSplitterDoubleClick` | 833 | 双击 50/50 |
| `createSplitterHandle` | 784 | 缝隙的 `UIDraggable`，受 `BBSSettings.editorResizablePanels` 控制 |

**重要事实：**

- 拖拽不是“自由浮动窗口”。它只能把面板放进树里的某个 split/stack，和 VS Code / BBS 自己的影片编辑器一样。
- 默认 `layoutLocked = true`（`:102`），要用户点锁图标解锁才会出现拖拽条。这和 BBS 影片编辑器一致：默认锁定，防误拖。
- 缝隙拖动只在 `BBSSettings.editorResizablePanels.get()` 为 true 时可用（`:792, 811, 866, 1000`）。如果用户关了 BBS 的“可调整面板”，bbsae 复用后缝隙也会失效；这是复用的代价，需要告知用户或做 fallback。

### 2.3 `UISplitter`：单缝 splitter（不是 dock）

源码：`_research/bbs-fs-1.20.1/src/client/java/mchorse/bbs_mod/ui/framework/elements/utils/UISplitter.java`（243 行）。

```java
public static UISplitter fraction(String key, float defaultValue, float min, float max) // :48
public static UISplitter pixels(String key, int defaultValue, int min, int max)         // :54
public UISplitter(String key, boolean fraction, float defaultValue)                    // :59
public UISplitter range(float min, float max)                                         // :78
public UISplitter range(float min, Supplier<Float> max)                               // :92
public UISplitter measure(UIElement origin, UIElement extent)                         // :102
public UISplitter fromEnd()                                                           // :122
public UISplitter vertical()                                                          // :130
public UISplitter onChange(Runnable onChange)                                         // :139
public int getPixels()                                                                // :153
public void reset()                                                                   // :165
```

特点：6×40 的 `UIDraggable`、支持 fraction/pixels、min/max、双击复位、把尺寸存到 `BBSSettings.editorLayoutSettings`（`:180-183`）。它只负责一条缝，不负责面板重组。

BBS 里 `UISplitter` 的使用者：模型编辑器左右栏、UV 栏、纹理浏览器、表单编辑器等（`UIModelEditorPanel.java:156-164`、`UITextureBrowser.java:72-73`、`UIFormEditor.java:244`）。**影片编辑器的面板组合用的是 `UIDockLayout`，不是这些 splitter。**

### 2.4 原版如何做“不同界面大小自适应”

不是“自动响应式断点”，而是四件事：

1. **面板永远占满剩余区域**  
   `UIDashboardPanels.setPanelPlacement`（`UIDashboardPanels.java:282-293`）用 `relative(this).w(1F).h(1F, -BAR)`，按任务栏停靠边（上/下/左/右）扣除 20px。`UIDashboard.setPanel` 会 `panel.resize()`（`UIDashboard.java:248-270`）。

2. **dock 树用比例，窗口变化递归缩放**  
   `UIDockLayout` 的 slot 用 `relative(this)` + 归一化 `x/y/w/h`（`applyPanelBoundsFromStacks`, `:1102-1132`），父容器 resize 时子元素自动按比例重排；不需要再跑一次 `setupFlex`。缝隙把手用“比例 + 像素偏移”保持固定命中宽度（`:758-774` 注释）。

3. **预览按 aspect-fit 自适应，而不是拉伸**  
   `UIFilmPreview.getViewport()`（`_research/bbs-fs-1.20.1/.../UIFilmPreview.java:351-371`）用 `Vectors.resize(width/height, area.w, area.h)` 算 black-bar 区域，再把 BBS 自定义渲染尺寸设为预览区尺寸（`UIFilmPanel.applyPreviewSizeToBBS`, `:858-905`）。`UIFilmPanel.resize()`（`:770-780`）在非导出、非缝隙拖动时重新应用；缝隙拖动中跳过（`dock.isAnySplitterDragging()`），避免拖拽时每帧重设 BBS 自定义尺寸。

4. **面板内容自己滚动/压缩**  
   `UIScrollView`、`UIList`、`UIPanelBase` 的标签条固定 20px、内容拿剩余空间（`UIPanelBase.java:64-123`）。原版没有“属性栏最小 296px”这种内容约束；它靠滚动和用户拖拽解决。

**结论**：原版的“自适应”= 比例 dock + letterbox 预览 + 滚动内容。bbsae 现在做的是“像素分栏 + 手写 `CompUi` 响应式”，更贴近 AE 的属性栏，但和原版 dock 不是一套。方案 B 要在这两者之间做桥接。

### 2.5 影片编辑器 `UIFilmPanel` 的实际用法

源码：`_research/bbs-fs-1.20.1/src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanel.java`（2103 行）。

面板 ID（`:157-161`）：

```java
PANEL_MAIN_ID         = "main";       // 时间轴 / 关键帧
PANEL_PREVIEW_ID      = "preview";    // 3D 世界预览（frameless）
PANEL_EDIT_AREA_ID    = "editArea";   // 属性面板
PANEL_REPLAYS_LIST_ID = "replaysList";
PANEL_REPLAY_PROPS_ID = "replayProps";
```

默认树 `EditorLayoutNode.defaultFilmLayout()`（`_research/bbs-fs-1.20.1/src/main/java/mchorse/bbs_mod/settings/values/ui/EditorLayoutNode.java:130-157`）本身就是一个“左列 / 中间 / 右列”的嵌套 split：

```java
new SplitterNode(false, 0.7916667F,
    new SplitterNode(false, 0.25294888F,
        new SplitterNode(true, 0.28479657F,
            new PanelNode("replayProps"),
            new PanelNode("replaysList")),
        new SplitterNode(true, 0.6167024F,
            new PanelNode("preview"),
            new PanelNode("main"))),
    new PanelNode("editArea"));
```

- 外层 `false`（按宽度）把“编辑区（左+中）”与 `editArea`（右侧属性）分开；
- 左中再按宽度分成 `replayProps/replaysList` 与 `preview/main`；
- 左侧上下是属性/回放列表，中间上下是预览/时间轴；
- `frameless("preview")` 让预览没有面板边框/沟槽；
- `UIFilmPanel.resize()` 再把预览区尺寸写给 BBS 的自定义渲染尺寸（letterbox，见 2.4）。

构造（`:309-324`）：

```java
this.dock = new UIDockLayout();
this.dock.relative(this.editor).w(1F).h(1F);
this.dock.source(this.createLayoutSource())
    .locked(!BBSSettings.editorLayoutSettings.isDockUnlocked(ValueEditorLayout.FILM))
    .frameless(PANEL_PREVIEW_ID)
    .gate(this::hasFilmInCurrentTab)
    .ensure(this::ensureFilmLayoutPanels)
    .onChanged(this::onDockLayoutChanged)
    .onLayoutSettled(this::applyPreviewSizeToBBS);
this.dock.addPanel(PANEL_EDIT_AREA_ID, this.editArea, Icons.EDITOR, UIKeys.FILM_PANELS_EDIT_AREA);
this.dock.addPanel(PANEL_MAIN_ID, this.main, Icons.FILM, UIKeys.FILM_PANELS_MAIN);
this.dock.addPanel(PANEL_PREVIEW_ID, this.preview, Icons.VIDEO_CAMERA, UIKeys.FILM_PANELS_PREVIEW);
this.dock.addPanel(PANEL_REPLAYS_LIST_ID, this.replayEditor.replaysList, Icons.LIST, UIKeys.FILM_PANELS_REPLAYS_LIST);
this.dock.addPanel(PANEL_REPLAY_PROPS_ID, this.replayEditor.replayProperties, Icons.PROPERTIES, UIKeys.FILM_PANELS_REPLAY_PROPS);
this.dock.mount();
this.editor.add(this.dock);
```

其他关键点：

- 布局源 `createLayoutSource()`（`:525-559`）返回 `ILayoutSource`：`getRoot/setRoot` 按当前编辑器（camera/replay）读 `ValueEditorLayout`；`getDefault` 返回 `EditorLayoutNode.defaultFilmLayout()`；`getHiddenPanels/setHiddenPanels` 也走 `ValueEditorLayout`。
- 每个编辑器可“绑定”自己的布局（`FILM_CAMERA` / `FILM_REPLAY`，`:481-519`），否则共享 `FILM`。
- 锁定状态存 `BBSSettings.editorLayoutSettings.setDockUnlocked(ValueEditorLayout.FILM, ...)`（`:466`）。
- 布局预设可以从外部数据应用（`:561-587`），`resetLayout` 在 `:588` 附近。
- 快捷键：`Ctrl+Tab`/`Ctrl+Shift+Tab` 循环 stack 标签、`Ctrl+空格` 最大化鼠标下方面板、`Ctrl+Alt+Z` 撤销布局（`:279-306`）。
- `updateMainEditorVisibility`（`:437-456`）用 `dock.isPanelActive(...)` 决定 camera/replay 内部哪个时间轴/属性可见；`onDockLayoutChanged`（`:426-430`）在每次 dock 布局后同步。
- `resize()`（`:770-780`）只做三件事：`super.resize()`、`updateTabVisibility()`、非导出/非缝隙拖动时 `applyPreviewSizeToBBS()`。
- `applyPreviewSizeToBBS()`（`:858-905`）根据 `editorPreviewSizeMode`（export/custom/window）和预览区尺寸设置 `BBSRendering` 的视频尺寸；奇偶取整；只有变化才应用。

**可直接复用到 bbsae 的部分**：`UIDockLayout` + `EditorLayoutNode` + `ILayoutSource` + `UISplitter`（用于关键帧抽屉这种子区域）+ 锁/撤销/最大化/隐藏菜单的交互语义。

**不能直接复用的部分**：BBS 的 `ValueEditorLayout` 是 BBS 全局设置，不适合 bbsae 的“每个工程一套布局”；`UIFilmPanel` 的 `applyPreviewSizeToBBS` 是 BBS 世界渲染专用，bbsae 预览是自己的 FBO，不需要也不应该碰 `BBSRendering.setCustomSize`。

### 2.6 1.20.1 与 2.7/1.20.4 源码的差异

对本次调研相关的三个文件：**无差异**。

| 文件 | `_research/bbs-fs` vs `_research/bbs-fs-1.20.1` | 1.20.1 jar 核对 |
| --- | --- | --- |
| `UIDockLayout.java` | SHA-256 相同 | `javap` 方法/嵌套类完全匹配 |
| `UIFilmPanel.java` | SHA-256 相同 | `public UIDockLayout dock` 存在 |
| `EditorLayoutNode.java` | SHA-256 相同 | `defaultFilmLayout` / `fromData` / `copyWith...` 存在 |

因此，`_research/bbs-fs-1.20.1` 可以直接当作 1.20.1 的源码参考；不需要为了 dock 去猜 jar 行为。真正需要小心的是 **BBS 版本升级** 后这些 API 是否还在，见风险 5.8。

### 2.7 “原版是否真的有面板拖拽组合”的明确回答

**有。** 不是“只有分栏 resizer”：

- `UIDockLayout` 是完整实现，不是占位类；有 `UIDockSlot`、`UIDockStackTabs`、`SplitterHandleInfo`、`DockStackInfo`、`LayoutSnapshot` 等内部类，以及完整的 drop target / 拖拽状态字段。jar 里**没有** `UISplitView` / `UIResizer` 这类命名；BBS 的停靠就是 `UIDockLayout`，单缝是 `UISplitter`。
- `UIFilmPanel` 的 5 个面板就是用它注册的。
- `UIParticleSchemePanel.java:109-127` 也用它（`new UIDockLayout()` + `addPanel`）。
- 但它是**树形停靠**，没有自由浮动窗口、没有任意像素位置悬浮；交互边界是 splitter/stack/edge drop。

所以对用户的承诺应该是：“像 BBS 原版一样可拖拽拆分/堆叠/停靠”，而不是“任意浮动窗口”。如果用户要的是后者，那是另一个产品需求，不应伪装成原版已有。

### 2.8 可直接复用到 bbsae dashboard panel 的机制

| 机制 | 复用方式 | 注意事项 |
| --- | --- | --- |
| `UIDockLayout` | 直接 `new UIDockLayout()`，`addPanel` 注册 4 个 bbsae 面板 | 用 `ILayoutSource` 提供 bbsae 自己的树；不要用 BBS `ValueEditorLayout` 全局存储 |
| `EditorLayoutNode` | `copyWithInsertSplitAt/AtRoot/StackAt/SwappedPanels/RemovedPanel/collectPanelIds` 做树操作 | 只操作树，不碰 UI；可纯单元测试 |
| `ILayoutSource` | bbsae 实现 `getRoot/setRoot/getDefault/getHiddenPanels/setHiddenPanels` | `setRoot` 每帧会被调用，必须延迟写盘 |
| `UISplitter` | 关键帧抽屉高度、quickPanel 宽度等单缝 | 不是 dock；不要拿它替代面板组合 |
| `UIDashboardPanels.setPanelPlacement` | bbsae 已经通过 `UIDashboardPanel` 获得“占满任务栏外”的 area | 不需要自己算窗口原点 |
| `UIFilmPreview` 的 letterbox 思路 | bbsae 预览已经自己 FBO；只需按 preview.area 做 aspect-fit 绘制/安全框 | **不要**复用 `applyPreviewSizeToBBS`（BBS 世界渲染专用） |
| BBS 的锁/最大化/撤销/隐藏菜单 | 直接调用 `toggleLock/toggleMaximizeUnderCursor/undoLayout/fillHiddenPanelsMenu` | 需要 bbsae 自己的锁图标与快捷键入口 |

---

## 3. bbsae 现状盘点

### 3.1 当前面板树

注册点：`BbsAeClient.onDashboardPanels`（`src/main/java/dev/bbsae/client/BbsAeClient.java:605-616`）在 `RegisterDashboardPanelsEvent` 里 `new UICompositorPanel(event.dashboard)`，再 `event.dashboard.getPanels().registerPanel(...)`；之后 `dashboard.setPanel(compositorPanel)` 时由 `UIDashboardPanels.setPanelPlacement` 给面板一个“占满任务栏之外”的 area（`UIDashboardPanels.java:282-293`）。所以 bbsae 不需要自己算窗口原点，只需要算面板内部布局。

`UICompositorPanel` 继承 `UIDashboardPanel`，是独立 dashboard panel（不是 overlay）。当前布局是**绝对定位 + 4 个手写把手**：

```
UICompositorPanel
├── header (y=0,h=24)                          UICompositorPanel.java:392-409
├── body (y=24, h=bodyHeight)                  :5288-5295
│   ├── effectColumn (left)                    :415-474
│   ├── preview (center)                        :476...
│   ├── inspectorColumn (right)                 :5030...
│   ├── leftHandle   (4px, 全 body 高)          :5291-5294
│   ├── rightHandle  (4px, 全 body 高)          :5291-5294
│   └── timelineHandle (4px, 原全宽 → 现 preview 宽) :11626
├── renderPanel (y=24, h=bodyHeight)            :5166...
├── timeline (y=1F-(tlh+18), h=tlh)             :5299-5302
│   ├── timelineHeader (26)                     :5319...
│   ├── trackTimeline / curveEditor             :5309 / :5315
│   ├── keyframeDrawer                          :...
│   └── keyframeHandle (5px)                    :5417
├── quickPanel (right of timeline)              :5304-5307
├── status (bottom, 16)                         :5162...
├── modalLayer / modalBox                       :5426 / :5450
├── startScreen                                 :5478
├── onboarding                                  :5482
└── settingsOverlay                             :5486
```

### 3.2 `applyLayout()` 做了什么

方法：`UICompositorPanel.applyLayout()`，当前 `:11559-11632`。

步骤：

1. `layoutHeader()`（`:11717`）—— 根据宽度隐藏/移动顶栏按钮。
2. `updateFilterButton()`。
3. 读 `left/right` 可见性，取 `leftPanelWidth/rightPanelWidth`。
4. `timelinePanelHeight = CompUi.fitTimelineHeight(area.h, timelinePanelHeight)`（`:11580`）。
5. `maxSide = CompUi.maxSideWidth(area.w)`；`leftPanelWidth = min(leftPanelWidth, maxSide)`、`rightPanelWidth = min(...)`（`:11581-11584`）。
6. `CompUi.fitColumns(area.w, lw, rw, gaps, 120)` 得最终列宽（`:11586-11589`）。
7. 把 `effectColumn/preview/inspectorColumn` 按绝对 `relative(body)` 放置（`:11591-11593`）。
8. `layoutPreviewBar(...)`（`:11595`）。
9. `bodyHeight = CompUi.bodyHeight(area.h, timelinePanelHeight)`；设置 body、timeline、quickPanel、renderPanel（`:11597-11611`）。
10. 设置 split handle（`:11613-11628`）；当前工作区已把 `timelineHandle` 从全宽改为 preview 宽（见 3.6）。
11. `layoutKeyframeDrawer()`；`resize()`（`:11630-11631`）。

**顺带发现（和 A/B 都有关）**：`savePanelLayout()` 末尾调 `this.save()`（当前工作区 `:11936-11951`），而 `save()` 会 `documentDirty = true`（`:8366-8374`）。1 秒采样器 `captureSnapshot()`（`:17608`）会在下一帧把整份 `CompProject`（**包括面板宽度和 B 的 dockLayout**）推进文档 undo。也就是说，现在拖一次 splitter，Ctrl+Z 可能“撤销面板尺寸”，B 层后更会把布局树混进内容撤销。

A/B 落地时应把持久化拆成两条：

```java
void saveDocument()   // 现有 save()：documentDirty=true + 快照 + 防抖写盘
void saveUiState()    // 只 saveRequested=true + 防抖写盘，不 documentDirty、不清 redo、不 markDirty
```

`savePanelLayout()` / dock 布局 settled 调 `saveUiState()`；测试断言“拖 splitter 后 undoStack 不变、redoStack 不清、文件仍写入”。这是面板架构改造里很容易被忽略的一条。

### 3.3 `CompUi` 尺寸系统

`src/main/java/dev/bbsae/client/ui/CompUi.java`：

| 方法 | 行 | 作用 |
| --- | --- | --- |
| `configure(int preference)` | 63 | 应用密度 0/1/2/3，推导 control/row/gap/label/icon/bar |
| `autoDensity(float scale)` | 103 | BBS GUI scale → 密度 |
| `leftWidth()/rightWidth()/timelineHeight()` | 168/173/178 | 各密度默认侧栏/时间轴 |
| `fitTimelineHeight(int areaH, int desired)` | 193 | 时间轴最多 62%、至少留 72px 预览 |
| `bodyHeight(int areaH, int timelineH)` | 207 | body 高度，最低 56 |
| `maxSideWidth(int areaW)` | 218 | 单侧最多 42%，96–420 |
| `fitColumns(int areaW,int left,int right,int gaps,int minPreview)` | 233 | 左右按比例收缩，保 120 预览 |
| `quickWidth(int areaW,int desired)` | 254 | 快捷面板最多 `w/2-40` |
| `timelineSplitterSpan(...)` | 271 | 当前工作区新增：时间轴把手只覆盖 preview |
| `headerLayout(int width,int control)` | 310 | 顶栏优先级布局（纯函数，已有测试） |
| `previewBarLayout(...)` | 515 | 预览浮动条（纯函数，已有测试） |

已有测试：
- `src/test/java/dev/bbsae/client/ui/CompUiTest.java`：密度、提示词。
- `src/test/java/dev/bbsae/client/ui/CompUiResponsiveTest.java`：时间轴/列宽/顶栏/预览条；工作区新增 `timelineSplitterStaysInsideThePreview`。

**缺口**：

- `applyLayout` 把“请求尺寸”和“生效尺寸”混在同一字段里（`leftPanelWidth` 被 `Math.min` 写回）。窗口缩小时用户宽度被永久改小，窗口放大后不会恢复。
- `bodyHeight` 最低 56，但 `timeline.y` 是 `area.h - tlh - 18`；在 `area.h < 约 170` 时 body 会压到 timeline 上（见 4.3 的约束）。
- 没有内容级最小宽度：`fitColumns` 可以按比例把 inspector 压到 < 296；`CompUi.maxSideWidth` 只限制上限，不保下限。
- 没有断点/堆叠：窄窗口只能硬挤，无法像 BBS 一样把面板 stack 成标签。
- `PanelResizeHandle` 没有双击复位、没有 cursor 反馈、没有 `isVisible()` 命中检查（见 3.6）。
- 密度自动档只在 `bind`/`applyDensity` 时读 `bbsScale()`；GUI scale 改了但面板没重开时不会重算。

### 3.4 现有动画覆盖清单

`UiMotion` 已在很多地方使用（`grep 'new UiMotion'`）：

| 页面/组件 | 现有 | 缺口 |
| --- | --- | --- |
| `UICompButton` (`:108 renderSkin`) | hover 填充/边框/文字/上浮 2px/阴影/强调轨 | 按下反馈；禁用态过渡 |
| `UICompIconButton` (`:32 render`) | 仅 `hover` 布尔→瞬间底板 | 无 motion、无按下、无焦点环 |
| `UICompTabBar` (`:36/:62`) | hover/选中下划线 | 内容切换的过渡（内容由宿主 setVisible 瞬切） |
| `UIEffectLibrary` (`UIEffectList :293`, `renderElementPart :331`) | **无**（BBS `UIList`） | 行 hover、选中、错峰、滚动平滑 |
| `UIEffectLibraryOverlay` (`:214 render`) | hover/错峰/缩略图淡入/平滑滚动 | 打开/关闭卡片过渡（部分有） |
| `UILayerLibrary` (`:344 render`) | 卡片入场/hover/选中/平滑滚动 | 打开/关闭整层过渡 |
| `UIAssetBrowser` (`:1128 render`) | hover/选中/缩略图淡入/错峰/平滑滚动/滑块 | 打开/关闭整层过渡（已有遮罩） |
| `UICompTrackTimeline` (`:633 render`) | 轨道头/轨道选择、clip 抓手颜色 | 行 hover、拖拽 HUD 淡入、吸附线脉冲、平滑滚动 |
| `UILayerQuickPanel` (`:346 render`) | 顶部 42px 渐变 + 入场条 | 面板显示/隐藏、行 hover、分组展开 |
| `UICurveEditor` (`:44 render`) | **无** | 点 hover/拖动、曲线入场 |
| `UICompCurveEditor` (`:1054 render`) | **无** | 同上（3002 行，风险较高） |
| `UIColorWheel` (`:64 render`) | **无** | hover 环、拖动反馈 |
| `UICompPresetBrowser` (`:208 UIScrollViewView`) | **无** | 卡片入场/行 hover |
| `UICompRenderJobList` (`:18`) | **无** | 状态脉冲、行 hover |
| `UIImportBrowser` (`:259 render`) | **无** | 打开/关闭、行 hover |
| `UICompSettings` (`:260 render`) | 遮罩/分类扫光/行 hover/卡片 overshoot | 分类切换内容过渡；部分行是 BBS 原生控件 |
| `UIStartScreen` (`:372 render`) | 入场/标题下划线/卡片 | 页面切换（当前是单页） |
| `UIOnboarding` (`:146 render`) | 卡片 overshoot/分页/背景光 | 页间滑动已有；可补内容错峰 |
| `UISponsorBoard` (`:45 render`) | 动画粒子/卡片 | 已覆盖 |
| `UICompositorPanel` (`:18521 render`) | 整体入场 6px、顶栏呼吸线、设置/引导遮罩 | 页面/模式切换、弹窗、面板显示/隐藏、状态条 |
| `UICompositorKeyframes` | 包装 BBS 关键帧编辑器 | 抽屉已由 `keyframeDrawerSlide` 动画（`:10965`） |
| `UICompositionClip` | BBS `UIClip` | 无自定义（BBS 原生） |

**BBS 原生控件**（`UIButton`、`UIToggle`、`UITrackpad`、`UITextbox`、`UIList` 的默认绘制、右键菜单、tooltip）不在本次替换范围；这与历史约束“运行时 UI 全部 BBS 原生 Java 控件”一致。C 层只动画 bbsae 自绘表面和宿主容器，不修改 BBS jar。

### 3.5 动画缺口分组（按页面）

1. **入口/页面层**：`UIStartScreen`、`UIOnboarding` 有；`UICompositorPanel` 的“开始页 ↔ 编辑器”“编辑器 ↔ 渲染队列”“聚焦模式”“左栏三页”“inspector 七页”“时间轴/曲线页”都是瞬切。
2. **浮层**：`UICompSettings`、`UIOnboarding`、`UIAssetBrowser`、`UILayerLibrary`、`UIEffectLibraryOverlay` 有；`UIImportBrowser`、旧 `modalLayer`、导出对话框、快捷键帮助、颜色选择器没有/不全。
3. **列表**：素材库/图层库/效果浮层有错峰；左栏效果列表、渲染队列、导入浏览器没有。
4. **交互反馈**：按钮 hover 有；icon 按钮、tab 按下、splitter hover、轨道行 hover、曲线点、色轮没有。
5. **布局过渡**：关键帧抽屉有；面板宽度/高度、快捷面板、聚焦/渲染模式、密度切换没有。

### 3.6 “拖轨道左侧面板数据时面板大小跟着变”的根因

#### 症状

用户按住时间轴左侧轨道头最左边的三条横线（`REORDER_GRIP=14`）上下拖动排序时，**时间轴高度**跟着变（左列/右列底部若正好压到别的把手，也可能误触宽度）。

#### 根因链（历史代码）

1. `body` 横跨整个内容区；`leftHandle/rightHandle/timelineHandle` 都是 `body` 的子元素，并且是在三个内容列**之后**添加的：
   ```java
   this.body.add(this.effectColumn, this.preview, this.inspectorColumn);
   this.body.add(this.leftHandle, this.rightHandle, this.timelineHandle); // 后添加 = 事件优先
   ```
   见 `UICompositorPanel.java:5288-5294`（当前工作区行号）。
2. 旧版 `timelineHandle` 是全宽的：
   ```java
   this.timelineHandle.relative(this.body).x(0).y(1F, -4).w(1F).h(4);
   ```
   它覆盖了 `effectColumn` 和 `inspectorColumn` 的**最后 4px**。
3. BBS 的 `UIElement.childrenMouseClicked` 从后往前遍历，**不检查 `isVisible()`，只检查 `isEnabled()`**：
   `_research/bbs-fs-1.20.1/.../UIElement.java:1300-1323`。
   所以点击左列底部 4px 时，`timelineHandle` 先收到事件；它的 `subMouseClicked` 只判断 `area.isInside(context)` 就 `dragging = true`（`UICompositorPanel.java:12006` 附近）。
4. 之后每帧 `PanelResizeHandle.render()` 调 `drag(context)`，轮询左键并修改 `timelinePanelHeight`（`:11958-11985`）。轨道行根本没收到按下，所以看起来是“拖数据把面板拖大了”。

**一句话**：不是轨道行拖拽状态和 splitter 共用，而是 **splitter 的命中矩形盖住了左列底部的数据行**；事件优先级由 BBS 的子元素顺序决定。

#### 为什么工作区的半修复有效

当前工作区已经有另一个协作者（或 Lead）改了一半：

- `CompUi.timelineSplitterSpan(areaW,left,right,gapL,gapR)`（`CompUi.java:271`）计算 preview 的 x/width；
- `applyLayout` 把 `timelineHandle` 改成：
  ```java
  int[] splitter = CompUi.timelineSplitterSpan(this.area.w, lw, rw, gapL, gapR);
  this.timelineHandle.relative(this.body).x(splitter[0]).y(1F, -4).w(splitter[1]).h(4);
  ```
  `UICompositorPanel.java:11615-11627`。
- `UICompTrackTimeline.subMouseClicked` 最前面加 `if (panel.isPanelResizing()) return true;`（`UICompTrackTimeline.java:1328-1336`）。
- `UICompositorPanel.isPanelResizing()`（`:11919-11925`）+ `PanelResizeHandle.isDragging()`（`:11942`）。
- `CompUiResponsiveTest.timelineSplitterStaysInsideThePreview`（测试文件 `:50-78`）。

这能把“左列底部被时间轴把手覆盖”消除，方向正确。**但还有 4 个缺口**：

1. **隐藏把手仍可命中**：`PanelResizeHandle.subMouseClicked` 没有 `isVisible()` 检查；`leftHandle` 在左栏隐藏时仍定位在 `x=lw+2`（`lw=0` 时为 2..6），`keyframeHandle` 隐藏时也可能保留旧 area。BBS 事件派发不看可见性，这会再次出现“点到了看不见的把手”。
2. **只是“把宽度改小”，不是结构化约束**：`timelineSplitterSpan` 是补丁；下一个新增把手/面板还会犯同样错误。应把“哪个矩形属于哪个面板/哪条缝”收进纯布局函数，把手命中由 `CompositorLayout.hitTest()` 统一决定。
3. **“拖拽状态共用”仍有隐患**：`PanelResizeHandle` 和 `UICompTrackTimeline` 各自轮询左键；`isPanelResizing()` 只挡“已经开始拖 splitter 后”的轨道点击，不能挡同一帧里另一个元素先启动。应引入单一 `GestureOwner`（NONE/SPLITTER/TRACK/PREVIEW/…），按下时只有一个 owner。
4. **同类问题：`UILayerQuickPanel` 也是“全局轮询”而不是“按下即拥有”**。`applyResize()`（`UILayerQuickPanel.java:396-425`）每帧检查 `Window.isMouseButtonPressed(0)`，只要鼠标在快捷面板左边缘 4px 内就 `resizing = true`；它不检查这次按下是否始于该边缘。于是拖着轨道 bar/数据经过快捷面板左边缘时，快捷面板宽度会跟着变。修复：在 `subMouseClicked` 里记录 `pressedOnGrip = area.isInside && mouseX - area.x <= 4`，`applyResize` 只在 `pressedOnGrip` 为 true 时启动，松开清零；并跳过 `panel.isPanelResizing()`/`trackTimeline.isInteracting()` 期间的启动。这个 bug 和 3.6 的主 bug 是同一类（轮询而没有手势所有权），不要只修一个。

#### 回归测试建议

纯逻辑优先（不需要 GL）：

```java
// 新测试：CompositorLayoutTest
assertFalse(layout.hitTest(leftColumnBottomX, leftColumnBottomY).isSplitter());
assertEquals(Splitter.NONE, layout.hitTest(leftX, leftBottom).splitter());
assertEquals(Splitter.TIMELINE, layout.hitTest(previewCenterX, bodyBottom - 1).splitter());
// 旧 bug 的精确点：左列底部 4px 内
for (int x : List.of(leftX + 4, leftX + leftW / 2, leftX + leftW - 4)) {
    for (int y : List.of(bodyBottom - 3, bodyBottom - 2, bodyBottom - 1)) {
        assertNotEquals(Splitter.TIMELINE, layout.hitTest(x, y).splitter());
    }
}
```

UI 级回归（无头难，需真机）：
- 在左栏“效果库”最后一行底部按住拖动 20px：轨道高度不变；
- 左栏隐藏时点击 preview 左边缘 4px：不出现左栏宽度变化；
- 开始拖 splitter 后把鼠标移到轨道上：不启动轨道拖拽；
- 拖 splitter 期间窗口 resize：不出现把手漂移/双拖拽。

**真机确认点**：在左栏最后一行**底部 4px** 按下拖动 vs 在行**中间**按下拖动。旧版应该是前者改时间轴、后者正常排序；如果两者都改，说明还有另一个全宽把手（`keyframeHandle` / `quickPanel` 左边缘）或面板区域重叠，需要继续用 `CompositorLayout.hitTest` 的命中日志定位。可以临时加 `-Dbbsae.debugPanelHit=true` 在按下时打印命中的 owner（不要提交调试代码）。

**不要**只测 `timelineSplitterSpan` 的返回值；要测“点是否落到把手上”的语义，否则补丁可以过测试但真实事件顺序仍错。

---

## 4. 方案 A：面板自适应（尺寸 / 最小尺寸 / 密度 / 窗口变化重排）

### 4.1 目标与非目标

目标：

- 窗口/面板尺寸变化时，三栏 + 时间轴 + 快捷面板**不重叠、不越界、不出现负尺寸**。
- 左右栏有内容级最小宽度；时间轴有最小/最大高度；短窗口优先保预览。
- 用户拖过的宽度在窗口缩小再放大后**能恢复**（请求值 vs 生效值分离）。
- 密度（GUI scale / 手动三档）变化时，布局按同一套规则重算。
- 与 B 共用同一份“面板矩形”模型，方便后续 dock 化。

非目标：

- 不在 A 层做面板拖拽组合（那是 B）。
- 不替换 BBS 原生控件皮肤。
- 不做“任意像素自由浮动窗口”。

### 4.2 新增文件与签名

**新文件**：`src/main/java/dev/bbsae/client/ui/layout/CompositorLayout.java`

```java
package dev.bbsae.client.ui.layout;

import java.util.List;

/**
 * 纯函数布局：输入窗口/请求尺寸，输出每个面板与每条缝的矩形。
 * 不读 UIElement、不读 GL、不写字段；单元测试直接调用。
 */
public final class CompositorLayout
{
    public static final int HEADER_H = 24;
    public static final int STATUS_H = 18;
    public static final int GAP = 6;

    public static final int MIN_LEFT = 140;
    public static final int MIN_RIGHT = 296;
    public static final int MIN_PREVIEW = 120;
    public static final int MIN_TIMELINE = 80;
    public static final int MIN_BODY = 72;
    public static final int SPLITTER_HIT = 4;

    public enum Mode { WIDE, MEDIUM, NARROW, SHORT }
    public enum Splitter { NONE, LEFT, RIGHT, TIMELINE }

    public record Rect(int x, int y, int w, int h)
    {
        public int ex() { return this.x + this.w; }
        public int ey() { return this.y + this.h; }
        public boolean inside(int px, int py) { ... }
        public Rect inset(int dx, int dy) { ... }
    }

    public record Inputs(
        int areaW, int areaH,
        int requestedLeft, int requestedRight, int requestedTimeline,
        boolean leftVisible, boolean rightVisible,
        boolean quickVisible, int requestedQuick,
        boolean focusMode, boolean renderMode)
    {}

    public record Result(
        Rect body, Rect left, Rect preview, Rect right, Rect timeline, Rect quick,
        Rect leftSplit, Rect rightSplit, Rect timelineSplit,
        int effectiveLeft, int effectiveRight, int effectiveTimeline,
        Mode mode, boolean needsDock, boolean compact)
    {}

    public static Result compute(Inputs in);
    public static Mode modeFor(int areaW, int areaH);
    public static int clampTimelineHeight(int areaH, int requested);
    public static int[] fitColumns(int areaW, int left, int right, int gaps, int minPreview);
    public static Splitter hitTest(Result r, int mouseX, int mouseY);
}
```

**算法草案**：

```text
contentH = max(0, areaH - HEADER_H - STATUS_H)
if contentH == 0: 全 0，避免负值

int upper = min(contentH * 0.62, contentH - MIN_BODY - GAP)
if (upper < MIN_TIMELINE) { upper = MIN_TIMELINE; needsDock = true; } // 短窗口标记，下面再兜底
timelineH = clamp(requestedTimeline, MIN_TIMELINE, upper)
bodyH     = max(0, contentH - timelineH - GAP)
bodyY     = HEADER_H

leftW  = leftVisible ? clamp(requestedLeft, MIN_LEFT, max(MIN_LEFT, areaW * 0.42)) : 0
rightW = rightVisible ? clamp(requestedRight, MIN_RIGHT, max(MIN_RIGHT, areaW * 0.42)) : 0
gaps   = (leftVisible ? GAP : 0) + (rightVisible ? GAP : 0)

// 先尝试保 MIN_PREVIEW；不够时按比例缩，但不低于各自的语义下限
available = max(0, areaW - gaps - MIN_PREVIEW)
if leftW + rightW > available:
    // 宽窗口按比例；窄窗口标记 needsDock，由 B 的 stack 树接管
    if areaW < 860: needsDock = true
    scale = available / (leftW + rightW)
    leftW = floor(leftW * scale)
    rightW = available - leftW

previewX = leftVisible ? leftW + GAP : 0
previewW = max(1, areaW - leftW - rightW - gaps)
rightX   = areaW - rightW

// 快捷面板：在时间轴右侧，和时间轴同高；不能把时间轴压到 0
quickW = quickVisible ? clamp(requestedQuick, 160, max(0, areaW / 2 - 40)) : 0
timelineW = max(1, areaW - quickW)
quick = Rect(areaW - quickW, bodyY + bodyH + GAP, quickW, timelineH)
timeline = Rect(0, bodyY + bodyH + GAP, timelineW, timelineH)

// 缝：只在相邻面板之间，宽 4，居中于 GAP
leftSplit  = Rect(leftW + GAP/2 - 2, bodyY, 4, bodyH)         // 仅 left 可见
rightSplit = Rect(areaW - rightW - GAP/2 - 2, bodyY, 4, bodyH) // 仅 right 可见
timelineSplit = Rect(previewX, bodyY + bodyH + GAP/2 - 2, previewW, 4)
```

**短窗口硬约束**（修复现有重叠）：

```java
int maxTimeline = contentH - MIN_BODY - GAP;
if (maxTimeline < MIN_TIMELINE) {
    // 窗口太矮：先缩时间轴到 MIN_TIMELINE，仍放不下则把 body 压到 0 并标记 needsDock
    needsDock = true;
    bodyH = Math.max(0, contentH - MIN_TIMELINE - GAP);
    timelineH = MIN_TIMELINE;
}
// 断言：body.ey() + GAP <= timeline.y
```

**断点**（A 层只标记，B 层执行）：

| 模式 | 条件 | 行为 |
| --- | --- | --- |
| `WIDE` | `areaW >= 1180 && areaH >= 520` | 三栏 + 时间轴，全部按内容最小宽 |
| `MEDIUM` | `860 <= areaW < 1180` | 左栏 + 预览/属性 stack（B 层）；A 层先按最小宽挤 |
| `NARROW` | `areaW < 860` | `needsDock = true`，建议使用 stack 树；A 层不自动隐藏面板 |
| `SHORT` | `areaH < 520` | 时间轴降到 80–180，保 body ≥ 72；`needsDock = true` 如果仍放不下 |

### 4.3 现有文件与插入锚点

| 文件 | 位置 | 改什么 |
| --- | --- | --- |
| `CompositorLayout.java` | 新文件 | 上面的纯函数 |
| `CompUi.java` | `:193-303` 附近 | `fitTimelineHeight/bodyHeight/fitColumns/maxSideWidth/timelineSplitterSpan` 改为委托 `CompositorLayout`（或保留薄包装并标 `@Deprecated`），避免两套公式 |
| `UICompositorPanel.java` | 字段 `:317-335` | 增加 `effectiveLeftWidth/effectiveRightWidth/effectiveTimelineHeight`；`leftPanelWidth/...` 只存请求值 |
| 同上 | `applyLayout()` `:11559-11632` | 改为调用 `CompositorLayout.compute`；不写回请求值；设置 `effective*`；把手矩形直接用 `Result`；`needsDock` 时显示状态提示（B 层切 stack） |
| 同上 | `PanelResizeHandle` `:11927-12009` | 用 `Result.leftSplit/rightSplit/timelineSplit` 做命中；`subMouseClicked` 先判 `isVisible()`/`canBeRendered`；`setVisible(false)` 时结束 drag；加双击复位；加 `requestCursor(HRESIZE/VRESIZE)`；拖动上限用 `CompositorLayout` 的 min/max |
| 同上 | `savePanelLayout()` `:11845-11863` | 只保存请求值；布局树（B）另行保存 |
| 同上 | `loadPanelLayout()` `:11894-11915` | 读取请求值后不 `Math.max` 到旧硬编码；交给 `CompositorLayout` clamp |
| 同上 | `applyDensity()` `:11869-11892` | 设置请求值为 `CompUi.leftWidth()/rightWidth()/timelineHeight()`；不要覆盖用户已拖的宽度（除非用户点“重置密度”） |
| 同上 | `resize()`（需新增 override） | `super.resize()` 后重算 `layoutPreviewBar(preview.area.w)`、`layoutKeyframeDrawer()`、`quickPanel` 定位；dock 模式也走这里 |
| 同上 | `layoutKeyframeDrawer()` `:10898-10943` | `max` 从 `timelinePanelHeight` 改为 `timeline.area.h`（B 层 dock 化后字段不再等于实际高度） |
| `UILayerQuickPanel.java` | `applyResize()` `:396-425` | 增加“按下是否始于左边缘”的 `pressedOnGrip` 标志；只在该标志为 true 时 resize；拖拽其他内容经过左边缘不得改宽度 |
| `CompUiResponsiveTest.java` | 现有 | 增加“请求宽度在缩小→放大后恢复”“左列底部不是 timeline splitter”“短窗口 body 不压 timeline”“quickPanel 宽度不因外部拖拽变化” |

### 4.4 风险

1. **行为变化**：现在窗口变窄时列宽会被永久改小；改成“只改生效值”后，窗口变宽会恢复用户宽度。这是修 bug，但用户可能第一次看到“面板自己变回去”，需要状态提示/文档。
2. **断点自动切换**：`needsDock` 只做提示、不自动改布局，A 层是安全的；如果 B 层自动切 stack，必须记住“用户是否自定义过布局”，否则会覆盖用户拖动。
3. **密度与请求值**：`applyDensity` 目前会重置宽度；改成“只重置请求值”后，`applyLayoutPreset` 仍要显式重置，避免预设失效。
4. **测试数据**：`CompUiTest` 断言 `leftWidth()` 等默认值；改委托后要保证返回值不变。

### 4.5 单元测试 / 无头验证

新增 `src/test/java/dev/bbsae/client/ui/CompositorLayoutTest.java`：

```java
@Test void noOverlapAtEverySize()
{
    for (int w = 320; w <= 3840; w += 16)
    for (int h = 240; h <= 2160; h += 16)
    {
        Result r = compute(new Inputs(w, h, 190, 300, 250, true, true, false, 210, false, false));
        assertTrue(r.left().w() >= 0 && ...);
        assertTrue(r.left().ex() <= r.preview().x());       // 左列不压预览
        assertTrue(r.preview().ex() <= r.right().x());      // 预览不压右栏
        assertTrue(r.body().ey() + GAP <= r.timeline().y()); // body 不压时间轴
        assertTrue(r.timeline().ey() <= h - STATUS_H);
        assertTrue(r.timelineSplit().inside(r.preview()));
    }
}

@Test void requestedWidthsSurviveShrinkAndGrow()
{
    Result narrow = compute(new Inputs(960, 720, 240, 360, 300, true, true, false, 210, false, false));
    Result wide    = compute(new Inputs(1920, 1080, 240, 360, 300, true, true, false, 210, false, false));
    assertEquals(240, wide.effectiveLeft());
    assertEquals(360, wide.effectiveRight());
    // narrow 的 effective 可以小，但请求值不能被写回；这里测 Result 不携带请求字段，
    // 真正的“写回”回归由 UICompositorPanel 的测试/代码审查保证
}

@Test void leftColumnBottomIsNotTimelineSplitter()
{
    Result r = compute(...);
    for (int x : new int[]{r.left().x() + 2, r.left().mx(), r.left().ex() - 2})
        for (int y : new int[]{r.body().ey() - 4, r.body().ey() - 3, r.body().ey() - 1})
            assertNotEquals(Splitter.TIMELINE, hitTest(r, x, y));
}

@Test void shortWindowKeepsBodyAboveTimeline()
{
    for (int h = 120; h <= 520; h += 4)
    {
        Result r = compute(new Inputs(1280, h, 190, 300, 250, true, true, false, 210, false, false));
        assertTrue(r.body().h() >= 0);
        assertTrue(r.body().ey() + GAP <= r.timeline().y() || r.needsDock());
    }
}
```

已有测试：`CompUiResponsiveTest` 继续跑；`CompUiTest` 不改期望值。

### 4.6 真机截图验收清单（A）

> Lead 集中验证；不要在本任务里开客户端。

1. 1920×1080，GUI scale 2 / 密度“中”：左 190、右 300、时间轴 250；截图对照 `docs/ui-track-redesign.png`。
2. 窗口从 1920 拖到 1280、960、800、700：无重叠、无负宽、预览 ≥120；700 宽时出现“建议使用堆叠布局”提示（B 前）。
3. 窗口高度 1080→700→520→420：时间轴被压缩，body ≥72；420 高时 body 不压时间轴。
4. 先拖右栏到 360，缩到 1280 再放大到 1920：右栏回到 360（不是卡在缩小值）。
5. GUI scale 2→3→4 + 密度自动：顶栏按钮按优先级隐藏、不重叠；左右栏宽度按密度档重置。
6. 拖左/右/时间轴缝隙：鼠标变 resize cursor；拖到极限停住；双击缝隙恢复 50/50（或默认值）；松手后重启客户端尺寸保留。
7. 左栏隐藏（聚焦/手动）时点击 preview 左边缘 4px：不触发左栏 resize。
8. 时间轴抽屉打开时拖关键帧缝隙：只改抽屉高度，不改时间轴高度。

---

## 5. 方案 B：面板可拖拽组合（对齐 BBS 原版）

### 5.1 路线选择

| 路线 | 交互一致性 | 内容最小宽度 | 工作量 | 风险 | 结论 |
| --- | --- | --- | --- | --- | --- |
| **B1 直接复用 `UIDockLayout`** | **100%（同一份类）** | 比例分栏；用断点 stack 补 | 中 | BBS API/全局设置耦合 | **推荐** |
| B2 自研 `CompositorDock`（复用 `EditorLayoutNode`） | 需逐项对齐（可做到 95%+） | 像素级最小宽 | 大 | 细节多、回归面大 | 备选 |
| B0 只加 splitter + 预设 | 30% | 好 | 小 | 不满足“可拖拽组合” | 不推荐 |

**推荐 B1**：原版已经有，且是 public；没有理由重写一套 drag/drop 树操作。bbsae 只需解决“面板注册、布局持久化、断点、与现有 header/quickPanel 的衔接”。

### 5.2 面板分解与默认树

把四个核心面板注册进 dock：

| id | 现有元素 | 图标（BBS `Icons`） | 标签 |
| --- | --- | --- | --- |
| `effects` | `effectColumn`（效果库/素材/预设） | `Icons.MATERIAL` 或 `Icons.WRENCH` | `效果 / 素材` |
| `preview` | `preview` | `Icons.VIDEO_CAMERA` | `预览` |
| `inspector` | `inspectorColumn` | `Icons.PROPERTIES` | `属性` |
| `timeline` | `timeline`（含 header/轨道/曲线/关键帧抽屉/**quickPanel**） | `Icons.FILM` | `时间轴` |

**与 `DOCK-LAYOUT-PLAN` 对齐**：ID 用 `effects/preview/inspector/timeline`，与已有 `CompDockSource.PANEL_*` 常量一致，不要另起 `left`/`main`。

`quickPanel` **放进 `timeline` 面板内部**（作为 timeline 的右下子区域），而不是单独注册第五个 dock panel：它由选中图层驱动、宽度 160–340，且当前就是“贴着时间轴右侧”的上下文面板。放进 timeline 后，timeline 被拖到别处时 quickPanel 自动跟随；不需要 dock 的 per-panel gate。代价是 timeline 面板内部仍要自己布局 quickPanel 宽度（现有 `layoutPreviewBar`/`applyLayout` 已有逻辑）。

默认树（宽窗口）：

```java
// 语义：上面一行三栏，下面一行时间轴
// SplitterNode(horizontal=true) 按高度切；false 按宽度切
new SplitterNode(true, 0.72F,                    // 上 72% / 下 28%
    new SplitterNode(false, 0.13F,               // 左 13%
        new PanelNode("effects"),
        new SplitterNode(false, 0.78F,           // 预览 : 属性 = 78 : 22
            new PanelNode("preview"),
            new PanelNode("inspector"))),
    new PanelNode("timeline"));
```

中窗口（`860–1180`）：属性栏和预览 stack 成标签，避免属性栏 < 296：

```java
new SplitterNode(true, 0.70F,
    new SplitterNode(false, 0.16F,
        new PanelNode("effects"),
        new StackNode(List.of("preview", "inspector"), "preview")),
    new PanelNode("timeline"));
```

窄窗口（`<860`）：左/预览/属性三标签 stack，时间轴在下面：

```java
new SplitterNode(true, 0.62F,
    new StackNode(List.of("effects", "preview", "inspector"), "preview"),
    new PanelNode("timeline"));
```

这些树都只包含已注册的 4 个 panel；`UIDockLayout.ensureRegisteredPanels` 会把缺失的 panel 补回 root 右侧（`UIDockLayout.java:565-584`），所以不会“丢面板”。

**比例 vs 内容最小宽的硬取舍**：上面的宽树按 1920 宽约得到 left≈250、preview≈1300、inspector≈370；按 1180 宽约得到 left≈153、preview≈800、inspector≈226。也就是说，**纯比例 dock 无法在 1180–1280 宽同时保住“左 190 / 右 296”**。三个选择：

- **B1-a（推荐先做）**：接受 inspector 在 1180 宽约 226px，配 `CompUi` 紧凑密度仍可用；低于 220px 时把中树切成 preview/inspector stack。这样交互 100% 原版，代价是中等窗口属性栏变窄。
- **B1-b**：把 WIDE 阈值提高到约 1680；1280 宽直接走 medium 树（preview/inspector stack），不再并排。属性栏更宽，但少一个同时可见的面板。
- **B2**：自研像素 dock，才能同时保住“左 190 / 右 296”和拖拽组合；工作量最大。

这个取舍必须由用户拍板（第 8 节 #2），不要实现完才发现“1280 宽属性栏太窄”。

### 5.3 复用已有的 `CompDockSource`（不要重复造 codec）

工作区已经有一份 B 的初版，**不要重复写**。但注意：`CompDockSource.java` 已存在，`UICompositorPanel` 里却还没有任何 `UIDockLayout` 引用（grep 无匹配），所以 B 目前是“数据层/计划已就绪、UI 未接线”，不是已完成。

- `docs/DOCK-LAYOUT-PLAN.zh.md`：B 的实施步骤（面板划分、默认树、持久化、改造步骤、真机清单）；
- `src/main/java/dev/bbsae/client/ui/CompDockSource.java`：已经 `implements ILayoutSource`，用 BBS 的 `DataToString.toString/fromString` 做编解码；
- `CompProject.CompSettings` 已有 `String dockLayout`、`String dockHiddenPanels`、`boolean dockUnlocked`（`CompProject.java:308-321` 附近，工作区新增）。

因此本文原方案的 `CompLayoutCodec` / `CompositorDockHost` **不再需要新建**（保留为“如果 5.2 的断点/结构签名需要额外工具”的备选）。B 的正确起点是修 `CompDockSource` 的三个性能/行为问题：

1. **`getRoot()` 每次调用都 `DataToString.fromString`**  
   `UIDockLayout` 在 `setupFlex` / `applySplitterDrag` / `layoutRoot()` 里会多次调用 `source.getRoot()`；当前 `CompDockSource.getRoot()`（`CompDockSource.java:42-48`）每次都 parse 字符串；`dockLayout` 为空时还每次 `defaults.get()` 新建一棵默认树。拖缝隙时每帧多次解析/建树，是纯浪费，且会产生临时对象。修法：缓存 `parsedRoot` 和 `defaultRoot`，只用 `settings.dockLayout` 的字符串比较做失效；`setRoot` 时同步更新缓存。

2. **`setRoot()` 每帧 `DataToString.toString`**  
   `UIDockLayout.applySplitterDrag` 每帧调 `setRoot`（`UIDockLayout.java:934-941`）。当前 `CompDockSource.setRoot`（`:50-65`）每帧 encode 整棵树并写 `settings.dockLayout`，即使不写盘也会产生字符串。修法：`setRoot` 只保存 `EditorLayoutNode pendingRoot` + `dirty = true`，**不调用 `onChange`**；新增 `flush()`，在 `onLayoutSettled`、面板关闭、切工程时 encode 一次、写 `settings.dockLayout`，再调用一次 `onChange`（debounced save）。

3. **`dockUnlocked` 持久化时机**  
   `CompDockSource` 只负责树/隐藏面板，不负责锁定；锁定状态应在 `UICompositorPanel` 的锁按钮/`toggleLock` 回调里写 `settings.dockUnlocked`，并在 `bind/loadPanelLayout` 时 `dock.locked(!settings.dockUnlocked)`。否则重启后锁状态丢失。

**断点/最小宽度扩展**（本计划相对 `DOCK-LAYOUT-PLAN` 的增量）：新增 `src/main/java/dev/bbsae/client/ui/CompDockPresets.java`（或放在 `comp/layout`，避免 `CompStore.migrate` 依赖 client）：

```java
public final class CompDockPresets
{
    public static EditorLayoutNode wide();       // 5.2 的宽树
    public static EditorLayoutNode medium();     // preview/inspector stack
    public static EditorLayoutNode narrow();     // effects/preview/inspector stack
    public static EditorLayoutNode forSize(int areaW, int areaH);
    public static EditorLayoutNode fromLegacyPixels(int areaW, int areaH,
                                                    int left, int right, int timeline);
}
```

- `CompDockSource` 的构造参数已经是 `Supplier<EditorLayoutNode> defaults`；把 `CompDockSource::defaultLayout` 换成 `() -> CompDockPresets.forSize(...)` 即可，不需要改 `ILayoutSource`。
- `fromLegacyPixels` 用于旧工程迁移：把 `CompSettings.leftPanelWidth/rightPanelWidth/timelineHeight` 换算成比例；失败/太小则退回 `forSize`。
- 现有 `CompDockSource.defaultLayout()` 用 0.13 / 0.80 / 0.72（属性栏占剩余 20%）。建议 P2 把它改成 0.13 / **0.78** / 0.72（属性栏占剩余 22%），这样 1180 宽约 226px、1920 宽约 370px，和 5.2 的数字一致；如果保持 0.80，1180 宽只有约 205px。
- 如果不想做断点，至少改这一个比例；不要在没有断点/比例调整的情况下承诺“保 296”。

**`layoutCustomized` 的判定**（如果要自动断点）：`UIDockLayout` 没有“用户改了布局”的回调；在 `applyPreset/resetLayout` 时保存 `baseline = CompDockSource.encode(root)`，在 `onLayoutSettled` 比较**结构签名**（encode 时把 `StackNode.active` 置空，或单独 `sameStructure(a,b)`），不同才置 `layoutCustomized=true`。`onLayoutSettled` 也会在切 stack 标签时触发，只改 `active` 不应算自定义。

### 5.4 现有文件修改与插入锚点

| 文件 | 位置 | 修改 |
| --- | --- | --- |
| `CompProject.java` | `CompSettings` `:308-321`（工作区已加） | **已存在** `String dockLayout`、`String dockHiddenPanels`、`boolean dockUnlocked`；本计划只建议再加 `boolean dockCustomized`（断点用，可选），**不要重复加 `layoutJson/layoutLocked/hiddenPanels`** |
| `CompProject.java` | `VERSION` `:70` | **不需要 5→6**：新字段缺省即空/默认；Gson 对缺字段有默认值。若要做旧像素→树的迁移，在 `loadPanelLayout` 做一次即可，不要动 `CompStore.migrate` 的版本门 |
| `CompStore.java` | `migrate(...)` `:682-...` | 不改；`CompSettings` 随项目 Gson 自动读写 |
| `CompDockSource.java` | `getRoot()` `:42-48` / `setRoot()` `:50-65` | 缓存解析结果；`setRoot` 只存 pending + dirty；新增 `flush()` 在 settled/关闭时 encode 一次；`setHiddenPanels` 同理延迟写入（见 5.3） |
| `UICompositorPanel.java` | 字段（宽度/handle 区域） | 增加 `UIDockLayout dock; CompDockSource dockSource; boolean dockMode;`；旧 `PanelResizeHandle` 字段保留给 `dockMode=false` 的 A 路径 |
| 同上 | 构造（`body` + handles 段） | `dock = new UIDockLayout(); dock.relative(this).x(0).y(24).w(1F).h(1F, -42); dock.locked(!settings.dockUnlocked).frameless("preview").source(new CompDockSource(settings, CompDockPresets::forSize, this::onDockChanged));`；`addPanel("effects", effectColumn, ...)` ×4；`mount()`；`this.add(header, dock, renderPanel, status, ...)`，`body` 不再加入 |
| 同上 | `applyLayout()` | dock 模式：`layoutHeader()` + `layoutPreviewBar(preview.area.w)` + `layoutKeyframeDrawer()` + timeline 内部 quickPanel 布局；不再设置四栏相对位置；旧路径保留给 `dockMode=false` |
| 同上 | `resize()`（新增 override） | `super.resize()` 后重算 `layoutPreviewBar(preview.area.w)`、`layoutKeyframeDrawer()`、timeline 内部 quickPanel；`dock.isAnySplitterDragging()` 时跳过重活；**不要**在这里 `dock.setupFlex`（dock 的 slot 用比例，父 resize 已递归） |
| 同上 | `layoutKeyframeDrawer()` | `max = timeline.area.h - 96`（不是 `timelinePanelHeight`）；timeline 高度由 dock 树决定 |
| 同上 | quickPanel 布局 | quickPanel 改为 `timeline` 的子元素（当前是 panel 的兄弟）；在 timeline 内 `relative(timeline).x(1F,-w).y(...)`；timeline 被 dock 移动时自动跟随 |
| 同上 | `toggleRenderView()` / `toggleFocusMode()` | renderView 隐藏 dock、显示 renderPanel（覆盖同一区域）；focusMode **不要**只 `setVisible(false)`（dock 会留空），用临时树（`copyWithRemovedPanel` 移掉 effects/inspector）并保存/恢复；不要写 `dockHiddenPanels`（否则关闭时还隐藏） |
| 同上 | `applyLayoutPreset()` / `resetEditorLayout()` | dock 模式按 `UIOnboarding.LAYOUT_*` 映射到 `CompDockPresets.wide/medium/narrow`；`dock.applyLayoutRoot`；`settings.dockUnlocked=false`/保持；`settings.dockCustomized=false` |
| 同上 | `loadPanelLayout()` | `dock.locked(!settings.dockUnlocked)`；`dockSource` 已从 `settings.dockLayout` 读；`dock.refresh()`；旧工程 `dockLayout` 为空 → 用 `CompDockPresets.fromLegacyPixels(...)` 写一次（可选） |
| 同上 | `savePanelLayout()` | `dockSource.flush()`；`settings.dockUnlocked = !dock.isLocked()`；`settings.dockHiddenPanels` 已由 source 管；旧像素字段同步写（回退）；末尾 `saveUiState()` 不是 `save()` |
| 同上 | 顶栏/`layoutHeader()` | 增加锁定图标（`Icons.LOCKED/UNLOCKED`）→ `dock.toggleLock()` + `settings.dockUnlocked`；位置见 5.5；`CompUi.headerLayout` 加 `lockX/showLock` |
| 同上 | `subKeyPressed` | **按 BBS 原版**：`Ctrl+Tab`/`Ctrl+Shift+Tab` 循环 stack、`Ctrl+空格` 最大化、`Ctrl+Alt+Z` 撤销布局。**不要用 `Ctrl+Shift+Z`/`Ctrl+M`**（前者是重做、后者与现有快捷键冲突；`DOCK-LAYOUT-PLAN` 第 99 行的写法要改） |
| `UICompSettings.java` | `buildLayout()` | 新增行：解锁/锁定、重置布局、隐藏面板…、布局自定义状态；不新增重复的宽度字段行 |
| `CompUi.java` | `headerLayout` | 增加 lock 占位；更新 `CompUiResponsiveTest` |
| `CompKeybinds.java` | 命令表 | 可选：4 个 dock 命令；先 `subKeyPressed` 直读，避免两套快捷键不一致 |

### 5.5 顶栏锁定按钮

BBS 影片编辑器把锁放在 action bar。bbsae 当前顶栏右侧是 focus + settings；建议：

```
[... 导出 渲染 设置] [锁定] [聚焦]
```

`CompUi.headerLayout(width, control)` 目前右侧固定 focus→settings→render→export→save（`:399-440`）。新增一个 `lock` 图标会挤压；最稳妥是把它作为**固定元素**放在 focus 左边，宽度 `control`，`showLock` 永远 true（和 focus/settings 一样不可隐藏）。需要：

- `HeaderLayout` 增加 `lockX` 字段（`CompUi.java:443-503`）；
- `rightPositions` 增加 lock 占位，right cluster 从 lock 左边继续；
- `UICompositorPanel.layoutHeader()` `:11717` 设置 `layoutLock.relative...x(layout.lockX)`；
- `CompUiResponsiveTest.headerLayoutNeverOverlaps` 增加 lock 断言。

### 5.6 持久化与迁移

- **per-project**：`CompSettings.dockLayout` + `dockHiddenPanels` + `dockUnlocked`（工作区已有），跟项目走；同一影片的 camera/replay 视图可共享一套（bbsae 目前没有 BBS 那种 camera/replay 双编辑器布局绑定，先不做）。
- **BBS 原版是全局**：`BBSSettings.editorLayoutSettings`（`ValueEditorLayout`）。bbsae 不复用它，原因：一个工程一个工作区更符合剪辑习惯，也不会污染 BBS 设置。这是有意的差异，见 5.7。
- **迁移**：旧工程 `dockLayout=""`，`CompDockSource.getRoot()` 自动回默认树；若想保留旧像素观感，在 `loadPanelLayout` 里用 `fromLegacyPixels` 写一次 `dockLayout`（不要动 `CompProject.VERSION`）。
- **未知 panel id**：`UIDockLayout.pruneUnknownPanels`（`:546-563`）会删；`ensureRegisteredPanels`（`:565-584`）会补；`CompDockSource` 的坏串回默认树。
- **隐藏面板**：`dockHiddenPanels` 持久化；`UIDockLayout` 的 `fillHiddenPanelsMenu` 负责恢复。
- **锁定状态**：`dockUnlocked` 持久化，默认 false（锁住，同 BBS）。
- **撤销**：布局变更**不进** `CompProject` 的 JSON undo 栈（不要 `beginAction`）；`UIDockLayout` 自带 session 级 `layoutUndo`（`:92-97, 401-409`）。文档 undo 只记录内容，不记录 UI 布局。

### 5.7 与 BBS 原版的差异（必须如实告知用户）

| 项 | BBS 原版 | bbsae 方案 | 影响 |
| --- | --- | --- | --- |
| 布局存储 | 全局 `ValueEditorLayout` | 每工程 `CompSettings.dockLayout` | 每个影片有自己的工作区；换工程布局会变 |
| 自适应 | 比例缩放，无断点 | 比例 + 断点 stack（宽/中/窄） | 窄窗口自动把属性栏 stack 成标签，BBS 不会 |
| 内容最小宽 | 只有 80px/5% 拖拽下限 | 左 140 / 右 296 / 预览 120 / 时间轴 80 | bbsae 更保内容可读性 |
| 快捷面板 | 无对应物（BBS 的 replay props 是 dock 面板） | quickPanel 作为 `timeline` 面板的子区域，选中才显示，随 timeline 移动 | 不是独立可拖面板；若要独立拖，再注册第五 panel |
| 绑定布局 | camera/replay 可各绑一套 | 暂不绑定 | 先共享一套，后续需要再加 |
| 锁定入口 | action bar 锁图标 | 顶栏锁图标 | 位置不同，语义相同 |
| 面板视觉 | BBS 自带 surface/shadow | `UIDockLayout` 的 surface；与 bbsae 扁平风格可能有差异 | 需要截图确认，必要时用 B2 自绘 slot |
| 折叠 | 隐藏面板（移出树）+ 隐藏面板菜单恢复；没有折叠成窄条 | 同 BBS；不做“折叠 rail” | 若要 rail 是 bbsae 额外需求，另做 |

**“100% 跟原版一样操作便利”的准确表述**：拖拽/停靠/stack/最大化/重置/撤销这些**交互**由 BBS 同一份 `UIDockLayout` 提供，因此与影片编辑器一致；bbsae 额外增加了断点 stack 和每工程持久化，这两点是差异，不是退化。

### 5.8 风险

1. **BBS API 稳定性**：`UIDockLayout`/`EditorLayoutNode` 不是正式 API，升级 BBS 可能改签名。缓解：`libs` 固定 2.7-1.20.1；把 BBS dock 调用集中在 `UICompositorPanel` + `CompDockSource` 两处，升级只改这两处；加编译期测试。
2. **`editorResizablePanels` 全局开关**：BBS 用户关闭“可调整面板”后，bbsae 的缝隙也不能拖。缓解：设置页提示；或在 B 层检测 `BBSSettings.editorResizablePanels` 为 false 时显示“BBS 设置已禁用面板缩放”，不静默失效。
3. **`UIDockLayout` 没有内容最小宽**：断点树是硬编码；用户自定义后窗口缩到很窄仍可能压扁属性栏。缓解：`onLayoutSettled` 检查 `getPanel(...).area.w`，低于阈值时只显示一次“窗口过窄，建议重置布局”提示，不自动覆盖。
4. **重父级（reparent）风险**：`effectColumn/preview/inspectorColumn/timeline` 从 `body` 移到 dock slot；它们内部的 `relative(...)` 都以自己为父，理论上安全，但 `preview` 的 `viewportActive`/`area`、`timeline` 的 `layoutKeyframeDrawer` 依赖父级面积，必须逐项验证。缓解：先在 feature flag 后面接入，保留旧路径；A/B 对比截图。
5. **`quickPanel` 定位**：把 quickPanel 从 `panel` 的兄弟改为 `timeline` 的子元素后，它跟着 timeline 的 slot 走；但 timeline 内部还要给 quickPanel 留右侧宽度（timeline 本身变窄），`layoutKeyframeDrawer` / `layoutPreviewBar` 的宽度计算要用 `timeline.area.w`，不能用 `panel.area.w`。
6. **布局拖拽每帧 encode/parse**：现有 `CompDockSource.getRoot()` 每次 parse、`setRoot()` 每次 encode；`UIDockLayout` 每帧都会调 `getRoot/setRoot`，这会在拖缝隙时产生大量临时对象。必须缓存解析结果 + 延迟 encode（5.3）。这不是理论风险，是现有 `CompDockSource.java:42-65` 的实际实现。
7. **隐藏面板/聚焦模式**：`UIDockLayout.gate` 是全局的，不能只隐藏 quickPanel；聚焦模式要临时改树，不能只 `setVisible(false)`（会留空洞）。
8. **视觉差异**：`UIDockLayout` 自带 `deepSurface`/阴影/4px 沟槽，可能与 bbsae 的扁平深色不协调；先截图，再决定是否包一层自绘 panel（B2 的 slot 克隆）。
9. **撤销语义**：布局变更不能进文档 undo；测试要断言拖布局后 `undoStack` 不变。
10. **并发修改**：工作区已有未提交的 `UICompositorPanel`/`UICompTrackTimeline` 修改；B 层改动必须等这些合并/对齐，否则冲突概率高。

### 5.9 单元测试 / 无头验证

| 测试 | 类型 | 内容 |
| --- | --- | --- |
| `CompDockSourceTest` | 纯 | `encode/decode` round-trip（`DataToString`）；空串/坏串 → `defaultLayout`；`getRoot` 缓存命中（同一字符串只 parse 一次，可用计数/计时验证）；`setRoot` 不立即 encode（`dirty` 为 true，`flush` 后才写） |
| `CompDockPresetsTest` | 纯 | wide/medium/narrow 都恰好包含 4 个 panel id；断点边界（如 859/860、1179/1180）；`fromLegacyPixels` 比例合理 |
| `CompDockTreeTest` | 纯（用 `EditorLayoutNode` 静态方法） | `copyWithInsertSplitAt` 四个边、`copyWithInsertStackAt`、`copyWithSwappedPanels`、`copyWithRemovedPanel` 折叠；`collectPanelIds` 无重复/无丢失 |
| `CompDockDropTest` | 纯 | 模拟 `updateDropTarget` 的几何：tab 优先、root 16px、panel 边 48px/25%、中心 stack；Shift 交换 |
| `CompDockSourceMigrationTest` | 纯 | 旧工程 `dockLayout=""` + 像素宽度 → `fromLegacyPixels` 写出一次；坏串不阻止打开 |
| `CompUiResponsiveTest` | 纯 | A 层新增断言 + header lock 不重叠 |
| `CompDockPersistenceTest` | 纯/集成 | `saveUiState()` 不置 `documentDirty`；拖 splitter/应用布局后 undoStack/redoStack 不变；`dockLayout` 仍写入；`dockUnlocked` 重启后恢复 |

UI 事件（拖拽）没有无头测试；如果要在 `CompDockSource`/`CompDockPresets` 之外测 drop，可新增一个纯 `CompDockDropResult`（输入旧树 + dragId + targetId + zone + shift，输出新树），语义与 `UIDockLayout.applyPanelDropResult` 一致；不要复制 UI 手势。

### 5.10 真机截图验收清单（B）

1. 默认锁定：四个面板无 20px 拖拽条；顶栏锁图标是 `LOCKED`。
2. 点锁：四个面板顶部出现 20px 拖拽条；内容不被条盖住（左栏标签、时间轴工具条仍可点）。
3. 拖左栏标题条到预览右边缘：出现右边缘高亮带，松手后左栏在预览右侧成列；属性栏仍在最右。
4. 拖左栏到预览中心：并入预览 stack，出现标签条；点标签切换；`Ctrl+Tab` 循环。
5. Shift+中心拖到属性栏：两个面板交换。
6. 拖到窗口最左/右/上/下 16px：相对整个布局拆出一列/一行（25%）。
7. 拖标签到另一个 stack 标签上停 500ms：自动展开；拖到标签之间显示插入光标。
8. 拖拽中 Esc：取消，布局不变。
9. 双击缝隙：两边 50/50；拖缝隙到最小：80px 停住；拖交叉缝隙：横竖一起动。
10. `Ctrl+空格`：鼠标下方面板最大化；再按恢复；`Ctrl+Alt+Z`：撤销上一次布局；重置按钮恢复默认。
11. 右键面板拖拽条：最大化/隐藏/恢复隐藏面板。
12. 1280 宽：自动/手动使用 medium 树，属性栏与预览 stack；920 宽：narrow 树三标签 stack；无面板宽度 < 内容可用。
13. 关闭工程再打开：布局、锁定、隐藏面板、stack 活动标签恢复；旧工程迁移后观感接近原来。
14. 聚焦模式：只留预览（或预览+时间轴），退出后原布局恢复；渲染队列：dock 隐藏，返回后恢复。
15. 拖 splitter 时预览不闪、不重设 BBS 尺寸（bbsae 预览 FBO 不重建每帧）。
16. 布局拖拽后文档 undo 栈不变；保存文件里 `dockLayout` 更新但内容数据不变。

### 5.11 备选 B2（自研 dock）与 B0

**B2**：如果直接复用 `UIDockLayout` 的视觉/全局设置不可接受：

- 复制其交互语义（不是代码）：`CompositorDock extends UIElement`，内部用 `EditorLayoutNode` 树 + `CompositorLayout` 像素布局；
- 自己实现 slot/tab/floating drag overlay；
- 优点：像素最小宽、完全控制视觉；
- 缺点：约 700–900 行新代码，拖放细节（spring-load/交叉缝隙/撤销/隐藏）都要重新回归；不满足“100% 同一实现”。

**B0**（不推荐）：保留现有四把手，只增加“布局预设 + 面板显示/隐藏 + 可折叠”，不做拖拽组合；能满足“自适应”，但不满足“可拖拽组合”。

---

## 6. 方案 C：非线性动效系统

### 6.1 原则

沿用 ROUND29 的结论（`docs/ROUND29-UI-MOTION-ONBOARDING.zh.md`）：

- 动效是**绘制层/布局层**状态，不写 `CompProject`，不进 undo，不影响导出像素。
- 每个宿主一个 `UiMotion` 时钟；不引入全局动画管理器。
- `CompPrefs.animations=false` 时所有通道直接返回目标值；强度 0 同样。
- 不改 BBS 原生控件的绘制；只包 bbsae 自绘表面/宿主容器。
- 一帧只 tick 一次；`dt` 上限 0.05；不做每帧字符串拼接/分配。

### 6.2 扩展 `UiMotion`（新增 API）

文件：`src/main/java/dev/bbsae/client/ui/UiMotion.java`（现有 268 行）。

新增：

```java
public enum Easing { LINEAR, EASE_OUT, EASE_IN_OUT, OUT_BACK, OUT_ELASTIC, OUT_EXPO }

public static float springStep(float value, float target, float stiffness, float damping, float dt);
public float spring(String key, float target, float stiffness, float damping);
public float tween(String key, float target, float durationSeconds, Easing easing);
public void tickFrame(long nowNanos);          // 可注入时钟，测试用
public boolean isActive();
public void prune(float idleSeconds);          // 清掉已到达且 idle 的通道
public int channelCount();                     // 诊断/测试
```

实现要点：

- `springStep` 用半隐式 Euler，`dt = min(dt, 0.05)`；critical damping `damping = 2*sqrt(stiffness)`；接近目标 0.001 时 snap，避免永远抖。
- `tween` 是 one-shot envelope：`elapsed / duration`，结束移除；不保留永久通道。
- `prune`：每个 channel 记录 `lastUsedNanos`；目标达到且超过 idle 秒移除。解决现有 `"thumb:"+path`/`"track-head:"+id` 通道只增不减的内存问题。
- `anim/hover/select/intro/introBack/flash/breathe` 保持不变；`intro` 内部改用 `tickFrame` 的时钟而不是每次 `System.nanoTime()`（测试可复现）。
- 所有新 API 在 `!CompPrefs.animating()` 或 `intensity<=0.001` 时直接返回 target/1。

新增测试：`src/test/java/dev/bbsae/client/ui/UiMotionSpringTest.java`

```java
@Test void criticallyDampedSpringDoesNotOvershoot();
@Test void springSnapsAndStops();
@Test void tweenEndsExactlyAtTarget();
@Test void animationsOffReturnsTargetImmediately();
@Test void pruneRemovesIdleChannels();
@Test void dtIsClamped();
```

### 6.3 页面/浮层过渡

**新文件**：`src/main/java/dev/bbsae/client/ui/motion/UiPageTransition.java`

```java
public final class UiPageTransition
{
    public UiPageTransition(float duration, float slidePx);
    public void begin(String pageId);      // 重启动画
    public void tick(float dt);            // 每帧一次；关闭动效时直接 p=1
    public float progress();               // 0..1，带 easeOut
    public float slideY();                 // (1-p)*slidePx
    public float veilAlpha();              // (1-p)*0.28
    public void renderVeil(UIContext ctx, Area area); // 在内容之后画一层深色 veil
}
```

**为什么用“滑动 + veil”而不是真正 alpha 交叉淡入**：BBS `Batcher2D` 没有给整棵子树设置全局 alpha 的 API；逐控件 alpha 会侵入 BBS 控件。滑动 + 一层快速淡出的深色 veil 能掩盖瞬切，成本 1 个 quad，且不改子控件。BBS 影片面板自己的入场也是矩阵平移（`UICompositorPanel.java:18683-18707`），同一思路。

**宿主包装**：`UiTransitionHost extends UIElement`（或直接在 `UICompositorPanel` 里用矩阵 + veil）：

- 左栏：在 `effectColumn` 里新增 `leftHost`（y=22,h=1F-22），把 `effectLibrary/assetPanel/presetBrowser` 放进去；`onSelect` 只切 host 内可见页 + `transition.begin(page)`。
- Inspector：`inspector` 的 `UIScrollView` 外面包 `inspectorHost`；`refreshInspector` 后 `transition.begin(...)`。
- 时间轴页：`trackTimeline/curveEditor` 外面包 `timelineHost`（y=26,h=1F-26）；`applyTimelinePage` 调 `transition.begin`。
- 渲染队列/聚焦模式：整块内容用 `UiTransitionHost` 或 veil；dock 模式下不要试图动画 dock 树，直接用 veil + 150ms。

### 6.4 布局 tween（面板尺寸/模式过渡）

**新文件**：`src/main/java/dev/bbsae/client/ui/motion/UiLayoutTween.java`

```java
public final class UiLayoutTween
{
    public record Target(int x, int y, int w, int h) {}
    public void set(String key, Target target);
    public void tick(float dt);              // 每帧一次
    public Target get(String key);           // 当前动画值
    public boolean isSettled();
    public void snapAll();
}
```

用途：

- 聚焦模式（左右栏收起/展开）；
- 渲染队列（body/timeline ↔ renderPanel）；
- 快捷面板显示/隐藏（宽度 0→target）；
- 密度切换（三栏宽度）；
- **不用于**用户拖 splitter：拖拽要 1:1 跟手，tween 会延迟。

实现方式：`UICompositorPanel` 在 `render`/`tick` 里取 `get(key)`，把 `relative(...).x/y/w/h` 设为动画值；尺寸变化超过 1px 时调用对应 `resize()`。成本：每帧几个 rect + 必要时 resize；只在过渡的 150–250ms 内。

**与 dock 的冲突**：dock 的树布局由 `UIDockLayout` 控制，不能对 slot 做 tween（私有 slot）。BBS 原版的 dock 拖拽本身也是 1:1 跟手、没有缓动；C 层不要给“用户正在拖的缝隙/面板”加延迟，只在**模式切换**（聚焦/渲染/开始页）用 veil。legacy A 模式下可用 layout tween；dock 模式下聚焦/渲染用 veil，不做 tree 动画。

### 6.5 列表错峰 / 行反馈

通用辅助：`src/main/java/dev/bbsae/client/ui/motion/UiStagger.java`

```java
public final class UiStagger
{
    /** 行 i 的入场进度；perItem 秒，maxDelay 秒；只在 animations 开启时非 1。 */
    public static float progress(UiMotion motion, String key, int index, float perItem, float maxDelay);
    public static int delayPx(float progress, int distance);
}
```

接入：

- `UIEffectLibrary.UIEffectList.renderElementPart`（`:331`）：加 `UiMotion` 到 `UIEffectLibrary`，`render`/`renderElementPart` tick；hover/选中用 `anim` 插值背景/左侧 accent；行内容只做 2–4px 位移或颜色，不做大幅滑动（BBS list 有裁剪）。
- `UICompRenderJobList`（`:18`）：加 `UiMotion`，`renderElementPart` 画状态色脉冲（RENDERING 用 `breathe`），行 hover。
- `UIImportBrowser`（`:259`）：外层 `render` 加 `motion.tickFrame`，背景 alpha `intro`，`box` 用矩阵平移 8px（不要缩放整个全屏 box）；列表行用 `UiStagger`。
- `UICompPresetBrowser`（`:208`）：外层 `UiMotion`，卡片/行错峰 + hover。
- `UICurveEditor`（`:44`）/`UIColorWheel`（`:64`）：加 hover 状态 + `anim` 环/点；拖动时颜色/粗细插值。
- `UICompTrackTimeline`（`:633`）：行 hover/选中已有 `motion.select`；补 clip bar hover（`"bar:"+id`）、拖拽 HUD 淡入、吸附线 `flash`/脉冲；不要动画滚动位置（命中测试会错位）。
- `UILayerQuickPanel`（`:346`）：补显示/隐藏（宽度/位移），行 hover 用 BBS `UITrackpad` 的 hover？BBS 原生控件内部 hover 不可拦截；只动画分组标题/背景。
- `UIEffectLibraryOverlay`/`UIAssetBrowser`/`UILayerLibrary`：已有；只做 `UiMotion` 通道 prune，避免长会话内存增长。

### 6.6 按钮/滑块反馈

- `UICompButton.renderSkin`（`:108-199`）：加 `pressed` 通道（按下时 `lift=0`、内缩 1px、fill 变暗 6%），释放回弹；`pressed` 通过覆盖 `subMouseClicked`/`subMouseReleased` 维护（BBS `UIButton` 没有 pressed 字段）。
- `UICompIconButton.render`（`:32`）：加 `UiMotion`，hover 底板 alpha/边框插值、按下缩放（矩阵 0.96–1.0）、可选 1px accent 环。
- `UICompTabBar`：按下/选中下划线已有；加按下缩放/文字亮度。
- BBS 原生 `UIToggle/UITrackpad`：不替换；如果用户强烈要求，用 `UICompRow` 包装一层 hover 背景，不改控件内部。

### 6.7 开关与性能预算

`CompPrefs.Data` 新增（`CompPrefs.java:60-109`）：

```java
public boolean panelTransitions = true;   // 页面/面板过渡
public boolean listStagger = true;        // 列表错峰
public boolean buttonFeedback = true;     // 按下/滑块反馈
```

设置页（`UICompSettings.buildMotion()` 附近）加三行开关；`animations=false` 是总开关，优先级最高；`motionIntensity=0` 等价关闭。

性能预算：

- 单帧 UI 动效 < 0.3ms（不含 BBS 自身绘制）；`UiMotion` 通道数 < 200；每帧 `HashMap` 查询 O(1)，无分配。
- 列表只对可见行算 `intro`；`UIList` 本身已裁剪。
- 不在 `render` 里 `new String` 拼 key：给 `EffectRow`/`CompLayer`/`Asset` 增加稳定 `motionKey` 字段，或直接用 `id`（避免 `"track-head:" + id` 每帧分配）。
- `prune` 每 60 帧调用一次，不在每帧遍历全表。
- `animations=false` 时 `tickFrame` 只更新时间，不更新通道；`render` 直接返回。

### 6.8 风险

1. **BBS 事件/绘制顺序**：veil 必须在子元素之后画；矩阵 push/pop 必须 try/finally（参考 `UICompositorPanel.java:18689-18707`）。异常路径不 pop 会污染后续 UI。
2. **命中区域与动画区域不一致**：只动画绘制、不动 `area` 的过渡（如行 hover 位移）不能让点击错位；布局 tween 必须同时更新 `area` 并 `resize()`。
3. **NaN/抖动**：spring 参数不当会抖；用 critical damping + snap + dt clamp；测试覆盖。
4. **每帧 resize 成本**：布局 tween 每帧 `resize()` 可能触发 inspector scroll 重排；只在尺寸变化 >1px 时 resize，且过渡短（150–250ms）。
5. **dock 模式冲突**：不要 tween dock slot；聚焦/渲染用 veil。
6. **设置关闭后残留**：`animations=false` 时所有通道立即 snap；`UiMotion` 里所有新方法都要检查，不能只检查 `anim`。
7. **BBS 原生控件**：不要试图给 `UIToggle/UITrackpad/UIList` 内部做逐控件 alpha；会侵入 BBS 或导致裁剪错误。

### 6.9 单元测试 / 无头验证

- `UiMotionSpringTest`（6.2）。
- `UiPageTransitionTest`：注入时钟，`begin` 后 0/50/100/300ms 的 progress 单调、端点 0/1、关闭动效直接 1。
- `UiLayoutTweenTest`：target 变化后 ≤ N 帧收敛，无 NaN，`snapAll` 立即到目标；关闭动效立即到目标。
- `UiStaggerTest`：index 0 立即开始，index 50 延迟不超过 `maxDelay`，关闭动效为 1。
- `CompPrefsOverlayTest` 扩展：三个新开关默认值/序列化。
- 渲染 smoke：现有 `ShaderCompileTest` 等隐藏 GL 工具不适合 UI；C 层以纯数学测试 + Lead 真机截图为主。

### 6.10 真机截图验收清单（C）

1. 0.25× 录屏/逐帧：左栏三页切换有 6px 上滑 + veil 淡出，不闪现旧内容；inspector 七页切换同样。
2. 时间轴/曲线页切换：轨道/曲线内容滑动 + veil；关键帧抽屉仍独立滑动。
3. 设置/引导/开始页打开：卡片 overshoot + 背景淡入；关闭不残影。
4. 导入浏览器/图层库/效果浮层/快捷键帮助：打开/关闭有 120–180ms 过渡；列表行错峰；滚动不跳。
5. 聚焦模式/渲染队列：内容 150–250ms 过渡；dock 模式下用 veil，不出现半截面板。
6. 快捷面板显示/隐藏：宽度/位移过渡，时间轴同步让位；不遮住轨道。
7. 按钮/图标按钮/tab：hover 120–220ms、按下内缩、释放回弹；无文字位移。
8. 列表 hover：效果库/渲染队列/轨道行/曲线点/色轮有反馈；快速移动不粘滞。
9. 设置里关“启用界面动效”：所有过渡立即到位、无残留半透明/偏移；只关“列表错峰”时其他动效仍工作。
10. 性能：1080p、200 行列表、拖时间轴/拖 splitter 时 60fps；无每帧分配导致的 GC 尖峰；UI 帧时间 < 2ms（不含 BBS 世界）。
11. 文档数据：动效前后 `CompProject` JSON/undo 栈不变；导出帧不受影响（抽 1 帧对比）。
12. BBS 原生控件（toggle/trackpad/textbox/右键菜单）外观/行为不变。

---

## 7. 实施顺序与合并策略

### 7.1 阶段

| 阶段 | 内容 | 依赖 | 验收 |
| --- | --- | --- | --- |
| P0 | 收编工作区半修复：`timelineSplitterSpan` + `isPanelResizing` + 测试；补隐藏把手/可见性/双击复位；修 `UILayerQuickPanel` 的轮询 resize | 无 | 编译通过后跑 `CompUiResponsiveTest`；真机复现 bug 消失 |
| P1 (A) | `CompositorLayout` + `applyLayout` 请求/生效分离 + 短窗口约束 + 测试 | P0 | `CompositorLayoutTest` 全绿；A 截图清单 |
| P2 (B) | 在既有 `CompDockSource`/`DOCK-LAYOUT-PLAN` 上接入 `UIDockLayout`（修缓存/延迟 encode、锁定、断点可选、设置/锁/快捷键） | P1 | B 截图清单；旧工程迁移；undo 不变 |
| P3 (C) | `UiMotion` 扩展 + `UiPageTransition` + `UiLayoutTween` + 列表/反馈 + 设置开关 | P1（B 可并行） | C 截图清单；动画关闭；性能 |
| P4 | 全量回归 + 文档/README/CHANGELOG | 全部 | 用户验收 |

### 7.2 Feature flag

建议在 `CompPrefs.Data` 增加：

```java
public boolean dockLayout = false;      // 先默认 false，B 验收后改 true
public boolean layoutTween = true;
```

- B 层上线前用 `dockLayout=false` 保留 A 的精确像素布局；验收后改默认 true，旧工程迁移。
- 出问题可一键回退，不用回滚代码。

### 7.3 合并注意

- 工作区当前有并行修改：`UICompositorPanel.java`、`UICompTrackTimeline.java`、`UICompSettings.java`、`CompProject.java`、`CompScene.java`、`CompPathGeometry.java` 等。B 层改动前先 `git diff` 对齐；不要覆盖别人的 `isPanelResizing`/`timelineSplitterSpan`。
- `CompPathGeometry.java` 的编译错误是并行任务；测试门禁要等它修好。若它长时间不修，可临时用 `git stash`（不要 commit）或让 Lead 处理，但本任务不应改它。
- 本计划不提交 git；实现者按 Lead 的提交策略走。

---

## 8. 需要用户/Lead 拍板的取舍

| # | 问题 | 选项 | 建议 |
| --- | --- | --- | --- |
| 1 | 是否直接复用 BBS `UIDockLayout` | A. 直接复用（100% 交互，比例布局） B. 自研像素 dock（视觉/最小宽可控，工作量大） | **A**；断点 stack 补最小宽 |
| 2 | 面板宽度语义（**最关键**） | A. BBS 纯比例（1280 宽 inspector≈226，接受变窄） B. 提高 WIDE 阈值到 1680（1280 宽属性栏进 stack，不再并排） C. 自研像素 dock（保 296，工作量大） | **A 先做**；如果用户明确要 1280 宽仍 296，则改 C（B2），不要用补丁硬撑 |
| 3 | 布局存哪里 | A. 每工程 `CompSettings` B. 全局 `CompPrefs`/BBS 设置 | **A**；可加“应用到所有工程”按钮 |
| 4 | 窄窗口行为 | A. 自动切 stack B. 只提示、不自动改 C. 自动折叠左栏 | **A 仅在未自定义时自动；自定义后只提示** |
| 5 | quickPanel 是否进 dock | A. 保持 dock 外 B. 注册第五 panel | **A 先做**；B 作为后续 |
| 6 | 动效默认值 | A. 全开 1.0 B. 保守 0.7 C. 默认关 | **A**，但增加 `panelTransitions/listStagger/buttonFeedback` 三个独立开关 |
| 7 | 快捷键 | A. 复用 BBS `Ctrl+Tab/Ctrl+Space/Ctrl+Alt+Z` B. 只放在面板按钮/菜单 | **A**，但只在合成器面板可见且 `KeyAction.PRESSED` 时生效；冲突时让用户改 |
| 8 | 是否允许浮动窗口 | A. 只做 BBS 式 dock B. 自由浮动 | **A**；用户要的是“像原版”，原版没有浮动 |
| 9 | 旧工程布局迁移 | A. 按旧像素换算 B. 统一重置默认 | **A**；保留观感，失败再 fallback |
| 10 | BBS 原生控件是否换皮 | A. 不换 B. 逐步换 | **A**；与“运行时 UI 全部 BBS 原生控件”约束一致 |

---

## 9. 附录

### 9.1 BBS API 速查

| 需求 | API | 文件:行 |
| --- | --- | --- |
| 注册面板 | `dock.addPanel(id, panel, icon, label)` | `UIDockLayout.java:201` |
| 首次布局 | `dock.mount()` | `:211` |
| 布局源 | `ILayoutSource`（getRoot/setRoot/getDefault/getHiddenPanels/setHiddenPanels） | `ILayoutSource.java` |
| 锁定 | `dock.toggleLock()` / `locked(boolean)` / `isLocked()` | `:143/:305/:240` |
| 重置 | `dock.resetLayout()` | `:313` |
| 应用树 | `dock.applyLayoutRoot(root)` | `:330` |
| 读树 | `dock.getLayoutRoot()` | `:325` |
| 撤销布局 | `dock.undoLayout()` | `:342` |
| 最大化 | `dock.toggleMaximizeUnderCursor()` | `:362` |
| 循环标签 | `dock.cycleDockStackTab(±1)` | `:411` |
| 拖拽中 | `dock.isAnySplitterDragging()` | `:252` |
| 隐藏面板菜单 | `dock.fillHiddenPanelsMenu(menu)` | `:1581` |
| 面板是否活动 | `dock.isPanelActive(id)` | `:245` |
| frameless | `dock.frameless("preview")` | `:151` |
| 全局显隐 | `dock.gate(() -> ...)` | `:158` |
| 布局后回调 | `dock.onChanged(...)` / `dock.onLayoutSettled(...)` | `:166/:178` |
| 单缝 splitter | `UISplitter.fraction/pixels/measure/range/fromEnd/vertical` | `UISplitter.java:48-139` |
| 树操作 | `EditorLayoutNode.copyWithInsertSplitAt / ...AtRoot / ...StackAt / ...SwappedPanels / copyWithRemovedPanel / collectPanelIds` | `EditorLayoutNode.java:181-412` |
| 默认影片树 | `EditorLayoutNode.defaultFilmLayout()` | `:130` |
| 面板占满 | `UIDashboardPanels.setPanelPlacement` | `UIDashboardPanels.java:282-293` |
| 预览 letterbox | `UIFilmPreview.getViewport()` | `UIFilmPreview.java:351-371` |

### 9.2 bbsae 关键锚点索引

| 文件 | 锚点 | 行（当前工作区） |
| --- | --- | --- |
| `UICompositorPanel.java` | 字段（宽度/高度/handle） | 317-335 |
| 同上 | 左栏 tabs/content | 415-474 |
| 同上 | body + handles 构造 | 5288-5295 |
| 同上 | timeline/quickPanel 构造 | 5299-5307 |
| 同上 | inspector 构造 | 5030-5052 |
| 同上 | modal/start/onboarding/settings 构造 | 5426-5489 |
| 同上 | root add 顺序 | 5490-5491 |
| 同上 | `applyLayout()` | 11559-11632 |
| 同上 | `layoutHeader()` | 11717 |
| 同上 | `layoutPreviewBar()` | 11788 |
| 同上 | `savePanelLayout()` | 11845 |
| 同上 | `loadPanelLayout()` | 11894 |
| 同上 | `applyDensity()` | 11869 |
| 同上 | `PanelResizeHandle` | 11927-12009 |
| 同上 | `isPanelResizing()` | 11919-11925 |
| 同上 | `layoutKeyframeDrawer()` | 10898-10943 |
| 同上 | `tickKeyframeDrawerAnimation()` | 10965-10977 |
| 同上 | `toggleRenderView()` / `toggleFocusMode()` | 11535 / 11550 |
| 同上 | `applyLayoutPreset()` / `resetEditorLayout()` | 6560 / 6610 |
| 同上 | `render()` | 18521 |
| 同上 | 左栏 tab onSelect | 459-470 |
| 同上 | inspector tab onSelect | 5033-5047 |
| 同上 | timeline tab onSelect | 5373-5379 |
| `UICompTrackTimeline.java` | `render()` | 633 |
| 同上 | `updateAreas()` | 243 |
| 同上 | `headerWidth()` | 236 |
| 同上 | `clickHeader()` / `REORDER_GRIP` | 1444 / 61 |
| 同上 | `handleDrag()` / `endDrag()` | 1745 / 1892 |
| 同上 | `isPanelResizing` guard | 1328-1336 |
| 同上 | `updateCursor()` | 2163 |
| `UICompTrackTimeline.java` | `renderHeaderRow()` / `renderRows()` | 969 / 811 |
| `CompUi.java` | 响应式函数 | 193-303 |
| `CompUi.java` | `headerLayout()` | 310 |
| `CompUi.java` | `previewBarLayout()` | 515 |
| `CompUi.java` | `timelineSplitterSpan()` | 271 |
| `UiMotion.java` | 现有缓动/通道 | 44-267 |
| `CompPrefs.java` | Data 字段 | 60-109 |
| `UICompButton.java` | `renderSkin()` | 108-199 |
| `UICompIconButton.java` | `render()` | 32-41 |
| `UIEffectLibrary.java` | `UIEffectList` / `renderElementPart` | 293 / 331 |
| `UICompRenderJobList.java` | 类 | 18 |
| `UIImportBrowser.java` | `render()` | 259 |
| `UICurveEditor.java` | `render()` | 44 |
| `UIColorWheel.java` | `render()` | 64 |
| `UICompPresetBrowser.java` | `UIScrollViewView.render()` | 216 |
| `UILayerQuickPanel.java` | `render()` / `applyResize()` | 346 / 396 |
| `UICompSettings.java` | `select()` / `buildLayout()` | 204 / 614 |
| `UIStartScreen.java` | `render()` | 372 |
| `UIOnboarding.java` | `render()` | 146 |
| `CompDockSource.java` | 类 / `getRoot()` / `setRoot()` / `defaultLayout()` | 24 / 42 / 50 / 187 |
| `CompProject.java` | `CompSettings.dockLayout/dockHiddenPanels/dockUnlocked` | 308-321 |
| `docs/DOCK-LAYOUT-PLAN.zh.md` | B 初版实施步骤 | 全文 |

### 9.3 命令

```powershell
# 只编译
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
. .\_tools\gradle-env.ps1
.\gradlew.bat compileJava compileTestJava --offline --console=plain

# A 层纯布局测试
.\gradlew.bat test --offline --console=plain --tests "dev.bbsae.client.ui.CompositorLayoutTest"
.\gradlew.bat test --offline --console=plain --tests "dev.bbsae.client.ui.CompUiResponsiveTest"

# B 层
.\gradlew.bat test --offline --console=plain --tests "dev.bbsae.client.ui.CompDock*"

# C 层
.\gradlew.bat test --offline --console=plain --tests "dev.bbsae.client.ui.UiMotionSpringTest"
.\gradlew.bat test --offline --console=plain --tests "dev.bbsae.client.ui.motion.*"
```

**注意**：当前 `CompPathGeometry.java:308-309` 的并行编译错误会让以上命令全部停在 `compileJava`；先让并行任务修好，或由 Lead 决定临时处理。不要把“编译失败”误判为本次 UI 方案失败。

---

## 10. 最终判断（给 Lead 的 5 句话）

1. **原版真的有面板拖拽组合**：`UIDockLayout` + `EditorLayoutNode`，1.20.1 jar 已核对；`UIFilmPanel` 用 5 个 panel；它是树形停靠，不是浮动窗口。
2. **bbsae 现在没有复用**：手写绝对三栏 + 4 个 splitter；自适应只做了一半（请求值与生效值混用、无内容最小宽、短窗口有重叠）。
3. **bug 根因是命中矩形**：时间轴 4px 把手横跨整个 body，盖住左列底部；事件从后往前派发，把手先吃掉按下；工作区半修复方向正确，但要补可见性/结构化命中/单一手势 owner。
4. **B 层直接复用 `UIDockLayout`** 才能 100% 对齐原版交互；代价是比例分栏和 BBS 全局 `editorResizablePanels` 开关；用宽/中/窄三套 stack 预设补内容最小宽。
5. **C 层统一到 `UiMotion`**：spring/tween/prune + 页面滑动+veil + 列表错峰 + 按钮反馈；全部受 `CompPrefs.animations` 控制；不改 BBS 原生控件、不进文档 undo、不影响导出。
