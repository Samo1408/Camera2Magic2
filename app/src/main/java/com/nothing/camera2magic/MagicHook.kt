package com.nothing.camera2magic

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import com.nothing.camera2magic.hook.Camera1Hooker
import com.nothing.camera2magic.hook.Camera2Hooker
import com.nothing.camera2magic.hook.SourceManager as SM
import com.nothing.camera2magic.hook.ImageReaderHooker
import com.nothing.camera2magic.hook.WebRTCHooker
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class MagicHook : XposedModule() {
    companion object {
        private const val TAG = "[MagicHook]"
    }
    init { System.loadLibrary("camera3") }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        // 模块日志通道：受控日志双写到 LSPosed 管理器的模块日志页，免 adb 查看
        Dog.moduleSink = { pri, tag, msg, err -> log(pri, tag, msg, err) }
        GlobalState.processName = Application.getProcessName()
        val remotePrefs = getRemotePreferences("camera_magic_config")
        SM.init(remotePrefs)
        // 入口可观测点：受 main_enable_log 门控，不可改常开（同 refreshPrefs 的指纹约束）
        Dog.i(TAG, "process=${GlobalState.processName}, hookEnabled=${SM.readyForHook}, media=${SM.validMedia?.type?.label ?: "none"}", SM.enableLog)
        Application::class.java.onCreateHook()
        Camera1Hooker(this, param)
        Camera2Hooker(this, param)
        ImageReaderHooker(this, param)
        WebRTCHooker(this, param)
        Dog.i(TAG, "all 4 hookers installed", SM.enableLog)
    }

    private fun Class<*>.onCreateHook() {
        val onCreate = getDeclaredMethod("onCreate")
        hook(onCreate).intercept { chain ->
            val app =  chain.thisObject as Application
            GlobalState.appContext = app.also {
                registerForegroundChecker(it)
            }
            Dog.i(TAG, "Application.onCreate intercepted", SM.enableLog)
            return@intercept chain.proceed()
        }
    }

    private fun registerForegroundChecker(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityCreated(activity: Activity, savedInstance: Bundle?) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityStarted(activity: Activity) {
                GlobalState.activityCount++
                if (GlobalState.activityCount == 1) {
                    SM.refreshAndDispatch()
                    activity.runOnUiThread {
                        if (SM.showToast) {
                            val text = "[✨] " + SM.toastMessage
                            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            override fun onActivityStopped(activity: Activity) { GlobalState.activityCount-- }
        })
    }
}
