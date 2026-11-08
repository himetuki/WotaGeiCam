package com.wotagei.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r 版本号接线的**源码守卫**（build.gradle.kts 与 CI workflow 不是 .kt，走普通文本读取，
 * 不硬套 [com.wotagei.cam.source.KotlinSourceScan] 的 Kotlin 词法遮蔽）。
 *
 * 锁的红线：`-Prbuild` 进 appVersionName 的拼接链（versionName / BuildConfig.VERSION_NAME →
 * 设置页"版本"显示、archivesName → 产物名）与 workflow 的 tag r 段提取/透传（分包与不分包
 * 两遍构建都要带上）。这些线被"顺手清理"删掉时编译照过、CI 照绿（tag 忘带 r 段只会静默退回
 * 纯版本号，谁也发现不了），所以必须在源码层钉死。
 *
 * 「尺子自己能红」：拿同一把尺打删线突变体，防判据空转（见 AudioSourceWiringGuardTest 先例）。
 */
class RbuildWiringGuardTest {

    /** 工作目录不定（Gradle 在 app/，IDE 可能在工程根/仓库根），从 user.dir 往上找；找不到必须抛 */
    private fun repoFile(rel: String): File {
        val tried = ArrayList<String>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var hops = 0
        while (dir != null && hops <= 5) {
            val f = File(dir, rel)
            tried.add(f.path)
            if (f.isFile) return f
            dir = dir.parentFile
            hops++
        }
        throw AssertionError("找不到 $rel（试过：$tried）——守卫不能在没有输入的情况下算通过")
    }

    private val gradleKts: String by lazy {
        repoFile("WotaGeiCamApp/app/build.gradle.kts").readText(Charsets.UTF_8)
    }
    private val workflowYml: String by lazy {
        repoFile(".github/workflows/build-apk.yml").readText(Charsets.UTF_8)
    }

    /** hay 里 needle 的出现次数（split 计数，免正则转义） */
    private fun countOf(hay: String, needle: String): Int = hay.split(needle).size - 1

    /** gradle 侧判据：真身与突变体都吃这一把尺 */
    private fun missingGradleWires(text: String): List<String> = listOf(
        text.contains("findProperty(\"rbuild\")") to
            "-Prbuild 属性读取必须在位（删掉后 CI tag 的 r 段与本地检查点都进不了版本名）",
        text.contains("removePrefix(\"r\")") to
            "必须归一接受 \"r08\" 形态（removePrefix(\"r\")）",
        text.contains("\"-r\$rbuildProp\"") to
            "r 后缀必须拼成 \"-r<r号>\"（形如 -r08）",
        text.contains("val appVersionName = appVersionBase + rSuffix") to
            "appVersionName 必须由 appVersionBase + rSuffix 拼接（versionName/BuildConfig/产物名全靠它连锁）",
        text.contains("versionName = appVersionName") to
            "defaultConfig.versionName 必须取 appVersionName（BuildConfig.VERSION_NAME → 设置页显示链）"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    /** workflow 侧判据 */
    private fun missingWorkflowWires(text: String): List<String> = listOf(
        text.contains("*-r*)") to
            "tag r 段提取的 case 模式（*-r*）必须在位",
        text.contains("\${GITHUB_REF_NAME##*-r}") to
            "必须从 ref_name 截出 r 段（\${GITHUB_REF_NAME##*-r}）",
        text.contains("EXTRA_PROPS=-Prbuild=") to
            "必须构造 EXTRA_PROPS=-Prbuild=<r>（非空才透传，向后兼容旧行为）",
        text.contains("rbuild:") to
            "workflow_dispatch 必须带可选输入 rbuild"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why } +
        if (countOf(text, "\$EXTRA_PROPS") < 2)
            listOf("两遍构建命令都必须引用 \$EXTRA_PROPS（分包/不分包各一处），现在只有 ${countOf(text, "\$EXTRA_PROPS")} 处")
        else emptyList()

    /** flavor 双包命名契约（r05 首推踩坑：cp 重复插 flavor → 双段名 → Publish 定位不到 → 空哈希） */
    private fun missingFlavorNamingWires(text: String): List<String> = listOf(
        text.contains("apk-out/*-full-universal-release-Android.apk") to
            "Publish 必须按单段名定位 full universal 包（apk-out/*-full-universal-release-Android.apk）",
        text.contains("apk-out/*-lite-universal-release-Android.apk") to
            "Publish 必须按单段名定位 lite universal 包（apk-out/*-lite-universal-release-Android.apk）",
        text.contains("universal APK 定位失败") to
            "定位失败必须硬失败（不许空哈希静默进 Release 正文）",
        text.contains("apk-out/*-\$flavor-*-Android.apk") to
            "Verify 计数 glob 必须允许 flavor 与 -Android 之间的 release 段（*-<flavor>-*-Android.apk）",
        // 9 枚口径（2026-11-09 用户裁决）：full4 + lite4 + compat1（旧版无 flavor 更新器的唯一入口）
        text.contains("Stage legacy-compat APK") to
            "compat 兼容包步骤必须在位（内容=lite，名字字母序最前，旧版必选中它）",
        text.contains("expected 9 staged APKs") to
            "staged APK 总数必须校验为 9（full4 + lite4 + compat1）",
        text.contains("-compat-universal-") to
            "compat 包名必须是 -compat-universal-release-Android.apk（字母序 'c' < 'f' < 'l'）",
        text.contains("SHA256-full: %s") to
            "正文必须回填 SHA256-full 专用行（full 变体只认这行）",
        text.contains("\"\${compat_files[@]}\"") to
            "compat 单枚必须单独一组上传（内容=lite；旧版挑第一个含 universal 的资产）",
        text.contains("gh release upload \"\$GITHUB_REF_NAME\" \"\$f\" --clobber") to
            "资产必须逐枚串行上传（避免并发上传把列表搞乱，便于核对 created_at）"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why } +
        if (countOf(text, "\${b}-\$d.apk") > 0)
            listOf("cp 不得再追加 -\$d 到产物名（Gradle 产物已含 flavor，双段名打断 Publish/更新器匹配）")
        else emptyList()

    @Test
    fun `gradle侧rbuild接线在位`() {
        val missing = missingGradleWires(gradleKts)
        assertEquals(
            "build.gradle.kts 的 rbuild 接线断线：\n${missing.joinToString("\n")}",
            emptyList<String>(), missing
        )
    }

    @Test
    fun `workflow侧rbuild提取与透传在位`() {
        val missing = missingWorkflowWires(workflowYml)
        assertEquals(
            ".github/workflows/build-apk.yml 的 rbuild 接线断线：\n${missing.joinToString("\n")}",
            emptyList<String>(), missing
        )
    }

    @Test
    fun `flavor双包命名契约在位`() {
        val missing = missingFlavorNamingWires(workflowYml)
        assertEquals(
            ".github/workflows/build-apk.yml 的 flavor 命名契约断线：\n${missing.joinToString("\n")}",
            emptyList<String>(), missing
        )
    }

    /** 「尺子自己能红」：把接线行从真身里删掉，同一把尺必须报红，否则判据是恒等式测不出退化 */
    @Test
    fun `尺子自己能红_删线突变体必报红`() {
        val gradleMutant = gradleKts.lines()
            .filter { !it.contains("rbuild") && !it.contains("rSuffix") }
            .joinToString("\n")
        assertTrue(
            "删掉 gradle 的 rbuild 接线后尺子必须红（否则判据空转）",
            missingGradleWires(gradleMutant).isNotEmpty()
        )
        val ymlMutant = workflowYml.lines()
            .filter { !it.contains("rbuild") && !it.contains("EXTRA_PROPS") }
            .joinToString("\n")
        assertTrue(
            "删掉 workflow 的 rbuild 提取/透传后尺子必须红（否则判据空转）",
            missingWorkflowWires(ymlMutant).isNotEmpty()
        )
        // flavor 命名契约的突变体：恢复"cp 追加 -$d"与旧的死模式 ls
        val flavorMutant = workflowYml
            .replace("apk-out/*-full-universal-release-Android.apk", "apk-out/*full*universal-release-Android.apk")
            .replace("cp \"\$f\" \"../../apk-out/\"", "cp \"\$f\" \"../../apk-out/\${b}-\$d.apk\"")
        assertTrue(
            "恢复双段名/死模式后尺子必须红（否则 flavor 判据空转）",
            missingFlavorNamingWires(flavorMutant).isNotEmpty()
        )
    }
}
