package com.enftc.hrbuzz;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** The page Health Connect opens from its permission screen: what HR Buzz does with the data. */
public class HealthPrivacyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkBars(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 28));
        scroll.addView(root);
        root.addView(Ui.bold(Ui.text(this, "Health Connect in HR Buzz", 26, Ui.TEXT)));
        root.addView(para("HR Buzz reads your steps and workouts from the last 7 days to count "
                + "your move minutes: the minutes of the day you spent walking briskly or "
                + "working out. Nothing is written to Health Connect."));
        root.addView(para("The count is kept on the phone. If you use the coach, the daily "
                + "totals (minutes only, never the steps, times or workouts themselves) are "
                + "sent to DeepSeek with the rest of your training summary, so the coach "
                + "knows how active you are between sessions."));
        root.addView(para("Turn it off in HR Buzz's Settings, or remove the permissions in "
                + "Health Connect, and HR Buzz stops reading and forgets the last count."));
        setContentView(scroll);
    }

    private android.widget.TextView para(String s) {
        android.widget.TextView t = Ui.text(this, s, 16, Ui.TEXT);
        t.setPadding(0, Ui.dp(this, 14), 0, 0);
        return t;
    }
}
