# 实体回放模式 · 集成钩子（给 Lead / UICompositorPanel 维护者）

> 本文只描述 **Lead 需要在 `client/ui/UICompositorPanel.java` 插入的钩子**，以及已经暴露的入口 API。
> `replay` 包内部实现见 [`REPLAY-MODE-REPORT.zh.md`](REPLAY-MODE-REPORT.zh.md) 与
> [`REPLAY-MODE-PLAN.zh.md`](REPLAY-MODE-PLAN.zh.md)。

## 1. 已经暴露的入口（`dev.bbsae.client.BbsAeClient`）

```java
/** 打开面板并绑定当前影片/回放；dashboard 为 null 或没有影片时只切面板。 */
public static void openReplayMode(mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard, UICompositorPanel panel);

/** 只带 dashboard 的便捷重载（内部用 BBSModClient.getDashboard()）。 */
public static void openReplayMode();

/** 注册出来的面板实例（测试/调试可读，可能为 null）。 */
public static dev.bbsae.client.replay.UIReplayModePanel replayModePanel();
```

行为：

- `openReplayMode(dashboard, panel)` 会 `dashboard.getPanel(UIFilmPanel.class)` 取**唯一**的影片面板，
  把 `filmPanel.getData()`（影片）和 `filmPanel.replayEditor.getReplay()`（当前回放）交给
  `UIReplayModePanel.bind(...)`，然后 `dashboard.setPanel(replayModePanel)` + `UIScreen.open(dashboard)`。
- 面板不会 new 第二个 `UIFilmPanel` / `UIReplaysEditor` / `UIFilmController`；它借用现有实例。
- 没有打开影片时调用也安全：只切到面板，3D 视口显示空场景和提示。
- `panel` 参数当前仅用于签名对齐，不读它的私有状态（合成器的影片/回放从 dashboard 的 filmPanel 取）。

## 2. UICompositorPanel 需要插入的 3 行（+ 1 行 import 可选）

**锚点**：`UICompositorPanel` 构造函数里 `this.space3DButton = new UIIcon(Icons.SPHERE, ...)` 之后、
`this.preview.add(this.space3DButton);`（当前行号 ≈ 5072，以可搜索文本为准）。在这一行**后面**插入：

```java
        /* 实体回放：进入 bbsae 的 3D 回放编辑空间（原版回放编辑轨道 + 原版 gizmo + 原版模型库）。 */
        UIIcon replayModeButton = new UIIcon(Icons.PLAYER, (button) -> BbsAeClient.openReplayMode(BBSModClient.getDashboard(), this));
        replayModeButton.relative(this.preview).x(1F, -160).y(6).w(20).h(20);
        replayModeButton.tooltip(IKey.constant("实体回放：在 3D 空间里编辑影片回放（原版轨道 / 原版 gizmo / 原版模型库）"));
        this.preview.add(replayModeButton);
```

说明：

- 该行图标按钮与现有 3D 按钮同一行（现有位置 -50 / -72 / -94 / -116 / -138，下一个空位是 **-160**；
  如果 Lead 的布局重构改了这些偏移，只要保证不重叠即可）。
- `BbsAeClient`、`BBSModClient`、`IKey`、`Icons`、`UIIcon` 在 `UICompositorPanel` 里**已经 import**（无需新增）。
- 只有 4 行是因为 tooltip 也算一行；要求的最小改动是「按钮创建 + 位置 + `preview.add`」3 行，
  tooltip 可选。若 Lead 想把入口放到别处（例如顶栏），只需复制 `BbsAeClient.openReplayMode(...)`
  这一行调用即可。
- **不要**在 `UICompositorPanel` 里直接 new `UIReplayModePanel` 或自己 `dashboard.setPanel`；
  入口方法已经处理了 bind/切面板/开屏。

## 3. 主界面入口（不需要 Lead 改动）

`BbsAeClient.onDashboardPanels` 已把面板注册进 dashboard：

```java
event.dashboard.getPanels().registerPanel(replayModePanel, IKey.constant("实体回放"), Icons.PLAYER);
```

所以 dashboard 左侧/底部的面板切换条会直接出现「实体回放」一页，与「合成器」并列。

## 4. 回退路线 A（面板内已实现，无需钩子）

`UIReplayModePanel` 顶部按钮「原版回放编辑」内部执行：

```java
dashboard.setPanel(filmPanel);
filmPanel.showPanel(filmPanel.replayEditor);
```

即使 3D 编辑尚有缺口，用户一键即可得到 100% BBS 原版回放编辑体验。

## 5. 验收钩子（可选，给 Lead 真机验证用）

- 从合成器点新按钮 → 面板切换，顶部状态行显示 `影片 <id> · 回放 n/m · tick x/y`。
- 从 dashboard 面板条点「实体回放」→ 同样进入（若此前没有 `bindFromDashboard()` 绑定过影片，
  面板 `onAppear` 会自动绑定当前影片面板的影片）。
- 点「原版回放编辑」→ 回到影片编辑器并显示原版回放编辑 dock 页。
