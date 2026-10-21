package com.emre.hrbuzz;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records a training session: one sample a second of heart rate, RR intervals and GPS
 * position, straight to disk, plus live stats and voice guidance. Application scoped like
 * {@link HrManager}; everything runs on the main thread.
 */
public class Recorder implements HrManager.Listener {

    private static final String TAG = "HRBuzz";

    public static final int IDLE = 0;
    public static final int RUNNING = 1;
    public static final int PAUSED = 2;

    /** A reading older than this is not "current heart rate" any more. */
    private static final long HR_FRESH_MS = 3000;
    /** A fix older than this is not "current position" any more. */
    private static final long FIX_FRESH_MS = 5000;
    /** Fixes worse than this never move the distance on. */
    private static final float MAX_ACCURACY_M = 25;
    /** Faster than this between fixes is a GPS jump, not you. */
    private static final double MAX_SPEED_MS = 30;
    /** Spoken over/under alerts repeat at most this often (the beeps keep going). */
    private static final long SPOKEN_ALARM_MS = 30000;
    /**
     * When a step lowers the upper limit (into a recovery or cool-down), heart rate takes a
     * minute or more to come down: keep the previous limit this long before tightening.
     */
    private static final long STEP_GRACE_MS = 90000;

    public interface Listener {
        /** Once a second while recording, and on every state change. */
        void onRecorderUpdate();
    }

    private static Recorder instance;

    public static synchronized Recorder get(Context context) {
        if (instance == null) {
            instance = new Recorder(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final HrManager hr;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private int state = IDLE;
    private long lastSavedId = -1;
    private SessionStore.Summary session;
    private Training.Stats stats;
    private Writer out;
    private final List<Integer> pendingRr = new ArrayList<>();

    // GPS
    private LocationManager locationManager;
    private Location lastFix;
    private Location anchor;
    private double gpsDistance;

    // voice guidance
    private TextToSpeech tts;
    private boolean ttsReady;
    private AudioManager audio;
    private AudioFocusRequest focus;
    /** Last announced interval slot (activeMs / interval) and whole km. */
    private long voiceSlot;
    private int voiceKm;
    private int lastSpokenAlarm;
    private long lastSpokenAlarmAt;
    /** Said before the speech engine was ready (the first step's cue, typically). */
    private final List<String> pendingSpeech = new ArrayList<>();

    // coached workout
    private CoachLogic.Plan plan;
    private int stepIndex = -1;
    private long lastActiveMs;
    private long inTargetMs;
    private long targetTimedMs;
    private int pendingHigh;
    private long tightenAtMs = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state == IDLE) {
                return;
            }
            if (state == RUNNING) {
                record();
            }
            notifyListeners();
            // Align to wall-clock seconds so samples are evenly spaced.
            main.postAtTime(this, SystemClock.uptimeMillis() + 1000
                    - System.currentTimeMillis() % 1000);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            onFix(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private Recorder(Context context) {
        this.appContext = context;
        this.hr = HrManager.get(context);
        SessionStore.recover(context, -1);
    }

    // ---------------------------------------------------------------- public API

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public int getState() {
        return state;
    }

    public boolean isRecording() {
        return state != IDLE;
    }

    /** The session being recorded, or null. */
    public SessionStore.Summary getSession() {
        return session;
    }

    /** Id of the session most recently finished and kept, or -1. */
    public long getLastSavedId() {
        return lastSavedId;
    }

    public Training.Stats getStats() {
        return stats;
    }

    public boolean usesGps() {
        return session != null && session.gps;
    }

    /** Human readable GPS state for the training screen. */
    public String gpsStatus() {
        if (!usesGps()) {
            return "";
        }
        if (locationManager == null) {
            return "GPS unavailable";
        }
        if (!fresh(lastFix)) {
            return "Waiting for GPS\u2026";
        }
        return String.format(Locale.ROOT, "GPS \u00b1%d m", Math.round(lastFix.getAccuracy()));
    }

    /** Current heart rate, or -1 when the strap has nothing fresh. */
    public int currentBpm() {
        return hr.getBpm();
    }

    public void start(String sport) {
        start(sport, null);
    }

    /**
     * Starts recording; with a coach plan, also runs its steps: each step sets this session's
     * alert limits and is announced aloud, whatever the voice guidance setting.
     */
    public void start(String sport, CoachLogic.Plan coached) {
        if (state != IDLE) {
            return;
        }
        plan = coached != null && !coached.rest && !coached.steps.isEmpty() ? coached : null;
        stepIndex = -1;
        lastActiveMs = 0;
        inTargetMs = 0;
        targetTimedMs = 0;
        tightenAtMs = -1;
        Training.Profile p = Prefs.profile(appContext);
        session = new SessionStore.Summary();
        session.id = System.currentTimeMillis();
        session.sport = sport;
        session.gps = Prefs.usesGps(sport);
        session.recording = true;
        session.hrMax = p.hrMax;
        session.male = p.male;
        session.age = p.age;
        session.weight = (int) p.weightKg;
        session.planTitle = plan != null ? plan.title : "";
        stats = new Training.Stats(p);
        gpsDistance = 0;
        anchor = null;
        lastFix = null;
        pendingRr.clear();
        lastSpokenAlarm = HrLogic.Alarm.NONE;
        SessionStore.save(appContext, session);
        try {
            out = new OutputStreamWriter(new FileOutputStream(
                    SessionStore.csv(appContext, session.id), true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "cannot open session file", e);
        }

        hr.addListener(this);
        hr.setSessionRunning(true);
        if (!hr.isActive()) {
            hr.start();
        }
        if (session.gps) {
            startGps();
        }
        startVoice();
        state = RUNNING;
        main.removeCallbacks(tick);
        main.post(tick);
        Log.i(TAG, "recording " + sport + " " + session.id);
    }

    public void pause() {
        if (state != RUNNING) {
            return;
        }
        state = PAUSED;
        write("-");
        stats.pause();
        anchor = null;
        hr.setSessionRunning(false);
        say("Paused.");
        notifyListeners();
    }

    public void resume() {
        if (state != PAUSED) {
            return;
        }
        state = RUNNING;
        hr.setSessionRunning(true);
        say("Resumed.");
        notifyListeners();
    }

    /**
     * Ends the session and saves it.
     *
     * @return its id, or -1 when it was too short to keep (under 10 s, nothing to see)
     */
    public long stop() {
        if (state == IDLE) {
            return -1;
        }
        state = IDLE;
        main.removeCallbacks(tick);
        hr.removeListener(this);
        hr.setSessionRunning(false);
        hr.clearSessionLimits();
        stopGps();
        closeWriter();

        long id = session.id;
        boolean keep = stats.activeMs >= 10000;
        if (keep) {
            session.apply(stats);
            if (plan != null) {
                session.targetPct = targetTimedMs >= 60000
                        ? (int) Math.round(100.0 * inTargetMs / targetTimedMs) : -1;
                Coach.get(appContext).markDone();
            }
            session.end = System.currentTimeMillis();
            session.recording = false;
            SessionStore.save(appContext, session);
            if (stats.peakHr > Prefs.hrMaxSeen(appContext)) {
                // A new highest peak: Auto max heart rate (and the zones) follow it.
                Prefs.of(appContext).edit().putInt("hrMaxSeen", stats.peakHr)
                        .putLong("hrMaxSeenAt", id).apply();
            }
            say("Session saved. " + Training.announcement(stats.activeMs, stats.distanceM,
                    stats.avgSpeed(), session.gps, stats.avgHr(), 0)
                    .replace("Heart rate", "Average heart rate")
                    .replace("Pace", "Average pace"), true);
        } else {
            SessionStore.delete(appContext, id);
            stopVoice();
        }
        session = null;
        plan = null;
        lastSavedId = keep ? id : -1;
        notifyListeners();
        return lastSavedId;
    }

    // ------------------------------------------------------------------ samples

    private void record() {
        Training.Sample s = new Training.Sample();
        s.t = System.currentTimeMillis();
        s.bpm = hr.getBpm() > 0 && hr.lastSampleAge() <= HR_FRESH_MS ? hr.getBpm() : -1;
        if (fresh(lastFix)) {
            s.lat = lastFix.getLatitude();
            s.lon = lastFix.getLongitude();
            if (lastFix.hasAltitude()) {
                s.alt = lastFix.getAltitude();
            }
        }
        s.dist = gpsDistance;
        s.rr = new int[pendingRr.size()];
        for (int i = 0; i < s.rr.length; i++) {
            s.rr[i] = pendingRr.get(i);
        }
        pendingRr.clear();

        stats.add(s);
        write(s.toCsv());
        workoutTick(s);
        voiceCheck();
    }

    // ---------------------------------------------------------------- workout

    /** The coach plan this session is following, or null. */
    public CoachLogic.Plan getPlan() {
        return plan;
    }

    /** Index of the current step; steps.size() once the plan is done; -1 before it starts. */
    public int getStepIndex() {
        return stepIndex;
    }

    /** Moving time left in the current step, ms. */
    public long stepRemainingMs() {
        if (plan == null || stepIndex < 0 || stepIndex >= plan.steps.size()) {
            return 0;
        }
        return CoachLogic.stepStart(plan.steps, stepIndex + 1) - stats.activeMs;
    }

    /** Share of heart-rate time so far inside the plan's zones, or -1. */
    public int targetPct() {
        return targetTimedMs >= 10000 ? (int) Math.round(100.0 * inTargetMs / targetTimedMs) : -1;
    }

    private void workoutTick(Training.Sample s) {
        if (plan == null) {
            return;
        }
        long dt = stats.activeMs - lastActiveMs;
        lastActiveMs = stats.activeMs;
        int idx = CoachLogic.stepAt(plan.steps, stats.activeMs);
        if (idx != stepIndex) {
            stepIndex = idx;
            enterStep(idx);
        } else if (tightenAtMs >= 0 && stats.activeMs >= tightenAtMs) {
            tightenAtMs = -1;
            hr.setSessionLimits(Math.min(pendingHigh, hr.getUserThreshold()), hr.getLowLimit());
        }
        if (idx < plan.steps.size() && s.bpm > 0) {
            CoachLogic.Step st = plan.steps.get(idx);
            int lo = Training.zoneFloor(st.zoneLow, session.hrMax);
            int hi = st.zoneHigh >= 5 ? Integer.MAX_VALUE
                    : Training.zoneFloor(st.zoneHigh + 1, session.hrMax) - 1;
            targetTimedMs += dt;
            if (s.bpm >= lo && s.bpm <= hi) {
                inTargetMs += dt;
            }
        }
    }

    private void enterStep(int idx) {
        int n = plan.steps.size();
        hr.chime();
        tightenAtMs = -1;
        if (idx >= n) {
            hr.clearSessionLimits();
            sayAlways("Workout complete. Keep going if you like, or finish when you're ready.");
            notifyListeners();
            return;
        }
        CoachLogic.Step st = plan.steps.get(idx);
        int[] limits = CoachLogic.stepLimits(st, session.hrMax, hr.getUserThreshold());
        int current = hr.getThreshold();
        if (limits[0] < current) {
            pendingHigh = limits[0];
            tightenAtMs = stats.activeMs + STEP_GRACE_MS;
            hr.setSessionLimits(current, limits[1]);
        } else {
            hr.setSessionLimits(limits[0], limits[1]);
        }
        String zones = st.zoneLow == st.zoneHigh ? "zone " + st.zoneLow
                : "zones " + st.zoneLow + " to " + st.zoneHigh;
        sayAlways("Step " + (idx + 1) + " of " + n + ". " + st.name + ", " + zones + ", "
                + st.minutes + (st.minutes == 1 ? " minute. " : " minutes. ") + st.cue);
        Log.i(TAG, "step " + (idx + 1) + "/" + n + " " + st.name + " limits " + limits[1]
                + "-" + limits[0]);
    }

    private void write(String line) {
        if (out == null) {
            return;
        }
        try {
            out.write(line);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "write failed", e);
        }
    }

    private void closeWriter() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
    }

    // ---------------------------------------------------------------------- GPS

    private void startGps() {
        locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                    locationListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "GPS unavailable", e);
            locationManager = null;
        }
    }

    private void stopGps() {
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
            locationManager = null;
        }
    }

    private void onFix(Location fix) {
        lastFix = fix;
        if (state != RUNNING || !fix.hasAccuracy() || fix.getAccuracy() > MAX_ACCURACY_M) {
            return;
        }
        if (anchor == null) {
            anchor = fix;
            return;
        }
        double d = anchor.distanceTo(fix);
        double seconds = Math.max(1, (fix.getElapsedRealtimeNanos()
                - anchor.getElapsedRealtimeNanos()) / 1e9);
        if (d / seconds > MAX_SPEED_MS) {
            return;                 // a jump; keep the anchor and wait for sane fixes
        }
        // Only move on once the step is bigger than the noise, so standing at a crossing
        // does not slowly add distance. The anchor stays put until then, so nothing is lost.
        double noise = Math.max(3, Math.min(10, (anchor.getAccuracy() + fix.getAccuracy()) / 4));
        if (d >= noise) {
            gpsDistance += d;
            anchor = fix;
        }
    }

    private static boolean fresh(Location fix) {
        return fix != null && (SystemClock.elapsedRealtimeNanos()
                - fix.getElapsedRealtimeNanos()) / 1000000 <= FIX_FRESH_MS;
    }

    // -------------------------------------------------------------------- voice

    private void startVoice() {
        voiceSlot = 0;
        voiceKm = 0;
        if (tts != null) {
            return;
        }
        audio = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build();
        tts = new TextToSpeech(appContext, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setAudioAttributes(attrs);
                tts.setLanguage(Locale.UK);
                // After this callback, once the progress listener below is in place.
                main.post(() -> {
                    if (tts != null) {
                        for (String text : pendingSpeech) {
                            speak(text, "step");
                        }
                    }
                    pendingSpeech.clear();
                });
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        main.post(() -> {
                            if (audio != null && !tts.isSpeaking()) {
                                audio.abandonAudioFocusRequest(focus);
                            }
                            if ("last".equals(id) && state == IDLE) {
                                stopVoice();
                            }
                        });
                    }

                    @Override
                    public void onError(String id) {
                        onDone(id);
                    }
                });
            }
        });
    }

    private void stopVoice() {
        pendingSpeech.clear();
        if (tts != null) {
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
        if (audio != null && focus != null) {
            audio.abandonAudioFocusRequest(focus);
        }
    }

    private void voiceCheck() {
        int mode = Prefs.voice(appContext);
        if (mode == Prefs.VOICE_OFF) {
            return;
        }
        // Slots rather than "next due" times, so switching the interval mid-session works.
        boolean due;
        if (mode == Prefs.VOICE_PER_KM) {
            int km = (int) (stats.distanceM / 1000);
            due = session.gps && km > voiceKm;
            voiceKm = km;
        } else {
            long slot = stats.activeMs / (mode * 60000L);
            due = slot > voiceSlot;
            voiceSlot = slot;
        }
        if (due) {
            int bpm = currentBpm();
            say(Training.announcement(stats.activeMs, stats.distanceM, stats.speed(),
                    session.gps, bpm, Training.zone(bpm, session.hrMax)));
        }
    }

    private void say(String text) {
        say(text, false);
    }

    private void say(String text, boolean last) {
        if (Prefs.voice(appContext) == Prefs.VOICE_OFF || tts == null || !ttsReady) {
            if (last) {
                stopVoice();
            }
            return;
        }
        speak(text, last ? "last" : "guide");
    }

    /** Workout steps are spoken even with voice guidance off: they are the plan. */
    private void sayAlways(String text) {
        if (tts == null) {
            return;
        }
        if (!ttsReady) {
            pendingSpeech.add(text);
            return;
        }
        speak(text, "step");
    }

    private void speak(String text, String id) {
        audio.requestAudioFocus(focus);
        // Queue: an alarm and a scheduled announcement can land in the same second.
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
    }

    // ---------------------------------------------------------------- HrManager

    @Override
    public void onState(String state) {
    }

    @Override
    public void onBpm(int bpm, boolean above) {
    }

    @Override
    public void onConnected(boolean connected) {
    }

    @Override
    public void onSample(HrLogic.Measurement m) {
        if (state != RUNNING) {
            return;
        }
        for (int rr : m.rrMs) {
            pendingRr.add(rr);
        }
    }

    @Override
    public void onAlarm(int event, int bpm) {
        if (state != RUNNING || event == HrLogic.Alarm.BACK) {
            return;
        }
        // The beep repeats every 5 s; saying it that often would drown out everything else.
        long now = System.currentTimeMillis();
        if (event == lastSpokenAlarm && now - lastSpokenAlarmAt < SPOKEN_ALARM_MS) {
            return;
        }
        lastSpokenAlarm = event;
        lastSpokenAlarmAt = now;
        if (event == HrLogic.Alarm.OVER) {
            say("Heart rate " + bpm + ". Too high, ease off.");
        } else if (event == HrLogic.Alarm.UNDER) {
            say("Heart rate " + bpm + ". Too low, pick it up.");
        } else if (event == HrLogic.Alarm.LOST) {
            say("Heart rate signal lost. Check the strap.");
        }
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            l.onRecorderUpdate();
        }
    }
}
