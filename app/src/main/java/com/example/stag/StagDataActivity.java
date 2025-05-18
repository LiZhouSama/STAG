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
        // scrollViewLogs = findViewById(R.id.scrollViewLogs); // Add ScrollView if you have one around tvLogOutput

        scannedDevicesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        scannedDevicesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerScannedDevices.setAdapter(scannedDevicesAdapter);

        btnScanDevice.setOnClickListener(v -> toggleScan());
        btnConnectDevice.setOnClickListener(v -> connectSelectedDevice());
        btnStartDataCollection.setOnClickListener(v -> startDataCollection());
        btnStopDataCollection.setOnClickListener(v -> stopDataCollection());

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
            public void onResponse(int code, SensorProDeviceInfo deviceInfo) {
                String deviceName = deviceInfo != null ? getDeviceDisplayNameFromInfo(deviceInfo) : (selectedDevice != null ? getDeviceDisplayName(selectedDevice) : "Selected Device");
                String statusMessage = "Device State Changed. Device: " + deviceName + ", Code: " + code;
                boolean prevIsConnected = isConnected;

                switch (code) {
                    case 1: // DEVICE_CONNECTING
                        statusMessage += " (Connecting)";
                        isConnected = false; // Still connecting, not fully connected
                        tvConnectionStatus.setText("Status: Connecting to " + deviceName);
                        break;
                    case 2: // DEVICE_CONNECTED
                        statusMessage += " (Connected)";
                        isConnected = true; 
                        selectedDevice = (deviceInfo != null && deviceInfo.getDeviceIdentify() != null) ? bluetoothAdapter.getRemoteDevice(deviceInfo.getDeviceIdentify()) : selectedDevice; // Update selectedDevice if info is richer
                        tvConnectionStatus.setText("Status: Connected to " + deviceName);
                        if (!prevIsConnected) { // Only show toast if state changed to connected
                           Toast.makeText(StagDataActivity.this, "Device " + deviceName + " connected!", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case 3: // DEVICE_DISCONNECTED
                        statusMessage += " (Disconnected)";
                        isConnected = false;
                        isCollecting = false; 
                        tvConnectionStatus.setText("Status: Disconnected from " + deviceName);
                        if (prevIsConnected) { // Only show toast if state changed to disconnected
                            Toast.makeText(StagDataActivity.this, "Device " + deviceName + " disconnected.", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case 4: // DEVICE_CONNECT_FAILED
                        statusMessage += " (Connection Failed)";
                        isConnected = false;
                        tvConnectionStatus.setText("Status: Connection Failed with " + deviceName);
                        break;
                    default: // Includes Code 0 (Unknown)
                        statusMessage += " (Unknown State: " + code + ")";
                        // If code is 0 and we were connected, assume disconnection or unstable state
                        if (code == 0 && isConnected) {
                            isConnected = false;
                            isCollecting = false;
                            tvConnectionStatus.setText("Status: Connection state unknown/lost with " + deviceName);
                        }
                        break;
                }
                logToScreen(statusMessage);
                if (deviceInfo != null) {
                    logToScreen("Monitor Device Info: " + JSON.toJSONString(deviceInfo));
                }
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

        UserProfileConfig userProfileConfig = new UserProfileConfig();
        userProfileConfig.setHeight(175); 
        userProfileConfig.setWeight(70);  
        userProfileConfig.setAge(30);     
        userProfileConfig.setGender(1);   

        sensorProManager.getMotionProvider().configUserProfile(userProfileConfig, new SensorProCallback<byte[]>() {
            @Override
            public void onResponse(int errorCode, byte[] returnObject) {
                if (errorCode == 100000 || errorCode == 0) { 
                    logToScreen("User profile configured successfully.");
                    startAlgorithmAndActualCollection();
                } else {
                    logToScreen("Failed to configure user profile. Error: " + errorCode + ". Is device still connected? " + (isConnected ? "Yes" : "No"));
                    Toast.makeText(StagDataActivity.this, "User Profile Config Failed: " + errorCode, Toast.LENGTH_LONG).show();
                    // Do not proceed if user profile config fails
                    // Update button states in case something changed
                    runOnUiThread(StagDataActivity.this::updateButtonStates);
                }
            }
        });
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
                        accDataArray = data.getSensorData().getAccDataArray();
                        gyroDataArray = data.getSensorData().getGyroDataArray();
                        // MAG is NOT under SensorData -> PostureData based on error.
                        // if (data.getSensorData().getPostureData() != null){ // MAG is under PostureData in FeatureData for STAG
                        //    magDataArray = data.getSensorData().getPostureData().getMagDataArray();
                        // }
                    }
                    
                    // Fallback or alternative: STAG doc example shows PostureData under FeatureData directly for MAG and Gait
                    if (data.getPostureData() != null) { // Corrected: MAG is likely directly under FeatureData.getPostureData()
                        magDataArray = data.getPostureData().getMagDataArray();
                    }


                    final AccDataArray finalAcc = accDataArray;
                    final GyroDataArray finalGyro = gyroDataArray;
                    final MagDataArray finalMag = magDataArray;

                    runOnUiThread(() -> {
                        if (finalAcc != null && finalAcc.getAccValueArray() != null && finalAcc.getAccValueArray().length > 0) { // MODIFIED: Renamed getAccData to getAccValueArray
                            // Displaying only the first point for brevity
                            tvAccData.setText(JSON.toJSONString(finalAcc.getAccValueArray()[0])); // MODIFIED: Renamed getAccData to getAccValueArray
                        } else {
                            // tvAccData.setText("No ACC data");
                        }
                        if (finalGyro != null && finalGyro.getGyroValueArray() != null && finalGyro.getGyroValueArray().length > 0) { // MODIFIED: Renamed getGyroData to getGyroValueArray
                            tvGyroData.setText(JSON.toJSONString(finalGyro.getGyroValueArray()[0])); // MODIFIED: Renamed getGyroData to getGyroValueArray
                        } else {
                            // tvGyroData.setText("No GYRO data");
                        }
                        if (finalMag != null && finalMag.getMagValueArray() != null && finalMag.getMagValueArray().length > 0) { // Correct from previous fix
                            tvMagData.setText(JSON.toJSONString(finalMag.getMagValueArray()[0])); // Correct from previous fix
                        } else {
                            // tvMagData.setText("No MAG data");
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
            isCollecting = false; // Force state update
            runOnUiThread(this::updateButtonStates);
            return;
        }

        // 1. Stop Realtime Measurement (as per doc_STAG.txt - "关闭实时测量")
        sensorProManager.getCustomProvider().stopCollectCustomData(new SensorProCallback<byte[]>() {
            @Override
            public void onResponse(int errorCode, byte[] returnObject) {
                if (errorCode == 0 || errorCode == 100000) {
                    logToScreen("Data collection stopped successfully.");
                } else {
                    logToScreen("Failed to stop data collection. Error: " + errorCode + ". Still proceeding with algorithm stop.");
                }
                // Even if stop command fails, attempt to stop algorithm and update state
                isCollecting = false; // Set before algorithm stop, as it might be part of cleanup
                
                // 2. Stop Algorithm (as per doc_STAG.txt - "关闭算法")
                try {
                    PostureDataConvertUtils.stopPostureDetection();
                    logToScreen("Posture detection algorithm stopped.");
                } catch (Exception e) {
                    logToScreen("Error stopping posture detection algorithm: " + e.getMessage());
                    Log.e(TAG, "Error stopping posture algorithm", e);
                }

                // 3. Unregister callback (Important!)
                // The SDK docs usually imply that callbacks should be unregistered when no longer needed.
                // sensorProManager.getCustomProvider().unregisterFeatureDataCallback(featureDataCallback); // Check for specific unregister method.
                // If no direct unregister, setting the callback to null or a dummy might be a workaround, but proper unregistration is preferred.
                // For now, we assume it might be unregistered implicitly or by stopping collection.
                logToScreen("FeatureData callback should be unregistered if an API exists.");

                runOnUiThread(() -> {
                    updateButtonStates();
                    tvAccData.setText("-");
                    tvGyroData.setText("-");
                    tvMagData.setText("-");
                });
            }
        });
    }

    // --- Permission Handling --- (To be filled)
    // --- Bluetooth Scan Logic --- (To be filled)
    // --- Bluetooth Connection Logic --- (To be filled)
    // --- Data Collection Logic --- (To be filled)

} 