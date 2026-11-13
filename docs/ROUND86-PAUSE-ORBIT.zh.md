# Round 86 — 暂停模型 / 3D 环绕 BBS 参考画面

> 版本 `0.99-hotfix44`。只加后缀，不迭代 1.0；本轮只改**回放编辑界面的预览**。

## 1. 暂停后模型消失：先证明“模型到底在哪个 FBO 里”

播放时模型正常、暂停就消失，最容易误判成“实体没了”或“缓存过期”。本轮先做逐像素读回，
把问题钉死在 GPU 合成链路上：

| 读回点 | 暂停时的结果 | 结论 |
|---|---|---|
| actor 模型目标 | `alpha=46155, rgb=70459` | 模型已经渲染出来了 |
| 通过 UI batcher 画进 content 目标 | `alpha=0, rgb=0` | 全屏 quad 一个像素都没写进去 |
| 合成帧 accumulation | `alpha=320×180×255, rgb=0` | 只剩不透明黑底 |

根因：暂停时 BBS 世界 pass 留下的投影/model-view/视口状态还没被清掉，独立模型却仍通过
`LayerContentRenderer.drawFullscreenActors` 用 UI batcher 画全屏 quad；quad 被裁掉或变换到
屏幕外，actor 目标有像素但 content 目标为空。播放时世界 pass 的状态恰好不同，所以“只有相机
运动时才显示”。

修复（第一性原理：**深度目标已经正确，就不要再用一个状态不确定的 2D batcher 去搬它**）：

1. `CompRenderer` 最终叠加不再走 UI batcher。独立模型直接
   `ReplayActorPass.renderModel` 拿到深度目标；
2. `PassRenderer.unpremultiply` 还原直通 alpha；
3. `PassRenderer.drawInto` 按 **accumulation 尺寸** 合成，而不是按模型目标尺寸
   （`modelRenderScale < 1` 时不会把整帧缩小）。

同时加固 `GlFramebuffer`：绑定时强制真实 `glViewport`、关闭 scissor/cull、恢复
`glColorMask` 和 `blendEquation(FUNC_ADD)`，避免 raw GL 状态把整帧写成“alpha 255 / RGB 0”。

`BbsPreviewBridge.entities()` 在 runner context 为空（暂停）时回退到
`UIFilmController.editorController.getEntities()`，linked 模型不再退回无动画 stub。

## 2. 3D 环绕看不到 BBS 画面

自由/绕对象视角原来只有深色渐变舞台，BBS 画面完全不可见。现在：

- 把捕获的 BBS 世界帧以 **contain + 压暗** 的方式画在自由舞台最底层；
- 上面再画网格、模型、gizmo，并标“BBS 参考画面”；
- 它只是 3D 舞台的参考背景，**不参与相机视角合成**；
- 相机视角仍然只使用合成自己的背景；只有工程显式 `previewBackground == 1` 才把世界铺到
  合成后面（即“预览图层充当整张背景”的反例约束）。

没有绑定 BBS 胶片时保持原渐变舞台，不请求世界帧。

## 3. 播放头拖动

`ReplayFilmKeyframes.moveNoKeyframes` 先把拖动写回借用的相机编辑器 cursor，回放页读的是
`ReplayEditSession` 的本地播放头，所以下一帧就弹回。覆写 `hasCursor()` 和
`moveNoKeyframes` 后，调试日志证明拖动确实进入 handler，但播放头仍然 0——真正根因是**两个
session 并存**：

- `UIReplayModePanel.build()` 创建 session 并据此构建时间轴；
- 首次 `bind()` 因 `filmChanged` 又创建第二个 session，viewport 用第二个；
- 时间轴持有第一个，拖动 seek 写旧 session，面板/指针读新 session，所以永远回到 0。

修复：首次绑定复用 `build()` 的 session；只有真正切换影片才创建新 session，并在
`selectReplay` 后重建时间轴。真机 `-PbbsaeReplayScrub=true` 修复前后：
`before=0.0 after=0.0` → `before=0.0 after=2385.5469 moved=true`。

预览帧改为 **dirty 重烘**，失败时保留上一张好帧，不再在暂停瞬间释放成黑屏。

## 4. 验证

- 测试工程背景从品红 `-65281` 改为黑 `-16777216`，排除背景色干扰。
- 全量测试：**155 套件 / 967 项 / 0 failures / 0 errors / 1 skipped**。
- 真机：
  - `docs/verify/round110-pause-paused.png`、`round112-pause-paused.png`、`round120-pause-paused.png`：
    暂停时模型可见；
  - `docs/verify/round111-orbit-free.png`：3D 自由视角的 BBS 参考画面；
  - `docs/verify/round113-pause-paused.png`：实体回退后的暂停帧。
- 播放头真机回归（`-PbbsaeReplayScrub=true`）：
  `before=0.0 after=0.0 moved=false` → `before=0.0 after=2385.5469 moved=true`。
- 调试像素日志（仅 `-Dbbsae.debugReplayPreview=true`）：
  `Replay preview model-content-*`、`model-blend-*`、`after-models`。
- 候选包：`bbsae-0.99-hotfix45.jar`（3,930,120 bytes，SHA-256
  `DCE24A6513A61AAC18C68EC64B0AF6C06C1C5884FC4C69625AF0EC349A811765`）。
