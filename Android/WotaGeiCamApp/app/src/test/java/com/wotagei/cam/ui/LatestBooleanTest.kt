package com.wotagei.cam.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y56 桥测：手势闭包只捕获一次、持有者本身稳定；组合期更新 focusUsable 后，
 * 旧闭包下一次执行必须读到最新值（而不是首帧固化的 Boolean）。
 */
class LatestBooleanTest {

    @Test
    fun `旧闭包通过稳定持有者读到最新值`() {
        val latest = LatestBoolean(false)
        // 模拟 pointerInput(Unit) 首帧捕获：闭包只拿到持有者，不拿当时的 Boolean
        val gestureClosure: () -> Boolean = { latest.value }

        assertFalse("首帧值", gestureClosure())
        latest.update(true)
        assertTrue("能力枚举完成后旧闭包必须读到 true", gestureClosure())
        latest.update(false)
        assertFalse("切到定焦镜头后旧闭包必须读到 false", gestureClosure())
    }
}
