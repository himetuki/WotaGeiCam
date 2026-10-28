/**
 * 蓝牙音箱面板（BT 批）：纯逻辑（BtSpeakerModel 的合并/排序/文案）+ 三条链的接线守卫。
 * 平台面（BtSpeakerController）在 pure-test 里只有惰性桩；减法定版（音量/播控不迁）由守卫钉住。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { BtEntry, BtSpeakerModel } from '../../entry/src/main/ets/bt/BtSpeakerModel';

function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/ui/HudSpec.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

function entry(id: string, name: string, battery: number): BtEntry {
  return new BtEntry(id, name, false, battery);
}

function ids(entries: Array<BtEntry>): string {
  return entries.map((e: BtEntry): string => `${e.deviceId}${e.a2dpConnected ? '*' : ''}`).join(',');
}

// ------------------------------------------------------------------ 合并/排序（纯逻辑）

test('合并_配对表打上A2DP连接态并按已连接置前', () => {
  const paired: Array<BtEntry> = [entry('A', '音箱甲', 80), entry('B', '音箱乙', -1), entry('C', '耳机丙', 30)];
  const merged: Array<BtEntry> = BtSpeakerModel.mergeEntries(paired, ['B']);
  assertEq('B*,A,C', ids(merged), '乙已连接置前，其余稳定保输入序（不做名字排序）');
  assertTrue(merged[0].a2dpConnected, '乙标已连接');
  assertFalse(merged[1].a2dpConnected, '甲未连接');
});

test('合并_A2DP连接表里有而配对表没有的设备补进来', () => {
  const merged: Array<BtEntry> = BtSpeakerModel.mergeEntries([entry('A', '甲', 50)], ['A', 'Z']);
  assertEq('A*,Z*', ids(merged), 'Z 不在配对表也被补入（刚连上、配对表未刷的场景）');
  assertEq('Z', merged[1].name, '补入条目名字未知时回退 deviceId');
});

test('合并_同id去重不重复出条', () => {
  const merged: Array<BtEntry> = BtSpeakerModel.mergeEntries(
    [entry('A', '甲', 10), entry('A', '甲', 20)], ['A']);
  assertEq(1, merged.length, 'deviceId 相同只出一条');
  assertEq(20, merged[0].batteryLevel, '后者胜（平台层顺序注入，纯函数只保证不重复）');
});

test('排序_只按连接态_未连接稳定保输入序', () => {
  const paired: Array<BtEntry> = [
    entry('D', 'b箱', -1), entry('C', 'a箱', -1), entry('B', '音箱乙', 40), entry('E', '', -1)
  ];
  const merged: Array<BtEntry> = BtSpeakerModel.mergeEntries(paired, ['B']);
  assertEq('B*,D,C,E', ids(merged), '乙已连接第一；其余 D/C/E 保持输入序（名字字节序无意义，不重排）');
});

// ------------------------------------------------------------------ 文案（纯逻辑）

test('电量文案_未上报显示破折号不编0', () => {
  assertEq('电量 —', BtSpeakerModel.batteryText(-1), '系统没报 = 如实说没有（上游 null 同义）');
  assertEq('电量 —', BtSpeakerModel.batteryText(101), '越界当未上报');
  assertEq('电量 —', BtSpeakerModel.batteryText(-2));
  assertEq('电量 0%', BtSpeakerModel.batteryText(0), '0 是合法低电（不许和未知混写）');
  assertEq('电量 80%', BtSpeakerModel.batteryText(80));
});

test('状态文案两态', () => {
  assertEq('已连接', BtSpeakerModel.stateText(true));
  assertEq('未连接', BtSpeakerModel.stateText(false));
});

// ------------------------------------------------------------------ 接线守卫（源码扫描）

test('接线_数据源走connection与a2dp_电量不走GATT', () => {
  const ctrl: string = sourceText('entry/src/main/ets/bt/BtSpeakerController.ets');
  assertTrue(ctrl.indexOf('connection.getPairedDevices()') >= 0, '配对表走 getPairedDevices');
  assertTrue(ctrl.indexOf('createA2dpSrcProfile()') >= 0 && ctrl.indexOf('getConnectedDevices()') >= 0,
    'A2DP 连接表走 a2dp profile');
  assertTrue(ctrl.indexOf('getRemoteDeviceBatteryInfo') >= 0,
    '电量走 getRemoteDeviceBatteryInfo（since 12）——不走 GATT 电池服务（PORT_MAP 原登记已修订）');
  assertTrue(ctrl.indexOf("connection.on('batteryChange'") >= 0, '电量实时刷新挂 batteryChange');
  assertTrue(ctrl.indexOf("connection.off('batteryChange'") >= 0, '解绑存在（页面销毁必停订阅）');
  // 减法定版红线：不许出现音量写/播控/扫描发现
  assertTrue(ctrl.indexOf('setVolume') < 0, '不迁音量写（无 setVolume 等价，登记）');
  assertTrue(ctrl.indexOf('avsession') < 0 && ctrl.indexOf('AVSession') < 0,
    '不迁播控三键（avsession 控制器需 system 权限，登记）');
  assertTrue(ctrl.indexOf('startBluetoothDiscovery') < 0,
    '不迁扫描发现（需定位权限；连接管理交给系统设置，登记）');
});

test('接线_面板页生命周期与入口', () => {
  const page: string = sourceText('entry/src/main/ets/pages/BtPanel.ets');
  assertTrue(page.indexOf('BtSpeakerController.watchBattery') >= 0, '页面挂电量监听');
  assertTrue(page.indexOf('BtSpeakerController.unwatchBattery()') >= 0 &&
    page.indexOf('this.disposed = true;') >= 0, '销毁必解订阅（蓝牙订阅常开费电）');
  assertTrue(page.indexOf('adapterOn') >= 0, '蓝牙未开走引导态而不是空列表装没事');

  const sheet: string = sourceText('entry/src/main/ets/ui/HudMoreSheet.ets');
  assertTrue(sheet.indexOf('this.onOpenBt();') >= 0, '「更多」面板必须有蓝牙音箱入口');

  // 2026-10-28 用户裁决：安卓侧无「更多」面板——入口锚点从相机页改挂设置页「屏幕监看」组
  //（守卫语义不变：蓝牙音箱必须有接线跳转到 BtPanel）
  const settings: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(settings.indexOf("pushUrl({ url: 'pages/BtPanel' })") >= 0,
    '设置页「屏幕监看」组必须有蓝牙音箱入口');

  const pages: string = readFileSync(resolve(srcRoot(),
    'entry/src/main/resources/base/profile/main_pages.json'), 'utf8');
  assertTrue(pages.indexOf('pages/BtPanel') >= 0, 'BtPanel 必须登记进 main_pages.json');

  const manifest: string = readFileSync(resolve(srcRoot(), 'entry/src/main/module.json5'), 'utf8');
  assertTrue(manifest.indexOf('ohos.permission.ACCESS_BLUETOOTH') >= 0,
    '必须声明 ACCESS_BLUETOOTH（normal 级，只声明不弹窗）');
});
