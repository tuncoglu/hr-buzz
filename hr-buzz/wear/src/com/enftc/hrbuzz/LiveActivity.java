package com.enftc.hrbuzz;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The screen during a run, in two swipeable pages like Fitbit's: the run itself (heart rate in
 * a ring of zones, time, distance and pace; coral with a "Slow down" banner while above the
 * limit) and, one swipe to the right, End / Pause / Lock. Lock stops sweat and rain from
 * touching anything until you hold the screen.
 */
public class LiveActivity extends Activity implements WristHr.Listener {

    private WristHr hr;
    private WearRecorder recorder;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ZoneRingView ring;
    private TextView clock;
    private TextView banner;
    private TextView number;
    private TextView unit;
    private TextView pill;
    private TextView bottom;
    private Button pauseButton;
    private View metrics;
    private View controls;
    private View lock;
    private View dotRun;
    private View dotControls;
    private boolean onControls;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, 1000);
        }
    };

    /** Two pages side by side: a horizontal swipe moves between them, taps reach the buttons. */
    private static final class Pager extends FrameLayout {

        interface Swipe {
            void toControls(boolean show);
        }

        private final int slop;
        private final Swipe swipe;
        private float downX;
        private float downY;
        private boolean dragging;

        Pager(Context c, Swipe swipe) {
            super(c);
            this.swipe = swipe;
            this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - downX;
                    if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(e.getY() - downY)) {
                        dragging = true;
                    }
                    break;
                default:
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Reached only when no button took the touch (the run page is all display).
                    downX = e.getX();
                    downY = e.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    float dx = e.getX() - downX;
                    if (Math.abs(dx) > getWidth() / 6f && Math.abs(dx) > Math.abs(e.getY() - downY)) {
                        swipe.toControls(dx > 0);       // right: controls; left: back to the run
                    }
                    dragging = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hr = WristHr.get(this);
        recorder = WearRecorder.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Wu.BLACK);

        Pager pager = new Pager(this, this::showControls);
        metrics = buildMetrics();
        controls = buildControls();
        pager.addView(metrics, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // page dots
        LinearLayout dots = Wu.row(this);
        dotControls = dot();
        dotRun = dot();
        dots.addView(dotControls);
        dots.addView(dotRun);
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dp.bottomMargin = Wu.px(this, 12);
        root.addView(dots, dp);

        lock = buildLock();
        lock.setVisibility(View.GONE);
        root.addView(lock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        root.post(() -> placePages(false));
    }

    private View dot() {
        View d = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Wu.px(this, 8), Wu.px(this, 8));
        lp.leftMargin = Wu.px(this, 4);
        lp.rightMargin = Wu.px(this, 4);
        d.setLayoutParams(lp);
        return d;
    }

    private FrameLayout.LayoutParams top(float designPx) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        p.topMargin = Wu.px(this, designPx);
        return p;
    }

    /** The run page: ring, time, heart rate, status pill, distance and pace. */
    private View buildMetrics() {
        FrameLayout page = new FrameLayout(this);
        ring = new ZoneRingView(this);
        page.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        clock = Wu.text(this, "0:00", 18, Wu.MUTED, true);
        page.addView(clock, top(34));

        banner = Wu.text(this, "Slow down", 18, Wu.ON_ACCENT, true);
        banner.setBackground(Wu.pill(Wu.ALERT, this, 18));
        banner.setPadding(Wu.px(this, 18), Wu.px(this, 7), Wu.px(this, 18), Wu.px(this, 7));
        page.addView(banner, top(30));

        LinearLayout middle = Wu.column(this);
        number = Wu.text(this, "--", 104, Wu.TEXT, true);
        number.setLetterSpacing(-0.03f);
        middle.addView(number);
        unit = Wu.text(this, "bpm", 18, Wu.MUTED, true);
        middle.addView(unit);
        page.addView(middle, top(80));

        pill = Wu.text(this, "", 17, Wu.TEXT, true);
        pill.setPadding(Wu.px(this, 16), Wu.px(this, 6), Wu.px(this, 16), Wu.px(this, 6));
        page.addView(pill, top(244));

        bottom = Wu.text(this, "", 17, Wu.MUTED, true);
        FrameLayout.LayoutParams b = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        b.bottomMargin = Wu.px(this, 40);
        page.addView(bottom, b);
        return page;
    }

    /** The controls page: End and Pause side by side, Lock under them. */
    private View buildControls() {
        LinearLayout page = Wu.column(this);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(Wu.BLACK);
        int side = Wu.px(this, 50);
        page.setPadding(side, Wu.px(this, 40), side, Wu.px(this, 40));

        LinearLayout row = Wu.row(this);
        row.setGravity(Gravity.CENTER);
        Button end = control("End", Wu.ALERT, Wu.ON_ACCENT);
        end.setOnClickListener(v -> end());
        pauseButton = control("Pause", Wu.BUTTON, Wu.TEXT);
        pauseButton.setOnClickListener(v -> {
            boolean paused = recorder.getState() == WearRecorder.PAUSED;
            startService(new Intent(this, WearService.class)
                    .setAction(paused ? WearService.ACTION_RESUME : WearService.ACTION_PAUSE));
            // The service acts on the same recorder; show the new state at once.
            pauseButton.setText(paused ? "Pause" : "Resume");
            showControls(false);
        });
        row.addView(end);
        row.addView(Wu.space(this, 1));
        row.addView(pauseButton);
        page.addView(row, Wu.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(Wu.space(this, 12));

        Button lockButton = control("Lock", Wu.BUTTON, Wu.TEXT);
        lockButton.setOnClickListener(v -> {
            hr.tick();
            showControls(false);
            lock.setVisibility(View.VISIBLE);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Wu.px(this, 150),
                Wu.px(this, 84));
        page.addView(lockButton, lp);
        return page;
    }

    private Button control(String label, int bg, int fg) {
        Button b = Wu.button(this, label, bg, fg, 84);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Wu.px(this, 84), 1);
        lp.leftMargin = Wu.px(this, 4);
        lp.rightMargin = Wu.px(this, 4);
        b.setLayoutParams(lp);
        return b;
    }

    /** A black cover that swallows every touch until it is held. */
    private View buildLock() {
        LinearLayout cover = Wu.column(this);
        cover.setGravity(Gravity.CENTER);
        cover.setBackgroundColor(0xE6000000);
        cover.setClickable(true);
        cover.addView(Wu.text(this, "Locked", 28, Wu.TEXT, true));
        cover.addView(Wu.space(this, 6));
        cover.addView(Wu.text(this, "Hold to unlock", 17, Wu.MUTED, true));
        cover.setContentDescription("Screen locked. Touch and hold to unlock");
        cover.setOnLongClickListener(v -> {
            hr.tick();
            lock.setVisibility(View.GONE);
            return true;
        });
        return cover;
    }

    private void end() {
        long id = WearService.finish(this);
        if (id > 0) {
            startActivity(new Intent(this, WearSummaryActivity.class)
                    .putExtra(WearSummaryActivity.EXTRA_ID, id)
                    .putExtra(WearSummaryActivity.EXTRA_FRESH, true));
        } else {
            // Same rule as the phone: under 10 s is a false start, nothing to keep.
            Toast.makeText(this, "Under 10 seconds: not saved", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /** Slides to the controls page, or back to the run page. */
    private void showControls(boolean show) {
        onControls = show;
        placePages(true);
    }

    private void placePages(boolean animate) {
        float w = metrics.getWidth();
        float runX = onControls ? w : 0;
        float controlsX = onControls ? 0 : -w;
        if (animate) {
            metrics.animate().translationX(runX).setDuration(180).start();
            controls.animate().translationX(controlsX).setDuration(180).start();
        } else {
            metrics.setTranslationX(runX);
            controls.setTranslationX(controlsX);
        }
        dotRun.setBackground(Wu.pill(onControls ? Wu.CARD : Wu.TEXT, this, 4));
        dotControls.setBackground(Wu.pill(onControls ? Wu.TEXT : Wu.CARD, this, 4));
    }

    @Override
    public void onBackPressed() {
        if (lock.getVisibility() == View.VISIBLE) {
            return;             // locked: only the hold unlocks
        }
        if (onControls) {
            showControls(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!recorder.isRecording()) {
            finish();           // opened from a stale notification: nothing to show
            return;
        }
        hr.addListener(this);
        main.post(refresher);
    }

    @Override
    protected void onStop() {
        hr.removeListener(this);
        main.removeCallbacks(refresher);
        super.onStop();
    }

    @Override
    public void onHeart() {
        refresh();
    }

    private void refresh() {
        SessionStore.Summary session = recorder.getSession();
        Training.Stats st = recorder.getStats();
        if (session == null || st == null) {
            return;
        }
        boolean paused = recorder.getState() == WearRecorder.PAUSED;
        pauseButton.setText(paused ? "Resume" : "Pause");
        int bpm = hr.getBpm();
        int hrMax = session.hrMax;
        boolean above = hr.isAbove();
        boolean below = hr.isBelow();

        ring.set(bpm, hrMax);
        CoachLogic.Plan plan = recorder.getPlan();
        int step = recorder.getStepIndex();
        if (plan != null && step >= 0 && step < plan.steps.size()) {
            // Coached: what to do now and how long is left in it.
            clock.setText(plan.steps.get(step).name + " "
                    + Training.duration(recorder.stepRemainingMs()));
        } else if (plan != null && step >= plan.steps.size()) {
            clock.setText("Workout done · " + Training.duration(st.activeMs));
        } else {
            clock.setText(Training.duration(st.activeMs));
        }
        clock.setVisibility(above ? View.INVISIBLE : View.VISIBLE);
        banner.setVisibility(above ? View.VISIBLE : View.GONE);
        number.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        number.setTextColor(above ? Wu.ALERT : Wu.TEXT);
        int spm = paused ? 0 : StepSensor.get(this).spm();
        unit.setText(spm > 0 ? "bpm  \u00b7  " + spm + " spm" : "bpm");

        String text;
        int bg;
        int fg;
        if (paused) {
            text = recorder.isAutoPaused() ? "Auto-paused" : "Paused";
            bg = Wu.CARD;
            fg = Wu.TEXT;
        } else if (hr.isLost() || bpm <= 0) {
            text = hr.isLost() ? "Check watch fit" : "Finding pulse…";
            bg = Wu.CARD;
            fg = Wu.MUTED;
        } else if (above) {
            text = WearLogic.overBy(bpm, hr.getThreshold()) + " over your limit";
            bg = Wu.BLACK;
            fg = Wu.MUTED;
        } else if (below) {
            text = WearLogic.underBy(bpm, hr.getLowLimit()) + " under your low limit";
            bg = Wu.CARD;
            fg = Wu.ZONE[1];
        } else {
            int zone = Training.zone(bpm, hrMax);
            text = zone > 0 ? "Zone " + zone + (hr.alarmOn() ? " · in target" : "")
                    : "Below zone 1";
            boolean target = zone > 0 && hr.alarmOn();
            bg = target ? Wu.TARGET_BG : Wu.CARD;
            fg = target ? Wu.TARGET_TEXT : Wu.TEXT;
        }
        pill.setText(text);
        pill.setTextColor(fg);
        pill.setBackground(Wu.pill(bg, this, 18));

        if (session.gps) {
            bottom.setText(Training.km(st.distanceM) + " km     " + Training.pace(st.speed())
                    + " /km");
        } else {
            bottom.setText(st.avgHr() > 0 ? "avg " + st.avgHr() + " bpm" : "");
        }
    }
}
