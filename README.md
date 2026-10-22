# GC-Tune（简体中文版）

[![GitHub Release](https://img.shields.io/github/v/release/0Neon0/GC-Tune-zh?style=flat-square&color=2ea44f)](https://github.com/0Neon0/GC-Tune-zh/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg?style=flat-square)](https://android.com)
[![OriginOS](https://img.shields.io/badge/OriginOS-Supported-informational.svg?style=flat-square)](https://www.vivo.com/origin-os)
[![Chinese](https://img.shields.io/badge/语言-简体中文-red.svg?style=flat-square)](#)

面向 OriginOS / vivo 设备的进阶系统调参工具与提权通道实现。GC-Tune 借助「游戏魔盒（电竞模式）」的执行环境，以系统级权限（`uid 1000` / `system`）执行命令、安装自定义 `.itz` 主题，并在**无需 root**的前提下直接为 OriginOS 桌面上色、替换自定义图标包。

> 本仓库是上游 [Bebrazui/GC-Tune](https://github.com/Bebrazui/GC-Tune) 的简体中文汉化镜像：界面文案、通知与本文档均已中文化，功能代码与协议保持不变。

[下载最新版本](https://github.com/0Neon0/GC-Tune-zh/releases/latest) &bull; [快速上手](#快速上手) &bull; [功能特性](#功能特性) &bull; [从源码构建](#从源码构建)

---

## 概述

GC-Tune 的核心是一条经由游戏魔盒特权子系统建立的 IPC 通道。借助它，应用可以拿到 `system`（uid 1000）的执行能力，用来调整系统设置、改写系统主题，以及用 Material You 或自定义配色为桌面图标着色。

### 核心能力

- **特权系统 Shell（`uid 1000`）**
  直接以系统身份执行管理级 shell 命令。唤醒机制由 `dumpsys SurfaceFlinger` 与 `logcat` 信号驱动，空闲时自动休眠，不做后台轮询，不额外耗电。

- **OriginOS 主题破解（`.itz`）**
  导入自定义及第三方 `.itz` 主题包，清理厂商商店的私有元数据，并规范化目录标识（`id`、`id3`、`uid`），让自定义主题能在 iTheme 中正常预览与套用，绕开授权收费墙。

- **系统图标 / 自适应图标重新着色**
  直接把统一的图标着色与样式写进 OriginOS 桌面：
  - Material 3 与自定义 HEX 配色着色
  - 去饱和、亮度、着色强度均可调
  - 自定义背景底板、方圆形圆角半径可调
  - 兼容标准第三方图标包

- **完全免 root**
  无需 root 权限，也无需解锁 bootloader。仅依赖系统框架集成与基础常规权限（通过 ADB 授予 `WRITE_SECURE_SETTINGS`）。

---

## 快速上手

### 1. 安装

从 [Releases](https://github.com/0Neon0/GC-Tune-zh/releases/latest) 页面下载已签名的 APK 并安装到设备：

```bash
adb install -r gctune-signed.apk
```

### 2. 授予权限

安装完成后，通过 ADB 授予一次 `WRITE_SECURE_SETTINGS` 权限：

```bash
adb shell pm grant com.poc.gctune android.permission.WRITE_SECURE_SETTINGS
```

### 3. 加入游戏魔盒

1. 打开 **设置** &rarr; **电竞模式**（游戏魔盒）。
2. 进入 **支持的游戏**（添加游戏）。
3. 在 **其他应用** 中找到 **GC Tune**，加入游戏列表。
4. 启动 GC Tune。引导页会显示通道连接是否已激活。

---

## 架构与 IPC 通道

```
 +-------------------------------------------------------+
 |                       GC Tune                      |
 |  com.poc.gctune（Activity / 前台服务）   |
 +-------------------------------------------------------+
                              |
                    SurfaceControl / IPC
                              v
 +-------------------------------------------------------+
 |                     游戏魔盒子系统               |
 |              SurfaceFlinger / uid 1000        |
 +-------------------------------------------------------+
                              |
          +-------------------+-------------------+
          |                   |                   |
          v                   v                   v
      系统 Shell           主题破解器            图标引擎
   （uid 1000 执行）      （.itz 改写）       （桌面上色）
```

命令执行期间，应用会启动一个轻量前台服务（`ChannelService`）持有图层与 ACK 接收器。后台守护进程严格由 `logcat` 事件驱动，空闲时 CPU 占用为零。

---

## 从源码构建

### 环境要求

- JDK 11 或更高版本
- Android SDK（Platform 35 / Build-Tools 34.0.0+）
- PowerShell（Windows / Linux / macOS）

### 构建步骤

克隆仓库并运行构建脚本：

```bash
git clone https://github.com/0Neon0/GC-Tune-zh.git
cd GC-Tune-zh
.\build.ps1
```

该脚本会用 `javac` 编译 Java 源码，经 `d8` 打包为 DEX，再用 `aapt` 打成 APK，最后用 `apksigner` 签名。

> 也可直接用 Gradle 构建：`.\gradlew assembleDebug`。

---

## 免责声明

本项目仅供研究、设备定制与学习交流使用。修改系统参数前，请自行确认符合设备厂商的相关政策与条款。

---

## 汉化说明

- 汉化范围：应用界面文案、通知文案、应用名称、`README.md`、PowerShell 脚本提示信息。
- 未改动：包名、类名、日志标签、ADB/shell 命令、IPC 协议令牌（`OK`/`FAIL`/`PENDING`/`BUSY`/`APPLIED`/`ERROR` 等）——改动这些会破坏与外部脚本的兼容性。
- 上游版权归原作者所有，本项目遵循原仓库的 MIT 许可。
