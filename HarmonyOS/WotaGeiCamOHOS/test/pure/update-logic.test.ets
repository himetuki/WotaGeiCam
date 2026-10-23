/**
 * 更新纯逻辑（UpdateChecker 全家）——上游 UpdateLogicTest.kt 直译 + 鸿蒙 versionCode 口径。
 *
 * 移植差异（登记）：
 * - 版本比较多一组：`versionCodeOf` / `isNewerThanVersionCode`（鸿蒙 versionName 不含 -rNN，
 *   打包工具拒绝该后缀，r 号进 versionCode；公式与 CI 的 Inject version 逐字一致）；
 * - 资产名从 `.apk` 换成 `.hap`，挑选优先 `-signed-Harmony.hap`；
 * - 段 3 的纯函数保留（tagFromLocation / guessAssetName），但编排里的注入函数在 OHOS 恒
 *   返回 undefined（平台拿不到中间 Location），故编排用例只在注入假身时覆盖段 3 分支。
 */
import {
  CompareVersions, MirrorChain, MirrorNode, ProbeKind, ProbeResult, RedirectProbe, ReleaseAsset,
  ReleaseJson, ReleaseNotes, UpdateConfig, probeLatestRelease
} from '../../entry/src/main/ets/update/UpdateChecker';

// ------------------------------------------------------------------ 版本比较（上游语义）

test('版本比较_同版本含r号_不算更新', () => {
  assertEq(0, CompareVersions.compare('0.0.7-r09', 'v0.0.7-r09'));
  assertFalse(CompareVersions.isNewerAvailable('0.0.7-r09', 'v0.0.7-r09'));
});

test('版本比较_主版本三段数字比大小', () => {
  assertTrue(CompareVersions.isNewerAvailable('0.0.7-r99', 'v0.0.8'));
  assertTrue(CompareVersions.isNewerAvailable('0.0.9', 'v0.1.0'));
  assertTrue(CompareVersions.isNewerAvailable('0.9.9', 'v1.0.0'));
  assertFalse(CompareVersions.isNewerAvailable('0.1.0', 'v0.0.99-r99'));
  assertFalse(CompareVersions.isNewerAvailable('1.0.0', 'v0.9.9'));
});

test('版本比较_主版本相同比r号_无r记0', () => {
  assertTrue(CompareVersions.isNewerAvailable('0.0.7', 'v0.0.7-r01'));
  assertTrue(CompareVersions.isNewerAvailable('0.0.7-r01', 'v0.0.7-r02'));
  assertFalse(CompareVersions.isNewerAvailable('0.0.7-r05', 'v0.0.7'));
  // 两位数 r 号按数值比（不是字符串比）
  assertTrue(CompareVersions.isNewerAvailable('0.0.7-r09', 'v0.0.7-r10'));
  assertFalse(CompareVersions.isNewerAvailable('0.0.7-r09', 'v0.0.7-r08'));
});

test('版本比较_解析失败保守不提示', () => {
  assertFalse(CompareVersions.isNewerAvailable('0.0.7-r09', 'v0.0.8-beta'));
  assertFalse(CompareVersions.isNewerAvailable('0.0.7-r09', 'v0.0.8-r01-x'));
  assertFalse(CompareVersions.isNewerAvailable('0.0.7-r09', 'v0.0.8.1'));
  assertFalse(CompareVersions.isNewerAvailable('0.0.7-r09', 'nonsense'));
  assertFalse(CompareVersions.isNewerAvailable('0.0.7-r09', ''));
  assertFalse(CompareVersions.isNewerAvailable('garbage', 'v0.1.0'));
});

// ------------------------------------------------------------------ 鸿蒙 versionCode 口径

test('versionCode_tag折算与CI公式一致', () => {
  // 1000000 + major*1e6 + minor*1e4 + patch*100 + r
  assertEq(1001005, CompareVersions.versionCodeOf('v0.0.10-r05'));
  assertEq(1000709, CompareVersions.versionCodeOf('v0.0.7-r09'));
  assertEq(1000700, CompareVersions.versionCodeOf('v0.0.7'));
  assertEq(2000000, CompareVersions.versionCodeOf('v1.0.0'));
  assertEq(1010103, CompareVersions.versionCodeOf('v0.1.1-r03'));
  assertNull(CompareVersions.versionCodeOf('v0.0.8-beta'));
  assertNull(CompareVersions.versionCodeOf('0.0'));
});

test('versionCode_同r不提示_更高r或更高patch提示', () => {
  // 本机已装 v0.0.10-r05（CI 注入 versionCode=1001005）
  assertFalse(CompareVersions.isNewerThanVersionCode(1001005, 'v0.0.10-r05'));
  assertTrue(CompareVersions.isNewerThanVersionCode(1001005, 'v0.0.10-r06'));
  // 远端无 r（r=0）比本机 r=5 旧 → 不提示
  assertFalse(CompareVersions.isNewerThanVersionCode(1001005, 'v0.0.10'));
  assertTrue(CompareVersions.isNewerThanVersionCode(1001005, 'v0.0.11-r01'));
  assertTrue(CompareVersions.isNewerThanVersionCode(1001005, 'v0.1.0'));
  // tag 坏了：保守不提示
  assertFalse(CompareVersions.isNewerThanVersionCode(1001005, 'nonsense'));
});

// ------------------------------------------------------------------ 镜像链状态机

function chainIssues(chain: MirrorChain, expectedPrefixes: Array<string>, rawUrl: string): Array<string> {
  const labels: Array<string> = [];
  const urls: Array<string> = [];
  while (!chain.exhausted) {
    labels.push(chain.currentLabel);
    urls.push(chain.urlFor(rawUrl));
    chain.advance();
  }
  const issues: Array<string> = [];
  if (labels.length !== expectedPrefixes.length) {
    issues.push(`节点数 ${labels.length} != ${expectedPrefixes.length}`);
  }
  for (let i = 0; i < expectedPrefixes.length; i++) {
    if (labels[i] !== expectedPrefixes[i]) {
      issues.push(`节点顺序 ${labels[i]} != ${expectedPrefixes[i]}`);
    }
    if (urls[i] !== expectedPrefixes[i] + rawUrl) {
      issues.push(`URL 变换与节点前缀不符：${urls[i]}`);
    }
  }
  if (chain.advance()) {
    issues.push('耗尽后 advance 必须返回 false');
  }
  return issues;
}

test('探测镜像链_只走确认能代理API的前缀_顺序为表序', () => {
  const issues: Array<string> = chainIssues(
    MirrorChain.probeChain(), UpdateConfig.API_MIRROR_PREFIXES, UpdateConfig.REPO_API_LATEST);
  assertEq(0, issues.length, issues.join('; '));
  // API 前缀必须是镜像表的子集（不许凭空多出没经过下载实测的域名）
  for (const p of UpdateConfig.API_MIRROR_PREFIXES) {
    assertTrue(UpdateConfig.MIRROR_PREFIXES.indexOf(p) >= 0, `API 前缀不在镜像表：${p}`);
  }
});

test('镜像链_reset后从头再试', () => {
  const chain: MirrorChain = MirrorChain.probeChain();
  chain.advance();
  chain.advance();
  assertEq(2, chain.cursor);
  chain.reset();
  assertEq(0, chain.cursor);
});

test('镜像链尺子自己能红_调序突变体必报红', () => {
  const raw: string = UpdateConfig.REPO_API_LATEST;
  const reversedPrefixes: Array<string> = [];
  for (let i = UpdateConfig.API_MIRROR_PREFIXES.length - 1; i >= 0; i--) {
    reversedPrefixes.push(UpdateConfig.API_MIRROR_PREFIXES[i]);
  }
  const nodes: Array<MirrorNode> = [];
  for (const p of reversedPrefixes) {
    nodes.push(new MirrorNode(p, (u: string): string => p + u));
  }
  const issues: Array<string> = chainIssues(new MirrorChain(nodes), UpdateConfig.API_MIRROR_PREFIXES, raw);
  assertTrue(issues.length > 0, '调换镜像顺序后尺子必须红');
});

// ------------------------------------------------------------------ JSON 提取（GitHub 真实形态节选）

const sampleJson: string = `{
  "url": "https://api.github.com/repos/himetuki/WotaGeiCam/releases/1",
  "tag_name": "v0.0.8-r01",
  "name": "WotaGeiCam v0.0.8-r01",
  "assets": [
    {
      "url": "https://api.github.com/assets/11",
      "name": "v0.0.8-r01-unsigned-Harmony.hap",
      "browser_download_url": "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/v0.0.8-r01-unsigned-Harmony.hap",
      "size": 12345678
    },
    {
      "url": "https://api.github.com/assets/12",
      "name": "v0.0.8-r01-signed-Harmony.hap",
      "browser_download_url": "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/v0.0.8-r01-signed-Harmony.hap",
      "size": 23456789
    },
    {
      "url": "https://api.github.com/assets/13",
      "name": "mapping.txt",
      "browser_download_url": "https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/mapping.txt",
      "size": 999
    }
  ],
  "body": "release notes with \\"quoted\\" text"
}`;

test('JSON提取_tag与资产_优先signed包', () => {
  assertEq('v0.0.8-r01', ReleaseJson.parseTag(sampleJson));
  const info = ReleaseJson.parse(sampleJson, false);
  assertTrue(info !== undefined);
  assertEq('v0.0.8-r01', info!.tag);
  // 优先 signed-Harmony（可装），不是 unsigned，也不是 mapping.txt
  assertEq('v0.0.8-r01-signed-Harmony.hap', info!.asset!.name);
  assertEq(23456789, info!.asset!.sizeBytes);
  assertTrue(info!.asset!.url.indexOf('signed-Harmony.hap') > 0);
  // JSON 里的 \\" 转义要还原
  assertEq('release notes with "quoted" text', info!.notes);
});

test('JSON提取_无signed时取第一个hap_无hap为null', () => {
  const noSigned: string = '{"tag_name": "v0.1.0", "assets": [' +
    '{"name": "mapping.txt", "browser_download_url": "https://x/m.txt", "size": 1},' +
    '{"name": "v0.1.0-unsigned-Harmony.hap", "browser_download_url": "https://x/a.hap", "size": 7}' +
    ']}';
  assertEq('v0.1.0-unsigned-Harmony.hap', ReleaseJson.pickAsset(ReleaseJson.parseAssets(noSigned))!.name);

  const noHap: string = '{"tag_name": "v0.1.0", "assets": [' +
    '{"name": "mapping.txt", "browser_download_url": "https://x/m.txt", "size": 1}]}';
  assertNull(ReleaseJson.pickAsset(ReleaseJson.parseAssets(noHap)));
  const parsed = ReleaseJson.parse(noHap, false);
  assertTrue(parsed !== undefined);
  assertNull(parsed!.asset);

  const noAssets = ReleaseJson.parse('{"tag_name": "v0.1.0"}', false);
  assertTrue(noAssets !== undefined);
  assertNull(noAssets!.asset);
  assertNull(ReleaseJson.parse('{"assets": []}', false));
});

test('JSON提取尺子自己能红_tag变化必须被提取出来', () => {
  const mutant: string = sampleJson.split('v0.0.8-r01').join('v0.9.9-r77');
  assertEq('v0.9.9-r77', ReleaseJson.parseTag(mutant));
});

test('资产名安全判据_只接受纯文件名hap', () => {
  assertTrue(ReleaseJson.isSafeReleaseAssetName('v0.0.9-r03-signed-Harmony.hap'));
  assertFalse(ReleaseJson.isSafeReleaseAssetName('../evil.hap'));
  assertFalse(ReleaseJson.isSafeReleaseAssetName('dir\\evil.hap'));
  assertFalse(ReleaseJson.isSafeReleaseAssetName('/sdcard/evil.hap'));
  assertFalse(ReleaseJson.isSafeReleaseAssetName('mapping.txt'));
  assertFalse(ReleaseJson.isSafeReleaseAssetName(''));
  assertFalse(ReleaseJson.isSafeReleaseAssetName(undefined));
  let longName: string = '';
  for (let i = 0; i < 256; i++) {
    longName += 'a';
  }
  assertFalse(ReleaseJson.isSafeReleaseAssetName(longName + '.hap'));
  // 不可信资产名不得被 pickAsset 选中（否则会拼进下载 URL）
  const unsafe: Array<ReleaseAsset> = [new ReleaseAsset('../evil.hap', 'https://x/e.hap', 1)];
  assertNull(ReleaseJson.pickAsset(unsafe));
});

test('redirect解析_从Location抠tag_资产名按鸿蒙产物推导', () => {
  assertEq('v0.0.8-r01',
    RedirectProbe.tagFromLocation('https://github.com/himetuki/WotaGeiCam/releases/tag/v0.0.8-r01'));
  assertEq('v0.0.8-r01',
    RedirectProbe.tagFromLocation('https://github.com/himetuki/WotaGeiCam/releases/tag/v0.0.8-r01/'));
  assertNull(RedirectProbe.tagFromLocation('https://github.com/himetuki/WotaGeiCam/releases'));
  assertNull(RedirectProbe.tagFromLocation(''));
  // build-hap.yml 的产物名：<tag>-unsigned-Harmony.hap / -signed-Harmony.hap
  assertEq('v0.0.8-r01-unsigned-Harmony.hap', RedirectProbe.guessAssetName('v0.0.8-r01'));
  assertEq('https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/' +
    'v0.0.8-r01-unsigned-Harmony.hap',
    UpdateConfig.directDownloadUrl('v0.0.8-r01', 'v0.0.8-r01-unsigned-Harmony.hap'));
});

// ------------------------------------------------------------------ 段 1→2→3 编排（网络注入假身）

test('编排_段1直连成功即用段1', async () => {
  const hit: Array<string> = [];
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> => {
      hit.push(url);
      return url === UpdateConfig.REPO_API_LATEST ? sampleJson : undefined;
    },
    async (url: string): Promise<string | undefined> => undefined);
  assertEq(ProbeKind.UPDATE_AVAILABLE, r.kind);
  assertEq('v0.0.8-r01', r.latestTag);
  // 段 1 成功就不该再碰镜像
  assertEq(1, hit.length);
});

test('编排_段1失败走段2镜像_镜像按表序逐个试', async () => {
  const hit: Array<string> = [];
  const mirrorJson: string = '{"tag_name": "v0.0.9", "assets": [' +
    '{"name": "v0.0.9-signed-Harmony.hap", "browser_download_url": "https://x/u.hap", "size": 5}]}';
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> => {
      hit.push(url);
      return url.indexOf(UpdateConfig.MIRROR_PREFIXES[1]) === 0 ? mirrorJson : undefined;
    },
    async (url: string): Promise<string | undefined> => undefined);
  assertEq(ProbeKind.UPDATE_AVAILABLE, r.kind);
  assertEq('v0.0.9', r.latestTag);
  assertEq(3, hit.length);
  assertEq(UpdateConfig.REPO_API_LATEST, hit[0]);
  assertEq(0, hit[1].indexOf(UpdateConfig.MIRROR_PREFIXES[0]));
  assertEq(0, hit[2].indexOf(UpdateConfig.MIRROR_PREFIXES[1]));
});

test('编排_镜像JSON的下载URL不得被采信_一律重建为仓库直连', async () => {
  const evil: string = '{"tag_name": "v0.0.9", "assets": [' +
    '{"name": "v0.0.9-signed-Harmony.hap", "browser_download_url": "https://evil.example/payload.hap", "size": 5}]}';
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> =>
      url.indexOf(UpdateConfig.MIRROR_PREFIXES[0]) === 0 ? evil : undefined,
    async (url: string): Promise<string | undefined> => undefined);
  assertEq(ProbeKind.UPDATE_AVAILABLE, r.kind);
  assertEq(UpdateConfig.directDownloadUrl('v0.0.9', 'v0.0.9-signed-Harmony.hap'), r.downloadUrl);
  assertFalse(r.downloadUrl.indexOf('evil.example') >= 0);
});

test('编排_全JSON失败段3redirect给出tag与推导资产', async () => {
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> => undefined,
    async (url: string): Promise<string | undefined> =>
      'https://github.com/himetuki/WotaGeiCam/releases/tag/v0.0.8-r01');
  assertEq(ProbeKind.UPDATE_AVAILABLE, r.kind);
  assertEq('v0.0.8-r01', r.latestTag);
  assertEq('v0.0.8-r01-unsigned-Harmony.hap', r.assetName);
  assertEq(-1, r.sizeBytes); // 段 3 无 size → 下载时用 Content-Length
  assertEq('https://github.com/himetuki/WotaGeiCam/releases/download/v0.0.8-r01/' +
    'v0.0.8-r01-unsigned-Harmony.hap', r.downloadUrl);
});

test('编排_远端不比本机新_即时报已是最新不再走后续段', async () => {
  const hit: Array<string> = [];
  const older: string = '{"tag_name": "v0.0.6", "assets": [' +
    '{"name": "a.hap", "browser_download_url": "https://x/a.hap", "size": 5}]}';
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> => {
      hit.push(url);
      return url === UpdateConfig.REPO_API_LATEST ? older : undefined;
    },
    async (url: string): Promise<string | undefined> => undefined);
  assertEq(ProbeKind.UP_TO_DATE, r.kind);
  assertEq(1, hit.length);
});

test('编排_有新版但JSON无hap附件_该节点无结论继续下一段', async () => {
  const noHap: string = '{"tag_name": "v0.9.9", "assets": [' +
    '{"name": "mapping.txt", "browser_download_url": "https://x/m.txt", "size": 1}]}';
  const ok: string = '{"tag_name": "v0.9.9", "assets": [' +
    '{"name": "v0.9.9-signed-Harmony.hap", "browser_download_url": "https://x/u.hap", "size": 5}]}';
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> => {
      if (url === UpdateConfig.REPO_API_LATEST) {
        return noHap;
      }
      return url.indexOf(UpdateConfig.MIRROR_PREFIXES[0]) === 0 ? ok : undefined;
    },
    async (url: string): Promise<string | undefined> => undefined);
  assertEq(ProbeKind.UPDATE_AVAILABLE, r.kind);
});

test('编排_三段全失败_报失败', async () => {
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> => undefined,
    async (url: string): Promise<string | undefined> => undefined);
  assertEq(ProbeKind.FAILED, r.kind);
});

test('编排_开始前已取消_一个节点都不碰', async () => {
  const hit: Array<string> = [];
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> => {
      hit.push(url);
      return undefined;
    },
    async (url: string): Promise<string | undefined> => undefined,
    (): boolean => true);
  assertEq(ProbeKind.CANCELLED, r.kind);
  assertEq(0, hit.length);
});

test('编排_节点失败后取消_剩余镜像与段3全部跳过', async () => {
  const hit: Array<string> = [];
  let calls: number = 0;
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> => {
      hit.push(url);
      calls++;
      return undefined;
    },
    async (url: string): Promise<string | undefined> => undefined,
    (): boolean => calls >= 1);
  assertEq(ProbeKind.CANCELLED, r.kind);
  assertEq(1, hit.length);
  assertEq(UpdateConfig.REPO_API_LATEST, hit[0]);
});

// ------------------------------------------------------------------ Release 说明整形（上游 UpdateNotesTest）

const hashHex: string = '0123456789abcdef'.repeat(4);

function jsonWithBody(body: string): string {
  return '{"tag_name": "v0.0.9-r07", "assets": [{"name": "v0.0.9-r07-signed-Harmony.hap", ' +
    '"browser_download_url": "https://x/u.hap", "size": 5}], "body": "' + body + '"}';
}

test('parse_说明原文两段都读_哈希仍只信段1', () => {
  const json: string = jsonWithBody('第一行\\n第二行');
  assertEq('第一行\n第二行', ReleaseJson.parse(json, true)!.notes);
  assertEq('第一行\n第二行', ReleaseJson.parse(json, false)!.notes);
  assertNull(ReleaseJson.parse('{"tag_name": "v0.0.9-r07", "body": null}', true)!.notes);
});

test('编排_段1与镜像段的新版都带说明', async () => {
  const json: string = jsonWithBody('说明正文\\n- 条目');
  const direct: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> =>
      url === UpdateConfig.REPO_API_LATEST ? json : undefined,
    async (url: string): Promise<string | undefined> => undefined);
  assertEq('说明正文\n- 条目', direct.notes);

  const viaMirror: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> =>
      url !== UpdateConfig.REPO_API_LATEST ? json : undefined,
    async (url: string): Promise<string | undefined> => undefined);
  assertEq('说明正文\n- 条目', viaMirror.notes);
});

test('展示整形_去标题井号与粗体与条目符号_文字不动', () => {
  const raw: string = [
    '# WotaGeiCam v0.0.9-r07',
    '',
    '一句话说明。',
    '',
    '## 本次更新',
    '',
    '### 视频更安全',
    '',
    '- **导出失败留下的视频更容易被找回来**：说明文字。',
    '* 第二条目 `代码样式` 保留原文。',
    '+ 第三条目',
    '---'
  ].join('\n');
  assertEq([
    'WotaGeiCam v0.0.9-r07',
    '',
    '一句话说明。',
    '',
    '本次更新',
    '',
    '视频更安全',
    '',
    '• 导出失败留下的视频更容易被找回来：说明文字。',
    '• 第二条目 代码样式 保留原文。',
    '• 第三条目'
  ].join('\n'), ReleaseNotes.forDisplay(raw));
});

test('展示整形_SHA256行与它上面的标签行隐去', () => {
  const raw: string = [
    '## 安装',
    '',
    '- 请下载 universal 包。',
    '- universal 包 SHA256：',
    'SHA256: ' + hashHex,
    '- 升级请直接覆盖安装。'
  ].join('\n');
  assertEq('安装\n\n• 请下载 universal 包。\n• 升级请直接覆盖安装。', ReleaseNotes.forDisplay(raw));
  assertEq('安装\n\n• 升级请直接覆盖安装。',
    ReleaseNotes.forDisplay('## 安装\n\nuniversal 包 SHA256: ' + hashHex + '\n- 升级请直接覆盖安装。'));
});

test('展示整形_空说明与只剩哈希行返回null', () => {
  assertNull(ReleaseNotes.forDisplay(undefined));
  assertNull(ReleaseNotes.forDisplay('   \n\t\n'));
  assertNull(ReleaseNotes.forDisplay('SHA256: ' + hashHex));
});

test('展示整形_超长按行截断补省略号_上限是绝对4000', () => {
  assertEq(4000, ReleaseNotes.MAX_DISPLAY_CHARS);
  const lines: Array<string> = [];
  for (let i = 1; i <= 1200; i++) {
    lines.push('第' + i + '行');
  }
  const long: string = lines.join('\n');
  assertTrue(long.length > ReleaseNotes.MAX_DISPLAY_CHARS, '样本必须真的超上限');
  const out: string | undefined = ReleaseNotes.forDisplay(long);
  assertTrue(out !== undefined);
  assertTrue(out!.length <= ReleaseNotes.MAX_DISPLAY_CHARS + 2, '截断后不得超过上限（外加省略号）');
  assertTrue(out!.endsWith('\n…'), '截断必须补省略号');
  const kept: Array<string> = out!.substring(0, out!.length - 2).split('\n');
  assertEq('第' + kept.length + '行', kept[kept.length - 1], '截断必须落在行边界');
  assertFalse(out!.indexOf('第1200行') >= 0);
});

test('尺子自己能红_原样返回的突变体必须偏离', () => {
  const raw: string = '## 标题\n- **条目**\nSHA256: ' + hashHex;
  const mutant = (s: string | undefined): string | undefined => s;
  assertTrue(mutant(raw) !== ReleaseNotes.forDisplay(raw));
});

test('parse_段1开关开才提取哈希_镜像段恒不信任', () => {
  const hex: string = 'deadbeefcafebabe0123456789abcdef'.repeat(2).substring(0, 64);
  assertEq(64, hex.length);
  const json: string = jsonWithBody('更新说明\\nSHA256: ' + hex + '\\n安装段');
  assertEq(hex, ReleaseJson.parse(json, true)!.sha256);
  // 段 2：镜像转发 body 可被代理改写 → 不提取
  assertNull(ReleaseJson.parse(json, false)!.sha256);
  // 说明原文两段都读（只用于展示）
  assertEq('更新说明\nSHA256: ' + hex + '\n安装段', ReleaseJson.parse(json, false)!.notes);
  assertNull(ReleaseJson.parse(jsonWithBody('普通说明'), true)!.sha256);
});

test('说明提取_标准格式与变体命中_输出小写', () => {
  const hex: string = '1234567890abcdef'.repeat(4);
  assertEq(hex, ReleaseJson.parseSha256FromNotes('SHA256: ' + hex));
  assertEq(hex.toLowerCase(), ReleaseJson.parseSha256FromNotes('SHA256: ' + hex.toUpperCase()));
  assertEq(hex, ReleaseJson.parseSha256FromNotes('SHA256：' + hex));
  assertEq(hex, ReleaseJson.parseSha256FromNotes('SHA256  :   ' + hex));
  assertEq(hex, ReleaseJson.parseSha256FromNotes('本次更新说明\n共若干项改进\nSHA256: ' + hex + '\n安装段'));
});

test('说明提取_非标准形态一律不命中', () => {
  const hex64: string = '1234567890abcdef'.repeat(4);
  assertNull(ReleaseJson.parseSha256FromNotes('SHA256: ' + hex64.substring(0, 63)));
  assertNull(ReleaseJson.parseSha256FromNotes('SHA256: ' + hex64 + 'f'));
  assertNull(ReleaseJson.parseSha256FromNotes('更新内容如下，共若干项'));
  assertNull(ReleaseJson.parseSha256FromNotes('sha256: ' + hex64));
  assertNull(ReleaseJson.parseSha256FromNotes(''));
});

// ------------------------------------------------------------------ SHA256SUMS（HAP 的校验值来源）

test('SHA256SUMS_按资产名精确匹配_容忍binary星号', () => {
  const a: string = 'a'.repeat(64);
  const b: string = 'B'.repeat(64);
  const sums: string = [
    `${a}  v0.0.10-r01-signed-Harmony.hap`,
    `${b} *v0.0.10-r01-unsigned-Harmony.hap`,
    ''
  ].join('\n');
  assertEq(a, ReleaseJson.parseSha256Sums(sums, 'v0.0.10-r01-signed-Harmony.hap'));
  // 大写输入归一成小写（与 sha256Matches 的大小写容忍同一口径）
  assertEq(b.toLowerCase(), ReleaseJson.parseSha256Sums(sums, 'v0.0.10-r01-unsigned-Harmony.hap'));
  // 资产名必须整名相等（子串不算）
  assertNull(ReleaseJson.parseSha256Sums(sums, 'signed-Harmony.hap'));
  assertNull(ReleaseJson.parseSha256Sums('', 'x.hap'));
  assertNull(ReleaseJson.parseSha256Sums('not a sums file', 'x.hap'));
});

test('编排_不把说明里的安卓APK哈希当HAP校验值', async () => {
  // 发版说明里的 SHA256 行是 build-apk.yml 回填的 universal APK 哈希；
  // 拿它校验 HAP 必然失败 → 探测结论里的 sha256 必须恒为 undefined（改由 SHA256SUMS 提供）。
  const hex: string = 'deadbeefcafebabe0123456789abcdef'.repeat(2).substring(0, 64);
  const json: string = '{"tag_name": "v0.9.9", "body": "SHA256: ' + hex + '", "assets": [' +
    '{"name": "v0.9.9-signed-Harmony.hap", "browser_download_url": "https://x/u.hap", "size": 5}]}';
  const r: ProbeResult = await probeLatestRelease(1000709,
    async (url: string): Promise<string | undefined> =>
      url === UpdateConfig.REPO_API_LATEST ? json : undefined,
    async (url: string): Promise<string | undefined> => undefined);
  assertEq(ProbeKind.UPDATE_AVAILABLE, r.kind);
  assertNull(r.sha256);
});
