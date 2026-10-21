package com.emre.hrbuzz;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements HrManager.Listener {

    private static final int REQ_PERMISSIONS = 1;
    private static final int REQ_ENABLE_BT = 2;

    private TextView bpmView;
    private TextView statusView;
    private EditText maxInput;
    private Button connectButton;

    private HrManager hr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Match the status/navigation bars to the white screen and use dark icons.
        getWindow().setStatusBarColor(0xFFFFFFFF);
        getWindow().setNavigationBarColor(0xFFFFFFFF);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }

        hr = HrManager.get(this);

        bpmView = findViewById(R.id.bpm);
        statusView = findViewById(R.id.status);
        maxInput = findViewById(R.id.maxHr);
        connectButton = findViewById(R.id.connect);

        maxInput.setText(String.valueOf(hr.getThreshold()));
        maxInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                try {
                    hr.setThreshold(Integer.parseInt(s.toString().trim()));
                } catch (NumberFormatException ignored) {
                    // half-typed input; keep the old value
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        connectButton.setOnClickListener(v -> toggle());

        requestNeededPermissions();
    }

    private void toggle() {
        if (hr.isActive()) {
            hr.stop();
            stopService(new Intent(this, LiveService.class));
            connectButton.setText("Connect");
            return;
        }
        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            Toast.makeText(this, "No Bluetooth on this device", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            try {
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            } catch (Exception e) {
                Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!hasBluetoothPermissions()) {
            requestNeededPermissions();
            return;
        }
        startForegroundService(new Intent(this, LiveService.class));
        hr.start();
        connectButton.setText("Disconnect");
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestNeededPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && !hasBluetoothPermissions()) {
            statusView.setText("Bluetooth permission denied");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT && resultCode == RESULT_OK) {
            toggle();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        hr.addListener(this);
        connectButton.setText(hr.isActive() ? "Disconnect" : "Connect");
        // Catch up with whatever happened while the screen was away.
        statusView.setText(hr.getState());
        showBpm(hr.getBpm(), hr.isAbove());
    }

    @Override
    protected void onStop() {
        super.onStop();
        hr.removeListener(this);
    }

    // ------------------------------------------------------------- HrManager

    @Override
    public void onState(String state) {
        runOnUiThread(() -> statusView.setText(state));
    }

    @Override
    public void onBpm(int bpm, boolean above) {
        runOnUiThread(() -> {
            showBpm(bpm, above);
            if (!above) {
                statusView.setText("In range");
            } else if (bpm > hr.getThreshold()) {
                statusView.setText("Over " + hr.getThreshold() + " \u2014 ease off");
            } else {
                // latched by hysteresis: below the limit but not yet clear
                statusView.setText("Easing \u2014 below " + hr.getClearBelow() + " to clear");
            }
        });
    }

    private void showBpm(int bpm, boolean above) {
        bpmView.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        bpmView.setTextColor(above ? 0xFFD32F2F : 0xFF212121);
    }

    @Override
    public void onConnected(boolean connected) {
        runOnUiThread(() -> {
            connectButton.setText(connected ? "Disconnect" : "Connect");
            if (!connected) {
                bpmView.setText("--");
                bpmView.setTextColor(0xFF212121);
            }
        });
    }
}
