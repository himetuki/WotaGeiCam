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
import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.anim.WotaMotionProvider
import com.wotagei.cam.ui.theme.TextScaleLayer
import com.wotagei.cam.ui.theme.WotaTheme

/** 本层新增的两条路由（其余路由用 media 包 [WotaNav] 常量，避免两处定义） */
const val ROUTE_CAMERA = "camera"
const val ROUTE_SETTINGS = "settings"

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
        setContent {
            WotaTheme {
                val prefs = remember { WotaSettings.of(this) }
                var motion by remember { mutableStateOf(WotaSettings.motionMode(prefs)) }
                WatchMotion(prefs) { motion = it }
                WotaMotionProvider(mode = motion) { WotaRoot() }
            }
        }
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

    // 方向与沉浸随目的地切换：录制页按设置页「默认方向」锁（默认横屏），其余页跟随用户系统旋转设置；
    // 录制页隐藏系统栏沉浸
    DisposableEffect(nav, activity) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            applyPageMode(activity, destination.route)
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }

    NavHost(
        navController = nav,
        startDestination = ROUTE_CAMERA
    ) {
        composable(ROUTE_CAMERA) {
            TextScaleLayer(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_CAMERA)) {
                CameraScreen(
                    onOpenGallery = { nav.navigate(WotaNav.GALLERY) },
                    onOpenSettings = { nav.navigate(ROUTE_SETTINGS) }
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
                onBack = { nav.popBackStack() }
            )
        }
        composable(ROUTE_SETTINGS) {
            TextScaleLayer(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_SETTINGS)) {
                SettingsScreen(onBack = { nav.popBackStack() })
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
    val cameraPage = route == ROUTE_CAMERA
    // 录制页方向由设置页「默认方向」决定（默认横屏使用，且不依赖系统自动旋转开关）；
    // 非录制页恒跟随用户的系统旋转设置。映射本体是 core/UIOrientation.screenOrientationOf（有 JVM 单测）。
    // 这里必须直读 prefs：纯 UI 设置走参数总线的话会被 applyDefaultsOnce 的「进程内只套一次」挡住，
    // 设置页改完回录制页就不生效了。每次换页都重读，所以改档立刻生效。
    val orientation = WotaSettings.uiOrientation(WotaSettings.of(act))
    act.requestedOrientation = UIOrientation.screenOrientationOf(cameraPage, orientation)
    val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
    if (cameraPage) {
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
