# PLAN-032: Native World Builder (EA's World Builder on Android)

Status: approved 06/11/2026 ("Approved. Proceed.", aggressive scope). Branch `v1.4.1-community`
(from `v1.4.0-community`). Milestone 1 (JNI skeleton, 3D view, round-trip harness; 12153) tested by the
owner on the phone 07/11/2026. v1.4.1-community (12154): the editing tools, the PC-style screen,
momentum scrolling, the mission editor (players, teams, scripts), the launcher's Maps & Mods section
and the navigation style switch.

## Scope (owner, 06/11/2026)

- **Drop the HTML editor.** `MapEditorActivity.java` and `assets/mapeditor/index.html` are removed;
  the Tools tab's map editor row now opens World Builder.
- **Native foundation from day one:** `libfluxwb.so` and the mandatory round-trip harness.
- **Native 3D UI immediately:** `WorldBuilderActivity` over d3d8gles: terrain, W3D objects,
  waypoints and player starts.
- **Core tools in v1.4.1:** terrain raise/lower, object placement, waypoint placement.
- **Absolute isolation:** its own `:wb` process; no engine source changed; maps written only by EA's
  code (`DataChunkOutput`, `WorldHeightMapEdit::saveToFile`, `CompressionManager`).

## How it is built

EA's World Builder (`GeneralsMD/Code/Tools/WorldBuilder`, 99 files, ~60,800 lines) is an MFC
program. Its **logic files are compiled unchanged**; what they need from MFC and from the dialogs is
supplied by small stand-ins in `GeneralsMD/Code/Tools/FluxWB/compat/`, searched before EA's
`include/`, so a stand-in header wins over EA's.

| Part | Source | State |
|---|---|---|
| Terrain document, map writer | EA `WHeightMapEdit.cpp` (unchanged) | compiled |
| Terrain renderer | EA `WBHeightMap.cpp` (unchanged) | compiled |
| Preview .tga | EA `MapPreview.cpp` (unchanged) | compiled |
| Waypoints, paths, triggers, water, boundaries in 3D | EA `DrawObject.cpp` (unchanged) | compiled |
| Tool base (brush maths, cell indexes) | EA `Tool.cpp` (unchanged) | compiled |
| Document (`CWorldBuilderDoc`) | `src/FluxWBDoc.cpp`: EA's `Serialize`/`OnOpenDocument`/`OnNewDocument`/`validate`/waypoint code, MFC plumbing removed | ported |
| 3D view (`WbView3d`) | `src/FluxWBView.cpp`: EA's `initWW3D`/`setupCamera`/`updateLights`/`invalObjectInView`/`render` | ported |
| Start-up | `src/FluxWBCore.cpp`: EA's `InitInstance` subsystem list with `StdLocalFileSystem`/`StdBIGFileSystem`, OpenAL null device | ported |
| MFC subset | `compat/StdAfx.h` (CString, CPoint, CRect, AfxMessageBox, cursors: no-ops) | new |
| Dialog stand-ins | `compat/TerrainModal.h`, `LayersList.h`, `mapobjectprops.h`, `MainFrm.h`, `PolygonTool.h`, `BuildListTool.h`, `MeshMoldOptions.h`, `WaterTool.h` | new |
| SDL main, touch camera | `src/FluxWBMain.cpp` | new |
| Java bridge | `src/FluxWBJni.cpp` + `FluxWBBridge.h` (command queue in, events out) | new |
| Activity | `WorldBuilderActivity.java` (SDLActivity, `:wb`, tool rail, open/save) | new |

Rule for every later file: bring EA's `.cpp` over unchanged and extend the stand-ins; port a body only
where it is an MFC class (document, view, dialogs), and then follow EA's function line by line, marking
each difference `Flux:`.

### Isolation (checked)

- No file under `GameEngine/`, `GameEngineDevice/`, `Libraries/`, `Core/` changes. The only build
  change outside `Tools/FluxWB` is `GeneralsMD/Code/CMakeLists.txt` (`add_subdirectory`, Android only)
  and the packaging scripts. TouchInput.cpp, chat, map transfer, MTU, CRC, overlays and rotation are
  untouched; the editor's gestures are its own (FluxWBMain.cpp).
- `:wb` loads `SDL3` + `fluxwb` only, never `libmain*.so`; it follows the launcher's render backend
  choice (GLES, ANGLE or Vulkan) and the Zero Hour game folder.
- The game's lockstep never sees the editor. A map's online identity is the CRC of its whole file
  (`Core/GameEngine/Source/GameClient/MapUtil.cpp:141`), and map transfer sends the file itself, so a
  map only has to be valid and the same file on every machine.

## Round-trip harness (Phase 0, mandatory)

`fluxwb_roundtrip --game <dir> --user <scratch> --out <scratch> [map...]` (built with libfluxwb):
open each map with EA's loader, save it with EA's writer (B), open B and save again (C).

| Verdict | Meaning |
|---|---|
| IDENTICAL | B equals the original's chunk data byte for byte |
| UPGRADED | B differs from the original but B == C; the report lists chunk versions, and whether a name was dropped from the chunk name table |
| UNSTABLE | B != C -- saving is not repeatable; fails the run |
| REFUSED | the map uses terrain textures this game lacks; saving is refused (EA would drop those tiles) |
| FAILED | unreadable or unwritable; fails the run |

Result on the owner's game folder (06/11/2026, every map the game sees: MapsZH.big, the base game's
maps.big, Maps/): **166 maps: 8 identical, 151 upgraded, 0 unstable, 7 refused, 0 failed.** Of the
151: 139 differ because the chunk name table is numbered in a different order (chunk and Dict keys
are written as ids from that table, so the bytes move while the content does not), the rest by EA's
own format upgrades and load fix-ups (BlendTileData v6/v7 -> v8, PolygonTriggers v3 -> v4, waypoint
renumbering, unique ids, tile optimisation). No map lost a chunk or a name. B == C for every map means
the map EA's loader reads back from our file serialises exactly like the one it read from the
original. The 7 refused maps are campaign maps of the base game (chi01, usa07...) using textures only
the base game's Terrain.ini has.

Run it again after any change to FluxWB, and before every release.

## Mobile UI (from day one)

- Left rail (UiKit): Open (my maps / official maps), New, Save, Reset camera. The tools join it.
- Camera: one finger moves the map, pinch zooms (EA's wheel offset), twist turns, two fingers pan.
- Tools (next): one finger applies the selected tool; two fingers stay the camera; a placement cursor
  above the finger with snap; options (brush width, feather, height, object list with W3D preview) in
  a bottom sheet; undo/redo always on screen.
- Saving: `<user data>/Maps/<name>/<name>.map` (+ EA's `.tga` preview); a folder is written only if
  World Builder created it (`.flux-world-builder` marker), so no downloaded or official map is replaced.

## Size

libfluxwb.so: 289 MB unstripped, 41.7 MB stripped, ~18.6 MB compressed in the APK (about one engine).

## v1.4.1-community additions (07/11/2026)

| Part | Source | State |
|---|---|---|
| Undo/redo, all undoable edits | EA `CUndoable.cpp` (unchanged) | compiled |
| Select/move/rotate/delete | EA `PointerTool.cpp` (unchanged) | compiled |
| Trigger areas | EA `PolygonTool.cpp` (unchanged) | compiled |
| Raise/lower, smooth, set height | EA `MoundTool.cpp`, `FeatherTool.cpp`, `BrushTool.cpp` (unchanged) | compiled |
| Objects, waypoints | EA `ObjectTool.cpp`, `WaypointTool.cpp` (unchanged) | compiled |
| Option panels' logic | `src/FluxWBPanels.cpp`: EA's ObjectOptions/WaypointOptions/LightOptions/MapObjectProps statics | ported |
| Tool registry | `src/FluxWBTools.cpp` (EA's app: one instance per tool, a current one) | new |
| Mission editor | `src/FluxWBScripts.cpp`: EA ScriptDialog/PlayerListDlg edits on a copy, OK = SidesListUndoable | ported |
| Screen | `WbEditorUi.java`, `WbScriptEditor.java` | new |
| Include redirect | `relfix/`: EA's `../../GameEngine/Include/...` includes, whose targets moved to Core/ | new |

Not yet: texture painting, roads, water, lights, build lists, layers, the object properties panel
(team, health, name of a placed object), map settings, AI build lists. They follow the same pattern.

## Next steps

1. Owner's review of milestone 1 on the phone (open official maps, look around, save, open in the game).
2. Tools: brush raise/lower (EA's `BrushTool.cpp` + `CUndoable.cpp`), object placement (`ObjectTool.cpp`
   + object list from TheThingFactory with `ObjectPreview`), waypoint placement (`WaypointTool.cpp`),
   undo/redo. Player starts are waypoints named `Player_N_Start` (EA's convention).
3. Trees through the terrain's tree buffer, vehicle tilt, object shadows, the labels font (EA's
   `drawLabels`), map light objects.
4. Teams, players, scripts, triggers, layers (the large MFC dialogs) -- later releases.

## Open questions

- Redaf12's code: not used. Only with a GPL release and their agreement.
- What's New for 1.4.1 (WhatsNew.RELEASE_LINE + whats_new_* in 13 locales) when the tools land.
