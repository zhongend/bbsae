# Photon attribution / Photon 引用声明

This project studied **Photon** by **KilaBash** and its contributors for its editor organization
and rendering ideas:

- Photon: <https://github.com/Low-Drag-MC/Photon>
- License: **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International**
  (CC BY-NC-SA 4.0) — <https://creativecommons.org/licenses/by-nc-sa/4.0/>
- Photon `LICENSE`: <https://github.com/Low-Drag-MC/Photon/blob/main/LICENSE>

## What was used

Only **ideas and structure** were studied, in particular:

- a standalone editor with its own preview framebuffer instead of an overlay on the host editor;
- capturing a clean scene colour/depth frame for a post-processing preview;
- effect/mask preview toggles in an editor UI.

The implementation in this repository is original Java for Minecraft 1.20.1 / Fabric / BBS FS
2.7. **No Photon source code, shaders, assets, textures, or data files are included or linked.**
The project is not a Photon port and is not affiliated with or endorsed by Photon or KilaBash.

## License boundary

This project is MIT-licensed. Photon remains under CC BY-NC-SA 4.0; its license and the additional
Photon 2 port restrictions are not changed or overridden by this project. If any Photon-derived
code or asset is added in the future, that part must be distributed under CC BY-NC-SA 4.0 with
attribution and the same license, and any port to a Minecraft version other than 1.21.x requires
prior written consent from the original author.

The reference research notes are in [`docs/photon-research.zh.md`](docs/photon-research.zh.md).
