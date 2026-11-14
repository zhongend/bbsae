# Round 110 — 旧工程里的共享预合成副本

> 版本 `0.99-hotfix75`。这是 hotfix74 的补丁轮：修复已经保存的工程里的历史共享数据。

## 1. 为什么 hotfix74 之后副本还是共享的

hotfix74 修的是**复制动作**：`CompLayerOps.duplicateLayer(project, layer)` 在复制时
递归克隆 `precompId` 指向的整个 `CompDocument`。新复制的图层因此是独立的。

但旧版本保存的工程里，副本图层 JSON 里的字段长这样：

```json
{ "id": "pc_copy", "type": "precomp", "precompId": "precomp_1", "name": "预合成 1 副本" }
{ "id": "pc",      "type": "precomp", "precompId": "precomp_1", "name": "预合成 1" }
```

两个图层**在磁盘上就已经指向同一个 `precompId`**。打开工程时，它们是两个普通对象，
但 `precompId` 相同 → 渲染和编辑都解析到同一个 `CompDocument`。新代码不会也无权
凭空改写这个引用，所以用户升级后看到的还是"改副本，原合成也变"。

结论：这是**数据迁移问题**，不是复制路径问题。两件事必须都做：

| 问题 | 修复 |
| --- | --- |
| 以后复制要独立 | hotfix72/74：递归克隆嵌套树 |
| 旧副本已经共享 | hotfix75：打开工程时拆分 |

## 2. 修复策略：只拆"看起来像副本"的

共享预合成在 AE 里是合法特性：两个图层可以刻意显示同一个嵌套合成（改一处、两处
都变）。所以不能一见到共享就拆。

`CompLayerOps.repairSharedPrecompCopies(project)` 的规则：

1. **只在同一个合成内**分组。跨合成的复用是工程结构，不动。
2. 每个 `precompId` 只保留**第一个**图层指向原合成（"原件"稳定，其它合成的引用
   不会被改义）。
3. 其余图层只有满足以下任一条件才拆：
   - `id` 含 `_copy`（编辑器复制命令一直用这个后缀）；
   - 名字含「副本」或 `copy`。
4. 目标 `CompDocument` 不存在（悬空 id）时跳过。
5. 幂等：修好的工程再跑一次返回 0。

拆分使用 `duplicateComposition()`：快照序列化深拷贝文档 + 递归克隆嵌套预合成树，
和用户点「复制目标预合成」得到的结果一致。

## 3. UI

### 打开工程

`bindStandalone` / `bindComposition` 在完成绑定后调用
`repairSharedPrecompCopies("打开工程")`。有修复时：

- 状态栏：`已自动修复旧版本的预合成共享：N 个副本获得独立预合成（打开工程）`
- 日志：`Repaired N shared precomp copies on 打开工程`
- 立即排队保存（迁移结果落盘）

### 预合成检查器

- 目标信息行：
  - `当前目标：<名称> · <id>（只有本层使用）`
  - 或 `注意：本合成里还有 N 个图层指向同一个预合成…旧版本复制的副本会这样`
- 「复制目标预合成（独立副本）」：只把**本层**换成私有副本。
- 「修复本工程所有旧共享副本」：先用 `countRepairableSharedPrecompCopies` 统计，
  0 时不产生撤销点；有则 `beginAction` + 修复（可 Ctrl+Z）。

### 复制反馈

- 单层复制状态栏追加 `· 独立预合成：<名称>（<id>）`；
- 多选复制 / 粘贴追加 `（N 个预合成已获得独立副本）`；
- 复制后若目标仍等于原目标且原目标存在，**取消复制**并提示，不会出现共用副本；
- 日志：`precomp duplicate: layer=… target A -> B`。

## 4. 验证

`PrecompIndependenceTest` 扩到 **15 项**（本轮新增 8 项）：

1. `aLegacySharedCopyIsDetachedOnRepair`：旧共享副本拆分后改名/改文字互不影响，
   二次修复返回 0；
2. `aDeliberatelySharedInstanceIsNotDetached`：无复制标记的共用不动；
3. `everyLegacyCopyInAGroupGetsItsOwnTree`：一组里多个旧副本各自独立；
4. `crossCompositionReuseIsLeftAlone`：跨合成复用不动；
5. `aDanglingSharedTargetIsNotCloned`：悬空目标不处理；
6. `theRepairedCopySurvivesSaveAndReload`：修复 → 保存 → 重载后仍独立且无待修项；
7. `enteringALegacyCopyDetachesItFirst`：打开副本前自动拆分，已独立后不再拆；
8. `detachOnlyTouchesCopyLookingLayers`：进入时不碰刻意共用。

全量：**179 套件 / 1109 项 / 0 failures / 0 errors / 1 skipped**。
未启动客户端截图；建议实机验证：打开旧工程（状态栏应提示修复数量）→ 进入副本改文字
→ 返回原预合成确认未变。

## 5. 边界

- 自动修复是迁移操作，不进入撤销栈；手动按钮可撤销。
- 如果旧副本被改过名字、id 里也没有 `_copy`，自动启发式不会拆；用预合成页的手动
  按钮。
- 拆分后工程里的 composition 数量会增加，这是"独立副本"的必然成本。
