package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.regionsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内录批 4（播放器选轨+同步+导出）的**源码结构守卫**。
 *
 * 行为本体（MediaCodec/MediaExtractor/MediaMuxer/ExoPlayer 选轨）在 JVM 跑不起来，真机验收
 * 归真机验证清单；这里锁**接线红线**，判据全部作用在 bodyOf 摘出的函数体上：
 * - 三控件必须整块被 dualAudio 门住（回归红线：单音轨片/外来视频不显示；cap-only 单音轨段
 *   同红线覆盖——不显、不误标）；
 * - 探测必须走 TrackSync.countAudioTracks（IO 线程）；
 * - 选轨 override 必须经 setOverrideForType（同型替换，防 addOverride 叠加）、带组下标防越界，
 *   且换片（attach）必清——TrackGroup 跨片残留指向已不存在的组；
 * - 同步必须复用 AudioSync 粗对齐/精修/置信度口径（单一真源），低置信度必须落 0（按录制起点）；
 * - 导出的音频 PTS 必须经 TrackRule.shiftedPts 判负丢弃（负 PTS 会崩 muxer，不许 clip 到 0），
 *   入库必须走 IS_PENDING 两段式；
 * - decodeMono 旧单参签名必须委托第 0 轨（对比页双文件对齐行为零变化的回归红线）。
 *
 * 「尺子自己能红」：每条判据配一个突变体，同一把尺必须立刻报红，防删空判据照样绿。
 */
class TrackToolsGuardTest {

    private fun masked(rel: String): String = codeOnly(KotlinSourceScan.mainSourceText(rel))

    // PlayerScreen 有路由/正文两个重载，bodyOf 不收重载——按特征 token 取正文那个
    private fun playerScreenBody(): String {
        val m = masked("player/PlayerScreen.kt")
        val bodies = regionsOf(m, "PlayerScreen").map { m.substring(it.start + 1, it.end - 1) }
        return bodies.first { it.contains("TapArbiter") }
    }

    /** PlayerEngine.attach 同文件两处（uri 版 + VideoClip 扩展）：取含 setMediaItem 的那个 */
    private fun engineAttachBody(): String {
        val m = masked("player/PlayerEngine.kt")
        val bodies = regionsOf(m, "attach").map { m.substring(it.start + 1, it.end - 1) }
        return bodies.first { it.contains("setMediaItem") }
    }

    /** 函数签名文本（参数表含默认值判定用）：从 fun 声明的 `(` 到体 `{` */
    private fun signatureOf(m: String, name: String): String {
        val decl = Regex("\\bfun\\s+" + Regex.escape(name) + "\\s*\\(").find(m)
            ?: throw AssertionError("找不到 fun $name 的声明")
        val paren = m.indexOf('(', decl.range.first)
        val body = KotlinSourceScan.regionOf(m, name)
        return m.substring(paren, body.start + 1)
    }

    // region 三控件门与接线（PlayerScreen）

    /** UI 判据：真身与突变体同吃一把尺 */
    private fun missingUiWires(body: String): List<String> = listOf(
        body.contains("if (dualAudio)") to "三控件必须整块被 dualAudio 门住（单音轨片/外来视频静默不显的回归红线）",
        body.contains("TrackSync.countAudioTracks(app, clip.uri) >= 2") to
            "双音轨判定必须来自打开时的 MediaExtractor 探测（数音轨 >=2）",
        body.contains("TrackSync.cachedOffsetMs(app, clip.uri)") to
            "打开时必须读上次同步的持久化偏移（导出读数不因退出页面丢失）",
        body.contains("engine.setAudioTrackOverride(1, 0)") to "选内录必须强制第二条音频组（组内单轨 trackIndex=0）",
        body.contains("engine.clearAudioOverride()") to "选环境必须清 override 回默认（默认即第一条音轨=环境）",
        body.contains("TrackSync.run(app, clip.uri)") to "同步钮必须跑 TrackSync.run",
        body.contains("R.string.player_track_sync_low") to "低置信度必须有「按录制起点对齐」提示（复用低置信口径）",
        body.contains("trackExporter.export(clip, track, offset)") to
            "导出必须把选轨快照与偏移一起交给 TrackExporter",
        (body.contains("trackPickAnchor = it") && body.contains("trackSyncAnchor = it") &&
            body.contains("trackExportAnchor = it")) to "三控件的就近弹窗必须各挂锚点（pillAnchor 写入方登记纪律）"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    @Test
    fun `三控件仅双音轨显示且接线完整`() {
        val missing = missingUiWires(playerScreenBody())
        assertEquals(
            "批 4 播放器接线断线：\n${missing.joinToString("\n")}",
            emptyList<String>(),
            missing
        )
        // 弹窗锚点形参无默认值必传（锚点纪律：漏挂让它编译不过，签名层再钉一道）
        val m = masked("player/PlayerScreen.kt")
        listOf("TrackPickPopup", "TrackExportPopup").forEach { name ->
            val sig = signatureOf(m, name)
            assertTrue(
                "$name 的 anchor 形参必须无默认值必传（锚点纪律）",
                sig.contains("anchor: androidx.compose.ui.unit.IntRect") && !sig.contains("IntRect =")
            )
        }
        // 红绿突变 1：拆掉双音轨门（恒显），同一把尺必须报红——这正是"单音轨也显示"的回归形态
        val mutantGate = playerScreenBody().replace("if (dualAudio)", "if (true)")
        assertTrue("拆门突变体必须报红", missingUiWires(mutantGate).isNotEmpty())
        // 红绿突变 2：探测改成恒真（外来视频也亮三控件），同一把尺必须报红
        val mutantProbe = playerScreenBody()
            .replace("TrackSync.countAudioTracks(app, clip.uri) >= 2", "true")
        assertTrue("探测恒真突变体必须报红", missingUiWires(mutantProbe).isNotEmpty())
        // 红绿突变 3：选内录不挂 override（点了没反应的半实现），同一把尺必须报红
        val mutantOverride = playerScreenBody()
            .replace("engine.setAudioTrackOverride(1, 0)", "Unit")
        assertTrue("选轨断线突变体必须报红", missingUiWires(mutantOverride).isNotEmpty())
    }

    // endregion

    // region 引擎选轨（PlayerEngine）

    /** 引擎判据的复用版（真身与突变体同吃一把尺） */
    private fun missingEngineWires(setBody: String, clearBody: String, attachBody: String): List<String> = listOf(
        setBody.contains("setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))") to
            "选轨必须经 TrackSelectionOverride + setOverrideForType（同型替换，防叠加残留）",
        setBody.contains("getOrNull(groupIndex)") to
            "组下标必须 getOrNull 防越界（换片竞态在引擎侧兜底空操作）",
        clearBody.contains("clearOverridesOfType(C.TRACK_TYPE_AUDIO)") to
            "清 override 必须按 AUDIO 类型整体清",
        attachBody.contains("clearAudioOverride()") to
            "attach 换片必须清旧片 override（TrackGroup 跨片残留指向已不存在的组）"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    @Test
    fun `引擎override走setOverrideForType且换片清态`() {
        val engine = masked("player/PlayerEngine.kt")
        val missing = missingEngineWires(
            bodyOf(engine, "setAudioTrackOverride"),
            bodyOf(engine, "clearAudioOverride"),
            engineAttachBody()
        )
        assertEquals(
            "PlayerEngine 选轨接线断线：\n${missing.joinToString("\n")}",
            emptyList<String>(),
            missing
        )
        // 红绿突变：把组下标钉死成 0（选内录永远落到环境组=选不进内录），同一把尺必须报红
        val mutantSet = bodyOf(engine, "setAudioTrackOverride").replace(
            "TrackSelectionOverride(group.mediaTrackGroup, trackIndex)",
            "TrackSelectionOverride(group.mediaTrackGroup, 0)"
        )
        assertTrue(
            "组下标钉死突变体必须报红",
            missingEngineWires(mutantSet, bodyOf(engine, "clearAudioOverride"), engineAttachBody()).isNotEmpty()
        )
    }

    // endregion

    // region 同步口径（TrackSync）

    /**
     * 同步判据的复用版（真身与突变体同吃一把尺；combineOffsetMs/failResult 本体有
     * TrackSyncTest 行为钉，这里锁 run 的接线与 P3-1/P3-2 的失败分层）
     */
    private fun missingSyncWires(runBody: String, wholeFile: String): List<String> = listOf(
        (runBody.contains("AudioSync.coarseAlign") && runBody.contains("AudioSync.refine")) to
            "粗对齐/精修必须复用 AudioSync（单一真源，不另起炉灶）",
        (runBody.contains("AudioSync.decodeMono(context, uri, 0)") &&
            runBody.contains("AudioSync.decodeMono(context, uri, 1)")) to
            "环境/内录必须按轨序分别解码（0=环境、1=内录）",
        runBody.contains("AudioSync.MIN_CONFIDENCE") to
            "低置信度门槛必须复用 AudioSync.MIN_CONFIDENCE（口径单一真源）",
        runBody.contains("resolveOffsetMs(offsetMs, low)") to
            "低置信度必须经 resolveOffsetMs 落 0（按录制起点对齐），不许把算出的偏移直接持久化",
        runBody.contains("combineOffsetMs(contentMs, firstPtsUs[1], firstPtsUs[0])") to
            "总偏移必须经 combineOffsetMs 合入两轨首样本容器时刻差（decodeMono 归零丢了这层）",
        // P3-1 占位值根除红线：读不到首样本的轨被剔除，合成端永远拿不到 MIN_VALUE
        //（Long.MIN_VALUE 进 (capPts−envPts) 补码回绕成约 +9.2e15 µs 荒谬偏移，审查已脚本实证）
        !wholeFile.contains("MIN_VALUE") to
            "audioFirstPtsUs 读不到首样本必须剔除轨，不许用占位值（MIN_VALUE 回绕红线，P3-1）",
        // P3-2 失败分层：run 的三个读取失败点（首样本不足/两路解码失败）全走 failResult(结构性=true)
        (runBody.split("failResult(structuralDualAudio = true)").size - 1 == 3) to
            "读取失败点必须恰好 3 处全走 failResult(结构性=true)（P3-2：探测已判双轨的片不许误报没有音轨）",
        runBody.contains("failResult(structuralDualAudio = false)") to
            "结构性音轨不足必须走 failResult(结构性=false)（dualAudio=false，没有音轨文案才如实）"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    @Test
    fun `同步复用AudioSync口径且低置信归零`() {
        val m = masked("player/TrackSync.kt")
        val missing = missingSyncWires(bodyOf(m, "run"), m)
        assertEquals(
            "TrackSync 口径断线：\n${missing.joinToString("\n")}",
            emptyList<String>(),
            missing
        )
        // 红绿突变 1：低置信度照样持久化算出的偏移（口径漂移），同一把尺必须报红
        val mutantLowConf = bodyOf(m, "run").replace("resolveOffsetMs(offsetMs, low)", "offsetMs")
        assertTrue(
            "低置信度直通突变体必须报红",
            missingSyncWires(mutantLowConf, m).isNotEmpty()
        )
        // 红绿突变 2（P3-1）：占位值回潮（首样本读不到塞 MIN_VALUE 进合成式），同一把尺必须报红
        val mutantPlaceholder = m.replace(
            "internal fun failResult",
            "private val placeholderPtsUs = Long.MIN_VALUE\n    internal fun failResult"
        )
        assertTrue(
            "占位值回潮突变体必须报红",
            missingSyncWires(bodyOf(mutantPlaceholder, "run"), mutantPlaceholder).isNotEmpty()
        )
        // 红绿突变 3（P3-2）：解码失败改回「没有音轨」误导文案，同一把尺必须报红
        val mutantFailMsg = bodyOf(m, "run").replace(
            "?: return@withContext failResult(structuralDualAudio = true)",
            "?: return@withContext Result(false, dualAudio = false, reasonRes = R.string.player_sync_no_audio)"
        )
        assertTrue(
            "解码失败误报没有音轨突变体必须报红",
            missingSyncWires(mutantFailMsg, m).isNotEmpty()
        )
    }

    // endregion

    // region 导出语义（TrackExport）

    @Test
    fun `导出PTS判负丢弃且pending两段式在位`() {
        val m = masked("player/TrackExport.kt")
        val copyBody = bodyOf(m, "copy")
        assertTrue(
            "音频 PTS 必须经 TrackRule.shiftedPts（判负丢弃，负 PTS 会崩 muxer）",
            copyBody.contains("TrackRule.shiftedPts(ex.sampleTime, baseUs, shiftUs)")
        )
        assertTrue(
            "shiftedPts 判 null 必须在位（丢弃前导，不许 clip 到 0——clip 会同刻多样本破单调）",
            copyBody.contains("if (outPts != null)")
        )
        val insertBody = bodyOf(m, "insertPending")
        assertTrue(
            "入库必须走 TrackRule.RELATIVE_PATH（Inau 子目录口径）",
            insertBody.contains("TrackRule.RELATIVE_PATH")
        )
        assertTrue(
            "入库必须 IS_PENDING 两段式（失败/取消删 pending，不留 0 字节幽灵条目）",
            insertBody.contains("IS_PENDING, 1")
        )
        // shiftUsFor 平移量本体由 TrackExportTest 行为钉（表达式体进不了 bodyOf，不重复）
        // 红绿突变：删掉判负丢弃（负 PTS 直写 muxer，真机必崩），同一把尺必须报红
        val mutant = copyBody.replace("if (outPts != null)", "if (true)")
        assertTrue("删判负突变体必须报红", !mutant.contains("if (outPts != null)"))
        assertTrue(
            "突变体必须至少断一条判据",
            missingExportWires(mutant, insertBody).isNotEmpty()
        )
    }

    /** copy/insert 判据的复用版（真身与突变体同吃） */
    private fun missingExportWires(copyBody: String, insertBody: String): List<String> = listOf(
        copyBody.contains("TrackRule.shiftedPts(ex.sampleTime, baseUs, shiftUs)") to "音频 PTS 必须经 shiftedPts",
        copyBody.contains("if (outPts != null)") to "判 null 丢弃必须显式在位",
        insertBody.contains("TrackRule.RELATIVE_PATH") to "入库必须走 Inau 子目录",
        insertBody.contains("IS_PENDING, 1") to "入库必须两段式"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    // endregion

    // region decodeMono 旧签名委托（AudioSync）

    @Test
    fun `decodeMono旧签名仍委托第0轨`() {
        val m = masked("player/AudioSync.kt")
        val bodies = regionsOf(m, "decodeMono").map { m.substring(it.start + 1, it.end - 1) }
        assertEquals("decodeMono 必须恰好两个重载（旧签名 + 轨序版）", 2, bodies.size)
        val legacy = bodies.first { !it.contains("audioIndex") }
        assertTrue(
            "旧单参签名必须委托轨序版第 0 轨（对比页双文件对齐行为零变化的回归红线）",
            legacy.contains("decodeMono(context, uri, 0)")
        )
        val indexed = bodies.first { it.contains("audioIndex") }
        assertTrue(
            "轨序版必须按 nth 计数取第 audioIndex 条音轨",
            indexed.contains("nth == audioIndex")
        )
        // 红绿突变：旧签名改委托第 1 轨（对比页同步会拿内录轨当主轨），同一把尺必须报红
        val mutant = legacy.replace("decodeMono(context, uri, 0)", "decodeMono(context, uri, 1)")
        assertTrue("委托改轨突变体必须报红", !mutant.contains("decodeMono(context, uri, 0)"))
    }

    // endregion
}
