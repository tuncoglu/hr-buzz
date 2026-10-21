package com.emre.hrbuzz;

/**
 * Plain-JVM checks for the decode + alarm logic. Run with ./test.sh
 */
public final class HrLogicTest {

    private static int failures;

    public static void main(String[] args) {
        parse();
        alarm();
        thresholdChange();
        stale();
        alertDue();
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }

    private static void parse() {
        // 8-bit value, no flags
        eq("8-bit", 72, HrLogic.parseBpm(new byte[]{0x00, 72}));
        // 16-bit value, little endian
        eq("16-bit", 155, HrLogic.parseBpm(new byte[]{0x01, (byte) 155, 0x00}));
        eq("16-bit high", 200, HrLogic.parseBpm(new byte[]{0x01, (byte) 200, 0x00}));
        // flags set for sensor contact / energy expended / RR, value still 8-bit
        eq("flags ignored", 130, HrLogic.parseBpm(new byte[]{0x1E, (byte) 130, 0, 0, 0, 0, 0, 0}));
        // rejected payloads
        eq("null", -1, HrLogic.parseBpm(null));
        eq("too short", -1, HrLogic.parseBpm(new byte[]{0x00}));
        eq("truncated 16-bit", -1, HrLogic.parseBpm(new byte[]{0x01, 90}));
        eq("zero (no contact)", -1, HrLogic.parseBpm(new byte[]{0x00, 0}));
        eq("implausible", -1, HrLogic.parseBpm(new byte[]{(byte) 0xFF, (byte) 200, 0x01}));
    }

    private static void alarm() {
        HrLogic.Alarm a = new HrLogic.Alarm(155, 3, 5000);
        long t = 1000;

        eq("below: quiet", HrLogic.Alarm.NONE, a.evaluate(140, t));
        eq("at limit: quiet", HrLogic.Alarm.NONE, a.evaluate(155, t));
        eq("above: alarm", HrLogic.Alarm.OVER, a.evaluate(156, t + 10));
        eq("still over, no repeat yet", HrLogic.Alarm.NONE, a.evaluate(160, t + 20));
        eq("repeat after interval", HrLogic.Alarm.OVER, a.evaluate(160, t + 5015));

        // hysteresis: dropping to 153 stays "above" and does not re-alert immediately
        eq("within hysteresis", HrLogic.Alarm.NONE, a.evaluate(153, t + 5100));
        ok("still latched above", a.isAbove());
        eq("recovered below hysteresis", HrLogic.Alarm.BACK, a.evaluate(151, t + 5200));
        ok("latched off", !a.isAbove());

        // a fresh crossing fires immediately again
        eq("crosses again", HrLogic.Alarm.OVER, a.evaluate(170, t + 5300));

        // reset clears the latch
        a.reset();
        ok("reset clears", !a.isAbove());
        eq("quiet after reset", HrLogic.Alarm.NONE, a.evaluate(150, t + 5400));
    }

    private static void thresholdChange() {
        HrLogic.Alarm a = new HrLogic.Alarm(155, 3, 5000);
        long t = 1000;
        eq("over before change", HrLogic.Alarm.OVER, a.evaluate(160, t));
        a.setThreshold(165);
        ok("latch survives threshold change", a.isAbove());
        eq("quiet inside new hysteresis", HrLogic.Alarm.NONE, a.evaluate(163, t + 100));
        eq("recovers against new threshold", HrLogic.Alarm.BACK, a.evaluate(162, t + 200));
        eq("clearBelow", 162, a.clearBelow());
    }

    private static void stale() {
        ok("fresh sample", !HrLogic.isStale(1000, 5000, 10000));
        ok("exactly at timeout", HrLogic.isStale(1000, 11000, 10000));
        ok("long silence", HrLogic.isStale(1000, 60000, 10000));
    }

    private static void alertDue() {
        ok("first alert is due", HrLogic.alertDue(0, 100000, 5000));
        ok("too soon", !HrLogic.alertDue(100000, 103000, 5000));
        ok("due again", HrLogic.alertDue(100000, 105000, 5000));
    }

    private static void eq(String what, int expected, int actual) {
        if (expected != actual) {
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
            failures++;
        } else {
            System.out.println("ok   " + what);
        }
    }

    private static void ok(String what, boolean condition) {
        if (!condition) {
            System.out.println("FAIL " + what);
            failures++;
        } else {
            System.out.println("ok   " + what);
        }
    }
}
