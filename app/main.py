from __future__ import annotations

import base64
import binascii
from contextlib import asynccontextmanager
from pathlib import Path

import cv2
import numpy as np
from fastapi import FastAPI, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse

from app.gesture import GESTURE_ACTIONS
from app.hand_tracker import HandTracker
from app.schemas import DetectResponse, GestureInfo, HealthResponse

_STATIC_DIR = Path(__file__).parent / "static"

_tracker: HandTracker | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _tracker
    _tracker = HandTracker()
    try:
        yield
    finally:
        _tracker.close()
        _tracker = None


app = FastAPI(
    title="Ai-motion-option",
    description="Gesture-driven, hands-free motion detection API for mobile clients.",
    version="0.1.0",
    lifespan=lifespan,
)


def _decode_image(data: bytes) -> np.ndarray:
    buffer = np.frombuffer(data, dtype=np.uint8)
    image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
    if image is None:
        raise HTTPException(status_code=422, detail="could not decode image")
    return image


@app.get("/", include_in_schema=False)
async def demo_page() -> FileResponse:
    """Mobile-friendly page that opens the camera, streams frames to /ws/motion, and
    shows the live detected gesture/action — the quickest way to try this on a phone."""
    return FileResponse(_STATIC_DIR / "index.html")


@app.get("/api/v1/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse()


@app.get("/api/v1/gestures", response_model=list[GestureInfo])
async def gestures() -> list[GestureInfo]:
    return [
        GestureInfo(gesture=gesture.value, action=action)
        for gesture, action in GESTURE_ACTIONS.items()
    ]


@app.post("/api/v1/detect", response_model=DetectResponse)
async def detect(file: UploadFile) -> DetectResponse:
    data = await file.read()
    image = _decode_image(data)
    hands = _tracker.process(image)
    return DetectResponse(hands=hands)


@app.websocket("/ws/motion")
async def motion_stream(websocket: WebSocket) -> None:
    """Continuous hands-free control channel: the client streams base64-encoded JPEG/PNG
    frames as text messages and receives detected gestures/actions as they occur.
    """
    await websocket.accept()
    try:
        while True:
            payload = await websocket.receive_text()
            try:
                data = base64.b64decode(payload, validate=True)
            except (binascii.Error, ValueError):
                await websocket.send_json({"error": "invalid base64 frame"})
                continue

            try:
                image = _decode_image(data)
            except HTTPException as exc:
                await websocket.send_json({"error": exc.detail})
                continue

            hands = _tracker.process(image)
            await websocket.send_json(DetectResponse(hands=hands).model_dump())
    except WebSocketDisconnect:
        pass
