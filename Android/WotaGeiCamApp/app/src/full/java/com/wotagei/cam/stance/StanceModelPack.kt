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

    /**
     * 检测输出坐标单位（Y66/R16）：清单顶层 `coord` 显式声明，导入校验与运行期共用。
     * - [COORD_NORMALIZED]：cx/cy/w/h 已是 0~1 画面占比（随包 litert wrapper 的实测输出）；
     * - [COORD_PIXEL]：相对模型输入张量宽高的像素（如 0~448，不是分析帧像素）。
     * 字段缺失只用于兼容既有合法旧包：此时检测器保留值域自适应，与升级前行为一致。
     */
    const val COORD_NORMALIZED = "normalized"
    const val COORD_PIXEL = "pixel"
    private val COORD_UNITS = setOf(COORD_NORMALIZED, COORD_PIXEL)

    /** 读取已通过 [validateDir] 的坐标单位；缺失（旧包）返回 null，非法值不会走到这里。 */
    internal fun coordinateUnit(meta: Map<String, Any?>): String? =
        meta["coord"] as? String

    internal const val MAX_JSON_BYTES = 64L * 1024
    internal const val MAX_MODEL_BYTES = 64L * 1024 * 1024
    internal const val MAX_PACK_BYTES = 128L * 1024 * 1024
    private const val META = "pack.meta.json"
    private const val BUILTIN_STAMP = "model_pack_builtin.stamp"
    private val GPU_FILES = setOf("yolo11n_fp32.tflite", "yolo11n_fp16.tflite")
    private val BASE_FILES = setOf("head.json", "margins.json", "yolo11n_int8.tflite")
    private val BUILTIN_FILES = BASE_FILES + "yolo11n_fp32.tflite" + META
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** 回滚结果结构化上抛（Y76）：调用方必须按真实状态给用户提示。 */
    internal enum class RollbackStatus {
        /** 原模型组从未被触碰。 */
        UNTOUCHED,

        /** 已恢复上一套模型组。 */
        RESTORED_PREVIOUS,

        /** 没有上一套可恢复；未确认的新包已丢弃（回退内置）。 */
        NO_PREVIOUS,

        /** 重命名回滚也失败：上一套仍在 model_pack_previous，待下次启动/导入恢复。 */
        PENDING_RECOVERY,
    }

    internal class ImportException(
        message: String,
        val rollback: RollbackStatus,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    internal data class RollbackOutcome(
        val status: RollbackStatus,
        val message: String,
    )

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
        // Y42：坏 target + 有效 previous 的恢复必须发生在选择激活目录之前。
        recoverPrevious(context.filesDir)
        val user = userDir(context)
        if (validateDir(user) != null) return user to "user"
        val builtin = builtinDir(context)
        // Y77：APK 内置包换代后旧缓存必须失效；重装失败仍保留旧包可用（降级不静默删除）。
        if (builtinStale(context)) installBuiltin(context)
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

    /** 是否仍有等待首次初始化确认的上一套用户包（R16 CPU 兜底探针的门控）。 */
    internal fun hasPrevious(filesDir: File): Boolean =
        File(filesDir, "model_pack_previous").exists()

    /**
     * 判定语义指纹（Y68）：CPU 主检测模型 + **实际使用的 GPU 检测资产**（名字与 SHA）。
     *
     * 只看 yolo11n_int8.tflite 会让「同 CPU、仅 GPU 权重换代」的包沿用旧判定状态；
     * 反过来，同一模型组切换 auto/force/off 时清单不变，指纹也不变，判定状态必须保留。
     */
    internal fun semanticFingerprint(meta: Map<String, Any?>): String? {
        val files = meta["files"] as? Map<*, *> ?: return null
        val cpu = files["yolo11n_int8.tflite"] as? String ?: return null
        val gpuName = GPU_FILES.singleOrNull { it in files } ?: return null
        val gpu = files[gpuName] as? String ?: return null
        // 坐标单位改变同一权重的解释方式，属于判定语义；显式声明参与指纹。
        // 旧包缺字段保持原指纹，避免升级后仅因新增字段就重置既有判定状态。
        val coord = coordinateUnit(meta)
        return "cpu:$cpu|gpu:$gpuName:$gpu" + if (coord != null) "|coord:$coord" else ""
    }

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
        // Y66：坐标单位必须显式且可识别；未知值不能带着错误框进入叠框/安全区。
        // 缺字段只放行既有合法旧包（检测器按输出值域自适应），不把“没写”当成任意单位。
        if (meta.containsKey("coord")) {
            val coord = meta["coord"]
            if (coord !is String || coord !in COORD_UNITS) return null
        }
        val files = meta["files"] as? Map<*, *> ?: return null
        val gpu = GPU_FILES.singleOrNull { it in files } ?: return null
        if (files.keys != BASE_FILES + gpu) return null
        if (children.map { it.name }.toSet() != files.keys + META) return null
        for ((name, sha) in files) {
            if (sha !is String || !SHA256.matches(sha)) return null
            val fileName = name as String
            val file = File(dir, fileName)
            if (sha256Of(file) != sha) return null
            // R16/Y67：SHA 只能证明一致性；张量域不符的包必须在这里拒绝，不能让运行期才失败。
            // GPU 检测资产是**改了扩展名的 tflite**（yolo11n_fp32.tflite 等），不能只按
            // `.tflite` 后缀判断，否则暂存校验会漏掉真正参与 GPU 推理的权重。
            if (StanceTensorContract.isSupportedAsset(fileName)) {
                StanceTensorContract.validate(fileName, file)
            }
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

    private fun builtinStampFile(context: Context): File = File(context.filesDir, BUILTIN_STAMP)

    /** APK 内清单摘要 = 内置包版本标识（清单又含四个载荷的 SHA）。 */
    private fun builtinAssetDigest(context: Context): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        context.assets.open("model_pack/$META").use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                require(total <= MAX_JSON_BYTES) { "内置清单超过体积上限" }
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Diag.w(TAG, "读取内置模型清单失败：${e.message}")
        null
    }

    private fun builtinStale(context: Context): Boolean {
        val dir = builtinDir(context)
        if (validateDir(dir) == null) return true
        val current = builtinAssetDigest(context) ?: return false
        val stamp = builtinStampFile(context)
        return !stamp.isFile || stamp.readText().trim() != current
    }

    /**
     * Y77：内置目录以「APK 清单摘要」为版本判据，换版后经同卷暂存 + rename 原子替换，
     * 不再因为旧 schema/SHA 仍有效就永远沿用旧缓存。
     */
    @Synchronized
    fun installBuiltin(context: Context): Boolean {
        val filesDir = context.filesDir
        val dir = builtinDir(context)
        val digest = builtinAssetDigest(context) ?: return false
        val stamp = builtinStampFile(context)
        if (validateDir(dir) != null && stamp.isFile && stamp.readText().trim() == digest) return true
        // 上次中断留下的内置暂存/旧目录：best-effort 回收，不阻塞本次安装。
        filesDir.listFiles { f ->
            f.name.startsWith("model_pack_builtin_import_") || f.name.startsWith("model_pack_builtin_old_")
        }.orEmpty().forEach { removeFlatDir(it) }
        val staging = try {
            File.createTempFile("model_pack_builtin_import_", "", filesDir)
        } catch (e: Exception) {
            Diag.w(TAG, "内置模型暂存创建失败：${e.message}")
            return false
        }
        try {
            check(staging.delete() && staging.mkdir()) { "无法创建内置模型暂存目录" }
            for (name in BUILTIN_FILES) {
                context.assets.open("model_pack/$name").use { input ->
                    File(staging, name).outputStream().use { output ->
                        limited(input, fileLimit(name)).copyTo(output)
                    }
                }
            }
            check(validateDir(staging) != null) { "内置模型组校验失败" }
            check(!Files.isSymbolicLink(dir.toPath())) { "内置模型目录不可为链接" }
            val old = File(filesDir, "model_pack_builtin_old_${System.nanoTime()}")
            val hadOld = dir.exists()
            if (hadOld && !dir.renameTo(old)) throw IllegalStateException("无法保留旧内置模型目录")
            if (!staging.renameTo(dir)) {
                if (hadOld) check(old.renameTo(dir)) { "内置模型目录恢复失败" }
                throw IllegalStateException("内置模型组激活失败")
            }
            removeFlatDir(old)
            // 摘要最后落盘：写失败只会导致下次启动重装一遍，不会把旧目录当新版本。
            val tmpStamp = File(filesDir, "$BUILTIN_STAMP.tmp")
            runCatching {
                tmpStamp.writeText(digest)
                if (!tmpStamp.renameTo(stamp)) {
                    stamp.delete()
                    check(tmpStamp.renameTo(stamp)) { "内置模型版本戳写入失败" }
                }
            }.onFailure { Diag.w(TAG, "内置模型版本戳写入失败：${it.message}") }
            return validateDir(dir) != null
        } catch (e: Exception) {
            Diag.w(TAG, "内置模型组安装失败：${e.message}")
            return false
        } finally {
            removeFlatDir(staging)
        }
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
                // 包本身不合法 = 没有触碰任何旧模型：沿用旧调用方的 IllegalArgumentException，
                // 同时不让 Controller 把“包坏了”误报成“已回滚”。
                ?: throw IllegalArgumentException("模型组校验失败（清单/sha256/张量契约不符）")
            val target = File(filesDir, "model_pack_user")
            val previous = File(filesDir, "model_pack_previous")
            if (!Files.isSymbolicLink(target.toPath())) {
                // previous 存在 = 上一套已确认模型/或上次未确认的导入；先保住更老的好模型，
                // 丢弃当前未确认 target（它从未初始化成功，不能当回滚候选）。
                if (previous.exists()) {
                    if (target.exists() && !removeFlatDir(target)) {
                        throw ImportException("未确认的新模型目录无法清理，已取消导入", RollbackStatus.UNTOUCHED)
                    }
                } else if (target.exists() && validateDir(target) == null) {
                    // 坏 target 不能当 previous，否则会用它“恢复”出一个不可用模型。
                    if (!removeFlatDir(target)) {
                        throw ImportException("损坏的模型目录无法清理，已取消导入", RollbackStatus.UNTOUCHED)
                    }
                }
            } else {
                throw ImportException("模型激活目录不可为链接", RollbackStatus.UNTOUCHED)
            }
            var oldTargetMoved = false
            if (!previous.exists() && target.exists()) {
                if (!move(target, previous)) {
                    throw ImportException("无法保留原模型组，已取消导入", RollbackStatus.UNTOUCHED)
                }
                oldTargetMoved = true
            }
            if (!move(staging, target)) {
                // 回滚与激活是同一个事务：失败注入必须走同一 move，才能真实模拟“两次 rename 都失败”。
                val status = restorePrevious(target, previous, move)
                throw ImportException(
                    when (status) {
                        RollbackStatus.RESTORED_PREVIOUS -> "模型组激活失败，已恢复原模型组"
                        RollbackStatus.PENDING_RECOVERY -> "模型组激活失败，原模型组待恢复（仍在 model_pack_previous）"
                        else -> "模型组激活失败，原模型组未变"
                    },
                    status,
                )
            }
            // R16：previous 不在这里删除——必须等新模型首次 Interpreter 初始化成功，
            // 由 Controller 调 [confirmActivePack]。这样“导入成功但运行期不可用”仍能回滚。
            if (oldTargetMoved) Diag.i(TAG, "新模型已激活，上一套保留待首次初始化确认")
            return meta
        } finally {
            removeFlatDir(staging)
        }
    }

    /** 恢复 previous 到 target；只用于导入失败后的原地回滚与启动恢复。 */
    private fun restorePrevious(
        target: File,
        previous: File,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ): RollbackStatus {
        if (!previous.exists()) return RollbackStatus.UNTOUCHED
        if (target.exists() && !removeFlatDir(target)) return RollbackStatus.PENDING_RECOVERY
        return if (move(previous, target)) RollbackStatus.RESTORED_PREVIOUS
        else RollbackStatus.PENDING_RECOVERY
    }

    /**
     * 启动/导入前恢复：rename 之间进程退出或 target 损坏时，优先恢复最后一个有效包。
     * - target 有效：不动 previous（等待首次初始化确认，R16）；
     * - target 无效 + previous 有效：隔离坏 target，恢复 previous（Y42）；
     * - 两者都无效：清掉，避免 previous.exists() 永久卡住后续导入。
     */
    private fun recoverPrevious(filesDir: File) {
        val target = File(filesDir, "model_pack_user")
        val previous = File(filesDir, "model_pack_previous")
        if (!previous.exists()) return
        if (validateDir(target) != null) return
        if (validateDir(previous) != null) {
            if (target.exists()) check(removeFlatDir(target)) { "损坏的模型目录无法隔离" }
            check(previous.renameTo(target)) { "原模型组恢复失败" }
            Diag.i(TAG, "已从 model_pack_previous 恢复上一套有效模型")
            return
        }
        // 两套都坏：清理以便 activeDir 走内置、后续导入不再被 previous 阻塞。
        removeFlatDir(previous)
        if (target.exists()) removeFlatDir(target)
        Diag.w(TAG, "user/previous 模型组均无效，已清理并回退内置")
    }

    /** 新模型首次初始化成功后调用：此后才允许删除上一套。 */
    @Synchronized
    internal fun confirmActivePack(filesDir: File): Boolean {
        val previous = File(filesDir, "model_pack_previous")
        if (!previous.exists()) return true
        if (validateDir(File(filesDir, "model_pack_user")) == null) return false
        if (!removeFlatDir(previous)) {
            Diag.w(TAG, "上一套模型组清理失败，保留到下次确认")
            return false
        }
        return true
    }

    /**
     * 新模型初始化失败后的回滚（Y76）：结构化返回真实状态，调用方不得统一说“原模型组未变”。
     * 没有 previous 时丢弃未确认 target，让 activeDir 回退内置而不是永远加载坏包。
     */
    @Synchronized
    internal fun rollbackActivePack(
        filesDir: File,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ): RollbackOutcome {
        val target = File(filesDir, "model_pack_user")
        val previous = File(filesDir, "model_pack_previous")
        if (!previous.exists()) {
            if (target.exists() && !removeFlatDir(target)) {
                return RollbackOutcome(RollbackStatus.PENDING_RECOVERY, "未确认的新模型无法清理，请重启应用")
            }
            return RollbackOutcome(RollbackStatus.NO_PREVIOUS, "未确认的新模型已丢弃，回退内置模型组")
        }
        if (validateDir(previous) == null) {
            removeFlatDir(previous)
            if (target.exists()) removeFlatDir(target)
            return RollbackOutcome(RollbackStatus.NO_PREVIOUS, "上一套模型也无效，已回退内置模型组")
        }
        if (target.exists() && !removeFlatDir(target)) {
            return RollbackOutcome(RollbackStatus.PENDING_RECOVERY, "原模型组待恢复（请重启应用）")
        }
        if (!move(previous, target)) {
            return RollbackOutcome(RollbackStatus.PENDING_RECOVERY, "原模型组待恢复（仍在 model_pack_previous）")
        }
        return RollbackOutcome(RollbackStatus.RESTORED_PREVIOUS, "已恢复上一套模型组")
    }

    /** 只清本类创建的平铺目录或暂存文件；不递归跟随链接或未知子目录。 */
    internal fun removeFlatDir(dir: File, delete: (File) -> Boolean = { it.delete() }): Boolean {
        if (!dir.exists()) return true
        val parent = dir.parentFile ?: return false
        if (Files.isSymbolicLink(dir.toPath()) || dir.canonicalFile.parentFile != parent.canonicalFile) return false
        // createTempFile 成功、delete+mkdir 之间进程退出会留下同名普通文件。它不是目录树，
        // 不能走 listFiles()==null 的“未知目录”分支，否则后续每次导入都被 stale 清理拒绝。
        if (!dir.isDirectory) {
            val removed = delete(dir) || !dir.exists()
            if (!removed) Diag.w(TAG, "模型暂存文件清理失败，后续导入将先回收：${dir.name}")
            return removed
        }
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
