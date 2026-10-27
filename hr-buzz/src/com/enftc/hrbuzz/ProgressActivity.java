package com.enftc.hrbuzz;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Whether training is working, in numbers worked out on the phone from the sessions: the
 * weeks side by side, pace against heart rate, and how steady each session was. The coach
 * reads exactly these figures, so what it says about progress can be checked here.
 */
public class ProgressActivity extends Activity {

    private static final int WEEKS = 8;

    private Coach coach;
    private LinearLayout root;
    private LinearLayout readBox;
    private boolean asking;
    private String reply;
    private String error;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        coach = Coach.get(this);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 28));
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        build();
    }

    private void build() {
        root.removeAllViews();
        root.addView(Ui.bold(Ui.text(this, "Progress", 26, Ui.TEXT)));
        root.addView(padTop(Ui.text(this, "Worked out on your phone from your sessions. The coach "
                + "is given the same figures.", 14, Ui.TEXT2), 6));

        List<CoachLogic.SessionInfo> sessions = coach.sessions();
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        List<CoachLogic.WeekRow> weeks = CoachLogic.weekly(sessions, now, zone, WEEKS);
        boolean any = false;
        for (CoachLogic.WeekRow w : weeks) {
            any |= w.sessions > 0;
        }
        if (!any) {
            root.addView(padTop(Ui.text(this, "Nothing recorded in the last " + WEEKS
                    + " weeks yet.", 15, Ui.TEXT), 16));
            return;
        }

        // ---- each week
        section("Each week");
        LinearLayout card = card();
        for (int i = 0; i < weeks.size(); i++) {
            CoachLogic.WeekRow w = weeks.get(i);
            if (w.sessions == 0) {
                // a run of empty weeks is one line, not one card row each
                int j = i;
                while (j + 1 < weeks.size() && weeks.get(j + 1).sessions == 0) {
                    j++;
                }
                card.addView(heading(i == j ? weekLabel(i) : i + "\u2013" + j + " weeks ago",
                        card.getChildCount() == 0));
                card.addView(Ui.text(this, "No sessions", 14, Ui.TEXT3));
                i = j;
                continue;
            }
            card.addView(weekHeading(w, card.getChildCount() == 0));
            card.addView(Ui.bold(Ui.text(this, Training.km(w.km * 1000) + " km · "
                    + hoursMinutes(w.activeMs) + " · " + w.sessions
                    + (w.sessions == 1 ? " session" : " sessions"), 16, Ui.TEXT)));
            card.addView(Ui.text(this, "Load " + Math.round(w.load) + (w.easyPct >= 0
                    ? " · " + w.easyPct + "% of heart rate time in zones 1–2" : ""),
                    13, Ui.TEXT2));
        }
        root.addView(padTop(Ui.text(this, "Load is the Edwards figure on each summary (minutes "
                + "in each zone × zone number), added up. A week's \"easy\" share is where "
                + "the 80/20 rule is checked.", 12, Ui.TEXT3), 6));

        // ---- pace against heart rate
        boolean pace = false;
        for (CoachLogic.WeekRow w : weeks) {
            pace |= w.runPace > 0 || w.walkPace > 0;
        }
        if (pace) {
            section("Pace and heart rate");
            LinearLayout pc = card();
            for (CoachLogic.WeekRow w : weeks) {
                if (w.runPace == 0 && w.walkPace == 0) {
                    continue;
                }
                pc.addView(weekHeading(w, pc.getChildCount() == 0));
                if (w.runPace > 0) {
                    pc.addView(Ui.text(this, "Run   " + split(w.runPace, w.runBpm, w.runMPerBeat),
                            15, Ui.TEXT));
                }
                if (w.walkPace > 0) {
                    pc.addView(Ui.text(this, "Walk  " + split(w.walkPace, w.walkBpm,
                            w.walkMPerBeat), 15, Ui.TEXT));
                }
            }
            root.addView(padTop(Ui.text(this, "Medians over each week's km splits. Fitter shows "
                    + "as more metres per heartbeat, or a faster pace at the same heart rate. "
                    + "Compare weeks of the same sport; one or two weeks are not a trend.", 12,
                    Ui.TEXT3), 6));
        }

        // ---- steadiness
        List<CoachLogic.Drift> drifts = CoachLogic.drifts(sessions, now, zone);
        if (!drifts.isEmpty()) {
            section("Steadiness within a session");
            LinearLayout dc = card();
            SimpleDateFormat when = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
            for (CoachLogic.Drift d : drifts) {
                LinearLayout row = new LinearLayout(this);
                row.addView(Ui.text(this, when.format(new Date(d.start)) + "  " + d.sport, 15,
                        Ui.TEXT), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(Ui.text(this, String.format(Locale.ROOT, "%.1f%%  %s", d.pct,
                        d.pct < 5 ? "steady" : d.pct <= 10 ? "some drift" : "drifted"), 15,
                        d.pct < 5 ? 0xFF34C759 : d.pct <= 10 ? 0xFFFFC400 : 0xFFFF6961));
                dc.addView(row);
            }
            root.addView(padTop(Ui.text(this, "Under 5%: heart rate held steady against pace. "
                    + "Over 10%: it crept up (tired, hot, or faster than your base for now).",
                    12, Ui.TEXT3), 6));
        }

        // ---- resting tests
        List<CoachLogic.Test> tests = Prefs.fitnessTests(this);
        if (!tests.isEmpty()) {
            section("Resting tests");
            LinearLayout tc = card();
            SimpleDateFormat when = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
            for (int i = tests.size() - 1; i >= 0 && tc.getChildCount() < 5; i--) {
                CoachLogic.Test t = tests.get(i);
                tc.addView(Ui.text(this, when.format(new Date(t.t)) + "   resting " + t.rest
                        + (t.rmssd > 0 ? "   HRV " + t.rmssd : "")
                        + (t.vo2 > 0 ? "   VO2max " + t.vo2 : ""), 15, Ui.TEXT));
            }
        }

        // ---- the coach's read, only when asked for
        if (coach.isEnabled()) {
            section("The coach's read");
            readBox = card();
            fillRead();
        }
    }

    private void fillRead() {
        readBox.removeAllViews();
        if (asking) {
            readBox.addView(Ui.text(this, "Reading your numbers…", 15, Ui.TEXT2));
            return;
        }
        if (reply != null) {
            TextView t = Ui.text(this, reply, 15, Ui.TEXT);
            t.setLineSpacing(0, 1.15f);
            readBox.addView(t);
        }
        if (error != null) {
            readBox.addView(padTop(Ui.text(this, error, 14, 0xFFFF6961), 6));
        }
        Button ask = new Button(this, null, 0, R.style.SecondaryButton);
        ask.setText(reply == null ? "Ask the coach what this says" : "Ask again");
        ask.setAllCaps(false);
        ask.setOnClickListener(v -> askCoach());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        lp.topMargin = Ui.dp(this, 10);
        readBox.addView(ask, lp);
    }

    private void askCoach() {
        asking = true;
        error = null;
        fillRead();
        coach.ask(CoachLogic.IMPROVING, (a, err) -> {
            asking = false;
            reply = a == null ? null : a.reply;
            error = err;
            if (!isFinishing() && readBox != null) {
                fillRead();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static String split(int paceSec, int bpm, double metresPerBeat) {
        StringBuilder sb = new StringBuilder(Training.pace(1000.0 / paceSec)).append(" /km");
        if (bpm > 0) {
            sb.append(" at ").append(bpm).append(" bpm");
        }
        if (metresPerBeat > 0) {
            sb.append(String.format(Locale.ROOT, " · %.2f m per beat", metresPerBeat));
        }
        return sb.toString();
    }

    private static String hoursMinutes(long ms) {
        return Training.duration(ms).replaceFirst(":\\d\\d$", "");
    }

    private static String weekLabel(int weeksAgo) {
        return weeksAgo == 0 ? "This week" : weeksAgo == 1 ? "Last week" : weeksAgo + " weeks ago";
    }

    private TextView weekHeading(CoachLogic.WeekRow w, boolean first) {
        return heading(weekLabel(w.weeksAgo), first);
    }

    private TextView heading(String label, boolean first) {
        TextView t = Ui.text(this, label, 12, Ui.TEXT2);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, first ? 0 : Ui.dp(this, 14), 0, Ui.dp(this, 2));
        return t;
    }

    private void section(String title) {
        TextView t = Ui.text(this, title.toUpperCase(Locale.getDefault()), 13, Ui.TEXT2);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 22), 0, 0);
        root.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        root.addView(c, lp);
        return c;
    }

    private TextView padTop(TextView t, int dp) {
        t.setPadding(0, Ui.dp(this, dp), 0, 0);
        return t;
    }
}
