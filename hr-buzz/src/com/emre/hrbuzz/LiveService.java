package com.emre.hrbuzz;

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
 * Keeps the process alive (and the BLE link and GPS with it) while the screen is off, and
 * mirrors heart rate, or the running session, into a persistent notification that is also
 * readable on the lock screen.
 */
public class LiveService extends Service implements HrManager.Listener, Recorder.Listener {

    static final String ACTION_STOP = "com.emre.hrbuzz.STOP";
    static final String ACTION_RECORD = "com.emre.hrbuzz.RECORD";
    static final String ACTION_PAUSE = "com.emre.hrbuzz.PAUSE";
    static final String ACTION_RESUME = "com.emre.hrbuzz.RESUME";
    static final String ACTION_FINISH = "com.emre.hrbuzz.FINISH";
    static final String EXTRA_SPORT = "sport";
    /** Follow today's coach plan. */
    static final String EXTRA_PLAN = "plan";

    private static final String CHANNEL_ID = "live";
    private static final int NOTIFICATION_ID = 1;
    private static final long NOTIFY_EVERY_MS = 2000;

    private NotificationManager notificationManager;
    private HrManager hr;
    private Recorder recorder;

    private int lastBpm = -1;
    private boolean lastAbove;
    private long lastNotifyAt;
    private int lastRecorderState = Recorder.IDLE;

    /** Starts a recording through the service, so it is in the foreground before GPS starts. */
    static void record(Context c, String sport) {
        record(c, sport, false);
    }

    static void record(Context c, String sport, boolean coached) {
        c.startForegroundService(new Intent(c, LiveService.class)
                .setAction(ACTION_RECORD).putExtra(EXTRA_SPORT, sport)
                .putExtra(EXTRA_PLAN, coached));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Heart rate", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
        hr = HrManager.get(this);
        recorder = Recorder.get(this);
        hr.addListener(this);
        recorder.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // Detach first so stop()'s state callbacks cannot re-post the notification.
            hr.removeListener(this);
            recorder.removeListener(this);
            recorder.stop();
            hr.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
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
            // Saved; the training screen opens the summary next time it is in front.
            recorder.stop();
        }
        goForeground(recorder.usesGps());
        // If the system restarted us after a kill, pick the strap back up.
        if (!hr.isActive()) {
            hr.start();
        }
        return START_STICKY;
    }

    private void goForeground(boolean location) {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (location) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        try {
            startForeground(NOTIFICATION_ID, build(), types);
        } catch (SecurityException e) {
            // No location permission: record heart rate without the route.
            Log.w("HRBuzz", "location foreground denied", e);
            startForeground(NOTIFICATION_ID, build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
    }

    @Override
    public void onDestroy() {
        hr.removeListener(this);
        recorder.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private PendingIntent action(String action, int code) {
        return PendingIntent.getService(
                this, code, new Intent(this, LiveService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        int bpm = hr.getBpm();
        boolean above = hr.isAbove();
        boolean below = hr.isBelow();
        String title;
        String text;
        Class<?> open = MainActivity.class;
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);

        if (recorder.isRecording()) {
            open = TrainingActivity.class;
            SessionStore.Summary s = recorder.getSession();
            Training.Stats st = recorder.getStats();
            StringBuilder t = new StringBuilder(s.sport);
            if (recorder.getState() == Recorder.PAUSED) {
                t.append(" \u00b7 paused");
            }
            t.append(" \u00b7 ").append(Training.duration(st.activeMs));
            if (s.gps) {
                t.append(" \u00b7 ").append(Training.km(st.distanceM)).append(" km");
            }
            CoachLogic.Plan plan = recorder.getPlan();
            int step = recorder.getStepIndex();
            if (plan != null && step >= 0 && step < plan.steps.size()) {
                t.append(" \u00b7 ").append(plan.steps.get(step).name).append(' ')
                        .append(Training.duration(recorder.stepRemainingMs())).append(" left");
            }
            title = t.toString();
            StringBuilder x = new StringBuilder();
            if (bpm > 0) {
                int zone = Training.zone(bpm, s.hrMax);
                x.append(bpm).append(" bpm");
                if (zone > 0) {
                    x.append(" \u00b7 Z").append(zone);
                }
            } else {
                x.append("No heart rate");
            }
            if (above) {
                x.append(" \u2014 over ").append(hr.getThreshold());
            } else if (below) {
                x.append(" \u2014 under ").append(hr.getLowLimit());
            }
            if (s.gps) {
                x.append(" \u00b7 ").append(Training.pace(st.speed())).append(" /km");
            }
            text = x.toString();
            if (recorder.getState() == Recorder.PAUSED) {
                b.addAction(new Notification.Action.Builder(null, "Resume",
                        action(ACTION_RESUME, 2)).build());
            } else {
                b.addAction(new Notification.Action.Builder(null, "Pause",
                        action(ACTION_PAUSE, 3)).build());
            }
            b.addAction(new Notification.Action.Builder(null, "Finish & save",
                    action(ACTION_FINISH, 4)).build());
        } else {
            if (bpm <= 0) {
                title = "Waiting for heart rate";
                text = alertText();
            } else if (above) {
                title = bpm + " bpm \u2014 over " + hr.getThreshold();
                text = "Ease off";
            } else {
                title = bpm + " bpm";
                text = alertText();
            }
            b.addAction(new Notification.Action.Builder(null, "Stop",
                    action(ACTION_STOP, 1)).build());
        }

        PendingIntent content = PendingIntent.getActivity(
                this, 0, new Intent(this, open),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return b.setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(recorder.isRecording()
                        ? Notification.CATEGORY_WORKOUT : Notification.CATEGORY_SERVICE)
                .build();
    }

    private String alertText() {
        return hr.isAlarmOn() ? "Alert above " + hr.getThreshold() + " bpm" : "Alert off";
    }

    private void refresh() {
        lastNotifyAt = System.currentTimeMillis();
        notificationManager.notify(NOTIFICATION_ID, build());
    }

    @Override
    public void onState(String state) {
        refresh();
    }

    @Override
    public void onBpm(int bpm, boolean above) {
        long now = System.currentTimeMillis();
        boolean changed = above != lastAbove || lastBpm < 0;
        lastBpm = bpm;
        lastAbove = above;
        if (!changed && now - lastNotifyAt < NOTIFY_EVERY_MS) {
            return;
        }
        refresh();
    }

    @Override
    public void onConnected(boolean connected) {
        if (!connected) {
            lastBpm = -1;
            lastAbove = false;
        }
        refresh();
    }

    @Override
    public void onRecorderUpdate() {
        int state = recorder.getState();
        boolean changed = state != lastRecorderState;
        lastRecorderState = state;
        if (changed) {
            // Session started or ended: the foreground type follows (GPS on or off).
            goForeground(recorder.usesGps());
        } else if (System.currentTimeMillis() - lastNotifyAt >= NOTIFY_EVERY_MS) {
            refresh();
        }
    }
}
