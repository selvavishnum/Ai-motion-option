# Signing key setup

Air Sensor's release builds are signed with a key that lives **outside this repository**. CI
reads it from GitHub Actions secrets; nothing about the key is ever committed.

This page is the one-time setup. After it, every build on `main` produces a signed APK, and the
same key is what you upload to Play Console.

## What you are creating

A **keystore** — one file holding one private key, protected by a password. Android uses it to
prove an update to Air Sensor really came from you. That is the whole security model: a phone
accepts an update only if it is signed by the same key as the version already installed.

Which means two rules, and they matter more than anything else on this page:

1. **Never commit it, never share it, never paste it into a chat.** Whoever holds this key can
   publish an update that every installed copy of Air Sensor trusts.
2. **Back it up somewhere you will still have in five years**, along with its password. Lose the
   key and you cannot update your own app — for sideloaded users, every future install needs an
   uninstall first, which wipes their settings.

(Play softens rule 2 slightly: with Play App Signing, Google holds the real app-signing key and
you can request an upload-key reset if you lose yours. That covers Play only, not sideloading,
and it is a recovery process — not a reason to be casual.)

## Step 1 — create the keystore

You need `keytool`, which ships with any JDK.

### On a computer

```bash
keytool -genkeypair -v \
  -keystore airsensor-release.keystore \
  -alias airsensor \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

### On the phone, with no computer

Install **Termux** (from F-Droid — the Play Store build is unmaintained), then:

```bash
pkg update && pkg install openjdk-17
cd ~/storage/shared      # so the file lands somewhere you can find it
# then the same keytool command as above
```

Either way it asks for a password, then for a name and organisation. The name fields are
cosmetic — they appear in the certificate, nowhere a user sees. The **password is not**: write
it down before you type it.

`-validity 10000` is about 27 years. Play requires a certificate valid past 2033, and an expired
signing certificate cannot be renewed — it ends the app. Do not shorten this.

Use the **same password** for the store and the key when prompted (press Enter to reuse it).
Different passwords work, but they are two more things to lose.

## Step 2 — turn it into text

GitHub secrets hold text, not files, so the keystore is stored base64-encoded.

```bash
base64 -w0 airsensor-release.keystore > keystore.base64.txt
```

On macOS, which has no `-w0`:

```bash
base64 -i airsensor-release.keystore -o keystore.base64.txt
```

`-w0` (or macOS's default) matters: the output must be **one single line** with no wrapping, or
the decode in CI produces a corrupt file.

## Step 3 — add four repository secrets

GitHub → your repository → **Settings** → **Secrets and variables** → **Actions** →
**New repository secret**. Add these four, named exactly:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the entire contents of `keystore.base64.txt` |
| `RELEASE_KEYSTORE_PASSWORD` | the store password from step 1 |
| `RELEASE_KEY_ALIAS` | `airsensor` (or whatever you passed to `-alias`) |
| `RELEASE_KEY_PASSWORD` | the key password — the same one, if you reused it |

Secrets are write-only: once saved, nobody (including you) can read them back, only replace
them. That is the point, and it is also why your own backup of the keystore file matters.

## Step 4 — delete the working copies, keep the real one

```bash
rm keystore.base64.txt
```

Keep `airsensor-release.keystore` itself — in a password manager, an encrypted backup, anywhere
that is not this repository. `android/.gitignore` blocks `*.keystore` and `*.jks` so a stray
copy in the working tree cannot be committed by accident, but that is a safety net, not a plan.

## Step 5 — check it worked

Push anything that touches `android/`, or run the **Android build** workflow manually from the
Actions tab. In the job log:

- The "Decode signing keystore" step should **not** print
  `No signing secrets configured` — that line means at least one secret is missing or misnamed.
- The release artifact should be `app-release.apk`. If it is `app-release-unsigned.apk`, the
  build ran without a key.

The signed APK installs straight over a previous signed build. Going *from* an
unsigned/debug-signed install *to* this one still needs an uninstall once, because the signature
changes — that is Android refusing to let a different key take over an installed app, working
exactly as intended.

## Play Console

Upload `app-release.aab` (the bundle, also built by CI) rather than the APK — Play does not
accept APKs for new apps. Play App Signing is on by default: your key becomes the *upload* key,
Google generates and holds the key end users' devices actually verify. Keep using the same
upload key for every release.
