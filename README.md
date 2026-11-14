# BBS AE（bbsae）

> Minecraft 1.20.1 / Fabric 的 **BBS FS 2.7 客户端 addon**。
> 它把 After Effects 式的合成器带进 BBS 影片编辑器：图层、关键帧、蒙版、效果、3D 摄像机、
> 预合成与视频导出，都在 BBS 原本的影片/回放工作流里完成。

当前版本：**0.99-hotfix82**。历史更新见 [`CHANGELOG.md`](CHANGELOG.md)。

## 功能

- **合成器面板**：独立的 BBS dashboard 面板，预览、时间轴、检查器、效果库、项目素材箱。
- **图层**：纯色、文字、图片/序列、形状、空对象、调整层、HUD 面板、进度条、预合成、
  BBS 预览、回放实体（深度抠像）。
- **动画**：BBS 原生关键帧通道、曲线编辑器、缓动/插值、表达式子集、动画预设。
- **效果**：58 个内置效果 + Alight Motion 参考效果库；支持图层样式、蒙版、轨道遮罩、
  混合模式，2D 与 3D 共用同一套渲染结果。
- **3D**：真实摄像机（位置/朝向/滚转/FOV）、自由 3D 视口、3D 图层投影、挤压体积。
- **预合成**：独立嵌套合成、折叠变换、自定义分辨率，复制/粘贴自动深拷贝。
- **BBS 集成**：每个影片一个工程、每个 replay 一个合成；合成器片段可放到 BBS 摄像机
  轨道，导出走 BBS 原生录像器（F6）；不会把内部虚拟回放行塞进 BBS 原版影片编辑器。
- **音频/片段**：合成音频轨、BBS 音频/视频片段 Ctrl+K 切分（与合成器片段切分连续）。
- **输出**：PNG 序列、H.264 审阅、ProRes 422 HQ / 4444 Alpha，输出模板可保存。
- **中文 UI**、可编辑快捷键、可拖拽面板、三档 UI 密度与自适应布局。

## 环境

- Minecraft **1.20.1**
- Fabric Loader **0.16.14+**、Fabric API **0.92.1+1.20.1**
- **BBS FS 2.7-1.20.1**（必需）
- Java **17+**

把 `bbsae-<version>.jar` 和 BBS 一起放进 `mods/` 即可。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/bbsae-<version>.jar`。开发环境需要把 BBS 2.7 1.20.1 的 jar 放在
`libs/bbs-2.7-1.20.1.jar`（本仓库不提交该依赖）。

## 使用入口

1. 打开 BBS 影片编辑器并选中回放；
2. 在回放属性或影片面板打开 **合成器**；
3. 编辑完成后，「合成」页可把当前合成添加到 BBS 影片轨道；
4. BBS 原生播放/F6 导出即可看到合成结果。

合成器是独立面板，不会覆盖或替换 BBS 原版影片编辑器。

## 文档

- 交接与架构：[`docs/HANDOFF.zh.md`](docs/HANDOFF.zh.md)
- 功能矩阵：[`docs/features.zh.md`](docs/features.zh.md)
- 本轮修复：[`docs/ROUND116-CUTOUT-CLARITY.zh.md`](docs/ROUND116-CUTOUT-CLARITY.zh.md)
- 时间轴适配：[`docs/ROUND115-FIT-CONTENT.zh.md`](docs/ROUND115-FIT-CONTENT.zh.md)
- 上一轮大修：[`docs/ROUND114-PRECOMP-BINDING-SPLIT.zh.md`](docs/ROUND114-PRECOMP-BINDING-SPLIT.zh.md)
- 更新记录：[`CHANGELOG.md`](CHANGELOG.md)

## 致谢

- BBS FS / McHorse 提供影片、回放、摄像机和渲染基础。
- Photon 仅作为编辑器/渲染思路研究，未包含其源码、shader 或素材，详见
  [`PHOTON-NOTICE.md`](PHOTON-NOTICE.md)。
- 赞助者与其它贡献见 [`CREDITS.md`](CREDITS.md)。

## License

MIT。
