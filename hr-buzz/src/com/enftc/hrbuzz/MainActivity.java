package com.enftc.hrbuzz;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Home: strap and live heart rate, alarm limits, sport picker, Start, latest sessions. */
public class MainActivity extends Activity implements HrManager.Listener, Recorder.Listener {

    private static final int REQ_PERMISSIONS = 1;
    private static final int REQ_ENABLE_BT = 2;
    private static final int REQ_LOCATION = 3;
    private static final int REQ_ENABLE_BT_START = 4;
    private static final int REQ_IMPORT = 5;

    /** Intent extra: start today's coach plan straight away (the morning notification's Start). */
    static final String EXTRA_START_PLAN = "startPlan";

    private TextView bpmView;
    private TextView statusView;
    private TextView zoneView;
    private TextView sensorView;
    private TextView contactView;
    private TextView batteryHint;
    private EditText maxInput;
    private EditText minInput;
    private Switch alarmSwitch;
    private Button connectButton;
    private Button startButton;
    /** Sport picker and Start: hidden behind a link while today's plan waits to be followed. */
    private View freeStart;
    private TextView freeLink;
    private boolean freeMode;
    private LinearLayout sportsRow;
    private LinearLayout sessionsList;
    private TextView noSessions;
    private LinearLayout coachCard;
    private LinearLayout moveCard;
    /** The next session to start follows the coach plan (survives the permission prompts). */
    private boolean coachedStart;
    private String coachError;
    private String autoPlannedFor = "";
    /** Today's plan before the last one-tap change, for Undo. */
    private CoachLogic.Plan previousPlan;
    private boolean asking;

    private HrManager hr;
    private Recorder recorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.darkBars(this);

        hr = HrManager.get(this);
        recorder = Recorder.get(this);

        bpmView = findViewById(R.id.bpm);
        statusView = findViewById(R.id.status);
        zoneView = findViewById(R.id.zone);
        sensorView = findViewById(R.id.sensor);
        contactView = findViewById(R.id.contact);
        batteryHint = findViewById(R.id.batteryHint);
        maxInput = findViewById(R.id.maxHr);
        minInput = findViewById(R.id.minHr);
        alarmSwitch = findViewById(R.id.alarmOn);
        connectButton = findViewById(R.id.connect);
        startButton = findViewById(R.id.start);
        freeStart = findViewById(R.id.freeStart);
        freeLink = findViewById(R.id.freeLink);
        sportsRow = findViewById(R.id.sports);
        sessionsList = findViewById(R.id.sessions);
        noSessions = findViewById(R.id.noSessions);
        coachCard = findViewById(R.id.coachCard);
        moveCard = findViewById(R.id.moveCard);

        maxInput.setText(String.valueOf(hr.getUserThreshold()));
        Ui.onNumber(maxInput, v -> hr.setThreshold(v));
        int low = hr.getUserLowLimit();
        minInput.setText(low > 0 ? String.valueOf(low) : "");
        Ui.onNumber(minInput, v -> hr.setLowLimit(v));
        alarmSwitch.setChecked(hr.isAlarmOn());
        alarmSwitch.setOnCheckedChangeListener((b, on) -> hr.setAlarmOn(on));

        String chosen = Prefs.sport(this);
        for (int i = 0; i < Prefs.SPORTS.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(this, Prefs.SPORTS[i]);
            chip.setOnClickListener(v -> {
                Ui.select(sportsRow, index);
                Prefs.of(this).edit().putString("sport", Prefs.SPORTS[index]).apply();
            });
            sportsRow.addView(chip);
            if (Prefs.SPORTS[i].equals(chosen)) {
                chip.setSelected(true);
            }
        }

        connectButton.setOnClickListener(v -> toggle());
        startButton.setOnClickListener(v -> {
            coachedStart = false;
            startSession();
        });
        freeLink.setOnClickListener(v -> {
            freeMode = true;
            showRecorder();
        });
        findViewById(R.id.settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.fitnessTest).setOnClickListener(
                v -> startActivity(new Intent(this, FitnessTestActivity.class)));
        batteryHint.setOnClickListener(v -> requestUnrestrictedBattery(this));
        findViewById(R.id.importSessions).setOnClickListener(v -> pickImport());

        requestNeededPermissions();
        handleIntent(getIntent());
        // Keeps Auto max heart rate current (and works out peaks of older sessions once).
        new Thread(() -> Prefs.refreshHrMaxSeen(getApplicationContext()), "hrmax-scan").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_START_PLAN, false)) {
            intent.removeExtra(EXTRA_START_PLAN);
            startPlan();
            return;
        }
        // A TCX file opened with, or shared to, HR Buzz.
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (u != null) {
                uris.add(u);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (list != null) {
                uris.addAll(list);
            }
        }
        if (!uris.isEmpty()) {
            intent.setAction(Intent.ACTION_MAIN);
            importFiles(uris);
        }
    }

    // ----------------------------------------------------------------- import

    private void pickImport() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    /** Reads TCX files off the main thread and reports what came in, one line per file. */
    private void importFiles(List<Uri> uris) {
        Toast.makeText(this, "Importing\u2026", Toast.LENGTH_SHORT).show();
        Context app = getApplicationContext();
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            int added = 0;
            SimpleDateFormat when = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
            for (Uri uri : uris) {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    Import.Result r = Import.tcx(in);
                    SessionStore.Summary s = SessionStore.importSession(app, r);
                    if (s == null) {
                        report.append(when.format(new Date(r.start))).append(": already in HR Buzz\n");
                        continue;
                    }
                    added++;
                    report.append(s.sport).append(", ").append(when.format(new Date(s.id)))
                            .append(": ").append(Training.duration(s.activeMs));
                    if (s.distanceM > 0) {
                        report.append(", ").append(Training.km(s.distanceM)).append(" km");
                    }
                    if (s.avgHr > 0) {
                        report.append(", avg HR ").append(s.avgHr);
                    }
                    if (r.pauses > 0) {
                        report.append(", ").append(r.pauses)
                                .append(r.pauses == 1 ? " pause" : " pauses");
                    }
                    report.append('\n');
                } catch (Import.ImportException | java.io.IOException | SecurityException e) {
                    report.append("Skipped a file: ").append(e instanceof Import.ImportException
                            ? e.getMessage() : "couldn't read it").append('\n');
                }
            }
            if (added > 0) {
                Prefs.refreshHrMaxSeen(app);
            }
            final int count = added;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                showSessions();
                new android.app.AlertDialog.Builder(this)
                        .setTitle(count == 0 ? "Nothing imported"
                                : "Imported " + count + (count == 1 ? " session" : " sessions"))
                        .setMessage(report.toString().trim())
                        .setPositiveButton("OK", null)
                        .show();
                Coach coach = Coach.get(this);
                if (count > 0 && coach.isEnabled() && !coach.todayDone()) {
                    refreshPlan("They just imported recent sessions from another app; plan "
                            + "today with that history in mind.");
                }
            });
        }, "import").start();
    }

    // ------------------------------------------------------------------ move minutes

    /** Shows the last count at once, then reads Health Connect again and updates it. */
    private void showMove() {
        if (!Prefs.moveOn(this) || !HealthSync.granted(this)) {
            moveCard.setVisibility(View.GONE);
            return;
        }
        drawMove(HealthSync.cached(this));
        Context app = getApplicationContext();
        new Thread(() -> {
            Move.Summary fresh = HealthSync.sync(app);
            if (fresh != null) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        drawMove(fresh);
                    }
                });
            }
        }, "move-sync").start();
    }

    private void drawMove(Move.Summary move) {
        moveCard.removeAllViews();
        moveCard.setVisibility(View.VISIBLE);
        moveCard.addView(label("MOVE MINUTES"));
        int[] week = move == null ? null : move.lastWeek(java.time.LocalDate.now());
        if (week == null || week[0] < 0) {
            moveCard.addView(gap(Ui.text(this, "Reading Health Connect\u2026", 15, Ui.TEXT2), 6));
            return;
        }
        int total = Move.Summary.total(week);
        if (Move.farAboveGoal(total)) {
            drawRecordedWeek(total);
            return;
        }
        moveCard.addView(gap(Ui.bold(Ui.text(this, week[0] + " min today", 24, Ui.TEXT)), 6));
        moveCard.addView(gap(Ui.text(this, total + " min in the last 7 days (goal "
                + Move.WEEKLY_GOAL + ")", 15, 0xFFC8C8CE), 4));
        String level = Move.level(week);
        if (!level.equals("unknown")) {
            moveCard.addView(gap(Ui.text(this, "Lately: " + level, 13, Ui.TEXT2), 4));
        }
    }

    /** For someone far past the 150 min guide, what HR Buzz recorded says more than a goal. */
    private void drawRecordedWeek(int moving) {
        List<long[]> mine = new ArrayList<>();
        for (SessionStore.Summary s : SessionStore.list(this)) {
            if (!s.recording) {
                mine.add(new long[]{s.id, s.activeMs, (long) (s.gps ? s.distanceM : 0)});
            }
        }
        long[] w = Move.recordedWeek(mine, System.currentTimeMillis());
        moveCard.removeAllViews();
        moveCard.addView(label("LAST 7 DAYS"));
        moveCard.addView(gap(Ui.bold(Ui.text(this, Training.km(w[2]) + " km recorded", 24,
                Ui.TEXT)), 6));
        moveCard.addView(gap(Ui.text(this, w[0] + (w[0] == 1 ? " session, " : " sessions, ")
                + Training.duration(w[1]), 15, 0xFFC8C8CE), 4));
        moveCard.addView(gap(Ui.text(this, "Moving " + moving + " min in all, well past the "
                + Move.WEEKLY_GOAL + " min guide", 13, Ui.TEXT2), 4));
    }

    // ------------------------------------------------------------------ coach

    /** Today's plan when it is there to follow: a training day, not done, nothing recording. */
    private CoachLogic.Plan pendingPlan() {
        Coach coach = Coach.get(this);
        CoachLogic.Plan plan = coach.isEnabled() && !recorder.isRecording()
                && !coach.todayDone() ? coach.todayPlan() : null;
        return plan != null && !plan.rest ? plan : null;
    }

    private void startPlan() {
        if (Coach.get(this).todayPlan() == null) {
            Toast.makeText(this, "No plan for today yet", Toast.LENGTH_SHORT).show();
            return;
        }
        coachedStart = true;
        startSession();
    }

    private final Runnable planPoll = () -> {
        if (!isFinishing() && !isDestroyed()) {
            showCoach();
        }
    };

    private void showCoach() {
        showCoachCard();
        // The check-in comes after the plan: the plan is what they opened the app for.
        Coach coach = Coach.get(this);
        if (coach.isEnabled() && !coach.todayDone()) {
            showCheckIn(coach);
        }
    }

    private void showCoachCard() {
        coachCard.removeAllViews();
        CoachLogic.Plan waiting = pendingPlan();
        showRecorder(waiting);      // its label depends on whether a plan is waiting
        Coach coach = Coach.get(this);
        if (!coach.isEnabled()) {
            coachCard.addView(label("COACH"));
            coachCard.addView(gap(Ui.text(this, "A coach that plans each day around your goal, "
                    + "sets your heart rate alerts for you and talks you through the session. "
                    + "Uses the AI service you choose (OpenAI, Claude, Gemini or DeepSeek) with "
                    + "your own API key; off until you set it up.", 15, 0xFFC8C8CE), 6));
            Button setup = secondary("Set up the coach");
            setup.setOnClickListener(v -> startActivity(new Intent(this, CoachSetupActivity.class)));
            coachCard.addView(setup, btnLp(10));
            return;
        }

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView headLabel = label(waiting != null ? "TODAY'S PLAN" : "COACH \u00b7 TODAY");
        if (waiting != null) {
            headLabel.setTextColor(0xFF34C759);
        }
        head.addView(headLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView open = Ui.text(this, "Open coach", 14, Ui.TEXT);
        open.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), 0, Ui.dp(this, 10));
        open.setOnClickListener(v -> startActivity(new Intent(this, CoachActivity.class)));
        head.addView(open);
        coachCard.addView(head);

        CoachLogic.Readiness r = coach.readiness();
        if (r.level != CoachLogic.Readiness.UNKNOWN) {
            coachCard.addView(gap(Ui.text(this, r.text, 13,
                    r.level == CoachLogic.Readiness.READY ? 0xFF34C759 : 0xFFFFC400), 2));
        }

        CoachLogic.Plan plan = coach.todayPlan();
        if (plan == null) {
            if (coach.isPlanning()) {
                coachCard.addView(gap(Ui.text(this, "Planning your day\u2026", 17, Ui.TEXT), 6));
                // A plan can be started from another screen (setup), with nobody to tell this
                // card when it ends: look again until it has.
                coachCard.removeCallbacks(planPoll);
                coachCard.postDelayed(planPoll, 1500);
                return;
            }
            coachCard.addView(gap(Ui.text(this, coachError != null ? coachError
                    : "No plan for today yet.", 15, coachError != null ? 0xFFFF6961 : 0xFFC8C8CE), 6));
            boolean keyProblem = coachError != null && coachError.contains("API key");
            Button plan1 = secondary(keyProblem ? "Open coach settings"
                    : coachError != null ? "Try again" : "Plan my day");
            plan1.setOnClickListener(v -> {
                if (keyProblem) {
                    coachError = null;
                    startActivity(new Intent(this, CoachSetupActivity.class));
                } else {
                    refreshPlan("They asked for today's plan.");
                }
            });
            coachCard.addView(plan1, btnLp(10));
            return;
        }

        if (coach.todayDone()) {
            coachCard.addView(gap(Ui.bold(Ui.text(this, "Done for today \u2713", 24, Ui.TEXT)), 6));
            coachCard.addView(gap(Ui.text(this, plan.title + " is in the bag. The next plan "
                    + "arrives tomorrow morning.", 15, 0xFFC8C8CE), 4));
            return;
        }

        String title = plan.rest ? plan.title : plan.title + ", " + plan.totalMinutes() + " min";
        coachCard.addView(gap(Ui.bold(Ui.text(this, title, 24, Ui.TEXT)), 6));
        double km = plan.rest ? 0 : coach.estimatedKm(plan);
        if (km > 0) {
            coachCard.addView(gap(Ui.text(this, "About " + CoachLogic.kmText(km)
                    + " km at your usual pace", 13, Ui.TEXT2), 2));
        }
        if (!plan.message.isEmpty()) {
            // The lead only: the whole note is in the coach screen.
            String lead = CoachLogic.shortMessage(plan.message, 180);
            TextView note = Ui.text(this, lead, 15, 0xFFC8C8CE);
            note.setOnClickListener(v -> startActivity(new Intent(this, CoachActivity.class)));
            coachCard.addView(gap(note, 4));
            if (!lead.equals(plan.message)) {
                TextView more = Ui.text(this, "Read the full note", 13, 0xFF4F8EF7);
                more.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
                more.setOnClickListener(v -> startActivity(new Intent(this, CoachActivity.class)));
                coachCard.addView(more);
            }
        }
        if (!plan.rest) {
            coachCard.addView(Ui.stepsBar(this, plan), buttonLp(12));
            int[] lim = mainLimits(plan);
            coachCard.addView(gap(Ui.text(this, "Alerts set for you: beep above " + lim[0]
                    + (lim[1] > 0 ? ", below " + lim[1] : "")
                    + (hr.isAlarmOn() ? "" : ". Your alarm switch is off; alerts turn on for "
                    + "this session only"), 13, 0xFFC8C8CE), 8));
            if (waiting != null) {
                coachCard.addView(gap(Ui.text(this, "Not started yet. Tap Start below to follow "
                        + "it: alerts and spoken steps only run in a planned session.",
                        13, 0xFF34C759), 6));
            }
        }
        for (String a : plan.adjusted) {
            coachCard.addView(gap(Ui.text(this, "\u2022 " + a, 13, 0xFFFFC400), 4));
        }
        if (!plan.rest) {
            Button start = new Button(this, null, 0, R.style.PrimaryButton);
            start.setText(recorder.isRecording() ? "Session in progress \u2014 open" : "Start this run");
            if (!"Running".equals(plan.sport)) {
                start.setText(recorder.isRecording() ? "Session in progress \u2014 open"
                        : "Start this session");
            }
            start.setAllCaps(false);
            start.setOnClickListener(v -> startPlan());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
            lp.topMargin = Ui.dp(this, 14);
            coachCard.addView(start, lp);
        }
        if (coach.isPlanning()) {
            coachCard.addView(gap(Ui.text(this, "Updating today's plan\u2026", 14, Ui.TEXT2), 10));
        } else if (coachError != null) {
            coachCard.addView(gap(Ui.text(this, coachError, 14, 0xFFFF6961), 10));
        }
        LinearLayout chips = new LinearLayout(this);
        if (plan.rest) {
            chips.addView(chip("I want to train", () -> refreshPlan("They want to train today "
                    + "after all; plan something suitable.")), chipLp());
        } else {
            chips.addView(chip("Not today", () -> change(CoachLogic.notToday(plan, plan.date),
                    "tapped Not today")), chipLp());
            chips.addView(chip("Shorter", () -> {
                CoachLogic.Plan shorter = CoachLogic.shorter(plan);
                if (shorter == null) {
                    Toast.makeText(this, "It's already as short as it gets", Toast.LENGTH_SHORT)
                            .show();
                } else {
                    change(shorter, "tapped Shorter");
                }
            }), chipLp());
            chips.addView(chip("Harder", () -> {
                previousPlan = CoachLogic.copy(plan);
                coachError = null;
                Coach.get(this).harder(plan, (p, err) -> {
                    coachError = err;
                    if (err != null) {
                        previousPlan = null;
                    }
                    if (!isFinishing()) {
                        showCoach();
                    }
                });
                showCoach();
            }), chipLp());
        }
        if (previousPlan != null) {
            chips.addView(chip("Undo", () -> {
                Coach.get(this).replacePlan(previousPlan, "undid the last change");
                previousPlan = null;
                coachError = null;
                showCoach();
            }), chipLp());
        }
        coachCard.addView(chips, buttonLp(10));
    }

    /** A one-tap change made on the phone: instant, and undoable. */
    private void change(CoachLogic.Plan next, String what) {
        CoachLogic.Plan current = Coach.get(this).todayPlan();
        previousPlan = current == null ? null : CoachLogic.copy(current);
        coachError = null;
        Coach.get(this).replacePlan(next, what);
        showCoach();
    }

    private static final String[] SLEPT = {"Well", "OK", "Badly"};
    private static final String[] GUT = {"Fine", "Off"};

    /**
     * The morning check-in (how they slept, how their digestion is) until both are answered,
     * and the resting test while there is none from the last day: what readiness runs on.
     */
    private void showCheckIn(Coach coach) {
        int[] answers = coach.checkIn();
        if (answers[0] == 0 || answers[1] == 0) {
            coachCard.addView(checkInRow("Slept", SLEPT, answers[0],
                    v -> coach.setCheckIn(v, -1)), btnLp(8));
            coachCard.addView(checkInRow("Digestion", GUT, answers[1],
                    v -> coach.setCheckIn(-1, v)), btnLp(6));
        }
        if (testLinkDue(coach)) {
            TextView test = Ui.text(this, "Take the 5-minute resting test, so today matches how "
                    + "you recovered", 13, 0xFF4F8EF7);
            test.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
            test.setOnClickListener(v -> startActivity(new Intent(this, FitnessTestActivity.class)));
            coachCard.addView(test);
        }
    }

    private static final int MAX_TEST_NAG_DAYS = 3;

    /**
     * Whether to offer the resting test on the card: only once the last one is a week old, and
     * on at most {@link #MAX_TEST_NAG_DAYS} days in a row of being ignored, so a person who does
     * not do them is not asked every morning. Doing a test starts the count again.
     */
    private boolean testLinkDue(Coach coach) {
        List<CoachLogic.Test> tests = Prefs.fitnessTests(this);
        long last = tests.isEmpty() ? 0 : tests.get(tests.size() - 1).t;
        android.content.SharedPreferences p = Prefs.of(this);
        int nags = p.getInt("testNags", 0);
        if (!CoachLogic.testDue(last, System.currentTimeMillis())) {
            if (nags != 0) {
                p.edit().putInt("testNags", 0).apply();
            }
            return false;
        }
        String today = coach.today();
        if (today.equals(p.getString("testNagDay", ""))) {
            return true;                // already counted today
        }
        if (nags >= MAX_TEST_NAG_DAYS) {
            return false;
        }
        p.edit().putInt("testNags", nags + 1).putString("testNagDay", today).apply();
        return true;
    }

    /** A label and chips; tapping saves 1-based, tapping the chosen one again clears it. */
    private LinearLayout checkInRow(String label, String[] options, int chosen,
                                    java.util.function.IntConsumer save) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(this, label, 14, Ui.TEXT2);
        row.addView(name, new LinearLayout.LayoutParams(Ui.dp(this, 84),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout chips = new LinearLayout(this);
        for (int i = 0; i < options.length; i++) {
            final int value = i + 1;
            TextView chip = Ui.chip(this, options[i]);
            chip.setTextSize(14);
            chip.setSelected(chosen == value);
            chip.setOnClickListener(v -> {
                int next = v.isSelected() ? 0 : value;
                Ui.select(chips, next - 1);
                save.accept(next);
                int[] a = Coach.get(this).checkIn();
                if (a[0] > 0 && a[1] > 0) {
                    showCoach();        // both answered: the check-in folds away
                }
            });
            chips.addView(chip);
        }
        row.addView(chips);
        return row;
    }

    private Button chip(String text, Runnable action) {
        Button b = secondary(text);
        b.setTextSize(14);
        boolean busy = Coach.get(this).isPlanning();
        b.setEnabled(!busy);
        b.setAlpha(busy ? 0.4f : 1f);
        b.setOnClickListener(v -> {
            if (!Coach.get(this).isPlanning()) {
                action.run();
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        lp.rightMargin = Ui.dp(this, 6);
        return lp;
    }

    private void refreshPlan(String reason) {
        if (Coach.get(this).isPlanning()) {
            return;
        }
        CoachLogic.Plan current = Coach.get(this).todayPlan();
        previousPlan = current == null ? null : CoachLogic.copy(current);
        coachError = null;
        Coach.get(this).refreshPlan(reason, (p, err) -> {
            coachError = err;
            if (!isFinishing()) {
                showCoach();
            }
        });
        showCoach();
    }

    /** The limits of the longest step: what "alerts set for you" means at a glance. */
    private int[] mainLimits(CoachLogic.Plan plan) {
        CoachLogic.Step main = plan.steps.get(0);
        for (CoachLogic.Step st : plan.steps) {
            if (st.minutes > main.minutes) {
                main = st;
            }
        }
        return CoachLogic.stepLimits(main, Prefs.hrMax(this), hr.getUserThreshold(),
                Coach.get(this).conversationalBpm());
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 12, Ui.TEXT2);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private <T extends View> T gap(T v, int topDp) {
        v.setPadding(v.getPaddingLeft(), Ui.dp(this, topDp), v.getPaddingRight(),
                v.getPaddingBottom());
        return v;
    }

    private Button secondary(String text) {
        Button b = new Button(this, null, 0, R.style.SecondaryButton);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams btnLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    /** Plans the day by itself the first time the app is opened on a day with no plan. */
    private void autoPlan() {
        Coach coach = Coach.get(this);
        String today = coach.today();
        if (!coach.isEnabled() || coach.todayPlan() != null || coach.isPlanning()
                || today.equals(autoPlannedFor)) {
            return;
        }
        autoPlannedFor = today;
        refreshPlan("They opened the app and there is no plan for today yet.");
    }

    // ------------------------------------------------------------- connection

    /**
     * Like Polar Beat, look for the strap as soon as the app is opened, so the heart rate is
     * already live by the time you press Start. Silent: no prompts if Bluetooth is off.
     */
    private void autoConnect() {
        if (hr.isActive() || !hasBluetoothPermissions()
                || !Prefs.of(this).getBoolean("autoConnect", true)) {
            return;
        }
        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }
        startForegroundService(new Intent(this, LiveService.class));
        hr.start();
    }

    private void toggle() {
        if (hr.isActive()) {
            if (recorder.isRecording()) {
                Toast.makeText(this, "Finish the session first", Toast.LENGTH_SHORT).show();
                return;
            }
            hr.stop();
            stopService(new Intent(this, LiveService.class));
            connectButton.setText("Connect");
            // Disconnecting by hand means "leave it off", until Connect is pressed again.
            Prefs.of(this).edit().putBoolean("autoConnect", false).apply();
            return;
        }
        if (!bluetoothReady(REQ_ENABLE_BT)) {
            return;
        }
        startForegroundService(new Intent(this, LiveService.class));
        hr.start();
        connectButton.setText("Disconnect");
        Prefs.of(this).edit().putBoolean("autoConnect", true).apply();
    }

    /** Asks for Bluetooth on / permissions as needed; true when ready to go. */
    private boolean bluetoothReady(int enableRequest) {
        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            Toast.makeText(this, "No Bluetooth on this device", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!hasBluetoothPermissions()) {
            requestNeededPermissions();
            return false;
        }
        if (!adapter.isEnabled()) {
            try {
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), enableRequest);
            } catch (Exception e) {
                Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show();
            }
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- session

    private void startSession() {
        if (recorder.isRecording()) {
            startActivity(new Intent(this, TrainingActivity.class));
            return;
        }
        if (!bluetoothReady(REQ_ENABLE_BT_START)) {
            return;
        }
        CoachLogic.Plan plan = coachedStart ? Coach.get(this).todayPlan() : null;
        String sport = plan != null ? plan.sport : Prefs.sport(this);
        if (Prefs.usesGps(sport)
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        launch(sport);
    }

    private void launch(String sport) {
        LiveService.record(this, sport, coachedStart);
        coachedStart = false;
        startActivity(new Intent(this, TrainingActivity.class));
    }

    // ------------------------------------------------------------ permissions

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestNeededPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasBluetoothPermissions()) {
                autoConnect();
            } else {
                statusView.setText("Bluetooth permission denied");
            }
        } else if (requestCode == REQ_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "No location permission: recording heart rate only, "
                        + "without distance or route", Toast.LENGTH_LONG).show();
            }
            CoachLogic.Plan plan = coachedStart ? Coach.get(this).todayPlan() : null;
            launch(plan != null ? plan.sport : Prefs.sport(this));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == REQ_IMPORT && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            importFiles(uris);
            return;
        }
        if (requestCode == REQ_ENABLE_BT) {
            toggle();
        } else if (requestCode == REQ_ENABLE_BT_START) {
            startSession();
        }
    }

    static boolean batteryUnrestricted(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static void requestUnrestrictedBattery(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        }
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        hr.addListener(this);
        recorder.addListener(this);
        autoConnect();
        connectButton.setText(hr.isActive() ? "Disconnect" : "Connect");
        // Catch up with whatever happened while the screen was away.
        statusView.setText(hr.getState());
        showBpm(hr.getBpm(), hr.isAbove() || hr.isBelow());
        showSensor();
        showRecorder();
        showSessions();
        Ui.show(batteryHint, !batteryUnrestricted(this));
        showCoach();
        showMove();
        Coach.get(this).scheduleIfMissing();
        autoPlan();
        askProfile();
        Ui.select(sportsRow, java.util.Arrays.asList(Prefs.SPORTS).indexOf(Prefs.sport(this)));
    }

    /** First run asks for the profile; after that, whether a new peak is a real max. */
    private void askProfile() {
        if (asking) {
            return;
        }
        if (ProfileDialog.needed(this)) {
            asking = true;
            ProfileDialog.show(this, () -> {
                asking = false;
                maxInput.setText(String.valueOf(hr.getUserThreshold()));
                showBpm(hr.getBpm(), hr.isAbove() || hr.isBelow());
                showCoach();
            });
            return;
        }
        int peak = Prefs.hrMaxPending(this);
        if (peak <= 0) {
            return;
        }
        int now = Prefs.hrMax(this);
        int usual = Training.ageHrMax(Prefs.age(this));
        String when = new SimpleDateFormat("d MMM", Locale.getDefault())
                .format(new Date(Prefs.of(this).getLong("hrMaxSeenAt", 0)));
        String msg = "A session on " + when + " held " + peak + " bpm for 10 seconds, above "
                + "the " + now + " your zones and alerts use now. Use " + peak + " as your max "
                + "heart rate?";
        if (peak > usual + 15) {
            msg += "\n\nThat is well above the usual for your age (" + usual + "). A dry strap, "
                    + "a synthetic shirt or static can make a strap read high: only use it if "
                    + "you really went all out.";
        }
        asking = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle("New max heart rate?")
                .setMessage(msg)
                .setPositiveButton("Use " + peak, (d, w) -> Prefs.decideHrMax(this, peak, true))
                .setNegativeButton("Not a real max",
                        (d, w) -> Prefs.decideHrMax(this, peak, false))
                .setOnDismissListener(d -> {
                    asking = false;
                    showBpm(hr.getBpm(), hr.isAbove() || hr.isBelow());
                    showCoach();
                })
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        hr.removeListener(this);
        recorder.removeListener(this);
    }

    private void showSessions() {
        sessionsList.removeAllViews();
        List<SessionStore.Summary> all = SessionStore.list(this);
        Ui.show(noSessions, all.isEmpty());
        SimpleDateFormat when = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        // totals per week, so each week can open with what it added up to
        java.util.Map<Long, long[]> weeks = new java.util.HashMap<>();
        for (SessionStore.Summary s : all) {
            if (!s.recording) {
                long[] w = weeks.computeIfAbsent(Training.weekStartDay(s.id, zone),
                        k -> new long[3]);
                w[0]++;
                w[1] += s.activeMs;
                w[2] += s.gps ? (long) s.distanceM : 0;
            }
        }
        long shownWeek = Long.MIN_VALUE;
        for (SessionStore.Summary s : all) {
            if (s.recording) {
                continue;
            }
            long week = Training.weekStartDay(s.id, zone);
            if (week != shownWeek) {
                shownWeek = week;
                sessionsList.addView(weekHeader(Training.weekLabel(week, now, zone), weeks.get(week)));
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.row);
            int p = Ui.dp(this, 14);
            row.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 8);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(Ui.bold(Ui.text(this, s.sport, 17, Ui.TEXT)));
            left.addView(Ui.text(this, when.format(new Date(s.id)), 13, Ui.TEXT2));
            if (!s.planTitle.isEmpty()) {
                left.addView(Ui.text(this, "Coached" + (s.targetPct >= 0
                        ? " \u00b7 " + s.targetPct + "% in target" : ""), 12, 0xFF4F8EF7));
            }
            row.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            right.addView(Ui.bold(Ui.text(this, Training.duration(s.activeMs), 17, Ui.TEXT)));
            StringBuilder sub = new StringBuilder();
            if (s.gps && s.distanceM > 0) {
                sub.append(Training.km(s.distanceM)).append(" km  ");
            }
            if (s.avgHr > 0) {
                sub.append("\u2665 ").append(s.avgHr);
            }
            right.addView(Ui.text(this, sub.toString().trim(), 13, Ui.TEXT2));
            row.addView(right);

            final long id = s.id;
            row.setOnClickListener(v -> startActivity(new Intent(this, SummaryActivity.class)
                    .putExtra(SummaryActivity.EXTRA_ID, id)));
            sessionsList.addView(row, lp);
        }
    }

    /** A week's name on the left, what it added up to on the right: km, time, sessions. */
    private View weekHeader(String name, long[] total) {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, Ui.dp(this, 16), 0, 0);
        TextView left = label(name.toUpperCase(Locale.getDefault()));
        h.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h.addView(Ui.text(this, (total[2] > 0 ? Training.km(total[2]) + " km \u00b7 " : "")
                + Training.duration(total[1]).replaceFirst(":\\d\\d$", "") + " \u00b7 " + total[0]
                + (total[0] == 1 ? " session" : " sessions"), 13, Ui.TEXT2));
        return h;
    }

    private void showRecorder() {
        showRecorder(recorder.isRecording() ? null : pendingPlan());
    }

    /** @param waiting today's plan, not started yet: {@link #pendingPlan} */
    private void showRecorder(CoachLogic.Plan waiting) {
        if (waiting == null) {
            freeMode = false;
        }
        // One way to start: the plan's own button, until they ask for a free session.
        boolean planOnly = waiting != null && !freeMode;
        freeStart.setVisibility(planOnly ? View.GONE : View.VISIBLE);
        freeLink.setVisibility(planOnly ? View.VISIBLE : View.GONE);
        if (recorder.isRecording()) {
            startButton.setText(recorder.getSession().sport + " in progress \u2014 open");
        } else {
            startButton.setText("Start");
        }
    }

    private void showSensor() {
        StringBuilder s = new StringBuilder();
        String name = hr.getDeviceName();
        if (name != null) {
            s.append(name);
        }
        int battery = hr.getBattery();
        if (battery >= 0) {
            s.append(s.length() > 0 ? "\n" : "").append("Battery ").append(battery).append('%');
        }
        sensorView.setText(s);
        Ui.show(contactView, hr.isConnected() && !hr.hasContact());
    }

    // ------------------------------------------------------------- HrManager

    @Override
    public void onState(String state) {
        runOnUiThread(() -> statusView.setText(state));
    }

    @Override
    public void onBpm(int bpm, boolean above) {
        runOnUiThread(() -> {
            boolean below = hr.isBelow();
            showBpm(bpm, above || below);
            if (above && bpm > hr.getThreshold()) {
                statusView.setText("Over " + hr.getThreshold() + " \u2014 ease off");
            } else if (above) {
                // latched by hysteresis: below the limit but not yet clear
                statusView.setText("Easing \u2014 below " + hr.getClearBelow() + " to clear");
            } else if (below) {
                statusView.setText("Under " + hr.getLowLimit() + " \u2014 pick it up");
            } else {
                statusView.setText("Live");
            }
        });
    }

    private void showBpm(int bpm, boolean alarming) {
        bpmView.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        int hrMax = Prefs.hrMax(this);
        int zone = Training.zone(bpm, hrMax);
        bpmView.setTextColor(alarming ? 0xFFFF453A : zone > 0 ? Ui.ZONE_COLORS[zone] : Ui.TEXT);
        if (bpm > 0) {
            zoneView.setText(zone > 0
                    ? "Zone " + zone + " \u00b7 " + Training.percentOfMax(bpm, hrMax) + "%"
                    : Training.percentOfMax(bpm, hrMax) + "% of max");
        } else {
            zoneView.setText("bpm");
        }
    }

    @Override
    public void onConnected(boolean connected) {
        runOnUiThread(() -> {
            connectButton.setText(hr.isActive() ? "Disconnect" : "Connect");
            if (!connected) {
                showBpm(-1, false);
            }
            showSensor();
        });
    }

    @Override
    public void onSensor() {
        runOnUiThread(this::showSensor);
    }

    @Override
    public void onRecorderUpdate() {
        // Only the state matters here, not every tick.
        runOnUiThread(this::showRecorder);
    }
}
