# Air Sensor — Privacy Policy

_Last updated: 2026-08-28_

Air Sensor is an accessibility tool that lets you operate your phone with hand and face
movements instead of touching the screen. This page describes exactly what it does with your
data. It is short because the app does very little.

## The short version

**Air Sensor collects nothing, transmits nothing about you, and has no servers.** There are no
accounts, no analytics, no advertising, and no third-party tracking of any kind.

## Camera

Air Sensor uses the front camera to see your hand and face.

- **Frames are analysed on your device and discarded immediately.** Each frame is converted into
  landmark coordinates — a set of points describing where your knuckles and facial features are —
  and the image itself is released as soon as that finishes.
- **No image, video, or frame is ever saved to storage or sent anywhere.** Not to us, not to
  anyone. There is nowhere for it to go: the app has no backend.
- The camera runs only while gesture control is switched on. Switching it off — in the app or
  from the Quick Settings tile — stops the camera. A notification is shown the whole time the
  camera is in use, and by default the camera is released while your screen is off.

## Gestures you record

If you use **Teach your gestures**, the app stores examples of your hand shapes so it can match
your hand rather than a built-in rule.

- Each example is **42 numbers** describing joint positions relative to your wrist, after
  position, size and rotation have been removed. It is not an image and cannot be turned back
  into one.
- These stay in the app's private storage on your phone. They are never uploaded.
- **Forget** on the training screen deletes them; uninstalling the app deletes them.

## Settings

Your gesture mappings, sensitivity level, and on/off switches are stored in the app's private
storage on your phone. Nothing else is stored.

## Accessibility service

Air Sensor uses Android's accessibility service to perform the swipes, taps and Back/Home
presses your gestures are mapped to.

- **It does not read the content of your screen.** The service is declared with
  `canRetrieveWindowContent="false"` and subscribes to no accessibility events, so it is not
  capable of seeing what is displayed in this or any other app.
- It only performs actions, on command from the gesture detector inside Air Sensor.

## Network

Air Sensor makes network requests for exactly one purpose: **downloading the two on-device
detection models**, once, from Google's public MediaPipe model storage
(`storage.googleapis.com`). Nothing about you is included in that request — it is an anonymous
file download, the same as fetching any public URL. After it completes the app works entirely
offline.

## Permissions and why

| Permission | Why |
|---|---|
| Camera | See your hand and face. Frames are processed on-device and discarded. |
| Accessibility service | Perform the swipe/tap/Back/Home your gesture is mapped to. Cannot read screen content. |
| Display over other apps | Draw the optional floating status dot and the optional finger pointer. Both can be switched off. |
| Notifications | Show the ongoing notification required while the camera runs. |
| Internet / network state | The one-time model download described above. |
| Wake lock | Briefly wake the display for the "wake screen" gesture. |

## Children

Air Sensor is not directed at children and collects no data from anyone, including children.

## Changes

If this policy changes, the date at the top changes with it, and the previous versions remain in
this repository's history.

## Contact

Questions or a privacy concern: open an issue at
<https://github.com/selvavishnum/Ai-motion-option/issues>.
