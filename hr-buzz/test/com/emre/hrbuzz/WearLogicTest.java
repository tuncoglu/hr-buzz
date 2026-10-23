package com.emre.hrbuzz;

public class WearLogicTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    public static void main(String[] args) {
        // the gauge starts at half of max and ends at max
        check(near(WearLogic.fraction(85, 170), 0f), "50% of max is the start");
        check(near(WearLogic.fraction(170, 170), 1f), "100% of max is the end");
        check(near(WearLogic.fraction(127, 170), 0.4941f), "mid value");
        check(near(WearLogic.fraction(60, 170), 0f), "below the start clamps to 0");
        check(near(WearLogic.fraction(200, 170), 1f), "above max clamps to 1");
        check(near(WearLogic.fraction(-1, 170), 0f), "no reading is 0");
        check(near(WearLogic.fraction(100, 0), 0f), "no max is 0");

        // angles run clockwise from bottom left, through the top, to bottom right
        check(near(WearLogic.angleFor(85, 170), 135f), "start angle");
        check(near(WearLogic.angleFor(170, 170), 405f), "end angle");
        check(near(WearLogic.angleFor(127, 170), 135f + 270f * 0.4941f), "mid angle");

        // five equal segments with a gap, matching Training's zone floors
        check(near(WearLogic.segmentStart(1), 135f), "zone 1 start");
        check(near(WearLogic.segmentStart(2), 189f), "zone 2 start");
        check(near(WearLogic.segmentStart(5), 351f), "zone 5 start");
        check(near(WearLogic.segmentSweep(3), 51f), "segment sweep");
        for (int z = 1; z <= 5; z++) {
            int floor = Training.zoneFloor(z, 170);
            check(near(WearLogic.angleFor(floor, 170), WearLogic.segmentStart(z)), "zone " + z
                    + " floor lands on its segment start (floor " + floor + ")");
        }

        // limits
        check(WearLogic.overBy(162, 150) == 12, "over by 12");
        check(WearLogic.overBy(150, 150) == 0, "at the limit is not over");
        check(WearLogic.overBy(162, 0) == 0, "no limit, never over");
        check(WearLogic.underBy(100, 110) == 10, "under by 10");
        check(WearLogic.underBy(110, 110) == 0, "at the low limit is not under");
        check(WearLogic.underBy(-1, 110) == 0, "no reading is not under");
        check(WearLogic.underBy(100, 0) == 0, "no low limit");

        // time in zone
        long[] ms = {0, 12000, 71000, 13000, 4000, 0};
        int[] pct = WearLogic.zonePercents(ms);
        check(pct[0] == 12 && pct[1] == 71 && pct[2] == 13 && pct[3] == 4 && pct[4] == 0,
                "zone percents");
        check(WearLogic.zonePercents(new long[6])[1] == 0, "no time, no percent");

        // km splits
        check(WearLogic.kmCrossed(990, 1010), "crossing 1 km");
        check(!WearLogic.kmCrossed(1010, 1500), "inside the same km");
        check(WearLogic.kmCrossed(1990, 2005), "crossing 2 km");
        check(!WearLogic.kmCrossed(0, 999), "not yet 1 km");
        check(!WearLogic.kmCrossed(1500, 1500), "standing still");
        check(!WearLogic.kmCrossed(2001, 1999), "a GPS wobble backwards does not count");

        // auto-pause
        WearLogic.AutoPause ap = new WearLogic.AutoPause();
        check(ap.update(3.0, 0, false) == WearLogic.AutoPause.NONE, "moving: nothing");
        check(ap.update(0.2, 1000, false) == WearLogic.AutoPause.NONE, "just slowed");
        check(ap.update(0.2, 5000, false) == WearLogic.AutoPause.NONE, "4 s still, not yet");
        check(ap.update(0.2, 6000, false) == WearLogic.AutoPause.PAUSE, "5 s still: pause");
        check(ap.update(0.1, 7000, true) == WearLogic.AutoPause.NONE, "paused and still: nothing");
        check(ap.update(2.0, 8000, true) == WearLogic.AutoPause.NONE, "just set off");
        check(ap.update(2.0, 10000, true) == WearLogic.AutoPause.RESUME, "2 s moving: resume");
        // a short stop does not pause
        ap = new WearLogic.AutoPause();
        ap.update(0.1, 0, false);
        ap.update(2.0, 3000, false);
        check(ap.update(0.1, 4000, false) == WearLogic.AutoPause.NONE, "stop timer restarts");
        check(ap.update(0.1, 8000, false) == WearLogic.AutoPause.NONE, "only 4 s since restart");
        // a flicker above the resume speed does not resume
        ap = new WearLogic.AutoPause();
        ap.update(2.0, 0, true);
        ap.update(0.5, 1000, true);
        check(ap.update(2.0, 2500, true) == WearLogic.AutoPause.NONE, "flicker restarts the timer");
        // no fix: never acts
        ap = new WearLogic.AutoPause();
        ap.update(0.1, 0, false);
        check(ap.update(-1, 9000, false) == WearLogic.AutoPause.NONE, "no fix, no pause");
        check(ap.update(0.1, 9500, false) == WearLogic.AutoPause.NONE, "no fix cleared the timer");

        // zone change
        WearLogic.ZoneWatch zw = new WearLogic.ZoneWatch();
        int changes = 0;
        for (int i = 0; i < 8; i++) {
            changes += zw.feed(2) != WearLogic.ZoneWatch.NONE ? 1 : 0;
        }
        check(changes == 0, "the first zone is not a change");
        check(zw.current() == 2, "settled in zone 2");
        for (int i = 0; i < 4; i++) {
            check(zw.feed(3) == WearLogic.ZoneWatch.NONE, "4 readings in zone 3 are not enough");
        }
        check(zw.feed(3) == 3, "the 5th reading in zone 3 is a change");
        check(zw.feed(3) == WearLogic.ZoneWatch.NONE, "and only once");
        // flicker across a boundary
        for (int i = 0; i < 10; i++) {
            check(zw.feed(i % 2 == 0 ? 4 : 3) == WearLogic.ZoneWatch.NONE, "flicker is ignored");
        }
        for (int i = 0; i < 4; i++) {
            zw.feed(2);
        }
        check(zw.feed(2) == 2, "dropping back is a change too");

        // this week
        java.time.ZoneId utc = java.time.ZoneId.of("UTC");
        java.time.ZoneId tokyo = java.time.ZoneId.of("Asia/Tokyo");
        // Friday 23 October 2026, noon UTC; its week runs Monday 19th to Sunday 25th
        long fri = java.time.ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, utc).toInstant().toEpochMilli();
        long mon = java.time.ZonedDateTime.of(2026, 10, 19, 7, 0, 0, 0, utc).toInstant().toEpochMilli();
        long lastSun = java.time.ZonedDateTime.of(2026, 10, 18, 22, 0, 0, 0, utc).toInstant().toEpochMilli();
        long min = 10 * 60000L;
        WearLogic.Week wk = WearLogic.week(
                new long[]{lastSun, mon, mon + 3600000L, fri - 3600000L},
                new long[]{40 * 60000L, 6 * 60000L, 5 * 60000L, 3 * 60000L},
                new double[]{8000, 1000, 900, 400}, new double[]{50, 6, 5, 2}, fri, utc, min);
        check(wk.today == 4, "Friday is index 4");
        check(wk.days[0], "Monday's two short sessions add up to a day");
        check(!wk.days[4], "a 3 minute session on Friday is not a day");
        check(wk.dayCount == 1, "one exercise day");
        check(wk.sessions == 3, "last Sunday's session is not this week's");
        check(Math.abs(wk.distanceM - 2300) < 0.01, "distance adds up");
        check(wk.activeMs == 14 * 60000L, "time adds up");
        check(Math.abs(wk.trimp - 13) < 0.01, "load adds up");
        // the same week seen from Tokyo: Sunday 22:00 UTC is already Monday there
        WearLogic.Week tk = WearLogic.week(new long[]{lastSun}, new long[]{40 * 60000L},
                new double[]{8000}, new double[]{50}, fri, tokyo, min);
        check(tk.sessions == 1 && tk.days[0], "time zones decide which day a session is on");
        // a Sunday is the last day of its week, not the first
        long sun = java.time.ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, utc).toInstant().toEpochMilli();
        WearLogic.Week su = WearLogic.week(new long[]{mon}, new long[]{30 * 60000L},
                new double[]{5000}, new double[]{20}, sun, utc, min);
        check(su.today == 6 && su.days[0] && su.sessions == 1, "Sunday still belongs to that Monday's week");
        check(WearLogic.week(new long[0], new long[0], new double[0], new double[0], fri, utc, min)
                .dayCount == 0, "an empty week");

        // cadence
        WearLogic.Cadence cad = new WearLogic.Cadence();
        check(cad.spm(0) == 0, "no readings, no cadence");
        // 170 steps a minute: a reading every 2 s, 5.67 steps each
        long t0 = 100000;
        for (int i = 0; i <= 10; i++) {
            cad.feed(t0 + i * 2000L, Math.round(i * 2000 * 170 / 60000.0) + 1000);
        }
        int rate = cad.spm(t0 + 20000);
        check(rate >= 165 && rate <= 175, "steady 170 spm reads about 170, got " + rate);
        check(cad.spm(t0 + 20000 + 11000) == 0, "11 s without a step: stopped");
        // standing, then walking again: a gap in the readings is fine
        WearLogic.Cadence g = new WearLogic.Cadence();
        g.feed(0, 500);
        g.feed(30000, 500);        // same count: ignored
        for (int i = 1; i <= 8; i++) {
            g.feed(30000 + i * 1000L, 500 + i * 2);        // 120 spm
        }
        int walk = g.spm(38000);
        check(walk >= 110 && walk <= 130, "120 spm after standing reads about 120, got " + walk);
        // a counter that restarts (reboot) does not give a negative or huge rate
        WearLogic.Cadence r = new WearLogic.Cadence();
        r.feed(0, 90000);
        r.feed(5000, 90010);
        r.feed(10000, 12);
        r.feed(15000, 26);
        check(r.spm(15000) >= 0 && r.spm(15000) < 400, "counter restart is survived");

        // the average ignores time spent standing
        WearLogic.Cadence avg = new WearLogic.Cadence();
        avg.feed(0, 1000);
        for (int i = 1; i <= 20; i++) {
            avg.feed(i * 500L, 1000 + i);              // 20 steps in 10 s: 120 spm
        }
        avg.feed(40000, 1020);                          // same count: standing for 30 s
        for (int i = 1; i <= 20; i++) {
            avg.feed(40000 + i * 500L, 1020 + i);      // 20 more steps in 10 s
        }
        int average = avg.averageSpm();
        check(average >= 115 && average <= 125, "average over stepping time is about 120, got " + average);
        avg.resetAverage();
        check(avg.averageSpm() == 0, "a new run starts with no average");
        check(new WearLogic.Cadence().averageSpm() == 0, "no steps, no average");

        // daily steps
        WearLogic.StepTally tally = new WearLogic.StepTally();
        check(tally.update("2026-10-23", 1000) == 0, "the first read only sets the baseline");
        check(tally.update("2026-10-23", 1400) == 400, "steps since the last read");
        check(tally.update("2026-10-23", 1400) == 400, "no new steps");
        check(tally.update("2026-10-24", 1500) == 100, "a new day starts again, with its own steps");
        check(tally.update("2026-10-24", 30) == 130, "after a restart the counter starts from zero");
        WearLogic.StepTally saved = new WearLogic.StepTally(tally.day(), tally.last(), tally.today());
        check(saved.update("2026-10-24", 80) == 180, "state survives being saved and loaded");

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("WearLogicTest ok");
    }
}
