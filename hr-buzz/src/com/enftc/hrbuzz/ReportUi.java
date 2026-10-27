package com.enftc.hrbuzz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Paint;
import android.net.Uri;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Report this answer" under what the coach wrote. Choosing a reason saves the report on the
 * phone; the person is then offered to email it to the privacy address. The email opens in
 * their own mail app, with the report written out for them to read, and nothing is sent until
 * they press Send there.
 */
final class ReportUi {

    private ReportUi() {
    }

    /** The small link to put under an answer. {@code kind} is "chat" or "debrief". */
    static TextView link(Activity a, String kind, String question, String answer) {
        TextView t = Ui.text(a, "Report this answer", 12, Ui.TEXT3);
        t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        t.setMinHeight(Ui.dp(a, 36));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setOnClickListener(v -> choose(a, kind, question, answer));
        return t;
    }

    private static void choose(Activity a, String kind, String question, String answer) {
        new AlertDialog.Builder(a)
                .setTitle("Report this answer")
                .setItems(Reports.REASONS, (d, which) -> {
                    Coach.get(a).report(kind, Reports.REASONS[which], question, answer);
                    offerEmail(a);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void offerEmail(Activity a) {
        new AlertDialog.Builder(a)
                .setTitle("Report saved")
                .setMessage("It is kept on this phone (Coach settings, Reported answers). Email "
                        + "it to the HR Buzz team as well? Your email app opens with the report "
                        + "written out for you to read; nothing is sent until you press Send "
                        + "there.")
                .setPositiveButton("Email it", (d, w) -> email(a, true))
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Opens the person's email app with the saved reports written out. */
    static void email(Activity a, boolean onlyNewest) {
        Coach coach = Coach.get(a);
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        i.putExtra(Intent.EXTRA_EMAIL, new String[]{Reports.EMAIL});
        i.putExtra(Intent.EXTRA_SUBJECT, Reports.subject(onlyNewest ? 1 : coach.reportCount()));
        i.putExtra(Intent.EXTRA_TEXT, coach.reportsEmailBody(onlyNewest));
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "No email app found. The report stays saved on this phone.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Coach settings: how many are saved, with a way to email or delete them. */
    static void review(Activity a, Runnable changed) {
        Coach coach = Coach.get(a);
        int n = coach.reportCount();
        if (n == 0) {
            Toast.makeText(a, "No answers reported yet", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Reported answers")
                .setMessage(n + (n == 1 ? " report is" : " reports are") + " saved on this "
                        + "phone. They stay here until you email or delete them, and are also "
                        + "removed by \"Delete everything the coach remembers\".")
                .setPositiveButton("Email them", (d, w) -> email(a, false))
                .setNeutralButton("Delete them", (d, w) -> {
                    coach.clearReports();
                    changed.run();
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
