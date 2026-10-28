package com.wotagei.cam.ui

import android.content.SharedPreferences
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import com.wotagei.cam.media.WotaNav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 取景器/播放页亮度渐变的**纯函数层**（docs/plan/29 §5.5，JVM 全测）。
 *
 * 机制事实（§5.4 已验）：`window.attributes.screenBrightness` 是**逐窗口覆写**，
 * 取值 0..1 或 `BRIGHTNESS_OVERRIDE_NONE`（跟随系统）；改它不需要任何权限、不写系统设置。
 * 系统没有亮度动画 API，按固定步长写窗口属性即是全部实现。
 */
object BrightnessRampMath {

    /** 系统亮度（0..[max]）→ 0..1 覆写域；读不到/越界回 0.5（宁可中庸，不崩不闪满屏） */
    fun systemFraction(value: Int, max: Int = 255): Float =
        if (max <= 0 || value !in 0..max) 0.5f else value.toFloat() / max

    /**
     * 渐变第 [elapsedMs] 毫秒的值：from→to 线性，超出 [rampMs] 钳到 to。
     * rampMs<=0 视为「立即」，直接 to——0 档语义必须比除零更明确。
     */
    fun valueAt(elapsedMs: Long, rampMs: Long, from: Float, to: Float): Float {
        if (rampMs <= 0) return to
        val t = (elapsedMs.toFloat() / rampMs).coerceIn(0f, 1f)
        return from + (to - from) * t
    }

    /** 渐变步数：约 [stepMs] 一步（7s≈60 步），至少 2 步（只有 1 步就等于跳变，失去"渐变"语义） */
    fun stepCount(rampMs: Long, stepMs: Long = 117L): Int =
        if (rampMs <= 0) 0 else maxOf(2, (rampMs / stepMs).toInt())
}

/**
 * 「启动后自动亮屏」与「起播渐亮」的窗口亮度驱动（29 §5.3，2026-10-28 落地）。
 *
 * ## 为什么住在 MainActivity（与 [WindowKeepOnDriver] 同层）
 * 亮度覆写是**窗口属性**，写它的人必须拥有窗口；且它必须与常亮驱动的恢复语义协同——
 * 两者由**同一个**目的地回调先后驱动（先 keepOn 后 ramp），离开页面时 keepOn.exit
 * 写回进页前原值、ramp.cancelAndRestore 写回起坡前原值，同线程顺序执行没有竞态窗口。
 *
 * ## 三条口径（29 §5.3 原文）
 * ① 启动亮屏：只在**冷启后第一次进入取景页**触发一次（进程内闸），延迟 0–10s（0=关），
 *    之后 7s 内线性渐变到满亮度；延迟期间离开取景页取消且**不补触发**；
 * ② 起播渐亮：单播放页**每次进入都重新武装**，首次从暂停/未播放进入播放时从当前窗口
 *    亮度渐变到满（0–3s，0=关）；暂停不重置，离开页面恢复进页前亮度；
 * ③ 基础：取景页不再强制拉满——`WindowKeepOnDriver.enter` 只保留常亮 flag，
 *    亮度跟随系统（2026-10-27 裁决，撤销 2026-10-07 的强制拉满）。
 *
 * 已知副作用（§5.4 接受）：渐变期间用户手动调系统亮度会被窗口覆写压住，直到离页。
 */
internal class BrightnessRamp(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {

    private var job: Job? = null

    /** 起坡前记录的窗口亮度（离页写回它；NONE = 跟随系统） */
    private var savedBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** 起坡中标记：只有真写过覆写才需要恢复，避免把"没渐变过"也写一遍 */
    private var ramping = false

    /** ① 的进程内一次性闸：离开取景页不重置（"延迟期间离开则取消、不补触发"） */
    private var cameraOnceUsed = false

    /** ② 的每页武装闸：进播放页置真，首次起播消费掉 */
    private var playerArmed = false

    /** 目的地变化的亮度侧入口（与 [WindowKeepOnDriver.apply] 同一回调，先后有序） */
    fun onRoute(window: Window, route: String?, prefs: SharedPreferences) {
        when (route) {
            ROUTE_CAMERA -> {
                playerArmed = false
                maybeCameraRamp(window, prefs)
            }
            WotaNav.PLAYER -> playerArmed = true
            else -> cancelAndRestore(window)
        }
    }

    /** 单播放页**首次**起播（PlayerScreen 每次起播都报，这里只认每页第一次） */
    fun onPlayerPlaybackStarted(window: Window, prefs: SharedPreferences) {
        if (!playerArmed) return
        playerArmed = false
        val rampMs = WotaSettings.playerBrightenRampTenths(prefs) * 100L
        if (rampMs <= 0L) return
        startRamp(window, delayMs = 0L, rampMs = rampMs)
    }

    private fun maybeCameraRamp(window: Window, prefs: SharedPreferences) {
        if (cameraOnceUsed) return
        val delaySec = WotaSettings.cameraBrightenDelaySec(prefs)
        if (delaySec <= 0) return
        cameraOnceUsed = true
        startRamp(window, delayMs = delaySec * 1_000L, rampMs = CAMERA_BRIGHTEN_RAMP_MS)
    }

    private fun startRamp(window: Window, delayMs: Long, rampMs: Long) {
        cancelAndRestore(window)
        savedBrightness = window.attributes.screenBrightness
        ramping = true
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val from = currentFraction(window)
            val steps = BrightnessRampMath.stepCount(rampMs)
            for (i in 1..steps) {
                window.attributes = window.attributes.apply {
                    screenBrightness = BrightnessRampMath.valueAt(rampMs * i / steps, rampMs, from, 1f)
                }
                delay(rampMs / steps)
            }
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
    }

    /** 离开取景/播放页（或驱动被销毁）：掐协程并按起坡前原值写回 */
    fun cancelAndRestore(window: Window) {
        job?.cancel()
        job = null
        if (ramping) {
            ramping = false
            window.attributes = window.attributes.apply { screenBrightness = savedBrightness }
        }
    }

    /** Activity 销毁兜底：窗口都没了，协程必须收掉 */
    fun shutdown() {
        scope.cancel()
        job = null
        ramping = false
    }

    /**
     * 渐变起点：窗口已有显式覆写（0..1）就按它，否则（NONE=跟随系统）读系统亮度。
     * 读不到（个别 ROM 限制）回 0.5——起点错一点只影响观感，不能因此崩掉取景页。
     */
    private fun currentFraction(window: Window): Float {
        val current = window.attributes.screenBrightness
        if (current in 0f..1f) return current
        return try {
            BrightnessRampMath.systemFraction(
                Settings.System.getInt(window.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            )
        } catch (e: Exception) {
            0.5f
        }
    }

    companion object {
        /** ① 的渐变时长（29 §九 裁决口径：7 秒 = 渐变时长，延迟另算） */
        const val CAMERA_BRIGHTEN_RAMP_MS = 7_000L
    }
}
