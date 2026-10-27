package com.enftc.hrbuzz;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * The maths behind the watch's zone ring, kept free of Android imports so it runs on a plain JVM.
 *
 * The ring is a 270 degree gauge open at the bottom. It starts at 50% of max heart rate (the
 * bottom of zone 1) and ends at 100%, so each of the five zones gets an equal 54 degrees.
 */
public final class WearLogic {

    /** Where the gauge starts, in degrees clockwise from 3 o'clock (bottom left). */
    public static final float START_DEG = 135f;
    public static final float SWEEP_DEG = 270f;
    public static final int ZONES = 5;
    /** Visible gap left between neighbouring zone segments, degrees. */
    public static final float GAP_DEG = 3f;

    private WearLogic() {
    }

    /** @return 0..1 along the gauge: 0 at 50% of max, 1 at 100% and above */
    public static float fraction(int bpm, int hrMax) {
        if (bpm <= 0 || hrMax <= 0) {
            return 0f;
        }
        float f = (bpm - 0.5f * hrMax) / (0.5f * hrMax);
        return Math.max(0f, Math.min(1f, f));
    }

    /** @return the angle of {@code bpm} on the ring, degrees clockwise from 3 o'clock */
    public static float angleFor(int bpm, int hrMax) {
        return START_DEG + SWEEP_DEG * fraction(bpm, hrMax);
    }

    /** @return where zone {@code zone} (1..5) starts on the ring, before the gap is taken off */
    public static float segmentStart(int zone) {
        return START_DEG + SWEEP_DEG / ZONES * (zone - 1);
    }

    /** @return how far zone {@code zone} reaches on the ring, with the gap taken off */
    public static float segmentSweep(int zone) {
        return SWEEP_DEG / ZONES - GAP_DEG;
    }

    /** @return bpm over the limit, or 0 when at or under it, or when there is no limit */
    public static int overBy(int bpm, int high) {
        return high > 0 && bpm > high ? bpm - high : 0;
    }

    /** @return bpm under the low limit, or 0 when at or over it, or when there is none */
    public static int underBy(int bpm, int low) {
        return low > 0 && bpm > 0 && bpm < low ? low - bpm : 0;
    }

    /** @return the share of heart-rate time spent in each of zones 1..5, in whole percent */
    public static int[] zonePercents(long[] zoneMs) {
        int[] out = new int[ZONES];
        long total = 0;
        for (int z = 1; z <= ZONES; z++) {
            total += zoneMs[z];
        }
        if (total <= 0) {
            return out;
        }
        for (int z = 1; z <= ZONES; z++) {
            out[z - 1] = (int) Math.round(100.0 * zoneMs[z] / total);
        }
        return out;
    }

    /** @return true when the distance has just passed a whole kilometre */
    public static boolean kmCrossed(double previousM, double nowM) {
        return (long) (nowM / 1000) > (long) (previousM / 1000) && nowM > previousM;
    }

    /**
     * Pauses a run by itself when you stop moving and resumes it when you set off again, from
     * the GPS speed. It never acts without a speed (no fix), and only resumes a pause it made.
     */
    public static final class AutoPause {

        public static final int NONE = 0;
        public static final int PAUSE = 1;
        public static final int RESUME = 2;

        /** Slower than this (about 2.9 km/h) counts as standing still. */
        public static final double STOP_BELOW_MS = 0.8;
        /** Faster than this (about 4.3 km/h) counts as moving again. */
        public static final double GO_ABOVE_MS = 1.2;
        public static final long STOP_AFTER_MS = 5000;
        public static final long GO_AFTER_MS = 2000;

        private long slowSince = -1;
        private long fastSince = -1;

        /**
         * @param speed metres per second, or a negative number when there is no fresh fix
         * @param paused whether this class's own pause is currently in force
         */
        public int update(double speed, long now, boolean paused) {
            if (speed < 0) {
                slowSince = -1;
                fastSince = -1;
                return NONE;
            }
            if (!paused) {
                fastSince = -1;
                if (speed >= STOP_BELOW_MS) {
                    slowSince = -1;
                    return NONE;
                }
                if (slowSince < 0) {
                    slowSince = now;
                }
                if (now - slowSince >= STOP_AFTER_MS) {
                    slowSince = -1;
                    return PAUSE;
                }
                return NONE;
            }
            slowSince = -1;
            if (speed <= GO_ABOVE_MS) {
                fastSince = -1;
                return NONE;
            }
            if (fastSince < 0) {
                fastSince = now;
            }
            if (now - fastSince >= GO_AFTER_MS) {
                fastSince = -1;
                return RESUME;
            }
            return NONE;
        }

        public void reset() {
            slowSince = -1;
            fastSince = -1;
        }
    }

    /**
     * Notices a change of heart rate zone that has held for a few readings, so a reading that
     * flickers across a boundary does not buzz every second.
     */
    public static final class ZoneWatch {

        public static final int NONE = -1;
        /** Consecutive readings in a new zone before it counts. */
        public static final int STABLE = 5;

        private int zone = -1;
        private int candidate = -1;
        private int count;

        /** @return the new zone (0..5) when it has just changed, otherwise {@link #NONE} */
        public int feed(int newZone) {
            if (newZone == candidate) {
                count++;
            } else {
                candidate = newZone;
                count = 1;
            }
            if (count < STABLE || candidate == zone) {
                return NONE;
            }
            int before = zone;
            zone = candidate;
            return before < 0 ? NONE : zone;      // the first stable zone is not a change
        }

        /** The zone it has settled in, or -1 before the first one. */
        public int current() {
            return zone;
        }

        /** Forget everything, for the start of a new run. */
        public void reset() {
            zone = -1;
            candidate = -1;
            count = 0;
        }
    }

    /** What this week (Monday to Sunday) came to. */
    public static final class Week {
        /** Days of the week with enough movement to count, Monday first. */
        public final boolean[] days = new boolean[7];
        public int dayCount;
        /** 0 for Monday .. 6 for Sunday. */
        public int today;
        public double distanceM;
        public long activeMs;
        public double trimp;
        public int sessions;
    }

    /**
     * Adds up the sessions that started this week. A day counts once its sessions together
     * have {@code minDayMs} of moving time, so a false start does not make an exercise day.
     * The arrays are parallel, one entry per session.
     */
    public static Week week(long[] starts, long[] activeMs, double[] distanceM, double[] trimp,
                            long now, ZoneId zone, long minDayMs) {
        Week w = new Week();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        w.today = today.getDayOfWeek().getValue() - 1;
        long[] dayMs = new long[7];
        for (int i = 0; i < starts.length; i++) {
            LocalDate day = Instant.ofEpochMilli(starts[i]).atZone(zone).toLocalDate();
            long offset = java.time.temporal.ChronoUnit.DAYS.between(monday, day);
            if (offset < 0 || offset > 6) {
                continue;
            }
            dayMs[(int) offset] += activeMs[i];
            w.activeMs += activeMs[i];
            w.distanceM += distanceM[i];
            w.trimp += trimp[i];
            w.sessions++;
        }
        for (int d = 0; d < 7; d++) {
            w.days[d] = dayMs[d] >= minDayMs;
            if (w.days[d]) {
                w.dayCount++;
            }
        }
        return w;
    }

    /**
     * Steps a minute over the last few seconds, from the step counter's cumulative readings.
     * The counter only reports when steps happen, so standing still shows up as the same
     * count getting older: cadence falls to zero without any new reading.
     */
    public static final class Cadence {

        /** The window the rate is measured over. */
        public static final long WINDOW_MS = 20000;
        /** A longer gap between readings means you stopped, and the stretch starts again. */
        public static final long GAP_MS = 4000;
        private static final int KEEP = 64;

        private final long[] times = new long[KEEP];
        private final long[] counts = new long[KEEP];
        private int size;
        private int head;
        /** Steps and time within unbroken stretches of stepping, for {@link #averageSpm()}. */
        private long steppingSteps;
        private long steppingMs;

        /** Adds a reading of the cumulative step count at {@code t} (ms). */
        public void feed(long t, long count) {
            if (size > 0) {
                int last = (head - 1 + KEEP) % KEEP;
                if (count < counts[last]) {
                    size = 0;           // the counter restarted (reboot): start again
                } else if (count == counts[last]) {
                    return;             // no new steps: the older reading stands
                } else if (t - times[last] <= GAP_MS) {
                    steppingSteps += count - counts[last];
                    steppingMs += t - times[last];
                }
            }
            times[head] = t;
            counts[head] = count;
            head = (head + 1) % KEEP;
            size = Math.min(KEEP, size + 1);
        }

        /**
         * @return steps a minute averaged over the time spent stepping (standing still does not
         *         count), 0 when there was not enough of it
         */
        public int averageSpm() {
            return steppingMs < 5000 ? 0
                    : (int) Math.round(steppingSteps * 60000.0 / steppingMs);
        }

        /** Forgets the averages (a new run); the rate over the last seconds is unaffected. */
        public void resetAverage() {
            steppingSteps = 0;
            steppingMs = 0;
        }

        /**
         * @return steps a minute over the latest stretch of continuous stepping (readings less
         *         than {@link #GAP_MS} apart, at most {@link #WINDOW_MS} long), 0 when not stepping
         */
        public int spm(long now) {
            if (size < 2) {
                return 0;
            }
            int newest = (head - 1 + KEEP) % KEEP;
            if (now - times[newest] > WINDOW_MS / 2) {
                return 0;               // nothing for 10 s: stopped
            }
            int idx = newest;
            for (int i = 1; i < size; i++) {
                int k = (newest - i + KEEP) % KEEP;
                int next = (k + 1) % KEEP;
                if (times[next] - times[k] > GAP_MS || times[newest] - times[k] > WINDOW_MS) {
                    break;
                }
                idx = k;
            }
            long span = times[newest] - times[idx];
            long steps = counts[newest] - counts[idx];
            if (steps <= 0 || span < 3000) {
                return 0;
            }
            return (int) Math.round(steps * 60000.0 / span);
        }
    }

    /**
     * Steps today from occasional reads of a counter that counts since the watch last started.
     * A read attributes the steps since the previous read to the day of the read, so the more
     * often it is read the better the day boundary.
     */
    public static final class StepTally {

        private String day = "";
        private long last = -1;
        private long today;

        public StepTally() {
        }

        public StepTally(String day, long last, long today) {
            this.day = day;
            this.last = last;
            this.today = today;
        }

        /** @return the steps counted so far on {@code dayKey} */
        public long update(String dayKey, long counter) {
            long delta = last < 0 ? 0 : counter >= last ? counter - last : counter;
            if (!dayKey.equals(day)) {
                day = dayKey;
                today = 0;
            }
            today += delta;
            last = counter;
            return today;
        }

        public String day() {
            return day;
        }

        public long last() {
            return last;
        }

        public long today() {
            return today;
        }
    }
}
