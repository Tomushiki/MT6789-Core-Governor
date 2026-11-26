# CoreGovernor User Guide

A plain-English guide to what each part of CoreGovernor does and how to start. Exact steps and values vary by device, so treat the suggested setups as starting points and adjust them while watching the Monitor screen and the overlay.

## 1. First start

1. Install the app and open it. Allow root (superuser) access when your root manager asks.
2. On Android 13 and later, allow Notifications so the service is not killed.
3. Go to the Monitor screen and start the governor. The overlay and the controls only work while the service is running.
4. Optional: in the Apps tab, grant Usage Access so the app can notice game launches instantly.

## 2. The screens

- Monitor: live CPU and GPU frequencies, load, temperature and power. Start and stop the service here and switch between Auto, Normal and Game.
- Metrics: history graphs for power, temperature, FPS, FPS per watt and work per watt, plus saved game sessions (see section 7c).
- Energy: on-device learned power curves, live estimated vs measured power, and the opt-in Efficiency governor (see section 7d).
- Tuning: all the settings explained below.
- OPPs: the list of GPU speed steps your device reports. Useful for troubleshooting. Turn on Always pause on open if you want this screen to start paused every time.
- System: your overall device rating and the process monitor (see sections 7f and 7g).
- Apps: choose which apps count as games.
- Log: what the governor has been doing, with timestamps. Check it to see whether things are working.

## 3. Normal mode and Game mode

CoreGovernor keeps two separate profiles.

- Normal mode is for everyday use. It stays efficient and ramps up when you need it.
- Game mode is for the apps you select. It usually allows higher minimum speeds and less throttling.

In Auto, the app switches to Game mode when a selected app is in the foreground. You can also force Normal or Game from the Monitor screen. In Tuning, the mode you are editing is shown in the setting names, so check which one you are changing.

## 4. Frequency settings

- Minimum and Maximum Frequency (CPU clusters and GPU): the allowed range. The governor never goes outside it. A higher minimum feels smoother but uses more battery. A lower maximum runs cooler.
- CPU/GPU Load Headroom: how much spare capacity to keep. Higher headroom raises clocks earlier, so it is smoother and uses more power.
- Ramp-Up Speed: how fast speeds climb when load rises. Faster feels more responsive.
- Ramp-Down Speed: how fast speeds fall when load drops. Slower keeps performance steady but wastes some power.
- CPU Load = Busiest Core: grades each cluster by its busiest core. Leave it on if one heavy thread should make the cluster speed up.
- Poll Interval: how often the governor checks the hardware. Keep the default unless you have a reason.
- Adaptive polling (on by default): when the phone is idle and calm for a couple of seconds, the governor checks the hardware a little less often (up to about 350 ms) to use less battery and CPU. It returns to your Poll Interval instantly when you touch the screen, open or switch apps, start a game, load rises, or throttling starts, so performance is not affected. Turn it off if you want the governor to always check at the Poll Interval. Touch detection needs Smart app profiles or Adaptive refresh rate to be on; without them, calm is judged by load alone.
- Normal Mode Launch Burst: a short boost when you open an app or when load suddenly spikes, so apps open quickly.

## 5. Protecting your phone

Two throttling systems work together. Whichever asks for the lower limit wins.

Temperature throttling
- Throttle Start Temperature: where it begins to hold the phone back.
- Full-Strength Temperature: where the reduction reaches its maximum.
- CPU Max Reduction and GPU Max Reduction: how much it can cut at full strength.
- Thermal Reaction Speed and Recovery Hysteresis: how quickly it reacts, and how far the phone must cool before it lets go.
- Charging-aware thermal (off by default): optionally lowers Throttle Start Temperature by `0–5 °C` while plugged in or charging to compensate for charger heat.

Power throttling (controls battery discharge draw only, released while charging)
- Power Budget Limit: the average watts the phone may draw.
- Sustain Time Above Limit: how long the draw must stay over the limit before it reacts.
- Throttle Step Size: how much it cuts each step.
- Slow Release Step: how gently it recovers once draw is back under budget.
- Wattage Averaging Window: how many samples are averaged, so short spikes are ignored.

Safety Floor Cap: throttling never pushes speeds below this percentage of your range.

If wattage readings look inverted or 1000 times too large or small, use the calibration options in the power section.

## 6. Extras

- Screen-off power saving (off by default): after a delay with the screen off, the app drops the CPU and GPU to their lowest speeds. It can stay normal while music plays or while charging.
- Start Automatically on Boot: starts the governor after the phone restarts.
- Restore last state after reboot (on by default): if the governor was running when the phone restarted, it starts again by itself once Android finishes booting, even with Start Automatically off. It only stays off after you press Stop. It starts after Android's boot is complete, which is before you open any app, but not before the phone is unlocked the first time if you use a PIN or pattern.
- Background keep-alive (Tuning > Control): turns off Android battery optimization for CoreGovernor so the system does not stop it in the background. Tap Allow background activity. With root it works without a prompt; otherwise Android asks you to confirm. The pill shows UNRESTRICTED when it is done.
- Presets: Balanced, Cool & Efficient and Sustained Gaming are built in. You can save your own and export or import everything as a JSON backup.

## 7. Floating overlay

A small always-visible strip showing CPU, GPU, temperature and power. It is off by default and only shows while the service is running.

- Enable overlay: turns it on.
- The compact strip shows CPU, GPU, FPS, temperature and power. Tap it to cycle to the medium and detailed styles.
- Quick Settings tile: add the Overlay tile from the tile editor in the Quick Settings panel. Each tap goes Off, Compact, Expanded, Detailed, Off. It works even with Click-through on.
- The power reading on the overlay is a fast live value (it follows the newest samples). The averaged value is still what the power limit uses.
- Click-through: touches pass through the overlay. Use the Unlock overlay button in the notification to move it again.
- Show only in Game mode: hides it in Normal mode.
- Opacity, Compact size and Expanded size: change how it looks. Portrait and landscape each keep their own overlay sizes (as well as positions), and the sliders automatically edit the orientation you are currently holding. The overlay always stays on screen.
- Horizontal and Vertical position: each style remembers its own position per orientation. It snaps to the screen edges when you drag it. Press Reset position to bring it back without changing your sizes.


## 7b. Nifty features: sensor pinning

Some phones read the gyroscope slowly until an app asks for faster data. This option keeps the chosen sensors (gyroscope by default) running at a fast, fixed rate for every app, so aiming and tilt controls respond quicker. It needs no root.

- Pin sensor refresh rate: turns it on. It runs while the governor service is running.
- Only pin while in a game: holds the sensors at the chosen rate only while a game (Game mode) is running, and releases them otherwise to save idle power.
- Rate: Max asks for the sensor's fastest rate. Lower rates use less battery.
- Sensors: pick which motion sensors to pin. At least one stays selected.
- Pause while the screen is off: releases the sensors when the screen turns off. On by default.
- The card shows the measured Hz per sensor, so you can see whether it worked. The hardware limit shown next to it is what the sensor reports as its maximum.

## 7c. Metrics tab: performance and efficiency graphs

The Metrics tab shows how the phone behaves over time, so you can see what a tuning change did.

- Power, temperature and FPS are drawn together. Each line has its own scale, written under the graph. Charging or plugged periods draw as a dashed amber line on the power graph and are excluded from average watts, peak watts, total Wh, FPS/W, and Work/W.
- FPS per watt is how many frames you get for each watt drawn. Higher is better.
- Work per watt is an estimate of how much the CPU and GPU did per watt. It is not a benchmark, so only compare it on this phone, and use it to compare settings against each other.
- Drag on a graph to read the values at that moment. The same moment is marked on all graphs. Double-tap to clear.
- Live 5m, 15m and 30m choose how far back the live graphs go. History is kept only while the app is running.
- Game sessions are saved automatically when a game from the Apps tab is in the foreground for at least 20 seconds. Tap a saved session to open it, and use the bin icon to delete it. The newest 40 are kept.
- FPS is only measured while the overlay is turned on (any style), so turn the overlay on if the FPS graphs are empty.
- Peak power, minimum power and energy used are shown as tiles. Charging time is left out of them, and a 0 W reading is ignored for the minimum.
- Ratios stay blank when there is no FPS reading or the power reading is near zero. If power numbers look wrong, use the calibration options in the power section.

A good way to use it: play the same game for a few minutes with one setting, change one thing in Tuning, play again, and compare the two saved sessions (FPS/W, 5% low, peak temperature).

## 7d. Energy tab: Battery health estimator, learned power model, and efficiency governor

CoreGovernor estimates your battery's real usable capacity and passively learns how much power each CPU cluster and the GPU draw on your specific phone.

- **Battery card & Capacity estimation:** integrates charge and discharge flow across uninterrupted segments of at least **8% change** and **3 minutes** (resetting if interrupted or plugged/unplugged), rejecting outliers and combining charge + discharge segments over time. Shows estimated mAh/Wh, State of Health (with optional manual design capacity override), remaining Wh, smoothed time remaining / to full, OS charge-counter second opinion, and session/lifetime In & Out totals.
- **Learn power model (on by default):** records ~1 Hz samples while the screen is on and the phone is discharging (skipping charging/plugged periods and a 5-second settling window after unplugging). High confidence across most frequencies and apps takes several hours of everyday use and gaming.
- **Efficiency governor (off by default):** once enabled, it uses the learned power/performance model to pick lower-energy frequencies inside your allowed min/max bounds and thermal/power caps whenever confidence is above your chosen threshold. Exploration probes only run while discharging. If it sees an FPS drop or overload, it backs off immediately and falls back to standard scaling.
- **Modes:** *Balanced* targets best work per watt, *Aggressive saver* leans toward lower power draw, and *Performance-first* guards responsiveness and frame rate.
- **Export / Reset:** copy `power_model.json` to the clipboard or reset either the learned power model or battery capacity estimate at any time.

## 7e. Smart app profiles and Adaptive refresh rate (Energy tab)

**Smart app profiles** (off by default). Turn it on in the Energy tab and use your phone normally. It needs hours of use to learn: light apps (reading, chat) slowly get a lower clock ceiling, heavy apps keep or get higher clocks. Pick Conservative, Balanced or Aggressive. In the Apps tab each app has "Smart profile": Auto, Locked (freeze what was learned), Override (Light / Moderate / Heavy) or Never touch, plus a reset. Thermal limits, your caps, pins, manual profiles and Game mode always win, and any sign of high load or a touch restores full speed at once. Learning only runs while the feature is on.

**Adaptive refresh rate (off by default).** Lowers the refresh rate when you are not touching the screen and returns to the highest on touch. It needs root and may behave differently on some ROMs: use Test switch first. If switching cannot be verified three times it turns itself off. Your original refresh-rate settings are restored when you turn it off or stop the service. Videos and games stay at the active rate.

**Screen-off power analysis (on by default).** The Energy tab shows how much battery the phone uses with the screen off (periods of 3 minutes or more, not charging): drain per hour, deep-sleep share, a standby estimate and a verdict. If drain is high or deep sleep is low, the wake-source lists show the kernel sources and apps that kept the phone awake. Data needs the service running; give it a night to be meaningful.

## 7f. Device rating and sharing sessions

- The System tab shows one score from 0 to 100, made from performance, efficiency, power draw, thermals, idle load and standby drain. It needs about 30 minutes of unplugged use before it appears, and charging time is never counted.
- Use it to compare settings on the same phone (play the same way, change one thing, check whether the score goes up). To compare different phones, only do so when the usage is similar. The confidence label tells you how much data is behind the number.
- Share rating sends a short text you can paste into a group. Reset clears the collected data.
- In the Metrics tab, open a saved session and use Share summary (text) or Save CSV (the raw data file).

## 7g. Process monitor

- The System tab lists apps and system processes by CPU use (percent of one core) and memory. It only samples while the tab is open, the app is in front and the screen is on, and refreshes every 8 seconds.
- An app is marked as a culprit when it stays busy in the background for about 30 seconds. Pick the sensitivity with the High, Medium and Low chips.
- The X button stops an app with root (after you confirm). It may start again by itself. Core system processes, root processes, daemons, your launcher, keyboard and dialer, this app and the app on screen are protected and cannot be stopped.

## 7h. Boot guard

If the governor does not stay running for a minute after an automatic start at boot, 3 times in a row, the next automatic start is skipped once, and Tuning > Control shows a short note. Start it by hand from the Monitor screen. A stable run clears the note.

## 7i. Staying alive, Afterburn toast, languages and updates (3.5.0)

- **Protect from being killed (root).** Tuning > Control. On by default. Keeps CoreGovernor and its root helper from being killed by the system and restarts the service if it dies while the governor is meant to be running. The status line shows what is applied (for example oom_score_adj, whitelisted, standby, phantom killer, watchdog). Switching it off restores the system settings it changed. If your phone still kills the app, also allow background activity in the app's system settings.
- **Afterburn toast.** A short pill at the top of the screen when Afterburn starts boosting a selected app. It never takes touches. Needs the overlay permission. Switch: Tuning > Afterburn > Afterburn toast.
- **Language.** System tab > Appearance > Language (includes Persian, right-to-left). Changes apply at once.
- **Updates.** Turn on "Include pre-releases" if you want test versions. If GitHub says you are rate limited, the message tells you when to try again.
- **Dimensity 8300 preset.** In Tuning > Presets, only on matching phones. It changes the CPU/GPU maximums and the temperature throttle and leaves everything else as it is.

## 7j. Stutter Hunter (Tuning > Power)

- **What it does:** While a game from your Games list is in the foreground with the screen on and the governor service running, Stutter Hunter learns that game's normal FPS (ignoring the first 20 seconds and zero readings). Once at least 120 healthy seconds have been learned, a sustained dip below your sensitivity triggers a quick background CPU check.
- **Notify only vs Stop apps:** Defaults to **Notify only**, which logs and lists the culprit without stopping anything. **Stop apps** runs `am force-stop` with root, enforces cooldowns and session caps, and checks 6 seconds later whether FPS recovered (if stopping an app did not help, it is skipped for the rest of the game session).
- **Safety:** Thermal throttling, power throttling, or 90%+ GPU load skips hunting. Own app, foreground game, launcher, keyboard, dialer, system apps, protected processes, and apps in your **Whitelist** are never blamed or stopped.

## 7k. Session Comparison, Full Details & Tweak Impact Ranking (Metrics tab, 4.0.0)

- **Settings snapshot:** Every new game session saves a compact snapshot of all active tuning settings at launch. If you tweak settings while playing, the session is marked `MODIFIED` while keeping the original start snapshot.
- **Keep sessions:** Tap the bookmark icon on any saved session (or in Full Details) to mark it `KEPT` so automatic 40-session cleanup and default Delete All will not delete it.
- **Full details:** Open any saved session and tap **Full details** (or **Details** in the session list) to inspect `1% low`, `Median`, `5% low`, frame stability (±10%), time throttled by heat/power, time above a chosen temperature threshold (`42/45/48/50 °C`), and the full settings snapshot.
- **Compare 2–4 sessions:** Tap **Compare** above the session list, pick 2 to 4 runs, and view side-by-side deltas against baseline A, shared-timeline overlay graphs for FPS, watts and temperature, only the settings that changed, a plain-language verdict, and fairness warnings (for charging, mismatched duration, or different games). Use **Export** to share a text summary.
- **Tweak impact ranking:** Once a game has sessions with varying settings, the Metrics tab ranks the best and worst setting changes with session counts and a **Low** confidence label whenever fewer than 3 sessions back a value. Tap **Weights** to adjust the composite score formula.

## 7l. Auto-Tune Per Game & Per-Game Profiles (Apps tab, 4.0.0)

- **Per-game overrides:** In the **Apps** tab, tap **Auto-tune & game profile** under any selected game to set custom Game-mode power limits, thermal throttle start temperatures, or Afterburn strength for that game alone (capped safely by your global limits).
- **Trial-based Auto-Tune (off by default):** Enable it per game and choose **Suggest only** (shows the next candidate change for you to tap Apply) or **Auto-apply**.
  1. Play an unplugged session of at least your configured minimum minutes (default 8 min) so Auto-Tune records a clean baseline.
  2. Candidate tweaks are applied only when a game starts—never mid-session. Short, charging, or mid-session-edited runs are ignored automatically.
  3. Safety checks revert and block any candidate that hits thermal limits, excessive power throttling, or a >15% drop in 5% low FPS.
  4. Lock individual tweaks you want untouched, inspect every trial in the **Trial Journal**, or use **Reset to baseline**, **Revert to my settings**, or **Re-tune** at any time.

## 8. Suggested starting points

Daily use: start from the Balanced preset. Leave temperature and power throttling on. Keep Normal mode maximums below the top step if the phone gets warm.

Battery saving: start from Cool & Efficient. Lower the Normal mode maximums, use slower ramp-up, turn on screen-off saving, and use a lower power budget.

Gaming: start from Sustained Gaming. Select your games in the Apps tab. In Game mode, raise the minimums a little and keep throttling on, so performance stays steady instead of collapsing from heat.

Change one setting at a time and check the Monitor screen and Log. Most of the time, steadier is better than a higher maximum.

## 9. Troubleshooting

- Nothing happens: check that root access was granted and the service is running.
- Clocks do not stay pinned: another tuner or thermal module may be writing to the same files. Disable it.
- Odd power numbers: use the calibration options in the power section.
- Overlay not visible: it needs the service running, may be set to Game mode only, or may be at a screen edge. Try Reset position.
- Still stuck: share a screenshot of the Monitor screen and Log in the Telegram group.

Use at your own risk. See the warning in the README.
