package com.wotagei.cam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机验证 WOTA 核心需求：1/24 与 1/25 秒快门 + 手动 ISO 能否被 HAL 真正应用。
 * 判据不是"我们下发了什么"，而是 CaptureResult 里 HAL 回报的实际曝光时间。
 */
@RunWith(AndroidJUnit4::class)
class ManualExposureTest {

    private fun backCameraId(mgr: CameraManager): String =
        mgr.cameraIdList.first {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }

    private fun measure(id: String, iso: Int, exposureNs: Long, fps: Int): Pair<Long, Int>? {
        val mgr = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // Camera2 回调必须有 Looper 线程
        val thread = HandlerThread("wota-exposure").apply { start() }
        val handler = Handler(thread.looper)
        val opened = CountDownLatch(1)
        val got = CountDownLatch(1)
        var applied: Pair<Long, Int>? = null
        var device: CameraDevice? = null
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
            override fun onDisconnected(d: CameraDevice) { d.close() }
            override fun onError(d: CameraDevice, error: Int) { opened.countDown() }
        }, handler)
        if (!opened.await(5, TimeUnit.SECONDS)) return null
        val cam = device ?: return null

        val cc = mgr.getCameraCharacteristics(id)
        val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        // 用 JPEG 流占位即可，重点是拿 CaptureResult
        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        req.addTarget(reader.surface)
        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        req.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        req.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        req.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
        req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))

        val listener = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
            ) {
                val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val g = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (e != null && g != null && got.count > 0) {
                    applied = e to g
                    got.countDown()
                }
            }
        }
        @Suppress("DEPRECATION")
        cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                s.setRepeatingRequest(req.build(), listener, null)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { got.countDown() }
        }, handler)

        val ok = got.await(8, TimeUnit.SECONDS)
        runCatching { reader.close() }
        runCatching { cam.close() }
        thread.quitSafely()
        val a = applied
        Log.i(TAG, "req iso=$iso exp=$exposureNs fps=$fps -> ok=$ok applied=$a " +
            "范围=${cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)} " +
            "ISO=${cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}")
        return if (ok) a else null
    }

    @Test
    fun shutter24And25AreActuallyAppliedByHal() {
        val mgr = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCameraId(mgr)

        val ns24 = 1_000_000_000L / 24   // 41_666_666
        val ns25 = 1_000_000_000L / 25   // 40_000_000
        val ns60 = 1_000_000_000L / 60   // 16_666_666

        val r24 = measure(id, 400, ns24, 25)
        val r25 = measure(id, 400, ns25, 25)
        val r60 = measure(id, 800, ns60, 25)

        // 三条都要拿到 HAL 回报，且回报值与请求值同档（±20% 容差，HAL 会量化）
        assertTrue("1/24 快门未取得 HAL 回报", r24 != null)
        assertTrue("1/25 快门未取得 HAL 回报", r25 != null)
        assertTrue("1/60 快门未取得 HAL 回报", r60 != null)
        assertTrue("1/24 实际曝光偏离请求：${r24!!.first}", kotlin.math.abs(r24!!.first - ns24) < ns24 * 0.2)
        assertTrue("1/25 实际曝光偏离请求：${r25!!.first}", kotlin.math.abs(r25!!.first - ns25) < ns25 * 0.2)
        assertTrue("1/60 实际曝光偏离请求：${r60!!.first}", kotlin.math.abs(r60!!.first - ns60) < ns60 * 0.2)
        // 手动 ISO 也要真生效
        assertTrue("ISO 未生效：${r60!!.second}", kotlin.math.abs(r60.second - 800) <= 1)
    }

    private companion object { const val TAG = "WotaExposure" }
}
