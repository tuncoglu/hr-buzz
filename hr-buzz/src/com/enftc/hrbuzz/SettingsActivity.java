package com.enftc.hrbuzz;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.health.connect.HealthConnectManager;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Profile (for zones and calories), voice guidance, training screen, strap and battery. */
public class SettingsActivity extends Activity {

    private static final int[] VOICE_VALUES = {Prefs.VOICE_OFF, Prefs.VOICE_PER_KM, 1, 5, 10};
    private static final String[] VOICE_LABELS = {"Off", "Each km", "1 min", "5 min", "10 min"};
    private static final String[] CUE_LABELS = {"Voice + buzz", "Voice", "Buzz only"};
    private static final int[] CUE_VALUES = {Prefs.CUE_BOTH, Prefs.CUE_VOICE, Prefs.CUE_BUZZ};

    private SharedPreferences prefs;
    private HrManager hr;
    private TextView zonesText;
    private EditText hrMaxInput;
    private TextView hrMaxValue;
    private TextView hrMaxSource;
    private LinearLayout hrMaxRow;
    private TextView restValue;
    private TextView restSource;
    private LinearLayout restRow;
    private TextView strapText;
    private Button batteryButton;
    private Switch moveSwitch;
    private static final int REQ_HEALTH = 7;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        prefs = Prefs.of(this);
        hr = HrManager.get(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 28));
        scroll.addView(root);
        root.addView(Ui.bold(Ui.text(this, "Settings", 26, Ui.TEXT)));

        // ---- profile
        section(root, "You");
        LinearLayout profile = card(root);
        hint(profile, "Used for heart rate zones and calories. Nothing leaves the phone.");
        number(profile, "Age", prefs.getInt("age", 40), "years", v -> {
            if (v >= 10 && v <= 100) {
                prefs.edit().putInt("age", v).apply();
                refreshProfile();
            }
        });
        LinearLayout sexRow = row(profile, "Sex");
        LinearLayout sex = new LinearLayout(this);
        String[] sexes = {"Male", "Female"};
        for (int i = 0; i < 2; i++) {
            final boolean male = i == 0;
            TextView chip = Ui.chip(this, sexes[i]);
            chip.setOnClickListener(v -> {
                prefs.edit().putBoolean("male", male).apply();
                Ui.select(sex, male ? 0 : 1);
            });
            sex.addView(chip);
        }
        Ui.select(sex, Prefs.male(this) ? 0 : 1);
        sexRow.addView(sex);
        number(profile, "Weight", prefs.getInt("weight", 75), "kg", v -> {
            if (v >= 30 && v <= 250) {
                prefs.edit().putInt("weight", v).apply();
            }
        });

        // Max heart rate: Auto (from sessions) or entered.
        hrMaxValue = valueRow(profile, "Max heart rate");
        sourceChips(profile, new String[]{"Auto", "My own"}, Prefs.hrMaxAuto(this) ? 0 : 1, i -> {
            prefs.edit().putBoolean("hrMaxAuto", i == 0).apply();
            refreshProfile();
        });
        hrMaxRow = new LinearLayout(this);
        hrMaxRow.setOrientation(LinearLayout.VERTICAL);
        profile.addView(hrMaxRow);
        hrMaxInput = number(hrMaxRow, "Your max", Prefs.hrMaxSetting(this), "bpm", v -> {
            if (v == 0 || (v >= 120 && v <= 230)) {
                prefs.edit().putInt("hrMax", v).apply();
                refreshProfile();
            }
        });
        hrMaxSource = Ui.text(this, "", 13, Ui.TEXT2);
        hrMaxSource.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        profile.addView(hrMaxSource);

        // Resting heart rate: from fitness tests or entered.
        restValue = valueRow(profile, "Resting heart rate");
        sourceChips(profile, new String[]{"From fitness tests", "My own"},
                Prefs.restHrAuto(this) ? 0 : 1, i -> {
                    prefs.edit().putBoolean("restHrAuto", i == 0).apply();
                    refreshProfile();
                });
        restRow = new LinearLayout(this);
        restRow.setOrientation(LinearLayout.VERTICAL);
        profile.addView(restRow);
        number(restRow, "Your resting", prefs.getInt("restHr", 0), "bpm", v -> {
            if (v == 0 || (v >= 30 && v <= 120)) {
                prefs.edit().putInt("restHr", v).apply();
                refreshProfile();
            }
        });
        restSource = Ui.text(this, "", 13, Ui.TEXT2);
        restSource.setPadding(0, Ui.dp(this, 4), 0, 0);
        profile.addView(restSource);

        section(root, "Heart rate zones");
        zonesText = Ui.text(this, "", 14, Ui.TEXT);
        zonesText.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout zonesCard = card(root);
        zonesCard.addView(zonesText);
        zonesCard.addView(gapTop(Ui.text(this, talkTestLine(), 13, Ui.TEXT2), 10));
        refreshProfile();
        // Sessions recorded before peaks were kept are read once, off the main thread.
        new Thread(() -> {
            Prefs.refreshHrMaxSeen(getApplicationContext());
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    refreshProfile();
                }
            });
        }, "hrmax-scan").start();

        // ---- voice
        section(root, "Voice guidance");
        LinearLayout voiceCard = card(root);
        hint(voiceCard, "Speaks time, distance, pace and heart rate zone during a session, "
                + "and says when you go over or under your alert limits. Music ducks while "
                + "it talks.");
        LinearLayout voice = new LinearLayout(this);
        int current = Prefs.voice(this);
        for (int i = 0; i < VOICE_VALUES.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(this, VOICE_LABELS[i]);
            chip.setOnClickListener(v -> {
                prefs.edit().putInt("voice", VOICE_VALUES[index]).apply();
                Ui.select(voice, index);
            });
            chip.setSelected(VOICE_VALUES[i] == current);
            voice.addView(chip);
        }
        Ui.fillRow(voice);              // all five in view: a scrolling row hid the last
        voiceCard.addView(voice, rowLp());

        TextView cuesLabel = Ui.text(this, "Workout steps and announcements", 15, Ui.TEXT);
        cuesLabel.setPadding(0, Ui.dp(this, 16), 0, 0);
        voiceCard.addView(cuesLabel);
        LinearLayout cues = new LinearLayout(this);
        int cueNow = Prefs.cue(this);
        for (int i = 0; i < CUE_VALUES.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(this, CUE_LABELS[i]);
            chip.setSelected(CUE_VALUES[i] == cueNow);
            chip.setOnClickListener(v -> {
                prefs.edit().putInt("cue", CUE_VALUES[index]).apply();
                Ui.select(cues, index);
            });
            cues.addView(chip);
        }
        Ui.fillRow(cues);
        voiceCard.addView(cues, rowLp());
        hint(voiceCard, "Buzz only says nothing aloud, so music and audiobooks are never "
                + "interrupted: a workout step is one long buzz and then one short buzz for "
                + "each step number, three long ones at the end, and two short ones for an "
                + "announcement. Alerts always buzz.");

        // ---- move minutes
        section(root, "Activity");
        LinearLayout activity = card(root);
        LinearLayout moveRow = row(activity, "Health Connect");
        moveSwitch = new Switch(this);
        moveSwitch.setChecked(Prefs.moveOn(this) && HealthSync.granted(this));
        moveSwitch.setOnCheckedChangeListener((b, on) -> setMove(on));
        moveRow.addView(moveSwitch);
        hint(activity, "Reads steps and workouts from Health Connect (where Google Fit, "
                + "Fitbit, Pixel Watch and most fitness apps save them) and counts the minutes "
                + "you were moving, shown on the home screen. If you allow them, it also reads "
                + "sleep, distance, resting heart rate and HRV from your watch. The coach is "
                + "told the daily totals, so it knows how active and rested you are between "
                + "sessions. Read-only; nothing is written back.");
        if (Prefs.moveOn(this) && HealthSync.granted(this) && HealthSync.missingOptional(this)) {
            Button more = new Button(this, null, 0, R.style.SecondaryButton);
            more.setText("Allow sleep, distance and heart data");
            more.setAllCaps(false);
            more.setOnClickListener(v -> requestPermissions(HealthSync.PERMISSIONS, REQ_HEALTH));
            activity.addView(more);
        }

        // ---- training screen
        section(root, "Training screen");
        LinearLayout screen = card(root);
        toggle(screen, "Keep screen on", "keepScreenOn", Prefs.keepScreenOn(this));
        toggle(screen, "Hold to pause or finish", "secureButtons", Prefs.secureButtons(this));
        toggle(screen, "Pause when standing still", "autoPause", Prefs.autoPause(this));
        hint(screen, "With GPS, a stop of about 8 seconds (a crossing) pauses the session, and "
                + "moving again resumes it, so time and pace are not dragged down. A pause "
                + "you made yourself stays until you resume.");
        hint(screen, "The training screen also shows over the lock screen, so a press of the "
                + "power button is enough to check it.");

        // ---- alarm sound
        section(root, "Alarm");
        LinearLayout alarm = card(root);
        hint(alarm, "Alerts play on the alarm volume, so they are heard even with media muted.");
        LinearLayout style = new LinearLayout(this);
        String[] styles = {"Calm", "Immediate"};
        for (int i = 0; i < styles.length; i++) {
            final boolean calm = i == 0;
            TextView chip = Ui.chip(this, styles[i]);
            chip.setSelected(calm == Prefs.calmAlerts(this));
            chip.setOnClickListener(v -> {
                hr.setCalmAlerts(calm);
                Ui.select(style, calm ? 0 : 1);
            });
            style.addView(chip);
        }
        Ui.fillRow(style);
        alarm.addView(style, rowLp());
        hint(alarm, "Calm: staying over the limit gets a short buzz after 10 s, the beep after "
                + "30 s, and then every 10 s; 10 bpm over sounds at once. Immediate: the beep "
                + "after three readings over, every 5 s.");
        Button test = button(alarm, "Play test alert");
        test.setOnClickListener(v -> hr.testAlarm());

        // ---- strap
        section(root, "Heart rate strap");
        LinearLayout strap = card(root);
        strapText = Ui.text(this, "", 15, Ui.TEXT);
        strap.addView(strapText);
        hint(strap, "HR Buzz remembers the first strap it connects to and ignores others, "
                + "so it never picks up somebody else's at the gym or a race.");
        Button forget = button(strap, "Forget this strap");
        forget.setOnClickListener(v -> {
            hr.forgetDevice();
            refreshStrap();
        });
        refreshStrap();

        // ---- battery
        section(root, "Background");
        LinearLayout bg = card(root);
        batteryButton = button(bg, "");
        batteryButton.setOnClickListener(v -> MainActivity.requestUnrestrictedBattery(this));

        section(root, "About");
        hint(card(root), "HR Buzz " + version() + ". No account. Sessions stay on the phone "
                + "until you export them (TCX/GPX from a session's summary). The internet is "
                + "used only by the coach, when you turn it on: it sends a summary of your "
                + "training to DeepSeek.");

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        batteryButton.setText(MainActivity.batteryUnrestricted(this)
                ? "Battery use: unrestricted \u2713" : "Allow unrestricted battery use");
        batteryButton.setEnabled(!MainActivity.batteryUnrestricted(this));
    }

    private void setMove(boolean on) {
        if (!on) {
            prefs.edit().putBoolean("moveOn", false).apply();
            HealthSync.forget(this);
            return;
        }
        if (!HealthSync.available(this)) {
            moveSwitch.setChecked(false);
            Toast.makeText(this, "Health Connect needs Android 14 or newer", Toast.LENGTH_LONG)
                    .show();
        } else if (HealthSync.granted(this)) {
            prefs.edit().putBoolean("moveOn", true).apply();
        } else {
            requestPermissions(HealthSync.PERMISSIONS, REQ_HEALTH);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_HEALTH) {
            return;
        }
        if (HealthSync.granted(this)) {
            prefs.edit().putBoolean("moveOn", true).apply();
            return;
        }
        moveSwitch.setChecked(false);
        // After two refusals Android stops asking: send them to Health Connect's own screen.
        try {
            startActivity(new Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, getPackageName()));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Allow steps and exercise for HR Buzz in Health Connect",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshProfile() {
        int age = Prefs.age(this);
        int estimate = Training.ageHrMax(age);
        int seen = Prefs.hrMaxSeen(this);
        int used = Prefs.hrMaxUsedPeak(this);
        String seenOn = seen > 0 ? new SimpleDateFormat("d MMM", Locale.getDefault())
                .format(new Date(prefs.getLong("hrMaxSeenAt", 0))) : "";
        boolean maxAuto = Prefs.hrMaxAuto(this);
        hrMaxValue.setText(Prefs.hrMax(this) + " bpm");
        Ui.show(hrMaxRow, !maxAuto);
        hrMaxInput.setHint(String.valueOf(estimate));
        if (!maxAuto) {
            hrMaxSource.setText("Your own number, e.g. from a lab test or an all-out uphill "
                    + "effort." + (seen > 0 ? " Highest held for 10 s in a session: " + seen
                    + " (" + seenOn + ")." : "") + (Prefs.hrMaxSetting(this) == 0
                    ? " Until you enter one: 220 \u2212 age = " + estimate + "." : ""));
        } else if (used > estimate) {
            hrMaxSource.setText("The highest you've held for 10 seconds: " + used + ". If a "
                    + "session goes higher, you'll be asked before it's used.");
        } else {
            hrMaxSource.setText("220 \u2212 age = " + estimate + ", Polar's estimate, until a "
                    + "session goes higher (held for 10 seconds) and you agree to use it."
                    + (seen > 0 ? " Your highest so far: " + seen + " (" + seenOn + ")." : ""));
        }

        boolean restAuto = Prefs.restHrAuto(this);
        int rest = Prefs.restHr(this);
        restValue.setText(rest > 0 ? rest + " bpm" : "--");
        Ui.show(restRow, !restAuto);
        if (!restAuto) {
            restSource.setText("Your own number. Fitness tests are still saved, but don't "
                    + "change it.");
        } else if (rest > 0) {
            List<CoachLogic.Test> tests = Prefs.fitnessTests(this);
            CoachLogic.Test last = tests.get(tests.size() - 1);
            restSource.setText("The middle of your last three fitness tests, so one restless "
                    + "morning doesn't move it. Latest: " + last.rest + " on "
                    + new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(last.t))
                    + ".");
        } else {
            restSource.setText("No fitness test in the last 90 days. Do one in the morning "
                    + "from the home screen and it fills in here.");
        }
        refreshZones();
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        return lp;
    }

    private TextView gapTop(TextView t, int dp) {
        t.setPadding(0, Ui.dp(this, dp), 0, 0);
        return t;
    }

    /**
     * What their own talk test answers say about the zones. The zones are never changed by it;
     * it only moves where easy coached steps start to beep.
     */
    private String talkTestLine() {
        long now = System.currentTimeMillis();
        List<CoachLogic.SessionInfo> all = Coach.get(this).sessions();
        int answered = 0;
        for (CoachLogic.SessionInfo si : all) {
            answered += si.talk > 0 ? 1 : 0;
        }
        int bpm = CoachLogic.conversationalBpm(all, now);
        if (bpm == 0) {
            return "Talk test: " + (answered == 0 ? "not answered yet" : "answered on " + answered
                    + (answered == 1 ? " session" : " sessions")) + ". After three sessions in "
                    + "which you could talk in full sentences, this shows what that says about "
                    + "your zones.";
        }
        CoachLogic.Step easy = new CoachLogic.Step();
        easy.zoneLow = 1;
        easy.zoneHigh = 2;
        int hrMax = Prefs.hrMax(this);
        int ceiling = Prefs.alertAbove(this);
        int with = CoachLogic.stepLimits(easy, hrMax, ceiling, bpm)[0];
        int without = CoachLogic.stepLimits(easy, hrMax, ceiling)[0];
        String line = "Talk test: you could talk in full sentences at an average of up to " + bpm
                + " bpm (" + CoachLogic.conversationalSessions(all, now) + " sessions). ";
        return line + (with > without
                ? "That is above your zone 2, so easy coached steps beep above " + with
                + " instead of " + without + ". Your zones stay as they are."
                : "That fits your zones.");
    }

    private void refreshZones() {
        int hrMax = Prefs.hrMax(this);
        StringBuilder sb = new StringBuilder();
        for (int z = 5; z >= 1; z--) {
            int lo = Training.zoneFloor(z, hrMax);
            int hi = z == 5 ? hrMax : Training.zoneFloor(z + 1, hrMax) - 1;
            sb.append(String.format(Locale.ROOT, "Z%d  %-10s %3d\u2013%3d bpm  %3d\u2013%d%%\n",
                    z, Training.ZONE_NAMES[z], lo, hi, Training.ZONE_PCT[z - 1],
                    z == 5 ? 100 : Training.ZONE_PCT[z]));
        }
        String source;
        if (!Prefs.hrMaxAuto(this) && Prefs.hrMaxSetting(this) > 0) {
            source = " (entered)";
        } else if (Prefs.hrMaxAuto(this)
                && Prefs.hrMaxUsedPeak(this) > Training.ageHrMax(Prefs.age(this))) {
            source = " (from your sessions)";
        } else {
            source = " (estimated from age)";
        }
        sb.append("\nHRmax ").append(hrMax).append(source);
        zonesText.setText(sb.toString());
    }

    private void refreshStrap() {
        String name = hr.getDeviceName();
        strapText.setText(name != null ? "Remembered: " + name
                : "None remembered yet \u2014 the next strap found will be used");
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ layout

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        parent.addView(c, lp);
        return c;
    }

    private void section(LinearLayout parent, String title) {
        TextView t = Ui.text(this, title.toUpperCase(Locale.getDefault()), 13, Ui.TEXT2);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 22), 0, 0);
        parent.addView(t);
    }

    private void hint(LinearLayout parent, String text) {
        TextView t = Ui.text(this, text, 13, Ui.TEXT3);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        parent.addView(t);
    }

    private LinearLayout row(LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 52));
        r.addView(Ui.text(this, label, 16, Ui.TEXT), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(r);
        return r;
    }

    /** A label with the value in use on the right. */
    private TextView valueRow(LinearLayout parent, String label) {
        LinearLayout r = row(parent, label);
        TextView v = Ui.bold(Ui.text(this, "", 18, Ui.TEXT));
        r.addView(v);
        return v;
    }

    private void sourceChips(LinearLayout parent, String[] labels, int selected,
                             IntConsumer onPick) {
        LinearLayout chips = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(this, labels[i]);
            chip.setTextSize(14);
            chip.setSelected(i == selected);
            chip.setOnClickListener(v -> {
                Ui.select(chips, index);
                onPick.accept(index);
            });
            chips.addView(chip);
        }
        parent.addView(chips);
    }

    private EditText number(LinearLayout parent, String label, int value, String unit,
                            IntConsumer onValue) {
        LinearLayout r = row(parent, label);
        // Built by hand: an EditText made with a bare style resource loses the platform's
        // text field defaults and never takes focus.
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        input.setGravity(Gravity.CENTER);
        input.setTextSize(20);
        input.setTextColor(Ui.TEXT);
        input.setSelectAllOnFocus(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setText(value > 0 ? String.valueOf(value) : "");
        r.addView(input, new LinearLayout.LayoutParams(Ui.dp(this, 88),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView u = Ui.text(this, unit, 15, Ui.TEXT2);
        u.setMinWidth(Ui.dp(this, 48));
        u.setPadding(Ui.dp(this, 6), 0, 0, 0);
        r.addView(u);
        Ui.onNumber(input, onValue);
        return input;
    }

    private void toggle(LinearLayout parent, String label, String key, boolean value) {
        LinearLayout r = row(parent, label);
        Switch s = new Switch(this);
        s.setChecked(value);
        s.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
        r.addView(s);
    }

    private Button button(LinearLayout parent, String text) {
        Button b = new Button(this, null, 0, R.style.SecondaryButton);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        lp.topMargin = Ui.dp(this, 8);
        parent.addView(b, lp);
        return b;
    }
}
