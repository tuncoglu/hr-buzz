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

    /**
     * Auto: the highest 10-second peak the person agreed to use, never below 220 - age.
     * Default unless entered.
     */
    static boolean hrMaxAuto(Context c) {
        return of(c).getBoolean("hrMaxAuto", hrMaxSetting(c) == 0);
    }

    /** Highest 10-second heart rate seen in the last year's sessions, 0 if none. */
    static int hrMaxSeen(Context c) {
        return of(c).getInt("hrMaxSeen", 0);
    }

    /**
     * The highest peak agreed to (see {@link #hrMaxPending}). A strap glitch held for 10 s
     * would otherwise move every zone and limit for a year.
     */
    static int hrMaxAccepted(Context c) {
        SharedPreferences p = of(c);
        if (!p.contains("hrMaxAccepted")) {
            // Earlier versions used every peak without asking: keep the one in force.
            int seen = hrMaxSeen(c);
            p.edit().putInt("hrMaxAccepted", seen).apply();
            return seen;
        }
        return p.getInt("hrMaxAccepted", 0);
    }

    /** The peak Auto uses: the agreed one, lower if its session has since been deleted. */
    static int hrMaxUsedPeak(Context c) {
        return Math.min(hrMaxAccepted(c), hrMaxSeen(c));
    }

    /** A new peak that would raise Auto HRmax and has not been answered yet, else 0. */
    static int hrMaxPending(Context c) {
        if (!hrMaxAuto(c)) {
            return 0;
        }
        int seen = hrMaxSeen(c);
        boolean raises = seen > Training.autoHrMax(age(c), hrMaxUsedPeak(c));
        return raises && seen > of(c).getInt("hrMaxDeclined", 0) ? seen : 0;
    }

    /** The answer to "use this new peak?": yes moves the zones, no stops asking about it. */
    static void decideHrMax(Context c, int peak, boolean use) {
        of(c).edit().putInt(use ? "hrMaxAccepted" : "hrMaxDeclined", peak).apply();
    }

    /** The person's own "alert above" limit in bpm: 85% of HRmax until they set one. */
    static int alertAbove(Context c) {
        return of(c).getInt("threshold", Training.defaultAlert(hrMax(c)));
    }

    /** HRmax used for zones. */
    static int hrMax(Context c) {
        if (hrMaxAuto(c)) {
            return Training.autoHrMax(age(c), hrMaxUsedPeak(c));
        }
        int set = hrMaxSetting(c);
        return set > 0 ? set : Training.ageHrMax(age(c));
    }

    /** Where {@link #hrMax} comes from, in words for the coach. */
    static String hrMaxSource(Context c) {
        if (hrMaxAuto(c)) {
            return hrMaxUsedPeak(c) > Training.ageHrMax(age(c))
                    ? "their highest recorded peak" : "the age formula (220 - age)";
        }
        return hrMaxSetting(c) > 0 ? "typed in by them in settings" : "the age formula (220 - age)";
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
                out.add(new CoachLogic.Test(o.getLong("t"), o.getInt("rest"), o.optInt("rmssd"),
                        o.optInt("vo2")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    static Training.Profile profile(Context c) {
        return new Training.Profile(hrMax(c), male(c), age(c), weight(c));
    }

    /** Move minutes from Health Connect: shown on the home screen and told to the coach. */
    static boolean moveOn(Context c) {
        return of(c).getBoolean("moveOn", false);
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
