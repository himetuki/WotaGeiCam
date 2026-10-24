/**
 * 滑杆吸附开关（上游 app/src/test/java/com/wotagei/cam/SliderSnapTest.kt 全量直译，
 * 对齐上游 0dee86d）：2026-10-21 用户反馈——变焦只能落 0.5/1/1.5…，想调 1.2×/1.7×。
 *
 * 三层被测（与上游同构）：
 * - 持久化编解码（encodeContinuousSliders / decodeContinuousSliders）：缺键=全部吸附、
 *   坏串/未知 id 不炸、编码顺序稳定；
 * - 桥函数 effectiveLadder：开关关掉 → 梯变 null（滑杆退回连续区间），并配突变体自证；
 * - 接线守卫（源码文本扫描，等价上游 KotlinSourceScan）：PillSlider 的 snapKey 声明存在
 *   （上游无默认值必传；ArkTS struct 成员必须给默认值，编译期强制不可得 → 点名守卫）、
 *   四根弹层滑杆逐个点名（EV 整数域显式 null）、设置页组真的写 prefs。
 *
 * 与上游接线臂的差异（登记）：本侧 PillSlider 调用点是 4 处（快门/ISO/EV/色温）而非上游
 * 录制页 10 处（变焦/对焦/斑马/并入门槛等胶囊未进本批）；播放器/设置页并入门槛滑杆
 * 未移植，无对应 null 点名臂。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  SLIDER_KEYS, SliderKey, decodeContinuousSliders, effectiveLadder, encodeContinuousSliders
} from '../../entry/src/main/ets/ui/SliderSnap';

/** 枚举成员名（string enum 无反向映射，键表点名用它拼 `SliderKey.XXX` 检索） */
const KEY_NAMES: Array<string> = ['SHUTTER', 'ISO', 'KELVIN', 'TINT', 'ZOOM', 'DIOPTER', 'PEAKING'];

/**
 * 有消费方的键（弹层滑杆挂了 snapKey 的）：SHUTTER/ISO/KELVIN（T21 三根）+
 * DIOPTER（FC 批对焦弹层）。其余三键（TINT/ZOOM/PEAKING）UI 留白（设置页不列）——
 * TINT 因白平衡副轴平台无等价物不迁（FC 批决策，见 focus-pill.test.ets）。
 */
const CONSUMED_NAMES: Array<string> = ['SHUTTER', 'ISO', 'KELVIN', 'DIOPTER'];

/** 从转译产物目录向上找仓库根（含 entry/src/main/ets/ui/PillPopup.ets 的祖先目录） */
function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/ui/PillPopup.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

/** 去注释（块注释 + 整行行注释）：让接线扫描只看代码，注释里的关键词不算数 */
function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function mainSourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

/** 提取某 struct 声明起的代码段（到下一个顶层文档注释/顶层 struct 边界） */
function structBodyOf(src: string, marker: string): string | undefined {
  const start: number = src.indexOf(marker);
  if (start < 0) {
    return undefined;
  }
  const from: number = start + marker.length;
  const nextDoc: number = src.indexOf('\n/**', from);
  const nextStruct: number = src.indexOf('\nexport struct', from);
  let end: number = src.length;
  if (nextDoc >= 0 && nextDoc < end) {
    end = nextDoc;
  }
  if (nextStruct >= 0 && nextStruct < end) {
    end = nextStruct;
  }
  return src.slice(start, end);
}

function occurrences(text: string, needle: string): number {
  let i: number = text.indexOf(needle);
  let n: number = 0;
  while (i >= 0) {
    n++;
    i = text.indexOf(needle, i + needle.length);
  }
  return n;
}

/** 接线体检（真身与突变体同吃）：返回违规清单，空 = 干净 */
function wiringIssues(popup: string, pills: string, settings: string, store: string): Array<string> {
  const bad: Array<string> = [];

  // 1) 形参：snapKey 声明存在（上游是 Kotlin 无默认值必传；ArkTS struct 成员必须给默认值
  //    null = 恒吸附的安全退化，编译期强制必传不可得，漏挂靠本点名守卫 + 调用点计数兜住）
  if (popup.indexOf('snapKey: SliderKey | null') < 0) {
    bad.push('PillSlider 缺 snapKey 形参（吸附开关没入口）');
  }

  // 2) 控件内部真的读了开关、并把开关应用到梯上
  const slider: string | undefined = structBodyOf(popup, 'export struct PillSlider');
  if (slider === undefined) {
    bad.push('定位不到 PillSlider 结构体');
  } else {
    if (slider.indexOf('sliderContinuousKeys(') < 0) {
      bad.push('PillSlider 没读设置页的吸附开关');
    }
    if (slider.indexOf('effectiveLadder(this.ladder, this.continuous)') < 0) {
      bad.push('PillSlider 读了开关却没用它挑梯');
    }
  }

  // 3) 弹层五根滑杆逐个点名（1 根整数域显式 null，4 根各一次；FC 批加对焦屈光度）
  if (occurrences(pills, 'PillSlider({') !== 5) {
    bad.push('PillSlider 调用点不是 5 处');
  }
  if (occurrences(pills, 'snapKey:') !== 5) {
    bad.push('有滑杆没点名 snapKey');
  }
  if (occurrences(pills, 'snapKey: null') !== 1) {
    bad.push('整数域滑杆（EV）必须显式传 snapKey: null，且恰好 1 处');
  }
  for (const name of KEY_NAMES) {
    const n: number = occurrences(pills, 'snapKey: SliderKey.' + name);
    const expect: number = CONSUMED_NAMES.indexOf(name) >= 0 ? 1 : 0;
    if (n !== expect) {
      bad.push('弹层滑杆 ' + name + ' 的 snapKey 出现 ' + n + ' 次（应恰 ' + expect + ' 次）');
    }
  }

  // 4) 设置页：分组标题存在、写回 prefs、无消费方的键不许进 UI
  if (settings.indexOf('滑杆吸附') < 0) {
    bad.push('设置页缺「滑杆吸附」分组（功能没有入口）');
  }
  if (settings.indexOf('decodeContinuousSliders(') < 0) {
    bad.push('分组没读当前吸附状态（选中态算不出来）');
  }
  if (settings.indexOf('this.settings.sliderContinuous = encodeContinuousSliders(next);') < 0) {
    bad.push('滑杆吸附分组没写回 prefs（点了不生效）');
  }
  for (const name of KEY_NAMES) {
    if (CONSUMED_NAMES.indexOf(name) < 0 && settings.indexOf('SliderKey.' + name) >= 0) {
      bad.push('设置页不许列无消费方的键 ' + name + '（UI 留白）');
    }
  }

  // 5) 存储层：键名 + 解码
  if (store.indexOf("'slider_continuous'") < 0) {
    bad.push('缺 slider_continuous 键');
  }
  if (store.indexOf('decodeContinuousSliders(') < 0) {
    bad.push('SettingsStore 没接连续值集合解码');
  }
  return bad;
}

// ------------------------------------------------------------------ 编解码

test('编码 空集为空串 按枚举声明顺序稳定输出', (): void => {
  assertEq('', encodeContinuousSliders(new Set<SliderKey>()), '空集编码为空串');
  assertEq(
    'shutter,zoom',
    encodeContinuousSliders(new Set<SliderKey>([SliderKey.ZOOM, SliderKey.SHUTTER])),
    '乱序传入仍按枚举声明顺序输出');
  // 同一集合无论传入顺序如何，结果一致（便于人工排查 prefs）
  assertEq(
    encodeContinuousSliders(new Set<SliderKey>([SliderKey.ISO, SliderKey.KELVIN])),
    encodeContinuousSliders(new Set<SliderKey>([SliderKey.KELVIN, SliderKey.ISO])),
    '同一集合恒得到同一串');
});

test('解码 缺键与坏串为空集 未知id忽略 全量往返一致', (): void => {
  assertTrue(decodeContinuousSliders(undefined).size === 0, '缺键=空集=全部吸附');
  assertTrue(decodeContinuousSliders('').size === 0, '空串=空集');
  assertTrue(decodeContinuousSliders('   ').size === 0, '空白串=空集');
  const unknown: Set<SliderKey> = decodeContinuousSliders('no_such_key,zoom');
  assertTrue(unknown.size === 1 && unknown.has(SliderKey.ZOOM), '未知 id（旧/新版本写入的）必须忽略，不许炸');
  assertEq(
    'zoom',
    encodeContinuousSliders(decodeContinuousSliders('zoom')),
    '单键往返一致');
  const all: Set<SliderKey> = new Set<SliderKey>(SLIDER_KEYS);
  const round: Set<SliderKey> = decodeContinuousSliders(encodeContinuousSliders(all));
  assertEq(all.size, round.size, '全量往返规模一致');
  for (const key of SLIDER_KEYS) {
    assertTrue(round.has(key), '全量往返含 ' + (key as string));
  }
  // 容忍空格（人工改 prefs 的常见形态）
  const spaced: Set<SliderKey> = decodeContinuousSliders(' iso , tint ');
  assertTrue(spaced.size === 2 && spaced.has(SliderKey.ISO) && spaced.has(SliderKey.TINT), '容忍逗号两侧空格');
});

test('键表是手写的七个 整数域参数不许混进来', (): void => {
  assertEq(7, SLIDER_KEYS.length, '七个键');
  const ids: Array<string> = SLIDER_KEYS.map((k: SliderKey): string => k as string);
  assertEq(
    'shutter,iso,kelvin,tint,zoom,diopter,peaking',
    ids.join(','),
    'id 串与上游逐字一致（持久化契约，只许追加不许改名）');
});

// ------------------------------------------------------------------ 桥函数

test('桥函数 关掉吸附时梯为null 否则原样同引用返回', (): void => {
  const ladder: Array<number> = [1, 1.5, 2];
  assertTrue(effectiveLadder(ladder, false) === ladder, '吸附开着必须原样用梯（同一引用）');
  assertNull(effectiveLadder(ladder, true), '关掉吸附必须退回连续滑杆');
  // 突变自证：忽略开关的突变体必须在 continuous=true 这一行与真身不同
  const mutant = (l: Array<number>, _c: boolean): Array<number> | null => l;
  assertTrue(
    mutant(ladder, true) !== effectiveLadder(ladder, true),
    '开关被忽略时本尺子必须能红');
});

// ------------------------------------------------------------------ 接线守卫

test('接线 每根滑杆都点名吸附开关 设置页真的写prefs', (): void => {
  const popup: string = mainSourceText('entry/src/main/ets/ui/PillPopup.ets');
  const pills: string = mainSourceText('entry/src/main/ets/ui/ParamPills.ets');
  const settings: string = mainSourceText('entry/src/main/ets/pages/Settings.ets');
  const store: string = mainSourceText('entry/src/main/ets/ui/SettingsStore.ets');
  const issues: Array<string> = wiringIssues(popup, pills, settings, store);
  assertTrue(
    issues.length === 0,
    '滑杆吸附接线发现断点：\n' + issues.join('\n'));
});

test('尺子自己能红 拆开关或漏点名必报红', (): void => {
  const popup: string = mainSourceText('entry/src/main/ets/ui/PillPopup.ets');
  const pills: string = mainSourceText('entry/src/main/ets/ui/ParamPills.ets');
  const settings: string = mainSourceText('entry/src/main/ets/pages/Settings.ets');
  const store: string = mainSourceText('entry/src/main/ets/ui/SettingsStore.ets');
  const someIssue = (issues: Array<string>, needle: string): boolean =>
    issues.some((s: string): boolean => s.indexOf(needle) >= 0);
  assertTrue(
    wiringIssues(popup, pills, settings, store).length === 0,
    '良品必须先真的绿，否则突变体的红没有意义');

  // 突变 1：色温滑杆漏挂 snapKey
  const missingKey: string = pills.replace('snapKey: SliderKey.KELVIN,', '');
  assertTrue(
    someIssue(wiringIssues(popup, missingKey, settings, store), 'snapKey'),
    '漏点名后尺子必须红');
  // 突变 2：PillSlider 读了开关却不应用（梯恒按原样用）
  const ignoresSwitch: string = popup.replace('effectiveLadder(this.ladder, this.continuous)', 'this.ladder');
  assertTrue(
    someIssue(wiringIssues(ignoresSwitch, pills, settings, store), '没用它挑梯'),
    '开关没应用到梯上时必须红');
  // 突变 3：设置页只改内存不落盘
  const noWrite: string = settings.replace(
    'this.settings.sliderContinuous = encodeContinuousSliders(next);', 'next;');
  assertTrue(
    someIssue(wiringIssues(popup, pills, noWrite, store), '不生效'),
    '设置页不写 prefs 时必须红');
  // 突变 4：snapKey 形参整个被摘掉（吸附开关没有入口）
  const dropped: string = popup.replace('snapKey: SliderKey | null = null;', 'snapKeyDropped: SliderKey | null = null;');
  assertTrue(
    someIssue(wiringIssues(dropped, pills, settings, store), '缺 snapKey'),
    'snapKey 形参被摘掉时必须红');
});

test('编码器突变自证 漏掉某个键必须与真身不同', (): void => {
  const keys: Set<SliderKey> = new Set<SliderKey>([SliderKey.ZOOM]);
  const mutant = (_k: Set<SliderKey>): string => '';
  assertFalse(
    mutant(keys) === encodeContinuousSliders(keys),
    '编码器退化成空串时本尺子必须能红');
});
