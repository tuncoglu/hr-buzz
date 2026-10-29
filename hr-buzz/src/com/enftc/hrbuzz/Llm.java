package com.enftc.hrbuzz;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The coach's connection to whichever AI service the person's key belongs to (see
 * {@link Provider}): one HTTPS request, JSON answer, reasoning on or off. java.net only, so it
 * runs (and is tested against a local stub) on a plain JVM. Reasoning tokens count against
 * the token limit, so callers leave generous room.
 */
public final class Llm {

    /**
     * {@link ApiException#code} for a request the on-device model refused because HR Buzz was
     * not on screen: nothing is wrong, it works when the app is open (see {@link OnDevice}).
     */
    public static final int NEEDS_FOREGROUND = -2;

    /** A failure worth showing to the person, already in plain words. */
    public static final class ApiException extends Exception {
        public final int code;
        /** The service refused a setting rather than the key, so fewer settings may work. */
        final boolean maybeSetting;

        ApiException(int code, String message) {
            this(code, message, false);
        }

        ApiException(int code, String message, boolean maybeSetting) {
            super(message);
            this.code = code;
            this.maybeSetting = maybeSetting;
        }
    }

    /** What to ask: the same for every service. */
    public static final class Request {
        final String system;
        final String user;
        final int maxTokens;
        final boolean thinking;

        /** {@code thinking} false is the fast mode: no reasoning pass, for plain questions. */
        public Request(String system, String user, int maxTokens, boolean thinking) {
            this.system = system;
            this.user = user;
            this.maxTokens = maxTokens;
            this.thinking = thinking;
        }

        public Request(String system, String user, int maxTokens) {
            this(system, user, maxTokens, true);
        }
    }

    /** The outcome of {@link #connect}: which service the key is for and the model to use. */
    public static final class Connection {
        public final Provider provider;
        public final String model;

        Connection(Provider provider, String model) {
            this.provider = provider;
            this.model = model;
        }
    }

    /** Where timing notes go; the Android side points this at logcat. Off (null) on a plain JVM. */
    public static volatile java.util.function.Consumer<String> trace;

    private final Provider provider;
    private final String model;
    private final String base;
    private final int readTimeoutMs;

    /** The exact body of the latest request (the key is never in it), for "last message sent". */
    public volatile String lastBody;

    private static void note(String text) {
        java.util.function.Consumer<String> t = trace;
        if (t != null) {
            t.accept(text);
        }
    }

    public Llm(Provider provider, String model) {
        this(provider, model, provider.baseUrl, 240000);
    }

    /** {@code base} replaces the service's address; tests point it at a local server. */
    public Llm(Provider provider, String model, String base, int readTimeoutMs) {
        this.provider = provider;
        this.model = model;
        this.base = base;
        this.readTimeoutMs = readTimeoutMs;
    }

    public Provider provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    /**
     * Sends the request and returns the reply parsed as a JSON object. Retries once when the
     * model returns empty or broken JSON, which the services document as an occasional result,
     * and steps down to fewer optional settings when one is refused for this model.
     */
    public Map<String, Object> ask(String apiKey, Request r) throws ApiException {
        ApiException last = null;
        int level = 0;
        int emptyTries = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            String body = provider.body(model, r.system, r.user, r.maxTokens, r.thinking, level);
            lastBody = body;
            note("attempt " + (attempt + 1) + " sending " + body.length() + " chars, level "
                    + level);
            String content;
            try {
                content = complete(apiKey, body);
            } catch (ApiException e) {
                if (e.maybeSetting && level < 2) {
                    level++;
                    last = e;
                    continue;
                }
                throw e;
            }
            if (content == null || content.trim().isEmpty()) {
                last = new ApiException(0, "The coach sent an empty answer");
            } else {
                try {
                    return Json.parseObject(jsonOf(content));
                } catch (Json.ParseException e) {
                    last = new ApiException(0, "The coach's answer could not be read");
                }
            }
            if (++emptyTries >= 2) {
                break;
            }
        }
        throw last;
    }

    /** Raw answer text of one request. */
    String complete(String apiKey, String body) throws ApiException {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        try {
            c = (HttpURLConnection) URI.create(provider.chatUrl(base, model)).toURL()
                    .openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            for (Map.Entry<String, String> h : provider.headers(apiKey).entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            long sent = System.currentTimeMillis();
            note("request sent after " + (sent - t0) + " ms (connect and upload)");
            int code = c.getResponseCode();
            long headers = System.currentTimeMillis();
            note("answer started after " + (headers - sent) + " ms, status " + code);
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) {
                int shown = provider.normalizeCode(code, text);
                throw new ApiException(shown, provider.explain(shown, text),
                        provider.mayBeOptionalSetting(shown, text));
            }
            Map<String, Object> resp = Json.parseObject(text);
            note("answer read after " + (System.currentTimeMillis() - headers) + " ms, "
                    + text.length() + " chars, " + provider.usage(resp));
            String content = provider.reply(resp);
            if (content == null || content.trim().isEmpty()) {
                String why = provider.refusal(resp);
                if (why != null) {
                    throw new ApiException(0, provider.shortName() + " " + why);
                }
            }
            return content;
        } catch (IOException e) {
            note("failed after " + (System.currentTimeMillis() - t0) + " ms: " + e);
            throw new ApiException(-1, "Couldn't reach " + provider.shortName()
                    + " — check your connection");
        } catch (Json.ParseException | RuntimeException e) {
            throw new ApiException(0, "Unexpected answer from " + provider.shortName());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------ recognising a key

    /**
     * Works out which service a key belongs to and which model to use, by asking each likely
     * service for the models the key can reach. A key that no candidate accepts is an error
     * with code 401; one that cannot be checked (offline) is code -1, and the caller may keep
     * it and settle the model on first use. The model list is read once and the choice cached
     * by the caller.
     */
    public static Connection connect(String apiKey) throws ApiException {
        return connect(apiKey, null);
    }

    /** {@code baseOverride} points every service at one address, for tests. */
    static Connection connect(String apiKey, String baseOverride) throws ApiException {
        List<Provider> candidates = Provider.identify(apiKey);
        if (candidates.isEmpty()) {
            throw new ApiException(0, "Couldn't tell which service this key is for. HR Buzz "
                    + "understands OpenAI (ChatGPT), Claude, Gemini and DeepSeek API keys");
        }
        ApiException rejected = null;
        for (Provider p : candidates) {
            try {
                Map<String, Object> models = get(p, baseOverride == null ? p.baseUrl : baseOverride,
                        apiKey);
                String model = p.pick(p.modelIds(models));
                note("key recognised as " + p.id + ", model " + (model == null ? "fallback" : model));
                return new Connection(p, model == null ? p.fallbackModel : model);
            } catch (ApiException e) {
                if (e.code != 401 && e.code != 403) {
                    throw e;
                }
                rejected = rejected == null ? e : new ApiException(401, "None of the services "
                        + "it could belong to accepted this key (" + names(candidates) + ")");
            }
        }
        throw rejected;
    }

    private static String names(List<Provider> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Provider p : candidates) {
            sb.append(sb.length() == 0 ? "" : ", ").append(p.shortName());
        }
        return sb.toString();
    }

    private static Map<String, Object> get(Provider p, String base, String apiKey)
            throws ApiException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(p.modelsUrl(base)).toURL().openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            for (Map.Entry<String, String> h : p.headers(apiKey).entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code != 200) {
                int shown = p.normalizeCode(code, text);
                throw new ApiException(shown, p.explain(shown, text));
            }
            return Json.parseObject(text);
        } catch (IOException e) {
            throw new ApiException(-1, "Couldn't reach " + p.shortName()
                    + " — check your connection");
        } catch (Json.ParseException | RuntimeException e) {
            throw new ApiException(0, "Unexpected answer from " + p.shortName());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------- helpers

    /** Some models wrap JSON in a ```json fence even in JSON mode; tolerate it. */
    static String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                return t.substring(nl + 1, end).trim();
            }
        }
        return t;
    }

    /**
     * The JSON object in an answer: unfenced, and cut down to its outermost braces when the
     * model added a sentence around it (services without a JSON mode do that now and then).
     */
    static String jsonOf(String s) {
        String t = stripFence(s);
        int open = t.indexOf('{');
        int close = t.lastIndexOf('}');
        if (!t.startsWith("{") && open >= 0 && close > open) {
            return t.substring(open, close + 1);
        }
        return t;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
