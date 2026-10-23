package com.emre.hrbuzz;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What a finished run came to: distance, time, heart rate, time in each zone, splits, how it
 * felt, and the coach's debrief.
 */
public class WearSummaryActivity extends Activity {

    static final String EXTRA_ID = "id";
    /** Opened straight after Finish: a long enough run gets its debrief without asking. */
    static final String EXTRA_FRESH = "fresh";

    /** Shorter sessions (false starts, tests) get a debrief only on request: each costs a call. */
    private static final long AUTO_DEBRIEF_MS = 5 * 60000L;
    private static final String[] TALK = {"Full sentences", "Short phrases", "A few words"};
    private static final String[] FEEL = {"Awful", "Poor", "OK", "Good", "Great"};

    private SessionStore.Summary summary;
    private boolean fresh;
    private ScrollView scroll;
    private boolean debriefing;
    private String debriefError;
    private boolean talkSkipped;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        summary = SessionStore.load(this, getIntent().getLongExtra(EXTRA_ID, -1));
        if (summary == null) {
            finish();
            return;
        }
        fresh = getIntent().getBooleanExtra(EXTRA_FRESH, false);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Wu.BLACK);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        setContentView(scroll);
        build();
        scroll.requestFocus();      // so the crown scrolls
        maybeDebrief();
    }

    private void build() {
        SessionStore.Summary s = summary;
        int side = Wu.px(this, 60);
        LinearLayout page = Wu.column(this);
        page.setPadding(side, Wu.px(this, 46), side, Wu.px(this, 60));

        String when = new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new Date(s.id));
        page.addView(Wu.text(this, "Run done · " + when, 17, Wu.MUTED, true));
        page.addView(Wu.space(this, 6));

        if (s.gps) {
            LinearLayout big = Wu.row(this);
            big.setGravity(Gravity.CENTER);
            big.addView(Wu.text(this, Training.km(s.distanceM), 60, Wu.TEXT, true));
            big.addView(Wu.text(this, " km", 22, Wu.MUTED, true));
            page.addView(big);
        } else {
            page.addView(Wu.text(this, Training.duration(s.activeMs), 54, Wu.TEXT, true));
        }
        page.addView(Wu.space(this, 4));
        String line = (s.gps ? Training.duration(s.activeMs) + "   " : "")
                + (s.avgHr > 0 ? "avg " + s.avgHr + " bpm" : "no heart rate");
        page.addView(Wu.text(this, line, 17, Wu.MUTED, true));
        page.addView(Wu.space(this, 20));

        zones(page, s);
        page.addView(Wu.space(this, 14));

        // the numbers
        if (s.maxHr > 0) {
            page.addView(stat("Max HR", s.maxHr + " bpm"));
        }
        if (s.gps && s.activeMs > 0) {
            page.addView(stat("Avg pace", Training.pace(s.avgSpeed()) + " /km"));
            if (s.bestSpeed > 0) {
                page.addView(stat("Best pace", Training.pace(s.bestSpeed) + " /km"));
            }
        }
        if (s.steps > 0) {
            page.addView(stat("Steps", String.format(Locale.getDefault(), "%,d", s.steps)));
            page.addView(stat("Cadence", s.cadence + " spm"));
        }
        if (s.kcal > 0) {
            page.addView(stat("Calories", Math.round(s.kcal) + " kcal"));
        }
        if (s.trimp > 0) {
            page.addView(Wu.space(this, 6));
            page.addView(Wu.text(this, Training.effort(s.zoneMs), 17, Wu.TEXT, true));
            page.addView(Wu.text(this, "Load " + Math.round(s.trimp) + " · "
                    + Training.loadLabel(s.trimp), 15, Wu.MUTED, false));
        }
        if (!s.planTitle.isEmpty() && s.targetPct >= 0) {
            page.addView(Wu.space(this, 6));
            page.addView(Wu.text(this, s.planTitle + ": " + s.targetPct + "% in target", 15,
                    Wu.TARGET_TEXT, true));
        }

        splits(page, s);

        // how hard it really was, whatever the zones say
        page.addView(Wu.space(this, 20));
        page.addView(Wu.text(this, "Could you talk?", 17, Wu.MUTED, true));
        page.addView(Wu.space(this, 6));
        for (int i = 0; i < TALK.length; i++) {
            int value = i + 1;
            page.addView(choice(TALK[i], s.talk == value, () -> {
                summary.talk = summary.talk == value ? 0 : value;
                save();
                rebuild();
                maybeDebrief();
            }));
            page.addView(Wu.space(this, 4));
        }
        page.addView(Wu.space(this, 14));
        page.addView(Wu.text(this, "How did it feel?", 17, Wu.MUTED, true));
        page.addView(Wu.space(this, 6));
        for (int i = 0; i < FEEL.length; i++) {
            int value = i + 1;
            page.addView(choice(FEEL[i], s.feeling == value, () -> {
                summary.feeling = summary.feeling == value ? 0 : value;
                save();
                rebuild();
            }));
            page.addView(Wu.space(this, 4));
        }

        coach(page, s);

        page.addView(Wu.space(this, 16));
        Button done = Wu.button(this, "Done", Wu.BUTTON, Wu.TEXT, 48);
        done.setOnClickListener(v -> finish());
        page.addView(done);

        scroll.removeAllViews();
        scroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Rebuilds without jumping back to the top. */
    private void rebuild() {
        int y = scroll.getScrollY();
        build();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void zones(LinearLayout page, SessionStore.Summary s) {
        int[] pct = WearLogic.zonePercents(s.zoneMs);
        int top = 0;
        for (int z = 0; z < pct.length; z++) {
            if (pct[z] > pct[top]) {
                top = z;
            }
        }
        if (pct[top] == 0) {
            return;
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Wu.pill(Wu.CARD, this, 11));
        bar.setClipToOutline(true);
        bar.setLayoutParams(Wu.lp(ViewGroup.LayoutParams.MATCH_PARENT, Wu.px(this, 22)));
        bar.setContentDescription(describe(pct));
        for (int z = 0; z < pct.length; z++) {
            if (pct[z] == 0) {
                continue;
            }
            View seg = new View(this);
            seg.setBackgroundColor(Wu.ZONE[z + 1]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, pct[z]);
            lp.rightMargin = z < pct.length - 1 ? Wu.px(this, 2) : 0;
            bar.addView(seg, lp);
        }
        page.addView(bar);
        page.addView(Wu.space(this, 10));
        page.addView(Wu.text(this, pct[top] + "% in zone " + (top + 1), 17, Wu.ZONE[top + 1], true));
    }

    private void splits(LinearLayout page, SessionStore.Summary s) {
        if (s.splits.isEmpty()) {
            return;
        }
        page.addView(Wu.space(this, 20));
        page.addView(Wu.text(this, "Km splits", 17, Wu.MUTED, true));
        page.addView(Wu.space(this, 6));
        int km = 0;
        for (int[] sp : s.splits) {
            km++;
            String pace = Training.pace(1000.0 / Math.max(1, sp[0]));
            page.addView(stat("Km " + km, pace + (sp[1] > 0 ? " · " + sp[1] : "")));
        }
    }

    private View stat(String label, String value) {
        LinearLayout r = Wu.row(this);
        r.setLayoutParams(Wu.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView l = Wu.text(this, label, 16, Wu.MUTED, false);
        l.setGravity(Gravity.START);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(Wu.text(this, value, 16, Wu.TEXT, true));
        r.setPadding(0, Wu.px(this, 2), 0, Wu.px(this, 2));
        return r;
    }

    private Button choice(String label, boolean selected, Runnable pick) {
        Button b = Wu.button(this, label, selected ? Wu.TARGET_BG : Wu.CARD,
                selected ? Wu.TARGET_TEXT : Wu.TEXT, 44);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * Wu.unit(this));
        b.setSelected(selected);
        b.setOnClickListener(v -> pick.run());
        return b;
    }

    /** Saves talk test / feeling without wiping a debrief the coach wrote meanwhile. */
    private void save() {
        SessionStore.Summary latest = SessionStore.load(this, summary.id);
        if (latest == null) {
            return;
        }
        latest.talk = summary.talk;
        latest.feeling = summary.feeling;
        SessionStore.save(this, latest);
        summary.coachNote = latest.coachNote;
    }

    // ------------------------------------------------------------------- coach

    private void coach(LinearLayout page, SessionStore.Summary s) {
        Coach coach = Coach.get(this);
        if (!coach.isEnabled()) {
            return;
        }
        page.addView(Wu.space(this, 20));
        page.addView(Wu.text(this, "Coach", 17, Wu.MUTED, true));
        page.addView(Wu.space(this, 6));

        CoachLogic.Debrief d = null;
        if (!s.coachNote.isEmpty()) {
            try {
                d = CoachLogic.parseDebrief(Json.parseObject(s.coachNote));
            } catch (Json.ParseException ignored) {
            }
        }
        if (d != null) {
            page.addView(Wu.text(this, d.headline, 18, Wu.TEXT, true));
            for (String point : d.points) {
                page.addView(Wu.space(this, 6));
                page.addView(Wu.text(this, point, 15, Wu.MUTED, false));
            }
            if (!d.next.isEmpty()) {
                page.addView(Wu.space(this, 8));
                page.addView(Wu.text(this, "Next: " + d.next, 15, Wu.TEXT, false));
            }
            return;
        }
        if (debriefing) {
            page.addView(Wu.text(this, "The coach is reading your run…", 16, Wu.MUTED, true));
            return;
        }
        if (debriefError != null) {
            page.addView(Wu.text(this, debriefError, 15, Wu.ALERT, true));
            page.addView(Wu.space(this, 6));
        }
        if (fresh && debriefError == null && s.activeMs >= AUTO_DEBRIEF_MS && s.talk == 0
                && !talkSkipped) {
            page.addView(Wu.text(this, "Answer \"could you talk?\" and the coach will debrief "
                    + "this run with it.", 15, Wu.MUTED, false));
            page.addView(Wu.space(this, 6));
            Button now = Wu.button(this, "Debrief without it", Wu.BUTTON, Wu.TEXT, 44);
            now.setOnClickListener(v -> {
                talkSkipped = true;
                debrief();
            });
            page.addView(now);
            return;
        }
        Button ask = Wu.button(this, debriefError != null ? "Try again" : "Ask the coach",
                Wu.BUTTON, Wu.TEXT, 48);
        ask.setOnClickListener(v -> debrief());
        page.addView(ask);
    }

    /** A run that just finished asks for its debrief by itself once the talk test is answered. */
    private void maybeDebrief() {
        if (fresh && Coach.get(this).isEnabled() && summary.coachNote.isEmpty() && !debriefing
                && debriefError == null && summary.activeMs >= AUTO_DEBRIEF_MS
                && summary.talk != 0) {
            debrief();
        }
    }

    private void debrief() {
        debriefing = true;
        debriefError = null;
        rebuild();
        Coach.get(this).debrief(summary, (d, err) -> {
            debriefing = false;
            debriefError = err;
            if (d != null) {
                SessionStore.Summary latest = SessionStore.load(this, summary.id);
                if (latest != null) {
                    summary = latest;
                }
            }
            if (!isFinishing()) {
                rebuild();
            }
        });
    }

    private static String describe(int[] pct) {
        StringBuilder sb = new StringBuilder("Time in zone: ");
        for (int z = 0; z < pct.length; z++) {
            sb.append(z == 0 ? "" : ", ").append("zone ").append(z + 1).append(' ')
                    .append(pct[z]).append(" percent");
        }
        return sb.toString();
    }
}
