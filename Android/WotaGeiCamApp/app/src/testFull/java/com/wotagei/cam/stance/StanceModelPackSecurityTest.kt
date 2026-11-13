package com.wotagei.cam.stance

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 实际目录/元数据边界，不以源码字符串代替输入校验。 */
class StanceModelPackSecurityTest {
    @get:Rule
    val temp = TemporaryFolder(File("build/test-tmp").apply { mkdirs() })

    private val payloads = linkedMapOf(
        "head.json" to """{"coef":[0,0,0,0,5],"intercept":-3}""",
        "margins.json" to """{"bands":{"left":[0.1,0.4],"right":[0.1,0.4],"bottom":[0.0,0.2]}}""",
        "yolo11n_int8.tflite" to "fake-int8",
        "yolo11n_fp32.tflite" to "fake-fp32",
    )

    private fun pack(): File = temp.newFolder("pack").also { dir ->
        payloads.forEach { (name, text) -> File(dir, name).writeText(text) }
        writeMeta(dir)
    }

    private fun writeMeta(dir: File, schema: String = "1", names: List<String> = payloads.keys.toList(), extra: String = "") {
        val hashes = names.joinToString(",") { name ->
            "\"$name\":\"${StanceModelPack.sha256Of(File(dir, name))}\""
        }
        File(dir, "pack.meta.json").writeText(
            """{"schema":$schema,"features":["cx","by","w","h","conf"],"files":{$hashes}$extra}"""
        )
    }

    @Test
    fun canonicalPackStillLoads() {
        assertNotNull(StanceModelPack.validateDir(pack()))
    }

    @Test
    fun manifestCannotDeclareParentFileEvenWithMatchingHash() {
        val dir = pack()
        File(dir.parentFile, "outside").writeText("private-file")
        writeMeta(dir, names = payloads.keys.toList() + "../outside")
        assertNull(StanceModelPack.validateDir(dir))
    }

    @Test
    fun manifestMustCoverTheWholeRequiredPayloadNotJustOneMatchingFile() {
        val dir = pack()
        writeMeta(dir, names = listOf("head.json"))
        assertNull(StanceModelPack.validateDir(dir))
    }

    @Test
    fun schemaMustBeAnExactVersionNotTruncatedFloat() {
        val dir = pack()
        writeMeta(dir, schema = "1.5")
        assertNull(StanceModelPack.validateDir(dir))
    }

    @Test
    fun oversizedMetadataIsRejectedBeforeParsing() {
        val dir = pack()
        writeMeta(dir, extra = ",\"notes\":\"${"x".repeat(65 * 1024)}\"")
        assertNull(StanceModelPack.validateDir(dir))
    }

    @Test
    fun oversizedHeadIsRejectedEvenWhenChecksumMatches() {
        val dir = pack()
        File(dir, "head.json").appendText(" ".repeat(65 * 1024))
        writeMeta(dir)
        assertNull(StanceModelPack.validateDir(dir))
    }

    @Test
    fun directoryCannotContainUnlistedPayload() {
        val dir = pack()
        File(dir, "unexpected.so").writeText("unexpected")
        assertNull(StanceModelPack.validateDir(dir))
    }
}
