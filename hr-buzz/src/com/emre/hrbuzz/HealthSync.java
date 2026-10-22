package com.emre.hrbuzz;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthPermissions;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.IntervalRecord;
import android.health.connect.datatypes.StepsRecord;
import android.os.OutcomeReceiver;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reads steps and workouts from Health Connect (Android's on-device health store, which Google
 * Fit, Fitbit, Pixel Watch and most fitness apps write to) and turns them into move minutes.
 * Read-only, nothing leaves the phone from here. Needs Android 14+, where Health Connect is
 * part of the system, so no library is needed.
 *
 * Health Connect only lets an app read in the foreground, so the result is cached in the
 * preferences each time the app is opened, and the morning coach job uses the cache.
 */
final class HealthSync {

    static final String[] PERMISSIONS = {HealthPermissions.READ_STEPS,
            HealthPermissions.READ_EXERCISE};

    private static final String TAG = "HealthSync";
    private static final int DAYS = 7;
    private static final int PAGE = 5000;

    private HealthSync() {
    }

    static boolean available(Context c) {
        return c.getSystemService(HealthConnectManager.class) != null;
    }

    static boolean granted(Context c) {
        for (String p : PERMISSIONS) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** The cached summary from the last sync, or null if it never ran. */
    static Move.Summary cached(Context c) {
        try {
            JSONObject o = new JSONObject(Prefs.of(c).getString("moveCache", ""));
            Map<LocalDate, Integer> days = new HashMap<>();
            JSONObject d = o.getJSONObject("days");
            for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) {
                String k = it.next();
                days.put(LocalDate.parse(k), d.getInt(k));
            }
            return new Move.Summary(days, o.getLong("asOf"));
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** What the coach may be told: the cache, only while the setting is on. */
    static Move.Summary forCoach(Context c) {
        return Prefs.moveOn(c) ? cached(c) : null;
    }

    static void forget(Context c) {
        Prefs.of(c).edit().remove("moveCache").apply();
    }

    /**
     * Reads the last week and caches it. Blocking: call off the main thread. Returns null when
     * Health Connect is missing or not allowed, else the fresh summary.
     */
    static Move.Summary sync(Context c) {
        HealthConnectManager hc = c.getSystemService(HealthConnectManager.class);
        if (hc == null || !granted(c)) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        Instant from = today.minusDays(DAYS - 1).atStartOfDay(zone).toInstant();
        Instant to = Instant.ofEpochMilli(now);
        Move.Counter counter = new Move.Counter();
        try {
            for (IntervalRecord r : readAll(hc, StepsRecord.class, from, to)) {
                counter.addSteps(r.getMetadata().getDataOrigin().getPackageName(),
                        r.getStartTime().toEpochMilli(), r.getEndTime().toEpochMilli(),
                        ((StepsRecord) r).getCount());
            }
            for (IntervalRecord r : readAll(hc, ExerciseSessionRecord.class, from, to)) {
                counter.addSpan(r.getStartTime().toEpochMilli(), r.getEndTime().toEpochMilli());
            }
        } catch (InterruptedException | RuntimeException e) {
            Log.w(TAG, "Health Connect read failed", e);
            return null;
        }
        // HR Buzz's own sessions count even when no other app wrote them to Health Connect.
        for (SessionStore.Summary s : SessionStore.list(c)) {
            if (!s.recording && s.id >= from.toEpochMilli()) {
                counter.addSpan(s.id, s.id + s.activeMs);
            }
        }
        TreeMap<LocalDate, Integer> found = counter.perDay(zone);
        // Every day of the window is written, zeros included, so "no movement" is not "unknown".
        Map<LocalDate, Integer> days = new TreeMap<>();
        JSONObject json = new JSONObject();
        try {
            for (int i = 0; i < DAYS; i++) {
                LocalDate d = today.minusDays(i);
                int m = found.containsKey(d) ? found.get(d) : 0;
                days.put(d, m);
                json.put(d.toString(), m);
            }
            Prefs.of(c).edit().putString("moveCache",
                    new JSONObject().put("asOf", now).put("days", json).toString()).apply();
        } catch (JSONException e) {
            return null;
        }
        return new Move.Summary(days, now);
    }

    /** Every record of a type in the range, page after page. */
    @SuppressWarnings("unchecked")
    private static <T extends IntervalRecord> java.util.List<IntervalRecord> readAll(
            HealthConnectManager hc, Class<T> type, Instant from, Instant to)
            throws InterruptedException {
        java.util.List<IntervalRecord> out = new java.util.ArrayList<>();
        TimeInstantRangeFilter range = new TimeInstantRangeFilter.Builder()
                .setStartTime(from).setEndTime(to).build();
        long token = -1;
        do {
            ReadRecordsRequestUsingFilters.Builder<T> b =
                    new ReadRecordsRequestUsingFilters.Builder<>(type)
                            .setTimeRangeFilter(range).setPageSize(PAGE);
            if (token != -1) {
                b.setPageToken(token);
            }
            Object[] result = new Object[1];
            CountDownLatch done = new CountDownLatch(1);
            hc.readRecords(b.build(), Runnable::run,
                    new OutcomeReceiver<ReadRecordsResponse<T>, HealthConnectException>() {
                        @Override
                        public void onResult(ReadRecordsResponse<T> r) {
                            result[0] = r;
                            done.countDown();
                        }

                        @Override
                        public void onError(HealthConnectException e) {
                            result[0] = e;
                            done.countDown();
                        }
                    });
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Health Connect did not answer");
            }
            if (result[0] instanceof HealthConnectException) {
                throw (HealthConnectException) result[0];
            }
            ReadRecordsResponse<T> page = (ReadRecordsResponse<T>) result[0];
            out.addAll(page.getRecords());
            token = page.getNextPageToken();
        } while (token != -1);
        return out;
    }
}
