package com.wotagei.cam

import com.wotagei.cam.media.LedgerRow
import com.wotagei.cam.media.MediaRowRef
import com.wotagei.cam.media.MediaTag
import com.wotagei.cam.media.RowIdentity
import com.wotagei.cam.media.RowProbe
import com.wotagei.cam.media.ledgerWriteMatches
import com.wotagei.cam.media.originalTrashPath
import com.wotagei.cam.media.aggregateRowUri
import com.wotagei.cam.media.ambiguousMediaIds
import com.wotagei.cam.media.failClosedIfAmbiguous
import com.wotagei.cam.media.legacyAggregateRowRefOf
import com.wotagei.cam.media.legacyTrashAliasTarget
import com.wotagei.cam.media.mediaRowRefOf
import com.wotagei.cam.media.mediaVolumeOf
import com.wotagei.cam.media.pruneSelection
import com.wotagei.cam.media.representativePartIndex
import com.wotagei.cam.media.sameStorageVolume
import com.wotagei.cam.media.tagsForRow
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y27：选中态 / 系列 / 标签依赖裸 ID；菜单全部失配时回退旧快照；组卡动作用输入顺序第一个。
 *
 * 修复口径（2026-11-20 收口）：
 * 1. **UI 身份一律用真实行 URI**（`clip.uri.toString()`）：跨卷同号 `_ID` 在聚合 MediaStore 上
 *    可以重号，列表键/选中态/待删集合/Pager 键/菜单重解析按裸 id 会串到另一卷的同号行；
 * 2. 选中/待删集合与当前可见行求交（外部删片、撤权、他页签操作后旧行不残留）；
 * 3. 菜单目标全部失配时**关闭**，不再回退旧快照；部分失配只保留仍在列表里的成员；
 * 4. 打开/对比的路由与加载贯穿**真实行 URI**（历史裸 ID 深链仍容，唯一命中否则关死）；
 *    Room 台账写入（收藏/tag）在裸 id 无歧义映射时才落库；
 * 5. 组卡动作取代表段（`partIndex` 最小者），不拿输入顺序第一个；
 * 6. 行 URI 按真实 `VOLUME_NAME` 拼回原卷（保留原始卷身份），`_ID` 仍是稳定行身份；
 * 7. 标签/系列/回收站旧账的**合成卷别名**只在「唯一且确认同一行」时兼容。
 */
class GalleryIdentityTest {

    // ---------- 纯函数真值表 ----------

    @Test
    fun `选中态只留下仍在列表里的键_同号不同卷互不牵连`() {
        assertEquals(setOf(2L), pruneSelection(setOf(1L, 2L, 3L), setOf(2L, 4L)))
        assertEquals(emptySet<Long>(), pruneSelection(setOf(9L), setOf(1L)))
        assertEquals(setOf(1L, 2L), pruneSelection(setOf(1L, 2L), setOf(1L, 2L, 3L)))
        assertEquals(emptySet<Long>(), pruneSelection(emptySet(), setOf(1L)))

        // 跨卷同号：_ID 都是 7，但两条行是不同 URI ⇒ 选中其中一条不得把另一条留下
        val external = "content://media/external/video/media/7"
        val sdCard = "content://media/1234-5678/video/media/7"
        assertEquals(setOf(sdCard), pruneSelection(setOf(external, sdCard), setOf(sdCard)))
    }

    @Test
    fun `代表段是partIndex最小者_脏数据排最后`() {
        assertEquals("首段下标 1（值为 0）", 1, representativePartIndex(listOf(2, 0, 1)))
        assertEquals("null 不能抢代表段", 2, representativePartIndex(listOf(null, 3, 1)))
        assertEquals("全 null 时取第一个（与 groupSpans 稳定排序一致）", 0, representativePartIndex(listOf(null, null)))
        assertEquals(0, representativePartIndex(listOf(5)))
        assertEquals("空列表没有代表段", -1, representativePartIndex(emptyList()))
    }

    @Test
    fun `裸ID反查只认唯一命中_跨卷同号即关死`() {
        assertEquals("唯一命中才是答案", 7L, failClosedIfAmbiguous(listOf(7L)))
        assertEquals("查无此行", null, failClosedIfAmbiguous(emptyList<Long>()))
        assertEquals(
            "跨卷同号命中两行 = 无法判定是哪一条 → 关死（不拿第一行）",
            null,
            failClosedIfAmbiguous(listOf(7L, 7L))
        )
        assertEquals("三行同样关死", null, failClosedIfAmbiguous(listOf(1L, 2L, 3L)))
    }

    @Test
    fun `跨卷同号按行URI判歧义_同一行的重复快照不算`() {
        val external = "content://media/external/video/media/7"
        val sdCard = "content://media/1234-5678/video/media/7"
        assertEquals(
            "同一 mediaId 命中两条不同卷 URI = 歧义",
            setOf(7L),
            ambiguousMediaIds(listOf(RowIdentity(7L, external), RowIdentity(7L, sdCard)))
        )
        assertEquals(emptySet<Long>(), ambiguousMediaIds(emptyList()))
        assertEquals(emptySet<Long>(), ambiguousMediaIds(listOf(RowIdentity(7L, external))))
        assertEquals(
            "同一条行的两次快照（同 URI）不是歧义",
            emptySet<Long>(),
            ambiguousMediaIds(listOf(RowIdentity(7L, external), RowIdentity(7L, external)))
        )
        assertEquals(
            "只有真的重号的行进集合，方向/顺序无关",
            setOf(1L, 3L),
            ambiguousMediaIds(
                listOf(
                    RowIdentity(1L, "a1"), RowIdentity(1L, "a2"),
                    RowIdentity(2L, "b1"), RowIdentity(2L, "b1"),
                    RowIdentity(3L, "c1"), RowIdentity(3L, "c2"), RowIdentity(3L, "c1")
                )
            )
        )
    }

    @Test
    fun `台账行与当前行按存储卷比对_合成卷等同主卷`() {
        assertEquals("external", mediaVolumeOf("content://media/external/video/media/7"))
        assertEquals("1234-5678", mediaVolumeOf("content://media/1234-5678/video/media/7"))
        assertEquals("非媒体 URI 没有卷名", null, mediaVolumeOf("file:///sdcard/a.mp4"))
        assertEquals("缺卷名段", null, mediaVolumeOf("content://media/"))
        assertEquals(null, mediaVolumeOf("content://media//video/media/7"))

        assertTrue(
            "录制插入拿到的合成卷 external 就是主卷 external_primary",
            sameStorageVolume("content://media/external/video/media/7", "content://media/external_primary/video/media/7")
        )
        assertTrue(
            "同卷同号（改名后 URI 也能对上卷）",
            sameStorageVolume("content://media/external_primary/video/media/7", "content://media/external_primary/video/media/9")
        )
        assertFalse(
            "跨卷同号不是同一行：系列成员不能盖到 SD 卡同号片上",
            sameStorageVolume("content://media/external_primary/video/media/7", "content://media/1234-5678/video/media/7")
        )
        assertFalse(
            "两个不同的可移动卷也不能互相认",
            sameStorageVolume("content://media/AAAA-1111/video/media/7", "content://media/BBBB-2222/video/media/7")
        )
        assertTrue(
            "台账侧解析不出卷名时保持旧行为（不凭猜测拆组）",
            sameStorageVolume("file:///sdcard/a.mp4", "content://media/1234-5678/video/media/7")
        )
        assertTrue(
            "当前行解析不出卷名时同样保持旧行为",
            sameStorageVolume("content://media/external/video/media/7", "garbage")
        )
    }

    // ---------- 行 URI 路由身份（Y27 正常播放/对比） ----------

    @Test
    fun `行URI解出具体卷与id_聚合别名一律拒绝`() {
        assertEquals(
            MediaRowRef("external_primary", 7L),
            mediaRowRefOf("content://media/external_primary/video/media/7")
        )
        assertEquals(
            MediaRowRef("1234-5678", 42L),
            mediaRowRefOf("content://media/1234-5678/video/media/42")
        )
        assertEquals(
            "合成卷 external 是跨所有卷的聚合集合，不能当行身份（旧深链的歧义键）",
            null,
            mediaRowRefOf("content://media/external/video/media/7")
        )
        assertEquals("段数不对", null, mediaRowRefOf("content://media/external_primary/video/media"))
        assertEquals("非视频集合", null, mediaRowRefOf("content://media/external_primary/audio/media/7"))
        assertEquals("id 非正", null, mediaRowRefOf("content://media/external_primary/video/media/0"))
        assertEquals("id 非数字", null, mediaRowRefOf("content://media/external_primary/video/media/abc"))
        assertEquals("别的 provider", null, mediaRowRefOf("content://com.other/x/video/media/7"))
        assertEquals("file URI 不是 MediaStore 行", null, mediaRowRefOf("file:///sdcard/a.mp4"))
        assertEquals(
            "查询串不参与段数判定",
            MediaRowRef("external_primary", 7L),
            mediaRowRefOf("content://media/external_primary/video/media/7?x=1")
        )
    }

    @Test
    fun `旧聚合账键形状与构造口径一致`() {
        assertEquals("content://media/external/video/media/9", aggregateRowUri(9L))
        assertEquals(
            MediaRowRef("external", 9L),
            legacyAggregateRowRefOf(aggregateRowUri(9L))
        )
        assertEquals(
            "具名主卷不是旧别名",
            null,
            legacyAggregateRowRefOf("content://media/external_primary/video/media/9")
        )
    }

    @Test
    fun `合成卷台账只在该id唯一且是主卷时认主卷`() {
        val ledger = "content://media/external/video/media/7"
        val primary = "content://media/external_primary/video/media/7"
        val sd = "content://media/1234-5678/video/media/7"
        assertTrue("唯一同号行时旧账的合成卷按主卷认", sameStorageVolume(ledger, primary, sameIdRows = 1))
        assertFalse(
            "同号有两行时旧账无法证明是哪一条（旧聚合读取把 SD 卡行也记成 external）",
            sameStorageVolume(ledger, primary, sameIdRows = 2)
        )
        assertFalse("合成卷永远不认 SD 卡行", sameStorageVolume(ledger, sd))
        assertTrue(
            "具名卷之间照旧按卷比，不受同号行数影响",
            sameStorageVolume(primary, primary, sameIdRows = 3)
        )
    }

    @Test
    fun `回收站旧账别名只在唯一且名字路径都对上时归一`() {
        val ledger = aggregateRowUri(7L)
        val primary = "content://media/external_primary/video/media/7"
        val sd = "content://media/1234-5678/video/media/7"
        val one = listOf(RowProbe(primary, "clip.mp4", "Movies/WotaGeiCam/Camera"))
        assertEquals(
            "唯一主卷行 + 名字/相对路径一致 = 确认同一行",
            primary,
            legacyTrashAliasTarget(ledger, "clip.mp4", "Movies/WotaGeiCam/Camera", one)
        )
        assertEquals(
            "同 id 两条行 = 无法确认",
            null,
            legacyTrashAliasTarget(
                ledger, "clip.mp4", "Movies/WotaGeiCam/Camera",
                one + RowProbe(sd, "clip.mp4", "Movies/WotaGeiCam/Camera")
            )
        )
        assertEquals(
            "确认不了同一个文件（ID 复用）就不归一到新片",
            null,
            legacyTrashAliasTarget(ledger, "old.mp4", "Movies/WotaGeiCam/Camera", one)
        )
        assertEquals(
            "相对路径不一致同样不认",
            null,
            legacyTrashAliasTarget(ledger, "clip.mp4", "Movies/WotaGeiCam/Other", one)
        )
        assertEquals(
            "同 id 唯一但落在 SD 卡上：合成卷别名只可能指主卷，不认",
            null,
            legacyTrashAliasTarget(
                ledger, "clip.mp4", "Movies/WotaGeiCam/Camera",
                listOf(RowProbe(sd, "clip.mp4", "Movies/WotaGeiCam/Camera"))
            )
        )
        assertEquals(
            "具名卷账键不需要归一",
            null,
            legacyTrashAliasTarget(primary, "clip.mp4", "Movies/WotaGeiCam/Camera", one)
        )
        assertEquals("零行不能放行", null, legacyTrashAliasTarget(ledger, "clip.mp4", "Movies/WotaGeiCam/Camera", emptyList()))
    }

    @Test
    fun `台账拒绝只剩另一卷同号或同URI换片`() {
        val primary = LedgerRow(MediaRowRef("external_primary", 7L), "/primary/one.mp4", 15L)
        val sd = LedgerRow(MediaRowRef("1234-5678", 7L), "/sd/one.mp4", 15L)
        assertTrue(ledgerWriteMatches(primary, listOf(primary)))
        assertTrue(ledgerWriteMatches(primary, listOf(primary, primary)))
        assertFalse(ledgerWriteMatches(primary, listOf(sd)))
        assertFalse(ledgerWriteMatches(primary, listOf(primary, sd)))
        assertFalse(ledgerWriteMatches(primary, listOf(primary.copy(addedSec = 16L))))
        assertFalse(ledgerWriteMatches(primary, listOf(primary, null)))
        assertFalse(ledgerWriteMatches(primary, emptyList()))
        assertFalse(ledgerWriteMatches(null, listOf(primary)))
    }

    @Test
    fun `读标签用路径和行URI_另一卷卸载也不串标签`() {
        val uri = "content://media/1234-5678/video/media/7"
        val tags = listOf(
            MediaTag(mediaId = 7L, path = "/sd/a.mp4", tag = "like", createdAt = 0L),
            MediaTag(mediaId = 7L, path = uri, tag = "风景", createdAt = 1L),
            MediaTag(mediaId = 7L, path = "/primary/a.mp4", tag = "别的片", createdAt = 2L)
        )
        val original = requireNotNull(originalTrashPath("/sd/.trashed-1234-a.mp4"))
        assertEquals(setOf("like", "风景"), tagsForRow(tags, setOf(uri, original)))
        assertEquals(emptySet<String>(), tagsForRow(tags, setOf("/tried/a.mp4")))
    }

    // ---------- 接线 ----------

    private fun flattened(rel: String, fn: String): String =
        KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel)), fn)
        )

    @Test
    fun `删片后选中与待删集合必须按行URI求交`() {
        val screen = flattened("media/GalleryScreen.kt", "GalleryScreen")
        assertTrue("必须调用 pruneSelection", screen.contains("pruneSelection("))
        assertTrue("选中集合要收敛", screen.contains("selectedKeys = kept"))
        assertTrue("待删集合也要收敛", screen.contains("purgeKeys = purgeKept"))
        assertTrue(
            "可见集合必须按真实行 URI 建（裸 _ID 跨卷会撞）",
            screen.contains("clips.mapTo(HashSet()) { rowKeyOf(it) }")
        )
        assertFalse("选择/待删状态不得再以裸 id 命名（回退即跨卷互相牵连）", screen.contains("selectedIds") || screen.contains("purgeIds"))
        assertFalse("多选的过滤不得再按裸 id 比较", screen.contains(".id in selectedKeys") || screen.contains(".id in purgeKeys"))
    }

    @Test
    fun `列表与Pager键必须走真实行URI`() {
        val timeline = flattened("media/GalleryScreen.kt", "TimelineList")
        assertTrue("时间线的 LazyColumn 键必须是行 URI", timeline.contains("items(g.clips, key = { rowKeyOf(it) })"))
        assertTrue("时间线代表段索引必须按行 URI 建", timeline.contains("entries.associateBy { rowKeyOf(it.clip) }"))
        assertTrue("时间线按行 URI 查 entry", timeline.contains("entryByRep[rowKeyOf(clip)]"))
        assertTrue("时间线只保留代表段也必须按行 URI 判", timeline.contains("entryByRep.containsKey(rowKeyOf(it))"))

        val grid = flattened("media/GalleryScreen.kt", "ClipGrid")
        assertTrue("网格的 LazyGrid 键必须是行 URI", grid.contains("items(reps, key = { rowKeyOf(it) })"))
        assertTrue("网格选中态必须按行 URI 判", grid.contains("selected = rowKeyOf(clip) in selectedKeys"))
        assertTrue("网格代表段索引必须按行 URI 建", grid.contains("entries.associateBy { rowKeyOf(it.clip) }"))

        val full = flattened("media/GalleryScreen.kt", "FullscreenBrowse")
        assertTrue(
            "Pager 页键必须显式给真实行 URI（默认 index 键会在顺序变化时错位/同号重复键崩）",
            full.contains("key = { index -> clips.getOrNull(index)?.let { rowKeyOf(it) } ?: index }")
        )

        val picker = flattened("media/GalleryScreen.kt", "ClipPickerDialog")
        assertTrue("对比选片器的网格键也必须走行 URI", picker.contains("items(clips, key = { rowKeyOf(it) })"))
    }

    @Test
    fun `菜单全部失配即关闭_重解析按行URI`() {
        val screen = flattened("media/GalleryScreen.kt", "GalleryScreen")
        assertFalse("不得再回退 requested 旧快照", screen.contains(".ifEmpty { requested }"))
        assertTrue("失配时必须关闭菜单", screen.contains("menuTargets = null"))
        assertTrue("菜单目标必须先在当前列表里按行 URI 重解析", screen.contains("requested.mapNotNull { r -> liveByKey[rowKeyOf(r)] }"))
        assertTrue("行 URI → 当前行快照的索引必须建出来", screen.contains("clips.associateBy { rowKeyOf(it) }"))
        assertFalse(
            "不得再按裸 id 重解析（跨卷同号会拿另一卷的行顶替）",
            screen.contains("clips.firstOrNull { it.id == r.id }")
        )
    }

    @Test
    fun `组卡动作使用代表段`() {
        val screen = flattened("media/GalleryScreen.kt", "GalleryScreen")
        assertTrue(
            "对比必须开代表段（首段）而不是 targets.first()",
            screen.contains("SeriesGroup.representativeOf(targets)")
        )
        assertFalse("不得再按 targets.first() 开对比", screen.contains("onCompare(targets.first().id)"))
        val menu = flattened("media/GalleryScreen.kt", "CardMenu")
        assertTrue(
            "组卡的单片动作（收藏/标签/重命名）也要落在代表段",
            menu.contains("clip = SeriesGroup.representativeOf(targets) ?: targets.first()")
        )
        assertFalse("CardMenu 不得再拿输入顺序第一个当单片目标", menu.contains("clip = targets.first()"))
        val group = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/SeriesGroup.kt")),
                "representativeOf"
            )
        )
        assertTrue("代表段必须走同一枚纯函数", group.contains("representativePartIndex("))
    }

    @Test
    fun `打开与对比的路由及加载贯穿真实行URI`() {
        val screen = flattened("media/GalleryScreen.kt", "GalleryScreen")
        assertTrue(
            "打开必须把整条行交出去（行 URI 才是跨卷唯一身份）",
            screen.contains("onOpen(clip)")
        )
        assertTrue("对比同理", screen.contains("onCompare(clip)"))
        assertFalse(
            "跨卷同号不再靠横幅拒绝：路由带行 URI 后没有歧义可拒",
            screen.contains("gallery_row_ambiguous else onOpen(") ||
                screen.contains("gallery_row_ambiguous else onCompare(")
        )
        assertTrue(
            "组卡对比仍走同一入口（代表段整条行交出去）",
            screen.contains("onCompare = { compareRow(SeriesGroup.representativeOf(targets) ?: targets.first()) }")
        )

        val navRaw = KotlinSourceScan.mainSourceText("media/GalleryScreen.kt")
        val nav = KotlinSourceScan.codeOnly(navRaw)
        assertTrue(
            "播放路由模板必须带行 URI 可选查询参数",
            navRaw.contains("const val PLAYER = \"player/{mediaId}?row={row}\"")
        )
        assertTrue(
            "对比路由模板必须带行 URI 可选查询参数",
            navRaw.contains("const val COMPARE = \"compare?leftMediaId={leftMediaId}&row={row}\"")
        )
        assertTrue("行 URI 进路由前必须编码（安全编码路径）", nav.contains("Uri.encode(row.toString())"))
        assertTrue("路由参数回读要有防御性解码", nav.contains("Uri.decode("))

        val main = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/MainActivity.kt"))
        val root = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(main, "WotaRoot"))
        assertTrue(
            "图库发起播放必须带整条行（id + 行 URI）",
            root.contains("nav.navigate(WotaNav.player(clip.id, clip.uri))")
        )
        assertTrue(
            "图库发起对比必须带整条行",
            root.contains("nav.navigate(WotaNav.compare(clip.id, clip.uri))")
        )
        assertTrue("播放页路由必须读行 URI 参数", root.contains("rowUri = WotaNav.rowUriOf("))
        assertTrue("对比左片路由必须读行 URI 参数", root.contains("leftRowUri = WotaNav.rowUriOf("))

        val player = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt"))
        assertTrue(
            "播放页有行 URI 时必须按具体卷钉行加载",
            player.contains("repo.clipByRowUri(rowUri) else repo.clipById(mediaId)")
        )
        val compare = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/CompareScreen.kt"))
        assertTrue(
            "对比左片同上（无行 URI 的旧深链仍走 clipById）",
            compare.contains("repo.clipByRowUri(leftRowUri) else repo.clipById(leftMediaId)")
        )

        val repo = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/MediaRepo.kt"))
        val byRow = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(repo, "clipByRowUri"))
        assertTrue("行 URI 必须先解成具体卷 + id", byRow.contains("mediaRowRefOf(rowUri.toString())"))
        assertTrue(
            "按卷反查（聚合集合上同号另一卷的行不能顶替）",
            byRow.contains("queryById(ref.mediaId, s.local, ref.volume)")
        )
        assertFalse(
            "行 URI 解不出来时不许退回裸 id 猜",
            byRow.contains("clipById(") || byRow.contains("queryById(ref.mediaId, s.local)")
        )
        assertTrue(
            "单条视图的标签也按本行路径匹配，不按裸 id 直连",
            byRow.contains("tagsForRow(") && byRow.contains("clip.tagPathKeys()")
        )
    }

    @Test
    fun `突变_打开退回裸id必红`() {
        val screen = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/GalleryScreen.kt"))
        val mutant = screen.replace("val openRow: (VideoClip) -> Unit = { clip -> onOpen(clip) }",
            "val openRow: (VideoClip) -> Unit = { clip -> onOpen(clip.id) }")
        assertTrue("突变体必须真的改了源码", mutant != screen)
        assertFalse(
            "退回裸 id 的打开（跨卷同号会开错片）必须报红",
            KotlinSourceScan.bodyOf(mutant, "GalleryScreen").contains("onOpen(clip)")
        )
    }

    @Test
    fun `标签与系列读端不共用旧裸键`() {
        val repo = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/MediaRepo.kt"))
        val join = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(repo, "joinTags"))
        assertTrue("先按 id 建索引，再核对本行路径", join.contains("tags.groupBy { it.mediaId }") &&
            join.contains("tagsForRow(candidates, clip.tagPathKeys())"))
        val ann = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(repo, "annotateSeries"))
        assertTrue("同一 id 的多条成员记录逐条比（不再 last-wins 覆盖）", ann.contains("MutableList<SeriesPart>"))
        assertTrue(
            "合成卷账键只在同 id 唯一时认主卷",
            ann.contains("sameStorageVolume(it.uri, c.uri.toString(), sameIdRows)")
        )
        assertTrue("同 id 的不同行 URI 数要先数出来", ann.contains("rowUris"))
        val dialog = flattened("media/GalleryScreen.kt", "MediaTagDialog")
        assertTrue("标签弹层不能重新用裸 id 渲染", dialog.contains("tagsForRow(it, pathKeys)"))
    }

    @Test
    fun `回收站旧账别名只经唯一确认后归一`() {
        val repoRaw = KotlinSourceScan.mainSourceText("media/MediaRepo.kt")
        val repo = KotlinSourceScan.codeOnly(repoRaw)
        val rec = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(repo, "reconcileLegacyTrashAliases"))
        assertTrue("必须用纯判据确认同一行", rec.contains("legacyTrashAliasTarget("))
        assertTrue("不能靠已筛选列表证明唯一", rec.contains("queryRowsById(item.mediaId, failures = failures)"))
        assertTrue("确认后按归一目标改写账键", rec.contains("trashDao.rewriteUri("))
        assertTrue("API 30+ 系统回收站才有权威，不做别名归一", rec.contains("SYSTEM_TRASH_SUPPORTED"))
        assertTrue("改写过就重读本地账快照", rec.contains("if (changed) trashDao.observeAll().first() else items"))
        val dao = KotlinSourceScan.mainSourceText("media/WotaDb.kt")
        assertTrue("目标键的冲突检查必须和写入在同一句 SQL", dao.contains("AND NOT EXISTS (SELECT 1 FROM trash_item WHERE uri = :newUri)"))
    }

    @Test
    fun `台账写入无歧义映射才落库`() {
        val actions = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/MediaActions.kt"))
        assertTrue(actions.contains("= writeTag(clip, TagSpace.LIKE, liked)"))
        assertTrue(actions.contains("= writeTag(clip, tag, false)"))
        assertTrue(KotlinSourceScan.bodyOf(actions, "addTag").contains("return writeTag(clip, tag, true)"))
        val write = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(actions, "writeTag"))
        val check = write.indexOf("if (!repo.canWriteLedgerFor(clip)) return@withContext false")
        assertTrue("先确认目标仍是唯一同一行，才允许落账", check >= 0 &&
            write.indexOf("dao.insertUnclaimed(") > check && write.indexOf("dao.deleteForPaths(") > check)
        assertTrue("拒绝必须回面向用户的横幅", write.contains("R.string.gallery_row_ambiguous"))
        val guard = flattened("media/MediaRepo.kt", "canWriteLedgerFor")
        assertTrue(guard.contains("failures.isEmpty() && ledgerWriteMatches(clip.ledgerRow(), hits.map { it.ledgerRow() })"))
    }

    @Test
    fun `行URI按真实卷拼回_保留原始卷身份`() {
        val rows = flattened("media/MediaRepo.kt", "readRows")
        assertTrue("投影必须带 VOLUME_NAME", rows.contains("VOLUME_NAME"))
        assertTrue("必须按卷拼回内容 URI", rows.contains("getContentUri("))
        assertTrue("拿不到卷名才退回聚合集合", rows.contains("EXTERNAL_CONTENT_URI"))
        val pick = flattened("media/MediaRepo.kt", "mediaStoreClipByUri")
        assertTrue(
            "手选 URI 反查必须按 URI 自带卷查（聚合集合上不同卷的 _ID 可以重号）",
            pick.contains("queryById(id, volume = uri.pathSegments.first())")
        )
    }

    @Test
    fun `裸ID反查多行命中按查无此片返回`() {
        val q = flattened("media/MediaRepo.kt", "queryById")
        assertTrue("全部命中行一起裁决", q.contains("failClosedIfAmbiguous(hits) ?: return null"))
        assertTrue("不能把半截结果当唯一", q.contains("if (failures.isNotEmpty()) return null"))
        val rows = flattened("media/MediaRepo.kt", "queryRowsById")
        assertTrue("先按具体卷查，再核对回来的行的卷", rows.contains("mediaRowRefOf(it.uri.toString())?.volume == concreteVolume"))
        assertTrue("系统回收站与活行在同一查询里裁决", rows.contains("includeTrash = SYSTEM_TRASH_SUPPORTED"))
        val read = flattened("media/MediaRepo.kt", "readCursor")
        assertTrue(read.contains("putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)"))
        assertTrue(
            "旧深链的合成卷名 external 仍须当聚合卷处理（否则老 URI 反查会看不见跨卷行）",
            rows.contains("it != MediaStore.VOLUME_EXTERNAL")
        )
    }
}