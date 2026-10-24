/**
 * T27-1 平台探针的页面侧执行器：起 native 探针 → 轮询到终态 → 解读成结论。
 * 结果解读在 record/AvScreenProbeModel（纯逻辑，全测）；本文件只做桥接与轮询。
 * 消费方 = pages/Index.ets 开发者页（默认隐藏入口），真机取证按钮。
 */
import { AvScreenProbeInfo, AvScreenProbeModel } from './AvScreenProbeModel';

const PROBE_WINDOW_MS: number = 2500;
const POLL_MS: number = 250;
/** 轮询上限（探针窗口 2.5s + Init/Start 等待余量；超限按原始串兜底返回） */
const POLL_DEADLINE_MS: number = 20000;

/** 一次性取证：起探针 → 轮询到终态 → 返回解读。桥接失败折 failed，绝不抛 */
export async function runAvScreenProbe(): Promise<AvScreenProbeInfo> {
  let start: (ms: number) => string;
  let status: () => string;
  try {
    // 与 ClipExporter 同款 dynamic import（经 GlLibBridge 静态载 so，顺序契约见该文件头）
    const mod: object = await import('../camera/GlLibBridge');
    const bridge = mod as Record<string, ((v: number) => string) | (() => string)>;
    start = bridge['startAvScreenProbe'] as (ms: number) => string;
    status = bridge['getAvScreenProbeStatus'] as () => string;
  } catch (e) {
    return AvScreenProbeInfo.ofFail('napi-failed');
  }
  if (start === undefined || status === undefined) {
    return AvScreenProbeInfo.ofFail('napi-missing');
  }
  const startError: string = start(PROBE_WINDOW_MS);
  if (startError !== '') {
    return AvScreenProbeInfo.ofFail(startError);
  }
  const deadline: number = Date.now() + POLL_DEADLINE_MS;
  for (;;) {
    const info: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse(status());
    if (info !== undefined) {
      return info;
    }
    if (Date.now() > deadline) {
      return AvScreenProbeInfo.ofFail('poll-timeout');
    }
    await new Promise<void>((resolve: () => void): void => {
      setTimeout((): void => resolve(), POLL_MS);
    });
  }
}

/** 取消进行中的探针（页面销毁兜底；探针自身的窗口上限才是硬收口） */
export function cancelAvScreenProbe(): void {
  import('../camera/GlLibBridge').then((mod: object): void => {
    const bridge = mod as Record<string, (() => void) | undefined>;
    const cancel: (() => void) | undefined = bridge['cancelAvScreenProbe'];
    if (cancel !== undefined) {
      cancel();
    }
  }).catch((_e: Object): void => {
    // 取消失败由窗口上限兜底
  });
}
