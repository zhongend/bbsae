# Round 48 — 停靠面板 / 深度重影 / AM 效果 / 钢笔动画 / 回放验证 / UI 动效（v0.93）

> 目标：从第一性原理完成用户八条需求里剩余的部分。本轮重点是把 0.92 留下的四个硬问题
> 收尾：面板拖拽组合（8-B）、逐像素深度重影本体（3）、AM repeat/调色批（2-d）、钢笔动画路径
> 增删点（6 收尾），并补上实体回放真机验证能力（5 收尾）与 12 个 UI 文件的非线性动效（8-C）。
>
> 验证：**96 个测试套件 / 565 项测试全绿（0 failures, 1 skipped）**（0.92 基线 90 / 511），真机截图
> `docs/verify/round48-dock-layout.png`、`round48-dock-stack.png`、`round48-depth-on.png`、
> `round48-replay-models.png`。
>
> 构建：必须走 ASCII junction + 串行 wrapper
> `powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 <task> --offline --console=plain`
> （真实路径含空格/中文会让 Gradle test worker 失败）。

---

## 1. 需求 8-B：面板像 BBS 原版一样可停靠、组合

### 1.1 结论先行

BBS 2.7 **自带完整停靠布局系统** `mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout`
（`UIFilmPanel` 已在用：分栏拖拽、拖拽停靠、标签堆叠、隐藏面板、布局锁、布局撤销、最大化）。
「像原版一样操作」= 直接复用这套系统的行为，而不是自研一套仿品。

### 1.2 实现

| 文件 | 作用 |
| --- | --- |
| `client/ui/CompDockSource.java`（新） | `ILayoutSource` → `CompSettings.dockLayout/dockHiddenPanels/dockUnlocked`；settings 用 Supplier（面板先于工程存在），项目切换 `reload()`；`setRoot` 只缓存，`flush()` 批量写；默认/聚焦/4 套工作区预设树 |
| `UICompositorPanel` | 构造时注册 4 个 dock 面板（效果/素材、预览、属性、时间轴）并 `mount()`；`applyDockLayout()` 只摆 header/status/渲染队列页；`resize()` 里 `dock.setupFlex(true)`（带 `layoutPass` 重入 guard），`syncDockDerivedLayout()` 摆预览栏/快捷面板/关键帧抽屉；顶栏「布局」按钮（左键锁/解锁、右键菜单：重置/撤销/最大化/恢复隐藏面板）；Ctrl+Alt+Z 撤销布局、Ctrl+空格 最大化；聚焦模式用临时树；工作区预设映射 dock 树 |
| `CompPrefs`/`UICompSettings` | 设置 → 开发者 →「停靠式面板（BBS 原版）」开关（默认开；切换后重开面板生效）；关掉回退旧版固定三栏 |
| `CompUi` + 测试 | 顶栏右侧集群新增「布局」按钮，`headerLayout` 几何 + 单测防重叠 |

关键设计取舍：

- **布局变化不是文档编辑**：`savePanelLayout()` 改走 `saveUiState()`（只防抖写盘），不置
  `documentDirty`、不产生 undo、不清预渲染缓存。拖分栏不会污染撤销历史。
- **写盘批量**：`UIDockLayout` 每个 drag 帧都会 `setRoot`；源只缓存树，拖拽结束后节流 250ms
  才 `flush()` 编码一次，避免每帧 JSON 序列化。
- **重入 guard**：`setupFlex → dock.resize → slot.resize → panel.resize` 会回到本面板；
  `layoutPass` 保证只有最外层跑 flex（否则递归）。
- **快速面板**留在时间轴面板内部右侧（随选择显隐、随面板拖动），不单独注册 dock 面板。
- 旧的固定三栏 splitter 完整保留为 `applyLegacyLayout()`，可由开发者开关回退。

### 1.3 真机验证

- `round48-dock-layout.png`：默认停靠布局（左效果栏 / 中预览 / 右属性 / 下时间轴 + 时间轴内右侧
  快速面板），顶栏「布局」按钮可见。
- `round48-dock-stack.png`：`-PbbsaeView=docktest` 把左栏与属性栏堆叠成标签、解锁布局；截图显示
  标签条 + 解锁拖拽条 + 面板内容正常渲染。

### 1.4 已知差异/限制

- dock 是**比例分栏**（BBS 原版同款），窗口很窄时属性栏会比旧版 296px 下限更窄；需要像素下限
  请用工作区预设或开发者开关回退旧布局。
- 面板显隐（聚焦/渲染模式）在 dock 下通过隐藏面板/临时树实现，不删面板注册。
- 布局预设（标准/聚焦/紧凑/宽屏）在 dock 下改变比例树；聚焦预设会隐藏左/右栏，恢复预设时再显示。

---

## 2. 需求 3：逐像素深度重影本体修复

### 2.1 根因（无头 GL 复现 + 修复前失败测试钉死）

不是 DEPTH_PLANE 数学错，而是 `DepthPlaneCompositor` 复用的 FBO **颜色附件从未被真正清空**：

1. 深度预写 pass 用裸 `GL11.glColorMask(false,false,false,false)`；实际颜色写被关，但
   `GlStateManager` 的 COLOR_MASK 缓存仍是 true（1.20.1 `_colorMask` 是缓存短路，javap 确认）；
2. 随后 `target.clearColorOnly(0)` → `bindOnly()` 的 `_colorMask(true,…)` 被缓存跳过 →
   `glClear(GL_COLOR_BUFFER_BIT)` 在 mask=false 下静默无效；
3. target 池化复用（acquire/release 只清深度不清颜色），每层末尾 `PassRenderer.draw(target,
   accumulation)` 又把「当前层 ∪ 历史所有层/帧残留」整幅合成一次。
   → 摄像机移动/编辑时轮廓按历史位姿/尺度反复叠加 = 星形/多边形重复 + 半透明边缘条带。
   静态不透明画面看起来正常，所以之前静态验证容易漏。

### 2.2 修复

- 每次 composite 前真正清颜色（深度保留跨层共享）；target 写入期间显式关混合（混合只由
  `PassRenderer.draw` 做一次）；颜色掩码改走 `GlStateManager`；保存/恢复
  DEPTH_TEST/DEPTH_FUNC/DEPTH_WRITEMASK/BLEND；acquire/release/构造都清颜色+深度。
- `GlFramebuffer.clearColorOnly()`：先裸 `glColorMask(true)` 再同步 GlStateManager 缓存，
  任何「裸 glColorMask(false) 后清颜色」都不再被短路。

### 2.3 证据

- 新增 `DepthPlaneGhostRegressionTest`（4 个像素级回归，真实 `____bug` 相机/图层数据 +
  真实 ShapeCache + 生产 PassRenderer/unpremultiply）：修复前 4/4 失败（如池化 target 残留断言
  `expected 0xFF000000 was 0xFFFF0000`；半透明层 41472 通道值差 >2、最大 70），修复后 4/4 通过。
- 真机 A/B：`round48-depth-on.png`（`-PbbsaeForceDepthPlane=true` + `____bug` 工程相机预览），
  星形/六边形单帧干净、无重复轮廓。

### 2.4 默认值建议

`perPixelDepth` 仍默认 **false**（本轮未改；用户在真机确认性能/画面前不擅自开）。修复后跨帧/
跨层/池复用/半透明语义与关闭路径一致，建议真机确认后改回 true。剩余非重影：DEPTH_PLANE 的
`if(source.a<0.004) discard;` 会丢 alpha=1/255 抗锯齿边（约 32 像素×1 步），可后续删掉并把测试
容差收紧到 0。详见 `docs/DEPTH-GHOST-FIX.zh.md`。

---

## 3. 需求 2-d：AM repeat 家族 + 调色首批

- 5 个数据驱动效果（`AmEffectDef.neutralAt` 新机制支持「恒等不是全 0」）：
  `am_repeat` / `am_repeat_radial` / `am_repeat_scatter`（合成空间 REPLICATE Mode 3/4/5，
  2D/3D 真正无限、不裁图层）；`am_rgb_mixer` / `am_solid_color`（严格零点）。
- 缩略图生成器修复：拒绝全空/近乎单色退化预览，失败时缩小像素参数重试；重生成 5 张新 + 修正
  4 张旧退化缩略图。
- 审计：AM 127 定义 / 自动匹配 94 / 真正缺失 64（`docs/EFFECTS-AM-GAP.zh.md` + `.generated.md`）。
- 未完成：`repeat.path`（需路径几何进效果参数域）、剩余模糊/变形/文字批。

---

## 4. 需求 6 收尾：钢笔动画路径增删点安全

- 已动画路径增/删点不再拒绝：收集受影响段端点 8 通道的所有关键 tick ∪ 播放头 → 逐 tick 求值
  → de Casteljau 保形分割 → 回写点/手柄；未受影响点通道整体搬移（tick/值/插值/手柄/style/
  duration 全保留）。每个既有动画键处曲线逐像素不变（`AnimatedPathRemapTest`，点到折线距离
  <0.05px）。
- 末尾加点按 effectcraft `CountOp::Insert` 规则；删点清该点通道、后续索引下移、键帧值精确不变；
  `<3` 点自动断开闭合；每次增删点一步 undo。
- 检查器按钮接入 `appendMaskPoint/appendShapePoint/removeMaskPoint/removeShapePoint`（动画安全）。
- 已知近似：键与键之间是「重映射后逐通道插值」，键帧处精确；点通道 KeyframeLoop 搬移会丢失。
  详见 `docs/PEN-TOOL-REPORT.zh.md` §9/§10。

---

## 5. 需求 5 收尾：实体回放真机验证/性能/空状态

- `ReplayDebugHook`：`-PbbsaeView=replay` 在 60 tick 自动填充影片并打开实体回放页；影片来源
  debugReplayFilm → 磁盘有 replay 的影片 → 内存合成 3 个原版生物 replay。
- `ReplayCulling`（视锥裁剪，选中永远画、颜色/拾取共用可见性）、`ReplayLogLimiter`（5s 限流）、
  `ReplayCalibration`（全部系统属性可覆盖、严格解析、HUD 显示裁剪计数）。
- 空状态/健壮性：无影片/无 replay/未选中/无模型/视野外/FBO 失败均有可读文案；修复换影片再进面板
  不重绑；修复「鼠标不在视口内截不到 gizmo」。
- **P0 崩溃修复**：`selectReplay` → BBS `scrollToReplay` 同步触发列表回调 → 再 `selectReplay`
  → StackOverflowError（点第一条 replay 就崩）。修复：列表回调走不滚动的 `selectReplayFromList`，
  程序化选择走 `selectReplay`（滚动），统一 re-entrancy guard。
- 真机截图 `round48-replay-models.png`；剩余标定风险见 `docs/REPLAY-MODE-REPORT.zh.md` §9。

---

## 6. 需求 8-C：非线性动效覆盖（12 文件）

按钮/图标/tab、效果库、渲染队列、导入浏览器、预设卡片、曲线编辑器、色轮、轨道时间轴
clip bar/吸附线、快捷面板、效果浮层统一接入 `UiMotion`：hover 插值、按下 spring 回弹、错峰入场、
flash/呼吸；`animations=false` 立即归位；每 60 帧 prune；不改 BBS 原生控件、不改命中区域、
不写工程数据。新增 10 个纯逻辑测试。详见 `docs/UI-MOTION-REPORT.zh.md`。

---

## 7. 回归与版本

- 0.92 基线：90 suites / 511 tests（1 skip）。本轮结束：**96 suites / 565 tests / 0 fail / 1 skip**。
- 版本：`mod_version=0.93`；jar `bbsae-0.93.jar`（大小/SHA-256 见 README 与 release 页）。
- 发布前检查：`gradle-run.ps1 test --offline`、`gradle-run.ps1 build -x test --offline`、
  `runClientView` 真机截图（dock/replay/depth）、`git status` 无临时文件。

## 8. 剩余工作（下一轮候选）

1. `perPixelDepth` 真机确认后默认开启（含 1/255 shader 残差清理）。
2. AM 缺失 64 条里的剩余模糊/变形/文字批；`repeat.path` 需路径几何接口。
3. dock 模式下的像素级最小宽度（窄窗口属性栏）与快速面板可独立停靠（可作为第 5 个 dock 面板）。
4. 回放模式真机标定值写回默认（models/camera 视觉对齐）。
5. `UICompCurveEditor`（时间轴曲线页）动效未覆盖；导入浏览器/效果浮层关闭过渡需宿主延迟移除。
6. 钢笔：Set First Vertex / 逐点羽化 / 保持顶点数开关。
