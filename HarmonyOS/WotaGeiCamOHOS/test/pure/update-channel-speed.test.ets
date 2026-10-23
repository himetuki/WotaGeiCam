/**
 * 下载渠道测速排序与请求目标安全门槛——上游 UpdateChannelSpeedTest.kt 的纯函数部分直译
 * （网络真身在 UpdateDownloader，pure-test 里只有惰性桩，测不了；接线由源码守卫兜住）。
 *
 * 与上游的差异（登记）：包体上限 100MiB（ohos.net.http 的 maxLimit 硬上限），
 * 上游是 2GiB；其余判据逐条对齐。
 */
import { UpdateConfig } from '../../entry/src/main/ets/update/UpdateChecker';
import { ChannelSpeed, UpdateDownloadPure } from '../../entry/src/main/ets/update/UpdatePure';

function speed(url: string, bps: number): ChannelSpeed {
  return new ChannelSpeed(url, bps);
}

// ------------------------------------------------------------------ 排序判据

test('排序_按实测速度降序_0垫底且不丢', () => {
  const ranked: Array<string> = UpdateDownloadPure.rankChannelsBySpeed([
    speed('direct', 0), speed('slow', 182000), speed('fast', 2300000), speed('mid', 1800000)
  ]);
  assertEq('fast,mid,slow,direct', ranked.join(','));
  assertTrue(ranked.indexOf('direct') >= 0, '测速为 0 的渠道不许丢');
});

test('排序_同速保持输入顺序_全0时输出与输入完全一致', () => {
  assertEq('a,b,c',
    UpdateDownloadPure.rankChannelsBySpeed([speed('a', 5), speed('b', 5), speed('c', 5)]).join(','));
  const zeroes: Array<string> =
    UpdateDownloadPure.rankChannelsBySpeed([speed('direct', 0), speed('m1', 0), speed('m2', 0)]);
  assertEq('direct,m1,m2', zeroes.join(','));
  assertEq(0, UpdateDownloadPure.rankChannelsBySpeed([]).length);
  // 负数（防御性）同样按「垫底」处理
  assertEq('good,neg',
    UpdateDownloadPure.rankChannelsBySpeed([speed('neg', -1), speed('good', 7)]).join(','));
});

test('测速上限是绝对字面量_256KiB与6秒预算', () => {
  assertEq(262144, UpdateDownloadPure.SPEED_TEST_BYTES);
  assertEq(6000, UpdateDownloadPure.SPEED_TEST_BUDGET_MS);
  assertEq(4000, UpdateDownloadPure.SPEED_TEST_CONNECT_TIMEOUT_MS);
  assertEq(3000, UpdateDownloadPure.SPEED_TEST_READ_TIMEOUT_MS);
  assertTrue(UpdateDownloadPure.SPEED_TEST_BYTES < 5000000, '采样上限必须小于真实包体量');
});

// ------------------------------------------------------------------ 下载候选

function candidateIssues(candidates: Array<string>, directUrl: string): Array<string> {
  const issues: Array<string> = [];
  if (candidates[0] !== directUrl) {
    issues.push(`直连不在首位：${candidates[0]}`);
  }
  if (candidates.length !== 1 + UpdateConfig.MIRROR_PREFIXES.length) {
    issues.push(`候选数 ${candidates.length} != 1 + ${UpdateConfig.MIRROR_PREFIXES.length}`);
  }
  if (new Set<string>(candidates).size !== candidates.length) {
    issues.push('候选里有重复 URL');
  }
  for (let i = 0; i < UpdateConfig.MIRROR_PREFIXES.length; i++) {
    if (candidates[i + 1] !== UpdateConfig.MIRROR_PREFIXES[i] + directUrl) {
      issues.push(`镜像候选 ${i + 1} 不是「前缀 + 直连」：${candidates[i + 1]}`);
    }
  }
  return issues;
}

test('下载候选_直连在首位_后接每个镜像前缀', () => {
  const raw: string = 'https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.10-r01/' +
    'v0.0.10-r01-signed-Harmony.hap';
  const issues: Array<string> =
    candidateIssues(UpdateDownloadPure.downloadCandidates(raw, UpdateConfig.MIRROR_PREFIXES), raw);
  assertEq(0, issues.length, issues.join('; '));
  // 镜像表必须全部 HTTPS（明文代理等于让中间人换包）
  for (const p of UpdateConfig.MIRROR_PREFIXES) {
    assertTrue(UpdateDownloadPure.isHttpsUrl(p + 'x'), `镜像表里有非 HTTPS 前缀：${p}`);
  }
});

test('下载候选尺子自己能红_丢直连突变体必报红', () => {
  const raw: string = 'https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.10-r01/a.hap';
  const noDirect: Array<string> = [];
  for (const p of UpdateConfig.MIRROR_PREFIXES) {
    noDirect.push(p + raw);
  }
  assertTrue(candidateIssues(noDirect, raw).length > 0, '丢掉直连候选后尺子必须红');
});

// ------------------------------------------------------------------ 请求目标安全门槛

test('URL门槛_只允许HTTPS_拒环回与私有地址', () => {
  assertTrue(UpdateDownloadPure.isSafeRemoteUrl('https://gh-proxy.com/https://github.com/a.hap'));
  assertTrue(UpdateDownloadPure.isSafeRemoteUrl('https://api.github.com/repos/x'));
  assertFalse(UpdateDownloadPure.isSafeRemoteUrl('http://gh-proxy.com/x'));
  assertFalse(UpdateDownloadPure.isSafeRemoteUrl('not a url'));
  assertFalse(UpdateDownloadPure.isSafeRemoteUrl('https://127.0.0.1/x'));
  assertFalse(UpdateDownloadPure.isSafeRemoteUrl('https://localhost/x'));
  assertFalse(UpdateDownloadPure.isSafeRemoteUrl('https://10.0.0.5/x'));
  assertFalse(UpdateDownloadPure.isSafeRemoteUrl('https://user:pw@127.0.0.1/x'), 'userinfo 不能绕过主机判据');
  assertFalse(UpdateDownloadPure.isSafeRemoteUrl('https://[::1]/x'));
});

test('主机判据_环回私有链路本地保留全拒_公网域名与地址放行', () => {
  const allowed: Array<string> = [
    'api.github.com', 'gh-proxy.com', 'gh-proxy.org', 'gh.monlor.com', 'ghproxy.imciel.com',
    '8.8.8.8', '172.32.0.1', '100.128.0.1', 'example.invalid', '1.2.3.999',
    '2001:4860:4860::8888'
  ];
  for (const h of allowed) {
    assertTrue(UpdateDownloadPure.isPublicHost(h), `应放行：${h}`);
  }
  const blocked: Array<string> = [
    '', 'localhost', 'LOCALHOST.', 'x.localhost',
    '127.0.0.1', '127.1.2.3', '0.0.0.0',
    '10.1.2.3', '172.16.0.1', '172.31.255.255', '192.168.1.1',
    '169.254.1.1', '100.64.0.1', '100.127.255.255',
    '192.0.2.1', '198.18.0.1', '198.51.100.7', '203.0.113.9',
    '224.0.0.1', '255.255.255.255',
    '[::1]', '::1', '[fd00::1]', '[fe80::1]', '[::ffff:127.0.0.1]'
  ];
  for (const h of blocked) {
    assertFalse(UpdateDownloadPure.isPublicHost(h), `应拒绝：${h}`);
  }
});

test('URL解析_authority与IPv6闭合判据', () => {
  const a = UpdateDownloadPure.parseUrl('https://gh-proxy.com/https://github.com/a.hap');
  assertTrue(a !== undefined);
  assertEq('https', a!.protocol);
  assertEq('gh-proxy.com', a!.host);
  assertFalse(a!.hasUserInfo);

  const b = UpdateDownloadPure.parseUrl('https://user:pw@example.com:8443/x?y#z');
  assertTrue(b !== undefined);
  assertEq('example.com', b!.host);
  assertTrue(b!.hasUserInfo);

  assertNull(UpdateDownloadPure.parseUrl('example.com/x'));
  assertNull(UpdateDownloadPure.parseUrl('https://[::1/x'));
});

// ------------------------------------------------------------------ 响应体上限与过期文件

test('响应体上限_未知长度放行_显式超帽即拒', () => {
  assertTrue(UpdateDownloadPure.probeBytesAllowed(-1));
  assertTrue(UpdateDownloadPure.probeBytesAllowed(0));
  assertTrue(UpdateDownloadPure.probeBytesAllowed(UpdateDownloadPure.MAX_PROBE_BYTES));
  assertFalse(UpdateDownloadPure.probeBytesAllowed(UpdateDownloadPure.MAX_PROBE_BYTES + 1));
  assertFalse(UpdateDownloadPure.bytesOverLimit(100, 100));
  assertTrue(UpdateDownloadPure.bytesOverLimit(101, 100));
});

test('过期文件判据_删part与非本轮资产_保留本轮已完成包', () => {
  const dir: Array<string> = [
    'v0.0.10-r01-signed-Harmony.hap',       // 本轮资产：保留
    'v0.0.10-r01-signed-Harmony.hap.part',  // 半截：删
    'v0.0.10-r01-signed-Harmony.hap.part1738888888888', // 上一代按代唯一的 part：删
    'v0.0.9-r06-signed-Harmony.hap',        // 旧包：删
    'v0.0.10-r01-unsigned-Harmony.hap'      // 别的资产名：删
  ];
  assertEq([
    'v0.0.10-r01-signed-Harmony.hap.part',
    'v0.0.10-r01-signed-Harmony.hap.part1738888888888',
    'v0.0.9-r06-signed-Harmony.hap',
    'v0.0.10-r01-unsigned-Harmony.hap'
  ].join(','), UpdateDownloadPure.staleFilesOf(dir, 'v0.0.10-r01-signed-Harmony.hap').join(','));
  assertEq(0, UpdateDownloadPure.staleFilesOf([], 'a.hap').length);
  assertEq(0, UpdateDownloadPure.staleFilesOf(['a.hap'], 'a.hap').length);
});

test('校验判据_无期望恒放行_有期望忽略大小写精确比对', () => {
  assertTrue(UpdateDownloadPure.sha256Matches(undefined, 'anything'));
  assertTrue(UpdateDownloadPure.sha256Matches('aa', 'aa'));
  assertTrue(UpdateDownloadPure.sha256Matches('AA', 'aa'));
  assertFalse(UpdateDownloadPure.sha256Matches('aa', 'ab'));
  assertFalse(UpdateDownloadPure.sha256Matches('aa', ''));
});

// ------------------------------------------------------------------ 尺子自己能红

test('尺子自己能红_排序退化成原序突变体必须偏离', () => {
  const input: Array<ChannelSpeed> = [speed('direct', 10), speed('mirror', 900), speed('dead', 0)];
  const urls: Array<string> = [];
  for (const s of input) {
    urls.push(s.url);
  }
  assertTrue(urls.join(',') !== UpdateDownloadPure.rankChannelsBySpeed(input).join(','));
});
