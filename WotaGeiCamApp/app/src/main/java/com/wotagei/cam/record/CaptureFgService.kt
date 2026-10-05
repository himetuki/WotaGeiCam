package com.wotagei.cam.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.IntentCompat
import com.wotagei.cam.R

/**
 * 内录前台服务：只负责让 MediaProjection 会话合法存活（targetSdk 34 下会话必须挂在
 * `foregroundServiceType="mediaProjection"` 的 FGS 上）。
 *
 * # API 34 顺序链（顺序写死，颠倒抛 SecurityException）
 * 1. [onCreate]：`startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`；
 * 2. [onStartCommand]：`MediaProjectionManager.getMediaProjection(resultCode, data)`；
 * 3. 紧随其后 `registerCallback`（API 34 要求 createVirtualDisplay 前已注册；音频路径同样要求尽早）。
 *    1→2 的顺序由「onCreate 恒先于 onStartCommand」的生命周期结构锁死，2→3 由调用顺序+守卫测试锁死
 *    （见 CapturePlatformGuardTest）。
 *
 * # 会话本体的归属与撤销的身份守卫（耦合最小的选型）
 * projection 由本服务 companion 持有（`internal set` 只准服务写），同包的 [PlaybackCaptureController]
 * 只读+挂回调：控制器不持服务引用、服务不依赖控制器类型，批 2 UI 只认控制器。
 * 撤销回调**逐会话新建并闭包捕获该会话本体**，`onStop` 先过身份守卫（`projection !== proj` 即失效）：
 * 程序侧主动拆旧会话（[stopActiveProjection]，重复授权重建/关停）产生的迟到 onStop
 * 既不会误报「用户撤销」（Revoked 只留给状态栏停止投屏/系统收回），也不会误杀替换后的新会话。
 *
 * # 通知停止入口（API 29/30 的「停止投屏」替代品）
 * 通知挂 [stopPendingIntent] 停止钮：[onStartCommand] 识别 [ACTION_STOP] 分支——
 * 这是**用户**主动结束采集，先回执撤销再走身份守卫拆会话，与 31+ 状态栏停止同语义。
 */
class CaptureFgService : Service() {

    companion object {
        /** 通知渠道 id，[com.wotagei.cam.WotaApp] 启动时建渠道 */
        const val CHANNEL_ID = "capture"

        /** 通知停止钮的 action：API 29/30 无稳定的系统级「停止投屏」入口，会话必须有可及的自建终止手段 */
        const val ACTION_STOP = "com.wotagei.cam.action.CAPTURE_STOP"
        private const val NOTIFICATION_ID = 41
        private const val REQUEST_CODE_STOP = 41
        private const val EXTRA_RESULT_CODE = "wota_capture_result_code"
        private const val EXTRA_RESULT_DATA = "wota_capture_result_data"

        /** 授权链路拿到的会话本体；null = 尚未建立或已拆除 */
        @Volatile
        var projection: MediaProjection? = null
            internal set

        /** 建链结果回传（controller 注册）：projection != null 成功；否则 error 为失败原因 */
        @Volatile
        var onEstablishResult: ((projection: MediaProjection?, error: String?) -> Unit)? = null

        /** 撤销回传（controller 注册）：用户状态栏停止投屏 / 系统撤销授权时触发 */
        @Volatile
        var onProjectionRevoked: (() -> Unit)? = null

        /**
         * 通知停止钮的 PendingIntent：显式组件 + setPackage 双保险，action 只在本应用内循环，
         * 杜绝被外部应用伪造/劫持。走 getService：服务已在前台运行，投递 onStartCommand
         * 不触发「后台起服务」限制（限制只拦未启动服务的创建）。
         */
        fun stopPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, CaptureFgService::class.java)
                .setAction(ACTION_STOP)
                .setPackage(context.packageName)
            return PendingIntent.getService(
                context,
                REQUEST_CODE_STOP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** 服务实例锚点（onCreate 挂 / onDestroy 摘）：companion 的拆会话入口要路由到活实例 */
        @Volatile
        private var instance: CaptureFgService? = null

        /**
         * 程序侧主动拆当前会话（controller 的重复授权重建 / shutdown 用）：
         * 先摘会话身份再 stop——旧会话迟到的 onStop 被 [onStartCommand] 里回调的身份守卫拦下，
         * 因此这次拆除**不产生「用户撤销」回执**、也不会误杀随后建立的新会话。
         */
        internal fun stopActiveProjection() {
            instance?.teardownProjection()
        }

        /** 组启动 intent：把授权回执带给服务（resultData 是 Intent，天然 Parcelable） */
        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, CaptureFgService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // API 34 顺序链第 1 环：必须先以 MEDIA_PROJECTION 类型进前台，才允许拿会话。
        // 放 onCreate（而非 onStartCommand）：onCreate 恒先于 onStartCommand，1→2 顺序由结构锁死。
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知停止钮（API 29/30 的「停止投屏」替代品）：这是**用户**主动结束采集，
            // 与 31+ 状态栏停止同语义。顺序敏感：先回执撤销（controller 进 Revoked），
            // 再走身份守卫路径拆会话——先拆会守卫就把这次自拆静默掉，controller 卡死 Active。
            Log.i(TAG, "capture stop requested from notification")
            onProjectionRevoked?.invoke()
            teardownProjection()
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent?.let {
            // IntentCompat：避开 API 33 起的平台 getParcelableExtra 弃用告警
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (resultCode == null || resultData == null || resultCode == Int.MIN_VALUE) {
            // 无授权回执（START_NOT_STICKY 重启或脏 intent）：没法建会话，退场
            failAndStop("no consent extras (service restart?)")
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            // API 34 顺序链第 2 环：onCreate 已 startForeground，这里才允许拿会话
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            failAndStop("getMediaProjection failed: ${e.message}")
            return START_NOT_STICKY
        }
        // API 34 顺序链第 3 环 + 会话身份守卫：拿到会话立刻注册回调（主线程 Handler）。
        // 回调逐会话新建并闭包捕获 proj：`projection !== proj` 的迟到 onStop（旧会话被
        // stopActiveProjection 主动拆除 / 已被新会话顶替）一律失效——不误报撤销、不误杀新会话。
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection !== proj) return
                projection = null
                // 用户状态栏「停止投屏」/ 系统撤销：通知 controller 进撤销态 → 自杀
                Log.i(TAG, "projection stopped by user/system")
                onProjectionRevoked?.invoke()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection = proj
        Log.i(TAG, "capture projection established")
        onEstablishResult?.invoke(proj, null)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 兜底：服务被系统/外部回收时不能留下活的投屏会话（先摘身份再 stop，不产生撤销回执）
        teardownProjection()
        if (instance === this) instance = null
        onEstablishResult = null
        onProjectionRevoked = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun teardownProjection() {
        // 顺序敏感：先摘 companion 里的会话身份再 stop，onStop 的身份守卫才拦得住这次自拆
        val p = projection ?: return
        projection = null
        p.stop()
    }

    private fun failAndStop(reason: String) {
        // onCreate 必已 startForeground，这里 stopSelf 不会触发「没进前台就停」的异常
        Log.e(TAG, reason)
        onEstablishResult?.invoke(null, reason)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setOngoing(true)
            // 自建停止入口（审查 P2）：内录是采集全局音频的隐私敏感会话，API 29/30
            // 没有稳定的系统级「停止投屏」，用户在通知上必须能一键终止。
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_app),
                    getString(R.string.capture_notification_stop),
                    stopPendingIntent(this)
                ).build()
            )
            .build()
    }
}
