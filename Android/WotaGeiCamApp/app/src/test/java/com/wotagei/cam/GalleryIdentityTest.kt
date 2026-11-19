package com.wotagei.cam

import com.wotagei.cam.media.RowIdentity
import com.wotagei.cam.media.ambiguousMediaIds
import com.wotagei.cam.media.failClosedIfAmbiguous
import com.wotagei.cam.media.mediaVolumeOf
import com.wotagei.cam.media.pruneSelection
import com.wotagei.cam.media.representativePartIndex
import com.wotagei.cam.media.sameStorageVolume
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
 * 4. 裸 id 无法无歧义映射的出口显式拒绝：打开/对比（路由仍只吃 Long）回横幅，
 *    Room 台账写入（收藏/tag，schema 不动）同样关死；
 * 5. 组卡动作取代表段（`partIndex` 最小者），不拿输入顺序第一个；
 * 6. 行 URI 按真实 `VOLUME_NAME` 拼回原卷（保留原始卷身份），`_ID` 仍是稳定行身份。
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
    fun `跨卷同号的打开与对比显式拒绝`() {
        val screen = flattened("media/GalleryScreen.kt", "GalleryScreen")
        assertTrue("歧义集合必须由当前可见行算出", screen.contains("ambiguousMediaIds(rowIdentities(clips))"))
        assertTrue(
            "打开只吃 Long 的路由命中歧义时必须拒绝并回横幅",
            screen.contains("if (clip.id in ambiguousIds) banner = R.string.gallery_row_ambiguous else onOpen(clip.id)")
        )
        assertTrue(
            "对比同理",
            screen.contains("if (clip.id in ambiguousIds) banner = R.string.gallery_row_ambiguous else onCompare(clip.id)")
        )
        assertTrue(
            "组卡对比也必须过同一道拒绝（代表段可能跨卷同号）",
            screen.contains("onCompare = { compareRow(SeriesGroup.representativeOf(targets) ?: targets.first()) }")
        )
    }

    @Test
    fun `台账写入无歧义映射才落库`() {
        val actions = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/MediaActions.kt"))
        for (fn in listOf("setLike", "addTag", "removeTag")) {
            assertTrue(
                "$fn 必须先过 mediaId 歧义闸门（裸 id 台账会串到另一卷同号行）",
                KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(actions, fn))
                    .contains("ambiguousLedgerRefusal(clip)?.let { return it }")
            )
        }
        val guard = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(actions, "ambiguousLedgerRefusal"))
        assertTrue("闸门必须查仓库的裸 id 唯一性", guard.contains("repo.ledgerMediaIdIsAmbiguous(clip.id)"))
        assertTrue("拒绝必须回面向用户的横幅", guard.contains("R.string.gallery_row_ambiguous"))

        val repo = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/MediaRepo.kt"))
        val amb = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(repo, "ledgerMediaIdIsAmbiguous"))
        assertTrue("必须把 pending 半成品排除在可见行外", amb.contains("IS_PENDING"))
        assertTrue("系统回收站的行也算当前可见行", amb.contains("trashedMatching(MediaScope.AllVideos, failures)"))
        assertTrue("查询失败必须 fail closed（证明不了唯一就不写）", amb.contains("failures.isNotEmpty()"))
        assertTrue("必须按不同行 URI 去重后再裁决", amb.contains("distinct()") && amb.contains("take(2)"))
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
        assertTrue("live 查询必须取回全部命中行再裁决", q.contains("failClosedIfAmbiguous(live)"))
        assertTrue("多行命中必须走关死分支", q.contains("live.size > 1"))
        assertFalse(
            "不得再对聚合查询直接取第一条活行（旧写法会把同号另一卷的视频顶上来）",
            q.contains(").firstOrNull()")
        )
        assertTrue("回收站兜底也必须先裁决", q.contains("failClosedIfAmbiguous(trashHits)"))
        assertTrue("回收站多行命中同样关死", q.contains("trashHits.size > 1"))
        assertTrue(
            "带卷反查时回收站兜底也要按卷收窄，另一卷同号的废片不能顶替",
            q.contains("it.uri.pathSegments.firstOrNull() == concreteVolume")
        )
        assertTrue(
            "旧深链的合成卷名 external 仍须当聚合卷处理（否则老 URI 反查会看不见跨卷行）",
            q.contains("it != MediaStore.VOLUME_EXTERNAL")
        )
    }
}