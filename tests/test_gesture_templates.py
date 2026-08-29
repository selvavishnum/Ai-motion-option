import math

import pytest

from app.gesture import NUM_LANDMARKS, Gesture, Point
from app.gesture_templates import (
    FEATURE_LENGTH,
    MIDDLE_MCP,
    MIN_SAMPLES,
    GestureTemplates,
    classify_personalized,
    normalize_landmarks,
)


def _hand(spread: float, *, origin=(0.5, 0.5), scale=1.0, rotation=0.0) -> list[Point]:
    """A synthetic hand whose shape is controlled by one number.

    `spread` moves the fingertips away from the palm — 0 is a fist, 1 is an open hand — while
    everything else stays fixed, so two hands with different spreads are genuinely different
    gestures and two with the same spread are the same gesture. origin/scale/rotation then place
    that hand in the frame, which is exactly what normalisation is supposed to discard.
    """
    points = []
    for index in range(NUM_LANDMARKS):
        if index == 0:
            x, y = 0.0, 0.0
        elif index == MIDDLE_MCP:
            x, y = 0.0, -0.3
        else:
            # Fan the remaining joints out around the palm, pushed outward by `spread`.
            angle = (index / NUM_LANDMARKS) * 2 * math.pi
            radius = 0.1 + 0.25 * spread
            x, y = radius * math.cos(angle), -0.3 + radius * math.sin(angle)

        x, y = x * scale, y * scale
        rx = x * math.cos(rotation) - y * math.sin(rotation)
        ry = x * math.sin(rotation) + y * math.cos(rotation)
        points.append(Point(x=origin[0] + rx, y=origin[1] + ry))
    return points


def test_normalize_produces_expected_length() -> None:
    assert len(normalize_landmarks(_hand(0.5))) == FEATURE_LENGTH


def test_normalize_puts_wrist_at_origin_and_axis_up() -> None:
    features = normalize_landmarks(_hand(0.5, origin=(0.9, 0.1), scale=2.0, rotation=1.1))
    assert features[0] == pytest.approx(0.0, abs=1e-6)
    assert features[1] == pytest.approx(0.0, abs=1e-6)
    # The wrist -> middle-knuckle axis is normalised to unit length pointing up (negative y).
    assert features[MIDDLE_MCP * 2] == pytest.approx(0.0, abs=1e-6)
    assert features[MIDDLE_MCP * 2 + 1] == pytest.approx(-1.0, abs=1e-6)


def test_normalize_is_invariant_to_position_scale_and_rotation() -> None:
    # The whole premise of the feature: the same gesture, moved, resized and tilted, must land in
    # the same place. Without this the user would have to reproduce their exact wrist angle and
    # distance from the phone for a recorded gesture to ever match.
    reference = normalize_landmarks(_hand(0.7))
    moved = normalize_landmarks(_hand(0.7, origin=(0.15, 0.85), scale=3.2, rotation=-2.0))
    for a, b in zip(reference, moved):
        assert a == pytest.approx(b, abs=1e-5)


def test_normalize_still_separates_different_shapes() -> None:
    # Invariance is only useful if it doesn't also erase the differences that matter.
    fist = normalize_landmarks(_hand(0.0))
    palm = normalize_landmarks(_hand(1.0))
    assert math.dist(fist, palm) > 0.5


def test_degenerate_hand_is_rejected() -> None:
    flat = [Point(x=0.5, y=0.5) for _ in range(NUM_LANDMARKS)]
    with pytest.raises(ValueError):
        normalize_landmarks(flat)


def _train(templates: GestureTemplates, gesture: Gesture, spread: float, count=MIN_SAMPLES) -> None:
    for i in range(count):
        # Each recording jitters slightly, as a real hand held twice does.
        templates.add(gesture, _hand(spread + i * 0.002, rotation=i * 0.03))


def test_matches_the_gesture_it_was_trained_on() -> None:
    templates = GestureTemplates()
    _train(templates, Gesture.FIST, 0.0)
    _train(templates, Gesture.OPEN_PALM, 1.0)

    assert classify_personalized(_hand(0.02), templates) == Gesture.FIST
    assert classify_personalized(_hand(0.98), templates) == Gesture.OPEN_PALM


def test_matches_regardless_of_where_the_hand_is_held() -> None:
    templates = GestureTemplates()
    _train(templates, Gesture.FIST, 0.0)
    _train(templates, Gesture.OPEN_PALM, 1.0)

    held_differently = _hand(1.0, origin=(0.2, 0.8), scale=2.5, rotation=0.9)
    assert classify_personalized(held_differently, templates) == Gesture.OPEN_PALM


def test_untrained_returns_none() -> None:
    assert classify_personalized(_hand(0.5), GestureTemplates()) is None


def test_too_few_samples_is_not_trained() -> None:
    templates = GestureTemplates()
    _train(templates, Gesture.FIST, 0.0, count=MIN_SAMPLES - 1)
    assert templates.trained_gestures() == []
    assert classify_personalized(_hand(0.0), templates) is None


def test_unfamiliar_shape_returns_none_rather_than_guessing() -> None:
    # Only a fist is trained, and a wide-open hand is nothing like it. Answering "fist" because
    # it is the only option would fire a real action on the user's phone for a gesture they never
    # made; the rule-based classifier gets the frame instead.
    templates = GestureTemplates()
    _train(templates, Gesture.FIST, 0.0)
    assert classify_personalized(_hand(1.0), templates, max_distance=0.5) is None


def test_ambiguous_match_returns_none() -> None:
    # Two gestures trained on nearly the same shape: neither can be picked honestly.
    templates = GestureTemplates()
    _train(templates, Gesture.FIST, 0.50)
    _train(templates, Gesture.PEACE, 0.51)
    assert classify_personalized(_hand(0.505), templates) is None


def test_clearing_one_gesture_leaves_the_others() -> None:
    templates = GestureTemplates()
    _train(templates, Gesture.FIST, 0.0)
    _train(templates, Gesture.OPEN_PALM, 1.0)
    templates.clear(Gesture.FIST)

    assert templates.trained_gestures() == [Gesture.OPEN_PALM]
    assert classify_personalized(_hand(0.98), templates) == Gesture.OPEN_PALM
