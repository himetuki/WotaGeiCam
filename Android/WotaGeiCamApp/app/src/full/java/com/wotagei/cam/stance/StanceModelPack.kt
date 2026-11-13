package com.wotagei.cam.stance

import android.content.Context
import android.net.Uri
import com.wotagei.cam.diag.Diag
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * 模型组只有两个入口：APK assets 与用户明确点击的 zip 导入。两者共用目录规格校验；
 * zip 先在同卷独占暂存目录内完整校验，再替换激活目录，失败保留原模型组。
 * 包内 SHA 只证明三件套一致，不证明发布者身份；自训模型仍须由用户自行确认来源。
 */
object StanceModelPack {
    private const val TAG = "StanceModelPack"
    const val SCHEMA = 1
    internal const val MAX_JSON_BYTES = 64L * 1024
    internal const val MAX_MODEL_BYTES = 64L * 1024 * 1024
    internal const val MAX_PACK_BYTES = 128L * 1024 * 1024
    private const val META = "pack.meta.json"
    private val GPU_FILES = setOf("yolo11n_fp32.tflite", "yolo11n_fp16.tflite")
    private val BASE_FILES = setOf("head.json", "margins.json", "yolo11n_int8.tflite")
    private val BUILTIN_FILES = BASE_FILES + "yolo11n_fp32.tflite" + META
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun builtinDir(context: Context): File = File(context.filesDir, "model_pack_builtin")
    fun userDir(context: Context): File = File(context.filesDir, "model_pack_user")

    /** 外部目录只存待导入文件，永不作为 Interpreter 的加载目录。 */
    fun userExternalDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "external")
        return File(base, "model_packs").apply { mkdirs() }
    }

    fun importLatestFromExternal(context: Context): Map<String, Any?> {
        val zip = userExternalDir(context).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("模型目录里没有 zip——先从电脑拷入 model_pack.zip")
        return importZip(context, Uri.fromFile(zip))
    }

    @Synchronized
    fun activeDir(context: Context): Pair<File, String> {
        recoverPrevious(context.filesDir)
        val user = userDir(context)
        if (validateDir(user) != null) return user to "user"
        val builtin = builtinDir(context)
        if (validateDir(builtin) != null) return builtin to "builtin"
        installBuiltin(context)
        if (validateDir(builtin) != null) return builtin to "builtin"
        Diag.w(TAG, "模型组双通道均无效")
        throw IllegalStateException("无有效模型组")
    }

    private fun fileLimit(name: String): Long =
        if (name == META || name == "head.json" || name == "margins.json") MAX_JSON_BYTES
        else if (name == "yolo11n_int8.tflite" || name in GPU_FILES) MAX_MODEL_BYTES
        else throw IllegalArgumentException("模型组含未知文件")

    /** 严格清单：四个载荷、一个 meta；拒绝路径、别名、符号链接及未登记文件。 */
    fun validateDir(dir: File): Map<String, Any?>? = try {
        validate(dir)
    } catch (_: Exception) {
        null
    }

    private fun validate(dir: File): Map<String, Any?>? {
        val root = dir.canonicalFile
        if (!root.isDirectory || Files.isSymbolicLink(dir.toPath())) return null
        val children = dir.listFiles()?.toList() ?: return null
        if (children.size != 5) return null
        var total = 0L
        for (f in children) {
            if (!f.isFile || f.canonicalFile != File(root, f.name)) return null
            val size = f.length()
            if (size <= 0 || size > fileLimit(f.name)) return null
            total += size
            if (total > MAX_PACK_BYTES) return null
        }
        @Suppress("UNCHECKED_CAST")
        val meta = StanceJson.parse(readJson(File(dir, META))) as? Map<String, Any?> ?: return null
        if (meta["schema"] != SCHEMA.toDouble()) return null
        if (meta["features"] != StanceHeadSpec.FEATURES) return null
        val files = meta["files"] as? Map<*, *> ?: return null
        val gpu = GPU_FILES.singleOrNull { it in files } ?: return null
        if (files.keys != BASE_FILES + gpu) return null
        if (children.map { it.name }.toSet() != files.keys + META) return null
        for ((name, sha) in files) {
            if (sha !is String || !SHA256.matches(sha)) return null
            if (sha256Of(File(dir, name as String)) != sha) return null
        }
        // JSON 也在激活前验结构，不能把坏判定头/安全区交给运行期再静默回退。
        val head = StanceHead.fromJson(readJson(File(dir, "head.json")))
        if (!head.intercept.isFinite() || head.coef.any { !it.isFinite() }) return null
        StanceSpec.parseCalibratedBands(readJson(File(dir, "margins.json")))
        return meta
    }

    private fun readJson(file: File): String = file.inputStream().use {
        limited(it, MAX_JSON_BYTES).readBytes().toString(Charsets.UTF_8)
    }

    @Synchronized
    fun installBuiltin(context: Context): Boolean = try {
        val dir = builtinDir(context)
        dir.mkdirs()
        for (name in BUILTIN_FILES) {
            context.assets.open("model_pack/$name").use { input ->
                File(dir, name).outputStream().use { output -> limited(input, fileLimit(name)).copyTo(output) }
            }
        }
        validateDir(dir) != null
    } catch (e: Exception) {
        Diag.w(TAG, "内置模型组复制失败：${e.message}")
        false
    }

    fun importZip(context: Context, uri: Uri): Map<String, Any?> =
        context.contentResolver.openInputStream(uri)?.use { importStream(context.filesDir, it) }
            ?: throw IllegalStateException("无法读取所选文件")

    /** 平台无关的唯一导入事务，测试直接喂真实 zip/文件系统。move 仅供失败路径注入。 */
    @Synchronized
    internal fun importStream(
        filesDir: File,
        input: InputStream,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ): Map<String, Any?> {
        recoverPrevious(filesDir)
        val stale = filesDir.listFiles { f -> f.name.startsWith("model_pack_import_") }.orEmpty()
        check(stale.map { removeFlatDir(it) }.all { it }) { "模型暂存文件仍被占用，已取消导入，请稍后重试" }
        val staging = File.createTempFile("model_pack_import_", "", filesDir)
        check(staging.delete() && staging.mkdir()) { "无法创建模型暂存目录" }
        try {
            unpack(input, staging)
            val meta = validateDir(staging)
                ?: throw IllegalArgumentException("模型组校验失败（清单/sha256/规格不符）")
            val target = File(filesDir, "model_pack_user")
            val previous = File(filesDir, "model_pack_previous")
            check(!previous.exists()) { "原模型组待恢复，请重试" }
            check(!Files.isSymbolicLink(target.toPath())) { "模型激活目录不可为链接" }
            val hadPrevious = target.exists()
            if (hadPrevious) check(move(target, previous)) { "无法保留原模型组，已取消导入" }
            if (!move(staging, target)) {
                if (hadPrevious) check(previous.renameTo(target)) { "原模型组待恢复，请重试" }
                throw IllegalStateException("模型组激活失败，已保留原模型组")
            }
            removeFlatDir(previous)
            return meta
        } finally {
            removeFlatDir(staging)
        }
    }

    /** rename 之间进程退出：优先恢复最后一个有效包，绝不把半包当新包。 */
    private fun recoverPrevious(filesDir: File) {
        val target = File(filesDir, "model_pack_user")
        val previous = File(filesDir, "model_pack_previous")
        if (!previous.exists()) return
        if (validateDir(target) != null) {
            removeFlatDir(previous)
        } else if (!target.exists() && validateDir(previous) != null) {
            check(previous.renameTo(target)) { "原模型组恢复失败" }
        }
    }

    /** 只清本类创建的平铺目录；不递归跟随链接或未知子目录。 */
    internal fun removeFlatDir(dir: File, delete: (File) -> Boolean = { it.delete() }): Boolean {
        if (!dir.exists()) return true
        val parent = dir.parentFile ?: return false
        if (Files.isSymbolicLink(dir.toPath()) || dir.canonicalFile.parentFile != parent.canonicalFile) return false
        val files = dir.listFiles() ?: return false
        if (files.any { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }) return false
        var removed = true
        for (file in files) if (!delete(file)) removed = false
        if (removed) removed = delete(dir)
        if (!removed) Diag.w(TAG, "模型临时目录清理失败，后续导入将先回收：${dir.name}")
        return removed
    }

    private fun unpack(input: InputStream, dir: File) {
        ZipInputStream(limited(input, MAX_PACK_BYTES).buffered()).use { zip ->
            val names = mutableSetOf<String>()
            var wrapper: String? = null
            var entries = 0
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 6) { "模型 zip 条目过多" }
                val raw = entry.name
                require(!raw.contains('\\') && !raw.contains(':') && raw.length <= 100) { "模型 zip 路径非法" }
                if (entry.isDirectory) {
                    require(raw == "model_pack/" && wrapper != "root") { "模型 zip 目录非法" }
                    require(zip.read() == -1) { "模型 zip 目录不可含数据" }
                    wrapper = "model_pack"
                } else {
                    val parts = raw.split('/')
                    val kind = if (parts.size == 1) "root" else "model_pack"
                    require(parts.size == 1 || parts.size == 2 && parts[0] == "model_pack") { "模型 zip 路径非法" }
                    require(wrapper == null || wrapper == kind) { "模型 zip 不得混用根目录与 model_pack 目录" }
                    wrapper = kind
                    val name = parts.last()
                    val limit = fileLimit(name)
                    require(names.add(name) && names.size <= 5) { "模型 zip 文件重复或过多" }
                    require(entry.size <= limit) { "模型文件超过体积上限" }
                    var size = 0L
                    File(dir, name).outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            size += n
                            total += n
                            require(size <= limit && total <= MAX_PACK_BYTES) { "模型文件超过体积上限" }
                            output.write(buf, 0, n)
                        }
                    }
                    require(size > 0) { "模型组含空文件" }
                }
                zip.closeEntry()
            }
            require(names.size == 5) { "模型 zip 必须含 meta/head/margins/两个 tflite" }
        }
    }

    private fun limited(input: InputStream, max: Long): InputStream = object : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int {
            val n = super.read()
            if (n >= 0) checkLimit(1)
            return n
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = `in`.read(b, off, minOf(len.toLong(), max - count + 1).toInt())
            if (n > 0) checkLimit(n)
            return n
        }
        private fun checkLimit(n: Int) {
            count += n
            require(count <= max) { "模型文件超过体积上限" }
        }
    }

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
