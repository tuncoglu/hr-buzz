package com.emre.hrbuzz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One saved session: the numbers Polar Beat shows (duration, HR avg/max, calories, distance,
 * pace avg/best) plus time in zones, training load, HR and pace graphs, route, km splits,
 * how it felt, notes, and TCX/GPX export.
 */
public class SummaryActivity extends Activity {

    static final String EXTRA_ID = "id";

    private static final String[] FEELINGS = {"Awful", "Poor", "OK", "Good", "Great"};
    private static final String[] TALK = {"Full sentences", "Short phrases", "A few words"};
    /** Shorter sessions (false starts, tests) get a debrief only on request: each one costs a call. */
    static final long AUTO_DEBRIEF_MS = 5 * 60000L;
    /** How long a fresh session waits for the talk test before the coach debriefs without it. */
    private static final long WAIT_FOR_TALK_MS = 45000L;

    private SessionStore.Summary summary;
    private EditText notes;
    private LinearLayout coachBox;
    private boolean debriefing;
    private String debriefError;
    /** They chose a debrief without answering the talk test (or the wait ran out). */
    private boolean talkSkipped;
    private LinearLayout talkRow;
    private LinearLayout feelRow;
    /** The talk test was changed on this screen, so it wins over an answer from the notification. */
    private boolean talkTouched;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean waiting;
    private final Runnable waitedForTalk = () -> {
        waiting = false;
        if (summary != null && !isFinishing() && !debriefing && summary.coachNote.isEmpty()
                && summary.talk == 0) {
            talkSkipped = true;
            debrief();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        summary = SessionStore.load(this, id);
        if (summary == null) {
            Toast.makeText(this, "Session not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        build(SessionStore.samples(this, id));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // An answer given on the "How was it?" notification while this screen was away.
        if (summary == null || talkRow == null || talkTouched) {
            return;
        }
        SessionStore.Summary disk = SessionStore.load(this, summary.id);
        if (disk != null && disk.talk != summary.talk) {
            summary.talk = disk.talk;
            Ui.select(talkRow, summary.talk - 1);
            afterTalkChanged();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(waitedForTalk);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (summary != null && notes != null) {
            String text = notes.getText().toString().trim();
            if (!text.equals(summary.notes)) {
                boolean newFlag = CoachLogic.redFlag(text) && !CoachLogic.redFlag(summary.notes);
                summary.notes = text;
                saveSummary();
                Coach coach = Coach.get(this);
                if (newFlag && coach.isEnabled()) {
                    // Same rule as the coach chat: a symptom ends today's training.
                    coach.replacePlan(CoachLogic.restPlan("",
                            "Rest after a symptom in your session notes. "
                                    + CoachLogic.RED_FLAG_REPLY));
                    Toast.makeText(this, "Today is now a rest day. Chest pain, fainting, "
                            + "dizziness or an irregular heartbeat need a doctor, not a coach.",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void build(List<Training.Sample> samples) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 28));
        scroll.addView(root);

        SessionStore.Summary s = summary;
        root.addView(Ui.bold(Ui.text(this, s.sport, 26, Ui.TEXT)));
        SimpleDateFormat when = new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault());
        root.addView(Ui.text(this, when.format(new Date(s.id)), 14, Ui.TEXT2));
        if (s.imported) {
            root.addView(Ui.text(this, "Imported from a TCX file", 14, Ui.TEXT2));
        }
        if (!s.planTitle.isEmpty()) {
            root.addView(Ui.text(this, "Coached: " + s.planTitle
                    + (s.targetPct >= 0 ? " \u00b7 " + s.targetPct + "% in target" : ""), 14,
                    0xFF4F8EF7));
        }

        // A session just finished and not yet rated: the two questions come first.
        boolean askFirst = isFresh() && !s.imported && (s.talk == 0 || s.feeling == 0);
        if (askFirst) {
            howCard(root);
        }

        // ---- numbers
        LinearLayout card = card(root);
        boolean distance = s.gps && s.distanceM > 0;
        String[] captions = distance
                ? new String[]{"Duration", "Distance km", "Avg pace /km", "Best pace /km",
                "Avg HR", "Max HR", "Calories kcal", "Training load"}
                : new String[]{"Duration", "Calories kcal", "Avg HR", "Max HR",
                "Training load", "% of HRmax avg"};
        Ui.Tile[] t = Ui.grid(this, card, captions, 2, 30);
        String avgHr = s.avgHr > 0 ? String.valueOf(s.avgHr) : "--";
        String maxHr = s.maxHr > 0 ? String.valueOf(s.maxHr) : "--";
        String load = String.valueOf(Math.round(s.trimp));
        t[0].set(Training.duration(s.activeMs));
        if (distance) {
            t[1].set(Training.km(s.distanceM));
            t[2].set(Training.pace(s.avgSpeed()));
            t[3].set(Training.pace(s.bestSpeed));
            t[4].set(avgHr);
            t[5].set(maxHr);
            t[6].set(String.valueOf(Math.round(s.kcal)));
            t[7].set(load);
        } else {
            t[1].set(String.valueOf(Math.round(s.kcal)));
            t[2].set(avgHr);
            t[3].set(maxHr);
            t[4].set(load);
            t[5].set(s.avgHr > 0 ? Training.percentOfMax(s.avgHr, s.hrMax) + "%" : "--");
        }

        long[] walked = s.walkTotals();
        if (walked[0] > 0) {
            int breaks = 0;
            for (long[] part : s.parts) {
                breaks += part[0] == 1 ? 1 : 0;
            }
            card.addView(Ui.text(this, breaks + " walk break" + (breaks == 1 ? "" : "s") + " \u00b7 "
                    + Training.duration(walked[0]) + " \u00b7 "
                    + Training.km(walked[1]) + " km, included above", 14, Ui.TEXT2));
        }

        if (!askFirst) {
            howCard(root);
        }

        // ---- the coach's debrief
        if (!s.coachNote.isEmpty() || Coach.get(this).isEnabled()) {
            coachBox = card(root);
            fillCoach();
        }

        // ---- what it trained
        LinearLayout effort = card(root);
        effort.addView(Ui.bold(Ui.text(this, Training.effort(s.zoneMs), 19, Ui.TEXT)));
        effort.addView(Ui.text(this, "Training load " + Math.round(s.trimp) + " \u00b7 "
                + Training.loadLabel(s.trimp) + "  (Edwards TRIMP: minutes in each zone \u00d7 "
                + "zone number)", 13, Ui.TEXT2));

        // ---- zones
        section(root, "Heart rate zones (HRmax " + s.hrMax + ")");
        ZoneBarsView zones = new ZoneBarsView(this);
        zones.setData(s.zoneMs, s.hrMax);
        card(root).addView(zones);

        // ---- charts
        if (s.avgHr > 0) {
            section(root, "Heart rate");
            LineChartView hrChart = new LineChartView(this);
            fillHrChart(hrChart, samples);
            card(root).addView(hrChart);
        }
        if (distance) {
            section(root, "Pace");
            LineChartView paceChart = new LineChartView(this);
            fillPaceChart(paceChart, samples);
            card(root).addView(paceChart);

            RouteView route = new RouteView(this);
            if (route.setData(samples, s.hrMax)) {
                section(root, "Route (coloured by zone)");
                card(root).addView(route);
            }
        }

        // ---- splits
        if (!s.splits.isEmpty()) {
            section(root, "Splits");
            LinearLayout splits = card(root);
            for (int i = 0; i < s.splits.size(); i++) {
                int[] sp = s.splits.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
                row.addView(Ui.text(this, "km " + (i + 1), 15, Ui.TEXT2),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(Ui.bold(Ui.text(this, Training.pace(1000.0 / Math.max(1, sp[0]))
                        + " /km", 15, Ui.TEXT)), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView hrText = Ui.text(this, sp[1] > 0 ? "\u2665 " + sp[1] : "", 15,
                        sp[1] > 0 ? Ui.ZONE_COLORS[Training.zone(sp[1], s.hrMax)] : Ui.TEXT2);
                hrText.setGravity(Gravity.END);
                row.addView(hrText, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                splits.addView(row);
            }
        }

        // ---- notes
        section(root, "Notes");
        notes = new EditText(this);
        notes.setHint("Training notes");
        notes.setText(s.notes);
        notes.setTextColor(Ui.TEXT);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setMinLines(2);
        root.addView(notes);

        // ---- actions
        section(root, "Export");
        root.addView(Ui.text(this, "TCX keeps heart rate, distance and route: import it into "
                + "Strava, Garmin Connect, TrainingPeaks, Runalyze\u2026 GPX is the route with "
                + "heart rate, for maps.", 13, Ui.TEXT3));
        Button tcx = button(root, "Share TCX", false);
        tcx.setOnClickListener(v -> share("tcx"));
        if (distance) {
            Button gpx = button(root, "Share GPX", false);
            gpx.setOnClickListener(v -> share("gpx"));
        }
        Button delete = button(root, "Delete session", false);
        delete.setTextColor(0xFFFF453A);
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Delete this session? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    SessionStore.delete(this, summary.id);
                    Coach.get(this).sessionDeleted(summary);
                    summary = null;
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show());

        setContentView(scroll);
    }

    // ------------------------------------------------------------ how was it

    /** The talk test and how it felt: what the coach checks the zones against. */
    private void howCard(LinearLayout root) {
        LinearLayout c = card(root);
        TextView label = Ui.text(this, "HOW WAS IT?", 12, Ui.TEXT2);
        label.setLetterSpacing(0.08f);
        c.addView(label);
        c.addView(padTop(Ui.text(this, "In the main part, could you talk?", 15, Ui.TEXT)));
        talkRow = new LinearLayout(this);
        for (int i = 0; i < TALK.length; i++) {
            final int value = i + 1;
            TextView chip = Ui.chip(this, TALK[i]);
            chip.setSelected(summary.talk == value);
            chip.setOnClickListener(v -> setTalk(value));
            talkRow.addView(chip);
        }
        c.addView(fullRow(talkRow));
        c.addView(padTop(Ui.text(this, "The coach checks your zones against this: if \"easy\" "
                + "runs keep coming out as hard by heart rate, your max heart rate may be off.",
                13, Ui.TEXT3)));
        c.addView(padTop(Ui.text(this, "How did it feel?", 15, Ui.TEXT)));
        feelRow = new LinearLayout(this);
        for (int i = 0; i < FEELINGS.length; i++) {
            final int value = i + 1;
            TextView chip = Ui.chip(this, FEELINGS[i]);
            chip.setSelected(summary.feeling == value);
            chip.setOnClickListener(v -> {
                summary.feeling = summary.feeling == value ? 0 : value;
                Ui.select(feelRow, summary.feeling - 1);
                saveSummary();
            });
            feelRow.addView(chip);
        }
        c.addView(scroller(feelRow));
    }

    private android.widget.HorizontalScrollView scroller(LinearLayout row) {
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.addView(row);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) sv.getLayoutParams()).topMargin = Ui.dp(this, 10);
        return sv;
    }

    /** The talk test options side by side, each with an equal share of the width. */
    private LinearLayout fullRow(LinearLayout row) {
        Ui.fillRow(row);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = Ui.dp(this, 10);
        return row;
    }

    private void setTalk(int value) {
        talkTouched = true;
        summary.talk = summary.talk == value ? 0 : value;
        Ui.select(talkRow, summary.talk - 1);
        saveSummary();
        if (summary.talk > 0) {
            HowWasIt.cancel(this);      // answered here: the notification is not needed
        }
        afterTalkChanged();
    }

    /**
     * A talk test answer arrived: a debrief still waiting for it starts, and one written
     * without it (a fresh session only, so old ones never spend a request) is written again.
     */
    private void afterTalkChanged() {
        if (coachBox == null || debriefing || summary.talk == 0) {
            return;
        }
        if (summary.coachNote.isEmpty()) {
            fillCoach();
        } else if (isFresh() && debriefUsedTalk() == 0) {
            debrief();
        }
    }

    private int debriefUsedTalk() {
        try {
            return CoachLogic.parseDebrief(Json.parseObject(summary.coachNote)).usedTalk;
        } catch (Json.ParseException e) {
            return 0;
        }
    }

    /** Finished within the last 3 hours: still the session they just did. */
    private boolean isFresh() {
        return System.currentTimeMillis() - Math.max(summary.end, summary.id) < 3 * 3600 * 1000L;
    }

    // ------------------------------------------------------------------ coach

    /**
     * Shows the stored debrief. For a fresh session of 5 minutes or more it is asked for by
     * itself: at once when the talk test is answered, otherwise after a short wait (an answer
     * during the wait starts it sooner). Anything older, or a false start, gets a button, so
     * opening an old session never spends a request.
     */
    private void fillCoach() {
        coachBox.removeAllViews();
        TextView label = Ui.text(this, "COACH", 12, Ui.TEXT2);
        label.setLetterSpacing(0.08f);
        coachBox.addView(label);
        CoachLogic.Debrief d = null;
        if (!summary.coachNote.isEmpty()) {
            try {
                d = CoachLogic.parseDebrief(Json.parseObject(summary.coachNote));
            } catch (Json.ParseException ignored) {
            }
        }
        if (d != null && !d.headline.isEmpty()) {
            TextView h = Ui.text(this, d.headline, 17, Ui.TEXT);
            h.setPadding(0, Ui.dp(this, 6), 0, 0);
            h.setLineSpacing(0, 1.15f);
            coachBox.addView(h);
            for (String pnt : d.points) {
                TextView t = Ui.text(this, "• " + pnt, 14, 0xFFC8C8CE);
                t.setPadding(0, Ui.dp(this, 6), 0, 0);
                coachBox.addView(t);
            }
            if (!d.next.isEmpty()) {
                TextView n = Ui.text(this, "Next: " + d.next, 14, 0xFF34C759);
                n.setPadding(0, Ui.dp(this, 8), 0, 0);
                coachBox.addView(n);
            }
            askBlock(d);
            if (debriefing) {
                coachBox.addView(padTop(Ui.text(this, "Updating with your answer…", 13,
                        Ui.TEXT2)));
            } else if (debriefError != null) {
                coachBox.addView(padTop(Ui.text(this, debriefError, 13, 0xFFFF6961)));
            }
            return;
        }
        if (debriefing) {
            coachBox.addView(padTop(Ui.text(this, "Reading your session…", 15, Ui.TEXT2)));
            return;
        }
        if (debriefError != null) {
            coachBox.addView(padTop(Ui.text(this, debriefError, 14, 0xFFFF6961)));
        }
        if (isFresh() && debriefError == null && summary.activeMs >= AUTO_DEBRIEF_MS) {
            if (summary.talk == 0 && !talkSkipped) {
                coachBox.addView(padTop(Ui.text(this, "The coach will debrief in a moment. "
                        + "Answer \"could you talk?\" and it will use it.", 15, Ui.TEXT2)));
                Button now = button(coachBox, "Debrief now", false);
                now.setOnClickListener(v -> {
                    handler.removeCallbacks(waitedForTalk);
                    waiting = false;
                    talkSkipped = true;
                    debrief();
                });
                if (!waiting) {
                    waiting = true;
                    handler.postDelayed(waitedForTalk, WAIT_FOR_TALK_MS);
                }
                return;
            }
            debrief();
            return;
        }
        Button ask = button(coachBox, debriefError != null ? "Try again" : "Ask the coach about this session", false);
        ask.setOnClickListener(v -> debrief());
    }

    /**
     * The coach's question about other sessions, as the talk test chips for each one still
     * unanswered. Answers go straight to those sessions and feed every later plan and debrief.
     */
    private void askBlock(CoachLogic.Debrief d) {
        List<SessionStore.Summary> open = new ArrayList<>();
        for (long id : d.askIds) {
            SessionStore.Summary other = id == summary.id ? null : SessionStore.load(this, id);
            if (other != null && other.talk == 0) {
                open.add(other);
            }
        }
        if (open.isEmpty()) {
            return;
        }
        coachBox.addView(padTop(Ui.bold(Ui.text(this, d.askText.isEmpty()
                ? "Could you talk during these?" : d.askText, 15, Ui.TEXT))));
        SimpleDateFormat when = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        for (SessionStore.Summary other : open) {
            coachBox.addView(padTop(Ui.text(this, other.sport + ", " + when.format(new Date(other.id))
                    + ", avg HR " + other.avgHr, 13, Ui.TEXT2)));
            LinearLayout row = new LinearLayout(this);
            for (int i = 0; i < TALK.length; i++) {
                final int value = i + 1;
                TextView chip = Ui.chip(this, TALK[i]);
                chip.setOnClickListener(v -> {
                    SessionStore.Summary fresh = SessionStore.load(this, other.id);
                    if (fresh != null) {
                        fresh.talk = value;
                        SessionStore.save(this, fresh);
                    }
                    fillCoach();
                });
                row.addView(chip);
            }
            coachBox.addView(fullRow(row));
        }
    }

    private void debrief() {
        debriefing = true;
        debriefError = null;
        Coach.get(this).debrief(summary, (d, err) -> {
            debriefing = false;
            debriefError = err;
            SessionStore.Summary fresh = SessionStore.load(this, summary.id);
            if (fresh != null && summary != null) {
                summary.coachNote = fresh.coachNote;
            }
            if (!isFinishing() && summary != null) {
                fillCoach();
            }
        });
        fillCoach();
    }

    /**
     * Saves feeling and notes without wiping what changed behind this screen: a debrief the
     * coach wrote meanwhile, or a talk test answer given on the notification.
     */
    private void saveSummary() {
        SessionStore.Summary disk = SessionStore.load(this, summary.id);
        if (disk != null && summary.coachNote.isEmpty()) {
            summary.coachNote = disk.coachNote;
        }
        if (disk != null && !talkTouched) {
            summary.talk = disk.talk;
        }
        SessionStore.save(this, summary);
    }

    private TextView padTop(TextView t) {
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        return t;
    }

    private void fillHrChart(LineChartView chart, List<Training.Sample> samples) {
        float[] v = bucket(samples, true);
        int lo = Integer.MAX_VALUE;
        int hi = 0;
        int[] colors = new int[v.length];
        for (int i = 0; i < v.length; i++) {
            if (Float.isNaN(v[i])) {
                continue;
            }
            lo = Math.min(lo, (int) v[i]);
            hi = Math.max(hi, (int) v[i]);
            colors[i] = Ui.ZONE_COLORS[Training.zone(Math.round(v[i]), summary.hrMax)];
        }
        if (hi == 0) {
            return;
        }
        float[] from = new float[5];
        int[] bandColors = new int[5];
        for (int z = 1; z <= 5; z++) {
            from[z - 1] = Training.zoneFloor(z, summary.hrMax);
            bandColors[z - 1] = Ui.ZONE_COLORS[z];
        }
        chart.setBands(from, bandColors);
        chart.setData(v, colors, lo - 5, hi + 5, false, summary.activeMs,
                x -> String.valueOf(Math.round(x)));
    }

    private void fillPaceChart(LineChartView chart, List<Training.Sample> samples) {
        float[] speed = bucket(samples, false);
        float[] pace = new float[speed.length];
        float fast = Float.MAX_VALUE;
        float slow = 0;
        for (int i = 0; i < speed.length; i++) {
            // walking-pace floor so a stop does not flatten the whole chart
            if (Float.isNaN(speed[i]) || speed[i] < 1000f / (20 * 60)) {
                pace[i] = Float.NaN;
                continue;
            }
            pace[i] = 1000f / speed[i];
            fast = Math.min(fast, pace[i]);
            slow = Math.max(slow, pace[i]);
        }
        if (slow == 0) {
            return;
        }
        chart.setData(pace, null, fast - 15, slow + 15, true, summary.activeMs,
                x -> Training.pace(1000.0 / x));
    }

    /**
     * Averages the samples into at most ~300 buckets of moving time: heart rate, or speed
     * from distance covered per bucket. Pauses are skipped, matching the moving-time axis.
     */
    private float[] bucket(List<Training.Sample> samples, boolean heartRate) {
        int count = 0;
        for (Training.Sample s : samples) {
            if (s != null) {
                count++;
            }
        }
        int buckets = Math.max(1, Math.min(300, count / 5));
        int per = Math.max(1, (int) Math.ceil(count / (double) buckets));
        float[] out = new float[(count + per - 1) / per];
        int i = 0;
        int n = 0;
        double sum = 0;
        int have = 0;
        Training.Sample prev = null;
        double moving = 0;      // seconds of movement inside the bucket
        for (Training.Sample s : samples) {
            if (s == null) {
                prev = null;
                continue;
            }
            if (heartRate) {
                if (s.bpm > 0) {
                    sum += s.bpm;
                    have++;
                }
            } else {
                if (prev != null) {
                    sum += Math.max(0, s.dist - prev.dist);
                    moving += Math.min(Training.MAX_TICK_MS, s.t - prev.t) / 1000.0;
                }
                prev = s;
            }
            n++;
            if (n == per) {
                out[i++] = heartRate
                        ? (have > 0 ? (float) (sum / have) : Float.NaN)
                        : (moving > 0 ? (float) (sum / moving) : Float.NaN);
                n = 0;
                sum = 0;
                have = 0;
                moving = 0;
            }
        }
        if (n > 0 && i < out.length) {
            out[i] = heartRate
                    ? (have > 0 ? (float) (sum / have) : Float.NaN)
                    : (moving > 0 ? (float) (sum / moving) : Float.NaN);
        }
        return out;
    }

    private void share(String format) {
        File f = SessionStore.export(this, summary, format);
        if (f == null) {
            Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show();
            return;
        }
        android.net.Uri uri = ExportProvider.uriFor(f);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("gpx".equals(format) ? "application/gpx+xml" : "application/vnd.garmin.tcx+xml")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, f.getName())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(f.getName(), uri));
        startActivity(Intent.createChooser(send, "Share " + format.toUpperCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------------ layout

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        parent.addView(c, lp);
        return c;
    }

    private void section(LinearLayout parent, String title) {
        TextView t = Ui.text(this, title.toUpperCase(Locale.getDefault()), 13, Ui.TEXT2);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 22), 0, 0);
        parent.addView(t);
    }

    private Button button(LinearLayout parent, String text, boolean primary) {
        Button b = new Button(this, null, 0,
                primary ? R.style.PrimaryButton : R.style.SecondaryButton);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        lp.topMargin = Ui.dp(this, 10);
        parent.addView(b, lp);
        b.setVisibility(View.VISIBLE);
        return b;
    }
}
