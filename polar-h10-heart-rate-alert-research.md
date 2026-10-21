# Heart-rate ceiling alerts with Polar Beat + H10 on a Pixel 10 Pro XL

Research note — 20 October 2026

## Short answer

**Polar Beat cannot do this, and it is not a settings problem.** Beat has heart-rate *zones* and *voice guidance*, but no upper/lower limit alarm. Polar reserves that feature for its watches (ZoneLock / target-zone alarms). No amount of digging in Beat's menus will produce a beep at 155 bpm.

The fix is a second app that listens to the same H10 and beeps, running in the background while Beat (or Strava, or anything else) records. The H10 can hold more than one Bluetooth connection at once, so this works.

**Recommended:** install **Heart Alert** (free to try, one-off unlock, no account, no internet permission), set max 155, and run it behind Polar Beat.

---

## What you actually own

| Item | Relevant detail |
|---|---|
| **Polar H10** | Chest strap, ECG-grade. Bluetooth LE **and** ANT+. Internal memory for one session. ~€99.90. |
| **Polar Beat** | Phone-first training app. GPS, live HR, voice guidance, training targets synced from Polar Flow. Still maintained — Android v3.5.10 shipped 11 June 2025 — but feature-frozen. |
| **Pixel 10 Pro XL** | Android 16. No ANT+ radio, so everything goes over Bluetooth LE. |

## Why Polar Beat can't alert you

Polar's own feature matrix for Beat lists, for H10 owners:

- real-time heart rate
- calories
- Training benefit
- **"HR zones"**
- **"Benefit target"** with real-time voice guidance
- Fitness Test, Running Index, EnergyPointer

There is no heart-rate limit or zone alarm in that list — and none in the App Store feature copy either. The only "alerting" is voice guidance, which is periodic coaching ("your heart rate is…", benefit-target nudges), not a threshold trigger. It will not fire the moment you cross 155.

This is a long-standing, well-documented gap. Runners and cyclists have been asking for years:

- *"The Polar Beats apps doesn't provide this. I mean I can keep it on and watch the display of the heart rate but this is dangerous when cycling."* — Cyclingnews forum, 2023
- *"Bought a Polar H9 HRM and synced it with Polar Beat… I want to set an alert or alarm to go off when I go outside my target heart rate zone but can't work out how?"* — UKC forum, 2023; the thread's conclusion was that it isn't possible and the poster bought a Garmin watch

### Where the feature actually lives

The alerting logic is in Polar's **watch** firmware, not the app. Polar watches (Grit X, Pacer, Street X, Vantage) have **ZoneLock**, which locks you into a heart-rate or pace zone and notifies you when you drift out. That's the feature you're looking for — it just requires a watch.

Polar's phone-only recording path is deliberately minimal: Polar's own support article on recording without a device tells you to *start the session in Polar Beat* and let the H10's internal memory do the rest. Beat is a capture app, not a coaching alarm.

## Your options

| Option | What it does | Cost | Catch |
|---|---|---|---|
| **Heart Alert** (recommended) | Beeps/vibrates when BPM leaves a min–max range you set. Runs in the background. | Free for 5 sessions, then one-off purchase. No ads, no account, **no internet permission**. | Separate app; you run it alongside your recorder. Tiny project (~5k installs), so no support safety net. |
| **FITIV Pulse** | Records the run *and* has HR alerts + voice coaching in one app. | Free, Pro subscription for advanced metrics. | Subscription creep; Android build is watch-centric and has had H10/Galaxy Watch source-confusion bugs reported. |
| **Pulse Range Monitor & Alert** | Paid, single-purpose beep/vibrate on upper/lower limits. | $4.49 | Fewer refinements than Heart Alert (no per-interval repeat, no out-of-range delay). |
| **Polar watch + H10** | Proper ZoneLock / zone alarms, wrist display, no phone needed. | £££ (a Pacer/Unite/Ignite) | You said you'd rather not buy a watch. Also duplicates hardware you already own. |
| **Polar Flow training target in Beat** | Shows a target zone; voice guidance nudges you. | Free | Not an alarm. No beep at the threshold. Worth 10 minutes of testing, not a solution. |

## Recommended setup

1. **Update the H10 firmware** once via Polar Beat (Settings → sensor). Old firmware has BLE quirks.
2. **Install Heart Alert** from Play Store.
3. **Grant "Nearby devices"** permission (Android 12+ requires it for BLE scanning). The app talks to the strap directly — no need to pair in Android's Bluetooth settings.
4. **Configure for a 155 ceiling:**
   - Max BPM: `155`
   - Min BPM: something like `120` (or off, if you only care about the ceiling)
   - Repeat interval: **5–10 s**, not 1 s — the most common complaint is that it beeps every second once you're over, which is unbearable mid-run
   - "Out of range for" delay: **3–5 s** — stops a single bad reading or a hill spike from triggering
   - Initial delay: 60–120 s so your warm-up doesn't scream at you
   - Vibration on (useful when wind or traffic masks the beep)
5. **Stop Android killing it:** Settings → Apps → Heart Alert → App battery usage → **Unrestricted**. On Pixels, adaptive battery will otherwise reap a background BLE app mid-run.
6. **Start order:** start Heart Alert first, then Polar Beat. Both should show live HR. If Beat fails to connect, reverse the order — you're testing which app wins the connection race.
7. **Test before you trust it:** walk up stairs or do 2 minutes brisk until you cross 155 and confirm the alert fires with the screen off and the phone in a pocket.

## Gotchas

- **Two connections, not unlimited.** The H10 will serve more than one BLE client simultaneously (that's how people run an alarm app alongside MapMyRide/Strava), but don't stack three.
- **Background execution is the real failure mode**, not Bluetooth. If the alert goes silent after 20 minutes, it's Android's battery manager, not the strap.
- **Strap contact matters more than the app.** Dry electrodes at the start of a run give garbage readings and false alarms — wet the strap or start after you've warmed up.
- **Don't use this as a medical device.** Heart Alert says so explicitly, and it's right.
- **Battery:** H10 uses a CR2025. If readings drop out, replace it before blaming software.

## If you'd rather have it on your wrist

A second-hand Polar Pacer or Unite paired to the H10 gives you ZoneLock and a proper target-zone alarm without a phone at all. That's the "correct" Polar answer — it's just expensive and redundant given you already own the strap and a phone.

---

## Addendum: built one instead

Rather than install someone else's app, there's now a purpose-built one in `hr-buzz/` — ~25 KB, no dependencies, no network permission, beeps and buzzes above a limit you set and runs as a foreground service so it survives a pocketed phone. See `hr-buzz/README.md`. It solves the gap above with the same approach as Heart Alert (second BLE client alongside your recorder), without the third-party app.

Since 21 October 2026 it also records sessions itself: GPS distance/pace/route, zones, calories, voice guidance, summaries, a fitness test, and TCX/GPX export. It replaces Polar Beat rather than running next to it. The only Beat features it lacks are Polar Flow sync and Polar's proprietary scores (Running Index, Training Benefit).

---

## Sources

- [What do I get with Polar Beat? — Polar support](https://support.polar.com/en/support/What_do_I_get_with_Polar_Beat?product_id=99516) — feature matrix; HR zones and Benefit target, no limit alarm
- [Polar Beat — App Store listing](https://apps.apple.com/us/app/polar-beat-running-fitness/id555252645) — full feature copy, no alert feature
- [Polar Beat 3.5.10 for Android — release notes, 11 June 2025](https://support.polar.com/en/updates/polar-beat-version-3510-for-android)
- [Can I record my heart rate without a training device or app? — Polar support](https://support.polar.com/en/support/can_i_record_my_heart_rate_without_a_training_device_or_a_training_app) — phone-only recording starts in Beat; H10 internal memory
- [How to use ZoneLock (Grit X / Pacer / Street X / Vantage) — Polar support](https://support.polar.com/br/how-to-use-zonelock-vantage-m-vantage-v) — the alert feature is watch-side
- [Heart Alert — Google Play](https://play.google.com/store/apps/details?id=com.nikashitsa.polar_alert_android) · [source on GitHub](https://github.com/nikashitsa/heart-alert-android) · [install/rating data](https://www.appbrain.com/app/heart-alert/com.nikashitsa.polar_alert_android)
- [FITIV Pulse — Google Play](https://play.google.com/store/apps/details?id=com.fitiv.fitivapplication) · [How to set heart rate alerts in FITIV Pulse](https://support.fitiv.com/hc/en-us/articles/47384159977364-Guide-How-to-Set-Heart-Rate-Alerts-in-FITIV-Pulse)
- [Pulse Range Monitor & Alert — Google Play](https://play.google.com/store/apps/details?id=com.range.pulse)
- [Cyclingnews forum thread: HR zone alarm app for Polar H10](https://forum.cyclingnews.com/threads/looking-for-a-heart-rate-zone-alarm-app-have-a-polar-h10-monitor.38592/) — confirms Beat gap and the two-connection workflow
- [UKC forum: How to set heart rate alerts on phone?](https://www.ukclimbing.com/forums/running/how_to_set_heart_rate_alerts_on_phone-759099) — same conclusion, 2023
- [Polar H10 product page](https://www.polar.com/en/sensors/h10-heart-rate-sensor)
