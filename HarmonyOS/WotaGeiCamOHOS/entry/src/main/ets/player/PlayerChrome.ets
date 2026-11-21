/**
 * 播放页控件 chrome 的**状态矩阵**（上游 player/PlayerChrome.kt，docs/plan/29 §6.3
 * 唯一真源，JVM 真值表测试）。
 *
 * 覆盖层的显隐全部由 [playerChromeOf] 一个出口决定，页面里不许再出现第二份判断：
 * - 顶栏：完整条（返回/文件名/对比/帧率/镜像/旋转 + 收起钮）——只在「控件可见且未收起」时渲染；
 * - 底栏 dock：只在控件可见时渲染（收起顶栏不动 dock）；
 * - 浮动返回：**完整顶栏不可见时恒在**（收起 / 控件隐藏）——它是退出播放页的唯一常驻出口；
 * - 「拉出」钮：只在「顶栏已收起且 dock 可见」时渲染（dock 第一行右端，绿实底），
 *   动作 = 展开顶栏并落盘；控件隐藏时它跟着 dock 一起走。
 */
export class PlayerChrome {
  topBar: boolean;
  dock: boolean;
  floatingBack: boolean;
  pullOut: boolean;

  constructor(topBar: boolean, dock: boolean, floatingBack: boolean, pullOut: boolean) {
    this.topBar = topBar;
    this.dock = dock;
    this.floatingBack = floatingBack;
    this.pullOut = pullOut;
  }
}

export function playerChromeOf(controlsVisible: boolean, topBarCollapsed: boolean): PlayerChrome {
  const topBar: boolean = controlsVisible && !topBarCollapsed;
  return new PlayerChrome(
    topBar,
    controlsVisible,
    !topBar,
    controlsVisible && topBarCollapsed
  );
}
