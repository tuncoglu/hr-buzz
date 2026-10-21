package com.emre.hrbuzz;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Polar Beat's Fitness Test, in the open: lie still for 5 minutes, then get resting heart
 * rate, HRV (RMSSD) and a VO2max estimate from the ratio of maximum to resting heart rate
 * (Uth et al. 2004). The last 3 minutes are measured; the first 2 let you settle.
 */
public class FitnessTestActivity extends Activity implements HrManager.Listener {

    private static final int TEST_SECONDS = 300;
    private static final int SETTLE_SECONDS = 120;

    private HrManager hr;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView clock;
    private TextView live;
    private TextView result;
    private TextView history;
    private Button startButton;

    private boolean running;
    private long startedAt;
    private final List<Integer> bpms = new ArrayList<>();
    private final List<Integer> rrs = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            int elapsed = (int) ((System.currentTimeMillis() - startedAt) / 1000);
            if (elapsed >= TEST_SECONDS) {
                complete();
                return;
            }
            int bpm = hr.getBpm();
            if (elapsed >= SETTLE_SECONDS && bpm > 0) {
                bpms.add(bpm);
            }
            int left = TEST_SECONDS - elapsed;
            clock.setText(String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60));
            live.setText((bpm > 0 ? bpm + " bpm" : "No heart rate") + "  \u00b7  "
                    + (elapsed < SETTLE_SECONDS ? "settling" : "measuring"));
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        hr = HrManager.get(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 28));
        scroll.addView(root);

        root.addView(Ui.bold(Ui.text(this, "Fitness test", 26, Ui.TEXT)));
        TextView how = Ui.text(this, "Wear the strap, lie down somewhere quiet and relax. "
                + "Stay still and breathe normally for 5 minutes; the phone beeps when it is "
                + "done.\n\nBest done in the morning, rested, before coffee \u2014 and in the "
                + "same conditions each time if you want to compare results.", 15, Ui.TEXT2);
        how.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(how);

        clock = Ui.bold(Ui.text(this, "5:00", 72, Ui.TEXT));
        clock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 20);
        root.addView(clock, clp);
        live = Ui.text(this, "", 16, Ui.TEXT2);
        live.setGravity(Gravity.CENTER);
        root.addView(live, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        startButton = new Button(this, null, 0, R.style.PrimaryButton);
        startButton.setText("Start test");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64));
        blp.topMargin = Ui.dp(this, 20);
        root.addView(startButton, blp);
        startButton.setOnClickListener(v -> {
            if (running) {
                cancel();
            } else {
                begin();
            }
        });

        result = Ui.text(this, "", 16, Ui.TEXT);
        result.setPadding(0, Ui.dp(this, 20), 0, 0);
        root.addView(result);

        history = Ui.text(this, "", 14, Ui.TEXT2);
        history.setPadding(0, Ui.dp(this, 20), 0, 0);
        root.addView(history);

        setContentView(scroll);
        showHistory();
    }

    @Override
    protected void onStart() {
        super.onStart();
        hr.addListener(this);
        if (!running) {
            live.setText(hr.isConnected() ? hr.getBpm() + " bpm" : hr.getState());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        hr.removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        main.removeCallbacks(tick);
    }

    private void begin() {
        if (!hr.isConnected()) {
            live.setText("Connect the strap first (Connect on the home screen)");
            return;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        bpms.clear();
        rrs.clear();
        result.setText("");
        startButton.setText("Cancel");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        main.post(tick);
    }

    private void cancel() {
        running = false;
        main.removeCallbacks(tick);
        startButton.setText("Start test");
        clock.setText("5:00");
        live.setText("Cancelled");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void complete() {
        running = false;
        startButton.setText("Start again");
        clock.setText("0:00");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hr.testAlarm();

        if (bpms.size() < 60) {
            result.setText("Not enough heart rate data \u2014 check the strap and try again.");
            return;
        }
        long sum = 0;
        for (int b : bpms) {
            sum += b;
        }
        int rest = Math.round(sum / (float) bpms.size());
        List<Integer> clean = Training.cleanRr(rrs);
        double rmssd = Training.rmssd(clean);
        int hrMax = Prefs.hrMax(this);
        double vo2 = Training.vo2maxEstimate(hrMax, rest);

        StringBuilder sb = new StringBuilder();
        sb.append("Resting heart rate  ").append(rest).append(" bpm\n");
        if (clean.size() >= 60) {
            sb.append("HRV (RMSSD)  ").append(Math.round(rmssd)).append(" ms\n");
        }
        sb.append(String.format(Locale.ROOT, "VO2max estimate  %.0f ml/kg/min\n", vo2));
        sb.append("\nVO2max here is 15.3 \u00d7 HRmax (").append(hrMax).append(") \u00f7 "
                + "resting HR, so it is only as good as your max heart rate.");

        SharedPreferences prefs = Prefs.of(this);
        try {
            JSONArray all = new JSONArray(prefs.getString("fitnessTests", "[]"));
            all.put(new JSONObject().put("t", System.currentTimeMillis()).put("rest", rest)
                    .put("rmssd", clean.size() >= 60 ? Math.round(rmssd) : 0)
                    .put("vo2", Math.round(vo2)));
            prefs.edit().putString("fitnessTests", all.toString()).apply();
        } catch (JSONException ignored) {
        }
        if (Prefs.restHrAuto(this)) {
            sb.append("\n\nYour profile's resting heart rate is now ")
                    .append(Prefs.restHrFromTests(this))
                    .append(" (the middle of your last three tests).");
        } else {
            sb.append("\n\nYour profile keeps the resting heart rate you entered; switch it to "
                    + "\"From fitness tests\" in Settings to follow these results.");
        }
        result.setText(sb.toString());
        showHistory();
    }

    private void showHistory() {
        try {
            JSONArray all = new JSONArray(Prefs.of(this).getString("fitnessTests", "[]"));
            if (all.length() == 0) {
                history.setText("");
                return;
            }
            SimpleDateFormat day = new SimpleDateFormat("d MMM yyyy", Locale.getDefault());
            StringBuilder sb = new StringBuilder("PREVIOUS TESTS\n");
            for (int i = all.length() - 1; i >= Math.max(0, all.length() - 10); i--) {
                JSONObject o = all.getJSONObject(i);
                sb.append(day.format(new Date(o.getLong("t")))).append("   rest ")
                        .append(o.getInt("rest"));
                if (o.optInt("rmssd") > 0) {
                    sb.append("   HRV ").append(o.getInt("rmssd"));
                }
                sb.append("   VO2max ").append(o.getInt("vo2")).append('\n');
            }
            history.setText(sb.toString());
        } catch (JSONException e) {
            history.setText("");
        }
    }

    // ------------------------------------------------------------- HrManager

    @Override
    public void onState(String state) {
    }

    @Override
    public void onBpm(int bpm, boolean above) {
        if (!running) {
            runOnUiThread(() -> live.setText(bpm + " bpm"));
        }
    }

    @Override
    public void onConnected(boolean connected) {
    }

    @Override
    public void onSample(HrLogic.Measurement m) {
        if (!running || System.currentTimeMillis() - startedAt < SETTLE_SECONDS * 1000L) {
            return;
        }
        for (int rr : m.rrMs) {
            rrs.add(rr);
        }
    }
}
