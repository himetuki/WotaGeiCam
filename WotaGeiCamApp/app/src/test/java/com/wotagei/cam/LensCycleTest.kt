package com.wotagei.cam

import com.wotagei.cam.core.nextLensKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 底栏那颗「单击循环切镜头」的取档算式（六项第 7 条）。
 *
 * 喂进去的键表就是 `enumerateLenses` 在运行时给的顺序，所以这里锁的是**循环与回绕规则**，
 * 而不是任何机型的具体颗数——AGENTS：镜头数量与顺序一律从能力表来。
 */
class LensCycleTest {

    private val three = listOf("rear-ultra", "rear-wide", "rear-tele")

    @Test
    fun followsTheEnumeratedOrderAndWraps() {
        assertEquals("rear-wide", nextLensKey(three, "rear-ultra"))
        assertEquals("rear-tele", nextLensKey(three, "rear-wide"))
        // 走完最后一颗回到第一颗，不是撞墙后不动
        assertEquals("rear-ultra", nextLensKey(three, "rear-tele"))
    }

    @Test
    fun unknownCurrentFallsBackToFirst() {
        // 换镜头与 HAL 下线会让列表重排，认不到当前那颗时从第一颗重新开始，而不是停在原地
        assertEquals("rear-ultra", nextLensKey(three, "gone"))
        assertEquals("rear-ultra", nextLensKey(three, null))
    }

    @Test
    fun singleLensCannotCycle() {
        // 定这颗时调用方给「本机只有一颗镜头，无从切换」，绝不静默吞掉一次点击
        assertNull(nextLensKey(listOf("only"), "only"))
        assertNull(nextLensKey(emptyList(), null))
    }

    @Test
    fun frontAndRearMixedListIsTraversedCompletely() {
        val mixed = listOf("rear-ultra", "rear-wide", "front")
        var cursor = mixed.first()
        // 收集整条轨迹而不是只看落点：只断言"转一圈回到起点"的话，一个永远返回第一颗的
        // 错实现也能过（审查 S3-5）。走满 size 步，要求轨迹集合 = 全部镜头且一步不多一步不少。
        val visited = mutableSetOf<String>()
        repeat(mixed.size) {
            cursor = nextLensKey(mixed, cursor) ?: error("应能循环")
            visited += cursor
        }
        assertEquals(mixed.toSet(), visited.toSet())
        assertEquals(mixed.size, visited.size)
        // 转满一圈回到起点
        assertEquals(mixed.first(), cursor)
    }
}
