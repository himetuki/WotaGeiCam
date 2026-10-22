package com.wotagei.cam.camera

/**
 * 「该不该抑制转换降级」的纯判定（2026-10-07 换段窗口缺陷修复）。
 *
 * [GlRenderEngine.degradeArcConvert] 顶部的早退条件。两个抑制窗口，任一为真即抑制
 * （早退、不清账、不换模式）：
 * - [recordTearingDown]：停止序列（`markRecordTearingDown` 置位 → 下次 `setArcConvert` 复位）；
 * - [segmentRotating]：GPU 路分段轮转的重挂窗口——旧编码面已死/将被 release 而 GL 还绑着它，
 *   新面尚未挂好。窗口内的 swap 失败是拆解伪影而非会话真失败；与停止期不同，会话内轮转
 *   不会重发 `setArcConvert`，没有可靠复位点，由重挂回调 mark、await 落定后 clear 成对管理
 *   （不能复用 recordTearingDown 的「下次 setArcConvert 复位」机制）。
 *
 * 窗口内的帧丢掉是可接受的取舍：换段点附近丢几帧，好过降级把换段点之后的成片降成
 * 直通盖 PTS 的慢放（音画渐进漂移）或让位次账缺前半段（MEND→DROP 重建清账）。
 *
 * 独立成纯函数：配置类判定必须能 JVM 单测全表并测桥本身（突变 `||`→`&&` 必红），
 * 否则删掉运行时的抑制分支测试仍全绿，测不出退化。
 */
fun degradeSuppressed(recordTearingDown: Boolean, segmentRotating: Boolean): Boolean =
    recordTearingDown || segmentRotating
