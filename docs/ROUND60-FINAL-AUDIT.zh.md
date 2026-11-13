# 0.99-hotfix2 发布候选审计

> 版本策略：项目仍为 `0.99`，本次只加后缀 `hotfix2`，不迭代到 1.0。
> GitHub 已做一次大版本提交：`7154a74`（`feat(0.99): effects-left workspace, embedded replay editor, comp-camera replay actors, 3D extrude/repeat, shader support, pen/matte fixes`）。

## 1. 构建产物

- 源码：`C:\Users\Administrator\Desktop\bbsae1.0 - 副本`
- 构建：`gradlew build -x test --offline`
- 产物：`bbsae-0.99-hotfix2.jar`
- SHA-256：
  `CE19ECF773AAAB6710B932B6D72A723A7F42251128E73E3B7BE33D0FD350CACD`
- 安装：
  `D:\新建文件夹\PCL 正式版 2.12.2\.minecraft\versions\1.20.1-BBS2.2\mods\bbsae-0.99-hotfix2.jar`
- 旧 jar 备份：`_backup\modpack-bbsae-0.99-hotfix-20261113-1025.jar`
- jar 内已确认包含：
  - `dev/bbsae/client/render/ReplayActorPass*.class`
  - `assets/bbsae/textures/effects/extrude3d.png`
  - 最新 UI 类与 `fabric.mod.json`

## 2. 测试

- 全量：**148 suites / 944 tests / 0 failures / 0 errors / 1 skipped**。
- 新增关键测试：
  - `ExtrudeEffectTest`：extrude3d 注册与默认参数；
  - `GlStateGuardSrgbTest`：sRGB 保存/关闭/恢复；
  - `ReplayActorMappingTest`：actor 中心、12 px/block、Y 翻转；
  - 蒙版开放路径 4 个测试类更新为 AE 语义。

## 3. 任务清单状态

| 任务 | 状态 | 说明 |
|---|---|---|
| 1 关键帧抽屉 | 完成 | 崩溃 + 高度/轨道修复，截图验证 |
| 2 左效果/右属性/独立效果页 | 完成 | 默认 dock + v7 迁移 + 真机截图 |
| 3 UI 审计 | 基本完成 | 模式条、回放崩溃边界、空状态遮挡、按钮守卫；左右栏仍是 BBS 原生控件 |
| 4 回放嵌入 | 完成 | 合成/回放模式条，真机脚本通过 |
| 5 回放预览 | 完成 | 3D 合成相机；2D plate + 平铺 fallback |
| 6 光影 | 完成 | offscreen + sRGB + fallback；正式 modpack 已在世界内打开合成器，BSL 下预览正常（截图 round64） |
| 7 动效 | 完成 | 指数弹簧、quint、press/tab 统一 |
| 8 抠像同步 | 完成 | 帧序号 + tick + 尺寸拒绝 |
| 9 钢笔 | 完成本批 | Set First Vertex、shape 变换、开放路径填充 |
| 10 预览缩放 | 完成 | 设备像素 + 缓存门槛 + 百分比读数 |
| 11 3D 实体回放 | 完成本批 | CompCamera + layer world + 映射测试 |
| 12 回归审计 | 进行中 | 本文件；最终冻结后再跑一次 |
| 13 3D 挤压 | 完成 | extrude3d 深度切片（合成/工作区） |
| 14 效果 | 大部分完成 | source UV/扭曲/模糊上限/重复/3D 工作区；AM 逐项像素对齐未穷尽 |

## 4. 已知限制

- 正式 modpack 已安装 hotfix2；用 PCL 的真实启动参数加载 Iris 1.7.6 + Sodium 0.5.13 +
  Indium + BSL v10，并从 `ShaderTest` 世界进入游戏、打开 bbsae 合成器：
  - 日志确认 `Using shaderpack: BSL_v10.0.zip`；
  - 预览信息显示 `光影已加载 · 光影最终帧 · 预览 50%`；
  - 截图 `docs/verify/round64-modpack-iris.png`；
  - 测试后已恢复 `iris.properties`（`enableShaders=false`）并删除测试世界。
- 回放左右栏仍使用 BBS 原生列表/属性控件，仅模式条/主体/空状态做了统一。
- AM 效果没有逐条做像素级对比；本轮解决的是底层坐标、重复、扭曲、模糊上限等基础层。
- GitHub 大版本提交：`7154a74`，remote `zhongend/bbsae` main。
- 后续若继续修 AM 逐项对齐，只提交小后缀，不迭代 1.0。
