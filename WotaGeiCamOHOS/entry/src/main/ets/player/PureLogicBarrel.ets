/**
 * 纯逻辑模块编译守卫（T25 P3-1）。
 *
 * hvigor 只编译从页面可达的文件——T24 新增的 AudioSync/CompareAudio/CompareHistory/
 * ComparePractice 在没有页面消费之前不会进 abc，类型错误要到 T25 接线时才暴露。
 * 本汇总文件在 EntryAbility.onCreate 调用 signature()，把这四个模块拉进 ArkTS program，
 * 让编译门覆盖它们（并顺带在启动日志留一行可核对签名）。
 *
 * 注意：本文件只做**引用**，不改变任何模块语义；不 import 平台 Kit。
 */
import { AudioSync } from './AudioSync';
import { AudioTrackKind, Side, selectionEffectOf } from './CompareAudio';
import { CompareHistory } from './CompareHistory';
import { comparePractice } from './ComparePractice';

/** 四模块的编译/启动签名（只读常量与纯函数，无副作用） */
export class PureLogicBarrel {
  /**
   * 返回一行签名：采样率/历史上限/左音量/右音量/练习桥 armed。
   * 期望（默认态）：`sr=8000 max=12 vol=1/0 armed=false`。
   */
  static signature(): string {
    const effect = selectionEffectOf(Side.LEFT, AudioTrackKind.ENV, true);
    return `sr=${AudioSync.TARGET_SR} max=${CompareHistory.MAX_ENTRIES} ` +
      `vol=${effect.leftVolume}/${effect.rightVolume} armed=${comparePractice.armed}`;
  }
}
