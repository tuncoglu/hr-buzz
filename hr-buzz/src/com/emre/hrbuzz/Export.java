package com.emre.hrbuzz;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Writes a recorded session as TCX (heart rate, distance, route; what Strava, Garmin Connect,
 * TrainingPeaks etc. import) or GPX (route with heart rate extension). Plain JVM, no Android.
 *
 * Samples come as recorded: a {@code null} entry marks a pause and starts a new track segment.
 */
public final class Export {

    private Export() {
    }

    /** TCX sport attribute: only Running, Biking and Other exist. */
    public static String tcxSport(String sport) {
        switch (sport) {
            case "Running":
                return "Running";
            case "Cycling":
                return "Biking";
            default:
                return "Other";
        }
    }

    public static String tcx(String sport, long start, List<Training.Sample> samples,
                             Training.Stats stats, String notes) {
        StringBuilder sb = new StringBuilder(samples.size() * 220 + 1024);
        String startIso = iso(start);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">\n")
                .append(" <Activities>\n")
                .append("  <Activity Sport=\"").append(tcxSport(sport)).append("\">\n")
                .append("   <Id>").append(startIso).append("</Id>\n")
                .append("   <Lap StartTime=\"").append(startIso).append("\">\n")
                .append("    <TotalTimeSeconds>").append(stats.activeMs / 1000).append("</TotalTimeSeconds>\n")
                .append("    <DistanceMeters>").append(num(stats.distanceM)).append("</DistanceMeters>\n")
                .append("    <Calories>").append(Math.round(stats.kcal)).append("</Calories>\n");
        if (stats.avgHr() > 0) {
            sb.append("    <AverageHeartRateBpm><Value>").append(stats.avgHr())
                    .append("</Value></AverageHeartRateBpm>\n")
                    .append("    <MaximumHeartRateBpm><Value>").append(stats.maxHr)
                    .append("</Value></MaximumHeartRateBpm>\n");
        }
        sb.append("    <Intensity>Active</Intensity>\n")
                .append("    <TriggerMethod>Manual</TriggerMethod>\n");

        boolean open = false;
        for (Training.Sample s : samples) {
            if (s == null) {
                if (open) {
                    sb.append("    </Track>\n");
                    open = false;
                }
                continue;
            }
            if (!open) {
                sb.append("    <Track>\n");
                open = true;
            }
            sb.append("     <Trackpoint><Time>").append(iso(s.t)).append("</Time>");
            if (s.hasFix()) {
                sb.append("<Position><LatitudeDegrees>").append(coord(s.lat))
                        .append("</LatitudeDegrees><LongitudeDegrees>").append(coord(s.lon))
                        .append("</LongitudeDegrees></Position>");
            }
            if (!Double.isNaN(s.alt)) {
                sb.append("<AltitudeMeters>").append(num(s.alt)).append("</AltitudeMeters>");
            }
            sb.append("<DistanceMeters>").append(num(s.dist)).append("</DistanceMeters>");
            if (s.bpm > 0) {
                sb.append("<HeartRateBpm><Value>").append(s.bpm).append("</Value></HeartRateBpm>");
            }
            sb.append("</Trackpoint>\n");
        }
        if (open) {
            sb.append("    </Track>\n");
        }
        sb.append("   </Lap>\n");
        if (notes != null && !notes.isEmpty()) {
            sb.append("   <Notes>").append(xml(notes)).append("</Notes>\n");
        }
        sb.append("   <Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">")
                .append("<Name>HR Buzz</Name></Creator>\n")
                .append("  </Activity>\n")
                .append(" </Activities>\n")
                .append("</TrainingCenterDatabase>\n");
        return sb.toString();
    }

    /** Only samples with a GPS fix can go in a GPX file; returns null when there are none. */
    public static String gpx(String sport, long start, List<Training.Sample> samples) {
        StringBuilder sb = new StringBuilder(samples.size() * 200 + 512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"HR Buzz\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
                .append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
                .append(" <metadata><time>").append(iso(start)).append("</time></metadata>\n")
                .append(" <trk>\n  <name>").append(xml(sport)).append("</name>\n")
                .append("  <type>").append(xml(sport.toLowerCase(Locale.ROOT))).append("</type>\n");
        boolean open = false;
        int points = 0;
        for (Training.Sample s : samples) {
            if (s == null || !s.hasFix()) {
                if (s == null && open) {
                    sb.append("  </trkseg>\n");
                    open = false;
                }
                continue;
            }
            if (!open) {
                sb.append("  <trkseg>\n");
                open = true;
            }
            points++;
            sb.append("   <trkpt lat=\"").append(coord(s.lat)).append("\" lon=\"")
                    .append(coord(s.lon)).append("\">");
            if (!Double.isNaN(s.alt)) {
                sb.append("<ele>").append(num(s.alt)).append("</ele>");
            }
            sb.append("<time>").append(iso(s.t)).append("</time>");
            if (s.bpm > 0) {
                sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(s.bpm)
                        .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>");
            }
            sb.append("</trkpt>\n");
        }
        if (open) {
            sb.append("  </trkseg>\n");
        }
        sb.append(" </trk>\n</gpx>\n");
        return points == 0 ? null : sb.toString();
    }

    static String iso(long millis) {
        return Instant.ofEpochSecond(millis / 1000).toString();
    }

    private static String coord(double v) {
        return String.format(Locale.ROOT, "%.7f", v);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
