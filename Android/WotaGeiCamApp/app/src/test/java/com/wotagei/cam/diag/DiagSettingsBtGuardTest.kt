package com.wotagei.cam.diag

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Y47 / Y48 / B09 / Y50 / Y62 的源码级契约（2026-11-19）。
 *
 * 这几张卡的行为都落在"设置页/蓝牙控制器有没有按约定写"上，纯函数表测够不到：
 * - Y47：断连回调必须同时重置代理申请位并重查状态（少一句就回到"永远显示已连接"）；
 * - Y48：connect 日志不许再拼设备地址；
 * - B09：组合期不许同步枚举目录，占用必须走 IO 线程并可按 tick 刷新；
 * - Y50：清除文案必须由 `res.state` 三态映射，不许无条件写"已清除"；
 * - Y62：授权入口文案必须披露系统层面的实际范围与不授权降级。
 *
 * 判据取**函数体**而非整文件（注释/别的函数里出现关键词不算数），并同时做 `assertFalse`
 * 反证，防止把关键词留在注释里就喂绿。
 */
class DiagSettingsBtGuardTest {

    private val btRaw = KotlinSourceScan.mainSourceText("bt/BtSpeakerController.kt")
    private val bt = KotlinSourceScan.codeOnly(btRaw)
    private val settings = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))

    @Test
    fun `Y47 断连必须清申请位并重查，refresh 是唯一重试入口`() {
        val disconnected = KotlinSourceScan.bodyOf(bt, "onServiceDisconnected")
        assertTrue("断连必须清 a2dp", disconnected.contains("a2dp = null"))
        assertTrue("断连必须清 proxyRequested，否则 requestProxy 永远短路", disconnected.contains("proxyRequested = false"))
        assertTrue("断连必须立刻重查并发布断开态", disconnected.contains("refresh()"))

        val refresh = KotlinSourceScan.bodyOf(bt, "refresh")
        assertTrue("refresh 必须在代理缺失时重新申请", refresh.contains("a2dp == null"))
        assertTrue("refresh 必须走 requestProxy 单入口", refresh.contains("requestProxy()"))
        assertTrue("重试前必须确认连接权限", refresh.contains("hasConnectPermission()"))
    }

    @Test
    fun `Y48 connect 日志不再携带设备地址`() {
        // Y48 锁的是日志正文，而 codeOnly 会把字符串字面量整段遮蔽（`speaker=` 也一起没了）。
        // 所以按 MediaSilentFailGuardTest 的既有写法：用遮蔽文本定位函数体区间，再从**原文**
        // 同偏移切出正文——掩码与原文逐位等长，偏移可直接复用。
        val region = KotlinSourceScan.regionOf(bt, "connect")
        val connect = btRaw.substring(region.start + 1, region.end - 1)
        assertFalse("日志里不许出现 device.address", connect.contains("device.address"))
        assertTrue("保留一条无身份的排查信息", connect.contains("speaker="))
    }

    @Test
    fun `B09 日志占用不在组合期同步枚举`() {
        val block = KotlinSourceScan.bodyOf(settings, "BlockDiag")
        assertFalse(
            "组合期直接 formatBytes(Diag.usedBytes()) 会做磁盘目录枚举",
            block.contains("formatBytes(Diag.usedBytes())")
        )
        assertTrue("占用要在 LaunchedEffect 里算", block.contains("LaunchedEffect("))
        assertTrue("必须切 IO 调度器", block.contains("Dispatchers.IO"))
        assertTrue("占用来源仍是 Diag.usedBytes()", block.contains("Diag.usedBytes()"))
        assertTrue("动作后要 bump 刷新 tick", block.contains("usageTick++"))
    }

    @Test
    fun `Y50 清除文案由实际结果三态驱动`() {
        val block = KotlinSourceScan.bodyOf(settings, "BlockDiag")
        assertTrue("必须读清除结果的状态", block.contains("res.state") || block.contains(".state"))
        assertTrue("部分成功文案要可达", block.contains("set_diag_clear_partial"))
        assertTrue("删除失败文案要可达", block.contains("set_diag_clear_failed"))
        assertTrue("整体异常文案要可达", block.contains("set_diag_clear_error"))
        assertFalse(
            "不许无条件写成功文案（旧缺陷原样）",
            block.contains("withContext(Dispatchers.IO) { Diag.clear() }\n                        busy = null\n                        status = context.getString(R.string.set_diag_cleared)")
        )
    }

    @Test
    fun `Y62 授权文案披露系统范围与不授权降级`() {
        val strings = readRepoFile("app/src/main/res/values/strings_settings.xml")
        val note = Regex("<string name=\"set_storage_note\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1)
            ?: throw AssertionError("找不到 set_storage_note 文案——守卫失去输入，不许算通过")
        assertTrue("必须说明系统层面授予的是全部文件", note.contains("全部文件"))
        assertTrue("必须说明本应用实际只用它做跨应用视频操作", note.contains("跨应用视频"))
        assertTrue("必须说明不授予时的降级（只影响其他应用视频）", note.contains("不授予时"))
        assertTrue("必须明确不会自动提升权限", note.contains("不会自动提升权限"))
    }

    private fun readRepoFile(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val tried = ArrayList<String>()
        var hops = 0
        while (dir != null && hops <= 5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val f = File(base, rel)
                tried.add(f.path)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError("找不到 $rel（试过：$tried）——守卫不能在没有输入时算通过")
    }
}
