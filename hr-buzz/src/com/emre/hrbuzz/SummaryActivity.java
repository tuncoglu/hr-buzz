package com.emre.hrbuzz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
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

    private SessionStore.Summary summary;
    private EditText notes;
    private LinearLayout coachBox;
    private boolean debriefing;
    private String debriefError;

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
    protected void onPause() {
        super.onPause();
        if (summary != null && notes != null) {
            String text = notes.getText().toString().trim();
            if (!text.equals(summary.notes)) {
                summary.notes = text;
                saveSummary();
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

        // ---- feeling + notes
        section(root, "How did it feel?");
        LinearLayout feel = new LinearLayout(this);
        for (int i = 0; i < FEELINGS.length; i++) {
            final int value = i + 1;
            TextView chip = Ui.chip(this, FEELINGS[i]);
            chip.setSelected(s.feeling == value);
            chip.setOnClickListener(v -> {
                summary.feeling = summary.feeling == value ? 0 : value;
                Ui.select(feel, summary.feeling - 1);
                saveSummary();
            });
            feel.addView(chip);
        }
        android.widget.HorizontalScrollView feelScroll = new android.widget.HorizontalScrollView(this);
        feelScroll.setHorizontalScrollBarEnabled(false);
        feelScroll.addView(feel);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.topMargin = Ui.dp(this, 10);
        root.addView(feelScroll, fl);

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

    /**
     * Shows the stored debrief; for a session that just finished, asks for one by itself,
     * otherwise offers a button (so opening an old session never spends a request).
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
                TextView t = Ui.text(this, "\u2022 " + pnt, 14, 0xFFC8C8CE);
                t.setPadding(0, Ui.dp(this, 6), 0, 0);
                coachBox.addView(t);
            }
            if (!d.next.isEmpty()) {
                TextView n = Ui.text(this, "Next: " + d.next, 14, 0xFF34C759);
                n.setPadding(0, Ui.dp(this, 8), 0, 0);
                coachBox.addView(n);
            }
            return;
        }
        if (debriefing) {
            coachBox.addView(padTop(Ui.text(this, "Reading your session\u2026", 15, Ui.TEXT2)));
            return;
        }
        if (debriefError != null) {
            coachBox.addView(padTop(Ui.text(this, debriefError, 14, 0xFFFF6961)));
        }
        boolean fresh = System.currentTimeMillis() - Math.max(summary.end, summary.id)
                < 3 * 3600 * 1000L;
        if (fresh && debriefError == null) {
            debrief();
            return;
        }
        Button ask = button(coachBox, debriefError != null ? "Try again" : "Ask the coach about this session", false);
        ask.setOnClickListener(v -> debrief());
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

    /** Saves feeling/notes without wiping a debrief the coach wrote meanwhile. */
    private void saveSummary() {
        SessionStore.Summary disk = SessionStore.load(this, summary.id);
        if (disk != null && summary.coachNote.isEmpty()) {
            summary.coachNote = disk.coachNote;
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
