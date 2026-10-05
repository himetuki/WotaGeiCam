package com.wotagei.cam.record

import com.wotagei.cam.core.ArcConvertMode
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制双轨化（内录体系批 3）的**源码结构守卫 + 引擎选型纯函数单测**。
 *
 * 双轨编排（MediaCodec 真机管线）JVM 测不到，按守卫口径锁结构红线：
 * - muxer 启动不变式：视频就绪 且 环境轨就绪 且（未启用捕获 或 捕获轨就绪）；
 * - EOS 判定单份：环境/内录两通道共用 feedAudioInput，pts<0 判据在全文件只许出现一次；
 * - 轨序红线：环境轨先 addTrack、内录轨后 addTrack（轨序即 tkhd track_ID 顺序，批 4 播放器识别用）；
 * - 捕获源失效路径：录制中 captureActive() 变 false → 内录轨 EOS 收尾、环境+视频继续；
 * - 引擎选型：captureAudio 强制 Codec（含 转换×内录、内录×高帧率 组合），MrRecorder 路径恒不见 captureAudio；
 * - 录制页接线：captureAudio = controller 态（录制开始时决定）且静音优先（内录×静音=纯视频）。
 *
 * 「尺子自己能红」：每条 bodyOf 守卫配突变体自证——拆掉被判据的行，同一把尺必须立刻报红。
 */
class DualTrackGuardTest {

    private fun maskedMain(rel: String): String =
        codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        KotlinSourceScan.flatten(bodyOf(maskedMain(rel), funName))

    /** 最小可用 profile：只动 fps / arcConvert / captureAudio，其余给合法默认 */
    private fun profile(fps: Int, convert: ArcConvertMode?, captureAudio: Boolean = false): RecordProfile =
        RecordProfile(
            width = 1920,
            height = 1080,
            fps = fps,
            captureRate = fps.toFloat(),
            bitrate = 0,
            codec = RecordProfile.CODEC_H264,
            sampleRate = 48_000,
            audioEnabled = true,
            orientationHint = 0,
            mirrored = false,
            useGpu = true,
            arcConvert = convert,
            captureAudio = captureAudio
        )

    // ------------------------------------------------------------------ muxer 启动不变式

    @Test
    fun `muxer启动不变式_视频且环境且未启用捕获或捕获就绪`() {
        val body = body("record/CodecRecorder.kt", "startMuxerIfReady")
        assertTrue(
            "muxer 启动必须先等视频轨（无视频轨的段 = 废片）",
            body.contains("if (videoTrack < 0) return false")
        )
        assertTrue(
            "muxer 启动必须等环境轨就绪（未启用音频=通道 null 视为就绪；**已废弃**（dropped，" +
                "从未产出真实样本，P3 对称防线）也视为就绪；否则必须已 addTrack）",
            body.contains("if (envCh != null && !envCh.dropped && envCh.track < 0) return false")
        )
        assertTrue(
            "muxer 启动必须等捕获轨就绪（未启用捕获=通道 null 视为就绪；**已废弃**（dropped，" +
                "从未产出真实样本，P0-a）也视为就绪；启用且未废弃时必须已 addTrack，" +
                "漏等会在双轨格式没集齐时 start 抛 IllegalStateException）",
            body.contains("if (capCh != null && !capCh.dropped && capCh.track < 0) return false")
        )
        // 突变自证：拆掉捕获轨条件（提前 start / 幽灵轨放行）必须报红
        val mutated = maskedMain("record/CodecRecorder.kt")
            .replace("if (capCh != null && !capCh.dropped && capCh.track < 0) return false", "")
        val mutatedBody = KotlinSourceScan.flatten(
            bodyOf(mutated, "startMuxerIfReady")
        )
        assertFalse(
            "拆掉捕获轨启动条件后守卫必须报红（防守卫被删空照样绿）",
            mutatedBody.contains("capCh.track < 0")
        )
    }

    // ------------------------------------------------------------------ EOS 判定单份

    @Test
    fun `EOS判定单份且双通道共用feedAudioInput`() {
        val all = maskedMain("record/CodecRecorder.kt")
        assertEquals(
            "pts<0 的 EOS 判据全文件只许一份（feedAudioInput）：环境/内录两通道必须共用，" +
                "复制一份迟早与 J 批修好的判定分叉",
            1,
            occurrences(all, "pkt.ptsUs < 0").size
        )
        val feed = body("record/CodecRecorder.kt", "feedAudioInput")
        assertTrue(
            "feedAudioInput 必须参数化通道（feeder 与通道状态都从形参来，不再读单实例字段）",
            feed.contains("src.poll(0)") && feed.contains("ch.heldPkt")
        )
        assertTrue(
            "喂到 EOS 哨兵必须经 queueEosOn 入队（置位权在 queueEosOn：入队成功才置位，P2）",
            feed.contains("queueEosOn(codec, idx, ch)")
        )
        // 双通道喂入点各就各位：环境、内录各调一次，实参互不串
        val seg = body("record/CodecRecorder.kt", "runSegment")
        assertTrue(
            "环境通道必须喂环境 feeder 与环境通道状态",
            seg.contains("feedAudioInput(a, ef, envCh)")
        )
        assertTrue(
            "内录通道必须喂捕获 feeder 与内录通道状态（双轨并存，不是同一实例换源）",
            seg.contains("feedAudioInput(c, cf, capCh)")
        )
    }

    // ------------------------------------------------------------------ 轨序红线

    @Test
    fun `轨序红线_环境轨先于内录轨addTrack`() {
        val raw = maskedMain("record/CodecRecorder.kt")
        val seg = KotlinSourceScan.flatten(bodyOf(raw, "runSegment"))
        val envAdd = seg.indexOf("envCh.track = mx.addTrack")
        val capAdd = seg.indexOf("capCh.track = mx.addTrack")
        assertTrue(
            "环境轨必须先 addTrack（轨序即 tkhd track_ID 顺序，批 4 播放器按 video 后第一/二条音轨识别环境/内录）",
            envAdd in 0 until capAdd
        )
        assertTrue(
            "内录轨 addTrack 门控必须带轨序与零样本双判据（等环境轨 add 完；环境编码器缺席" +
                "**或环境轨已废弃**都视为无轨序前置——env 废弃仍挡门 = muxStarted 恒 false 复现 P1）；" +
                "且编码器必须已产出首个真实样本 hasSample，P0-a",
            seg.contains("(envCh == null || envCh.dropped || envCh.track >= 0)") &&
                seg.contains("capCh.fmtReady && capCh.hasSample")
        )
        // 突变自证：门控改成恒真，同一把尺必须报红
        val mutatedSeg = KotlinSourceScan.flatten(
            bodyOf(
                raw.replace("(envCh == null || envCh.dropped || envCh.track >= 0)", "(true)"),
                "runSegment"
            )
        )
        assertFalse(
            "拆掉轨序门控后守卫必须报红",
            mutatedSeg.contains("envCh == null || envCh.dropped || envCh.track >= 0")
        )
    }

    // ------------------------------------------------------------------ 捕获源失效路径

    @Test
    fun `捕获源失效路径_内录轨收尾_环境视频继续`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        assertTrue(
            "内录通道喂入必须先过 captureActive 健康判据（录制中投影撤销=内录轨提前收尾）",
            seg.contains("if (cf.captureActive())")
        )
        assertTrue(
            "失效后必须先排空残留包再兜底直发 EOS（尾音不丢、且必收尾不悬挂）",
            seg.contains("queueAudioEos(c, capCh)")
        )
        val active = body("record/AudioFeeder.kt", "captureActive")
        assertTrue(
            "captureActive 三判据：AudioRecord 已建 且 未到 EOS 且 外部健康判据（controller 态）不否决",
            active.contains("record != null") && active.contains("!eosSeen") &&
                active.contains("healthy?.invoke() != false")
        )
        val pump = body("record/AudioFeeder.kt", "pump")
        assertTrue(
            "泵循环必须带捕获源健康检查（撤销后 read 只回静音不回错的机型靠这条保证 EOS）",
            pump.contains("if (health != null && !health())")
        )
        // 突变自证：captureActive 恒真，失效路径探测与泵健康检查两条判据必须同时断
        val mutatedActive = KotlinSourceScan.flatten(
            bodyOf(
                maskedMain("record/AudioFeeder.kt")
                    .replace("record != null && !eosSeen && healthy?.invoke() != false", "true"),
                "captureActive"
            )
        )
        assertFalse(
            "captureActive 被改成恒真后守卫必须报红",
            mutatedActive.contains("eosSeen")
        )
    }

    // ------------------------------------------------------------------ 引擎选型

    @Test
    fun `内录强制Codec引擎_组合矩阵`() {
        // 内录 × 普通帧率：MediaRecorder 无多音轨能力，必须强制 Codec
        assertTrue(Recorders.useCodecEngine(profile(30, null, captureAudio = true)))
        // 内录 × 转换（arcConvert）：两者都强制 Codec，GL 只动视频支路，双音轨不受影响
        assertTrue(Recorders.useCodecEngine(profile(24, ArcConvertMode.MEND, captureAudio = true)))
        assertTrue(Recorders.useCodecEngine(profile(25, ArcConvertMode.DROP, captureAudio = true)))
        // 内录 × 高帧率：组合恒 Codec
        assertTrue(Recorders.useCodecEngine(profile(120, null, captureAudio = true)))
        // 现状红线：不内录时选型口径与批 3 前逐字一致
        assertFalse(Recorders.useCodecEngine(profile(30, null)))
        assertFalse(Recorders.useCodecEngine(profile(24, null)))
        assertTrue(Recorders.useCodecEngine(profile(120, null)))
        assertTrue(Recorders.useCodecEngine(profile(24, ArcConvertMode.MEND)))
    }

    @Test
    fun `useCodecEngine感知captureAudio且Mr路径恒不见内录`() {
        val body = body("record/Recorder.kt", "useCodecEngine")
        assertTrue(
            "useCodecEngine 必须感知 captureAudio（MediaRecorder 无多音轨能力，内录会话漏进 MR = 双轨全废）",
            body.contains("p.captureAudio")
        )
        assertFalse(
            "MrRecorder 不得感知 captureAudio（内录恒被 useCodecEngine 拦在 Codec 引擎，" +
                "MR 路径出现该字段 = 选型闸被绕过）",
            maskedMain("record/MrRecorder.kt").contains("captureAudio")
        )
        // 突变自证：选型函数删掉 captureAudio 判据，同一把尺必须报红
        val mutated = KotlinSourceScan.flatten(
            bodyOf(
                codeOnly(KotlinSourceScan.mainSourceText("record/Recorder.kt"))
                    .replace("p.fps > MR_MAX_FPS || p.arcConvert != null || p.captureAudio", "p.fps > MR_MAX_FPS || p.arcConvert != null"),
                "useCodecEngine"
            )
        )
        assertFalse("拆掉 captureAudio 判据后守卫必须报红", mutated.contains("p.captureAudio"))
    }

    // ------------------------------------------------------------------ P0 失效贯穿生命周期（修复轮 1）

    @Test
    fun `P0失效与首启失败贯穿生命周期_轮转不空试`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        assertTrue(
            "捕获失效兜底收尾必须就地拆 feeder 并改写 profile（P0-b：只对当段收尾的话，" +
                "轮转重建编码器/沿用死 feeder，后续段全成零样本幽灵轨）",
            seg.contains("stopCapFeeder()") &&
                seg.contains("profile = profile?.copy(captureAudio = false)")
        )
        val capFail = body("record/CodecRecorder.kt", "startCapFeederOrDropAudio")
        assertTrue(
            "内录 feeder 首启失败必须改写 profile（P0-b：只释放 capCodec 的话轮转会复活它）",
            capFail.contains("profile = p.copy(captureAudio = false)")
        )
        val envFail = body("record/CodecRecorder.kt", "startFeederOrDropAudio")
        assertTrue(
            "环境 feeder 首启失败必须改写 profile（P3：同族降级口径统一，轮转不空试）",
            envFail.contains("profile = p.copy(audioEnabled = false)")
        )
        val next = body("record/CodecRecorder.kt", "openNextSegment")
        assertTrue(
            "轮转重建必须按 profile 改写判定（环境/内录两块独立判定：一路降级不连坐另一路）",
            next.contains("aacMinBuf > 0 && p.audioEnabled") &&
                next.contains("aacMinBuf > 0 && p.captureAudio")
        )
        // 突变自证：拆掉失效路径的 profile 改写，同一把尺必须报红
        val mutatedSeg = KotlinSourceScan.flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt")
                    .replace("profile = profile?.copy(captureAudio = false)", "Unit"),
                "runSegment"
            )
        )
        assertFalse(
            "拆掉失效路径 profile 改写后守卫必须报红",
            mutatedSeg.contains("profile?.copy(captureAudio = false)")
        )
    }

    // ------------------------------------------------------------------ P0 零样本防线（修复轮 1）

    @Test
    fun `P0零样本防线_hasSample为addTrack前置_废弃通道按未启用处理`() {
        val seg = body("record/CodecRecorder.kt", "runSegment")
        assertTrue(
            "内录轨 addTrack 前置判据必须含 hasSample（编码器产出过首个真实样本才准入，" +
                "fmtReady 只证明格式到了）",
            seg.contains("capCh.fmtReady && capCh.hasSample")
        )
        assertTrue(
            "EOS 收尾仍无真实样本必须标 dropped（废弃通道不进 muxer，零样本轨结构上不可能）",
            seg.contains("!capCh.hasSample") && seg.contains("capCh.dropped = true")
        )
        assertTrue(
            "addTrack 前的真实样本必须暂存补写（heldOutIdx）：已产出样本 ⇒ 轨必有 ≥1 写入样本",
            seg.contains("capCh.heldOutIdx = idx") && seg.contains("capCh.heldOutIdx = -1")
        )
        // 突变自证：addTrack 门控删掉 hasSample（回到只要 fmtReady），同一把尺必须报红
        val mutatedSeg = KotlinSourceScan.flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt")
                    .replace("capCh.fmtReady && capCh.hasSample", "capCh.fmtReady"),
                "runSegment"
            )
        )
        assertFalse(
            "拆掉 hasSample 前置判据后守卫必须报红",
            mutatedSeg.contains("capCh.fmtReady && capCh.hasSample")
        )
    }

    // ------------------------------------------------------------------ P0-c muxer.stop 异常按段废（修复轮 1）

    @Test
    fun `P0c_muxerStop异常置engineError按段废不静默提交`() {
        val raw = maskedMain("record/CodecRecorder.kt")
        val body = KotlinSourceScan.flatten(bodyOf(raw, "closeSegmentEngine"))
        val stopAt = body.indexOf("m.stop()")
        val errAt = body.indexOf("engineError = engineError ?:")
        assertTrue(
            "closeSegmentEngine 必须在 muxer.stop 的 IllegalStateException 分支置 engineError" +
                "（零样本轨/未 start 的容器必然缺 moov，静默吞掉会让损坏 MP4 被 sealCurrent 按" +
                "keep=true 提交进相册，P0-c；判据为代码序：m.stop() 之后必须跟着置错行——" +
                "错误码字面量在 codeOnly 下被遮蔽，只锁代码结构）",
            stopAt in 0 until errAt
        )
        // 突变自证：拆掉置 error 行（回到静默吞），同一把尺必须报红
        val mutated = KotlinSourceScan.flatten(
            bodyOf(raw.replace("engineError = engineError ?: ", "Unit /* "), "closeSegmentEngine")
        )
        assertFalse(
            "拆掉 muxer.stop 置错行后守卫必须报红",
            mutated.indexOf("m.stop()") < mutated.indexOf("engineError = engineError ?:")
        )
    }

    // ------------------------------------------------------------------ P2 eosQueued 置位即真正入队（修复轮 1）

    @Test
    fun `P2_eosQueued置位即真正入队_失败不置位下轮重试`() {
        val eos = body("record/CodecRecorder.kt", "queueEosOn")
        val queueAt = eos.indexOf("queueInputBuffer")
        val flagAt = eos.indexOf("ch.eosQueued = true")
        assertTrue(
            "eosQueued 置位必须发生在 queueInputBuffer 成功之后（P2：先置位再入队的话，" +
                "idx<0 或入队抛异常时 EOS 实际丢了而标志已立，done 恒不置位 → DRAIN 超时误废当段）",
            queueAt in 0 until flagAt
        )
        val fallback = body("record/CodecRecorder.kt", "queueAudioEos")
        assertFalse(
            "兜底入口不得预置位（置位权唯一在 queueEosOn，idx<0 留给下一轮重试）",
            fallback.contains("ch.eosQueued = true")
        )
        // 突变自证：在 queueInputBuffer 之前插入置位（模拟旧单发语义），顺序断言必须报红
        val mutated = KotlinSourceScan.flatten(
            bodyOf(
                maskedMain("record/CodecRecorder.kt").replace(
                    "codec.queueInputBuffer(idx, 0, 0, eosPts(ch), MediaCodec.BUFFER_FLAG_END_OF_STREAM)",
                    "ch.eosQueued = true\n        codec.queueInputBuffer(idx, 0, 0, eosPts(ch), MediaCodec.BUFFER_FLAG_END_OF_STREAM)"
                ),
                "queueEosOn"
            )
        )
        val mQueue = mutated.indexOf("queueInputBuffer")
        val mFlag = mutated.indexOf("ch.eosQueued = true")
        assertFalse(
            "置位挪到入队之前（旧语义）后顺序守卫必须报红",
            mQueue in 0 until mFlag
        )
    }

    // ------------------------------------------------------------------ P1 贯穿收尾与 eosQueued 解耦（修复轮 2）

    @Test
    fun `P1_失效贯穿收尾不依赖eosQueued且dropped处补启动闸重评`() {
        val raw = maskedMain("record/CodecRecorder.kt")
        val seg = KotlinSourceScan.flatten(bodyOf(raw, "runSegment"))
        // 竞态的确定性钉法（把竞态变成可断言的纯状态）：teardown 块由「!capTornDown」门控，
        // 它所在支路的入口判据只有 captureActive()==false——**块内与块前都不得出现 eosQueued**。
        // 旧缺陷：收尾锁在「!eosQueued && 队列空」门内，泵线程必投的 EOS 哨兵被消费置位后
        // 该门永久关闭，贯穿收尾只剩几十毫秒竞态窗口
        val activeAt = seg.indexOf("cf.captureActive()")
        val tornAt = seg.indexOf("if (!capTornDown)")
        val stopAt = seg.indexOf("stopCapFeeder()", tornAt)
        assertTrue(activeAt in 0 until tornAt && tornAt in 0 until stopAt)
        val teardownBlock = seg.substring(tornAt, stopAt)
        assertFalse(
            "teardown 块内不得依赖 eosQueued（P1：哨兵消费置位会让该门永久关闭，" +
                "失效贯穿收尾被竞态跳过 → 僵尸 feeder + profile 残 true → 轮转空试复活）",
            teardownBlock.contains("eosQueued")
        )
        // torn 支路必须仍是 captureActive 的 else（输入态=captureActive false 单独充分触发）
        val elseAt = seg.indexOf("} else {", activeAt)
        assertTrue(elseAt in activeAt until tornAt)
        // dropped 置位处必须补 startMuxerIfReady 重评（废弃=未启用，可能正是它挡着 muxer start）。
        // 判据锁「置位后紧随重评」的完整模式，防的是只删重评行不删标志位的半截突变
        val capReEvalPat =
            "capCh.dropped = true if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)"
        assertTrue(
            "内录 dropped 置位处必须就地重评启动闸（不重评则 muxStarted 恒 false → " +
                "停止时报 stop before muxer started 整段作废且录制终止）",
            seg.contains(capReEvalPat)
        )
        val envReEvalPat =
            "envCh.dropped = true if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)"
        assertTrue(
            "环境 dropped 置位处同样必须重评启动闸（P3 对称）",
            seg.contains(envReEvalPat)
        )
        // 「dropped 通道在启动闸视同 null」的选型钉死（null 等价）：startMuxerIfReady 双闸都带 !dropped
        val gate = body("record/CodecRecorder.kt", "startMuxerIfReady")
        assertTrue(
            "启动闸必须把 dropped 通道视同未启用（null 等价）",
            gate.contains("!envCh.dropped") && gate.contains("!capCh.dropped")
        )
        // 突变自证 1：把 teardown 重新锁回 eosQueued 门（旧竞态语义），同一把尺必须报红
        val mutated = seg.replace(
            "if (!capTornDown) { capTornDown = true",
            "if (!capTornDown && !capCh.eosQueued) { capTornDown = true"
        )
        val mTorn = mutated.indexOf("if (!capTornDown")
        val mStop = mutated.indexOf("stopCapFeeder()", mTorn)
        assertTrue(mutated.substring(mTorn, mStop).contains("eosQueued"))
        // 突变自证 2：删掉 dropped 处的重评调用，模式断言必须报红
        val mutated2 = seg.replace(
            " if (!muxStarted) muxStarted = startMuxerIfReady(mx, videoTrack, envCh, capCh)",
            ""
        )
        assertFalse(mutated2.contains(capReEvalPat))
    }

    // ------------------------------------------------------------------ P2 停止兜底 EOS 每轮重试（修复轮 2）

    @Test
    fun `P2_停止轮转兜底EOS不在deadline单发门内_每轮重试`() {
        val seg = KotlinSourceScan.flatten(bodyOf(maskedMain("record/CodecRecorder.kt"), "runSegment"))
        // 旧单发语义的结构特征：外门把 stopping 与 deadline == 0L 合在一起，EOS 兜底只发一次
        assertFalse(
            "停止/轮转兜底 EOS 不得与 deadline 置位同门单发（那一拍 idx<0 则 EOS 丢失、" +
                "done 恒不置位 → 1200ms DRAIN 超时误废整段，P2）",
            seg.contains("&& deadline == 0L")
        )
        // 内包一层 if (deadline == 0L) 的变体同样要红：停止门内，dl 块自身那处之外、
        // 到兜底 EOS 调用之间不得再出现 deadline == 0L（门区域从 `if (stopping` 起算，
        // 失效兜底支路的 queueAudioEos(c, capCh) 在循环更早处，不能作为定位锚）
        val gateAt = seg.indexOf("if (stopping || rotatePending)")
        val dlAt = seg.indexOf("deadline == 0L", gateAt)
        val eosAAt = seg.indexOf("queueAudioEos(a, envCh)", gateAt)
        val eosCAt = seg.indexOf("queueAudioEos(c, capCh)", eosAAt)
        assertTrue("停止门内必须有 dl 块与双通道兜底 EOS（顺序：dl 块 → env → cap）", dlAt in 0 until eosAAt && eosAAt < eosCAt)
        val second = seg.indexOf("deadline == 0L", dlAt + 1)
        assertFalse(
            "dl 块与兜底 EOS 之间不得再出现 deadline==0L（内包一层=回到单发）",
            second in 0 until eosAAt
        )
        // 突变自证：恢复旧合并门（EOS 单发），两条断言必须同时红
        val mutated = seg.replace(
            "if (stopping || rotatePending) { if (deadline == 0L) {",
            "if (stopping || rotatePending && deadline == 0L) {"
        )
        assertTrue(mutated.contains("&& deadline == 0L"))
        val m2 = mutated.replace(
            "if (a != null && envCh != null && !envCh.eosQueued) queueAudioEos(a, envCh)",
            "if (deadline == 0L) { if (a != null && envCh != null && !envCh.eosQueued) queueAudioEos(a, envCh) }"
        )
        val mGate = m2.indexOf("if (stopping || rotatePending")
        val mDl = m2.indexOf("deadline == 0L", mGate)
        val mEos = m2.indexOf("queueAudioEos(a, envCh)", mGate)
        assertTrue(m2.indexOf("deadline == 0L", mDl + 1).let { it in 0 until mEos })
    }

    // ------------------------------------------------------------------ P3 环境通道零样本防线对称（修复轮 2）

    @Test
    fun `P3_环境通道零样本防线与内录对称`() {
        val seg = KotlinSourceScan.flatten(bodyOf(maskedMain("record/CodecRecorder.kt"), "runSegment"))
        // 环境轨 addTrack 门：格式 + 首个真实样本双前置 + 废弃排除（与内录门同构）
        assertTrue(
            "环境轨 addTrack 门必须带 hasSample 前置与 dropped 排除（env feeder 产出首个 " +
                "PCM 前死亡时零样本轨不得进 muxer，P3）",
            seg.contains("!envCh.dropped && envCh.fmtReady && envCh.hasSample")
        )
        assertTrue(
            "环境 EOS 收尾无样本必须标 dropped 并重评启动闸（按「未启用音频」处理，段照常成片）",
            seg.contains("envCh.dropped = true")
        )
        assertTrue(
            "环境轨首样本必须暂存补写（与内录通道同构的 heldOutIdx 机制）",
            seg.contains("envCh.heldOutIdx = idx") && seg.contains("envCh.heldOutIdx = -1")
        )
        // 轨序红线不变：环境 addTrack 门必须排在内录门之前
        val envGate = seg.indexOf("!envCh.dropped && envCh.fmtReady && envCh.hasSample")
        val capGate = seg.indexOf("!capCh.dropped && capCh.fmtReady && capCh.hasSample")
        assertTrue("环境 addTrack 门必须先于内录门（轨序红线）", envGate in 0 until capGate)
        // 突变自证：环境门删掉 hasSample（回到 FORMAT_CHANGED 即 add），同一把尺必须报红
        val mutated = seg.replace(
            "!envCh.dropped && envCh.fmtReady && envCh.hasSample",
            "!envCh.dropped && envCh.fmtReady"
        )
        assertFalse(
            "拆掉环境 hasSample 前置后守卫必须报红",
            mutated.contains("!envCh.dropped && envCh.fmtReady && envCh.hasSample")
        )
    }

    // ------------------------------------------------------------------ 录制页接线

    @Test
    fun `录制页接线_内录态入profile且静音优先`() {
        val screen = maskedMain("ui/CameraScreen.kt")
        val build = body("ui/CameraScreen.kt", "buildProfile")
        assertTrue(
            "buildProfile 必须带静音优先裁决：内录×静音=纯视频（audioEnabled 优先）",
            build.contains("captureAudio = captureAudio && params.audioEnabled.value")
        )
        assertTrue(
            "起录必须传内录态（controller.state is Active，录制开始时决定；漏挂 = 内录永不生效）",
            screen.contains("captureAudio = captureState is CaptureState.Active")
        )
        assertTrue(
            "RecordRunner.start 的 captureAudio 形参必须无默认值（漏挂让它编译不过，同 #69 锚点纪律）",
            screen.contains("captureAudio: Boolean")
        )
        // 突变自证：静音优先改成无条件内录，同一把尺必须报红
        val mutatedBuild = KotlinSourceScan.flatten(
            bodyOf(
                screen.replace(
                    "captureAudio = captureAudio && params.audioEnabled.value",
                    "captureAudio = captureAudio"
                ),
                "buildProfile"
            )
        )
        assertFalse(
            "拆掉静音优先判据后守卫必须报红（内录×静音=纯视频是用户裁决口径）",
            mutatedBuild.contains("captureAudio && params.audioEnabled.value")
        )
    }
}
