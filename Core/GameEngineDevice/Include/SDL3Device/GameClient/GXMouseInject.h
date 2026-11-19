/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Flux Build 23/10/2026 Floating LMB/RMB buttons.
//
// The launcher's touch overlay can now place a button whose action is a mouse
// click rather than a key. It calls this from JNI (SDLActivity.nativeInjectMouseButton).
//
// The two input models are deliberately kept apart:
//
//   * Mouse/Touchpad mode (GXMouseModeEnabled) has a real pointer, so the
//     click is the same SDL mouse event the touchpad itself sends, at the
//     touchpad pointer's current position.
//   * Native touch mode has no pointer at all. Synthesizing a pointer there
//     is exactly the bug this port already fixed once (AGENTS.md, "touch
//     input is not a mouse"). So Left click asks TouchInput to tap the last
//     tactical point, and Right click asks TouchInput to cancel/deselect --
//     the engine's own rules, not a fake MSG_RAW_MOUSE_* stream.

#pragma once

// button uses the browser/DOM numbering the launcher stores: 1 = left, 3 = right.
void GXInjectFloatingMouseButton(int button, bool down);

// GeneralsX @feature Flux Build 06/11/2026 The launcher's "i" button (GeneralsZHActivity): while on,
// a tap on the battlefield shows what is under the finger -- the game's own mouse-over hint (name,
// owner, health, a supply pile's amount) -- instead of selecting or ordering. Taps on the interface,
// building placement, holds and drags are unchanged. Callable from any thread.
void GXSetUnitInfoMode(bool on);
