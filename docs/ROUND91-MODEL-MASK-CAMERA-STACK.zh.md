# Round 91 — 模型蒙版预乘、相机 override 栈、单层预合成相机

> 版本 `0.99-hotfix55`。未启动客户端；验证来自纯逻辑回归与全量测试。

## 1. 模型蒙版：预乘 / 直通 alpha 不一致

### 现象

3D replay 模型加路径蒙版后，蒙版边缘或半透明区域**发亮/泛白**，看起来像蒙版没有正确压暗；
普通图层蒙版正常。

### 根因

- `ReplayActorPass` 的模型 target 用普通 alpha blending 渲染，RGB 是**预乘**的；
- `PassRenderer.mask` 使用共享 `COMPOSITE` shader：`UseBackdrop=0` 时输出
  `vec4(srcRgb, s.a * mask)`，只改 alpha，不乘 RGB；
- 对普通直通图层，后续 blend 用 alpha 覆盖，RGB 保持 authored 值是正确的；
- 对预乘模型，RGB 已含 alpha，mask 只改 alpha 后 RGB 相对新 alpha 过亮；半透明/羽化边缘
  尤其明显。

### 修复

`CompRenderer.applyModelMask`：

1. `PassRenderer.unpremultiply(content)` 得到直通临时 FBO；
2. 在直通 FBO 上执行 `PassRenderer.mask`；
3. 返回直通蒙版结果，释放临时 FBO；
4. 调用方 `compositeModelTarget(..., premultiplied=false)`，用 `drawInto` 直通合成；
5. `applyModelPostEffects(..., premultiplied)` 只在输入仍是预乘时才反预乘，蒙版后的 glitch
   不会二次反预乘。

所有权：模型原 target 是 `ReplayActorPass` 的持久缓存，不释放；只释放临时直通 FBO 和
mask 输出。

## 2. 相机 override 栈

### 现象

回放自由/环绕视角下，进入或经过一个**折叠预合成**后，后续 3D 图层的视角突然回退默认相机；
或父级显式相机在子合成 pop 后失效。

### 根因

`CompScene` 的 `CAMERA_OVERRIDE` 是单槽 ThreadLocal：

```java
pushCamera(A);          // 回放自由视角
  pushCamera(B);        // 折叠预合成
  popCamera();          // remove() → A 也没了
camera(ctx);            // 默认相机
```

不是栈，嵌套 push/pop 必然丢外层。

### 修复

- `ThreadLocal<Deque<CompCamera>>`（`LinkedList`，允许 null 抑制项）；
- `pushCamera` 压栈，`popCamera` 弹栈，空栈才 remove；
- `camera()` 读栈顶；
- 新增 `hasCameraOverride()`、`suspendCameraOverride()`、`restoreCameraOverride()` 给后续
  嵌套“临时使用子相机”的场景；
- 新增 `CameraOverrideStackTest`：push outer → push inner → pop → 仍 has outer → pop → 无。

## 3. 单层预合成相机

- `CompStore.precompose` 原来只复制源图层，不复制父级活动相机；多选 `CompLayerOps.precompose`
  上一轮已修，单层仍会丢透视。
- 现在 `CompStore.precompose` 调用 `CompLayerOps.inheritActiveCamera(doc, sub, in, fps)`；
- 若源图层本身就是 camera，跳过继承，避免子合成里两个相机；
- 新增 `singleLayerPrecomposeCarriesTheActiveCamera`。

## 4. 验证

- 全量测试：**163 套件 / 999 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端；请在工程中验证：
  1. 半透明/羽化边缘的 3D 模型路径蒙版是否不再发亮；
  2. 回放自由视角 → 经过折叠预合成 → 后续 3D 图层是否仍保持外层相机；
  3. 单层预合成 3D 层是否保留透视/位置；
  4. 2D/3D 预览与导出一致。
- 候选包：`bbsae-0.99-hotfix55.jar`（3,952,962 bytes，SHA-256
  `EF56363468C59944B0A9D537BC3A3B8886E36FFD26C1681D3102B290363E0F3F`）。
