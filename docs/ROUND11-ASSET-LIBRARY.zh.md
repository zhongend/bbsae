# Round 11：素材库不可用修复 + 背景模糊

## 1. 现象

素材库能打开，分类和文件名都在，但：

- 所有 Minecraft 物品/方块/实体缩略图显示成占位图标；
- 双击或“插入为新图层”后，图层没有可用纹理；
- 素材库面板在 1536×864 / GUI scale 2 下过高，底部按钮可能超出屏幕。

## 2. 第一性原因：`mc:` 被当成了资源命名空间

素材库给原版纹理的路径是：

```
mc:textures/block/acacia_leaves.png
```

`ImageTextureCache.fromResource` 原来这样解析：

```java
int colon = path.indexOf(':');
String namespace = colon > 0 ? path.substring(0, colon) : "minecraft";
String id = colon > 0 ? path.substring(colon + 1) : path;
```

于是得到：

```java
new Identifier("mc", "textures/block/acacia_leaves.png")
```

但 `mc` 不是 Minecraft 的资源命名空间，原版是 `minecraft`。  
`getResource` 永远返回空，所以**所有原版素材都加载失败**。日志可以直接看到：

```
Asset texture missing: identifier=mc:textures/block/acacia_leaves.png
```

### 修复

```java
if ("mc".equals(namespace))
{
    namespace = "minecraft";
}
```

`bbs:` 保持 `bbs`，项目素材保持文件路径。

## 3. 素材库背景模糊

`UIAssetBrowser.render` 在画面板前：

```java
InterfaceBlur.applyUnder();
context.batcher.box(area.x, area.y, area.ex(), area.ey(), 0x66000000);
```

- `InterfaceBlur.applyUnder()` 使用 BBS 自带的 Dual Kawase 界面模糊；
- 外圈再加一层 40% 黑色遮罩，保证即使关闭 BBS “界面模糊”也有 modal 对比度；
- 模糊只作用于背景，素材库面板本身仍然清晰。

证据：`docs/verify/round11-asset-library.png`。

## 4. 布局修复

原面板固定 **700×440**，在 1536×864、GUI scale 2（逻辑 768×432）下高度超出屏幕，
底部“插入/替换/关闭”可能点不到。现在：

- 面板 **620×400**；
- 分类按钮 6 列；
- 缩略图 5 列 × 3 行；
- 底部操作按钮上移到 y=348；
- 搜索框右移到 x=372，仍然完整落在面板内。

## 5. 相关文件

- `src/main/java/dev/bbsae/client/render/ImageTextureCache.java`
- `src/main/java/dev/bbsae/client/ui/UIAssetBrowser.java`

## 6. 验证

- 真实客户端打开素材库，原版方块缩略图正常显示；
- 背景模糊可见；
- `test` **65 / 65** 通过。
