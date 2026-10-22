package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库**静默失败**的结构守卫（任务 9：S5-S5-05 + S4-M7-02）。
 *
 * 被治的病：媒体库读查询用 `runCatching` 无声兜底、批量删除只数个失败数，两处失败现场
 * 在日志里一条线索都没有 —— 而「列表空」「删除失败」两种事实在界面上完全同形，
 * 鸿蒙/EMUI 容器里 logcat 又看不到（只能靠应用内自持的诊断日志取证），所以三处必须有锚点。
 *
 * 同时钉住两个**不许被顺手改掉**的语义：
 * 1. 读失败仍然返回空/半截列表（不许因为加日志就改成抛异常——那会让整个媒体库崩）；
 * 2. `deniedRes` 只对 SecurityException 提「去开权限」，别的失败原因不得引着用户去开无关权限。
 *
 * 突变自证见本轮提交记录（分别删掉两处 onFailure、删掉逐条 Log.e、把权限位恒置 true）。
 */
class MediaSilentFailGuardTest {

    private fun masked(rel: String) = codeOnly(mainSourceText(rel))

    /** 取函数体（代码位，字符串字面量已被遮蔽）；只适合判"有没有调某个函数"这类代码结构 */
    private fun body(rel: String, fn: String) = flatten(bodyOf(masked(rel), fn))

    /**
     * 取**原始**函数体（字面量不遮蔽）。
     *
     * 判"日志正文里写了哪些字段"必须用它：这些字段全在字符串字面量里，
     * 而遮蔽器会把字面量内容（含 `${` 与 `}` 两个定界符）一律抹成空格 —— 用遮蔽文本判字面量必假。
     * 区间取自遮蔽文本，两者是等长文本，偏移通用（`KotlinSourceScan.lineOf` 同此理）。
     */
    private fun rawBody(rel: String, fn: String): String {
        val r = com.wotagei.cam.source.KotlinSourceScan.regionOf(masked(rel), fn)
        return flatten(mainSourceText(rel).substring(r.start + 1, r.end - 1))
    }

    // ------------------------------------------------------------------ MediaRepo：两处读查询

    @Test
    fun `常规查询失败必须有日志锚点且仍返回列表`() {
        val b = body("media/MediaRepo.kt", "readCursor")
        assertTrue("查询失败必须记日志（否则『列表空』与『查询炸了』无法区分）", b.contains("onFailure"))
        assertTrue("失败必须落 Log.e（异常现场要能取证）", b.contains("Log.e("))
        assertTrue("红线：失败仍返回已读到的列表，不许改成抛异常", b.contains("return out"))
        assertFalse("不许把失败重新抛出去（媒体库整屏会崩）", b.contains("throw "))
    }

    @Test
    fun `回收站查询失败必须有日志锚点且仍返回空列表`() {
        val b = body("media/MediaRepo.kt", "readTrashed")
        assertTrue("回收站查询失败必须记日志", b.contains("onFailure"))
        assertTrue("失败必须落 Log.e", b.contains("Log.e("))
        assertTrue("红线：失败仍返回 out（空列表）", b.contains("return out"))
        assertFalse("不许把失败重新抛出去", b.contains("throw "))
    }

    /** 日志不许是空壳：参数得带进去，否则多条失败分不出是哪次查询炸的。
     *  判的是**字面量内容** ⇒ 用原始文本（遮蔽文本里字面量已被抹成空格）。 */
    @Test
    fun `查询日志必须带现场参数`() {
        val cursor = rawBody("media/MediaRepo.kt", "readCursor")
        assertTrue(
            "常规查询日志要带 uri（否则查不出是哪次查询炸的）",
            cursor.contains("uri=\$contentUri")
        )
        assertTrue("常规查询日志要带 selection", cursor.contains("selection=\$selection"))
        assertTrue(
            "日志要说明失败后按什么返回（空/半截列表），否则读者会误以为补了日志就是修好了",
            cursor.contains("按空/半截列表返回")
        )
        assertTrue(
            "回收站日志要带 API 级别（29 与 30+ 的失败原因完全不同）",
            rawBody("media/MediaRepo.kt", "readTrashed").contains("Build.VERSION.SDK_INT")
        )
    }

    // ------------------------------------------------------------------ MediaActions：逐条删除失败

    @Test
    fun `批量删除失败必须逐条记日志`() {
        val b = rawBody("media/MediaActions.kt", "deleteOneByOne")
        assertTrue("每条删除失败都要记日志（只报一个总数事后无从复盘）", b.contains("Log.e(TAG,"))
        assertTrue("日志要带 uri（定位是哪一条）", b.contains("uri=\${clip.uri}"))
        assertTrue("日志要带异常类名", b.contains("\${err.javaClass.simpleName}"))
        assertTrue("日志要带 message", b.contains("\${err.message}"))
        assertTrue("异常对象本身也要传下去（栈才有取证价值）", b.contains("\${err.message}\", err)"))
    }

    @Test
    fun `只有SecurityException才提开权限`() {
        val del = body("media/MediaActions.kt", "deleteOneByOne")
        assertTrue(
            "权限位只能由 SecurityException 置起（别的异常让用户去开权限是误导）",
            del.contains("err is SecurityException")
        )
        assertTrue(
            "权限位必须只在 SecurityException 分支置起（放在失败计数之后无条件的行上等于恒真）",
            del.contains("if (err is SecurityException) permissionDenied = true")
        )
        assertTrue(
            "收尾文案必须把权限位传给 deniedRes（不传就退回了『一律提权限』的老口径）",
            del.contains("deniedRes(R.string.media_delete_failed, permissionDenied)")
        )
    }

    /** `deniedRes` 是表达式体，`bodyOf` 定位不到它，按声明切片后折叠断言 */
    @Test
    fun `deniedRes只对权限失败给开权限文案`() {
        val raw = flatten(
            mainSourceText("media/MediaActions.kt")
                .substringAfter("private fun deniedRes(")
                .substringBefore("// endregion")
        )
        assertTrue("deniedRes 必须认『这次失败是不是权限问题』这个事实", raw.contains("permissionDenied"))
        assertTrue("非权限失败直接给兜底文案", raw.contains("if (!permissionDenied) fallback"))
        assertTrue(
            "权限失败且用户确实没拿到「所有文件访问」时才给开权限文案",
            raw.contains("WotaStorage.hasAllFilesAccess()") && raw.contains("R.string.media_need_all_files")
        )
    }

    /** 红线：「所有文件访问」这条产品决定不许被这次改动动摇 */
    @Test
    fun `系统确认框仍然不存在且权限判定口径不变`() {
        // 判"代码真的调了系统授权框"必须剥掉 import 行：那些 import 是系统框移除前留下的死引用
        //（PendingIntent / RemoteAction / RecoverableSecurityException，正文已无一处使用），
        // 它们证明不了框回来了，只会让这条守卫恒红。真要抓"框回来了"，抓**调用**即可。
        val src = masked("media/MediaActions.kt")
            .lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")
        assertFalse(
            "不许把系统授权框搬回来（用户定版：只走「所有文件访问」，不弹系统框）",
            src.contains("createTrashRequest(") || src.contains("createDeleteRequest(") ||
                src.contains("startIntentSenderForResult")
        )
        assertFalse(
            "不许再捕 RecoverableSecurityException 去回放系统授权回路",
            src.contains("catch (") && src.contains("RecoverableSecurityException")
        )
        assertTrue("权限判定仍读 hasAllFilesAccess（未换成别的事实来源）", src.contains("WotaStorage.hasAllFilesAccess()"))
    }

    // ------------------------------------------------------------------ MediaActions：回收站直写

    /**
     * 回收站直写失败必须记**位次与条数**（#12）。
     *
     * 被治的病：`writeTrashedOne` 把「抛异常」与「update 返回 0 行」一起收敛成 `false`、
     * 零日志，而 `writeTrashed` 用 `takeWhile` 遇第一个失败就停 —— 一次失败静默截断在
     * `takeWhile` 的边界上，事后**没有任何数字**能区分「用户只选了一条」与「第 3 条写失败、
     * 后面根本没写」。该路径已有回滚，所以这是可观测性缺口、不是坏数据。
     *
     * 三个锚点缺一不可：
     * 1. `trashDirect` 的**汇总锚点**：覆盖「0 行」与「异常」两种（`takeWhile` 保证成功段是前缀，
     *    `done.size` 就是失败位次）；
     * 2. `writeTrashedOne` 的 `onFailure`：只覆盖抛异常那一支（`0 行` 不走 `onFailure`）——
     *    所以 ① 少不得，② 也不得；
     * 3. 回滚返回值比对：回滚失败才是真会坏数据的那种，原来返回值被丢弃、整条不可见。
     */
    @Test
    fun `回收站直写失败必须记位次与条数`() {
        // 汇总锚点挂 trashDirect（块体，bodyOf 可用）
        val direct = rawBody("media/MediaActions.kt", "trashDirect")
        assertTrue("失败必须落 Log.e（只报一个布尔，事后无从复盘）", direct.contains("Log.e(TAG,"))
        assertTrue("日志要带失败位次（否则分不出是第几条断的）", direct.contains("失败位次=\${done.size}"))
        assertTrue("日志要带成功/总数两个数（否则分不出『只选了一条』与『第 3 条就断了』）", direct.contains("\${done.size}/\${clips.size}"))
        assertTrue("日志要指向第一条失败行", direct.contains("uri=\${clips[done.size].uri}"))

        // writeTrashedOne 是表达式体，bodyOf 定位不到，按声明切片
        val one = writeTrashedOneBody()
        assertTrue("异常现场必须留锚点", one.contains("onFailure"))
        assertTrue("异常必须落 Log.e", one.contains("Log.e(TAG,"))
        assertTrue("日志要带异常类名（不同原因药方不同）", one.contains("\${it.javaClass.simpleName}"))
        assertTrue("栈要传下去（只有类名没有栈，取证价值为零）", one.contains("\${it.javaClass.simpleName}\", it)"))
        assertTrue("红线：仍然收敛成 false（媒体库整屏不许因写库异常崩）", one.contains("getOrDefault(false)"))

        // 回滚失败的锚点：返回值原本被丢弃，不比对就整条不可见
        assertTrue("回滚也必须比对返回值", direct.contains("back.size != done.size"))
        assertTrue("回滚失败必须留锚点", direct.contains("回收站直写回滚失败"))
    }

    /**
     * 突变自证：把锚点摘掉或掏空，判据必须真的翻红。
     *
     * 三个突变**各自单飞**、各证一件事（不搞一把摘两处的合并突变——那样任一处判据失守都会被另一处盖住）：
     * - ① 整条删掉 `trashDirect` 的汇总锚点 → 汇总判据翻假、异常判据**不动**。
     *   这正是"只留 `onFailure`"的形态：证明「`update` 返回 0 行」那条路确实靠汇总锚点兜住，
     *   `onFailure` 盖不住它（0 行不是异常，不走 `onFailure`）。
     * - ② 汇总锚点**在**，但把位次字段掏成常量 → 汇总判据翻假。
     *   证明判据读的是位次本身，不是"块体里恰好有个 `Log.e`"这种恒真式。
     * - ③ 摘掉 `onFailure` → 异常判据翻假、汇总判据**不动**（两条锚点互不代偿）。
     *
     * 每个突变都先断言**它真的改动了源码**：不改动就等于什么都没证明（本仓踩过的空操作突变坑）。
     */
    @Test
    fun `突变自证：摘掉回收站锚点判据必红`() {
        val raw = mainSourceText("media/MediaActions.kt")
        assertTrue("对照组：真源码上汇总锚点必须为真", hasDirectAnchor(rawBodyOf(raw, "trashDirect")))
        assertTrue("对照组：真源码上异常锚点必须为真", hasOneAnchor(writeTrashedOneBodyOf(raw)))

        // ① 整行删掉汇总锚点（按行删，不能只删片段：留下半个字符串会让遮蔽器读到下一个引号，
        //    花括号配平随之失真，守卫就会因为"解析炸了"而不是因为"锚点没了"变红——那是假红）
        val noSummary = raw.replace(SUMMARY_LINE, "")
        assertTrue("突变体①必须真的改了源码", noSummary != raw)
        assertFalse("删掉汇总锚点后汇总判据必须翻假", hasDirectAnchor(rawBodyOf(noSummary, "trashDirect")))
        assertTrue("①不得牵连异常锚点", hasOneAnchor(writeTrashedOneBodyOf(noSummary)))

        // ② 锚点在、位次字段被掏成常量
        val noSeq = raw.replace("失败位次=\${done.size}", "失败位次=未知")
        assertTrue("突变体②必须真的改了源码", noSeq != raw)
        assertFalse("位次字段被掏空后汇总判据必须翻假", hasDirectAnchor(rawBodyOf(noSeq, "trashDirect")))

        // ③ 摘掉 onFailure（整段链式调用，连它的花括号一起摘，免得配平失真）
        val noOnFailure = raw.replace(ON_FAILURE_LINE, "")
        assertTrue("突变体③必须真的改了源码", noOnFailure != raw)
        assertFalse("删掉 onFailure 后异常判据必须翻假", hasOneAnchor(writeTrashedOneBodyOf(noOnFailure)))
        assertTrue("③不得牵连汇总锚点", hasDirectAnchor(rawBodyOf(noOnFailure, "trashDirect")))
    }

    /** 汇总锚点那一行（整行删：只删片段会留下半个字符串让遮蔽器配平失真 ⇒ 假红） */
    private val SUMMARY_LINE = Regex(".*Log\\.e\\(TAG, \"回收站直写失败.*\\R")
    private val ON_FAILURE_LINE = Regex(".*\\.onFailure \\{[^\\n]*\\}\\R")

    /** 判据：块体里是否带了「位次 + 条数」的汇总锚点 */
    private fun hasDirectAnchor(body: String): Boolean =
        body.contains("Log.e(TAG,") && body.contains("失败位次=\${done.size}") &&
            body.contains("\${done.size}/\${clips.size}") && body.contains("uri=\${clips[done.size].uri}")

    /** 判据：表达式体切片里是否带了异常锚点（含栈） */
    private fun hasOneAnchor(body: String): Boolean =
        body.contains("onFailure") && body.contains("Log.e(TAG,") && body.contains("\${it.javaClass.simpleName}\", it")

    /** `writeTrashedOne` 是表达式体（`bodyOf` 定位不到），按声明切片到下一个声明 */
    private fun writeTrashedOneBody(): String = writeTrashedOneBodyOf(mainSourceText("media/MediaActions.kt"))

    private fun writeTrashedOneBodyOf(src: String): String {
        val anchor = "private fun writeTrashedOne("
        if (!src.contains(anchor)) throw AssertionError("找不到 $anchor —— 函数被改名/挪走，本守卫失去输入")
        return flatten(src.substringAfter(anchor).substringBefore("private suspend fun recordTrashRows("))
    }

    /** [rawBody] 的泛化版：可对**任意**源码文本取函数体（突变自证要拿突变体喂同一把尺） */
    private fun rawBodyOf(src: String, fn: String): String {
        val r = com.wotagei.cam.source.KotlinSourceScan.regionOf(codeOnly(src), fn)
        return flatten(src.substring(r.start + 1, r.end - 1))
    }
}