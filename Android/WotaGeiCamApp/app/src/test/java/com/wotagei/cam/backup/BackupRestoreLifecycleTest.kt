package com.wotagei.cam.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 真正调用生产 onRestoreFinished / WotaApp.onCreate / restoreTags / onFullBackup，失败仅注入平台 IO。 */
class BackupRestoreLifecycleTest {
    private val keyA = PortableUserData.MediaKey("Movies/", "a.mp4", 123L, 1000L)
    private val a = PortableUserData.Tag(keyA, "like", 10L)
    private val b = PortableUserData.Tag(keyA.copy(name = "b.mp4"), "技巧", 20L)
    private val incoming get() = PortableUserData.Snapshot(mapOf("gallery_columns" to 3, "level_enabled" to true), listOf(b))

    private fun fixture() = BackupPlatformFixture(classes, temporary.newFolder())
    private fun seed(f: BackupPlatformFixture) {
        f.seedSettings(mapOf("gallery_columns" to 5, "compare_history" to "local-only"))
        f.write(f.pending, PortableUserData.Snapshot(emptyMap(), listOf(a)))
        f.write(f.staged, incoming)
    }

    @Test fun `prefs提交失败保留合法源_下次真实启动补缺并恰好消费`() {
        fixture().use { f ->
            seed(f)
            f.script("commitOk", false)
            runCatching { f.restoreFinished() }
            assertTrue("失败不能删掉本次合法输入", f.staged.isFile)
            assertEquals(listOf(a), f.read(f.pending).tags)
            assertFalse(f.disk.containsKey("level_enabled"))
            // SharedPreferences commit(false) 也可能改内存；重试仍必须真正再提交，不能把它当作已落盘。
            assertEquals(true, f.memory["level_enabled"])
            f.script("commitOk", true)
            f.startup()
            assertFalse(f.staged.exists())
            assertEquals(mapOf("gallery_columns" to 5, "compare_history" to "local-only", "level_enabled" to true), f.disk)
            assertEquals(listOf(a, b), f.read(f.pending).tags)
            val commits = f.script("commits")
            f.startup()
            assertEquals("已消费源不应再次提交", commits, f.script("commits"))
            assertEquals(listOf(a, b), f.read(f.pending).tags)
        }
    }

    @Test fun `pending部分原子写失败回滚旧A_媒体IO重试保留A并合入B`() = runBlocking {
        fixture().use { f ->
            seed(f)
            val old = f.pending.readBytes()
            f.script("failWritePath", f.pending.path)
            runCatching { f.restoreFinished() }
            assertTrue("已开始部分写入，不能用未触达写盘的假失败", (f.script("failedBytes") as Int) >= 32)
            assertEquals(1, f.script("pendingFailures"))
            assertArrayEquals("AtomicFile必须保旧pending", old, f.pending.readBytes())
            assertFalse(File(f.pending.path + ".new").exists())
            assertTrue("B仍有唯一可重试源", f.staged.isFile)
            f.script("failWritePath", null)
            assertTrue(f.restoreMedia().isEmpty()) // 尚未授权/媒体未到：只交接，不抹 pending。
            assertFalse(f.staged.exists())
            assertEquals(listOf(a, b), f.read(f.pending).tags)
            assertEquals(5, f.disk["gallery_columns"])
            f.restoreMedia()
            assertEquals(listOf(a, b), f.read(f.pending).tags)
        }
    }

    @Test fun `合法源未交接时备份不得覆盖或finally删除_后续备份先成功交接`() {
        fixture().use { f ->
            seed(f)
            val source = f.staged.readBytes()
            f.script("commitOk", false)
            val aborted = runCatching { f.backup() }
            assertTrue("无法完整交接时必须让本轮备份失败，不能正常返回空快照", aborted.isFailure)
            assertTrue(f.staged.isFile)
            assertArrayEquals(source, f.staged.readBytes())
            assertEquals("持久化失败必须在super备份前中止", 0, f.script("backupCalls"))
            assertEquals(listOf(a), f.read(f.pending).tags)
            f.script("commitOk", true)
            val exported = f.backup()
            assertEquals(listOf(a, b), exported.tags)
            assertEquals(5, exported.settings["gallery_columns"])
            assertFalse(f.staged.exists())
            assertEquals(listOf(a, b), f.read(f.pending).tags)
        }
    }

    @Test fun `损坏输入单独丢弃不碰旧pending_真实读取故障必须留源`() {
        fixture().use { f ->
            seed(f)
            f.staged.writeBytes(byteArrayOf(1, 2, 3))
            runCatching { f.restoreFinished() }
            assertFalse("坏格式不永久阻塞每次启动", f.staged.exists())
            assertEquals(listOf(a), f.read(f.pending).tags)
            assertEquals(0, f.script("commits"))
            f.write(f.staged, incoming)
            f.script("failReadPath", f.staged.path)
            runCatching { f.restoreFinished() }
            assertTrue("IO不可读不能冒充坏格式丢弃合法输入", f.staged.isFile)
            assertEquals(listOf(a), f.read(f.pending).tags)
            f.script("failReadPath", null)
            f.restoreFinished()
            assertEquals(listOf(a, b), f.read(f.pending).tags)
            assertFalse(f.staged.exists())
        }
    }

    @Test fun `损坏的待匹配缓存只丢弃自身_备份仍能完成`() {
        fixture().use { f ->
            f.staged.delete()
            f.pending.parentFile!!.mkdirs()
            f.pending.writeBytes(byteArrayOf(1, 2, 3))
            val exported = f.backup()
            assertFalse("派生缓存损坏不能永久阻断 Auto Backup", f.pending.exists())
            assertTrue(exported.tags.isEmpty())
            assertFalse(f.staged.exists())
        }
    }

    @Test fun `bak恢复源可重试_媒体到齐后只补缺标签不覆盖本机时间`() = runBlocking {
        fixture().use { f ->
            val key = PortableUserData.MediaKey("Movies/", "old.mp4", 123L, 1000L)
            val old = PortableUserData.Tag(key, "like", 10L)
            val custom = PortableUserData.Tag(key, "技巧", 20L)
            f.write(File(f.staged.path + ".bak"), PortableUserData.Snapshot(emptyMap(), listOf(old, custom)))
            f.rows += com.wotagei.cam.media.MediaTag(9L, 7L, BackupPlatformFixture.OLD_PATH, "like", 999L)
            f.restoreMedia()
            assertFalse(File(f.staged.path + ".bak").exists())
            assertEquals(listOf(old, custom), f.read(f.pending).tags)
            assertEquals(2, f.restoreMedia(listOf(f.clip())).size)
            assertFalse(f.pending.exists())
            assertEquals(999L, f.rows.single { it.tag == "like" }.createdAt)
            assertEquals(20L, f.rows.single { it.tag == "技巧" }.createdAt)
            f.restoreMedia(listOf(f.clip()))
            assertEquals(2, f.rows.size)
        }
    }

    @Test fun `R14_两份各六千条合并超限仍先消化旧pending_无死循环`() = runBlocking {
        fixture().use { f ->
            val oldTags = (0 until 6000).map {
                PortableUserData.Tag(PortableUserData.MediaKey("Movies/", "old$it.mp4", 123L, 1000L), "like", it.toLong())
            }
            val newTags = (0 until 6000).map {
                PortableUserData.Tag(PortableUserData.MediaKey("Movies/", "new$it.mp4", 123L, 1000L), "n$it", it.toLong())
            }
            f.write(f.pending, PortableUserData.Snapshot(emptyMap(), oldTags))
            f.write(f.staged, PortableUserData.Snapshot(emptyMap(), newTags))

            val oldClips = (0 until 6000).map {
                f.clip(path = "/storage/emulated/0/Movies/old$it.mp4", name = "old$it.mp4", id = 100L + it)
            }
            assertEquals("旧 pending 的 6000 条必须先落 Room，而不是被合并异常挡住", 6000,
                f.restoreMedia(oldClips).size)
            assertTrue("未交接的新标签仍留在恢复源，供下一轮", f.staged.isFile)
            assertTrue("合并超限时旧 pending 已消化，而不是无限重试", f.pending.exists())
            assertTrue("pending 里不能再有未匹配的旧标签",
                f.read(f.pending).tags.none { it.media.name.startsWith("old") })
            assertEquals(6000, f.rows.size)

            val newClips = (0 until 6000).map {
                f.clip(path = "/storage/emulated/0/Movies/new$it.mp4", name = "new$it.mp4", id = 20_000L + it)
            }
            assertEquals("下一轮把恢复源交接并全部落库", 6000, f.restoreMedia(newClips).size)
            assertFalse("源只在完整交接后才消费", f.staged.exists())
            assertEquals(12_000, f.rows.size)
        }
    }

    @Test fun `R15_新pending静默发布失败不消费恢复源_重试可用`() {
        fixture().use { f ->
            seed(f)
            f.script("silentPublishPath", f.pending.path) // finishWrite 不报错也不发布
            runCatching { f.restoreFinished() }
            assertTrue("新 pending 未发布时恢复源仍有唯一副本", f.staged.isFile)
            assertEquals("旧 pending 必须保持一致", listOf(a), f.read(f.pending).tags)
            f.script("silentPublishPath", null)
            f.restoreFinished()
            assertFalse(f.staged.exists())
            assertEquals(listOf(a, b), f.read(f.pending).tags)
        }
    }

    @Test fun `Y39_同ID旧路径行不算已恢复_新路径标签会替换旧行`() = runBlocking {
        fixture().use { f ->
            f.rows += com.wotagei.cam.media.MediaTag(7L, 7L, BackupPlatformFixture.OLD_PATH, "like", 5L)
            f.write(f.pending, PortableUserData.Snapshot(emptyMap(), listOf(a)))
            val restored = f.restoreMedia(listOf(f.clip(path = BackupPlatformFixture.NEW_PATH, name = "a.mp4")))
            assertEquals(1, restored.size)
            assertEquals("ID 复用 + 旧行时必须按当前媒体路径落库", BackupPlatformFixture.NEW_PATH,
                f.rows.single { it.tag == "like" }.path)
            assertEquals("旧路径的脏行必须被清掉，不能留下同 ID+tag 两行", 1, f.rows.size)
            assertFalse(f.pending.exists())
        }
    }

    @Test fun `Y39_指纹不匹配或可见性不足时pending保留`() = runBlocking {
        fixture().use { f ->
            f.rows += com.wotagei.cam.media.MediaTag(7L, 7L, BackupPlatformFixture.OLD_PATH, "like", 5L)
            f.write(f.pending, PortableUserData.Snapshot(emptyMap(), listOf(a)))
            // 同 ID 的另一条视频（名称不同 ⇒ 指纹不同）不足以消费 pending。
            assertTrue(f.restoreMedia(listOf(f.clip(path = BackupPlatformFixture.NEW_PATH, name = "different.mp4"))).isEmpty())
            assertTrue(f.pending.exists())
            // 部分授权：即使可见集合里有匹配项，也不足以证明同指纹的另一条不存在。
            assertTrue(f.restoreMedia(listOf(f.clip()), visibilityComplete = false).isEmpty())
            assertTrue(f.pending.exists())
            assertEquals(1, f.rows.size)
        }
    }

    @Test fun `Y40_部分可见导出保留不可见行上次确认的指纹`() {
        fixture().use { f ->
            f.rows += com.wotagei.cam.media.MediaTag(7L, 7L, BackupPlatformFixture.OLD_PATH, "like", 10L)
            val complete = f.backup()
            assertEquals(1, complete.tags.size)
            assertEquals(keyA, complete.tags.single().media)
            assertTrue("完整导出必须留下本机核验账本", f.ledger.isFile)

            f.script("denyQuery", true) // 模拟部分授权/卷不可见
            val partial = f.backup()
            assertEquals("不可见行的已有标签必须继续出现在下一份备份里", 1, partial.tags.size)
            assertEquals(keyA, partial.tags.single().media)
            assertEquals("like", partial.tags.single().value)
            f.script("denyQuery", false)
        }
    }

    @Test fun `Y41_导出暂存落盘后进程终止_下次启动不当恢复输入`() {
        fixture().use { f ->
            assertTrue(f.stageExport(PortableUserData.Snapshot(mapOf("gallery_columns" to 9), listOf(a))))
            assertTrue(f.staged.isFile)
            assertTrue(f.exportMarker.isFile)
            // 进程在 finally 清理前退出：没有 cleanupExport，直接进入下一次启动。
            f.startup()
            assertFalse("导出暂存必须按事务标记丢弃", f.staged.exists())
            assertFalse(f.exportMarker.exists())
            assertFalse("本机设置不能被自己的导出快照改写", f.disk.containsKey("gallery_columns"))
            assertFalse("本机标签不能流入 pending", f.pending.exists())
        }
    }

    @Test fun `Y41_陈旧标记不能吞掉真正的系统恢复输入`() {
        fixture().use { f ->
            f.write(f.staged, incoming)
            f.exportMarker.writeText("1:1") // 指纹不匹配 ⇒ 不是本机导出暂存
            f.startup()
            assertFalse(f.staged.exists())
            assertEquals(true, f.disk["level_enabled"])
            assertEquals(listOf(b), f.read(f.pending).tags)
            assertFalse(f.exportMarker.exists())
        }
    }

    @Test fun `R14_条数未超但字节超4MiB时也分批交接_旧pending先消化`() = runBlocking {
        fixture().use { f ->
            val big = "p".repeat(60_000)
            val oldTags = (0 until 60).map {
                PortableUserData.Tag(PortableUserData.MediaKey(big + "/", "old$it.mp4", 123L, 1000L), "like", it.toLong())
            }
            val newTags = (0 until 20).map {
                PortableUserData.Tag(PortableUserData.MediaKey(big + "/", "new$it.mp4", 123L, 1000L), "n$it", it.toLong())
            }
            f.write(f.pending, PortableUserData.Snapshot(emptyMap(), oldTags))
            f.write(f.staged, PortableUserData.Snapshot(emptyMap(), newTags))
            // 80 条远低于 10000 条帽，但每条 60KB 路径会让整体超过 4MiB 字节帽；
            // mergeTags 不会抛，必须按字节余量分批，旧 pending 不能因此永久卡住。
            val oldClips = (0 until 60).map {
                f.clip(path = "/storage/emulated/0/$big/old$it.mp4", name = "old$it.mp4", id = 100L + it,
                    relativePath = big + "/")
            }
            assertEquals(60, f.restoreMedia(oldClips).size)
            assertTrue("未交接的大批量新标签必须留在恢复源", f.staged.isFile)
            val pending = f.read(f.pending).tags
            assertTrue("旧标签必须先消化，不能被字节帽挡住", pending.none { it.media.name.startsWith("old") })
            assertTrue("待匹配缓存只剩本次未交接的新标签", pending.all { it.media.name.startsWith("new") })
            assertEquals(60, f.rows.size)

            // 下一轮再带旧+新媒体：先匹配本机已有缓存，再由源补上仍放不下的新标签，不丢一条。
            val newClips = (0 until 20).map {
                f.clip(path = "/storage/emulated/0/$big/new$it.mp4", name = "new$it.mp4", id = 20_000L + it,
                    relativePath = big + "/")
            }
            val both = oldClips + newClips
            val first = f.restoreMedia(both).size
            val second = f.restoreMedia(both).size
            assertEquals("两轮恢复后 80 条标签必须全部落库", 80, first + second)
            assertFalse("源只在完整交接后才消费", f.staged.exists())
            assertFalse(f.pending.exists())
            assertEquals(80, f.rows.size)
        }
    }

    @Test fun `Y40_部分可见且无历史指纹时中止备份保留上一份`() {
        fixture().use { f ->
            f.rows += com.wotagei.cam.media.MediaTag(7L, 7L, BackupPlatformFixture.OLD_PATH, "like", 10L)
            f.script("mediaAccess", "PARTIAL_READ")
            f.script("denyQuery", true)
            val aborted = runCatching { f.backup() }
            assertTrue("无法核验又无历史指纹时必须让本轮备份失败，不能提交少标签备份", aborted.isFailure)
            assertEquals("中止不能调用系统备份覆盖上一份", 0, f.script("backupCalls"))
            assertFalse("中止不得留下导出快照", f.staged.exists())
            assertFalse("中止不得留下导出标记", f.exportMarker.exists())
            // 用户补全权限后仍能正常备份。
            f.script("denyQuery", false)
            f.script("mediaAccess", "ALL_FILES")
            val exported = f.backup()
            assertEquals(1, exported.tags.size)
            assertEquals(keyA, exported.tags.single().media)
        }
    }

    @Test fun `Y40_卷未挂载时不提交少标签备份_有历史指纹则保留`() {
        fixture().use { f ->
            f.rows += com.wotagei.cam.media.MediaTag(7L, 7L, BackupPlatformFixture.OLD_PATH, "like", 10L)
            f.script("volumesMounted", false)
            f.script("missingQuery", true) // SD/OTG 拔出：行静默消失，查询不报错
            val aborted = runCatching { f.backup() }
            assertTrue("卷未挂载又无历史指纹时不能提交少标签备份", aborted.isFailure)
            assertEquals("中止不能调用系统备份覆盖上一份", 0, f.script("backupCalls"))

            f.script("volumesMounted", true)
            f.script("missingQuery", false)
            assertEquals(1, f.backup().tags.size) // 完整可见时建立已确认指纹账本

            f.script("volumesMounted", false)
            f.script("missingQuery", true)
            val preserved = f.backup()
            assertEquals("卷暂时不可见时，已确认标签必须继续留在下一份备份里", 1, preserved.tags.size)
            assertEquals(keyA, preserved.tags.single().media)
        }
    }

    @Test fun `Y40_完整可见时已删除媒体不靠旧账本复活`() {
        fixture().use { f ->
            f.rows += com.wotagei.cam.media.MediaTag(7L, 7L, BackupPlatformFixture.OLD_PATH, "like", 10L)
            val complete = f.backup()
            assertEquals(1, complete.tags.size)
            assertTrue("完整导出必须留下本机核验账本", f.ledger.isFile)

            f.script("missingQuery", true) // 完整权限下 MediaStore 查无此行 ⇒ 媒体已删除
            val afterDelete = f.backup()
            assertTrue("已删除媒体不能靠上一份账本重新出现在备份里", afterDelete.tags.isEmpty())
            assertFalse("确认删除后旧账本条目应清掉", f.ledger.exists())
        }
    }

    @Test fun `Y41_标记只剩bak时仍丢弃中断导出`() {
        fixture().use { f ->
            assertTrue(f.stageExport(PortableUserData.Snapshot(mapOf("gallery_columns" to 9), listOf(a))))
            val markerBak = File(f.exportMarker.path + ".bak")
            assertTrue("模拟 finishWrite 前被挪到 .bak 的中断标记", f.exportMarker.renameTo(markerBak))
            f.startup()
            assertFalse("只剩 .bak 标记也必须丢弃本机导出", f.staged.exists())
            assertFalse("清理必须连 .bak 一起删", markerBak.exists())
            assertFalse(f.exportMarker.exists())
            assertFalse("本机设置不能被自己的导出快照改写", f.disk.containsKey("gallery_columns"))
            assertFalse("本机标签不能流入 pending", f.pending.exists())
        }
    }

    @Test fun `Y41_无标记时清理AtomicFile临时件且不当恢复输入`() {
        fixture().use { f ->
            val stale = File(f.staged.path + ".new")
            f.write(stale, incoming) // 只有 .new，没有 base/标记/.bak
            f.startup()
            assertFalse("陈旧 .new 必须清理", stale.exists())
            assertFalse(f.staged.exists())
            assertFalse("陈旧临时件不能当恢复输入", f.disk.containsKey("level_enabled"))
            assertFalse(f.pending.exists())
        }
    }

    companion object {
        @ClassRule @JvmField val temporary = TemporaryFolder(File("build/tmp/backup-restore-test").apply { mkdirs() })
        private lateinit var classes: File
        @BeforeClass @JvmStatic fun compilePlatform() { classes = BackupPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
