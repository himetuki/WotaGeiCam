package com.wotagei.cam

import com.wotagei.cam.media.GalleryEmptyState
import com.wotagei.cam.media.galleryEmptyState
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「删除闸门那 900ms 不要显示『这里还没有视频』」守卫（2026-10-02 真机观察项）。
 *
 * 现象：播放器「彻底删除」→ 返回媒体库，「全部」页签**最多约 1.1s** 显示空列表（"这里还没有视频"）
 * 才填入。那不是数据没了，是收尾编排的**设计结果**：`runDeleteFinish` 先关闸（定版：列表刷新
 * 必须晚于播放页出栈），清缓存 → 出栈 → 900ms → 开闸 + invalidate。返回的媒体库是一次**新组合**，
 * `collectAsState(emptyList())` 在闸门期只有空列表可显示。
 *
 * 本轮的收窄口径（**不动仓库级缓存**、不动 `remember(repo, scope, filter)` 那两条键、不退回上一轮
 * 的 NonCancellable 修法）：只在 UI 侧把"闸门押后"与"真的空"分开——闸门为 true 时显示一只转圈，
 * 闸门一开真实快照自然替换。**没有**做"保留上一份非空列表"那条路：那条路会在切到空页签时闪一下
 * 别的页签的内容，还要防"闸门期旧内容被误当成新内容"（点得到已删视频），代价与风险都更大。
 *
 * 证伪点（各能单独抓一类回退）：
 * 1. 行为用例——纯函数 `galleryEmptyState` 的四格真值表（闸门期列表非空 ⇒ 仍是 LIST，这正是
 *    "媒体库内批量删除不清空内容"那条边界）；
 * 2. 桥桩——三个列表形态都必须把这个纯函数当唯一判据（漏一个形态 = 那个形态照旧显示空态）；
 * 3. 铁桩——闸门必须仍由删除收尾驱动（`holdListRefresh` / `releaseListRefresh` 还得挂在
 *    `finishDelete` → `runDeleteFinish` 上），且 `remember(repo, scope, filter)` 那两条键一字未动。
 */
class GalleryRefreshGateStateTest {

    // ---------- 一、纯函数真值表（运行时唯一判据，也是本条测试的判出力的来源） ----------

    @Test
    fun `闸门押后且列表为空_显示刷新中_不是空态`() {
        // 正是真机那 1.1s 的形态：新组合 + 闸门没开 ⇒ 唯一的真相是"正在刷新"
        assertEquals(GalleryEmptyState.REFRESHING, galleryEmptyState(clipsEmpty = true, listRefreshing = true))
    }

    @Test
    fun `闸门关着且列表为空_才是真空`() {
        // 首帧查询还没回来、或这个库这个页签本来就没有视频 ⇒ 空态（与改前逐字同值）
        assertEquals(GalleryEmptyState.EMPTY, galleryEmptyState(clipsEmpty = true, listRefreshing = false))
    }

    @Test
    fun `闸门押后但列表非空_不许显示刷新中`() {
        // 媒体库内批量彻底删除就是这一形态：闸门期内 collectAsState 保留**上一份**列表，
        // 把它判成 REFRESHING 等于把内容清空给用户看（那是本轮要避的新缺陷）
        assertEquals(GalleryEmptyState.LIST, galleryEmptyState(clipsEmpty = false, listRefreshing = true))
    }

    @Test
    fun `列表非空且闸门关着_正常列表`() {
        assertEquals(GalleryEmptyState.LIST, galleryEmptyState(clipsEmpty = false, listRefreshing = false))
    }

    // ---------- 二、桥桩：三个列表形态都要读这座桥 ----------

    @Test
    fun `三个列表形态的空态都必须走galleryEmptyState这座桥`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("media/GalleryScreen.kt"))
        for (name in listOf("TimelineList", "ClipGrid", "FullscreenBrowse")) {
            val body = bodyOf(masked, name)
            assertTrue(
                "$name 的空态必须读 galleryEmptyState（漏一个形态 = 那个形态在闸门期照旧显示「这里还没有视频」）",
                body.contains("galleryEmptyState(")
            )
            assertTrue(
                "$name 必须把裁决交给 EmptySlot（唯一出口，别在这里另写一支 if (clips.isEmpty())）",
                body.contains("EmptySlot(")
            )
            assertFalse(
                "$name 不许回退成裸 if (clips.isEmpty())（那正是本轮要关掉的空窗判定）",
                body.contains("if (clips.isEmpty())")
            )
        }
    }

    /** 闸门必须真的从仓库流到 UI（桥的输入不是凭空来的） */
    @Test
    fun `媒体库屏必须订阅仓库的listRefreshing`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("media/GalleryScreen.kt"))
        val body = bodyOf(masked, "GalleryScreen")
        assertTrue(
            "GalleryScreen 必须读 repo.listRefreshing（否则 UI 侧永远看不到闸门，刷新中落不了地）",
            body.contains("repo.listRefreshing")
        )
    }

    /** 只读视图：写入侧仍只有 hold/release 两个口子，UI 不许拿到写方法 */
    @Test
    fun `MediaRepo只把闸门当只读视图放出去`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("media/MediaRepo.kt"))
        assertTrue(
            "MediaRepo 必须暴露 listRefreshing（UI 侧唯一入口）",
            masked.contains("val listRefreshing") && masked.contains("refreshGate")
        )
        assertFalse(
            "曝出去的不许是 MutableStateFlow（那等于把写闸门的权力交给 UI）",
            masked.contains("val listRefreshing: MutableStateFlow")
        )
        // UI 侧只订阅、不开合：闸门的开合是删除收尾的编排（runDeleteFinish）
        val ui = codeOnly(KotlinSourceScan.mainSourceText("media/GalleryScreen.kt"))
        assertFalse("GalleryScreen 不许直接关闸", bodyOf(ui, "GalleryScreen").contains("holdListRefresh"))
        assertFalse("GalleryScreen 不许直接开闸", bodyOf(ui, "GalleryScreen").contains("releaseListRefresh"))
    }

    // ---------- 三、铁桩：不许为了这个改动动到别处 ----------

    /** 上一轮的门禁收益：那两条 remember 键一动就等于把"每重组重开一轮查询"请回来 */
    @Test
    fun `冷管道的两条remember键一字未动`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("media/GalleryScreen.kt"))
        val body = bodyOf(masked, "GalleryScreen")
        assertTrue(
            "remember(repo, scope, filter) 这条键不许动（动了每重组就重开一轮 MediaStore + Room 查询）",
            body.contains("remember(repo, scope, filter)")
        )
        assertTrue(
            "remember(repo) 这条键（customTags）不许动",
            body.contains("remember(repo)")
        )
    }

    /** 反"悄悄改成保留上一份快照"：初值仍必须是无辜的 emptyList()，别把进程级缓存塞进来 */
    @Test
    fun `clips的收集初值仍是emptyList_没有夹带进程级快照`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("media/GalleryScreen.kt"))
        val body = bodyOf(masked, "GalleryScreen")
        assertTrue(
            "clips 仍由 clipsFlow.collectState(emptyList()) 收集（本轮选的是「区分空态」，不是「保留旧列表」）",
            body.contains("clipsFlow.collectState(emptyList())")
        )
        // 只点名"保留最后一份非空列表"那条路的特征符号，不用笼统的 Snapshot——
        // 屏里本来就有 barSnapshot / msgSnapshot（多选条与横幅的快照），笼统判会误红
        assertFalse(
            "不许引入「最后一份非空列表」那种进程级快照（它会闪别的页签内容，还会让闸门期旧内容可点）",
            body.contains("lastNonEmpty") || body.contains("LastEmitted") || body.contains("GalleryListSnapshot")
        )
    }

    /** 闸门的开合仍归删除收尾管：hold/release 还得挂在 finishDelete 上 */
    @Test
    fun `闸门仍由删除收尾驱动`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("media/MediaActions.kt"))
        val body = bodyOf(masked, "finishDelete")
        assertTrue(
            "finishDelete 必须仍然 holdListRefresh()（关闸早于出栈， 定版）",
            body.contains("holdListRefresh()")
        )
        assertTrue(
            "finishDelete 必须仍然 releaseListRefresh()（开闸 + invalidate，闸门卡死就是进程级空列表）",
            body.contains("releaseListRefresh()")
        )
    }
}
