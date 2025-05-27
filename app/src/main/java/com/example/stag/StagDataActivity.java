package com.example.stag;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.alibaba.fastjson.JSON; // For logging, as seen in STAG docs
import com.huawei.hiresearch.sensorprosdk.SensorProManager;
import com.huawei.hiresearch.sensorprosdk.datatype.custom.FeatureData;
import com.huawei.hiresearch.sensorprosdk.datatype.custom.SensorProUniteCollectTypeConfigure;
import com.huawei.hiresearch.sensorprosdk.datatype.custom.config.SensorProUniteConfigure;
import com.huawei.hiresearch.sensorprosdk.datatype.custom.config.SensorProUniteIMUConfigure;
import com.huawei.hiresearch.sensorprosdk.datatype.custom.config.SensorProUniteMAGConfigure;
import com.huawei.hiresearch.sensorprosdk.datatype.device.SensorProDeviceInfo;
import com.huawei.hiresearch.sensorprosdk.datatype.fitness.UserProfileConfig;
import com.huawei.hiresearch.sensorprosdk.datatype.sensor.AccDataArray;
import com.huawei.hiresearch.sensorprosdk.datatype.sensor.GyroDataArray;
import com.huawei.hiresearch.sensorprosdk.datatype.sensor.MagDataArray;
import com.huawei.hiresearch.sensorprosdk.jni.PostureDataConvertUtils; // For starting/stopping algorithm
import com.huawei.hiresearch.sensorprosdk.provider.callback.SensorProCallback;
import com.huawei.hiresearch.sensorprosdk.provider.callback.SensorProDeviceDiscoverCallback;
import com.huawei.hiresearch.sensorprosdk.provider.callback.SensorProDeviceStateCallback;
import com.huawei.hiresearch.sensorprosdk.utils.LogUtils; // Using SDK's LogUtils if available and configured

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Imports for CSV writing
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Required imports for CSV functionality and data types
import android.os.Environment;
import com.huawei.hiresearch.sensorprosdk.datatype.sensor.AccData;
import com.huawei.hiresearch.sensorprosdk.datatype.sensor.GyroData;
import com.huawei.hiresearch.sensorprosdk.datatype.sensor.MagData;
import com.huawei.hiresearch.sensorprosdk.datatype.sensor.MagIntData;

public class StagDataActivity extends AppCompatActivity {

    private static final String TAG = "StagDataActivity";
    private static final int S_TAG_DEVICE_ITEM_TYPE = 8; // From doc_STAG.txt
    private static final int REQUEST_ENABLE_BT = 1001;
    private static final long SCAN_PERIOD = 10000; // Scan for 10 seconds

    // UI Elements
    private Button btnScanDevice, btnConnectDevice, btnStartDataCollection, btnStopDataCollection;
    private Spinner spinnerScannedDevices;
    private TextView tvConnectionStatus, tvLinAccData, tvOrientationData, tvRawMagData, tvLogOutput;
    private ScrollView scrollViewLogs; // To auto-scroll log output

    private BluetoothAdapter bluetoothAdapter;
    private ArrayAdapter<String> scannedDevicesAdapter;
    private List<BluetoothDevice> discoveredDevicesList = new ArrayList<>();
    private Map<String, BluetoothDevice> discoveredDevicesMap = new HashMap<>(); // MAC Address -> Device
    private BluetoothDevice selectedDevice;
    private boolean isScanning = false;
    private boolean isConnected = false;
    private boolean isCollecting = false;
    private Handler scanHandler = new Handler(Looper.getMainLooper());

    // Huawei SDK specific
    private SensorProManager sensorProManager;

    // CSV Writing specific
    private static final String CSV_HEADER = "frame_index,raw_acc_x,raw_acc_y,raw_acc_z,raw_gyro_x,raw_gyro_y,raw_gyro_z,raw_mag_x,raw_mag_y,raw_mag_z,lin_acc_x,lin_acc_y,lin_acc_z,ori_roll_deg,ori_pitch_deg,ori_yaw_deg";
    private static final int DATA_BUFFER_FLUSH_SIZE = 100; // Write to file every 100 samples
    private static final long DATA_FLUSH_INTERVAL_MS = 5000; // Or every 5 seconds
    private List<String> csvDataBuffer = new ArrayList<>();
    private final Object csvBufferLock = new Object(); // 新增一个锁对象
    private File currentCsvFile;
    private BufferedWriter currentCsvFileWriter;
    private Handler dataFlushHandler = new Handler(Looper.getMainLooper());
    private Runnable dataFlushRunnable;
    private TextView tvCurrentFileName; // Optional: For displaying current file name
    private long frameCounter = 0; // Added frame counter

    private StringBuilder logBuilder = new StringBuilder();

    // ActivityResultLauncher for permissions
    private ActivityResultLauncher<String[]> requestPermissionsLauncher;
    private ActivityResultLauncher<Intent> enableBtLauncher;

    // VQF and data processing specific
    private VqfNative vqfNative;
    private static final float SAMPLING_RATE_HZ = 100.0f; // Matches IMU config
    private static final float GRAVITATIONAL_ACCELERATION = 9.80665f; // More precise g
    private static final float ACC_RAW_TO_G_FACTOR = 0.00048828f;
    private static final float GYRO_RAW_TO_RADS_FACTOR = 0.00122173f;

    // Magnetometer calibration matrices from preprocess.py
    // Flattened 3x3 matrix A_mag_cal: row-major order
    private static final float[] A_MAG_CAL = {
        0.9959f, -0.0899f, 0.0084f,
       -0.2040f,  1.1372f, 0.0481f,
       -0.5261f,  0.1616f, 0.9534f
    };
    private static final float[] B_MAG_CAL = {1.3280f, 10.6289f, 6.2491f};

    // Temporary arrays for VQF input
    private float[] accForVqf = new float[3]; // m/s^2
    private float[] gyrForVqf = new float[3]; // rad/s
    private float[] magForVqf = new float[3]; // uT (calibrated)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stag_data);

        initUI();
        initBluetooth();
        initSDK();
        setupPermissionLaunchers();
        requestNecessaryPermissions();
    }

    private void initUI() {
        btnScanDevice = findViewById(R.id.btnScanDevice);
        btnConnectDevice = findViewById(R.id.btnConnectDevice);
        btnStartDataCollection = findViewById(R.id.btnStartDataCollection);
        btnStopDataCollection = findViewById(R.id.btnStopDataCollection);
        spinnerScannedDevices = findViewById(R.id.spinnerScannedDevices);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvLinAccData = findViewById(R.id.tvLinAccData);
        tvOrientationData = findViewById(R.id.tvOrientationData);
        tvRawMagData = findViewById(R.id.tvMagData);
        tvLogOutput = findViewById(R.id.tvLogOutput);
        scrollViewLogs = findViewById(R.id.scrollViewLogs);
        tvCurrentFileName = findViewById(R.id.tvCurrentFileName);

        scannedDevicesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        scannedDevicesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerScannedDevices.setAdapter(scannedDevicesAdapter);

        btnScanDevice.setOnClickListener(v -> toggleScan());
        btnConnectDevice.setOnClickListener(v -> connectSelectedDevice());
        btnStartDataCollection.setOnClickListener(v -> {
            if (isConnected && !isCollecting) {
                initCsvWriter();
                startDataCollection();
            } else if (!isConnected) {
                logToScreen("Device not connected. Cannot start collection.");
                Toast.makeText(this, "Device not connected. Please connect first.", Toast.LENGTH_SHORT).show();
            } else {
                logToScreen("Already collecting or starting collection...");
            }
        });
        btnStopDataCollection.setOnClickListener(v -> {
            if (isCollecting) {
                stopDataCollection();
            }
        });

        spinnerScannedDevices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < discoveredDevicesList.size()) {
                    selectedDevice = discoveredDevicesList.get(position);
                    logToScreen("Selected device: " + getDeviceDisplayName(selectedDevice));
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedDevice = null;
            }
        });

        updateButtonStates();
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            logToScreen("Bluetooth not supported on this device.");
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void initSDK() {
        // SensorProManager should be initialized in Application class (ResearchStack)
        // We get the instance here.
        try {
            sensorProManager = SensorProManager.getInstance();
            if (sensorProManager == null) {
                 logToScreen("Error: SensorProManager instance is null. Check SDK initialization in Application class.");
                 Toast.makeText(this, "SDK Init Error", Toast.LENGTH_LONG).show();
            }
             // Register device state monitor after getting instance
            registerDeviceStateMonitor();
        } catch (Exception e) {
            logToScreen("Exception during SensorProManager.getInstance(): " + e.getMessage());
            Log.e(TAG, "SDK getInstance exception", e);
        }
    }
    
    // Placeholder for logToScreen and other methods to be added next
    private void logToScreen(final String message) {
        Log.d(TAG, message);
        runOnUiThread(() -> {
            logBuilder.append(message).append("\n");
            tvLogOutput.setText(logBuilder.toString());
            if (scrollViewLogs != null) {
                 scrollViewLogs.post(() -> scrollViewLogs.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void updateButtonStates() {
        btnScanDevice.setText(isScanning ? "Stop Scan" : "Scan S-TAG Devices");
        btnConnectDevice.setEnabled(!isScanning && selectedDevice != null && !isConnected);
        btnStartDataCollection.setEnabled(isConnected && !isCollecting);
        btnStopDataCollection.setEnabled(isConnected && isCollecting);

        // Clear data fields when not collecting or disconnected
        if (!isCollecting || !isConnected) {
            if (tvLinAccData != null) tvLinAccData.setText("LinAcc: -");
            if (tvOrientationData != null) tvOrientationData.setText("Orientation: -");
            if (tvRawMagData != null) tvRawMagData.setText("RawMag: -");
        }
    }

    private String getDeviceDisplayName(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // This should have been handled by permission request, but as a fallback.
            return device.getAddress(); 
        }
        return (device.getName() == null || device.getName().isEmpty()) ? device.getAddress() : device.getName() + " [" + device.getAddress() + "]";
    }

    private void setupPermissionLaunchers() {
        requestPermissionsLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
            boolean allGranted = true;
            for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                if (!entry.getValue()) {
                    allGranted = false;
                    logToScreen("Permission denied: " + entry.getKey());
                }
            }
            if (allGranted) {
                logToScreen("All necessary permissions granted.");
                // Now try to enable Bluetooth if not already enabled
                checkAndEnableBluetooth();
            } else {
                logToScreen("Some permissions were denied. App functionality may be limited.");
                Toast.makeText(this, "Permissions are required for STAG functionality.", Toast.LENGTH_LONG).show();
            }
        });

        enableBtLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                logToScreen("Bluetooth enabled successfully.");
            } else {
                logToScreen("Bluetooth not enabled. App functionality may be limited.");
                Toast.makeText(this, "Bluetooth is required to scan for S-TAG.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void requestNecessaryPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ Bluetooth permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            // Older Bluetooth permissions (BLUETOOTH_ADMIN for discovery)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }

        // Location permissions (required for BLE scan on many Android versions)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        // ACCESS_COARSE_LOCATION is usually granted if FINE_LOCATION is, but can be added if issues arise.
        // if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        //     permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        // }

        // Storage permissions (if SDK's debug log needs it, or future file operations)
        // For Android 10+ (API 29+), WRITE_EXTERNAL_STORAGE has restrictions.
        // For simplicity with setDebugLog(true), we request it for older versions.
        // If (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // Or up to 28 as in Manifest
        //     if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        //         permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        //     }
        // }

        if (!permissionsToRequest.isEmpty()) {
            logToScreen("Requesting permissions: " + permissionsToRequest);
            requestPermissionsLauncher.launch(permissionsToRequest.toArray(new String[0]));
        } else {
            logToScreen("All necessary permissions already granted.");
            checkAndEnableBluetooth();
        }
    }

    private void checkAndEnableBluetooth() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            logToScreen("Bluetooth is not enabled. Requesting to enable.");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                 // This should not happen if permissions were granted, but as a safeguard.
                logToScreen("BLUETOOTH_CONNECT permission not granted before enabling Bluetooth. This is unexpected.");
                Toast.makeText(this, "Connect permission missing for BT enable", Toast.LENGTH_SHORT).show();
                return;
            }
            enableBtLauncher.launch(enableBtIntent);
        } else {
            logToScreen("Bluetooth is already enabled.");
        }
    }

    private void toggleScan() {
        if (!hasScanPermissions()) {
            logToScreen("Scan permissions not granted. Requesting again.");
            requestNecessaryPermissions(); // Re-trigger permission request
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            logToScreen("Bluetooth not enabled. Please enable Bluetooth.");
            checkAndEnableBluetooth();
            return;
        }

        if (sensorProManager == null) {
            logToScreen("SDK not initialized, cannot scan.");
            return;
        }

        if (!isScanning) {
            startScan();
        } else {
            stopScan();
        }
    }

    private boolean hasScanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void startScan() {
        logToScreen("Starting S-TAG device scan...");
        isScanning = true;
        discoveredDevicesList.clear();
        discoveredDevicesMap.clear();
        scannedDevicesAdapter.clear();
        updateButtonStates();

        // Stop scan after SCAN_PERIOD
        scanHandler.postDelayed(this::stopScan, SCAN_PERIOD);

        // SDK call from doc_basic.txt (scanDevice example)
        sensorProManager.getDeviceProvider().scanDevice(S_TAG_DEVICE_ITEM_TYPE, new SensorProDeviceDiscoverCallback() {
            @Override
            public void onDeviceDiscovered(BluetoothDevice device) {
                if (device != null) {
                    String deviceAddress = device.getAddress();
                    if (!discoveredDevicesMap.containsKey(deviceAddress)) {
                        // Check for BLUETOOTH_CONNECT permission before accessing device.getName()
                        if (ActivityCompat.checkSelfPermission(StagDataActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            logToScreen("BLUETOOTH_CONNECT permission missing for device.getName(). Requesting permissions.");
                            // This scenario implies a state where scan is allowed but connect isn't, might need to re-request.
                            // For simplicity, we log and add by address if name is inaccessible.
                            // requestNecessaryPermissions(); // Could lead to loop if not careful
                            logToScreen("Discovered S-TAG: " + device.getAddress());
                        } else {
                             logToScreen("Discovered S-TAG: " + getDeviceDisplayName(device));
                        }
                        discoveredDevicesList.add(device);
                        discoveredDevicesMap.put(deviceAddress, device);
                        runOnUiThread(() -> scannedDevicesAdapter.add(getDeviceDisplayName(device)));
                    } else {
                         // logToScreen("Duplicate device discovered: " + getDeviceDisplayName(device));
                    }
                }
            }

            @Override
            public void onDeviceDiscoveryFinished() {
                if (isScanning) { // Ensure it was not stopped by timeout already
                    logToScreen("Scan finished (callback). Found " + discoveredDevicesList.size() + " devices.");
                    isScanning = false; // Update state if finished by SDK before timeout
                    scanHandler.removeCallbacksAndMessages(null); // Remove pending stopScan runnable
                    runOnUiThread(() -> {
                        updateButtonStates();
                        if (discoveredDevicesList.isEmpty()) {
                            Toast.makeText(StagDataActivity.this, "No S-TAG devices found.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }

            @Override
            public void onDeviceDiscoveryCanceled() {
                logToScreen("Scan canceled by SDK.");
                isScanning = false;
                scanHandler.removeCallbacksAndMessages(null);
                runOnUiThread(StagDataActivity.this::updateButtonStates);
            }

            @Override
            public void onFailure(int cause) {
                logToScreen("Scan failed. Cause: " + cause);
                isScanning = false;
                scanHandler.removeCallbacksAndMessages(null);
                runOnUiThread(StagDataActivity.this::updateButtonStates);
                Toast.makeText(StagDataActivity.this, "Scan failed: " + cause, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void stopScan() {
        if (isScanning) {
            logToScreen("Stopping S-TAG device scan...");
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                logToScreen("BLUETOOTH_SCAN permission not granted to stop scan. This is an issue.");
                // Cannot call cancelDiscovery without permission
            } else {
                // SDK does not have a direct stopScan in the scanDevice example.
                // Typically, scanDevice might stop itself after a period or when onDeviceDiscoveryFinished is called.
                // The SensorProDeviceDiscoverCallback has onDeviceDiscoveryCanceled, but it's a callback, not a command.
                // We are relying on the SCAN_PERIOD timeout or the onDeviceDiscoveryFinished callback.
                // If there were a sensorProManager.getDeviceProvider().stopScanDevice() method, it would be called here.
                logToScreen("Scan will stop via timeout or onDeviceDiscoveryFinished callback.");
            }
            isScanning = false;
            scanHandler.removeCallbacksAndMessages(null); // Important to remove timeout runnable
            runOnUiThread(this::updateButtonStates);
        }
    }

    private void connectSelectedDevice() {
        if (selectedDevice == null) {
            logToScreen("No device selected to connect.");
            Toast.makeText(this, "Please select a device from the list", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sensorProManager == null) {
            logToScreen("SDK not initialized, cannot connect.");
            return;
        }

        // Check BLUETOOTH_CONNECT permission before connecting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            logToScreen("BLUETOOTH_CONNECT permission not granted. Requesting...");
            requestNecessaryPermissions(); // This will re-trigger the permission flow
            return;
        }

        // Stop scanning before attempting to connect
        if (isScanning) {
            stopScan();
        }

        logToScreen("Attempting to connect to: " + getDeviceDisplayName(selectedDevice) + "...");
        tvConnectionStatus.setText("Status: Connecting to " + getDeviceDisplayName(selectedDevice));
        // Set isConnected to false initially before attempting a new connection, 
        // to prevent multiple clicks or race conditions if previous state was somehow stuck true.
        isConnected = false;
        updateButtonStates();

        // SDK call from doc_basic.txt (connectBTDevice example)
        sensorProManager.getDeviceProvider().connectBTDevice(selectedDevice, new SensorProCallback<String>() {
            @Override
            public void onResponse(int errorCode, String returnObject) {
                // The primary confirmation of connection status will come from registerDeviceStateMonitor
                // This callback mainly indicates the attempt's immediate outcome.
                if (errorCode == 0) { // 0 indicates success for the connection *attempt*
                    logToScreen("Connection attempt to " + getDeviceDisplayName(selectedDevice) + " initiated. Waiting for state monitor confirmation. Response: " + returnObject);
                    // Don't set isConnected = true here directly. Rely on DeviceStateMonitor.
                    // tvConnectionStatus can be updated to "Status: Connection initiated..."
                } else {
                    isConnected = false; // Attempt failed outright
                    logToScreen("Connection attempt failed. Error code: " + errorCode + ", Message: " + returnObject);
                    runOnUiThread(() -> {
                        tvConnectionStatus.setText("Status: Connection Attempt Failed (Error: " + errorCode + ")");
                        updateButtonStates();
                        Toast.makeText(StagDataActivity.this, "Connection attempt failed: " + errorCode, Toast.LENGTH_LONG).show();
                    });
                }
                // updateButtonStates() will be called by DeviceStateMonitor mostly
            }
        });
    }

    private void registerDeviceStateMonitor() {
        if (sensorProManager == null) {
            logToScreen("SDK not initialized, cannot register state monitor.");
            return;
        }
        String serviceIdForMonitor = ""; 
        sensorProManager.getDeviceProvider().registerStateMonitor(serviceIdForMonitor, new SensorProDeviceStateCallback() {
            @Override
            public void onResponse(int outerCode, SensorProDeviceInfo deviceInfo) {
                String deviceDisplayName = "Unknown Device";
                int actualDeviceConnectState = -1; // Default to an invalid state

                if (deviceInfo != null) {
                    deviceDisplayName = getDeviceDisplayNameFromInfo(deviceInfo);
                    actualDeviceConnectState = deviceInfo.getDeviceConnectState();
                    logToScreen("Monitor Device Info: " + JSON.toJSONString(deviceInfo) + ", Outer Code: " + outerCode);
                } else if (selectedDevice != null) {
                    deviceDisplayName = getDeviceDisplayName(selectedDevice);
                    logToScreen("DeviceStateMonitor: deviceInfo is null, outerCode: " + outerCode + ". Using selected device name: " + deviceDisplayName);
                } else {
                    logToScreen("DeviceStateMonitor: deviceInfo is null and no selectedDevice, outerCode: " + outerCode);
                }

                String statusMessage = "Device State Changed. Device: " + deviceDisplayName + ", ConnectionState: " + actualDeviceConnectState + " (OuterCode: " + outerCode + ")";
                boolean prevIsConnected = isConnected;

                // Prioritize deviceInfo.getDeviceConnectState() for connection logic
                switch (actualDeviceConnectState) {
                    case 1: // DEVICE_CONNECTING from SensorProDeviceInfo.DeviceConnectState
                        statusMessage += " (Connecting)";
                        isConnected = false; 
                        tvConnectionStatus.setText("Status: Connecting to " + deviceDisplayName);
                        break;
                    case 2: // DEVICE_CONNECTED from SensorProDeviceInfo.DeviceConnectState
                        statusMessage += " (Connected)";
                        isConnected = true; 
                        // Update selectedDevice if deviceInfo provides a valid MAC that can be used to get a BluetoothDevice object
                        if (deviceInfo != null && deviceInfo.getDeviceIdentify() != null && bluetoothAdapter != null) {
                            try {
                                selectedDevice = bluetoothAdapter.getRemoteDevice(deviceInfo.getDeviceIdentify());
                            } catch (IllegalArgumentException e) {
                                logToScreen("Error getting remote device from MAC: " + deviceInfo.getDeviceIdentify() + " - " + e.getMessage());
                            }
                        }
                        tvConnectionStatus.setText("Status: Connected to " + deviceDisplayName);
                        if (!prevIsConnected) { 
                           Toast.makeText(StagDataActivity.this, "Device " + deviceDisplayName + " connected!", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case 3: // DEVICE_DISCONNECTED from SensorProDeviceInfo.DeviceConnectState
                        statusMessage += " (Disconnected)";
                        isConnected = false;
                        isCollecting = false; 
                        tvConnectionStatus.setText("Status: Disconnected from " + deviceDisplayName);
                        if (prevIsConnected) { 
                            Toast.makeText(StagDataActivity.this, "Device " + deviceDisplayName + " disconnected.", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case 4: // DEVICE_CONNECT_FAILED from SensorProDeviceInfo.DeviceConnectState
                        statusMessage += " (Connection Failed)";
                        isConnected = false;
                        tvConnectionStatus.setText("Status: Connection Failed with " + deviceDisplayName);
                        break;
                    default: // Includes cases where deviceInfo might be null or connectState is unexpected
                        statusMessage += " (DeviceConnectState Unknown: " + actualDeviceConnectState + ")";
                        // If outerCode suggested an issue or if deviceInfo is null, consider it not robustly connected
                        if (outerCode != 0 && outerCode != 100000 ) { // Assuming 0 and 100000 from deviceInfo.returnCode are positive indicators
                             if(isConnected) { // if it was previously connected, now it's unstable/unknown
                                isConnected = false;
                                isCollecting = false;
                                tvConnectionStatus.setText("Status: Connection state uncertain with " + deviceDisplayName);
                             }
                        } else if (deviceInfo == null && isConnected) {
                            // If device info is null and we thought we were connected, revert state.
                            isConnected = false;
                            isCollecting = false;
                            tvConnectionStatus.setText("Status: Connection lost (no device info) with " + deviceDisplayName);
                        }
                        // If actualDeviceConnectState is not 1,2,3, or 4, it's an unknown state. 
                        // If it was previously connected, this might mean a problem.
                        else if (actualDeviceConnectState < 1 || actualDeviceConnectState > 4) {
                            if(isConnected){
                                isConnected = false;
                                isCollecting = false;
                                tvConnectionStatus.setText("Status: Connection state invalid with " + deviceDisplayName);
                            }
                        }
                        break;
                }
                logToScreen(statusMessage);
                runOnUiThread(StagDataActivity.this::updateButtonStates);
            }
        });
        logToScreen("Device state monitor registered.");
    }
    
    private String getDeviceDisplayNameFromInfo(SensorProDeviceInfo deviceInfo) {
        if (deviceInfo == null) return "Unknown Device";
        return (deviceInfo.getDeviceName() == null || deviceInfo.getDeviceName().isEmpty()) ? deviceInfo.getDeviceIdentify() : deviceInfo.getDeviceName() + " [" + deviceInfo.getDeviceIdentify() + "]";
    }

    // Lifecycle methods (onDestroy)

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan(); // Stop scanning if active
        // According to doc_STAG.txt, we need to stop algorithm and data collection if active.
        if (isCollecting) {
            stopDataCollectionLogic(); // Ensure this is called
        }
        if (isConnected) {
           // SDK does not provide explicit disconnect for SensorProManager in STAG guide, removal is shown
           // Consider if SensorProManager.getInstance().getDeviceProvider().removeBTDevice() is appropriate here
           // For now, rely on SDK to handle connection lifecycle or explicit user removal if implemented.
        }
        // Unregister any broadcast receivers or callbacks if registered directly in activity
        // sensorProManager.getInstance().getDeviceProvider().unregisterStateMonitor(); // Check SDK for unregister method
        logToScreen("Activity Destroyed");
        dataFlushHandler.removeCallbacks(dataFlushRunnable); // Stop periodic flush when activity is destroyed
    }

    private void startDataCollection() {
        if (!isConnected) {
            logToScreen("Device not connected. Cannot start collection.");
            Toast.makeText(this, "Device not connected.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isCollecting) {
            logToScreen("Already collecting data.");
            return;
        }
        if (sensorProManager == null) {
            logToScreen("SDK not initialized, cannot start collection.");
            return;
        }

        logToScreen("Starting data collection process...");

        // Initialize VQF
        if (vqfNative != null) {
            vqfNative.close(); // Close previous instance if any
        }
        vqfNative = new VqfNative(1.0f / SAMPLING_RATE_HZ);
        logToScreen("VQF Initialized for " + SAMPLING_RATE_HZ + " Hz.");

        // UserProfileConfig - usually optional for raw data collection, but part of original flow
        UserProfileConfig userProfileConfig = new UserProfileConfig();
        userProfileConfig.setHeight(175); 
        userProfileConfig.setWeight(70);  
        userProfileConfig.setAge(30);     
        userProfileConfig.setGender(1);   
        // Temporarily skip configUserProfile to diagnose if it's the blocking point
        logToScreen("Temporarily SKIPPING configUserProfile call.");
        startAlgorithmAndActualCollection(); 

        /* ursprünglicher Code mit configUserProfile und Timeout-Logik:
        
        logToScreen("Calling configUserProfile...");
        final boolean[] configProfileCallbackCalled = {false};
        final Handler configProfileTimeoutHandler = new Handler(Looper.getMainLooper());
        final Runnable configProfileTimeoutRunnable = () -> {
            if (!configProfileCallbackCalled[0]) {
                logToScreen("Error: configUserProfile callback timed out after 15 seconds.");
                Toast.makeText(StagDataActivity.this, "User Profile Config Timed Out", Toast.LENGTH_LONG).show();
                runOnUiThread(StagDataActivity.this::updateButtonStates);
            }
        };
        configProfileTimeoutHandler.postDelayed(configProfileTimeoutRunnable, 15000);

        sensorProManager.getMotionProvider().configUserProfile(userProfileConfig, new SensorProCallback<byte[]>() {
            @Override
            public void onResponse(int errorCode, byte[] returnObject) {
                configProfileCallbackCalled[0] = true;
                configProfileTimeoutHandler.removeCallbacks(configProfileTimeoutRunnable);
                logToScreen("configUserProfile onResponse. errorCode: " + errorCode);

                if (errorCode == 100000 || errorCode == 0) {
                    logToScreen("User profile configured successfully.");
                    startAlgorithmAndActualCollection();
                } else {
                    logToScreen("Failed to configure user profile. Error: " + errorCode);
                    Toast.makeText(StagDataActivity.this, "User Profile Config Failed: " + errorCode, Toast.LENGTH_LONG).show();
                    if (vqfNative != null) { vqfNative.close(); vqfNative = null; }
                    runOnUiThread(StagDataActivity.this::updateButtonStates);
                }
            }
        });
        */
    }

    private void startAlgorithmAndActualCollection() {
        // 2. Start Algorithm
        int freq = (int)SAMPLING_RATE_HZ; // Use the defined SAMPLING_RATE_HZ
        int cpFusionMode = 2; // ACC+GYRO+MAG
        try {
            PostureDataConvertUtils.startPostureDetection(freq, cpFusionMode);
            logToScreen("Posture detection algorithm started (freq: " + freq + "Hz, mode: " + cpFusionMode + ").");
        } catch (Exception e) {
            logToScreen("Error starting posture detection algorithm: " + e.getMessage());
            Log.e(TAG, "Error starting posture detection algorithm", e);
            if (vqfNative != null) { vqfNative.close(); vqfNative = null; }
            return;
        }

        // 3. Register Feature Data Callback
        sensorProManager.getCustomProvider().registerFeatureDataCallback(featureDataCallback);
        logToScreen("FeatureData callback registered.");

        // 4. Configure and Start Realtime Measurement
        SensorProUniteCollectTypeConfigure activeCollectConfig = new SensorProUniteCollectTypeConfigure();
        activeCollectConfig.setParseData(true);
        activeCollectConfig.setRealtimeMesureTimeOut(0); // Continuous until stopped
        activeCollectConfig.setCollectAcc(true);
        activeCollectConfig.setCollectGyro(true);
        activeCollectConfig.setCollectMag(true);
        // activeCollectConfig.setCollectGaitPosture(false); // Explicitly false if not needed
        // activeCollectConfig.setCheckWearCollect(false);  // Typically for posture/gait

        SensorProUniteCollectTypeConfigure offlineCollectConfig = new SensorProUniteCollectTypeConfigure();

        SensorProUniteConfigure togetherConfig = new SensorProUniteConfigure();
        SensorProUniteIMUConfigure imuConfigure = new SensorProUniteIMUConfigure();
        if (freq == 100) imuConfigure.setFrequency(SensorProUniteIMUConfigure.FrequencyHZ.Frequency_100HZ);
        else if (freq == 200) imuConfigure.setFrequency(SensorProUniteIMUConfigure.FrequencyHZ.Frequency_200HZ);
        else { logToScreen("Unsupported IMU frequency: " + freq + " Hz. Defaulting to 100Hz for SDK."); imuConfigure.setFrequency(SensorProUniteIMUConfigure.FrequencyHZ.Frequency_100HZ); }
        togetherConfig.setImuConfigure(imuConfigure);

        SensorProUniteMAGConfigure magConfigure = new SensorProUniteMAGConfigure();
         if (freq == 100) magConfigure.setFrequency(SensorProUniteMAGConfigure.FrequencyHZ.Frequency_100HZ);
        else if (freq == 200) magConfigure.setFrequency(SensorProUniteMAGConfigure.FrequencyHZ.Frequency_200HZ);
        else { logToScreen("Unsupported MAG frequency: " + freq + " Hz. Defaulting to 100Hz for SDK."); magConfigure.setFrequency(SensorProUniteMAGConfigure.FrequencyHZ.Frequency_100HZ); }
        togetherConfig.setMagConfigure(magConfigure);

        sensorProManager.getCustomProvider().startNewActiveAndOfflineCustomData(
            activeCollectConfig, offlineCollectConfig, togetherConfig, 
            new SensorProCallback<byte[]>() {
                @Override
                public void onResponse(int errorCode, byte[] returnObject) {
                    if (errorCode == 0 || errorCode == 100000) {
                        isCollecting = true;
                        frameCounter = 0; // Reset frame counter on new collection start
                        logToScreen("Data collection started successfully.");
                        runOnUiThread(() -> {
                            if (tvLinAccData!=null) tvLinAccData.setText("LinAcc: Receiving...");
                            if (tvOrientationData!=null) tvOrientationData.setText("Orientation: Receiving...");
                            if (tvRawMagData!=null) tvRawMagData.setText("RawMag: Receiving...");
                            updateButtonStates();
                        });
                    } else {
                        isCollecting = false;
                        logToScreen("Failed to start data collection. Error: " + errorCode);
                        Toast.makeText(StagDataActivity.this, "Start Collection Failed: " + errorCode, Toast.LENGTH_LONG).show();
                        try { PostureDataConvertUtils.stopPostureDetection(); } catch (Exception e) { Log.e(TAG, "Error stopping posture alg on collection fail", e); }
                        if (vqfNative != null) { vqfNative.close(); vqfNative = null; }
                        // Consider unregistering featureDataCallback here
                        runOnUiThread(StagDataActivity.this::updateButtonStates);
                    }
                }
            }
        );
    }

    private final SensorProCallback<FeatureData> featureDataCallback = new SensorProCallback<FeatureData>() {
        @Override
        public void onResponse(int errorCode, FeatureData data) {
            if (!isCollecting || vqfNative == null) {
                return; // Not collecting or VQF not ready
            }

            if (errorCode == 0 || errorCode == 100000) {
                if (data != null) {
                    AccDataArray accDataArray = null;
                    GyroDataArray gyroDataArray = null;
                    // MagDataArray magDataArray = null; // Declared later

                    if (data.getSensorData() != null) {
                        accDataArray = data.getSensorData().getAccDataArray();
                        gyroDataArray = data.getSensorData().getGyroDataArray();
                    }
                    // else {
                        // logToScreen("FeatureDataCallback: data.getSensorData() is NULL."); 
                    // }
                    
                    // Magnetometer data acquisition
                    MagDataArray magDataArray = null;
                    if (data.getPostureData() != null) {
                        magDataArray = data.getPostureData().getMagDataArray();
                    }
                    // else logToScreen("FeatureDataCallback: data.getPostureData() is NULL.");


                    MagIntData[] magIntSamples = null;
                    MagData[] magFloatSamples = null; // Used as fallback
                    boolean useIntMag = false;
                    int magSamplesLength = 0;

                    if (magDataArray != null) {
                        magIntSamples = magDataArray.getMagValueIntArray();
                        if (magIntSamples != null && magIntSamples.length > 0) {
                            useIntMag = true;
                            magSamplesLength = magIntSamples.length;
                        } else {
                            magFloatSamples = magDataArray.getMagValueArray();
                            if (magFloatSamples != null && magFloatSamples.length > 0) {
                                Log.i(TAG, "Info: Using MagData[] (float array) as fallback for magnetometer data."); // Log to system log, not screen
                                magSamplesLength = magFloatSamples.length;
                            }
                            // If both are null/empty, magSamplesLength remains 0
                        }
                    }

                    // Process samples - assuming arrays are synchronized and have same length
                    int numSamples = 0;
                    AccData[] accSamples = (accDataArray != null) ? accDataArray.getAccValueArray() : null;
                    GyroData[] gyroSamples = (gyroDataArray != null) ? gyroDataArray.getGyroValueArray() : null;

                    if (accSamples != null) numSamples = accSamples.length;
                    
                    if (gyroSamples != null) {
                        numSamples = (numSamples == 0) ? gyroSamples.length : Math.min(numSamples, gyroSamples.length);
                    } else if (accSamples != null) { 
                        numSamples = 0; // VQF needs at least Acc & Gyro (for 6D)
                    }
                    
                    boolean useMagForVQF = false;
                    if (magSamplesLength > 0) { // If we have any kind of mag data
                        numSamples = (numSamples == 0) ? magSamplesLength : Math.min(numSamples, magSamplesLength);
                        useMagForVQF = true; // We have mag data to use
                    } else {
                        // If no mag data, VQF runs in 6D if acc & gyro are present.
                        // numSamples is already based on acc and gyro. If one is missing, numSamples would be 0.
                        if (accSamples == null || gyroSamples == null) numSamples = 0;
                    }


                    if (numSamples == 0) {
                        // logToScreen("Not enough synchronized sensor data in this batch.");
                        return;
                    }

                    // For UI update, use the first sample's processed data
                    float[] firstLinAcc = null;
                    float[] firstEuler = null;
                    MagData firstRawMagForUI = null;

                    for (int i = 0; i < numSamples; i++) {
                        AccData rawAcc = (accSamples != null && i < accSamples.length) ? accSamples[i] : null;
                        GyroData rawGyro = (gyroSamples != null && i < gyroSamples.length) ? gyroSamples[i] : null;
                        
                        MagData currentIterMagData = null; // This will hold the mag data for the current sample, converted to float if from int

                        if (useIntMag && magIntSamples != null && i < magIntSamples.length && magIntSamples[i] != null) {
                            MagIntData intData = magIntSamples[i];
                            currentIterMagData = new MagData();
                            currentIterMagData.setMagX((float)intData.getMagX());
                            currentIterMagData.setMagY((float)intData.getMagY());
                            currentIterMagData.setMagZ((float)intData.getMagZ());
                        } else if (magFloatSamples != null && i < magFloatSamples.length && magFloatSamples[i] != null) {
                            currentIterMagData = magFloatSamples[i];
                        }


                        if (rawAcc == null || rawGyro == null) continue; // Need at least Acc and Gyro

                        // 1. Convert Raw SDK data to VQF input units
                        accForVqf[0] = rawAcc.getAccX() * ACC_RAW_TO_G_FACTOR * GRAVITATIONAL_ACCELERATION;
                        accForVqf[1] = rawAcc.getAccY() * ACC_RAW_TO_G_FACTOR * GRAVITATIONAL_ACCELERATION;
                        accForVqf[2] = rawAcc.getAccZ() * ACC_RAW_TO_G_FACTOR * GRAVITATIONAL_ACCELERATION;

                        gyrForVqf[0] = rawGyro.getGyroX() * GYRO_RAW_TO_RADS_FACTOR;
                        gyrForVqf[1] = rawGyro.getGyroY() * GYRO_RAW_TO_RADS_FACTOR;
                        gyrForVqf[2] = rawGyro.getGyroZ() * GYRO_RAW_TO_RADS_FACTOR;
                        
                        float[] currentMagCalibrated = null;
                        if (useMagForVQF && currentIterMagData != null) {
                            float[] rawMagValues = {currentIterMagData.getMagX(), currentIterMagData.getMagY(), currentIterMagData.getMagZ()};
                            // Apply calibration: mag_cal = A_mag_cal * raw_mag_values - B_mag_cal
                            for(int r=0; r<3; ++r){
                                magForVqf[r] = 0;
                                for(int c=0; c<3; ++c){
                                    magForVqf[r] += A_MAG_CAL[r*3+c] * rawMagValues[c];
                                }
                                magForVqf[r] -= B_MAG_CAL[r];
                            }
                            currentMagCalibrated = magForVqf;
                        }

                        // // 2. Update VQF
                        // if (currentMagCalibrated != null) { // This implies useMagForVQF was true and currentIterMagData was not null
                        //     vqfNative.update(gyrForVqf, accForVqf, currentMagCalibrated);
                        //     logToScreen("VQF Update: Magnetometer used (9D mode).");
                        // } else {
                        //     // logToScreen("VQF Update: Magnetometer not used (6D mode).");
                        //     vqfNative.update(gyrForVqf, accForVqf); // 6D update
                        // }
                        vqfNative.update(gyrForVqf, accForVqf); // use 6D update temporarily cuz the inaccuracy of mag data

                        // 3. Get Quaternion from VQF (w, x, y, z)
                        float[] quatWXYZ = (useMagForVQF && currentMagCalibrated != null) ? vqfNative.getQuat() : vqfNative.getQuat6D();

                        // 4. Calculate Linear Acceleration and Euler Angles
                        float[] linAccSensor = calculateLinearAcceleration(accForVqf, quatWXYZ);
                        float[] eulerAnglesDeg = quaternionToEulerAnglesXYZ(quatWXYZ, true);

                        if (i == 0) { // Store first sample's processed data for UI
                            firstLinAcc = linAccSensor;
                            firstEuler = eulerAnglesDeg;
                            if(currentIterMagData != null) firstRawMagForUI = currentIterMagData;
                        }
                        
                        // 5. Write data to CSV buffer
                        // Pass raw SDK float values and processed values
                        writeDataToBuffer(rawAcc, rawGyro, currentIterMagData, linAccSensor, eulerAnglesDeg);
                    }

                    // Update UI with the first processed sample of the batch
                    final float[] uiLinAcc = firstLinAcc;
                    final float[] uiEuler = firstEuler;
                    final MagData uiRawMag = firstRawMagForUI;

                    runOnUiThread(() -> {
                        if (uiLinAcc != null && tvLinAccData != null) {
                            tvLinAccData.setText(String.format(Locale.US, "LinAcc: [%.2f, %.2f, %.2f] m/s²", uiLinAcc[0], uiLinAcc[1], uiLinAcc[2]));
                        }
                        if (uiEuler != null && tvOrientationData != null) {
                            tvOrientationData.setText(String.format(Locale.US, "Orient(R,P,Y): [%.1f, %.1f, %.1f]°", uiEuler[0], uiEuler[1], uiEuler[2]));
                        }
                        if (uiRawMag != null && tvRawMagData != null) {
                            tvRawMagData.setText(String.format(Locale.US, "RawMag: [%.1f, %.1f, %.1f] μT", uiRawMag.getMagX(), uiRawMag.getMagY(), uiRawMag.getMagZ()));
                        }
                    });

                }
            } else {
                logToScreen("FeatureData callback error. Code: " + errorCode);
            }
        }
    };

    private float[] calculateLinearAcceleration(float[] measuredAcc, float[] quatWXYZ) {
        // quatWXYZ is [w, x, y, z]
        float w = quatWXYZ[0];
        float x = quatWXYZ[1];
        float y = quatWXYZ[2];
        float z = quatWXYZ[3];

        // Gravity vector in world frame (ENU: Z is up, so gravity is along negative Z)
        float gx_world = 0;
        float gy_world = 0;
        float gz_world = -GRAVITATIONAL_ACCELERATION;

        // Calculate rotation matrix from quaternion (sensor to world)
        // R = [ [1-2(y^2+z^2),   2(xy-wz),     2(xz+wy)],
        //       [2(xy+wz),     1-2(x^2+z^2),   2(yz-wx)],
        //       [2(xz-wy),     2(yz+wx),     1-2(x^2+y^2)] ]
        // We need to transform world gravity to sensor frame: g_sensor = R_transpose * g_world
        // R_transpose_ij = R_ji

        // Gravity components in sensor frame
        // gx_sensor = (1-2(y*y+z*z))*gx_world + (2*(x*y+w*z))*gy_world + (2*(x*z-w*y))*gz_world
        // gy_sensor = (2*(x*y-w*z))*gx_world + (1-2*(x*x+z*z))*gy_world + (2*(y*z+w*x))*gz_world
        // gz_sensor = (2*(x*z+w*y))*gx_world + (2*(y*z-w*x))*gy_world + (1-2*(x*x+y*y))*gz_world
        // Since gx_world and gy_world are 0:
        float gx_sensor = (2*(x*z - w*y)) * gz_world;
        float gy_sensor = (2*(y*z + w*x)) * gz_world;
        float gz_sensor = (1 - 2*(x*x + y*y)) * gz_world;
        
        // Linear acceleration = measured_acceleration_sensor - gravity_component_in_sensor_frame
        // preprocess.py: lin_acc = measured_acc + (Rot_inv * gravity_world)
        // gravity_sensor_frame in preprocess.py is Rot_inv * [0,0,-g]
        // So, it's measured_acc + gravity_in_sensor_representation_of_world_gravity
        // Here, gx_sensor, gy_sensor, gz_sensor are components of [0,0,-g] in sensor frame.
        // So, linAcc = measuredAcc - [gx_sensor, gy_sensor, gz_sensor] if gx_sensor is gravity's X component.
        // OR linAcc = measuredAcc + [gx_sensor, gy_sensor, gz_sensor] if gx_sensor means the component of external force that *counters* gravity's effect on X.

        // Let's follow preprocess.py: lin_acc = measured_acc + gravity_in_sensor_frame
        // where gravity_in_sensor_frame is (world_gravity_vector_transformed_to_sensor_frame)
        // World gravity vector is typically [0, 0, -g] if Z is up.
        // The derived gx_sensor, gy_sensor, gz_sensor are components of [0,0,-g] in sensor frame.
        float[] linAcc = new float[3];
        linAcc[0] = measuredAcc[0] + gx_sensor;
        linAcc[1] = measuredAcc[1] + gy_sensor;
        linAcc[2] = measuredAcc[2] + gz_sensor;
        return linAcc;
    }

    private float[] quaternionToEulerAnglesXYZ(float[] qWXYZ, boolean inDegrees) {
        float w = qWXYZ[0];
        float x = qWXYZ[1];
        float y = qWXYZ[2];
        float z = qWXYZ[3];
        float[] eulerRad = new float[3];

        // Roll (x-axis rotation)
        double sinr_cosp = 2 * (w * x + y * z);
        double cosr_cosp = 1 - 2 * (x * x + y * y);
        eulerRad[0] = (float) Math.atan2(sinr_cosp, cosr_cosp);

        // Pitch (y-axis rotation)
        double sinp = 2 * (w * y - z * x);
        if (Math.abs(sinp) >= 1)
            eulerRad[1] = (float) Math.copySign(Math.PI / 2, sinp); // Use 90 degrees if out of range
        else
            eulerRad[1] = (float) Math.asin(sinp);

        // Yaw (z-axis rotation)
        double siny_cosp = 2 * (w * z + x * y);
        double cosy_cosp = 1 - 2 * (y * y + z * z);
        eulerRad[2] = (float) Math.atan2(siny_cosp, cosy_cosp);

        if (inDegrees) {
            eulerRad[0] = (float) Math.toDegrees(eulerRad[0]);
            eulerRad[1] = (float) Math.toDegrees(eulerRad[1]);
            eulerRad[2] = (float) Math.toDegrees(eulerRad[2]);
        }
        return eulerRad;
    }

    private void stopDataCollection() {
        if (!isCollecting) {
            logToScreen("Not currently collecting data.");
            return;
        }
        logToScreen("Attempting to stop data collection...");
        stopDataCollectionLogic();
    }
    
    private void stopDataCollectionLogic() {
        if (sensorProManager == null) {
            logToScreen("SDK not initialized, cannot stop collection.");
            isCollecting = false; 
            if (vqfNative != null) { vqfNative.close(); vqfNative = null; }
            runOnUiThread(this::updateButtonStates);
            stopCsvWriter(); 
            return;
        }
        logToScreen("Executing stopDataCollectionLogic..."); 

        sensorProManager.getCustomProvider().stopCollectCustomData(new SensorProCallback<byte[]>() {
            @Override
            public void onResponse(int errorCode, byte[] returnObject) {
                if (errorCode == 0 || errorCode == 100000) {
                    logToScreen("Data collection stopped successfully (SDK callback).");
                } else {
                    logToScreen("Failed to stop data collection (SDK callback). Error: " + errorCode + ". Proceeding with cleanup.");
                }
                isCollecting = false; // Set here regardless of algorithm stop success
                if (vqfNative != null) { // Close VQF here after collection stream stops
                    vqfNative.close();
                    vqfNative = null;
                    logToScreen("VQF closed.");
                }
                
                // Perform further cleanup (algorithm stop, CSV)
                // This part was previously in a new thread, ensure it's handled robustly.
                // For simplicity, let's do it sequentially after SDK call, but on main callback thread is okay.
                try {
                    PostureDataConvertUtils.stopPostureDetection();
                    logToScreen("Posture detection algorithm stopped.");
                } catch (Exception e) {
                    logToScreen("Error stopping posture detection algorithm: " + e.getMessage());
                    Log.e(TAG, "Error stopping posture algorithm", e);
                }

                // Unregister FeatureData callback - check SDK for an unregister method
                // sensorProManager.getCustomProvider().unregisterFeatureDataCallback(featureDataCallback);
                // logToScreen("FeatureData callback should be unregistered if an API exists.");

                stopCsvWriter(); // Stop and flush CSV writer

                runOnUiThread(() -> {
                    updateButtonStates(); // Reflect that collection is stopped
                    if (tvLinAccData!=null) tvLinAccData.setText("LinAcc: -");
                    if (tvOrientationData!=null) tvOrientationData.setText("Orientation: -");
                    if (tvRawMagData!=null) tvRawMagData.setText("RawMag: -");
                    if (tvCurrentFileName != null && currentCsvFile == null) { 
                        // tvCurrentFileName.setText("CSV Stopped. Ready for new file.");
                    }
                    logToScreen("Cleanup finished. Ready to start new collection if connected.");
                });
            }
        });
    }

    private void initCsvWriter() {
        if (currentCsvFileWriter != null) {
            logToScreen("CSV writer already initialized.");
            return;
        }
        // frameCounter reset is now done in startDataCollection/startNewActiveAndOfflineCustomData callback
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "STAG_VQF_DATA_" + timeStamp + ".csv"; // Updated filename
            File documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (documentsDir == null) {
                logToScreen("Error: External documents directory not available.");
                Toast.makeText(this, "Cannot access storage for CSV.", Toast.LENGTH_LONG).show();
                return;
            }
            if (!documentsDir.exists()) {
                if (!documentsDir.mkdirs()) {
                    logToScreen("Error: Could not create documents directory.");
                    Toast.makeText(this, "Cannot create storage directory.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            currentCsvFile = new File(documentsDir, fileName);
            currentCsvFileWriter = new BufferedWriter(new FileWriter(currentCsvFile));
            currentCsvFileWriter.write(CSV_HEADER);
            currentCsvFileWriter.newLine();
            logToScreen("CSV file initialized: " + fileName);
            if (tvCurrentFileName != null) {
                tvCurrentFileName.setText("Saving to: " + fileName);
            }

            dataFlushRunnable = new Runnable() {
                @Override
                public void run() {
                    boolean shouldFlush = false;
                    // 在检查和决定是否刷写前获取锁
                    synchronized (csvBufferLock) {
                        if ((isCollecting || currentCsvFileWriter != null) && !csvDataBuffer.isEmpty()) {
                            shouldFlush = true;
                            // logToScreen("Periodic flush: flushing " + csvDataBuffer.size() + " lines to CSV."); // 移动或修改日志
                        }
                    }

                    if (shouldFlush) {
//                        logToScreen("Periodic flush: flushing lines to CSV."); // 避免在锁内获取size，因为size可能已改变
                        flushCsvBufferToFile(false); // Don't close if just periodic
                    }

                    // 只有在仍然收集数据或写入器仍然打开时才重新调度
                    if (isCollecting || currentCsvFileWriter != null) {
                         dataFlushHandler.postDelayed(this, DATA_FLUSH_INTERVAL_MS);
                    }
                }
            };
            dataFlushHandler.postDelayed(dataFlushRunnable, DATA_FLUSH_INTERVAL_MS);

        } catch (IOException e) {
            logToScreen("Error initializing CSV writer: " + e.getMessage());
            Log.e(TAG, "initCsvWriter error", e);
            currentCsvFile = null;
            currentCsvFileWriter = null;
        }
    }

    // Updated to include processed data
    private void writeDataToBuffer(AccData rawAcc, GyroData rawGyro, MagData rawMag,
                                   float[] linAcc, float[] eulerAnglesDeg) {
        if (currentCsvFileWriter == null) { // Check if writer is available (not closed yet)
            return;
        }
        
        String rAccX = "N/A", rAccY = "N/A", rAccZ = "N/A";
        String rGyroX = "N/A", rGyroY = "N/A", rGyroZ = "N/A";
        String rMagX = "N/A", rMagY = "N/A", rMagZ = "N/A";

        if (rawAcc != null) {
            rAccX = String.valueOf(rawAcc.getAccX());
            rAccY = String.valueOf(rawAcc.getAccY());
            rAccZ = String.valueOf(rawAcc.getAccZ());
        }
        if (rawGyro != null) {
            rGyroX = String.valueOf(rawGyro.getGyroX());
            rGyroY = String.valueOf(rawGyro.getGyroY());
            rGyroZ = String.valueOf(rawGyro.getGyroZ());
        }
        if (rawMag != null) {
            rMagX = String.valueOf(rawMag.getMagX());
            rMagY = String.valueOf(rawMag.getMagY());
            rMagZ = String.valueOf(rawMag.getMagZ());
        }

        String lAccX = (linAcc != null) ? String.format(Locale.US, "%.4f", linAcc[0]) : "N/A";
        String lAccY = (linAcc != null) ? String.format(Locale.US, "%.4f", linAcc[1]) : "N/A";
        String lAccZ = (linAcc != null) ? String.format(Locale.US, "%.4f", linAcc[2]) : "N/A";

        String oriX = (eulerAnglesDeg != null) ? String.format(Locale.US, "%.2f", eulerAnglesDeg[0]) : "N/A";
        String oriY = (eulerAnglesDeg != null) ? String.format(Locale.US, "%.2f", eulerAnglesDeg[1]) : "N/A";
        String oriZ = (eulerAnglesDeg != null) ? String.format(Locale.US, "%.2f", eulerAnglesDeg[2]) : "N/A";

        String csvLine = frameCounter + "," +
                         rAccX + "," + rAccY + "," + rAccZ + "," +
                         rGyroX + "," + rGyroY + "," + rGyroZ + "," +
                         rMagX + "," + rMagY + "," + rMagZ + "," +
                         lAccX + "," + lAccY + "," + lAccZ + "," +
                         oriX + "," + oriY + "," + oriZ;
        
        boolean bufferReachedFlushSize = false;
        synchronized (csvBufferLock) {
            csvDataBuffer.add(csvLine);
            frameCounter++; // Increment frameCounter while holding the lock to keep it in sync with buffer adds
            if (csvDataBuffer.size() >= DATA_BUFFER_FLUSH_SIZE) {
                bufferReachedFlushSize = true;
            }
        }

        if (bufferReachedFlushSize) {
            // logToScreen("Buffer flush (size trigger): flushing " + DATA_BUFFER_FLUSH_SIZE + " lines to CSV.");
            flushCsvBufferToFile(false); // Don't close if just size trigger
        }
    }

    private void flushCsvBufferToFile(boolean isClosing) {
        if (currentCsvFileWriter == null) {
            if (!isClosing) logToScreen("CSV writer not available for flushing.");
            return;
        }

        List<String> linesToWrite; // 用于存储要写入的行的副本

        synchronized (csvBufferLock) {
            // 如果缓冲区为空且不是正在关闭，则无需执行任何操作
            if (csvDataBuffer.isEmpty() && !isClosing) {
                return;
            }
            // 创建缓冲区的副本以进行迭代
            linesToWrite = new ArrayList<>(csvDataBuffer);
            // 清空原始缓冲区，因为它将被写入
            csvDataBuffer.clear();
        }
        
        // 如果副本为空（例如，在获取锁后缓冲区被清空了，并且不是正在关闭），则无需执行任何操作
        if (linesToWrite.isEmpty() && !isClosing) {
             return;
        }

        try {
            for (String line : linesToWrite) { // 遍历副本
                currentCsvFileWriter.write(line);
                currentCsvFileWriter.newLine();
            }
            // csvDataBuffer.clear(); // 已在上面的同步块中完成
            currentCsvFileWriter.flush(); 
            if (isClosing) {
                logToScreen("Flushed remaining data and closing CSV file: " + (currentCsvFile != null ? currentCsvFile.getName() : "N/A"));
                currentCsvFileWriter.close();
                currentCsvFileWriter = null; 
                final File savedFile = currentCsvFile; 
                runOnUiThread(() -> { 
                    if (tvCurrentFileName != null) {
                        tvCurrentFileName.setText("Saved to: " + (savedFile != null ? savedFile.getName() : "N/A") + " (Stopped)");
                    }
                });
                currentCsvFile = null; 
            }
        } catch (IOException e) {
            logToScreen("Error writing to CSV file: " + e.getMessage());
            Log.e(TAG, "flushCsvBufferToFile error", e);
            if(isClosing){ // 确保在关闭时出错也清理引用
                currentCsvFileWriter = null;
                currentCsvFile = null;
            }
        }
    }

    private void stopCsvWriter() {
        logToScreen("Stopping CSV writer...");
        dataFlushHandler.removeCallbacks(dataFlushRunnable); 
        flushCsvBufferToFile(true); 
    }
} 