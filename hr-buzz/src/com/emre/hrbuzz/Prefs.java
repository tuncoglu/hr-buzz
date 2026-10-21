package com.emre.hrbuzz;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Profile and training settings, in the same preferences file HrManager uses for the alarm.
 */
final class Prefs {

    /** Sports offered on the home screen; the first {@link #GPS_SPORTS} record a route. */
    static final String[] SPORTS = {"Running", "Walking", "Cycling", "Hiking", "Gym", "Other"};
    private static final int GPS_SPORTS = 4;

    /** Voice guidance: 0 off, -1 every km, otherwise every N minutes. */
    static final int VOICE_OFF = 0;
    static final int VOICE_PER_KM = -1;

    private Prefs() {
    }

    static SharedPreferences of(Context context) {
        return context.getSharedPreferences("hrbuzz", Context.MODE_PRIVATE);
    }

    static boolean usesGps(String sport) {
        for (int i = 0; i < GPS_SPORTS; i++) {
            if (SPORTS[i].equals(sport)) {
                return true;
            }
        }
        return false;
    }

    static int age(Context c) {
        return of(c).getInt("age", 40);
    }

    static boolean male(Context c) {
        return of(c).getBoolean("male", true);
    }

    static int weight(Context c) {
        return of(c).getInt("weight", 75);
    }

    /** Entered HRmax; 0 means none entered. Only used when {@link #hrMaxAuto} is off. */
    static int hrMaxSetting(Context c) {
        return of(c).getInt("hrMax", 0);
    }

    /** Auto: highest 10-second peak from sessions, never below 220 - age. Default unless entered. */
    static boolean hrMaxAuto(Context c) {
        return of(c).getBoolean("hrMaxAuto", hrMaxSetting(c) == 0);
    }

    /** Highest 10-second heart rate seen in the last year's sessions, 0 if none. */
    static int hrMaxSeen(Context c) {
        return of(c).getInt("hrMaxSeen", 0);
    }

    /** HRmax used for zones. */
    static int hrMax(Context c) {
        if (hrMaxAuto(c)) {
            return Training.autoHrMax(age(c), hrMaxSeen(c));
        }
        int set = hrMaxSetting(c);
        return set > 0 ? set : Training.ageHrMax(age(c));
    }

    /** Re-reads the year's sessions for their highest peak (slow: off the main thread). */
    static void refreshHrMaxSeen(Context c) {
        long[] best = SessionStore.peakHr(c);
        of(c).edit().putInt("hrMaxSeen", (int) best[0]).putLong("hrMaxSeenAt", best[1]).apply();
    }

    /** Resting HR follows the fitness tests (default) rather than a number typed in. */
    static boolean restHrAuto(Context c) {
        return of(c).getBoolean("restHrAuto", true);
    }

    /** Resting HR from the fitness tests, 0 when there are none in the last 90 days. */
    static int restHrFromTests(Context c) {
        List<CoachLogic.Test> tests = fitnessTests(c);
        long[] times = new long[tests.size()];
        int[] rests = new int[tests.size()];
        for (int i = 0; i < tests.size(); i++) {
            times[i] = tests.get(i).t;
            rests[i] = tests.get(i).rest;
        }
        return Training.restingFromTests(times, rests, System.currentTimeMillis());
    }

    /** Resting HR in use: from the tests, or the entered value; 0 when unknown. */
    static int restHr(Context c) {
        return restHrAuto(c) ? restHrFromTests(c) : of(c).getInt("restHr", 0);
    }

    /** Saved fitness test results, oldest first. */
    static List<CoachLogic.Test> fitnessTests(Context c) {
        List<CoachLogic.Test> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(of(c).getString("fitnessTests", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new CoachLogic.Test(o.getLong("t"), o.getInt("rest"), o.optInt("rmssd")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    static Training.Profile profile(Context c) {
        return new Training.Profile(hrMax(c), male(c), age(c), weight(c));
    }

    static String sport(Context c) {
        return of(c).getString("sport", SPORTS[0]);
    }

    static int voice(Context c) {
        return of(c).getInt("voice", VOICE_OFF);
    }

    static boolean keepScreenOn(Context c) {
        return of(c).getBoolean("keepScreenOn", true);
    }

    /** Pause and stop need a long press, so a pocket or a sweaty thumb cannot end a run. */
    static boolean secureButtons(Context c) {
        return of(c).getBoolean("secureButtons", true);
    }
}
