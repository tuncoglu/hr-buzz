package com.enftc.hrbuzz;

import android.content.Context;

/** The watch's run options, kept in the same preferences file as everything else. */
final class WearPrefs {

    private WearPrefs() {
    }

    /** A buzz at every kilometre. On by default, like Fitbit's 1 km split alert. */
    static boolean kmBuzz(Context c) {
        return Prefs.of(c).getBoolean("kmBuzz", true);
    }

    /** A buzz when heart rate settles in another zone. Off by default: the limit alerts say more. */
    static boolean zoneBuzz(Context c) {
        return Prefs.of(c).getBoolean("zoneBuzz", false);
    }

    /** Pause by itself when you stop (GPS sports only). Off by default. */
    static boolean autoPause(Context c) {
        return Prefs.of(c).getBoolean("autoPause", false);
    }

    static void set(Context c, String key, boolean value) {
        Prefs.of(c).edit().putBoolean(key, value).apply();
    }
}
