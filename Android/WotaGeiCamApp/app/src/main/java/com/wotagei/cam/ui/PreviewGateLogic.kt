package com.wotagei.cam.ui

import com.wotagei.cam.camera.CameraUiState
import com.wotagei.cam.camera.DeviceStatus
import com.wotagei.cam.camera.PreviewStatus

/**
 * 预览遮罩的「打开中」判定（Y60）。
 *
 * 只回答一件事：当前该给转圈，还是该给「拒权/失败 + 重试」。
 *
 * - **缺相机权限时一律不给转圈**：权限弹窗关闭后 [CameraUiState.device] 常停在 CLOSE
 *   （甚至还没枚举到 OPEN_ING），若先命中打开中分支，用户看到的是永远转不完的圈，
 *   页内拒权提示与「去设置」入口被盖住、不可达。拒权是确定态，必须直呈。
 * - [PreviewStatus.ERROR] 是真正的失败档，同样不转圈（既有口径）。
 * - 其余 OPEN_ING / CLOSE / OPEN_SUCCEED 是「已授权但会话未就绪」——枚举能力中、
 *   会话重建、回场重开都落在这三档，转圈是正确反馈（2026-10-30 真机：这 ~1s 若显示
 *   失败文案会把用户吓退）。
 */
fun previewGateOpening(ui: CameraUiState): Boolean =
    !ui.needsCameraPermission &&
        ui.preview != PreviewStatus.ERROR &&
        (
            ui.device == DeviceStatus.OPEN_ING ||
                ui.device == DeviceStatus.CLOSE ||
                ui.device == DeviceStatus.OPEN_SUCCEED
            )