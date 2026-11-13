package com.wotagei.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 发布 workflow 的源码守卫：沿用现有守卫的文本检查与突变自证，不引入 YAML/联网依赖。
 * 白名单是本次 gh API 核验过的上游 commit；升级或新增 action 时须重新核验并同步更新。
 * 这里只接受当前 workflow 使用的块式 uses/with 写法，不充当通用 YAML 解析器。
 */
class CiActionSupplyChainGuardTest {
    private val verifiedPins = setOf(
        "actions/checkout@11d5960a326750d5838078e36cf38b85af677262", // v4.4.0
        "actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3", // v4.9.1
        "gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a", // v4.4.3
        "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02" // v4.6.2
    )
    private val usesKey = Regex("""^\s*(?:-\s+)?uses:""")
    private val usesLine = Regex("""^( *)(- )?uses: +([^\s]+)$""")

    private fun workflow(name: String): String {
        val relative = ".github/workflows/$name"
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val file = generateSequence(start) { it.parentFile }.take(7)
            .map { File(it, relative) }.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $relative，守卫不能在没有输入时通过")
        return file.readText(Charsets.UTF_8)
    }

    private fun indent(line: String): Int = line.takeWhile { it == ' ' }.length

    /** 真身与突变体共用同一把尺；checkout 的选项只在本步骤的 with 块内查找。 */
    private fun violations(text: String): List<String> {
        val lines = text.lines().map { it.substringBefore('#').trimEnd() }
        val actions = lines.withIndex().filter { usesKey.containsMatchIn(it.value) }
        val errors = mutableListOf<String>()
        if (actions.isEmpty()) errors.add("缺少 action 声明")
        var checkouts = 0
        for ((index, line) in actions) {
            val match = usesLine.matchEntire(line)
            if (match == null) {
                errors.add("第 ${index + 1} 行 uses 必须采用块式固定 commit 写法")
                continue
            }
            val ref = match.groupValues[3]
            if (ref !in verifiedPins) errors.add("未核验的 action 引用：$ref")
            if (!ref.startsWith("actions/checkout@")) continue
            checkouts++
            // - uses: 的键比列表缩进深两格；普通 uses: 的键缩进保持不变。
            val keyIndent = match.groupValues[1].length + if (match.groupValues[2].isEmpty()) 0 else 2
            val tail = lines.drop(index + 1).takeWhile { it.isBlank() || indent(it) >= keyIndent }
            val withLine = " ".repeat(keyIndent) + "with:"
            val withIndex = tail.indices.filter { tail[it] == withLine }.singleOrNull()
            val options = if (withIndex == null) emptyList() else tail.drop(withIndex + 1)
                .takeWhile { it.isBlank() || indent(it) > keyIndent }
            val optionPrefix = " ".repeat(keyIndent + 2) + "persist-credentials:"
            if (options.filter { it.startsWith(optionPrefix) } != listOf("$optionPrefix false")) {
                errors.add("checkout 必须在自己的 with 中显式设置 persist-credentials: false")
            }
        }
        if (checkouts == 0) errors.add("缺少受保护的 checkout")
        return errors
    }

    @Test
    fun `两条发布workflow仅使用已核验commit且不持久化checkout凭据`() {
        for (name in listOf("build-apk.yml", "build-hap.yml")) {
            assertEquals(name, emptyList<String>(), violations(workflow(name)))
        }
    }

    @Test
    fun `尺子自己能红_逐个action退回tag分支短SHA或伪造SHA必失败`() {
        for (name in listOf("build-apk.yml", "build-hap.yml")) {
            val text = workflow(name)
            for (pin in verifiedPins.filter { text.contains(it) }) {
                for (ref in listOf("v4", "main", pin.substringAfter('@').take(12), "0".repeat(40))) {
                    val mutant = text.replace(pin, "${pin.substringBefore('@')}@$ref")
                    assertTrue("$name 的 $pin 退化为 $ref 必须失败", violations(mutant).isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `尺子自己能红_删除启用或注释凭据保护必失败`() {
        for (name in listOf("build-apk.yml", "build-hap.yml")) {
            val text = workflow(name)
            for (replacement in listOf("", "persist-credentials: true", "# persist-credentials: false")) {
                val mutant = text.replace("persist-credentials: false", replacement)
                assertTrue("$name 的凭据保护改为 '$replacement' 必须失败", violations(mutant).isNotEmpty())
            }
        }
    }

    @Test
    fun `邻接步骤的false不能冒充checkout保护`() {
        val checkout = verifiedPins.single { it.startsWith("actions/checkout@") }
        val java = verifiedPins.single { it.startsWith("actions/setup-java@") }
        val text = """
            - uses: $checkout
            - uses: $java
              with:
                persist-credentials: false
        """.trimIndent()
        assertTrue(violations(text).any { it.contains("checkout 必须") })
        assertTrue(violations("# uses: $checkout\n# persist-credentials: false").isNotEmpty())
    }
}
