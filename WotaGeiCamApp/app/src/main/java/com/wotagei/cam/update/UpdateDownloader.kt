package com.wotagei.cam.update

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「检查更新」的**网络执行层**（update 包唯一允许出现 HttpURLConnection 的地方，守卫钉住）。
 * 零新依赖：HttpURLConnection 直用。
 *
 * 职责：
 * - [fetchJson] / [fetchRedirectLocation]：探测三段编排（[probeLatestRelease]）注入的网络真身；
 * - [downloadApk]：下载 fallback 链 + 进度回调 + 落盘 `cacheDir/updates/`（app 私有，无需存储权限）。
 *
 * 每个下载节点的防线（失败自动切下一个节点）：
 * 1. 连接超时 10s / 读超时 30s——**停滞的兜底就是 30s 读超时**（修复轮 P2-1：应用层「8s 无新字节」
 *    检查在 read() 阻塞语义下循环顶永不可达，属死代码，已删除；诚实记录，不装作有 8s 停滞检测）；
 * 2. Content-Length 与实际字节数一致性校验（探测段给了 size 时）；
 * 3. 落盘后首字节 `PK` 魔数校验（防镜像塞回 HTML 错误页当 APK）；
 * 4. 响应体上限：探测 JSON 帽 [MAX_PROBE_BYTES]（头判据 + 限流读取双保险）、APK 磁盘帽
 *    [MAX_APK_BYTES]（头判据 + 读循环累计）——镜像不可信，内存与磁盘两侧都不许无界。
 *
 * 探测（[fetchJson] / [fetchRedirectLocation]）用 [PROBE_READ_TIMEOUT_MS] 短读超时：
 * 「检查中…」不可挂数分钟，配合取消探针（检查中点行 = 取消）最坏 5×20s 内返回。
 *
 * HTTP 明文镜像（用户点名的 toolwa.com/github）不在链里：中间人可替换 APK，见 UpdateConfig。
 */
object UpdateDownloader {

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 30_000

    /** 探测专用短读超时（修复轮 P2-2）：「检查中…」必须可取消、不可挂数分钟 */
    const val PROBE_READ_TIMEOUT_MS = 10_000

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

    // ------------------------------------------------------------------ 探测真身（注入 probeLatestRelease）

    /**
     * GET JSON 正文；任何失败（连不上/非 2xx/超时/超帽）返回 null——编排靠 null 快速跳过下一节点。
     * 探测调用方传 [PROBE_READ_TIMEOUT_MS] 短读超时（P2-2），下载目录之外不共用 30s。
     */
    fun fetchJson(url: String, readTimeoutMs: Int = READ_TIMEOUT_MS): String? {
        val conn = openConnection(url, followRedirects = true, readTimeoutMs = readTimeoutMs) ?: return null
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
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
        val conn = openConnection(url, followRedirects = false, readTimeoutMs = readTimeoutMs) ?: return null
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
     * 被当成完整 APK 交安装器。链上节点顺序由 [MirrorChain.downloadChain] 决定（直连优先）。
     *
     * @param directUrl 直连下载 URL（探测结果携带；链上节点各自做前缀变换）
     * @param expectedSize 探测段 1/2 给的资产大小（段 3 为 null → 只信响应 Content-Length）
     * @param onProgress 进度回调（已下字节, 总字节；总未知时为 -1，UI 退化为只显示已下 MB）
     * @param isCancelled 取消探针（取消按钮置位），每次读循环检查
     */
    fun downloadApk(
        context: Context,
        directUrl: String,
        expectedSize: Long?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
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

        val chain = MirrorChain.downloadChain()
        while (!chain.exhausted) {
            if (isCancelled()) {
                part.delete()
                return DownloadOutcome.Cancelled
            }
            when (val r = tryNode(chain.urlFor(directUrl), part, expectedSize, onProgress, isCancelled)) {
                is DownloadOutcome.Done ->
                    // 重下同一资产时旧包还在：Windows 语义下 renameTo 对已存在目标会失败，必须先删旧再改名
                    //（此刻删的是刚下完的新包要顶掉的旧包，与 P3-3 保留旧包给安装器读不冲突——重下即用户已弃旧包）
                    if ((if (target.exists()) target.delete() else true) && part.renameTo(target)) {
                        return DownloadOutcome.Done(target, r.sizeBytes)
                    } else {
                        part.delete()
                        return DownloadOutcome.Failed
                    }
                is DownloadOutcome.Cancelled -> {
                    part.delete()
                    return DownloadOutcome.Cancelled
                }
                is DownloadOutcome.Failed -> chain.advance()
            }
        }
        part.delete()
        return DownloadOutcome.Failed
    }

    /**
     * 下载目录的过期文件判据（纯函数，JVM 全测——修复轮 P3-3 的桥函数）：
     * 删 `.part` 半截文件与非本轮资产名的文件；**保留本轮 asset 的已完成 APK**
     * （安装器可能还在读它）。下载失败时不删任何已完成包。
     */
    internal fun staleFilesOf(dirNames: List<String>, assetName: String): List<String> =
        dirNames.filter { it.endsWith(".part") || it != assetName }

    /** 单节点尝试：成功落 part / 取消 / 失败三态，任何异常都折算成失败（换下一个节点） */
    private fun tryNode(
        url: String,
        part: File,
        expectedSize: Long?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean
    ): DownloadOutcome {
        val conn = openConnection(url, followRedirects = true, readTimeoutMs = READ_TIMEOUT_MS) ?: return DownloadOutcome.Failed
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

    private fun openConnection(url: String, followRedirects: Boolean, readTimeoutMs: Int): HttpURLConnection? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = followRedirects
        // GitHub API 硬要求带 User-Agent；顺带让镜像日志能认出我们
        c.setRequestProperty("User-Agent", "WotaGeiCam-UpdateCheck")
        c
    } catch (e: Exception) {
        null
    }
}
