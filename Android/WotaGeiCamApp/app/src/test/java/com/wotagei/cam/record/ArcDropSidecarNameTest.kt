package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sidecar **规范名**（`.drops.json`）的单一出口（B04）。
 *
 * 缺陷形态：清扫侧按"成片全名 + `.drops.json`"拼归位目标
 * （`VID_x.mp4.drops.json`），而 [ArcDropLog.sidecarFor]/[ArcDropLog.readFrom]
 * 寻址的是"去掉最后一段扩展名"换后缀（`VID_x.drops.json`）——同一份档案两个名字，
 * App 按成片路径永远查不到清扫归位后的那份。修法不是让读取侧再兼容一种拼法，
 * 而是把**命名规则收到 [ArcDropLog.sidecarName] 一枚函数**里，清扫、CameraScreen 的
 * MediaStore 兜底、File 通道/读取全调它。
 *
 * 这里钉三件事：
 * 1. 规则本身（去最后一段扩展名、幂等、无扩展名、点开头、带点目录/反斜杠路径）；
 * 2. 清扫归位目标与规范名**逐字同源**（引用同一函数的结果，不是各拼一套碰巧相等）；
 * 3. 生产侧不许绕开该出口自己拼（源码守卫）。
 */
class ArcDropSidecarNameTest {

    // ---------------------------------------------------------------- 规则本身

    @Test
    fun `成片名只去掉最后一段扩展名`() {
        assertEquals(
            "VID_20261008_172951_262.mp4 → VID_20261008_172951_262.drops.json",
            "VID_20261008_172951_262.drops.json",
            ArcDropLog.sidecarName("VID_20261008_172951_262.mp4")
        )
    }

    @Test
    fun `前缀里的点和下划线都原样保留`() {
        // 抽帧/编辑前缀（"30fto24f_"）与时间戳里的下划线都不是扩展名分割点
        assertEquals(
            "30fto24f_VID_20261007_215240_909.drops.json",
            ArcDropLog.sidecarName("30fto24f_VID_20261007_215240_909.mp4")
        )
    }

    @Test
    fun `已经是sidecar名时幂等_不叠第二层后缀`() {
        // 真机 r20 踩过：清扫"剥段名"得到的恰好就是规范名，再套一层会拼出
        // `….drops.json.drops.json`
        val once = ArcDropLog.sidecarName("VID_1.mp4")
        assertEquals("重复套用必须收敛", once, ArcDropLog.sidecarName(once))
        assertEquals("VID_1.drops.json", once)
    }

    @Test
    fun `无扩展名与点开头的名字按普通文件名处理`() {
        assertEquals("无扩展名直接换后缀", "VID_1.drops.json", ArcDropLog.sidecarName("VID_1"))
        // 点开头是隐藏文件/特殊名，开头的点不是扩展名分割点（dot == 0 不许当成扩展名）
        assertEquals("点开头不当作扩展名", ".mp4.drops.json", ArcDropLog.sidecarName(".mp4"))
    }

    @Test
    fun `旧拼法_成片全名加后缀_必须与新规则分叉`() {
        // 这条钉的是缺陷形态本身：如果哪天有人把规则改回"全名 + EXTENSION"，
        // 上面的规范名用例会红；这条则保证"两种拼法确实不同"这个前提一直成立，
        // 免得清扫侧再推出"兼容两种名字"的分叉实现。
        val video = "VID_20261008_172951_262.mp4"
        val legacy = video + ArcDropLog.EXTENSION
        assertFalse(
            "旧拼法 $legacy 必须不同于规范名（同源缺陷的判别前提）",
            legacy == ArcDropLog.sidecarName(video)
        )
    }

    // ---------------------------------------------------------------- 路径拆分

    @Test
    fun `目录名里带点不被截断`() {
        // 旧实现对整条路径 lastIndexOf('.')：`…/版本1.2/VID_x.mp4` 会把目录截成
        // `…/版本1` 再去拼 sidecar，档案落到别的目录名下
        assertEquals(
            "/storage/emulated/0/Movies/版本1.2/VID_x.drops.json",
            ArcDropLog.sidecarFor("/storage/emulated/0/Movies/版本1.2/VID_x.mp4")
        )
    }

    @Test
    fun `反斜杠路径同样只换文件名`() {
        assertEquals(
            "C:\\Movies\\1.2\\VID_x.drops.json",
            ArcDropLog.sidecarFor("C:\\Movies\\1.2\\VID_x.mp4")
        )
    }

    @Test
    fun `裸文件名与sidecarName同口径`() {
        assertEquals(ArcDropLog.sidecarName("VID_x.mp4"), ArcDropLog.sidecarFor("VID_x.mp4"))
    }

    // ---------------------------------------------------------------- 与清扫同源

    @Test
    fun `清扫归位目标逐字等于规范名`() {
        val clip = "30fto24f_VID_20261008_172951_262.mp4"
        val plan = sweepPlan(
            "30fto24f_.pending-1792056591-VID_20261008_172951_262.drops.json",
            ageMs = StalePendingSweep.SWEEP_STALE_MS + 1,
            cleanTwinExists = false,
            clipName = clip,
            clipPlayable = null
        )
        assertEquals(SweepAction.RENAME_ARCHIVE, plan.action)
        assertEquals(
            "清扫目标必须由 sidecarName 生成（与 readFrom 同一规则）",
            ArcDropLog.sidecarName(clip),
            plan.to
        )
    }

    // ---------------------------------------------------------------- 源码守卫

    @Test
    fun `清扫与UI兜底都必须走sidecarName单一出口`() {
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        assertTrue(
            "sweepPlan 必须用 ArcDropLog.sidecarName 拼归位目标",
            bodyOf(sweep, "sweepPlan").contains("ArcDropLog.sidecarName(clipName)")
        )
        assertTrue(
            "runOnce 的重复件判据必须吃规范目标名",
            bodyOf(sweep, "runOnce").contains("ArcDropLog.sidecarName")
        )
        val ui = codeOnly(mainSourceText("ui/CameraScreen.kt"))
        assertTrue(
            "CameraScreen 的 MediaStore 兜底必须走 sidecarName（不许在 UI 侧各拼一套）",
            ui.contains("val displayName = ArcDropLog.sidecarName(videoName)")
        )
    }

    @Test
    fun `sidecarFor必须先把目录与文件名拆开`() {
        val src = codeOnly(mainSourceText("record/ArcDropLog.kt"))
        val body = bodyOf(src, "sidecarFor")
        // 字符字面量在遮蔽文本里会变成空格，所以数调用次数而不是比字面量内容
        assertTrue(
            "必须分别找 '/' 与 '\\\\' 两个分隔符（maxOf 取最后一个）",
            body.contains("maxOf(") && occurrences(body, "lastIndexOf(").size == 2
        )
        assertTrue("只对文件名段应用命名规则", body.contains("sidecarName(videoPath.substring(slash + 1))"))
    }
}