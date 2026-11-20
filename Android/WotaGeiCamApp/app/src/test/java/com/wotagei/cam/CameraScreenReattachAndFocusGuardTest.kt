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
    private val ctrlSrc: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/CameraController.kt"))
    }

    // ---------------------------------------------------------------- Y52

    @Test
    fun `重挂收集器以 ctrl 与 activeSink 为键`() {
        // 2026-11-20 独立复核：只对整文件查 "LaunchedEffect(ctrl, activeSink)" 会被同一文件
        // 另一个看门狗 effect 满足（:871），收集器退回 LaunchedEffect(ctrl) 也照样绿。
        // 这里把断言收窄到 displayReattach.collect 之前的那一段。
        val collectAt = flat.indexOf("displayReattach.collect")
        assertTrue("必须存在 displayReattach 收集器", collectAt > 0)
        val collectorBlock = flat.substring(maxOf(0, collectAt - 400), collectAt)
        assertTrue(
            "displayReattach 收集器必须同时以 ctrl 与 activeSink 为键（Y52）：只认 ctrl 时" +
                "GPU↔DIRECT 切换后旧收集器写给旧计数持有者，新承载视图的 epoch 不推进",
            collectorBlock.contains("LaunchedEffect(ctrl, activeSink)")
        )
        assertFalse(
            "收集器不许退回只认 ctrl 的旧键形态",
            collectorBlock.contains("LaunchedEffect(ctrl) {")
        )
        assertTrue(
            "计数持有者必须按 activeSink 重建（与收集器同键），换 sink 后旧计数不再被改写",
            flat.contains("val reattachEpoch = remember(activeSink) { ReattachEpoch")
        )
        assertTrue(
            "收集器必须通过新持有者推进（旧 sink 的迟到请求只动旧纪元）",
            flat.contains("reattachEpoch.request()")
        )
        assertTrue(
            "承载视图必须按新持有者的纪元重建",
            flat.contains("key(reattachEpoch.epoch)")
        )
        // Y52 同拍：recoverGlSink 先 bind 新 sink 再 request，组合还没重组；replay=0 会把
        // 请求只送给旧收集器。replay=1 让新收集器启动时补收这一枚。
        assertTrue(
            "displayReattach 必须 replay=1（否则同一拍发出的重挂请求新收集器收不到）",
            ctrlSrc.contains("MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)")
        )
    }

    // ---------------------------------------------------------------- Y56

    @Test
    fun `点按对焦的可用性判据走 rememberUpdatedState`() {
        assertTrue(
            "focusUsable 必须装进稳定持有者（Y56）：pointerInput(Unit) 只捕获首帧闭包，" +
                "直接读组合期 Boolean 会把默认可用固化",
            flat.contains("val focusUsableLatest = remember { LatestBoolean(focusUsable) }")
        )
    }

    @Test
    fun `轻触与双击两条入口都现读 focusUsableNow`() {
        // 只锁 `.tapFocus(...)` 调用这块：胶囊中心对焦（onFocusCenter）读的是普通组合值，
        // 不是 pointerInput(Unit) 的首帧闭包，不在此次修复范围（也不该被这条守卫连带判红）。
        val start = flat.indexOf(".tapFocus(")
        assertTrue("点按/双击手势必须仍挂在预览容器上", start >= 0)
        val gesture = flat.substring(start, minOf(flat.length, start + 900))
        val nowReads = Regex("!focusUsableLatest.value -> Unit").findAll(gesture).count()
        assertEquals("轻触与双击必须各自现读 focusUsableLatest（共 2 处）", 2, nowReads)
        assertFalse(
            "旧的组合期快照分支不许留在手势回调里",
            gesture.contains("!focusUsable -> Unit")
        )
    }
}