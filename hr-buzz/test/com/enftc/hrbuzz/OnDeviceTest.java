package com.enftc.hrbuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM checks for the on-device coach layer with a fake engine: asking, retrying a broken
 * answer, the error wording and what the engine is given. Run with ./test.sh
 */
public final class OnDeviceTest {

    private static int failures;

    /** An engine that answers from a script: each entry is text to return or a Failure code. */
    private static final class Fake implements OnDevice.Engine {
        final List<Object> script = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        final List<Integer> tokens = new ArrayList<>();
        int calls;

        @Override
        public int status() {
            return OnDevice.AVAILABLE;
        }

        @Override
        public void download() {
        }

        @Override
        public String generate(String system, String user, int maxTokens) throws OnDevice.Failure {
            systems.add(system);
            tokens.add(maxTokens);
            Object next = script.get(Math.min(calls++, script.size() - 1));
            if (next instanceof Integer) {
                throw new OnDevice.Failure((Integer) next, "library says " + next);
            }
            return (String) next;
        }

        @Override
        public String modelName() {
            return "nano-v4-full";
        }
    }

    public static void main(String[] args) throws Exception {
        asking();
        errors();
        absent();
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }

    private static void asking() throws Exception {
        Fake e = new Fake();
        e.script.add("{\"reply\":\"hi\",\"plan\":null}");
        Llm.Request r = new Llm.Request("SYS", "USER", 32000, true);
        Map<String, Object> m = OnDevice.ask(e, r);
        eq("plain JSON parsed", "hi", m.get("reply"));
        ok("JSON-only instruction appended to the system prompt",
                e.systems.get(0).startsWith("SYS") && e.systems.get(0).contains("single JSON object"));
        eq("32000 tokens clamped", OnDevice.MAX_OUTPUT_TOKENS, e.tokens.get(0));

        e = new Fake();
        e.script.add("```json\n{\"a\":1}\n```");
        eq("a code fence is stripped", 1.0, OnDevice.ask(e, r).get("a"));

        e = new Fake();
        e.script.add("Sure! Here you go: {\"a\":2} Hope that helps.");
        eq("prose around the object is cut", 2.0, OnDevice.ask(e, r).get("a"));

        e = new Fake();
        e.script.add("not json at all");
        e.script.add("{\"a\":3}");
        eq("a broken answer is retried once", 3.0, OnDevice.ask(e, r).get("a"));
        eq("exactly two calls", 2, e.calls);

        e = new Fake();
        e.script.add("");
        e.script.add("still {broken");
        try {
            OnDevice.ask(e, r);
            ok("two bad answers fail", false);
        } catch (Llm.ApiException ex) {
            ok("two bad answers fail with a readable message", ex.getMessage().contains("could not be read"));
            eq("two calls, no more", 2, e.calls);
        }

        e = new Fake();
        e.script.add("{\"a\":1}");
        Llm.Request small = new Llm.Request("S", "U", 6000, false);
        OnDevice.ask(e, small);
        eq("a smaller budget is kept", 6000, e.tokens.get(0));

        String shown = OnDevice.describe(r);
        ok("the log shows the system prompt and the user text",
                shown.contains("SYS") && shown.contains("USER") && shown.contains("Gemma 4"));
    }

    private static void errors() {
        Llm.ApiException bg = OnDevice.toApi(new OnDevice.Failure(30, "x"));
        eq("background use is its own code", Llm.NEEDS_FOREGROUND, bg.code);
        ok("and says to open the app", bg.getMessage().contains("open"));
        eq("busy maps to a retry code", 429, OnDevice.toApi(new OnDevice.Failure(9, "x")).code);
        eq("battery quota maps to a retry code", 429,
                OnDevice.toApi(new OnDevice.Failure(27, "x")).code);
        ok("update needed says AICore",
                OnDevice.toApi(new OnDevice.Failure(604, "x")).getMessage().contains("AICore"));
        ok("no storage says storage",
                OnDevice.toApi(new OnDevice.Failure(501, "x")).getMessage().contains("storage"));
        ok("an unknown code is shown", OnDevice.toApi(new OnDevice.Failure(77, "x"))
                .getMessage().contains("77"));
        ok("none of them look like a rejected key", OnDevice.toApi(new OnDevice.Failure(30, "x")).code != 401
                && OnDevice.toApi(new OnDevice.Failure(9, "x")).code != 401);

        Fake e = new Fake();
        e.script.add(30);
        try {
            OnDevice.ask(e, new Llm.Request("S", "U", 100));
            ok("a failure surfaces", false);
        } catch (Llm.ApiException ex) {
            eq("the failure becomes the foreground code", Llm.NEEDS_FOREGROUND, ex.code);
            eq("a refusal is not retried", 1, e.calls);
        }

        ok("ready line", OnDevice.statusLine(OnDevice.AVAILABLE).contains("ready"));
        ok("download line", OnDevice.statusLine(OnDevice.DOWNLOADABLE).contains("downloaded"));
        ok("unavailable line names the preview", OnDevice.statusLine(OnDevice.UNAVAILABLE)
                .contains("developer preview"));
    }

    private static void absent() {
        OnDevice.use(null);
        ok("a build without the engine reports it", !OnDevice.compiled());
        OnDevice.use(new Fake());
        ok("a build with one does too", OnDevice.compiled());
        OnDevice.use(null);
    }

    private static void eq(String what, Object expected, Object actual) {
        check(what, expected == null ? actual == null : expected.equals(actual),
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
