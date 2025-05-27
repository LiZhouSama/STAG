#include <jni.h>
#include <android/log.h>
#include "vqf/vqf.hpp" // VQF C++ API头文件

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,"VQF_JNI",__VA_ARGS__)

// Helper function to convert vqf_real_t (potentially double) to jfloat array
void convert_and_set_jfloat_array(JNIEnv* env, jfloatArray java_array, const vqf_real_t* cpp_array, int size) {
    if (sizeof(vqf_real_t) == sizeof(float)) {
        env->SetFloatArrayRegion(java_array, 0, size, reinterpret_cast<const jfloat*>(cpp_array));
    } else { // Assuming vqf_real_t is double if not float
        jfloat temp_float_array[size];
        for (int i = 0; i < size; ++i) {
            temp_float_array[i] = static_cast<jfloat>(cpp_array[i]);
        }
        env->SetFloatArrayRegion(java_array, 0, size, temp_float_array);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_stag_VqfNative_nativeInit(JNIEnv* env, jclass clazz, jfloat dt) {
    auto* params = new VQFParams(); // 使用默认参数
    // 如需自定义参数:
    // params->motionBiasEstEnabled = true;
    // params->restBiasEstEnabled = true;
    // params->magDistRejectionEnabled = true;
    
    // 创建VQF实例
    auto* f = new VQF(static_cast<vqf_real_t>(dt)); // 使用采样时间dt初始化
    LOGI("VQF Initialized with dt: %f", dt);
    return reinterpret_cast<jlong>(f);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_stag_VqfNative_nativeUpdate(
        JNIEnv* env, jclass clazz, jlong ptr,
        jfloat gx, jfloat gy, jfloat gz,
        jfloat ax, jfloat ay, jfloat az,
        jfloat mx, jfloat my, jfloat mz,
        jboolean useMag) {

    auto* f = reinterpret_cast<VQF*>(ptr);
    if (!f) {
        LOGI("VQF nativeUpdate called with null pointer.");
        return;
    }
    
    // 创建临时数组存储IMU数据
    vqf_real_t gyr[3] = {
        static_cast<vqf_real_t>(gx), 
        static_cast<vqf_real_t>(gy), 
        static_cast<vqf_real_t>(gz)
    };
    vqf_real_t acc[3] = {
        static_cast<vqf_real_t>(ax), 
        static_cast<vqf_real_t>(ay), 
        static_cast<vqf_real_t>(az)
    };
    
    if (useMag) {
        vqf_real_t mag[3] = {
            static_cast<vqf_real_t>(mx), 
            static_cast<vqf_real_t>(my), 
            static_cast<vqf_real_t>(mz)
        };
        // 使用9D融合（陀螺仪+加速度计+磁力计）
        f->update(gyr, acc, mag);
    } else {
        // 使用6D融合（陀螺仪+加速度计）
        f->update(gyr, acc);
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_stag_VqfNative_nativeGetQuat(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* f = reinterpret_cast<VQF*>(ptr);
    jfloatArray arr = env->NewFloatArray(4);
    if (!f) {
        LOGI("VQF nativeGetQuat called with null pointer.");
        vqf_real_t zero_quat[4] = {1.0, 0.0, 0.0, 0.0}; // 默认为单位四元数 (w,x,y,z)
        convert_and_set_jfloat_array(env, arr, zero_quat, 4);
        return arr;
    }
    vqf_real_t quat_data[4];
    f->getQuat9D(quat_data); // 获取9D融合结果(输出到数组)
    convert_and_set_jfloat_array(env, arr, quat_data, 4);
    return arr;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_stag_VqfNative_nativeGetQuat6D(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* f = reinterpret_cast<VQF*>(ptr);
    jfloatArray arr = env->NewFloatArray(4);
    if (!f) {
        LOGI("VQF nativeGetQuat6D called with null pointer.");
        vqf_real_t zero_quat[4] = {1.0, 0.0, 0.0, 0.0};
        convert_and_set_jfloat_array(env, arr, zero_quat, 4);
        return arr;
    }
    vqf_real_t quat_data[4];
    f->getQuat6D(quat_data); // 获取6D融合结果(输出到数组)
    convert_and_set_jfloat_array(env, arr, quat_data, 4);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_stag_VqfNative_nativeDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    auto* f = reinterpret_cast<VQF*>(ptr);
    if (f) {
        delete f;
        LOGI("VQF Destroyed.");
    } else {
        LOGI("VQF nativeDestroy called with null pointer.");
    }
}