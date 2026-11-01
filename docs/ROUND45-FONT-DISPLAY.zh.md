# Round 45 — 字体导入/显示修复（v0.22.1）

用户反馈：**导入字体之后文字仍然不显示**。这一轮从"一个字符最终是怎么变成像素的"出发，
把「字体文件 → BBS 字形缓存 → MC 字体图集 → 文本排版 → 绘制」整条链重新核了一遍。

结论：这不是一个 bug，而是三个独立事实叠在一起，任何一个都会让"导入字体没效果"：

1. **字体文件是字符到字形的局部映射**（不是全字符集）：
   - `timesbd.ttf`（Times Bold）没有汉字；
   - 大多数中文字体没有 emoji；
   - 展示字体往往只有大写。
   Minecraft 的 `FontStorage` 对"字体里没有的字符"返回 `BuiltinEmptyGlyph.MISSING`：
   **没有字形渲染器、advance = 1 px**。所以"拉丁字体 + 中文文字"不是"回退到默认字体"，
   而是**整行什么都不画**（或者每字 1 px 挤成一团）。这正是用户看到的现象。
2. **中文系统字体大多是 `.ttc` 字体集合**（微软雅黑 `msyh.ttc`、宋体 `simsun.ttc`…）。
   BBS 的 stb 加载器只认"文件偏移 0 处的单个字体"，对 `ttcf` 头直接失败 →
   "已导入但 BBS 无法解析"，随后静默使用默认字体。
3. **字号与 BBS 字形图集的布局尺寸没有区分**：字号 200 的文字用 96 尺寸的图集放大 2 倍，
   并且混排两种字体时两者的原生高度不同（BBS 默认字体 ≈9 px，自定义字体按字号布局）。

## 1. 逐字符回退（核心修复）

新增 `dev.bbsae.client.render.FontCoverage`：

- 用 stb 的 `stbtt_FindGlyphIndex` 直接问字体文件"你有没有这个码点的字形"——
  这与 Minecraft/stb 构建字形图集时用的判断完全一致；
- 字体文件只读一次，stb 需要缓冲区常驻，因此做 4 个文件的 LRU，并在
  `CompRenderer.clearCaches()` 里随其他缓存一起释放；
- 文件缺失/损坏/无法初始化 stb 时按"画不出来"缓存，避免坏工程每帧读盘；
  BBS 尚未初始化（单元测试）时安全返回。

`LayerContentRenderer` 现在**逐码点**选择渲染器：

```
char c -> 自定义字体有该字形 ? 自定义字体 : BBS 默认字体
```

并且：

- **测量与绘制使用同一选择**（`lineWidth` 同样逐字回退），否则居中/对齐会漂移；
- **回退字形按比例缩放**：默认字体 9 px、自定义字体 43 px 时，回退字符以 43/9 ≈ 4.8 倍绘制，
  否则会看到"大号拉丁字母旁边一行小号汉字"；
- 排版改为按 **码点**（不是 UTF-16 char）迭代，emoji 不再被拆成两个替换字符。

## 2. `.ttc` 字体集合：提取第一个字体

新增 `FontImport.isCollection/faceCount/extractFace`：

- 读 `ttcf` 头 → 第 N 个字体的表目录偏移（TTC 里的表偏移是**文件绝对偏移**，直接可用）；
- 重新写出一个独立的 sfnt：12 字节头 + 表目录（重算偏移，4 字节对齐）+ 各表原始数据；
- 校验和原样复制（stb 和 BBS 都不校验；只有 `head.checkSumAdjustment` 会过期）。

导入流程因此变成：

| 输入 | 行为 | 结果文件名 |
|---|---|---|
| `.ttf` / `.otf` | 直接复制 | 原名 |
| `.ttc` | 提取 face 0，写成独立 sfnt | `微软雅黑-face0.ttf` |

系统字体选择器也列出 `.ttc`，并从第一个字体读取名字（不再显示 `msyh` 这类文件名）。

## 3. 字号与图集

- 布局尺寸仍是 `clamp(round(fontSize), 4, 96)`，但向 BBS `FontManager` 传入
  `oversample = ceil(fontSize / size)`：200 px 的文字现在用 192 px 的字形图集绘制，
  而不是 96 px 图集放大 2 倍；
- `FontManager.get` 的返回渲染器如果没有底层 `TextRenderer`，按"加载失败"处理，
  不再把 null renderer 交给 `Batcher2D`。

## 4. 让用户看得见原因（UI）

- 文字检查器在字体下方实时提示：
  - 找不到字体文件 → "已回退默认字体"；
  - 字体缺少当前文字里的字符 → "该字体缺少 N 个字符（示例…），这些字符已自动使用默认字体"。
- 导入完成的状态栏同样报告缺字数量；
- 字体下拉框改为**大小写不敏感**匹配（`Timesbd.TTF` 与 `timesbd.ttf` 视为同一字体，
  不再显示"默认"却实际使用自定义字体）。

## 5. 验证

新增可复现的验证工具：

- `-Dbbsae.debugFonts=true`：打印解析结果（link/文件/尺寸/oversample/高度/`H` 与 `新` 的宽度、
  覆盖率）以及每个码点最终选了哪个字体、advance 多少；
- `runClientView -PbbsaeFontShot=fontsheet`：用**真实合成渲染器**画一张 5 行对照表
  （拉丁/中文/标点 × 自定义字体 / 默认字体），输出到 `run/screenshots/fontsheet.png`；
  `-PbbsaeFontImportSrc=<系统字体路径>` 会先走一遍与编辑器完全相同的导入流程（含 TTC 提取）。

实测（本机）：

- `timesbd.ttf`（无汉字）：拉丁行是 Times Bold，中文行**自动回退并正常显示**（修复前整行空白）；
- `msyh.ttc`（微软雅黑）：提取为 `msyh-face0.ttf`，拉丁与中文都用雅黑正常显示，
  `coversH=true coversHan=true`。

单元测试（全量 278 个测试，0 失败）：

- `FontCoverageTest`：拉丁字体有 `A` 没有 `中`；缺字列表只包含真正缺的字符；空白/控制字符不算缺字；
- `FontImportTtcTest`：`ttcf` 识别、face 提取后仍是合法 sfnt、提取结果经 stb 仍有汉字字形、
  越界索引报错、系统字体列表包含 `.ttc` 且都有可读名字；
- `FontImportTest` / `SystemFontsTest` 更新为"集合是受支持的容器"这一新契约。

## 6. 已知问题

- 在 Windows + 微软雅黑 54 px 的实测中，左括号 `U+FF08` 不显示（同字体同尺寸的
  `U+FF09` 正常；stb 直接光栅化该字形有 189 个非空像素，说明字形数据本身没问题）。
  这属于 Minecraft 字形图集层面的个别字形问题，与本插件的字体选择/测量无关：
  排版宽度正确（41 px），换行与回退均正常。遇到时用默认字体或换一个字体即可。
