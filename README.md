# CoreGovernor

**New here? Read the [User Guide](USER_GUIDE.md).**

**Custom userspace CPU and GPU throttling governor for rooted MediaTek Helio G99 (`MT6789`) devices running Android 12+.**

---

## What It Is

CoreGovernor locks all discovered CPU cluster governors to `powersave` so the Linux kernel never scales CPU frequencies autonomously, and takes over as the single authoritative frequency controller for both CPU and GPU. Every `100–250 ms`, a foreground service reads live CPU/GPU load, thermal sensors, and battery wattage through a single persistent `su` root shell, then writes pinned frequencies directly to sysfs and `/proc/gpufreqv2/fix_target_opp_index`.

No external rule engine, profile daemon, or competing writer runs inside the app—only one controller loop owns the hardware nodes.

---

## Features

- **Automatic Hardware Discovery**:
  - Scans all `/sys/devices/system/cpu/cpufreq/policy*` clusters (all 3 clusters on `MT6789`), reading `related_cpus` and `scaling_available_frequencies`.
  - Reads the live GPU OPP table from `/proc/gpufreqv2/gpu_working_opp_table` (falling back to `gpu_signed_opp_table` and `stack_working_opp_table`), preserving real OPP indexes, frequencies (kHz), and voltages while sorting lowest-to-highest regardless of kernel order.
  - Discovers `/sys/class/thermal/thermal_zone*` sensors, `/sys/class/power_supply/battery/temp`, and battery `current_now` / `voltage_now` nodes.
- **Load-Based CPU & GPU Scaling**:
  - Computes per-cluster CPU utilization from `/proc/stat` and GPU load from GED nodes (`/sys/kernel/ged/hal/gpu_utilization` or `/sys/module/ged/parameters/gpu_loading`, automatically selecting the first changing node).
  - Grades available frequencies (or sorted OPP positions) from `0` to `100` inside your configured range with configurable **headroom**, fast **ramp-up speed**, and smooth **ramp-down speed**.
  - Writes `scaling_min_freq = scaling_max_freq` only when the target changes.
- **Per-Mode Frequency Ranges & App Detection**:
  - Separate **Normal** and **Game** mode profiles with independent CPU/GPU min-max ranges, headroom, and ramp speeds.
  - Configurable **Launch Burst** in Normal mode triggered by foreground app switches or sudden load spikes.
  - Automatic mode switching via `UsageStatsManager` (with root `dumpsys window` fallback), plus a manual quick toggle (`Auto` / `Normal` / `Game`) and selectable installed-apps checklist.
- **Dual Stacking Throttling Systems**:
  - **Temperature Throttling**: Smooth continuous curve (`Linear`, `Gentle`, or `Aggressive`) between start and full-strength temperatures, independent CPU/GPU max reduction strength, reaction speed, and recovery hysteresis.
  - **Power-Draw Throttling**: Rolling-average wattage (`current × voltage`) with unit-scale and sign-polarity calibration, sustain timer, small step-down cuts, and slow recovery below budget.
  - Both caps stack (`min` cap wins) and never drop below your **Safety Floor %**.
- **Performance Metrics**:
  - A Metrics tab with live and saved-session graphs for power, temperature, FPS, FPS per watt and an estimated work-per-watt, with a shared drag-to-inspect cursor. Game sessions are saved automatically.
- **Presets, JSON Backup & Diagnostics**:
  - Built-in and user-named presets with full **JSON export and import** via Storage Access Framework.
  - Dedicated **OPPs** screen showing every parsed GPU OPP entry and a copyable raw `/proc/gpufreqv2/gpufreq_status` debug readout.
  - Timestamped, filterable, copyable **Log** screen and optional boot auto-start.
  - Automatic restore of original CPU governors, original `min`/`max` limits, and GPU `-1` unpin on stop, exit, or crash.

---

## Requirements

- **Rooted Android device** with working `su` binary (Magisk, KernelSU, or APatch)
- **MediaTek Helio G99 (`MT6789`)** SoC (or compatible MediaTek `gpufreqv2` + `cpufreq` kernel exposing `/proc/gpufreqv2/*` and `/sys/devices/system/cpu/cpufreq/policy*`)
- **Android 12 (API 31) or later**

---

## Warning

> **USE AT YOUR OWN RISK — NO WARRANTY.**
>
> CoreGovernor directly modifies kernel CPU frequency limits (`scaling_governor`, `scaling_min_freq`, `scaling_max_freq`) and pins GPU OPP states (`/proc/gpufreqv2/fix_target_opp_index`) as root. Incorrect ranges, aggressive overclocked OPPs, or disabled thermal/power caps can cause instability, thermal shutdowns, battery drain, or crashes. By using this software, you accept full responsibility for your device.

---

## Installation and Build Instructions

### Option 1: Install Prebuilt Release APK
1. Download the signed release APK from the [GitHub Releases](../../releases) page.
2. Install it on your device (`adb install CoreGovernor-v1.0.0-release.apk` or via your file manager).

### Option 2: Build from Source
**Prerequisites:** JDK 17+, Android SDK (Compile SDK 36 / Platform 36), and Gradle 9.3.1 (or Android Studio).

```bash
# 1. Clone the repository
git clone https://github.com/<your-username>/CoreGovernor.git
cd CoreGovernor

# 2. Run unit tests
gradle :app:testDebugUnitTest

# 3. Build a debug APK
gradle :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# 4. Build a signed release APK (with keystore environment variables)
export KEYSTORE_PATH=/path/to/your-release-key.jks
export STORE_PASSWORD=your_store_password
export KEY_PASSWORD=your_key_password
gradle :app:assembleRelease
```

*(If your local keystore alias is not `upload`, either use a keystore with alias `upload` or sign the unsigned APK with `apksigner`.)*

### How to Grant Root & Permissions
1. Open **CoreGovernor** on your phone.
2. When your root manager (**Magisk**, **KernelSU**, or **APatch**) prompts for superuser (`su`) access, tap **Grant** permanently.
   - If you missed the prompt, open your root manager app, enable superuser access for `CoreGovernor`, then tap the **Refresh** button on the **Monitor** screen.
3. On Android 13+, allow **Notifications** so the foreground governor service remains active without being killed by the system.
4. *(Optional)* In the **Apps** tab, tap **Grant** to enable **Usage Access** for instant foreground app switching (otherwise CoreGovernor uses its root `dumpsys window` fallback).

---

## How the GPU Control Works

Unlike generic devfreq tuners, MediaTek Helio G99 (`MT6789`) devices—especially those with custom vendor boot images and extended 40+ entry OPP tables—do not reliably follow `devfreq` or `GED` upper-bound writes.

CoreGovernor handles GPU scaling purely through real **OPP indexes**:

1. **Table Discovery**: Reads `/proc/gpufreqv2/gpu_working_opp_table` (falling back to `gpu_signed_opp_table`, then `stack_working_opp_table`). Each row's real `oppIndex`, frequency in kHz, and voltage are parsed.
2. **Order Normalization**: Without assuming index `0` is fastest or slowest, CoreGovernor detects the table's direction and builds one sorted array ordered from **lowest to highest frequency**, keeping each item's real kernel `oppIndex`.
3. **Position-Space Scaling**: All allowed range filtering, thermal/power cap stepping, headroom, and ramp-up/ramp-down smoothing run on array positions `0 .. N-1`.
4. **OPP Pinning**: Only at write time does CoreGovernor map the chosen position back to its real `oppIndex` and write it to `/proc/gpufreqv2/fix_target_opp_index` (only when the target changes).
5. **Restore**: Writing `-1` to `/proc/gpufreqv2/fix_target_opp_index` hands GPU DVFS control back to the kernel when stopping, exiting, or recovering from a crash.

---

## Known Limitations & Tested Devices

- **Tested Target**: MediaTek Helio G99 (`MT6789`) devices running Android 12–15 with `gpufreqv2` kernel interfaces and custom vendor OPP tables (~42 OPPs).
- **Root Dependency**: Non-rooted devices cannot read or write `/sys/devices/system/cpu/cpufreq/*` or `/proc/gpufreqv2/*`.
- **Kernel Variations**: `gpufreq_status`, `gpu_utilization`, and `battery/current_now` units/signs vary slightly across vendor kernels; use the **Calibration** toggles under **Tuning → Power** and check **OPPs → Raw `gpufreq_status`** if readings look inverted or scaled by `1000×`.
- **Single-Controller Assumption**: Other kernel managers (e.g., Franco Kernel Manager, Scene, Konabess, or Magisk thermal modules) writing to `cpufreq` or `gpufreqv2` simultaneously will conflict with CoreGovernor—disable other tuners while CoreGovernor is active.

---

## Contributing

Contributions, bug reports, and device-compatibility fixes are welcome!

Because CoreGovernor is licensed under **GNU GPL v3.0**, **any forks, modified versions, or redistributed APKs must also release their complete corresponding source code under the GNU GPL v3.0 license**.

1. Fork the repository and create a feature branch.
2. Ensure all new source files carry the GPL-3.0 copyright/license header.
3. Run `gradle :app:testDebugUnitTest` before opening a pull request.

---

## License

Licensed under the GNU GPL v3.0 — see the [LICENSE](LICENSE) file.
