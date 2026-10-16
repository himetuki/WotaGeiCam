package com.wotagei.cam

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 对焦锁定（用户反馈第 2 条）的**真机取证探针**：路径 A（`AF_MODE_AUTO` 单次触发后由
 * repeating 保持）在这台设备上到底成不成立、高帧率档接不接受 AUTO/区域覆写。
 *
 * 三件事（结论走 `sendStatus`，本机 ROM 会丢应用自己的 Log 标签、logcat 不可靠）：
 * 1. 逐镜头能力：`afModes`/`maxRegionsAf`/`maxRegionsAe`（R3a 禁用路径的实际分布）；
 * 2. 锁定保持：TEMPLATE_RECORD + `AF_MODE_AUTO` + 区域 + `TRIGGER_START` → 轮询
 *    `CONTROL_AF_STATE` 至 `FOCUSED_LOCKED/NOT_FOCUSED_LOCKED`（5s 超时 FAIL）；随后静置 3s
 *    连续采样——**预期不出现 `PASSIVE_SCAN`、保持 LOCKED**，这就是"锁定语义"的设备证据；
 *    同时记录 `LENS_FOCUS_DISTANCE` 读数（路径 B「屈光度冻结」兜底的可行性评估）；
 * 3. 高帧率档（Q6 的结论输入）：`SESSION_HIGH_SPEED` 下重复流程，记录 AUTO + 区域是否被接受、
 *    是否发生降级。**只报告不判死**（HAL 差异是探针的观测对象，不是本用例的断言对象）。
 *
 * 跑法沿用既有探针模式：装 probe 变体的 app + androidTest 两个 APK 后
 * `am instrument -e class com.wotagei.cam.FocusLockDeviceProbeTest#<用例>`；
 * 相机在锁屏/后台会被系统拒开（`cannot open camera from background`），先亮屏解锁再跑。
 * 输出保持 PRIVATE 面的画面只为占住会话，不做任何消费注入；分析全部读 CaptureResult。
 */
@RunWith(AndroidJUnit4::class)
class FocusLockDeviceProbeTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun line(text: String) {
        instr.sendStatus(Activity.RESULT_OK, Bundle().apply { putString("wota", text) })
    }

    private fun backCameraId(mgr: CameraManager): String =
        mgr.cameraIdList.first {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }

    /** 逐镜头能力上报（只读 characteristics，不开相机） */
    @Test
    fun reportAbilityPerLens() {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = mgr.cameraIdList
        assertTrue("一颗镜头都没枚举到", ids.isNotEmpty())
        ids.forEach { id ->
            val cc = mgr.getCameraCharacteristics(id)
            val afModes = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
            val regionsAf = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val regionsAe = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            line(
                "camera=$id afModes=$afModes(maxRegionsAf=$regionsAf maxRegionsAe=$regionsAe) " +
                    "→ 锁定条目可用=${afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)}"
            )
        }
    }

    /** 锁定保持取证（普通会话）：到 LOCKED → 静置 3s 无 PASSIVE_SCAN → 记屈光度读数 */
    @Test
    fun probeAutoLockHold() {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCameraId(mgr)
        val cc = mgr.getCameraCharacteristics(id)
        val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        assertTrue("缺流配置表，无法建会话", map != null)
        val afModes = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
        if (!afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            line("camera=$id 不支持 AF_MODE_AUTO，锁定走 R3a 禁用路径，探针跳过")
            return
        }
        val maxRegionsAf = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        // 尺寸与区域一律从 characteristics 现取（不写死机型值）：最小 PRIVATE 档最省带宽
        val outSize = map!!.getOutputSizes(ImageFormat.PRIVATE)?.minByOrNull { it.width * it.height }
        assertTrue("本机没有 PRIVATE 输出档", outSize != null)
        val size = outSize!!

        val thread = HandlerThread("wota-focus-probe").apply { start() }
        val handler = Handler(thread.looper)
        val device = openDevice(mgr, id, handler)
        try {
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 2)
            // 不透画面（本探针不判画面）：到一帧丢一帧，免得缓冲堆满卡住 repeating
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)
            val states = Collections.synchronizedList(ArrayList<Int>())
            val distances = Collections.synchronizedList(ArrayList<Float>())
            val listener = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    result.get(CaptureResult.CONTROL_AF_STATE)?.let { states.add(it) }
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { distances.add(it) }
                }
            }
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            req.addTarget(reader.surface)
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            if (maxRegionsAf > 0) {
                req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(centerRegion(size)))
            }
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)

            val session = configureRegular(device, reader.surface, handler)
            try {
                session.setRepeatingRequest(req.build(), listener, handler)
                line("repeating 已下发：size=${size.width}x${size.height} maxRegionsAf=$maxRegionsAf")

                // 段 2：单发 START（与引擎 engageFocusLock 的段 2 同形态）
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                session.capture(req.build(), listener, handler)
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)

                // 段 3：轮询至 LOCKED（5s 超时 FAIL）
                val startedAt = SystemClock.elapsedRealtime()
                var lockedState = 0
                while (SystemClock.elapsedRealtime() - startedAt < 5_000) {
                    val s = states.lastOrNull() ?: 0
                    if (s == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        s == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    ) {
                        lockedState = s
                        break
                    }
                    Thread.sleep(50)
                }
                line("锁定到达：state=$lockedState 用时=${SystemClock.elapsedRealtime() - startedAt}ms（0=超时）")
                assertTrue(
                    "5s 内没等到 LOCKED 态（states=${states.toList()}）——路径 A 在此设备不成立",
                    lockedState != 0
                )

                // 段 4：静置 3s 连续采样——出现 PASSIVE_SCAN = 连续对焦又在搜寻，锁定语义不成立
                states.clear()
                Thread.sleep(3_000)
                val after = states.toList()
                val passive = after.count {
                    it == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                        it == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                }
                line("静置 3s：帧数=${after.size} PASSIVE=$passive 终态=${after.lastOrNull()} 分布=${after.distinct()}")
                line("LENS_FOCUS_DISTANCE 尾部读数=${distances.takeLast(3)}（路径 B 可行性）")
                assertTrue(
                    "锁定后出现 PASSIVE_SCAN×$passive——AUTO 保持不成立，转路径 B（屈光度冻结）",
                    passive == 0
                )
            } finally {
                runCatching { session.stopRepeating() }
                runCatching { session.close() }
                runCatching { reader.close() }
            }
        } finally {
            runCatching { device.close() }
            thread.quitSafely()
        }
    }

    /** 高帧率档取证（Q6 结论输入）：高速会话下 AUTO + 区域是否被接受、是否降级——只报告 */
    @Test
    fun probeHighSpeedAcceptsAutoAndRegion() {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCameraId(mgr)
        val cc = mgr.getCameraCharacteristics(id)
        val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        assertTrue("缺流配置表，无法建会话", map != null)
        val highSizes = map!!.highSpeedVideoSizes
        if (highSizes.isEmpty()) {
            line("camera=$id 无高速尺寸档，跳过（Q6 结论=本机无 >60fps 会话档）")
            return
        }
        // 取「有帧率范围」的最高档：档位一律现读，不写死
        val picked = highSizes.sortedByDescending { it.width * it.height }
            .firstNotNullOfOrNull { s ->
                map.getHighSpeedVideoFpsRangesFor(s)?.firstOrNull()?.let { it to s }
            }
        assertTrue("高速尺寸都没有帧率范围，跳过", picked != null)
        val (fpsRange, size) = picked!!
        val afModes = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
        val maxRegionsAf = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        line("高速档：size=${size.width}x${size.height} fps=[${fpsRange.lower},${fpsRange.upper}] " +
            "afAuto=${afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)} maxRegionsAf=$maxRegionsAf")
        if (!afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            line("高速档同理不支持 AUTO：锁定条目在该档禁用（R2/Q6 的禁用路径成立）")
            return
        }

        val thread = HandlerThread("wota-focus-hs").apply { start() }
        val handler = Handler(thread.looper)
        val device = openDevice(mgr, id, handler)
        try {
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 2)
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)
            val states = Collections.synchronizedList(ArrayList<Int>())
            val listener = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    result.get(CaptureResult.CONTROL_AF_STATE)?.let { states.add(it) }
                }
            }
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            req.addTarget(reader.surface)
            req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            if (maxRegionsAf > 0) req.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(centerRegion(size)))
            req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)

            val session = configureHighSpeed(device, reader.surface, handler)
            if (session == null) {
                line("高速会话未配置成功：视为该档不可用（锁定在该档按 Q6 处置）")
                runCatching { reader.close() }
                return
            }
            try {
                val accepted = runCatching {
                    session.setRepeatingBurst(
                        session.createHighSpeedRequestList(req.build()), listener, handler
                    )
                }
                line("高速会话 AUTO+区域重复请求：${if (accepted.isSuccess) "被接受" else "被拒(${accepted.exceptionOrNull()?.message})"}")
                if (accepted.isFailure) return
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                val burst = runCatching {
                    session.captureBurst(session.createHighSpeedRequestList(req.build()), listener, handler)
                }
                req.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                line("高速单发 START：${if (burst.isSuccess) "已下发" else "被拒(${burst.exceptionOrNull()?.message})"}")
                Thread.sleep(2_000)
                line("高速档 AF_STATE 采样=${states.distinct()}（LOCKED 出现即该档锁定语义成立）")
            } finally {
                runCatching { session.stopRepeating() }
                runCatching { session.close() }
                runCatching { reader.close() }
            }
        } finally {
            runCatching { device.close() }
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun centerRegion(size: Size): MeteringRectangle {
        // 引擎 meteringBox 的中央等价形态（1000×1000 框、权重最大），坐标按当前输出尺寸折中
        val half = 1000
        val x = (size.width / 2 - half).coerceAtLeast(0)
        val y = (size.height / 2 - half).coerceAtLeast(0)
        return MeteringRectangle(x, y, half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun openDevice(mgr: CameraManager, id: String, handler: Handler): CameraDevice {
        val opened = CountDownLatch(1)
        var device: CameraDevice? = null
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                opened.countDown()
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
            }

            override fun onError(d: CameraDevice, error: Int) {
                opened.countDown()
            }
        }, handler)
        assertTrue("打开相机超时（锁屏/后台会拒开，先亮屏解锁）", opened.await(5, TimeUnit.SECONDS))
        val d = device
        assertTrue("相机打开失败（onError）", d != null)
        return d!!
    }

    @Suppress("DEPRECATION")
    private fun configureRegular(
        device: CameraDevice, surface: android.view.Surface, handler: Handler
    ): CameraCaptureSession {
        val configured = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                configured.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                configured.countDown()
            }
        }, handler)
        assertTrue("普通会话配置超时", configured.await(5, TimeUnit.SECONDS))
        assertTrue("普通会话配置失败", session != null)
        return session!!
    }

    @Suppress("DEPRECATION")
    private fun configureHighSpeed(
        device: CameraDevice, surface: android.view.Surface, handler: Handler
    ): CameraConstrainedHighSpeedCaptureSession? {
        val configured = CountDownLatch(1)
        var session: CameraConstrainedHighSpeedCaptureSession? = null
        device.createConstrainedHighSpeedCaptureSession(listOf(surface), object :
            CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s as? CameraConstrainedHighSpeedCaptureSession
                configured.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                configured.countDown()
            }
        }, handler)
        configured.await(5, TimeUnit.SECONDS)
        return session
    }
}
