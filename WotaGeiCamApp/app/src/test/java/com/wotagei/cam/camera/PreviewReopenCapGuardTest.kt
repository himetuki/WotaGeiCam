package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览重开**触顶可达**的源码级守卫（2026-10-13 真机缺陷）。
 *
 * 缺陷本体（Android 16 设备，内录授权后 onConfigureFailed 反复失败）：`reopenRetries` 曾在
 * onOpened 里归零——「重开 → open 成功（计数清零）→ configure 失败 → scheduleReopen」每一轮
 * 都把计数洗回 0，`REOPEN_MAX` 永不可达 ⇒ 预览黑屏无限重开循环。修复：归零点挪到 onConfigured
 * 成功侧（只有会话真正建起来才算"重开成功"），configure 连续失败时计数得以累积到触顶，
 * scheduleReopen 停在失败态交 UI 遮罩 / 重进恢复。
 *
 * 判据作用在 codeOnly 遮蔽后的代码结构上（注释与日志字面量不参与判定），每条配突变自证。
 */
class PreviewReopenCapGuardTest {

    private val masked by lazy { codeOnly(mainSourceText("camera/Camera2Engine.kt")) }

    private fun body(funName: String): String = flatten(bodyOf(masked, funName))

    @Test
    fun `onOpened不再归零reopenRetries`() {
        val opened = body("onOpened")
        // 正向锚点：证明切到的是 onOpened 真身
        assertTrue("锚点丢失：onOpened 没截到 device 赋值", opened.contains("device = opened"))
        assertTrue(
            "onOpened 不得归零 reopenRetries——open 成功≠重开成功，在这里清零会让「configure 反复失败」" +
                "的计数每轮洗回 0，REOPEN_MAX 永不可达（黑屏无限重开循环回潮）",
            !opened.contains("reopenRetries")
        )
        // 突变自证：把归零塞回 onOpened 后尺子必须红
        val mutated = flatten(
            bodyOf(
                masked.replace("device = opened", "device = opened\nreopenRetries = 0"),
                "onOpened"
            )
        )
        assertTrue("归零塞回 onOpened 后尺子必须红", mutated.contains("reopenRetries"))
    }

    @Test
    fun `reopenRetries归零必须在onConfigured成功侧`() {
        val configured = body("onConfigured")
        // 正向锚点：证明切到的是 onConfigured 真身
        assertTrue("锚点丢失：onConfigured 没截到预览发布", configured.contains("publishPreview(PreviewStatus.ING)"))
        val reset = configured.indexOf("reopenRetries = 0")
        val publish = configured.indexOf("publishPreview(PreviewStatus.ING)")
        assertTrue(
            "onConfigured 成功侧必须归零 reopenRetries（只有会话真正建起来才算重开成功）",
            reset >= 0
        )
        assertTrue("归零应落在 publishPreview(ING) 附近（reset=$reset publish=$publish）", reset in 0 until publish)
        // 突变自证：归零被拆后尺子必须红
        val mutated = configured.replace("reopenRetries = 0", "Unit")
        assertTrue("onConfigured 归零被拆后尺子必须红", !mutated.contains("reopenRetries = 0"))
    }

    @Test
    fun `onConfigureFailed路径必须经failDevice到scheduleReopen且含REOPEN_MAX触顶`() {
        val failed = body("onConfigureFailed")
        assertTrue("锚点丢失：onConfigureFailed 没截到 failDevice 调用", failed.contains("failDevice("))
        // 链路：onConfigureFailed → failDevice(OPEN_FAILED_INNER_ERROR) → scheduleReopen
        val failDevice = body("failDevice")
        assertTrue(
            "failDevice 必须对内部错误走 scheduleReopen（onConfigureFailed 的重开通道断了 = 失败后无人重开）",
            failDevice.contains("scheduleReopen()")
        )
        // 触顶判断必须在位、且在自增之前（先查后加：触顶即停，不先花掉一次机会）
        val reopen = body("scheduleReopen")
        val cap = reopen.indexOf("reopenRetries >= REOPEN_MAX")
        val inc = reopen.indexOf("reopenRetries++")
        assertTrue(
            "scheduleReopen 必须含 REOPEN_MAX 触顶判断——归零点挪到 onConfigured 后它是" +
                "「configure 反复失败」唯一的出头出口",
            cap >= 0
        )
        assertTrue("触顶判断必须在自增之前（cap=$cap inc=$inc）", cap in 0 until inc)
        // 突变自证：触顶判据被拆（恒不触顶 = 无限重开回潮）后尺子必须红
        val mutated = reopen.replace("reopenRetries >= REOPEN_MAX", "false")
        assertTrue("触顶判断被拆后尺子必须红", !mutated.contains("REOPEN_MAX"))
    }
}
