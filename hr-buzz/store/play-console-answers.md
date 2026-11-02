# Play Console: answers drafted from the code (check each against the form's current wording)

## App content
- **Privacy policy:** host `privacy-policy.md` at a public URL (GitHub Pages is enough) and use that URL both in the listing and as the page Health Connect links to.
- **App access:** nothing is locked behind a login. The Polar strap is needed for live heart rate; reviewers can still open every screen.
- **Ads:** none. **Category:** Health & Fitness. **Target audience:** 18+ is simplest.
- **Health apps declaration:** it is a fitness and heart-rate training app that is not a medical device; it records health data and offers coaching. Say so, and add the disclaimer from the policy to the listing description.

## Data safety
Data collected / shared, all with "optional" where the user turns it on:
- **Health info and Fitness info** (heart rate, workouts, steps, sleep): collected. Shared with a third party (the AI service whose key the person pastes: OpenAI, Anthropic, Google or DeepSeek) only when the coach is turned on; purpose: app functionality / personalisation. Encrypted in transit: yes. Users can request deletion: yes (in-app delete for the coach; uninstall for everything).
- **Precise location:** collected for outdoor sessions; **not shared**, stays on device.
- **Personal info (age, sex, weight):** as above, shared with that AI service only when the coach is on.
- No data is shared for advertising or analytics; no data is sold.
- Because the data stays on the device unless the coach is on, "collected" for the on-device items depends on how Play defines it: data processed only on device and never sent need not be declared as collected. Read the form's help text for that case.

## AI-generated content policy
The coach writes text with a third-party AI service. In-app reporting is built in: "Report this answer" under every chat reply and debrief saves a report on the phone and offers an email to privacy@sourdoughcalculator.uk (the person presses Send themselves). Mention it in the Console's AI-generated content answer. Safety: chest-pain, fainting and similar messages are answered on the phone and never sent to the model; the phone also checks every plan before it can run.

## Health Connect declaration (one use case per type)
All reads, no writes:
- `READ_STEPS`, `READ_EXERCISE`: move minutes on the home screen and activity context for the coach.
- `READ_SLEEP`, `READ_DISTANCE`, `READ_RESTING_HEART_RATE`, `READ_HEART_RATE_VARIABILITY`: optional "Allow sleep, distance and heart data"; only used to judge recovery and tell the coach about your last 14 days. These four are the likeliest to be questioned: if you do not want to defend them, remove them from the manifest and `HealthSync.PERMISSIONS`.

## Foreground services (phone)
- `connectedDevice`: keeps the Bluetooth link to the heart-rate strap alive while the screen is off during a session or alert monitoring.
- `location`: records distance, pace and route while the screen is off during a GPS sport.
- Video: record a short screen capture of starting a run, locking the phone, and the notification staying up.

## Restricted permission: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
Core function: a heart-rate alarm and session recording that must keep running for hours with the screen off. The app asks once, with a one-tap prompt and a fallback to the settings page. Before sending the form, test a long session with the permission denied and write what actually breaks (the README only measured delays before the wake lock was added, so it is not evidence for this). If you cannot show a failure, or the review does not accept it, remove the permission and keep only the fallback.

## Listing assets
512x512 icon, 1024x500 feature graphic, 2-8 phone screenshots (the four in the repo are a start; check their size), short description (80 characters), full description (4000), contact email.

## On-device coach (in the Play build from 2.4)
From 2.4 the Play build is the Gradle one (`ondevice/build.sh release`): it adds "Use the coach on this phone" (Gemma 4 through Android AICore, developer preview) and Google's ML Kit GenAI library. Phones without the model see the option answer "isn't available on this phone". Answer the forms with this in mind:
- **Data safety, new rows: none.** ML Kit queues usage reports for Google (package, version, locale, a per-install id, sizes and codes: see https://developers.google.com/ml-kit/android-data-disclosure) and would upload them through Google Data Transport. The Gradle build removes Data Transport's two upload entry points from the manifest (`JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver`, in `ondevice/build.sh`), so nothing is sent. Checked on the Pixel 10 Pro XL (2026-11-02, test app under another package, debug build): without the removal an upload job (`firebaselogging.googleapis.com`, transport `FIREBASE_ML_SDK`, run after at least a day, idle, unmetered) was queued within 20 s of the first model check, with 5 then 6 events in `databases/com.google.android.datatransport.events` (no text: package, SDK version `1.0.0-beta5`, locales, install UUID, and for a generation the settings and sizes); with the removal, `TransportRuntime` logs "Error scheduling event ... non-existent component", no job appears, nothing is queued further, the app never records network use, and plans and free-form chat answers still work. Play counts data as collected when it leaves the device, so there is nothing to declare for it. If a reviewer asks, this is the answer. **Not checked:** actual traffic on the wire (no proxy); the proof is the missing job, the missing network counters and the code path.
- **Re-check on every library update.** `ondevice/build.sh release` refuses to finish if a Data Transport scheduler shows up in the release manifest. A new `genai-prompt` version could send in another way (for example through WorkManager), so after bumping it repeat the check: install a debug build under a spare package name beside the real app, ask the coach a question, and look at `adb shell dumpsys jobscheduler` for jobs from that package, and at `dumpsys netstats detail` for its uid.
- **Data safety, existing rows:** with the on-device model chosen, health and profile data goes to the on-device model only, never off the phone, so "shared with the AI service" still applies only to the key services.
- **AICore itself** is a system component of the phone, not part of the app.
- **AI-generated content:** add that the coach can run on Google's Gemma model on the phone as well as on the user's own service; reporting works the same.
- **Permissions:** the merged manifest adds only `com.google.android.apps.aicore.service.BIND_SERVICE` (plus the library's own internal receiver permission); no new dangerous permission.
- **Size:** the download grows from about 200 KB to about 4 MB (bundle) / 11 MB (APK); minSdk 31 and targetSdk 37 are unchanged.
- **Upload:** `ondevice/build.sh release` writes `hr-buzz-ondevice-release.aab`, signed with the same upload key as before. Version codes must keep rising (2.4 is code 6).
- **Policy:** `store/privacy-policy.md` has the new sections; redeploy it (`python3 store/deploy-privacy.py deploy`) before the release goes to review.
