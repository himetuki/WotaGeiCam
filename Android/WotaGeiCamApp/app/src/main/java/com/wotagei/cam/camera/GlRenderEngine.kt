package com.wotagei.cam.camera

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.wotagei.cam.diag.Diag as Log
import android.view.Surface
import com.wotagei.cam.core.ArcConvertMode
import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.DENOISE_LEVEL_FULL
import com.wotagei.cam.core.DENOISE_LEVEL_LITE
import com.wotagei.cam.core.DENOISE_LEVEL_OFF
import com.wotagei.cam.core.DisplayScale
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.GlowParams
import com.wotagei.cam.core.GlowStatus
import com.wotagei.cam.core.glowRgb
import com.wotagei.cam.core.PeakingColor
import com.wotagei.cam.core.glowScaleFor
import com.wotagei.cam.record.ArcKeepRule
import com.wotagei.cam.record.ArcOp
import com.wotagei.cam.record.ArcRepairFlow
import com.wotagei.cam.record.FrameLedger
import com.wotagei.cam.record.arcKeepToleranceNs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAG_GL = "WotaGl"
private const val BYTES_PER_FLOAT = 4

/**
 * 窗口面入口契约（对 `PreviewSink` 的必要补充，不是改名）。
 *
 * `PreviewSink` 是「拉」模型（Camera2 侧调 [PreviewSink.cameraTargets] 取目标面），缺一条把
 * 「上屏面」推进 sink 的通道，而 GPU 与 DIRECT 两种模式都需要它：
 * - GPU：[GlRenderEngine] 实现本接口，用窗口面建 EGLSurface；
 * - DIRECT：直显 sink 同样要实现本接口，把 `SurfaceView` 的 Surface 存下并出现在 `cameraTargets()` 返回值里。
 *
 * 未实现本接口的 sink，`ui/widget/CameraSurface` 退回调用其 `onDisplaySurface` 回调参数（见 CameraSurface.kt）。
 */
interface DisplaySurfaceReceiver {
    /**
     * @param surface 窗口面；null 表示承载已销毁，须销毁对应 EGLSurface，但线程与上下文保持存活，
     *                让「预览关闭 + 后台继续录制」时编码器 pass 仍能吃到帧（渲染/采集分离）
     * @param widthPx 承载视图宽（px），等比居中 contentRect 的分母
     * @param heightPx 承载视图高（px）
     */
    fun onDisplaySurfaceChanged(surface: Surface?, widthPx: Int, heightPx: Int)
}

/**
 * GPU 渲染引擎：EGL14 上下文 + GLES 程序 + 双 viewport 输出 + 相机帧驱动（03 文档第 3 节）。
 *
 * 数据流：Camera2 → 本引擎的 `SurfaceTexture`(OES) → ①编码器输入面（严格全屏）②窗口面（等比居中）。
 * ②那一侧可以多一道「毛玻璃底板」的贴板 pass（#84，[FrostCardTable] 交矩形、[FrostPlatePass] 画），
 * ①那一侧永远只有相机帧本身——成片里出现毛玻璃是 S1 缺陷，这条有源码级用例守着。
 * 所有 GL/EGL 调用都在专用 [HandlerThread] 上，主线程只投递状态；帧节奏由
 * [SurfaceTexture.OnFrameAvailableListener] 驱动（WHEN_DIRTY 语义，不定时器空转）。
 *
 * DIRECT（不使用 GPU）模式下 **不要构造本类**：相机侧直接把预览面与录像面挂进同一 session（03 文档 §3.7）。
 * 切渲染模式 = 关预览 → 换 sink → 重开预览。
 *
 * 实例一次性：[release] 后不可复用（线程已退出），重建请 new 新实例。
 */
class GlRenderEngine : PreviewSink, DisplaySurfaceReceiver, FrostBlurProvider, GlowProvider,
    SurfaceTexture.OnFrameAvailableListener {

    companion object {
        /** EGL14 未暴露该常量，取 Khronos 原值 EGL_OPENGL_ES3_BIT_KHR */
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

        /** 上屏节流上限（03 文档 §3.1）：只作用于窗口 pass，编码器 pass 一帧不丢 */
        private const val MAX_DISPLAY_FPS = 60f

        /**
         * program 会话缓存上限（docs/plan/25 §3.9.2-3）：键是四维 [ProgramKey]，两槽同键共用；
         * 反复切监看/曲线/降噪只做 `glUseProgram`，不重编。3×2×3 组合理论值远大于此，
         * 但实际常用 ≤4（默认档 / 曲线 / 降噪 / 监看各一），8 只是"切来切去不重编"的余量。
         */
        private const val PROGRAM_CACHE_LIMIT = 8

        /** 直方图采样拍点：4Hz 足够调曲线参考用，读回成本 16KB/拍 */
        private const val HISTOGRAM_PERIOD_MS = 250L

        /** 峰值对焦邻域取样步长（UV 空间，03 文档 §3.6 的 texel≈0.002 档位） */
        private const val PEAKING_TEXEL = 0.002f

        /**
         * 停录冲刷屏障（[flushArcPending]）的等待上限。r09 实测 GL 线程会被 >1s 的长任务
         * 占用（编码面 swap 背压/着色器编译），旧值 1s 必超时；上限只防 GL 线程彻底卡死
         * （那此时上屏也已冻结，多等无益），5s 对实测长任务留 5 倍余量。
         *
         * 与 `RecordRunner.shutdown()` 3s 外层 latch 的数值关系：极端卡顿下外层先超时——
         * 那只影响「退出页要不要等收尾」（收尾线程仍会把 stopInternal 跑完并记
         * 「录制收尾超时」），本值才决定 flush 是否落地、末帧补弧保不保得住；
         * 两个超时各管各的语义，故意不取齐。
         */
        private const val FLUSH_BARRIER_TIMEOUT_MS = 5_000L
    }

    // region 线程与生命周期

    private val started = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val drawScheduled = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // endregion

    // region 跨线程「意图」（GL 线程每帧读一次；各项独立更新，错一帧没有观感影响）

    /** 给 Camera2 的预览目标面，GL 线程建好后发布 */
    @Volatile
    private var cameraTargetSurface: Surface? = null

    @Volatile
    private var wantEffect: FrameEffect = FrameEffect.NONE

    @Volatile
    private var wantZebraThreshold = 0.6f

    @Volatile
    private var wantZebraDensity = 1

    @Volatile
    private var wantPeakingStrength = 0.6f

    @Volatile
    private var wantPeakingColor: PeakingColor = PeakingColor.RED

    /** 传感器方向与显示角分两路写入，残余角内部现算（与 DirectSink 同一套语义） */
    private val orientation = PreviewOrientation()

    @Volatile
    private var wantFrameWidth = 0

    @Volatile
    private var wantFrameHeight = 0

    /** 待消费的相机帧数（多帧只取最新，等价于分析流的 acquireLatestImage） */
    @Volatile
    private var newFrames = 0

    /** 状态脏标记：无新帧也要重画一次（切效果/改尺寸/换面） */
    @Volatile
    private var dirty = true

    @Volatile
    private var lastDrawnTimestampNs = 0L

    /** 等比居中区（窗口坐标，原点左上），GL 线程整体替换、主线程读快照 */
    @Volatile
    private var publishedContentRect = Rect()

    /**
     * 毛玻璃 A/B 开关的**意图**（#84，默认关）。写在调用线程、生效在 GL 线程（见 [setFrostBlurEnabled]）。
     *
     * 真源在 `ui/`（配置键/持久化都不在本层），而 A2 之后 UI 是把这一位**写进 [FrostCardTable] 的表头**、
     * 由 GL 线程每帧搬进来（见 [adoptFrostIntentFromTable]）——UI 拿不到引擎实例，也不需要拿到。
     * 这里只认这一个布尔值；DIRECT 模式下它同样能被设 true，
     * 但 [isFrostBlurAvailable] 恒为 false —— 那是物理上限而不是 bug（docs/plan/14 §二）。
     */
    @Volatile
    private var wantFrostBlur = false

    // endregion

    // region GL 线程状态（@Volatile 的那几个例外供主线程做 best-effort 查询）

    @Volatile
    private var glReady = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglVersion = 2

    /** 1x1 无窗口面：没有任何输出面时也要有 current 上下文才能 updateTexImage */
    private var pbufferSurface: EGLSurface? = null

    @Volatile
    private var windowEglSurface: EGLSurface? = null

    private var windowNative: Surface? = null

    @Volatile
    private var windowWidth = 0

    @Volatile
    private var windowHeight = 0

    @Volatile
    private var encoderEglSurface: EGLSurface? = null

    /** 编码面的 Surface 本体。GL 线程写、录制控制线程经 [isEncoderSurfaceNative] 读 → 必须 @Volatile */
    @Volatile
    private var encoderNative: Surface? = null

    @Volatile
    private var encoderWidth = 0

    @Volatile
    private var encoderHeight = 0

    /** 目标帧率：>0 时给编码器面写均匀呈现时间戳，避免容器报出非整数帧率 */
    @Volatile
    private var encoderFps = 0

    /**
     * 本段是否按 NTSC 非整数档落网格（23.976/29.97/59.94）。
     *
     * 只影响**网格步长**这一个算术点（`ntscGridStepNs(fps)` vs `1e9/fps`），不碰 fps 本身、
     * 不碰相机请求、不碰容器声明——链上其余部分仍是 Int，这是选「Int + 映射表」而不是
     * 「全链升浮点」的原因（见 [ntscGridStepNs]）。
     */
    @Volatile
    private var encoderNtsc = false

    /** 已交给编码器的帧序号，用于算**网格**时间戳与抽帧位次账 */
    private var encoderFrameIndex = 0

    /**
     * 本段已写出的最后一枚呈现时间戳（ns，0 = 还没写过）。
     *
     * 只用来防时钟回退：新戳**小于**它时退回去（容器对同轨时间戳倒退会直接判失败），
     * 相等允许（源帧率高于目标帧率时同一网格会落进多帧，见 [encoderSlotPtsNs]）。
     */
    private var encoderLastPtsNs = 0L

    /**
     * 均匀时间戳的基准（ns）。必须留在 `System.nanoTime()` 同一时间域：
     * EGL 默认就是按换帧瞬间的系统时钟打 PTS，从 0 起算会和 MediaRecorder
     * 音频侧的时钟跨域，muxer 直接产出不可用文件（真机表现为录制后无成片）。
     */
    private var encoderBaseNs = 0L

    // ---- 打戳观测账（S1-M2-02 / M2-03 / M2-06 三联，2026-10-10）----
    //
    // 只观测、不改口径：三枚都进段收尾行（[drainStampObs]），一格数字定案是「源真慢」
    // 「两时基有速率差」「管线延迟单调增长」还是「驱动写不进戳」。此前应用内部一个数字都没有，
    // 用户报「掉帧」时只能靠外部 ffprobe 反推。O(1) 内存：只留和/计数/极值，不留样本
    // （与 CodecRecorder 的 muxPtsFixes/muxPtsMaxBackUs 同一记账口径）。
    // 全部由 GL 线程独占写（写点在 [stampEncoderPresentation]，读点在 [replaceEncoderSurface]），
    // 不需要 @Volatile。

    /** 帧龄**漂移**累计和（ns）：(换帧墙钟 − SOF 戳) − 本段首帧基线，见 stampEncoderPresentation */
    private var stampAgeSumNs = 0L

    /**
     * 本段帧龄基线（2026-10-26 反馈附B）：首帧的 (nanoTime − SOF 戳)。
     * 两个时钟域（nanoTime 任意基准 vs SENSOR_TIMESTAMP 由 TIMESTAMP_SOURCE 决定）差一个
     * 不定常量，直接相减恒为巨大负数（真机 −21.7h）；只看相对基线的漂移才有时钟无关的诊断价值。
     */
    private var stampAgeBaseNs = 0L

    /** 帧龄样本数（相机 SOF 戳还没到的那几帧不计入） */
    private var stampAgeCount = 0L

    /** 帧龄峰值（ns）：最坏管线延迟 */
    private var stampAgeMaxNs = 0L

    /** 网格空格数（格）：相邻两枚戳跳格数 >1 的部分累计（M2-01 单侧残余的投影） */
    private var stampGapSlots = 0L

    /** 最大空格跨距（格）：单次跳跃空掉多少格 */
    private var stampGapMaxSlots = 0L

    /** EGL 写呈现时间戳失败次数（该扩展在部分驱动上不可用） */
    private var stampFailCount = 0

    // region 录制期转换（强制 24/25fps 无精确档时的抽帧/补弧，2026-10-03 定版）

    /** 转换模式；null = 不转换（编码支路照旧整帧直通） */
    @Volatile
    private var arcConvert: ArcConvertMode? = null

    /** 转换目标帧率（= RecordProfile.fps，喂均匀节奏与 PTS 分母） */
    @Volatile
    private var arcDstFps = 0

    /** 在线保留规则：到达时刻 vs 应到时刻，无需预估传感器帧率 */
    private var arcRule = ArcKeepRule(slotNs = 0L)

    /** 抽帧/补弧状态机（录制在线路：保留决策走 onClassification） */
    private var arcFlow = ArcRepairFlow(dstFps = 24)

    /** MEND 模式的离屏通道（pending/acc 乒乓 + 整像素选择），GL 线程内使用 */
    private var arcMendPass: ArcMendPass? = null

    /**
     * 补弧合并差分死区（0..32，8bit luma 级）。默认吃参数总线发布的只读快照；
     * [setArcMergeDeadband] 可运行期改档（GL 线程随后下发给 ArcMendPass 的 uniform）。
     */
    @Volatile
    private var wantArcMergeDeadband = ArcMergeDeadband.deadband

    /** 被抽帧位次账（GL 线程写、主线程 stop 时快照取走；配对 = 输出位次 to 丢弃数） */
    private val arcDrops = ArrayList<Pair<Int, Int>>()
    private val arcDropsLock = Any()

    /**
     * 停止期标志：RecordRunner 进入停止序列（冲刷滞留帧 → 拆编码器/muxer → 解绑编码面）前置位，
     * [degradeArcConvert] 看到它就不再清账换模式。停止窗口内任何 GL 编码失败都是「面已死」的
     * 拆解伪影（codec.stop 即输入面失效 → swap 必 EGL_BAD_ALLOC）而非会话中真失败，此时降级
     * 只会把已记好的位次账/实测帧率清掉——sidecar drops 归零、成片名丢前缀（r09 实测）。
     * 置位：录制线程在拆任何东西之前经 [markRecordTearingDown] 写（@Volatile 直写即可，
     * GL 线程随后每帧读它）；复位：下一段录制 start 必发 [setArcConvert]（含 null），在其 GL 体里解除。
     */
    @Volatile
    private var recordTearingDown = false

    /**
     * 换段重挂窗口抑制位：GPU 路分段轮转时旧编码面已死（codec.stop）而新面尚未挂好，窗口内
     * GL 撞死面的 swap 失败与停止期同属拆解伪影（degradeSuppressed 并列判定）。**不复用**
     * [recordTearingDown]：会话内轮转不会重发 [setArcConvert]，没有「下次必发」的复位点——
     * 这里由重挂回调 mark、await 落定（成功或超时）后 clear，try/finally 成对保证。
     * 置位在泵线程（重挂回调）、读取在 GL 线程（degradeArcConvert），跨线程必须 @Volatile。
     */
    @Volatile
    private var segmentRotating = false

    /** 进入换段重挂窗口（必须在 [setOutputSurface] 下发新面**之前**，见 [segmentRotating]） */
    fun markSegmentRotating() {
        segmentRotating = true
    }

    /** 离开换段重挂窗口（await 成功或超时都要调，重挂回调用 try/finally 保证） */
    fun clearSegmentRotating() {
        segmentRotating = false
    }

    /**
     * 抑制期日志闩（与 [recordTearingDown] 同在 [setArcConvert] GL 体复位）：停录后空闲预览
     * 每枚相机帧都会撞一次「补弧链未就绪」的抑制分支（引擎 arcConvert 不随停录自清、编码面
     * 已解绑，直到下一段 start 必发的 setArcConvert 才翻篇），不闩就是 ~30 行/秒的日志刷屏——
     * 首条记日志，其后静默 return。只被 GL 线程读写（degradeArcConvert/setArcConvert 体），
     * 无跨线程访问，不需要 @Volatile。
     */
    private var teardownSuppressLogged = false

    /** 停录冲刷的弃冲标志（录制线程等待超时置位、GL 线程的 flush runnable 读；见 [flushArcPending]） */
    private val flushGiveUp = AtomicBoolean(false)

    // endregion

    private var inputTexture: SurfaceTexture? = null
    private var oesTextureId = 0
    private var hasTextureData = false

    // region program 双槽（docs/plan/25 §3.1 / §3.3）

    /**
     * 一枚已建好的 GL program 及其 uniform/attrib 位置（不可变）。
     *
     * [applied] 是**实际编出来**的键：链接失败走回退阶梯后，可能与请求键不同
     * （例：请求 `(ZEBRA,1,0,0)` 的曲线版编不出来 ⇒ applied `(ZEBRA,0,0,0)`）。
     * 槽里的 `requested` 记请求键、这里记实际键，两者不等就是"走了回退"。
     */
    private class GlProgram(
        val applied: ProgramKey,
        val id: Int,
        val aPosition: Int,
        val aTexCoord: Int,
        val uTexMatrix: Int,
        val uFrame: Int,
        val uStripe: Int,
        val uThreshold: Int,
        val uTile: Int,
        val uTexel: Int,
        val uStrength: Int,
        val uColor: Int,
        val uCurve: Int,
        val uSigmaR: Int,
        val uGlowSrc: Int,
        val uGlowGain: Int,
        val uGlowAdd: Int,
        val uGlowFreq: Int,
        val uGlowBlend: Int,
        val uGlowTint: Int
    )

    /**
     * 一条绘制支路 → 当前挂着的 program（`requested ≠ program.applied` 表示请求键走了回退）。
     *
     * 两个槽一起构成双槽模型：DISPLAY（监看叠加 + 曲线）与 MOVIE（曲线 + 降噪，effect 恒 NONE）。
     * 键相同 ⇒ 两槽指向**同一枚** GlProgram（`displaySlot.program === movieSlot.program`），
     * 不新增编译、不新增显存，成片逐像素不变。
     */
    private class PassSlot {
        var requested: ProgramKey? = null
        var program: GlProgram? = null

        fun clearBinding() {
            requested = null
            program = null
        }
    }

    /** 上屏槽：effect = 用户选的 NONE/ZEBRA/PEAKING（monitor layer 只在这里出现） */
    private var displaySlot = PassSlot()

    /** 成片槽：effect 恒 NONE（[movieProgramKey] 是唯一真源；编码 pass 只认这个槽） */
    private var movieSlot = PassSlot()

    /**
     * 会话级 program 缓存（`LinkedHashMap` 的 access-order，即 LRU）：键 = 四维 [ProgramKey]。
     * 切监看先查这里；被任一槽引用的项永不淘汰；`releaseGl` 统一删。
     */
    private val programCache = LinkedHashMap<ProgramKey, GlProgram>(PROGRAM_CACHE_LIMIT, 0.75f, true)

    /**
     * 编译失败备忘（按四维键）：同一坏键不逐帧重试。
     *
     * 取代单槽时代的三个全局布尔（`blacklistedEffect` / `curveFailed` / `passThroughFailed`）——
     * 那些全局布尔在双槽下会**连坐**：display 的 `ZEBRA+曲线` 链接失败会把成片的曲线一起关掉，
     * 而用户只是开了一下斑马纹。按键记之后，display 的坏效果只影响 display 的请求键。
     */
    private val failedKeys = mutableSetOf<ProgramKey>()

    // endregion

    // region Glow（docs/plan/24 R3 · 25 §11.3/§11.4；scene layer，两槽都带、进成片）

    /**
     * Glow 参数：UI 线程写、GL 线程读 ⇒ `@Volatile`（整体替换，无需锁）。
     * **形状参数只换 uniform**（不重建 program）；只有 `enabled` 进 [ProgramKey] 的 `glow` 一维。
     */
    @Volatile
    private var glowParams = GlowParams.DEFAULT

    /** 预换算的光晕 tint（`glowRgb` 只在参数变化时调，帧路径零分配；白 = 恒等 = 跟随原色） */
    @Volatile
    private var glowTint = glowRgb(GlowParams.DEFAULT.color)

    /**
     * Glow 离屏链（三趟：亮部提取+降采样 → 横向高斯 → 纵向高斯）。
     * **只在 GL 线程创建/调用**（null = `initGl` 还没跑或已 release）；UI 线程只读它的状态快照
     * （[glowStatus] / [isGlowAvailable]）⇒ 引用本身 `@Volatile`。
     */
    @Volatile
    private var glowChain: GlowBlurChain? = null

    /**
     * 1×1 全黑纹理：链未就绪（首帧/刚开启/已停用）时 `uGlowSrc` 绑它，光晕贡献为 0。
     * **不为它切 program**（切 program 会闪帧、会在编码 swap 前编译）。
     * ⚠ **GL 线程独占**（建/用/删都在 `initGl`→`releaseGl` 那条线上），不加 @Volatile。
     */
    private var glowBlackTex = 0

    /**
     * 拷贝趟的恒等 4×2 UV（见 [glowIdentityTexCoords]）；`initGl` 建、`releaseGl` 清。
     * ⚠ **GL 线程独占**（只在链的 draw 与释放路径上读写），不加 @Volatile。
     */
    private var glowIdentityUv: FloatBuffer? = null

    /**
     * 本帧可采样的 Glow 纹理（0 = 未就绪，`drawPass` 退回 1×1 黑纹理）。
     * **`drawPass` 只读它、不碰 [glowChain]**：链只在 `drawFrame` 里跑一次（§11.4 的硬纪律）。
     * ⚠ **GL 线程独占**（`drawGlowPass` 写、`drawPass` 读，同一条线程），不加 @Volatile。
     */
    private var glowFrameTex = 0

    /** 上一次打出的 Glow 分档（档变才打日志，避免每帧刷屏） */
    private var glowLoggedScale = 0

    // endregion

    // region 上屏降档（docs/plan/24 R3 §2.2 · 25 §11.5）

    /**
     * 上屏渲染比例意图（UI 线程写、GL 线程读 ⇒ @Volatile）。
     * **只作用于 [drawWindowPass]**：相机流/编码面/MEND/Glow 链/霜链尺寸一律不碰。
     */
    @Volatile
    private var wantDisplayScale = DisplayScale.ORIG

    /** 上屏离屏 RT + 放大 blit（GL 线程独占；null = `initGl` 还没跑或已 release） */
    private var displayPost: DisplayPostPass? = null

    /** `GL_MAX_TEXTURE_SIZE` 只查一次（上屏 RT 的硬上限保护） */
    private var glMaxTextureSizeCached = 0

    /**
     * 上屏降档回落信号（GL 线程写、UI 线程 collect）：true = 这一档在本机不可用，
     * 已按原片渲染。UI 收到后把档位改回 [DisplayScale.ORIG] 并弹一句提示，然后把标志复位。
     */
    val displayScaleFallback = kotlinx.coroutines.flow.MutableStateFlow(false)

    // endregion

    /**
     * 画面降噪意图（2026-10-26）：GPU 双边档。写在调用线程、生效在 GL 线程；
     * program 选择与曲线/效果同一套机制（改开关即重建两槽的键，见 [syncMovieSlot] / [syncDisplaySlot]）。
     */
    @Volatile
    private var wantDenoise = false

    // ---- 直方图采样（2026-10-27：RGB 曲线框背景，调曲线参考用）----
    // 仅 GPU 档有意义（曲线框本就只在这档可拖）。数据路：drawFrame 尾部把当前帧画进
    // 64×64 迷你 FBO（**上屏槽的 program** ⇒ 曲线输出后的画面，"边调边看分布"），glReadPixels
    // 16KB 后分箱；250ms 一拍，弹窗可见才采（[setHistogramEnabled]）。
    // 读回在 GL 线程会短暂管线停顿——16KB + 4Hz 的量级远低于既有霜板/补弧 pass 的成本，
    // 且只发生在弹窗打开期间（录制中开弹窗调曲线是它的目标场景，掉一拍观测帧可接受）。

    /** 弹窗可见时置 true；false 即停采并清快照（省电 + 不留旧数据） */
    @Volatile
    private var wantHistogram = false

    /** 3×64 计数快照（R/G/B）；整体引用替换发布，读侧免锁 */
    @Volatile
    var histogramSnapshot: IntArray? = null
        private set

    private var histFbo = 0
    private var histTex = 0
    private var histLastSampleMs = 0L

    private val histPixels = java.nio.ByteBuffer.allocateDirect(64 * 64 * 4).order(java.nio.ByteOrder.nativeOrder())

    /** 弹窗开关（CurvePopup visible 时开；写在调用线程，读在 GL 线程 → @Volatile） */
    fun setHistogramEnabled(on: Boolean) {
        if (released.get()) return
        wantHistogram = on
        if (!on) histogramSnapshot = null
    }

    /**
     * drawFrame 尾部调用（GL 线程）：250ms 一拍把当前帧画进迷你 FBO 并读回分箱。
     * 用 [drawPass]（**上屏槽**的 program = 曲线/降噪/监看叠加后的输出）而不是另编 identity——
     * "边调边看结果"口径，见 HistogramMath 头注。开斑马时这里会采到条纹分布，登记为已知限制
     * （docs/plan/25 §十"不改直方图采样口径"）。
     */
    private fun maybeSampleHistogram() {
        // 上屏槽没有 program（两槽皆空的极端态）时早退：留着旧快照比采一张黑帧更有意义
        if (displaySlot.program == null) return
        if (!wantHistogram) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - histLastSampleMs < HISTOGRAM_PERIOD_MS) return
        histLastSampleMs = now
        if (histFbo == 0) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 64, 64, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0
            )
            histTex = tex[0]
            histFbo = fbo[0]
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, histFbo)
        GLES20.glViewport(0, 0, 64, 64)
        drawPass(encoderPass = false)
        histPixels.rewind()
        GLES20.glReadPixels(0, 0, 64, 64, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, histPixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val bins = com.wotagei.cam.core.HistogramMath.binRgba(histPixels.array(), 64, 64)
        histogramSnapshot = bins // 引用替换发布（读侧免锁）
    }

    /** 释放迷你 FBO/纹理（GL 线程；与其它 GL 资源同一收尾点） */
    private fun releaseHistogram() {
        if (histFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(histFbo), 0)
        if (histTex != 0) GLES20.glDeleteTextures(1, intArrayOf(histTex), 0)
        histFbo = 0
        histTex = 0
        histogramSnapshot = null
    }

    /** 双边降噪的亮度差 σ（0..1 归一化亮度域）：越小越保边。夜间暗部噪点取 0.08。
     *  uTexel 复用既有峰值对焦那颗同名 uniform（二者不同时在场：`displayProgramKey` 保证
     *  effect ≠ NONE 时 denoise 恒 0，见 [ProgramKey] 的注释） */
    private var denoiseSigmaR = 0.08f

    private var stripeTextureId = 0
    private var stripeDensity = 0

    /** 曲线查表纹理（256×1 GL_RGB，绑在单元 2）。0 表示曲线关着 */
    private var curveTextureId = 0

    @Volatile
    private var curveBytes: ByteArray? = null

    /** 已经传上去的那份表。`setCurve` 每次都烘出新数组，所以按引用比就能判断"表变了没" */
    private var curveUploaded: ByteArray? = null

    /**
     * 毛玻璃离屏链（OES→2D + 两趟高斯）。只在 GL 线程创建与使用；
     * null = `initGl` 还没跑（或已 release），此时 [isFrostBlurAvailable] 一律 false。
     */
    private var frostChain: FrostBlurChain? = null

    /**
     * 毛玻璃**上屏画板**（#84 步骤 2）：按 [FrostCardTable] 的矩形表把霜贴到窗口面，
     * 只在 [drawWindowPass] 的预览 blit 之后被叫到 —— [drawEncoderPass] 里没有、也不许有它的引用。
     */
    private var frostPlatePass: FrostPlatePass? = null

    /**
     * 结果纹理 + 它对应的那块等比画面矩形，一次原子发布。
     *
     * A2 路线下这份快照有**两个读者**：本层的 [drawWindowPass]（画板，同一线程所以不会撕裂）与
     * [frostGeometry] 那条取证/工装入口（主线程）。UI 侧不再拿它做换算——那枚纹理出不了这条上下文。
     */
    @Volatile
    private var publishedFrost: FrostSnapshot? = null

    /**
     * 矩形表的读方副本（构造期定长分配，运行期只读只覆写 ⇒ 每帧零分配）。
     *
     * 上一版这里还是**两份**（last-good + 试读，靠"撞车就沿用上一帧"来躲撕裂）；
     * [FrostCardTable] 换成双缓冲 + 一次引用交换之后读方不可能再读到半张表，
     * 那套试读/换手的 dance 就成了纯粹的复杂度，已收掉一份。留着副本（而不是直接把发布出去
     * 的那块数组交给画板逐帧读）仍然是有意义的：画板要贴着 GL 调用画满一整帧，
     * 期间主线程还能继续发布新表，拿副本读就没有"读到底时被换掉"的账要算。
     */
    private val frostTableCopy = FloatArray(FrostCardTable.TABLE_FLOATS)

    /** [frostTableCopy] 里压实后的卡片数（读表成功才有值；0 = 这帧一块板都不画） */
    private var frostTableCards = 0

    // region 霜探针诊断位（#84 取证，读方见 ui/FrostProbe.kt）
    //
    // 三个 @Volatile，GL 线程在 [drawFrostPlates] 里写、UI 线程的探针叠层读。
    // 与 [frostTableCards] 的关系：那枚是私有工作值，这三枚是它的**对外镜像**——镜像而已，
    // 不参与任何绘制判断，删探针也不许动绘制路径。口径与 [FrostCardTable.isPlatesDrawn] 同一条：
    // `windowThrottled()` 早退的帧连 [drawFrostPlates] 都不进，这三枚在节流间隔里保持上一帧的值。
    //
    // 三个合成一条因果链，正好是交接里点名的四个数：
    // [frostDiagSnapshotPublished]（快照非空？）→ [frostDiagTableCards]（表里有几块板？）→
    // [frostDiagDrewPlates]（实画出去几块？）。前两个真、第三个 0 ⇒ 断点在
    // [FrostPlatePass.draw] 内部（broken / 视图原点推断失败 / UV 求交全落空），用
    // [frostPlateBrokenForDiagnostics] 与探针侧自算的 frostViewOriginInto 区分。

    /** 最近一个窗口帧：模糊快照是否在手上（= publishedFrost != null） */
    @Volatile
    internal var frostDiagSnapshotPublished: Boolean = false

    /** 最近一个窗口帧：矩形表里压实后的卡片数（读表失败保持 -1，正常路径不会发生） */
    @Volatile
    internal var frostDiagTableCards: Int = -1

    /** 最近一个窗口帧：[FrostPlatePass.draw] 实画出去的板数（0 = 一块都没画） */
    @Volatile
    internal var frostDiagDrewPlates: Int = -1
    // endregion

    private val stMatrix = FloatArray(16)

    /** 方向诊断只打首帧/换姿态一行（见 [consumeFrame]）：false = 还没打过第一行 */
    private var stLoggedOnce = false
    private var stLoggedMirror = false

    /** 上屏 pass 与编码 pass 各一份纹理坐标与脏标记：两者施加的转正角不同，见 [buildTexCoords] */
    private val displayTexCoords = FloatArray(8)
    private val encoderTexCoords = FloatArray(8)
    private var displayGeometryKey = Int.MIN_VALUE
    private var encoderGeometryKey = Int.MIN_VALUE
    private var lastWindowSwapNs = 0L
    private val glContentRect = Rect()

    private val positions = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val positionBuffer: FloatBuffer = nativeFloatBuffer(positions)
    private val displayTexCoordBuffer: FloatBuffer = nativeFloatBuffer(FloatArray(8))
    private val encoderTexCoordBuffer: FloatBuffer = nativeFloatBuffer(FloatArray(8))

    // endregion

    /** 相机目标面就绪回调（在 GL 线程触发，需要主线程请自行 post）。 */
    @Volatile
    var onCameraSurfaceReady: (() -> Unit)? = null

    /** 每帧输出完成后回调（GL 线程）。录制/直方图可用 [frameTimestampNs] 做 A/V 对齐。 */
    @Volatile
    var onFrameDrawn: ((timestampNs: Long) -> Unit)? = null

    init {
        // 构造即起线程：Camera2Engine 可能直接拿本实例当 sink 就调 cameraTargets()
        start()
    }

    /** 幂等启动；[release] 之后调用无效。 */
    fun start() {
        if (released.get() || !started.compareAndSet(false, true)) return
        // 【GL 渲染线程提级 2026-10-09】DISPLAY(-4) → URGENT_DISPLAY(-8)。
        // 理由：这条线程是**成片帧的唯一生产者**（相机帧消费 + 编码面 swap），成片有没有空洞完全由它决定。
        // 成片 PTS 按「名义 24fps 网格 + 每格取 GL 换帧瞬间真实时钟」打点（:1389 + :1988-1995），
        // 本机实际有效帧率 ≈23.75fps < 名义 24 ⇒ 每 ~96 帧必有一格填不上 = 每 4 秒一个空格。
        // 任何一次调度延迟让这一拍错过网格，成片就缺一帧——所以它该高于音频采集档（-16）：
        // producer 的时序抖动会**直接烧成片空档**，而采集侧有 ~1.3s 队列深度兜底，抖动可吸收。
        // URGENT_DISPLAY 是 Android 给渲染线程的正式档位，无需任何权限。
        val t = HandlerThread("WotaGlRender", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        thread = t
        t.start()
        val h = Handler(t.looper)
        handler = h
        // 走 postGl：若在 initGl 执行前被 release()，就不会再建出一套没人回收的资源
        postGl { initGl() }
    }

    /** EGL/GL 是否就绪（未就绪时 [cameraTargets] 返回空表） */
    fun isReady(): Boolean = glReady && !released.get()

    // region PreviewSink

    /**
     * Camera2 会话的目标面。返回空表表示 GL 线程还没建出 `SurfaceTexture`（毫秒级窗口），
     * 此时应等 [onCameraSurfaceReady] 回调后再建会话，不要轮询重试。
     */
    override fun cameraTargets(): List<Surface> =
        if (released.get()) emptyList() else listOfNotNull(cameraTargetSurface)

    /**
     * 契约语义 = **相机帧尺寸**（决定 SurfaceTexture 默认缓冲大小与等比适配的源比例），不是视图尺寸；
     * 视图尺寸由 [DisplaySurfaceReceiver.onDisplaySurfaceChanged] 提供。
     *
     * 未调用时的退化行为：不额外做等比居中（等比区=整个窗口），画面比例完全由承载视图自身的布局比例决定
     * ——06 文档 §2 要求预览区按 16:9/4:3 布局，因此该退化是可接受的，但仍应在会话建好后调用一次。
     */
    override fun onSizeChanged(w: Int, h: Int) {
        if (released.get()) return
        wantFrameWidth = w
        wantFrameHeight = h
        postGl {
            inputTexture?.setDefaultBufferSize(w, h)
            updateContentRect()
            dirty = true
            scheduleDraw()
        }
    }

    /**
     * @param sensorOrientation 来自 CameraCharacteristics；预览与录像共用同一旋转，保证方向一致
     * @param mirrored 前置摄像头取 true（镜像只作用在屏幕空间，上屏与录出的方向因此一致）
     */
    override fun setOrientation(sensorOrientation: Int, mirrored: Boolean) {
        if (released.get()) return
        orientation.setSensorOrientation(sensorOrientation, mirrored)
        postGl {
            updateContentRect()
            dirty = true
            scheduleDraw()
        }
    }

    /** 显示旋转角（0/90/180/270），由 UI 写入；与 [setOrientation] 各写各的通道 */
    override fun setDisplayDegrees(degrees: Int) {
        if (released.get()) return
        orientation.setDisplayDegrees(degrees)
        postGl {
            updateContentRect()
            dirty = true
            scheduleDraw()
        }
    }

    // endregion

    // region DisplaySurfaceReceiver

    override fun onDisplaySurfaceChanged(surface: Surface?, widthPx: Int, heightPx: Int) {
        if (released.get()) return
        postGl { replaceWindowSurface(surface, widthPx, heightPx) }
    }

    // endregion

    // region 录制侧接口

    /**
     * 绑定编码器输入面（`Recorder.surface`）。尺寸必须与编码器设定 **逐像素相同**，
     * 否则 MediaCodec 报尺寸不匹配（03 文档 §3.8）；尺寸变化即重建其 EGLSurface。
     *
     * @param surface null 表示解除绑定（只上屏）
     * @param frameRate 目标帧率（Int；`<=0` 视为不可用，压根不打戳）
     * @param ntsc 该档是否是真 NTSC 非整数帧率（23.976/29.97/59.94，即相机只能给※近似档）。
     *   true 时网格步长改查 [ntscGridStepNs]；默认 false ⇒ 整数档调用点零改动。
     */
    fun setOutputSurface(surface: Surface?, widthPx: Int, heightPx: Int, frameRate: Int = 0, ntsc: Boolean = false) {
        if (released.get()) return
        encoderFps = if (surface == null) 0 else frameRate.coerceAtLeast(0)
        // 解面必须连 NTSC 一起清：残留 true 会让下一段的整数档落成非整数网格
        encoderNtsc = if (surface == null) false else ntsc
        postGl { replaceEncoderSurface(surface, widthPx, heightPx) }
    }

    /** 编码器面是否已绑定（录制侧可据此决定何时 start） */
    fun isOutputSurfaceBound(): Boolean = encoderEglSurface != null

    /**
     * 传入的 native 面是否就是**当前绑定**的编码面。换段重挂路径专用：轮转时旧绑定还没解，
     * [isOutputSurfaceBound] 首查恒真、await 零等待——必须等「新面已绑定」才行。
     */
    fun isEncoderSurfaceNative(s: Surface?): Boolean = encoderNative === s

    /** 编码器面尺寸（px），未绑定为 0x0 */
    fun outputSurfaceSize(): Pair<Int, Int> = encoderWidth to encoderHeight

    /**
     * 配置录制期转换（强制 24/25fps 无精确档时的抽帧/补弧）。[mode] = null 关闭（直通）。
     * 必须在 [setOutputSurface] **之前**调：节奏锚点随编码面重挂一起复位。
     * 体内只做投递，GL 线程内外都能调——录制中段补弧降级（[degradeArcConvert]）也从 GL
     * 线程进这里复用同一份重建（postGl 排到本线程随后执行，context 常驻 current，安全）。
     */
    fun setArcConvert(mode: ArcConvertMode?, dstFps: Int) {
        if (released.get()) return
        postGl {
            // 新会话重挂必经此路（录制 start「转换配置每段必发」）：上一段的停止期抑制到此解除
            recordTearingDown = false
            teardownSuppressLogged = false
            arcConvert = mode?.takeIf { dstFps > 0 }
            // 档位与模式成对清零：mode 关掉后 arcDstFps 残留，编码面重挂处会拿旧值重建状态机
            arcDstFps = if (arcConvert != null) dstFps else 0
            // 档位必须在这里接进规则（ArcKeepRule.slotNs 是构造期 val，只 reset 不换实例）：
            // slotNs 恒 0 → onFrame 恒真 → 抽帧整条失效，而 PTS/位次账按 dstFps 照跑，
            // 成片时长被压缩、音画错位。自 7812e16 功能落地起就没接过线，2026-10-04 审计修复。
            // 相位容差（2026-10-17）按槽宽的运行时比例显式传入，不在判定里写毫秒常量。
            val arcSlotNs = if (arcConvert != null) 1_000_000_000L / dstFps else 0L
            arcRule = ArcKeepRule(
                slotNs = arcSlotNs,
                toleranceNs = arcKeepToleranceNs(arcSlotNs)
            )
            arcFlow = ArcRepairFlow(dstFps)
            synchronized(arcDropsLock) { arcDrops.clear() }
            // 每段录制启动读一次参数总线发布的死区快照：持久化值在进录制页时已由 restore 写回。
            // 运行期 setArcMergeDeadband 只覆盖本段后续的 uniform，下一段重新回到总线口径。
            wantArcMergeDeadband = ArcMergeDeadband.deadband
            arcMendPass?.release()
            arcMendPass = if (arcConvert == ArcConvertMode.MEND && encoderWidth > 0) {
                ArcMendPass().also {
                    it.setDeadband(wantArcMergeDeadband)
                    it.ensure(encoderWidth, encoderHeight)
                }
            } else {
                null
            }
            // 源帧率实测账随转换开启复位（O(1) 内存的首尾时间戳法，见 noteArcFrame）
            arcSrcFirstTsNs = Long.MIN_VALUE
            arcSrcLastTsNs = Long.MIN_VALUE
            arcSrcFrames = 0
            Log.i(TAG_GL, "arcConvert=$mode dstFps=$dstFps")
        }
    }

    // ---- 录制期转换的源帧率实测（O(1) 内存）：保留帧/丢弃帧的到达时间戳都在这记账，
    // 首尾时间戳 + 帧数折算平均帧率。中位数法更抗畸变但需要 O(n) 样本（录制期帧数可达数十万），
    // 平均值对"容器假 fps"的量级判断足够——成片名前缀只要量级对（30 vs 24）就达到可辨目的。
    // GL 线程写、UI 线程读：三枚都 @Volatile（long 读写原子性）
    @Volatile private var arcSrcFirstTsNs = Long.MIN_VALUE
    @Volatile private var arcSrcLastTsNs = Long.MIN_VALUE
    @Volatile private var arcSrcFrames = 0

    /** 抽帧规则判定处调用（转换开启时每帧一次）；只记账不分配 */
    private fun noteArcFrame(tsNs: Long) {
        if (arcSrcFirstTsNs == Long.MIN_VALUE) arcSrcFirstTsNs = tsNs
        arcSrcLastTsNs = tsNs
        arcSrcFrames++
    }

    /**
     * 录制收尾取实测源帧率（整数，容器标称会被强制档标假——这就是前缀要实测的原因）。
     * 样本不足（<2 帧或时长 <1s）返回 0，调用方应退化为不带前缀。
     */
    fun measuredArcSrcFps(): Int {
        val first = arcSrcFirstTsNs
        val last = arcSrcLastTsNs
        val frames = arcSrcFrames
        val spanNs = last - first
        if (frames < 2 || spanNs <= 0L) return 0
        // 四舍五入，与离线修复路探测核的整数视图（ArcRateProbe.measuredSrcFps(...).roundedInt()；
        // M6-01 前的旧名 measuredFps）同一取整口径：29.97fps 源在录制路才不会被打成 29，
        // 两条路对同一传感器给出的前缀一致
        return (((frames - 1L) * 1_000_000_000L + spanNs / 2) / spanNs).toInt().coerceIn(1, 480)
    }

    /** 取走被抽帧位次账（录制收尾写 sidecar 用；取走即清空） */
    fun drainArcDrops(): List<Pair<Int, Int>> = synchronized(arcDropsLock) {
        arcDrops.toList().also { arcDrops.clear() }
    }

    /**
     * 停录前冲刷 MEND 滞留帧：状态机会滞留一枚保留帧等"下一保留帧"来决定并弧，
     * 而录制停止没有 EOS 通知——不冲刷它就必丢一枚输出帧（≤1/24s）。同步等 GL 完成
     * （编码器此时还活着），必须在 `Recorder.stop()` **之前**调。返回冲刷的帧数（0/1）。
     *
     * 屏障语义（r09 竞态修复）：latch 在 runnable 的 finally 里 countDown，所以等待返回 =
     * runnable **真正执行完**。GL 线程被长任务占用时 runnable 只会排队晚跑，必须等满
     * [FLUSH_BARRIER_TIMEOUT_MS] 才允许放弃——放弃即置 [flushGiveUp] 撤销本次冲刷，晚到的
     * runnable 见标志整段不跑（滞留帧连同状态机留给下一段 [setArcConvert] 重建），绝不在
     * 已被 rec.stop 拆死的编码面（codec.stop 即输入面失效）上画/swap 撞 EGL_BAD_ALLOC。
     */
    fun flushArcPending(): Int {
        if (released.get() || arcConvert != ArcConvertMode.MEND) return 0
        val h = handler ?: return 0
        val latch = CountDownLatch(1)
        flushGiveUp.set(false)
        var flushed = 0
        h.post {
            try {
                if (flushGiveUp.get()) return@post   // 等待方已超时撤销，编码器此刻可能已拆：什么都不许碰
                if (!released.get() && arcConvert == ArcConvertMode.MEND && arcFlow.hasPending) {
                    val encoder = encoderEglSurface
                    val mend = arcMendPass
                    if (encoder != null && mend != null && mend.ready && makeCurrent(encoder)) {
                        // EOS 指令序的折叠步（onSourceEos 契约 = fold?→emit）：滞留帧之后还进过
                        // 被抽帧（hasAcc）必须先并进 pending 再发，否则停录前最后积累的弧光
                        // 整段丢在 acc 面里；fold 失败只降级成无补弧发出，不拦 emit（少补弧好过整帧丢失）
                        if (arcFlow.hasAcc && !mend.foldAccIntoPending()) {
                            Log.w(TAG_GL, "flush 折叠 acc 失败，退化为无补弧发出")
                        }
                        if (mend.drawPending()) {
                            stampEncoderPresentation(encoder)
                            if (swap(encoder)) {
                                val idx = (encoderFrameIndex - 1).coerceAtLeast(0)
                                synchronized(arcDropsLock) {
                                    arcDrops += idx to arcRule.takeDrops()
                                }
                                flushed = 1
                            }
                        }
                    }
                    arcFlow.onSourceEos()   // 滞留已出，状态机归零；此后到 stop 的来帧自然弃置
                }
            } finally {
                latch.countDown()
            }
        }
        val deadline = System.nanoTime() + FLUSH_BARRIER_TIMEOUT_MS * 1_000_000L
        val landed = runCatching { latch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) }
            .onFailure { Log.w(TAG_GL, "flushArcPending 等待被打断：${it.message}") }
            .getOrDefault(false)
        if (!landed) {
            flushGiveUp.set(true)
            Log.w(
                TAG_GL,
                "flushArcPending ${FLUSH_BARRIER_TIMEOUT_MS}ms 未落地（GL 线程被长任务占用），" +
                    "撤销滞留帧冲刷，末帧补弧放弃"
            )
        }
        return flushed
    }

    /**
     * 标记进入停止序列：**必须在冲刷/拆编码器任何动作之前**由录制线程调（见
     * [recordTearingDown]）。下一段录制 start 必发 [setArcConvert]，那里复位。
     */
    fun markRecordTearingDown() {
        recordTearingDown = true
    }

    // endregion

    // region 特效接口（单效果槽）

    /** NONE / ZEBRA / PEAKING 互斥；切换先删旧 program 再建新的 */
    fun setEffect(effect: FrameEffect) {
        if (released.get()) return
        wantEffect = effect
        requestRender()
    }

    /**
     * @param threshold 0..1 归一化亮度阈值（UI 80–100 ↔ 0.6–1.0）
     * @param density 1 稀疏 / 2 适中 / 3 稠密 → 平铺倍数 4/8/16
     */
    fun setZebraParameters(threshold: Float, density: Int) {
        if (released.get()) return
        wantZebraThreshold = threshold.coerceIn(0f, 1f)
        wantZebraDensity = ZebraPattern.sanitize(density)
        requestRender()
    }

    /**
     * @param strength 0..1 边缘强度阈值，越小越灵敏
     * @param color 描边色档位
     */
    fun setPeakingParameters(strength: Float, color: PeakingColor) {
        if (released.get()) return
        wantPeakingStrength = strength.coerceIn(0f, 1f)
        wantPeakingColor = color
        requestRender()
    }

    /**
     * 画面降噪（2026-10-26）：[enabled] = 是否启用 GPU 双边档。改开关即重建 program
     * （与 [setCurve] 同一条路径：编译落在下一帧，且不阻塞编码面 swap）。
     *
     * DIRECT 渲染模式下不应调用本入口——相机帧不进 GL 管线，双边无从作用；
     * 降噪档的实际生效判定在 [com.wotagei.cam.core.effectiveVideoDenoise]，UI 与引擎共用同一支。
     */
    fun setVideoDenoise(enabled: Boolean) {
        if (released.get()) return
        if (wantDenoise == enabled) return
        wantDenoise = enabled
        postGl {
            // 显式改降噪档 = 给所有含降噪维度的坏键一次重试（只清这些，别把驱动级坏键又试一遍）
            failedKeys.removeAll { it.denoise != DENOISE_LEVEL_OFF }
            retrySlots()
            // 成片优先：movie 槽的键先跟上（display 的同步在 drawFrame 的编码 pass 之后）
            syncMovieSlot()
            dirty = true
            scheduleDraw()
        }
    }

    /** 当前 program 是否带双边降噪（UI/守卫查询用；与 [isReady] 同一类只读快照） */
    fun isVideoDenoiseOn(): Boolean = wantDenoise && !released.get()

    /**
     * 补弧合并差分死区 D（0..32，8bit luma 级）：`Δluma > D` 时整颗像素取被抽帧，否则保持保留帧。
     * 只动补弧合并的 uniform，不碰打戳/帧率/抽帧节奏。显式改档只在本段录制有效；
     * 下一段 `setArcConvert` 会重新读参数总线发布的持久化口径（真源仍在 [WotaParams]）。
     */
    fun setArcMergeDeadband(deadband: Int) {
        if (released.get()) return
        val clamped = ArcMergeDeadband.clamp(deadband)
        wantArcMergeDeadband = clamped
        postGl {
            arcMendPass?.setDeadband(clamped)
            Log.i(TAG_GL, "arcMergeDeadband=$clamped")
        }
    }

    /** 最近一次设定的曲线/色调（[setWbGains] 换增益时要拿它们重烘同一张表） */
    private var lastCurveStack: CurveStack? = null
    /** 画面算法白平衡的相对增益（null = 不参与），与 [lastCurveStack]/[lastCurveTone] 同烘一张 LUT */
    private var wbLutGains: FloatArray? = null
    private var lastCurveTone: com.wotagei.cam.core.ToneParams = com.wotagei.cam.core.ToneParams.IDENTITY

    /**
     * 画面算法白平衡的相对增益（[com.wotagei.cam.core.WbApply.gains]，已按最大通道归一）：
     * 不支持 HAL 增益的机型上，手动色温/色调由这组数乘进场景层 LUT 的最后一段。
     * null / 全 1 = 不参与（自动白平衡、预设档、以及能走 HAL 的机型都走这条）。
     *
     * **独立入口而不是并入 [setCurve] 的键**：色温滑杆拖动时每 tick 都在变，并进长键会把
     * effect/zebra/peaking/curve/降噪 整条链重跑一遍（同 Glow 的分键理由）。
     */
    fun setWbGains(gains: FloatArray?) {
        if (released.get()) return
        wbLutGains = gains?.takeUnless { com.wotagei.cam.core.isIdentityGains(it) }
        bakeCurveLut()
        postGl {
            failedKeys.removeAll { it.curve }
            retrySlots()
            syncMovieSlot()
            dirty = true
            scheduleDraw()
        }
    }

    /** 曲线/色调/白平衡三者共用一张 LUT：任一非恒等就得烘，只看曲线一侧会静默丢掉另两项 */
    private fun bakeCurveLut() {
        val stack = lastCurveStack
        val tone = lastCurveTone
        val wb = wbLutGains
        curveBytes = stack
            ?.takeIf { !it.isPassthrough || !tone.isIdentity || wb != null }
            ?.bakeBytes(tone, wbGains = wb)
    }

    /**
     * 应用 RGB 曲线 + 色调（29 §4.3，2026-10-28）；两者都恒等即关闭（跳过 LUT 的唯一合法判据：
     * `stack.isPassthrough && tone.isIdentity`——**不能**只用曲线一侧决定，否则色调会被静默丢掉）。
     *
     * 烘焙在调用线程做（`TABLE_SIZE*3` 次插值，微秒级），GL 线程只拿字节表，
     * 避免拖动控制点时把重计算压到渲染线程上掉帧。
     */
    fun setCurve(
        stack: CurveStack?,
        tone: com.wotagei.cam.core.ToneParams = com.wotagei.cam.core.ToneParams.IDENTITY
    ) {
        if (released.get()) return
        lastCurveStack = stack
        lastCurveTone = tone
        bakeCurveLut()
        postGl {
            // 每次显式改曲线都重新给一次编译机会：可能是上一版表触发的偶发失败。
            // 只清含 curve 维度的备忘——不含曲线维度的坏键（如驱动不认 PEAKING）与本次无关。
            failedKeys.removeAll { it.curve }
            retrySlots()
            syncMovieSlot()
            dirty = true
            scheduleDraw()
        }
    }

    // endregion

    // region 毛玻璃背板接口（#84，见 FrostBlurProvider 的语义边界）

    /**
     * A/B 开关（默认关）。**不**触发 `requestRender()`：状态刷新会连带重画一次编码 pass，
     * 那就是往录像里塞一帧重复帧——开关只该影响上屏方向（docs/plan/14 §二 定版口径）。
     * 于是打开后模糊在下一个相机帧产出（30–60fps 下 ≤33ms，肉眼与"点了没反应"分不开的情况不存在）；
     * 关掉则是立即生效（[isFrostBlurAvailable] 当场变 false，不等下一帧），画板也当场撤报。
     *
     * ⚠ 生产路径上这一位不由 UI 直接调本函数携带：`ui/` 拿不到引擎实例，开关意图写在
     * [FrostCardTable] 的表头里，由 GL 线程每帧经 [adoptFrostIntentFromTable] 搬进来。
     * 本函数因此是**同一枚门的直注入口**（工装/取证用），直接注进来的意图下一帧会被表位覆盖回去。
     */
    override fun setFrostBlurEnabled(enabled: Boolean) {
        if (released.get()) return
        if (wantFrostBlur == enabled) return
        wantFrostBlur = enabled
        postGl {
            if (enabled) {
                // 给停用过的链与画板各一次重试机会（显式人为动作才重置，不是每帧重试）
                frostChain?.resetAfterBreak()
                frostChain?.prepare()
                frostPlatePass?.resetAfterBreak()
                frostPlatePass?.prepare()
            } else {
                // 关就是"现在就没有模糊"，快照当场撤掉；资源保留不删，再开不重建也不闪
                publishedFrost = null
                FrostCardTable.reportPlatesDrawn(false)
            }
        }
    }

    /**
     * GL 线程：把 UI 写进矩形表表头的开关意图搬进 [wantFrostBlur]（#84 步骤 2 的唯一一条生产入口）。
     *
     * 只在真变化时做事，所以每帧的成本是一次读表位（[FrostCardTable.hasUiEnabled]：一把无竞争的监视器锁 +
     * 一次表头位读，与 [FrostCardTable.tryReadInto] 同一把锁、同一份已发布的表）加一次比较；
     * `prepared` 那两趟编译也因此只发生在翻转那一帧
     * （与 [setFrostBlurEnabled] 的退避同一条理由：编译落在热点帧上就是明晃晃掉帧）。
     * 关掉时顺手把画板回报撤成 false，UI 下一帧就把底板的纯色 fill 画回来。
     */
    private fun adoptFrostIntentFromTable() {
        val want = FrostCardTable.hasUiEnabled()
        if (want == wantFrostBlur) return
        wantFrostBlur = want
        if (want) {
            frostChain?.resetAfterBreak()
            frostChain?.prepare()
            frostPlatePass?.resetAfterBreak()
            frostPlatePass?.prepare()
        } else {
            publishedFrost = null
            FrostCardTable.reportPlatesDrawn(false)
        }
    }

    override fun isFrostBlurEnabled(): Boolean = wantFrostBlur && !released.get()

    override fun isFrostBlurAvailable(): Boolean =
        wantFrostBlur && !released.get() && publishedFrost != null

    // 霜探针（#84）：两枚 pass 有没有因连续错误进入停用态。只读不写，false 也可能是"还没建"。
    // ⚠ 故意写成**块体**：表达式体（`= expr`）不被源码守卫的函数体定位识别，
    // 体内的 frostPlatePass/frostChain 会被误判成"第二枚属性声明"把 FrostEncoderGuardTest 打红
    internal fun frostChainBrokenForDiagnostics(): Boolean {
        return frostChain?.isBrokenForDiagnostics() ?: false
    }

    internal fun frostPlateBrokenForDiagnostics(): Boolean {
        return frostPlatePass?.isBrokenForDiagnostics() ?: false
    }

    override fun frostRenderTarget(): FrostRenderTarget? {
        if (!isFrostBlurAvailable()) return null
        val s = publishedFrost ?: return null
        return FrostRenderTarget(s.textureId, s.texWidthPx, s.texHeightPx)
    }

    /**
     * 卡片矩形 → UV 所需几何（一次原子读，RT 尺寸与等比画面矩形保证同帧）。
     *
     * @param viewOriginXInWindowPx 承载视图在 app 窗口里的原点 X（px）；视图本来就铺满窗口时传 0f
     * @param viewOriginYInWindowPx 同上，Y
     */
    override fun frostGeometry(
        viewOriginXInWindowPx: Float,
        viewOriginYInWindowPx: Float
    ): FrostGeometry? {
        if (!isFrostBlurAvailable()) return null
        val s = publishedFrost ?: return null
        return FrostGeometry(
            contentRectInViewPx = s.contentRectInViewPx,
            viewOriginXInWindowPx = viewOriginXInWindowPx,
            viewOriginYInWindowPx = viewOriginYInWindowPx,
            texWidthPx = s.texWidthPx,
            texHeightPx = s.texHeightPx
        )
    }

    // endregion

    // region 帧驱动与查询

    /** 相机帧到达（构造 SurfaceTexture 时已把回调 handler 设为 GL 线程） */
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (released.get()) return
        // 帧账：回调数与 newFrames 同点自增（newFrames 语义不变；两枚都在 GL 线程写）
        FrameLedger.onGlCallback()
        newFrames++
        scheduleDraw()
    }

    /** 主动请求重画一帧（状态变化用；相机帧由 [onFrameAvailable] 驱动，禁止定时器空转） */
    fun requestRender() {
        if (released.get()) return
        dirty = true
        scheduleDraw()
    }

    /** 最近一帧的相机时间戳（ns，`SurfaceTexture.getTimestamp()`），未出帧为 0 */
    fun frameTimestampNs(): Long = lastDrawnTimestampNs

    /**
     * 画面在窗口中的等比居中区（窗口坐标，原点左上）。
     *
     * ⚠ 「窗口」在这里指**这条 EGL 窗口面 = 承载视图自己**（分母是
     * [DisplaySurfaceReceiver.onDisplaySurfaceChanged] 回报的视图宽高），**不是 app 整屏**。
     * HUD 卡片实测矩形走的是整屏坐标（`positionInWindow`），两者差一枚视图原点——
     * 拿它直接配卡片矩形必错位。#84 步骤 2 之后这条换算发生在 GL 线程内部
     * （[drawFrostPlates] → [FrostPlatePass]，原点由 [frostViewOriginInto] 现算，
     * 卡片矩形经 [FrostCardTable] 交过来），所以 UI 侧不必再换算；
     * [frostGeometry] + [frostUvOfCard] 那一对入口留给取证/工装（手算单测都在 `FrostBlur.kt` 那一层）。
     */
    fun contentRect(): Rect = Rect(publishedContentRect)

    /** 窗口承载尺寸（px）——同上，这是承载视图的尺寸，不是整屏 */
    fun windowSize(): Pair<Int, Int> = windowWidth to windowHeight

    // endregion

    /**
     * 幂等释放：销毁两个 EGLSurface、program、纹理、EGL 上下文并退出 GL 线程。
     * 调用后本实例不可复用。
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        newFrames = 0
        dirty = false
        val h = handler
        val t = thread
        handler = null
        if (h == null) {
            t?.quitSafely()
            return
        }
        // 释放绕过 postGl（它会被 released 拦掉），且必须在 GL 线程做才能删掉 current 上下文的资源
        h.post {
            releaseGl()
            t?.quitSafely()
        }
    }

    // region 内部实现：线程投递

    private inline fun postGl(crossinline block: () -> Unit) {
        if (released.get()) return
        val h = handler ?: return
        h.post { if (!released.get()) block() }
    }

    /** WHEN_DIRTY 调度：多次请求合并成一次绘制 */
    private fun scheduleDraw() {
        if (released.get()) return
        val h = handler ?: return
        if (drawScheduled.compareAndSet(false, true)) {
            h.post {
                drawScheduled.set(false)
                drawFrame()
            }
        }
    }

    // endregion

    // region 内部实现：EGL 初始化与面管理

    private fun initGl() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG_GL, "eglGetDisplay 失败 err=0x${eglErrorHex()}")
            return
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.w(TAG_GL, "eglInitialize 失败 err=0x${eglErrorHex()}")
            return
        }
        eglDisplay = display

        val config = chooseConfig(display)
        if (config == null) {
            EGL14.eglTerminate(display)
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return
        }
        eglConfig = config

        val context = createContext(display, config)
        if (context == null) {
            EGL14.eglTerminate(display)
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglConfig = null
            return
        }
        eglContext = context

        val pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (pbuffer == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG_GL, "pbuffer 创建失败 err=0x${eglErrorHex()}")
            teardownEgl()
            return
        }
        pbufferSurface = pbuffer
        if (!makeCurrent(pbuffer)) {
            teardownEgl()
            return
        }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        // 带纹理名的构造会把纹理 attach 到 current 上下文，所以必须在 GL 线程、makeCurrent 之后创建
        val st = SurfaceTexture(oesTextureId)
        if (wantFrameWidth > 0 && wantFrameHeight > 0) {
            st.setDefaultBufferSize(wantFrameWidth, wantFrameHeight)
        }
        st.setOnFrameAvailableListener(this, handler)
        inputTexture = st
        cameraTargetSurface = Surface(st)

        // 链本体只存对象、不碰 GL（program/RT 都按需在建好上下文之后才编/建），所以放这里安全
        frostChain = FrostBlurChain(::link, ::drainGlError, positionBuffer)
        // 上屏画板同理：只存对象，program 等到开关真打开时才编（见 setFrostBlurEnabled）
        frostPlatePass = FrostPlatePass(::link, ::drainGlError, positionBuffer)
        // 上屏降档（24 批 2）：只存对象；program/RT 等真的切到 >ORIG 时才建（ORIG 不占资源）
        displayPost = DisplayPostPass(::link, positionBuffer)
        // Glow 离屏链（24 批 1）：与霜链同构、实例独立；program 等 `enabled` 翻真时再 prepare
        glowChain = GlowBlurChain(::link, ::drainGlError, positionBuffer)
        glowIdentityUv = glowIdentityTexCoords()
        glowBlackTex = createGlowBlackTexture()

        glReady = true
        // 首帧之前先把上屏槽建好（此时编码面尚未绑定 ⇒ movie 槽不建），
        // 让第一次窗口 pass 不再等一次 program 链接
        syncDisplaySlot()
        updateContentRect()
        checkGlError("initGl")
        Log.i(TAG_GL, "EGL 就绪 version=$eglVersion oesTex=$oesTextureId")
        onCameraSurfaceReady?.invoke()
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or EGL_OPENGL_ES3_BIT_KHR,
            // 同时要 WINDOW 与 PBUFFER：前者给两路输出面，后者给无窗口态
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            Log.w(TAG_GL, "eglChooseConfig 无可用配置 err=0x${eglErrorHex()}")
            return null
        }
        return configs[0]
    }

    private fun createContext(display: EGLDisplay, config: EGLConfig): EGLContext? {
        val es3 = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        if (es3 != EGL14.EGL_NO_CONTEXT) {
            eglVersion = 3
            return es3
        }
        // 着色器按 GLSL ES 1.00 写，退回 ES2 仍能跑，不至于整块预览黑掉
        Log.w(TAG_GL, "ES3 上下文创建失败 err=0x${eglErrorHex()}，退回 ES2")
        val es2 = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (es2 == EGL14.EGL_NO_CONTEXT) {
            Log.w(TAG_GL, "ES2 上下文创建失败 err=0x${eglErrorHex()}")
            return null
        }
        eglVersion = 2
        return es2
    }

    private fun makeCurrent(surface: EGLSurface?): Boolean {
        val s = surface ?: return false
        if (!EGL14.eglMakeCurrent(eglDisplay, s, s, eglContext)) {
            Log.w(TAG_GL, "eglMakeCurrent 失败 err=0x${eglErrorHex()}")
            return false
        }
        return true
    }

    private fun swap(surface: EGLSurface): Boolean {
        // 只给编码面记账：窗口/pbuffer 的 swap 不属于"提交编码器"这一级
        val encoder = surface === encoderEglSurface
        if (EGL14.eglSwapBuffers(eglDisplay, surface)) {
            if (encoder) FrameLedger.onEncoderSwap(true)
            return true
        }
        if (encoder) FrameLedger.onEncoderSwap(false)
        Log.w(TAG_GL, "eglSwapBuffers 失败 err=0x${eglErrorHex()}")
        return false
    }

    /** 销毁前先解除 current，否则 eglDestroySurface 返回 EGL_BAD_ACCESS */
    private fun destroyEglSurface(surface: EGLSurface?) {
        if (surface == null) return
        pbufferSurface?.let { makeCurrent(it) }
        if (!EGL14.eglDestroySurface(eglDisplay, surface)) {
            Log.w(TAG_GL, "eglDestroySurface 失败 err=0x${eglErrorHex()}")
        }
    }

    private fun replaceWindowSurface(surface: Surface?, widthPx: Int, heightPx: Int) {
        windowWidth = widthPx
        windowHeight = heightPx
        if (surface != null && surface === windowNative) {
            // 同一窗口的尺寸变化：EGL 窗口面继续有效，只重算 viewport（承载侧也复用同一 Surface）
            updateContentRect()
            dirty = true
            scheduleDraw()
            return
        }
        destroyEglSurface(windowEglSurface)
        windowEglSurface = null
        windowNative = null
        lastWindowSwapNs = 0L
        updateContentRect()
        dirty = true
        if (surface == null) {
            scheduleDraw()
            return
        }
        val config = eglConfig
        if (config == null) {
            Log.w(TAG_GL, "EGL 未就绪，无法创建窗口面")
            return
        }
        if (!surface.isValid) {
            Log.w(TAG_GL, "窗口 Surface 已失效，跳过上屏 pass")
            scheduleDraw()
            return
        }
        val es = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (es == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG_GL, "窗口 EGLSurface 创建失败 err=0x${eglErrorHex()}")
            return
        }
        windowEglSurface = es
        windowNative = surface
        scheduleDraw()
    }

    private fun replaceEncoderSurface(surface: Surface?, widthPx: Int, heightPx: Int) {
        if (surface != null && surface === encoderNative && widthPx == encoderWidth && heightPx == encoderHeight) {
            return
        }
        // 打戳观测账的段尾（帧龄均值/峰值、空格数与最大跨距、写戳失败数）：放在销毁旧面**之前**，
        // 换面与解面两条出口一次收干净——旧面在此之后就不存在了，而新面若绑定失败会就地 return，
        // 放在后面会让上一段的账一直悬着，混进下一段成功的段收尾行里。
        drainStampObs(if (surface == null) "解面" else "换面")
        destroyEglSurface(encoderEglSurface)
        encoderEglSurface = null
        encoderNative = null
        encoderWidth = 0
        encoderHeight = 0
        if (surface == null) {
            Log.i(TAG_GL, "编码器面已解除")
            // 成片槽的绑定只清引用（program 可能正被 display 共用 / 在会话缓存里等下一次起录）：
            // 下一段起录按新尺寸/新帧率重新预热（§3.5）
            movieSlot.clearBinding()
            // 解绑即清转换账（节奏/位次）：半会话状态不许漏进下一次录制
            // （今天的 stop 时序先 drain 再解绑，这里清是账本本位，不依赖调用顺序）
            arcRule.reset()
            if (arcDstFps > 0) arcFlow = ArcRepairFlow(arcDstFps)
            synchronized(arcDropsLock) { arcDrops.clear() }
            // 补弧离屏资源一起放：它持有 5 张编码尺寸 RGBA 纹理（1080p ≈ 41MiB / 4K ≈ 166MiB），
            // 不放就一路滞留到下次 setArcConvert 或退出录制页——而空闲预览期本来就用不到它。
            // setArcConvert 会在真正需要时按 encoderWidth/Height 重建，释放不损失任何能力。
            arcMendPass?.release()
            arcMendPass = null
            return
        }
        val config = eglConfig
        if (config == null) {
            Log.w(TAG_GL, "EGL 未就绪，无法绑定编码器面")
            return
        }
        if (!surface.isValid) {
            Log.w(TAG_GL, "编码器 Surface 已失效")
            return
        }
        val es = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (es == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG_GL, "编码器 EGLSurface 创建失败 err=0x${eglErrorHex()}")
            return
        }
        encoderEglSurface = es
        encoderNative = surface
        encoderWidth = widthPx
        encoderHeight = heightPx
        encoderFrameIndex = 0
        encoderBaseNs = System.nanoTime()
        encoderLastPtsNs = 0L
        // 转换会话：新文件新节奏（分段/重挂都从这里走），位次账随文件清零
        arcRule.reset()
        if (arcDstFps > 0) arcFlow = ArcRepairFlow(arcDstFps)
        synchronized(arcDropsLock) { arcDrops.clear() }
        if (arcConvert == ArcConvertMode.MEND && widthPx > 0 && heightPx > 0) {
            // 正常起录顺序是 setArcConvert → setOutputSurface：setArcConvert 执行时编码面尺寸
            // 还是 0，ArcMendPass 不会被创建，真正建在下面这条惰性路径上。死区必须在这里补上，
            // 否则它取构造默认值 12——运行期把 D 改成非 12 时，在线 MEND 忽略、离线修复生效，
            // "三路同规则"当场破产（ArcMendDeadbandWiringGuardTest 钉着）。
            if (arcMendPass == null) {
                arcMendPass = ArcMendPass().also { it.setDeadband(wantArcMergeDeadband) }
            }
            arcMendPass?.ensure(widthPx, heightPx)
        }
        // 预热（docs/plan/25 §3.5）：movie 键依赖编码面尺寸/帧率（[denoiseLevelNow] 可能刚从完整核
        // 切到轻量核），新尺寸落定后直接把 program 编好/绑好。这样两笔编译都发生在相机帧到达之前，
        // 不占第一枚编码帧的 swap 时间窗（成片 PTS 网格对丢格零容忍）。
        // 建 program 需要 current 上下文，而本函数不保证 current ⇒ 用 pbuffer 兜一次。
        // Glow 开着时把 glow=1 的两个变体一起预热（24 §1.3）：录制首帧 / 首次开 Glow 都不编译。
        if (makeCurrent(pbufferSurface)) {
            syncMovieSlot()
            // Glow 开着：链的 program 与两个槽的 glow=1 变体都在这里编好
            //（录制中第一次开 Glow / 换档都不许现编；prepare/build 都是幂等的）
            if (glowParams.enabled) {
                glowChain?.prepare()
                prepareGlowPrograms()
            }
        } else {
            Log.w(TAG_GL, "WotaGl 预热跳过：makeCurrent(pbufferSurface) 失败")
        }
        // 这行**在 makeCurrent 之外**：预热被跳过时也要照打（program=无 本身就是排障要看的证据）
        Log.i(
            TAG_GL,
            "WotaGl 编码面绑定：program=movie ${movieSlot.program?.applied?.debug() ?: "无"}（监看叠加只上屏）"
        )
        dirty = true
        scheduleDraw()
    }

    private fun teardownEgl() {
        destroyEglSurface(windowEglSurface)
        windowEglSurface = null
        windowNative = null
        destroyEglSurface(encoderEglSurface)
        encoderEglSurface = null
        encoderNative = null
        destroyEglSurface(pbufferSurface)
        pbufferSurface = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglContext != EGL14.EGL_NO_CONTEXT &&
                !EGL14.eglDestroyContext(eglDisplay, eglContext)
            ) {
                Log.w(TAG_GL, "eglDestroyContext 失败 err=0x${eglErrorHex()}")
            }
            if (!EGL14.eglTerminate(eglDisplay)) {
                Log.w(TAG_GL, "eglTerminate 失败 err=0x${eglErrorHex()}")
            }
        }
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
    }

    // endregion

    // region 内部实现：绘制

    private fun drawFrame() {
        if (released.get() || !glReady) return
        val hasFrame = newFrames > 0
        if (!hasFrame && !dirty) return
        newFrames = 0
        dirty = false
        // 只为状态刷新而重画时不受节流限制，保证切效果/转屏立刻可见
        val stateOnly = !hasFrame

        // 编码器面优先：高帧率时上屏可以被限频，录制帧不能丢
        val first = encoderEglSurface ?: windowEglSurface ?: pbufferSurface
        if (!makeCurrent(first)) return

        if (hasFrame && !consumeFrame()) return
        if (!hasTextureData) {
            // 还没吃到相机帧，画空纹理只会闪脏块；保持脏标记等下一帧
            dirty = true
            return
        }

        // 成片槽先同步（编码 pass 要用它）；上屏槽的同步让到编码 pass 之后（§3.9.2-2）。
        // 没有编码面时 syncMovieSlot 只清绑定、不建 program（空闲预览不白编一枚用不到的）。
        syncMovieSlot()

        // Glow 离屏链：每帧一次，夹在成片槽同步与编码 pass 之间（§11.7 定版顺序）。
        // 它必须在 drawEncoderMended 之前跑——MEND 的 cur 渲染同样要采这张图。
        drawGlowPass(hasFrame)

        // 录制期转换（抽帧/补弧）只动编码支路；预览/霜/上屏各走各的，一帧不丢。
        // skip=true 的帧跳过编码 swap（帧序号不推进 ⇒ 输出节奏均匀）；MEND 通道内部失败会
        // 把自己降级并返回"需要直通补一帧"，所以调用方取反成 skip，直通调用点恒一（FrostEncoderGuard 钉着）。
        // 转换账（保留判定/位次/实测帧率）只认**新到的相机帧**：状态刷新重画（dirty 无新帧）
        // 拿旧时间戳再分类一轮会幻记丢弃、双计数源帧（实测前缀被打高）；转换模式下也不许往
        // 均匀节奏里塞无账重复帧——stateOnly 的效果切换等下一个真帧落地。
        // 2026-10-27（§3.9.2-1）：没有新帧的状态刷新一律不进编码支路（`skip = !hasFrame`）——
        // 此前只有转换模式兜住了这一条，曲线/监看的状态刷新每来一次就往成片里多塞一帧重复帧。
        var skip = !hasFrame
        if (arcConvert != null) {
            if (hasFrame) {
                // 保留判定只算一次；帧账的"有意抽帧"与 DROP/MEND 共用同一枚判定结果
                // （不得把计数反过来参与 skip/PTS：见 stampEncoderPresentation 的源码守卫）
                val keepFrame = arcRule.onFrame(lastDrawnTimestampNs)
                if (!keepFrame) FrameLedger.onArcDrop()
                when (arcConvert) {
                    ArcConvertMode.DROP -> skip = !keepFrame
                    ArcConvertMode.MEND -> skip = !drawEncoderMended(keepFrame)
                    else -> Unit
                }
                noteArcFrame(lastDrawnTimestampNs)
            } else {
                skip = true
            }
        }
        if (!skip) {
            val encoderBound = encoderEglSurface != null
            drawEncoderPass()
            // DROP 的保留帧经直通发出，位次账必须在这里落（与 MEND emit/flush 同一
            // (输出位次, 丢弃数) 口径）：漏记则 DROP 的 sidecar drops 恒空，立档落空；
            // 以"编码面在位且成片 program 可用"为前提——两者缺一 drawEncoderPass 都是空操作，
            // 记账会写假位次
            if (arcConvert == ArcConvertMode.DROP && encoderEglSurface != null && movieSlot.program != null) {
                synchronized(arcDropsLock) {
                    arcDrops += (encoderFrameIndex - 1).coerceAtLeast(0) to arcRule.takeDrops()
                }
            }
            // 两段降级的二段触发：DROP 模式下直通编码面在本帧中途掉链（makeCurrent/swap
            // 失败已在 drawEncoderPass 内走 dropEncoderSurface）= 公共部件坏，抽帧也发不出
            // 帧，按 [degradeArcConvert] 的三态退直通保底。面进出都发生在本函数单次执行内，
            // posted 的重挂/解绑不会插进中间，encoderBound→null 即为本次失败，无歧义。
            if (arcConvert == ArcConvertMode.DROP && encoderBound && encoderEglSurface == null) {
                degradeArcConvert("直通编码面掉链")
            }
        }
        // 上屏槽同步让到编码 pass **之后**：首次切监看那一次的 program 链接是毫秒级，
        // 不许让它挡在编码器的 swap 前面（与下面霜开关同一条纪律）。首帧仍成立：无编码面时
        // 编码 pass 是空操作，display 在同一帧的窗口 pass 之前建好。
        syncDisplaySlot()
        // 两槽都空 = program 子系统整体不可用（连直通都链接不上），这一帧不出图
        if (displaySlot.program == null && movieSlot.program == null) return
        // 开关意图从矩形表搬进门里——放在编码 pass **之后**：翻开关那一次的着色器编译是毫秒级，
        // 不许让它挡在编码器的 swap 前面（那是成片的一帧）
        adoptFrostIntentFromTable()
        drawFrostPass(hasFrame)
        drawWindowPass(stateOnly)
        maybeSampleHistogram()

        onFrameDrawn?.invoke(lastDrawnTimestampNs)
        checkGlError("drawFrame")
    }

    private fun drawEncoderPass() {
        val encoder = encoderEglSurface ?: return
        // 成片 program 不可用 ⇒ 宁可丢这一帧也不 stamp/swap（stamp 会推进位次账、swap 会交出一帧
        // 从未绘制的缓冲）。**绝不拿上屏槽的 program 补**——那是把斑马纹重新灌进成片的唯一路径。
        if (movieSlot.program == null) return
        if (!makeCurrent(encoder)) {
            dropEncoderSurface()
            return
        }
        // 严格全屏：视口尺寸即编码器尺寸，不做等比适配
        GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
        drawPass(encoderPass = true)
        stampEncoderPresentation(encoder)
        if (!swap(encoder)) dropEncoderSurface()
    }

    /**
     * MEND（自动抽帧补弧）路的编码支路：特效链先渲进离屏 cur，再由 `ArcRepairFlow` 的指令
     * 决定滞留/并弧/发出。
     * @return true = 本帧需要调用方**直通补发**（内部失败已把自己降级，安全阀语义）；
     *         false = 本帧已处理（发出/滞留/丢弃），调用方跳过直通。
     */
    private fun drawEncoderMended(keep: Boolean): Boolean {
        val encoder = encoderEglSurface
        val mend = arcMendPass
        if (encoder == null || mend == null || !mend.ready || encoderWidth <= 0) {
            degradeArcConvert("补弧链未就绪")
            return true
        }
        if (movieSlot.program == null) {
            // 成片 program 不可用：本帧按"需要直通补发"处理（内部已降级），绝不拿上屏槽的 program 补
            degradeArcConvert("成片 program 不可用")
            return true
        }
        if (!makeCurrent(encoder)) {
            dropEncoderSurface()
            return false
        }
        // 1) 特效链 → 离屏 cur（编码口径：只抵消矩阵分量、不转正不镜像）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mend.curFbo())
        GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
        drawPass(encoderPass = true)
        // cur 渲染完立刻恢复 FB=0（全引擎唯一一处 glBindFramebuffer，用后即还）：ArcMendPass 的
        // copyInto/mergeInto 自绑自还，唯独 drawPending 契约是「画进当前已绑定的面」——而
        // onClassification 在连续保留帧（kept&&hasPending&&!hasAcc）时 EmitPending 打头，不先还 FB
        // 它就把待发帧画进 curFbo（待发帧覆盖 cur 源，MergeCurAsPending 再把污染的 cur 拷回
        // pending = 鬼影逐帧复合），编码器 swap 到从未绘制的陈旧缓冲；片头被抽帧（!kept&&!hasPending
        // → 空 op 列）时 curFbo 还会一路漏进霜关早退的 drawFrostPass 与 drawWindowPass 预览链，
        // 并以脏 FB 跨帧污染下一段直通。drawPass 只 draw 不碰 FB，此处切换对特效链渲染零影响。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // 2) 状态机指令（保留决策已在 drawFrame 里交给 arcRule）
        for (op in arcFlow.onClassification(keep)) {
            val ok = when (op) {
                is ArcOp.AccumulateCur -> mend.accumulate(op.fresh)
                is ArcOp.FoldAccIntoPending -> mend.foldAccIntoPending()
                is ArcOp.HoldCurAsPending -> mend.holdCurAsPending()
                is ArcOp.MergeCurAsPending -> mend.mergeCurAsPending(op.useAcc)
                is ArcOp.EmitPending -> emitMended(encoder, mend)
            }
            if (!ok) {
                degradeArcConvert("补弧 GL 指令失败")
                return true
            }
            if (op is ArcOp.EmitPending) {
                // 刚发出的输出位次 = 自增前的帧序号；伴随丢弃数在保留判定时已累计。
                // 帧号夹下界：frameRate=0 直注时 stamp 不推进序号，裸 -1 会写负位次
                synchronized(arcDropsLock) {
                    arcDrops += (encoderFrameIndex - 1).coerceAtLeast(0) to arcRule.takeDrops()
                }
            }
        }
        return false
    }

    /** 待发帧 → 编码器面（视口/均匀 PTS/swap 与 [drawEncoderPass] 同一口径；帧序号只在 emit 推进） */
    private fun emitMended(encoder: EGLSurface, mend: ArcMendPass): Boolean {
        GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
        if (!mend.drawPending()) return false
        stampEncoderPresentation(encoder)
        if (!swap(encoder)) {
            dropEncoderSurface()
            return false
        }
        return true
    }

    /**
     * 转换链路失败的安全阀，三态两段：**MEND → DROP → 直通**（2026-10-04 裁决）。
     *
     * - 一段（MEND 失败）：降 **DROP（仅抽帧不补弧）**。补弧链专属部件（ArcMendPass 的
     *   program/FBO）坏了不影响 DROP 分支——它不进补弧链，抽帧节奏、均匀 PTS、位次账全保留；
     *   pending/acc 面里的半程补弧积累（状态机 + 像素面）随重建**作废**，不试图保留——
     *   丢一两枚弧光好过整段慢放。
     * - 二段（DROP 再失败，由 [drawFrame] 在直通编码面掉链时触发）：失败源于 makeCurrent/
     *   编码面这类公共部件，抽帧也救不了，最后保底降**全速直通**（arcConvert=null）——
     *   此时编码面已掉、只余上屏，录制本身大概率已中止，直通只是把状态账归零的出口。
     *
     * 全速直通**绝不作为第一响应**：转换模式下 PTS 仍按 dstFps 均匀盖，直通会让降级点之后
     * 的成片段落慢放、音画渐进漂移到秒级（结构性坏片且静默，旧实现即在位的缺陷）。
     */
    private fun degradeArcConvert(reason: String) {
        // 停止期/换段窗口一切编码失败都是「面已死」的拆解伪影（见 degradeSuppressed：
        // recordTearingDown 与 segmentRotating 并列），清账换模式只会毁掉已记好的位次账/
        // 实测帧率——首条记日志、其后静默（空闲预览每帧都撞一次抑制），窗口外的真失败
        // 不受影响照旧降级
        if (degradeSuppressed(recordTearingDown, segmentRotating)) {
            if (!teardownSuppressLogged) {
                teardownSuppressLogged = true
                Log.i(TAG_GL, "抑制转换降级（停止/换段窗口，$reason）——拆解伪影不清账（后续同类静默）")
            }
            return
        }
        when (arcConvert) {
            ArcConvertMode.MEND -> {
                Log.w(TAG_GL, "录制期转换降级：补弧失败，改仅抽帧不补弧（$reason）")
                // 复用 setArcConvert 的按档重建（含 slotNs 接线/状态机重建/位次账清点）：
                // 此刻在 GL 线程，postGl 排队到本线程随后执行，不重入当前帧；重建落定前
                // 的这枚帧按 drawEncoderMended 契约由调用方直通补发。重建会清掉降级前
                // 已记的抽帧位次账（重建路径固有行为），此后 sidecar 只记降级点之后的位次。
                setArcConvert(ArcConvertMode.DROP, arcDstFps)
            }
            ArcConvertMode.DROP -> {
                Log.w(TAG_GL, "录制期转换降级：仅抽帧也失败，退直通保底（$reason）")
                setArcConvert(null, 0)
            }
            null -> Unit
        }
    }

    /**
     * 给编码器面写**网格化**呈现时间戳（网格取真实时钟、源慢时逐帧接管，见 [encoderAdaptivePtsNs]）。
     *
     * 为什么不是纯"帧序号 × 1e9/fps"（2026-10-08 真机缺陷）：那个口径隐含假设"源实到帧率 =
     * 目标帧率"，而半精确档（24※）恰恰不满足——真机秒表对照实测：一轮 31.85s 的真实录制里
     * 视频轨合计 34.21s（每段恒 ~180 帧 / 7.45s），音频轨合计 30.01s（音频戳取自真实
     * nanoTime ⇒ 它才是真实时长）⇒ 成片被匀速拉长 8~14%、与音轨逐渐错位（亮场景源实到
     * 26~31 帧/秒，戳却按 24 帧/秒走）。写成"帧序号 × 网格"看不出这个问题，写成"真实时钟
     * 落网格"两头都要：帧长齐（不出现 90000/1）且总时长 = 真实时长。
     *
     * 为什么还要**接管**（2026-10-11 量化）：网格按名义速率锚、帧按实到速率到达时，源慢一丁点
     * 也会逐段欠格（ALN-AL00 实到 23.75 vs 名义 24 ⇒ 每 4.00s 一个假空档）。[encoderAdaptivePtsNs]
     * 在源慢过名义网格时逐帧改用 `base+elapsed`，四性质同时成立（帧长恒定、无洞、时长不变、
     * 源快时逐字同旧）。
     *
     * 打戳失败即**不推进**位次账与最后一枚戳（S1-M2-06）：`eglPresentationTimeANDROID` 是这枚
     * 网格戳的唯一落点，失败后容器里那枚是 EGL 默认的换帧瞬间墙钟——它不落网格，成片整条退回
     * 「r_frame_rate 被报成 90000/1」的老坑。账却先推进（`encoderLastPtsNs`/`encoderFrameIndex`）
     * 的话，位次 sidecar 从此宣称某个位次带网格戳，而容器内容里没有 ⇒ 账与容器永久不一致。
     * 失败次数照实计（段收尾行可查），且**不影响成片时间轴口径**：不推进 [encoderLastPtsNs]
     * 只是撤掉防回退的地板，下一帧仍由 `System.nanoTime()` 落自己的格（墙钟单调 ⇒ 戳仍单调）。
     */
    private fun stampEncoderPresentation(encoder: EGLSurface) {
        val fps = encoderFps
        if (fps <= 0) return
        val display = eglDisplay
        if (display === EGL14.EGL_NO_DISPLAY) return
        val nowNs = System.nanoTime()
        // 网格步长：NTSC 非整数档查表（见 ntscGridStepNs），其余走 1e9/fps。
        // 查表落空（该 fps 不在映射表里）时**退回整数步长**，绝不把 0 当步长传下去——
        // 0 会让 encoderAdaptivePtsNs 把整段的戳并成同一枚，成片等于只有一帧。
        val ntscStep = if (encoderNtsc) ntscGridStepNs(fps) else 0L
        val stepNs = if (ntscStep > 0L) ntscStep else 1_000_000_000L / fps
        val ptsNs = encoderAdaptivePtsNs(encoderBaseNs, nowNs, stepNs, encoderLastPtsNs, encoderFrameIndex)
        // 观测 S1-M2-02：帧龄 = 换帧墙钟 − 相机 SOF 戳。抽帧节奏吃相机戳、成片戳吃墙钟，
        // 中间那段不恒定的管线延迟此前全仓零读数，"源真慢 / 两时基速率差 / 延迟单调增长"
        // 三种成因分不开。相机戳还没到（0）不记——那会把墙钟全量算成帧龄。
        // 【2026-10-26 修正（反馈附B）】原实现 `nowNs - frameTsNs` 混了两个时钟域：
        // nowNs = System.nanoTime()（任意基准），frameTsNs = 相机 SOF 戳（SENSOR_TIMESTAMP，
        // 基准由 SENSOR_INFO_TIMESTAMP_SOURCE 决定，UNKNOWN 档从开机附近起算）——两域差一个
        // 不定常量 ⇒ 帧龄恒为巨大负值（真机实测 −21.7h / −33.3h，指标失效）。
        // 修正：**段内相对化**——本段首个样本的 (now − ts) 记为基线，其后只累计与基线的**漂移**。
        // 语义从"绝对帧龄"变为"帧龄漂移"：漂移单调上涨 = 管线延迟在涨；围绕 0 波动 = 两时基
        // 速率差；恒 0 附近 = 源真慢不成立。跨时钟域基线差被减掉，任何机型都不会再出巨负数。
        val frameTsNs = lastDrawnTimestampNs
        if (frameTsNs > 0L) {
            val ageNs = nowNs - frameTsNs
            if (stampAgeCount == 0L) stampAgeBaseNs = ageNs
            val driftNs = ageNs - stampAgeBaseNs
            stampAgeSumNs += driftNs
            stampAgeCount++
            if (driftNs > stampAgeMaxNs) stampAgeMaxNs = driftNs
        }
        // 观测 S1-M2-03：空档（跳格数 >1）在 App 侧第一次留痕。纯函数 [encoderAdaptivePtsNs]
        // 把 slot 序号丢掉了，这里从两枚戳反推跨距，计空格总数与最大单次跨距。
        val gap = encoderSlotGap(encoderLastPtsNs, ptsNs, fps)
        if (gap > 0L) {
            stampGapSlots += gap
            if (gap > stampGapMaxSlots) stampGapMaxSlots = gap
        }
        val written = runCatching { EGLExt.eglPresentationTimeANDROID(display, encoder, ptsNs) }
            .onFailure {
                stampFailCount++
                Log.w(TAG_GL, "写呈现时间戳失败：${it.message}")
            }
            .isSuccess
        if (!written) return
        encoderLastPtsNs = ptsNs
        encoderFrameIndex++
    }

    /**
     * 段收尾观测行并清账（S1-M2-02/03/06 三联的唯一出口）。
     *
     * 两个段尾都要走它：换面重挂（分段轮转）与解面（停录）。没打过一帧戳的段不刷行，
     * 但账一律清干净——半会话状态漏进下一次录制比少一行日志坏得多。
     */
    private fun drainStampObs(why: String) {
        if (stampAgeCount > 0L || stampGapSlots > 0L || stampFailCount > 0) {
            Log.i(
                TAG_GL,
                stampObsLine(
                    why, stampAgeSumNs, stampAgeCount, stampAgeMaxNs,
                    stampGapSlots, stampGapMaxSlots,
                    // 实到帧率 = 本段打戳成功的帧数 / 本段时长。与「空格」互补：
                    // 空格答「网格有没有被跳空」，实到答「源到底交了多少帧」。
                    // 读点在 encoderBaseNs 复位（换面末尾）**之前** —— 本函数就调在那一段里。
                    stampAchievedCentiFps(encoderFrameIndex, System.nanoTime() - encoderBaseNs),
                    stampFailCount
                )
            )
        }
        stampAgeSumNs = 0L
        stampAgeCount = 0L
        stampAgeMaxNs = 0L
        stampAgeBaseNs = 0L // 新段重取基线（跨段的时钟域常量不许带进来）
        stampGapSlots = 0L
        stampGapMaxSlots = 0L
        stampFailCount = 0
    }

    /**
     * 毛玻璃离屏 pass（#84 步骤 1）。夹在编码 pass 之后、上屏 pass 之前，四道门缺一不可：
     *
     * - 开关关 ⇒ 一次 GL 调用都不发（A/B 对照组必须干净）；
     * - `hasFrame` 为假 ⇒ 状态刷新重画没有新像素可糊，跳过；
     * - 没有窗口面 ⇒ 后台纯录制时糊给谁看？跳过（省掉一整趟离屏，编码路径零影响）；
     * - 上屏这帧本来就要被 [MAX_DISPLAY_FPS] 节流掉 ⇒ 糊了也没人看，跳过。
     *
     * 与录制的所有瓜葛到此为止：本函数**不进** [drawEncoderPass]，不改它的视口/纹理坐标/swap 时机/PTS，
     * 链内部进出各存各恢复 `glViewport`、`glScissor`、`glUseProgram` 与顶点属性 enable 位
     * （见 [FrostBlurChain] 的状态恢复），所以录出来的画面与开关无关、上屏的状态也不带脏。
     * 糊出来之后**谁把霜贴到屏幕上**是另一件事：那在本文件的 [drawFrostPlates]（上屏 pass 里，
     * 同样绝不进编码 pass），两者共用 `publishedFrost` 这一份同帧快照。
     * 唯一的观感分叉是「GL 模式的上屏多了霜、DIRECT 没有」，性质与 curve/zebra 同族。
     */
    private fun drawFrostPass(hasFrame: Boolean) {
        if (!wantFrostBlur) {
            if (publishedFrost != null) publishedFrost = null
            return
        }
        if (!hasFrame) return
        val chain = frostChain ?: return
        if (windowEglSurface == null || windowThrottled()) return
        val contentW = glContentRect.width()
        val contentH = glContentRect.height()
        if (contentW <= 0 || contentH <= 0) {
            if (publishedFrost != null) publishedFrost = null
            return
        }
        // 拷贝趟要和上屏 pass 采同一套坐标（含转正与镜像），RT 里的图才与屏幕上看到的同朝向；
        // buildTexCoords 自带按 key 缓存，这里不会每帧重算
        buildTexCoords(encoderPass = false)
        chain.draw(oesTextureId, stMatrix, displayTexCoordBuffer, contentW, contentH)
        publishFrost(chain.target)
    }

    /**
     * 快照只在真变化时换对象（每帧零分配的另一半）：内容 = 结果纹理 + 同帧的等比画面矩形，
     * 一次写一个 @Volatile 引用，主线程不会读到「新尺寸配旧矩形」这种撕裂组合。
     */
    private fun publishFrost(target: FrostRenderTarget?) {
        if (target == null) {
            if (publishedFrost != null) publishedFrost = null
            return
        }
        val l = glContentRect.left.toFloat()
        val t = glContentRect.top.toFloat()
        val r = glContentRect.right.toFloat()
        val b = glContentRect.bottom.toFloat()
        val current = publishedFrost
        if (current != null &&
            current.matches(target.textureId, target.widthPx, target.heightPx, l, t, r, b)
        ) {
            return
        }
        publishedFrost = FrostSnapshot(
            textureId = target.textureId,
            texWidthPx = target.widthPx,
            texHeightPx = target.heightPx,
            contentRectInViewPx = FrostRectPx(l, t, r, b)
        )
    }

    private fun drawWindowPass(stateOnly: Boolean) {
        val window = windowEglSurface ?: return
        // 上屏 program 不可用（整条回退阶梯都编不出来）：宁可这一帧不刷窗口，也不清黑 + swap——
        // 那会闪一帧纯黑（§3.4 的失败隔离：上屏失败不该有任何可见副作用以外的动作）
        if (displaySlot.program == null) return
        if (!stateOnly && windowThrottled()) return
        if (!makeCurrent(window)) {
            dropWindowSurface()
            return
        }
        // 上屏降档（24 §2.2）：>ORIG 时先画进等比区/divisor 的 RT 再放大 blit 回来。
        // 同一帧**绝不**既画 RT 又画窗口 —— 降档不可用时整帧改走原片路径。
        if (wantDisplayScale != DisplayScale.ORIG && drawWindowPassScaled(window)) return
        drawWindowPassOrig(window)
    }

    /**
     * **原片档（`DisplayScale == ORIG`）的那条路：与引入上屏档之前逐字不变。**
     * 清黑 → 视口=等比区 → `drawPass(false)` → 霜板 → swap。
     * 本函数体内**不许**出现任何上屏 RT / blit / 档位引用（源码守卫钉着）。
     */
    private fun drawWindowPassOrig(window: EGLSurface) {
        // 先全屏清黑再把视口收窄到等比居中区，得到正确黑边
        GLES20.glViewport(0, 0, windowWidth, windowHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val left = glContentRect.left
        val top = glContentRect.top
        val width = glContentRect.width().coerceAtLeast(1)
        val height = glContentRect.height().coerceAtLeast(1)
        // 窗口坐标 Y 向下 → GL 视口 Y 向上
        GLES20.glViewport(left, windowHeight - top - height, width, height)
        drawPass(encoderPass = false)
        finishWindowFrame(window)
    }

    /**
     * 上屏帧的**收尾**（两条窗口路径共用：原片档与上屏降档）。
     *
     * 顺带说明：上屏降档（24 §2.2）把"blit 回窗口"插在 `drawPass` 与这里之间，
     * 所以霜板在两条路上都仍是"预览之后、swap 之前"（#84 的顺序红线不变）；
     * 而它**只**在这条尾巴里贴 —— [drawEncoderPass] 一个引用都没有（`FrostEncoderGuardTest` 钉着）。
     */
    private fun finishWindowFrame(window: EGLSurface) {
        // 预览 blit 之后、swap 之前：按矩形表把霜底板贴上去（#84 步骤 2，A2 混合）。
        // 这一步只在窗口面里发生，[drawEncoderPass] 走的是另一条完整独立的路（同一条"绝不碰霜"红线）
        drawFrostPlates()
        if (!swap(window)) dropWindowSurface() else lastWindowSwapNs = System.nanoTime()
    }

    /**
     * 上屏降档（`DisplayScale > ORIG`）：等比区/divisor 的 RT → 放大 blit → 霜板 → swap。
     *
     * @return true = 本帧已经画完并 swap；false = 这一档此刻不可用（`begin` 说不行，或运行时链被停用），
     *   调用方**同一帧**改走 [drawWindowPassOrig]（并且本函数已经置了 [displayScaleFallback]）
     */
    private fun drawWindowPassScaled(window: EGLSurface): Boolean {
        val post = displayPost
        val size = glContentRect
        val contentW = size.width()
        val contentH = size.height()
        // 瞬态 0/未量到的等比区（转屏/分屏/视图刚重建）不是"这一档不可用"：
        // 静默回原片这一帧即可，**绝不置回落信号** —— 否则 UI 会把用户档位永久写回并落盘
        // （录制中 SIZE 还锁着，用户改不回来 ⇒ 设置白丢）。
        if (contentW <= 0 || contentH <= 0) return false
        if (post == null ||
            !post.begin(contentW, contentH, wantDisplayScale.divisor, glMaxTextureSize())
        ) {
            // 尺寸是正的但这一档真的用不了（<128px / 超纹理上限 / FBO 或 program 级失败）：
            // 让 UI 按最近可用档收回（"读数显示 4、实际按原片跑"是禁止的静默偏差）
            displayScaleFallback.value = true
            return false
        }
        // ① 画进 RT（视口已由 begin 设成 RT 尺寸）
        drawPass(encoderPass = false)
        // ② 回默认面 + 放大 blit（霜板/swap 都假定当前是默认面）
        post.blitToWindow(windowWidth, windowHeight, size)
        finishWindowFrame(window)
        return true
    }

    /**
     * 上屏画板（#84 步骤 2）：读一张矩形表、贴若干块「霜 + 圆角 + 底板色」，然后把"这帧真贴了板"
     * 回报给 [FrostCardTable]，UI 侧据此决定底板的纯色 fill 让不让位。
     *
     * 三条口径值得写明白：
     * - **读表与画板在同一条 GL 线程上**，所以表里的矩形与 `publishedFrost` 的等比画面矩形是同一帧的账，
     *   不需要再防"新尺寸配旧矩形"（跨线程那一半由 [FrostCardTable] 的双缓冲 + 一次引用交换保证，
     *   见它的类注释「不撕裂」一节）；
     * - 读表**不会失败也不会撞车**：一次 `tryReadInto` 拿到的就是某一整代的完整表（-1 只在调用方
     *   数组太短时出现，本类的 [frostTableCopy] 按 [FrostCardTable.TABLE_FLOATS] 定长，永不触发）；
     *   读到的表为空（`frostTableCards == 0`，HUD 还没注册板 / 表被停用）就一块都不画；
     * - 快照为 null（开关刚关、链停用、还没出帧）时报 false ⇒ 旧的纯色 fill 立刻回来，
     *   这一条是"关掉要能完整回到现在的观感"的可证部分。
     *
     * ⚠ **本函数不是每帧都跑到**：[drawWindowPass] 被 `windowThrottled()` 早退的那些帧（上屏帧率高于
     * [MAX_DISPLAY_FPS] 时的节流帧）压根不进这里，既不贴板也不回报 ⇒ [FrostCardTable.isPlatesDrawn]
     * 在节流间隔里保持上一帧的值。节流帧本来就不上屏，所以这不是漏报，但"每帧回报"那句话别说满。
     */
    private fun drawFrostPlates() {
        val snapshot = publishedFrost
        val pass = frostPlatePass
        val read = FrostCardTable.tryReadInto(frostTableCopy)
        if (read >= 0) frostTableCards = read
        val drew = if (snapshot != null && pass != null && frostTableCards > 0) {
            val rect = snapshot.contentRectInViewPx
            pass.draw(
                texId = snapshot.textureId,
                texWidthPx = snapshot.texWidthPx,
                texHeightPx = snapshot.texHeightPx,
                contentLeftPx = rect.left,
                contentTopPx = rect.top,
                contentRightPx = rect.right,
                contentBottomPx = rect.bottom,
                viewWidthPx = windowWidth,
                viewHeightPx = windowHeight,
                table = frostTableCopy,
                cardCount = frostTableCards
            )
        } else {
            0
        }
        // 霜探针（#84）：只做镜像写，不参与任何判断——这帧"表里几块、实画几块、快照在不在"如实留档
        frostDiagSnapshotPublished = snapshot != null
        frostDiagTableCards = frostTableCards
        frostDiagDrewPlates = drew
        FrostCardTable.reportPlatesDrawn(drew > 0)
    }

    private fun dropWindowSurface() {
        destroyEglSurface(windowEglSurface)
        windowEglSurface = null
        windowNative = null
        windowWidth = 0
        windowHeight = 0
    }

    private fun dropEncoderSurface() {
        destroyEglSurface(encoderEglSurface)
        encoderEglSurface = null
        encoderNative = null
        encoderWidth = 0
        encoderHeight = 0
        // movie 槽只清引用、不删 program：同一枚可能正被 display 槽共用 / 在会话缓存里等着复用，
        // 统一由缓存淘汰与 [releaseGl] 收口
        movieSlot.clearBinding()
    }

    private fun windowThrottled(): Boolean {
        if (lastWindowSwapNs == 0L) return false
        val minIntervalNs = (1_000_000_000L / MAX_DISPLAY_FPS).toLong()
        return System.nanoTime() - lastWindowSwapNs < minIntervalNs
    }

    private fun consumeFrame(): Boolean {
        val st = inputTexture ?: return false
        return try {
            st.updateTexImage()
            FrameLedger.onGlConsumed()
            st.getTransformMatrix(stMatrix)
            // 矩阵自带的旋转分量决定「抵消多少」，变了就必须重算两路纹理坐标（真机首帧才拿得到矩阵）
            val stChanged = orientation.setStMatrix(stMatrix)
            // 方向链路诊断（2026-10-27 前摄歪 90° 排查）：矩阵逐元素只在首帧/换姿态打一行，
            // 前后摄 HAL 矩阵不同轴时这里是唯一可观测量
            if (stChanged || !stLoggedOnce || orientation.mirrored != stLoggedMirror) {
                stLoggedOnce = true
                stLoggedMirror = orientation.mirrored
                Log.i(
                    TAG_GL,
                    "WotaOrient 矩阵=${stMatrix.contentToString()} " +
                        "stK=${orientation.stRotationDegrees} residual=${orientation.residualDegrees()} " +
                        "applied=${orientation.appliedDegrees()} " +
                        "disp=${orientation.displayDegrees()}/${orientation.displayMirrored()} " +
                        "encMirror=${orientation.encoderMirrored()} " +
                        "frame=${wantFrameWidth}x${wantFrameHeight}"
                )
            }
            if (stChanged) {
                updateContentRect()
                dirty = true
                scheduleDraw()
            }
            lastDrawnTimestampNs = st.timestamp
            hasTextureData = true
            true
        } catch (t: Throwable) {
            // 切镜头/面失效瞬间会抛 Illegal(State|Argument)Exception，跳过本帧即可
            Log.w(TAG_GL, "updateTexImage 跳过本帧: ${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * 两条输出路唯一的绘制例程：**按布尔选槽**，其余逐行不变。
     *
     * 不变量（双槽模型的编译期外契约，`ProgramKeyTest` / `MonitorEffectEncoderGuardTest` 钉着）：
     * - `p.applied.effect != NONE ⇒ p.applied.denoise == 0`——`uTexel` 一颗 uniform 被峰值与降噪共用，
     *   靠这条保证两者不同时在场（由 [displayProgramKey] 的降噪闸给出）；
     * - 编码 pass 只能拿到 movie 槽（effect 恒 NONE），监看叠加结构上进不了成片。
     *
     * 贴图坐标与输出面仍由 `encoderPass` 决定（[buildTexCoords]）：编码只抵消矩阵分量、不转正不镜像。
     */
    private fun drawPass(encoderPass: Boolean) {
        val p = (if (encoderPass) movieSlot else displaySlot).program ?: return
        GLES20.glUseProgram(p.id)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (p.uFrame >= 0) GLES20.glUniform1i(p.uFrame, 0)
        if (p.uTexMatrix >= 0) GLES20.glUniformMatrix4fv(p.uTexMatrix, 1, false, stMatrix, 0)

        if (p.applied.curve) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTextureId)
            if (p.uCurve >= 0) GLES20.glUniform1i(p.uCurve, 2)
        }

        if (p.applied.effect == FrameEffect.ZEBRA) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripeTextureId)
            if (p.uStripe >= 0) GLES20.glUniform1i(p.uStripe, 1)
            if (p.uThreshold >= 0) GLES20.glUniform1f(p.uThreshold, wantZebraThreshold)
            val repeat = ZebraPattern.tileRepeat(wantZebraDensity)
            if (p.uTile >= 0) GLES20.glUniform2f(p.uTile, repeat, repeat)
        } else if (p.applied.effect == FrameEffect.PEAKING) {
            if (p.uTexel >= 0) GLES20.glUniform2f(p.uTexel, PEAKING_TEXEL, PEAKING_TEXEL)
            if (p.uStrength >= 0) GLES20.glUniform1f(p.uStrength, wantPeakingStrength)
            val rgb = peakingRgb(wantPeakingColor)
            if (p.uColor >= 0) GLES20.glUniform3f(p.uColor, rgb[0], rgb[1], rgb[2])
        }

        // 双边降噪的 uniform（2026-10-26）：纹素步长取相机帧尺寸（错则降噪半径错）；
        // 只在本帧 program 确为双边档时写，不给直通/曲线 program 白设未知 uniform
        if (p.applied.denoise != DENOISE_LEVEL_OFF) {
            if (p.uTexel >= 0 && wantFrameWidth > 0 && wantFrameHeight > 0) {
                GLES20.glUniform2f(p.uTexel, 1f / wantFrameWidth, 1f / wantFrameHeight)
            }
            if (p.uSigmaR >= 0) GLES20.glUniform1f(p.uSigmaR, denoiseSigmaR)
        }

        // Glow（scene layer，两槽都带）：链在 drawFrame 里跑过（每帧一次），这里**只采样结果**。
        // 链未就绪绑 1×1 黑纹理 ⇒ 贡献为 0，绝不为它切 program（切 program 会闪帧）。
        val glowOn = p.applied.glow
        if (glowOn) {
            val glowTex = glowFrameTex.takeIf { it != 0 } ?: glowBlackTex
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GLOW_SRC_UNIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowTex)
            if (p.uGlowSrc >= 0) GLES20.glUniform1i(p.uGlowSrc, GLOW_SRC_UNIT)
            if (p.uGlowGain >= 0) GLES20.glUniform1f(p.uGlowGain, glowParams.gain)
            if (p.uGlowAdd >= 0) GLES20.glUniform1f(p.uGlowAdd, glowParams.add)
            if (p.uGlowFreq >= 0) GLES20.glUniform1f(p.uGlowFreq, glowParams.freq)
            if (p.uGlowBlend >= 0) GLES20.glUniform1f(p.uGlowBlend, glowParams.blend)
            if (p.uGlowTint >= 0) {
                val t = glowTint
                GLES20.glUniform3f(p.uGlowTint, t[0], t[1], t[2])
            }
            // 2026-10-28：同色光晕——不再上传色带两端色（见 Shaders.applyGlow 的注释）
        }

        val coords = if (encoderPass) encoderTexCoordBuffer else displayTexCoordBuffer
        buildTexCoords(encoderPass)
        if (p.aPosition >= 0) {
            GLES20.glEnableVertexAttribArray(p.aPosition)
            GLES20.glVertexAttribPointer(p.aPosition, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        }
        if (p.aTexCoord >= 0) {
            GLES20.glEnableVertexAttribArray(p.aTexCoord)
            GLES20.glVertexAttribPointer(p.aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // 立刻把 5 号单元上的 Glow 图摘下来：下一帧链要往同一张纹理里画，某些驱动只查
        // "这张还挂在某个激活单元上"就当 feedback loop 报警（免费的保险，与霜链同一条理由）
        if (glowOn) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GLOW_SRC_UNIT)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
    }

    /**
     * 等比居中：内容尺寸 = 相机帧按 sensorOrientation 旋转后的包围盒。
     * 用包围盒公式而不是「只有 90 倍数才交换宽高」，兼容个别机型报非整百的 sensorOrientation。
     *
     * 「按比例缩放 + 取整 + 居中」那一半算术抽在 [letterboxContentRectInPx]（纯函数、有手算单测）：
     * 毛玻璃的 RT 尺寸与卡片 → UV 换算都以这块矩形为基准，两边共用一个真源才不会各算各的。
     */
    private fun updateContentRect() {
        val w = windowWidth
        val h = windowHeight
        val fw = wantFrameWidth
        val fh = wantFrameHeight
        if (w <= 0 || h <= 0 || fw <= 0 || fh <= 0) {
            glContentRect.set(0, 0, w.coerceAtLeast(0), h.coerceAtLeast(0))
            publishedContentRect = Rect(glContentRect)
            return
        }
        val rad = Math.toRadians(normalizedOrientation().toDouble())
        val c = abs(cos(rad).toFloat())
        val s = abs(sin(rad).toFloat())
        val contentW = fw * c + fh * s
        val contentH = fw * s + fh * c
        if (contentW <= 0f || contentH <= 0f) {
            glContentRect.set(0, 0, w, h)
            publishedContentRect = Rect(glContentRect)
            return
        }
        val fit = letterboxContentRectInPx(w, h, contentW, contentH)
        glContentRect.set(fit.left.roundToInt(), fit.top.roundToInt(), fit.right.roundToInt(), fit.bottom.roundToInt())
        publishedContentRect = Rect(glContentRect)
    }

    private fun normalizedOrientation(): Int = orientation.residualDegrees()

    /**
     * 两路各自把「旋转 + 镜像」烘进 4 个顶点的纹理坐标。
     *
     * 施加角 [PreviewOrientation.appliedDegrees] = 残余角 + 缓冲矩阵自带的旋转分量：矩阵那部分先被
     * 抵消回教科书约定，再按残余角转正，所以本函数与 `uTexMatrix` 复合后的净效果与机型无关。
     *
     * 编码 pass 只抵消矩阵分量、不转正、不镜像 —— 送进编码器的永远是「缓冲原样」那一帧，
     * 容器方向交给 `orientationHint`（见 record.recordOrientationHint），两种渲染模式因此同尺寸同方向。
     *
     * 推导：屏幕位置 (u,v)（v 向上）→ 图像空间 X=u-0.5、Y=0.5-v（Y 向下为正）；
     * 内容顺时针转 θ 等价于取样位置逆旋 -θ，即 X0=c·X+s·Y、Y0=-s·X+c·Y；
     * 镜像作用在屏幕空间，故先令 X=-X。写回属性时 t=1-v 抵消顶点 Y 轴方向。
     */
    private fun buildTexCoords(encoderPass: Boolean) {
        // 上屏走上屏口径（displayDegrees/displayMirrored），编码只抵消矩阵分量（stRotationDegrees
        // + encoderMirrored）：两族的分工完全不同，2026-10-27 前摄方向缺陷把这一点写在这里。
        val deg = if (encoderPass) orientation.stRotationDegrees else orientation.displayDegrees()
        val mirror = if (encoderPass) orientation.encoderMirrored() else orientation.displayMirrored()
        val key = (deg shl 1) or (if (mirror) 1 else 0)
        val target = if (encoderPass) encoderTexCoords else displayTexCoords
        val buffer = if (encoderPass) encoderTexCoordBuffer else displayTexCoordBuffer
        val cached = if (encoderPass) encoderGeometryKey else displayGeometryKey
        if (key == cached) return
        if (encoderPass) encoderGeometryKey = key else displayGeometryKey = key
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        for (i in 0 until 4) {
            var x = positions[i * 2] * 0.5f
            val y = -positions[i * 2 + 1] * 0.5f
            if (mirror) x = -x
            val sx = c * x + s * y
            val sy = -s * x + c * y
            target[i * 2] = sx + 0.5f
            target[i * 2 + 1] = 0.5f - sy
        }
        buffer.position(0)
        buffer.put(target)
        buffer.position(0)
    }

    // endregion

    // region 对外：Glow（GlowProvider 实现 + 离屏链驱动）

    /**
     * 写入 Glow 参数（任意线程）。
     *
     * **绝不调 `requestRender()`/置 `dirty`**：形状参数是 uniform 级改动，下一个相机帧生效（≤33ms）；
     * 触发重画就等于在录制中往成片里多塞一帧重复帧（霜的先例，25 §11.7 / 24 §1.3）。
     *
     * `enabled` 翻真时把"编译/建链"挪到 postGl：开光晕那一帧本来就什么都没画，不该在
     * `drawFrame` 里现编（毫秒级编译撞上编码 swap 就是丢格）。
     * 关掉只停跑（下一帧起链不再被调），资源保留 —— 再打开不重建、不闪。
     */
    override fun setGlowParams(params: GlowParams) {
        if (released.get()) return
        val next = params.sanitize()
        val wasOn = glowParams.enabled
        glowParams = next
        // tint 预换算（颜色只在参数变化时算一次，帧路径零分配）
        glowTint = glowRgb(next.color)
        if (!next.enabled || wasOn) return
        postGl {
            // 显式开启 = 给含 glow 维度的坏键一次重试（同一坏键不逐帧重试的口径不变）
            failedKeys.removeAll { it.glow }
            glowChain?.resetAfterBreak()
            glowChain?.prepare()
            prepareGlowPrograms()
        }
    }

    /**
     * 本 sink 这一刻能否产出光晕：链已建、引擎未释放、链没被停用。
     * **不**看 `glowParams.enabled`（"能不能"与"开没开"是两件事，UI 要分开显示）。
     */
    override fun isGlowAvailable(): Boolean =
        glowChain != null && !released.get() && glowChain?.isBrokenForDiagnostics() != true

    /**
     * 状态快照（UI 线程读）。字段来源：
     * - `available` = 链当前产出的纹理 id ≠ 0（未就绪/停用时为 false）；
     * - `enabled` = [glowParams]；`broken` = 链的停用位；
     * - `scale` = [glowScaleFor]（相机帧尺寸 × 帧率；还没绑定编码面时帧率按 30 估）；
     * - `input*` = 相机帧尺寸（= 录制档）；`rt*`、`clamped` = 链当前 RT 与夹取位。
     *
     * 这些读都是"尽力快照"：跨线程读到的可能是上一帧的组合（胶囊本来就是低频读数，够用）。
     */
    override fun glowStatus(): GlowStatus {
        val chain = glowChain
        val fps = if (encoderFps > 0) encoderFps else 30
        return GlowStatus(
            available = (chain?.resultTex ?: 0) != 0,
            enabled = glowParams.enabled,
            broken = chain?.isBrokenForDiagnostics() == true,
            scale = glowScaleFor(wantFrameWidth, wantFrameHeight, fps),
            inputWidthPx = wantFrameWidth,
            inputHeightPx = wantFrameHeight,
            fps = fps,
            rtWidthPx = chain?.rtWidthPx ?: 0,
            rtHeightPx = chain?.rtHeightPx ?: 0,
            clamped = chain?.lastClamped == true
        )
    }

    /**
     * 预热 **glow=1** 的两个键（成片槽 + 上屏槽）：只进会话缓存、**不绑槽**。
     *
     * 为什么要预热：`enabled` 翻真会换掉两槽的 program，而编译是毫秒级——不预热就是
     * "录制中第一次开 Glow 掉一帧"。失败按既有失败备忘口径走 [failedKeys]（回退阶梯会降掉 glow），
     * 这里不额外处理返回值。
     */
    private fun prepareGlowPrograms() {
        val curveOn = curveBytes != null
        val denoise = denoiseLevelNow()
        buildProgram(movieProgramKey(curveOn, denoise, glow = true), "movie")
        buildProgram(displayProgramKey(wantEffect, curveOn, denoise, glow = true), "display")
    }

    /**
     * Glow 离屏链：**每帧一次**，在 `drawFrame` 里编码 pass 之前跑（§11.7 的定版顺序）。
     *
     * 四条早退：
     * - 关着 / `blend <= 0` ⇒ 一次 GL 调用都不发（零代价口径）；
     * - state-only 帧（`!hasFrame`）⇒ 没有新像素可糊，跳过；
     * - 既没编码面也没窗口面 ⇒ 没有消费者，跳过；
     * - **空闲预览且上屏正被 [MAX_DISPLAY_FPS] 节流** ⇒ 跳过。只在没有编码面时省：
     *   编码面在位时编码器每帧都要这张图，一帧都不能省。
     *
     * 链自己保存/恢复 viewport/scissor/program/属性/FBO（含 `GL_FRAMEBUFFER_BINDING`），
     * 所以它夹在 `syncMovieSlot()` 与编码 pass 之间不会伤到 MEND 的"画进当前面"契约。
     */
    private fun drawGlowPass(hasFrame: Boolean) {
        if (!glowParams.enabled || glowParams.blend <= 0f) return
        if (!hasFrame) return
        if (encoderEglSurface == null && windowEglSurface == null) return
        if (encoderEglSurface == null && windowThrottled()) return
        val uv = glowIdentityUv ?: return
        val chain = glowChain ?: return
        val scale = glowScaleFor(wantFrameWidth, wantFrameHeight, if (encoderFps > 0) encoderFps else 30)
        val ok = chain.draw(
            oesTextureId = oesTextureId,
            texMatrix = stMatrix,
            identityUv = uv,
            frameWidthPx = wantFrameWidth,
            frameHeightPx = wantFrameHeight,
            scale = scale,
            widthParam = glowParams.width,
            thresholdParam = glowParams.threshold,
            softParam = glowParams.soft
        )
        // drawPass 只认这个字段（不直接读链）：失败时链已把 resultTex 归 0 ⇒ 这里自然退回黑纹理
        glowFrameTex = chain.resultTex
        // 档变才打（每帧一行会把 logcat 刷满；分档本身是"自动变化必须可见"的取证点）
        if (ok && scale != glowLoggedScale) {
            glowLoggedScale = scale
            Log.i(TAG_GLOW, "Glow 链：scale=$scale rt=${chain.rtWidthPx}x${chain.rtHeightPx}（每帧一次）")
        }
    }

    /** 1×1 全黑 RGBA8：链未就绪时的 `uGlowSrc` 兜底（贡献为 0，不切 program） */
    private fun createGlowBlackTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4)
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    // endregion

    // region 对外：上屏降档（24 §2.3）

    /**
     * 写入上屏渲染比例（任意线程）。
     *
     * **不 `requestRender`/不置 `dirty`**：这是上屏那一路的渲染比例，下一个相机帧的窗口 pass 自然生效
     * （≤16ms）；触发重画只会在录制中多画一帧，没有任何收益。
     * `>ORIG` 时把 blit program 的编译挪到 postGl（开档那一帧本来就什么都没画，不该在帧路径上现编）；
     * `ORIG` 时释放 RT（只删两枚空纹理，program 留在会话缓存 —— 编译贵、重建便宜）。
     *
     * 档位超出本机能力（等比区太小/纹理上限）由 [drawWindowPass] 当场回原片并通过
     * [displayScaleFallback] 告知 UI，**不在写入侧猜**（那一刻窗口尺寸可能还没量出来）。
     */
    fun setDisplayScale(scale: DisplayScale) {
        if (released.get()) return
        if (wantDisplayScale == scale) return
        wantDisplayScale = scale
        postGl {
            if (scale == DisplayScale.ORIG) displayPost?.releaseTarget() else displayPost?.prepare()
        }
    }

    /** 最近一次查询到的 `GL_MAX_TEXTURE_SIZE`（异常值兜 2048，低端机常见上限） */
    private fun glMaxTextureSize(): Int {
        if (glMaxTextureSizeCached > 0) return glMaxTextureSizeCached
        val out = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, out, 0)
        glMaxTextureSizeCached = if (out[0] > 0) out[0] else 2048
        return glMaxTextureSizeCached
    }

    // endregion

    // region 内部实现：program 与纹理（双槽）

    /**
     * Glow 维度（docs/plan/24 R3）：scene layer，两槽都带、进成片。
     * 直接取用户开关——`enabled` 一变，两槽的键就变（走会话缓存，不重编）。
     */
    private fun glowOn(): Boolean = glowParams.enabled

    /**
     * **成片槽**同步：必须在编码 pass 之前调（[drawFrame]）。键 = [movieProgramKey]，effect 恒 NONE。
     *
     * 没有编码面就**不建** movie（空闲预览不白编一枚用不到的程序）：只清绑定、不删对象——
     * 同一枚 program 可能正被 display 共用，或者在会话缓存里等着下一次起录复用。
     *
     * 键变了才重建（`requested` 记请求键，`program.applied` 记实际键）。失败走 §3.4 的回退阶梯，
     * 坏键记进 [failedKeys]（同一坏键不逐帧重试）。
     */
    private fun syncMovieSlot() {
        val wantMov = movieProgramKey(curveBytes != null, denoiseLevelNow(), glowOn())
        if (encoderEglSurface == null) {
            movieSlot.clearBinding()
            syncEffectTextures()
            return
        }
        if (movieSlot.program == null || movieSlot.requested != wantMov) {
            movieSlot.requested = wantMov
            movieSlot.program = buildProgram(wantMov, "movie")
        }
        syncEffectTextures()
    }

    /**
     * **上屏槽**同步：必须在编码 pass **之后**调（[drawFrame]）——首次切监看那一次的 program
     * 链接是毫秒级，不许挡在编码器的 swap 前面（与霜开关同一条纪律，§3.9.2-2）。
     *
     * 与 movie 同键且 movie 已建好 ⇒ **直接共用**那一枚（`effect=NONE` 时零编译的根源）；
     * 否则按自己的键取/建（监看叠加只影响 display 的键）。
     */
    private fun syncDisplaySlot() {
        val curveOn = curveBytes != null
        val denoise = denoiseLevelNow()
        val glow = glowOn()
        val wantDisp = displayProgramKey(wantEffect, curveOn, denoise, glow)
        val wantMov = movieProgramKey(curveOn, denoise, glow)
        if (wantDisp == wantMov && movieSlot.program?.applied == wantMov) {
            // 两槽同键且成片那枚就是本键 ⇒ 直接共用（不新增编译、不新增显存）。
            // movie 走了回退（applied ≠ 请求键）时不共用：各按自己的键取一次，缓存键相同 ⇒
            // 取到的仍是同一枚回退 program，不重复编译
            displaySlot.requested = wantDisp
            displaySlot.program = movieSlot.program
        } else if (displaySlot.program == null || displaySlot.requested != wantDisp) {
            displaySlot.requested = wantDisp
            displaySlot.program = buildProgram(wantDisp, "display")
        }
        syncEffectTextures()
    }

    /**
     * 两槽共享的两张纹理（§3.7）：
     * - 斑马条纹瓦片**只属于上屏槽**（monitor layer）——删除判据看 display 的 `applied.effect`；
     * - 曲线 LUT 两槽共享——**任一槽**的 `applied.curve` 为真就留着，都为假才删。
     *
     * 每帧都调是安全的：两个 ensure 各自带"形状/表没变就早退"的判据，改曲线表只重传、不重建 program。
     */
    private fun syncEffectTextures() {
        if (displaySlot.program?.applied?.effect == FrameEffect.ZEBRA) {
            ensureStripeTexture(wantZebraDensity)
        } else {
            deleteStripeTexture()
        }
        if (movieSlot.program?.applied?.curve == true || displaySlot.program?.applied?.curve == true) {
            ensureCurveTexture()
        } else {
            deleteCurveTexture()
        }
    }

    /**
     * 失败备忘被清掉之后**给两槽一次重试机会**：只作废"请求键"、保留 program 引用。
     *
     * 为什么不能只清 [failedKeys]：槽里可能正挂着回退结果（`requested.curve=true` 但
     * `applied.curve=false`），只看"请求键没变"就又走回老路，曲线改了一直不生效。
     * 保留 program 引用的理由：这一瞬 `syncEffectTextures` 还能查到 applied，不会把
     * 上屏正在用的条纹/曲线纹理误删（那些 delete 都看槽里的 applied）。
     */
    private fun retrySlots() {
        displaySlot.requested = null
        movieSlot.requested = null
    }

    /**
     * 本帧该用哪个降噪档（0 = 关，1 = 完整核，2 = 轻量核）。
     *
     * 判据 = [com.wotagei.cam.core.denoiseKernelLite]（纯函数，UI 侧算提示用的是同一支）：
     * **编码面**的「像素 × 帧率」超过阈值就用轻量核。只算编码面是有意的——它是成片的唯一来源，
     * 也是唯一会按相机帧节奏逐帧跑满全分辨率的 pass；上屏那一路本身被限频（[MAX_DISPLAY_FPS]），
     * 拿它参与分档只会让预览态与录制态反复换档、白闪两次 program。
     *
     * 无编码面（只在预览）时一律完整核：那时没有成片帧要保，少一层降噪反而是损失。
     */
    private fun denoiseLevelNow(): Int = when {
        !wantDenoise -> DENOISE_LEVEL_OFF
        encoderEglSurface != null && encoderFps > 0 &&
            com.wotagei.cam.core.denoiseKernelLite(encoderWidth, encoderHeight, encoderFps) ->
            DENOISE_LEVEL_LITE
        else -> DENOISE_LEVEL_FULL
    }

    /**
     * 四维键 → 片元着色器。**降噪只在 effect=NONE（直通/曲线）这两条支路**：斑马纹与峰值对焦是
     * 判读工具，对它们降噪没有意义（这条口径由 [displayProgramKey] 的降噪闸保证，不是在这里判）。
     * 抽成函数是因为回退阶梯的每一级都换一个键、也就换一份源码。
     *
     * `glow=true` 时在 base 上注入 `applyGlow`（[Shaders.glowFragment]）；**注入锚点对不上返回 null**
     * ⇒ 整条链接失败 ⇒ [nextFallback] 降掉 glow 并留日志。不许静默用无 Glow 源：
     * 那会让"开了光晕但没效果"变成查不出来的静默失效。
     */
    private fun fragmentFor(key: ProgramKey): String? {
        val base = when (key.effect) {
            FrameEffect.ZEBRA -> Shaders.zebraFragment(key.curve)
            FrameEffect.PEAKING -> Shaders.peakingFragment(key.curve)
            else -> when {
                key.denoise == DENOISE_LEVEL_LITE && key.curve -> Shaders.BILATERAL_LITE_CURVE_FS
                key.denoise == DENOISE_LEVEL_LITE -> Shaders.BILATERAL_LITE_FS
                key.denoise == DENOISE_LEVEL_FULL && key.curve -> Shaders.BILATERAL_CURVE_FS
                key.denoise == DENOISE_LEVEL_FULL -> Shaders.BILATERAL_FS
                key.curve -> Shaders.CURVE_FS
                else -> Shaders.PASS_THROUGH_FS
            }
        }
        if (!key.glow) return base
        return Shaders.glowFragment(base, key.effect)
    }

    /**
     * 按**请求键**取一枚 program：先查会话缓存，再按 [nextFallback] 的单调阶梯逐级试。
     *
     * 失败隔离（§3.4）：每个链接失败的键写进 [failedKeys]、下一帧跳过重试；**display 的坏效果
     * 只影响 display 的请求键**——movie 的键永远不会含 ZEBRA/PEAKING，所以它的曲线/降噪不会被
     * 一次"开斑马"连坐掉（旧全局 `curveFailed` 的连坐缺陷）。
     *
     * @return null = 整条阶梯都不可用（连直通都链接不上），该槽本帧不出图
     */
    private fun buildProgram(want: ProgramKey, pass: String): GlProgram? {
        // 请求键本身就是已知坏键 ⇒ 直接从它的下一级开始，不重复一次注定失败的链接
        var candidate: ProgramKey? = if (want in failedKeys) nextFallback(want) else want
        while (candidate != null) {
            val key = candidate
            if (key !in failedKeys) {
                programCache[key]?.let { cached ->
                    if (key != want) {
                        Log.w(TAG_GL, "WotaGl program 回退：pass=$pass 请求 ${want.short()} → ${cached.applied.short()}（缓存命中）")
                    }
                    return cached
                }
                val program = linkProgram(key)
                if (program != null) {
                    putProgram(program)
                    Log.i(TAG_GL, "WotaGl program：pass=$pass ${key.debug()} applied=${key.short()}")
                    if (key != want) {
                        Log.w(TAG_GL, "WotaGl program 回退：pass=$pass 请求 ${want.short()} → ${key.short()}")
                    }
                    return program
                }
                failedKeys += key
                Log.w(TAG_GL, "WotaGl program 回退：pass=$pass 键 ${key.short()} 链接失败，降下一级")
            }
            candidate = nextFallback(key)
        }
        Log.w(TAG_GL, "WotaGl program：pass=$pass 请求 ${want.short()} 整条回退阶梯都不可用——本槽不出图")
        return null
    }

    /** 真正链接一枚 program 并取齐所有 location（[key] 即实际编译的键） */
    private fun linkProgram(key: ProgramKey): GlProgram? {
        // Glow 是 program 维度：开着就用带 vUvGlow 的顶点源（关着时 TEXTURE_VS 一个字节不变）
        val fragment = fragmentFor(key) ?: return null
        val vertex = if (key.glow) Shaders.TEXTURE_VS_GLOW else Shaders.TEXTURE_VS
        val id = link(vertex, fragment)
        if (id == 0) return null
        if (key.denoise != DENOISE_LEVEL_OFF) {
            // 切档留痕（2026-10-26）：0=关 / 1=完整核（9 采样）/ 2=轻量核（5 采样，按负载自动降档）。
            // 真机核对"到底用没用到轻量档"就 grep 这一行，不用重编也不用看帧账
            Log.i(TAG_GL, "画面降噪 program：档=${key.denoise}（0=关 1=完整 2=轻量）")
        }
        return GlProgram(
            applied = key,
            id = id,
            aPosition = GLES20.glGetAttribLocation(id, "aPosition"),
            aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord"),
            uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix"),
            uFrame = GLES20.glGetUniformLocation(id, "uFrame"),
            uStripe = GLES20.glGetUniformLocation(id, "uStripe"),
            uThreshold = GLES20.glGetUniformLocation(id, "uThreshold"),
            uTile = GLES20.glGetUniformLocation(id, "uTile"),
            uTexel = GLES20.glGetUniformLocation(id, "uTexel"),
            uStrength = GLES20.glGetUniformLocation(id, "uStrength"),
            uColor = GLES20.glGetUniformLocation(id, "uColor"),
            uCurve = GLES20.glGetUniformLocation(id, "uCurve"),
            uSigmaR = GLES20.glGetUniformLocation(id, "uSigmaR"),
            uGlowSrc = GLES20.glGetUniformLocation(id, "uGlowSrc"),
            uGlowGain = GLES20.glGetUniformLocation(id, "uGlowGain"),
            uGlowAdd = GLES20.glGetUniformLocation(id, "uGlowAdd"),
            uGlowFreq = GLES20.glGetUniformLocation(id, "uGlowFreq"),
            uGlowBlend = GLES20.glGetUniformLocation(id, "uGlowBlend"),
            uGlowTint = GLES20.glGetUniformLocation(id, "uGlowTint")
        )
    }

    /**
     * 收进会话缓存（access-order LRU，上限 [PROGRAM_CACHE_LIMIT]）。
     *
     * 淘汰**跳过被任一槽引用的项**：把在用的 program 删掉会让那一槽下一帧拿到一个已删的 id。
     * 极端情况下（全部在引用中）宁可暂时超上限，也不删在用的。
     */
    private fun putProgram(program: GlProgram) {
        programCache[program.applied] = program
        while (programCache.size > PROGRAM_CACHE_LIMIT) {
            val victim = programCache.entries.firstOrNull {
                it.value !== displaySlot.program && it.value !== movieSlot.program
            } ?: return
            programCache.remove(victim.key)
            GLES20.glDeleteProgram(victim.value.id)
        }
    }

    /**
     * 曲线表变了才重传。[syncEffectTextures] 每帧都会走到这里（两槽同步的尾部），
     * 不加这道判断就是每帧分配一个 direct buffer + 整表 `glTexImage2D`，
     * 拖动曲线时预览会自己掉帧——本来想验的"跟手"就被我这行改没了。
     */
    private fun ensureCurveTexture() {
        val bytes = curveBytes ?: return
        if (curveTextureId != 0 && curveUploaded === bytes) return
        if (curveTextureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            curveTextureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTextureId)
        // GLES2 里 GL_RGB + GL_FLOAT 不是合法组合，只有 UNSIGNED_BYTE 能一次放下三通道表
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
            ColorCurve.TABLE_SIZE, 1, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE,
            buf
        )
        checkGlError("curveTexImage")
        curveUploaded = bytes
    }

    private fun deleteCurveTexture() {
        if (curveTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(curveTextureId), 0)
            curveTextureId = 0
        }
        curveUploaded = null
    }

    private fun link(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            Log.w(TAG_GL, "glCreateProgram 返回 0")
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return 0
        }
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        // 链接后 shader 可立即删除，二进制留在 program 里
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG_GL, "program 链接失败: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            Log.w(TAG_GL, "glCreateShader 返回 0")
            return 0
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(TAG_GL, "shader 编译失败: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun ensureStripeTexture(density: Int) {
        val safeDensity = ZebraPattern.sanitize(density)
        if (stripeTextureId != 0 && stripeDensity == safeDensity) return
        deleteStripeTexture()
        val bitmap = ZebraPattern.tileBitmap(safeDensity)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // 平铺靠 GL_REPEAT + 着色器 fract；瓦片按整周期绘制，接缝不可见
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        checkGlError("stripeTexImage")
        stripeTextureId = ids[0]
        stripeDensity = safeDensity
    }

    private fun deleteStripeTexture() {
        if (stripeTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(stripeTextureId), 0)
            stripeTextureId = 0
        }
        stripeDensity = 0
    }

    /** 描边色档位 → 线性 RGB（红档与 res/values/colors.xml 的监看红同值） */
    private fun peakingRgb(color: PeakingColor): FloatArray = when (color) {
        PeakingColor.WHITE -> floatArrayOf(1.00f, 1.00f, 1.00f)
        PeakingColor.GREEN -> floatArrayOf(0.12f, 1.00f, 0.28f)
        PeakingColor.BLUE -> floatArrayOf(0.20f, 0.55f, 1.00f)
        PeakingColor.ORANGE -> floatArrayOf(1.00f, 0.60f, 0.05f)
        PeakingColor.RED -> floatArrayOf(1.00f, 0.12f, 0.12f)
    }

    // endregion

    // region 内部实现：释放与工具

    private fun releaseGl() {
        glReady = false
        hasTextureData = false
        // 删除资源需要 current 上下文，用 pbuffer 兜底
        makeCurrent(pbufferSurface)

        // 两槽绑定先松手，再把会话缓存里的 program 一次删光（含被槽引用的那几枚）——
        // 这里是全引擎唯一的 program 释放点，漏登记就是 ID 残留 + 上下文销毁前不回收
        displaySlot.clearBinding()
        movieSlot.clearBinding()
        programCache.values.forEach { GLES20.glDeleteProgram(it.id) }
        programCache.clear()
        failedKeys.clear()
        // 离屏链的 2 纹理 + 2 FBO + 2 program 全在这一次调用里删（内部删完把 target 置 null）
        frostChain?.release()
        frostChain = null
        // 画板的 program 与那枚结果纹理的绑定也在这里收口（新资源必须在此登记删除，14 号计划 §二）
        frostPlatePass?.release()
        frostPlatePass = null
        // 上屏降档（1 纹理 + 1 FBO + 1 program）同一处收口（24 §2.2 的登记义务）
        displayPost?.release()
        displayPost = null
        // Glow 链（3 纹理 + 3 FBO + 2 program）与那枚 1×1 黑纹理同一处收口（24 §1.2 的登记义务）
        glowChain?.release()
        glowChain = null
        if (glowBlackTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(glowBlackTex), 0)
            glowBlackTex = 0
        }
        glowIdentityUv = null
        glowFrameTex = 0
        // 补弧离屏通道（5 张编码尺寸纹理）同理：正常路径在解绑编码面时就放了，
        // 这里兜「引擎直接销毁、没走过解绑」的那条路
        arcMendPass?.release()
        arcMendPass = null
        publishedFrost = null
        frostTableCards = 0
        frostDiagSnapshotPublished = false
        frostDiagTableCards = -1
        frostDiagDrewPlates = -1
        // 引擎没了 ⇒ 屏幕上不可能还有板：撤报，UI 下一帧把旧的纯色 fill 画回来
        FrostCardTable.reportPlatesDrawn(false)
        deleteStripeTexture()
        deleteCurveTexture()
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }

        val target = cameraTargetSurface
        cameraTargetSurface = null
        target?.release()
        val st = inputTexture
        inputTexture = null
        st?.setOnFrameAvailableListener(null)
        st?.release()

        // 直方图迷你 FBO 必须在 **teardownEgl 之前**删（需要现存的当前上下文）；
        // 放在 teardown 之后调用是空操作（上下文已销毁，ID 变 stale）——GPU 资源靠
        // EGL 销毁上下文兜底回收，但字段里的 ID 残留会让下一次误判"已建"
        releaseHistogram()

        teardownEgl()

        onFrameDrawn = null
        onCameraSurfaceReady = null
        Log.i(TAG_GL, "GL 线程资源已释放")
    }

    private fun eglErrorHex(): String = Integer.toHexString(EGL14.eglGetError())

    /** 现有调用点都只要"记日志"，所以它们继续用这个不关心返回值的老签名 */
    private fun checkGlError(where: String) {
        drainGlError(where)
    }

    /**
     * 排空错误队列并**报告条数**：日志口径与 [checkGlError] 逐字相同（同一个 TAG_GL、同一句格式），
     * 只是多返回一个计数——离屏链要靠它把「这帧 GL 报错了」当成停用判据，而不只是留一行日志。
     */
    private fun drainGlError(where: String): Int {
        var count = 0
        var error = GLES20.glGetError()
        while (error != GLES20.GL_NO_ERROR) {
            count++
            Log.w(TAG_GL, "$where: glError 0x${Integer.toHexString(error)}")
            error = GLES20.glGetError()
        }
        return count
    }

    // endregion
}

/**
 * 帧呈现时间戳的"真实时钟落网格"口径（纯函数；另见 [GlRenderEngine.stampEncoderPresentation] 的调用点）。
 *
 * 两头都要，缺一头就是真机缺陷：
 * - **落网格**（`slot × 1e9/fps`）：容器里帧长齐。不落网格、直接用换帧瞬间的系统时钟，会因为相机
 *   帧到达抖动凑不出公共帧长，真机表现为成片 `r_frame_rate` 被报成 90000/1（原注释记录的坑）。
 * - **网格位置取自真实时钟**（`(now - base) / step`）：总时长 = 真实时长。原先按**帧序号**取网格，
 *   隐含"源实到帧率 = 目标帧率"；半精确档（24※）不满足该假设——真机秒表对照：一轮 31.85s 的真实
 *   录制，视频轨合计 34.21s（每段恒 ~180 帧）、音频轨（真实 nanoTime 打戳）合计 30.01s ⇒ 成片匀速
 *   拉长 8~14% 且与音轨逐渐错位。源帧率高于目标时同一网格落进多帧（时间戳相等，播放取最后一枚，
 *   节奏与真相机一致），源端漏帧时网格自然拉出空档（真实缺口如实反映，不压缩）。
 *
 * @param baseNs 本段基准（与帧序号账同时复位）
 * @param nowNs 换取瞬间的真实时钟（`System.nanoTime()` 同域）
 * @param fps 目标帧率（<=0 视为不可用，原样返回 [lastPtsNs]）
 * @param lastPtsNs 本段已写出的最后一枚戳（0 = 还没有）
 * @return 应写进编码器面的戳（ns）；**允许与上一枚相等**（同网格多帧），但绝不小于它
 */
internal fun encoderSlotPtsNs(baseNs: Long, nowNs: Long, fps: Int, lastPtsNs: Long): Long {
    if (fps <= 0) return lastPtsNs
    val step = 1_000_000_000L / fps
    if (step <= 0L) return lastPtsNs
    return slotGridPtsNs(baseNs, nowNs, step, lastPtsNs)
}

/**
 * 网格落戳的**共同内核**（文件私有；[encoderSlotPtsNs] 与 [encoderAdaptivePtsNs] 都走它）。
 *
 * 为什么必须共用：本工程吃过「恒等式假证明」的亏——两份网格算术各写一遍，改一处忘一处，
 * 两条口径就会在某天分叉，而任何「两条口径在真值表上相等」的守卫都测不出那种分叉。
 * 网格算术**只有这一份**是本文件的事实源。
 *
 * 前置条件：调用方保证 [stepNs] > 0（两个调用点各自的不可用分支都已经先把 `step <= 0` 挡掉）。
 * 收 [baseNs]/[nowNs] 而不是收算好的 elapsed，是为了让「退基准」与「夹地板」这两条兜底
 * 和 [encoderSlotPtsNs] 的旧行为**同处一条**——旧函数里它们也在一起，拆开就是分叉的起点。
 */
private fun slotGridPtsNs(baseNs: Long, nowNs: Long, stepNs: Long, lastPtsNs: Long): Long {
    val elapsed = nowNs - baseNs
    val pts = if (elapsed <= 0L) baseNs else baseNs + (elapsed / stepNs) * stepNs
    return if (pts > lastPtsNs) pts else lastPtsNs
}

/**
 * PTS 网格的**源慢接管**口径（纯函数；调用点 [GlRenderEngine.stampEncoderPresentation]）。
 *
 * # 缺陷（2026-10-11 真机量化，ALN-AL00）
 * 名义 24fps 的档上，源实到 ≈23.75fps。[encoderSlotPtsNs] 让槽位按**名义速率**推进、帧按**实到速率**
 * 到达，于是每约 95 帧累计欠一格 ⇒ **每 4.00s 成片一个空洞**（实测 28.79s 片 683 帧 vs 应有 691，
 * 末帧 PTS 落在 689 格 = 28.708s，7 个假空档）。同一个模型解释两台机：WIKO 源 24.48fps > 24 ⇒ 零空洞。
 * 导出是纯 remux，无罪。
 *
 * ⚠ **那个「空洞」不是源的缺口**，是网格锚错速率的失真——源实况是「连续每秒交 23.75 帧、无缺口」。
 * 照实数缺口的 `encoderSlotGap` 因此会把这 7 格记成真实掉帧，而用户看到的是一段没掉帧却打不出
 * 均匀帧长的成片。
 *
 * # 形态 B（采纳）：逐帧自适应，不锁 step'
 * 接管分支即 `PTS = max(base + elapsed, lastPtsNs)`，源 ≥ 名义时**逐字复用旧内核**（不是复制逻辑）。
 * 四条性质同时成立：
 * 1. 相邻差 = `1/23.75` = 42105263ns **恒定**（帧长均匀）；
 * 2. 逐帧 [encoderSlotGap] 恒 0（**无洞**）；
 * 3. 末帧跨度 = 真实时长（见下面的「两处必须写实的修正」第 2 条）；
 * 4. 源快于名义（31fps/24 档、48fps/24 档）时 `frameIndex·step >= elapsed` 恒真 ⇒ 走名义网格
 *    ⇒ **与现行逐字相同**（0 行为变化）。
 *
 * # 形态 A（拒绝）：跑前锁定一个 `step'`、对后续所有帧生效
 * 那需要新状态，且源缓慢漂移时会逐渐失调（锁于开跑时的速率，漂移不跟随）。
 * 本函数是**逐帧**重新判定，接管/不接管随源快慢自然切换，没有任何跨帧状态。
 *
 * # 两处必须写实的修正（本仓有「恒等式假证明」前科，守卫按这两句严格表述写）
 * 1. 「段首样本不足时退化与现行口径逐字相同」**只在 `frameIndex <= 0` 严格成立**（首帧 / 帧率不可用 /
 *    时钟回退三种情形逐字同旧）。早段抖动帧是**同性质**不是**逐字相同**——`frameIndex = 1` 时
 *    只要 `elapsed > step`（源慢过哪怕一格）就已经接管。
 * 2. 「末帧跨度 = 真实时长」严格是 `(n−1)/实际fps + 末帧曝光`，与 `n/实际fps` **差一帧**
 *    （约 42ms 量级）。所以端到端断言必须落在**容差带**（±1 帧长）上，不许把等号当恒等式。
 *
 * @param stepNs 网格步长（ns）>0；整数档是 `1e9/fps`，NTSC 档是 [ntscGridStepNs] 的实数近似步长。
 *   **本函数不认 fps**：NTSC 档的步长不是 `1e9/24`，把 fps 传进来会在该档退回整数网格（本项 #2 的坑）。
 * @param frameIndex 本段已写出的帧数（打戳成功才自增，所以它就是「名义网格应当已到达的格数」判据的来源）
 */
internal fun encoderAdaptivePtsNs(
    baseNs: Long,
    nowNs: Long,
    stepNs: Long,
    lastPtsNs: Long,
    frameIndex: Int
): Long {
    // 档位不可用：与旧口径逐字相同（step<=0 守卫在此原样保留；fps<=0 守卫在调用方
    // stampEncoderPresentation 的入口早退——两处合计等价于旧函数的那两道）
    if (stepNs <= 0L) return lastPtsNs
    // 段首没有样本：与旧口径逐字相同。**必须**早于接管判定——frameIndex=0 时接管谓词
    // `elapsed > 0·step` 对任何正 elapsed 恒真，不挡住就会把首帧推到 base+elapsed（=nowNs）。
    if (frameIndex <= 0) return slotGridPtsNs(baseNs, nowNs, stepNs, lastPtsNs)
    val elapsed = nowNs - baseNs
    // 时钟回退 / 还没起走：退基准再夹地板，与旧口径逐字相同
    if (elapsed <= 0L) return slotGridPtsNs(baseNs, nowNs, stepNs, lastPtsNs)
    val candidate = if (elapsed > frameIndex * stepNs) baseNs + elapsed else slotGridPtsNs(baseNs, nowNs, stepNs, lastPtsNs)
    return if (candidate > lastPtsNs) candidate else lastPtsNs
}

/**
 * 相邻两枚网格戳之间的**空档格数**（纯函数；S1-M2-03 观测，调用点 [GlRenderEngine.stampEncoderPresentation]）。
 *
 * [encoderSlotPtsNs] 只交出戳、把 slot 序号丢了，空档在 App 侧因此不留痕：用户报「掉帧」时
 * 内部没有一个数字能证实或证伪，只能靠外部 ffprobe。这里把「本次跳了几格 − 1」还原出来，
 * 与 `CodecRecorder` 的 `muxPtsFixes`/`muxPtsMaxBackUs` 同一记账口径（计次 + 最大量）。
 *
 * 口径与 M2-01 的定版裁决一致：**如实反映真实缺口，不压缩**——所以本函数只数，不改戳。
 *
 * @param lastPtsNs 上一枚已写出的戳（`<= 0` = 本段首帧，没有上一枚 ⇒ 0）
 * @param ptsNs 本次应写出的戳（小于上一枚 = 未前进 ⇒ 0）
 * @param fps 目标帧率（`<= 0` 不可用 ⇒ 0）
 * @return 空掉的网格格数；无空档（同格多帧 / 满一格 / 首帧 / 档位不可用）一律 0
 */
internal fun encoderSlotGap(lastPtsNs: Long, ptsNs: Long, fps: Int): Long {
    if (fps <= 0 || lastPtsNs <= 0L || ptsNs < lastPtsNs) return 0L
    val step = 1_000_000_000L / fps
    if (step <= 0L) return 0L
    val jumped = (ptsNs - lastPtsNs) / step
    return if (jumped > 1L) jumped - 1L else 0L
}

/**
 * 段内**实测帧率**（纯函数，单位「厘帧/秒」，成文时除 100 取两位小数）。
 *
 * 为什么单独一个纯函数而不是内联在 [stampObsLine] 前面算：它是 `n / span` 的除法，
 * `n = 0` 或 `span <= 0`（时钟回退、段还没起走）必须各报 0 而不是崩或报天文数字，
 * 这条边界要在 JVM 里能断。同段的口径提醒见 [stampObsLine]。
 *
 * ⚠ 读数比「源真实帧率」**偏高约一帧的量**（≈0.04fps @23.75）：`span` 盖的是第 0 帧到第 n−1 帧的
 * 到达时刻，末帧的曝光不在跨度里，所以 `n / span` 比 `n / (n/fps)` 大。不是 bug，是"帧数除以跨度"
 * 的固有形态——守卫按这个事实写，不许把它当成恒等式。
 */
internal fun stampAchievedCentiFps(frameIndex: Int, spanNs: Long): Long =
    if (frameIndex <= 0 || spanNs <= 0L) 0L else frameIndex.toLong() * 100_000_000_000L / spanNs

/**
 * 段收尾观测行的成文（纯函数；S1-M2-02/03/06 三联 + 实到帧率的唯一出口 [GlRenderEngine.drainStampObs] 只调它）。
 *
 * 抽成纯函数是为了让「均值/峰值/空格/跨距/失败数/实到**真的落在那一行里**」可被 JVM 断言——
 * 内联在 `Log.i` 的字符串里时，删掉任一字段测试仍全绿，正是本工程吃过多次的假绿。
 * 均值口径：先按样本数求平均再换算成 ms（不先换算再平均，否则每帧截断误差会累积）。
 *
 * @param ageCount 帧龄样本数；0 时均值报 0（无样本不是 0 延迟，日志里靠 `样本=0` 自明）
 * @param achievedCentiFps 实到帧率（厘帧/秒，整数传入以免浮点格式化踩到 locale：小数点会变逗号）
 */
internal fun stampObsLine(
    why: String,
    ageSumNs: Long,
    ageCount: Long,
    ageMaxNs: Long,
    gapSlots: Long,
    gapMaxSlots: Long,
    achievedCentiFps: Long,
    failCount: Int
): String {
    val meanMs = if (ageCount > 0L) ageSumNs / ageCount / 1_000_000L else 0L
    val fpsText = "${achievedCentiFps / 100}.${(achievedCentiFps % 100).toString().padStart(2, '0')}"
    return "打戳观测[$why] 帧龄漂移 均值=${meanMs}ms 峰值=${ageMaxNs / 1_000_000L}ms 样本=$ageCount " +
        "实到=${fpsText}fps 空格=${gapSlots}格 最大跨距=${gapMaxSlots}格 写戳失败=$failCount"
}

/**
 * FloatArray 拷成 GL 需要的直接缓冲（GLES20 只有 Buffer 版 glVertexAttribPointer 重载）。
 * internal 而非 private：[FrostBlurChain] 的恒等纹理坐标缓冲也要走同一条构造路，不许另抄一份。
 */
internal fun nativeFloatBuffer(values: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
