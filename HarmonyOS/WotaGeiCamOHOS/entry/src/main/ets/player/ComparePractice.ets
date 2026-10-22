/**
 * 对比页「对着左片练」动线的进程内桥，直译上游 player/ComparePractice.kt（对齐上游 38d712a）。
 *
 * 动线：对比页点「对着左片练」→ primeWith 记下参考片并置 armed，压栈一个新录制页 →
 * 录制页**首次组合**时 takeLeftId 取走参考片（自动切进分屏对比模式）→ 录制停止成功时把新片
 * mediaId 记进 pendingRightId 并弹栈回对比页 → 对比页 ON_RESUME 消费 pendingRightId 填右槽。
 *
 * 自愈：用户没录就退、或录制失败时 armed 仍是真——对比页 ON_RESUME 见 armed 即先清（再收货），
 * 保证不会把「上一次练习的尾巴」错配到「下一次随便录的视频」上；pendingLeftId 由录制页一次性
 * 取走，不依赖自愈。
 *
 * 本批边界（T24）：纯状态语义已直译；**分屏练拍页面未移植，动线接线阻塞**（T25+ 随对比页
 * 接线/录制页分屏模式一起接）。
 */

/** 练习桥状态机（上游 Kotlin object 的进程内单例语义） */
export class ComparePractice {
  /** 对比页发起、录制页待消费的练习意图 */
  armed: boolean = false;
  /** 录制页写、对比页读的新片 mediaId（pending uri 自带 id，commit 前已定） */
  pendingRightId: number | undefined = undefined;
  /** 练习参考片（对比页左路视频）：录制页取走后自动进分屏循环播放 */
  private pendingLeftId: number | undefined = undefined;

  /** 对比页发起练习：记参考片并武装意图（armed 同步置真） */
  primeWith(leftId: number): void {
    this.pendingLeftId = leftId;
    this.armed = true;
  }

  /**
   * 录制页首次组合时取走参考片 id（读后即清：练习模式只在"进录制页那一刻"成立，
   * 之后用户自己切分屏/退出分屏都不该被这条旧意图拉回）。
   */
  takeLeftId(): number | undefined {
    const id: number | undefined = this.pendingLeftId;
    this.pendingLeftId = undefined;
    return id;
  }

  /**
   * 录制页停止成功时调用：armed 为真才记账（普通录制的停止不进这条动线）。
   * @returns true = 本条录制确属练习动线，调用方应弹栈回对比页
   */
  deliver(mediaId: number): boolean {
    if (!this.armed) {
      return false;
    }
    this.armed = false;
    this.pendingRightId = mediaId;
    return true;
  }
}

/** 模块单例（页面侧直接 import 本常量，等价上游 ComparePractice object） */
export const comparePractice: ComparePractice = new ComparePractice();
