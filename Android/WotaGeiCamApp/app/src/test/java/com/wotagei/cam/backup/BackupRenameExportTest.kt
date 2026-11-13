package com.wotagei.cam.backup

import com.wotagei.cam.media.MediaTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 与恢复 IO 分开：驱动实际 MediaActions.rename，再用实际 Agent 导出，不降低 path/DATA 身份核对。 */
class BackupRenameExportTest {
    private fun fixture() = BackupPlatformFixture(classes, temporary.newFolder()).apply {
        rows += MediaTag(9L, 7L, BackupPlatformFixture.OLD_PATH, "like", 100L)
        rows += MediaTag(10L, 7L, BackupPlatformFixture.OLD_PATH, "技巧", 200L)
    }

    @Test fun `受控改名成功按真实DATA搬标签_撞名真实名称仍能备份收藏和自定义标签`() = runBlocking {
        fixture().use { f ->
            val result = f.rename(f.clip())
            assertEquals("Done", result!!.javaClass.simpleName)
            assertEquals(listOf(BackupPlatformFixture.NEW_PATH, BackupPlatformFixture.NEW_PATH), f.rows.map { it.path })
            assertEquals(listOf(100L, 200L), f.rows.map { it.createdAt })
            val exported = f.backup()
            assertEquals(listOf("like", "技巧"), exported.tags.map { it.value })
            assertTrue(exported.tags.all { it.media.name == "renamed (1).mp4" })
            assertEquals(listOf(100L, 200L), exported.tags.map { it.createdAt })
        }
    }

    @Test fun `改名拒绝或零行不能迁移标签_原文件收藏仍可导出`() = runBlocking {
        for (denied in listOf(false, true)) fixture().use { f ->
            f.script("updateResult", 0)
            f.script("denyUpdate", denied)
            val result = f.rename(f.clip())
            assertEquals("Message", result!!.javaClass.simpleName)
            assertEquals(0, f.pathMoves)
            assertTrue(f.rows.all { it.path == BackupPlatformFixture.OLD_PATH })
            assertEquals(2, f.backup().tags.size)
        }
    }

    @Test fun `ID复用时改名前真实路径不同不得把旧标签搬给新片_导出仍拒绝旧身份`() = runBlocking {
        fixture().use { f ->
            // UI拿到的旧clip与tag仍是old；Provider中同一ID已复用，必须以改名前实际DATA作条件。
            f.script("path", "/storage/emulated/0/Movies/reused.mp4")
            f.rename(f.clip())
            assertTrue(f.rows.all { it.path == BackupPlatformFixture.OLD_PATH })
            assertTrue(f.backup().tags.isEmpty())
        }
    }

    @Test fun `改名后DATA不可回查不猜路径也不误绑定`() = runBlocking {
        fixture().use { f ->
            f.script("unreadableAfterRename", true)
            f.rename(f.clip())
            assertEquals(0, f.pathMoves)
            assertTrue(f.rows.all { it.path == BackupPlatformFixture.OLD_PATH })
            assertTrue(f.backup().tags.isEmpty())
        }
    }

    @Test fun `实际DAO条件同时约束媒体ID和旧路径且不改schema`() {
        val main = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main"), File(it, "app/src/main"),
                File(it, "Android/WotaGeiCamApp/app/src/main")) }
            .first { File(it, "AndroidManifest.xml").isFile }
        val code = File(main, "java/com/wotagei/cam/media/WotaDb.kt").readText()
        val query = Regex("@Query\\(\"([^\"]+)\"\\)\\s*suspend fun movePathAfterRename\\(")
            .find(code)?.groupValues?.get(1)
        fun guarded(sql: String?): Boolean = sql?.trim()?.replace(Regex("\\s+"), " ") ==
            "UPDATE media_tag SET path = :newPath WHERE mediaId = :mediaId AND path = :oldPath"
        assertTrue("真实运行DAO必须按两份旧身份条件更新，不可只认ID", guarded(query))
        val mutant = requireNotNull(query).replace(" AND path = :oldPath", "")
        assertNotEquals(query, mutant)
        assertFalse("去掉旧路径条件的真实突变必须被同一判据拒绝", guarded(mutant))
        assertTrue(code.contains("version = 1"))
    }

    companion object {
        @ClassRule @JvmField val temporary = TemporaryFolder(File("build/tmp/backup-rename-test").apply { mkdirs() })
        private lateinit var classes: File
        @BeforeClass @JvmStatic fun compilePlatform() { classes = BackupPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
