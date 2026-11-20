package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        "head.json" to """{"coef":[0,0,0,0,5],"intercept":-3}""".toByteArray(),
        "margins.json" to """{"bands":{"left":[0.1,0.4],"right":[0.1,0.4],"bottom":[0.0,0.2]}}""".toByteArray(),
        "yolo11n_int8.tflite" to StanceTestFixtures.tflite(),
        "yolo11n_fp32.tflite" to StanceTestFixtures.tflite(),
    )

    private fun pack(name: String = "pack"): File = temp.newFolder(name).also { dir ->
        payloads.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
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

    // Y66/R16：坐标单位是导入契约的一部分；旧包缺字段仍可用，未知声明必须在激活前拒绝。

    @Test
    fun missingCoordKeepsLegacyPackUsableWithAutoDetection() {
        val meta = requireNotNull(StanceModelPack.validateDir(pack()))
        assertNull("旧包缺 coord 字段必须继续可用，交给检测器值域自适应", StanceModelPack.coordinateUnit(meta))
    }

    @Test
    fun declaredCoordinateUnitsAreAcceptedAndExposed() {
        for (unit in listOf(StanceModelPack.COORD_NORMALIZED, StanceModelPack.COORD_PIXEL)) {
            val dir = pack("coord-$unit")
            writeMeta(dir, extra = ",\"coord\":\"$unit\"")
            val meta = requireNotNull(StanceModelPack.validateDir(dir))
            assertEquals(unit, StanceModelPack.coordinateUnit(meta))
        }
    }

    @Test
    fun unknownOrNonStringCoordinateUnitIsRejectedBeforeActivation() {
        val unknown = pack("coord-unknown")
        writeMeta(unknown, extra = ",\"coord\":\"pixels\"")
        assertNull("未知坐标单位不能进入激活目录", StanceModelPack.validateDir(unknown))

        val nonString = pack("coord-non-string")
        writeMeta(nonString, extra = ",\"coord\":1")
        assertNull("坐标单位必须是字符串", StanceModelPack.validateDir(nonString))
    }

    @Test
    fun coordinateUnitParticipatesInSemanticFingerprint() {
        val legacy = pack("coord-legacy")
        val legacyMeta = requireNotNull(StanceModelPack.validateDir(legacy))
        val normalized = pack("coord-normalized")
        writeMeta(normalized, extra = ",\"coord\":\"normalized\"")
        val normalizedMeta = requireNotNull(StanceModelPack.validateDir(normalized))
        val pixel = pack("coord-pixel")
        writeMeta(pixel, extra = ",\"coord\":\"pixel\"")
        val pixelMeta = requireNotNull(StanceModelPack.validateDir(pixel))
        val legacyFp = requireNotNull(StanceModelPack.semanticFingerprint(legacyMeta))
        assertNotEquals(legacyFp, StanceModelPack.semanticFingerprint(normalizedMeta))
        assertNotEquals(
            StanceModelPack.semanticFingerprint(normalizedMeta),
            StanceModelPack.semanticFingerprint(pixelMeta),
        )
    }
}
