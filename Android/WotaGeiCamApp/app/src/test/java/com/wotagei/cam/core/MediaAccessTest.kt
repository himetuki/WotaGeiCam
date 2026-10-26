package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体读取权限四态的**纯函数**测试（2026-10-26，对比播放右槽选片失败）。
 *
 * 钉两件事：
 * 1. 状态判定的优先级与版本闸——特别是「部分授权只在 API 34+ 成立」，调用方一旦漏写
 *    版本闸（把 34 以下的未知权限读成已授予），这里必须当场红；
 * 2. 回程 URI 的形态判据——系统照片选择器的 URI authority 同样是 `media`，但它末段的
 *    数字不是 MediaStore `_ID`；判据写错会让选片静默挂到「同号的另一条视频」上。
 */
class MediaAccessTest {

    @Test
    fun `所有文件访问压过其它三态`() {
        assertEquals(
            MediaAccessState.ALL_FILES,
            mediaAccessStateFor(sdk = 34, fullRead = true, partialRead = true, allFiles = true)
        )
    }

    @Test
    fun `全量优先于部分授权`() {
        assertEquals(
            MediaAccessState.FULL_READ,
            mediaAccessStateFor(sdk = 34, fullRead = true, partialRead = false, allFiles = false)
        )
    }

    @Test
    fun `部分授权只在 API 34 及以上成立`() {
        assertEquals(
            MediaAccessState.PARTIAL_READ,
            mediaAccessStateFor(sdk = 34, fullRead = false, partialRead = true, allFiles = false)
        )
        // 33 及以下没有 READ_MEDIA_VISUAL_USER_SELECTED：传 true 也只按未授权处理
        assertEquals(
            MediaAccessState.NONE,
            mediaAccessStateFor(sdk = 33, fullRead = false, partialRead = true, allFiles = false)
        )
        assertEquals(
            MediaAccessState.NONE,
            mediaAccessStateFor(sdk = 29, fullRead = false, partialRead = true, allFiles = false)
        )
    }

    @Test
    fun `三样都没有就是未授权`() {
        assertEquals(
            MediaAccessState.NONE,
            mediaAccessStateFor(sdk = 34, fullRead = false, partialRead = false, allFiles = false)
        )
    }

    @Test
    fun `只有部分授权与未授权需要权限提示`() {
        assertTrue(needsMediaAccessHint(MediaAccessState.PARTIAL_READ))
        assertTrue(needsMediaAccessHint(MediaAccessState.NONE))
        assertFalse(needsMediaAccessHint(MediaAccessState.FULL_READ))
        assertFalse(needsMediaAccessHint(MediaAccessState.ALL_FILES))
    }

    @Test
    fun `媒体读权限按 API 33 分档`() {
        assertEquals("android.permission.READ_MEDIA_VIDEO", mediaReadPermissionFor(33))
        assertEquals("android.permission.READ_MEDIA_VIDEO", mediaReadPermissionFor(34))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", mediaReadPermissionFor(32))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", mediaReadPermissionFor(29))
    }

    @Test
    fun `诊断标签四态互不相同`() {
        val labels = MediaAccessState.values().map { mediaAccessLabel(it) }
        assertEquals(MediaAccessState.values().size, labels.toSet().size)
        assertEquals("部分授权(仅选中项)", mediaAccessLabel(MediaAccessState.PARTIAL_READ))
    }

    @Test
    fun `MediaStore 视频集合 URI 才认作可反查 id`() {
        assertTrue(
            isMediaStoreVideoUriParts(
                authority = "media",
                segments = listOf("external", "video", "media", "123")
            )
        )
        // 分卷命名空间（SD 卡 / external_primary）同样是合法形态
        assertTrue(
            isMediaStoreVideoUriParts(
                authority = "media",
                segments = listOf("external_primary", "video", "media", "42")
            )
        )
    }

    @Test
    fun `照片选择器与文档 provider 的 URI 不得当 MediaStore id 使用`() {
        // 系统 Photo Picker：authority 也是 media，但 id 是选择器自己的命名空间
        assertFalse(
            isMediaStoreVideoUriParts(
                authority = "media",
                segments = listOf("picker", "0", "com.android.providers.media.photopicker", "media", "1000000034")
            )
        )
        // DocumentsUI：video:123 不是集合条目
        assertFalse(
            isMediaStoreVideoUriParts(
                authority = "com.android.providers.media.documents",
                segments = listOf("document", "video:123")
            )
        )
        // 形态对但末段不是数字（ContentUris.parseId 会抛，这里提前判掉）
        assertFalse(
            isMediaStoreVideoUriParts(
                authority = "media",
                segments = listOf("external", "video", "media", "abc")
            )
        )
        assertFalse(isMediaStoreVideoUriParts(authority = null, segments = emptyList()))
    }
}
