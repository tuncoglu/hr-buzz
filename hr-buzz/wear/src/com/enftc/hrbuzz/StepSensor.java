package com.enftc.hrbuzz;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * The watch's step counter during a run: steps taken and steps a minute right now. The counter
 * counts steps since the watch last started and keeps counting in hardware, so the run's steps
 * are the difference between two readings.
 *
 * Application scoped; everything runs on the main thread.
 */
public class StepSensor implements SensorEventListener {

    private static StepSensor instance;

    public static synchronized StepSensor get(Context context) {
        if (instance == null) {
            instance = new StepSensor(context.getApplicationContext());
        }
        return instance;
    }

    private final SensorManager sensors;
    private final Sensor counter;
    private final WearLogic.Cadence cadence = new WearLogic.Cadence();
    private boolean active;
    private long total = -1;

    private StepSensor(Context context) {
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        counter = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
    }

    /** Starts reading; needs the activity recognition permission, else nothing arrives. */
    public void start() {
        if (active || counter == null) {
            return;
        }
        try {
            active = sensors.registerListener(this, counter, SensorManager.SENSOR_DELAY_FASTEST);
        } catch (SecurityException e) {
            Log.w("HRBuzz", "no step counter permission", e);
            active = false;
        }
    }

    public void stop() {
        if (active) {
            sensors.unregisterListener(this);
            active = false;
        }
    }

    /** @return steps since the watch last started, or -1 before the first reading */
    public long total() {
        return total;
    }

    /** Starts a new run's average. */
    public void resetAverage() {
        cadence.resetAverage();
    }

    /** @return steps a minute averaged over the time spent stepping since {@link #resetAverage} */
    public int averageSpm() {
        return cadence.averageSpm();
    }

    /** @return steps a minute while stepping, 0 when standing or without a reading */
    public int spm() {
        return cadence.spm(SystemClock.elapsedRealtime());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) {
            return;
        }
        total = Math.round(event.values[0]);
        cadence.feed(SystemClock.elapsedRealtime(), total);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
