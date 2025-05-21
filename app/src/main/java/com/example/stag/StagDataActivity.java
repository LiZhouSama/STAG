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

public class StagDataActivity extends AppCompatActivity {

    private static final String TAG = "StagDataActivity";
    private static final int S_TAG_DEVICE_ITEM_TYPE = 8; // From doc_STAG.txt
    private static final int REQUEST_ENABLE_BT = 1001;
    private static final long SCAN_PERIOD = 10000; // Scan for 10 seconds

    // UI Elements
    private Button btnScanDevice, btnConnectDevice, btnStartDataCollection, btnStopDataCollection;
    private Spinner spinnerScannedDevices;
    private TextView tvConnectionStatus, tvAccData, tvGyroData, tvMagData, tvLogOutput;
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
    private static final String CSV_HEADER = "frame_index,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z";
    private static final int DATA_BUFFER_FLUSH_SIZE = 100; // Write to file every 100 samples
    private static final long DATA_FLUSH_INTERVAL_MS = 5000; // Or every 5 seconds
    private List<String> csvDataBuffer = new ArrayList<>();
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
        tvAccData = findViewById(R.id.tvAccData);
        tvGyroData = findViewById(R.id.tvGyroData);
        tvMagData = findViewById(R.id.tvMagData);
        tvLogOutput = findViewById(R.id.tvLogOutput);
        tvCurrentFileName = findViewById(R.id.tvCurrentFileName); // Make sure this ID exists in your XML if you use it

        scannedDevicesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        scannedDevicesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerScannedDevices.setAdapter(scannedDevicesAdapter);

        btnScanDevice.setOnClickListener(v -> toggleScan());
        btnConnectDevice.setOnClickListener(v -> connectSelectedDevice());
        btnStartDataCollection.setOnClickListener(v -> {
            if (isConnected && !isCollecting) {
                initCsvWriter(); // Initialize CSV writer when collection starts
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
                stopDataCollection(); // This will eventually call stopCsvWriter via stopDataCollectionLogic
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
            // Optional: auto-scroll
            // final ScrollView scrollView = findViewById(R.id.scrollViewLogs); // Assuming you have a ScrollView around tvLogOutput
            // if (scrollView != null) {
            //     scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            // }
        });
    }

    private void updateButtonStates() {
        btnScanDevice.setText(isScanning ? "Stop Scan" : "Scan S-TAG Devices");
        btnConnectDevice.setEnabled(!isScanning && selectedDevice != null && !isConnected);
        btnStartDataCollection.setEnabled(isConnected && !isCollecting);
        btnStopDataCollection.setEnabled(isConnected && isCollecting);
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
            Toast.makeText(this, "Device not connected. Please connect first.", Toast.LENGTH_SHORT).show(); // More informative
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

        UserProfileConfig userProfileConfig = new UserProfileConfig(); // Keep creation for now, but don't send
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
                    logToScreen("Failed to configure user profile. Error: " + errorCode + ". Is device still connected? " + (isConnected ? "Yes" : "No"));
                    Toast.makeText(StagDataActivity.this, "User Profile Config Failed: " + errorCode, Toast.LENGTH_LONG).show();
                    runOnUiThread(StagDataActivity.this::updateButtonStates);
                }
            }
        });
        */
    }

    private void startAlgorithmAndActualCollection() {
        // 2. Start Algorithm (as per doc_STAG.txt - "开启算法")
        // freq: 100 or 200; cpFusionMode: 0 (GYRO only), 1 (ACC+GYRO), 2 (ACC+GYRO+MAG)
        int freq = 100; // Use 100Hz or 200Hz as needed
        int cpFusionMode = 2; // For ACC, GYRO, MAG
        try {
            PostureDataConvertUtils.startPostureDetection(freq, cpFusionMode);
            logToScreen("Posture detection algorithm started (freq: " + freq + "Hz, mode: " + cpFusionMode + ").");
        } catch (Exception e) {
            logToScreen("Error starting posture detection algorithm: " + e.getMessage());
            Log.e(TAG, "Error starting posture detection algorithm", e);
            return; // Do not proceed if algorithm fails to start
        }

        // 3. Register Feature Data Callback (as per doc_STAG.txt - "获取实时数据")
        // This should be registered before starting the measurement collection
        sensorProManager.getCustomProvider().registerFeatureDataCallback(featureDataCallback);
        logToScreen("FeatureData callback registered.");

        // 4. Configure and Start Realtime Measurement (as per doc_STAG.txt - "开启实时测量")
        SensorProUniteCollectTypeConfigure activeCollectConfig = new SensorProUniteCollectTypeConfigure();
        activeCollectConfig.setParseData(true); // SDK should parse the data
        // activeCollectConfig.setRriInterval(10); // Not collecting RRI, so not strictly needed
        // activeCollectConfig.setRealtimeMesureTimeOut(10 * 60 * 1000); // 10 minutes, or 0 for indefinite until stopped.
                                                                     // Let's use 0 for continuous until explicitly stopped.
        activeCollectConfig.setRealtimeMesureTimeOut(0); 

        activeCollectConfig.setCollectAcc(true);
        activeCollectConfig.setCollectGyro(true);
        activeCollectConfig.setCollectMag(true);
        // activeCollectConfig.setCollectGaitPosture(true); // We only need raw sensor data, not higher-level gait parameters for now
        // activeCollectConfig.setCheckWearCollect(true);   // Typically for posture/gait

        SensorProUniteCollectTypeConfigure offlineCollectConfig = new SensorProUniteCollectTypeConfigure(); // Empty for no offline collection

        SensorProUniteConfigure togetherConfig = new SensorProUniteConfigure();
        SensorProUniteIMUConfigure imuConfigure = new SensorProUniteIMUConfigure();
        imuConfigure.setFrequency(SensorProUniteIMUConfigure.FrequencyHZ.Frequency_100HZ); // Or Frequency_200HZ, must match algorithm freq if alg needs it
        togetherConfig.setImuConfigure(imuConfigure); // Corrected: Use setImuConfigure for ACC/GYRO freq

        SensorProUniteMAGConfigure magConfigure = new SensorProUniteMAGConfigure();
        magConfigure.setFrequency(SensorProUniteMAGConfigure.FrequencyHZ.Frequency_100HZ); // Or Frequency_200HZ
        togetherConfig.setMagConfigure(magConfigure);

        sensorProManager.getCustomProvider().startNewActiveAndOfflineCustomData(
            activeCollectConfig, 
            offlineCollectConfig, 
            togetherConfig, 
            new SensorProCallback<byte[]>() {
                @Override
                public void onResponse(int errorCode, byte[] returnObject) {
                    if (errorCode == 0 || errorCode == 100000) { // Success codes
                        isCollecting = true;
                        logToScreen("Data collection started successfully.");
                        runOnUiThread(() -> {
                            tvAccData.setText("-");
                            tvGyroData.setText("-");
                            tvMagData.setText("-");
                            updateButtonStates();
                        });
                    } else {
                        isCollecting = false;
                        logToScreen("Failed to start data collection. Error: " + errorCode);
                        Toast.makeText(StagDataActivity.this, "Start Collection Failed: " + errorCode, Toast.LENGTH_LONG).show();
                        // Attempt to stop algorithm if collection failed to start properly
                        try {
                            PostureDataConvertUtils.stopPostureDetection();
                            logToScreen("Posture detection algorithm stopped due to collection start failure.");
                        } catch (Exception e) {
                            logToScreen("Error stopping posture algorithm after collection failure: " + e.getMessage());
                        }
                        // Unregister callback if start failed
                        // sensorProManager.getCustomProvider().unregisterFeatureDataCallback(featureDataCallback); // Check for unregister method
                        runOnUiThread(StagDataActivity.this::updateButtonStates);
                    }
                }
            }
        );
    }

    private final SensorProCallback<FeatureData> featureDataCallback = new SensorProCallback<FeatureData>() {
        @Override
        public void onResponse(int errorCode, FeatureData data) {
            if (errorCode == 0 || errorCode == 100000) { // Success codes
                if (data != null) {
                    // From doc_STAG.txt "获取实时数据" and "向算法输入原始数据" examples
                    // LogUtils.info(TAG, "FeatureData received: " + JSON.toJSONString(data)); // Detailed log
                    
                    // Input to algorithm (optional if you only need raw data displayed and not algorithm results)
                    // try {
                    //     List<PostureResult> postureResults = PostureDataConvertUtils.getBoltParseResult(data, true); // true for MAG collected
                    //     if (postureResults != null && !postureResults.isEmpty()) {
                    //         // Log or use postureResults if needed. It also contains raw-ish data in nativeFeature.
                    //         // logToScreen("Posture Algorithm Result: " + JSON.toJSONString(postureResults.get(0)));
                    //     }
                    // } catch (Exception e) {
                    //    logToScreen("Error parsing data with PostureDataConvertUtils: " + e.getMessage());
                    // }

                    AccDataArray accDataArray = null;
                    GyroDataArray gyroDataArray = null;
                    MagDataArray magDataArray = null;

                    if (data.getSensorData() != null) {
                        LogUtils.info(TAG, "featureDataCallback: data.getSensorData() is NOT null");
                        accDataArray = data.getSensorData().getAccDataArray();
                        gyroDataArray = data.getSensorData().getGyroDataArray();
                        // Previous error indicated SensorData does not have getPostureData()
                        // if (data.getSensorData().getPostureData() != null) { 
                        //    LogUtils.d(TAG, "featureDataCallback: data.getSensorData().getPostureData() is NOT null");
                        // }
                    } else {
                        LogUtils.info(TAG, "featureDataCallback: data.getSensorData() IS NULL");
                    }
                    
                    if (data.getPostureData() != null) { 
                        LogUtils.info(TAG, "featureDataCallback: data.getPostureData() is NOT null");
                        magDataArray = data.getPostureData().getMagDataArray();
                        if (magDataArray != null) {
                            LogUtils.info(TAG, "featureDataCallback: data.getPostureData().getMagDataArray() is NOT null");
                        } else {
                            LogUtils.info(TAG, "featureDataCallback: data.getPostureData().getMagDataArray() IS NULL");
                        }
                    } else {
                        LogUtils.info(TAG, "featureDataCallback: data.getPostureData() IS NULL");
                    }

                    // Log raw FeatureData to see its structure for MAG
                    LogUtils.info(TAG, "Full FeatureData: " + JSON.toJSONString(data));

                    final AccDataArray finalAcc = accDataArray;
                    final GyroDataArray finalGyro = gyroDataArray;
                    final MagDataArray finalMag = magDataArray;

                    // Prepare data for CSV writing
                    AccData[] accSamples = null;
                    GyroData[] gyroSamples = null;
                    Object[] magSamplesArray = null; // Assuming it's an array of samples
                    List<?> magSamplesList = null;    // Or a list of samples

                    if (finalAcc != null && finalAcc.getAccValueArray() != null) {
                        accSamples = finalAcc.getAccValueArray(); 
                    }
                    if (finalGyro != null && finalGyro.getGyroValueArray() != null) {
                        gyroSamples = finalGyro.getGyroValueArray(); 
                    }
                    if (finalMag != null) {
                        Object magIntArrayObject = null;
                        try {
                            java.lang.reflect.Method method = finalMag.getClass().getMethod("getMagValueIntArray");
                            magIntArrayObject = method.invoke(finalMag);
                        } catch (NoSuchMethodException nsme) {
                            try {
                                java.lang.reflect.Field field = finalMag.getClass().getDeclaredField("magValueIntArray");
                                field.setAccessible(true);
                                magIntArrayObject = field.get(finalMag);
                            } catch (Exception e) { logToScreen("Failed to access magValueIntArray field in callback: " + e.getMessage()); }
                        } catch (Exception e) { logToScreen("Error invoking getMagValueIntArray() in callback: " + e.getMessage()); }

                        if (magIntArrayObject != null) {
                            if (magIntArrayObject.getClass().isArray()) {
                                magSamplesArray = (Object[]) magIntArrayObject; // Cast to Object array
                            } else if (magIntArrayObject instanceof java.util.List) {
                                magSamplesList = (java.util.List<?>) magIntArrayObject;
                            }
                        }
                    }

                    // Determine the number of samples to iterate through (minimum of all available)
                    int numSamples = 0;
                    if (accSamples != null) numSamples = accSamples.length;
                    if (gyroSamples != null) numSamples = Math.min(numSamples, gyroSamples.length);
                    else numSamples = 0; // If gyro is null, no synced samples with acc
                    
                    if (magSamplesArray != null) numSamples = Math.min(numSamples, magSamplesArray.length);
                    else if (magSamplesList != null) numSamples = Math.min(numSamples, magSamplesList.size());
                    else numSamples = 0; // If mag is null, no synced samples with acc/gyro

                    if (numSamples == 0 && (accSamples != null && accSamples.length > 0)){
                        // If only ACC data is available, or others are empty, log at least ACC
                        // This case might need special handling if you expect all sensors or none
                        LogUtils.info(TAG, "Processing single ACC sample as others are missing/empty. Total ACC samples: " + accSamples.length);
                        numSamples = accSamples.length; // Process all acc samples if it's the only one with data
                    } else if (numSamples == 0 && (gyroSamples != null && gyroSamples.length > 0)){
                        LogUtils.info(TAG, "Processing single GYRO sample as others are missing/empty. Total GYRO samples: " + gyroSamples.length);
                        numSamples = gyroSamples.length; // Process all gyro samples
                    } else if (numSamples == 0 && ((magSamplesArray != null && magSamplesArray.length > 0) || (magSamplesList != null && !magSamplesList.isEmpty()))){
                        LogUtils.info(TAG, "Processing single MAG sample as others are missing/empty.");
                        numSamples = (magSamplesArray != null) ? magSamplesArray.length : magSamplesList.size();
                    }

                    if (numSamples > 0) {
                        logToScreen("Processing " + numSamples + " samples from current FeatureData callback.");
                    }

                    AccData accForUi = null;
                    GyroData gyroForUi = null;
                    Object magForUi = null;

                    for (int i = 0; i < numSamples; i++) {
                        AccData currentAcc = (accSamples != null && i < accSamples.length) ? accSamples[i] : null;
                        GyroData currentGyro = (gyroSamples != null && i < gyroSamples.length) ? gyroSamples[i] : null;
                        Object currentMag = null;
                        if (magSamplesArray != null && i < magSamplesArray.length) {
                            currentMag = magSamplesArray[i];
                        } else if (magSamplesList != null && i < magSamplesList.size()) {
                            currentMag = magSamplesList.get(i);
                        }
                        
                        writeDataToBuffer(currentAcc, currentGyro, currentMag);

                        if (i == 0) { // For UI, only update with the first sample of the batch
                            accForUi = currentAcc;
                            gyroForUi = currentGyro;
                            magForUi = currentMag;
                        }
                    }

                    // Create final variables for use in lambda to update UI (with the first sample of the batch)
                    final AccData finalAccSampleForUi = accForUi;
                    final GyroData finalGyroSampleForUi = gyroForUi;
                    final Object finalMagSampleForUi = magForUi;

                    runOnUiThread(() -> {
                        if (finalAccSampleForUi != null) { 
                            tvAccData.setText(JSON.toJSONString(finalAccSampleForUi));
                        }
                        if (finalGyroSampleForUi != null) { 
                            tvGyroData.setText(JSON.toJSONString(finalGyroSampleForUi));
                        }
                        if (finalMagSampleForUi != null) { 
                            tvMagData.setText(JSON.toJSONString(finalMagSampleForUi));
                        }
                    });
                }
            } else {
                logToScreen("FeatureData callback error. Code: " + errorCode);
            }
        }
    };

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
                // isCollecting should be set to false once the intention to stop is confirmed by SDK or immediately.
                // Let's set it here to allow UI to update botones even if algorithm stop is slow.
                isCollecting = false;
                runOnUiThread(() -> {
                    // Update buttons to reflect that collection is stopping/stopped initially
                    // The Start button might not enable until algorithm fully stops if isConnected depends on it.
                    updateButtonStates(); 
                });

                // Perform further cleanup asynchronously to avoid blocking the callback thread
                new Thread(() -> {
                    logToScreen("Background thread: Attempting to stop posture algorithm...");
                    try {
                        PostureDataConvertUtils.stopPostureDetection();
                        logToScreen("Background thread: Posture detection algorithm stopped.");
                    } catch (Exception e) {
                        logToScreen("Background thread: Error stopping posture detection algorithm: " + e.getMessage());
                        Log.e(TAG, "Error stopping posture algorithm", e);
                    }

                    logToScreen("Background thread: Unregistering FeatureData callback (placeholder).");
                    // sensorProManager.getCustomProvider().unregisterFeatureDataCallback(featureDataCallback);
                    // logToScreen("FeatureData callback should be unregistered if an API exists.");

                    logToScreen("Background thread: Stopping CSV writer...");
                    stopCsvWriter(); // Stop and flush CSV writer

                    // Final UI update on the main thread
                    runOnUiThread(() -> {
                        logToScreen("Background thread: Final UI update. isConnected: " + isConnected + ", isCollecting: " + isCollecting);
                        updateButtonStates(); // This will now correctly use isCollecting = false
                        tvAccData.setText("-");
                        tvGyroData.setText("-");
                        tvMagData.setText("-");
                        if (tvCurrentFileName != null && currentCsvFile == null) { // If file was closed by stopCsvWriter
                            // tvCurrentFileName.setText("CSV Stopped. Ready for new file."); // Or similar
                        }
                        logToScreen("Cleanup finished. Ready to start new collection if connected.");
                    });
                }).start();
            }
        });
    }

    private void initCsvWriter() {
        if (currentCsvFileWriter != null) {
            logToScreen("CSV writer already initialized.");
            return;
        }
        frameCounter = 0; // Reset frame counter for new file
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "STAG_DATA_" + timeStamp + ".csv";
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

            // Setup periodic flush
            dataFlushRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isCollecting && !csvDataBuffer.isEmpty()) {
                        logToScreen("Periodic flush: flushing " + csvDataBuffer.size() + " lines to CSV.");
                        flushCsvBufferToFile(false);
                    }
                    dataFlushHandler.postDelayed(this, DATA_FLUSH_INTERVAL_MS);
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

    private void writeDataToBuffer(AccData acc, GyroData gyro, Object magSample) {
        if (currentCsvFileWriter == null || !isCollecting) {
            // Not collecting or writer not ready
            return;
        }
        long currentFrameIndex = frameCounter; // Use current frameCounter value for this line

        // Assuming AccData, GyroData, and magSample (from reflection) have x, y, z fields
        // This part will need careful handling of the actual structure of these objects
        // For now, let's assume they can be JSON stringified and we extract from there or they have direct getters.
        // This is a placeholder and will likely need refinement based on actual data object structure.
        
        String accX = "N/A", accY = "N/A", accZ = "N/A";
        String gyroX = "N/A", gyroY = "N/A", gyroZ = "N/A";
        String magX = "N/A", magY = "N/A", magZ = "N/A";

        try {
            // For AccData (assuming it has getX, getY, getZ or similar, or direct public fields x,y,z)
            // This is highly speculative. Replace with actual field access or getters.
            if (acc != null) {
                // Example: If AccData has public float x, y, z; fields
                 accX = String.valueOf(acc.getAccX()); 
                 accY = String.valueOf(acc.getAccY());
                 accZ = String.valueOf(acc.getAccZ());
            }
        } catch (Exception e) { logToScreen("Error getting ACC data for CSV: " + e.getMessage()); }

        try {
            if (gyro != null) {
                 gyroX = String.valueOf(gyro.getGyroX());
                 gyroY = String.valueOf(gyro.getGyroY());
                 gyroZ = String.valueOf(gyro.getGyroZ());
            }
        } catch (Exception e) { logToScreen("Error getting GYRO data for CSV: " + e.getMessage()); }
        
        try {
            if (magSample != null) {
                // magSample is the object from magIntArray[0]
                // Assuming it has magX, magY, magZ as fields (likely int based on 'IntArray')
                // We need to use reflection again here if fields are not public or no getters.
                // For simplicity, let's assume JSON stringification and then parsing, or direct field access if known.
                // This is very inefficient and just for a placeholder.
                // A better way would be to cast magSample to its actual type if known or use reflection for specific fields.
                String magJson = JSON.toJSONString(magSample);
                com.alibaba.fastjson.JSONObject magObj = JSON.parseObject(magJson);
                if (magObj != null) {
                    magX = magObj.getString("magX");
                    magY = magObj.getString("magY");
                    magZ = magObj.getString("magZ");
                }
            }
        } catch (Exception e) { logToScreen("Error getting MAG data for CSV: " + e.getMessage()); }

        String csvLine = currentFrameIndex + "," + accX + "," + accY + "," + accZ + "," +
                         gyroX + "," + gyroY + "," + gyroZ + "," +
                         magX + "," + magY + "," + magZ;
        csvDataBuffer.add(csvLine);
        frameCounter++; // Increment frame counter for the next set of data

        if (csvDataBuffer.size() >= DATA_BUFFER_FLUSH_SIZE) {
            logToScreen("Buffer flush (size trigger): flushing " + csvDataBuffer.size() + " lines to CSV.");
            flushCsvBufferToFile(false);
        }
    }

    private void flushCsvBufferToFile(boolean isClosing) {
        if (currentCsvFileWriter == null) {
            if (!isClosing) logToScreen("CSV writer not available for flushing."); // Don't log if intentionally closing
            return;
        }
        if (csvDataBuffer.isEmpty() && !isClosing) {
            // logToScreen("CSV buffer is empty, nothing to flush."); // Optional: reduce verbose logging
            return;
        }

        try {
            for (String line : csvDataBuffer) {
                currentCsvFileWriter.write(line);
                currentCsvFileWriter.newLine();
            }
            csvDataBuffer.clear();
            currentCsvFileWriter.flush(); // Ensure data is written to disk
            if (isClosing) {
                logToScreen("Flushed remaining data and closing CSV file: " + (currentCsvFile != null ? currentCsvFile.getName() : "N/A"));
                currentCsvFileWriter.close();
                currentCsvFileWriter = null;
                final File savedFile = currentCsvFile; // Create a final copy for lambda
                runOnUiThread(() -> { // Ensure UI update is on main thread
                    if (tvCurrentFileName != null) {
                        tvCurrentFileName.setText("Saved to: " + (savedFile != null ? savedFile.getName() : "N/A") + " (Stopped)");
                    }
                });
                currentCsvFile = null; // Ready for next session or sharing
            }
        } catch (IOException e) {
            logToScreen("Error writing to CSV file: " + e.getMessage());
            Log.e(TAG, "flushCsvBufferToFile error", e);
        }
    }

    private void stopCsvWriter() {
        logToScreen("Stopping CSV writer...");
        dataFlushHandler.removeCallbacks(dataFlushRunnable); // Stop periodic flush
        flushCsvBufferToFile(true); // Flush any remaining data and close the file
    }

    // --- Permission Handling --- (To be filled)
    // --- Bluetooth Scan Logic --- (To be filled)
    // --- Bluetooth Connection Logic --- (To be filled)
    // --- Data Collection Logic --- (To be filled)

} 