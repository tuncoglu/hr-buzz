package com.emre.hrbuzz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Coach setup and privacy in one place: on/off, the DeepSeek key, four tap-only questions,
 * the morning time, exactly what is sent, the last request, and "forget everything".
 */
public class CoachSetupActivity extends Activity {

    private static final String[] GOALS = {"Run a 10K", "Get fitter", "Lose weight",
            "Lower resting HR", "Just feel better"};
    private static final int[] DAYS = {2, 3, 4, 5, 6};
    private static final int[] MINUTES = {20, 35, 45, 60, 90, 120};
    private static final String[] AROUND = {"Nothing", "Knees", "Back", "New to running"};
    private static final int[] MORNING = {6 * 60 + 30, 7 * 60, 7 * 60 + 30, 8 * 60, -1};

    private SharedPreferences prefs;
    private Coach coach;

    private Switch onSwitch;
    private EditText keyInput;
    private String goal;
    private int days;
    private int minutes;
    private String around;
    private EditText aroundOther;
    private int morning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        prefs = Prefs.of(this);
        coach = Coach.get(this);
        goal = prefs.getString("coachGoal", "Get fitter");
        days = prefs.getInt("coachDays", 3);
        minutes = prefs.getInt("coachMinutes", 35);
        around = prefs.getString("coachWorkAround", "Nothing");
        morning = coach.morningMinute();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 28));
        scroll.addView(root);

        root.addView(Ui.bold(Ui.text(this, "Coach", 26, Ui.TEXT)));
        hint(root, "Plans each day from your goal and your HR Buzz history, sets your alerts "
                + "and talks you through the session. Everything else in HR Buzz works without "
                + "it. Changes save as you leave this screen.");

        // ---- on/off + key
        LinearLayout card = card(root);
        LinearLayout row = row(card, "Use the coach");
        onSwitch = new Switch(this);
        onSwitch.setChecked(prefs.getBoolean("coachOn", false) || !prefs.getBoolean("coachSetUp", false));
        row.addView(onSwitch);
        LinearLayout model = row(card, "Model");
        model.addView(Ui.text(this, "DeepSeek Flash", 15, 0xFFC8C8CE));
        TextView keyLabel = Ui.text(this, "Your DeepSeek API key", 15, Ui.TEXT);
        keyLabel.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(keyLabel);
        keyInput = new EditText(this);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setTextColor(Ui.TEXT);
        keyInput.setHint(coach.hasKey() ? "Saved \u2014 type a new one to replace it" : "sk-\u2026");
        keyInput.setSingleLine();
        card.addView(keyInput);
        hint(card, "Get one at platform.deepseek.com (API keys). It is encrypted with a key "
                + "held by Android's keystore and only ever sent to api.deepseek.com.");

        // ---- the four questions
        section(root, "1 \u00b7 What for?");
        chips(root, GOALS, goal, v -> goal = v);

        section(root, "2 \u00b7 Days a week");
        String[] dayLabels = new String[DAYS.length];
        for (int i = 0; i < DAYS.length; i++) {
            dayLabels[i] = String.valueOf(DAYS[i]);
        }
        chips(root, dayLabels, String.valueOf(days), v -> days = Integer.parseInt(v));

        section(root, "3 \u00b7 Time on a weekday");
        String[] minLabels = new String[MINUTES.length];
        for (int i = 0; i < MINUTES.length; i++) {
            minLabels[i] = MINUTES[i] + " min";
        }
        chips(root, minLabels, minutes + " min",
                v -> minutes = Integer.parseInt(v.replace(" min", "")));

        section(root, "4 \u00b7 Anything to work around?");
        boolean custom = !java.util.Arrays.asList(AROUND).contains(around);
        chips(root, AROUND, custom ? "" : around, v -> {
            around = v;
            aroundOther.setText("");
        });
        aroundOther = new EditText(this);
        aroundOther.setHint("Or type it, e.g. asthma, flat routes only");
        aroundOther.setTextColor(Ui.TEXT);
        aroundOther.setTextSize(15);
        aroundOther.setSingleLine();
        if (custom) {
            aroundOther.setText(around);
        }
        root.addView(aroundOther);

        section(root, "Plan ready each morning at");
        String[] mornLabels = new String[MORNING.length];
        String chosen = "Off";
        for (int i = 0; i < MORNING.length; i++) {
            mornLabels[i] = MORNING[i] < 0 ? "Off"
                    : String.format(Locale.ROOT, "%02d:%02d", MORNING[i] / 60, MORNING[i] % 60);
            if (MORNING[i] == morning) {
                chosen = mornLabels[i];
            }
        }
        chips(root, mornLabels, chosen, v -> {
            morning = -1;
            for (int i = 0; i < MORNING.length; i++) {
                if (MORNING[i] >= 0 && String.format(Locale.ROOT, "%02d:%02d",
                        MORNING[i] / 60, MORNING[i] % 60).equals(v)) {
                    morning = MORNING[i];
                }
            }
        });
        hint(root, "A notification with today's plan and a Start button. Off: the plan is "
                + "made when you open the app.");

        // ---- privacy
        section(root, "What the coach sees");
        LinearLayout seen = card(root);
        line(seen, "\u2713", 0xFF34C759, "Session totals: date, sport, time, distance, average "
                + "and max heart rate, minutes in each zone, load, how it felt, your notes");
        line(seen, "\u2713", 0xFF34C759, "Resting heart rate and HRV from fitness tests");
        if (Prefs.moveOn(this)) {
            line(seen, "\u2713", 0xFF34C759, "Move minutes per day for the last week, from "
                    + "Health Connect (minutes only, not the steps or workouts behind them)");
        }
        line(seen, "\u2713", 0xFF34C759, "Age, sex, weight, max heart rate, your answers above");
        line(seen, "\u2715", 0xFFFF6961, "Never: GPS routes or location, second-by-second heart "
                + "rate, beat-to-beat (RR) data, your name, contacts");

        Button last = button(root, "See the last message sent");
        last.setOnClickListener(v -> showLastRequest());
        Button forget = button(root, "Delete everything the coach remembers");
        forget.setTextColor(0xFFFF6961);
        forget.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Delete today's plan, the conversation, the request log and all "
                        + "session debriefs? Your sessions themselves stay.")
                .setPositiveButton("Delete", (d, w) -> {
                    coach.forget();
                    Toast.makeText(this, "Coach memory deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show());

        Button save = new Button(this, null, 0, R.style.PrimaryButton);
        save.setText(prefs.getBoolean("coachSetUp", false) && coach.hasKey() ? "Done" : "Make my plan");
        save.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 60));
        lp.topMargin = Ui.dp(this, 24);
        root.addView(save, lp);
        save.setOnClickListener(v -> save());

        setContentView(scroll);
    }

    /** The button: save, and say clearly if something still stops the coach working. */
    private void save() {
        if (!persist(true)) {
            return;
        }
        savedByButton = true;
        finish();
    }

    /** Leaving by Back or a swipe saves too: nothing typed here should be lost. */
    @Override
    protected void onPause() {
        super.onPause();
        if (!savedByButton) {
            persist(false);
        }
    }

    private boolean savedByButton;

    /**
     * Stores everything on the screen. The key is only stored when it looks like a DeepSeek
     * key; the rest always is.
     *
     * @param loud show what still needs doing (the button) rather than staying quiet (leaving)
     * @return true when the coach is ready to use, or deliberately switched off
     */
    private boolean persist(boolean loud) {
        String key = keyInput.getText().toString().replaceAll("\\s+", "");
        boolean keyOk = true;
        if (!key.isEmpty()) {
            if (!key.startsWith("sk-")) {
                keyOk = false;
                Toast.makeText(this, "Key not saved: DeepSeek keys start with sk-",
                        Toast.LENGTH_LONG).show();
            } else if (coach.setKey(key)) {
                keyInput.setText("");
                keyInput.setHint("Saved \u2014 type a new one to replace it");
            } else {
                keyOk = false;
                Toast.makeText(this, "Couldn't store the key on this phone", Toast.LENGTH_LONG)
                        .show();
            }
        }
        boolean on = onSwitch.isChecked();
        String other = aroundOther.getText().toString().trim();
        boolean changed = !goal.equals(prefs.getString("coachGoal", ""))
                || days != prefs.getInt("coachDays", -1)
                || minutes != prefs.getInt("coachMinutes", -1);
        prefs.edit().putBoolean("coachOn", on).putBoolean("coachSetUp", true)
                .putString("coachGoal", goal).putInt("coachDays", days)
                .putInt("coachMinutes", minutes)
                .putString("coachWorkAround", other.isEmpty() ? around : CoachLogic.trim(other, 80))
                .putInt("coachMorning", morning).apply();
        coach.schedule();
        if (on && !coach.hasKey()) {
            if (loud && keyOk) {
                Toast.makeText(this, "Saved. Add your DeepSeek API key to switch the coach on",
                        Toast.LENGTH_LONG).show();
            }
            return false;
        }
        if (coach.isEnabled() && !coach.isPlanning()
                && (changed || coach.todayPlan() == null || !key.isEmpty())) {
            coach.refreshPlan(changed ? "They just changed their goal or availability."
                    : "They just set up the coach.", null);
        }
        if (loud && !keyOk) {
            return false;
        }
        if (!loud && coach.isEnabled()) {
            Toast.makeText(this, "Coach saved", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void showLastRequest() {
        String body = coach.lastRequest();
        String text;
        if (body == null) {
            text = "Nothing has been sent yet.";
        } else {
            try {
                text = new JSONObject(body).toString(2).replace("\\n", "\n").replace("\\/", "/");
            } catch (JSONException e) {
                text = body;
            }
        }
        TextView t = Ui.text(this, text, 12, Ui.TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("Last message to DeepSeek")
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------------ layout

    private interface Pick {
        void picked(String value);
    }

    private LinearLayout chips(LinearLayout parent, String[] labels, String selected, Pick pick) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(this, labels[i]);
            chip.setSelected(labels[i].equals(selected));
            chip.setMinHeight(Ui.dp(this, 44));
            chip.setOnClickListener(v -> {
                Ui.select(row, index);
                pick.picked(labels[index]);
            });
            row.addView(chip);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        parent.addView(hs, lp);
        return row;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(this, 16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout row(LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 48));
        r.addView(Ui.text(this, label, 16, Ui.TEXT), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(r);
        return r;
    }

    private void line(LinearLayout parent, String mark, int color, String text) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        TextView m = Ui.bold(Ui.text(this, mark, 15, color));
        m.setMinWidth(Ui.dp(this, 26));
        r.addView(m);
        r.addView(Ui.text(this, text, 14, 0xFFC8C8CE), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(r);
    }

    private void section(LinearLayout parent, String title) {
        TextView t = Ui.text(this, title.toUpperCase(Locale.getDefault()), 13, Ui.TEXT2);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 22), 0, 0);
        parent.addView(t);
    }

    private void hint(LinearLayout parent, String text) {
        TextView t = Ui.text(this, text, 13, Ui.TEXT2);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        parent.addView(t);
    }

    private Button button(LinearLayout parent, String text) {
        Button b = new Button(this, null, 0, R.style.SecondaryButton);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        lp.topMargin = Ui.dp(this, 10);
        parent.addView(b, lp);
        return b;
    }
}
