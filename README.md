# Hermes Monitor (Android)

A dark, edge-to-edge **WebView shell** around the existing Mission Control web
dashboard (`mission-control/server.py`). It does not reimplement the dashboard —
it loads it, adds first-run connection setup, safe-area handling, offline
fallback, and **polling-driven local notifications** for critical alerts.

- Package: `com.demongroker.hermesmonitor`
- Kotlin, minSdk 26, targetSdk 35, compileSdk 35, AGP 8.7.3, Gradle 8.9, JDK 17

## Features

- **First-run setup**: enter the dashboard base URL + token, tap **Connect**.
  The app POSTs `/api/login` and reports success/failure inline. Both values
  are persisted and editable later via the ⚙ settings action in the header.
- **WebView hardening**: JavaScript + DOM storage enabled; cookie persistence
  via `CookieManager` (the `mc_session` cookie from `/api/login` is injected so
  the dashboard's own fetches authenticate); mixed content **never** allowed;
  file/content access disabled; SSL errors always **cancel**; external links
  open in the system browser, not the WebView.
- **Safe areas**: `WindowCompat.setDecorFitsSystemWindows(false)` +
  `ViewCompat.setOnApplyWindowInsetsListener` pad the root by the real
  system-bar/cutout insets; the dashboard page additionally uses
  `env(safe-area-inset-*)`.
- **Always dark**: navy `#0B1220` background, gold `#D4AF37` accent. Window and
  WebView backgrounds are navy from the first frame — no white flash.
- **Offline handling**: main-frame load failures (network or HTTP ≥ 400) show a
  native navy error view with a **Retry** button. Never a blank white screen.
- **Critical-alert notifications without Firebase** — see below.

## Pointing the app at the real API

1. Make sure the phone is on the same tailnet as the OptiPlex.
2. Launch the app; on the setup screen enter:
   - Base URL: `http://100.105.81.82:8791` (the Tailscale-only bind of
     `mission-control/server.py`)
   - Token: the dashboard token configured on the server.
3. Tap **Connect**. On success the dashboard loads immediately.

The base URL is user-supplied at runtime; **no URL or token is hardcoded or
embedded in the APK**. Because the dashboard is served over plain HTTP on a
Tailscale-only address, the manifest sets `usesCleartextTraffic="true"`;
WebView mixed content is still never allowed and bad SSL certificates always
abort the connection.

## How the token is stored

The token (and base URL) live in **EncryptedSharedPreferences**
(`androidx.security:security-crypto`), encrypted with an AES-256-GCM master key
held in the **Android Keystore**. Nothing secret is written to logs, bundled in
resources, or committed to this repo. API polling sends the token as a
`Authorization: Bearer <token>` header; the WebView session uses the HttpOnly
cookie returned by `/api/login`.

## Notifications: polling-driven local notifications, NOT server push

There is **no Firebase/FCM** and no push channel. Instead:

- A WorkManager `PeriodicWorkRequest` runs `AlertWorker` every **15 minutes**
  (WorkManager's minimum period; actual timing is battery/Doze-approximate).
- An **immediate one-time check** runs on app start and after a successful
  Connect.
- Each run GETs `/api/state` with the stored Bearer token and posts a local
  notification for every alert with `severity == "critical"` that has not
  already been notified. Dedupe is by alert `id` in SharedPreferences (bounded
  to the most recent 500 ids).
- The `critical_alerts` channel (high importance) is created at app startup;
  `POST_NOTIFICATIONS` is requested on Android 13+. Tapping a notification
  opens the app.

**Limits**: alerts can be delayed by up to ~15 minutes (more under Doze); if
the app is force-stopped, periodic work stops until the app is opened again;
no notifications are delivered while the device is offline; denying the
notification permission on Android 13+ silences alerts. This is best-effort
local polling, not a real-time push guarantee.

## Building

Requires JDK 17 and the Android SDK (compileSdk 35). **Do not run heavy Gradle
builds on the OptiPlex host (thermal limit)** — use CI.

```bash
./gradlew assembleDebug      # debug APK (unsigned release config is skipped)
./gradlew assembleRelease    # release APK; signed if KEYSTORE_* env vars are set
```

Release signing reads env vars (never committed):

| Env var             | Meaning                          |
|---------------------|----------------------------------|
| `KEYSTORE_FILE`     | path to a PKCS12 keystore        |
| `KEYSTORE_PASSWORD` | store password (== key password) |
| `KEY_ALIAS`         | key alias                        |
| `KEY_PASSWORD`      | key password (== store password) |

Without them, `assembleRelease` produces an unsigned APK.

## CI / GitHub Actions

`.github/workflows/build.yml` builds `assembleRelease` on every push to `main`
and on tags `v*`, verifies the signature with `apksigner`, uploads the APK +
`.sha256` as an artifact, and on a `v*` tag publishes a GitHub release with
both files.

Required repository secrets (set via `gh secret set`, never committed):

- `KEYSTORE_BASE64` — base64 of the PKCS12 keystore (`base64 -w0 release.jks`)
- `KEYSTORE_PASSWORD` — store password
- `KEY_ALIAS` — key alias
- `KEY_PASSWORD` — key password (must equal the store password for AGP)

The keystore was generated locally with `keytool -genkeypair -storetype
PKCS12` (RSA 4096, storepass == keypass) and lives outside this repo. Keep it
safe: losing it means future updates cannot install over this app.

## Installing

Download `hermes-monitor-<version>.apk` from the repo's Releases, verify the
checksum (`sha256sum -c hermes-monitor-<version>.apk.sha256`), and sideload.
First launch walks you through the setup screen.
