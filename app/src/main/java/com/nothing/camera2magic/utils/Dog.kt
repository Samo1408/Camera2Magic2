package com.nothing.camera2magic.utils

import android.util.Log

/**
 * 全局日志器：统一写入 logcat（tag=VCX），查看方式为 `adb logcat -s VCX:*`；
 * Hook 侧额外双写到 XposedModule.log（LSPosed 管理器 → 日志 → 模块日志，免 adb）。
 * enabled 参数由调用方传入（通常为 SM.enableLog / Dog.enabled），任何通道都不绕过它。
 */
object Dog {
    private const val TAG = "VCX"

    var enabled: Boolean = false

    /**
     * Hook 侧模块日志通道：由 MagicHook 注入 XposedModule::log 的四参重载，宿主进程保持 null
     * （XposedModule 只存在于目标进程）。参数与 logcat 对齐：priority（Log.INFO/WARN/ERROR）、
     * tag、消息行、可空异常，级别与堆栈由 LSPosed 模块日志页呈现。
     */
    var moduleSink: ((Int, String, String, Throwable?) -> Unit)? = null

    fun i(tag: String? = null, message: String, enabled: Boolean = this.enabled) {
        if (!enabled) return
        val line = "[${tag ?: ""}] $message"
        Log.i(TAG, line)
        moduleSink?.invoke(Log.INFO, TAG, line, null)
    }

    fun w(tag: String? = null, message: String, enabled: Boolean = this.enabled) {
        if (!enabled) return
        val line = "[${tag ?: ""}] $message"
        Log.w(TAG, line)
        moduleSink?.invoke(Log.WARN, TAG, line, null)
    }

    fun e(tag: String? = null, message: String, throwable: Throwable? = null, enabled: Boolean = this.enabled) {
        if (!enabled) return
        val msg = if (throwable != null) "$message: ${throwable.message}" else message
        val line = "[${tag ?: ""}] $msg"
        Log.e(TAG, line, throwable)
        moduleSink?.invoke(Log.ERROR, TAG, line, throwable)
    }
}
