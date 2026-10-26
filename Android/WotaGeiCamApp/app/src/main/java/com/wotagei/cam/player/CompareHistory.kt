package com.wotagei.cam.player

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 双视频对比的「历史」存储（对照播放页顶栏入口）。
 *
 * 结构化数据照 `WotaSettings.KEY_CURVE_STACK` 那条先例：整表编成一段 JSON 串存进
 * SharedPreferences 单键（`wota_settings` 文件的 [KEY_COMPARE_HISTORY]），不建表不起文件。
 * 键内容即契约：`[{"rightId":..,"rightUri":"..","leftId":..,"ts":..}, ...]`，队首最新。
 *
 * 2026-10-26 起右侧身份不再只有库内 mediaId：外来来源（照片选择器/文档 provider 的回程 URI）
 * 没有 MediaStore 行，只能靠 `rightUri` 记住（encode 时只在非空才写）；库内条目仍写 `rightId`
 * （外来来源 rightId=0）。旧 JSON（只有 rightId、没有 rightUri）解析口径完全不变，照旧可用。
 */
object CompareHistory {

    const val KEY_COMPARE_HISTORY = "compare_history"

    /** 历史上限：低频便利功能，最新 12 条足够回看，多了只拖慢反查 */
    private const val MAX_ENTRIES = 12

    /**
     * 一条对照记录：右侧视频（库内 rightId 或外来 rightUri）与哪条左侧 leftId 搭过、
     * 什么时候（epoch ms）。外来来源不在库内，rightId=0 且 rightUri 非空；库内条目也存
     * rightUri（行因权限变化暂时不可见时按 URI 回退），nullable 只为兼容只写 id 的旧数据。
     */
    data class Entry(
        val rightId: Long,
        val rightUri: String?,
        val leftId: Long,
        val ts: Long
    ) {
        /**
         * 去重/删除键：库内按 id、外来按 uri。两种命名空间前缀隔离，uri 条目不会与
         * 同数字 id 的库内条目互相覆盖。
         */
        val key: String get() = if (rightId > 0L) "media:$rightId" else "uri:$rightUri"
    }

    /**
     * 读全部记录（队首最新）。容错口径：缺键返回空表；整串 JSON 解析失败返回空表；
     * 单条缺 leftId/ts 或类型不对的坏行**跳过不抛**，返回前面已解析成功的行——
     * 历史只是便利性缓存，坏一行不该把整个面板打挂。
     *
     * 接受口径：只要 `rightUri` 非空或 `rightId > 0` 就收（旧 JSON 只有 rightId，走后者）；
     * `rightUri` 用 optString 容错读取，库内旧行不会因缺这个新字段被丢。
     */
    fun entries(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(KEY_COMPARE_HISTORY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val entry = runCatching {
                val o = arr.getJSONObject(i)
                Entry(
                    rightId = o.optLong("rightId", 0L),
                    rightUri = o.optString("rightUri").takeIf { it.isNotEmpty() },
                    leftId = o.getLong("leftId"),
                    ts = o.getLong("ts")
                )
            }.getOrNull() ?: continue
            if (entry.rightId <= 0L && entry.rightUri.isNullOrEmpty()) continue
            out += entry
        }
        return out
    }

    /**
     * 记一次对照。右侧身份取 `rightId`（库内）或 `rightUri`（外来），两者都空时不记。
     * 按 [Entry.key] 去重：同一右侧视频无论之前搭过谁、记过几次，都只在队首留一条
     * （更新 leftId + ts 后插队首）；新条目同样插队首。超过 [MAX_ENTRIES] 从队尾截断。
     * `apply()` 异步落盘：消费方只在本页组合期读，内存立即可见即可。
     */
    fun record(prefs: SharedPreferences, rightId: Long, rightUri: String?, leftId: Long) {
        if (rightId <= 0L && rightUri.isNullOrEmpty()) return
        val entry = Entry(
            rightId = rightId,
            rightUri = rightUri?.takeIf { it.isNotEmpty() },
            leftId = leftId,
            ts = System.currentTimeMillis()
        )
        val next = entries(prefs).toMutableList()
        next.removeAll { it.key == entry.key }
        next.add(0, entry)
        prefs.edit()
            .putString(KEY_COMPARE_HISTORY, encode(next.take(MAX_ENTRIES)))
            .apply()
    }

    /** 旧签名重载：库内条目记账（rightUri=null），保留给只认 mediaId 的调用方 */
    fun record(prefs: SharedPreferences, rightId: Long, leftId: Long) =
        record(prefs, rightId, null, leftId)

    /** 按 [Entry.key] 摘掉一条（没有该条即无操作），同样 `apply()` */
    fun remove(prefs: SharedPreferences, key: String) {
        val next = entries(prefs).filterNot { it.key == key }
        prefs.edit().putString(KEY_COMPARE_HISTORY, encode(next)).apply()
    }

    private fun encode(list: List<Entry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
                .put("rightId", e.rightId)
            // 外来来源才写 uri；库内条目省写，旧版读取方（只认 rightId）也能照旧消费
            if (!e.rightUri.isNullOrEmpty()) o.put("rightUri", e.rightUri)
            o.put("leftId", e.leftId)
                .put("ts", e.ts)
            arr.put(o)
        }
        return arr.toString()
    }
}
