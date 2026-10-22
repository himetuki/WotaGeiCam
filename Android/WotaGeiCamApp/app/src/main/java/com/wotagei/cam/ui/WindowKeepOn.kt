package com.wotagei.cam.ui

import android.view.Window
import android.view.WindowManager
import com.wotagei.cam.media.WotaNav

/**
 * 「屏幕常亮 + 窗口高亮」的**单一真源**（2026-10-07 真机缺陷 #103 定版）。
 *
 * 为什么从录制页的 `DisposableEffect` 搬到「拥有窗口的那一层」（MainActivity，经 NavController
 * 的目的地变化回调驱动）——两条机制上的理由：
 *
 * 1. 旧写法 `view.keepScreenOn = true` 只写**视图 flag**。框架把它合并进窗口 flag 是
 *    **边沿触发**的：AOSP 11 `ViewRootImpl.collectViewAttributes()` 只在「聚合后的 keepScreenOn
 *    发生变化」时才调 `applyKeepScreenOnFlag()`；而合并结果只落在 ViewRootImpl 的**私有**
 *    `mWindowAttributes` 副本里（客户端那一份 `Window.getAttributes()` 永远不带它）。
 *    本应用同一条效果里紧跟着的 `window.attributes = window.attributes.apply { … }` 是一次
 *    **整份下发**（`ViewRootImpl.setLayoutParams` → `mWindowAttributes.copyFrom`），会把窗口 flags
 *    换成客户端快照里的那一份 ⇒ KEEP_SCREEN_ON 被静默抹掉；此后 `View.setFlags` 又因为"值没变"
 *    直接短路，再没有"变化"去触发重新合并 ⇒ 直到冷启动（重新挂载，从头合并一次）才会恢复。
 * 2. 现在改成 `window.addFlags/clearFlags(FLAG_KEEP_SCREEN_ON)`：改的是 `Window` 自己的**活属性**
 *    （`getAttributes()` 返回的就是它），并且 `ViewRootImpl` 会把它记成"客户端 flag"
 *    （`mClientWindowLayoutFlags`）⇒ 任何后续整份下发都自带它，不再依赖边沿触发、也不怕
 *    `window.attributes` 整份赋值。
 *
 * 触发时机也不再依赖"某个 composable 何时重组"：`NavController` 在 `navigate`/`popBackStack`/
 * `setGraph` 时**同步派发**目的地变化，且 `addOnDestinationChangedListener` 注册时立刻回放当前
 * 目的地（navigation 2.7.4 `NavController.kt` 第 415-427 行）——冷启动与页面往返都覆盖得到。
 */
internal class WindowKeepOnDriver {

    /** 进常亮页前的窗口亮度原值（-1 = `BRIGHTNESS_OVERRIDE_NONE` = 跟随系统）；出页按它写回 */
    private var savedBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** 幂等闸：已经施加过就不再记"原值"，免得把 1.0 当成用户进页前的原始亮度 */
    private var applied = false

    /** 目的地变化时的唯一入口：取景相关页进入、其余页退出（判定表见 [keepScreenOnForRoute]） */
    fun apply(window: Window, route: String?) {
        if (keepScreenOnForRoute(route)) enter(window) else exit(window)
    }

    /**
     * 进常亮页。顺序是机制要求，不许对调：
     * ① `addFlags` 先把 flag 写进 `window.attributes` 这份**活对象**；
     * ② 再整份下发亮度覆写——取的就是 getter 返回的活对象，此刻它已经带上 flag。
     * （反序也不会丢 flag，这样写是为了让"flag 先落地"成为源码上的硬约束：
     * 以后有人在两步之间插一次整体赋值，也不会把还没落地的 flag 换成客户端快照。）
     */
    private fun enter(window: Window) {
        if (applied) return
        savedBrightness = window.attributes.screenBrightness
        applied = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
    }

    /** 出常亮页：按进页前记下的原值写回亮度，再摘掉窗口 flag（可正常熄屏） */
    private fun exit(window: Window) {
        if (!applied) return
        applied = false
        window.attributes = window.attributes.apply { screenBrightness = savedBrightness }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/**
 * 路由 → 是否「常亮 + 高亮」（**单一真源**，逐条全表单测在 `KeepOnRouteTableTest`）。
 *
 * 产品口径（用户 2026-10-07 定版）：**取景相关页面**（录制页 / 「编辑控件」页）常亮 + 拉满亮度；
 * 其余页面（媒体库 / 播放器 / 对比 / 设置）一律恢复——不常亮、亮度回进页前原值。
 *
 * 非取景路由**逐条写出来**（而不是只写 `else`）：新增路由时必须显式过一遍这张表，不能靠默认值蒙混。
 * 判定表中的路由形态与 `NavDestination.route` 一致（播放/对比是带占位符的路由模板）。
 */
fun keepScreenOnForRoute(route: String?): Boolean = when (route) {
    ROUTE_CAMERA, ROUTE_HUD_EDITOR -> true
    WotaNav.GALLERY, WotaNav.PLAYER, WotaNav.COMPARE, ROUTE_SETTINGS -> false
    // 未知路由（含 null）：默认不常亮——宁可少常亮一页，也不能在非取景页钉住常亮
    else -> false
}
