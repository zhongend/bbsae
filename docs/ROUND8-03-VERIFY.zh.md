# 第八轮 03：验证与产物（2026-10-18）

> 上游：
> [`ROUND8-01-AUDIT.zh.md`](ROUND8-01-AUDIT.zh.md)（第一性原理与 bug 清单）、
> [`ROUND8-02-PIXEL.zh.md`](ROUND8-02-PIXEL.zh.md)（PIXEL 实现）。

## 1. 测试结果

命令（本机；路径含中文，所以用 GBK 编码参数）：

```powershell
$root = $PWD.Path
$env:JAVA_HOME = "$root\_tools\jdk21\jdk21.0.12_12"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$env:GRADLE_USER_HOME = "$root\.gradle-home"
$env:TEMP = "$root\.tmp"
$env:TMP = "$root\.tmp"
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=GBK -Dsun.jnu.encoding=GBK'

& 'C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.2.0-bin\11i5gvueggl8a5cioxuftxrik\gradle-9.2.0\bin\gradle.bat' test --offline --console=plain
```

结果：**61 / 61 通过，0 failures，0 errors，0 skipped**。

| 测试类 | 数量 | 覆盖 |
| --- | ---: | --- |
| `CompModelTest` | 18 | 时间映射、关键帧往返、蒙版/形状/文字、表达式、JSON、轨道 move/trim/split |
| `Camera3DTest` | 5 | 3D 相机/投影 |
| `CameraViewTest` | 4 | 2D 相机/旋转/solo |
| `EffectSpaceTest` | 5 | 层空间映射；本轮新增 **identity 往返** |
| `ShaderCompileTest` | 2 | 所有运行时 GLSL 编译/链接 + straight alpha |
| `ShaderDistortTest` | 1 | 扭曲真实像素断言 |
| `ShaderPixelTest` | 12 | PIXEL 七效果真实 GL 像素断言 + 联系表 |
| `PixelPlanTest` | 5 | 无 GL 的参数死链/默认值/速度 |
| `EffectCoverageTest` | 5 | 双向覆盖、参数范围、JSON 往返 |
| `CompMotionTest` | 4 | 运动模板 |

### 1.1 `ShaderPixelTest` 具体断言

| 测试 | 断言 |
| --- | --- |
| `scanlinesDarkenAlternatingRows` | 至少 5 行亮、5 行暗 |
| `mirrorOverlaysAFoldedCopy` | 左边缘采到右半（蓝），不再是红 |
| `mirrorOpacityZeroIsIdentity` | opacity=0 时原图完整保留 |
| `mirrorUsesLayerSpaceInsteadOfCompositionSpace` | 16×16 居中层的折叠在层内；层外 alpha=0 |
| `halftoneProducesInkDotsWithSeparatePaper` | 网点中心=ink 色，格角=paper 色 |
| `halftoneStrengthZeroIsIdentity` | strength=0 与源图逐像素相同 |
| `embossFlatImageIsNeutralGrey` | 平坦图内部=128±1 中灰 |
| `embossAmountZeroIsIdentity` | amount=0 与源图逐像素相同 |
| `glitchIsIdentityAtZeroAmountAndAnimatesWithPhase` | amount=0 恒等；Phase 0 vs 20 输出不同；>20 像素改变 |
| `vhsIsIdentityAtZeroAmountAndUsesNoise` | amount=0 恒等；noise 0 vs 1 输出不同 |
| `kaleidoscopeFoldsIntoFourSymmetricSectors` | 4 段输出水平/垂直镜像对称（容差 2） |
| `writeEffectContactSheet` | 写出 `docs/verify/round8-pixel-effects.png` |

### 1.2 无 GL 的参数死链测试

`PixelPlanTest.everyAlightMotionParameterChangesThePlan` 对七个效果的每个 `.p(...)` 参数：

1. 用默认值生成 `PixelPlan.signature()`；
2. 把该参数改到 min（若默认=min 则改到 max）；
3. 再生成签名；
4. 断言签名不同。

同一测试对每个颜色参数做同样检查。它直接覆盖了本轮修掉的
`glitch.speed` / `vhs.speed` / `vhs.noise` 类问题；以后新增效果忘记接参数会立即失败。

## 2. 视觉证据

联系表：`docs/verify/round8-pixel-effects.png`（640×364，8 个 32×32 源图放大 5 倍 + 标签）。

![PIXEL effects contact sheet](verify/round8-pixel-effects.png)

- 第一行：Source、扫描线、Glitch、VHS；
- 第二行：半色调、浮雕、镜像、万花筒。

注意：这是 **headless GL 3.2 的真实 FBO 输出**，不是 UI 截图；源图是程序生成的
渐变 + 棋盘 + 对角线测试卡，所以 Glitch/VHS 的色偏在斜线上特别明显，属于预期。

## 3. 构建产物

```powershell
gradle.bat build --offline --console=plain
```

结果：`BUILD SUCCESSFUL`，`remapJar` 正常写出。

| 文件 | 大小 | SHA-256 |
| --- | ---: | --- |
| `bbsae-0.1.0.jar` | **396510** | `1CC9F7F8827B5926A2A9C005A9D1FFDC8B946C276C796C8D4415E87EF6DBA00C` |
| `build/libs/bbsae-0.1.0.jar` | 396510 | 同上（`remapJar` 产物） |

注意：Gradle 任务在 PowerShell 里可能以 `exit code 1` 结束，这是 `gradle.bat` 把
`JAVA_TOOL_OPTIONS` 写到 stderr 触发的 PowerShell 原生错误显示；日志里
`BUILD SUCCESSFUL` 与 `test-results` 才是真相。

## 4. 实机复验清单（接手后做）

本轮没有启动 Minecraft 客户端。建议：

1. 用 ASCII 路径的开发客户端（历史经验：BBS 在中文路径下读 `categories.json` 会失败）；
2. 打开合成器，选一个图层，效果库 → “屏幕 / 复古” → 依次应用 7 个效果；
3. 对每个效果拖 `amount`/`strength`/`angle`/`phase`，确认画面实时变化；
4. 选中 adjustment 层应用半色调，确认不再上下翻转（`identity()` 修复）；
5. 缩放/移动图层，确认网点、镜像接缝、Glitch 条带跟着层；
6. 按 F6 导出 2 秒，抽帧确认效果进入 MP4；
7. 如要复验 `depth_dof.focus`，需要真实世界 depth：在影片 overlay 里放一个有前景/背景的镜头，
   拖动“对焦深度”，确认清晰区域移动。

## 5. 未覆盖/剩余风险

- `depth_dof.focus` 只有代码路径修正，没有真实 depth 的自动测试；
- `EffectRenderer.supports` 与 `apply` switch 仍是手写两份；测试能防止漂移，但没有彻底消除；
- 半色调的“网点数量按项目宽度”在极端图层尺寸下可能显得过密/过疏，实机需要调默认值；
- 4K 性能、CPU 蒙版栅格化、Iris/Sodium 深度捕获等历史风险不变。
