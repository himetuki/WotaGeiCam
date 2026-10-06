package com.wotagei.cam.ui

/**
 * 设置页的**分区真源**（纯 Kotlin，无 Compose/Android 依赖，JVM 单测直打）。
 *
 * 设置页改成「左右两栏、每栏内多个功能区块、区块随内容流式变化」后，最怕的是
 * 「哪个区块归哪栏」和「什么宽度走两栏」这两条判定散落在 Compose 里、改一处忘一处：
 * 于是把分区表与宽度判定收成这里的纯函数，UI 只负责按 [layoutPlanOf] 的结果摆位。
 *
 * [SettingsBlock] 的**声明顺序 = 改造前单列自上而下的视觉顺序**，`blocksOf()` 与它恒等；
 * 各栏取子序列时保持这个相对顺序，所以两栏合起来读仍是同一套先后。
 */
enum class SettingsBlock(val id: String) {
    /** 默认档位（帧率/快门/码率/采样率/渲染模式/静音） */
    DEFAULT("default"),

    /** 默认方向（录制页横屏/竖屏） */
    ORIENTATION("orientation"),

    /** 动效（PLAIN/FLUENT/LIQUID） */
    MOTION("motion"),

    /** 取景页显示（读数掩码 + 胶囊掩码 + 位置编辑 + 毛玻璃背板） */
    HUD("hud"),

    /** 存储（MANAGE_EXTERNAL_STORAGE 授权入口） */
    STORAGE("storage"),

    /** 参考线（默认开启的那几种） */
    REFLINE("refline"),

    /** 水平仪（启用 + 震动提示） */
    LEVEL("level"),

    /** 对比播放（编排节拍） */
    COMPARE("compare"),

    /** 文本高度（取景页/弹层/设置页各一套缩放） */
    TEXT("text"),

    /** 权限（相机/麦克风/媒体/蓝牙状态 + 去系统设置） */
    PERM("perm"),

    /** 关于（版本号/包名/检查更新） */
    ABOUT("about"),
}

/** 全部区块，顺序 = [SettingsBlock] 声明顺序 = 改造前的单列视觉顺序 */
fun blocksOf(): List<SettingsBlock> = SettingsBlock.values().toList()

/** 左栏区块（`columnOfBlock` == 0），保持 [blocksOf] 的相对顺序 */
private val LEFT_COLUMN = listOf(
    SettingsBlock.DEFAULT,
    SettingsBlock.ORIENTATION,
    SettingsBlock.MOTION,
    SettingsBlock.HUD,
    SettingsBlock.STORAGE,
)

/** 右栏区块（`columnOfBlock` == 1），保持 [blocksOf] 的相对顺序 */
private val RIGHT_COLUMN = listOf(
    SettingsBlock.REFLINE,
    SettingsBlock.LEVEL,
    SettingsBlock.COMPARE,
    SettingsBlock.TEXT,
    SettingsBlock.PERM,
    SettingsBlock.ABOUT,
)

/** 区块 → 栏号：0 = 左栏，1 = 右栏（表里没登记的区块按右栏处理，但 [layoutPlanOf] 会用两栏表直接摆位，漏登记会被并集断言抓出） */
fun columnOfBlock(block: SettingsBlock): Int = if (block in LEFT_COLUMN) 0 else 1

/**
 * 可用宽度 → 栏数：窄幅退化到单栏，观感与改造前一致。
 * 门限 600dp 是两栏能否放下「TierPicker 一行档位 + 说明」的经验值，**不是机型数值**。
 */
fun settingsColumnCount(availableWidthDp: Int): Int = if (availableWidthDp < 600) 1 else 2

/** 一次布局的落地计划：[columns] 栏数，左/右栏各自的区块序列 */
data class SettingsLayoutPlan(
    val columns: Int,
    val left: List<SettingsBlock>,
    val right: List<SettingsBlock>,
)

/**
 * 桥函数：把「可用宽度」这一运行时输入桥到「具体哪栏放哪些块」的行为上。
 *
 * 单栏时 `right` 为空、`left == blocksOf()`（等价于改造前的整列）；
 * 两栏时 `left + right` 的并集 == `blocksOf()`、无重复，且各自保持 `blocksOf()` 的相对顺序。
 * 这三条是 UI 摆位的全部前提，由 `SettingsLayoutTest` 逐条钉住。
 *
 * 两栏**直接取左右两栏表**（而不是按 [columnOfBlock] 过滤）：这样「某块漏登记」在并集上就是少一项，
 * 能被测试直接抓出；若改用过滤，漏登记的块会被默认归到右栏、静默自洽。
 */
fun layoutPlanOf(availableWidthDp: Int): SettingsLayoutPlan {
    val columns = settingsColumnCount(availableWidthDp)
    return if (columns == 1) {
        SettingsLayoutPlan(columns = 1, left = blocksOf(), right = emptyList())
    } else {
        SettingsLayoutPlan(columns = 2, left = LEFT_COLUMN, right = RIGHT_COLUMN)
    }
}
