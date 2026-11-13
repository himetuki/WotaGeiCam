package com.wotagei.cam.stance

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 走真实解压、清单校验和同卷激活；只在 rename 失败测试中注入文件系统故障。 */
class StancePackImportTest {
    @get:Rule
    val temp = TemporaryFolder(File("build/test-tmp").apply { mkdirs() })

    private fun payload(
        gpu: String = "yolo11n_fp32.tflite",
        margins: String = """{"bands":{"left":[0.1,0.4],"right":[0.1,0.4],"bottom":[0.0,0.2]}}""",
    ): LinkedHashMap<String, ByteArray> {
        val files = linkedMapOf(
            "head.json" to """{"coef":[0,0,0,0,5],"intercept":-3}""".toByteArray(),
            "margins.json" to margins.toByteArray(),
            "yolo11n_int8.tflite" to "cpu-model".toByteArray(),
            gpu to "gpu-model".toByteArray(),
        )
        val hashes = files.entries.joinToString(",") { (name, bytes) ->
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "\"$name\":\"$sha\""
        }
        files["pack.meta.json"] = """{"schema":1,"features":["cx","by","w","h","conf"],"files":{$hashes}}""".toByteArray()
        return files
    }

    private fun zip(files: Map<String, ByteArray>, prefix: String = ""): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            if (prefix.isNotEmpty()) { zip.putNextEntry(ZipEntry(prefix)); zip.closeEntry() }
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(prefix + name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun assertClean(root: File) {
        val leftover = root.listFiles()!!.filter { it.name.startsWith("model_pack_import_") }
        assertTrue("staging left: ${leftover.flatMap { it.listFiles()?.toList().orEmpty() }.map { it.name to it.length() }}", leftover.isEmpty())
    }

    @Test
    fun producerWrappedZipAndFlatZipAreBothAccepted() {
        for (prefix in listOf("", "model_pack/")) {
            val root = temp.newFolder()
            StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload(), prefix)))
            assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
            assertClean(root)
        }
    }

    @Test
    fun fp16PackProducedByTrainingToolRemainsUsable() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload("yolo11n_fp16.tflite"))))
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
    }

    @Test
    fun badReplacementCannotEraseTheExistingActivePack() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val old = File(root, "model_pack_user/head.json").readBytes()
        val bad = payload().apply { this["head.json"] = "tampered".toByteArray() }
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(zip(bad)))
        }
        assertArrayEquals(old, File(root, "model_pack_user/head.json").readBytes())
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
        assertClean(root)
    }

    @Test
    fun renameFailureRestoresExistingPackWithoutCopyFallback() {
        val root = temp.newFolder()
        val archive = zip(payload())
        StanceModelPack.importStream(root, ByteArrayInputStream(archive))
        assertThrows(IllegalStateException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(archive)) { from, to ->
                if (from.name.startsWith("model_pack_import_")) false else from.renameTo(to)
            }
        }
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
        assertFalse(File(root, "model_pack_previous").exists())
        assertClean(root)
    }

    @Test
    fun processDeathBetweenRenamesIsRecoveredBeforeNextImport() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        assertTrue(File(root, "model_pack_user").renameTo(File(root, "model_pack_previous")))
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(byteArrayOf()))
        }
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
        assertClean(root)
    }

    @Test
    fun traversalAbsoluteAndUnknownPathsAreRejectedBeforeActivation() {
        for (name in listOf("../outside", "/head.json", "model_pack/../head.json", "model_pack\\head.json", "C:head.json", "x/head.json", "extra.so")) {
            val root = temp.newFolder()
            val bad = linkedMapOf(name to "bad".toByteArray()).apply { putAll(payload()) }
            assertThrows(name, IllegalArgumentException::class.java) {
                StanceModelPack.importStream(root, ByteArrayInputStream(zip(bad)))
            }
            assertFalse(File(root, "model_pack_user").exists())
            assertClean(root)
        }
    }

    @Test
    fun aliasingZipEntriesAreRejectedRatherThanOverwritingPayload() {
        val root = temp.newFolder()
        val bad = payload().apply { this["model_pack/head.json"] = this["head.json"]!! }
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(zip(bad)))
        }
        assertClean(root)
    }

    @Test
    fun repeatedExactZipEntryIsRejected() {
        val root = temp.newFolder()
        val bad = payload().apply { this["head.jsox"] = this["head.json"]!! }
        val archive = zip(bad)
        val marker = "head.jsox".toByteArray()
        for (i in 0..archive.size - marker.size) {
            if (marker.indices.all { archive[i + it] == marker[it] }) archive[i + marker.lastIndex] = 'n'.code.toByte()
        }
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(archive))
        }
        assertClean(root)
    }

    @Test
    fun missingOrEmptyFilesAreRejected() {
        for (bad in listOf(payload().apply { remove("head.json") }, payload().apply { this["head.json"] = byteArrayOf() })) {
            val root = temp.newFolder()
            assertThrows(IllegalArgumentException::class.java) {
                StanceModelPack.importStream(root, ByteArrayInputStream(zip(bad)))
            }
            assertClean(root)
        }
    }

    @Test
    fun compressedJsonBombWithUnknownEntrySizeIsBoundedByActualBytes() {
        val root = temp.newFolder()
        val bad = linkedMapOf("pack.meta.json" to ByteArray(65 * 1024) { ' '.code.toByte() })
        val archive = zip(bad)
        assertTrue(archive.size < 1024)
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(archive))
        }
        assertFalse(File(root, "model_pack_user").exists())
        assertClean(root)
    }

    @Test
    fun interruptedReadCleansOnlyItsOwnStagingAndRetainsActivePack() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val broken = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("test interrupted stream")
        }
        assertThrows(java.io.IOException::class.java) { StanceModelPack.importStream(root, broken) }
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
        assertClean(root)
    }

    @Test
    fun oversizedModelIsRejectedWhileDecompressingAndCleaned() {
        val root = temp.newFolder()
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("yolo11n_int8.tflite"))
                val chunk = ByteArray(64 * 1024)
                repeat(1024) { zip.write(chunk) }
                zip.write(0)
                zip.closeEntry()
            }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(archive))
        }
        assertClean(root)
    }

    @Test
    fun aggregateLimitIsEnforcedEvenWhenEachModelIsWithinItsLimit() {
        val root = temp.newFolder()
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                val chunk = ByteArray(64 * 1024)
                for (name in listOf("yolo11n_int8.tflite", "yolo11n_fp32.tflite")) {
                    zip.putNextEntry(ZipEntry(name))
                    repeat(1024) { zip.write(chunk) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("head.json"))
                zip.write(1)
                zip.closeEntry()
            }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(archive))
        }
        assertClean(root)
    }

    @Test
    fun producerWrapperWithoutDirectoryEntryIsAccepted() {
        val root = temp.newFolder()
        val wrapped = payload().mapKeys { (name, _) -> "model_pack/$name" }
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(wrapped)))
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
        assertClean(root)
    }

    @Test
    fun directoryEntryCannotHideAnUnboundedPayload() {
        val root = temp.newFolder()
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("model_pack/"))
                zip.write(ByteArray(65 * 1024))
                zip.closeEntry()
            }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(archive))
        }
        assertClean(root)
    }

    @Test
    fun floatCollapsedMarginsWithCorrectHashesCannotReplaceActivePackOrBands() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val oldHead = File(root, "model_pack_user/head.json").readBytes()
        val bands = StanceSpec.MARGIN_BANDS.toMap()
        for (range in listOf("[0.1,0.100000001]", "[1e-50,2e-50]")) {
            val margins = """{"bands":{"left":$range,"right":[0.1,0.4],"bottom":[0.0,0.2]}}"""
            // 重算全套 SHA，钉住端侧 Float 规格，不能靠摘要不匹配偶然拒绝。
            val bad = payload(margins = margins)
            assertThrows(IllegalArgumentException::class.java) {
                StanceModelPack.importStream(root, ByteArrayInputStream(zip(bad)))
            }
            assertArrayEquals(oldHead, File(root, "model_pack_user/head.json").readBytes())
            assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
            assertFalse(StanceSpec.loadCalibratedBands(margins))
            assertEquals("失败不可部分覆盖安全区", bands, StanceSpec.MARGIN_BANDS)
            assertClean(root)
        }
    }

    @Test
    fun cleanupFailureStillAttemptsAllChildrenAndNextImportReclaimsTheRemainder() {
        val root = temp.newFolder()
        val stale = File(root, "model_pack_import_abandoned").apply { mkdir() }
        val blocked = File(stale, "head.json").apply { writeText("blocked") }
        val other = File(stale, "margins.json").apply { writeText("removable") }
        val attempted = mutableSetOf<File>()
        assertFalse(StanceModelPack.removeFlatDir(stale) { file ->
            attempted.add(file)
            file != blocked && file.delete()
        })
        assertEquals(setOf(blocked, other), attempted)
        assertTrue(blocked.exists())
        assertFalse("一个删除失败不能短路跳过其他文件", other.exists())
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        assertFalse(stale.exists())
        assertClean(root)
    }

    @Test
    fun unknownNestedStagingIsNotRecursivelyDeletedOrAllowedToAccumulate() {
        val root = temp.newFolder()
        val stale = File(root, "model_pack_import_unknown").apply { mkdir() }
        val nested = File(stale, "nested").apply { mkdir() }
        val retained = File(nested, "keep").apply { writeText("must remain") }
        assertThrows(IllegalStateException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        }
        assertEquals("must remain", retained.readText())
        assertEquals(listOf(stale), root.listFiles()!!.toList())
        assertFalse(File(root, "model_pack_user").exists())
    }

    @Test
    fun builtinPackMeetsTheSameStrictManifestContract() {
        val dir = File("src/full/assets/model_pack")
        assertNotNull("checked-in builtin pack", StanceModelPack.validateDir(dir))
    }
}
