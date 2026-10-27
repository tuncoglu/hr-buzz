package com.enftc.hrbuzz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The "How was it?" notification after a session: the talk test, answered with one tap from the
 * pocket, without opening the app. The answer is what lets the coach check the zones.
 */
public class HowWasIt extends BroadcastReceiver {

    static final String ACTION_TALK = "com.enftc.hrbuzz.TALK";
    static final String EXTRA_ID = "id";
    static final String EXTRA_TALK = "talk";

    private static final String CHANNEL_ID = "howwasit";
    private static final int NOTIFICATION_ID = 3;
    private static final long KEEP_MS = 3 * 3600 * 1000L;
    private static final String[] LABELS = {"", "Full sentences", "Short phrases", "A few words"};

    /** Asks about a session that was just saved. */
    static void post(Context c, SessionStore.Summary s) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "After a session",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("One question after a session, for the coach");
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);

        PendingIntent open = PendingIntent.getActivity(c, 31,
                new Intent(c, SummaryActivity.class).putExtra(SummaryActivity.EXTRA_ID, s.id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle("How was it?")
                .setContentText(s.sport + ", " + Training.duration(s.activeMs)
                        + ". In the main part, could you talk?")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(KEEP_MS);
        for (int talk = 1; talk < LABELS.length; talk++) {
            b.addAction(new Notification.Action.Builder(null, LABELS[talk],
                    PendingIntent.getBroadcast(c, 40 + talk,
                            new Intent(c, HowWasIt.class).setAction(ACTION_TALK)
                                    .putExtra(EXTRA_ID, s.id).putExtra(EXTRA_TALK, talk),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .build());
        }
        nm.notify(NOTIFICATION_ID, b.build());
    }

    static void cancel(Context c) {
        c.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(EXTRA_ID, -1);
        int talk = intent.getIntExtra(EXTRA_TALK, 0);
        if (ACTION_TALK.equals(intent.getAction()) && talk >= 1 && talk < LABELS.length) {
            SessionStore.Summary s = SessionStore.load(context, id);
            if (s != null) {
                s.talk = talk;
                SessionStore.save(context, s);
            }
        }
        cancel(context);
    }
}
