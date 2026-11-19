package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B13 · PREPARE 期第二按不许再排一次 start（工作线程会静默拒绝）。
 *
 * ## 缺陷
 * 快门入口 `onRecordClick` 的重入守卫读的是 [com.wotagei.cam.camera.RecordStatus]，而
 * `RecordRunner.start` 只是 handler.post：insert + 编码器准备 1~2s 里状态仍是 IDLE。按钮态
 * 也等状态回流才变忙碌 ⇒ 这段时间的第二次按下会再排一次 start，工作线程以「非 IDLE 忽略重复
 * start」吞掉——用户看到的是按钮没反应，也分不清是没按到还是正在准备。
 *
 * ## 修法
 * 按下起录的同一下拉响一根本地闩锁（合成 PREPARE）：按钮立即画忙碌，闩锁挡住同一段准备期的
 * 第二按；`recStatus` 一离开 IDLE 就交还给真实状态机，后续 PREPARE（点停挂 pendingStop）与
 * START 语义不变。
 *
 * # 判据
 * `CameraScreen.kt` 的 Compose 状态只能真机证；这里按 [KotlinSourceScan] 遮蔽后锁接线与次序
 * （不做自写括号配对——本项目只信遮蔽器），并配突变自证（拆闩锁 / 拆忙碌态 / 改坏交还 /
 * 把守卫挪到预览提示之后都必须报红）。
 */
class RecordPreparePressGuardTest {

    private val screenSrc by lazy { KotlinSourceScan.mainSourceText("ui/CameraScreen.kt") }

    /** 遮蔽 + 折叠空白：注释/字面量里的「闩锁」「开始」字样不参与判定 */
    private val flat by lazy { KotlinSourceScan.flatten(KotlinSourceScan.codeOnly(screenSrc)) }

    private val gate = "startQueued -> Unit"
    private val previewGate =
        "ui.preview != PreviewStatus.ING -> showTip(app.getString(R.string.cam_wait_preview))"
    private val latch = "startQueued = true"
    private val startCall = "runner.start("
    private val busy = "busy = startQueued || recStatus == RecordStatus.PREPARE"
    private val handBack =
        "LaunchedEffect(recStatus) { if (recStatus != RecordStatus.IDLE) startQueued = false }"

    @Test
    fun `PREPARE未回流前的第二按被闩锁挡住且先于预览提示`() {
        val issues = issues(flat)
        assertEquals("B13 快门第二按守卫缺件：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `尺子自己能红_拆闩锁_挪次序_拆忙碌态_改坏交还必须报红`() {
        assertTrue(
            "拆掉闩锁分支后守卫仍绿——尺子失效",
            issues(flat.replace(gate, ""))
                .any { it.contains("第二按没有被本地闩锁挡住") }
        )
        // 把守卫挪到预览提示之后：台词还在、次序已坏，必须由次序那条判据抓住
        val afterPreview = flat.replace(gate, "").replace(previewGate, "$previewGate $gate")
        assertTrue(
            "守卫挪到预览提示之后仍绿——次序判据失效",
            issues(afterPreview).any { it.contains("必须排在预览提示之前") }
        )
        assertTrue(
            "按钮忙碌态不再吃闩锁后守卫仍绿——尺子失效",
            issues(flat.replace(busy, "busy = recStatus == RecordStatus.PREPARE"))
                .any { it.contains("忙碌态") }
        )
        assertTrue(
            "交还条件反过来后守卫仍绿——尺子失效",
            issues(flat.replace(handBack, handBack.replace("!= ", "== ")))
                .any { it.contains("交还") }
        )
    }

    /** 接线问题清单；空 = 干净。输入是遮蔽+折叠后的文本（突变自证也走同一入口）。 */
    private fun issues(src: String): List<String> {
        val bad = ArrayList<String>()
        val gateAt = src.indexOf(gate)
        val startAt = src.indexOf(startCall)
        if (startAt < 0) return listOf("锚点丢失：定位不到起录调用 runner.start——守卫失去输入")

        if (gateAt < 0) {
            bad.add("B13：PREPARE 未回流前的第二按没有被本地闩锁挡住——会再排一次 start 被工作线程静默拒绝")
        }
        val previewAt = src.indexOf(previewGate)
        if (previewAt < 0) {
            bad.add("锚点丢失：定位不到预览就绪门")
        } else if (gateAt > previewAt) {
            bad.add("B13：第二按守卫必须排在预览提示之前（排队中不许落进 cam_wait_preview 分支）")
        }
        val latches = KotlinSourceScan.occurrences(src, latch)
        when {
            latches.size != 1 ->
                bad.add("B13：起录闩锁必须恰拉响一次，实际 ${latches.size} 次")
            latches.single() > startAt ->
                bad.add("B13：闩锁必须先于 runner.start——start 只是 handler.post，状态回流前还有一整拍")
        }
        if (!src.contains(busy)) {
            bad.add("B13：录制键忙碌态没吃本地闩锁——按下到 PREPARE 回流之间按钮仍是待机态")
        }
        if (!src.contains(handBack)) {
            bad.add("B13：闩锁没在状态机接管（离开 IDLE）后交还——真实 PREPARE/START 语义会被本地态盖住")
        }
        return bad
    }
}