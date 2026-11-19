package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/**
 * Y32「救援优先依据明确封轨完成标记」的判定表：[MoovSeal.complete] 直接按 ISO-BMFF 顶层
 * box 走一遍，moov **在盘上且完整**才算封轨完成——缺尾段的半截文件（录制被杀/断电、
 * mdat 截断、moov 缺失）必须在这里被挡下，不许晋升进相册（缺尾段文件不晋升）。
 *
 * 全部用例是真字节表（JVM 无 Android 依赖），并配突变自证：把 extractable 的封轨闸摘掉，
 * 尺子必须红。
 */
class MoovSealTest {

    @get:org.junit.Rule
    val tmp = TemporaryFolder(File("build/tmp/moov-seal-test").apply { mkdirs() })

    // ---- 字节构造助手 ----

    /** 顶层 box 头：32 位 size + type（普通形态） */
    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteBuffer.allocate(size)
        out.putInt(size).put(type.toByteArray(Charsets.US_ASCII)).put(payload)
        return out.array()
    }

    /** 64 位 largesize 形态：size 字段=1，后接 8 字节真实 size（含 16 字节头） */
    private fun largeBox(type: String, payload: ByteArray): ByteArray {
        val size = 16 + payload.size
        val out = ByteBuffer.allocate(size)
        out.putInt(1).put(type.toByteArray(Charsets.US_ASCII)).putLong(size.toLong()).put(payload)
        return out.array()
    }

    /** size==0（开到文件尾）形态：size 字段=0，payload 到 EOF */
    private fun openBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + payload.size)
        out.putInt(0).put(type.toByteArray(Charsets.US_ASCII)).put(payload)
        return out.array()
    }

    /** mdat 载荷随便给点数据（判定只看顶层结构，不看内容） */
    private fun mdat(bytes: Int) = box("mdat", ByteArray(bytes))

    private fun file(vararg chunks: ByteArray): File {
        val f = tmp.newFile()
        f.outputStream().use { out -> chunks.forEach { out.write(it) } }
        return f
    }

    // ---- 判定表 ----

    @Test
    fun `完整moov在盘上_判封轨完成`() {
        val f = file(box("ftyp", ByteArray(8)), mdat(64), box("moov", ByteArray(40)))
        assertTrue("moov 存在且完整 ⇒ 封轨完成", MoovSeal.complete(f))
    }

    @Test
    fun `moov在mdat之前也判完成`() {
        // 生成器不保证 moov 在尾；顶层顺序无关
        val f = file(box("ftyp", ByteArray(8)), box("moov", ByteArray(40)), mdat(64))
        assertTrue(MoovSeal.complete(f))
    }

    @Test
    fun `前置moov完整但后续mdat截断_不晋升`() {
        val data = mdat(64)
        val f = file(box("ftyp", ByteArray(8)), box("moov", ByteArray(40)), data.copyOf(data.size - 8))
        assertFalse("前置 moov 通过后仍须检查所有后续 box", MoovSeal.complete(f))
    }

    @Test
    fun `缺moov的半截文件_不晋升`() {
        // 录制被杀的常态：ftyp + mdat（哪怕 mdat 完整）没有 moov ⇒ 绝不能清 IS_PENDING
        val f = file(box("ftyp", ByteArray(8)), mdat(64))
        assertFalse("无 moov = 未封轨完成", MoovSeal.complete(f))
    }

    @Test
    fun `moov头声明超出文件尾_半截不晋升`() {
        // moov 头已写、内容被截断：size 指向文件外 ⇒ 不完整
        val header = ByteBuffer.allocate(8).putInt(8 + 64).put("moov".toByteArray(Charsets.US_ASCII)).array()
        val f = file(box("ftyp", ByteArray(8)), header, ByteArray(20))
        assertFalse("moov 声明 72 字节但盘上只有 28 ⇒ 缺尾", MoovSeal.complete(f))
    }

    @Test
    fun `中途box越界_后面的真moov不可达_不晋升`() {
        // mdat 头声明 1000 字节但只写了 30：文件在该 box 就烂了，即便后面碰巧有 moov 字节
        val brokenMdat = ByteBuffer.allocate(8).putInt(1000).put("mdat".toByteArray(Charsets.US_ASCII)).array()
        val f = file(box("ftyp", ByteArray(8)), brokenMdat, box("moov", ByteArray(40)))
        assertFalse("顶层 box 越界 = 容器结构损坏", MoovSeal.complete(f))
    }

    @Test
    fun `largesize64位moov_判完成`() {
        // size==1 ⇒ 走 64 位 largesize（大文件常态）
        val f = file(box("ftyp", ByteArray(8)), largeBox("moov", ByteArray(40)), mdat(64))
        assertTrue(MoovSeal.complete(f))
    }

    @Test
    fun `largesize声明越界_不晋升`() {
        val bogus = ByteBuffer.allocate(16).putInt(1).put("moov".toByteArray(Charsets.US_ASCII))
            .putLong(Long.MAX_VALUE).array()
        val f = file(box("ftyp", ByteArray(8)), bogus)
        assertFalse(MoovSeal.complete(f))
    }

    @Test
    fun `size0开到文件尾的moov_判完成`() {
        // MediaMuxer/生成器收尾写 moov 时偶见的 open-ended box：moov 头在盘上且延到 EOF
        val f = file(box("ftyp", ByteArray(8)), mdat(64), openBox("moov", ByteArray(40)))
        assertTrue("size==0 且 type=moov ⇒ 完整", MoovSeal.complete(f))
    }

    @Test
    fun `size0的非moov盒后无内容_不晋升`() {
        val f = file(box("ftyp", ByteArray(8)), openBox("mdat", ByteArray(40)))
        assertFalse("开到文件尾的是 mdat ⇒ moov 没写", MoovSeal.complete(f))
    }

    @Test
    fun `损坏与空文件_一律不晋升`() {
        assertFalse("空文件", MoovSeal.complete(file(ByteArray(0))))
        assertFalse("过短（<16B）", MoovSeal.complete(file(ByteArray(12))))
        assertFalse("纯垃圾", MoovSeal.complete(file("garbage not an mp4 at all.......".toByteArray())))
        assertFalse("size<8 的损坏头", MoovSeal.complete(file(ByteBuffer.allocate(8).putInt(4).put("moov".toByteArray(Charsets.US_ASCII)).array())))
        assertFalse("不存在的文件", MoovSeal.complete(File(tmp.root, "no-such-${System.nanoTime()}.mp4")))
    }

    // ---- 接线守卫：救援判据必须先过封轨闸 ----

    /** 救援判据切片：extractable 体内、MediaExtractor 构造之前 */
    private fun extractableHead(raw: String): String {
        val code = KotlinSourceScan.codeOnly(raw)
        val at = code.indexOf("private fun extractable(")
        assertTrue("守卫失去输入：extractable 不在了", at >= 0)
        val exAt = code.indexOf("MediaExtractor()", at)
        assertTrue("守卫失去输入：extractable 里没有 MediaExtractor 构造", exAt > at)
        return code.substring(at, exAt)
    }

    @Test
    fun `救援判据必须先过MoovSeal封轨闸再开extractor`() {
        val raw = KotlinSourceScan.mainSourceText("record/StalePendingSweep.kt")
        val head = extractableHead(raw)
        assertTrue(
            "Y32：救援判据必须在创建 MediaExtractor 之前先 MoovSeal.complete(f)（缺尾段文件不晋升）",
            head.contains("MoovSeal.complete(f)")
        )
        // 突变自证：把封轨闸摘掉（直接开 extractor）尺子必须红
        val mutant = raw.replace(
            Regex.fromLiteral("if (!MoovSeal.complete(f)) return false"),
            ""
        )
        assertFalse("摘掉封轨闸后守卫必须报红", extractableHead(mutant).contains("MoovSeal.complete(f)"))
        assertTrue(
            "突变体必须真的摘了闸（替换不咬合 = 尺子没失效也没生效）",
            !extractableHead(mutant).contains("MoovSeal.complete(")
        )
    }
}
