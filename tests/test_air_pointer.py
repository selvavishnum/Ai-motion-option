"""Tests for the air pointer state machine.

The two things worth proving are the two the old behaviour got wrong: that an arbitrary path --
a circle -- survives as a curve rather than being quantised onto an axis, and that holding still
toggles the pen exactly once per hold rather than repeatedly.
"""

from __future__ import annotations

import math

import pytest

from app.air_pointer import ACTIVE_HI, ACTIVE_LO, AirPointer, PointerEvent

FRAME_MS = 1000.0 / 15.0
WIDTH = 1080
HEIGHT = 2400


def _pointer(**kwargs) -> AirPointer:
    return AirPointer(width_px=WIDTH, height_px=HEIGHT, **kwargs)


def _hold(p: AirPointer, x: float, y: float, frames: int, start_ms: float = 0.0) -> list:
    return [p.update(x, y, start_ms + i * FRAME_MS) for i in range(frames)]


def test_active_band_is_stretched_to_the_full_screen() -> None:
    p = _pointer()
    assert p.update(ACTIVE_LO, ACTIVE_LO, 0.0).x == pytest.approx(0.0)
    p2 = _pointer()
    assert p2.update(ACTIVE_HI, ACTIVE_HI, 0.0).x == pytest.approx(WIDTH)


def test_outside_the_active_band_is_clamped_not_wrapped() -> None:
    p = _pointer()
    update = p.update(0.0, 1.0, 0.0)
    assert update.x == pytest.approx(0.0)
    assert update.y == pytest.approx(HEIGHT)


def test_pen_starts_up() -> None:
    assert not _pointer().pen_down


def test_holding_still_puts_the_pen_down_once() -> None:
    p = _pointer(dwell_ms=300.0)
    events = [u.event for u in _hold(p, 0.5, 0.5, frames=40)]

    assert events.count(PointerEvent.PEN_DOWN) == 1
    # The latch is the point: without it a resting hand toggles once per dwell interval.
    assert events.count(PointerEvent.PEN_UP) == 0
    assert p.pen_down


def test_a_second_hold_lifts_the_pen() -> None:
    p = _pointer(dwell_ms=300.0)
    _hold(p, 0.5, 0.5, frames=20)
    assert p.pen_down

    # Move away far enough to clear the latch, then hold again.
    p.update(0.7, 0.5, 1000.0)
    events = [u.event for u in _hold(p, 0.7, 0.5, frames=20, start_ms=1100.0)]

    assert PointerEvent.PEN_UP in events
    assert not p.pen_down


def test_moving_never_toggles_the_pen() -> None:
    """Drawing slowly must not trip the dwell, however long the stroke takes."""
    p = _pointer(dwell_ms=300.0)
    events = []
    for i in range(120):
        # A slow sweep: every frame moves further than the dwell radius allows.
        events.append(p.update(0.3 + i * 0.004, 0.5, i * FRAME_MS).event)

    assert PointerEvent.PEN_DOWN not in events
    assert PointerEvent.PEN_UP not in events


def test_a_circle_traced_with_the_pen_down_stays_a_circle() -> None:
    """The claim the whole change rests on: an arbitrary path is reported as that path, not
    quantised onto the nearest axis the way fixed-direction swipes were.

    Each axis maps its own comfortable band onto its own screen dimension, exactly as a trackpad
    does, so on a portrait screen a circle traced in the air arrives as an ellipse in pixels --
    and the user, who is watching the dot rather than their hand, traces whatever shape puts the
    dot where they want it. What must survive is the *shape*: the path is measured in units of
    its own extent on each axis, where a circle is a circle again.
    """
    p = _pointer(dwell_ms=200.0)
    _hold(p, 0.5, 0.5, frames=20)  # pen down
    assert p.pen_down

    radius = 0.12
    points = []
    t = 1000.0
    # Two laps: the first lets the smoothing settle, the second is measured.
    for i in range(120):
        angle = 2 * math.pi * i / 60
        u = p.update(0.5 + radius * math.cos(angle), 0.5 + radius * math.sin(angle), t)
        t += FRAME_MS
        assert u.event is PointerEvent.PEN_MOVE
        if i >= 60:
            points.append((u.x, u.y))

    span_x = max(x for x, _ in points) - min(x for x, _ in points)
    span_y = max(y for _, y in points) - min(y for _, y in points)
    assert span_x > 0 and span_y > 0

    scaled = [(x / span_x, y / span_y) for x, y in points]
    cx = sum(x for x, _ in scaled) / len(scaled)
    cy = sum(y for _, y in scaled) / len(scaled)
    radii = [math.hypot(x - cx, y - cy) for x, y in scaled]
    mean_radius = sum(radii) / len(radii)

    # Every sampled point sits near one radius from the centre. A square of four axis-aligned
    # strokes -- what the old swipe quantisation could produce at best -- has corners 1.41x
    # further out than its edge midpoints, so it fails this by a wide margin.
    for r in radii:
        assert abs(r - mean_radius) / mean_radius < 0.15

    # And it is smooth: consecutive segments turn by a similar small angle all the way round.
    # A polygon turns by nothing along an edge and everything at a corner.
    turns = []
    for (ax, ay), (bx, by), (dx, dy) in zip(scaled, scaled[1:], scaled[2:]):
        first = math.atan2(by - ay, bx - ax)
        second = math.atan2(dy - by, dx - bx)
        turns.append(abs((second - first + math.pi) % (2 * math.pi) - math.pi))
    assert max(turns) < 4 * (sum(turns) / len(turns))


def test_release_ends_a_drag_in_progress() -> None:
    p = _pointer(dwell_ms=200.0)
    _hold(p, 0.5, 0.5, frames=20)
    assert p.pen_down

    update = p.release()
    assert update is not None
    assert update.event is PointerEvent.PEN_UP
    assert not p.pen_down


def test_release_with_the_pen_up_reports_nothing() -> None:
    p = _pointer()
    p.update(0.5, 0.5, 0.0)
    assert p.release() is None


def test_release_forgets_the_position_so_the_next_sighting_does_not_slide_in() -> None:
    p = _pointer()
    _hold(p, 0.25, 0.25, frames=10)
    p.release()

    # A fresh hand at the other side of the frame must appear there, not travel across.
    update = p.update(ACTIVE_HI, ACTIVE_HI, 5000.0)
    assert update.x == pytest.approx(WIDTH, abs=1.0)


def test_a_shorter_dwell_toggles_sooner() -> None:
    def frames_until_pen_down(dwell_ms: float) -> int:
        p = _pointer(dwell_ms=dwell_ms)
        for i in range(200):
            if p.update(0.5, 0.5, i * FRAME_MS).event is PointerEvent.PEN_DOWN:
                return i
        raise AssertionError("pen never went down")

    assert frames_until_pen_down(200.0) < frames_until_pen_down(900.0)


def test_lift_pen_ends_the_drag_without_moving_the_cursor() -> None:
    """A click must not restart the smoothing. Releasing after every selection would make the
    cursor jump exactly when the user is looking at what they just aimed at."""
    p = _pointer(dwell_ms=200.0)
    _hold(p, 0.5, 0.5, frames=20)
    assert p.pen_down
    before = p.position

    update = p.lift_pen()
    assert update is not None
    assert update.event is PointerEvent.PEN_UP
    assert not p.pen_down
    assert p.position == before

    # The next frame continues from where it was, rather than snapping to the raw fingertip.
    assert p.update(0.5, 0.5, 3000.0).x == pytest.approx(before[0], abs=1.0)


def test_lift_pen_with_the_pen_up_reports_nothing() -> None:
    p = _pointer()
    p.update(0.5, 0.5, 0.0)
    assert p.lift_pen() is None
