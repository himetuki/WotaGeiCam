package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.allBodies
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.enclosingOf
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MrRecorder] 换段「实例换代 + 输入面归属」的**源码结构守卫**（2026-10-08 真机根因修复）。
 *
 * 真机缺陷（WIKO GAR-AN60 / Android 11、32MiB 测试阈值 + 50Mbps、两轮复现）：第一段与第一次
 * 换段都正常，**第二次换段之后彻底停摆**——旧段（33.3MB）已封段 commit、第 2 个新段连 pending
 * 都没留下、UI 计时冻结在 00:14 且无任何提示。第 2 次换段确实跑过（段 1 的封段提交就发生在它
 * 里面），失败点在其 `createPending` 之后：`createPending` / `OutputTarget.open` / `applyConfig`
 * / `mr.start()` 四者之一。四者的共同前提都是「同一个 MediaRecorder 实例的第 3 次
 * reset→configure→prepare→start」，所以修法是把「实例复用」整条拿掉：换段 stop 之后**销毁旧实例**，
 * 由 `ensureRecorder` 新建；`inputSurface`（persistent 面）属本类、**跨实例复用**，由 `applyConfig`
 * 重挂到新实例上（出货应用 StaCam 的 `x5/b.e()` 同款：stop → release → 置 null → 500ms →
 * 新实例 → 重挂输入面 → prepare → start）。
 *
 * 这里钉四条红线，谁把实例复用接回去、谁把面改成每实例一份、谁把交接漏掉，都当场红：
 * 1. `rotateSegment` 必须 `stopEngine()` → `releaseEngine()` → … → `applyConfig(ensureRecorder()…)`
 *    → `mr?.start()` 的顺序（偏移序，不只是"文本里出现过"）；
 * 2. `MediaRecorder()` 全类只在 `ensureRecorder` 里构造一次（单一起源）；
 * 3. persistent 输入面只在 `applyInputSurface` 建、只在会话级 `release` 释放，换段路径一个字都不许碰；
 * 4. 换段面交接必须走纯函数 [rotateSurfaceHandoffNeeded]、包 `runCatching`、且早于 `mr?.start()`。
 */
class MrRotateInstanceGuardTest {

    private val src by lazy { codeOnly(mainSourceText("record/MrRecorder.kt")) }

    @Test
    fun `换段必须先销毁旧实例再让 ensureRecorder 新建`() {
        val rot = bodyOf(src, "rotateSegment")
        val stop = rot.indexOf("stopEngine()")
        val release = rot.indexOf("releaseEngine()")
        val pending = rot.indexOf("createPending(")
        val prepare = rot.indexOf("applyConfig(ensureRecorder()")
        val start = rot.indexOf("mr?.start()")
        assertTrue("锚点丢失：rotateSegment 没截到 stopEngine()", stop >= 0)
        assertTrue("锚点丢失：rotateSegment 没截到 releaseEngine()（销毁旧实例的调用）", release >= 0)
        assertTrue("锚点丢失：rotateSegment 没截到 createPending(", pending >= 0)
        assertTrue("换段必须用 ensureRecorder() 走新建路径（不许先取实例再复用）", prepare >= 0)
        assertTrue("锚点丢失：rotateSegment 没截到 mr?.start()", start >= 0)
        assertTrue("必须先 stop 落定（文件完整）再销毁实例", stop < release)
        assertTrue("销毁旧实例必须早于建新 pending（新实例由后面的 applyConfig 创建）", release < pending)
        assertTrue("销毁旧实例必须早于 re-prepare（否则 applyConfig 拿到的是被释放的旧实例）", release < prepare)
        assertTrue("re-prepare 必须早于 start（顺序反了 = 拿旧编码器启动）", prepare < start)
    }

    /**
     * 单一起源红线：`MediaRecorder()` 只许出现在 `ensureRecorder` 的声明区（它是表达式体，
     * 没有花括号体可摘，故用"命中点必须落在该声明之后的一小段窗口内"来钉）。
     * 换段路径若自己 `MediaRecorder()`，`ensureRecorder` 的 `mr ?: …` 会永远命中旧字段、
     * 新实例无人接线（监听器/面都没挂），"换实例"变成"漏一次 prepare"。
     */
    @Test
    fun `MediaRecorder 只能在 ensureRecorder 里构造`() {
        val hits = occurrences(src, "MediaRecorder()")
        assertTrue("MediaRecorder() 必须恰好出现 1 次（唯一创建点），实际 ${hits.size} 次", hits.size == 1)
        val decl = src.indexOf("private fun ensureRecorder()")
        assertTrue("锚点丢失：找不到 ensureRecorder 的声明", decl >= 0)
        assertTrue(
            "MediaRecorder() 必须落在 ensureRecorder 的声明区（偏移 ${hits[0]}，声明在 $decl）",
            hits[0] in decl until (decl + 200)
        )
    }

    /**
     * 面归属红线：persistent 输入面（GPU 路的编码器输入）**跨实例复用**——
     * 建立只许在 `applyInputSurface` 里一处，释放只许在会话级 `release` 里一处，`rotateSegment`
     * 体内一个字都不许出现（换段重建面会让 GL 还画在旧面上、换段释放面会让新实例无面可挂，
     * 两者都直接导致"下一段无帧"）。
     */
    @Test
    fun `换段不得重建也不得释放 persistent 输入面`() {
        val bodies = allBodies(src)
        val created = occurrences(src, "createPersistentInputSurface")
        assertTrue("persistent 面必须恰好建 1 次（跨实例复用），实际 ${created.size} 次", created.size == 1)
        val creator = enclosingOf(bodies, created[0])
        assertTrue("persistent 面只许在 applyInputSurface 里建（实际在 $creator）", creator == "applyInputSurface")

        val released = occurrences(src, "inputSurface?.release()")
        assertTrue("persistent 面必须恰好释放 1 次（会话级），实际 ${released.size} 次", released.size == 1)
        val releaser = enclosingOf(bodies, released[0])
        assertTrue("persistent 面只许在会话级 release() 里释放（实际在 $releaser）", releaser == "release")

        val rot = bodyOf(src, "rotateSegment")
        assertFalse("换段路径不许触碰 inputSurface（重建/释放都会让下一段无帧）", rot.contains("inputSurface"))
    }

    /**
     * 面交接红线：换段后「对外编码面换了对象」必须让接线方重挂（DIRECT 换相机会话目标、
     * GPU 退回路换 GL 绑定），否则帧进不去、新段静默零帧。判定走纯函数
     * [rotateSurfaceHandoffNeeded]（本体与身份用例在 `RecordHealthTest`），且：
     * - 必须由该判定把门（不许无条件回调：默认档 GPU 面跨实例不变，白重挂有代价）；
     * - 必须包 `runCatching`（接线方抛异常不许把换段折断，与 CodecRecorder 的重挂同口径）；
     * - 必须早于 `mr?.start()`（与首段"先挂牌再 start"同口径）。
     */
    @Test
    fun `换段面交接必须走纯函数且先于 start`() {
        val rot = bodyOf(src, "rotateSegment")
        val judge = rot.indexOf("if (rotateSurfaceHandoffNeeded(")
        val hook = rot.indexOf("onInputSurfaceRecreated?.invoke(")
        val guard = rot.indexOf("runCatching")
        val start = rot.indexOf("mr?.start()")
        assertTrue("换段必须用纯函数 rotateSurfaceHandoffNeeded 把门（判定不许内联、也不许无条件回调）", judge >= 0)
        assertTrue("换段必须调接线方钩子 onInputSurfaceRecreated?.invoke(", hook >= 0)
        assertTrue("钩子调用必须包 runCatching（接线方抛异常不许折断换段）", guard >= 0)
        assertTrue("锚点丢失：没截到 mr?.start()", start >= 0)
        assertTrue("判定必须早于钩子", judge < hook)
        assertTrue("钩子必须在 runCatching 之内（先判后包）", guard < hook)
        assertTrue("面交接必须早于 start（start 之后挂面 = 头几帧落进无人消费的旧面）", hook < start)
    }

    /**
     * 口径写明红线：
     * - 类注必须写清换段的"换代"语义（谁把类注改回"re-prepare 复用实例"即与实现分叉）；
     * - `ensureRecorder` 的线程契约（MediaRecorder 回调 Looper 跟随创建线程）不许删：换段在新实例上
     *   重建，若有人把创建挪出控制线程，802/801 回调会换线程，单线程口径当场失效。
     */
    @Test
    fun `换段换代口径与实例线程契约必须在源码里写明`() {
        val text = mainSourceText("record/MrRecorder.kt")
        assertTrue("类注必须写明换段销毁旧实例（换代）的口径", text.contains("销毁旧实例"))
        assertTrue("ensureRecorder 必须保留单一起源的写法 mr ?: MediaRecorder()", src.contains("mr ?: MediaRecorder()"))
        // KDoc 在遮蔽文本里是空格，找注释必须用**原文**（text）而不是 codeOnly 结果（src）
        val decl = text.indexOf("private fun ensureRecorder()")
        assertTrue("锚点丢失：原文里找不到 ensureRecorder 的声明", decl >= 0)
        val head = text.substring((decl - 1_500).coerceAtLeast(0), decl)
        assertTrue("ensureRecorder 的 KDoc 必须写明回调 Looper 跟随创建线程（换段重建也在控制线程）", head.contains("Looper"))
    }
}
