import UIKit
#if canImport(WotaCore)
import WotaCore
#endif

// 播放页控件 chrome 的状态矩阵（对齐 Android `PlayerChrome.kt`）。
//
// 四个覆盖层的显隐全部由 `playerChromeOf` 一个出口决定：
// - 顶栏：只在「控件可见且未收起」时渲染；
// - 底栏 dock：只在控件可见时渲染（收起顶栏不动 dock）；
// - 浮动返回：完整顶栏不可见时恒在（收起 / 控件隐藏）；
// - 「拉出」钮：只在「顶栏已收起且 dock 可见」时渲染。

struct PlayerChrome: Equatable {
    var topBar: Bool
    var dock: Bool
    var floatingBack: Bool
    var pullOut: Bool
}

func playerChromeOf(controlsVisible: Bool, topBarCollapsed: Bool) -> PlayerChrome {
    let topBar = controlsVisible && !topBarCollapsed
    return PlayerChrome(
        topBar: topBar,
        dock: controlsVisible,
        floatingBack: !topBar,
        pullOut: controlsVisible && topBarCollapsed
    )
}
