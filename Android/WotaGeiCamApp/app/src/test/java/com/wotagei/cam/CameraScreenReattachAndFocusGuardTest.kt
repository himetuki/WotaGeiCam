package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制页两条「首帧闭包/旧持有者」类缺陷的源码结构守卫（Y52 / Y56）。
 *
 * 这两条修的都是**接线键**，不是可独立计算的纯算式：Compose 的协程键与
 * `rememberUpdatedState` 持有者只有在真机组合里才表现为「读到新值」。JVM 侧能钉住的是
 * 结构本身——键里丢一枚、或回调又读回组合期快照，这里就红。
 */
class CameraScreenReattachAndFocusGuardTest {

    private val flat: String by lazy {
        KotlinSourceScan.flatten(KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")))
    }

    // ---------------------------------------------------------------- Y52

    @Test
    fun `重挂收集器以 ctrl 与 activeSink 为键`() {
        assertTrue(
            "displayReattach 收集器必须同时以 ctrl 与 activeSink 为键（Y52）：只认 ctrl 时" +
                "GPU↔DIRECT 切换后旧收集器写给旧计数持有者，新承载视图的 epoch 不推进",
            flat.contains("LaunchedEffect(ctrl, activeSink)")
        )
        assertFalse(
            "不许退回只认 ctrl 的旧键形态",
            flat.contains("LaunchedEffect(ctrl) { var lastAt = 0L")
        )
        assertTrue(
            "计数持有者必须按 activeSink 重建（与收集器同键），换 sink 后旧计数不再被改写",
            flat.contains("var surfaceEpoch by remember(activeSink) { mutableIntStateOf(0) }")
        )
    }

    // ---------------------------------------------------------------- Y56

    @Test
    fun `点按对焦的可用性判据走 rememberUpdatedState`() {
        assertTrue(
            "focusUsable 必须装进 rememberUpdatedState（Y56）：pointerInput(Unit) 只捕获首帧闭包，" +
                "直接读组合期 Boolean 会把默认可用固化",
            flat.contains("val focusUsableNow by rememberUpdatedState(focusUsable)")
        )
    }

    @Test
    fun `轻触与双击两条入口都现读 focusUsableNow`() {
        // 只锁 `.tapFocus(...)` 调用这块：胶囊中心对焦（onFocusCenter）读的是普通组合值，
        // 不是 pointerInput(Unit) 的首帧闭包，不在此次修复范围（也不该被这条守卫连带判红）。
        val start = flat.indexOf(".tapFocus(")
        assertTrue("点按/双击手势必须仍挂在预览容器上", start >= 0)
        val gesture = flat.substring(start, minOf(flat.length, start + 900))
        val nowReads = Regex("!focusUsableNow -> Unit").findAll(gesture).count()
        assertEquals("轻触与双击必须各自现读 focusUsableNow（共 2 处）", 2, nowReads)
        assertFalse(
            "旧的组合期快照分支不许留在手势回调里",
            gesture.contains("!focusUsable -> Unit")
        )
    }
}