package com.wotagei.cam.player

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 双视频对比的「历史」存储（对照播放页顶栏入口）。
 *
 * 结构化数据照 `WotaSettings.KEY_CURVE_STACK` 那条先例：整表编成一段 JSON 串存进
 * SharedPreferences 单键（`wota_settings` 文件的 [KEY_COMPARE_HISTORY]），不建表不起文件。
 * 键内容即契约：`[{"rightId":..,"leftId":..,"ts":..}, ...]`，队首最新。
 */
object CompareHistory {

    const val KEY_COMPARE_HISTORY = "compare_history"

    /** 历史上限：低频便利功能，最新 12 条足够回看，多了只拖慢反查 */
    private const val MAX_ENTRIES = 12

    /** 一条对照记录：右侧视频 rightId 与哪条左侧 leftId 搭过、什么时候（epoch ms） */
    data class Entry(val rightId: Long, val leftId: Long, val ts: Long)

    /**
     * 读全部记录（队首最新）。容错口径：缺键返回空表；整串 JSON 解析失败返回空表；
     * 单条缺字段/类型不对/id 非正数的坏行**跳过不抛**，返回前面已解析成功的行——
     * 历史只是便利性缓存，坏一行不该把整个面板打挂。
     */
    fun entries(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(KEY_COMPARE_HISTORY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = runCatching {
                val o = arr.getJSONObject(i)
                Entry(o.getLong("rightId"), o.getLong("leftId"), o.getLong("ts"))
            }.getOrNull() ?: continue
            if (entry.rightId <= 0L || entry.leftId <= 0L) continue
            out += entry
        }
        return out
    }

    /**
     * 记一次对照。按 rightId 去重：同一右侧视频无论之前搭过谁、记过几次，都只在队首留一条
     * （更新 leftId + ts 后插队首）；新条目同样插队首。超过 [MAX_ENTRIES] 从队尾截断。
     * `apply()` 异步落盘：消费方只在本页组合期读，内存立即可见即可。
     */
    fun record(prefs: SharedPreferences, rightId: Long, leftId: Long) {
        if (rightId <= 0L) return
        val next = entries(prefs).toMutableList()
        next.removeAll { it.rightId == rightId }
        next.add(0, Entry(rightId, leftId, System.currentTimeMillis()))
        prefs.edit()
            .putString(KEY_COMPARE_HISTORY, encode(next.take(MAX_ENTRIES)))
            .apply()
    }

    /** 按 rightId 摘掉一条（没有该条即无操作），同样 `apply()` */
    fun remove(prefs: SharedPreferences, rightId: Long) {
        val next = entries(prefs).filterNot { it.rightId == rightId }
        prefs.edit().putString(KEY_COMPARE_HISTORY, encode(next)).apply()
    }

    private fun encode(list: List<Entry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("rightId", e.rightId)
                    .put("leftId", e.leftId)
                    .put("ts", e.ts)
            )
        }
        return arr.toString()
    }
}
