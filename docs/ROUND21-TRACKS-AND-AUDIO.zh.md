# Round 21 — 轨道缩放 / 音频与视频轨道 / 窄面板（0.10.0）

> 用户点名三个问题：**轨道不能通过快捷键放大缩小**、**音频和视频没有轨道**，以及"一切 UI bug"。
> 下面每条都先写成一句可以被证伪的话，再改代码。
>
> 产物：`bbsae-0.10.0.jar`（623 996 字节，SHA-256
> `213a21e0849a733d69c915a56df37302f2aac00c0310e1c22f745aea3f756218`）。测试 **164/164**。

---

## 1. 轨道不能缩放：一个单位错误，两处入口

### 1.1 断言

> "在 12 秒合成、720px 轨道区里按一次 `=`，用户应该看到时间轴从 12 秒变成约 8.9 秒。"

### 1.2 定位

快捷键本身是接好的（`=` / `-` → `trackTimeline.zoom(±1)`），滚轮也接了（`animateZoom(anchor, wheel)`）。
问题在 `zoom()` 的实现：

```java
this.xAxis.zoomAnchor(0.5F, steps);   // steps = ±1
```

而 BBS 的 `Scale.zoom(delta, min, max)` 是 **加法**：

```java
setZoom(clamp(getZoom() + delta, min, max));
```

`getZoom()` 的单位是**像素/秒**。12 秒合成适配后 ≈ 60 px/s，加 1 等于 1.7% —— 用户看到的就是"没反应"。
滚轮 `animateZoom(anchor, ±1)` 同样是加 1，一起坏。

### 1.3 修复

```java
static double zoomTarget(double current, double steps)   // current * 1.35^steps，钳 [0.05, 200000]
...
this.xAxis.zoomAnchor(anchor, target - current, 0.05D, 200_000D);   // 乘法目标 → BBS 要的加法增量
```

* 一次 `=` / 一格滚轮 = **1.35×**，锚定鼠标（键盘用 0.5 = 视口中心）。
* 状态栏改成 `时间轴 135%（81 px/s）`——相对全长的百分比 + 绝对密度，两者都能验证。
* `Alt + =` / `Alt + -` = 轨道**高度**（行高），和 `Alt+滚轮` 同一语义；帮助页同步。
* 上下限从 `Scale` 默认的 0.01…1000 放宽到 0.05…200 000：1000 px/s 对 1 秒合成也不够（一帧要 60 px）。

### 1.4 验证

两个测试直接驱动 **BBS 真 `Scale`**（不是模型）：

* `CompTimelineZoomScaleTest.oneStepIsARealZoomOnTheRealScale`：60 px/s 一步后必须正好 81 px/s；
* `…zoomOutAndBackRestoresTheFittedView`：三步出 + 三步进必须回到 60；
* `…zoomingInShowsLessTime`：两步进后可见时长必须小于原来一半；
* `CompTimelineZoomTest`：`1.35^steps`、可逆、钳制、以及"一步必须改变 >20%"（旧实现只改 1.7%）。

---

## 2. 音频没有轨道

### 2.1 断言

> "导入一段音频后，时间轴上必须有一条能看到、能点、能拖、能裁、能用 Delete 删掉的片段。"

### 2.2 根因

`UICompTrackTimeline` 的行集合就是 `doc.layers`：`rowAt`、`clampScroll`、`renderRows`、命中测试、
滚动总高全部以 `rows().size()` 为准。`doc.audio` 只有检查器里的一段列表，**从未进入时间轴的坐标系**。
所以音频"存在但不可见"：没有 bar、没有入点、没有可拖的东西。

### 2.3 修复：把音频当成一条**虚拟行**

```java
private int rowCount()      { return rows().size() + (hasAudioRow() ? 1 : 0); }
private int audioRowIndex() { return rows().size(); }
```

* 行高、滚动、时间轴映射全部复用同一套数学，不新开第二套时间线；
* 行头：`🔊 音频 (n)`，空轨时显示"导入音频后，片段会出现在这条轨道上"；
* 轨道区：每段一个绿色 bar，静音变暗、选中描边，宽度 = `clip.length(comp.duration)`；
* 交互与图层 bar 完全一致：主体拖动 = 改入点，左右 8px = 裁入点/裁剪长度；
  裁入点时**源偏移同步**（内容不动），且不能把 offset 拖成负数；
* 选中音频会清空图层选择（Delete 只有一个含义），`deleteSelected()` 先看音频再看图层；
* 导入时用 `CompAudioMixer.probeDuration()` 记录真实时长——时间轴画的和导出混的是同一个数。

### 2.4 验证

实机截图 `docs/verify/round21-audio-track.png`：底部 `音频 (1)` 行里有一条 `debug` 片段，
时间轴其余轨道正常。`CompTimelineZoomTest.audioLengthFallsBackToTheCompositionEnd` 钉住
"显式时长优先 / 无时长到合成末尾 / 越过末尾为 0"。

---

## 3. 视频没有轨道

### 3.1 断言

> "导入一个 mp4，时间轴上必须出现一条能播放的视频轨道，而且导出时用的是同一批帧。"

### 3.2 根因

仓库里根本没有"导入视频"这条路：`image` 图层只认静态图，`footage` 图层**已经**支持
`frame_%04d.png` 按 `sourceTime × fps` 取帧，但没有任何代码把视频变成帧序列。于是"视频"在这个
插件里不是一个可编辑对象。

### 3.3 修复：把导入做成一次**转换**（`CompVideoImport`）

第一性原理：合成器的帧函数是 `(document, frame) → image`，它必须能任意顺序、任意次数地取第 N 帧。
流式解码给不了这个保证，**编号帧序列可以**，而消费方（`footage`）已经在。

流程（worker 线程，最长 60 秒）：

```
ffmpeg -hide_banner -i clip.mp4                       # 探测尺寸
ffmpeg -y -i clip.mp4 -vf fps=<合成帧率>,scale=<w>:<h> \
       -frames:v <fps×60> -start_number 0 assets/frames/<name>/frame_%04d.png
```

* 尺寸按**信框**缩放进合成（不裁剪：裁掉的画面用户拿不回来，黑边可以通过缩放图层去掉）；
* 宽高强制偶数（H.264/PNG 都更安分），文件名冲突自动加 `_2`；
* 完成后在**客户端线程**建 `footage` 图层：`imagePath = assets/frames/<name>/frame_%04d.png`、
  宽高 = 提取尺寸、入点 0、出点 = `min(合成时长, 帧数/帧率)`、选中它；
* 如果这期间工程被切走，只提示不落地（宁可取消，也不要往别人的工程里塞东西）。
* 入口：素材页 `导入视频…`（mp4/mov/mkv/webm/avi/m4v）。

### 3.4 验证

* 拆帧命令用同一个 FFmpeg 手工跑过：2 秒 640×360@30 的测试片 → `fps=24,scale=480:270` → **48 帧**
  `frame_0000.png…`，与 `footage` 的取帧规则一致。
* 实机（`-PbbsaeVideo=clip.mp4`）：日志
  `video import: 120 frames @ 60 fps -> out 2.0s (640x360)`，
  落盘 `assets/frames/clip_2/frame_%04d.png`（`clip` 已存在 → 自动加 `_2`），
  工程里生成 `footage` 图层 `in 0 / out 2.0 / 640×360`。

---

## 4. 顺带修掉的两个 UI bug

### 4.1 窄面板里预览被挤成 0

`applyLayout()` 先独立地把左右两栏各钳到 `area.w - 300`，于是 `w ≈ 360` 时
`预览宽 = 360 - 160 - 160 - 12 < 0`——预览元素宽度为负、直接不画，用户看到"面板空了一半"。
现在第二次钳制：按比例缩两栏，保证预览至少 160px。

### 4.2 AE 模式下三个"点了没反应"的开关

效果 / 蒙版 / BBS 底图在 AE 模式下不参与成片（帧服务器总是渲染效果与蒙版，舞台永远是合成背景色）。
它们现在在 AE 模式下隐藏，而不是留在那里让用户点进一个 no-op。

---

## 5. 这一轮的共同模式

三个 bug 是同一类错误的三个实例：

| bug | 错误的假设 | 真值 |
| --- | --- | --- |
| 轨道缩放 | "zoom 参数是倍数/步数" | BBS 的 `zoom` 是**加法**，单位 px/s |
| 音频没轨道 | "时间轴的行 = 图层" | 行是**任何有入出点的东西**，音频也是 |
| 视频没轨道 | "footage 图层存在就等于能导入视频" | 缺的是**导入转换**这一步 |

修法也一样：先把断言写进测试（缩放用真 `Scale`、音频长度用真 `CompAudio`），
再让代码满足它；UI 的事实（行数、可见性、按钮）尽量收敛到一个函数（`rowCount()`、
`refreshEncoderState()`），而不是散在十几个地方各算一遍。

## 6. 产物

- `bbsae-0.10.0.jar` 623996 字节
- SHA-256 `213a21e0849a733d69c915a56df37302f2aac00c0310e1c22f745aea3f756218`
- `test` **164/164**（本轮 +8：缩放 7、音频长度 1；另修了 `Scale` 语义测试）
- 截图：`docs/verify/round21-audio-track.png`
