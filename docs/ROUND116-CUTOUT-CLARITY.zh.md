# Round 116 — 回放实体抠像完整性 / 全屏清晰度

> 版本 `0.99-hotfix83`。全量：**192 套件 / 1181 项 / 0 failures / 0 errors / 1 skipped**。

## 1. 回放实体抠像：主体像穿模、不完整

### 根因

1. **演员-only 重绘的自遮挡错误**：覆盖蒙版原来的 unoccluded 目标没有深度附件，而且关闭了深度
   写入与背面剔除。所有面（包括背脸）按提交顺序 alpha 混合，背脸可以盖在正脸上；看起来就是
   模型内部穿模/缺面。旧主路径又直接用这张蒙版的 RGB 当演员图像，所以错误的面色/层次直接进入
   成片。
2. **姿态与世界演员不一致**：
   - `FilmEditorController.updateEntities()` 在播放时使用 `cursor + 1`，蒙版却用裸 cursor；
   - `captureActorState` 对每个演员调用都会被洋葱皮覆盖，`lastActorTransition` 可能变成 0；
   - 蒙版 apply 关键帧时又加了 `transition`，`FilmEntityRenderer` 再插值一次。
   这些都会让抠像演员比世界演员早/晚一个 tick，边缘和面被深度比较裁掉。
3. **半透明部件被推迟**：BBS 的 `FormTranslucentQueue` 在 `AFTER_ENTITIES` 仍激活，蒙版重绘的
   半透明像素被排到世界 flush，蒙版缺外层皮肤/披风/半透明盔甲。

### 修复

- **完整抠像优先**：`ReplayEntityKey.drawCutout()` 使用**世界帧里演员自己的颜色** + **未遮挡覆盖
  alpha** 做全屏 shader 抠像。世界帧已经有正确的自遮挡、半透明排序和光照，不会再出现第二个
  演员实例的背脸/穿模。世界帧不可用时才退回演员-only 图像。
- **覆盖保持并集**：未遮挡目标继续不写自深度。它只提供 alpha；若写自深度，半透明外层会把
  身后的身体挡掉，反而在抠像里挖出身体形状的洞。
- **姿态对齐**：
  - `actorTick(controller)` 对 `FilmEditorController` 在 runner 运行时 `+1`；
  - `captureActorState` 每个世界帧只接受第一次演员捕获，洋葱皮不再覆盖当前姿态；
  - 蒙版只 apply 整数 tick，sub-tick 交给 `FilmEntityRenderer` 一次。
- **半透明完整性**：蒙版 pass 继续暂停 `FormTranslucentQueue`，半透明外层进入覆盖目标。

## 2. 全屏后效果/文字/图形马赛克

### 根因

三条分辨率链同时把画面放大，任何一条都会让全屏看起来像马赛克：

1. `CompLayerSourceCache.rasterMaxEdge()` 在 `context.preview` 时固定返回 `MAX_EDGE=1024`。小面板
   够用；预览最大化/全屏后合成 FBO 是 1920+ 设备像素，所有 3D 图层、3D 文字、形状和烘焙效果
   仍然只栅格化到 1024，再被放大到合成尺寸。
2. `CompRenderer.previewScale()` 把预览倍率硬限制在 1×。BBS 默认视频分辨率是 **1280×720**；
   在 1920 全屏上它需要 1.5×，却被渲染成 1280 再拉伸，整张合成（包括 2D 文字/效果）都是软的。
3. BBS 影片叠加/导出的 `CompositionOverlay` 把合成纹理画进 1920×1080 虚拟画布；1280 合成同样
   被拉伸 1.5×。
4. 自定义字体图集按 `fontSize` 栅格化；1.5× 预览把 54px 图集铺到 81 个合成像素上，文字发糊。

### 修复

- **面板预览超采样**：倍率上限从 1× 提到 **2×**，并以 4K UHD 像素预算封顶；1280×720 合成在
  1920 全屏按 1.5× 渲染，1920 合成在 4K 屏幕按 2× 渲染，4K 合成保持 1×。
- **图层栅格跟随目标**：预览 raster cap = 实际 `renderWidth/renderHeight`（4K 硬上限），小面板
  仍保 1024 保护；`rasterScaleFor()` 允许图层随输出倍率超采样，但不让 100px 小图层因 1920 目标
  被放大 19 倍。
- **叠加/导出按显示尺寸渲染**：`CompRenderer.render()` 用虚拟画布与合成尺寸算出 1×/1.5×/2×
  的输出倍率，BBS 影片叠加和导出不再把 1280 合成拉进 1920。
- **自定义文字超采样**：自定义字体按 `fontSize × outputScale` 请求 BBS 图集，再用
  `fontSize / fontHeight` 的原有映射缩回相同合成尺寸；tracking/描边同步换算。Minecraft 默认
  位图字体无法超采样，保持旧行为。
- **抠像**保持 hotfix82 的并集 alpha 方案，不写自深度，避免半透明外层挖洞。

## 3. 验证

新增/更新：

- `CutoutClarityContractsTest`(4)：完整 cutout 优先、unoccluded 覆盖并集、蒙版 tick/首捕获、预览目标。
- `PreviewProxyTest`：1280 合成在 1920 设备上 1.5×、1920 合成在大画布 2×、BBS 叠加 1280→1.5×。
- `EffectPipeline3DConsistencyTest`：全屏 1920 预览的 raster cap = 1920，1900 图层 scale = 1；
  超采样下 1280 图层按 1.5× 栅格化，100px 图层不超过 1.5×。
- `RenderMemoryBudgetTest`：小预览 1024、全屏 4K 3840、导出 3840。
- `TextLayoutTest` 继续覆盖布局/换行/动画文字；保留 `ReplayEntityKeyPixelTest` 的世界色 + 覆盖 alpha
  像素证明。

```text
192 suites / 1181 tests / 0 failures / 0 errors / 1 skipped
```

实机建议：
1. 选中回放实体层，观察人物外层/四肢完整，旋转/移动时没有背脸盖住正脸；
2. 3D 文字/形状/效果图层在全屏预览中边缘与笔画清楚，1280 合成不再像 1× 拉伸；
3. 暂停时抠像人物与世界演员对齐（没有重影/缺半边）；
4. 4K 合成全屏时注意显存；这档按 4K 栅格化，必要时把合成分辨率降到 1920。
