package com.wotagei.cam.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 水平仪 / 俯仰仪数据源（需求 24、25 行「必须」，03 文档 §5）。
 *
 * 只读 `TYPE_ACCELEROMETER`（拿静态重力即可，不用 TYPE_GRAVITY/旋转矢量，省一颗传感器），
 * 采样率 `SENSOR_DELAY_GAME`（约 50Hz：够跟手，又不到 `SENSOR_DELAY_FASTEST` 的功耗）。
 *
 * 角度约定（**以显示方向为基准**，即观察者眼里的屏幕横平竖直，全部以「度」输出）：
 * - [roll]：绕取景光轴的左右倾角，`0 = 左右水平`，**正值 = 屏幕右侧偏低**，范围 -90..90。
 *   `ui/widget/RefLineOverlay.drawHorizon` 按同一约定绘制真实地平线（右侧偏低 → 线向右上方抬起）。
 * - [pitch]：镜头光轴相对水平面的仰俯角，`0 = 镜头水平`，**正值 = 仰角**、负值 = 俯角，范围 -90..90。
 *   03 文档写的 `atan2(-y, hypot(x,z))` 是「相对平放」的倾角，平举时为 ±90，和仪表过心线语义冲突，
 *   这里按需求「仰角/俯角」改为相对水平面：`atan2(-z, hypot(x,y))`。
 * - [isLevel]：`|roll| < LEVEL_TOLERANCE_DEG`。
 *
 * 为什么必须换算显示方向：加速度计给的是**机头坐标系**读数（X=机头右、Y=机头顶、Z=出屏），
 * 只在竖屏时与屏幕系重合。相机页锁横屏，不换算的话「横屏平举」会被读成 ±90°（真机实测 87.2°），
 * 水平仪整块失真。[setDisplayDegrees] 由 UI 在页面方向确定后喂入，光轴是屏幕法线，
 * 平面内旋转不改变 [pitch] 的分子分母，故只有 roll 需要换算。
 *
 * 用法：`start()` / `stop()` 与 `registerListener` / `unregisterListener` 成对，
 * 一般挂在相机页的 `DisposableEffect` 或 `ON_RESUME`/`ON_PAUSE` 上；离开页面务必 `stop()`（加速度计常开很费电）。
 * 无需任何 manifest 权限（回正震动用的 VIBRATE 已在清单里）。
 */
class LevelSensor(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 本机没有加速度计时为 false，UI 应据此隐藏仪表（[start] 也只打一行日志） */
    val available: Boolean get() = accelerometer != null

    /** 水平仪/俯仰仪过 0° 是否震动，由设置页 `level_buzz_enabled` 喂入；关掉后两路都不震 */
    @Volatile
    var buzzEnabled: Boolean = true

    private val _roll = MutableStateFlow(0f)

    /** 左右水平角（-90..90，0=水平，正=右侧偏低） */
    val roll: StateFlow<Float> = _roll

    private val _pitch = MutableStateFlow(0f)

    /** 镜头仰俯角（-90..90，0=水平，正=仰角） */
    val pitch: StateFlow<Float> = _pitch

    private val _isLevel = MutableStateFlow(false)

    /** 是否已回正（[roll] 落在 ±[LEVEL_TOLERANCE_DEG] 内） */
    val isLevel: StateFlow<Boolean> = _isLevel

    @Volatile
    private var displayDegrees = 0

    /**
     * 喂入当前显示旋转角（0/90/180/270，取自 `WindowManager.defaultDisplay.rotation * 90`）。
     * 不调用时按竖屏处理，等价于换算前的老行为。
     */
    fun setDisplayDegrees(degrees: Int) {
        displayDegrees = ((degrees % 360) + 360) % 360
    }

    private var registered = false
    private var primed = false
    private var vibrator: Vibrator? = null

    // ---- 静默自愈 watchdog（首启"俯仰仪无响应、重启恢复"的缺陷修复，2026-10-03 用户反馈）：
    // 部分机型冷启时 registerListener 返回 true 但 HAL 迟迟不吐样本（传感器服务唤醒竞态），
    // 二次启动才正常。注册后 600ms 仍零样本就注销重挂，最多两次；收到任一样本即解除。
    private val mainHandler = Handler(Looper.getMainLooper())
    private var samplesSeen = 0
    private var watchdogRetries = 0

    private val watchdog = Runnable {
        if (!registered || samplesSeen > 0) return@Runnable
        if (watchdogRetries >= WATCHDOG_MAX_RETRIES) {
            Log.w(TAG, "accelerometer silent after $watchdogRetries re-register attempts, giving up")
            return@Runnable
        }
        watchdogRetries++
        Log.w(TAG, "accelerometer silent after register, re-registering (retry=$watchdogRetries)")
        registered = false
        runCatching { sensorManager?.unregisterListener(this) }
        start()
    }

    // 低通后的重力分量；NaN = 尚未收到首个样本
    private var sx = Float.NaN
    private var sy = Float.NaN
    private var sz = Float.NaN

    /** 开始采样；重复调用无副作用 */
    fun start() {
        val manager = sensorManager
        val sensor = accelerometer
        if (manager == null || sensor == null) {
            Log.w(TAG, "no accelerometer, level meter unavailable")
            return
        }
        if (registered) return
        samplesSeen = 0
        val ok = runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }.getOrDefault(false)
        registered = ok
        if (!ok) {
            Log.w(TAG, "registerListener(ACCELEROMETER) failed")
            return
        }
        if (watchdogRetries < WATCHDOG_MAX_RETRIES) {
            mainHandler.removeCallbacks(watchdog)
            mainHandler.postDelayed(watchdog, WATCHDOG_DELAY_MS)
        }
    }

    /** 停止采样；下次 [start] 从当前姿态重新收敛，不会用旧值做低通起点 */
    fun stop() {
        mainHandler.removeCallbacks(watchdog)
        // 重挂预算随会话复位：两次重试耗尽只代表"这一次注册窗口"没救活，
        // 用户离开再回来是新的传感器环境，该给新一轮预算（否则整个实例生命周期永久哑掉）
        watchdogRetries = 0
        if (!registered) return
        registered = false
        runCatching { sensorManager?.unregisterListener(this) }
        sx = Float.NaN
        sy = Float.NaN
        sz = Float.NaN
        primed = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (samplesSeen == 0) watchdogRetries = 0   // 自愈成功（或本就正常）：预算归零，下次异常重新计数
        samplesSeen++
        val values = event.values
        if (values.size < 3) return
        val x = values[0]
        val y = values[1]
        val z = values[2]
        // 低通加在重力分量上而不是角度上：角度在 ±90 处会绕回，直接滤角度会在两端抖出跳变
        if (sx.isNaN()) {
            sx = x
            sy = y
            sz = z
        } else {
            sx += SMOOTH_ALPHA * (x - sx)
            sy += SMOOTH_ALPHA * (y - sy)
            sz += SMOOTH_ALPHA * (z - sz)
        }
        val magnitude = sqrt(sx * sx + sy * sy + sz * sz)
        // 剧烈抖动/近似自由落体时读数不可信，丢掉这一帧
        if (magnitude < MIN_VALID_G * GRAVITY) return

        val planar = sqrt(sx * sx + sy * sy)
        // 机器接近「平放」时 X-Y 投影长度趋 0，roll 数值上完全失真 —— 保持上一次的值
        val rollValid = planar >= FLAT_GUARD * magnitude
        // 换算与折叠全在 LevelMath（纯函数，JVM 可单测）：roll 保证落在 ±90 内
        val display = LevelMath.displayVector(sx, sy, displayDegrees)
        val pitchDeg = LevelMath.pitchOf(sx, sy, sz)
        val rollDeg = if (rollValid) LevelMath.rollOf(display[0], display[1]) else _roll.value

        if (!primed) {
            // 首帧只落值、不震动：否则一进取景页就震一下
            primed = true
            _pitch.value = pitchDeg
            if (rollValid) _roll.value = rollDeg
            _isLevel.value = rollValid && isLevelOf(rollDeg)
            return
        }
        // 俯仰过 0° 与左右回正是两路独立提示，不能共用 roll 那条早退路径
        val pitchWasLevel = isLevelOf(_pitch.value)
        if (abs(pitchDeg - _pitch.value) >= EMIT_STEP_DEG) _pitch.value = pitchDeg
        if (isLevelOf(pitchDeg) && !pitchWasLevel) buzz()
        if (!rollValid || abs(rollDeg - _roll.value) < EMIT_STEP_DEG) return
        _roll.value = rollDeg
        val level = isLevelOf(rollDeg)
        if (level == _isLevel.value) return
        _isLevel.value = level
        if (level) buzz()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 回正瞬间单次轻震 100ms（「低成本高感知」细节）；无振动马达的机器静默跳过 */
    private fun buzz() {
        if (!buzzEnabled) return
        val vibrator = this.vibrator ?: acquireVibrator()?.also { this.vibrator = it } ?: return
        if (!runCatching { vibrator.hasVibrator() }.getOrDefault(false)) return
        val effect = VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        runCatching { vibrator.vibrate(effect) }
            .onFailure { Log.w(TAG, "vibrate failed: ${it.message}") }
    }

    /** API 31+ 走 VibratorManager；取不到（含 minSdk 29~30）回退 deprecated VIBRATOR_SERVICE */
    private fun acquireVibrator(): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = runCatching {
                appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            }.getOrNull()
            val fromManager = runCatching { manager?.defaultVibrator }.getOrNull()
            if (fromManager != null) return fromManager
        }
        @Suppress("DEPRECATION")
        return runCatching { appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }.getOrNull()
    }

    companion object {
        /** 水平判定容差（度）：UI 配色与 [isLevel] 共用这一个阈值，避免两处各写一份 */
        const val LEVEL_TOLERANCE_DEG = 1.5f

        /** 低通系数：越小越稳但越钝，0.15 是 50Hz 下的手感折中值 */
        const val SMOOTH_ALPHA = 0.15f

        /** 小于该角度变化不发布新值：50Hz 原始事件直接驱动 Compose 会白白重组发热 */
        const val EMIT_STEP_DEG = 0.1f

        /** 判定左右水平（UI 侧同用一个函数着色，见 `ui/widget/AttitudeMeter`） */
        fun isLevelOf(rollDegrees: Float): Boolean = abs(rollDegrees) < LEVEL_TOLERANCE_DEG

        private const val TAG = "WotaSensor"
        private const val BUZZ_MS = 100L
        private const val GRAVITY = 9.80665f
        private const val MIN_VALID_G = 0.6f
        private const val FLAT_GUARD = 0.25f

        /** 注册后静默多久判定"HAL 没吐数据"并重挂 */
        private const val WATCHDOG_DELAY_MS = 600L

        /** 自愈重挂上限（两次仍静默就放弃，避免死循环耗电） */
        private const val WATCHDOG_MAX_RETRIES = 2
    }
}
