"""Pure gesture classification logic, decoupled from MediaPipe so it is unit-testable
without the heavy vision dependencies installed.

Operates on the 21 hand landmarks defined by the MediaPipe Hands model
(https://developers.google.com/mediapipe/solutions/vision/hand_landmarker), given as
normalized (x, y, z) image coordinates where x/y are in [0, 1] and y increases downward.
"""

from __future__ import annotations

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


def _finger_extended(landmarks: list[Point], tip: int, pip: int) -> bool:
    # Image y grows downward, so an extended (raised) finger has its tip above its pip joint.
    return landmarks[tip].y < landmarks[pip].y


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
        return Gesture.PEACE
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
