package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成片命名的**纯拼接**与生产侧接线（B04）。
 *
 * 缺陷形态：老写法 `Random.nextInt(1000)` 当 3 位序号——同秒内 1000 个值随手抽，
 * 换段/自动重录挤在同一秒就有 1/1000 撞名概率。撞了 provider 会自行给真实落盘名加
 * `" (1)"`，而日志/拼装名仍停在请求名，两者分叉（sidecar 按成片名对号时会找错）。
 *
 * 修法两层：进程内**单调**序号把同秒碰撞压到 0；序号回绕/跨进程重启的残余碰撞由
 * provider 回查 + 换号重试兜底，插入后再回查**最终名**留日志。这里钉纯函数（格式、
 * 回绕、负序号）与四处接线（displayName 单调进位、createPending 回查重试/终名回查、
 * 选卷常量、空间探测落点），防止哪天"回退成随机"或"只改一处"。
 */
class VideoStoreNameTest {

    // ---------------------------------------------------------------- 纯拼接

    @Test
    fun `基本格式_空前缀_三位序号`() {
        assertEquals(
            "VID_20261119_120000_000.mp4",
            VideoStore.fileName("", "20261119_120000", 0)
        )
        assertEquals(
            "VID_20261119_120000_007.mp4",
            VideoStore.fileName("", "20261119_120000", 7)
        )
    }

    @Test
    fun `前缀置于时间戳之前`() {
        // 抽帧/编辑过的成片名（用户 2026-10-04 定版）：前缀可辨且不改时间戳段
        assertEquals(
            "30fto24f_VID_20261119_120000_042.mp4",
            VideoStore.fileName("30fto24f_", "20261119_120000", 42)
        )
    }

    @Test
    fun `序号满1000回绕_且1000个值互异`() {
        assertEquals("1000 回绕到 000", "VID_s_000.mp4", VideoStore.fileName("", "s", 1000))
        assertEquals("1999 回绕到 999", "VID_s_999.mp4", VideoStore.fileName("", "s", 1999))
        val names = (0 until 1000).map { VideoStore.fileName("", "s", it) }
        assertEquals("同秒 1000 个序号必须给出 1000 个不同文件名", 1000, names.toSet().size)
    }

    @Test
    fun `负序号也归到合法三位数`() {
        // 时钟/状态异常时不许拼出 `VID_s_-01.mp4` 或 `%3d` 的负号
        assertEquals("VID_s_999.mp4", VideoStore.fileName("", "s", -1))
        assertEquals("VID_s_000.mp4", VideoStore.fileName("", "s", -1000))
    }

    // ---------------------------------------------------------------- 接线守卫

    @Test
    fun `displayName必须用单调进位而不是随机`() {
        val src = codeOnly(mainSourceText("record/VideoStore.kt"))
        assertFalse(
            "VideoStore 不允许再用 kotlin.random.Random（同秒撞名的根因）",
            src.contains("kotlin.random.Random")
        )
        assertFalse("文件内不许残留 Random 调用", src.contains("Random."))
        assertTrue(
            "序号必须来自进程内单调进位器",
            bodyOf(src, "displayName").contains("nameSeq.getAndIncrement()")
        )
    }

    @Test
    fun `createPending撞名要换号重试并以provider终名为准`() {
        val body = bodyOf(codeOnly(mainSourceText("record/VideoStore.kt")), "createPending")
        assertTrue("请求名仍必须由 displayName 生成", body.contains("displayName(namePrefix = namePrefix)"))
        assertTrue("插入前必须回查同名条目", body.contains("videoNameTaken(name)"))
        assertTrue("重试有上限（不许无限循环）", body.contains("tries < NAME_RETRIES"))
        assertTrue(
            "插入后必须回查 provider 的最终 DISPLAY_NAME（撞名时 provider 会自行改名）",
            body.contains("queryStr(uri, MediaStore.MediaColumns.DISPLAY_NAME)")
        )
    }

    @Test
    fun `成片与sidecar选卷必须是同一枚外部主卷常量`() {
        // createPending / writeSidecar 都是 `= try {…}` 表达式体，bodyOf 只认花括号体；
        // 这里按"两处调用同在一份文件、且各出现一次"判，函数名仍由祖先守卫钉着。
        val src = codeOnly(mainSourceText("record/VideoStore.kt"))
        assertTrue(
            "成片插入必须显式落外部主卷",
            src.contains("MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)")
        )
        assertTrue(
            "sidecar 走 Files 集合时也必须落同一枚卷（否则 readFrom 按成片路径找不到）",
            src.contains("MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)")
        )
    }

    @Test
    fun `空间探测必须量实际成片目录所在的卷`() {
        val src = codeOnly(mainSourceText("record/VideoStore.kt"))
        assertTrue(
            "freeSpaceMb（表达式体）必须量 freeSpaceProbe 定位到的卷",
            src.contains("StatFs(freeSpaceProbe().absolutePath)")
        )
        val probe = bodyOf(src, "freeSpaceProbe")
        assertTrue("探测从成片目录出发", probe.contains("outputDir()"))
        assertTrue(
            "应用专属目录只作兜底（真写入口径是公共成片目录）",
            probe.indexOf("outputDir()") < probe.indexOf("getExternalFilesDir")
        )
        // outputDir 是表达式体，bodyOf 不适用于它：直接钉整行拼接口径
        assertTrue(
            "公共成片目录必须由外部存储根 + RELATIVE_PATH 拼出",
            src.contains("File(Environment.getExternalStorageDirectory(), RELATIVE_PATH)")
        )
    }
}