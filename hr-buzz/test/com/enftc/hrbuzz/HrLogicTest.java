package com.enftc.hrbuzz;

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
        measurement();
        lowLimit();
        confirm();
        lowTiming();
        calm();
        cues();
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

    private static void measurement() {
        // H10 style: flags 0x16 = contact supported + detected + RR present, 8-bit HR
        HrLogic.Measurement m = HrLogic.parse(new byte[]{0x16, 70, 0x00, 0x04, 0x33, 0x03});
        eq("measurement bpm", 70, m.bpm);
        ok("contact detected", m.contactSupported && m.contact);
        eq("two RR intervals", 2, m.rrMs.length);
        eq("RR 1024/1024 s = 1000 ms", 1000, m.rrMs[0]);
        eq("RR 819/1024 s = 800 ms", 800, m.rrMs[1]);

        // contact supported but not detected
        HrLogic.Measurement off = HrLogic.parse(new byte[]{0x04, 60});
        ok("no contact", off.contactSupported && !off.contact);

        // energy expended field sits before RR and must be skipped
        HrLogic.Measurement e = HrLogic.parse(new byte[]{0x18, 90, 0x10, 0x00, 0x00, 0x02});
        eq("RR after energy", 1, e.rrMs.length);
        eq("RR after energy value", 500, e.rrMs[0]);

        // no contact flag at all: assume contact
        ok("contact unsupported", HrLogic.parse(new byte[]{0x00, 72}).contact);
    }

    private static void lowLimit() {
        HrLogic.Alarm a = new HrLogic.Alarm(160, 3, 5000);
        a.setLow(120);
        long t = 1000;
        eq("warm-up below low: quiet (not armed)", HrLogic.Alarm.NONE, a.evaluate(95, t));
        eq("reaches low: arms", HrLogic.Alarm.NONE, a.evaluate(125, t + 100));
        eq("drops under: alarm", HrLogic.Alarm.UNDER, a.evaluate(118, t + 200));
        ok("latched below", a.isBelow());
        eq("still under, no repeat yet", HrLogic.Alarm.NONE, a.evaluate(115, t + 300));
        eq("under repeats", HrLogic.Alarm.UNDER, a.evaluate(115, t + 5300));
        eq("inside hysteresis", HrLogic.Alarm.NONE, a.evaluate(122, t + 5400));
        eq("recovered", HrLogic.Alarm.BACK, a.evaluate(123, t + 5500));
        eq("in band: quiet", HrLogic.Alarm.NONE, a.evaluate(140, t + 5600));
        eq("under again fires at once", HrLogic.Alarm.UNDER, a.evaluate(110, t + 5700));
        eq("jumps straight over the top", HrLogic.Alarm.OVER, a.evaluate(170, t + 5800));
        ok("now above, not below", a.isAbove() && !a.isBelow());

        a.reset();
        eq("reset disarms low", HrLogic.Alarm.NONE, a.evaluate(100, t + 6000));
        a.setLow(0);
        eq("low off", HrLogic.Alarm.NONE, a.evaluate(50, t + 6100));
    }

    private static void confirm() {
        HrLogic.Alarm a = new HrLogic.Alarm(155, 3, 5000, 3);
        a.setLow(120);
        long t = 0;
        eq("arm low", HrLogic.Alarm.NONE, a.evaluate(130, t));
        eq("one spike: quiet", HrLogic.Alarm.NONE, a.evaluate(190, t += 1000));
        eq("two spikes: quiet", HrLogic.Alarm.NONE, a.evaluate(190, t += 1000));
        eq("dip resets the count", HrLogic.Alarm.NONE, a.evaluate(140, t += 1000));
        eq("over 1", HrLogic.Alarm.NONE, a.evaluate(160, t += 1000));
        eq("over 2", HrLogic.Alarm.NONE, a.evaluate(161, t += 1000));
        eq("over 3: alarm", HrLogic.Alarm.OVER, a.evaluate(162, t += 1000));
        eq("repeats need no new confirm", HrLogic.Alarm.OVER, a.evaluate(162, t += 5000));
        eq("back", HrLogic.Alarm.BACK, a.evaluate(140, t += 1000));
        eq("under 1", HrLogic.Alarm.NONE, a.evaluate(110, t += 1000));
        eq("under 2", HrLogic.Alarm.NONE, a.evaluate(110, t += 1000));
        eq("under 3: alarm", HrLogic.Alarm.UNDER, a.evaluate(110, t += 1000));
        a.reset();
        a.setLow(0);
        eq("after reset, a fresh count", HrLogic.Alarm.NONE, a.evaluate(170, t += 1000));
    }

    private static void lowTiming() {
        HrLogic.Alarm a = new HrLogic.Alarm(155, 3, 5000, 3);
        a.setLowTiming(10, 30000);
        a.setLow(100);
        long t = 0;
        eq("arm low", HrLogic.Alarm.NONE, a.evaluate(110, t));
        for (int i = 1; i <= 9; i++) {
            eq("dip " + i + " of 10: quiet", HrLogic.Alarm.NONE, a.evaluate(98, t += 1000));
        }
        eq("dip 10: nudge", HrLogic.Alarm.UNDER, a.evaluate(98, t += 1000));
        eq("no repeat after 5 s", HrLogic.Alarm.NONE, a.evaluate(98, t += 5000));
        eq("repeat after 30 s", HrLogic.Alarm.UNDER, a.evaluate(98, t += 25000));
        eq("high alarm keeps its own pace", HrLogic.Alarm.NONE, a.evaluate(160, t += 1000));
        eq("high 2", HrLogic.Alarm.NONE, a.evaluate(160, t += 1000));
        eq("high 3: alarm", HrLogic.Alarm.OVER, a.evaluate(160, t += 1000));
        eq("high repeats after 5 s", HrLogic.Alarm.OVER, a.evaluate(160, t += 5000));
    }

    /** Feeds {@code bpm} once a second for seconds [from, to], returns the first second per event. */
    private static int[] feed(HrLogic.Alarm a, int from, int to, int bpm, long t0) {
        int[] first = new int[6];
        java.util.Arrays.fill(first, -1);
        for (int s = from; s <= to; s++) {
            int ev = a.evaluate(bpm, t0 + s * 1000L);
            if (first[ev] < 0) {
                first[ev] = s;
            }
        }
        return first;
    }

    private static void calm() {
        long t0 = 1_000_000L;
        // immediate mode is what it always was: the alarm on the third reading over
        HrLogic.Alarm plain = new HrLogic.Alarm(130, 3, 5000, 3);
        eq("immediate: third reading", 2, feed(plain, 0, 10, 135, t0)[HrLogic.Alarm.OVER]);

        HrLogic.Alarm a = new HrLogic.Alarm(130, 3, 5000, 3);
        a.setCalm(true, 10000, 30000, 10, 10000);
        int[] first = feed(a, 0, 29, 135, t0);
        eq("calm: a nudge after 10 s over", 10, first[HrLogic.Alarm.NUDGE]);
        eq("calm: no alarm yet at 29 s", -1, first[HrLogic.Alarm.OVER]);
        int[] more = feed(a, 30, 45, 135, t0);
        eq("calm: the alarm after 30 s", 30, more[HrLogic.Alarm.OVER]);
        eq("calm: the nudge is given once", -1, more[HrLogic.Alarm.NUDGE]);
        ok("calm: repeats every 10 s, not 5", a.evaluate(135, t0 + 44_000L) == HrLogic.Alarm.NONE
                && a.evaluate(135, t0 + 40_000L) == HrLogic.Alarm.NONE);
        eq("calm: back once 3 under the limit", HrLogic.Alarm.BACK, a.evaluate(126, t0 + 50_000L));

        // far over the limit is not worth waiting for
        HrLogic.Alarm far = new HrLogic.Alarm(130, 3, 5000, 3);
        far.setCalm(true, 10000, 30000, 10, 10000);
        eq("calm: 15 over sounds on the third reading", 2, feed(far, 0, 10, 145, t0)[HrLogic.Alarm.OVER]);

        // a short dip back under does not restart the timers
        HrLogic.Alarm dip = new HrLogic.Alarm(130, 3, 5000, 3);
        dip.setCalm(true, 10000, 30000, 10, 10000);
        feed(dip, 0, 7, 135, t0);
        feed(dip, 8, 9, 128, t0);
        eq("calm: a 2 s dip counts as still over", 12, feed(dip, 10, 20, 135, t0)[HrLogic.Alarm.NUDGE]);

        // really coming back down starts it over
        HrLogic.Alarm back = new HrLogic.Alarm(130, 3, 5000, 3);
        back.setCalm(true, 10000, 30000, 10, 10000);
        feed(back, 0, 5, 135, t0);
        feed(back, 6, 14, 120, t0);
        eq("calm: after 9 s under the clock starts again", 25, feed(back, 15, 40, 135, t0)[HrLogic.Alarm.NUDGE]);

        // switching it off puts the old behaviour back
        HrLogic.Alarm off = new HrLogic.Alarm(130, 3, 5000, 3);
        off.setCalm(true, 10000, 30000, 10, 10000);
        off.setCalm(false, 0, 0, 0, 0);
        eq("calm off: third reading again", 2, feed(off, 0, 10, 135, t0)[HrLogic.Alarm.OVER]);
    }

    private static void cues() {
        long[] one = HrLogic.stepPattern(1);
        eq("step 1: wait, long, gap, one short", 4, one.length);
        eq("the long buzz comes first", 350, (int) one[1]);
        eq("one short buzz", 90, (int) one[3]);
        long[] three = HrLogic.stepPattern(3);
        eq("step 3: three short buzzes", 8, three.length);
        eq("they end on a buzz", 90, (int) three[three.length - 1]);
        eq("short ones are told apart by a gap", 130, (int) three[4]);
        eq("capped at five", HrLogic.stepPattern(5).length, HrLogic.stepPattern(9).length);
        eq("step 0 is step 1", one.length, HrLogic.stepPattern(0).length);
        ok("a pattern always starts with no wait", three[0] == 0 && HrLogic.DONE_PATTERN[0] == 0
                && HrLogic.TICK_PATTERN[0] == 0);

        // auto-pause: 8 slow seconds in a row pauses, 3 quick ones resume
        HrLogic.AutoPause ap = new HrLogic.AutoPause();
        int at = -1;
        for (int i = 0; i < 12 && at < 0; i++) {
            if (ap.onSpeed(false, 0.2) == HrLogic.AutoPause.PAUSE) {
                at = i;
            }
        }
        eq("pauses on the 8th still second", 7, at);
        eq("a fix without speed is ignored", HrLogic.AutoPause.NONE, ap.onSpeed(true, -1));
        eq("one quick second is not enough", HrLogic.AutoPause.NONE, ap.onSpeed(true, 1.6));
        eq("a slow one starts the count again", HrLogic.AutoPause.NONE, ap.onSpeed(true, 0.4));
        ap.onSpeed(true, 1.6);
        ap.onSpeed(true, 1.6);
        eq("the third in a row resumes", HrLogic.AutoPause.RESUME, ap.onSpeed(true, 1.6));
        HrLogic.AutoPause walk = new HrLogic.AutoPause();
        int fired = 0;
        for (int i = 0; i < 60; i++) {
            fired += walk.onSpeed(false, 0.9) == HrLogic.AutoPause.PAUSE ? 1 : 0;
        }
        eq("a slow stroll (0.9 m/s) never pauses", 0, fired);
        HrLogic.AutoPause stop = new HrLogic.AutoPause();
        for (int i = 0; i < 6; i++) {
            stop.onSpeed(false, 0.1);
        }
        stop.onSpeed(false, 1.4);       // a step forward
        int again = -1;
        for (int i = 0; i < 12 && again < 0; i++) {
            if (stop.onSpeed(false, 0.1) == HrLogic.AutoPause.PAUSE) {
                again = i;
            }
        }
        eq("a moving second restarts the still count", 7, again);
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
