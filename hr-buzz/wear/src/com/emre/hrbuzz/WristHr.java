package com.emre.hrbuzz;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The watch's own optical heart rate sensor and the over/under alarm on top of it. The watch
 * counterpart of {@link HrManager}, without the Bluetooth strap: same limits (kept in the same
 * preferences), same {@link HrLogic.Alarm} rules, but the alerts are buzzes only.
 *
 * Application scoped; everything runs on the main thread.
 */
public class WristHr implements SensorEventListener {

    private static final String TAG = "HRBuzz";

    /** Once you are over the limit, stay "over" until you drop this far below it. */
    private static final int HYSTERESIS = 3;
    private static final long RE_ALERT_MS = 5000;
    /** Readings in a row past a limit before it sounds: the wrist sensor has spikes. */
    private static final int CONFIRM = 3;
    private static final int LOW_CONFIRM = 10;
    private static final long LOW_RE_ALERT_MS = 30000;
    /** A reading older than this is not "current heart rate" any more. */
    private static final long FRESH_MS = 5000;
    /** No valid reading for this long during a session sounds the lost-signal buzz. */
    private static final long LOST_MS = 15000;
    private static final long WATCHDOG_MS = 1000;

    public interface Listener {
        /** A reading, an alarm or the alert state changed. */
        void onHeart();
    }

    private static WristHr instance;

    public static synchronized WristHr get(Context context) {
        if (instance == null) {
            instance = new WristHr(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences prefs;
    private final SensorManager sensors;
    private final Sensor sensor;
    private final Vibrator vibrator;
    private final HrLogic.Alarm alarm;
    private PowerManager.WakeLock wakeLock;

    private boolean active;
    private boolean sessionRunning;
    private int overrideHigh;
    private int overrideLow = -1;
    private int bpm = -1;
    private long lastValidAt;
    private long startedAt;
    private long lastLostAlertAt;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }
            long now = System.currentTimeMillis();
            long since = Math.max(lastValidAt, startedAt);
            if (sessionRunning && now - since > LOST_MS && alarmOn()
                    && HrLogic.alertDue(lastLostAlertAt, now, RE_ALERT_MS)) {
                lastLostAlertAt = now;
                buzz(HrLogic.Alarm.LOST);
            }
            changed();      // the number goes stale on screen even when no event arrives
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    private WristHr(Context context) {
        this.appContext = context;
        this.prefs = Prefs.of(context);
        this.sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.sensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        this.vibrator = vm == null ? null : vm.getDefaultVibrator();
        this.alarm = new HrLogic.Alarm(getThreshold(), HYSTERESIS, RE_ALERT_MS, CONFIRM);
        this.alarm.setLowTiming(LOW_CONFIRM, LOW_RE_ALERT_MS);
    }

    // ---------------------------------------------------------------- public API

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** @return false when this watch has no heart rate sensor */
    public boolean hasSensor() {
        return sensor != null;
    }

    public boolean isActive() {
        return active;
    }

    /** Starts reading. Needs the body sensors permission; without it no readings arrive. */
    public void start() {
        if (active || sensor == null) {
            return;
        }
        try {
            active = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        } catch (SecurityException e) {
            Log.w(TAG, "no heart rate permission", e);
            active = false;
            return;
        }
        if (!active) {
            return;
        }
        startedAt = System.currentTimeMillis();
        lastValidAt = 0;
        alarm.reset();
        updateWakeLock();
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, WATCHDOG_MS);
    }

    public void stop() {
        if (!active) {
            return;
        }
        active = false;
        sensors.unregisterListener(this);
        main.removeCallbacks(watchdog);
        bpm = -1;
        alarm.reset();
        updateWakeLock();
        changed();
    }

    /** During a session the lost-signal buzz is on and the screen-off wake lock is held. */
    public void setSessionRunning(boolean value) {
        sessionRunning = value;
        applyLimits();
        updateWakeLock();
    }

    /** @return the current heart rate, or -1 when there is no fresh valid reading */
    public int getBpm() {
        if (!active || bpm <= 0
                || System.currentTimeMillis() - lastValidAt > FRESH_MS) {
            return -1;
        }
        return bpm;
    }

    public boolean isAbove() {
        return alarmOn() && alarm.isAbove() && getBpm() > 0;
    }

    public boolean isBelow() {
        return alarmOn() && alarm.isBelow() && getBpm() > 0;
    }

    /** Whether no reading has come for long enough to count as a lost signal. */
    public boolean isLost() {
        long since = Math.max(lastValidAt, startedAt);
        return active && System.currentTimeMillis() - since > LOST_MS;
    }

    // ------------------------------------------------------------------- limits

    public boolean alarmOn() {
        return prefs.getBoolean("alarmOn", true);
    }

    public void setAlarmOn(boolean value) {
        prefs.edit().putBoolean("alarmOn", value).apply();
        alarm.reset();
        changed();
    }

    /** Alert above in force now, in bpm: the current coached step's, else the person's own. */
    public int getThreshold() {
        return overrideHigh > 0 ? overrideHigh : getUserThreshold();
    }

    /** The person's own "alert above" setting, whatever a session is doing. */
    public int getUserThreshold() {
        return Prefs.alertAbove(appContext);
    }

    public void setThreshold(int value) {
        prefs.edit().putInt("threshold", value).apply();
        if (overrideHigh > value) {
            // A coached step never sits above the person's own ceiling, even mid-step.
            overrideHigh = value;
        }
        applyLimits();
        changed();
    }

    /** Alert below in force now, in bpm; 0 means off. */
    public int getLowLimit() {
        return overrideLow >= 0 ? overrideLow : getUserLowLimit();
    }

    /** The person's own "alert below" setting. */
    public int getUserLowLimit() {
        return prefs.getInt("lowLimit", 0);
    }

    public void setLowLimit(int value) {
        prefs.edit().putInt("lowLimit", value).apply();
        applyLimits();
        changed();
    }

    /**
     * Limits for the current step of a coached workout, for this session only: nothing is
     * saved, and {@link #clearSessionLimits()} puts the person's own back.
     *
     * @param low 0 for no lower alert during this step
     */
    public void setSessionLimits(int high, int low) {
        overrideHigh = high;
        overrideLow = low;
        applyLimits();
    }

    public void clearSessionLimits() {
        overrideHigh = 0;
        overrideLow = -1;
        applyLimits();
    }

    /** The lower alert only sounds during a session, like on the phone. */
    private void applyLimits() {
        alarm.setThreshold(getThreshold());
        alarm.setLow(sessionRunning ? getLowLimit() : 0);
    }

    /** Sets max heart rate by hand, which turns Auto off (the zones follow it). */
    public void setHrMax(int value) {
        prefs.edit().putInt("hrMax", value).putBoolean("hrMaxAuto", false).apply();
        changed();
    }

    // ------------------------------------------------------------------- sensor

    @Override
    public void onSensorChanged(SensorEvent event) {
        float value = event.values.length > 0 ? event.values[0] : 0;
        // 0 means "no pulse yet"; accuracy <= 0 is no contact or unreliable (watch loose).
        if (value <= 0 || value > 250 || event.accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) {
            return;
        }
        int rate = Math.round(value);
        long now = System.currentTimeMillis();
        bpm = rate;
        lastValidAt = now;
        int result = alarm.evaluate(rate, now);
        if (result != HrLogic.Alarm.NONE && result != HrLogic.Alarm.BACK && alarmOn()) {
            buzz(result);
        }
        changed();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void changed() {
        for (Listener l : listeners) {
            l.onHeart();
        }
    }

    /**
     * High: double buzz. Low: triple buzz. Lost signal: one long buzz. They differ in rhythm
     * so you can tell them apart with the screen off.
     */
    private void buzz(int event) {
        if (vibrator == null) {
            return;
        }
        long[] pattern;
        if (event == HrLogic.Alarm.OVER) {
            pattern = new long[]{0, 250, 120, 250};
        } else if (event == HrLogic.Alarm.UNDER) {
            pattern = new long[]{0, 150, 100, 150, 100, 150};
        } else if (event == HrLogic.Alarm.BACK) {
            pattern = new long[]{0, 70, 80, 70};
        } else {
            pattern = new long[]{0, 900};
        }
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } catch (RuntimeException e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    /** A short friendly buzz: a coached workout moving to its next step. */
    public void chime() {
        buzz(HrLogic.Alarm.BACK);
    }

    /** One firm buzz: a kilometre done. */
    public void kmBuzz() {
        vibrate(new long[]{0, 220});
    }

    /** Short then long for a move up a zone, long then short for a move down. */
    public void zoneBuzz(boolean up) {
        vibrate(up ? new long[]{0, 60, 50, 170} : new long[]{0, 170, 50, 60});
    }

    private void vibrate(long[] pattern) {
        if (vibrator == null) {
            return;
        }
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } catch (RuntimeException e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    /** A short single buzz, e.g. when a run starts or is paused. */
    public void tick() {
        if (vibrator == null) {
            return;
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        } catch (RuntimeException e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    /**
     * Keeps the CPU awake while a session runs: with the screen off Android otherwise delays
     * the sensor batches and the lost-signal timer.
     */
    private void updateWakeLock() {
        boolean need = active && sessionRunning;
        if (need && wakeLock == null) {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hrbuzz:wrist");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } else if (!need && wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }
}
