package com.emre.hrbuzz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * The morning plan: runs once a day at the chosen time (when there is a network), asks the
 * coach for today's session and posts it as a notification with a Start button.
 */
public class CoachJob extends JobService {

    static final String CHANNEL_ID = "coach";
    private static final int NOTIFICATION_ID = 2;

    @Override
    public boolean onStartJob(JobParameters params) {
        Coach coach = Coach.get(this);
        if (!coach.isEnabled()) {
            coach.schedule();       // cancels the job when the coach is off, else renews it
            return false;
        }
        new Thread(() -> {
            try {
                String reason = "Morning plan for today.";
                if (LocalDate.now().getDayOfWeek() == DayOfWeek.SUNDAY) {
                    reason += " It is Sunday: start the message with a one-sentence recap "
                            + "of the past week.";
                }
                CoachLogic.Plan p = coach.todayPlan();
                if (p == null) {
                    p = coach.planNow(reason);
                }
                post(this, p);
            } catch (DeepSeek.ApiException e) {
                Log.w("HRBuzz", "morning plan failed: " + e.getMessage());
                postError(this, e.getMessage());
            } finally {
                jobFinished(params, false);
                coach.schedule();
            }
        }, "coach-morning").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Coach",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Today's plan each morning");
        nm.createNotificationChannel(ch);
    }

    static void post(Context c, CoachLogic.Plan p) {
        channel(c);
        PendingIntent open = PendingIntent.getActivity(c, 10,
                new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(p.rest ? "Rest day" : p.title + " \u00b7 " + p.totalMinutes() + " min")
                .setContentText(p.message)
                .setStyle(new Notification.BigTextStyle().bigText(p.message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        List<CoachLogic.Test> tests = Prefs.fitnessTests(c);
        long lastTest = tests.isEmpty() ? 0 : tests.get(tests.size() - 1).t;
        if (!p.rest && System.currentTimeMillis() - lastTest > 20 * 3600 * 1000L) {
            // A morning test lets the phone soften today if the body has not recovered.
            PendingIntent test = PendingIntent.getActivity(c, 12,
                    new Intent(c, FitnessTestActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Resting test first", test).build());
        }
        if (!p.rest) {
            PendingIntent start = PendingIntent.getActivity(c, 11,
                    new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_START_PLAN, true)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Start", start).build());
        }
        c.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, b.build());
    }

    private static void postError(Context c, String error) {
        channel(c);
        PendingIntent open = PendingIntent.getActivity(c, 10,
                new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                new Notification.Builder(c, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_heart)
                        .setContentTitle("Couldn't plan today")
                        .setContentText(error + ". Open HR Buzz to try again.")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build());
    }
}
