"""Pure gesture classification logic, decoupled from MediaPipe so it is unit-testable
without the heavy vision dependencies installed.

Operates on the 21 hand landmarks defined by the MediaPipe Hands model
(https://developers.google.com/mediapipe/solutions/vision/hand_landmarker), given as
normalized (x, y, z) image coordinates where x/y are in [0, 1] and y increases downward.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum


class Handedness(str, Enum):
    LEFT = "Left"
    RIGHT = "Right"


class Gesture(str, Enum):
    FIST = "fist"
    OPEN_PALM = "open_palm"
    THUMBS_UP = "thumbs_up"
    THUMBS_DOWN = "thumbs_down"
    POINT = "point"
    PEACE = "peace"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class Point:
    x: float
    y: float
    z: float = 0.0


# Landmark indices, per the MediaPipe Hands topology.
WRIST = 0
THUMB_CMC, THUMB_MCP, THUMB_IP, THUMB_TIP = 1, 2, 3, 4
INDEX_MCP, INDEX_PIP, INDEX_DIP, INDEX_TIP = 5, 6, 7, 8
MIDDLE_MCP, MIDDLE_PIP, MIDDLE_DIP, MIDDLE_TIP = 9, 10, 11, 12
RING_MCP, RING_PIP, RING_DIP, RING_TIP = 13, 14, 15, 16
PINKY_MCP, PINKY_PIP, PINKY_DIP, PINKY_TIP = 17, 18, 19, 20

_FINGER_TIP_PIP = (
    (INDEX_TIP, INDEX_PIP),
    (MIDDLE_TIP, MIDDLE_PIP),
    (RING_TIP, RING_PIP),
    (PINKY_TIP, PINKY_PIP),
)

NUM_LANDMARKS = 21


def _distance_sq(a: Point, b: Point) -> float:
    return (a.x - b.x) ** 2 + (a.y - b.y) ** 2


# How much further than its own middle joint the middle finger must reach before a hand counts
# as a peace sign rather than a point.
#
# Without a margin the two poses share a boundary at exactly ratio 1.0, and a pointing hand does
# not hold its middle finger fully curled -- it sits a little proud of the knuckle, wandering
# either side of that line frame to frame. The classification then alternates between POINT and
# PEACE, which is felt as the cursor stalling and something being selected while you were only
# moving. A real peace sign clears this several times over.
PEACE_MIDDLE_MARGIN = 0.15


def _finger_extension(landmarks: list[Point], tip: int, pip: int) -> float:
    """How far the tip reaches from the wrist, relative to its own middle joint.

    Above 1.0 the finger is extended, below it the tip has folded back toward the palm. Returned
    as a ratio rather than a flag so a caller that needs to be sure -- rather than merely on the
    right side of the line -- can ask for a margin.
    """
    wrist = landmarks[WRIST]
    pip_distance_sq = _distance_sq(landmarks[pip], wrist)
    if pip_distance_sq <= 0.0:
        # Degenerate: the joint is on top of the wrist, so there is no direction to be extended
        # in. Report "not extended" rather than dividing by zero.
        return 0.0
    return math.sqrt(_distance_sq(landmarks[tip], wrist) / pip_distance_sq)


def _finger_extended(landmarks: list[Point], tip: int, pip: int) -> bool:
    # An extended finger reaches further from the wrist than its own middle joint does; a curled
    # one folds the tip back toward the palm, bringing it closer.
    #
    # This used to compare image y ("tip is above the pip"), which silently required the hand to
    # be pointing roughly upward. Point the finger downward — as happens naturally when sweeping
    # a hand downward — and the tip falls below the pip, the finger reads as curled, and the pose
    # stops being recognised at all. Distance from the wrist carries the same meaning without
    # caring which way the hand is oriented.
    wrist = landmarks[WRIST]
    return _distance_sq(landmarks[tip], wrist) > _distance_sq(landmarks[pip], wrist)


def _thumb_extended(landmarks: list[Point], handedness: Handedness) -> bool:
    # The thumb folds sideways rather than up/down, so it is judged on x instead of y.
    # A right hand's extended thumb points left (smaller x than the IP joint) in image space
    # and vice versa for a left hand, because MediaPipe reports handedness from the subject's
    # own perspective while images are typically not mirrored back.
    tip_x = landmarks[THUMB_TIP].x
    ip_x = landmarks[THUMB_IP].x
    if handedness == Handedness.RIGHT:
        return tip_x < ip_x
    return tip_x > ip_x


def extended_fingers(landmarks: list[Point], handedness: Handedness) -> list[bool]:
    """Returns [thumb, index, middle, ring, pinky] extended flags."""
    if len(landmarks) != NUM_LANDMARKS:
        raise ValueError(f"expected {NUM_LANDMARKS} landmarks, got {len(landmarks)}")
    flags = [_thumb_extended(landmarks, handedness)]
    flags.extend(_finger_extended(landmarks, tip, pip) for tip, pip in _FINGER_TIP_PIP)
    return flags


def classify_gesture(landmarks: list[Point], handedness: Handedness) -> Gesture:
    thumb, index, middle, ring, pinky = extended_fingers(landmarks, handedness)
    others_up = index and middle and ring and pinky
    others_down = not (index or middle or ring or pinky)

    if others_up and thumb:
        return Gesture.OPEN_PALM
    if others_down and not thumb:
        return Gesture.FIST
    if others_down and thumb:
        wrist_y = landmarks[WRIST].y
        tip_y = landmarks[THUMB_TIP].y
        return Gesture.THUMBS_UP if tip_y < wrist_y else Gesture.THUMBS_DOWN
    if index and middle and not ring and not pinky:
        # A margin, not just the flag: see PEACE_MIDDLE_MARGIN. A middle finger that is only
        # barely past its knuckle belongs to a pointing hand, and calling it a peace sign is how
        # the pointer ends up clicking when the user meant to move.
        middle_clear = _finger_extension(landmarks, 12, 10) > 1.0 + PEACE_MIDDLE_MARGIN
        return Gesture.PEACE if middle_clear else Gesture.POINT
    if index and not middle and not ring and not pinky:
        return Gesture.POINT
    return Gesture.UNKNOWN


# Hands-free actions triggered by each recognized gesture. A mobile client polls or streams
# frames to the API and reacts to the returned action name instead of requiring touch input.
GESTURE_ACTIONS: dict[Gesture, str] = {
    Gesture.OPEN_PALM: "stop",
    Gesture.FIST: "start",
    Gesture.THUMBS_UP: "confirm",
    Gesture.THUMBS_DOWN: "cancel",
    Gesture.POINT: "select",
    Gesture.PEACE: "next",
}


def action_for(gesture: Gesture) -> str | None:
    return GESTURE_ACTIONS.get(gesture)
