package com.emre.hrbuzz;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Plain-JVM checks for move minutes. Run with ./test.sh */
public final class MoveTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final long MIN = 60000L;
    /** 2026-10-21 10:00 London (BST ended 25 Oct) */
    private static final long T0 = java.time.ZonedDateTime.of(2026, 10, 21, 10, 0, 0, 0, LONDON)
            .toInstant().toEpochMilli();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
    private static int failures;

    public static void main(String[] args) {
        steps();
        origins();
        workouts();
        midnight();
        level();
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }

    private static void steps() {
        Move.Counter c = new Move.Counter();
        c.addSteps("phone", T0, T0 + 10 * MIN, 1000);        // 100/min: 10 moving minutes
        c.addSteps("phone", T0 + 20 * MIN, T0 + 30 * MIN, 300);   // 30/min: pottering
        c.addSteps("phone", T0 + 40 * MIN, T0 + 41 * MIN, 50);    // exactly the threshold
        eq("brisk + threshold", 11, c.perDay(LONDON).get(DAY));
        eq("zero steps ignored", null, new Move.Counter().perDay(LONDON).get(DAY));
    }

    private static void origins() {
        Move.Counter c = new Move.Counter();
        c.addSteps("phone", T0, T0 + 5 * MIN, 400);    // 80/min
        c.addSteps("watch", T0, T0 + 5 * MIN, 400);    // the same walk
        eq("two devices, one walk", 5, c.perDay(LONDON).get(DAY));
        // Each device only caught half of the minutes' steps: 30 + 30 is not 60.
        Move.Counter d = new Move.Counter();
        d.addSteps("phone", T0, T0 + 5 * MIN, 150);
        d.addSteps("watch", T0, T0 + 5 * MIN, 150);
        eq("not summed across devices", null, d.perDay(LONDON).get(DAY));
    }

    private static void workouts() {
        Move.Counter c = new Move.Counter();
        c.addSpan(T0, T0 + 30 * MIN);                   // gym session, no steps
        c.addSteps("phone", T0 + 10 * MIN, T0 + 20 * MIN, 1000);   // inside it: not twice
        eq("workout minutes, once", 30, c.perDay(LONDON).get(DAY));
        Move.Counter bad = new Move.Counter();
        bad.addSpan(T0, T0 + 48 * 60 * MIN);
        eq("absurd span ignored", null, bad.perDay(LONDON).get(DAY));
    }

    private static void midnight() {
        long mid = java.time.ZonedDateTime.of(2026, 10, 21, 23, 55, 0, 0, LONDON)
                .toInstant().toEpochMilli();
        Move.Counter c = new Move.Counter();
        c.addSteps("phone", mid, mid + 10 * MIN, 1000);
        Map<LocalDate, Integer> days = c.perDay(LONDON);
        eq("before midnight", 5, days.get(DAY));
        eq("after midnight", 5, days.get(DAY.plusDays(1)));
    }

    private static void level() {
        eq("sedentary", "sedentary", Move.level(new int[]{5, 8, 10, 6, 4, 9, 7}));
        eq("light", "lightly active", Move.level(new int[]{15, 18, 12, 20, 14, 16, 19}));
        eq("active", "active", Move.level(new int[]{30, 25, 40, 22, 35, 28, 31}));
        eq("too little known", "unknown", Move.level(new int[]{30, -1, -1, -1, 20, -1, -1}));
        eq("unknown days skipped", "active",
                Move.level(new int[]{-1, 30, 25, 40, -1, -1, 31}));
        Map<LocalDate, Integer> m = new java.util.HashMap<>();
        m.put(DAY, 9);
        Move.Summary s = new Move.Summary(m, T0);
        eq("covered day", 9, s.on(DAY));
        eq("uncovered day", -1, s.on(DAY.minusDays(1)));
        eq("total skips unknown", 9, Move.Summary.total(s.lastWeek(DAY)));
    }

    private static void eq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name + ": want " + want + ", got " + got);
        }
    }
}
