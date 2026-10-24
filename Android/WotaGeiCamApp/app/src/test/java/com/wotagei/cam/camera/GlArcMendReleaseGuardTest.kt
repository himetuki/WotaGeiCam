package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补弧离屏通道（ArcMendPass）与编码面句柄的释放守卫（2026-10-25 审查轮）。
 *
 * 背景：MEND 模式下 [ArcMendPass] 持有 5 张编码尺寸 RGBA 纹理（1080p ≈ 41MiB / 4K ≈ 166MiB）。
 * 它唯一的释放点原是 `setArcConvert`（下一次起录才走到），于是「录一段 → 停止」之后，
 * 整个空闲预览期这些纹理一直驻在显存里——而 idle 预览根本用不到补弧。
 * 本守卫钉住两条释放路径：**解绑编码面**（停录）与 **引擎销毁**（退出录制页）。
 *
 * 另钉一枚跨线程可见性：`encoderNative` 由 GL 线程写、录制控制线程经
 * [GlRenderEngine.isEncoderSurfaceNative] 读（分段轮转的「新面已绑定」谓词），
 * 同组字段全是 `@Volatile`，漏一枚就让轮询线程按 JMM 读不到新值 → 白等到超时。
 */
class GlArcMendReleaseGuardTest {

    private fun violations(src: String): List<String> {
        val bad = ArrayList<String>()
        val detach = flatten(bodyOf(src, "replaceEncoderSurface"))
        // 解绑分支（surface == null）必须释放补弧通道：这是停录后唯一会走到的出口
        val detachBranch = detach.substringAfter("if (surface == null)").substringBefore("val config =")
        if (!detachBranch.contains("arcMendPass?.release()")) {
            bad.add("解绑编码面（停录）必须释放 arcMendPass——否则 5 张编码尺寸纹理滞留整个空闲预览期")
        }
        if (!detachBranch.contains("arcMendPass = null")) {
            bad.add("释放后必须把 arcMendPass 置 null（否则下一次解绑重复 release 同一批已删的 GL 对象）")
        }
        val release = flatten(bodyOf(src, "releaseGl"))
        if (!release.contains("arcMendPass?.release()")) {
            bad.add("releaseGl 必须兜底释放 arcMendPass（引擎直接销毁、没走过解绑的那条路）")
        }
        // @Volatile：声明行本身不带注解，看它上面那一行
        val lines = src.lines()
        val declAt = lines.indexOfFirst { it.contains("private var encoderNative") }
        val prev = lines.getOrNull(declAt - 1)?.trim().orEmpty()
        if (declAt < 0 || prev != "@Volatile") {
            bad.add("encoderNative 必须 @Volatile（GL 线程写、录制控制线程经 isEncoderSurfaceNative 读）")
        }
        return bad
    }

    private fun source() = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))

    @Test
    fun `补弧通道在解绑编码面与引擎销毁两条路都释放`() {
        val src = source()
        assertEquals(
            "补弧释放/可见性断点：\n${violations(src).joinToString("\n")}",
            emptyList<String>(),
            violations(src)
        )
    }

    @Test
    fun `突变_去掉任一条释放必红`() {
        val src = source()
        assertEquals("良品必须先真的绿", 0, violations(src).size)
        // 突变 1：解绑分支不再释放补弧通道（= 显存滞留缺陷回潮）
        val noDetachRelease = src.replace(
            "            arcMendPass?.release()\n            arcMendPass = null\n            return",
            "            return"
        )
        assertTrue(
            "解绑分支去掉释放后尺子必须报红",
            violations(noDetachRelease).any { it.contains("停录") }
        )
        // 突变 2：releaseGl 不再兜底
        val noTeardownRelease = src.replace(
            "        arcMendPass?.release()\n        arcMendPass = null\n        publishedFrost",
            "        publishedFrost"
        )
        assertTrue(
            "releaseGl 去掉兜底后尺子必须报红",
            violations(noTeardownRelease).any { it.contains("releaseGl") }
        )
        // 突变 3：抹掉 @Volatile（连着它上面那行注解一起抹）
        val noVolatile = src.replace(
            "    @Volatile\n    private var encoderNative",
            "    private var encoderNative"
        )
        assertTrue(
            "抹掉 @Volatile 后尺子必须报红",
            violations(noVolatile).any { it.contains("Volatile") }
        )
    }
}
