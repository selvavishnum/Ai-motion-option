"""Tests for the 1-Euro filter.

The claim being tested is the one the filter exists for: it smooths more when the signal is slow
than when it is fast. A test that only checked "output is near input" would pass for a
pass-through, which is precisely the thing that made the old fixed-factor pointer feel wrong.
"""

from __future__ import annotations

import math
import random

import pytest

from app.one_euro import OneEuroFilter, OneEuroFilter2D

FRAME_MS = 1000.0 / 15.0  # the camera budget this runs at


def _feed(f: OneEuroFilter, values: list[float]) -> list[float]:
    return [f.filter(v, i * FRAME_MS) for i, v in enumerate(values)]


def test_first_sample_passes_through() -> None:
    f = OneEuroFilter()
    assert f.filter(0.42, 0.0) == pytest.approx(0.42)


def test_constant_signal_stays_constant() -> None:
    out = _feed(OneEuroFilter(), [0.5] * 30)
    for value in out:
        assert value == pytest.approx(0.5, abs=1e-9)


def test_noise_on_a_still_signal_is_attenuated() -> None:
    random.seed(7)
    noise = [0.5 + random.uniform(-0.01, 0.01) for _ in range(200)]
    out = _feed(OneEuroFilter(), noise)

    # Compare the steady state, skipping the warm-up where the filter is still converging.
    def spread(xs: list[float]) -> float:
        tail = xs[50:]
        mean = sum(tail) / len(tail)
        return math.sqrt(sum((x - mean) ** 2 for x in tail) / len(tail))

    assert spread(out) < spread(noise) / 2


def test_a_fast_move_is_tracked_more_closely_than_a_slow_one() -> None:
    """The whole premise: the cutoff rises with speed, so a deliberate sweep is not dragged
    behind while a near-still hand is still held steady."""
    steps = 40

    fast = OneEuroFilter()
    fast_lag = 0.0
    for i in range(steps):
        target = i * 0.02  # 0.02 per frame
        fast_lag = abs(target - fast.filter(target, i * FRAME_MS))

    slow = OneEuroFilter()
    slow_lag = 0.0
    for i in range(steps):
        target = i * 0.0005  # 40x slower
        slow_lag = abs(target - slow.filter(target, i * FRAME_MS))

    # Lag as a fraction of the distance travelled per frame: the fast signal keeps a far smaller
    # share of its own step size than the slow one does.
    assert fast_lag / 0.02 < slow_lag / 0.0005


def test_reset_forgets_history() -> None:
    f = OneEuroFilter()
    _feed(f, [0.9] * 20)
    f.reset()
    # Without the reset the next sample would be pulled toward 0.9.
    assert f.filter(0.1, 999.0) == pytest.approx(0.1)


def test_repeated_timestamp_does_not_divide_by_zero() -> None:
    f = OneEuroFilter()
    f.filter(0.3, 100.0)
    value = f.filter(0.4, 100.0)
    assert math.isfinite(value)


def test_backwards_timestamp_does_not_produce_nonsense() -> None:
    f = OneEuroFilter()
    f.filter(0.3, 500.0)
    value = f.filter(0.4, 100.0)
    assert math.isfinite(value)
    assert 0.0 <= value <= 1.0


def test_2d_filters_axes_identically() -> None:
    """A diagonal move must be smoothed the same on both axes, or a circle comes out an
    ellipse."""
    f = OneEuroFilter2D()
    for i in range(30):
        v = i * 0.01
        x, y = f.filter(v, v, i * FRAME_MS)
        assert x == pytest.approx(y)
