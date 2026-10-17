package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线光弧修复链的**打戳失败必须整任务失败**（2026-10-11）。
 *
 * 缺陷本体：`ArcRepairGl.stampPresentation` 原来返回 Unit，`emitOnGl` 照常
 * `eglSwapBuffers`、调用方也无从得知 ⇒ 坏戳进容器。
 *
 * 与录制路 M2-06 的三点差异（决定"照抄录制路"并不成立，必须各自论证）：
 * 1. 本链**不产 `.drops.json` sidecar**（`ArcDropLog` 写入方只有 `CodecRecorder`），
 *    没有「账与容器分家」那一重对账；
 * 2. `ArcRepairGl.basePtsNs` 硬编码 0，没有可被污染的「上一戳地板」；
 * 3. **但危害被 basePtsNs = 0 放大**——正常戳是从 0 起的小数值，打戳失败时 EGL 回落到换帧墙钟
 *    （`System.nanoTime()` 量级），会在两枚小值戳之间插入一枚量级完全不同的戳；
 *    坏戳一旦进容器**无法自愈且用户不可见**，而离线任务重跑成本低、坏片不可逆。
 *
 * 判据归属：`docs/scan/S5` 对本链判「合规」看的是「有没有日志」，`S1-M2-06` 判「缺陷」看的是
 * 「账与容器是否一致」——本修复以 M2-06 判据为准，两者不冲突。
 */
class ArcRepairGlStampGuardTest {

    /** 接线体检：返回违规清单，空 = 接线完整（真身与突变体同吃，本仓既有范式） */
    private fun stampViolations(maskedGl: String, maskedRunner: String): List<String> {
        val bad = ArrayList<String>()

        // ① 返回值：签名必须声明 Boolean，且真值必须来自 runCatching 的 isSuccess
        val r = regionOf(maskedGl, "stampPresentation")
        val sig = maskedGl.substring(maskedGl.lastIndexOf("private fun stampPresentation", r.start), r.start)
        if (!sig.contains("): Boolean")) bad.add("stampPresentation 必须返回 Boolean（调用方要能判失败）")
        val stamp = flatten(bodyOf(maskedGl, "stampPresentation"))
        if (!stamp.contains("val ok = runCatching")) bad.add("打戳结果必须取自 runCatching")
        if (!stamp.contains(".isSuccess")) bad.add("打戳结果必须取自 runCatching 的 isSuccess")
        if (!stamp.contains("return ok")) bad.add("必须把 isSuccess 原样返回（恒 true = 吞掉失败）")
        if (!stamp.contains("stampFailCount++")) bad.add("打戳失败必须计次（本链无 sidecar，日志就是全部痕迹）")

        // ② 消费 + 顺序：失败检查早于 swap
        val emit = flatten(bodyOf(maskedGl, "emitOnGl"))
        if (!emit.contains("if (!stampPresentation(ptsUs)) return false")) {
            bad.add("emitOnGl 必须消费 stampPresentation 的返回值并早退（不消费 = 坏戳照发）")
        }
        val stampAt = emit.indexOf("stampPresentation(")
        val swapAt = emit.indexOf("eglSwapBuffers(")
        if (stampAt < 0) bad.add("锚点丢失：没截到 stampPresentation 调用点")
        if (swapAt < 0) bad.add("锚点丢失：没截到 eglSwapBuffers")
        if (stampAt >= 0 && swapAt >= 0 && stampAt > swapAt) {
            bad.add("打戳失败检查必须早于 eglSwapBuffers（晚于 swap 就已经把坏帧换出去了）")
        }

        // ③ 结构：失败不推进 ArcRepairRunner 侧的 countOutput
        val execGpu = flatten(bodyOf(maskedRunner, "execGpu"))
        val throwAt = execGpu.indexOf("if (!gl.emitPending(op.ptsUs)) throw ArcFail(ArcRepairError.GPU)")
        val countAt = execGpu.indexOf("countOutput(1)")
        if (throwAt < 0) bad.add("锚点丢失：没截到 emitPending 失败即抛 ArcFail(GPU)")
        if (countAt < 0) bad.add("锚点丢失：没截到 countOutput(1)")
        if (throwAt >= 0 && countAt >= 0 && throwAt > countAt) {
            bad.add("countOutput 必须在失败抛异常之后（失败也推进输出帧数 = 进度与成片分家）")
        }
        return bad
    }

    /**
     * 截出 `acquire` 的文本片段。**不能用 [bodyOf]**：`acquire` 是表达式体
     * （`fun acquire(): Boolean = postAndWait(OP_WAIT_MS) { … }`），
     * [com.wotagei.cam.source.KotlinSourceScan] 的 `regionAfterDecl` 撞上 `=` 即判"无花括号体"并抛。
     * 这里按"下一个 `fun ` 之前"切片——`acquire` 体内没有嵌套函数声明，切片边界稳定。
     */
    private fun acquireSlice(maskedGl: String): String {
        val start = maskedGl.indexOf("fun acquire(")
        if (start < 0) throw AssertionError("源码里找不到 fun acquire(——守卫失去输入，不许算通过")
        val next = maskedGl.indexOf("fun ", start + "fun acquire(".length)
        return maskedGl.substring(start, if (next < 0) maskedGl.length else next)
    }

    /**
     * M6-05 观测接线体检：`acquire()` 里的"被 SurfaceTexture 合帧数"必须**纯观测**——只增、
     * 不改戳、不进抽帧判定（落 WARN 只在积压创新高/首次命中时，避免管线长期慢于解码器时按
     * 帧率刷屏），且既有帧账（`framesConsumed++`）与既有调用次序逐字不变。
     *
     * 为什么必须钉住"不改戳"：`updateTexImage` 吞掉的中间帧不进任何既有计数，抽帧计划的
     * `srcIndex` 因此可能错位——这条观测就是为它留痕的。但它一旦反过来去改 PTS / 干预抽取，
     * 就从"留痕"变成"第二个真相源"，与 `stampPresentation` 的单一时基口径分家（本仓最怕的分叉）。
     */
    private fun mergeObsViolations(maskedGl: String): List<String> {
        val bad = ArrayList<String>()
        val acq = flatten(acquireSlice(maskedGl))

        // ① 观测必须存在且是**只读**取证：积压 = framesQueued − framesConsumed（不改任一计数）
        if (!acq.contains("val backlog = synchronized(frameLock) { framesQueued - framesConsumed }")) {
            bad.add("观测失去输入：acquire 必须只读取积压 framesQueued − framesConsumed")
        }
        // ② 计数只增 + 峰值只增
        if (!acq.contains("mergedFrames += merged")) bad.add("合帧计数必须只增（缺 mergedFrames +=）")
        if (!acq.contains("if (merged > mergedMaxBack) { mergedMaxBack = merged")) {
            bad.add("单次最大积压必须按峰值更新（缺 if (merged > mergedMaxBack) { mergedMaxBack = merged）")
        }
        // ②′ 日志必须被「创新高」分支包住：管线长期慢于解码器时 backlog>1 几乎每帧成立
        //     （正是本观测要抓的场景），日志若落在 if 之外就以帧率频率刷 WARN（长片数万行）
        //     淹掉有用诊断。计数语义不变（只增），只把落日志收敛到首次命中/创新高。
        val peakOpen = acq.indexOf("if (merged > mergedMaxBack) {")
        val logAt = acq.indexOf("Log.w(")
        if (logAt < 0) bad.add("合帧观测失去痕迹：WARN 日志被整个删掉")
        if (peakOpen >= 0 && logAt >= 0 && peakOpen > logAt) {
            bad.add("合帧日志必须落在「创新高」分支内（现落在 if 之前 = 命中即每帧落一条）")
        }
        if (peakOpen >= 0) {
            val peakClose = acq.indexOf('}', peakOpen)
            val guarded = if (peakClose > peakOpen) acq.substring(peakOpen, peakClose) else ""
            if (!guarded.contains("Log.w(")) {
                bad.add("合帧日志必须与峰值更新同处「创新高」分支（落在分支外 = 命中即每帧落一条）")
            }
        }
        listOf("mergedFrames -=", "mergedFrames--", "mergedMaxBack -=", "mergedMaxBack--").forEach { dec ->
            if (acq.contains(dec)) bad.add("合帧计数出现递减 $dec（观测只增，不许回落）")
        }
        // ③ 计数器不得门控抽帧（只许出现在观测/日志里，不许进 if 条件）
        if (acq.contains("if (mergedFrames")) bad.add("合帧计数进了控制流（if (mergedFrames…)）——观测不得干预抽帧")
        if (acq.contains("if (mergedMaxBack")) bad.add("峰值计数进了控制流（if (mergedMaxBack…)）——观测不得干预抽帧")
        // ④ 绝不碰 PTS：观测块里不许出现任何打戳/换面符号
        listOf("eglPresentationTimeANDROID", "stampPresentation", "eglSwapBuffers", "ptsUs").forEach { pts ->
            if (acq.contains(pts)) bad.add("观测块碰了 PTS（出现 $pts）——合帧观测只许计次落日志")
        }
        // ⑤ 既有帧账与调用次序逐字不变：backlog 取在读帧之前，帧账自增在拷贝之后
        val backlogAt = acq.indexOf("val backlog = synchronized(frameLock)")
        val updateAt = acq.indexOf("st.updateTexImage()")
        val copyAt = acq.indexOf("copyOesToCur()")
        val consumeAt = acq.indexOf("synchronized(frameLock) { framesConsumed++ }")
        if (updateAt < 0) bad.add("锚点丢失：没截到 st.updateTexImage()")
        if (copyAt < 0) bad.add("锚点丢失：没截到 copyOesToCur()")
        if (consumeAt < 0) bad.add("锚点丢失：没截到 framesConsumed++（既有帧账被挪走/删掉）")
        if (backlogAt >= 0 && updateAt >= 0 && backlogAt > updateAt) {
            bad.add("积压必须在 updateTexImage 之前取（之后再读就不是被吞前的积压了）")
        }
        if (copyAt >= 0 && consumeAt >= 0 && copyAt > consumeAt) {
            bad.add("既有次序被改：framesConsumed++ 必须在拷贝之后（提前自增会与既有抽帧节拍分家）")
        }
        // ⑥ 计数声明必须在类属性处按 0 起（不许挂在别处初始化成非零）
        if (!maskedGl.contains("private var mergedFrames = 0L")) bad.add("缺声明 private var mergedFrames = 0L")
        if (!maskedGl.contains("private var mergedMaxBack = 0L")) bad.add("缺声明 private var mergedMaxBack = 0L")
        return bad
    }

    @Test
    fun `合帧观测只增不改戳接线完整`() {
        val bad = mergeObsViolations(codeOnly(mainSourceText("record/ArcRepairGl.kt")))
        assertEquals("合帧观测接线发现断点：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    /**
     * 突变自证：把观测退回"会改行为"或"消失"的形态必报红。六枚突变体各对应一种真实退化——
     * **计数递减**（不再是单调账）、**观测块写戳**（第二真相源）、**计数门控抽帧**（观测变干预）、
     * **删掉积压读取**（观测失去输入）、**帧账次序被改**（既有节拍被动）、**日志挪出创新高分支**
     * （命中即每帧落一条 WARN = 长片刷屏）；另配一组只加注释的对照。
     */
    @Test
    fun `尺子自己能红_合帧观测突变体必报红`() {
        val gl = codeOnly(mainSourceText("record/ArcRepairGl.kt"))
        assertEquals("良品必须先真的绿", 0, mergeObsViolations(gl).size)

        val countLine = "            mergedFrames += merged\n"
        val peakLine = "            if (merged > mergedMaxBack) {\n"
        val backlogLine = "        val backlog = synchronized(frameLock) { framesQueued - framesConsumed }\n"
        val consumeLine = "        synchronized(frameLock) { framesConsumed++ }\n"
        val copyLine = "        val ok = copyOesToCur()\n"
        listOf(countLine, peakLine, backlogLine, consumeLine, copyLine).forEach {
            assertTrue("突变锚点丢失：没截到\n$it", gl.contains(it))
        }

        val mutants = listOf(
            Triple("计数递减（不再是单调账）", gl.replace(countLine, "            mergedFrames -= merged\n"), true),
            Triple("观测块写戳（第二真相源）", gl.replace(countLine, "            stampPresentation(0L)\n$countLine"), true),
            Triple("计数门控抽帧（观测变干预）", gl.replace(peakLine, "            if (mergedFrames > 5L) return@postAndWait false\n"), true),
            Triple("删掉积压读取（观测失去输入）", gl.replace(backlogLine, ""), true),
            Triple("帧账次序被改（自增挪到拷贝之前）", gl.replace(copyLine + consumeLine, consumeLine + copyLine), true),
            // 日志节流退化：把「创新高」分支拆掉，日志退回命中即每帧落一条（真机长片刷屏场景）
            Triple(
                "日志挪出创新高分支（命中即每帧落一条）",
                gl.replace(peakLine, "            if (merged > mergedMaxBack) mergedMaxBack = merged\n"),
                true
            ),
            // 对照组：只在观测行尾加注释，不许引发红
            Triple("对照组：只加注释", gl.replace(backlogLine, backlogLine.trimEnd() + " // 观测\n"), false)
        )
        mutants.forEach { (name, mutated, shouldBeRed) ->
            assertTrue("突变锚点丢失（$name 未改动源码文本）", mutated != gl)
            val red = mergeObsViolations(mutated)
            if (shouldBeRed) {
                assertTrue("突变体「$name」必须报红（实际违规：${red.joinToString("；")}）", red.isNotEmpty())
            } else {
                assertEquals("对照组「$name」不该报红（实际违规：${red.joinToString("；")}）", 0, red.size)
            }
        }
    }

    @Test
    fun `打戳失败接线完整`() {
        val bad = stampViolations(
            codeOnly(mainSourceText("record/ArcRepairGl.kt")),
            codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        )
        assertEquals("打戳失败接线发现断点：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    /**
     * 突变自证：把各处退回缺陷形态必报红。
     *
     * 五枚 gl 侧突变体对应五种真实退化：**删返回值检查**、**把检查挪到 swap 之后**、
     * **吞掉 runCatching 的失败**、**删计次**、**退回返回 Unit**；另有一条 **runner 侧**
     * 的 countOutput 位次突变（必须喂给 [stampViolations] 的 runner 参数，见下）。
     * 再配一条对照组（只改注释），保证尺子不对无害改动过敏。上一轮的坑是语义空操作突变体
     * ——这里的每个突变体都改变了可观测行为（坏帧是否被换出去 / 失败是否被吞 /
     * 进度是否照推），不可能与真身等价。
     */
    @Test
    fun `尺子自己能红_打戳失败接线突变体必报红`() {
        val gl = codeOnly(mainSourceText("record/ArcRepairGl.kt"))
        val runner = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        assertEquals("良品必须先真的绿", 0, stampViolations(gl, runner).size)

        val checkLine = "        if (!stampPresentation(ptsUs)) return false\n"
        val swapLine = "        val swapped = EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)\n"
        assertTrue("突变锚点丢失：没截到打戳检查行", gl.contains(checkLine))
        assertTrue("突变锚点丢失：没截到 swap 行", gl.contains(swapLine))

        val mutants = listOf(
            Triple(
                "删掉返回值检查（onFailure 后不回 false，缺陷本体）",
                gl.replace(checkLine, "        stampPresentation(ptsUs)\n"),
                true
            ),
            Triple(
                "把失败检查挪到 eglSwapBuffers 之后",
                gl.replace(checkLine + swapLine, swapLine + checkLine),
                true
            ),
            Triple("吞掉 runCatching 的失败（恒返回 true）", gl.replace("        return ok\n", "        return true\n"), true),
            Triple("删掉打戳失败计次", gl.replace("stampFailCount++", ""), true),
            Triple("stampPresentation 退回返回 Unit", gl.replace("private fun stampPresentation(ptsUs: Long): Boolean {", "private fun stampPresentation(ptsUs: Long) {"), true),
            // 对照组：只改注释不许引发红
            Triple("对照组：只改注释", gl.replace("private fun emitOnGl(ptsUs: Long): Boolean {", "private fun emitOnGl(ptsUs: Long): Boolean { // 注释"), false)
        )
        mutants.forEach { (name, mutated, shouldBeRed) ->
            val red = stampViolations(mutated, runner)
            if (shouldBeRed) {
                assertTrue("突变体「$name」必须报红（实际违规：${red.joinToString("；")}）", red.isNotEmpty())
            } else {
                assertEquals("对照组「$name」不该报红（实际违规：${red.joinToString("；")}）", 0, red.size)
            }
        }

        // ⑥ runner 侧：countOutput 被挪到失败抛异常之前——喂的是 **runner** 槽位。
        // （门禁 #1c 的教训：这枚突变体曾被放进 gl 槽名单，forEach 把它当 maskedGl 传进去，
        //   regionOf 在 ArcRepairRunner.kt 里找 fun stampPresentation 找到 0 个函数体直接炸
        //   「失去输入」——突变体本身没问题，是喂错了参数。execGpu 现序是 throw(:721) →
        //   countOutput(:722)，把 countOutput 注到 throw 之前，顺序判据必红。）
        val runnerMut = runner.replace(
            "if (!gl.emitPending(op.ptsUs)) throw ArcFail(ArcRepairError.GPU)",
            "countOutput(1); if (!gl.emitPending(op.ptsUs)) throw ArcFail(ArcRepairError.GPU)"
        )
        assertTrue("突变锚点丢失：没截到 emitPending 失败即抛 ArcFail(GPU) 行", runnerMut != runner)
        val red6 = stampViolations(gl, runnerMut)
        assertTrue(
            "突变体「countOutput 被挪到失败抛异常之前」必须报红（实际违规：${red6.joinToString("；")}）",
            red6.isNotEmpty()
        )
    }
}
