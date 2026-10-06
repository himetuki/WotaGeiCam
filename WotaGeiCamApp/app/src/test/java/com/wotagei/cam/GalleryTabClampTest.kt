package com.wotagei.cam

import com.wotagei.cam.media.ClipFilter
import com.wotagei.cam.media.GalleryTab
import com.wotagei.cam.media.GalleryTabs
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库页签收窄矫正（[GalleryTabs.clampedSelection]）的纯函数全表 + GalleryScreen 接线守卫。
 *
 * 背景（缺陷 3）：customTags 来自 media_tag 行，删掉唯一带该 tag 的视频（或删 tag 本身）后 tabs
 * 缩回 3 项——旧代码里 `tabIndex=3` 只在显示处被 coerce，`filter` 仍是 `Tagged(已删tag)`：
 * 高亮跳到"回收站"、内容区恒空。矫正规则：
 * 1. index 越界 ⇒ 夹回最后一项并**同步** filter（任务指定的主形态）；
 * 2. index 合法但 filter 与 `tabs[index].filter` 不一致 ⇒ 同步 filter（删的是**更靠前**的自定义
 *    tag 时 index 不越界、高亮看着正常，内容区却在查已删 tag，同样恒空——只修形态 1 漏这条）；
 * 3. 一致（含 tabs 空的极端）⇒ 原样返回，不动 onSelect 自己维护的选中态。
 *
 * 期望值一律手写字面量。突变：删掉 GalleryScreen 的 `LaunchedEffect(tabs)` → 接线守卫必红；
 * 把矫正改成只处理越界（漏形态 2）→ 「index 合法但 filter 失效」用例必红。
 */
class GalleryTabClampTest {

    /** 定版页签序：全部 → 收藏 → 回收站 → 自定义。三系统页签是收窄后的底线形态 */
    private fun tabs3() = listOf(
        GalleryTab(ClipFilter.All, "全部"),
        GalleryTab(ClipFilter.Like, "收藏"),
        GalleryTab(ClipFilter.Trash, "回收站")
    )

    @Test
    fun `index 越界夹回最后一项并同步 filter`() {
        // 删掉唯一带该 tag 的视频：tabs 缩回 3 项，tabIndex=3、filter=Tagged(已删) ⇒ 矫正到 (2, Trash)
        assertEquals(
            2 to ClipFilter.Trash,
            GalleryTabs.clampedSelection(tabs3(), 3, ClipFilter.Tagged("舞蹈"))
        )
        // 越界更多也同一落点
        assertEquals(
            2 to ClipFilter.Trash,
            GalleryTabs.clampedSelection(tabs3(), 5, ClipFilter.Tagged("solo"))
        )
    }

    @Test
    fun `index 合法且 filter 一致时原样返回`() {
        // 正常浏览态不许被矫正搅动（onSelect 自己维护选中态）
        assertEquals(0 to ClipFilter.All, GalleryTabs.clampedSelection(tabs3(), 0, ClipFilter.All))
        assertEquals(1 to ClipFilter.Like, GalleryTabs.clampedSelection(tabs3(), 1, ClipFilter.Like))
        assertEquals(2 to ClipFilter.Trash, GalleryTabs.clampedSelection(tabs3(), 2, ClipFilter.Trash))
        val t4 = tabs3() + GalleryTab(ClipFilter.Tagged("舞蹈"), "舞蹈")
        assertEquals(3 to ClipFilter.Tagged("舞蹈"), GalleryTabs.clampedSelection(t4, 3, ClipFilter.Tagged("舞蹈")))
    }

    @Test
    fun `index 合法但 filter 已失效也矫正`() {
        // 删掉的是更靠前的自定义 tag A：tabs=[All,Like,Trash,B]，tabIndex=3 落在 Tagged(B) 上，
        // filter 仍是 Tagged(A) —— 高亮正常、内容恒空。必须把 filter 同步成 tabs[3] 的
        assertEquals(
            3 to ClipFilter.Tagged("B"),
            GalleryTabs.clampedSelection(
                listOf(
                    GalleryTab(ClipFilter.All, "全部"),
                    GalleryTab(ClipFilter.Like, "收藏"),
                    GalleryTab(ClipFilter.Trash, "回收站"),
                    GalleryTab(ClipFilter.Tagged("B"), "B")
                ),
                3,
                ClipFilter.Tagged("A")
            )
        )
    }

    @Test
    fun `空 tabs 不矫正`() {
        // GalleryTabs.build 恒有 3 个系统页签，空表是防御性分支：原样返回即可
        assertEquals(
            3 to ClipFilter.Tagged("t"),
            GalleryTabs.clampedSelection(emptyList(), 3, ClipFilter.Tagged("t"))
        )
    }

    /**
     * 接线守卫（结构锁）：GalleryScreen 必须存在**恰好一处** `LaunchedEffect(tabs)`，且体内
     * 走纯函数矫正并回写 tabIndex/filter 两处状态。删掉该 effect（突变）→ 本用例必红。
     */
    @Test
    fun `GalleryScreen 必须有页签收窄矫正接线`() {
        val src = codeOnly(mainSourceText("media/GalleryScreen.kt"))
        val hits = occurrences(src, "LaunchedEffect(tabs)")
        assertEquals("LaunchedEffect(tabs) 必须恰好一处（删掉即失守）", 1, hits.size)
        // 矫正体很短，命中点往后取一段窗口判定体内接线
        val window = src.substring(hits[0], minOf(src.length, hits[0] + 400))
        assertTrue("矫正必须走纯函数 clampedSelection", window.contains("clampedSelection("))
        assertTrue("必须回写 tabIndex", window.contains("tabIndex = i"))
        assertTrue("必须同步回写 filter", window.contains("filter = f"))
    }
}
