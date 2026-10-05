package com.wotagei.cam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.wotagei.cam.record.CaptureFgService

/**
 * 启动期不做重活：Room / 媒体库查询全部延迟到进入对应页面，保证冷启动。
 */
class WotaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createCaptureChannel()
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
