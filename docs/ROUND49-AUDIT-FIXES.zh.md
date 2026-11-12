# Round 49 — 系统性 bug 审计与修复（v0.94）

> 目标（需求 7 的核心）：**假设所有功能都有 bug**，按第一性原理逐层审计「效果数据层 / 合成器
> 交互层 / 深度 / 回放 / 钢笔 / 动效」，修掉影响操作的 P1，并把能无头化的验证全部写成测试。
>
> 验证：**110 个测试套件 / 669 项测试全绿**（0.93 基线 96/565，1 skipped）；窄窗口真机截图
> `docs/verify/round49-narrow.png`。
>
> 构建：ASCII junction + 串行 wrapper
> `powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 <task> --offline --console=plain`。
> 本轮严格限制客户端运行：只跑 1 次窄窗口截图验证（用户要求减少截图测试）。

---

## 0. 方法与分工

| 方向 | 方式 | 产出 |
| --- | --- | --- |
| 合成器交互层 | 只读审计 subagent 逐条给 file:line 证据 | 4 个 P1 + 7 个 P2（本轮全部修完 P1、大部分 P2） |
| 效果/时间轴数据流 | 只读审计 subagent | 4 个 P1（shake 参数、预设通道、曲线 fx 写回、id 复用）+ 2 个跨效果正确性（REPLICATE alpha、proxy 像素单位） |
| 深度 | depth 队友 | 1/255 残差清理、状态恢复/池化健壮性、17 个开关路径测试 |
| 回放 | replay 队友 | 10 个数据层修复 + 33 tests |
| 钢笔 | pen 队友 | loops 保真 / Set First Vertex / 2 个真机前 bug |
| 效果批 | effects 队友 | 13 个 AM P1 效果 + 2 个跨效果修复 |
| 动效 | motion 队友 | 剩余页面 + 关闭过渡 + 全局 prune |

所有队友**不开客户端**，只用串行 wrapper 跑编译/测试；Lead 负责集成、UI 宿主接线、审计修复、
真机验证与发布。

---

## 1. 合成器交互层：4 个 P1

### 1.1 焦点模式布局被写进工程（最严重，用户会以为"面板坏了"）

- **现象**：按 F 进聚焦 → 1 秒内（250ms 的 dock flush + 600ms 保存防抖）关闭面板/切工程/退出游戏
  → 重开工程只剩「预览 + 时间轴」，效果/属性面板消失；只有在「布局」右键菜单里才能恢复。
- **根因**：`applyDockFocusMode()` 的临时树通过 `CompDockSource.setRoot/setHiddenPanels` 走持久化
  通道，并置 `dockLayoutDirty = true` → `flushDockLayout() → dockSource.flush() → saveUiState()`
  把焦点树写进 `CompSettings.dockLayout/dockHiddenPanels`。
- **修复**：`CompDockSource.suspendPersist(true)` 在焦点模式期间持久化挂起（树上仍可正常布局），
  退出焦点/应用预设/切换工程/关闭面板前恢复；`dockLayoutDirty` 在挂起期间不再置位；
  关闭/消失回调先 `restoreFocusBeforePersist()` 再 `flushSave()`。
- **验证**：代码路径审查 + 现有 `CompDockSourceTest`；焦点模式不再产生 `dockLayout` 写入
  （`-DbbsaeDebugDock=true` 日志可见 settled 但无 flush 日志）。

### 1.2 预览右上角图标列压住预览条（点 A 执行 B）

- **现象**：preview 宽度 <458px（legacy 面板宽 <954、dock 把预览拖窄、窄窗口）时，摄像机/钢笔/
  回放图标与浮动预览条同一 20px 行重叠，点「线性：自动」实际触发摄像机设置或摄像机工具；
  preview <160px 时图标甚至跑到左侧效果列上方抢点击。
- **根因**：图标固定 `1F-28-22n`，预览条固定 `x=6,w=min(292,previewW-12)`，无碰撞处理。
- **修复**：新增纯函数 `CompUi.previewRailLayout(previewWidth)`：从最右侧按 22px 槽位放图标，
  预留预览条最小宽度（150px），放不下的图标隐藏（`show=false, x=-1`）；`layoutPreviewBar` 用
  剩余宽度计算预览条；`applyPreviewRail` 统一定位/显隐。AE 编码器徽标移到第二行避免同列重叠。
- **验证**：`CompUiResponsiveTest.previewRailNeverCoversThePreviewBar` 扫描 40–900px，断言
  图标不出界、不互相重叠、预览条与最左图标不重叠；窄窗口真机截图。

### 1.3 关键帧抽屉把轨道区挤成负高度

- **现象**：dock + 聚焦（时间轴仅 16% 高）或矮时间轴时打开关键帧抽屉，轨道高度变负 → 轨道消失，
  抽屉盖住时间轴工具条，看上去"时间轴坏了"。
- **根因**：抽屉高度上限用 `timelineHeight - 96` 拍脑袋，没扣 26px 标题、最小轨道行和 5px 手柄。
- **修复**：`max = timelineHeight - 26 - 56 - 5`；若 `max < 96` 自动收起抽屉并提示
  「时间轴太矮，关键帧抽屉已收起」。`MIN_TRACKS_HEIGHT = 56`。

### 1.4 Esc 优先级错误（飞行/摄像机工具/轨道拖拽）

- **现象**：BBS 摄像机飞行中按 Esc → 飞行不退出、选择被清空；AE 摄像机工具拖动中 Esc → 拖动继续；
  时间轴拖片段中 Esc → 继续跟手并在松手后提交，或只是关掉关键帧抽屉。
- **根因**：通用 Esc 阶梯（弹层→抽屉→预合成→选择）在相机飞行/工具判断之前执行；轨道拖拽没有取消语义。
- **修复**：显式优先级链，插在钢笔之后、通用阶梯之前：
  1) 摄像机飞行 → 退出；2) 摄像机工具拖动 → 结束；3) 时间轴拖拽 → `UICompTrackTimeline.cancelDrag()`
  （按拖动起点恢复 in/out、音频 start/offset/duration、工作区 in/out，不提交）；4) AE 摄像机工具开着
  → 关闭工具；然后才走弹层/抽屉/预合成/选择。
- **验证**：逻辑审查 + 现有 UI 测试；`cancelDrag()` 新增代码与原拖动字段一一对应。

---

## 2. 合成器交互层：P2 修复（本轮一并做完）

- **切换工程前 `flushSave()`**：`bindComposition/bindStandalone` 先写旧工程再换 `this.project`，
  600ms 防抖窗口内的编辑不再串写进新工程或丢失。
- **撤销快照剥离工作区**：`snapshotForUndo()` 临时清空 dockLayout/面板宽高/相机预览/3D 视口位姿等
  工作区字段再序列化；`applySnapshot()` 保留当前工作区并 `dockSource.reload()+dock.refresh()+applyLayout()`。
  Ctrl+Z 只回退内容，不再把面板跳回旧布局/隐藏。
- **`applyLayoutPreset` 复位焦点状态**：避免"焦点中应用预设→按 F 把预设覆盖回去"。
- **legacy 渲染队列页高度**：`renderPanel.h(renderView ? area.h-42 : bodyHeight)`，页面不再只占半屏。
- **`UICompTabBar` 父区域命中守卫**：绝对定位的 tab 不被裁剪，窄面板溢出时不再抢邻居面板的点击
  （检查器 7 个 tab、时间轴 tab、左栏 tab 都受益）。
- **`UiPageTransition` 首帧不再闪黑纱**：构造时 `elapsed = duration`（此前第一次打开面板会误画
  0.16s 的 28% 黑纱）。
- **持久化补全**：快捷面板宽度拖动、时间轴页签切换立即 `saveUiState()`（dock 用户不触发
  `savePanelLayout` 导致重开丢失）。
- **顶栏「效果库 / 工程」纳入响应式布局**：此前固定 `1F-456/1F-396`，窄窗口压住播放/上一帧；
  现参与右簇碰撞回退（save→export→render→timecode→frames→replay→title→library→projects→play），
  并新增单测锁住。
- **dock 快捷键补齐 BBS 原版**：Ctrl+Tab / Ctrl+Shift+Tab 循环堆叠标签、Ctrl+Alt+Z 撤销布局、
  Ctrl+空格 最大化；布局菜单同步提示。

---

## 3. 图层效果数据层：4 个 P1 + 2 个跨效果正确性

### 3.1 抖动/震屏参数完全无效（只有关键帧生效）

- **根因**：`CompScene.shakeOf()` 用 `layer.effectParam()` → `scalarAt()` 无通道时只返回字面 fallback，
  永不读 `CompEffect.params`；其它效果都走 `resolveEffect()`。副作用：3D 烘焙 padding 按真实
  amplitude 计算，raster 变大但画面不抖。
- **修复**：`CompLayer.effectValue(effect, key, fallback, frame)` = `resolveEffect()`（基值 + fx 通道
  覆盖）→ `valueF`；shake 5 个参数全部改走它；旧 `effectParam` 标 `@Deprecated` 并委托。
- **测试**：`ShakeEffectValueTest` 6 例（amplitude=200 无通道时平移 >50px，旧实现最多 18px）。

### 3.2 动画预设清空/注入内容通道

- **根因**：`CompAnimationPreset.capture()` 复制 `layer.channels` 全表（含 fx/mask/shape/text），
  `applyTo()` 直接 `clear()+putAll()` → 一键清掉目标图层的蒙版/形状/效果动画，并把源层 fx 注入。
- **修复**：`isPresetChannel()` 白名单（排除 `fx/ mask/ shape/ text/` 前缀）；capture 只采预设通道；
  applyTo 先 `saveTracks()`、保留目标内容通道再合并预设通道；旧格式预设应用时同样过滤。
- **测试**：`CompAnimationPresetTest` 5 例。

### 3.3 曲线编辑器改 fx 关键帧不写回

- **根因**：`CompCurveChannel.insert/setKeyValue` 只 `layer.putScalar()`，而 `putScalar` 对 `fx/`
  前缀是 no-op → 曲线里拖"模糊半径"关键帧值，删光关键帧后参数回到旧基值。
- **修复**：`writeBaseValue()` 对 `fx/<id>/<param>` 镜像到 `effect.put()`；mask/shape 路径确认支持；
  顺手补 `shape/<id>/fillGradientAngle` 的 `putNestedScalar/nestedScalar` 读写（此前可显示但被吞）。
- **测试**：`CompCurveChannelFxWritebackTest` 4 例。

### 3.4 删除效果留下 fx 通道 + id 复用

- **根因**：删除效果只删 List 元素，`fx/<id>/*` 通道变孤儿；`uniqueEffectId()` 只按 effect 找最小
  空号 → 新效果拿到同 id 后被旧关键帧"劫持"，且孤儿轨道永远删不掉、3D 图层每帧重烘焙。
- **修复**：删除效果/移除调色时 `removeEffectChannels()` 清 `fx/<id>/` 全部通道（`saveTracks()`
  在保存/快照时重建序列化表）；`CompStore.hasEffectChannels` + `uniqueEffectId` 跳过仍有通道的 id。
- **测试**：`CompStoreEffectIdTest` 6 例；删除路径接入清理。

### 3.5 跨效果正确性（预览 = 导出）

- **REPLICATE 直通 alpha**：复制器家族把直通 alpha 当预乘 → 不透明度 0.5 复制体发黑、文字/发光软边
  重复后黑边。改为预乘空间累加 + 输出 un-premultiply；`EffectRepeatTest` 新增 2 个像素测试。
- **预览 proxy 像素单位**：RGB 分离 Texel、投影/内阴影偏移、图层样式 `inner()` 距离/模糊半径统一按
  合成像素换算（`PassRenderer` 新增参考尺寸重载 + `EffectRenderer.proxyScale`）；
  `PreviewProxyTest` 纯函数 + `EffectPipelineTest` 1:1 vs 0.5 渲染一致测试。

---

## 4. 逐像素深度收尾

- 1/255 残差：删除 `DEPTH_PLANE` 的 `if (source.a < 0.004) discard;`，回归测试容差 1→0。
- GL 状态全部改走 `GlStateManager`（消除"实际状态变、缓存没变"同类根因）；异常路径 finally 恢复
  depth/blend/colorMask 并绑回调用者 framebuffer+viewport；池化 target 加 `pooled/deleted` 守卫。
- 17 个开关路径测试；`CompPrefs.perPixelDepth` 默认仍 false，但设置 → 开发者新增开关（即时生效、
  清预渲染缓存），用户可自行开启。

---

## 5. 回放 / 钢笔 / 动效

- **回放**：10 个数据层 bug（内容等值选错回放、undo 后陈旧对象写回脱管记录、等长列表变更检测、
  NaN tick/framing、坏相机矩阵全裁剪、pick FBO 异常崩溃 UI、pick id 公式、自动关键帧错 tick、
  空状态噪音）；replay 包 71 tests。仍待真机：gizmo 拖动写回+原版 undo、模型库换模型、列表
  增删/重排 UI、播放头边界 seek（报告 §7.1）。
- **钢笔**：KeyframeLoop 保真、Set First Vertex（闭合路径、通道轮转、1 步 undo）、修复「动画路径
  命中读 base 而非当前帧」和「Shift 45° 双重约束」；钢笔 10 类 90 tests。
- **动效**：`UiMotion` 全局 prune；资源/图层库/开始页/引导页入场；导入/效果浮层关闭淡出
  （`beginClose` + 渲染结束后回调移除）；动效测试 27 个。

---

## 6. 验证记录

| 项目 | 结果 |
| --- | --- |
| 全量测试 | **110 suites / 669 tests / 0 failures / 1 skipped**（0.93: 96/565） |
| 效果像素测试 | 13 个新效果逐条「默认参数改变画面 + 中性配置两条路径逐像素恒等」 |
| 深度 | `DepthPlane*`/`PerPixelDepth*` 25/25；ghost 回归容差 0 |
| 回放 | replay 包 71 tests（+33） |
| 钢笔 | 10 类 90 tests（+16） |
| 动效 | 27 个纯逻辑测试 |
| 真机 | `docs/verify/round49-narrow.png`：1000×700 窗口下顶栏不重叠、图标列按宽度降级、
停靠面板仍可用 |

## 7. 剩余风险 / 下一轮候选

1. 回放交互类真机验证（gizmo 拖动/undo、模型库换模型、列表增删、播放头边界）。
2. `perPixelDepth` 真机性能确认后再决定是否默认开启；极端 near/far 的 24bit 深度抽样。
3. 效果：AM 剩余 54 条（变形族剩余、文字 6 个、`repeat.path`）；缩略图生成器偶发退化需观察。
4. 窄面板 tab 溢出目前只保证"点击不外泄"，未来可做 `»` 溢出菜单/横向滚动。
5. `UICurveEditor`（时间轴曲线页）与 BBS 原生控件内部动效未覆盖。
6. 钢笔：逐点羽化、RotoBezier、Free Transform、Contents 组。
7. 预设对 transform 是整族替换（无部分应用语义）——与旧行为一致，若要做需新语义。
