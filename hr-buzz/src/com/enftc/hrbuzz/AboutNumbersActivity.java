package com.enftc.hrbuzz;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * What each fitness-test number is, how it is worked out, and, as plainly as possible, how far
 * it has and has not been checked. Written to be true of the code: change this page when
 * {@code Training.cleanRr}, {@code rmssdClean}, {@code sdnn}, {@code pnn50} or
 * {@code vo2maxEstimate} change.
 */
public class AboutNumbersActivity extends Activity {

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

        root.addView(Ui.bold(Ui.text(this, "About these numbers", 26, Ui.TEXT)));
        root.addView(para("The fitness test gives five numbers. This page says what each one "
                + "is, how it is worked out, and what has and has not been checked."));

        root.addView(heading("What goes in"));
        root.addView(para("The strap sends a heart rate about once a second and, with each "
                + "beat, the time since the previous beat (an RR interval). The strap finds "
                + "the beats itself; HR Buzz never sees the ECG. The first 2 minutes of the "
                + "5-minute test are for settling and are thrown away. The last 3 minutes are "
                + "measured."));

        root.addView(heading("Resting HR"));
        root.addView(para("The average of the once-a-second heart rate readings in the "
                + "measured 3 minutes. Your profile can follow the middle of your last three "
                + "tests."));

        root.addView(heading("HRV: RMSSD, SDNN and pNN50"));
        root.addView(para("All three describe how much the time between beats varies. A "
                + "higher value generally means more variation, which usually goes with "
                + "being rested. What counts as high or low depends on age and fitness, so "
                + "compare yourself with yourself, taken the same way each time."));
        root.addView(para("RMSSD: the typical size of the change from one beat interval to "
                + "the next (root mean square of successive differences). It mostly reflects "
                + "quick, breathing-linked changes and is the one the coach uses.\n\n"
                + "SDNN: the standard deviation of all the intervals, so it also includes "
                + "slower swings. It grows with the length of the recording, so only compare "
                + "it with other 3-minute tests.\n\n"
                + "pNN50: the share of neighbouring intervals that differ by more than "
                + "50 ms. It is a percentage and is 0 for many people with low variation, "
                + "which is a real result, not a failure."));

        root.addView(heading("How beats are cleaned"));
        root.addView(para("An interval is dropped if it is shorter than 300 ms, longer than "
                + "2000 ms, or differs from the one before by more than 25%. That removes "
                + "missed and extra beats from the strap. A difference is only taken between "
                + "two intervals that were next to each other in what the strap sent: when "
                + "one is dropped, the pair on either side of it is skipped, because the "
                + "gap would otherwise look like a big change from beat to beat. The test "
                + "shows how many clean beats it used, and gives no HRV with fewer than 60."));

        root.addView(heading("VO2max"));
        root.addView(para("15.3 × your max heart rate ÷ your resting heart rate "
                + "(Uth et al., 2004). It uses nothing else, so it is only as good as the "
                + "max heart rate in your profile, and for one person it can be well off. "
                + "Read it as a trend between tests, not as a lab result."));

        root.addView(heading("What has been checked"));
        root.addView(para("The formulas are tested against figures worked out by hand, "
                + "including the cleaning and the skipped pairs. The app has been used with "
                + "a real Polar H10.\n\n"
                + "What has not been done: the numbers have not been compared with a "
                + "reference ECG, with a laboratory, or with another HRV app on the same "
                + "recordings, and there is no accuracy figure for them. Treat them as a "
                + "consistent personal measurement, not a validated one."));

        root.addView(heading("Limits"));
        root.addView(para("Three minutes is short. Posture, breathing, caffeine, a bad night "
                + "or a full bladder all move the numbers, so one test means little and a "
                + "run of tests means more. The app cannot tell an irregular heart rhythm "
                + "from noise: with one, the HRV numbers mean nothing. This is not a "
                + "medical device and does not diagnose anything; if something about your "
                + "heart worries you, see a doctor."));

        root.addView(heading("Where the numbers go"));
        root.addView(para("Every test is saved on the phone. If you use the coach, resting "
                + "HR, RMSSD and VO2max go to the AI service you chose, as listed in the "
                + "coach settings. SDNN and pNN50 stay on the phone."));

        setContentView(scroll);
    }

    private TextView heading(String s) {
        TextView t = Ui.bold(Ui.text(this, s, 18, Ui.TEXT));
        t.setPadding(0, Ui.dp(this, 22), 0, 0);
        return t;
    }

    private TextView para(String s) {
        TextView t = Ui.text(this, s, 15, Ui.TEXT2);
        t.setPadding(0, Ui.dp(this, 8), 0, 0);
        return t;
    }
}
