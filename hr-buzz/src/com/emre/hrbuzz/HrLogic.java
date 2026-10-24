package com.emre.hrbuzz;

/**
 * The parts worth testing, kept free of Android imports so they run on a plain JVM.
 */
public final class HrLogic {

    private HrLogic() {
    }

    /**
     * Decodes a Bluetooth SIG "Heart Rate Measurement" (0x2A37) payload.
     *
     * <pre>
     * byte 0   flags: bit0 = 16-bit value, bit1 = sensor contact, bit3 = energy expended, bit4 = RR
     * byte 1.. heart rate (uint8, or uint16 little endian when bit0 is set)
     * </pre>
     *
     * @return beats per minute, or -1 when the payload is too short or implausible
     */
    public static int parseBpm(byte[] value) {
        if (value == null || value.length < 2) {
            return -1;
        }
        int flags = value[0] & 0xFF;
        int bpm;
        if ((flags & 0x01) != 0) {
            if (value.length < 3) {
                return -1;
            }
            bpm = (value[1] & 0xFF) | ((value[2] & 0xFF) << 8);
        } else {
            bpm = value[1] & 0xFF;
        }
        if (bpm <= 0 || bpm > 250) {
            return -1;
        }
        return bpm;
    }

    /** One decoded notification: rate, skin contact and any beat-to-beat (RR) intervals. */
    public static final class Measurement {
        public int bpm = -1;
        /** The strap reports contact at all; when false, {@link #contact} means nothing. */
        public boolean contactSupported;
        public boolean contact = true;
        public int[] rrMs = new int[0];
    }

    /**
     * Full decode of a 0x2A37 payload, including flags bit1/bit2 (contact detected /
     * supported) and RR intervals (bit4, uint16 in 1/1024 s, after the optional energy field).
     */
    public static Measurement parse(byte[] value) {
        Measurement m = new Measurement();
        m.bpm = parseBpm(value);
        if (value == null || value.length < 2) {
            return m;
        }
        int flags = value[0] & 0xFF;
        m.contactSupported = (flags & 0x04) != 0;
        m.contact = !m.contactSupported || (flags & 0x02) != 0;
        int offset = (flags & 0x01) != 0 ? 3 : 2;
        if ((flags & 0x08) != 0) {
            offset += 2;
        }
        if ((flags & 0x10) != 0 && offset < value.length) {
            int count = (value.length - offset) / 2;
            m.rrMs = new int[count];
            for (int i = 0; i < count; i++) {
                int raw = (value[offset + 2 * i] & 0xFF) | ((value[offset + 2 * i + 1] & 0xFF) << 8);
                m.rrMs[i] = Math.round(raw * 1000f / 1024f);
            }
        }
        return m;
    }

    // ----------------------------------------------------------------- buzz cues

    /**
     * The buzz that tells a workout step has started, for when nothing is spoken: one long
     * buzz, then one short buzz for each step number (up to five), so "step 3" is felt.
     */
    public static long[] stepPattern(int step) {
        int n = Math.max(1, Math.min(5, step));
        // {wait, long, gap, short, gap, short, ...}: ends on a buzz
        long[] p = new long[2 + 2 * n];
        p[1] = 350;
        p[2] = 250;
        for (int i = 0; i < n; i++) {
            p[3 + 2 * i] = 90;
            if (i < n - 1) {
                p[4 + 2 * i] = 130;
            }
        }
        return p;
    }

    /** Three long buzzes: the workout is complete. */
    public static final long[] DONE_PATTERN = {0, 400, 200, 400, 200, 400};

    /** Two short buzzes: something was announced (a km, a pause, a resume). */
    public static final long[] TICK_PATTERN = {0, 60, 90, 60};

    /**
     * Notices that someone has stopped moving (waiting at a crossing) from GPS speed, once a
     * second, so a session pauses by itself and starts again when they do.
     */
    public static final class AutoPause {
        public static final int NONE = 0;
        public static final int PAUSE = 1;
        public static final int RESUME = 2;

        /** Slower than this is standing still: a stroll is about 1 m/s. */
        static final double STILL_MS = 0.5;
        /** Faster than this is moving again. */
        static final double MOVING_MS = 1.0;
        static final int STILL_FIXES = 8;
        static final int MOVING_FIXES = 3;

        private int still;
        private int moving;

        /**
         * @param paused whether the session is paused (by this detector)
         * @param speed  GPS speed in m/s, negative when the fix has none
         */
        public int onSpeed(boolean paused, double speed) {
            if (speed < 0) {
                return NONE;
            }
            if (!paused) {
                moving = 0;
                if (speed >= STILL_MS) {
                    still = 0;
                } else if (++still >= STILL_FIXES) {
                    still = 0;
                    return PAUSE;
                }
            } else {
                still = 0;
                if (speed <= MOVING_MS) {
                    moving = 0;
                } else if (++moving >= MOVING_FIXES) {
                    moving = 0;
                    return RESUME;
                }
            }
            return NONE;
        }

        public void reset() {
            still = 0;
            moving = 0;
        }
    }

    /** True when no reading has arrived for {@code timeoutMs} (strap fell off or out of range). */
    public static boolean isStale(long lastSampleAt, long now, long timeoutMs) {
        return now - lastSampleAt >= timeoutMs;
    }

    /** True when at least {@code intervalMs} has passed since the last repeating alert. */
    public static boolean alertDue(long lastAlertAt, long now, long intervalMs) {
        return now - lastAlertAt >= intervalMs;
    }

    /**
     * Decides when to sound the alarm.
     *
     * <ul>
     *   <li>fires once the rate has been above the threshold for {@code confirm} readings
     *       in a row, so a single artifact spike does not set it off,</li>
     *   <li>re-fires no more often than {@code reAlertMs} while still over,</li>
     *   <li>only clears once the rate drops {@code hysteresis} below the threshold,
     *       so a reading hovering at the limit does not chatter.</li>
     *   <li>optionally does the same for a lower limit, which only arms once the rate has
     *       first reached it, so a warm-up does not set it off.</li>
     * </ul>
     */
    public static final class Alarm {

        public static final int NONE = 0;
        public static final int OVER = 1;
        public static final int BACK = 2;
        public static final int UNDER = 3;
        /** Not an evaluate() result: the strap link is down, so no limit is being watched. */
        public static final int LOST = 4;
        /** Calm mode only: over the limit for a while, a gentle first warning (no beep). */
        public static final int NUDGE = 5;

        /** A dip back under the limit shorter than this does not restart the calm timers. */
        static final long OVER_GRACE_MS = 5000;

        private int threshold;
        private int low;                  // 0 = no lower limit
        private final int hysteresis;
        private final long reAlertMs;
        private final int confirm;
        private int lowConfirm;
        private long lowReAlertMs;

        // calm mode: a gentle nudge after a while over the limit, the alarm after longer
        private boolean calm;
        private long nudgeAfterMs;
        private long alertAfterMs;
        private int farOver;
        private long calmRepeatMs;
        private long overSince;           // 0 = not over
        private long lastOverAt;
        private boolean nudged;

        private boolean above;
        private boolean below;
        private boolean lowArmed;
        private int overCount;
        private int underCount;
        private long lastAlertAt = Long.MIN_VALUE;

        public Alarm(int threshold, int hysteresis, long reAlertMs) {
            this(threshold, hysteresis, reAlertMs, 1);
        }

        /** @param confirm readings in a row past a limit before it sounds (1 = at once) */
        public Alarm(int threshold, int hysteresis, long reAlertMs, int confirm) {
            this.threshold = threshold;
            this.hysteresis = hysteresis;
            this.reAlertMs = reAlertMs;
            this.confirm = Math.max(1, confirm);
            this.lowConfirm = this.confirm;
            this.lowReAlertMs = reAlertMs;
        }

        /**
         * The lower limit is a nudge, not a warning: it can wait longer before it sounds and
         * repeat less often than the high alarm, so a few seconds of dip does not nag.
         */
        public void setLowTiming(int confirm, long reAlertMs) {
            lowConfirm = Math.max(1, confirm);
            lowReAlertMs = reAlertMs;
        }

        /**
         * Calm mode, for coached sessions and anyone tired of beeps: staying over the limit is
         * answered first by a {@link #NUDGE} after {@code nudgeAfterMs}, and by the alarm
         * itself only after {@code alertAfterMs} (or at once when {@code farOver} bpm past the
         * limit), then repeating every {@code repeatMs} instead of the usual interval.
         * Dips back under the limit of a few seconds do not restart the timers.
         */
        public void setCalm(boolean on, long nudgeAfterMs, long alertAfterMs, int farOver,
                            long repeatMs) {
            calm = on;
            this.nudgeAfterMs = nudgeAfterMs;
            this.alertAfterMs = alertAfterMs;
            this.farOver = farOver;
            calmRepeatMs = repeatMs;
            overSince = 0;
            nudged = false;
        }

        /** Changes the limit without losing the latched state. */
        public void setThreshold(int value) {
            threshold = value;
        }

        /** Sets the lower limit; 0 turns it off. Re-arms so it waits for the rate to reach it. */
        public void setLow(int value) {
            if (value != low) {
                low = value;
                below = false;
                lowArmed = false;
                underCount = 0;
            }
        }

        /** The rate at or below which an over-the-limit state clears. */
        public int clearBelow() {
            return threshold - hysteresis;
        }

        /** The rate at or above which an under-the-limit state clears. */
        public int clearAbove() {
            return low + hysteresis;
        }

        public boolean isAbove() {
            return above;
        }

        public boolean isBelow() {
            return below;
        }

        public void reset() {
            above = false;
            below = false;
            lowArmed = false;
            overCount = 0;
            underCount = 0;
            overSince = 0;
            nudged = false;
            lastAlertAt = Long.MIN_VALUE;
        }

        /**
         * @return {@link #OVER} to sound the high alarm, {@link #UNDER} for the low one,
         *         {@link #BACK} when the rate has recovered, {@link #NONE} to stay quiet
         */
        public int evaluate(int bpm, long now) {
            if (above) {
                if (bpm <= threshold - hysteresis) {
                    above = false;
                    return BACK;
                }
                return repeat(now, OVER, calm ? calmRepeatMs : reAlertMs);
            }
            if (below) {
                if (bpm > threshold) {
                    below = false;          // straight past the band: report the high alarm
                } else if (low <= 0 || bpm >= low + hysteresis) {
                    below = false;
                    return BACK;
                } else {
                    return repeat(now, UNDER, lowReAlertMs);
                }
            }
            if (bpm > threshold) {
                underCount = 0;
                if (calm) {
                    return calmOver(bpm, now);
                }
                if (++overCount < confirm) {
                    return NONE;
                }
                overCount = 0;
                above = true;
                lastAlertAt = now;
                return OVER;
            }
            overCount = 0;
            if (calm && overSince != 0 && now - lastOverAt > OVER_GRACE_MS) {
                overSince = 0;              // really back under, not a dip
                nudged = false;
            }
            if (low > 0) {
                if (!lowArmed) {
                    lowArmed = bpm >= low;
                } else if (bpm < low) {
                    if (++underCount < lowConfirm) {
                        return NONE;
                    }
                    underCount = 0;
                    below = true;
                    lastAlertAt = now;
                    return UNDER;
                } else {
                    underCount = 0;
                }
            }
            return NONE;
        }

        private int calmOver(int bpm, long now) {
            if (overSince == 0) {
                overSince = now;
            }
            lastOverAt = now;
            if (++overCount < confirm) {
                return NONE;
            }
            long over = now - overSince;
            if (over >= alertAfterMs || bpm > threshold + farOver) {
                above = true;
                lastAlertAt = now;
                overCount = 0;
                overSince = 0;
                nudged = false;
                return OVER;
            }
            if (!nudged && over >= nudgeAfterMs) {
                nudged = true;
                return NUDGE;
            }
            return NONE;
        }

        private int repeat(long now, int event, long everyMs) {
            if (now - lastAlertAt >= everyMs) {
                lastAlertAt = now;
                return event;
            }
            return NONE;
        }
    }
}
