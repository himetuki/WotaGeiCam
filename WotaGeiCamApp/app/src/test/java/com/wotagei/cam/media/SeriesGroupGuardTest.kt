package com.wotagei.cam.media

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分段系列读侧与呈现的**接线守卫**（#5 Step 4c / 4d）。
 *
 * [SeriesGroupTest] 钉归组规则的真值表；这里钉那些「只有源码能看见、错了不会红」的东西：
 *
 * 1. **红线：schema 一个字不许动**。`WotaDb` 是 `version = 1` + `fallbackToDestructiveMigration()`，
 *    全仓无 Migration。给实体加字段或加 `@Index` 都是改 schema ⇒ 升级即重建库 ⇒
 *    清空用户的收藏 / 标签 / 回收站 / 系列。
 * 2. **读侧接线**：`MediaRepo.clips` 必须把两条 series 流的结果送进 `annotateSeries`，
 *    否则归组永远建不起来而真值表全绿。
 * 3. **VideoClip 的 series 字段必须带默认值**：`readRows` 的构造点一个字都不改。
 * 4. **动作扇出**：多选条必须经 `expandSeriesTargets` 展开，否则「只把首段移进回收站」。
 * 5. **FullscreenBrowse 不许被改**：它吃扁平 `clips`，pager 语义零改动。
 * 6. **收尾**：`finishDelete` 必须调 `seriesDao.pruneOrphans()`。
 *
 * 判据用哪把尺：**含字符串字面量的判据走原文**（`codeOnly` 会把字面量抹成空格，用它判必假），
 * 表达式体函数按声明切片（`bodyOf` 定位不到 `= xxx` 的函数，撞上会直接抛）。
 */
class SeriesGroupGuardTest {

    private fun masked(rel: String) = codeOnly(mainSourceText(rel))
    private fun body(rel: String, fn: String) = flatten(bodyOf(masked(rel), fn))

    /**
     * 表达式体切片：按声明起点切到下一个标记。
     *
     * 两端都必须是"找不到就抛"：`substringAfter` 找不到定界符时**原样返回整篇源码**，
     * `substringBefore` 同理 —— 那样切片会一路读到文件尾，把无关代码算成证据，
     * 函数被改名/挪走时守卫还会照样全绿。定位不到输入的守卫是最坏的一种守卫。
     */
    private fun exprBody(src: String, decl: String, until: String): String {
        if (!src.contains(decl)) throw AssertionError("找不到声明 `$decl` —— 函数被改名/挪走，本守卫失去输入")
        if (!src.contains(until)) throw AssertionError("找不到切片终点标记 `$until` —— 结构变了，本守卫失去输入")
        val body = src.substringAfter(decl).substringBefore(until)
        if (body.length >= src.length) throw AssertionError("切片没有缩短（终点在声明之前？）")
        return flatten(body)
    }

    // ------------------------------------------------------------------ 红线：schema

    @Test
    fun `红线：两个系列实体的定义一个字不许动`() {
        val db = mainSourceText("media/WotaDb.kt") // 实体注解里全是字符串字面量 ⇒ 必须走原文
        val ms = db.substringAfter("@Entity(tableName = \"media_series\")").substringBefore("@Dao")
        val sp = db.substringAfter("@Entity(tableName = \"series_part\"").substringBefore("@Dao")
        // 「不许出现 @Index」判的是**代码**不是注释：本仓的注释里就写着「绝不加 @Index」这句话，
        // 用原文判会把自己骂红（这正是 codeOnly 存在的理由）。
        assertFalse("MediaSeries 不许出现 @Index（加索引同样改 schema）", codeOnly(ms).contains("@Index"))
        assertFalse("SeriesPart 不许出现 @Index", codeOnly(sp).contains("@Index"))
        assertTrue("MediaSeries 主键必须仍是 Long", ms.contains("@PrimaryKey val seriesId: Long"))
        assertTrue("MediaSeries 仍只有三列", ms.contains("val startedAt: Long") && ms.contains("val partCount: Int"))
        assertTrue("SeriesPart 主键仍是 (seriesId, partIndex)", sp.contains("primaryKeys = [\"seriesId\", \"partIndex\"]"))
        assertTrue(
            "SeriesPart 仍只有五列",
            sp.contains("val seriesId: Long") && sp.contains("val partIndex: Int") &&
                sp.contains("val mediaId: Long") && sp.contains("val uri: String") && sp.contains("val durationMs: Long")
        )
    }

    @Test
    fun `版本仍是1且仍无Migration`() {
        val db = mainSourceText("media/WotaDb.kt")
        assertTrue("版本仍是 1（升版本就必须配 Migration，本片不升）", db.contains("version = 1"))
        assertTrue("破坏性迁移仍在（所以 schema 红线才这么要紧）", db.contains("fallbackToDestructiveMigration()"))
        assertFalse("本片不许加 Migration（加了实体就得能迁，红线的前提就变了）", Regex("\\bMigration\\s*\\(").containsMatchIn(db))
        assertFalse("不许调 addMigrations", db.contains("addMigrations"))
    }

    @Test
    fun `SeriesDao只许加查询不许动既有语句`() {
        val dao = mainSourceText("media/WotaDb.kt")
            .substringAfter("interface SeriesDao {").substringBefore("@Database")
        assertTrue("必须有 observeAllSeries", dao.contains("fun observeAllSeries(): Flow<List<MediaSeries>>"))
        assertTrue("必须有 observeAllParts", dao.contains("fun observeAllParts(): Flow<List<SeriesPart>>"))
        assertTrue("必须有 pruneOrphans", dao.contains("suspend fun pruneOrphans()"))
        assertTrue("既有的 partsOf / partOfMedia / clearFor 都得留着", dao.contains("suspend fun partsOf(") && dao.contains("suspend fun partOfMedia(") && dao.contains("suspend fun clearFor("))
    }

    // ------------------------------------------------------------------ 读侧接线

    @Test
    fun `MediaRepo必须把两条series流接进clips`() {
        val repo = masked("media/MediaRepo.kt")
        assertTrue("triggers 必须并入 observeAllSeries 与 observeAllParts", repo.contains("observeAllSeries()") && repo.contains("observeAllParts()"))
        assertTrue("必须有 seriesDao", repo.contains("private val seriesDao = db.seriesDao()"))
        // `clips` 是表达式体（`= triggers()...`），bodyOf 定位不到 ⇒ 按声明切片
        val clips = exprBody(
            mainSourceText("media/MediaRepo.kt"),
            "fun clips(scope: MediaScope",
            "单条查询"
        )
        assertTrue(
            "clips 必须把 snapshot 的 series/parts 送进 annotateSeries",
            clips.contains("annotateSeries(") && clips.contains("s.series") && clips.contains("s.parts")
        )
    }

    @Test
    fun `annotateSeries按mediaId建索引并带出段序与总数`() {
        // 表达式体 ⇒ 按声明切片
        val ann = exprBody(
            mainSourceText("media/MediaRepo.kt"),
            "internal fun annotateSeries(",
            "/**\n * 媒体仓库"
        )
        assertTrue("必须按 mediaId 建索引", ann.contains("byMedia"))
        assertTrue("必须带出 partIndex", ann.contains("partIndex = p.partIndex"))
        assertTrue("必须带出 partCount（取 media_series 那一列）", ann.contains("seriesParts = partCount[p.seriesId]"))
        assertTrue("查不到归属的行必须原样返回（单段片逐字不变）", ann.contains("?: return@map c"))
    }

    @Test
    fun `VideoClip的series字段必须全部带默认值`() {
        val decl = mainSourceText("media/MediaRepo.kt")
            .substringAfter("data class VideoClip(").substringBefore(") {")
        for (line in listOf("val seriesId: Long? = null", "val partIndex: Int? = null", "val seriesParts: Int = 1")) {
            assertTrue("VideoClip 的 `$line` 必须带默认值（否则 readRows 等所有构造点都得改）", decl.contains(line))
        }
    }

    // ------------------------------------------------------------------ 呈现与扇出

    @Test
    fun `时间线必须走归组且展开态只在屏内`() {
        val tl = exprBody(
            mainSourceText("media/GalleryScreen.kt"),
            "private fun TimelineList(",
            "/** 展开态下的子段行"
        )
        assertTrue("时间线必须调 SeriesGroup.galleryEntries（不调 = 归组永远不生效）", tl.contains("SeriesGroup.galleryEntries("))
        assertTrue("展开态必须是屏内 remember（不持久化）", tl.contains("var expandedSeries by remember { mutableStateOf(emptySet<Long>()) }"))
        assertTrue("只保留代表段出行（否则一次录制会在列表里出现两遍）", tl.contains("entryByRep.containsKey(it.id)"))
        assertTrue("系列行必须内联列出子段", tl.contains("TimelinePartRow("))
        assertTrue("组标题取代表段", tl.contains("entry?.title"))
        assertTrue("角标必须走 stringResource（Compose 里不许出现面向用户的中文串）", tl.contains("R.string.gallery_series_count"))
    }

    @Test
    fun `多选条必须经扇出展开否则只动首段`() {
        assertTrue(
            "多选必须经 SeriesGroup.expandSeriesTargets 展开（否则「只把首段移进回收站、其余还在原地」）",
            masked("media/GalleryScreen.kt").contains("SeriesGroup.expandSeriesTargets(")
        )
        val fan = exprBody(
            mainSourceText("media/SeriesGroup.kt"),
            "fun expandSeriesTargets(",
            "\n}"
        )
        assertTrue("扇出只在成组时发生（退化单片没有别的成员可动）", fan.contains("group.size >= MIN_GROUP_SIZE"))
        assertTrue("扇出必须去重（同组两个成员都被选中时不该动两遍）", fan.contains("distinct()"))
    }

    @Test
    fun `FullscreenBrowse继续吃扁平clips零改动`() {
        val screen = masked("media/GalleryScreen.kt")
        assertTrue("全屏浏览必须仍收 clips（而不是 entries）—— pager 语义零改动", screen.contains("FullscreenBrowse(clips,"))
        assertFalse("不许把 entries 喂给 FullscreenBrowse", screen.contains("FullscreenBrowse(entries"))
    }

    // ------------------------------------------------------------------ 收尾

    @Test
    fun `finishDelete必须调pruneOrphans`() {
        val fd = body("media/MediaActions.kt", "finishDelete")
        assertTrue("必须先清成员（clearFor）", fd.contains("seriesDao.clearFor(ids)"))
        assertTrue("必须收掉没人指的系列空壳头（pruneOrphans）", fd.contains("seriesDao.pruneOrphans()"))
        val clearAt = fd.indexOf("seriesDao.clearFor(ids)")
        val pruneAt = fd.indexOf("seriesDao.pruneOrphans()")
        assertTrue("两个调用都得在", clearAt >= 0 && pruneAt >= 0)
        assertTrue("prune 必须排在 clearFor 之后（反了就 prune 不掉刚清空的头）", pruneAt > clearAt)
    }

    // ------------------------------------------------------------------ 突变自证

    /**
     * 突变自证：逐条破坏接线，判据必须真的翻红。
     *
     * 每个突变都先断言「它确实改动了源码」——没改动的突变体在这条自证里等于什么都没证明
     * （本仓踩过的语义空操作突变坑）。
     */
    @Test
    fun `突变自证：摘掉接线判据必红`() {
        val screenRaw = mainSourceText("media/GalleryScreen.kt")
        val actionsRaw = mainSourceText("media/MediaActions.kt")
        val repoRaw = mainSourceText("media/MediaRepo.kt")

        // ① 扇出：把 expandSeriesTargets 换回原样 picked
        val noFan = screenRaw.replace(
            "SeriesGroup.expandSeriesTargets(clips.filter { it.id in selectedIds }, clips)",
            "clips.filter { it.id in selectedIds }"
        )
        assertTrue("突变体①必须真的改了源码", noFan != screenRaw)
        assertFalse("摘掉扇出后扇出接线判据必须翻假", codeOnly(noFan).contains("SeriesGroup.expandSeriesTargets("))

        // ② 收尾：摘掉 pruneOrphans
        val noPrune = actionsRaw.replace("seriesDao.pruneOrphans()", "")
        assertTrue("突变体②必须真的改了源码", noPrune != actionsRaw)
        assertFalse(
            "摘掉 pruneOrphans 后收尾判据必须翻假",
            flatten(bodyOf(codeOnly(noPrune), "finishDelete")).contains("seriesDao.pruneOrphans()")
        )

        // ③ 读侧：把 s.series / s.parts 换成空表（归组永远建不起来而真值表全绿）
        val noSeries = repoRaw.replace(
            "annotateSeries(joinTags(queryMediaStore(scope), s.tags), s.series, s.parts)",
            "annotateSeries(joinTags(queryMediaStore(scope), s.tags), emptyList(), emptyList())"
        )
        assertTrue("突变体③必须真的改了源码", noSeries != repoRaw)
        assertFalse(
            "摘掉 series/parts 后读侧接线判据必须翻假",
            exprBody(noSeries, "fun clips(scope: MediaScope", "单条查询").contains("s.series")
        )

        // ④ 互不代偿：摘掉收尾不得牵连接线；摘掉扇出不得牵连收尾
        // （否则"必红"可能只是某一条恒红 —— 那样三条断言里有一半是摆设）
        assertTrue(
            "摘掉 pruneOrphans 不得牵连读侧接线",
            exprBody(noPrune, "fun clips(scope: MediaScope", "单条查询").contains("s.series")
        )
        assertTrue(
            "摘掉扇出不得牵连收尾",
            flatten(bodyOf(codeOnly(noFan), "finishDelete")).contains("seriesDao.pruneOrphans()")
        )
    }
}
