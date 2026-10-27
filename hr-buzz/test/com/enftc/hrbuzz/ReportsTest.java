package com.enftc.hrbuzz;

/** Plain-JVM checks for "Report this answer". Run with ./test.sh */
public final class ReportsTest {

    private static int failures;

    public static void main(String[] args) {
        String one = Reports.add("", 1792573046431L, "chat", Reports.REASONS[0],
                "Claude (Anthropic)", "claude-haiku-5-5", "Is 110 min enough for 12 km?",
                "Yes, easily.");
        eq("one report saved", 1, Reports.count(one));
        String two = Reports.add(one, 1792573146431L, "debrief", Reports.REASONS[2],
                "", "", "", "You ran 5 km.");
        eq("a second is added after the first", 2, Reports.count(two));

        String body = Reports.emailBody(two);
        ok("says what these are and who is sending", body.contains("AI service wrote")
                && body.contains("I am sending them myself"));
        ok("warns to read before sending", body.contains("read what is below before you send"));
        ok("report numbers", body.contains("--- Report 1 ---") && body.contains("--- Report 2 ---"));
        ok("time is written out", body.contains("2026-10-21T08:57:26.431Z"));
        ok("chat and debrief are told apart", body.contains("the coach chat")
                && body.contains("a session debrief"));
        ok("service and model", body.contains("Service: Claude (Anthropic), claude-haiku-5-5"));
        ok("the problem", body.contains("Problem: Wrong or unsafe advice"));
        ok("question and answer", body.contains("What was asked: Is 110 min enough for 12 km?")
                && body.contains("The answer:\nYes, easily."));
        ok("no empty service line when there was no key", body.split("Service:", -1).length == 2);

        String newest = Reports.last(two);
        eq("last keeps only the newest", 1, Reports.count(newest));
        ok("and it is the newest", Reports.emailBody(newest).contains("You ran 5 km.")
                && !Reports.emailBody(newest).contains("Yes, easily."));

        String many = "";
        for (int i = 0; i < Reports.MAX + 7; i++) {
            many = Reports.add(many, i, "chat", "x", "s", "m", "q" + i, "a" + i);
        }
        eq("kept to the limit", Reports.MAX, Reports.count(many));
        ok("oldest dropped first", !Reports.emailBody(many).contains("What was asked: q0\n")
                && Reports.emailBody(many).contains("What was asked: q" + (Reports.MAX + 6)));

        String longText = "x".repeat(Reports.MAX_TEXT + 500);
        String clipped = Reports.add("", 0, "chat", "x", "s", "m", longText, longText);
        ok("long answers are cut", Reports.emailBody(clipped).length() < 2 * Reports.MAX_TEXT + 1500);

        eq("broken saved text starts again", 1, Reports.count(Reports.add("not json", 0, "chat",
                "x", "s", "m", "q", "a")));
        eq("nothing saved counts as zero", 0, Reports.count(null));
        eq("one report subject", "HR Buzz: report on a coach answer", Reports.subject(1));
        eq("several reports subject", "HR Buzz: 3 reports on coach answers", Reports.subject(3));
        eq("the address is the privacy one", "privacy@sourdoughcalculator.uk", Reports.EMAIL);
        eq("four reasons to choose from", 4, Reports.REASONS.length);

        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }

    private static void eq(String what, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        check(what, same, "expected " + expected + ", got " + actual);
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
