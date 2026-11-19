# Flux Build v1.1.2 Hotfix - Input, Colour and Network Forensics

Date: 23/10/2026
Branch: `feature/floating-buttons-spoof`
Base APK: `apk/GeneralsXZH-android-v1.1.2.apk` (versionCode 11201, versionName 1.1.2)

## Input Routing Audit Report

### 1. The battlefield does not go through the floating overlay

A normal map touch follows `SDLSurface.onTouch` -> SDL native touch -> `SDL3GameEngine::handleTouchEvent`.
It does not pass through `HotkeyOverlayView`. Only the floating buttons are hit-tested by
`HotkeyOverlayView.onTouchEvent()` (`ACTION_DOWN` outside a button returns `false`, line 614), and only
those buttons call `SDLActivity.nativeInjectMouseButton()` (`HotkeyOverlayView` lines 623, 704, 747).
This matters because a Java-side deadzone cannot by itself govern a map tap.

### 2. A plain map tap is not what creates the tiny selection box

A touch that stays inside `TAP_DEAD_ZONE_PX` until release remains in `TouchState::PENDING` and resolves
as `TouchInput::tap(downX, downY)` on release (`SDL3GameEngine.cpp:2032`). `TouchInput::tap()` then either
selects a unit under the finger, issues a context order, or deselects on empty ground
(`TouchInput.cpp:275-340`). It never sends `MSG_RAW_MOUSE_LEFT_BUTTON_DOWN`.

The ghost box requires promotion to `TouchState::SELECTING`. In the old code that promotion happened when:

- the finger had been held for `SELECT_HOLD_MS = 250 ms` (`SDL3GameEngine.cpp:446`), and
- it moved more than the old `TAP_DEAD_ZONE_PX = 16` Manhattan pixels (`SDL3GameEngine.cpp:1681-1683`).

Promotion then sent `MSG_RAW_MOUSE_LEFT_BUTTON_DOWN` at the anchor and a position immediately
(`SDL3GameEngine.cpp:1746-1749`). Every later motion grew the box. A tap with modest tremor or a slow
lift could therefore hold for 250 ms and move 17 px, draw a 1-2 unit box, and release into a selection
instead of a click.

The requested "15 px deadzone" would have made this worse, not better: the old threshold was already
16 px. The leak was the second, too-small selection gate, not a movement event below 16 px.

**Fix.** The tap/pan gate is now 32 logical pixels and Euclidean, not Manhattan; the selection box has
its own 48 logical-pixel gate (`SDL3GameEngine.cpp:458-467`). A held finger that moves between 32 and
48 px pans; it cannot become a selection box. Below 32 px no `MOUSE_MOTION` is emitted and release is a
click.

### 3. The floating Menu/ESC press could outlive its gesture

There were three independent lifetime bugs in `HotkeyOverlayView`:

1. `ACTION_POINTER_UP` was not handled. If the finger that pressed Menu/ESC lifted while any other
   finger remained down, the button action was deferred until the last `ACTION_UP`. The key-down could
   therefore be sent long after the visual press, at the release point of an unrelated finger.
2. `ACTION_UP` did not check `activePointer`. A release from the wrong pointer could fire the active
   button.
3. `sendKeyWithModifiers()` sent key-down immediately and scheduled key-up (and modifier-up) through
   chained `postDelayed(20/25 ms)` calls. A detach, pause, or screen change in that window left the
   synthetic key-down alive. A later map tap then met a stale ESC/Menu down state.

**Fix.** The overlay now owns the whole gesture from the first hit (`touchOwned`, line 69), consumes
moves even after the finger leaves the button (`ACTION_MOVE`, lines 660-674), finishes on
`ACTION_POINTER_UP` for the pointer that started the press (lines 635-642), validates the pointer on
`ACTION_UP` (lines 645-655), and cancels the mouse button on `ACTION_CANCEL` (lines 677-681).
`sendKeyWithModifiers()` now sends the complete modifier-down -> key-down -> key-up -> modifier-up
sequence synchronously (lines 1013-1028), so no synthetic key state can survive the method.
`onDetachedFromWindow()` also releases any held state (lines 1003-1010).

### 4. Floating LMB injection crossed threads

`HotkeyOverlayView` calls `SDLActivity.nativeInjectMouseButton()` on the Android UI thread. The JNI
bridge (`AndroidTextEditor.cpp:261-265`) called `GXInjectFloatingMouseButton()` directly, which read
`TheMouse`, `TheSDL3Window`, the touchpad state, and called `TouchInput::tap()` - all engine state owned
by the game thread.

**Fix.** `GXInjectFloatingMouseButton()` now posts one ordered SDL user event
(`SDL3GameEngine.cpp:3380-3403`); `pollSDL3Events()` consumes it on the game thread
(`SDL3GameEngine.cpp:2848-2851`) and dispatches to `gxHandleFloatingMouseButton()`. If the event queue
is unavailable during shutdown, the old direct path is kept as an explicit fallback.

### 5. Pairing contract

- Floating LMB/RMB: down is injected on `ACTION_DOWN`, up on every terminal path (`ACTION_UP`,
  `ACTION_POINTER_UP` for the owning pointer, `ACTION_CANCEL`, pause/detach). A move outside the
  button cancels the press and releases the mouse button.
- Native selection: the only `MSG_RAW_MOUSE_LEFT_BUTTON_DOWN` is paired with the release branch's
  `MSG_RAW_MOUSE_LEFT_BUTTON_UP`; a second finger during `SELECTING` finalizes with the same up.
- Synthetic keys: one synchronous down/up pair per activation; no pending key-ups remain after the
  method returns.

## Colour crash

`ThemeHelper.mapColor()` returned a colour **resource ID** for palette colours but a raw **ARGB** value
after `ThemeColors.resolve()` for a custom colour. `UiKit.color()` then passed that result to
`ContextCompat.getColor()` as if it were still an ID, so the first custom swatch threw
`Resources.NotFoundException`.

`mapColor()` now resolves the palette resource with `context.getColor()` and returns ARGB on every path;
`UiKit.color()` returns it directly (`ThemeHelper.java`, `UiKit.java`). Callers that pass the result to
`ColorStateList`, `GradientDrawable`, or `setTextColor` were already expecting a colour, not an ID.

## Colour UI

The card now opens a `BottomSheetDialog` (`ThemeColorSheet.java`):

- three grouped roles (accent, background, text) with preset swatches and a "current" swatch when the
  colour is custom;
- a hex field for the selected role, with validation;
- a live preview card showing the draft base, ink, accent button and body text;
- Cancel / Reset / Apply. Nothing is written until Apply, and only roles the player actually changed are
  persisted. A light base auto-selects a readable ink, and light ink on the Day palette supplies a dark
  base; explicit choices are never overwritten.

All 14 new or changed strings were added to every one of the 13 locale folders.

## Network fixes

### QuickMatch CRC

`NGMP_OnlineServices_MatchmakingInterface::StartMatchmaking()` read `TheGlobalData->m_exeCRC` directly.
That field is writable by the lobby-join path, `/forcerelay`, and debug `f_fakeCRC`, so a later action
could change what QuickMatch advertised. `GlobalData` now records the opted-in PC value separately in
`m_exeCRCOverride` during `GlobalData::init()`; QuickMatch uses the override when active and logs
`[GX-QM] StartMatchmaking exe_crc=... pc_spoof=...`. The fallback is the current PC value
`560676586`, with the signed `pc_exe_crc` setting and the data-pack seed taking precedence when present.

### 1096-byte packet drop

`MAX_PACKET_SIZE` is the **send** cap (1100 - 6 = 1094). The receive path accepted a wire payload up to
`MAX_NETWORK_MESSAGE_LEN` (1100), then compared it against `sizeof(TransportMessage.data)`, which was
only 1094. A legal 1096-byte PC packet was rejected.

`GX_MAX_TRANSPORT_MESSAGE = 2048` now backs `TransportMessage.data`, `NetPacket::m_packet`, and the
copy. `NetPacket(TransportMessage*)` copies the delivered length, clamped to the new capacity, instead
of a fixed `MAX_PACKET_SIZE` memcpy that both truncated a 1096-byte packet and over-read a shorter one.
The outgoing builders still bound themselves by `MAX_PACKET_SIZE`.

### WebSocket bad argument

`Disconnect()` nulled `m_pCurlWS` but left `m_bConnected` true. `Send()`, `Ping()` and the queued-send
loop then called `curl_ws_send()` with a null easy handle, producing
`A libcurl function was given a bad argument`.

`Disconnect()` now always clears `m_bConnected` and removes the easy handle from the multi handle before
cleanup. `Ping()`, `Send()` and `Tick()` require both `m_bConnected` and a non-null `m_pCurlWS`. The
queued sends and `curl_ws_recv()` are guarded, and the metadata log is only read when non-null. A bad
argument result also marks the socket disconnected.

### PopBackToLobby fallback

When `parentWOLGameSetup` was null, Back logged a bail and did nothing. It now leaves the setup screen
with `popImmediate(FALSE)` and, if that was the only screen, pushes `Menus/MainMenu.wnd`.

## Verification

- `./scripts/build/android/build-dual-hz.sh`: both passes succeeded; APK carries
  `libmain.so`, `libmain60.so`, `libgenerals.so`, `libgenerals60.so`.
- `./gradlew clean assembleDebug`: BUILD SUCCESSFUL.
- APK: versionName `1.1.2`, versionCode `11201`, v2 signature valid.
- SHA-256: `25cc1ff7a57dad1e55d2c02078c6c9a71f51acb8e7128fa087306435057286af`
- Size: 107 MiB.
- Locales: all 13 variants parse and carry the same 610 string names as `values/`.
- Not performed: an on-device or emulator UI run; no usable device was available. The verification is
  build- and source-based.
