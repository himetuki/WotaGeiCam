package com.wotagei.cam

import com.wotagei.cam.core.MediaAccessState
import com.wotagei.cam.media.AccessRefresh
import com.wotagei.cam.media.GalleryEmptyState
import com.wotagei.cam.media.MediaListFailure
import com.wotagei.cam.media.accessRefreshMode
import com.wotagei.cam.media.galleryEmptyState
import com.wotagei.cam.media.listFailureFor
import com.wotagei.cam.media.thumbKeyShouldEvict
import com.wotagei.cam.media.thumbKeyUri
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y26：媒体库查询失败显示为空、撤权后既有列表/缩略图无主动失效。
 *
 * 修复把「空」拆成七态（加载中 / 刷新中 / 真空 / 部分授权 / 未授权 / 查询失败 / 列表），
 * 回前台重验权限；权限**枚举变化**时 `invalidate()` 重查列表，`ThumbLoader.invalidateAll()`
 * 清缓存并推进代数（屏内 Bitmap 跟着换代）；同一枚举的 PARTIAL_READ 重选只重查列表并
 * **定向**驱逐确实不再可见的行（[AccessRefresh.TARGETED]），不再每次回前台全量清空。
 * 本测试同时钉真值表与接线，验收口径：
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

    // ---------- 三、失效档位真值表 ----------

    @Test
    fun `首次探测只记录状态_不失效`() {
        assertEquals(AccessRefresh.NONE, accessRefreshMode(null, MediaAccessState.PARTIAL_READ))
        assertEquals(AccessRefresh.NONE, accessRefreshMode(null, MediaAccessState.NONE))
        assertEquals(AccessRefresh.NONE, accessRefreshMode(null, MediaAccessState.FULL_READ))
    }

    @Test
    fun `同一枚举只有PARTIAL_READ重选定向失效`() {
        assertEquals(
            "PARTIAL_READ → PARTIAL_READ：可见集合可能换了一批，列表必须重查、缩略图定向清理",
            AccessRefresh.TARGETED,
            accessRefreshMode(MediaAccessState.PARTIAL_READ, MediaAccessState.PARTIAL_READ)
        )
        for (same in listOf(MediaAccessState.ALL_FILES, MediaAccessState.FULL_READ, MediaAccessState.NONE)) {
            assertEquals(
                "同一枚举的非部分授权重验没有可见集合变化（$same）",
                AccessRefresh.NONE,
                accessRefreshMode(same, same)
            )
        }
    }

    @Test
    fun `枚举变化一律全量失效`() {
        assertEquals(AccessRefresh.FULL, accessRefreshMode(MediaAccessState.FULL_READ, MediaAccessState.NONE))
        assertEquals(AccessRefresh.FULL, accessRefreshMode(MediaAccessState.NONE, MediaAccessState.FULL_READ))
        assertEquals(AccessRefresh.FULL, accessRefreshMode(MediaAccessState.FULL_READ, MediaAccessState.ALL_FILES))
        assertEquals(AccessRefresh.FULL, accessRefreshMode(MediaAccessState.PARTIAL_READ, MediaAccessState.FULL_READ))
    }

    // ---------- 四、缩略图键解析与定向驱逐 ----------

    @Test
    fun `缩略图缓存键取回行URI_URI自身含@也不截断`() {
        assertEquals(
            "content://media/external/video/media/7",
            thumbKeyUri("content://media/external/video/media/7@512@3")
        )
        assertEquals(
            "content://media/1234-5678/video/media/7",
            thumbKeyUri("content://media/1234-5678/video/media/7@512@0")
        )
        assertEquals(
            "content://media/external/video/media/7?x=a@b",
            thumbKeyUri("content://media/external/video/media/7?x=a@b@256@9")
        )
        assertEquals(null, thumbKeyUri("garbage"))
        assertEquals("只有一个 @ 不是合法缓存键", null, thumbKeyUri("content://media/x/7@512"))
        assertEquals(null, thumbKeyUri("@512@0"))
    }

    @Test
    fun `定向驱逐只碰不再可见的媒体行`() {
        val visible = setOf("content://media/external/video/media/7")
        assertFalse(
            "仍在可见集合里的行不许清（清了整屏缩略图白重解码）",
            thumbKeyShouldEvict("content://media/external/video/media/7@512@1", visible)
        )
        assertTrue(
            "确实不再可见的媒体行必须驱逐",
            thumbKeyShouldEvict("content://media/external/video/media/8@512@1", visible)
        )
        assertFalse(
            "外来 provider 的缩略图没有对应的列表可见集合，不参与清理",
            thumbKeyShouldEvict("content://com.example.picker/1@512@1", visible)
        )
        assertFalse("解析不出 URI 的键跳过，不猜", thumbKeyShouldEvict("garbage", visible))
    }

    // ---------- 五、接线：权限变化必须真的让旧列表/旧缩略图失效 ----------

    private fun body(rel: String, fn: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel)), fn))

    @Test
    fun `回前台重验_首次探测不清_枚举变化全量失效`() {
        val repo = body("media/MediaRepo.kt", "refreshAccess")
        assertTrue("refreshAccess 必须真的探测权限", repo.contains("mediaAccessState("))
        assertTrue("失效判定必须收敛到纯函数", repo.contains("accessRefreshMode(prev, next)"))
        assertTrue("无需失效必须在失效动作之前返回", repo.contains("if (mode == AccessRefresh.NONE) return"))
        assertTrue("权限变化必须触发列表重查", repo.contains("invalidate()"))
        assertTrue("枚举变化必须走全量分支", repo.contains("if (mode == AccessRefresh.FULL)"))
        assertTrue("全量分支必须清缩略图缓存并推进代数", repo.contains("ThumbLoader.invalidateAll()"))
        assertTrue("同枚举部分授权必须走定向分支", repo.contains("retainVisibleThumbs()"))

        val full = repo.indexOf("if (mode == AccessRefresh.FULL)")
        val evictAll = repo.indexOf("ThumbLoader.invalidateAll()")
        val targeted = repo.indexOf("} else {")
        assertTrue("全量分支必须包住 invalidateAll", full >= 0 && evictAll > full)
        assertTrue("定向分支在全量分支之后（else），不许把 invalidateAll 漏进定向路径", targeted > evictAll)
        assertFalse(
            "不得再只比枚举：prev == next 提前返回会把同枚举的部分授权重选吞掉",
            repo.contains("if (prev == next) return")
        )
    }

    @Test
    fun `同枚举部分授权重选只定向驱逐不可见缩略图`() {
        val retain = body("media/MediaRepo.kt", "retainVisibleThumbs")
        assertTrue("必须按当前权限重读全量可见行（不限于当前页签）", retain.contains("queryMediaStore(MediaScope.AllVideos)"))
        assertTrue("可见集合必须用真实行 URI（裸 _ID 跨卷会撞）", retain.contains("it.uri.toString()"))
        assertTrue("必须交给定向驱逐", retain.contains("ThumbLoader.retainMediaRows(visible)"))
        assertTrue("读失败必须留下现场（并按保守集合清理）", retain.contains("if (read.failure != null)"))

        val rows = body("media/MediaRepo.kt", "retainMediaRows")
        assertTrue("必须遍历当前全部缓存键", rows.contains("cache.snapshot()"))
        assertTrue("只驱逐判据命中的键", rows.contains("thumbKeyShouldEvict(key, visibleUris)"))
        assertTrue("驱逐动作是 remove", rows.contains("cache.remove(key)"))
        assertFalse("定向路径不许全量 evictAll（那会退化成高频清空）", rows.contains("evictAll"))
        assertFalse("定向路径不推进代数（屏内可见行 Bitmap 仍有效）", rows.contains("generationState.value"))
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
        assertTrue("权限变化必须清选择", screen.contains("selectedKeys = emptySet()"))
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