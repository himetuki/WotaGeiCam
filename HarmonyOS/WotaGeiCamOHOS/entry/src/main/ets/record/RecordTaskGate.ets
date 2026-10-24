/**
 * 长时任务闸（纯逻辑）：录制期间「系统长时任务」的启停状态机。
 *
 * 语义：`onStart/onStop` 返回 **状态是否发生迁移**——true 才做平台动作（start/stop
 * BackgroundRunning），false 表示重复调用（重试段、重复停录）**不许**再碰系统接口：
 * 连续任务重复申请/释放会让通知栏与系统侧状态错乱（上游 CaptureFgService 的守卫同族）。
 *
 * 零平台 import。
 */
export class RecordTaskGate {
  private running: boolean = false;

  /** 起录：false = 已在录（健康重录段不需要新任务） */
  onStart(): boolean {
    if (this.running) {
      return false;
    }
    this.running = true;
    return true;
  }

  /** 停录：false = 本来就没在跑（重复停录/停一个没起过的会话） */
  onStop(): boolean {
    if (!this.running) {
      return false;
    }
    this.running = false;
    return true;
  }

  get active(): boolean {
    return this.running;
  }
}
