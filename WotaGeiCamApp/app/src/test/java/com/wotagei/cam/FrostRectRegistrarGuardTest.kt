package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「霜板注册点必须在布局变化后重写矩形」+「矩形必须在承载视图局部坐标里量」的源码级守卫。
 *
 * 缺陷史（两条）：
 * 1. `hudFrostRectRegistrar` 的 LaunchedEffect 只拿 coords（布局回调每次递的都是**同一个**
 *    LayoutCoordinates 实例，坐标是现算值）进 key，注册稳定之后的任何纯布局变化
 *    （旋转——本工程 manifest 配了 configChanges 不重建 Activity、状态栏/挖孔避让 inset 变化、
 *    Dock 自己的 animateContentSize 逐帧变高）都不会重启 effect ⇒ 表停在旧矩形，GL 板与
 *    Compose 描边/fill 分离。修复是把矩形四分量 rectX/Y/W/H 一并进 key。
 * 2. 旧口径用 `positionInWindow()` 现算卡片位置（= `localToWindow`，会走过祖先那层
 *    `graphicsLayer{scale}`）却拼上不含缩放的 `size`——分屏（实时对比播放）下位置被缩、宽度不减，
 *    板与卡片错开且偏大。修复是改走 [com.wotagei.cam.ui.viewLocalRectInto]（`localPositionOf`
 *    抵消共同祖先变换），本条用**负面断言**封死 `positionInWindow()` 再被写回来。
 *
 * 断言按 AGENTS「恒等式不算证明」的口径锁 **函数体**（bodyOf，非整文件 contains），
 * 别处的同名片段喂不绿这里。`positionInWindow` 的负面断言只看**函数体**（注释已被 codeOnly 遮蔽），
 * 与别处仍合法使用它的地方（编辑页回退路径、MergeScene 等）互不干扰。
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
    fun `注册effect的key必须含矩形四分量与预览盒`() {
        assertTrue(
            "LaunchedEffect 的 key 必须含 rectX..rectH（只拿 coords 实例进 key 时，稳定后的布局变化" +
                "不会重启 effect，GL 板停在旧矩形与 Compose 分离）与 preview（预览盒从 null 到有值是" +
                "坐标系切换，不重启就会拿旧口径的矩形配新口径的表头）",
            body.contains("LaunchedEffect(slot, coords, preview, rectX, rectY, rectW, rectH, alpha, intent, visible)")
        )
    }

    @Test
    fun `矩形必须在视图局部坐标里量且不得再用 positionInWindow`() {
        // 正向锚点：统一换算入口必须在体内（缺了它这条守卫就是在对空文本做否定断言）
        assertTrue(
            "hudFrostRectRegistrar 体内必须走 viewLocalRectInto（承载视图局部坐标；分屏那层祖先缩放靠" +
                "localPositionOf 抵消）",
            body.contains("viewLocalRectInto")
        )
        // 负面断言：positionInWindow 会把祖先 graphicsLayer 的缩放算进位置却不缩 size ⇒ 分屏下板错位偏大
        assertFalse(
            "hudFrostRectRegistrar 体内不得再出现 positionInWindow：它含祖先缩放而 size 不含，" +
                "分屏（实时对比播放）下霜板与 Compose 描边分离（旧缺陷原样复发）",
            body.contains("positionInWindow")
        )
    }

    @Test
    fun `屏外哨兵分支仍在`() {
        // 2026-10-04 Dock 滑出真机死锁：哨兵一旦被删（改成撤槽离场），GL 表清空 → live=false →
        // wotaHudCard 的 live 短路挡死恢复，板永久回不来
        assertTrue("屏外哨兵写入必须保留（leftPx = -99999f）", body.contains("leftPx = -99999f"))
        assertTrue("屏外哨兵写入必须保留（rightPx = -99998f）", body.contains("rightPx = -99998f"))
        assertTrue("哨兵行必须带 alpha = 0f", body.contains("alpha = 0f"))
    }

    @Test
    fun `measure失败必须写零面积哨兵不留残板`() {
        // 新口径下 measure() 在矩形退化（bottomRight<=topLeft）时 return false。若失败分支只
        // continue/return 而不写表，槽里会留着上一代的合法矩形，GL 继续按旧矩形贴板 = 残板。
        // 两处消费点（轮询循环 + 布局回调）都必须写零面积哨兵；writeCard 的退化口径会清在场位。
        val hits = KotlinSourceScan.occurrences(body, "writeCard(slot, 0f, 0f, 0f, 0f")
        assertTrue(
            "hudFrostRectRegistrar 的两处 measure() 失败分支都必须写零面积哨兵" +
                "（writeCard(slot, 0f, 0f, 0f, 0f, ...)），实见 ${hits.size} 处",
            hits.size == 2
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
            "rectX != rectScratch[0]", "rectY != rectScratch[1]", "rectW != w", "rectH != h",
            "rectX = rectScratch[0]", "rectY = rectScratch[1]", "rectW = w", "rectH = h"
        ).forEach { needle ->
            assertTrue("hudFrostRectRegistrar 体内缺少 $needle：矩形比对/写回链断了", body.contains(needle))
        }
    }
}
