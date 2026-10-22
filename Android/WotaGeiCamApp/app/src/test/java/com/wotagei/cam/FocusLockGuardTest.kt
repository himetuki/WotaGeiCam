package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对焦锁定（用户反馈第 2 条）的**源码接线守卫**：Compose 手势与弹窗点击在 JVM 单测里跑不到，
 * 「双击主入口真的接了锁定」「锁定期被挡的三条路都不静默」「起录重建后锁还在」「能力缺失不摆假开关」
 * 这些接线全部用 [KotlinSourceScan] 钉住。
 *
 * 拍板口径（docs/plan/21 §八）：主入口＝双击取景画面锁定；长按＝解锁+该点重焦+切 AE；
 * 副控制＝对焦胶囊「锁定对焦/解除锁定」条目；锁定期轻触与双击同款（不重焦、首次提示）；
 * R3a 能力不含 AUTO 不许假开关；R3b 锁定期按「中心点按对焦」给同款提示、不重焦。
 * 判据全部写成吃 `masked: String` 的纯函数，真身与合成突变体同吃一把尺（每条配「尺子自己能红」）。
 */
class FocusLockGuardTest {

    private val maskedEngine by lazy { codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt")) }
    private val maskedParams by lazy { codeOnly(KotlinSourceScan.mainSourceText("core/WotaParams.kt")) }
    private val maskedScreen by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")) }
    private val maskedPills by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")) }
    private val flatScreen by lazy { flatten(maskedScreen) }
    private val flatPills by lazy { flatten(maskedPills) }

    // ------------------------------------------------------------------ 文本定位小工具

    /** 从遮蔽文本的开 `{` 处配对出整枚 lambda 体（ParamsPersistWiringGuardTest 同款） */
    private fun lambdaBody(text: String, openBraceAt: Int): String? {
        var depth = 1
        for (i in openBraceAt + 1 until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openBraceAt + 1, i)
                }
            }
        }
        return null
    }

    /** 锚串之后第一枚 lambda 的体；锚找不到返回 null（守卫失去输入，不许算通过） */
    private fun bodyAfterAnchor(text: String, anchor: String): String? {
        val at = text.indexOf(anchor)
        if (at < 0) return null
        val brace = text.indexOf('{', at)
        if (brace < 0) return null
        return lambdaBody(text, brace)
    }

    /** 取某个调用点的完整实参文本（锚串**不含**调用左括号；遮蔽后圆括号配对，找不到返回 null） */
    private fun callArgs(text: String, anchor: String): String? {
        val at = text.indexOf(anchor)
        if (at < 0) return null
        val open = text.indexOf('(', at + anchor.length)
        if (open < 0) return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 判据（纯函数）

    /** 引擎侧锁定链：订阅/早退/停预览/复锁/超时守卫/AUTO 能力守卫；空 = 完整 */
    private fun engineWiringIssues(masked: String): List<String> {
        val issues = ArrayList<String>()
        fun body(fn: String): String? = runCatching { bodyOf(masked, fn) }.getOrNull()
        val attach = body("attach")
        if (attach == null || occurrences(attach, "focusLocked").isEmpty()) {
            issues.add("attach 没订阅 focusLocked——锁定事件没人回执，胶囊/双击变假开关")
        } else {
            if (occurrences(attach, "engageFocusLock").isEmpty() || occurrences(attach, "releaseFocusLock").isEmpty()) {
                issues.add("attach 的锁定订阅必须双分支（锁定→engage / 解除→release）")
            }
        }
        val tap = body("tapToFocus")
        if (tap == null || flatten(tap).contains("if (params.focusLocked.value) return@post").not()) {
            issues.add("tapToFocus 缺 focusLocked 早退——锁定期点按仍会重新对焦（防误触失守）")
        }
        val stop = body("stopPreview")
        if (stop == null || occurrences(stop, "focusLocked").isEmpty() || occurrences(stop, "restoreTapFocusNow").isEmpty()) {
            issues.add("stopPreview 必须『未锁才恢复』：restoreTapFocusNow 与 focusLocked 同框（锁要跨 ON_PAUSE 存活）")
        }
        val conf = body("onConfigured")
        if (conf == null || occurrences(conf, "engageFocusLock").isEmpty()) {
            issues.add("onConfigured 缺会话重建复锁——起录/停录/换段后锁静默失效")
        }
        val sched = body("scheduleTapRestore")
        if (sched == null || occurrences(sched, "focusLocked").isEmpty()) {
            issues.add("scheduleTapRestore 缺守卫——锁定在 4.5s 窗口内开启会被超时恢复拉回连续模式")
        }
        val engage = body("engageFocusLock")
        if (engage == null || !flatten(engage).contains("afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)")) {
            issues.add("engageFocusLock 缺 R3a 守卫——能力不含 AUTO 的镜头不许假装能锁")
        }
        if (engage != null && occurrences(engage, "scheduleTapRestore").isNotEmpty()) {
            issues.add("engageFocusLock 不许排超时恢复（锁会被自己 4.5s 后解开）")
        }
        // 两个调用点各有唯一形态：bus 订阅（attach）与会话重建复锁（onConfigured）
        if (occurrences(masked, "if (locked) engageFocusLock").isEmpty()) {
            issues.add("缺 bus 订阅调用点（if (locked) engageFocusLock）")
        }
        if (occurrences(masked, "if (params.focusLocked.value) engageFocusLock").isEmpty()) {
            issues.add("缺会话重建复锁调用点（if (params.focusLocked.value) engageFocusLock）")
        }
        if (occurrences(masked, "fun engageFocusLock").isEmpty()) {
            issues.add("engageFocusLock 声明丢了")
        }
        return issues
    }

    /** 总线侧：清锁唯一落点在 applyAbility，且锁态与锚点一起清；空 = 完整 */
    private fun paramsIssues(masked: String): List<String> {
        val body = runCatching { bodyOf(masked, "applyAbility") }.getOrNull()
            ?: return listOf("定位不到 applyAbility 的体——清锁唯一落点丢了")
        val issues = ArrayList<String>()
        if (occurrences(body, "focusLocked").isEmpty()) {
            issues.add("applyAbility 缺清锁（Q4：相机整套重开必须解锁）")
        }
        if (!body.contains("focusLocked.value = false")) {
            issues.add("applyAbility 没把锁态置回 false")
        }
        if (!body.contains("focusLockPoint.value = null")) {
            issues.add("applyAbility 没清锁定锚点（activeArray 换了，旧坐标语义失效）")
        }
        return issues
    }

    /** UI 侧：双击主入口接线 + 轻触/长按的锁定分支 + 锁定入口与框的数据源；空 = 完整 */
    private fun uiWiringIssues(masked: String, flat: String): List<String> {
        val issues = ArrayList<String>()
        // ① 手势定义层：tapFocus 必须收 onDoubleTap 并接进 detectTapGestures
        if (!flat.contains("fun Modifier.tapFocus(") || !flat.contains("onDoubleTap = { offset -> onDoubleTap(")) {
            issues.add("tapFocus 手势层没接 onDoubleTap——双击入口（R1 主入口）没接手势")
        }
        // ② 触摸层传入三路回调
        val args = callArgs(flat, "matchParentSize() .tapFocus")
        if (args == null) {
            issues.add("定位不到触摸层的 .tapFocus(...) 调用点")
        } else {
            for (h in listOf("onTap = {", "onDoubleTap = {", "onLongPress = {")) {
                if (!args.contains(h)) issues.add("触摸层缺 $h——三路手势有一路没接")
            }
            val dbl = bodyAfterAnchor(args, "onDoubleTap = {")
            if (dbl == null ||
                !dbl.contains("lockFocusAt(") ||
                occurrences(dbl, "showLockedTapTip()").isEmpty()
            ) {
                issues.add("onDoubleTap 处理体必须双分支：未锁→lockFocusAt（该点锁定）/ 已锁→showLockedTapTip（不重焦）")
            }
            val tap = bodyAfterAnchor(args, "onTap = {")
            if (tap == null ||
                !tap.contains("focusTapAt(") ||
                occurrences(tap, "showLockedTapTip()").isEmpty()
            ) {
                issues.add("onTap 处理体必须双分支：未锁→focusTapAt（原语义）/ 已锁→showLockedTapTip（不重焦）")
            }
            val lp = bodyAfterAnchor(args, "onLongPress = {")
            if (lp == null ||
                occurrences(lp, "params.focusLocked.value").isEmpty() ||
                !lp.contains("focusTapAt(") ||
                occurrences(lp, "persist.flush()").isEmpty()
            ) {
                issues.add("onLongPress 必须：锁定期先解锁 + 该点重焦（focusTapAt）+ 保留 H3 的 persist.flush()")
            }
        }
        // ③ 锁定动作本体：先写锚点再置锁定态（引擎按总线事件读到的要是新锚点）+ 双入口共用的可用性闸
        val lock = bodyAfterAnchor(masked, "val lockFocusAt")
        if (lock == null) {
            issues.add("定位不到 lockFocusAt——双击/胶囊共用的锁定动作丢了")
        } else {
            val anchorAt = lock.indexOf("focusLockPoint.value")
            val lockedAt = lock.indexOf("focusLocked.value = true")
            if (anchorAt < 0 || lockedAt < 0 || anchorAt > lockedAt) {
                issues.add("lockFocusAt 必须『先写 focusLockPoint 再置 focusLocked=true』（锚点先于锁定态）")
            }
            // R3a 的入口侧闸：能力不含 AUTO / 手动档都只给说明、不置锁定态（双击路径据此不摆假开关）
            if (!lock.contains("CONTROL_AF_MODE_AUTO") || !lock.contains("AfMode.MANUAL")) {
                issues.add("lockFocusAt 缺可用性闸（R3a：能力不含 AUTO / 手动对焦档都锁不了；双击入口同吃这道闸）")
            }
            if (!lock.contains("blockedTip")) {
                issues.add("lockFocusAt 没走可用性闸——不可用设备上双击会置出假锁定态")
            }
        }
        // ④ R3b：锁定期按「中心点按对焦」不静默（同款提示、不重焦）
        val center = bodyAfterAnchor(masked, "onFocusCenter = {")
        if (center == null ||
            occurrences(center, "focusLocked").isEmpty() ||
            occurrences(center, "showLockedTapTip()").isEmpty()
        ) {
            issues.add("onFocusCenter 缺锁态分支——锁定期按中心对焦会静默（R3b 要求同款提示、不重焦）")
        }
        // ⑤ 对焦框：锁定期数据源切到锚点 + locked 形参
        val boxArgs = callArgs(flat, "FocusBox")
        if (boxArgs == null || !boxArgs.contains("focusLockPoint") || !boxArgs.contains("locked = focusLocked")) {
            issues.add("FocusBox 调用点必须传锁定锚点数据源与 locked 形参（Q8：框常显 + 小锁标）")
        }
        return issues
    }

    /** 胶囊侧：锁定条目（副控制）+ 能力禁用（R3a）+ 切换 AF 模式即解锁；空 = 完整 */
    private fun uiLockEntryIssues(masked: String, flat: String): List<String> {
        val issues = ArrayList<String>()
        if (!flat.contains("focusLocked: Boolean") || !flat.contains("onToggleFocusLock: () -> Unit")) {
            issues.add("PillHost 的 focusLocked/onToggleFocusLock 必须是无默认值必传形参（漏挂要编译不过）")
        }
        val focusArgs = callArgs(flat, "PillKey.FOCUS -> FocusPill")
        if (focusArgs == null ||
            !focusArgs.contains("focusLocked = focusLocked") ||
            !focusArgs.contains("onToggleFocusLock = onToggleFocusLock")
        ) {
            issues.add("FOCUS 分支没把锁定态与回执透传给 FocusPill")
        }
        val body = runCatching { bodyOf(masked, "FocusPill") }.getOrNull()
        if (body == null) {
            issues.add("定位不到 FocusPill——锁定条目宿主丢了")
            return issues
        }
        if (occurrences(body, "CONTROL_AF_MODE_AUTO").isEmpty()) {
            issues.add("FocusPill 缺 R3a 禁用判据（afModes 不含 AUTO 时锁定条目必须灰显）")
        }
        for (res in listOf(
            "R.string.cam_focus_lock",
            "R.string.cam_focus_unlock",
            "R.string.cam_focus_manual_locked",
            "R.string.cam_focus_lock_unsupported"
        )) {
            if (!body.contains(res)) issues.add("FocusPill 缺文案 $res（锁定/解除/手动档说明/不支持说明）")
        }
        if (occurrences(body, "onToggleFocusLock()").isEmpty()) {
            issues.add("FocusPill 的锁定条目没回执 onToggleFocusLock——点了没反应")
        }
        if (!body.contains("focusLocked.value = false")) {
            issues.add("切换 AF 模式必须先解锁（解除路径之三：模式选择让位）")
        }
        return issues
    }

    /** `focusLocked`/`focusLockPoint` 的整仓白名单：只许住在这四个文件的既定集合里；空 = 合规 */
    private fun whitelistIssues(sources: Map<String, String>, field: String, mustContain: Set<String>): List<String> {
        val issues = ArrayList<String>()
        val hits = sources.filterValues { it.contains(field) }.keys
        val extra = hits - ALLOWED_FILES
        if (extra.isNotEmpty()) {
            issues.add("$field 出现在白名单外的文件（不许顺手持久化/散写）：$extra")
        }
        val missing = mustContain - hits
        if (missing.isNotEmpty()) {
            issues.add("$field 在应含文件里缺失（接线断了）：$missing")
        }
        return issues
    }

    // ------------------------------------------------------------------ 真身断言

    @Test
    fun `引擎锁定链接线完整`() {
        assertEquals("引擎接线缺件：", emptyList<String>(), engineWiringIssues(maskedEngine))
    }

    @Test
    fun `总线清锁唯一落点在applyAbility`() {
        assertEquals("总线清锁缺件：", emptyList<String>(), paramsIssues(maskedParams))
    }

    @Test
    fun `UI 双击主入口与锁定期交互接线完整`() {
        assertEquals("UI 接线缺件：", emptyList<String>(), uiWiringIssues(maskedScreen, flatScreen))
    }

    @Test
    fun `胶囊锁定条目与能力禁用接线完整`() {
        assertEquals("胶囊接线缺件：", emptyList<String>(), uiLockEntryIssues(maskedPills, flatPills))
    }

    @Test
    fun `锁态两字段住在前端四个文件的既定集合`() {
        val sources = KotlinSourceScan.walkMainSources()
        assertEquals(
            "focusLocked 白名单：",
            emptyList<String>(),
            whitelistIssues(sources, "focusLocked", ALLOWED_FILES)
        )
        assertEquals(
            "focusLockPoint 白名单：",
            emptyList<String>(),
            whitelistIssues(
                sources, "focusLockPoint",
                setOf("core/WotaParams.kt", "camera/Camera2Engine.kt", "ui/CameraScreen.kt")
            )
        )
    }

    // ------------------------------------------------------------------ 尺子自己能红

    @Test
    fun `尺子自己能红_拆掉引擎任一接线必报红`() {
        assertTrue("真身必须绿", engineWiringIssues(maskedEngine).isEmpty())
        val noAttach = maskedEngine.replace("if (locked) engageFocusLock", "if (locked) Unit")
        assertTrue("拆掉 attach 订阅的锁定分支后判据必须红", engineWiringIssues(noAttach).isNotEmpty())
        val noTap = maskedEngine.replaceFirst("if (params.focusLocked.value) return@post", "Unit")
        assertTrue("拆掉 tapToFocus 早退后判据必须红", engineWiringIssues(noTap).isNotEmpty())
        val noRelock = maskedEngine.replace("if (params.focusLocked.value) engageFocusLock", "if (false) engageFocusLock")
        assertTrue("拆掉会话重建复锁后判据必须红", engineWiringIssues(noRelock).isNotEmpty())
        val noGuard = maskedEngine.replace(
            "!slotAbility.afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)",
            "false"
        )
        assertTrue("拆掉 AUTO 能力守卫后判据必须红", engineWiringIssues(noGuard).isNotEmpty())
        val noStopGuard = maskedEngine.replace(
            "if (!params.focusLocked.value) restoreTapFocusNow",
            "if (true) restoreTapFocusNow"
        )
        assertTrue("拆掉 stopPreview 的未锁才恢复后判据必须红", engineWiringIssues(noStopGuard).isNotEmpty())
        val noSched = maskedEngine.replaceFirst("if (params.focusLocked.value) return\n", "val unused = 0\n")
        assertTrue("拆掉 scheduleTapRestore 守卫后判据必须红", engineWiringIssues(noSched).isNotEmpty())
    }

    @Test
    fun `尺子自己能红_拆掉总线清锁必报红`() {
        assertTrue("真身必须绿", paramsIssues(maskedParams).isEmpty())
        val mutant = maskedParams.replace("focusLocked.value = false", "Unit")
        assertTrue("拆掉 applyAbility 里的清锁后判据必须红", paramsIssues(mutant).isNotEmpty())
    }

    @Test
    fun `尺子自己能红_拆掉双击或锁定期分支必报红`() {
        assertTrue("真身必须绿", uiWiringIssues(maskedScreen, flatScreen).isEmpty())
        val noDoubleTap = maskedScreen.replace("onDoubleTap", "noDoubleTap")
        assertTrue("拆掉双击接线后判据必须红", uiWiringIssues(noDoubleTap, flatten(noDoubleTap)).isNotEmpty())
        val silentCenter = maskedScreen.replace("params.focusLocked.value -> showLockedTapTip()", "Unused42 -> Unit42")
        assertTrue("拆掉中心对焦的锁态分支后判据必须红", uiWiringIssues(silentCenter, flatten(silentCenter)).isNotEmpty())
        val anchorAfter = maskedScreen.replace(
            "params.focusLockPoint.value = point\n            params.focusLocked.value = true",
            "params.focusLocked.value = true\n            params.focusLockPoint.value = point"
        )
        assertTrue("把『先锚点后锁定』倒过来后判据必须红", uiWiringIssues(anchorAfter, flatten(anchorAfter)).isNotEmpty())
        val noGate = maskedScreen.replace(
            "slotNow?.ability?.afModes?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == false",
            "false"
        )
        assertTrue("拆掉可用性闸的能力判据后判据必须红", uiWiringIssues(noGate, flatten(noGate)).isNotEmpty())
    }

    @Test
    fun `尺子自己能红_拆掉胶囊锁定条目或白名单越界必报红`() {
        assertTrue("真身必须绿", uiLockEntryIssues(maskedPills, flatPills).isEmpty())
        val noArgs = maskedPills.replace("focusLocked = focusLocked", "focusLocked = false")
        assertTrue("FOCUS 分支不透传锁态后判据必须红", uiLockEntryIssues(noArgs, flatten(noArgs)).isNotEmpty())
        val noUnlock = maskedPills.replace("if (params.focusLocked.value) params.focusLocked.value = false", "Unit")
        assertTrue("拆掉『切换模式即解锁』后判据必须红", uiLockEntryIssues(noUnlock, flatten(noUnlock)).isNotEmpty())
        // 白名单：多一枚文件 / 少一枚应含文件，两个方向都要红
        val sources = KotlinSourceScan.walkMainSources()
        val extra = sources + ("ui/HudLayer.kt" to "val leak = focusLocked")
        assertTrue("白名单外的文件出现 focusLocked 必须红", whitelistIssues(extra, "focusLocked", ALLOWED_FILES).isNotEmpty())
        val missing = sources - "ui/CameraPills.kt"
        assertTrue("应含文件缺失 focusLocked 必须红", whitelistIssues(missing, "focusLocked", ALLOWED_FILES).isNotEmpty())
    }

    private companion object {
        /** 锁态允许出现的四个文件（Q4 单点规则 + 双层守卫的落点；见 docs/plan/21 §八） */
        val ALLOWED_FILES = setOf(
            "core/WotaParams.kt",
            "camera/Camera2Engine.kt",
            "ui/CameraScreen.kt",
            "ui/CameraPills.kt"
        )
    }
}
