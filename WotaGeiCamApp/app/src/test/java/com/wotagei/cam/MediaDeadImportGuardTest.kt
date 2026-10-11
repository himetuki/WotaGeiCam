package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `media/MediaActions.kt` 的**死 import 守卫**（#13）。
 *
 * 背景：这七个符号是「系统授权框」那条老路的残留（`PendingIntent`/`ActivityInfo`/
 * `rememberLauncherForActivityResult`/`IntentSenderRequest`/`ActivityResultContracts`）
 * 与几处早已改写掉的 Compose/工具引用（`mutableStateOf`/`findActivity`）。框已从代码里
 * 彻底移除（见 `MediaSilentFailGuardTest` 的系统确认框守卫），符号却还挂在 import 段上：
 * 读者会以为这条路还在，编译期也无害（Kotlin 允许未使用的 import），于是它能一直躺着。
 *
 * 判定口径：**只判 import 段的简单名**。不判正文是有意的——正文里出现同名局部变量、
 * 注释里提到符号名都属正常，只有 import 段是"这个文件真的向编译器要了这个绑定"的事实。
 *
 * ⚠ 这条守卫**不是**「lint unused 归零」的度量：Android Lint 默认根本没有 `UnusedImport`
 * 检查项，本仓也没有 detekt/ktlint，所以删 import 不会让 `gradlew lint` 的 warning 数变化。
 * 想要一个真会变小的数字，看 #14 的 `ObsoleteSdkInt`。
 *
 * 突变自证：把七个符号任意一个塞回 import 段 → 判据必须红（见 [尺子能红]）。
 */
class MediaDeadImportGuardTest {

    /** 已被删除的死符号（简单名）；`Diag as Log` 这种带别名的取原名，别名不改变「引的是谁」 */
    private val dead = listOf(
        "PendingIntent",
        "ActivityInfo",
        "rememberLauncherForActivityResult",
        "IntentSenderRequest",
        "ActivityResultContracts",
        "mutableStateOf",
        "findActivity"
    )

    /** import 段的简单名集合：`import a.b.C as D` 取 `C` */
    private fun importedNames(src: String): Set<String> =
        src.lines()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").substringBefore(" as ").trim().substringAfterLast('.') }
            .toSet()

    /** 被判为死的 import（真值表：命中即列出，未命中为空集） */
    private fun deadHits(src: String): List<String> =
        importedNames(src).filter { it in dead }.sorted()

    @Test
    fun `MediaActions 的 import 段不许再出现这七个死符号`() {
        val hits = deadHits(mainSourceText("media/MediaActions.kt"))
        assertEquals("import 段仍引着已删除的死符号：$hits", emptyList<String>(), hits)
    }

    /**
     * 尺子能红（突变自证）：把七个符号逐个塞回 import 段，判据必须**逐个**报出来。
     *
     * 逐个而不是一把塞七个：七个同时塞进去时，只要判据认得其中任意一个（比如只查 `PendingIntent`）
     * 这条断言照样绿——那等于没证明其余六个真的被看着。逐个塞 ⇒ 认不全就必红。
     */
    @Test
    fun `尺子能红：逐个塞回 import 段判据必须逐个报出来`() {
        val src = mainSourceText("media/MediaActions.kt")
        val anchor = "import android.os.Build"
        assertTrue("锚点行不在（文件结构变了，突变自证失去落点）", src.contains(anchor))
        for (sym in dead) {
            val mutant = src.replace(anchor, "$anchor\nimport fake.pkg.$sym")
            assertEquals(
                "把 $sym 塞回 import 段后判据没报出来——这条守卫对它失守",
                listOf(sym),
                deadHits(mutant)
            )
        }
    }
}
