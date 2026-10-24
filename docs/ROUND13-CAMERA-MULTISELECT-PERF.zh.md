# 第十三轮：摄像机重构 / 3D 父子级 / 多选与预合成 / 性能

> 版本：**0.3.0**（上一轮 0.2.0）
> 产物：`bbsae-0.3.0.jar`（**504989** 字节，SHA-256 `7726ac1514cd2a23f0525db53cf1870677961a5c1a23a01b4bce39ea7cc86d32`）
> 回滚点：`_backup/round12-2026-10-24/`（第十二轮动工前源码）+ `bbsae-0.2.0.jar`
> 本轮结束会把当前源码归档到 `_backup/round13-final/`。

本轮四件事：**把摄像机推倒重写**、**修 3D 父子级**、**多选 + 合成为一个预渲染**、**把"十分卡顿"按第一性原理拆掉**。

---

## 1. 第一性原理：摄像机到底是什么

一个摄像机只回答一个问题：**世界点 → 屏幕像素**。它由三样东西唯一确定：

1. **眼的位置** P（comp 像素空间，Y 向下，Z 朝向观众）；
2. **朝向**：一个正交基 `(right, up, forward)`；
3. **镜头**：垂直视场角 fov、近远裁剪面。

由此推出全部：

```
focal    = (compHeight / 2) / tan(fov / 2)      // 距离为 focal 时 1 像素 = 1 comp 像素
distance = 眼睛到 z=0 平面的距离
zoom     = focal / distance                      // 2D 视图的缩放
right    = normalize((0,-1,0) × forward)         // comp 的"上"是 -Y
up       = forward × right
view     = [ right ; up ; -forward ]，平移 = -basis·P
screen   = projection · view · worldPoint
```

而 **2D 视图不是另一套相机**，它就是同一个相机在 `z = 0` 平面上的切片：

```
view2D = translate(w/2, h/2) · scale(zoom) · rotateZ(-roll) · translate(-cx, -cy)
```

`test: perspectiveAnd2DViewAgreeAtZeroDepth` 把这条写成断言：**同一个点在 2D 图层和 3D 图层上投影出来的像素完全相同（误差 < 0.1px）**。这就是"开 3D 不会跳、不会有尺寸差"的数学保证。

### 1.1 旧实现为什么"根本不能用"

| # | 根因 | 表现 |
| --- | --- | --- |
| 1 | `camera3D` 是**总开关**：`useCamera = camera != null && camera.camera3D && cameraPreview`，新建的摄像机默认 `camera3D=false` | 加了摄像机什么都没发生 → "摄像机没用" |
| 2 | 2D 视图用 `transform.scale.x` 当缩放，3D 用 `cameraZ`，两套语义 | 同一个摄像机在 2D/3D 下缩放不一致 |
| 3 | 3D 路径是**另一套矩阵**（`localWorldMatrix3D`，末尾 `scale(1,-1,1)`），2D 是 `localWorldMatrix` | 3D 父子级、选择框、拖动、命中测试各算各的 |
| 4 | UI 的摄像机拾取只在 `cameraPreviewMode` 下有效，且把"摄像机原点"当作可点区域 | 不能选中/拖动摄像机，只能输数字 |
| 5 | 投影用 `Matrix4f.transformPosition`（JOML 的**仿射**变换，不做透视除法） | 3D 投影结果是 `-230646 px` 这种荒谬值 → 画面完全错乱 |

第 5 条是本轮最关键的发现：JOML 里做透视必须用 `transformProject`。旧代码用 `transformPosition`，所有 3D 图层的坐标都被放大了一个数量级，这正是"3D 摄像机无法使用"的直接原因。

### 1.2 新实现（`CompCamera` + `CompScene`）

- **一个空间**：comp 像素，X 右 / Y 下 / Z 朝观众。图层内容的方向不再需要翻转，3D 图层与 2D 图层共用 `translate · rotateZ · rotateX · rotateY · scale`。
- **一个相机**：`CompCamera.of(comp, time, frame)` 解析当前位置/朝向/镜头；没有摄像机时返回 identity（中心、1:1、无滚转）。
- **一个场景图**：`CompScene.world()` 只算一次（每帧每层缓存）；`model()` = `view·world`（3D）或 `view2D·world`（2D）；`projection()` = 透视矩阵或 comp 正交。
- **兼容旧工程**：`cameraZ` 为 0 时回退到旧的 `z`，再回退到 `focal / scaleX`，所以老工程打开时构图与保存时一致。
- **拾取/手柄/拖动全部走同一条投影**：`CompScene.project/unproject`。屏幕点先经 `unproject` 回到 comp 空间，再做局部框命中——摄像机推近后仍然点得准。

### 1.3 摄像机能做什么（全部在预览里）

| 操作 | 结果 |
| --- | --- |
| 拖摄像机本体（画面中央的方框） | 平移；目标点跟随，朝向不变 |
| 拖橙色准星（目标点） | 瞄准（自动打开"看向目标"），用反投影求 z=目标 Z 平面上的点 |
| 滚轮 | 推拉 `cameraZ`（同时写 `z`，保证旧字段一致） |
| Alt+滚轮 | 改 FOV |
| 检查器 | 位置 X/Y、距离 Z、FOV、滚转、目标 X/Y/Z、看向目标、俯仰/偏航（自由镜头）、近远裁剪、重置 |
| 3D 空间按钮 | 只控制辅助线（地面网格 + 视锥），**不再控制摄像机是否生效** |

`camera3D` 的含义收窄为"透视 / 正交"：关掉后 3D 图层保留位移与缩放，但不再近大远小（z 仍参与遮挡排序）。

---

## 2. 3D 父子级（上一轮遗留）

第十二轮把 2D 父子级改成了换基 `local' = parentWorld⁻¹ · childWorld`，但 3D 走 `localWorldMatrix3D`（带 Y 翻转），两套矩阵无法互逆，所以 3D 子级绑定仍会错位。

本轮的前提是**只剩一套场景图**，于是 3D 父子级自动落进同一条公式。剩下的只是分解：

```
M = Rz(roll) · Rx(pitch) · Ry(yaw) · S
m12 = sin(pitch)          → pitch = asin(m12)
atan2(-m02, m22)          → yaw
atan2(-m10, m11)          → roll
|col0|, |col1|, |col2|    → scaleX, scaleY, scaleZ
```

- 位置永远精确（平移列直接取反）；
- 纯旋转+缩放精确；
- 父级同时**非均匀缩放 + 旋转**会产生剪切，而图层没有剪切通道 → 旋转/缩放取最接近值（测试写明契约：位置误差 < 0.05px）。
- 关键帧处理：位置/旋转 Z/缩放 XY 改 `Transform` key；**z / rotationX / rotationY / scaleZ 走标量通道**，由 `shiftChannel` 一并平移或缩放，所以 3D 动画在绑定后曲线形状不变。

---

## 3. 多选与批量操作

### 3.1 模型

选区是**一组图层 id**（`LinkedHashSet`，保持点击顺序），`selectedLayer` 是它的"主成员"：

- 轨道头/轨道体点击：普通=替换，**Ctrl=切换**，**Shift=区间**（从主成员到点击行，按文档顺序）；
- 预览点击：普通=替换，Ctrl=切换；拖动时**所有选中图层**一起移动（每个图层各自换算到自己的父空间）；
- `Ctrl+Shift+A` 全选，`Esc` 清空；
- 多选时右侧检查器变成一个"多选"页，只显示批量操作（避免误改单层属性）。

### 3.2 批量功能

| 功能 | 语义 |
| --- | --- |
| **合成预渲染**（Pre-compose） | 新建子合成（同尺寸/帧率），选中图层按原顺序搬入；**入点整体前移到最早的一个为 0**，关键帧/标记同步；组内的父子/遮罩保留，指向组外的被清空；在原最上层位置插入一个 precomp 图层，跨度 = 原选区并集；组外原本指向组内图层的父子/遮罩引用改指向新 precomp |
| 编组（空对象） | 在选区包围盒中心建 null，用换基绑定把所有选中图层挂上去（位置不动），此后拖 null 整体移动 |
| 对齐 | 左/水平中/右/上/垂直中/下，**按合成边界**对齐；用世界包围盒计算，再换算到各自父空间 |
| 分布 | 水平/垂直等距（≥3 个图层，按中心） |
| 显示/隐藏/锁定 | 作用于全部选中 |
| 复制/删除 | 复制后新副本成为选区；删除后选中原位置的下一个 |

对齐/分布用的是**世界包围盒**（`CompLayerOps.bounds(doc, layer)`），不是局部坐标——否则父级被移动过的子级会对不齐。

---

## 4. 性能：把"十分卡顿"拆成可测的几项

### 4.1 每帧重渲染整条渲染图（最大项）

旧实现每个显示帧都跑完整合成：每层一个 FBO、unpremultiply、蒙版、每个效果 2~5 个全屏 pass、混合……即使播放头停着、什么都没改。

新增 `CompRenderCache`：

- 对"会影响像素的一切"算一个 64 位指纹：图层字段、效果/样式/蒙版/形状/文字动画参数、绑定、**所有关键帧的 tick + contentHash + 手柄**；
- 时间按**合成帧率**量化（30fps 的合成在 120Hz 屏上只渲染 30 次/秒）；
- 命中缓存时只画一个 textured quad；
- 指纹变了才重渲染，渲染结果由缓存持有（不再归还 FBO 池）。

### 4.2 每秒全量序列化工程（第二项）

`captureSnapshot` 每 1 秒 `CompStore.snapshot(project)` —— 一个多层工程是数 MB JSON，**即使没有任何改动**。改为：

- `save()` 打脏标记；快照只在脏的时候序列化；
- 拖拽（鼠标按下 / 轨道交互 / 曲线交互）期间跳过；
- 快照栈增加**字节预算 32MB**（超出从栈底淘汰），避免几十个 MB 级字符串把 GC 拖垮。

### 4.3 GL 同步点

- `SceneDepth.capture()` 原来每帧 `glGetError()` 循环 + `glCheckFramebufferStatus`（都会强制 CPU 等 GPU）。现在只在分配/尺寸变化时检测一次；并且**只有真的存在 depth_* 效果时才抓深度**。
- `GlFramebuffer` 构造里的 `glCheckFramebufferStatus` 改为 `-Dbbsae.debugFbo=true` 时才执行。
- `SceneColor`（世界帧拷贝）只在**存在 BBS 预览图层或 BBS 背景开启**时执行，不再无条件每帧拷贝。

### 4.4 FBO 池

`MAX_IDLE` 12 → 32。低于峰值时池会**每帧销毁并重建 8MB 纹理**，这是典型的"一卡一卡"来源。

### 4.5 每帧分配与重复计算

- `CompScene` 每帧每层只算一次世界矩阵/模型矩阵/深度，渲染、排序、手柄、命中测试共用；
- 3D 网格步长 200 → 240、线段更粗、只在 3D 空间模式绘制；
- 摄像机投影不再每帧重建两次（`prepare3D` 现在只解析一次相机）。

---

## 5. 文件清单

新增：

```
src/main/java/dev/bbsae/client/render/CompCamera.java       唯一摄像机模型（解析/投影/反投影/2D 视图）
src/main/java/dev/bbsae/client/render/CompScene.java        唯一场景图（local/world/model/projection/depth）
src/main/java/dev/bbsae/client/render/CompRenderCache.java  帧指纹 + 预览帧缓存
src/main/java/dev/bbsae/comp/CompLayerOps.java              多选批量：预合成/编组/对齐/分布/复制/包围盒
src/test/java/dev/bbsae/comp/CompLayerOpsTest.java          6 个用例
docs/ROUND13-CAMERA-MULTISELECT-PERF.zh.md                  本文档
```

重写/修改：

```
CompRenderer.java     渲染图只负责"画什么、什么顺序"；投影/矩阵交给 CompScene；新增 renderCached
CompRenderContext.java  每帧场景缓存 + 相机字段
CompParenting.java    3D 分解（Rz·Rx·Ry）、3D 绑定、标量通道平移
CompLayerOps / UICompositorPanel  多选模型、批量操作、摄像机检查器、摄像机 gizmo、拖动反投影
UICompTrackTimeline.java  修饰键选择、多选高亮、批量右键菜单
SceneDepth.java       去掉每帧 GL 同步
GlFramebuffer.java    去掉构造期同步（可选调试开关）
FboPool.java          池容量 12 → 32
BbsPreviewBridge.java 世界帧拷贝门控
```

删除：

```
src/test/java/dev/bbsae/client/render/CameraViewTest.java   （合并进新的 Camera3DTest）
```

---

## 6. 验证

```
JAVA_HOME=<root>/_tools/jdk21/jdk21.0.12_12
GRADLE_USER_HOME=<root>/.gradle-home
TEMP/TMP=C:/bbsae-tmp
JAVA_TOOL_OPTIONS=-Dfile.encoding=GBK -Dsun.jnu.encoding=GBK
gradle.bat test --offline --console=plain     → BUILD SUCCESSFUL（97 个用例）
gradle.bat remapJar --offline --console=plain → build/libs/bbsae-0.3.0.jar
```

本轮新增/重写的断言：

- 相机在焦距距离、z=0 平面 **1:1**（四角 760/440/1160/640）；
- **2D 与 3D 在 z=0 的投影逐点相等**（< 0.1px）；
- 正 Z 靠近平移并放大；pan/zoom 同时驱动两种视图（距离减半 → zoom 2，中心左移 200px）；
- 滚转 90°：世界反向旋转，右侧点跑到画面上方；
- 自由镜头 yaw 只横向移动、pitch 只纵向移动；
- 2D 图层跟随平移/缩放/滚转，但**忽略倾斜**；
- 正交相机：z 不改变大小，但 z 仍参与排序；
- 场景图父子组合与手算一致；
- 3D 父子级：均匀缩放+倾斜+多级链，**整个 4×4 矩阵**绑定前后一致；非均匀缩放时位置精确；
- 多选：预合成的时间重定基/内外关系改指向、编组不改位置、对齐到合成边界、等距分布、复制独立。

---

## 7. 进游戏验收清单（按顺序）

1. 新建工程 → 添加一个 **纯色** 图层 → 添加 **摄像机**：画面不应发生任何变化（1:1）。
2. 拖摄像机本体：整个画面平移；拖橙色准星：画面转向准星；滚轮：推拉；Alt+滚轮：视场变化。
3. 把纯色图层的 **3D 开关** 打开：它仍在原位（z=0 一致），拖动它仍跟手。
4. 给该图层设 `z = 300`：它变大；`z = -300`：变小；再关掉摄像机的"透视投影"：大小复原（正交）。
5. 两层叠放，上层做父级：绑定后不应移动；移动/旋转父级，子级跟随；拖动子级仍跟手。
6. 轨道头 **Ctrl+点击** 多选两三层 → 右侧出现"多选"页 → 点 **合成预渲染**：轨道变成一条 precomp，双击进入应看到原图层且入点对齐。
7. 多选两个图层 → **编组（空对象）** → 拖 null：两个图层整体移动且相对位置不变。
8. 多选 → 对齐/分布：视觉上对齐到画布。
9. 停住播放头不动 5 秒：CPU/GPU 占用应显著低于旧版（预览每帧只画一张贴图）。
10. 播放：时间轴走一帧才重渲染一帧（30fps 合成在 60Hz 屏上不会双倍渲染）。

---

## 8. 已知限制 / 下一轮

- **2D 图层不跟随相机倾斜**（只跟随平移/缩放/滚转）。这是"2D 图层贴在屏幕上"的定义，不是 bug；需要透视就给图层开 3D。
- 父级**非均匀缩放 + 旋转**时子级旋转只能近似（位置精确）——图层没有剪切通道。
- 预合成目前固定为"同父合成尺寸"，没有 AE 的"裁剪到选区/移动所有属性"选项。
- 多选拖动的旋转/缩放按主成员计算（整组旋转需要真正的组变换，属于下一轮）。
- 预览缓存按内容指纹失效；如果将来加入外部资源（视频帧、外部纹理）需把它们纳入指纹。
- 3D 图层的内容 FBO 仍是整幅 comp 尺寸；超大合成 + 多层 3D 时显存占用偏高，下一轮可做包围盒尺寸的 FBO。
