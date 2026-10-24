package com.emre.hrbuzz;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The coach: readiness, this week at a glance, the next days, and a conversation where
 * answers come back as actions (an updated plan you can start or undo), not just text.
 */
public class CoachActivity extends Activity {

    private static final int REQ_SPEECH = 1;

    private Coach coach;
    private LinearLayout chips;
    private LinearLayout content;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private boolean busy;
    private String error;
    /** The plan an answer replaced, for Undo; null when there is nothing to undo. */
    private CoachLogic.Plan undo;
    private boolean showUpdated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        coach = Coach.get(this);
        if (!coach.isEnabled()) {
            startActivity(new Intent(this, CoachSetupActivity.class));
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);

        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ---- suggestion chips + input, pinned at the bottom
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 14));
        chips = new LinearLayout(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        bottom.addView(hs);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.card);
        bar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 6), 0);
        input = new EditText(this);
        input.setHint("Ask the coach\u2026");
        input.setTextColor(Ui.TEXT);
        input.setBackground(null);
        input.setTextSize(15);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendTyped();
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        Button mic = new Button(this, null, 0, R.style.SecondaryButton);
        mic.setText("Speak");
        mic.setAllCaps(false);
        mic.setTextSize(14);
        mic.setContentDescription("Speak to the coach");
        mic.setOnClickListener(v -> listen());
        bar.addView(mic, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)));
        send = new Button(this, null, 0, R.style.PrimaryButton);
        send.setText("Send");
        send.setAllCaps(false);
        send.setTextSize(14);
        send.setOnClickListener(v -> sendTyped());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 44));
        sl.leftMargin = Ui.dp(this, 6);
        bar.addView(send, sl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 8);
        bottom.addView(bar, bl);
        root.addView(bottom);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (content != null) {
            render();
        }
    }

    // ------------------------------------------------------------------ render

    private void render() {
        content.removeAllViews();

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.bold(Ui.text(this, "Coach", 26, Ui.TEXT)), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView progress = Ui.chip(this, "Progress");
        progress.setOnClickListener(v -> startActivity(new Intent(this, ProgressActivity.class)));
        head.addView(progress);
        TextView settings = Ui.chip(this, "Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, CoachSetupActivity.class)));
        head.addView(settings);
        content.addView(head);

        CoachLogic.Readiness r = coach.readiness();
        TextView ready = Ui.text(this, r.level == CoachLogic.Readiness.UNKNOWN
                ? "Do a fitness test in the morning and the coach will factor in how rested you are."
                : r.text, 14, r.level == CoachLogic.Readiness.READY ? 0xFF34C759
                : r.level == CoachLogic.Readiness.UNKNOWN ? Ui.TEXT2 : 0xFFFFC400);
        ready.setPadding(0, Ui.dp(this, 6), 0, 0);
        content.addView(ready);

        renderWeek();

        CoachLogic.Plan plan = coach.todayPlan();
        if (plan != null) {
            section(plan.rest ? "Today" : "Today's plan");
            LinearLayout today = card();
            double km = plan.rest ? 0 : coach.estimatedKm(plan);
            today.addView(Ui.bold(Ui.text(this, plan.rest ? plan.title
                    : plan.title + " \u00b7 " + plan.totalMinutes() + " min", 19, Ui.TEXT)));
            if (km > 0) {
                today.addView(gap(Ui.text(this, "About " + CoachLogic.kmText(km)
                        + " km at your usual pace", 13, Ui.TEXT2), 2));
            }
            if (!plan.message.isEmpty()) {
                today.addView(gap(Ui.text(this, plan.message, 15, 0xFFC8C8CE), 6));
            }
            if (!plan.rest) {
                today.addView(Ui.stepsBar(this, plan), lpTop(12));
            }
            for (String a : plan.adjusted) {
                today.addView(gap(Ui.text(this, "\u2022 " + a, 13, 0xFFFFC400), 4));
            }
            // An updated plan has its own Start below, in the conversation.
            if (!plan.rest && !coach.todayDone() && !showUpdated) {
                Button start = startButton();
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
                slp.topMargin = Ui.dp(this, 14);
                today.addView(start, slp);
            }
        }
        if (plan != null && !plan.week.isEmpty()) {
            section("Next days");
            LinearLayout list = card();
            for (CoachLogic.Day d : plan.week) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(Ui.dp(this, 40));
                TextView day = Ui.text(this, CoachLogic.weekday(d.date), 13, Ui.TEXT2);
                day.setMinWidth(Ui.dp(this, 44));
                row.addView(day);
                row.addView(Ui.text(this, d.minutes > 0 ? d.title + " \u00b7 " + d.minutes + "\u2032"
                        : d.title, 15, Ui.TEXT), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                if (d.zone > 0) {
                    TextView z = Ui.text(this, "Z" + d.zone, 12, 0xFF0E0E10);
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setCornerRadius(Ui.dp(this, 10));
                    bg.setColor(Ui.ZONE_COLORS[d.zone]);
                    z.setBackground(bg);
                    z.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
                    row.addView(z);
                }
                list.addView(row);
            }
        }

        List<String> kept = coach.remembered();
        if (!kept.isEmpty()) {
            section("What the coach keeps in mind");
            LinearLayout list = card();
            for (String item : kept) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(Ui.text(this, "\u2022 " + item, 14, 0xFFC8C8CE),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView remove = Ui.text(this, "Forget", 13, 0xFF4F8EF7);
                remove.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), 0, Ui.dp(this, 8));
                remove.setOnClickListener(v -> {
                    coach.forgetItem(item);
                    render();
                });
                row.addView(remove);
                list.addView(row);
            }
            list.addView(gap(Ui.text(this, "Picked up from your messages and sent with every "
                    + "plan.", 12, Ui.TEXT3), 6));
        }

        section("Conversation");
        List<String[]> chat = coach.chat();
        if (chat.isEmpty()) {
            content.addView(gap(Ui.text(this, "Ask anything, or tap a suggestion below: "
                    + "\u201cI only have 20 minutes\u201d changes today's plan for you.", 14,
                    Ui.TEXT2), 6));
        }
        for (String[] turn : chat) {
            content.addView(bubble(turn[1], "you".equals(turn[0])));
        }
        if (showUpdated && plan != null) {
            content.addView(updatedCard(plan));
        }
        if (busy) {
            content.addView(gap(Ui.text(this, "Coach is thinking\u2026", 14, Ui.TEXT2), 8));
        }
        if (error != null) {
            content.addView(gap(Ui.text(this, error, 14, 0xFFFF6961), 8));
        }
        send.setEnabled(!busy);
        renderChips();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Suggestions that fit today: before a run, after it, a rest day, a hard week. */
    private void renderChips() {
        CoachLogic.Plan plan = coach.todayPlan();
        CoachLogic.ChipState st = new CoachLogic.ChipState();
        st.hasPlan = plan != null;
        st.rest = plan != null && plan.rest;
        st.done = coach.todayDone();
        st.hardWeek = CoachLogic.hardWeek(coach.sessions(), System.currentTimeMillis());
        chips.removeAllViews();
        for (String text : CoachLogic.suggestions(st)) {
            TextView c = Ui.chip(this, text);
            c.setTextSize(14);
            c.setMinHeight(Ui.dp(this, 40));
            c.setOnClickListener(v -> onChip(text));
            chips.addView(c);
        }
    }

    /** Two suggestions need no request: the plan's own reasons, and the Progress screen. */
    private void onChip(String text) {
        if (busy) {
            return;
        }
        if (CoachLogic.IMPROVING.equals(text)) {
            startActivity(new Intent(this, ProgressActivity.class));
        } else if ((CoachLogic.WHY_PLAN.equals(text) || CoachLogic.WHY_REST.equals(text))
                && coach.explainPlan(text)) {
            error = null;
            render();
        } else {
            ask(text);
        }
    }

    /** Last 7 days against the 7 before: sessions, minutes, load change, easy share. */
    private void renderWeek() {
        List<CoachLogic.SessionInfo> sessions = coach.sessions();
        long now = System.currentTimeMillis();
        long weekAgo = now - 7 * 86400000L;
        int count = 0;
        long ms = 0;
        long easy = 0;
        long zoned = 0;
        for (CoachLogic.SessionInfo s : sessions) {
            if (s.start < weekAgo) {
                continue;
            }
            count++;
            ms += s.activeMs;
            easy += s.zoneMs[1] + s.zoneMs[2];
            for (int z = 1; z <= 5; z++) {
                zoned += s.zoneMs[z];
            }
        }
        double[] loads = CoachLogic.recentLoads(sessions, now, java.time.ZoneId.systemDefault());
        section("Last 7 days");
        LinearLayout card = card();
        Ui.Tile[] t = Ui.grid(this, card, new String[]{"Sessions", "Time", "Load vs week before",
                "Easy (Z1\u20132)"}, 4, 18);
        t[0].set(String.valueOf(count));
        t[1].set(Training.duration(ms).replaceFirst(":\\d\\d$", ""));
        t[2].set(loads[1] > 0 ? String.format(Locale.ROOT, "%+d%%",
                Math.round((loads[0] - loads[1]) * 100 / loads[1])) : "\u2013");
        t[3].set(zoned > 0 ? Math.round(easy * 100.0 / zoned) + "%" : "\u2013");
        for (Ui.Tile tile : t) {
            tile.label.setTextSize(11);
        }
    }

    private View updatedCard(CoachLogic.Plan plan) {
        LinearLayout c = card();
        TextView l = Ui.text(this, plan.rest ? "TODAY, UPDATED" : "TODAY'S PLAN, UPDATED", 12, Ui.TEXT2);
        l.setLetterSpacing(0.08f);
        c.addView(l);
        c.addView(gap(Ui.bold(Ui.text(this, plan.rest ? plan.title
                : plan.title + " \u00b7 " + plan.totalMinutes() + " min", 19, Ui.TEXT)), 4));
        for (String a : plan.adjusted) {
            c.addView(gap(Ui.text(this, "\u2022 " + a, 13, 0xFFFFC400), 4));
        }
        LinearLayout buttons = new LinearLayout(this);
        if (!plan.rest) {
            Button start = startButton();
            start.setText("Start now");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
            lp.rightMargin = Ui.dp(this, 8);
            buttons.addView(start, lp);
        }
        if (undo != null) {
            Button u = new Button(this, null, 0, R.style.SecondaryButton);
            u.setText("Undo");
            u.setAllCaps(false);
            u.setOnClickListener(v -> {
                coach.replacePlan(undo, "undid the coach chat's change");
                undo = null;
                showUpdated = false;
                Toast.makeText(this, "Back to the previous plan", Toast.LENGTH_SHORT).show();
                render();
            });
            buttons.addView(u, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        c.addView(buttons, lp);
        content.removeView(c);
        return c;
    }

    /** Starts today's plan: the home screen does the permission prompts and the set-up. */
    private Button startButton() {
        Button start = new Button(this, null, 0, R.style.PrimaryButton);
        start.setText("Start this session");
        start.setAllCaps(false);
        start.setTextSize(16);
        start.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_START_PLAN, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        return start;
    }

    private LinearLayout.LayoutParams lpTop(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }

    private View bubble(String text, boolean mine) {
        TextView t = Ui.text(this, text, 15, Ui.TEXT);
        t.setLineSpacing(0, 1.15f);
        int p = Ui.dp(this, 12);
        if (mine) {
            t.setBackgroundResource(R.drawable.chip);
            t.setPadding(p + 4, p, p + 4, p);
        } else {
            t.setPadding(0, p / 2, Ui.dp(this, 24), p / 2);
            t.setTextColor(0xFFE6E6EA);
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(mine ? Gravity.END : Gravity.START);
        wrap.setPadding(mine ? Ui.dp(this, 48) : 0, Ui.dp(this, 8), 0, 0);
        wrap.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    // -------------------------------------------------------------------- ask

    private void sendTyped() {
        String text = input.getText().toString().trim();
        if (!text.isEmpty()) {
            input.setText("");
            ask(text);
        }
    }

    private void ask(String text) {
        if (busy) {
            return;
        }
        busy = true;
        error = null;
        CoachLogic.Plan before = coach.todayPlan();
        coach.ask(text, (a, err) -> {
            busy = false;
            error = err;
            if (a != null && a.plan != null) {
                undo = before;
                showUpdated = true;
            }
            if (!isFinishing()) {
                render();
            }
        });
        render();
    }

    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to the coach");
        try {
            startActivityForResult(i, REQ_SPEECH);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No speech recognition on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (said != null && !said.isEmpty()) {
                ask(said.get(0));
            }
        }
    }

    // ------------------------------------------------------------------ layout

    private void section(String title) {
        TextView t = Ui.text(this, title.toUpperCase(Locale.getDefault()), 13, Ui.TEXT2);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 20), 0, 0);
        content.addView(t);
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
        content.addView(c, lp);
        return c;
    }

    private <T extends View> T gap(T v, int topDp) {
        v.setPadding(v.getPaddingLeft(), Ui.dp(this, topDp), v.getPaddingRight(),
                v.getPaddingBottom());
        return v;
    }
}
