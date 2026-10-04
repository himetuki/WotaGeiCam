package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「霜板注册点必须在布局变化后重写矩形」的源码级守卫。
 *
 * 缺陷史：`hudFrostRectRegistrar` 的 LaunchedEffect 只拿 coords（布局回调每次递的都是**同一个**
 * LayoutCoordinates 实例，positionInWindow 是现算值）进 key，注册稳定之后的任何纯布局变化
 * （旋转——本工程 manifest 配了 configChanges 不重建 Activity、状态栏/挖孔避让 inset 变化、
 * Dock 自己的 animateContentSize 逐帧变高）都不会重启 effect ⇒ 表停在旧矩形，GL 板与
 * Compose 描边/fill 分离——与 frostLast 漏窗口原点（464bd5e「两层分离」）同族。
 * 修复是把窗口矩形四分量 rectX/Y/W/H 一并进 key；守卫锁的就是这条，谁把 key"优化"回去测试先红。
 *
 * 断言按 AGENTS「恒等式不算证明」的口径锁 **函数体**（bodyOf，非整文件 contains），
 * 别处的同名片段喂不绿这里。
 */
class FrostRectRegistrarGuardTest {

    private val body: String by lazy {
        KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/design/Widgets.kt")),
                // 扩展函数：bodyOf 的定位正则要求 `fun <名字>(` 紧邻，接收者必须并入名字
                "Modifier.hudFrostRectRegistrar"
            )
        )
    }

    @Test
    fun `注册effect的key必须含窗口矩形四分量`() {
        assertTrue(
            "LaunchedEffect 的 key 必须含 rectX..rectH：只拿 coords 实例进 key 时，稳定后的布局变化" +
                "（旋转/避让/尺寸动画）不会重启 effect，GL 板停在旧矩形与 Compose 分离",
            body.contains("LaunchedEffect(slot, coords, rectX, rectY, rectW, rectH, alpha, intent, visible)")
        )
    }

    @Test
    fun `布局回调必须比对并写回矩形分量`() {
        // 正向锚点一：回调里要先认领 coords（effect 首轮从 null 变有值靠它）
        assertTrue("布局回调必须写 coords（effect 首轮启动的输入）", body.contains("coords = c"))
        // 正向锚点二：四个分量都要有「变了才写」的比对与写回，缺一个就是那个轴向漏报；
        // 只锁「写回」不锁「比对」的话，把比对删掉（每次布局都写）测试仍绿——比对在不在
        // 不改行为只改写表次数，但两条都在才说明这条链是完整的一座
        listOf(
            "rectX != p.x", "rectY != p.y", "rectW != s.width.toFloat()", "rectH != s.height.toFloat()",
            "rectX = p.x", "rectY = p.y", "rectW = s.width.toFloat()", "rectH = s.height.toFloat()"
        ).forEach { needle ->
            assertTrue("hudFrostRectRegistrar 体内缺少 $needle：矩形比对/写回链断了", body.contains(needle))
        }
    }
}
