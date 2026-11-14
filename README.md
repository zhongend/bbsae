# BBS AE (`bbsae`)

> **0.99-hotfix69**：3D 预合成的深层修复。
> - hotfix68 已让 3D 工作区渲染真实嵌套合成并修正父/子相机双重投影；本轮继续修复
>   预合成图层本身的 3D 标志：多选 3D 图层预合成后，新预合成层现在保持 `threeD=true`，
>   不会在 3D 视口里退化成屏幕固定的 2D 贴片；单层预合成同样继承源图层的 3D 状态。
> - 普通预合成仍按自己的合成相机压平（透明底），3D 平面跟随父级摄像机；折叠变换
>   仍是显式可选项。
> - 详见 [`docs/ROUND105-PRECOMP-3D-FLAG.zh.md`](docs/ROUND105-PRECOMP-3D-FLAG.zh.md)。
>
> **0.99-hotfix68**：3D 世界 / 回放编辑器里的预合成图层修复。
> - 3D 自由视口不再用不透明的蓝色「预合成（草稿）」色板代替预合成：真实嵌套合成
>   会在预览、3D 工作区、回放编辑器的自由/环绕/摄像机预览和导出中渲染；
> - 预合成透明背景保留，不再是一整块不透明矩形；
> - **关键投影修复**：普通预合成按它自己的合成相机/投影先栅格化，再作为父层平面
>   放置；此前把父级 3D 视口的相机传进嵌套合成，再做一次四边形投影，导致嵌套 3D
>   内容双重投影、位置/大小错误；
> - 「折叠变换」仍显式继承父相机；带蒙版/效果/样式的预合成仍压平，避免把父层像素
>   操作错误地复制到子层；
> - 回放编辑器的自由视图、环绕视图、摄像机预览与 PiP 共用同一套 `CompRenderer` /
>   `CompViewportRenderer`，修复自动同步。
> - 详见 [`CHANGELOG.md`](CHANGELOG.md) 与
>   [`docs/ROUND104-PRECOMP-3D-ALPHA.zh.md`](docs/ROUND104-PRECOMP-3D-ALPHA.zh.md)。
>
> **0.99-hotfix67**：总变换关键帧、预览吸附、X/Y 缩放锁定。
> - 关键帧抽屉新增 **「总变换」**：一次 K 帧会把位置/旋转/缩放的全部 XYZ 轴在同一帧打键；
>   总变换有独立的关键帧轨道（同步 XYZ），各 XYZ 子曲线仍可分别编辑。
>   - 调整过 XYZ 子曲线后，总变换轨道会锁定并显示链接图标；再按总变换 K 或同步，
>     会弹出确认窗口，确认后用总变换曲线**重置所有 XYZ 曲线**。
>   - 总变换曲线本身改动时，所有 XYZ 轴自动重建/同步；删除总变换键也会同步。
> - 预览框新增 **对齐吸附**：拖动图层时吸附到合成中心/四边，以及其它可见图层的
>   边缘/中心；显示黄色对齐参考线。设置里可开关，吸附距离可调，按住 Alt 临时禁用。
> - **X/Y 缩放锁定**：默认开启，拖角点/边缘、3D 缩放 Gizmo、检查器缩放数值或快捷
>   缩放都会保持原宽高比，避免一拉就变形；设置和「运动」页都可开关。
> - 详见 [`CHANGELOG.md`](CHANGELOG.md) 与
>   [`docs/ROUND103-MASTER-TRANSFORM-SNAP-SCALELOCK.zh.md`](docs/ROUND103-MASTER-TRANSFORM-SNAP-SCALELOCK.zh.md)。
>
> **0.99-hotfix66**：预览框左右分栏的标签溢出提示。
> - 预览左右两侧的分区标签（效果库/素材箱/预设/回放，以及调色/图层/影视/蒙版/效果/
>   文字/合成/输出）在空间不足时不再只是静默截断：
>   - 有隐藏标签的一侧绘制**渐隐渐变**，明确表示栏位还在继续；
>   - 边缘显示**呼吸的 «N / »N** 提示（N 为隐藏分区数），鼠标悬停高亮、点击翻页；
>   - 活动分区会自动滚入可见窗口，不会再出现“选中了但看不到标签”的情况；
>   - 支持左右两侧同时溢出，两侧各有独立提示。
> - 窗口滚动逻辑抽成 `CompUi.tabStripWindow(...)` 纯函数，并用测试覆盖窄栏、首尾页、
>   中间页和任意宽度下“活动分区必须可见”。
>
> **0.99-hotfix65**：蒙版 / 排除 / 预合成 / 搜索 / 素材箱提示修复。
> - **蒙版与排除不再局限于形状图层**：文字、纯色、图片、视频、预合成等任意有像素的图层
>   都可以作为 AM 蒙版/排除源；蒙版页对每个图层直接给出「用作蒙版 / 用作排除」，
>   并保留「上方图层 → 蒙版」快捷动作。手动路径蒙版对所有图层类型同样生效。
> - **多选预合成修复**：保留共享外部父级/轨道遮罩，组内时间重定基与外层引用重指向；
>   预合成后主动清除帧/预渲染/图层源缓存，避免出现「图层已移入子合成但画面空白」。
> - **效果库搜索**：不再只依赖文本框回调，每帧以文本框内容为准重新过滤；搜索框
>   点击有 focus 兜底，即使布局早于宽度计算也不会变成“看得见但点不动”。
> - **素材箱图形按钮悬浮提示**：导入 / 新建素材箱 / 旧素材库 / 刷新四个图标都有
>   悬停文字，不再靠猜。
> - 详见 [`CHANGELOG.md`](CHANGELOG.md) 与
>   [`docs/ROUND101-MASK-PRECOMP-SEARCH.zh.md`](docs/ROUND101-MASK-PRECOMP-SEARCH.zh.md)。
>
> **0.99-hotfix64**：音频与素材工作流大修。
> - 音频导入后**预览必响**：不再只在 AE 编码器下试听；修复 8-bit WAV 被当成静音、
>   改音量/音调后预览不再重启、异步解码/复制不卡 UI。
> - 波形图按**片段的源偏移 / 时长 / 变速 / 循环**绘制，不再把整首歌拉满一条剪辑；
>   8/16/24/32-bit PCM 与 32-bit float 都能画。
> - 右侧检查器新增 **「影视」** 页：音频音量、音调（半音）、变速、声像、淡入淡出、
>   循环/静音、入点/偏移/时长、试听/重新链接；视频图层的适配、尺寸、速度、冻结、
>   时间重映射和「从视频提取音频到音轨」也在同一页。
> - 左侧 **「素材箱」**（Premiere 式项目面板）：图片/音频/视频/帧序列统一索引，
>   自制素材箱、搜索、缩略图；从素材箱拖到时间轴可新建片段，拖到已有轨道则**替换素材**
>   并保留变换/效果/关键帧；音频可直接拖入音频轨；拖到素材箱行可归档。
> - 修复素材导入/外链：外部视频登记、Windows 盘符路径不再被当作资源命名空间、
>   同名文件不再覆盖、序列帧不再被拆成几百张图片、缺失外链可见并可重新链接。
> - 详见 [`CHANGELOG.md`](CHANGELOG.md) 与 [`docs/ROUND100-ASSET-BIN-FILM-AUDIO.zh.md`](docs/ROUND100-ASSET-BIN-FILM-AUDIO.zh.md)。
>
> **0.99-hotfix63**：素材库新增「试听」：外链音频与 BBS 音效可直接预览，外链音频条目
> 出现在项目分类并支持「加入音轨」；OS 拖入后目标轨道/入点有呼吸高亮和插入线动效；
> 外链索引自动迁移、音频导入后刷新素材库、当前界面校验等外链 bug 修复。
>
> **0.99-hotfix62**：外链音频不再创建工程 `assets/audio` 目录；只有关闭外链、需要复制/
> 转码时才创建。hotfix61 的 raw PCM 解码与 hotfix60 的外链索引保留。
>
> **0.99-hotfix61**：修复外链压缩音频解码：FFmpeg 不再输出到不可 seek 的 WAV（头尺寸为
> `0xFFFFFFFF`，BBS WaveReader 会拒绝），改为输出 raw `s16le` PCM 并直接构造 `Wave`；
> 同时保留 hotfix60 的真正外链与多格式支持。
>
> **0.99-hotfix60**：真正外链：项目 `CompSettings.externalAssets` 记录绝对路径，素材库
> 直接列出外链图片（`external:`），图片/视频/音频导入不再复制；音频新增 MP3/FLAC/M4A/
> AAC/OPUS/WMA/AIFF，FFmpeg 在内存中解码（stdout，不写工程），WAV/OGG 仍原生解码；
> 外链素材在素材库可直接拖用。详见 [`CHANGELOG.md`](CHANGELOG.md)。

<p align="center">
  <img src="docs/gallery/cover.png" alt="BBS AE (bbsae)" width="460">
</p>

<p align="center">
<b>浣滆€?/ Author锛氱粓end</b> 路 <a href="https://github.com/zhongend">@zhongend</a>銆€<b>鑱斿悎寮€鍙戣€?/ Co-developer锛氭槦杈颁箙姊﹀悰锛圫tar Dm Sov锛?/b> 路 <a href="https://github.com/cupefersi">@cupefersi</a>
</p>

> **鐗瑰埆鎰熻阿璧炲姪鑰咃細銆愭槦杈颁箙姊﹀悰_Star Dm Sov銆戙€佽涔嬩功銆丼zv鏂囨．鐗?7** 鈥?浣犱滑鐨勮禐鍔╂敮鎸佷簡鏈彃浠剁殑寮€鍙戯紝鎰熻阿浣犱滑銆傚畬鏁撮福璋㈣ [CREDITS.md](CREDITS.md)銆?
> 浜ゆ帴鏂囨。锛?*[docs/HANDOFF.zh.md](docs/HANDOFF.zh.md)**锛堝綋鍓嶆灦鏋勩€佸凡瀹炵幇鍔熻兘銆佸叧閿?bug銆佹湭瀹屾垚椤广€佹瀯寤?- 閲嶆槧灏勬祦绋嬶級銆?- **Source:** <https://github.com/zhongend/bbsae>
- **Issues:** <https://github.com/zhongend/bbsae/issues>
- **鏇存柊鏃ヨ / Changelog:** [`CHANGELOG.md`](CHANGELOG.md)
- **Feature matrix:** [`docs/features.zh.md`](docs/features.zh.md)
- **UI architecture:** [`docs/ui-architecture.zh.md`](docs/ui-architecture.zh.md)
- **Photon research:** [`docs/photon-research.zh.md`](docs/photon-research.zh.md)
- **Developer handoff:** [`docs/HANDOFF.zh.md`](docs/HANDOFF.zh.md)
- **涓枃鍥炬枃浠嬬粛 / Chinese gallery:** [`docs/GALLERY.zh.md`](docs/GALLERY.zh.md)
- **Round 8 (Alight Motion PIXEL effects):** [`docs/ROUND8-01-AUDIT.zh.md`](docs/ROUND8-01-AUDIT.zh.md),
  [`docs/ROUND8-02-PIXEL.zh.md`](docs/ROUND8-02-PIXEL.zh.md),
  [`docs/ROUND8-03-VERIFY.zh.md`](docs/ROUND8-03-VERIFY.zh.md),
  [`docs/ROUND8-04-PUBLISH.zh.md`](docs/ROUND8-04-PUBLISH.zh.md)
- **Round 9 (export fidelity / duration / icon):** [`docs/ROUND9-EXPORT-SYNC.zh.md`](docs/ROUND9-EXPORT-SYNC.zh.md)
- **Round 10 (drag layer reorder):** [`docs/ROUND10-LAYER-REORDER.zh.md`](docs/ROUND10-LAYER-REORDER.zh.md)
- **Round 11 (asset library fix + blur):** [`docs/ROUND11-ASSET-LIBRARY.zh.md`](docs/ROUND11-ASSET-LIBRARY.zh.md)
- **Round 12 (AE curves / presets / parenting / shape corners, v0.2.0):** [`docs/ROUND12-CURVES-PRESETS-PARENTING.zh.md`](docs/ROUND12-CURVES-PRESETS-PARENTING.zh.md)
- **Round 13 (camera rewrite / 3D parenting / multi-select / performance, v0.3.0):** [`docs/ROUND13-CAMERA-MULTISELECT-PERF.zh.md`](docs/ROUND13-CAMERA-MULTISELECT-PERF.zh.md)
- **Round 14 (UI density / flat buttons / tooltips, v0.4.0):** [`docs/ROUND14-UI-DENSITY-FLAT.zh.md`](docs/ROUND14-UI-DENSITY-FLAT.zh.md)
- **Round 15 (BBS-native preview + world depth / pre-render cache / UI performance, v0.5.0):**
  [`docs/ROUND15-PREVIEW-CACHE-PERF.zh.md`](docs/ROUND15-PREVIEW-CACHE-PERF.zh.md)
- **Round 16 (curve editor redesign 鈥?jank analysis, render architecture, AE parity plan):**
  [`docs/ROUND16-CURVE-EDITOR-DESIGN.zh.md`](docs/ROUND16-CURVE-EDITOR-DESIGN.zh.md)
- **Round 17 (curve editor P0+P1 鈥?column-span geometry, blank-page fix, v0.6.0):**
  [`docs/ROUND17-CURVE-PERF.zh.md`](docs/ROUND17-CURVE-PERF.zh.md)
- **Round 18 (visible curves / asset import / video+audio export / single-page library / solid
  stage / camera semantics, v0.7.0):**
  [`docs/ROUND18-CURVE-IMPORT-EXPORT-CAMERA.zh.md`](docs/ROUND18-CURVE-IMPORT-EXPORT-CAMERA.zh.md)
- **Round 19 (AE encoder mode 鈥?frame server pipeline for preview and export, v0.8.0):**
  [`docs/ROUND19-AE-ENCODER.zh.md`](docs/ROUND19-AE-ENCODER.zh.md)
- **Round 20 (AE pipeline: disk frame cache, render budget, raw-frame pipe, preview audio,
  proxy resolution, v0.9.0):** [`docs/ROUND20-AE-PIPELINE.zh.md`](docs/ROUND20-AE-PIPELINE.zh.md)
- **Round 21 (timeline zoom, audio and video tracks, narrow-panel fix, v0.10.0):**
  [`docs/ROUND21-TRACKS-AND-AUDIO.zh.md`](docs/ROUND21-TRACKS-AND-AUDIO.zh.md)
- **Round 22 (Blender-style 3D viewport camera 鈥?free orbit/pan/zoom, source-texture draft
  rendering, v0.11.0):** [`docs/ROUND22-CAMERA-VIEWPORT.zh.md`](docs/ROUND22-CAMERA-VIEWPORT.zh.md)
- **Round 23 (viewport usability: right-drag orbit, gradients, motion path, plane-depth stacking,
  first-open layout, playback flicker, v0.12.0):**
  [`docs/ROUND23-VIEWPORT-FIXES.zh.md`](docs/ROUND23-VIEWPORT-FIXES.zh.md)
- **Round 24 (viewport input polling, layer-space effects in the export path, mask/exclude,
  effect thumbnails, layer quick panel, v0.13.2):**
  [`docs/ROUND24-VIEWPORT-MASK-QUICKPANEL.zh.md`](docs/ROUND24-VIEWPORT-MASK-QUICKPANEL.zh.md)
- **Round 25 (replay entity layer 鈥?depth-keyed matte, native file dialog, v0.14.1):**
  [`docs/ROUND25-ENTITY-KEY-IMPORT.zh.md`](docs/ROUND25-ENTITY-KEY-IMPORT.zh.md)
- **Round 26 (owned Win32 file dialog, sound import, layer library, true 3D camera, v0.15.0):**
  [`docs/ROUND26-IMPORT-LIBRARY-CAMERA.zh.md`](docs/ROUND26-IMPORT-LIBRARY-CAMERA.zh.md)
- **Round 27 (playback clock / BBS-native curve picker / 3D XYZ quick panel, v0.16.0):**
  [`docs/ROUND27-PLAYBACK-CURVES-3D.zh.md`](docs/ROUND27-PLAYBACK-CURVES-3D.zh.md)
- **Round 28 (asset-library scrollbar / professional AE curve editor / plugin icon, v0.17.0):**
  [`docs/ROUND28-ASSETS-CURVES-ICON.zh.md`](docs/ROUND28-ASSETS-CURVES-ICON.zh.md)
- **Round 29 (onboarding / BBS-style settings / non-linear motion / editable keybinds, v0.18.0):**
  [`docs/ROUND29-UI-MOTION-ONBOARDING.zh.md`](docs/ROUND29-UI-MOTION-ONBOARDING.zh.md)
- **Round 30 (sponsor hall / BBS flight camera / 3D effect fix / repeats / adaptive UI, v0.19.0):**
  [`docs/ROUND30-FINAL.zh.md`](docs/ROUND30-FINAL.zh.md)
- **Round 31 (effect-library decluttering / camera hints / settings-only sponsors / premium UI light, v0.19.1):**
  [`docs/ROUND31-UI-POLISH.zh.md`](docs/ROUND31-UI-POLISH.zh.md)
- **Round 32 (Iris final-pass capture / shader-aware replay matte, v0.19.2):**
  [`docs/ROUND32-IRIS-SHADERS.zh.md`](docs/ROUND32-IRIS-SHADERS.zh.md)
- **Round 33 (replay matte persistence / header geometry / collapsible filter, v0.19.3):**
  [`docs/ROUND33-ENTITY-MATTE-UI.zh.md`](docs/ROUND33-ENTITY-MATTE-UI.zh.md)
- **Round 34 (3D matte transparency / 3D effects / effect strength / 3D adjustment layers, v0.20.0):**
  [`docs/ROUND34-3D-MATTE-EFFECTS.zh.md`](docs/ROUND34-3D-MATTE-EFFECTS.zh.md)
- **Round 35 (custom font import / working folder buttons / native dialog fallback, v0.20.1):**
  [`docs/ROUND35-FONT-IMPORT-OS-OPEN.zh.md`](docs/ROUND35-FONT-IMPORT-OS-OPEN.zh.md)
- **Round 36 (single layer/effect application / keyframed strength / precomp pass inheritance, v0.20.3):**
  [`docs/ROUND36-LAYER-EFFECT-APPLICATION.zh.md`](docs/ROUND36-LAYER-EFFECT-APPLICATION.zh.md)
- **Round 37 (3D effect support region / baked styles and masks / 3D effect completeness, v0.20.4):**
  [`docs/ROUND37-3D-EFFECT-SUPPORT.zh.md`](docs/ROUND37-3D-EFFECT-SUPPORT.zh.md)
- **Round 38 (AE effect programme / blur and sharpen family complete, v0.21.0):**
  [`docs/ROUND38-AE-BLUR-FAMILY.zh.md`](docs/ROUND38-AE-BLUR-FAMILY.zh.md)
- **Round 39 (AE effect programme, batch 2: distort family, v0.21.1):**
  [`docs/ROUND39-AE-DISTORT-FAMILY.zh.md`](docs/ROUND39-AE-DISTORT-FAMILY.zh.md)
- **Round 40 (mask exclude semantics / visual mask editing / 3D track mattes, v0.21.2):**
  [`docs/ROUND40-MASK-EXCLUDE.zh.md`](docs/ROUND40-MASK-EXCLUDE.zh.md)
- **Round 41 (projected layer and warp alpha association, v0.21.3):**
  [`docs/ROUND41-PREMULTIPLY.zh.md`](docs/ROUND41-PREMULTIPLY.zh.md)
- **Round 42 (custom font rendering / BBS font folder import flow, v0.21.4):**
  [`docs/ROUND42-FONT-RENDER.zh.md`](docs/ROUND42-FONT-RENDER.zh.md)
- **Round 43 (automatic system font discovery and import, v0.21.5):**
  [`docs/ROUND43-SYSTEM-FONTS.zh.md`](docs/ROUND43-SYSTEM-FONTS.zh.md)
- **Round 44 (effect & layer bug-fix release, v0.22.0):** [`docs/ROUND44-EFFECT-LAYER-FIXES.zh.md`](docs/ROUND44-EFFECT-LAYER-FIXES.zh.md)
- **Photon attribution / 寮曠敤澹版槑:** [`PHOTON-NOTICE.md`](PHOTON-NOTICE.md)

<p align="center">
  <img src="docs/gallery/hero.png" alt="BBS AE 鈥?onboarding, BBS-style settings and editable shortcuts" width="100%">
</p>

> This repository does **not** redistribute the BBS FS jar. Download BBS FS 2.7-1.20.1 from its
> official Modrinth page and put `bbs-2.7-1.20.1.jar` in `libs/` before building.

A screen-space compositor for **BBS FS** films, built as a BBS addon for **Minecraft 1.20.1 / Fabric**.

The compositor adds a 2D/2.5D layer on top of BBS's finished 3D frame: text, shapes, images,
solid layers, nulls, adjustment layers, precomps, masks, feathered mattes, effects, layer styles,
parenting, tracked bindings and a native-keyframe timeline. The result is drawn through BBS's
official `RegisterFrameOverlaysEvent`, so it is included in BBS's preview **and in its FFmpeg film
export** without touching the `.dat` film format.

## Download / Install

1. Download `bbsae-0.99-hotfix69.jar` from the repository's
   [releases](https://github.com/zhongend/bbsae/releases), or build it with
   `gradlew remapJar` (the artifact is `build/libs/bbsae-0.99-hotfix69.jar`).
2. Install **Minecraft 1.20.1**, **Fabric Loader 0.16.14+**, **Fabric API 0.92.1+1.20.1** and
   **BBS FS 2.7-1.20.1**.
3. Put `bbsae-0.99-hotfix69.jar` and the BBS FS jar into the game's `mods/` folder.
4. Launch the game, open a film in BBS, select a replay, then use
   **Replay properties → Compositor → Open Film Compositor**.

`bbsae-0.99-hotfix69.jar` SHA-256: `08D0AC17B1B9AEB4C4E42441AB2DAE81EC84ACE2EF5B6C2015C7A5581BF6A1BD` (4,027,248 bytes).

## Gallery

> 涓枃鐗堝浘鏂囦粙缁嶏紙鍚€愬紶璇存槑锛夎 **[docs/GALLERY.zh.md](docs/GALLERY.zh.md)**銆?### The editor, not an overlay

<p align="center">
  <img src="docs/gallery/editor-overview.png" alt="BBS AE editor: effects library, live preview, Effect Controls and track timeline" width="100%">
</p>

BBS AE opens as an **independent BBS dashboard panel**, not a transparent overlay on the
film editor. The layout follows a Premiere-style information architecture:

1. **Effects library** 鈥?search, categories and a recent list; double-click applies to the selected track.
2. **Live FBO preview** 鈥?the compositor renders into its own framebuffer with selection handles,
   safe-area/grid overlays, and **FX / Mask / BBS-world** toggles for A/B comparison.
3. **Effect Controls** 鈥?motion, opacity, blend mode, the applied-effect stack, layer styles,
   masks, text, grade, and the context-sensitive Layer/Comp/Output pages.
4. **Track timeline** 鈥?one row per layer, movable/trimable clips, frame snapping, work-area
   shading, markers and a BBS-native keyframe drawer. Drag the **left grip** of a track header
   to reorder layers vertically (blue insertion line), without hunting through the context menu.

<p align="center">
  <img src="docs/gallery/timeline.png" alt="Track timeline with per-layer clips, snapping and markers" width="100%">
</p>

### 58 effects, including the Alight Motion PIXEL family and repeat tools

The compositor ships a curated catalogue rather than a wall of half-implemented effects. It covers
blur, colour, keying, generate, distort, stylize, transitions and depth. Round 8 added seven
screen/retro effects built on the shared PIXEL shader, and 0.19 adds **Grid Repeat**, **Random
Repeat**, **Turbulent Displace**, **Colorama** and **Light Sweep**:

**Scanlines 璺?Glitch 璺?VHS 璺?Halftone 璺?Emboss 璺?Mirror 璺?Kaleidoscope**

<p align="center">
  <a href="docs/verify/round8-pixel-effects.png">
    <img src="docs/verify/round8-pixel-effects.png" alt="Scanlines, Glitch, VHS, Halftone, Emboss, Mirror and Kaleidoscope" width="640">
  </a>
</p>

Halftone uses Alight Motion-style properties (dots across the project width, strength, angle, row
phase, ink/paper colours); Mirror overlays a mirrored copy with an opacity control. All seven run in
**layer space**, so the pattern follows the layer when it is moved, scaled or rotated.

### Grading, assets, 3D and export

| <img src="docs/verify/round6-grade6.png" alt="AE-style colour wheels" width="100%"> | <img src="docs/verify/round28-assets.png" alt="Asset library with Minecraft icons and a draggable scrollbar" width="100%"> |
| :---: | :---: |
| **AE-style grading** 鈥?lift/gamma/gain wheels, five-point master curve, presets and keyframeable exposure/contrast/saturation/vibrance. | **Asset library** 鈥?project media plus the client's item/block/entity/gui textures; search, thumbnails and a draggable scrollbar. |

| <img src="docs/verify/round7-3d2.png" alt="3D camera and 3D card in the compositor" width="100%"> | <img src="docs/verify/ui-4-export-dialog.png" alt="Export dialog with resolution, aspect and frame rate" width="100%"> |
| :---: | :---: |
| **3D camera and 2.5D cards** 鈥?free camera, depth ordering, adjustment layers and precomps. | **BBS-native export** 鈥?resolution/aspect/fps presets write into BBS's recorder, then F6 renders the real film. |

| <img src="docs/verify/round6-export-1080p60.png" alt="A frame from a 1080p60 export" width="100%"> |
| :---: |
| **A real 1920脳1080 @ 60 fps export** 鈥?494 frames, 8.23 s, with the compositor layers baked into the MP4. |

### Built on BBS's own frame path

The compositor does not re-encode films or patch `.dat` files. BBS serializes a small
`bbsae:composition` camera clip; `CompRenderer` draws the layer stack at the
`RegisterFrameOverlaysEvent` point. That means the same composition appears in BBS's preview and in
its FFmpeg film export, and BBS keeps ownership of keyframes, undo, project storage and video
encoding.

## What's new in 0.91 鈥?2D layers stay screen-attached

- Root cause of the 2D-layer bug: once any 3D layer had a z offset (or the camera was tilted),
  `CompScene.spatial()` made *every* flat layer go through the 3D `projectPlane()` pass, so plain
  2D layers were tilted/moved along with the 3D scene.
- The spatial model branch and `CompRenderer.projectPlane()` now require `layer.threeD`; 2D layers
  always use `camera.view2D` (pan/zoom/roll yes, tilt no), matching the documented contract.
- `Camera3DTest` pins that a 2D layer still uses view2D under a tilted camera.
- 379 tests green.

## What's new in 0.90 鈥?replay entity layers keep their matte in 3D effects

- A replay entity layer's coverage matte is projected in **composition space** (the world camera
  decides which actor pixels are keyed), but the 3D effect bake rasterized the layer in **layer-local
  space**. A 3D effect on an entity layer therefore sampled the wrong region of the world frame.
- Entity layers are now excluded from the layer-local bake: they render through the direct
  composition-space path, so 3D effects run on the correctly keyed image.
- `shouldBake3D` is a testable function; `EntityLayerBakeTest` covers entity layers, normal 3D
  shapes and 2D layers.
- 378 tests green.

## What's new in 0.89 鈥?fix the camera-preview ghost by disabling the depth plane path

- Reproduced with the user's project `____bug` locally: with the main compositor's per-pixel depth
  enabled, the camera preview showed duplicated star/polygon layers and stripes; with
  `DepthPlaneCompositor` disabled the same project renders correctly.
- The free 3D workspace was always clean because it uses `Viewport3DRenderer`, a different path.
- Per-pixel depth in the main composition is now **off by default** (Settings 鈫?鏄剧ず涓庣嚎妗?can enable
  it again); the main composition falls back to whole-layer ordering, while the 3D workspace keeps
  its depth-tested quads.
- 0.87's opaque camera preview and 0.88's world-capture feedback guards remain.
- 375 tests green.

## What's new in 0.88 鈥?stop the BBS-preview feedback ghost

- Root cause found in the user's film project: it contains a **BBS preview layer**. With BBS' custom
  preview/recording size, the late world copy could run after the composition overlay, so the BBS
  layer sampled the previous composition and recursed into multiple ghosts.
- Custom-size frames now capture the clean world **after entities** instead; the late
  AFTER_TRANSLUCENT copy no longer overwrites it.
- While the composition overlay is drawing, `captureWorldNow()` is guarded so it cannot capture the
  composition being painted.
- 0.87's opaque camera preview and depth-state restoration remain.
- 375 tests green.

## What's new in 0.87 鈥?camera-preview ghosting fix

- Camera preview now renders a **fresh opaque frame** every time: it no longer reuses the preview
  cache (whose transparent stage could let the previous frame show through) and never shares a
  cached texture with the normal preview.
- While the compositor is open, BBS' editor **onion skins** are temporarily suppressed (restored on
  exit); their extra previous/next frame drawings are a common source of duplicated shapes and text
  in camera preview. A new Settings switch 鍏抽棴 BBS 娲嬭懕鐨畫褰?controls this if you need onion skins.
- `Viewport3DRenderer` now restores depth writes/tests when it finishes, so depth state cannot leak
  into the composition blit.
- 375 tests green.

## What's new in 0.86 鈥?keyframe TIME-axis zoom

- Clarified intent: the zoom needed in the XYZ keyframe drawer is the **time axis**, not row height.
  The drawer title arrows now zoom the time axis in/out by setting the x-axis view around the current
  centre, clamped to the composition duration.
- Alt+wheel anywhere inside the keyframe drawer zooms the time axis, including over the XYZ track
  labels; requests reported as a horizontal wheel are accepted too.
- Row-height controls remain on the curve editor +/- buttons and the Settings sliders, independent of
  time zoom.
- Client screenshot: `docs/verify/round62-keyframe-timezoom.png` (ticks 0鈥?0 s collapsed to a
  120鈥?50 frame window); project and prefs restored.
- 375 tests green.

## What's new in 0.85 鈥?drawer closes with the same slide

- The closing path now shrinks the keyframe drawer through the same animated height instead of
  snapping it shut; opening and closing share one clipped layout.
- Switching away from the timeline page resets the slide, so returning with the drawer open slides it
  up from the bottom again.

## What's new in 0.84 鈥?XYZ track resizing and a sliding keyframe drawer

- The **keyframe drawer** holding the position X/Y/Z rows now has up/down buttons in its title bar
  that resize the dope-sheet tracks directly; the curve editor's channel list has its own +/鈭?  buttons, and the Settings sliders drive both editors.
- Alt+wheel now resizes rows anywhere inside the curve editor, not only when the pointer is over a
  specific channel row. The buttons remain the guaranteed path.
- The keyframe drawer **slides up from the timeline's bottom edge** (~300 ms ease-out) and slides
  down before hiding, with its content clipped to the animated height.
- Client screenshot: `docs/verify/round60-keyframe-drawer.png`; project and prefs restored.
- 375 tests green.

## What's new in 0.83 鈥?overlay presets in Settings

- 鏄剧ず涓庣嚎妗?gains three one-click buttons: **绾噣棰勮** (hide gizmos, boxes, camera wireframes,
  guides and preview text), **鏄剧ず鍏ㄩ儴绾挎涓庢彁绀?*, and **鎭㈠榛樿澶栬璁剧疆** (opacity/thickness back
  to 1, row heights back to defaults, all switches on).
- These complement the per-control sliders/toggles and give an immediate way to declutter or reset.

## What's new in 0.82 鈥?visible keyframe track-height controls

- The keyframe editor now has **+/- buttons in its top-right corner** that change the dope-sheet row
  height directly, so resizing no longer depends on a wheel gesture that BBS' own scroll handling can
  swallow.
- Settings 鈫?鏄剧ず涓庣嚎妗?has sliders for **鍏抽敭甯ц建閬撻珮搴?* (12鈥?0 px) and **鏃堕棿杞磋建閬撻珮搴?*
  (18鈥?2 px); moving them applies immediately and saves to prefs.
- Alt+wheel / Alt+= / Alt+- remain as shortcuts; the buttons and sliders are the reliable fallback.
- Client screenshot: `docs/verify/round58-keyframe-buttons.png`; project and prefs restored.
- 375 tests green.

## What's new in 0.81 鈥?one gizmo at a time, BBS box by default

- The 3D workspace now defaults to the **scale** mode: only the BBS-style selection box, corner and
  edge grips and a centre uniform handle are shown. W switches to the move arrows, R to the rotation
  rings 鈥?so the three gizmos no longer pile up on one layer.
- Grips are larger (6 px corners, 4 px edges, 6 px centre) with wider hit areas; dragging the centre
  handle scales uniformly.
- The mode bar moved further up, clear of the HUD text.
- Client screenshot: `docs/verify/round56-box-scale.png`; project and prefs restored.
- 375 tests green.

## What's new in 0.80 鈥?BBS-style placement box in 3D

- The 3D workspace now also has the BBS placement-gizmo interaction: four corner grips scale both
  local axes freely, and the four edge grips scale a single axis. The pointer is intersected with the
  layer's plane, so a rotated 3D layer still scales along its own axes. Dragging inside the box keeps
  moving the layer; the AE arrows, W/E/R modes and the mode bar remain available.
- The mode bar moved above the HUD so it no longer covers the 3D mode/camera text.
- Client screenshot: `docs/verify/round55-box-gizmo.png`; project and preferences restored.
- 375 tests green.

## What's new in 0.79 鈥?recent effects and a keyframe-row verification

- The effect library has a **鏈€杩戜娇鐢?* category: it lists the effects in the order they were applied
  and clicking one stacks it again. The category sits right after 鍏ㄩ儴 and shows an empty state when
  there is no history yet.
- Verified the BBS keyframe editor's row height in the real client with
  `-PbbsaeKeyframeRowHeight=40` (`docs/verify/round55-keyframe-rows.png`); it goes through the
  dope sheet's `setTrackHeight` path.
- Project and preferences were restored after the run.
- 375 tests green.

## What's new in 0.78 鈥?keyframe track height and a preview-text switch

- The **BBS keyframe editor** now exposes its dope-sheet track height: Alt+wheel or Alt+= / Alt+-
  changes it between 12 and 60 px and saves it to `prefs.json`, so the editor reopens at the size you
  chose. The main timeline's row height is persisted the same way.
- Settings 鈫?鏄剧ず涓庣嚎妗?has a new **棰勮鏂囧瓧鎻愮ず** switch. Turning it off hides the 3D mode/camera HUD,
  the look-through hint, the resolution strip and the camera position labels; the wireframe geometry
  itself is unaffected.
- `CompPrefsOverlayTest` covers the defaults and a JSON round-trip of the overlay opacity/thickness,
  both row heights and the hint switch.
- 375 tests green.

## What's new in 0.77 鈥?wireframe settings and track-height zoom

- Settings has a new **鏄剧ず涓庣嚎妗?* page: a master gizmo switch, gizmo opacity, wireframe thickness,
  selection boxes, camera gizmos and safe-area/guides toggles. All overlay strokes go through one
  `line()` path, so opacity and thickness apply immediately to gizmos, selection boxes, camera
  frustums and guides; turning gizmos or selection boxes off also disables their hit tests, so an
  invisible handle cannot be dragged by accident.
- The curve editor's channel list now supports **vertical zoom**: Alt+wheel or Alt+= / Alt+- changes
  the row height between 12 and 34 px, with the status line showing the current size. The timeline's
  existing Alt+wheel track-height gesture is unchanged.
- Client screenshot: `docs/verify/round52-settings-overlay.png`.
- 373 tests green.

## What's new in 0.76 鈥?move / scale / rotate gizmos in 3D

- The 3D workspace now has three AE-style gizmo modes: **W = move** (arrows), **E = scale** (axis-end
  squares plus a centre square for uniform scale), **R = rotate** (three projected X/Y/Z rings). A
  clickable mode bar sits above the bottom HUD and highlights the active mode; the W/E/R keys switch
  modes while the preview is focused.
- Each drag is computed from the screen axis or angle captured at press time, so it follows the
  pointer; Shift snaps moves to whole units and rotation to 15掳. Axes pointing at the camera are drawn
  dimmed and are not hit-tested.
- `CompGizmo3DTest` covers the scale factor around 1 and shortest-path screen angle deltas.
- Client screenshots: `docs/verify/round51-gizmo-rotate.png`, `docs/verify/round51-gizmo-scale.png`.
- 373 tests green.

## What's new in 0.75 鈥?AE-style 3D move gizmo and 2D scale fixes

- **3D move gizmo**: selecting a layer in the 3D workspace draws X (red), Y (green) and Z (blue)
  arrows with arrowheads and axis letters, projected through the viewport camera from the layer's
  anchor. Dragging an arrow translates along that axis; Shift snaps to whole composition units.
  2D layers in the workspace get X/Y arrows, 3D layers also Z.
- The projection, hit test and drag conversion live in pure `CompGizmo3D` (`CompGizmo3DTest`): only
  motion along the arrow changes the value, perpendicular motion does not, and an axis pointing at
  the camera is drawn but not hit-tested.
- **2D scale fixes**: corner drags now move X/Y freely by default and Shift keeps the aspect ratio
  (the old logic was inverted); dragging past the anchor keeps the negative sign so the layer
  mirrors instead of collapsing to a 1% sliver; edge grips are coloured by axis (left/right red = X,
  top/bottom green = Y) to match the 3D arrows.
- `docs/verify/round50-gizmo3d.png` shows the gizmo in the real client.
- 371 tests green.

## What's new in 0.74 鈥?a real effect library with rendered previews

- **New full-screen effect library** (`鏁堟灉搴揱 button in the header, also opened from the effects
  page): category tabs, search, a thumbnail grid, click-to-apply and a status line. Tiles animate 鈥?  hover lift and accent rail, an applied pulse, staggered entrance and smooth scrolling; category
  chips animate on hover too.
- **207 preview thumbnails** are rendered by the real effect pipeline
  (`EffectThumbnailTest`, run with `-PbbsaeThumbs`) into
  `assets/bbsae/textures/effects/<id>.png`. A resource test keeps the set in sync with the registry,
  so a missing preview fails the build instead of showing a blank tile.
- The previews use the same test card as the contact sheet, and an effect whose default is the
  identity is nudged to a mid parameter so its tile actually shows the effect.
- Also from this span: the replay matte sizes itself from the world framebuffer's attachment
  (`FramebufferSize`) rather than a guessed viewport, and the 3D workspace draws translucent layers in
  two passes (opaque writes depth, translucent blends without depth writes) with raw GL state so a
  stale `GlStateManager` cache cannot turn it opaque.
- 363 tests green; 207 visible effects.

## What's new in 0.73 鈥?client smoke test and replay-matte verification entry

- Added `-PbbsaeFilmId=` for the existing `bbsaeView=entity` verification view, so a real film's
  replay matte can be opened without clicking through the UI.
- Ran the client once (not repeatedly): the composition with 3D layers, text and the world backdrop
  rendered correctly after the depth changes 鈥?`docs/verify/round47-client-smoke.png`. The available
  test film has no usable replay actor, so the matte was empty; the offset fix is still covered by
  the sizing rules/tests and the runtime size warning, but a film with a replay is needed for the
  final on-screen check.
- Project data was backed up and restored around the run.
- 357 tests green; 137 effects.

## What's new in 0.72 鈥?pooled depth targets and a matte-size guard

- `DepthPlaneCompositor` now pools its colour+depth targets per size (max four each) instead of
  allocating one per composition render, which removes GPU allocation churn while scrubbing and
  playing. The pool is freed with the rest of the render caches.
- `ReplayEntityKey` warns once per second when the coverage target size does not match the world
  frame 鈥?the exact condition that produced the offset entity key. A future regression now shows up
  in the client log instead of silently shifting the matte.
- `DepthPlaneCompositorTest` asserts pool reuse (same size reused, different sizes separate).
- 357 tests green; 137 effects.

## What's new in 0.71 鈥?translucent 3D layers no longer occlude

- Depth prepass now writes depth only for fragments with alpha 鈮?0.5, so a semi-transparent 3D layer
  no longer punches an opaque hole through the layers behind it.
- The colour pass is split: opaque fragments use depth-equality against the prepass, translucent
  fragments use less-or-equal with no depth write, so they blend over a nearer opaque pixel only
  when they are really in front of it.
- `DepthPlaneCompositorTest` covers both cases: a translucent near blue over an opaque far red yields
  a blend, and a translucent far red behind an opaque near blue is fully hidden.
- 356 tests green; 137 effects.

## What's new in 0.70 鈥?per-pixel depth in the main compositor

- New `DEPTH_PLANE` shader treats each 3D layer as a camera-space plane: for every fragment it
  intersects the view ray with the plane and writes `gl_FragDepth`.
- `DepthPlaneCompositor` owns one colour+depth target per composition, runs a depth-only pass and an
  equal-depth colour pass, then lets `PassRenderer` apply the layer's blend mode and opacity exactly
  as before. `CompRenderer` now uses it for `usePerspective` layers in spatial compositions; 2D layers
  keep the old path.
- `DepthPlaneCompositorTest`: two full-frame planes (far red, near blue) composed in **both** orders
  both end blue 鈥?impossible with whole-layer painter order.
- Added a tiny `CompShaders.reload()` test hook so each GL test class can compile programs in its own
  hidden context without reusing destroyed ids.
- 354 tests green; 137 effects.

## What's new in 0.69 鈥?real per-pixel depth in the 3D workspace

- `GlFramebuffer` can now own a `DEPTH_COMPONENT24` renderbuffer (cleared in `begin`, released in
  `delete`).
- New `Viewport3DRenderer` draws each 3D layer as a quad whose four corners are transformed by the
  layer's world matrix and the workspace camera's view-projection, so the GPU interpolates true
  per-pixel depth. Depth test/write are enabled and fully transparent fragments are discarded; 2D
  layers keep the screen-space batcher path with depth disabled.
- `Viewport3DRendererTest` proves the point: two overlapping quads, drawn near-first or far-first,
  both end with the nearer blue quad owning the centre and the far red ring outside 鈥?a depth buffer,
  not whole-layer painter order.
- Honest scope: the **main compositor/export** still bakes 3D layers and orders them by plane. A
  per-pixel depth composite there is the next step and is not claimed here.
- 353 tests green; 137 effects.

## What's new in 0.68 鈥?viewport ordering pinned and the AE entry hidden when impossible

- `CompViewportRenderer.sortByView(ctx, layers, eye, forward)` is now a testable unit.
  `CompViewportSortTest` proves the orbit camera decides the order (layer depths swap when the camera
  moves behind the comp) and that coplanar layers keep the stack order.
- Settings 鈫?娓叉煋 / 缂栫爜绠＄嚎 now **hides the AE option entirely** for projects containing a BBS
  preview or replay-entity layer, with a tooltip explaining why, instead of accepting a click and
  rejecting it afterwards.
- Honest remaining limitation: intersecting 3D planes still rely on per-layer painter sorting; a
  per-pixel depth composite (depth attachment + plane-depth shader) is the next step and is not
  claimed done here.
- 351 tests green; 137 effects.

## What's new in 0.67 鈥?3D ordering, entity matte, project entry and the AE/BBS split

- **3D workspace order**: layer sorting used `CompScene.planeDepth(ctx, layer)`, which resolves the
  *composition* camera; the 3D workspace orbits a free camera. Orbiting therefore reordered layers
  wrongly and they interpenetrated. `CompScene.planeDepth(world, eye, forward)` now sorts against the
  orbit camera's own position/forward.
- **Replay entity matte offset**: the coverage target was sized to the **window** framebuffer while
  BBS' custom-size preview/recorder renders the world at the film resolution. Different aspect ratios
  stretched the 0..1 mask over the plate. The matte now renders at the world frame's size and the
  depth blit's source rectangle describes the world framebuffer (`EntityMaskSizing`, with tests).
- **Project list on the main page**: a 宸ョ▼ button in the compositor header saves and returns to the
  project list (the list already existed on the start screen, but was only reachable via Settings).
- **AE vs BBS renderer**: projects containing a `bbs` preview layer or a `replay` entity-key layer
  cannot switch to the AE encoder (the AE renderer is frame-driven and cannot reproduce BBS'
  tick-driven world). The status line explains why; opening an old AE project with such layers falls
  back to the BBS encoder automatically.
- 349 tests green; 137 effects.

## What's new in 0.66 鈥?painter sorting for non-convex primitives

- Back-face culling alone is only correct for convex meshes; torus, ring, hollow box and star
  polyhedron are non-convex, so their holes filled in, side walls showed sawtooth notches and back
  faces leaked through. `Mesh.drawSorted(mvp)` now sorts triangles far-to-near by centroid clip z
  (JOML column-major: `clip.z = m02*x + m12*y + m22*z + m32`) into a dynamic index buffer, and
  culling is disabled so the painter order owns occlusion regardless of winding.
- `docs/verify/round39-all-shapes.png` shows the result: torus/ring have real holes, cylinder and
  sphere are smooth, heart and star polyhedron are solid.
- 338 tests green; 137 effects.

## What's new in 0.65 鈥?zero-restore verified in the real renderer

- New `bbsaeView=zerotest` scenario renders three cases in one frame: shatter at **0** (must be a
  clean rectangle), shatter at **0.35** (must be broken) and a 3D cube with stacked effects.
  `docs/verify/round38-zerotest.png` shows exactly that 鈥?the user-reported "shatter at 0 cannot
  restore a square" contract now has pixel evidence from the real composition renderer.
- New effects: **鍙戝厜** (`AM 路 椋庢牸鍖朻, alpha halo) and **骞虫粦鏂滈潰** (`AM 路 缁樺埗`, luminance bevel),
  both with zero identities covered by the forced-shader audits.
- 137 effects total (122 2D + 15 3D); 338 tests green.

## What's new in 0.64 鈥?zero-set audits and contour lines

- `AmEffectDef.zeroKeys()` exposes the "several parameters are zero together" identity sets (colour
  tone's nine gains, highlight/shadow's two amounts). A new forced-shader test,
  `everyDeclaredZeroSetIsIdentityInsideTheShader`, renders each one pixel by pixel against an opaque
  source 鈥?the same audit that previously caught the cartoon/glass defects.
- New effect **杞粨绾?* (`AM 路 缁樺埗`): luminance-gradient outline with strength 0 = original.
- 135 effects total (120 2D + 15 3D); 338 tests green.

## What's new in 0.63 鈥?shader-level identity audits that actually run the shader

- `AmEffectRenderer.applyForced` is a test-only entry point that bypasses the neutral early-out, so
  the zero/custom-identity audits really execute the shader body instead of proving a skipped pass.
  The audits also use a full-bleed opaque source, so border pixels cannot hide behind transparent
  margins.
- The stricter audit found and fixed three real defects:
  - **鍗￠€?* still quantised colours at `strength=0` (quantisation and outline now scale with
    strength);
  - **鐜荤拑** applied its tint/highlight regardless of refraction strength (now weighted by
    `strength / 24`);
  - **娓愬彉閬僵 / 寰勫悜鎿﹂櫎锛堣搴︼級** kept a half-transparent sweep edge at progress 1 (explicit
    identity branch), so "progress 1 = original" is now exact.
- 337 tests green; 134 effects.

## What's new in 0.62 鈥?shader-level zero audit and keying/repeat effects

- New test `everyDeclaredZeroIsIdentityInsideTheShader`: it bypasses the neutral early-out and calls
  `AmEffectRenderer` directly, comparing every zero-declared effect pixel by pixel against the
  source. Previously a non-identity shader could pass because the renderer skipped the pass.
- It immediately caught a real bug: 鑹插害閿?applied its spill suppression regardless of strength, so
  strength 0 still changed colours; spill is now scaled by strength.
- New effects: **鑹插害閿?*, **浜害閿?* (`AM 路 閬僵`), **缃戞牸骞抽摵**, **绾挎潯閲嶅** (`AM 路 閲嶅`).
- 134 effects total (119 2D + 15 3D); 335 tests green.

## What's new in 0.61 鈥?3D shapes now work on 2D layers too

- Composition-space mesh effects render into a **layer-sized surface** first and then place that
  surface as a quad at the layer's projected rectangle. The previous path sampled the whole
  composition on every face, which is why they looked wrong on 2D layers.
- Fixed a long-standing fragment-shader bug: `uv = uv * repeat + 0.5` shifted every textured mesh
  sample into the wrong quadrant of the source (shapes appeared blank or misplaced). It is now
  `(uv - 0.5) * repeat + 0.5 + offset`, an identity at the defaults.
- Evidence: `docs/verify/round34-all-shapes.png` shows **all 16 primitives in one frame** on 2D
  layers, and `docs/verify/round34-cube3d.png` is the 3D baked path.
- 334 tests green; 130 effects.

## What's new in 0.60 鈥?3D shape rendering verified in the client

- The "gaps" between the cube's faces were **not geometry**: with a full-bleed source texture the
  faces meet exactly (`docs/verify/round33-cube-fill.png`). Earlier screenshots sampled the layer's
  transparent margins onto the faces, which is expected for a source with alpha padding.
- New `bbsaeAllShapes` debug scenario builds a 16-primitive grid in one composition, and
  `bbsaeCompShot` writes the exact rendered frame for inspection.
- **Open issue (documented, not claimed fixed):** mesh effects on **2D** layers still sample the
  whole composition texture instead of the layer rectangle, so their appearance there is wrong. The
  baked 3D-layer path is verified correct.
- 334 tests green; 130 effects.

## What's new in 0.59 鈥?3D shapes bake into the layer again

- Root cause of the tiny/offset 3D primitives: `s3d_*` effects were forced into the post-projection
  pass, but the pixels they received were a **layer-local** raster while the matrix was composition
  space. They now bake into the layer raster (ortho, filling it) and only 2D layers use the
  composition-space placement. The exported frame shows the cube at the layer's size and position
  (`docs/verify/round32-cube-baked.png`).
- New `bbsaeCompShot` verification hook writes the exact `CompRenderer` frame as PNG, so placement
  questions are answered from pixels instead of the viewport (which also draws gizmos).
- **Remaining open issue:** the cube's faces still show gaps between them (geometry/culling), tracked
  for the next round; the release does not claim it fixed.
- 334 tests green; 130 effects.

## What's new in 0.58 鈥?verification harness for 3D primitives

- New debug entries for isolating a single layer: `bbsae.debugSelectLayer`, `bbsae.debugOnlyLayer`
  and `bbsaeView=cubetest` (one 3D layer with `s3d_cube`). They make a client screenshot show
  exactly one effect result instead of guessing from a full scene.
- **Known issue (tracked, not claimed fixed):** the 3D primitives render correctly in the headless
  contact sheet, but the client preview shows them smaller/offset relative to the layer bounds.
  World-matrix, model-matrix and composition-space ortho placement were all tried; the remaining
  difference points at the compositor preview path rather than the matrix. Next step is comparing an
  exported frame against the preview. The feature stays in the build, and this note keeps the
  release honest.
- 334 tests green; 130 effects.

## What's new in 0.57 鈥?text inspector verified in the real client

- Added `bbsae.debugSelectLayer`: a verification hook that selects a layer by name **or text
  content**, so screenshots can show a page for that layer type without mouse input.
- The debug demo scene now includes a text layer (`鏂囧瓧鏁堟灉 Aa 123`) and applies `s3d_cube` to the
  3D card, exercising the text inspector and the projected-pass mesh path in one launch.
- `docs/verify/round30-textfx4.png` shows the 鏂囧瓧 page: 鏂囧瓧妯″紡, 鎵撳瓧杩涘害, 闅忔満瀛楃姣斾緥, 闅忔満绉嶅瓙,
  璁℃暟鍣ㄥ亸绉?鍊嶇巼 鈥?and the text rendering in the viewport.
- 334 tests green; 130 effects.

## What's new in 0.56 鈥?text content effects

- Text layers now support content-level effects before layout: **鏃堕棿鐮?* (`HH:MM:SS:FF`),
  **璁℃暟鍣?* (offset + scale), **闅忔満瀛楃** (deterministic per-frame scramble) and
  **鎵撳瓧杩涘害** (typewriter reveal over code points, so CJK is safe).
- These are nullable layer fields, so projects saved before the feature keep their exact text; the
  inspector's 鏂囧瓧 section gains mode, progress (keyframable), scramble amount/seed and counter
  offset/scale controls.
- Per-character transform and spacing are already covered by the existing text animators and
  tracking/line-height properties (the reference app's texttransform/textspacing effects).
- New `TextContentTest` covers formatting, code points, determinism and composition. 334 tests green.

## What's new in 0.55 鈥?transition and reveal batch

- **妫嬬洏婧惰В**, **鍍忕礌婧惰В**, **寰勫悜鎿﹂櫎锛堣搴︼級**, **娓愬彉閬僵** (`AM 路 杞満`): progress-driven
  reveals; progress 1 is the identity and is declared through `neutralWhen`.
- **閫熷害绾?* (`AM 路 鐢熸垚`): radial speed streaks with count/sharpness/radius and colour.
- 130 effects total (115 2D + 15 3D); 326 tests green; library names stay unique.

## What's new in 0.54 鈥?effect order is preserved around the 3D bake

- A 3D layer now splits its effect stack at the **first projected effect**: everything before it is
  baked into the layer raster, everything from it onward runs after projection **in the author's
  order**. Previously only the projected effects themselves ran late, so `绔嬫柟浣?-> 妯＄硦` executed
  the blur first and the cube last.
- `requiresProjectedPass` / `firstProjectedEffect` are pure list logic and are pinned by
  `EffectOrderTest` (split point, disabled effects, classification).
- 326 tests green; 125 effects.

## What's new in 0.53 鈥?hollow box, star polyhedron and compositing tools

- **绌哄績鐩?* (six thin slabs forming a box shell) and **鏄熷舰澶氶潰浣?* (star polygon bipyramid)
  join `AM 路 3D 褰㈢姸` 鈥?15 primitives total, all lit and texture-mapped.
- **鍊掑奖** (mirrored copy below with fade), **鍐呴槾褰?* (alpha-difference inner shading) under
  `AM 路 鍚堟垚`; **楂樺厜** (gaussian sheen band) under `AM 路 椋庢牸鍖朻.
- 125 effects total (110 2D + 15 3D); 322 tests green.

## What's new in 0.52 鈥?opacity and halftone tools

- **缇藉寲**, **鍗婅皟鐐?*, **绛夐珮绾挎笎鍙?* (`AM 路 缁樺埗`): averaged alpha feather, luminance-sized
  halftone dots and contour bands coloured by luminance.
- **娣″叆娣″嚭**, **鐪ㄧ溂** (`AM 路 閫忔槑搴): progress alpha and a time-quantised blink; fade's identity
  is progress 1 (declared through neutralWhen).
- **鍏夋壂** (`AM 路 椋庢牸鍖朻): a moving highlight band.
- 120 effects total (107 2D + 13 3D); 322 tests green.

## What's new in 0.51 鈥?effects work in 2D and 3D and stack

- **3D-bake sampling fixed**: effects that run on the layer-local raster now sample the source with
  its own UVs (`layerLocalPass` 鈫?`LocalSource = 1`) instead of mapping through composition UVs,
  which used to sample the wrong region (or clamp) inside the 3D bake.
- **3D primitives are applied once**: `s3d_*` effects are excluded from the 3D bake and forced into
  the composition pass, so the layer's world matrix is not applied twice.
- **Stacking is tested**: a new pipeline test chains 2D鈫?D銆?D鈫?D銆?D鈫?D and three-effect chains,
  checking each pass keeps the frame size, produces pixels and raises no GL error.
- 322 tests green; 114 effects.

## What's new in 0.50 鈥?heart primitive and texture mapping for 3D

- **蹇冨舰** (`AM 路 3D 褰㈢姸`): a parametric heart outline extruded into a solid primitive.
- Every 3D shape now exposes **texture mapping**: repeat, offset X/Y and rotation. Repeat values
  other than 1 switch to `fract` tiling; otherwise the UV is clamped.
- Fixed a real GLSL error found by the headless link test: comparing a `vec2` with a `float`
  (`abs(TexRepeat) > 0.0001`) is invalid in GLSL 150 and had made the mesh program fail to link.
- 114 effects total (101 2D + 13 3D); 321 tests green.

## What's new in 0.49 鈥?twelve 3D primitives with lighting

- Six more primitives: **鐜舰, 鍗佸瓧妫辨煴, 鍏潰浣? 鍏１鏌? 涓夋１鏌? 鏄熷舰妫辨煴** (`AM 路 3D 褰㈢姸`).
- Mesh vertices now carry **normals**; the fragment stage applies directional diffuse + specular
  shading (鍏夌収瑙掑害/楂樺害/寮哄害銆佺幆澧冨厜銆侀珮鍏夐攼搴? using a normal matrix derived from the MVP.
- 113 effects total (101 2D + 12 3D); the contact sheet shows all twelve primitives rendering.
- 321 tests green.

## What's new in 0.48 鈥?real 3D primitives

- New mesh pipeline: `Mesh`, `MeshShapes` and `MeshRenderer` render **绔嬫柟浣?/ 鍦嗘煴浣?/ 鐞冧綋 /
  妫遍敟 / 鍦嗛敟 / 鍦嗙幆** with the layer content as the surface texture. The vertex stage uses real
  `Position/UV` attributes and an MVP matrix built from the layer's own world matrix and projection.
- The primitives are convex, so back-face culling alone resolves occlusion: the colour-only
  framebuffers stay unchanged (no depth attachment, no render-graph changes).
- Six `s3d_*` effects (`AM 路 3D 褰㈢姸`) with rotate X/Y/Z, thickness and scale; the headless shader
  test links the dedicated mesh program and the contact sheet shows all six rendering.
- 107 effects total (101 2D + 6 3D); 321 tests green.

## What's new in 0.47 鈥?past one hundred reference effects

- **缈昏浆**, **鍊炬枩**, **鎸ゅ帇** (`AM 路 鍙樻崲`): angle-driven flip, shear and axis squeeze.
- **閬僵妯＄硦** (`AM 路 妯＄硦`): blur restricted to alpha edges.
- **鑹茶皟** (`AM 路 璋冭壊`): per-channel gain plus lift.
- **鍧楃姸婧惰В** (`AM 路 閫忔槑搴): block-wise dissolve; progress 1 is the identity and is declared
  through `neutralWhen`.
- **101 reference-compatible effects**; the neutrality test probe now respects each slider's range
  (identities at the maximum, like a dissolve at progress 1, step down instead of up).
- 321 tests green.

## What's new in 0.46 鈥?channel maps and matte composites

- **閫氶亾鏄犲皠**, **HSV 閫氶亾鏄犲皠** (`AM 路 璋冭壊`): channel swizzles and HSV gain/rotation, both with
  identity mappings.
- **濉厖鑳屽悗**, **瀹炶壊閬僵**, **閬僵杈圭紭** (`AM 路 鍚堟垚`): fill transparent areas, solid fill inside
  alpha, coloured fringe on alpha edges.
- **闂儊** (`AM 路 閫忔槑搴): frame-quantised alpha flicker.
- 95 reference-compatible effects; 321 tests green.

## What's new in 0.45 鈥?borders, beams and arrays

- **杈规**, **鎻忚竟閿ュ害** (`AM 路 缁樺埗`): width/feather border and a tapered outline.
- **鑳介噺鏉?*, **鍏竟褰㈤樀鍒?* (`AM 路 鐢熸垚`): glowing beam and hex grid glow.
- **褰╄壊鍒嗙** (`AM 路 璋冭壊`): radial RGB split.
- 89 reference-compatible effects; every body is in layer space with an identity value.
- 321 tests green.

## What's new in 0.44 鈥?procedural shapes and edge tools

- **寰勫悜鍏夌嚎**, **鐐归樀**, **鏄熷舰** (`AM 路 鐢熸垚`): procedural ray fans, dot grids and star polygons
  with colour and alpha.
- **骞虫粦杈圭紭**, **鍗婅皟绾?* (`AM 路 缁樺埗`): alpha edge averaging and luminance-modulated halftone
  lines.
- 84 reference-compatible effects; all in layer space with declared identities (alpha 0 / radius 0).
- 321 tests green.

## What's new in 0.43 鈥?colour, drawing and blur batch

- **RGB 浼介┈** (per-channel gamma), **楗卞拰搴︿笌鑷劧楗卞拰搴?*, **鍗曡壊寮鸿皟** (keep one hue, desaturate
  the rest) in `AM 路 璋冭壊`.
- **鍙戝厜杈圭紭** (edge glow) in `AM 路 缁樺埗`; **婕╂丁妯＄硦** (per-radius rotational blur) and
  **鍐呮ā绯?* (alpha-masked blur) in `AM 路 妯＄硦`.
- 79 reference-compatible effects; every body is in layer space and declares an identity.
- 321 tests green.

## What's new in 0.42 鈥?animated transform effects

- **鎶栧姩**, **鎽囨憜**, **鑴夊姩缂╂斁**, **鏃嬭浆**, **闅忔満浣嶇Щ**, **缂╂斁杈呭姪** in `AM 路 鍙樻崲`.
- Time-driven effects read the composition `Time` uniform, so preview, pause and export agree;
  every one declares a zero value (amount/angle/speed), and scale assist reports neutral at 1脳1.
- 73 reference-compatible effects; 321 tests green.

## What's new in 0.41 鈥?spin blur, liquify, sketch, chromatic blur, bokeh

- **鏃嬭浆妯＄硦** (24-tap angular blur), **褰╄壊妯＄硦** (per-channel directional blur) in `AM 路 妯＄硦`.
- **娑插寲** (two-channel fbm displacement in layer pixels) in `AM 路 鍙樺舰`.
- **绱犳弿** (edge + inverted luminance over a paper colour) and **鏁ｆ櫙** (bright-pass disc sampling)
  in `AM 路 椋庢牸鍖朻.
- 67 reference-compatible effects, all in layer space; 321 tests green.

## What's new in 0.40 鈥?reference effects now work in layer space

- Every reference effect body now runs in **layer coordinates**: `luv = layerUv(vUv)`, pixel radii in
  `LayerSize` (layer pixels), resampling through `sampleLayer()` (which maps layer pixels back to
  composition UVs), and `LayerTexel` for texel offsets.
- 58 single-input bodies were migrated mechanically and the four backdrop-reading effects
  (缃崲鍥?娓愬彉缃崲/鐜荤拑/鍏夊寘瑁? by hand 鈥?`Source` is sampled in layer space while `Comp` stays in
  composition space.
- Consequence: ripples, bends, glows and blurs on a small layer are anchored to that layer instead
  of the composition centre; directional effects follow layer-local y-down coordinates, which is the
  reference convention (and why some sheet tiles read flipped).
- 321 tests green; all 62 shader bodies still link.

## What's new in 0.39 鈥?lens flare and proxy-correct pixel units

- **闀滃ご鍏夋檿** (`AM 路 椋庢牸鍖朻): radial rays plus a core glow driven by a light position, colour and
  intensity (intensity 0 returns the layer). 62 effects total.
- **Proxy-resolution fix**: reference effects now convert pixel parameters through the composition
  size (`Size`/`Texel` from the render context) instead of the proxy framebuffer size, and the
  separable blur receives composition texels. Previously every radius/distance shrank by the proxy
  scale, so preview looked weaker than the full-resolution export.
- 321 tests green.

## What's new in 0.38 鈥?edge light and a wider zero contract

- **杈圭紭鍏?* (`AM 路 椋庢牸鍖朻): 16-direction alpha-ring sampling with colour and strength;
  radius 0 returns the layer untouched. 61 effects total.
- **Custom identity tests**: `AmEffectDef.neutralWhen(predicate)` covers effects whose neutral value
  is not zero 鈥?骞抽摵 at scale 1 (offset/gap/mirror zero) and 鍍忕礌鍖?at size 1 (no vignette/
  saturation/offset) now report neutral and skip the pass, exactly like effects whose identity
  parameter is zero.
- The neutrality tests were extended accordingly (probe values now respect slider minimums).
- 321 tests green.

## What's new in 0.37 鈥?overlay and desaturation batch

- **鍘昏壊**, **鍗曡壊** (`AM 路 璋冭壊`): luminance mix and tinted monochrome.
- **娓愬彉鍙犲姞**, **寰勫悜娓愬彉** (`AM 路 鐢熸垚`): two-colour linear/radial overlays with normal/screen/
  multiply/add blending and an alpha slider (alpha 0 = original layer).
- Failed effect programs are now cached negatively, so a broken body costs one log line instead of
  one compile attempt per frame; the transparency category also gets its own library icon.
- 60 reference-compatible effects; 321 tests green.

## What's new in 0.36 鈥?hex shift, ripple pulse, inner glow and a real UI milestone check

- **鍏竟褰綅绉?* (per-hex random displacement), **娉㈢汗鎵╂暎** (time-driven radial wave),
  **鍐呭彂鍏?* (16-tap edge ring) 鈥?56 reference-compatible effects total.
- Milestone verification in the real client (single launch, project restored afterwards):
  effect-library row icons, the inspector section dividers between 璺熻釜鍣?and 缁戝畾/璺熻釜, and the
  tracker UI are visible in `docs/verify/round09-ui-milestone.png` and
  `docs/verify/round09-ui-dividers.png`.
- Debug-only screenshot hooks (`debugExpandEffects`, `debugInspectorPage`, `debugInspectorScroll`,
  `debugWindow`) let future verification capture a specific panel state in one command.
- 321 tests green.

## What's new in 0.35 鈥?procedural and colour batch

- **鐑姏鍥?* (five-stop heat ramp by luminance), **鑹插亸** (cyan-red / magenta-green / yellow-blue
  shift), **浜戦浘** (fbm clouds with time evolution and two-colour mapping) in `AM 路 璋冭壊` /
  `AM 路 鐢熸垚`.
- **绛夐珮绾?* (luminance contour lines), **鍏竟褰㈠钩閾?* (hexagonal mosaic with gap colour),
  **鍗锋洸** (radial curl in layer pixels) in `AM 路 椋庢牸鍖朻 / `AM 路 閲嶅` / `AM 路 鍙樺舰`.
- 53 reference-compatible effects; 321 tests green.

## What's new in 0.34 鈥?one entry per effect in the library

- The reference batch introduced a second row for effects the classic catalogue already had
  (楂樻柉妯＄硦, 涓囪姳绛? 闀滃儚, 缃戞牸, 鑹插僵鏄犲皠, 缃崲鍥? 鏋佸潗鏍? 鎻忚竟, 鑹茬浉鏄犲皠): the library showed two
  identical names in two categories. The superseded ids are now hidden from the library (including
  the recent list) while staying registered and renderable, so old projects keep working.
- `EffectLibraryNamingTest` pins the contract: visible names are unique, hidden ids still have a
  renderer, and each hidden id has an AM successor.
- 321 tests green.

## What's new in 0.33 鈥?tile/repeat and paint batch

- **骞抽摵**, **鏃嬭浆骞抽摵** (`AM 路 閲嶅`): tiling with offset/gap/mirror and radial sector repetition.
- **鑴夊姩涓嶉€忔槑搴?* (`AM 路 閫忔槑搴): time-driven alpha pulse.
- **妫曡鑹?*, **鑹插甫**, **娌圭敾** (`AM 路 璋冭壊` / `AM 路 椋庢牸鍖朻): sepia mix, per-channel posterise,
  and a four-quadrant Kuwahara pass for brush-like smoothing (radius 0 returns the input).
- 47 reference-compatible effects total; 319 tests green.

## What's new in 0.32 鈥?colour grading and light batch

- **Six new effects**: 鍒嗙鑹茶皟銆佸弻鑹茶皟銆佽壊褰╂槧灏勶紙8-stop palette锛夈€佽壊鐩稿亸绉?in `AM 路 璋冭壊`;
  婕忓厜锛坱ime-driven band锛塧nd 鍏夊寘瑁癸紙backdrop spill around edges锛塱n `AM 路 椋庢牸鍖朻.
- Palette map indexes an eight-colour LUT by luminance; split tone and duotone keep the original
  alpha and expose balance/amount sliders.
- Neutral declarations (`amount`/`alpha`/`strength`) for all six; shader link + neutrality tests
  cover them. 319 tests green.

## What's new in 0.31 鈥?backdrop-reading effects (displacement map, gradient displace, glass)

- The effect pipeline now hands each layer the **composition below it**: `CompRenderContext.backdrop`
  is set around the layer effect loop and the adjustment pass, and `AmEffectDef.comp()` marks
  effects that sample it through `Comp`.
- **缃崲鍥?* (RG/GB/luminance channels, amount in layer pixels), **娓愬彉缃崲** (composition gradient),
  **鐜荤拑** (refraction + tint + highlight) added under `AM 路 鍙樺舰` / `AM 路 椋庢牸鍖朻.
- Backdrop effects are excluded from the layer-local 3D bake and run once after projection, so a 3D
  plane cannot apply them twice; without a backdrop (isolated preview) they fall back to their own
  input instead of failing.
- 319 tests green.

## What's new in 0.30 鈥?zero value is proven pixel by pixel

- **Pixel-level zero test.** `EffectPipelineTest.zeroValuesRestoreTheInputPixelForPixel` runs
  thirteen representative effects (block shatter, wave, blur, turbulent displace, bend, gaussian
  blur, pinch/bulge, ripple, swirl, mirror, stretch, slice glitch, chromatic aberration) through the
  real GL pipeline at their identity value and requires the output to equal the input **exactly**.
- **Block shatter near-zero fix.** The shard border used to be applied regardless of the cell's
  progress: at progress 0.005 (no shard had started moving) the layer was covered by a dark grid,
  which is what "pulling it to zero does not restore the layer" looked like. The border now fades
  in with the cell's own progress; a regression test allows resampling noise but not edge darkening.
- 319 tests green.

## What's new in 0.29 鈥?generative and distortion batch

- **Eight more effects**: 褰╄壊鍍忓樊銆佺Щ杞存ā绯娿€佽酱鍚戞媺浼搞€佸垏鐗囨晠闅?(distortion/style) and
  鍥涜壊娓愬彉銆佹潯绾广€佺綉鏍笺€佹槦绌?(generative). Each has its reference parameter set and a zero-value
  declaration where one exists.
- The generative group now includes gradient/stripe/grid/star overlays with colour pickers and
  alpha, all keyframeable through the existing scalar channels.
- 317 tests green; contact sheet shows the new row rendering.

## What's new in 0.28 鈥?blur batch with a two-pass path

- **Five blur effects**: 楂樻柉妯＄硦銆佹柟妗嗘ā绯娿€佸姩鎰熸ā绯娿€佸緞鍚戞ā绯娿€侀暅澶存ā绯?(`AM 路 妯＄硦`).
- Pure blurs no longer go through a fragment body: `AmEffectDef.blur(...)` routes them to the
  existing separable blur (premultiply 鈫?horizontal 鈫?vertical 鈫?unpremultiply), with an optional
  iteration count for the box kernel. A zero radius skips the pass entirely.
- Directional (motion), radial (zoom) and lens blurs are single-pass sample loops with their own
  length/angle/centre/radius parameters.
- 317 tests green; the contact sheet shows all five rendering.

## What's new in 0.27 鈥?third effect batch (polar, fractal warp, colour tools)

- **Six more reference-compatible effects**: 鏋佸潗鏍囥€佸垎褰㈡壄鏇层€佽壊褰╁寲銆佹浛鎹㈤鑹层€佹笎鍙樻槧灏勩€佸緞鍚戦槾褰?
- Shared shader helpers grew the noise/fbm/rotation set, so noise-driven effects are plain
  definitions from now on.
- All new effects carry their zero-value declaration (strength/amount/size) and are covered by
  the shader link test and the neutrality test.
- 317 tests green; effect contact sheet (`docs/verify/effect-pipeline.png`) now renders every
  batch through the real composition pipeline.

## What's new in 0.26 鈥?distort batch, zero-value audit, library icons

- **Six more reference-compatible effects**: 鏀剁缉涓庤啫鑳€銆佸渾褰㈡尝绾广€佷竾鑺辩瓛銆侀暅鍍忋€佹棆杞壄鏇层€佸儚绱犲寲
  (`AM 路 鍙樺舰`), all data-driven and covered by the headless shader link test.
- **Zero means the original layer.** Seventeen existing effects had no neutral branch, so a value
  of 0 still ran their shader; brightness/contrast, hue/saturation, exposure, tint, levels, curves,
  invert, vignette, noise, grain, shake, matte choker, colorama, color grade and the new effects now
  short-circuit at their identity value. `EffectNeutralTest` pins the contract and lists the
  effects that legitimately have no zero state.
- **Effect library icons**: every effect row now carries a 16 px category icon from the BBS icon
  atlas (colour wheel, morph, blur sphere, checkerboard, key, exchange, cylinder, font鈥?, with a
  reserved gutter so names stay aligned; category headers keep their tree arrow.
- 317 tests green.

## What's new in 0.25 鈥?the full blend set and clearer inspector groups

- **Blend modes 13 鈫?30.** The layer blending list now also has 钂欑増 / 鎺掗櫎锛堣挋鐗堬級 as alpha
  operations (the backdrop keeps its colour, only coverage changes), plus 绾挎€у姞娣便€佺嚎鎬у噺娣°€?  绾挎€у厜銆佷寒鍏夈€佺偣鍏夈€佹煍鍏夊彔鍔犮€佸噺鍘汇€佸垝鍒嗐€佽緝鏆楅鑹层€佽緝浜鑹层€佽壊鐩搞€侀ケ鍜屽害銆侀鑹层€佹槑搴︺€?  棰滆壊鐩镐箻. Existing project files keep their numeric mode ids.
- **Every blend formula is pinned by a pixel test.** `BlendModesTest` composites 1脳1 pixels through
  the real GLSL and compares them with the documented formula, including the two mask modes.
- **Inspector dividers.** Each collapsible section is preceded by a two-pixel hairline groove, so
  the right-hand panel reads as groups instead of one long run of headers.
- **Data-driven effect runtime + first reference batch.** Effects are now definitions (parameter
  schema + one fragment body) compiled lazily: 鑹插僵骞宠　銆佽壊娓┿€侀珮鍏変笌闃村奖銆佸崱閫氥€侀暱闃村奖銆佸集鏇层€?  鎻忚竟 ship in the new `AM 路 璋冭壊` / `AM 路 椋庢牸鍖朻 / `AM 路 鍙樺舰` groups. The library, sliders,
  colour pickers and keyframe channels are generated from the definition, and the zero-value
  contract skips the pass so those effects return the original layer at 0.
- Research and roadmap for the effect-library parity work: [`docs/EFFECT-LIBRARY-PLAN.zh.md`](docs/EFFECT-LIBRARY-PLAN.zh.md).

## What's new in 0.24 鈥?wave/chroma/itrp/钂欑増 fix round + 鐐硅窡韪櫒

- **娉㈡氮鍙樺舰 / 缃崲鍥?/ 婀嶆祦缃崲鐜板湪鐪熺殑浼氬姩**銆侱ISTORT 鐫€鑹插櫒鐨勪綅绉婚噺琚敊璇湴闄や簡涓ゆ鍥惧眰
  灏哄锛屼竴涓?48 px 鐨勪綅绉诲彧绉诲姩 ~0.1 px鈥斺€旇繖涓変釜鏁堟灉姝ゅ墠涓€鐩?鏈敓鏁?銆傜潃鑹插櫒鏀逛负鐩存帴鎶婂浘灞?  鍍忕礌浣嶇Щ浜ょ粰 layer鈫抍omp 鍩猴紱3D 鐑樼剻鐨勫弻閲嶇缉鏀句竴骞朵慨鎺夈€?- **缁垮箷鎶犲儚閲嶅仛**锛氳壊搴︽ā寮忔敼涓鸿壊鐩稿樊 + 楗卞拰搴﹂棬鎺э紙浜害鏃犲叧锛夛紝鎵撳厜鐨勭豢骞曚笌鍋忛粍缁块兘鑳芥姞鍑€锛?  鏂板 钂欑増榛戝満/鐧藉満 娓呯悊婊戞潌锛涙孩鑹叉姂鍒跺彧鍦ㄥ睆骞曡壊褰卞搷鍖哄帇缁匡紝涓讳綋缁胯壊涓嶅彈褰卞搷锛屽彔鍔犲湪鎶犲儚
  涓婄殑鏁堟灉涓嶅啀娣峰叆娈嬬暀缁胯竟銆?- **鍒嗗舰鏉傝壊鎸?AE 璇箟閲嶅仛**锛氬綊鍏ャ€屽櫔鐐逛笌棰楃矑銆嶏紝鍣０鍩熸敼鍥惧眰鍍忕礌绌洪棿锛堢缉鏀?= 鐗瑰緛灏哄锛夛紝
  婕斿寲閫愬叓搴︽棆杞櫔澹板煙鈥斺€旀墦鍏抽敭甯у悗浜戦浘鍙樺舰鑰岄潪骞崇Щ銆?- **itrp / iterationRP 榛戝睆淇**锛歩trp 鐨?final pass 杈撳嚭 alpha=0锛屾崟鑾风殑涓栫晫甯ф寜绾圭悊 alpha
  娣峰悎鏃舵暣甯т笉鍙鈥斺€旀崟鑾峰悗鏂板 OPAQUE pass 鎶?alpha 褰掍竴涓?1锛汭ris 鏈€缁堝抚閽╁瓙缁戜笉涓婃椂鑷姩
  鍥為€€ colortex0 鎹曡幏锛岄瑙堣鍑烘樉绀烘崟鑾风姸鎬併€?- **钂欑増**锛?D/璋冩暣灞?杞ㄩ亾閬僵鐨勮挋鐗堢粺涓€鎸夋覆鏌撳垎杈ㄧ巼鏍呮牸鍖栵紙涓?3D 鐑樼剻涓€鑷达級锛屽姩鐢昏挋鐗堢紪杈?  涓嶅啀姣忓抚鏍呮牸鍖栨暣骞呭悎鎴愶紝棰勮鍗￠】娑堥櫎銆?- **鐐硅窡韪櫒**锛氭鏌ュ櫒鍙柊寤鸿窡韪櫒锛堥敋鐐?妯℃澘/鎼滅储/缃俊搴?鑼冨洿锛夛紝姣忔樉绀哄抚鍗忎綔寮忓垎鏋愪竴甯э紱
  缁撴灉鍙竴閿儤鐒欏埌鎵€閫夊浘灞傜殑浣嶇疆鍏抽敭甯э紝鎴栭€氳繃 `tracker.<id>.x / .y` 琛ㄨ揪寮忛┍鍔ㄥ弬鏁般€?
## What's new in 0.23 鈥?this is now BBS AE

**BBS Compositor is now BBS AE.** The mod id stays `bbsae`, so existing projects keep working.
This release collects the fixes since 0.22.1:

- **Faster preview** 鈥?frame-cache writes moved to a background writer with atomic files; cache keys now include the project and entity/replay parameters, and the timeline stops copying its layer list every frame.
- **Precise curve editing** 鈥?only real Bezier handles are drawn, the nearest handle wins, and handle drags use a press snapshot so they no longer jump or drop the last mouse sample.
- **Readable layer library** 鈥?cards size themselves from the available width and wrap Chinese descriptions with real font metrics.
- **Better transform handles** 鈥?edges scale one axis, corners stay proportional (Shift frees them), and 3D layers use a ray/plane hit test so the handle tracks the pointer in perspective.
- The first sponsor is now **銆愭槦杈颁箙姊﹀悰_Star Dm Sov銆?*, in gold.

The full list is in [`CHANGELOG.md`](CHANGELOG.md).

## What's new in 0.22.1 鈥?imported fonts actually display

The font path is fixed from first principles: a font file is a **partial map** from code points to
glyphs, so "the imported font does not show" turned out to be three separate facts stacked on top
of each other.

- **Per-character fallback.** Minecraft answers a code point the font has no glyph for with a
  missing glyph that has *no renderer and a 1 px advance*; a Latin font with Chinese text therefore
  rendered **nothing at all**. The compositor now asks the font file itself
  (`stbtt_FindGlyphIndex`, cached per file) and draws each character with the chosen font where it
  has the glyph, and with BBS' default font where it does not 鈥?measured and drawn with the same
  choice, and the fallback glyph is scaled to the custom font's size so mixed text lines up.
- **`.ttc` collections are supported.** 寰蒋闆呴粦/瀹嬩綋/榛戜綋 ship as TrueType collections, and BBS'
  stb loader only reads a single font at offset 0 鈥?"imported but BBS cannot parse". The importer
  now extracts face 0 into a standalone sfnt (`msyh-face0.ttf`), which every loader understands.
  The system-font picker lists `.ttc` files and names them from their first face.
- **Layout size vs glyph atlas size.** A 200 px text layer used to be a 96 px atlas blown up 2脳;
  the importer now passes the oversampling factor so the atlas matches the drawn size.
- **The UI explains the rest.** The text inspector reports when the font file is missing and how
  many characters of the current text the font cannot draw (with a sample), instead of silently
  falling back. Importing reports the same.
- Verification harness: `-Dbbsae.debugFonts=true` logs font resolution and per-glyph advances;
  `runClientView -PbbsaeFontShot=fontsheet [-PbbsaeFontImportSrc=<font>]` renders a real
  composition sheet with a custom font, a system `.ttc`, and the default font. Screenshots:
  [`round45-font-timesbd-fallback.png`](docs/verify/round45-font-timesbd-fallback.png),
  [`round45-font-msyh-ttc.png`](docs/verify/round45-font-msyh-ttc.png).
- New tests: `FontCoverageTest`, `FontImportTtcTest` (real `msyh.ttc` extraction + stb coverage).

## What's new in 0.22.0 鈥?effect & layer bug-fix release

This release is a deep, first-principles pass over the two subsystems that produced the most
complaints: **effects (鏁堟灉)** and **layers (鍥惧眰)**. Every fix below has a regression test or a
pixel-level harness behind it; the suite is now 265 tests and includes a real-GL
`EffectPipelineTest` that drives every catalogue entry through `EffectRenderer` and writes a
contact sheet.

**Effects**

- **Blur kernels were 8x too wide.** The 17-tap kernel multiplied the radius by the *tap index*
  instead of the normalised `i / 8`, so a 12 px blur sampled up to 96 px. Every blur, glow, drop
  shadow and stroke was far too strong, and the halo was clipped by a padding that only reserved
  `radius`. Fixed in the shader; `blurRadiusIsInPixelsNotTapCounts` pins it.
- **Preview and export now use the same pixel units.** Shaders were fed the framebuffer size
  while `EffectSpace` works in composition pixels, so at any proxy scale (<1) every pixel-valued
  parameter (radius, offset, mosaic size, halftone dots) grew by `1/scale`, and local resampling
  drifted by the same factor. Effects visibly jumped while dragging a curve and never matched the
  1:1 export. Every family now binds the composition size.
- **AE-style centre parameters are layer-local.** 鐞冮潰鍖?/ 鍏夊琛ュ伩 / CC 闀滃ご / CC 鍊炬枩 / 鏀惧ぇ /
  CC 娉㈢汗鑴夊啿 / 鍙樻崲 / CC 寰勫悜蹇€熸ā绯?compared a 0..1 layer coordinate against composition UV, so
  the effect was dead unless the layer filled the comp, and 涓績 Y was mirrored. Centres now go
  through `EffectSpace.localToCompUv`, like 寰勫悜妯＄硦 already did.
- **钂欑増鏀剁缉 no longer erases the layer.** `choke` (px) was compared against a 0..1 alpha, so the
  default `smoothstep(2, 4, a)` was zero everywhere. It is a real alpha erode/dilate now, with
  softness in pixels.
- **Wipes/dissolve had an inverted identity.** Progress 0 is fully hidden in the shader, so the
  neutral value is 1; the old rule made the first keyframe pop the layer on for one frame.
- **Keyed soft edges lost alpha twice** (`c.a * alpha` where alpha already contained `c.a`).
- **鏆楄 did nothing at its defaults.** The in-layer distance is at most 0.707, but the default
  radius was 0.75; the distance is now normalised by the corner distance.
- **缃崲鍥?s 鍣尝缂╂斁 slider was dead** (the shader hardcoded 80 cycles), and **鏂瑰悜妯＄硦** never set
  the shared blur kernel's `Mode`, so a box blur elsewhere in the comp changed its kernel.
- **鎶栧姩/闇囧睆 now respects 鏁堟灉寮哄害** (0 = no shake) instead of ignoring the slider.
- **Warp effects round-trip through premultiplied alpha**, removing dark halos around text and
  semi-transparent edges.
- **澶栧彂鍏?is drawn under the layer** (it used to be identical to 杈夊厜 and wash colour into the
  layer), and 鐫€鑹?s default strength is 0.5 so adding it no longer flattens the layer to one colour.

**Layers**

- **Delete / Ctrl+D act on the whole selection.** With several tracks highlighted, Delete removed
  only the primary layer and Ctrl+D copied only it.
- **Duplicating a selection remaps parent and track-matte references** to the *new* ids, so a
  duplicated null still drives its duplicated children; duplicated animated layers are also offset
  visibly (the +24 px used to be ignored once a layer had transform keys).
- **棰勫悎鎴?keeps the group's z position.** The insertion index was computed before the layers were
  removed, so the precomp landed one slot too high.
- **棰勫悎鎴?no longer paints an opaque black rectangle.** Nested comps now render with a transparent
  stage, and a 3D precomp with any effect/style/mask no longer exports the "棰勫悎鎴愶紙鑽夌锛?
  placeholder text.
- **Track mattes are applied after effects/styles on 2D and 3D alike**, and the matte layer's own
  masks are honoured. Cameras/nulls/adjustment layers are rejected as mattes instead of silently
  multiplying the target by zero.
- **鐙樉 (solo) is honoured by the 3D workspace, by both hit tests, and by adjustment layers**, not
  just by the composition graph.
- **Animated masks and 鏂囨湰妗嗗搴?now invalidate the 3D raster** (a keyed mask used to freeze at its
  first frame, in the export too).
- **Mirroring works**: a negative scale is preserved instead of being clamped to `+0.0001`.
- **`startTime` is applied after the speed change** (`(t - in) * timeScale + startTime`); trimming
  and splitting compensate consistently.
- **VRAM leaks fixed**: the live-world preview leaked one composition-sized FBO per display frame
  (BBS preview / entity / depth comps), the 3D source cache leaked its staging buffer when a
  style/mask replaced it, and error paths leaked the accumulation target.
- **Guide (鍙傝€冨眰) layers no longer reach the recorded film or the green pre-render frames**, and the
  鏁堟灉/钂欑増 A/B toggles now work on green frames and on baked 3D layers.
- **Texture state can no longer go stale** after the FBO pool deletes a texture: binds are forced
  raw and the pixel-store mode is restored by `GlStateGuard`.

**Editor**

- **Inspector toggles are undoable and saved** (鍙/閿佸畾/鐙樉/鍙傝€冨眰, 鏃堕棿閲嶆槧灏? 閬僵, 钂欑増, 鏍囪,
  缁戝畾, 鏂囧瓧, 鎹㈣), so Ctrl+Z now reverts the checkbox you just clicked. **閲嶇疆鍙樻崲** clears all
  nine transform channels, including the 3D ones.
- **A second 璋冭壊 effect can no longer reuse an effect id** (parameter edits used to land on the old
  effect), effect apply is gated to a single unlocked layer, effect fold state is per layer, and
  right-clicking inside a multi-selection keeps it so the batch menu is reachable.
- A reorder no-op no longer wipes the redo stack; a reorder keeps `selectedIds` and `selectedLayer`
  in sync; marker ids are unique; deleting a layer clears bindings that pointed at it.
- The 鍥惧眰搴?modal now blocks clicks and scrolling behind it, and its grid no longer sticks to the
  mouse when the release happens outside the dialog.
- The README's Chinese text was mojibake from an old encoding round-trip; it is repaired.

## What's new in 0.21.5

- **System fonts are available in the text inspector.** A searchable "System fonts鈥? list scans
  `Windows\Fonts` (and the macOS/Linux font folders), reads each font's OpenType name table for a
  real display name, and imports the chosen file into BBS' fonts folder through the normal link
  loader. Fonts are copied only when selected.
- **Name-table parsing bug fixed.** The parser read `count` from the name table's `format` field,
  so every font looked like it had zero records and the list fell back to filenames; `count` is at
  offset 2. `SystemFontsTest` pins the real Arial name.
- TTC collections are not listed: BBS' stb loader cannot address an individual face in a
  collection. TTF/OTF are listed; protected folders and malformed files are skipped safely.
- 254/254 tests pass.

## What's new in 0.21.4

- **Imported fonts actually render.** The text layer measured with the custom FontRenderer but
  drew glyphs through `Batcher2D.text`, which always uses the default font; metrics changed while
  the letters did not. Glyph drawing now sets the batcher font to the layer's font (and restores
  it afterwards), so measurement and rendering agree.
- **Import flow mirrors original BBS.** The text section now has "Open BBS fonts folder" and
  "Refresh font list" buttons next to import; fonts copied to `config/bbs/assets/fonts` are listed
  immediately, the same folder/link contract BBS uses.
- Import still verifies the copy through BBS' own stb loader and explains TTC/WOFF/unsupported
  OTF/CFF instead of silently selecting a font that falls back.
- 250/250 tests pass.

## What's new in 0.21.3

- **Projected 2D layers no longer darken.** `projectPlane` returns a premultiplied framebuffer
  after drawing straight-alpha content into a transparent target; it is now un-premultiplied
  exactly once. This fixes dark fringes on flat layers under a tilted camera and dark luma
  track mattes projected onto a 3D target.
- **Geometric warps no longer bleed black at alpha edges.** `AE_DISTORT` now round-trips through
  premultiplied alpha like the blur family.
- 250/250 tests pass.

## What's new in 0.21.2

- **The first Subtract / Exclude mask now cuts from the full layer.** The first mask used to be
  treated as an empty canvas, so a single Subtract/Exclude showed the mask instead of the outside.
  The stack now matches AE/Alight Motion: Add/Intersect define the area, Subtract/Exclude cut out
  of the layer.
- **Masks are editable on the canvas.** The 2D/camera preview projects each mask with the same
  `CompScene.project` path as the selection box, draws points and bezier handles, and lets you drag
  points/handles. 3D layers use a mouse-ray/plane intersection, so the point follows the cursor on
  the plane. Drags write keyframes at the playhead when a mask channel already exists.
- **3D track mattes work.** The "鐢ㄤ綔钂欑増 / 鐢ㄤ綔鎺掗櫎" buttons used to be ignored on 3D targets; the
  matte is now applied after projection, and a 2D matte over a 3D target is projected first.
- 250/250 tests pass, including `MaskCombineTest` for the first-mask and combined modes.

## What's new in 0.21.1

- **AE effect programme, batch 2: 12 new distort effects** 鈥?offset, polar coordinates,
  spherize, optics compensation, CC lens, CC slant, magnify, CC ripple pulse, CC bend, CC split,
  rolling-shutter repair and a 2D transform. They join displacement, bulge, pinch, twist, wave,
  turbulent displace and mirror.
- One `AE_DISTORT` shader family supplies the modes. Each is a single-input resample, so it is
  baked into the layer raster before projection and follows the 3D plane; `EffectPadding` and the
  proxy-radius conversion are wired in, and the composition does not apply them twice.
- Neutral values and `global_strength` are covered: zero amount/offset/identity transform returns
  the original layer.
- 248/248 tests pass, including the headless GLSL compile test for `AE_DISTORT`.

## What's new in 0.21.0

- **AE effect programme, batch 1: Blur & Sharpen is complete (17/17).** Nine new effects 鈥?  bilateral, smart, CC cross, CC radial fast, lens/bokeh, channel, compound, unsharp mask and
  camera-shake deblur 鈥?join the existing Gaussian/box/directional/radial blurs, sharpen and
  linear wipe. One `AE_BLUR` shader family supplies the modes; the pass works in premultiplied
  alpha so falloffs do not darken.
- The new effects participate in the existing strength, neutral-value, 3D padding and
  proxy-radius systems, so they behave like every other effect in 2D and 3D.
- This is a staged programme, not a claim to all 200+ AE effects: audio, particles, tracking,
  Cryptomatte/EXtractoR and temporal effects need engines this compositor does not have yet.
  The roadmap is in the round document.
- 246/246 tests pass; the new shader is covered by the headless GLSL compile test.

## What's new in 0.20.4

- **3D layers reserve room for their effects.** A blur, glow, drop shadow or stroke used to be
  clipped at the layer rectangle because the 3D raster was exactly the layer box. The raster is now
  padded by the effect kernel's radius (`EffectPadding`), so the falloff is complete in the viewport,
  the camera view and the export.
- **Layer styles are baked with the layer.** Drop shadow, outer glow, stroke and gradient overlay
  were applied after projection (or not at all in the 3D workspace). They now run in layer space
  after the effects, are skipped by the composition pass, and follow the plane.
- **Masks work on 3D layers.** The mask is rasterized in layer space, multiplied in before the
  effects, and the layer is now projected through the baked path even when the mask is its only
  pixel operation.
- 246/246 tests pass.

## What's new in 0.20.3

- **3D-layer effects are applied exactly once.** The layer-space raster already bakes the
  non-depth stack; 0.20.0 then ran the same effects again after projection, doubling every blur,
  brightness and generator and replacing a layer-anchored gradient with a composition-space one.
- **Keyframed effect strength now animates.** `global_strength` is part of every new effect's
  parameter map, and `resolveEffect` also resolves `fx/<id>/...` channels that have no stored
  base value, so a strength keyframe on an older effect is no longer ignored.
- **Precomps inherit the parent pass.** A depth/guide pass with effects disabled no longer brings
  the precomp's effects back through a hardcoded `true`.
- 238/238 tests pass.

## What's new in 0.20.2

- **Fonts in subfolders resolve correctly.** The picker used to strip a font link down to its
  basename, so `fonts/sub/Name.ttf` was looked up as `fonts/Name.ttf` (often missing, or a
  different root font). The path under `fonts/` is now preserved; existing root-font projects are
  unchanged.

## What's new in 0.20.1

- **Custom fonts import for real.** The importer keeps the font's actual (including Chinese) name,
  never overwrites a different font with the same name, rejects TTC/WOFF with a clear message, and
  verifies the copy through BBS' own stb loader before claiming success. The font picker also scans
  the fonts folder directly, so the new font appears immediately.
- **Folder buttons work without AWT.** "Open folder" used `Desktop` and swallowed every exception;
  on a headless JRE or a launcher with a restricted desktop it did nothing. It now uses Minecraft's
  own OS integration, then AWT, then a direct `explorer`/`open`/`xdg-open` launch, and reports the
  path when all three fail.
- **Native file dialogs no longer fail silently.** On Windows, a dialog that returns "cancel" in a
  few milliseconds (stale HWND, remote session) is treated as a backend failure and falls back to
  AWT; an unowned Win32 dialog is skipped in favour of AWT's always-on-top owner.
- **Font browser starts in the system font folder.** Importing a font from the in-game browser opens
  `Windows\Fonts` first, and remembers a previously browsed font folder.

## What's new in 0.20.0

- **3D entity layers are transparent again.** A 3D replay-entity layer used to fall back to the
  un-keyed world plate, so its background was the whole frame. The screen-space matte now runs for
  2D and 3D entity layers alike; the 3D transform no longer re-projects the key away from the actor.
- **The matte no longer lags the preview.** The matte used Minecraft's tick delta while the world
  actor used the film runner's own fractional cursor. Both passes now use the actor pass's exact
  sub-tick, so the matte and the plate overlap frame for frame.
- **3D layer effects actually run.** `renderBaked3D` only projected the source raster, but the
  effect loop skipped every non-depth effect believing it had been baked. Effects now run once,
  after the projection (depth effects still run last).
- **3D layers keep their edges.** Flat layers are projected as a 16x16 cell grid instead of one
  giant quad, so a corner behind the camera no longer clips the whole layer.
- **Adjustment layers work in the 3D workspace.** A visible adjustment layer is applied as a
  full-screen post-process over the finished viewport image, so it grades the whole camera view.
- **Every effect has a keyframeable strength.** `global_strength` 0 is exactly the original layer,
  1 is the raw effect; generative effects (gradient, checkerboard, kaleidoscope) finally cross-fade.
  A neutral table also makes zero blur/glow/distortion/stroke/wipe values true no-ops.

## What's new in 0.19.3

- **Replay entity matte, fixed for real.** The depth-only target is replaced by a transparent
  actor-only **RGBA coverage** pass. The world depth is blitted into the same target first, so
  hardware `LEQUAL` still occludes actors behind blocks; the layer shader then samples an ordinary
  colour texture. No `DEPTH_COMPONENT24` sampling, no fragile sampler state.
- **The matte now persists.** It is generated once per world frame at `AFTER_ENTITIES`; the frame
  overlay no longer clears and re-renders it when a visibility toggle changes the request. Hiding
  and showing the layer no longer produces the "flashes once, then disappears" behaviour.
- **Editor onion skins no longer poison the matte.** The editor controller leaves the shared entity
  posed at an onion-skin tick; before the coverage pass the replay is re-applied at the current
  tick. Playback was already correct, which is why opening the project from film playback worked.
- **Header geometry.** The settings button no longer overlaps the focus button; the right cluster
  is computed from each control's own width and low-priority actions hide before they collide.
- **Collapsible texture filter.** The linear/auto/nearest chip expands to three explicit choices,
  remembers the open state, and survives narrow previews.

## What's new in 0.19.2

- The compositor preview now samples Iris' **final pass** instead of the pre-composite G-buffer,
  so BSL/Complementary-style exposure, bloom, tonemap and colour grading match the live game.
- Replay entity mattes read Iris' **own depth attachment** and match the live film id, fixing
  "mask shows 0 entities" under a shader pack.
- Fixed the 2D preview hint overprinting the resolution/shader readout.

## What's new in 0.19.0 / 0.19.1

**The thank-you release: a sponsor hall, BBS-style flight cameras, a faster AE curve, adaptive
layout, texture-filter intelligence, and a real fix for effects on 3D layers.**

*Sponsor credits.* **鏄熻景涔呮ⅵ鍚?*, **琛€涔嬩功** and **Szv鏂囨．鐗?7** now live in a dedicated
**Settings 鈫?璧炲姪鑰?* category, not on the editing page: a compact gold-lit board, three cards,
and one plain thank-you per person. No per-person motivational copy. The same names are in
`CREDITS.md`.

*BBS flight camera.* Camera settings now switch between **AE 鎽勫儚鏈?* (position + target) and
**BBS 鍘熺増鎽勫儚鏈?* (free yaw/pitch). A BBS camera shows a flight icon; press **F** to fly with
WASD, Space/Shift and the mouse, scroll to change speed, and press **F** again to leave a
position/rotation/FOV keyframe at the playhead. Both camera styles are ordinary layers and can
coexist in one composition; the active camera decides the shot.

*3D effects actually work.* The source-raster fingerprint omitted effect type, order, enabled
state and resolved parameters. Every 3D layer effect appeared dead until unrelated content
changed. The fingerprint now includes the whole resolved effect stack, depth effects are applied
after the 3D projection instead of being skipped, and a missing depth buffer is a true no-op
rather than erasing the layer.

*New effects.* **缂冩垶鐗搁柌宥咁槻 / 闂呭繑婧€闁插秴顦?* (Alight Motion-style grid and seeded random repetition),
**婀嶆祦缃崲** (EffectCraft-inspired fractal displacement), **鑹插僵鏄犲皠** (three-stop Colorama),
and **鎵囧帨** (animated light sweep). The effect catalogue is now **58** entries; all new shaders
have real GL pixel tests, and the repeat shader has identity/repetition/gap/seed tests.

*AE curve performance.* BBS's batcher emits one draw call per solid box. The value graph was
issuing thousands of boxes per frame (halo + core, per row, per column). The graph now opens one
batch scope, samples each curve once and paints both strokes from the same column array. The
vertex/draw-call count no longer scales with curve slope.

*Effect library decluttered.* The 56 px effect preview and the 16 px per-row thumbnails are gone;
the list gains their space and uses 17 px rows. A 16 px thumbnail could not communicate a blur or
a displacement, while it made the 190 px sidebar read as a dense grid.

*Interface light.* The onboarding backdrop now uses a warm/cool aurora, slow light shafts, an
orbital structure line and dust behind the card. The editor shell gets a quiet blue-black
gradient, two low-alpha pools and four stage corner brackets; none of it covers controls or the
program picture.

*Adaptive UI.* Header actions disappear before they collide, side columns shrink around a
preview floor, the timeline never steals the body on short windows, the settings card fits small
panels, and the preview controls fold themselves away on narrow previews.

*Texture filtering.* A new project setting **绾挎€ц繃婊?* and per-image override choose automatic,
linear or nearest. Automatic detects high-resolution sources (large/downscaled) and keeps
pixel-art nearest; the settings page shows the live high-res/pixel/pending counts. The preview
bar has a one-click `绾挎€?/ 鑷姩 / 鏈€杩戦偦` chip.

*Keyframe drawer and shader compatibility.* Switching to the curve page and back no longer leaves
the keyframe drawer blank. Preview status now reports Iris/shader-pass state, shadow-pass copies
are skipped, and a missing depth attachment is visible in the preview readout instead of silently
turning depth effects into a black frame.

<p align="center">
  <img src="docs/gallery/sponsors.png" alt="Sponsor credits inside Settings, with three names and a plain thank-you" width="100%">
</p>

<p align="center">
  <img src="docs/gallery/effects-library-clean.png" alt="Effect library without preview thumbnails, leaving room for names and categories" width="100%">
</p>

<p align="center">
  <img src="docs/gallery/onboarding-premium.png" alt="Onboarding background with warm/cool aurora and light shafts" width="100%">
</p>

<p align="center">
  <img src="docs/gallery/iris-bsl-preview.png" alt="Iris + BSL final-pass image visible in the compositor preview" width="100%">
</p>

<p align="center">
  <img src="docs/gallery/bbs-flight-camera.png" alt="BBS-style flight camera with the flight HUD and F-key hint" width="100%">
</p>

## What's new in 0.18.0

**A first-run onboarding, a BBS-style settings surface and editable shortcuts 鈥?and every custom
widget now moves with a non-linear curve instead of snapping.**

*Onboarding.* The compositor opens on a BBS-style welcome card: layout presets (鏍囧噯 / 涓撴敞 / 绱у噾 /
瀹藉睆), a motion preset (骞宠　 / 鐢靛奖鎰?/ 鏋佺畝), a shortcut scheme, and a short finish page. The
background is the same visual language as BBS's own landing screen 鈥?a deep surface, drifting
lights and a pointer-following glow 鈥?and the choice is persisted, so it only appears once. It can
be replayed from Settings 鈫?鏂版墜寮曞.

*Settings.* The old fixed 520脳520 modal is replaced by a left category rail + right scrollable list
in the style of BBS 2.7's settings: search, section headers, toggles, sliders and segmented choices.
Motion intensity/speed, glow, shadows, backdrop brightness, layout density, project defaults,
encoder/proxy and the shortcut editor all live here. The overlay enters with a short overshoot,
rows light up under the pointer and category switches sweep an accent line.

*Shortcuts.* Every compositor command is now an action in one table with a default for the
Premiere and BBS schemes. Click a keycap, press the new combination, and it is saved globally;
Backspace clears a binding, Esc cancels, and conflicting actions are reported in the status bar.
`Ctrl+,` opens Settings and `Ctrl+/` jumps straight to the editor.

*Motion.* A shared `UiMotion` layer drives button hover, tab underlines, tile/card entrance,
asset-library scroll smoothing, track selection, panel entrance and the new overlays. Settings can
turn it off or reduce intensity/speed; all motion is paint-time only, never in the frame/export
path. The `UiMotion` easings and the shortcut table are covered by unit tests.

<p align="center">
  <img src="docs/gallery/onboarding.png" alt="BBS AE first-run onboarding with layout, motion and shortcut choices" width="100%">
</p>

<p align="center">
  <img src="docs/gallery/settings.png" alt="BBS-style compositor settings with categories, motion controls and editable shortcuts" width="100%">
</p>

## What's new in 0.17.0

**The asset scrollbar drags, Bezier handles drag from where they are, and the project icon is finally
the plugin's icon.**

*Asset library scrollbar.* The 3 px line was painted, never hit-tested. It is now a real 8 px thumb:
drag it, click the track to jump, release anywhere (the physical button is polled so it cannot stick),
hover highlights, and the grid reserves room so the thumb never covers a tile. The hint says
鈥旀粴杞垨鍙充晶婊戝潡婊戝姩鈥?
*The curve editor was editing the wrong property.* All five transform curves share one BBS
`Transform` keyframe. The editor stored `IdentityHashMap<Keyframe, channel>` ownership, so the last
adapter (Scale Y) overwrote Position X: dragging a position key changed Scale Y, and the
Bezier/Ease presets filtered the selection to nothing. Ownership now comes from the **active
channel** for transform keys; scalar channels keep their one-to-one map.

*Bezier handles no longer collapse on mouse-down.* The drag handler treated the pointer delta as the
handle's absolute offset, so the first 1 px collapsed `rx` to 0.25 and `ry` to 0. It now snapshots
`rx/ry/lx/ly` at press and adds the delta, clamps influence to the adjacent segment, mirrors by
default, breaks with Alt, and reports influence/speed. Default 鍗?-frame handles are expanded to
33.3 % influence when Bezier is chosen; value fitting includes the handles so they cannot be clipped.

*Professional graph ergonomics.* Middle/Space drag pans **both** axes; right-clicking a key exposes the
interpolation menu (linear / bezier / ease / ease-in / ease-out / hold / auto / continuous / delete);
arrow keys nudge 1 frame / 1 pixel (Shift = 10脳); the graph shows a selected-key card (channel, time,
value, interpolation) next to the playhead readout.

*The plugin icon.* `fabric.mod.json` had no `icon`, so Mod Menu showed a placeholder. The repository's
32脳32 compositor icon (by 瓒呯骇澶у竷鍙? is now packaged as a 128脳128 padded, rounded
`assets/bbsae/icon.png` and registered in the metadata; the BBS 16脳16 tab/clip icon stays the
original. `IconResourceTest` checks both.

<p align="center">
  <img src="docs/verify/round28-curve2.png" alt="AE curve editor with visible Bezier handles and the selected-key readout" width="100%">
</p>

## What's new in 0.16.0

**The preview runs at the film's speed, the clip picker speaks Chinese, and 3D layers get
their XYZ back.**

*The clock was a unit error.* `startRenderFrame(tickDelta)` added `tickDelta / 20` once per
rendered frame, but BBS passes Minecraft's **fraction of the current client tick** there, not the
elapsed time since the previous frame. Summing the sawtooth happened to give 1脳 at 60 fps, ran
**3.6脳 too fast at 144 fps** and 0.5脳 at 30 fps. Playback now advances by wall-clock seconds with
a 0.5 s stall clamp, reset on resume/seek, so one second of composition is one second of film.
`CompTimeTest` reproduces the old refresh-rate dependence and checks the new clock at
30/60/90/144/240 fps.

*The clip picker no longer shows a translation key.* BBS labels a clip type through
`UIKeys.C_CLIP` (`bbs.ui.camera.clips.<link>`). A missing entry falls back to the key itself, so
the add-clip menu showed `bbs.ui.camera.clips.bbsae:composition`. The addon now registers that
key 鈥?and the legacy `composition` spelling 鈥?through `RegisterL10nEvent`, with a post-ready
fallback, and new clips are titled `鍚堟垚鍣╜ from the start.

*The 3D layer quick panel is 3D.* The right-hand quick panel switches from five 2D rows to ten
3D rows: position X/Y/Z, rotation X/Y/Z and scale X/Y/Z, each group with its own 鈼?key button.
Z depth, pitch, yaw and Z scale write the layer's scalar channels, the same channels the 3D scene
graph reads.

*BBS's own curve picker is one click away.* A **鎻掑€兼洸绾?* button in the keyframe drawer header
and in the quick panel opens BBS's `UIInterpolationContextMenu`: the graph preview, the full
icon grid (linear/hold/bezier/auto/ease/bounce/elastic/鈥? the four bezier arguments and
copy/paste. It applies to every selected keyframe, or selects the key at the playhead first.

*First-key buttons work.* The inspector's 鈼?was disabled until a scalar channel already existed,
and `addScalarKey` read fields `CompLayer.scalar()` did not know (`z`, `rotationX/Y`, `scaleZ`,
`squash`, camera distance/target/FOV), inserting 0. Every scalar field is now readable, the
current displayed value is passed explicitly, and the key action starts its undo checkpoint
before writing.

<p align="center">
  <img src="docs/verify/round27-quick3d.png" alt="3D XYZ quick panel and BBS interpolation picker entry" width="100%">
</p>

## What's new in 0.10.0

**The timeline zooms, and audio/video finally have tracks.**

*Zoom was a unit error.* BBS's `Scale.zoom(delta, min, max)` **adds** to a value measured in pixels
per second; the editor passed 鍗?, so every key press and wheel notch changed the scale by 1.7 % 鈥?a
dead control. A step is now 1.35脳 (`zoomTarget`), applied through the same additive API and anchored
at the cursor, with `Alt + =/-` for track height. Two tests drive BBS's real `Scale` to prove that one
step is exactly 1.35脳 and that in/out steps cancel.

*Audio is a track now.* The timeline's rows were exactly `doc.layers`; audio clips lived only in the
inspector. There is now a virtual audio row (`rowCount()` = layers + 1) with the same time axis, row
height and scroll: green bars, click to select, drag to move, 8-px edges to trim (the source offset
follows a trim-in), `Delete` removes the selected clip 鈥?and the bar length is the length stored at
import (`probeDuration`), so what you see is what the mixer plays.

*Video is a track now.* `footage` layers already consume `frame_%04d.png`, but nothing produced those
frames. `CompVideoImport` converts a video with FFmpeg on a worker thread 鈥?at the composition's frame
rate and raster, letterboxed, 60 s cap 鈥?then creates a footage layer on the client thread. The
compositor can then ask for frame 91 in any order, which is the whole point of the frame pipeline.

*Two UI bugs.* A side-by-side layout at ~360 logical pixels squeezed the preview to a negative width
(so it silently stopped drawing); both columns now shrink proportionally with a 160 px preview floor.
And the effect/mask/world-backdrop toggles are hidden in AE mode, where they cannot change the frame.

## What's new in 0.9.0

**The AE pipeline got the four things it was missing.**

*Disk frames.* The frame server now has an L2 on disk keyed by the **content fingerprint**
(`cache/frames/<project>/<comp>/<signature>_<frame>_<w>x<h>.rgba`). Because the file name *is* the
document state, an entry can never be stale: different content means a different file. Raw pixels
(no PNG round-trip 鈥?1080p ImageIO is slower than re-rendering a simple comp), written only for
frames that took 鈮?45 ms, 512 MB budget with oldest-first eviction.

*A render budget instead of one frame per tick.* GL lives on the render thread 鈥?a background render
thread would need a shared context and would fight Minecraft's state. So the "farm" spends 22 ms of
each 50 ms tick producing frames (up to 6), which removes the hard 20 fps ceiling: a 12-second
60 fps composition used to take 36 s to write even at 3 ms a frame. And full-size video now streams
**raw RGBA frames into FFmpeg's stdin** instead of writing a PNG per frame, which was the real
bottleneck. Resized presets, PNG sequences and "keep frames" keep the file path (and its resampler).

*Preview audio.* The composition's mix is rendered on a worker thread and played through BBS's own
OpenAL (`SoundBuffer`/`SoundPlayer`), so there is no second audio backend. The playhead is
authoritative: `sync(time)` corrects drift over 250 ms and handles loops, and one
`syncPreviewAudio()` derives everything from `(playing, AE, has audio, toggle)` 鈥?so any code that
pauses playback also stops the sound.

*Proxy resolution, explicit.* `encoderProxy` 1/1, 3/4, 1/2, 1/4 in Settings; the badge reads
`AE 缂栫爜鍣?路 浠ｇ悊 1/2`. The frame cache keys on the factor, and export is always 1/1 鈥?a silent
proxy is how "the preview looks soft but the export is sharp" becomes a bug report.

## What's new in 0.8.0

**A second render pipeline, and it is the one an editor needs.** Until now the composition was an
overlay: the game produced the frame, at window resolution, at display-refresh timing, and BBS's
recorder wrote it. That is correct for a film overlay and wrong for an editor, where "frame 90"
must mean the same picture in the viewer and in the file. **AE 缂栫爜鍣ㄦā寮?* (Settings 鈫掓覆鏌?/ 缂栫爜
绠＄嚎) switches the frame source to the compositor:

- `CompRenderer.renderFrame(project, doc, frame, alpha)` is now the **only** way a frame leaves the
  compositor: time comes from the frame index (`frame / fps`), the raster is the composition's own
  resolution, editor chrome is suppressed, guide layers are excluded.
- `CompFrameServer` caches those frames, keyed by the document fingerprint 鈥?editing anything makes
  the old frames unreachable, so there is no invalidation to forget. 192 MB LRU (~23 frames of
  1080p).
- The preview asks the server for the playhead's frame; the render queue asks it for every frame of
  the range. Same function, same pixels. This is the producer/consumer "pull" model MLT uses and
  the `RenderManager` + `FrameHashCache` design Olive uses.
- **AE 瀵煎嚭** (the header button changes label) queues the composition at its own resolution and
  frame rate instead of going through BBS's recorder.

It is a different pipeline, so it is labelled like one: a one-time **risk dialog** (six concrete
consequences, not a disclaimer), a red **AE 缂栫爜鍣?/ 甯?N / total** badge on the preview, and a red
hint in Settings. The honest limitations: the 3D world can only enter through the `BBS 妫板嫯顫峘 layer,
and offline exports see its **last** captured frame; depth effects lose their depth; the BBS
recorder keeps rendering in BBS mode, so the two paths never mix.

## What's new in 0.7.0

**The curve is a curve again.** The editor opened on a fixed **-100鈥?00** value axis and a view
length of `frames / 24` seconds, so a position channel (values in the hundreds) and a 12-second
60 fps composition were both framed wrong: every sampled Y landed outside the graph rectangle, the
clip removed the line, and only the keyframe glyphs survived. The graph now frames its own data
(value axis from the active channel's keys, time axis from the composition duration), pins
out-of-range values to the edge like AE, and refills its per-column spans every frame 鈥?the
incremental span cache whose invalidation could not be proven is gone (0.2 ms/frame against a
screenshot that showed neighbouring columns jumping between unrelated values).

**Importing a file works inside the game.** `UIFileDialogs.pickFile()` is a native tinyfd window: on
a fullscreen game it can open behind the window or never call back at all, which is why the import
button looked dead. Images, fonts and audio now go through an in-game browser
(`UIImportBrowser`) with drives, folders, an extension filter and a typed path; the freshly
imported file is selected in the asset library immediately.

**Video and audio export.** The composition has a real audio track (in-point, offset, gain, fades,
loop, mute). `CompAudioMixer` renders it to 48 kHz stereo with a pure function, and that same mix
feeds the MP4's AAC track, the standalone WAV module and the PNG sequence folder. Video export pads
audio before `-shortest` so the last frame is never dropped; a range export now plays the *right*
part of a clip (`seek = exportStart - clipStart`, previously every range export started the music
from 0). The render queue's output module is a chooser with real names and a video/audio icon
instead of a text field for internal preset ids.

**The asset library is one sliding page.** The old modal pinned everything to a 620脳400 rectangle;
below that the action row was off-screen ("the button does nothing"). Categories slide
horizontally, the thumbnail grid scrolls vertically, and the footer switches between short and long
labels by width. The render queue's field row no longer uses `UIElement.row()`, which ignores child
widths and was squeezing a 110-px label column into a quarter-width box.

**The stage is solid.** A transparent composition over the live game world read as "the panel is
broken". The stage colour is opaque by default, the world backdrop is an explicit toggle, and
`CompRenderer.stageColor()` is the single place that decides (backdrop preview 鈫?transparent,
alpha export 鈫?transparent, everything else 鈫?the document colour). Existing projects migrate once.

**Camera semantics.** A camera is a view, not content: hiding its row or trimming it no longer
switches the composition back to 1:1. The gizmo, its hit test and its drag all read the animated
transform (`cameraEye`), and `layerAt()` reuses `handleAt()` instead of maintaining a second copy of
the geometry 鈥?clicking the camera body now works wherever it is drawn.

## What's new in 0.6.0

**The curve editor stopped drawing a line per pixel.** `line()` walked every pixel of a segment
and emitted a quad per step, so a steep curve cost thousands of quads per frame and got slower the
further you dragged a handle. Curves are now rasterised into a **per-column span cache**
(`CurveSpans`): one rectangle per screen column, merged into runs when the span is identical.
Vertex count is bounded by the width of the graph instead of by the data, and an unchanged curve
costs zero evaluations.

**Editing only recomputes what changed.** A handle drag rebuilds the 20鈥?0 columns of the affected
segment instead of resampling the whole graph; the five transform axes share one key list, so they
invalidate together (a shared epoch) while scalar channels keep their own revision counter.

**Release no longer jumps.** A drag used to rebuild the entire inspector and every keyframe sheet
on mouse-up 鈥?3鈥?0 ms landing exactly on the frame you let go. A drag now only re-runs the
inspector's value bindings; only adding/removing keys rebuilds widgets.

**Preview follows the curve.** Dragging used to leave the pre-render cache answering with the last
green frame (a regression from 0.5.0: `markDirty()` only ran on save). A drag now invalidates the
cache every frame, drops the preview to 0.7脳 resolution while the mouse is down, and 鈥?when the
playhead sits outside the edited segment 鈥?reuses the frame it already has instead of re-rendering
the whole graph.

**The blank curve page is fixed.** The strip was laid out only when the tab was clicked, so a
dashboard page switch (or entering a precomp, or the render view) could leave the curve editor
visible with a zero-height rectangle 鈥?and a negative clip silently erases everything. The strip is
now reconciled every frame from `(page, visibility, area)`, the graph area is clamped to at least
1脳1, and a strip too short to host a graph falls back to the timeline with a message. Switching to
the curve page also grows the strip to 55% of the panel once.

**Smaller fixes.** Modifiers in the curve area go through GLFW instead of `Screen.hasAltDown()`
(Alt-broken handles were intermittent); the active channel survives an inspector edit (it used to
jump back to "浣嶇疆 X" because the check compared object identity); `keyAt`/marquee iterate the raw
key list instead of allocating a `Key` per keyframe per frame; grid labels and the value readout
are cached per view/frame instead of `String.format`-ed every display frame.

Value-axis zoom stays on **Ctrl+wheel** (as requested), not AE's Alt+wheel.

Tests: **119/119**.

## What's new in 0.5.0

**The preview is BBS's preview now.** The compositor used to paint the film frame only if a
toggle callback happened to request it, cropped it to fill the composition rectangle, and had no
depth at all 鈥?a `depth_*` effect silently did nothing while previewing. Three changes:

1. The world frame and its **depth buffer** are copied together at the end of the world render
   (`SceneDepth.captureFrom`), so colour and depth always come from the same frame, and depth
   effects see the film's real geometry.
2. The capture request is re-derived from the document every frame instead of only from a toggle,
   so a project saved with the world backdrop 鈥?or a one-click 3D workspace 鈥?actually gets it.
3. The world frame is drawn **contained** (letterboxed), exactly like BBS's own preview, instead
   of cover-cropped into the composition rectangle.

**Pre-render cache with a green bar.** Press **棰勬覆鏌?* in the timeline toolbar and the ruler
fills with a green strip. Green frames are served from a disk+VRAM cache
(`config/bbsae/cache/鈥旑泦, raw RGBA + deflate, 1280脳720 cap) instead of being re-rendered, so a
composition with a dozen effects plays back smoothly. The pass is cooperative 鈥?two frames per
display frame, 18 ms budget 鈥?and any edit invalidates the green immediately.

**The editor stopped writing the project to disk on every mouse event.** `save()` used to
serialize the whole project (megabytes of JSON) and rewrite the file for every slider tick, wheel
notch and drag delta; it is now a dirty flag with a debounced write. That single change removes
most of the "the UI is extremely laggy" symptom.

**Preview renders at display resolution.** A 1920脳1080 composition shown in a 700 px panel used to
render 2.07 M pixels to display 0.28 M. The render targets now follow the viewport
(`CompRenderContext.scale`), which is a 7脳 reduction in per-pass cost with no coordinate changes.

**Camera.** Wheel now dollies *towards* the composition (it used to zoom out), Alt+wheel narrows
the field of view, Alt is read from GLFW instead of `Screen.hasAltDown()`, and the inspector is no
longer rebuilt on every wheel notch.

**Alt+wheel resizes the tracks.** It was bound to horizontal timeline zoom; row height was hidden
behind Alt+Shift. Now Alt+wheel is track height (18鈥?2 px), Ctrl+wheel zooms time.

**Curve editor.** The value axis is fitted to the **active channel** instead of every visible
channel 鈥?position (0鈥?080) and opacity (0鈥? on one axis turned the graph into a flat line at the
bottom. Only the active channel's curves, keys and handles are drawn and hit-tested by default
(`鑱氱劍` overlays the rest), and the cursor shows a crosshair with a live time/value readout.

See [`docs/ROUND15-PREVIEW-CACHE-PERF.zh.md`](docs/ROUND15-PREVIEW-CACHE-PERF.zh.md).

## What's new in 0.4.0

**UI density: small / medium / large, auto-following BBS.** Everything 鈥?row height, control height,
label column, icon size, panel widths 鈥?now derives from one density number (`CompUi`). "Auto" reads
BBS's applied GUI scale: 1鈥?鈫?large, 3 鈫?medium, 4+ 鈫?small. That inversion is deliberate: a scale
of 4 leaves a 480脳270 logical canvas, so tall rows simply do not fit, while scale 1 has room to
breathe. Switch it in Settings 鈫掗悾宀勬桨鐎靛棗瀹? it is saved per project.

**Flat buttons instead of accent blocks.** BBS's `UIButton` fills itself with the theme's primary
colour; forty of those in an inspector is a wall of saturated blocks. `UICompButton` keeps all of
BBS's click/hover/tooltip behaviour and changes only the skin: raised-surface fill, hairline border,
left-aligned label, accent reserved for the single primary action of a surface (play, enter
compositor, apply effect). One-symbol actions became icon buttons.

**Every button explains itself on hover.** `CompUi.describe` is a vocabulary of 70+ Chinese
descriptions covering the panel's buttons; `UICompButton` sets the tooltip automatically from its
label, and unknown labels still get a sentence. A test fails if a known label loses its description.

See [`docs/ROUND14-UI-DENSITY-FLAT.zh.md`](docs/ROUND14-UI-DENSITY-FLAT.zh.md).

## What's new in 0.3.0

**One camera, finally.** The compositor used to have two unrelated camera paths (a 2D pan/zoom built
from `scale.x`, and a 3D perspective camera gated behind a `camera3D` toggle), plus a Y-flip hack in
the 3D matrix. They disagreed about zoom and framing, and the 3D projection called JOML's affine
`transformPosition` instead of `transformProject` 鈥?so 3D layers landed at coordinates like
`-230646px`. Everything now derives from one pose: one space (comp pixels, Y down, Z toward the
viewer), one `CompCamera`, one `CompScene`. The 2D view is literally the 3D camera sampled at z = 0,
which is why switching a layer to 3D no longer changes its size or position (pinned by a test to
0.1px). Drag the camera body to pan, the crosshair to aim, the wheel to dolly, Alt+wheel for FOV.

**3D parenting.** With one scene graph, 3D parenting is the same change of basis as 2D 鈥?plus a
Rz璺疪x璺疪y decomposition so z / tilt / scaleZ survive. Whole-matrix equality is asserted in tests.

**Multi-select and pre-compose.** Ctrl/Shift selection in the track timeline, Ctrl-click in the
preview, drag the whole selection, and a batch panel: combine into a pre-render (time rebased,
parent/matte relations retargeted), group under a null, align, distribute, show/hide/lock,
duplicate, delete.

**Performance.** The editor re-rendered the entire graph every display frame even while parked.
A content fingerprint now caches the composed frame (and quantises time to the composition frame
rate); the 1-second full-project JSON snapshot only runs when the document is dirty and is capped at
32MB; depth/world captures are on demand; the per-frame `glGetError`/`glCheckFramebufferStatus`
syncs are gone; the FBO pool no longer destroys and recreates 8MB textures mid-frame.

See [`docs/ROUND13-CAMERA-MULTISELECT-PERF.zh.md`](docs/ROUND13-CAMERA-MULTISELECT-PERF.zh.md).

## What's new in 0.2.0

**AE curve editor.** The bottom strip now has a `鏃堕棿杞?/ 鏇茬嚎` switch. The curve view is a real
After Effects graph editor: value graph and speed graph, per-keyframe bezier handles with
mirrored/Alt-broken tangents, marquee selection, drag-to-retime with frame snapping, double-click
to add a key, AE keyframe glyphs (square = linear, circle = bezier, diamond = hold), eight easing
presets (linear / bezier / easy ease / ease in / ease out / hold / auto / continuous) with an
influence field, and a channel list that shows every animated property 鈥?position/rotation/scale as separate curves, opacity, effect parameters, mask points, shape corners.

Why it needed engine work: BBS evaluates a `Transform` channel with one interpolation for the whole
vector and ignores `lx/ly/rx/ry`, so handles could never bend position/scale/rotation.
`CompCurves` now evaluates each component with the same `BezierUtils` math BBS uses for float
channels 鈥?the curve the editor draws is exactly the curve that renders.

**Layer presets.** A third left-column tab: save the selected layer as an animation / effect-stack /
style / shape / whole-layer preset, apply it to any other layer, or use the ten built-in looks.
Effect and shape ids are remapped on apply (including their animated channel paths), and animation
payloads are re-timed to the target layer's in point.

**Parenting no longer teleports.** Binding used to write `parentId` and let the parent matrix
multiply the child, so a child at (900, 500) bound to a parent at (200, 100) jumped by (200, 100)
times the parent's rotation and scale. `CompParenting` now does a change of basis
(`local' = parentWorld鈦宦?路 childWorld`), shifts every transform key by the same delta, and converts
preview drags into parent space so the layer stays under the cursor.

**Shape corners and colours.** Per-corner radii for rectangles (4) and triangles/polygons/stars
(one per vertex), round or bevel joins, animatable per corner; and shapes now paint their own fill
and stroke instead of the whole layer taking the last shape's colour.

See [`docs/ROUND12-CURVES-PRESETS-PARENTING.zh.md`](docs/ROUND12-CURVES-PRESETS-PARENTING.zh.md)
for the derivations, the file map and the verification list.

## Status

This is the third vertical slice: the data model, render graph, editor, per-replay project
isolation, timeline scrubbing and render queue are in place. It is not a finished AE clone, and
the feature matrix in [`docs/features.zh.md`](docs/features.zh.md) is deliberately explicit about
what is and is not implemented.

Verified in this iteration:

- `compileJava` and all **119** tests pass (headless model + real hidden-window OpenGL 3.2 pixel
  tests + export-duration/icon checks + the new curve/parenting/preset/shape assertions);
- the addon loads in a Fabric 1.20.1 + BBS 2.7 development client;
- the compositor preview renders the composition (not a blank frame) after fixing projection,
  model-view and mask-transform state;
- the BBS film recorder produced a real H.264 MP4 (`320x180`, 20 fps, 39 frames) and the exported
  frame contains the compositor layers, proving the frame-overlay path reaches the video file;
- the final jar has Minecraft references remapped to intermediary and keeps BBS class names.

Build artifact: `build/libs/bbsae-0.6.0.jar` (also copied to `bbsae-0.6.0.jar`), **548025** bytes,
SHA-256 `b4ab3d93cf64e8eaa154542b1a42a3da2e1df22e224196f04009de44185aef92` (check the file if it is
repacked). The jar is produced by `gradlew build`; `remapJar` writes it directly and the class
entries are intermediary-remapped.

2026-10-24 interaction-logic pass (see `docs/INTERACTION-AUDIT.zh.md`):

- precompose now rebases the inner layer to 0 (in/out, keys, markers), keeps the source's stack
  index and transfers parent/matte relations; the previous code could make a layer starting at 5s
  disappear and jump the result to the top;
- selecting a keyframe now moves the playhead, so the Effect Controls edit the selected key
  instead of writing a second key at the old playhead;
- the Layer page's in/out fields use the same `trimInTo/trimOutTo` semantics as the timeline;
- errors use `notifyError` instead of assignments to `status.label` (render overwrote them every
  frame); the export dialog has one Start button and accepts 0 as "composition duration";
- material replacement has an explicit replace mode; precomp placeholders are preview-only;
- discrete commands create action-level undo checkpoints, and style reorder/3D exit/comp rename/
  precomp breadcrumb/work-area handles/keyframe-tick hit testing are in place. 66/66 tests pass.

2026-10-18 asset-library hotfix:

- the asset browser's `mc:` marker was passed to `Identifier` as a namespace (`mc`), but vanilla
  resources live in `minecraft`; every item/block/entity thumbnail failed to load and inserted
  layers had no texture. `mc:` is now mapped to `minecraft`;
- added `InterfaceBlur.applyUnder()` plus a dark scrim behind the library;
- the panel is now 620脳400 (5脳3 thumbnails, 6 category columns) so the action buttons fit a
  1536脳864 / GUI-scale-2 logical screen;
- evidence: [`docs/verify/round11-asset-library.png`](docs/verify/round11-asset-library.png),
  details: [`docs/ROUND11-ASSET-LIBRARY.zh.md`](docs/ROUND11-ASSET-LIBRARY.zh.md).

2026-10-18 ninth pass (export fidelity, 12 s duration, compositor icon):

- the export length now uses `max(film, composition)` instead of BBS's 12-second camera default;
  the composition clip is normalized (`tick=0`, full duration, opacity 1) and the film is saved
  before recording, so the recorder cannot stop early or miss trimmed clips;
- the **BBS preview layer** no longer disappears in the film-editor/F6 export path: the clean
  world frame is captured at `AFTER_TRANSLUCENT` into `SceneColor`, and a `bbs` layer can sample
  it outside the compositor panel too. The 2D overlay also disables depth writes and restores the
  UI scissor, so the export cannot inherit a stale composition/frame state;
- `workAreaOut` follows the composition duration (including migration of the old 12-second
  default), so preview playback no longer loops at 12 s;
- added the user-provided compositor icon as `assets/bbsae/assets/textures/gui/compositor_icon.png`
  (16脳16), used by the dashboard tab and the `bbsae:composition` clip;
- the export dialog has an explicit **Export duration (seconds)** field (persisted as
  `CompSettings.exportDuration`, 0 = composition duration) and a **Sync to BBS timeline** button.
  The same field is available in the **compositor settings** after entering a project, with an
  **Apply export duration to composition + BBS timeline** button, so the length can be changed
  before opening the export dialog;
  the value is passed to BBS's native recorder directly as `round(seconds * 20) + 1` ticks, and
  `editorLoop` is disabled for the export. A 40-second setting produced **2402 frames /
  40.033 s** (`docs/verify/round9-export-40s-35s.png`, `round9-export-40s-ffprobe.txt`), a
  25-second composition produced 1502 frames / 25.033 s, and a 20-second run is kept at
  `docs/verify/round9-export-20s-15s.png`;
- **fixed the actual 20-second drag wall:** the field used to update only `exportDuration`, while
  the timeline clamps drags to `doc.duration`; the callback now calls
  `adoptCompositionDuration(seconds)`, extending `doc.duration`, work area, composition clip and
  BBS loop first. A layer can now be dragged from 20 s to 40 s (`layerCanMovePastTwentySeconds...`);
- a BBS-preview-layer-only export at `docs/verify/round9-bbs-preview-layer.png` proves the
  `bbs` layer now samples the clean world frame in the export path;
- tests now **65 / 65** (added `compositionExportDurationExtendsPastTheTwelveSecondFilmDefault`
  and `IconResourceTest`). Details: [`docs/ROUND9-EXPORT-SYNC.zh.md`](docs/ROUND9-EXPORT-SYNC.zh.md).

2026-10-18 eighth pass (Alight Motion PIXEL effects, first-principles bug audit):

- added seven PIXEL-family effects: **Scanlines, Glitch, VHS, Halftone, Emboss, Mirror,
  Kaleidoscope**; the catalogue grew to 53 then **58** in 0.19 (repeats, turbulence, colour mapping,
  light sweep);
- every one operates in **layer space** through `EffectSpace` / `sampleLocal`, so the pattern
  follows the layer instead of staying glued to the composition;
- Halftone follows Alight Motion's properties (dots across the project width, strength, angle,
  row phase, ink/paper colours); Mirror now overlays a mirrored copy with an opacity control
  instead of discarding half the layer;
- fixed four dead parameters (`glitch.speed`, `vhs.speed`, `vhs.noise`, `depth_dof.focus`);
- fixed `EffectSpace.identity()` inverting the V axis (adjustment/3D-layer effects sampled the
  vertically flipped row) and `CompShaders.delete()` leaving a dangling `rgbSplit` program;
- new `PixelPlan` separates parameter resolution from GL so a headless test can prove every
  registry parameter reaches the shader; new GL pixel tests cover identity, layer space, colour
  and symmetry. Evidence: `docs/verify/round8-pixel-effects.png`.
- details: [`docs/ROUND8-01-AUDIT.zh.md`](docs/ROUND8-01-AUDIT.zh.md),
  [`docs/ROUND8-02-PIXEL.zh.md`](docs/ROUND8-02-PIXEL.zh.md),
  [`docs/ROUND8-03-VERIFY.zh.md`](docs/ROUND8-03-VERIFY.zh.md),
  [`docs/ROUND8-04-PUBLISH.zh.md`](docs/ROUND8-04-PUBLISH.zh.md).

2026-10-18 sixth pass (distortion, grading, landing screen, media/sound, export):

- distortion effects now measure strength in **pixels** and radius against the layer's half
  diagonal (`2锜?wavelength` for waves); `pinch` gained its missing radius parameter. A real GL
  pixel test asserts that a bulge pulls transparent pixels into a square;
- the inspector has a real **grading page**: three AE-style colour wheels (lift/gamma/gain),
  a five-point master tone curve drawn with the same formula as the shader, presets and
  keyframeable exposure/contrast/saturation/vibrance/temperature/tint;
- the **landing screen** lists `.bbscomp` projects, creates new ones (name, canvas, fps,
  duration) and forbids editing until a project is chosen; shortcut scheme (Premiere / BBS) is
  selected there and persisted per project;
- all four panel areas are **draggable** (left, right, timeline, keyframe drawer) and the
  keyframe drawer height is saved; the old `row()` tab overflow is gone;
- the media library indexes 10 texture roots plus BBS textures, searches across every category,
  and has a sound library that auditions vanilla/BBS sound events, copies their ids and opens
  BBS's own sound overlay;
- layer duration has direct in/out/duration fields and 1s/2s/5s/fill buttons; settings add a
  default layer duration;
- 10 motion templates, a reworked block-shatter shader and a richer composition-clip inspector
  (project picker, fit choice, clip info, fixed 鍚堟垚鍣?title);
- external replay playback can be configured to hide the compositor
  (`showDuringExternalPlayback`), detected with `BBSRendering.isCustomSize()` so F6 recording is
  never mistaken for external playback;
- export accepts custom width/height and a numeric aspect ratio; a real **1920x1080 @ 60 fps**
  export was produced (494 frames, 8.23 s) and a frame is at
  `docs/verify/round6-export-1080p60.png`.

2026-10-18 track-editor / Effect Controls pass (the Premiere-style correction):

- the bottom of the workspace is now a **real track editor**, not a small strip above a property
  sheet: one row per layer, a track header with visibility/lock, a movable/trimable clip bar,
  frame-snapped dragging, edge handles, snapping to the playhead/markers/other clips, zoom/pan,
  work-area shading and a proper seconds ruler;
- the layer stack is no longer a second list in the left column. Track headers are the single
  selection surface; clicking a bar selects its layer and feeds the right-hand panel. This removes
  the old duplicate "which list owns selection?" state;
- the right column is now **Effect Controls** by default: Motion (position/scale/rotation/anchor),
  Opacity + blend mode, the applied-effect stack and layer styles for the selected track. With no
  track selected it shows an explicit empty state instead of editing the last layer;
- the left column is a Premiere-style **Effects library** with search, categories, recent effects
  and a native `UIList` tree. Double-click or "Apply" adds the effect to the selected track; an
  effect can be enabled, reordered or removed in Effect Controls;
- the BBS keyframe editor moved into an optional drawer under the track timeline. Adding a keyframe
  opens it automatically; it fits the composition duration instead of the keyframes' bounding box;
- shortcuts were rebuilt around `KeyAction.PRESSED`: the old code also ran on key release, which
  made Space/S and other toggles fire twice and look broken. The new set adds J/K/L shuttle,
  frame/5-frame stepping, Home/End, edit-point and marker navigation, track selection/stacking,
  `[`/`]` trim to playhead, I/O work area, Alt+arrow nudging, C/Ctrl+K split, S snapping,
  `=`/`-`/`\` zoom/fit, F focus, Ctrl+S save, Ctrl+M export and Ctrl+/ shortcut help;
- split-at-playhead keeps footage continuous (`startTime` advances with the split), trim-in keeps
  the source frame under the cursor instead of slipping the clip, and moving a bar carries its
  keyframes and layer markers with it. These rules live in `CompLayer` and are covered by tests.

2026-10-18 UI pass (Chinese build):

- the whole compositor UI is Chinese: header, inspector tabs, every inspector field, effect names,
  effect parameter names and render-queue states;
- the inspector tab bar was replaced with a deterministic clickable bar (BBS's `UITabStrip` is a
  scroll view and swallowed presses inside the dashboard panel, so no tab could be switched);
- the timeline is primed on the first laid-out frame: sheets no longer stay blank until the user
  clicks something;
- Space toggles playback;
- opening the compositor now starts with a replay picker: a replay must be chosen before editing;
- export opens a small preset dialog (1280/1920/2560/3840, 16:9 / 9:16 / 1:1, 24/30/60 fps) that
  writes the values into BBS's own video settings and then runs BBS's native recorder, so export is
  no longer stuck at the old 320x180 preview size;
- toolbar regrouped: transport in the centre, save/export/render/focus anchored to the right, the
  add-layer button moved into the layer panel header.

2026-10-18 asset-library pass:

- **asset library** (materials button in the layer panel): a photo-library style browser with
  categories, thumbnails, search and paging. It indexes the project's own `assets` folder **and the
  client's resource packs** (`mc:textures/item|block|entity|gui`), so Minecraft item/block icons can
  be dropped straight into a HUD composition; double-click inserts, or replace the selected layer;
- layer stack is now drawn top-most first (AE convention), new layers appear on top;
- global undo/redo via 1-second project snapshots (Ctrl+Z / Ctrl+Y), plus Ctrl+D duplicate,
  Delete, Escape closes modals;
- Chinese default layer names, Chinese layer badges, label rows in the inspector use explicit
  two-column layout.

Implemented now:

- film/replay-scoped compositor projects: a film owns its project, every replay owns a separate
  composition inside it; the replay selector switches the edited composition;
- `.bbscomp` projects with multiple compositions, markers, master parameters and output presets;
- layer types: solid/panel/rect, bar, text, image/footage, shape, null, adjustment, precomp;
- in/out, start offset, time scale, freeze, keyframable source-time remap, layer markers, opacity,
  13 blend modes, parenting and track mattes (alpha/luma, normal/inverted);
- BBS-native `KeyframeChannel<Transform>` / `KeyframeChannel<Float>` animation, including effect
  parameters, text metrics, mask points/handles and shape trim/repeater values, edited with BBS's
  own `UIKeyframeEditor` (dope sheet, graph, context menus, interpolation, copy/paste);
- a real track timeline with a seconds ruler, playhead, work-area shading, track headers and
  movable/trimable layer bars; the BBS keyframe editor (dope sheet/graph/native keyframe dragging)
  opens in a drawer under the tracks when needed;
- masks with add/subtract/intersect/exclude, feather, opacity, positive/negative expansion,
  inversion, editable points and bezier handles;
- a curated **58-effect** catalogue (blur, color, keying, generate, distort, stylize, screen/retro,
  transitions, depth, grid/random repeat) with real box/radial blur, unsharp sharpen, depth-aware
  passes and the Alight Motion-style Scanlines / Glitch / VHS / Halftone / Emboss / Mirror /
  Kaleidoscope / Grid Repeat / Random Repeat family;
- layer styles: drop shadow, inner shadow, outer/glow, bevel, color/gradient overlay, satin,
  stroke;
- text with tracking, line height, wrap, per-character range/wiggle animators;
- vector shapes (rect, ellipse, polygon, star, bezier path), boolean operators, trim, repeater,
  roundness, fill/stroke, editable/animatable path points;
- image layers and `%04d` footage sequences with source-time remap and an OS file-picker import
  that copies media into the project's own asset folder;
- tracking bindings to the BBS camera, replay actors and transformed layer anchors, plus depth
  texture capture for depth matte/fog/DOF/displacement;
- animation presets that store/reapply property animation without replacing target content;
- render queue with add/remove/retry/cancel, render settings/output module separation, editable
  output modules, PNG sequence, H.264 review and ProRes 4444 alpha presets;
- auto-save and `.bak` backups;
- a compact BBS-native editor with a Premiere-style information architecture:
  **Effects library | composition preview | Effect Controls**, and a **track timeline** at the
  bottom. The timeline has a seconds ruler, track headers (name, type icon, visibility, lock),
  movable/trimable bars with keyframe ticks and FX badges, snapping, zoom/pan and work-area shading;
  the BBS keyframe editor sits in an optional drawer under the tracks;
- the inspector is contextual: **Effect Controls** (Motion/Opacity/effect stack/styles), **Layer**
  (timing, switches, parenting, matte, expressions, type-specific fields), **Mask**, **Text**,
  **Comp** and **Output**. Nothing edits a hidden "last selected layer";
- the left **Effects library** has search, categories, recent effects and double-click apply; the
  effect stack in Effect Controls supports enable/disable, reorder and remove;
- preview drag writes the layer position, or inserts a transform keyframe at the playhead when the
  layer already has transform animation;
- the compositor has its own FBO preview again (it is **not** an overlay over BBS's film editor),
  with a preview toolbar for FX/mask preview toggles and a resolution/fps/FX readout;
- BBS timeline empty-state, timeline undo id, Add Transform/Opacity Keyframe context actions, and
  Fit / - / + / wheel-zoom track-timeline navigation;
- baked export through BBS's recorder (the **Export** header button opens the film and starts the
  normal BBS recorder), and comp-only export through the render queue.

## Requirements

- Minecraft **1.20.1**
- Fabric Loader 0.16.14
- Fabric API 0.92.1+1.20.1
- BBS FS **2.7-1.20.1**
- Java 17+ to run the game; **Java 21** to run this Gradle build (Loom 1.15)

## Build

1. Put `bbs-2.7-1.20.1.jar` in `libs/` (downloaded from the BBS FS Modrinth page).
2. Set `JAVA_HOME` to a Java 21 JDK.
3. Run:

   ```bat
   gradlew.bat build
   ```

   The addon jar is written to `build/libs/bbsae-0.6.0.jar`; the headless model tests run with
   `gradlew.bat test`.

## Use

1. Put the addon jar and BBS FS in the game's `mods/` folder.
2. Open a film in BBS, select a replay, open its **Replay properties**, and press
   **Open Film Compositor** in the **Compositor** section. This is the intended entry point: the
   panel is bound to that film and replay, and the film gets its composition clip automatically.
3. In the compositor, pick a layer, import an image with **Import Image...**, add text/shapes,
   set stopwatches, and drag clips/edges in the track timeline or scrub its ruler.
4. Press **Export** in the compositor header to open the film and start BBS's normal recorder, or
   use **Render** for the compositor's own render queue.
5. The **Replay** button cycles replays; right-click it to pick a replay or another saved film.
   Every replay has its own composition, and every film has its own project file.

Projects live under:

```
config/bbsae/projects/<film_id>.json
config/bbsae/projects/<film_id>/assets/
config/bbsae/exports/<film_id>/
```

`CompStore.loadFilmProject` creates the project lazily; `ensureReplayComp` creates one composition
per replay. Opening a replay also inserts/updates the `bbsae:composition` clip on the camera
timeline, so a recorded film cannot silently lose its compositor.

## Link to a film

The link is a normal BBS camera clip (`bbsae:composition`), not a sidecar and not a modified film
file. A clip stores project id, comp id, time offset, time scale, fit, opacity and hold. BBS
serializes the clip itself; the project remains a reusable external file.

Baked output needs no special exporter: the overlay runs inside BBS's normal frame. Comp-only
output (alpha, ProRes, PNG) uses the compositor's own render queue, which renders at the
composition's native resolution and hands the PNG sequence to FFmpeg.

## EffectCraft reference

[EffectCraft](https://github.com/storytold/effectcraft) was studied as a design reference. It is a
pure-Rust/egui After Effects reimplementation, so its crates cannot be linked into a Java/Fabric
mod, and copying its UI layer would introduce an FFI/embedding boundary the addon does not need.
What was reused is its **interface and workflow design**: a project/composition/layer hierarchy,
a timeline panel with a left property tree and a right ruler/CTI, current-time display, fit/zoom,
work area, layer switches, effect controls and a render queue. The implementation here stays inside
BBS's native Java UI and film data model.

The other reference addons were studied in [docs/ui-architecture.zh.md](docs/ui-architecture.zh.md):
BBS Refreshed for compact surfaces, BBS POV for sectioned editors/native timeline empty states,
BBSVFX for modular effects, and Wemppy's BBS fork for 1.20.1 API behaviour. An HTML/MCEF embedding
was deliberately rejected: BBS's preview is an OpenGL framebuffer and MCEF would add a native
Chromium runtime, input bridge and alpha-composition layer without preserving BBS's keyframe/undo/
F6-export path. See that document for the full reasoning and a possible isolated MCEF prototype.

[Photon](https://github.com/Low-Drag-MC/Photon) was studied separately for its editor and render
pipeline. It is NeoForge 1.21.1 + LDLib2 and cannot be linked into this Fabric 1.20.1/BBS addon;
the useful conclusions are documented in [docs/photon-research.zh.md](docs/photon-research.zh.md):
dockable hierarchy/scene/timeline workspaces, a real-time scene preview with FX/bloom/mask
toggles, and an isolated editor post-effect stack over a color+depth render target.

Known limits relative to EffectCraft/AE: the compositor itself has no imported external 3D model
files (BBS models are edited in the separate **瀹炰綋鍥炴斁 / Entity Replay** page, which reuses BBS's
own replay timeline, gizmo and model library), no lights, no graph editor for arbitrary
expressions, no full AM 306-effect catalogue (137 effects shipped; the AM gap is documented in
`docs/EFFECTS-AM-GAP.zh.md`), and per-character text editing on canvas is still limited. Multi-panel
docking: BBS's `UIDockLayout` is the planned base (see `docs/DOCK-LAYOUT-PLAN.zh.md`).

## Design notes

- The composition render graph is `layer content FBO -> masks -> track matte -> effects -> styles
  -> blend into accumulation`. Adjustment layers run their effects on the accumulation directly.
- All effect and compositing shaders are runtime GLSL programs (`GlslProgram`), guarded by
  `GlStateGuard` so BBS/Sodium/Iris state is restored.
- Masks and vector shapes are rasterized on the CPU and cached by content hash. This keeps masks
  consistent and supports feathered boolean masks without a stack of stencil passes.
- Depth is copied from the bound world framebuffer at the overlay point. If the copy is refused,
  depth effects degrade to a no-op and log a warning rather than corrupting the frame.
- The editor intentionally does not implement a second window/timeline toolkit. BBS owns the
  buttons, sliders, number fields, track rows, keyframe diamonds, graph editor and context menus.
  Custom drawing is limited to the preview viewport, safe-area/grid overlay, the track timeline
  (bars, ruler, snapping guide) and track-header controls; keyframe diamonds remain BBS's.
- The compositor FBO is drawn with an identity model-view and its own orthographic projection.
  This matters in the UI: BBS's screen model-view/scissor state must never transform composition
  content into the dashboard's coordinate space. Masks use JOML's column-major translation
  (`m30/m31`); using `m03/m13` silently collapsed every mask to the top-left corner.

## Screenshots and verification

The screenshots above were captured from the Fabric 1.20.1 + BBS 2.7 development client. The
editing workflow was verified with real mouse/keyboard injection: applying an effect, trimming a
track, splitting at the playhead, opening the export dialog, switching to the grade page and
playing back. The full evidence set lives in [`docs/verify/`](docs/verify/), including the real
`1920x1080@60fps` export frame `docs/verify/round6-export-1080p60.png` and the PIXEL contact
sheet. An older exported MP4 frame is still available at
[`docs/export-frame3.png`](docs/export-frame3.png).

## 楦ｈ阿 / Acknowledgements

杩欎釜鎻掍欢鑳戒竴鐩村悜鍓嶈蛋锛岀涓嶅紑涓嬮潰杩欎笁浣嶈禐鍔╄€呫€備粬浠殑璧炲姪鏀寔浜嗘湰鎻掍欢鐨勫紑鍙戯細浠樿垂鐨勫紑鍙戞椂闂淬€佹覆鏌撲笌瀵煎嚭鐜妭鐨勭湡鏈洪獙璇侊紝浠ュ強姣忎竴杞慨澶嶅悗鐨勫洖褰掓祴璇曘€?*鐗瑰埆鎰熻阿浣犱滑瀵?BBS 鍚堟垚鍣ㄥ紑鍙戠殑璧炲姪鏀寔銆?*

| 璧炲姪鑰?/ Sponsor | 鎰熻阿 / Thanks |
| --- | --- |
| **鏄熻景涔呮ⅵ鍚?* | 鎰熻阿浣犺禐鍔╁苟涓€鐩村叧娉ㄨ繖涓」鐩€?|
| **琛€涔嬩功** | 鎰熻阿浣犵殑璧炲姪锛岃鏁堟灉鏍堝拰鍥惧眰鍔熻兘鑳芥寔缁墿灞曘€?|
| **Szv鏂囨．鐗?7** | 鎰熻阿浣犵殑璧炲姪涓庡弽棣堬紝甯姪鍚堟垚鍣ㄤ竴璺慨鍒颁粖澶┿€?|

鍚嶅瓧浼氫竴鐩寸暀鍦ㄨ繖閲岋紙娓告垙鍐呫€岃缃?鈫?鍏充簬 / 璧炲姪鑰呫€嶅悓鏍峰睍绀猴級銆傚鏋滀綘涔熸兂鏀寔杩欎釜鎻掍欢锛屽彲浠ュ湪 GitHub 涓婃墦寮€ issue銆佹彁 PR锛屾垨閫氳繃椤圭洰椤电殑璧炲姪鏂瑰紡鑱旂郴浣滆€呫€?
### 鍏朵粬鎰熻阿 / Other credits

- **椤圭洰涓庣淮鎶?/ Project & maintainer:** 缁坋nd ([@zhongend](https://github.com/zhongend))
- **鍚堟垚鍣ㄥ浘鏍?/ Compositor icon:** 瓒呯骇澶у竷鍙?鈥?original 32脳32 at
  [`docs/gallery/compositor-icon.png`](docs/gallery/compositor-icon.png); BBS UI copy at 16脳16;
  the 128脳128 Mod Menu icon ([`src/main/resources/assets/bbsae/icon.png`](src/main/resources/assets/bbsae/icon.png))
  is the same mark on a padded, rounded dark tile.
- **杩愯鐜 / Runtime:** Minecraft 1.20.1 + Fabric + BBS FS 2.7-1.20.1
- **Photon (浠呯爺绌?/ research only):** [Photon](https://github.com/Low-Drag-MC/Photon) by **KilaBash**,
  licensed **CC BY-NC-SA 4.0**. Only editor/rendering ideas were studied; no Photon source,
  shaders or assets are included. See [PHOTON-NOTICE.md](PHOTON-NOTICE.md).

瀹屾暣鍚嶅崟瑙?[CREDITS.md](CREDITS.md)銆?
## License

MIT.
- **Round 45 (imported fonts display, .ttc extraction, v0.22.1):** [`docs/ROUND45-FONT-DISPLAY.zh.md`](docs/ROUND45-FONT-DISPLAY.zh.md)
- **Round 47 (AE 3D layer logic / AE camera tool / effect fixes / entity replay / pen tool / UI gesture fixes, v0.92):** [`docs/ROUND47-MOTION-CAMERA.zh.md`](docs/ROUND47-MOTION-CAMERA.zh.md)
- **Round 48 (BBS dockable panels / per-pixel-depth ghost root fix / AM repeat family / animated pen paths / replay verification / UI motion, v0.93):** [`docs/ROUND48-DOCK-DEPTH.zh.md`](docs/ROUND48-DOCK-DEPTH.zh.md)
- **Round 49 (exhaustive bug audit: effect data layer P1s, compositor interaction P1s, per-pixel-depth cleanup, replay/pen/motion polish, AM P1 batch, v0.94):** [`docs/ROUND49-AUDIT-FIXES.zh.md`](docs/ROUND49-AUDIT-FIXES.zh.md)
- **Round 50 (second audit wave: timeline/undo/selection + 3D camera/render-cache pipeline, replay interaction real-machine verification, AM deform batch, v0.95):** [`docs/ROUND50-AUDIT-FIXES.zh.md`](docs/ROUND50-AUDIT-FIXES.zh.md)
- **Round 51 (third audit wave: persistence/save isolation + export pipeline deadlock/queue guards, depth AUTO default, pen keep-count/RotoBezier, AM final batch, settings motion, v0.98):** [`docs/ROUND51-AUDIT-FIXES.zh.md`](docs/ROUND51-AUDIT-FIXES.zh.md)
- **Round 54 (UI state-machine audit + render/cache audit P0/P1: real Esc cancel, export snapshot, mask ownership, cache scope, GPU byte budgets, green-frame race, v0.99):** [`docs/ROUND54-UI-STATE.zh.md`](docs/ROUND54-UI-STATE.zh.md), [`docs/RENDER-AUDIT-FIX.zh.md`](docs/RENDER-AUDIT-FIX.zh.md), [`docs/PERF-GPU-MEMORY.zh.md`](docs/PERF-GPU-MEMORY.zh.md)
- **Round 53 (replay undo UI root cause fixed + 3D camera/layer audit + save/curve performance, v0.98):** [`docs/ROUND53-PERF-REPLAY-CAMERA.zh.md`](docs/ROUND53-PERF-REPLAY-CAMERA.zh.md)
- **Round 52 (effect 2D/3D/export consistency + 3D effect unit/matte/raster fixes, effects-right default dock, text/mask performance, save-path tuning, v0.98):** [`docs/ROUND52-EFFECT-3D-PERF.zh.md`](docs/ROUND52-EFFECT-3D-PERF.zh.md)
