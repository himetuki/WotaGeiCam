/**
 * 「该不该抑制转换降级」的纯判定——上游 camera/GlRenderSuppression.kt 直译（零平台 import）。
 *
 * 语义：GPU 路 `degradeArcConvert` 顶部的早退条件。两个抑制窗口，任一为真即抑制
 * （早退、不清账、不换模式）：
 * - [recordTearingDown]：停止序列（置位 → 下次 `setArcConvert` 复位）；
 * - [segmentRotating]：GPU 路分段轮转的重挂窗口——旧编码面已死/将被 release 而 GL 还绑着它，
 *   新面尚未挂好。窗口内的 swap 失败是拆解伪影而非会话真失败；与停止期不同，会话内轮转
 *   不会重发 `setArcConvert`，没有可靠复位点，由重挂回调 mark、await 落定后 clear 成对管理
 *   （不能复用 recordTearingDown 的「下次 setArcConvert 复位」机制）。
 *
 * 窗口内的帧丢掉是可接受的取舍：换段点附近丢几帧，好过降级把换段点之后的成片降成
 * 直通盖 PTS 的慢放（音画渐进漂移）或让位次账缺前半段（MEND→DROP 重建清账）。
 *
 * 本侧接线登记：鸿蒙侧该判定目前在 native `cpp/gl_render.cpp` 的 C++ 分支里，ArkTS 面
 * 没有对应接线点（也没有 Camera2Engine 那套 `syncFpsPick`/`degradeArcConvert` 结构）。
 * 本批只交付纯函数与其全表用例；等 C++ 侧需要共用口径时经 napi 导出再回接。
 */
export function degradeSuppressed(recordTearingDown: boolean, segmentRotating: boolean): boolean {
  return recordTearingDown || segmentRotating;
}
