package com.wotagei.cam.source

import java.io.File

/**
 * 源码级守卫用的**最小 Kotlin 词法遮蔽 + 函数体定位**工具（只在测试里用，不参与生产）。
 *
 * # 为什么守卫不能直接 `fileText.contains("frost")`
 * 整份 [com.wotagei.cam.camera.GlRenderEngine] 一定含霜符号（`drawFrostPass`、`publishedFrost` 就在同一文件里），
 * 对整文件做"不含"判定要么永远红、要么什么都测不到。要判的是**某一个函数体内部**，所以必须先把函数体
 * 边界解出来，再在边界内判定。
 *
 * # 为什么不自写花括号计数器（AGENTS.md 的老教训：结构判定交解析器，不要自写计数）
 * 朴素的 `{`/`}` 计数会被这几类东西带偏：
 * - 注释里出现的括号（本项目的中文 KDoc 大量写 `[a, b)`、`（...）`）；
 * - 字符串字面量里的括号（`"UV 要 4 个 float，实际 ${out.size}"` 既有收尾 `}` 又有插值里的调用括号）；
 * - 字符字面量 `'{'`。
 * 一旦"函数体"截到一半，后面所有判定就建立在半份文本上——红不红全看运气，这正是本项目最怕的假绿。
 * 所以这里先做一遍**词法遮蔽**：把注释与字符串/字符字面量的内容替换成空格（长度与换行位置逐字不变，
 * 因此遮蔽文本上拿到的偏移能直接回原文算行号），只把 `${...}` 插值里的**真代码**保留；
 * 之后的括号配对只作用在"确定是代码"的字符上。
 *
 * # 覆盖范围（诚实边界）
 * 这是**遮蔽器，不是完整 Kotlin 解析器**。已处理：行注释、可嵌套块注释（KDoc）、普通字符串、
 * `"""` 原始字符串、字符字面量、反斜杠转义、字符串内 `${}` 插值（含插值里再套字符串）。
 * 撑不住时会**抛 [AssertionError]**（花括号不配平 / 函数体定位不到），不会静默放行。
 */
internal object KotlinSourceScan {

    /** 主源码相对路径的锚点：`<模块>/src/main/java/com/wotagei/cam/<relativePath>` */
    private const val MAIN_SRC = "src/main/java/com/wotagei/cam"

    /**
     * 找到主源码里的某个文件。
     *
     * 工作目录不确定（Gradle 的单元测试任务默认在 `app/`，IDE 里跑可能在工程根或仓库根），
     * 所以从 `user.dir` 往上走若干层逐个试。**找不到必须抛，不许返回 null**：
     * 找不到输入还全绿的守卫是最坏的一种守卫。
     */
    fun mainSourceFile(relativePath: String): File {
        val tried = ArrayList<String>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var hops = 0
        while (dir != null && hops <= 5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val f = File(base, "$MAIN_SRC/$relativePath")
                tried.add(f.path)
                if (f.isFile) return f
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError("找不到主源码文件 $relativePath（试过的路径：$tried）——守卫不能在没有输入的情况下算通过")
    }

    /** 读主源码（**显式 UTF-8**：测试 JVM 的 file.encoding 不由本工程保证） */
    fun mainSourceText(relativePath: String): String = mainSourceFile(relativePath).readText(Charsets.UTF_8)

    // ------------------------------------------------------------------ 整仓遍历

    /** 遍历时**按名字跳过的目录**（理由逐条见 [walkMainSources] 的三条排除规则） */
    private val walkSkipDirs = setOf(".mimosa", "build", "out", ".gradle", ".idea", ".tmp")

    /**
     * 主源码**包根** `<模块>/src/main/java/com/wotagei/cam` 的探测。
     *
     * 候选规则与 [mainSourceFile] 逐字同源——同一批 `dir` / `dir/app` / `dir/WotaGeiCamApp` /
     * `dir/WotaGeiCamApp/app` 四枚基目录 × 同样的 5 层上溯——差别只在命中条件：这里判
     * `isDirectory`（要的是目录），[mainSourceFile] 判 `isFile`（要的是那一枚文件）。
     * 找不到返回 null：抛错交给 [walkMainSources]，本函数只负责找，好让 `camRoot` 走参那座桥
     * （见 [walkMainSources] @param）不必跟着一起抛。
     */
    private fun mainSourceRootOrNull(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var hops = 0
        while (dir != null && hops <= 5) {
            for (base in listOf(dir, File(dir, "app"), File(dir, "WotaGeiCamApp"), File(File(dir, "WotaGeiCamApp"), "app"))) {
                val root = File(base, MAIN_SRC)
                if (root.isDirectory) return root
            }
            dir = dir.parentFile
            hops++
        }
        return null
    }

    /**
     * 遍历主源码**全部** `*.kt`：包根 → 相对路径 → 全文。
     *
     * 相对路径与 [mainSourceFile] 的入参**同一形状**（`/` 分隔，如 `ui/HudLayer.kt`），因此
     * `walkMainSources().keys` 可以直接当 `mainSourceText(...)` 的实参用，两张表不会各说各话
     * （[com.wotagei.cam.bt.BtScanLocationPermGuardTest] 用一枚「同一根、同一份内容」的断言钉住这一点）。
     *
     * # 为什么要它：整仓级的禁入守卫
     * 「位置权限只许住在白名单那两枚文件里」这类判据，早先只能硬编码几枚文件名逐个读——
     * **漏进第 6 枚文件的违规守卫全绿**，而 KDoc 里那句「白名单外任何文件 → 红」就成了没有实现
     * 支撑的空话。有了本函数，判据吃的是整棵主源码树。
     *
     * # 三条排除规则（缺哪条都会假绿，理由如下）
     * 1. **只扫 `src/main/java`**——由**遍历根**保证：根就是 `MAIN_SRC`，`src/test`、`src/androidTest`
     *    结构上够不着。刻意**不**再按「目录叫不叫 `test`」筛一遍：合法主源码包若恰好叫那个名字，
     *    按名筛选会把它静默漏掉，而「静默漏掉」正是本函数最不能犯的错；
     * 2. **`.mimosa` 整目录跳过**——本仓 `com/wotagei/cam/<包根>/.mimosa/hook-state` 下那一堆
     *    `.source` 是 hook 工具留下的**旧版源码副本**，对它们断言＝拿历史文本打分（恒假绿或恒红，
     *    本仓点过名的坑）。所以既用 `onEnter` 在目录层剪枝、又在文件层按相对路径**逐段复核**一遍，双保险；
     * 3. **只认 `.kt`**——`.source` / `.json` 等工具产物靠扩展名挡在门外，同样在文件层判。
     *
     * `build/`、`.gradle/` 之类构建产物目录一并按名字跳过（`src/main/java` 下本不该有，属防御性）。
     *
     * @param camRoot 指定包根；默认按 [mainSourceRootOrNull] 探测。**显式传值是给遍历规则本身留的
     *   一座桥**（AGENTS.md：「计划→行为」要测桥本身）——把一枚**只存在于临时目录里**的「新文件」
     *   喂进来，就能在不碰真实源码树的前提下证明「新文件会被枚举到、`.mimosa`/`build` 不会」。
     * @throws AssertionError 找不到包根、或一枚 `.kt` 都没枚举到——没有输入不许算通过
     */
    fun walkMainSources(camRoot: File? = null): Map<String, String> {
        val root = camRoot ?: mainSourceRootOrNull()
            ?: throw AssertionError("找不到主源码包根 $MAIN_SRC——守卫不能在没有输入的情况下算通过")
        val out = LinkedHashMap<String, String>()
        root.walkTopDown()
            .onEnter { it.name !in walkSkipDirs }
            .forEach { f ->
                if (!f.isFile || f.extension != "kt") return@forEach
                val rel = f.relativeTo(root).invariantSeparatorsPath
                // 目录层剪过一次，这里按相对路径逐段再复核：路径中任何一段命中跳过名单都算
                if (rel.split('/').any { it in walkSkipDirs }) return@forEach
                out[rel] = f.readText(Charsets.UTF_8)
            }
        if (out.isEmpty()) {
            throw AssertionError("walkMainSources 在 $root 下一枚 .kt 都没枚举到——守卫不能在没有输入的情况下算通过")
        }
        return out
    }

    // ------------------------------------------------------------------ 词法遮蔽

    private const val K_STR = 1
    private const val K_RAW = 2
    private const val K_CHAR = 3
    private const val K_INTERP = 4

    private class Frame(val kind: Int, var braces: Int = 0)

    /**
     * 遮蔽掉注释与字面量内容，返回**等长**文本（换行原样保留，行号才对得上）。
     * 结果里剩下的就是代码字符（含 `${}` 插值内部的代码）。
     */
    fun codeOnly(src: String): String {
        val n = src.length
        val out = CharArray(n)
        val stack = ArrayList<Frame>()
        var i = 0
        while (i < n) {
            val c = src[i]
            val top = if (stack.isEmpty()) null else stack[stack.size - 1]
            if (top != null && top.kind != K_INTERP) {
                // 字符串/字符字面量内部：除 `${` 之外全部遮蔽
                when {
                    c == '\n' -> { out[i] = '\n'; i++ }
                    top.kind != K_RAW && c == '\\' -> {
                        out[i] = ' '; i++
                        if (i < n) { out[i] = if (src[i] == '\n') '\n' else ' '; i++ }
                    }
                    top.kind == K_RAW && c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"' -> {
                        stack.removeAt(stack.size - 1)
                        out[i] = ' '; out[i + 1] = ' '; out[i + 2] = ' '
                        i += 3
                    }
                    top.kind == K_STR && c == '"' -> { stack.removeAt(stack.size - 1); out[i] = ' '; i++ }
                    top.kind == K_CHAR && c == '\'' -> { stack.removeAt(stack.size - 1); out[i] = ' '; i++ }
                    c == '$' && i + 1 < n && src[i + 1] == '{' -> {
                        stack.add(Frame(K_INTERP, 0))
                        out[i] = ' '; out[i + 1] = ' '
                        i += 2
                    }
                    else -> { out[i] = ' '; i++ }
                }
                continue
            }
            // 这里往下都是代码（顶层，或某层插值内部）
            when {
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    out[i] = ' '; out[i + 1] = ' '; i += 2
                    while (i < n && src[i] != '\n') { out[i] = ' '; i++ }
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> { i = maskBlockComment(src, out, i) }
                c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"' -> {
                    stack.add(Frame(K_RAW)); out[i] = ' '; out[i + 1] = ' '; out[i + 2] = ' '; i += 3
                }
                c == '"' -> { stack.add(Frame(K_STR)); out[i] = ' '; i++ }
                c == '\'' -> { stack.add(Frame(K_CHAR)); out[i] = ' '; i++ }
                // 插值内部的成对花括号两边都留字（配平）；插值自己那对 `${` … `}` 两边都不留字
                c == '{' && top != null -> { top.braces++; out[i] = '{'; i++ }
                c == '}' && top != null -> {
                    if (top.braces == 0) {
                        stack.removeAt(stack.size - 1)
                        out[i] = ' '
                    } else {
                        top.braces--
                        out[i] = '}'
                    }
                    i++
                }
                else -> { out[i] = c; i++ }
            }
        }
        return String(out)
    }

    /** 可嵌套块注释（Kotlin 的块注释允许套娃，KDoc 里写代码样例就会碰到）；进来时 `src[from]` 就是那个斜杠 */
    private fun maskBlockComment(src: String, out: CharArray, from: Int): Int {
        var i = from
        var depth = 1
        out[i] = ' '; out[i + 1] = ' '; i += 2
        val n = src.length
        while (i < n && depth > 0) {
            val c = src[i]
            if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                depth++; out[i] = ' '; out[i + 1] = ' '; i += 2; continue
            }
            if (c == '*' && i + 1 < n && src[i + 1] == '/') {
                depth--; out[i] = ' '; out[i + 1] = ' '; i += 2; continue
            }
            out[i] = if (c == '\n') '\n' else ' '
            i++
        }
        if (depth != 0) throw AssertionError("块注释没闭合（起始偏移 $from）——遮蔽器判定失败，不给半份文本")
        return i
    }

    // ------------------------------------------------------------------ 结构定位

    /** 函数体区间：[start] 指向开 `{`，[end] 是闭 `}` 的下一位 */
    internal class Body(val start: Int, val end: Int)

    /**
     * 解出 `fun <name>(...) [: 返回类型] { ... }` 的花括号区间。
     * 表达式体（`= xxx`）不算区间（没有花括号可言）。
     */
    fun regionsOf(masked: String, name: String): List<Body> {
        val found = ArrayList<Body>()
        val declRegex = Regex("\\bfun\\s+" + Regex.escape(name) + "\\s*\\(")
        for (m in declRegex.findAll(masked)) {
            val paren = masked.indexOf('(', m.range.first)
            if (paren < 0) continue
            regionAfterDecl(masked, paren)?.let { found.add(it) }
        }
        return found
    }

    /** 文件里**所有**带花括号体的函数（名 → 区间；重载各占一条） */
    fun allBodies(masked: String): List<Pair<String, Body>> {
        val found = ArrayList<Pair<String, Body>>()
        for (m in Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").findAll(masked)) {
            val paren = masked.indexOf('(', m.range.first)
            if (paren < 0) continue
            regionAfterDecl(masked, paren)?.let { found.add(m.groupValues[1] to it) }
        }
        return found
    }

    /** 某个偏移落在哪个函数体里（多个重叠加套时取最里层）；不在任何体内返回 null（如顶层属性初始化） */
    fun enclosingOf(bodies: List<Pair<String, Body>>, offset: Int): String? =
        bodies.filter { offset in it.second.start until it.second.end }
            .minByOrNull { it.second.end - it.second.start }
            ?.first

    /** 从参数表左圆括号处往后解出一整个函数体；表达式体返回 null */
    private fun regionAfterDecl(masked: String, paren: Int): Body? {
        var i = paren
        var depth = 0
        var closed = false
        while (i < masked.length) {
            val c = masked[i]
            if (c == '(') depth++ else if (c == ')') {
                depth--
                if (depth == 0) { i++; closed = true; break }
            }
            i++
        }
        if (!closed) return null
        // 参数表之后到函数体之间只允许返回类型；撞上 `=` 就是表达式体，撞上 `}` 是没体的抽象/接口声明
        val signature = StringBuilder()
        while (i < masked.length) {
            val c = masked[i]
            if (c == '{') {
                // 跨过了另一个 `fun` 说明刚才那枚声明根本没有体（接口/抽象成员），
                // 那枚 `{` 属于后面的声明——必须拒绝，否则"函数体"会一路吞到整份文件
                if (Regex("\\b(fun|val|var|object|interface|class|enum|init|companion)\\b").containsMatchIn(signature)) return null
                return matchBraces(masked, i)
            }
            if (c == '=' || c == '}') return null
            signature.append(c)
            if (signature.length > 400) return null
            i++
        }
        return null
    }

    private fun matchBraces(masked: String, openAt: Int): Body {
        var depth = 0
        var i = openAt
        while (i < masked.length) {
            when (masked[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return Body(openAt, i + 1) }
            }
            i++
        }
        throw AssertionError("花括号不配平（开在偏移 $openAt）——遮蔽器没覆盖到的写法，宁可报错也不要给半份文本")
    }

    /**
     * 取某个函数的**体文本**（花括号内部，不含定界符）。
     * 找不到、或同名函数不止一个（重载）都直接抛：守卫的前提是"我知道我在看哪一段"。
     */
    fun bodyOf(masked: String, name: String): String {
        val regions = regionsOf(masked, name)
        if (regions.isEmpty()) {
            throw AssertionError("源码里找不到 fun $name(...) 的花括号体——函数被改名/挪走/改成表达式体，守卫失去输入，不许算通过")
        }
        if (regions.size > 1) {
            throw AssertionError("fun $name 有 ${regions.size} 个重载体，守卫得逐个点名，否则只锁住第一个会漏")
        }
        val r = regions[0]
        return masked.substring(r.start + 1, r.end - 1)
    }

    /** 同 [bodyOf]，但给出区间（要判"某次调用落在哪个函数体里"时用） */
    fun regionOf(masked: String, name: String): Body {
        val regions = regionsOf(masked, name)
        if (regions.size != 1) {
            throw AssertionError("fun $name 定位到 ${regions.size} 个函数体，期望恰好 1 个")
        }
        return regions[0]
    }

    /** 词边界出现位置（避免把 `drawPass` 的命中算成 `drawPassXYZ` 的一部分） */
    fun occurrences(text: String, needle: String): List<Int> {
        val res = ArrayList<Int>()
        var i = text.indexOf(needle)
        while (i >= 0) {
            val before = if (i == 0) ' ' else text[i - 1]
            val afterIdx = i + needle.length
            val after = if (afterIdx >= text.length) ' ' else text[afterIdx]
            if (!before.isIdentPart() && !after.isIdentPart()) res.add(i)
            i = text.indexOf(needle, i + 1)
        }
        return res
    }

    /** 偏移 → 1 起的行号（用原文算；遮蔽文本与原文逐位等长，所以偏移通用） */
    fun lineOf(src: String, offset: Int): Int {
        var line = 1
        val limit = minOf(offset, src.length)
        for (i in 0 until limit) if (src[i] == '\n') line++
        return line
    }

    /** 折叠所有空白：让 `contains` 判据不受换行与缩进影响 */
    fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun Char.isIdentPart(): Boolean = this == '_' || this == '$' || isLetterOrDigit()
}
