# Credits / 鸣谢

## 特别感谢赞助者 / Sponsors — thank you for supporting development

BBS AE 能够持续开发、持续修 bug，离不开下面三位赞助者。
**你们的赞助支持了本插件的开发**：付费的开发时间、渲染与导出环节的真机验证、
以及每一轮修复之后的回归测试，都是用这些支持换来的。谢谢你们。

BBS AE keeps being developed and repaired because of the three people below.
**Their sponsorship funds this plugin's development** — the paid development time, the
real-client verification of rendering and export, and the regression suite that runs after
every fix. Thank you.

| # | 赞助者 / Sponsor | 感谢 / Thank you |
| --- | --- | --- |
| 1 | **【星辰久梦君_Star Dm Sov】** | 赞助并长期关注这个项目，感谢你的信任与支持。 |
| 2 | **血之书** | 感谢你的赞助，让效果栈与图层功能可以持续扩展。 |
| 3 | **Szv文森特37** | 感谢你的赞助与反馈，帮助合成器一路修到今天。 |

Names are permanent: they also appear in the in-game **Settings → 关于 / 赞助者** board
(`UISponsorBoard`), and this list is kept in sync with
[README.md](README.md#鸣谢--acknowledgements).

If you would like to support the plugin too, open an issue or a pull request on GitHub, or
contact the maintainer about sponsorship.

## Project / 项目

- **项目与维护 / Project & maintainer:** 终end ([@zhongend](https://github.com/zhongend))
- **合成器图标 / Compositor icon:** 超级大布叮

  The compositor icon (`src/main/resources/assets/bbsae/assets/textures/gui/compositor_icon.png`)
  was designed by **超级大布叮** and is used with permission for the BBS AE UI. The original
  32×32 source is stored at `docs/gallery/compositor-icon.png`; the UI copy is downscaled to 16×16
  to match BBS's icon grid. The 128×128 Mod Menu icon
  (`src/main/resources/assets/bbsae/icon.png`) is the same mark on a padded, rounded dark tile,
  so it remains visible on both light and dark launcher backgrounds.

- **运行环境 / Runtime:** Minecraft 1.20.1, Fabric Loader, Fabric API, BBS FS 2.7-1.20.1
- **随游戏打包的库 / Bundled libraries:** JOML, LWJGL, Gson (via their respective licenses)

## Photon

[Photon](https://github.com/Low-Drag-MC/Photon) by **KilaBash** is licensed
**CC BY-NC-SA 4.0**. This project only studied Photon's editor organization and rendering ideas
(standalone editor, own preview FBO, clean scene capture). **No Photon source code, shaders,
assets, textures, or data files are included or linked.** The implementation here is original
Java for Minecraft 1.20.1 / Fabric / BBS FS 2.7, and this project is not a Photon port or endorsed
by Photon. The full notice, including the Photon 2 port restrictions, is in
[PHOTON-NOTICE.md](PHOTON-NOTICE.md).

The rest of the project is MIT-licensed; see [LICENSE](LICENSE).
