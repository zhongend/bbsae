# 合成器 UI 架构与参考项目研究

## 结论先行

本项目最终采用 **BBS 原生 Java UI + 一块自绘 GL 预览/时间轴条**，没有把 HTML 浏览器嵌进 BBS。

不是“不会写网页所以放弃”，而是当前约束下 HTML 无法满足三个硬条件：

1. **BBS 的预览框不是 DOM，而是 BBS 世界渲染后的一张 GL framebuffer。**
   BBS 的镜头、世界、录像和 F6 导出都在 OpenGL 路径里。HTML 页面如果要“在预览框上面”，
   需要浏览器把透明纹理合成到 BBS 的 framebuffer 上，同时把鼠标/键盘事件准确转交给
   BBS 的镜头和 timeline；这不是一个 `<div>` 能解决的。
2. **MC 没有自带浏览器。** Fabric 1.20.1 里要用 Chromium，只能依赖
   [MCEF](https://github.com/mc-wiki/mcef) 这类 **原生 CEF** 库。它需要额外的 native
   Chromium、平台包和输入/窗口集成，体积、启动时间、崩溃面、与 Sodium/Iris 的组合都不可控，
   不适合作为一个“合成器功能”偷偷塞进 jar。
3. **原生 UI 的收益是实打实的。** BBS 已经提供 `UIDashboardPanel`、`UISection`、
   `UITabStrip`、`UIList`、`UIKeyframeEditor`、`UIKeyframeDopeSheet`、`ContextMenuManager`、
   `UIColor`、`UITrackpad`、`UIFileDialogs`，以及 film/replay/clip 数据模型。继续用它们可以
   直接复用 BBS 的输入、撤销输入行为、镜头、F6 导出和资源管理；换成 HTML 等于把这些重新实现一遍。

因此：**HTML 可以作为设计原型/外部工具，但不会作为运行时 UI。**

## 参考项目研究

| 项目 | 主要价值 | 本项目的取舍 |
| --- | --- | --- |
| [EffectCraft](https://github.com/storytold/effectcraft) | 纯 Rust/egui 的 AE 重实现；Project/Composition/Layer 层级、Timeline 左侧属性树 + 右侧标尺/CTI、Effect Controls、Render Queue | 复用了**信息架构和工作流**，没有复用 Rust 代码；时间轴标尺、当前时间、Fit、Render Queue 都是这个思路 |
| [BBS Refreshed](https://github.com/quaIett/bbs-refreshed-addon) | 通过 mixin 给 BBS 做整体视觉刷新：圆角面板、字体、动画、主题 | 没有引入它的全局主题依赖（会改变整个 BBS）；只借鉴了“紧凑工具栏 + 卡片式分区”的视觉原则 |
| [BBS POV](https://github.com/busyflow/bbs-pov-addon) | POV 编辑器的分栏、icon/section 切换、原生 keyframe editor 的空状态/过滤/undo、film timeline 集成 | 直接借鉴了 **inspector 分页**、**timeline 空状态提示**、**timeline undo id**、**在原生 keyframe editor 上叠加业务时间轴** 的做法 |
| [BBSVFX](https://github.com/xavineditor/BBSVFX) | 效果模块化、可开关、预设、表单/渲染分离、镜头导出 | 复用了“效果注册表 + 开关 + 预设”的模块化思路；没有搬它的 form/VFX 渲染栈 |
| [Wemppy4/bbs-fs](https://github.com/Wemppy4/bbs-fs) | BBS 的常用 fork，用于核对 1.20.1 API 和 UI 习惯 | 作为 BBS API 行为参考；没有引入第二套 BBS |
| [Photon](https://github.com/Low-Drag-MC/Photon) | 可停靠的层级/场景/时间轴编辑器，实时 SceneView，独立编辑器后处理栈（HDR/bloom/mask） | 代码不可直接移植（NeoForge 1.21.1 + LDLib2）；借鉴了“独立编辑器 + 自己的预览 FBO + FX/Mask 预览开关”的结构。详见 [photon-research.zh.md](photon-research.zh.md) |

## 当前合成器布局

```
┌────────────────────────────────────────────────────────────────────┐
│ BBS Compositor │ 影片/工程 │ Replay ▾ │ Save + Play ◀ ▶ 时间码 │ Export Render Focus │
├───────────┬──────────────────────────────────┬─────────────────────┤
│ Layers    │ Composition Preview              │ Inspector Tabs      │
│ (UIList)  │ - 合成结果 GL FBO                │ Comp / Layer / FX   │
│           │ - 选择框/四角 handle             │ Mask / Text / Out   │
│           │ - 拖动选中层                     │ (UIScrollView)      │
├───────────┴──────────────────────────────────┴─────────────────────┤
│ - / + / Fit │ 可拖动 scrubber + 秒刻度 + 当前时间 + playhead       │
│ BBS UIKeyframeEditor: dope sheet / graph / 关键帧菱形 / 右键菜单   │
└────────────────────────────────────────────────────────────────────┘
```

- **Inspector 不再是无限长滚动**：`UITabStrip` 把 Comp / Layer / FX / Mask / Text / Out 分开。
- **Focus**：隐藏层栈和检查器，只保留预览和时间轴。
- **Replay 选择器**：点击打开 BBS 原生 `UIListOverlayPanel`，列出当前影片的所有 replay 以及其它已保存影片；选择 replay 切换该 replay 的 composition，选择 Film 切换影片工程。
- **每个 replay 独立 composition**：工程仍按影片隔离，composition id 形如
  `replay_<replayId>`，避免不同 replay 的图层互相污染。
- **Timeline**：顶部 `- / + / Fit`，中间 scrubber，底部仍是 BBS 原生
  `UIKeyframeEditor`。原生菱形拖动、插值、复制粘贴、右键菜单保留。
- **Preview**：选中层有边框和四角 handle；按住左键可拖动选中层，有 transform 关键帧时
  自动在播放头写入关键帧，否则改静态 x/y。预览来自合成器自己的 `CompRenderer` FBO，
  不是 BBS 影片编辑器覆盖层；左上角的 FX/Mask 开关可对比效果/蒙版。
- **空状态**：没有可关键帧属性时显示 “No keyframes + 如何开始”，不再是一片纯黑。

> 重要边界：合成器是**独立 dashboard panel**，不会把界面 full overlay 到 BBS 影片编辑器上。
> 影片编辑器保留自己的世界预览、replay 列表、camera/replay 轨道和属性 dock；合成器拥有自己的
> 预览 FBO、图层栈、检查器和 BBS `UIKeyframeEditor`。这是本轮根据反馈明确修正的架构。

## 为什么不是“在 BBS 里塞一个 HTML 覆盖层”

如果未来一定要做，可行的最小实验是：

1. 单独做一个 MCEF 实验分支，不进入正式 jar；
2. 用 MCEF 的 browser texture，开启透明背景，在 `onRenderBeforeScreen` 之后画在 BBS 预览之上；
3. 把 BBS 的鼠标坐标转换为网页坐标，转发点击/滚轮；
4. 网页只做“面板 UI”，所有影片/合成/关键帧操作通过 WebSocket/Java bridge 调回 mod；
5. 接受额外的 ~100MB Chromium 运行时和 MCEF 版本兼容风险。

这仍然不能解决 **HTML 和 BBS 世界 framebuffer 的透明合成**、**BBS 原生 keyframe 拖拽**
和 **F6 导出路径**，所以它不是本项目当前的正确工程选择。

## 后续优先级

1. 多 replay 的 composition clip 启用/禁用策略（避免多个 replay 同时叠加）；
2. 时间轴中的 layer duration bar 与 composition marker；
3. 预览缩放/平移与 snapping；
4. 素材拖放导入和媒体库；
5. 全局 undo 栈。

## 参考链接

- EffectCraft: <https://github.com/storytold/effectcraft>
- BBS Refreshed: <https://github.com/quaIett/bbs-refreshed-addon>
- BBS POV: <https://github.com/busyflow/bbs-pov-addon>
- BBSVFX: <https://github.com/xavineditor/BBSVFX>
- Wemppy4 BBS fork: <https://github.com/Wemppy4/bbs-fs>
- MCEF: <https://github.com/mc-wiki/mcef>
