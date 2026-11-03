package com.enftc.hrbuzz;

import android.app.Activity;
import android.os.Bundle;
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

    private static final int TEST_SECONDS = FitnessTestSession.TEST_SECONDS;
    private static final int SETTLE_SECONDS = FitnessTestSession.SETTLE_SECONDS;

    private HrManager hr;
    private FitnessTestSession session;

    private TextView clock;
    private TextView live;
    private TextView result;
    private TextView history;
    /** Resting HR, HRV and VO2max: the last test's, live while measuring, then this one's. */
    private Ui.Tile[] tiles;
    /** SDNN and pNN50, the two extra HRV figures. */
    private Ui.Tile[] hrvTiles;
    private TextView tilesNote;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        hr = HrManager.get(this);
        session = FitnessTestSession.get(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 28));
        scroll.addView(root);

        root.addView(Ui.bold(Ui.text(this, "Fitness test", 26, Ui.TEXT)));
        TextView how = Ui.text(this, "Wear the sensor, lie down somewhere quiet and relax. "
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
            if (session.isRunning()) {
                session.cancel();
                live.setText("Cancelled");
            } else {
                begin();
            }
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card);
        int cp = Ui.dp(this, 12);
        card.setPadding(cp, cp, cp, cp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 20);
        root.addView(card, tlp);
        tiles = Ui.grid(this, card, new String[]{"Resting HR\nbpm", "HRV (RMSSD)\nms",
                "VO2max\nml/kg/min"}, 3, 30);
        hrvTiles = Ui.grid(this, card, new String[]{"SDNN\nms", "pNN50\n%"}, 2, 30);
        tilesNote = Ui.text(this, "", 13, Ui.TEXT3);
        tilesNote.setGravity(Gravity.CENTER);
        tilesNote.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(tilesNote);

        TextView legend = Ui.text(this, "Resting HR: your average heart rate while still. "
                + "Lower usually means fitter or more rested.\n"
                + "RMSSD: how much the time between beats changes from one beat to the next. "
                + "Higher usually means more recovered.\n"
                + "SDNN: the overall spread of those times, slower swings included.\n"
                + "pNN50: how often neighbouring beats differ by more than 50 ms.\n"
                + "VO2max: a fitness estimate from your max and resting heart rate.",
                13, Ui.TEXT3);
        legend.setLineSpacing(0, 1.15f);
        legend.setPadding(0, Ui.dp(this, 14), 0, 0);
        card.addView(legend);

        result = Ui.text(this, "", 16, Ui.TEXT);
        result.setPadding(0, Ui.dp(this, 20), 0, 0);
        root.addView(result);

        history = Ui.text(this, "", 14, Ui.TEXT2);
        history.setPadding(0, Ui.dp(this, 20), 0, 0);
        root.addView(history);

        Button about = new Button(this, null, 0, R.style.SecondaryButton);
        about.setText("About these numbers");
        about.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, AboutNumbersActivity.class)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        alp.topMargin = Ui.dp(this, 20);
        root.addView(about, alp);

        setContentView(scroll);
        showHistory();
        render();
    }

    /** Draws the session as it is now: the countdown, the live numbers or the last result. */
    private void render() {
        boolean running = session.isRunning();
        if (running) {
            int elapsed = Math.min(session.elapsedSeconds(), TEST_SECONDS);
            int left = TEST_SECONDS - elapsed;
            int bpm = hr.getBpm();
            clock.setText(String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60));
            live.setText((bpm > 0 ? bpm + " bpm" : "No heart rate") + "  \u00b7  "
                    + (elapsed < SETTLE_SECONDS ? "settling" : "measuring"));
            showLive(elapsed);
            startButton.setText("Cancel");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean finished = session.isFinished();
        startButton.setText(finished ? "Start again" : "Start test");
        clock.setText(finished ? "0:00" : "5:00");
        result.setText(session.resultText());
        showLatest();
        if (finished && !session.resultNote().isEmpty()) {
            tilesNote.setText(session.resultNote());
        }
        showHistory();
        if (finished) {
            live.setText("");
        } else if (!live.getText().toString().equals("Cancelled")) {
            live.setText(hr.isConnected() ? hr.getBpm() + " bpm" : hr.getState());
        }
    }

    /** Before a test: the last result, or what the test will give. */
    private void showLatest() {
        List<CoachLogic.Test> tests = Prefs.fitnessTests(this);
        if (tests.isEmpty()) {
            setTiles(0, 0, 0, 0, -1);
            tilesNote.setText("No test yet. These fill in as the test runs, from 2:00 in.");
            return;
        }
        CoachLogic.Test last = tests.get(tests.size() - 1);
        setTiles(last.rest, last.rmssd, Training.vo2maxEstimate(Prefs.hrMax(this), last.rest),
                last.sdnn, last.pnn50);
        tilesNote.setText("Your last test, " + new SimpleDateFormat("d MMM", Locale.getDefault())
                .format(new Date(last.t)) + ".");
    }

    /** While the test runs: the numbers so far, from the measured part only. */
    private void showLive(int elapsed) {
        if (elapsed < SETTLE_SECONDS) {
            setTiles(0, 0, 0, 0, -1);
            int wait = SETTLE_SECONDS - elapsed;
            tilesNote.setText(String.format(Locale.ROOT,
                    "Settling: measuring starts in %d:%02d", wait / 60, wait % 60));
            return;
        }
        List<Integer> bpms = session.bpms();
        int rest = 0;
        if (bpms.size() >= 10) {
            long sum = 0;
            for (int b : bpms) {
                sum += b;
            }
            rest = Math.round(sum / (float) bpms.size());
        }
        List<Integer> rrs = session.rrs();
        List<Integer> clean = Training.cleanRr(rrs);
        boolean enough = clean.size() >= 30;
        setTiles(rest, enough ? Training.rmssdClean(rrs) : 0,
                Training.vo2maxEstimate(Prefs.hrMax(this), rest),
                enough ? Training.sdnn(rrs) : 0, enough ? Training.pnn50(rrs) : -1);
        tilesNote.setText("So far, from " + clean.size() + " clean beats. Stay still.");
    }

    private void setTiles(int rest, double rmssd, double vo2, double sdnn, double pnn50) {
        hrvTiles[0].set(sdnn > 0 ? String.valueOf(Math.round(sdnn)) : "--");
        hrvTiles[1].set(pnn50 >= 0 ? String.valueOf(Math.round(pnn50)) : "--");
        tiles[0].set(rest > 0 ? String.valueOf(rest) : "--");
        tiles[1].set(rmssd > 0 ? String.valueOf(Math.round(rmssd)) : "--");
        tiles[2].set(vo2 > 0 ? String.valueOf(Math.round(vo2)) : "--");
    }

    @Override
    protected void onStart() {
        super.onStart();
        hr.addListener(this);
        session.setUi(this::render);
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        hr.removeListener(this);
        session.setUi(null);
    }

    private void begin() {
        if (!session.begin()) {
            live.setText("Connect the sensor first (Connect on the home screen)");
            return;
        }
        render();
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
                    if (o.optInt("sdnn") > 0) {
                        sb.append("   SDNN ").append(o.getInt("sdnn"));
                    }
                    if (o.has("pnn50")) {
                        sb.append("   pNN50 ").append(o.getInt("pnn50")).append('%');
                    }
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
        if (!session.isRunning()) {
            runOnUiThread(() -> live.setText(bpm + " bpm"));
        }
    }

    @Override
    public void onConnected(boolean connected) {
    }
}
