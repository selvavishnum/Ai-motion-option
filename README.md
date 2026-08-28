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
  another app is in the foreground. Each frame is rotated upright and mirrored before
  detection — the sensor buffer arrives rotated 90° on a phone held normally, and the hand
  classifier reads finger positions off the image axes, so an uncorrected frame turns every
  hand pose into "unknown".
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
- A **Quick Settings tile** ("Air gestures") switches detection on and off from the pull-down
  shade, next to Wi-Fi and Torch — no need to open the app. Tap **Add Quick Settings tile** in
  the app to have Android offer to place it (Android 13+; on Android 12, add it by hand from
  the shade's edit screen). The tile mirrors the service's real state rather than its own, so
  it stays correct when the service is started from the app or stopped by the system, and it
  greys out as *Setup needed* until Camera and Accessibility are granted. Adding the tile does
  not grant anything: it is only a switch for what the app already has permission to do.
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

Hand and face detection each have their **own on/off switch** on the main screen. That's a
speed control as much as a preference: the camera's frames are a fixed budget, and when both are
enabled they alternate frame-by-frame, so switching off the one you don't use roughly halves how
long the other takes to react.

| Gesture                              | Default action  |
| ------------------------------------- | --------------- |
| Open palm                             | Wake screen     |
| Fist (closed palm)                    | Screen off (needs device admin) |
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

### Gesture sensitivity

One dial, 1 (least sensitive) to 5 (most), on the main screen. It rescales every movement
threshold and the pose debounce together — a lower movement threshold lets noisier input through
to the classifier, which is exactly when more frames of confirmation are worth paying for.

It is one control rather than the six numbers it drives because the complaint behind it is always
one of two things: *"it triggers when I didn't mean to"* (lower it) or *"I have to move miles
before it notices"* (raise it). Level 3 is the default and reproduces the behaviour from before
the setting existed. The running service re-reads it every frame, so dragging the slider retunes
detection live.

### Teach your own gestures

The rule-based classifier encodes one opinion about what a fist looks like, tuned on one pair of
hands. It cannot be right for everyone — finger length, how far someone actually curls, whether
they can fully extend a finger at all — and for an accessibility tool that last one is the whole
point.

**Teach your gestures** (main screen) records five examples of a gesture as you actually make it,
and from then on matches your hand instead of the rule. There is no training framework and no
model file: landmarks are normalised to remove position, size and rotation, and compared against
the average of your recordings. For a handful of examples per gesture that is both the simplest
and the most accurate option — a network fitted to five samples would mostly be fitting noise.

- **Recording needs gesture control switched on.** The detection service already owns the camera;
  a second camera in the training screen would stop gesture control while you record the gestures
  gesture control is meant to use.
- **Your recordings never leave the phone**, and nothing about them is an image — they are 42
  numbers per example describing joint positions relative to your wrist.
- **A poor match falls back to the built-in rule** rather than guessing. If the closest recorded
  gesture is still far away, or two match almost equally well, the frame goes to the rules: a
  wrong Home press costs more than a missed one.

The geometry is unit-tested in `tests/test_gesture_templates.py` against the Python reference
(`app/gesture_templates.py`), including the property the whole feature rests on — the same
gesture, moved, resized and tilted, normalises to the same numbers.

### Known limitations (by design, not bugs)

- **"Screen off" needs a device-admin grant.** Android gives an ordinary app no API to turn the
  display off. The only sanctioned route is becoming a device admin holding the `force-lock`
  policy and calling `lockNow()`, so palm-close does work — but only after you tap **Allow
  screen off (device admin)** and accept a deliberately scary-looking system dialog. The policy
  file requests *force-lock only*: no data wipe, no password control. Without the grant the
  gesture is a silent no-op. Revoke any time in Settings → Security → Device admin apps.
- **Banking and UPI apps will refuse to pay while gesture control is on. That is correct, and
  it is not a bug.** Google Pay, PhonePe, Paytm and bank apps check, before every payment,
  whether an accessibility service is enabled or a window is drawn over the screen — and refuse
  with something like *"Your payment is declined for security reasons."* if either is true. The
  reason is straightforward: an accessibility service can read the screen and inject taps, and
  an overlay can cover the real PIN pad with a fake one. That is precisely how UPI-fraud apps
  drain accounts, and the payment app cannot tell Air Sensor apart from one. Air Sensor asks
  for both of those permissions, so it trips the check.

  **To pay: switch gestures off, pay, switch them back on.** The Quick Settings tile makes this
  two taps. If a payment is still declined with the service stopped, also turn off the floating
  bubble in the app, and if it *still* declines, turn Air Sensor's Accessibility permission off
  in Settings → Accessibility for the duration — some apps check whether the permission is
  granted at all, not just whether the service is running.

  This app does not attempt to bypass that protection, and won't. Anything that hid Air Sensor
  from those checks would be, functionally, exactly the technique overlay malware uses.
- **Latency is tuned per gesture, and is not literally zero.** Camera exposure, model
  inference, and gesture dispatch each cost real milliseconds. What's controllable is the frame
  budget, and that dominates everything: the debounce counts *frames*, so the frame rate sets
  how long a pose takes to confirm. At 60ms per frame a discrete pose confirms in roughly
  180ms with only one modality enabled, or ~360ms with both (down from ~720ms). Continuous
  motions — the air trackpad and pinch-zoom — bypass that debounce entirely and fire on a ~90ms
  cooldown. Discrete high-impact actions (screen off, Home, Back) deliberately keep the
  three-frame confirmation so a hand passing through a pose mid-motion can't trigger them.
- **Gaze (look left/right) and air-trackpad direction follow a mirror, not the raw camera.**
  Camera frames are rotated upright and flipped horizontally before detection, so the frame
  matches what you'd see in a mirror: move your hand to *your* right and it swipes right. Both
  signals are computed from raw landmark positions rather than a trained classifier, so if
  either still reads reversed on your device it's a one-line sign flip — say which one.

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
3. If **Turn on Accessibility permission** or **Allow floating status bubble** shows
   **"App was denied access"**, that's Android's *Restricted settings* gate: Android 13+ blocks
   both permissions for any app installed outside an app store. It is an OS security control, so
   no app — including this one — can turn it off for itself; you unlock it once, by hand. The
   catch is that the block appears on the Accessibility/overlay screens while the unlock lives on
   the app's own **App info** page, so the in-app **Got "App was denied access"? Fix it** button
   takes you straight there with the steps:

   **Settings → Apps → App management → Air Sensor → ⋮ (top right) → Allow restricted settings**

   Then come back and grant Accessibility and the bubble. One-time per install. If that menu item
   isn't there at all (some ColorOS/MIUI builds hide it), the only remaining route is ADB from a
   computer:

   ```bash
   adb shell appops set com.aimotion.handsfree ACCESS_RESTRICTED_SETTINGS allow
   ```
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

### Signing key

Release builds are signed with a key held in GitHub Actions secrets, never in this repository —
see [docs/SIGNING.md](docs/SIGNING.md) for the one-time setup. Until those secrets exist, CI
produces `app-release-unsigned.apk`, which a phone cannot install; use `app-debug.apk` for
sideloading in the meantime.

### Build it yourself

```bash
cd android
./gradlew assembleDebug   # needs the Android SDK; see android/app/build.gradle.kts for versions
```

### Battery

The camera is the dominant cost — there is no low-power gesture sensor to fall back on, so the
front camera runs and every frame is fed to an on-device model. Three things keep that in check,
two of them automatic:

- **Idle throttling (automatic).** Most of the day nothing is in front of the phone. After five
  seconds with no hand or face visible the loop drops from ~16 fps to ~3 fps, and snaps back the
  instant a hand or face appears — before you have even formed a pose. The only cost is up to one
  extra 300ms before the first gesture of a session registers.
- **Screen-off release (setting, on by default).** With the screen off the phone is usually
  pocketed or face down, so the camera films nothing. **Save battery when screen is off** unbinds
  the camera entirely, letting the hardware power down; merely skipping frames would not, since
  the sensor keeps streaming either way. The trade-off is that no gesture can wake the screen
  while nothing is watching, so turn it off if you rely on palm-to-wake.
- **Switch off the modality you don't use.** Air and Face detection each cost a model inference.
  Turning one off halves the work — and makes the other roughly twice as fast to react.

Battery-optimisation exemption is still worth granting: it stops OEM battery managers
(ColorOS/Realme UI, MIUI) from killing the service outright, which is a reliability setting, not
a power one.

### Wave gestures — no camera

The proximity sensor (the one that blanks the screen during a call) can detect a hand passing
over the top of the phone. That gives a **third, camera-free trigger modality**, enabled with
**Wave gestures (no camera)**:

| Wave | Default action |
| ---- | -------------- |
| Wave once | Wake screen |
| Wave twice | Home |

Both are remappable, in their own section of the mapping list.

**It is deliberately tiny, because the hardware genuinely cannot do more.** The sensor reports
one thing — something is near, or nothing is near. There is no shape, no direction, no usable
distance. Palm vs fist, scroll direction, pinch and every face gesture need the camera; nothing
on the phone can substitute for actually seeing your hand.

What it buys instead is power and coverage:

- It is hardware-triggered and costs a fraction of a milliamp, against the camera's continuous
  capture plus model inference.
- It keeps working while the screen is off — exactly the window where the camera is released to
  save battery. So *wave to wake the screen* still works with **Save battery when screen is off**
  enabled, which would otherwise be a straight choice between the two.

Holding your hand over the sensor does nothing on purpose: anything covered for more than ~1.2s
is a pocket, a face-down phone or a call, not a gesture. Without that guard the mode would fire
constantly in a pocket.

If the phone has no proximity sensor the switch is disabled and says so.

### Updating without losing your settings

The release APK is signed with a **committed keystore** (`android/app/airsensor-release.keystore`,
password in `app/build.gradle.kts`). That is deliberate, and worth explaining because committing a
signing key is normally wrong.

CI previously generated a throwaway key on every run. Every APK was therefore signed differently,
and Android refuses to install one build over another when the signature changes
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, surfaced as a bare "App not installed"). Updating meant
uninstalling first — which deletes every saved gesture mapping and permission grant. A stable key
is what makes an update actually an update.

**This key is not a secret.** Anyone with the repository can produce an APK that Android will
accept as an update to this app. That trade is only acceptable because this is a personally
sideloaded app distributed through nothing. If this were ever published properly, generate a
private key, keep it out of the repository, and pass it in through the `RELEASE_KEYSTORE_PATH`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` environment variables,
which still take precedence over the committed one.

One-time step: because builds before this change were signed with a different (random) key, the
**first** install after it still needs an uninstall. Every update after that installs cleanly over
the top.
