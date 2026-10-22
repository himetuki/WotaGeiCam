package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Dock 滑出判据必须与栏位无关」的源码级守卫（用户 2026-10-04 定版）。
 *
 * 定版口径：录制页上**任何带弹窗的控件**（竖 Dock 内的条目、底栏左右侧那两颗、顶栏胶囊组）
 * 一点开，左右两枚竖 Dock 都滑出画面收回，弹窗关闭时复原。判据只能是"有浮层开着"
 * （就近胶囊 `pop` / 整块面板 `sheet`），**不许看控件在哪枚容器里**。
 *
 * 为什么值得单独一座闸：本项目有「编辑控件位置」，同颗控件可被用户拖到任意栏位。
 * 若把触发条件写成栏位判断（"只有左 Dock 那颗弹的才滑"或按 zoneRects 反推空间够不够），
 * 控件一旦被挪动就静默失配——JVM 侧拿不到、lint 不报，真机上表现为"某颗挪了位置的控件
 * 弹窗不再收起 Dock"。这是位置表与行为判据的分叉，只能在源码级挡住。
 *
 * 断言取的是遮蔽后的**表达式文本**（注释与字面量都不参与），因此只有改判据本体才会红；
 * 旁边注释怎么写、提示文案怎么改都不影响。
 */
class DockSlideTriggerGuardTest {

    private val masked: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
    }

    /** `val docksOut = <表达式>` 的表达式文本（单行）；取不到 = 判据被改名或挪走，直接红 */
    private fun docksOutExpr(): String {
        val at = masked.indexOf("val docksOut =")
        assertTrue("找不到 docksOut 判据：它被改名/删了，滑出行为没有真源可守", at >= 0)
        val rest = masked.substring(at + "val docksOut =".length)
        return rest.substringBefore('\n').trim()
    }

    @Test
    fun `判据必须由浮层态构成`() {
        val expr = docksOutExpr()
        // 两条浮层线都要在判据里：就近胶囊与整块面板（各控制项的长按/点击入口都汇聚到这两态）
        assertTrue("docksOut 没读就近胶囊 pop：竖Dock/底栏/顶栏的胶囊弹窗将不会收起 Dock", expr.contains("pop"))
        assertTrue("docksOut 没读整块面板 sheet：曲线整页面板的滑出会被削弱", expr.contains("Sheet"))
    }

    @Test
    fun `判据不许出现栏位身份`() {
        val expr = docksOutExpr()
        // 栏位枚举/按栏的寄存器名一概不许出现：出现任何一枚就等于把行为与"控件现在在哪一栏"绑定，
        // 用户在「编辑控件位置」里挪动该控件后行为就静默失配（本守卫的存在理由）
        listOf("HudZone", "zoneRects", "LEFT", "RIGHT", "BOTTOM", "TOP", "READOUT").forEach { banned ->
            assertFalse("docksOut 判据里出现栏位身份 $banned：滑出必须按'有弹窗'触发，与控件所属容器无关", expr.contains(banned))
        }
    }

    @Test
    fun `判据只由布尔合取构成`() {
        // 只允许 sheet / pop 两个量与比较、逻辑或、括号、空白：别夹带第三态（例如 recording、
        // splitOn），多一个状态就多一条只有真机点得到的分支
        val expr = docksOutExpr()
        val allowed = ("sheet pop ! = | & ( ) Sheet.NONE null true false" + " .\t").toSet()
        val illegal = expr.filter { it !in allowed }
        assertTrue("docksOut 判据里出现未登记的符号: '$illegal'（整条: $expr）", illegal.isEmpty())
    }
}
