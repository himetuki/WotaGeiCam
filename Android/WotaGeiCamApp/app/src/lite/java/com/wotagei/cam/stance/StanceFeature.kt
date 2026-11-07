package com.wotagei.cam.stance

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 站位功能门面（**lite 变体**：无 AI 站位，docs/plan/33 桥接 + 2026-11-07 用户裁决：
 * lite 保留站位圆钮做导流）。
 *
 * 与 src/full 的同名类构成同 FQ 名双实现：CameraScreen 只 import 本类与 [StanceUi]。
 * 行为：圆钮照常显示、点击弹**宣传浮层**（「此版本为无AI站位指示版本……」）；
 * 无检测、无叠加、无播报、引擎槽不挂（attach/detach NoOp）。
 * 本文件**不得 import 任何模型/推理符号**——lite 包不携带 tflite 依赖与模型资产。
 */
class StanceFeature private constructor(private val appContext: Context) {

    private val _state = MutableStateFlow(StanceUi())
    val state: StateFlow<StanceUi> = _state.asStateFlow()

    fun prepare(context: Context) {}          // 无模型组可装配

    fun setEnabled(context: Context, on: Boolean) {
        _state.value = _state.value.copy(enabled = on)
    }

    fun setRecordingActive(active: Boolean) {}   // 无录制联动诉求（功能恒关）

    fun setFrontLens(front: Boolean) {}

    fun setRotation(degrees: Int) {}

    fun onPageEnter() {}

    fun onPageExit() {}

    fun release() {}

    var attachHook: ((Surface, ImageReader?) -> Boolean)? = null
    var detachHook: (() -> Unit)? = null
    var targetAspectProvider: (() -> Float?)? = null
    var characteristicsProvider: (() -> CameraCharacteristics?)? = null

    /** 无检测输出——lite 恒空叠加。 */
    @Composable
    fun Overlay(modifier: Modifier) {
    }

    /** 宣传浮层（用户 2026-11-07 口径原文文案）。 */
    @Composable
    fun ControlsPopup(gpuAllowed: Boolean) {
        Column(
            Modifier
                .widthIn(min = 264.dp, max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xCC1C1C24))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("站位提示", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "此版本为无AI站位指示版本，如需使用AI站位指示功能，请前往项目仓库获取",
                color = Color(0xFFDDDDDD),
                fontSize = 13.sp,
            )
            Text(
                "（full 变体内置 YOLO 站位检测、判定模型与安全区标定，可在录制前对站位偏差给出逐号位语音与画面提示）",
                color = Color(0xFF9A9AA6),
                fontSize = 11.sp,
            )
        }
    }

    companion object {
        @Volatile
        private var instance: StanceFeature? = null

        fun get(context: Context): StanceFeature =
            instance ?: synchronized(this) {
                instance ?: StanceFeature(context.applicationContext).also { instance = it }
            }
    }
}
