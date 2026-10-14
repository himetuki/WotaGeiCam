package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「ensureSession 输出面构造必须落进防御」的源码级接线守卫（2026-10-13 真机 P0 崩溃）。
 *
 * 缺陷本体（Mate 60 Pro / Android 16）：录制反复失败重试期间，GL/编码侧已 release 旧输出面，
 * 引擎 targets 列表仍攥着失效 surface；下一次 onOpened → ensureSession 时
 * `OutputConfiguration(surface)` 构造内部 `SurfaceUtils.getSurfaceSize` 对死面直接抛
 * IllegalArgumentException("Surface was abandoned")——该构造裸在 try 外，异常沿 WotaCamEngine
 * 线程未捕获即终止进程。修复：构造收进 try 表达式，IAE 走既有 failDevice(OPEN_FAILED_INNER_ERROR)
 * 优雅收尾（与 createCaptureSession 的 IAE 同一失败码）。
 *
 * 判据作用在 codeOnly 遮蔽后的**代码结构**上（注释与字符串字面量不参与判定）：
 * 「val configs = try { … } catch (IllegalArgumentException) { failDevice(OPEN_FAILED_INNER_ERROR…) ; return }」
 * 这副骨架一旦被拆回裸 `targets.map`（回潮 = 崩溃回归），尺子必须当场红。
 */
class SessionSurfaceAbandonGuardTest {

    private fun ensureSessionBody(): String =
        KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt")),
                "ensureSession"
            )
        )

    /** ensureSession 体内「configs 构造的防御骨架」体检：返回违规清单，空 = 接线完整 */
    private fun violations(body: String): List<String> {
        val bad = ArrayList<String>()
        val tryStart = body.indexOf("val configs = try {")
        if (tryStart < 0) {
            bad.add("OutputConfiguration 构造没包进 try 表达式（val configs = try { 缺失，裸构造 = Surface abandoned 崩溃回潮）")
            return bad
        }
        val sessionStart = body.indexOf("val config = SessionConfiguration")
        if (sessionStart < 0) {
            bad.add("锚点丢失：没截到 SessionConfiguration 构造（ensureSession 形状变了，守卫需同步）")
            return bad
        }
        if (sessionStart < tryStart) {
            bad.add("configs 的 try 必须先于 SessionConfiguration（顺序反 = 构造根本没在防御里）")
            return bad
        }
        val defense = body.substring(tryStart, sessionStart)
        if (!defense.contains("OutputConfiguration(surface)")) {
            bad.add("OutputConfiguration(surface) 构造不在 configs 的 try 区间内（裸奔在防御之外）")
        }
        if (!defense.contains("catch (e: IllegalArgumentException)")) {
            bad.add("configs 的 try 缺 catch (IllegalArgumentException)——Surface abandoned 没人接住")
        }
        val fail = defense.indexOf("failDevice(DeviceStatus.OPEN_FAILED_INNER_ERROR,")
        if (fail < 0) {
            bad.add("catch 分支缺 failDevice(OPEN_FAILED_INNER_ERROR)——崩溃防护不许静默吞掉，必须走既有失败收尾")
        }
        if (fail >= 0 && defense.indexOf("return", fail) < 0) {
            bad.add("failDevice 之后必须 return（configs 没产出，继续往下走只会二次炸）")
        }
        return bad
    }

    @Test
    fun `ensureSession的OutputConfiguration构造必须落进防御且IAE走failDevice收尾`() {
        val v = violations(ensureSessionBody())
        assertEquals(
            "Surface abandoned 崩溃防护接线发现断点：\n${v.joinToString("\n")}",
            0, v.size
        )
    }

    @Test
    fun `尺子自己能红_拆防御或吞failDevice必报红`() {
        val body = ensureSessionBody()
        assertTrue("良品必须先真的绿，否则后面突变体的红没有意义", violations(body).isEmpty())
        // 突变 1：拆掉 try 包装（回退裸 targets.map = 缺陷形态，真机即崩）
        val bare = body.replace("val configs = try {", "val configs = targets.map { surface ->")
        assertTrue("拆掉防御 try 后尺子必须红", violations(bare).isNotEmpty())
        // 突变 2：catch 吞掉不收尾（failDevice 换成只留日志 = 失败无错误码、上层等不到状态）
        val tryStart = body.indexOf("val configs = try {")
        val sessionStart = body.indexOf("val config = SessionConfiguration")
        val failAt = body.indexOf("failDevice(DeviceStatus.OPEN_FAILED_INNER_ERROR,", tryStart)
        assertTrue(
            "突变前置失效：良品里 failDevice(OPEN_FAILED_INNER_ERROR) 应在 configs 的 try 区间内",
            failAt in tryStart until sessionStart
        )
        val needle = "failDevice(DeviceStatus.OPEN_FAILED_INNER_ERROR,"
        val swallowed = body.substring(0, failAt) + "Log.w(TAG," + body.substring(failAt + needle.length)
        assertTrue("catch 吞掉 failDevice 后尺子必须红", violations(swallowed).isNotEmpty())
    }
}
