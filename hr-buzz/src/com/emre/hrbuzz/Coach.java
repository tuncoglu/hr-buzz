package com.emre.hrbuzz;

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
 * Android's keystore), today's plan, the conversation, and the calls to DeepSeek. Every plan
 * goes through {@link CoachLogic#guard} before anything can run it. Application scoped.
 */
public class Coach {

    private static final String TAG = "HRBuzz";
    private static final String KEY_ALIAS = "hrbuzz_coach_api_key";
    static final int JOB_MORNING = 7;
    private static final int MAX_CHAT = 30;

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
    private final DeepSeek api = new DeepSeek();
    /** Requests in flight; the UI shows "Updating" and ignores taps while above zero. */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    private Coach(Context context) {
        this.appContext = context;
        this.prefs = Prefs.of(context);
    }

    // --------------------------------------------------------------- settings

    /** Turned on, set up and holding a key: the coach may talk to DeepSeek. */
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
        s.ceiling = HrManager.get(appContext).getUserThreshold();
        return s;
    }

    /** Minutes after midnight for the morning plan; -1 for no morning notification. */
    public int morningMinute() {
        return prefs.getInt("coachMorning", 7 * 60 + 30);
    }

    // -------------------------------------------------------------------- key

    public boolean hasKey() {
        return prefs.getString("coachKey", null) != null;
    }

    /**
     * Encrypts and stores the key, then reads it back to be sure.
     *
     * @return false when it could not be stored (nothing changes then)
     */
    public boolean setKey(String key) {
        key = key == null ? "" : key.trim();
        if (key.isEmpty()) {
            prefs.edit().remove("coachKey").apply();
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
        prefs.edit().putString("coachPlan", Json.write(CoachLogic.planToMap(p))).apply();
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
        p.date = today();
        savePlan(p);
    }

    public void refreshPlan(String reason, Callback<CoachLogic.Plan> cb) {
        inFlight.incrementAndGet();
        worker.execute(() -> {
            CoachLogic.Plan p = null;
            String error = null;
            try {
                p = planNow(reason);
            } catch (DeepSeek.ApiException e) {
                error = e.getMessage();
            }
            inFlight.decrementAndGet();
            deliver(cb, p, error);
        });
    }

    /**
     * "Harder": the model gets to design it, but the result must really be harder (and not
     * much longer) after the phone's checks; otherwise the main step simply moves up a zone.
     * The callback's error is set only when neither works (e.g. this week is at its limit).
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
                p = planNow(CoachLogic.harderReason(current));
                if (!CoachLogic.isHarder(current, p)) {
                    Log.i(TAG, "model's harder plan wasn't harder; using the fallback");
                    p = null;
                }
            } catch (DeepSeek.ApiException e) {
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
                error = "Can't make today harder: this week is already near its safe limit, "
                        + "or today is as hard as the plan allows.";
            } else {
                savePlan(p);
            }
            inFlight.decrementAndGet();
            deliver(cb, p, error);
        });
    }

    /** Same as {@link #refreshPlan} on the calling (background) thread, for the morning job. */
    CoachLogic.Plan planNow(String reason) throws DeepSeek.ApiException {
        CoachLogic.Plan rest = symptomRest();
        if (rest != null) {
            return rest;
        }
        String key = key();
        if (key == null) {
            throw new DeepSeek.ApiException(401, "Add your DeepSeek API key in Coach settings");
        }
        CoachLogic.Settings s = settings();
        List<CoachLogic.SessionInfo> sessions = sessions();
        CoachLogic.Readiness r = readiness();
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        Map<String, Object> brief = CoachLogic.brief(s, sessions, tests(), r, storedPlan(),
                HealthSync.forCoach(appContext), now, zone);
        String body = DeepSeek.body(CoachLogic.SYSTEM, CoachLogic.planPrompt(brief, reason), 1500);
        Map<String, Object> answer = call(key, body);
        CoachLogic.Plan p = CoachLogic.parsePlan(answer, today());
        if (p == null) {
            throw new DeepSeek.ApiException(0, "The coach's plan could not be read");
        }
        double[] loads = CoachLogic.recentLoads(sessions, now, zone);
        CoachLogic.guard(p, s, r, loads[1], loads[0], Prefs.SPORTS);
        savePlan(p);
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
        savePlan(p);
        return p;
    }

    // -------------------------------------------------------------------- ask

    public void ask(String text, Callback<CoachLogic.Ask> cb) {
        addChat("you", text);
        if (CoachLogic.redFlag(text)) {
            CoachLogic.Ask a = new CoachLogic.Ask();
            a.reply = CoachLogic.RED_FLAG_REPLY;
            a.plan = CoachLogic.restPlan(today(), "Rest after reporting a symptom.");
            replacePlan(a.plan);
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
                    throw new DeepSeek.ApiException(401, "Add your DeepSeek API key first");
                }
                CoachLogic.Settings s = settings();
                List<CoachLogic.SessionInfo> sessions = sessions();
                CoachLogic.Readiness r = readiness();
                long now = System.currentTimeMillis();
                ZoneId zone = ZoneId.systemDefault();
                Map<String, Object> brief = CoachLogic.brief(s, sessions, tests(), r,
                        todayPlan(), HealthSync.forCoach(appContext), now, zone);
                List<String[]> history = chat();
                if (!history.isEmpty()) {
                    history = history.subList(0, history.size() - 1);  // drop this message
                }
                String body = DeepSeek.body(CoachLogic.SYSTEM,
                        CoachLogic.askPrompt(brief, history.subList(
                                Math.max(0, history.size() - 10), history.size()), text), 1500);
                a = CoachLogic.parseAsk(call(key, body), today());
                if (a.plan != null) {
                    double[] loads = CoachLogic.recentLoads(sessions, now, zone);
                    CoachLogic.guard(a.plan, s, r, loads[1], loads[0], Prefs.SPORTS);
                    savePlan(a.plan);
                }
                applySettings(a);
                if (a.reply.isEmpty()) {
                    a.reply = a.plan != null ? "Done \u2014 today's plan is updated." : "OK.";
                }
                addChat("coach", a.reply);
            } catch (DeepSeek.ApiException e) {
                error = e.getMessage();
            }
            inFlight.decrementAndGet();
            deliver(cb, a, error);
        });
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
                    throw new DeepSeek.ApiException(401, "No DeepSeek API key");
                }
                List<CoachLogic.SessionInfo> sessions = sessions();
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
                    self = info(s);
                }
                Map<String, Object> brief = CoachLogic.brief(settings(), history, tests(),
                        readiness(), null, HealthSync.forCoach(appContext),
                        System.currentTimeMillis(), ZoneId.systemDefault());
                CoachLogic.Plan plan = s.planTitle.isEmpty() ? null : storedPlan();
                String body = DeepSeek.body(CoachLogic.SYSTEM,
                        CoachLogic.debriefPrompt(brief, self, plan), 600);
                Map<String, Object> answer = call(key, body);
                d = CoachLogic.parseDebrief(answer);
                SessionStore.Summary fresh = SessionStore.load(appContext, s.id);
                if (fresh != null) {
                    fresh.coachNote = Json.write(answer);
                    SessionStore.save(appContext, fresh);
                }
            } catch (DeepSeek.ApiException e) {
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
                out.add(new String[]{o.getString("from"), o.getString("text")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private synchronized void addChat(String from, String text) {
        List<String[]> all = chat();
        all.add(new String[]{from, text});
        JSONArray a = new JSONArray();
        for (String[] t : all.subList(Math.max(0, all.size() - MAX_CHAT), all.size())) {
            try {
                a.put(new JSONObject().put("from", t[0]).put("text", t[1]));
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString("coachChat", a.toString()).apply();
    }

    /** The last request body sent to DeepSeek, exactly (no key in it), or null. */
    public String lastRequest() {
        return prefs.getString("coachLastRequest", null);
    }

    /** Deletes the plan, the conversation, the request log and every session debrief. */
    public void forget() {
        prefs.edit().remove("coachPlan").remove("coachChat").remove("coachLastRequest")
                .remove("coachDone").apply();
        for (SessionStore.Summary s : SessionStore.list(appContext)) {
            if (!s.coachNote.isEmpty()) {
                s.coachNote = "";
                SessionStore.save(appContext, s);
            }
        }
    }

    // ------------------------------------------------------------------- data

    private Map<String, Object> call(String key, String body) throws DeepSeek.ApiException {
        prefs.edit().putString("coachLastRequest", body).apply();
        return api.ask(key, body);
    }

    List<CoachLogic.SessionInfo> sessions() {
        List<CoachLogic.SessionInfo> out = new ArrayList<>();
        for (SessionStore.Summary s : SessionStore.list(appContext)) {
            if (!s.recording) {
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
        return si;
    }

    List<CoachLogic.Test> tests() {
        return Prefs.fitnessTests(appContext);
    }

    public CoachLogic.Readiness readiness() {
        return CoachLogic.readiness(tests(), System.currentTimeMillis());
    }

    private <T> void deliver(Callback<T> cb, T result, String error) {
        if (cb != null) {
            main.post(() -> cb.done(error == null ? result : null, error));
        }
    }

    // --------------------------------------------------------------- schedule

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
