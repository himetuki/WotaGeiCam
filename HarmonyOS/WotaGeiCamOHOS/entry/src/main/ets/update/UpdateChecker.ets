/**
 * 「检查更新」纯逻辑层——上游 update/UpdateChecker.kt 的直译（对齐上游 971874a/376b3ef）。
 *
 * 本文件**零平台 import**（不出现 @ohos./@kit.）：网络以两个 Promise 函数参数注入，
 * 编排因此可在 test/pure 里全测（假身注入，见 test/pure/update-checker.test.ets）。
 *
 * 与上游的**登记偏离**（鸿蒙打包约束导致，非取舍）：
 * 1. 版本比较口径：HarmonyOS 的 versionName 只接受 `<数字.数字.数字>`（打包工具拒绝 `-rNN`
 *    后缀，见 .github/workflows/build-hap.yml 的 Inject version），r 号进 versionCode。
 *    因此本侧以 **versionCode** 为准比较：远端 tag → `1000000 + major*1e6 + minor*1e4 +
 *    patch*100 + r`，与 CI 的注入公式逐字一致；上游的 versionName 字符串比较仍保留
 *    （`CompareVersions`），供纯逻辑对拍与文档口径。
 * 2. 产物名：鸿蒙资产是 `<tag>-signed-Harmony.hap` / `<tag>-unsigned-Harmony.hap`，
 *    不是安卓的 `-universal-release-Android.apk`；挑选优先 signed（可装；unsigned 仅供
 *    结构校验），见 `ReleaseJson.pickAsset`。
 * 3. 段 3（releases/latest 的 302 → tag）在 OHOS 无可靠等价物：`@ohos.net.http` 自动跟随
 *    重定向且拿不到中间 Location（`maxRedirects` 是 API 23 的「上限」语义，不是「禁用」）。
 *    本侧保留 `RedirectProbe` 与编排里的段 3 位置（注入函数返回 undefined 即跳过），
 *    实际运行只有段 1（官方 API）+ 段 2（镜像代理 API）两段。
 */

/** 仓库与镜像常量（顺序即 fallback 优先级，与上游逐字一致） */
export class UpdateConfig {
  /** 段 1：GitHub API latest release（免登录，公开仓库） */
  static readonly REPO_API_LATEST: string =
    'https://api.github.com/repos/himetuki/WotaGeiCam/releases/latest';

  /** 段 3：releases/latest 页面（302 → /releases/tag/<tag>）——OHOS 侧仅保留位次，见文件头偏离 3 */
  static readonly RELEASES_LATEST_PAGE: string =
    'https://github.com/himetuki/WotaGeiCam/releases/latest';

  /** 下载直连前缀（releases/download/<tag>/<asset>） */
  static readonly DOWNLOAD_BASE: string =
    'https://github.com/himetuki/WotaGeiCam/releases/download';

  /**
   * 镜像前缀（前缀 + 完整原始 URL），全表 HTTPS。明文代理（toolwa.com/github）被排除：
   * 中间人可替换包体。安全模型三层兜底：全表 HTTPS、下载后 SHA-256 端到端校验
   * （哈希只从段 1 官方端点的 Release 说明提取）、安装侧自补校验（见 UpdateInstaller）。
   */
  static readonly MIRROR_PREFIXES: Array<string> = [
    'https://gh-proxy.com/',
    'https://gh-proxy.org/',
    'https://gh.monlor.com/',
    'https://ghproxy.imciel.com/',
    'https://ghproxy.net/'
  ];

  /** 能代理 api.github.com 的镜像（探测段 2 专用；下载能用 ≠ API 能用） */
  static readonly API_MIRROR_PREFIXES: Array<string> = [
    'https://gh-proxy.com/',
    'https://gh-proxy.org/'
  ];

  static directDownloadUrl(tag: string, assetName: string): string {
    return `${UpdateConfig.DOWNLOAD_BASE}/${tag}/${assetName}`;
  }
}

/** 解析后的版本：主版本三段 + r 号（无 r 记 0） */
export class ParsedVersion {
  main: Array<number>;
  r: number;

  constructor(main: Array<number>, r: number) {
    this.main = main;
    this.r = r;
  }
}

/** 版本比较（上游 CompareVersions 直译 + 鸿蒙 versionCode 口径） */
export class CompareVersions {
  /**
   * 远端是否比本机新（true 才提示更新），口径 = versionName 字符串（上游语义）。
   * tag 形如 `v0.0.7-r09`，本机形如 `0.0.7-r09`：去 v 前缀后主版本三段数字比大小；
   * 主版本相同比 r 号（无 r 记 0）。**任一侧解析失败 → false（保守不提示）**。
   */
  static isNewerAvailable(localVersionName: string, remoteTag: string): boolean {
    const cmp: number | undefined = CompareVersions.compare(localVersionName, remoteTag);
    return cmp === undefined ? false : cmp > 0;
  }

  /** 比较：>0 远端新、=0 相同、<0 本机新；任一侧解析失败返回 undefined */
  static compare(localVersionName: string, remoteTag: string): number | undefined {
    const local: ParsedVersion | undefined = CompareVersions.parse(localVersionName);
    const remote: ParsedVersion | undefined = CompareVersions.parse(remoteTag);
    if (local === undefined || remote === undefined) {
      return undefined;
    }
    for (let i = 0; i < 3; i++) {
      if (local.main[i] !== remote.main[i]) {
        return remote.main[i] - local.main[i];
      }
    }
    return remote.r - local.r;
  }

  /**
   * 解析 `v0.0.7-r09` / `0.0.7` / `0.0.7-r02`（上游规则）：
   * v/V 前缀剥掉；主版本必须**恰好三段**纯数字（多段少段都解析失败）；
   * r 段 `-r` 后只允许纯数字（`v0.0.8-beta`、`v0.0.8-r01-x` 一律失败）。
   */
  static parse(version: string): ParsedVersion | undefined {
    let s: string = version.trim();
    if (s.length > 1 && (s.charAt(0) === 'v' || s.charAt(0) === 'V')) {
      s = s.substring(1);
    }
    const rIdx: number = s.indexOf('-r');
    let mainStr: string;
    let r: number;
    if (rIdx >= 0) {
      mainStr = s.substring(0, rIdx);
      const rStr: string = s.substring(rIdx + 2);
      if (!/^[0-9]+$/.test(rStr)) {
        return undefined;
      }
      r = Number.parseInt(rStr, 10);
      if (!Number.isFinite(r)) {
        return undefined;
      }
    } else {
      mainStr = s;
      r = 0;
    }
    const parts: Array<string> = mainStr.split('.');
    if (parts.length !== 3) {
      return undefined;
    }
    const main: Array<number> = [];
    for (const p of parts) {
      if (!/^[0-9]+$/.test(p)) {
        return undefined;
      }
      const v: number = Number.parseInt(p, 10);
      if (!Number.isFinite(v)) {
        return undefined;
      }
      main.push(v);
    }
    return new ParsedVersion(main, r);
  }

  /**
   * tag → 鸿蒙 versionCode（公式与 CI 的 Inject version 逐字一致）：
   * `1000000 + major*1000000 + minor*10000 + patch*100 + r`。
   * 解析失败返回 undefined（保守：不提示更新）。
   */
  static versionCodeOf(tag: string): number | undefined {
    const p: ParsedVersion | undefined = CompareVersions.parse(tag);
    if (p === undefined) {
      return undefined;
    }
    return 1000000 + p.main[0] * 1000000 + p.main[1] * 10000 + p.main[2] * 100 + p.r;
  }

  /**
   * 鸿蒙侧真正的「有没有新版」判据：本地 versionCode（bundleManager 读到的整数）<
   * 远端 tag 折算的 versionCode。tag 不可解析 → false（保守不提示）。
   */
  static isNewerThanVersionCode(localVersionCode: number, remoteTag: string): boolean {
    const remote: number | undefined = CompareVersions.versionCodeOf(remoteTag);
    if (remote === undefined) {
      return false;
    }
    return remote > localVersionCode;
  }
}

/** fallback 链的单个节点：label 供日志与测试点名，build 把原始 GitHub URL 变换成本节点的实际 URL */
export class MirrorNode {
  label: string;
  private build: (rawUrl: string) => string;

  constructor(label: string, build: (rawUrl: string) => string) {
    this.label = label;
    this.build = build;
  }

  urlFor(rawUrl: string): string {
    return this.build(rawUrl);
  }
}

/**
 * 有序 fallback 链状态机（上游同构）：探测段 2 与下载链共用。
 * 失败走 advance() 到下一节点；耗尽（exhausted）后上层判整体失败；reset() 供下次点击从头再试。
 */
export class MirrorChain {
  private nodes: Array<MirrorNode>;
  private index: number = 0;

  constructor(nodes: Array<MirrorNode>) {
    if (nodes.length === 0) {
      throw new Error('fallback 链至少要有一个节点');
    }
    this.nodes = nodes;
  }

  get cursor(): number {
    return this.index;
  }

  get exhausted(): boolean {
    return this.index >= this.nodes.length;
  }

  get currentLabel(): string {
    return this.nodes[this.index].label;
  }

  urlFor(rawUrl: string): string {
    return this.nodes[this.index].urlFor(rawUrl);
  }

  /** 移到下一个节点；已耗尽返回 false */
  advance(): boolean {
    if (this.exhausted) {
      return false;
    }
    this.index++;
    return !this.exhausted;
  }

  reset(): void {
    this.index = 0;
  }

  /** 探测段 2 的镜像链（无直连——直连是段 1 专职；只放确认能代理 api.github.com 的前缀） */
  static probeChain(): MirrorChain {
    const nodes: Array<MirrorNode> = [];
    for (const p of UpdateConfig.API_MIRROR_PREFIXES) {
      nodes.push(new MirrorNode(p, (url: string): string => p + url));
    }
    return new MirrorChain(nodes);
  }
}

/** release 附件（JSON assets[] 里的一枚，或段 3 推导出来的） */
export class ReleaseAsset {
  name: string;
  url: string;
  sizeBytes: number;

  /** sizeBytes < 0 表示 JSON 没给（上游 Long? = null；ArkTS 用 -1 哨兵，调用方见到 -1 当未知） */
  constructor(name: string, url: string, sizeBytes: number) {
    this.name = name;
    this.url = url;
    this.sizeBytes = sizeBytes;
  }
}

/**
 * latest release 的最小结论：tag + 挑出来的 HAP 附件 + 说明原文。
 * sha256 只由**段 1（api.github.com 官方端点）**提取——镜像转发的 body 不可信。
 * notes（说明原文）段 1/段 2 都读（只用于展示，不参与任何判断）。
 */
export class ReleaseInfo {
  tag: string;
  asset: ReleaseAsset | undefined;
  sha256: string | undefined;
  notes: string | undefined;

  constructor(tag: string, asset: ReleaseAsset | undefined, sha256: string | undefined,
    notes: string | undefined) {
    this.tag = tag;
    this.asset = asset;
    this.sha256 = sha256;
    this.notes = notes;
  }
}

/** GitHub releases/latest JSON 的免依赖提取器（上游手写提取器直译，只认关心的键） */
export class ReleaseJson {
  static parseTag(json: string): string | undefined {
    return ReleaseJson.stringValueAfterKey(json, 'tag_name');
  }

  static parseAssets(json: string): Array<ReleaseAsset> {
    const out: Array<ReleaseAsset> = [];
    const arrStart: number = ReleaseJson.indexOfValueAfterKey(json, 'assets', '[');
    if (arrStart < 0) {
      return out;
    }
    const arrEnd: number = ReleaseJson.matchingBracket(json, arrStart, '[', ']');
    if (arrEnd < 0) {
      return out;
    }
    const arr: string = json.substring(arrStart + 1, arrEnd);
    let i: number = 0;
    while (i < arr.length) {
      if (arr.charAt(i) === '{') {
        const end: number = ReleaseJson.matchingBracket(arr, i, '{', '}');
        if (end < 0) {
          break;
        }
        const obj: string = arr.substring(i, end + 1);
        const name: string | undefined = ReleaseJson.stringValueAfterKey(obj, 'name');
        const url: string | undefined = ReleaseJson.stringValueAfterKey(obj, 'browser_download_url');
        const size: number | undefined = ReleaseJson.numberValueAfterKey(obj, 'size');
        if (name !== undefined && url !== undefined) {
          out.push(new ReleaseAsset(name, url, size === undefined ? -1 : size));
        }
        i = end + 1;
      } else {
        i++;
      }
    }
    return out;
  }

  /**
   * 资产名是否可安全拼进下载 URL（上游判据，只把 `.apk` 换成 `.hap`）：
   * 镜像转发的 JSON 不可信，name 可以带路径分隔符/`..`。只接受**纯文件名 + .hap**。
   * 真正的下载 URL 一律由 UpdateConfig.directDownloadUrl 用可信 tag + 校验过的资产名重建。
   */
  static isSafeReleaseAssetName(name: string | undefined): boolean {
    if (name === undefined || name.length < 1 || name.length > 255) {
      return false;
    }
    if (!/\.hap$/i.test(name)) {
      return false;
    }
    if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      return false;
    }
    if (name.indexOf('..') >= 0) {
      return false;
    }
    return true;
  }

  /**
   * 挑 HAP 附件：优先 `-signed-Harmony.hap`（CI 配了签名 Secrets 才产出，真机可装）；
   * 没有则第一个 `.hap`（unsigned 只能做结构校验）；无 .hap 返回 undefined。
   */
  static pickAsset(assets: Array<ReleaseAsset>): ReleaseAsset | undefined {
    const haps: Array<ReleaseAsset> = [];
    for (const a of assets) {
      if (ReleaseJson.isSafeReleaseAssetName(a.name)) {
        haps.push(a);
      }
    }
    for (const a of haps) {
      // 注意用带前导 `-` 的形态：`-unsigned-Harmony.hap` 里也含 `signed`，裸 indexOf('signed') 会误中
      if (a.name.toLowerCase().indexOf('-signed-harmony') >= 0) {
        return a;
      }
    }
    return haps.length > 0 ? haps[0] : undefined;
  }

  /**
   * 说明原文（body）**总是**读出来交给 ReleaseInfo.notes（弹窗展示，段 1/2 都有）；
   * extractSha256=true 时额外从同一段文本提取发版者写入的 universal 包 SHA-256。
   * **哈希只允许段 1 开**：镜像转发的 body 代理可改，拿假哈希当真会把合法更新判废。
   */
  static parse(json: string, extractSha256: boolean): ReleaseInfo | undefined {
    const tag: string | undefined = ReleaseJson.parseTag(json);
    if (tag === undefined) {
      return undefined;
    }
    const body: string | undefined = ReleaseJson.stringValueAfterKey(json, 'body');
    let sha: string | undefined = undefined;
    if (extractSha256 && body !== undefined) {
      sha = ReleaseJson.parseSha256FromNotes(body);
    }
    return new ReleaseInfo(tag, ReleaseJson.pickAsset(ReleaseJson.parseAssets(json)), sha, body);
  }

  /**
   * 从 Release 说明文本提取 SHA-256（纯函数，**上游口径直译，本侧不用于 HAP 校验**）：
   * 找 `SHA256` 后跟冒号（中英文皆容）与空白、再跟**恰好 64** 位十六进制的第一处
   * （尾随不能再是 hex）；命中返回**小写** hex，否则 undefined。
   *
   * 为什么本侧不用它：发版流水线里那行哈希由 build-apk.yml 回填，是**安卓 universal APK**
   * 的校验值；鸿蒙包是另一枚资产，拿 APK 的哈希校验 HAP 必然不匹配。HAP 的哈希改从 CI 另外
   * 产出的 `SHA256SUMS` 直连文件读取（见 [parseSha256Sums] 与 UpdateDownloader 的消费点）。
   */
  static parseSha256FromNotes(notes: string): string | undefined {
    const m: RegExpMatchArray | null =
      notes.match(/SHA256\s*[:：]\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])/);
    if (m === null) {
      return undefined;
    }
    return m[1].toLowerCase();
  }

  /**
   * `SHA256SUMS`（`<64位hex>  <文件名>` 每行一枚，binary 模式的 `*` 前缀容忍）→ 指定资产的哈希。
   * 没有该资产名返回 undefined（老 Release 可能没这个资产 → 退回 PK + size 两道防线）。
   */
  static parseSha256Sums(text: string, assetName: string): string | undefined {
    const normalized: string = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    for (const line of normalized.split('\n')) {
      const t: string = line.trim();
      if (t === '') {
        continue;
      }
      const m: RegExpMatchArray | null = t.match(/^([0-9a-fA-F]{64})\s+\*?(.+)$/);
      if (m !== null && m[2].trim() === assetName) {
        return m[1].toLowerCase();
      }
    }
    return undefined;
  }

  // ------------------------------------------------------------------ 手写 JSON 提取（上游同构）

  /** 键 `"key"` 后第一个字符串值（处理 `\"` 转义）；GitHub 的键固定小写 */
  private static stringValueAfterKey(json: string, key: string): string | undefined {
    const needle: string = `"${key}"`;
    let i: number = json.indexOf(needle);
    while (i >= 0) {
      const v: number = ReleaseJson.valueStartAfterColon(json, i + needle.length);
      if (v >= 0 && json.charAt(v) === '"') {
        return ReleaseJson.readString(json, v);
      }
      i = json.indexOf(needle, i + needle.length);
    }
    return undefined;
  }

  /** 键后的第一个数字值（读连续数字），没有返回 undefined */
  private static numberValueAfterKey(json: string, key: string): number | undefined {
    const needle: string = `"${key}"`;
    let i: number = json.indexOf(needle);
    while (i >= 0) {
      const v: number = ReleaseJson.valueStartAfterColon(json, i + needle.length);
      if (v >= 0) {
        let j: number = v;
        while (j < json.length && json.charAt(j) >= '0' && json.charAt(j) <= '9') {
          j++;
        }
        if (j > v) {
          const n: number = Number.parseInt(json.substring(v, j), 10);
          if (Number.isFinite(n)) {
            return n;
          }
        }
      }
      i = json.indexOf(needle, i + needle.length);
    }
    return undefined;
  }

  /** 数组/对象值开头：键后跳过空白与冒号，返回首个非空白字符的下标（不是值返回 -1） */
  private static valueStartAfterColon(json: string, fromKeyEnd: number): number {
    let j: number = fromKeyEnd;
    while (j < json.length && /\s/.test(json.charAt(j))) {
      j++;
    }
    if (j >= json.length || json.charAt(j) !== ':') {
      return -1;
    }
    j++;
    while (j < json.length && /\s/.test(json.charAt(j))) {
      j++;
    }
    return j < json.length ? j : -1;
  }

  private static indexOfValueAfterKey(json: string, key: string, valueChar: string): number {
    const needle: string = `"${key}"`;
    let i: number = json.indexOf(needle);
    while (i >= 0) {
      const v: number = ReleaseJson.valueStartAfterColon(json, i + needle.length);
      if (v >= 0 && json.charAt(v) === valueChar) {
        return v;
      }
      i = json.indexOf(needle, i + needle.length);
    }
    return -1;
  }

  private static readString(json: string, openQuote: number): string | undefined {
    const sb: Array<string> = [];
    let i: number = openQuote + 1;
    while (i < json.length) {
      const c: string = json.charAt(i);
      if (c === '\\' && i + 1 < json.length) {
        const e: string = json.charAt(i + 1);
        if (e === 'n') {
          sb.push('\n');
        } else if (e === 't') {
          sb.push('\t');
        } else if (e === 'r') {
          sb.push('\r');
        } else {
          sb.push(e);
        }
        i += 2;
      } else if (c === '"') {
        return sb.join('');
      } else {
        sb.push(c);
        i++;
      }
    }
    return undefined;
  }

  /** 配平括号定位（跳过字符串字面量内部）；找不到返回 -1 */
  private static matchingBracket(s: string, open: number, openCh: string, closeCh: string): number {
    let depth: number = 0;
    let i: number = open;
    let inStr: boolean = false;
    while (i < s.length) {
      const c: string = s.charAt(i);
      if (inStr && c === '\\') {
        i++;
      } else if (c === '"') {
        inStr = !inStr;
      } else if (!inStr && c === openCh) {
        depth++;
      } else if (!inStr && c === closeCh) {
        depth--;
        if (depth === 0) {
          return i;
        }
      }
      i++;
    }
    return -1;
  }
}

/**
 * Release 说明 → 更新弹窗里的纯文本（上游 ReleaseNotes 直译，2026-10-21 用户要求）。
 * 只做**去标记、不重排内容**：标题/条目/粗体/行内代码标记去掉，正文一字不改；
 * 机器用的 SHA-256 行（及只有标签的上一行）整行隐去；超 4000 字按行截断补省略号。
 */
export class ReleaseNotes {
  /** 弹窗最多展示的字数（绝对字面量，与上游一致） */
  static readonly MAX_DISPLAY_CHARS: number = 4000;

  private static readonly HASH_LINE: RegExp = /SHA256\s*[:：]\s*[0-9a-fA-F]{64}(?![0-9a-fA-F])/;
  private static readonly HASH_LABEL_ONLY: RegExp = /^.*SHA256\s*[:：]\s*$/;
  private static readonly HEADING: RegExp = /^\s*#{1,6}\s+/;
  private static readonly BULLET: RegExp = /^(\s*)[-*+]\s+/;
  private static readonly RULE: RegExp = /^[-*_]{3,}$/;
  private static readonly CRLF: RegExp = /\r\n/g;
  private static readonly CR: RegExp = /\r/g;
  private static readonly MANY_BLANK: RegExp = /\n{3,}/g;

  /** 说明原文 → 弹窗可展示的纯文本；全空 / 只剩哈希行时返回 undefined（弹窗不显示说明区） */
  static forDisplay(raw: string | undefined): string | undefined {
    if (raw === undefined || raw.trim() === '') {
      return undefined;
    }
    const out: Array<string> = [];
    const normalized: string = raw.replace(ReleaseNotes.CRLF, '\n').replace(ReleaseNotes.CR, '\n');
    for (const line of normalized.split('\n')) {
      const trimmed: string = line.trim();
      if (ReleaseNotes.HASH_LINE.test(trimmed) || ReleaseNotes.HASH_LABEL_ONLY.test(trimmed)) {
        continue;
      }
      if (trimmed === '') {
        out.push('');
        continue;
      }
      if (ReleaseNotes.RULE.test(trimmed)) {
        continue;
      }
      let s: string = line.replace(/\s+$/, '');
      s = s.replace(ReleaseNotes.HEADING, '');
      s = s.replace(ReleaseNotes.BULLET, '$1• ');
      s = s.split('**').join('').split('__').join('').split('`').join('');
      out.push(s);
    }
    const text: string = out.join('\n').replace(ReleaseNotes.MANY_BLANK, '\n\n').trim();
    if (text === '') {
      return undefined;
    }
    return text.length <= ReleaseNotes.MAX_DISPLAY_CHARS
      ? text
      : ReleaseNotes.truncateAtLine(text);
  }

  /** 截断：优先退到行边界（不留半句话）；退得太远（不足一半）就地硬截，末尾补一行省略号 */
  private static truncateAtLine(text: string): string {
    let cut: number = ReleaseNotes.MAX_DISPLAY_CHARS;
    const lineBreak: number = text.lastIndexOf('\n', cut - 1);
    if (lineBreak > Math.floor(cut / 2)) {
      cut = lineBreak;
    }
    let head: string = text.substring(0, cut).replace(/\s+$/, '');
    // 硬截可能落在代理对中间（emoji 被劈成半个），退一格
    if (head.length > 0) {
      const code: number = head.charCodeAt(head.length - 1);
      if (code >= 0xD800 && code <= 0xDBFF) {
        head = head.substring(0, head.length - 1);
      }
    }
    return head + '\n…';
  }
}

/** 段 3：releases/latest 页 302 → tag 解析与资产名推导（OHOS 侧仅保留纯函数，见文件头偏离 3） */
export class RedirectProbe {
  /** `.../releases/tag/v0.0.8-r01`（尾斜杠容忍）→ `v0.0.8-r01`；不是 tag 路径返回 undefined */
  static tagFromLocation(location: string): string | undefined {
    const marker: string = '/releases/tag/';
    const idx: number = location.indexOf(marker);
    if (idx < 0) {
      return undefined;
    }
    let tag: string = location.substring(idx + marker.length);
    if (tag.endsWith('/')) {
      tag = tag.substring(0, tag.length - 1);
    }
    if (tag === '' || tag.indexOf('/') >= 0) {
      return undefined;
    }
    return tag;
  }

  /**
   * 段 3 无 JSON 资产列表，资产名从 tag 推导。鸿蒙 CI 的产物名是
   * `<tag>-unsigned-Harmony.hap`（build-hap.yml），签署版为 `-signed-Harmony.hap`；
   * 推导只能猜恒存在的那一枚。
   */
  static guessAssetName(tag: string): string {
    return `${tag}-unsigned-Harmony.hap`;
  }
}

/** 探测结论的种类（ArkTS 无 sealed interface，用枚举 + 单类承载） */
export enum ProbeKind {
  UPDATE_AVAILABLE = 'update-available',
  UP_TO_DATE = 'up-to-date',
  FAILED = 'failed',
  CANCELLED = 'cancelled'
}

/**
 * 探测结论：UI 只消费这个，不再自己比版本。
 * UPDATE_AVAILABLE 时 refresh 字段有效；其余态字段为空。
 */
export class ProbeResult {
  kind: ProbeKind;
  latestTag: string;
  assetName: string;
  downloadUrl: string;
  /** 探测未给大小 = -1（下载时用 Content-Length） */
  sizeBytes: number;
  /** 段 1 才携带；undefined = 无可信哈希，下载退回 PK 魔数 + size 一致性 + 安装侧校验 */
  sha256: string | undefined;
  /** 段 1/2 都携带（说明原文，弹窗展示时交给 ReleaseNotes.forDisplay） */
  notes: string | undefined;

  private constructor(kind: ProbeKind, latestTag: string, assetName: string, downloadUrl: string,
    sizeBytes: number, sha256: string | undefined, notes: string | undefined) {
    this.kind = kind;
    this.latestTag = latestTag;
    this.assetName = assetName;
    this.downloadUrl = downloadUrl;
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
    this.notes = notes;
  }

  static updateAvailable(latestTag: string, assetName: string, downloadUrl: string,
    sizeBytes: number, sha256: string | undefined, notes: string | undefined): ProbeResult {
    return new ProbeResult(ProbeKind.UPDATE_AVAILABLE, latestTag, assetName, downloadUrl,
      sizeBytes, sha256, notes);
  }

  static upToDate(): ProbeResult {
    return new ProbeResult(ProbeKind.UP_TO_DATE, '', '', '', -1, undefined, undefined);
  }

  static failed(): ProbeResult {
    return new ProbeResult(ProbeKind.FAILED, '', '', '', -1, undefined, undefined);
  }

  static cancelled(): ProbeResult {
    return new ProbeResult(ProbeKind.CANCELLED, '', '', '', -1, undefined, undefined);
  }

  get available(): boolean {
    return this.kind === ProbeKind.UPDATE_AVAILABLE;
  }
}

/** 网络注入契约：任何失败（连不上/非 2xx/超时/超帽）返回 undefined，编排据此快速跳过下一节点 */
export type FetchJson = (url: string) => Promise<string | undefined>;

/** 段 3 注入契约：不跟随重定向时返回 Location；OHOS 平台实现恒 undefined（自动跟随，拿不到中间跳） */
export type FetchRedirectLocation = (url: string) => Promise<string | undefined>;

/**
 * 段 1→2→3 编排（上游 probeLatestRelease 直译，版本判据换成 versionCode）。
 *
 * - 段 1：直连 GitHub API，extractSha256=true（官方端点的说明可信）；
 * - 段 2：镜像前缀逐个代理同一 API，extractSha256=false（镜像可改写 body，宁可不校验）；
 *   说明原文照读（只用于弹窗展示）；
 * - 段 3：302 → tag → 推导资产名（OHOS 注入函数恒 undefined，此段实际跳过）。
 *
 * 取消语义：编排在**每个节点前**检查 isCancelled，置位即跳过剩余节点返回 Cancelled；
 * 节点内部（已发出的请求）不可打断，取消后最坏再等一个探测超时周期。
 */
export async function probeLatestRelease(localVersionCode: number, fetchJson: FetchJson,
  fetchRedirectLocation: FetchRedirectLocation,
  isCancelled: () => boolean = (): boolean => false): Promise<ProbeResult> {
  if (isCancelled()) {
    return ProbeResult.cancelled();
  }
  const direct: ProbeResult | undefined =
    judgeJson(await fetchJson(UpdateConfig.REPO_API_LATEST), localVersionCode, true);
  if (direct !== undefined) {
    return direct;
  }
  const chain: MirrorChain = MirrorChain.probeChain();
  while (!chain.exhausted) {
    if (isCancelled()) {
      return ProbeResult.cancelled();
    }
    const viaMirror: ProbeResult | undefined =
      judgeJson(await fetchJson(chain.urlFor(UpdateConfig.REPO_API_LATEST)), localVersionCode, false);
    if (viaMirror !== undefined) {
      return viaMirror;
    }
    chain.advance();
  }
  if (isCancelled()) {
    return ProbeResult.cancelled();
  }
  const location: string | undefined = await fetchRedirectLocation(UpdateConfig.RELEASES_LATEST_PAGE);
  if (location !== undefined) {
    const tag: string | undefined = RedirectProbe.tagFromLocation(location);
    if (tag !== undefined) {
      const assetName: string = RedirectProbe.guessAssetName(tag);
      if (!CompareVersions.isNewerThanVersionCode(localVersionCode, tag)) {
        return ProbeResult.upToDate();
      }
      return ProbeResult.updateAvailable(tag, assetName,
        UpdateConfig.directDownloadUrl(tag, assetName), -1, undefined, undefined);
    }
  }
  return ProbeResult.failed();
}

/**
 * 一个 JSON 节点的结论：undefined = 该节点无结论（解析失败，或「有新版但拿不到 .hap 附件」）
 * → 继续下一段；非 undefined = 终局结论（UpToDate / UpdateAvailable）。
 */
function judgeJson(json: string | undefined, localVersionCode: number,
  extractSha256: boolean): ProbeResult | undefined {
  if (json === undefined) {
    return undefined;
  }
  const info: ReleaseInfo | undefined = ReleaseJson.parse(json, extractSha256);
  if (info === undefined) {
    return undefined;
  }
  if (!CompareVersions.isNewerThanVersionCode(localVersionCode, info.tag)) {
    return ProbeResult.upToDate();
  }
  const asset: ReleaseAsset | undefined = info.asset;
  if (asset === undefined) {
    return undefined;
  }
  // 镜像 JSON 可能被改写：URL 字段一律不信，用可信仓库常量 + 已校验资产名重建。
  // sha256 恒 undefined：说明里的哈希行是安卓 APK 的，与 HAP 不是同一枚资产；
  // HAP 的校验值由下载层从 `SHA256SUMS` 直连文件解析（见 UpdateDownloader.resolveExpectedSha256）。
  return ProbeResult.updateAvailable(info.tag, asset.name,
    UpdateConfig.directDownloadUrl(info.tag, asset.name), asset.sizeBytes, undefined, info.notes);
}
