package com.wotagei.cam.update

/**
 * 「检查更新」的**纯逻辑层**（2026-10-06，用户批准解除 INTERNET 红线后的第一批网络代码）：
 * - [UpdateConfig]：仓库常量与镜像前缀表（顺序即优先级）；
 * - [CompareVersions]：远端 tag ↔ 本机 versionName 的比较（保守：解析失败一律不提示）；
 * - [MirrorChain]：有序 fallback 链状态机（探测段 2 与下载链共用）；
 * - [ReleaseJson]：GitHub releases/latest JSON 的免依赖提取（工程不引 JSON 库，手写提取器）；
 * - [RedirectProbe]：段 3 的 302 Location → tag 解析与 universal 资产名推导；
 * - [probeLatestRelease]：段 1→2→3 的编排。网络以两个函数**参数注入**（真身在 UpdateDownloader，
 *   JVM 单测给假身），编排逻辑因此可全测。
 *
 * 本文件**零 android import**（UpdateNetworkGuardTest 钉住）：纯 JVM。
 * 网络实现（HttpURLConnection）在 UpdateDownloader，UI 只在 SettingsScreen（无自动检查守卫钉住：
 * 探测只准由用户点击触发，不许挂生命周期/进入页面触发）。
 */

/** 仓库与镜像常量（顺序即 fallback 优先级，用户点名的镜像链） */
object UpdateConfig {
    /** 段 1：GitHub API latest release（免登录，公开仓库） */
    const val REPO_API_LATEST = "https://api.github.com/repos/himetuki/WotaGeiCam/releases/latest"

    /** 段 3：releases/latest 页面（302 → /releases/tag/<tag>） */
    const val RELEASES_LATEST_PAGE = "https://github.com/himetuki/WotaGeiCam/releases/latest"

    /** 下载直连前缀（releases/download/<tag>/<asset>） */
    const val DOWNLOAD_BASE = "https://github.com/himetuki/WotaGeiCam/releases/download"

    /**
     * 镜像前缀（前缀 + 完整原始 URL）。用户点名的 `toolwa.com/github` 被排除：它是 HTTP 明文
     * （无 TLS），中间人可替换 APK 二进制；targetSdk 34 默认禁明文流量，也不为它开 usesCleartextTraffic 例外。
     *
     * 安全模型（2026-10-13，v0.0.8）：公共代理的运营方理论上都能替换字节，「不会被污染」不靠
     * 信任代理，靠三层兜底——① 全表 HTTPS（防链路篡改）；② 下载后 SHA-256 端到端校验（哈希由
     * 发版者写进 GitHub Release 说明，只有段 1 的 api.github.com 官方端点读取它，代理改不走；
     * 见 [ReleaseJson.parseSha256FromNotes] 与 UpdateDownloader 的消费点）；③ 系统安装器的签名
     * 强校验（与已装应用不同签名的包根本装不上）。代理被污染的最坏后果 = 更新失败，不是装上恶意软件。
     *
     * 节点存活实测（2026-10-13，releases/download 路径 curl 直连）：gh-proxy.com=206、
     * ghproxy.net=206、ghproxy.link=307→200。此前链上的 ghp.ci（连接失败）、github.akams.cn 与
     * moeyy.cn（均 404）已死节点清退——死节点让 fallback 多等一轮超时，白拖 fallback 时间。
     */
    val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghproxy.link/",
    )

    fun directDownloadUrl(tag: String, assetName: String): String = "$DOWNLOAD_BASE/$tag/$assetName"
}

/** 版本比较（用户裁决的规则，纯函数） */
object CompareVersions {

    /**
     * 远端是否比本机新（true 才提示更新）。tag 形如 `v0.0.7-r09`，本机形如 `0.0.7-r09`：
     * 去 v 前缀后主版本三段数字比大小；主版本相同比 r 号（无 r 记 0）。
     * **任一侧解析失败 → false（保守不提示）**，防解析不了的 tag 误报。
     */
    fun isNewerAvailable(localVersionName: String, remoteTag: String): Boolean {
        val cmp = compare(localVersionName, remoteTag) ?: return false
        return cmp > 0
    }

    /** 比较：>0 远端新、=0 相同、<0 本机新；任一侧解析失败返回 null */
    fun compare(localVersionName: String, remoteTag: String): Int? {
        val local = parse(localVersionName) ?: return null
        val remote = parse(remoteTag) ?: return null
        for (i in 0 until 3) {
            if (local.main[i] != remote.main[i]) return remote.main[i].compareTo(local.main[i])
        }
        return remote.r.compareTo(local.r)
    }

    private data class Parsed(val main: List<Long>, val r: Long)

    /**
     * 解析 `v0.0.7-r09` / `0.0.7` / `0.0.7-r02`。规则：
     * - v/V 前缀剥掉；主版本必须**恰好三段**纯数字（多段少段都算解析失败，保守不提示）；
     * - r 段 `-r` 后只允许纯数字（`v0.0.8-beta`、`v0.0.8-r01-x` 这类未定义形态一律解析失败）。
     */
    private fun parse(version: String): Parsed? {
        var s = version.trim()
        if (s.length > 1 && (s[0] == 'v' || s[0] == 'V')) s = s.substring(1)
        val rIdx = s.indexOf("-r")
        val mainStr: String
        val r: Long
        if (rIdx >= 0) {
            mainStr = s.substring(0, rIdx)
            r = s.substring(rIdx + 2).toLongOrNull() ?: return null
        } else {
            mainStr = s
            r = 0
        }
        val parts = mainStr.split('.')
        if (parts.size != 3) return null
        val main = parts.map { it.toLongOrNull() ?: return null }
        return Parsed(main, r)
    }
}

/** fallback 链的单个节点：label 供日志与测试点名，build 把原始 GitHub URL 变换成本节点的实际 URL */
class MirrorNode(val label: String, private val build: (String) -> String) {
    fun urlFor(rawUrl: String): String = build(rawUrl)
}

/**
 * 有序 fallback 链状态机：探测段 2（镜像代理 API）与下载链（直连+镜像）共用。
 * 失败走 [advance] 到下一节点；耗尽（[exhausted]）后上层判整体失败。[reset] 供下次点击从头再试。
 */
class MirrorChain(private val nodes: List<MirrorNode>) {
    init {
        require(nodes.isNotEmpty()) { "fallback 链至少要有一个节点" }
    }

    var cursor = 0
        private set

    val exhausted: Boolean get() = cursor >= nodes.size
    val currentLabel: String get() = nodes[cursor].label

    fun urlFor(rawUrl: String): String = nodes[cursor].urlFor(rawUrl)

    /** 移到下一个节点；已耗尽返回 false */
    fun advance(): Boolean {
        if (exhausted) return false
        cursor++
        return !exhausted
    }

    fun reset() {
        cursor = 0
    }

    companion object {
        /** 下载链：直连优先，失败再逐个走镜像前缀 */
        fun downloadChain(): MirrorChain = MirrorChain(
            listOf(MirrorNode("direct") { it }) +
                UpdateConfig.MIRROR_PREFIXES.map { p -> MirrorNode(p) { url -> p + url } }
        )

        /** 探测段 2 的镜像链（无直连——直连是段 1 专职） */
        fun probeChain(): MirrorChain = MirrorChain(
            UpdateConfig.MIRROR_PREFIXES.map { p -> MirrorNode(p) { url -> p + url } }
        )
    }
}

/** release 附件（JSON assets[] 里的一枚，或段 3 推导出来的） */
data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long?)

/**
 * latest release 的最小结论：tag + 挑出来的 APK 附件 + 说明原文。
 * [sha256] = 发版者写在 Release 说明里的 universal 包 SHA-256（十六进制小写）。只有**段 1
 * （api.github.com 官方端点）**才提取——镜像转发的 body 不可信；null = 无可信哈希，下载退回
 * 现有防线（PK 魔数 + size 一致性 + 安装签名网）。
 * [notes] = 同一份说明原文（Markdown，未加工），供更新弹窗展示。与 [sha256] 共用同一道信任
 * 门槛：**只有段 1 有**，镜像转发的一律不采信——弹窗里的文字是 app 代替发版者说的话，
 * 不能由中间代理决定。
 */
data class ReleaseInfo(
    val tag: String,
    val asset: ReleaseAsset?,
    val sha256: String? = null,
    val notes: String? = null
)

/** GitHub releases/latest JSON 的免依赖提取器（手写，只认我们关心的三个键，够用即可） */
object ReleaseJson {

    fun parseTag(json: String): String? = stringValueAfterKey(json, "tag_name")

    fun parseAssets(json: String): List<ReleaseAsset> {
        val arrStart = indexOfValueAfterKey(json, "assets", '[') ?: return emptyList()
        val arrEnd = matchingBracket(json, arrStart, '[', ']') ?: return emptyList()
        val arr = json.substring(arrStart + 1, arrEnd)
        val out = ArrayList<ReleaseAsset>()
        var i = 0
        while (i < arr.length) {
            if (arr[i] == '{') {
                val end = matchingBracket(arr, i, '{', '}') ?: break
                val obj = arr.substring(i, end + 1)
                val name = stringValueAfterKey(obj, "name")
                val url = stringValueAfterKey(obj, "browser_download_url")
                val size = numberValueAfterKey(obj, "size")
                if (name != null && url != null) out.add(ReleaseAsset(name, url, size))
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    /**
     * 资产名是否可安全拼进下载 URL（纯函数）。镜像转发的 JSON 不可信：`browser_download_url`
     * 可以指向任意主机，`name` 也可以带路径分隔符/`..`。本工程只接受**纯文件名 + .apk**：
     * 不含 `/`、`\`、连续点，长度有上限。真正的下载 URL 一律由 [UpdateConfig.directDownloadUrl]
     * 用可信的 tag + 校验过的资产名重建，**不再信 JSON 里的 URL 字段**。
     */
    internal fun isSafeReleaseAssetName(name: String?): Boolean {
        if (name == null || name.length !in 1..255) return false
        if (!name.endsWith(".apk", ignoreCase = true)) return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.contains("..")) return false
        return true
    }

    /** 挑 APK 附件：优先 universal（四件套里的通用包），没有则第一个 .apk；无 .apk 返回 null */
    fun pickApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
        val apks = assets.filter { isSafeReleaseAssetName(it.name) }
        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) } ?: apks.firstOrNull()
    }

    fun parse(json: String): ReleaseInfo? = parse(json, trustedNotes = false)

    /**
     * [trustedNotes]=true 时才读 Release 说明（body）：从中提取发版者写入的 universal 包 SHA-256，
     * 并把说明原文交给 [ReleaseInfo.notes]（更新弹窗展示）。
     * **只允许段 1（api.github.com 官方端点）开**：镜像转发的 body 代理可改，提取出来的哈希
     * 没有可信度，弹窗里的文字也会变成代理说了算。说明文本的书写约定见发版流程：
     * 一行 `SHA256: <64位十六进制>`。
     */
    fun parse(json: String, trustedNotes: Boolean): ReleaseInfo? {
        val tag = parseTag(json) ?: return null
        // body 只读一次：sha256 与 notes 取自同一段文本
        val body = if (trustedNotes) stringValueAfterKey(json, "body") else null
        return ReleaseInfo(
            tag = tag,
            asset = pickApkAsset(parseAssets(json)),
            sha256 = body?.let { parseSha256FromNotes(it) },
            notes = body
        )
    }

    /**
     * 从 Release 说明文本提取 SHA-256（纯函数）：找 `SHA256` 后跟冒号（中英文皆容）与空白、
     * 再跟**恰好 64** 位十六进制的第一处（尾随不能再是 hex，防 65 位串误命中前 64 位）；
     * 命中返回**小写** hex，否则 null。
     */
    fun parseSha256FromNotes(notes: String): String? {
        val m = Regex("SHA256\\s*[:：]\\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])").find(notes) ?: return null
        return m.groupValues[1].lowercase()
    }

    // ------------------------------------------------------------------ 手写 JSON 提取

    /** 键 `"key"` 后第一个字符串值（处理 `\"` 转义）；GitHub 的键固定小写，不做大小写变体 */
    private fun stringValueAfterKey(json: String, key: String): String? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        while (i >= 0) {
            val v = valueStartAfterColon(json, i + needle.length)
            if (v != null && json[v] == '"') return readString(json, v)
            i = json.indexOf(needle, i + needle.length)
        }
        return null
    }

    /** 键后的第一个数字值（读连续数字），没有返回 null */
    private fun numberValueAfterKey(json: String, key: String): Long? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        while (i >= 0) {
            val v = valueStartAfterColon(json, i + needle.length)
            if (v != null) {
                var j = v
                while (j < json.length && json[j].isDigit()) j++
                if (j > v) return json.substring(v, j).toLongOrNull()
            }
            i = json.indexOf(needle, i + needle.length)
        }
        return null
    }

    /** 数组/对象值开头：键后跳过空白与冒号，返回首个非空白字符的下标（不是值就返回 null） */
    private fun valueStartAfterColon(json: String, fromKeyEnd: Int): Int? {
        var j = fromKeyEnd
        while (j < json.length && json[j].isWhitespace()) j++
        if (j >= json.length || json[j] != ':') return null
        j++
        while (j < json.length && json[j].isWhitespace()) j++
        return if (j < json.length) j else null
    }

    private fun indexOfValueAfterKey(json: String, key: String, valueChar: Char): Int? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        while (i >= 0) {
            val v = valueStartAfterColon(json, i + needle.length)
            if (v != null && json[v] == valueChar) return v
            i = json.indexOf(needle, i + needle.length)
        }
        return null
    }

    private fun readString(json: String, openQuote: Int): String? {
        val sb = StringBuilder()
        var i = openQuote + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    sb.append(
                        when (json[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> json[i + 1]
                        }
                    )
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** 配平括号定位（跳过字符串字面量内部，GitHub 资产名/URL 不含括号但仍按规矩处理） */
    private fun matchingBracket(s: String, open: Int, openCh: Char, closeCh: Char): Int? {
        var depth = 0
        var i = open
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            when {
                inStr && c == '\\' -> i++
                c == '"' -> inStr = !inStr
                !inStr && c == openCh -> depth++
                !inStr && c == closeCh -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}

/**
 * Release 说明 → 更新弹窗里的纯文本（2026-10-21，用户要求「更新弹窗展示 notes」）。
 *
 * 说明由发版者手写 Markdown（`###` 标题、`**粗体**`、`- ` 条目，末尾还有一行给自动校验用的
 * `SHA256: <hex>`）。工程里没有 Markdown 渲染器，也不为它引依赖，所以这里只做**去标记、不重排内容**：
 * - 标题/条目/粗体/行内代码的标记去掉，正文文字一字不改；
 * - 机器用的 SHA-256 行、以及它上面只有标签的那一行（`- universal 包 SHA256：`）整行隐去——
 *   哈希由 app 静默校验，弹窗里 64 位十六进制只是噪声；
 * - 超过 [MAX_DISPLAY_CHARS] 截断并补省略号：说明来自 GitHub（单条上限 12.5 万字符），
 *   整段塞进 Compose Text 会把弹窗排版拖垮。
 *
 * 纯函数、零 android import（与 [UpdateChecker] 其余部分同一条 JVM 可测线）。
 */
object ReleaseNotes {

    /** 弹窗最多展示的字数（绝对字面量；约 100 行，远超本项目单次发布说明的实际长度） */
    const val MAX_DISPLAY_CHARS = 4000

    /** 机器用的哈希行：`SHA256: <64 位十六进制>`（整行隐去） */
    private val HASH_LINE = Regex("SHA256\\s*[:：]\\s*[0-9a-fA-F]{64}(?![0-9a-fA-F])")

    /** 只有标签、哈希在下一行的写法（发版说明的真实形态：`- universal 包 SHA256：`） */
    private val HASH_LABEL_ONLY = Regex("^.*SHA256\\s*[:：]\\s*$")

    /** `### 标题` → `标题` */
    private val HEADING = Regex("^\\s*#{1,6}\\s+")

    /** `- 条目` / `* 条目` / `+ 条目` → `• 条目`（保留缩进层级） */
    private val BULLET = Regex("^(\\s*)[-*+]\\s+")

    /** Markdown 分隔线（`---` / `***` / `___`）：没有任何信息 */
    private val RULE = Regex("^[-*_]{3,}$")

    /** 说明原文 → 弹窗可展示的纯文本；全空 / 只剩哈希行时返回 null（弹窗不显示说明区） */
    fun forDisplay(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val out = ArrayList<String>()
        for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val trimmed = line.trim()
            if (HASH_LINE.containsMatchIn(trimmed) || HASH_LABEL_ONLY.matches(trimmed)) continue
            if (trimmed.isEmpty()) {
                out.add("")
                continue
            }
            if (RULE.matches(trimmed)) continue
            var s = line.trimEnd()
            s = HEADING.replace(s, "")
            s = BULLET.replace(s, "$1• ")
            // 成对的粗体/行内代码标记去掉，文字本身一字不改
            s = s.replace("**", "").replace("__", "").replace("`", "")
            out.add(s)
        }
        val text = out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (text.isEmpty()) return null
        return if (text.length <= MAX_DISPLAY_CHARS) text else truncateAtLine(text)
    }

    /** 截断：优先退到行边界（不留半句话）；退得太远（不足一半）就地硬截，末尾补一行省略号 */
    private fun truncateAtLine(text: String): String {
        var cut = MAX_DISPLAY_CHARS
        val lineBreak = text.lastIndexOf('\n', cut - 1)
        if (lineBreak > cut / 2) cut = lineBreak
        var head = text.substring(0, cut).trimEnd()
        // 硬截可能落在代理对中间（emoji 被劈成半个），退一格
        if (head.isNotEmpty() && Character.isHighSurrogate(head.last())) head = head.dropLast(1)
        return head + "\n…"
    }
}

/** 段 3：releases/latest 页 302 → tag 解析与资产名推导 */
object RedirectProbe {

    /** `.../releases/tag/v0.0.8-r01`（尾斜杠容忍）→ `v0.0.8-r01`；不是 tag 路径返回 null */
    fun tagFromLocation(location: String): String? {
        val marker = "/releases/tag/"
        val idx = location.indexOf(marker)
        if (idx < 0) return null
        var tag = location.substring(idx + marker.length)
        if (tag.endsWith("/")) tag = tag.dropLast(1)
        if (tag.isEmpty() || tag.contains('/')) return null
        return tag
    }

    /**
     * 段 3 无 JSON 资产列表，universal 包名从 tag 推导。命名以 CI workflow（build-apk.yml 产物四件套）
     * 为准：archivesName = `WotaGeiCam-v<versionName>` ⇒ 资产名**携带 v 前缀**
     * （如 `WotaGeiCam-v0.0.7-r08-universal-release.apk`）。
     */
    fun guessUniversalAssetName(tag: String): String = "WotaGeiCam-$tag-universal-release.apk"
}

/** 探测结论：UI 只消费这个，不再自己比版本 */
sealed interface ProbeResult {
    /**
     * 远端有新版：tag + 直连下载 URL（镜像链在下载时走）+ 资产名 + 大小（段 3 无 → null，下载时用 Content-Length）。
     * [sha256] 仅段 1 携带（官方端点的 Release 说明哈希，见 [ReleaseInfo.sha256]）；null = 下载退回现有防线。
     * [notes] 同样仅段 1 携带（说明原文，弹窗展示时交给 [ReleaseNotes.forDisplay]）；段 2/3 恒 null。
     */
    data class UpdateAvailable(
        val latestTag: String,
        val assetName: String,
        val downloadUrl: String,
        val sizeBytes: Long?,
        val sha256: String? = null,
        val notes: String? = null
    ) : ProbeResult

    /** 已是最新 */
    object UpToDate : ProbeResult

    /** 三段 fallback 全失败（或拿不到可下载的 APK） */
    object Failed : ProbeResult

    /** 用户取消（检查中点行）：编排在每个节点前查探针，剩余节点全部跳过 */
    object Cancelled : ProbeResult
}

/**
 * 段 1→2→3 编排。网络以两个函数注入（UpdateDownloader 提供真身、单测给假身）：
 * - [fetchJson]：GET 返回正文；任何失败（连不上/非 2xx/超时）返回 null（快速跳过下一节点）；
 * - [fetchRedirectLocation]：GET 不跟随重定向，返回 Location 头；失败 null；
 * - [isCancelled]：取消探针，「检查中点行 = 取消」——编排在**每个节点前**检查，
 *   置位即跳过剩余节点返回 [ProbeResult.Cancelled]。节点内部（已发出的请求）不可打断，
 *   取消后最坏再等一个超时周期（探测真身用短读超时，见 UpdateDownloader.PROBE_READ_TIMEOUT_MS）。
 */
fun probeLatestRelease(
    localVersionName: String,
    fetchJson: (String) -> String?,
    fetchRedirectLocation: (String) -> String?,
    isCancelled: () -> Boolean = { false }
): ProbeResult {
    // 段 1：直连 GitHub API（官方端点：Release 说明 body 可信——提取 SHA-256 供下载后校验，
    // 同时把说明原文带给更新弹窗）
    if (isCancelled()) return ProbeResult.Cancelled
    jsonNode(fetchJson(UpdateConfig.REPO_API_LATEST), localVersionName, trustedNotes = true)?.let { return it }
    // 段 2：镜像前缀逐个代理同一 API（gh-proxy 系对 api 路径支持不一，失败快速跳过）。
    // trustedNotes=false：镜像可改写 body，转发来的哈希没有可信度、说明文字也不采信，
    // 宁可不校验/不展示也不拿代理给的文本当真。
    val chain = MirrorChain.probeChain()
    while (!chain.exhausted) {
        if (isCancelled()) return ProbeResult.Cancelled
        jsonNode(fetchJson(chain.urlFor(UpdateConfig.REPO_API_LATEST)), localVersionName, trustedNotes = false)?.let { return it }
        chain.advance()
    }
    // 段 3：302 → tag → 推导 universal 资产名（无 size，下载时用 Content-Length）
    if (isCancelled()) return ProbeResult.Cancelled
    fetchRedirectLocation(UpdateConfig.RELEASES_LATEST_PAGE)?.let { loc ->
        RedirectProbe.tagFromLocation(loc)?.let { tag ->
            val assetName = RedirectProbe.guessUniversalAssetName(tag)
            return decide(
                ReleaseInfo(tag, ReleaseAsset(assetName, UpdateConfig.directDownloadUrl(tag, assetName), null)),
                localVersionName
            )
        }
    }
    return ProbeResult.Failed
}

/**
 * 一个 JSON 节点的结论：null = 该节点无结论（解析失败，或「有新版但拿不到 .apk 附件」——继续下一段）；
 * 非 null = 终局结论（UpToDate / UpdateAvailable），编排立即返回。
 * [trustedNotes] 只在段 1（api.github.com 官方端点）为 true：只有那时 body 里的 SHA-256 才可信。
 */
private fun jsonNode(json: String?, localVersionName: String, trustedNotes: Boolean = false): ProbeResult? {
    json ?: return null
    val info = ReleaseJson.parse(json, trustedNotes = trustedNotes) ?: return null
    return when {
        !CompareVersions.isNewerAvailable(localVersionName, info.tag) -> ProbeResult.UpToDate
        info.asset != null -> {
            // 段 2 的 JSON 可能被镜像改写：URL 字段一律不信，用可信仓库常量 + 已校验资产名重建
            val url = UpdateConfig.directDownloadUrl(info.tag, info.asset.name)
            ProbeResult.UpdateAvailable(
                info.tag, info.asset.name, url, info.asset.sizeBytes, info.sha256, info.notes
            )
        }
        else -> null
    }
}

/** 段 3 的资产是推导出来的（恒非 null），结论只有最新/有新版两态 */
private fun decide(info: ReleaseInfo, localVersionName: String): ProbeResult {
    val asset = info.asset!!
    return if (!CompareVersions.isNewerAvailable(localVersionName, info.tag)) {
        ProbeResult.UpToDate
    } else {
        ProbeResult.UpdateAvailable(
            info.tag, asset.name, UpdateConfig.directDownloadUrl(info.tag, asset.name), asset.sizeBytes
        )
    }
}
