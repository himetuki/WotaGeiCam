package com.wotagei.cam

import com.wotagei.cam.camera.directDisplayMatrix
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.assignRearTiers
import com.wotagei.cam.core.pickDirectPreviewSize
import com.wotagei.cam.record.recordOrientationHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 本轮修复新增的纯函数单测（JVM，不需设备）：
 * DIRECT 显示矩阵、DIRECT 预览流选档、录制容器方向角、后摄档位分配。
 */
class DirectRulesTest {

    /** 行主序 3x3 作用于齐次坐标点 */
    private fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

    private fun assertNear(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertTrue("期望 $expected 实得 $actual", abs(expected.first - actual.first) < 1e-3f &&
            abs(expected.second - actual.second) < 1e-3f)
    }

    @Test
    fun `直显矩阵在零度旋转下等比居中并留信箱`() {
        val m = directDisplayMatrix(100, 100, 200, 100, 0, false)
        // 2:1 的画面铺进 1:1 视图 → 100x50 居中，上下各 25px 黑边
        assertNear(0f to 25f, apply(m, 0f, 0f))
        assertNear(100f to 75f, apply(m, 100f, 100f))
    }

    @Test
    fun `直显矩阵顺时针九十度时画面左上角落到内容框右上`() {
        val m = directDisplayMatrix(100, 100, 200, 100, 90, false)
        // 旋转后包围盒 100x200 → 等比 0.5 → 50x100 水平居中
        assertNear(75f to 0f, apply(m, 0f, 0f))
        assertNear(25f to 100f, apply(m, 100f, 100f))
    }

    @Test
    fun `直显矩阵镜像作用在屏幕横轴且与旋转可交换`() {
        val flat = directDisplayMatrix(100, 100, 100, 100, 0, true)
        assertNear(100f to 0f, apply(flat, 0f, 0f))
        assertNear(0f to 100f, apply(flat, 100f, 100f))
        // 180 度 + 镜像 = 只沿纵轴镜像
        val both = directDisplayMatrix(100, 100, 100, 100, 180, true)
        assertNear(0f to 100f, apply(both, 0f, 0f))
        assertNear(100f to 0f, apply(both, 100f, 100f))
    }

    @Test
    fun `直显矩阵在非整百旋转角下按包围盒算黑边`() {
        val m = directDisplayMatrix(100, 100, 200, 100, 45, false)
        val d = 0.7071f
        // 2:1 画面转 45° 的外接框 = (200+100)·sin45 见方，等比后正好铺满正方形视图
        val content = (200f + 100f) * d
        val scale = 100f / content
        val corners = listOf(apply(m, 0f, 0f), apply(m, 100f, 0f), apply(m, 0f, 100f), apply(m, 100f, 100f))
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        assertEquals(content * scale, xs.max() - xs.min(), 0.6f)
        assertEquals(content * scale, ys.max() - ys.min(), 0.6f)
        assertTrue("旋转后必须水平居中", abs((xs.max() + xs.min()) / 2f - 50f) < 0.6f)
        assertTrue("旋转后必须垂直居中", abs((ys.max() + ys.min()) / 2f - 50f) < 0.6f)
    }

    @Test
    fun `尺寸未就绪时直显矩阵退回单位阵`() {
        val m = directDisplayMatrix(0, 0, 1920, 1080, 90, false)
        assertEquals(1f, m[0], 1e-6f)
        assertEquals(0f, m[1], 1e-6f)
        assertEquals(0f, m[2], 1e-6f)
        assertEquals(1f, m[4], 1e-6f)
    }

    @Test
    fun `直显预览流只挑与录像同宽高比的档`() {
        val candidates = listOf(
            Size(4160, 3120), Size(3264, 2448), Size(2560, 1440), Size(1920, 1080),
            Size(1920, 864), Size(1280, 720), Size(1024, 768), Size(800, 600),
            Size(720, 720), Size(960, 540), Size(640, 360)
        )
        // 16:9 录像：先在 16:9 支里按视图面积取最小够用档，2560x1440 超过 1080p 上限要跳过
        assertEquals(Size(1280, 720), pickDirectPreviewSize(candidates, Size(1920, 1080), 1260, 660))
        // 视图很大时升到上限内最大档
        assertEquals(Size(1920, 1080), pickDirectPreviewSize(candidates, Size(1920, 1080), 3000, 1700))
        // 4:3 录像：只取 4:3 支（4160x3120/3264x2448 超上限），不能拿 16:9 档拉伸
        assertEquals(Size(1024, 768), pickDirectPreviewSize(candidates, Size(3264, 2448), 1200, 900))
        // 1:1 录像：同比例里最小够用
        assertEquals(Size(720, 720), pickDirectPreviewSize(candidates, Size(720, 720), 400, 400))
    }

    @Test
    fun `直显预览流选档不越界不硬编码`() {
        val candidates = listOf(Size(1920, 1080), Size(1280, 720))
        // 视图未布局（面积 0）时不能一路退到最小档
        assertEquals(Size(1280, 720), pickDirectPreviewSize(candidates, Size(1280, 720), 0, 0))
        // 能力表为空 → 交回上层兜底，不伪造尺寸
        assertNull(pickDirectPreviewSize(emptyList(), Size(1920, 1080), 100, 100))
        // 录像档本身小于上限时不得选到比录像更大的预览流
        assertEquals(
            Size(640, 360),
            pickDirectPreviewSize(listOf(Size(1920, 1080), Size(1280, 720), Size(640, 360)), Size(640, 360), 300, 200)
        )
        // 没有任何同比例档时退到全表最小够用档，而不是返回 null 让画面失控
        assertEquals(
            Size(640, 480),
            pickDirectPreviewSize(listOf(Size(1280, 720), Size(640, 480)), Size(1000, 1000), 200, 200)
        )
    }

    /**
     * 方向角语义在 2026-09-26 这次修复里改过一次，断言随之改写，理由记在这：
     * 旧实现按「GPU 路把画面在编码帧里画正 / DIRECT 路只能靠容器角」分成两套公式，
     * 但真机核对发现 GPU 的编码 pass 也在转正画面，于是同一姿态两条路给出的容器角不同、
     * 成片一个正一个歪 90°。现在两条路都往编码器送**缓冲原样帧**（GPU 的编码 pass 只抵消
     * `SurfaceTexture` 矩阵自带的旋转），转正完全交给容器角，所以四组合统一成
     * `sensorOrientation − 显示旋转`（= `SENSOR_ORIENTATION` 的官方转正定义）。
     */
    @Test
    fun `录制方向角两条渲染路径同一个转正角`() {
        // 真机横屏（sensor=90、显示=90）：容器不记角，成片容器不带 rotation
        assertEquals(0, recordOrientationHint(90, 90, front = false, direct = false))
        assertEquals(0, recordOrientationHint(90, 90, front = false, direct = true))
        // 反向横屏差 180
        assertEquals(180, recordOrientationHint(90, 270, front = false, direct = false))
        assertEquals(180, recordOrientationHint(90, 270, front = false, direct = true))
        // 竖屏持机：容器记 90，播放器据此出竖画幅
        assertEquals(90, recordOrientationHint(90, 0, front = false, direct = false))
        assertEquals(90, recordOrientationHint(90, 0, front = false, direct = true))
        // 前摄同样按「传感器方向 − 显示旋转」：镜像只是预览侧的屏幕翻转，文件里是未镜像原样帧
        assertEquals(180, recordOrientationHint(270, 90, front = true, direct = false))
        assertEquals(180, recordOrientationHint(270, 90, front = true, direct = true))
        // 负角与越界角归一到 0..359
        assertEquals(270, recordOrientationHint(-90, 0, front = false, direct = true))
        assertEquals(270, recordOrientationHint(450, 180, front = false, direct = false))
    }

    @Test
    fun `后摄档位按颗数分配且多出的并入超长焦`() {
        assertEquals(emptyList<LensType>(), assignRearTiers(0))
        assertEquals(listOf(LensType.WIDE), assignRearTiers(1))
        assertEquals(listOf(LensType.SUPER_WIDE, LensType.WIDE), assignRearTiers(2))
        assertEquals(listOf(LensType.SUPER_WIDE, LensType.WIDE, LensType.TELEPHOTO), assignRearTiers(3))
        assertEquals(
            listOf(LensType.SUPER_WIDE, LensType.WIDE, LensType.TELEPHOTO, LensType.SUPER_TELEPHOTO),
            assignRearTiers(4)
        )
        // 颗数多于四个基本档时也要每颗都有档位（尾部并入超长焦），不能丢镜头
        val six = assignRearTiers(6)
        assertEquals(6, six.size)
        assertEquals(LensType.SUPER_TELEPHOTO, six.last())
        assertEquals(LensType.SUPER_WIDE, six.first())
    }
}
