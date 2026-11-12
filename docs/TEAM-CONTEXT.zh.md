# BBS AE · 团队协作与构建上下文（给 AI 协作者）

> 本文件由 Team Lead 维护。先读完再动手。项目根目录即本文件所在仓库根目录。

## 1. 这是什么项目

**bbsae（BBS AE）**：Minecraft 1.20.1 / Fabric 的 **BBS FS 2.7 客户端 addon**。
它把 After Effects 式的合成器（图层 / 关键帧 / 蒙版 / 效果 / 3D 摄像机 / 导出）搬进 BBS 影片编辑器。

- 语言：Java 17；构建：Gradle（fabric-loom）；依赖 BBS 的 jar：`libs/bbs-2.7-1.20.1.jar`。
- 主入口：`src/main/java/dev/bbsae/BbsAeAddon.java`、`client/BbsAeClient.java`。
- 合成数据模型：`src/main/java/dev/bbsae/comp/`；渲染：`client/render/`；UI：`client/ui/`。
- 总文档：[`docs/HANDOFF.zh.md`](HANDOFF.zh.md)、[`docs/EFFECT-LIBRARY-PLAN.zh.md`](EFFECT-LIBRARY-PLAN.zh.md)、
  [`docs/features.zh.md`](features.zh.md)。
- 长期约束（构建、架构铁律、历史坑）：[`.workbuddy/memory/MEMORY.md`](../.workbuddy/memory/MEMORY.md)。

## 2. 构建与测试（必须用这套环境）

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
. .\_tools\gradle-env.ps1        # 设置 JAVA_HOME/GRADLE_USER_HOME/TEMP/JAVA_TOOL_OPTIONS 并 cd 到项目根
.\gradlew.bat test --offline --console=plain
```

- **多人并行时必须用串行包装器**（否则两个 Gradle 会互删 build/classes，报
  `Unable to delete directory build/classes/java/test` / `NoSuchFileException`）：
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
  ```
  它用命名互斥量排队，参数原样传给 gradlew（`compileJava` / `test --tests "..."` 都可以）。
  构建日志里出现 `gradle-run: lock acquired` 表示拿到锁。
- 若不想 dot-source，也可以把 `_tools\gradle-env.ps1` 里的环境变量直接内联写在命令里。

- 快速编译：`.\gradlew.bat compileJava compileTestJava --offline --console=plain`
- 只跑一个测试类：`.\gradlew.bat test --offline --console=plain --tests "dev.bbsae.render.Camera3DTest"`
- 交付 jar：`.\gradlew.bat build --offline --console=plain` → `build/libs/bbsae-<version>.jar`。
- **不要**改 `gradle.properties` 的 `mod_version`；版本与发布由 Lead 统一处理。
- 多个协作者可能同时跑 Gradle：偶发编译错误若指向「别人正在改的文件」，等 30–60 秒重试即可；
  失败时先 `git status --short` 看是谁的文件。
- `runClient*` 任务会启动真实客户端，**默认禁止**（用户明确要求不要频繁开客户端截图）。
  只有 Lead 在每轮收尾做一次真机验证时才允许。

## 3. 硬性规则

1. 不 `git commit` / `git push`。工作区改动由 Lead 统一提交、统一发版。
2. 不打开客户端截图测试；以单元测试 + 无头 GL 测试为准（测试里已有隐藏 GL 上下文的工具类）。
3. UI 文本用中文；代码注释/文档风格参考现有文件（说明「为什么」，不是「是什么」）。
4. 改行为必须同时加回归测试；新测试放在 `src/test/java/dev/bbsae/...` 对应包。
5. 只改自己任务声明的文件范围；需要改别人范围时先发消息给 Lead。
6. 完成后向 Lead 汇报：改了什么文件、根因、验证命令与结果、未完成风险。
7. 遇到需要真机确认的问题，写清复现步骤与期望，交给 Lead 集中验证，不要自己开客户端。

## 4. 当前任务主线（用户 8 条需求）

1. **3D 摄像机逻辑**（AE 语义：只有开了 3D 的图层受摄像机影响）+ 设置里的「开发者设置」可切回旧逻辑；
   没有摄像机时右键拖动预览框**不得自动创建**摄像机；补齐 AE 摄像机操作与设置按钮。
2. **图层效果逻辑**：3D 模式下部分效果无效、文字图层效果上下颠倒、重复类效果被图层大小裁成「蒙版」，
   要求 2D/3D 都真正无限延伸；效果参数语义对齐 Alight Motion（数据见 `_research/am/effects.json`）。
3. **逐像素深度导致预览重影**（近期 0.87–0.89 已修摄像机预览重影，逐像素深度已默认关闭，仍需排查）。
4. **界面操作逻辑**：效果列表并入预览右侧、其他交互问题。
5. **实体回放编辑模式**：主界面切换按钮；BBS 原版回放编辑（轨道/模型选择/gizmo/动作）搬进 bbsae 3D 空间。
6. **钢笔工具**：100% 复刻 AE 钢笔（参考 `_research/effectcraft`），2D/3D 都可用。
7. **全项目 bug 排查**，优先影响操作的问题。
8. **UI 优化**：所有页面非线性动画、面板像 BBS 原版自适应尺寸、面板可拖拽组合；
   修复「拖轨道左侧面板数据时面板大小跟着变」的问题。

## 5. 参考代码与数据

- BBS 原版源码（2.7 / 1.20.4 分支，架构以 `libs/bbs-2.7-1.20.1.jar` 的类签名为准）：
  `_research/bbs-fs/`；回放/模型相关在 `src/client/java/mchorse/bbs_mod/{film,cubic,forms,ui}`。
- EffectCraft（AE 复刻，Rust）：`_research/effectcraft/`；
  钢笔/路径重点看 `crates/engine/src/commands/shape_tool.rs`、`crates/ui-egui/src/panels/path_vr_panels.rs`、
  `crates/path/src/`。
- Alight Motion 效果数据（历史提取）：`_research/am/effects.json`、`_research/am/inventory.json`。
- 历史备份：`_backup/`（改动前可对比）；旧版 jar：仓库根目录。

## 6. 已经踩过的坑（务必避免）

- 合成 FBO 是 **y-down ortho**，纹理 V 轴与合成 Y 相反；任何 comp UV → 图层局部映射必须
  `compY = (1 - vUv.y) * compHeight`。少翻一次 V 就会「效果锚在合成上、拖图层不跟随」。
- 摄像机层在渲染循环 / solo 扫描里必须跳过；`cameraView` 用 `rotateZ(-angle)`。
- 混合模式 `COMPOSITE` 必须 `mix(srcRgb, blendColor(b, src), b.a)`，否则透明背景上 Multiply/Screen 变黑/白。
- 预览绝不能给 BBS 开 `BBSRendering.setCustomSize`（世界帧会 blit 到所有 dashboard 页）。
- `UIElement.row()` 会无视子元素 `w()` 均分宽度；需要精确定位用绝对 `relative(...).x/y/w/h`。
- `UIKeyframeEditor` 的 sheet 必须在控件有真实尺寸后再加（否则 dope sheet 缓存为空）。
- 撤销是「每秒 JSON 快照 + 恢复」，轨道拖动期间不抓快照。
- 每帧每层矩阵只算一次（`CompRenderContext` 缓存）；新增影响像素的字段必须加进
  `CompRenderCache.signature`，否则「改了没反应」。
