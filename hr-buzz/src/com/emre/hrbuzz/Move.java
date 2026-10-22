package com.emre.hrbuzz;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Move minutes: the minutes of a day spent moving, from step counts and workouts. Google Fit's
 * own "Move Minutes" has no equivalent in Health Connect, so this counts them: a minute is a
 * move minute when it has at least {@link #STEPS_PER_MIN} steps, or falls inside a workout.
 * Pure Java, tested.
 */
public final class Move {

    /** A slow walk is about 60 steps a minute; below this it is shuffling, not moving. */
    public static final int STEPS_PER_MIN = 50;
    /** The WHO guideline for adults: 150 minutes of moderate activity a week. */
    public static final int WEEKLY_GOAL = 150;
    /** A workout record longer than this is a bad record, not a day-long workout. */
    private static final long MAX_SPAN_MS = 12L * 3600 * 1000;
    private static final long MINUTE = 60000L;

    private Move() {
    }

    /** Collects step intervals and workout spans, then counts the minutes per local day. */
    public static final class Counter {
        /** origin -> minute index -> steps. Phone and watch both write steps for the same walk. */
        private final Map<String, Map<Long, Double>> steps = new HashMap<>();
        private final Set<Long> spans = new HashSet<>();

        /** Spreads {@code count} steps evenly over [start, end). */
        public void addSteps(String origin, long start, long end, long count) {
            if (count <= 0 || end <= start) {
                return;
            }
            Map<Long, Double> perMinute = steps.computeIfAbsent(origin, k -> new HashMap<>());
            double rate = (double) count / (end - start);
            for (long m = Math.floorDiv(start, MINUTE); m * MINUTE < end; m++) {
                long overlap = Math.min(end, (m + 1) * MINUTE) - Math.max(start, m * MINUTE);
                perMinute.merge(m, rate * overlap, Double::sum);
            }
        }

        /** Every minute of [start, end) counts, e.g. a recorded workout. */
        public void addSpan(long start, long end) {
            if (end <= start || end - start > MAX_SPAN_MS) {
                return;
            }
            for (long m = Math.floorDiv(start, MINUTE); m * MINUTE < end; m++) {
                // A minute the workout covers for less than half does not count as moving.
                long overlap = Math.min(end, (m + 1) * MINUTE) - Math.max(start, m * MINUTE);
                if (overlap * 2 >= MINUTE) {
                    spans.add(m);
                }
            }
        }

        /** Move minutes per local day, oldest first. */
        public TreeMap<LocalDate, Integer> perDay(ZoneId zone) {
            // Phone and watch both counting one walk must not double it: take the origin with
            // the most steps in each minute.
            Map<Long, Double> best = new HashMap<>();
            for (Map<Long, Double> perMinute : steps.values()) {
                for (Map.Entry<Long, Double> e : perMinute.entrySet()) {
                    best.merge(e.getKey(), e.getValue(), Math::max);
                }
            }
            Set<Long> moving = new HashSet<>(spans);
            for (Map.Entry<Long, Double> e : best.entrySet()) {
                if (e.getValue() >= STEPS_PER_MIN) {
                    moving.add(e.getKey());
                }
            }
            TreeMap<LocalDate, Integer> days = new TreeMap<>();
            for (long m : moving) {
                LocalDate d = Instant.ofEpochMilli(m * MINUTE).atZone(zone).toLocalDate();
                days.merge(d, 1, Integer::sum);
            }
            return days;
        }
    }

    /** The last days of move minutes, as the home screen and the coach use them. */
    public static final class Summary {
        public final Map<LocalDate, Integer> days;
        public final long asOf;

        public Summary(Map<LocalDate, Integer> days, long asOf) {
            this.days = days;
            this.asOf = asOf;
        }

        /** Minutes on that day, or -1 when the sync did not cover it. */
        public int on(LocalDate day) {
            Integer m = days.get(day);
            return m == null ? -1 : m;
        }

        /** The 7 days ending today (today first). -1 where unknown. */
        public int[] lastWeek(LocalDate today) {
            int[] out = new int[7];
            for (int i = 0; i < 7; i++) {
                out[i] = on(today.minusDays(i));
            }
            return out;
        }

        /** Sum of the known days among the 7 ending today. */
        public static int total(int[] week) {
            int t = 0;
            for (int m : week) {
                t += Math.max(m, 0);
            }
            return t;
        }
    }

    /**
     * A word for how mobile the person has been over the 7 days, from the average of the days
     * that are known, against the weekly goal spread evenly (about 21 min a day).
     */
    public static String level(int[] week) {
        int known = 0;
        int sum = 0;
        for (int m : week) {
            if (m >= 0) {
                known++;
                sum += m;
            }
        }
        if (known < 3) {
            return "unknown";
        }
        double perDay = (double) sum / known;
        double goalPerDay = WEEKLY_GOAL / 7.0;
        if (perDay < goalPerDay / 2) {
            return "sedentary";
        }
        return perDay < goalPerDay ? "lightly active" : "active";
    }
}
