# Round 124：预览放大后空白、顶栏回放工程绑定层级

> 版本 `0.99-hotfix91`。本轮未启动客户端。两个问题都从“尺寸/身份的边界条件”而不是 UI 表面定位。

## 1. 预览框放大到一定大小后画面消失

### 根因

预览的渲染目标是 **composition 尺寸 × previewScale**：

- `previewScale` 只用“像素预算”限制到 3840×2160（约 4K UHD）；
- 这个预算约束的是 **宽×高**，不是单边长度；
- 21:9 / 32:9 等长条合成可以总像素不超预算，却请求超过 4096px 的宽度：

```text
2560×1080，scale 1.75 → 4480×1890  // 总像素没超 4K 预算，宽度超常见 4096 纹理上限
3840×1080，scale 1.41 → 5414×1523
```

超过 `GL_MAX_TEXTURE_SIZE` 的颜色附件会创建出 **不完整的 FBO**，且 `GlFramebuffer` 默认不检查状态；
`renderCompToFbo` 继续渲染/清屏，最终表现为“预览放大到某个尺寸后整块内容消失”，没有异常。

同一问题也存在于 **图层源纹理**：`rasterMaxEdge` 固定 cap 4096，低端 GPU 报 2048 时同样会得到不完整 FBO。

### 修复

- 新增 `MAX_PREVIEW_EDGE = 3840`：`previewScale` 和 `outputScale` 同时按 **长边** 与像素预算取最小值；
- `renderScaleWithinTextureLimit()` + `GL_MAX_TEXTURE_SIZE`：渲染 composition 前按实际 GPU 上限再裁一次
  （2048 的 GPU 会自动降 scale，画面缩小但**可见**，而不是 FBO 不完整后空白）；
- `CompRenderer.maxTextureEdge()` 暴露给 `CompLayerSourceCache.rasterMaxEdge()`，图层光栅同样不超过实际纹理上限；
- 新增测试：21:9/32:9 预览长边 ≤3840；4096/2048 GPU 下的 scale clamp；7680 宽合成降到 0.5。

### 为什么不是“加个 try/catch”

FBO 不完整不会抛异常，`glCheckFramebufferStatus` 才是事实来源；本轮在请求阶段就不产生超限目标，
并用纯函数把“尺寸→安全 scale”钉进单元测试，避免下次预算改动重新引入。

## 2. 顶栏「回放/影片」选到回放实体而不是工程

### 根因

`openReplaySelector()` 把 **Replay 实体** 和 **Film** 放在同一层列表里：

```text
Replay · Alice
Replay · Bob
Film · my-film
```

用户的心智模型是 **影片工程 → 合成器图层**：一个影片工程里可以有多个 bbsae 合成
（`CompDocument`），它们才是进入 BBS 影片采样链路的对象。顶层直接列 replay，既看不到“工程”，
也无法在子级选择合成。

### 修复

- 顶栏标签改为 **影片工程**：`影片：<filmId>`；无影片时仍显示 `工程：<project>`；无绑定显示
  `回放/影片：-`。
- 左键选择器改成二级：
  1. `选择回放工程（BBS 影片）`：列出 `filmIds`，当前影片标 `（当前）`；
  2. `选择合成器图层（选中即绑定）`：列出该影片工程的全部 `CompProject.comps`（按索引解析，
     不靠重名匹配）；**不再把 Replay 实体当图层**。
- 选中合成后调用新的 `bindCurrentCompositionToFilm()`：
  - 为 `(project, comp)` 幂等调用 `CompositionClip.ensureForFilm(...)`；
  - 已有片段只更新，不会重复添加；
  - 这样 bbsae 的合成才真正进入 BBS 原版影片采样链路，而不是只切换了编辑视图。
  - 若合成 ID 对应某个原版 Replay，会顺带恢复该 Replay 关联；手建合成没有对应 Replay 也能绑定。
- 复用了单个 `ensureCompositionClip()` 创建入口：显式“加入影片轨道”和顶栏“绑定合成”共用同一逻辑，
  其余打开/时长路径继续只走 `updateForFilm()`，不会偷加片段。
- 跨影片选择时，先 `load` 影片数据；选中合成后才 `fill` 共享 film panel 并打开合成器；取消选择不会
  改掉当前影片。

## 验证

- `PreviewProxyTest`：长边 cap、纹理上限 clamp；
- `ReplaySelectorParsingTest`：`影片 · id（当前）` 与 `图层 · n · name` 的解析；
- 全量测试：**205 suites / 1241 tests / 0 failures / 0 errors / 1 skipped**；
- 未启动客户端，未干扰正在使用的项目；
- 构建产物：`bbsae-0.99-hotfix91.jar`。
