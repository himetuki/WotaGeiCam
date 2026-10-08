package com.wotagei.cam

import com.wotagei.cam.record.PendingName
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pending 临时名清洗（2026-10-07 命名 race 修复）的全表行为测试。
 *
 * 真机实证的烂名形态：`.pending-<id>-` 临时段被拼前缀烙进成片名
 * （`30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4`）。
 * 规则红线：正则钉住「.pending- + 纯数字 + -」形态，正常名字里含 "pending" 字样不受影响。
 */
class PendingNameTest {

    @Test
    fun `正常名原样返回`() {
        assertEquals("VID_20261007_215822_240.mp4", PendingName.stripPendingJunk("VID_20261007_215822_240.mp4"))
        assertEquals("30fto24f_VID_20261007_215822_240.mp4", PendingName.stripPendingJunk("30fto24f_VID_20261007_215822_240.mp4"))
    }

    @Test
    fun `裸临时名剥成原名`() {
        // commit 前 provider 侧的完整临时名形态（前缀段）
        assertEquals(
            "VID_20261007_215822_240.mp4",
            PendingName.stripPendingJunk(".pending-1791986302-VID_20261007_215822_240.mp4")
        )
    }

    @Test
    fun `带前缀的中缀临时名剥段保前缀`() {
        // 真机实证形态：前缀已拼上、临时段夹在中间
        assertEquals(
            "30fto24f_VID_20261007_215822_240.mp4",
            PendingName.stripPendingJunk("30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4")
        )
    }

    @Test
    fun `多个临时段全剥`() {
        assertEquals("VID_x.mp4", PendingName.stripPendingJunk(".pending-1-.pending-22-VID_x.mp4"))
        assertEquals("abc.mp4", PendingName.stripPendingJunk("a.pending-3-b.pending-45-c.mp4"))
    }

    @Test
    fun `非数字或无点形态不误伤`() {
        // id 段必须是纯数字：字母/混合形态不是 provider 的临时名
        assertEquals(".pending-abc-VID_x.mp4", PendingName.stripPendingJunk(".pending-abc-VID_x.mp4"))
        assertEquals(".pending-12ab-VID_x.mp4", PendingName.stripPendingJunk(".pending-12ab-VID_x.mp4"))
        // 没有 `.pending-` 前缀的 pending 字样是普通名字
        assertEquals("pending-123-VID_x.mp4", PendingName.stripPendingJunk("pending-123-VID_x.mp4"))
        assertEquals("my.pending-file.mp4", PendingName.stripPendingJunk("my.pending-file.mp4"))
    }

    @Test
    fun `空串与纯段输入`() {
        assertEquals("", PendingName.stripPendingJunk(""))
        assertEquals("", PendingName.stripPendingJunk(".pending-99-"))
    }

    // ---- sidecar 归位目标（2026-10-08 幽灵 sidecar 修复）----
    // 入参/返回都是**成片路径**（搬动由 ArcDropLog.renameFor 负责拼扩展名；喂 sidecar 名会拼成
    // `.drops.drops.json`，r20 首轮真机踩过）

    @Test
    fun `sidecar归位_名字已一致时不搬`() {
        assertEquals(null, PendingName.sidecarConvergeTarget("/d/VID_1.mp4", "VID_1.mp4"))
    }

    @Test
    fun `sidecar归位_回查名带临时段时按剥净名归位`() {
        // 真机幽灵形态：成片/sidecar 都还落在 pending 名下
        assertEquals(
            "/d/VID_1.mp4",
            PendingName.sidecarConvergeTarget("/d/.pending-9-VID_1.mp4", ".pending-9-VID_1.mp4")
        )
        // 回查名已剥净、当前名仍是临时名 ⇒ 同样搬
        assertEquals(
            "/d/VID_1.mp4",
            PendingName.sidecarConvergeTarget("/d/.pending-9-VID_1.mp4", "VID_1.mp4")
        )
    }

    @Test
    fun `sidecar归位_回查失败时按当前名剥段兜底`() {
        assertEquals(
            "/d/VID_1.mp4",
            PendingName.sidecarConvergeTarget("/d/.pending-9-VID_1.mp4", null)
        )
        // 当前名本来就干净、回查又失败 ⇒ 无从判断，不搬（null）
        assertEquals(null, PendingName.sidecarConvergeTarget("/d/VID_1.mp4", null))
        // 空串按回查失败处理（queryDisplayName 可能给出空串）
        assertEquals("/d/VID_1.mp4", PendingName.sidecarConvergeTarget("/d/.pending-9-VID_1.mp4", ""))
    }

    @Test
    fun `sidecar归位_无目录前缀与畸形路径`() {
        assertEquals(
            "VID_1.mp4",
            PendingName.sidecarConvergeTarget(".pending-9-VID_1.mp4", "VID_1.mp4")
        )
        // 成片改名（加前缀）后 sidecar 必须跟着走
        assertEquals(
            "/x/y/30fto24f_VID_2.mp4",
            PendingName.sidecarConvergeTarget("/x/y/VID_2.mp4", "30fto24f_VID_2.mp4")
        )
        assertEquals(null, PendingName.sidecarConvergeTarget("", "VID_1.mp4"))
        assertEquals(null, PendingName.sidecarConvergeTarget("/d/", "VID_1.mp4"))
    }

    /**
     * 接线守卫：sidecar 归位必须**与"是否加前缀"无关**。
     *
     * 真机缺陷形态：归位原先嵌在"加前缀改名"分支里（条件是源实测帧率可用），不加前缀的会话整块跳过
     * ⇒ 每会话留一条 `.pending-<id>-….drops.json` 幽灵。这条守卫钉三件事：①归位调用在 `stopInternal`
     * 里且走 `sidecarConvergeTarget` 单一出口；②它的偏移**晚于**前缀决策（即在改名动作之后统一收口）；
     * ③`stopInternal` 里 `srcFps` 只出现一次——若有人把归位重新塞回 `srcFps` 门下，这里会变 2 而红。
     */
    @Test
    fun `sidecar归位不许挂在前缀分支里`() {
        val screen = codeOnly(mainSourceText("ui/CameraScreen.kt"))
        val body = bodyOf(screen, "stopInternal")
        assertTrue("归位必须走 sidecarConvergeTarget 单一出口", body.contains("PendingName.sidecarConvergeTarget("))
        val converge = body.indexOf("sidecarConvergeTarget(")
        val prefixDecision = body.lastIndexOf("convertNamePrefix(")
        assertTrue("stopInternal 里应有前缀决策与归位两处锚点", converge > 0 && prefixDecision > 0)
        assertTrue("归位必须排在前缀改名之后统一收口（偏移 $converge vs $prefixDecision）", converge > prefixDecision)
        // 真正的红线：归位块自己不许再拿"实测源帧率"把关——那正是旧缺陷（不加前缀的会话整块跳过）。
        // 只看归位锚点之后的切片：前缀决策区在它前面，用 srcFps 是对的。
        val afterConverge = body.substring(converge)
        assertTrue(
            "归位块不许用 srcFps 把关（那会把幽灵 sidecar 请回来）",
            !afterConverge.contains("srcFps")
        )
        assertEquals("前缀改名决策只该有一处", 1, body.split("convertNamePrefix(").size - 1)
        // 语义钉住：converge 与 renameFor 收到的都必须是**成片路径**（renameFor 自己拼扩展名；喂
        // sidecar 名会拼成 `.drops.drops.json`——r20 首轮真机实测踩过）
        assertTrue(
            "converge 必须收成片路径 path",
            body.contains("PendingName.sidecarConvergeTarget(path, queriedName)")
        )
        assertTrue(
            "renameFor 必须收成片路径 path + converge 目标",
            body.contains("ArcDropLog.renameFor(path, target)")
        )
    }

    @Test
    fun `commitRenameTarget单一出口`() {
        // 单一出口 = 前缀 + 剥净名：fetchedName 取到临时名也不怕
        assertEquals(
            "24fto24f_VID_x.mp4",
            PendingName.commitRenameTarget("24fto24f_", ".pending-7-VID_x.mp4")
        )
        assertEquals(
            "24fto24f_VID_x.mp4",
            PendingName.commitRenameTarget("24fto24f_", "VID_x.mp4")
        )
        // 等价于前缀 + stripPendingJunk（组合关系钉住，防止两套清洗规则漂移）
        assertEquals(
            "p_" + PendingName.stripPendingJunk(".pending-5-n.mp4"),
            PendingName.commitRenameTarget("p_", ".pending-5-n.mp4")
        )
    }
}
