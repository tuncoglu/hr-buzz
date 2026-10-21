package com.emre.hrbuzz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps the process alive (and the BLE link with it) while the screen is off,
 * and mirrors the current heart rate into a persistent notification.
 */
public class LiveService extends Service implements HrManager.Listener {

    static final String ACTION_STOP = "com.emre.hrbuzz.STOP";

    private static final String CHANNEL_ID = "live";
    private static final int NOTIFICATION_ID = 1;
    private static final long NOTIFY_EVERY_MS = 2000;

    private NotificationManager notificationManager;
    private HrManager hr;

    private int lastBpm = -1;
    private boolean lastAbove;
    private long lastNotifyAt;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Heart rate", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
        hr = HrManager.get(this);
        hr.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Detach first so stop()'s state callbacks cannot re-post the notification.
            hr.removeListener(this);
            hr.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, build(0, false));
        // If the system restarted us after a kill, pick the strap back up.
        if (!hr.isActive()) {
            hr.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hr.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build(int bpm, boolean above) {
        String title;
        String text;
        if (bpm <= 0) {
            title = "Waiting for heart rate";
            text = "Alert above " + hr.getThreshold() + " bpm";
        } else if (above) {
            title = bpm + " bpm \u2014 over " + hr.getThreshold();
            text = "Ease off";
        } else {
            title = bpm + " bpm";
            text = "Alert above " + hr.getThreshold() + " bpm";
        }

        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stop = PendingIntent.getService(
                this, 1, new Intent(this, LiveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        null, "Stop", stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public void onState(String state) {
        notificationManager.notify(NOTIFICATION_ID, build(lastBpm, lastAbove));
    }

    @Override
    public void onBpm(int bpm, boolean above) {
        long now = System.currentTimeMillis();
        boolean changed = above != lastAbove || lastBpm < 0;
        if (!changed && now - lastNotifyAt < NOTIFY_EVERY_MS) {
            return;
        }
        lastBpm = bpm;
        lastAbove = above;
        lastNotifyAt = now;
        notificationManager.notify(NOTIFICATION_ID, build(bpm, above));
    }

    @Override
    public void onConnected(boolean connected) {
        if (!connected) {
            lastBpm = 0;
            lastAbove = false;
            notificationManager.notify(NOTIFICATION_ID, build(0, false));
        }
    }
}
