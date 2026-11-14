# Round 119：BBS 原版声音、回放 gizmo/Alt 选择、合成器文字裁切

> 版本 `0.99-hotfix86`。本轮对应三个严重报告，全部按第一性原理从数据流定位，并用真实客户端
> 日志/像素验证；2D 与 3D 预览、BBS 原版导出共用同一套渲染与音频数据。

## 1. BBS 原版界面没有声音，BBS 导出也没有声音

### 根因（不是“音量没开”）

声音不是“文件存在”这个事实，而是 **OpenAL 源被要求播放一段已经混好的采样**。整条链有三段，
原来只有第一段是通的：

1. **合成器预览**：`CompAudioPlayer` 把 `CompAudioMixer.mix()` 的 48 kHz 立体声缓冲交给
   `SoundPlayer`。这一段一直是好的，所以合成器里能听到声音。
2. **BBS 原版影片页播放**：BBS 只播放 `film.camera` 里的 `AudioClip`，逐帧由
   `AudioClientClip.manageSounds(ClipContext)` 驱动。`CompositionClip` 是另一种 clip，从不参与
   这条链，所以“合成器”在 BBS 原版界面里是一张无声的画面。
3. **BBS 原版导出**：`AudioRenderer.renderAudio()` 只解码 `camera.getClips(AudioClip.class)`，
   再把 WAV 交给录像/FFmpeg。合成音轨不是 `AudioClip`，因此 F6/影片页导出也是无声。

因此不是“点一下播放按钮”的问题：即使 BBS 自己的播放/导出完全正常，合成音频也永远进不了它的
音频管线。

### 修复：把合成音频接进 BBS 自己的音频时钟和导出边界

- 新增 `client/audio/CompMixCache`：按 **音频内容**（clip 路径/入点/偏移/时长/增益/音调/速度/
  声像/淡入淡出/静音/循环）缓存一份混音。编辑才失效，拖动播放头不重混；混音在工作线程完成，
  客户端线程只负责把结果交给 OpenAL。
- 新增 `client/audio/CompFilmAudio`：在 BBS 的
  `AudioClientClip.manageSounds(ClipContext)` **之后**读取同一帧 `CompositionClip` 放进
  `clipData` 的 `CompInstance`，按 `instance.time()`（与画面完全相同的
  `(ticks * timeScale + offset) / 20`）定位每个合成窗口的播放位置。播放/暂停/拖动/循环都跟随
  BBS 的 `context.playing` 与影片时钟；两个窗口各自有独立音源，互不抢位置。
- 新增 `AudioClientClipMixin`：在 `manageSounds` 的 `TAIL` 调用上面的逻辑。选择这个点是因为
  它同时具备“本帧的合成实例”和“本帧是否在播放”，不需要第二套时钟。
- 新增 `CompFilmAudioExport` + `AudioRendererMixin`：在 BBS 生成导出 WAV 后，按同一套
  `compositionTimeAt()` 把每个合成窗口混进去（48 kHz 立体声，保留 BBS 原有单声道内容）。
  **当影片只有合成音频、没有任何 `AudioClip` 时**，`renderAudio` 原本会直接返回 false 且不创建
  文件；现在依然会把合成混音写成 WAV 并返回 true，导出 session 因此能拿到 `audioFile`。
- 新增 `PanelVideoExportSessionMixin` / `WorldVideoExportSessionMixin`：在 `prepare()` 期间把
  “正在导出的影片”绑定到线程局部，避免面板隐藏后 `renderAudio` 找不到影片。
- 影片播放开始时自动暂停影片页的“试听混音”，避免同一份混音以两个位置同时播放。

### 验证（真实客户端）

原版影片页播放（日志）：

```text
Film audio audit: original film playback started=true
Film audio audit: original playback running=true compAudioSources=1 status='播放中 · 1 个合成音频窗口 · 播放中'
```

BBS 自己的导出音频渲染（测试影片 **没有任何 BBS AudioClip**，`BBS track=false`）：

```text
Film audio: composition audio mixed into bbsae-audio-audit.wav
  (window=0.00..155.80s, 1 comp window(s), 7478400 frame(s), BBS track=false)
Film audio audit: BBS export audio ok=true file=bbsae-audio-audit.wav
  bytes=29913644 frames=7478400 peak=0.36621094 comps=2
```

`peak=0.366` 证明文件里确实是合成混音的正弦测试音，而不是静音占位。截图：
`docs/verify/round119-film-audio.png`。

## 2. 回放编辑器 gizmo 闪烁、Alt+左键选择与部位高亮

### 2.1 闪烁的根因：gizmo 的“本帧摆放”被缓存的 pick pass 跳过了

BBS 在每帧世界渲染开始时调用 `Gizmo.forgetPlacement()`；UI pass 只能绘制 **本帧刚捕获** 的
gizmo 摆放矩阵。回放页的 pick pass 为了性能缓存了整个 id pass：

```text
if (this.lastValid && signature == this.lastSignature) return;
```

而 gizmo 的 `Gizmo.captureVisual/renderStencil` 正是 **只在 pick pass 里** 调用的。于是：

- pick 重跑的帧：gizmo 有摆放，能画；
- pick 命中缓存的帧：没有任何 capture，`hasLastRenderMatrix=false`，gizmo 整帧消失。

鼠标/相机/播放头一动就重 pick，一停就命中缓存——视觉上就是 gizmo 按 pick 频率闪。

**修复**：把“摆放捕获”和“id 读回”拆开。`UIReplayViewport.render()` 每帧调用
`ReplayPickPass.captureGizmoVisual()`，用与 stencil 完全相同的 `gizmoStack()` 调
`Gizmo.captureVisual()`；昂贵的 id pass 仍然只在输入变化时执行。两者共用同一个矩阵构造函数，
所以“看到的 gizmo”和“点到的 gizmo”不可能再各自为政。

验证日志（同一次运行，两帧静止，id pass 保持 9 次不增长，而 capture/draw 每帧都有）：

```text
Replay gizmo script: part settled [...] pickRenders=9
Replay gizmo script: still frame pickRenders=9 (cache hits if unchanged)
Replay gizmo capture: origin=(-123.666046, 110.249985, -193.00558) bone=anchor areaH=267.0
Replay gizmo draw: area=475x267@211,54 placed=true center=265.062,23.962513
```

> 说明：这个测试影片里被选中的 `anchor` 骨骼在相机 viewport 之外（center y=24 < area y=54），
> 所以截图本身看不到手柄；日志证明的是“缓存命中帧仍然每帧提交了 gizmo”，而这正是原 bug
> 消失的那一帧。用户影片中骨骼在视口内时手柄会在同一像素位置稳定显示。

### 2.2 Alt/普通点击的逻辑

原实现：

```java
if (Window.isAltPressed() && picked >= 0) selectReplay(picked);
if (picked >= 0) selectReplay(picked);       // 普通左键也抢当前演员
```

后果：普通左键点在另一个模型上会直接切走当前回放，当前演员的部位永远没机会被选；Alt 的语义
也被稀释。BBS 原版只把“其他回放”放进 stencil 供 **Alt** 选择，普通点击属于当前模型的部位。

**修复**：

- 抽出纯函数 `ReplayClickRules.action(alt, pickedReplay, hasPart)`：
  - `pickedReplay >= 0` 且 Alt → `SELECT_REPLAY`；
  - `pickedReplay >= 0` 且非 Alt → `NONE`（不抢，不当作部位）；
  - 否则有部位 → `SELECT_PART`。
- `ReplayPickPass` 里其他回放的整模 id 不再被塞进 `pickedForm`，避免把别的演员的 Form 传给当前
  演员的 body-part picker。
- 悬停提示同样遵守：非 Alt 时提示“按住 Alt 选择回放”，而不是让人以为普通点击就能换模型。

### 2.3 部位高亮（跟 BBS 原版一样会“亮起”）

BBS 的 `FilmStencilPicker` 会在 pick 后调用
`StencilFormFramebuffer.renderPreview()`：把 pick buffer 用 picker-preview shader 合成回视口，
命中 id 的像素按 `BBSSettings.stencilHighlightColor` 上色。回放页只做了 id pass，**从未调用这个
合成**，所以点击选中了部位却没有任何视觉反馈。

**修复**：`ReplayPickPass.renderHighlight()` 每帧：

1. 鼠标下有 id 时：`stencil.renderPreview(context, area)`（其他回放只在 Alt 下亮，和 BBS 把其他
   演员放进 stencil 的条件一致）；
2. 鼠标不在任何可用 id 上时：用 `stencil.indexOf(selectedForm, selectedBone)` 找到当前选中骨骼的
   id，持续点亮它（和模型编辑器里从骨骼列表选中骨骼后的表现一致）。

### 2.4 点击 head 却落到 anchor 的逻辑错误

`UIReplayModePanel.render()` 每帧把 `keyframeEditor.getBone()` 写回 session。点击部位后，
`UIReplaysEditorUtils.pickForm()` 有时要等 track/pose editor 重建一帧，期间 `getBone()` 还是上一次
的 `anchor`，于是刚选中的 `head` 立刻被覆盖。

**修复**：

- `applyPendingPartPick()` 在 `pickForm` 后检查当前 editor bone；不等于用户点的骨骼时，直接
  `poseFactory.poseEditor.selectBone(bone)` 强制到点击的骨骼。
- session 只在 editor 的骨骼 **真的发生变化** 时才同步（`lastEditorBone`），切换回放时清零；
  这样“编辑器的旧值”不会覆盖“视口刚点的值”。

## 3. 合成器文字下半部被裁切

### 3.1 根因：矢量栅格按 BBS 行盒算尺寸，而不是按字形墨迹

hotfix85 的 `VectorTextRaster` 用

```text
imageH = lines * lineHeight * (fontSize / fontHeight) * pixelScale
```

定纹理高度。这个公式量的是 **BBS 的行盒**，而实际绘制的是 Java2D 的 `GlyphVector`：它的
`ascent + descent` 通常大于行盒（中文回退字体更明显，约 1.25–1.4 em；带 `g j p q y` 的拉丁文
至少多一个 descent）。基线又放在 `pad + ascent`，最后一行/下伸部就落到纹理下边缘之外——表现为
一条水平的“下半部被切掉”。

另外，3D/带效果图层会先把文字烘焙到 **图层矩形** 大小的栅格；字号大于图层高度或描边较粗时，
同样会从底部/两侧被裁。

### 3.2 修复

- `VectorTextRaster.plan()` 先用真实字形轮廓的 `GlyphVector.getVisualBounds()` 求所有墨迹的
  `minX/maxX/minY/maxY`（含描边），再决定：
  - `ascent = max(字体 ascent, -minY) + 1`；
  - `descent = max(字体 descent, maxY) + 1`；
  - `imageH = (行数-1)*lineGap + ascent + descent + pad*2`；
  - 左/右方向的墨迹也计入宽度（对齐方式导致的左移用独立 `leftPad` 补偿）。
  这让“纹理底边”由字形本身决定，而不是由行盒猜。
- 栅格化与测量共用同一套 `Geometry`（同一 `FontRenderContext`、同一 baseline/lineGap/
  leftPad），测量的包围盒就是绘制的包围盒。
- `CompLayerSourceCache.bounds()` 对 3D 文字把栅格扩到完整文本块：行数×字号×行高 + 描边；
  未换行时再按“CJK 约 1 em / 拉丁约 0.62 em”的保守字符宽度估计扩宽，避免宽行从两侧被 3D
  栅格裁掉（多余部分是透明留白，不会改变文字像素的位置）。

### 3.3 验证

`docs/verify/round119-textclip.png`（`CompRenderer.renderFrame` 全分辨率输出，没有 UI 覆盖）：

- 第 1 行：默认 BBS 位图字体，`gjpqy` 下伸部完整；
- 第 2 行：导入的 `msyh-face0.ttf`（矢量栅格），`gjpqy`、中文、数字完整；
- 第 3 行：150 px 字号、3D、辉光+模糊、图层高度只有 120 px；文字的下伸部仍然完整（两侧的裁切
  是 3D 透视下文字超出画布边缘，不是栅格裁切）。

## 4. 测试与构建

- 新增：
  - `CompMixCacheKeyTest`：混音 key 只随音频内容变化；
  - `CompFilmAudioMappingTest`：`(ticks * timeScale + offset)/20`，含分割窗口/偏移/变速；
  - `ReplayClickRulesTest`：Alt 选模型、普通点击不抢模型、部位选择；
  - `VectorTextRasterGeometryTest`：descent/描边/多行/右对齐墨迹必须落在栅格内；
  - `TextLayerBoundsTest`：3D 文字宽度估计覆盖 CJK+拉丁；
  - `Round119AudioGizmoContractsTest`：混音接入 BBS 时钟/导出、gizmo 每帧捕获、Alt 规则与
    高亮调用都在正确位置。
- 全量：**200 个测试套件 / 1218 项测试 / 0 失败 / 0 错误 / 1 跳过**。

## 5. 已知边界

- 合成音频目前混合的是 **当前合成文档自己的 `doc.audio`**；预合成内部子合成的音轨还不参与父级
  混音（预合成作为独立时间轴，后续可递归混音）。
- 反向播放（`timeScale < 0`）时不会倒放音频（OpenAL 不支持负速率），会静音该窗口；正向
  变速/偏移/分割均与画面一致。
- BBS 原版“录制时静音影片音频”（`videoMuteAudioWhileRender`）只作用于 BBS 自己的音频源；
  bbsae 的合成监听仍可听见，但录制文件里的音轨由导出混音决定，不受监听音量影响。
