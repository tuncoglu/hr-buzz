package com.emre.hrbuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Session maths: zones, calories, GPS distance, running stats, splits. No Android imports,
 * so it runs on a plain JVM and the summary of a saved session can be rebuilt by replaying
 * its samples through exactly the code that produced the live numbers.
 */
public final class Training {

    private Training() {
    }

    /** Lower bound of zones 1..5 as % of HRmax (Polar's default sport zones). */
    public static final int[] ZONE_PCT = {50, 60, 70, 80, 90};

    public static final String[] ZONE_NAMES = {
            "Below zones", "Very light", "Light", "Moderate", "Hard", "Maximum"};

    /** Gap between samples treated as continuous; longer gaps (pause, restart) count this much. */
    public static final long MAX_TICK_MS = 2000;

    /** @return 0 below zone 1, otherwise 1..5 */
    public static int zone(int bpm, int hrMax) {
        if (bpm <= 0 || hrMax <= 0) {
            return 0;
        }
        for (int z = 5; z >= 1; z--) {
            if (bpm * 100 >= ZONE_PCT[z - 1] * hrMax) {
                return z;
            }
        }
        return 0;
    }

    /** Lowest whole bpm that counts as {@code zone} (1..5). */
    public static int zoneFloor(int zone, int hrMax) {
        return (ZONE_PCT[zone - 1] * hrMax + 99) / 100;
    }

    /** Polar's default estimate: 220 - age. */
    public static int ageHrMax(int age) {
        return 220 - age;
    }

    /**
     * HRmax when set to Auto: the highest heart rate held for 10 s in a session, but never
     * below the age estimate (an easy month should not drag your zones down).
     */
    /** Default "alert above" until the person sets one: 85% of HRmax, high in zone 4. */
    public static int defaultAlert(int hrMax) {
        return Math.round(hrMax * 0.85f);
    }

    public static int autoHrMax(int age, int seenPeak) {
        return Math.max(ageHrMax(age), seenPeak);
    }

    /**
     * Resting heart rate from fitness tests: the median of the latest three taken in the last
     * 90 days, so one restless morning does not move it. 0 when there are none.
     */
    public static int restingFromTests(long[] times, int[] rests, long now) {
        List<long[]> recent = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            if (rests[i] > 0 && now - times[i] <= 90L * 86400000L && times[i] <= now) {
                recent.add(new long[]{times[i], rests[i]});
            }
        }
        if (recent.isEmpty()) {
            return 0;
        }
        recent.sort((a, b) -> Long.compare(b[0], a[0]));
        int n = Math.min(3, recent.size());
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = (int) recent.get(i)[1];
        }
        java.util.Arrays.sort(v);
        return n % 2 == 1 ? v[n / 2] : Math.round((v[n / 2 - 1] + v[n / 2]) / 2f);
    }

    /** Percent of HRmax, rounded. */
    public static int percentOfMax(int bpm, int hrMax) {
        return hrMax <= 0 ? 0 : Math.round(bpm * 100f / hrMax);
    }

    /**
     * Energy expenditure from heart rate (Keytel et al. 2005, the variant without VO2max).
     *
     * @return kcal per minute, never negative
     */
    public static double kcalPerMin(int bpm, boolean male, int age, double weightKg) {
        if (bpm <= 0) {
            return 0;
        }
        double kj = male
                ? -55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * age
                : -20.4022 + 0.4472 * bpm - 0.1263 * weightKg + 0.074 * age;
        return Math.max(0, kj / 4.184);
    }

    /** Light activity, 2.5 MET: what an easy walk costs, whatever the heart rate says. */
    static final double LIGHT_MET = 2.5;

    /**
     * Energy expenditure for a session sample. Keytel's formula comes from exercise and runs
     * high at easy heart rates (6 kcal/min at 93 bpm, a running rate for a stroll), so below
     * 45% of HRmax a light-activity estimate is used instead, blending into Keytel by 60%.
     */
    public static double kcalPerMin(int bpm, boolean male, int age, double weightKg, int hrMax) {
        if (bpm <= 0) {
            return 0;
        }
        double keytel = kcalPerMin(bpm, male, age, weightKg);
        if (hrMax <= 0) {
            return keytel;
        }
        double light = LIGHT_MET * 3.5 * weightKg / 200;
        double w = Math.max(0, Math.min(1, (bpm / (double) hrMax - 0.45) / 0.15));
        return (1 - w) * light + w * Math.max(light, keytel);
    }

    /** Great-circle distance in metres. */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Edwards TRIMP points per minute spent in each zone. */
    public static double trimp(long[] zoneMs) {
        double points = 0;
        for (int z = 1; z <= 5; z++) {
            points += z * zoneMs[z] / 60000.0;
        }
        return points;
    }

    /** One-line description of what the session trained, from where the time went. */
    public static String effort(long[] zoneMs) {
        long total = 0;
        for (int z = 1; z <= 5; z++) {
            total += zoneMs[z];
        }
        if (total < 60000) {
            return "Too short to rate";
        }
        // Hard work counts even when it is a small share of the session.
        if (zoneMs[5] >= 3 * 60000 || zoneMs[5] * 10 >= total) {
            return "Maximal \u2014 speed and VO2max";
        }
        if (zoneMs[4] >= 10 * 60000 || zoneMs[4] * 4 >= total) {
            return "Threshold \u2014 sustained high speed";
        }
        if (zoneMs[3] + zoneMs[4] >= total / 2) {
            return "Tempo \u2014 aerobic fitness";
        }
        if (zoneMs[2] + zoneMs[3] >= total / 2) {
            return "Base endurance";
        }
        return "Recovery";
    }

    /** Light / Moderate / Hard / Very hard from a TRIMP score. */
    public static String loadLabel(double trimp) {
        if (trimp < 50) {
            return "Light";
        }
        if (trimp < 120) {
            return "Moderate";
        }
        if (trimp < 250) {
            return "Hard";
        }
        return "Very hard";
    }

    /** Estimated VO2max from heart rates alone (Uth et al. 2004). */
    public static double vo2maxEstimate(int hrMax, int hrRest) {
        return hrRest <= 0 ? 0 : 15.3 * hrMax / hrRest;
    }

    /** Root mean square of successive RR differences, ms. 0 with fewer than 2 intervals. */
    public static double rmssd(List<Integer> rrMs) {
        if (rrMs.size() < 2) {
            return 0;
        }
        double sum = 0;
        for (int i = 1; i < rrMs.size(); i++) {
            double d = rrMs.get(i) - rrMs.get(i - 1);
            sum += d * d;
        }
        return Math.sqrt(sum / (rrMs.size() - 1));
    }

    /**
     * Drops RR intervals that are physiologically impossible or jump more than 25% from the
     * previous good one (missed or extra beats), which would otherwise dominate RMSSD.
     */
    public static List<Integer> cleanRr(List<Integer> rrMs) {
        List<Integer> out = new ArrayList<>();
        int last = -1;
        for (int rr : rrMs) {
            if (rr < 300 || rr > 2000) {
                continue;
            }
            if (last > 0 && Math.abs(rr - last) * 4 > last) {
                last = rr;
                continue;
            }
            out.add(rr);
            last = rr;
        }
        return out;
    }

    // ------------------------------------------------------------------ format

    /** 1:02:03, or 23:10 under an hour. */
    public static String duration(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s / 60) % 60;
        s %= 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    /** Minutes:seconds per km for a speed in m/s; "--:--" when stopped or implausible. */
    public static String pace(double metresPerSecond) {
        if (metresPerSecond < 0.3) {
            return "--:--";
        }
        long secPerKm = Math.round(1000 / metresPerSecond);
        if (secPerKm >= 60 * 60) {
            return "--:--";
        }
        return String.format(Locale.ROOT, "%d:%02d", secPerKm / 60, secPerKm % 60);
    }

    public static String km(double metres) {
        return String.format(Locale.ROOT, "%.2f", metres / 1000);
    }

    /** What voice guidance says: "12 minutes. 2.31 kilometres. Pace 6 05. Heart rate 142, zone 3." */
    public static String announcement(long activeMs, double distanceM, double speed,
                                      boolean gps, int bpm, int zone) {
        StringBuilder sb = new StringBuilder();
        long minutes = activeMs / 60000;
        long hours = minutes / 60;
        if (hours > 0) {
            sb.append(hours).append(hours == 1 ? " hour " : " hours ");
            minutes %= 60;
        }
        sb.append(minutes).append(minutes == 1 ? " minute. " : " minutes. ");
        if (gps) {
            sb.append(String.format(Locale.ROOT, "%.2f kilometres. ", distanceM / 1000));
            if (speed >= 0.3) {
                long secPerKm = Math.round(1000 / speed);
                sb.append("Pace ").append(secPerKm / 60).append(' ')
                        .append(String.format(Locale.ROOT, "%02d", secPerKm % 60)).append(". ");
            }
        }
        if (bpm > 0) {
            sb.append("Heart rate ").append(bpm);
            if (zone > 0) {
                sb.append(", zone ").append(zone);
            }
            sb.append('.');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ samples

    /**
     * One recorded second. Stored as a CSV row: {@code t,bpm,lat,lon,alt,dist,rr}, with
     * {@code rr} a ';'-separated list of RR intervals in ms. A row of just "-" marks a pause,
     * so a track can be split where the recording stopped.
     */
    public static final class Sample {
        public long t;
        public int bpm = -1;
        public double lat = Double.NaN;
        public double lon = Double.NaN;
        public double alt = Double.NaN;
        public double dist;
        public int[] rr = new int[0];

        public boolean hasFix() {
            return !Double.isNaN(lat) && !Double.isNaN(lon);
        }

        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append(t).append(',').append(bpm).append(',');
            if (hasFix()) {
                sb.append(String.format(Locale.ROOT, "%.7f,%.7f,", lat, lon));
            } else {
                sb.append(",,");
            }
            if (!Double.isNaN(alt)) {
                sb.append(String.format(Locale.ROOT, "%.1f", alt));
            }
            sb.append(',').append(String.format(Locale.ROOT, "%.1f", dist)).append(',');
            for (int i = 0; i < rr.length; i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(rr[i]);
            }
            return sb.toString();
        }

        /** @return the sample, or null for a pause marker, a header or a damaged row */
        public static Sample parse(String line) {
            String[] f = line.split(",", -1);
            if (f.length < 6) {
                return null;
            }
            try {
                Sample s = new Sample();
                s.t = Long.parseLong(f[0]);
                s.bpm = Integer.parseInt(f[1]);
                s.lat = f[2].isEmpty() ? Double.NaN : Double.parseDouble(f[2]);
                s.lon = f[3].isEmpty() ? Double.NaN : Double.parseDouble(f[3]);
                s.alt = f[4].isEmpty() ? Double.NaN : Double.parseDouble(f[4]);
                s.dist = Double.parseDouble(f[5]);
                if (f.length > 6 && !f[6].isEmpty()) {
                    String[] parts = f[6].split(";");
                    s.rr = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        s.rr[i] = Integer.parseInt(parts[i]);
                    }
                }
                return s;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Profile values the maths needs. */
    public static final class Profile {
        public final int hrMax;
        public final boolean male;
        public final int age;
        public final double weightKg;

        public Profile(int hrMax, boolean male, int age, double weightKg) {
            this.hrMax = hrMax;
            this.male = male;
            this.age = age;
            this.weightKg = weightKg;
        }
    }

    // -------------------------------------------------------------------- stats

    /**
     * Running totals for one session, fed one sample at a time. Paused time is never fed in,
     * so everything here is "moving time" like Polar Beat's duration.
     */
    public static final class Stats {

        private static final long SPEED_WINDOW_MS = 20000;
        private static final int WINDOW = 64;
        private static final long CATCH_UP_GAP_MS = 3000;
        private static final double CATCH_UP_M = 10;

        private final Profile profile;

        public long activeMs;
        public double distanceM;
        public int maxHr;
        public double kcal;
        public final long[] zoneMs = new long[6];
        /** Fastest 20 s rolling speed seen, m/s. */
        public double bestSpeed;
        /** Breaks (pauses) in order: {gap ms, bpm just before, bpm just after}. */
        public final List<long[]> pauses = new ArrayList<>();
        /** Per completed km: {seconds, average bpm}. */
        public final List<int[]> splits = new ArrayList<>();
        /** Highest average over 10 consecutive heart rate samples (~10 s): a real peak, not a spike. */
        public int peakHr;

        private static final int PEAK_WINDOW = 10;
        private final int[] peakWin = new int[PEAK_WINDOW];
        private int peakCount;
        private int peakHead;
        private int peakSum;

        private long hrMs;
        private double hrWeighted;
        private long lastT = -1;
        private double lastDist;
        /** When the distance last grew, -1 at the start of a segment. */
        private long lastMoveT = -1;

        private final List<long[]> closedSegments = new ArrayList<>();
        private long segMs;
        private long segHrMs;
        private double segHrWeighted;
        private int segMax;
        private int lastBpm;
        private long pauseT = -1;
        private int pauseBpm;

        private final long[] winT = new long[WINDOW];
        private final double[] winD = new double[WINDOW];
        private int winCount;
        private int winHead;

        private long splitStartMs;
        private long splitHrMs;
        private double splitHrWeighted;

        public Stats(Profile profile) {
            this.profile = profile;
        }

        /** Forget the previous sample so the next one starts a new segment (after a pause). */
        public void pause() {
            if (lastT >= 0) {
                closeSegment();
                pauseT = lastT;
                pauseBpm = lastBpm;
            }
            lastT = -1;
            winCount = 0;
            lastMoveT = -1;
            peakCount = 0;
            peakSum = 0;
        }

        private void closeSegment() {
            if (segMs > 0) {
                closedSegments.add(new long[]{segMs,
                        segHrMs > 0 ? Math.round(segHrWeighted / segHrMs) : 0, segMax});
            }
            segMs = 0;
            segHrMs = 0;
            segHrWeighted = 0;
            segMax = 0;
        }

        /** The moving stretches between breaks: {ms, average bpm, max bpm}, the open one last. */
        public List<long[]> segments() {
            List<long[]> out = new ArrayList<>(closedSegments);
            if (segMs > 0) {
                out.add(new long[]{segMs,
                        segHrMs > 0 ? Math.round(segHrWeighted / segHrMs) : 0, segMax});
            }
            return out;
        }

        public void add(Sample s) {
            if (pauseT >= 0) {
                pauses.add(new long[]{Math.max(0, s.t - pauseT), pauseBpm, Math.max(0, s.bpm)});
                pauseT = -1;
            }
            // Time is the gap since the previous sample, so a segment's first sample adds none.
            long dt = lastT < 0 ? 0 : Math.max(0, Math.min(MAX_TICK_MS, s.t - lastT));
            double dd = Math.max(0, s.dist - lastDist);
            lastT = s.t;
            lastDist = s.dist;

            activeMs += dt;
            double before = distanceM;
            distanceM += dd;
            if (dd > 0) {
                // A lost GPS fix freezes the distance, then the whole gap arrives in one step:
                // real distance, but no speed. Start the speed window again after it.
                if (lastMoveT >= 0 && s.t - lastMoveT > CATCH_UP_GAP_MS && dd > CATCH_UP_M) {
                    winCount = 0;
                }
                lastMoveT = s.t;
            }

            if (s.bpm > 0 && s.bpm <= 225) {
                if (peakCount == PEAK_WINDOW) {
                    peakSum -= peakWin[peakHead];
                } else {
                    peakCount++;
                }
                peakWin[peakHead] = s.bpm;
                peakSum += s.bpm;
                peakHead = (peakHead + 1) % PEAK_WINDOW;
                if (peakCount == PEAK_WINDOW) {
                    peakHr = Math.max(peakHr, Math.round(peakSum / (float) PEAK_WINDOW));
                }
            } else {
                peakCount = 0;
                peakSum = 0;
            }
            segMs += dt;
            if (s.bpm > 0) {
                lastBpm = s.bpm;
                segHrMs += dt;
                segHrWeighted += (double) s.bpm * dt;
                segMax = Math.max(segMax, s.bpm);
            }
            if (s.bpm > 0) {
                hrMs += dt;
                hrWeighted += (double) s.bpm * dt;
                splitHrMs += dt;
                splitHrWeighted += (double) s.bpm * dt;
                maxHr = Math.max(maxHr, s.bpm);
                zoneMs[zone(s.bpm, profile.hrMax)] += dt;
                kcal += kcalPerMin(s.bpm, profile.male, profile.age, profile.weightKg, profile.hrMax)
                        * dt / 60000.0;
            }

            if ((int) (distanceM / 1000) > (int) (before / 1000)) {
                int seconds = (int) Math.round((activeMs - splitStartMs) / 1000.0);
                int avg = splitHrMs > 0 ? (int) Math.round(splitHrWeighted / splitHrMs) : 0;
                splits.add(new int[]{seconds, avg});
                splitStartMs = activeMs;
                splitHrMs = 0;
                splitHrWeighted = 0;
            }

            winT[winHead] = activeMs;
            winD[winHead] = distanceM;
            winHead = (winHead + 1) % WINDOW;
            winCount = Math.min(WINDOW, winCount + 1);

            double v = speed();
            if (v > bestSpeed && windowSpan() >= SPEED_WINDOW_MS * 3 / 4) {
                bestSpeed = v;
            }
        }

        public int avgHr() {
            return hrMs > 0 ? (int) Math.round(hrWeighted / hrMs) : 0;
        }

        /** Whole-session average speed, m/s. */
        public double avgSpeed() {
            return activeMs > 0 ? distanceM / (activeMs / 1000.0) : 0;
        }

        /** Speed over roughly the last 20 s of moving time, m/s. */
        public double speed() {
            if (winCount < 2) {
                return 0;
            }
            int newest = (winHead - 1 + WINDOW) % WINDOW;
            int oldestIdx = (winHead - winCount + WINDOW) % WINDOW;
            int idx = oldestIdx;
            for (int i = 0; i < winCount; i++) {
                int k = (oldestIdx + i) % WINDOW;
                if (winT[newest] - winT[k] <= SPEED_WINDOW_MS) {
                    idx = k;
                    break;
                }
            }
            long span = winT[newest] - winT[idx];
            if (span < 5000) {
                return 0;
            }
            return (winD[newest] - winD[idx]) / (span / 1000.0);
        }

        private long windowSpan() {
            if (winCount < 2) {
                return 0;
            }
            int newest = (winHead - 1 + WINDOW) % WINDOW;
            int oldest = (winHead - winCount + WINDOW) % WINDOW;
            return Math.min(SPEED_WINDOW_MS, winT[newest] - winT[oldest]);
        }

        public double trimp() {
            return Training.trimp(zoneMs);
        }
    }
}
