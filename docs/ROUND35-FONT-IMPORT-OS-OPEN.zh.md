# Round 35 — 字体导入、打开文件夹与文件对话框（0.20.1）

这一轮修的是“按钮点了没反应”这一类问题：它们不是 UI 画错了，而是**操作系统的调用失败了，
但代码把失败吞掉了**。

## 1. 自定义字体导入

### 1.1 旧实现的问题

`importFontFile` 做三件事：把文件复制到 `config/bbs/assets/fonts`、把文件名塞进
`layer.fontName`、弹一句“已导入”。三个环节都可能“看起来成功，实际无效”：

1. **文件名被削成 ASCII**：`思源黑体.ttf` → `____.ttf`。几个中文字体全部同名，后导入的
   覆盖前一个；字体下拉里显示 `____.ttf`，用户根本认不出。
2. **只检查扩展名，不检查容器**：TTC 字体集合、WOFF/WOFF2、损坏文件都能被复制并选中。
   BBS 的 stb 加载器随后返回 null，文字静默回退到默认字体——这就是“导入没效果”。
3. **没有验证复制结果**：即使复制失败/目标不可读，状态仍然显示成功。
4. **字体下拉列表可能滞后**：列表来自 `FontManager.getFontLinks()`；新文件已经落盘，
   但列表还没刷新时，用户看不到刚导入的字体。
5. **内置浏览器从“图片”目录开始**：字体在 `C:\Windows\Fonts`，用户要手动翻很久。

### 1.2 新实现

新增 `FontImport`（纯逻辑，可单测）：

- **保留真实文件名**：只替换路径非法字符（`\ / : * ? " < > |` 和控制字符），
  中文、空格、括号都保留。
- **容器头检查**：`00 01 00 00` / `true` / `typ1` / `OTTO` 放行；`ttcf` 明确提示
  “TTC 不支持，请导出单个 TTF/OTF”；`wOFF`/`wOF2` 提示使用原始字体；其他文件
  报“不是 TTF/OTF”。
- **不覆盖别的字体**：同名但内容不同 → `name-2.ttf`、`name-3.ttf`；内容相同 →
  复用已有文件，重复导入不会制造副本。
- **复制后用 BBS 自己的加载器验证**：`FontManager.accept(...)` 清缓存，再
  `get(link, 32, 1)`；只有真的解析出 `FontRenderer` 才设置 `layer.fontName`。
  OTF/CFF 或损坏文件会提示“已复制但无法解析”，而不是假装成功。
- **下拉列表双来源**：`getFontLinks()` + 直接扫描 fonts 文件夹，新字体立即出现。
- **字体浏览器默认目录**：Windows 下优先 `%WINDIR%\Fonts`，其次是
  `%LOCALAPPDATA%\Microsoft\Windows\Fonts`；上次浏览的是字体目录时继续用它。

## 2. “打开文件夹”按钮

### 2.1 根因

两处按钮（渲染队列的“打开文件夹”、导出完成后的自动打开）都是：

```java
if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder);
catch (Exception ignored) {}
```

`Desktop` 需要 AWT 桌面环境。整合包/启动器完全可能用
`-Djava.awt.headless=true` 启动 JVM，或运行在没有桌面支持的会话里；此时
`isDesktopSupported()` 为 false，或者 `open` 抛异常，而 `catch` 把它吞了。
按钮看起来就是“点了没反应”。

### 2.2 修复

新增 `OsOpen`，按可靠性顺序尝试：

1. **Minecraft 自己的** `Util.getOperatingSystem().open(file)`——Windows 走
   `rundll32 url.dll,FileProtocolHandler`，macOS 走 `open`，Linux 走 `xdg-open`，
   不需要 AWT；
2. AWT `Desktop`（非 headless 且支持 OPEN 时）；
3. 直接启动 `explorer.exe` / `open` / `xdg-open`。

三步都失败时返回 false，调用方显示“无法打开导出文件夹：<路径>”，不再静默。
`openFolder` 会先 `createDirectories`，所以导出目录还不存在时也能打开。

## 3. 原生文件对话框

`NativeFileDialog` 在 Windows 上优先用 `GetOpenFileNameW`（以游戏窗口为 owner）。
旧实现有两个盲点：

- **没有 owner 的 Win32 对话框**和 tinyfd 一样可能跑到游戏窗口后面；现在
  `windowHandle() == 0` 时跳过 Win32，直接用 AWT 的 always-on-top owner。
- **失败被当成取消**：`GetOpenFileNameW` 在某些远程桌面/策略环境下立即返回
  false 且 `CommDlgExtendedError() == 0`，旧代码当作“用户取消”，既不回调也不
  打开内置浏览器。现在若对话框在 250ms 内返回“取消”，视为后端失败，转 AWT；
  AWT 也失败才打开内置浏览器。

## 4. 验证

- 单测 **234/234**，0 failures / 0 errors。
- 新增 `FontImportTest`：容器识别、中文名保留、扩展名、同名去重、真实
  `C:\Windows\Fonts\arial.ttf` 头检查（不存在时自动跳过）。
- 版本 `0.20.1`：`bbsae-0.20.1.jar`，872598 字节，
  SHA-256 `B58948A3E5A40AE0E971EA2637ADBCC2CF2A538139D0351F68C65851791F275F`。

## 5. 已知边界

- BBS 的 stb 加载器不支持 TTC 和部分 CFF OTF；导入会明确提示，需先转换为 TTF。
- 字体文件复制到 BBS 动态资源目录；如果整合包把 assets 放在只读位置，会显示
  复制失败的原因。
