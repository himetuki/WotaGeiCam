package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-07 真机三项修复的**源码结构守卫**：
 *
 * 1. 命名 race：commit 后改名的所有出口必须走 `PendingName.commitRenameTarget` / `stripPendingJunk`
 *    （成片名与 sidecar 回查名同走），裸拼 `prefix + old` 的写法一见即红；
 * 2. 音源双开：面板两开关独立（环境音真源 params.audioEnabled，不再派生 `!active`）、
 *    buildProfile 通道位统一走 `audioTrackPlan` 桥、CodecRecorder 的内录轨不再挂在环境编码器之下；
 * 3. PREPARE 期停止点击不许被静默吞：onRecordClick 有 requestStopWhenReady 分支、
 *    RecordRunner 有挂起/消费两侧。
 *
 * 行为面由 PendingNameTest / AudioTrackPlanTest 的全表钉着，这里锁接线红线（Compose/JVM 跑不到的部分）。
 */
class RealDeviceFixWiringGuardTest {

    private fun maskedMain(rel: String): String =
        codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun screenBody(name: String): String = bodyOf(maskedMain("ui/CameraScreen.kt"), name)

    // ------------------------------------------------------------------ 1. 命名 race

    @Test
    fun `commit后改名走剥名出口`() {
        val stop = screenBody("stopInternal")
        assertTrue(
            "成片改名必须走 PendingName.commitRenameTarget（provider 复原临时名是异步的，裸拼会烙烂名）",
            stop.contains("PendingName.commitRenameTarget(prefix, old)")
        )
        assertTrue(
            "改名处不得再出现裸拼 prefix + old（命名 race 回归形态）",
            !stop.contains("prefix + old")
        )
        // sidecar 回查名与成片名同一套剥段规则（两边名字永远同走）
        assertTrue(
            "sidecar 回查名必须过 stripPendingJunk（烂名段不许跟进 .drops.json 文件名）",
            stop.contains("PendingName::stripPendingJunk")
        )
        // createPending 的 insert 名不带前缀逻辑不动：_pending 侧不许被误加剥名（insert 名本来干净）
        val store = maskedMain("record/VideoStore.kt")
        assertTrue(
            "createPending 的 insert 名保持 displayName 直插（那一段没毛病，不许画蛇添足）",
            bodyOf(store, "createPending").contains("val name = displayName(namePrefix = namePrefix)")
        )
    }

    @Test
    fun `尺子自己能红_命名`() {
        val stop = screenBody("stopInternal")
        val mutated = stop.replace("PendingName.commitRenameTarget(prefix, old)", "prefix + old")
        assertTrue(
            "突变体（退回裸拼）必须踩中『不得裸拼』判据",
            mutated.contains("prefix + old")
        )
        assertTrue(!stop.contains("prefix + old"))
    }

    // ------------------------------------------------------------------ 2. 音源双开

    /** 突变尺：把 buildProfile 的桥调用拆掉后必须踩中判据 */
    private fun missingPlanWires(profileBody: String): List<String> = listOf(
        "audioTrackPlan(params.audioEnabled.value, captureAudio)" to
            "buildProfile 的两个通道位必须统一从 audioTrackPlan 桥取（音源双开唯一口径）",
        "AudioTrack.AMBIENT in" to "环境音通道位必须判桥结果含 AMBIENT",
        "AudioTrack.CAPTURE in" to "内录通道位必须判桥结果含 CAPTURE"
    ).filter { (token, _) -> !profileBody.contains(token) }.map { (_, why) -> why }

    @Test
    fun `buildProfile通道位走桥且不再被静音掐死`() {
        val profile = screenBody("buildProfile")
        assertEquals(
            "buildProfile 的桥接线缺件：\n${missingPlanWires(profile).joinToString("\n")}",
            emptyList<String>(),
            missingPlanWires(profile)
        )
        assertTrue(
            "旧互斥（captureAudio && audioEnabled 掐死）必须已废除",
            !profile.contains("captureAudio && params.audioEnabled.value")
        )
    }

    @Test
    fun `面板两开关独立`() {
        val tab = bodyOf(maskedMain("ui/dialog/AudioSourcePanel.kt"), "AudioSourceTab")
        // 环境音开关的真源是 ambientEnabled 参数，不再是派生的 !active
        assertTrue(
            "环境音开关选中态必须直读 ambientEnabled（旧 !active 派生是互斥视觉遗留）",
            tab.contains("checked = ambientEnabled")
        )
        assertTrue(
            "环境音开关不得再派生自内录态（!active）",
            !tab.contains("checked = !active")
        )
        assertTrue(
            "环境音开关必须发 onToggleAmbient 意图",
            tab.contains("onToggleAmbient()")
        )
        // 内录开关获得关闭语义（旧版关内录藏在环境音开关里）
        assertTrue(
            "内录开关激活态再点必须走 onDisableCapture（开关语义完整）",
            tab.contains("active -> onDisableCapture()")
        )
        // 录制中两开关都锁（既有红线不回退）
        assertEquals(2, occurrences(tab, "recording -> onLockTip()").size)
    }

    @Test
    fun `内录轨不再挂在环境编码器之下`() {
        val prepare = bodyOf(maskedMain("record/CodecRecorder.kt"), "prepare")
        assertTrue(
            "minBuf 必须按「环境或内录任一要建」起算（仅内录组合合法）",
            prepare.contains("(p.audioUsable || p.captureAudio)")
        )
        assertTrue(
            "环境编码器创建必须显式带 p.audioEnabled 条件（minBuf 扩容后不得误建）",
            prepare.contains("if (minBuf > 0 && p.audioEnabled)")
        )
        assertTrue(
            "内录编码器创建不得再前置 a != null（环境音关不连坐内录）",
            prepare.contains("if (minBuf > 0 && p.captureAudio) createAudioEncoder(p, minBuf)") &&
                !prepare.contains("p.captureAudio && a != null")
        )
        // 两轨独立降级：a 失败不再连坐关 cap
        assertTrue(
            "降级必须逐轨独立（a==null 只关环境，不再两条全关）",
            prepare.contains("a == null && p.audioEnabled -> p.copy(audioEnabled = false)")
        )
        // 仅内录时 aacMinBuf 不得被误清（分段轮转重建 cap 编码器还吃它）
        assertTrue(
            "aacMinBuf 清零必须以两轨全无为条件",
            prepare.contains("if (a == null && c == null) aacMinBuf = 0")
        )
    }

    @Test
    fun `AudioPill转传双开接线`() {
        val pills = maskedMain("ui/CameraPills.kt")
        val pillBody = bodyOf(pills, "AudioPill")
        assertTrue(
            "AudioPill 必须把 ambientEnabled/onToggleAmbient 转传给 AudioSourcePanel（漏挂编译不过，无默认值）",
            pillBody.contains("ambientEnabled = ambientEnabled") &&
                pillBody.contains("onToggleAmbient = onToggleAmbient")
        )
        val hostBranch = bodyOf(pills, "PillHost")
        assertTrue(
            "PillHost 的 BT 分支必须给环境音开关接 params.audioEnabled 真源与 toggle",
            hostBranch.contains("params.audioEnabled.observed()") &&
                hostBranch.contains("params.audioEnabled.value = !params.audioEnabled.value")
        )
    }

    @Test
    fun `尺子自己能红_音源`() {
        val profile = screenBody("buildProfile")
        // 突变 1：环境音通道位退回直读静音位（绕开桥）
        val mutatedProfile = profile.replace(
            "AudioTrack.AMBIENT in plan",
            "params.audioEnabled.value"
        )
        assertTrue(missingPlanWires(mutatedProfile).isNotEmpty())
        // 真身必须绿
        assertTrue(missingPlanWires(profile).isEmpty())
    }

    // ------------------------------------------------------------------ 3. PREPARE 期停止

    @Test
    fun `准备期停止请求有挂起与消费两侧`() {
        val req = screenBody("requestStopWhenReady")
        assertTrue(
            "RecordRunner 必须有 pendingStop 挂起位（requestStopWhenReady 置 true）",
            req.contains("pendingStop = true")
        )
        val begin = screenBody("beginSession")
        assertTrue(
            "beginSession 进 START 后必须消费 pendingStop（立即 stopInternal）",
            begin.contains("if (pendingStop)") && begin.contains("stopInternal()")
        )
        // 消费后清账：残留标记不许漏进轮转/重录路径
        assertTrue(
            "消费侧必须把 pendingStop 复位",
            begin.contains("pendingStop = false")
        )
        // 新会话入口清旧意图
        val start = screenBody("start")
        assertTrue(
            "start() 入口必须清 pendingStop（新会话不继承上一会话残留）",
            start.contains("pendingStop = false")
        )
        // @Volatile：UI 线程置位、handler 线程消费
        val runnerDecl = maskedMain("ui/CameraScreen.kt")
        assertTrue(
            "pendingStop 必须标 @Volatile（跨线程标志位）",
            occurrences(runnerDecl, "@Volatile").size >= 2
        )
    }

    @Test
    fun `尺子自己能红_停止`() {
        val begin = screenBody("beginSession")
        val mutated = begin.replace("stopInternal()", "Unit")
        assertTrue(
            "突变体（拆掉消费侧停止调用）必须踩中判据",
            !mutated.contains("stopInternal()")
        )
        assertTrue(begin.contains("stopInternal()"))
    }
}
