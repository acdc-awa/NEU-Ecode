# Ecode Android App — Code Cleanup & UI Redesign

## Overview

Clean up debug logging, simplify code, and restyle the NEU ePass (一码通) Android app with Material Design 3 + Northeastern University blue theme.

## Scope

Three independent work items, done in order:

1. Remove debug logging
2. Simplify code post-log-removal
3. Material 3 UI with 东大蓝 theme

## 1. Logging Policy

- Remove ALL `Log.d` calls
- Keep `Log.e` for exceptions
- Keep minimal `Log.i` — only at meaningful lifecycle boundaries (login success/failure, credential cleared)
- Tag constants remain but simplified (`TAG` kept per class)

## 2. Code Simplification

### EcodeApiClient
- `tryFetch()`: remove the 401 Set-Cookie XSRF-TOKEN parsing (CookieJar handles this). Return null on 401, let `fetchQRCode()` handle re-auth
- Simplify `fetchQRCode()`: inline the retry, less nesting

### CasAuthenticator
- `submitCredentials()`: remove response HTML title parsing (unused check)
- `fetchLoginPage()`: inline variable, less verbose
- `redeemTicket()`: simplify found flag logic

### MainActivity
- Remove all Log.d from UI flow
- Simplify variable names where obvious

### PersistentCookieJar
- Remove logging, keep core save/load/clear

### CredentialManager
- No changes (already clean)

## 3. Material 3 UI

### Dependencies
- Add `com.google.android.material:material:1.11.0` to version catalog and build file

### Theme
- Change parent to `Theme.Material3.DayNight.NoActionBar`
- Define custom color attributes in `colors.xml`:

| Name | Hex | Role |
|------|-----|------|
| md_theme_primary | #003366 | 东大蓝 |
| md_theme_primary_container | #D6E4F0 | 浅蓝背景 |
| md_theme_on_primary | #FFFFFF | 按钮文字 |
| md_theme_background | #F5F7FA | 页面底色 |
| md_theme_surface | #FFFFFF | 卡片表面 |
| md_theme_on_surface | #1C1B1F | 主要文字 |
| md_theme_on_surface_variant | #49454F | 次要文字 |

### Layout (activity_main.xml)

From top to bottom:
1. `tvUsername` — centered, primary color, medium weight, 18sp, marginTop 64dp
2. `tvStatus` — centered, on_surface_variant, 14sp
3. `ivQRCode` — 280dp, inside a Material CardView (rounded 12dp, elevation 2dp), marginTop 32dp
4. `btnSwitchAccount` — Material TextButton, primary color, below QR code

### Login Dialog
- Replace AlertDialog with MaterialAlertDialogBuilder
- Input fields styled with Material 3 text input style

### Dark Mode
- DayNight theme provides automatic dark mode
- Dark variants defined in `values-night/colors.xml`

## Files Modified

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add material 1.11.0 |
| `app/build.gradle.kts` | Add material dependency |
| `app/src/main/res/values/colors.xml` | Full M3 color palette |
| `app/src/main/res/values-night/colors.xml` | Dark mode colors (new) |
| `app/src/main/res/values/themes.xml` | Switch to M3 DayNight |
| `app/src/main/res/layout/activity_main.xml` | CardView + M3 styling |
| `app/src/main/java/.../MainActivity.kt` | Remove logs, simplify |
| `app/src/main/java/.../EcodeApiClient.kt` | Remove logs, simplify |
| `app/src/main/java/.../CasAuthenticator.kt` | Remove logs, simplify |
| `app/src/main/java/.../PersistentCookieJar.kt` | Remove logs |

## Out of Scope

- No behavior changes (login flow, QR refresh, cookie handling stay identical)
- No architecture changes (single activity, same class structure)
- No new features
