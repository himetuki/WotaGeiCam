package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **停录收尾错误态必须自复位**的源码级守卫（逻辑自检迭代 12·L）。
 *
 * 存在的理由：[com.wotagei.cam.ui.CameraScreen] 的 `RecordRunner.stopInternal` 收尾时
 * `out.error != null` 会把 [com.wotagei.cam.camera.RecordStatus] 置成 ERROR，而快门入口
 * `onRecordClick` 的重入守卫是「非 IDLE 即忽略」——这个 ERROR 若无人复位，快门就被永久顶死
 * （快速启停的废片 TOO_SHORT、引擎 LOOP/ROTATE 错都走这条），只能等下一次 ON_PAUSE 的
 * 二次 stop 早退顺带把状态拉回 IDLE。`failNow`（起录期失败）一直有 80ms 自复位，
 * 本守卫把「收尾失败与起录失败同一复位口径」钉住。
 *
 * # 判据为什么按函数体切片
 * `RecordStatus.ERROR` 与 `postDelayed` 在整份 CameraScreen.kt 里多次出现，整文件 contains
 * 永远绿；只锁 failNow 的复位行又拦不住 stopInternal 那条被单独删掉（正是当初的缺陷形态）。
 * 先按 [KotlinSourceScan] 解出函数体边界（注释与字符串遮蔽后配平括号），再在边界内判定，
 * 每条断言都配正向锚点防切片切空调转。
 */
class RecordStopStatusGuardTest {

    private val screenSrc by lazy { KotlinSourceScan.mainSourceText("ui/CameraScreen.kt") }

    /** 遮蔽后的文本：注释与字面量内容不再参与结构判定 */
    private val masked by lazy { KotlinSourceScan.codeOnly(screenSrc) }

    @Test
    fun `stopInternal 收尾错误也走 80ms 自复位`() {
        val body = KotlinSourceScan.bodyOf(masked, "stopInternal")
        // 正向锚点：先证明切到的是收尾真身，否则下面的断言全是空转
        assertTrue("锚点丢失：切片没截到 rec.stop()", body.contains("rec.stop()"))
        assertTrue("锚点丢失：没截到结果落流", body.contains("result.value = out"))
        assertTrue("锚点丢失：没截到摘面分支", body.contains("setOutputSurface(null, 0, 0)"))
        // 错误分支本体：ERROR 落表 + postDelayed 复位，两件都在 stopInternal 体内
        assertTrue("收尾错误必须落 ERROR 态（错误提示的窗口）", body.contains("status.value = RecordStatus.ERROR"))
        val resetAt = body.indexOf(
            "handler.postDelayed({ if (status.value == RecordStatus.ERROR) status.value = RecordStatus.IDLE }, 80L)"
        )
        assertTrue(
            "stopInternal 体内没有 ERROR→IDLE 的 80ms 自复位：收尾失败（废片 TOO_SHORT/引擎错）后快门会被重入守卫永久顶死",
            resetAt >= 0
        )
        // 次序：先落 ERROR 再挂复位（反过来 = 复位挂上时还没进 ERROR，条件恒假）
        val errorAt = body.indexOf("status.value = RecordStatus.ERROR")
        assertTrue("ERROR 落表必须先于自复位挂号（errorAt=$errorAt resetAt=$resetAt）", errorAt in 0 until resetAt)
    }

    @Test
    fun `failNow 与 stopInternal 的复位口径是同一行代码`() {
        // 两处复位必须逐字同款（同一延时、同一条件）：防有人只改一处让两条失败路的
        // 复位窗口分叉——起录失败弹得回、收尾失败弹不回，正是本轮缺陷的非对称形态
        val expected = "handler.postDelayed({ if (status.value == RecordStatus.ERROR) status.value = RecordStatus.IDLE }, 80L)"
        assertEquals(
            "failNow 里的自复位行变了，与守卫口径失配",
            1,
            KotlinSourceScan.occurrences(KotlinSourceScan.bodyOf(masked, "failNow"), expected).size
        )
        assertEquals(
            "stopInternal 里的自复位行必须与 failNow 逐字同款（恰好一行，多出即口径分叉）",
            1,
            KotlinSourceScan.occurrences(KotlinSourceScan.bodyOf(masked, "stopInternal"), expected).size
        )
    }
}
