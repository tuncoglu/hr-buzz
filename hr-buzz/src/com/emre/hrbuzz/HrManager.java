package com.emre.hrbuzz;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the BLE heart-rate link and the over-threshold alarm.
 *
 * Single instance, application scoped, so the foreground service and the UI
 * can share one connection. Callbacks arrive on binder threads.
 */
public class HrManager {

    private static final String TAG = "HRBuzz";

    /** Standard Bluetooth SIG heart rate service / measurement characteristic. */
    private static final UUID HR_SERVICE =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    /** Once you are over the limit, stay "over" until you drop this far below it. */
    private static final int HYSTERESIS = 3;
    /** Minimum gap between repeated alarms while still over the limit. */
    private static final long RE_ALERT_MS = 5000;
    /** Readings in a row past a limit before it sounds: one artifact spike is not enough. */
    private static final int CONFIRM = 3;
    /** Outside a session a lost link only warns this long, then waits quietly. */
    private static final long LOST_ALERT_MS = 30000;
    /** If nothing is found, start scanning again after this long. */
    private static final long RESCAN_MS = 15000;

    /** No reading for this long while "connected" means the strap is off or out of range. */
    private static final long STALE_MS = 10000;
    private static final long WATCHDOG_MS = 2000;

    public interface Listener {
        void onState(String state);

        void onBpm(int bpm, boolean above);

        void onConnected(boolean connected);

        /** Every decoded reading, including RR intervals and skin contact. */
        default void onSample(HrLogic.Measurement m) {
        }

        /**
         * An alarm just sounded: {@link HrLogic.Alarm#OVER}, UNDER, LOST (bpm -1) or BACK
         * (back under a limit, or heart rate back after a lost signal).
         */
        default void onAlarm(int event, int bpm) {
        }

        /** Strap name, battery or contact changed. */
        default void onSensor() {
        }
    }

    private static HrManager instance;

    public static synchronized HrManager get(Context context) {
        if (instance == null) {
            instance = new HrManager(context.getApplicationContext());
        }
        return instance;
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences prefs;

    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private ToneGenerator tone;
    private Vibrator vibrator;

    private int threshold;
    private int bpm;
    private boolean connected;
    private boolean active;           // user wants to be connected
    private boolean scanning;
    private HrLogic.Alarm alarm;
    private String state = "Not connected";
    private boolean above;
    private long lastSampleAt;
    private boolean linkLost;           // data stopped after it had flowed; cleared by fresh data
    private long lostAt;
    private long lastLostAlertAt;
    private boolean lostAlerted;
    private boolean lostWatching;
    private boolean hadData;            // this link has delivered a reading
    private boolean sessionRunning;
    private PowerManager.WakeLock wakeLock;
    private int battery = -1;
    private boolean contact = true;
    private boolean alarmOn;
    private String deviceName;
    private boolean lowActive;
    private int overrideHigh;
    private int overrideLow = -1;

    /**
     * Sounds the lost-signal alarm while heart rate data is missing, so a silent dropout cannot
     * go unnoticed: until it is back during a session, for {@link #LOST_ALERT_MS} otherwise
     * (taking the strap off after a workout should not beep forever).
     */
    private final Runnable lostWatch = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (!active || !linkLost || (!sessionRunning && now - lostAt > LOST_ALERT_MS)) {
                lostWatching = false;
                updateWakeLock();
                return;
            }
            if (alarmOn && HrLogic.alertDue(lastLostAlertAt, now, RE_ALERT_MS)) {
                lastLostAlertAt = now;
                lostAlerted = true;
                alarm(HrLogic.Alarm.LOST);
                for (Listener l : listeners) {
                    l.onAlarm(HrLogic.Alarm.LOST, -1);
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!connected) {
                return;
            }
            long now = System.currentTimeMillis();
            if (HrLogic.isStale(lastSampleAt, now, STALE_MS)) {
                dropLink("No heart rate data \u2014 check the strap");
                return;
            }
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final Runnable rescan = new Runnable() {
        @Override
        public void run() {
            if (active && !connected) {
                connect();      // fresh adapter + scanner: they die when Bluetooth is toggled
            }
        }
    };

    private HrManager(Context context) {
        this.appContext = context;
        this.prefs = context.getSharedPreferences("hrbuzz", Context.MODE_PRIVATE);
        this.threshold = prefs.getInt("threshold", Training.defaultAlert(Prefs.hrMax(context)));
        this.alarm = new HrLogic.Alarm(threshold, HYSTERESIS, RE_ALERT_MS, CONFIRM);
        this.alarmOn = prefs.getBoolean("alarmOn", true);
        this.deviceName = prefs.getString("deviceName", null);
    }

    // ---------------------------------------------------------------- public API

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** The upper limit in force now: a coached step's, else the person's own. */
    public int getThreshold() {
        return overrideHigh > 0 ? overrideHigh : threshold;
    }

    /** The person's own "alert above" setting, whatever a session is doing. */
    public int getUserThreshold() {
        return threshold;
    }

    public void setThreshold(int value) {
        if (value < 60 || value > 230) {
            return;
        }
        threshold = value;
        if (overrideHigh <= 0) {
            alarm.setThreshold(value);
        } else if (value < overrideHigh) {
            // A coached step never sits above the person's own ceiling, even mid-step.
            overrideHigh = value;
            alarm.setThreshold(value);
        }
        prefs.edit().putInt("threshold", value).apply();
    }

    /**
     * Limits for the current step of a coached workout, for this session only: nothing is
     * saved, and {@link #clearSessionLimits()} puts the person's own back.
     *
     * @param low 0 for no lower alert during this step
     */
    public void setSessionLimits(int high, int low) {
        overrideHigh = high;
        overrideLow = low;
        alarm.setThreshold(high);
        if (lowActive) {
            alarm.setLow(low);
        }
    }

    public void clearSessionLimits() {
        overrideHigh = 0;
        overrideLow = -1;
        alarm.setThreshold(threshold);
        if (lowActive) {
            alarm.setLow(getUserLowLimit());
        }
    }

    public boolean isAlarmOn() {
        return alarmOn;
    }

    /** Turns the over-the-limit sound off without touching recording or the strap link. */
    public void setAlarmOn(boolean value) {
        alarmOn = value;
        prefs.edit().putBoolean("alarmOn", value).apply();
    }

    /** Lower limit in force now, in bpm, 0 when off. Only sounds while {@link #setLowActive}. */
    public int getLowLimit() {
        return overrideLow >= 0 ? overrideLow : getUserLowLimit();
    }

    /** The person's own "alert below" setting. */
    public int getUserLowLimit() {
        return prefs.getInt("lowLimit", 0);
    }

    public void setLowLimit(int value) {
        if (value != 0 && (value < 40 || value > 220)) {
            return;
        }
        prefs.edit().putInt("lowLimit", value).apply();
        if (lowActive && overrideLow < 0) {
            alarm.setLow(value);
        }
    }

    /**
     * Set by the recorder while a session is running (not paused). The lower limit only makes
     * sense mid-workout, a lost signal alerts until it is back, and the CPU is kept awake so
     * the timers behind both keep running with the screen off.
     */
    public void setSessionRunning(boolean value) {
        sessionRunning = value;
        lowActive = value;
        alarm.setLow(value ? getLowLimit() : 0);
        if (value && linkLost) {
            startLostWatch();
        }
        updateWakeLock();
    }

    public boolean isBelow() {
        return connected && alarm.isBelow();
    }

    /** Strap battery %, or -1 when unknown. */
    public int getBattery() {
        return connected ? battery : -1;
    }

    /** False when the strap says its electrodes have no skin contact. */
    public boolean hasContact() {
        return contact;
    }

    /** Name of the remembered strap, or null when any strap will do. */
    public String getDeviceName() {
        return prefs.getString("deviceAddress", null) == null ? null : deviceName;
    }

    /** Forget the remembered strap so the next search takes the first one it finds. */
    public void forgetDevice() {
        prefs.edit().remove("deviceAddress").remove("deviceName").apply();
        deviceName = null;
        notifySensor();
    }

    /** Latest reading, or -1 when there is none. */
    public int getBpm() {
        return connected ? bpm : -1;
    }

    public boolean isAbove() {
        return connected && above;
    }

    /** Milliseconds since the strap last sent a reading. */
    public long lastSampleAge() {
        return System.currentTimeMillis() - lastSampleAt;
    }

    /** Below this the over-limit state clears. */
    public int getClearBelow() {
        return alarm.clearBelow();
    }

    public String getState() {
        return state;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isActive() {
        return active;
    }

    public void start() {
        if (active) {
            return;
        }
        active = true;
        alarm.reset();
        updateWakeLock();
        connect();
    }

    public void stop() {
        active = false;
        main.removeCallbacks(rescan);
        main.removeCallbacks(lostWatch);
        linkLost = false;
        lostWatching = false;
        stopScan();
        closeGatt();
        alarm.reset();
        bpm = -1;
        setConnected(false);
        updateWakeLock();
        if (tone != null) {
            tone.release();
            tone = null;
        }
        announce("Not connected");
    }

    // ------------------------------------------------------------------ connect

    private void connect() {
        stopScan();
        BluetoothManager manager =
                (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            announce("Bluetooth is off");
            scheduleRescan(RESCAN_MS);
            return;
        }
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            announce("Bluetooth permission missing");
            return;
        }
        if (scanner == null) {
            announce("Bluetooth LE unavailable");
            scheduleRescan(RESCAN_MS);
            return;
        }
        startScan();
    }

    private void scheduleRescan(long delayMs) {
        main.removeCallbacks(rescan);
        main.postDelayed(rescan, delayMs);
    }

    /**
     * The strap link is gone or dead. Drop it, alert until it is back, and look for the strap
     * again. Covers both a reported disconnect and a link that went silent without one.
     */
    private void dropLink(String message) {
        boolean hadReadings = hadData;
        closeGatt();
        setConnected(false);
        announce(message);
        scheduleRescan(3000);
        // A strap that connects but never sends (lying on a desk) is not a dropout. Nor does
        // a reconnect that brings no data end one: the clock keeps running from the first loss.
        if (hadReadings && !linkLost) {
            linkLost = true;
            lostAt = System.currentTimeMillis();
            lastLostAlertAt = 0;
            lostAlerted = false;
            startLostWatch();
        }
    }

    private void startLostWatch() {
        main.removeCallbacks(lostWatch);
        lostWatching = true;
        updateWakeLock();
        main.post(lostWatch);
    }

    /** Heart rate is flowing again after a loss: stop the alarm, and say so if it sounded. */
    private void linkBack() {
        linkLost = false;
        lostWatching = false;
        main.removeCallbacks(lostWatch);
        updateWakeLock();
        if (lostAlerted && alarmOn) {
            alarm(HrLogic.Alarm.BACK);
            for (Listener l : listeners) {
                l.onAlarm(HrLogic.Alarm.BACK, bpm);
            }
        }
        lostAlerted = false;
    }

    /**
     * Handler timers stop when the CPU sleeps, and a foreground service does not prevent that:
     * measured on a Pixel, the lost-signal beep fell from every 5 s to every 10-20 s with the
     * screen off. Hold a partial wake lock while a session runs or the lost alarm is sounding.
     */
    private void updateWakeLock() {
        boolean want = active && (sessionRunning || lostWatching);
        if (wakeLock == null) {
            if (!want) {
                return;
            }
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HRBuzz:alarm");
            wakeLock.setReferenceCounted(false);
        }
        if (want && !wakeLock.isHeld()) {
            wakeLock.acquire(12 * 3600 * 1000L);    // a backstop, not a session limit
        } else if (!want && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void startScan() {
        if (scanner == null) {
            return;
        }
        stopScan();
        String target = getDeviceName() != null ? getDeviceName() : "a heart rate strap";
        announce(linkLost ? "Signal lost \u2014 searching\u2026"
                : "Searching for " + target + "\u2026");

        // Once a strap has worked, only ever connect to that one: in a gym or a race the
        // first heart rate strap in range may well be somebody else's.
        ScanFilter.Builder filter = new ScanFilter.Builder();
        String pinned = prefs.getString("deviceAddress", null);
        if (pinned != null && BluetoothAdapter.checkBluetoothAddress(pinned)) {
            filter.setDeviceAddress(pinned);
        } else {
            filter.setServiceUuid(new android.os.ParcelUuid(HR_SERVICE));
        }
        List<ScanFilter> filters = Collections.singletonList(filter.build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(filters, settings, scanCallback);
            scanning = true;
        } catch (SecurityException e) {
            announce("Bluetooth permission missing");
            return;
        }
        main.removeCallbacks(rescan);
        main.postDelayed(rescan, RESCAN_MS);
    }

    private void stopScan() {
        if (scanner == null || !scanning) {
            return;
        }
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException | IllegalStateException ignored) {
            // permission revoked, or the adapter went away mid-run; nothing to do
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            // Several results can be queued before stopScan() lands; connect once.
            if (device == null || !scanning || gatt != null) {
                return;
            }
            Log.i(TAG, "scan hit: " + safeName(device));
            stopScan();
            main.removeCallbacks(rescan);
            announce("Connecting to " + safeName(device) + "\u2026");
            try {
                gatt = device.connectGatt(appContext, false, gattCallback,
                        BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                announce("Bluetooth permission missing");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed: " + errorCode);
            announce("Scan failed");
            main.removeCallbacks(rescan);
            main.postDelayed(rescan, RESCAN_MS);
        }
    };

    // Binder-thread callbacks only hand off to the main thread, so all state is single-threaded.
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, final int newState) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onConnectionChanged(g, newState);
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onServices(g, status);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) {
                        return;
                    }
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        lastSampleAt = System.currentTimeMillis();
                        remember(g.getDevice());
                        setConnected(true);
                        // "Live" waits for the first reading; a lost signal ends with it too.
                        announce("Connected \u2014 waiting for heart rate\u2026");
                        readBattery(g);
                    } else {
                        fail("Could not start heart rate notifications");
                    }
                }
            });
        }

        // Android 13+
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         final byte[] value, final int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onRead(g, c, value, status);
                }
            });
        }

        // Android 12 and older
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         final int status) {
            final byte[] value = c.getValue();
            main.post(new Runnable() {
                @Override
                public void run() {
                    onRead(g, c, value, status);
                }
            });
        }

        // Android 13+
        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic c,
                                            final byte[] value) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    handle(value);
                }
            });
        }

        // Android 12 and older
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic c) {
            final byte[] value = c.getValue();
            main.post(new Runnable() {
                @Override
                public void run() {
                    handle(value);
                }
            });
        }
    };

    private void onConnectionChanged(BluetoothGatt g, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (g != gatt) {
                return;
            }
            announce("Connected, starting heart rate\u2026");
            try {
                g.discoverServices();
            } catch (SecurityException ignored) {
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (g != gatt) {
                // a connection we already gave up on
                try {
                    g.close();
                } catch (SecurityException ignored) {
                }
                return;
            }
            if (active) {
                dropLink("Signal lost \u2014 reconnecting\u2026");
            } else {
                closeGatt();
                setConnected(false);
                announce("Not connected");
            }
        }
    }

    private void onServices(BluetoothGatt g, int status) {
        if (g != gatt) {
            return;
        }
        BluetoothGattService service =
                status == BluetoothGatt.GATT_SUCCESS ? g.getService(HR_SERVICE) : null;
        BluetoothGattCharacteristic characteristic =
                service == null ? null : service.getCharacteristic(HR_MEASUREMENT);
        BluetoothGattDescriptor descriptor =
                characteristic == null ? null : characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            fail("No heart rate service on this device");
            return;
        }
        try {
            g.setCharacteristicNotification(characteristic, true);
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(descriptor);
            }
        } catch (SecurityException e) {
            announce("Bluetooth permission missing");
        }
        // "Live" is announced from onDescriptorWrite once the strap confirms.
    }

    /** Drops the link and goes back to scanning instead of sitting in a dead state. */
    private void fail(String message) {
        closeGatt();
        setConnected(false);
        announce(message);
        if (active) {
            main.removeCallbacks(rescan);
            main.postDelayed(rescan, RESCAN_MS);
        }
    }

    // -------------------------------------------------------------- measurement

    private void handle(byte[] value) {
        final HrLogic.Measurement m = HrLogic.parse(value);
        if (m.contact != contact) {
            contact = m.contact;
            notifySensor();
        }
        int heartRate = m.bpm;
        if (heartRate < 0) {
            return;
        }
        if (!m.contact) {
            // The strap says its electrodes are off the skin: the number is noise. Leave
            // lastSampleAt alone so the watchdog treats a long gap as a lost signal.
            bpm = -1;
            return;
        }
        bpm = heartRate;
        lastSampleAt = System.currentTimeMillis();
        if (!hadData) {
            hadData = true;
            announce("Live");
        }
        if (linkLost) {
            linkBack();
        }

        final int event = alarm.evaluate(heartRate, System.currentTimeMillis());
        if (event != HrLogic.Alarm.NONE && alarmOn) {
            alarm(event);
            for (Listener l : listeners) {
                l.onAlarm(event, heartRate);
            }
        }
        for (Listener l : listeners) {
            l.onSample(m);
        }

        final int bpmNow = bpm;
        final boolean aboveNow = alarm.isAbove();
        above = aboveNow;
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onBpm(bpmNow, aboveNow);
                }
            }
        });
    }

    /** Plays the high alarm once so the volume can be checked. */
    public void testAlarm() {
        alarm(HrLogic.Alarm.OVER);
    }

    /** A short, friendly tone with one buzz: a coached workout moving to its next step. */
    public void chime() {
        alarm(HrLogic.Alarm.BACK);
    }

    /**
     * High: two beeps + double buzz. Low: one long low tone + triple buzz. Lost signal: a
     * warbling tone + one long buzz, so it cannot be mistaken for "too high". Back: short ack.
     */
    private void alarm(int event) {
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            }
            if (event == HrLogic.Alarm.OVER) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250);
            } else if (event == HrLogic.Alarm.UNDER) {
                tone.startTone(ToneGenerator.TONE_CDMA_LOW_L, 400);
            } else if (event == HrLogic.Alarm.LOST) {
                tone.startTone(ToneGenerator.TONE_SUP_INTERCEPT, 1000);
            } else {
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 120);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "tone failed", e);
        }

        try {
            if (vibrator == null) {
                if (Build.VERSION.SDK_INT >= 31) {
                    VibratorManager vm = (VibratorManager) appContext
                            .getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    if (vm != null) {
                        vibrator = vm.getDefaultVibrator();
                    }
                } else {
                    vibrator = (Vibrator) appContext
                            .getSystemService(Context.VIBRATOR_SERVICE);
                }
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = event == HrLogic.Alarm.OVER
                        ? new long[]{0, 220, 120, 220}
                        : event == HrLogic.Alarm.UNDER
                        ? new long[]{0, 120, 100, 120, 100, 120}
                        : event == HrLogic.Alarm.LOST
                        ? new long[]{0, 700}
                        : new long[]{0, 70};
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, -1);
                // Untagged vibrations are scaled by the media haptics setting (measured at
                // 51% on a Pixel); alarm usage gets the alarm strength instead.
                boolean urgent = event != HrLogic.Alarm.BACK;
                if (Build.VERSION.SDK_INT >= 33) {
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(urgent
                            ? VibrationAttributes.USAGE_ALARM
                            : VibrationAttributes.USAGE_NOTIFICATION));
                } else {
                    vibrator.vibrate(effect, new AudioAttributes.Builder()
                            .setUsage(urgent ? AudioAttributes.USAGE_ALARM
                                    : AudioAttributes.USAGE_NOTIFICATION)
                            .build());
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private void setConnected(boolean value) {
        connected = value;
        main.removeCallbacks(watchdog);
        if (value) {
            main.postDelayed(watchdog, WATCHDOG_MS);
        } else {
            hadData = false;
            above = false;
            contact = true;
            bpm = -1;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onConnected(connected);
                }
            }
        });
    }

    private void announce(final String state) {
        this.state = state;
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onState(state);
                }
            }
        });
    }

    private void remember(BluetoothDevice device) {
        deviceName = safeName(device);
        if (prefs.getString("deviceAddress", null) == null) {
            prefs.edit().putString("deviceAddress", device.getAddress())
                    .putString("deviceName", deviceName).apply();
        }
        notifySensor();
    }

    private void readBattery(BluetoothGatt g) {
        BluetoothGattService service = g.getService(BATTERY_SERVICE);
        BluetoothGattCharacteristic level =
                service == null ? null : service.getCharacteristic(BATTERY_LEVEL);
        if (level == null) {
            return;
        }
        try {
            g.readCharacteristic(level);
        } catch (SecurityException ignored) {
        }
    }

    private void onRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
        if (g != gatt || status != BluetoothGatt.GATT_SUCCESS || value == null
                || value.length < 1 || !BATTERY_LEVEL.equals(c.getUuid())) {
            return;
        }
        battery = value[0] & 0xFF;
        notifySensor();
    }

    private void notifySensor() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onSensor();
                }
            }
        });
    }

    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
            }
            gatt = null;
        }
    }

    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "heart rate strap" : name;
        } catch (SecurityException e) {
            return "heart rate strap";
        }
    }
}
