package com.wotagei.cam.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 备份只携带用户意图，不携带权限、URI、MediaStore ID、回收站、机型能力或模型文件。 */
internal object PortableUserData {
    const val FILE_NAME = "wota_user_backup.bin"
    const val PREFS_NAME = "wota_settings"
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val VERSION = 1
    private const val MAX_TAGS = 10000
    private const val MAX_STRING_BYTES = 65536

    /** 明确的键白名单：新增设置须显式评估能否迁移；尤其不能把 compare_history 的 URI 带走。 */
    val SETTING_KEYS: Set<String> = setOf(
        "motion_mode", "default_fps", "default_shutter_ns", "default_bitrate", "default_sample_rate",
        "default_render_mode", "default_audio_muted", "default_iso", "default_ae_mode", "default_ev",
        "default_wb_preset", "default_wb_kelvin", "default_wb_tint", "default_zoom", "default_flash",
        "default_stabilize", "default_af_mode", "default_mf_diopter", "default_size", "default_frame_effect",
        "default_zebra_threshold", "default_zebra_density", "default_peaking_strength", "default_peaking_color",
        // LensType 是产品枚举，不是 cameraId；本机的 lens_user_picked/能力协商状态不迁移。
        "default_lens", "default_arc_merge_deadband", "denoise_audio", "denoise_video",
        "correction_auto", "correction_manual_ns", "default_marker_spread", "default_marker_color",
        "display_scale", "glow_params", "echo_params", "default_ref_lines", "level_enabled",
        "level_buzz_enabled", "hud_items", "hud_pills", "hud_pills_schema", "ui_orientation", "hud_layout",
        "text_scale_camera", "text_scale_settings", "text_scale_dialog", "gallery_columns",
        "camera_brighten_delay_sec", "player_brighten_ramp_tenths", "player_top_bar_collapsed",
        "curve_panel_side_dp", "tone_params", "slider_continuous", "arc_convert_mode", "hud_frost_blur",
        "curve_stack", "compare_tick_ms", "stance_mode", "stance_n", "stance_beta", "stance_brighten",
        "stance_line_interval_ms", "stance_poll_interval_ms"
        // 诊断开关、站位检测启动/GPU 模式保持新机默认；不自动激活日志或依赖本机模型的功能。
    )

    data class MediaKey(val relativePath: String, val name: String, val size: Long, val durationMs: Long) {
        fun valid(): Boolean = relativePath.isNotBlank() && !relativePath.startsWith('/') &&
            relativePath.split('/').none { it == ".." || it == "." || it.contains('\\') } &&
            name.isNotBlank() && name.none { it == '/' || it == '\\' || it.isISOControl() } &&
            relativePath.none { it.isISOControl() } && size > 0 && durationMs > 0
    }

    data class Tag(val media: MediaKey, val value: String, val createdAt: Long)
    data class Snapshot(val settings: Map<String, Any>, val tags: List<Tag>)

    /** 格式损坏可丢弃；原始输入流的 IO 异常不包装，留给调用方保源重试。 */
    class InvalidSnapshotException(message: String, cause: Exception? = null) : IOException(message, cause)

    private val BOOLEAN_KEYS = setOf("default_audio_muted", "correction_auto", "level_enabled",
        "level_buzz_enabled", "player_top_bar_collapsed", "hud_frost_blur", "stance_brighten")
    private val INT_KEYS = setOf("default_fps", "default_shutter_ns", "default_bitrate", "default_sample_rate",
        "default_iso", "default_ev", "default_wb_kelvin", "default_wb_tint", "default_zebra_density",
        "default_arc_merge_deadband", "default_ref_lines", "hud_items", "hud_pills", "hud_pills_schema",
        "text_scale_camera", "text_scale_settings", "text_scale_dialog", "gallery_columns",
        "camera_brighten_delay_sec", "player_brighten_ramp_tenths", "compare_tick_ms", "stance_n")
    private val LONG_KEYS = setOf("correction_manual_ns", "stance_line_interval_ms", "stance_poll_interval_ms")
    private val FLOAT_KEYS = setOf("default_zoom", "default_mf_diopter", "default_zebra_threshold",
        "default_peaking_strength", "default_marker_spread", "curve_panel_side_dp", "stance_beta")

    fun settingsOf(all: Map<String, *>): Map<String, Any> = all.mapNotNull { (key, value) ->
        val valid = key in SETTING_KEYS && when (key) {
            in BOOLEAN_KEYS -> value is Boolean
            in INT_KEYS -> value is Int
            in LONG_KEYS -> value is Long
            in FLOAT_KEYS -> value is Float && value.isFinite()
            else -> value is String && value.length <= MAX_STRING_BYTES &&
                value.toByteArray(Charsets.UTF_8).size <= MAX_STRING_BYTES
        }
        if (valid && value != null) key to value else null
    }.toMap()

    /** 恢复是补充，不覆盖目标设备已存在的用户设置。 */
    fun settingsToRestore(restored: Map<String, *>, current: Map<String, *>): Map<String, Any> =
        settingsOf(restored).filterKeys { it !in current }

    /** 同一媒体的同一标签只保留一份，已有待匹配项优先，重复 restore 不累积数据。 */
    fun mergeTags(existing: List<Tag>, incoming: List<Tag>): List<Tag> =
        (existing + incoming).distinctBy { it.media to it.value }.also { require(it.size <= MAX_TAGS) }

    /** 一次建索引，只保留完整且唯一的指纹；runtime 不按每条标签重复扫描全部媒体。 */
    fun <T> uniqueCandidates(items: List<T>, keyOf: (T) -> MediaKey): Map<MediaKey, T> =
        items.groupBy(keyOf).filter { (key, rows) -> key.valid() && rows.size == 1 }
            .mapValues { (_, rows) -> rows.first() }

    /** 只对唯一、完整匹配的媒体恢复标签；同名/同 ID 均不足为凭。 */
    fun matches(key: MediaKey, candidates: List<MediaKey>): Boolean = key in uniqueCandidates(candidates) { it }

    fun write(snapshot: Snapshot, output: OutputStream) {
        val settings = settingsOf(snapshot.settings)
        val tags = snapshot.tags.filter { it.media.valid() && it.value.isNotBlank() && it.value.length <= 128 }
            .distinctBy { it.media to it.value }.also { require(it.size <= MAX_TAGS) }
        val out = DataOutputStream(object : OutputStream() {
            private var written = 0
            private fun checkLimit(count: Int) {
                if (count > MAX_BYTES - written) throw IOException("备份超过上限")
            }
            override fun write(value: Int) {
                checkLimit(1)
                output.write(value)
                written++
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                checkLimit(length)
                output.write(bytes, offset, length)
                written += length
            }
            override fun flush() = output.flush()
        })
        out.writeInt(VERSION)
        out.writeInt(settings.size)
        settings.forEach { (key, value) ->
            out.text(key)
            when (value) {
                is Boolean -> { out.writeByte(1); out.writeBoolean(value) }
                is Int -> { out.writeByte(2); out.writeInt(value) }
                is Long -> { out.writeByte(3); out.writeLong(value) }
                is Float -> { out.writeByte(4); out.writeFloat(value) }
                is String -> { out.writeByte(5); out.text(value) }
            }
        }
        out.writeInt(tags.size)
        tags.forEach { tag ->
            out.text(tag.media.relativePath)
            out.text(tag.media.name)
            out.writeLong(tag.media.size)
            out.writeLong(tag.media.durationMs)
            out.text(tag.value)
            out.writeLong(tag.createdAt)
        }
        out.flush()
    }

    /** 不使用 Java 反序列化；坏版本、越界长度、截断与尾随数据全部拒绝，调用方不应用半份快照。 */
    fun read(input: InputStream): Snapshot {
        val buffer = ByteArray(8192)
        val bytes = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (bytes.size() + n > MAX_BYTES) throw InvalidSnapshotException("备份超过上限")
            bytes.write(buffer, 0, n)
        }
        // 从这里起只解析内存字节，异常明确是坏格式；上面的真实读取异常原样传播，不能误删源。
        return try {
            val data = DataInputStream(bytes.toByteArray().inputStream())
            require(data.readInt() == VERSION) { "不支持的备份版本" }
            val settings = linkedMapOf<String, Any>()
            repeat(data.count(SETTING_KEYS.size)) {
                val key = data.text()
                val value: Any = when (data.readByte().toInt()) {
                    1 -> data.readBoolean()
                    2 -> data.readInt()
                    3 -> data.readLong()
                    4 -> data.readFloat()
                    5 -> data.text()
                    else -> error("不支持的设置类型")
                }
                require(!settings.containsKey(key))
                settings[key] = value
            }
            val tags = List(data.count(MAX_TAGS)) {
                Tag(MediaKey(data.text(), data.text(), data.readLong(), data.readLong()), data.text(), data.readLong())
                    .also { require(it.media.valid() && it.value.isNotBlank() && it.value.length <= 128) }
            }
            require(data.read() == -1) { "备份存在尾随数据" }
            Snapshot(settingsOf(settings), tags.distinctBy { it.media to it.value })
        } catch (e: Exception) {
            throw InvalidSnapshotException("备份格式或版本无效", e)
        }
    }

    private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }
    private fun DataInputStream.text(): String = ByteArray(count(MAX_STRING_BYTES))
        .also { readFully(it) }.toString(Charsets.UTF_8)
    private fun DataOutputStream.text(value: String) {
        require(value.length <= MAX_STRING_BYTES)
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }
}
