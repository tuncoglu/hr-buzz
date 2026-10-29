package com.enftc.hrbuzz;

import java.util.Map;

/**
 * The coach's other way to think: Gemma 4 running on this phone through Android's AICore, with
 * no key, no account and nothing sent anywhere. The engine itself (Google's ML Kit GenAI
 * library) is only in the Gradle build under {@code ondevice/}; this file is plain Java so the
 * rest of the app, the Play build and the tests need nothing from it. The engine is found by
 * name, so a build without it simply reports {@link #compiled()} false.
 *
 * <p>Two things differ from a cloud service and the callers must respect them. AICore answers
 * only while HR Buzz is on screen (a background job or a foreground service is refused with
 * {@link Llm#NEEDS_FOREGROUND}), and the model is a preview that only phones enrolled in the
 * AICore developer preview have.
 */
public final class OnDevice {

    private OnDevice() {
    }

    /** What the person sees as the service. */
    public static final String NAME = "Gemma 4 E4B (preview)";

    // The library's FeatureStatus values.
    public static final int UNAVAILABLE = 0;
    public static final int DOWNLOADABLE = 1;
    public static final int DOWNLOADING = 2;
    public static final int AVAILABLE = 3;

    // The library's GenAiException error codes that need their own wording.
    static final int ERR_NOT_AVAILABLE = 8;
    static final int ERR_BUSY = 9;
    static final int ERR_REQUEST_TOO_LARGE = 12;
    static final int ERR_NOT_SUPPORTED = 16;
    static final int ERR_BATTERY_QUOTA = 27;
    static final int ERR_BACKGROUND = 30;
    static final int ERR_NOT_ENOUGH_DISK = 501;
    static final int ERR_NEEDS_SYSTEM_UPDATE = 604;
    static final int ERR_FEATURE_NOT_FOUND = 606;

    /** The model reads the whole brief but is not asked for essays; reasoning stays off. */
    static final int MAX_OUTPUT_TOKENS = 8192;

    private static final String JSON_ONLY = "\n\nReply with a single JSON object and nothing else "
            + "(no prose before or after it, no code fence).";

    /** The engine failed; {@code code} is the library's error code, 0 when it has none. */
    public static final class Failure extends Exception {
        public final int code;

        public Failure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** The model on this phone. Calls block: worker thread only. */
    public interface Engine {
        /** One of {@link #UNAVAILABLE}..{@link #AVAILABLE} for the model the coach uses. */
        int status() throws Failure;

        /** Starts the model download when {@link #DOWNLOADABLE}; returns when it has finished. */
        void download() throws Failure;

        /** The answer text for one request. */
        String generate(String system, String user, int maxTokens) throws Failure;

        /** The model AICore actually runs, e.g. "nano-v4-full"; "" when unknown. */
        String modelName();
    }

    private static final String ENGINE_CLASS = "com.enftc.hrbuzz.MlKitEngine";
    private static Engine engine;
    private static boolean looked;

    /** True when this build carries the engine at all. */
    public static synchronized boolean compiled() {
        return engine() != null;
    }

    /** The engine, or null when this build does not have one. */
    public static synchronized Engine engine() {
        if (!looked) {
            looked = true;
            try {
                engine = (Engine) Class.forName(ENGINE_CLASS).getDeclaredConstructor()
                        .newInstance();
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                engine = null;
            }
        }
        return engine;
    }

    /** Tests install a fake engine (or null for none). */
    static synchronized void use(Engine e) {
        engine = e;
        looked = true;
    }

    // ------------------------------------------------------------------ asking

    /**
     * Sends the request to the model and returns the answer parsed as a JSON object, with the
     * same retry-once behaviour as {@link Llm#ask}. {@code thinking} is ignored: with it off the
     * plan takes about 30 seconds on the phone, with it on over a minute and a half for no
     * better a plan.
     */
    public static Map<String, Object> ask(Engine e, Llm.Request r) throws Llm.ApiException {
        String system = r.system + JSON_ONLY;
        int tokens = Math.min(r.maxTokens, MAX_OUTPUT_TOKENS);
        Llm.ApiException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String text;
            try {
                text = e.generate(system, r.user, tokens);
            } catch (Failure f) {
                throw toApi(f);
            }
            if (text == null || text.trim().isEmpty()) {
                last = new Llm.ApiException(0, "The coach sent an empty answer");
                continue;
            }
            try {
                return Json.parseObject(Llm.jsonOf(text));
            } catch (Json.ParseException ex) {
                last = new Llm.ApiException(0, "The coach's answer could not be read");
            }
        }
        throw last;
    }

    /** What "See the last message sent" shows for a request that never left the phone. */
    public static String describe(Llm.Request r) {
        return Json.write(Json.map("model", NAME, "system", r.system + JSON_ONLY,
                "user", r.user, "maxOutputTokens", Math.min(r.maxTokens, MAX_OUTPUT_TOKENS),
                "thinking", false));
    }

    // ------------------------------------------------------------------ errors

    /** A failure in the person's words, with a code the callers can act on. */
    public static Llm.ApiException toApi(Failure f) {
        switch (f.code) {
            case ERR_BACKGROUND:
                return new Llm.ApiException(Llm.NEEDS_FOREGROUND, "The on-device coach only "
                        + "works while HR Buzz is open on screen — open the app and try again");
            case ERR_BUSY:
                return new Llm.ApiException(429, "Your phone's AI is busy with another app "
                        + "— try again in a minute");
            case ERR_BATTERY_QUOTA:
                return new Llm.ApiException(429, "Your phone has limited HR Buzz's on-device AI "
                        + "to save battery — try again later");
            case ERR_NEEDS_SYSTEM_UPDATE:
                return new Llm.ApiException(0, "Update Android AICore in Google Play to use the "
                        + "on-device coach");
            case ERR_NOT_ENOUGH_DISK:
                return new Llm.ApiException(0, "There isn't enough free storage for the "
                        + "on-device model");
            case ERR_REQUEST_TOO_LARGE:
                return new Llm.ApiException(0, "Your history is too long for the on-device "
                        + "coach to read");
            case ERR_NOT_AVAILABLE:
            case ERR_NOT_SUPPORTED:
            case ERR_FEATURE_NOT_FOUND:
                return new Llm.ApiException(0, "The on-device model isn't available on this "
                        + "phone right now");
            default:
                return new Llm.ApiException(0, "The on-device coach failed"
                        + (f.code != 0 ? " (code " + f.code + ")" : ""));
        }
    }

    /** One line for the setup screen about the model's state. */
    public static String statusLine(int status) {
        switch (status) {
            case AVAILABLE:
                return NAME + " is ready on this phone";
            case DOWNLOADABLE:
                return NAME + " can be downloaded to this phone";
            case DOWNLOADING:
                return NAME + " is downloading";
            default:
                return NAME + " isn't available on this phone. It needs a Pixel enrolled in the "
                        + "AICore developer preview";
        }
    }
}
