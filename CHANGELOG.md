# Changelog

All notable changes to **CoreGovernor** will be documented in this file.

## [1.12.2] - 2026-11-23

### Fixed
- **FPS counter stuck at 0.** FPS is now measured from the real frame present timestamps of the app's display layer (the same method other FPS monitors use). The old frame-counter method is kept as a backup.
- The Log tab (tag `FPS`) shows which method and layers are being used.

### Changed
- Version 1.12.2 (versionCode 16).

## [1.12.1] - 2026-11-23

### Fixed
- **FPS counter not updating.** If no frame layer matches the foreground app's package name, the counter now falls back to the busiest non-system layer instead of staying stuck.
- The FPS sampler now writes what it is tracking (or why it can't) to the Log tab, so problems can be diagnosed.

### Changed
- Version 1.12.1 (versionCode 15).

## [1.12.0] - 2026-11-23

### Added
- **Three overlay styles.** Tap the overlay to cycle compact, medium, detailed. Each style remembers its own position.
- **Medium overlay** with CPU, GPU, TEMP, FPS and PWR rings.
- **Detailed overlay** with per-cluster CPU rows, DDR (memory) frequency, FPS and PWR.
- **Memory (DDR) frequency monitor** (auto-discovered devfreq node).
- **FPS counter** for the foreground app.
- **Keep RAM at max frequency** (Tuning > Memory (RAM)). Off by default, "Only in Game mode" on by default. Released when the screen turns off, the option is switched off or the service stops.

### Changed
- Version 1.12 (versionCode 14).

## [1.11.0] - 2026-11-23

### Added
- **Smart split by component** (Tuning > Power): when power is over the limit, the heavier consumer (CPU or GPU) is throttled more than the lighter one, using each component's load and frequency as an estimate. A light per-cluster split inside the CPU cut shifts a small share to the busiest cluster. On by default, can be turned off.

### Changed
- The overlay CPU value and the Monitor chart now use the true average utilization of all cores instead of the busiest core.
- Version 1.11 (versionCode 13).

## [1.10.0] - 2026-11-23

### Added
- **Background opacity** slider (0–100%) that only fades the dark pill of the overlay. The old "Opacity" slider is now "Overall opacity".

### Changed
- Cheaper foreground app detection.
- Cheaper root file reads (shell built-ins with automatic fallback to cat).
- Version 1.10 (versionCode 12).

## [1.9.1] - 2026-11-23

### Added
- New Tuning chips: **Screen-off** and **Overlay**. Those cards now have their own filter, and Control shows only the control loop.

## [1.9.0] - 2026-11-23

### Added
- **Overlay position memory**: the floating overlay remembers a separate position for the compact and expanded styles, stored as permille (0–1000) of the free screen space so it always stays on screen.
- **Edge snapping**, horizontal and vertical **position sliders**, and a **Reset position** button in Tuning.

### Changed
- Generic MediaTek wording across the app.
- Version 1.9 (versionCode 10).

## [1.8.0] - 2026-11-22

### Added
- **Floating overlay** showing CPU, GPU, temperature and power. Compact strip by default, tap to expand; click-through mode with an "Unlock overlay" notification button; opacity slider; optional Game-mode-only display. Off by default, and it hides when the screen is off.
- Separate size sliders for compact and expanded styles (40% to 400%, clamped to the screen width).
- **Performance governor** as the default for all CPU policies (max-only pinning), with automatic fallback to powersave.

## [1.6.0] - 2026-11-22

### Added
- **Screen-off Power Saving (off by default)**: Configurable sleep-saving mode in **Tuning** (`screenOffSavingEnabled`, `screenOffDelaySec` 0–60 s, `screenOffExemptMusic`, `screenOffExemptCharging`). Uses dynamic `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` broadcasts and `PowerManager.isInteractive` in `GovernorService` with zero screen-state polling. After the configured delay when not exempt, pauses the control loop and pins all CPU clusters and the GPU to their minimum frequencies/OPP index, re-checking exemptions every 10 s. On screen-on, resumes immediately and triggers the Normal-mode launch burst; disabling or stopping restores normal control or original governors cleanly.
- **Remembered Performance-Mode Clusters**: Persists per-policy `maxOnly`/`performance` pinning detection across app restarts so affected clusters switch immediately on startup without waiting for 3 min-reverts. Clears the saved flag and re-detects if pinned `min=max` stops sticking.

## [1.5.0] - 2026-11-22

### Fixed
- **Min=Max Pinning Under Performance Governor (`GovernorEngine`)**: Clusters switched to `maxOnly` / `performance` governor mode now write both `scaling_max_freq` and `scaling_min_freq` (using the same raise/lower ordering) and probe `scaling_min_freq` back after ~150 ms via `minWorksInPerformance`. When `scaling_min_freq` sticks under `performance`, CoreGovernor continues pinning `min=max` so big clusters hold full target clocks; if still reverted, it falls back to `scaling_max_freq`-only writes for that policy without leaving `performance` mode on `CpuDrift`.

## [1.4.0] - 2026-11-22

### Fixed
- **Max-Only Performance Governor Fallback (`GovernorEngine`)**: Added per-policy detection of consecutive `scaling_min_freq` reverts when `scaling_max_freq` sticks. After 3 consecutive min-reverts on a cluster, CoreGovernor automatically switches that policy to the `performance` governor (when present in `scaling_available_governors`) and pins frequency via `scaling_max_freq` only, preventing big clusters from getting stuck at minimum clock under kernels that ignore `scaling_min_freq`. Falls back to `powersave` min/max pinning if `performance` is unavailable or fails, and restores each policy's original governor on stop/exit.

## [1.1.0] - 2026-11-22

### Fixed
- **Single-Core Peak Scaling (`computeClusterLoadPct`)**: Added `useBusiestCore` mode (enabled by default via `cpuLoadUsesBusiestCore`) so each CPU cluster is graded by its busiest core rather than the average across all cores in the cluster. On 2-core big clusters (e.g., policy 6 / CPUs 6–7), a single pegged thread now registers as ~100% cluster load instead of ~50%, allowing `GovernorMath.selectFrequency()` to reach top clock frequencies cleanly.

### Added
- **Tuning Toggle (`CPU Load = Busiest Core`)**: Added a switch in the **Tuning** screen (persisted in JSON config and backups) to toggle between busiest-core grading (default) and the legacy cluster-averaged calculation.

## [1.0.0] - 2026-11-22

### Added
- **Single Persistent Root Controller**: Persistent `su` shell session (`RootShell`) with batched sysfs/proc reads and change-only frequency writes across all CPU clusters and GPU.
- **CPU Policy Control**: Automatic discovery of all `/sys/devices/system/cpu/cpufreq/policy*` clusters, locking governors to `powersave` and writing pinned `scaling_min_freq = scaling_max_freq` values.
- **OPP-Index GPU Control**: Parses `/proc/gpufreqv2/gpu_working_opp_table` (with fallback to `gpu_signed_opp_table` and `stack_working_opp_table`), normalizes lowest-to-highest ordering regardless of kernel index direction, and pins frequencies exclusively via `/proc/gpufreqv2/fix_target_opp_index` (restoring `-1` on stop/exit/crash).
- **GED GPU Load Probing**: Probes `/sys/kernel/ged/hal/gpu_utilization` and `/sys/module/ged/parameters/gpu_loading`, locking onto the first node returning changing utilization values.
- **Dual Modes & App Switching**: Independent Normal and Game mode profiles with per-cluster/GPU ranges, headroom, asymmetric ramp-up/ramp-down speeds, Normal-mode launch burst, UsageStats + root `dumpsys window` foreground detection, and an installed-apps Game Mode selector.
- **Stacked Temperature & Power Throttling**: Continuous thermal curves (`Linear`, `Gentle`, `Aggressive`) with sensor selection, reaction speed, and hysteresis, stacked with gentle window-averaged wattage throttling and unit/polarity calibration.
- **Presets, JSON Backup & Telemetry UI**: Five-screen Jetpack Compose interface (**Monitor**, **Tuning**, **OPPs**, **Apps**, **Log**), live multi-series history graph, copyable raw `/proc/gpufreqv2/gpufreq_status` debug view, boot auto-start receiver, and foreground service notification controls.
