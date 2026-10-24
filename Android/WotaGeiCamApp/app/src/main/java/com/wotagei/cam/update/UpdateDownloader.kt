package com.wotagei.cam.update

import android.content.Context
import com.wotagei.cam.diag.Diag as Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 「检查更新」的**网络执行层**（update 包唯一允许出现 HttpURLConnection 的地方，守卫钉住）。
 * 零新依赖：HttpURLConnection 直用。
 *
 * 职责：
 * - [fetchJson] / [fetchRedirectLocation]：探测三段编排（[probeLatestRelease]）注入的网络真身；
 * - [downloadApk]：**测速选路** + 下载 fallback + 进度回调 + 落盘 `cacheDir/updates/`（app 私有，无需存储权限）。
 *
 * 请求目标的统一门槛（2026-10-21）：只允许 HTTPS，且主机名不得是 localhost / 环回 / 私有 /
 * 链路本地 / 保留地址。**重定向由本模块手写跟随**（[HttpURLConnection] 自动跟随不校验目标，
 * 恶意镜像可以用 302 把我们引到 `https://127.0.0.1:8443/` 或内网地址），每一跳都重新过门槛。
 *
 * 每个下载渠道的防线（失败自动切下一渠道）：
 * 1. 连接超时 10s / 读超时 30s——**停滞的兜底就是 30s 读超时**（修复轮 P2-1：应用层「8s 无新字节」
 *    检查在 read() 阻塞语义下循环顶永不可达，属死代码，已删除；诚实记录，不装作有 8s 停滞检测）；
 * 2. Content-Length 与实际字节数一致性校验（探测段给了 size 时）；
 * 3. 落盘后首字节 `PK` 魔数校验（防镜像塞回 HTML 错误页当 APK）；
 * 4. 响应体上限：探测 JSON 帽 [MAX_PROBE_BYTES]（头判据 + 限流读取双保险）、APK 磁盘帽
 *    [MAX_APK_BYTES]（头判据 + 读循环累计）——镜像不可信，内存与磁盘两侧都不许无界。
 * 5. SHA-256 端到端校验（v0.0.8）：探测段 1 从 GitHub 官方端点的 Release 说明提取发版者写入的
 *    universal 包哈希，落盘后本地重算比对——不匹配即镜像污染实锤，删包判失败。镜像改不走
 *    官方端点的说明文本，这是「镜像污染最坏后果=更新失败」的核心保证（见 UpdateConfig 安全模型）。
 *
 * 探测（[fetchJson] / [fetchRedirectLocation]）用 [PROBE_READ_TIMEOUT_MS] 短读超时：
 * 「检查中…」不可挂数分钟，配合取消探针（检查中点行 = 取消）最坏两枚镜像 ×20s 内返回。
 *
 * HTTP 明文镜像（用户点名的 toolwa.com/github）不在链里：中间人可替换 APK，见 UpdateConfig。
 */
object UpdateDownloader {

    private const val TAG = "WotaUpdate"

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 30_000

    /** 探测专用短读超时（修复轮 P2-2）：「检查中…」必须可取消、不可挂数分钟 */
    const val PROBE_READ_TIMEOUT_MS = 10_000

    // ------------------------------------------------------------------ 下载渠道测速（2026-10-21）

    /**
     * 每渠道测速最多读的字节数（绝对字面量，单测钉死）：256KiB 足够把「连接 + 首字节」的
     * 固定开销摊薄，又不至于把测速本身变成一次完整下载（全量 5.4MB）。
     */
    const val SPEED_TEST_BYTES = 256 * 1024

    /** 测速的连接超时：测速不是下载，连不上就认输，别陪它等满 10s */
    const val SPEED_TEST_CONNECT_TIMEOUT_MS = 4_000

    /** 测速的读超时：单次 read 阻塞上限 */
    const val SPEED_TEST_READ_TIMEOUT_MS = 3_000

    /** 测速单渠道的整段预算（含重定向）：各渠道并发跑，整体等待 ≈ 这个值 */
    const val SPEED_TEST_BUDGET_MS = 6_000

    /** 测速读缓冲（小一点，取消与预算检查才落得及时） */
    private const val SPEED_TEST_BUFFER = 16 * 1024

    /** 重定向最多跟几跳（手写跟随，每跳都重过安全门槛） */
    private const val MAX_REDIRECTS = 5

    /**
     * 探测响应体的宽上限（2026-10-07）：GitHub releases/latest JSON 实际几十 KB，这里放宽到 4MiB。
     * 探测链上有 4 枚第三方镜像前缀（不可信，PK 魔数就是为它们设的）——无帽读响应体，
     * 镜像回数 GB 的"JSON"就是 OOM。超帽按该节点失败处理，走既有 fallback 切下一节点。
     */
    const val MAX_PROBE_BYTES = 4L * 1024 * 1024

    /**
     * APK 下载的磁盘侧上限（2026-10-07）：无 Content-Length 且探测未给 size 时，读循环对
     * 响应体长度一无所知，同一批不可信镜像可以无限流写盘。2GiB 远超真实 APK 体量，
     * 超帽按该节点失败处理（.part 由下轮重写覆盖、终途 delete 清理）。
     */
    const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024

    private const val BUFFER_SIZE = 64 * 1024

    // ------------------------------------------------------------------ 响应体上限（纯判定，JVM 全测）

    /**
     * Content-Length 头判据（纯函数）：**未知长度（<=0）放行**、交给限流读取兜住；
     * 显式声明超帽即拒。上限值本身必须钉死具体字面量单测（突变 Long.MAX_VALUE 必红）。
     */
    internal fun probeBytesAllowed(contentLength: Long): Boolean =
        contentLength <= 0 || contentLength <= MAX_PROBE_BYTES

    /** 限流读取的越帽判定（纯函数）：累计已读是否超出 [limit]，读取方据此断开按节点失败处理 */
    internal fun bytesOverLimit(totalRead: Long, limit: Long): Boolean = totalRead > limit

    // ------------------------------------------------------------------ 测速排序（纯函数，JVM 全测）

    /** 单个渠道的测速结论：url + 实测字节/秒（0 = 连不上 / 非 APK 响应 / 超预算） */
    internal data class ChannelSpeed(val url: String, val bytesPerSec: Long)

    /**
     * 下载候选（直连 + 各镜像前缀）：顺序 = **测速前的兜底顺序**（直连优先、镜像按配置表序）。
     * 纯函数——「直连一定在第一位」「每个镜像恰好出现一次」都能单测钉死。
     */
    internal fun downloadCandidates(directUrl: String): List<String> =
        listOf(directUrl) + UpdateConfig.MIRROR_PREFIXES.map { it + directUrl }

    /**
     * 测速排序（纯函数，JVM 全测）：实测速度降序；速度 <= 0 的渠道**垫底但不丢**（保持输入顺序）。
     * 全部测速失败时输出与输入完全一致——等价于回到「直连优先 + 镜像表序」的旧 fallback，
     * 绝不会出现「测速失败就一个渠道都不试」。同速保持输入顺序（sortedByDescending 是稳定排序）。
     */
    internal fun rankChannelsBySpeed(speeds: List<ChannelSpeed>): List<String> {
        val fast = speeds.filter { it.bytesPerSec > 0 }.sortedByDescending { it.bytesPerSec }
        val rest = speeds.filter { it.bytesPerSec <= 0 }
        return (fast + rest).map { it.url }
    }

    // ------------------------------------------------------------------ 探测真身（注入 probeLatestRelease）

    /**
     * GET JSON 正文；任何失败（连不上/非 2xx/超时/超帽）返回 null——编排靠 null 快速跳过下一节点。
     * 探测调用方传 [PROBE_READ_TIMEOUT_MS] 短读超时（P2-2），下载目录之外不共用 30s。
     */
    fun fetchJson(url: String, readTimeoutMs: Int = READ_TIMEOUT_MS): String? {
        // Accept 必须在连接建立前挂上：openFollowing 内部会先读 responseCode 判重定向，
        // 请求那时候就发出去了，之后再 setRequestProperty 不生效
        val conn = openFollowing(
            url, readTimeoutMs, accept = "application/vnd.github+json"
        ) ?: return null
        try {
            if (conn.responseCode !in 200..299) return null
            // 响应体必须有帽：显式超帽直接拒；无 Content-Length 时靠限流读取兜（镜像不可信）
            if (!probeBytesAllowed(conn.contentLengthLong)) return null
            val body = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        total += n
                        if (bytesOverLimit(total, MAX_PROBE_BYTES)) return null
                        body.write(buf, 0, n)
                    }
                }
            }
            return body.toString("UTF-8")
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** GET 不跟随重定向，返回 3xx 的 Location 头；失败返回 null（段 3 专用，读超时同上） */
    fun fetchRedirectLocation(url: String, readTimeoutMs: Int = READ_TIMEOUT_MS): String? {
        val conn = openRaw(url, readTimeoutMs) ?: return null
        try {
            if (conn.responseCode !in 300..399) return null
            return conn.getHeaderField("Location") ?: return null
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 下载

    /** 下载结论：UI 只消费这个 */
    sealed interface DownloadOutcome {
        /** 已落盘并校验通过（file 在 cacheDir/updates/ 下，可直接交 FileProvider） */
        data class Done(val file: File, val sizeBytes: Long) : DownloadOutcome

        /** 用户取消（.part 已清理） */
        object Cancelled : DownloadOutcome

        /** 链上所有节点都失败（.part 已清理） */
        object Failed : DownloadOutcome
    }

    /**
     * 下载 APK。落盘前写 `<名字>.part`，全部校验通过后改名——中断/失败不会留下半截文件
     * 被当成完整 APK 交安装器。
     *
     * **测速选路（2026-10-21 用户裁决）**：每次下载前先给「直连 + 各镜像」并发测速
     * （各读最多 [SPEED_TEST_BYTES]、整段预算 [SPEED_TEST_BUDGET_MS]），按实测速度降序下载；
     * 测速全失败时保持候选原序（直连优先 + 镜像表序），与旧版 fallback 完全一致。
     *
     * @param directUrl 直连下载 URL（探测结果携带；各渠道各自做前缀变换）
     * @param expectedSize 探测段 1/2 给的资产大小（段 3 为 null → 只信响应 Content-Length）
     * @param onProgress 进度回调（已下字节, 总字节；总未知时为 -1，UI 退化为只显示已下 MB）
     * @param isCancelled 取消探针（取消按钮置位），每次读循环检查
     * @param expectedSha256 段 1 说明里的哈希（null = 无可信哈希，退回 PK+size+签名三层防线）
     * @param onSpeedTested 测速排序完成、开始真正下载时回调（UI 把「正在选路」切成进度态）
     */
    fun downloadApk(
        context: Context,
        directUrl: String,
        expectedSize: Long?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
        expectedSha256: String? = null,
        onSpeedTested: () -> Unit = {}
    ): DownloadOutcome {
        val dir = File(context.applicationContext.cacheDir, "updates")
        dir.mkdirs()
        val assetName = directUrl.substringAfterLast('/')
        // 修复轮 P3-3：只清理 .part 半截文件与非本轮资产的文件——本轮 asset 的已完成 APK 可能
        // 还被系统安装器读着（用户关了对话框去安装器），不能一把梭全删
        val names = dir.listFiles()?.map { it.name } ?: emptyList()
        val stale = staleFilesOf(names, assetName).toSet()
        dir.listFiles()?.filter { it.name in stale }?.forEach { it.delete() }
        val target = File(dir, assetName)
        // 修复轮 P2：part 文件按代唯一（.part<nanoTime>）——上一代已取消协程在 read 返回后仍可能
        // 落最后一块（≤64KB）再自检到取消，它的 chunk 写入与 part.delete() 都只落在自己的 part 上，
        // 绝不与新一代下载交错写同一文件；旧代残留由下轮 staleFilesOf 清掉
        val part = File(dir, "$assetName.part${System.nanoTime()}")

        // 测速选路：并发测完再下（测速失败/超预算的渠道垫底仍保留，见 rankChannelsBySpeed）
        val ordered = measureAndRank(downloadCandidates(directUrl), isCancelled)
        if (isCancelled()) {
            part.delete()
            return DownloadOutcome.Cancelled
        }
        onSpeedTested()
        for (url in ordered) {
            if (isCancelled()) {
                part.delete()
                return DownloadOutcome.Cancelled
            }
            when (val r = tryNode(url, part, expectedSize, onProgress, isCancelled)) {
                is DownloadOutcome.Done -> {
                    // 重下同一资产时旧包还在：Windows 语义下 renameTo 对已存在目标会失败，必须先删旧再改名
                    //（此刻删的是刚下完的新包要顶掉的旧包，与 P3-3 保留旧包给安装器读不冲突——重下即用户已弃旧包）
                    val renamed = (if (target.exists()) target.delete() else true) && part.renameTo(target)
                    if (renamed) {
                        // SHA-256 端到端校验（v0.0.8）：哈希来自段 1 官方端点的 Release 说明（发版者写入）。
                        // 探测没给（段 2/3、旧版 notes）→ 跳过校验（不为 null 才读盘，省一次全量 IO），
                        // 退回既有防线（PK+size+安装签名网）；给了且不匹配 → 镜像污染实锤，删包判失败。
                        // 校验本身是读盘：IO 异常必须折算成失败——下载协程没有 CoroutineExceptionHandler，
                        // 抛出去就是未捕获异常杀进程（与 tryNode「任何异常都折算成失败」同纪律）。
                        val verified = runCatching {
                            expectedSha256 == null ||
                                sha256Matches(expectedSha256, sha256Hex(target))
                        }.getOrElse { e ->
                            Log.e(TAG, "SHA-256 校验读盘失败（${e.javaClass.simpleName}），判失败", e)
                            false
                        }
                        if (verified) {
                            return DownloadOutcome.Done(target, r.sizeBytes)
                        }
                        Log.e(TAG, "SHA-256 校验不匹配（期望=$expectedSha256 实际≠），判镜像污染，删包")
                        target.delete()
                        return DownloadOutcome.Failed
                    } else {
                        part.delete()
                        return DownloadOutcome.Failed
                    }
                }
                is DownloadOutcome.Cancelled -> {
                    part.delete()
                    return DownloadOutcome.Cancelled
                }
                // 该渠道失败：试下一个（顺序已按测速从快到慢）
                is DownloadOutcome.Failed -> Unit
            }
        }
        part.delete()
        return DownloadOutcome.Failed
    }

    // ------------------------------------------------------------------ 测速真身（并发）

    /**
     * 各渠道并发测速并按速度排序；返回的列表已是下载顺序。
     * 线程池按渠道数开（上限 6）、daemon 线程，`finally` 里 shutdownNow——取消/异常都不留线程。
     */
    private fun measureAndRank(candidates: List<String>, isCancelled: () -> Boolean): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(candidates.size, 6)) { r ->
            Thread(r, "wota-update-speed").apply { isDaemon = true }
        }
        return try {
            val futures = candidates.map { url ->
                url to pool.submit(Callable { measureOne(url, isCancelled) })
            }
            // 等结果同样受整段预算约束：[measureOne] 的 deadline 只在「开连接前」与「读循环顶」
            // 检查，单次 connect / read 阻塞与 DNS 解析都不受它打断——这里必须自带超时，
            // 否则「6 秒预算」在最坏情况下不成立，用户点取消也要干等一个挂起的渠道。
            val deadlineNanos = System.nanoTime() + SPEED_TEST_BUDGET_MS * 1_000_000L
            rankChannelsBySpeed(
                futures.map { (url, f) ->
                    val leftMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                    val bps = try {
                        if (leftMs > 0) f.get(leftMs, TimeUnit.MILLISECONDS) else 0L
                    } catch (e: Exception) {
                        // 超时 / 取消 / 执行失败：该渠道记 0 垫底（仍在表里，仍会被尝试）
                        0L
                    }
                    ChannelSpeed(url, bps)
                }
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * 单渠道测速：读到 [SPEED_TEST_BYTES] / 预算耗尽 / 流结束为止，返回字节每秒。
     * 以下一律记 0（排序垫底但仍会尝试）：连不上、非 2xx、首块不是 APK 魔数（HTML 落地页）、没读到数据。
     * 计时从**发请求之前**算起，连接与首字节的延迟也计入——真实下载同样要付这笔钱。
     */
    private fun measureOne(url: String, isCancelled: () -> Boolean): Long {
        if (isCancelled()) return 0
        val started = System.nanoTime()
        val deadline = started + SPEED_TEST_BUDGET_MS * 1_000_000L
        val conn = openFollowing(
            url,
            SPEED_TEST_READ_TIMEOUT_MS,
            SPEED_TEST_CONNECT_TIMEOUT_MS,
            deadline
        ) ?: return 0
        try {
            if (conn.responseCode !in 200..299) return 0
            var read = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(SPEED_TEST_BUFFER)
                while (read < SPEED_TEST_BYTES) {
                    if (isCancelled() || System.nanoTime() > deadline) break
                    val want = minOf(buf.size.toLong(), SPEED_TEST_BYTES - read).toInt()
                    val n = input.read(buf, 0, want)
                    if (n <= 0) break
                    // 首块必须是 APK 的 PK 魔数：镜像回 HTML 落地页（ghproxy.link 2026-10-21 实测形态）不算快渠道
                    if (read == 0L && !(n >= 2 && buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte())) {
                        return 0
                    }
                    read += n
                }
            }
            if (read <= 0) return 0
            val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            return read * 1000L / elapsedMs
        } catch (e: Exception) {
            return 0
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载目录的过期文件判据（纯函数，JVM 全测——修复轮 P3-3 的桥函数）：
     * 删 `.part` 半截文件与非本轮资产名的文件；**保留本轮 asset 的已完成 APK**
     * （安装器可能还在读它）。下载失败时不删任何已完成包。
     */
    internal fun staleFilesOf(dirNames: List<String>, assetName: String): List<String> =
        dirNames.filter { it.endsWith(".part") || it != assetName }

    // ------------------------------------------------------------------ SHA-256 端到端校验（v0.0.8）

    /**
     * 校验判据（纯函数，JVM 全测）：[expectedSha256] 为 null（探测没给可信哈希：段 2/3 或旧版
     * notes）恒放行——退回既有防线（PK 魔数 + size 一致性 + 安装签名网）；给了则必须与实际哈希
     * **忽略大小写**相等（说明文本里是大写 hex 也合法，见 ReleaseJson.parseSha256FromNotes 的
     * 小写化约定，这里再容一道大写输入）。
     */
    internal fun sha256Matches(expectedSha256: String?, actualHex: String): Boolean {
        expectedSha256 ?: return true
        return expectedSha256.equals(actualHex, ignoreCase = true)
    }

    /**
     * 文件的 SHA-256（流式 64KB，全量读一遍；纯 JVM——java.security.MessageDigest 是 JDK 面）。
     * 只在校验分支调用（探测给了哈希时一次 ~5MB 的读盘开销，毫秒级），不碰正常路径。
     */
    internal fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 单节点尝试：成功落 part / 取消 / 失败三态，任何异常都折算成失败（换下一个节点） */
    private fun tryNode(
        url: String,
        part: File,
        expectedSize: Long?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ): DownloadOutcome {
        val conn = openFollowing(url, READ_TIMEOUT_MS) ?: return DownloadOutcome.Failed
        try {
            if (isCancelled()) return DownloadOutcome.Cancelled
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return DownloadOutcome.Failed
            val declared = conn.contentLengthLong.takeIf { it > 0 }
            // 探测给的 size 与响应头对不上：源不对劲，换节点
            if (expectedSize != null && declared != null && declared != expectedSize) {
                return DownloadOutcome.Failed
            }
            // 磁盘侧帽也先查头：显式声明超限就不必开流写盘
            if (declared != null && bytesOverLimit(declared, MAX_APK_BYTES)) {
                return DownloadOutcome.Failed
            }
            val total = declared ?: expectedSize ?: -1L
            var read = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) return DownloadOutcome.Cancelled
                        // 停滞兜底 = 30s 读超时（P2-1：应用层 8s 检查在 read 阻塞语义下不可达，已删）
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            read += n
                            // 无 Content-Length 且探测未给 size 时响应体长度未知：磁盘侧同样要帽
                            //（超帽按该节点失败处理，半截 .part 由下轮覆盖/终途 delete 清理）
                            if (bytesOverLimit(read, MAX_APK_BYTES)) return DownloadOutcome.Failed
                            out.write(buf, 0, n)
                            onProgress(read, total)
                        }
                    }
                }
            }
            if (total > 0 && read != total) return DownloadOutcome.Failed
            if (!isApkMagic(part)) return DownloadOutcome.Failed
            return DownloadOutcome.Done(part, read)
        } catch (e: IOException) {
            part.delete()
            return DownloadOutcome.Failed
        } catch (e: Exception) {
            part.delete()
            return DownloadOutcome.Failed
        } finally {
            conn.disconnect()
        }
    }

    /** APK = ZIP，首两字节 `PK`。防镜像把 HTML 错误页/登录页当文件体吐回来 */
    private fun isApkMagic(file: File): Boolean {
        if (file.length() < 2) return false
        file.inputStream().use { ins ->
            val magic = ByteArray(2)
            if (ins.read(magic) != 2) return false
            return magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        }
    }

    /**
     * 只允许 HTTPS（纯函数，JVM 全测）。targetSdk 34 的明文默认禁网是平台行为，不是本模块的
     * 契约；这里把"下载/探测永不走明文"写成自己可验证的判据，镜像前缀或未来改表都不会悄悄降级。
     */
    internal fun isHttpsUrl(url: String): Boolean =
        runCatching { URL(url).protocol.equals("https", ignoreCase = true) }.getOrDefault(false)

    /**
     * 请求目标的安全判据（纯函数，JVM 全测）：只允许 https、必须有主机名，且拒绝 localhost /
     * 环回 / 私有 / 链路本地 / 保留地址的**字面量 IP**。镜像返回的 302 目标也走这条——本模块
     * 手写重定向的本意就是不让代理把请求引到内网。域名不做 DNS 预解析（那本身是一次网络请求，
     * 还有 DNS rebinding 的坑），真正的门是 HTTPS 证书校验。
     */
    internal fun isSafeRemoteUrl(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (!parsed.protocol.equals("https", ignoreCase = true)) return false
        val host = parsed.host ?: return false
        return isPublicHost(host)
    }

    /** 主机名判据（纯函数，JVM 全测）：localhost / 环回 / 私有 / 链路本地 / 保留地址 → false */
    internal fun isPublicHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return false
        return if (h.startsWith("[") || h.contains(':')) isPublicIpv6(h) else isPublicIpv4OrDomain(h)
    }

    /**
     * 「像 IP 字面量又不是规范点分十进制」的主机判据（纯函数，JVM 全测）。
     *
     * 只认两种标签：十进制数字与 `0x` 十六进制。域名分支必须**不**被这类主机蹭到——
     * 平台的 `inet_aton` 语义会把 `127.1`、`2130706433`、`0177.0.0.1`、`0x7f.0.0.1`
     * 当 IP 解析（分别落 127.0.0.1 / 127.0.0.1 / 127.0.0.1 / 127.0.0.1），
     * 而「不是字面量就放行」的旧判据正是把它们漏进内网的那道缝。
     */
    private val NUMERIC_LABEL = Regex("[0-9]+|0[xX][0-9a-fA-F]+")

    private fun isNumericLiteralHost(h: String): Boolean =
        h.isNotEmpty() && h.split('.').all { NUMERIC_LABEL.matches(it) }

    /**
     * 规范点分十进制：恰好 4 段、每段 1~3 位、无前导零（`0` 自身除外）、值 ≤255。
     * 任何「像 IP 又不符合」的形态（段数不对、超范围、前导零、十六进制）返回 null。
     */
    private fun parseIpv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val s = parts[i]
            if (s.length !in 1..3 || !s.all { it.isDigit() }) return null
            // 前导零（007、010）在 inet_aton 下是八进制，禁掉——宁可拒也不猜平台语义
            if (s.length > 1 && s[0] == '0') return null
            val v = s.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }

    /** IPv4 私网/环回/保留判据（前两段即可定，第三段留给 192.0.2.0/24 与 198.51.100/24） */
    private fun isBlockedIpv4(a: Int, b: Int, c: Int): Boolean =
        a == 0 || a == 10 || a == 127 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 192 && b == 0) ||
            (a == 198 && (b == 18 || b == 19)) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113) ||
            a >= 224

    /**
     * 主机判据的 IPv4 半边：**域名**（含字母的标签）一律放行（不做 DNS 预解析）；
     * 数字/十六进制标签的主机按 IP 字面量要求——不是规范点分十进制就拒，不是判完私网再放行。
     * 私网/环回/保留判据见 [isBlockedIpv4]（0/8、10/8、100.64/10、127/8、169.254/16、
     * 172.16/12、192.168/16、192.0.0.0/16、198.18/15、198.51.100/24、203.0.113/24、224/4 与 240/4）。
     */
    private fun isPublicIpv4OrDomain(h: String): Boolean {
        if (isNumericLiteralHost(h)) {
            val o = parseIpv4(h) ?: return false
            return !isBlockedIpv4(o[0], o[1], o[2])
        }
        return true
    }

    /**
     * IPv6 字面量：先严格解析成 16 字节（[parseIpv6]），**解析不了一律拒**（fail-closed），
     * 再按地址判据：未指定、环回、fc00::/7（ULA）、fe80::/10（链路本地）、ff00::/8（组播），
     * 以及 ::ffff:0:0/96（IPv4 映射）、::/96（IPv4 兼容，含 ::a9fe:a9fe 这类云元数据地址）、
     * 64:ff9b::/96（NAT64）——后三种解出内嵌 IPv4 后按 [isBlockedIpv4] 判。
     * 旧的「startsWith("fc") / ::ffff: 只认点分十进制」实现漏掉了十六进制映射形式
     * （`[::ffff:c0a8:1]`、`[::ffff:7f00:1]`、`[0:0:0:0:0:ffff:127.0.0.1]` 全部放行）。
     */
    private fun isPublicIpv6(host: String): Boolean {
        val bytes = parseIpv6(host) ?: return false
        fun zero(from: Int, to: Int) = (from until to).all { bytes[it] == 0.toByte() }
        fun v4At(from: Int) = intArrayOf(
            bytes[from].toInt() and 0xff, bytes[from + 1].toInt() and 0xff,
            bytes[from + 2].toInt() and 0xff, bytes[from + 3].toInt() and 0xff
        )
        // ::/128 未指定、::1/128 环回
        if (zero(0, 16)) return false
        if (zero(0, 15) && bytes[15] == 1.toByte()) return false
        // fc00::/7 ULA、fe80::/10 链路本地、ff00::/8 组播
        if ((bytes[0].toInt() and 0xfe) == 0xfc) return false
        if (bytes[0] == 0xfe.toByte() && (bytes[1].toInt() and 0xc0) == 0x80) return false
        if (bytes[0] == 0xff.toByte()) return false
        // 2001:db8::/32（文档专用）、100::/64（discard-only）
        if (bytes[0] == 0x20.toByte() && bytes[1] == 0x01.toByte() &&
            bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()
        ) return false
        if (bytes[0] == 0x01.toByte() && zero(1, 8)) return false
        // ::ffff:0:0/96 IPv4 映射
        if (zero(0, 10) && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) {
            val o = v4At(12)
            return !isBlockedIpv4(o[0], o[1], o[2])
        }
        // ::/96 IPv4 兼容（::a9fe:a9fe = 169.254.169.254）
        if (zero(0, 12)) {
            val o = v4At(12)
            return !isBlockedIpv4(o[0], o[1], o[2])
        }
        // 64:ff9b::/96 NAT64（内嵌 IPv4 同样可能是环回/链路本地）
        if (bytes[0] == 0x00.toByte() && bytes[1] == 0x64.toByte() &&
            bytes[2] == 0xff.toByte() && bytes[3] == 0x9b.toByte() && zero(4, 12)
        ) {
            val o = v4At(12)
            return !isBlockedIpv4(o[0], o[1], o[2])
        }
        return true
    }

    /**
     * IPv6 字面量 → 16 字节（纯函数，JVM 全测，零网络调用）。不认 zone id（`%eth0`）、
     * 多处 `::`、非法十六进制组；尾组可以是点分 IPv4（`::ffff:1.2.3.4` 形态）。
     */
    private fun parseIpv6(text: String): ByteArray? {
        var s = text.removePrefix("[").removeSuffix("]").lowercase()
        if (s.isEmpty() || s.contains('%')) return null
        // 尾组点分 IPv4 → 换成两个十六进制组（::ffff:1.2.3.4 → ::ffff:102:304）。
        // 必须按十六进制写：下面 group() 用 toInt(16) 解析，写十进制会被当成 16 进制读错
        val lastColon = s.lastIndexOf(':')
        if (s.contains('.')) {
            if (lastColon < 0) return null
            val o = parseIpv4(s.substring(lastColon + 1)) ?: return null
            s = s.substring(0, lastColon + 1) +
                "${(o[0] shl 8 or o[1]).toString(16)}:${(o[2] shl 8 or o[3]).toString(16)}"
        }
        val halves = s.split("::")
        if (halves.size > 2) return null
        val head = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
        val tail = if (halves.size == 2 && halves[1].isNotEmpty()) halves[1].split(':') else emptyList()
        if (halves.size == 1 && head.size != 8) return null
        if (halves.size == 2 && head.size + tail.size > 7) return null
        val groups = ArrayList<Int>(8)
        fun group(g: String): Int? =
            if (g.length in 1..4 && g.all { it in "0123456789abcdef" }) g.toInt(16) else null

        for (g in head) groups.add(group(g) ?: return null)
        if (halves.size == 2) repeat(8 - head.size - tail.size) { groups.add(0) }
        for (g in tail) groups.add(group(g) ?: return null)
        if (groups.size != 8) return null
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i * 2] = (groups[i] ushr 8).toByte()
            out[i * 2 + 1] = (groups[i] and 0xff).toByte()
        }
        return out
    }

    /** 原始连接（**不跟随重定向**）；URL 不过 [isSafeRemoteUrl] 直接 null */
    private fun openRaw(
        url: String,
        readTimeoutMs: Int,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        accept: String? = null
    ): HttpURLConnection? {
        if (!isSafeRemoteUrl(url)) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            // 重定向由 openFollowing 手写跟随：平台默认跟随**不校验目标主机**，
            // 恶意镜像能用 302 把请求引到 https://127.0.0.1:8443/ 或内网地址
            c.instanceFollowRedirects = false
            // GitHub API 硬要求带 User-Agent；顺带让镜像日志能认出我们
            c.setRequestProperty("User-Agent", "WotaGeiCam-UpdateCheck")
            if (accept != null) c.setRequestProperty("Accept", accept)
            c
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 跟随重定向的连接：最多 [MAX_REDIRECTS] 跳，**每一跳都重新过** [isSafeRemoteUrl]
     * （`https → http` 降级、跳内网/环回一律中止）。[deadlineNanos] 非 null 时每跳前查预算。
     * 返回的连接已停在非 3xx 响应上（2xx 或 4xx/5xx 由调用方判）；任何失败返回 null。
     */
    private fun openFollowing(
        url: String,
        readTimeoutMs: Int,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        deadlineNanos: Long? = null,
        accept: String? = null
    ): HttpURLConnection? {
        var current = url
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            if (deadlineNanos != null && System.nanoTime() > deadlineNanos) return null
            val conn = openRaw(current, readTimeoutMs, connectTimeoutMs, accept) ?: return null
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                return null
            }
            if (code !in 300..399) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrBlank()) return null
            current = try {
                URL(URL(current), location).toString()
            } catch (e: Exception) {
                return null
            }
            hops++
        }
        return null
    }
}
