#!/usr/bin/env node
/**
 * 真机预检 + 走查脚本（T27 真机批与 23 条真机待验项的操作入口）。
 *
 * 用法（在仓库根或 WotaGeiCamOHOS/ 下）：
 *   node HarmonyOS/WotaGeiCamOHOS/scripts/device-check.mjs          # 预检：设备/安装态/签名链
 *   node HarmonyOS/WotaGeiCamOHOS/scripts/device-check.mjs log      # 实时 hilog（Wota* 全 tag）
 *   node HarmonyOS/WotaGeiCamOHOS/scripts/device-check.mjs check    # 打印 23 条待验清单（带出处）
 *
 * 退出码：0 = 预检通过（可以开始取证）；1 = 有阻塞项（按提示处理）；2 = 脚本自身问题。
 * 本脚本只读（hdc install 除外，见 --install 显式开关）：不改设备设置、不卸载应用。
 */
'use strict';

import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const BUNDLE = 'com.wotagei.camohos';
const HDC_CANDIDATES = [
  process.env.HDC_PATH,
  'D:/Application/HUAWEI/DevEco/DevEco Studio/Sdk/default/openharmony/toolchains/hdc.exe',
  // PATH 里的 hdc
  'hdc'
];

function findHdc() {
  for (const c of HDC_CANDIDATES) {
    if (!c) { continue; }
    try {
      execFileSync(c, ['list', 'targets'], { stdio: ['ignore', 'pipe', 'ignore'], timeout: 8000 });
      return c;
    } catch (e) {
      // try next
    }
  }
  return undefined;
}

function hdc(hdcPath, ...args) {
  return execFileSync(hdcPath, args, { encoding: 'utf-8', timeout: 20000 }).trim();
}

function main() {
  const mode = process.argv[2] || 'pre';
  // 清单模式不依赖设备（走查前的准备，可离线打印）
  if (mode === 'check') {
    printChecklist();
    return;
  }
  const hdcPath = findHdc();
  if (hdcPath === undefined) {
    console.error('✗ 找不到 hdc（设 HDC_PATH 或安装 DevEco SDK 工具链）');
    process.exit(2);
  }
  const targets = hdc(hdcPath, 'list', 'targets').split(/\r?\n/).map(s => s.trim()).filter(s => s && s !== '[Empty]');
  if (targets.length === 0) {
    console.log('✗ 无已连接设备。请：');
    console.log('  1. USB 连接 HarmonyOS 手机');
    console.log('  2. 手机上 开发者选项 → USB 调试 打开（首次连接会弹授权框，勾选“始终允许”）');
    console.log('  3. 重跑本脚本');
    process.exit(1);
  }
  const serial = targets[0];
  console.log(`✓ 设备已连接：${serial}（${targets.length > 1 ? '多台时取第一台' : '单台'}）`);

  if (mode === 'log') {
    console.log('--- hilog 实时（Wota* 全 tag；Ctrl+C 退出）---');
    try {
      execFileSync(hdcPath, ['shell', 'hilog'], { stdio: 'inherit', timeout: 0 });
    } catch (e) {
      // Ctrl+C
    }
    return;
  }

  // ---- 设备信息
  try {
    const model = hdc(hdcPath, 'shell', 'param', 'get', 'const.product.model');
    const osVer = hdc(hdcPath, 'shell', 'param', 'get', 'const.ohos.apiversion');
    console.log(`✓ 型号 ${model} · API ${osVer}`);
  } catch (e) {
    console.log('! 设备信息读取失败（不影响后续）');
  }

  // ---- 安装态
  let installed = false;
  try {
    const dump = hdc(hdcPath, 'shell', 'bm', 'dump', '-n', BUNDLE);
    installed = dump.length > 0 && !dump.includes('not found') && !dump.includes('查询结果不存在');
  } catch (e) {
    installed = false;
  }
  const signedHap = path.join(ROOT, 'HarmonyOS', 'WotaGeiCamOHOS', 'entry', 'build', 'default',
    'outputs', 'default', 'entry-default-signed.hap');
  console.log(`${installed ? '✓ 已安装 ' : '✗ 未安装 '}${BUNDLE}`);
  console.log(`${existsSync(signedHap) ? '✓ 本地签名包存在 ' : '✗ 本地签名包缺失（先跑 node scripts/local-sign.mjs）'}${path.basename(signedHap)}`);

  if (!installed && !existsSync(signedHap)) {
    console.log('\n→ 阻塞：既无已装应用也无本地签名包。先构建（assembleHap）+ 签名（local-sign.mjs），');
    console.log('  或用 DevEco Studio 自动签名后真机运行一次。');
    process.exit(1);
  }
  if (installed) {
    console.log('\n注意（既有红线，T31 登记）：若已装包签名指纹 ≠ 本地包（48A3BDCE… vs C8D36043…），');
    console.log('  直接 -r 覆盖安装会失败。两条路：');
    console.log('  a) 推荐：DevEco Studio 打开本工程 → 真机 Run（自动签名链，与已装包同源则直接覆盖）');
    console.log('  b) hdc uninstall ' + BUNDLE + ' 后 hdc install <signed.hap>（应用私有目录数据会丢，');
    console.log('     含已录视频——先确认可丢再走这条）');
  }

  // ---- pre 模式收尾
  console.log('\n预检通过后，取证入口：');
  console.log('  1. 应用 → 设置 → 版本号 7 连点解锁开发者入口 → Index 页');
  console.log('  2. 点「AVScreenCapture 探针」（T27-1：隐私弹窗形态 + 双源同采，约 2.5s）');
  console.log('  3. 23 条待验清单：node scripts/device-check.mjs check');
  process.exit(0);
}

function printChecklist() {
  const lines = [
    ['T31', '横竖屏 HUD 布局/系统栏往返/网格可见/胶囊刷新（16 项）', '相机页各向旋转、进设置返回'],
    ['C', '应用内更新：断网/联网检查、说明排版、下载取消、SHA256SUMS 缺失兜底', '设置 → 检查更新'],
    ['D', '导出平移（双音轨源）、已同步读数显示', '播放页导出（暂无内录源 → 读数应为“未同步”）'],
    ['E', '水平仪方向（横屏换算）、跟手度、平放不跳、单次震动、开关即时生效', '相机页右上姿态仪'],
    ['F', '参考线 11 档可见性、水平线方向、红线压顶', '设置 → 取景参考线逐档'],
    ['G', '分段归组显示/组级动作扇出/回收站退化恢复', '跨段录制后媒体库'],
    ['J3a', '读数条默认档 3 颗一行、120% 字体退档、全取消不渲染、时长红字', '相机页顶栏 + 设置'],
    ['J3b', '编辑页上下移后顺序生效、隐藏再显示位置保持、恢复默认', '设置 → 取景读数 → 调整顺序'],
    ['MS', 'systemShare 沙箱 URI 可读性（单发/多发）、中点帧精度、确认框取消形态', '媒体库卡片 分享/存入相册'],
    ['BT', '801 机型引导态、电量刷新实时性、双 A2DP 连接合并行为', '相机页 更多 → 蓝牙 → 音箱'],
    ['RT', '通知关闭降级（9800006）、切后台续录实测、系统通知形态', '起录后切后台'],
    ['FC', '手动对焦距离 API24 生效、定焦头降级注记、录制中对焦可调', '相机页 对焦胶囊'],
    ['GX', '长按与列表滚动冲突、多段组内清单滚动', '媒体库组卡长按'],
    ['P27', 'T27-1 探针三态：双源/单源/被拒（隐私弹窗形态即结论）', 'Index 页 → AVScreenCapture 探针']
  ];
  console.log('\n=== 真机待验清单（真源：PORT_MAP 各交付批节；此处为操作索引） ===');
  for (const [batch, what, how] of lines) {
    console.log(`  [${batch.padEnd(4)}] ${what}`);
    console.log(`         入口：${how}`);
  }
  console.log('\n实时日志：node scripts/device-check.mjs log（过滤 WotaGL|WotaRecord|WotaClip|WotaUpdate|WotaBt|WotaRecordTask）');
}

main();
