/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.reverie.paint.core.CrashHandler
import com.reverie.paint.core.FontManager
import com.reverie.paint.core.LanguageManager
import com.reverie.paint.core.telemetry.MainThreadWatchdog
import com.reverie.paint.core.telemetry.TelemetryReporter

class ReverieApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LanguageManager.init(this)
        CrashHandler.init(this)
        FontManager.appContext = this
        // 延迟 30s 的后台任务, 只读本地崩溃日志; 端点未注入时自身 no-op
        TelemetryReporter.scheduleStartupScan(this)
        // 主线程卡顿看门狗: ANR 的系统转储在真机上经常抓不到, 由它把"卡在哪"留下来
        MainThreadWatchdog.start(this)

        installGlanceTrampolineGuard()
    }

    /**
     * Glance 桌面小组件启动崩溃防护 (签名 a8e7f4659a)
     *
     * 某些系统桌面启动器在低内存或生命周期回收时, 转发 RemoteViews 点击意图会丢失 extras
     * 导致 androidx.glance.appwidget.action.InvisibleActionTrampolineActivity 抛出:
     * IllegalArgumentException("List adapter activity trampoline invoked without specifying target intent")
     * 在 Activity.onCreate (dispatchActivityCreated) 阶段检测并补全 fallback intent, 避免崩溃
     */
    private fun installGlanceTrampolineGuard() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val className = activity.javaClass.name
                if (className.contains("TrampolineActivity")) {
                    try {
                        val currentIntent = activity.intent ?: Intent().also { activity.intent = it }
                        var targetIntent: Intent? = null
                        try {
                            @Suppress("DEPRECATION")
                            targetIntent = currentIntent.getParcelableExtra<Intent>("ACTION_INTENT")
                        } catch (e: Throwable) {
                            Log.w("ReverieApp", "Failed to deserialize ACTION_INTENT from trampoline intent", e)
                        }
                        if (targetIntent == null) {
                            Log.w("ReverieApp", "Trampoline activity launched without ACTION_INTENT, injecting fallback")
                            val fallbackIntent = Intent(activity, MainActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                `package` = activity.packageName
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            currentIntent.putExtra("ACTION_INTENT", fallbackIntent)
                        }
                        if (!currentIntent.hasExtra("ACTION_TYPE")) {
                            currentIntent.putExtra("ACTION_TYPE", "ACTIVITY")
                        }
                    } catch (t: Throwable) {
                        Log.e("ReverieApp", "Failed to sanitize trampoline activity intent", t)
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
