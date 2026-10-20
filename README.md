# NewTermux

[![Latest release](https://img.shields.io/github/v/release/Dantesousa/NewTermux?label=release)](https://github.com/Dantesousa/NewTermux/releases/latest)
[![Android 7+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#installation)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.md)

A personal fork of [Termux](https://termux.dev) with a modernized **Jetpack Compose** interface and an extended feature set — accent theming, terminal color themes, built-in package, file and SSH managers, plus an **embedded Pi agent panel (Nebula Pi)**. It installs under the `com.termux` package, so it's a **drop-in replacement** for stock Termux and stays fully compatible with the entire [Termux package ecosystem](https://github.com/termux/termux-packages).

> **Latest stable:** `v1.6.11` — [**Download the APK →**](https://github.com/Dantesousa/NewTermux/releases/latest)

---

## Contents

- [Features](#features)
- [Nebula Pi — embedded agent panel](#nebula-pi--embedded-agent-panel)
- [Using Nebula Pi](#using-nebula-pi)
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
- Clean **Material 3 dark** interface, built in **Jetpack Compose**.
- Consistent rounded, outlined pop-out menus and drawers throughout.
- **9 accent presets** plus a fully custom accent via an HSV color wheel or RGB sliders.
- **13 built-in terminal color themes** (Dracula, Nord, Gruvbox, Tokyo Night, Solarized, and more) plus a live **custom-theme editor** for the background, foreground, cursor and all 16 ANSI colors.
- Custom **Nebula launcher icon** (Pi + cursor over nebula, violet/magenta; demo build in petrol/cyan as `Nebula Demo`).

### Built-in managers
- **Package Manager** — browse installed packages, search the catalog, and install or remove from the toolbar.
- **File Manager** — browse, view, edit, create, delete and share files in your Termux home directory.
- **SSH Manager** — save connection profiles (host, port, user, key path and port-forwarding tunnels) and connect in a tap.

### Productivity
- **Text Expansion** — type a short trigger (e.g. `;ll`) then Space/Tab to expand it (e.g. `ls -la`).
- **Startup script** — sourced into every new session so aliases and env vars persist; editable from Settings.
- **Speech-to-Text** input, inline **command autocorrect**, and **URL detection** (long-press a link to open or copy).

### Customizable toolbar & drawer
- Toolbar buttons you can show/hide individually: **AC** (autocorrect), **Root** shell, **STT**, **Packages**, **Clear**, **Nebula Pi**, and **Settings**.
- Left-drawer utilities — **Export Screen**, **Make Script**, **Pkg Update** — plus up to **10 custom command buttons** (long-press to edit, `+`/`−` to resize).
- Collapsible **extra-keys** row, optionally moved into a swipe-in right-side drawer.

### Backup & restore
- One-tap **basic** (home) or **full** (home + usr) `.tar.gz` backup and restore through the system file picker.

---

## Nebula Pi — embedded agent panel

> Since `v1.6.7`. Full Pi chat inside the app — no terminal needed.

- **Conversation** — token-by-token streaming, expandable thinking blocks, tool calls with text + image preview, bash output. Type mid-run and it steers (`steer`, shows `Na fila` when queued).
- **Slash commands** (list button) — built-ins via RPC: `/model`, `/thinking`, `/new`, `/compact`, `/abort`, `/clear_queue`, `/session`, `/copy`, `/export`, `/name`, `/tree`, `/bash` — plus everything Pi discovers (`get_commands`): extensions, prompt templates and skills.
- **`/model` / `/thinking`** open pickers with the real catalog from the daemon.
- **Extension UI** — `select`, `confirm`, `input`, `editor` become native dialogs (answer returns as `extension_ui_response`); `notify` / `setStatus` / `setWidget` show inline.
- **Sessions (🕘 button)** — list with title, message count and date, title/path search, one-tap open (`switch_session` + `get_messages`), delete with confirm, `New session` on top.
- **Memory (button)** — reads/writes `~/.pi/agent/pi-hermes-memory/{MEMORY,USER,failures}.md` via daemon (`nebula_memory` / `nebula_memory_save`), with search and `save new`.
- **Attachments (📎 button)** — image → base64 embedded in the prompt (Pi really sees it) + thumbnail in chat; document → copied to `/storage/emulated/0/Download/nebula-pi-attach/` and read by Pi via `read` tool, with first-lines preview chip.
- **Tool results** — text preview (800 chars / 8 lines) + up to 3 image thumbs per tool.
- **Theme** — accent picker in the panel (12 presets incl. Nebula), shared with Termux via `NewTermuxAccent`.
- **Background notification** — `turn finished` notification when the app is in background.
- **Integrations** — `pi-panel` terminal command, Android Share (text/image from any app), `Nebula Pi` launcher icon.

Architecture: `PiActivity (Compose) → PiRpcClient —TCP 127.0.0.1:37877→ pi-rpc-daemon.mjs → pi --mode rpc` running in the real Termux env (same bash, tools, files, login). TCP is used because `minSdkVersion=21` has no Unix Domain Socket. Versioned daemon source lives in [`pi-daemon/`](pi-daemon/).

---

## Using Nebula Pi

### 1. Requirements
```bash
pkg install nodejs
```
- First launch asks once for **Display over other apps** permission — without it `pi-panel` can't open activities from background on Android 10+.

### 2. Open the panel
- Tap the **Pi button** in the terminal toolbar, or
- Tap the **Nebula Pi** launcher icon, or
- Share text/image from any app → **Nebula Pi**, or
- From terminal:
```bash
pi-panel                  # just opens
pi-panel "explain this log: ..."
```

### 3. Daemon (usually auto-starts)
```bash
pi-rpc                    # start daemon on 127.0.0.1:37877
pi-rpc-test "your question"
pi-rpc-test --image /path/to.png "what's in here?"
pi-rpc-test --state       # connection check only
node ~/work/NewTermux/pi-daemon/pi-probe.mjs  # healthcheck (get_state)
```
Runtime files: `~/.cache/nebula-pi/` (`port`, `daemon.pid`, `daemon.log`).

Env overrides: `NEBULA_PI_BIN` (pi binary), `NEBULA_PI_CWD`, `NEBULA_PI_ARGS`, `NEBULA_PI_PORT`, `NEBULA_PI_RUNTIME_DIR`, `NEBULA_PI_AUTORESTART=1`.

### 4. Day-to-day
- **Chat**: type + Send. While busy, new message steers instead of erroring.
- **Commands**: list button → pick `/model`, `/thinking`, `/compact`, etc.
- **Sessions**: 🕘 → search → tap to open, trash to delete, `New session` to reset.
- **Memory**: memory button → search → `save new`.
- **Attach**: 📎 → image (goes inline) or document (goes to `Download/nebula-pi-attach/` + readable path).

Full protocol notes, F0 test results and Android pitfalls: [`pi-daemon/README.md`](pi-daemon/README.md).

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
The `newtermux-test-coexist_*.apk` build installs as a **separate** app (`com.termux.demo`, shown as **Nebula Demo**) alongside your existing Termux. The full UI — settings, themes, toolbar, managers **and the Nebula Pi panel** (talks TCP to the real Termux daemon) — works live, running a harmless demo shell (no real commands execute). Uninstall it and install the main build when you're ready to switch.

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

NewTermux is a fork of the [**Termux**](https://termux.dev) app by the Termux community. All credit for the terminal engine and Linux environment belongs upstream; for the packages installable inside the app, see [termux/termux-packages](https://github.com/termux/termux-packages).

Licensed under the **GNU GPLv3** (same as upstream Termux). See [LICENSE.md](LICENSE.md).
