package com.emre.hrbuzz;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The launcher screen: live heart rate, a big Start button, then (scrolling down, or turning
 * the crown) the zones, the limits and the latest runs.
 */
public class StartActivity extends Activity implements WristHr.Listener {

    /** Start today's coach plan (set by the coach screen and its morning notification). */
    static final String EXTRA_START_PLAN = "startPlan";

    private static final int REQUEST_PERMISSIONS = 1;

    private WristHr hr;
    private WearRecorder recorder;
    private LinearLayout page;
    private ScrollView scroll;
    private GpsProbe gps;
    /** The run to start once the permissions are answered. */
    private boolean startCoached;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hr = WristHr.get(this);
        recorder = WearRecorder.get(this);
        gps = new GpsProbe(this, this::onHeart);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Wu.BLACK);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (recorder.isRecording()) {
            // A run is going: this is where the notification or the launcher brings you back.
            startActivity(new Intent(this, LiveActivity.class));
        }
        hr.addListener(this);
        if (hasHeartPermission()) {
            hr.start();
        }
        startGpsProbe();
        if (hasStepPermission()) {
            StepJob.schedule(this);
            StepLog.snapshot(this, this::showSteps);
        }
        if (getIntent().getBooleanExtra(EXTRA_START_PLAN, false)) {
            getIntent().removeExtra(EXTRA_START_PLAN);
            begin(true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
        scroll.requestFocus();      // so the crown scrolls
    }

    @Override
    protected void onStop() {
        gps.stop();
        hr.removeListener(this);
        if (!recorder.isRecording()) {
            hr.stop();
        }
        super.onStop();
    }

    @Override
    public void onHeart() {
        TextView bpm = findViewById(R.id.live_bpm);
        if (bpm != null) {
            int now = hr.getBpm();
            bpm.setText(now > 0 ? String.valueOf(now) : "--");
        }
        TextView status = findViewById(R.id.ready_status);
        if (status != null) {
            showStatus(status);
        }
    }

    /** Updates the "Steps today" line once a read of the counter has been added up. */
    private void showSteps() {
        TextView t = findViewById(R.id.steps_today);
        long steps = StepLog.today(this);
        if (t != null && steps >= 0) {
            t.setText("Steps today \u00b7 " + String.format(Locale.getDefault(), "%,d", steps));
        }
    }

    /** GPS sports only: a probe while this screen is open, if location is allowed. */
    private void startGpsProbe() {
        if (Prefs.usesGps(Prefs.sport(this)) && !recorder.isRecording()
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
            gps.start();
        } else {
            gps.stop();
        }
    }

    /** "Pulse found \u00b7 GPS ready": whether a run can start properly right now. */
    private void showStatus(TextView status) {
        boolean pulse = hr.getBpm() > 0;
        boolean wantsGps = Prefs.usesGps(Prefs.sport(this));
        String text;
        boolean ready;
        if (!hr.hasSensor()) {
            text = "No heart rate sensor";
            ready = false;
        } else if (!wantsGps) {
            text = pulse ? "Pulse found \u00b7 no GPS needed" : "Finding pulse\u2026";
            ready = pulse;
        } else if (!gps.isRunning()) {
            text = (pulse ? "Pulse found" : "Finding pulse\u2026") + " \u00b7 GPS off";
            ready = false;
        } else {
            int accuracy = gps.accuracyMetres();
            text = (pulse ? "Pulse found" : "Finding pulse\u2026") + " \u00b7 "
                    + (accuracy < 0 ? "GPS connecting\u2026" : "GPS ready \u00b1" + accuracy + " m");
            ready = pulse && accuracy >= 0;
        }
        status.setText(text);
        status.setTextColor(ready ? Wu.TARGET_TEXT : Wu.MUTED);
    }

    // ----------------------------------------------------------------- permissions

    private String heartPermission() {
        return Build.VERSION.SDK_INT >= 36
                ? "android.permission.health.READ_HEART_RATE" : Manifest.permission.BODY_SENSORS;
    }

    private boolean hasHeartPermission() {
        return checkSelfPermission(heartPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{heartPermission(), Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p);
            }
        }
        requestPermissions(need.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_PERMISSIONS) {
            return;
        }
        if (hasHeartPermission()) {
            hr.start();
            // GPS and notifications are optional: heart rate alone is enough to run.
            startRun(startCoached);
        } else {
            Toast.makeText(this, "Heart rate permission needed", Toast.LENGTH_LONG).show();
        }
    }

    /** Starts a run now, or after asking for the permissions it needs. */
    private void begin(boolean coached) {
        // Location (GPS sports) and steps are asked for once; saying no still records heart
        // rate, just without the route or the step count.
        boolean wantsLocation = Prefs.usesGps(Prefs.sport(this))
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED;
        boolean wantsSteps = !hasStepPermission();
        boolean askExtras = (wantsLocation || wantsSteps)
                && !Prefs.of(this).getBoolean("askedExtras", false);
        if (hasHeartPermission() && !askExtras) {
            startRun(coached);
        } else {
            startCoached = coached;
            Prefs.of(this).edit().putBoolean("askedExtras", true).apply();
            askPermissions();
        }
    }

    private boolean hasStepPermission() {
        return checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startRun(boolean coached) {
        CoachLogic.Plan plan = coached ? Coach.get(this).todayPlan() : null;
        WearService.record(this, plan != null ? plan.sport : Prefs.sport(this), plan != null);
        startActivity(new Intent(this, LiveActivity.class));
    }

    // -------------------------------------------------------------------------- UI

    private void build() {
        int hrMax = Prefs.hrMax(this);
        int side = Wu.px(this, 62);

        page = Wu.column(this);
        page.setPadding(side, Wu.px(this, 46), side, Wu.px(this, 70));

        page.addView(Wu.text(this, "HR Buzz", 18, Wu.MUTED, true));
        page.addView(Wu.space(this, 10));

        // live heart rate
        LinearLayout live = Wu.row(this);
        live.setGravity(Gravity.CENTER);
        TextView bpm = Wu.text(this, hr.getBpm() > 0 ? String.valueOf(hr.getBpm()) : "--", 48,
                Wu.TEXT, true);
        bpm.setId(R.id.live_bpm);
        live.addView(bpm);
        TextView unit = Wu.text(this, "  bpm now", 16, Wu.MUTED, true);
        live.addView(unit);
        page.addView(live);
        page.addView(Wu.space(this, 4));
        TextView ready = Wu.text(this, "", 15, Wu.MUTED, true);
        ready.setId(R.id.ready_status);
        showStatus(ready);
        page.addView(ready);
        page.addView(Wu.space(this, 10));

        Button start;
        if (!hr.hasSensor()) {
            page.addView(Wu.text(this, "This watch has no heart rate sensor", 17, Wu.ALERT, true));
            start = null;
        } else {
            start = Wu.button(this, "Start run", Wu.ZONE[2], Wu.ON_ACCENT, 76);
            start.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * Wu.unit(this));
            start.setOnClickListener(v -> begin(false));
            page.addView(start);
            page.addView(Wu.space(this, 8));
            Button sport = Wu.button(this, Prefs.sport(this) + "  \u203a", Wu.BUTTON, Wu.TEXT, 44);
            sport.setContentDescription("Sport: " + Prefs.sport(this) + ". Tap to choose");
            sport.setOnClickListener(v -> startActivity(new Intent(this, SportActivity.class)));
            page.addView(sport);
        }
        page.addView(Wu.space(this, 10));

        // the coach's plan for today, one tap from here
        Coach coach = Coach.get(this);
        CoachLogic.Plan today = coach.isEnabled() ? coach.todayPlan() : null;
        // "Coach" stays in the label, with today's plan under it, so it always reads as the coach.
        String coachLabel = today == null ? "Coach"
                : "Coach\n" + (today.rest ? "Rest day"
                        : today.title + " \u00b7 " + today.totalMinutes() + " min");
        Button coachButton = Wu.button(this, coachLabel, Wu.CARD, Wu.TEXT, today == null ? 48 : 68);
        coachButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * Wu.unit(this));
        coachButton.setContentDescription(today == null ? "Coach"
                : "Coach: " + coachLabel.substring(coachLabel.indexOf('\n') + 1));
        coachButton.setOnClickListener(v -> startActivity(new Intent(this, WearCoachActivity.class)));
        page.addView(coachButton);
        page.addView(Wu.space(this, 10));

        List<SessionStore.Summary> recent = SessionStore.list(this);
        page.addView(weekCard(recent));
        page.addView(Wu.space(this, 12));

        int high = hr.getThreshold();
        int low = hr.getLowLimit();
        String target = !hr.alarmOn() ? "Alerts off"
                : low > 0 ? "Alerts below " + low + " and above " + high
                : "Alert above " + high;
        page.addView(Wu.text(this, target, 16, Wu.MUTED, true));
        page.addView(Wu.space(this, 4));
        page.addView(Wu.text(this, "Max HR " + hrMax + (Prefs.hrMaxAuto(this) ? " (Auto)" : ""),
                15, Wu.MUTED, false));

        // zones
        page.addView(Wu.space(this, 30));
        page.addView(Wu.text(this, "Zones", 18, Wu.MUTED, true));
        page.addView(Wu.space(this, 8));
        for (int z = 1; z <= 5; z++) {
            page.addView(zoneRow(z, hrMax));
            page.addView(Wu.space(this, 6));
        }

        // limits
        page.addView(Wu.space(this, 24));
        page.addView(Wu.text(this, "Limits", 18, Wu.MUTED, true));
        page.addView(Wu.space(this, 8));
        page.addView(stepper("Max HR", hrMax, 120, 220, 0, v -> {
            hr.setHrMax(v);
            build();
        }));
        page.addView(Wu.space(this, 8));
        page.addView(stepper("Alert above", high, 100, 220, 0, v -> {
            hr.setThreshold(v);
            build();
        }));
        page.addView(Wu.space(this, 8));
        page.addView(stepper("Alert below", low, 60, 200, Math.max(60, high - 40), v -> {
            hr.setLowLimit(v);
            build();
        }));
        page.addView(Wu.space(this, 8));
        Button alarm = Wu.button(this, hr.alarmOn() ? "Alerts on" : "Alerts off",
                Wu.BUTTON, Wu.TEXT, 48);
        alarm.setOnClickListener(v -> {
            hr.setAlarmOn(!hr.alarmOn());
            build();
        });
        page.addView(alarm);

        // run alerts
        page.addView(Wu.space(this, 24));
        page.addView(Wu.text(this, "Run alerts", 18, Wu.MUTED, true));
        page.addView(Wu.space(this, 8));
        page.addView(toggle("Km split buzz", "kmBuzz", WearPrefs.kmBuzz(this)));
        page.addView(Wu.space(this, 6));
        page.addView(toggle("Zone change buzz", "zoneBuzz", WearPrefs.zoneBuzz(this)));
        page.addView(Wu.space(this, 6));
        page.addView(toggle("Auto-pause", "autoPause", WearPrefs.autoPause(this)));
        page.addView(Wu.space(this, 4));
        page.addView(Wu.text(this, "Auto-pause needs GPS, so Gym and Other never use it.", 13,
                Wu.MUTED, false));

        // profile: only used for calories and the age formula for max HR
        page.addView(Wu.space(this, 24));
        page.addView(Wu.text(this, "Profile", 18, Wu.MUTED, true));
        page.addView(Wu.space(this, 8));
        page.addView(stepper("Age", Prefs.age(this), 10, 99, 0, v -> {
            Prefs.of(this).edit().putInt("age", v).apply();
            build();
        }));
        page.addView(Wu.space(this, 8));
        page.addView(stepper("Weight kg", Prefs.weight(this), 30, 200, 0, v -> {
            Prefs.of(this).edit().putInt("weight", v).apply();
            build();
        }));
        page.addView(Wu.space(this, 8));
        Button sex = Wu.button(this, Prefs.male(this) ? "Male" : "Female", Wu.BUTTON, Wu.TEXT, 48);
        sex.setOnClickListener(v -> {
            Prefs.of(this).edit().putBoolean("male", !Prefs.male(this)).apply();
            build();
        });
        page.addView(sex);

        // recent runs
        if (!recent.isEmpty()) {
            page.addView(Wu.space(this, 24));
            page.addView(Wu.text(this, "Recent", 18, Wu.MUTED, true));
            page.addView(Wu.space(this, 8));
            for (int i = 0; i < Math.min(5, recent.size()); i++) {
                page.addView(recentRow(recent.get(i)));
                page.addView(Wu.space(this, 6));
            }
        }

        scroll.removeAllViews();
        scroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A day with this much moving time counts as an exercise day. */
    private static final long EXERCISE_DAY_MS = 10 * 60000L;

    /**
     * "2 of 5 days" this week, a Monday-to-Sunday strip of the days that counted, and the
     * week's totals. The target is the coach's days a week, when it is set up.
     */
    private View weekCard(List<SessionStore.Summary> sessions) {
        int n = sessions.size();
        long[] starts = new long[n];
        long[] active = new long[n];
        double[] distance = new double[n];
        double[] load = new double[n];
        for (int i = 0; i < n; i++) {
            SessionStore.Summary s = sessions.get(i);
            starts[i] = s.id;
            active[i] = s.recording ? 0 : s.activeMs;
            distance[i] = s.distanceM;
            load[i] = s.trimp;
        }
        WearLogic.Week w = WearLogic.week(starts, active, distance, load,
                System.currentTimeMillis(), java.time.ZoneId.systemDefault(), EXERCISE_DAY_MS);
        boolean coached = Prefs.of(this).getBoolean("coachSetUp", false);
        int target = coached ? Prefs.of(this).getInt("coachDays", 0) : 0;
        boolean reached = target > 0 && w.dayCount >= target;

        LinearLayout card = Wu.column(this);
        card.setBackground(Wu.pill(Wu.CARD, this, 24));
        card.setPadding(Wu.px(this, 12), Wu.px(this, 10), Wu.px(this, 12), Wu.px(this, 12));
        card.addView(Wu.text(this, "This week", 15, Wu.MUTED, true));
        String headline = target > 0 ? w.dayCount + " of " + target + " days"
                : w.dayCount + (w.dayCount == 1 ? " day" : " days");
        TextView head = Wu.text(this, headline, 26, reached ? Wu.TARGET_TEXT : Wu.TEXT, true);
        head.setContentDescription("This week: " + headline + " with exercise");
        card.addView(head);
        card.addView(Wu.space(this, 6));

        String letters = "MTWTFSS";
        LinearLayout strip = Wu.row(this);
        strip.setGravity(Gravity.CENTER);
        for (int d = 0; d < 7; d++) {
            LinearLayout cell = Wu.column(this);
            View dot = new View(this);
            dot.setBackground(Wu.pill(w.days[d] ? Wu.ZONE[2] : Wu.BUTTON, this, 9));
            dot.setLayoutParams(Wu.lp(Wu.px(this, 18), Wu.px(this, 18)));
            cell.addView(dot);
            cell.addView(Wu.text(this, String.valueOf(letters.charAt(d)), 13,
                    d == w.today ? Wu.TEXT : Wu.MUTED, d == w.today));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            strip.addView(cell, lp);
        }
        strip.setContentDescription("Days with exercise this week, Monday first");
        card.addView(strip);

        if (hasStepPermission()) {
            card.addView(Wu.space(this, 8));
            long steps = StepLog.today(this);
            TextView st = Wu.text(this, steps >= 0 ? "Steps today \u00b7 "
                    + String.format(Locale.getDefault(), "%,d", steps) : "Counting steps\u2026",
                    15, Wu.TEXT, true);
            st.setId(R.id.steps_today);
            card.addView(st);
        }
        if (w.sessions > 0) {
            card.addView(Wu.space(this, 4));
            String totals = (w.distanceM > 0 ? Training.km(w.distanceM) + " km \u00b7 " : "")
                    + Training.duration(w.activeMs)
                    + (w.trimp >= 1 ? " \u00b7 load " + Math.round(w.trimp) : "");
            card.addView(Wu.text(this, totals, 15, Wu.MUTED, true));
        }
        return card;
    }

    /** One row that shows its option as On or Off and flips it on a tap. */
    private View toggle(String label, String key, boolean on) {
        Button b = Wu.button(this, label + ": " + (on ? "On" : "Off"),
                on ? Wu.TARGET_BG : Wu.CARD, on ? Wu.TARGET_TEXT : Wu.TEXT, 52);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * Wu.unit(this));
        b.setOnClickListener(v -> {
            WearPrefs.set(this, key, !on);
            build();
        });
        return b;
    }

    private View zoneRow(int zone, int hrMax) {
        int from = Training.zoneFloor(zone, hrMax);
        String range = zone < 5 ? from + "–" + (Training.zoneFloor(zone + 1, hrMax) - 1)
                : from + "+";
        boolean live = hr.getBpm() > 0 && Training.zone(hr.getBpm(), hrMax) == zone;
        LinearLayout r = Wu.row(this);
        r.setBackground(Wu.pill(live ? Wu.TARGET_BG : Wu.CARD, this, 22));
        r.setPadding(Wu.px(this, 16), 0, Wu.px(this, 16), 0);
        r.setLayoutParams(Wu.lp(ViewGroup.LayoutParams.MATCH_PARENT, Wu.px(this, 44)));
        View dot = new View(this);
        dot.setBackground(Wu.pill(Wu.ZONE[zone], this, 6));
        dot.setLayoutParams(Wu.lp(Wu.px(this, 12), Wu.px(this, 12)));
        r.addView(dot);
        TextView name = Wu.text(this, "  Zone " + zone, 17, Wu.TEXT, true);
        name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(Wu.text(this, range, 17, Wu.MUTED, true));
        return r;
    }

    private interface Change {
        void to(int value);
    }

    /**
     * A label with a number and minus/plus buttons. With {@code onValue} above 0 the limit can
     * be off (shown as "Off", stored as 0): minus from {@code min} turns it off and plus from
     * off turns it on at {@code onValue}.
     */
    private View stepper(String label, int value, int min, int max, int onValue, Change change) {
        LinearLayout box = Wu.column(this);
        box.setBackground(Wu.pill(Wu.CARD, this, 24));
        box.setPadding(Wu.px(this, 8), Wu.px(this, 8), Wu.px(this, 8), Wu.px(this, 8));
        box.addView(Wu.text(this, label, 15, Wu.MUTED, true));
        LinearLayout r = Wu.row(this);
        r.setGravity(Gravity.CENTER);
        Button minus = round("\u2212");
        Button plus = round("+");
        minus.setContentDescription(label + " down");
        plus.setContentDescription(label + " up");
        boolean canBeOff = onValue > 0;
        minus.setOnClickListener(v -> change.to(value <= 0 ? 0
                : value <= min ? (canBeOff ? 0 : min) : value - 1));
        plus.setOnClickListener(v -> change.to(value <= 0 ? onValue : Math.min(max, value + 1)));
        TextView number = Wu.text(this, value > 0 ? String.valueOf(value) : "Off", 26, Wu.TEXT, true);
        r.addView(minus);
        r.addView(number, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(plus);
        box.addView(r);
        return box;
    }

    private Button round(String s) {
        Button b = Wu.button(this, s, Wu.BUTTON, Wu.TEXT, 48);
        b.setLayoutParams(Wu.lp(Wu.px(this, 52), Wu.px(this, 48)));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private View recentRow(SessionStore.Summary s) {
        String when = new java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault())
                .format(new java.util.Date(s.id));
        String detail = (s.gps ? Training.km(s.distanceM) + " km · " : "")
                + Training.duration(s.activeMs);
        LinearLayout r = Wu.column(this);
        r.setBackground(Wu.pill(Wu.CARD, this, 22));
        r.setPadding(Wu.px(this, 16), Wu.px(this, 8), Wu.px(this, 16), Wu.px(this, 8));
        r.addView(Wu.text(this, when, 15, Wu.MUTED, true));
        r.addView(Wu.text(this, detail, 18, Wu.TEXT, true));
        r.setClickable(true);
        r.setOnClickListener(v -> startActivity(new Intent(this, WearSummaryActivity.class)
                .putExtra(WearSummaryActivity.EXTRA_ID, s.id)));
        r.setContentDescription(when + ", " + detail);
        return r;
    }
}
