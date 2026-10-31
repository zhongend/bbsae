# Round 18 — 曲线可见性 / 外部素材导入 / 音视频导出 / 素材库单页 / 纯色背景 / 摄像机（0.7.0）

> 承接 `0.6.0`。本轮处理的六个问题都由用户报告，全部按"先找不变量、再改代码"的方式定位：
> 每个 bug 都先写成一个**不需要启动游戏就能验证**的断言，再让代码满足它。
>
> 产物：`bbsae-0.7.0.jar`（583 134 字节，SHA-256
> `4d198356871ef5b0905725bed9d320a9346c63c47464b36bab22c8b2a72d254f`）。测试 137/137。

---

## 1. AE 曲线不显示，只有几个关键帧点

### 1.1 定位

曲线上每一个像素的 Y 都由三个数决定：`(value - axisMin) / (axisMax - axisMin)`、图高、以及
图的原点。本轮把这三者分别在**无游戏环境**下打印出来，得到（真实运行日志）：

```
curve spans transform.x area=150,284 810x218 view=0.0+30.0 axis=-100.0..100.0 fps=60.0
```

两个数字直接暴露了问题：

| 观察 | 应该 | 原因 |
| --- | --- | --- |
| `axis=-100..100` | 位置通道是 380…1500 | `fitValue()` 只在点"适配/聚焦/切图表"时调用，`rebuild()` 从不调用，所以值轴停在默认 ±100 |
| `view=0..30`（12 秒合成） | `0..12` | `rebuild()` 用 `getDurationTicks() / 24`，而 `getDurationTicks()` 返回的是 **帧数**（`duration × fps`），60 fps 下放大了 2.5 倍 |

值轴 ±100 时，所有位置值都落在矩形之外：`Batcher2D.clip()` 把整条线裁掉，只剩关键帧的菱形
（它们本来就在同一个 `toY()` 上，只是少数正好落在范围内）—— 这就是"只有几个点点"。

0.6.0 里还有第二个陷阱：`CurveSpans` 存的是**绝对** Y，而绘制时又加了一次图原点，整条线被推到
裁剪区下方。那份改动已在本轮之前修正（`build(from, to, originY, sampler)` 只减一次原点），但
**只修坐标不修视口**，用户看到的仍然是"没有线"。

### 1.2 修复

1. **值轴永远框住数据**：`rebuild()` 在换图层时 `fitTime() + fitValue()`，换通道时 `fitValue()`；
   其余情况只在"当前值轴完全看不到该通道任何一个关键帧"时才重新适配
   （`fitValueIfInvisible()`）——这样手动缩放的视图不会被每帧重置，而数据也永远不会跑到画布外。
2. **时间轴用合成时长**：`fitTime()` 用 `doc.duration`，不再做 `/24` 的帧→秒换算。
3. **无关键帧时框当前值**：不透明度通道的值在 0…1 之间，给它一个 ±100 的轴等于把线压成一条边。
   现在按 `当前值 ± max(1, |值|/2)` 取景。
4. **越界值钳到边缘**：采样器把 Y 钳在图矩形内（AE 的行为）。值超出轴范围时曲线贴着上/下边缘，
   而不是整条消失。
5. **删掉列几何缓存**。0.6.0 用 "view stamp + 每通道 revision + 脏列区间" 缓存 `CurveSpans`。
   实机日志里出现过相邻列在毫不相关的两个值之间跳动、而同一条数据在无游戏环境下完全平滑的现象：
   一个无法证明失效条件的缓存就是 bug 工厂。现在每帧按图宽重采一次（810 列 × 一次二分查找，
   < 0.2 ms），顶点数仍与**宽度**成正比，与斜率无关（`CurveSpans` 保留，只是不再是缓存）。

### 1.3 验证

* `CurveGeometryReproTest`：用截图里那组真实几何（810×218、12 秒、350…800 轴、三个缓动关键帧）
  复采样，断言"每列都有值""段内单调""最后一个关键帧之后恒定""跨度 = 采样 Y − 原点，只减一次"。
* 实机日志（修复后）：`view=0.0+12.0 axis=329.0..771.0 keys=3`，采样值 620 → 574 → 441 → 381 →
  406 → 469 → 545 → 621 → 687 → 720，与缓动曲线一致。
* 实机截图：`docs/verify/round18-curve-editor.png`（网格 + 通道列表 + 连续曲线 + 关键帧 + 手柄）。

---

## 2. 无法导入外部素材

`UIFileDialogs.pickFile()` 走 LWJGL 的 tinyfd —— 一个**原生窗口**。全屏 Minecraft 下它会
开在游戏后面，或者干脆不弹；不弹的时候回调永不触发，按钮就是"死的"，而且没有任何异常可报。

修复：新增 `UIImportBrowser`（本仓库自绘的游戏内文件浏览器）：

* 驱动器列表 → 目录 → 按扩展名过滤的文件，双击目录进入，`上一级`/路径框/`转到` 三个入口；
* 图片、字体、音频三条导入路径共用它（`importLibraryImage` / `importFont` / `importAudioFile`）；
* 导入后自动刷新素材库并**选中刚导入的文件**（`UIAssetBrowser.selectPath`），
  不再需要用户在几百个缩略图里自己找；
* 复制统一走 `copyIntoProjectImages()` → `assets/images/<name>`，音频走 `assets/audio/<name>`，
  路径解析统一走 `ImageTextureCache.resolve()`，不会再出现"文件存在但加载不到"。

`ESC` 先关导入浏览器，再关素材库。

---

## 3. 视频与音频导出

### 3.1 音频是时间线内容，不是输出设置

`CompAudio` 挂在 `CompDocument` 上（入点、源偏移、时长、增益、淡入淡出、循环、静音），
`CompAudioMixer` 是一个纯函数：`(document, 时间区间) → 48 kHz 立体声样本`。
同一份混音喂给三个地方：视频导出的 AAC 轨、独立 WAV 模块、以及"保留帧"的 PNG 序列。

解码用 BBS 自己的 `WaveReader` / `VorbisReader`（WAV、OGG），不引入新依赖。

### 3.2 区间导出的偏移 bug

`mixClip()` 原来用 `local = frame - firstFrame` 作为源内偏移。当片段在导出区间**之前**就开始了，
`firstFrame` 被钳成 0，于是"导出 2s…3s"实际拿到的是文件的开头（少了 `exportStart - clipStart`）。
修复为 `seek = max(0, exportStart - clipStart)`，源索引和淡入淡出都按片段自身时间计算。
`CompAudioMixerTest` 用一个"前半段静音、后半段有声"的 WAV 把这条钉住。

### 3.3 导出模块

* `CompOutputPreset.kind = "video" | "audio"`，音频模块 `audio_wav`（48k PCM）、`audio_m4a`（AAC 192k）。
* 渲染队列的"预设"从**文本框改成选择器**（`UIChoiceButton`，显示模块真名 + 视频/音频图标）：
  以前要手输 `review_h264` 这种内部 id，输错就静默回退到第一个（视频）预设，"导出音频"实际渲染视频。
* 队列行显示模块名、状态、进度和消息，而不是 `audio_wav`。
* `start()` 找不到预设时按 job id 前缀决定用音频还是视频模块，不再"第一个预设"。
* 视频混流加 `-af apad -shortest`：帧数与采样数永远差最后零点几毫秒，没有 apad 会吃掉最后一帧。
* 音频模块不渲染任何一帧：`prepareAudio → encode`，失败信息明确（"这条合成没有可导出的音频"）。

### 3.4 迁移

v5 迁移会给**已有非空预设列表**追加音频模块（老工程只有视频预设，`getPreset("audio_wav")` 返回
null 后会回退到第一个视频预设）。这条有专门的测试，包括"迁移两次不会重复添加"。

---

## 4. 素材库单页与按钮排版

素材库原来把每个控件钉在 620×400 的矩形里；在 432 逻辑像素高的屏幕上，底部动作行**跑到屏幕外**，
用户看到的是"按钮点了没反应"。分页（‹ ›）让情况更糟：两个翻页按钮最先掉出去。

现在是单页：

* 顶部标题/搜索/刷新/关闭按面板真实宽度定位，窄屏自动缩短文案；
* 分类条**横向滑动**（滚轮、拖动、两侧 ‹ › 区域），永不换行；
* 缩略图网格**纵向滚动**，用剩余的全部高度，右侧有滚动条；
* 底部动作行按宽度切换 4×44px / 92+72+106+106 两种排布，不会再重叠。

实机截图：`docs/verify/round18-asset-library.png`（1440 宽、GUI scale 2；13 个分类一行放得下，
底部四个按钮不重叠，缩略图带文件名）。

同时修掉渲染队列的排版：`queueFields` 原来是 `row(4)` + 四个 `fieldRow`，而 BBS 的
`UIElement.row()` **无视子元素请求的宽度**、平均分配 —— 每个 110px 标签列被塞进 1/4 宽的盒子里，
旁边的输入框宽度变成负数，于是"预设/名称/开始/结束"互相压在一起。改成绝对定位的两行两列。

---

## 5. 合成器背景改成纯色

三层原因叠在一起：

1. `CompDocument.background` 默认 `0x00000000`（全透明）；
2. 打开工程时的"一次性迁移"把 `previewBackground` 强制设成 **1**（BBS 世界底图），
   于是合成区域是透明的，露出后面的游戏画面；
3. `enter3DSpace()` 也顺手把底图打开。

修复：

* `background` 默认 `0xFF000000`（不透明黑），合成页的"背景"色可改；
* 打开工程不再强制世界底图（`previewBackground = 0`），世界底图仍是预览栏里的一个开关；
* `enter3DSpace/exit3DSpace` 不再改背景；
* `CompRenderer.stageColor()` 成了唯一出口：**BBS 底图预览**透明（世界要透出来）、
  **alpha 导出**透明（文件要带 alpha）、其余一律用 `comp.background`；
* v4 → v5 迁移把老工程从"世界底图"改回纯色一次，用户之后再改不会被覆盖（有测试）。

---

## 6. 摄像机

按"摄像机是视图，不是内容"这一条重排：

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| `activeCamera()` 调 `activeAt()`，而它先看 `visible` | 点一下眼睛图标，整个合成立刻回到 1:1 | 摄像机不再看可见性，也不看入出点（`CompLayer.isCamera()`） |
| 入出点默认 0…12 秒 | 12 秒合成里，5 秒后摄像机"消失" | 同上 |
| gizmo / 命中测试 / 选择框读 `scalarAt("x", layer.x)` | 给位置打关键帧后，图标停在起始位置，画面已经走了 | 新增 `CompLayer.cameraEye(frame)`（取 transform 通道）与 `cameraTarget(frame)`，四处调用统一 |
| `layerAt()` 自己算了一套摄像机命中几何 | 画出来的摄像机本体点不动（只有正好在画面中心时能点） | 直接复用 `handleAt()`，绘制与命中只有一份几何 |
| `of()` 用"cameraZ 是否为 0"判断有没有通道 | 推拉关键帧经过 0 的那一帧跳到另一个距离 | 改为"通道存在就用通道值" |

另外：摄像机的 gizmo 在眼睛关闭/出点之外也照常绘制（否则画面被摄像机改变却没有手柄可拖）。

`Camera3DTest` 新增两条：`cameraStaysActiveWhenHiddenOrTrimmed`、
`cameraEyeFollowsTheTransformChannel`。

---

## 7. 文件地图

新增：

| 文件 | 作用 |
| --- | --- |
| `comp/CompAudio.java` | 音频片段模型 |
| `client/export/CompAudioMixer.java` | 混音 + WAV 写出（纯函数） |
| `client/ui/UIImportBrowser.java` | 游戏内文件浏览器 |
| `src/test/.../CompAudioMixerTest.java` | 混音 7 条断言 |
| `src/test/.../CompStoreMigrationTest.java` | v4/v5 迁移 6 条断言 |
| `src/test/.../CurveGeometryReproTest.java` | 曲线几何 2 条断言 |

修改（要点）：

| 文件 | 改动 |
| --- | --- |
| `client/ui/UICompCurveEditor.java` | 值轴/时间轴重新取景、越界钳制、删除列缓存、DEBUG_SPANS |
| `client/ui/CurveSpans.java` | 去掉 stamp/viewKey，只做容器 |
| `client/ui/UIAssetBrowser.java` | `selectPath`/`reveal`、导入后选中 |
| `client/ui/UICompRenderJobList.java` | 显示模块名与进度 |
| `client/ui/UICompositorPanel.java` | 摄像机几何统一、纯色背景、预设选择器、音频段、导入路径 |
| `client/render/CompCamera.java` | cameraZ 通道优先 |
| `client/render/CompRenderer.java` | `stageColor()` 三分支、`transparentStage` |
| `comp/CompDocument.java` | 不透明背景、`activeCamera` 语义 |
| `comp/CompLayer.java` | `isCamera`/`cameraEye`/`cameraTarget` |
| `comp/CompStore.java` | v5 迁移（背景 + 音频预设） |
| `comp/CompOutputPreset.java` | `kind`、音频模块 |

## 8. 开发用验证入口

```bash
# 打开合成器并直接进入某个状态，然后截图（需要真实桌面）
./gradlew runClientView -PbbsaeView=curve,assets
```

`-Dbbsae.debugView=curve|assets|camera|queue`（逗号分隔，每 8 秒切一个），
`-Dbbsae.debugCurve=true` 会打印每帧的图几何与采样值 —— 第 1 节的结论就是它给出的。
这两个开关只影响 `runClientView`，正式 jar 里没有副作用。

## 9. 未做

* 音频轨在时间轴上还不可见/不可拖动（目前只能在"合成"页里改入点、增益、淡入淡出）；
* 曲线编辑器的顶部工具条仍是 8 个按钮，AE 的右键键类型菜单没做；
* 摄像机没有"目标点吸附到图层"。
