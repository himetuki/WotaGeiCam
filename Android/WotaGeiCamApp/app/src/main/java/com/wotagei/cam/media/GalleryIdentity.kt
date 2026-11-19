package com.wotagei.cam.media

/**
 * 媒体库选中态 / 菜单目标 / 系列代表段的**纯身份判据**（Y27，JVM 可测）。
 *
 * 为什么单独抽一层：[VideoClip] 里躺着 `android.net.Uri`，本仓 JVM 单测没有
 * Robolectric/mockk（android.jar 桩里 Uri 构造器直接抛 `Stub!`），真值表只能在裸
 * Long/Int 上写。UI 侧只做一次薄映射。
 */

/**
 * 选中态与当前可见行求交：已经不在列表里的 id 必须掉出选择。
 *
 * 原缺陷：`selectedIds` 只在 filter/scope/mode 变化时清空。外部删片、撤权、其他页签
 * 操作导致列表收窄后，选择条仍按旧 id 计数并让动作作用于已不存在的条目。
 */
internal fun pruneSelection(selected: Set<Long>, live: Set<Long>): Set<Long> =
    selected.filterTo(LinkedHashSet(selected.size)) { it in live }

/**
 * 一组分段里代表段的下标：`partIndex` 最小者（首段），脏数据（null）排到最后。
 *
 * 与 [SeriesGroup.groupSpans] 的排序口径一致。组卡动作（对比等）必须落到代表段，
 * 否则会开较晚的一段（Y27）。空列表返回 -1。
 */
internal fun representativePartIndex(partIndices: List<Int?>): Int {
    if (partIndices.isEmpty()) return -1
    return partIndices.indices.minByOrNull { partIndices[it] ?: Int.MAX_VALUE } ?: -1
}