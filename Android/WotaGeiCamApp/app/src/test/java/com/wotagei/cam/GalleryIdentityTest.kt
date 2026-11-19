package com.wotagei.cam

import com.wotagei.cam.media.failClosedIfAmbiguous
import com.wotagei.cam.media.pruneSelection
import com.wotagei.cam.media.representativePartIndex
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y27：选中态 / 系列 / 标签依赖裸 ID；菜单全部失配时回退旧快照；组卡动作用输入顺序第一个。
 *
 * 修复口径：
 * 1. 选中/待删集合与当前可见行求交（外部删片、撤权、他页签操作后旧 id 不残留）；
 * 2. 菜单目标全部失配时**关闭**，不再回退旧快照；部分失配只保留仍在列表里的成员；
 * 3. 组卡动作取代表段（`partIndex` 最小者），不拿输入顺序第一个；
 * 4. 行 URI 按真实 `VOLUME_NAME` 拼回原卷（保留原始卷身份），`_ID` 仍是稳定行身份。
 */
class GalleryIdentityTest {

    // ---------- 纯函数真值表 ----------

    @Test
    fun `选中态只留下仍在列表里的id`() {
        assertEquals(setOf(2L), pruneSelection(setOf(1L, 2L, 3L), setOf(2L, 4L)))
        assertEquals(emptySet<Long>(), pruneSelection(setOf(9L), setOf(1L)))
        assertEquals(setOf(1L, 2L), pruneSelection(setOf(1L, 2L), setOf(1L, 2L, 3L)))
        assertEquals(emptySet<Long>(), pruneSelection(emptySet(), setOf(1L)))
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

    // ---------- 接线 ----------

    private fun flattened(rel: String, fn: String): String =
        KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel)), fn)
        )

    @Test
    fun `删片后选中与待删集合必须求交`() {
        val screen = flattened("media/GalleryScreen.kt", "GalleryScreen")
        assertTrue("必须调用 pruneSelection", screen.contains("pruneSelection("))
        assertTrue("选中集合要收敛", screen.contains("selectedIds = kept"))
        assertTrue("待删集合也要收敛", screen.contains("purgeIds = purgeKept"))
    }

    @Test
    fun `菜单全部失配即关闭_不许回退旧快照`() {
        val screen = flattened("media/GalleryScreen.kt", "GalleryScreen")
        assertFalse("不得再回退 requested 旧快照", screen.contains(".ifEmpty { requested }"))
        assertTrue("失配时必须关闭菜单", screen.contains("menuTargets = null"))
        assertTrue("菜单目标必须先在当前列表里验证", screen.contains("clips.firstOrNull { it.id == r.id }"))
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
            menu.contains("SeriesGroup.representativeOf(targets)")
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