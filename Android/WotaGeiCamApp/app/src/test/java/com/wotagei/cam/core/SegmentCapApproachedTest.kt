package com.wotagei.cam.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换段预提示判据的纯逻辑账（2026-10-28 审查加固）：85% 阈值 / 不可观测口径 / 产品常量。
 * 引擎侧字节来源是 `RecorderHealth.outputBytes`（Codec 逐缓冲计数、MR 路 Os.fstat），
 * UI 轮询在 CameraScreen 的 pollTask。
 */
class SegmentCapApproachedTest {

    /** 便于构造的小上限：与产品 3.5GiB 同判据，只换标尺 */
    private val cap = 1_000_000L

    @Test
    fun `达到85百分即置真_之下不置`() {
        assertTrue(segmentCapApproached(850_000L, cap))
        assertTrue("超过阈值当然也真", segmentCapApproached(999_999L, cap))
        assertFalse("85% 差 1 字节不算到", segmentCapApproached(849_999L, cap))
        assertFalse("空段不算", segmentCapApproached(0L, cap))
    }

    @Test
    fun `不可观测的负值恒假_量不到不是超了`() {
        assertFalse("-1 = RecorderHealth 不可观测口径，不许当超限", segmentCapApproached(-1L, cap))
        assertFalse(segmentCapApproached(Long.MIN_VALUE, cap))
    }

    @Test
    fun `产品默认上限3点5GiB_85百分约3点19GB`() {
        // 真实标尺：3,758,096,384 × 0.85 = 3,194,381,926.4 ⇒ 首个达标字节 3,194,381,927；
        // 50Mbps 下 ≈8.5 分钟开始提示，9.3 分钟整段到顶——10 分钟以上的录制必然先看到提示再看到换段
        assertTrue(segmentCapApproached(3_194_381_927L))
        assertFalse(segmentCapApproached(3_194_381_926L))
    }
}
