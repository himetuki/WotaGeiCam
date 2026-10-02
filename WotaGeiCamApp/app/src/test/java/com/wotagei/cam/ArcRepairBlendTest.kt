package com.wotagei.cam

import com.wotagei.cam.record.ArcRepairBlend
import com.wotagei.cam.record.ArcRepairPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复**纯 CPU 路线的平面混合**的 JVM 测试。
 *
 * 只钉 `ArcRepairBlend.average` 这一条算式本身：真机侧的「读解码 Image → 混合 → 写编码器 Image」
 * 涉及 `android.media.Image` 与 stride 布局，JVM 里不存在，只能在设备上验（证据是成片的帧步长与时长）。
 * 这里能钉住的是**数值口径**：混合必须与 GPU 路线 `ARC_REPAIR_FS` 的 `mix(prev, cur, 0.5)` 同权重，
 * 且必须按无符号字节算——U/V 平面大量样本落在 0x80 以上，一旦被当成负数，色度会整片偏掉。
 *
 * 权重真源是 [ArcRepairPlan.blendWeight]，本文件有一条用例专门把两者对账：
 * 哪天有人把权重从 0.5 改成别的，那条会红，逼他同时改这里与 GPU 的 shader。
 */
class ArcRepairBlendTest {

    // region 基本算式

    @Test
    fun `两端点不改变原值`() {
        val a = byteArrayOf(0, 255.toByte(), 0, 255.toByte())
        val b = byteArrayOf(0, 255.toByte(), 255.toByte(), 0)
        val out = ByteArray(4)
        ArcRepairBlend.average(a, b, out, 4)
        // 0 与 0 平均必须还是 0；255 与 255 平均必须还是 255（不能被 +1 拱出界）
        assertEquals(0, out[0].toInt() and 0xFF)
        assertEquals(255, out[1].toInt() and 0xFF)
        // 0 与 255 平均 = 127.5 → 进位到 128（round half up）
        assertEquals(128, out[2].toInt() and 0xFF)
        assertEquals(128, out[3].toInt() and 0xFF)
    }

    @Test
    fun `和为零的一对数也到不了负数`() {
        // 这条专防"忘了 and 0xFF"：0x80 在有符号字节里是 −128，两个 −128 相加再 shr 1 仍是负数
        val a = ByteArray(3) { 0x80.toByte() }
        val b = ByteArray(3) { 0x80.toByte() }
        val out = ByteArray(3)
        ArcRepairBlend.average(a, b, out, 3)
        for (i in 0..2) assertEquals(0x80, out[i].toInt() and 0xFF)
    }

    @Test
    fun `高值域样本不被当成负数`() {
        // 0xC0 与 0xE0：正解 (192+224)/2 = 208 = 0xD0。若按有符号算会得到负数 −24 之类的垃圾
        val a = byteArrayOf(0xC0.toByte())
        val b = byteArrayOf(0xE0.toByte())
        val out = ByteArray(1)
        ArcRepairBlend.average(a, b, out, 1)
        assertEquals(0xD0, out[0].toInt() and 0xFF)
    }

    @Test
    fun `结果恒落在两个入参之间`() {
        // 对 0..255 的每一对抽样：平均值不许越出 [min, max]（含端点）
        val out = ByteArray(1)
        for (x in 0..255 step 17) {
            for (y in 0..255 step 17) {
                ArcRepairBlend.average(byteArrayOf(x.toByte()), byteArrayOf(y.toByte()), out, 1)
                val v = out[0].toInt() and 0xFF
                assertTrue("avg($x,$y)=$v 越界", v >= minOf(x, y) && v <= maxOf(x, y))
            }
        }
    }

    // endregion

    // region 边界与内存约定

    @Test
    fun `len 只作用于前缀_不越界不污染尾部`() {
        val a = byteArrayOf(10, 20, 30, 40)
        val b = byteArrayOf(10, 20, 30, 40)
        val out = ByteArray(4) { 0x7F }
        ArcRepairBlend.average(a, b, out, 2)
        assertEquals(10, out[0].toInt() and 0xFF)
        assertEquals(20, out[1].toInt() and 0xFF)
        // 后两格没被写：保留哨兵 0x7F（证明没有按 out.size 一路算到底）
        assertEquals(0x7F, out[2].toInt() and 0xFF)
        assertEquals(0x7F, out[3].toInt() and 0xFF)
    }

    @Test
    fun `len 为 0 或负数时一个字节都不动`() {
        val out = ByteArray(3) { 0x11 }
        ArcRepairBlend.average(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), out, 0)
        assertArrayEquals(ByteArray(3) { 0x11 }, out)
        ArcRepairBlend.average(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), out, -5)
        assertArrayEquals(ByteArray(3) { 0x11 }, out)
    }

    @Test
    fun `out 与 a 同数组时就地可用`() {
        // 真机不这么用（mid 是独立缓冲），但算式声称就地安全，就得钉住：
        // 每格只读本格写本格，若哪天改成跨格依赖，这条会红
        val a = byteArrayOf(0, 100.toByte(), 255.toByte())
        val b = byteArrayOf(200.toByte(), 100.toByte(), 0)
        val expect = ByteArray(3).also { ArcRepairBlend.average(a.copyOf(), b, it, 3) }
        ArcRepairBlend.average(a, b, a, 3)
        assertArrayEquals(expect, a)
    }

    @Test
    fun `out 与 b 同数组时就地可用`() {
        val a = byteArrayOf(0, 100.toByte(), 255.toByte())
        val b = byteArrayOf(200.toByte(), 100.toByte(), 0)
        val expect = ByteArray(3).also { ArcRepairBlend.average(a.copyOf(), b.copyOf(), it, 3) }
        ArcRepairBlend.average(a, b, b, 3)
        assertArrayEquals(expect, b)
    }

    // endregion

    // region 与计划层的对账（权重真源）

    @Test
    fun `权重与 ArcRepairPlan 声明的 0_5 一致`() {
        // 权重不是这里能随便定的常数：GPU 路线的 shader 用的是同一个 0.5。
        // 把 average 按权重 w 的通用式写一遍，用 blendWeight() 反推，两者必须落在同一档
        val w = ArcRepairPlan.blendWeight()
        assertEquals(0.5f, w, 0f)
        val out = ByteArray(1)
        for (x in 0..255 step 51) {
            for (y in 0..255 step 51) {
                ArcRepairBlend.average(byteArrayOf(x.toByte()), byteArrayOf(y.toByte()), out, 1)
                val got = out[0].toInt() and 0xFF
                val want = ((x * (1f - w) + y * w) + 0.5f).toInt().coerceIn(0, 255)
                assertEquals("avg($x,$y) 与 blendWeight=$w 的口径不符", want, got)
            }
        }
    }

    @Test
    fun `混合帧恒落在相邻两原帧之间_这是缺口重建的前提`() {
        // 光弧修复的物理前提：插入帧的每个样本都夹在前后两原帧之间，绝不外推出高光。
        // 用一条"亮斑向右扫"的合成序列走一遍，逐帧检查插值不越界
        val w = 16
        val frames = List(4) { t ->
            ByteArray(w) { x -> if (x == t * 4) 255.toByte() else 0 }
        }
        val out = ByteArray(w)
        for (i in 0 until frames.size - 1) {
            ArcRepairBlend.average(frames[i], frames[i + 1], out, w)
            for (x in 0 until w) {
                val v = out[x].toInt() and 0xFF
                val lo = minOf(frames[i][x].toInt() and 0xFF, frames[i + 1][x].toInt() and 0xFF)
                val hi = maxOf(frames[i][x].toInt() and 0xFF, frames[i + 1][x].toInt() and 0xFF)
                assertTrue("插值帧 $i 第 $x 个样本 $v 越出 [$lo,$hi]", v in lo..hi)
            }
        }
    }

    // endregion
}
