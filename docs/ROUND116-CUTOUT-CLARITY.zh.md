# Round 116 — 回放实体抠像完整性 / 全屏清晰度

> 版本 `0.99-hotfix81`。全量：**192 套件 / 1179 项 / 0 failures / 0 errors / 1 skipped**。

## 1. 回放实体抠像：主体像穿模、不完整

### 根因

1. **演员-only 重绘的自遮挡错误**：覆盖蒙版原来的 unoccluded 目标没有深度附件，而且关闭了深度
   写入与背面剔除。所有面（包括背脸）按提交顺序 alpha 混合，背脸可以盖在正脸上；看起来就是
   模型内部穿模/缺面。
2. **姿态与世界演员不一致**：
   - `FilmEditorController.updateEntities()` 在播放时使用 `cursor + 1`，蒙版却用裸 cursor；
   - `captureActorState` 对每个演员调用都会被洋葱皮覆盖，`lastActorTransition` 可能变成 0；
   - 蒙版 apply 关键帧时又加了 `transition`，`FilmEntityRenderer` 再插值一次。
   这些都会让抠像演员比世界演员早/晚一个 tick，边缘和面被深度比较裁掉。
3. **半透明部件被推迟**：BBS 的 `FormTranslucentQueue` 在 `AFTER_ENTITIES` 仍激活，蒙版重绘的
   半透明像素被排到世界 flush，蒙版缺外层皮肤/披风/半透明盔甲。
4. **旧主路径是蒙版 RGB**：蒙版重绘的颜色来自第二个演员实例，即使 alpha 完整，内部面的颜色/
   层次也可能来自错误的面。

### 修复

- **完整抠像优先**：`ReplayEntityKey.drawCutout()` 使用**世界帧里演员自己的颜色** + **未遮挡覆盖
  alpha** 做全屏 shader 抠像。世界帧已经有正确的自遮挡、半透明排序和光照，不会再出现第二个
  演员实例的背脸/穿模。世界帧不可用时才退回演员-only 图像。
- **覆盖目标自深度**：`plainTarget` 现在带 DEPTH24；unoccluded pass 清深度、开深度测试与写入，
  背脸不再盖住正脸。occluded pass 继续使用世界深度 + polygon offset。
- **姿态对齐**：
  - `actorTick(controller)` 对 `FilmEditorController` 在 runner 运行时 `+1`；
  - `captureActorState` 每个世界帧只接受第一次演员捕获，洋葱皮不再覆盖当前姿态；
  - 蒙版只 apply 整数 tick，sub-tick 交给 `FilmEntityRenderer` 一次。
- **半透明完整性**：蒙版 pass 继续暂停 `FormTranslucentQueue`，半透明外层进入覆盖目标。

## 2. 全屏后效果/文字/图形马赛克

### 根因

`CompLayerSourceCache.rasterMaxEdge()` 在 `context.preview` 时固定返回 `MAX_EDGE=1024`。小面板
够用；但预览最大化/全屏后合成 FBO 是 1920+ 设备像素，所有 3D 图层、3D 文字、形状和烘焙效果
仍然只栅格化到 1024，再被放大到合成尺寸，正好是用户看到的“一个个都是马赛克”。2D 文字/效果
直接画在合成 FBO 上不受这个 cap 影响，所以问题集中在带 3D/烘焙路径的内容。

### 修复

- 预览 raster cap 改为跟随实际渲染目标：
  ```java
  target = max(context.renderWidth(), context.renderHeight());
  return max(MAX_EDGE, min(4096, target));
  ```
  小面板仍保留 1024 的内存/填充率保护；全屏 1920 合成按 1920 栅格化，4K 按 4K（硬上限 4096）。
- 导出/叠加路径不受影响，仍至少使用合成长边；预渲染目标仍遵守自己的 1280 目标。
- 指纹包含 raster 尺寸，低分辨率与高分辨率条目分开缓存，不会把旧的 1024 图当成全屏图复用。

## 3. 验证

新增/更新：

- `CutoutClarityContractsTest`(4)：完整 cutout 优先、unoccluded 覆盖、蒙版 tick/首捕获、预览目标。
- `EffectPipeline3DConsistencyTest`：全屏 1920 预览的 raster cap = 1920，1900 图层 scale = 1。
- `RenderMemoryBudgetTest`：小预览 1024、全屏 4K 3840、导出 3840。
- 保留 `ReplayEntityKeyPixelTest`（世界色 + 覆盖 alpha 的 GL 像素证明）。

```text
192 suites / 1179 tests / 0 failures / 0 errors / 1 skipped
```

实机建议：
1. 选中回放实体层，观察人物外层/四肢完整，旋转/移动时没有背脸盖住正脸；
2. 3D 文字/形状/效果图层在全屏预览中边缘与笔画清楚，不再像 1024 放大；
3. 暂停时抠像人物与世界演员对齐（没有重影/缺半边）；
4. 4K 合成全屏时注意显存；这档会按 4K 栅格化，必要时把合成分辨率降到 1920。
