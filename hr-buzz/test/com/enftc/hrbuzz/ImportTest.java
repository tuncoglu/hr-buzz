package com.enftc.hrbuzz;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-JVM checks for TCX import: our own export round-trips, pauses survive (as a new
 * Track or as a time gap), HR and distance are read, junk is refused. Run with ./test.sh
 */
public final class ImportTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        roundTrip();
        polarStyleGap();
        garminTracks();
        rejects();
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }

    private static Training.Sample s(long t, int bpm, double dist, double lat) {
        Training.Sample x = new Training.Sample();
        x.t = t;
        x.bpm = bpm;
        x.dist = dist;
        if (!Double.isNaN(lat)) {
            x.lat = lat;
            x.lon = -0.12;
            x.alt = 20;
        }
        return x;
    }

    private static void roundTrip() throws Exception {
        long t0 = 1792500000000L;
        List<Training.Sample> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(s(t0 + i * 1000L, 130 + i % 10, i * 3.0, 51.5 + i * 0.00003));
        }
        rows.add(null);   // paused for 2 minutes
        for (int i = 0; i < 60; i++) {
            rows.add(s(t0 + 180000 + i * 1000L, 140, 180 + i * 3.0, 51.6 + i * 0.00003));
        }
        Training.Stats st = new Training.Stats(new Training.Profile(180, true, 40, 75));
        for (Training.Sample x : rows) {
            if (x == null) {
                st.pause();
            } else {
                st.add(x);
            }
        }
        String tcx = Export.tcx("Running", t0, rows, st, "windy");
        Import.Result r = Import.tcx(in(tcx));
        eq("sport", "Running", r.sport);
        eq("start", t0 - t0 % 1000, r.start);
        eq("one pause", 1, r.pauses);
        eq("all points", 121, r.samples.size());
        ok("gps", r.gps);
        eq("hr read", 130, r.samples.get(0).bpm);
        eq("notes", "windy", r.notes);
        Training.Stats again = new Training.Stats(new Training.Profile(180, true, 40, 75));
        for (Training.Sample x : r.samples) {
            if (x == null) {
                again.pause();
            } else {
                again.add(x);
            }
        }
        eq("moving time survives (pause excluded)", st.activeMs, again.activeMs);
        near("distance survives", st.distanceM, again.distanceM, 0.11);
        eq("avg hr survives", st.avgHr(), again.avgHr());
    }

    /** Polar-like: one Track, paused time simply missing; HR in a namespaced extension-free form. */
    private static void polarStyleGap() throws Exception {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">"
                + "<Activities><Activity Sport=\"Running\"><Id>2026-10-20T12:27:01.000Z</Id>"
                + "<Lap StartTime=\"2026-10-20T12:27:01.000Z\"><TotalTimeSeconds>40</TotalTimeSeconds>"
                + "<DistanceMeters>100.0</DistanceMeters><Track>");
        for (int i = 0; i < 40; i++) {
            int total = 1 + (i < 20 ? i : i + 60);     // 60 s missing after 20 s
            b.append(String.format("<Trackpoint><Time>2026-10-20T12:%02d:%02d.000Z</Time>",
                    27 + total / 60, total % 60));
            b.append("<DistanceMeters>").append(i * 2.5).append("</DistanceMeters>")
                    .append("<HeartRateBpm><Value>").append(120 + i).append("</Value></HeartRateBpm>")
                    .append("</Trackpoint>");
        }
        b.append("</Track></Lap></Activity></Activities></TrainingCenterDatabase>");
        Import.Result r = Import.tcx(in(b.toString()));
        eq("gap becomes a pause", 1, r.pauses);
        ok("no gps without positions", !r.gps);
        eq("file lap seconds", 40.0, r.fileSeconds);
        Training.Stats st = new Training.Stats(new Training.Profile(180, true, 40, 75));
        for (Training.Sample x : r.samples) {
            if (x == null) {
                st.pause();
            } else {
                st.add(x);
            }
        }
        eq("moving time excludes the gap", 38000L, st.activeMs);
        eq("max hr", 159, st.maxHr);
    }

    private static void garminTracks() throws Exception {
        String tp = "<Trackpoint><Time>%s</Time><DistanceMeters>%s</DistanceMeters>"
                + "<HeartRateBpm><Value>150</Value></HeartRateBpm></Trackpoint>";
        StringBuilder b = new StringBuilder("<TrainingCenterDatabase><Activities>"
                + "<Activity Sport=\"Biking\"><Lap><Track>");
        for (int i = 0; i < 10; i++) {
            b.append(String.format(tp, "2026-10-19T08:00:" + String.format("%02d", i) + "Z", i * 5));
        }
        b.append("</Track><Track>");
        for (int i = 0; i < 10; i++) {
            b.append(String.format(tp, "2026-10-19T08:00:" + String.format("%02d", 12 + i) + "Z",
                    50 + i * 5));
        }
        b.append("</Track></Lap><Lap><Track>");
        for (int i = 0; i < 10; i++) {   // second lap restarts distance at 0
            b.append(String.format(tp, "2026-10-19T08:00:" + String.format("%02d", 22 + i) + "Z",
                    i * 5));
        }
        b.append("</Track></Lap></Activity></Activities></TrainingCenterDatabase>");
        Import.Result r = Import.tcx(in(b.toString()));
        eq("biking -> cycling", "Cycling", r.sport);
        eq("new track = pause, lap without one doesn't double", 2, r.pauses);
        Training.Sample last = r.samples.get(r.samples.size() - 1);
        near("lap distance reset handled", 95 + 45, last.dist, 1e-9);
    }

    private static void rejects() {
        ok("junk", fails("hello"));
        ok("no track", fails("<TrainingCenterDatabase><Activities/></TrainingCenterDatabase>"));
        ok("doctype refused", fails("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM "
                + "\"file:///etc/passwd\">]><TrainingCenterDatabase>&e;</TrainingCenterDatabase>"));
    }

    private static boolean fails(String xml) {
        try {
            Import.tcx(in(xml));
            return false;
        } catch (Import.ImportException e) {
            return true;
        }
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void eq(String what, Object expected, Object actual) {
        check(what, expected.equals(actual), "expected " + expected + ", got " + actual);
    }

    private static void near(String what, double expected, double actual, double tol) {
        check(what, Math.abs(expected - actual) <= tol, "expected " + expected + ", got " + actual);
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
