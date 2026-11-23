# Changelog

All notable changes to **CoreGovernor** will be documented in this file.

## [2.7.0] - 2026-11-23

### Added
- **Smart app profiles (opt-in, default OFF).** Learns per app how much each CPU cluster and the GPU typically and at peak ask for (capacity-independent work units), the discharge power it costs, and how bursty it is. Apps are classified LIGHT / MODERATE / HEAVY / NOISY with a confidence that grows with use; new apps start from shared category/global priors. Light apps get a lower ceiling (learned peak plus headroom), heavy/noisy apps get a raised floor. Conservative / Balanced / Aggressive modes. Full ceiling is restored within one tick on saturation or touch, with a warm-up on every app switch. Thermal, user caps, pins, manual profiles and Game mode always take precedence. Per-app **Auto / Locked / Override (Light, Moderate, Heavy, Never touch)** plus per-app reset in the Apps tab, and an **App demand** card in the Energy tab. Stored in `app_profiles.json` (atomic, saved at most every ~5 min).
- **Adaptive refresh rate [Experimental] (opt-in, default OFF).** Drops the display to the lowest supported rate when idle and boosts on touch (touch detected from the touchscreen interrupt counter, with an input-event fallback). Auto-detects a working root method, verifies every switch, auto-disables after 3 failures, and always restores the original refresh-rate settings (toggle off, service stop, next start after a crash). Energy tab card with idle/active rate, idle delay, time share, switch count and a Test switch button.

### Changed
- Version 2.7.0 (versionCode 41).

## [2.6.2] - 2026-11-23

### Added
- **Landscape overlay size (`overlayCompactScalePctL`, `overlayExpandedScalePctL`).** Compact and expanded/detailed overlay sizes are now stored separately per orientation (existing setups keep their portrait size on landscape via a `-1` fallback sentinel). The Tuning sliders, `-`/`+` nudge buttons, and Fine toggle edit the active orientation and indicate `(Portrait)` or `(Landscape)`. `Reset position` leaves sizes intact.
- **Explicit charging vs discharging detection (`BatteryState`).** Derived once per tick from `BatteryManager` status/plugged state and current polarity (with `AUTO_ABS` sign learning, 3-second debounce hysteresis, and `FULL_PLUGGED` deadband `<= 0.15 W` / `<= 50 mA`). Overlay (all 3 styles), Monitor hero card, notification, and Power Throttle card clearly show charging (`⚡ +X.XX W` green), plugged/full (`PLUG 0.0` green), or discharge watts.
- **Charging-aware thermal throttle (opt-in).** Added a **Charging-aware thermal** toggle and **Charging start offset** (`0–5 °C`) in Tuning to lower the thermal throttle start temperature while plugged in or charging.
- **Battery capacity estimator & Energy tab Battery card.** Passively estimates real battery capacity (`mAh` / `Wh`), State of Health, remaining energy, ~2-minute EMA ETAs (`Time left` / `To full`), and session/lifetime `In`/`Out` counters across uninterrupted `>= 8%` and `>= 3 min` charge/discharge segments with weighted median + MAD outlier filtering and atomic `battery_model.json` persistence. Includes an optional design-capacity override (`0 = auto`), enable toggle, and reset dialog on the Energy tab.

### Changed
- **Discharge-only watt governors & learning.** Power throttling, watt averaging window, PowerSplit, PowerModel passive training (plus a 5-second settling gate after flipping back to discharging), Efficiency exploration probes, and Metrics average/max power, `Wh`, `FPS/W`, and `Work/W` summaries now use discharging power only.
- **Metrics chart & session badges.** Charging/plugged periods are still recorded on the power chart with dashed amber strokes and scrub labels, and mixed sessions are marked as `• incl. charging`. CSV rows remain backward compatible with earlier 7-column and 15-field files.
- Version 2.6.2 (versionCode 40).

## [2.6.1] - 2026-11-23

### Changed
- **Overlay size & position fine-tuning.** Replaced the 40%–400% (5% step) overlay scale sliders with continuous 1% sliders capped dynamically per orientation and overlay style (up to 60%×35% of screen for compact, 45%×35% for expanded/detailed, bounded within 50%–200%), and switched position sliders to 0.1% precision matching permille storage.
- **Micro-adjustment controls & Fine mode.** Added a non-persisted **Fine** toggle zooming size (±10%) and position (±10.0%) sliders around the current value, plus hold-to-repeat `-` / `+` nudge buttons under each slider.
- **Screen-bounds position clamping.** Overlay measuring and positioning now share full display metrics and re-clamp after scale, style, orientation, or detailed row-count changes so the overlay can reach all edges and never extends off-screen.
- Version 2.6.1 (versionCode 39).

## [2.6.0] - 2026-11-23

### Added
- **On-device learned power model (`PowerModel`).** Passively learns this device's power draw (mW) per CPU cluster and GPU across frequency steps and utilization bands using online regularized regression with outlier/transient filtering, lifelong exponential adaptation, cross-scenario overlays (`power_model.json`, max 24 scenarios with LRU eviction), and ~5-minute throttled atomic persistence + lifecycle flushes.
- **Opt-in Efficiency governor.** Disabled by default. When enabled and a bucket's confidence reaches the configurable minimum threshold, selects lower-energy frequencies within your allowed ranges and thermal/power caps that still meet demand (headroom and game FPS vs display refresh rate), with hysteresis, immediate regression backoff + confidence penalty, and bounded exploration.
- **Energy tab (`Energy`).** Shows sample count, overall confidence, scenarios learned, live estimated vs measured power, per-component learned power vs frequency curves (dimming low-confidence points), controls for learning, Efficiency governor, optimization mode (`Balanced`, `Aggressive saver`, `Performance-first`), minimum confidence, exploration rate, JSON clipboard export, and reset confirmation. UI updates pause when hidden.

### Changed
- Version 2.6.0 (versionCode 38).

## [2.3.2] - 2026-11-23

### Changed
- Detailed overlay now uses larger rings, larger text and balanced spacing for readability, with the column heights aligned and a screen-size safety clamp.
- Version 2.3.2 (versionCode 37).

## [2.3.1] - 2026-11-23

### Changed
- **Debounced config saves.** Changing sliders or toggles now updates in-memory state immediately and writes `core_governor_config.json` to disk after a ~400 ms pause instead of on every step, flushing pending changes when stopping the governor or leaving the app.
- **Cached usage-stats permission check.** `ForegroundDetector` now caches the `AppOpsManager` usage-access binder query for 5 seconds during background ticks instead of querying every cycle.
- **Notification reuse & deduplication.** The foreground service builds its notification action `PendingIntent`s once and skips `notify()` calls when the title, telemetry line, and actions have not changed.
- **Compiled regex reuse.** Moved per-tick and per-call `Regex` instances into compiled-once constants in `SysfsParsers`, `GovernorEngine`, `ForegroundDetector`, and `FpsDaemon`.
- **OPPs tab efficiency & Pause switch.** Scoped state reads on the GPU OPPs screen so only rows whose live/pinned/capped status changed recompose, throttled raw status card refreshes to ~1 s, and added a **Pause live updates** switch (unpersisted, off by default) that freezes the live highlight and raw card until turned off or refreshed.
- Version 2.3.1 (versionCode 36).

### Fixed
- **Atomic config writes.** Settings are now written to a temporary file first and renamed over `core_governor_config.json` to prevent partial or truncated writes.

### Removed
- Removed per-change `"Pin OPP idx=... OK"` success log entries from the GPU governor (failures are still logged).
- Removed unused tracking variables in the FPS sampler loop and an unused performance-pin policy load on governor startup.

## [2.3.0] - 2026-11-23

### Added
- **Separate overlay positions for portrait and landscape.** Each overlay style (compact, medium, detailed) now remembers one position in portrait and a different one in landscape. Dragging the overlay saves into the orientation you are in; rotating the phone moves it to that orientation's saved spot. The Tuning position sliders edit the current orientation and say which one in their title.
- Existing saved positions are copied to landscape the first time, so nothing jumps after updating.

### Changed
- The overlay can now reach the very top edge (over the status bar and notch area) and every other edge, in both orientations.
- Edge snapping is much smaller (about 1% instead of 2.5%) so the overlay stays exactly where you leave it.
- "Reset position" resets both orientations.
- Version 2.3.0 (versionCode 35).

## [2.2.0] - 2026-11-23

### Added
- **Update checker.** The app asks GitHub for the latest release of Tomushiki/MT6789-Core-Governor and compares it with the installed version. When a newer one exists, a banner appears with Download (opens the APK), Release notes and Later buttons.
- The version line under the title shows the status (up to date, checking, update available, failed) and can be tapped to check manually.
- An automatic check runs at app start, at most once every 12 hours. Nothing else is sent: one HTTPS request to api.github.com with no personal data.
- Adds the INTERNET permission. Version 2.2.0 (versionCode 34).

## [2.1.1] - 2026-11-23

### Added
- **FPS on the compact overlay.** The smallest overlay style now shows CPU, GPU, FPS, temperature and power, so you no longer have to switch to the medium or detailed style to see FPS. The FPS dot uses the same colours as the other styles (green near the screen refresh rate, orange below that). It shows `--` when there is no reading.

### Changed
- FPS is now measured whenever the overlay is on, in any style (before, only in medium and detailed). The extra cost is the same FPS helper that those styles already used. With the overlay off, FPS is still not measured.
- The compact overlay is a little wider because of the new FPS item. Its size sliders and screen-edge limits work as before.
- Note for the Metrics tab: FPS graphs need the overlay to be on, because that is when FPS is measured. Power, temperature and work-per-watt do not depend on it.
- Version 2.1.1 (versionCode 33).

## [2.1.0] - 2026-11-23

### Added
- **Performance Metrics tab ("Metrics").** A new tab with history graphs that share one time axis:
  - **Power, temperature and FPS** together on one plot (each line has its own scale, shown under the graph).
  - **FPS per watt**: measured FPS divided by measured power draw.
  - **Work per watt**: an estimate of work done (CPU cluster frequency x cores x load, plus GPU frequency x load, in busy GHz) divided by power. It works in any app, not only games, but it is an estimate and not a benchmark.
  - Drag on any graph to inspect a moment; the same moment is marked on every graph. Double-tap to clear.
  - Summary tiles: average FPS, 5% low FPS, average power, FPS/W, work/W, peak temperature, and energy used in Wh.
- **Live history** of the last 5, 15 or 30 minutes (one point per second, kept in memory only).
- **Saved game sessions.** While a game is in the foreground, the session is recorded and saved when it ends (at least 20 seconds). The newest 40 are kept. Open one to see its graphs, or delete it. A new session starts when you switch to a different game or after a pause longer than 15 seconds (for example screen-off saving).
- Ratios use a 5-second average and stay blank while power is below 0.3 W or there is no FPS reading.
- Unit tests for the efficiency maths, the session file format and the recorder.
- Version 2.1.0 (versionCode 32).

### Notes
- No extra hardware polling: the recorder reads the values the control loop already has, builds at most one point per second and writes a small file only when a game session ends.

## [2.0.1] - 2026-11-23

### Fixed
- **FPS stuck at about 60 in games running at 120 or 144 FPS (seen in Blood Strike).** At 30, 45 and 60 FPS the counter matched the game, but above 60 it stayed at 60. On this ROM the frame table holds only about 60 rows, so counting rows can never read higher. The counter now uses the spacing between frames whenever there are 20 or more frames over at least 0.3 s and the app is still drawing, which is right at any frame rate.
- The layer note in the log now also shows `span=` (how many milliseconds the frames cover), so a wrong reading can be checked.
- Version 2.0.1 (versionCode 31).

## [2.0.0] - 2026-11-23

### Added
- **Sensor pinning ("Nifty" tab).** Ported from SwiftSense's Boost Sensors: while the governor runs, CoreGovernor keeps a listener registered on the chosen sensors (default: gyroscope) at a fixed fast sampling period, so the sensor hub runs them fast for every app. Events are received on their own display-priority thread with no batching. Releasing drops the hub back to the normal rate. Rates: Max, 200, 100, 50 and about 16 Hz. It can pause while the screen is off (on by default). The measured rate per sensor is published once a second. Settings are stored separately, so existing configs are untouched. Off by default.
- New **Nifty** chip on the Tuning screen with the on/off switch, rate, sensor choice, pause-while-screen-off, and the measured Hz per sensor.
- Manifest now declares `android.permission.HIGH_SAMPLING_RATE_SENSORS` (needed for rates above 200 Hz).
- Version 2.0.0 (versionCode 30). Includes the 1.12.14 drift-log change, which was never released separately.

## [1.12.14] - 2026-11-23

### Changed
- **CPU drift warnings are now a once-a-minute summary.** The kernel or system changing the CPU limits now and then is normal. The app still fixes each one immediately (that behaviour is unchanged), but it no longer writes a warning every few seconds. It now logs one INFO line per policy per minute with the number of times it happened. This also stops the drift lines from pushing useful lines out of the 400-line log buffer.
- Version 1.12.14 (versionCode 28).

## [1.12.13] - 2026-11-23

### Fixed
- **No FPS in apps that draw into a SurfaceView (Geometry Dash and many other games).** Neither the task callback nor the per-layer frame table sees those apps on this Android version. The helper now also reads SurfaceFlinger's whole-screen composition counter and the overlay uses it for any app where the other two methods give nothing. It works for every app, not for one game.
- **A burst of frames at app launch was read as 143 FPS.** The frame-spacing rate is now used only when the frame table is nearly full (100+ frames per second).
- **Empty frame tables now and then.** All `dumpsys` calls from the helper run one at a time, and a layer is only dropped after 3 misses in a row, so the number no longer jumps to 0 for a moment.

### Changed
- `Compare:` log line now also shows `screen=` (composition rate). Source name `sf-composition` is used when it is the one shown.
- Version 1.12.13 (versionCode 27).

## [1.12.12] - 2026-11-23

### Fixed
- **FPS flickered between values, 0 and "waiting".** When a screen was static the helper re-scanned every layer on each pass (about 11 `dumpsys` calls), so its numbers went stale and the overlay dropped back to the slow backup methods. Layer scanning now runs on its own thread; the main loop only re-measures the one chosen layer, twice a second.
- **Old app's FPS leaked into the next app.** Every reading is now tagged with its task, and only readings for the app on screen are used.
- **Apps other than the one game were not followed.** The helper's own focused-app detection now drives FPS for every app (except the system UI), so it no longer lags behind app switches. Once the helper has data, the old backup methods are not used any more, which also removes the "no package match" fallback noise.

### Changed
- FPS is lightly smoothed and refreshed twice a second instead of once.
- Log: the helper's notes are shown at most every 10 s, and `Compare:` now names the task.
- Version 1.12.12 (versionCode 26).

## [1.12.11] - 2026-11-23

### Fixed
- **FPS in games still read about 62 when the game ran at about 140.** The task callback can under-report in games, and the SurfaceFlinger layer measurement only ran when the callback was silent. The layer measurement now runs for every app all the time, and the app shows the higher of the two live values. Frame spacing is used for the rate whenever 20 or more frames are in the window, so it is not limited to 128 frames per second.
- This works for every app you open, not only one game: the helper follows the foreground task and re-picks its busiest layer.

### Added
- The Log tab (`FPS`) now shows a `Compare:` line every 8 s with the callback value, the layer value, the value shown and the layer used.

### Changed
- Version 1.12.11 (versionCode 25).

## [1.12.10] - 2026-11-23

### Fixed
- **FPS looked capped on high-refresh games (62 shown while the game ran at about 140).** SurfaceFlinger only keeps the last 128 frames of a layer, so counting frames in one second can never exceed that. When the buffer is nearly full the rate is now taken from the spacing between frames, and the best of the presented and ready timestamps is used.
- **FPS for every app, not only one game.** The helper now ranks all layers of the foreground app (newest first, SurfaceView layers first, up to 8), follows the busiest one, re-scans every 5 s or when it goes quiet, and refreshes the layer list right away when an app is launched fresh instead of waiting.

### Added
- The Log tab (`FPS`) shows a `Latency detail` line every 8 s with the layer used and the row counts, so numbers can be checked against another FPS meter.

### Changed
- Version 1.12.10 (versionCode 24).

## [1.12.9] - 2026-11-23

### Fixed
- **Game FPS fallback used the wrong layer names.** On this Android version `dumpsys SurfaceFlinger --list` prints a long debug dump (`RequestedLayerState{...}`) instead of plain names, so the whole debug line was passed as the layer name and SurfaceFlinger returned no frames. The real layer names (`package/Activity#id`, `SurfaceView[...]#id`) are now pulled out of those lines, helper layers (input sink, splash, letterbox) are skipped, and the busiest of up to 5 layers is used.

### Changed
- Version 1.12.9 (versionCode 23).

## [1.12.8] - 2026-11-23

### Fixed
- **Game FPS fallback found no layers.** The root helper now runs `dumpsys` by its full path (`/system/bin/dumpsys`), because the root helper process may not have a PATH.
- If the helper still has nothing fresh, the older kernel/SurfaceFlinger methods now take over instead of showing 0.

### Added
- The Log tab (`FPS`) now includes a short sample of what `dumpsys SurfaceFlinger --list` / `--latency` returned when no layer data is found, so the cause can be seen.

### Changed
- Version 1.12.8 (versionCode 22).

## [1.12.7] - 2026-11-23

### Fixed
- **FPS stopped in games (worked only in the launcher).** The system task-FPS callback is often silent or reports 0 for games. When that happens the helper now measures FPS from SurfaceFlinger present timestamps of the app's layers (SurfaceView layers first) and reports it as source `sf-latency`.
- The Log tab (tag `FPS`) now shows the watched task, helper errors, and which source is active (`task-callback`, `sf-latency` or `idle`).

### Changed
- Version 1.12.7 (versionCode 21).

## [1.12.6] - 2026-11-23

### Added
- **FPS now uses the same method as the Metric monitor.** A small root helper (`FpsDaemon`, started with `su` + `app_process` from this APK) registers a task FPS callback with the window manager for the focused app, so the FPS value comes straight from the system instead of parsing SurfaceFlinger dumps.
- The helper follows the focused task, stops when the overlay is hidden or the engine stops, and exits on its own if the app is killed.

### Changed
- The older kernel node / SurfaceFlinger counter / timestats methods are kept as automatic backups if the helper can't start or exits. The Log tab (tag `FPS`) shows which source is used and why a fallback happened.
- Version 1.12.6 (versionCode 20).

## [1.12.5] - 2026-11-23

### Fixed
- **FPS counter stuck at 0 on Android 16.** SurfaceFlinger lists the same layer several times (one entry per refresh-rate bucket) and the counter only read the last one. The entries are now added together, so the real frame count of the app is used.

### Changed
- Removed the slower per-layer `--latency` method, which returns no data on newer Android. FPS now uses one light query per second.
- Version 1.12.5 (versionCode 19).

## [1.12.4] - 2026-11-23

### Fixed
- FPS per-app method now gets real layer names (some Android versions print a long debug dump instead of names for the layer list).
- FPS log lines no longer repeat every second, and the Log tab now shows the raw frame data when it can't be read.

### Changed
- Version 1.12.4 (versionCode 18).

## [1.12.3] - 2026-11-23

### Fixed
- **FPS counter now works like other system FPS monitors.** It first reads the display's own frame counter (the kernel fps node, or SurfaceFlinger's global frame counter), which shows the real frame rate of whatever is on screen. The per-app layer methods from 1.12.2 stay as backups.
- The Log tab shows which source the FPS comes from.

### Changed
- Version 1.12.3 (versionCode 17).

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
