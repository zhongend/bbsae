# Round 59 — 回归审计记录（进行中）

> 本文件记录新增代码触发的真实回归、已做的防护和最终发布前仍需验证的项目。

## 1. 新增效果触发的回归（已修复）

新增 `extrude3d` 后：

- `EffectCoverageTest.everyRegisteredEffectHasARenderer` 失败 → 在
  `EffectRenderer.SUPPORTED` 注册；`apply` 对它是 no-op，真正的几何处理在
  `CompRenderer.renderBaked3D`；
- `EffectThumbnailResourceTest` 失败 → 新增 80×80
  `assets/bbsae/textures/effects/extrude3d.png`；
- 新增 `ExtrudeEffectTest` 固定 id/name/category/depth/slices/shade 默认值；
- 结果：148 suites / 944 tests 全绿。

## 2. 开放路径语义变更的影响

- `MaskCache.isMatte` 不再检查 `closed`；旧测试
  `openPathIsNotAMask` / `openingAClosedPathRemovesItsMatte` /
  `openAndDegeneratePathsProduceNoTexture` 已更新。
- 两点的路径仍然不 matte，避免「第二次点击把整层抠没」的原始 bug 回归。
- `closed` 仍在 `MaskCache.hash/contentKey` 中，开/关会重建纹理；
  `MaskCacheKeyTest` 无需改动。
- 风险：绘制到第 3 个点时蒙版会立即开始填充。这是 AE 行为，但需要真机确认视觉上不干扰
  继续绘制。

## 3. 3D 重复/挤压的性能边界

- `CompViewportRenderer.drawReplicated3D` 单个复制体上限 48；grid 限制 8×8。
- `CompRenderer.renderBaked3D` 的 extrusion 上限 48 切片 × 16×16 网格 = 最多 12288 个 quad；
  只在启用 `extrude3d` 时发生。
- `ReplayActorPass` 离屏 target 在客户端停止时通过 `release()` 释放。

## 4. 光影状态卫生（需求 6）

- 新增 `GlStateGuardSrgbTest`：隐藏 GL 上下文里打开 `GL_FRAMEBUFFER_SRGB`，进入
  `GlStateGuard` 后必须关闭，退出后必须恢复。
- 合成/3D/导出已包进 `BBSRendering.renderOffscreen`；
  `renderOffscreen` 在光影 pipeline 抛异常时回退直接渲染，避免合成器崩溃。
- 正式 modpack 实测：Iris 1.7.6 + Sodium 0.5.13 + Indium + BSL v10 能加载，
  日志出现 `Using shaderpack: BSL_v10.0.zip`；从测试世界进入游戏并打开合成器后，预览正常，
  信息栏显示「光影已加载 · 光影最终帧」，截图 `docs/verify/round64-modpack-iris.png`。

## 5. 崩溃边界

- `switchWorkspace(回放)` 对 `replayWorkspace.appear()/seek` 包了 try/catch：坏影片或 BBS
  面板构建失败时回到合成工作区并显示原因。
- `disappear()` 同样保护；切回合成仍能完成。
- `CompRenderer.renderOffscreen` 在 `BBSRendering.renderOffscreen` 抛异常/缺类时回退到直接
  渲染，防止光影 pipeline 把整个合成器带崩；只记录一次警告。

## 6. 回放合成相机映射测试

- `ReplayActorPass.actorMapping` 提取为纯函数，新增 `ReplayActorMappingTest`：
  - actor 中心必须落在图层原点；
  - 100 px 高合成使用 12 px/block；
  - 世界 Y 轴翻转为合成 Y-down。
- 固定了任务 11 最容易回归的「模型位置/比例/Y 轴」数学，不需要 GL 或 film。

## 7. 仍需真机/最终审计

- BSL 下合成器预览已通过（round64 截图）；仍需在正式 modpack 里做一次光影下的导出/抠像
  逐帧确认。
- 2D entity layer 仍是 world plate，但已有 renderFlat 兜底。
- 回放左右栏仍是 BBS 原生列表/属性；模式条和主体已统一。
- AM 效果逐项像素回归未穷尽；已解决 source UV、repeat、blur 上限、distortion padding/
  smoothstep 等基础层。
- 最终需要：全量测试、导出对比、安装 jar、版本后缀、GitHub 一次大版本提交。
