package com.wotagei.cam

import com.wotagei.cam.camera.Shaders
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光弧修复 GPU 路的**采样器类型守卫**（2026-10-05 真机「GPU 路全程黑屏」缺陷的回归红线）。
 *
 * 缺陷本体：`ArcRepairGl` 曾把 OES 直通画笔（[Shaders.PASS_THROUGH_FS]，`samplerExternalOES`）
 * 同时用作 cur→pending/acc 拷贝与 emit 上编码面的画笔，而那三处采的都是**普通 2D FBO 纹理**。
 * 采样器类型与纹理目标错配时，规范上 texture lookup 结果**未定义**；常见驱动行为是按采样器
 * 声明的目标去读单元上对应槽位的绑定——而 EXTERNAL 槽位在每次绘制后都被 `unbindUnits` 清成
 * 默认纹理（不完整）→ 本机实测恒采出 (0,0,0,1) 全黑；draw/swap/编码照常成功，产物时长正常、
 * 内容全程黑屏、无任何失败返回（CPU 路不碰 GL，正常）。
 * 离线 GL 无法 JVM 实测，这里锁**源码结构红线**（bodyOf，删行/改形即红）：
 * - 两条直通画笔的采样器类型互斥（一条 program 只能吃一种纹理目标，`Shaders.kt` FROST_COPY_FS
 *   KDoc 的老纪律）；2D 版强制不透明、不带 OES 扩展声明（与 ARC_MERGE_FS 同口径）；
 * - 离线引擎：OES 画笔只许出现在 OES 趟（copyOesToCur）；2D 趟（copyToPending/copyIntoAcc/
 *   emitOnGl）必须用 `pass2dProgram`，且 2D 拷贝趟不许再摸 EXTERNAL_OES 绑定（原 `isOes`
 *   死支是同型陷阱，已删，复活即红）；
 * - 录制侧 `ArcMendPass` 的 blit 画笔同罪同修（它采的 cur/pend/acc 也全是 2D FBO 纹理）。
 */
class ArcRepairGlSamplerGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    @Test
    fun `两条直通画笔的采样器类型互斥`() {
        // OES 版只许声明 OES 采样器；2D 版只许声明 2D 采样器——混用就是黑屏缺陷本身
        assertTrue(Shaders.PASS_THROUGH_FS.contains("uniform samplerExternalOES uFrame"))
        assertFalse(Shaders.PASS_THROUGH_FS.contains("sampler2D"))
        assertTrue(Shaders.PASS_THROUGH_2D_FS.contains("uniform sampler2D uFrame"))
        assertFalse(Shaders.PASS_THROUGH_2D_FS.contains("samplerExternalOES"))
        // 2D 版不采外部纹理就不需要扩展声明（部分驱动要求 #extension 是首条指令，能免则免，
        // 与 ARC_MERGE_FS 同口径）；强制不透明纪律两条都要在
        assertFalse(Shaders.PASS_THROUGH_2D_FS.contains("#extension"))
        assertTrue(Shaders.PASS_THROUGH_2D_FS.contains("vec4(texture2D(uFrame, vUv).rgb, 1.0)"))
        // 取大画笔只吃 2D（cur/pend/acc 全是 2D FBO 纹理）
        assertTrue(Shaders.ARC_MERGE_FS.contains("uniform sampler2D uA"))
        assertTrue(Shaders.ARC_MERGE_FS.contains("uniform sampler2D uB"))
        assertFalse(Shaders.ARC_MERGE_FS.contains("samplerExternalOES"))
    }

    @Test
    fun `离线引擎2D支路必须用2D画笔`() {
        val gl = maskedMain("record/ArcRepairGl.kt")
        // 2D 画笔必须在 buildPrograms 里链出来（只定义不链接 = 画笔不存在，绘制恒败）
        assertTrue(
            "buildPrograms 必须链 Shaders.PASS_THROUGH_2D_FS（缺 = 2D 趟没有可用画笔）",
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(gl, "buildPrograms"))
                .contains("Shaders.PASS_THROUGH_2D_FS")
        )
        // 三条 2D 趟必须用 2D 画笔、不得退回 OES 画笔（退回 = 类型错配 = 真机全程黑屏）
        listOf("copyToPending", "copyIntoAcc", "emitOnGl").forEach { fn ->
            val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(gl, fn))
            assertTrue(
                "$fn 采的是普通 2D 纹理，必须 glUseProgram(pass2dProgram)（samplerExternalOES " +
                    "读 EXTERNAL 槽位的默认纹理 = 恒黑，2026-10-05 真机缺陷本体）",
                body.contains("glUseProgram(pass2dProgram)")
            )
            assertFalse(
                "$fn 不许用 OES 直通画笔 passProgram（类型错配 = 全程黑屏）",
                body.contains("glUseProgram(passProgram)")
            )
        }
        // OES 趟（解码帧拷进 cur）保留 OES 画笔——2D 化它反而是反向类型错配
        assertTrue(
            "copyOesToCur 采的是 EXTERNAL_OES 解码帧，必须保留 passProgram",
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(gl, "copyOesToCur"))
                .contains("glUseProgram(passProgram)")
        )
        // 2D 拷贝趟不许再摸 EXTERNAL_OES 绑定（原 isOes 死支是同型陷阱：真调了同样恒黑）
        assertFalse(
            "copyToPending 是 2D→2D 趟，体内不许出现 GL_TEXTURE_EXTERNAL_OES 绑定（isOes 死支已删，复活即红）",
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(gl, "copyToPending"))
                .contains("GL_TEXTURE_EXTERNAL_OES")
        )
        // 释放与新画笔对称（漏删 = 每会话泄漏一枚 program，同 GlRenderEngine 批 I 那条教训）
        assertTrue(
            "releaseGl 必须删 pass2dProgram（漏删 = 每次修复泄漏一枚 program）",
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(gl, "releaseGl"))
                .contains("glDeleteProgram(pass2dProgram)")
        )
    }

    @Test
    fun `在线补弧通道画笔同为2D`() {
        // ArcMendPass 的 blit 画笔采的 cur/pend/acc 也全是 2D FBO 纹理（copyInto/drawPending），
        // 与离线同一处缺陷：OES 采样只发生在引擎 drawPass 里（相机帧→cur），不经这条画笔
        val ensureBody = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("camera/ArcMendPass.kt"), "ensure")
        )
        assertTrue(
            "ArcMendPass.ensure 必须链 Shaders.PASS_THROUGH_2D_FS（OES 画笔采 2D = 补弧帧恒黑，" +
                "批 F 极性修复后该支路已无直通兜底）",
            ensureBody.contains("Shaders.PASS_THROUGH_2D_FS")
        )
        assertFalse(
            "ArcMendPass.ensure 不许再链 OES 直通画笔（blit 的源永远是 2D FBO 纹理）",
            ensureBody.contains("PASS_THROUGH_FS)")
        )
    }
}
