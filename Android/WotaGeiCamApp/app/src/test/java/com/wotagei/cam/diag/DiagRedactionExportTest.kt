package com.wotagei.cam.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Y48「日志脱敏」与 Y49「导出硬字节上限」的可执行表测（2026-11-19）。
 *
 * 为什么必须能跑：这两张卡治的都是"出事后才发现"的问题——
 * - 脱敏漏一处，MAC/文档 id/账户目录就跟着用户导出的 txt 一起发出去；
 * - 上限按字符算，中文一行 3 字节，标称 2MiB 实际能写到 6MiB，分享面板/邮件直接拒收。
 * 所以判据全部落在**纯函数与真实临时文件**上，JVM 单测即可复现，不依赖真机。
 */
class DiagRedactionExportTest {

    // ---------------------------------------------------------------- Y48 脱敏

    @Test
    fun `MAC 两种分隔符都抹掉，正常文本不动`() {
        val out = diagRedactText("连接 AA:BB:CC:DD:EE:FF 与 aa-bb-cc-dd-ee-ff 失败")
        assertFalse("冒号 MAC 不许原样留下", out.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse("连字符 MAC 不许原样留下", out.contains("aa-bb-cc-dd-ee-ff"))
        assertEquals("两个 MAC 都要有占位", 2, Regex("<MAC>").findAll(out).count())
        assertTrue("事件语义要保留", out.contains("连接") && out.contains("失败"))

        val plain = "换段腿超预算 3000ms，帧=60"
        assertEquals("没有敏感信息时一个字都不改", plain, diagRedactText(plain))
    }

    @Test
    fun `content URI 只留 authority，文档 id 与文件名抹掉`() {
        val out = diagRedactText("分享 content://com.wotagei.cam.provider/share/share-out/wotagei-log-1.txt 失败")
        assertTrue("authority 要留下（才能判断是哪个 provider）", out.contains("content://com.wotagei.cam.provider/"))
        assertFalse("文档 id/文件名不许留下", out.contains("share-out"))
        assertFalse("文件名不许留下", out.contains("wotagei-log-1.txt"))
        assertTrue("末尾中文标点不能被吞掉", out.endsWith("失败"))
    }

    /** MediaStore / 系统选片器回程 URI 同样只留 authority，数字 id 不随导出件外发 */
    @Test
    fun `共享媒体 content URI 只留 authority`() {
        val out = diagRedactText(
            "相册 content://media/external/video/media/12345；" +
                "选择器 content://media/picker/0/com.android.providers.media.photopicker/media/42；" +
                "文档 content://com.android.providers.media.documents/document/image%3A99"
        )
        assertFalse("MediaStore 数字 id 不许留下", out.contains("12345"))
        assertFalse("选片器命名空间不许留下", out.contains("photopicker/media/42"))
        assertFalse("文档 id 不许留下", out.contains("image%3A99"))
        assertEquals("三段 URI 都只留 authority", 3, Regex("content://media/…|content://com\\.android\\.providers\\.media\\.documents/…").findAll(out).count())
        assertTrue("authority 要保留（判断来源）", out.contains("content://media/…"))
    }

    @Test
    fun `应用私有与外部应用路径整段替换，Windows 开发机路径也不留`() {
        val out = diagRedactText(
            "私有 /data/user/0/com.wotagei.cam/files/a.jpg；" +
                "外部 /storage/emulated/0/Android/data/com.wotagei.cam/files/b.mp4；" +
                "开发机 C:\\Users\\alice\\project\\app.log"
        )
        assertFalse("/data/user/0 路径不许留下", out.contains("/data/user/0"))
        assertFalse("外部应用目录不许留下", out.contains("/storage/emulated/0/Android/data"))
        assertFalse("Windows 用户目录不许留下", out.contains("Users\\alice"))
        assertTrue("私有路径占位", out.contains("<应用私有路径>"))
        assertTrue("外部路径占位", out.contains("<应用外部路径>"))
        assertTrue("本机路径占位", out.contains("<本机路径>"))
        assertTrue("非路径文本保留", out.contains("私有") && out.contains("外部") && out.contains("开发机"))
    }

    /** 脱敏必须幂等：导出/崩溃两条路会各兜一次，二次处理不许把占位符再改坏 */
    @Test
    fun `脱敏幂等`() {
        val once = diagRedactText("MAC AA:BB:CC:DD:EE:FF uri content://com.wotagei.cam.provider/a/b")
        assertEquals(once, diagRedactText(once))
    }

    // ---------------------------------------------------------------- Y49 字节预算

    @Test
    fun `UTF-8 字节数按实际编码算，不按字符数`() {
        assertEquals("ASCII 一字符一字节", 3, diagUtf8ByteCount("abc"))
        assertEquals("中文一字符三字节", 6, diagUtf8ByteCount("日志"))
        assertEquals("emoji 四字节", 4, diagUtf8ByteCount("\uD83D\uDCF7"))
    }

    @Test
    fun `按字节前缀不劈开代理对`() {
        val emoji = "\uD83D\uDCF7"
        assertEquals("放不下 4 字节就一个都不要", "", diagUtf8Prefix(emoji, 3))
        assertEquals("刚好 4 字节", emoji, diagUtf8Prefix(emoji, 4))
        assertEquals("中文预算 4 字节只能放一个", "日", diagUtf8Prefix("日志", 4))
    }

    @Test
    fun `正文构造器超限时只留能放下的字节且返回 false`() {
        val builder = DiagReportBuilder(10)
        assertFalse("10 个中文 = 30 字节，必须报没装下", builder.append("一二三四五六七八九十"))
        val out = builder.toString()
        assertTrue("总量不许超过 10 字节", diagUtf8ByteCount(out) <= 10)
        assertEquals("按字节只能留三个中文（9 字节）", "一二三", out)
        assertTrue("剩余预算归零", builder.remaining == 1)
    }

    /**
     * 核心回归：全中文正文也必须硬性 ≤ 2MiB。
     * 旧实现把 `String.length` 当预算 ⇒ 2MiB 字符的中文实际写成 6MiB。
     */
    @Test
    fun `全中文报告总量硬性不超过导出上限`() {
        val limit = Diag.DIAG_EXPORT_MAX_BYTES.toInt()
        // 约 2.1MB 字符（6.3MB UTF-8），足以顶穿 2MiB 上限
        val body = "诊断日志行：相机被系统拒绝，正在回退。".repeat(120_000)
        val out = diagAssembleReport(body, "=== 结束 ===\n", limit)
        val bytes = diagUtf8ByteCount(out)
        assertTrue("实际字节 $bytes 超过硬上限 $limit", bytes <= limit)
        assertTrue("超限时仍要留下正文（不是空文件）", bytes > limit - 4096)
    }

    /** 多段（轮转件）共用同一份预算：任何一段都不能绕开总上限 */
    @Test
    fun `轮转多段拼接仍受同一硬上限约束`() {
        val limit = 64 * 1024
        val builder = DiagReportBuilder(limit)
        repeat(8) { i ->
            builder.append("\n=== 日志件 wotagei.log.$i ===\n")
            builder.append("中文现场".repeat(5_000))
        }
        val bytes = diagUtf8ByteCount(builder.toString())
        assertTrue("8 段各自都不大，但合计必须被总预算截住：$bytes > $limit", bytes <= limit)
    }

    @Test
    fun `结尾预留足够时结束标记一定完整出现`() {
        val limit = 2048
        val body = "中文".repeat(300) // 1800 字节
        val footer = "=== 结束 行数=1 字节=1800 ===\n"
        val out = diagAssembleReport(body, footer, limit)
        assertTrue("正文已按上限-预留构造，结尾必须完整", out.endsWith(footer))
        assertTrue(diagUtf8ByteCount(out) <= limit)
    }

    // ---------------------------------------------------------------- 大文件尾部读取

    @Test
    fun `尾部读取按字节且不把大文件全量读进内存`() {
        val dir = Files.createTempDirectory("wota-diag-tail").toFile()
        val f = File(dir, "big.log").apply { writeText("0123456789") }
        assertEquals("取尾部 3 字节", "789", String(diagReadTailBytes(f, 3), Charsets.UTF_8))
        assertEquals("预算大于文件就全给", "0123456789", String(diagReadTailBytes(f, 100), Charsets.UTF_8))
        assertEquals("零预算给空", 0, diagReadTailBytes(f, 0).size)
        assertEquals("不存在的文件给空", 0, diagReadTailBytes(File(dir, "nope"), 10).size)
    }

    // ---------------------------------------------------------------- 轮转

    @Test
    fun `轮转保留 current-1-2 三件且内容按新旧移位`() {
        val dir = Files.createTempDirectory("wota-diag-rotate").toFile()
        File(dir, "wotagei.log").writeText("new")
        File(dir, "wotagei.log.1").writeText("mid")
        File(dir, "wotagei.log.2").writeText("old")
        assertTrue("三件都可改名", diagRotateFiles(dir, "wotagei.log", keep = 3))
        assertEquals("当前件让位后应不存在", false, File(dir, "wotagei.log").exists())
        assertEquals("最新内容进 .1", "new", File(dir, "wotagei.log.1").readText())
        assertEquals("次新内容进 .2", "mid", File(dir, "wotagei.log.2").readText())
        assertEquals("最老一件被删", false, File(dir, "wotagei.log.3").exists())
    }

    @Test
    fun `轮转失败留痕带当前字节数与保留上限`() {
        val line = diagRotateFailLine("11-19 10:00:00.000", 600_000L, 3)
        assertTrue(line.contains("600000"))
        assertTrue(line.contains("3"))
        assertTrue("要说明后果", line.contains("继续增大"))
        assertTrue(line.endsWith("\n"))
    }

    // ---------------------------------------------------------------- 清除结果

    @Test
    fun `清除结果三态映射诚实`() {
        assertEquals(Diag.DiagClearState.CLEARED, diagClearStatus(removed = 3, failed = 0))
        assertEquals("本来就没有日志也算清除成功", Diag.DiagClearState.CLEARED, diagClearStatus(removed = 0, failed = 0))
        assertEquals(Diag.DiagClearState.PARTIAL, diagClearStatus(removed = 2, failed = 1))
        assertEquals("一件都没删掉不算部分成功", Diag.DiagClearState.FAILED, diagClearStatus(removed = 0, failed = 2))
        assertEquals("整个动作抛异常", Diag.DiagClearState.FAILED, diagClearStatus(removed = 0, failed = 0, hardError = true))
    }

    @Test
    fun `清除判据把轮转失败留痕也算本系统文件`() {
        val dir = Files.createTempDirectory("wota-diag-clear2").toFile()
        File(dir, "wotagei.log").writeText("x")
        File(dir, "diag-rotate-fail.log").writeText("y")
        File(dir, "keep.txt").writeText("z")
        val res = diagClearLogFilesDetailed(dir, "wotagei.log", "crash-", Diag.DIAG_ROTATE_FAIL_NAME)
        assertEquals("两件日志都要删", 2, res.removed)
        assertEquals("没有失败件", 0, res.failed)
        assertTrue("无关文件不动", File(dir, "keep.txt").isFile)
    }

    // ------------------------------------------------- Y48 共享媒体路径（边界复核）

    /**
     * 合成夹具：用户相册/公共导出目录的绝对路径。
     * 旧脱敏只覆盖 `Android/data|media`，`Movies/WotaGeiCam`、`DCIM`、`Pictures`、SD 卡卷全漏。
     */
    @Test
    fun `共享媒体绝对路径整段脱敏，事件语义保留`() {
        val out = diagRedactText(
            "导出 /storage/emulated/0/Movies/WotaGeiCam/Clip/IMG_001.mp4 完成；" +
                "相册 /storage/emulated/0/DCIM/Camera/VID_2026.mp4；" +
                "sdcard /sdcard/Pictures/family.jpg；" +
                "SD卡 /storage/1234-5678/DCIM/private.jpg；" +
                "分享 file:///storage/emulated/0/Pictures/c.png；" +
                "内部 /data/media/0/Movies/d.mp4"
        )
        assertFalse("公共 Movies 路径不许留下", out.contains("WotaGeiCam/Clip"))
        assertFalse("相册文件名不许留下", out.contains("VID_2026"))
        assertFalse("/sdcard 路径不许留下", out.contains("/sdcard/"))
        assertFalse("SD 卡卷路径不许留下", out.contains("1234-5678"))
        assertFalse("file URI 里的路径不许留下", out.contains("/storage/emulated"))
        assertFalse("/data/media 路径不许留下", out.contains("/data/media/"))
        assertEquals("六段路径都要有占位", 6, Regex("<共享媒体路径>").findAll(out).count())
        assertTrue("file scheme 前缀保留", out.contains("file://<共享媒体路径>"))
        assertTrue("事件语义要保留", out.contains("导出") && out.contains("完成"))
    }

    /** 日志里大量是 `key=/path` 这种无空格连接；旧的 `(?<!\S)` 会把整段漏掉 */
    @Test
    fun `等号与冒号后的应用路径也要脱敏`() {
        val out = diagRedactText(
            "dataDir=/data/user/0/com.wotagei.cam/files/a.jpg " +
                "uri:/storage/emulated/0/Android/data/com.wotagei.cam/files/b.mp4"
        )
        assertFalse("等号后的私有路径不许留下", out.contains("/data/user/0"))
        assertFalse("冒号后的外部应用路径不许留下", out.contains("/storage/emulated/0/Android/data"))
        assertTrue(out.contains("<应用私有路径>"))
        assertTrue(out.contains("<应用外部路径>"))
    }

    /** 导出端再兜一次：磁盘上可能还有旧版本未脱敏的日志件 */
    @Test
    fun `导出读取旧日志件时统一脱敏`() {
        val dir = Files.createTempDirectory("wota-diag-oldlog").toFile()
        val f = File(dir, "wotagei.log.1").apply {
            writeText("旧日志 MAC AA:BB:CC:DD:EE:FF path=/storage/emulated/0/DCIM/a.jpg\n")
        }
        val text = diagReadRedactedTail(f, 4096)
        assertFalse("旧件里的 MAC 不许导出", text.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse("旧件里的共享媒体路径不许导出", text.contains("/storage/emulated"))
        assertTrue("占位要出现", text.contains("<MAC>") && text.contains("<共享媒体路径>"))
        assertTrue("语义保留", text.contains("旧日志"))
    }

    // ------------------------------------------------- Y49 物理 512KiB 上限（边界复核）

    /**
     * 核心回归：一批日志跨过文件上限时必须切段轮转，而不是"写完才发现超了"。
     * 旧实现按 append 前的长度判断，499KiB + 整批中文可写出 1MB+。
     */
    @Test
    fun `整批跨上限时按字节切段且每个日志件不超上限`() {
        val cap = 16 * 1024
        val dir = Files.createTempDirectory("wota-diag-batch").toFile()
        val lines = (0 until 12).map { i -> "第${i}行 " + "中文".repeat(500) + "\n" }
        val plan = diagPlanFileAppend(currentBytes = 0L, lines = lines, maxBytes = cap.toLong())
        assertTrue("必须切出多段（单段装不下整批）", plan.segments.size >= 3)
        plan.segments.forEach { assertTrue("每段都必须 ≤ 上限：${it.bytes.size}", it.bytes.size <= cap) }
        assertEquals("一条都不该在规划期丢掉", 12, plan.linesAppended)

        val outcome = diagExecuteAppendPlan(dir, "wotagei.log", keep = 3, plan = plan, maxBytes = cap.toLong())
        assertEquals("全部写入", 12, outcome.linesAppended)
        assertEquals("不该丢行", 0, outcome.linesDropped)
        assertFalse("轮转应成功", outcome.rotationFailed)

        for (i in 0 until 3) {
            val f = if (i == 0) File(dir, "wotagei.log") else File(dir, diagRotatedName("wotagei.log", i))
            assertTrue("件 $i 应存在", f.isFile)
            assertTrue("件 $i 超上限：${f.length()}", f.length() <= cap)
        }
        assertTrue("最新一行应在当前件", File(dir, "wotagei.log").readText().contains("第11行"))
    }

    /** 单条巨大堆栈/异常原文不能靠"下一批再轮转"，必须在本条内截断并留痕 */
    @Test
    fun `单条超长记录截断到上限且标记可读`() {
        val cap = 8 * 1024
        val line = "头部" + "中".repeat(10_000) + "尾部\n"
        val plan = diagPlanFileAppend(currentBytes = 0L, lines = listOf(line), maxBytes = cap.toLong())
        assertEquals("超长记录仍算一条", 1, plan.linesAppended)
        assertEquals("一条记录只产生一段", 1, plan.segments.size)
        val bytes = plan.segments[0].bytes
        assertTrue("截断后仍超上限：${bytes.size}", bytes.size <= cap)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("保留开头语义", text.startsWith("头部"))
        assertTrue("截断要留痕", text.contains("已截断"))
        assertFalse("截断后不许还带着尾部", text.contains("尾部"))
        assertFalse("不许把 UTF-8 字符劈成替换符", text.contains('\uFFFD'))

        val dir = Files.createTempDirectory("wota-diag-oversized").toFile()
        val outcome = diagExecuteAppendPlan(dir, "wotagei.log", keep = 3, plan = plan, maxBytes = cap.toLong())
        assertEquals(1, outcome.linesAppended)
        assertTrue(File(dir, "wotagei.log").length() <= cap)
    }

    /** 旧版本已经写超的当前件：先按尾部裁回上限，保留最近现场 */
    @Test
    fun `已超上限的当前件先裁尾部再继续写`() {
        val cap = 4 * 1024
        val dir = Files.createTempDirectory("wota-diag-trim").toFile()
        val f = File(dir, "wotagei.log").apply {
            writeText("旧头部\n" + "a".repeat(cap + 4096))
        }
        assertTrue("夹具必须真的超限", f.length() > cap)
        assertTrue(diagTrimFileToTail(f, cap))
        assertTrue("裁剪后不超上限：${f.length()}", f.length() <= cap)
        assertTrue("保留的是尾部", f.readText().endsWith("a"))
    }

    /**
     * 轮转失败（系统拒绝改名/删除）时：宁可不写新行，也不越过物理上限。
     * 用"非空目录占住 .1"制造确定性失败，不依赖平台文件锁。
     */
    @Test
    fun `轮转失败时不越过上限且丢弃计数诚实`() {
        val cap = 4 * 1024
        val dir = Files.createTempDirectory("wota-diag-rotate-fail").toFile()
        val current = File(dir, "wotagei.log").apply { writeText("x".repeat(3000)) }
        val blocker = File(dir, "wotagei.log.1").apply { mkdirs() }
        File(blocker, "hold").writeText("hold")

        val plan = diagPlanFileAppend(
            currentBytes = current.length(),
            lines = listOf("y".repeat(2000)),
            maxBytes = cap.toLong()
        )
        val outcome = diagExecuteAppendPlan(dir, "wotagei.log", keep = 3, plan = plan, maxBytes = cap.toLong())
        assertTrue("轮转失败必须被报告", outcome.rotationFailed)
        assertEquals("放不下就不写", 0, outcome.linesAppended)
        assertEquals("丢弃要计数", 1, outcome.linesDropped)
        assertTrue("已有日志保留", current.readText().startsWith("xxx"))
        assertTrue("物理上限不许破：${current.length()}", current.length() <= cap)
        assertTrue("导出件要自曝磁盘侧丢弃", diagFileCapDroppedNote(outcome.linesDropped.toLong()).contains("1 行未落盘"))
        assertEquals("没丢弃就不占行", "", diagFileCapDroppedNote(0))
    }
}
