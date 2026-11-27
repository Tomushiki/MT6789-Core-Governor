# Changelog

All notable changes to **CoreGovernor** will be documented in this file.

## [Unreleased]

No changes yet.

## [4.3.0] - 2026-11-27

### Added
- **Fast root writer for the smooth limiter's fast switcher.** A small helper starts once as root (`app_process` running `FastWriterMain` from the app's own APK) and keeps `scaling_max_freq`, `scaling_min_freq` and the GPU node open. Each frequency write is now one direct write over a pipe instead of a whole shell command, so a write should drop from tens of milliseconds to about one. No kernel change.
- The two CPU writes of a flip (max then min, or min then max) go out in one pipe round trip, in the same order as before.
- `FastWriteCoreTest` unit tests (keeps files open, no stale bytes, recovers when a node is recreated, protocol).

### Changed
- Only the fast switcher's shell (`RootShell(fast)`) uses the helper. The control loop and every other shell are unchanged.
- **Safe fallback.** The helper starts in the background; until it reports ready, and whenever it is unavailable, a call fails, or it hangs for 2 s, writes go through the shell exactly as in 4.2.0 (including the chmod repairs). A write the helper cannot do is retried through the shell. After 3 helper failures it stays off for the session. The log shows `Fast writer ready in N ms`, or the reason it was dropped.
- The helper is stopped when the governor stops, and exits by itself if the app dies. Nodes that cannot be rewound (some proc files) are reopened for every write.
- The `WriteLat` "fast switcher" figures now measure the helper path, so they show the gain directly.
- Version 4.3.0 (versionCode 76).

## [4.2.0] - 2026-11-27

### Changed
- **The Energy screen's battery section is rebuilt to be readable at a glance.** The old wall of numbers is replaced by plain-language cards:
  - **Hero card:** big battery ring, "On battery / Charging / Full", time left or time to full in words, and two facts with a one-word verdict (power: light/normal/heavy/very heavy, or charging: slow/normal/fast; temperature: cool/normal/warm/hot).
  - **History graphs** with a 6 hours / 24 hours / 7 days picker: battery level over time (green bands while plugged in, touch and hold to read any moment) and a power graph (bars up = charging, bars down = using).
  - **Summary and comparison:** battery lost per hour (overall, screen on, screen off), time on battery, average charging speed, warmest temperature, and a sentence comparing with the period before ("Better: you used 12% less battery per hour").
  - **Battery health card:** a plain word (Excellent / Good / Showing its age / Worn), the percentage, current vs new capacity, and a clear note when the estimate is still learning.
  - **Charge and use history:** each charge or use session with duration, % change, average power and screen-on share.
- The previous detailed battery card (capacity, calibration, charge-time-by-power, segments, design override, reset) is kept under **Show technical details**.

### Added
- **Battery history recorder** (`BatteryHistory.kt`): one point per minute (or when the level/state changes) with level, state, power, temperature, voltage and screen state, kept for 7 days in `battery_history.csv` and saved every 5 minutes and on stop. "Clear battery history" is in the technical details.
- `BatteryHistoryTest` unit tests (recording rules, pruning, windows, thinning, sessions, drain summary, file format).
- Version 4.2.0 (versionCode 75). New texts are English only for now.

## [4.1.1] - 2026-11-27

### Changed
- **The smooth limiter's fast switcher has its own root shell** (`RootShell(fast)` in the log). In the 4.1.0 log every frequency write took 155-223 ms because it waited behind the control loop's reads on the shared shell, which capped switching at about 6 per second and made slices under ~300 ms pointless. Only the fast switcher's writes use the new shell; the control loop's reads and writes stay serialized on the main shell as before.
- **Safe handover.** The control loop still never writes a cluster (or the GPU) while the fast switcher owns it, and it now also waits for the fast switcher's in-flight write to finish before writing that cluster again (at most one tick), so the two shells can never write the same file at the same time.
- **Write time is measured per shell.** The `WriteLat` line now shows `fast switcher`, `control CPU` and `control GPU/other` separately, and slow-write warnings from the fast shell say `(fast shell)`. The slice learner's write-time floor (3 x p90) now uses the fast switcher's own writes.
- Version 4.1.1 (versionCode 74).

## [4.1.0] - 2026-11-27

### Added
- **"Smooth limit: fix kernel overrides"** (off by default). While the fast switcher owns a CPU cluster, the control loop did not re-assert limits, so a kernel or vendor override stayed until the next frequency flip. With the option on, an override of the fast switcher's last write makes it write the value again. Safety rules: at most one fix per cluster every 400 ms (no write war on the root shell), never while a CPU or GPU temperature cap is active, and only when the control loop's own read is newer than the last write. The log summarises the fixes once a minute as a `CpuDrift` line (count, what the kernel had, what we wanted).
- `SmoothSlice.shouldFixDrift` (pure) with unit tests.

### Changed
- Version 4.1.0 (versionCode 73). The default behaviour is unchanged: the option is off, and overrides are still only counted (as in 4.0.9).

## [4.0.9] - 2026-11-27

### Added
- **Smooth limit slice length.** New setting "Smooth limit: slice length" (30-400 ms, default 50 ms = exactly the behaviour before 4.0.9). It is the shortest time the smooth limiter stays on one of its two frequency steps. The fast switcher runs on its own 20 ms timer, so this (not the 140-450 ms control tick) is what sets how finely the average power is sliced.
- **Automatic slice learning** (off by default): "Learn the slice length automatically". Only during long steady game sessions (2 minutes of game, power limit engaged, discharging, no launch burst), one CPU cluster at a time. It measures a 45 s baseline, then tries the next shorter slice from the ladder 400 / 300 / 200 / 150 / 100 / 70 / 50 / 40 / 30 ms for 45 s, and keeps going only while it is better (overshoot smaller or FPS steadier) and nothing is worse (overshoot, FPS steadiness, CpuDrift re-asserts, write time, switch-rate backoff). If something gets worse, or there is no clear gain, it goes back to the last good slice and keeps it. If the load changed too much to compare, the stage is measured again (up to 2 times). The slider is the starting point; the results are saved per cluster. Switching learning on again starts from scratch.
- **Hard safe range.** Never shorter than 30 ms or longer than 400 ms, and never shorter than 3 x the measured p90 write time (rounded up to the ladder). The existing switch-rate guard (phase doubling above 14 switches/s, 30 s backoff above 28/s) still applies on top.
- **Write time logging.** Every frequency write is timed (including any wait for the root shell). `WriteLat` log line every 30 s: count, average, p50, p90, max for CPU writes and for GPU/other writes. A single write over 150 ms logs a warning (at most every 10 s).
- **One `SmoothSlice` log line every time a slice changes** (trial start, kept and trying shorter, reverted, settled, interrupted), with the numbers that decided it, e.g. `cpu_3 slice 100 -> 70 ms (trial)` and `cpu_3 slice settled at 100 ms (kept 100ms: 70ms was worse: ...)`.
- `SmoothSliceLearnerTest` unit tests (ladder and floor, decision function, window statistics, latency tracker, state machine).

### Changed
- CpuDrift counting now also sees the kernel overriding the fast switcher's last write (the control loop does not re-assert while the fast switcher owns a cluster, so those overrides were invisible before). It is only counted for the learner; behaviour is unchanged and the `CpuDrift` log lines are unchanged.
- Version 4.0.9 (versionCode 72).

## [4.0.8] - 2026-11-27

### Changed
- **The Game Thread Controller has its own root shell.** Its thread scan (which can hold a shell for 4-7 s in a busy game) used to share the background shell with the FPS reads, so FPS updated only about every 8 s in games. The scan and its taskset writes now use a fourth shell (`RootShell(thread)` in the log), so FPS reads, Stutter Hunter and Auto-Tune no longer wait behind it. The control loop's shell is untouched.
- **Backup plan for slow scans.** When a thread scan takes 1.5 s or more, the app tries a one-command scan (a single `grep` over every thread's `comm` file) once. It is switched to only if it finds about the same threads (90-110% of them) and is at least twice as fast; otherwise the normal scan stays. If the one-command scan ever returns nothing, it falls back to the normal scan. The log says which was chosen.
- Version 4.0.8 (versionCode 71).

### Added
- `GameThreadFastEnumTest` unit tests for the one-command scan command, its parsing and the trust check.

## [4.0.7] - 2026-11-27

### Changed
- **Game Thread Controller scans far less often.** Each scan of the game's threads costs several seconds of root shell time, and it used to repeat every 2 s. It now scans every 2 s only for the first 30 s after a game starts (while threads are still appearing), every 4 s while a scan still finds new threads to pin, and every 12 s once things have settled. Pinning itself is unchanged and new threads (for example when a match loads) are still picked up.
- **Game-set FPS caps no longer count against the tune.** When the game holds a flat ~30 FPS for 2 s or more (lobby, menu, pause) in a session that otherwise runs well above 30, those seconds are left out of average, minimum, 1%/5% low, median, stability and FPS per watt. Auto-Tune therefore no longer rejects a trial with `safety_p5_collapse` because the game went to its lobby. A session that simply runs at 30 FPS all the time is left alone, and a single dip or a wandering 28-32 FPS run still counts as real. Temperature, power and energy still use every second.
- Saving a session logs when this happened, for example `Game held a flat ~30 FPS cap for 17s (lobby/menu/pause, set by the game, not by CoreGovernor)`.
- Version 4.0.7 (versionCode 70).

### Added
- `GameLimitedFpsTest` unit tests for the cap detection and the scan interval.

## [4.0.6] - 2026-11-27

### Added
- **Smooth limit minimum model confidence slider** (`Tuning` -> `Power throttling`, shown while Smooth power limit is on). Range 10-95%, default 40%, saved with the config as `smoothMinConfidencePct`. It is separate from the Energy tab's confidence setting, so changing one no longer affects the other.
- Unit tests for the average-confidence rule, the lower-threshold case, faster cap release and noisy-sample protection.

### Changed
- **Smooth power limit judges the model by its average confidence.** Before, one rarely-visited frequency step below the threshold switched smoothing off for the whole ladder, so in a game every cluster stayed on `plain (low model confidence)`. Now the average over the steps must reach the slider value, and the two steps actually alternated between must reach half of it.
- The log reason now shows the numbers, for example `low model confidence (avg 33% < 40%)`, so you can set the slider just below the average.
- **Power model learns faster.** Confidence builds quicker (about 60% after 8 samples instead of 14), frequency steps never visited inherit more confidence from their neighbours (0.7 instead of 0.45), and each sample corrects the model harder while a step is still young.
- Version 4.0.6 (versionCode 69).

### Fixed
- **Power cap sank too far and stayed there.** The cap was cut by up to 12% per 350 ms from the last few noisy watt samples but lifted by only 1-2% per step, so it ratcheted down to about 40% and cost FPS while power was close to the limit. Maximum cut per step is now 2.5x instead of 4x, an engaged cap judges the blend of the fast and slow readings, and release speeds up (up to 8x) the further the draw is under the limit.

## [4.0.5] - 2026-11-26 (Optimized edition)

### Changed
- **Fewer root round trips when pinning CPU clusters.** The max and min frequency writes for a cluster now go to the root shell as one ordered command instead of two. Write order (max then min when raising, min then max when lowering), per-file error handling, failure cooldowns and log lines are unchanged. Any write that does not report success is retried the old way, including the chmod fallbacks.
- The two writes can no longer be split by the 20 ms fast switcher, which also reduces waiting on the shared root shell.
- Version 4.0.5 (versionCode 68).

### Added
- `SysfsBatchWriteTest` unit tests for the batched write command and its result parsing.

## [4.0.0] - 2026-11-26

### Added
- **Game Session Comparison & Full Details (`Metrics` tab):**
  - **Settings snapshot per session:** Each recorded session now captures a compact snapshot of the active tuning state at game start (Game-mode CPU/GPU headroom & ramp speeds, CPU/GPU frequency ranges, thermal throttle curve/start/full/reduction/reaction, power throttle limit/sustain/step/smooth limit, Afterburn state & per-package strength, RAM pin, Stutter Hunter mode, Game Thread Controller, Adaptive Refresh Rate, per-game override status, and Auto-Tune trial ID) inside the `#CG1|` CSV header. Legacy sessions without snapshots continue to load with "Settings not recorded".
  - **Mid-session change tracking:** Modifying tuning settings while a session is recording keeps the start snapshot intact and increments a `MODIFIED` counter on the session.
  - **Session Full Details screen:** Displays composite score (0–100), percentile FPS (`1% low`, `Median`, `5% low`, `Avg`), frame stability (% within ±10% of average FPS), power/thermal throttle durations, configurable temperature threshold time-above counter (`42/45/48/50 °C`), downsampled charts, and the full start snapshot table.
  - **Multi-session Comparison (2–4 sessions):** Side-by-side metrics table with direction arrows + colour-coded better/worse deltas relative to baseline A, shared time-axis overlay charts (FPS, power, temperature), settings-differences-only breakdown, plain-language verdicts, fairness warnings (different games, charging included, duration mismatch, short session < 20 samples, mid-session edits, missing snapshot), and plain-text export via `SessionExport.comparisonText`.
  - **Tweak Impact Ranking & customizable weights:** Aggregates recorded sessions per game to rank the best and worst tweaks with delta score, FPS, p5, watts, temperature, sample counts, and clear Low confidence warnings when fewer than 3 sessions back either value. Composite score weights (`35% p5 FPS, 25% avg FPS, 20% FPS/W, 10% temp, 10% stability` by default) can be tuned and saved.
  - **Keep protection:** Bookmarking a session marks it as `KEPT`, protecting it from automatic 40-session pruning and accidental "Delete all" unless explicitly checked.
- **Auto-Tune Per Game (`Apps` tab -> `Auto-tune & game profile`):**
  - **Per-game override profiles:** `GovernorConfig.perGameProfiles` stores optional per-package overrides (Game-mode headroom/ramps/ranges, power limit, thermal start/full, Afterburn strength) resolved and cached on package/config change with zero per-tick allocations, clamped safely within global thermal and power limits.
  - **Conservative trial-based Auto-Tune (OFF by default):** Supports `Suggest only` and `Auto-apply` modes, captures an unplugged clean baseline first, tests one bounded parameter step per full game session (settings only change at game launch, never mid-session), discards charging/short/mid-edited/no-FPS sessions, blacklists and immediately reverts on thermal/power/p5-collapse safety triggers, keeps improvements only when beating the configured reward margin (3% default), supports per-parameter locks, `Reset to baseline`, `Revert to my settings`, `Re-tune`, and a trial journal linked directly to Session Details & Comparison.

### Changed
- Version 4.0.0 (versionCode 67).

## [3.8.3] - 2026-11-26

### Changed
- **Smooth power limit also runs when the thermal cap is the tighter one.** Before, thermal caps parked on a single step. Now the limiter alternates between the two steps either side of the thermal cap too (also while charging). Thermal runs get no watt-based trim, since the trim is tied to the power limit; the trim is applied only to components where the power cap is the tighter one.
- **Smooth power limit is no longer labelled "experimental" and is ON by default for fresh installs only.** Existing saved configs keep whatever they have saved (old configs without the field still load as off).
- Version 3.8.3 (versionCode 66).

## [3.8.2] - 2026-11-26

### Fixed
- **Live watts on the overlay slowed down in games.** In 3.8.1 the overlay's watts were only refreshed when a full control tick finished. In a game each tick takes much longer (every frequency write goes through the root shell while the game uses the CPU), so the overlay fell back to a slow update. The overlay now has its own power reader: while the overlay is showing, the screen is on and the phone is discharging, it reads the battery current and voltage every 120 ms on its own timer, directly from the system files (no root shell). If the app is not allowed to read them directly, it uses its own small root shell, never the main one that the control loop uses. The control loop no longer overwrites this value while the reader is running.
  - Unchanged: the averaged watts used by the power limit, the history graphs, the notification and the logs, and the overlay's 150 ms redraw limit.
  - Limit: the battery gauge itself usually refreshes about once a second on many phones, so very short spikes can still be flattened by the hardware. Other overlay values (CPU and GPU load and clocks, temperature) still update once per control tick.

### Changed
- Version 3.8.2 (versionCode 65).

## [3.8.1] - 2026-11-26

### Added
- **Quick Settings tile "Overlay".** Add it from the tile editor (pencil icon in the Quick Settings panel). Each tap goes to the next step: Off, Compact, Expanded, Detailed, then Off again. The tile shows the current style, works even when Click-through is on, and turns the overlay on (and starts the governor if it is not running). If the overlay permission is missing it tries to grant it with root, otherwise it opens the Android permission screen. Tapping the overlay itself still cycles Compact, Expanded, Detailed as before; the tile follows along.

### Changed
- **Faster live watts on the overlay.** Three things were slowing it down: the overlay redrew at most once per second (now every 150 ms), the value was a 10-sample average (about 1.5 s of lag; the overlay now shows a fast reading that mostly follows the newest samples), and the control loop slowed down when the phone looked calm (it now stays at your poll interval while the overlay is on). The averaged value is unchanged for the power limit, the history graphs, the notification and the logs, so limiter behaviour does not change. Limit: the battery gauge itself usually refreshes about once a second on many phones, so very short spikes can still be flattened by the hardware.
- Version 3.8.1 (versionCode 64).

## [3.8.0] - 2026-11-26

### Added
- **Smooth power limit (`Tuning > Power`, experimental, OFF by default).** Instead of parking a CPU cluster or the GPU on one frequency step, the limiter switches between the two steps either side of the target so the *average* power lands on the limit (example: 5 W at 2.5 GHz, 3.5 W at 2.4 GHz, limit 4 W = about 1/3 of the time on 2.5 and 2/3 on 2.4). It uses the power model to pick the two steps and the share of time, reads the cap as a share of the power range, and applies a slow correction from measured watts if the model is off (limited to +/-15%). Phases are at least 50 ms. If frequency writes pass 14 per second it lengthens the phases, and if they stay far above that it pauses for 30 s.
  - Switching runs on its own 20 ms timer (a "fast switcher"), so it no longer depends on how long a full control tick takes. The control loop only publishes the plan (two steps and the share of time) about once per tick; the fast switcher alternates the steps. While it owns a component, the control loop does not write that component's frequency.
  - Falls back to the normal cap when: the poll interval is above 500 ms, the control loop is slower than 700 ms per tick (the plan would go stale), the model is not confident enough (uses the existing minimum confidence setting), or the model looks flat or broken. A plan older than 1.5 s is ignored and the normal loop takes over again. Also not used during Afterburn/burst, or when the thermal cap is the tighter one.
  - Log tag `SmoothLimit`: when it starts or pauses (with the reason), and every 5 s the step pair and duty per component, measured watts, switches per second and trim.
  - Limits: while it alternates, the efficiency governor does not pick the frequency for that component (it stays between the two planned steps, never above the load-based target); some kernels fight max-frequency writes (the existing drift re-assert still applies); needs testing on a device. The thermal limit is not smoothed yet.
  - New config field `powerThrottle.smoothPowerLimit` (old configs load with it off). New pure class `SmoothPowerLimiter` with its own tests.
- **Text size per overlay style (`Tuning > Overlay`).** Compact, Expanded and Detailed each get a Text size slider (70-160%). It scales labels, values, the ring captions and the Detailed rows (including their line height); the figure in the middle of the rings keeps its size. Measuring, the Size slider limit and drawing all use the same multiplier, so the overlay never gets cut off. New config fields `overlayCompactTextScalePct`, `overlayExpandedTextScalePct`, `overlayDetailedTextScalePct` (100 by default, so nothing changes until you move a slider).

### Fixed
- Unit test `maxScalePct...` was out of date: it expected per-style caps (60%/45% width, 35% height) but the code uses one cap for all styles (85% width, 50% height). The test now matches the code; app behaviour is unchanged.

### Changed
- Smooth power limit: the "paused" log line now appears once per cause (it used to repeat every tick), says when it resumes, and the loop-speed average reacts faster and ignores idle pauses. The active log line now shows the real time per control tick.
- New English texts for the two features above; other languages fall back to English until translated.

## [3.7.2] - 2026-11-26

### Added
- **Afterburn strength per app (`Tuning > Power > Afterburn`).** Every app in the Boost apps list gets its own Default / Light / Medium / Max choice, so a heavy game can use Max and a lighter app Light. Default follows the main Boost strength. The pill and the Afterburn decision use the strength of the app being boosted (also during the 3 s grace period). Removing an app from the Boost list forgets its strength. New config field `boostStrengthByPkg` (old configs load unchanged).

### Changed
- Version 3.7.2 (versionCode 62).

## [3.7.1] - 2026-11-26

First release of the 3.7 series (3.7.0 was never published; everything is listed here).

### Added
- **Independent controls for each overlay style (`Tuning > Overlay`).** Compact, Expanded and Detailed each have their own Size, Horizontal and Vertical sliders (separately for portrait and landscape) and their own "Reset position" button. All three are always editable, not only the one currently showing, and the live one is marked "(showing now)". The Fine chip and the - / + stepping work on every slider. New helpers (`overlayScaleFor`, `withOverlayScalePctFor`, `overlayPosPmFor`, `withOverlayPosPmFor`, `withOverlayPositionResetFor`) and a `updateOverlayStyle` ViewModel action back this.
- **Afterburn status dot on the overlay.** A small dot in the top-right corner of every overlay style shows what Afterburn is doing for the app in front: green = boosting, amber = reduced, red = blocked (heat or battery). Nothing shows while Afterburn is off or idle. It sits inside the padding, so overlay sizes do not change.
- **Per-style background opacity.** Compact, Expanded and Detailed each have their own Background opacity slider. Moving the main background opacity slider still sets all three; adjust a style afterwards to fine-tune it. Old configs follow the main value (new fields `overlayCompactBgOpacityPct`, `overlayExpandedBgOpacityPct`, `overlayDetailedBgOpacityPct`, -1 = follow main).
- **Own size for the Detailed overlay.** Detailed no longer shares its size with Expanded. Until you change it, it keeps following the Expanded size (sentinel `-1`), so existing setups look exactly the same. Landscape falls back to the Detailed portrait size, then to Expanded. New config fields: `overlayDetailedScalePct` and `overlayDetailedScalePctL` (old config files load unchanged).
- **New power-cap controller (`PowerCapController`, pure and unit tested).** Replaces the old fixed-step loop that sawtoothed around the limit:
  - steps the cap down in proportion to how far the draw is over the limit (1x to 4x the step);
  - once engaged, keeps stepping every 350 ms instead of waiting for the sustain time again;
  - judges the draw on a short window while engaged, so it reacts to what the last step did;
  - skips half the sustain wait for a big spike (more than 35% over the limit);
  - only releases after the draw has stayed under 95% of the limit for 1.5 s, so it settles instead of bouncing across the limit;
  - never goes below the safety floor, and holds the stored cap while Afterburn is bypassing it.
- **Three root shells.** The control loop's power and frequency reads keep the main shell. Slow, occasional jobs (thread scan, FPS dumpsys) use a second background shell, and process scans (Stutter Hunter, System tab) use a third, so none of them can queue in front of the control loop or each other. Writes stay on the main shell.
- **Slow root command log.** When a root command runs 400 ms or more, or waits 600 ms or more for the shell, the Log tab names the command (at most one line every 5 s).
- **Afterburn "waiting" reason.** While a boost app is in front but Afterburn is not boosting, the log says why every 10 s (state, temperature against the throttle start and needed margin, battery level against the gauge, smoothed voltage sag).
- **Memory-pressure handling in Stutter Hunter and the System tab.** The process scan also reads total and available RAM and the kernel's memory-stall figure, so memory pressure can be told apart from CPU hogs. Covered by new unit tests (`MemoryHogTest`).

### Changed
- **Battery power reads no longer stall or read as 0 W.** If the root read lacks the battery current or voltage nodes, they are read directly (no shell). A failed or empty read is no longer counted as a 0 W sample: the last good reading is reused for at most 2.5 s, so a frozen value can no longer sit on screen and drive the cap.
- **Afterburn is steadier in games.**
  - A game that briefly shows another package (login page, WebView, overlay, notification shade) keeps its boost for a 3 s grace period.
  - The voltage-sag cut is judged on a roughly 2 s average, so one load spike cannot trip it.
  - The voltage-graded battery level may sit at most 10 points under the gauge, so one sag-distorted estimate cannot push a healthy phone into the low-battery rules or the 5% cutoff.
- **Faster game thread scan.** Thread names are read with the shell builtin `read` instead of one `cat` and `tr` per thread, so a game with hundreds of threads no longer holds the root shell for seconds on every scan.
- **Switching overlay style refreshes the numbers at once** (the per-core rows no longer wait up to a second).
- Version 3.7.1 (versionCode 61).

### Fixed
- **Afterburn pill for Boost-list apps.** The "Afterburn engaged" pill was shown only for apps in the Games list, while Afterburn itself only looks at the Boost apps list. An app that was only in the Boost list was boosted with no pill, which looked like Afterburn was not engaging. Any app Afterburn boosts (Games list or Boost apps list) now shows the pill.
- **Charge sleep guard.** `onScreenOff`, `onScreenOn` and `stop` are now serialized, since they are called from the screen receiver and from the engine's stop, so no kill job or `getevent` process can leak. A power-button listener cancelled while its `su` process was still starting is now destroyed instead of lingering until the next key event.
- **Stutter Hunter.** `onStop()` (main thread) and the hunting loop (background thread) now share one lock for game-session state, baselines and the detector. The throttle cause reported for an FPS dip is read under that lock, so a stop during a tick can no longer report a wrong or empty cause.

## [3.6.8] - 2026-11-25

### Added
- **Persistent per-game thread history (`Tuning > Game Threads`).** Observed live threads are merged and saved per game inside `core_governor_config.json` (`threadHistory` and `lastObservedGamePackage`), recording game package, display name, last process name, thread name (`comm`), last observed timestamp, observation count, and session `lastKnownTid`.
- **Thread History UI section.** Distinguishes **LIVE** threads (with current TIDs and latest observation time) from **HISTORICAL** threads (with last known TID, last seen time, observation count, and mapped CPU status), allows switching games, using or assigning/editing Exact or Pattern CPU mappings directly from any historical thread name, removing individual historical entries, and clearing history per game or across all games.
- **Background-game and history fallback handling.** Opening CoreGovernor while a game is running keeps the selected/last-observed game target intact, refreshes live threads if the game process still has an active PID (`BACKGROUND_LIVE`), or displays saved history with `"Game is not currently active or live threads are unavailable. Showing saved thread history."` (`SHOWING_HISTORY`).

### Changed
- **Live-TID-only `taskset` enforcement.** Custom mappings created from historical threads always key on the thread name or pattern rather than stale TIDs, and runtime `taskset` pinning continues to target only currently live TIDs while the selected game is active in the foreground.
- **Backward-compatible config persistence.** Existing configuration files without thread history fields load cleanly with empty history, and history pruning prioritizes custom-mapped and most-recently-observed threads.
- Version 3.6.8 (versionCode 58).

## [3.6.7] - 2026-11-25

### Added
- **Game Thread Controller (Tuning > Game Threads, off by default).** Inspects the foreground game's PID and live `/proc/[pid]/task/[tid]/comm` threads via `RootShell` (`TID — thread name` with manual Refresh and safe handling if processes or threads exit), and enforces per-thread CPU affinity using `taskset -p` in Game mode.
- **Core assignment, matching & presets.** Multi-select CPU core chips (`Core 0`–`Core 7`, bounded safely by detected CPU count), Linux hexadecimal affinity mask conversion, readable core-range labels (`0-2`, `3-5`, `6-7`), quick presets (`Big 6-7`, `Mid 3-5`, `Little 0-2`), both Pattern matching and Exact thread-name matching, plus Edit and Remove actions for saved mappings.
- **Auto-classify threads fallback.** Optional conservative fallback (off by default) that assigns `Main`/`Render` threads to `Big 6-7` and `Worker` threads to `Mid 3-5` only when no custom mappings exist, never overriding user mappings or guessing unknown threads.
- **Persistence & runtime safety.** Saved inside `core_governor_config.json` (`gameThreadController` with `enabled`, `autoClassify`, `selectedGamePackage`, `mappings`, and `threadMaskMap`), running in `GovernorEngine` at most once every 2 seconds strictly in Game mode for a selected foreground game, caching unchanged `(pid, thread, mask)` state, and ignoring individual `taskset` failures safely.

### Changed
- Version 3.6.7 (versionCode 57).

## [3.6.6] - 2026-11-25

### Fixed
- **Crash on launch ("Cannot create an instance of CoreGovernorViewModel").** The engine's log was created after the parts that write to it during startup, so if the saved rating data could not be read, the app crashed before opening. The log is now created first, so a bad or unreadable data file is logged as a warning and the app starts normally.

### Changed
- **Real battery level now works while charging.** The charger's voltage rise (charge current x internal resistance) is taken back off, then the level is read from the same curve as when unplugged. It is only graded between 5% and 90% (before the constant-voltage taper), after the reading has settled, and never strays more than 10 points from the gauge; confidence is capped lower than the unplugged grade. Afterburn ignores this charge-side grade and keeps using the gauge while charging, because the charger inflates the voltage.
- **Full-charge calibration feeds the capacity estimate.** The capacity read from a clean charge to 100% now joins the blend. A mostly slow charge (under 15 W) counts about three times more than a fast one, and a reading far from the design capacity is ignored.
- **Battery capacity and health now use the charging data.** Each charge segment records its average power, time spent at each power level (<5, 5-10, 10-15, 15-20, 20-25, 25+ W), highest voltage and peak current. Fast-charge segments count less toward capacity and health, because the fuel gauge runs ahead of the real charge at high power. The voltage lost inside the cell (current x resistance) also lowers a segment's weight once the resistance is learned.
- Charge segments can no longer outweigh the discharge side by more than 1.5x, and the estimate's confidence now counts trust-weighted percent steps, so many fast-charge segments no longer inflate it.
- **Time to full** now follows the learned charge-power curve per 10% of battery, so the slow taper near 100% is counted.
- Battery card shows charge time per power band (session and lifetime), peak charge voltage, charge-data trust, and average W / peak V on each recent charge segment.
- **Charge sleep guard (Tuning > Screen-off).** While the screen is off and the phone is charging, it stops background apps (through root) that wake the CPU and heat the phone, so charging stays cooler. Choose a whitelist of apps that are never stopped, an always-stop list of apps and system processes, or switch on "Stop all other user apps". Your launcher, keyboard, phone and CoreGovernor are never stopped, and nothing is stopped while music is playing. While the guard is on, the screen-off clock saving also applies while charging.
- **Fast wake boost.** While the screen is off, the power button is detected straight away and the clocks go to maximum for a moment before the screen lights, so unlocking is quicker. Works for any wake, even when the guard is off.
- Version 3.6.6 (versionCode 56).

## [3.6.5] - 2026-11-24

### Added
- **Persian (فارسی) language.** The whole app, overlay and notifications are now available in Persian, with right-to-left layout. Choose it under Appearance > Language.

## [3.6.4] - 2026-11-24

### Added
- **Pin sensors only in games.** New switch under Pin sensor refresh rate: "Only pin while in a game". The sensors are held at your chosen rate only while a game is detected and running (Game mode) and are released the rest of the time, which cuts idle power use. Works together with "Pause while the screen is off". Off by default, so existing behaviour is unchanged.

## [3.6.3] - 2026-11-24

### Changed
- **Bigger overlay size range.** The overlay size sliders now go well past 100% in portrait (it used to stop around 73%) and further in landscape. The overlay is still limited so it always fits on screen.

## [3.6.2] - 2026-11-24

### Fixed
- **Landscape overlay.** The overlay, its size limit and the position sliders now share one screen size and orientation, taken from the system configuration instead of the display rotation (which could report portrait while rotated). This fixes the tiny size cap, the wrong position and the size flicker in landscape.

## [3.6.1] - 2026-11-24

### Added
- **Stutter Hunter reports governor throttling.** When an FPS dip happens while the governor is actively limiting performance because of heat, or because of the battery/power limit (or Afterburn blocked by heat or battery), Stutter Hunter now says so in its actions list and log instead of blaming a background process. No process is investigated or stopped in that case. A low battery percentage alone is never reported as the cause.
- **Afterburn ending toast.** A pill now also appears when Afterburn drops from boosting to reduced or blocked (heat or battery) while you are still in the game.

### Changed
- **Afterburn toasts are selected-game only.** Start and ending toasts appear only for an app in your Games list that is also the foreground app. Apps that are only in the Afterburn list never show a toast, and leaving the game does not show an ending toast.
- **Landscape overlay controls and remembered positions.** Landscape sizes and positions are saved separately from portrait for every overlay style, and fall back to the portrait values for older saved configs.

### Fixed
- **Overlay drag in landscape.** The overlay can now be dragged across the whole screen in landscape (screen size is taken from the physical display and current rotation) and uses the landscape position. Placement details are written to the log under "Overlay".
- **Landscape position sliders.** The horizontal and vertical position sliders now save to the landscape slot while the phone is in landscape (they were saving to the portrait slot, so they looked stuck).
- **Liquid glass text color.** Text inside liquid-glass cards now uses the theme's text color instead of turning black.

## [3.6.0] - 2026-11-24

### Added
- **Stutter Hunter (Tuning > Power).** Learns each game's normal FPS (from the Games list) while in the foreground and watches for sudden drops. When FPS dips below your sensitivity threshold without thermal/power throttling or GPU saturation, it samples background processes and either logs the top CPU hog ("Notify only", default) or stops it with `am force-stop` ("Stop apps") using session safety brakes and a 6-second recovery check. Includes a live status line, recent actions history, and a non-system Whitelist.

## [3.5.0] - 2026-11-24

### Added
- **Protect from being killed (root).** New switch in Tuning > Control, on by default. It exempts CoreGovernor from Doze and app standby, keeps its process (and the FPS daemon) at a protected `oom_score_adj` and re-checks it about every 30 seconds, and on Android 12+ turns off the phantom process killer. Your original phantom-process settings are saved and restored when you switch it off. A tiny root watchdog restarts the service if the app is killed while the governor is meant to be running, and stops when you stop the governor on purpose. A status line shows what was applied and verified, and each step is logged.
- **Afterburn toast.** A small, non-touchable pill over the game shows "AFTERBURN ACTIVE", the app name and the boost level when Afterburn starts boosting (once per boost session, at least 20 seconds apart). Needs the overlay permission; switch it off with "Afterburn toast" in the Afterburn section.
- **Languages.** English, Bahasa Indonesia, Spanish, Portuguese (Brazil), Russian, Turkish, Hindi, Arabic, French and Vietnamese, with a Language selector in the Appearance card. It applies immediately and is remembered. Missing text falls back to English.
- **Built-in preset for Dimensity 8300 class phones** (CPU policies 0, 4 and 7). It sets the daily and game CPU/GPU maximums and a temperature throttle that starts at 60 C. It changes only those values, keeps all your other settings, and only appears on matching phones.

### Changed
- **Update checker.** Now reads the latest releases (not just the newest stable one), picks the highest version, and has an "Include pre-releases" switch (off by default). It remembers GitHub's answer so repeated checks do not use up the rate limit, retries once, and shows clear messages for offline, rate limited (with the time to try again), no release found, up to date and update available. The update card shows release notes, a Download button and Dismiss for that version.
- Version 3.5.0 (versionCode 49).

## [3.2.0] - 2026-11-24

### Changed
- **Minimalistic look.** Calmer, flatter colours with a softer accent, thinner and quieter chart lines, a flatter bottom bar (only the selected tab is highlighted, no heavy pill) and slightly tighter cards. Nothing about how the app works has changed: every screen, switch, slider and setting is still there and behaves the same.

### Added
- **Appearance (System tab).** All purely visual and remembered:
  - **Themes:** System, Dark, Light or AMOLED black, plus an accent colour.
  - **Border colour:** presets or a custom colour.
  - **Corner radius slider** (sharp to very round) and a **card transparency slider**.
  - **Liquid glass:** frosted translucent cards with a soft sheen and light edge. With a background picture it also blurs the picture (Android 12+).
  - **Background image** from the system photo picker (no storage permission), with a dimming slider and Remove.
  - A Reset button returns everything to the minimalistic defaults.
- **New app icon** (phoenix), including the themed (monochrome) icon.
- Version 3.2.0 (versionCode 48).

## [3.1.0] - 2026-11-24

### Added
- **Real battery level (Energy tab > Battery).** The app now grades the actual battery level from voltage and current behaviour. It learns the battery's internal resistance from voltage drops when the load jumps, adds the sag back, and reads the level from a voltage curve. It shows the real level, how much headroom the gauge hides, the detected chemistry, resistance and live sag.
- **High-voltage vs standard Li-ion detection.** A 4.4 V cell and a 4.2 V cell use different curves. The app tells them apart from what the cell actually does (a resting voltage above 4.26 V can only be high-voltage) and re-learns on every start.
- **Battery calibration (Energy tab).** One undisturbed charge to 100% (until the battery stops taking power) records the full-charge voltage, which settles whether the cell is 4.2 V or 4.4 V, plus the charged capacity. One undisturbed discharge from 95% to empty records the real resting-voltage curve. Plugging in, a long gap or a restart discards the run in progress. The result is saved and used for the real level.
- **Afterburn uses the real level and voltage sag.** The battery condition uses the graded level when it is trusted, so a gauge that reads low no longer holds boost back. New optional "Voltage sag awareness": boost fades from 150 mV of sag and stops at 400 mV, to protect a weak or cold cell.

### Changed
- **Overlays can be made smaller.** Minimum size for the compact, expanded and detailed overlays is now 25% (was 50%).
- **Design capacity fix.** If the kernel reports a design capacity 10 times too small (for example 516 instead of 5160 mAh), it is corrected from the kernel full capacity, so State of Health no longer reads above 100%. A value that cannot be trusted is hidden and you can set it manually.
- Version 3.1.0 (versionCode 47).

## [3.0.0] - 2026-11-24

### Added
- **Afterburn (Tuning > Power).** While an app you picked is in front, Afterburn temporarily bypasses the power-throttle cap so the phone can hold higher clocks. It has a master switch and a strength (Light / Medium / Max). Thermal throttling always stays in charge, and the stored cap is held (not pushed down) during a boost, so normal limits come back smoothly when the boost ends.
  - **Thermal condition (optional).** Boost only while the temperature is at least 3 to 8 C (adjustable) below your thermal throttle start. More headroom gives a stronger boost.
  - **Battery condition (optional).** Below the threshold (default 40%) the boost is reduced, and close to empty there is none. Charging ignores it.
  - **The two conditions decide together.** A low battery raises the required temperature margin by 2 C, and thermal headroom scales the boost. Either can be switched off and the other still works. A live status (Idle / Boosting / Reduced / Blocked) shows on the card, and changes are written to the Log tab.
- **Afterburn app list (Apps tab).** A new "Afterburn" list next to the Games list picks the apps and games Afterburn may work on.

### Changed
- Version 3.0.0 (versionCode 46). Afterburn is off by default, so nothing changes until you turn it on.

## [2.7.4] - 2026-11-24

CoreGovernor now uses less CPU and memory by itself. Governing behaviour is unchanged: thermal and power throttling, the learned power model, the efficiency governor, smart app profiles and touch boost work exactly as before.

### Added
- **Adaptive polling (Tuning > Control, on by default).** Your Poll Interval is still the fastest the loop ever runs. When the phone has been calm for about 2 seconds (screen on, no game, no launch burst, no throttling, low CPU and GPU load, no touch for 1.5 seconds), the loop waits up to about 350 ms between checks. It goes back to your Poll Interval straight away when you touch the screen, switch apps, load jumps, a game starts or throttling begins. Turn it off to always poll at the Poll Interval. The Log tab notes when it switches between calm and active (at most once every 10 seconds).

### Changed
- **Smoother app screens.** Only the Monitor tab, and the temperature and power cards in Tuning, now update several times per second. Other tabs and the top bar no longer redraw on every reading.
- **System tab sampling is lighter.** The process list refreshes every 8 seconds (was 4), pauses while the screen is off or the app is not in front, and still shows the first reading right away. Culprit detection still looks at about 30 seconds (now 4 samples instead of 6).
- **Metrics live list.** The live history is only prepared while the Metrics tab is open, and appears at once when you open it. Recording, saved sessions and the device rating are not affected.
- Version 2.7.4 (versionCode 45).

## [2.7.3] - 2026-11-24

### Added
- **Boot guard (subtle).** If the governor never stays up for 60 seconds after an automatic start on boot, 3 boots in a row, the next automatic start is skipped once and the reason is written to the Log tab. Tuning > Control shows one small note only when this happened. Starting or stopping by hand always works and clears the counter, and a stable run clears it too.
- **Share a saved session (Metrics tab).** Open a saved session, then Share summary (text with FPS, 5% low, temperature, power, FPS/W, energy, device and app version) or Save CSV (copies the session file to a place you choose).
- **Overall device rating (new System tab).** One 0-100 score built over time from performance (5% low FPS), efficiency (FPS/W), power draw, thermals, idle CPU load outside games and standby drain (screen-off analysis). Uses only device-independent measures, ignores charging, shows a confidence level and a per-part breakdown, and has Share rating and Reset. Needs about 30 minutes of unplugged use. Stored in `rating.json` (atomic, serialized saves).
- **Process monitor (System tab).** Lists apps and system processes by CPU use and memory while the tab is open. Apps that stay busy in the background for about 30 seconds are marked as culprits (sensitivity High / Medium / Low), with a small stop button that force-stops the app with root after a confirmation. Core system processes, root processes, native daemons, the launcher, the keyboard, the dialer, this app and the foreground app are protected and have no stop button.

### Changed
- The bottom bar now has 8 tabs, so only the selected tab shows its label.
- Version 2.7.3 (versionCode 44).

## [2.7.2] - 2026-11-23

### Added
- **Peak and minimum power in Performance Metrics.** The summary now has PEAK POWER, MIN POWER and ENERGY tiles, and each saved session lists its power range. They cover the part of the history you are looking at (the live window or the opened session). Charging samples are excluded, and a 0 W reading (no sensor value) is never counted as the minimum. Sessions saved before this version show a blank minimum.
- **Background keep-alive (Tuning > Control).** Shows whether Android battery optimization is off for CoreGovernor and turns it off with one button. It tries root first (no prompt, whitelists the app in the device-idle controller and allows background running), checks the real result, and falls back to Android's own "Allow background activity" prompt. There is also an Open app settings button for phone brands with their own Autostart or Background switches. Adds the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission.
- **Always pause on open (OPPs tab).** A second switch under Pause live updates. When on, the OPPs screen starts paused every time you open the app. The Refresh button still takes a fresh reading. The choice is remembered.

- **Restore last state after reboot.** If the governor was running when the phone restarted, it now starts again by itself as soon as Android finishes booting, even when Start Automatically on Boot is off. "Running" is only cleared by pressing Stop (in the app or the notification), so a shutdown, crash or system kill still restores it. New switch "Restore last state after reboot" in Tuning > Control (on by default). The old Start Automatically on Boot switch still works as before. It also brings the governor back after an app update, and listens for the quick-boot broadcast some phone brands send. The Log tab records why it did or did not start.

### Changed
- The Performance Metrics footer no longer repeats energy and peak power (they have their own tiles now).
- Version 2.7.2 (versionCode 43).

## [2.7.1] - 2026-11-23

### Added
- **Screen-off power analysis (Energy tab).** Measures every screen-off period of 3 minutes or more while unplugged: battery used (% and mAh), drain per hour, average watts, and deep-sleep share (uptime vs real time). Shows 7-day and today totals against screen-on drain, a standby estimate, the worst recent period, and a short verdict. Names what keeps the phone awake: kernel wakeup sources (time active and wakeup count during the off period) and apps holding partial wakelocks (checked every ~10 min while off, only when the device is already awake). Charging periods are excluded. Stored in `screen_off_log.json` (last 40 periods, 14 days), with an on/off toggle and reset.

### Changed
- **Adaptive refresh rate is no longer labelled Experimental** (badge and warning text removed).
- Version 2.7.1 (versionCode 42).

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
