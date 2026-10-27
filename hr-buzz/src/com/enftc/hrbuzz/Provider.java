package com.enftc.hrbuzz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AI services the coach can talk to, and everything that differs between them: how a key
 * is recognised, the endpoints, the headers, the request body, where the answer sits in the
 * reply, how an error is worded and which model to use. The person pastes a key and HR Buzz
 * works the rest out from it. Pure Java, so it is tested on a plain JVM.
 *
 * <p>Every request asks for JSON and for the service's own "think first" setting. A model
 * that refuses one of those optional settings is retried with fewer of them (see the
 * {@code level} of {@link #body}), so a model released after this was written still works.
 */
public enum Provider {

    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash",
            "https://platform.deepseek.com/api_keys"),
    OPENAI("openai", "OpenAI (ChatGPT)", "https://api.openai.com", "gpt-5-mini",
            "https://platform.openai.com/api-keys"),
    ANTHROPIC("anthropic", "Claude (Anthropic)", "https://api.anthropic.com",
            "claude-haiku-5-5", "https://platform.claude.com/settings/keys"),
    GEMINI("gemini", "Gemini (Google)", "https://generativelanguage.googleapis.com",
            "gemini-2.5-flash", "https://aistudio.google.com/apikey");

    /**
     * The page that walks through getting a key and what it costs. It lives on the web, not in
     * the app, because prices and sign-up screens change faster than the app is updated.
     */
    public static final String GUIDE_URL = "https://hrbuzz.sourdoughcalculator.uk/keys/";

    public final String id;
    /** Name for the person: "OpenAI (ChatGPT)". */
    public final String label;
    public final String baseUrl;
    /** Used only when the service's model list can't be read. */
    public final String fallbackModel;
    /** Where the person creates a key. */
    public final String keyPage;

    Provider(String id, String label, String baseUrl, String fallbackModel, String keyPage) {
        this.id = id;
        this.label = label;
        this.baseUrl = baseUrl;
        this.fallbackModel = fallbackModel;
        this.keyPage = keyPage;
    }

    /** The host the key is sent to, for the privacy line. */
    public String host() {
        return baseUrl.replace("https://", "");
    }

    /** A short name for sentences: "OpenAI", "Claude", "Gemini", "DeepSeek". */
    public String shortName() {
        int space = label.indexOf(' ');
        return space > 0 ? label.substring(0, space) : label;
    }

    public static Provider byId(String id) {
        for (Provider p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return DEEPSEEK;        // keys saved before there was a choice were DeepSeek's
    }

    // ------------------------------------------------------------ recognising a key

    private static final Pattern DEEPSEEK_KEY = Pattern.compile("sk-[0-9a-f]{32}");

    /**
     * Which services this key could belong to, most likely first; empty when it matches none.
     * Claude and Gemini keys are unmistakable. A plain {@code sk-} key is OpenAI's or
     * DeepSeek's: 32 hex digits is DeepSeek's shape, anything longer is OpenAI's, and the
     * caller settles it by asking the service.
     */
    public static List<Provider> identify(String key) {
        List<Provider> out = new ArrayList<>();
        String k = key == null ? "" : key.trim();
        if (k.startsWith("sk-ant-")) {
            out.add(ANTHROPIC);
        } else if (k.startsWith("AIza") && k.length() >= 30) {
            out.add(GEMINI);
        } else if (k.startsWith("sk-")) {
            if (DEEPSEEK_KEY.matcher(k).matches()) {
                out.add(DEEPSEEK);
                out.add(OPENAI);
            } else {
                out.add(OPENAI);
                out.add(DEEPSEEK);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the wire

    /** Chat endpoint. {@code base} is {@link #baseUrl} unless a test points it elsewhere. */
    public String chatUrl(String base, String model) {
        switch (this) {
            case OPENAI:
                return base + "/v1/chat/completions";
            case ANTHROPIC:
                return base + "/v1/messages";
            case GEMINI:
                return base + "/v1beta/models/" + model + ":generateContent";
            default:
                return base + "/chat/completions";
        }
    }

    public String modelsUrl(String base) {
        switch (this) {
            case OPENAI:
                return base + "/v1/models";
            case ANTHROPIC:
                return base + "/v1/models?limit=1000";
            case GEMINI:
                return base + "/v1beta/models?pageSize=1000";
            default:
                return base + "/models";
        }
    }

    /** Request headers; the key goes here and nowhere else. */
    public Map<String, String> headers(String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        switch (this) {
            case ANTHROPIC:
                h.put("x-api-key", key);
                h.put("anthropic-version", "2023-06-01");
                break;
            case GEMINI:
                h.put("x-goog-api-key", key);
                break;
            default:
                h.put("Authorization", "Bearer " + key);
        }
        return h;
    }

    private static final String JSON_ONLY = "\n\nReply with a single JSON object and nothing else "
            + "(no prose before or after it, no code fence).";

    /**
     * The request body, also what "See the last message sent" shows (the key is never in it).
     *
     * @param thinking true for the reasoning pass, false for a quick answer
     * @param level    0 sends every optional setting; 1 drops the "thinking" switch; 2 sends only
     *                 the plain request (JSON asked for in words). The client steps up a level
     *                 when the service says a setting is not allowed for this model.
     */
    public String body(String model, String system, String user, int maxTokens,
                       boolean thinking, int level) {
        Map<String, Object> b;
        switch (this) {
            case OPENAI: {
                b = Json.map("model", model, "messages", messages(system, user));
                if (level < 2) {
                    b.put("response_format", Json.map("type", "json_object"));
                }
                if (level < 1) {
                    b.put("reasoning_effort", thinking ? "medium" : "low");
                }
                b.put("max_completion_tokens", Math.min(maxTokens, 16000));
                b.put("stream", false);
                break;
            }
            case ANTHROPIC: {
                b = Json.map("model", model, "max_tokens", Math.min(maxTokens, 16000),
                        "system", system + JSON_ONLY);
                List<Object> msgs = new ArrayList<>();
                msgs.add(Json.map("role", "user", "content", user));
                b.put("messages", msgs);
                if (level < 1 && thinking) {
                    b.put("thinking", Json.map("type", "adaptive"));
                }
                // A quick answer sends no "thinking" at all: the newest models refuse
                // "disabled", and at low effort they think little.
                if (level < 2) {
                    b.put("output_config", Json.map("effort", thinking ? "medium" : "low"));
                }
                break;
            }
            case GEMINI: {
                b = new LinkedHashMap<>();
                b.put("systemInstruction", Json.map("parts",
                        java.util.Arrays.asList(Json.map("text", system))));
                b.put("contents", java.util.Arrays.asList(Json.map("role", "user", "parts",
                        java.util.Arrays.asList(Json.map("text", user)))));
                Map<String, Object> cfg = new LinkedHashMap<>();
                if (level < 2) {
                    cfg.put("responseMimeType", "application/json");
                }
                cfg.put("maxOutputTokens", Math.min(maxTokens, 30000));
                if (level < 1) {
                    cfg.put("thinkingConfig", Json.map("thinkingBudget", thinking ? -1 : 0));
                }
                b.put("generationConfig", cfg);
                break;
            }
            default: {
                b = Json.map("model", model, "messages", messages(system, user));
                if (level < 2) {
                    b.put("response_format", Json.map("type", "json_object"));
                }
                if (level < 1) {
                    b.put("thinking", Json.map("type", thinking ? "enabled" : "disabled"));
                    if (thinking) {
                        b.put("reasoning_effort", "high");
                    }
                }
                b.put("max_tokens", maxTokens);
                b.put("stream", false);
            }
        }
        return Json.write(b);
    }

    private static List<Object> messages(String system, String user) {
        List<Object> messages = new ArrayList<>();
        messages.add(Json.map("role", "system", "content", system));
        messages.add(Json.map("role", "user", "content", user));
        return messages;
    }

    // ------------------------------------------------------------------ the answer

    /** The answer's text out of a parsed reply; null when there is none. */
    public String reply(Map<String, Object> resp) {
        StringBuilder sb = new StringBuilder();
        switch (this) {
            case ANTHROPIC:
                for (Object o : Json.list(resp.get("content"))) {
                    Map<String, Object> block = Json.obj(o);
                    if (block != null && "text".equals(block.get("type"))) {
                        sb.append(Json.str(block, "text", ""));
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            case GEMINI: {
                List<Object> cands = Json.list(resp.get("candidates"));
                if (cands.isEmpty()) {
                    return null;
                }
                Map<String, Object> content = Json.obj(Json.obj(cands.get(0)).get("content"));
                for (Object o : Json.list(content == null ? null : content.get("parts"))) {
                    Map<String, Object> part = Json.obj(o);
                    if (part != null && !Boolean.TRUE.equals(part.get("thought"))) {
                        sb.append(Json.str(part, "text", ""));
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            default: {
                List<Object> choices = Json.list(resp.get("choices"));
                if (choices.isEmpty()) {
                    return null;
                }
                Map<String, Object> message = Json.obj(Json.obj(choices.get(0)).get("message"));
                return Json.str(message, "content", null);
            }
        }
    }

    /** Why the service refused to answer (safety), or null when it did not. */
    public String refusal(Map<String, Object> resp) {
        switch (this) {
            case ANTHROPIC:
                return "refusal".equals(resp.get("stop_reason")) ? "declined this request" : null;
            case GEMINI: {
                Map<String, Object> fb = Json.obj(resp.get("promptFeedback"));
                if (fb != null && fb.get("blockReason") != null) {
                    return "blocked this request";
                }
                List<Object> cands = Json.list(resp.get("candidates"));
                if (!cands.isEmpty() && "SAFETY".equals(Json.obj(cands.get(0)).get("finishReason"))) {
                    return "blocked this answer";
                }
                return null;
            }
            default: {
                List<Object> choices = Json.list(resp.get("choices"));
                if (choices.isEmpty()) {
                    return null;
                }
                Map<String, Object> message = Json.obj(Json.obj(choices.get(0)).get("message"));
                return message != null && message.get("refusal") instanceof String
                        ? "declined this request" : null;
            }
        }
    }

    /** Token counts for the trace; "" when the reply has none. */
    public String usage(Map<String, Object> resp) {
        try {
            switch (this) {
                case ANTHROPIC:
                    return "tokens " + Json.str(Json.obj(resp.get("usage")), "output_tokens", "?")
                            + " out";
                case GEMINI: {
                    Map<String, Object> u = Json.obj(resp.get("usageMetadata"));
                    return "tokens " + Json.str(u, "candidatesTokenCount", "?") + " out, "
                            + Json.str(u, "thoughtsTokenCount", "0") + " reasoning";
                }
                default: {
                    Map<String, Object> u = Json.obj(resp.get("usage"));
                    return "tokens " + Json.str(u, "completion_tokens", "?") + " out, "
                            + Json.str(Json.obj(u.get("completion_tokens_details")),
                            "reasoning_tokens", "?") + " reasoning";
                }
            }
        } catch (RuntimeException e) {
            return "no usage";
        }
    }

    // ------------------------------------------------------------------- errors

    /** The service's own message out of an error body, "" when there is none. */
    static String detail(String body) {
        try {
            return Json.str(Json.obj(Json.parseObject(body).get("error")), "message", "");
        } catch (Json.ParseException | RuntimeException e) {
            return "";
        }
    }

    /**
     * The status to act on. Gemini answers a wrong key with 400 rather than 401, so that case
     * is moved to 401 and gets the "check your key" wording.
     */
    public int normalizeCode(int code, String body) {
        if (this == GEMINI && code == 400 && detail(body).toLowerCase().contains("api key")) {
            return 401;
        }
        return code;
    }

    /** True when the service refused the request itself, rather than the key or the account. */
    public boolean mayBeOptionalSetting(int code, String body) {
        if (code != 400 && code != 422) {
            return false;
        }
        String d = detail(body).toLowerCase();
        return !(d.contains("credit") || d.contains("quota") || d.contains("api key")
                || d.contains("billing"));
    }

    public String explain(int code, String body) {
        String name = shortName();
        String d = detail(body).toLowerCase();
        if (code == 401 || code == 403) {
            return name + " rejected the API key — check it in Coach settings";
        }
        if (code == 402 || d.contains("credit balance") || d.contains("insufficient_quota")
                || d.contains("exceeded your current quota")) {
            return "Your " + name + " account is out of credit or over its quota";
        }
        if (code == 404) {
            return name + " does not offer the chosen model to this key";
        }
        if (code == 429) {
            return name + " is busy or rate-limiting — try again in a minute";
        }
        if (code == 400 || code == 422) {
            String detail = detail(body);
            return name + " refused the request" + (detail.isEmpty() ? "" : ": " + detail);
        }
        return code >= 500 ? name + " is having trouble — try again later"
                : name + " error " + code;
    }

    // ----------------------------------------------------------- choosing a model

    /** The model ids a service lists, out of its parsed model-list reply. */
    public List<String> modelIds(Map<String, Object> resp) {
        List<String> ids = new ArrayList<>();
        if (this == GEMINI) {
            for (Object o : Json.list(resp.get("models"))) {
                Map<String, Object> m = Json.obj(o);
                if (m == null || !Json.list(m.get("supportedGenerationMethods"))
                        .contains("generateContent")) {
                    continue;
                }
                String name = Json.str(m, "name", "");
                ids.add(name.startsWith("models/") ? name.substring(7) : name);
            }
        } else {
            for (Object o : Json.list(resp.get("data"))) {
                String id = Json.str(Json.obj(o), "id", "");
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * The model to use out of what the key can reach: the newest from the small tier of each
     * service (a Haiku, a "luna" or "mini", a "flash"), the tier DeepSeek Flash belongs to and
     * the coach was built on. A daily plan needs reasoning and JSON but not the dearest model,
     * and this keeps a month of coaching to pennies or a few dollars. Null when nothing
     * recognisable is listed.
     */
    public String pick(List<String> ids) {
        switch (this) {
            case DEEPSEEK:
                return fallbackModel;
            case OPENAI:
                return newest(ids, "gpt-(\\d+(?:\\.\\d+)?)-(?:luna|mini)", "gpt-(\\d+(?:\\.\\d+)?)");
            case ANTHROPIC:
                return newest(ids, "claude-haiku-(\\d+(?:-\\d{1,2})?)",
                        "claude-sonnet-(\\d+(?:-\\d{1,2})?)", "claude-opus-(\\d+(?:-\\d{1,2})?)");
            default:
                return newest(ids, "gemini-(\\d+(?:\\.\\d+)?)-flash", "gemini-(\\d+(?:\\.\\d+)?)-pro");
        }
    }

    /** The highest version among ids matching the first pattern that matches any; else null. */
    private static String newest(List<String> ids, String... patterns) {
        for (String p : patterns) {
            Pattern re = Pattern.compile(p);
            String best = null;
            int[] bestVersion = null;
            for (String id : ids) {
                Matcher m = re.matcher(id);
                if (!m.matches()) {
                    continue;
                }
                int[] v = version(m.group(1));
                if (best == null || compare(v, bestVersion) > 0) {
                    best = id;
                    bestVersion = v;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** "5.5" or "5-5" as {5, 5}, so 5.10 sorts above 5.9. */
    static int[] version(String s) {
        String[] parts = s.split("[.-]");
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Integer.parseInt(parts[i]);
        }
        return v;
    }

    static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** Providers in the order shown to the person. */
    public static List<Provider> all() {
        List<Provider> l = new ArrayList<>();
        Collections.addAll(l, OPENAI, ANTHROPIC, GEMINI, DEEPSEEK);
        return l;
    }
}
