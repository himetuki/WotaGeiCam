package com.wotagei.cam.camera

import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * 双摄并发能力探测（用户第 3 项「另镜头录制」的前置）。
 *
 * 只用官方 API：`CameraManager.getConcurrentCameraIds()`（**API 30+**，本工程 minSdk 29
 * ⇒ 必须按 [Build.VERSION.SDK_INT] 分级，低版本连调用都不能发生）。
 *
 * 返回：能与被开镜头 `cameraId` **同时打开**的镜头 id 集合（空集 = 本机不支持并发，
 * 后续批次的 UI 据此灰置「另镜头录制」）。
 *
 * ⚠ 机型无关：不写死任何 cameraId；API < 30、查询抛异常（`CameraAccessException` 等）
 * 一律返回空集（保守 = 不支持，宁可少给一个功能也不给一个点了必崩的入口）。
 */
fun concurrentCameraIdsWith(mgr: CameraManager, cameraId: String): Set<String> {
    // API < 30 没有并发查询这一支，直接判不支持；分级必须发生在属性访问之前
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
    return try {
        // getConcurrentCameraIds() 声明抛 CameraAccessException，且各 ROM 可能另抛运行时异常，
        // 统一吞掉——探测失败等同"本机未知/不支持"，绝不把异常抛到 UI 组合里
        buddiesOf(mgr.concurrentCameraIds, cameraId)
    } catch (e: Exception) {
        emptySet()
    }
}

/**
 * 「组合集合 → 与 [cameraId] 同组的伙伴」这一半纯函数，JVM 可测（[concurrentCameraIdsWith]
 * 里的 API 分级与异常吞掉由真机/集成覆盖，JVM 只测这一步解析）。
 *
 * 规则：只认**包含目标且 size ≥ 2** 的组合（`size == 1` 的组合等于没有并发伙伴），
 * 取同组内除目标外的所有 id；目标出现在多个组合里时取并集；组里没有目标则不参与。
 */
internal fun buddiesOf(combos: Set<Set<String>>, cameraId: String): Set<String> =
    combos.filter { combo -> cameraId in combo && combo.size >= 2 }
        .flatMapTo(mutableSetOf()) { combo -> combo.filter { it != cameraId } }
