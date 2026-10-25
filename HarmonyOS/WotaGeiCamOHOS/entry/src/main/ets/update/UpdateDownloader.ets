/**
 * 「检查更新」的网络执行层——上游 update/UpdateDownloader.kt 的鸿蒙移植
 * （对齐上游 971874a/376b3ef）。本文件是全工程**唯一**允许出现 @ohos.net.http 的地方。
 *
 * 职责：
 * - fetchJson：探测段 1/2 的网络真身（注入 probeLatestRelease）；
 * - fetchRedirectLocation：段 3 契约占位（OHOS 无等价物，恒 undefined，见 UpdateChecker 文件头）；
 * - downloadPackage：**测速选路** + 下载 fallback + 进度回调 + 落盘 `cacheDir/updates/`。
 *
 * 每个渠道的防线（失败自动切下一渠道）：
 * 1. 连接超时 10s / 读超时 30s（停滞兜底）；
 * 2. Content-Length 与实际字节数一致性（探测给了 size 时也查）；
 * 3. 落盘后首字节 `PK` 魔数（HAP 也是 zip；防镜像回 HTML 错误页）；
 * 4. 响应体上限（探测 4MiB / 包体 100MiB——平台 maxLimit 硬上限，登记偏离上游 2GiB）；
 * 5. SHA-256 端到端校验（哈希只信段 1 官方端点的 Release 说明）。
 *
 * **平台偏离（登记）**：ohos.net.http 自动跟随重定向且拿不到中间 `Location`，无法像上游那样
 * 手写逐跳跟随 + 每跳过安全门槛。补偿：每渠道 URL 在发请求前过 isSafeRemoteUrl（拒环回/私有），
 * 落盘后仍有 PK + size + SHA-256 三道；且 OHOS 侧没有「系统安装器按签名强校验」的等价物，
 * 安装前另做包名/完整性核对（见 UpdateInstaller）。
 */
import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { Diag } from '../diag/Diag';
import { Sha256 } from './Sha256';
import { ReleaseJson, UpdateConfig } from './UpdateChecker';
import { ChannelSpeed, UpdateDownloadPure } from './UpdatePure';

const TAG: string = 'WotaUpdate';

/** 下载结论的三种终态 */
export enum DownloadKind {
  DONE = 'done',
  CANCELLED = 'cancelled',
  FAILED = 'failed'
}

/** 下载结论：UI 只消费这个 */
export class DownloadOutcome {
  kind: DownloadKind;
  path: string;
  sizeBytes: number;

  constructor(kind: DownloadKind, path: string, sizeBytes: number) {
    this.kind = kind;
    this.path = path;
    this.sizeBytes = sizeBytes;
  }

  static done(path: string, sizeBytes: number): DownloadOutcome {
    return new DownloadOutcome(DownloadKind.DONE, path, sizeBytes);
  }

  static cancelled(): DownloadOutcome {
    return new DownloadOutcome(DownloadKind.CANCELLED, '', 0);
  }

  static failed(): DownloadOutcome {
    return new DownloadOutcome(DownloadKind.FAILED, '', 0);
  }
}

/** 进度回调 (已下字节, 总字节；总未知时为 -1) */
export type DownloadProgressListener = (bytesRead: number, totalBytes: number) => void;

/** 单个流式请求的收口结果（内部用） */
class StreamResult {
  code: number;
  bytesRead: number;
  declaredLength: number;

  constructor(code: number, bytesRead: number, declaredLength: number) {
    this.code = code;
    this.bytesRead = bytesRead;
    this.declaredLength = declaredLength;
  }
}

export class UpdateDownloader {
  static readonly CONNECT_TIMEOUT_MS: number = 10000;
  static readonly READ_TIMEOUT_MS: number = 30000;
  /** 探测专用短读超时：「检查中…」必须可取消、不可挂数分钟 */
  static readonly PROBE_READ_TIMEOUT_MS: number = 10000;

  /**
   * GET JSON 正文；任何失败（连不上/非 2xx/超时/超帽）返回 undefined——编排据此快速跳过下一节点。
   * 平台自动跟随重定向（见文件头偏离）。
   */
  static async fetchJson(url: string, readTimeoutMs: number = UpdateDownloader.READ_TIMEOUT_MS):
    Promise<string | undefined> {
    if (!UpdateDownloadPure.isSafeRemoteUrl(url)) {
      Diag.w(TAG, `探测目标被安全门槛拒绝：${url}`);
      return undefined;
    }
    const req: http.HttpRequest = http.createHttp();
    try {
      const res: http.HttpResponse = await req.request(url, {
        method: http.RequestMethod.GET,
        connectTimeout: UpdateDownloader.CONNECT_TIMEOUT_MS,
        readTimeout: readTimeoutMs,
        maxLimit: UpdateDownloadPure.MAX_PROBE_BYTES,
        expectDataType: http.HttpDataType.STRING,
        header: {
          'Accept': 'application/vnd.github+json',
          'User-Agent': 'WotaGeiCam-UpdateCheck'
        }
      });
      if (res.responseCode < 200 || res.responseCode > 299) {
        Diag.i(TAG, `探测节点 HTTP ${res.responseCode}：${url}`);
        return undefined;
      }
      if (typeof res.result === 'string') {
        return res.result;
      }
      return undefined;
    } catch (e) {
      Diag.i(TAG, `探测节点异常：${url}`, e as Error);
      return undefined;
    } finally {
      req.destroy();
    }
  }

  /**
   * 段 3 的注入契约：OHOS 的 http 自动跟随 302 且不暴露中间 `Location`，
   * 无法实现「不跟随重定向读 Location」。恒返回 undefined ⇒ 编排跳过段 3（登记偏离）。
   */
  static async fetchRedirectLocation(url: string): Promise<string | undefined> {
    Diag.i(TAG, `段 3 重定向探测在 OHOS 无等价物，已跳过：${url}`);
    return undefined;
  }

  /**
   * 下载 HAP。落盘前写 `<名字>.part<nanoTime>`，全部校验通过才改名——中断/失败不会留下
   * 半截文件被当成完整包。
   *
   * 测速选路：每次下载前先给「直连 + 各镜像」并发测速（各读最多 256KiB、整段预算 6s），
   * 按实测速度降序下载；测速全失败时保持候选原序（直连优先 + 镜像表序）。
   */
  static async downloadPackage(ctx: common.Context, directUrl: string, expectedSize: number,
    expectedSha256: string | undefined, onProgress: DownloadProgressListener,
    isCancelled: () => boolean, onSpeedTested: () => void): Promise<DownloadOutcome> {
    const dir: string = `${ctx.cacheDir}/updates`;
    try {
      fileIo.mkdirSync(dir, true);
    } catch (e) {
      // 目录已存在即正常（mkdir 非递归不会走到这里）
    }
    const assetName: string = directUrl.substring(directUrl.lastIndexOf('/') + 1);
    const present: Array<string> = UpdateDownloader.listNames(dir);
    const stale: Array<string> = UpdateDownloadPure.staleFilesOf(present, assetName);
    for (const n of stale) {
      try {
        fileIo.unlinkSync(`${dir}/${n}`);
      } catch (e) {
        Diag.w(TAG, `清理旧更新文件失败：${n}`, e as Error);
      }
    }
    const target: string = `${dir}/${assetName}`;
    // part 按代唯一：上一代已取消的读取不会被写进新一代的 part
    const part: string = `${dir}/${assetName}.part${Date.now()}`;

    // HAP 的校验值：Release 说明里的 SHA256 行是安卓 APK 的（发版流水线由 build-apk.yml 回填），
    // 与 HAP 不是同一枚资产 → 改读 CI 另外产出的 SHA256SUMS（直连 GitHub，不走镜像）。
    // 拿不到（老 Release / 网络失败）→ undefined，退回 PK + size 两道防线。
    const expectedSha: string | undefined =
      await UpdateDownloader.resolveExpectedSha256(directUrl, expectedSha256);

    const candidates: Array<string> =
      UpdateDownloadPure.downloadCandidates(directUrl, UpdateConfig.MIRROR_PREFIXES);
    const ordered: Array<string> = await UpdateDownloader.measureAndRank(candidates, isCancelled);
    if (isCancelled()) {
      UpdateDownloader.removeQuietly(part);
      return DownloadOutcome.cancelled();
    }
    onSpeedTested();
    for (const url of ordered) {
      if (isCancelled()) {
        UpdateDownloader.removeQuietly(part);
        return DownloadOutcome.cancelled();
      }
      const r: DownloadOutcome =
        await UpdateDownloader.tryNode(url, part, expectedSize, onProgress, isCancelled);
      if (r.kind === DownloadKind.DONE) {
        // 重下同一资产时旧包还在：先删旧再改名（rename 对已存在目标的行为平台相关）
        UpdateDownloader.removeQuietly(target);
        try {
          fileIo.renameSync(part, target);
        } catch (e) {
          Diag.e(TAG, `更新包改名失败：${part} → ${target}`, e as Error);
          UpdateDownloader.removeQuietly(part);
          return DownloadOutcome.failed();
        }
        // RISK：无期望哈希时短路——不白读 ~5MB 全量包体算一个用不上的摘要
        // （对齐上游 f941799；读盘异常已由 sha256HexOfFile 内部折 ''，不会穿成未捕获异常）
        const actual: string = expectedSha === undefined
          ? '' : UpdateDownloader.sha256HexOfFile(target);
        if (UpdateDownloadPure.sha256Matches(expectedSha, actual)) {
          Diag.i(TAG, `更新包就绪：${target}（${r.sizeBytes}B）`);
          return DownloadOutcome.done(target, r.sizeBytes);
        }
        Diag.e(TAG, `SHA-256 不匹配（期望=${expectedSha} 实际=${actual}），判镜像污染，删包`);
        UpdateDownloader.removeQuietly(target);
        return DownloadOutcome.failed();
      }
      if (r.kind === DownloadKind.CANCELLED) {
        UpdateDownloader.removeQuietly(part);
        return DownloadOutcome.cancelled();
      }
      // 该渠道失败：试下一个（顺序已按测速从快到慢）
      UpdateDownloader.removeQuietly(part);
    }
    return DownloadOutcome.failed();
  }

  /**
   * 解析 HAP 的期望哈希：直连下载 URL …/download/<tag>/<asset> 里的 tag + asset 名，
   * 再取同 tag 下的 `SHA256SUMS`（build-hap.yml 产出的资产，`sha256sum *.hap` 原文），
   * 按资产名匹配一行。**只走直连**：SUMS 若从镜像取，镜像可以同时换包和换哈希，校验就失去意义。
   */
  private static async resolveExpectedSha256(directUrl: string,
    expectedSha256: string | undefined): Promise<string | undefined> {
    if (expectedSha256 !== undefined) {
      return expectedSha256;
    }
    const assetSlash: number = directUrl.lastIndexOf('/');
    if (assetSlash <= 0) {
      return undefined;
    }
    const assetName: string = directUrl.substring(assetSlash + 1);
    const tagSlash: number = directUrl.lastIndexOf('/', assetSlash - 1);
    if (tagSlash < 0) {
      return undefined;
    }
    const tag: string = directUrl.substring(tagSlash + 1, assetSlash);
    const sumsUrl: string = UpdateConfig.directDownloadUrl(tag, 'SHA256SUMS');
    const text: string | undefined =
      await UpdateDownloader.fetchJson(sumsUrl, UpdateDownloader.PROBE_READ_TIMEOUT_MS);
    if (text === undefined) {
      Diag.i(TAG, `SHA256SUMS 不可用，本次只做 PK + size 校验：${sumsUrl}`);
      return undefined;
    }
    const sha: string | undefined = ReleaseJson.parseSha256Sums(text, assetName);
    if (sha === undefined) {
      Diag.i(TAG, `SHA256SUMS 里没有 ${assetName}，本次只做 PK + size 校验`);
    }
    return sha;
  }

  /** 文件的 SHA-256（64KiB 分块流式，全量读一遍；只在校验分支调用；读失败返回空串 → 判不匹配） */
  static sha256HexOfFile(path: string): string {
    const md: Sha256 = new Sha256();
    try {
      const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
      try {
        const buf: ArrayBuffer = new ArrayBuffer(64 * 1024);
        while (true) {
          const n: number = fileIo.readSync(file.fd, buf);
          if (n <= 0) {
            break;
          }
          md.update(new Uint8Array(buf.slice(0, n)));
        }
      } finally {
        fileIo.closeSync(file);
      }
      return md.digestHex();
    } catch (e) {
      Diag.w(TAG, `更新包读取失败，无法校验：${path}`, e as Error);
      return '';
    }
  }

  /** HAP = ZIP，首两字节 `PK`（防镜像把 HTML 错误页/登录页当文件体吐回来） */
  static hasPackageMagic(path: string): boolean {
    let fd: number = -1;
    try {
      const size: number = fileIo.statSync(path).size;
      if (size < 2) {
        return false;
      }
      fd = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY).fd;
      const buf: ArrayBuffer = new ArrayBuffer(2);
      const n: number = fileIo.readSync(fd, buf);
      if (n !== 2) {
        return false;
      }
      const b: Uint8Array = new Uint8Array(buf);
      return b[0] === 0x50 && b[1] === 0x4b;
    } catch (e) {
      return false;
    } finally {
      if (fd >= 0) {
        try {
          fileIo.closeSync(fd);
        } catch (e) {
          // 只读句柄关闭失败不影响判据
        }
      }
    }
  }

  // ------------------------------------------------------------------ 测速（并发）

  /** 各渠道并发测速并按速度排序；返回的列表已是下载顺序 */
  private static async measureAndRank(candidates: Array<string>,
    isCancelled: () => boolean): Promise<Array<string>> {
    if (candidates.length === 0) {
      return [];
    }
    const tasks: Array<Promise<ChannelSpeed>> = [];
    for (const url of candidates) {
      tasks.push(UpdateDownloader.measureOne(url, isCancelled));
    }
    const speeds: Array<ChannelSpeed> = await Promise.all(tasks);
    return UpdateDownloadPure.rankChannelsBySpeed(speeds);
  }

  /**
   * 单渠道测速：读到 256KiB / 预算耗尽 / 流结束为止，返回字节每秒。
   * 连不上、非 2xx、首块不是 PK 魔数、没读到数据一律记 0（排序垫底但仍会尝试）。
   * 计时从发请求之前算起，连接与首字节延迟也计入。
   */
  private static measureOne(url: string, isCancelled: () => boolean): Promise<ChannelSpeed> {
    return new Promise<ChannelSpeed>((resolve: (s: ChannelSpeed) => void) => {
      if (isCancelled() || !UpdateDownloadPure.isSafeRemoteUrl(url)) {
        resolve(new ChannelSpeed(url, 0));
        return;
      }
      const started: number = Date.now();
      const deadline: number = started + UpdateDownloadPure.SPEED_TEST_BUDGET_MS;
      const req: http.HttpRequest = http.createHttp();
      let read: number = 0;
      let firstChunk: boolean = true;
      let settled: boolean = false;
      const finish = (bps: number): void => {
        if (settled) {
          return;
        }
        settled = true;
        try {
          req.destroy();
        } catch (e) {
          // 已在收口，destroy 失败无影响
        }
        resolve(new ChannelSpeed(url, bps));
      };
      const elapsedMs = (): number => Math.max(1, Date.now() - started);
      req.on('dataReceive', (chunk: ArrayBuffer) => {
        if (settled) {
          return;
        }
        const bytes: Uint8Array = new Uint8Array(chunk);
        if (firstChunk) {
          firstChunk = false;
          if (!(bytes.length >= 2 && bytes[0] === 0x50 && bytes[1] === 0x4b)) {
            finish(0);
            return;
          }
        }
        read += bytes.length;
        if (read >= UpdateDownloadPure.SPEED_TEST_BYTES || Date.now() > deadline || isCancelled()) {
          finish(Math.floor(read * 1000 / elapsedMs()));
        }
      });
      req.on('dataEnd', () => {
        finish(read > 0 ? Math.floor(read * 1000 / elapsedMs()) : 0);
      });
      req.requestInStream(url, {
        method: http.RequestMethod.GET,
        connectTimeout: UpdateDownloadPure.SPEED_TEST_CONNECT_TIMEOUT_MS,
        readTimeout: UpdateDownloadPure.SPEED_TEST_READ_TIMEOUT_MS,
        maxLimit: UpdateDownloadPure.MAX_PACKAGE_BYTES,
        header: { 'User-Agent': 'WotaGeiCam-UpdateCheck' }
      }).then((code: number): void => {
        if (code < 200 || code > 299) {
          finish(0);
        }
      }).catch((e: Object): void => {
        finish(0);
      });
      // 预算兜底：读循环里每个 dataReceive 才查一次，长时间无数据靠这条收口
      setTimeout((): void => {
        finish(0);
      }, UpdateDownloadPure.SPEED_TEST_BUDGET_MS);
    });
  }

  // ------------------------------------------------------------------ 单节点下载

  /** 单节点尝试：成功落 part / 取消 / 失败三态，任何异常都折算成失败（换下一个节点） */
  private static tryNode(url: string, part: string, expectedSize: number,
    onProgress: DownloadProgressListener, isCancelled: () => boolean): Promise<DownloadOutcome> {
    return new Promise<DownloadOutcome>((resolve: (o: DownloadOutcome) => void) => {
      if (!UpdateDownloadPure.isSafeRemoteUrl(url)) {
        resolve(DownloadOutcome.failed());
        return;
      }
      let fd: number = -1;
      try {
        fd = fileIo.openSync(part,
          fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC).fd;
      } catch (e) {
        Diag.w(TAG, `更新包临时文件打不开：${part}`, e as Error);
        resolve(DownloadOutcome.failed());
        return;
      }
      const req: http.HttpRequest = http.createHttp();
      let read: number = 0;
      let declared: number = -1;
      let total: number = expectedSize > 0 ? expectedSize : -1;
      let settled: boolean = false;
      const closeOut = (): void => {
        if (fd < 0) {
          return;
        }
        try {
          fileIo.closeSync(fd);
        } catch (e) {
          // 已在收口；双关闭的异常不必上抛
        }
        fd = -1;
      };
      const finish = (o: DownloadOutcome): void => {
        if (settled) {
          return;
        }
        settled = true;
        try {
          req.destroy();
        } catch (e) {
          // 收口中
        }
        closeOut();
        resolve(o);
      };
      req.on('headersReceive', (header: Object) => {
        declared = UpdateDownloader.contentLengthOf(header);
        if (declared > 0 && total < 0) {
          total = declared;
        }
        // 头里显式声明超帽：不必开流写盘
        if (declared > 0 && UpdateDownloadPure.bytesOverLimit(declared,
          UpdateDownloadPure.MAX_PACKAGE_BYTES)) {
          finish(DownloadOutcome.failed());
        }
      });
      req.on('dataReceive', (chunk: ArrayBuffer) => {
        if (settled) {
          return;
        }
        if (isCancelled()) {
          finish(DownloadOutcome.cancelled());
          return;
        }
        const bytes: Uint8Array = new Uint8Array(chunk);
        read += bytes.length;
        if (UpdateDownloadPure.bytesOverLimit(read, UpdateDownloadPure.MAX_PACKAGE_BYTES)) {
          finish(DownloadOutcome.failed());
          return;
        }
        try {
          fileIo.writeSync(fd, chunk);
        } catch (e) {
          Diag.w(TAG, `更新包写入失败：${part}`, e as Error);
          finish(DownloadOutcome.failed());
          return;
        }
        onProgress(read, total);
      });
      req.on('dataEnd', () => {
        if (settled) {
          return;
        }
        // 先关句柄再核对落盘内容（写完的字节必须全部可见）
        closeOut();
        if (expectedSize > 0 && declared > 0 && declared !== expectedSize) {
          Diag.w(TAG, `响应头长度 ${declared} 与探测给的 ${expectedSize} 不一致：${url}`);
          finish(DownloadOutcome.failed());
          return;
        }
        if (total > 0 && read !== total) {
          Diag.w(TAG, `更新包截断：读 ${read}B，应为 ${total}B：${url}`);
          finish(DownloadOutcome.failed());
          return;
        }
        if (!UpdateDownloader.hasPackageMagic(part)) {
          Diag.w(TAG, `首字节不是 PK，判镜像落地页：${url}`);
          finish(DownloadOutcome.failed());
          return;
        }
        finish(DownloadOutcome.done(part, read));
      });
      req.requestInStream(url, {
        method: http.RequestMethod.GET,
        connectTimeout: UpdateDownloader.CONNECT_TIMEOUT_MS,
        readTimeout: UpdateDownloader.READ_TIMEOUT_MS,
        maxLimit: UpdateDownloadPure.MAX_PACKAGE_BYTES,
        header: { 'User-Agent': 'WotaGeiCam-UpdateCheck' }
      }).then((code: number): void => {
        if (code !== 200) {
          Diag.w(TAG, `下载渠道 HTTP ${code}：${url}`);
          finish(DownloadOutcome.failed());
        }
      }).catch((e: Object): void => {
        Diag.w(TAG, `下载渠道异常：${url}`, e as Error);
        finish(DownloadOutcome.failed());
      });
    });
  }

  // ------------------------------------------------------------------ 小工具

  /** 从响应头里取 content-length（键名大小写不定，转成串后正则取，避开动态属性访问） */
  private static contentLengthOf(header: Object): number {
    try {
      const text: string = JSON.stringify(header);
      const m: RegExpMatchArray | null = text.match(/["']?content-length["']?\s*:\s*"?([0-9]+)/i);
      if (m === null) {
        return -1;
      }
      const n: number = Number.parseInt(m[1], 10);
      return Number.isFinite(n) ? n : -1;
    } catch (e) {
      return -1;
    }
  }

  /** 目录里的文件名（不存在 → 空表） */
  private static listNames(dir: string): Array<string> {
    try {
      return fileIo.listFileSync(dir);
    } catch (e) {
      return [];
    }
  }

  private static removeQuietly(path: string): void {
    try {
      fileIo.unlinkSync(path);
    } catch (e) {
      // 文件不存在即目标状态
    }
  }
}
