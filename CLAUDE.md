# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

NEU-Ecode: a minimal Android app (Kotlin, single `:app` module, package `com.neboer.ecode`) that logs into Northeastern University's (东北大学) CAS single sign-on and displays the student's E码通/一码通 QR code. There is no official API — everything is screen-scraping of the university's web endpoints, so the login flow is inherently fragile.

## Build Commands

Gradle 8.5 wrapper, AGP 8.2.2, Kotlin 1.9.22, JDK 17 (compileSdk 34, minSdk 24). There are **no test sources or test dependencies** in the repo.

- `./gradlew assembleDebug` — build debug APK
- `./gradlew installDebug` — build and install to a connected device/emulator
- `./gradlew lint` — Android lint
- `./gradlew :app:compileDebugKotlin` — fast compile check

There are no tests to run. Verification is build + install on a device; the login flow can only be exercised with a real NEU student account.

## Versioning + Releases

- `versionName`/`versionCode` are derived from git tags in `app/build.gradle.kts` (top of file): tag pushes (`v1.2.3`) use `GITHUB_REF_NAME` directly, local builds use `git describe --tags --match v*`, falling back to `1.0.0` when no tag exists. `versionCode` = major\*10000 + minor\*100 + patch. The in-app version label (`SettingsActivity`) reads `PackageInfo.versionName`, so it follows automatically — never hardcode versions
- `.github/workflows/build-apk.yml` builds a signed release APK on `v*` tag pushes (attaches it to a GitHub Release) and on manual `workflow_dispatch`. Signing reads env vars `SIGNING_STORE_FILE`/`SIGNING_STORE_PASSWORD`/`SIGNING_KEY_ALIAS`/`SIGNING_KEY_PASSWORD`, wired from GitHub Secrets (`KEYSTORE_BASE64` + the three passwords); without them the workflow still builds but the APK is unsigned/not installable. The keystore is the user's own file at `C:\Users\zhx\AndroidKeys\e-one` (alias/password held by the user, never committed); its base64 copy lives in `keystore/` (gitignored) for pasting into Secrets — see `keystore/signing-info.txt`
- To release: commit, tag `vX.Y.Z`, push the tag — that's the whole loop

## Architecture

Plain Activities with hand-rolled OkHttp networking — no DI, ViewModel, or Repository layers; classes are constructed inline with the Activity as context. All classes live flat in `app/src/main/java/com/neboer/ecode/`.

### Activities

- `MainActivity` (launcher) — the home screen doubles as the entry point; it is always reachable, even logged out. On `onCreate`/`onResume` it checks `WebViewCookieJar.hasEcodeSession()` (ecode-domain `XSRF-TOKEN` in the WebView CookieManager): logged in → QR + balance data flow; logged out → empty state ("未登录" placeholder with a 去登录 button that opens `LoginActivity`; settings also hosts a 登录 entry). Session changes are picked up on `onResume` by re-checking the cookie, so login completion / account switch needs no result plumbing
- `LoginActivity` — wrapped WebView CAS login page (MaterialToolbar + progress bar + WebView, no longer fullscreen-bare nor the launcher). Success = `XSRF-TOKEN` landing in the CookieManager, detected by an 800 ms cookie poll + `doUpdateVisitedHistory`; then it `setResult(RESULT_OK)` and jumps to `MainActivity` with `CLEAR_TOP|SINGLE_TOP` (settings gets popped), and the main screen auto-refreshes on `onResume`
- `SettingsActivity` — account button renders 登录/切换账号 by current session state; balance data source dropdown (ECARD/PORTAL); back-press mode radio buttons; the 关于 section hosts the online-update state machine (see below)

### QR + balance refresh model

- The QR loop runs **only while foregrounded AND the QR card is visible**: `onPause` cancels the loop job, re-showing the card or returning to the foreground restarts it (fresh fetch immediately). Hiding the QR card (`qrVisible` persisted in `AppSettings`) cancels the loop; tapping it again restarts
- Refresh cadence is driven by the server: `EcodeApiClient` parses `data[0].attributes.qrInvalidTime` (unix seconds) and the loop schedules the next fetch at expiry +500 ms (clamped 1 s–120 s; falls back to 10 s when the field is missing)
- `EcodeApiClient.fetchQRCode()` returns a sealed `QrFetchResult`: `Success(qrCode, invalidAtMs)` / `AuthExpired` (CASTGC renewal failed → main screen clears the session, shows the empty state with "登录已过期") / `NetworkError` (IOException → exponential backoff retry 5 s→30 s; never clears the session). Catching IOException here is what keeps a dead network from crashing the coroutine
- Balance: `BalanceSource.fetchBalance()` returns a sealed `BalanceResult` (`Success`/`NetworkUnreachable`/`Failed`) — no auto-fallback between sources by design; the UI toasts "一卡通不可达(可能不在校园网)…" for ECARD unreachable, hinting at the manual source switch in settings. Balance loads once automatically per session (on login return) and afterwards only manually

### Online update (v<xx.xx.xx> releases)

User-initiated from SettingsActivity ("检查更新" button); a 9-state UI state machine (`UpdateUiState`) drives status text / progress bar / one action button (下载更新 / 暂停 / 继续下载 / 安装更新). Components (all flat in `com.neboer.ecode`):

- **Download sources** (`UpdateSource`, options in `UpdateChecker.UI_SOURCES`): 自动选择（测速, default) / GitHub 直连 (prefix "") / gh-proxy 源1 `https://gh-proxy.org/` / gh-proxy 源2 `https://v4.gh-proxy.org/` / AxisNow 源 `https://axisnow.gh-proxy.org/`. Verified 2026-09: all three proxies forward both release downloads (with Range/206) **and** `api.github.com` queries. The selected source id persists in `AppSettings.updateSourceId`; a dropdown (`ddlUpdateSource`) with a 测速 button beside it lives above the 检查更新 button — the button runs `UpdateChecker.measureLatencies` against the API URL and rewrites the dropdown items to "源 · <ms>" (timeout shown for failures). Mirror sites die over time — edit `UI_SOURCES` when one breaks
- `UpdateChecker` — GETs `https://api.github.com/repos/acdc-awa/NEU-Ecode/releases/latest`; when a manual source is selected that proxy is tried first for the API too, otherwise direct-first then `PROXY_PREFIXES` fallback. Picks the first `.apk` asset. Version comparison: `parseVersion` regex accepts `v1.2.3` / `1.2.3` / `1.2.3-3-gabc` (local git describe suffix is ignored), missing segments default to 0; `isNewer` compares via `toVersionCode` (major*1e6 + minor*1e3 + patch). Tags not matching the pattern → check fails loudly rather than falsely offering an update. GitHub API rate limit (60/hr per IP) makes the proxy fallbacks matter too
- `ApkUpdateDownloader` — blocking `download(release, sourceId, listener)` for the caller's IO coroutine. Manual source: that prefix first, remaining sources (direct last) as fallback. Auto: measures latency per source via shared `UpdateChecker.measureLatencies` (parallel GETs against the real asset URL, 5 s call timeout each), sorts by latency, downloads from the winner. Every attempt sends `Range: bytes=<part length>-` so any failure (dead proxy mid-stream, user pause, process death) resumes from the `.part` file in `getExternalFilesDir(null)/update/`; HTTP 200 (range ignored) restarts cleanly, 416 deletes the stale `.part`. Completion requires the byte count to reach the Content-Range total, then `.part` renames to `Ecode-<version>.apk`. Progress callbacks are throttled to ~150 ms and posted to the main thread. `cleanupExcept(version)` purges other versions' files after a check. Static helpers `partialFile`/`downloadedApk`/`updateDir` are shared with the Activity
- `ApkInstaller` — FileProvider URI + `ACTION_VIEW` install intent; on API 26+ without "install unknown apps" permission it stores the path in `pendingApkPath` and opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES`; `SettingsActivity.onResume` calls `resumePendingInstall` to continue after the grant
- Manifest additions: `REQUEST_INSTALL_PACKAGES` permission + `androidx.core.content.FileProvider` with `res/xml/file_paths.xml` (external-files-path `update/`)
- Download lives in the SettingsActivity `lifecycleScope` and is cancelled in `onDestroy` — leaving the page pauses (`.part` kept, resumable); `onDestroy` cancel also prevents a zombie download racing a new Activity's download on the same file
- JVM-verified via the demo harness: `cd demo && ../gradlew runUpdate` runs version-compare assertions plus real release query (through AxisNow, proving proxy API forwarding) / auto-speed-select download / manual-source resume against GitHub (Android stubs: `Handler`/`Looper`/`getExternalFilesDir` added in `demo/src/main/kotlin/android/`)

### Auth + QR flow (the core)

1. `LoginActivity` (WebView) — loads `https://pass.neu.edu.cn/tpass/login?service=<ecode sso/login>`; the real browser handles credentials/SMS verification/WebVPN rewrites. Success = ecode-domain `XSRF-TOKEN` present in the CookieManager (checked by an 800 ms poll and `doUpdateVisitedHistory`). Deliberately not `CASTGC`: it is planted before SMS verification even completes. The session's single source of truth is the WebView `CookieManager`; OkHttp reads/writes it through `WebViewCookieJar` (flushed on save)
2. `CasSessionRenewer.renewEcodeSession()` — silent renewal: GETs the same CAS login URL; a valid `CASTGC` gets 302'd straight through with a ticket to ecode's sso/login, which re-plants `XSRF-TOKEN`. Success = landed host is ecode + `hasEcodeSession()`; false means CASTGC expired; IOException propagates (callers classify it as `NetworkError`)
3. `EcodeApiClient` — GET `https://ecode.neu.edu.cn/ecode/api/qr-code` with the current XSRF token in the `X-XSRF-TOKEN` header (re-read from the CookieManager per request; ecode rotates it per response). QR string at `data[0].attributes.qrCode`, expiry at `data[0].attributes.qrInvalidTime` (unix seconds). First failure → renew → retry once; returns sealed `QrFetchResult` (see "QR + balance refresh model")
4. `AppSettings` — plain SharedPreferences ("ecode_settings"): `backPressMode`, `qrVisible`, `updateSourceId`, `balanceSource`
5. `EcardClient` — fetches the campus-card balance from `http://ecard.neu.edu.cn/selfsearch/User/Home.aspx` (plain HTTP; regex on `主钱包余额`; connect timeout 5 s so off-campus unreachability fails fast). Session establishment mirrors `.har/ecardlogin.har` and succeeds in a **single round** in the browser: manually follow the redirect chain from `http://ecard.neu.edu.cn/selflogin/login.aspx` (302 → CAS, which issues a ticket immediately when the TGC `CASTGC` is valid), the ticket landing page returns a **200 form** with server-generated hidden fields (`username`/`timestamp`/`auid`) — parse it with Jsoup and POST it to `/selfsearch/SSOLogin.aspx` → 302 `Index.aspx`. The SSOLogin POST response sets **two same-name `.ASPXAUTSSM` Set-Cookie headers** (an expired empty delete + the 128-char final value); the browser keeps only the last one, and the WebView CookieManager does the same (later-wins per cookie) — sending both poisons the session and makes `Index.aspx` bounce to `/selfsearch/login.aspx`, in which case the walk retries once as a fallback (fresh ticket, second POST). A walk landing on the CAS login form means the TGC is dead and cannot be renewed without re-login. Balance failures never clear credentials or disturb the QR flow
6. `BalanceSource` / `PortalClient` — balance source abstraction over sealed `BalanceResult`. `EcardClient` is the default display source (authoritative values, campus-only). `PortalClient` is the off-campus-capable backup: portal "personal data" JSON API — `GET personal/frontend/data/items?type=personal_data` → find the entry with `key == "card.balance"` (id is a server-issued long hash, fetched dynamically; `net.balance` 网费 is available the same way) → `GET personal/frontend/data/detail?id=<id>` → `d.data.value`. Auth rides the shared `CASTGC` through the portal's own CAS entry `cas_login/1` (its redirect target consumes the ticket; pointing `service=` at the bare root does NOT work), then a `/portal/` warm-up GET mints `SESS_ID`. Portal values currently read higher than ecard's; the source switch is manual in settings

## Conventions and gotchas

- The codebase is in Chinese: UI strings, comments, and log messages. New strings go in `res/values/strings.xml` (with `values-night` colors already handled by the DayNight theme). Keep new logs in Chinese to match
- Never log passwords — usernames may appear in debug logs, passwords never should
- `docs/superpowers/specs/2026-04-27-ecode-cleanup-design.md` is an agreed cleanup design: remove all `Log.d`, keep `Log.e` and minimal `Log.i`. The Material 3 UI part is applied (M3 DayNight theme, 东大蓝 `#003366` palette in `colors.xml` + `values-night/`), but the logging cleanup is **not** applied — `Log.d` remains pervasive and is the debugging breadcrumb trail for the auth flow
- When login breaks, suspect a server-side change at `pass.neu.edu.cn`/`ecode.neu.edu.cn` first — the code is a screen-scraper, not an API client
- Release build has minify disabled; the manifest uses cleartext traffic for the campus endpoints
