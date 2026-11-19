package com.wotagei.cam

import com.wotagei.cam.record.ArcPublishOutcome
import com.wotagei.cam.record.arcPublishOutcome
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R08：补弧产物的「文件 / 发布 / 索引」三段账目。
 *
 * 旧实现只把 `keep`（封口成功）当成功返回：`VideoStore.seal` 两次 `IS_PENDING=0` 都失败后
 * 文件仍在盘上，但媒体库过滤 pending、系统相册也隐藏；播放页却显示「修复完成，已存入相册」。
 * 现在封口后的三态必须由 [arcPublishOutcome] 显式分流，pending 分支不得报纯成功。
 */
class ArcRepairPublishTest {

    @Test
    fun `封口并发布成功才算PUBLISHED`() {
        assertEquals(ArcPublishOutcome.PUBLISHED, arcPublishOutcome(keep = true, committed = true))
    }

    @Test
    fun `封口但两次发布失败是PENDING_PUBLISH而非成功`() {
        assertEquals(ArcPublishOutcome.PENDING_PUBLISH, arcPublishOutcome(keep = true, committed = false))
    }

    @Test
    fun `未封口或零样本按丢弃处理`() {
        assertEquals(ArcPublishOutcome.DISCARDED, arcPublishOutcome(keep = false, committed = true))
        assertEquals(ArcPublishOutcome.DISCARDED, arcPublishOutcome(keep = false, committed = false))
    }

    @Test
    fun `源码钉扎_发布三态与索引刷新接线`() {
        val src = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        val fin = bodyOf(src, "finish")
        assertTrue("finish 必须按 committed 分三态（不能只看 keep）", fin.contains("arcPublishOutcome("))
        assertTrue("必须读 seal 回报的 committed", fin.contains("seg.committed"))
        assertTrue(
            "发布成功必须由任务所有者刷新索引（否则媒体库要等下一次无关 invalidate）",
            fin.contains("MediaRepo.get(ctx).invalidate()")
        )
        assertTrue("pending 分支必须带 PUBLISH_PENDING 码", fin.contains("ArcRepairError.PUBLISH_PENDING"))
        assertTrue("pending 分支必须标记 pendingPublish", fin.contains("pendingPublish = true"))
        assertTrue("pending 分支必须保留 uri/path/bytes 账目（文件在盘上）", fin.contains("uri = seg.uri") && fin.contains("bytes = seg.bytes"))
    }

    @Test
    fun `源码钉扎_播放页对待发布给专门文案`() {
        val player = codeOnly(mainSourceText("player/PlayerScreen.kt"))
        assertTrue("播放页必须先识别 pendingPublish", player.contains("pendingPublish == true"))
        assertTrue("播放页必须给待发布专用文案", player.contains("R.string.player_arc_pending_publish"))
    }
}
