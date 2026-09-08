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

## Architecture

Plain Activities with hand-rolled OkHttp networking — no DI, ViewModel, or Repository layers; classes are constructed inline with the Activity as context. All classes live flat in `app/src/main/java/com/neboer/ecode/`.

### Activities

- `LoginActivity` (launcher) — if `CredentialManager.hasCredential()`, jumps straight to `MainActivity`; otherwise shows the CAS login form
- `MainActivity` — QR display: a `lifecycleScope` coroutine loop polls `EcodeApiClient.fetchQRCode()` every 10 s, renders the returned string with ZXing `QRCodeWriter`, and forces screen brightness to 1.0 while the QR is visible. The same loop refreshes the card balance via `EcardClient` at most every 60 s (shown in `tvBalance`). Tapping the QR card toggles visibility. A null fetch result (re-auth failed) wipes credentials/cookies and relaunches `LoginActivity`
- `SettingsActivity` — back-press mode (single/double) radio buttons; "switch account" clears cookies + credentials and relaunches `LoginActivity`

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
