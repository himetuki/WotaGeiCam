package com.wotagei.cam.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.wotagei.cam.core.UIOrientation
import com.wotagei.cam.media.WotaNav
import com.wotagei.cam.player.CompareScreen
import com.wotagei.cam.player.PlayerScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyMergeHook(intent)
        applyFrostProbe(intent)
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

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // 任一权限被拒即给「去设置」出口（06 文档 §1 权限策略）
        if (grants.values.any { !it }) showPermissionPrompt = true
    }
    LaunchedEffect(Unit) {
        val missing = missingStartupPermissions(context)
        if (missing.isNotEmpty()) launcher.launch(missing)
    }

    // 方向与沉浸随目的地切换：方向所有页都按设置页「默认方向」锁（用户 2026-10-02 指令）；
    // HUD 页（录制页 + 编辑控件页）隐藏系统栏沉浸，其余页显示
    DisposableEffect(nav, activity) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            applyPageMode(activity, destination.route)
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
                onBack = { nav.popBackStack() }
            )
        }
        composable(
            route = WotaNav.PLAYER,
            arguments = listOf(navArgument(WotaNav.PLAYER_ARG) { type = NavType.LongType })
        ) { entry ->
            PlayerScreen(
                mediaId = entry.arguments?.getLong(WotaNav.PLAYER_ARG) ?: -1L,
                onBack = { nav.popBackStack() },
                onCompare = { id -> nav.navigate(WotaNav.compare(id)) }
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
                onBack = { nav.popBackStack() },
                onPractice = { nav.navigate(ROUTE_CAMERA) }
            )
        }
        composable(ROUTE_SETTINGS) {
            TextScaleLayer(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_SETTINGS)) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenHudEditor = { nav.navigate(ROUTE_HUD_EDITOR) }
                )
            }
        }
        composable(ROUTE_HUD_EDITOR) {
            // 编辑的是录制页的控件，文本高度也走录制页那一档，两边量出来的宽度才同一个数
            TextScaleLayer(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_CAMERA)) {
                HudLayoutEditorScreen(onBack = { nav.popBackStack() })
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
    if (hudPage) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/** 启动即要的三项权限：相机必需，麦克风影响音轨，视频读取只服务媒体库（缺录音权限走降级） */
private fun missingStartupPermissions(context: Context): Array<String> {
    val list = mutableListOf<String>()
    if (!hasPermission(context, Manifest.permission.CAMERA)) list += Manifest.permission.CAMERA
    if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) list += Manifest.permission.RECORD_AUDIO
    val media = WotaSettings.mediaReadPermission()
    if (!hasPermission(context, media)) list += media
    return list.toTypedArray()
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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
