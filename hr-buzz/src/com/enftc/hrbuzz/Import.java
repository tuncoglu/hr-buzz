package com.enftc.hrbuzz;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Reads a TCX file (Polar Flow, Garmin Connect, Strava and others export it) into HR Buzz
 * samples. Pure Java: SAX is in both the JDK and Android.
 *
 * Pauses: TCX has no pause marker. Polar leaves paused time out of the track, Garmin starts a
 * new {@code <Track>}; both become a pause here, as does any gap of more than
 * {@link #PAUSE_GAP_MS} between points, so paused time never counts as moving time.
 */
public final class Import {

    private Import() {
    }

    /** A gap between trackpoints longer than this is a pause, not a slow sample. */
    public static final long PAUSE_GAP_MS = 10000;

    public static final class Result {
        public String sport = "Other";
        public long start;
        /** As {@link SessionStore#samples}: null entries mark pauses. */
        public List<Training.Sample> samples = new ArrayList<>();
        public String notes = "";
        public boolean gps;
        /** Totals the file itself states (lap sums), for checking. */
        public double fileSeconds;
        public double fileMeters;
        public int pauses;
    }

    public static final class ImportException extends Exception {
        ImportException(String message) {
            super(message);
        }
    }

    /** TCX's Running/Biking/Other to an HR Buzz sport. */
    static String sportFor(String tcx) {
        if (tcx == null) {
            return "Other";
        }
        switch (tcx) {
            case "Running":
                return "Running";
            case "Biking":
                return "Cycling";
            default:
                return "Other";
        }
    }

    public static Result tcx(InputStream in) throws ImportException {
        Handler h = new Handler();
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(true);
            // An exported workout never needs DTDs or entities; refuse them (XXE).
            String[] off = {"http://xml.org/sax/features/external-general-entities",
                    "http://xml.org/sax/features/external-parameter-entities"};
            for (String feature : off) {
                try {
                    f.setFeature(feature, false);
                } catch (Exception ignored) {
                    // not supported by this parser: it does not resolve them anyway
                }
            }
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // Android's parser: no DTD processing to switch off
            }
            InputSource src = new InputSource(in);
            f.newSAXParser().parse(src, h);
        } catch (SAXException | ParserConfigurationException | IOException e) {
            throw new ImportException("Not a readable TCX file");
        }
        Result r = h.result;
        // Drop trailing pause markers and empty runs of markers.
        List<Training.Sample> clean = new ArrayList<>();
        for (Training.Sample s : r.samples) {
            if (s == null && (clean.isEmpty() || clean.get(clean.size() - 1) == null)) {
                continue;
            }
            clean.add(s);
        }
        while (!clean.isEmpty() && clean.get(clean.size() - 1) == null) {
            clean.remove(clean.size() - 1);
        }
        r.samples = clean;
        int samples = 0;
        r.pauses = 0;
        for (Training.Sample s : clean) {
            if (s == null) {
                r.pauses++;
            } else {
                samples++;
                r.gps |= s.hasFix();
            }
        }
        if (samples < 10) {
            throw new ImportException("No track in this file");
        }
        r.start = firstTime(clean);
        return r;
    }

    private static long firstTime(List<Training.Sample> samples) {
        for (Training.Sample s : samples) {
            if (s != null) {
                return s.t;
            }
        }
        return 0;
    }

    private static final class Handler extends DefaultHandler {
        final Result result = new Result();
        private final StringBuilder text = new StringBuilder();
        private final List<String> path = new ArrayList<>();
        private Training.Sample point;
        private Training.Sample last;
        private boolean sportSet;
        private double distOffset;
        private double lastRaw = -1;

        @Override
        public void startElement(String uri, String local, String qName, Attributes atts) {
            String name = local.isEmpty() ? qName : local;
            path.add(name);
            text.setLength(0);
            if ("Activity".equals(name) && !sportSet) {
                result.sport = sportFor(atts.getValue("Sport"));
                sportSet = true;
            } else if ("Track".equals(name)) {
                result.samples.add(null);       // a new track (Garmin) is a pause
            } else if ("Trackpoint".equals(name)) {
                point = new Training.Sample();
                point.t = -1;
                point.dist = Double.NaN;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            String name = local.isEmpty() ? qName : local;
            String value = text.toString().trim();
            String parent = path.size() >= 2 ? path.get(path.size() - 2) : "";
            if (point != null) {
                switch (name) {
                    case "Time":
                        point.t = time(value);
                        break;
                    case "LatitudeDegrees":
                        point.lat = num(value);
                        break;
                    case "LongitudeDegrees":
                        point.lon = num(value);
                        break;
                    case "AltitudeMeters":
                        point.alt = num(value);
                        break;
                    case "DistanceMeters":
                        point.dist = num(value);
                        break;
                    case "Value":
                        if ("HeartRateBpm".equals(parent)) {
                            double v = num(value);
                            point.bpm = Double.isNaN(v) || v <= 0 || v > 250 ? -1 : (int) Math.round(v);
                        }
                        break;
                    case "Trackpoint":
                        finishPoint();
                        break;
                    default:
                        break;
                }
            } else if ("TotalTimeSeconds".equals(name) && "Lap".equals(parent)) {
                result.fileSeconds += zeroIfNaN(num(value));
            } else if ("DistanceMeters".equals(name) && "Lap".equals(parent)) {
                result.fileMeters += zeroIfNaN(num(value));
            } else if ("Notes".equals(name) && "Activity".equals(parent)) {
                result.notes = value;
            }
            path.remove(path.size() - 1);
            text.setLength(0);
        }

        private void finishPoint() {
            Training.Sample p = point;
            point = null;
            if (p.t < 0) {
                return;
            }
            // Distance: keep it cumulative and never backwards (a lap reset or a missing
            // value keeps the last one).
            if (Double.isNaN(p.dist)) {
                p.dist = last != null ? last.dist : 0;
            } else {
                double raw = p.dist;
                if (lastRaw >= 0 && raw < lastRaw - 1) {
                    distOffset += lastRaw;              // the file restarted distance (new lap)
                }
                lastRaw = raw;
                p.dist = raw + distOffset;
                if (last != null && p.dist < last.dist) {
                    p.dist = last.dist;
                }
            }
            if (last != null && p.t <= last.t) {
                return;                                 // duplicate or out of order
            }
            if (last != null && p.t - last.t > PAUSE_GAP_MS) {
                result.samples.add(null);
            }
            result.samples.add(p);
            last = p;
        }

        private static long time(String s) {
            try {
                return Instant.parse(s).toEpochMilli();
            } catch (DateTimeParseException e) {
                try {
                    return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli();
                } catch (DateTimeParseException e2) {
                    try {
                        // some exporters omit the zone: treat as UTC
                        return Instant.parse(s + "Z").toEpochMilli();
                    } catch (DateTimeParseException e3) {
                        return -1;
                    }
                }
            }
        }

        private static double num(String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        private static double zeroIfNaN(double v) {
            return Double.isNaN(v) ? 0 : v;
        }
    }
}
