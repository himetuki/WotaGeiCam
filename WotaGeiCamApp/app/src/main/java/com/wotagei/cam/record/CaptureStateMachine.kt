package com.wotagei.cam.record

/**
 * 内录授权链路的状态机——**纯 Kotlin 层**：无任何 android 依赖（纯度守卫锁死，
 * 同 player/CompareTimeline 先例），JVM 单测直打全部转移。
 *
 * 状态：`Idle → Authorizing → Active / Failed → Revoked`（Revoked / Failed / Active 均可重新授权）。
 * 「Revoked = 授权过的会话被撤销」承载批 3 的环境音回退语义：见此态即回落麦克风采环境音。
 */
sealed class CaptureState {
    /** 未授权、无会话 */
    object Idle : CaptureState()

    /** 授权流程中：系统授权框已拉起，或同意后正在建链（FGS→getMediaProjection→回调→配置） */
    object Authorizing : CaptureState()

    /** 会话在位，可建设备内录 AudioRecord */
    object Active : CaptureState()

    /** 建链失败（[reason] 供 UI 展示）；可重新授权 */
    data class Failed(val reason: String) : CaptureState()

    /** 会话被撤销（用户状态栏停止投屏 / 系统收回）：批 3 据此回退环境音 */
    object Revoked : CaptureState()
}

sealed class CaptureEvent {
    /** 拉起系统授权框（createScreenCaptureIntent） */
    object ConsentRequested : CaptureEvent()

    /** 用户在授权框取消/返回 */
    object ConsentCancelled : CaptureEvent()

    /** 建链全链成功（服务回执 projection） */
    object Established : CaptureEvent()

    /** 建链任一步失败 */
    data class EstablishFailed(val reason: String) : CaptureEvent()

    /** MediaProjection.onStop：用户停止投屏或系统撤销 */
    object ProjectionStopped : CaptureEvent()

    /** 主动关停（controller.shutdown） */
    object Shutdown : CaptureEvent()
}

/**
 * 状态转移表（全函数、无异常路径）：未列出的 (state, event) 组合一律原态返回。
 * - ConsentRequested：Idle/Active/Failed/Revoked 都可发起（Active 发起 = 重复授权重建）；
 *   已在 Authorizing 时重复拉起无效。
 * - ConsentCancelled：只从 Authorizing 回滚到 Idle，其余态原样。
 * - Established / EstablishFailed：只在 Authorizing 生效（乱序回执不凭空改态）。
 * - ProjectionStopped：授权过但会话被撤（Authorizing/Active/Failed 时都可能发生）→ Revoked。
 * - Shutdown：任意态归 Idle。
 */
fun transition(state: CaptureState, event: CaptureEvent): CaptureState = when (event) {
    is CaptureEvent.ConsentRequested ->
        if (state is CaptureState.Authorizing) state else CaptureState.Authorizing
    is CaptureEvent.ConsentCancelled ->
        if (state is CaptureState.Authorizing) CaptureState.Idle else state
    is CaptureEvent.Established ->
        if (state is CaptureState.Authorizing) CaptureState.Active else state
    is CaptureEvent.EstablishFailed ->
        if (state is CaptureState.Authorizing) CaptureState.Failed(event.reason) else state
    is CaptureEvent.ProjectionStopped ->
        if (state is CaptureState.Authorizing || state is CaptureState.Active || state is CaptureState.Failed) {
            CaptureState.Revoked
        } else {
            state
        }
    is CaptureEvent.Shutdown -> CaptureState.Idle
}
