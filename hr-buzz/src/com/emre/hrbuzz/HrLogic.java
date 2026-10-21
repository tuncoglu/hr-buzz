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
     *   <li>fires the moment the rate goes above the threshold,</li>
     *   <li>re-fires no more often than {@code reAlertMs} while still over,</li>
     *   <li>only clears once the rate drops {@code hysteresis} below the threshold,
     *       so a reading hovering at the limit does not chatter.</li>
     * </ul>
     */
    public static final class Alarm {

        public static final int NONE = 0;
        public static final int OVER = 1;
        public static final int BACK = 2;

        private int threshold;
        private final int hysteresis;
        private final long reAlertMs;

        private boolean above;
        private long lastAlertAt = Long.MIN_VALUE;

        public Alarm(int threshold, int hysteresis, long reAlertMs) {
            this.threshold = threshold;
            this.hysteresis = hysteresis;
            this.reAlertMs = reAlertMs;
        }

        /** Changes the limit without losing the latched state. */
        public void setThreshold(int value) {
            threshold = value;
        }

        /** The rate at or below which an over-the-limit state clears. */
        public int clearBelow() {
            return threshold - hysteresis;
        }

        public boolean isAbove() {
            return above;
        }

        public void reset() {
            above = false;
            lastAlertAt = Long.MIN_VALUE;
        }

        /**
         * @return {@link #OVER} to sound the high alarm, {@link #BACK} when the rate
         *         has recovered, {@link #NONE} to stay quiet
         */
        public int evaluate(int bpm, long now) {
            if (!above) {
                if (bpm > threshold) {
                    above = true;
                    lastAlertAt = now;
                    return OVER;
                }
                return NONE;
            }
            if (bpm <= threshold - hysteresis) {
                above = false;
                return BACK;
            }
            if (now - lastAlertAt >= reAlertMs) {
                lastAlertAt = now;
                return OVER;
            }
            return NONE;
        }
    }
}
