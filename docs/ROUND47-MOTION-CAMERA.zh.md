# Round 47 — AE 3D 逻辑 / AE 摄像机 / 效果修复 / 实体回放 / 钢笔工具 / UI 手势修复（v0.92）

> 本轮把用户八条需求里的六条落到可验收状态。详细技术报告见：
>
> - 效果：`docs/EFFECTS-FIX-REPORT.zh.md`、`docs/EFFECTS-AM-GAP.zh.md`、`docs/EFFECTS-AM-GAP.generated.md`
> - 实体回放：`docs/REPLAY-MODE-REPORT.zh.md`（+ `REPLAY-MODE-PLAN.zh.md`、`REPLAY-MODE-INTEGRATION.zh.md`）
> - 钢笔：`docs/PEN-TOOL-REPORT.zh.md`（+ `PEN-TOOL-PLAN.zh.md`）
> - 界面/面板：`docs/UI-PANEL-PLAN.zh.md`、`docs/DOCK-LAYOUT-PLAN.zh.md`
> - 协作/构建：`docs/TEAM-CONTEXT.zh.md`
>
> 全量验证：**90 suites / 511 tests / 0 failures / 1 skipped**（0.91 基线 72 / 379）。
> 真机验证：`docs/verify/round47-*.png`（见本文第 4 节）。

## 1. 需求覆盖

| 用户需求 | 本轮状态 | 证据 |
| --- | --- | --- |
| 1. 3D 摄像机 AE 逻辑 + 开发者设置切换 + 无摄像机不自动创建 + AE 摄像机操作/设置按钮 | ✅ 完成 | `CameraLogicModeTest`、`Camera3DTest`、设置 → 开发者页、预览右侧 5 个摄像机/工具按钮 |
| 2. 图层效果逻辑：3D 无效 / 文字翻转 / 重复效果无限 / AM 参数对齐 | ✅ a/b/c 完成；d 产出 gap 清单 + 11 处对齐，剩余 66 条按 P0/P1 排期 | `EffectOrientationTest`(4)、`EffectRepeatTest`(6)、`EFFECTS-AM-GAP.zh.md` |
| 3. 逐像素深度导致预览重影 | 🟡 默认关闭 + 缓存签名修复；主合成重影本体留待专门排查（0.87–0.89 已修复摄像机预览重影路径） | `CompRenderCache.signature` 含 perPixelDepth |
| 4. 效果列表并入预览右侧 + 其他交互问题 | ✅ 右侧检查器「效果控件」为唯一效果编辑区；另修 splitter/quick panel 手势冲突 | 见第 3 节 |
| 5. 实体回放编辑模式 | ✅ P0–P4 代码完成（真机待验细节见报告 §6） | `client/replay/` 11 文件 + 18 单测；dashboard「实体回放」入口 + 合成器 PLAYER 按钮 |
| 6. AE 钢笔工具 | ✅ 主体完成（动画路径通道重映射等列未完成项） | `CompPathGeometry`/`CompPenSession`/`UIPenTool` + 62 用例 |
| 7. 全项目 bug 排查 | 🟡 本轮修掉效果/蒙版/钢笔/面板/构建若干真实 bug；持续进行 | 各报告 |
| 8. UI 动效/自适应/拖拽组合 + 轨道左侧拖拽 bug | 🟡 手势 bug 已修；动效/布局/停靠基础设施就绪，`UIDockLayout` 拖拽组合下一轮接入 | `UiMotion` 扩展、`CompositorLayout`、`CompDockSource`、`DOCK-LAYOUT-PLAN.zh.md` |

## 2. 本轮新增/修改规模

- 新增主代码：`client/replay/`（11）、`UIPenTool`、`CompPathGeometry`、`CompPenSession`、
  `CompCameraOps`、`CompositorLayout`、`CompDockSource`、`UiStagger`、`UiPageTransition` 等。
- 新增测试：18 个测试类（效果方向/重复、钢笔 7 类、回放 4 类、摄像机逻辑、布局/动效/停靠持久化）。
- 文档/工具：`docs/*-PLAN/REPORT/GAP`、`_tools/gradle-run.ps1`、`_tools/am-gap/compare.py`、
  `_tools/gradle-env.ps1`。

## 3. 交互修复要点（人类视角）

1. **右键拖动不再是「会创建东西的手势」**：没有摄像机时只会提示；要摄像机就点显式的
   「新建摄像机」。AE 的摄像机工具是模式（左环绕/中平移/右推拉），不会误拖图层。
2. **文字加效果不再上下颠倒**：3D 图层效果在图层栅格上执行，V 轴翻转只保留一处。
3. **重复效果真正「无限」**：重复类是图层复制器，复制体只受合成边界限制；镜像/平铺/生成类
   保持 AE 语义（在图层内），需要整幅生成请用调整层。
4. **「拖数据把面板拖大」的两个根因都修了**：splitter 命中矩形不再覆盖左右分栏底部；
   快捷面板宽度只在按下其左边缘 4px 时改变。
5. **钢笔工具**：形状层编辑形状路径，其它层编辑蒙版；开放路径只显示轮廓，不参与遮罩。

## 4. 真机验证（截图）

| 截图 | 验证内容 |
| --- | --- |
| `docs/verify/round47-textfx.png` | 文字图层 + 发光 + 模糊：文字方向正常、效果生效（需求 2-b） |
| `docs/verify/round47-replay.png` | 「实体回放」编辑页：左侧原版回放列表、中部 3D 模型视口、底部原版 dope sheet（需求 5） |

> 若截图缺失，说明当轮客户端验证未跑通；以测试结果和报告中的真机清单为准，下一轮补跑。

## 5. 未完成 / 下一轮优先项

1. **需求 8-B：面板拖拽组合**（BBS `UIDockLayout` 直接复用）+ 需求 8-A 自适应接入
   （`CompositorLayout` 已备好，见 `DOCK-LAYOUT-PLAN.zh.md`）。
2. **需求 3：主合成逐像素深度重影**本体修复（当前默认关闭规避）。
3. **需求 2-d 剩余**：66 条缺失 AM shader 效果按 gap 文档 P0/P1 分批补齐；
   `selector` 参数类型（下拉/单选）需要 `AmEffectDef` + 检查器联合改动。
4. **需求 6 剩余**：动画路径「求值→重映射→回写关键帧」；逐点羽化/形状组等。
5. **需求 5 真机标定**：comp↔world 朝向、MC Camera 朝向、gizmo 拾取容差。
