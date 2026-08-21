import base64
from pathlib import Path

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


@pytest.fixture(scope="module")
def sample_frame(tmp_path_factory) -> bytes:
    image = (np.random.default_rng(0).random((240, 320, 3)) * 255).astype(np.uint8)
    path: Path = tmp_path_factory.mktemp("frames") / "frame.jpg"
    cv2.imwrite(str(path), image)
    return path.read_bytes()


def test_health(client: TestClient) -> None:
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_gestures_lists_all_mapped_actions(client: TestClient) -> None:
    r = client.get("/api/v1/gestures")
    assert r.status_code == 200
    gestures = {g["gesture"] for g in r.json()}
    assert gestures == {"open_palm", "fist", "thumbs_up", "thumbs_down", "point", "peace"}


def test_detect_accepts_a_valid_image(client: TestClient, sample_frame: bytes) -> None:
    r = client.post(
        "/api/v1/detect", files={"file": ("frame.jpg", sample_frame, "image/jpeg")}
    )
    assert r.status_code == 200
    assert "hands" in r.json()


def test_detect_rejects_garbage_bytes(client: TestClient) -> None:
    r = client.post(
        "/api/v1/detect", files={"file": ("frame.jpg", b"not an image", "image/jpeg")}
    )
    assert r.status_code == 422


def test_motion_stream_round_trip(client: TestClient, sample_frame: bytes) -> None:
    with client.websocket_connect("/ws/motion") as ws:
        ws.send_text(base64.b64encode(sample_frame).decode())
        assert ws.receive_json() == {"hands": []}

        ws.send_text("not-valid-base64!!")
        assert ws.receive_json() == {"error": "invalid base64 frame"}
