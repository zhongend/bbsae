# Round 26 — 深度检查与缺陷修复（0.14.2）

> 本轮没有新功能。起因是环境迁移（原 B: 盘工作目录随 RAR 归档交付）后做的一次全量深度检查：
> 三个并行审查（主面板交互 / 时间轴与曲线 / 导出管线）+ 实机截图回归，共确认并修复 24 处缺陷，
> 其中 1 个 P0、7 个 P1。测试 **182/182**；实机 6 个视图截图回归全部正常。

产物：`bbsae-0.14.2.jar`（738 413 字节，SHA-256
`ed7b0f6da2f61cd9fc821b4306984430f9ff411650510d5b56e0a090f3300716`）。

---

## 1. 导出管线

### 1.1 P0 — 管道导出在 audio.wav 落盘前就启动 FFmpeg

`Session.begin()` 里 `startPipe()` 是同步的，而音频混音发生在第一个 tick 的
`prepareAudio()`。FFmpeg 启动时会打开所有 `-i`，输入 1 是还没写出来的 `audio.wav`——
首次导出一个新输出目录时进程直接退出（`No such file or directory`），随后第一帧写入抛
"FFmpeg stopped accepting frames"，任务失败；重试反而"成功"，因为读到的是上一次导出的旧混音。
修复：编码器移到 `tick()` 里 `prepareAudio()` 之后启动。带音频轨的默认预设
（review_h264、原尺寸、不保留帧 → 管道模式）从此一次通过。

### 1.2 P1 — 渲染中途抛异常不再泄漏 FFmpeg 进程

`tick()` 的 Throwable 分支只置失败不 `stopEncoder()`：还活着的 FFmpeg 永远等 stdin，
输出文件被锁，之后同名导出全部失败直到重启游戏。现在失败与 `cancel()` 同样回收进程。

### 1.3 P1 — 预览音频：混音进行中编辑片段导致永久静默

`ensureBuffer` 的守卫顺序是"key 相同就返回"+"worker 活着就返回"。K1 混音中改成 K2：
K2 入队、worker 仍活着 → 直接返回；K1 worker 完成时 ticket 失配丢弃结果——**K2 永远没有
worker**，之后每次调用都在"key 相同"处短路。修复：key 变化直接换新 worker（旧 worker 的
结果本来就会被 ticket 丢弃）。

### 1.4 P1 — 循环片段 offset 越界炸掉整条混音

`clip.offset ≥ 解码长度` 且 `loop` 时，`loopLength = max(1, 负数) = 1`，取模后索引仍越界 →
AIOOBE → `prepareAudio` 整个 catch → **导出无声**，只有一行日志。修复：越界即跳过该片段；
循环取模保证落在文件内。另加 `MAX_MIX_SECONDS`（30 分钟）上限：超长区间截断并写日志，
不再可能分配数 GB 的 float[]（也不再用负数 total 静默返回空）。

### 1.5 P2 — 管道背压不再阻塞渲染线程

1080p RGBA 一帧 8.3 MB，进程间管道缓冲 ~64 KB：直接 `write()` 每帧都要等 FFmpeg 编完，
22 ms 帧预算变成每帧卡顿。改为专用写线程 + 3 帧有界队列：渲染线程只在真的领先编码器 3 帧
时才等待（这正是需要的背压）。`encode()` 用 EOF 哨兵 + join 收尾，`stopEncoder()` 中断 +
销毁进程。

### 1.6 其余

- `pollEncoder`/`encode`/`startPipe` 三处扩展名推导统一为 `outputExtension(preset)`
  （手写 JSON 的 container=mov/extension="" 曾报 `name.mp4`，实际是 `.mov`）。
- `CompPrecache`：签名变化清目录时，写线程队列里最多 32 个旧签名任务会把旧像素写回**新签名**
  的目录（重启后当作有效缓存读出）。加 generation 计数，落盘前校验，换代即丢弃。
- `NativeFileDialog`：已有对话框打开时第二个导入点击从"无回调"改为按契约触发
  `onUnavailable`；AWT 后端的对话框生命周期挪进 EDT（Windows 走 Win32 路径不受影响）。

## 2. 编辑器交互

### 2.1 P1 — 数值编辑现在可撤销、必落盘

检查器的数值行从不调用 `save()`：`documentDirty` 恒为 false → 撤销采样器认为"没变"
（Ctrl+Z 要么"没有可撤销的操作"，要么回退到更早的快照并吞掉刚输入的值）；自动保存关闭时
输入的值永远不会写到工程文件。修复在源头：`setScalar` / `setTransformComponent` /
`setOpacity` 以及 `numberRow` / `animatedNumberRow` / `textRow` / `colorRow` 四个共享行
控件统一在回调末尾 `save()`（三个字段写 + markDirty，磁盘写仍走防抖）。

### 2.2 P1 — selectedIds 与 selectedLayer 不再分叉

批量操作（删除/复制/对齐/预合成门）只认 `selectedIds`，而 Ctrl+K、Ctrl+D、新建图层、
进出预合成、切换工程/回放、撤销恢复等 ~15 处只写 `selectedLayer`。新增
`setSingleSelection()` 统一写入，`refreshLayers()` 每次修剪指向当前合成不存在的 id
（并在集合空而主选中存在时回填）。进入预合成不再残留父合成的"多选"状态。

### 2.3 P1 — 曲线编辑器：框选与 Ctrl+A 尊重 solo

`soloActive` 时框选/Ctrl+A 只选**画出来的**通道。之前看不见的关键帧会进选择集，
Delete 静默删除用户根本看不到的键（数据丢失）。

### 2.4 P1 — 速度图表拖动基准修正

按下快照原先是属性值，速度图表里它被当作切线速度：抓住一个 90° 的关键帧拖 3 像素，
切线速度瞬间跳到 ~90/s。快照改为 `keySpeed(...)`（显示值）。

### 2.5 其余

- 轨道时间轴滚动条总高改用 `rowCount()`（含虚拟音频行）：只有音频在折叠区之下时不再出现
  "导入的音频无影无踪也没有滚动条"。
- 拆分（播放头不在片段上）与空选复制在 `beginAction` **之前**返回：不再压入无效撤销检查点、
  不再清空重做栈。
- 曲线通道列表滚轮加上内容高度上限（原来可以滚到面板全空）。
- 检查器音频行的选中改走 `selectAudioClip()`：图层选中被清掉，Delete 不会删掉时间轴上
  高亮着的图层。
- 快捷面板读数：同层同帧但数值变了（gizmo 拖动/撤销）也会刷新，左键按住时不打扰输入。
- 导入浏览器拖拽滚动：按下后若在按钮上松开（BBS 的事件在此被消费），滚动不再"粘"在鼠标上
  （轮询物理按键，与视口同款方案）。
- 引导页 Enter 补 `KeyAction.PRESSED`（加载失败路径上曾双触发重复报错）。
- 引导页卡片缩到下限时右栏改为按比例收缩，"新建工程"表单不再算出负宽度。

## 3. 渲染

- `ReplayEntityMask` / `SceneDepth` 的深度纹理用 `GL_CLAMP_TO_EDGE` 替换
  `GL_CLAMP`：MC 1.20.1 是 core profile 3.2，`GL_CLAMP` 已被移除，每次创建目标都产生两条
  `GL_INVALID_ENUM`（行为上碰巧无害——默认就是边缘裁剪——但每次都刷 GL 错误）。

## 4. 验证

- `test` **182/182**（修复前后同一套件，无回归）。
- 实机 `runClientView`：panel/curve/assets/camera/queue/entity 六视图截图回归通过
  （截图在交付时附于 `C:\bbsae-tmp\verify-*.png`）。
- 修复后 entity 视图日志中 `GL_INVALID_ENUM` 归零。
- 打包 `remapJar`，jar 内 169/169 个类与编译产物一一对应（10-31 的崩溃正是旧产物缺
  `CompExportManager` 导致的 `NoClassDefFoundError`）。

## 5. 环境备忘

- 原项目在 B: 盘（subst），交付 RAR 解压到 `D:\xz\bbsae-work\bbsae1.0 - 副本` 后重建
  `subst B:` 即可离线构建：`JAVA_HOME=_tools/jdk21/jdk21.0.12_12`，
  `GRADLE_USER_HOME=B:/.gradle-home`，`TEMP=C:\bbsae-tmp`，`gradlew test --offline`。
- 实机截图：`JAVA_TOOL_OPTIONS="-Dbbsae.debugShot=<名> -Dbbsae.debugShotTicks=<n>"`；
  注意 MC 保存的截图没有 `.png` 后缀，轮询文件时两种都查。
