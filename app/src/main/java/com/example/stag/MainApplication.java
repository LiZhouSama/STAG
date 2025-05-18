package com.example.stag;

import android.app.Application;
import com.huawei.hiresearch.bridge.HiResearchBridgeStack;
// 如果在 ResearchStack 中取消了 initSensor 的注释，这里也需要取消注释
import com.huawei.hiresearch.sensor.SensorManager; 

public class MainApplication extends Application {

    private static final String YOUR_PROJECT_CODE = "ifcurmqc"; // 您提供的项目编码

    @Override
    public void onCreate() {
        super.onCreate();
        //SDK初始化
        //"<yourPojectCode>"：HuaweiResearch平台对应研究项目编码
        HiResearchBridgeStack.init(this, new ResearchStack(this, YOUR_PROJECT_CODE));
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // 仅在您初始化了sensor模块(huawei-research-android-sensor.aar)时调用
        // SensorManager.getInstance().destrory();

        // 注意: 检查 SensorProManager 或 SensorProCommonManager 是否有对应的 destroy/terminate 方法
        // doc_basic.txt 中 ResearchStack 示例的 onTerminate 调用了 SensorManager.getInstance().destrory()
        // 但在 SensorProManager 的初始化部分，并没有对应的销毁方法被提及放在 onTerminate。
        // 通常SDK会自行管理生命周期，或者在特定Provider中有释放方法。
        // 如果 SensorProManager.getInstance() 有 destroy() 方法，可以在这里考虑调用，但需查阅确认。
        // com.huawei.hiresearch.sensorprosdk.utils.LogUtils.info("MainApplication", "onTerminate called"); // 确保LogUtils可用或使用android.util.Log
    }
}
