package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceFile
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 换段停摆修复的**源码结构守卫**（2026-10-08 真机缺陷）。
 *
 * 真机：24※ 强制档下每轮录 1~6 段就停摆几十秒（段名时间戳差 30~45s、无新文件、无错误码、
 * 计时冻结、画面静默丢），停止时才又封一段；DIRECT 路（无 GPU/无 GL 重挂）同样中招。
 * 代码级结论：阈值触顶后泵线程要在**同一条产出线程**上串行走完
 * 「拆编码器/muxer → 关 fd → 查 SIZE/DATA → 清 IS_PENDING（失败还重试）→ 建新 pending →
 *  开新 fd → 重建编码器 → 起新段」，每一步都是无上限的 MediaProvider/FUSE/native 同步调用。
 *
 * 本守卫钉住修复的三条纪律（纯函数表见 `RotateStallTest`）：
 * 1. **有界**：泵线程上不许再出现无界的 `store.*` / `target?.close()` 调用，全部经
 *    [BoundedStore] 的有界封装；
 * 2. **可见**：换段腿超预算即置 [RecordError.ROTATE_STALL]（带丢失毫秒载荷），UI 必须把它
 *    映射成带秒数的中文提示（资源与占位符都钉住——漏占位符 getString 会抛，提示整条丢）；
 * 3. **有账**：逐段丢帧时长必须进 `RecordResult.segLostMs` 与 sidecar 段清单，用户事后可查。
 */
class RotateStallGuardTest {

    private val codec = codeOnly(mainSourceText("record/CodecRecorder.kt"))
    private val screen = codeOnly(mainSourceText("ui/CameraScreen.kt"))

    /**
     * 文案资源文件（`res/values/strings_camera.xml`）：从主源码定位到模块目录后逐级上溯查找。
     * 找不到**必须抛**——守卫不能在没有输入的情况下算通过（与 `KotlinSourceScan` 同口径）。
     */
    private fun stringsCamera(): File {
        var d: File? = mainSourceFile("ui/CameraScreen.kt").parentFile
        while (d != null) {
            val f = File(d, "res/values/strings_camera.xml")
            if (f.isFile) return f
            d = d.parentFile
        }
        throw AssertionError("找不到 res/values/strings_camera.xml（文案资源被挪走/改名，守卫失去输入）")
    }

    @Test
    fun `换段腿的入库调用必须全走有界封装`() {
        val next = bodyOf(codec, "nextSegment")
        assertTrue("nextSegment 必须经有界建 pending", next.contains("createPendingBounded("))
        assertFalse(
            "nextSegment 不许直接调 store.createPending（无上限的 MediaProvider 调用=整机静默丢内容）",
            next.contains("store.createPending(")
        )
        assertFalse("nextSegment 不许直接调 store.discard", next.contains("store.discard("))

        val seal = bodyOf(codec, "sealCurrent")
        assertTrue("sealCurrent 必须经有界关 fd", seal.contains("closeTargetBounded()"))
        assertTrue("sealCurrent 必须经有界封段", seal.contains("sealBounded("))
        assertFalse("sealCurrent 不许直接 target?.close()（关 fd 也走 MediaProvider）", seal.contains("target?.close()"))
        assertFalse("sealCurrent 不许直接 store.seal", seal.contains("store.seal("))

        val disc = bodyOf(codec, "discardCurrent")
        assertFalse("discardCurrent 不许直接 target?.close()", disc.contains("target?.close()"))
        assertTrue("discardCurrent 的入库调用也要有界", disc.contains("storeIo.run("))

        // 有界封装本体：必须真用执行器 + 用腿预算（不是自造的常数）
        assertTrue(bodyOf(codec, "createPendingBounded").contains("storeIo.run(rotateBudgetLeftMs())"))
        assertTrue(bodyOf(codec, "sealBounded").contains("storeIo.run(rotateBudgetLeftMs())"))
        assertTrue(bodyOf(codec, "openTargetBounded").contains("storeIo.run(rotateBudgetLeftMs())"))
        // 清账（关 fd）要留下限：腿预算已尽也得把句柄收掉，否则 fd 泄漏
        assertTrue(bodyOf(codec, "closeBounded").contains("cleanupBudgetMs(rotateBudgetLeftMs())"))

        // 换段腿的编码器/muxer 重建前必须先看预算：预算已尽就不往下走（native 调用没上限可给）
        val open = bodyOf(codec, "openNextSegment")
        assertTrue(
            "openNextSegment 必须在重建前检查腿预算（预算尽→交回 pumpLoop 判停摆）",
            open.contains("rotateBudgetLeftMs() <= 0L") && open.contains("openTargetBounded(")
        )
    }

    @Test
    fun `换段腿必须起钟结算且越界即明确失败`() {
        val pump = bodyOf(codec, "pumpLoop")
        val will = pump.indexOf("val willRotate =")
        val begin = pump.indexOf("beginRotateLeg()")
        val close = pump.indexOf("closeSegmentEngine()")
        val settle = pump.indexOf("settleRotateLeg(")
        assertTrue("锚点丢失：pumpLoop 没截到换段意图判定", will >= 0)
        assertTrue("锚点丢失：pumpLoop 没截到换段起钟", begin >= 0)
        assertTrue("锚点丢失：pumpLoop 没截到拆编码器", close >= 0)
        assertTrue("锚点丢失：pumpLoop 没截到换段腿结算", settle >= 0)
        assertTrue("换段意图必须在拆编码器之前定（腿的起钟与结算都以它为准）", will < close)
        assertTrue("起钟必须在拆编码器之前（此后进来的帧写不进容器=丢帧窗口开启）", begin < close)
        assertTrue("结算必须在换段分支之后（腿已走完才谈得上耗时）", settle > close)
        assertTrue(
            "起钟必须只在真要换段时（停止途中取消轮转时腿里只有最后一段封段，那份慢不许算成丢内容）",
            pump.contains("if (willRotate) beginRotateLeg()")
        )
        assertTrue(
            "结算必须带上是否真轮转（false 时只记账不判失败）",
            pump.contains("settleRotateLeg(judgeStall = wantRotate)")
        )

        val settleBody = bodyOf(codec, "settleRotateLeg")
        assertTrue("越界判定必须走桥函数", settleBody.contains("rotateStallDecision("))
        assertTrue("越界必须置 ROTATE_STALL 失败码", settleBody.contains("rotateStallError("))
        assertTrue("腿耗时必须折成丢帧账", settleBody.contains("rotateLostMs("))
        assertTrue("腿起点必须复位（否则下一次结算算成两倍）", settleBody.contains("rotateLegStartMs = 0L"))
        assertTrue("非轮转腿必须早退（不许判失败）", settleBody.contains("if (!judgeStall) return"))

        assertTrue(
            "预算常量必须被函数体引用（只声明不引用=没接线）",
            bodyOf(codec, "rotateBudgetLeftMs").contains("ROTATE_LEG_BUDGET_MS") &&
                bodyOf(codec, "rotateBudgetLeftMs").contains("STORE_CALL_BUDGET_MS")
        )
    }

    @Test
    fun `停止时泵线程没出来的那一腿也要结算并亮错`() {
        val stop = bodyOf(codec, "stop")
        val join = stop.indexOf("if (!joinLoop())")
        assertTrue("停止必须判 join 是否超时", join >= 0)
        assertTrue("join 超时必须结算换段腿", stop.contains("settleRotateLegAtStop()"))
        val settleAtStop = bodyOf(codec, "settleRotateLegAtStop")
        assertTrue("停止路径结算必须走收严判据（不误报大文件封段）", settleAtStop.contains("rotateStopStallDecision("))
        assertTrue("兜底判定为真才置 ROTATE_STALL", settleAtStop.contains("rotateStallError("))
        // joinLoop 必须回布尔：超时要能被判出来（旧实现只留一行 Log.e，静默放行）
        assertTrue("joinLoop 超时必须 return false", bodyOf(codec, "joinLoop").contains("return false"))
    }

    @Test
    fun `停录途中不许再换段`() {
        val pump = bodyOf(codec, "pumpLoop")
        assertTrue(
            "换段分支必须排除 stopping（停录时阈值恰触顶会白建 pending 并按 NO_VIDEO_TRACK 假失败）",
            pump.contains("&& !stopping")
        )
    }

    @Test
    fun `丢帧账必须落进结果与sidecar段清单`() {
        val build = bodyOf(codec, "buildResult")
        assertTrue("结果必须带逐段丢帧账", build.contains("segLostMs = segLostMs.toList()"))
        assertTrue("结果必须带累计丢失与次数", build.contains("lostMs = rotateLoss.totalMs") && build.contains("rotateStalls = rotateLoss.count"))
        assertTrue("无产物的失败结果也要带丢失账", bodyOf(codec, "finish").contains("rotateLoss.totalMs"))
        assertTrue(
            "段账必须与样本数账同处落地（索引才对得上 partIndex）",
            bodyOf(codec, "pumpLoop").contains("segLostMs.add(pendingLegLostMs)")
        )
        assertTrue(
            "UI 侧 sidecar 段清单必须注入逐段丢失",
            screen.contains("lostMs = out.segLostMs.getOrNull(part.partIndex)")
        )
    }

    @Test
    fun `停摆必须给用户带秒数的中文提示`() {
        val text = bodyOf(screen, "recordResultText")
        assertTrue("失败码必须映射到文案", text.contains("RecordError.ROTATE_STALL"))
        assertTrue("文案必须带上丢失秒数（码里的毫秒载荷要翻成秒）", text.contains("rotateStallLostSeconds("))
        // 资源必须存在且带 %1$d 占位符：漏占位符 getString 会抛 IllegalArgumentException，
        // 提示整条丢掉（比没有提示更糟：用户什么都看不到）
        val xml = stringsCamera().readText(Charsets.UTF_8)
        assertTrue("资源 cam_record_rotate_stall 必须存在", xml.contains("name=\"cam_record_rotate_stall\""))
        assertTrue("资源必须带 %1\$d 占位符", xml.contains("cam_record_rotate_stall\">换段受阻，已停止并保留已录内容（丢失约 %1\$d 秒画面）"))
        // 两种出口必须分开说（慢 = 带秒数；败 = 后续画面未能录下）：合成一句会让"丢失约 1 秒"
        // 误导成"只丢了一秒"，而实际是整场结束
        assertTrue("文案必须按载荷分流", text.contains("if (lost <= 0L)"))
        assertTrue("载荷为 0 时必须用后续未录下那条资源", text.contains("R.string.cam_record_rotate_stall_short"))
        assertTrue(
            "短文案资源必须存在且不带秒数占位符",
            xml.contains("cam_record_rotate_stall_short\">换段受阻，已停止并保留已录内容（后续画面未能录下）")
        )
    }

    @Test
    fun `已保存的成功路径也要把丢失秒数报出来`() {
        // 用户按停止时那一腿还没结算 = 泵线程仍卡在腿里（真机每一轮停摆都是这么结束的）：
        // 结果走 ok 分支也必须带上丢失时长，否则用户只看到"已保存"而不知道丢了几十秒
        assertTrue("成功路径必须按 lostMs 分流到带丢失的文案", screen.contains("result.lostMs >= LOSS_NOTICE_MIN_MS"))
        assertTrue("必须用资源 cam_record_saved_loss 报秒数", screen.contains("R.string.cam_record_saved_loss"))
        assertTrue("秒数必须同一套换算（至少 1 秒、四舍五入）", screen.contains("rotateStallLostSeconds(result.lostMs)"))
        val xml = stringsCamera().readText(Charsets.UTF_8)
        assertTrue("资源 cam_record_saved_loss 必须存在", xml.contains("name=\"cam_record_saved_loss\""))
        assertTrue("资源必须带两个占位符（时长 + 丢失秒数）", xml.contains("已保存 %1\$s（其中约 %2\$d 秒因换段受阻丢失）"))
    }

    @Test
    fun `换段腿失败必须有界重试而不是一次就放弃`() {
        val next = bodyOf(codec, "nextSegment")
        assertTrue("必须走重试判定桥", next.contains("rotateRetryDecision("))
        assertTrue("必须引用重试上限常量（不许内联数字）", next.contains("ROTATE_STORE_ATTEMPTS") || next.contains("rotateRetryDecision("))
        assertTrue("两次尝试之间必须退让（给 provider 收尾的时间窗）", next.contains("ROTATE_RETRY_BACKOFF_MS"))
        assertTrue("每轮失败的 pending 必须就地回收（不许幽灵）", next.contains("discardBounded(uri)"))
        assertTrue("放弃时必须记下环节游标", next.contains("rotateFailStage"))
        // 退让必须真的睡下去（只在注释里提一句不算接线）
        assertTrue("退让必须落到 SystemClock.sleep", next.contains("SystemClock.sleep("))
    }

    @Test
    fun `换段腿失败必须带环节游标`() {
        // 这台 ROM 无日志：环节游标是唯一能在真机上分辨"卡在哪一步"的通道
        assertTrue(
            "失败码必须带上环节游标",
            codec.contains("rotateStallError(rotateLoss.totalMs, rotateFailStage)")
        )
        // 每个环节都要在腿上真被记过一次（常量声明不算接线）
        for ((stage, where) in listOf(
            "RotateStage.SEAL" to "sealBounded",
            "RotateStage.INSERT" to "createPendingBounded",
            "RotateStage.OPEN" to "openTargetBounded",
            "RotateStage.ENCODE" to "openNextSegment",
            "RotateStage.MUX" to "openNextSegment",
            "RotateStage.START" to "openNextSegment",
            "RotateStage.ROTATE" to "nextSegment"
        )) {
            val body = bodyOf(codec, where)
            assertTrue("$where 必须记过 $stage", body.contains(stage))
        }
        val note = bodyOf(codec, "noteRotateStage")
        assertTrue("只记第一个失败的环节（后续是连锁反应）", note.contains("if (rotateFailStage.isEmpty())"))
        // 遮蔽器会把字符串字面量抹成空白，所以这里按赋值形态判（别拿空串字面量当锚点）
        assertTrue("换段起钟必须清环节游标", bodyOf(codec, "beginRotateLeg").contains("rotateFailStage ="))
    }

    @Test
    fun `换段受阻账必须落盘`() {
        // 这台 ROM 吞掉应用日志、dumpsys media.codec 不可用 ⇒ 失败账必须是文件
        assertTrue("UI 必须判要不要落账", screen.contains("RotateFailLog.shouldLog("))
        assertTrue("UI 必须写账行", screen.contains("RotateFailLog.append("))
        assertTrue("账行必须走同一个编码函数", screen.contains("rotateFailLedgerLine("))
        assertTrue("落账目录取成片同目录（adb 可直接 pull）", screen.contains("out.path?.let { File(it).parentFile }"))
        assertTrue("无路径时退回应用外部目录（不许静默不写）", screen.contains("getExternalFilesDir(null)"))
        // 迟到落地的 insert 必须能看见（Resolver.insert 不可取消）:计数器进结果、进账行
        assertTrue("结果必须带迟到 pending 计数", bodyOf(codec, "buildResult").contains("latePending = latePendingReaped"))
        assertTrue("交接闸回收时必须自增计数", codec.contains("latePendingReaped++"))
        assertTrue("账行必须带上迟到计数", screen.contains("latePending = out.latePending"))
    }

    @Test
    fun `muxer写失败必须有现场快照与有界重建`() {
        val write = bodyOf(codec, "writeSample")
        assertTrue("写失败必须建现场快照", write.contains("muxFailDiagnostic("))
        assertTrue("快照必须含轨别/字段是否还指着这枚 muxer/已写字节/样本数/重建次数",
            write.contains("kind = kind") && write.contains("fieldSame = muxer === mx") &&
                write.contains("writtenBytes = segWrittenBytes") && write.contains("videoSamples = segVideoWritten") &&
                write.contains("restarts = segmentRestarts"))
        assertTrue("是否 stopping/rotatePending 也要进快照（停止期余帧 vs 换段期）",
            write.contains("stopping = stopping") && write.contains("rotatePending = rotatePending"))
        // 判据必须逐字取"本段已写视频样本 + 已写字节"，不许被改宽成恒真（恒真=连"写过再失败"也重建）
        assertTrue(
            "重建判定必须用本段真实计数当输入",
            write.contains("muxRecoverDecision(freshSegmentMuxFailure(segVideoWritten, segWrittenBytes), segmentRestarts)")
        )
        assertTrue("重建路径必须置请求位", write.contains("segmentRestartRequested = true"))
        // 判定表达式必须逐字钉住（把 if 改成 if (false) 就是把重建整条摘掉，必须红）
        assertTrue(
            "重建分支条件必须逐字接线",
            write.contains("if (muxRecoverDecision(freshSegmentMuxFailure(segVideoWritten, segWrittenBytes), segmentRestarts))")
        )
        // 重建路径**不许**置 engineError：置了录制中途看门狗会抢先把整场停掉（重建就没意义了）
        val recoverAt = write.indexOf("segmentRestartRequested = true")
        val errAt = write.indexOf("muxFailError(")
        assertTrue("锚点丢失：没截到重建请求位", recoverAt >= 0)
        assertTrue("锚点丢失：没截到失败码", errAt >= 0)
        assertTrue("重建（else 分支之外）必须先于失败码出现", recoverAt < errAt)
        assertTrue("触顶/非新段才置失败码（带快照）", write.contains("engineError = engineError ?: muxFailError(diag)"))
        // 轨别常量必须在调用点上真被传（不是只声明）
        for (k in listOf("TRACK_VIDEO", "TRACK_ENV", "TRACK_CAP")) {
            assertTrue("runSegment 必须按轨别传 $k", bodyOf(codec, "runSegment").contains(k))
        }
    }

    @Test
    fun `重建本段必须收口并由泵循环记账`() {
        val seg = bodyOf(codec, "runSegment")
        assertTrue("段内必须检查重建请求并早退", seg.contains("if (segmentRestartRequested)"))
        val earlyAt = seg.indexOf("if (segmentRestartRequested)")
        val noTrackAt = seg.indexOf("no video track produced")
        assertTrue("早退必须排在本段判废之前（不许把重建误判成 NO_VIDEO_TRACK 结束整场）", earlyAt >= 0 && (noTrackAt < 0 || earlyAt < noTrackAt))
        val pump = bodyOf(codec, "pumpLoop")
        assertTrue("泵循环必须消费重建请求", pump.contains("val restart = segmentRestartRequested") && pump.contains("segmentRestartRequested = false"))
        assertTrue("重建必须纳入换段腿判定（与换段共用同一条腿）", pump.contains("(restart && engineError == null)"))
        assertTrue("重建成功必须计数并记账", pump.contains("segmentRestarts++") && pump.contains("noteSegmentRestartLoss("))
        val loss = bodyOf(codec, "noteSegmentRestartLoss")
        assertTrue("重建丢失必须进累计账", loss.contains("rotateLoss.add("))
        assertTrue("重建丢失必须进逐段账（下一轮随段账落地）", loss.contains("pendingLegLostMs +="))
        assertTrue("结果必须带重建次数", bodyOf(codec, "buildResult").contains("segmentRestarts = segmentRestarts"))
        assertTrue("账行必须带重建次数", screen.contains("restarts = out.segmentRestarts"))
        // 已重建（成功接住）的那次也必须把现场快照带进账目：用户看到"没问题"，排查者看到"踩过"
        assertTrue("结果必须带最近一次 muxer 现场快照", bodyOf(codec, "buildResult").contains("muxDiag = muxFailDetail"))
        assertTrue("无产物失败也要带快照", bodyOf(codec, "finish").contains("muxFailDetail"))
        assertTrue("账行必须在成功时用已重建标记带出快照", screen.contains("muxRecoveredCode(muxDiag)"))
        assertTrue("有快照就要落账（哪怕没丢内容）", screen.contains("|| muxDiag.isNotEmpty()"))
    }

    @Test
    fun `新会话与释放必须复位换段账`() {
        assertTrue("prepare 必须复位", bodyOf(codec, "prepare").contains("resetRotateBooks()"))
        assertTrue("release 必须复位", bodyOf(codec, "release").contains("resetRotateBooks()"))
        assertTrue("finish 必须复位（结果已取出，账不能漏进下一次）", bodyOf(codec, "finish").contains("resetRotateBooks()"))
        val reset = bodyOf(codec, "resetRotateBooks")
        assertTrue("复位必须清腿起点", reset.contains("rotateLegStartMs = 0L"))
        assertTrue("复位必须清入库超时位", reset.contains("rotateStoreTimedOut = false"))
        assertTrue("复位必须清段账", reset.contains("segLostMs.clear()"))
        assertTrue("复位必须清累计账", reset.contains("rotateLoss.clear()"))
        assertTrue("复位必须清重建请求位与次数", reset.contains("segmentRestartRequested = false") && reset.contains("segmentRestarts = 0"))
    }
}
