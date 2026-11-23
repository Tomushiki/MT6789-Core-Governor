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
- OPPs: the list of GPU speed steps your device reports. Useful for troubleshooting.
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
- Normal Mode Launch Burst: a short boost when you open an app or when load suddenly spikes, so apps open quickly.

## 5. Protecting your phone

Two throttling systems work together. Whichever asks for the lower limit wins.

Temperature throttling
- Throttle Start Temperature: where it begins to hold the phone back.
- Full-Strength Temperature: where the reduction reaches its maximum.
- CPU Max Reduction and GPU Max Reduction: how much it can cut at full strength.
- Thermal Reaction Speed and Recovery Hysteresis: how quickly it reacts, and how far the phone must cool before it lets go.

Power throttling (controls power usage, not charging)
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
- Presets: Balanced, Cool & Efficient and Sustained Gaming are built in. You can save your own and export or import everything as a JSON backup.

## 7. Floating overlay

A small always-visible strip showing CPU, GPU, temperature and power. It is off by default and only shows while the service is running.

- Enable overlay: turns it on.
- The compact strip shows CPU, GPU, FPS, temperature and power. Tap it to cycle to the medium and detailed styles.
- Click-through: touches pass through the overlay. Use the Unlock overlay button in the notification to move it again.
- Show only in Game mode: hides it in Normal mode.
- Opacity, Compact size and Expanded size: change how it looks. The overlay always stays on screen.
- Horizontal and Vertical position: each style remembers its own position. It snaps to the screen edges when you drag it. Press Reset position to bring it back.


## 7b. Nifty features: sensor pinning

Some phones read the gyroscope slowly until an app asks for faster data. This option keeps the chosen sensors (gyroscope by default) running at a fast, fixed rate for every app, so aiming and tilt controls respond quicker. It needs no root.

- Pin sensor refresh rate: turns it on. It runs while the governor service is running.
- Rate: Max asks for the sensor's fastest rate. Lower rates use less battery.
- Sensors: pick which motion sensors to pin. At least one stays selected.
- Pause while the screen is off: releases the sensors when the screen turns off. On by default.
- The card shows the measured Hz per sensor, so you can see whether it worked. The hardware limit shown next to it is what the sensor reports as its maximum.

## 7c. Metrics tab: performance and efficiency graphs

The Metrics tab shows how the phone behaves over time, so you can see what a tuning change did.

- Power, temperature and FPS are drawn together. Each line has its own scale, written under the graph.
- FPS per watt is how many frames you get for each watt drawn. Higher is better.
- Work per watt is an estimate of how much the CPU and GPU did per watt. It is not a benchmark, so only compare it on this phone, and use it to compare settings against each other.
- Drag on a graph to read the values at that moment. The same moment is marked on all graphs. Double-tap to clear.
- Live 5m, 15m and 30m choose how far back the live graphs go. History is kept only while the app is running.
- Game sessions are saved automatically when a game from the Apps tab is in the foreground for at least 20 seconds. Tap a saved session to open it, and use the bin icon to delete it. The newest 40 are kept.
- FPS is only measured while the overlay is turned on (any style), so turn the overlay on if the FPS graphs are empty.
- Ratios stay blank when there is no FPS reading or the power reading is near zero. If power numbers look wrong, use the calibration options in the power section.

A good way to use it: play the same game for a few minutes with one setting, change one thing in Tuning, play again, and compare the two saved sessions (FPS/W, 5% low, peak temperature).

## 7d. Energy tab: learned power model and efficiency governor

CoreGovernor can passively learn how much power each CPU cluster and the GPU actually draw on your specific phone across frequencies, loads, and apps.

- **Learn power model (on by default):** records ~1 Hz samples while the screen is on and the phone is discharging (skipping charging and sudden spikes). High confidence across most frequencies and apps takes several hours of everyday use and gaming.
- **Efficiency governor (off by default):** once enabled, it uses the learned power/performance model to pick lower-energy frequencies inside your allowed min/max bounds and thermal/power caps whenever confidence is above your chosen threshold. If it sees an FPS drop or overload, it backs off immediately and falls back to standard scaling.
- **Modes:** *Balanced* targets best work per watt, *Aggressive saver* leans toward lower power draw, and *Performance-first* guards responsiveness and frame rate.
- **Export / Reset:** copy `power_model.json` to the clipboard or reset the learned model at any time.

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
