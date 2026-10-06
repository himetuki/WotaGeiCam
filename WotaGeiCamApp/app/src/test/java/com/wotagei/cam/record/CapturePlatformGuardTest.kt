package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内录平台骨架（批 1）的**源码/清单守卫**。行为（授权框、FGS 通知、真会话）JVM 测不到，这里钉结构红线：
 * - manifest：FGS 两权限在位、`CaptureFgService` 声明与 `mediaProjection` type 在位
 *   （INTERNET 红线 2026-10-06 已按用户裁决解除，联网守卫移交 update/UpdateNetworkGuardTest）；
 * - API 34 顺序链：`onCreate` 内 `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`，
 *   `getMediaProjection` 只准在 `onStartCommand` 且先拿会话后 `registerCallback`
 *   （targetSdk 34 下先 getMediaProjection 抛 SecurityException）；
 * - 纯状态机 [CaptureStateMachine.kt] 保持纯 Kotlin（同 player/CompareTimeline 纯度守卫先例）。
 *
 * 全部判据作用在 [KotlinSourceScan.codeOnly] 遮蔽后的文本上（注释里提到旧符号不算违规）。
 * 红/绿突变自证：删 manifest 权限/service 行、把 startForeground 挪到 getMediaProjection 之后、
 * 删 onStop 的身份守卫、给 controller 换回直接 projection?.stop()、删通知停止 action、
 * 删 ACTION_STOP 分支、删 onEstablishResult 的态守卫、给状态机文件加一个 android import，
 * 对应断言即红。
 */
class CapturePlatformGuardTest {

    /** 清单文件定位：同 [KotlinSourceScan.mainSourceFile] 的逐层上溯策略 */
    private fun manifestText(): String {
        val rel = "src/main/AndroidManifest.xml"
        var dir: File = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val f = File(base, rel)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 AndroidManifest.xml——守卫不能在没有输入的情况下算通过")
    }

    private fun masked(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked(rel), funName))

    // ------------------------------------------------------------------ manifest

    @Test
    fun `manifest_内录FGS权限与服务声明在位`() {
        val m = manifestText()
        assertTrue(
            "缺 FOREGROUND_SERVICE（normal，FGS 启动前置）",
            m.contains("android:name=\"android.permission.FOREGROUND_SERVICE\"")
        )
        assertTrue(
            "缺 FOREGROUND_SERVICE_MEDIA_PROJECTION（targetSdk 34 的 mediaProjection 型 FGS 前置）",
            m.contains("android:name=\"android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION\"")
        )
        assertTrue(
            "缺 CaptureFgService 声明（未声明的服务 startForegroundService 直接炸）",
            m.contains("android:name=\".record.CaptureFgService\"")
        )
        assertTrue(
            "服务必须标 foregroundServiceType=\"mediaProjection\"（targetSdk 34：缺它 startForeground 崩）",
            Regex("foregroundServiceType\\s*=\\s*\"mediaProjection\"").containsMatchIn(m)
        )
    }

    // ------------------------------------------------------------------ API 34 顺序链

    @Test
    fun `API34顺序链_onCreate先startForeground_getMediaProjection只在onStartCommand且回调随后注册`() {
        val onCreate = body("record/CaptureFgService.kt", "onCreate")
        val onStart = body("record/CaptureFgService.kt", "onStartCommand")
        assertTrue(
            "onCreate 必须以 MEDIA_PROJECTION 类型 startForeground（顺序链第 1 环；放 onCreate 靠" +
                "「onCreate 恒先于 onStartCommand」把 1→2 锁成结构顺序）",
            onCreate.contains("startForeground(") &&
                onCreate.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")
        )
        assertFalse(
            "getMediaProjection 不准进 onCreate（顺序链第 2 环必须在进前台之后的 onStartCommand）",
            onCreate.contains("getMediaProjection")
        )
        assertTrue(
            "onStartCommand 必须调 getMediaProjection（顺序链第 2 环）",
            onStart.contains("getMediaProjection(")
        )
        assertTrue(
            "registerCallback 必须在 getMediaProjection 之后尽早注册（顺序链第 3 环；" +
                "API 34 要求 createVirtualDisplay 前已注册）",
            onStart.indexOf("registerCallback") > onStart.indexOf("getMediaProjection(")
        )
    }

    @Test
    fun `服务会话与撤销回执挂点在位`() {
        val svc = masked("record/CaptureFgService.kt")
        // companion 单例持有（internal set 只准服务写）+ onStop 撤销回执
        assertTrue("companion 必须持有 projection 单例（internal set）", svc.contains("var projection: MediaProjection?"))
        assertTrue("onStop 回调必须触发撤销回执", KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(svc, "onStop")).contains("onProjectionRevoked"))
    }

    @Test
    fun `撤销链路_onStop带会话身份守卫_controller拆会话必须走stopActiveProjection`() {
        val onStop = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(masked("record/CaptureFgService.kt"), "onStop")
        )
        assertTrue(
            "onStop 必须先过会话身份守卫（程序拆旧会话/新会话顶替后迟到的 onStop：不误报「用户撤销」、不误杀新会话）",
            onStop.contains("projection !== proj")
        )
        for (fn in listOf("onConsentGranted", "shutdown")) {
            val b = body("record/PlaybackCaptureController.kt", fn)
            assertFalse(
                "$fn 不许直接 projection?.stop()——绕过服务身份守卫会把重建误标 Revoked / 误杀新会话",
                b.contains("projection?.stop()")
            )
            assertTrue(
                "$fn 拆旧会话必须走 CaptureFgService.stopActiveProjection()（身份守卫路径）",
                b.contains("stopActiveProjection()")
            )
        }
    }

    @Test
    fun `通知停止入口_buildNotification挂停止action_onStartCommand识别ACTION_STOP分支`() {
        val svc = masked("record/CaptureFgService.kt")
        val build = body("record/CaptureFgService.kt", "buildNotification")
        assertTrue(
            "buildNotification 必须挂停止 action（addAction 接 stopPendingIntent：" +
                "API 29/30 无稳定的系统级「停止投屏」，采集全局音频的隐私敏感会话必须有可及终止手段）",
            build.contains("addAction(") && build.contains("stopPendingIntent(")
        )
        val pi = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(svc, "stopPendingIntent"))
        assertTrue(
            "stopPendingIntent 必须 PendingIntent.getService + setPackage + IMMUTABLE（action 只在本应用内循环，防伪造劫持）",
            pi.contains("PendingIntent.getService") && pi.contains("setPackage") && pi.contains("FLAG_IMMUTABLE")
        )
        val onStart = KotlinSourceScan.flatten(body("record/CaptureFgService.kt", "onStartCommand"))
        assertTrue(
            "onStartCommand 必须识别通知停止 action（action == ACTION_STOP 分支在位）",
            onStart.contains("action == ACTION_STOP")
        )
        assertTrue(
            "停止分支必须先回执撤销再拆会话最后 stopSelf（用户主动结束=Revoked 语义；" +
                "先拆会话会被身份守卫静默，controller 卡死 Active）",
            onStart.indexOf("ACTION_STOP") < onStart.indexOf("onProjectionRevoked") &&
                onStart.indexOf("onProjectionRevoked") < onStart.indexOf("stopSelf()")
        )
    }

    @Test
    fun `建链回执_onEstablishResult成功分支带Authorizing脏回执防线`() {
        val b = body("record/PlaybackCaptureController.kt", "onEstablishResult")
        assertTrue(
            "onEstablishResult 成功分支必须先验 Authorizing（脏回执不覆写 captureConfig、不凭空改态，" +
                "防批 2 并发授权路径）",
            b.contains("_state.value !is CaptureState.Authorizing") &&
                b.indexOf("CaptureState.Authorizing") < b.indexOf("captureConfig =")
        )
    }

    // ------------------------------------------------------------------ 纯度

    @Test
    fun `状态机保持纯Kotlin_controller推进一律走transition`() {
        val machine = masked("record/CaptureStateMachine.kt")
        assertFalse(
            "record/CaptureStateMachine.kt 必须保持纯 Kotlin（无 android/androidx 依赖）：JVM 单测直打的前提",
            machine.contains("android")
        )
        // controller 的状态推进必须走 transition 纯函数（删掉它 = 状态机与实现脱钩、纯测失效）
        val ctrl = masked("record/PlaybackCaptureController.kt")
        assertTrue("controller 状态推进必须走 transition", ctrl.contains("transition("))
    }
}
