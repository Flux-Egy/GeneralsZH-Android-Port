## Important: PC cross-play is temporarily disabled

**PC Crossplay is temporarily disabled to ensure compliance and stability. We are working closely with the
GeneralsOnline team and will re-enable it in the next update. Android-to-Android play is fully supported.**

This build is Android-only on GeneralsOnline, out of respect for the GeneralsOnline developers, who asked for
cross-play to be turned off:

- The game reports its own Android checksum and its real module list to GeneralsOnline -- it never presents
  itself as the PC client.
- Lobbies hosted on PC are not listed, and a join to one is refused.
- QuickMatch is hidden (its matchmaking pool is the PC client's). Custom lobbies work as usual.
- The game, the launcher's Home page and the GeneralsOnline account screen say so, in every language.

The lockstep checksums, the INI checksum and the mod rules stay fully active.

**To mod authors and anyone building from source:** do not turn cross-play back on in your builds.
GeneralsOnline treats an Android client in a PC game as a violation of its rules, and the accounts of players
using such a build risk being banned.

## Install

This is a new app, **C&C GZH-Flux** (`com.generalsx.flux.community`). It installs beside earlier Flux test
builds instead of replacing them, so their settings do not carry over: choose your game folder once on Home.

## What's new

### Online and stability
- **Interface packs work online.** Leikeze Indicators and other packs that only change button pictures keep
  the community patch, so GeneralsOnline lobbies let you in. Packs that change game rules still count as
  mods (checked against the patch, button by button).
- **Overlays no longer freeze the game.** Discord, Telegram or a call shown over the game used to stop it,
  network included, which got players dropped from online matches. Coming back after a phone call no
  longer ends the match either.
- **Disconnect reasons are logged** (`[GX-DISC]`), so a dropped match can be explained.

### Performance
- Fewer hitches in battles and menus: graphics buffers and object names are prepared ahead instead of making
  the game wait, and UI text pages are kept instead of being rebuilt.
- **Performance Guard and thermal tier:** phones that run too slowly or overheat through online matches keep
  to 4-player lobbies until a match runs smoothly.

### Touch and interface
- **"i" Info button:** tap it, then tap a unit, building or supply pile to see its name, owner, health or
  supplies (move it with the touch editor). The hover card (hold on a unit) now shows on touch screens too.
- **Performance HUD:** FPS, temperature, battery and a 30-second graph; move, resize and restyle it in the
  game (hold it), choose what it shows in the launcher.
- **Touch editor (the gear):** a selected button shows a gold handle at its corner -- drag it to change the
  button's width and height independently. While the editor is open, the "i" button and the HUD can be
  moved too; they start clear of the gear and the chat button.
- Camera & Selection Mode (1-finger or 2-finger), saved touch-button layouts, ten launcher themes, a polished
  launcher, a map editor, replay import, a Builders group in the hotkey editor, the Academy with game rules,
  a What's new sheet, Pioneers credits, and a link to the Flux Build Telegram.
- The Arabic launcher is now in simple Egyptian colloquial, with game terms kept in English.

## Symbols

`*-v1.4.0-community.symbols.txt.gz`: the dynamic symbol tables of the four shipped engines (Zero Hour and
Generals, 30 Hz and 60 Hz), with their BuildIDs, for reading crash reports.
