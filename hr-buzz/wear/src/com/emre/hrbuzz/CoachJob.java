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

/**
 * The watch's morning plan. It stands in for the phone's CoachJob of the same name (the
 * watch build compiles this file instead): the same daily job, but the notification opens the
 * watch's coach screen, and its Start button starts today's plan.
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

    private static void channel(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Coach",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Today's plan each morning");
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private static PendingIntent open(Context c, int code, boolean startPlan) {
        Intent i = new Intent(c, WearCoachActivity.class);
        if (startPlan) {
            i.putExtra(WearCoachActivity.EXTRA_START_PLAN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        return PendingIntent.getActivity(c, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void post(Context c, CoachLogic.Plan p) {
        channel(c);
        Notification.Builder b = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(p.rest ? "Rest day" : p.title + " · " + p.totalMinutes() + " min")
                .setContentText(p.message)
                .setStyle(new Notification.BigTextStyle().bigText(p.message))
                .setContentIntent(open(c, 10, false))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        if (!p.rest) {
            b.addAction(new Notification.Action.Builder(null, "Start", open(c, 11, true)).build());
        }
        c.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, b.build());
    }

    private static void postError(Context c, String error) {
        channel(c);
        c.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                new Notification.Builder(c, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_heart)
                        .setContentTitle("Couldn't plan today")
                        .setContentText(error + ". Open HR Buzz to try again.")
                        .setContentIntent(open(c, 10, false))
                        .setAutoCancel(true)
                        .build());
    }
}
