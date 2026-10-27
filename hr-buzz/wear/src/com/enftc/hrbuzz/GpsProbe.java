package com.enftc.hrbuzz;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Listens for GPS fixes while the Start screen is open, so it can say whether the watch has a
 * fix before the run starts. Off again as soon as the screen goes, to spare the battery.
 */
final class GpsProbe implements LocationListener {

    interface Callback {
        void onGps();
    }

    /** A fix older than this no longer counts as "ready". */
    private static final long FRESH_MS = 8000;

    private final Context context;
    private final Callback callback;
    private LocationManager manager;
    private Location last;

    GpsProbe(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    /** @return false when GPS cannot be used (no permission, or no GPS) */
    boolean start() {
        if (manager != null) {
            return true;
        }
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this,
                    Looper.getMainLooper());
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w("HRBuzz", "GPS probe unavailable", e);
            manager = null;
            return false;
        }
    }

    void stop() {
        if (manager != null) {
            manager.removeUpdates(this);
            manager = null;
        }
        last = null;
    }

    boolean isRunning() {
        return manager != null;
    }

    /** @return the accuracy in metres of the latest fix, or -1 when there is none yet */
    int accuracyMetres() {
        if (last == null || !last.hasAccuracy() || (SystemClock.elapsedRealtimeNanos()
                - last.getElapsedRealtimeNanos()) / 1000000 > FRESH_MS) {
            return -1;
        }
        return Math.round(last.getAccuracy());
    }

    @Override
    public void onLocationChanged(Location location) {
        last = location;
        callback.onGps();
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
