import UIKit
import AVFoundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 单击/双击仲裁（对齐 Android `TapArbiter`）。
///
/// 单击必须等满双击窗口才落地：否则第二指抬起来时显隐已经切过一次，
/// 「双击启停」会顺带把控件栏闪一下。等满窗口才切显隐，是这两个手势能共存的唯一办法。
final class PlayerTapArbiter {

    enum Decision: Equatable {
        /// 这一击先当作单击，调用方挂起一个窗口的定时器，到点再切显隐。
        case deferredToggle
        /// 这一击是双击的第二击：取消挂起的显隐，改判为启停。
        case playPause
    }

    private let windowMs: Double
    /// 挂起中的单击到期时刻（ms）；0 = 没有挂起。
    private var pendingDueMs: Double = 0

    init(windowMs: Double = 240) {
        self.windowMs = windowMs
    }

    func onPointerUp(nowMs: Double) -> Decision {
        if pendingDueMs != 0, nowMs <= pendingDueMs {
            pendingDueMs = 0
            return .playPause
        }
        pendingDueMs = nowMs + windowMs
        return .deferredToggle
    }

    /// 挂起的单击定时器到点。返回 true 才真的切显隐。
    func onToggleDue(nowMs: Double) -> Bool {
        if pendingDueMs == 0 { return false }
        if nowMs < pendingDueMs { return false }
        pendingDueMs = 0
        return true
    }

    func cancelPending() {
        pendingDueMs = 0
    }
}
