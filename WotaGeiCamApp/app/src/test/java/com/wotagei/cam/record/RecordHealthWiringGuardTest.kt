package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.mainSourceFile
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起录自检在 UI 侧的**接线守卫**（[com.wotagei.cam.ui.CameraScreen] 的 `RecordRunner`）。
 *
 * 纯函数判定再好，接线断了就白搭：自检拍子没挂 → 永不重录；丢弃没兜底 → 幽灵 pending；
 * 触顶没引用上限 → 无限重启。这里逐条钉住：
 * - `healthCheckLoop` 存在且真调了 [healthVerdict]/[retryActionOf]（不是空拍）；
 * - 无三格信号的引擎（MR 路）走 [streamHealthVerdict] 的产出活性判据（**不是**只认错误码），
 *   且必须读 `h.outputBytes`；旧的 [weakHealthVerdict] 已删除，全仓不得再出现该名字；
 * - MR 路读数源必须落在 [MrRecorder.health()]：含 `Os.fstat` 与 `-1L` 兜底（量不到不许当 0）；
 * - `abandonAndRestart` 必须丢弃被弃段的 pending（`discard`，且过 `shouldDiscardAbandonedSink`
 *   闸门——只删未进产物的那枚）并重放会话；收尾本体抽在 `teardownAbandonedSession`，
 *   与自检触顶的 GIVE_UP 分支**共用同一套**（两处各写一份必漂移）；
 * - GIVE_UP（触顶宣告失败）时引擎必然还在录（入口守卫 `rec = recorder ?: return`）：
 *   必须**先**走共用收尾停引擎、**后** `failNow`——裸调 failNow 只清引用位，引擎继续编码收音、
 *   pending 永不 discard，后续 MAX_FILE_BYTES 轮转还会把废会话的段 commit 进相册；
 * - `MAX_RESTART_ATTEMPTS` 被**函数体**引用（不存在无限重试；不锁整文件，避免 import 行喂绿）；
 * - 自检失败走明确失败（`SELF_CHECK_FAILED`），**不许**闪 ERROR：`abandonAndRestart` 体内
 *   不得出现 `RecordStatus.ERROR`（status 全程保持 START）；
 * - **停止路径不受自检影响**：`stopInternal` 体内不得出现任何自检分支（负面断言）。
 */
class RecordHealthWiringGuardTest {

    private val screenSrc by lazy { mainSourceText("ui/CameraScreen.kt") }
    private val masked by lazy { codeOnly(screenSrc) }

    @Test
    fun `healthCheckLoop 必须真调自检纯函数并按判定动作`() {
        val body = bodyOf(masked, "healthCheckLoop")
        assertTrue("锚点丢失：没截到引擎读书", body.contains("rec.health()"))
        assertTrue(
            "healthCheckLoop 必须调 healthVerdict（可观测引擎）",
            body.contains("healthVerdict(")
        )
        assertTrue(
            "healthCheckLoop 必须调 retryActionOf 决定动作",
            body.contains("retryActionOf(")
        )
        assertTrue(
            "healthCheckLoop 必须按 milestonesObservable 分流到 streamHealthVerdict",
            body.contains("milestonesObservable") && body.contains("streamHealthVerdict(")
        )
        assertTrue(
            "MR 路产出活性判据必须读 h.outputBytes（不许拿三格去套）",
            body.contains("h.outputBytes")
        )
        assertFalse(
            "旧弱判 weakHealthVerdict 已删除，healthCheckLoop 不得再引用",
            body.contains("weakHealthVerdict")
        )
        assertTrue(
            "WAIT 必须重投自检拍（否则一次 WAIT 后永不再问）",
            body.contains("handler.postDelayed(healthCheckTask, HEALTH_TICK_MS)")
        )
        assertTrue(
            "触顶必须走明确失败 SELF_CHECK_FAILED",
            body.contains("RecordError.SELF_CHECK_FAILED")
        )
    }

    @Test
    fun `MrRecorder 的产出活性读数必须读 fd 且量不到回 -1`() {
        val src = codeOnly(mainSourceText("record/MrRecorder.kt"))
        val body = bodyOf(src, "health")
        assertTrue("MR 路读数源必须含 Os.fstat（读真实 fd 的 st_size）", body.contains("Os.fstat"))
        assertTrue("MR 路读不到必须回 -1L（不可观测，不许当 0 字节判废）", body.contains("-1L"))
        assertTrue("MR 路读数必须落进 RecorderHealth.outputBytes", body.contains("outputBytes"))
    }

    @Test
    fun `CodecRecorder 的 outputBytes 必须来自已有写入账`() {
        val src = codeOnly(mainSourceText("record/CodecRecorder.kt"))
        val body = bodyOf(src, "health")
        assertTrue("Codec 路 health 必须如实填 outputBytes", body.contains("outputBytes"))
        assertTrue("Codec 路 outputBytes 必须复用已写字节账 segWrittenBytes", body.contains("segWrittenBytes"))
    }

    @Test
    fun `weakHealthVerdict 已删除 全仓零命中`() {
        // 该判定已被产出活性判据取代：任何一处残留都是"说了谎"的死代码，必须 0 命中。
        val root = mainSourceFile("ui/CameraScreen.kt").parentFile?.parentFile
            ?: error("定位主源码根失败")
        val hits = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText(Charsets.UTF_8).contains("weakHealthVerdict") }
            .map { it.name }
            .toList()
        assertTrue("weakHealthVerdict 必须全仓 0 命中，实际命中：$hits", hits.isEmpty())
    }

    @Test
    fun `abandonAndRestart 必须丢弃被弃段并重放会话`() {
        val body = bodyOf(masked, "abandonAndRestart")
        // 停录+弃段账目+摘面的纪律本体抽到了 teardownAbandonedSession（与 GIVE_UP 共用），必须委托它
        assertTrue(
            "必须委托共用收尾 teardownAbandonedSession（两处各写一份必漂移）",
            body.contains("teardownAbandonedSession(rec)")
        )
        assertTrue("必须 attempt++ 记录已用机会", body.contains("attempt++"))
        assertTrue("必须重放 beginSession", body.contains("beginSession()"))
        assertFalse(
            "自动重录不许闪 ERROR（status 全程保持 START），只许 failNow 走失败",
            body.contains("RecordStatus.ERROR")
        )
        // 原拆解纪律逐条保留，只换靶子到共用收尾函数体
        val t = bodyOf(masked, "teardownAbandonedSession")
        assertTrue("停止期必须先置位（GL 侧抑制拆解伪影）", t.contains("markRecordTearingDown()"))
        assertTrue("MEND 滞留帧必须先冲刷", t.contains("flushArcPending()"))
        assertTrue("必须先停引擎再释放", t.contains("rec.stop()") && t.contains("rec.release()"))
        assertTrue(
            "必须 discard 被弃段的 sink（幽灵 pending 兜底）",
            t.contains(".discard(")
        )
        assertTrue(
            "discard 必须过 shouldDiscardAbandonedSink 闸门（sessionSink 只在 beginSession 赋值，" +
                "分段轮转后它指向已 commit 的首段，无条件删会删用户成片）",
            t.contains("shouldDiscardAbandonedSink(")
        )
        assertTrue(
            "收尾必须摘编码面（DIRECT 摘 recordingTarget，GL 摘 outputSurface）",
            t.contains("setRecordingTarget(null)") && t.contains("setOutputSurface(null, 0, 0)")
        )
    }

    /**
     * P1 顺序红线：弃段意图必须在 `rec.stop()` **之前**声明。顺序反了（先停后弃）引擎已按旧的 keep
     * 语义判过，弃段意图来不及生效，坏段照样 commit 进相册。位置序断言防止有人把两行写反。
     * （红线随收尾本体一起搬进了共用收尾函数。）
     */
    @Test
    fun `共用收尾必须先声明弃段再停录`() {
        val body = bodyOf(masked, "teardownAbandonedSession")
        val abandon = body.indexOf("abandonCurrentSegment()")
        val stop = body.indexOf("rec.stop()")
        assertTrue("必须调 rec.abandonCurrentSegment 声明弃段", abandon >= 0)
        assertTrue("锚点丢失：切片没截到 rec.stop()", stop >= 0)
        assertTrue(
            "弃段声明必须出现在 rec.stop() 之前（顺序反了引擎已按 keep 判过，弃段来不及生效）",
            abandon < stop
        )
    }

    /**
     * 触顶 GIVE_UP 的收尾红线：入口守卫（`rec = recorder ?: return`）保证此刻引擎必然还在 START 态
     * 录着，`failNow` 只清 `polling`/`recorder` 两个引用位、`stopInternal` 见 `recorder == null` 会
     * 早退——裸调 failNow 等于让引擎带着没人管的 fd 继续编码收音。位置序断言：先共用收尾、后 failNow。
     */
    @Test
    fun `自检触顶 GIVE_UP 必须先走共用收尾再宣告失败`() {
        val body = bodyOf(masked, "healthCheckLoop")
        val teardown = body.indexOf("teardownAbandonedSession(rec)")
        val fail = body.indexOf("failNow(")
        assertTrue("锚点丢失：GIVE_UP 分支没截到共用收尾调用", teardown >= 0)
        assertTrue("锚点丢失：GIVE_UP 分支没截到 failNow", fail >= 0)
        assertTrue(
            "触顶宣告失败前必须先停掉还在录的引擎（先 failNow 后收尾 = 收尾撞 stopInternal 早退失守）",
            teardown < fail
        )
    }

    /** 防两套收尾漂移：自动重录与触顶失败都必须调用同一个共用收尾函数（不存在第二份手抄）。 */
    @Test
    fun `自动重录与自检触顶必须共用同一收尾函数`() {
        assertTrue(
            "abandonAndRestart 必须调 teardownAbandonedSession",
            bodyOf(masked, "abandonAndRestart").contains("teardownAbandonedSession(")
        )
        assertTrue(
            "healthCheckLoop 的 GIVE_UP 分支必须调同一 teardownAbandonedSession",
            bodyOf(masked, "healthCheckLoop").contains("teardownAbandonedSession(")
        )
    }

    /**
     * MR 路的弃段与换段清错接线：
     * - stop 与换段的 keep 判定都必须走 [segmentKeepDecision]（弃段意图压过"看着正常"）；
     * - 换段成功（`state = START`）之后必须清 `engineError`，否则 801 后 stop 抛过一次就会把
     *   STOP_FAILED 一直挂着、此后每一拍都误判 UNHEALTHY 而整场重启；
     * - 清错前必须把旧段失败码挪进 `sessionError`（不把"旧段确实失败过"从最终结果里抹掉）；
     * - 新段起始复位弃段意图（一次判废不许拖累之后每一段）；`prepare`/`start` 的新会话复位也一并钉住
     *   （冗余防御，防复用引擎实例时陈旧弃段意图拖垮新会话）。
     */
    @Test
    fun `MrRecorder 弃段与换段清错必须接线`() {
        val src = codeOnly(mainSourceText("record/MrRecorder.kt"))
        val stop = bodyOf(src, "stop")
        assertTrue(
            "stop 的 keep 判定必须走 segmentKeepDecision（弃段意图压过正常判定）",
            stop.contains("segmentKeepDecision(")
        )
        val rot = bodyOf(src, "rotateSegment")
        assertTrue("换段的旧段封段也要走 segmentKeepDecision", rot.contains("segmentKeepDecision("))
        val startIdx = rot.indexOf("state = EngineState.START")
        val clearIdx = rot.indexOf("engineError = null")
        assertTrue(
            "换段成功（state=START）之后必须清 engineError（否则残留错误把仍在正常录的新段整场重启）",
            startIdx >= 0 && clearIdx > startIdx
        )
        assertTrue(
            "清错前必须把旧段失败码挪进 sessionError（结果口径不丢信息）",
            rot.contains("sessionError = engineError")
        )
        assertTrue("新段起始复位弃段意图", rot.contains("abandonSegment = false"))
        // 新会话起始也各有一处复位（prepare 与 start）。属冗余防御、删掉运行时不会红，但一旦日后
        // 改成复用引擎实例，漏掉这处就会让"上一会话的弃段意图"拖垮新会话的每一段——故由守卫钉住。
        assertTrue(
            "prepare 新会话起始必须复位弃段意图（复用引擎实例时旧会话弃段意图不许拖累新会话）",
            bodyOf(src, "prepare").contains("abandonSegment = false")
        )
        assertTrue(
            "start 新会话起始必须复位弃段意图（同上，防复用引擎时的陈旧弃段意图）",
            bodyOf(src, "start").contains("abandonSegment = false")
        )
        val abandon = bodyOf(src, "abandonCurrentSegment")
        assertTrue("abandonCurrentSegment 必须置位 abandonSegment", abandon.contains("abandonSegment = true"))
    }

    /** CodecRecorder 同族接线：本路存在"无错误码但三格不全"的 keep=true 态，故也接弃段通路。 */
    @Test
    fun `CodecRecorder 也接弃段通路`() {
        val src = codeOnly(mainSourceText("record/CodecRecorder.kt"))
        val abandon = bodyOf(src, "abandonCurrentSegment")
        assertTrue("CodecRecorder 必须实现弃段置位", abandon.contains("abandonSegment = true"))
        val pump = bodyOf(src, "pumpLoop")
        assertTrue("pumpLoop 的封段 keep 必须走 segmentKeepDecision", pump.contains("segmentKeepDecision("))
        val seg = bodyOf(src, "runSegment")
        assertTrue("runSegment 段起始必须复位弃段意图", seg.contains("abandonSegment = false"))
    }

    /**
     * MR 路产出活性的双源交叉核对：`Os.fstat` 便宜但依赖 FUSE 行为，读数可疑（< 门槛）时须再查
     * MediaStore `SIZE` 交叉核对；用 `sizeOfOrUnknown`（量不到回 -1）而非 `sizeOf`（量不到回 0），
     * 否则一次查询失败会被误译成"零字节"而在窗口末误杀。
     */
    @Test
    fun `MR 路产出活性必须做双源交叉核对`() {
        val body = bodyOf(masked, "healthCheckLoop")
        assertTrue("必须调 resolveOutputBytes 合成两源", body.contains("resolveOutputBytes("))
        assertTrue(
            "交叉核对必须读 provider 权威读数（sizeOfOrUnknown，量不到回 -1）",
            body.contains("sizeOfOrUnknown(")
        )
        assertTrue("可疑判据必须是 fstat 读数 < 门槛", body.contains("h.outputBytes") && body.contains("MIN_OUTPUT_BYTES"))
    }

    /** MrRecorder.target 标 @Volatile：health 今天与控制线程同线程读它，此标注是防御未来把自检挪出该线程的零成本保险。 */
    @Test
    fun `MrRecorder 的 target 必须标 Volatile`() {
        val src = codeOnly(mainSourceText("record/MrRecorder.kt"))
        assertTrue(
            "target 必须 @Volatile（今天 health 与控制线程同线程，此为防御未来跨线程读的零成本保险）",
            src.contains("@Volatile private var target")
        )
    }

    @Test
    fun `重录必须复用同一套会话接线`() {
        val begin = bodyOf(masked, "beginSession")
        assertTrue("会话体必须每段必发 setArcConvert", begin.contains("setArcConvert(profile.arcConvert, profile.fps)"))
        assertTrue(
            "会话体必须下发编码面并等挂好（首录与重录同一口径）",
            begin.contains("setOutputSurface(surface, profile.width, profile.height, profile.fps)") &&
                begin.contains("awaitEncoderSurface()")
        )
        assertTrue("会话体必须重建 pending（partIndex=0 起头）", begin.contains("createPending(0)"))
        assertTrue("会话体必须在成功后才置 START", begin.contains("status.value = RecordStatus.START"))
    }

    @Test
    fun `重试上界必须被源码引用 不存在无限重试`() {
        // 只锁**函数体**（bodyOf）而非整文件 occurrences——后者会被
        // `import com.wotagei.cam.record.MAX_RESTART_ATTEMPTS` 那一行喂绿（AGENTS 点名的
        // "声明行喂绿"）：把 healthCheckLoop 里的常量换成字面量 2 时旧写法仍绿。
        // 上界本身的边界语义另由 RecordHealthTest 的桥函数用例（attempts<max）直断言。
        assertTrue(
            "healthCheckLoop 必须引用 MAX_RESTART_ATTEMPTS（不存在无限重试）",
            bodyOf(masked, "healthCheckLoop").contains("MAX_RESTART_ATTEMPTS")
        )
    }

    @Test
    fun `停止路径不受自检影响`() {
        val body = bodyOf(masked, "stopInternal")
        assertTrue("锚点丢失：切片没截到 rec.stop()", body.contains("rec.stop()"))
        // 负面断言：stop 收尾绝不能掺进自检分支（重录/自检只在录制中健康循环里发生）
        assertFalse("stopInternal 不许出现 healthCheckLoop", body.contains("healthCheckLoop"))
        assertFalse("stopInternal 不许出现 abandonAndRestart", body.contains("abandonAndRestart"))
        assertFalse("stopInternal 不许出现 healthVerdict", body.contains("healthVerdict"))
        assertFalse("stopInternal 不许出现 retryActionOf", body.contains("retryActionOf"))
    }

    /**
     * 录制中途「引擎判废看门狗」的接线（2026-10-08 真机缺陷的次生缺陷）。
     *
     * 现场：引擎在录制中途判废（第二次换段失败）时，UI 侧 status 仍是 START、计时冻结在 00:14、
     * 屏幕上一个字都没有——用户既不知道录废了也没有收尾。看门狗把这个"死在录制里"的态变成
     * 可见失败：判定走纯函数 [engineWatchdogTrips]（全表在 `RecordHealthTest`），收尾走
     * `stopInternal`（把引擎错误码写进 result 并带上已提交分段，UI 走既有失败提示通道）。
     *
     * 定位说明：本文件里 `override fun run()` 有三处（pollTask / healthCheckTask / 看门狗），
     * 所以按"体内出现看门狗判据"摘出唯一那一个；摘不到即失败（被删/被改名不许静默算过）。
     */
    private fun watchdogRunBody(): String {
        val bodies = KotlinSourceScan.regionsOf(masked, "run")
            .map { masked.substring(it.start + 1, it.end - 1) }
            .filter { it.contains("engineWatchdogTrips(") }
        assertTrue("必须恰好一个 run 体走引擎看门狗判据，实际 ${bodies.size} 个", bodies.size == 1)
        return bodies[0]
    }

    @Test
    fun `录制中途引擎判废看门狗必须接线`() {
        val body = watchdogRunBody()
        assertTrue("看门狗必须读引擎健康读数的错误码", body.contains("rec.health().errorCode"))
        assertTrue(
            "看门狗判定必须走纯函数 engineWatchdogTrips（不许内联判空）",
            body.contains("engineWatchdogTrips(")
        )
        assertTrue(
            "看门狗必须按录制态把门（非录制态的错误码归停止收尾路径）",
            body.contains("status.value == RecordStatus.START")
        )
        assertTrue("看门狗必须自续（录制中每拍重投，否则一次判废后再无人问）", body.contains("handler.postDelayed(this, POLL_MS)"))
        assertTrue("看门狗收尾必须走 stopInternal（带上已提交分段 + 把错误码交给 UI）", body.contains("stopInternal()"))
        assertFalse(
            "看门狗收尾不许走 failNow（只造一个无产物的失败结果，用户已录好的分段信息全丢）",
            body.contains("failNow(")
        )
    }

    /**
     * 挂载时机红线：看门狗只许在起录自检确认健康（HEALTHY）之后挂。
     * 起录窗口内的失败归自检管（那里有"自动重录"语义、attempt 有上界）；看门狗若从起录就挂，
     * 首拍就能读到错误码并抢先 `stopInternal`，把自检的自动重录整条挤掉（信号可从首拍起就有错）。
     */
    @Test
    fun `自检确认健康后必须挂上引擎看门狗`() {
        val body = bodyOf(masked, "healthCheckLoop")
        val healthy = body.indexOf("HealthVerdict.HEALTHY")
        val arm = body.indexOf("handler.post(engineWatchdogTask)")
        assertTrue("锚点丢失：healthCheckLoop 没截到 HEALTHY 分支", healthy >= 0)
        assertTrue("锚点丢失：healthCheckLoop 没截到看门狗挂载", arm >= 0)
        assertTrue("看门狗必须在 HEALTHY 分支挂（判定顺序不许前移）", arm > healthy)
        assertTrue("HEALTHY 仍须归零 attempt（下一次起录从满机会算）", body.contains("attempt = 0"))
        assertFalse(
            "起录路径不许直接挂看门狗（会挤掉自检的自动重录）",
            bodyOf(masked, "beginSession").contains("engineWatchdogTask")
        )
    }

    /**
     * MR 路换段接线（P1 缺陷修复守卫）：onInfo 曾要求 `what == MEDIA_RECORDER_INFO_UNKNOWN`
     * 才往下走、却在 `extra` 里找 801/802——AOSP 契约里 801/802 是 **what** 的取值（extra 恒 0），
     * 判定序反转 ⇒ 真机只打日志、`rotateSegment` 永不可达，触顶后静默停写。这里钉住：
     * - 判定必须走纯函数 [mrRotateRequested]（本体与全表单测在 RecordHealthTest，这里锁接线）；
     * - **先打日志后判定**：日志保留 what/extra 原值是修复后的自证手段（判定写反时日志先红）；
     * - 体内不得再出现裸 `when (extra)` 换段判定（旧写法回潮即红）。
     */
    @Test
    fun `MrRecorder onInfo 必须走 mrRotateRequested 且先打日志`() {
        val src = codeOnly(mainSourceText("record/MrRecorder.kt"))
        val body = bodyOf(src, "onInfo")
        val judge = body.indexOf("mrRotateRequested(")
        val log = body.indexOf("Log.i(")
        assertTrue("onInfo 必须调纯函数 mrRotateRequested（判定不许内联回回调里）", judge >= 0)
        assertTrue("锚点丢失：onInfo 没截到日志调用", log >= 0)
        assertTrue(
            "必须先打日志再判定（what/extra 原值进日志是修复后的自证手段）",
            log < judge
        )
        assertTrue("日志必须带 what= 与 extra= 两个值", mainSourceText("record/MrRecorder.kt").contains("mr info what=\$what extra=\$extra"))
        assertTrue("判定为真必须调 rotateSegment（换段动作不许丢）", body.contains("rotateSegment("))
        assertFalse(
            "不得再用裸 when (extra) 判换段（AOSP 里 801/802 是 what 的取值，旧判定是反转的根因）",
            body.contains("when (extra)")
        )
        assertFalse(
            "不得再以 what==UNKNOWN 作换段门槛（那正是判定反转的旧写法）",
            body.contains("MEDIA_RECORDER_INFO_UNKNOWN")
        )
    }
}
