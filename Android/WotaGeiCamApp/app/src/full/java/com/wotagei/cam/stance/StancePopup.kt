package com.wotagei.cam.stance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import android.content.Context
import com.wotagei.cam.ui.design.WotaSwitch

/**
 * 站位提示浮层内容（M3b，用户十七稿弹窗要求逐项）：
 * 精确度 β 滑杆（+重置+说明）／自动·手动模式／手动人数 1~5／GPU·NPU 加速开关
 * （联动设置页"使用GPU"=KEY_DEFAULT_RENDER==GPU 才可开）／打开·扫描模型目录按钮
 * （+模型存放目录文本标注）／当前模型·判定头·安全区版本号文本／模型更新说明。
 * 半透明深底卡（浮在预览上可读；与站位线"透明浮层"同为 Popup 层，不进成片）。
 */
@Composable
fun StancePopupContent(
    state: StanceController.UiState,
    gpuAllowed: Boolean,
    modelDirText: String,
    onEnabled: (Boolean) -> Unit,
    onBeta: (Float) -> Unit,
    onBetaReset: () -> Unit,
    onMode: (String, Int) -> Unit,
    onGpuMode: (String) -> Unit,
    onLineInterval: (Long) -> Unit,
    onPollInterval: (Long) -> Unit,
    onBrighten: (Boolean) -> Unit,
    onImportFromDir: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(min = 264.dp, max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xCC1C1C24))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ① 总开关
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("站位提示", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("预览态专属 · 仅后置镜头 · 录制中自动暂停，停录恢复", color = Color(0xFF9A9AA6), fontSize = 11.sp)
            }
            WotaSwitch(checked = state.enabled, onCheckedChange = onEnabled)
        }

        if (state.enabled) {
            // ② 精确度 β（D3：大=宽松/小=严格）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("精确度", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    String.format("β=%.2f", state.beta),
                    color = Color(0xFFFFCC66), fontSize = 12.sp,
                )
                androidx.compose.material3.TextButton(onClick = onBetaReset) {
                    Text("重置", color = Color(0xFF9A9AA6), fontSize = 11.sp)
                }
            }
            androidx.compose.material3.Slider(
                value = state.beta,
                onValueChange = onBeta,
                valueRange = StanceSpec.BETA_MIN..StanceSpec.BETA_MAX,
            )
            Text(
                "精确度说明：β 越小判定越严格（人微微偏移就报歪），越大越宽松（大幅偏移也不报）。" +
                    "改完立即生效；默认 0.30。",
                color = Color(0xFF9A9AA6), fontSize = 10.sp,
            )

            // ③ 人数模式：自动 / 手动 1~5
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("人数", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ModeChip("自动", state.mode == JudgementEngine.MODE_AUTO) {
                    onMode(JudgementEngine.MODE_AUTO, state.nManual)
                }
                ModeChip("手动", state.mode == JudgementEngine.MODE_MANUAL) {
                    onMode(JudgementEngine.MODE_MANUAL, state.nManual)
                }
                if (state.mode == JudgementEngine.MODE_MANUAL) {
                    // 手动人数 1~5（步进器；自动模式隐藏）
                    StepButton("−") { onMode(JudgementEngine.MODE_MANUAL, state.nManual - 1) }
                    Text("${state.nManual}", color = Color.White, fontSize = 14.sp)
                    StepButton("+") { onMode(JudgementEngine.MODE_MANUAL, state.nManual + 1) }
                }
            }

            // ③b 轮询节奏双间隔（用户 r06 口径：轮内每号位间隔 / 两轮之间静默）
            IntervalSlider("轮询内间隔", "流程内每号位播报的间隔", state.lineIntervalMs, onLineInterval)
            IntervalSlider("轮询间间隔", "两轮全流程播报之间的静默", state.pollIntervalMs, onPollInterval)

            // ④ GPU/NPU 加速三态（r03：自动=白名单裁决 / 强制=跳白名单实验（Maleoon 等
            //    新 GPU 白名单未收录但有 GLES compute 能力，运行期验证失败自动回退） / 关）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GPU/NPU 加速", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ModeChip("自动", gpuAllowed && state.gpuMode == StanceDetector.MODE_AUTO) {
                    onGpuMode(StanceDetector.MODE_AUTO)
                }
                ModeChip("强制", gpuAllowed && state.gpuMode == StanceDetector.MODE_FORCE) {
                    onGpuMode(StanceDetector.MODE_FORCE)
                }
                ModeChip("关", !gpuAllowed || state.gpuMode == StanceDetector.MODE_OFF) {
                    onGpuMode(StanceDetector.MODE_OFF)
                }
            }
            Text(
                "当前：${state.backendName.ifEmpty { "CPU" }}" +
                    if (gpuAllowed) "" else "（设置页渲染模式为 DIRECT，不可用）",
                color = Color(0xFF9A9AA6), fontSize = 10.sp,
            )
            Text(
                "自动=按兼容性白名单裁决；强制=跳过白名单实验（白名单未收录的新 GPU 如 " +
                    "Maleoon 可能可用，推理验证失败会自动回退并提示）。",
                color = Color(0xFF77777F), fontSize = 10.sp,
            )

            // ⑤ 预处理提亮（夜间识别，§6 默认关）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("预处理提亮", color = Color.White, fontSize = 13.sp)
                    Text("夜间识别用；只进检测输入", color = Color(0xFF9A9AA6), fontSize = 10.sp)
                }
                WotaSwitch(checked = state.brighten, onCheckedChange = onBrighten)
            }

            // ⑥ 模型目录 + 扫描导入（用户十七稿：无法直接跳转 → 文本标注 + 扫描按钮）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("模型目录", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onImportFromDir) {
                    Text("扫描目录导入", color = Color(0xFF66AAFF), fontSize = 11.sp)
                }
            }
            Text(
                modelDirText,
                color = Color(0xFF77777F), fontSize = 9.sp,
            )

            // ⑦ 版本号 + 模型更新说明
            Text(
                state.versionText,
                color = Color(0xFF9A9AA6), fontSize = 10.sp,
            )
            Text(
                "模型更新：把 PC 试验台打出的 model_pack.zip 放进上面的模型目录，点「扫描目录导入」；" +
                    "或直接更新应用（新版应用通常随包附带新模型）。校验失败会自动回退内置模型组。",
                color = Color(0xFF77777F), fontSize = 10.sp,
            )

            state.fallbackTip?.let {
                Text(it, color = Color(0xFFFFAA55), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun IntervalSlider(
    label: String,
    note: String,
    valueMs: Long,
    onChange: (Long) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${valueMs / 1000.0}s", color = Color(0xFFFFCC66), fontSize = 12.sp)
        }
        androidx.compose.material3.Slider(
            value = valueMs.toFloat(),
            onValueChange = { onChange((it.toLong() / 500L) * 500L) },   // 0.5s 步进
            valueRange = 500f..10000f,
        )
        Text(note, color = Color(0xFF9A9AA6), fontSize = 10.sp)
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color(0xFF111118) else Color.White,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFFFCC66) else Color(0xFF33333D))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Text(
        symbol,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .background(Color(0xFF33333D), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
