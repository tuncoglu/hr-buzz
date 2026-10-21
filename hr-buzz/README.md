# HR Buzz

A ~25 KB Android app that connects to a Bluetooth heart-rate strap (Polar H10, H9, Verity Sense, Garmin, Wahoo…) and beeps + buzzes when your heart rate goes over a limit you set. Nothing else — no account, no cloud, no ads, no analytics, and no network permission at all.

Built because Polar Beat has no heart-rate alarm: the alert feature lives in Polar's watches (ZoneLock), not the app.

## Behaviour

- Connects to any device advertising the standard Bluetooth SIG heart rate service (`0x180D`), which the H10 does.
- Displays live BPM; paints the number red when you're over.
- **Over the limit** → beep + double buzz, repeated every 5 s while you stay over.
- **Back under** → short confirmation beep, once.
- **Hysteresis of 3 bpm:** if your limit is 155, you have to drop to 152 before it considers you "recovered". Stops the alarm chattering when you sit right on the line.
- Runs as a foreground service, so the link survives the screen going off and the phone being in a pocket. The live BPM also shows in the notification.
- Reconnects by itself if the strap drops out.
- **Dropout warning:** if the link to the strap drops while you are running, it beeps and buzzes every 5 s ("Signal lost") until it reconnects or you press Stop.
- **No-data warning:** if the strap stops sending readings for 10 s (fell off, out of range) it beeps and buzzes every 5 s with "No heart rate data".
- **Stop** button on the notification disconnects and shuts the service down without opening the app.

## Verified on device

Tested against a real Polar H10 on a Pixel 10 Pro XL (Android 17):

| Check | Result |
|---|---|
| Live heart rate from the strap | 74–77 bpm, updating |
| Alarm on crossing the limit | vibration + tone, UI goes red |
| Repeat while still over | 4 alerts, 5.7 s apart (matches the 5 s interval) |
| Alarm with app in background | continued after pressing Home |
| Alarm with screen off / locked | continued while display dark |
| Notification | live `77 bpm — over 70` / `Ease off` |
| Auto-reconnect after strap woke | connected unprompted |

The one gotcha worth knowing: **an H10 that isn't being worn doesn't advertise**, so the app will sit on "Searching…" indefinitely. That's the strap's power saving, not a bug — Polar Beat behaves the same way.

See `screenshot-live.png` and `screenshot-alarm.png`.

## Install

The prebuilt APK is `hr-buzz.apk`.

Over USB:

```bash
adb install -r hr-buzz.apk
```

Or copy the file to the phone and tap it (you'll need to allow "install unknown apps" for your file manager).

On first launch it asks for **Nearby devices** (Bluetooth) and **Notifications**. Both are needed: the first for the strap, the second for the ongoing notification.

## Use

1. Put the strap on (wet the electrodes — dry contact gives junk readings and false alarms).
2. Open HR Buzz, set the limit (default 155).
3. Tap **Connect**, pick nothing — it finds the strap itself.
4. Start your usual running app if you want a recording; both can hold a connection to the H10 at once.
5. Pocket the phone.

**Stop the alarm** with the **Stop** button on the notification, or open the app and tap **Disconnect**.

## Battery

The app holds a foreground service while connected. On a Pixel, check **Settings → Apps → HR Buzz → App battery usage → Unrestricted** if you find Android is reaping it during long runs. Foreground services are usually left alone, but Pixels are aggressive.

## Build

No Gradle, no Android Studio, no AndroidX. The build is a shell script over the SDK tools:

```bash
./build.sh          # -> hr-buzz.apk
./test.sh           # plain-JVM tests for the decode + alarm logic
```

Requires `ANDROID_HOME` (defaults to `/opt/android-sdk`) and a JDK (`JAVA_HOME`, defaults to `~/.cache/polar-hr-toolchain/jdk`). compileSdk 36, minSdk 31, targetSdk 34.

The build is: `aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zipalign` → `apksigner`, signed with a throwaway debug keystore in `build/`.

## Layout

| Path | What |
|---|---|
| `src/com/emre/hrbuzz/HrLogic.java` | Pure decode + alarm state machine (no Android imports, unit tested) |
| `src/com/emre/hrbuzz/HrManager.java` | BLE scan/connect/notify, wiring, tone + vibration |
| `src/com/emre/hrbuzz/MainActivity.java` | One screen: BPM, limit, connect toggle, permissions |
| `src/com/emre/hrbuzz/LiveService.java` | Foreground service + notification |
| `test/com/emre/hrbuzz/HrLogicTest.java` | 21 checks covering payload parsing and alarm edge cases |

## Known limits

- Only connects to straps that **advertise** the heart rate service. The H10 does; a few cheap straps don't, and would need an unfiltered scan.
- One strap per run. No recording, no history, no export — that's what Polar Beat or Strava are for.
- The alarm tone plays on the alarm stream, so it follows your alarm volume (not media volume) and is audible with media muted.
- It's a training aid, not a medical device.
