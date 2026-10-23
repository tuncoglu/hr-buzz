package com.emre.hrbuzz;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Records a run on the watch: one sample a second of heart rate and GPS position, straight to
 * disk in the phone app's format. The lean counterpart of {@link Recorder}, without coach
 * plans, voice, walk breaks or RR intervals. Everything runs on the main thread.
 */
public class WearRecorder {

    private static final String TAG = "HRBuzz";

    public static final int IDLE = 0;
    public static final int RUNNING = 1;
    public static final int PAUSED = 2;

    /** Sessions shorter than this are dropped, like on the phone. */
    private static final long MIN_KEEP_MS = 10000;
    private static final long FIX_FRESH_MS = 5000;
    private static final float MAX_ACCURACY_M = 25;
    private static final double MAX_SPEED_MS = 30;
    /**
     * When a step lowers the upper limit (into a recovery or cool-down), heart rate takes a
     * minute or more to come down: keep the previous limit this long before tightening.
     */
    private static final long STEP_GRACE_MS = 90000;

    private static WearRecorder instance;

    public static synchronized WearRecorder get(Context context) {
        if (instance == null) {
            instance = new WearRecorder(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final WristHr hr;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int state = IDLE;
    private long lastSavedId = -1;
    private SessionStore.Summary session;
    private Training.Stats stats;
    private Writer out;

    // coached workout
    private CoachLogic.Plan plan;
    private int stepIndex = -1;
    private long lastActiveMs;
    private long inTargetMs;
    private long targetTimedMs;
    private int pendingHigh;
    private long tightenAtMs = -1;

    // run alerts
    private final WearLogic.AutoPause autoPause = new WearLogic.AutoPause();
    private final WearLogic.ZoneWatch zoneWatch = new WearLogic.ZoneWatch();
    private boolean autoPaused;
    private double lastKmCheckM;

    // steps and cadence
    private long stepBase = -1;

    private LocationManager locationManager;
    private Location lastFix;
    private Location anchor;
    private double gpsDistance;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state == IDLE) {
                return;
            }
            if (state == RUNNING) {
                record();
            }
            autoPauseCheck();
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
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private WearRecorder(Context context) {
        this.appContext = context;
        this.hr = WristHr.get(context);
        SessionStore.recover(context, -1);
    }

    // ---------------------------------------------------------------- public API

    public int getState() {
        return state;
    }

    public boolean isRecording() {
        return state != IDLE;
    }

    public SessionStore.Summary getSession() {
        return session;
    }

    public Training.Stats getStats() {
        return stats;
    }

    /** Id of the session most recently finished and kept, or -1. */
    public long getLastSavedId() {
        return lastSavedId;
    }

    public boolean usesGps() {
        return session != null && session.gps;
    }

    /** @return true when GPS is on and has a recent fix */
    public boolean hasFix() {
        return fresh(lastFix);
    }

    public void start(String sport) {
        start(sport, null);
    }

    /**
     * Starts recording; with a coach plan, also runs its steps: each step sets this session's
     * alert limits and buzzes when it starts.
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
        autoPause.reset();
        autoPaused = false;
        lastKmCheckM = 0;
        zoneWatch.reset();
        stepBase = -1;
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
        SessionStore.save(appContext, session);
        try {
            out = new OutputStreamWriter(new FileOutputStream(
                    SessionStore.csv(appContext, session.id), true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "cannot open session file", e);
        }
        hr.setSessionRunning(true);
        if (!hr.isActive()) {
            hr.start();
        }
        StepSensor.get(appContext).start();
        StepSensor.get(appContext).resetAverage();
        if (session.gps) {
            startGps();
        }
        state = RUNNING;
        main.removeCallbacks(tick);
        main.post(tick);
        hr.tick();
        Log.i(TAG, "recording " + sport + " " + session.id);
    }

    public void pause() {
        if (state != RUNNING) {
            return;
        }
        autoPaused = false;     // a pause you asked for stays until you resume
        state = PAUSED;
        write("-");
        stats.pause();
        anchor = null;
        hr.setSessionRunning(false);
        hr.tick();
    }

    public void resume() {
        if (state != PAUSED) {
            return;
        }
        autoPaused = false;
        autoPause.reset();
        state = RUNNING;
        hr.setSessionRunning(true);
        hr.tick();
    }

    /**
     * Ends the session and saves it.
     *
     * @return its id, or -1 when it was too short to keep
     */
    public long stop() {
        if (state == IDLE) {
            return -1;
        }
        state = IDLE;
        main.removeCallbacks(tick);
        hr.setSessionRunning(false);
        hr.clearSessionLimits();
        stopGps();
        closeWriter();

        long id = session.id;
        boolean keep = stats.activeMs >= MIN_KEEP_MS;
        long stepsNow = StepSensor.get(appContext).total();
        int averageSpm = StepSensor.get(appContext).averageSpm();
        StepSensor.get(appContext).stop();
        if (keep) {
            session.apply(stats);
            if (stepBase >= 0 && stepsNow >= stepBase) {
                session.steps = (int) (stepsNow - stepBase);
                // Averaged over the time spent stepping, not the seconds stood still.
                session.cadence = averageSpm;
            }
            if (plan != null) {
                session.targetPct = targetTimedMs >= 60000
                        ? (int) Math.round(100.0 * inTargetMs / targetTimedMs) : -1;
                Coach.get(appContext).markDone();
            }
            session.end = System.currentTimeMillis();
            session.recording = false;
            SessionStore.save(appContext, session);
            if (stats.peakHr > Prefs.hrMaxSeen(appContext)) {
                Prefs.of(appContext).edit().putInt("hrMaxSeen", stats.peakHr)
                        .putLong("hrMaxSeenAt", id).apply();
            }
        } else {
            SessionStore.delete(appContext, id);
        }
        session = null;
        plan = null;
        lastSavedId = keep ? id : -1;
        return lastSavedId;
    }

    // ------------------------------------------------------------------ samples

    private void record() {
        if (stepBase < 0 && StepSensor.get(appContext).total() >= 0) {
            stepBase = StepSensor.get(appContext).total();
        }
        Training.Sample s = new Training.Sample();
        s.t = System.currentTimeMillis();
        s.bpm = hr.getBpm();
        if (fresh(lastFix)) {
            s.lat = lastFix.getLatitude();
            s.lon = lastFix.getLongitude();
            if (lastFix.hasAltitude()) {
                s.alt = lastFix.getAltitude();
            }
        }
        s.dist = gpsDistance;
        stats.add(s);
        write(s.toCsv());
        workoutTick(s);
        alertsTick(s);
    }

    /** @return true while this run is paused by auto-pause rather than by the person */
    public boolean isAutoPaused() {
        return autoPaused;
    }

    /** The GPS speed in m/s, or -1 without a fresh fix. */
    private double gpsSpeed() {
        return fresh(lastFix) && lastFix.hasSpeed() ? lastFix.getSpeed() : -1;
    }

    /** Auto-pause: needs GPS, so it only applies to GPS sports. */
    private void autoPauseCheck() {
        if (!WearPrefs.autoPause(appContext) || session == null || !session.gps) {
            return;
        }
        if (state == PAUSED && !autoPaused) {
            return;         // paused by hand: only the person resumes
        }
        int action = autoPause.update(gpsSpeed(), System.currentTimeMillis(), autoPaused);
        if (action == WearLogic.AutoPause.PAUSE && state == RUNNING) {
            pause();
            autoPaused = true;      // after pause(), which clears it for a pause by hand
        } else if (action == WearLogic.AutoPause.RESUME && state == PAUSED) {
            resume();
        }
    }

    /** Km split and zone-change buzzes. The limit alarms always come first. */
    private void alertsTick(Training.Sample s) {
        if (WearLogic.kmCrossed(lastKmCheckM, stats.distanceM) && WearPrefs.kmBuzz(appContext)) {
            hr.kmBuzz();
        }
        lastKmCheckM = stats.distanceM;
        if (s.bpm > 0) {
            int before = zoneWatch.current();
            int zone = zoneWatch.feed(Training.zone(s.bpm, session.hrMax));
            if (zone != WearLogic.ZoneWatch.NONE && WearPrefs.zoneBuzz(appContext)
                    && !hr.isAbove() && !hr.isBelow()) {
                hr.zoneBuzz(zone > before);
            }
        }
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
            int[] band = CoachLogic.targetBand(plan.steps.get(idx), session.hrMax);
            targetTimedMs += dt;
            if (s.bpm >= band[0] && s.bpm <= band[1]) {
                inTargetMs += dt;
            }
        }
    }

    private void enterStep(int idx) {
        hr.chime();
        tightenAtMs = -1;
        if (idx >= plan.steps.size()) {
            hr.clearSessionLimits();
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
        Log.i(TAG, "step " + (idx + 1) + "/" + plan.steps.size() + " " + st.name + " limits "
                + limits[1] + "-" + limits[0]);
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
}
