"""Thin wrapper around the MediaPipe Hand Landmarker task that turns a raw image into
gesture results.

MediaPipe's Tasks API (the current, non-deprecated API as of mediapipe>=1.0) requires a
model bundle on disk. It is downloaded once and cached locally rather than bundled in the
repo, since model files are large binary artifacts that don't belong in version control.
"""

from __future__ import annotations

import os
import urllib.request
from pathlib import Path

import numpy as np

from app.gesture import Handedness, Point, action_for, classify_gesture
from app.schemas import HandResult, LandmarkOut

_MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/"
    "hand_landmarker/float16/latest/hand_landmarker.task"
)
_DEFAULT_MODEL_PATH = Path.home() / ".cache" / "ai-motion-option" / "hand_landmarker.task"


def _resolve_model_path() -> Path:
    model_path = Path(os.environ.get("HAND_LANDMARKER_MODEL_PATH", _DEFAULT_MODEL_PATH))
    if not model_path.exists():
        model_path.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(_MODEL_URL, model_path)  # noqa: S310 - fixed Google CDN URL
    return model_path


class HandTracker:
    def __init__(self, max_num_hands: int = 2, min_detection_confidence: float = 0.6) -> None:
        import mediapipe as mp
        from mediapipe.tasks.python import vision
        from mediapipe.tasks.python.core.base_options import BaseOptions

        self._mp = mp
        options = vision.HandLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=str(_resolve_model_path())),
            running_mode=vision.RunningMode.IMAGE,
            num_hands=max_num_hands,
            min_hand_detection_confidence=min_detection_confidence,
        )
        self._landmarker = vision.HandLandmarker.create_from_options(options)

    def close(self) -> None:
        self._landmarker.close()

    def process(self, image_bgr: np.ndarray) -> list[HandResult]:
        import cv2

        image_rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
        mp_image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=image_rgb)
        result = self._landmarker.detect(mp_image)

        hands: list[HandResult] = []
        for hand_landmarks, handedness_categories in zip(
            result.hand_landmarks, result.handedness
        ):
            category = handedness_categories[0]
            handedness = Handedness.LEFT if category.category_name == "Left" else Handedness.RIGHT

            points = [Point(x=lm.x, y=lm.y, z=lm.z) for lm in hand_landmarks]
            gesture = classify_gesture(points, handedness)

            hands.append(
                HandResult(
                    handedness=handedness.value,
                    confidence=category.score,
                    gesture=gesture.value,
                    action=action_for(gesture),
                    landmarks=[LandmarkOut(x=p.x, y=p.y, z=p.z) for p in points],
                )
            )
        return hands

    def __enter__(self) -> "HandTracker":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()
