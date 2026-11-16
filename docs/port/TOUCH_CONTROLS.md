# Touch controls

The gesture table is in the [README](../../README.md#touch-controls). This page is the rest: how
gestures are recognised, what is deliberately off on touch, and how to report a control problem.

A one-finger gesture is decided by what the finger does, not by a mode switch. A
24-logical-pixel dead zone separates a tap from a drag. Crossing it before the finger
has been still for 400 ms pans the camera immediately, at any speed. Staying within
10 logical pixels of one point for 400 ms arms the selection box: a small ring fills
under the finger while it waits and turns green when the box is armed, and the next
drag draws it. Releasing without moving after 600 ms remains cancel/deselect
(except while a building placement is pending, where the release places it).

Pan inertia is off, so the camera stops where the finger stops. There is no hidden
one-finger pan mode. Two fingers add simultaneous pan and zoom, but no core action
requires two fingers.

Screen-edge scrolling is **off** on touch. It is defined by a pointer resting
near an edge, and it ends only when a later pointer event reports a position
back inside the safe zone — a condition that cannot occur without a pointer,
which is why it used to leave the camera scrolling on its own. Dragging with
a finger is the touch equivalent and is already direct.

If controls misbehave, turn on **Touch input overlay** in Setup →
Diagnostics: it draws the current gesture, where your finger is, where the
engine thinks the pointer is, and the camera's scroll anchor, and writes
matching `[gxtouch]` lines into the log. Those four things are the same thing
on a desktop and different things on a touchscreen, and their disagreement is
what every control bug here has turned out to be.

For how this is built and how to add a gesture, see
[`docs/WORKDIR/lessons/LESSON-touch-input-is-not-a-mouse.md`](../WORKDIR/lessons/LESSON-touch-input-is-not-a-mouse.md).
