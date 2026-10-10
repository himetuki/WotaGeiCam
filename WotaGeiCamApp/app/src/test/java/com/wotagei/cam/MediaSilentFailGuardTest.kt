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
}