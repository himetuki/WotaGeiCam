package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体链 sidecar / 入库一致性的**源码结构守卫**（逻辑自检批次 H）。
 *
 * MediaActions/MediaRepo 依赖 ContentResolver/Room，JVM 行为测试造不出来；
 * 按本循环守卫口径锁函数体——三处一致性红线被"顺手重构"删掉时行为测试未必红：
 * - 媒体库改名的 sidecar 必须搬去**回查到的真实落盘名**（MediaStore 撞名自行加序号仍报
 *   成功，用请求名拼落点 = 旧名孤儿 + 新片无档案，与录制路 A 批修复同族）；
 * - 回收站态彻底删除要按「目录+原显示名」补删 sidecar（系统回收站把底层文件改名成
 *   `.trashed-<ts>-<原名>`、DATA 列跟着变，只删 dataPath 侧 = 孤儿档案残留）；
 * - 媒体库常规查询必须带 IS_PENDING 门（pending 记录对 owner 查询默认可见，不挡 =
 *   录制/导出中回媒体库看到 0 字节幽灵条目）。
 */
class MediaSidecarGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    @Test
    fun `媒体库改名回查真实名再搬sidecar`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("media/MediaActions.kt"), "rename")
        )
        assertTrue(
            "rename 必须回查真实 DISPLAY_NAME 再搬 sidecar（MediaStore 撞名自行加序号仍报成功，" +
                "用请求名拼 sidecar 落点会与落盘名分叉；只定义不调用等于没修）",
            body.contains("queryDisplayName(")
        )
    }

    @Test
    fun `回收站态彻底删除按原名补删sidecar`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("media/MediaActions.kt"), "deleteOneByOne")
        )
        assertTrue(
            "deleteOneByOne 对回收站态行必须按目录+原显示名补删 sidecar（系统回收站把底层文件改名成 " +
                ".trashed-<ts>-<原名>，DATA 列跟着变，只删 dataPath 侧会留下孤儿档案）",
            body.contains("deleteFor(originPath)")
        )
    }

    @Test
    fun `媒体库常规查询带IS_PENDING门`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("media/MediaRepo.kt"), "queryMediaStore")
        )
        assertTrue(
            "queryMediaStore 必须带 is_pending = 0 门（pending 记录对 owner 查询默认可见，" +
                "不挡的话录制/导出中回媒体库看到 0 字节幽灵条目，点开是半成品文件）",
            body.contains("IS_PENDING")
        )
    }
}
