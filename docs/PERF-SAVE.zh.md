# 性能审计 P0-3：保存 / 撤销快照路径（0.98）

> 审计结论：`UICompositorPanel.captureSnapshot`（每秒采样 + 每次手势开始/结束 + 自动保存）最终都走
> `CompStore.snapshot` → `CompLayer.saveTracks()`（逐层 `toData` + `DataToString`），自动保存的
> `saveChecked` 还要**读旧文件 + 全量解析 + `toJson` 成多 MB String + 再全量解析回读**。300 层 /
> 5000 键时一次拖动 300–600ms，全部发生在渲染线程。
>
> 本轮只改 `dev.bbsae.comp.CompStore`（写域内），**没有动** `UICompositorPanel` / UI / render /
> export / replay / build.gradle：把保存路径从「2 次全量解析 + 2 份大 String」改成「1 次流式写 +
> 2 次流式语法扫描 + 1 次字节 copy」，并去掉可证明为空图层的 `saveTracks()`。

## 1. 审计：旧路径的时间花在哪

`CompStore.saveChecked(project)`（旧实现）每次保存：

| # | 步骤 | 旧做法 | 代价 |
|---|---|---|---|
| 1 | 读旧文件 + 版本守卫 | `Files.readAllBytes` → `new String` → `GSON.fromJson` | 整个旧工程进堆 + 一次全量对象树解析 |
| 2 | 归一化 + 动画回写 | `prepareForSave` 对**每一层**调 `ensureInitialized()` + `saveTracks()` | 每层重建 `transformData`/`opacityData`/`channels`；有键层走 `DataToString` |
| 3 | 序列化 | `GSON.toJson(project)` → `StringWriter` → `String` | 多 MB 堆分配 + 一次 char 数组 copy |
| 4 | 回读校验 | `GSON.fromJson(json)` | **第二次**全量对象树解析与分配 |
| 5 | 写盘 | `Files.writeString(tmp, json)` | 再把 String 编码成 UTF-8 |
| 6 | `.bak` | 把第 1 步的旧 `byte[]` 再 `Files.write` | 旧文件已经在内存里 |

撤销路径 `snapshot()` 同样每次都 `toJson(project)` 成 pretty JSON String，而且对每一层
`saveTracks()`——即使这一层没有任何关键帧。

## 2. 修复

### 2.1 `saveChecked` 全流式（核心）

`src/main/java/dev/bbsae/comp/CompStore.java`：

1. **旧文件只做流式扫描，不再读进 byte[]/String，也不再解析成 `CompProject`。**
   `inspectProjectFile(Path)` 用 Gson 的 `JsonReader`（`setLenient(false)`）读顶层对象，
   只取 `format`、`version`、`comps` 三项，其余字段用 `skipValue()` 递归消费。
   - 整份文档都被消费，因此截断的尾巴 / 尾随逗号 / 非对象 `comps` 都能被发现（旧实现靠
     `fromJson` 抛异常，新实现不建对象树也能达到同样的「可解析」判断）。
   - 版本守卫仍然在**任何写操作之前**：`version > CompProject.VERSION` 直接拒绝。
   - `.bak` 是否轮换由同一次扫描决定：只有旧文件是「可读工程」才复制。

2. **校验内存对象，不再回读 JSON。**
   `prepareForSave` 已经把 `format/version/comps/各 list` 归一化，之后直接
   `isValid(project)` 覆盖旧回读校验的同一组条件，省掉第二次全量解析；序列化本身是否写得出来
   由随后的流式写 + `.tmp` 扫描兜底（NaN 等会让 Gson 在写 `.tmp` 时报错，失败清理逻辑不变）。

3. **序列化直接流式写 `.tmp`。**
   ```java
   try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))
   {
       GSON.toJson(project, writer);   // 不产生中间 String
   }
   ```
   磁盘工程仍然 **pretty-print**（可读、可 diff、可手改）；只有撤销快照改用 compact。

4. **`.tmp` 用同一个流式扫描验证**：要求 `format=bbsae`、`version` 与内存一致、`comps` 非空。
   不通过 → 删除 `.tmp`、返回 false、`lastSaveError` 置为「序列化结果未通过校验」。

5. **`.bak` 改为 `Files.copy(path, bak, REPLACE_EXISTING)`**：只传路径，不读旧文件。
   旧文件不可读（损坏/截断）时不覆盖 `.bak`，保住最后一份好备份。

6. **顺序**（保证不丢数据）：

   ```
   扫描旧文件（版本守卫 + 可读性）
     → prepareForSave / isValid
     → 流式写 .tmp
     → 扫描 .tmp（语法 + format/version/comps）
     → 旧文件 copy 到 .bak
     → ATOMIC_MOVE(.tmp → path)（不支持时退化为 REPLACE_EXISTING move）
   ```

   失败路径：删除 `.tmp`、`lastSaveError`、「返回 false」语义不变；`.bak` 失败只记日志、不影响
   主文件成功。若最后的 move 失败，`.bak` 是**未变的主文件副本**（绝不是坏字节）；这条顺序
   已写进方法 javadoc。

### 2.2 撤销快照：compact JSON + 可证明空层跳过 `saveTracks`

- **`snapshot` 改用 compact Gson**（`SNAPSHOT_GSON`）。快照只被 `restore(String)` 解析回来，
  从不展示、不 diff，pretty 只是白白占用 undo 的 32MB 预算。同一 300 层 / 5100 键工程：
  **1,242,702 → 885,123 chars（−28.8%）**。在本机这个规模上，省掉缩进的空格处理落在噪声内
  （见 §5），确定性的收益是内存/字符数；工程越大、undo 栈越深，省下的预算越多。
- **`prepareTracksForWrite`** 统一供 `snapshot` 和 `saveChecked` 使用：
  - 每层仍先 `ensureInitialized()`（保留正常化副作用）；
  - 只有「活通道全空 + `transformData`/`opacityData`/`channels` 全空」的层才跳过
    `saveTracks()`——此时 `saveTracks()` 的结果必然是三个空串 + 空 map，是可证明的 no-op；
  - **任何有键、有轴通道、有旧序列化数据的层照旧重建**，避免重演「快照不写回 → undo 丢全部
    关键帧」的历史 bug。判定刻意保守，测试 `aSecondSnapshotStillCapturesKeysAddedAfterTheFirst`
    锁定「第一次快照后新增的键必须出现在第二次快照里」。

### 2.3 调试计时

`-Dbbsae.debugSave=true` 时输出（默认关闭，零日志开销）：

- `bbsae save <file>: scan+prepare+write=…ms`、`verify=…ms bytes=…`、`total=…ms`；
- `bbsae snapshot: layers=… rebuilt=… skipped=… chars=… prepare=…ms toJson=…ms total=…ms`。

### 2.4 明确没做：后台 IO

`saveChecked` 的契约是「返回 true 时主文件已经写好」，调用方（导出/切换工程/测试）会立刻读文件；
把 `.bak` + move 丢到 executor 会让「保存成功」早于落盘、`lastSaveError` 变成延迟可见，还会和
`synchronized` 的读写顺序打架。序列化本身也必须在前台（绝不能把 `CompProject` 带到后台线程），
所以后台化只能省掉 copy/move 的几毫秒，收益/风险不成比例。**本轮不做**；如果以后要 save queue，
需要 Lead 先定「保存完成」语义（见 §7）。

## 3. 不变量（测试锁定）

- 损坏工程：隔离（quarantine）行为不变，绝不静默覆盖成 demo；
- 未来版本：磁盘上 `version > VERSION` 永远拒绝覆盖；内存工程版本过高同样拒绝；
- `.bak`：只在旧文件可读 + 可解析时轮换，且是**逐字节 copy**；损坏旧文件不覆盖最后的好备份；
- 保存失败：清理 `.tmp`、`lastSaveError` 非空、返回 false；
- 保存后再 `loadOrNull`：comp / layer / 关键帧数量与数值一致；
- 保存过程：**0 次全量 parse、0 次 JSON String 序列化**（`SaveIoStats` 计数器）；
- 快照：静态层跳过、动画层写回，旧快照保持旧的关键帧数。

## 4. 测试

新增 `src/test/java/dev/bbsae/comp/SavePathPerfTest.java`（8 个用例）：

| 用例 | 覆盖 |
|---|---|
| `saveStreamsToDiskWithoutBuildingOrParsingAString` | 40 层 × 10 键：`fullParses=0`、`stringSerializations=0`、`streamWrites=1`、`streamScans=1`、40 个有键层全部 `saveTracks`；保存后重新 load 关键帧不丢；无 `.tmp` 残留 |
| `backupIsAByteCopyOfThePreviousRevisionAndIsNotParsed` | 第二次保存：旧文件与新 `.tmp` 各扫描 1 次、0 次 parse；`.bak` 与上一版**逐字节相等** |
| `unreadablePreviousFileNeverRotatesOverTheLastGoodBackup` | 旧文件有合法头部但被截断：新工程写入成功，`.bak` 仍是上一份好备份 |
| `streamingValidatorAcceptsOnlyCompleteProjectDocuments` | 流式校验：完整文件 true；截断 / 空 comps / 尾随逗号 / 非对象 / 缺失文件 false |
| `snapshotSkipsProvablyEmptyLayersAndKeepsAnimatedOnes` | 1 静态 + 1 动画层：`trackRebuilds=1`、`trackSkips=1`；restore 后 opacity/effects 通道数值一致 |
| `aSecondSnapshotStillCapturesKeysAddedAfterTheFirst` | 第一次快照后新增 opacity + scalar 键；第二次快照必须包含，且第一份快照不被污染 |
| `saveFailureCleansUpWhenTheProjectDirectoryCannotBeCreated` | 工程目录无法创建：返回 false、`lastSaveError` 非空、不留下主文件 |
| `largeProjectRoundTripsWithinBudget` | 300 层 / 5100 键：快照 / 保存 / 旧路径 A/B 打点，保存后 300 层 × 17 键完整 load |

扩展 `CompStorePersistenceTest`（16 个用例，新增 1 个）：

- `backupIsAnExactByteCopyOfThePreviousRevision`：`.bak` 与上一版逐字节相等（不是重新序列化）；
- `saveCheckedWritesAndRotatesTheBackupOnlyAfterTheMainWrite` 增加「成功后无 `.tmp`」断言；
- `backupRotationFailureDoesNotFailTheMainSave` 改用**非空**目录占住 `.bak` 路径：
  Windows 上 `Files.copy(REPLACE_EXISTING)` 会替换空目录，空目录已不能真正触发失败分支。

验证命令（串行 wrapper，离线）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 `
  test --offline --console=plain `
  --tests "dev.bbsae.comp.SavePathPerfTest" `
  --tests "dev.bbsae.comp.CompStorePersistenceTest" `
  --tests "dev.bbsae.comp.EditorViewStateRoundTripTest" `
  --tests "dev.bbsae.comp.PenPathPersistenceTest"
```

全量：**882 tests / 1 failed / 1 skipped**。唯一失败是并行工作的另一位 agent 未提交的
`TimelineVisibleRowFilterTest.firstRowFollowsTheScrollOffset()`（`UICompTrackTimeline` 正在改，
与 `dev.bbsae.comp` 无关）；所有 `dev.bbsae.comp.*` 套件全绿。

## 5. 实测（本机，warm，5 次取最快值）

工程形状：300 层 × 17 opacity 键 = 5100 关键帧（审计规模），磁盘 pretty JSON 1,242,702B。

| 路径 | 时间 | 说明 |
|---|---|---|
| `CompStore.snapshot`（新：compact + 空层跳过） | **33ms**，885,123 chars | 与旧的 pretty 快照同量级（缩进处理在噪声内）；字符数恒定 −28.8% |
| 旧 snapshot（每层 `saveTracks` + pretty String） | **35–45ms**，1,242,702 chars | 基准参考，未提交到生产 |
| `CompStore.saveChecked`（新：流式） | **64–66ms** | 0 次全量 parse / 0 次 JSON String |
| 旧 `saveChecked` 等价路径（String + 回读 + 读旧文件解析） | **88–107ms** | 基准参考，未提交到生产 |

本机同时有其它 agent 的 Gradle/测试在跑，绝对毫秒会波动（同一路径不同轮次可差 1.5–2×，
快照/旧快照甚至会出现互有胜负的轮次）；确定性指标稳定：磁盘 JSON 更大 → 快照字符数固定减少
28.8%；旧保存路径固定多出「读旧文件 + 旧文件 parse + `toJson` String + 回读 parse」，所以
`saveChecked` 每一轮都更快。一次手势 ≈ 2 次快照 + 1 次保存：本轮后约 `2×33 + 65 ≈ 131ms`；
旧路径（同样本机、同一工程）约 `2×35 + 88 ≈ 158ms`（保守）到 `2×45 + 107 ≈ 197ms`，且旧路径的 parse 次数在 300 层/更复杂
工程上随对象数线性放大。

## 6. 文件

| 文件 | 变更 |
|---|---|
| `src/main/java/dev/bbsae/comp/CompStore.java` | `saveChecked` 流式写 + `JsonReader` 校验 + `.bak` 字节 copy；`snapshot` compact；`prepareTracksForWrite`/`needsTrackWriteback`；`-Dbbsae.debugSave` 分段计时；`SaveIoStats` 测试计数；删除无用的 `existingVersion`/`readPrevious` |
| `src/test/java/dev/bbsae/comp/SavePathPerfTest.java` | 新增（8） |
| `src/test/java/dev/bbsae/comp/CompStorePersistenceTest.java` | +1 字节 copy 测试；`.tmp` 清理断言；备份失败测试改用非空目录 |
| `docs/PERF-SAVE.zh.md` | 本文档 |

**未改动**：`UICompositorPanel`、`CompLayer`、`client/**`、`export/**`、`replay/**`、`build.gradle`。

## 7. 剩余瓶颈 / 需要 Lead 的决策

1. **撤销仍是「整工程 JSON」**：一次手势至少 2 次全量序列化（`beginAction` 手势开始 +
   手势结束/采样器）。本轮只降低常数（compact −28.8%、空层跳过、保存去掉二次 parse）。
   要真正把 300–600ms 降到毫秒级，需要 **comp 级撤销 / 结构化 diff undo** 或「快照延迟到真正
   需要 undo 时再序列化」——这会改 `UICompositorPanel` 的 undo 栈语义，属 Lead 决策。
2. **保存仍在渲染线程**：流式化后一次保存 ~65ms（300 层 / 5100 键，本机），大工程仍可感知。若要后台
   save queue（单线程、flush 时等待、只传 path/bytes），需要先定义 `saveChecked` 的完成语义。
3. **`restore` 仍是全量 Gson parse**：Ctrl+Z 时发生；要优化得先做 comp 级撤销。
4. **有键层的 `saveTracks` 仍全量重建 `DataToString`**：要安全 memoize，需要 `CompLayer` 暴露
   通道修订号/脏标记（`CompLayer` 不在本任务写域；需 Lead 批准后另开任务）。
5. `UICompositorPanel.snapshotForUndo` 为了屏蔽 UI 字段会临时改 `settings` 再恢复；它也是
   「整工程序列化」难以缓存的原因之一，属 UI 写域。
