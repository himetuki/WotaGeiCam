package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-21 启动闪退（release，黑屏后未进录制页即退）的源码守卫。
 *
 * 根因：lifecycle-runtime-compose 2.8.2 的 `collectAsStateWithLifecycle()` 读取
 * `androidx.lifecycle.compose.LocalLifecycleOwner`（新版），而本工程锁在 Compose UI 1.6.8
 * （BOM 2024.06.00），根组合只提供旧版 `androidx.compose.ui.platform.LocalLifecycleOwner`；
 * release 的 R8 优化档下新版 Local 没有值，CameraScreen 首帧的 `observed()` 抛
 * `CompositionLocal LocalLifecycleOwner not present`。
 *
 * 修法是在 `MainActivity.onCreate` 的 `setContent` 根组合把同一个 owner 补给新版 Local。
 * 本守卫钉住这条桥——删掉它，真机 release 会重新闪退，而 JVM 单测本身不会崩。
 */
class LifecycleLocalProviderGuardTest {

    @Test
    fun `根组合必须把旧版 owner 补给 lifecycle 新版 Local`() {
        val src = KotlinSourceScan.mainSourceText("ui/MainActivity.kt")
        val masked = KotlinSourceScan.codeOnly(src)
        val onCreate = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "onCreate"))

        assertTrue(
            "MainActivity.onCreate 必须用 CompositionLocalProvider 承载根组合",
            onCreate.contains("CompositionLocalProvider(")
        )
        assertTrue(
            "必须把旧版 platform owner 补给 lifecycle 新版 Local：" +
                "`LifecycleLocalLifecycleOwner provides ComposeLocalLifecycleOwner.current`",
            onCreate.contains(
                "LifecycleLocalLifecycleOwner provides ComposeLocalLifecycleOwner.current"
            )
        )
        assertTrue(
            "两个 LocalLifecycleOwner 必须用别名导入，避免同名遮蔽",
            src.contains(
                "import androidx.compose.ui.platform.LocalLifecycleOwner as ComposeLocalLifecycleOwner"
            ) && src.contains(
                "import androidx.lifecycle.compose.LocalLifecycleOwner as LifecycleLocalLifecycleOwner"
            )
        )
        assertTrue(
            "桥必须包住 WotaTheme（根组合），不能只包某个子页面",
            onCreate.indexOf("CompositionLocalProvider(") < onCreate.indexOf("WotaTheme")
        )
    }
}
