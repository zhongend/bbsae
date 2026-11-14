# Round 94 — wiggle 平滑/种子、连续一元符号、视口模型透明度

> 版本 `0.99-hotfix58`。最小改动。

## 1. wiggle(frequency, amplitude)

### 旧问题

```java
deterministicNoise(frame * frequency, 37F) * 2 - 1
```

`deterministicNoise` 是 `fract(sin(...))` 哈希，整数采样点之间完全去相关。`frame * f`
让每帧跳到新的哈希格，所以频率只是换随机序列；固定 seed 37 还让所有属性/图层锁步。

### 新实现

- `Context` 增加 `default int seed() { return 0; }`；
- `x = frame / fps * frequency`；
- `smoothNoise(x, seed)`：取 `floor(x)` 与 `floor(x)+1` 两个 `deterministicNoise`，映射到
  [-1,1]，用 smoothstep 插值；
- 结果乘 amplitude；
- 种子：
  - 位置/旋转表达式：`(layer.id + "/" + property).hashCode()`；
  - 不透明度表达式：`(layer.id + "/opacity").hashCode()`。

这样频率控制每秒变化次数、相邻帧连续，不同图层/属性各有序列；`wiggle(2,50)` 幅度 ≤50。

## 2. 连续一元符号

`unary()` 的 `-`/`+` 分支从 `power()` 改回 `unary()`：

- `--5 = 5`
- `+-5 = -5`
- `-(2+3) = -5`
- 同时保持 `-2^2 = -4`、`2^-3 = 0.125`

`power()` 底数仍是 `primary()`，指数继续 `unary()`，右结合不变。

## 3. 3D 视口回放模型透明度

`CompViewportRenderer.drawReplayModels`：

```java
// old: layer.opacity
float copyOpacity = layer.opacityFrame(context.frame) * alpha;
```

视口与合成/导出的关键帧透明度一致。不透明度表达式仍只有合成路径的 `opacityAt` 处理，
本轮只修关键帧残留（用户指定最小改动）。

## 验证

- 全量测试：**165 套件 / 1007 项 / 0 failures / 0 errors / 1 skipped**。
- `CompExpressionTest` 覆盖：
  - 相邻帧差值 < 20（幅度 50、2Hz）；
  - 不同属性 seed 结果不同；
  - 幅度 ≤50；
  - `--5`、`+-5`、`-(2+3)`、`2^-3`。
- 未启动客户端。
- 候选包：`bbsae-0.99-hotfix58.jar`（3,954,041 bytes，SHA-256
  `2BEC1F0B1B4FD3A0D4663C1DE734FD7457E0B806A2AF3D3EF5C368DF8429EF13`）。
