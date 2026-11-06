package com.wotagei.cam.stance

import com.wotagei.cam.diag.Diag

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 模型组加载与校验（M2b，D20）：
 * **双通道**：① APK `assets/model_pack/` 内置（首启复制到 filesDir，随版本走）；
 * ② 用户自助导入——设置页 SAF 选 zip → 解压到 filesDir/model_pack_user → 校验激活。
 *
 * **校验与回退**（用户参与原则：一切自动降级必提示）：加载前校验
 * pack.meta.json 的 schema/features 与各文件 sha256；**校验失败自动回退内置模型组
 * 并标记**，绝不因用户替换坏文件导致功能瘫痪。
 * 三件套必须同批产出（meta 记录 trained_at/sessions）——防“新头+旧安全区”口径漂移。
 */
object StanceModelPack {
    private const val TAG = "StanceModelPack"
    const val SCHEMA = 1

    /** 激活的模型组目录（built-in=随包，user=用户导入）。 */
    fun builtinDir(context: Context): File =
        File(context.filesDir, "model_pack_builtin")

    fun userDir(context: Context): File =
        File(context.filesDir, "model_pack_user")

    /**
     * 「自行存入模型」目录（D20 用户口径）：App 专属外部目录——用户可在电脑 USB
     * 或部分文件管理器直接放入 model_pack.zip，弹窗「扫描目录导入」一键激活。
     * 完整路径在弹窗文本标注（用户要求：无法直接跳转就改文本标注）。
     */
    fun userExternalDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "external")
        return File(base, "model_packs").apply { mkdirs() }
    }

    /** 扫描 [userExternalDir] 下最新的 zip 并导入（「自行存入模型」通道）。 */
    fun importLatestFromExternal(context: Context): Map<String, Any?> {
        val dir = userExternalDir(context)
        val zip = dir.listFiles { f -> f.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("模型目录里没有 zip——先从电脑拷入 model_pack.zip")
        return importZip(context, Uri.fromFile(zip))
    }

    /** 当前激活目录：用户包有效 > 内置包（缓存校验结果，meta 级别）。 */
    fun activeDir(context: Context): Pair<File, String> {
        val user = userDir(context)
        val userMeta = validateDir(user)
        if (userMeta != null) return user to "user"
        val builtin = builtinDir(context)
        val builtinMeta = validateDir(builtin)
        if (builtinMeta != null) return builtin to "builtin"
        // 双双无效：尝试从 assets 重铺内置
        installBuiltin(context)
        val re = validateDir(builtin)
        if (re != null) return builtin to "builtin"
        Diag.w(TAG, "模型组双通道均无效")
        throw IllegalStateException("无有效模型组")
    }

    /** 目录校验：pack.meta.json 结构 + 各文件 sha256。返回 meta（null=无效）。 */
    fun validateDir(dir: File): Map<String, Any?>? {
        val metaFile = File(dir, "pack.meta.json")
        if (!metaFile.isFile) return null
        val meta = try {
            @Suppress("UNCHECKED_CAST")
            StanceJson.parse(metaFile.readText()) as? Map<String, Any?> ?: return null
        } catch (_: Exception) {
            return null
        }
        if ((meta["schema"] as? Double)?.toInt() != SCHEMA) return null
        @Suppress("UNCHECKED_CAST")
        val features = (meta["features"] as? List<*>)?.map { it.toString() } ?: return null
        if (features != StanceHeadSpec.FEATURES) return null
        @Suppress("UNCHECKED_CAST")
        val files = meta["files"] as? Map<String, Any?> ?: return null
        if (files.isEmpty()) return null   // 空模型清单 = 无模型文件的空包，判无效
        for ((name, sha) in files) {
            val f = File(dir, name)
            if (!f.isFile) return null
            if (sha256Of(f) != sha) return null
        }
        return meta
    }

    /** 首启/兜底：从 assets 复制内置模型组到 filesDir（覆盖式，随版本更新）。 */
    fun installBuiltin(context: Context): Boolean {
        val dir = builtinDir(context)
        dir.mkdirs()
        val names = listOf("pack.meta.json", "margins.json", "head.json",
            "yolo11n_int8.tflite", "yolo11n_fp32.tflite")
        return try {
            for (name in names) {
                context.assets.open("model_pack/$name").use { input ->
                    File(dir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            true
        } catch (e: Exception) {
            Diag.w(TAG, "内置模型组复制失败：${e.message}")
            false
        }
    }

    /**
     * SAF 导入 zip（用户自助替换，D20）：解压到 userDir → 校验 → 成功返回 meta，
     * 失败清残留并抛异常（UI 提示，激活仍是内置包）。
     */
    fun importZip(context: Context, uri: Uri): Map<String, Any?> {
        val tmp = File(context.cacheDir, "model_pack_import")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entries = 0
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = File(entry.name).name   // 只取文件名（防 zip 路径穿越）
                            File(tmp, name).outputStream().use { zip.copyTo(it) }
                            entries++
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    require(entries >= 5) { "zip 内文件不足（应有 pack.meta/margins/head/两个 tflite）" }
                }
            } ?: throw IllegalStateException("无法读取所选文件")
            val meta = validateDir(tmp) ?: throw IllegalArgumentException(
                "模型组校验失败（sha256/规格不符）——已保持内置模型组")
            // 校验通过 → 就地替换 userDir
            userDir(context).deleteRecursively()
            tmp.renameTo(userDir(context))
            return meta
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
