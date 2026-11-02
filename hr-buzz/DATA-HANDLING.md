# How HR Buzz handles your data, and how to check

HR Buzz records heart rate, GPS routes, sleep and activity from Health Connect, and your age, sex
and weight. This page says where each of those goes, and for every claim points at the code or a
command that shows it. If the code and this page disagree, the code wins and this page is a bug:
see [SECURITY.md](../SECURITY.md). The plain-language version is the
[privacy policy](https://hrbuzz.sourdoughcalculator.uk/privacy/).

## The short version

- **Everything stays on the phone** unless you turn on the optional coach and pick an online AI
  service. Then a summary (not the raw recording) goes to the service whose key you pasted.
- **One file opens network connections:** `src/com/enftc/hrbuzz/Llm.java`, the coach's client.
- **No account, no ads, no analytics, no crash reporting, no tracking libraries.**
- **No backup copy** is taken by Android (`android:allowBackup="false"`), so your data is not
  copied to Google Drive or to a new phone automatically.
- The **on-device coach** (Gemma through Android AICore) sends nothing to anyone.

## Where each kind of data lives and goes

| Data | Stored | Leaves the phone? |
|---|---|---|
| Heart rate and beat-to-beat (RR) intervals | `files/sessions/<id>.csv`, one row a second (`SessionStore.java`, `Recorder.java`) | Never sent to the coach. Leaves only if you export a session and share it |
| GPS route | Same session file | Never sent to the coach. Leaves only if you export a session (GPX/TCX) and share it |
| Session summary, notes, how it felt | `files/sessions/<id>.json` | A summary (totals, per-km splits, zone minutes, load, feel, notes) goes to the coach's AI service if you use one; see below |
| Age, sex, weight, max heart rate | App settings (`Prefs.java`) | Sent to the coach's AI service if you use one |
| Health Connect steps, workouts, sleep, resting HR, HRV | Read on demand (`HealthSync.java`); daily totals kept in settings | Daily totals go to the coach's AI service if you turned Health Connect on and use one. Nothing is ever written back |
| Fitness tests (resting HR, HRV, VO2max) | App settings (`FitnessTestSession.java`) | Results go to the coach's AI service if you use one |
| Your API key | Encrypted with AES-GCM under a key held in the Android Keystore (`Coach.java`) | Only to the service it belongs to |
| Coach plans, chat, reports of bad answers | App settings, in the app's private storage | Your questions go to the coach's AI service; reports stay local until you press Send in your own email app (`ReportUi.java`) |

All of it is in the app's private storage, which other apps cannot read. It is not additionally
encrypted by the app except the API key (Android encrypts phone storage as a whole).

## The coach and the network

- Only `Llm.java` opens connections, to the host of the service you chose and nothing else
  (`Provider.java` lists them: api.openai.com, api.anthropic.com,
  generativelanguage.googleapis.com, api.deepseek.com). Check:
  `grep -rln "HttpURLConnection\|openConnection" src` lists that one file.
- HTTPS only: `res/xml/network_security.xml` forbids cleartext and trusts system certificates.
- What it sends is built in `CoachLogic.java` (look for the `put("...")` calls). It has no
  coordinates and no RR data. Notes that mention a symptom are withheld, and messages about chest
  pain, fainting or similar are answered on the phone and never sent (`redFlag` in `CoachLogic.java`).
- The exact last request is shown in the app: Coach settings → **See the last message sent**.
- Those services handle what you send under their own terms. HR Buzz cannot control that, and the
  privacy policy says so.

## The on-device coach (Play build)

- `OnDevice.java` and `ondevice/app/src/main/kotlin/com/enftc/hrbuzz/MlKitEngine.kt` call
  Google's Gemma model through Android AICore. The model runs on the phone; nothing in this path
  uses the network (it does not go through `Llm.java`).
- Google's ML Kit library, which the Play build uses to reach AICore, by default queues small usage
  reports for Google (package name, version, locale, a random install id, message sizes: never the
  text). It uploads them through Google Data Transport. The build **removes the two components
  that do the uploading** (`JobInfoSchedulerService` and `AlarmManagerSchedulerBroadcastReceiver`,
  see the comment in `ondevice/build.sh`), so they are not sent, and the release step fails if
  they come back. Check:
  `aapt2 dump xmltree --file AndroidManifest.xml hr-buzz-ondevice-release.apk | grep -i scheduler`
  prints nothing.
- How this was checked, and what was not: on a Pixel 10 Pro XL, without the removal an upload job
  was queued within seconds; with it no job exists, the app recorded no network use, and plans and
  chat still worked. Traffic on the wire was not captured. The steps to repeat it are in
  `store/play-console-answers.md`. Redo them after any update of `genai-prompt`.
- The model itself is downloaded and run by Android's AICore, which is part of the phone's system,
  not of this app.

## Permissions, and why

| Permission | For |
|---|---|
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | Finding and connecting to your strap (scan is flagged `neverForLocation`) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS distance, pace and route for outdoor sports; asked only when you start one |
| `FOREGROUND_SERVICE`, `..._CONNECTED_DEVICE`, `..._LOCATION`, `WAKE_LOCK` | Keeping the strap link and GPS alive with the screen off |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Stopping Android from killing a long session or alarm |
| `POST_NOTIFICATIONS`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED` | Alerts and buzzes; the morning plan notification, rescheduled after a reboot |
| `INTERNET`, `ACCESS_NETWORK_STATE` | The online coach only (`Llm.java`) |
| `health.READ_STEPS`, `READ_EXERCISE`, `READ_SLEEP`, `READ_DISTANCE`, `READ_RESTING_HEART_RATE`, `READ_HEART_RATE_VARIABILITY` | Optional Health Connect reads for move minutes and the coach; read-only; off until you allow them |
| `com.google.android.apps.aicore.service.BIND_SERVICE` | Reaching AICore (Play build) |

Check against the real build: `aapt2 dump badging <apk> | grep uses-permission`.

## What is logged

Logcat lines (search the source for `Log.`) carry operational facts: a session id, a plan title,
timings, byte counts, HTTP status. They do not carry heart-rate values, coordinates, your key or
the text of any request or answer. The coach's request trace is lengths and timings only
(`Llm.note`).

## What you can delete

- Coach settings → **Delete everything the coach remembers**: plans, chat, notes, check-ins,
  saved reports and the request log.
- Uninstalling deletes everything the app stored. Data already sent to an AI service you chose is
  outside the app's reach; contact that service.

## Not verified

- Traffic on the wire (the claims above rest on the code, the manifest and Android's job and
  network counters).
- What an AI service you choose does with what you send.
- The behaviour of Android AICore itself.
- Google Play may collect crash and performance data from phones that share it (Android vitals). That is Play's own reporting, not something this app does.

If you find something this page gets wrong, please say so.
