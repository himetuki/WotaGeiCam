/**
 * 诊断日志（对应上游 diag/Diag.kt 的最小移植：i/w/e/d 四个静态方法）。
 * 上游用 adb logcat -s <tag> 过滤，这里换成 hilog，tag 语义不变。
 */
import { hilog } from '@kit.PerformanceAnalysisKit';

export class Diag {
  static i(tag: string, msg: string): void {
    hilog.info(0x0000, tag, '%{public}s', msg);
  }

  static w(tag: string, msg: string): void {
    hilog.warn(0x0000, tag, '%{public}s', msg);
  }

  static e(tag: string, msg: string): void {
    hilog.error(0x0000, tag, '%{public}s', msg);
  }

  static d(tag: string, msg: string): void {
    hilog.debug(0x0000, tag, '%{public}s', msg);
  }
}
