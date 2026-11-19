package com.wotagei.cam

import com.wotagei.cam.core.MediaAccessState
import com.wotagei.cam.media.GalleryEmptyState
import com.wotagei.cam.media.MediaListFailure
import com.wotagei.cam.media.accessRefreshInvalidates
import com.wotagei.cam.media.galleryEmptyState
import com.wotagei.cam.media.listFailureFor
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y26：媒体库查询失败显示为空、撤权后既有列表/缩略图无主动失效。
 *
 * 修复把「空」拆成七态（加载中 / 刷新中 / 真空 / 部分授权 / 未授权 / 查询失败 / 列表），
 * 回前台重验权限；权限变化时 `invalidate()` 重查列表，`ThumbLoader.invalidateAll()` 清缓存
 * 并推进代数（屏内 Bitmap 跟着换代）。本测试同时钉真值表与接线，验收口径：
 * 「撤权后回前台 → 列表呈现权限态而非空/旧数据；缩略图缓存随之失效」。
 */
class GalleryAccessStateTest {

    // ---------- 一、失败分类纯函数 ----------

    @Test
    fun `只有SecurityException算权限失败`() {
        assertEquals(MediaListFailure.PERMISSION, listFailureFor(SecurityException("revoked")))
        assertEquals(MediaListFailure.QUERY, listFailureFor(RuntimeException("provider busy")))
        assertEquals(null, listFailureFor(null))
    }

    // ---------- 二、空态真值表（优先级即语义） ----------

    @Test
    fun `首查未回是加载中_不是真空`() {
        assertEquals(
            GalleryEmptyState.LOADING,
            galleryEmptyState(clipsEmpty = true, listRefreshing = false, access = null, listFailure = null, loaded = false)
        )
    }

    @Test
    fun `撤权且列表为空是权限态_不是真空`() {
        assertEquals(
            GalleryEmptyState.PERMISSION,
            galleryEmptyState(
                clipsEmpty = true,
                listRefreshing = false,
                access = MediaAccessState.NONE,
                listFailure = null,
                loaded = true
            )
        )
    }

    @Test
    fun `SecurityException失败是权限态_即使权限探测还没回来`() {
        assertEquals(
            GalleryEmptyState.PERMISSION,
            galleryEmptyState(
                clipsEmpty = true,
                listRefreshing = false,
                access = null,
                listFailure = MediaListFailure.PERMISSION,
                loaded = true
            )
        )
    }

    @Test
    fun `部分授权且列表为空是受限态`() {
        assertEquals(
            GalleryEmptyState.LIMITED,
            galleryEmptyState(
                clipsEmpty = true,
                listRefreshing = false,
                access = MediaAccessState.PARTIAL_READ,
                listFailure = null,
                loaded = true
            )
        )
    }

    @Test
    fun `普通查询失败是失败态`() {
        assertEquals(
            GalleryEmptyState.FAILURE,
            galleryEmptyState(
                clipsEmpty = true,
                listRefreshing = false,
                access = MediaAccessState.FULL_READ,
                listFailure = MediaListFailure.QUERY,
                loaded = true
            )
        )
    }

    @Test
    fun `授权正常且查询回来仍空才是真空`() {
        assertEquals(
            GalleryEmptyState.EMPTY,
            galleryEmptyState(
                clipsEmpty = true,
                listRefreshing = false,
                access = MediaAccessState.FULL_READ,
                listFailure = null,
                loaded = true
            )
        )
    }

    @Test
    fun `列表非空时权限与失败标记都不许清空内容`() {
        // 与删除闸门那条边界同源：collectAsState 还保留上一份列表时，不能因为标记翻成
        // 权限/失败态就把内容换成一个提示
        for (access in MediaAccessState.values()) {
            for (failure in listOf(null, MediaListFailure.PERMISSION, MediaListFailure.QUERY)) {
                assertEquals(
                    "列表非空必须保持 LIST（access=$access failure=$failure）",
                    GalleryEmptyState.LIST,
                    galleryEmptyState(clipsEmpty = false, listRefreshing = false, access = access, listFailure = failure, loaded = true)
                )
            }
        }
    }

    // ---------- 三、接线：权限变化必须真的让旧列表/旧缩略图失效 ----------

    private fun body(rel: String, fn: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel)), fn))

    @Test
    fun `回前台重验_权限变化才失效_首次探测不清`() {
        val repo = body("media/MediaRepo.kt", "refreshAccess")
        assertTrue("refreshAccess 必须真的探测权限", repo.contains("mediaAccessState("))
        assertTrue("失效判定必须收敛到纯函数", repo.contains("accessRefreshInvalidates(prev, next)"))
        assertTrue("权限变化必须触发列表重查", repo.contains("invalidate()"))
        assertTrue("权限变化必须清缩略图缓存并推进代数", repo.contains("ThumbLoader.invalidateAll()"))
        val decision = repo.indexOf("if (!accessRefreshInvalidates(prev, next)) return")
        val evict = repo.indexOf("ThumbLoader.invalidateAll()")
        assertTrue(
            "判定为「无需失效」必须在失效之前返回：首次探测与同枚举非部分授权都没有旧数据可清",
            decision in 0 until evict
        )
        assertFalse(
            "不得再只比枚举：prev == next 提前返回会把同枚举的部分授权重选吞掉",
            repo.contains("if (prev == next) return")
        )
    }

    @Test
    fun `同一枚举的部分授权重选也必须让列表与缩略图失效`() {
        // 首次探测只记录状态
        assertFalse(accessRefreshInvalidates(null, MediaAccessState.PARTIAL_READ))
        assertFalse(accessRefreshInvalidates(null, MediaAccessState.NONE))
        // 同枚举内只有 PARTIAL_READ 的可见集合可能变（Android 14 可反复重选可见项）
        assertTrue(
            "PARTIAL_READ → PARTIAL_READ 也必须失效：重选后可见集合已换一批",
            accessRefreshInvalidates(MediaAccessState.PARTIAL_READ, MediaAccessState.PARTIAL_READ)
        )
        for (same in listOf(MediaAccessState.ALL_FILES, MediaAccessState.FULL_READ, MediaAccessState.NONE)) {
            assertFalse(
                "同一枚举的非部分授权重验没有可见集合变化（$same）",
                accessRefreshInvalidates(same, same)
            )
        }
        // 枚举变化一律失效
        assertTrue(accessRefreshInvalidates(MediaAccessState.FULL_READ, MediaAccessState.NONE))
        assertTrue(accessRefreshInvalidates(MediaAccessState.NONE, MediaAccessState.FULL_READ))
        assertTrue(accessRefreshInvalidates(MediaAccessState.FULL_READ, MediaAccessState.ALL_FILES))
        assertTrue(accessRefreshInvalidates(MediaAccessState.PARTIAL_READ, MediaAccessState.FULL_READ))
    }

    @Test
    fun `缩略图全量失效必须同时清缓存和推进代数`() {
        val invalidate = body("media/MediaRepo.kt", "invalidateAll")
        assertTrue("缓存必须清空", invalidate.contains("cache.evictAll()"))
        assertTrue("代数必须推进（屏内 remember 的 Bitmap 才会换代）", invalidate.contains("generationState.value += 1"))
        // bitmapKey 是表达式体，bodyOf 定位不到；按原文行判（遮蔽文本会把键里的字符串抹掉）
        val raw = KotlinSourceScan.mainSourceText("media/MediaRepo.kt")
        assertTrue(
            "缓存键必须带代数",
            raw.contains("fun bitmapKey(uri: Uri, px: Int): String = \"\$uri@\$px@\${generationState.value}\"")
        )
    }

    @Test
    fun `列表读失败必须与列表同帧返回`() {
        val q = body("media/MediaRepo.kt", "queryMediaStore")
        assertTrue("查询必须收集失败", q.contains("failures"))
        assertTrue("失败必须分类", q.contains("listFailureFor("))
        assertTrue("必须返回 MediaListRead（列表 + 失败同帧）", q.contains("MediaListRead("))
        assertTrue("回收站查询也必须交出失败（否则回收站页签的空仍是静默空）", q.contains("trashedMatching(scope, failures)"))
        val read = body("media/MediaRepo.kt", "readCursor")
        assertTrue("readCursor 必须把异常交给调用方分类", read.contains("failures?.add("))
        assertTrue("回收站查询同样必须交出异常", body("media/MediaRepo.kt", "readTrashed").contains("failures?.add("))
    }

    @Test
    fun `单片ID查询必须显式带IS_PENDING门`() {
        val q = body("media/MediaRepo.kt", "queryById")
        assertTrue(
            "queryById 必须显式 IS_PENDING=0（列表挡住了 pending 行，单条反查链不能再漏一条）",
            q.contains("IS_PENDING")
        )
    }

    @Test
    fun `媒体库屏订阅四态流并回前台重验`() {
        val screen = body("media/GalleryScreen.kt", "GalleryScreen")
        assertTrue("必须订阅权限四态", screen.contains("repo.mediaAccess"))
        assertTrue("必须订阅查询失败类别", screen.contains("repo.listFailure"))
        assertTrue("必须订阅首查完成标记", screen.contains("repo.listLoaded"))
        assertTrue("ON_RESUME 必须重验权限", screen.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue("重验入口必须是 refreshAccess", screen.contains("repo.refreshAccess()"))
        assertTrue("权限变化必须清选择", screen.contains("selectedIds = emptySet()"))
        assertTrue("权限变化必须关菜单", screen.contains("menuTargets = null"))
    }

    @Test
    fun `三个列表形态都吃权限与失败参数`() {
        for (name in listOf("TimelineList", "ClipGrid", "FullscreenBrowse")) {
            val b = body("media/GalleryScreen.kt", name)
            assertTrue(
                "$name 必须把权限/失败/加载三态喂给 galleryEmptyState",
                b.contains("galleryEmptyState(clips.isEmpty(), refreshing, access, listFailure, loaded)")
            )
        }
    }

    @Test
    fun `缩略图组合键含代数`() {
        val thumb = body("media/GalleryScreen.kt", "VideoThumbnail")
        assertTrue("必须订阅 ThumbLoader.generation", thumb.contains("ThumbLoader.generation"))
        assertTrue("remember 键必须含 gen（否则旧 Bitmap 常驻）", thumb.contains("remember(uri, px, gen)"))
        assertTrue("effect 键必须含 gen", thumb.contains("LaunchedEffect(uri, px, gen)"))
    }

    @Test
    fun `EmptySlot是权限与失败文案的唯一出口`() {
        val slot = body("media/GalleryScreen.kt", "EmptySlot")
        assertTrue("权限态文案", slot.contains("R.string.gallery_media_permission"))
        assertTrue("受限态文案", slot.contains("R.string.gallery_media_access_limited"))
        assertTrue("失败态文案", slot.contains("R.string.gallery_media_query_failed"))
        assertFalse("不得回退成把所有空态都当真空", slot.contains("GalleryEmptyState.LOADING -> EmptyHint"))
    }
}