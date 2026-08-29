"""The 1-Euro filter: adaptive smoothing for a cursor driven by a noisy tracker.

The air pointer used a single fixed low-pass factor, and a fixed factor cannot be right. Set it
heavy enough that a still fingertip stops buzzing and the cursor lags visibly behind a fast
movement; set it light enough to keep up and the dot jitters when you try to hold it on a target.
Both failures read to the user as "it doesn't work properly", which is exactly the complaint.

The 1-Euro filter (Casiez, Roussel & Vogel, CHI 2012) resolves that by making the cutoff a
function of speed: heavy smoothing while the hand is nearly still, so the dot sits quietly on its
target, and progressively lighter smoothing as it moves, so a fast sweep is not dragged behind.
It is two one-pole low-pass filters -- one on the value, one on its derivative -- and nothing
else, which is why it fits inside a per-frame budget on a phone.

Kept in Python as the reference implementation, mirroring gesture.py and gesture_templates.py,
so the numerical behaviour is tested here and ported to Kotlin with confidence.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

# Defaults tuned for a fingertip in normalised image coordinates at ~15 frames per second.
# MIN_CUTOFF sets how still the pointer is when the hand is still: lower is steadier. BETA sets
# how quickly the filter gets out of the way when the hand moves: higher is more responsive but
# lets more jitter through during motion.
#
# BETA is scaled to the units it multiplies. Speed here is in normalised image widths per second,
# where a brisk sweep across the frame is under 1.0 -- so the textbook values quoted for pixel
# coordinates (fractions of a hundredth) would add nothing to the cutoff at any speed a hand can
# reach, leaving a filter that is adaptive in name only.
DEFAULT_MIN_CUTOFF = 0.7
DEFAULT_BETA = 1.0
DEFAULT_D_CUTOFF = 1.0

# Guards against a duplicated or out-of-order timestamp producing a division by zero or a
# nonsensical rate. 200 Hz is far above any camera frame rate this runs at.
MAX_RATE_HZ = 200.0


def _alpha(cutoff: float, dt: float) -> float:
    """Smoothing factor of a one-pole low-pass filter with the given cutoff, for a step of dt."""
    tau = 1.0 / (2.0 * math.pi * cutoff)
    return 1.0 / (1.0 + tau / dt)


@dataclass
class _LowPass:
    """One-pole low-pass filter that remembers whether it has seen a value yet."""

    value: float = 0.0
    initialised: bool = False

    def filter(self, x: float, alpha: float) -> float:
        if not self.initialised:
            self.value = x
            self.initialised = True
            return x
        self.value = alpha * x + (1.0 - alpha) * self.value
        return self.value

    def reset(self) -> None:
        self.initialised = False
        self.value = 0.0


@dataclass
class OneEuroFilter:
    """Smooths one scalar signal. Use one per axis.

    Args:
        min_cutoff: cutoff frequency, in Hz, at zero speed. Lower means a steadier cursor when
            the hand is still, at the cost of more lag when it starts moving.
        beta: how much the cutoff rises with speed. Higher means the filter yields sooner to a
            deliberate movement.
        d_cutoff: cutoff for the speed estimate itself. Rarely worth changing; it exists so the
            speed used to pick the cutoff is not itself pure noise.
    """

    min_cutoff: float = DEFAULT_MIN_CUTOFF
    beta: float = DEFAULT_BETA
    d_cutoff: float = DEFAULT_D_CUTOFF

    _x: _LowPass = field(default_factory=_LowPass, repr=False)
    _dx: _LowPass = field(default_factory=_LowPass, repr=False)
    _last_timestamp_ms: float | None = field(default=None, repr=False)

    def reset(self) -> None:
        """Forgets all history. Call when the tracked subject disappears, so the next sighting
        starts at its own position rather than sliding in from where the last one ended."""
        self._x.reset()
        self._dx.reset()
        self._last_timestamp_ms = None

    def filter(self, x: float, timestamp_ms: float) -> float:
        """Returns the smoothed value of ``x`` observed at ``timestamp_ms``."""
        previous = self._last_timestamp_ms
        self._last_timestamp_ms = timestamp_ms

        if previous is None:
            # No interval yet, so no speed and nothing to smooth against.
            self._x.filter(x, 1.0)
            return x

        elapsed_s = (timestamp_ms - previous) / 1000.0
        # A non-advancing clock would make the rate infinite and the filter a pass-through; a
        # backwards one would make it negative. Clamping is the honest response to both: treat
        # them as one tick at the ceiling rate.
        dt = max(elapsed_s, 1.0 / MAX_RATE_HZ)

        speed = (x - self._x.value) / dt
        smoothed_speed = self._dx.filter(speed, _alpha(self.d_cutoff, dt))

        cutoff = self.min_cutoff + self.beta * abs(smoothed_speed)
        return self._x.filter(x, _alpha(cutoff, dt))


@dataclass
class OneEuroFilter2D:
    """Two [OneEuroFilter]s sharing a configuration, for a point.

    The axes are filtered independently but from the same clock, so a diagonal movement is
    smoothed by the same amount on both -- which is what keeps a circle drawn in the air round
    instead of squashed along whichever axis happened to be noisier.
    """

    min_cutoff: float = DEFAULT_MIN_CUTOFF
    beta: float = DEFAULT_BETA
    d_cutoff: float = DEFAULT_D_CUTOFF

    _fx: OneEuroFilter = field(init=False, repr=False)
    _fy: OneEuroFilter = field(init=False, repr=False)

    def __post_init__(self) -> None:
        self._fx = OneEuroFilter(self.min_cutoff, self.beta, self.d_cutoff)
        self._fy = OneEuroFilter(self.min_cutoff, self.beta, self.d_cutoff)

    def reset(self) -> None:
        self._fx.reset()
        self._fy.reset()

    def filter(self, x: float, y: float, timestamp_ms: float) -> tuple[float, float]:
        return (self._fx.filter(x, timestamp_ms), self._fy.filter(y, timestamp_ms))
