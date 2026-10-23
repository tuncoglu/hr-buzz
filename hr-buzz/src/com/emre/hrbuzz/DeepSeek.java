package com.emre.hrbuzz;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek chat completions over plain HTTPS (OpenAI-compatible API), JSON output mode,
 * thinking on at high effort: the coach should reason over the person's history, not answer
 * off the cuff. Reasoning tokens count against max_tokens, so callers leave generous room.
 * java.net only, so it runs (and is tested against a local stub) on a plain JVM.
 */
public final class DeepSeek {

    public static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    public static final String MODEL = "deepseek-flash";

    /** A failure worth showing to the person, already in plain words. */
    public static final class ApiException extends Exception {
        public final int code;

        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Where timing notes go; the Android side points this at logcat. Off (null) on a plain JVM. */
    public static volatile java.util.function.Consumer<String> trace;

    private final String endpoint;
    private final int readTimeoutMs;

    private static void note(String text) {
        java.util.function.Consumer<String> t = trace;
        if (t != null) {
            t.accept(text);
        }
    }

    public DeepSeek() {
        this(ENDPOINT, 240000);
    }

    public DeepSeek(String endpoint, int readTimeoutMs) {
        this.endpoint = endpoint;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** The request body, also what "See the last message sent" shows (the key is never in it). */
    public static String body(String system, String user, int maxTokens) {
        List<Object> messages = new ArrayList<>();
        messages.add(Json.map("role", "system", "content", system));
        messages.add(Json.map("role", "user", "content", user));
        return Json.write(Json.map(
                "model", MODEL,
                "messages", messages,
                "response_format", Json.map("type", "json_object"),
                "thinking", Json.map("type", "enabled"),
                "reasoning_effort", "high",
                "max_tokens", maxTokens,
                "stream", false));
    }

    /**
     * Sends {@code body} and returns the reply parsed as a JSON object. Retries once when the
     * model returns empty or broken JSON, which the API documents as an occasional result.
     */
    public Map<String, Object> ask(String apiKey, String body) throws ApiException {
        ApiException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            note("attempt " + (attempt + 1) + " sending " + body.length() + " chars");
            String content = complete(apiKey, body);
            if (content == null || content.trim().isEmpty()) {
                last = new ApiException(0, "The coach sent an empty answer");
                continue;
            }
            try {
                return Json.parseObject(stripFence(content));
            } catch (Json.ParseException e) {
                last = new ApiException(0, "The coach's answer could not be read");
            }
        }
        throw last;
    }

    /** Raw message content of the first choice. */
    String complete(String apiKey, String body) throws ApiException {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        try {
            c = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
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
            note("answer read after " + (System.currentTimeMillis() - headers) + " ms, "
                    + text.length() + " chars, " + usage(text));
            if (code != 200) {
                throw new ApiException(code, explain(code, text));
            }
            Map<String, Object> resp = Json.parseObject(text);
            List<Object> choices = Json.list(resp.get("choices"));
            if (choices.isEmpty()) {
                return null;
            }
            Map<String, Object> message = Json.obj(Json.obj(choices.get(0)).get("message"));
            return Json.str(message, "content", null);
        } catch (IOException e) {
            note("failed after " + (System.currentTimeMillis() - t0) + " ms: " + e);
            throw new ApiException(-1, "Couldn't reach DeepSeek \u2014 check your connection");
        } catch (Json.ParseException | RuntimeException e) {
            throw new ApiException(0, "Unexpected answer from DeepSeek");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** The token counts from a reply, for the trace; "" when it has none. */
    private static String usage(String text) {
        try {
            Map<String, Object> u = Json.obj(Json.parseObject(text).get("usage"));
            return "tokens " + Json.str(u, "completion_tokens", "?")
                    + " out, " + Json.str(Json.obj(u.get("completion_tokens_details")),
                    "reasoning_tokens", "?") + " reasoning";
        } catch (Json.ParseException | RuntimeException e) {
            return "no usage";
        }
    }

    static String explain(int code, String body) {
        switch (code) {
            case 401:
                return "DeepSeek rejected the API key \u2014 check it in Coach settings";
            case 402:
                return "Your DeepSeek account is out of credit";
            case 429:
                return "DeepSeek is busy or rate-limiting \u2014 try again in a minute";
            case 400:
            case 422:
                String detail = "";
                try {
                    detail = Json.str(Json.obj(Json.parseObject(body).get("error")), "message", "");
                } catch (Json.ParseException | RuntimeException ignored) {
                }
                return "DeepSeek refused the request" + (detail.isEmpty() ? "" : ": " + detail);
            default:
                return code >= 500 ? "DeepSeek is having trouble \u2014 try again later"
                        : "DeepSeek error " + code;
        }
    }

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
