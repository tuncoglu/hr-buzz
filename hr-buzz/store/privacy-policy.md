# HR Buzz privacy policy

_Last updated: 27 October 2026. Published by ENFTC. Contact: privacy@sourdoughcalculator.uk._

HR Buzz ("the app"; "we" below means ENFTC, its publisher) is a heart-rate training app for Bluetooth chest straps. It has no account, no ads and no analytics, and it does not send anything to us. Everything it records stays on your phone unless you turn on the optional features described below.

## What the app handles

| Data | Why | Where it goes |
|---|---|---|
| Heart rate and beat-to-beat (RR) intervals from your strap | Live display, alerts, zones, calories, session history | Stays on your phone |
| Location (GPS) | Distance, pace and route of outdoor sessions. Asked for only when you start a GPS sport | Stays on your phone. Never sent to the coach |
| Your profile: age, sex, weight, max heart rate | Zones and calorie estimates | Stays on your phone; sent to the coach if you turn it on (see below) |
| Session history, notes, how you felt | Summaries, progress, export | Stays on your phone; totals are sent to the coach if you turn it on |
| Steps, workouts, and optionally sleep, distance, resting heart rate and HRV from Health Connect | "Move minutes", and context for the coach. Off by default; read-only | Stays on your phone; daily totals are sent to the coach if you turn it on |
| Bluetooth ("Nearby devices") | Finding and connecting to your strap | Stays on your phone |

## Optional coach (off by default)

If you set up the coach, HR Buzz sends training information to the third-party AI service **you choose** by pasting your own API key: **OpenAI** (ChatGPT, `api.openai.com`), **Anthropic** (Claude, `api.anthropic.com`), **Google** (Gemini, `generativelanguage.googleapis.com`) or **DeepSeek** (`api.deepseek.com`). It uses it to write plans and post-session comments. HR Buzz recognises which service a key belongs to, asks that service which models the key can use, and picks one; it contacts only that service. This is the only time the app uses the network.

- **Sent:** session totals (date, sport, duration, distance, average and maximum heart rate, minutes per zone, kilometre splits, training load, how you felt, talk test, notes), trends calculated from them, resting-test results, check-ins, the coach's plan history and what it keeps in mind, Health Connect daily totals if you turned them on, and your age, sex, weight and max heart rate.
- **Never sent:** GPS or location, second-by-second heart rate, RR data, your name.
- You can see the exact last request in Coach settings ("See the last message sent").
- The service you chose handles that data under its own terms and privacy policy (OpenAI, Anthropic, Google or DeepSeek); read them before turning the coach on.
- Messages that mention chest pain, fainting, palpitations and similar symptoms are not sent: the phone answers them itself.
- Your API key is stored encrypted with an Android Keystore key and is sent only to the service it belongs to, over HTTPS.

## Health Connect

HR Buzz asks to read steps, exercise sessions, and optionally sleep, distance, resting heart rate and heart rate variability. It never writes to Health Connect. You can turn this off in HR Buzz's settings or remove the permissions in Health Connect, and HR Buzz forgets what it read.

## Sharing and export

HR Buzz shares nothing on its own. You can export a session as TCX or GPX and send it to another app; that is your choice each time.

## Keeping and deleting data

Data lives in the app's private storage. "Delete everything the coach remembers" clears the coach's plan, chat, notes, check-ins and request log. Uninstalling the app deletes everything it stored. Data already sent to the AI service you chose is outside the app's control; contact that service about it.

## Not medical advice

HR Buzz and its coach are training aids, not medical devices. Heart-rate zones, alerts and plans are estimates and are not a diagnosis or a substitute for a doctor. If you have a heart condition or take medication that affects heart rate, use the limits your doctor gave you. Stop and seek medical help for chest pain, fainting, dizziness or an irregular heartbeat.

## Children

HR Buzz is not directed at children under 13.

## Changes and contact

If this policy changes, the date above changes and the new text is published at this address. Questions or requests: privacy@sourdoughcalculator.uk.
