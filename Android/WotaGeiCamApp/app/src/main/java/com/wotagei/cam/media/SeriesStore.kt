package com.wotagei.cam.media

import android.content.ContentUris
import android.net.Uri
import com.wotagei.cam.diag.Diag as Log
import com.wotagei.cam.record.VideoSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 分段归组落库的日志锚点（写库失败现场取证） */
private const val TAG = "WotaSeries"

/** 单段不成系列：只有 1 段的录制落库只会给媒体库添一条恒等于 Single 的死行 */
internal const val MIN_SERIES_PARTS = 2

/**
 * 分段归组落库前的中间形态（纯 Kotlin，不碰任何 Android 类型）。
 *
 * 为什么要这一层：`VideoSegment` 里的 `uri` 是 `android.net.Uri`，JVM 单测造不出来
 * （本仓没有 Robolectric/mockk，android.jar 的桩方法直接抛）。把规则所依赖的三个字段
 * 提成纯数据，规则才能被真值表覆盖——否则「uri 为空的段不收」这条只能靠人读代码。
 */
internal data class SeriesPartInput(
    val partIndex: Int,
    val uriStr: String?,
    val durationMs: Long
)

/**
 * 录制收尾时把分段写进 Room（`media_series` / `series_part`）。
 *
 * # 为什么落 Room 而不是留在内存
 * 归组关系必须**跨进程稳定**：重启后媒体库照样认得出「这两段是同一次录制」。
 * 成员关系落表、键是 (`seriesId`, `mediaId`)，读侧由 `SeriesDao.observeAll*` 原样恢复，
 * 不依赖任何内存态。
 *
 * 已知且接受的既有暴露面：外部 app 删文件后 MediaStore 复用同一个 `_ID`，会误挂。
 * 这与 `media_tag` 同源同暴露面（本仓既有已接受）；本 app 删除则由 `clearFor` + `pruneOrphans` 主动清。
 *
 * # 为什么整段 [NonCancellable]
 * 调用点在录制页的 `LaunchedEffect(runner) { runner.result.collect { … } }` 里，那是**组合作用域**：
 * 用户在写库途中离场（按 Home / 返回 / 切页）会把协程取消在半路 ⇒ 分段漏记 ⇒ 归组静默丢失。
 * 与 `MediaActions.runDeleteFinish` 同一个道理：这段是与调用方生死无关的自愈动作，不去指望
 * 调用方"恰好还没死"。只抗取消，**不抗进程被杀**（后台被系统杀掉时的漏记本片不做对账）。
 *
 * # 为什么异常全吞
 * 写库失败只是"归组没记上"，绝不许让 `SQLiteFullException` 冒进 UI 把录制页带崩。
 */
internal object SeriesStore {

    /**
     * 落库一条系列记录 + 逐段成员关系。
     *
     * 门槛：[seriesId] 为 0（没有归属）或纯函数算不出成系列（[seriesPartsToRecord] 返回空）
     * ⇒ 直接返回，什么都不写。
     */
    suspend fun record(db: WotaDb, seriesId: Long, startedAtMs: Long, parts: List<VideoSegment>) {
        if (seriesId == 0L) return
        val inputs = parts.map { SeriesPartInput(it.partIndex, it.uri?.toString(), it.durationMs) }
        val rows = seriesPartsToRecord(seriesId, inputs)
        if (rows.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                val dao = db.seriesDao()
                dao.upsertSeries(MediaSeries(seriesId = seriesId, startedAt = startedAtMs, partCount = rows.size))
                rows.forEach { dao.upsertPart(it) }
            }.onFailure {
                Log.e(TAG, "分段归组落库失败 seriesId=$seriesId parts=${rows.size}", it)
            }
        }
    }

    /**
     * 分段 → 成员行的纯函数（抽出来是为了能 JVM 单测：Room 要 Context，测不了）。
     *
     * 规则：
     * - `uriStr` 为 null 的段**不收**（没登记进 MediaStore 的段在媒体库里根本不存在，
     *   收了就是永久孤儿行，还会让 `partCount` 与实际可见成员对不上）；
     * - [parseId] 解析不出 id 的段**跳过**（`series_part.mediaId` 是主键的一半，塞 0 会让
     *   整组挂到一个根本不存在的媒体上）；
     * - 有效段不足 [MIN_SERIES_PARTS] ⇒ 返回空列表（单段不成系列，见下面的取舍说明）；
     * - [seriesId] 原样带上，`partIndex` 用引擎给的全局段序（跨轮转保持，组内排序靠它）。
     *
     * [parseId] 做成入参是为了把 Android 的 `ContentUris` 挡在测试之外；生产传默认值。
     *
     * **门槛为什么放在这里、而不是在 `record` 里判 `rows.size < 2`**：
     * 放外面的话这条规则 JVM 测不到（`record` 要 Context + Room），而它恰恰是最容易被
     * 顺手改成 `< 1` 的那种一行判据——改了不会有任何红灯。放进纯函数后它进了真值表。
     */
    internal fun seriesPartsToRecord(
        seriesId: Long,
        parts: List<SeriesPartInput>,
        parseId: (String) -> Long? = { s -> runCatching { ContentUris.parseId(Uri.parse(s)) }.getOrNull() }
    ): List<SeriesPart> {
        val rows = parts.mapNotNull { p ->
            val uriStr = p.uriStr ?: return@mapNotNull null
            val mediaId = parseId(uriStr) ?: return@mapNotNull null
            SeriesPart(
                seriesId = seriesId,
                partIndex = p.partIndex,
                mediaId = mediaId,
                uri = uriStr,
                durationMs = p.durationMs
            )
        }
        return if (rows.size < MIN_SERIES_PARTS) emptyList() else rows
    }
}
