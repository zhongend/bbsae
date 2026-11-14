# Round 118：Alt 选择、矢量文字、模糊质量、影片页音频与回放卡顿

本轮对应 hotfix85，覆盖五个报告。所有验证均使用本地客户端截图/日志；导出与 2D/3D 预览走
同一套 `CompRenderer` / `CompAudioPlayer` 路径。

## 1. Alt / 组件选择根本无效（已修复）

### 根因

`ReplayPickPass` 负责把模型和 gizmo 画进 stencil 缓冲。可见模型由 `ReplayModelPass` 用
`viewCamera.view` 绘制，但 pick pass 之前把 `RenderSystem` 的 model-view 设成**单位矩阵**。
结果：

- 模型不在 stencil 的可见位置上；
- 光标下面读到的永远是 0；
- `StencilMap` / object id 再正确也没有 actor 或骨骼可读。

所以不是 Alt 键没绑定，也不是点击回调没调用，而是 **id pass 和显示 pass 用了不同的相机**。

### 修复

`ReplayPickPass` 现在：

1. `modelView.peek().getPositionMatrix().set(viewCamera.view)`；
2. 使用同一个 `viewCamera.projection`；
3. 同时设置并恢复 `RenderSystem.setInverseViewRotationMatrix(...)`；
4. 恢复 actor VAO，避免离开回放页后 BBS 的 UI/world pass 使用失效 VAO。

验证日志（hotfix85 客户端）：

```text
Replay gizmo script: click head [index=60 replay=-1 form=bbs:model bone=head]
```

`index=60` 已越过 gizmo 的 stencil 区，`form=bbs:model`、`bone=head` 说明点击确实命中了当前
演员的骨骼。Alt 分支保持 BBS 原版顺序：先选其他回放，没有其他回放时选当前演员的部位。

### 可操作性

- 鼠标悬停显示“选择回放：xxx”或“部位：xxx”，Alt 时显示“Alt · 选择回放”；
- 选中部位后状态栏/属性面板显示骨骼；
- `invalidatePreview()` 会让 pick 缓存失效，编辑姿势后不会点到旧位置。

## 2. 回放编辑器编辑优化

- 部件点击、骨骼关键帧插入、gizmo 拖动现在使用真实 stencil 结果，而不是“看起来能点”；
- `debugClickBone`、`debugPartSummary`、悬停提示共用同一套 pick 结果；
- 骨骼 gizmo 使用骨骼复合矩阵，拖动从选中骨骼开始；
- pick pass 增加输入签名缓存，鼠标/相机/播放头/选择不变时不再逐帧重绘；
- 虚拟回放列表过滤只在列表或选择变化时执行；
- 属性编辑、关键帧、撤销等路径调用 `invalidatePreview()`，缓存不会显示旧 gizmo。

## 3. 文字边缘像素化（矢量栅格）

### 问题

BBS 的 `FontManager` 把自定义字体交给 STB 烘成 **bitmap atlas**，图层或摄像机放大后是点采样
的阶梯。字体文件本身是矢量，但进入渲染管线后已经不是。

### 实现

新增 `VectorTextRaster`：

- 用 Java2D `Font.createFont` + `GlyphVector.getOutline()` 取得真实字形轮廓；
- 按最终像素密度栅格化：`outputScale × layerScale × 2`，最高 4×；
- 每个 block 缓存为一张线性过滤的 RGBA 纹理，按原布局的字体单位换算到合成像素；
- 缺字回退（中文、标点）也选择系统矢量字体，逐字回退，不再回到 Minecraft 位图字形；
- 默认 BBS 位图字体保持原样；带逐字动画的文本保留原逐字路径（动画需要每字变换）。

验证截图：`docs/verify/round118-font-vector.png`。同一张图里：

- 自定义 Times 字体的拉丁文/数字是平滑矢量；
- 中文回退也变成矢量（系统字体）；
- 最后两行“Default font”仍是 BBS 默认位图字体，作为对照。

## 4. 模糊断层与像素感

- `BLUR`：tap 数由 `floor(radius*0.125)`（最多 64，但大半径步长 8 px）改为
  `ceil(radius*0.5)`（8–64），半径 200 时步长从 8 px 降到约 3 px；
- `AE_BLUR`：
  - 交叉/复合：自适应 8–48 tap；
  - 径向：32 tap；
  - bokeh：48 tap，逐像素旋转 golden-angle spiral，打散环状采样纹；
  - 通道模糊：33 tap；
  - 双边：7×7；
- `RADIAL`：12 → 32 tap；
- 所有模糊末尾加入按 alpha 缩放的 1/255 dither，打散 RGBA8 的渐变条带。

验证截图：`docs/verify/round118-text-blur.png`（文字 + 发光 + 模糊）。模糊边缘连续，没有
硬阶梯；大半径下也没有旧版的梳状断层。

## 5. 影片页音频浏览

- BBS 影片页预览右上角新增 `Icons.SOUND` 按钮；
- `FilmAudioAudition` 打开 BBS overlay：
  - `▶ 合成器混音 · …（播放 / 停止）`：调用 `CompAudioPlayer`，影片播放时跟随播放头；
  - 工程素材目录递归扫描出的音频文件，以及 `CompAudio` 轨道引用的文件；
  - 选中文件调用 `CompAudioPreview.play(path)` 试听；
  - 无音频时显示“没有可试听的音频（先在合成器素材箱导入音频）”。
- `ClientTickEvents` 每 tick 调用 `CompAudioPlayer.tick()`，仅在影片 runner 运行时 sync，
  暂停时允许自由试听。

验证日志与截图：

```text
Film audio audit: comp mix status='播放中 · 0.82s' playing=true
docs/verify/round118-film-audio.png
```

截图里能看到 `film_export_test · test-tone.wav` 条目和“合成器混音”条目（测试工程为验证临时
加入的 1 秒 440 Hz 音轨）。

## 6. 回放卡顿

- pick pass 只在以下输入变化时重绘：鼠标位置、视口区域、相机 view/projection、播放头、
  播放状态、选中回放/骨骼、bone gizmo、可见/剔除数量、实体表大小、回放数量；
- 空闲验证：`idle pick passes before edit = 2`（页面渲染 60 tick，只做了 2 次完整 id pass；
  旧行为是每帧一次）；
- 虚拟回放列表过滤只在 `bind`/选择变化/列表版本变化时执行，避免每帧重建 BBS 列表；
- 缓存键包含 `invalidatePreview` 显式失效，编辑后不会卡在旧 pick。

## 测试与构建

- `test`：194 suites / 1197 tests / 0 failures / 0 errors / 1 skipped；
- 新增 `Round118QualityContractsTest`（view 矩阵、VAO、矢量文字、模糊 tap/dither、影片页音频）；
- jar：`bbsae-0.99-hotfix85.jar`；
- 版本文档：`CHANGELOG.md`、`README.md`。

## 已知边界

- 默认 Minecraft 位图字体仍是位图；矢量化只针对导入字体（以及其系统矢量回退）；
- 逐字动画文本保留原路径；
- 音频浏览器列出的是 bbsae 工程音频与 `CompAudio` 引用，不是 BBS 资源包里的 sound event；
- 模糊仍是 RGBA8 管线，dither 是亚 LSB 级；极端大半径/低分辨率预览仍建议提高预览分辨率。
