# 第十二轮：AE 曲线编辑器 / 图层预设 / 父子级绑定修复 / 图形角与颜色

> 版本：**0.2.0**（上一轮 0.1.0）
> 产物：`bbsae-0.2.0.jar`（476800 字节，SHA-256 `1a7e7cdebaa36aee0836967971faab77628582fe2b7638f54dff5bf1768fe2bc`）
> 备份：动工前把 `src/`、`docs/*.md`、`README.md`、`build.gradle`、`gradle.properties`、旧 jar 完整复制到
> `_backup/round12-2026-10-24/`（102 个文件，2.1 MB）。

本轮四件事：**曲线编辑器**、**图层预设**、**父子级绑定错位修复**、**图形角半径与颜色**。
所有改动都遵守原有硬约束：合成器仍是独立 dashboard panel，运行时 UI 全部 BBS 原生控件 +
少量自绘区域，没有 HTML/MCEF，没有第二套关键帧系统。

---

## 1. 第一性原理：曲线是什么，绑定是什么

这一轮的两个"新功能"和一个"bug"其实都源于同一个问题：**我们此前把变换存成了"一个 Transform 通道"，
而 AE 的模型是"每个属性一条曲线"**。

- **曲线**的第一性原理：一条动画曲线 = 时间 × 值 的二维函数，每个关键帧拥有两条切线手柄。
  AE 的"值图表"画的是 `v(t)`，其贝塞尔控制点是 `(t0 + rx, v0 + ry)` 与 `(t1 - lx, v1 + ly)`；
  "速度图表"画的是 `dv/dt`，同一组手柄的另一种读数（影响 = `rx / 段长`，速度 = `ry/rx × fps`）。
- **父子级**的第一性原理：父级贡献的是一个**矩阵**，子级坐标是父子空间中的坐标。
  绑定 = **换基**，不是"填一个 parentId 字段"。正确公式只有一条：

  ```
  local' = parentWorld⁻¹ · childWorld
  ```

  位置之所以"错开"，是因为旧代码只写了 `parentId`，子级的世界矩阵瞬间从 `M_child`
  变成 `M_parent · M_child`——等于把父级的位移/旋转/缩放又叠加了一次。
- **图形的角**：角半径是**沿两条相邻边各裁剪 r 像素**后的连接方式（圆弧或切角），
  与"整体圆角"不是一回事；颜色是**每个形状自己的填充/描边**，不是整层一个颜色。

---

## 2. 曲线编辑器

### 2.1 为什么不能直接用 BBS 的插值

BBS 的 `Transform.lerp` 把同一个 `IInterp` 套在 translate/scale/rotate 三个向量上，
`Interpolations.BEZIER` 走的是 `Lerps.cubicHermite(a0,a,b,b0,x)`——**完全不读 `lx/ly/rx/ry`**。
只有 `FloatKeyframeFactory` 的 `BEZIER` 分支会调用
`BezierUtils.get(aValue,bValue,aTick,bTick,a.rx,a.ry,b.lx,b.ly,x)`。

所以位置/旋转/缩放的曲线如果照旧走 `channel.interpolate()`，手柄画得再准也不会影响画面。
解决办法不是改 BBS，而是**在 `CompLayer.transformAt()` 里按分量求值**：

```
transformAt(frame):
  translate = (curveValue(AXIS_X), curveValue(AXIS_Y), 0)
  scale     = (curveValue(AXIS_SCALE_X), curveValue(AXIS_SCALE_Y), 1)
  rotate.z  = curveValue(AXIS_ROTATION)
```

`curveValue` 在 `CompCurves` 里，逐分量复刻 BBS 的语义：

| 插值 | 求值 |
| --- | --- |
| `CONST` | 保持 a 的值 |
| `BEZIER` | `BezierUtils.get(...)`（与浮点通道完全同一套手柄数学） |
| `AUTO` / `AUTO_CLAMPED` | `AutoBezier.get(...)`（Blender 式自动切线） |
| `CUBIC` | `Lerps.cubic(preA + v1, a, b, postB + v2, x)` |
| 其它（sine/elastic/bounce/…） | `interp.interpolate(IInterp.context.set(...))` |

段查找用**二分**（`transformValue`），因为 `transformAt` 每层每帧要被调用多次。
另外**必须返回新的 `Transform`**：`TransformKeyframeFactory` 内部复用一个 `Transform i` 实例，
原地修改会把别的图层/别的帧一起改掉（本轮新增回归测试 `transformAtDoesNotLeakTheFactorysSharedInstance`）。

### 2.2 数据适配层 `CompCurveChannel`

曲线 UI 不应该关心"这个值在 Transform 里还是在 float 通道里"。`CompCurveChannel` 把三种存储
（transform 分量 / opacity / 任意标量路径）统一成"一条有 keys 的曲线"：

- 5 条变换曲线总是存在：`transform.x / y / rotation / scaleX / scaleY`（AE 属性顺序、AE 红绿配色）
- `opacity`
- 所有已经有 key 的标量通道（`fx/<id>/<param>`、`mask/...`、`shape/...`、`text/...`、`timeRemap`），
  名字会反查成"辉光 · 半径""蒙版 m1 · 羽化"这种可读标签
- 写回：变换分量写回 `Transform` 并 `channel.sort()`；同时把 `layer.x/y/rotation/scaleX/scaleY`
  同步成同一个值，删掉所有 key 时不会跳回去

### 2.3 UI（`UICompCurveEditor`）

时间轴区顶部新增 **时间轴 / 曲线** 两个页签（`UICompTabBar`）。切到曲线后：

- 左侧 150px 通道列表：分组（变换 / 属性 / 效果 / 蒙版 / 形状 / 文字）、色块、可点选/折叠；
  Ctrl+点击隐藏/显示某条曲线
- 右侧图表：秒刻度尺（复用 `CompTimelineRuler`）、时间网格 + 数值网格、零线、播放头
- 关键帧图形按插值类型区分（与 AE 一致）：**方块 = 线性、圆点 = 贝塞尔/自动、菱形 = 定格**
- 选中关键帧后显示两条手柄；拖动手柄改切线，**Alt 断开对称**（默认镜像）
- 工具条：`值图表/速度图表`、`线性 / 贝塞尔 / 缓动 / 缓入 / 缓出 / 定格 / 自动 / 连续`、
  影响（默认 33.3%）、适配、吸附、加键、删键
- 交互：
  - 单击选键（Shift 加选）、框选（Shift+拖空白）、`Ctrl+A` 全选、`Delete` 删除
  - 拖键改时间+值；吸附开启时时间按帧取整
  - 双击空白 = 在该位置加键（值图表加值，速度图表加切线速度）
  - 滚轮缩放时间、Shift+滚轮平移、Ctrl+滚轮缩放数值轴、中键/空格+拖 平移
  - 顶部刻度尺拖动 = 拖播放头
- 每次手势 `beginCurveAction()` 打一次撤销检查点，结束时 `curveChanged()` 刷新关键帧抽屉、
  检查器和工程文件（曲线编辑器与 BBS dope sheet 编辑的是同一批 `KeyframeChannel`，没有第二份数据）

### 2.4 缓动预设的数学

`CompCurves.applyEase(channel, keys, side, influence)`：

- 出向：`a.rx = 段长 × influence`，`a.ry = 0`，`a.interp = BEZIER`
- 入向：`b.lx = 段长 × influence`，`b.ly = 0`，前一个 key 的 `interp = BEZIER`
- `EASE_IN` 只做入向、`EASE_OUT` 只做出向、`EASE_BOTH` 两边都做
- 线性 = 手柄复位 + `LINEAR`；定格 = `CONST`；自动 = `AUTO_CLAMPED`；
  连续 = 按相邻关键帧斜率生成两侧切线（AE 的 Continuous Bezier），手柄长度取 1/3 段长

因为 `BezierUtils` 把 `ry/h` 当作单位化控制点 y，`ry = 0` 正好等价于"切线水平"，
这就是 AE Easy Ease 的 33.3% / 0 速度。

---

## 3. 图层预设

### 3.1 模型

- `CompPreset`：`id / name / kind / description / builtin / accent / data(JSON)`
- `CompPresetPayload`：真正被复制的内容
  - `transformData / opacityData / channels`（动画）
  - `effects / styles / shapes`（效果栈、图层样式、形状组）
  - `layerType / color / strokeColor / strokeWidth`（整套预设的外观）
  - `sourceIn / sourceFps`（应用时按目标图层入点重新对时，和 AE 动画预设一致）
- 五种 kind：`motion / effect / style / shape / layer`
- 应用语义：
  - 效果/形状**总是追加**，并生成不冲突的新 id；
    原本挂在 `fx/<旧id>/...`、`shape/<旧id>/...` 的关键帧通道会**同步改名**（否则动画就丢了）
  - 动画：`transformData/opacityData` 覆盖，标量通道按路径合并；随后按
    `(target.in - sourceIn) × fps` 平移所有 key
  - `layer`：以上全部 + 填充/描边色

### 3.2 内置预设（`CompPresetLibrary`）

内置预设和用户预设走**同一条** apply 代码路径：先在内存里构造 payload，再序列化一次。

| 名称 | 类型 | 内容 |
| --- | --- | --- |
| 电影青橙 | 效果 | color_grade + curves + vignette + grain |
| 柔光人像 | 效果 | glow + brightness_contrast + hue_saturation |
| 霓虹发光 | 效果 | glow + outer_glow + color_grade |
| 复古 VHS | 效果 | vhs + scanlines + color_grade |
| 故障闪断 | 效果 | glitch + scanlines |
| 卡片投影 | 效果 | drop_shadow + stroke |
| 外发光 + 白描边 | 样式 | outer_glow + stroke |
| 圆角卡片 | 形状 | 480×280 矩形，四角 28px |
| 三角徽章 | 形状 | 三角形，三角各 10px |
| 圆环 | 形状 | 两个椭圆相减 |

### 3.3 UI

左栏新增第三个页签 **预设**（`UICompPresetBrowser`）：

- 搜索框（按名称/描述过滤）
- 五个"存为…"按钮：动画 / 效果 / 样式 / 形状 / 整套（保存当前选中轨道）
- 内置预设只读；工程预设可 `×` 删除
- 点击应用；应用后刷新关键帧抽屉、检查器和工程文件
- 用户预设存在 `CompProject.presets`，随工程文件走（`CompProject.VERSION` 2 → 3，旧工程自动补空列表）

---

## 4. 父子级绑定错位（根因与修复）

### 4.1 根因

旧实现只有两行：

```java
layer.parentId = ids.getOrDefault(value, "");
```

渲染端是 `world = parentWorld · local`。于是子级的世界变换瞬间多乘了一个父级矩阵：

- 父级在 (200,100) → 子级整体平移 (+200,+100)
- 父级旋转 30° → 子级绕父级原点公转
- 父级缩放 2× → 子级位置和尺寸都翻倍

这就是"绑定之后两个元素经常错开，保持不了原来的位置"。

第二个错位来源在**预览里拖动**：拖动把屏幕像素增量直接加到子级的 `x/y` 上。
子级坐标在父级空间里，父级有缩放/旋转时，拖动手感会变成"元素跑得比鼠标快/方向不对"。

### 4.2 修复

新增 `CompParenting`：

```java
bind(doc, child, parentId, frame):
    childWorld  = worldMatrix(child)          // 绑定前
    parentWorld = parentId.isEmpty() ? I : worldMatrix(parent)
    local       = parentWorld⁻¹ · childWorld  // 换基
    (x,y,rot,sx,sy) = decompose(local)
    applyDelta(child, Δx, Δy, Δrot, sxRatio, syRatio)
    child.parentId = parentId
```

- `applyDelta` 在**没有关键帧**时改基础字段；**有关键帧**时把同样的 delta 应用到每一个
  `Transform` key（缩放比例同时缩放 `ry/ly`，因为手柄是相对偏移），再 `sort()`。
  这样绑定后动画曲线形状不变，只是整体换到了父级空间。
- `decompose` 用极分解角度 `atan2(m01-m10, m00+m11)`：对"纯旋转+缩放"是精确的；
  父级同时有非均匀缩放和旋转时会产生**剪切**，而图层变换没有剪切通道——此时
  **位置仍然精确**，旋转/缩放是最接近的近似（测试里把这条契约写死了）。
- 拖动：`toLocalDelta()` 把 comp 像素增量乘上父级线性部分的逆矩阵，元素严格跟随光标。
- 解绑走同一条路径（父级 = 单位矩阵），所以"解除父级"也不跳。
- 循环/自绑定在 `bind` 里直接拒绝（返回 false，UI 提示），`chooseParent` 列表也照旧过滤。

### 4.3 仍然存在的限制

- 3D 图层（`threeD = true`）走 `localWorldMatrix3D`，其 Y 轴是反的，本轮补偿只覆盖 2D 链路；
  3D 子级绑定仍按原来的矩阵叠加（不会崩，但可能位移）。
- 父级**有动画**时，补偿以"绑定那一帧"为准（AE 同样如此）：换到别的帧，子级会带着
  父级在该帧的位移差。这是语义，不是 bug。
- `CompBinding`（跟踪绑定）写入的是绝对 comp 坐标，和父子级同时开启时会互相覆盖；
  这一点在本轮没有改（见 HANDOFF 第 8 节 P3）。

---

## 5. 图形：逐角半径 + 逐形状颜色

### 5.1 逐角半径

`CompShape` 新增：

- `List<Float> corners`：逐角半径（像素）。矩形是 左上/右上/右下/左下；
  三角形/多边形/星形按顶点顺序（从正上方开始顺时针）；路径按点序
- `int cornerStyle`：`0 = 圆角（二次贝塞尔连接）`、`1 = 切角（直线连接）`
- `cornerCount() / ensureCorners() / corner(i) / setCorner(i,v) / hasCorners()`
- 新增形状类型 `TRIANGLE`（三边多边形，但语义独立，便于 UI 直接给三个角）

`ShapeRasterizer`：

- 矩形：没有逐角半径时仍走原来的 `RoundRectangle2D`（旧工程像素级不变）；
  有逐角半径时走 `roundedPolygon`
- 多边形/星形/三角形：先生成顶点，再按每个顶点的半径沿两条邻边各裁剪 r 像素，
  然后 `quadTo(顶点)`（圆角）或 `lineTo`（切角）
- 半径被钳制到两条邻边较短者的一半，避免相邻两个角互相穿插成自交路径
- 角半径可以打关键帧：路径 `shape/<id>/c/<index>`，由 `CompLayer.shapeAt()` 逐帧解析

> 注意：`putNestedScalar`/`nestedScalar` 里的属性 switch 对未知键会 `return true`，
> 所以 `c/<index>` 的判断必须放在 switch **之前**，否则永远走不到（本轮踩过）。

### 5.2 逐形状颜色

旧实现把所有形状合并成一个 `Area`，最后只用一个填充色（"最后一个不透明形状的颜色"）——
所以多形状图层改颜色"看起来没反应"。

`ShapeCache.rasterize` 改为**按列表顺序逐形状绘制**：

| 布尔运算 | 实现 |
| --- | --- |
| 相加 | 用该形状自己的填充/渐变直接 `fill` |
| 相减 | `AlphaComposite.DstOut` 擦除已有像素 |
| 相交 | 生成遮罩后用 `DstIn` 保留交集 |
| 排除 | 两张临时图（cur−area、area−cur）用 `SrcOver` 合并 |

描边也改为"每个形状画完自己的填充后立刻画自己的描边"，颜色互不干扰。
缓存 hash 加入 `corners`/`cornerStyle`，改角半径会重新栅格化。

UI（图层页 → 形状）重写：

- 创建行：矩形 / 三角形 / 星形 / 多边形 / 贝塞尔路径
- 每个形状一个标题行：`id · 类型` + 布尔运算按钮（相加/相减/相交/排除）+ 删除
- 角半径逐行（矩形四行带方位名），另有"圆角/切角"切换与"全部归零"
- 填充颜色、渐变开关、渐变颜色 2、渐变角度、**描边颜色**、描边宽度
- 修剪/重复/路径点等原有参数保留

---

## 6. 文件清单

新增：

```
src/main/java/dev/bbsae/comp/CompCurves.java             曲线数学、缓动预设、二分求值
src/main/java/dev/bbsae/comp/CompCurveChannel.java       通道适配（transform/opacity/scalar）
src/main/java/dev/bbsae/comp/CompParenting.java          换基绑定、极分解、父空间增量
src/main/java/dev/bbsae/comp/CompPreset.java             预设模型（捕获/应用/JSON）
src/main/java/dev/bbsae/comp/CompPresetPayload.java      预设内容与 id/通道重映射
src/main/java/dev/bbsae/comp/CompPresetLibrary.java      10 个内置预设
src/main/java/dev/bbsae/client/ui/UICompCurveEditor.java 曲线编辑器（自绘图表 + 原生工具条）
src/main/java/dev/bbsae/client/ui/UICompPresetBrowser.java 预设浏览器
src/test/java/dev/bbsae/comp/CompCurvesTest.java         6 个用例
src/test/java/dev/bbsae/comp/CompParentingTest.java      8 个用例
src/test/java/dev/bbsae/comp/CompShapePresetTest.java    8 个用例
docs/ROUND12-CURVES-PRESETS-PARENTING.zh.md              本文档
```

修改：

```
CompLayer.java        transformAt 按分量求值；角半径通道；shapeAt 解析 corners
CompShape.java        TRIANGLE、corners、cornerStyle、cornerCount/ensureCorners/copy
ShapeRasterizer.java  逐角圆角/切角、三角形
ShapeCache.java       逐形状颜色 + 布尔运算的像素实现 + hash
CompProject.java      presets 列表、settings.timelinePage、VERSION 3
CompStore.java        保存/迁移补 presets
UICompositorPanel.java 时间轴/曲线页签、预设页签、绑定时换基、拖动父空间增量、形状检查器重写
gradle.properties     mod_version 0.2.0
README.md / docs/HANDOFF.zh.md / docs/features.zh.md  版本与文档
```

---

## 7. 验证

```
JAVA_HOME=<root>/_tools/jdk21/jdk21.0.12_12
GRADLE_USER_HOME=<root>/.gradle-home
TEMP/TMP=C:/bbsae-tmp
JAVA_TOOL_OPTIONS=-Dfile.encoding=GBK -Dsun.jnu.encoding=GBK
gradle.bat test --offline --console=plain      → BUILD SUCCESSFUL（88 个用例：原有 66 + 本轮新增 22）
gradle.bat remapJar --offline --console=plain  → build/libs/bbsae-0.2.0.jar
```

本轮新增的断言（都在 headless 环境跑，不需要 GL）：

- 线性采样 25/50；Easy Ease 后 1/4 点 < 24、中点仍 ≈ 50、3/4 点 > 76
- 手柄占段长 1/3 且 `ry = 0`，插值类型变成 BEZIER
- 定格：9.9 帧仍是起点值，10 帧跳到终点值
- `transformAt` 不污染工厂共享实例
- 绑定到偏移父级 / 均匀缩放+旋转父级 / 多级链 / 解绑：世界矩阵误差 < 0.05px
- 非均匀缩放+旋转父级：**位置**误差 < 0.05px（旋转为近似，测试写明契约）
- 子级动画在绑定后整条曲线平移、两端世界位置不变
- 循环/自绑定被拒绝；2× 父级下 40px 拖动 → 20 局部像素
- 矩形 4 角、三角形 3 角、角半径过大被钳制、角半径可关键帧
- 预设：重复应用产生唯一 id、圆环形状的相减、卡片四角 28px、JSON 往返保参数

> 说明：本轮没有启动 Minecraft 客户端做视觉验收（沙箱内无 GPU 窗口）。
> 曲线编辑器/预设面板的布局与交互按 `UICompTrackTimeline`、`UIEffectLibrary` 的既有约定实现，
> 首次进游戏建议按第 8 节清单走一遍。

---

## 8. 接手后第一次进游戏要看的点

1. 时间轴顶部 **曲线** 页签：切过去是否显示通道列表 + 图表；选中轨道后曲线是否立即出现
2. 选中两个位置关键帧 → 点 **缓动** → 曲线是否变成 S 形，预览里运动是否同步变缓
3. 拖手柄：默认两侧镜像，按住 Alt 只动一侧；松开后 BBS dope sheet 的插值图标是否同步
4. 双击图表空白：是否在该处加键并选中
5. 左栏 **预设** → 应用"电影青橙"到一张图片轨道：效果栈是否出现 4 个效果且参数可见
6. 绑定：把一个 (900,500) 的图层绑到 (200,100) 的 null → 画面**不应**移动；
   移动 null → 子级跟随；旋转/缩放 null → 子级围绕 null 变换
7. 拖动已绑定的子级：光标与元素是否严丝合缝（父级缩放 2× 时最明显）
8. 形状页：新建三角形 → 三个角半径分别调 → 形状是否变成圆角三角形；
   两个形状叠放改不同颜色 → 颜色是否各自生效

已知需观察的性能点：曲线编辑器每帧对每条可见曲线按 2px 采样并调用
`transformValue`（二分查找），20 条曲线 × 1000px ≈ 1 万次/帧，正常机器无压力；
若在极弱机器上感觉卡，可把采样步长从 2px 调到 3~4px。

---

## 9. 下一轮建议

1. 曲线编辑器：框选后整体平移数值（AE 的 `Alt+↑/↓`）、复制/粘贴关键帧、
   时间轴与曲线共用选区
2. 3D 图层的父子级补偿（在 `localWorldMatrix3D` 的 Y 翻转空间里做换基）
3. 预设缩略图（用 `CompRenderer.renderOffscreen` 渲一帧 64×36 缩略图缓存到工程目录）
4. 形状：逐点"角类型"（尖角/圆角/贝塞尔把手独立编辑）、虚线描边
5. 把 `CompBinding` 的绝对坐标改成"相对父级"，彻底消除与父子级的冲突
