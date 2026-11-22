# Changelog

All notable changes to **CoreGovernor** will be documented in this file.

## [1.0.0] - 2026-11-22

### Added
- **Single Persistent Root Controller**: Persistent `su` shell session (`RootShell`) with batched sysfs/proc reads and change-only frequency writes across all CPU clusters and GPU.
- **CPU Policy Control**: Automatic discovery of all `/sys/devices/system/cpu/cpufreq/policy*` clusters, locking governors to `powersave` and writing pinned `scaling_min_freq = scaling_max_freq` values.
- **OPP-Index GPU Control**: Parses `/proc/gpufreqv2/gpu_working_opp_table` (with fallback to `gpu_signed_opp_table` and `stack_working_opp_table`), normalizes lowest-to-highest ordering regardless of kernel index direction, and pins frequencies exclusively via `/proc/gpufreqv2/fix_target_opp_index` (restoring `-1` on stop/exit/crash).
- **GED GPU Load Probing**: Probes `/sys/kernel/ged/hal/gpu_utilization` and `/sys/module/ged/parameters/gpu_loading`, locking onto the first node returning changing utilization values.
- **Dual Modes & App Switching**: Independent Normal and Game mode profiles with per-cluster/GPU ranges, headroom, asymmetric ramp-up/ramp-down speeds, Normal-mode launch burst, UsageStats + root `dumpsys window` foreground detection, and an installed-apps Game Mode selector.
- **Stacked Temperature & Power Throttling**: Continuous thermal curves (`Linear`, `Gentle`, `Aggressive`) with sensor selection, reaction speed, and hysteresis, stacked with gentle window-averaged wattage throttling and unit/polarity calibration.
- **Presets, JSON Backup & Telemetry UI**: Five-screen Jetpack Compose interface (**Monitor**, **Tuning**, **OPPs**, **Apps**, **Log**), live multi-series history graph, copyable raw `/proc/gpufreqv2/gpufreq_status` debug view, boot auto-start receiver, and foreground service notification controls.
