# Round 32 — Iris / BSL 光影与回放抠像

## 问题不是“没有启用光影”，而是采样点错了

BBS 合成器的预览背景来自 `SceneColor`：在 `WorldRenderEvents.AFTER_TRANSLUCENT` 把世界颜色
复制到自己的纹理。这个点在 **Iris 的 final pass 之前**，所以复制到的是 G-buffer 阶段的原版
画面，而不是 BSL 完成曝光、泛光、色调映射和调色后的最终画面。表现就是：游戏里明明有光影，
合成器预览却像原版渲染。

第一性原理：**预览必须采样“玩家最终看到的像素”，而不是某个中间阶段。** Iris 的
`IrisRenderingPipeline.finalizeLevelRendering()` 正好是 final pass 结束的位置。

## 修复

1. 新增可选 Mixin `IrisFinalizeMixin`，在 `finalizeLevelRendering()` 的 TAIL 调用
   `SceneColor.captureFinal()`。
2. `SceneColor` 增加第二张 `finalTexture`：早先的 `AFTER_TRANSLUCENT` 拷贝保留为无光影/首帧
   回退；有 Iris 时 `texture()` 优先返回 final 拷贝。
3. Mixin 不编译依赖 Iris。Iris 不存在时目标类找不到，`defaultRequire: 0` 让游戏继续运行。
4. 回放抠像的深度也改为优先读 Iris 自己的 `renderTargets.getDepthTexture()`。主 framebuffer
   的深度在 Iris 切换渲染目标后可能为空，会让抠像把所有实体都判成背景。
5. 回放抠像查找影片控制器时优先使用当前 `UIFilmPanel` 的原始 film id，而不是经过存储清洗的
   `project.id`；括号/空格被改成下划线曾导致 `A2(MAIN)_` 对不上 `A2_MAIN__`。

## 实测

自建隔离测试客户端（不改动用户实例）：

- Iris 1.7.6 + Sodium 0.5.13 + Indium 1.0.36。
- `Using shaderpack: BSL_v8.4.02.2.zip`。
- 日志：`Iris final-pass capture enabled: 1920x1080 from framebuffer 1`。
- 日志：`Iris depth attachment available: texture=4 1920x1080`。
- 真实 BBS 影片 `A2(MAIN)_`（7 个回放）的抠像：`entities=1`，深度目标有 15101 个实体像素。
- 移除 Iris/Sodium 后再启动：Mixin 只输出
  `@Mixin target ... was not found bbsae.mixins.json:IrisFinalizeMixin` 警告，游戏正常进入世界，
  预览回退原版渲染（`ShaderCompat` 显示“原版”）。

用户提供的 `BSL_v10.0.zip` 在 Iris 1.7.6 下被判为 `Pack ... is not valid`，这是光影包与
Iris/游戏版本的兼容问题，不是合成器采样问题；同一环境使用 BSL 8.4.02.2 能正常加载并完成
final-pass 采样。若必须使用 BSL 10.x，请更新到支持 1.20.1 的 Iris 版本并确认 Iris 日志中
出现 `Using shaderpack`，而不是 `not valid`。

## 兼容性

- 无 Iris：`captureFinal()` 不执行，回退到原有拷贝。
- Iris 但光影关闭：`ShaderCompat.irisLoaded()` 为 false，同样回退。
- 回放抠像：Iris 深度不可用时自动回退主深度，不阻塞合成器。

## 产物

- 版本 `0.19.2`
- `bbsae-0.19.2.jar`：846584 字节
- SHA-256：`00FA8A9240A396924EFD82DD70712510C4FA0CB8C2327DF964919B568B53B077`
- `clean test`：223/223，0 failures / 0 errors
