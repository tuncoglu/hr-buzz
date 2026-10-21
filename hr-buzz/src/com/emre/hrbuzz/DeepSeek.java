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
 * thinking off: the coach wants a quick, structured answer, not a long deliberation.
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

    private final String endpoint;
    private final int readTimeoutMs;

    public DeepSeek() {
        this(ENDPOINT, 60000);
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
                "thinking", Json.map("type", "disabled"),
                "max_tokens", maxTokens,
                "temperature", 0.7,
                "stream", false));
    }

    /**
     * Sends {@code body} and returns the reply parsed as a JSON object. Retries once when the
     * model returns empty or broken JSON, which the API documents as an occasional result.
     */
    public Map<String, Object> ask(String apiKey, String body) throws ApiException {
        ApiException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
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
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
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
            throw new ApiException(-1, "Couldn't reach DeepSeek \u2014 check your connection");
        } catch (Json.ParseException | RuntimeException e) {
            throw new ApiException(0, "Unexpected answer from DeepSeek");
        } finally {
            if (c != null) {
                c.disconnect();
            }
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
