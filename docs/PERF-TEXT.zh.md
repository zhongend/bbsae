# 文字渲染性能审计修复（P0-1 + P0-2）

任务板：`task-29`。写域：`FontCoverage.java`、`LayerContentRenderer.java`、`src/test/java/dev/bbsae/client/render/**`、本文件。未打开客户端，未改动 CompRenderer / CompLayerSourceCache / MaskCache / ShapeCache / Effect* / comp / UI / CompPrefs / export / build.gradle。

## 结论

| 指标 | 修复前（200 字形、带描边、单层） | 修复后 |
| --- | --- | --- |
| `covers()` 次数 | 600–800/帧（每字形 × 5 次描边/填充） | **0/帧**（布局缓存命中后绘制路径不再询问覆盖） |
| 文件系统调用 | 约 3,600–5,600 次/帧（每次 covers 含 2×`isFile`、2×`length`、`getCanonicalPath`、`lastModified`、`length`） | **0/帧**；冷启动每字体 1 次 `readAllBytes` + 1 次 canonicalPath |
| 文字层布局 | 每帧 `split("\\n")` + `resolve` + `layout` + 每行重新测宽 | 每帧一次 key 查找；**仅 text/字体/字号/tracking/wrapWidth/wrap/align 变化时重算** |
| wrap 复杂度 | O(n²)（每词重拼 candidate、每字符重测前缀） | O(n)（手写 split + 前缀宽度累加，实测与旧算法逐行一致） |
| GPU draw call | 200 字形 × 5 = **1,000/层/帧** | **字体图集数（通常 1，混合回退字体 2）/层/帧**，与行数、描边、字形数无关 |
| 每帧临时 String | 每字形 1 个 `new String(Character.toChars)` | 0（布局缓存 + BMP `char→String` 表） |
| 每字形 Quaternionf | 每动画字形 1 个 | 1 个复用实例 |

`-Dbbsae.debugTextPerf=true` 时每 5 秒输出两行统计（covers/IO/布局命中/布局耗时/绘制耗时/TextRenderer 调用数/真实批次数）。

## 根因（原实现）

### P0-1 覆盖查询 = 每字形一次文件系统风暴

`FontCoverage.covers(File,int)` 的热路径：

1. `isReadable(file)` → `isFile()` + `length()`；
2. `lookup(file)` 又 `isReadable()` 一遍 → 再 `isFile()` + `length()`；
3. `file.getCanonicalPath()`（Windows 单次 20–100 µs）；
4. `file.lastModified()`；
5. `file.length()`；
6. 命中缓存后还要比较 modified/length。

而 `LayerContentRenderer` 的 `glyphFont()` 对每个字形、每种绘制偏移都调用一次：200 字形 × 5 次（4 描边 + 填充）= 1,000 次/帧，即数千次文件系统调用。单文字层实测 5–40 ms/帧，是全项目最大的单点。

`FontCoverage.resolve()` 与 `LayerContentRenderer.resolve()` 在名字缓存命中后仍然每帧 `file.isFile()`，又叠加两次 stat。

### P0-2 布局与绘制

- `drawText()` 每帧：`TextContent.resolve`、`fontFor()`（BBS 字体管理器查找）、`layout()`；
- `layout()` 每帧 `text.split("\\n",-1)` 编译正则；`split(" ")`、`line + " " + word`、每词两次 `lineWidth()`、溢出时对每个字符重测 `chunk + c` → O(n²)；
- 每个字形 `new String(Character.toChars(cp))`、每个动画字形 `new Quaternionf()`；
- `drawGlyph()` 对每个字形最多 5 次 `Batcher2D.text()`。BBS 的 `Batcher2D.text()` 内部是 `flushBatch()` + `DrawContext.drawText()` + `DrawContext.draw()`，即**每次调用就是一次独立 draw call**，200 字形 = 1,000 draw call/层/帧。

## 修复

### 1. FontCoverage：绘制入口解析一次，之后零 IO

- 新增 `FontCoverage.coverage(File)`，在 `drawText()` 入口解析一次，后续 `covers(Coverage,int)` 只查内存。
- `IdentityHashMap<File,Coverage>` 快路径：同一个 `File` 实例第二次查询 **不做任何 canonicalPath/stat/read**。同一路径的新 `File` 实例走 canonicalPath 一次后复用同一 `Coverage`。
- 每个字体首次查询后写入 `long[1024] covered` + `long[1024] known`（Unicode BMP 位图，16 KB/字体）。命中的“有/无字形”都缓存；非 BMP（emoji）用小型 HashMap。
- 不可读文件缓存为无 native 内存的 sentinel；不可解析字体保持旧语义（stb 初始化失败 → 视为“能画”，抛异常 → “不能画”），也全部缓存。
- `resolve(name)` 命中不再 `isFile()`；失败结果做 1 秒 TTL 负缓存，避免拼错字体名时每帧扫描 fonts 目录。字体导入/资源重载仍通过 `LayerContentRenderer.clearFonts()` / `CompRenderer.clearCaches()` → `FontCoverage.clear()` 失效。
- 始终在线的轻量计数（canonicalPath/stat/read/stb/lookupHit）供测试证明“零 IO”；`-Dbbsae.debugTextPerf` 额外计时 covers 并定期打日志。

### 2. 布局缓存 + 手写 wrap

- `LruCache<LayoutKey,TextLayout>`，上限 64 个文本块。键 = 文本 + 字体名/字体渲染器 identity + 回退字体 identity + fontSize + tracking + wrapWidth + wrap + align + segments（本渲染器暂无样式分段，恒 0，预留失效位）。命中只做一次哈希/相等比较。
- `LayoutKey` 里存完整文本而不是仅哈希，避免 32 位哈希碰撞导致错误复用；`String.hashCode()` 由 JDK 缓存，静态文本每帧零成本。
- `splitNewlines()`/`splitSpaces()` 手写，逐字符语义与 `String.split("\\n",-1)` / `split(" ")` 完全一致（含空字符串、连续空格、尾部空行）。
- `wrapLine()` 不再拼接/重测字符串：逐词扫描一次，把 advance 以与旧 `lineWidth()` **相同的加法顺序**累加（候选词先接在行前缀和之后逐字符加），`tracking * (count - 1)` 始终最后一次性乘加。这样即使回退字体产生非整数 advance，浮点舍入也与旧实现逐位一致，`> maxWidth` 的行断点不会改变；溢出断字同样只扫描一遍、维护前缀和。
- 布局结果缓存每行文本、宽度（用与旧实现相同的求和顺序）、字形表、连续同字体 run。绘制路径不再测宽、不再取覆盖、不再分配字形字符串。
- `new Quaternionf()` 改为静态复用 `ROTATION.rotationZ(...)`；`char→String` 使用 64K BMP 引用表。

### 3. 批绘制：1 次 GPU draw 而不是 1,000 次

`Batcher2D.text()` 每次都会 `DrawContext.draw()`，无法批量。改为直接走 `DrawContext` 内部的同一套 API：

```java
font.getRenderer().draw(text, (int) x, (int) y, textColor(color), false,
    matrices.peek().getPositionMatrix(), context.getVertexConsumers(),
    TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
...
context.draw();   // 每层一次
```

- 所有 `TextRenderer.draw()` 追加到同一个 `VertexConsumerProvider.Immediate`；最后一次 `DrawContext.draw()` 按字体图集 RenderLayer 各出一次 draw call。200 字形单字体 = 1 次 GPU draw（4 描边 + 填充全部在同一 buffer）。
- 非动画文本：连续同字体、scale=1、tracking=0 且“逐字形宽度之和 == 整串宽度”的字符合并成一个 run，每行每描边偏移一次 `TextRenderer.draw`（正常 200 字形一行 = 5 次 CPU 调用 + 1 次 GPU draw）。
- 不满足合并条件的 run（tracking≠0、回退字体缩放、宽度校验失败、`§` 格式化符）逐字形绘制，但仍在同一 buffer 中，GPU draw call 数不变。
- 动画文本保持旧顺序（每个字形的 4 描边 + 填充相邻），只是最后统一 flush。
- 逐字形像素位置保持一致的关键：`DrawContext.drawText()` 会把 x/y 截断为 int，旧路径每个字形单独截断；合并 run 只在“无 tracking、scale=1、advances 为整数且整串宽度等于逐字形宽度之和”时启用，此时小数部分恒定，截断后的位置逐字形相同。
- 复刻了 `Batcher2D.text()` 的浅色表面白色变暗逻辑（`BBSSettings.lightSurfaces()` + `darkenWhite`），避免批量后颜色变化。
- 复刻了旧路径结束时的 `RenderSystem.depthFunc(GL_LEQUAL)` 状态。
- 空白字符：旧路径从不绘制空白；合并 run 只把普通空格 `U+0020` 放进字符串（无像素、仅推进），其它空白（`\t`、`\r`…）继续跳过。`§` 单独成 run，避免合并后被解析成格式码。
- 字体渲染器**不缓存**：BBS 会在 30 秒空闲后驱逐字体、在字体文件变化时失效条目，逐帧 `FontManager.get()` 只是哈希查找；若返回的 `FontRenderer` identity 变化，就调用 `FontCoverage.invalidate(file)` 丢弃旧 cmap，并让布局键自然失效。`clearFonts()` 同时清空布局缓存和 `FontCoverage`。

## 测试

新增（均可无 GL 运行）：

- `src/test/java/dev/bbsae/client/render/TextLayoutTest.java`（13 个用例）
  - 与旧 `layout()` 逐字节对比：22 组文本 × 10 种宽度 × 4 种 tracking × wrap 开/关；250 组随机文本 fuzz；另加 300 组**小数宽度** fuzz（回退字体缩放产生的非整数 advance，浮点求和顺序也逐位一致）；逐行完全一致；
  - `splitNewlines`/`splitSpaces` 与 `String.split` 的 1,200 组 fuzz 对比；
  - 行数/字形数/行宽与旧算法一致；
  - 布局缓存命中、任一参数变化失效、LRU 访问顺序/淘汰/上限 64；
  - run 合并规则：宽度和校验、tracking、回退缩放、字体切换、空格、`§`；
  - `char→String` 复用。
- `src/test/java/dev/bbsae/client/render/FontCoverageIoTest.java`（6 个用例）
  - 首次 `covers` 有 read/canonicalPath，第二次起 **canonicalPath=0、stat=0、read=0**；
  - 同一路径的新 `File` 实例不重读字体文件；
  - BMP 位图：26 个新码点第一次恰好 26 次 stb，第二次 0 次；缺失字形同样缓存；
  - 不存在的文件只 stat 一次，之后零 IO；
  - `clear()` 后重新读取；
  - `missingGlyphs()` 复用同一 coverage。

命令（互斥锁构建，未开客户端）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\bbsae-build\_tools\gradle-run.ps1 test --offline --console=plain
```

结果：**完整测试套件通过**（新增 19 个用例；`FontCoverageTest` 等旧用例不回归）。测试机为 Windows，使用 `C:\Windows\Fonts\arial.ttf`；无系统字体时相关用例通过 `Assumptions` 跳过。

可选 GL 像素守护：本次没有开客户端，因此没有自动化像素对比。建议在有客户端的环境用既有字体验证入口做 A/B：

```powershell
./gradlew runClientView -PbbsaeFontShot=fontsheet -PbbsaeFontShotFont=timesbd.ttf
```

对比 0.96 与当前版本的字体检查表截图，重点看描边、回退字体（汉字）、斜体字形重叠处。理论上允许的差异仅限“同一行内先画完所有描边偏移再画填充”带来的 ≤1 px 抗锯齿覆盖顺序差异；逐字形坐标、颜色、字宽、行距、对齐均保持一致。

## 剩余 draw call / 热点估算

- GPU draw call = 每次文字层 `DrawContext.draw()` 的字体图集 RenderLayer 数：
  - 默认字体：1；
  - 单自定义字体：1；
  - 自定义字体 + 回退字体（如拉丁字体里的汉字）：2；
  - 与行数、字形数、描边数无关。证据里的 1,000/层/帧 降到 1–2/层/帧。
- CPU `TextRenderer.draw()` 调用数 = 每个 run 的绘制次数 × (描边?5:1)：
  - 200 字形、单字体、无动画、无 tracking：每行 1 个 run → 5 次；
  - 每出现一次字体切换/回退缩放/`§` 断一次 run；动画文本退化为逐字形（仍只有 1–2 次 GPU draw）。
- 每帧文字路径的分配：`LayoutKey`、`GlyphBatch`（含一个小 IdentityHashMap）、事件计数对象等，量级为每层几十字节；字形 String/布局/字体渲染器全部命中缓存。
- 仍未消除的固有成本：布局 miss 时每字形一次 `FontRenderer.getWidth()`（与旧实现相同，但只发生一次）；字体渲染器本身受 BBS `FontManager` 的 atlas 光栅化影响；`§`、tracking≠0、逐字动画文本仍走逐字形 CPU 路径。

## 风险与边界

- 缓存故意不再逐帧校验文件 mtime/size：字体在运行中被替换/删除后需要 `clearFonts()`（字体导入与 `CompRenderer.clearCaches()` 已覆盖）。这是“零 IO”的前提，已写入类注释。
- `resolve()` 失败负缓存 TTL 1 秒；同名文件在 1 秒内出现时最多延迟 1 秒生效。
- 同一行内描边层的绘制顺序从“每字形：4 描边 + 填充”变为“每 run：4 描边 + 填充”。字形互不重叠时像素完全一致；斜体/花体等相邻字形包围盒重叠处可能有 ≤1 px 的混合顺序差异（在任务允许的 1 px 抗锯齿容差内）。动画文本仍保持旧顺序。
- 布局缓存把 `FontRenderer` 引用保存在值里；BBS 重新加载同一文件时渲染器 identity 变化会让布局键失效，同时丢弃该文件的 cmap。若外部代码绕过 `FontManager` 替换字体对象，仍需调用 `clearFonts()`。
- 像素守护仍是可选的：本次未开客户端，自动化测试不覆盖 GL 光栅化。
