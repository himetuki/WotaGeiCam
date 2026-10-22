package com.wotagei.cam

import com.wotagei.cam.media.runDeleteFinish
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「彻底删除的收尾必须活着走到开闸」时序守卫（2026-10-02 真机缺陷，两轮录像-删除周期均复现）。
 *
 * 事故口径：播放器顶栏「彻底删除」→ 确认框确认 → 返回媒体库，「全部」页签空列表
 * （「这里还没有视频」），切收藏/回收站、滚动都不恢复，**杀进程重进才恢复**（重启后 13 条完整、
 * 文件无损）。根因不是数据丢失，是 [com.wotagei.cam.media.MediaRepo.refreshGate] 卡在 true：
 * 收尾链跑在调用屏的 `rememberCoroutineScope` 里，`pop = onBack` 让播放页离屏 ⇒ 组合作用域
 * 连同 `delay(REFRESH_DELAY_MS)` 一起被取消 ⇒ `releaseListRefresh()`（开闸 + invalidate）永不执行。
 * 修法见 [runDeleteFinish] 上方长注释：整段 `withContext(NonCancellable)` + finally 无条件开闸。
 *
 * 行为用例全部可证伪（尺子自己能红）：
 * 1. 顺序用例钉住  定版「关闸早于出栈、开闸晚于出栈」；
 * 2. **取消免疫用例**是本类的核心——退回无 NonCancellable 的旧实现时它会红：
 *    调用方作用域在 pop 之后被取消，`release` 就永远等不到；
 * 3. 源码钉扎两条：`finishDelete` 必须真的委托给 `runDeleteFinish`（否则修的是死代码、行为用例
 *    照绿而真机照旧），且 `withContext(NonCancellable)` 必须包在两个 `delay` 之前。
 */
class DeleteFinishGateTest {

    /** 跨线程追加/读取都安全的小本子（收尾跑在 Dispatchers.Default，轮询跑在测试线程） */
    private val log = CopyOnWriteArrayList<String>()

    /** 轮询等某个回调发生；不用 kotlinx-coroutines-test（本模块没引那个依赖，也不值得为它引） */
    private fun await(tag: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (log.contains(tag)) return true
            Thread.sleep(2)
        }
        return log.contains(tag)
    }

    @Test
    fun `删除收尾顺序_关闸在出栈前_开闸与refresh在出栈后`() = runBlocking {
        log.clear()
        runDeleteFinish(
            clipDeleted = true,
            popDelayMs = 1,
            refreshDelayMs = 1,
            hold = { log.add("hold") },
            cleanup = { log.add("cleanup") },
            pop = {
                log.add("pop")
                //  定版：出栈前列表不许重算（pager 会用陈旧 position 越界），所以闸门必须先关上
                assertTrue("出栈前必须已关闸", log.contains("hold"))
                assertTrue("出栈前缓存必须已清", log.contains("cleanup"))
                // 刷新必须晚于播放页出栈：走到 pop 这一步时闸门还不许开
                assertTrue("出栈这一刻闸门还不许开", !log.contains("release"))
            },
            release = { log.add("release") },
            refresh = { log.add("refresh") }
        )
        assertEquals(listOf("hold", "cleanup", "pop", "release", "refresh"), log)
    }

    @Test
    fun `一条都没删掉时不关闸不清缓存_但出栈与开闸照旧`() = runBlocking {
        log.clear()
        // 与旧实现逐字等价：clips 为空（整批都缺权限删不掉）时不关闸、不清 Room，pop/release/refresh 照走
        runDeleteFinish(
            clipDeleted = false,
            popDelayMs = 1,
            refreshDelayMs = 1,
            hold = { log.add("hold") },
            cleanup = { log.add("cleanup") },
            pop = { log.add("pop") },
            release = { log.add("release") },
            refresh = { log.add("refresh") }
        )
        assertEquals(listOf("pop", "release", "refresh"), log)
    }

    /**
     * 核心证伪用例：调用方屏的组合作用域在 pop 之后被取消（真机上就是播放页离屏的那一刻）。
     *
     * 旧实现（`finishDelete` 里裸 `delay(REFRESH_DELAY_MS)` + `repo.releaseListRefresh()`）在这里
     * 会红：`release` 永不发生 ⇒ 闸门永久停在 true ⇒ 媒体库全页签空列表、只能杀进程恢复。
     */
    @Test
    fun `调用方作用域在出栈后被取消_闸门仍必须打开`() = runBlocking {
        log.clear()
        // 用一个独立 Job 扮演「调用方屏的 rememberCoroutineScope」：MediaOps.launchOp 就是往这种
        // 作用域里 launch 的，播放器确认删除时它的生死取决于 PlayerScreen 是否还在组合里
        val caller = launch(Dispatchers.Default) {
            runDeleteFinish(
                clipDeleted = true,
                popDelayMs = 40,
                refreshDelayMs = 600,
                hold = { log.add("hold") },
                cleanup = { log.add("cleanup") },
                pop = { log.add("pop") },
                release = { log.add("release") },
                refresh = { log.add("refresh") }
            )
        }
        // 真机上这一刻：pop 已把播放页出栈、组合作用域被取消，而 900ms 的 REFRESH_DELAY 还没走完
        assertTrue("pop 必须先发生，否则本用例根本没测到取消窗口", await("pop"))
        caller.cancel()
        assertTrue(
            "调用方作用域被取消后开闸仍必须执行（退回无 NonCancellable 的旧实现这里就红）",
            await("release")
        )
        assertTrue("refresh 也必须跑完（媒体库重查的最后一步）", await("refresh"))
    }

    /** 防「死代码式回退」：把收尾缩回 MediaActions 类内私有实现，runDeleteFinish 就成了没人调的死代码 */
    @Test
    fun `finishDelete必须把收尾时序委托给runDeleteFinish`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("media/MediaActions.kt"))
        val body = bodyOf(masked, "finishDelete")
        assertTrue(
            "MediaActions.finishDelete 必须调用 runDeleteFinish(...)（否则 NonCancellable 修的是死代码：行为用例全绿、真机照旧复现空列表）",
            body.contains("runDeleteFinish(")
        )
    }

    /** NonCancellable 必须包住整段时序（含两个 delay），只包 release 一行挡不住调用方取消 */
    @Test
    fun `runDeleteFinish必须把两个delay都包进NonCancellable`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("media/MediaActions.kt"))
        val body = bodyOf(masked, "runDeleteFinish")
        // 只查「withContext 出现在 NonCancellable 之前」，不在 `withContext(NonCancellable)` 的
        // 字面量上卡死：换个排版风格（多一个空格）不该把守卫打红
        val nc = body.indexOf("NonCancellable")
        val wc = body.indexOf("withContext")
        assertTrue("收尾时序必须包在 withContext(NonCancellable) 里", nc >= 0 && wc >= 0 && wc < nc)
        val firstDelay = body.indexOf("delay(")
        assertTrue("第一个 delay 也必须在 NonCancellable 之后", firstDelay > wc)
        assertTrue("第二个 delay 也必须在 NonCancellable 之后", body.lastIndexOf("delay(") > wc)
    }
}
