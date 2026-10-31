# Round 22 — 3D 视口：把摄像机从"移动镜头才能看"改成 Blender 式自由视图

> 用户的原话是三条：**进入 3D 空间要像 Blender 一样能随意拖动缩放**、**要有摄像机位置显示**、
> **一切摄像机功能都正常**；以及一句判断：**目前性能十分卡顿，根本用不了**。
> 本轮先把它拆成可证伪的断言，再改代码。

产物：`bbsae-0.11.0.jar`（671 320 字节，SHA-256
`84103a0d6f8f5da5416596a531214001a9869a90de92ffa37ada801c44b787fa`）。测试 **174/174**。

---

## 1. 第一性原理：为什么旧 3D 空间必然卡

先看旧实现做了什么（`ROUND13` 之后的版本）：

* "进入 3D 空间" = 把 `cameraPreviewMode` 设成 true，让预览按合成摄像机渲染；
* 导航 = 改**合成摄像机**的位置/距离（拖本体平移、滚轮推拉）。

于是有两个不可分割的后果：

1. **编辑器没有自己的视点**。你只能通过"移动成片镜头"来换个角度看，这不是 3D 视口，
   而是"带透视的取景器"。想要 Blender 那种绕着一个点转，就只能改镜头 —— 但那是作品本身。
2. **每一次鼠标移动都要重渲整条渲染图**。`CompCamera.of()` 读的是文档里的摄像机；摄像机一动，
   `CompRenderCache.signature` 变，整条图（每层一个全合成尺寸 FBO + 每个效果 2~5 个全屏 pass +
   蒙版 + unpremultiply + 混合）全部重跑。拖动 60 次/秒 = 每秒 60 次整图重渲。
   这不是"优化不够"，是**架构把不该耦的东西耦在一起了**。

把摄像机拆开看，它只回答两个不同的问题：

| 问题 | 归属 | 是否进成片 |
| --- | --- | --- |
| 这一帧镜头看到什么？ | `CompCamera`（合成摄像机） | 是 |
| 我现在站在哪里看这个场景？ | `CompViewportCamera`（编辑器视口） | 否 |

Blender 的 numpad 0 / 自由视图就是这条分界线。把它们合成一个摄像机，既拿不到自由视图，
又让每次导航都变成一次重新出片。

**第二条第一性原理**：摄像机移动只改变图层**落在屏幕的哪里**，不改变图层**是什么**。
所以正确的最小工作单元是：

```
源纹理（图层本地像素，随内容变） × 投影四边形（随摄像机变）
```

而旧渲染器把投影烘进了图层 FBO —— 摄像机一动，图层的**像素本身**就作废了。这就是卡顿的根。

---

## 2. 新结构

```
CompViewportCamera   编辑器视口：target + yaw/pitch/distance + fov + 正交/透视
        │            orbit / pan / zoomAt / frame / snap / project / unproject(Plane)
        ▼
CompViewportRenderer 草稿渲染：舞台 + 地面网格 + 图层四边形（按深度排序）+ 调试统计
        │            渲染到自己的 FBO，再用 Batcher2D 贴回面板（见 §5）
        ▼
CompLayerSourceCache 图层源纹理：按"内容指纹"缓存，与摄像机、位移、旋转无关
```

### 2.1 为什么源纹理能省掉重渲

`CompLayerSourceCache` 把每层栅格化到**图层本地坐标**（锚点 0,0 的矩形），
FBO 最长边 1024 px，48 MB 预算，LRU。指纹只包含**会改变像素的字段**：
文字、字号、颜色、图片路径、形状列表、文字动画器、当前帧（footage/动画）……
**不含** x/y/rotation/scale/opacity/blendMode —— 这些由四边形那一步施加。

所以：

* 拖图层：0 次栅格化，只有变换；
* 转视口：0 次栅格化、0 次整图渲染；
* 改文字内容：1 层栅格化；
* 拖动摄像机：0 次整图渲染。

### 2.2 透视没有用 shader

每层按屏幕尺寸切成 1~8 格（对角线 / 110px），每格两个三角形用**投影后的四个角**，
UV 用该格在图层本地矩形里的位置。平面在透视下是单应变换，分格后的仿射近似误差远小于 1px，
而整层仍然只有一次 draw call。用"每像素射线与图层平面求交"的 shader 可以做到精确，
但那要按视口面积 × 层数着色 —— 对弱 GPU 正好是反方向。

### 2.3 混合：预乘，不是直通

`GlFramebuffer` 里的内容是用 `SRC_ALPHA/ONE_MINUS_SRC_ALPHA` 画到透明底上的，结果天然是**预乘**的。
所以四边形这一遍按预乘方式合成（RGB 与 A 都被顶点 alpha 缩放），省掉每条图层的 unpremultiply 全屏 pass，
文字/形状的抗锯齿边缘也不会发黑。

---

## 3. 交互（Blender 的键位，尽量不发明新词）

| 操作 | 结果 |
| --- | --- |
| 中键拖动 | 绕视口目标环绕（每像素 0.4°，俯仰钳 ±89.5°） |
| Shift + 中键拖动 | 平移视口目标（内容跟手） |
| 滚轮 | 朝光标缩放（缩放前后各反投影一次，光标下的点不动） |
| 数字键盘 0 / 主键盘 0 | 看穿合成摄像机（最终画面）/ 回到自由视图 |
| 数字键盘 1 / 3 / 7（Ctrl 反向） | 前 / 右 / 顶（后 / 左 / 底） |
| 数字键盘 5 | 正交 / 透视 |
| 数字键盘 . | 框住所选 |
| Ctrl + Home | 框住整个合成 |
| 拖摄像机本体 | 在过眼睛、平行于屏幕的平面上移动眼睛；目标点同步位移，朝向不变 |
| Shift + 拖摄像机本体 | 沿视线推拉 |
| 拖橙色准星 | 在目标 Z 平面上重新瞄准（自动开启"看向目标"） |
| Ctrl + 滚轮 | 推拉合成摄像机（`cameraZ`） |
| Alt + 滚轮 | 改合成摄像机视场 |
| 左键点击图层 | 选中（Ctrl 多选）；拖动在图层自身平面上移动（3D 层同时改 z） |

**位置显示**：视口左下角 HUD 常驻
`摄像机 X/Y/Z · FOV · 视图距离`，摄像机图标旁还有一行跟随的坐标标签。
摄像机图标 + 目标准星 + 视锥四条线都画在视口里，可点可拖。

**"看穿摄像机"是姿态，不是另一套数学**：`lookThrough()` 把视口目标设成摄像机的目标点、
距离设成眼到目标距离、yaw/pitch 从 forward 反解，再用与 `CompCamera` 完全相同的
`view + projection` 构造投影。测试 `lookThroughMatchesTheCompositionCamera` 断言：
同一点在"看穿视图"和"成片"里落在同一像素（< 0.1 px）。

---

## 4. 性能：这一轮实际动了几处

1. **导航不再触发成片渲染**（最大项）。自由视口走草稿路径，3 层 = 3 次贴图四边形 + 1 次贴回。
2. **线段不再按 2px 铺方块**。旧的 `line()` 每 2 像素画一个 2×2 方块：一条 700px 视锥边 350 个方块，
   地面网格每帧上万。现在一次 `MatrixStack` 旋转 + 一个四边形。
3. **视口渲染进自己的 FBO**，尺寸就是面板预览尺寸（约 1000×200 的草稿，而不是 1920×1080 全图）。
4. **源纹理按内容缓存**，拖动图层/转视口时零栅格化。

验证：`-Dbbsae.debugViewport=true` 每秒打印
`viewport: N layers, comp WxH, yaw … pitch … dist … persp [x0,y0 x1,y1]`，
最后一段是**第一个可见图层投影后的屏幕矩形** —— 用来区分"几何跑出屏幕"和"没画出来"。

---

## 5. 本轮最贵的坑：UI 里直接画 GL 会画到别人的 FBO

第一版视口把三角形直接提交到 UI 帧里（`RenderSystem.setShader` + `BufferRenderer.drawWithGlobalProgram`），
结果：**batcher 画的准星、图标、线框全对，自己画的四边形和网格全不见**。

探针（`-Dbbsae.debugViewport=true` 打印 `fbo=`）给出答案：

```
viewport draw: layer=bbs_preview empty=false sample=342,68 fbo=1 scissor=true cells=2 error=0x0
```

`fbo=1` —— 当时绑定的不是屏幕。BBS 的 UI 帧里 FBO 绑定由面板/后处理决定，
"直接画"等于把像素画进一个随后不被贴回来的目标。**结论：视口必须渲染到自己的 FBO，
再用 `Batcher2D.texturedBox` 贴回面板**，和合成预览走同一条"一帧如何到达面板"的路。
这一条也解释了为什么"画不出来但没有任何 GL 错误"。

第二条坑：`CompLayerSourceCache` 栅格化时必须把 **batcher 的矩阵栈**（不只是 RenderSystem 的 modelview）
重置成单位阵，否则图层内容会被面板的平移再平移一次。

---

## 6. 草稿契约（写进 UI，不藏在文档里）

自由视口是**草稿**，HUD 第一行常驻 `3D 空间 · 草稿（效果/蒙版跳过）`：

| 不参与草稿 | 原因 |
| --- | --- |
| 效果、样式、蒙版、轨道遮罩、混合模式 | 它们是对"已合成画面"的操作，不是源像素 |
| 预合成内容 | 会递归整条渲染图；草稿画一块带标签的板子 |

判断镜头请按 **0 看穿摄像机**：那条路径仍然是原来的完整渲染图（含效果/蒙版），
且只有它会被导出。

---

## 7. 已知限制 / 下一轮

* 草稿不显示效果（见上）。需要"带效果的草稿"就得把效果改成在图层本地空间执行，
  那是效果管线的一次真正重构，不能顺手做。
* 图层在视口里拖动只改 x/y/（3D 层的）z；没有 Blender 的轴向约束手柄（G/X/Y/Z）。
* 摄像机没有"本地轴向手柄"，位移靠拖本体 + Shift 推拉；精确值仍然在检查器里输。
* 视口姿态按工程保存（`settings.viewport*`），但每个合成共用一份，不按合成分别记。
* 2D 图层在视口里是 z=0 的平面（这是它的定义）；要它参与透视就打开图层的 3D。

---

## 8. 验证

```
JAVA_HOME=<root>/_tools/jdk21/jdk21.0.12_12
GRADLE_USER_HOME=<root>/.gradle-home
TEMP/TMP=C:/bbsae-tmp
JAVA_TOOL_OPTIONS=-Dfile.encoding=GBK -Dsun.jnu.encoding=GBK
gradle.bat test --offline --console=plain             → 174/174
gradle.bat runClientView -PbbsaeView=camera           → 自由视口（截图 docs/verify/round22-viewport.png）
gradle.bat runClientView -PbbsaeView=cameralook       → 看穿摄像机（截图 docs/verify/round22-cameralook.png）
```

新增断言（`CompViewportCameraTest`，11 条）：

* 眼在目标正前方、`forward = -Z`、右/上轴与合成空间一致；
* 环绕保持距离；俯仰钳制后基仍然正交归一（不出现 NaN）；
* 平移让内容精确跟手（±0.5px）；`zoomAt` 让光标下的点不动；
* 正交在目标平面上与透视**同尺寸**，且深度不再改变投影位置；
* `unproject(project(p)) == p`；
* `frame()` 后四个角都在视口内（任意 yaw/pitch）；
* 数字键盘 1/3/7 的朝向；
* 看穿摄像机与 `CompCamera` 逐点一致（< 0.1px）。

截图：

* `docs/verify/round22-viewport.png` —— 自由视口：网格、透视中的图层平面（BBS 预览层 + 卡片）、
  摄像机图标/准星、左下角位置 HUD。
* `docs/verify/round22-cameralook.png` —— 看穿摄像机：最终画面 + 红色取景框。

## 9. 产物

- `bbsae-0.11.0.jar` 671320 字节
- SHA-256 `84103a0d6f8f5da5416596a531214001a9869a90de92ffa37ada801c44b787fa`
- `test` **174/174**
- 验证截图：`docs/verify/round22-viewport.png`（自由视口）、`docs/verify/round22-cameralook.png`（看穿摄像机）
