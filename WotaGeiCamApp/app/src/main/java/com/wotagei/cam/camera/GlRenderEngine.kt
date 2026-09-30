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
import android.util.Log
import android.view.Surface
import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.PeakingColor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
class GlRenderEngine : PreviewSink, DisplaySurfaceReceiver, FrostBlurProvider,
    SurfaceTexture.OnFrameAvailableListener {

    companion object {
        /** EGL14 未暴露该常量，取 Khronos 原值 EGL_OPENGL_ES3_BIT_KHR */
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

        /** 上屏节流上限（03 文档 §3.1）：只作用于窗口 pass，编码器 pass 一帧不丢 */
        private const val MAX_DISPLAY_FPS = 60f

        /** 峰值对焦邻域取样步长（UV 空间，03 文档 §3.6 的 texel≈0.002 档位） */
        private const val PEAKING_TEXEL = 0.002f
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

    private var encoderNative: Surface? = null

    @Volatile
    private var encoderWidth = 0

    @Volatile
    private var encoderHeight = 0

    /** 目标帧率：>0 时给编码器面写均匀呈现时间戳，避免容器报出非整数帧率 */
    @Volatile
    private var encoderFps = 0

    /** 已交给编码器的帧序号，用于算均匀时间戳 */
    private var encoderFrameIndex = 0

    /**
     * 均匀时间戳的基准（ns）。必须留在 `System.nanoTime()` 同一时间域：
     * EGL 默认就是按换帧瞬间的系统时钟打 PTS，从 0 起算会和 MediaRecorder
     * 音频侧的时钟跨域，muxer 直接产出不可用文件（真机表现为录制后无成片）。
     */
    private var encoderBaseNs = 0L

    private var inputTexture: SurfaceTexture? = null
    private var oesTextureId = 0
    private var hasTextureData = false

    private var programId = 0
    private var programEffect = FrameEffect.NONE

    /** 效果档编译失败：记住它，避免每帧重建同一坏 program 拖死帧率 */
    private var blacklistedEffect: FrameEffect? = null

    /** 连直通 program 都链接不上（驱动异常）：停止逐帧重试，否则每帧白烧一次编译 */
    private var passThroughFailed = false

    private var stripeTextureId = 0
    private var stripeDensity = 0

    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTexMatrixLoc = -1
    private var uFrameLoc = -1
    private var uStripeLoc = -1

    /** 曲线查表纹理（256×1 GL_RGB，绑在单元 2）。null 表示曲线关着 */
    private var curveTextureId = 0

    @Volatile
    private var curveBytes: ByteArray? = null

    /** 带曲线的 program 链接失败：记住它，避免每帧重建同一坏 program 拖死帧率 */
    private var curveFailed = false

    private var programCurve = false

    /** 已经传上去的那份表。`setCurve` 每次都烘出新数组，所以按引用比就能判断"表变了没" */
    private var curveUploaded: ByteArray? = null

    private var uCurveLoc = -1
    private var uThresholdLoc = -1
    private var uTileLoc = -1
    private var uTexelLoc = -1
    private var uStrengthLoc = -1
    private var uColorLoc = -1

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

    private val stMatrix = FloatArray(16)

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
        val t = HandlerThread("WotaGlRender", Process.THREAD_PRIORITY_DISPLAY)
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
     */
    fun setOutputSurface(surface: Surface?, widthPx: Int, heightPx: Int, frameRate: Int = 0) {
        if (released.get()) return
        encoderFps = if (surface == null) 0 else frameRate.coerceAtLeast(0)
        postGl { replaceEncoderSurface(surface, widthPx, heightPx) }
    }

    /** 编码器面是否已绑定（录制侧可据此决定何时 start） */
    fun isOutputSurfaceBound(): Boolean = encoderEglSurface != null

    /** 编码器面尺寸（px），未绑定为 0x0 */
    fun outputSurfaceSize(): Pair<Int, Int> = encoderWidth to encoderHeight

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
     * 应用 RGB 曲线；传 null 或恒等曲线即关闭。
     *
     * 烘焙在调用线程做（`TABLE_SIZE*3` 次插值，微秒级），GL 线程只拿字节表，
     * 避免拖动控制点时把重计算压到渲染线程上掉帧。
     */
    fun setCurve(stack: CurveStack?) {
        if (released.get()) return
        curveBytes = stack?.takeIf { !it.isPassthrough }?.bakeBytes()
        postGl {
            // 每次显式改曲线都重新给一次编译机会：可能是上一版表触发的偶发失败
            curveFailed = false
            syncEffect()
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

        glReady = true
        syncEffect()
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
        if (EGL14.eglSwapBuffers(eglDisplay, surface)) return true
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
        destroyEglSurface(encoderEglSurface)
        encoderEglSurface = null
        encoderNative = null
        encoderWidth = 0
        encoderHeight = 0
        if (surface == null) {
            Log.i(TAG_GL, "编码器面已解除")
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

        syncEffect()
        if (programId == 0) return

        drawEncoderPass()
        // 开关意图从矩形表搬进门里——放在编码 pass **之后**：翻开关那一次的着色器编译是毫秒级，
        // 不许让它挡在编码器的 swap 前面（那是成片的一帧）
        adoptFrostIntentFromTable()
        drawFrostPass(hasFrame)
        drawWindowPass(stateOnly)

        onFrameDrawn?.invoke(lastDrawnTimestampNs)
        checkGlError("drawFrame")
    }

    private fun drawEncoderPass() {
        val encoder = encoderEglSurface ?: return
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
     * 给编码器面写**均匀**呈现时间戳（帧序号 × 1e9/fps）。
     * 不写的话 EGL 用换帧瞬间的系统时钟当 PTS，而相机帧到达本身有抖动，
     * 容器里就凑不出公共帧长 —— 真机表现为成片 `r_frame_rate` 被报成 90000/1。
     */
    private fun stampEncoderPresentation(encoder: EGLSurface) {
        val fps = encoderFps
        if (fps <= 0) return
        val display = eglDisplay
        if (display === EGL14.EGL_NO_DISPLAY) return
        val ptsNs = encoderBaseNs + encoderFrameIndex * (1_000_000_000L / fps)
        runCatching { EGLExt.eglPresentationTimeANDROID(display, encoder, ptsNs) }
            .onFailure { Log.w(TAG_GL, "写呈现时间戳失败：${it.message}") }
        encoderFrameIndex++
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
        if (!stateOnly && windowThrottled()) return
        if (!makeCurrent(window)) {
            dropWindowSurface()
            return
        }
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
        // 预览 blit 之后、swap 之前：按矩形表把霜底板贴上去（#84 步骤 2，A2 混合）。
        // 这一步只在窗口面里发生，[drawEncoderPass] 走的是另一条完整独立的路（同一条"绝不碰霜"红线）
        drawFrostPlates()
        if (!swap(window)) dropWindowSurface() else lastWindowSwapNs = System.nanoTime()
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
            st.getTransformMatrix(stMatrix)
            // 矩阵自带的旋转分量决定「抵消多少」，变了就必须重算两路纹理坐标（真机首帧才拿得到矩阵）
            if (orientation.setStMatrix(stMatrix)) {
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

    private fun drawPass(encoderPass: Boolean) {
        if (programId == 0) return
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        if (uFrameLoc >= 0) GLES20.glUniform1i(uFrameLoc, 0)
        if (uTexMatrixLoc >= 0) GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, stMatrix, 0)

        if (programCurve) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTextureId)
            if (uCurveLoc >= 0) GLES20.glUniform1i(uCurveLoc, 2)
        }

        if (programEffect == FrameEffect.ZEBRA) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stripeTextureId)
            if (uStripeLoc >= 0) GLES20.glUniform1i(uStripeLoc, 1)
            if (uThresholdLoc >= 0) GLES20.glUniform1f(uThresholdLoc, wantZebraThreshold)
            val repeat = ZebraPattern.tileRepeat(wantZebraDensity)
            if (uTileLoc >= 0) GLES20.glUniform2f(uTileLoc, repeat, repeat)
        } else if (programEffect == FrameEffect.PEAKING) {
            if (uTexelLoc >= 0) GLES20.glUniform2f(uTexelLoc, PEAKING_TEXEL, PEAKING_TEXEL)
            if (uStrengthLoc >= 0) GLES20.glUniform1f(uStrengthLoc, wantPeakingStrength)
            val rgb = peakingRgb(wantPeakingColor)
            if (uColorLoc >= 0) GLES20.glUniform3f(uColorLoc, rgb[0], rgb[1], rgb[2])
        }

        val coords = if (encoderPass) encoderTexCoordBuffer else displayTexCoordBuffer
        buildTexCoords(encoderPass)
        if (aPositionLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        }
        if (aTexCoordLoc >= 0) {
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, coords)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
        val deg = if (encoderPass) orientation.stRotationDegrees else orientation.appliedDegrees()
        val mirror = !encoderPass && orientation.mirrored
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

    // region 内部实现：program 与纹理（单效果槽）

    private fun syncEffect() {
        // 「程序尚未建出来」必须和「效果没变」分开判断：初值 programEffect 就是 NONE，
        // 只看效果是否相同会让直通 program 永远不编译，预览整块黑（真机踩过）。
        if (programId == 0 && passThroughFailed) return
        val requested = wantEffect
        val target = if (requested == blacklistedEffect) FrameEffect.NONE else requested
        val curveOn = curveBytes != null && !curveFailed
        if (programId != 0 && target == programEffect && curveOn == programCurve) {
            if (target == FrameEffect.ZEBRA) ensureStripeTexture(wantZebraDensity)
            // 开关状态没变、只是曲线形状改了：重传表即可，重建 program 会闪一帧
            if (programCurve) ensureCurveTexture()
            return
        }
        if (programId != 0) {
            //  单效果槽：切换时先删旧 program 再建新的，同一时刻只亮一个
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        programEffect = FrameEffect.NONE
        programCurve = false
        buildProgram(target, curveOn)
        if (programEffect != FrameEffect.ZEBRA) deleteStripeTexture()
        if (programEffect == FrameEffect.ZEBRA) ensureStripeTexture(wantZebraDensity)
        if (programCurve) ensureCurveTexture() else deleteCurveTexture()
    }

    private fun buildProgram(effect: FrameEffect, curveOn: Boolean) {
        val fragment = when (effect) {
            FrameEffect.ZEBRA -> Shaders.zebraFragment(curveOn)
            FrameEffect.PEAKING -> Shaders.peakingFragment(curveOn)
            else -> if (curveOn) Shaders.CURVE_FS else Shaders.PASS_THROUGH_FS
        }
        var id = link(Shaders.TEXTURE_VS, fragment)
        var effective = effect
        var curveApplied = curveOn
        if (id == 0 && curveOn) {
            // 曲线版编不出来时先退曲线、保住当前效果；只有直通本身失败才允许不出图
            Log.w(TAG_GL, "曲线 program 链接失败，退回不带曲线的 $effect")
            curveFailed = true
            curveApplied = false
            val plain = when (effect) {
                FrameEffect.ZEBRA -> Shaders.zebraFragment(false)
                FrameEffect.PEAKING -> Shaders.peakingFragment(false)
                else -> Shaders.PASS_THROUGH_FS
            }
            id = link(Shaders.TEXTURE_VS, plain)
        }
        if (id == 0 && effective != FrameEffect.NONE) {
            // 少数驱动不支持片元 highp：退化成直通而不是黑屏，并记住该档不再重试
            Log.w(TAG_GL, "效果 $effective 的 program 链接失败，退回直通")
            blacklistedEffect = effective
            effective = FrameEffect.NONE
            curveApplied = false
            id = link(Shaders.TEXTURE_VS, Shaders.PASS_THROUGH_FS)
        }
        if (id == 0) {
            Log.w(TAG_GL, "直通 program 链接失败，本引擎不出图")
            passThroughFailed = true
            return
        }
        programId = id
        programEffect = effective
        programCurve = curveApplied
        aPositionLoc = GLES20.glGetAttribLocation(id, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(id, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(id, "uTexMatrix")
        uFrameLoc = GLES20.glGetUniformLocation(id, "uFrame")
        uStripeLoc = GLES20.glGetUniformLocation(id, "uStripe")
        uThresholdLoc = GLES20.glGetUniformLocation(id, "uThreshold")
        uTileLoc = GLES20.glGetUniformLocation(id, "uTile")
        uTexelLoc = GLES20.glGetUniformLocation(id, "uTexel")
        uStrengthLoc = GLES20.glGetUniformLocation(id, "uStrength")
        uColorLoc = GLES20.glGetUniformLocation(id, "uColor")
        uCurveLoc = GLES20.glGetUniformLocation(id, "uCurve")
    }

    /**
     * 曲线表变了才重传。`syncEffect` 每帧都会走到这里（早退分支也调它），
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

        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        programEffect = FrameEffect.NONE
        programCurve = false
        // 离屏链的 2 纹理 + 2 FBO + 2 program 全在这一次调用里删（内部删完把 target 置 null）
        frostChain?.release()
        frostChain = null
        // 画板的 program 与那枚结果纹理的绑定也在这里收口（新资源必须在此登记删除，14 号计划 §二）
        frostPlatePass?.release()
        frostPlatePass = null
        publishedFrost = null
        frostTableCards = 0
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
