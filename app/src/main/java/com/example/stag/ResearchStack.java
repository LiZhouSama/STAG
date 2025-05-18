package com.example.stag;

import android.content.Context;
import com.huawei.hiresearch.bridge.HiResearchBridgeStack;
import com.huawei.hiresearch.bridge.BridgeManager2; // From doc_basic
import com.huawei.hiresearch.bridge.config.BridgeConfig;
import com.huawei.hiresearch.bridge.config.HttpClientConfig;
import com.huawei.hiresearch.sensorprosdk.SensorProManager;
import com.huawei.hiresearch.sensorprosdk.SensorProCommonManager; // From doc_basic
// Import other managers if needed, e.g., SensorManager, ResearchManager2, SensorScaleManager from doc_basic if those modules were used.

import java.util.concurrent.TimeUnit;

public class ResearchStack extends HiResearchBridgeStack {

    public ResearchStack(Context context, String projectCode) {
        super(); // Call super constructor

        // 初始化bridge（必选）
        initBridge(context, projectCode);

        // 初始化sensorpro(可选, 但对STAG是必须的)
        initSensorPro(context);

        // 根据您的需求，如果使用其他模块，也需要初始化它们，例如:
        // initSensor(context); // For huawei-research-android-sensor.aar
        // initResearch(context, projectCode); // For huawei-research-android-research.aar
        // initScale(context); // For huawei-research-android-scale.aar
    }

    /**
     * 初始化bridge、common（必选）
     * @param context     上下文
     * @param projectCode 研究项目编码
     */
    private void initBridge(Context context, String projectCode) {
        BridgeManager2.init(context);
        // http client 配置
        HttpClientConfig httpClientConfig = new HttpClientConfig(30, 30, 30, TimeUnit.SECONDS);
        // bridge配置
        BridgeConfig bridgeConfig = new BridgeConfig(context);
        // 设置研究项目id
        bridgeConfig.setProjectCode(projectCode);
        BridgeManager2.getInstance().addStudyProject(httpClientConfig, bridgeConfig);
    }

    /**
     * 初始化sensorpro
     * 仅在您需要使用huawei-research-android-sensorpro-alg-x.x.aar、
     * huawei-research-android-sensorpro-common-x.x.aar、
     * huawei-research-android-sensorpro-wear-x.x.aar时初始化
     * @param context 上下文
     */
    private void initSensorPro(Context context) {
        // SensorProManager.init(context, ""); // The second parameter is for serviceId, typically empty for default service.
        // From doc_basic.txt, it shows SensorProManager.init(context, "") and then SensorProCommonManager.init(context)
        SensorProManager.init(context, ""); // serviceId传空字符串，如文档所示
        SensorProCommonManager.init(context);

        // （可选）开启SensorPro日志采集开关。APP需开启存储权限，且APP是debug版本
        // Query your BuildConfig.DEBUG or a similar flag
        // if (BuildConfig.DEBUG) { // Replace com.example.stag.BuildConfig if your package is different
             SensorProManager.getInstance().setDebugLog(true);
        // }
    }

    // Add initSensor, initResearch, initScale methods from doc_basic.txt if you plan to use those modules.
    // For STAG raw data, initSensorPro is the key one.

    /**
     * Called by the system when the HiResearchBridgeStack is first created.
     * You can override this if you need to, but the constructor handles initialization for now.
     */
    public void onCreate() {
        // super.onCreate() is not available in HiResearchBridgeStack directly.
        // Initialization is done in the constructor.
        // If HiResearchBridgeStack had its own onCreate, you might call super.onCreate() here.
    }

    /**
     * Called by the system to notify a Service that it is no longer used and is being removed.
     * You can override this if you need to perform cleanup.
     */
    public void onDestroy() {
        // super.onDestroy() is not available in HiResearchBridgeStack directly.
        // Perform any cleanup specific to your ResearchStack here.
        // For example, if SensorProManager had a destroy method, it might be called here.
        // The MainApplication's onTerminate handles SensorManager.getInstance().destrory(),
        // but doc_basic for SensorPro doesn't specify a global destroy for SensorProManager in onTerminate.
    }
} 