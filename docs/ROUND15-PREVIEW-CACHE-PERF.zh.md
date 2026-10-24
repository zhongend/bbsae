# Round 15 — 预览走 BBS 原版逻辑 / 预渲染缓存 / 性能（0.5.0）

> 2026-10-24。本轮针对用户反馈的六个问题：
> 摄像机不可用、界面卡顿、预览没有深度且不跟 BBS 原版一致、
> 没有 AE 式预渲染绿条、Alt+滚轮不能缩放轨道、曲线编辑器难用。
>
> 方法：先找"物理事实"（每帧到底发生了什么），再改代码。没有加新的抽象层，
> 只把已有的错误假设换成正确的假设。

---

## 0. 一句话结论

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 界面卡顿 | `save()` 在每次滑条/滚轮/拖拽事件里 **序列化整个工程并写盘**（121 处调用） | 改成去抖写盘，一次手势一次写 |
| 界面卡顿 | 预览按 **合成分辨率** 渲染，再缩小画到面板 | 按 **面板实际像素** 渲染（预览代理） |
| 摄像机不可用 | 3D 空间的"BBS 底图"只在开关回调里请求，重开工程/一键 3D 都拿不到世界帧；滚轮方向还是反的 | 每帧从设置推导请求；滚轮方向修正 |
| 预览没有深度 | 深度只在导出路径捕获，且读的是合成器自己的 FBO（空深度） | 在世界渲染结束时同时拷 color+depth，预览用真实世界深度 |
| 预览不像 BBS | 世界帧按 cover 裁切；默认背景是纯黑 | 改成 BBS 的 contain 加黑边；新工程默认显示世界底图 |
| 没有预渲染 | 没有缓存层 | 新增 `CompPrecache`：磁盘+显存两级缓存，时间尺绿条 |
| Alt+滚轮 | 被绑到时间轴缩放，行高被挪到 Alt+Shift | Alt+滚轮 = 轨道行高；时间轴缩放 = Ctrl/Alt+Shift |
| 曲线难用 | 数值轴按 **所有可见通道** 拟合，位置(0–1080)和透明度(0–1)共用一个轴 | 数值轴只按当前通道；默认只画当前通道 |

---

## 1. 第一性原理：卡顿到底是什么

一个编辑器一帧要做的事只有三类：

1. **UI 布局与绘制**（BBS 的控件树）；
2. **预览像素**（合成图）；
3. **持久化**（工程 JSON）。

第 3 类**不应该出现在每帧路径里**，但旧代码把它放进去了：

```java
// 旧 UICompositorPanel.save()
void save() {
    if (this.project != null) CompStore.save(this.project);  // Gson 全量序列化
    this.documentDirty = true;
}
```

`CompStore.save` 做的事：`Gson.toJson(整个工程)` → 复制 `.bak` → 写 `.tmp` → 原子移动 → 失效文档缓存。
一个 20 层、带关键帧和蒙版的工程是 1–5 MB JSON。滑条拖动时每个 mouse-move 都触发一次，
就是每帧几 MB 的分配 + 一次 fsync。**这不是"渲染慢"，是磁盘 I/O 和 GC 在拖 UI。**

`save()` 有 121 个调用点，全部是"用户改了一个值"。所以修复方式不是给每个控件加防抖，
而是让 `save()` 的语义从"写盘"变成"标记脏 + 预约写盘"：

```java
void save() {
    this.documentDirty = true;
    this.saveRequested = true;
    this.saveRequestedMs = now;
    CompPrecache.markDirty();       // 绿条失效
}

// render() 里：600ms 且鼠标松开 → flushSave()
// onClose / onDisappear / 切换工程 → flushSave()
```

**一次手势 = 一次写盘**。`CompStore.save` 本身没改，它的调用者变聪明了。

第二个卡顿源是预览分辨率。旧路径：

```
CompRenderer.renderCached(... frameWidth = 面板宽, ...)
  → renderToTexture(...)                    // FBO = doc.width × doc.height (1920×1080)
  → blit 缩放到面板 (约 700×400)
```

渲染了 2.07M 像素，只显示了 0.28M。加了 10 个效果之后，每个效果都是一次全屏 pass，
浪费被放大 7 倍。第一性原理：**预览的目标像素数应该等于它显示的像素数**。

本轮给 `CompRenderContext` 加了 `scale`：

```java
public float scale = 1F;                  // 只影响 render target，不影响坐标系
public int renderWidth()  { return round(width  * scale); }
public int renderHeight() { return round(height * scale); }
```

所有 pass 都是 NDC → 当前 viewport，所以把 FBO 换成 `w×s, h×s` 后，
**坐标一个都不用改**，只是像素少了。`previewScale()` 取
`min(视口宽/合成宽, 视口高/合成高)`，钳在 `[0.2, 1]`，量化到 1/16（拖分隔条时不会每个像素重渲染）。

效果链里的模糊半径、像素单位参数都是按**合成像素**定义的，再经 UV 归一化，
所以缩小渲染目标不会改变效果外观，只改变采样精度。

---

## 2. 预渲染缓存（AE 绿条）

### 2.1 为什么需要它

播放一帧的成本 = 图层数 ×（内容 pass + 蒙版 + 遮罩 + 效果 pass + 样式 + 混合）。
这和"编辑"无关，和"重复计算同一批像素"有关。AE 的答案是磁盘缓存 + 时间尺上的绿色区间：
绿色区间内的帧不再求值，只做纹理查找。

### 2.2 设计

`dev.bbsae.client.render.CompPrecache`

- **键**：`(project, comp, frameIndex)`。
- **失效**：复用 `CompRenderCache.signature()` 的内容指纹。任何影响像素的字段变化 →
  指纹变化 → 整个缓存目录删除。不复制文档状态，缓存帧只是指纹的函数。
- **分辨率**：`min(合成, 1280×720)`，保持宽高比并取偶数。预览视口比这小，
  4K 缓存是 8 倍的磁盘换没人看的像素。
- **存储**：`config/bbsae/cache/<project>/<comp>/f%06d.rgba`，
  `Deflater(BEST_SPEED)` 压缩原始 RGBA。PNG 更小，但编码 50–100ms、解码 ~20ms，
  正好抵消缓存的意义；deflate level 1 在 1280px 上约 2ms 一次。
- **两级**：显存 LRU 16 张（约 59MB）+ 磁盘。命中显存直接 blit；
  命中磁盘时 inflate + 上传（~4ms），比重新渲染便宜一个数量级。
- **协作式任务**：每显示帧最多 2 帧、18ms 预算；单帧超预算就冷却 2 帧，
  保证编辑器自己还能画。GL 渲染不能被抢占，所以只能"下一次少做一点"。
- **写入异步**：渲染 → `readTopDown()` → 上传纹理（立即可用）→ 后台线程压缩写盘。
  绿条在**像素可用时**就变绿，不等 fsync。

### 2.3 绿条

`UICompTrackTimeline.renderPrecacheBar()`：时间尺底部 3px 的条带，
按**连续区间**合并绘制（一分钟全缓存 = 几个 quad，不是 1800 个）。
`coverage()` 返回 `BitSet`，避免对每帧构造字符串键。

按钮：时间轴工具条 **预渲染**。范围 = 工作区（若比整段短），否则整段。
按钮文字兼作进度：`停止 42%`；空闲时显示已缓存帧数。
旁边的垃圾桶清空本合成缓存。

### 2.4 正确性：绿条不能骗人

`save()` 会调用 `CompPrecache.markDirty()` —— 任何编辑都让绿条立即失效。
但 `save()` 也被"面板设置"调用（不影响像素），所以 `validate()` 在编辑静默 250ms 后
重算一次指纹：若和缓存签名一致，重新信任缓存。这样"上次预渲染过的工程重新打开"仍然有绿条。

含 `BBS 预览` 图层的合成**不允许**预渲染：那一层采样实时世界帧，缓存它等于冻结世界。
按钮会明确提示，而不是悄悄给出错误画面。

---

## 3. 预览 = BBS 原版画面 + 深度

### 3.1 旧实现的三个错误假设

1. **"BBS 底图开关只需要在回调里请求"**
   `setBackdropRequested()` 只在 toggle 回调里调用。于是：
   - 工程保存了 `previewBackground = 1`，重开后没有任何东西请求捕获 → 显示"BBS 预览不可用"；
   - 一键 3D 空间 `enter3DSpace()` 设置 `previewBackground = 1` 但用 `setValue(true)`
     （BBS 的 `UIToggle.setValue` 不触发回调）→ 同样拿不到世界帧。
   用户看到的"摄像机不可用 / 预览极差"，很大一部分是**世界底图根本没被捕获**。

   修复：`BbsPreviewBridge.syncRequests(project, comp)`，每帧从文档推导
   `worldCaptureRequested / depthRequested / backdropRequested`。三个布尔读，零成本。

2. **"深度只在导出时捕获"**
   `CompRenderer.renderToTexture` 只在 `captureDepth` 时调 `SceneDepth.capture()`，
   而 `capture()` 读的是"当前绑定的 draw framebuffer"——在合成器里那是合成器自己的 FBO，
   深度附件是空的。所以深度效果在预览里**永远**是 no-op。

   修复：世界渲染结束时（`WorldRenderEvents.LAST`）把 **color 和 depth 一起**拷贝，
   `SceneDepth.captureFrom(mc.getFramebuffer().fbo, w, h)` 显式指定源帧缓冲。
   预览渲染时 `renderToTexture` 优先使用这份世界深度；导出路径仍自己捕获。
   color 和 depth 来自同一帧、同一个 framebuffer，不可能错位。

3. **"世界帧按 cover 填满合成矩形"**
   BBS 自己的预览从不裁切影片画面，它加黑边。cover 会悄悄改变构图。
   修复：contain + 黑底，和 BBS 预览一致。

### 3.2 默认背景

新工程 / 未选择过背景的旧工程，第一次打开时迁移为 `previewBackground = 1`
（`CompSettings.previewBackgroundChosen` 一次性标记）。合成器是"在影片上叠加"的工具，
默认舞台应该是影片画面，不是黑幕。用户之后手动关掉就永久记住。

---

## 4. 摄像机

三个具体问题：

1. **滚轮方向反了**。`z + wheel * 60`：向前滚 = 远离画面 = 缩小。
   改成 `z - wheel * max(20, z*0.08)`，并按距离比例步进（近处细、远处粗）。
   Alt+滚轮改视场同理：`fov - wheel*2`（向前滚 = 视角变窄 = 推近）。
2. **Alt 检测用了 `Window.isAltPressed()`**，它走 `Screen.hasAltDown()`，
   在 BBS dashboard panel 里不可靠（`CompInput` 就是为此存在的）。改用 `CompInput.altDown()`。
3. **每个滚轮刻度重建整个检查器**（几十个控件）+ 写盘。现在检查器最多 140ms 重建一次，
   写盘走去抖。

摄像机数学本身没有改：`CompCamera` / `CompScene` 的单摄像机模型是第 13 轮验证过的
（z=0 平面 2D/3D 投影逐像素一致，有 `Camera3DTest` 钉住）。
本轮修的是"摄像机被 UI 和捕获链路挡住"，不是矩阵。

---

## 5. Alt+滚轮 = 轨道高度

旧映射（第 13 轮）：

| 手势 | 动作 |
| --- | --- |
| Alt+滚轮 | 时间轴水平缩放 |
| Alt+Shift+滚轮 | 轨道行高 |

用户要的是"Alt+滚轮缩放轨道大小"。新映射：

| 手势 | 动作 |
| --- | --- |
| **Alt+滚轮** | **轨道行高（18–62px）** |
| Ctrl+滚轮 | 时间轴缩放（锚定鼠标） |
| Alt+Shift+滚轮 | 时间轴缩放（保留旧习惯） |
| Shift+滚轮 | 水平平移 |
| 滚轮 | 垂直滚动 |

行高变化会 clamp 滚动位置，状态栏显示 `轨道高度 24px（Alt+滚轮）`。

---

## 6. 曲线编辑器

旧实现的数值轴是**所有可见通道的并集**：

```java
for (CompCurveChannel channel : this.visibleChannels()) { min/max 所有关键帧 }
```

位置是 0–1080，透明度是 0–1。同一个轴上，透明度曲线退化成贴着底边的一条直线，
所有属性的关键帧叠在一起 —— 这就是"十分邋遢"。

修复：

1. **数值轴由当前通道决定**（`soloActive` 且当前通道有关键帧时）。
2. **默认只画当前通道**的曲线、关键帧、手柄；`聚焦` 按钮可切回叠加模式。
3. **命中测试跟随可见性**：不画的通道不再抢点击（旧代码会点到隐形关键帧）。
4. **光标十字线 + 数值读数**：`0.42s  位置 X 512.3`，按当前通道的 `format()` 输出。
5. 手柄命中半径 6px → 8px，关键帧 8px → 10px。

---

## 7. 文件地图

新增：

- `src/main/java/dev/bbsae/client/render/CompPrecache.java`（约 560 行）
- `src/test/java/dev/bbsae/client/render/PreviewProxyTest.java`（7 个断言）

修改：

| 文件 | 改动 |
| --- | --- |
| `CompRenderContext` | `scale`、`renderWidth/Height()` |
| `CompRenderer` | `previewScale()`、`blitTexture()`、`renderForCache()`、预览走世界深度、`renderCached` 先查预渲染缓存 |
| `CompPrecache` | 磁盘+显存缓存、协作任务、绿条数据源 |
| `SceneDepth` | `captureFrom(fbo, w, h)` 显式源帧缓冲 |
| `BbsPreviewBridge` | `syncRequests()`、`depth()`、color/depth 同帧捕获 |
| `CompProject.CompSettings` | `previewBackgroundChosen`（一次性迁移） |
| `UICompositorPanel` | 去抖 `save()`/`flushSave()`、预览代理、摄像机滚轮、预渲染按钮、背景迁移 |
| `UICompTrackTimeline` | Alt+滚轮行高、绿条 |
| `UICompCurveEditor` | 数值轴按通道、聚焦模式、十字线读数、命中半径 |

---

## 8. 验证

- `gradle test`：**111/111** 通过（新增 `PreviewProxyTest` 7 个）。
- 构建：`gradle remapJar --offline`，产物 `bbsae-0.5.0.jar`。
- 注意：本机含中文的工程路径会让 Gradle test worker 报
  `ClassNotFoundException`。用 `subst X: "<工程目录>"` 映射成 ASCII 盘符后正常
  （junction 无效，Gradle 会解析回真实路径）。

### 8.1 手工验证清单（真实客户端）

1. 打开影片 → 合成器：预览里能看到 BBS 世界画面（不是黑底），
   时间轴拖动时世界跟着动。
2. 加一个 `深度模糊` 效果：预览里有景深，不再是无效果。
3. 拖滑条：界面不卡；松开鼠标后 `config/bbsae/projects/*.json` 才更新。
4. 时间轴工具条点 **预渲染**：时间尺底部出现绿色条带，按钮显示百分比；
   变绿后播放明显流畅。改一个图层 → 绿条立即消失。
5. Alt+滚轮在轨道区上下滚动：行高变化（不是时间轴缩放）。
6. 曲线页：默认只显示当前通道；移动鼠标有十字线和数值。
7. 摄像机：加摄像机 → 滚轮向前推近（不是拉远）；Alt+滚轮收窄视场。

---

## 9. 已知限制

- **3D 图层不会被世界几何遮挡**。合成器是在 BBS 画面之上做 2.5D 合成，
  深度只提供给 `depth_*` 效果（景深/雾/深度遮罩）。要做真正的遮挡，
  需要把世界深度写进图层 pass 的深度测试，属于渲染管线级改动，不在本轮。
- **预渲染不支持 `BBS 预览` 图层**（世界是实时的，缓存即冻结）。
- 预渲染期间编辑器仍会有轻微掉帧：单帧渲染不可抢占，只能靠冷却让步。
- 磁盘缓存不自动清理旧工程目录（换工程会新建目录）。`CompPrecache.clear()` 只清当前合成。
- 曲线编辑器仍不支持框选跨通道、复制粘贴关键帧。
