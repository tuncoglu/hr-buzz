package com.enftc.hrbuzz;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.time.LocalDate;

/**
 * Steps today, from occasional reads of the step counter (when the app opens and once an hour
 * from {@link StepJob}). The sums are {@link WearLogic.StepTally}; this keeps them in the
 * preferences.
 */
final class StepLog {

    private static final String DAY = "stepsDay";
    private static final String LAST = "stepsLast";
    private static final String TODAY = "stepsToday";

    private StepLog() {
    }

    /** @return today's steps as last counted, or -1 before the first read today */
    static long today(Context c) {
        SharedPreferences p = Prefs.of(c);
        return LocalDate.now().toString().equals(p.getString(DAY, "")) ? p.getLong(TODAY, 0) : -1;
    }

    /** Reads the counter once and adds the steps since the last read to today. */
    static void snapshot(Context context, Runnable done) {
        Context c = context.getApplicationContext();
        SensorManager sensors = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor counter = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (counter == null) {
            finish(done);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        SensorEventListener once = new SensorEventListener() {
            private boolean got;

            @Override
            public void onSensorChanged(SensorEvent event) {
                if (got || event.values.length == 0) {
                    return;
                }
                got = true;
                sensors.unregisterListener(this);
                record(c, Math.round(event.values[0]));
                finish(done);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        try {
            sensors.registerListener(once, counter, SensorManager.SENSOR_DELAY_FASTEST);
        } catch (SecurityException e) {
            Log.w("HRBuzz", "no step counter permission", e);
            finish(done);
            return;
        }
        // The counter reports on change, so give up if nothing comes (the job must end).
        main.postDelayed(() -> {
            sensors.unregisterListener(once);
            finish(done);
        }, 4000);
    }

    private static void finish(Runnable done) {
        if (done != null) {
            new Handler(Looper.getMainLooper()).post(done);
        }
    }

    private static synchronized void record(Context c, long counter) {
        SharedPreferences p = Prefs.of(c);
        WearLogic.StepTally tally = new WearLogic.StepTally(p.getString(DAY, ""),
                p.getLong(LAST, -1), p.getLong(TODAY, 0));
        long today = tally.update(LocalDate.now().toString(), counter);
        p.edit().putString(DAY, tally.day()).putLong(LAST, tally.last()).putLong(TODAY, today)
                .apply();
    }
}
