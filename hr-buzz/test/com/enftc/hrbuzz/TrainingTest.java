package com.enftc.hrbuzz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-JVM checks for session maths and export. Run with ./test.sh
 */
public final class TrainingTest {

    private static int failures;

    public static void main(String[] args) {
        zones();
        calories();
        distance();
        stats();
        splitsAndPause();
        csv();
        effort();
        hrv();
        format();
        export();
        profileValues();
        planAnnouncement();
        weeks();
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }

    private static void weeks() {
        java.time.ZoneId london = java.time.ZoneId.of("Europe/London");
        long wed = java.time.ZonedDateTime.of(2026, 10, 21, 10, 0, 0, 0, london).toInstant().toEpochMilli();
        long mon = java.time.ZonedDateTime.of(2026, 10, 19, 6, 0, 0, 0, london).toInstant().toEpochMilli();
        long sun = java.time.ZonedDateTime.of(2026, 10, 18, 23, 30, 0, 0, london).toInstant().toEpochMilli();
        long sat = java.time.ZonedDateTime.of(2026, 10, 3, 9, 0, 0, 0, london).toInstant().toEpochMilli();
        eq("Monday morning is this week", Training.weekStartDay(wed, london), Training.weekStartDay(mon, london));
        ok("Sunday night is the week before", Training.weekStartDay(sun, london) < Training.weekStartDay(mon, london));
        eq("this week", "This week", Training.weekLabel(Training.weekStartDay(mon, london), wed, london));
        eq("last week", "Last week", Training.weekLabel(Training.weekStartDay(sun, london), wed, london));
        eq("an older week is named by its Monday", "Week of 28 Sep",
                Training.weekLabel(Training.weekStartDay(sat, london), wed, london));
    }

    private static void planAnnouncement() {
        eq("in target", "Step 2 of 3. 14 minutes left. 3.20 kilometres. Heart rate 118, in target.",
                Training.planAnnouncement(2, 3, 13 * 60000L + 20000L, 118, 0, true, 3200));
        eq("above: ease off", "Step 1 of 3. 5 minutes left. Heart rate 131, above target. Ease off.",
                Training.planAnnouncement(1, 3, 5 * 60000L, 131, 1, false, 0));
        eq("below: pick it up", "Step 3 of 3. 1 minute left. Heart rate 95, below target. Pick it up.",
                Training.planAnnouncement(3, 3, 59999 + 1, 95, -1, false, 0));
        eq("the last seconds", "Step 2 of 2. Under a minute left. Heart rate 120, in target.",
                Training.planAnnouncement(2, 2, 45000, 120, 0, false, 0));
        eq("no heart rate: just where they are", "Step 1 of 4. 10 minutes left.",
                Training.planAnnouncement(1, 4, 10 * 60000L, -1, 0, false, 0));
        eq("no distance yet is not announced", "Step 1 of 4. 10 minutes left.",
                Training.planAnnouncement(1, 4, 10 * 60000L, -1, 0, true, 0));
    }

    private static void zones() {
        eq("zone below 50%", 0, Training.zone(94, 190));
        eq("zone 1 at exactly 50%", 1, Training.zone(95, 190));
        eq("zone 2", 2, Training.zone(120, 190));
        eq("zone 3 at 70%", 3, Training.zone(133, 190));
        eq("zone 4", 4, Training.zone(155, 190));
        eq("zone 5", 5, Training.zone(180, 190));
        eq("zone above max", 5, Training.zone(200, 190));
        eq("no reading", 0, Training.zone(-1, 190));
        eq("zone 4 floor", 152, Training.zoneFloor(4, 190));
        eq("zone 3 floor rounds up", 133, Training.zoneFloor(3, 189));
        eq("percent", 74, Training.percentOfMax(140, 190));
    }

    private static void calories() {
        // Keytel: male 40 y, 80 kg, 140 bpm -> (-55.0969+88.326+15.904+8.068)/4.184 = 13.67
        near("keytel male", 13.67, Training.kcalPerMin(140, true, 40, 80), 0.02);
        // female 35 y, 60 kg, 150 bpm -> (-20.4022+67.08-7.578+2.59)/4.184 = 9.96
        near("keytel female", 9.96, Training.kcalPerMin(150, false, 35, 60), 0.02);
        near("never negative", 0, Training.kcalPerMin(30, true, 20, 40), 0);
        near("no hr, no kcal", 0, Training.kcalPerMin(-1, true, 40, 80), 0);
        double light = Training.LIGHT_MET * 3.5 * 75 / 200;
        near("easy hr: light activity", light, Training.kcalPerMin(75, true, 40, 75, 180), 1e-9);
        near("hard hr: Keytel", Training.kcalPerMin(150, true, 40, 75),
                Training.kcalPerMin(150, true, 40, 75, 180), 1e-9);
        double stroll = Training.kcalPerMin(93, true, 40, 75, 180);
        ok("93 bpm: between the two", stroll > light && stroll < Training.kcalPerMin(93, true, 40, 75));
    }

    private static void distance() {
        // one thousandth of a degree of latitude ~ 111.2 m
        near("haversine lat", 111.19, Training.distance(51.5, -0.12, 51.501, -0.12), 0.1);
        near("same point", 0, Training.distance(51.5, -0.12, 51.5, -0.12), 1e-9);
    }

    private static void stats() {
        Training.Stats s = new Training.Stats(new Training.Profile(190, true, 40, 80));
        // 120 s at 3 m/s with HR stepping 140 then 160
        for (int i = 0; i <= 120; i++) {
            s.add(sample(1000L * i, i < 60 ? 140 : 160, 3.0 * i));
        }
        eq("active ms", 120000, (int) s.activeMs);
        near("distance", 360, s.distanceM, 0.01);
        eq("max hr", 160, s.maxHr);
        // each sample owns the second before it: 59 s at 140, 61 s at 160
        eq("avg hr", 150, s.avgHr());
        near("current speed", 3.0, s.speed(), 1e-6);
        near("best speed", 3.0, s.bestSpeed, 1e-6);
        eq("time in zone 3 (140 = 73%)", 59000, (int) s.zoneMs[3]);
        eq("time in zone 4 (160 = 84%)", 61000, (int) s.zoneMs[4]);
        double kcal = 59 / 60.0 * Training.kcalPerMin(140, true, 40, 80)
                + 61 / 60.0 * Training.kcalPerMin(160, true, 40, 80);
        near("kcal", kcal, s.kcal, 1e-6);
        near("trimp", 59 / 60.0 * 3 + 61 / 60.0 * 4, s.trimp(), 1e-6);

        // a long gap (app restarted) counts at most MAX_TICK_MS
        s.add(sample(1000L * 120 + 60000, 160, 360));
        eq("gap capped", 120000 + (int) Training.MAX_TICK_MS, (int) s.activeMs);

        // no HR samples: no zone time, no kcal
        Training.Stats noHr = new Training.Stats(new Training.Profile(190, true, 40, 80));
        noHr.add(sample(0, -1, 0));
        noHr.add(sample(1000, -1, 0));
        eq("no hr: avg 0", 0, noHr.avgHr());
        near("no hr: kcal 0", 0, noHr.kcal, 0);

        // a lost GPS fix: distance freezes 17 s, then the gap arrives in one 40 m step. The
        // distance counts, but the catch-up must not read as a fast 20 s stretch.
        Training.Stats walk = new Training.Stats(new Training.Profile(190, true, 40, 80));
        double d = 0;
        for (int i = 0; i <= 120; i++) {
            if (i < 60 || i > 77) {
                d += i == 78 ? 40 : 1.4;
            }
            walk.add(sample(1000L * i, 110, d));
        }
        near("catch-up distance counts", 1.4 * 60 + 40 + 1.4 * 42 - 1.4, walk.distanceM, 1.5);
        near("catch-up is not a sprint", 1.4, walk.bestSpeed, 0.1);

        // walking breaks: two moving stretches with one break between them
        Training.Stats br = new Training.Stats(new Training.Profile(190, true, 40, 80));
        for (int i = 0; i <= 60; i++) {
            br.add(sample(1000L * i, 150, 3.0 * i));
        }
        br.pause();
        for (int i = 0; i <= 30; i++) {
            br.add(sample(1000L * (300 + i), i == 0 ? 118 : 140, 180 + 1.4 * i));
        }
        List<long[]> seg = br.segments();
        eq("two stretches", 2, seg.size());
        eq("first stretch ms", 60000, (int) seg.get(0)[0]);
        eq("first stretch avg bpm", 150, (int) seg.get(0)[1]);
        eq("one break", 1, br.pauses.size());
        eq("break gap ms", 240000, (int) br.pauses.get(0)[0]);
        eq("bpm as the break started", 150, (int) br.pauses.get(0)[1]);
        eq("bpm as it ended", 118, (int) br.pauses.get(0)[2]);

        // a one-tick resume between pauses is part of the break, not a stretch of its own
        Training.Stats tap = new Training.Stats(new Training.Profile(190, true, 40, 80));
        tap.add(sample(0, 120, 0));
        tap.pause();                                    // one sample, then paused at once
        for (int i = 10; i <= 70; i++) {
            tap.add(sample(1000L * i, 150, 3.0 * i));
        }
        tap.pause();
        tap.add(sample(100000, 130, 210));              // resumed for one tick
        tap.pause();
        for (int i = 0; i <= 20; i++) {
            tap.add(sample(1000L * (200 + i), 140, 220 + 3.0 * i));
        }
        eq("double tap: two stretches", 2, tap.segments().size());
        eq("double tap: one break", 1, tap.pauses.size());
        eq("double tap: break runs from the first pause", 130000, (int) tap.pauses.get(0)[0]);
        eq("double tap: bpm as the break started", 150, (int) tap.pauses.get(0)[1]);

        // walk breaks: recording never stops, the parts are labelled
        Training.Stats wb = new Training.Stats(new Training.Profile(190, true, 40, 80));
        for (int i = 0; i < 120; i++) {
            boolean onBreak = i >= 60 && i < 90;
            Training.Sample x = sample(1000L * i, onBreak ? 130 : 160, (onBreak ? 1.4 : 3.0) * i);
            x.walk = onBreak;
            wb.add(x);
        }
        List<long[]> parts = wb.parts();
        eq("three parts", 3, parts.size());
        eq("first part is main", 0, (int) parts.get(0)[0]);
        eq("second part is the walk break", 1, (int) parts.get(1)[0]);
        eq("break avg bpm", 130, (int) parts.get(1)[2]);
        eq("bpm at the break's start is the effort's", 160, (int) parts.get(1)[5]);
        eq("break ends at its own bpm", 130, (int) parts.get(1)[6]);
        ok("distance and time carry on through the break", wb.activeMs >= 118000
                && wb.distanceM > 0);
        eq("a session without a walk break has no parts", 0, br.parts().size());
        Training.Sample rt = Training.Sample.parse(wb.parts().isEmpty() ? "" : sampleLine(true));
        ok("walk flag round-trips through the csv", rt != null && rt.walk);
        Training.Sample plain = Training.Sample.parse(sampleLine(false));
        ok("old rows read as not walking", plain != null && !plain.walk);
    }

    private static void splitsAndPause() {
        Training.Stats s = new Training.Stats(new Training.Profile(190, true, 40, 80));
        // 2.5 km at 4 m/s, pausing for 5 minutes halfway (time jumps, distance does not)
        long t = 0;
        double d = 0;
        for (int i = 0; i < 300; i++) {
            s.add(sample(t, 150, d));
            t += 1000;
            d += 4;
        }
        s.pause();
        t += 300000;
        d -= 4;     // no distance accrues while paused: resume where we stopped
        for (int i = 0; i < 326; i++) {
            s.add(sample(t, 150, d));
            t += 1000;
            d += 4;
        }
        eq("two km splits", 2, s.splits.size());
        eq("split 1 = 250 s", 250, s.splits.get(0)[0]);
        eq("split 2 = 250 s across the pause", 250, s.splits.get(1)[0]);
        eq("split avg hr", 150, s.splits.get(0)[1]);
        eq("pause not counted", 624000, (int) s.activeMs);
        near("avg speed ignores pause", 4.0, s.avgSpeed(), 1e-9);
        near("speed right after pause still 4 m/s", 4.0, s.speed(), 1e-6);
    }

    private static void csv() {
        Training.Sample a = sample(1700000000000L, 142, 1234.5);
        a.lat = 51.5012345;
        a.lon = -0.1234567;
        a.alt = 21.3;
        a.rr = new int[]{420, 431};
        Training.Sample b = Training.Sample.parse(a.toCsv());
        ok("csv roundtrip", b != null && b.t == a.t && b.bpm == 142
                && Math.abs(b.lat - a.lat) < 1e-7 && Math.abs(b.lon - a.lon) < 1e-7
                && Math.abs(b.alt - 21.3) < 1e-9 && Math.abs(b.dist - 1234.5) < 1e-9
                && Arrays.equals(b.rr, a.rr));
        Training.Sample noFix = Training.Sample.parse(sample(5, -1, 0).toCsv());
        ok("csv without fix", noFix != null && !noFix.hasFix() && Double.isNaN(noFix.alt)
                && noFix.rr.length == 0);
        ok("pause marker", Training.Sample.parse("-") == null);
        ok("damaged row", Training.Sample.parse("12,x,,,,0,") == null);
    }

    private static void effort() {
        long[] z = new long[6];
        eq("too short", "Too short to rate", Training.effort(z));
        z[2] = 40 * 60000;
        z[3] = 5 * 60000;
        eq("base", "Base endurance", Training.effort(z));
        z[2] = 20 * 60000;
        z[3] = 30 * 60000;
        z[4] = 9 * 60000;
        eq("tempo", "Tempo \u2014 aerobic fitness", Training.effort(z));
        z[4] = 20 * 60000;
        eq("threshold", "Threshold \u2014 sustained high speed", Training.effort(z));
        z[5] = 4 * 60000;
        eq("maximal", "Maximal \u2014 speed and VO2max", Training.effort(z));
        long[] easy = new long[6];
        easy[1] = 30 * 60000;
        eq("recovery", "Recovery", Training.effort(easy));
        eq("load light", "Light", Training.loadLabel(30));
        eq("load hard", "Hard", Training.loadLabel(130));
    }

    private static void hrv() {
        List<Integer> rr = Arrays.asList(1000, 1010, 990, 1000);
        // diffs 10, -20, 10 -> sqrt((100+400+100)/3) = 14.142
        near("rmssd", 14.142, Training.rmssd(rr), 0.001);
        List<Integer> dirty = Arrays.asList(1000, 1010, 500, 1000, 2500, 990);
        eq("clean drops ectopic and out of range", Arrays.asList(1000, 1010, 990).toString(),
                Training.cleanRr(dirty).toString());
        // a dropped interval (here a 2500 ms strap dropout) must not make its neighbours count
        // as successive beats: the 200 ms step across it used to dominate RMSSD
        List<Integer> gap = Arrays.asList(1000, 1010, 990, 1000, 2500, 1200, 1190);
        near("rmssd old way inflated by the gap", 90.1, Training.rmssd(Training.cleanRr(gap)), 0.5);
        near("rmssd skips pairs across a dropped interval", 13.229, Training.rmssdClean(gap), 0.001);
        near("rmssdClean of clean data equals rmssd", 14.142, Training.rmssdClean(rr), 0.001);
        near("rmssdClean with no pair", 0, Training.rmssdClean(Arrays.asList(1000, 2500, 1000)), 0);
        near("vo2max uth", 15.3 * 190 / 50, Training.vo2maxEstimate(190, 50), 1e-9);
    }

    private static void format() {
        eq("duration short", "05:07", Training.duration(307000));
        eq("duration long", "1:02:03", Training.duration(3723000));
        eq("pace 5:00", "5:00", Training.pace(1000 / 300.0));
        eq("pace stopped", "--:--", Training.pace(0));
        eq("km", "5.14", Training.km(5139));
        eq("announcement", "12 minutes. 2.31 kilometres. Pace 6 05. Heart rate 142, zone 3.",
                Training.announcement(12 * 60000 + 5000, 2310, 1000 / 365.0, true, 142, 3));
        eq("announcement indoor", "1 hour 1 minute. Heart rate 120.",
                Training.announcement(61 * 60000, 0, 0, false, 120, 0));
    }

    private static void export() {
        Training.Profile p = new Training.Profile(190, true, 40, 80);
        Training.Stats st = new Training.Stats(p);
        List<Training.Sample> rows = new ArrayList<>();
        long t0 = 1760000000000L;
        for (int i = 0; i < 3; i++) {
            Training.Sample s = sample(t0 + i * 1000L, 140 + i, i * 3);
            s.lat = 51.5 + i * 0.00001;
            s.lon = -0.12;
            rows.add(s);
            st.add(s);
        }
        rows.add(null);
        rows.add(sample(t0 + 10000, -1, 6));
        String tcx = Export.tcx("Running", t0, rows, st, "felt <good> & easy");
        ok("tcx sport", tcx.contains("<Activity Sport=\"Running\">"));
        ok("tcx id", tcx.contains("<Id>" + Export.iso(t0) + "</Id>"));
        eq("tcx two tracks", 2, count(tcx, "<Track>"));
        eq("tcx trackpoints", 4, count(tcx, "<Trackpoint>"));
        eq("tcx hr only where known", 3, count(tcx, "<HeartRateBpm>"));
        ok("tcx notes escaped", tcx.contains("felt &lt;good&gt; &amp; easy"));
        ok("tcx uses dot decimals", tcx.contains("<LatitudeDegrees>51.5000000<"));
        eq("tcx sport mapping", "Biking", Export.tcxSport("Cycling"));
        eq("tcx other", "Other", Export.tcxSport("Gym"));

        String gpx = Export.gpx("Running", t0, rows);
        eq("gpx points only with fix", 3, count(gpx, "<trkpt "));
        eq("gpx hr", 3, count(gpx, "<gpxtpx:hr>"));
        List<Training.Sample> indoor = new ArrayList<>();
        indoor.add(sample(t0, 120, 0));
        ok("gpx null without fixes", Export.gpx("Gym", t0, indoor) == null);
        eq("iso", "2025-10-09T08:53:20Z", Export.iso(t0));
    }

    private static void profileValues() {
        eq("age estimate", 180, Training.ageHrMax(40));
        eq("auto: never below the estimate", 180, Training.autoHrMax(40, 171));
        eq("auto: rises with a real peak", 188, Training.autoHrMax(40, 188));

        long now = 1792573046431L;
        long day = 86400000L;
        eq("rest: none", 0, Training.restingFromTests(new long[0], new int[0], now));
        eq("rest: single", 54, Training.restingFromTests(new long[]{now - day}, new int[]{54}, now));
        eq("rest: median of latest three", 52, Training.restingFromTests(
                new long[]{now - 5 * day, now - 3 * day, now - 2 * day, now - day},
                new int[]{40, 52, 58, 51}, now));
        eq("rest: two average", 53, Training.restingFromTests(
                new long[]{now - 2 * day, now - day}, new int[]{52, 54}, now));
        eq("rest: older than 90 days ignored", 0, Training.restingFromTests(
                new long[]{now - 100 * day}, new int[]{50}, now));

        // peak: a one-second spike does not count, ten seconds held does
        Training.Stats s = new Training.Stats(new Training.Profile(190, true, 40, 80));
        for (int i = 0; i < 30; i++) {
            s.add(sample(i * 1000L, i == 5 ? 210 : 150, 0));
        }
        eq("spike ignored", 156, s.peakHr);   // 9 x 150 + 210 = 1560 / 10
        for (int i = 30; i < 45; i++) {
            s.add(sample(i * 1000L, 172, 0));
        }
        eq("held peak", 172, s.peakHr);
        Training.Stats gaps = new Training.Stats(new Training.Profile(190, true, 40, 80));
        for (int i = 0; i < 30; i++) {
            gaps.add(sample(i * 1000L, i % 5 == 0 ? -1 : 180, 0));
        }
        eq("needs 10 consecutive readings", 0, gaps.peakHr);
        Training.Stats bogus = new Training.Stats(new Training.Profile(190, true, 40, 80));
        for (int i = 0; i < 20; i++) {
            bogus.add(sample(i * 1000L, 240, 0));
        }
        eq("implausible readings ignored", 0, bogus.peakHr);
    }

    // ------------------------------------------------------------------ helpers

    private static Training.Sample sample(long t, int bpm, double dist) {
        Training.Sample s = new Training.Sample();
        s.t = t;
        s.bpm = bpm;
        s.dist = dist;
        return s;
    }

    private static String sampleLine(boolean walk) {
        Training.Sample x = sample(5000, 120, 10);
        x.walk = walk;
        return x.toCsv();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static void eq(String what, Object expected, Object actual) {
        check(what, expected.equals(actual), "expected " + expected + ", got " + actual);
    }

    private static void near(String what, double expected, double actual, double tolerance) {
        check(what, Math.abs(expected - actual) <= tolerance,
                "expected " + expected + ", got " + actual);
    }

    private static void ok(String what, boolean condition) {
        check(what, condition, "");
    }

    private static void check(String what, boolean condition, String detail) {
        if (!condition) {
            System.out.println("FAIL " + what + (detail.isEmpty() ? "" : ": " + detail));
            failures++;
        } else {
            System.out.println("ok   " + what);
        }
    }
}
