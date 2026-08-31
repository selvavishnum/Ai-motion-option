"""The air pointer: one pointed finger drives a cursor that can also draw.

What this replaces
------------------
The old single-finger behaviour fired one of four fixed swipes once the fingertip had travelled
far enough along an axis. That is why a finger in the air can draw a "+" and nothing else: every
movement was forced onto the nearest axis and quantised into a fixed-length swipe, so a circle
came out as, at best, four straight strokes. Curves were not merely inaccurate; they were not
representable.

What replaces it
----------------
A cursor that follows the fingertip continuously, plus a pen that can be put down and lifted.
While the pen is down the cursor's whole path is dispatched as one continuous drag, so whatever
the finger traces -- a circle, an arc, a long smooth scroll -- arrives as that shape. Scrolling
and turning are then not special cases at all: they are drags, of whatever length the user
actually made.

Putting the pen down
--------------------
Holding the fingertip still toggles it: dwell to press, dwell again to lift. Dwell is the
established idiom for pointers with no buttons -- head-tracking and eye-tracking mice both use
it -- and it is the only signal available from a single finger that does not require a second
pose, which is the constraint here.

Three rules stop it from being twitchy:

* The dwell timer only runs while the cursor stays inside a small radius, and any real movement
  restarts it.
* It also only runs while the cursor is slower than a walking pace. A radius alone is not
  enough: a hand drawing *slowly* creeps across the radius over several frames without any one
  frame looking like movement, so a long careful stroke could put the pen down in the middle of
  itself. Speed catches that immediately, at any dwell length -- which matters because the
  sensitivity dial shortens the dwell.
* After a toggle fires, another cannot fire until the finger has moved again. Without this a
  hand resting still would toggle the pen down, up, down, ... once per dwell interval, which
  would look exactly like the "it does things I didn't ask for" failure this is meant to fix.

Kept in Python as the reference implementation, mirroring gesture.py, so the state machine is
tested here and ported to Kotlin with confidence.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from app.one_euro import OneEuroFilter2D

# Your hand travels comfortably across the middle of the camera's view, so that band is stretched
# to cover the whole screen. Mapping the full frame would mean reaching far to one side just to
# touch the edge of the display, and never quite reaching the corners.
ACTIVE_LO = 0.2
ACTIVE_HI = 0.8

# Defaults for sensitivity level 3. The Kotlin side rescales the radius and the dwell time from
# the sensitivity dial; the geometry does not change.
DEFAULT_DWELL_MS = 700.0
DEFAULT_DWELL_RADIUS_PX = 34.0
DEFAULT_STILL_SPEED_PX_S = 70.0


class PointerEvent(Enum):
    """What the caller must do about this frame, beyond moving the dot."""

    NONE = "none"
    PEN_DOWN = "pen_down"
    """Begin a drag at the reported position."""
    PEN_MOVE = "pen_move"
    """Extend the drag in progress to the reported position."""
    PEN_UP = "pen_up"
    """End the drag."""


@dataclass(frozen=True)
class PointerUpdate:
    x: float
    y: float
    pen_down: bool
    event: PointerEvent


@dataclass
class AirPointer:
    """Turns a stream of fingertip observations into cursor positions and pen events.

    Args:
        width_px, height_px: the display, in pixels.
        dwell_ms: how long the fingertip must hold still to toggle the pen.
        dwell_radius_px: how far the cursor may drift and still count as held still.
        still_speed_px_s: how fast the cursor may travel and still count as held still.
    """

    width_px: int
    height_px: int
    dwell_ms: float = DEFAULT_DWELL_MS
    dwell_radius_px: float = DEFAULT_DWELL_RADIUS_PX
    still_speed_px_s: float = DEFAULT_STILL_SPEED_PX_S

    _filter: OneEuroFilter2D = field(default_factory=OneEuroFilter2D, repr=False)
    _x: float = field(default=0.0, repr=False)
    _y: float = field(default=0.0, repr=False)
    _has_position: bool = field(default=False, repr=False)
    _pen_down: bool = field(default=False, repr=False)

    # Centre of the region the cursor has stayed inside, and when it entered.
    _dwell_x: float = field(default=0.0, repr=False)
    _dwell_y: float = field(default=0.0, repr=False)
    _dwell_since_ms: float = field(default=0.0, repr=False)
    _last_timestamp_ms: float = field(default=0.0, repr=False)

    # Set when a toggle fires; cleared once the finger leaves the dwell radius. Stops one long
    # hold from toggling the pen over and over.
    _toggle_latched: bool = field(default=False, repr=False)

    @property
    def pen_down(self) -> bool:
        return self._pen_down

    @property
    def position(self) -> tuple[float, float]:
        return (self._x, self._y)

    def update(self, raw_x: float, raw_y: float, timestamp_ms: float) -> PointerUpdate:
        """Feeds one fingertip observation in normalised (0..1) image coordinates."""
        fx, fy = self._filter.filter(raw_x, raw_y, timestamp_ms)
        x = _map_axis(fx, self.width_px)
        y = _map_axis(fy, self.height_px)

        if not self._has_position:
            self._has_position = True
            self._x, self._y = x, y
            self._last_timestamp_ms = timestamp_ms
            self._begin_dwell(timestamp_ms)
            return PointerUpdate(x, y, self._pen_down, PointerEvent.NONE)

        elapsed_s = max((timestamp_ms - self._last_timestamp_ms) / 1000.0, 1e-3)
        speed = _hypot(x - self._x, y - self._y) / elapsed_s
        self._last_timestamp_ms = timestamp_ms
        self._x, self._y = x, y

        drifted = (
            _hypot(x - self._dwell_x, y - self._dwell_y) > self.dwell_radius_px
            or speed > self.still_speed_px_s
        )
        if drifted:
            # Moving is the common case, and it clears everything the dwell was accumulating --
            # including the latch, so the *next* stop can toggle again.
            self._begin_dwell(timestamp_ms)
            self._toggle_latched = False
            event = PointerEvent.PEN_MOVE if self._pen_down else PointerEvent.NONE
            return PointerUpdate(x, y, self._pen_down, event)

        if self._toggle_latched or timestamp_ms - self._dwell_since_ms < self.dwell_ms:
            # Still holding, but either not for long enough yet or already spent on a toggle.
            # A drag still needs the position: a pen held almost still is drawing a small mark,
            # not nothing.
            event = PointerEvent.PEN_MOVE if self._pen_down else PointerEvent.NONE
            return PointerUpdate(x, y, self._pen_down, event)

        self._toggle_latched = True
        self._pen_down = not self._pen_down
        return PointerUpdate(
            x, y, self._pen_down, PointerEvent.PEN_DOWN if self._pen_down else PointerEvent.PEN_UP
        )

    def lift_pen(self) -> PointerUpdate | None:
        """Ends a drag but keeps the cursor exactly where it is.

        Distinct from [release], and the distinction matters: release forgets the smoothing
        history so a hand that has left and come back does not slide in from where the last one
        stopped. Doing that after every click would restart the filter mid-session, and the
        cursor would visibly jump the moment the user selected something -- while looking at the
        thing they had just carefully aimed at.
        """
        if not self._pen_down:
            return None
        self._pen_down = False
        return PointerUpdate(self._x, self._y, False, PointerEvent.PEN_UP)

    def release(self) -> PointerUpdate | None:
        """Call when the finger is no longer visible.

        Returns a PEN_UP if a drag was in progress -- a stroke left open because a hand left the
        frame would keep the touch pressed on whatever is underneath, which is worse than any
        gesture this could have been. Returns None if there was nothing to end.
        """
        was_down = self._pen_down
        x, y = self._x, self._y
        self._filter.reset()
        self._has_position = False
        self._pen_down = False
        self._toggle_latched = False
        if not was_down:
            return None
        return PointerUpdate(x, y, False, PointerEvent.PEN_UP)

    def _begin_dwell(self, timestamp_ms: float) -> None:
        self._dwell_x, self._dwell_y = self._x, self._y
        self._dwell_since_ms = timestamp_ms


def _map_axis(normalized: float, size_px: int) -> float:
    """Stretches the comfortable middle band of the camera view across a full screen axis."""
    t = (normalized - ACTIVE_LO) / (ACTIVE_HI - ACTIVE_LO)
    return min(max(t, 0.0), 1.0) * size_px


def _hypot(dx: float, dy: float) -> float:
    return (dx * dx + dy * dy) ** 0.5
