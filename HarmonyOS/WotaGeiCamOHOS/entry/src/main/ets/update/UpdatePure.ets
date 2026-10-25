/**
 * 下载器的**纯逻辑判据**——上游 update/UpdateDownloader.kt 里可 JVM 全测的部分直译
 * （测速排序、URL 安全门槛、响应体上限、过期文件判据、SHA-256 判据），零平台 import。
 *
 * 平台真身（@ohos.net.http 流式下载、fileIo 落盘）在同目录 UpdateDownloader.ets，
 * 只调用这里的判据；test/pure/update-download.test.ets 直引本文件。
 */

/** 单个渠道的测速结论：url + 实测字节/秒（0 = 连不上 / 非包体响应 / 超预算） */
export class ChannelSpeed {
  url: string;
  bytesPerSec: number;

  constructor(url: string, bytesPerSec: number) {
    this.url = url;
    this.bytesPerSec = bytesPerSec;
  }
}

/** 解析出的 URL 关键字段（只认本模块需要的三件，不引平台 URL 实现） */
export class ParsedUrl {
  protocol: string;
  host: string;
  /** authority 里 `@` 之前有内容（userinfo 不能绕过主机判据） */
  hasUserInfo: boolean;

  constructor(protocol: string, host: string, hasUserInfo: boolean) {
    this.protocol = protocol;
    this.host = host;
    this.hasUserInfo = hasUserInfo;
  }
}

/** 下载链的纯判据集合（与上游 UpdateDownloader 的 internal 判据逐条对齐） */
export class UpdateDownloadPure {
  /** 每渠道测速最多读的字节数（256KiB，绝对字面量） */
  static readonly SPEED_TEST_BYTES: number = 262144;
  /** 测速的连接超时：测速不是下载，连不上就认输 */
  static readonly SPEED_TEST_CONNECT_TIMEOUT_MS: number = 4000;
  /** 测速的读超时：单次 read 阻塞上限 */
  static readonly SPEED_TEST_READ_TIMEOUT_MS: number = 3000;
  /** 测速单渠道的整段预算（各渠道并发跑，整体等待 ≈ 这个值） */
  static readonly SPEED_TEST_BUDGET_MS: number = 6000;

  /** 探测响应体上限（GitHub JSON 实际几十 KB，放宽到 4MiB；镜像不可信，无帽即 OOM） */
  static readonly MAX_PROBE_BYTES: number = 4 * 1024 * 1024;

  /**
   * 包体下载上限：ohos.net.http 的 `maxLimit` 硬上限是 100MiB（平台文档值），
   * 与上游 2GiB 不同（登记偏离）。HAP 实际体量远小于此。
   */
  static readonly MAX_PACKAGE_BYTES: number = 100 * 1024 * 1024;

  /** Content-Length 头判据：**未知长度（<=0）放行**、交给限流读取兜住；显式声明超帽即拒 */
  static probeBytesAllowed(contentLength: number): boolean {
    return contentLength <= 0 || contentLength <= UpdateDownloadPure.MAX_PROBE_BYTES;
  }

  /** 限流读取的越帽判定：累计已读是否超出 limit */
  static bytesOverLimit(totalRead: number, limit: number): boolean {
    return totalRead > limit;
  }

  /**
   * 下载候选（直连 + 各镜像前缀）：顺序 = 测速前的兜底顺序（直连优先、镜像按配置表序）。
   * 纯函数——「直连一定在第一位」「每个镜像恰好出现一次」都能单测钉死。
   */
  static downloadCandidates(directUrl: string, mirrorPrefixes: Array<string>): Array<string> {
    const out: Array<string> = [directUrl];
    for (const p of mirrorPrefixes) {
      out.push(p + directUrl);
    }
    return out;
  }

  /**
   * 测速排序：实测速度降序；速度 <= 0 的渠道**垫底但不丢**（保持输入顺序）。
   * 全部测速失败时输出与输入完全一致（回到「直连优先 + 镜像表序」的旧 fallback）；
   * 同速保持输入顺序（Array.sort 在 ES2019 起稳定）。
   */
  static rankChannelsBySpeed(speeds: Array<ChannelSpeed>): Array<string> {
    const fast: Array<ChannelSpeed> = [];
    const rest: Array<ChannelSpeed> = [];
    for (const s of speeds) {
      if (s.bytesPerSec > 0) {
        fast.push(s);
      } else {
        rest.push(s);
      }
    }
    fast.sort((a: ChannelSpeed, b: ChannelSpeed): number => b.bytesPerSec - a.bytesPerSec);
    const out: Array<string> = [];
    for (const s of fast) {
      out.push(s.url);
    }
    for (const s of rest) {
      out.push(s.url);
    }
    return out;
  }

  /**
   * 下载目录的过期文件判据：删 `.part` 半截文件与非本轮资产名的文件；
   * **保留本轮 asset 的已完成 HAP**（安装器/保存流程可能还在读它）。
   */
  static staleFilesOf(dirNames: Array<string>, assetName: string): Array<string> {
    const out: Array<string> = [];
    for (const n of dirNames) {
      if (n.indexOf('.part') >= 0 || n !== assetName) {
        out.push(n);
      }
    }
    return out;
  }

  /**
   * 校验判据：expectedSha256 为 undefined（探测没给可信哈希：段 2/3 或旧版说明）恒放行
   * ——退回既有防线（PK 魔数 + size 一致性 + 安装侧校验）；给了则必须与实际哈希
   * **忽略大小写**相等。
   */
  static sha256Matches(expectedSha256: string | undefined, actualHex: string): boolean {
    if (expectedSha256 === undefined) {
      return true;
    }
    return expectedSha256.toLowerCase() === actualHex.toLowerCase();
  }

  /** 只允许 HTTPS（镜像前缀或未来改表都不会悄悄降级成明文） */
  static isHttpsUrl(url: string): boolean {
    const parsed: ParsedUrl | undefined = UpdateDownloadPure.parseUrl(url);
    return parsed !== undefined && parsed.protocol === 'https';
  }

  /**
   * 请求目标的安全判据：只允许 https、必须有主机名，且拒绝 localhost / 环回 / 私有 /
   * 链路本地 / 保留地址的**字面量 IP**。userinfo 不能绕过（`https://user:pw@127.0.0.1` 判 127.0.0.1）。
   * 域名不做 DNS 预解析（DNS rebinding 的坑），真正的门是 HTTPS 证书校验。
   */
  static isSafeRemoteUrl(url: string): boolean {
    const parsed: ParsedUrl | undefined = UpdateDownloadPure.parseUrl(url);
    if (parsed === undefined || parsed.protocol !== 'https') {
      return false;
    }
    return UpdateDownloadPure.isPublicHost(parsed.host);
  }

  /** 主机名判据：localhost / 环回 / 私有 / 链路本地 / 保留地址 → false */
  static isPublicHost(host: string): boolean {
    let h: string = host.trim().toLowerCase();
    while (h.endsWith('.')) {
      h = h.substring(0, h.length - 1);
    }
    if (h === '') {
      return false;
    }
    if (h === 'localhost' || h.endsWith('.localhost')) {
      return false;
    }
    if (h.startsWith('[') || h.indexOf(':') >= 0) {
      return UpdateDownloadPure.isPublicIpv6(h);
    }
    return UpdateDownloadPure.isPublicIpv4OrDomain(h);
  }

  /**
   * 解析 `scheme://[userinfo@]host[:port][/path...]`（纯字符串扫描，不依赖 URL 实现）。
   * 不合格（无 scheme / 无 authority / IPv6 未闭合）返回 undefined。
   */
  static parseUrl(raw: string): ParsedUrl | undefined {
    const schemeEnd: number = raw.indexOf('://');
    if (schemeEnd <= 0) {
      return undefined;
    }
    const protocol: string = raw.substring(0, schemeEnd).toLowerCase();
    const afterScheme: string = raw.substring(schemeEnd + 3);
    let end: number = afterScheme.length;
    for (let i = 0; i < afterScheme.length; i++) {
      const c: string = afterScheme.charAt(i);
      if (c === '/' || c === '?' || c === '#') {
        end = i;
        break;
      }
    }
    const authority: string = afterScheme.substring(0, end);
    if (authority === '') {
      return undefined;
    }
    const at: number = authority.lastIndexOf('@');
    const hasUserInfo: boolean = at >= 0;
    const hostPort: string = hasUserInfo ? authority.substring(at + 1) : authority;
    let host: string;
    if (hostPort.startsWith('[')) {
      const close: number = hostPort.indexOf(']');
      if (close < 0) {
        return undefined;
      }
      host = hostPort.substring(1, close);
    } else {
      const colon: number = hostPort.indexOf(':');
      host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
    }
    if (host === '') {
      return undefined;
    }
    return new ParsedUrl(protocol, host, hasUserInfo);
  }

  /**
   * 「像 IP 字面量又不是规范点分十进制」的主机判据（纯函数，全测；RISK 批对齐上游
   * f941799 重写）。只认两种标签：十进制数字与 `0x` 十六进制。域名分支必须**不**被
   * 这类主机蹭到——平台的 inet_aton 语义会把 `127.1`、`2130706433`、`0177.0.0.1`、
   * `0x7f.0.0.1` 当 IP 解析（分别都落 127.0.0.1），而「不是字面量就放行」的旧判据
   * 正是把它们漏进内网的那道缝。
   */
  private static isNumericLiteralHost(h: string): boolean {
    if (h === '') {
      return false;
    }
    const labels: Array<string> = h.split('.');
    for (const label of labels) {
      if (!/^[0-9]+$/.test(label) && !/^0[xX][0-9a-fA-F]+$/.test(label)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 规范点分十进制：恰好 4 段、每段 1~3 位、无前导零（`0` 自身除外）、值 ≤255。
   * 任何「像 IP 又不符合」的形态（段数不对、超范围、前导零、十六进制）返回 undefined。
   */
  static parseIpv4(h: string): Array<number> | undefined {
    const parts: Array<string> = h.split('.');
    if (parts.length !== 4) {
      return undefined;
    }
    const out: Array<number> = [0, 0, 0, 0];
    for (let i = 0; i < 4; i++) {
      const s: string = parts[i];
      if (s.length < 1 || s.length > 3 || !/^[0-9]+$/.test(s)) {
        return undefined;
      }
      // 前导零（007、010）在 inet_aton 下是八进制，禁掉——宁可拒也不猜平台语义
      if (s.length > 1 && s.charAt(0) === '0') {
        return undefined;
      }
      const v: number = Number.parseInt(s, 10);
      if (!Number.isFinite(v) || v < 0 || v > 255) {
        return undefined;
      }
      out[i] = v;
    }
    return out;
  }

  /** IPv4 私网/环回/保留判据（前两段即可定，第三段留给 192.0.2.0/24 与 198.51.100/24） */
  static isBlockedIpv4(a: number, b: number, c: number): boolean {
    return a === 0 || a === 10 || a === 127 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 192 && b === 0) ||
      (a === 198 && (b === 18 || b === 19)) ||
      (a === 198 && b === 51 && c === 100) ||
      (a === 203 && b === 0 && c === 113) ||
      a >= 224;
  }

  /**
   * 主机判据的 IPv4 半边：**域名**（含字母的标签）一律放行（不做 DNS 预解析）；
   * 数字/十六进制标签的主机按 IP 字面量要求——不是规范点分十进制就拒（先拒后判，
   * 不是判完私网再放行）。
   */
  static isPublicIpv4OrDomain(h: string): boolean {
    if (UpdateDownloadPure.isNumericLiteralHost(h)) {
      const o: Array<number> | undefined = UpdateDownloadPure.parseIpv4(h);
      if (o === undefined) {
        return false;
      }
      return !UpdateDownloadPure.isBlockedIpv4(o[0], o[1], o[2]);
    }
    return true;
  }

  /**
   * IPv6 字面量：先严格解析成 16 字节（parseIpv6），**解析不了一律拒**（fail-closed），
   * 再按地址判据：未指定、环回、fc00::/7（ULA）、fe80::/10（链路本地）、ff00::/8（组播）、
   * 2001:db8::/32（文档）、100::/64（discard），以及 ::ffff:0:0/96（IPv4 映射）、
   * ::/96（IPv4 兼容，含 ::a9fe:a9fe 云元数据）、64:ff9b::/96（NAT64）——后三种解出
   * 内嵌 IPv4 后按 isBlockedIpv4 判。旧的「startsWith("fc") / ::ffff: 只认点分十进制」
   * 实现漏掉了十六进制映射形式（[::ffff:c0a8:1]、[::ffff:7f00:1]、[0:0:0:0:0:ffff:127.0.0.1]
   * 全部放行——RISK 批对齐上游 f941799 重写）。
   */
  static isPublicIpv6(host: string): boolean {
    const bytes: Array<number> | undefined = UpdateDownloadPure.parseIpv6(host);
    if (bytes === undefined) {
      return false;
    }
    const zero = (from: number, to: number): boolean => {
      for (let i = from; i < to; i++) {
        if (bytes[i] !== 0) {
          return false;
        }
      }
      return true;
    };
    const v4At = (from: number): Array<number> => [
      bytes[from] & 0xff, bytes[from + 1] & 0xff, bytes[from + 2] & 0xff, bytes[from + 3] & 0xff
    ];
    // ::/128 未指定、::1/128 环回
    if (zero(0, 16)) {
      return false;
    }
    if (zero(0, 15) && bytes[15] === 1) {
      return false;
    }
    // fc00::/7 ULA、fe80::/10 链路本地、ff00::/8 组播
    if ((bytes[0] & 0xfe) === 0xfc) {
      return false;
    }
    if (bytes[0] === 0xfe && (bytes[1] & 0xc0) === 0x80) {
      return false;
    }
    if (bytes[0] === 0xff) {
      return false;
    }
    // 2001:db8::/32（文档专用）、100::/64（discard-only）
    if (bytes[0] === 0x20 && bytes[1] === 0x01 && bytes[2] === 0x0d && bytes[3] === 0xb8) {
      return false;
    }
    if (bytes[0] === 0x01 && zero(1, 8)) {
      return false;
    }
    // ::ffff:0:0/96 IPv4 映射
    if (zero(0, 10) && bytes[10] === 0xff && bytes[11] === 0xff) {
      const o: Array<number> = v4At(12);
      return !UpdateDownloadPure.isBlockedIpv4(o[0], o[1], o[2]);
    }
    // ::/96 IPv4 兼容（::a9fe:a9fe = 169.254.169.254）
    if (zero(0, 12)) {
      const o: Array<number> = v4At(12);
      return !UpdateDownloadPure.isBlockedIpv4(o[0], o[1], o[2]);
    }
    // 64:ff9b::/96 NAT64（内嵌 IPv4 同样可能是环回/链路本地）
    if (bytes[0] === 0x00 && bytes[1] === 0x64 && bytes[2] === 0xff && bytes[3] === 0x9b && zero(4, 12)) {
      const o: Array<number> = v4At(12);
      return !UpdateDownloadPure.isBlockedIpv4(o[0], o[1], o[2]);
    }
    return true;
  }

  /**
   * IPv6 字面量 → 16 字节（纯函数，全测，零网络调用）。不认 zone id（`%eth0`）、
   * 多处 `::`、非法十六进制组；尾组可以是点分 IPv4（`::ffff:1.2.3.4` 形态）。
   */
  static parseIpv6(text: string): Array<number> | undefined {
    let s: string = text.toLowerCase();
    if (s.startsWith('[')) {
      s = s.substring(1);
    }
    if (s.endsWith(']')) {
      s = s.substring(0, s.length - 1);
    }
    if (s === '' || s.indexOf('%') >= 0) {
      return undefined;
    }
    // 尾组点分 IPv4 → 换成两个十六进制组（::ffff:1.2.3.4 → ::ffff:102:304）
    const lastColon: number = s.lastIndexOf(':');
    if (s.indexOf('.') >= 0) {
      if (lastColon < 0) {
        return undefined;
      }
      const o: Array<number> | undefined = UpdateDownloadPure.parseIpv4(s.substring(lastColon + 1));
      if (o === undefined) {
        return undefined;
      }
      s = s.substring(0, lastColon + 1) +
        ((o[0] << 8) | o[1]).toString(16) + ':' + ((o[2] << 8) | o[3]).toString(16);
    }
    const halves: Array<string> = s.split('::');
    if (halves.length > 2) {
      return undefined;
    }
    const head: Array<string> = halves[0] === '' ? [] : halves[0].split(':');
    const tail: Array<string> = halves.length === 2 && halves[1] !== '' ? halves[1].split(':') : [];
    if (halves.length === 1 && head.length !== 8) {
      return undefined;
    }
    if (halves.length === 2 && head.length + tail.length > 7) {
      return undefined;
    }
    const groups: Array<number> = [];
    const group = (g: string): number | undefined => {
      if (g.length < 1 || g.length > 4) {
        return undefined;
      }
      for (const ch of g.split('')) {
        if ('0123456789abcdef'.indexOf(ch) < 0) {
          return undefined;
        }
      }
      return Number.parseInt(g, 16);
    };
    for (const g of head) {
      const v: number | undefined = group(g);
      if (v === undefined) {
        return undefined;
      }
      groups.push(v);
    }
    if (halves.length === 2) {
      for (let i = 0; i < 8 - head.length - tail.length; i++) {
        groups.push(0);
      }
    }
    for (const g of tail) {
      const v: number | undefined = group(g);
      if (v === undefined) {
        return undefined;
      }
      groups.push(v);
    }
    if (groups.length !== 8) {
      return undefined;
    }
    const out: Array<number> = [];
    for (let i = 0; i < 8; i++) {
      out.push((groups[i] >>> 8) & 0xff);
      out.push(groups[i] & 0xff);
    }
    return out;
  }

}
