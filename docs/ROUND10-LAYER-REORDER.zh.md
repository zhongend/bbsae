# Round 10：图层左侧拖拽排序

## 1. 用户问题

图层顺序只能通过右键菜单“上移一层 / 下移一层”或快捷键一层层挪，图层多时非常慢。
用户要求：**拖拽图层左侧**直接调整顺序。

## 2. 实现

### 2.1 时间轴行序

`UICompTrackTimeline.rows()` 返回的是**视觉顺序**（最上面的行是最后绘制的顶层）：

```java
List<CompLayer> layers = new ArrayList<>(doc.layers);
Collections.reverse(layers);
```

因此拖拽目标不能直接当 `doc.layers` 的下标使用。

### 2.2 拖拽手柄

每个轨道头左侧 14px 画三条短横线作为 grip：

- 鼠标进入时变成上下拖拽光标；
- 按下后记录 `MODE_REORDER`、源图层、当前行；
- 上下移动时计算**插入槽**（0 = 最上面，rows.size() = 最下面），画一条高亮插入线；
- 松开时提交。

### 2.3 顺序模型

`CompDocument.moveLayerToDisplayIndex(layer, displayIndex)`：

1. 复制 `layers` 并反转成显示顺序；
2. 从显示列表移除源图层；
3. 目标槽在源之后时减一（移除导致下标左移）；
4. 插回显示列表；
5. 反转写回 `doc.layers`；
6. 返回是否真的改变。

`UICompositorPanel.moveLayerToDisplayIndex` 负责刷新轨道、Inspector、Sheets、保存工程。

## 3. 测试

`CompModelTest.layerReorderUsesVisualRowsInsteadOfDocumentOrder`：

- 初始 doc `[A, B, C]`，视觉 `[C, B, A]`；
- 把 C 拖到第 2 槽 → doc `[A, C, B]`；
- 把 A 拖到最上面 → doc `[C, B, A]`；
- 原地拖动返回 false。

全量 **65 / 65** 通过。

## 4. 相关文件

- `src/main/java/dev/bbsae/client/ui/UICompTrackTimeline.java`
- `src/main/java/dev/bbsae/client/ui/UICompositorPanel.java`
- `src/main/java/dev/bbsae/comp/CompDocument.java`
- `src/test/java/dev/bbsae/comp/CompModelTest.java`

## 5. 使用

在时间轴左侧轨道头，按住最左边的三条横线上下拖动；蓝色横线表示插入位置。  
眼睛、锁、3D、父子链接图标仍然各自可点击，不会被排序手势抢走。
