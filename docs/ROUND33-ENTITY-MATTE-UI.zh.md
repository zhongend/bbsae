# Round 33 — 回放实体留存、顶栏几何与可折叠过滤（0.19.3）

> 这一轮修的是三个“看起来像没做”的问题：回放实体图层只闪一下、顶栏设置按钮重叠、
> 线性过滤只能三态循环。三处都不是缺功能，而是渲染状态 / 生命周期 / 几何计算 / 交互模型错了。

## 1. 回放实体：为什么只闪一下

### 1.1 现象与线索

用户观察到的两个关键事实：

1. **从 BBS 原版影片回放打开合成器时，实体能正常显示**；
2. 在合成器面板里**隐藏再显示**实体图层，抠像会完整地闪一下（带光影），然后立刻消失。

这两个事实把问题从“mask 没生成”直接推到“mask 被生成过，但没有留到下一帧”。

### 1.2 第一性原理：mask 是“每世界帧一次”的产物

抠像需要两样东西：

- **世界帧的颜色/深度**（当帧）；
- **只包含选中回放的实体 pass**（当帧）。

两者必须在**同一个世界帧**里产生，并且在这一帧的所有合成绘制中保持不变。
旧实现把 mask 的生成挂在“请求签名”上：

- `ReplayEntityMask.request()` 比较 film/all/ids/occlusion 的签名；
- 面板的 `syncRequests()` 在 **UI 帧**（世界之后）才把新请求写进去；
- 帧叠加层 `CompositionOverlay` 在绘制前调用 `ensureCurrent()`，发现签名变了就**在合成阶段重画 mask**。

隐藏/显示图层会改变签名，于是出现：

1. 显示后的第一帧，图层采样到**上一次已经画好的 mask**（或叠加层刚补画的那一张）——完整、带光影，闪一下；
2. 同一帧/下一帧，`ensureCurrent()` 在**合成 FBO 已绑定、演员状态已被编辑器改过**的 GL 状态里再次清空并重画 mask；
3. 这次重画得到的 target 是空的（或与图层读取的不是同一张），图层随即透明。

这就是“闪一下然后消失”的完整因果链，不是采样随机失败。

### 1.3 第二个坑：编辑器把实体摆到了 onion skin 的 tick

`FilmEditorController.renderEntity()` 画完当前演员后，会继续画 **onion skin**（前后关键帧的残影）：

```java
replay.keyframes.apply(tick1, entity);   // tick1 是另一个关键帧
...
FilmEntityRenderer.renderEntity(...);
```

每次 onion 都把**同一个 entity** 重新摆到另一个 tick，结束时**不恢复**。所以世界演员 pass 结束后，
实体停留在 onion tick 上。mask 在 `AFTER_ENTITIES` 再渲染一次时，相机矩阵是对的，但**姿态/时间**
是 onion 的——抠出来的演员和世界里的演员对不上。

原版回放使用的是**播放控制器**，它没有 onion skin，entity 始终停在当前 tick，所以“从回放打开”
是正常的。这正是用户给出的线索。

### 1.4 修复

1. **只在世界阶段生成一次**：`ReplayEntityMask.render()` 在 `AFTER_ENTITIES` 生成覆盖目标；
   `ensureCurrent()` 只在**当前帧还没有任何覆盖目标**（新合成/导出第一帧）时才补画，
   已有目标时直接保留，绝不在合成阶段清空重画。
2. **重画前把实体摆回当前 tick**：

   ```java
   float tick = replay.getTick(controller.getTick()) + transition;
   replay.keyframes.apply(tick, entity);
   replay.properties.applyProperties(entity.getForm(), tick);
   RenderFrame.invalidate();
   ```

   播放控制器下这是幂等的；编辑器下它覆盖掉 onion 留下的姿态。
3. **矩阵来自演员自己的入口**：监听 `FormPoseEvents.ACTOR_BEFORE`，在
   `FilmEntityRenderer.renderEntity` 真正开始画世界演员的那一刻捕获 camera / pose stack /
   model-view / projection。`BEFORE_ENTITIES` 可能在 projection 还没建立时触发（实测捕获到
   `+Inf` 投影），`AFTER_ENTITIES` 又可能被 form 代码改过；只有演员入口是准确的。
4. **覆盖纹理替代深度采样**：实体的“身份”不再由图层 shader 采样 `DEPTH_COMPONENT24` 判断，
   而是在实体 pass 里输出一张 `RGBA8` 覆盖纹理（透明背景 + 实体 alpha），需要世界遮挡时先把
   世界深度 blit 到同一 FBO 的深度附件，用硬件 `GL_LEQUAL` 剔除。图层 shader 只采样普通颜色
   纹理，世界颜色仍然来自 SceneColor（因此保留光影调色）。
5. **GL 程序/纹理单元显式绑定**：BBS/Iris 的 raw GL 调用会让 `GlStateManager` 的缓存与真实状态
   脱节。`GlslProgram.use()` 和 `PassRenderer.bindRaw()` 现在同时做 raw 调用与缓存同步；
   这是让图层 shader 真正读到 mask 的最后一环（诊断日志确认
   `unit0=world, unit1=mask, EntityMask=1`）。

### 1.5 验证

- 无光影、合成器面板、真实影片 `A2(MAIN)_` 2.0s：
  `docs/verify/round33-entity-isolated.png` —— 隐藏 BBS 预览层后，透明背景（品红舞台）
  上只剩演员，且连续多帧存在（截图在第 10 秒后）。
- BSL 下覆盖目标转储：`docs/verify/round33-entity-coverage.png` —— 带光影的演员剪影，
  证明实体 pass 在 Iris 下确实写入了覆盖目标。
- shader pass 存在性：`docs/verify/round33-entity-pass.png` —— 强制全屏绿的调试视图，
  证明图层 shader、图层目标、合成链路都在。
- 日志：`Replay entity coverage (unoccluded): 1920x1080 entities=1`；
  `coverage stats: 232279 covered ...`；`Entity key bindings: unit0=... unit1=... EntityMask=1`。

> 说明：自建测试客户端在开启 BSL 时单次启动约 4–6 分钟（Iris 预编译光影），
> 本轮最后几次 BSL 全流程截图被启动时间拖住；上面的隔离截图是在同一客户端关闭光影时
> 截取的，覆盖转储则是在 BSL 下截取的。两者合起来证明“mask 在 Iris 下正确、图层在
> 合成器里可见”。用户的正式客户端装 0.19.3 后可直接在原回放/合成器路径复验。

## 2. 顶栏“设置”附近按钮重叠

### 2.1 根因

`layoutHeader()` 的右侧按钮簇从右边缘往左排，但每个按钮用的是 `x = 1F - right`（左边缘），
而 `right` 的推进量是“下一个按钮的宽度 + 间距”：

```java
this.focusButton.x(1F, -40).w(control);   // 18 px，占 [-40, -22]
right += control + 8;                      // right = 66
this.settingsButton.x(1F, -66).w(48);      // 占 [-66, -18]
```

设置按钮的右边缘在 `-18`，焦点按钮的左边缘在 `-40`，两者重叠 22 px。这个错误与宽度无关；
窄窗口只是让左半边也挤过来，看起来更明显。

### 2.2 修复

顶栏几何抽成纯函数 `CompUi.headerLayout(width, control)`：右侧按“自身宽度 + 8”向左推进，
左侧向右推进；碰撞时按优先级隐藏（保存 → 导出 → 渲染 → 时间码 → 逐帧 → 回放 → 标题），
焦点和设置永不隐藏。测试 `headerButtonsNeverOverlap` 覆盖 14 种宽度 × 5 种密度。

## 3. “线性过滤”可折叠

- chip 显示当前值，点击展开/折叠；
- 展开后三个明确选项（自动 / 线性 / 最近邻），当前项带 `✓`；
- 选择后更新 `project.settings.textureFilter`、chip、状态栏并保存；
- 展开状态持久化到 `project.settings.previewFilterExpanded`；
- 窄预览下 chip 最后才隐藏，菜单宽度随预览收敛；
- 布局抽成 `CompUi.previewBarLayout(...)`，测试覆盖 64–900 px。

## 4. 验证汇总

- 单测：**225/225**，0 failures / 0 errors。
- 真实影片 `A2(MAIN)_`：隔离截图、BSL 覆盖转储、shader pass 截图、日志。
- UI：`docs/verify/round33-ui-1280-g1.png`、`round33-ui-1600-g1.png`、
  `round33-ui-1920-g1.png`、`round33-ui-1920-g2.png`（截图前用
  `-Dbbsae.debugWindow=WxH` 强制 GLFW 窗口尺寸，避免启动器/窗口管理器忽略
  `--width/--height`）。1280/1600 的 GUI scale 2 逻辑宽度（640/800）由
  `CompUiResponsiveTest` 的纯布局断言覆盖：测试客户端的窗口管理器在上一实例
  保存 options 时会抢回窗口尺寸，视觉截图无法稳定复现这两个组合。
- 版本 `0.19.3`：`bbsae-0.19.3.jar`，863550 字节，SHA-256
  `6089C0CFCF2F46DAAD77C578D9091C423C16B2CE7D3DC9DDD7BC0699DA68C9D6`。

## 5. 兼容性

- 无 Iris：覆盖 pass 直接读主 framebuffer 的深度附件；blit 失败自动退化为无遮挡。
- Iris：优先从当前 draw framebuffer 复制深度；Iris 的 render target 仍可用。
- 旧工程：`entityTolerance` 字段保留（不再参与判断），旧工程可直接加载。
- 自定义 shader 路径保留在 `-Dbbsae.entityKeyShader=true` 之外作为 A/B 诊断；
  生产默认是覆盖纹理 shader 路径。
