# Round 43 — 系统字体自动发现与导入（0.21.5）

## 1. 第一性原理：系统字体不能“原地”给 BBS 用

BBS 的 `FontManager` 只接受一种东西：

```text
Link = assets:fonts/<相对路径>
```

它通过 `AssetProvider.getFile(link)` 到 `config/bbs/assets/fonts/` 找文件。
`FontManager.get(Link, size, scale)` 没有“绝对路径”这个入口。

所以“自动调用系统字体”的正确做法不是把 `C:\Windows\Fonts\arial.ttf` 直接塞给 BBS，
而是：

1. 扫描系统字体目录，列出候选；
2. 用户选中后，把文件**复制到 BBS 自己的 fonts 目录**；
3. 走原有的 `Link` 加载路径（`FontManager.get` / 文本渲染）。

这和原版 BBS 的“把字体放进 fonts 文件夹再选”是同一个契约，只是把“手动找文件”
自动化了。

## 2. `SystemFonts`

新增 [SystemFonts.java](src/main/java/dev/bbsae/client/ui/SystemFonts.java)：

- Windows：`%WINDIR%\Fonts`、`%LOCALAPPDATA%\Microsoft\Windows\Fonts`
- macOS：`/System/Library/Fonts`、`/Library/Fonts`、`~/Library/Fonts`
- Linux：`/usr/share/fonts`、`/usr/local/share/fonts`、`~/.local/share/fonts`、`~/.fonts`
- 递归扫描，只收 `.ttf` / `.otf`；
- 读取 OpenType `name` 表，优先 nameID 4（全名），其次 nameID 1（家族），
  所以列表显示 “Microsoft YaHei” 而不是 `msyh`；
- 结果缓存；刷新按钮调用 `SystemFonts.refresh()`；
- 保护目录/损坏文件/伪扩展名不会让整个列表崩掉；
- TTC 集合不列出：BBS 的 stb 加载器无法寻址集合里的单个 face。

### 本轮实际抓到的一个 bug

第一次单测 `Arial should have a name table` 失败：`fullName` 返回 null。

根因：OpenType `name` 表的头部是

```text
format(2)  count(2)  stringOffset(2)
```

解析器却从 offset 0 读 count（读到的是 format=0），于是每个字体都是“0 条 name 记录”，
全部退化成文件名。修复为 count 在 offset 2、stringOffset 在 offset 4。
`SystemFontsTest` 用真实 `arial.ttf` 锁住了这个行为。

## 3. UI

文本图层的字体区域新增 **“系统字体…”**：

- 打开一个可搜索的 `UIStringOverlayPanel`，列出系统 TTF/OTF 的**字体名**；
- 选中后走 `importFontFile()`：复制到 BBS fonts、走原版 `FontManager` 验证、设置
  `layer.fontName`；
- 已存在的同内容字体复用，不同内容同名加 `-2`/`-3`；
- “刷新字体列表”同时刷新 BBS 字体目录扫描和系统字体缓存；
- 仍保留“打开 BBS 字体文件夹…”给手动拖入的工作流。

## 4. 验证

- 单测 **254/254**，0 failures / 0 errors。
- 新增 `SystemFontsTest`：
  - 只接受 TTF/OTF；
  - 真实 `arial.ttf` 的 name 表解析；
  - 垃圾文件/TTC 头返回 null 而不抛异常；
  - Windows 已安装字体列表非空且按名称排序。
- 版本 `0.21.5`：`bbsae-0.21.5.jar`，898944 字节，
  SHA-256 `2DF642993B9B511FA6B3045F837E363F2CEE3EA1886B9CE4ED2758B2FE81D864`。
