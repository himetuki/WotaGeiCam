import UIKit
import AVFoundation
import CoreGraphics
#if canImport(WotaCore)
import WotaCore
#endif

/// 播放画面宿主（`AVPlayerLayer` + 双指缩放/平移变换 + 视频方向）。
///
/// 设计口径（与 Android `WotaPlayerSurface` 等价，实现重写）：
/// - **不硬编码机型方向**：显示角仍由当前视频轨的 `preferredTransform` 归一（0/90/180/270），
///   非 90 度倍数的变换按 0 处理。但 `AVPlayerLayer` 在 iOS 上已按该 transform 呈现画面
///   （`AVPlayerItem.presentationSize` 同源），故本视图把角度记在 `trackRotationDegrees`
///   供诊断/布局，不默认再叠一次旋转（叠加会双重旋转）。
/// - **叠加顺序稳定**：`旋转 → 缩放 → 平移`（`rotationDegrees` 默认 0，供非 AVPlayerLayer
///   的自绘表面手工旋转）。平移永远按**屏幕方向**跟手，因此平移在最外层。
/// - **镜像**：水平翻转用 `scaleX` 取负，与缩放/平移复合在同一层 `transform` 上。
/// - **边界钳制**：平移域逐轴 `±视口×(s−1)/2`，只由 `WotaCore.PlayerZoomPanMath` 一处决定，
///   UIKit 与对比页共用同一份钳制，不各抄一份。
/// - **手势**：双指捏合和平移共同维护视口变换，始终在未经变换的宿主坐标中计算位移。
final class PlayerSurfaceView: UIView {

    // MARK: - 图层

    // 只变换子层。宿主同时是裁剪视口和手势坐标系，缩放时它自己的坐标不动。
    private let playerLayer = AVPlayerLayer()

    var player: AVPlayer? {
        get { playerLayer.player }
        set { playerLayer.player = newValue }
    }

    // MARK: - 视图状态（由外部状态机持有，本视图只做呈现与手势消费）

    /// 水平镜像（Android `mirror`）。
    var isMirrored: Bool = false {
        didSet { if isMirrored != oldValue { applyTransform() } }
    }

    /// 旋转角（度）。**仅用于非 AVPlayerLayer 的自绘表面或上层手工旋转**；默认 0。
    ///
    /// 注意：本工程用 `AVPlayerLayer` 承载画面，iOS 已按视频轨的 `preferredTransform`
    /// （`AVPlayerItem.presentationSize` 同源）呈现画面。这里若再把同一角度写进图层
    /// `transform`，会变成双重旋转。故 `updateRotation(from:)` 只记录
    /// `trackRotationDegrees` 供诊断/布局使用，不自动写入本属性。
    var rotationDegrees: CGFloat = 0 {
        didSet { if rotationDegrees != oldValue { applyTransform() } }
    }

    /// 由当前视频轨 `preferredTransform` 归一出的显示角（0/90/180/270，只读诊断值）。
    private(set) var trackRotationDegrees: CGFloat = 0

    /// 缩放倍率（0.1...4，由 `PlayerZoomPanMath.clampScale` 钳制）。
    var zoomScale: CGFloat = 1 {
        didSet { if zoomScale != oldValue { applyTransform() } }
    }

    /// 平移量（pt，屏幕方向；由 `PlayerZoomPanMath.clampPan` 钳制）。
    var panOffset: CGSize = .zero {
        didSet {
            if panOffset.width != oldValue.width || panOffset.height != oldValue.height {
                applyTransform()
            }
        }
    }

    /// 读取当前视频轨的显示角（度），供上层在换片时刷新。
    ///
    /// **不直接改 `rotationDegrees`**：`AVPlayerLayer` 已经按 `preferredTransform` 呈现，
    /// 再叠加一次会双重旋转。这里只把角度记录下来（镜像/缩放/平移仍在屏幕坐标系里做）。
    func updateRotation(from track: AVAssetTrack?) {
        guard let track = track else {
            trackRotationDegrees = 0
            return
        }
        trackRotationDegrees = PlayerVideoOrientationMath.degrees(from: track.preferredTransform)
    }

    // MARK: - 初始化

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    private func configure() {
        backgroundColor = .black
        isOpaque = true
        clipsToBounds = true
        playerLayer.videoGravity = .resizeAspect
        layer.addSublayer(playerLayer)
        addGestureRecognizers()
    }

    // MARK: - 变换

    /// 叠加顺序：旋转 → 缩放 → 平移（平移在最外层）。
    ///
    /// 旋转与缩放都绕图层中心（`CGAffineTransform` 的默认锚点），
    /// 先 `rotate` 再 `scale` 与 Android `graphicsLayer(rotationZ, scaleX, scaleY)` 同构。
    func applyTransform() {
        let scaleX = isMirrored ? -zoomScale : zoomScale
        let transform = CGAffineTransform(scaleX: scaleX, y: zoomScale)
            .concatenating(CGAffineTransform(rotationAngle: rotationDegrees * .pi / 180))
            .concatenating(CGAffineTransform(translationX: panOffset.width, y: panOffset.height))
        // 手势每帧写 transform。若走 Core Animation 隐式动画，手指停下后图层还会
        // 继续补间 0.25s，表现为「跟手迟滞/回弹」。这里显式关掉隐式动作，只呈现当前帧。
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.setAffineTransform(transform)
        CATransaction.commit()
    }

    /// 依视口尺寸重夹平移（视口变化 / 捏回 1x 时调用）。
    func reclampPan() {
        let clamped = PlayerZoomPanMath.clampPan(panOffset, scale: zoomScale, viewport: bounds.size)
        if clamped.width != panOffset.width || clamped.height != panOffset.height {
            panOffset = clamped
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.bounds = CGRect(origin: .zero, size: bounds.size)
        playerLayer.position = CGPoint(x: bounds.midX, y: bounds.midY)
        CATransaction.commit()
        reclampPan()
    }

    /// 复位缩放与平移（换片 / 退出播放）。
    func resetZoomPan() {
        zoomScale = 1
        panOffset = .zero
        applyTransform()
    }

    // MARK: - 手势（双指）

    private var pinchRecognizer: UIPinchGestureRecognizer?
    private var panRecognizer: UIPanGestureRecognizer?

    /// 倍率与平移都是相邻事件的增量。仅平移时倍率为 1，保留当前缩放。
    var onPinchChanged: ((CGFloat, CGSize) -> Void)?
    var isTwoFingerGestureActive: Bool { pinchActive || panActive }

    private var pinchActive = false
    private var panActive = false
    private var lastPinchLocation: CGPoint = .zero
    private var lastPanLocation: CGPoint = .zero

    private func addGestureRecognizers() {
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        pinch.delegate = self
        addGestureRecognizer(pinch)
        pinchRecognizer = pinch

        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        // 只接受双指平移：单指留给单击显隐与进度条。
        pan.minimumNumberOfTouches = 2
        pan.maximumNumberOfTouches = 2
        pan.delegate = self
        addGestureRecognizer(pan)
        panRecognizer = pan
    }

    @objc private func handlePinch(_ recognizer: UIPinchGestureRecognizer) {
        switch recognizer.state {
        case .began:
            pinchActive = true
            lastPinchLocation = recognizer.location(in: self)
            recognizer.scale = 1
        case .changed:
            let location = recognizer.location(in: self)
            let scale = recognizer.scale
            recognizer.scale = 1
            guard recognizer.numberOfTouches == 2 else {
                lastPinchLocation = location
                return
            }
            let translation = CGSize(
                width: location.x - lastPinchLocation.x,
                height: location.y - lastPinchLocation.y
            )
            lastPinchLocation = location
            onPinchChanged?(scale, translation)
        case .ended, .cancelled, .failed:
            pinchActive = false
            if panActive, let pan = panRecognizer {
                lastPanLocation = pan.location(in: self)
            }
            reclampPan()
        default:
            break
        }
    }

    @objc private func handlePan(_ recognizer: UIPanGestureRecognizer) {
        switch recognizer.state {
        case .began:
            panActive = true
            lastPanLocation = recognizer.location(in: self)
        case .changed:
            let location = recognizer.location(in: self)
            defer { lastPanLocation = location }
            // 捏合活跃时由其质心带动平移，避免两套识别器重复累计。
            guard !pinchActive, recognizer.numberOfTouches == 2 else { return }
            let translation = CGSize(
                width: location.x - lastPanLocation.x,
                height: location.y - lastPanLocation.y
            )
            onPinchChanged?(1, translation)
        case .ended, .cancelled, .failed:
            panActive = false
            reclampPan()
        default:
            break
        }
    }
}

extension PlayerSurfaceView: UIGestureRecognizerDelegate {

    /// 捏合与双指平移必须并行（同一个双指手势里同时发生），其余识别器不并行。
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        if gestureRecognizer === panRecognizer, otherGestureRecognizer === pinchRecognizer {
            return true
        }
        if gestureRecognizer === pinchRecognizer, otherGestureRecognizer === panRecognizer {
            return true
        }
        return false
    }
}

// MARK: - 视频方向

/// 由 `AVAssetTrack.preferredTransform` 判定显示旋转角（度）。
///
/// **不硬编码机型方向**：读 `preferredTransform` 的实际角度，只认 90 度倍数；
/// 无法判定时返回 0（保持原始方向）。
enum PlayerVideoOrientationMath {

    /// 逐档归一：0 / 90 / 180 / 270（度）。
    static func degrees(from transform: CGAffineTransform) -> CGFloat {
        let angle = atan2(Double(transform.b), Double(transform.a))
        let normalized = normalizeAngleRadians(angle)
        return CGFloat(normalized * 180 / .pi)
    }

    private static func normalizeAngleRadians(_ angle: Double) -> Double {
        let twoPi = 2 * Double.pi
        var value = angle.truncatingRemainder(dividingBy: twoPi)
        if value < 0 { value += twoPi }
        let quarter = Double.pi / 2
        let step = (value / quarter).rounded()
        return step * quarter
    }
}
