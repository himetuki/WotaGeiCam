package com.wotagei.cam.stance

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.wotagei.cam.camera.AnalysisSessionDecision
import com.wotagei.cam.diag.Diag
import com.wotagei.cam.ui.dialog.observed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 站位功能门面（**full 变体**：有 AI 站位，docs/plan/33 桥接）。
 *
 * 与 src/lite 的同名类构成同 FQ 名双实现：CameraScreen（主源集）只 import 本类
 * 与 [StanceUi]，编译期由 flavor 决定解析到哪一份。本实现转发
 * [StanceController]（完整功能：模型组/分析流/判定/播报/叠加）。
 */
class StanceFeature private constructor(
    private val appContext: Context,
    private val ctrl: StanceController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val state: StateFlow<StanceUi> = ctrl.state
        .map { StanceUi(enabled = it.enabled, running = it.running, lineIntervalMs = it.lineIntervalMs, pollIntervalMs = it.pollIntervalMs, fallbackTip = it.fallbackTip) }
        .stateIn(scope, SharingStarted.Eagerly, StanceUi())

    fun prepare(context: Context) = ctrl.prepare(context)

    fun setEnabled(context: Context, on: Boolean) = ctrl.setEnabled(context, on)

    fun setRecordingActive(active: Boolean) = ctrl.setRecordingActive(active)

    /** B16：设置页渲染模式 ↔ 推理加速准入（弹窗与装配同口径）。 */
    fun setRenderGpuAllowed(allowed: Boolean) = ctrl.setRenderGpuAllowed(allowed)

    fun setFrontLens(front: Boolean) = ctrl.setFrontLens(front)

    /** 镜头切换未决（Y70）：切镜窗口内先停分析，槽位落定后由 Controller 自行补挂。 */
    fun setLensSettling(settling: Boolean) = ctrl.setLensSettling(settling)

    fun setRotation(degrees: Int) = ctrl.setRotation(degrees)

    fun onAnalysisTargetChanged() = ctrl.onAnalysisTargetChanged()

    fun onAnalysisSessionDecision(decision: AnalysisSessionDecision) = ctrl.onAnalysisSessionDecision(decision)

    fun onPageEnter() = ctrl.onPageEnter()

    fun onPageExit() = ctrl.onPageExit()

    fun onCameraControllerCleared() = ctrl.onCameraControllerCleared()

    fun release() {
        synchronized(Companion) {
            if (instance === this) {
                ctrl.release()
                scope.cancel()
                instance = null
            } else {
                scope.cancel()
            }
        }
    }

    /** 挂槽回执直通：引擎未就绪或拒绝时不能伪造 running=true。 */
    var attachHook: ((Surface, ImageReader?) -> Boolean)?
        get() = ctrl.attachHook
        set(value) { ctrl.attachHook = value }

    var detachHook: (() -> Unit)?
        get() = ctrl.detachHook
        set(value) { ctrl.detachHook = value }

    var targetAspectProvider: (() -> Float?)?
        get() = ctrl.targetAspectProvider
        set(value) { ctrl.targetAspectProvider = value }

    var characteristicsProvider: (() -> CameraCharacteristics?)?
        get() = ctrl.characteristicsProvider
        set(value) { ctrl.characteristicsProvider = value }

    /** 实际生效镜头标识（Y09/B14）：分析 reader 的复用键 = 该标识 + YUV 尺寸。 */
    var lensKeyProvider: (() -> String?)?
        get() = ctrl.lensKeyProvider
        set(value) { ctrl.lensKeyProvider = value }

    /** 预览流实际几何（B14）：叠加映射只认 sink 实测尺寸/转正/镜像与裁剪证据。 */
    var previewGeometryProvider: (() -> PreviewStreamGeometry?)?
        get() = ctrl.previewGeometryProvider
        set(value) { ctrl.previewGeometryProvider = value }

    /** 号位叠加（判定输出可视化；View 层结构性不进成片）。 */
    @Composable
    fun Overlay(modifier: androidx.compose.ui.Modifier) {
        val s by ctrl.state.observed()
        StanceOverlay(output = s.output, geometry = s.geometry, modifier = modifier)
    }

    /**
     * 浮层内容（完整控制面板：总开关/精确度/人数/加速三态/提亮/模型目录/版本）。
     *
     * [modifier] 由调用方给：CameraScreen 按实测右缘目标传入 `widthIn(max = …)`（Y55），
     * 窄窗/分屏时面板整体收缩而不是越过右 Dock 槽左缘。
     */
    @Composable
    fun ControlsPopup(gpuAllowed: Boolean, modifier: Modifier = Modifier) {
        val s by ctrl.state.observed()
        StancePopupContent(
            state = s,
            gpuAllowed = gpuAllowed,
            modelDirText = com.wotagei.cam.stance.StanceModelPack.userExternalDir(appContext).absolutePath,
            onEnabled = { ctrl.setEnabled(appContext, it) },
            onBeta = { ctrl.setBeta(appContext, it) },
            onBetaReset = { ctrl.setBeta(appContext, StanceSpec.BETA_DEFAULT) },
            onMode = { m, n -> ctrl.setMode(appContext, m, n) },
            onGpuMode = { ctrl.setGpuMode(appContext, it) },
            onLineInterval = { ctrl.setLineInterval(appContext, it) },
            onPollInterval = { ctrl.setPollInterval(appContext, it) },
            onBrighten = { ctrl.setBrighten(appContext, it) },
            onImportFromDir = { ctrl.importFromExternalDir(appContext) },
            modifier = modifier,
        )
    }

    companion object {
        @Volatile
        private var instance: StanceFeature? = null

        fun get(context: Context): StanceFeature =
            instance ?: synchronized(this) {
                instance ?: StanceFeature(
                    context.applicationContext,
                    StanceController.get(context.applicationContext),
                ).also { instance = it }
            }
    }
}
