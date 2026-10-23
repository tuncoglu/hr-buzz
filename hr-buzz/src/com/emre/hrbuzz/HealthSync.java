package com.emre.hrbuzz;

import android.content.Context;
import android.content.pm.PackageManager;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthPermissions;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RestingHeartRateRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.os.OutcomeReceiver;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reads steps and workouts from Health Connect (Android's on-device health store, which Google
 * Fit, Fitbit, Pixel Watch and most fitness apps write to) and turns them into move minutes;
 * with the optional permissions, also sleep, distance, resting heart rate and HRV per day for
 * the coach. Read-only, nothing leaves the phone from here. Needs Android 14+, where Health
 * Connect is part of the system, so no library is needed.
 *
 * Health Connect only lets an app read in the foreground, so the result is cached in the
 * preferences each time the app is opened, and the morning coach job uses the cache.
 */
final class HealthSync {

    /** Needed for move minutes. */
    private static final String[] REQUIRED = {HealthPermissions.READ_STEPS,
            HealthPermissions.READ_EXERCISE};
    /** Asked for together with the required ones; each is read only when allowed. */
    static final String[] PERMISSIONS = {HealthPermissions.READ_STEPS,
            HealthPermissions.READ_EXERCISE, HealthPermissions.READ_SLEEP,
            HealthPermissions.READ_DISTANCE, HealthPermissions.READ_RESTING_HEART_RATE,
            HealthPermissions.READ_HEART_RATE_VARIABILITY};

    private static final String TAG = "HealthSync";
    /** Days of move minutes (the home screen's week). */
    private static final int DAYS = 7;
    /** Days of sleep, distance, resting heart rate and HRV kept for the coach. */
    private static final int HEALTH_DAYS = CoachLogic.DAYS_BACK;
    private static final int PAGE = 5000;

    private HealthSync() {
    }

    static boolean available(Context c) {
        return c.getSystemService(HealthConnectManager.class) != null;
    }

    /** True when move minutes can be read; the optional data may still be missing. */
    static boolean granted(Context c) {
        for (String p : REQUIRED) {
            if (!has(c, p)) {
                return false;
            }
        }
        return true;
    }

    /** True when one of the optional permissions is still missing (worth asking again). */
    static boolean missingOptional(Context c) {
        for (String p : PERMISSIONS) {
            if (!has(c, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean has(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
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

    /** Days of sleep, steps, km, resting heart rate and HRV, while the setting is on. */
    static List<CoachLogic.HealthDay> daysForCoach(Context c) {
        List<CoachLogic.HealthDay> out = new ArrayList<>();
        if (!Prefs.moveOn(c)) {
            return out;
        }
        try {
            JSONArray a = new JSONArray(Prefs.of(c).getString("healthCache", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                CoachLogic.HealthDay d = new CoachLogic.HealthDay();
                d.date = o.getString("date");
                d.sleepMinutes = o.optInt("sleep", -1);
                d.asleep = o.optString("asleep");
                d.woke = o.optString("woke");
                d.steps = o.optInt("steps", -1);
                d.distanceM = o.optInt("m", -1);
                d.restingHr = o.optInt("rhr");
                d.hrvMs = o.optInt("hrv");
                out.add(d);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void forget(Context c) {
        Prefs.of(c).edit().remove("moveCache").remove("healthCache").apply();
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
        Instant healthFrom = today.minusDays(HEALTH_DAYS - 1).atStartOfDay(zone).toInstant();
        Instant to = Instant.ofEpochMilli(now);
        Move.Counter counter = new Move.Counter();
        Health health = new Health();
        try {
            for (StepsRecord r : readAll(hc, StepsRecord.class, healthFrom, to)) {
                if (!r.getStartTime().isBefore(from)) {
                    counter.addSteps(origin(r), r.getStartTime().toEpochMilli(),
                            r.getEndTime().toEpochMilli(), r.getCount());
                }
                health.steps.add(origin(r), day(r.getEndTime(), zone), r.getCount());
            }
            for (ExerciseSessionRecord r : readAll(hc, ExerciseSessionRecord.class, from, to)) {
                counter.addSpan(r.getStartTime().toEpochMilli(), r.getEndTime().toEpochMilli());
            }
        } catch (InterruptedException | RuntimeException e) {
            Log.w(TAG, "Health Connect read failed", e);
            return null;
        }
        try {
            readHealth(c, hc, health, healthFrom, to, zone);
        } catch (InterruptedException | RuntimeException e) {
            // Move minutes still count; the coach just has less to go on.
            Log.w(TAG, "Health Connect sleep/heart read failed", e);
        }
        saveHealth(c, health, today);
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

    /** Per day, per app that wrote it: phone and watch both count the same walk. */
    private static final class PerOrigin {
        final Map<LocalDate, Map<String, Double>> byDay = new HashMap<>();

        void add(String origin, LocalDate day, double value) {
            byDay.computeIfAbsent(day, k -> new HashMap<>()).merge(origin, value, Double::sum);
        }

        /** The app with the most that day, or -1 when none wrote anything. */
        int on(LocalDate day) {
            Map<String, Double> m = byDay.get(day);
            if (m == null) {
                return -1;
            }
            double best = 0;
            for (double v : m.values()) {
                best = Math.max(best, v);
            }
            return (int) Math.round(best);
        }
    }

    private static final class Health {
        final PerOrigin steps = new PerOrigin();
        final PerOrigin metres = new PerOrigin();
        final Map<LocalDate, Integer> sleep = new HashMap<>();
        final Map<LocalDate, long[]> sleepSpan = new HashMap<>();
        final Map<LocalDate, Integer> restingHr = new HashMap<>();
        final Map<LocalDate, List<Double>> hrv = new HashMap<>();
    }

    private static void readHealth(Context c, HealthConnectManager hc, Health h, Instant from,
                                   Instant to, ZoneId zone) throws InterruptedException {
        if (has(c, HealthPermissions.READ_DISTANCE)) {
            for (DistanceRecord r : readAll(hc, DistanceRecord.class, from, to)) {
                h.metres.add(origin(r), day(r.getEndTime(), zone), r.getDistance().getInMeters());
            }
        }
        if (has(c, HealthPermissions.READ_SLEEP)) {
            // a night belongs to the day they woke up; naps add to it
            for (SleepSessionRecord r : readAll(hc, SleepSessionRecord.class,
                    from.minusSeconds(12 * 3600), to)) {
                LocalDate d = day(r.getEndTime(), zone);
                h.sleep.merge(d, asleepMinutes(r), Integer::sum);
                long[] span = h.sleepSpan.get(d);
                long start = r.getStartTime().toEpochMilli();
                long end = r.getEndTime().toEpochMilli();
                if (span == null || end - start > span[1] - span[0]) {
                    h.sleepSpan.put(d, new long[]{start, end});
                }
            }
        }
        if (has(c, HealthPermissions.READ_RESTING_HEART_RATE)) {
            for (RestingHeartRateRecord r : readAll(hc, RestingHeartRateRecord.class, from, to)) {
                h.restingHr.merge(day(r.getTime(), zone), (int) r.getBeatsPerMinute(), Math::min);
            }
        }
        if (has(c, HealthPermissions.READ_HEART_RATE_VARIABILITY)) {
            for (HeartRateVariabilityRmssdRecord r : readAll(hc,
                    HeartRateVariabilityRmssdRecord.class, from, to)) {
                h.hrv.computeIfAbsent(day(r.getTime(), zone), k -> new ArrayList<>())
                        .add(r.getHeartRateVariabilityMillis());
            }
        }
    }

    /** Time asleep: the session less its awake stages, or all of it when it has no stages. */
    private static int asleepMinutes(SleepSessionRecord r) {
        long ms = r.getEndTime().toEpochMilli() - r.getStartTime().toEpochMilli();
        for (SleepSessionRecord.Stage st : r.getStages()) {
            int t = st.getType();
            if (t == SleepSessionRecord.StageType.STAGE_TYPE_AWAKE
                    || t == SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_IN_BED
                    || t == SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_OUT_OF_BED) {
                ms -= st.getEndTime().toEpochMilli() - st.getStartTime().toEpochMilli();
            }
        }
        return (int) Math.max(0, ms / 60000);
    }

    private static void saveHealth(Context c, Health h, LocalDate today) {
        JSONArray a = new JSONArray();
        ZoneId zone = ZoneId.systemDefault();
        try {
            for (int i = 0; i < HEALTH_DAYS; i++) {
                LocalDate d = today.minusDays(i);
                JSONObject o = new JSONObject().put("date", d.toString());
                if (h.sleep.containsKey(d)) {
                    o.put("sleep", h.sleep.get(d));
                    long[] span = h.sleepSpan.get(d);
                    o.put("asleep", clock(span[0], zone)).put("woke", clock(span[1], zone));
                }
                if (h.steps.on(d) >= 0) {
                    o.put("steps", h.steps.on(d));
                }
                if (h.metres.on(d) >= 0) {
                    o.put("m", h.metres.on(d));
                }
                if (h.restingHr.containsKey(d)) {
                    o.put("rhr", h.restingHr.get(d));
                }
                List<Double> v = h.hrv.get(d);
                if (v != null && !v.isEmpty()) {
                    double sum = 0;
                    for (double x : v) {
                        sum += x;
                    }
                    o.put("hrv", (int) Math.round(sum / v.size()));
                }
                a.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        Prefs.of(c).edit().putString("healthCache", a.toString()).apply();
    }

    private static String origin(Record r) {
        return r.getMetadata().getDataOrigin().getPackageName();
    }

    private static LocalDate day(Instant t, ZoneId zone) {
        return t.atZone(zone).toLocalDate();
    }

    private static String clock(long millis, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(zone);
        return String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
    }

    /** Every record of a type in the range, page after page. */
    @SuppressWarnings("unchecked")
    private static <T extends Record> List<T> readAll(
            HealthConnectManager hc, Class<T> type, Instant from, Instant to)
            throws InterruptedException {
        List<T> out = new ArrayList<>();
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
