package com.enftc.hrbuzz;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Locale;

/**
 * The coach on the watch: today's plan with Start / Not today / Shorter / Harder, and the
 * setup (key, goal, days, minutes, anything to work around, morning time). The plans, the
 * checks that keep them safe and the calls to DeepSeek are the phone app's own {@link Coach}.
 */
public class WearCoachActivity extends Activity {

    static final String EXTRA_START_PLAN = "startPlan";

    private static final String[] GOALS = {"Run a 10K", "Get fitter", "Lose weight",
            "Lower resting HR", "Just feel better"};
    private static final int[] DAYS = {2, 3, 4, 5, 6, 7};
    private static final int[] MINUTES = {20, 35, 45, 60, 90, 120};
    private static final String[] AROUND = {"Nothing", "Knees", "Back", "New to running"};
    private static final int[] MORNING = {6 * 60 + 30, 7 * 60, 7 * 60 + 30, 8 * 60, -1};

    private Coach coach;
    private SharedPreferences prefs;
    private ScrollView scroll;
    private boolean editing;
    private String error;
    private CoachLogic.Plan previous;
    /** The coach's message, the step list and notes stay folded away until Details is tapped. */
    private boolean detailsOpen;

    // setup form
    private EditText keyInput;
    /** What was typed in the key field: the page rebuilds when the screen wakes, so keep it. */
    private String keyDraft = "";
    private String goal;
    private int days;
    private int minutes;
    private String around;
    private int morning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        coach = Coach.get(this);
        prefs = Prefs.of(this);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Wu.BLACK);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        setContentView(scroll);
        loadForm();
        editing = !prefs.getBoolean("coachSetUp", false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (getIntent().getBooleanExtra(EXTRA_START_PLAN, false)) {
            getIntent().removeExtra(EXTRA_START_PLAN);
            CoachLogic.Plan plan = coach.todayPlan();
            if (plan != null && !plan.rest) {
                startPlan();
                return;
            }
        }
        // Not while the settings form is open: a rebuild would wipe a key being typed, and the
        // watch's keyboard and its dimming screen both stop and restart this page.
        if (!editing || scroll.getChildCount() == 0) {
            build();
        }
        scroll.requestFocus();
    }

    @Override
    protected void onStop() {
        if (keyInput != null) {
            keyDraft = keyInput.getText().toString();
        }
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void loadForm() {
        goal = prefs.getString("coachGoal", GOALS[1]);
        days = prefs.getInt("coachDays", 3);
        minutes = prefs.getInt("coachMinutes", 35);
        around = prefs.getString("coachWorkAround", AROUND[0]);
        morning = prefs.getInt("coachMorning", 7 * 60 + 30);
    }

    private void startPlan() {
        startActivity(new Intent(this, StartActivity.class)
                .putExtra(StartActivity.EXTRA_START_PLAN, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    // -------------------------------------------------------------------------- UI

    private LinearLayout page() {
        int side = Wu.px(this, 62);
        LinearLayout p = Wu.column(this);
        p.setPadding(side, Wu.px(this, 46), side, Wu.px(this, 70));
        return p;
    }

    private void show(LinearLayout p) {
        scroll.removeAllViews();
        scroll.addView(p, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void build() {
        if (keyInput != null && editing) {
            keyDraft = keyInput.getText().toString();
        }
        if (editing) {
            buildSetup();
        } else {
            buildPlan();
        }
    }

    private void buildPlan() {
        LinearLayout p = page();
        p.addView(Wu.text(this, "Coach", 18, Wu.MUTED, true));
        p.addView(Wu.space(this, 8));
        CoachLogic.Plan plan = coach.todayPlan();

        if (!coach.isEnabled()) {
            p.addView(Wu.text(this, "The coach is off. Add your DeepSeek key to switch it on.",
                    17, Wu.TEXT, false));
            p.addView(Wu.space(this, 12));
        } else if (plan == null) {
            p.addView(Wu.text(this, "No plan for today yet", 20, Wu.TEXT, true));
            p.addView(Wu.space(this, 12));
            Button b = Wu.button(this, "Plan today", Wu.ZONE[2], Wu.ON_ACCENT, 60);
            b.setOnClickListener(v -> refreshPlan("They opened the app and there is no plan "
                    + "for today yet."));
            p.addView(b);
        } else if (plan.rest) {
            p.addView(Wu.text(this, "Rest day", 28, Wu.TEXT, true));
            p.addView(Wu.space(this, 10));
            Button b = Wu.button(this, "I want to train", Wu.ZONE[2], Wu.ON_ACCENT, 60);
            b.setOnClickListener(v -> refreshPlan("They want to train today after all; "
                    + "plan something suitable."));
            p.addView(b);
            p.addView(Wu.space(this, 8));
            detailsToggle(p);
            if (detailsOpen) {
                p.addView(Wu.space(this, 8));
                p.addView(Wu.text(this, plan.message, 16, Wu.MUTED, false));
            }
        } else {
            planCard(p, plan);
        }

        if (coach.isPlanning()) {
            p.addView(Wu.space(this, 10));
            p.addView(Wu.text(this, "Updating today's plan…", 16, Wu.MUTED, true));
        } else if (error != null) {
            p.addView(Wu.space(this, 10));
            p.addView(Wu.text(this, error, 16, Wu.ALERT, true));
        }

        p.addView(Wu.space(this, 22));
        Button settings = Wu.button(this, "Coach settings", Wu.BUTTON, Wu.TEXT, 48);
        settings.setOnClickListener(v -> {
            editing = true;
            build();
            scroll.scrollTo(0, 0);
        });
        p.addView(settings);
        show(p);
    }

    private void planCard(LinearLayout p, CoachLogic.Plan plan) {
        // The headline: what and how long, big. The rest is a tap away under Details.
        p.addView(Wu.text(this, plan.title, 26, Wu.TEXT, true));
        p.addView(Wu.text(this, summaryLine(plan), 20, Wu.TARGET_TEXT, true));
        p.addView(Wu.space(this, 10));
        p.addView(stepsBar(plan));
        if (!plan.adjusted.isEmpty()) {
            p.addView(Wu.space(this, 6));
            p.addView(Wu.text(this, "Adjusted for you \u00b7 see details", 15, Wu.ZONE[4], true));
        }
        p.addView(Wu.space(this, 12));

        Button start = Wu.button(this, coach.todayDone() ? "Start again" : "Start this run",
                Wu.ZONE[2], Wu.ON_ACCENT, 64);
        start.setOnClickListener(v -> startPlan());
        p.addView(start);
        if (coach.todayDone()) {
            p.addView(Wu.space(this, 6));
            p.addView(Wu.text(this, "Today's run is done", 15, Wu.TARGET_TEXT, true));
        }
        p.addView(Wu.space(this, 8));

        detailsToggle(p);
        if (detailsOpen) {
            p.addView(Wu.space(this, 8));
            p.addView(Wu.text(this, plan.message, 16, Wu.MUTED, false));
            for (CoachLogic.Step st : plan.steps) {
                p.addView(Wu.space(this, 8));
                String zones = st.zoneLow == st.zoneHigh ? "Z" + st.zoneLow
                        : "Z" + st.zoneLow + "\u2013" + st.zoneHigh;
                p.addView(Wu.text(this, st.name + " \u00b7 " + st.minutes + " min \u00b7 " + zones,
                        16, Wu.TEXT, true));
            }
            for (String a : plan.adjusted) {
                p.addView(Wu.space(this, 8));
                p.addView(Wu.text(this, a, 15, Wu.ZONE[4], false));
            }
        }
        p.addView(Wu.space(this, 8));

        Button not = Wu.button(this, "Not today", Wu.BUTTON, Wu.TEXT, 48);
        not.setOnClickListener(v -> change(CoachLogic.notToday(plan, plan.date),
                "tapped Not today"));
        p.addView(not);
        p.addView(Wu.space(this, 6));
        Button shorter = Wu.button(this, "Shorter", Wu.BUTTON, Wu.TEXT, 48);
        shorter.setOnClickListener(v -> {
            CoachLogic.Plan s = CoachLogic.shorter(plan);
            if (s == null) {
                Toast.makeText(this, "Already as short as it gets", Toast.LENGTH_SHORT).show();
            } else {
                change(s, "tapped Shorter");
            }
        });
        p.addView(shorter);
        p.addView(Wu.space(this, 6));
        Button harder = Wu.button(this, "Harder", Wu.BUTTON, Wu.TEXT, 48);
        harder.setEnabled(!coach.isPlanning());
        harder.setOnClickListener(v -> {
            previous = CoachLogic.copy(plan);
            error = null;
            WorkService.hold(this);
            coach.harder(plan, (np, err) -> {
                WorkService.release(this);
                error = err;
                if (err != null) {
                    previous = null;
                }
                if (!isFinishing()) {
                    build();
                }
            });
            build();
        });
        p.addView(harder);
        p.addView(Wu.space(this, 6));
        // After changing max heart rate, age or weight, the plan is worth making again.
        Button again = Wu.button(this, "Plan again", Wu.BUTTON, Wu.TEXT, 48);
        again.setEnabled(!coach.isPlanning());
        again.setOnClickListener(v -> refreshPlan("They changed their settings (max heart rate, "
                + "age or weight); make today's plan again with them."));
        p.addView(again);
        if (previous != null) {
            p.addView(Wu.space(this, 6));
            Button undo = Wu.button(this, "Undo", Wu.BUTTON, Wu.MUTED, 48);
            undo.setOnClickListener(v -> {
                coach.replacePlan(previous, "undid the last change");
                previous = null;
                error = null;
                build();
            });
            p.addView(undo);
        }
    }

    /** Shows or folds away the coach's message, the steps and the notes. */
    private void detailsToggle(LinearLayout p) {
        Button b = Wu.button(this, detailsOpen ? "Hide details" : "Details", Wu.CARD, Wu.TEXT, 48);
        b.setOnClickListener(v -> {
            detailsOpen = !detailsOpen;
            build();
        });
        p.addView(b);
    }

    /** "30 min \u00b7 zone 2": the length and the zone of the longest step. */
    private static String summaryLine(CoachLogic.Plan plan) {
        CoachLogic.Step main = plan.steps.get(0);
        for (CoachLogic.Step st : plan.steps) {
            if (st.minutes > main.minutes) {
                main = st;
            }
        }
        String zone = main.zoneLow == main.zoneHigh ? "zone " + main.zoneLow
                : "zones " + main.zoneLow + "\u2013" + main.zoneHigh;
        return plan.totalMinutes() + " min \u00b7 " + zone;
    }

    /** The plan's steps as coloured bars sized by duration. */
    private View stepsBar(CoachLogic.Plan plan) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(Wu.lp(ViewGroup.LayoutParams.MATCH_PARENT, Wu.px(this, 18)));
        for (CoachLogic.Step st : plan.steps) {
            View seg = new View(this);
            seg.setBackground(Wu.pill(Wu.ZONE[Math.max(1, Math.min(5, st.zoneHigh))], this, 5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, st.minutes));
            lp.rightMargin = Wu.px(this, 2);
            bar.addView(seg, lp);
        }
        return bar;
    }

    /** A one-tap change made on the watch: instant, and undoable. */
    private void change(CoachLogic.Plan changed, String what) {
        previous = CoachLogic.copy(coach.todayPlan());
        coach.replacePlan(changed, what);
        error = null;
        build();
    }

    private void refreshPlan(String reason) {
        if (coach.isPlanning()) {
            return;
        }
        CoachLogic.Plan current = coach.todayPlan();
        previous = current == null ? null : CoachLogic.copy(current);
        error = null;
        WorkService.hold(this);
        coach.refreshPlan(reason, (p, err) -> {
            WorkService.release(this);
            error = err;
            if (!isFinishing()) {
                build();
            }
        });
        build();
    }

    // ------------------------------------------------------------------------ setup

    private void buildSetup() {
        LinearLayout p = page();
        p.addView(Wu.text(this, "Coach settings", 18, Wu.MUTED, true));
        p.addView(Wu.space(this, 8));

        keyInput = new EditText(this);
        keyInput.setHint(coach.hasKey() ? "Key saved — type to replace" : "DeepSeek API key");
        keyInput.setHintTextColor(Wu.MUTED);
        keyInput.setTextColor(Wu.TEXT);
        keyInput.setSingleLine();
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setLayoutParams(Wu.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        keyInput.setText(keyDraft);
        p.addView(keyInput);
        p.addView(Wu.space(this, 8));

        p.addView(cycle("Goal", goal, () -> goal = next(GOALS, goal)));
        p.addView(Wu.space(this, 6));
        p.addView(cycle("Days a week", String.valueOf(days), () -> days = nextInt(DAYS, days)));
        p.addView(Wu.space(this, 6));
        p.addView(cycle("Time on a weekday", minutes + " min",
                () -> minutes = nextInt(MINUTES, minutes)));
        p.addView(Wu.space(this, 6));
        p.addView(cycle("Work around", around, () -> around = next(AROUND, around)));
        p.addView(Wu.space(this, 6));
        p.addView(cycle("Plan ready at", morningLabel(morning),
                () -> morning = nextInt(MORNING, morning)));
        p.addView(Wu.space(this, 10));
        p.addView(Wu.text(this, "Sent to DeepSeek: session totals, zones, splits, notes, "
                + "age, sex, weight. Never GPS or your name.", 13, Wu.MUTED, false));
        p.addView(Wu.space(this, 12));

        Button save = Wu.button(this, "Save", Wu.ZONE[2], Wu.ON_ACCENT, 60);
        save.setOnClickListener(v -> save());
        p.addView(save);
        if (prefs.getBoolean("coachSetUp", false)) {
            p.addView(Wu.space(this, 6));
            Button back = Wu.button(this, "Back", Wu.BUTTON, Wu.MUTED, 48);
            back.setOnClickListener(v -> {
                editing = false;
                loadForm();
                build();
            });
            p.addView(back);
        }
        show(p);
    }

    /** One row that shows its value and moves to the next on a tap. */
    private View cycle(String label, String value, Runnable advance) {
        Button b = Wu.button(this, label + ": " + value, Wu.CARD, Wu.TEXT, 52);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Wu.textPx(this, 17));
        b.setOnClickListener(v -> {
            advance.run();
            b.setText(label + ": " + (label.equals("Goal") ? goal
                    : label.equals("Days a week") ? String.valueOf(days)
                    : label.equals("Time on a weekday") ? minutes + " min"
                    : label.equals("Work around") ? around : morningLabel(morning)));
        });
        return b;
    }

    private static String next(String[] values, String current) {
        int i = Arrays.asList(values).indexOf(current);
        return values[(i + 1) % values.length];
    }

    private static int nextInt(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private static String morningLabel(int minute) {
        return minute < 0 ? "Off"
                : String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private void save() {
        String key = keyInput.getText().toString().trim();
        boolean keyOk = true;
        if (!key.isEmpty()) {
            if (!key.startsWith("sk-")) {
                keyOk = false;
                Toast.makeText(this, "DeepSeek keys start with sk-", Toast.LENGTH_LONG).show();
            } else if (!coach.setKey(key)) {
                keyOk = false;
                Toast.makeText(this, "Couldn't store the key", Toast.LENGTH_LONG).show();
            }
        }
        if (!keyOk) {
            return;
        }
        keyDraft = "";
        boolean changed = !goal.equals(prefs.getString("coachGoal", ""))
                || days != prefs.getInt("coachDays", -1)
                || minutes != prefs.getInt("coachMinutes", -1);
        prefs.edit().putBoolean("coachOn", true).putBoolean("coachSetUp", true)
                .putString("coachGoal", goal).putInt("coachDays", days)
                .putInt("coachMinutes", minutes).putString("coachWorkAround", around)
                .putInt("coachMorning", morning).apply();
        coach.schedule();
        editing = false;
        error = null;
        if (!coach.hasKey()) {
            Toast.makeText(this, "Saved. Add your DeepSeek key to switch the coach on",
                    Toast.LENGTH_LONG).show();
        } else if (coach.isEnabled() && !coach.isPlanning()
                && (changed || coach.todayPlan() == null || !key.isEmpty())) {
            WorkService.hold(this);
            coach.refreshPlan(changed ? "They just changed their goal or availability."
                    : "They just set up the coach.", (p, err) -> {
                WorkService.release(this);
                error = err;
                if (!isFinishing()) {
                    build();
                }
            });
        }
        build();
    }
}
