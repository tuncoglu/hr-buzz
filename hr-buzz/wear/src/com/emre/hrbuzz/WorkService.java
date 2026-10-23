package com.emre.hrbuzz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Keeps the app alive while the coach is thinking. With the watch screen off Android freezes a
 * background app, which stalls the network call and loses the plan, so each request holds this
 * foreground service until its answer is in. It shows a small "coach is working" notification.
 */
public class WorkService extends Service {

    private static final String CHANNEL_ID = "work";
    private static final int NOTIFICATION_ID = 3;
    /** Requests in flight; only touched on the main thread. */
    private static int holds;

    /** Call on the main thread before starting a coach request. */
    static void hold(Context c) {
        if (++holds == 1) {
            c.getApplicationContext().startForegroundService(
                    new Intent(c.getApplicationContext(), WorkService.class));
        }
    }

    /** Call on the main thread when the request has finished, whatever came back. */
    static void release(Context c) {
        if (holds > 0 && --holds == 0) {
            c.getApplicationContext().stopService(
                    new Intent(c.getApplicationContext(), WorkService.class));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Coach working",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle("Coach is working")
                .setContentText("This can take a couple of minutes")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build();
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
