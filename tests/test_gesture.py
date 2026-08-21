from app.gesture import Gesture, Handedness, Point, action_for, classify_gesture

NUM_LANDMARKS = 21


def _base_landmarks() -> list[Point]:
    # Wrist at the bottom-center; every other joint starts folded near the wrist so tests
    # only need to move the joints relevant to the finger(s) under test.
    return [Point(x=0.5, y=0.9, z=0.0) for _ in range(NUM_LANDMARKS)]


def _extend(landmarks: list[Point], tip: int, pip: int) -> None:
    landmarks[pip] = Point(x=0.5, y=0.6, z=0.0)
    landmarks[tip] = Point(x=0.5, y=0.3, z=0.0)


def _curl(landmarks: list[Point], tip: int, pip: int) -> None:
    landmarks[pip] = Point(x=0.5, y=0.6, z=0.0)
    landmarks[tip] = Point(x=0.5, y=0.8, z=0.0)


def test_open_palm() -> None:
    lm = _base_landmarks()
    for tip, pip in ((8, 6), (12, 10), (16, 14), (20, 18)):
        _extend(lm, tip, pip)
    lm[3] = Point(x=0.6, y=0.5)  # thumb ip
    lm[4] = Point(x=0.5, y=0.5)  # thumb tip, extended to the left for a right hand
    assert classify_gesture(lm, Handedness.RIGHT) == Gesture.OPEN_PALM
    assert action_for(Gesture.OPEN_PALM) == "stop"


def test_fist() -> None:
    lm = _base_landmarks()
    for tip, pip in ((8, 6), (12, 10), (16, 14), (20, 18)):
        _curl(lm, tip, pip)
    lm[3] = Point(x=0.6, y=0.5)
    lm[4] = Point(x=0.65, y=0.5)  # thumb tucked in, not extended for a right hand
    assert classify_gesture(lm, Handedness.RIGHT) == Gesture.FIST
    assert action_for(Gesture.FIST) == "start"


def test_thumbs_up() -> None:
    lm = _base_landmarks()
    for tip, pip in ((8, 6), (12, 10), (16, 14), (20, 18)):
        _curl(lm, tip, pip)
    lm[0] = Point(x=0.5, y=0.9)  # wrist
    lm[3] = Point(x=0.6, y=0.6)
    lm[4] = Point(x=0.5, y=0.2)  # thumb extended above the wrist
    assert classify_gesture(lm, Handedness.RIGHT) == Gesture.THUMBS_UP
    assert action_for(Gesture.THUMBS_UP) == "confirm"


def test_thumbs_down() -> None:
    lm = _base_landmarks()
    for tip, pip in ((8, 6), (12, 10), (16, 14), (20, 18)):
        _curl(lm, tip, pip)
    lm[0] = Point(x=0.5, y=0.5)  # wrist
    lm[3] = Point(x=0.6, y=0.7)
    lm[4] = Point(x=0.5, y=0.9)  # thumb extended below the wrist
    assert classify_gesture(lm, Handedness.RIGHT) == Gesture.THUMBS_DOWN
    assert action_for(Gesture.THUMBS_DOWN) == "cancel"


def test_point() -> None:
    lm = _base_landmarks()
    _extend(lm, 8, 6)
    for tip, pip in ((12, 10), (16, 14), (20, 18)):
        _curl(lm, tip, pip)
    lm[3] = Point(x=0.6, y=0.5)
    lm[4] = Point(x=0.55, y=0.5)
    assert classify_gesture(lm, Handedness.RIGHT) == Gesture.POINT
    assert action_for(Gesture.POINT) == "select"


def test_peace() -> None:
    lm = _base_landmarks()
    _extend(lm, 8, 6)
    _extend(lm, 12, 10)
    for tip, pip in ((16, 14), (20, 18)):
        _curl(lm, tip, pip)
    lm[3] = Point(x=0.6, y=0.5)
    lm[4] = Point(x=0.55, y=0.5)
    assert classify_gesture(lm, Handedness.RIGHT) == Gesture.PEACE
    assert action_for(Gesture.PEACE) == "next"


def test_left_hand_thumb_orientation_is_mirrored() -> None:
    lm = _base_landmarks()
    for tip, pip in ((8, 6), (12, 10), (16, 14), (20, 18)):
        _extend(lm, tip, pip)
    lm[3] = Point(x=0.4, y=0.5)
    lm[4] = Point(x=0.5, y=0.5)  # extended to the right, correct for a left hand
    assert classify_gesture(lm, Handedness.LEFT) == Gesture.OPEN_PALM


def test_wrong_landmark_count_raises() -> None:
    import pytest

    from app.gesture import extended_fingers

    with pytest.raises(ValueError):
        extended_fingers([Point(0, 0)], Handedness.RIGHT)
