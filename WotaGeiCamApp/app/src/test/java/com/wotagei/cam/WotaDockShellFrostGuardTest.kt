package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「底栏壳注册的霜矩形也必须在承载视图局部坐标里量」的源码级守卫。
 *
 * 与 [FrostRectRegistrarGuardTest] 同一族缺陷的另一条注册链（[Modifier.wotaDockShell]）：
 * 旧口径用 `positionInWindow()` 现算节点原点（= `localToWindow`，含祖先那层
 * `graphicsLayer{scale}`），分屏（实时对比播放）下原点被缩、`drawWithCache` 的局部宽高不缩
 * ⇒ 板与底板轮廓分离。修复是改走 `viewLocalOriginInto`（`localPositionOf` 抵消共同祖先变换）。
 *
 * 另外锁住**六元组比对**：节点原点必须进比对，否则页面往返/沉浸切换时节点平移而局部几何不变
 * （2026-10-04 真机 56px「底栏两层分离」的根因），漏比就停旧位。
 *
 * 断言只作用在函数体（bodyOf）上，且 `positionInWindow` 的负面断言看不见注释（codeOnly 已遮蔽），
 * 与同文件里仍合法使用它的 `mergeAnchor`（MergeScene 走布局矩形）互不干扰。
 */
class WotaDockShellFrostGuardTest {

    private val body: String by lazy {
        KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/anim/LiquidMerge.kt")),
                // 扩展函数：接收者必须并入名字
                "Modifier.wotaDockShell"
            )
        )
    }

    @Test
    fun `底栏壳原点必须在视图局部坐标里量且不得再用 positionInWindow`() {
        assertTrue(
            "wotaDockShell 体内必须走 viewLocalOriginInto（承载视图局部坐标；分屏那层祖先缩放靠" +
                "localPositionOf 抵消）",
            body.contains("viewLocalOriginInto")
        )
        assertFalse(
            "wotaDockShell 体内不得再出现 positionInWindow：它含祖先缩放而局部宽高不含，" +
                "分屏（实时对比播放）下霜板与底板轮廓分离（旧缺陷原样复发）",
            body.contains("positionInWindow")
        )
        // 预览盒坐标必须真的被读到（回退才有依据；缺它这条链就永远走窗口系）
        assertTrue("wotaDockShell 必须读 LocalPreviewCoords 决定坐标系", body.contains("LocalPreviewCoords"))
    }

    @Test
    fun `六元组比对必须含节点原点`() {
        listOf(
            "frostLast[4] != ox", "frostLast[5] != oy",
            "frostLast[4] = ox", "frostLast[5] = oy"
        ).forEach { needle ->
            assertTrue("wotaDockShell 体内缺少 $needle：节点原点漏比会让板停在旧位置", body.contains(needle))
        }
    }
}
