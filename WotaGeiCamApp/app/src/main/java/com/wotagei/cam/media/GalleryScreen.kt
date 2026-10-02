package com.wotagei.cam.media

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.player.rememberPlayerEngine
import com.wotagei.cam.player.WotaPlayerSurface
import com.wotagei.cam.ui.WotaSettings
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaMenuEntry
import com.wotagei.cam.ui.design.WotaMenuPopup
import com.wotagei.cam.ui.design.WotaOutlineIcon
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaRec
import com.wotagei.cam.ui.theme.WotaSurface
import com.wotagei.cam.ui.theme.WotaSurfaceAlt
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import com.wotagei.cam.ui.anim.LocalMotion

/**
 * 导航路由常量（UI 层 NavHost 照此注册）：
 * - `gallery` 媒体库
 * - `player/{mediaId}` 单视频播放页
 * - `compare?leftMediaId={leftMediaId}` 双视频对比（右视频页内选取）
 */
object WotaNav {
    const val GALLERY = "gallery"
    const val PLAYER = "player/{mediaId}"
    const val PLAYER_ARG = "mediaId"
    const val COMPARE = "compare?leftMediaId={leftMediaId}"
    const val COMPARE_ARG_LEFT = "leftMediaId"

    fun player(mediaId: Long): String = "player/$mediaId"
    fun compare(leftMediaId: Long): String = "compare?leftMediaId=$leftMediaId"
}

/** 页签切换入场位移：比胶囊按下缩放的反馈更明显，但不到半个屏，免得读成「整页在跑」 */
private const val GALLERY_ENTER_SLIDE_DP = 24

/**
 * HDS 子页签容器高：`ohos_id_tab_default_height` = 56vp（Tabs 默认高度，float.json L2016，
 * research/refs/oss/tokens.json size 组）。指示条/文字块在此容器内垂直居中。
 */
private val SUBTAB_CONTAINER_HEIGHT = 56.dp

/**
 * 子页签指示条与文字的间距：`ohos_id_subtab_line_gap` = 8vp（float.json L588，tokens.json 同组）。
 */
private val SUBTAB_LINE_GAP = 8.dp

/**
 * 媒体库页（需求 29 行）：收藏 / 分享 / 删除 / 回收站 / tag（无数量限制 + 分类标签页）
 * 与时间线 / 网格 / 全屏滑动三种形态。
 */
@Composable
fun GalleryScreen(
    onOpen: (Long) -> Unit,
    onCompare: (Long) -> Unit,
    onBack: () -> Unit,
    repo: MediaRepo = rememberMediaRepo(),
    ops: MediaOps = rememberMediaOps()
) {
    val app = LocalContext.current.applicationContext
    // 列数是用户选择，必须跨进程活：以前是 remember(2)，看个视频退回来就重置了
    val prefs = remember(app) { WotaSettings.of(app) }
    var scope by remember { mutableStateOf(MediaScope.WotaLibrary) }
    var mode by remember { mutableStateOf(ListMode.Grid) }
    var gridColumns by remember { mutableStateOf(WotaSettings.galleryColumns(prefs)) }
    var filter by remember { mutableStateOf<ClipFilter>(ClipFilter.All) }
    var tabIndex by remember { mutableStateOf(0) }
    var banner by remember { mutableStateOf<Int?>(null) }
    var menuClip by remember { mutableStateOf<VideoClip?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var purgeIds by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(filter, scope, mode) { selectedIds = emptySet() }

    // 这两条 Flow **必须 remember**：`repo.clips(...)` / `repo.customTags()` 每次调用都造一条新的
    // 冷管道，而 `collectAsState` 的 producer 以 **Flow 实例**为 key —— 不 remember 就等于
    // 「每重组一次就取消并重开一轮 MediaStore + Room 查询」（多选点一下、横幅变一次、切页签都算），
    // 进媒体库那几帧本来就紧，这些 churn 是白送的。键取 (repo, scope, filter)：库是单例，
    // 作用域/页签一变就该换管道，与原来「每次调用新建」的语义等价，只是不再随重组重开。
    val clipsFlow = remember(repo, scope, filter) { repo.clips(scope, filter) }
    val tagsFlow = remember(repo) { repo.customTags() }
    val customTags by tagsFlow.collectState(emptyList())
    val tabs = remember(customTags) { GalleryTabs.build(app, customTags) }
    val clips by clipsFlow.collectState(emptyList())

    DisposableEffect(ops) {
        ops.onMessage = { res -> banner = res }
        onDispose { ops.onMessage = null }
    }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(2600)
            banner = null
        }
    }

    // 屏上没有 M3 Surface/Scaffold 兜底，环境色在根上给一次，裸 Text/Icon 才不会落回黑色
    CompositionLocalProvider(LocalContentColor provides WotaText) {
        Column(
            Modifier
                .fillMaxSize()
                .background(WotaBg)
                .safeDrawingPadding()
        ) {
            GalleryTopBar(
                scope = scope,
                mode = mode,
                gridColumns = gridColumns,
                onScope = { scope = it },
                onMode = { mode = it },
                onColumns = { n ->
                    gridColumns = n
                    prefs.edit().putInt(WotaSettings.KEY_GALLERY_COLUMNS, n).apply()
                },
                onBack = onBack
            )

            GalleryTabStrip(
                tabs = tabs,
                selected = tabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
                onSelect = { i, tab -> tabIndex = i; filter = tab.filter }
            )

            // 页签 / 布局切换：整块内容侧滑淡入。BOM 2023.10.01 落在 foundation 1.5.4，
            // 没有 animateItemPlacement，逐行位移动不了；这里只加 graphicsLayer，
            // 不重建 LazyGrid，免得两副网格同时解码缩略图。
            val motion = LocalMotion.current
            val density = LocalDensity.current
            val enterShift = remember { Animatable(0f) }
            val enterAlpha = remember { Animatable(1f) }
            var lastTabMode by remember { mutableStateOf<Pair<Int, ListMode>?>(null) }
            LaunchedEffect(tabIndex, mode) {
                val prev = lastTabMode
                lastTabMode = tabIndex to mode
                // 进页首帧不入场，否则一打开媒体库就自己闪一下
                if (prev == null) return@LaunchedEffect
                val sign = when {
                    tabIndex > prev.first -> 1f
                    tabIndex < prev.first -> -1f
                    else -> 0f // 只换布局：没有左右语义，退化成纯淡入
                }
                enterShift.snapTo(sign * with(density) { GALLERY_ENTER_SLIDE_DP.dp.toPx() })
                enterAlpha.snapTo(if (sign == 0f) 0.3f else 0.55f)
                launch { enterAlpha.animateTo(1f, motion.float) }
                enterShift.animateTo(0f, motion.float)
            }

            Box(
                Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = enterAlpha.value
                        translationX = enterShift.value
                    }
            ) {
                val isTrash = filter is ClipFilter.Trash
                when (mode) {
                    ListMode.Timeline -> TimelineList(clips, isTrash, onOpen) { menuClip = it }
                    ListMode.Grid -> ClipGrid(
                        clips = clips,
                        columns = gridColumns,
                        isTrash = isTrash,
                        selectedIds = selectedIds,
                        onOpen = onOpen,
                        onMenu = { menuClip = it },
                        onToggleSelect = { c ->
                            selectedIds = if (c.id in selectedIds) selectedIds - c.id else selectedIds + c.id
                        }
                    )
                    ListMode.Fullscreen -> FullscreenBrowse(clips, onOpen, onCompare, ops)
                }
            }

            // 多选条从底边升起。退出动画期间 selectedIds 已清空，动作数据钉在最后一次的
            // 快照上，否则滑出过程中会闪一帧「已选 0 个」。
            val picked = clips.filter { it.id in selectedIds }
            var barSnapshot by remember { mutableStateOf(picked) }
            if (picked.isNotEmpty()) barSnapshot = picked
            AnimatedVisibility(
                visible = picked.isNotEmpty(),
                enter = fadeIn(motion.float) + slideInVertically(motion.offset) { it },
                exit = fadeOut(motion.float) + slideOutVertically(motion.offset) { it }
            ) {
                SelectionBar(
                    count = barSnapshot.size,
                    trashedOnly = barSnapshot.all { it.isTrashed },
                    onShare = { ops.share(barSnapshot) },
                    onLike = { barSnapshot.forEach { ops.setLike(it, true) } },
                    onTrash = { ops.moveToTrash(barSnapshot) },
                    onRestore = { ops.restoreFromTrash(barSnapshot) },
                    onPurge = { purgeIds = barSnapshot.map { it.id }.toSet() },
                    onClear = { selectedIds = emptySet() }
                )
            }

            // 批量彻底删除必须先过我们自己的确认框：改成直删之后没有系统授权框兜着了，
            // 少这一层就是「误触一次 = 不可恢复地删掉整批」（2026-09-27 真机误删过 19 个测试片）
            if (purgeIds.isNotEmpty()) {
                val doomed = clips.filter { it.id in purgeIds }
                AlertDialog(
                    onDismissRequest = { purgeIds = emptySet() },
                    title = { Text(stringResource(R.string.gallery_purge_title_many, doomed.size)) },
                    text = {
                        Text(
                            stringResource(R.string.gallery_purge_body),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            purgeIds = emptySet()
                            ops.deleteForever(
                                doomed,
                                pop = { selectedIds = emptySet() },
                                refresh = { repo.invalidate() }
                            )
                        }) { Text(stringResource(R.string.ok), color = WotaRec) }
                    },
                    dismissButton = {
                        TextButton(onClick = { purgeIds = emptySet() }) { Text(stringResource(R.string.cancel)) }
                    },
                    containerColor = WotaSurface
                )
            }

            // 操作反馈条：出现/2.6s 后消失都走动画。它在 Column 里，出现/消失会占/让高度，
            // 上方 weight(1f) 的内容随之瞬移一帧——expandIn/shrinkOut 是**布局尺寸动画**（#81 第 1 条
            // 定版禁掉），换 alpha+scale（允许档）后瞬移仍在；要免掉得给这行常驻高度槽，观感取舍
            // 留真机看过再定，别悄悄把尺寸动画加回来
            val msg = banner
            var msgSnapshot by remember { mutableStateOf<Int?>(null) }
            if (msg != null) msgSnapshot = msg
            AnimatedVisibility(
                visible = msg != null,
                enter = fadeIn(motion.float) + scaleIn(motion.float, initialScale = 0.9f),
                exit = fadeOut(motion.float) + scaleOut(motion.float, targetScale = 0.9f)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WotaSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) { Text(stringResource(msgSnapshot ?: 0), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    menuClip?.let { clip ->
        CardMenu(clip = clip, ops = ops, onDismiss = { menuClip = null }, onCompare = { onCompare(clip.id) })
    }
}

// region 顶栏

/** 列数按 2→3→4→5→2 循环；越界值（旧持久化数据里的 1 或 6）直接回到首档 */
internal fun nextColumnTier(current: Int): Int {
    val tiers = WotaSettings.GALLERY_COLUMN_TIERS
    val i = tiers.indexOf(current)
    return if (i < 0) tiers.first() else tiers[(i + 1) % tiers.size]
}

@Composable
private fun GalleryTopBar(
    scope: MediaScope,
    mode: ListMode,
    gridColumns: Int,
    onScope: (MediaScope) -> Unit,
    onMode: (ListMode) -> Unit,
    onColumns: (Int) -> Unit,
    onBack: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // M3 1.1.2 的 IconButton / TextButton 会把布局撑到 ≥48dp（不受父约束上限压缩），窄栏只能自绘命中区
        ToolIcon(Icons.Outlined.Close, stringResource(R.string.gallery_desc_back), onBack)
        Text(
            stringResource(R.string.gallery_title),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        ToolChip(
            stringResource(if (scope == MediaScope.WotaLibrary) R.string.gallery_scope_wota else R.string.gallery_scope_album)
        ) { onScope(if (scope == MediaScope.WotaLibrary) MediaScope.AllVideos else MediaScope.WotaLibrary) }
        if (mode == ListMode.Grid) {
            ToolChip(stringResource(R.string.gallery_cols_fmt, gridColumns)) {
                onColumns(nextColumnTier(gridColumns))
            }
        }
        ToolIcon(Icons.Outlined.ViewDay, stringResource(R.string.gallery_mode_timeline)) { onMode(ListMode.Timeline) }
        ToolIcon(Icons.Outlined.GridView, stringResource(R.string.gallery_mode_grid)) { onMode(ListMode.Grid) }
        ToolIcon(Icons.Outlined.Fullscreen, stringResource(R.string.gallery_mode_full)) { onMode(ListMode.Fullscreen) }
    }
}

@Composable
private fun ToolIcon(image: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(image, contentDescription = description, tint = WotaText, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = WotaText,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(WotaSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * 页签条：HDS 子页签（下划线型）。形态依据 `.tmp/hw_chipsgroup-0000001929788350.md`：
 * 子页签是「在一个组件内切换不同页面内容」的切换控件，下划线即其传统形态；胶囊样式归
 * ChipGroup（筛选/可多选语义），且本条页签数随自定义 tag 无上限、文案可长——规范允许
 * 「文本较长时超出屏幕展示区域，同时提供左右滑动」，下划线 + 横向滚动比胶囊组更贴。
 * 数值全部取系统令牌（research/refs/oss/tokens.json，逐条带 float.json/color.json 行号）：
 * 容器高 56vp（[SUBTAB_CONTAINER_HEIGHT]）、指示条高 2vp（ohos_id_subtab_line_height）、
 * 文字↔指示条 8vp（[SUBTAB_LINE_GAP]）、指示条圆角 4vp（ohos_id_corner_radius_subtab）、
 * 选中字/线 = 品牌蓝 #007DFF（ohos_id_color_subtab_text_on / _line_on；作指示条是纯图形，
 * 作文字是 WotaColor.accent 审计过的前景用法）、未选字 = 60% 次级（ohos_id_color_subtab_text_off，
 * 深色 #99FFFFFF 与 WotaColor.textMid 逐值一致）。
 * M3 的 ScrollableTabRow/Tab 把高度钉在 48dp（TabBaselineLayout 内部取容器最小高，压不动），
 * 只能自绘。指示条不做位移/颜色动画（#81 第 1 条：状态即时切换；切页反馈由内容区入场位移承担）。
 */
@Composable
private fun GalleryTabStrip(tabs: List<GalleryTab>, selected: Int, onSelect: (Int, GalleryTab) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val outOfView = listState.layoutInfo.visibleItemsInfo.none { it.index == selected }
        if (tabs.isNotEmpty() && outOfView) listState.animateScrollToItem(selected.coerceIn(0, tabs.size - 1))
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(SUBTAB_CONTAINER_HEIGHT),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(tabs) { i, tab ->
            val active = i == selected
            // #81 第 1 条：文字/指示条颜色即时切换，禁动画
            val labelColor = if (active) WotaColor.accent else WotaColor.textMid
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 140.dp)
                    .clickable { onSelect(i, tab) }
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(SUBTAB_LINE_GAP))
                // 未选中不给指示条（规范只有 _line_on 一档），但保留透明占位，避免选中切换时行高跳动
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) WotaColor.accent else Color.Transparent)
                )
            }
        }
    }
}

// endregion

// region 多选操作条

/**
 * 长按卡片进入多选：批量分享（ACTION_SEND_MULTIPLE）/ 收藏 / 回收站 / 彻底删除。
 *
 * 整批都已在回收站里时（只有「回收站」页签会出现这种选择）动作要换向：给「还原」，
 * 不再摆那颗点了没意义的「移入回收站」。
 */
@Composable
private fun SelectionBar(
    count: Int,
    trashedOnly: Boolean,
    onShare: () -> Unit,
    onLike: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(WotaSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.gallery_selected_count, count),
            style = MaterialTheme.typography.labelMedium,
            color = WotaTextDim,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.gallery_menu_share)) }
        IconButton(onClick = onLike) { Icon(Icons.Outlined.Favorite, contentDescription = stringResource(R.string.gallery_menu_like)) }
        if (trashedOnly) {
            IconButton(onClick = onRestore) { Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.gallery_menu_restore)) }
        } else {
            IconButton(onClick = onTrash) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.gallery_menu_trash)) }
        }
        IconButton(onClick = onPurge) { Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.gallery_menu_purge), tint = WotaRec) }
        TextButton(onClick = onClear) { Text(stringResource(R.string.gallery_select_clear)) }
    }
}

// endregion

// region 时间线

@Composable
private fun TimelineList(clips: List<VideoClip>, isTrash: Boolean, onOpen: (Long) -> Unit, onMenu: (VideoClip) -> Unit) {
    val app = LocalContext.current.applicationContext
    val listState = rememberLazyListState()
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(scrolling) { ThumbLoader.paused = scrolling }
    DisposableEffect(Unit) { onDispose { ThumbLoader.paused = false } }
    if (clips.isEmpty()) {
        EmptyHint(stringResource(if (isTrash) R.string.gallery_trash_empty else R.string.gallery_empty))
        return
    }
    val groups = remember(clips) { groupTimeline(clips, app) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        groups.forEach { g ->
            item(key = "d_${g.dayKey}") {
                Text(
                    g.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = WotaTextDim,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            items(g.clips, key = { "c_${it.id}" }) { clip ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(clip.id) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoThumbnail(clip.uri, Modifier.size(width = 78.dp, height = 58.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(clip.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${formatDuration(clip.durationMs)} · ${formatSize(clip.sizeBytes)}",
                            style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                            color = WotaTextDim
                        )
                        TagPills(clip.tags)
                    }
                    if (clip.liked) Icon(Icons.Outlined.Favorite, contentDescription = stringResource(R.string.gallery_desc_liked), tint = WotaRec, modifier = Modifier.size(16.dp))
                    IconButton(onClick = { onMenu(clip) }) {
                        Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.gallery_desc_menu), tint = WotaTextDim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// endregion

// region 网格

@Composable
private fun ClipGrid(
    clips: List<VideoClip>,
    columns: Int,
    isTrash: Boolean,
    selectedIds: Set<Long>,
    onOpen: (Long) -> Unit,
    onMenu: (VideoClip) -> Unit,
    onToggleSelect: (VideoClip) -> Unit
) {
    val gridState = rememberLazyGridState()
    val scrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
    LaunchedEffect(scrolling) { ThumbLoader.paused = scrolling }
    DisposableEffect(Unit) { onDispose { ThumbLoader.paused = false } }
    if (clips.isEmpty()) {
        EmptyHint(stringResource(if (isTrash) R.string.gallery_trash_empty else R.string.gallery_empty))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(clips, key = { "c_${it.id}" }) { clip ->
            ClipCard(
                clip = clip,
                selected = clip.id in selectedIds,
                onClick = { if (selectedIds.isEmpty()) onOpen(clip.id) else onToggleSelect(clip) },
                onLongClick = { onToggleSelect(clip) },
                onMenu = { onMenu(clip) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(clip: VideoClip, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onMenu: () -> Unit) {
    // 描边层**常驻**（透明占位，保住"挂/卸 border 会改测量路径"那条原诉求），
    // 颜色即时切换（#81 第 1 条：禁动画颜色）——只去掉渐变，层不动。
    // 圆角走件位档 24dp（WotaShape.card = ohos_id_corner_radius_card，Top 8 #2 的本文件落点）
    val borderColor = if (selected) WotaAccent else Color.Transparent
    Box(
        Modifier
            .clip(RoundedCornerShape(WotaShape.card))
            .background(WotaSurface)
            .border(2.dp, borderColor, RoundedCornerShape(WotaShape.card))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column {
            // 16:9 比 4:3 矮一档，同屏能多看一行；缩略图仍是 Crop，不会因为比例变化露出黑边
            VideoThumbnail(clip.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.padding(8.dp)) {
                Text(clip.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatDuration(clip.durationMs),
                    style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    color = WotaTextDim
                )
                TagPills(clip.tags)
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 角标一律自带白描边底衬：纯 tint 图标压在亮画面上会整个看不见（真机截图核对）
            if (clip.liked) WotaOutlineIcon(
                Icons.Outlined.Favorite,
                stringResource(R.string.gallery_desc_liked),
                size = 22.dp,
                glyph = 13.dp,
                ink = WotaRec
            )
            if (clip.isTrashed) WotaOutlineIcon(
                Icons.Outlined.Restore,
                stringResource(R.string.gallery_desc_trashed),
                size = 22.dp,
                glyph = 13.dp
            )
            WotaOutlineIcon(Icons.Outlined.Tune, stringResource(R.string.gallery_desc_menu), onClick = onMenu)
        }
    }
}

/** 卡片操作：收藏 / 对比 / 分享 / 进回收站 / 还原 / 彻底删除 / tag / 重命名 */
@Composable
private fun CardMenu(clip: VideoClip, ops: MediaOps, onDismiss: () -> Unit, onCompare: () -> Unit) {
    val repo = rememberMediaRepo()
    var tagDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var purgeDialog by remember { mutableStateOf(false) }

    // 紧凑菜单弹层替代 AlertDialog：后者 24dp 内边距把 8 项撑出屏幕，「分类标签/重命名/彻底删除」根本点不到
    WotaMenuPopup(
        onDismiss = onDismiss,
        title = clip.name,
        entries = listOf(
            WotaMenuEntry(stringResource(R.string.gallery_menu_like)) { ops.setLike(clip, !clip.liked) },
            WotaMenuEntry(stringResource(R.string.gallery_menu_compare)) { onDismiss(); onCompare() },
            WotaMenuEntry(stringResource(R.string.gallery_menu_share)) { ops.share(listOf(clip)) },
            if (clip.isTrashed) {
                WotaMenuEntry(stringResource(R.string.gallery_menu_restore)) { ops.restoreFromTrash(listOf(clip)); onDismiss() }
            } else {
                WotaMenuEntry(stringResource(R.string.gallery_menu_trash)) { ops.moveToTrash(listOf(clip)); onDismiss() }
            },
            WotaMenuEntry(stringResource(R.string.gallery_menu_tags)) { tagDialog = true },
            WotaMenuEntry(stringResource(R.string.gallery_menu_rename)) { renameDialog = true },
            WotaMenuEntry(stringResource(R.string.gallery_menu_purge), danger = true) { purgeDialog = true }
        )
    )

    if (tagDialog) MediaTagDialog(clip = clip, ops = ops, onDismiss = { tagDialog = false })
    if (renameDialog) RenameDialog(clip = clip, ops = ops, onDismiss = { renameDialog = false })
    if (purgeDialog) {
        AlertDialog(
            onDismissRequest = { purgeDialog = false },
            title = { Text(stringResource(R.string.gallery_purge_title)) },
            text = { Text(stringResource(R.string.gallery_purge_body)) },
            confirmButton = {
                TextButton(onClick = {
                    purgeDialog = false
                    onDismiss()
                    ops.deleteForever(listOf(clip), pop = onDismiss, refresh = { repo.invalidate() })
                }) { Text(stringResource(R.string.ok), color = WotaRec) }
            },
            dismissButton = { TextButton(onClick = { purgeDialog = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = WotaSurface
        )
    }
}

@Composable
private fun RenameDialog(clip: VideoClip, ops: MediaOps, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(clip.name.substringBeforeLast('.', clip.name)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_rename_title)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { ops.rename(clip, text); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = WotaSurface
    )
}

/** tag 增删（无数量限制 —— 需求 29 行） */
@Composable
fun MediaTagDialog(clip: VideoClip, ops: MediaOps, onDismiss: () -> Unit) {
    val repo = rememberMediaRepo()
    val tags by repo.db().tagDao().observeTagsOf(clip.id).collectState(clip.tags.toList())
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_tag_title)) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.gallery_tag_hint), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { ops.addTag(clip, input); input = "" }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.gallery_tag_add), tint = WotaAccent)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val custom = tags.filter { TagSpace.isCustom(it) }
                if (custom.isEmpty()) {
                    Text(stringResource(R.string.gallery_tag_none), style = MaterialTheme.typography.labelSmall, color = WotaTextDim)
                }
                custom.forEach { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tag, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { ops.removeTag(clip, tag) }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.gallery_tag_remove), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        containerColor = WotaSurface
    )
}

// endregion

// region 全屏滑动

/**
 * 全屏滑动浏览（ViewPager2 形态）。
 * 离屏页也会 compose：自动播放必须比对 pagerState.currentPage 与自身 index，离屏只出缩略图、不绑播放器。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullscreenBrowse(
    clips: List<VideoClip>,
    onOpen: (Long) -> Unit,
    onCompare: (Long) -> Unit,
    ops: MediaOps
) {
    if (clips.isEmpty()) {
        EmptyHint(stringResource(R.string.gallery_empty))
        return
    }
    val engine = rememberPlayerEngine()
    // foundation 1.5.4：pageCount 只能由 PagerState 提供（HorizontalPager 的 pageCount 形参已升级为 error）
    val pagerState = rememberPagerState(pageCount = { clips.size })
    val page = pagerState.currentPage
    val playing by engine.isPlaying.collectState(false)
    val pos by engine.positionMs.collectState(0L)
    val dur by engine.durationMs.collectState(0L)

    LaunchedEffect(page, clips) {
        val clip = clips.getOrNull(page) ?: return@LaunchedEffect
        engine.attach(clip.uri)
        engine.softPause(false)
    }
    DisposableEffect(Unit) { onDispose { engine.releasePlayer() } }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { index ->
        val clip = clips.getOrNull(index) ?: return@HorizontalPager
        val isCurrent = page == index
        Box(
            Modifier
                .fillMaxSize()
                .background(WotaBg)
        ) {
            if (isCurrent) {
                WotaPlayerSurface(engine = engine, modifier = Modifier.fillMaxSize())
            } else {
                VideoThumbnail(clip.uri, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (isCurrent) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { engine.softPause(playing) }) {
                        Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.player_play_pause))
                    }
                    Text("${formatDuration(pos)} / ${formatDuration(dur)}", style = MonoStyle, color = WotaText)
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { ops.setLike(clip, !clip.liked) }) {
                        Icon(
                            if (clip.liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            // 标签必须跟着状态走：原来恒报「已收藏」，未收藏的成片读屏也说成已收藏
                            contentDescription = stringResource(
                                if (clip.liked) R.string.gallery_desc_liked else R.string.gallery_desc_unliked
                            )
                        )
                    }
                    IconButton(onClick = { ops.share(listOf(clip)) }) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.gallery_menu_share)) }
                    IconButton(onClick = { ops.moveToTrash(listOf(clip)) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.gallery_menu_trash)) }
                    if (clip.isTrashed) {
                        IconButton(onClick = { ops.restoreFromTrash(listOf(clip)) }) {
                            Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.gallery_menu_restore))
                        }
                    }
                    IconButton(onClick = { onCompare(clip.id) }) { Icon(Icons.Outlined.CompareArrows, contentDescription = stringResource(R.string.gallery_menu_compare)) }
                    IconButton(onClick = { onOpen(clip.id) }) { Icon(Icons.Outlined.Fullscreen, contentDescription = stringResource(R.string.gallery_open_player)) }
                }
            }
        }
    }
}

// endregion

// region 选择器（对比页右视频）

/**
 * 视频选择器：内置媒体库 / 手机相册两个 tab，网格缩略图，点选回 [VideoClip]。
 */
@Composable
fun ClipPickerDialog(title: String, onPick: (VideoClip) -> Unit, onDismiss: () -> Unit) {
    val repo = rememberMediaRepo()
    var scope by remember { mutableStateOf(MediaScope.WotaLibrary) }
    val wotaLabel = stringResource(R.string.gallery_scope_wota)
    val albumLabel = stringResource(R.string.gallery_scope_album)
    val tabLabels = listOf(wotaLabel, albumLabel)
    var tabIndex by remember { mutableStateOf(0) }
    val clips by repo.clips(scope, ClipFilter.All).collectState(emptyList())
    val gridState = rememberLazyGridState()
    val scrolling by remember { derivedStateOf { gridState.isScrollInProgress } }
    LaunchedEffect(scrolling) { ThumbLoader.paused = scrolling }
    DisposableEffect(Unit) { onDispose { ThumbLoader.paused = false } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabLabels.forEachIndexed { i, label ->
                        TextButton(onClick = { tabIndex = i; scope = if (i == 0) MediaScope.WotaLibrary else MediaScope.AllVideos }) {
                            Text(label, color = if (i == tabIndex) WotaAccent else WotaTextDim)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (clips.isEmpty()) {
                    Text(stringResource(R.string.gallery_empty), color = WotaTextDim, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(clips, key = { "p_${it.id}" }) { clip ->
                            Box(Modifier.clickable { onPick(clip) }) {
                                VideoThumbnail(clip.uri, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = WotaSurface
    )
}

// endregion

// region 通用组件

/** tag 药丸：最多显示 max 个 + 计数（自定义 tag 本身无数量上限） */
@Composable
fun TagPills(tags: Set<String>, max: Int = 2) {
    val custom = tags.filter { TagSpace.isCustom(it) }
    if (custom.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        custom.take(max).forEach { tag ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(WotaSurfaceAlt)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) { Text(tag, style = MaterialTheme.typography.labelSmall, color = WotaTextDim, maxLines = 1) }
        }
        if (custom.size > max) {
            Text("+${custom.size - max}", style = MaterialTheme.typography.labelSmall, color = WotaTextDim)
        }
    }
}

/**
 * 缩略图：ContentResolver.loadThumbnail 优先，失败回退 MediaMetadataRetriever（见 [ThumbLoader]）。
 * 并发限 2；列表滑动中暂停新请求。
 */
@Composable
fun VideoThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    px: Int = 512,
    contentScale: ContentScale = ContentScale.Crop
) {
    val app = LocalContext.current.applicationContext
    var bitmap by remember(uri, px) { mutableStateOf(ThumbLoader.peek(uri, px)) }
    LaunchedEffect(uri, px) {
        if (bitmap == null) bitmap = ThumbLoader.load(app, uri, px)
    }
    Box(modifier.background(WotaSurfaceAlt), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(Modifier.size(18.dp), color = WotaAccent, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = WotaTextDim)
    }
}

/** Flow → State（屏内少写一遍初值类型） */
@Composable
private fun <T> Flow<T>.collectState(initial: T) = collectAsState(initial)

// endregion
