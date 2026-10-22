package com.wotagei.cam.bt

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2DP 代理的**晚到回收守卫**（2026-10-07 缺陷修复）。
 *
 * 缺陷：进录制页立刻退出时 `close()` 先跑（此刻 `a2dp==null`，`closeProfileProxy` 被短路跳过），
 * `getProfileProxy` 的绑定回调 `onServiceConnected` 晚于 close 到达且无 `closed` 闸、直接
 * `a2dp = proxy`——之后无人再释放，代理与蓝牙服务绑定驻留到进程死（泄漏）。
 *
 * 守卫钉住早退分支的存在与位置：`if (closed)` 必须**先于** `a2dp = proxy`，且分支内必须
 * 当场 `closeProfileProxy` 解绑。行为在 JVM 单测里跑不起来（无 Robolectric、依赖系统蓝牙服务），
 * 按本项目惯例锁源码结构（判定作用在 codeOnly 遮蔽后的文本上，注释提到不算）。
 */
class BtProxyLeakGuardTest {

    /** onServiceConnected 的接线体检：返回违规清单，空 = 接线完整（真身与突变体同吃） */
    private fun lateProxyViolations(src: String): List<String> {
        val bad = ArrayList<String>()
        val body = flatten(bodyOf(src, "onServiceConnected"))
        val closedGate = body.indexOf("if (closed)")
        val release = body.indexOf("closeProfileProxy(BluetoothProfile.A2DP, proxy)")
        val assign = body.indexOf("a2dp = proxy")
        if (closedGate < 0) bad.add("onServiceConnected 缺 closed 早退闸（close 之后晚到的代理照常赋值 = 泄漏）")
        if (release < 0) bad.add("早退分支必须当场 closeProfileProxy 解绑晚到代理（光 return 不解绑仍泄漏）")
        if (assign < 0) bad.add("找不到 a2dp = proxy 赋值（守卫失去输入，不许算通过）")
        if (closedGate >= 0 && assign >= 0 && closedGate > assign) {
            bad.add("closed 闸必须先于 a2dp = proxy（先赋值后查 = 晚到代理照样泄漏）")
        }
        if (closedGate >= 0 && release >= 0 && release < closedGate) {
            bad.add("closeProfileProxy 必须在 closed 闸之内（闸外无条件解绑会拆掉正常会话的代理）")
        }
        return bad
    }

    @Test
    fun `onServiceConnected 必须有closed早退并当场解绑晚到代理`() {
        val violations = lateProxyViolations(
            codeOnly(mainSourceText("bt/BtSpeakerController.kt"))
        )
        assertTrue(
            "A2DP 晚到代理回收发现回归：\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `尺子自己能红_删closed早退必报红`() {
        val src = codeOnly(mainSourceText("bt/BtSpeakerController.kt"))
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", lateProxyViolations(src).isEmpty())
        // 突变：闸失效（回到祖传形态——晚到代理直接赋值）。
        // 文本级突变不编译；if (false) 保持花括号配平，遮蔽器才能继续解函数体
        val mutant = src.replace("if (closed) {", "if (false) {")
        assertTrue(
            "closed 闸失效后尺子必须红",
            lateProxyViolations(mutant).isNotEmpty()
        )
    }

    /** closed 字段的可见性闸：@Volatile 必须紧贴字段（允许中间隔被遮蔽成空白的注释行） */
    private fun closedIsVolatile(src: String): Boolean {
        val at = src.indexOf("private var closed")
        if (at < 0) return false
        return src.substring(0, at).trimEnd().endsWith("@Volatile")
    }

    @Test
    fun `closed 字段必须volatile_主线程写binder线程读`() {
        val src = codeOnly(mainSourceText("bt/BtSpeakerController.kt"))
        assertTrue(
            "closed 必须 @Volatile（close() 主线程写 / onServiceConnected binder 线程读，无 happens-before 可读到过期 false 复现泄漏）",
            closedIsVolatile(src)
        )
    }

    @Test
    fun `突变自证_删closed的volatile必报红`() {
        val src = codeOnly(mainSourceText("bt/BtSpeakerController.kt"))
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", closedIsVolatile(src))
        // 突变：精确抹掉 closed 字段前的 @Volatile（不动文件里其他字段）
        val mutant = src.replace("@Volatile\n    private var closed", "            private var closed")
        assertFalse("删 @Volatile 后尺子必须红", closedIsVolatile(mutant))
    }
}
