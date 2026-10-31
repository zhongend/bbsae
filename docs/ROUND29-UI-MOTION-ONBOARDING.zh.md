# Round 29 — 新手引导、BBS 风格设置、可编辑快捷键与全局非线性动效（v0.18.0）

> 目标：把“能用”的合成器 UI 提升到“愿意每天用”的编辑器 UI。参考
> `DADDDADADD.rich.zh.md` 的运动语言，但不照搬它的每个数值；以 BBS 自己的
> onboarding / settings / landing backdrop 为视觉基准。

## 0. 结果

- 新增 5 页新手引导：欢迎、工作区布局、动效与光影、快捷键方案、完成。
- 新增 BBS 2.7 风格设置：左侧分类 + 搜索、右侧滚动选项、分区标题、分段选择。
- 新增可编辑快捷键：每个命令一个 action，点击键帽录制，支持清除、取消、冲突提示、全部重置。
- 新增 `UiMotion` / `UiGfx` / `UiBackdrop`：按钮、页签、卡片、素材格、轨道选择、浮层入场、
  引导背景统一使用非线性缓动。
- 设置中可以关闭动效、调强度/速度、关辉光/阴影、调引导背景亮度。
- 版本 `0.18.0`；测试从 193 增至 197，全部通过。

## 1. 第一性原则

### 1.1 动效是绘制层状态，不是文档状态

BBS 的 `UIElement` 是 retained tree，但 `Batcher2D` 每帧立即重绘。因此：

- 不给 `CompProject` / `CompDocument` 增加任何“动画时间”字段；
- 不在 `CompRenderer`、`client/render/`、帧覆盖层、F6 导出里做任何过渡；
- 所有过渡只读取每一帧的 `System.nanoTime()` 和鼠标/选择状态，**不写工程**；
- 不引入 GL 状态、自定义 shader、framebuffer、HTML/MCEF；
- 所有 clip/unclip 成对，异常路径也恢复裁切。

这样动画不会改变导出像素，也不会让 BBS 的撤销/保存把过渡状态写进 `.dat` 或 `.bbscomp`。

### 1.2 一层的运动，而不是每个控件各自发明

`UiMotion` 是每个宿主自己的时钟和通道表：

- `anim(key, target, speed)`：指数逼近，用于 hover / selection / scroll；
- `intro(delay)` / `introBack(delay)`：一次性入场包络；
- `flash(key, duration)`：一次性闪烁/扫光；
- `breathe(period)`：极慢呼吸，只用于面板顶部一条强调线；
- 全局设置 `animations / motionIntensity / motionSpeed / glow / shadows` 在每次采样时读取，
  所以设置页改动立即生效，不需要重建 UI。

缓动函数：`easeOut`、`easeInOut`、`easeOutBack`、`easeOutElastic`、`easeOutExpo`。
`animations=false` 时所有通道直接返回 target，UI 完全静态可用（不是“半速”或“透明”）。

### 1.3 运动语言

- hover：120–220 ms 指数逼近；
- 入场：约 300 ms，位移 6–10 px，scale 0.985→1；
- 卡片/引导：overshoot 轻微回弹，不弹跳过度；
- 背景光：5 Hz 级别的慢跟手；
- 阴影最多 3 层，alpha ≤ 0.14；不使用大面积高斯模糊；
- 不给文字做位移；不给每个列表项做无限动画。

## 2. 新增文件

| 文件 | 作用 |
| --- | --- |
| `UiMotion.java` | 每宿主时钟、通道、缓动、全局设置读取 |
| `UiGfx.java` | 渐变、发光线、柔和阴影、强调轨 |
| `UiBackdrop.java` | BBS landing 风格的灯、跟随鼠标光、微粒排斥 |
| `CompPrefs.java` | 全局偏好 JSON：引导、动效、布局、快捷键覆盖 |
| `CompKeybinds.java` | 命令表、两套默认方案、录制/冲突/重置 |
| `UIOnboarding.java` | 5 页引导、布局/动效/快捷键选择 |
| `UICompSettings.java` | BBS 风格设置、搜索、快捷键编辑器 |

## 3. 新手引导

页面：

1. **欢迎**：能力概览 + 3 个能力卡 + 开始设置；
2. **工作区布局**：标准 / 专注 / 紧凑 / 宽屏，带迷你布局图；
3. **动效与光影**：平衡 / 电影感 / 极简，带不同振幅的波形预览；
4. **快捷键**：Premiere / BBS 原版，附“稍后在设置中编辑快捷键”；
5. **完成**：汇总布局、动效、快捷键，进入合成器。

行为：

- 首次 `onAppear` 时，`CompPrefs.needsOnboarding()` 为真则显示；
- `CompPrefs.setOnboardingDone()` 写入 `onboardingSeen/onboardingVersion`，只出现一次；
- 「跳过」也算已读，避免每次打开 dashboard 都打扰；设置里有“重新播放新手引导”；
- 布局/动效/快捷键选择即时写入全局偏好；布局同时写入当前工程（如果已有工程）；
- 方向键 ←/→、Enter、Esc 与鼠标等价；圆点可点击；
- 卡片有 180 ms 的输入去抖，避免一次长按/重复事件跳过整段引导；
- 背景 `UiBackdrop` 复用 BBS 的视觉语言：深色渐变、3 个漂移灯、48 个被指针推开的微粒、
  跟随鼠标的柔光；卡片表面也叠加一层很淡的指针光，避免“光被不透明卡片挡住”。

## 4. 设置

### 4.1 布局

- 遮罩：78% 黑，背后编辑器不抢注意力；
- 卡片：`min(960, panel.w-32) × min(650, panel.h-32)`，居中；
- 左栏 184 px：搜索 + 6 个分类（动效与光效、界面布局、快捷键、项目设置、高级、新手引导）；
- 右栏 `UIScrollView`：24 px 行高、10 px padding、3 px 行距；
- 顶部标题/副标题/关闭；底部版本与保存说明；
- 搜索实时过滤行和分区标题；切换分类会重置滚动位置并播放一次强调线扫光。

### 4.2 控件

- `Row`：每个设置行自带 hover 通道，hover 时淡背景 + 2 px 强调轨；
- `UIToggle`：开关；
- `UISliderTrackpad`：动效强度/速度/背景亮度；
- `Segmented`：BBS 风格分段按钮（Premiere/BBS、自动/紧凑/标准/宽松、编码器、代理），
  不再用一个巨大的主色条占满一行；
- `UITrackpad`：时长等数值；
- `UICompButton`：预设、重置、打开引导、清除缓存等。

### 4.3 动效选项

| 选项 | 范围 | 说明 |
| --- | --- | --- |
| 启用界面动效 | 开/关 | 关闭后全部直接到位 |
| 动效强度 | 0–1.5 | 缩放位移、阴影、hover 幅度 |
| 动效速度 | 0.5–2.5 | 所有指数/入场速度倍率 |
| 光效 / 辉光 | 开/关 | 页签下划线、卡片光、引导背景 |
| 阴影 | 开/关 | 按钮/卡片/设置浮层 |
| 引导背景亮度 | 0–1.5 | 只影响 onboarding/start 背景 |

## 5. 可编辑快捷键

### 5.1 命令表

`CompKeybinds.ACTIONS` 里每个 action 有：

- `id`、中文标签、分类；
- Premiere 默认绑定列表；
- BBS 默认绑定列表；
- 用户覆盖（存在 `CompPrefs.keybinds`）。

保留原有全部命令：空格/J/K/L、Alt+K、S/F/M/Shift+M/Ctrl+Shift+M/C/`\`、`=`/`-`、
Alt+`=`/`-`、`[`/`]`、`,`/`.`、Alt+I/O 或 I/O、Ctrl+Shift+I/O、Home/End、Delete/Backspace、
Ctrl+Z、Ctrl+Shift+Z/Ctrl+Y、Ctrl+S、Ctrl+Shift+D 或 Ctrl+D、Ctrl+M、Ctrl+Shift+A、Ctrl+/。
新增 `Ctrl+,` 打开设置。

`Ctrl+K` 仍作为拆分的兼容别名；`Ctrl+Alt+K` 仍打开快捷键界面；V/A 仍提示选择工具。

### 5.2 录制

1. 点击键帽；
2. `UICompositorPanel.subKeyPressed` 最前面检查 `CompKeybinds.isCapturing()`（设置浮层也有同样
   检查，保证事件路由到哪一层都能捕获）；
3. 按下主键 + 当前 Ctrl/Shift/Alt；
4. 写入 `CompPrefs`，立刻保存，回调重建列表；
5. `Esc` 取消，`Backspace/Delete` 清除（保存 `cleared=true`，这样默认绑定不会复活）；
6. 与其它 action 冲突时，状态栏显示冲突命令名；
7. “恢复全部默认快捷键”清空覆盖表。

`find()` 对修饰键做**精确匹配**：Ctrl+S 不会触发 S 吸附，Shift 多按也不会误触。

### 5.3 持久化

`config/bbsae/prefs.json`：

```json
{
  "onboardingVersion": 1,
  "onboardingSeen": true,
  "animations": true,
  "motionIntensity": 1.0,
  "motionSpeed": 1.0,
  "glow": true,
  "shadows": true,
  "backdropIntensity": 1.0,
  "layoutPreset": 0,
  "shortcutScheme": 0,
  "keybinds": {
    "file.save": {"key": 83, "ctrl": true, "shift": false, "alt": false, "cleared": false}
  }
}
```

这些是**应用偏好**，不是工程数据；只有布局/项目默认值/快捷键方案会再写入当前工程的
`CompSettings`。

## 6. 全局动效接入点

| 位置 | 动效 |
| --- | --- |
| `UICompButton` | hover 填充/边框/文字插值、上浮 2 px、阴影、强调轨、右对齐键帽 |
| `UICompTabBar` | hover 淡背景、选中下划线宽度/透明渐入、文字颜色 |
| `UICompositorPanel` | 面板入场 6 px、顶部强调线 3.6 s 呼吸、设置/引导遮罩时清 tooltip |
| `UIStartScreen` | landing 背景、卡片阴影/顶部光、卡片入场、标题下划线展开 |
| `UIAssetBrowser` | 弹层遮罩、格子逐行入场、hover/选中、缩略图淡入、平滑滚动、滑块 |
| `UILayerLibrary` | 卡片入场、hover/选中、平滑滚动 |
| `UIEffectLibrary` | 行 hover 背景 + 左强调轨 |
| `UILayerQuickPanel` | 顶部渐变/强调线、入场条、分组轨 |
| `UICompTrackTimeline` | 轨道头/轨道的选择过渡、clip 边框/抓手插值 |
| `UICompSettings` | 卡片 overshoot、分类扫光、行 hover、按钮/分段 |
| `UIOnboarding` | 卡片 overshoot、分页滑动、卡片 hover/选中、背景光、卡片指针光 |

BBS 原生 `UIButton` / `UIToggle` / `UITextbox` 仍保持 BBS 自己的皮肤；这次不改 BBS jar，
也不替换 BBS 的 widget，只把合成器自有的可交互表面和浮层统一到一套运动语言。

## 7. 验证

- `./gradlew test`：197/197 通过（新增 `CompKeybindTest`、`UiMotion` 缓动/颜色测试）。
- `runClientView -PbbsaeView=settingskeys`：设置、分段、快捷键列表、右对齐键帽、0.18.0 footer。
- `runClientView -PbbsaeView=curve` + `-Dbbsae.debugOnboardingLock=true -Dbbsae.debugOnboardingPage=0`：
  欢迎页、光标光、能力卡、圆点。
- 截图：`docs/gallery/onboarding.png`、`docs/gallery/settings.png`、`docs/gallery/hero.png`。
- 检查：无 dashboard 构建异常、无 NPE、无 GL 状态泄漏；`run/options.txt` 在截图后恢复 fullscreen。

已知限制：

- 快捷键录制只接受键盘主键 + 三修饰键，不支持鼠标侧键/组合序列；
- 引导的重复点击防护是 180 ms 去抖，不是完整的手势状态机；
- 旧版固定尺寸设置对话框保留在“高级 → 打开旧版高级对话框”，仅用于编码器风险提示和极端排查；
- 动效关闭后仍保留即时 hover 颜色变化（无过渡），不是完全无反馈。

## 8. 文件

新增：

- `src/main/java/dev/bbsae/client/ui/UiMotion.java`
- `src/main/java/dev/bbsae/client/ui/UiGfx.java`
- `src/main/java/dev/bbsae/client/ui/UiBackdrop.java`
- `src/main/java/dev/bbsae/client/ui/CompPrefs.java`
- `src/main/java/dev/bbsae/client/ui/CompKeybinds.java`
- `src/main/java/dev/bbsae/client/ui/UIOnboarding.java`
- `src/main/java/dev/bbsae/client/ui/UICompSettings.java`
- `src/test/java/dev/bbsae/client/ui/CompKeybindTest.java`
- `docs/ROUND29-UI-MOTION-ONBOARDING.zh.md`（本文件）

修改：

- `UICompButton`、`UICompTabBar`、`UICompositorPanel`、`UIStartScreen`、`UIAssetBrowser`、
  `UILayerLibrary`、`UIEffectLibrary`、`UILayerQuickPanel`、`UICompTrackTimeline`；
- `CompPrefs` 与 `CompKeybinds` 由面板/设置共享；
- `gradle.properties` 版本 `0.18.0`；
- README / GALLERY / features / HANDOFF 更新。
