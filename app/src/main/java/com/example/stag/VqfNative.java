package com.example.stag; // Matching StagDataActivity's package

public class VqfNative {
    static {
        try {
            System.loadLibrary("vqf_native");
        } catch (UnsatisfiedLinkError e) {
            // Log or handle error, e.g., library not found or architecture mismatch
            android.util.Log.e("VqfNative", "Failed to load vqf_native library", e);
        }
    }

    private long nativeHandle; // Renamed from 'handle' to 'nativeHandle' for clarity

    public VqfNative(float dt) {
        nativeHandle = nativeInit(dt);
    }

    // Update method now takes individual float arrays for acc, gyr, mag
    public void update(float[] gyr, float[] acc, float[] mag) {
        boolean useMag = (mag != null && mag.length == 3); // Ensure mag is not null and has 3 elements
        nativeUpdate(nativeHandle,
            gyr[0], gyr[1], gyr[2],
            acc[0], acc[1], acc[2],
            useMag ? mag[0] : 0.0f,
            useMag ? mag[1] : 0.0f,
            useMag ? mag[2] : 0.0f,
            useMag);
    }
    
    // Method to update with 6D data (IMU only)
    public void update(float[] gyr, float[] acc) {
        nativeUpdate(nativeHandle,
            gyr[0], gyr[1], gyr[2],
            acc[0], acc[1], acc[2],
            0.0f, 0.0f, 0.0f, // Mag values are zero
            false); // useMag is false
    }

    public float[] getQuat() { // Returns [w, x, y, z]
        return nativeGetQuat(nativeHandle);
    }
    
    public float[] getQuat6D() { // Returns [w, x, y, z] for 6D fusion
        return nativeGetQuat6D(nativeHandle);
    }

    public void close() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0; // Prevent double destruction
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close(); // Ensure native resources are released if close() wasn't called explicitly
        } finally {
            super.finalize();
        }
    }

    // Native method declarations
    private static native long nativeInit(float dt);
    private static native void nativeUpdate(long handle,
            float gx, float gy, float gz,
            float ax, float ay, float az,
            float mx, float my, float mz,
            boolean useMag);
    private static native float[] nativeGetQuat(long handle); // For 9D
    private static native float[] nativeGetQuat6D(long handle); // For 6D
    private static native void nativeDestroy(long handle);
}
