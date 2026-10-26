package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选片回程的**线程纪律**守卫（2026-10-26 URI 优先改造后重写，前身是
 * `PickFallbackQueryThreadGuardTest`）。
 *
 * 旧实现：回调里 `ContentUris.parseId` → `_ID` 兜底查询 → `clipByIdOnce`，守卫钉的是
 * 「兜底 query 必须包在 `withContext(Dispatchers.IO)`」。改造后兜底查询整段删除——
 * 回程 URI 直接交给 `repo.clipByUri(uri)`（内部自带 IO 切换、可读性预检与元数据读取），
 * 外来来源（照片选择器/文档 provider/云相册 provider）不再按 mediaId 反查。
 *
 * 守卫改钉两条**不变式**（旧守卫的真实意图：主线程不许有 binder IPC）：
 * 1. 回调体到解析调用之间（含协程启动后的同步段）不得出现任何同步 IO：`contentResolver.` /
 *    `openAssetFileDescriptor` / `MediaMetadataRetriever` / `ContentUris.parseId` 一律不许；
 * 2. 解析必须在协程启动之后调 `repo.clipByUri(`（挪回同步路径、或退回按 id 反查都会红）。
 */
class PickResolutionThreadGuardTest {

    /** 主线程禁入的同步 IO 形态（命中即视为把 IO 塞回了回调体/协程同步段） */
    private val forbidden = listOf(
        "contentResolver.", "openAssetFileDescriptor", "MediaMetadataRetriever", "ContentUris.parseId"
    )

    /** 判据本体：良品与突变体走同一把尺 */
    private fun holds(src: String, launcherAnchor: String, launchAnchor: String): Boolean {
        val start = src.indexOf(launcherAnchor)
        if (start < 0) return false
        val launch = src.indexOf(launchAnchor, start)
        if (launch < 0) return false
        val resolve = src.indexOf("repo.clipByUri(", launch)
        if (resolve < 0) return false
        // 切片一直取到解析调用：协程启动后的同步段也算在内（在那里塞 query 同样是主线程 IPC）
        val slice = src.substring(start, resolve)
        return forbidden.none { slice.contains(it) }
    }

    @Test
    fun `对比页选片回程不在主线程做IO且走可挂起的 clipByUri`() {
        val src = codeOnly(mainSourceText("player/CompareScreen.kt"))
        assertTrue(
            "CompareScreen 选片回调必须在协程里调 repo.clipByUri，且回调体内不得有同步 IO",
            holds(src, "val pickRight = rememberLauncherForActivityResult", "tapScope.launch")
        )
    }

    @Test
    fun `分屏选片回程不在主线程做IO且走可挂起的 clipByUri`() {
        val src = codeOnly(mainSourceText("ui/CameraScreen.kt"))
        assertTrue(
            "CameraScreen 分屏选片回调必须在协程里调 repo.clipByUri，且回调体内不得有同步 IO",
            holds(src, "val picker = rememberLauncherForActivityResult", "scope.launch")
        )
    }

    @Test
    fun `突变_把解析退回同步query或按id反查必红`() {
        val rel = "player/CompareScreen.kt"
        val launcher = "val pickRight = rememberLauncherForActivityResult"
        val src = codeOnly(mainSourceText(rel))
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", holds(src, launcher, "tapScope.launch"))
        assertFalse(
            "解析退回同步 contentResolver.query 后必须红（主线程 IPC 复活）",
            holds(src.replace("repo.clipByUri(", "app.contentResolver.query("), launcher, "tapScope.launch")
        )
        assertFalse(
            "解析退回按 mediaId 反查后必须红（外来 URI 会重新被判死）",
            holds(src.replace("repo.clipByUri(", "repo.clipByIdOnce("), launcher, "tapScope.launch")
        )
    }
}
