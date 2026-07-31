# Obsidian - Privacy-Focused Android WebView Browser

Obsidian is a native Android WebView browser built with Kotlin and Jetpack Compose. Enter any URL to open it full screen, with multiple tabs, ad/tracker blocking, and privacy protections built in.

## Features

- **URL-entry home screen**: type a website and tap Done to open it full screen — no visible URL bar or browser chrome while browsing.
- **Multiple tabs**: open several sites at once, switch between them from the home screen.
- **Hidden tabs with a 6-digit PIN**: hide any tab from the tab list; a PIN (stored only as a salted hash, never in plain text) is required to reveal it again. Hidden tabs re-lock automatically when you leave the app or background it.
- **Ad & tracker blocking**: a broad blocklist of ad networks and analytics/tracking domains is blocked at the network level.
- **Pop-up blocking**: `window.open`/new-window attempts are blocked outright.
- **WebRTC IP-leak protection**: blocks `RTCPeerConnection` before any page script runs, closing a common way sites bypass a VPN to discover your real IP.
- **Privacy hardening**: third-party cookies blocked, no saved form data, Google Safe Browsing telemetry disabled, geolocation requests auto-denied, screenshots/recents-preview blocked (`FLAG_SECURE`), and all cookies/cache/history are wiped when the app closes.
- **Downloads & offline support**: native `DownloadManager` integration with a Downloads screen, offline banner, and automatic reload when connectivity returns.

## Prerequisites

- **Android Studio**: recent stable version.
- **JDK**: 17.
- **Min SDK**: Android 8.0 (API 24).

## Configuring your website

There's no fixed target site — you enter any URL from the app's home screen at runtime. No code change is needed to point it at a different site.

## Building locally

1. Open the project root in Android Studio and let it sync.
2. Build a debug APK:
   ```bash
   gradle :app:assembleDebug
   ```
   Build a release APK:
   ```bash
   gradle :app:assembleRelease
   ```
3. Run unit tests:
   ```bash
   gradle :app:testDebugUnitTest
   ```

This project doesn't include a Gradle wrapper (`gradlew`), so use a system-installed `gradle` (as above), or in Android Studio just click **Run**/**Build** — it uses its own bundled Gradle automatically.

## Building an APK from GitHub (no local setup needed)

A workflow at `.github/workflows/build-apk.yml` builds a debug APK automatically on every push, and can also be triggered manually.

1. Push this project to a GitHub repository.
2. In the repo, go to the **Actions** tab.
3. If it doesn't start automatically, select **Build APK** → **Run workflow**.
4. Once it finishes (green check), open the run → scroll to **Artifacts** → download `obsidian-debug-apk`.
5. Unzip it — that's your installable `.apk`. Transfer it to your phone and install it (you'll need to allow "install from unknown sources" for whichever app you use to open it).

Note: this builds a **debug APK**, which is fine for installing on your own device but isn't signed for the Play Store. If you need a signed release build, that requires a signing keystore — ask and it can be added to the workflow as a secret.

## Key configuration

- **Application ID**: `com.example.weblite`
