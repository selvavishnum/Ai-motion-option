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

## Android app: Air Sensor — system-wide gesture control

Unlike the web demo above (which only reacts inside its own page), the Android app in
`android/` (app name **Air Sensor**) runs entirely on-device and can control **whatever app is
currently on screen** — scroll Reels/Shorts, turn a Kindle page, pinch-zoom a photo, scroll a
browser article, go back/home, open the app switcher, or launch another app — using air-gestures
picked up by the front camera. No server needed; detection runs fully offline after the
one-time model download. Targets Android 12+ (`minSdk 31`).

### How it works

- **CameraX** captures the front camera in a foreground service, continuously, even while
  another app is in the foreground.
- **MediaPipe Hand Landmarker** (on-device) finds hand landmarks in each frame; the same
  rule-based classifier as `app/gesture.py`, ported to Kotlin (`gesture/Gesture.kt`), turns
  them into a gesture. Thumb/index-tip distance is tracked continuously across frames for
  pinch-to-zoom, independent of the discrete poses below.
- **MediaPipe Face Landmarker** (on-device, blendshapes) runs on alternating frames from the
  same camera feed, classifying blink/eyebrows-up/mouth-open/smile the same way — a second,
  independent set of triggers mappable to any action, running at the same time as hand poses.
- An **AccessibilityService** — a permission you must turn on manually in Android Settings,
  since no app can grant this to itself — is what actually acts: it simulates a swipe/tap/pinch
  gesture, a Back/Home/Recents press, or launches another app, exactly as if you'd touched the
  screen.
- An optional **floating status bubble** (the "draw over other apps" permission) confirms the
  service is watching and gives a one-tap way back into the app, from anywhere.
- The app can request exemption from OEM battery optimization, which otherwise tends to kill
  background services on skins like ColorOS/Realme UI and MIUI.

### Hard limits (Android platform, not this app)

- **"Next app" opens the app switcher, not a silent jump.** Android exposes no API for one
  app to read or control another app's identity — only the Recents/switcher UI is available,
  same as swiping up-and-hold on stock gesture navigation. From there, you tap the app.
- **"Close app" means Home, not force-quit.** Android does not let one app terminate
  another, with or without Accessibility permission — only the user (or root) can.
  "Close" here backgrounds the app via the Home action.
- **Only works on whatever's visible.** A simulated swipe/pinch lands on the foreground app,
  so gesture control only affects the app currently on screen — same as a real touch would.
- **Accessibility, overlay, and battery-exemption permissions are manual, every time.** This
  is an Android security requirement, not a bug — no app can silently grant itself this level
  of control.
- **iPhone: not possible.** There is no iOS equivalent to AccessibilityService for
  third-party input injection without jailbreaking.

### Gestures

| Gesture                              | Default action  |
| ------------------------------------- | --------------- |
| Open palm                             | Wake screen     |
| Fist (closed palm)                    | Home            |
| Peace/victory                         | Swipe right     |
| Thumbs up                             | Swipe up        |
| Thumbs down                           | Swipe down      |
| Point (index), moving                 | Air trackpad: turn/scroll |
| Point (index), held still             | Air trackpad: tap/select |
| Two fingers, spreading apart          | Pinch zoom in   |
| Two fingers, pinching in              | Pinch zoom out  |

Every discrete pose in the table above (all but the last three rows) is remappable in the
app's settings screen — to any of Swipe (4 directions), Tap, Back, Home, **Recents** (app
switcher / "next app"), **Wake screen**, or **Launch app** (pick any installed app from a
list, e.g. make "peace sign" open Instagram directly). The single-finger air trackpad and
two-finger pinch-zoom run automatically whenever a hand is visible making that shape — they
aren't part of the remappable table since they're continuous motions, not single poses:
moving your pointed finger turns/scrolls in that direction, and holding it still briefly
taps/selects.

**Face gestures** (also remappable, run at the same time as hand gestures, independent
defaults so the two don't collide):

| Face gesture      | Default action        |
| ------------------ | --------------------- |
| Blink (both eyes)  | Select / open app (Tap) |
| Wink left eye only | Back / exit app        |
| Wink right eye only| Home                   |
| Eyebrows up        | Scroll up              |
| Eyebrows down      | Scroll down            |
| Look left (gaze)   | Turn/slide left        |
| Look right (gaze)  | Turn/slide right       |
| Mouth open         | Recents                |
| Smile              | Select / open app (Tap) |

Tap **Gesture guide** in the app for a visual cheat-sheet of every hand and face gesture, how
to perform it, and its current mapped action.

### Known limitations (by design, not bugs)

- **No "screen off" gesture.** Android gives ordinary apps no API to turn the screen off —
  only Device Admin's `lockNow()` comes close, and that requires a heavy, rarely-granted
  permission. Palm-close maps to Home instead of a lock; open-palm wakes the screen back up.
- **The "install unknown sources" / overlay warning on banking apps is not a bug.** Android
  deliberately blocks overlay windows (and warns about them) on screens a banking/finance app
  marks as secure, to stop tapjacking-style attacks — the exact pattern used by overlay
  malware. This app does not attempt to bypass that protection, and won't.
- **Latency is tuned per gesture, not zero everywhere.** Continuous motions (the air
  trackpad, pinch-zoom) use a short debounce so they feel closer to real-time. Discrete
  high-impact actions (Home, Back, wake) keep a slightly longer debounce/cooldown so an
  accidental half-second pose doesn't misfire them.
- **Gaze (look left/right) and air-trackpad turn/scroll direction are best-effort.** Both are
  computed from raw landmark positions rather than a pre-trained classifier, so their sign
  (which way is "left") can vary slightly by camera/mirroring setup. If either feels reversed
  on your device, that's a one-line fix — report which one and which direction is wrong.

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
3. If **Turn on Accessibility permission** or **Allow floating status bubble** shows "App was
   denied access" with no toggle: Android 13+ blocks these "restricted settings" by default for
   any app installed outside the Play Store. Go to **Settings → Apps → Air Sensor → ⋮ (top
   right) → Allow restricted settings**, then try again — one-time per install.
4. Grant camera + Accessibility, then optionally **Allow floating status bubble** and
   **Ignore battery optimization** (recommended so the background service survives OEM
   battery managers).
5. Flip the **Gesture control running** switch on.
6. Switch to any app (Reels, Shorts, Kindle, a browser) and show a gesture in front of the
   front camera.

Also worth knowing: Google Play Protect may show "App blocked to protect your device" for this
class of app (Camera + Accessibility + Overlay + background-run permissions together look like
spyware to an automated scanner, even though this is exactly what a gesture-control app needs).
To install anyway: **Play Store → profile icon → Play Protect → gear icon → turn off "Scan
apps with Play Protect"**, install, then turn scanning back on.

### Build it yourself

```bash
cd android
./gradlew assembleDebug   # needs the Android SDK; see android/app/build.gradle.kts for versions
```
