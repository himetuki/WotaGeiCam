package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页覆盖保活的**源码结构守卫**（2026-10-29 手动白平衡绿帧·二段）。
 *
 * 现场：r22 修掉恒等 CCM 后，用户仍复现「回设置再回录制页闪一下绿再回正」。根因在导航结构：
 * 设置页推在录制页之上时录制页 dispose 会关相机（unbind）并释放 GL 引擎（SurfaceTexture 一起
 * 没了），回来必然整条重开会话——卓易通桥在会话重建的首几拍无视请求元数据、沿用 HAL 残留的
 * AWB_OFF 状态（种子 CCM/稳压窗都拦不住，绿帧发生在重建间隙、与请求内容无关，r22 连拍实测
 * f2 帧 G/B=12.4、B≈0）。
 *
 * 保活口径：设置页覆盖期间相机与 GL 引擎**都不关不释**（GL 的"摘显示面但线程与上下文存活"
 * 是 PreviewSink 系既有契约），回场零重建零闪帧。三条红线：
 * - 旗标只由 MainActivity 的目的地回调维护（route==settings 时置真），dispose 只读不写；
 * - GL 引擎实例由页面级 ViewModel 缓存（跨组合销毁复用同一 SurfaceTexture），释放权上收
 *   [CameraController.onCleared]，页面 dispose 不再 release；
 * - 回场路径 start() 必须对"设备 OPEN_SUCCEED 且预览 ING"早退——openCurrentLens 不幂等，
 *   重开一次就前功尽弃。
 */
class WbCoverKeepAliveGuardTest {

    private val screenSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
    }
    private val ctrlSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/CameraController.kt"))
    }
    private val activitySrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/MainActivity.kt"))
    }

    @Test
    fun `录制页dispose必须按旗标分_覆盖期不关相机不释GL`() {
        assertTrue(
            "dispose 必须有覆盖保活分支（旗标真 = 相机与 GL 原样保活）",
            screenSrc.contains("if (ctrl.previewKeptBehind && activeSink is GlRenderEngine)")
        )
        assertTrue(
            "页面 dispose 不许再释放 GL 引擎（释放权上收 onCleared；释放了 SurfaceTexture，会话就得重建 = 绿帧）",
            !screenSrc.contains("activeSink as? GlRenderEngine)?.release()")
        )
        assertTrue(
            "GPU 引擎必须取页面级缓存实例（组合销毁重建也拿回同一个 SurfaceTexture）",
            screenSrc.contains("ctrl.glSink()")
        )
    }

    @Test
    fun `旗标由目的地回调维护且只对设置页置真`() {
        assertTrue(
            "MainActivity 必须在目的地回调里维护旗标（navigate/popBackStack 同步触发，先于旧页 dispose）",
            activitySrc.contains("previewKeptBehind = destination.route == ROUTE_SETTINGS")
        )
    }

    @Test
    fun `回场start必须对存活会话早退_且GL引擎随VM释放`() {
        // 2026-10-29 黑屏取证后：早退判据从缓存状态（OPEN_SUCCEED+ING）改成引擎真身 isPreviewLive
        //（缓存状态在快速导航竞态里会残留成"看着活着"，早退会把相机卡死）。openCurrentLens 不幂等
        // 的理由不变——真活着时仍然不许重开。
        val startBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(ctrlSrc, "start"))
        assertTrue("start() 早退必须用引擎真身判据", startBody.contains("isPreviewLive()"))
        val cleared = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(ctrlSrc, "onCleared"))
        assertTrue("onCleared 必须释放缓存的 GL 引擎", cleared.contains("cachedGlSink?.release()"))
    }

    @Test
    fun `覆盖期生命周期与第二处dispose都不许停相机`() {
        // r24 真机实测：主 dispose 已保活，但生命周期 ON_PAUSE→pause() 与第二处 dispose 的
        // ctrl.stop() 仍然把相机杀掉（CameraPower STOP），回来重开=绿帧。三处必须全部按旗标分。
        assertTrue(
            "生命周期 dispose 必须按旗标分（覆盖期不许 ctrl.stop）",
            screenSrc.contains("if (!ctrl.previewKeptBehind) ctrl.stop()")
        )
        assertTrue(
            "onPause 覆盖期必须早退（停预览=回场变重启预览）",
            ctrlSrc.contains("if (previewKeptBehind) return")
        )
        assertTrue(
            "start/resume 的回场早退必须用引擎真身 isPreviewLive（缓存状态可能残留成黑屏死态）",
            ctrlSrc.contains("isPreviewLive()")
        )
    }

    @Test
    fun `黑屏自愈_真身判据加预览看门狗`() {
        // 2026-10-29 黑屏取证：设置页快速连按返回 → 录制页反复重建 → 最后一次重建后相机
        // 没被拉起（进程活着、无崩溃、之后再无相机遥测），整屏黑。三处防线必须都在：
        val engineSrc = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt"))
        assertTrue(
            "引擎必须提供真身判据 isPreviewLive（线程+设备+会话+期望预览）",
            engineSrc.contains("fun isPreviewLive(): Boolean") &&
                engineSrc.contains("session != null && desiredPreview")
        )
        assertTrue(
            "bind 的早退必须消化 pendingStart（否则该起相机的欠账永久挂着）",
            ctrlSrc.contains("if (boundSink === sink && engine != null) {") &&
                ctrlSrc.contains("if (pendingStart) start()")
        )
        assertTrue("必须提供整条重建入口 forceRebind", ctrlSrc.contains("fun forceRebind(sink: PreviewSink)"))
        assertTrue(
            "录制页必须有预览自愈 watchdog（先重启预览、仍死再整条重建；codeOnly 遮蔽字符串，按代码锚）",
            screenSrc.contains("ctrl.restartPreview()") &&
                screenSrc.contains("ctrl.forceRebind(activeSink)")
        )
        // 2026-10-29 二次取证：判据必须是**帧心跳**（result 回调计数），不许再用状态字段——
        // r30 用 isPreviewLive 判活，重建卡死时字段仍"看着活着"，看门狗被绕过、用户仍黑屏。
        assertTrue(
            "看门狗判据必须是帧心跳（frameResultCount），状态字段可残留、帧数不会",
            screenSrc.contains("frameResultCount()")
        )
        assertTrue(
            "引擎必须暴露 result 回调计数",
            engineSrc.contains("fun frameResultCount(): Long")
        )
    }

    @Test
    fun `稳压窗必须挂在显示面挂摘重挂上`() {
        val glSrc = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt"))
        // 会话全程存活也会闪（r23 实测）：桥过渡帧吐的是 HAL 过渡态，与会话重建无关，
        // 所以布防点必须在显示面事件上，而不是 onConfigured
        assertTrue(
            "GL 显示面挂摘/重挂必须布防稳压窗",
            glSrc.contains("onDisplaySurfaceTransient?.invoke()")
        )
        assertTrue(
            "引擎必须有显示面瞬态入口",
            ctrlSrcSrcContains("fun onSinkSurfaceTransient")
        )
        assertTrue(
            "录制页必须把显示面瞬态接到门面",
            screenSrc.contains("onDisplaySurfaceTransient = { ctrl.onPreviewSurfaceTransient() }")
        )
    }

    private fun ctrlSrcSrcContains(anchor: String): Boolean =
        KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt")
        ).contains(anchor)
}
