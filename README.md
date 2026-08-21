# Ai-motion-option

A gesture-driven, hands-free motion detection API. Mobile clients send camera frames and
get back detected hand gestures mapped to actions, so an app can be controlled without touch
input — e.g. an open palm to stop, a fist to start, a thumbs up to confirm.

## Stack

- **FastAPI** for the HTTP/WebSocket API
- **MediaPipe Hand Landmarker** for hand landmark detection
- A small rule-based classifier (`app/gesture.py`) that turns the 21 hand landmarks into a
  named gesture and its mapped action, independent of MediaPipe so it's unit-testable on its
  own

## Gestures

| Gesture       | Action    |
| ------------- | --------- |
| Open palm     | `stop`    |
| Fist          | `start`   |
| Thumbs up     | `confirm` |
| Thumbs down   | `cancel`  |
| Point (index) | `select`  |
| Peace / victory | `next`  |

## Try it on your phone

`GET /` serves a self-contained mobile web demo: it opens your phone's camera, streams
frames to `/ws/motion` over the WebSocket, and shows the detected gesture/action live on
screen. No app install required — it's a regular web page.

Browsers only allow camera access on `localhost` or over HTTPS, so if your phone reaches the
server over your LAN IP (not `localhost`) you need an HTTPS tunnel, e.g.
[ngrok](https://ngrok.com):

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000   # start the server
ngrok http 8000                                    # in another terminal, prints an https:// URL
```

Open the `https://…ngrok…` URL on your phone, tap "Enable camera & start", allow camera
access, and hold up a gesture (open palm, fist, thumbs up/down, point, peace).

## API

- `GET /api/v1/health` — liveness check
- `GET /api/v1/gestures` — lists supported gestures and their mapped actions
- `POST /api/v1/detect` — multipart image upload (`file`), returns detected hands, their
  gesture, and mapped action for a single frame
- `WS /ws/motion` — continuous hands-free control channel: send base64-encoded JPEG/PNG
  frames as text messages, receive `{"hands": [...]}` gesture results per frame

## Running locally

```bash
pip install -r requirements.txt
uvicorn app.main:app --reload
```

MediaPipe's Tasks API needs system graphics libraries (`libegl1`, `libgles2`, `libgl1`) even
for CPU-only inference. On Debian/Ubuntu:

```bash
apt-get install -y libegl1 libgles2 libgl1
```

The hand landmarker model (`hand_landmarker.task`) is downloaded automatically on first run
and cached under `~/.cache/ai-motion-option/`. Set `HAND_LANDMARKER_MODEL_PATH` to point at a
pre-downloaded model file instead (useful for offline or air-gapped deployments).

## Tests

```bash
pip install -r requirements-dev.txt
pytest
```

`tests/test_gesture.py` covers the pure gesture-classification logic with no vision
dependencies required at import time. `tests/test_api.py` exercises the full FastAPI app,
including the WebSocket streaming endpoint.
