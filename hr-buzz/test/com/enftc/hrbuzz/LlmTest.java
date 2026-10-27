package com.enftc.hrbuzz;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plain-JVM checks for the AI service layer: recognising a key, each service's request and
 * reply shapes, choosing a model, and the client against local stub servers. Run with ./test.sh
 */
public final class LlmTest {

    private static int failures;

    private static final String ANTHROPIC_KEY = "sk-ant-api03-" + "x".repeat(40);
    private static final String GEMINI_KEY = "AIzaSy" + "a".repeat(33);
    private static final String OPENAI_KEY = "sk-proj-" + "A1b2".repeat(12);
    private static final String DEEPSEEK_KEY = "sk-" + "0123456789abcdef".repeat(2);

    public static void main(String[] args) throws Exception {
        recognise();
        bodies();
        headersAndUrls();
        replies();
        models();
        errors();
        client();
        connect();
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all checks passed");
    }

    // ------------------------------------------------------------------ keys

    private static void recognise() {
        eq("claude key", Arrays.asList(Provider.ANTHROPIC), Provider.identify(ANTHROPIC_KEY));
        eq("gemini key", Arrays.asList(Provider.GEMINI), Provider.identify(GEMINI_KEY));
        eq("openai project key: openai first", Arrays.asList(Provider.OPENAI, Provider.DEEPSEEK),
                Provider.identify(OPENAI_KEY));
        eq("legacy openai key", Provider.OPENAI, Provider.identify("sk-" + "Ab3".repeat(16)).get(0));
        eq("deepseek key: 32 hex digits, deepseek first",
                Arrays.asList(Provider.DEEPSEEK, Provider.OPENAI), Provider.identify(DEEPSEEK_KEY));
        eq("whitespace around a key is ignored", Provider.ANTHROPIC,
                Provider.identify("  " + ANTHROPIC_KEY + "\n").get(0));
        ok("garbage is not a key", Provider.identify("hello").isEmpty());
        ok("a short AIza is not a key", Provider.identify("AIza123").isEmpty());
        ok("nothing", Provider.identify(null).isEmpty() && Provider.identify("").isEmpty());
        eq("a copied key alone", ANTHROPIC_KEY, Provider.keyIn(ANTHROPIC_KEY));
        eq("with a newline from the copy", GEMINI_KEY, Provider.keyIn(GEMINI_KEY + "\n"));
        eq("inside a sentence", OPENAI_KEY, Provider.keyIn("Here is your key: " + OPENAI_KEY + " keep it safe."));
        eq("in quotes", DEEPSEEK_KEY, Provider.keyIn("\"" + DEEPSEEK_KEY + "\""));
        eq("in backticks with a full stop", ANTHROPIC_KEY, Provider.keyIn("`" + ANTHROPIC_KEY + "`."));
        ok("ordinary text holds no key", Provider.keyIn("buy milk and call the dentist") == null);
        ok("a web address is not a key", Provider.keyIn("https://platform.openai.com/api-keys") == null);
        ok("a short sk- word is not a key", Provider.keyIn("sk-short") == null);
        ok("nothing copied", Provider.keyIn("") == null && Provider.keyIn(null) == null);
        ok("a huge paste is ignored", Provider.keyIn("x ".repeat(2000)) == null);
        eq("saved ids come back", Provider.GEMINI, Provider.byId("gemini"));
        eq("keys saved before the choice existed are deepseek's", Provider.DEEPSEEK,
                Provider.byId(null));
    }

    // ------------------------------------------------------------------ requests

    private static void bodies() throws Exception {
        // OpenAI: reasoning models take max_completion_tokens and reasoning_effort
        Map<String, Object> o = Json.parseObject(
                Provider.OPENAI.body("gpt-5-mini", "sys", "usr", 32000, true, 0));
        eq("openai model", "gpt-5-mini", o.get("model"));
        eq("openai json mode", "json_object", Json.obj(o.get("response_format")).get("type"));
        eq("openai thinks medium", "medium", o.get("reasoning_effort"));
        eq("openai token cap", 16000.0, o.get("max_completion_tokens"));
        ok("openai never sends max_tokens", !o.containsKey("max_tokens"));
        eq("openai fast mode", "low", Json.parseObject(
                Provider.OPENAI.body("m", "s", "u", 100, false, 0)).get("reasoning_effort"));
        Map<String, Object> o1 = Json.parseObject(Provider.OPENAI.body("m", "s", "u", 100, true, 1));
        ok("openai level 1 drops reasoning_effort, keeps JSON mode",
                !o1.containsKey("reasoning_effort") && o1.containsKey("response_format"));
        Map<String, Object> o2 = Json.parseObject(Provider.OPENAI.body("m", "s", "u", 100, true, 2));
        ok("openai level 2 is the plain request",
                !o2.containsKey("reasoning_effort") && !o2.containsKey("response_format"));

        // Claude: system prompt asks for JSON, thinking and effort, no sampling settings
        Map<String, Object> a = Json.parseObject(
                Provider.ANTHROPIC.body("claude-sonnet-5-5", "sys", "usr", 32000, true, 0));
        eq("claude model", "claude-sonnet-5-5", a.get("model"));
        eq("claude token cap", 16000.0, a.get("max_tokens"));
        ok("claude system asks for JSON only",
                Json.str(a, "system", "").startsWith("sys") && Json.str(a, "system", "").contains("JSON"));
        eq("claude adaptive thinking", "adaptive", Json.obj(a.get("thinking")).get("type"));
        eq("claude effort medium", "medium", Json.obj(a.get("output_config")).get("effort"));
        Map<String, Object> msg = Json.obj(Json.list(a.get("messages")).get(0));
        eq("claude user turn", "usr", msg.get("content"));
        ok("claude: no temperature, no prefill",
                !a.containsKey("temperature") && Json.list(a.get("messages")).size() == 1);
        Map<String, Object> aq = Json.parseObject(
                Provider.ANTHROPIC.body("m", "s", "u", 100, false, 0));
        ok("claude fast mode sends no thinking field (the newest models refuse disabled)",
                !aq.containsKey("thinking"));
        eq("claude fast mode: low effort", "low", Json.obj(aq.get("output_config")).get("effort"));
        Map<String, Object> a1 = Json.parseObject(Provider.ANTHROPIC.body("m", "s", "u", 100, true, 1));
        ok("claude level 1 drops thinking, keeps effort",
                !a1.containsKey("thinking") && a1.containsKey("output_config"));
        Map<String, Object> a2 = Json.parseObject(Provider.ANTHROPIC.body("m", "s", "u", 100, true, 2));
        ok("claude level 2 sends neither", !a2.containsKey("thinking") && !a2.containsKey("output_config"));

        // Gemini: system instruction, contents, generation config
        Map<String, Object> g = Json.parseObject(
                Provider.GEMINI.body("gemini-2.5-flash", "sys", "usr", 32000, true, 0));
        eq("gemini system text", "sys", Json.obj(Json.list(
                Json.obj(g.get("systemInstruction")).get("parts")).get(0)).get("text"));
        Map<String, Object> cfg = Json.obj(g.get("generationConfig"));
        eq("gemini json mode", "application/json", cfg.get("responseMimeType"));
        eq("gemini token cap", 30000.0, cfg.get("maxOutputTokens"));
        eq("gemini thinks dynamically", -1.0, Json.obj(cfg.get("thinkingConfig")).get("thinkingBudget"));
        eq("gemini fast mode: thinking budget 0", 0.0, Json.obj(Json.obj(Json.parseObject(
                Provider.GEMINI.body("m", "s", "u", 100, false, 0)).get("generationConfig"))
                .get("thinkingConfig")).get("thinkingBudget"));
        ok("gemini level 2 drops JSON mode and thinking", !Json.obj(Json.parseObject(
                Provider.GEMINI.body("m", "s", "u", 100, true, 2)).get("generationConfig"))
                .containsKey("responseMimeType"));

        // DeepSeek is exactly what it was before the choice existed
        Map<String, Object> d = Json.parseObject(
                Provider.DEEPSEEK.body("deepseek-flash", "s", "u", 100, true, 0));
        eq("deepseek thinking", "enabled", Json.obj(d.get("thinking")).get("type"));
        eq("deepseek effort", "high", d.get("reasoning_effort"));
        eq("deepseek max_tokens", 100.0, d.get("max_tokens"));

        for (Provider p : Provider.values()) {
            for (String key : new String[]{ANTHROPIC_KEY, GEMINI_KEY, OPENAI_KEY, DEEPSEEK_KEY}) {
                ok(p.id + ": a key is never in the body",
                        !p.body("m", "s", "u", 100, true, 0).contains(key));
            }
        }
    }

    private static void headersAndUrls() {
        eq("openai auth", "Bearer k", Provider.OPENAI.headers("k").get("Authorization"));
        eq("deepseek auth", "Bearer k", Provider.DEEPSEEK.headers("k").get("Authorization"));
        eq("claude key header", "k", Provider.ANTHROPIC.headers("k").get("x-api-key"));
        eq("claude version header", "2023-06-01",
                Provider.ANTHROPIC.headers("k").get("anthropic-version"));
        ok("claude has no bearer", !Provider.ANTHROPIC.headers("k").containsKey("Authorization"));
        eq("gemini key header", "k", Provider.GEMINI.headers("k").get("x-goog-api-key"));
        eq("claude url", "https://api.anthropic.com/v1/messages",
                Provider.ANTHROPIC.chatUrl(Provider.ANTHROPIC.baseUrl, "m"));
        eq("openai url", "https://api.openai.com/v1/chat/completions",
                Provider.OPENAI.chatUrl(Provider.OPENAI.baseUrl, "m"));
        eq("deepseek url", "https://api.deepseek.com/chat/completions",
                Provider.DEEPSEEK.chatUrl(Provider.DEEPSEEK.baseUrl, "m"));
        eq("gemini url carries the model but not the key",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent",
                Provider.GEMINI.chatUrl(Provider.GEMINI.baseUrl, "gemini-x"));
        eq("host for the privacy line", "api.openai.com", Provider.OPENAI.host());
        eq("short names", "Claude", Provider.ANTHROPIC.shortName());
    }

    // ------------------------------------------------------------------- answers

    private static void replies() throws Exception {
        eq("openai text", "{\"a\":1}", Provider.OPENAI.reply(Json.parseObject(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"a\\\":1}\"}}]}")));
        eq("claude text skips thinking blocks", "{\"a\":1}", Provider.ANTHROPIC.reply(Json.parseObject(
                "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hmm\"},"
                        + "{\"type\":\"text\",\"text\":\"{\\\"a\\\":\"},"
                        + "{\"type\":\"text\",\"text\":\"1}\"}]}")));
        eq("gemini text skips thoughts", "{\"a\":1}", Provider.GEMINI.reply(Json.parseObject(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"thinking...\",\"thought\":true},"
                        + "{\"text\":\"{\\\"a\\\":1}\"}]}}]}")));
        ok("no choices: no text", Provider.OPENAI.reply(Json.parseObject("{\"choices\":[]}")) == null);
        ok("no content: no text", Provider.ANTHROPIC.reply(Json.parseObject("{\"content\":[]}")) == null);
        ok("no candidates: no text", Provider.GEMINI.reply(Json.parseObject("{}")) == null);

        ok("claude refusal", Provider.ANTHROPIC.refusal(
                Json.parseObject("{\"stop_reason\":\"refusal\",\"content\":[]}")) != null);
        ok("claude normal stop is no refusal", Provider.ANTHROPIC.refusal(
                Json.parseObject("{\"stop_reason\":\"end_turn\"}")) == null);
        ok("gemini blocked prompt", Provider.GEMINI.refusal(
                Json.parseObject("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")) != null);
        ok("openai refusal", Provider.OPENAI.refusal(Json.parseObject(
                "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"no\"}}]}")) != null);

        eq("json around prose is cut out", "{\"a\":1}",
                Llm.jsonOf("Here is the plan:\n{\"a\":1}\nHope that helps"));
        eq("fenced json", "{\"a\":1}", Llm.jsonOf("```json\n{\"a\":1}\n```"));
        eq("plain json untouched", "{\"a\":{\"b\":2}}", Llm.jsonOf("{\"a\":{\"b\":2}}"));
    }

    // -------------------------------------------------------------------- models

    private static void models() throws Exception {
        eq("openai: the newest mini, not the dated copy, not the full model", "gpt-5.2-mini",
                Provider.OPENAI.pick(Arrays.asList("gpt-4o", "gpt-5-mini", "gpt-5.2-mini",
                        "gpt-5.2", "gpt-5-mini-2025-08-07", "gpt-5.2-mini-search", "text-embedding-3-small",
                        "gpt-5.2-codex")));
        eq("openai: a newer luna beats an older mini", "gpt-6-luna",
                Provider.OPENAI.pick(Arrays.asList("gpt-5.4-mini", "gpt-6-luna", "gpt-6-sol",
                        "gpt-6-astra", "gpt-5.6-luna")));
        eq("openai: 5.10 is newer than 5.9", "gpt-5.10-mini",
                Provider.OPENAI.pick(Arrays.asList("gpt-5.9-mini", "gpt-5.10-mini")));
        eq("openai: no mini, the plain model", "gpt-4.1",
                Provider.OPENAI.pick(Arrays.asList("gpt-4.1", "whisper-1")));
        eq("claude: the newest haiku", "claude-haiku-5-5",
                Provider.ANTHROPIC.pick(Arrays.asList("claude-haiku-4-5", "claude-haiku-5-5",
                        "claude-sonnet-5", "claude-sonnet-5-5", "claude-opus-5-5",
                        "claude-sonnet-4-6", "claude-fable-5-1")));
        eq("claude: no haiku, the newest sonnet", "claude-sonnet-5-5",
                Provider.ANTHROPIC.pick(Arrays.asList("claude-sonnet-5", "claude-sonnet-5-5",
                        "claude-opus-5-5", "claude-sonnet-4-5-20250929", "claude-sonnet-4-6")));
        eq("claude: no haiku or sonnet, an opus", "claude-opus-4-8",
                Provider.ANTHROPIC.pick(Arrays.asList("claude-opus-4-7", "claude-opus-4-8")));
        eq("claude: only a dated model is not guessed at", null,
                Provider.ANTHROPIC.pick(Arrays.asList("claude-sonnet-4-5-20250929")));
        eq("gemini: the newest flash", "gemini-3-flash",
                Provider.GEMINI.pick(Arrays.asList("gemini-2.5-flash", "gemini-3-flash",
                        "gemini-3-pro", "gemini-2.5-flash-lite", "gemini-3-flash-preview")));
        eq("gemini: no flash, a pro", "gemini-2.5-pro",
                Provider.GEMINI.pick(Arrays.asList("gemini-2.5-pro", "gemini-embedding-001")));
        eq("deepseek keeps its own model", "deepseek-flash",
                Provider.DEEPSEEK.pick(Arrays.asList("deepseek-chat")));
        eq("nothing listed", null, Provider.OPENAI.pick(Arrays.asList()));

        List<String> ids = Provider.GEMINI.modelIds(Json.parseObject("{\"models\":["
                + "{\"name\":\"models/gemini-3-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]}]}"));
        eq("gemini lists only models that can chat, without the prefix",
                Arrays.asList("gemini-3-flash"), ids);
        eq("openai and claude list data[].id", Arrays.asList("a", "b"),
                Provider.OPENAI.modelIds(Json.parseObject("{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}")));
    }

    // -------------------------------------------------------------------- errors

    private static void errors() {
        String badKey = "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
                + "\"status\":\"INVALID_ARGUMENT\"}}";
        eq("gemini wrong key arrives as 400 but means 401", 401, Provider.GEMINI.normalizeCode(400, badKey));
        eq("another 400 stays 400", 400, Provider.GEMINI.normalizeCode(400,
                "{\"error\":{\"message\":\"Unknown name thinkingConfig\"}}"));
        ok("401 wording", Provider.ANTHROPIC.explain(401, "").contains("Claude rejected the API key"));
        ok("claude out of credit is a 400, said plainly", Provider.ANTHROPIC.explain(400,
                "{\"error\":{\"message\":\"Your credit balance is too low to access the API\"}}")
                .contains("out of credit"));
        ok("openai quota is a 429, said as credit", Provider.OPENAI.explain(429,
                "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"You exceeded your current quota\"}}")
                .contains("out of credit"));
        ok("plain 429 is busy", Provider.OPENAI.explain(429, "{}").contains("busy"));
        ok("404 names the model problem", Provider.GEMINI.explain(404, "{}").contains("model"));
        ok("529 overloaded is the service's trouble", Provider.ANTHROPIC.explain(529, "{}")
                .contains("trouble"));
        ok("a refused setting is retried with fewer", Provider.OPENAI.mayBeOptionalSetting(400,
                "{\"error\":{\"message\":\"Unsupported parameter: 'reasoning_effort'\"}}"));
        ok("a credit problem is not a setting", !Provider.ANTHROPIC.mayBeOptionalSetting(400,
                "{\"error\":{\"message\":\"Your credit balance is too low\"}}"));
        ok("a 401 is not a setting", !Provider.OPENAI.mayBeOptionalSetting(401, "{}"));
    }

    // ------------------------------------------------------------- stub servers

    private static void client() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        AtomicReference<String> sent = new AtomicReference<>();
        AtomicInteger picky = new AtomicInteger();
        AtomicInteger credit = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/v1/chat/completions", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            sent.set(body(ex));
            reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"reply\\\":\\\"openai\\\"}\"}}]}");
        });
        server.createContext("/claude/v1/messages", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("x-api-key"));
            version.set(ex.getRequestHeaders().getFirst("anthropic-version"));
            sent.set(body(ex));
            reply(ex, 200, "{\"stop_reason\":\"end_turn\",\"content\":["
                    + "{\"type\":\"thinking\",\"thinking\":\"\"},"
                    + "{\"type\":\"text\",\"text\":\"Sure, here you go:\\n{\\\"reply\\\":\\\"claude\\\"}\"}]}");
        });
        server.createContext("/gemini/v1beta/models/gemini-x:generateContent", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            sent.set(body(ex));
            ok("gemini key is not in the url", !ex.getRequestURI().toString().contains("key="));
            reply(ex, 200, "{\"candidates\":[{\"content\":{\"parts\":["
                    + "{\"text\":\"{\\\"reply\\\":\\\"gemini\\\"}\"}]}}]}");
        });
        // a model that refuses reasoning_effort and then response_format
        server.createContext("/picky/v1/chat/completions", ex -> {
            String b = body(ex);
            picky.incrementAndGet();
            sent.set(b);
            if (b.contains("reasoning_effort")) {
                reply(ex, 400, "{\"error\":{\"message\":\"Unsupported parameter: 'reasoning_effort' "
                        + "is not supported with this model.\"}}");
            } else if (b.contains("response_format")) {
                reply(ex, 400, "{\"error\":{\"message\":\"response_format is not supported\"}}");
            } else {
                reply(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"reply\\\":\\\"plain\\\"}\"}}]}");
            }
        });
        server.createContext("/broke/v1/messages", ex -> {
            credit.incrementAndGet();
            reply(ex, 400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                    + "\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}");
        });
        server.createContext("/refusing/v1/messages", ex ->
                reply(ex, 200, "{\"stop_reason\":\"refusal\",\"content\":[]}"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Llm.Request r = new Llm.Request("system JSON", "user", 500);
        try {
            Map<String, Object> o = new Llm(Provider.OPENAI, "gpt-5-mini", base + "/openai", 5000)
                    .ask(OPENAI_KEY, r);
            eq("openai answer", "openai", o.get("reply"));
            eq("openai bearer", "Bearer " + OPENAI_KEY, auth.get());
            ok("openai key not in body", !sent.get().contains(OPENAI_KEY));

            Map<String, Object> c = new Llm(Provider.ANTHROPIC, "claude-sonnet-5-5", base + "/claude", 5000)
                    .ask(ANTHROPIC_KEY, r);
            eq("claude answer, found inside a sentence", "claude", c.get("reply"));
            eq("claude key header", ANTHROPIC_KEY, auth.get());
            eq("claude version header", "2023-06-01", version.get());
            ok("claude key not in body", !sent.get().contains(ANTHROPIC_KEY));

            Map<String, Object> g = new Llm(Provider.GEMINI, "gemini-x", base + "/gemini", 5000)
                    .ask(GEMINI_KEY, r);
            eq("gemini answer", "gemini", g.get("reply"));
            eq("gemini key header", GEMINI_KEY, auth.get());
            ok("gemini key not in body", !sent.get().contains(GEMINI_KEY));

            Llm picky3 = new Llm(Provider.OPENAI, "o-picky", base + "/picky", 5000);
            Map<String, Object> p = picky3.ask(OPENAI_KEY, r);
            eq("a model that refuses settings still answers", "plain", p.get("reply"));
            eq("it took three requests: full, without effort, plain", 3, picky.get());
            ok("what is shown is the request that worked",
                    !picky3.lastBody.contains("reasoning_effort") && !picky3.lastBody.contains("response_format"));

            try {
                new Llm(Provider.ANTHROPIC, "m", base + "/broke", 5000).ask(ANTHROPIC_KEY, r);
                ok("credit problem throws", false);
            } catch (Llm.ApiException e) {
                ok("credit problem is said plainly", e.getMessage().contains("out of credit"));
                eq("and is asked once, not retried with fewer settings", 1, credit.get());
            }
            try {
                new Llm(Provider.ANTHROPIC, "m", base + "/refusing", 5000).ask(ANTHROPIC_KEY, r);
                ok("refusal throws", false);
            } catch (Llm.ApiException e) {
                ok("refusal is reported as the service declining", e.getMessage().contains("Claude declined"));
            }
        } finally {
            server.stop(0);
        }
    }

    private static void connect() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok/v1/models", ex -> {
            ok("claude is asked with its own headers", ex.getRequestHeaders().getFirst("x-api-key") != null);
            reply(ex, 200, "{\"data\":[{\"id\":\"claude-haiku-5-5\"},{\"id\":\"claude-sonnet-5-5\"}]}");
        });
        // an sk- key that is not OpenAI's: OpenAI says 401, DeepSeek knows it
        server.createContext("/mixed/v1/models", ex -> {
            hits.incrementAndGet();
            reply(ex, 401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}");
        });
        server.createContext("/mixed/models", ex ->
                reply(ex, 200, "{\"data\":[{\"id\":\"deepseek-flash\"}]}"));
        server.createContext("/nobody/v1beta/models", ex -> reply(ex, 400,
                "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\"}}"));
        server.createContext("/nobody/v1/models", ex -> reply(ex, 401, "{\"error\":{\"message\":\"no\"}}"));
        server.createContext("/nobody/models", ex -> reply(ex, 401, "{\"error\":{\"message\":\"no\"}}"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            Llm.Connection c = Llm.connect(ANTHROPIC_KEY, base + "/ok");
            eq("claude key: service", Provider.ANTHROPIC, c.provider);
            eq("claude key: the small model it can reach", "claude-haiku-5-5", c.model);

            Llm.Connection m = Llm.connect(OPENAI_KEY, base + "/mixed");
            eq("an sk- key openai refuses is tried as deepseek's", Provider.DEEPSEEK, m.provider);
            eq("openai was asked first", 1, hits.get());

            try {
                Llm.connect(GEMINI_KEY, base + "/nobody");
                ok("a refused gemini key throws", false);
            } catch (Llm.ApiException e) {
                eq("a refused key is a 401, though gemini says 400", 401, e.code);
            }
            try {
                Llm.connect(OPENAI_KEY, base + "/nobody");
                ok("a key no service accepts throws", false);
            } catch (Llm.ApiException e) {
                eq("rejected by every candidate: 401", 401, e.code);
                ok("and says which were tried", e.getMessage().contains("OpenAI")
                        && e.getMessage().contains("DeepSeek"));
            }
            try {
                Llm.connect("not-a-key", base);
                ok("an unrecognised key throws", false);
            } catch (Llm.ApiException e) {
                eq("unrecognised: code 0", 0, e.code);
                ok("and lists what is understood", e.getMessage().contains("Gemini"));
            }
            try {
                Llm.connect(ANTHROPIC_KEY, "http://127.0.0.1:1");
                ok("offline throws", false);
            } catch (Llm.ApiException e) {
                eq("offline is -1, so the key can be kept unchecked", -1, e.code);
            }
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------- helpers

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
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
