"""On-device gesture personalisation: learn a user's own hand shapes from a handful of examples.

The rule-based classifier in gesture.py encodes one opinion about what a "fist" looks like,
tuned on one pair of hands. It cannot be right for everyone — finger length, how far someone
actually curls, whether they can fully extend a finger at all — and for an accessibility tool
that last one is the whole point.

This module is the alternative: record a few examples of a gesture as the user actually makes
it, and match new frames against those. No training framework, no model file. Normalised
landmarks are compared to the average of the recorded examples, which for a handful of samples
per class is both the simplest and the most accurate thing available — a neural network fitted
to eight examples would mostly be fitting noise.

Kept in Python as the reference implementation, mirroring gesture.py, so the geometry can be
tested here and ported to Kotlin with confidence.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from app.gesture import NUM_LANDMARKS, WRIST, Gesture, Point

# The knuckle at the base of the middle finger. Wrist -> this point is the most stable axis on a
# hand: it barely moves as fingers curl, unlike anything involving the fingertips.
MIDDLE_MCP = 9

# Length of a normalised feature vector: 21 points, x and y each. z is dropped — MediaPipe's
# depth is relative and much noisier than x/y, and including it mostly adds jitter.
FEATURE_LENGTH = NUM_LANDMARKS * 2


def normalize_landmarks(landmarks: list[Point]) -> tuple[float, ...]:
    """Reduces a hand to its *shape*, discarding where it is, how big it is, and how it is turned.

    Three steps, in order:

    1. **Translate** so the wrist is the origin — where the hand is in frame says nothing about
       which gesture it is.
    2. **Rotate** so the wrist -> middle knuckle axis points straight up. Without this, the same
       fist held at a tilt lands far from the recorded fist, and the user would have to reproduce
       their exact wrist angle every time.
    3. **Scale** so that axis has unit length — a hand near the camera and the same hand further
       away are the same gesture.

    What survives is the relative position of every joint: exactly what distinguishes gestures,
    and nothing else.
    """
    if len(landmarks) != NUM_LANDMARKS:
        raise ValueError(f"expected {NUM_LANDMARKS} landmarks, got {len(landmarks)}")

    wrist = landmarks[WRIST]
    axis_x = landmarks[MIDDLE_MCP].x - wrist.x
    axis_y = landmarks[MIDDLE_MCP].y - wrist.y
    scale = math.hypot(axis_x, axis_y)
    if scale < 1e-6:
        # Wrist and middle knuckle coincide: a degenerate detection, not a hand. Refusing to
        # normalise is better than dividing by ~zero and producing enormous garbage features.
        raise ValueError("degenerate hand: wrist and middle knuckle coincide")

    # Rotation that takes the axis to (0, -1) — "up" in image coordinates, where y grows downward.
    # cos/sin are read straight off the normalised axis rather than via atan2 and back.
    cos = -axis_y / scale
    sin = -axis_x / scale

    features: list[float] = []
    for point in landmarks:
        dx = (point.x - wrist.x) / scale
        dy = (point.y - wrist.y) / scale
        features.append(dx * cos - dy * sin)
        features.append(dx * sin + dy * cos)
    return tuple(features)


def _distance(a: tuple[float, ...], b: tuple[float, ...]) -> float:
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


@dataclass
class GestureTemplates:
    """The examples a user has recorded, per gesture.

    Samples are kept rather than only their average so that re-recording one gesture doesn't
    require the others, and so a future version can drop an outlier sample without asking the
    user to start over.
    """

    samples: dict[Gesture, list[tuple[float, ...]]] = field(default_factory=dict)

    def add(self, gesture: Gesture, landmarks: list[Point]) -> None:
        self.samples.setdefault(gesture, []).append(normalize_landmarks(landmarks))

    def clear(self, gesture: Gesture) -> None:
        self.samples.pop(gesture, None)

    def trained_gestures(self) -> list[Gesture]:
        """Gestures with enough examples to be worth matching against."""
        return [g for g, s in self.samples.items() if len(s) >= MIN_SAMPLES]

    def centroid(self, gesture: Gesture) -> tuple[float, ...] | None:
        recorded = self.samples.get(gesture)
        if not recorded or len(recorded) < MIN_SAMPLES:
            return None
        count = len(recorded)
        return tuple(sum(values) / count for values in zip(*recorded))


# Below this, an "average" is one or two hand positions and matching against it would be worse
# than the rules it replaces.
MIN_SAMPLES = 5

# Tuning. Both are in normalised-hand units, where 1.0 is the wrist-to-middle-knuckle distance,
# so they mean the same thing on every hand and at every distance from the camera.
#
# These are starting points from the geometry, not measurements: the real values want checking
# against recordings from a real hand, which is why they are named constants rather than magic
# numbers buried in the comparison.
MAX_MATCH_DISTANCE = 1.6
MIN_MARGIN = 0.35


def classify_personalized(
    landmarks: list[Point],
    templates: GestureTemplates,
    max_distance: float = MAX_MATCH_DISTANCE,
    min_margin: float = MIN_MARGIN,
) -> Gesture | None:
    """Matches a hand against the user's recorded examples.

    Returns None — meaning "fall back to the rule-based classifier" — rather than guessing,
    whenever the match is not clearly good:

    * nothing trained yet, or the hand is degenerate;
    * the closest gesture is still far from anything recorded (an unmodelled hand shape, or a
      hand caught mid-transition between two gestures);
    * two gestures match almost equally well, so picking either is a coin flip.

    Refusing to answer is the right behaviour for a system that fires real actions on other
    apps: a wrong Home press costs the user more than a missed one.
    """
    trained = templates.trained_gestures()
    if not trained:
        return None

    try:
        features = normalize_landmarks(landmarks)
    except ValueError:
        return None

    scored = sorted(
        (_distance(features, templates.centroid(g)), g) for g in trained
    )
    best_distance, best_gesture = scored[0]
    if best_distance > max_distance:
        return None
    if len(scored) > 1 and scored[1][0] - best_distance < min_margin:
        return None
    return best_gesture
