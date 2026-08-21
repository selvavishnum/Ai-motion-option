# Ai-motion-option

A gesture-driven, hands-free motion detection system. Two parts:

- **`app/`** — a FastAPI service that detects hand gestures from camera frames, for anyone
  building their own client against it (web, mobile, etc).
- **`android/`** — a native Android app (see [Android app](#android-app-system-wide-gesture-control)
  below) that goes further: it uses air-gestures to control **other apps system-wide** —
  Instagram Reels, YouTube Shorts, Kindle, browser articles — by simulating swipes/taps via
  Android's Accessibility APIs. This only works on Android; iOS has no equivalent API for
  third-party gesture injection.

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

Browsers only allow camera access on `localhost` or over HTTPS, so you need the server
reachable over HTTPS. Two ways to get there:

### No computer at all — deploy to a free cloud host (Render)

This runs the server in the cloud, so there's nothing to keep running on your own machine —
you only need a phone/browser to set it up.

1. Fork or push this repo to your own GitHub account.
2. Go to [render.com](https://dashboard.render.com) on your phone, sign in with GitHub.
3. **New +** → **Blueprint** → pick this repo. Render reads `render.yaml` and configures
   everything (Docker build, free plan, health check) automatically.
4. Click **Deploy**. First build takes a few minutes (it also downloads the hand-tracking
   model at build time).
5. Once live, Render gives you an `https://ai-motion-option-xxxx.onrender.com` URL — open it
   on your phone and tap "Enable camera & start".

Free-tier services spin down after inactivity, so the first request after idling can take
~30s to wake back up.

### Have a computer — run locally + tunnel

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

## Android app: system-wide gesture control

Unlike the web demo above (which only reacts inside its own page), the Android app in
`android/` runs entirely on-device and can control **whatever app is currently on screen** —
scroll Reels/Shorts, turn a Kindle page, scroll a browser article, go back/home, or launch
another app — using air-gestures picked up by the front camera. No server needed; detection
runs fully offline after the one-time model download.

### How it works

- **CameraX** captures the front camera in a foreground service, continuously, even while
  another app is in the foreground.
- **MediaPipe Hand Landmarker** (on-device) finds hand landmarks in each frame; the same
  rule-based classifier as `app/gesture.py`, ported to Kotlin (`gesture/Gesture.kt`), turns
  them into a gesture.
- An **AccessibilityService** — a permission you must turn on manually in Android Settings,
  since no app can grant this to itself — is what actually acts: it simulates a swipe/tap
  gesture, or a Back/Home press, or launches another app, exactly as if you'd touched the
  screen.

### Hard limits (Android platform, not this app)

- **"Close app" means Home, not force-quit.** Android does not let one app terminate
  another, with or without Accessibility permission — only the user (or root) can.
  "Close" here backgrounds the app via the Home action.
- **Only works on whatever's visible.** A simulated swipe lands on the foreground app, so
  gesture control only affects the app currently on screen — same as a real touch would.
- **Accessibility permission is manual, every time.** This is an Android security
  requirement, not a bug — no app can silently grant itself this level of control.
- **iPhone: not possible.** There is no iOS equivalent to AccessibilityService for
  third-party input injection without jailbreaking.

### Default gestures

| Gesture       | Default action  |
| ------------- | --------------- |
| Open palm     | Back            |
| Fist          | Home            |
| Point (index) | Tap             |
| Peace/victory | Swipe right     |
| Thumbs up     | Swipe up        |
| Thumbs down   | Swipe down      |

Every gesture is remappable in the app's settings screen, including to **Launch app** (pick
any installed app from a list) — e.g. make "peace sign" open Instagram directly.

### Install it (no computer needed)

Every push to `main` under `android/` auto-builds via GitHub Actions
(`.github/workflows/android-build.yml`) and publishes two APKs to this repo's
[Releases](../../releases/tag/android-latest) page:

- **`app-release.apk` — try this first.** Signed with a real (non-debug) key generated fresh
  each build. Some OEM skins (ColorOS/Realme UI, MIUI, ...) silently reject debug-signed
  sideloaded apps with a generic "App not installed" error — the release build avoids that.
- `app-debug.apk` — standard debug build, kept for comparison if you're debugging an install
  issue.

1. On your phone, open the repo's Releases page and download `app-release.apk`.
2. Tap the downloaded file to install; Android will prompt you to allow "install unknown
   apps" for your browser/file manager the first time — allow it. On ColorOS/Realme UI:
   **Settings → Additional Settings → Privacy → Special app access → Install unknown apps**
   → pick the app you're installing from → allow it there specifically.
3. Open the app, tap **Grant camera permission**, then **Turn on Accessibility permission**
   (this opens Android Settings — find "Hands-Free Gestures" under Downloaded/Installed
   apps and enable it).
4. Flip the **Gesture control running** switch on.
5. Switch to any app (Reels, Shorts, Kindle, a browser) and show a gesture in front of the
   front camera.

### Build it yourself

```bash
cd android
./gradlew assembleDebug   # needs the Android SDK; see android/app/build.gradle.kts for versions
```
