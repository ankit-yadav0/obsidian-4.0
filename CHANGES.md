# Obsidian 4.0 - fixes and clean-up

Everything found in the code analysis was fixed, and all dead code was removed.
This file lists what changed, how each change was checked, and what to test first.

## How things were verified (and what could not be)

| Level | What it means | Covers |
|---|---|---|
| **RUN** | Executed for real: Kotlin 2.2.10 + JUnit-style tests on the actual source files, or Node against a mock browser | `util/` (URL rules, blocklist, PIN hashing): 18 tests. Injected JavaScript: 38 tests. The Kotlin string constant is byte-identical to the tested script. |
| **TYPE** | Compiled with `kotlinc` 2.2.10 against the real `android.jar` (API 35) and kotlinx-coroutines, androidx replaced by thin stubs | Everything except Compose: data, network, VPN, privacy, WebView clients, `MainViewModel`. Zero errors. |
| **REVIEW** | Read and cross-checked, not compiled: there is no Compose/Gradle/Android SDK in the sandbox | Compose screens, `MainActivity`, `AppWebView`, manifest, Gradle, CI. Checked: zero syntax errors, every named argument of every composable call matches its definition, every `viewModel.*` member exists, every new import exists, all XML is well-formed, every `libs.*` alias exists in the catalog. |

**Not done:** no Gradle build, no APK, no run on a phone. Please run the checklist at the bottom first.

## Privacy and security

| # | Problem | Fix | Level |
|---|---|---|---|
| 1 | The VPN gate was a picture on top of a page that kept loading (first request left on your real IP) | WebView is stopped and paused and nothing loads until the VPN is up; all WebView traffic is black-holed meanwhile (one proxy owner, `NetworkPolicyManager`, so Tor and the kill-switch cannot overwrite each other); the overlay swallows touches; downloads are refused | TYPE + REVIEW |
| 2 | Data wipe ran only in `onDestroy` (not on kill / force-stop) and also on dark-mode / font-size changes (surprise logout) | Wipe only when really finishing, plus a wipe of WebView's data directory at the next cold start before any WebView exists; `configChanges` extended; cookie flush waits for removal to finish | TYPE + REVIEW |
| 3 | Any site got camera / mic after one OS grant, everything else auto-granted | Per-request dialog naming the site; only camera, mic and DRM can ever be granted; a second request is refused; cancelled requests handled | REVIEW |
| 4 | Downloads bypassed Tor, had no confirmation, silently failed on Android 9 and older, "started" toast even on failure | Confirmation dialog (APK warning), refused while Tor is on or VPN missing, runtime storage permission on API <= 28, toast only after a real enqueue, retry fetches fresh cookies / user agent | TYPE + REVIEW |
| 5 | Incognito only covered cookies, and the code comment claiming it was disclosed was false | Honest caption on the home screen, site data cleared when an incognito tab closes, history / cache cleared when its view is destroyed, no bookmarks from incognito or hidden tabs | TYPE + REVIEW |
| 6 | PIN was SHA-256 of 6 digits (brute-forced in < 1 s), no lockout, no way out of a forgotten PIN, hidden-tab count visible | PBKDF2-HMAC-SHA256 (30 000 iterations, RFC vectors tested), 5 tries then 30 s lock doubling to 15 min, "Forgot PIN" reset, row shown whenever a PIN exists and the count only after unlocking; old PINs still work and are upgraded on first unlock | RUN + TYPE + REVIEW |
| 7 | Backup rules did not cover WebView data; history went to cloud backup | `allowBackup=false`, extraction rules exclude everything for cloud backup and device transfer | REVIEW |
| 8 | FileProvider exposed all shared storage (and used a tag AndroidX does not know) | Only `Download/` | REVIEW |
| 9 | `intent:` / `tel:` / `mailto:` launched apps without a tap and `intent:` was not sanitised | Taps only; `intent:` parsed safely (component and selector cleared, BROWSABLE) | TYPE |
| 10 | `allowFileAccess` / `allowContentAccess` on, mixed content relaxed | Off / default (never allow) | TYPE |

## Browsing behaviour

| # | Problem | Fix | Level |
|---|---|---|---|
| 11 | After typing another site in the address bar, every link on it was blocked (stale captured domain) | The WebView clients keep their own "current site" and receive fresh callbacks on every recomposition | TYPE |
| 12 | Cross-site server redirects (`youtu.be`, `gmail.com`, OAuth, payments) were silently dropped; tapped links to other sites were dropped and counted as "Ad / Tracker" | Redirects are followed; a tapped cross-site link shows "Blocked a link to X - Open"; logged as "Blocked link", not counted as a tracker; scripted jumps still blocked | RUN + TYPE |
| 13 | HTTP 403 / 404 / 503 pages (e.g. Cloudflare challenges) were hidden behind the error screen; WebView's own error page cleared it again; failed loads counted as success | HTTP statuses left alone, error screen only for real failures, `ERR_ABORTED` ignored, progress 100 % of an error page ignored | TYPE |
| 14 | Single-page apps (YouTube) could jump back to an old URL; bare domains loaded twice | Loads are explicit requests, never "URL differs on recomposition"; `doUpdateVisitedHistory` keeps the address bar and tab URL right | TYPE + REVIEW |
| 15 | `loadUrl()` + `return true` in `shouldOverrideUrlLoading` (documented anti-pattern) and iframe links could navigate the whole page | Main frame only, allowed navigations just continue | TYPE |
| 16 | Every refresh reloaded twice | One mechanism | REVIEW |
| 17 | `RTCPeerConnection` threw on **read**, breaking feature detection | Reads `undefined`, writes ignored | RUN |
| 18 | `example.com/search?q=x`, `host:port`, IPs, `#fragment`, unicode domains, `HTTP://` became Google searches; empty input opened a search | Rewritten and tested; `http://` upgraded; blank ignored; `javascript:` / `file:` / `data:` never loaded | RUN |
| 19 | "Always open externally" did nothing for typed / bookmarked URLs | Applied when a URL is opened or typed | TYPE |
| 20 | Blocklist matched substrings (`bigsegment.com`, `/facebook.com/trending`, docs pages mentioning googleanalytics), blocked pages you typed, returned empty responses that broke page scripts | Whole-label host matching, path rules on the path only, main-frame requests never blocked, no-op stubs for blocked scripts, 1x1 GIF for pixels | RUN + TYPE |
| 21 | Same-site logic treated `alice.github.io` / `bob.github.io` and unrelated IPs as one site | Private hosting suffixes and IP literals handled | RUN |
| 22 | Canvas noise: only bit 0, re-randomised per call (averageable), mutated the visible canvas, created 2D contexts on WebGL canvases, missed `toBlob`; WebGL2 leaked the GPU; `deviceMemory` spoofed *upwards* | Deterministic additive noise per page load and origin on a copy, `toBlob` + `OffscreenCanvas` covered, WebGL2 masked, hardware values only ever capped | RUN |
| 23 | YouTube skipper left the video muted after an ad; ran in hidden tabs | Only undoes its own mute, pauses while hidden | RUN |
| 24 | Renderer crash (out of memory on low-RAM phones) killed the whole app | `onRenderProcessGone` replaces the dead WebView (with a loop guard) | TYPE + REVIEW |

## Performance and robustness

* Blocked-request counters were non-atomic (lost updates) and recomposed the whole screen per request: now atomic and published at most every 400 ms. `RUN/TYPE`
* Polling loops that never stopped (VPN every 3 s, downloads every 1.2 s): VPN and network state are callback-driven, download polling runs only while something downloads. `TYPE`
* Page timers and media kept running in the background: WebView is paused on `onStop`. `REVIEW`
* Splash 1.8 s -> 1.0 s. Two JPEGs (807 KB + 557 KB, up to ~17 MB decoded each after density scaling) -> `drawable-nodpi` WebP (117 KB + 10 KB, PSNR 37 / 40 dB). `RUN`
* Dead `LAYER_TYPE_HARDWARE`, `setRenderPriority`, WebSQL setting and an unreachable `onCreateWindow` removed.
* State collection uses `collectAsStateWithLifecycle`; installed-app checks moved out of composition.
* `AppDatabase` double-checked locking completed; `updateProgress` no longer overwrites a known file path with NULL; deleting an unfinished download now cancels it.

## UI fixes

Address bar opens the keyboard with the URL selected and a tap elsewhere cancels editing; home screen respects status bar, navigation bar and keyboard; "Downloading" tab no longer lists failed items; deleting a download asks (remove from list / delete file); overlays no longer let taps through; privacy dashboard wording is accurate; back on the home screen no longer touches a destroyed WebView.

## Dead code and leftovers removed

`AdultContentFilter.kt` (never called), `VpnWarningBanner.kt` (never used), `TorManager.kt` (replaced by `NetworkPolicyManager`), `BookmarkDao.isBookmarked/delete`, `DownloadDao.getById`, `MainViewModel.dismissSplash/updateCanGoBack/canGoBack` and two unused public flows, the "Adult content" dashboard branch, the splash `AnimatedVisibility` wrapper that never played, the `URLUtil` wrapper object, `ic_launcher_foreground.xml` (byte-identical copy of `ic_app_logo.xml`), `backup_rules.xml`, the unknown `<external-public-path>` tag, `READ_EXTERNAL_STORAGE`, redundant `hardwareAccelerated` attributes, commented-out dependency lines, template tests (`ExampleUnitTest`, `GreetingScreenshotTest` and its corrupted baseline PNG).

**Dependencies removed** (nothing imported them): Firebase AI, Firebase App Check, the google-services plugin, Retrofit, Moshi (+ codegen), OkHttp, logging-interceptor, Roborazzi, Compose tooling / test libraries, coroutines-test, Espresso, and every unused catalog entry. Names `CineHD` / `WebLite` / `CineRed` are now `Obsidian` / `ObsidianRed`.

## Other repairs

* **Launcher icons:** all 10 legacy `.webp` icons were corrupted (a text-encoding mangling; 6 would not even decode). Re-rendered from the vector design as valid WebP; themed icon now has a proper monochrome layer. `RUN`
* **Tests:** the Robolectric test expected "WebLite" and the instrumented test expected package `com.example` (both failed); fixed. New unit tests for URL rules, blocklist and PIN hashing.
* **CI:** debug signing key is cached so each APK installs over the previous one; unit tests run *after* the APK is uploaded so they can never block it.
* **README** rewritten to match the app (it still described a "no URL bar" design and an API-24 = Android 8 mistake).

## Behaviour changes you will notice

HTTPS-only (plain-HTTP-only sites will not load) - camera / mic / download prompts - "Blocked a link ... Open" snackbar - downloads off in Tor mode and without the VPN - incognito caption - 5 wrong PIN tries lock entry - no backups - one-second splash - closing an incognito tab signs you out of other tabs (disclosed).

## Deliberately not changed

Google stays the default search engine (`UrlUtils.SEARCH_URL`); Safe Browsing stays off (the misleading comment was corrected); the VPN heuristic remains Proton-oriented because Android cannot say which app owns a tunnel (see README); the YouTube ad skipper stays (it is fragile by nature).

## Please check on a phone first

1. `gradle assembleDebug` succeeds; `gradle testDebugUnitTest` is green.
2. Open `youtu.be/<id>` and `gmail.com`: they should load.
3. Open a site, type a different site in the address bar, tap a link on it: it should load.
4. Tap a link to another site from a Google result: snackbar with **Open**.
5. Disconnect the VPN: the overlay appears and no page loads; reconnect: the page reloads.
6. Open a site that asks for the camera: dialog first.
7. Download a file: confirmation first; with Tor on it must be refused.
8. Turn dark mode on and off while logged in somewhere: you must stay logged in.
9. Set a PIN, hide a tab, enter a wrong PIN five times: lock message.
