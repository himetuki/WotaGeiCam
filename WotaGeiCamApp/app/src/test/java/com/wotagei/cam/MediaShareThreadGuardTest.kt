package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.regionsOf
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量分享主线程磁盘 stat 收口的结构守卫（P2，7c5716d 祖传缺陷）。
 *
 * 缺陷：`WotaShare.resolve` 里的 `file.exists()` 被 `MediaOps.share` 在主线程逐项调用，
 * 多选几十个视频 = 主线程 N 次磁盘 stat（点分享卡顿）。
 * 钉住：resolve 必须包进 `withContext(Dispatchers.IO)`、起 chooser 回主线程、
 * 取消不许被吞成失败横幅；`MediaOps.share` 必须整条走 `launchOp`（统一兜底），
 * 且 #46 的「回收站态不分享」前置剔除不许动。
 * 突变：把 IO 摘掉 / 退回同步直调 → 本类用例必红。
 */
class MediaShareThreadGuardTest {

    /** MediaActions.kt 里有多个 fun share（MediaActions/MediaOps 各一），按体内标记点名 */
    private fun shareBodyOf(src: String, marker: String): String {
        val hits = regionsOf(src, "share")
            .map { src.substring(it.start, it.end) }
            .filter { it.contains(marker) }
        assertTrue("按标记 $marker 定位 fun share 必须恰好 1 个（守卫前提）", hits.size == 1)
        return hits[0]
    }

    @Test
    fun `WotaShare 的 resolve 必须在IO线程_startActivity 回主线程`() {
        val src = codeOnly(mainSourceText("media/Share.kt"))
        val body = flatten(bodyOf(src, "share"))
        val io = body.indexOf("withContext(Dispatchers.IO)")
        val resolve = body.indexOf("resolve(context, it)")
        val main = body.indexOf("withContext(Dispatchers.Main)")
        val start = body.indexOf("startActivity(chooser)")
        assertTrue(
            "resolve 的逐项磁盘 stat 必须包进 withContext(Dispatchers.IO)（多选几十个视频不许主线程逐项 stat）",
            io >= 0 && resolve > io
        )
        assertTrue(
            "起 chooser 必须回主线程（withContext(Dispatchers.Main) 先于 startActivity）",
            main >= 0 && start > main
        )
        assertTrue(
            "取消必须先于通用 catch 原样抛回（离开屏幕的正常取消不许吞成分享失败横幅）",
            body.indexOf("catch (e: CancellationException)") in 0 until body.indexOf("catch (e: Exception)")
        )
        assertTrue(
            "FLAG_GRANT_READ_URI_PERMISSION 授权 flag 不许动",
            body.contains("FLAG_GRANT_READ_URI_PERMISSION")
        )
    }

    @Test
    fun `MediaOps_share 必须走launchOp且保留trashed前置剔除`() {
        val src = codeOnly(mainSourceText("media/MediaActions.kt"))
        val opsBody = flatten(shareBodyOf(src, "launchOp"))
        assertTrue(
            "分享必须整条走 launchOp（统一兜底：失败记日志 + 横幅 + 取消透传）",
            opsBody.contains("launchOp { actions.share(")
        )
        assertTrue(
            "trashed 项前置剔除不许动（#46 定版：回收站态的行不分享）",
            opsBody.contains("isTrashed")
        )
        assertTrue(
            "不许保留旧的同步直调形态（那是在组合期线程做 stat）",
            !opsBody.contains("val res = actions.share(")
        )
        // MediaActions.share 改为挂起返回 MediaOp（签名在函数体外，bodyOf 够不到，锁签名文本）
        val beforeCall = src.substring(0, src.indexOf("WotaShare.share(app, clips)"))
        assertTrue(
            "MediaActions.share 必须是 suspend 返回 MediaOp（launchOp 的块签名要求）",
            Regex("suspend fun share\\(clips: List<VideoClip>\\): MediaOp").containsMatchIn(beforeCall)
        )
    }

    @Test
    fun `突变自证_摘掉IO或退回同步直调必红`() {
        // 突变 1：Share.kt 把 resolve 摘出 IO（文本级突变不编译，只验尺子会红）
        val shareSrc = codeOnly(mainSourceText("media/Share.kt"))
        val goodBody = flatten(bodyOf(shareSrc, "share"))
        val io = goodBody.indexOf("withContext(Dispatchers.IO)")
        val resolve = goodBody.indexOf("resolve(context, it)")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", io >= 0 && resolve > io)
        val mutant1 = flatten(
            bodyOf(
                shareSrc.replace(
                    "withContext(Dispatchers.IO) { clips.mapNotNull { resolve(context, it) } }",
                    "clips.mapNotNull { resolve(context, it) }"
                ),
                "share"
            )
        )
        val io1 = mutant1.indexOf("withContext(Dispatchers.IO)")
        val resolve1 = mutant1.indexOf("resolve(context, it)")
        assertTrue(
            "摘掉 IO 后尺子必须红",
            !(io1 >= 0 && resolve1 > io1)
        )
        // 突变 2：MediaOps.share 退回旧的同步直调。定位 marker 用 isTrashed（突变不动它）——
        // 若用 launchOp 定位，突变体恰好删光 marker 会以"守卫前提失败"假红，验不了尺子
        val actionsSrc = codeOnly(mainSourceText("media/MediaActions.kt"))
        val mutant2 = flatten(
            shareBodyOf(
                actionsSrc.replace("launchOp { actions.share(shippable) }", "run { actions.share(shippable) }"),
                "isTrashed"
            )
        )
        assertTrue("退回同步直调后尺子必须红", !mutant2.contains("launchOp { actions.share("))
    }
}
