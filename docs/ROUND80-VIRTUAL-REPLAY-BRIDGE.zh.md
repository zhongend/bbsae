# Round 80 — bbsae 合成器 ↔ 虚拟回放编辑器互通

> 版本 `0.99-hotfix38`。

## 1. 根因

回放编辑器绑定的是 `Film.replays`（BBS 影片的真实回放列表），而 bbsae 合成器里的回放模型是
`CompLayer`（`type=replay`、`independent=true`、`formData`）。两边没有任何映射：

- 合成器「＋模型」只创建了 CompLayer；
- 切到回放编辑器，`Film.replays` 仍是影片原来的真实回放（或为空），所以提示“没有检测到回放”；
- 反过来，回放编辑器里看到的是真实影片的回放（例如 donkey），不是 bbsae 虚拟世界的模型。

## 2. 一条稳定的一对一链接

`CompLayer` 新增：

- `replayId`：稳定的 BBS Replay id；
- `replayAnchorX/Y/Z`：该回放在图层里的世界原点。

新增 `CompReplayBridge`：

- `ensure` / `ensureAll`：每个独立 bbsae 回放模型层对应且只对应一个 `Replay`；
- 新建时复制图层 form、名称，标记为虚拟回放；已有链接不重复创建；
- `syncFormToReplay` / `syncLayerFromReplay`：模型库在两侧保持一致；
- `VIRTUAL_MARKER`：虚拟回放 label 前的零宽标记，用于原版列表的文本过滤，不改变显示名称。

调用点：

- 「＋模型」创建后立即链接；
- 模型库选完模型后把 form 同步到 Replay；
- 从合成器切到回放前 `ensureAll`；
- 回到合成器时把回放编辑器里改过的 form 同步回图层。

## 3. 合成器添加的回放能被编辑器看到

进入回放工作区时：

1. `CompReplayBridge.ensureAll(film, doc)`；
2. 选中当前 CompLayer 对应的 Replay；
3. `frameAll`，避免新模型在视口边缘/画外；
4. 原版回放列表、属性、轨道、gizmo 全部指向这个 Replay。

因此「合成器添加回放 → 回放编辑器提示没有回放」不再复现。反向新增的回放也会在退回合成器时
补成 linked CompLayer（`importNewReplaysFromEditor`）。

## 4. 回放编辑器只显示虚拟世界回放

- 虚拟回放的 label 带零宽 `VIRTUAL_MARKER`；
- `UIReplayModePanel` 用原版 `UIReplayList` 自带的文本过滤只保留虚拟回放，真实影片回放
  （donkey 等）不再出现在左侧列表；
- `ReplayEditSession` 增加 replay filter：视口模型 pass、拾取 pass、frameAll、placeableCount
  都只处理虚拟回放；
- 原版列表的搜索框和“+”按钮在此工作区禁用：搜索会覆盖过滤串，而“+”会创建真实影片回放；
  虚拟回放应在合成器点「＋模型」创建。

预览仍是 `UIReplayViewport` 的黑色 bbsae 虚拟 3D 世界，不显示实时 Minecraft 世界。

## 5. 不污染真实世界

虚拟回放虽然为了复用 BBS 原版编辑器而存在于 film 的 replay 列表里，但：

- 在合成器/影片播放时 `enabled=false`，BBS world pass 不会把 bbsae 虚拟模型画进真实世界；
- 只有进入 bbsae 回放编辑器时临时 `enabled=true`，退出后再次关闭；
- 合成器的 linked-model pass 用 Replay 的 keyframes 自行渲染，不依赖 controller 的 enabled
  状态；没有 controller 实体时创建一个私有 `StubEntity` 并按 keyframes/properties 摆姿势。

## 6. 验证

- 真机流程：`bbsae.debugBridgeCompReplay` 在合成器里新建一个独立回放模型（chicken）→ 切到
  回放编辑器；日志显示 `Bridge debug ... virtual=true`，列表只有虚拟回放，没有真实影片回放。
  截图：`docs/verify/round80-virtual-replay-bridge.png`。
- 合成器侧：`debugBridgeComposite` 在虚拟回放 x 通道写入关键帧并回到合成器，模型按 x=2 的
  回放关键帧右移（项目像素比例 64px/格），证明 linked-model pass 使用 Replay 数据而不是静态
  layer form。截图：`docs/verify/round80-linked-model.png`。
- 合成器左侧回放页仍列出多个测试遗留的 linked 层；正式项目里每个「＋模型」只有一层。
- 全量测试：**152 套件 / 951 项测试 / 0 failures / 0 errors / 1 skipped**。
- 本地候选包：`bbsae-0.99-hotfix38.jar`（3,892,486 bytes，SHA-256
  `18B2FB31184DB0DF84C4A26AE1A35731E3D5D01D7E32C7556EE8E5337EF8BF97`）。

## 7. 仍未完成

- 虚拟回放目前仍保存在 film 的 replay 列表里（带零宽标记、默认 disabled），因此 BBS 原版影片
  编辑器仍可能列出这些 disabled 行；彻底隔离需要单独的虚拟 Film/Replay 存储与 filmPanel 交换。
- 原版列表的“+”和搜索在 bbsae 回放工作区被禁用；虚拟回放的创建入口是合成器的「＋模型」。
- 真实影片回放与虚拟回放共用同一 Film 的 undo 历史；删除/撤销跨两侧时的边界尚未逐一审计。
