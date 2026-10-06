package com.wotagei.cam.update

import android.content.Context
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
 * 3. 落盘后首字节 `PK` 魔数校验（防镜像塞回 HTML 错误页当 APK）。
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

    private const val BUFFER_SIZE = 64 * 1024

    // ------------------------------------------------------------------ 探测真身（注入 probeLatestRelease）

    /**
     * GET JSON 正文；任何失败（连不上/非 2xx/超时）返回 null——编排靠 null 快速跳过下一节点。
     * 探测调用方传 [PROBE_READ_TIMEOUT_MS] 短读超时（P2-2），下载目录之外不共用 30s。
     */
    fun fetchJson(url: String, readTimeoutMs: Int = READ_TIMEOUT_MS): String? {
        val conn = openConnection(url, followRedirects = true, readTimeoutMs = readTimeoutMs) ?: return null
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
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
                            out.write(buf, 0, n)
                            read += n
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
