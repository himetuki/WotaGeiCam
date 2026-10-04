package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「引擎终止关闭：拆除与退线程必须是一个原子任务」的源码级守卫（迭代 13 · D 项）。
 *
 * 缺陷本体：旧版 close() 把拆除与退出投成两个任务，中间有执行间隙——bind() 换引擎时旧引擎
 * open 的 post 若插进该间隙（或作为到期消息在 quitSafely 之后被补发），`manager.openCamera`
 * 已发起而线程旋即退出，onOpened 投到已退出的 looper 被静默丢弃 ⇒ 相机句柄无人 close。
 * 修复：调用方线程先同步置 `threadRunning = false` 封住所有 post 的入队/执行守卫（quitSafely
 * 对已到期消息照发不误，只合并 post 封不住「合并任务执行前入队」的一路），再把拆除与
 * quitSafely 合并进单个 `workHandler.post`（try/finally 保「拆除抛异常也一定退出线程」）。
 *
 * Camera2Engine 是 Camera2 框架封装类，JVM 里没有可跑的行为测试，按工程惯例落源码级守卫。
 */
class EngineClosePostMergeGuardTest {

    private val src by lazy { KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt") }
    private val masked by lazy { KotlinSourceScan.codeOnly(src) }

    @Test
    fun `close 里只许有一枚投递点`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "close"))
        // 锚点：确认截到的是终止性关闭真身
        assertTrue("锚点丢失：close 里没有 ImageReader 收尾", body.contains("stopImageReaders()"))
        assertTrue("锚点丢失：close 里没有设备拆除", body.contains("teardownDevice()"))
        assertEquals(
            "close 里投递块（post {）必须恰好 1 处——拆除与 quit 分投会留出执行间隙，" +
                "旧引擎 open 的 post 插队后 openCamera 已发起而线程退出，onOpened 随线程死亡丢失",
            1, KotlinSourceScan.occurrences(body, "post {").size
        )
        assertEquals(
            "quitSafely 全文件只允许 close 里这一处——退出语义必须单点收敛，不许再开第二个退出通道",
            1, KotlinSourceScan.occurrences(masked, "quitSafely").size
        )
    }

    @Test
    fun `quitSafely 必须落在同一 post 块内拆除之后`() {
        val body = KotlinSourceScan.bodyOf(masked, "close")
        val post = KotlinSourceScan.occurrences(body, "workHandler.post").firstOrNull()
            ?: throw AssertionError("close 里找不到 workHandler.post——单 post 结构丢了")
        val quit = body.indexOf("quitSafely", post)
        assertTrue("合并任务里找不到 quitSafely——线程退出通道丢了", quit >= 0)
        val teardown = body.indexOf("teardownDevice()", post)
        assertTrue(
            "teardownDevice 必须在投递块内、quitSafely 之前——先拆干净再退线程是 close 的固定顺序",
            teardown in post until quit
        )
        // post 块的范围：在遮蔽后的文本上做括号配对（注释/字符串已被词法遮蔽清空，剩下的括号是纯代码括号）
        val openBrace = body.indexOf('{', post)
        var depth = 0
        var blockEnd = -1
        for (i in openBrace until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { blockEnd = i; break } }
            }
        }
        assertTrue("post 块没配平——遮蔽器覆盖不到的写法，守卫拒绝放行", blockEnd >= 0)
        assertTrue(
            "quitSafely 必须在 post 块内部（$quit < $blockEnd）执行——落在块外就是在调用线程上先退线程，拆除可能没跑",
            quit < blockEnd
        )
    }

    @Test
    fun `threadRunning 必须在调用方线程同步封口且先于投递`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "close"))
        val flag = KotlinSourceScan.occurrences(body, "threadRunning = false")
        assertEquals(
            "close 里 threadRunning = false 必须恰好 1 处",
            1, flag.size
        )
        val post = KotlinSourceScan.occurrences(body, "workHandler.post").firstOrNull()
            ?: throw AssertionError("close 里找不到 workHandler.post")
        assertTrue(
            "threadRunning = false 必须先于投递（在调用方线程上置假）：否则 close 返回后到合并任务执行前，" +
                "旧引擎 open 的 post 仍能通过入队守卫，再被 quitSafely 当到期消息补发",
            flag[0] < post
        )
        assertEquals(
            "threadRunning = false 全文件只允许 close 里这一处",
            1, KotlinSourceScan.occurrences(masked, "threadRunning = false").size
        )
    }
}
