package com.wotagei.cam.update

import java.net.URL

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

    /**
     * 官方 API 请求允许落在哪些主机（Y43）：段 1 的「说明正文可信」标记必须绑定**重定向后的最终来源**，
     * 不能只看用户填的起始 URL。api.github.com 正常不重定向；任何把它引到别的主机的 302 都按失败处理，
     * 以免代理/中间设备用「官方 URL 发起、镜像正文落地」的方式伪造可信 SHA。
     */
    val OFFICIAL_API_HOSTS = setOf("api.github.com")

    /** releases/latest 页面 302 的 Location 只接受 GitHub 官方页面（防 Location 指向任意主机）。 */
    val OFFICIAL_RELEASE_HOSTS = setOf("github.com", "www.github.com")

    /** 下载直连前缀（releases/download/<tag>/<asset>） */
    const val DOWNLOAD_BASE = "https://github.com/himetuki/WotaGeiCam/releases/download"

    /**
     * 镜像前缀（前缀 + 完整原始 URL）。用户点名的 `toolwa.com/github` 被排除：它是 HTTP 明文
     * （无 TLS），中间人可替换 APK 二进制；targetSdk 34 默认禁明文流量，也不为它开 usesCleartextTraffic 例外。
     *
     * 安全模型（2026-10-13，v0.0.8；2026-10-21 扩表后不变）：公共代理的运营方理论上都能替换字节，
     * 「不会被污染」不靠信任代理，靠三层兜底——① 全表 HTTPS（防链路篡改）；② 下载后 SHA-256 端到端
     * 校验（哈希由发版者写进 GitHub Release 说明，只有段 1 的 api.github.com 官方端点读取它，代理改不走；
     * 见 [ReleaseJson.parseSha256FromNotes] 与 UpdateDownloader 的消费点）；③ 系统安装器的签名强校验
     * （与已装应用不同签名的包根本装不上）。代理被污染的最坏后果 = 更新失败，不是装上恶意软件。
     *
     * **名单来源与地区差异（2026-11-09 用户裁决：不许只以本机宿主网络测试为准）**：
     * 用户分布在不同地区/运营商，任何单点实测都不能代表"对别人可用"。所以名单按
     * "公开常见公益加速前缀（社区长期提及）+ 本机 2026-10-21 实测可用的起点" 组合，
     * 并且**不把单点实测当作淘汰依据**——本机不通的也保留在后排（可能是地区/时段问题）。
     * 真正的裁决交给运行时：
     * - 下载前【并发测速 6 秒预算】按实测字节率降序重排（见 [UpdateDownloader.rankChannelsBySpeed]）；
     * - 连不上/非 2xx/首块不是 APK 魔数/超预算 → 记 0 垫底但**仍会被尝试**；
     * - 全部失败才判下载失败（UI 同时给出 Release 页手动下载指引）。
     *
     * 本机 2026-10-21 起点记录（仅供参考，不代表其它地区）：gh-proxy.com（API+下载 ✓）、
     * gh-proxy.org（API+下载 ✓）、gh.monlor.com（下载 ✓）、ghproxy.imciel.com（下载 ✓）、
     * ghproxy.net（下载 ✓ 慢）。历史清退原因只有一类是硬伤：**307 后回 HTML 落地页**
     * （首块非 PK 魔数，探测/测速都会判该渠道无效，自动跳过）；ghproxy.*.edu.cn 类域名不存在。
     *
     * 顺序 = **测速前的兜底顺序**（直连永远第一，见 [UpdateDownloader.downloadCandidates]）；
     * 下载时按实测速度重排。名单变长不会拖慢成功路径（并发测速 + 6 秒总预算），
     * 只会在"全表都慢"时多试几秒。
     */
    val MIRROR_PREFIXES = listOf(
        // —— 起点（本机 2026-10-21 实测可用；仅起点参考，非地区保证）——
        "https://gh-proxy.com/",
        "https://gh-proxy.org/",
        "https://gh.monlor.com/",
        "https://ghproxy.imciel.com/",
        "https://ghproxy.net/",
        // —— 公开常见前缀（社区长期提及；本机旧测部分不通，保留给其它地区/时段）——
        "https://ghfast.top/",
        "https://gh.llkk.cc/",
        "https://ghproxy.cc/",
        "https://gh-proxy.net/",
        "https://hub.gitmirror.com/",
        "https://ghps.cc/",
    )

    /**
     * 能代理 **api.github.com** 的镜像（探测段 2 专用）：下载能用 ≠ API 能用。
     * 探测段 2 是**逐节点短超时**尝试，所以这里只放"公开资料普遍认为支持 api 代理"的前缀；
     * 失败节点 0 成本跳过，不进下载名单（下载走 [MIRROR_PREFIXES] 全表）。
     */
    val API_MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://gh-proxy.org/",
        "https://ghfast.top/",
    )

    fun directDownloadUrl(tag: String, assetName: String): String = "$DOWNLOAD_BASE/$tag/$assetName"
}

/**
 * 资产名里的 ABI 段（CI 命名：`WotaGeiCam-<tag>-<flavor|compat>[-<abi>]-release-Android.apk`）。
 *
 * Y44 的根因是「选到 ABI 分包、却拿 universal 的摘要去校验」；把资产名解析成枚举后，摘要与
 * ABI 都跟具体资产名绑定，而不是跟 flavor 绑死。[UNKNOWN] 表示出现了本工程不认识的 ABI 段，
 * 安装闸一律拒绝（宁可让用户手动下载，也不装一个来源说不清的包）。
 */
enum class UpdateAbi(val token: String) {
    UNIVERSAL("universal"),
    ARM64_V8A("arm64-v8a"),
    ARMEABI_V7A("armeabi-v7a"),
    UNKNOWN("");

    /** 当前设备是否能用这枚资产（universal 恒可用；分包看 Build.SUPPORTED_ABIS） */
    fun supportedBy(deviceAbis: List<String>): Boolean = when (this) {
        UNIVERSAL -> true
        ARM64_V8A -> deviceAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
        ARMEABI_V7A -> deviceAbis.any {
            it.equals("armeabi-v7a", ignoreCase = true) || it.equals("armeabi", ignoreCase = true)
        }
        UNKNOWN -> false
    }

    /**
     * APK 内容是否像这枚资产声明的那样（安装前最后一道 ABI 绑定）。
     * `lib/` 下没有本机 so 时（纯 Java 依赖的 APK）无法从归档判断，按「不矛盾」放行；
     * 有 so 时则要求声明的 ABI 段真实存在。
     */
    fun presentIn(archiveAbis: Set<String>): Boolean = when (this) {
        UNIVERSAL -> true
        ARM64_V8A -> archiveAbis.isEmpty() || archiveAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
        ARMEABI_V7A -> archiveAbis.isEmpty() || archiveAbis.any {
            it.equals("armeabi-v7a", ignoreCase = true) || it.equals("armeabi", ignoreCase = true)
        }
        UNKNOWN -> false
    }

    companion object {
        fun fromAssetName(name: String): UpdateAbi = when {
            name.contains("-arm64-v8a-", ignoreCase = true) -> ARM64_V8A
            name.contains("-armeabi-v7a-", ignoreCase = true) -> ARMEABI_V7A
            name.contains("-universal-", ignoreCase = true) -> UNIVERSAL
            // 旧版单包资产名没有 ABI/flavor 段，内容是 universal。已知 x86 段则明确拒绝。
            name.contains("-x86", ignoreCase = true) -> UNKNOWN
            else -> UNIVERSAL
        }
    }
}

/**
 * 一次更新选择的**不可变绑定**（Y43）：发行 tag + flavor + 具体资产名 + ABI + 该资产摘要。
 *
 * 下载器只接收这个对象，产出的 [UpdateDownloader.DownloadOutcome.Done] 会把它与实际字节摘要
 * 一起带回；安装器再用它核对归档包名/versionName/flavor/ABI/摘要。这样「用户在弹窗里看到的
 * 版本」与「最终交给安装器的字节」不再是两条可以各自被替换的路径。
 *
 * [expectedSha256] 为 null 只表示「本次探测没有可信摘要」（镜像段/旧说明），**不表示**可以跳过
 * 其余绑定：versionName 必须与 tag 对应，flavor 后缀必须一致，ABI 必须与设备/归档相符。
 */
data class UpdateSelection(
    val releaseTag: String,
    val flavor: String?,
    val assetName: String,
    val abi: UpdateAbi,
    val expectedSha256: String?,
    val sizeBytes: Long?,
    val downloadUrl: String
) {
    init {
        require(flavor == null || flavor == "full" || flavor == "lite") { "未知 flavor：$flavor" }
        require(assetName.isNotEmpty() && downloadUrl.endsWith("/$assetName")) {
            "下载 URL 与资产名未绑定：$downloadUrl / $assetName"
        }
    }

    /** 该 tag + flavor 对应的 versionName（lite 带 -lite 后缀；与 build.gradle.kts 的命名一致） */
    val expectedVersionName: String
        get() {
            val base = releaseTag.trim().removePrefix("v").removePrefix("V")
            return if (flavor == "lite") "$base-lite" else base
        }

    companion object {
        fun normalizeFlavor(value: String?): String? = when (value?.trim()?.lowercase()) {
            "full" -> "full"
            "lite" -> "lite"
            else -> null
        }

        /**
         * 旧调用/纯下载测试的兼容构造：tag 从 GitHub 下载 URL 反推，flavor 未知（null），
         * 因而安装闸不拿它做 versionName 绑定（生产路径一律走探测结果里的 flavor）。
         */
        fun legacy(downloadUrl: String, sizeBytes: Long?, expectedSha256: String?): UpdateSelection {
            val assetName = downloadUrl.substringAfterLast('/')
            val tag = downloadUrl.substringBeforeLast('/').substringAfterLast('/')
            return UpdateSelection(
                releaseTag = tag,
                flavor = null,
                assetName = assetName,
                abi = UpdateAbi.fromAssetName(assetName),
                expectedSha256 = expectedSha256,
                sizeBytes = sizeBytes,
                downloadUrl = downloadUrl
            )
        }
    }
}

/** 探测响应的正文 + 最终落地 URL（Y43：可信标记必须绑定重定向后的来源）。 */
data class FetchedJson(val body: String, val finalUrl: String)

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
        // 只有本地 lite 构建追加已知 -lite 后缀；远端 tag 仍按原语法严格解析，不吞任意后缀。
        val local = parse(localVersionName.trim().removeSuffix("-lite")) ?: return null
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
        /**
         * 探测段 2 的镜像链（无直连——直连是段 1 专职）。只放**确认能代理 api.github.com** 的前缀：
         * 下载能用的镜像未必代理 API，放进来只会白等一轮超时。
         */
        fun probeChain(): MirrorChain = MirrorChain(
            UpdateConfig.API_MIRROR_PREFIXES.map { p -> MirrorNode(p) { url -> p + url } }
        )
    }
}

/** release 附件（JSON assets[] 里的一枚，或段 3 推导出来的） */
data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long?)

/**
 * latest release 的最小结论：tag + 挑出来的 APK 附件 + 说明原文。
 * [sha256] = 发版者写在 Release 说明里、**与 [asset] 同名绑定**的 SHA-256（十六进制小写）。
 * 只有**段 1（api.github.com 官方端点且最终来源仍是该主机）**才提取——镜像转发的 body 不可信，
 * 拿假哈希当真会把合法更新判废；null = 该资产没有可信哈希（旧说明/镜像段），下载仍保留 PK 魔数、
 * size、versionName/flavor/ABI 与安装签名网。**绝不拿 universal 的摘要去校验 ABI 分包**（Y44）。
 * [notes] = 同一份说明原文（Markdown，未加工），供更新弹窗展示。**段 1/段 2 都读**（2026-10-21
 * 用户裁决：都走镜像了，安装包本就可能被换，一段说明文字不必再设门槛）；它只用于展示，
 * 不参与任何判断——装不装得上仍由 SHA-256（段 1）与系统安装签名决定。
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

    /**
     * 挑 APK 附件：[flavor] 三态更新互斥（2026-11-07 用户裁决——有 AI 的只更新有 AI 包，
     * 无 AI 的只更新无 AI 包）：
     * - lite：**只在 -lite- 附件里挑，且 universal 优先**（绝不推 full/兼容包——lite 用户装 full
     *   会改变功能集）；无 lite 附件 → null（不提示，保守：CompareVersions 同款保守哲学）；
     * - full：优先 -full- 附件（universal 优先），其次通用包（历史发版无 flavor 后缀），
     *   **绝不推 -lite- 或 -compat- 包（compat 的内容也是 lite）**；
     * - null（旧调用/单测兼容）：旧行为（优先 universal，无则第一个 .apk）。
     *
     * **universal 优先是硬要求**（2026-11-09 修）：12 枚资产里 ABI 分包名按字母序排在 universal
     * 之前（-arm64-v8a- / -armeabi-v7a-），只按"第一个含 flavor 段"会挑到 arm64 分包，
     * armeabi-v7a 设备直接装不上。选包顺序固定为 flavor 组内 universal → 组内第一个。
     *
     * **[digests] 非空时**（段 1 官方说明）优先选「有同名摘要」的资产；universal 仍优先于 ABI 分包。
     * 只有 ABI 分包可用时（universal 缺失），[requireDigestForAbiSplits]=true 会拒绝没有
     * 分包摘要的候选（Y44 验收：仅有分包 + 其摘要 → 通过；无摘要 → 拒绝）。[deviceAbis]
     * 用于多个分包里挑当前设备真能装的，顺序与 Build.SUPPORTED_ABIS 一致。
     */
    fun pickApkAsset(
        assets: List<ReleaseAsset>,
        flavor: String? = null,
        digests: Map<String, String> = emptyMap(),
        requireDigestForAbiSplits: Boolean = false,
        deviceAbis: List<String> = emptyList()
    ): ReleaseAsset? {
        val apks = assets.filter { isSafeReleaseAssetName(it.name) }
        val scoped = when (flavor) {
            "lite" -> apks.filter { it.name.contains("-lite-", ignoreCase = true) }
            "full" -> {
                val full = apks.filter { it.name.contains("-full-", ignoreCase = true) }
                if (full.isNotEmpty()) full else apks.filterNot {
                    // 只回退真正无 flavor 的历史包；compat 是 lite 字节副本，不属于历史通用 full 包。
                    it.name.contains("-lite", ignoreCase = true) || it.name.contains("-compat-", ignoreCase = true)
                }
            }
            else -> apks
        }
        if (scoped.isEmpty()) return null

        // 只有名字里显式带 `-universal-` 的才是 universal 首选；无 ABI/flavor 段的旧包
        // 在 [UpdateAbi.fromAssetName] 里同样按 universal 语义看待，但它们不是「显式 universal」，
        // 必须留在后面的组内首个回退，不能抢占 explicit universal 的位置。
        val universal = scoped.filter { it.name.contains("-universal-", ignoreCase = true) }
        if (universal.isNotEmpty()) {
            // universal 优先；有同名摘要的那枚优先（同一组通常只有一枚 universal）。
            return universal.firstOrNull { digests.containsKey(it.name) } ?: universal.first()
        }

        val splits = scoped.filter { UpdateAbi.fromAssetName(it.name) != UpdateAbi.UNKNOWN }
        if (splits.isEmpty()) return null
        val deviceOrdered = if (deviceAbis.isEmpty()) {
            splits
        } else {
            val supported = splits.filter { UpdateAbi.fromAssetName(it.name).supportedBy(deviceAbis) }
            supported.sortedBy { asset ->
                val abi = UpdateAbi.fromAssetName(asset.name)
                deviceAbis.indexOfFirst { it.equals(abi.token, ignoreCase = true) }
                    .let { if (it < 0) Int.MAX_VALUE else it }
            }
        }
        if (deviceOrdered.isEmpty()) return null
        if (requireDigestForAbiSplits) {
            return deviceOrdered.firstOrNull { digests.containsKey(it.name) }
        }
        return deviceOrdered.first()
    }

    fun parse(json: String): ReleaseInfo? = parse(json, extractSha256 = false)

    /**
     * 说明原文（body）**总是**读出来交给 [ReleaseInfo.notes]（弹窗展示，段 1/2 都有）；
     * [extractSha256]=true 时额外从同一段文本提取发版者写入的摘要，并**按资产名绑定**：
     * - 逐资产行：`SHA256-asset: <资产名> <64位十六进制>`（Y44 推荐的新写法，可写多行）；
     * - 旧三行：`SHA256:` / `SHA256-full:` / `SHA256-lite:` 只绑定到对应 flavor 的 **universal**
     *   资产名（历史语义保留；绝不移交给 ABI 分包）。
     * **哈希只允许段 1（api.github.com 官方端点且最终来源可信）开**：镜像转发的 body 代理可改，
     * 拿假哈希当真会把合法更新判废；说明文字没有这个顾虑（只展示、不参与判断）。
     */
    fun parse(
        json: String,
        extractSha256: Boolean,
        flavor: String? = null,
        deviceAbis: List<String> = emptyList()
    ): ReleaseInfo? {
        val tag = parseTag(json) ?: return null
        // body 只读一次：sha256 与 notes 取自同一段文本
        val body = stringValueAfterKey(json, "body")
        val assets = parseAssets(json)
        val digests = if (extractSha256) resolveAssetDigests(assets, body.orEmpty(), flavor) else emptyMap()
        val asset = pickApkAsset(
            assets = assets,
            flavor = flavor,
            digests = digests,
            requireDigestForAbiSplits = extractSha256,
            deviceAbis = deviceAbis
        )
        return ReleaseInfo(
            tag = tag,
            asset = asset,
            sha256 = asset?.let { digests[it.name] },
            notes = body
        )
    }

    /**
     * 把说明文本里的摘要解析成「资产名 → 摘要」映射（纯函数，Y44）。
     * 逐资产行优先；旧 flavor 行只给同 flavor 的 universal 资产兜底；通用行只给旧版
     * 无 flavor 的 universal 资产兜底。**同一份摘要不会跨资产名复用**。
     */
    fun resolveAssetDigests(
        assets: List<ReleaseAsset>,
        notes: String,
        flavor: String?
    ): Map<String, String> {
        if (notes.isEmpty() || assets.isEmpty()) return emptyMap()
        val perAsset = parseAssetDigests(notes)
        val legacyUniversal = parseSha256FromNotes(notes, flavor)
        val out = LinkedHashMap<String, String>()
        for (asset in assets) {
            val digest = perAsset[asset.name]
                ?: legacyUniversal?.takeIf { isLegacyUniversalFor(asset.name, flavor) }
            if (digest != null) out[asset.name] = digest
        }
        return out
    }

    /** 逐资产摘要行：`SHA256-asset: <安全资产名> <64 hex>`（资产名允许字母数字点下划线连字符） */
    private val ASSET_SHA_LINE =
        Regex("SHA256-asset\\s*[:：]\\s*([A-Za-z0-9._-]+\\.apk)\\s+([0-9a-fA-F]{64})(?![0-9a-fA-F])")

    /** 解析逐资产摘要行；同一资产重复出现时以第一行为准（防后写的行覆盖可信值） */
    fun parseAssetDigests(notes: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ASSET_SHA_LINE.findAll(notes).forEach { m ->
            val name = m.groupValues[1]
            if (isSafeReleaseAssetName(name) && !out.containsKey(name)) {
                out[name] = m.groupValues[2].lowercase()
            }
        }
        return out
    }

    /** 旧三行只与「对应 flavor 的 universal 包」绑定，不能落到 ABI 分包头上 */
    private fun isLegacyUniversalFor(assetName: String, flavor: String?): Boolean {
        if (!assetName.contains("-universal-", ignoreCase = true)) return false
        val full = assetName.contains("-full-", ignoreCase = true)
        val lite = assetName.contains("-lite-", ignoreCase = true)
        val compat = assetName.contains("-compat-", ignoreCase = true)
        return when (flavor) {
            "full" -> full
            "lite" -> lite
            else -> !full && !lite && !compat
        }
    }

    /**
     * 从 Release 说明文本提取 SHA-256（纯函数）：找 `SHA256`（可带 `-full`/`-lite` 后缀）后跟冒号
     * （中英文皆容）与空白、再跟**恰好 64** 位十六进制的第一处（尾随不能再是 hex，防 65 位串误命中
     * 前 64 位）；命中返回**小写** hex，否则 null。
     *
     * **三行语义（2026-11-09 用户裁决：旧版兼容包=lite）**：
     * - `SHA256:`      **旧版更新器唯一认识的一行**（≤v0.0.12-r01 只读这行，且无 flavor 概念）。
     *   它必须指向「旧版会挑到的那个包」——即**无 flavor 命名的 lite 包**（CI 产出 12 枚资产里的
     *   旧命名四件套，内容=lite）。所以通用行 = **lite universal 的哈希**，不是 full！
     * - `SHA256-full:` full 变体专用行（full 更新器只认这行，**无则判无哈希**，绝不回退通用行——
     *   通用行现在是 lite 的哈希，回退必错配）。
     * - `SHA256-lite:` lite 变体专用行（同上，只认本行）。
     *
     * [flavor]=null（旧调用/未知）：按通用行解析（历史语义保留）。
     */
    fun parseSha256FromNotes(notes: String, flavor: String? = null): String? {
        if (flavor == "lite") {
            val lite = Regex("SHA256-lite\\s*[:：]\\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])").find(notes)
                ?: return null
            return lite.groupValues[1].lowercase()
        }
        if (flavor == "full") {
            // 不回退通用行：r05 起通用行是 lite 的哈希（旧版兼容口径），回退会让 full 必然校验失败。
            val full = Regex("SHA256-full\\s*[:：]\\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])").find(notes)
                ?: return null
            return full.groupValues[1].lowercase()
        }
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

    /** 机器用的哈希行：`SHA256:` / `SHA256-full:` / `SHA256-lite:` + 64 位十六进制（整行隐去） */
    private val HASH_LINE = Regex("SHA256(-full|-lite)?\\s*[:：]\\s*[0-9a-fA-F]{64}(?![0-9a-fA-F])")

    /** 逐资产摘要行：`SHA256-asset: <资产名> <64 hex>`（Y44；正文里也是机器用行，整行隐去） */
    private val ASSET_HASH_LINE =
        Regex("SHA256-asset\\s*[:：]\\s*[A-Za-z0-9._-]+\\.apk\\s+[0-9a-fA-F]{64}(?![0-9a-fA-F])")

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
            if (HASH_LINE.containsMatchIn(trimmed) || ASSET_HASH_LINE.containsMatchIn(trimmed) ||
                HASH_LABEL_ONLY.matches(trimmed)
            ) continue
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

    /**
     * 段 3 的 Location **必须落在 GitHub 官方发布页**（Y43）：路径里出现 `/releases/tag/` 还不够，
     * 恶意镜像完全可以回一个 `https://evil.example/x/releases/tag/v9.9.9` 把版本号抬到任意值。
     * 主机不在 [UpdateConfig.OFFICIAL_RELEASE_HOSTS] 时整段结论作废，让更新失败而不是弹假版本。
     */
    fun trustedTagFromLocation(location: String): String? {
        if (!hostAllowed(location, UpdateConfig.OFFICIAL_RELEASE_HOSTS)) return null
        return tagFromLocation(location)
    }

    /** 最终落地 URL 的主机是否在允许集合内（Y43：可信标记必须绑定重定向后的来源） */
    fun hostAllowed(url: String, allowed: Set<String>): Boolean {
        val host = runCatching { URL(url).host }.getOrNull()?.lowercase() ?: return false
        return host in allowed
    }

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
     * 段 3 无 JSON 资产列表，universal 包名从 tag 推导。命名以 CI workflow（build-apk.yml 产物）
     * 为准：资产名 = `WotaGeiCam-<tag>-<flavor>-universal-release-Android.apk`（双包）或
     * `WotaGeiCam-<tag>-universal-release-Android.apk`（单包旧版）——**项目名前缀（2026-10-25 用户
     * 裁决：资产名必须带项目名）+ v 前缀、扩展名前平台后缀 -Android、flavor 段只有一个**。
     * （旧版资产 ≤0.0.10-r05 不带项目名——更新器只匹配最新 release，新版上线自然切换，无兼容逻辑。）
     */
    fun guessUniversalAssetName(tag: String, flavor: String? = null): String =
        if (flavor == "full" || flavor == "lite") "WotaGeiCam-$tag-$flavor-universal-release-Android.apk"
        else "WotaGeiCam-$tag-universal-release-Android.apk"
}

/** 探测结论：UI 只消费这个，不再自己比版本 */
sealed interface ProbeResult {
    /**
     * 远端有新版：tag + 直连下载 URL（镜像链在下载时走）+ 资产名 + 大小（段 3 无 → null，下载时用 Content-Length）。
     * [sha256] 仅段 1 携带（官方端点的 Release 说明哈希，见 [ReleaseInfo.sha256]）；null = 下载退回现有防线。
     * [notes] 段 1/2 都携带（说明原文，弹窗展示时交给 [ReleaseNotes.forDisplay]）；段 3 只有 Location，恒 null。
     */
    data class UpdateAvailable(
        val latestTag: String,
        val assetName: String,
        val downloadUrl: String,
        val sizeBytes: Long?,
        val sha256: String? = null,
        val notes: String? = null,
        /** 本次探测所属 flavor（BuildConfig.FLAVOR）；段 3 推导的资产名也必须与它一致 */
        val flavor: String? = null
    ) : ProbeResult {
        /** 资产名解析出的 ABI（Y44：摘要/ABI/资产名三者同源） */
        val abi: UpdateAbi get() = UpdateAbi.fromAssetName(assetName)

        /** 交给下载器的不可变绑定（Y43） */
        val selection: UpdateSelection
            get() = UpdateSelection(
                releaseTag = latestTag,
                flavor = UpdateSelection.normalizeFlavor(flavor),
                assetName = assetName,
                abi = abi,
                expectedSha256 = sha256,
                sizeBytes = sizeBytes,
                downloadUrl = downloadUrl
            )
    }

    /** 已是最新 */
    object UpToDate : ProbeResult

    /** 三段 fallback 全失败（或拿不到可下载的 APK） */
    object Failed : ProbeResult

    /** 用户取消（检查中点行）：编排在每个节点前查探针，剩余节点全部跳过 */
    object Cancelled : ProbeResult
}

/**
 * 段 1→2→3 编排。网络以函数注入（UpdateDownloader 提供真身、单测给假身）：
 * - [fetchJson]：GET 返回正文；任何失败（连不上/非 2xx/超时）返回 null（快速跳过下一节点）；
 * - [fetchJsonSource]（可选，生产路径用）：与 [fetchJson] 相同，但额外返回**最终落地 URL**，
 *   从而让「官方说明可信」绑定重定向后的来源（Y43）；不传则沿用旧的「起始 URL 可信」语义；
 * - [fetchRedirectLocation]：GET 不跟随重定向，返回 Location 头；失败 null；
 * - [isCancelled]：取消探针，「检查中点行 = 取消」——编排在**每个节点前**检查，
 *   置位即跳过剩余节点返回 [ProbeResult.Cancelled]。节点内部（已发出的请求）不可打断，
 *   取消后最坏再等一个超时周期（探测真身用短读超时，见 UpdateDownloader.PROBE_READ_TIMEOUT_MS）。
 */
fun probeLatestRelease(
    localVersionName: String,
    fetchJson: (String) -> String?,
    fetchRedirectLocation: (String) -> String?,
    isCancelled: () -> Boolean = { false },
    flavor: String? = null,
    deviceAbis: List<String> = emptyList(),
    fetchJsonSource: ((url: String, allowedHosts: Set<String>?) -> FetchedJson?)? = null
): ProbeResult {
    // 段 1：直连 GitHub API（官方端点：Release 说明 body 可信——提取 SHA-256 供下载后校验，
    // 同时把说明原文带给更新弹窗）。Y43：可信不仅看起始 URL，还要看最终落地主机。
    if (isCancelled()) return ProbeResult.Cancelled
    val direct = fetchJsonSource?.invoke(UpdateConfig.REPO_API_LATEST, UpdateConfig.OFFICIAL_API_HOSTS)
    // Y43 双保险：生产传输层已按 allowedHosts 拒绝非官方最终来源；编排层再核一次，
    // 注入方/未来调用方传错也拿不到「可信说明」标记（宁可这一段无结论，也不信错来源的哈希）。
    val directBody = if (fetchJsonSource == null) {
        fetchJson(UpdateConfig.REPO_API_LATEST)
    } else {
        direct?.takeIf { RedirectProbe.hostAllowed(it.finalUrl, UpdateConfig.OFFICIAL_API_HOSTS) }?.body
    }
    jsonNode(
        directBody, localVersionName, extractSha256 = directBody != null,
        flavor = flavor, deviceAbis = deviceAbis
    )?.let { return it }
    // 段 2：镜像前缀逐个代理同一 API（gh-proxy 系对 api 路径支持不一，失败快速跳过）。
    // extractSha256=false：镜像可改写 body，转发来的哈希没有可信度，宁可不校验也不拿假哈希当真；
    // 说明原文照读（只用于弹窗展示，装不装得上仍由摘要 + versionName/flavor/ABI + 系统签名决定）。
    val chain = MirrorChain.probeChain()
    while (!chain.exhausted) {
        if (isCancelled()) return ProbeResult.Cancelled
        val url = chain.urlFor(UpdateConfig.REPO_API_LATEST)
        val body = fetchJsonSource?.invoke(url, null)?.body ?: fetchJson(url)
        jsonNode(
            body, localVersionName, extractSha256 = false,
            flavor = flavor, deviceAbis = deviceAbis
        )?.let { return it }
        chain.advance()
    }
    // 段 3：302 → tag → 推导资产名（无 size，下载时用 Content-Length）。
    // Y43：Location 主机必须是 GitHub 官方发布页，否则整段作废。
    if (isCancelled()) return ProbeResult.Cancelled
    fetchRedirectLocation(UpdateConfig.RELEASES_LATEST_PAGE)?.let { loc ->
        RedirectProbe.trustedTagFromLocation(loc)?.let { tag ->
            val assetName = RedirectProbe.guessUniversalAssetName(tag, flavor)
            return decide(
                ReleaseInfo(tag, ReleaseAsset(assetName, UpdateConfig.directDownloadUrl(tag, assetName), null)),
                localVersionName,
                flavor
            )
        }
    }
    return ProbeResult.Failed
}

/**
 * 一个 JSON 节点的结论：null = 该节点无结论（解析失败，或「有新版但拿不到可校验的 .apk 附件」——
 * 继续下一段）；非 null = 终局结论（UpToDate / UpdateAvailable），编排立即返回。
 * [extractSha256] 只在段 1（官方端点且最终来源可信）为 true：只有那时 body 里的摘要才可信；
 * 说明原文（notes）两段都读，只用于弹窗展示。
 */
private fun jsonNode(
    json: String?,
    localVersionName: String,
    extractSha256: Boolean = false,
    flavor: String? = null,
    deviceAbis: List<String> = emptyList()
): ProbeResult? {
    json ?: return null
    val info = ReleaseJson.parse(
        json, extractSha256 = extractSha256, flavor = flavor, deviceAbis = deviceAbis
    ) ?: return null
    return when {
        !CompareVersions.isNewerAvailable(localVersionName, info.tag) -> ProbeResult.UpToDate
        info.asset != null -> {
            // 段 2 的 JSON 可能被镜像改写：URL 字段一律不信，用可信仓库常量 + 已校验资产名重建
            val url = UpdateConfig.directDownloadUrl(info.tag, info.asset.name)
            ProbeResult.UpdateAvailable(
                info.tag, info.asset.name, url, info.asset.sizeBytes, info.sha256, info.notes, flavor
            )
        }
        else -> null
    }
}

/** 段 3 的资产是推导出来的（恒非 null），结论只有最新/有新版两态 */
private fun decide(info: ReleaseInfo, localVersionName: String, flavor: String?): ProbeResult {
    val asset = info.asset!!
    return if (!CompareVersions.isNewerAvailable(localVersionName, info.tag)) {
        ProbeResult.UpToDate
    } else {
        ProbeResult.UpdateAvailable(
            info.tag, asset.name, UpdateConfig.directDownloadUrl(info.tag, asset.name),
            asset.sizeBytes, flavor = flavor
        )
    }
}
