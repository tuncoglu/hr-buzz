# HR Buzz

A ~120 KB Android app for a Bluetooth heart-rate strap (Polar H10, H9, Verity Sense, Garmin, Wahoo…) that does what Polar Beat does: records sessions with GPS, heart rate zones, calories and voice guidance. It also does what Beat can't: beeps and buzzes when your heart rate goes over (or under) a limit you set. One app instead of two. No account, no cloud, no ads, no analytics. The only network use is the optional coach (below), which is off until you set it up. Move minutes from Health Connect are optional too.

| Home | Training | Summary | Route & splits |
|---|---|---|---|
| ![](screenshot-home.png) | ![](screenshot-training.png) | ![](screenshot-summary.png) | ![](screenshot-route.png) |

## Polar Beat parity

What Beat 3.5.13 does, and where it is here:

| Polar Beat | HR Buzz |
|---|---|
| Sport picker + Start | Running, Walking, Cycling, Hiking (GPS) and Gym, Other (no GPS) |
| Live HR, zone, duration, distance, pace, calories | Training screen: HR in zone colour with % of HRmax, zone pointer, duration, distance, current and average pace, calories, average HR |
| Voice guidance | Every km, or every 1 / 5 / 10 min: time, distance, pace, HR and zone. Ducks music |
| Walk break (running) | a button on the training screen: heart rate and GPS keep recording, the part is labelled for the coach, the low alert is off until you tap Back to running |
| Secure pause button | "Hold to pause or finish" (on by default) |
| Session summary: duration, HR avg/max, calories, distance, pace avg/max | Same, plus training load |
| HR and pace graphs, map | HR graph over zone bands, pace graph, route drawn to scale and coloured by zone (no map tiles: that would need the network) |
| How do you feel + training notes | Same |
| Latest sessions | Home screen list, newest first |
| Fitness Test (OwnIndex) | Fitness test: 5 min lying still → resting HR, HRV (RMSSD) and a VO2max estimate, with history |
| HR sensor with battery | Strap name + battery %, remembered strap |
| Sync to Polar Flow | **Not done.** I couldn't find any way for a third-party app to upload to Flow. Export TCX/GPX instead (Strava, Garmin Connect, TrainingPeaks, Runalyze…) |
| Running Index, Training Benefit, EnergyPointer, fat-burn % | **Not copied**: they are Polar's own unpublished algorithms. Replaced by transparent equivalents: time in zones, Edwards TRIMP training load, and an effort label derived from where the time went |
| Save HR in the H10's memory | Not done: it needs Polar's proprietary protocol |

## Beyond Beat

- **Heart rate alarm**, the reason this app exists. Above the limit for 3 readings in a row (so one glitch doesn't set it off): beep and double buzz, repeated every 5 s, with 3 bpm hysteresis so it doesn't chatter. Works with or without a session running. Readings the strap flags as "no skin contact" are ignored. The default limit is 85% of your max heart rate.
- **Lower limit** (ZoneLock-style band). Only during a session, and only arms once you've reached it, so a warm-up doesn't set it off. Low tone and triple buzz.
- **Spoken alerts** ("Heart rate 158. Too high, ease off.") when voice guidance is on, at most every 30 s. The beeps keep going.
- **Lost-signal alarm.** If heart rate stops for 10 s after it had been coming in, a warbling tone and one long buzz (different from "too high") repeat every 5 s, with "Heart rate signal lost" spoken if voice is on and a chime when it's back. During a session it keeps going until the signal returns; outside one it stops after 30 s, so taking the strap off doesn't beep forever. The phone is kept awake while a session runs, because with the screen off Android otherwise delays the alarm timers.
- **Remembers your strap** and ignores every other one, so it never locks onto someone else's at the gym or a race. Forget it in Settings.
- **Skin contact warning** from the strap's contact flag ("wet the electrodes").
- **Strap battery** shown on the home screen, and on the training screen when ≤ 20%.
- **Auto-connect** when the app opens (like Beat), unless you last pressed Disconnect.
- **Km splits** with average HR per km.
- **Crash-proof recording.** Samples go to disk every second. If Android kills the app mid-run, the session is rebuilt from disk the next time the app starts.
- **Lock screen.** The training screen shows over the lock screen, and the notification (silent, but not "low", which Pixel hides there) shows live stats with Pause/Resume and Finish & save. Finish & save asks you to unlock first.
- **RR intervals** are recorded (in the session CSV) for later HRV analysis.
- **Battery prompt.** A one-tap request for unrestricted battery use, so Pixel battery management doesn't kill long runs.

## Coach (optional, DeepSeek)

An LLM coach built to take as little effort as possible. It talks first, and you mostly tap:

- **Today's plan on the home screen.** "Easy run, 35 min", one line on why, its steps as zone-coloured bars, and the alerts it will set. **Start this run** sets everything up; **Not today / Shorter / Harder** re-plan in one tap. Rest days say so.
- **Guided workouts, offline.** Each step sets that step's alert limits for the session (never above your own "alert above" ceiling), chimes, and is spoken aloud, even with voice guidance off. The training screen shows the step, time left, the target band and % in target. The network is never needed mid-run.
- **Debrief** on the summary right after Finish: two or three lines with one concrete comparison to your history, and what's next. Older sessions and ones under 5 minutes get it on request, so browsing and false starts cost nothing.
- **Ask.** Suggestion chips, typing or speech. Answers that change today come back as an updated plan with **Start now** and **Undo**. "I can do 5 days now" updates the settings.
- **Morning notification** at your chosen time with today's plan and a Start button (a JobScheduler job, so it waits for a network and survives reboots). Sundays open with a one-line recap.
- **Setup is four taps** (goal, days a week, minutes, anything to work around), plus an optional target and date. It already knows your zones, profile and last 12 weeks.
- **Talk test.** The summary asks whether you could talk in full sentences, short phrases or a few words. The debrief waits for the answer, and the coach checks your zones against it.
- **Morning check-in.** Two taps on the home card (how you slept, how your digestion is) and a link to the resting test while you haven't done one today; the morning notification has a **Resting test first** button. A test that shows you're tired softens today's plan on the phone, without calling the model.
- **Progress worked out on the phone.** Per week: km, median pace, heart rate and metres per heartbeat from the km splits, run pace at the same heart rate, and heart-rate drift within each run. The model reads trends instead of doing sums.
- **Max HR check.** The coach is told where your max HR came from, the age estimate and your highest recorded peak, and which long sessions averaged 88% of max or more. A habit of those usually means the max is set too low; the coach raises it once and asks how they felt.
- **Memory.** Lasting things you tell it in the chat (a preference, an injury, what training does for you) are kept, listed in the coach screen, and can be forgotten one by one. The coach also sees the last two weeks of what was planned, what you changed and what you did, and what it told you after each session.

The model proposes; the phone decides. `CoachLogic.guard` checks every plan before it can run:

- It must be a valid shape. Sports and zones are clamped, and a session can be at most 4 hours (or your usual length while there are no recent sessions to build on).
- Readiness from your morning fitness tests caps it: resting HR up ≥ 8 bpm or HRV down 25% makes it easy, smaller rises soften the hard parts. Without a recent test, your watch's resting HR and HRV from Health Connect are compared with its own earlier days.
- There is no weekly volume cap; the coach judges a heavy week by recovery and intensity mix, not a percentage rise.
- Any mention of chest pain or tightness, arm or jaw pain, fainting, dizziness, palpitations, an irregular heartbeat or unusual breathlessness, in the chat or in a session's notes, never reaches the model. The phone answers "stop and see a doctor" and makes today a rest day (for 48 hours after a note like that).
- Step limits allow 5 bpm over the top of the zone. Zone-1-only steps (walks, cool-downs) keep only your own limit, and when a step lowers the limit you get 90 s to come down before it beeps.

Privacy, as shown in Coach settings:

- **Sent:** session totals (date, sport, time, distance, average/max HR, minutes per zone, km splits, load, feeling, talk test, notes) and trends computed from them, fitness-test resting HR/HRV/VO2max, check-ins, the plan's two-week history, what the coach keeps in mind, Health Connect daily totals if on, and age, sex, weight, max HR (and its source) and your setup answers.
- **Never sent:** GPS or location, second-by-second HR, RR data, your name.
- **Your key:** stored encrypted with an Android keystore key and only sent to `api.deepseek.com` over HTTPS (cleartext is disabled app-wide).
- **Controls:** *See the last message sent* shows the exact request. *Delete everything the coach remembers* wipes the plan and its history, the conversation, what it keeps in mind, the check-ins, the request log and the debriefs.

Model: `deepseek-flash` via `https://api.deepseek.com/chat/completions`, with JSON output mode and thinking on (high effort), so plans and debriefs are reasoned through. The model is called only at four moments: the morning plan, after Finish, Sunday, and when you ask. You need your own DeepSeek API key (platform.deepseek.com).

## Move minutes (optional, Health Connect)

Settings → Activity → **Move minutes** reads your steps and workouts from Health Connect, where Google Fit, Fitbit, Pixel Watch and most fitness apps save them, and counts the minutes you were moving. It shows on the home screen (today, and the last 7 days against the 150 min a week guideline) and the coach is told the daily totals, so it knows how sedentary or active you are between sessions.

- **Not Google Fit.** The Fit APIs are being shut down and have no replacement for apps without a server; Health Connect is the on-device successor. Android 14 or newer (it is part of the system there, so no library is added).
- **Our own count.** Fit's "Move Minutes" has no Health Connect equivalent, so a minute counts when it has at least 50 steps or falls inside a workout (HR Buzz's own sessions count too). Steps written by two devices for the same walk are not added together. It will be close to what Fit showed, not identical.
- **Sleep, distance and heart data.** If you also allow them, the last 14 days of sleep (hours, when you fell asleep and woke), steps, km, resting heart rate and HRV are read for the coach. Steps and workouts are all move minutes need; the rest is optional (Settings → Activity → *Allow sleep, distance and heart data*).
- **Read-only.** Nothing is written to Health Connect. Health Connect only allows reading while the app is open, so the data is refreshed when you open HR Buzz and the morning plan uses the last read.
- **Privacy.** On the phone, plus, if the coach is on, the daily totals sent to DeepSeek (never the records, times or workouts behind them). Turning it off forgets them.

## Zones and calories

Zones are Polar's default five: 50–60–70–80–90–100% of HRmax. HRmax is the value you enter in Settings, or Auto: 220 − age (Polar's default), raised to the highest heart rate you've held for 10 s in a session, once you agree. A new peak is never used without asking, because a strap glitch can read high. Zones are only as good as that number, so enter your real maximum if you know it. If you take beta-blockers or have a heart condition, use the limit your doctor gave you; the first-run screen asks for it.

Calories use the heart-rate formula of Keytel et al. (2005), so set age, sex and weight (the app asks on first run). Keytel comes from exercise and runs high at easy heart rates, so below 45% of HRmax a light-activity estimate (2.5 MET) is used instead, blending into Keytel by 60%. The fitness test's VO2max is Uth et al. (2004), 15.3 × HRmax / resting HR. It's an estimate, not a lab test.

## Verified on device

Pixel 10 Pro XL (Android 17) with a real Polar H10:

| Check | Result |
|---|---|
| Connect, live HR, strap name, battery | 68 bpm, "Polar H10 8C02C525", 100% |
| Auto-connect on app launch | live within ~5 s |
| Over-limit alarm, home + training screen | red number / red HR card, tone + buzz, spoken alert |
| Session record, pause, resume, finish (long press) | saved, summary opens |
| Voice guidance every 1 min and every km | spoke on the minute and at 1.00 km |
| GPS distance, pace, km split, route (fed by a test GPS provider at 5 m/s) | 1.36 km, split 3:19 /km (5 m/s = 3:20), route drawn |
| App killed mid-session (reinstall) | session recovered from disk |
| Share TCX | share sheet with `hrbuzz_2026-10-21_1001_running.tcx` |
| Fitness test start / cancel | countdown runs, cancel resets |
| Lost signal in a Gym session, unplugged, screen off, strap taken off | 30 of 30 alerts on time (5.1 s apart) over 2.5 min, "back" chime when the strap went back on. Before the wake lock: 23 of ~44, gaps up to 19 s |
| Lost signal outside a session | 6 alerts over 30 s, then quiet; wake lock held only for those 30 s |
| Strap connected but not worn | no alarm (previously a buzz every 14 s) |
| Alarm vibration strength | full (alarm usage); previously 51%, scaled as media |

Not yet tested outdoors with real GPS; the distance filter (accuracy ≤ 25 m, jumps > 30 m/s dropped, steps smaller than the fix noise held back) is tuned from the usual phone-GPS behaviour.

## Install

The prebuilt APK is `hr-buzz.apk` (not committed; `./build.sh` makes it).

```bash
adb install -r hr-buzz.apk
```

On first launch it asks for **Nearby devices** (Bluetooth) and **Notifications**. **Location** is asked for the first time you start a GPS sport. Without it, sessions record heart rate only.

## Use

1. Put the strap on (wet the electrodes).
2. Open HR Buzz. It finds the strap by itself.
3. Set **Alert above** (and optionally **Alert below**).
4. Pick a sport, press **Start**. Pocket the phone.
5. Hold **Finish** (or tap **Finish & save** in the notification). The summary opens, where you can rate it, add notes or share TCX/GPX.

The first time, open **Settings** and enter age, sex, weight and (ideally) your real max HR.

You can still run Polar Beat or Strava alongside it: an H10 with "2 Bluetooth connections" enabled serves both.

## Build

No Gradle, no Android Studio, no AndroidX. The build is a shell script over the SDK tools:

```bash
./build.sh          # -> hr-buzz.apk
./test.sh           # plain-JVM tests: decode, alarm, zones, calories, stats, splits, export
```

Requires `ANDROID_HOME` (defaults to `/opt/android-sdk`) and a JDK (`JAVA_HOME`, defaults to `~/.cache/polar-hr-toolchain/jdk`). compileSdk 36, minSdk 31, targetSdk 34.

## Layout

| Path | What |
|---|---|
| `src/.../HrLogic.java` | Heart rate payload decode (HR, contact, RR) and the high/low alarm state machine. Pure Java, tested |
| `src/.../Training.java` | Zones, Keytel calories, haversine, session stats, splits, TRIMP, effort label, RMSSD, formatting, CSV sample format. Pure Java, tested |
| `src/.../Move.java` | Move minutes from step intervals and workouts, per local day, and the sedentary / active label. Pure Java, tested |
| `src/.../HealthSync.java` | Reads steps and workouts from Health Connect (framework API) and caches the week |
| `src/.../Export.java` | TCX and GPX writers. Pure Java, tested |
| `src/.../HrManager.java` | BLE scan/connect/notify, strap pinning, battery, tone + vibration |
| `src/.../Recorder.java` | Session recording: 1 Hz samples to disk, GPS distance filter, voice guidance |
| `src/.../SessionStore.java` | Sessions on disk (`files/sessions/<id>.csv` + `.json`), crash recovery, export files |
| `src/.../LiveService.java` | Foreground service (connected device + location) and the live notification |
| `src/.../ExportProvider.java` | Minimal read-only content provider for sharing exports |
| `src/.../MainActivity.java` | Home: strap, live HR, alert limits, sport, Start, latest sessions |
| `src/.../TrainingActivity.java` | Live training screen |
| `src/.../SummaryActivity.java` | Session summary, charts, route, splits, notes, export, delete |
| `src/.../SettingsActivity.java` | Profile, zones, voice, training screen, alarm test, strap, battery |
| `src/.../FitnessTestActivity.java` | Resting HR / HRV / VO2max test |
| `src/.../LineChartView.java`, `ZoneBarsView.java`, `ZoneStripView.java`, `RouteView.java` | Hand-drawn charts |
| `src/.../Json.java` | Small JSON reader/writer, so the coach logic runs on a plain JVM |
| `src/.../CoachLogic.java` | Coach brain on the phone: what the model sees, prompts, reading answers, plan checks, readiness, red flags, step limits. Pure Java, tested |
| `src/.../DeepSeek.java` | Chat-completions client (java.net, JSON mode, retry on empty). Tested against a local stub server |
| `src/.../Coach.java` | Coach on Android: settings, encrypted key, plan, conversation, morning job scheduling |
| `src/.../CoachJob.java` | Morning plan job + notification |
| `src/.../CoachActivity.java`, `CoachSetupActivity.java` | Coach screen (week, next days, conversation) and setup/privacy |
| `test/...` | `HrLogicTest`, `TrainingTest`, `CoachTest`, `ImportTest`, `MoveTest` |

## Known limits

- An H10 that isn't being worn doesn't advertise, so the app will sit on "Searching…". Wet the strap.
- The route has no map underneath. Share the GPX to see it on one.
- Calories, zones and VO2max are only as good as the profile in Settings.
- It's a training aid, not a medical device.
