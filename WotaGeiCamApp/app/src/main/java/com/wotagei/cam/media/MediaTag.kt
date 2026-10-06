package com.wotagei.cam.media

import android.content.Context
import com.wotagei.cam.R

/**
 * 标签空间：系统保留 tag + 用户自定义 tag（数量不限 —— 需求「标记tag（无数量限制，需要有按分类的标签页）」）。
 * 系统 tag 只有两个，用户自定义 tag 直接存原文，UI 侧「收藏/回收站」页签与自定义页签走同一条过滤链路。
 */
object TagSpace {

    const val LIKE = "like"
    const val TRASH = "trash"

    val SYSTEM = setOf(LIKE, TRASH)

    fun isSystem(tag: String): Boolean = tag in SYSTEM

    fun isCustom(tag: String): Boolean = tag !in SYSTEM

    /** 自定义 tag 合法性：去空白后非空、不含保留词、长度受限（避免药丸换行炸掉布局） */
    fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 24) return null
        if (SYSTEM.any { it.equals(t, ignoreCase = true) }) return null
        return t
    }
}

/** 媒体库数据范围：本应用目录（Movies/WotaGeiCam）与手机相册（全量视频） */
enum class MediaScope { WotaLibrary, AllVideos }

/** 列表形态：时间线 / 网格 / 全屏滑动（需求 29 行「按时间线/网格/全屏滑动查看」） */
enum class ListMode { Timeline, Grid, Fullscreen }

/**
 * 顶部标签页与过滤条件。
 * 页签顺序定版：全部 → 收藏 → 回收站 → 各自定义 tag（按首次使用时间）。
 */
sealed class ClipFilter {
    object All : ClipFilter()
    object Like : ClipFilter()
    object Trash : ClipFilter()
    data class Tagged(val tag: String) : ClipFilter()
}

/** 顶部 ScrollableTabRow 的一项 */
data class GalleryTab(val filter: ClipFilter, val label: String)

object GalleryTabs {

    /** 系统页签文案走资源，自定义页签用用户原文（不是界面文案，无需资源化） */
    fun labelFor(context: Context, filter: ClipFilter): String = when (filter) {
        ClipFilter.All -> context.getString(R.string.gallery_tab_all)
        ClipFilter.Like -> context.getString(R.string.gallery_tab_like)
        ClipFilter.Trash -> context.getString(R.string.gallery_tab_trash)
        is ClipFilter.Tagged -> filter.tag
    }

    fun build(context: Context, customTags: List<String>): List<GalleryTab> {
        val tabs = mutableListOf(
            GalleryTab(ClipFilter.All, labelFor(context, ClipFilter.All)),
            GalleryTab(ClipFilter.Like, labelFor(context, ClipFilter.Like)),
            GalleryTab(ClipFilter.Trash, labelFor(context, ClipFilter.Trash))
        )
        customTags.filter { TagSpace.isCustom(it) }.forEach {
            tabs += GalleryTab(ClipFilter.Tagged(it), labelFor(context, ClipFilter.Tagged(it)))
        }
        return tabs
    }

    /**
     * 页签收窄后的选中态矫正（纯函数，JVM 全表单测；调用方 GalleryScreen 的 `LaunchedEffect(tabs)`）。
     *
     * 【为什么需要】customTags 来自 media_tag 行（按 clip 删/清标签而缩），tabs 随之缩短后：
     * 1. `tabIndex` 越界——显示处 coerce 了高亮，`filter` 却仍指向已消失的 [ClipFilter.Tagged]，
     *    高亮与内容区失配（高亮跳到"回收站"、内容恒空）；
     * 2. index 仍合法但 filter 失效——删掉的是**更靠前**的自定义 tag 时，原 index 落在别的页签上，
     *    高亮看着正常，内容区却在查已删的 tag，恒空且不可自愈。
     * 两条都是"选中态 (index, filter) 与页签表不一致"，统一矫正：夹回 `index.coerceIn(0, lastIndex)`
     * 并同步该页签的 filter。
     *
     * @return 矫正后的 (index, filter)；选中态本来一致（含 tabs 为空的极端）时原样返回
     */
    fun clampedSelection(tabs: List<GalleryTab>, index: Int, filter: ClipFilter): Pair<Int, ClipFilter> {
        if (tabs.isEmpty()) return index to filter
        val i = index.coerceIn(0, tabs.lastIndex)
        val tabFilter = tabs[i].filter
        return if (tabFilter == filter) index to filter else i to tabFilter
    }
}

/** 时间线分组（今天 / 昨天 / 某月某日） */
data class TimelineGroup(val label: String, val dayKey: String, val clips: List<VideoClip>)
