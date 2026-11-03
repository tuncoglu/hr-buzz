package com.enftc.hrbuzz;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One fitness test, owned by the process rather than by the screen showing it, so rotating
 * the phone, going back or opening another screen does not reset the countdown. The screen
 * ({@link FitnessTestActivity}) only attaches to draw it. If the screen is gone when the five
 * minutes are up, the test still finishes: it beeps and saves the result.
 */
final class FitnessTestSession implements HrManager.Listener {

    static final int TEST_SECONDS = 300;
    static final int SETTLE_SECONDS = 120;

    private static FitnessTestSession instance;

    static synchronized FitnessTestSession get(Context context) {
        if (instance == null) {
            instance = new FitnessTestSession(context.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final HrManager hr;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Redraws the screen; null while no screen is attached. Main thread only. */
    private Runnable ui;

    private boolean running;
    private long startedAt;
    /** Set once a test has run to the end in this process; cleared by the next start. */
    private boolean finished;
    private String resultText = "";
    private String resultNote = "";
    private final List<Integer> bpms = new ArrayList<>();
    private final List<Integer> rrs = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            int elapsed = elapsedSeconds();
            if (elapsed >= TEST_SECONDS) {
                complete();
                return;
            }
            int bpm = hr.getBpm();
            if (elapsed >= SETTLE_SECONDS && bpm > 0) {
                bpms.add(bpm);
            }
            redraw();
            main.postDelayed(this, 1000);
        }
    };

    private FitnessTestSession(Context app) {
        this.app = app;
        this.hr = HrManager.get(app);
    }

    // ------------------------------------------------------------------ state

    boolean isRunning() {
        return running;
    }

    boolean isFinished() {
        return finished;
    }

    int elapsedSeconds() {
        return (int) ((System.currentTimeMillis() - startedAt) / 1000);
    }

    List<Integer> bpms() {
        return bpms;
    }

    List<Integer> rrs() {
        return rrs;
    }

    String resultText() {
        return resultText;
    }

    String resultNote() {
        return resultNote;
    }

    void setUi(Runnable ui) {
        this.ui = ui;
    }

    private void redraw() {
        if (ui != null) {
            ui.run();
        }
    }

    // ---------------------------------------------------------------- control

    /** @return false if the strap is not connected, so nothing started. */
    boolean begin() {
        if (running) {
            return true;
        }
        if (!hr.isConnected()) {
            return false;
        }
        running = true;
        finished = false;
        startedAt = System.currentTimeMillis();
        bpms.clear();
        rrs.clear();
        resultText = "";
        resultNote = "";
        hr.addListener(this);
        main.post(tick);
        return true;
    }

    void cancel() {
        running = false;
        finished = false;
        main.removeCallbacks(tick);
        hr.removeListener(this);
        redraw();
    }

    private void complete() {
        running = false;
        finished = true;
        main.removeCallbacks(tick);
        hr.removeListener(this);
        hr.testAlarm();

        if (bpms.size() < 60) {
            resultText = "Not enough heart rate data — check the sensor and try again.";
            resultNote = "";
            redraw();
            return;
        }
        long sum = 0;
        for (int b : bpms) {
            sum += b;
        }
        int rest = Math.round(sum / (float) bpms.size());
        List<Integer> clean = Training.cleanRr(rrs);
        double rmssd = Training.rmssdClean(rrs);
        double sdnn = Training.sdnn(rrs);
        double pnn50 = Training.pnn50(rrs);
        int hrMax = Prefs.hrMax(app);
        double vo2 = Training.vo2maxEstimate(hrMax, rest);

        boolean hrvOk = clean.size() >= 60;
        resultNote = "This test, from " + clean.size() + " clean beats.";
        StringBuilder sb = new StringBuilder();
        if (!hrvOk && rrs.isEmpty()) {
            // Many watches and some armbands send the heart rate but no beat-to-beat intervals.
            sb.append("No HRV: this sensor sends no beat-to-beat intervals. Chest straps do; "
                    + "most watches do not. Resting heart rate and VO2max below are still "
                    + "worked out.\n\n");
        } else if (!hrvOk) {
            sb.append("No HRV this time: the sensor sent too few clean beat-to-beat intervals "
                    + "(").append(clean.size()).append(" of 60 needed). Wet the electrodes "
                    + "and stay still.\n\n");
        }
        sb.append("VO2max here is 15.3 × HRmax (").append(hrMax).append(") ÷ "
                + "resting HR, so it is only as good as your max heart rate.");

        SharedPreferences prefs = Prefs.of(app);
        try {
            JSONArray all = new JSONArray(prefs.getString("fitnessTests", "[]"));
            JSONObject entry = new JSONObject().put("t", System.currentTimeMillis())
                    .put("rest", rest)
                    .put("rmssd", hrvOk ? Math.round(rmssd) : 0)
                    .put("vo2", Math.round(vo2));
            if (hrvOk) {
                entry.put("sdnn", Math.round(sdnn));
                if (pnn50 >= 0) {
                    entry.put("pnn50", Math.round(pnn50));
                }
            }
            all.put(entry);
            prefs.edit().putString("fitnessTests", all.toString()).apply();
        } catch (JSONException ignored) {
        }
        if (Prefs.restHrAuto(app)) {
            sb.append("\n\nYour profile's resting heart rate is now ")
                    .append(Prefs.restHrFromTests(app))
                    .append(" (the middle of your last three tests).");
        } else {
            sb.append("\n\nYour profile keeps the resting heart rate you entered; switch it to "
                    + "\"From fitness tests\" in Settings to follow these results.");
        }
        Coach coach = Coach.get(app);
        if (coach.isEnabled()) {
            CoachLogic.Plan before = coach.todayPlan();
            int adjusted = before == null ? 0 : before.adjusted.size();
            coach.applyReadiness();
            CoachLogic.Plan after = coach.todayPlan();
            if (after != null && after.adjusted.size() > adjusted) {
                sb.append("\n\nToday's plan is now easier: ")
                        .append(after.adjusted.get(after.adjusted.size() - 1)).append('.');
            }
        }
        resultText = sb.toString();
        redraw();
    }

    // ------------------------------------------------------------- HrManager

    @Override
    public void onState(String state) {
    }

    @Override
    public void onBpm(int bpm, boolean above) {
    }

    @Override
    public void onConnected(boolean connected) {
    }

    /** Called on the Bluetooth thread; the lists are only touched on the main thread. */
    @Override
    public void onSample(HrLogic.Measurement m) {
        final int[] intervals = m.rrMs.clone();
        main.post(() -> {
            if (!running || System.currentTimeMillis() - startedAt < SETTLE_SECONDS * 1000L) {
                return;
            }
            for (int rr : intervals) {
                rrs.add(rr);
            }
        });
    }
}
