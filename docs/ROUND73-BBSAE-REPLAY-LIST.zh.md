# Round 73 — bbsae 回放工作区 / actor-only 抠像

本轮把「回放」从 BBS 原生影片回放面板改成 bbsae 自己的编辑工作区，并把抠像从
world-plate matte 换成 actor-only 图像。

## 1. bbsae 自己的回放模式

之前回放模式打开的是 `UIReplayModePanel`（BBS 原生影片回放列表/属性），依赖真实影片。

现在：

- 「合成 / 回放」切换仍在顶栏；
- 回放模式保留 bbsae 正常 dock：左栏回放列表、中间合成预览、右侧检查器、下方时间轴；
- 隐藏 BBS 原生回放面板；
- 回放 transport 驱动 bbsae 的 `time`，播放/上一帧/下一帧/时间码来自合成器；
- 进入回放自动切到左栏「回放」页；
- 进入回放时临时隔离图层：只保留 bbsae 3D 模型、文字层、摄像机，
  退出时恢复所有图层的原可见性。

真机截图：`docs/verify/round72-bbsae-replay.png`，模型、文字、相机 HUD 同时可见。

## 2. 模型可见性

- independent 模型使用每图层独立 FBO，避免共享 actor target 被下一次 bake 覆盖；
- 源缓存跳过 independent 模型层，模型每帧直接渲染；
- 合成阶段增加最终模型 overlay，模型在所有普通图层之后合成；
- 回放模式隔离装饰性卡片/背景，避免模型被上层卡片盖住；
- 新模型默认 240×240、scale 2；
- 3D 模型始终使用透视相机投影，不再因为 `layer.threeD` 的旧值回落成正交/压扁；
- 旧模型 `scaleZ=1` 且 X/Y 非 1 时按 X 补成体积，带 `scaleZ` 关键帧时以作者值为准；
- 回放模式下 gizmo 拖动结束会在播放头写入变换关键帧，并刷新轨道；
- **模型焦点/回放隔离默认关闭**：选择 3D 回放图层不再自动隐藏其他图层。
- **模型焦点**（可选，默认关闭）：开启后选中 bbsae 模型时临时隐藏装饰卡片/背景，只保留模型、
  文字、相机，普通合成预览也能直接看到模型；取消选择或关闭设置即恢复。

普通合成模型焦点真机截图：`docs/verify/round75-normal-model-focus.png`。

## 3. 回放抠像：actor-only 新方案

旧路径是「世界画面 + coverage 遮罩裁切」，反复出现背景残留和运动不同步。现在：

- coverage target 已包含 actor 自己的 RGB 和 alpha（透明区为 0，世界遮挡由深度测试剔除）；
- 新增 `ReplayEntityKey.drawActors`，直接把 actor-only 图作为图层内容；
- 不再采样世界颜色、不再乘 matte，因此背景不会被抠出来；
- 旧 world-plate matte 只作为没有 actor 图时的 fallback。

真机截图：`docs/verify/round74-actor-only-matte.png`（洋红底、只有 actor 轮廓，无世界矩形背景）。

## 4. 摄像机按钮

- 预览框「看穿摄像机」按钮隐藏；
- 摄像机工具不会在预览工具栏/右侧按钮区误启动 orbit/Track；
- 摄像机工具 3px 死区：单击不再产生零距离相机移动/幽灵撤销步骤；
- 改为「设置 → 显示 → 看穿摄像机」；
- 数字键盘 0 仍可用。

## 5. 多选提示

- 多选后自动切到检查器「图层」页；
- 预览左下角显示常驻多选提示条：「多选 N 个图层 · 预合成 / 编组 / 对齐 / 删除」；
- 状态栏提示「已多选 N 个图层 · 可预合成 / 合并 / 删除」；
- 设置 → 显示新增「上下文操作提示」开关。

## 6. 素材导入与外部链接

- 音频现在支持 WAV / OGG / MP3 / FLAC / M4A；
- WAV/OGG 默认 **external:绝对路径** 链接，不复制；设置「链接外部素材」可关闭；
- MP3/FLAC/M4A 用 FFmpeg 转 48k 双声道 WAV（mixer 只解 WAV/OGG）；
- 外部音频路径在预览、混音、导出三处共用同一个 resolver；
- 选中音频后可「重新链接外部音频…」，不复制、不重建 clip；
- 图片新增「链接外部图片…」，`imagePath` 支持 `external:`；
- 视频新增 `videolink` 层：`external:绝对路径`，预览按需异步抽帧并预取后两帧，
  导入时预热第一秒；导出同步等待；源文件不复制；
- 帧缓存按路径+mtime+frame+分辨率，最多 96 项；
- 音频轨道使用 BBS 的 WAV/Vorbis 解码器绘制真实波形（WAV/OGG 都支持）；
- 视频 FFmpeg 命令加 `-nostdin`，避免后台导入等待 stdin。

## 7. 验证

- 全量：**152 suites / 950 tests / 0 failures / 0 errors / 1 skipped**。
- 本地候选包：`bbsae-0.99-hotfix29.jar`，已安装到正式 modpack；
  SHA-256 `34DA6CA5BA8DA138FF02630D446F875F70CFF3A35EF7023DCA450E779B0F5841`。
- 本地提交：`077e244`、`41755f1`；本轮素材链接改动尚未提交。
- 未推送 GitHub。

## 7. 仍未完成

- 素材音频/视频导入、外部素材链接尚未开始；
- 轨道音频编辑尚未开始；
- 摄像机其它手势未逐个审计；
- actor-only 在复杂半透明模型上的表现还需更多实测。
