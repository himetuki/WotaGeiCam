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
            runCatching { f.backup() }
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

    companion object {
        @ClassRule @JvmField val temporary = TemporaryFolder(File("build/tmp/backup-restore-test").apply { mkdirs() })
        private lateinit var classes: File
        @BeforeClass @JvmStatic fun compilePlatform() { classes = BackupPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
