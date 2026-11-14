# Round 105 — 3D 预合成标志的深入修复

> 版本 `0.99-hotfix69`。本轮专门排查 hotfix68 的遗留问题。

## 1. hotfix68 还漏了什么

hotfix68 做了两件事：

1. 删除 3D 工作区的蓝色不透明「预合成（草稿）」板；
2. 普通预合成用自己的合成相机/投影压平，避免父相机双重投影。

但预合成**图层对象**本身仍是默认的 `threeD=false`。当用户多选 3D 图层执行
“预合成”：

- `CompLayerOps.precompose` 创建的新 `precomp` 没有复制 3D 标志；
- 在 3D 工作区里，`CompScene.usePerspective` / `sortsIn3D` 把该层当作屏幕固定的
  2D 贴片；
- 它不会跟随父级摄像机，也不参与 3D 排序。

结果仍然像“预合成在 3D 世界里显示错误”：内容有了、透明了，但位置/透视不跟相机。

## 2. 修复

### 2.1 多选路径

`CompLayerOps.precompose` 在创建预合成层时：

```text
if any selected layer.threeD -> precomp.threeD = true
```

2D 选择保持 `threeD=false`，不改变原有 2D 工作流。

### 2.2 单层路径

`CompStore.precompose(project, doc, source, ...)`：

```text
precomp.threeD = source.threeD
```

单层预合成也继承源图层的 3D 状态。

### 2.3 与相机的关系

- 预合成内部仍按 hotfix68 的规则：普通预合成用**自己的**合成相机/投影压平，
  透明底，再作为父级空间里的平面；
- 折叠变换仍是显式选项；
- 本轮只改变预合成图层在父合成中的空间归属，不改变嵌套内容本身的渲染方式。

## 3. 边界

- 已有工程中用户显式设为 2D 的预合成层不会被强制改回 3D；只影响新建预合成。
- 多选混合 2D/3D 图层时，只要有 3D 成员就设置 `threeD=true`；嵌套内容仍按预合成
  自己的相机压平。若需要精确保留每个子层的 3D 关系，使用「折叠变换」。
- 3D 预合成的内部内容仍是平面图像（AE 的非折叠预合成语义），不是逐层 3D 合并。

## 4. 验证

- `CompLayerOpsTest`：
  - 两个 3D 图层预合成后 `precomp.threeD == true`；
  - 单层 3D 源预合成后 `precomp.threeD == true`；
  - 2D 单层预合成保持 `false`。
- `CompModelTest` 增加 2D 源保持 2D 的断言。
- 全量测试：**175 套件 / 1064 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端截图；3D 工作区/回放编辑器建议实机确认。

## 5. 建议实机检查

1. 多选两个 3D 图层（带 Z 位置/旋转）→ 预合成；
2. 进入 3D 工作区，环绕摄像机，确认预合成整体跟随相机而不是屏幕固定；
3. 回放编辑器自由/环绕/摄像机预览各看一次；
4. 2D 图层预合成仍保持原来的屏幕固定行为；
5. 如需嵌套 3D 逐层关系，打开「折叠变换」对比。
