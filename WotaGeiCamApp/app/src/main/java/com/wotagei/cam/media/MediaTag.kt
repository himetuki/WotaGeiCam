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
}

/** 时间线分组（今天 / 昨天 / 某月某日） */
data class TimelineGroup(val label: String, val dayKey: String, val clips: List<VideoClip>)
