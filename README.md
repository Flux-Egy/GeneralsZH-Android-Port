# Flux Build: The Ultimate C&C Generals Zero Hour Android Port

**Flux Build** is a fork and continuation of [MYSOREZ's GeneralsZH Android Port](https://github.com/MYSOREZ/GeneralsZH-Android-Port). It runs Command & Conquer: Generals – Zero Hour natively on Android phones and tablets. Our focus is **performance, stability and community features**: a smooth picture on modern high-refresh screens, reliable online matches, touch controls you can shape to your hands, and map editing on the device itself.

> You need your own copy of the game data (for example the Steam edition of *Command & Conquer: Generals – Zero Hour*). Flux Build does not include any EA game files.

---

## Versions in this repository

| Branch | Version | Highlights |
|---|---|---|
| `v1.4.4-community` | 1.4.4 (12187) | [Android] lobbies, no Performance Guard kicks, fixed “i” and “G” buttons, Ctrl+number fix, mouse edge scrolling, keys on the loading screen, frame limiter |
| `v1.4.2-community` | 1.4.2 (12162) | Logic Time Scale render rate, tag-free CRC, adaptive GSR, hitch fixes, Size/Opacity sliders |
| `v1.4.1-community` | 1.4.1 (12157) | Native World Builder, Windowed mode (black bars), custom resolutions |
| `v1.4.0-community` | 1.4.0 (12152) | Android-only community release, HTML map editor, Egyptian Arabic |

---

## What's New in Flux Build (The Evolution)

### PC-like performance with the Logic Time Scale
- The game draws at **60, 75, 90, 100, 120 or 144 fps, your screen's maximum, or uncapped**. The camera, scrolling, touch and interface move every frame.
- The game logic keeps its own clock: 60 Hz, or 30 Hz on the Classic engine.
  - **Online and LAN:** the network sets the pace, so lockstep and checksums are untouched.
  - **Skirmish and campaign:** the speed follows TheSuperHackers' **Logic Time Scale**, so the game speed stays exactly what you chose.
- The render rate never drops below the logic rate, so a fast screen can never slow the match down.

### Engine optimizations

| Problem | Fix |
|---|---|
| Terrain jitter and black flashes while panning or zooming | The terrain draw area is sized once per map, with a sensible cap |
| Stutter when trees come into view | The tree buffer is refilled per terrain region instead of on every screen-edge crossing |
| A hitch the first time each effect appears | Shaders are pre-built during loading (shader pre-warm) |

### Online stability: tag-free CRC
- A "Mismatch" against players on other Android builds turned out not to be a desync. One build added a GeneralsOnline revision tag to its checksum and the other did not.
- Flux Build now always sends the **tag-free checksum**, the same format as other current Android builds.
- Replays recorded with either format are still verified correctly.
- *PC cross-play is disabled at the request of the GeneralsOnline developers; the community builds are Android-only online.*

### Adaptive GSR (upscaling)
The picture is scaled to the screen by the method that suits the chosen resolution:

| Scale (rendered → screen) | Method |
|---|---|
| Exactly 1:1 | Pixel-exact copy, no filter |
| Below 1× | Bilinear |
| Up to 2× (for example 720p on 1080p) | Snapdragon Game Super Resolution (Adreno) |
| Beyond 2× | Bilinear, with a hint to choose a higher resolution |

The launcher shows which method the next game will use.

---

## UI, Controls & Launcher

- **Custom frame limiter:** chips for 60 / 75 / 90 / 100 / 120 / 144 / Screen max / Uncapped, plus a custom number. Rates your screen cannot show are hidden.
- **Advanced touch editor:**
  - Size and Opacity sliders for the selected button, or for all buttons when none is selected.
  - Full key mapping: letters, numbers and Ctrl / Shift / Alt modifiers.
  - Background dimming in the in-game editor.
  - Your own default layout, plus export and import.
- **Windowed mode (black bars):** the game keeps a 16:9 picture with black bars. Touch is mapped to the picture, and touch buttons can sit in the bars.
- **Custom resolutions:** presets (Native, 1080p, 720p, 540p) or your own width × height.
- **13 languages:** English, Arabic (Egyptian), Interslavic, German, Spanish, Persian, French, Korean, Polish, Portuguese (Brazil), Russian, Ukrainian and Chinese.

---

## Map Editors & Tools

- **Native World Builder (1.4.1 and later):** EA's World Builder running natively on the device, with the full 3D view.
  - New maps get their player starts automatically.
  - The playable-area border is shown.
  - Every save checks the player starts, so a map never opens on a black screen.
  - **Save as** protects the original maps; official maps are always saved as a copy.
- **HTML map editor:** a lightweight, beginner-friendly 2D editor for quick edits and low-end devices.
- **Replays:** import replay files from the Maps & Mods screen.

---

## Building

The Android build uses CMake with the NDK for the engines and Gradle for the launcher. See `AGENTS.md` and `docs/` for the toolchain, build scripts and conventions.

---

## Credits

| | |
|---|---|
| **Original Android port** | [MYSOREZ](https://github.com/MYSOREZ/GeneralsZH-Android-Port) |
| **Engine contributions** | [TheSuperHackers](https://github.com/TheSuperHackers) (GeneralsGameCode, Logic Time Scale), [fbraz3](https://github.com/fbraz3) (GeneralsX, DXVK work), and the GeneralsOnline team |
| **Flux Build** | The Flux Build Team |

Command & Conquer, Generals and Zero Hour are trademarks of Electronic Arts. This project is not affiliated with or endorsed by Electronic Arts. The source code is released under the GPL v3 with EA's additional terms; see [LICENSE.md](LICENSE.md).

---

## Join our Telegram

News, test builds, help and feedback: **[t.me/FluxBuild](https://t.me/FluxBuild)**
