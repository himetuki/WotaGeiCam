package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「分辨率」弹窗里帧率行选档的**精确性重查守卫**（2026-10-07 缺陷修复）。
 *
 * 缺陷：SizePill 的 fps onPick 曾用裸 `fps.copy(value = opt.value)`——`exact` 沿用旧档。
 * "有原生 [25,25] 但无 [24,24]"的机型从 ※24 切到原生 25 会把 exact=false 带过去：
 * `arcConvertNow()` 判 `value in REQUIRED_FPS && !exact` 误判为 ※ 档 → 录制期对原生精确
 * 25fps 白抽帧（成片卡顿）+ HUD 挂假 ※。同文件 FpsPill 早在 ea0b74e 修过同款（:288-294），
 * SizePill 是漏网点。本守卫钉住 SizePill 分支必须与 FpsPill 同口径重查，回退即红。
 *
 * 2026-10-26 判定域**两次升级**：先是从「按目标档的单模态 fpsRangesFor(size, highSpeed)」改成
 * 「普通∪高速并集 fpsRangesUnion(size)」（厂商把 50/60 只放进高速表时，vivo V2520A），
 * 单模态判定会让这两档在换档时落回 disabled。守卫判据随之更新，意图不变（按目标档重查）。
 */
class SizeFpsExactRequeryGuardTest {

    private fun maskedPills(): String = codeOnly(mainSourceText("ui/CameraPills.kt"))

    /** SizePill 的 fps 选档体检：返回违规清单，空 = 接线完整（真身与突变体同吃） */
    private fun sizePillFpsViolations(pills: String): List<String> {
        val bad = ArrayList<String>()
        val size = flatten(bodyOf(pills, "SizePill"))
        if (!size.contains("decideFps(size, opt.value)")) {
            bad.add(
                "SizePill 的 fps onPick 必须按目标档走 core.decideFps(size, 目标档) 重查——" +
                    "它是「用哪张表 / 下发哪个区间 / 精不精确 / 要不要高速会话」的唯一出口：" +
                    "自己的单模态判定会让只进高速表的 50/60 灰显（2026-10-26 之前），" +
                    "而按 fps 阈值猜表又会让请求发出机器没声明的区间（同轮修掉）"
            )
        }
        if (size.contains("fps.copy(value = opt.value)")) {
            bad.add("SizePill 出现裸 fps.copy(value = opt.value)（exact 沿用旧档；只许带 enabled/exact 的兜底全形）")
        }
        return bad
    }

    @Test
    fun `SizePill 帧率档必须按目标档重查精确性`() {
        val violations = sizePillFpsViolations(maskedPills())
        assertEquals(
            "SizePill 帧率选档发现 ※ 档污染回归：\n${violations.joinToString("\n")}",
            0, violations.size
        )
    }

    @Test
    fun `尺子自己能红_回退裸copy或删重查必报红`() {
        val pills = maskedPills()
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", sizePillFpsViolations(pills).isEmpty())
        // 突变 1：回退成祖传裸 copy（7c5716d 形态，exact 沿用旧档）
        val bareCopy = pills.replace(
            "fps.copy(value = opt.value, enabled = false, exact = false)",
            "fps.copy(value = opt.value)"
        )
        assertTrue(
            "回退裸 copy 后尺子必须红",
            sizePillFpsViolations(bareCopy).any { it.contains("裸 fps.copy") }
        )
        // 突变 2：抄丢重查调用（文本级突变，不编译——守卫只判代码形态）
        val noRequery = pills.replace("decideFps(size, opt.value)", "fps.copy(value = opt.value)")
        assertTrue(
            "删掉按目标档重查后尺子必须红",
            sizePillFpsViolations(noRequery).any { it.contains("decideFps") }
        )
    }
}
