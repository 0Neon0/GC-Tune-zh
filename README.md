# GC-Tune

[![GitHub Release](https://img.shields.io/github/v/release/Bebrazui/GC-Tune?style=flat-square&color=2ea44f)](https://github.com/Bebrazui/GC-Tune/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg?style=flat-square)](https://android.com)
[![OriginOS](https://img.shields.io/badge/OriginOS-Supported-informational.svg?style=flat-square)](https://www.vivo.com/origin-os)

An advanced system tuning utility and privilege channel implementation for OriginOS and vivo devices. GC-Tune leverages the Game Cube (Ultra Game Mode) execution environment to execute commands with elevated system privileges (`uid 1000` / `system`), install custom `.itz` themes, and apply custom icon pack coloring directly to the OriginOS launcher without root.

[Download Latest Release](https://github.com/Bebrazui/GC-Tune/releases/latest) &bull; [Quick Setup](#quick-setup) &bull; [Features](#features) &bull; [Building](#building)

---

## Overview

GC-Tune is built around an IPC channel established through the privileged Game Cube subsystem. This grants `system` (uid 1000) execution capabilities to tune device settings, patch system themes, and tint icons with Material You and custom palettes.

### Key Capabilities

- **Privileged System Shell (`uid 1000`)**  
  Run administrative shell commands directly under system credentials. Uses an idle-safe wake mechanism driven by `dumpsys SurfaceFlinger` and `logcat` signals without background polling or battery drain.

- **OriginOS Theme Patcher (`.itz`)**  
  Imports custom and third-party `.itz` theme archives, cleans up proprietary store metadata, and normalizes catalog identifiers (`id`, `id3`, `uid`) so custom themes can be previewed and applied via iTheme without license paywalls.

- **System & Adaptive Icon Recoloring**  
  Applies uniform icon tinting and styling directly into the OriginOS launcher:
  - Material 3 and custom HEX color tinting
  - Adjustable desaturation, brightness, and tint strength
  - Custom background tiles and squircle corner radius adjustment
  - Compatibility with standard third-party icon packs

- **Zero Root Requirement**  
  Requires no root access or bootloader unlocking. Operates purely through system framework integration and basic standard permissions (`WRITE_SECURE_SETTINGS` via ADB).

---

## Quick Setup

### 1. Installation

Download the signed APK from the [Releases](https://github.com/Bebrazui/GC-Tune/releases/latest) page and install it on your device:

```bash
adb install -r gctune-signed.apk
```

### 2. Grant Permissions

Grant `WRITE_SECURE_SETTINGS` via ADB once after installation:

```bash
adb shell pm grant com.poc.gctune android.permission.WRITE_SECURE_SETTINGS
```

### 3. Add to Game Cube

1. Open **Settings** &rarr; **Ultra Game Mode** (Game Cube).
2. Go to **Supported Games** (Add Game).
3. Under **Other Apps**, select **GC Tune** and add it to the game list.
4. Launch GC Tune. The onboarding screen will confirm the active channel connection.

---

## Architecture & IPC Channel

```
 +-------------------------------------------------------+
 |                       GC Tune                         |
 |  com.poc.gctune (Activities / Foreground Service)      |
 +-------------------------------------------------------+
                             |
                   SurfaceControl / IPC
                             v
 +-------------------------------------------------------+
 |                 Game Cube Subsystem                   |
 |              SurfaceFlinger / uid 1000                |
 +-------------------------------------------------------+
                             |
         +-------------------+-------------------+
         |                   |                   |
         v                   v                   v
   System Shell        Theme Patcher        Icon Engine
 (uid 1000 execution)   (.itz rewrite)    (Launcher tint)
```

The app initiates a lightweight foreground service (`ChannelService`) holding the layer and ACK receiver while commands run. The watcher daemon runs strictly event-driven via `logcat`, ensuring zero CPU usage when idle.

---

## Building from Source

### Prerequisites

- JDK 11 or higher
- Android SDK (Platform 35 / Build-Tools 34.0.0+)
- PowerShell (Windows / Linux / macOS)

### Build Steps

Clone the repository and run the build script:

```bash
git clone https://github.com/Bebrazui/GC-Tune.git
cd GC-Tune
.\build.ps1
```

The script compiles the Java sources using `javac`, bundles the classes into a DEX file via `d8`, packages the APK via `aapt`, and signs it using `apksigner`.

---

## Disclaimer

This project is intended for research, device customization, and educational purposes. Ensure you comply with your device manufacturer policies before modifying system parameters.
