package com.enftc.hrbuzz;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The coach on the Android side: settings, the API key (encrypted with a key that never leaves
 * Android's keystore), today's plan, the conversation, and the calls to the AI service the key
 * belongs to (OpenAI, Claude, Gemini or DeepSeek, worked out from the key). Every plan
 * goes through {@link CoachLogic#guard} before anything can run it. Application scoped.
 */
public class Coach {

    private static final String TAG = "HRBuzz";
    private static final String KEY_ALIAS = "hrbuzz_coach_api_key";
    static final int JOB_MORNING = 7;
    private static final int MAX_CHAT = 60;
    /** Chat turns sent with each question. */
    private static final int CHAT_TURNS = 30;
    /** max_tokens covers the model's reasoning as well as its answer. */
    private static final int PLAN_TOKENS = 32000;
    private static final int ASK_TOKENS = 32000;
    /** Without a reasoning pass the whole budget is the answer, so far less is needed. */
    private static final int ASK_FAST_TOKENS = 6000;
    private static final int DEBRIEF_TOKENS = 32000;

    public interface Callback<T> {
        /** Exactly one of {@code result} and {@code error} is non-null. On the main thread. */
        void done(T result, String error);
    }

    private static Coach instance;

    public static synchronized Coach get(Context context) {
        if (instance == null) {
            instance = new Coach(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Requests in flight; the UI shows "Updating" and ignores taps while above zero. */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    private Coach(Context context) {
        this.appContext = context;
        this.prefs = Prefs.of(context);
        // Timings only (no key, no text): where a slow coach request spends its time.
        Llm.trace = note -> Log.i(TAG, "api: " + note);
    }

    // --------------------------------------------------------------- settings

    /** Turned on, set up and holding a key: the coach may talk to the key's AI service. */
    public boolean isEnabled() {
        return prefs.getBoolean("coachOn", false) && prefs.getBoolean("coachSetUp", false)
                && hasKey();
    }

    public boolean isPlanning() {
        return inFlight.get() > 0;
    }

    public CoachLogic.Settings settings() {
        CoachLogic.Settings s = new CoachLogic.Settings();
        s.goal = prefs.getString("coachGoal", "Get fitter");
        s.daysPerWeek = prefs.getInt("coachDays", 3);
        s.minutes = prefs.getInt("coachMinutes", 30);
        s.workAround = prefs.getString("coachWorkAround", "Nothing");
        s.hrMax = Prefs.hrMax(appContext);
        s.age = Prefs.age(appContext);
        s.male = Prefs.male(appContext);
        s.weightKg = Prefs.weight(appContext);
        s.restHr = Prefs.restHr(appContext);
        s.ceiling = Prefs.alertAbove(appContext);
        s.hrMaxSource = Prefs.hrMaxSource(appContext);
        s.peakHr = Prefs.hrMaxSeen(appContext);
        // "date|hrMax": raised for a different hrMax does not count
        String[] raised = prefs.getString("coachHrMaxRaised", "").split("\\|");
        s.hrMaxRaised = raised.length == 2 && raised[1].equals(String.valueOf(s.hrMax))
                ? raised[0] : "";
        s.goalTarget = prefs.getString("coachGoalTarget", "");
        s.goalBy = prefs.getString("coachGoalBy", "");
        s.remember = remembered();
        return s;
    }

    // ------------------------------------------------------- what it remembers

    /** Things the person asked the coach to keep in mind, oldest first. */
    public List<String> remembered() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("coachRemember", "[]"));
            for (int i = 0; i < a.length(); i++) {
                out.add(a.getString(i));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void saveRemembered(List<String> all) {
        JSONArray a = new JSONArray();
        for (String t : all.subList(Math.max(0, all.size() - CoachLogic.MAX_REMEMBER), all.size())) {
            a.put(t);
        }
        prefs.edit().putString("coachRemember", a.toString()).apply();
    }

    private synchronized void remember(List<String> items) {
        List<String> all = remembered();
        for (String t : items) {
            if (!CoachLogic.redFlag(t) && !all.contains(t)) {
                all.add(t);
            }
        }
        saveRemembered(all);
    }

    public synchronized void forgetItem(String item) {
        List<String> all = remembered();
        all.remove(item);
        saveRemembered(all);
    }

    // --------------------------------------------------------------- check-in

    /** How they slept and how their digestion was today: 0 = not answered. */
    public int[] checkIn() {
        JSONObject day = checkIns().optJSONObject(today());
        return day == null ? new int[]{0, 0} : new int[]{day.optInt("sleep"), day.optInt("gut")};
    }

    /** Saves part of today's check-in; -1 leaves that answer as it is. */
    public synchronized void setCheckIn(int sleep, int gut) {
        JSONObject all = checkIns();
        try {
            JSONObject day = all.optJSONObject(today());
            if (day == null) {
                day = new JSONObject();
            }
            if (sleep >= 0) {
                day.put("sleep", sleep);
            }
            if (gut >= 0) {
                day.put("gut", gut);
            }
            all.put(today(), day);
            prefs.edit().putString("coachCheckIns", pruned(all).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private JSONObject checkIns() {
        try {
            return new JSONObject(prefs.getString("coachCheckIns", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Only the days the coach is told about are kept. */
    private JSONObject pruned(JSONObject byDate) {
        String oldest = java.time.LocalDate.parse(today())
                .minusDays(CoachLogic.DAYS_BACK - 1).toString();
        JSONObject out = new JSONObject();
        for (java.util.Iterator<String> it = byDate.keys(); it.hasNext(); ) {
            String d = it.next();
            if (d.compareTo(oldest) >= 0) {
                try {
                    out.put(d, byDate.get(d));
                } catch (JSONException ignored) {
                }
            }
        }
        return out;
    }

    /** Health Connect, check-ins and the plan's history, as the brief takes them. */
    private CoachLogic.Extras extras() {
        CoachLogic.Extras x = new CoachLogic.Extras();
        x.move = HealthSync.forCoach(appContext);
        x.planDone = todayDone();
        x.health = HealthSync.daysForCoach(appContext);
        JSONObject ci = checkIns();
        for (java.util.Iterator<String> it = ci.keys(); it.hasNext(); ) {
            String d = it.next();
            JSONObject o = ci.optJSONObject(d);
            if (o != null) {
                CoachLogic.CheckIn c = new CoachLogic.CheckIn();
                c.date = d;
                c.sleep = o.optInt("sleep");
                c.gut = o.optInt("gut");
                x.checkIns.add(c);
            }
        }
        JSONObject log = planLog();
        for (java.util.Iterator<String> it = log.keys(); it.hasNext(); ) {
            String d = it.next();
            JSONObject o = log.optJSONObject(d);
            if (o != null) {
                CoachLogic.PlanDay pd = new CoachLogic.PlanDay();
                pd.date = d;
                pd.planned = o.optString("planned");
                JSONArray ch = o.optJSONArray("changes");
                for (int i = 0; ch != null && i < ch.length(); i++) {
                    pd.changes.add(ch.optString(i));
                }
                x.planDays.add(pd);
            }
        }
        return x;
    }

    /** Minutes after midnight for the morning plan; -1 for no morning notification. */
    public int morningMinute() {
        return prefs.getInt("coachMorning", 7 * 60 + 30);
    }

    // -------------------------------------------------------------------- key

    public boolean hasKey() {
        return prefs.getString("coachKey", null) != null;
    }

    /** The AI service the saved key belongs to. Keys saved before there was a choice are DeepSeek's. */
    public Provider provider() {
        return Provider.byId(prefs.getString("coachProvider", Provider.DEEPSEEK.id));
    }

    /** The model in use: the one picked for this key, or the service's usual one until then. */
    public String model() {
        String m = prefs.getString("coachModel", "");
        return m.isEmpty() ? provider().fallbackModel : m;
    }

    /** "Claude (Anthropic) · claude-sonnet-5-5", or null with no key saved. */
    public String serviceLine() {
        return hasKey() ? provider().label + " · " + model() : null;
    }

    public static final int KEY_OK = 0;
    public static final int KEY_REJECTED = 1;
    public static final int KEY_UNCHECKED = 2;

    public interface KeyCheck {
        /**
         * On the main thread. {@code KEY_OK}: the service accepted it ({@code message} says
         * which and what model). {@code KEY_REJECTED}: it did not, and the key was not saved.
         * {@code KEY_UNCHECKED}: could not ask (offline); the key was saved.
         */
        void done(int state, String message);
    }

    /**
     * Asks the key's service which models it offers and, only if the service accepts the key,
     * saves it with the provider and model it settled on (on the worker). A key the service
     * refuses is not saved, so a typo never replaces a key that works; one that cannot be
     * checked (offline) is saved and settled on first use.
     */
    public void checkNewKey(String key, KeyCheck check) {
        final String k = key == null ? "" : key.trim();
        worker.execute(() -> {
            int state;
            String message;
            try {
                Llm.Connection c = Llm.connect(k);
                if (setKey(k)) {
                    prefs.edit().putString("coachProvider", c.provider.id)
                            .putString("coachModel", c.model).apply();
                    state = KEY_OK;
                    message = serviceLine();
                } else {
                    state = KEY_REJECTED;
                    message = "Couldn't store the key on this phone";
                }
            } catch (Llm.ApiException e) {
                message = e.getMessage();
                if (e.code == 401 || e.code == 403) {
                    state = KEY_REJECTED;
                } else {
                    state = setKey(k) ? KEY_UNCHECKED : KEY_REJECTED;
                }
            }
            final int s = state;
            final String m = message;
            main.post(() -> check.done(s, m));
        });
    }

    /**
     * The client for the saved key. A model not yet settled for this key is picked from the
     * service's own list; offline, the service's usual model stands in and the call itself
     * reports any real problem. Blocking: worker thread only.
     */
    private Llm client(String key) {
        if (prefs.getString("coachModel", "").isEmpty()) {
            try {
                Llm.Connection c = Llm.connect(key);
                prefs.edit().putString("coachProvider", c.provider.id)
                        .putString("coachModel", c.model).apply();
            } catch (Llm.ApiException ignored) {
                // offline or an odd reply: carry on with the usual model
            }
        }
        return new Llm(provider(), model());
    }

    /**
     * Encrypts and stores the key, then reads it back to be sure. The service it belongs to is
     * worked out from its shape; the model is settled by {@link #checkKey} or on first use.
     *
     * @return false when it could not be stored (nothing changes then)
     */
    public boolean setKey(String key) {
        key = key == null ? "" : key.trim();
        if (key.isEmpty()) {
            prefs.edit().remove("coachKey").remove("coachProvider").remove("coachModel").apply();
            return true;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, secret());
            byte[] ct = c.doFinal(key.getBytes(StandardCharsets.UTF_8));
            String before = prefs.getString("coachKey", null);
            prefs.edit().putString("coachKey", Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).commit();
            if (!key.equals(key())) {
                prefs.edit().putString("coachKey", before).commit();
                return false;
            }
            List<Provider> who = Provider.identify(key);
            prefs.edit().putString("coachProvider", (who.isEmpty() ? Provider.DEEPSEEK : who.get(0)).id)
                    .remove("coachModel").apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot store key", e);
            return false;
        }
    }

    private String key() {
        String stored = prefs.getString("coachKey", null);
        if (stored == null || !stored.contains(":")) {
            return null;
        }
        try {
            String[] parts = stored.split(":", 2);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, secret(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "cannot read key", e);
            return null;
        }
    }

    private static SecretKey secret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    // ------------------------------------------------------------------- plan

    public String today() {
        return CoachLogic.dateOf(System.currentTimeMillis(), ZoneId.systemDefault());
    }

    /** Today's checked plan, or null when there is none for today yet. */
    public CoachLogic.Plan todayPlan() {
        CoachLogic.Plan p = storedPlan();
        return p != null && today().equals(p.date) ? p : null;
    }

    private CoachLogic.Plan storedPlan() {
        String json = prefs.getString("coachPlan", null);
        if (json == null) {
            return null;
        }
        try {
            Map<String, Object> m = Json.parseObject(json);
            return CoachLogic.parsePlan(m, Json.str(m, "date", ""));
        } catch (Json.ParseException e) {
            return null;
        }
    }

    void savePlan(CoachLogic.Plan p) {
        savePlan(p, null);
    }

    /**
     * Stores the plan, and notes it in the plan's history: the first plan of a day is what was
     * planned, later ones are changes, described by {@code change} (null: not a change).
     */
    void savePlan(CoachLogic.Plan p, String change) {
        prefs.edit().putString("coachPlan", Json.write(CoachLogic.planToMap(p))).apply();
        logPlan(p, change);
    }

    private synchronized void logPlan(CoachLogic.Plan p, String change) {
        JSONObject log = planLog();
        String what = p.rest ? "rest day" : p.title + ", " + p.totalMinutes() + " min";
        try {
            JSONObject day = log.optJSONObject(p.date);
            if (day == null) {
                day = new JSONObject().put("planned", what).put("changes", new JSONArray());
            } else if (change != null) {
                JSONArray changes = day.getJSONArray("changes");
                String entry = change + ": " + what;
                if (changes.length() > 0 && entry.equals(changes.optString(changes.length() - 1))) {
                    return;
                }
                changes.put(entry);
            } else {
                return;
            }
            log.put(p.date, day);
            prefs.edit().putString("coachPlanLog", pruned(log).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private JSONObject planLog() {
        try {
            return new JSONObject(prefs.getString("coachPlanLog", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** True once today's planned session has been recorded. */
    public boolean todayDone() {
        return today().equals(prefs.getString("coachDone", ""));
    }

    void markDone() {
        prefs.edit().putString("coachDone", today()).apply();
    }

    /** A deleted session no longer counts as today's planned one. */
    void sessionDeleted(SessionStore.Summary s) {
        if (!s.planTitle.isEmpty() && today().equals(CoachLogic.dateOf(s.id, ZoneId.systemDefault()))) {
            prefs.edit().remove("coachDone").apply();
        }
    }

    /** A plan stored without calling the model (red-flag rest, or undo). */
    public void replacePlan(CoachLogic.Plan p) {
        replacePlan(p, "changed on the phone");
    }

    /** As above, saying what the change was for the plan's history. */
    public void replacePlan(CoachLogic.Plan p, String change) {
        p.date = today();
        savePlan(p, change);
    }

    /**
     * After a fitness test: today's plan is checked again against the new readiness, on the
     * phone, so a tired morning softens it without asking the model.
     */
    public void applyReadiness() {
        CoachLogic.Plan p = todayPlan();
        if (p == null || p.rest || todayDone()) {
            return;
        }
        CoachLogic.Readiness r = readiness();
        if (r.level != CoachLogic.Readiness.REST && r.level != CoachLogic.Readiness.EASY) {
            return;
        }
        int before = p.adjusted.size();
        double[] loads = CoachLogic.recentLoads(sessions(), System.currentTimeMillis(),
                ZoneId.systemDefault());
        CoachLogic.guard(p, settings(), r, loads[1], loads[0], Prefs.SPORTS);
        if (p.adjusted.size() > before) {
            savePlan(p, "softened after the morning test");
        }
    }

    public void refreshPlan(String reason, Callback<CoachLogic.Plan> cb) {
        inFlight.incrementAndGet();
        worker.execute(() -> {
            CoachLogic.Plan p = null;
            String error = null;
            try {
                p = planNow(reason);
            } catch (Llm.ApiException e) {
                error = e.getMessage();
            }
            inFlight.decrementAndGet();
            deliver(cb, p, error);
        });
    }

    /**
     * "Harder": the model gets to design it, but the result must really be harder (and not
     * much longer) after the phone's checks; otherwise the main step simply moves up a zone.
     * The callback's error is set only when neither works (e.g. the main step is already
     * zone 4, or readiness keeps today easy).
     */
    public void harder(CoachLogic.Plan current, Callback<CoachLogic.Plan> cb) {
        inFlight.incrementAndGet();
        worker.execute(() -> {
            CoachLogic.Plan p = null;
            String error = null;
            CoachLogic.Plan rest = symptomRest();
            if (rest != null) {
                inFlight.decrementAndGet();
                deliver(cb, rest, null);
                return;
            }
            try {
                p = planNow(CoachLogic.harderReason(current), null);
                if (!CoachLogic.isHarder(current, p)) {
                    Log.i(TAG, "model's harder plan wasn't harder; using the fallback");
                    p = null;
                }
            } catch (Llm.ApiException e) {
                Log.w(TAG, "harder via model failed: " + e.getMessage());
            }
            if (p == null) {
                CoachLogic.Plan fallback = CoachLogic.harderFallback(current);
                if (fallback != null) {
                    List<CoachLogic.SessionInfo> sessions = sessions();
                    double[] loads = CoachLogic.recentLoads(sessions, System.currentTimeMillis(),
                            ZoneId.systemDefault());
                    CoachLogic.guard(fallback, settings(), readiness(), loads[1], loads[0],
                            Prefs.SPORTS);
                    if (CoachLogic.isHarder(current, fallback)) {
                        p = fallback;
                    }
                }
            }
            if (p == null) {
                savePlan(current);      // planNow may have stored a not-harder plan
                error = "Can't make today harder: it is already as hard as the plan allows, "
                        + "or your resting heart rate says to keep it easy.";
            } else {
                savePlan(p, "tapped Harder");
            }
            inFlight.decrementAndGet();
            deliver(cb, p, error);
        });
    }

    /** Same as {@link #refreshPlan} on the calling (background) thread, for the morning job. */
    CoachLogic.Plan planNow(String reason) throws Llm.ApiException {
        return planNow(reason, "asked the coach for a new plan");
    }

    /** {@code change} goes in the plan's history when today already had a plan; null: nothing. */
    private CoachLogic.Plan planNow(String reason, String change) throws Llm.ApiException {
        CoachLogic.Plan rest = symptomRest();
        if (rest != null) {
            return rest;
        }
        String key = key();
        if (key == null) {
            throw new Llm.ApiException(401, "Add your API key in Coach settings");
        }
        CoachLogic.Settings s = settings();
        List<CoachLogic.SessionInfo> sessions = sessionsWithBreaks();
        CoachLogic.Readiness r = readiness();
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        Map<String, Object> brief = CoachLogic.brief(s, sessions, tests(), r, storedPlan(),
                extras(), now, zone);
        Llm.Request request = new Llm.Request(CoachLogic.SYSTEM,
                CoachLogic.planPrompt(brief, reason), PLAN_TOKENS);
        Map<String, Object> answer = call(key, request);
        CoachLogic.Plan p = CoachLogic.parsePlan(answer, today());
        if (p == null) {
            throw new Llm.ApiException(0, "The coach's plan could not be read");
        }
        double[] loads = CoachLogic.recentLoads(sessions, now, zone);
        CoachLogic.guard(p, s, r, loads[1], loads[0], Prefs.SPORTS);
        savePlan(p, change);
        if (brief.containsKey("hrMaxCheck") && s.hrMaxRaised.isEmpty()) {
            prefs.edit().putString("coachHrMaxRaised", today() + "|" + s.hrMax).apply();
        }
        Log.i(TAG, "coach plan: " + p.title + " " + p.totalMinutes() + " min, adjusted "
                + p.adjusted);
        return p;
    }

    /**
     * A rest day, saved without asking the model, when a session in the last two days has
     * notes mentioning a red-flag symptom; null otherwise.
     */
    private CoachLogic.Plan symptomRest() {
        if (!CoachLogic.recentRedFlag(sessions(), System.currentTimeMillis())) {
            return null;
        }
        CoachLogic.Plan p = CoachLogic.restPlan(today(), "Your recent session notes mention a "
                + "symptom. " + CoachLogic.RED_FLAG_REPLY);
        savePlan(p, "rest after a symptom in their session notes");
        return p;
    }

    // -------------------------------------------------------------------- ask

    public void ask(String text, Callback<CoachLogic.Ask> cb) {
        addChat("you", text);
        if (CoachLogic.redFlag(text)) {
            CoachLogic.Ask a = new CoachLogic.Ask();
            a.reply = CoachLogic.RED_FLAG_REPLY;
            a.plan = CoachLogic.restPlan(today(), "Rest after reporting a symptom.");
            replacePlan(a.plan, "reported a symptom in the chat");
            addChat("coach", a.reply);
            main.post(() -> cb.done(a, null));
            return;
        }
        inFlight.incrementAndGet();
        worker.execute(() -> {
            CoachLogic.Ask a = null;
            String error = null;
            try {
                String key = key();
                if (key == null) {
                    throw new Llm.ApiException(401, "Add your API key first");
                }
                CoachLogic.Settings s = settings();
                List<CoachLogic.SessionInfo> sessions = sessionsWithBreaks();
                CoachLogic.Readiness r = readiness();
                long now = System.currentTimeMillis();
                ZoneId zone = ZoneId.systemDefault();
                Map<String, Object> brief = CoachLogic.brief(s, sessions, tests(), r,
                        todayPlan(), extras(), now, zone);
                List<String[]> history = chat();
                if (!history.isEmpty()) {
                    history = history.subList(0, history.size() - 1);  // drop this message
                }
                // A message that may change training gets the reasoning pass; a plain
                // question gets the quick answer.
                boolean thinking = CoachLogic.needsThinking(text);
                Llm.Request request = new Llm.Request(CoachLogic.SYSTEM,
                        CoachLogic.askPrompt(brief, history.subList(
                                Math.max(0, history.size() - CHAT_TURNS), history.size()), text),
                        thinking ? ASK_TOKENS : ASK_FAST_TOKENS, thinking);
                a = CoachLogic.parseAsk(call(key, request), today());
                if (a.plan != null) {
                    double[] loads = CoachLogic.recentLoads(sessions, now, zone);
                    CoachLogic.guard(a.plan, s, r, loads[1], loads[0], Prefs.SPORTS);
                    savePlan(a.plan, "changed it in the coach chat");
                }
                applySettings(a);
                remember(a.remember);
                if (a.reply.isEmpty()) {
                    a.reply = a.plan != null ? "Done \u2014 today's plan is updated." : "OK.";
                }
                addChat("coach", a.reply);
            } catch (Llm.ApiException e) {
                error = e.getMessage();
            }
            inFlight.decrementAndGet();
            deliver(cb, a, error);
        });
    }

    /**
     * "Why this plan?" without a request: today's plan already carries the coach's reasons, so
     * it is read back (with anything the phone changed) as a conversation turn.
     *
     * @return false when there is no plan to explain, so the question should go to the coach
     */
    public boolean explainPlan(String question) {
        CoachLogic.Plan p = todayPlan();
        if (p == null || p.message.isEmpty()) {
            return false;
        }
        StringBuilder sb = new StringBuilder(p.message);
        for (String a : p.adjusted) {
            sb.append("\n\n").append(a).append('.');
        }
        addChat("you", question);
        addChat("coach", sb.toString());
        return true;
    }

    /** About how far today's plan goes at their usual pace, in km (0: not known). */
    public double estimatedKm(CoachLogic.Plan p) {
        return CoachLogic.estimateKm(p, sessions(), System.currentTimeMillis());
    }

    private void applySettings(CoachLogic.Ask a) {
        SharedPreferences.Editor e = prefs.edit();
        if (a.daysPerWeek > 0) {
            e.putInt("coachDays", a.daysPerWeek);
        }
        if (a.minutes > 0) {
            e.putInt("coachMinutes", a.minutes);
        }
        if (a.goal != null) {
            e.putString("coachGoal", a.goal);
        }
        e.apply();
    }

    // ---------------------------------------------------------------- debrief

    public void debrief(SessionStore.Summary s, Callback<CoachLogic.Debrief> cb) {
        worker.execute(() -> {
            CoachLogic.Debrief d = null;
            String error = null;
            try {
                String key = key();
                if (key == null) {
                    throw new Llm.ApiException(401, "No API key");
                }
                List<CoachLogic.SessionInfo> sessions = sessionsWithBreaks();
                List<CoachLogic.SessionInfo> history = new ArrayList<>();
                CoachLogic.SessionInfo self = null;
                for (CoachLogic.SessionInfo si : sessions) {
                    if (si.start == s.id) {
                        self = si;
                    } else {
                        history.add(si);
                    }
                }
                if (self == null) {
                    SessionStore.backfill(appContext, s);
                    self = info(s);
                }
                List<CoachLogic.SessionInfo> refs = new ArrayList<>();
                Map<String, Object> brief = CoachLogic.brief(settings(), history, tests(),
                        readiness(), null, extras(),
                        System.currentTimeMillis(), ZoneId.systemDefault(), refs);
                CoachLogic.Plan plan = s.planTitle.isEmpty() ? null : storedPlan();
                Llm.Request request = new Llm.Request(CoachLogic.SYSTEM,
                        CoachLogic.debriefPrompt(brief, self, plan), DEBRIEF_TOKENS);
                // Stored with the debrief: which sessions its question is about (the phone
                // works them out from the refs), and whether it had the talk test answer.
                Map<String, Object> answer = new java.util.LinkedHashMap<>(call(key, request));
                answer.put("askIds", new ArrayList<Object>(CoachLogic.askTargets(
                        CoachLogic.parseDebrief(answer).askRefs, refs)));
                answer.put("usedTalk", self.talk);
                d = CoachLogic.parseDebrief(answer);
                SessionStore.Summary fresh = SessionStore.load(appContext, s.id);
                if (fresh != null) {
                    fresh.coachNote = Json.write(answer);
                    SessionStore.save(appContext, fresh);
                }
            } catch (Llm.ApiException e) {
                error = e.getMessage();
            }
            deliver(cb, d, error);
        });
    }

    // ------------------------------------------------------------------- chat

    public List<String[]> chat() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("coachChat", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                // date and hrMax: when it was written, and the zones its numbers came from
                out.add(new String[]{o.getString("from"), o.getString("text"),
                        o.optString("date", ""),
                        o.has("hrMax") ? String.valueOf(o.optInt("hrMax")) : ""});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private synchronized void addChat(String from, String text) {
        List<String[]> all = chat();
        all.add(new String[]{from, text, today(), String.valueOf(Prefs.hrMax(appContext))});
        JSONArray a = new JSONArray();
        for (String[] t : all.subList(Math.max(0, all.size() - MAX_CHAT), all.size())) {
            try {
                JSONObject turn = new JSONObject().put("from", t[0]).put("text", t[1]);
                if (!t[2].isEmpty()) {
                    turn.put("date", t[2]);
                }
                if (!t[3].isEmpty()) {
                    turn.put("hrMax", Integer.parseInt(t[3]));
                }
                a.put(turn);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString("coachChat", a.toString()).apply();
    }

    /** The last request body sent to the AI service, exactly (no key in it), or null. */
    public String lastRequest() {
        return prefs.getString("coachLastRequest", null);
    }

    // ---------------------------------------------------------------- reports

    /**
     * Keeps a "Report this answer" on the phone: what was said, what was asked, why it was
     * flagged, and which service and model wrote it. Nothing leaves the phone from here.
     */
    public void report(String kind, String reason, String question, String answer) {
        prefs.edit().putString("coachReports", Reports.add(prefs.getString("coachReports", ""),
                System.currentTimeMillis(), kind, reason,
                hasKey() ? provider().label : "", hasKey() ? model() : "", question, answer))
                .apply();
    }

    public int reportCount() {
        return Reports.count(prefs.getString("coachReports", ""));
    }

    /** All saved reports as the text of an email, or just the newest one. */
    public String reportsEmailBody(boolean onlyNewest) {
        String all = prefs.getString("coachReports", "");
        return Reports.emailBody(onlyNewest ? Reports.last(all) : all);
    }

    public void clearReports() {
        prefs.edit().remove("coachReports").apply();
    }

    /**
     * Deletes the plan and its history, the conversation, what it remembers, the check-ins,
     * the request log, the saved reports and every session debrief.
     */
    public void forget() {
        prefs.edit().remove("coachReports").remove("coachPlan").remove("coachChat")
                .remove("coachLastRequest")
                .remove("coachDone").remove("coachPlanLog").remove("coachRemember")
                .remove("coachCheckIns").remove("coachHrMaxRaised").apply();
        for (SessionStore.Summary s : SessionStore.list(appContext)) {
            if (!s.coachNote.isEmpty()) {
                s.coachNote = "";
                SessionStore.save(appContext, s);
            }
        }
    }

    // ------------------------------------------------------------------- data

    /**
     * One request to the key's service. If the service says the chosen model is gone (404),
     * the model is picked again from its list and the request is retried once.
     */
    private Map<String, Object> call(String key, Llm.Request request) throws Llm.ApiException {
        Llm api = client(key);
        try {
            return send(api, key, request);
        } catch (Llm.ApiException e) {
            if (e.code != 404 || prefs.getString("coachModel", "").isEmpty()) {
                throw e;
            }
            prefs.edit().remove("coachModel").apply();
            Llm again = client(key);
            if (again.model().equals(api.model())) {
                throw e;
            }
            return send(again, key, request);
        }
    }

    /** Sends, and keeps the exact body (no key in it) for "See the last message sent". */
    private Map<String, Object> send(Llm api, String key, Llm.Request request)
            throws Llm.ApiException {
        try {
            return api.ask(key, request);
        } finally {
            String body = api.lastBody;
            if (body != null) {
                prefs.edit().putString("coachLastRequest", body).apply();
            }
        }
    }

    /**
     * Finished sessions as the coach sees them. Older sessions may lack their breaks: use
     * {@link #sessionsWithBreaks} where the model reads them.
     */
    List<CoachLogic.SessionInfo> sessions() {
        return sessions(false);
    }

    /** {@link #sessions}, with breaks filled in for older sessions. Reads sample files: worker only. */
    private List<CoachLogic.SessionInfo> sessionsWithBreaks() {
        return sessions(true);
    }

    private List<CoachLogic.SessionInfo> sessions(boolean backfill) {
        List<CoachLogic.SessionInfo> out = new ArrayList<>();
        for (SessionStore.Summary s : SessionStore.list(appContext)) {
            if (!s.recording) {
                if (backfill) {
                    SessionStore.backfill(appContext, s);
                }
                out.add(info(s));
            }
        }
        return out;
    }

    private static CoachLogic.SessionInfo info(SessionStore.Summary s) {
        CoachLogic.SessionInfo si = new CoachLogic.SessionInfo();
        si.start = s.id;
        si.sport = s.sport;
        si.activeMs = s.activeMs;
        si.distanceM = s.gps ? s.distanceM : 0;
        si.avgHr = s.avgHr;
        si.maxHr = s.maxHr;
        si.zoneMs = s.zoneMs.clone();
        si.trimp = s.trimp;
        si.feeling = s.feeling;
        si.notes = s.notes;
        si.planTitle = s.planTitle;
        si.targetPct = s.targetPct;
        si.segments = s.segments;
        si.pauses = s.pauses;
        si.splits = s.splits;
        si.parts = s.parts;
        si.stepResults = s.stepResults;
        si.talk = s.talk;
        si.hrMax = s.hrMax;
        if (!s.coachNote.isEmpty()) {
            try {
                si.coachNext = CoachLogic.parseDebrief(Json.parseObject(s.coachNote)).next;
            } catch (Json.ParseException ignored) {
            }
        }
        return si;
    }

    List<CoachLogic.Test> tests() {
        return Prefs.fitnessTests(appContext);
    }

    /**
     * A heart rate at which their talk test answers say they can talk in full sentences, or 0:
     * easy steps do not beep below it. See {@link CoachLogic#conversationalBpm}.
     */
    public int conversationalBpm() {
        return CoachLogic.conversationalBpm(sessions(), System.currentTimeMillis());
    }

    public CoachLogic.Readiness readiness() {
        return CoachLogic.readiness(tests(), HealthSync.daysForCoach(appContext),
                System.currentTimeMillis());
    }

    private <T> void deliver(Callback<T> cb, T result, String error) {
        if (cb != null) {
            main.post(() -> cb.done(error == null ? result : null, error));
        }
    }

    // --------------------------------------------------------------- schedule

    /**
     * Schedules the morning plan only when no job is waiting. Called whenever the app opens:
     * the job is otherwise only (re)made by the setup screen and by the job itself, so one
     * lost job (an update, a force stop) would silence the morning plan for good.
     */
    public void scheduleIfMissing() {
        JobScheduler js = (JobScheduler) appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null && js.getPendingJob(JOB_MORNING) == null) {
            schedule();
        }
    }

    /**
     * Schedules the morning plan: a job that waits until the chosen time and for a network,
     * then plans the day and posts it as a notification. Survives reboots.
     */
    public void schedule() {
        JobScheduler js = (JobScheduler) appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        int minute = morningMinute();
        if (!isEnabled() || minute < 0) {
            js.cancel(JOB_MORNING);
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        LocalDateTime at = now.toLocalDate().atTime(minute / 60, minute % 60);
        ZonedDateTime next = at.atZone(now.getZone());
        if (!next.isAfter(now.plusMinutes(1))) {
            next = next.plusDays(1);
        }
        long delay = next.toInstant().toEpochMilli() - System.currentTimeMillis();
        JobInfo job = new JobInfo.Builder(JOB_MORNING,
                new ComponentName(appContext, CoachJob.class))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 2 * 3600 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        try {
            js.schedule(job);
            Log.i(TAG, "coach morning job in " + delay / 60000 + " min");
        } catch (RuntimeException e) {
            // A morning notification is a nicety; never let it take the app down.
            Log.w(TAG, "cannot schedule morning plan", e);
        }
    }
}
