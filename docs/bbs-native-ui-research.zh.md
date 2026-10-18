# BBS 原生 UI / 轨道调研结论（2.7-1.20.1）

这份记录只回答一件事：BBS 里哪些东西能直接用，哪些不能；避免再出现“另起一套窗口/时间轴”。

## 1. UI 底座：没有 FS.Skia.UI / Control / MVU

在本仓库的 BBS FS 2.7-1.20.1 源码里搜索 `FS.Skia`、`Skia`、`MVU`、`extends Control`，没有找到对应的 UI 框架。BBS 的界面基类是：

- `mchorse.bbs_mod.ui.framework.elements.UIElement`
- `mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel`
- `mchorse.bbs_mod.ui.framework.elements.buttons.UIButton`
- `mchorse.bbs_mod.ui.framework.elements.input.list.UIList`
- `mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor`

所以本插件不再有自定义窗口框架、自定义时间轴绘制。面板继承 `UIDashboardPanel`，图层列表继承 `UIList`，轨道区直接使用 `UIKeyframeEditor`。

POV 的做法也是同一套：POV 的每个 Action Clip 编辑器（例如
`UIBossBarActionClip`、`UIChatActionClip`、`UIGuiActionClip`）都是
`new UIKeyframeEditor((consumer) -> new UIFilmKeyframes(...))`，没有重写影片编辑器，也没有自己画关键帧菱形。它把内容挂到 BBS 的 clip/action 面板和渲染钩子上。

## 2. 轨道扩展点：能加分类/样式，不能加新的 TrackKind

`ADDONS.md` 明确写着：

> **Track kinds.** The kinds of track a film can animate are an enum. An addon's own kind of track
> (as opposed to its own animated property, which the modifier above covers) has no way in yet.

对应的 API 是：

- `RegisterTrackCategoriesEvent` / `TrackCategory`：只能把已有的 `TrackId` 归入某个分类，并参与分类按钮/快捷键排序；
- `RegisterTrackStylesEvent`：只改轨道颜色/图标/标签；
- `TimelineEvents.OVERLAY`：在原生时间轴上叠画，不改变轨道数据；
- `TrackCatalog` 是 BBS 内部遍历 **Form 的值** 来生成轨道的，不是任意 addon 对象都能注册进去。

结论：不修改 BBS 本体的情况下，**无法把“合成器图层”注册成一个新的 TrackKind**，也无法让 `TrackCatalog` 自动遍历 `CompLayer`。硬要完整复用骨骼那种父子轨道树，只有两条路：

1. 把合成器图层做成 **Form / BodyPart**，挂到隐藏的 replay 上，让 BBS 的 `TrackCatalog` 自动生成轨道；
2. 复用原生的 `UIKeyframeEditor`/`UIKeyframeSheet` 行交互，由插件提供 sheet（当前采用）。

当前实现是第 2 条：轨道行、菱形、右键菜单、插值、复制粘贴都由 BBS 画；数据是 BBS `KeyframeChannel`。这不是“自己画时间轴”，但也不是完整的 `TrackCatalog` 树。

## 3. 当前数据与 UI 对应关系

| 层 | 当前实现 |
| --- | --- |
| 面板 | `UICompositorPanel extends UIDashboardPanel` |
| 图层列表 | `UICompLayerList extends UIList<CompLayer>` |
| 轨道区 | `UIKeyframeEditor` + `UICompKeyframes extends UIKeyframes` |
| 轨道行 | `UIKeyframeSheet`（Transform / Opacity） |
| 动画值 | `KeyframeChannel<Transform>` / `KeyframeChannel<Float>` |
| 持久化 | `DataToString` + `.bbscomp` 的 `transformData` / `opacityData` |

已经删除：`UICompTimeline`（自定义时间轴绘制）、`CompKeyframe` / `CompKeyframes`（自定义线性轨道模型）。

## 4. 下一步

要让“合成器图层 == BBS 原生轨道树”真正成立，需要做 Form 化：

- 每层一个 Form/BodyPart，属性用 BBS `Value`；
- 合成器本身是一个 Form，挂到隐藏 replay；
- `TrackCatalog` 自动生成层级轨道，`RegisterTrackCategoriesEvent` 给合成器轨道加分类；
- 覆盖层只负责把 Form 的值画成屏幕空间图层。

在这之前，当前版本至少保证：不另画窗口、不另画时间轴、不另写关键帧编辑交互。
