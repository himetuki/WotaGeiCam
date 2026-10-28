package com.wotagei.cam.player

/**
 * 播放页控件 chrome 的**状态矩阵**（docs/plan/29 §6.3 的唯一真源，JVM 真值表测试）。
 *
 * 四个覆盖层的显隐全部由 [playerChromeOf] 一个出口决定，Compose 里不许再出现第二份判断：
 * - 顶栏：完整条（7 枚钮 + 文件名 + 收起钮）——只在「控件可见且未收起」时渲染；
 * - 底栏 dock：只在控件可见时渲染（收起顶栏不动 dock）；
 * - 浮动返回：**完整顶栏不可见时恒在**（收起 / 控件隐藏）——它是退出播放页的唯一常驻出口，
 *   统一成透明浮动款后不再区分「收起」与「控件隐藏」两种外观；
 * - 「拉出」钮：只在「顶栏已收起且 dock 可见」时渲染（dock 第一行右端，绿实底），
 *   动作 = 展开顶栏并落盘；控件隐藏时它跟着 dock 一起走。
 */
data class PlayerChrome(
    val topBar: Boolean,
    val dock: Boolean,
    val floatingBack: Boolean,
    val pullOut: Boolean
)

fun playerChromeOf(controlsVisible: Boolean, topBarCollapsed: Boolean): PlayerChrome {
    val topBar = controlsVisible && !topBarCollapsed
    return PlayerChrome(
        topBar = topBar,
        dock = controlsVisible,
        floatingBack = !topBar,
        pullOut = controlsVisible && topBarCollapsed
    )
}
