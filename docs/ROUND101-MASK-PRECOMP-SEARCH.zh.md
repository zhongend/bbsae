# Round 101 — 蒙版全图层、预合成、效果库搜索与素材箱提示

> 版本 `0.99-hotfix65`。按项目约定未启动客户端截图；验证来自模型/纯函数测试、编译和全量
> headless 回归。

## 1. 蒙版 / 排除为什么只对形状图层有效

合成 shader 的蒙版/排除是 **alpha 运算**，与源图层是形状、文字还是纯色无关：

```text
Mask   : backdrop.a *= source.a
Exclude: backdrop.a *= (1 - source.a)
```

真正的限制在 UI：`maskSection()` 用 `if ("shape".equals(layer.type))` 包住
「用作蒙版 / 用作排除」，非形状图层只能看到「上方形状 → 蒙版」；没有形状可指时，
整组按钮消失，用户自然以为文字/纯色不能用蒙版。

修复：

- 任意 `hasPixels` 图层（文字、纯色、图片、视频、帧序列、预合成）都显示
  「用作蒙版（所有下方图层）」「用作排除（所有下方图层）」；
- 「上方图层 → 蒙版/排除」改为最近的有像素图层，不再限定 shape；
- 手动路径蒙版（矩形/椭圆/钢笔、羽化、扩展、不透明度、组合模式）原本就按
  `layer.masks` 工作，本轮确认所有图层类型都可添加；
- 清理所有“形状层”限定文案。

2D 路径用 `MaskCache.get(...)` 在层空间栅格化；3D 路径由
`CompLayerSourceCache` 先乘蒙版再烘焙；两条路径都不检查图层类型。

## 2. 多选预合成后图形消失

### 2.1 共享外部父级 / 遮罩丢失

`CompLayerOps.precompose` 把所选图层移入子合成后，新预合成层默认 `parentId=""`、
`trackMatteId=""`。如果原来的组挂在一个外部父级下，组会跳到错误位置；如果组使用外部
轨道遮罩，alpha 会变成全 1 或全 0，画面看起来“消失”。

修复：只转移所有成员共享的外部关系：

```text
if all selected layers share parent P (P outside selection) -> precomp.parentId = P
if all selected layers share matte M (M outside selection) -> precomp.trackMatteId = M
```

组内父级/遮罩关系保留；组外引用继续重指向预合成层。

### 2.2 缓存陈旧

预合成同时改变父层栈和嵌套内容。`CompRenderCache.signature` 包含递归预合成内容，
签名会变化；但绿帧缓存、帧服务器和 `CompLayerSourceCache` 还持有旧纹理。
`doPrecomposeSelection()` 现在在操作完成后调用统一缓存失效
（`CompRenderCache` / `CompFrameServer` / `CompLayerSourceCache` / `CompPrecache`
/ `ShapeCache` / `MaskCache`），保证下一帧重新渲染新的嵌套合成。

同时把 `duplicate()` 与 `doc.layers.remove()` 拆成两阶段：先复制全部成员，再创建子合成
并移除父层。中途复制失败时父层栈保持不变，不会再出现“删了一半、子合成没完成”的
消失状态。

### 2.3 回归测试

- 父时间 5–8 秒的组：`precomp.activeAt(5s/6s)`、子层 local time 0/0.5 可见；
- 共享外部父级/遮罩转移到预合成层；
- 预合成前后 `CompRenderCache.signature` 必须不同；
- 工程 snapshot/restore 后子合成与预合成层仍存在。

## 3. 效果库搜索栏

旧实现只依赖 `UITextbox` 的 `Consumer<String>` 回调。回调一旦因为焦点/布局/事件分发被
吞掉，输入框有字、列表却完全不变。

修复分三层：

1. **权威来源**：`UIEffectLibrary.render()` 每帧读 `search.getText()`，与
   `lastQuery` 不同就更新 `filter` 并重建列表；
2. **点击兜底**：父级 `subMouseClicked` 检查搜索框矩形并 `context.focus(search)`，
   防止早期布局留下零宽/陈旧命中区；
3. **纯函数**：`matchesFilter(definition, needle)` 同时匹配名称、id、分类，
   null 元数据安全；新增 4 个测试，遍历注册表断言每个效果都能按名称搜到。

## 4. 素材箱图形按钮悬浮提示

`UIProjectBin` 顶栏四个图标是手绘的（为了 22 px 高度和一致的视觉），因此没有 BBS
`UIIcon.tooltip` 的自动提示。新增 `drawHeaderTooltip`：

- 导入素材：图片 / 视频 / 音频；
- 新建素材箱（只归档，不复制文件）；
- 打开旧素材库（游戏贴图 / BBS 音效）；
- 刷新素材索引（外部文件改动后）。

鼠标悬停时在指针旁绘制深色提示条；下方空间不足自动翻到指针上方。

## 5. 验证

- 全量测试：**172 套件 / 1041 项 / 0 failures / 0 errors / 1 skipped**。
- 新增 `UIEffectLibraryFilterTest`；扩展 `CompLayerOpsTest` 的预合成父级、时间、
  签名、持久化回归。
- 未启动客户端；蒙版/预合成渲染路径与 2D/3D 共用同一套 `CompRenderer` /
  `CompLayerSourceCache` 代码，导出与预览共用渲染入口。
- 已知边界：3D 自由视口的预合成仍显示“预合成（草稿）”色板（既有性能取舍）；
  2D 预览和导出渲染真实嵌套合成。
