# Flux Build v1.1.2 - Phase 0 Architecture Audit

Date: 23/10/2026
Branch: `feature/floating-buttons-spoof`

## Scope

Reviewed the three files that own the Android launcher and the floating-button layer:

- `android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java`
- `android/app/src/main/java/com/generalsx/zerohour/HotkeyOverlayView.java`
- `android/app/src/main/java/com/generalsx/zerohour/TouchControlConfig.java`

The supporting classes added or extended by v1.1.2 were reviewed with them:
`DeviceAutoConfig`, `ThemeColors`, `ThemeHelper`, `UiKit`, `TouchControlsActivity`, `GeneralsZHActivity`.

This audit is about architecture and behaviour. It is not a security review, and it did not run the game on a
device. `./gradlew clean assembleDebug` is the runtime-independent verification.

## What the launcher actually is

There is no `activity_setup.xml`. `SetupActivity` has always built its UI programmatically, first with a bottom
navigation strip and now with a `DrawerLayout`/`NavigationView`. A layout file was not invented for v1.1.2.
The only launcher layout assets are the existing drawables and values resources.

## Findings

| # | Finding | Severity | v1.1.2 status |
|---|---------|----------|----------------|
| 1 | `TouchControlConfig.load()` turned `enabled=false` into the factory profile and `enabled=true` again. Switching the floating controls off therefore brought back the default E/Q/W/R buttons at factory sizes on the next load. | High | **Fixed.** `load()` reads the enabled flag before anything else, defaults only when no profile key exists, and preserves empty arrays and parse failures. `prepareForLaunch()` restores the stock `GroupPanel.wnd` when the overlay is disabled or has no buttons. |
| 2 | The launcher's edit toolbar was laid out with fixed 58/62 dp text gaps. Long localised labels could run under the +/- buttons or make the bar spill off a narrow screen. | Medium | **Improved in v1.1.2.** The gaps are measured from the active locale and the bar grows by that measured amount. It still has no hard clamp, so a very narrow split-screen window remains an edge case. |
| 3 | `HotkeyOverlayView` draws buttons with `spec.scale` and `spec.opacity`. The editor preview draws with `config.buttonScale` and `config.buttonOpacity`, and ignores `spec.scale` entirely. Moving the editor's global Size/Opacity sliders therefore changes the preview but not the overlay for saved buttons (and the per-button Scale control changes the overlay but not the preview). | High | **Out of scope, reported only.** The global sliders need one deliberate decision: either make the overlay read them as a multiplier, or remove them from the editor. The v1.1.2 resize work only writes `widthDp`/`heightDp` and does not change this contract. |
| 4 | The overlay ignores a button's saved `borderColor` and always strokes it with the current theme accent. The editor still exposes a border colour. | Medium | **Out of scope, reported only.** v1.1.2 intentionally keeps the themed accent on the overlay; the stale editor control should be removed or wired up separately. |
| 5 | `TouchControlConfig.prepareForLaunch()` silently ignores both the `GeneralsXTouch.ini` write and the `GroupPanel.wnd` deletion. A read-only game folder can leave pan sensitivity stale, or leave the stock panel behind while the overlay is active. | Medium | **Out of scope, reported only.** |
| 6 | `TouchControlConfig.save()` catches `Exception` around JSON array construction and then saves whatever was built. A serialization failure could therefore write a partial profile. | Low | **Out of scope, reported only.** `JSONObject.put()` only throws for non-finite numbers; the current values are finite. A corrupt imported profile is a different path. |
| 7 | `TouchControlConfig.clamp()` propagates `NaN` from corrupt JSON (`"widthDp": "NaN"`). A NaN reaches drawing and hit-testing geometry. | Low | **Out of scope, reported only.** Normal load/import rejects invalid numeric strings or clamps they produce; a hand-edited `NaN` string is the only trigger found. |
| 8 | `SetupActivity.applyInternalResolutionSetting()` ignores the boolean returned by `writeKeyValueFile()`, so the spinner's "saved" toast can appear after an I/O failure. When there is no live file and no game `DefaultOptions.ini`, the launch path can write an Options.ini containing only `Resolution`. | Medium | **Partly contained.** The first-launch auto-config now writes a complete file and the launch path preserves it. The failure toast remains out of scope. |
| 9 | `DeviceAutoConfig` and `SetupActivity.screenSize()` use the deprecated `Display.getRealMetrics()`. It does not subtract cutouts and may report a larger area than the game can draw. | Low | **Out of scope, reported only.** A `WindowMetrics`/insets-based replacement is the follow-up. |
| 10 | Tapping the gear while a floating LMB/RMB button is held with another finger used to drop the active pointer state without injecting the matching mouse-up (or clearing toggles). | Medium | **Fixed.** `enterEditMode()` now calls `cancelActivePress()` before taking the snapshot. |
| 11 | The overlay called `ThemeColors.overlayFill()` for every button on every draw, reading SharedPreferences in the draw loop. | Low | **Fixed.** The custom base and its flag are cached in the overlay constructor; the editor preview can keep the static helper. |
| 12 | Custom colours can still make a deliberately low-contrast combination, for example a bright custom accent on the Day palette. The guard does not choose a dark accent automatically. | Low | **Accepted limitation.** Light text on Day is now redirected to a dark base; the accent is still the player's explicit choice. |

## The disabled-profile fix, in detail

The required fix is in `TouchControlConfig.load()`:

1. `config.enabled` is read before the button array is inspected.
2. Defaults are returned only when `PREF_BUTTONS` is absent. The factory defaults inherit the saved
   `enabled`, pan sensitivity, global scale and opacity.
3. A present but empty button array is kept, and logged as an intentionally empty profile.
4. A parse failure returns the partially read config without saving anything, so the only copy of a
   damaged profile is not overwritten.
5. `widthDp`/`heightDp` are canonical; the legacy `width`/`height` keys remain a fallback for v1.1.1
   profiles.
6. `prepareForLaunch()` deletes `GroupPanel.wnd` only when `enabled && !buttons.isEmpty()`. Otherwise
   it restores the bundled stock panel if it is missing.

This preserves the switch state, the exact button list, positions, sizes and action types across a
disable/re-enable cycle and across an app restart.

## New-code review notes

- `ThemeColors.resolve()` maps the full surface ladder from a custom base, not only the root background.
  That is why a dark base chosen while Day is active does not leave light cards under white text.
- `ThemeHelper.palette()` is used for the Day/System guards; `getMode()` alone would miss a phone in
  light mode while the stored mode is `SYSTEM`.
- `writeKeyValueFile()` now stages the complete Options.ini beside the target and moves it over the
  target, so a failed first-launch/re-run write cannot truncate an existing profile.
- `maybeRunAutoConfiguration()` treats a non-empty file as a profile only when it carries
  `StaticGameLOD` or `IdealStaticGameLOD`; this closes the old bare-`Resolution`-file race while
  still merging all existing keys.
- The overlay's prominent `+` is edit-mode-only. Keyboard, LMB and RMB are the three actions; the new
  button is persisted in the same JSON as an edited one.
- Resize is selected-button-only, uses the bottom-right handle, writes `widthDp`/`heightDp`, and has
  a 36 dp invisible hit box around the 22 dp knob.

## Explicitly not touched

- Networking, GeneralsOnline, PC spoofing and anti-cheat behaviour.
- No C++ or native source. The v1.1.2 change is Java and Android resources.
- No new permissions, no manifest component changes.

## Verification performed

- `./gradlew clean assembleDebug`: successful.
- APK: `versionName 1.1.2`, `versionCode 11200`, both engine pairs
  (`libmain`, `libmain60`, `libgenerals`, `libgenerals60`), bundled `gamedata/DefaultOptions.ini`.
- All 13 locale folders have 596 string names, with no missing or extra names relative to `values/`.
- `unzip -t` and `apksigner verify` pass. The APK carries the same debug certificate as v1.1.1, so it
  installs as an update.
- Not performed: an on-device or emulator UI run. The linked build is static/build-verified only.
