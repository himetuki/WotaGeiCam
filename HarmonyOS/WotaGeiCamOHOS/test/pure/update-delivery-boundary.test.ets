/** 真正驱动下载/导出编排；仅替换网络、文件句柄与 picker，绝不发请求或触达系统安装器。 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { createRequire } from 'node:module';
import { runInNewContext } from 'node:vm';
import { Sha256 } from '../../entry/src/main/ets/update/Sha256';
import { UpdateConfig } from '../../entry/src/main/ets/update/UpdateChecker';

interface TestModule { exports: Record<string, Object>; }
interface TranspileResult { outputText: string; }
interface Transpiler {
  transpileModule(source: string, options: Object): TranspileResult;
  ModuleKind: Record<string, number>;
  ScriptTarget: Record<string, number>;
}
interface Outcome { kind: string; path: string; sizeBytes: number; sha256: string; message: string; }
interface Saved { ok: boolean; uri: string; message: string; }
interface Downloader {
  downloadPackage(ctx: Object, url: string, size: number, progress: () => void, cancelled: () => boolean,
    speedDone: () => void): Promise<Outcome>;
  fetchJson(url: string): Promise<string | undefined>;
  measureAndRank(urls: Array<string>): Promise<Array<string>>;
  tryNode(url: string, part: string): Promise<Outcome>;
}
interface Installer {
  saveAs(ctx: Object, path: string, name: string, sha: string | undefined): Promise<Saved>;
}
interface Handle { path: string; offset: number; }
type ModuleRequire = (name: string) => Object;
type ModuleFactory = (load: ModuleRequire, module: TestModule, exports: Record<string, Object>) => void;

function root(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/update/UpdateInstaller.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found');
}

/** VM 只替换平台边界。下载闸、官方 SUMS 解析、SHA 算法、导出双重核对仍加载真实生产源码。 */
class DeliveryFixture {
  files: Map<string, Uint8Array> = new Map<string, Uint8Array>();
  handles: Map<number, Handle> = new Map<number, Handle>();
  pickerCalls: number = 0;
  downloads: number = 0;
  sumsRequests: Array<string> = [];
  sums: string | undefined = undefined;
  payload: Uint8Array = new Uint8Array([0x50, 0x4b, 1, 2]);
  pickerAction: () => void = (): void => {};
  cancelPicker: boolean = false;
  maxWriteBytes: number = Number.MAX_SAFE_INTEGER;
  downloader: Downloader;
  installer: Installer;
  private modules: Map<string, TestModule> = new Map<string, TestModule>();
  private nextFd: number = 0;
  private ts: Transpiler;

  constructor() {
    const req: NodeRequire = createRequire(__filename);
    const tsPath: string = process.env.PURE_TEST_TS_PATH ||
      resolve(process.env.DEVECO_HOME || 'D:/Application/HUAWEI/DevEco/DevEco Studio',
        'tools/hvigor/hvigor/node_modules/typescript');
    this.ts = req(tsPath) as Transpiler;
    const downloader: Record<string, Object> = this.load(resolve(root(), 'entry/src/main/ets/update/UpdateDownloader.ets'));
    this.downloader = downloader.UpdateDownloader as Downloader;
    this.installer = this.load(resolve(root(), 'entry/src/main/ets/update/UpdateInstaller.ets')).UpdateInstaller as Installer;
    this.downloader.fetchJson = async (url: string): Promise<string | undefined> => {
      this.sumsRequests.push(url);
      return this.sums;
    };
    // 只替代传输：实际 SHA 校验、删包、fallback、完成结果/官方摘要传递仍走生产 downloadPackage。
    this.downloader.measureAndRank = async (urls: Array<string>): Promise<Array<string>> => urls;
    this.downloader.tryNode = async (url: string, part: string): Promise<Outcome> => {
      this.downloads++;
      this.files.set(part, this.payload.slice());
      return { kind: 'done', path: part, sizeBytes: this.payload.length, sha256: '', message: '' };
    };
  }

  private load(file: string): Record<string, Object> {
    const cached: TestModule | undefined = this.modules.get(file);
    if (cached !== undefined) {
      return cached.exports;
    }
    const module: TestModule = { exports: {} };
    this.modules.set(file, module);
    const source: string = readFileSync(file, 'utf8');
    const output: TranspileResult = this.ts.transpileModule(source, {
      compilerOptions: { module: this.ts.ModuleKind.CommonJS, target: this.ts.ScriptTarget.ES2020 }
    });
    const load: ModuleRequire = (name: string): Object => {
      if (name === '@kit.CoreFileKit') {
        return this.fileKit();
      }
      if (name.startsWith('@kit.')) {
        return {};
      }
      if (name.endsWith('/Diag')) {
        return { Diag: { i: (): void => {}, w: (): void => {}, e: (): void => {} } };
      }
      if (name.startsWith('.')) {
        return this.load(resolve(dirname(file), name + '.ets'));
      }
      throw new Error('unexpected dependency ' + name);
    };
    const factory: ModuleFactory = runInNewContext('(function(require,module,exports){' + output.outputText + '\n})',
      { Uint8Array, ArrayBuffer, TextEncoder, Date, Promise, setTimeout: (): number => 0 }) as ModuleFactory;
    factory(load, module, module.exports);
    return module.exports;
  }

  private fileKit(): Object {
    const fixture: DeliveryFixture = this;
    class DocumentViewPicker {
      async save(): Promise<Array<string>> {
        fixture.pickerCalls++;
        fixture.pickerAction();
        return fixture.cancelPicker ? [] : ['chosen.hap'];
      }
    }
    return {
      picker: { DocumentViewPicker },
      fileIo: {
        OpenMode: { READ_ONLY: 0, READ_WRITE: 1, CREATE: 2, TRUNC: 4 },
        mkdirSync: (): void => {},
        listFileSync: (): Array<string> => Array.from(this.files.keys()).map((p: string): string => p.substring(p.lastIndexOf('/') + 1)),
        unlinkSync: (path: string): void => { this.files.delete(path); },
        renameSync: (from: string, to: string): void => {
          this.files.set(to, this.bytes(from));
          this.files.delete(from);
        },
        statSync: (path: string): Object => ({ size: this.bytes(path).length }),
        openSync: (path: string, mode: number): Object => {
          if (mode !== 0) this.files.set(path, new Uint8Array(0));
          this.bytes(path);
          const fd: number = ++this.nextFd;
          this.handles.set(fd, { path, offset: 0 });
          return { fd };
        },
        readSync: (fd: number, buffer: ArrayBuffer): number => {
          const handle: Handle = this.handles.get(fd)!;
          const bytes: Uint8Array = this.bytes(handle.path);
          const n: number = Math.min(buffer.byteLength, bytes.length - handle.offset);
          new Uint8Array(buffer).set(bytes.subarray(handle.offset, handle.offset + n));
          handle.offset += n;
          return n;
        },
        writeSync: (fd: number, buffer: ArrayBuffer): number => {
          const handle: Handle = this.handles.get(fd)!;
          const old: Uint8Array = this.bytes(handle.path);
          const written: number = Math.min(buffer.byteLength, this.maxWriteBytes);
          const joined: Uint8Array = new Uint8Array(old.length + written);
          joined.set(old);
          joined.set(new Uint8Array(buffer, 0, written), old.length);
          this.files.set(handle.path, joined);
          return written;
        },
        closeSync: (file: number | { fd: number }): void => {
          this.handles.delete(typeof file === 'number' ? file : file.fd);
        }
      }
    };
  }

  bytes(path: string): Uint8Array {
    const bytes: Uint8Array | undefined = this.files.get(path);
    if (bytes === undefined) throw new Error('file missing ' + path);
    return bytes;
  }

  download(): Promise<Outcome> {
    return this.downloader.downloadPackage({ cacheDir: 'cache' }, UpdateConfig.directDownloadUrl('v0.0.12-r12', 'update.hap'),
      4, (): void => {}, (): boolean => false, (): void => {});
  }
}

test('交付边界_缺失坏哈希重名SUMS均不能下载出完成态', async () => {
  for (const sums of [undefined, '', 'bad  update.hap', '0'.repeat(64) + '  other.hap',
    '0'.repeat(64) + '  update.hap\n' + '1'.repeat(64) + '  update.hap']) {
    const f: DeliveryFixture = new DeliveryFixture();
    f.sums = sums;
    const result: Outcome = await f.download();
    assertEq('failed', result.kind);
    assertEq('', result.path);
    assertEq(0, f.downloads);
    assertTrue(result.message.indexOf('官方 SHA-256') >= 0);
    assertEq(UpdateConfig.directDownloadUrl('v0.0.12-r12', 'SHA256SUMS'), f.sumsRequests[0]);
  }
});

test('交付边界_坏镜像PK及size合法也必须删包且不导出', async () => {
  const f: DeliveryFixture = new DeliveryFixture();
  f.sums = '0'.repeat(64) + '  update.hap';
  const result: Outcome = await f.download();
  assertEq('failed', result.kind);
  assertTrue(f.downloads > 1, '坏镜像后仍尝试其他渠道');
  assertEq(0, f.files.size, '不保留坏包或半截包');
  f.files.set('cache/updates/fake.hap', f.payload);
  for (const sha of [undefined, '', 'bad', '0'.repeat(64)]) {
    assertFalse((await f.installer.saveAs({}, 'cache/updates/fake.hap', 'fake.hap', sha)).ok);
  }
  assertEq(0, f.pickerCalls, '不可信包甚至不打开导出选择器');
  assertFalse(f.files.has('chosen.hap'));
});

test('交付边界_坏镜像不得删除或替换旧可信成品', async () => {
  const f: DeliveryFixture = new DeliveryFixture();
  const old: Uint8Array = new Uint8Array([0x50, 0x4b, 7, 8]);
  const target: string = 'cache/updates/update.hap';
  f.files.set(target, old);
  f.sums = Sha256.hexOf(old) + '  update.hap';
  const result: Outcome = await f.download();
  assertEq('failed', result.kind);
  assertEq('', result.path);
  assertEq(1, f.files.size, '坏包与part清理，旧成品保留');
  assertEq(Sha256.hexOf(old), Sha256.hexOf(f.bytes(target)));
});

test('交付边界_官方匹配才完成并允许原字节另存_系统安装交互保留', async () => {
  const f: DeliveryFixture = new DeliveryFixture();
  const sha: string = Sha256.hexOf(f.payload);
  f.sums = sha + '  update.hap';
  const downloaded: Outcome = await f.download();
  assertEq('done', downloaded.kind);
  assertEq(sha, downloaded.sha256);
  const saved: Saved = await f.installer.saveAs({}, downloaded.path, 'update.hap', downloaded.sha256);
  assertTrue(saved.ok);
  assertEq(1, f.pickerCalls);
  assertEq(sha, Sha256.hexOf(f.bytes('chosen.hap')));
  assertTrue(saved.message.indexOf('由系统确认') >= 0);
});

test('交付边界_另存零写或短写不能报告成功且文件句柄必关闭', async () => {
  for (const limit of [0, 2]) {
    const f: DeliveryFixture = new DeliveryFixture();
    const sha: string = Sha256.hexOf(f.payload);
    f.sums = sha + '  update.hap';
    const downloaded: Outcome = await f.download();
    f.maxWriteBytes = limit;
    const saved: Saved = await f.installer.saveAs({}, downloaded.path, 'update.hap', downloaded.sha256);
    assertFalse(saved.ok, '短写不得冒充完整更新包');
    assertEq('', saved.uri, '保存失败不可交付完成路径');
    assertTrue(saved.message.indexOf('保存失败') >= 0);
    assertEq(limit, f.bytes('chosen.hap').length, '确实模拟了文件系统短写，不是空 fixture');
    assertEq(sha, Sha256.hexOf(f.bytes(downloaded.path)), '失败不能删改已验证的源包');
    assertEq(0, f.handles.size, '复制失败必须关闭源和目标句柄');
  }
});

test('交付边界_picker等待中替换包必须复验拦截_取消不复制', async () => {
  const f: DeliveryFixture = new DeliveryFixture();
  f.sums = Sha256.hexOf(f.payload) + '  update.hap';
  const downloaded: Outcome = await f.download();
  f.pickerAction = (): void => { f.files.set(downloaded.path, new Uint8Array([0x50, 0x4b, 8, 9])); };
  const saved: Saved = await f.installer.saveAs({}, downloaded.path, 'update.hap', downloaded.sha256);
  assertFalse(saved.ok);
  assertFalse(f.files.has('chosen.hap'));
  f.files.set(downloaded.path, f.payload);
  f.pickerAction = (): void => {};
  f.cancelPicker = true;
  assertEq('已取消保存', (await f.installer.saveAs({}, downloaded.path, 'update.hap', downloaded.sha256)).message);
  assertFalse(f.files.has('chosen.hap'));
});

test('官方哈希来源只能本仓库规范tag资产_镜像私服或路径注入不可指定SUMS', () => {
  const good: string = UpdateConfig.directDownloadUrl('v0.0.12-r12', 'update.hap');
  assertEq(UpdateConfig.directDownloadUrl('v0.0.12-r12', 'SHA256SUMS'), UpdateConfig.sha256SumsUrlFor(good));
  for (const url of ['https://evil.example/update.hap', UpdateConfig.MIRROR_PREFIXES[0] + good,
    UpdateConfig.directDownloadUrl('v0x0x12-r12', 'update.hap'),
    UpdateConfig.directDownloadUrl('v0.0.12-r12', 'a?b.hap'),
    UpdateConfig.directDownloadUrl('v0.0.12-r12', '../update.hap'),
    UpdateConfig.directDownloadUrl('v0.0.12-r12', '%2e%2e.hap')]) {
    assertNull(UpdateConfig.sha256SumsUrlFor(url));
  }
});
