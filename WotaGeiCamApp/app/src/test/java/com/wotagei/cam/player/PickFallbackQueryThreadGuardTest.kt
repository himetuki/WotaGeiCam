package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACTION_PICK 兜底路径的**线程守卫**（两处同型：[com.wotagei.cam.player.CompareScreen] 的右槽选片、
 * [com.wotagei.cam.ui.CameraScreen] 的分屏右窗选片）。
 *
 * `rememberLauncherForActivityResult` 回调体在**主线程**触发；`ContentUris.parseId` 失败
 * （非 media uri，注释里点名的相册/文件管理器来源）时的 `contentResolver.query` 兜底反查是
 * binder IPC——留在回调体里，媒体库大或 MediaProvider 繁忙时必掉帧。回调是 lambda 不是 `fun`，
 * `bodyOf` 定位不到，改用**文本偏移序断言**：兜底反查必须出现在选片回调的 `launch` 之后
 * （且包在 `withContext(Dispatchers.IO)` 里——rememberCoroutineScope 是 Main，协程体里的
 * 裸 query 仍是主线程 IPC，clipById 的 flowOn(IO) 管不到协程体）。
 */
class PickFallbackQueryThreadGuardTest {

    @Test
    fun `CompareScreen 选片兜底查询必须在协程内且走 IO`() {
        val masked = codeOnly(mainSourceText("player/CompareScreen.kt"))
        val pick = masked.indexOf("val pickRight = rememberLauncherForActivityResult")
        assertTrue("锚点丢失：没找到 pickRight 回调", pick >= 0)
        val launch = masked.indexOf("tapScope.launch", pick)
        assertTrue("锚点丢失：pickRight 回调后没截到 tapScope.launch", launch > pick)
        val io = masked.indexOf("withContext(Dispatchers.IO)", launch)
        assertTrue("锚点丢失：tapScope.launch 后没截到 withContext(Dispatchers.IO)", io > launch)
        val query = masked.indexOf("contentResolver.query", pick)
        assertTrue("锚点丢失：pickRight 回调后没截到兜底反查", query > pick)
        assertTrue(
            "兜底反查必须落在 withContext(Dispatchers.IO) 内（挪回回调体/协程裸跑都是主线程 IPC）",
            query > io
        )
    }

    @Test
    fun `CameraScreen 分屏选片兜底查询必须在协程内且走 IO`() {
        val masked = codeOnly(mainSourceText("ui/CameraScreen.kt"))
        val pick = masked.indexOf("val picker = rememberLauncherForActivityResult")
        assertTrue("锚点丢失：没找到分屏 picker 回调", pick >= 0)
        val launch = masked.indexOf("scope.launch", pick)
        assertTrue("锚点丢失：picker 回调后没截到 scope.launch", launch > pick)
        val io = masked.indexOf("withContext(Dispatchers.IO)", launch)
        assertTrue("锚点丢失：scope.launch 后没截到 withContext(Dispatchers.IO)", io > launch)
        val query = masked.indexOf("contentResolver.query", pick)
        assertTrue("锚点丢失：picker 回调后没截到兜底反查", query > pick)
        assertTrue(
            "兜底反查必须落在 withContext(Dispatchers.IO) 内（挪回回调体/协程裸跑都是主线程 IPC）",
            query > io
        )
    }
}
