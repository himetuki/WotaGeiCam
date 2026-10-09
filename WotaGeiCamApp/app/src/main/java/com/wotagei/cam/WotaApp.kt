package com.wotagei.cam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.wotagei.cam.diag.Diag
import com.wotagei.cam.record.CaptureFgService
import com.wotagei.cam.ui.WotaSettings

/**
 * 启动期不做重活：Room / 媒体库查询全部延迟到进入对应页面，保证冷启动。
 */
class WotaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createCaptureChannel()
        // 诊断日志（2026-10-09）：**必须在任何业务代码之前装**，否则冷启到页面就绪这段的日志收不到。
        // 只做三件事——建目录、起后台落盘线程、装崩溃处理器，都是轻量操作（不违反"冷启不做重活"）。
        // 详细档从设置读（默认关，只记 i/w/e）。
        runCatching {
            Diag.install(this, verbose = WotaSettings.diagVerbose(WotaSettings.of(this)))
        }
    }

    /**
     * 内录前台服务的常驻通知渠道：低重要性（无声、无横幅），只让会话在状态栏可见。
     * minSdk 29 ≥ O(26)，无需版本分支。
     */
    private fun createCaptureChannel() {
        val channel = NotificationChannel(
            CaptureFgService.CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.capture_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
