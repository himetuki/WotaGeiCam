package com.wotagei.cam

import android.app.Instrumentation
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.media.ClipFilter
import com.wotagei.cam.media.GalleryTabs
import com.wotagei.cam.media.MediaActions
import com.wotagei.cam.media.MediaRepo
import com.wotagei.cam.media.MediaScope
import com.wotagei.cam.media.VideoClip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 自定义 tag 全链路取证（#14 分类标签页 / #35 播放器 tag 项的数据半边）。
 *
 * 为什么用代码而不是手点：本机 IME 下 `adb input text` 与 `input keyevent` 都进不了 Compose
 * TextField 的状态（uiautomator 报的是输入法组合区文本，控件里其实是空的），手点添加必然空转。
 *
 * 只碰自录成片，且默认收尾删干净。要留fixture给肉眼验收页签时跑
 * `-e keep true`，看完在弹窗里点 X 删除，别把测试标签留在用户库里。
 */
@RunWith(AndroidJUnit4::class)
class TagFlowTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val keep: Boolean = InstrumentationRegistry.getArguments().getString("keep") == "true"
    private val tagName: String = InstrumentationRegistry.getArguments().getString("tag") ?: "qa-验证"

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, android.os.Bundle().apply { putString("wota", text) })
    }

    @Test
    fun customTagDrivesTabAndFilter() = runBlocking {
        val repo = MediaRepo.get(ctx)
        val actions = MediaActions.get(ctx)
        val clip: VideoClip = repo.clips(MediaScope.WotaLibrary, ClipFilter.All).first().firstOrNull()
            ?: run { line("本机没有自录成片，跳过"); return@runBlocking }
        line("目标 clip=${clip.name} id=${clip.id} 原标签=${clip.tags}")

        val addRes = actions.addTag(clip, tagName)
        line("addTag($tagName) -> $addRes")
        val rows = repo.db().tagDao().observeAll().first().count { it.mediaId == clip.id && it.tag == tagName }
        assertEquals("标签没落库", 1, rows)

        val custom = repo.customTags().first()
        assertTrue("customTags() 没返回新标签：$custom", custom.contains(tagName))

        val tabs = GalleryTabs.build(ctx, custom).map { it.filter }
        assertTrue("页签列表里没有 Tagged($tagName)：$tabs", tabs.contains(ClipFilter.Tagged(tagName)))

        val filtered = repo.clips(MediaScope.WotaLibrary, ClipFilter.Tagged(tagName)).first()
        assertEquals("按标签过滤应只命中这一条", listOf(clip.id), filtered.map { it.id })

        val otherScope = repo.clips(MediaScope.AllVideos, ClipFilter.Tagged(tagName)).first()
        assertEquals("手机相册作用域下同一标签的命中数应一致", listOf(clip.id), otherScope.map { it.id })

        if (keep) {
            line("keep=true，标签留在 id=${clip.id} 上，验收后请手动删除")
            return@runBlocking
        }

        actions.removeTag(clip, tagName)
        val after = repo.db().tagDao().observeAll().first().count { it.mediaId == clip.id && it.tag == tagName }
        assertEquals("删除后仍有残留标签行", 0, after)
        val customAfter = repo.customTags().first()
        assertNotNull("customTags() 返回 null", customAfter)
        assertEquals("没有任何成片引用后，自定义标签应消失", false, customAfter.contains(tagName))
        val tabsAfter = GalleryTabs.build(ctx, customAfter).map { it.filter }
        assertEquals("页签应随之消失", false, tabsAfter.contains(ClipFilter.Tagged(tagName)))
        line("收尾完成：$tagName 已从库中移除")
    }
}
