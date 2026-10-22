package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「配置中的会话回调不许复活已停掉的预览」这条语义的源码级守卫（迭代 9 · 批次 I）。
 *
 * 缺陷本体：`createCaptureSession` 异步配置，回调晚于 `stopPreview` 到达时 `closeSessionQuietly`
 * 手里 session 字段还是 null、无会话可关；旧版 onConfigured 只查 `gen != generation`（stopPreview
 * 不动 generation），回调照常把新 session 赋回字段并重发 repeating ⇒ 后台预览复活推流。
 * 修复：`sessionAttempt` 代数——每次 create 前自增并捕获，`closeSessionQuietly` 自增作废在途回调。
 *
 * Camera2Engine 是 Camera2 框架封装类，JVM 里没有可跑的行为测试，按工程惯例落源码级守卫。
 */
class SessionAttemptGuardTest {

    private val src by lazy { KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt") }
    private val masked by lazy { KotlinSourceScan.codeOnly(src) }

    @Test
    fun `onConfigured 必须校验会话配置代数`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "onConfigured"))
        // 正向锚点：先证明切到的是 onConfigured 真身
        assertTrue("锚点丢失：没截到 session 赋值", body.contains("session = configured"))
        assertTrue("锚点丢失：没截到代数守卫", body.contains("gen != generation"))
        // 修复本体：attempt 过期的回调必须当场 close 作废
        assertTrue(
            "onConfigured 缺少 attempt != sessionAttempt 检查——stopPreview 之后到达的配置回调会把预览在后台复活",
            body.contains("attempt != sessionAttempt")
        )
        assertTrue("过期的 configured 必须先 close 再返回", body.contains("configured.close()"))
    }

    @Test
    fun `onConfigureFailed 同样要校验会话配置代数`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "onConfigureFailed"))
        assertTrue("锚点丢失：没截到 close", body.contains("failed.close()"))
        assertTrue(
            "onConfigureFailed 缺少 attempt != sessionAttempt 检查——过期回调会把失败状态写进新会话的账",
            body.contains("attempt != sessionAttempt")
        )
    }

    @Test
    fun `closeSessionQuietly 必须先作废在途配置再关会话`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "closeSessionQuietly"))
        assertTrue("锚点丢失：没截到会话关闭", body.contains("runCatching { hs?.close() }"))
        val invalidate = body.indexOf("sessionAttempt++")
        val close = body.indexOf("hs?.close()")
        assertTrue(
            "closeSessionQuietly 缺少 sessionAttempt++——在途的 onConfigured 没人作废",
            invalidate >= 0
        )
        assertTrue(
            "sessionAttempt++ 必须在关会话之前（invalidate=$invalidate close=$close）：先作废才有『此后到达的回调一律过期』的口径",
            invalidate < close
        )
    }

    @Test
    fun `ensureSession 发起配置时必须捕获新的代数`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "ensureSession"))
        assertTrue(
            "ensureSession 缺少 ++sessionAttempt——每次 createCaptureSession 都要有一枚新代数可认",
            body.contains("++sessionAttempt")
        )
        assertTrue("锚点丢失：没截到 sessionCallback 调用", body.contains("sessionCallback(highSpeed, targets"))
    }
}
