package com.wotagei.cam.ui

import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers

/**
 * 常驻参数胶囊的「点按循环取值」（用户 2026-09-28 鸿蒙化第 2 条）。
 *
 * 候选值一律来自**参数总线自己的 range**（那份 range 是 `CameraCharacteristics` 在
 * `applyAbility` 时写进来的）与工程内的档位表，这里不出现任何机型硬编码；
 * 精细选择仍然在长按打开的就近面板里（滑杆、非常规档位都在那边）。
 */

/** 循环取下一档：到底回到第一个；当前值不在候选里时回到第一个。空候选返回 null */
fun <T> nextInCycle(options: List<T>, current: T): T? {
    if (options.isEmpty()) return null
    val index = options.indexOf(current)
    return options[if (index < 0) 0 else (index + 1) % options.size]
}

/**
 * 把某个读数胶囊推进一档。
 *
 * @return 有没有真的改动（false 时调用方给「这一项现在不能改」的提示，不静默吞掉点击）
 */
fun hudCycleStep(item: HudItem, params: WotaParams, evStep: Float): Boolean {
    val aeAuto = params.aeMode.value != AeMode.MANUAL
    return when (item) {
        HudItem.SHUTTER -> {
            val ns = WotaTiers.SHUTTER_DENOM
                .map { WotaTiers.NS_PER_SECOND / it }
                .filter { it in params.shutter.value.range ?: LongRange.EMPTY }
                .sortedDescending()
            if (ns.isEmpty()) return false
            if (aeAuto) {
                params.aeMode.value = AeMode.MANUAL
                params.shutter.value = params.shutter.value.copy(value = ns.first())
            } else {
                val next = nextInCycle(ns, params.shutter.value.value) ?: return false
                params.shutter.value = params.shutter.value.copy(value = next)
                if (next == ns.first()) params.aeMode.value = AeMode.AUTO   // 走完一圈回 AUTO
            }
            true
        }

        HudItem.ISO -> {
            val ladder = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800)
                .filter { it in params.iso.value.range ?: IntRange.EMPTY }
            if (ladder.isEmpty()) return false
            if (aeAuto) {
                params.aeMode.value = AeMode.MANUAL
                params.iso.value = params.iso.value.copy(value = ladder.first())
            } else {
                val next = nextInCycle(ladder, params.iso.value.value) ?: return false
                params.iso.value = params.iso.value.copy(value = next)
                if (next == ladder.first()) params.aeMode.value = AeMode.AUTO
            }
            true
        }

        HudItem.EV -> {
            val r = params.ev.value.range ?: return false
            val steps = (r.start..r.endInclusive).toList()
            if (steps.isEmpty()) return false
            val next = nextInCycle(steps, params.ev.value.value) ?: return false
            params.ev.value = params.ev.value.copy(value = next)
            true
        }

        HudItem.WB -> {
            val presets = WbPreset.values().toList()
            val next = nextInCycle(presets, params.wbMode.value) ?: return false
            params.wbMode.value = next
            true
        }

        HudItem.FPS -> {
            val r = params.fps.value.range ?: return false
            val tiers = WotaTiers.FPS.filter { it in r }
            if (tiers.isEmpty()) return false
            val next = nextInCycle(tiers, params.fps.value.value) ?: return false
            params.fps.value = params.fps.value.copy(value = next)
            true
        }

        HudItem.BITRATE -> {
            val next = nextInCycle(WotaTiers.BITRATES, params.bitrate.value) ?: return false
            params.bitrate.value = next
            true
        }

        HudItem.ZOOM -> {
            val state = params.zoom.value
            val r = state.range ?: return false
            val tiers = listOf(0.5f, 1f, 2f, 3f, 4f, 6f, 10f).filter { it >= r.start && it <= r.endInclusive }
            if (tiers.isEmpty()) return false
            val next = nextInCycle(tiers, state.value) ?: return false
            params.zoom.value = state.copy(value = next)
            true
        }
    }
}
