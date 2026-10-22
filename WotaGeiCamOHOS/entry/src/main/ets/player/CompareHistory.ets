/**
 * 双视频对比的「历史」纯核心（直译上游 player/CompareHistory.kt，对齐上游 38d712a）。
 *
 * 上游把「解析/去重/编码」和 SharedPreferences 读写揉在一个 object 里；本侧只落**纯核心**：
 * - entriesOf(raw) / recordEntry(entries, …) / removeEntry(entries, …) / encodeEntries(entries)
 *   —— 全部无平台依赖，raw 就是上游 SharedPreferences 单键里的 JSON 串；
 * - preferences 适配（读 KEY_COMPARE_HISTORY、apply 异步落盘）留待 T25 页面接线批，
 *   届时 `entriesOf(prefs.getString(KEY, undefined))` 与 `putString(KEY, encodeEntries(next))` 即可。
 *
 * 键内容即契约：`[{"rightId":..,"leftId":..,"ts":..}, ...]`，队首最新。
 * T24 差异登记：recordEntry 由调用方显式传 ts（上游内部读 System.currentTimeMillis）——
 * 纯函数可测；调用方传 Date.now() 即与上游等价。recordEntry/removeEntry 一律返回新数组、
 * 不改入参（上游是 prefs 写回，天然不暴露入参变异）。
 *
 * T25 数值容错（登记）：上游 org.json 的 getLong 对数字串宽松解析（"5" → 5，非整数串抛异常
 * 被 runCatching 吞掉）；本侧 entriesOf 对 number/string **统一要求 safe integer**
 * （number 非有限/超安全整数跳过，string 另需匹配 `^-?\d+$`；"5abc"/"5.5"/"" 仍跳过），
 * 与上游 getLong 的接受面对齐到「整数可读、其他坏值跳过」（T25 审查修复：两分支口径一致）。
 */

/** 整数串白名单（ArkTS 禁正则字面量） */
const INT_STRING_RE: RegExp = new RegExp('^-?\\d+$');

/**
 * 历史字段数值读取：number → safe integer；string → 整数串且 safe integer；其余 undefined。
 * 对齐上游 getLong 的宽松数字串解析（见文件头 T25 登记）。
 */
function historyNumberOrUndefined(value: Object): number | undefined {
  if (typeof value === 'number') {
    const n: number = value as number;
    // 与 string 分支同口径：非有限/超安全整数一律跳过（防 JSON.parse 出的畸形大数）
    return Number.isFinite(n) && Number.isSafeInteger(n) ? n : undefined;
  }
  if (typeof value === 'string') {
    const s: string = value as string;
    if (!INT_STRING_RE.test(s)) {
      return undefined;
    }
    const n: number = Number(s);
    return Number.isSafeInteger(n) ? n : undefined;
  }
  return undefined;
}

/** 一条对照记录：右侧视频 rightId 与哪条左侧 leftId 搭过、什么时候（epoch ms） */
export class CompareHistoryEntry {
  readonly rightId: number;
  readonly leftId: number;
  readonly ts: number;

  constructor(rightId: number, leftId: number, ts: number) {
    this.rightId = rightId;
    this.leftId = leftId;
    this.ts = ts;
  }
}

/** 历史纯核心（无平台依赖） */
export class CompareHistory {
  /** SharedPreferences 单键名（上游同值；T25 读 preferences 时复用） */
  static readonly KEY_COMPARE_HISTORY: string = 'compare_history';
  /** 历史上限：低频便利功能，最新 12 条足够回看，多了只拖慢反查 */
  static readonly MAX_ENTRIES: number = 12;

  /**
   * 读全部记录（队首最新）。容错口径：缺键/空串返回空表；整串 JSON 解析失败返回空表；
   * 单条缺字段/类型不对/id 非正数的坏行**跳过不抛**，返回前面已解析成功的行——
   * 历史只是便利性缓存，坏一行不该把整个面板打挂。
   */
  static entriesOf(raw: string | undefined): Array<CompareHistoryEntry> {
    if (raw === undefined || raw.length === 0) {
      return [];
    }
    let parsed: Object;
    try {
      parsed = JSON.parse(raw) as Object;
    } catch (e) {
      return [];
    }
    if (parsed === null || !Array.isArray(parsed)) {
      return [];
    }
    const arr: Array<Object> = parsed as Array<Object>;
    const out: Array<CompareHistoryEntry> = [];
    for (let i: number = 0; i < arr.length; i++) {
      const rowObj: Object = arr[i];
      if (rowObj === null || typeof rowObj !== 'object') {
        continue;
      }
      const row: Record<string, Object> = rowObj as Record<string, Object>;
      const rightNum: number | undefined = historyNumberOrUndefined(row['rightId']);
      const leftNum: number | undefined = historyNumberOrUndefined(row['leftId']);
      const tsNum: number | undefined = historyNumberOrUndefined(row['ts']);
      if (rightNum === undefined || leftNum === undefined || tsNum === undefined) {
        continue;
      }
      const entry: CompareHistoryEntry = new CompareHistoryEntry(rightNum, leftNum, tsNum);
      if (entry.rightId <= 0 || entry.leftId <= 0) {
        continue;
      }
      out.push(entry);
    }
    return out;
  }

  /**
   * 记一次对照。按 rightId 去重：同一右侧视频无论之前搭过谁、记过几次，都只在队首留一条
   * （更新 leftId + ts 后插队首）；新条目同样插队首。超过 MAX_ENTRIES 从队尾截断。
   * rightId <= 0 为 no-op（返回入参的副本）。返回新数组，不改入参。
   */
  static recordEntry(
    entries: Array<CompareHistoryEntry>,
    rightId: number,
    leftId: number,
    ts: number
  ): Array<CompareHistoryEntry> {
    const next: Array<CompareHistoryEntry> = [];
    if (rightId <= 0) {
      for (const e of entries) {
        next.push(e);
      }
      return next;
    }
    next.push(new CompareHistoryEntry(rightId, leftId, ts));
    for (const e of entries) {
      if (e.rightId !== rightId && next.length < CompareHistory.MAX_ENTRIES) {
        next.push(e);
      }
    }
    return next;
  }

  /** 按 rightId 摘掉一条（没有该条即无操作）；返回新数组，不改入参 */
  static removeEntry(entries: Array<CompareHistoryEntry>, rightId: number): Array<CompareHistoryEntry> {
    const next: Array<CompareHistoryEntry> = [];
    for (const e of entries) {
      if (e.rightId !== rightId) {
        next.push(e);
      }
    }
    return next;
  }

  /** 编成 SharedPreferences 单键字符串（键序固定 rightId/leftId/ts，与上游 JSONObject 顺序一致） */
  static encodeEntries(entries: Array<CompareHistoryEntry>): string {
    let out: string = '[';
    for (let i: number = 0; i < entries.length; i++) {
      if (i > 0) {
        out += ',';
      }
      const e: CompareHistoryEntry = entries[i];
      out += `{"rightId":${e.rightId},"leftId":${e.leftId},"ts":${e.ts}}`;
    }
    out += ']';
    return out;
  }
}
