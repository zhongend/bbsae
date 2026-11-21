# Round 123：动态文字的 3D 光栅边界

> 版本 `0.99-hotfix90`。本轮未启动客户端。延续 hotfix89 的“排查其他 bug”，修的是 3D 文字
> 在动态内容下的边界估算。

## 根因

3D 文字走的是 `CompLayerSourceCache.bounds()` → 估算源纹理矩形 → `LayerContentRenderer.drawText()`
真正绘制。绘制端用的是：

```java
TextContent.resolve(layer, context.time, fps, frame)
```

但边界端原来直接用：

```java
String text = layer.text == null ? "" : layer.text;
```

当文字层使用**变量、表达式或逐字动画**时，`resolve()` 得到的字符串可能比模板更长。边界仍按
模板估算，3D 文字纹理就会偏窄，右侧（或两侧）被裁切；2D 文字直接画在合成目标上，所以这个
bug 只在 3D/导出源纹理路径出现。

## 修复

- `bounds()` 的 text 分支改用与绘制端完全相同的 `TextContent.resolve(layer, context.time,
  comp.fps, frame)`；行数、最宽行、字距与描边都基于真正要画的字符串。
- 仍复用 hotfix88/89 的 tracking 估算（每行、每字间一次 `max(0, tracking)`，多行取最宽行）。

## 验证

- 本轮未启动客户端；
- 全量测试：**204 suites / 1237 tests / 0 failures / 0 errors / 1 skipped**；
- 构建产物：`bbsae-0.99-hotfix90.jar`。
