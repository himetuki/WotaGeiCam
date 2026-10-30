package com.wotagei.cam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import android.os.SystemClock
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import com.wotagei.cam.diag.Diag as Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wotagei.cam.R
import com.wotagei.cam.camera.CameraController
import com.wotagei.cam.core.UIOrientation
import com.wotagei.cam.media.WotaNav
import com.wotagei.cam.player.ClipRule
import com.wotagei.cam.player.CompareScreen
import com.wotagei.cam.player.PlayerScreen
import com.wotagei.cam.player.TrackRule
import com.wotagei.cam.record.StalePendingSweep
import com.wotagei.cam.ui.anim.EXTRA_MERGE_HOOK
import com.wotagei.cam.ui.anim.MergeDebugHook
import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.anim.WotaMotionProvider
import com.wotagei.cam.ui.theme.TextScaleLayer
import com.wotagei.cam.ui.theme.WotaTheme

/** 本层新增的两条路由（其余路由用 media 包 [WotaNav] 常量，避免两处定义） */
const val ROUTE_CAMERA = "camera"
const val ROUTE_SETTINGS = "settings"
/** 「编辑控件」页（13 号计划第 5 条）：沉浸（隐藏系统栏）跟着录制页；方向自 2026-10-02 起所有页统一按设置锁 */
const val ROUTE_HUD_EDITOR = "hudEditor"

/**
 * 单 Activity + NavHost（06 文档 §1）。
 *
 * 首帧只做三件事：起导航、请求权限、按目的地切方向与沉浸 —— 相机/Room/媒体库都在各自页面里延迟初始化，
 * 满足「冷启动 < 900ms」（06 文档 §6）。
 */
class MainActivity : ComponentActivity() {

    /**
     * 窗口级「常亮 + 高亮」的驱动（唯一真源，见 [WindowKeepOnDriver]）。
     * 放在 Activity 这一层：窗口归它所有，且驱动时机是 NavController 的目的地回调，
     * 与任何页面的组合/重组生命周期无关。
     */
    private val windowKeepOn = WindowKeepOnDriver()

    /**
     * 亮度渐变驱动（29 §5.3：启动亮屏 + 起播渐亮；见 [BrightnessRamp]）。
     * 与 [windowKeepOn] 由同一个目的地回调先后驱动——离开页面时 keepOn 先写回进页前原值、
     * ramp 再写回起坡前原值（两值相等，同线程顺序执行无竞态）。
     */
    private val brightnessRamp = BrightnessRamp()

    /**
     * 目的地变化时驱动窗口常亮/高亮与亮度渐变（由 [WotaRoot] 注册的 OnDestinationChangedListener
     * 回调）：`navigate` / `popBackStack` / `setGraph` 都会同步走到这里，注册时还会立刻回放当前
     * 目的地，所以冷启与"媒体库/设置/编辑页往返"都覆盖得到。
     */
    internal fun onRouteForWindowKeepOn(route: String?) {
        windowKeepOn.apply(window, route)
        brightnessRamp.onRoute(window, route, WotaSettings.of(this))
    }

    /**
     * 播放页首次起播（29 §5.3-②）：渐变的**触发**在页面（它知道何时起播），写入仍收口在
     * 窗口拥有者这一层。每次起播都会被调，武装闸（每页一次）在 [BrightnessRamp] 里收口。
     */
    internal fun onPlayerPlaybackStarted() {
        brightnessRamp.onPlayerPlaybackStarted(window, WotaSettings.of(this))
    }

    /**
     * 预览自愈 watchdog（2026-10-29 黑屏取证 → 2026-10-30 三次修订）：**Activity 级**兜底。
     *
     * 为什么不能只放在 [CameraScreen] 的组成里：黑屏的一种可能形态是页面卡在导航中间态、
     * 组合不再重组——那时页面内的 LaunchedEffect 也停摆，看门狗等于不存在。这一层只要
     * Activity 在前台就会跑，与任何页面的组合生命周期无关。
     *
     * 判据是**两条单调心跳**（r31 只判了第一条，所以"相机在出帧、屏上不换帧"这一档永远漏）：
     * - 相机心跳 `frameResultCount`：不涨 = 相机会话死 ⇒ 4s 重启预览、10s 整条重建；
     * - 上屏心跳 `displayFrameCount`：不涨而相机在涨 = 上屏链死 ⇒ 2.5s 重挂承载面，
     *   连续三次仍不上屏就整条重建 + 再重挂。
     * 两条都只在当前路由是录制页时判（其他页面相机可以合法停着）。
     */
    private var previewWatchJob: Job? = null
    private var watchRoute: String? = null

    /** 最近一次见到的录制页门面（onStop 收相机用：app 退后台时保活的相机必须立刻放掉） */
    internal var previewCtrl: com.wotagei.cam.camera.CameraController? = null

    /**
     * 录制页**组合**是否在场（页面进出场回报，非路由态）。2026-10-30 黑屏定案：连点返回的
     * 导航竞态能把录制页组合拆掉而目的地不变（r38 日志 47.465→48.256 实录）——整窗黑、
     * 无控件、应用活着。此时唯一的自救是重建 Activity（ViewModel 存活，相机随页面重组回来）。
     */
    @Volatile
    internal var cameraPageComposed = false

    internal fun onCameraPageComposed() {
        cameraPageComposed = true
    }

    internal fun onCameraPageDisposed() {
        cameraPageComposed = false
    }

    internal fun onRouteForPreviewWatch(route: String?, ctrl: com.wotagei.cam.camera.CameraController?) {
        watchRoute = route
        if (route != ROUTE_CAMERA) {
            previewWatchJob?.cancel()
            previewWatchJob = null
            return
        }
        // 路由是录制页但这拍拿不到 ctrl（冷启/快速导航竞态里 backStackEntry 可能还不在）：
        // 不许把已经在跑的那条顺手停掉——页面会拿手里的 ctrl 自己重新布防。
        if (ctrl == null) return
        if (previewWatchJob?.isActive == true) return
        previewWatchJob = lifecycleScope.launch {
            var camLast = -1L
            var camGrowAtMs = SystemClock.elapsedRealtime()
            var dispLast = -1L
            var dispGrowAtMs = SystemClock.elapsedRealtime()
            // 上屏判据要等相机真的出过帧才启用（相机还没起来时"屏上没帧"是必然的）
            var camSeenFrames = false
            var restarted = false
            var reattachTries = 0
            var pageGoneSinceMs = 0L
            while (isActive) {
                delay(1000)
                if (watchRoute != ROUTE_CAMERA) break
                val nowMs = SystemClock.elapsedRealtime()
                // 只在真正可见时判（后台/其它页不算）
                if (lifecycle.currentState != Lifecycle.State.RESUMED) {
                    camGrowAtMs = nowMs
                    dispGrowAtMs = nowMs
                    camSeenFrames = false
                    restarted = false
                    reattachTries = 0
                    continue
                }
                // 黑屏竞态兜底（2026-10-30 定案）：目的地还是录制页、但页面组合已被
                // 导航竞态拆掉（连点返回）⇒ 屏幕整窗黑且任何相机侧自愈都无意义（组合
                // 没了）。持续 3s 重建 Activity——ViewModel 存活，页面重组后相机自己回来。
                if (!cameraPageComposed) {
                    if (pageGoneSinceMs == 0L) pageGoneSinceMs = nowMs
                    else if (nowMs - pageGoneSinceMs >= 3_000L) {
                        Log.w("WotaWatch", "录制页组合已销毁而目的地未变（连点返回竞态）⇒ 重建 Activity 自救")
                        recreate()
                        return@launch
                    }
                } else {
                    pageGoneSinceMs = 0L
                }
                val cam = ctrl.frameResultCount()
                if (cam != null && cam != camLast) {
                    if (!camSeenFrames) {
                        camSeenFrames = true
                        dispGrowAtMs = nowMs
                    }
                    camLast = cam
                    camGrowAtMs = nowMs
                    restarted = false
                }
                val disp = ctrl.displayFrameCount()
                if (disp != null && disp != dispLast) {
                    dispLast = disp
                    dispGrowAtMs = nowMs
                    reattachTries = 0
                }
                val camDeadMs = nowMs - camGrowAtMs
                if (camDeadMs >= 4_000L) {
                    if (cam == null) {
                        Log.w("WotaWatch", "Activity 级预览自愈：引擎不在 ⇒ 重新绑定")
                        ctrl.currentSink()?.let { ctrl.bind(it) }
                        camGrowAtMs = nowMs
                        dispGrowAtMs = nowMs
                        camSeenFrames = false
                    } else if (!restarted) {
                        restarted = true
                        Log.w("WotaWatch", "Activity 级预览自愈：${camDeadMs}ms 无相机帧 ⇒ 重启预览")
                        ctrl.restartPreview()
                        dispGrowAtMs = nowMs
                    } else if (camDeadMs >= 10_000L) {
                        Log.w("WotaWatch", "Activity 级预览自愈：10s 仍无帧 ⇒ 整条重建引擎")
                        ctrl.currentSink()?.let { ctrl.forceRebind(it) }
                        camGrowAtMs = nowMs
                        dispGrowAtMs = nowMs
                        camSeenFrames = false
                        restarted = false
                    }
                    continue
                }
                // 上屏侧：相机在出帧而屏上不换帧（r31 的盲区）——重启相机没用，只能重挂面
                if (disp != null && camSeenFrames && nowMs - dispGrowAtMs >= 2_500L) {
                    reattachTries++
                    Log.w(
                        "WotaWatch",
                        "Activity 级预览自愈：相机在出帧但上屏无帧 ${nowMs - dispGrowAtMs}ms" +
                            "（hasSurface=${ctrl.hasDisplaySurface()} 第${reattachTries}次）⇒ 重挂显示面"
                    )
                    dispGrowAtMs = nowMs
                    if (reattachTries >= 3) {
                        Log.w("WotaWatch", "Activity 级预览自愈：重挂显示面无效 ⇒ 整条重建引擎 + 再重挂")
                        ctrl.currentSink()?.let { ctrl.forceRebind(it) }
                        camGrowAtMs = nowMs
                        camSeenFrames = false
                        reattachTries = 0
                    } else {
                        ctrl.requestDisplayReattach()
                    }
                }
            }
        }
    }

    override fun onStop() {
        // app 退到后台：把"被留在别的页后面"的保活相机立刻放掉（前台往返才保活）。
        // 录制页正在前台时不能在这里收——那条路由生命周期 ON_PAUSE/ON_RESUME 老路负责。
        previewCtrl?.onAppBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        // 与 onCreate 配对（2026-10-30）：isFinishing=false 的 destroy = 配置变更/容器重建，
        // 黑屏窗口若与这行同拍出现，"整窗黑"就是重建期产物，与相机/GL 无关
        com.wotagei.cam.diag.Diag.i("WotaNav", "Activity onDestroy（isFinishing=$isFinishing）")
        // 诊断日志的「上一会话结束于」留痕：正常退出与"被系统杀/崩溃"因此可区分
        // （下次冷启会把这行写进日志——排障时第一个要回答的就是"上次是正常退出的吗"）
        if (isFinishing) com.wotagei.cam.diag.Diag.markCleanExit()
        brightnessRamp.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Activity 重建留痕（2026-10-30 黑屏取证）：「整窗变黑但应用活着、返回正常」的候选之一
        // 是配置变更/容器导致的 Activity 重建——重建期窗口会黑一拍。有没有重建、是不是配置变更
        // （重建=true），这行直接写死；与 WotaNav 目的地行配对读。
        com.wotagei.cam.diag.Diag.i(
            "WotaNav",
            "Activity onCreate（重建=${savedInstanceState != null}）"
        )
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyMergeHook(intent)
        applyFrostProbe(intent)
        applyCompareProbe(intent)
        // 冷启维护清扫（一次性、后台线程）：把历史遗留的 `.pending-<id>-` 条目收干净——成片去掉
        // pending 段（它还带着难看名字挂在相册里）、无主/重复的 sidecar 档案删除。三条安全边界
        // （只认自己的命名形态 / 只碰 1 小时以上的 / 进程内只跑一次）与「成片永不删」红线见
        // [StalePendingSweep] 类注。只在真冷启跑：配置变更重建复用同一进程，没必要再扫一遍。
        if (savedInstanceState == null) {
            Thread({
                runCatching { StalePendingSweep.runOnce(applicationContext, StalePendingSweep.outputDir()) }
                    .onFailure { Log.w("WotaSweep", "维护清扫失败：${it.message}") }
                // #4 导出链 pending 行救援：剪辑/选轨 commit 全败保留的成片行在这里收录进相册。
                // 目录由 ui 层传入（record 包不得反向依赖 player 包的 ClipRule/TrackRule）
                runCatching {
                    StalePendingSweep.rescuePendingRows(
                        applicationContext,
                        listOf(
                            externalVideoDir(ClipRule.RELATIVE_PATH),
                            externalVideoDir(TrackRule.RELATIVE_PATH),
                            StalePendingSweep.outputDir()
                        )
                    )
                }.onFailure { Log.w("WotaSweep", "pending 行救援失败：${it.message}") }
            }, "wota-sweep").apply { isDaemon = true }.start()
        }
        setContent {
            WotaTheme {
                val prefs = remember { WotaSettings.of(this) }
                var motion by remember { mutableStateOf(WotaSettings.motionMode(prefs)) }
                WatchMotion(prefs) { motion = it }
                WotaMotionProvider(mode = motion) { WotaRoot() }
            }
        }
    }

    /**
     * `launchMode="singleTask"`：应用已在前台时，第二次 `am start` 只送新 intent、不重建 Activity。
     * 换档（p=0.25 → 0.5）靠的就是这一条，所以这里必须收下新 intent，否则只有冷启动那一次生效。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyMergeHook(intent)
        applyFrostProbe(intent)
        applyCompareProbe(intent)
    }

    /**
     * #73 取证钩子**唯一**的写入入口：adb 的 intent extra（[EXTRA_MERGE_HOOK]）。
     *
     * 没有第二个写入方——设置页没有这一行、没有可误触的手势、不写 prefs。
     * extra 缺失（从桌面图标或最近任务重进）就是关闭态，所以"进程重启即失效"之外还多一条
     * "重新进一次就失效"，取证跑完不需要手动清。正式的 release/debug 变体里 [MergeDebugHook.applySpec]
     * 只能得到关闭态（闸门在 `mergeHookArgsOf`），这条调用点也就是零成本。
     */
    private fun applyMergeHook(intent: Intent?) {
        MergeDebugHook.applySpec(intent?.getStringExtra(EXTRA_MERGE_HOOK))
    }

    /** #84 霜探针的唯一写入入口：与 [applyMergeHook] 同一套纪律（见 [FrostProbe] 的类注释） */
    private fun applyFrostProbe(intent: Intent?) {
        FrostProbe.apply(intent?.getBooleanExtra(EXTRA_FROST_PROBE, false) == true)
    }

    /** 对比播放黑屏探针的唯一写入入口：与 [applyFrostProbe] 同一套纪律（见 [CompareProbe] 的类注释） */
    private fun applyCompareProbe(intent: Intent?) {
        CompareProbe.apply(intent?.getBooleanExtra(EXTRA_COMPARE_PROBE, false) == true)
    }
}

/** 设置页改动画风格后立即生效：监听 SharedPreferences */
@Composable
private fun WatchMotion(prefs: SharedPreferences, onChange: (MotionMode) -> Unit) {
    DisposableEffect(prefs) {
        @Suppress("DEPRECATION")
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == WotaSettings.KEY_MOTION) onChange(WotaSettings.motionMode(prefs))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

@Composable
@Suppress("LongMethod")
private fun WotaRoot() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val nav: NavHostController = rememberNavController()
    var showPermissionPrompt by remember { mutableStateOf(false) }
    // 各屏文本高度独立：prefs 读值即当前设置，回本页时重组生效
    val prefs = remember(context) { WotaSettings.of(context) }

    // 页内返回防抖（2026-10-30 黑屏定案）：设置页返回箭头连点多下时，第二下 pop 会落在
    // 上一个 pop 的转场中途，把刚进场的录制页组合连带拆掉——目的地不变、内容已销毁
    // = 整窗黑、无控件、系统返回仍可用（r38 日志 47.465→48.256 实录）。400ms 内的
    // 第二次 pop 一律忽略；转场动画本身 ~300ms，正常操作节奏不受影响。
    var lastPopAt by remember { mutableStateOf(0L) }
    fun popBackStackDebounced() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPopAt < 400L) return
        lastPopAt = now
        nav.popBackStack()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // 任一权限被拒即给「去设置」出口（06 文档 §1 权限策略）——但媒体读取是「全量或部分」
        // 二选一：Android 14+ 用户选「选择照片和视频」时 READ_MEDIA_VIDEO=false 而
        // READ_MEDIA_VISUAL_USER_SELECTED=true，这两条都拿到 false 才算拒；只认前一条
        // 会把用户明确选过的部分授权当场误判成拒绝
        val mediaKeys = grants.keys.filter { it in MEDIA_PERMISSION_KEYS }
        val otherDenied = grants.entries.filter { it.key !in mediaKeys }.any { !it.value }
        val mediaDenied = mediaKeys.isNotEmpty() && mediaKeys.all { grants.getValue(it) == false }
        if (otherDenied || mediaDenied) showPermissionPrompt = true
    }
    LaunchedEffect(Unit) {
        val missing = missingStartupPermissions(context)
        if (missing.isNotEmpty()) launcher.launch(missing)
    }

    // 方向与沉浸随目的地切换：方向所有页都按设置页「默认方向」锁（用户 2026-10-02 指令）；
    // HUD 页（录制页 + 编辑控件页）隐藏系统栏沉浸，其余页显示
    DisposableEffect(nav, activity) {
        // 窗口归 MainActivity 所有：常亮/高亮也走这一条目的地回调（单一真源见 ui/WindowKeepOn.kt），
        // 与「某个页面何时重组/何时离开组合」彻底解耦
        val windowOwner = activity as? MainActivity
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            // 目的地留痕（2026-10-30 黑屏取证）：黑屏投诉里最关键的问题就是"黑的那一屏是哪个
            // 页面"——状态栏可见与否只能缩小范围，这一行直接写名。一次导航一行，量级可忽略。
            com.wotagei.cam.diag.Diag.i("WotaNav", "目的地 → ${destination.route}")
            applyPageMode(activity, destination.route)
            windowOwner?.onRouteForWindowKeepOn(destination.route)
            // 页面保活旗标（2026-10-29 手动白平衡绿帧 / 2026-10-30 黑屏三次修订扩面）：
            // 此监听在 navigate/popBackStack 时**同步触发、先于旧页 dispose 与 ON_PAUSE**，
            // 旗标读到的新值即时生效。口径：离开录制页就保活——设置页无限期（r28 既有语义），
            // 其他前台页给宽限（到点自动关，见 CameraController.PAGE_KEEP_ALIVE_MS）；
            // 回到录制页立刻解除。相机 entry 不在栈上时按 false 走老路。
            val cameraCtrl = runCatching {
                CameraController.of(nav.getBackStackEntry(ROUTE_CAMERA))
            }.getOrNull()
            if (cameraCtrl != null) {
                windowOwner?.previewCtrl = cameraCtrl
                if (destination.route == ROUTE_CAMERA) {
                    cameraCtrl.cancelPreviewKeepAlive()
                } else {
                    cameraCtrl.keepPreviewAlive(
                        if (destination.route == ROUTE_SETTINGS) 0L else CameraController.PAGE_KEEP_ALIVE_MS
                    )
                }
            }
            // Activity 级预览自愈（与页面组合解耦）：路由进出录制页时起停
            windowOwner?.onRouteForPreviewWatch(destination.route, cameraCtrl)
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }

    // 方向设置改档即时生效：换页监听只在 destination 变化时触发，用户此刻正停在设置页，
    // 改完不换页也必须立刻转方向。与上面 WatchMotion 同一套 prefs 监听手法（设置页 commit 落盘
    // 会回调这里），route 从当前栈顶回读，正好复用 applyPageMode 的整套逻辑，不新增架构。
    DisposableEffect(nav, activity, prefs) {
        @Suppress("DEPRECATION")
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WotaSettings.KEY_UI_ORIENTATION) {
                applyPageMode(activity, nav.currentBackStackEntry?.destination?.route)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    NavHost(
        navController = nav,
        startDestination = ROUTE_CAMERA
    ) {
        composable(ROUTE_CAMERA) {
            TextScaleLayer(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_CAMERA)) {
                CameraScreen(
                    onOpenGallery = { nav.navigate(WotaNav.GALLERY) },
                    onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                    // 练习动线回程（2026-10-04 方向 4 + 同日裁决）：录成后定向弹回对比页。
                    // 用户可能录成前绕经媒体库/播放器——无差别 popBackStack() 只退一层会落错层
                    // （数据不丢但人到媒体库）。这里定向弹到对比页 route，途中的绕行探索页被
                    // 一并弹掉属预期；对比页不在栈内（异常动线）时保底回普通弹栈。该回调只在
                    // ComparePractice.deliver 成功（练习桥 armed）时被调，普通录制路径零影响。
                    onPracticeFinish = {
                        if (!nav.popBackStack(WotaNav.COMPARE, false)) nav.popBackStack()
                    }
                )
            }
        }
        composable(WotaNav.GALLERY) {
            GalleryEntryScreen(
                onOpen = { id -> nav.navigate(WotaNav.player(id)) },
                onCompare = { id -> nav.navigate(WotaNav.compare(id)) },
                onBack = { popBackStackDebounced() }
            )
        }
        composable(
            route = WotaNav.PLAYER,
            arguments = listOf(navArgument(WotaNav.PLAYER_ARG) { type = NavType.LongType })
        ) { entry ->
            PlayerScreen(
                mediaId = entry.arguments?.getLong(WotaNav.PLAYER_ARG) ?: -1L,
                onBack = { popBackStackDebounced() },
                onCompare = { id -> nav.navigate(WotaNav.compare(id)) },
                // 起播渐亮（29 §5.3-②）：页面只上报"起播了"，武装闸与窗口写入收口在 MainActivity
                onPlaybackStarted = { (activity as? MainActivity)?.onPlayerPlaybackStarted() }
            )
        }
        composable(
            route = WotaNav.COMPARE,
            arguments = listOf(
                navArgument(WotaNav.COMPARE_ARG_LEFT) { type = NavType.LongType; defaultValue = -1L }
            )
        ) { entry ->
            CompareScreen(
                leftMediaId = entry.arguments?.getLong(WotaNav.COMPARE_ARG_LEFT) ?: -1L,
                onBack = { popBackStackDebounced() },
                onPractice = { nav.navigate(ROUTE_CAMERA) }
            )
        }
        composable(ROUTE_SETTINGS) {
            TextScaleLayer(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_SETTINGS)) {
                SettingsScreen(
                    onBack = { popBackStackDebounced() },
                    onOpenHudEditor = { nav.navigate(ROUTE_HUD_EDITOR) }
                )
            }
        }
        composable(ROUTE_HUD_EDITOR) {
            // 编辑的是录制页的控件，文本高度也走录制页那一档，两边量出来的宽度才同一个数
            TextScaleLayer(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_CAMERA)) {
                HudLayoutEditorScreen(onBack = { popBackStackDebounced() })
            }
        }
    }

    if (showPermissionPrompt) {
        AlertDialog(
            onDismissRequest = { showPermissionPrompt = false },
            title = { Text(stringResource(R.string.cam_perm_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.perm_denied_settings), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionPrompt = false
                    context.openAppSettings()
                }) { Text(stringResource(R.string.perm_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionPrompt = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun applyPageMode(activity: Activity?, route: String?) {
    val act = activity ?: return
    // 沉浸仍只属于取景器那一圈控件的页面：录制页与「编辑控件」页（13 号计划第 5 条）共用一套系统栏显隐，
    // 两页的 (x, y) 必须落在同一个安全区里才谈得上"位置"
    val hudPage = route == ROUTE_CAMERA || route == ROUTE_HUD_EDITOR
    // 方向对所有页生效（用户 2026-10-02 指令；旧口径「HUD 页按档锁、其余页恒 FULL_USER 跟随系统旋转」已作废）：
    // 任何页都按设置页「默认方向」锁（默认横屏使用，且不依赖系统自动旋转开关），
    // 连带效果是设置/媒体库/播放器/对比页在 app 内不再随系统自动旋转。映射本体是
    // core/UIOrientation.screenOrientationOf（有 JVM 单测）。这里必须直读 prefs：纯 UI 设置走参数总线的话
    // 会被 applyDefaultsOnce 的「进程内只套一次」挡住。换页会重读一次，改档本身也由 prefs 监听即时重读（见 WotaRoot）。
    val orientation = WotaSettings.uiOrientation(WotaSettings.of(act))
    act.requestedOrientation = UIOrientation.screenOrientationOf(orientation)
    val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
    when {
        // HUD 页（录制页 + 编辑控件页）：全沉浸
        hudPage -> {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        // 播放页：状态栏 + 导航栏都隐藏（2026-10-29 用户反馈：带小白条的设备上播放器底部被
        // 导航条占出一段白色空带）。此前只藏状态栏是为了"视频面尺寸恒定"（29 §6.5），但导航条
        // 常驻本身就是那段空带的来源；两端都藏后播放器全屏，呼出行为仍是滑动临时显示。
        route == WotaNav.PLAYER -> {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
        }
        // 设置页（2026-10-29 用户反馈）：只藏导航栏（顶部状态栏保留，设置页顶栏本来就在状态栏下）。
        // 不藏的话底部滑动区/小白条会把滚动列表最后一行压出一段白带
        route == ROUTE_SETTINGS -> {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
        else -> controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * 启动即要的权限：相机必需，麦克风影响音轨，视频读取只服务媒体库（缺录音权限走降级）。
 * Android 14+ 媒体读取按「全量或部分」二选一：部分授权（READ_MEDIA_VISUAL_USER_SELECTED）
 * 也视为已满足并成对请求两条——用户上次选过「选择照片和视频」后 READ_MEDIA_VIDEO 恒为拒，
 * 只看那一条会每次冷启都重弹权限框
 */
private fun missingStartupPermissions(context: Context): Array<String> {
    val list = mutableListOf<String>()
    if (!hasPermission(context, Manifest.permission.CAMERA)) list += Manifest.permission.CAMERA
    if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) list += Manifest.permission.RECORD_AUDIO
    val media = WotaSettings.mediaReadPermission()
    // 单点版本闸（34 以下 VISUAL_USER_SELECTED 是未知权限，请求必拒，不能进请求清单）
    val api34 = Build.VERSION.SDK_INT >= 34
    val partialGranted = api34 && hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    if (!hasPermission(context, media) && !partialGranted) {
        list += media
        if (api34) list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    }
    return list.toTypedArray()
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** 媒体读取的「全量/部分」二选一权限对。InlinedApi 抑制理由：Manifest.permission 里的这两条
 *  只是编译期内联的字符串常量，拿来**比较** grants 的键在任何 API 上都安全（低版本上根本
 *  不会出现在请求清单里）；真正受版本闸保护的是 missingStartupPermissions 的请求侧 */
@SuppressLint("InlinedApi")
private val MEDIA_PERMISSION_KEYS = setOf(
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
)

/** 公共 Movies 下的导出成片目录（与 [StalePendingSweep.outputDir] 同一构造口径） */
@Suppress("DEPRECATION") // 公共目录定位：本工程持 MANAGE_EXTERNAL_STORAGE，走这条最稳
private fun externalVideoDir(relativePath: String): java.io.File =
    java.io.File(Environment.getExternalStorageDirectory(), relativePath)

/** 从 Compose 的 Context 回溯到 Activity（LocalContext 在 NavHost 里已是 Activity，但包了 wrapper） */
internal fun Context.findActivity(): Activity? {
    var cur: Context? = this
    while (cur != null) {
        if (cur is Activity) return cur
        cur = (cur as? android.content.ContextWrapper)?.baseContext
    }
    return null
}

/** 应用详情页：权限被永久拒绝后的唯一出口 */
internal fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}
