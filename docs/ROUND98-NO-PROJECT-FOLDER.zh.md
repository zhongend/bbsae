# Round 98 — 外链音频不创建工程目录

> 版本 `0.99-hotfix62`。

## 问题

`addAudioClip` 在判断链接/复制之前执行：

```java
Path folder = CompStore.assetRoot(project.id).resolve("audio");
Files.createDirectories(folder);
```

即使用户只是外链一个 WAV/MP3，工程里也会出现空的 `assets/audio/`。严格来说没有复制文件，
但仍然是外链路径对工程的写入，和“真外链”语义不一致。

## 修复

- 移除顶部的无条件 `createDirectories`；
- 仅 `else if (direct)` 的复制分支创建目录；
- 压缩格式的 `convertAudioToWav` 已经在目标父目录上调用 `createDirectories`；
- 外链分支完全不创建工程目录。

## 验证

- 全量测试：**165 套件 / 1007 项 / 0 failures / 0 errors / 1 skipped**。
- 未启动客户端；真机可检查导入外链音频后工程 `assets/audio` 不存在或保持原状。
- 候选包：`bbsae-0.99-hotfix62.jar`（3,958,207 bytes，SHA-256
  `DF704E2635182E6480249C2EE1C265A4F123E6129125FA81BD711C7622686C33`）。
