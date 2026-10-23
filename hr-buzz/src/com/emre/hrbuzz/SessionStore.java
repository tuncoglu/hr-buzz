package com.emre.hrbuzz;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Sessions on disk, in the app's private files: {@code sessions/<id>.csv} holds the samples
 * (written as they are recorded, so a killed app loses at most a second), {@code <id>.json}
 * the summary and notes. The id is the start time in epoch millis.
 */
final class SessionStore {

    private static final String TAG = "HRBuzz";

    private SessionStore() {
    }

    static final class Summary {
        long id;
        String sport;
        boolean gps;
        /** Still being recorded, or the app died before it was finished. */
        boolean recording;
        long end;
        long activeMs;
        double distanceM;
        int avgHr;
        int maxHr;
        /** Highest heart rate held ~10 s; 0 when not yet computed (older sessions). */
        int peakHr;
        double kcal;
        long[] zoneMs = new long[6];
        double trimp;
        double bestSpeed;
        List<int[]> splits = new ArrayList<>();
        /** Moving stretches between breaks: {ms, average bpm, max bpm}. */
        List<long[]> segments = new ArrayList<>();
        /** Breaks: {gap ms, bpm before, bpm after}. */
        List<long[]> pauses = new ArrayList<>();
        /** False for sessions saved before breaks were kept: filled in from their samples. */
        boolean pausesKnown;
        // profile at the time, so a rebuild gives the same numbers after a settings change
        int hrMax;
        boolean male;
        int age;
        int weight;
        /** 0 = not rated, 1..5 = how it felt. */
        int feeling;
        String notes = "";
        /** Coached workout this followed, or "" for a free session. */
        String planTitle = "";
        /** Share of heart-rate time inside the plan's zones, or -1. */
        int targetPct = -1;
        /** The coach's debrief as JSON, or "". */
        String coachNote = "";
        /** Brought in from a file (e.g. a Polar Flow export) rather than recorded here. */
        boolean imported;

        Training.Profile profile() {
            return new Training.Profile(hrMax, male, age, weight);
        }

        void apply(Training.Stats s) {
            activeMs = s.activeMs;
            distanceM = s.distanceM;
            avgHr = s.avgHr();
            maxHr = s.maxHr;
            peakHr = s.peakHr;
            kcal = s.kcal;
            System.arraycopy(s.zoneMs, 0, zoneMs, 0, 6);
            trimp = s.trimp();
            bestSpeed = s.bestSpeed;
            splits = new ArrayList<>(s.splits);
            segments = s.segments();
            pauses = new ArrayList<>(s.pauses);
            pausesKnown = true;
        }

        double avgSpeed() {
            return activeMs > 0 ? distanceM / (activeMs / 1000.0) : 0;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id).put("sport", sport).put("gps", gps).put("recording", recording)
                    .put("end", end).put("activeMs", activeMs).put("distanceM", distanceM)
                    .put("avgHr", avgHr).put("maxHr", maxHr).put("peakHr", peakHr)
                    .put("kcal", kcal)
                    .put("trimp", trimp).put("bestSpeed", bestSpeed)
                    .put("hrMax", hrMax).put("male", male).put("age", age).put("weight", weight)
                    .put("feeling", feeling).put("notes", notes)
                    .put("planTitle", planTitle).put("targetPct", targetPct)
                    .put("coachNote", coachNote).put("imported", imported);
            JSONArray z = new JSONArray();
            for (long v : zoneMs) {
                z.put(v);
            }
            o.put("zoneMs", z);
            JSONArray sp = new JSONArray();
            for (int[] split : splits) {
                sp.put(new JSONArray().put(split[0]).put(split[1]));
            }
            o.put("splits", sp);
            o.put("segments", longRows(segments));
            o.put("pauses", longRows(pauses));
            return o;
        }

        private static JSONArray longRows(List<long[]> rows) throws JSONException {
            JSONArray a = new JSONArray();
            for (long[] r : rows) {
                JSONArray row = new JSONArray();
                for (long v : r) {
                    row.put(v);
                }
                a.put(row);
            }
            return a;
        }

        private static List<long[]> readRows(JSONArray a) throws JSONException {
            List<long[]> out = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONArray row = a.getJSONArray(i);
                long[] r = new long[row.length()];
                for (int k = 0; k < r.length; k++) {
                    r[k] = row.getLong(k);
                }
                out.add(r);
            }
            return out;
        }

        static Summary fromJson(JSONObject o) throws JSONException {
            Summary s = new Summary();
            s.id = o.getLong("id");
            s.sport = o.optString("sport", "Other");
            s.gps = o.optBoolean("gps");
            s.recording = o.optBoolean("recording");
            s.end = o.optLong("end");
            s.activeMs = o.optLong("activeMs");
            s.distanceM = o.optDouble("distanceM", 0);
            s.avgHr = o.optInt("avgHr");
            s.maxHr = o.optInt("maxHr");
            s.peakHr = o.optInt("peakHr");
            s.kcal = o.optDouble("kcal", 0);
            s.trimp = o.optDouble("trimp", 0);
            s.bestSpeed = o.optDouble("bestSpeed", 0);
            s.hrMax = o.optInt("hrMax", 180);
            s.male = o.optBoolean("male", true);
            s.age = o.optInt("age", 40);
            s.weight = o.optInt("weight", 75);
            s.feeling = o.optInt("feeling");
            s.notes = o.optString("notes", "");
            s.planTitle = o.optString("planTitle", "");
            s.targetPct = o.optInt("targetPct", -1);
            s.coachNote = o.optString("coachNote", "");
            s.imported = o.optBoolean("imported");
            s.pausesKnown = o.has("pauses");
            s.segments = readRows(o.optJSONArray("segments"));
            s.pauses = readRows(o.optJSONArray("pauses"));
            JSONArray z = o.optJSONArray("zoneMs");
            for (int i = 0; z != null && i < Math.min(6, z.length()); i++) {
                s.zoneMs[i] = z.getLong(i);
            }
            JSONArray sp = o.optJSONArray("splits");
            for (int i = 0; sp != null && i < sp.length(); i++) {
                JSONArray a = sp.getJSONArray(i);
                s.splits.add(new int[]{a.getInt(0), a.getInt(1)});
            }
            return s;
        }
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sessions");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File csv(Context c, long id) {
        return new File(dir(c), id + ".csv");
    }

    private static File json(Context c, long id) {
        return new File(dir(c), id + ".json");
    }

    static void save(Context c, Summary s) {
        File target = json(c, s.id);
        File tmp = new File(target.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(s.toJson().toString());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "save summary failed", e);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(target);
    }

    static Summary load(Context c, long id) {
        try {
            return Summary.fromJson(new JSONObject(read(json(c, id))));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** All sessions, newest first. */
    static List<Summary> list(Context c) {
        List<Summary> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            if (!f.getName().endsWith(".json")) {
                continue;
            }
            try {
                out.add(Summary.fromJson(new JSONObject(read(f))));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "unreadable session " + f, e);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.id, a.id));
        return out;
    }

    /** Samples in recorded order; a null entry marks a pause. */
    static List<Training.Sample> samples(Context c, long id) {
        List<Training.Sample> out = new ArrayList<>();
        File f = csv(c, id);
        if (!f.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals("-")) {
                    if (!out.isEmpty() && out.get(out.size() - 1) != null) {
                        out.add(null);
                    }
                    continue;
                }
                Training.Sample s = Training.Sample.parse(line);
                if (s != null) {
                    out.add(s);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "read samples failed", e);
        }
        return out;
    }

    /**
     * Sessions saved before breaks were kept get their segments, breaks and best speed from
     * their samples, once. Reads a file: call off the main thread.
     */
    static void backfill(Context c, Summary s) {
        if (s.pausesKnown || s.recording) {
            return;
        }
        Training.Stats st = replay(s, samples(c, s.id));
        s.segments = st.segments();
        s.pauses = new ArrayList<>(st.pauses);
        s.bestSpeed = st.bestSpeed;
        s.pausesKnown = true;
        save(c, s);
    }

    /** Replays the samples through {@link Training.Stats}, exactly as during the recording. */
    static Training.Stats replay(Summary s, List<Training.Sample> samples) {
        Training.Stats stats = new Training.Stats(s.profile());
        for (Training.Sample sample : samples) {
            if (sample == null) {
                stats.pause();
            } else {
                stats.add(sample);
            }
        }
        return stats;
    }

    /**
     * Finishes sessions left marked "recording" by an app that was killed mid-run, rebuilding
     * their summary from whatever samples reached the disk.
     */
    static void recover(Context c, long exceptId) {
        for (Summary s : list(c)) {
            if (!s.recording || s.id == exceptId) {
                continue;
            }
            List<Training.Sample> samples = samples(c, s.id);
            if (samples.isEmpty()) {
                delete(c, s.id);
                continue;
            }
            s.apply(replay(s, samples));
            for (int i = samples.size() - 1; i >= 0; i--) {
                if (samples.get(i) != null) {
                    s.end = samples.get(i).t;
                    break;
                }
            }
            s.recording = false;
            save(c, s);
            Log.i(TAG, "recovered unfinished session " + s.id);
        }
    }

    /**
     * The highest 10-second heart rate across sessions of the last 365 days, as
     * {bpm, session id}; {0, 0} when none. Sessions recorded before peaks were kept get theirs
     * worked out from their samples once, here. Reads files: call off the main thread.
     */
    static long[] peakHr(Context c) {
        long since = System.currentTimeMillis() - 365L * 86400000L;
        long[] best = {0, 0};
        for (Summary s : list(c)) {
            if (s.recording || s.id < since || s.avgHr <= 0) {
                continue;
            }
            if (s.peakHr == 0) {
                s.peakHr = Math.max(1, replay(s, samples(c, s.id)).peakHr);
                save(c, s);
            }
            if (s.peakHr > best[0]) {
                best[0] = s.peakHr;
                best[1] = s.id;
            }
        }
        return best;
    }

    /**
     * Saves an imported session like a recorded one, its stats worked out with the current
     * profile.
     *
     * @return the new summary, or null when a session starting at the same second exists
     */
    static Summary importSession(Context c, Import.Result r) throws IOException {
        long id = r.start;
        if (load(c, id) != null || csv(c, id).exists()) {
            return null;
        }
        Training.Profile p = Prefs.profile(c);
        Summary s = new Summary();
        s.id = id;
        s.sport = r.sport;
        s.gps = r.gps || r.fileMeters > 0;
        s.hrMax = p.hrMax;
        s.male = p.male;
        s.age = p.age;
        s.weight = (int) p.weightKg;
        s.notes = r.notes == null ? "" : r.notes;
        s.imported = true;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(csv(c, id)),
                StandardCharsets.UTF_8)) {
            for (Training.Sample x : r.samples) {
                w.write(x == null ? "-" : x.toCsv());
                w.write('\n');
            }
        }
        s.apply(replay(s, r.samples));
        for (int i = r.samples.size() - 1; i >= 0; i--) {
            if (r.samples.get(i) != null) {
                s.end = r.samples.get(i).t;
                break;
            }
        }
        save(c, s);
        return s;
    }

    static void delete(Context c, long id) {
        //noinspection ResultOfMethodCallIgnored
        csv(c, id).delete();
        //noinspection ResultOfMethodCallIgnored
        json(c, id).delete();
    }

    /**
     * Writes a TCX or GPX export to {@code files/exports} for sharing through
     * {@link ExportProvider}.
     *
     * @return the file, or null when there is nothing to export in that format
     */
    static File export(Context c, Summary s, String format) {
        List<Training.Sample> samples = samples(c, s.id);
        String body = "gpx".equals(format)
                ? Export.gpx(s.sport, s.id, samples)
                : Export.tcx(s.sport, s.id, samples, replay(s, samples), s.notes);
        if (body == null) {
            return null;
        }
        File dir = new File(c.getFilesDir(), "exports");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        for (int i = 0; old != null && i < old.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            old[i].delete();
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(new Date(s.id));
        File out = new File(dir, "hrbuzz_" + stamp + "_" + s.sport.toLowerCase(Locale.ROOT)
                + "." + format);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(body);
            return out;
        } catch (IOException e) {
            Log.w(TAG, "export failed", e);
            return null;
        }
    }

    private static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return new String(data, 0, off, StandardCharsets.UTF_8);
        }
    }
}
