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
   * IPv4 字面量按段判断；**不是字面量（域名）一律放行**。拒：0/8、10/8、100.64/10、127/8、
   * 169.254/16、172.16/12、192.168/16、192.0.0.0/16、198.18/15、198.51.100/24、203.0.113/24、
   * 224/4 与 240/4（组播/保留）。
   */
  static isPublicIpv4OrDomain(h: string): boolean {
    const parts: Array<string> = h.split('.');
    if (parts.length !== 4) {
      return true;
    }
    const octets: Array<number> = [0, 0, 0, 0];
    for (let i = 0; i < 4; i++) {
      const s: string = parts[i];
      if (s === '' || s.length > 3 || !/^[0-9]+$/.test(s)) {
        return true;
      }
      const v: number = Number.parseInt(s, 10);
      if (!Number.isFinite(v) || v < 0 || v > 255) {
        return true;
      }
      octets[i] = v;
    }
    const a: number = octets[0];
    const b: number = octets[1];
    const c: number = octets[2];
    const blocked: boolean = a === 0 || a === 10 || a === 127 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 192 && b === 0) ||
      (a === 198 && (b === 18 || b === 19)) ||
      (a === 198 && b === 51 && c === 100) ||
      (a === 203 && b === 0 && c === 113) ||
      a >= 224;
    return !blocked;
  }

  /** IPv6 字面量：拒 `::`、`::1`、fc00::/7（ULA）、fe80::/10（链路本地）与 ::ffff: 映射的私网/环回 */
  static isPublicIpv6(host: string): boolean {
    let h: string = host.toLowerCase();
    if (h.startsWith('[')) {
      h = h.substring(1);
    }
    if (h.endsWith(']')) {
      h = h.substring(0, h.length - 1);
    }
    if (h === '::' || h === '::1') {
      return false;
    }
    if (h.startsWith('fc') || h.startsWith('fd')) {
      return false;
    }
    if (h.startsWith('fe8') || h.startsWith('fe9') || h.startsWith('fea') || h.startsWith('feb')) {
      return false;
    }
    if (h.startsWith('::ffff:')) {
      return UpdateDownloadPure.isPublicIpv4OrDomain(h.substring('::ffff:'.length));
    }
    return true;
  }
}
