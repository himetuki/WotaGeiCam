package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URI 优先选片的接线守卫（2026-10-26，对比播放右槽选片三根因一起修之后的收口）。
 *
 * 这五条都是"删掉/挪走就悄悄退化、且只在真机上才看得出"的接线，逐条对应一次审查发现：
 * 1. `clipByUri` 的所有 IO 必须在 `withContext(Dispatchers.IO)` 内，且 `CancellationException`
 *    必须原样抛出——吞掉会让调用方在已取消的协程里继续写 Compose 状态；
 * 2. `persistPickReadPermission` 是 ActivityManager binder 调用，必须离开主线程；
 * 3. 分屏页的播放错误横幅必须在**已挂片分支**也渲染——原先只画在空态，
 *    挂片后解码失败/授权失效就是黑屏无提示（审查 P1）；
 * 4. `PlayerEngine.attach` 对同一 URI 的失败重选必须真正重试（去系统设置开完权限回来
 *    再选同一条是最常见的恢复路径），并同时清掉两个错误态；
 * 5. 历史面板在库内 id 查不到时要回退到列表里存的 URI，否则「权限降级/行暂时不可见」
 *    会被误显示成「视频已不存在」。
 */
class PickUriWiringGuardTest {

    private fun body(rel: String, name: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(codeOnly(mainSourceText(rel)), name))

    @Test
    fun `clipByUri 必须在IO上执行且取消要冒泡`() {
        val b = body("media/MediaRepo.kt", "clipByUri")
        assertTrue("clipByUri 必须在 withContext(Dispatchers.IO) 内（provider 读取是阻塞 IO）",
            b.contains("withContext(Dispatchers.IO)"))
        assertTrue("CancellationException 必须原样抛出（吞掉 = 已取消协程继续写 UI 状态）",
            b.contains("CancellationException"))
    }

    @Test
    fun `持久授权必须离开主线程`() {
        val b = body("media/PickPermission.kt", "persistPickReadPermission")
        assertTrue("takePersistableUriPermission 是 binder 调用，必须 withContext(Dispatchers.IO)",
            b.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun `分屏页播放错误横幅必须在已挂片分支可见`() {
        val b = body("ui/CameraScreen.kt", "SplitComparePlayer")
        val seek = b.indexOf("WotaSeekBar(")
        assertTrue("锚点丢失：没找到已挂片分支的 WotaSeekBar", seek >= 0)
        assertTrue(
            "播放错误横幅必须在已挂片分支也渲染（只画空态 = 解码失败只剩黑屏无提示）",
            b.indexOf("banner?.let", seek) > seek
        )
    }

    @Test
    fun `同URI再选必须在失败时真正重试`() {
        val b = body("player/PlayerEngine.kt", "attach")
        assertTrue("同 URI 失败后重选必须重试（否则开完权限回来仍是错误态）",
            b.contains("if (!_error.value)"))
        assertTrue("重试必须同时清掉 PlaybackError（两个错误态不许分叉）",
            b.contains("_playbackError.value = null"))
    }

    @Test
    fun `历史面板必须回退到 rightUri`() {
        val b = body("player/CompareScreen.kt", "CompareHistorySheet")
        assertTrue("库内 id 查不到时要回退历史里存的 URI（否则误报视频已不存在）",
            b.contains("rightUri?.let"))
    }
}
