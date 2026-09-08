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

- `LoginActivity` (launcher) — if `CredentialManager.hasCredential()`, jumps straight to `MainActivity`; otherwise shows the CAS login form
- `MainActivity` — QR display: a `lifecycleScope` coroutine loop polls `EcodeApiClient.fetchQRCode()` every 10 s, renders the returned string with ZXing `QRCodeWriter`, and forces screen brightness to 1.0 while the QR is visible. The same loop refreshes the card balance via `EcardClient` at most every 60 s (shown in `tvBalance`). Tapping the QR card toggles visibility. A null fetch result (re-auth failed) wipes credentials/cookies and relaunches `LoginActivity`
- `SettingsActivity` — back-press mode (single/double) radio buttons; "switch account" clears cookies + credentials and relaunches `LoginActivity`; the 关于 section hosts the online-update state machine (see below)

### Online update (v<xx.xx.xx> releases)

User-initiated from SettingsActivity ("检查更新" button); a 9-state UI state machine (`UpdateUiState`) drives status text / progress bar / one action button (下载更新 / 暂停 / 继续下载 / 安装更新). Components (all flat in `com.neboer.ecode`):

- `UpdateChecker` — GETs `https://api.github.com/repos/acdc-awa/NEU-Ecode/releases/latest` direct-first, then through each prefix in `UpdateChecker.PROXY_PREFIXES` (currently `gh.llkk.cc`, `ghfast.top`, `gh-proxy.com`; these mirror sites die over time — edit the list when one breaks, direct always stays as last resort for downloads). Picks the first `.apk` asset. Version comparison: `parseVersion` regex accepts `v1.2.3` / `1.2.3` / `1.2.3-3-gabc` (local git describe suffix is ignored), missing segments default to 0; `isNewer` compares via `toVersionCode` (major*1e6 + minor*1e3 + patch). Tags not matching the pattern → check fails loudly rather than falsely offering an update. GitHub API rate limit (60/hr per IP) makes the proxy fallbacks matter too
- `ApkUpdateDownloader` — blocking `download()` for the caller's IO coroutine; iterates `PROXY_PREFIXES + direct`, sends `Range: bytes=<part length>-` every attempt so any failure (dead proxy mid-stream, user pause, process death) resumes from the `.part` file in `getExternalFilesDir(null)/update/`; HTTP 200 response (range ignored) restarts cleanly, 416 deletes the stale `.part`. Completion requires the byte count to reach the Content-Range total, then `.part` renames to `Ecode-<version>.apk`. Progress callbacks are throttled to ~150 ms and posted to the main thread. `cleanupExcept(version)` purges other versions' files after a check. Static helpers `partialFile`/`downloadedApk`/`updateDir` are shared with the Activity
- `ApkInstaller` — FileProvider URI + `ACTION_VIEW` install intent; on API 26+ without "install unknown apps" permission it stores the path in `pendingApkPath` and opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES`; `SettingsActivity.onResume` calls `resumePendingInstall` to continue after the grant
- Manifest additions: `REQUEST_INSTALL_PACKAGES` permission + `androidx.core.content.FileProvider` with `res/xml/file_paths.xml` (external-files-path `update/`)
- Download lives in the SettingsActivity `lifecycleScope` and is cancelled in `onDestroy` — leaving the page pauses (`.part` kept, resumable); `onDestroy` cancel also prevents a zombie download racing a new Activity's download on the same file
- JVM-verified via the demo harness: `cd demo && ../gradlew runUpdate` runs version-compare assertions plus real release query/download/resume against GitHub (Android stubs: `Handler`/`Looper`/`getExternalFilesDir` added in `demo/src/main/kotlin/android/`)

### Auth + QR flow (the core)

1. `CasAuthenticator.login()` — scrapes `https://pass.neu.edu.cn/tpass/login` with Jsoup: parses hidden `lt`/`execution` form fields, RSA-encrypts `username + password` with a hardcoded public key (companion object), POSTs the form, then walks the redirect chain looking for a `ticket` query param. Some accounts hit a TPass guide flow (引导流程) with meta-refresh pages, JS-loaded forms, and TGT→ST exchange — `completeGuideFlow()`, `tryLoadDynamicContent()`, `exchangeTgtForSt()` handle those. The ticket is redeemed at `https://ecode.neu.edu.cn/ecode/api/sso/login?ticket=...` to capture the `XSRF-TOKEN` Set-Cookie, stored via `CredentialManager`
2. `EcodeApiClient.fetchQRCode()` — GET `https://ecode.neu.edu.cn/ecode/api/qr-code` with the XSRF token in the `X-XSRF-TOKEN` header; the QR string is at `data[0].attributes.qrCode` in the JSON body. On failure it clears cookies, re-authenticates with stored credentials, and retries once; returning null means give up (caller wipes credentials)
3. `PersistentCookieJar` — OkHttp `CookieJar` persisted to SharedPreferences ("ecode_cookies") so the session survives app restarts. Deliberately normalizes every cookie's path to `"/"` so cookies share across subpaths — preserve that behavior
4. `CredentialManager` — `EncryptedSharedPreferences` ("ecode_cred") holding username, password, XSRF token
5. `AppSettings` — plain SharedPreferences ("ecode_settings"): `backPressMode`, `qrVisible`
6. `EcardClient` — fetches the campus-card balance from `http://ecard.neu.edu.cn/selfsearch/User/Home.aspx` (plain HTTP; regex on `主钱包余额`). Session establishment mirrors `.har/ecardlogin.har` and succeeds in a **single round** in the browser: manually follow the redirect chain from `http://ecard.neu.edu.cn/selflogin/login.aspx` (302 → CAS, which issues a ticket immediately when the TGC `CASTGC` is valid), the ticket landing page returns a **200 form** with server-generated hidden fields (`username`/`timestamp`/`auid`) — parse it with Jsoup and POST it to `/selfsearch/SSOLogin.aspx` → 302 `Index.aspx`. The SSOLogin POST response sets **two same-name `.ASPXAUTSSM` Set-Cookie headers** (an expired empty delete + the 128-char final value); the browser keeps only the last one, and `PersistentCookieJar` must do the same (later-wins per cookie) — sending both poisons the session and makes `Index.aspx` bounce to `/selfsearch/login.aspx`, in which case the walk retries once as a fallback (fresh ticket, second POST). If a walk lands on the CAS login form instead, run `CasAuthenticator.login()` (ecode service, refreshes the TGC) and retry. Balance failures never clear credentials or disturb the QR flow
7. `BalanceSource` / `PortalClient` — balance source abstraction. `EcardClient` implements it and is the display source (authoritative values). `PortalClient` is a prepared but dormant backup: portal "personal data" JSON API — `GET personal/frontend/data/items?type=personal_data` → find the entry with `key == "card.balance"` (id is a server-issued long hash, fetched dynamically; `net.balance` 网费 is available the same way) → `GET personal/frontend/data/detail?id=<id>` → `d.data.value`. Auth is just the `SESS_ID` cookie, minted when CAS redeems a ticket for `service=https://personal.neu.edu.cn/` (rides the shared `CASTGC`; on failure re-runs `CasAuthenticator.login()` with stored credentials). Dormant because portal and ecard values are currently out of sync (portal reads higher); when NEU syncs them, switch `MainActivity`'s balance call to `PortalClient` (one line — both implement `BalanceSource`)

## Conventions and gotchas

- The codebase is in Chinese: UI strings, comments, and log messages. New strings go in `res/values/strings.xml` (with `values-night` colors already handled by the DayNight theme). Keep new logs in Chinese to match
- Never log passwords — usernames may appear in debug logs, passwords never should
- `docs/superpowers/specs/2026-04-27-ecode-cleanup-design.md` is an agreed cleanup design: remove all `Log.d`, keep `Log.e` and minimal `Log.i`. The Material 3 UI part is applied (M3 DayNight theme, 东大蓝 `#003366` palette in `colors.xml` + `values-night/`), but the logging cleanup is **not** applied — `Log.d` remains pervasive and is the debugging breadcrumb trail for the auth flow
- When login breaks, suspect a server-side change at `pass.neu.edu.cn`/`ecode.neu.edu.cn` first — the code is a screen-scraper, not an API client
- Release build has minify disabled; the manifest uses cleartext traffic for the campus endpoints
