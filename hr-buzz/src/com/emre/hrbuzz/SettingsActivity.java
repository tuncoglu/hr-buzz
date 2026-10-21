package com.emre.hrbuzz;

import android.app.Activity;
import android.content.SharedPreferences;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Profile (for zones and calories), voice guidance, training screen, strap and battery. */
public class SettingsActivity extends Activity {

    private static final int[] VOICE_VALUES = {Prefs.VOICE_OFF, Prefs.VOICE_PER_KM, 1, 5, 10};
    private static final String[] VOICE_LABELS = {"Off", "Every km", "1 min", "5 min", "10 min"};

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
        card(root).addView(zonesText);
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
        HorizontalScrollView voiceScroll = new HorizontalScrollView(this);
        voiceScroll.setHorizontalScrollBarEnabled(false);
        voiceScroll.addView(voice);
        voiceCard.addView(voiceScroll);

        // ---- training screen
        section(root, "Training screen");
        LinearLayout screen = card(root);
        toggle(screen, "Keep screen on", "keepScreenOn", Prefs.keepScreenOn(this));
        toggle(screen, "Hold to pause or finish", "secureButtons", Prefs.secureButtons(this));
        hint(screen, "The training screen also shows over the lock screen, so a press of the "
                + "power button is enough to check it.");

        // ---- alarm sound
        section(root, "Alarm");
        LinearLayout alarm = card(root);
        hint(alarm, "Alerts play on the alarm volume, so they are heard even with media muted.");
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

    private void refreshProfile() {
        int age = Prefs.age(this);
        int estimate = Training.ageHrMax(age);
        int seen = Prefs.hrMaxSeen(this);
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
        } else if (seen > estimate) {
            hrMaxSource.setText("The highest you've held for 10 seconds: " + seen + " on "
                    + seenOn + ". It rises by itself if a session goes higher.");
        } else {
            hrMaxSource.setText("220 \u2212 age = " + estimate + ", Polar's estimate, until a "
                    + "session goes higher (held for 10 seconds)."
                    + (seen > 0 ? " Your highest so far: " + seen + "." : ""));
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
        } else if (Prefs.hrMaxAuto(this) && Prefs.hrMaxSeen(this) > Training.ageHrMax(Prefs.age(this))) {
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
