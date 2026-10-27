package com.enftc.hrbuzz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the process alive (and the heart rate sensor and GPS with it) while the screen is off
 * during a run, and mirrors the run into an ongoing notification.
 */
public class WearService extends Service implements WristHr.Listener {

    static final String ACTION_RECORD = "com.enftc.hrbuzz.RECORD";
    static final String ACTION_PAUSE = "com.enftc.hrbuzz.PAUSE";
    static final String ACTION_RESUME = "com.enftc.hrbuzz.RESUME";
    static final String ACTION_FINISH = "com.enftc.hrbuzz.FINISH";
    static final String EXTRA_SPORT = "sport";
    /** Follow today's coach plan. */
    static final String EXTRA_PLAN = "plan";

    private static final String CHANNEL_ID = "run";
    private static final int NOTIFICATION_ID = 1;
    private static final long NOTIFY_EVERY_MS = 2000;

    private NotificationManager notificationManager;
    private WristHr hr;
    private WearRecorder recorder;
    private long lastNotifyAt;

    /** Starts a run through the service, so it is in the foreground before GPS starts. */
    static void record(Context c, String sport) {
        record(c, sport, false);
    }

    static void record(Context c, String sport, boolean coached) {
        c.startForegroundService(new Intent(c, WearService.class)
                .setAction(ACTION_RECORD).putExtra(EXTRA_SPORT, sport)
                .putExtra(EXTRA_PLAN, coached));
    }

    /** Stops and saves a run, then lets the service go. @return the saved id, or -1 */
    static long finish(Context c) {
        long id = WearRecorder.get(c).stop();
        c.stopService(new Intent(c, WearService.class));
        return id;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Run", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
        hr = WristHr.get(this);
        recorder = WearRecorder.get(this);
        hr.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RECORD.equals(action)) {
            String sport = intent.getStringExtra(EXTRA_SPORT);
            goForeground(Prefs.usesGps(sport));
            recorder.start(sport, intent.getBooleanExtra(EXTRA_PLAN, false)
                    ? Coach.get(this).todayPlan() : null);
        } else if (ACTION_PAUSE.equals(action)) {
            recorder.pause();
        } else if (ACTION_RESUME.equals(action)) {
            recorder.resume();
        } else if (ACTION_FINISH.equals(action)) {
            recorder.stop();
            hr.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            notificationManager.cancel(NOTIFICATION_ID);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!recorder.isRecording()) {
            // Restarted by the system after a kill, or a stray action: nothing to keep alive.
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground(recorder.usesGps());
        return START_NOT_STICKY;
    }

    private void goForeground(boolean location) {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
        if (location) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        try {
            startForeground(NOTIFICATION_ID, build(), types);
        } catch (SecurityException e) {
            // No location permission: record heart rate without the route.
            Log.w("HRBuzz", "location foreground denied", e);
            startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        }
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

    private PendingIntent action(String action, int code) {
        return PendingIntent.getService(
                this, code, new Intent(this, WearService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        String title = "Run";
        String text = "";
        if (recorder.isRecording()) {
            SessionStore.Summary s = recorder.getSession();
            Training.Stats st = recorder.getStats();
            boolean paused = recorder.getState() == WearRecorder.PAUSED;
            StringBuilder t = new StringBuilder(Training.duration(st.activeMs));
            if (paused) {
                t.append(" · paused");
            }
            if (s.gps) {
                t.append(" · ").append(Training.km(st.distanceM)).append(" km");
            }
            title = t.toString();
            CoachLogic.Plan plan = recorder.getPlan();
            int step = recorder.getStepIndex();
            if (plan != null && step >= 0 && step < plan.steps.size()) {
                title += " \u00b7 " + plan.steps.get(step).name;
            }
            int bpm = hr.getBpm();
            if (bpm > 0) {
                int zone = Training.zone(bpm, s.hrMax);
                text = bpm + " bpm" + (zone > 0 ? " · Z" + zone : "");
                if (hr.isAbove()) {
                    text += " — over " + hr.getThreshold();
                } else if (hr.isBelow()) {
                    text += " — under " + hr.getLowLimit();
                }
            } else {
                text = "No heart rate";
            }
            b.addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause",
                    action(paused ? ACTION_RESUME : ACTION_PAUSE, paused ? 2 : 3)).build());
            b.addAction(new Notification.Action.Builder(null, "Finish",
                    action(ACTION_FINISH, 4)).build());
        }
        PendingIntent content = PendingIntent.getActivity(
                this, 0, new Intent(this, LiveActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_WORKOUT)
                .build();
    }

    @Override
    public void onHeart() {
        long now = System.currentTimeMillis();
        if (recorder.isRecording() && now - lastNotifyAt >= NOTIFY_EVERY_MS) {
            lastNotifyAt = now;
            notificationManager.notify(NOTIFICATION_ID, build());
        }
    }
}
