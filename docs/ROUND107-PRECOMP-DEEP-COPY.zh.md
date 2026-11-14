# Round 107 — 复制预合成时生成独立嵌套合成

> 版本 `0.99-hotfix72`。未启动客户端截图；模型/序列化测试 + 全量回归验证。

## 1. 现象

复制（Ctrl+D 或 Ctrl+C/V）一个预合成图层后，副本和原图层仍然播放同一份嵌套合成：

- 在副本内部改子层、效果、关键帧，原预合成也变；
- 两个预合成的时间轴看起来是同一份；
- 删除/修改其中一个的嵌套内容会影响另一个。

## 2. 根因

`CompLayer.duplicate()` 只复制字段，`precompId` 是一个字符串引用，副本仍指向原
`CompDocument`。项目里没有新的 comp 对象，所以两个图层共用同一个 `precompId`。

## 3. 深拷贝设计

### 3.1 文档深拷贝

`CompStore.duplicateDocument(source)`：

1. 把 source 放入临时 `CompProject`；
2. `snapshot(temp)` 序列化（内部会 `saveTracks`，因此 transient 的 XYZ 曲线、
   总变换、效果/蒙版轨道都进入 JSON）；
3. `restore(json)` 反序列化、sanitize、migrate、hydrate；
4. 返回新的 `CompDocument`，id 仍为 source.id。

这复用工程保存/撤销的同一套序列化，避免手工漏掉 `CompDocument` 字段（音频、
标记、tracker、参数、工作区、活动摄像机等）。

### 3.2 预合成树克隆

`CompLayerOps.duplicateLayer(project, layer)`：

- 普通图层：`layer.duplicate()`；
- `type == "precomp"` 且 `precompId` 有效：
  - `duplicateDocument()` 克隆子合成；
  - 生成唯一 id（`<原id>_copy`）和名称（`原名称 副本`）；
  - 加入 `project.comps`；
  - 递归扫描克隆子合成里的每个 precomp 图层，克隆它的目标并重写
    `precompId`；
  - 用 `done` map 保证同一份嵌套合成在一个克隆树里只克隆一次；
  - 用 `visiting` 检测循环，环上的引用置空，避免无限递归。

这样副本拥有自己的完整 comp 树；原预合成不受影响。

### 3.3 所有复制入口

- Ctrl+D / 图层“复制”：`UICompositorPanel.duplicateLayer` →
  `CompLayerOps.duplicateLayer(project, ...)`。
- 多选复制：`CompLayerOps.duplicate(project, doc, selection)`。
- Ctrl+C/Ctrl+V：剪贴板保存 template，粘贴时
  `CompLayerOps.paste(project, doc, templates, index)` 再深拷贝。
- 轨道右键复制/菜单复制同样走这些入口。

### 3.4 兼容旧调用

`duplicate(doc, selection)` 和 `paste(doc, templates, index)` 保留，内部转调
project=null 的版本：没有工程上下文时无法创建新 CompDocument，这是模型层
兼容 API；UI 和工程内路径全部传 project，因此实际复制不会共享。

## 4. 验证

- `CompLayerOpsTest`：
  - `copyingAPrecompGivesTheCopyItsOwnNestedComposition`：
    copy.precompId != 原 id、comps 增加、克隆子层编辑不影响原层；
  - `copyingANestedPrecompClonesTheWholeTree`：嵌套 precomp 也克隆；
  - `pastingAPrecompAlsoClonesItsNestedComposition`：Ctrl+V 路径。
- 全量测试：**176 套件 / 1075 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端截图；建议实机检查：
  1. 复制一个预合成，在副本内部改子层，原预合成不变；
  2. 复制含嵌套预合成的预合成，确认树完整且独立；
  3. Ctrl+C/V 重复粘贴，每次都是新的子合成；
  4. 删除原预合成后副本仍可播放（只要副本仍在项目中）。

## 5. 边界

- 复制预合成会真实增加项目里的 composition 对象。大嵌套树复制会占用更多工程
  体积，这是“独立副本”语义的必然成本。
- 没有工程上下文的旧模型重载无法克隆，保留为兼容 API；UI 不使用它。
