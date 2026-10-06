# Obsidian 4.0

A privacy-focused Android browser built with Kotlin, Jetpack Compose and a hardened `WebView`.

## What it does

| Area | Behaviour |
|---|---|
| **HTTPS-only** | Typed `http://` addresses and `http://` navigations are upgraded to `https://`. Android blocks cleartext traffic anyway, so sites that only speak plain HTTP will not load. |
| **Site lock** | A page stays inside the site you opened. Same-site links and server redirects work normally, scripted jumps to other sites are blocked, and a link you tap that leads to another site is blocked with an **Open** button so you can follow it on purpose. |
| **Tracker blocking** | Sub-resource requests to known ad / tracker hosts are blocked (whole host labels only, so `bigsegment.com` is never mistaken for `segment.com`). Blocked scripts get harmless stubs so pages keep working. Per-tab shield switch. |
| **Hardened WebView** | Third-party cookies off, no `file://` / `content://` access, WebRTC hidden (feature detection simply reports "unsupported"), `Sec-GPC` / `DNT` signals, reduced user agent, canvas / audio noise and WebGL (1 and 2) vendor masking. See *Limits* below. |
| **Permissions** | Camera and microphone ask you **per request** (site name shown). Location is always denied. Downloads always ask first. |
| **Hidden tabs** | Protected by a 6-digit PIN stored as PBKDF2-HMAC-SHA256. Five wrong tries lock entry for 30 s, doubling up to 15 min. "Forgot PIN" resets the PIN and closes all hidden tabs. Tabs re-lock whenever the app leaves the foreground. |
| **Incognito** | Cookies are not saved while an incognito tab is open, and cookies plus site storage are cleared when it closes. Obsidian uses **one shared browser profile**, so closing an incognito tab also signs you out of your other tabs. Incognito and hidden tabs cannot be bookmarked. |
| **Tor** | Optional routing of the WebView through Orbot (`127.0.0.1:9050`). Android's download manager cannot use Tor, so **downloads are disabled while Tor is on**. |
| **VPN gate** | Browsing needs Proton VPN to be connected. While it is not, the WebView is stopped and paused and its traffic is black-holed, so nothing leaves over your real connection. Downloads are paused too. |
| **Nothing survives** | Cookies, storage and cache are wiped when the app is closed **and** again on the next cold start (a killed app never runs its shutdown code). Screenshots and the recents preview are blocked, and cloud backup / device transfer are disabled. |
| **Privacy dashboard** | Shows what was blocked this session. |

Also: tabs, bookmarks, a downloads manager, offline banner, pull-to-refresh, edge-swipe back/forward, fullscreen video.

## Requirements

* Android 7.0+ (API 24)
* To build: JDK 17 and Gradle 9.3.1 (AGP 9.1.1, Kotlin 2.2.10)

## Build

```bash
gradle assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
gradle testDebugUnitTest          # JVM unit tests (URL rules, blocklist, PIN hashing) + Robolectric
```

A **release** build needs a signing key: set `KEYSTORE_PATH` (default `my-upload-key.jks` in the repo root),
`STORE_PASSWORD` and `KEY_PASSWORD`, then run `gradle assembleRelease`. Without them the release task fails
with "Keystore file not found" by design.

On GitHub Actions the workflow caches `~/.android/debug.keystore`, so every CI APK is signed with the same
debug key and a new build installs over the old one (you no longer have to uninstall first).

## Limits you should know about

* **Fingerprint protection is best effort.** The noise is imperceptible by design, so a tracker that rounds
  pixel values can still see through it. Obsidian is not Tor Browser: even over Tor its WebView fingerprint is
  its own, not a uniform one.
* **The VPN gate cannot see which app owns the tunnel.** It checks "Proton VPN installed" and "the active
  network is a VPN". For a guaranteed kill-switch also turn on Android's *Always-on VPN* +
  *Block connections without VPN* for Proton.
* **Safe Browsing is off** (so no URL prefix is sent to Google). The price: no built-in phishing warning page.
* **Default search engine is Google** (`UrlUtils.SEARCH_URL`). Change that one constant to use another engine.
* The YouTube ad skipper depends on YouTube's page markup and may stop working whenever YouTube changes it.
* Android 9 and older ask for the storage permission the first time you download a file.

## Layout

```
app/src/main/java/com/example/
  MainActivity.kt                 window flags, permission / download dialogs, lifecycle, wiring
  weblite/viewmodel/              MainViewModel: tabs, navigation requests, network policy, counters
  weblite/webview/                WebView setup, navigation policy, blocking, injected privacy script
  weblite/util/                   pure-Kotlin rules (URL handling, blocklist, PIN hashing) with unit tests
  weblite/network, vpn, privacy/  proxy / Tor policy, connectivity, VPN state, wipe, external hand-off
  weblite/data/                   Room database, PIN storage, downloads repository
  weblite/ui/components/          Compose screens and sheets
```
