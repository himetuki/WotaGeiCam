import AVFoundation
import Foundation
#if canImport(WotaCore)
import WotaCore
#endif

/// 最小相机参数快照：平台层把 `AVCaptureDevice` 的能力翻译成 UI 可展示的纯数据。
/// UI 不直接持有 AVFoundation 类型。
struct CameraParameterSnapshot {
    var deviceName: String
    var deviceModel: String
    var resolution: String
    var frameRate: String
    var exposure: ExposureBiasRange
    var exposureValue: Float
    var hasTorch: Bool
    var torchAvailable: Bool
    var torchEnabled: Bool
    /// 降级/不支持说明；UI 直接展示，不静默忽略。
    var notes: [String]

    static let unavailable = CameraParameterSnapshot(
        deviceName: "—",
        deviceModel: "—",
        resolution: "—",
        frameRate: "—",
        exposure: ExposureBiasRange(minimum: 0, maximum: 0),
        exposureValue: 0,
        hasTorch: false,
        torchAvailable: false,
        torchEnabled: false,
        notes: ["尚未建立相机输入，参数在预览启动后可用。"]
    )
}

/// 基础 WOTA 录制参数调整接口（最小集：曝光目标偏置 + 手电筒）。
///
/// 设计约束：
/// - 不假设 iOS 相机 API 与 Android Camera2 语义等价；每一项调整都先判断设备是否支持，
///   不支持时返回明确的降级说明，而不是静默失败；
/// - 所有设备操作都在 [CameraSessionController.sessionQueue] 上执行，与启停/配置互斥；
/// - 分辨率/帧率协商（更接近 Android `MediaRecorder` profile 的部分）留给后续录制任务，
///   这里只**读取并展示**当前 activeFormat，不擅自切换格式。
final class CameraParameterController {

    var onSnapshot: ((CameraParameterSnapshot) -> Void)?

    private let session: AVCaptureSession
    private let queue: DispatchQueue
    private let deviceProvider: () -> AVCaptureDevice?

    init(sessionController: CameraSessionController) {
        self.session = sessionController.session
        self.queue = sessionController.sessionQueue
        self.deviceProvider = { sessionController.currentVideoDevice() }
    }

    /// 刷新当前参数快照；结果在主线程回调。
    func refresh() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.publish(self.snapshotOnQueue())
        }
    }

    /// 设置手电筒；设备不支持时只更新提示文案。
    func setTorch(enabled: Bool) {
        queue.async { [weak self] in
            guard let self = self else { return }
            var notes: [String] = []

            if let device = self.deviceProvider() {
                if !device.hasTorch {
                    notes.append("该设备没有闪光灯，无法使用手电筒。")
                } else if !device.isTorchAvailable {
                    notes.append("手电筒当前不可用（可能被其他应用占用或温度过高）。")
                } else {
                    do {
                        try device.lockForConfiguration()
                        let mode: AVCaptureDevice.TorchMode = enabled ? .on : .off
                        if device.isTorchModeSupported(mode) {
                            device.torchMode = mode
                        } else {
                            notes.append("该设备不支持请求的手电筒模式（\(enabled ? "常亮" : "关闭")）。")
                        }
                        device.unlockForConfiguration()
                    } catch {
                        notes.append("无法锁定相机以设置手电筒：\(error.localizedDescription)")
                    }
                }
            } else {
                notes.append("尚未建立相机输入，无法设置手电筒。")
            }

            var snapshot = self.snapshotOnQueue()
            snapshot.notes = notes + snapshot.notes
            self.publish(snapshot)
        }
    }

    /// 设置曝光目标偏置（EV）；设备不支持时只更新提示文案。
    func setExposureBias(_ value: Float) {
        queue.async { [weak self] in
            guard let self = self else { return }
            var notes: [String] = []

            if let device = self.deviceProvider() {
                let range = ExposureBiasRange(
                    minimum: device.minExposureTargetBias,
                    maximum: device.maxExposureTargetBias
                )
                if range.isAdjustable {
                    do {
                        try device.lockForConfiguration()
                        device.setExposureTargetBias(range.clamp(value), completionHandler: nil)
                        device.unlockForConfiguration()
                    } catch {
                        notes.append("无法锁定相机以设置曝光：\(error.localizedDescription)")
                    }
                } else {
                    notes.append("该设备不支持曝光补偿调整，滑杆已禁用。")
                }
            } else {
                notes.append("尚未建立相机输入，无法设置曝光。")
            }

            var snapshot = self.snapshotOnQueue()
            snapshot.notes = notes + snapshot.notes
            self.publish(snapshot)
        }
    }

    // MARK: - 只在 sessionQueue 上调用

    private func snapshotOnQueue() -> CameraParameterSnapshot {
        guard let device = deviceProvider() else {
            return .unavailable
        }

        var notes: [String] = []

        let dimensions = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let resolution = "\(dimensions.width)×\(dimensions.height)"

        var maxFrameRate: Float = 0
        let ranges = device.activeFormat.videoSupportedFrameRateRanges
        for range in ranges {
            if range.maxFrameRate > maxFrameRate {
                maxFrameRate = range.maxFrameRate
            }
        }
        let frameRate = maxFrameRate > 0 ? String(format: "最高 %.0f fps", maxFrameRate) : "未知"

        let exposure = ExposureBiasRange(
            minimum: device.minExposureTargetBias,
            maximum: device.maxExposureTargetBias
        )
        if !exposure.isAdjustable {
            notes.append("该设备不支持曝光补偿，滑杆已禁用。")
        }

        let torchAvailable = device.hasTorch && device.isTorchAvailable
        if !torchAvailable {
            notes.append("该设备当前不可用手电筒（无闪光灯或被占用）。")
        }

        return CameraParameterSnapshot(
            deviceName: device.localizedName,
            deviceModel: device.modelID,
            resolution: resolution,
            frameRate: frameRate,
            exposure: exposure,
            exposureValue: device.exposureTargetBias,
            hasTorch: device.hasTorch,
            torchAvailable: torchAvailable,
            torchEnabled: device.torchMode == .on,
            notes: notes
        )
    }

    private func publish(_ snapshot: CameraParameterSnapshot) {
        DispatchQueue.main.async { [weak self] in
            self?.onSnapshot?(snapshot)
        }
    }
}
