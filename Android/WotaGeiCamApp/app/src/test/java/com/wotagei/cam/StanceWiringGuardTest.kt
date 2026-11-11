package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 站位提示（方案 32）M2 运行时接线守卫（2026-11-07 修复轮）。
 *
 * 为什么要有这一份：这一轮的缺陷全部属于「编译过、单测绿、真机上静默失效」——
 * 1) 分析帧不转正 → 竖持时左右/前后判定互换（看不见的错）；
 * 2) 单人选主与判定不同源 → 判路人；
 * 3) 模型热换 new 一条分析管线 → 旧 HandlerThread/reader/Interpreter 泄漏，且新模型不生效；
 * 4) 参数热更新跨线程改判定引擎状态（偶发状态撕裂，被单帧 catch 吞掉）；
 * 5) 录制窗口误摘分析槽 → 录制中重建相机会话；离页继续播报；退出不释放。
 * 每条都钉成对源码函数体的判据（与 DenoiseWiringGuardTest 同一手法）。
 *
 * **flavor 源集回退（docs/plan/33）**：stance 包迁移到 src/full 后，
 * `stance/…` 相对路径的读取走 [flavorSourceText]（main → full → lite 顺序回退）。
 */
class StanceWiringGuardTest {

    /** main → full 回退的源码读取（flavor 源集迁移后的 stance 专属读取器）。 */
    private fun flavorSourceText(relativePath: String): String {
        require(!relativePath.contains("..")) { "非法相对路径: $relativePath" }
        val mainFile = com.wotagei.cam.source.KotlinSourceScan.mainSourceFile("WotaApp.kt")
        // main 源码根（WotaApp.kt 所在目录）→ 规范化分隔符 → 同构替换 src/main 段 → src/full
        val camPath = mainFile.parentFile!!.absolutePath.replace('\\', '/')
        val f = File(
            camPath.replaceFirst("/src/main/", "/src/full/"),
            relativePath,
        )
        check(f.isFile) { "flavor 源集找不到 $relativePath（$f）" }
        return f.readText(Charsets.UTF_8)
    }

    private val controller = codeOnly(flavorSourceText("stance/StanceController.kt"))
    private val analysis = codeOnly(flavorSourceText("stance/StanceAnalysis.kt"))
    private val engine = codeOnly(flavorSourceText("stance/StanceEngine.kt"))
    private val cameraCtl = codeOnly(mainSourceText("camera/CameraController.kt"))
    private val screen = codeOnly(mainSourceText("ui/CameraScreen.kt"))
    private val mainActivity = codeOnly(mainSourceText("ui/MainActivity.kt"))

    @Test
    fun `模型装配必须离开主线程且复用分析管线`() {
        val prepare = bodyOf(controller, "prepare")
        assertTrue("prepare 必须走异步装配", prepare.contains("reloadAsync("))
        assertFalse(
            "prepare 不许在主线程直接 activeDir/装配（13.8MB copy+sha256+Interpreter）",
            prepare.contains("StanceModelPack.activeDir"),
        )
        val reload = bodyOf(controller, "reloadAsync")
        assertTrue("装配在协程里", reload.contains("packScope.launch"))
        assertTrue("装配完成回主线程补挂", reload.contains("withContext(Dispatchers.Main)"))
        // 热换不再无条件 new 管线：按需创建 + 已存在则复用
        val applyPack = bodyOf(controller, "applyPack")
        assertTrue("热换走 updateModel 复用管线", applyPack.contains("a.updateModel("))
        assertTrue("分析管线按需创建（存在则复用）", applyPack.contains("analysis ?: StanceAnalysis"))
        assertTrue("复用同一实例", applyPack.contains("analysis = it"))
    }

    @Test
    fun `安全区与检测器一起在分析线程生效`() {
        val update = bodyOf(analysis, "updateModel")
        assertTrue("margins 经分析线程落地", update.contains("marginsJson"))
        assertTrue("模型替换走 owner 的 swap", update.contains("swapModel("))
        assertTrue("loadCalibratedBands 在 swap 内", bodyOf(analysis, "swapModel").contains("StanceSpec.loadCalibratedBands"))
        val start = bodyOf(analysis, "start")
        assertTrue("reader 失效必须重建（引擎会单方面 close）", start.contains("surface.isValid"))
    }

    @Test
    fun `判定参数只从分析线程写引擎`() {
        val sync = bodyOf(controller, "syncEngineParams")
        assertTrue("参数同步走 updateEngineConfig", sync.contains("updateEngineConfig("))
        assertFalse("不许在主线程直接 setBeta/setMode", sync.contains("a.engine.set"))
        val upd = bodyOf(analysis, "updateEngineConfig")
        assertTrue("投递到分析线程", upd.contains("h.post(apply)"))
    }

    @Test
    fun `录制窗口只停听不摘槽且开关不重置`() {
        val suspend = bodyOf(controller, "suspendForRecording")
        assertTrue("录制暂停回调", suspend.contains("pauseListening"))
        assertFalse("录制中不许摘分析槽（会重建相机会话打断录制）", suspend.contains("detach"))
        val resume = bodyOf(controller, "resumeAfterRecording")
        assertTrue("停录恢复", resume.contains("maybeAttachAfterGate"))
        val setEnabled = bodyOf(controller, "setEnabled")
        assertTrue("录制中两向都拒绝调整", setEnabled.contains("if (recording)"))
        assertFalse("录制中调整状态而不重建会话", setEnabled.contains("setAnalysisTarget"))
        assertTrue("前置镜头不许开启（D13）", setEnabled.contains("on && frontLens"))
        val screenBody = bodyOf(screen, "CameraScreen")
        // flavor 桥接（docs/plan/33）后 CameraScreen 走 StanceFeature 门面
        assertTrue("录制态必须接线", screenBody.contains("stance.setRecordingActive(recording)"))
        assertTrue("镜头切换必须接线", screenBody.contains("stance.setFrontLens(lens == LensType.FRONT)"))
    }

    @Test
    fun `离页停听回场恢复`() {
        val enter = bodyOf(controller, "onPageEnter")
        assertTrue(enter.contains("maybeAttachAfterGate"))
        val exit = bodyOf(controller, "onPageExit")
        assertTrue(exit.contains("pauseListening"))
        val screenBody = bodyOf(screen, "CameraScreen")
        assertTrue(screenBody.contains("stance.onPageEnter()"))
        assertTrue(screenBody.contains("stance.onPageExit()"))
    }

    @Test
    fun `引擎重建后分析槽自动复位`() {
        val bind = bodyOf(cameraCtl, "bind")
        assertTrue("bind 里复位仍有效的分析面", bind.contains("analysisTarget?.let"))
        assertTrue("失效面不还原（等回场重建 reader）", bind.contains("targetSurface.isValid"))
        val setTarget = bodyOf(cameraCtl, "setAnalysisTarget")
        assertTrue("挂摘回报是否成功（引擎未建 = false）", setTarget.contains("return surface == null"))
    }

    @Test
    fun `Controller销毁时reader释放委托分析owner`() {
        val screenBody = bodyOf(screen, "CameraScreen")
        assertTrue(
            "宿主必须把 Controller 销毁的 reader 收尾接给分析 owner",
            screenBody.contains("analysisOwnerRelease = { stance.onCameraControllerCleared() }")
        )
        val cleared = bodyOf(cameraCtl, "onCleared")
        assertTrue("onCleared 必须优先委托，不在 Main 直接 close", cleared.contains("analysisOwnerRelease"))
        val owner = bodyOf(controller, "onCameraControllerCleared")
        assertTrue("分析 owner 收尾必须排队关闭 reader", owner.contains("releaseReader()"))
    }

    @Test
    fun `退出窗口释放分析线程与模型`() {
        val destroy = bodyOf(mainActivity, "onDestroy")
        // flavor 桥接后 MainActivity 走 StanceFeature 门面释放
        assertTrue("finish 释放站位单例", destroy.contains("StanceFeature.get(this).release()"))
        val release = bodyOf(controller, "release")
        assertTrue("装配线程也要收", release.contains("resetPackScope"))
    }

    @Test
    fun `单人判定对象与选主同源`() {
        val observe = bodyOf(engine, "observe")
        assertTrue("n==1 判定必须用 primary", observe.contains("val primary = if (n == 1) selectPrimary(gated)"))
        assertTrue("people 同源", observe.contains("if (n == 1) primary else"))
    }
}