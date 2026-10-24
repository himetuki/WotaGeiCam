package com.wotagei.cam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.StrictMode
import com.wotagei.cam.diag.Diag
import com.wotagei.cam.record.CaptureFgService
import com.wotagei.cam.ui.WotaSettings

/**
 * 启动期不做重活：Room / 媒体库查询全部延迟到进入对应页面，保证冷启动。
 */
class WotaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // StrictMode 主线程违规收集（2026-10-24 性能基线轮）：只在 debug 变体装，
        // release 不进这个分支零开销。penaltyLog 把违规打进 logcat 的 StrictMode 标签，
        // 场景复跑后 grep 收集；detectAll = 线程全部检查项 + VM 全部检查项。
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build()
            )
        }
        // SharedPreferences 首次加载定序（StrictMode P2，2026-10-24）：wota_settings.xml 的
        // 磁盘读发生在首个 getSharedPreferences 调用线程上（含目录 exists() 检查），裸挪后台会
        // 输给主线程竞态。latch 定序保证：磁盘读发生在后台线程，主线程只 await（park，无 IO）
        // ——总墙钟不变（这份读本来就串在 onCreate 里），只是把系统调用挪出主线程。
        val prefsPrefetched = java.util.concurrent.CountDownLatch(1)
        kotlin.concurrent.thread(start = true, isDaemon = true, name = "wota-prefs-prefetch") {
            runCatching {
                val p = WotaSettings.of(this@WotaApp)
                // 必须真等到「载入完成」：awaitLoadedLocked 在 Android 15 上对**等待方**也打
                // DiskReadViolation（P2 首跑实证）——只调 of() 是异步起载，主线程首读仍会撞。
                // 读一次 all() 把等待留在本后台线程上，主线程首读才是纯内存。
                p.all
            }
            prefsPrefetched.countDown()
        }
        createCaptureChannel()
        // 诊断日志（2026-10-09）：**必须在任何业务代码之前装**，否则冷启到页面就绪这段的日志收不到。
        // 只做三件事——建目录、起后台落盘线程、装崩溃处理器，都是轻量操作（不违反"冷启不做重活"）。
        // 总开关与详细档都从设置读（总开关默认关：关着时不写常规日志，崩溃件不受影响）。
        // prefs 定序：先等上面的后台预热完成， Diag.install 参数里的 WotaSettings 读不碰磁盘。
        runCatching {
            prefsPrefetched.await()
            Diag.install(
                this,
                verbose = WotaSettings.diagVerbose(WotaSettings.of(this)),
                enabled = WotaSettings.diagEnabled(WotaSettings.of(this))
            )
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
