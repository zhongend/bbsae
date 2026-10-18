# 第八轮 04：GitHub 发布记录（2026-10-18）

## 1. 仓库

- 仓库：<https://github.com/zhongend/bbsae>
- 默认分支：`main`
- 可见性：public
- 许可：MIT（`LICENSE`）

## 2. 提交

| 提交 | 说明 |
| --- | --- |
| `a72aee4` | `Add Alight Motion PIXEL effects and layer-space fixes`（103 files，+18716/−2319） |
| `f0c0ab1` | `Update handoff with round 8 build and entry points` |
| `1ae375b` | `Document GitHub publish and release asset` |
| `007c531` | `Record final publish commit and release notes` |
| `5d63fee` | `Add visual gallery and Chinese illustrated introduction` |

本文件最后更新时 `main` 的最新提交是 `5d63fee`。远程源码已验证包含：

- `EffectRenderer.planPixel` / `SUPPORTED`；
- `docs/ROUND8-01-AUDIT.zh.md`、`ROUND8-02-PIXEL.zh.md`、`ROUND8-03-VERIFY.zh.md`；
- `docs/verify/round8-pixel-effects.png`；
- README 中的新 hash `1CC9F7F8...` 与 61 测试。

## 3. Release 资产

- Release：<https://github.com/zhongend/bbsae/releases/tag/v0.1.0>
- 资产：<https://github.com/zhongend/bbsae/releases/download/v0.1.0/bbsae-0.1.0.jar>
- 大小：`396510` 字节
- SHA-256：`1CC9F7F8827B5926A2A9C005A9D1FFDC8B946C276C796C8D4415E87EF6DBA00C`

旧的 `v0.1.0` 资产（216872 字节）已被本轮构建替换；tag 仍指向最初的 release commit，
这是 GitHub release 的“固定 tag + 可替换资产”用法。Release 说明也已更新为
“53 effects + round 8”与本轮 hash。如果以后要改源码版本号，建议升级
`gradle.properties` 的 `mod_version` 并新建 `v0.1.1` release，而不是继续覆盖。

## 4. 构建与上传命令（复现）

```powershell
$root = $PWD.Path
$env:JAVA_HOME = "$root\_tools\jdk21\jdk21.0.12_12"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$env:GRADLE_USER_HOME = "$root\.gradle-home"
$env:TEMP = "$root\.tmp"
$env:TMP = "$root\.tmp"
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=GBK -Dsun.jnu.encoding=GBK'

& 'C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.2.0-bin\11i5gvueggl8a5cioxuftxrik\gradle-9.2.0\bin\gradle.bat' build --offline --console=plain
Copy-Item build\libs\bbsae-0.1.0.jar .\bbsae-0.1.0.jar -Force
Get-FileHash .\bbsae-0.1.0.jar -Algorithm SHA256
```

上传源码时不要提交：

- `build/`、`.gradle-home/`、`.tmp/`、`run/`、`_tools/`、`_research/`、`.workbuddy*/`；
- `libs/*.jar`（BBS 本体不随仓库分发）；
- 根目录 `*.jar`（release 资产单独上传）。

发布资产通过 GitHub API：

```text
DELETE /repos/zhongend/bbsae/releases/assets/{asset_id}
POST   https://uploads.github.com/repos/zhongend/bbsae/releases/{release_id}/assets?name=bbsae-0.1.0.jar
```

## 5. 验证

- 远程 `EffectRenderer.java` 含 `planPixel` / `SUPPORTED`；
- 远程 `README.md` 含 `1CC9F7F8` 与 `ROUND8` 链接；
- 本地 `jar tf` 含 `EffectRenderer$PixelPlan.class`、`fabric.mod.json`；
- `javap` 显示 Minecraft 引用是 intermediary（`net/minecraft/class_...`），
  BBS 引用保留 `mchorse/bbs_mod/...`；
- 测试：61/61 通过。

## 6. 图文介绍页（追加）

为了在 GitHub 首页直接展示界面，追加了 commit `5d63fee`
（`Add visual gallery and Chinese illustrated introduction`）：

- `docs/gallery/hero.png`：1600×900 首页 banner（标题 + 编辑器截图 + 四个效果缩略图）；
- `docs/gallery/editor-overview.png`：1920×1200 带 4 个编号标注的界面总览；
- `docs/gallery/timeline.png`：轨道时间轴细节裁图；
- `docs/GALLERY.zh.md`：中文逐图介绍；
- `README.md`：顶部 hero、Gallery 章节、调色/素材/3D/导出截图表格。

GitHub 渲染后的图片 URL 已验证为
`https://github.com/zhongend/bbsae/raw/main/docs/gallery/hero.png` 等；
Release 说明也加上了同一张 hero 图。
