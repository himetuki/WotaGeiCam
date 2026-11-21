import Foundation

/// 相机权限/会话对外可观察的失败分类。
///
/// 该类型不依赖 AVFoundation，平台层负责把 `AVAuthorizationStatus` 和
/// `AVCaptureSession` 的运行时错误映射到这里；UI 再映射为具体文案。
public enum CameraFailure: String, Equatable, Sendable {
    case permissionDenied
    case permissionRestricted
    case cameraUnavailable
    case configurationFailed
    case runtimeError
}

/// 基础预览状态机的状态。
public enum CameraSessionPhase: Equatable, Sendable {
    case idle
    case requestingPermission
    case configuring
    case running
    case interrupted
    case failed(CameraFailure)

    public var isRunning: Bool {
        if case .running = self {
            return true
        }
        return false
    }

    public var isFailure: Bool {
        if case .failed = self {
            return true
        }
        return false
    }
}

/// 驱动基础预览状态机的事件。
public enum CameraSessionEvent: Equatable, Sendable {
    case requestPermission
    case permissionGranted
    case permissionDenied(restricted: Bool)
    case sessionStarted
    case sessionStopped
    case interrupted
    case resumed
    case failed(CameraFailure)
    case cameraUnavailable
}

/// 串行相机队列使用的纯状态机。
///
/// 状态转换刻意保持保守：运行中重复收到启动/恢复事件不会打断预览；
/// 后台停止不会覆盖已经展示的失败原因；权限被拒和硬件缺失有独立失败分类。
public struct CameraSessionStateMachine: Equatable, Sendable {
    public private(set) var phase: CameraSessionPhase

    public init(initialPhase: CameraSessionPhase = .idle) {
        self.phase = initialPhase
    }

    @discardableResult
    public mutating func apply(_ event: CameraSessionEvent) -> CameraSessionPhase {
        switch event {
        case .requestPermission:
            switch phase {
            case .idle, .interrupted, .failed:
                phase = .requestingPermission
            default:
                break
            }

        case .permissionGranted:
            switch phase {
            case .idle, .requestingPermission, .failed, .interrupted:
                phase = .configuring
            default:
                break
            }

        case .permissionDenied(let restricted):
            phase = .failed(restricted ? .permissionRestricted : .permissionDenied)

        case .sessionStarted:
            phase = .running

        case .sessionStopped:
            switch phase {
            case .requestingPermission, .configuring, .running, .interrupted:
                phase = .idle
            default:
                break
            }

        case .interrupted:
            switch phase {
            case .configuring, .running:
                phase = .interrupted
            default:
                break
            }

        case .resumed:
            if phase == .interrupted {
                phase = .running
            }

        case .failed(let failure):
            phase = .failed(failure)

        case .cameraUnavailable:
            phase = .failed(.cameraUnavailable)
        }

        return phase
    }
}
