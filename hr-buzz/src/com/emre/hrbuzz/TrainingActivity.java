package com.emre.hrbuzz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The live training screen: big heart rate in zone colour, zone pointer, duration, distance,
 * pace and calories, with pause / resume and finish. Shows over the lock screen so a press of
 * the power button is enough to glance at it mid-run.
 */
public class TrainingActivity extends Activity implements Recorder.Listener, HrManager.Listener {

    private HrManager hr;
    private Recorder recorder;

    private TextView sportView;
    private TextView gpsView;
    private TextView statusView;
    private LinearLayout hrCard;
    private GradientDrawable hrCardBg;
    private TextView bpmView;
    private TextView zoneView;
    private ZoneStripView strip;
    private Ui.Tile[] tiles;
    private Button pauseButton;
    private Button walkButton;
    private Button finishButton;
    private TextView hint;
    private boolean gps;

    // coached workout
    private LinearLayout stepCard;
    private TextView stepLabel;
    private TextView stepLeft;
    private TextView stepText;
    private LinearLayout stepBar;
    private int shownStep = -2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        setShowWhenLocked(true);
        hr = HrManager.get(this);
        recorder = Recorder.get(this);
        if (!recorder.isRecording()) {
            finish();
            return;
        }
        gps = recorder.usesGps();
        if (Prefs.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 8), pad, pad);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        sportView = Ui.bold(Ui.text(this, "", 20, Ui.TEXT));
        top.addView(sportView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        gpsView = Ui.text(this, "", 14, Ui.TEXT2);
        top.addView(gpsView);
        root.addView(top);

        statusView = Ui.text(this, "", 15, Ui.TEXT2);
        statusView.setPadding(0, Ui.dp(this, 4), 0, 0);
        root.addView(statusView);

        buildStepCard(root);

        hrCard = new LinearLayout(this);
        hrCard.setOrientation(LinearLayout.VERTICAL);
        hrCard.setGravity(Gravity.CENTER_HORIZONTAL);
        hrCardBg = new GradientDrawable();
        hrCardBg.setCornerRadius(Ui.dp(this, 20));
        hrCardBg.setColor(0xFF1C1C1F);
        hrCard.setBackground(hrCardBg);
        hrCard.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 12));
        bpmView = Ui.bold(Ui.text(this, "--", 124, Ui.TEXT));
        bpmView.setIncludeFontPadding(false);
        bpmView.setGravity(Gravity.CENTER);
        hrCard.addView(bpmView);
        zoneView = Ui.text(this, "", 18, Ui.TEXT);
        zoneView.setGravity(Gravity.CENTER);
        hrCard.addView(zoneView);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = Ui.dp(this, 10);
        root.addView(hrCard, cardLp);

        strip = new ZoneStripView(this);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 34));
        stripLp.topMargin = Ui.dp(this, 10);
        root.addView(strip, stripLp);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[] captions = gps
                ? new String[]{"Duration", "Distance km", "Pace /km", "Avg pace /km",
                "Calories kcal", "Avg HR"}
                : new String[]{"Duration", "Calories kcal", "Avg HR", "Max HR"};
        tiles = Ui.grid(this, grid, captions, 2, 40);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridLp.topMargin = Ui.dp(this, 8);
        root.addView(grid, gridLp);

        root.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        walkButton = new Button(this, null, 0, R.style.SecondaryButton);
        walkButton.setText("Walk break");
        walkButton.setAllCaps(false);
        walkButton.setOnClickListener(v -> recorder.toggleWalkBreak());
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        wl.bottomMargin = Ui.dp(this, 10);
        root.addView(walkButton, wl);

        LinearLayout buttons = new LinearLayout(this);
        pauseButton = new Button(this, null, 0, R.style.SecondaryButton);
        pauseButton.setText("Pause");
        finishButton = new Button(this, null, 0, R.style.PrimaryButton);
        finishButton.setText("Finish");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1);
        LinearLayout.LayoutParams br = new LinearLayout.LayoutParams(0, Ui.dp(this, 64), 1);
        br.leftMargin = Ui.dp(this, 12);
        buttons.addView(pauseButton, bl);
        buttons.addView(finishButton, br);
        root.addView(buttons);

        hint = Ui.text(this, "", 13, Ui.TEXT3);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        wireButtons();
    }

    /** "Step 2 of 3 \u00b7 Steady \u00b7 14:20 left", the steps as a bar, and the target band. */
    private void buildStepCard(LinearLayout root) {
        CoachLogic.Plan plan = recorder.getPlan();
        if (plan == null) {
            return;
        }
        stepCard = new LinearLayout(this);
        stepCard.setOrientation(LinearLayout.VERTICAL);
        stepCard.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 12);
        stepCard.setPadding(p + 2, p, p + 2, p);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        stepLabel = Ui.text(this, "", 13, Ui.TEXT2);
        stepLabel.setLetterSpacing(0.06f);
        top.addView(stepLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stepLeft = Ui.bold(Ui.text(this, "", 20, Ui.TEXT));
        top.addView(stepLeft);
        stepCard.addView(top);

        stepBar = new LinearLayout(this);
        for (CoachLogic.Step st : plan.steps) {
            View seg = new View(this);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setCornerRadius(Ui.dp(this, 4));
            d.setColor(Ui.ZONE_COLORS[st.zoneHigh]);
            seg.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 8),
                    Math.max(1, st.minutes));
            lp.rightMargin = Ui.dp(this, 3);
            stepBar.addView(seg, lp);
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = Ui.dp(this, 8);
        barLp.bottomMargin = Ui.dp(this, 8);
        stepCard.addView(stepBar, barLp);

        stepText = Ui.text(this, "", 14, 0xFFC8C8CE);
        stepCard.addView(stepText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        root.addView(stepCard, lp);
    }

    private void updateStep() {
        CoachLogic.Plan plan = recorder.getPlan();
        if (stepCard == null || plan == null) {
            return;
        }
        int i = recorder.getStepIndex();
        int n = plan.steps.size();
        if (i >= n) {
            stepLabel.setText("PLAN DONE \u00b7 " + plan.title.toUpperCase());
            stepLeft.setText("");
            int pct = recorder.targetPct();
            stepText.setText((pct >= 0 ? pct + "% in target. " : "")
                    + "Keep going, or hold Finish.");
        } else if (i >= 0) {
            CoachLogic.Step st = plan.steps.get(i);
            stepLabel.setText("STEP " + (i + 1) + " OF " + n + " \u00b7 " + st.name.toUpperCase());
            stepLeft.setText(Training.duration(recorder.stepRemainingMs()));
            int high = hr.getThreshold();
            int low = hr.getLowLimit();
            StringBuilder t = new StringBuilder(low > 0 ? "Stay between " + low + " and " + high
                    : "Stay under " + high);
            if (i + 1 < n) {
                t.append(". Next: ").append(plan.steps.get(i + 1).name)
                        .append(' ').append(plan.steps.get(i + 1).minutes).append('\u2032');
            }
            int pct = recorder.targetPct();
            if (pct >= 0) {
                t.append(" \u00b7 ").append(pct).append("% in target");
            }
            stepText.setText(t);
        }
        if (i != shownStep) {
            shownStep = i;
            for (int k = 0; k < stepBar.getChildCount(); k++) {
                stepBar.getChildAt(k).setAlpha(k < i ? 0.55f : k == i ? 1f : 0.25f);
            }
        }
    }

    private void wireButtons() {
        boolean secure = Prefs.secureButtons(this);
        hint.setText(secure ? "Hold a button to pause or finish" : "");
        if (secure) {
            View.OnClickListener nag = v -> Toast.makeText(this,
                    "Hold to " + ((Button) v).getText().toString().toLowerCase(),
                    Toast.LENGTH_SHORT).show();
            pauseButton.setOnClickListener(nag);
            finishButton.setOnClickListener(nag);
            pauseButton.setOnLongClickListener(v -> {
                togglePause();
                return true;
            });
            finishButton.setOnLongClickListener(v -> {
                finishSession();
                return true;
            });
        } else {
            pauseButton.setOnClickListener(v -> togglePause());
            finishButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("Finish and save this session?")
                    .setPositiveButton("Finish", (d, w) -> finishSession())
                    .setNegativeButton("Keep going", null)
                    .show());
        }
    }

    private void togglePause() {
        startService(new Intent(this, LiveService.class).setAction(
                recorder.getState() == Recorder.PAUSED
                        ? LiveService.ACTION_RESUME : LiveService.ACTION_PAUSE));
    }

    private void finishSession() {
        startService(new Intent(this, LiveService.class).setAction(LiveService.ACTION_FINISH));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!recorder.isRecording()) {
            openSummaryAndClose();
            return;
        }
        recorder.addListener(this);
        hr.addListener(this);
        update();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (recorder != null) {
            recorder.removeListener(this);
            hr.removeListener(this);
        }
    }

    private void openSummaryAndClose() {
        long id = recorder.getLastSavedId();
        if (id > 0) {
            startActivity(new Intent(this, SummaryActivity.class)
                    .putExtra(SummaryActivity.EXTRA_ID, id));
        } else {
            Toast.makeText(this, "Too short to save", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void update() {
        if (!recorder.isRecording()) {
            openSummaryAndClose();
            return;
        }
        SessionStore.Summary s = recorder.getSession();
        Training.Stats st = recorder.getStats();
        boolean paused = recorder.getState() == Recorder.PAUSED;

        boolean walking = recorder.isWalkBreak();
        sportView.setText(paused ? s.sport + " \u00b7 paused"
                : walking ? s.sport + " \u00b7 walk break" : s.sport);
        // Only for running, and not while paused: a walk break keeps recording.
        walkButton.setVisibility("Running".equals(s.sport) ? View.VISIBLE : View.GONE);
        walkButton.setEnabled(!paused);
        walkButton.setText(walking ? "Back to running" : "Walk break");
        StringBuilder top = new StringBuilder(recorder.gpsStatus());
        int battery = hr.getBattery();
        if (battery >= 0 && battery <= 20) {
            top.append(top.length() > 0 ? "  " : "").append("Strap ").append(battery).append('%');
        }
        gpsView.setText(top);

        int bpm = recorder.currentBpm();
        int zone = Training.zone(bpm, s.hrMax);
        bpmView.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        boolean over = hr.isAbove();
        boolean under = hr.isBelow();
        int color = bpm > 0 ? Ui.ZONE_COLORS[zone] : 0xFF1C1C1F;
        hrCardBg.setColor(over || under ? 0xFFB3261E : blend(color));
        if (bpm > 0) {
            zoneView.setText((zone > 0 ? "Zone " + zone + " \u00b7 " + Training.ZONE_NAMES[zone]
                    : Training.ZONE_NAMES[0]) + " \u00b7 "
                    + Training.percentOfMax(bpm, s.hrMax) + "%");
        } else {
            zoneView.setText(hr.isConnected() ? "Waiting for heart rate" : "Strap not connected");
        }
        strip.set(bpm, s.hrMax);

        if (walking && !over) {
            statusView.setText("Walk break \u2014 still recording, low alert off");
        } else if (over) {
            statusView.setText("Over " + hr.getThreshold() + " \u2014 ease off");
        } else if (under) {
            statusView.setText("Under " + hr.getLowLimit() + " \u2014 pick it up");
        } else if (!hr.isConnected()) {
            statusView.setText(hr.getState());
        } else if (!hr.hasContact()) {
            statusView.setText("No skin contact \u2014 check the strap");
        } else if (recorder.getPlan() != null) {
            statusView.setText("Coached: " + recorder.getPlan().title);
        } else {
            statusView.setText(alarmSummary());
        }

        tiles[0].set(Training.duration(st.activeMs));
        if (gps) {
            tiles[1].set(Training.km(st.distanceM));
            tiles[2].set(Training.pace(st.speed()));
            tiles[3].set(Training.pace(st.avgSpeed()));
            tiles[4].set(String.valueOf(Math.round(st.kcal)));
            tiles[5].set(st.avgHr() > 0 ? String.valueOf(st.avgHr()) : "--");
        } else {
            tiles[1].set(String.valueOf(Math.round(st.kcal)));
            tiles[2].set(st.avgHr() > 0 ? String.valueOf(st.avgHr()) : "--");
            tiles[3].set(st.maxHr > 0 ? String.valueOf(st.maxHr) : "--");
        }
        pauseButton.setText(paused ? "Resume" : "Pause");
        updateStep();
    }

    private String alarmSummary() {
        StringBuilder sb = new StringBuilder();
        if (hr.alertsOn()) {
            int low = hr.getLowLimit();
            sb.append(low > 0 ? "Alerts " + low + "\u2013" + hr.getThreshold()
                    : "Alert above " + hr.getThreshold());
        } else {
            sb.append("Alerts off");
        }
        return sb.toString();
    }

    /** Zone colour darkened so white digits stay readable on it. */
    private static int blend(int color) {
        int r = ((color >> 16) & 0xFF) * 45 / 100;
        int g = ((color >> 8) & 0xFF) * 45 / 100;
        int b = (color & 0xFF) * 45 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public void onRecorderUpdate() {
        runOnUiThread(this::update);
    }

    @Override
    public void onState(String state) {
        runOnUiThread(this::update);
    }

    @Override
    public void onBpm(int bpm, boolean above) {
        runOnUiThread(this::update);
    }

    @Override
    public void onConnected(boolean connected) {
        runOnUiThread(this::update);
    }
}
