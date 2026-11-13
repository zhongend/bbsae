# Round 83 — 回放预览与合成器同步 / 轨道播放编辑 / 数值面板重叠

> 版本 `0.99-hotfix41`。

## 1. 回放模型和合成器模型不一致的根因

同一个箱子在两个界面方向相反、厚度不同，不是渲染器不同，而是**映射比例和 Z 符号不同**：

| 项目 | 合成器 linked model | 回放编辑器（旧） |
| --- | --- | --- |
| 每方块像素 | `project.modelPixelsPerBlock` 或 `comp.height * 0.12`（320×180 → 21.6） | `ReplayCalibration` 固定 64 |
| Z 映射 | `scale(px, -px, +px)` | `flipZ=true` → `scale(px, -px, -px)` |
| 结果 | 正确的箱子朝向/厚度 | 绕 X 翻转 180°，尺寸约 3 倍 |

修复：

- `ReplayCalibration.DEFAULT_FLIP_Z` 改为 `false`，回放映射和合成器一样是
  `scale(px, -px, +px)`；回放 actor pass 已经禁用背面剔除，镜像 winding 不会导致破面。
- `ReplayEditSession.syncSceneScale()`：从当前 CompProject/CompDocument 读取与
  `ReplayActorPass.pixelsPerBlock` 完全相同的比例；没有工程时才退回校准属性。
- `UIReplayModePanel.bind()` 每次绑定都同步一次比例。
- 回放相机仍强制透视（`ReplayEditSession` 构造时 `camera.setOrtho(false)`），不使用正交投影。

## 2. 回放主画面显示真实合成内容

- 默认视角为 **相机视角**：`CompRenderer.renderCached` 直接把当前 bbsae 合成画面渲染到回放视口，
  能看到文字、图片、卡片、效果和 linked 回放模型，而不只是参考线。
- 自由视角/绕对象旋转直接调用**合成器的 `CompViewportRenderer`**（不是另写一套线框/模型 pass）：
  三角形、文字、图片、卡片和 linked 模型全部通过同一条合成代码渲染；回放编辑页只让回放模型
  可交互，其他元素只读。
- `ReplaySceneSpace.anchor` 对齐到对应 CompLayer，并在对齐时同步平移视角 target，BBS 原生
  gizmo/pick 和合成里的 actor 保持重合。
- 三个 BBS 预览按钮（PLANE / ORBIT / FRUSTUM）保留；不引入合成器的摄像机/钢笔工具栏。
- 独立“合成预览”窗口默认关闭；设置仍可切换关闭 / 子窗口 / 双窗口。

## 3. 轨道播放和编辑

### 3.1 播放

旧实现每帧调用 `UIFilmPanel.setCursor()`，而这个 panel 的 runner 在隐藏/暂停/重启路径中会被 BBS
重置到 0；实测 3 秒只前进约 0.14 秒。现在：

- `ReplayEditSession` 自己保存 **local playhead**，`playhead()/tick()/keyframeTick()` 都读它；
- `advancePlayback()` 只累加 local playhead，不再依赖易被外部改写的 runner；
- `seek()` 仍同步 runner（给 BBS 内部读取），但不再把 runner 当作播放时钟；
- 时间轴用 `ReplayFilmKeyframes` 覆盖 `getTick/getPlayheadTick`，直接读 local playhead，所以
  轨道播放头与 actor 播放同步；
- 播放开始/播放中把时间轴 `shiftIntoMiddle` 到当前 tick，playhead 不会跑出可见范围。

真机日志：开始 0s，60 个 client tick（3s）后 local playhead 为 **2.86s**，单调前进；
`screenshot` 顶部 timecode 为 `00:00:02:17`。

### 3.2 编辑与数值面板重叠

`UIKeyframeEditor.target(...)` 的语义是：**选中的关键帧数值工厂面板会以 target 的大小铺满
target**（`UITimelinePanel.attachPropertiesPanel`）。旧代码：

```java
editor.target(this.replayProperties);
```

于是 K pose 帧时，关键帧数值面板整块盖在右侧回放属性面板上——这就是“数据界面重叠、K pose
帧数值界面都叠在一起”的直接原因。

修复：不再把 `replayProperties` 当 target。`UIKeyframeEditor` 无 target 时使用它自带的
140px 右侧属性条（构造器已预留 `view.w(1F, -140)`），关键帧数值面板留在时间轴内部，右侧
回放属性保持独立。这样既保留 BBS 原版的关键帧编辑控件，也不会互相覆盖。

## 4. 其他同步修复

- 虚拟回放 full-bright（`ReplayRenderLight` + mixin）不再受主世界光照影响；真实影片/BBS 预览层
  不受影响。
- 回放列表只显示 bbsae 虚拟回放；真实影片回放隐藏。
- 回放页时间轴高度按 body 剩余空间分配，矮窗口下不会顶到列表/属性面板。

## 5. 验证

- 全量测试：153 套件 / 954 项 / 0 failures / 0 errors / 1 skipped。
- 回放相机视角同步合成画面截图：`docs/verify/round83-camera-sync.png`。
- 自由视角直接使用 CompViewportRenderer 的实际合成元素截图：`docs/verify/round84-free-comp.png`。
- 播放验证截图：`docs/verify/round85-playback.png`（timecode `00:00:02:17`；日志 3 秒前进
  2.86 秒）。
- 双窗口可选模式截图：`docs/verify/round82-twowindow.png`。
- 本地候选包：`bbsae-0.99-hotfix41.jar`（3,911,584 bytes，SHA-256 75E0336942CB8F1324C9EE7BB1168C140D09038D385E5224BD908ABB8A46C446）。
