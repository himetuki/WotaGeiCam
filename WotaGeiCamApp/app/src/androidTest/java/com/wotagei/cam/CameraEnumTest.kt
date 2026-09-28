package com.wotagei.cam

import android.app.Instrumentation
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.core.LensSlot
import com.wotagei.cam.core.enumerateLenses
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 镜头枚举取证（真机仪器测试，结果用 sendStatus 打到 `am instrument` stdout）。
 *
 * 本机 ROM 会丢应用自己的 Log 标签，所以取证不能靠 logcat；每行一个 INSTRUMENTATION_STATUS 记录。
 * 只做只读探测，不开相机、不落文件。
 */
@RunWith(AndroidJUnit4::class)
class CameraEnumTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, Bundle().apply { putString("wota", text) })
    }

    @Test
    fun dumpCameraEnumeration() {
        val ctx: Context = instr.targetContext.applicationContext
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = mgr.cameraIdList
        line("CAM ids=${ids.toList()}")
        val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val rotation = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
        line("SYS sdk=${Build.VERSION.SDK_INT} brand=${Build.BRAND} model=${Build.MODEL} rotation=$rotation")
        for (id in ids) {
            val cc = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull()
            if (cc == null) {
                line("CAM $id characteristics=UNREADABLE")
                continue
            }
            val facing = cc.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_BACK
            val level = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
            val caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val physicals = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cc.physicalCameraIds else emptySet()
            }.getOrDefault(emptySet())
            val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
            val physSize = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val orient = cc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1
            val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val priv = map?.getOutputSizes(android.graphics.ImageFormat.PRIVATE)?.joinToString(",") { "${it.width}x${it.height}" }
            val jpeg = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.joinToString(",") { "${it.width}x${it.height}" }
            line(
                "CAM id=$id facing=${facingName(facing)} level=$level orient=$orient" +
                    " focals=${focals.joinToString(",")} sensorPhys=${physSize?.width}x${physSize?.height}" +
                    " logical=${caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)}" +
                    " physicalIds=${physicals.sorted().joinToString(",")}"
            )
            line("CAM id=$id caps=${caps.joinToString(",")}")
            line("CAM id=$id privateSizes=[$priv]")
            line("CAM id=$id jpegSizes=[$jpeg]")
        }
        val slots = enumerateLenses(mgr)
        line("SLOTS count=${slots.size}")
        slots.forEach { s: LensSlot ->
            line(
                "SLOT key=${s.key} type=${s.type} logic=${s.logicId} phys=${s.physicalId ?: "-"}" +
                    " eq=${s.eqFocal}mm orient=${s.ability.sensorOrientation}" +
                    " videoSizes=${s.ability.videoSizes.size} previewSizes=${s.ability.previewSizes.size}"
            )
        }
        // 取证补充：dumpsys 报 4 台 HAL 设备，逐个试读非公开 id，确认框架到底暴露了哪几颗
        for (probe in listOf("2", "3", "80", "100", "0x2")) {
            val err = runCatching { mgr.getCameraCharacteristics(probe) }.exceptionOrNull()
            if (err != null) {
                line("PROBE id=$probe characteristics=${err.javaClass.simpleName}:${err.message}")
                continue
            }
            val cc = mgr.getCameraCharacteristics(probe)
            val facing = cc.get(CameraCharacteristics.LENS_FACING) ?: -1
            val level = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
            val orient = cc.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1
            val focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
            val caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val physicals = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cc.physicalCameraIds else emptySet()
            }.getOrDefault(emptySet())
            val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val priv = map?.getOutputSizes(android.graphics.ImageFormat.PRIVATE)
                ?.joinToString(",") { "${it.width}x${it.height}" }
            line(
                "PROBE id=$probe characteristics=OK facing=${facingName(facing)} level=$level orient=$orient" +
                    " focals=${focals.joinToString(",")} logical=" +
                    "${caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)}" +
                    " physicalIds=${physicals.sorted().joinToString(",")}"
            )
            line("PROBE id=$probe privateSizes=[$priv]")
        }
        // 能不能真打开：逐个 openCamera 等回调，只记录成/败与错误码，不建会话
        for (probe in listOf("0", "1", "2", "3", "4")) {
            line("OPEN id=$probe result=" + openCameraOnce(mgr, probe))
        }
    }

    /** 单颗相机开一次就关，返回 OPEN / 错误码名，用于判断隐藏 id 是否真可用 */
    private fun openCameraOnce(mgr: CameraManager, id: String): String {
        val latch = CountDownLatch(1)
        var outcome = "TIMEOUT"
        // 回调必须落在别的线程：仪器测试本身跑在主线程上，主线程一阻塞住就收不到 onOpened
        val thread = HandlerThread("WotaEnumProbe").apply { start() }
        val handler = Handler(thread.looper)
        runCatching {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    outcome = "OPEN ${device.id}"
                    runCatching { device.close() }
                    latch.countDown()
                }

                override fun onDisconnected(device: CameraDevice) {
                    outcome = "DISCONNECTED"
                    runCatching { device.close() }
                    latch.countDown()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    outcome = "ERROR $error"
                    runCatching { device.close() }
                    latch.countDown()
                }
            }, handler)
        }.onFailure { outcome = "THROW ${it.javaClass.simpleName}:${it.message}"; latch.countDown() }
        runCatching { latch.await(3L, TimeUnit.SECONDS) }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        return outcome
    }

    private fun facingName(facing: Int): String = when (facing) {
        CameraMetadata.LENS_FACING_FRONT -> "FRONT"
        CameraMetadata.LENS_FACING_BACK -> "BACK"
        CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($facing)"
    }
}
