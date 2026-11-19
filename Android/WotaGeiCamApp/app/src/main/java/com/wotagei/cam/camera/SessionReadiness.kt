package com.wotagei.cam.camera

/**
 * 一次相机会话的**可执行就绪快照**（R01，2026-11-19）。
 *
 * 「预览状态 == ING」只说明我们发过意图，不证明 `setRepeatingRequest` 真的被会话接纳；
 * 首录与换段交接若只看状态流，会在旧会话/旧世代上乐观 start。这份快照把就绪绑到
 * **设备世代 + 会话配置世代 + 录制面世代**上，调用方必须等「发起操作之后的新快照」。
 *
 * @property generation 设备世代（切镜头/关设备递增）
 * @property attempt 会话配置世代（每次 close/重建递增；旧回调不能把它写回）
 * @property configured 当前世代的会话已配置成功
 * @property repeatingSubmitted 当前世代已成功提交 repeating（失败时保持 false）
 * @property recordingTargetPresent DIRECT 录制面是否在会话形状里
 * @property recordingTargetEpoch 录制面每次变更递增（同一 Surface 重设也算一次交接）
 * @property recordingWindow 录制业务窗口（含 PREPARE）是否打开
 */
data class CameraSessionReadiness(
    val generation: Int = 0,
    val attempt: Int = 0,
    val configured: Boolean = false,
    val repeatingSubmitted: Boolean = false,
    val recordingTargetPresent: Boolean = false,
    val recordingTargetEpoch: Int = 0,
    val recordingWindow: Boolean = false
) {
    /** 真正可出帧：配置成功且 repeating 已被接纳，而不是状态流里的乐观 ING。 */
    val ready: Boolean get() = configured && repeatingSubmitted
}

/**
 * 等号档读出探测的唯一准入判据（R02 抽成纯函数，JVM 可测）。
 *
 * 录制窗口内绝不探测：GPU 路没有 `extraSurface`，只看相机面向量会漏掉整段 GPU 录制；
 * 探测请求是等号 SENSOR 三键，漏进录制就会改写用户选的曝光。
 */
internal fun exposureProbeAllowed(
    deviceOpen: Boolean,
    directSurfacePresent: Boolean,
    recordingWindow: Boolean,
    manualAe: Boolean,
    correctionAuto: Boolean,
    correctionApplicable: Boolean,
    alreadyLatched: Boolean
): Boolean = deviceOpen &&
    !directSurfacePresent &&
    !recordingWindow &&
    manualAe &&
    correctionAuto &&
    correctionApplicable &&
    !alreadyLatched

