# NewTermux

[![Latest release](https://img.shields.io/github/v/release/Dantesousa/NewTermux?label=release)](https://github.com/Dantesousa/NewTermux/releases/latest)
[![Android 7+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#installation)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.md)

A personal fork of [Termux](https://termux.dev) with a modernized **Jetpack Compose** interface and an extended feature set — accent theming, terminal color themes, and built-in package, file and SSH managers. It installs under the `com.termux` package, so it's a **drop-in replacement** for stock Termux and stays fully compatible with the entire [Termux package ecosystem](https://github.com/termux/termux-packages).

> **Latest stable:** `v1.6.0` — [**Download the APK →**](https://github.com/Dantesousa/NewTermux/releases/latest)

---

## Contents

- [Features](#features)
- [Installation](#installation)
- [Building from source](#building-from-source)
- [Credits & license](#credits--license)

---

## Features

### Terminal & shell
- Full terminal emulator built on the upstream Termux engine — **100% package compatible**.
- **zsh + Oh My Zsh** configured automatically on first launch, with an optional toggle for autosuggestions and syntax highlighting (bundled, no download required).
- Always-visible **session tabs**: one-tap `×` to close, long-press to rename.

### Modern UI & theming
- Clean **Material 3 dark** interface, built in **Jetpack Compose** (v1.6.0).
- Consistent rounded, outlined pop-out menus and drawers throughout.
- **9 accent presets** plus a fully custom accent via an HSV color wheel or RGB sliders.
- **13 built-in terminal color themes** (Dracula, Nord, Gruvbox, Tokyo Night, Solarized, and more) plus a live **custom-theme editor** for the background, foreground, cursor and all 16 ANSI colors.

### Built-in managers
- **Package Manager** — browse installed packages, search the catalog, and install or remove from the toolbar.
- **File Manager** — browse, view, edit, create, delete and share files in your Termux home directory.
- **SSH Manager** — save connection profiles (host, port, user, key path and port-forwarding tunnels) and connect in a tap.

### Productivity
- **Text Expansion** — type a short trigger (e.g. `;ll`) then Space/Tab to expand it (e.g. `ls -la`).
- **Startup script** — sourced into every new session so aliases and env vars persist; editable from Settings.
- **Speech-to-Text** input, inline **command autocorrect**, and **URL detection** (long-press a link to open or copy).

### Customizable toolbar & drawer
- Toolbar buttons you can show/hide individually: **AC** (autocorrect), **Root** shell, **STT**, **Packages**, **Clear**, and **Settings**.
- Left-drawer utilities — **Export Screen**, **Make Script**, **Pkg Update** — plus up to **10 custom command buttons** (long-press to edit, `+`/`−` to resize).
- Collapsible **extra-keys** row, optionally moved into a swipe-in right-side drawer.

### Backup & restore
- One-tap **basic** (home) or **full** (home + usr) `.tar.gz` backup and restore through the system file picker.

---

## Installation

**Requires Android 7.0+.** NewTermux installs as `com.termux`, so **uninstall any existing Termux app and its plugins first** (mixing sources fails with a signature error).

Download the APK for your device from the [**latest release**](https://github.com/Dantesousa/NewTermux/releases/latest):

| APK | For |
|-----|-----|
| `arm64-v8a` | Most modern phones **(recommended)** |
| `armeabi-v7a` | Older 32-bit devices |
| `x86_64` / `x86` | Emulators and x86 devices |
| `universal` | Any device (larger download) |

### Try it before you switch
The `newtermux-test-coexist_*.apk` build installs as a **separate** app (`com.termux.demo`) alongside your existing Termux. The full UI — settings, themes, toolbar, managers — works live, running a harmless demo shell (no real commands execute). Uninstall it and install the main build when you're ready to switch.

> **Signing note:** GitHub APKs are signed with the shared Termux debug key, so NewTermux builds can update over one another but **not** over an F-Droid or Play Store Termux install. Switching sources requires a full uninstall first.

---

## Building from source

Requires **JDK 17** and the Android SDK.

```bash
git clone https://github.com/Dantesousa/NewTermux.git
cd NewTermux

# Main app (installs as com.termux)
./gradlew assembleDebug

# Coexist demo build (installs as com.termux.demo)
./gradlew assembleDemo
```

Built APKs are written to `app/build/outputs/apk/`.

---

## Credits & license

NewTermux is a fork of the [**Termux**](https://github.com/termux/termux-app) app by the Termux community. All credit for the terminal engine and Linux environment belongs upstream; for the packages installable inside the app, see [termux/termux-packages](https://github.com/termux/termux-packages).

Licensed under the **GNU GPLv3** (same as upstream Termux). See [LICENSE.md](LICENSE.md).
