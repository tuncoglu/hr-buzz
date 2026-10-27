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
