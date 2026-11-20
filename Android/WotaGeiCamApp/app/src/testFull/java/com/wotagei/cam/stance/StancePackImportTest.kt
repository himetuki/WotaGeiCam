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
        gpuModel: ByteArray = StanceTestFixtures.tflite(),
    ): LinkedHashMap<String, ByteArray> = stanceTestPayload(gpu = gpu, margins = margins, gpuModel = gpuModel)

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
    fun doubleRenameFailureReportsPendingRecoveryInsteadOfUntouched() {
        val root = temp.newFolder()
        val archive = zip(payload())
        StanceModelPack.importStream(root, ByteArrayInputStream(archive))
        val error = assertThrows(StanceModelPack.ImportException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(archive)) { from, to ->
                // 激活与恢复两次 rename 都失败：上一套只能留在 previous 等下次启动恢复。
                if (from.name.startsWith("model_pack_import_") || from.name == "model_pack_previous") false
                else from.renameTo(to)
            }
        }
        assertEquals(StanceModelPack.RollbackStatus.PENDING_RECOVERY, error.rollback)
        val message = requireNotNull(error.message)
        assertFalse("待恢复不能提示成未变：$message", message.contains("未变"))
        assertTrue("待恢复提示必须点名 previous", message.contains("model_pack_previous"))
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_previous")))
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
    fun crashedStagingRegularFileIsReclaimedBeforeNextImport() {
        val root = temp.newFolder()
        // createTempFile 后、delete+mkdir 前进程退出会留下普通文件；它必须能被回收，
        // 否则 stale 清单永远非空，后续每次导入都在清理阶段被拒绝。
        val crashed = File(root, "model_pack_import_abandoned").apply { writeText("crash window") }
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        assertFalse(crashed.exists())
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
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
    fun importedPackKeepsPreviousUntilFirstInitConfirms() {
        val root = temp.newFolder()
        val v1 = payload()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(v1)))
        val v1Head = File(root, "model_pack_user/head.json").readBytes()
        // 第二套模型（head 不同）导入成功后，上一套必须仍在 model_pack_previous。
        val v2 = stanceTestPayload(head = """{"coef":[0,0,0,0,6],"intercept":-3}""")
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(v2)))
        assertNotNull("上一套必须保留到首次初始化确认", StanceModelPack.validateDir(File(root, "model_pack_previous")))
        assertArrayEquals(v1Head, File(root, "model_pack_previous/head.json").readBytes())
        // 首次 Interpreter 初始化成功后才允许删除。
        assertTrue(StanceModelPack.confirmActivePack(root))
        assertFalse(File(root, "model_pack_previous").exists())
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
    }

    @Test
    fun rollbackAfterUnconfirmedImportRestoresPrevious() {
        val root = temp.newFolder()
        val v1Head = run {
            StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
            File(root, "model_pack_user/head.json").readBytes()
        }
        StanceModelPack.importStream(
            root,
            ByteArrayInputStream(zip(stanceTestPayload(head = """{"coef":[0,0,0,0,6],"intercept":-3}"""))),
        )
        val outcome = StanceModelPack.rollbackActivePack(root)
        assertEquals(StanceModelPack.RollbackStatus.RESTORED_PREVIOUS, outcome.status)
        assertArrayEquals(v1Head, File(root, "model_pack_user/head.json").readBytes())
        assertFalse(File(root, "model_pack_previous").exists())
    }

    @Test
    fun rollbackWithNoPreviousDropsUnconfirmedTarget() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val outcome = StanceModelPack.rollbackActivePack(root)
        assertEquals(StanceModelPack.RollbackStatus.NO_PREVIOUS, outcome.status)
        assertFalse("没有上一套可用时不能把未确认的新包留在激活目录", File(root, "model_pack_user").exists())
    }

    @Test
    fun startupRecoveryReplacesCorruptTargetWithPrevious() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val target = File(root, "model_pack_user")
        assertTrue(target.renameTo(File(root, "model_pack_previous")))
        target.mkdirs()
        File(target, "junk.bin").writeText("corrupt")
        // 导入前先恢复：坏 target 被隔离，previous 填回激活位；随后空 zip 解析失败。
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(byteArrayOf()))
        }
        assertNotNull(StanceModelPack.validateDir(target))
        assertFalse(File(root, "model_pack_previous").exists())
        assertClean(root)
    }

    @Test
    fun tensorContractMismatchWithMatchingHashesCannotReplaceActivePack() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val oldHead = File(root, "model_pack_user/head.json").readBytes()
        // 清单里的 SHA 全部正确，但张量形状不是资产承诺的规格 → 装配期拒绝。
        val bad = stanceTestPayload(model = StanceTestFixtures.tflite(inputShape = intArrayOf(1, 3, 320, 320)))
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(zip(bad)))
        }
        assertArrayEquals(oldHead, File(root, "model_pack_user/head.json").readBytes())
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
        assertClean(root)
    }

    @Test
    fun gpuDetectionAssetIsTensorValidatedEvenWithObfuscatedName() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val oldHead = File(root, "model_pack_user/head.json").readBytes()
        // GPU 资产是改了扩展名的 tflite：只按 .tflite 后缀校验会漏掉真正参与 GPU 推理的权重。
        // 清单/SHA/head 全部合法，但 GPU 张量输出行数不足 → 暂存阶段必须拒绝。
        val badGpu = stanceTestPayload(
            gpuModel = StanceTestFixtures.tflite(outputShape = intArrayOf(1, 4, 4116)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(zip(badGpu)))
        }
        assertArrayEquals(oldHead, File(root, "model_pack_user/head.json").readBytes())
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
        assertClean(root)
    }

    @Test
    fun alternateGpuAssetNameUsesTheSamePackWideContract() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val oldHead = File(root, "model_pack_user/head.json").readBytes()
        // 另一个 obfuscated 名（训练工具 fp16 变体）也不能绕过校验。
        val badAlias = stanceTestPayload(
            gpu = "yolo11n_fp16.tflite",
            gpuModel = StanceTestFixtures.tflite(inputShape = intArrayOf(1, 4, 448, 448)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            StanceModelPack.importStream(root, ByteArrayInputStream(zip(badAlias)))
        }
        assertArrayEquals(oldHead, File(root, "model_pack_user/head.json").readBytes())
        assertNotNull(StanceModelPack.validateDir(File(root, "model_pack_user")))
        assertClean(root)
    }

    @Test
    fun gpuOnlyAssetReplacementChangesSemanticFingerprintWhileSamePackStaysStable() {
        val root = temp.newFolder()
        val metaA = StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val filesA = requireNotNull(metaA["files"] as? Map<*, *>)
        val fingerprintA = requireNotNull(StanceModelPack.semanticFingerprint(metaA))
        // 同一份清单（同一包切 auto/force/off 后端）指纹必须稳定，判定状态才能保留。
        assertEquals(fingerprintA, StanceModelPack.semanticFingerprint(metaA))

        // 只替换 GPU 检测资产：CPU 主模型 SHA 不变，但语义指纹必须变化。
        val metaB = StanceModelPack.importStream(
            root,
            ByteArrayInputStream(
                zip(payload(gpuModel = StanceTestFixtures.tflite(inputName = "gpu-b"))),
            ),
        )
        val filesB = requireNotNull(metaB["files"] as? Map<*, *>)
        assertEquals(filesA["yolo11n_int8.tflite"], filesB["yolo11n_int8.tflite"])
        assertNotEquals(filesA["yolo11n_fp32.tflite"], filesB["yolo11n_fp32.tflite"])
        val fingerprintB = requireNotNull(StanceModelPack.semanticFingerprint(metaB))
        assertNotEquals("仅换 GPU 权重也必须重置判定语义", fingerprintA, fingerprintB)
    }

    @Test
    fun confirmDoesNotDiscardPreviousWhenTargetBecameInvalid() {
        val root = temp.newFolder()
        StanceModelPack.importStream(root, ByteArrayInputStream(zip(payload())))
        val v1Head = File(root, "model_pack_user/head.json").readBytes()
        StanceModelPack.importStream(
            root,
            ByteArrayInputStream(zip(stanceTestPayload(head = """{"coef":[0,0,0,0,6],"intercept":-3}"""))),
        )
        // 目标目录在首次初始化前被外部破坏（权限/断电）：不能把唯一可回滚的上一套删掉。
        File(root, "model_pack_user/head.json").appendText("x")
        assertFalse(StanceModelPack.confirmActivePack(root))
        assertTrue(File(root, "model_pack_previous").exists())
        val outcome = StanceModelPack.rollbackActivePack(root)
        assertEquals(StanceModelPack.RollbackStatus.RESTORED_PREVIOUS, outcome.status)
        assertArrayEquals(v1Head, File(root, "model_pack_user/head.json").readBytes())
        assertFalse(File(root, "model_pack_previous").exists())
    }

    @Test
    fun builtinPackMeetsTheSameStrictManifestContract() {
        val dir = File("src/full/assets/model_pack")
        val meta = requireNotNull(StanceModelPack.validateDir(dir)) { "checked-in builtin pack" }
        // Y66：随包 litert wrapper 的输出已归一化（真机实测），必须在清单里显式声明。
        assertEquals(StanceModelPack.COORD_NORMALIZED, StanceModelPack.coordinateUnit(meta))
    }
}
