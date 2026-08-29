# Play Console submission notes

Everything Play asks for that is not the AAB itself. Fill the console from here rather than
re-deriving it each time, and update this file when the app changes what it does.

> Play policy changes; this is what applied when it was written (2026-08). Check the console's
> own wording before submitting.

---

## App category and audience

- **Category:** Tools, or Accessibility if offered. The listing must read as an accessibility
  tool, because that framing is what makes the accessibility service permissible at all — see
  the declaration below.
- **Target audience:** 13+. Not directed at children.
- **Ads:** none. **In-app purchases:** none.

## Privacy policy URL

Host [PRIVACY.md](PRIVACY.md) and give Play the URL. The simplest route with no extra
infrastructure is GitHub Pages: repository **Settings → Pages → Source: main, folder `/docs`**,
which publishes it at `https://selvavishnum.github.io/Ai-motion-option/PRIVACY`.

## Data safety form

The honest answers are almost all "no", because the app has no backend to send anything to.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data encrypted in transit? | N/A — no user data is transmitted |
| Do you provide a way for users to request that their data is deleted? | **Yes** — "Forget" on the training screen, and uninstalling |

**Why "no collection" is the correct answer, not a convenient one.** Play defines *collection* as
transmitting data off the device. Air Sensor's camera frames are analysed in memory and released;
recorded gesture examples and settings stay in the app's private storage. Nothing is uploaded,
because there is no server to upload it to.

If a reviewer queries the camera permission, the answer is the on-device processing described in
the privacy policy — the same thing the app's own notification tells the user while it runs.

## Permissions declaration — accessibility service

This is the one that decides the submission.

**Which permission:** `BIND_ACCESSIBILITY_SERVICE`.

**Declared use — paste this, or something close to it:**

> Air Sensor is an accessibility tool for people who cannot reliably touch a touchscreen —
> through motor impairment, tremor, limited hand mobility, or temporary injury. The front camera
> detects hand and face movements, and the accessibility service performs the corresponding
> swipe, tap, pinch, Back, Home, Recents or app launch, so the phone can be operated without
> touching it. This is the app's entire and only function.
>
> The service performs actions only. It is declared with `canRetrieveWindowContent="false"` and
> subscribes to no accessibility events, so it cannot read the content of any screen. It is
> declared `isAccessibilityTool="true"`.
>
> There is no alternative API. Android exposes no other way for an app to inject a swipe or tap
> into another app, which is what hands-free control of the whole device requires.

**Supporting points if a reviewer pushes back:**

- `android:isAccessibilityTool="true"` is set.
- `android:canRetrieveWindowContent="false"` — screen content is never read.
- `accessibilityEventTypes` is absent entirely, so the service receives no events at all. Most
  apps that are refused ask for `typeAllMask`; this one asks for nothing.
- The app uses `<queries>` rather than `QUERY_ALL_PACKAGES` to list launchable apps.
- No data leaves the device.

**Demo video.** Reviewers normally ask for one. Script in the next section.

## Demo video script

Unlisted YouTube link, 60–90 seconds, screen recording of a real device with a second camera or
mirror showing the hand. No narration needed if captions are used — but say the accessibility
purpose out loud or in text within the first ten seconds.

1. **(0:00–0:10) What it is.** Caption: *"Air Sensor lets you use an Android phone without
   touching the screen — for people who can't reliably use a touchscreen."*
2. **(0:10–0:25) Granting the permission.** Show Settings → Accessibility → Air Sensor being
   turned on. This is what the reviewer needs to see is user-initiated.
3. **(0:25–0:50) The actual purpose.** Open a scrolling app. Point one finger and sweep down —
   the page scrolls. Sweep up. Show a fist mapped to Home returning to the launcher. Keep the
   hand visible and never touch the screen.
4. **(0:50–1:05) The accessibility case, explicitly.** Caption: *"Every action here would
   otherwise require touching the screen."*
5. **(1:05–1:20) What it does not do.** Caption: *"The service performs gestures only. It cannot
   read screen content, and no data leaves the device."* Optionally show the app's own privacy
   text on screen.

## Before uploading

- [ ] `targetSdk` meets Play's current minimum (35 at the time of writing; 36 from 31 Aug 2026)
- [ ] Upload the **AAB**, not the APK — `android/app/build/outputs/bundle/release/app-release.aab`,
      built by CI on every push to `android/`
- [ ] Signing secrets configured so the bundle is actually signed — see [SIGNING.md](SIGNING.md)
- [ ] Privacy policy URL live and reachable
- [ ] Data safety form submitted
- [ ] Accessibility permissions declaration submitted, with the demo video link
- [ ] Store listing describes it as an accessibility tool, matching the declaration
- [ ] Screenshots: the main screen, the gesture guide, the training screen
- [ ] New personal developer accounts: 12 testers on closed testing for 14 continuous days before
      production is unlocked

## Known review risks

- **The accessibility declaration is the whole submission.** Everything above exists to make it
  credible. If it is refused, appeal with the four supporting points and the video rather than
  changing the app.
- **`SYSTEM_ALERT_WINDOW`** needs no declaration form but is worth explaining in the listing: it
  draws the optional status dot and finger pointer, both of which the user can switch off.
- **Foreground service type `camera`** requires the FGS declaration in the console, justified by
  the same purpose: detection must continue while another app is in the foreground, which is the
  only moment gesture control is useful.
