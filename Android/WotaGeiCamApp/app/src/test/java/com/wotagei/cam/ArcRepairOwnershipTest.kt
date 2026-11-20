package com.wotagei.cam

import com.wotagei.cam.record.ArcRepairError
import com.wotagei.cam.record.ArcRepairRequest
import com.wotagei.cam.record.ArcRepairResult
import com.wotagei.cam.record.ArcRepairTaskGate
import com.wotagei.cam.record.ArcRepairTasks
import com.wotagei.cam.record.ArcResultKind
import com.wotagei.cam.record.arcRepairProgress
import com.wotagei.cam.record.ArcProgressStage
import com.wotagei.cam.record.arcResultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y23 的**宿主侧所有权账本**（[ArcRepairTasks]）桥测。
 *
 * 卡片的形态是"谁有权起跑 / 结果属于哪一轮 / 取消与确认落到谁"：
 * - 起跑前先领取单一槽位 ⇒ 连点两下只跑一轮，且执行器拒收时槽位回滚（不留幽灵 RUNNING）；
 * - 每轮带 `(来源 URI, 代数)`；取消/确认按代数匹配 ⇒ 迟到的取消碰不到下一轮、确认不清别人的结果；
 * - 终态**恰好一次**入队且不被后续起跑清掉 ⇒ 退页/重建后结果还在，直到页面确认；
 * - 取消与完成赛跑时照实保留真结果（不改判成"已取消"）。
 *
 * 这些行为在旧实现里根本不存在（状态挂在页面 remember 的 runner 上），所以表本身就是
 * 辨别性的：删掉单槽校验、代数码或结果队列里任一格，对应用例会红。
 */
class ArcRepairOwnershipTest {

    private fun result(
        ok: Boolean = false,
        error: String? = null,
        pendingPublish: Boolean = false
    ) = ArcRepairResult(ok, null, null, 0L, error, pendingPublish = pendingPublish)

    @Test
    fun `连续启动两次只跑一轮`() {
        val launched = ArrayList<ArcRepairRequest>()
        val tasks = ArcRepairTasks()
        val first = tasks.start("content://v/1", "a.mp4", useGpu = true, dstFps = 24) { req ->
            launched.add(req)
            1L
        }
        val second = tasks.start("content://v/1", "a.mp4", useGpu = false, dstFps = 25) { req ->
            launched.add(req)
            2L
        }
        assertTrue("首轮应领到槽位", first)
        assertFalse("第二轮必须直接拒绝（不排队、不跑第二轮）", second)
        assertEquals("执行器只应被起跑一次", 1, launched.size)
        assertEquals("槽位仍是首轮", launched[0], tasks.state.value.running?.request)
    }

    @Test
    fun `执行器拒收时槽位回滚并按忙返回`() {
        val tasks = ArcRepairTasks()
        val accepted = tasks.start("content://v/1", "a.mp4", useGpu = true, dstFps = 24) { null }
        assertFalse("执行器没有领到所有权 ⇒ 本体起跑失败", accepted)
        assertNull("不得留下幽灵 RUNNING（旧实现先写 RUNNING 再 post）", tasks.state.value.running)
        assertTrue("没有起跑就不该有结果", tasks.state.value.results.isEmpty())
        assertTrue("回滚后可以正常起跑下一轮", tasks.start("content://v/1", "a.mp4", useGpu = true, dstFps = 24) { 1L })
    }

    @Test
    fun `取消只作用于本代数并落到执行器token`() {
        val cancelledTokens = ArrayList<Long>()
        val tasks = ArcRepairTasks(onCancel = { cancelledTokens.add(it) })
        var seen: ArcRepairRequest? = null
        tasks.start("content://v/1", "a.mp4", useGpu = false, dstFps = 24) { req ->
            seen = req
            7L
        }
        val first = checkNotNull(seen)
        // 执行器 token 回填发生在 start 返回前：此时取消必须真的落到执行器
        tasks.cancel(first)
        assertEquals("取消必须把执行器 token 交出去", listOf(7L), cancelledTokens)
        assertEquals("取消中的轮次仍占着槽位（等收尾），不得被新一轮顶掉", first, tasks.state.value.running?.request)
        assertTrue("取消中的轮次不再接受新进度", tasks.state.value.running?.cancelling == true)

        // 第二轮：旧代数的迟到取消不得碰它
        tasks.finish(first, result(ok = true))
        var second: ArcRepairRequest? = null
        tasks.start("content://v/1", "a.mp4", useGpu = false, dstFps = 24) { req ->
            second = req
            8L
        }
        tasks.cancel(first)
        assertEquals("旧代数的迟到取消不得再叫停执行器", listOf(7L), cancelledTokens)
        assertEquals("新一轮不得被标成取消中", false, tasks.state.value.running?.cancelling)
        assertEquals("新一轮仍持有槽位", second, tasks.state.value.running?.request)
    }

    @Test
    fun `取消与完成赛跑时照实保留真结果`() {
        val tasks = ArcRepairTasks(onCancel = {})
        var request: ArcRepairRequest? = null
        tasks.start("content://v/1", "a.mp4", useGpu = true, dstFps = 24) { req ->
            request = req
            3L
        }
        val req = checkNotNull(request)
        tasks.cancel(req)
        // 取消已发出，但产物已封口发布成功：结果必须照实是成功，不得被改判成"已取消"
        tasks.finish(req, result(ok = true))
        val item = tasks.state.value.results.single()
        assertTrue("真结果不得被取消标记吞掉", item.payload.ok)
        assertNull("槽位归还", tasks.state.value.running)
    }

    @Test
    fun `终态留存到确认且新起跑不清旧结果`() {
        val tasks = ArcRepairTasks()
        var first: ArcRepairRequest? = null
        tasks.start("content://v/1", "a.mp4", useGpu = true, dstFps = 24) { req ->
            first = req
            1L
        }
        tasks.finish(checkNotNull(first), result(ok = true))
        assertTrue("起跑下一轮不清未读结果", tasks.start("content://v/2", "b.mp4", useGpu = false, dstFps = 25) { 2L })
        assertEquals("旧结果仍在队列里（退页/换片不丢）", 1, tasks.state.value.results.size)

        val bReq = checkNotNull(tasks.state.value.running?.request)
        tasks.finish(bReq, result(error = ArcRepairError.CANCELLED))
        assertEquals("两条结果都保留，先到先显示", 2, tasks.state.value.results.size)

        tasks.acknowledge(checkNotNull(first).taskId)
        assertEquals("确认只清对应代数", listOf(bReq.taskId), tasks.state.value.results.map { it.request.taskId })
    }

    @Test
    fun `同一代数的终态只入队一次`() {
        val tasks = ArcRepairTasks()
        var request: ArcRepairRequest? = null
        tasks.start("content://v/1", "a.mp4", useGpu = true, dstFps = 24) { req ->
            request = req
            1L
        }
        val req = checkNotNull(request)
        tasks.finish(req, result(error = ArcRepairError.SOURCE))
        tasks.finish(req, result(ok = true))
        val item = tasks.state.value.results.single()
        assertFalse("重复终态不得覆盖或追加（执行器保证恰好一次）", item.payload.ok)
    }

    @Test
    fun `进度只认当前轮且取消后不再前进`() {
        val tasks = ArcRepairTasks(onCancel = {})
        var request: ArcRepairRequest? = null
        tasks.start("content://v/1", "a.mp4", useGpu = true, dstFps = 24) { req ->
            request = req
            1L
        }
        val req = checkNotNull(request)
        val foreign = req.copy(taskId = req.taskId + 100)
        tasks.progress(foreign, 0.9f)
        assertEquals("别的代数不得推进本片进度", arcRepairProgress(ArcProgressStage.PREPARE, 0, 0), tasks.state.value.running?.progress)
        tasks.progress(req, 0.5f)
        assertEquals(0.5f, tasks.state.value.running?.progress ?: Float.NaN, 0.0001f)
        tasks.progress(req, 0.4f)
        assertEquals("进度不得倒退", 0.5f, tasks.state.value.running?.progress ?: Float.NaN, 0.0001f)
        tasks.progress(req, Float.NaN)
        assertEquals(0.5f, tasks.state.value.running?.progress ?: Float.NaN, 0.0001f)
        tasks.cancel(req)
        tasks.progress(req, 0.8f)
        assertEquals("取消中的迟到进度不得再推进", 0.5f, tasks.state.value.running?.progress ?: Float.NaN, 0.0001f)
    }

    @Test
    fun `在跑状态按来源URI分片`() {
        val tasks = ArcRepairTasks()
        tasks.start("content://v/1", "a.mp4", useGpu = true, dstFps = 24) { 1L }
        assertNotNull("本片能看到自己在跑", tasks.state.value.runningFor("content://v/1"))
        assertNull("别的片不得把本片标成在忙（入口 accent 与进度行都按它画）", tasks.state.value.runningFor("content://v/2"))
    }

    @Test
    fun `取消只对当前轮生效_晚取消不污染下一轮`() {
        val gate = ArcRepairTaskGate()
        val t1 = checkNotNull(gate.begin())
        gate.cancel(t1)
        assertTrue(gate.isCancelled(t1))
        gate.end(t1)
        assertFalse("结束的轮次不再算被取消", gate.isCancelled(t1))
        val t2 = checkNotNull(gate.begin())
        gate.cancel(t1)
        assertFalse("旧 token 的迟到取消不得污染新一轮", gate.isCancelled(t2))
        assertFalse("未知 token 一律不生效", gate.isCancelled(9_999L))
        assertTrue("持有所有权时不得领取第二轮", gate.hasTask())
        assertNull(gate.begin())
    }

    @Test
    fun `终态归类真值表`() {
        assertEquals(ArcResultKind.DONE, arcResultKind(result(ok = true)))
        assertEquals(ArcResultKind.CANCELLED, arcResultKind(result(error = ArcRepairError.CANCELLED)))
        assertEquals(ArcResultKind.NO_NEED, arcResultKind(result(error = ArcRepairError.NO_NEED)))
        assertEquals(
            "已封口待发布自成一类（不是失败，也不是成功）",
            ArcResultKind.PENDING_PUBLISH,
            arcResultKind(result(error = ArcRepairError.PUBLISH_PENDING, pendingPublish = true))
        )
        assertEquals(ArcResultKind.FAILED, arcResultKind(result(error = ArcRepairError.GPU)))
        assertFalse("取消不得落进普通失败文案", arcResultKind(result(error = ArcRepairError.CANCELLED)) == ArcResultKind.FAILED)
    }
}