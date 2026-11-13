package com.wotagei.cam.backup

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

class PortableUserDataTest {
    private val main: File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .map { dir -> listOf(File(dir, "src/main"), File(dir, "app/src/main"),
            File(dir, "Android/WotaGeiCamApp/app/src/main")) }
        .flatten().first { File(it, "AndroidManifest.xml").isFile }
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun xml(file: File): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file).documentElement

    private fun ruleSets(): List<Element> {
        val app = xml(File(main, "AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        assertEquals("true", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("true", app.getAttributeNS(androidNs, "fullBackupOnly"))
        assertEquals(".backup.WotaBackupAgent", app.getAttributeNS(androidNs, "backupAgent"))
        val legacy = app.getAttributeNS(androidNs, "fullBackupContent").removePrefix("@xml/")
        val modern = app.getAttributeNS(androidNs, "dataExtractionRules").removePrefix("@xml/")
        val full = xml(File(main, "res/xml/$legacy.xml"))
        val extraction = xml(File(main, "res/xml/$modern.xml"))
        assertEquals("full-backup-content", full.tagName)
        assertEquals("data-extraction-rules", extraction.tagName)
        assertEquals(1, extraction.getElementsByTagName("cloud-backup").length)
        assertEquals(1, extraction.getElementsByTagName("device-transfer").length)
        return listOf(full, extraction.getElementsByTagName("cloud-backup").item(0) as Element,
            extraction.getElementsByTagName("device-transfer").item(0) as Element)
    }

    /** 模拟 Android 的正向 include 语义：只要出现 include，未列出的域/路径默认拒绝。 */
    private fun allowed(rules: Element, domain: String, path: String): Boolean {
        fun matches(tag: String): Boolean {
            val nodes = rules.getElementsByTagName(tag)
            return (0 until nodes.length).any { i ->
                val rule = nodes.item(i) as Element
                val prefix = rule.getAttribute("path").removeSuffix("/")
                rule.getAttribute("domain") == domain &&
                    (prefix == "." || path == prefix || path.startsWith("$prefix/"))
            }
        }
        return (rules.getElementsByTagName("include").length == 0 || matches("include")) && !matches("exclude")
    }

    @Test fun `实际manifest接线的两代XML云和换机都只允许用户快照`() {
        for (rules in ruleSets()) {
            val includes = rules.getElementsByTagName("include")
            assertEquals(1, includes.length)
            val entry = includes.item(0) as Element
            assertEquals("file", entry.getAttribute("domain"))
            assertEquals(PortableUserData.FILE_NAME, entry.getAttribute("path"))
            assertTrue(allowed(rules, "file", PortableUserData.FILE_NAME))
            val denied = listOf(
                "file" to "model_pack_builtin/model.tflite", "file" to "model_pack_user/model.tflite",
                "file" to "diag/crash.log", "file" to "future-private-file",
                "sharedpref" to "wota_settings.xml", "sharedpref" to "wota_diag.xml",
                "sharedpref" to "wota_lens_pick.xml", "sharedpref" to "wota_player_ab.xml",
                "database" to "wota_media.db", "database" to "wota_media.db-wal",
                "external" to "logs/session.log", "external" to "model_pack_template.zip",
                "root" to "shared_prefs/unknown.xml", "device_file" to "private-state",
                "device_sharedpref" to "unknown.xml", "device_database" to "unknown.db")
            denied.forEach { (domain, path) -> assertFalse("${rules.tagName}: $domain/$path", allowed(rules, domain, path)) }
            // 守卫自证：删掉 include 就会回到平台的全域备份默认，必须能被行为矩阵识别。
            val mutated = rules.cloneNode(true) as Element
            mutated.removeChild(mutated.getElementsByTagName("include").item(0))
            assertTrue(allowed(mutated, "file", "model_pack_user/model.tflite"))
        }
    }

    @Test fun `设置明确按键和类型白名单并排除设备绑定和未来字段`() {
        val approved = mapOf("motion_mode" to "FLUENT", "gallery_columns" to 4, "level_enabled" to true,
            "default_zoom" to 1.25f, "correction_manual_ns" to 500000L)
        val input = approved + mapOf("compare_history" to "content://media/external/video/media/42",
            "diag_enabled" to true, "diag_verbose" to true, "last_session" to "old-device",
            "lens_user_picked" to true, "stance_on" to true, "stance_gpu_mode" to "force",
            "future_secret" to "private", "default_iso" to "wrong type", "stance_beta" to Float.NaN)
        assertEquals(approved, PortableUserData.settingsOf(input))
        assertEquals(approved, PortableUserData.settingsOf(PortableUserData.settingsOf(input)))
    }

    @Test fun `快照真实往返保留收藏标签且不携带媒体ID或本机URI`() {
        val key = PortableUserData.MediaKey("Movies/WotaGeiCam/Camera/", "练习.mp4", 123456L, 3456L)
        val snapshot = PortableUserData.Snapshot(mapOf("gallery_columns" to 3),
            listOf(PortableUserData.Tag(key, "like", 123L), PortableUserData.Tag(key, "技巧", 124L)))
        val output = ByteArrayOutputStream()
        PortableUserData.write(snapshot, output)
        assertEquals(snapshot, PortableUserData.read(ByteArrayInputStream(output.toByteArray())))
        val raw = output.toByteArray().toString(Charsets.UTF_8)
        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("/storage/emulated/"))
        assertFalse(raw.contains("mediaId"))
    }

    @Test fun `迁移只认完整唯一指纹同名同ID不足为凭且未迁移媒体不误贴标签`() {
        val key = PortableUserData.MediaKey("Movies/", "clip.mp4", 123L, 1000L)
        assertTrue(PortableUserData.matches(key, listOf(key)))
        assertFalse(PortableUserData.matches(key, emptyList()))
        assertFalse(PortableUserData.matches(key, listOf(key, key)))
        assertFalse(PortableUserData.matches(key, listOf(key.copy(size = 124))))
        assertFalse(PortableUserData.matches(key, listOf(key.copy(relativePath = "DCIM/"))))
        assertFalse(PortableUserData.matches(key, listOf(key.copy(durationMs = 999))))
        assertFalse(PortableUserData.matches(key.copy(relativePath = "../private/"), listOf(key)))
        assertFalse(PortableUserData.matches(key.copy(durationMs = 0), listOf(key.copy(durationMs = 0))))
        assertFalse(PortableUserData.matches(key.copy(size = 0), listOf(key.copy(size = 0))))
    }

    @Test fun `运行时共用的候选索引每媒体只投影一次且歧义不选首个`() {
        val keys = (0 until 10000).map { PortableUserData.MediaKey("Movies/", "$it.mp4", 1L, 1L) }
        var calls = 0
        val unique = PortableUserData.uniqueCandidates(keys + keys.first()) { key -> calls++; key }
        assertEquals(10001, calls)
        assertEquals(9999, unique.size)
        assertFalse(unique.containsKey(keys.first()))
        assertEquals(keys.last(), unique[keys.last()])
        assertFalse(PortableUserData.uniqueCandidates(listOf(keys.first().copy(size = 0))) { it }.isNotEmpty())
    }

    @Test fun `恢复补缺不覆盖本机设置且合并去重原有pending`() {
        val current = mapOf("gallery_columns" to 5, "compare_history" to "local-only")
        assertEquals(mapOf("level_enabled" to true), PortableUserData.settingsToRestore(
            mapOf("gallery_columns" to 3, "level_enabled" to true, "compare_history" to "old-device"), current))
        val key = PortableUserData.MediaKey("Movies/", "a.mp4", 123L, 1000L)
        val old = PortableUserData.Tag(key, "like", 100L)
        val incoming = listOf(old.copy(createdAt = 200L), PortableUserData.Tag(key, "新标签", 300L))
        val merged = PortableUserData.mergeTags(listOf(old), incoming)
        assertEquals(listOf(old, incoming[1]), merged)
        assertEquals(merged, PortableUserData.mergeTags(merged, incoming))
        assertEquals(listOf(old), PortableUserData.mergeTags(listOf(old), emptyList()))
    }

    @Test fun `累计限写在写入途中拒绝且真实文件从不超过4MiB`() {
        val app = requireNotNull(requireNotNull(main.parentFile).parentFile)
        val file = File(app, "build/tmp/backup-bounded-${System.nanoTime()}.bin")
        requireNotNull(file.parentFile).mkdirs()
        try {
            // 每个字段都在单字段帽内，但总量超帽；不能先写数百MiB再检查文件大小。
            val key = PortableUserData.MediaKey("x".repeat(60000) + "/", "a.mp4", 1L, 1L)
            val tags = (0..100).map { PortableUserData.Tag(key.copy(name = "$it.mp4"), "like", it.toLong()) }
            try {
                file.outputStream().use { PortableUserData.write(PortableUserData.Snapshot(emptyMap(), tags), it) }
                fail("累计超帽必须在写入中抛异常")
            } catch (_: IOException) { }
            assertTrue("没有悄悄省略标签，确实已写到边界附近", file.length() > 3 * 1024 * 1024)
            assertTrue("输出绝不超过总帽", file.length() <= PortableUserData.MAX_BYTES)
        } finally {
            file.delete()
        }
    }

    @Test fun `坏版本截断尾随数据和超量快照不能部分恢复`() {
        val out = ByteArrayOutputStream()
        PortableUserData.write(PortableUserData.Snapshot(mapOf("gallery_columns" to 4), emptyList()), out)
        val bytes = out.toByteArray()
        listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1),
            bytes.clone().also { it[3] = 99 }, ByteArray(PortableUserData.MAX_BYTES + 1)).forEach { bad ->
            try {
                PortableUserData.read(bad.inputStream())
                fail("坏快照必须拒绝")
            } catch (_: Exception) { }
        }
    }
}
