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
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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

    /** Once you are over the limit, stay "over" until you drop this far below it. */
    private static final int HYSTERESIS = 3;
    /** Minimum gap between repeated alarms while still over the limit. */
    private static final long RE_ALERT_MS = 5000;
    /** If nothing is found, start scanning again after this long. */
    private static final long RESCAN_MS = 15000;

    /** No reading for this long while "connected" means the strap is off or out of range. */
    private static final long STALE_MS = 10000;
    private static final long WATCHDOG_MS = 2000;

    public interface Listener {
        void onState(String state);

        void onBpm(int bpm, boolean above);

        void onConnected(boolean connected);
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
    private long lastStaleAlertAt;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!connected) {
                return;
            }
            long now = System.currentTimeMillis();
            if (HrLogic.isStale(lastSampleAt, now, STALE_MS)
                    && now - lastStaleAlertAt >= RE_ALERT_MS) {
                lastStaleAlertAt = now;
                announce("No heart rate data \u2014 check the strap");
                alarm(true);
            }
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final Runnable rescan = new Runnable() {
        @Override
        public void run() {
            if (active && !connected) {
                startScan();
            }
        }
    };

    private HrManager(Context context) {
        this.appContext = context;
        this.prefs = context.getSharedPreferences("hrbuzz", Context.MODE_PRIVATE);
        this.threshold = prefs.getInt("threshold", 155);
        this.alarm = new HrLogic.Alarm(threshold, HYSTERESIS, RE_ALERT_MS);
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

    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int value) {
        if (value < 60 || value > 230) {
            return;
        }
        threshold = value;
        alarm.setThreshold(value);
        prefs.edit().putInt("threshold", value).apply();
    }

    /** Latest reading, or -1 when there is none. */
    public int getBpm() {
        return connected ? bpm : -1;
    }

    public boolean isAbove() {
        return connected && above;
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
        connect();
    }

    public void stop() {
        active = false;
        main.removeCallbacks(rescan);
        stopScan();
        closeGatt();
        alarm.reset();
        bpm = -1;
        setConnected(false);
        if (tone != null) {
            tone.release();
            tone = null;
        }
        announce("Not connected");
    }

    // ------------------------------------------------------------------ connect

    private void connect() {
        BluetoothManager manager =
                (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            announce("Bluetooth is off");
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
            return;
        }
        startScan();
    }

    private void startScan() {
        if (scanner == null) {
            return;
        }
        stopScan();
        announce("Searching for a heart rate strap\u2026");

        List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder().setServiceUuid(
                        new android.os.ParcelUuid(HR_SERVICE)).build());
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
        } catch (SecurityException ignored) {
            // permission revoked mid-run; nothing to do
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
                        setConnected(true);
                        announce("Live");
                    } else {
                        fail("Could not start heart rate notifications");
                    }
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
            closeGatt();
            setConnected(false);
            if (active) {
                announce("Signal lost, reconnecting\u2026");
                main.removeCallbacks(rescan);
                main.postDelayed(rescan, 3000);
            } else {
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
        int heartRate = HrLogic.parseBpm(value);
        if (heartRate < 0) {
            return;
        }
        bpm = heartRate;
        lastSampleAt = System.currentTimeMillis();

        int event = alarm.evaluate(heartRate, System.currentTimeMillis());
        if (event == HrLogic.Alarm.OVER) {
            alarm(true);
        } else if (event == HrLogic.Alarm.BACK) {
            alarm(false);
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

    private void alarm(boolean over) {
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            }
            if (over) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250);
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
                long[] pattern = over
                        ? new long[]{0, 220, 120, 220}
                        : new long[]{0, 70};
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
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
            above = false;
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
