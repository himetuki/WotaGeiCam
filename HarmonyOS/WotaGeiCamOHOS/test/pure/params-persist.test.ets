/**
 * 录制页参数持久化（ui/ParamsPersist.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/ParamsPersistSessionTest.kt +
 * ParamsPersistWiringGuardTest.kt（纯函数/纯 prefs 面；Compose 接线守卫本侧以
 * Camera.ets 的接入点人工核对，见交付报告）。
 *
 * 覆盖：① 24 键白名单契约（手写清单双向核对 + 排除清单）；② restore 每键正确值；
 * ③ 坏值/缺失回默认；④ fps 钳制、快门/ISO/变焦 >0 门槛、LOCK 归一、屈光度负数归 0、
 * 尺寸串解析；⑤ 枚举串大小写与坏值；⑥ 会话影子语义（只写变化字段/幂等/影子前进/
 * 写失败重试/resync/requestedSize 意图）；⑦ 键名迁移（旧键 fps=24 → 新键缺失时读出 24）。
 *
 * 期望值一律手写字面量（不用实现反推）；prefs 用纯 map 替身（上游 FakePrefs 的最小面）。
 */
import {
  PARAMS_KEYS, ParamsMigrate, ParamsPersist, ParamsPersistSession, ParamsPrefs, PrefEnum,
  enumFrom, enumName, formatSize, parseSize
} from '../../entry/src/main/ets/ui/ParamsPersist';
import { ParamState, WotaParams } from '../../entry/src/main/ets/core/WotaParams';
import {
  AeMode, AfMode, Flash, FrameEffect, PeakingColor, Size, Stabilize, WotaTiers, WbPreset
} from '../../entry/src/main/ets/core/Constants';
import { LensType } from '../../entry/src/main/ets/core/LensSlot';

type PrefRaw = string | number | boolean;

/** 纯 map 假 prefs：记录 put 调用序列（会话影子用例的断言面）；failPuts 模拟写盘失败 */
class FakeParamsPrefs implements ParamsPrefs {
  map: Map<string, PrefRaw> = new Map<string, PrefRaw>();
  puts: Array<string> = [];
  failPuts: boolean = false;

  getInt(key: string, def: number): number {
    const raw: PrefRaw | undefined = this.map.get(key);
    return typeof raw === 'number' ? Math.trunc(raw) : def;
  }

  getBool(key: string, def: boolean): boolean {
    const raw: PrefRaw | undefined = this.map.get(key);
    return typeof raw === 'boolean' ? raw : def;
  }

  getFloat(key: string, def: number): number {
    const raw: PrefRaw | undefined = this.map.get(key);
    return typeof raw === 'number' ? raw : def;
  }

  getString(key: string, def: string): string {
    const raw: PrefRaw | undefined = this.map.get(key);
    return typeof raw === 'string' ? raw : def;
  }

  putInt(key: string, value: number): boolean {
    return this.record(key, Math.trunc(value));
  }

  putBool(key: string, value: boolean): boolean {
    return this.record(key, value);
  }

  putFloat(key: string, value: number): boolean {
    return this.record(key, value);
  }

  putString(key: string, value: string): boolean {
    return this.record(key, value);
  }

  private record(key: string, value: PrefRaw): boolean {
    if (this.failPuts) {
      return false;
    }
    this.map.set(key, value);
    this.puts.push(key);
    return true;
  }
}

/** 拍板清单的手写字面量（与 PARAMS_KEYS 双向核对；顺序也钉） */
const EXPECTED_KEYS: Array<string> = [
  'default_fps', 'default_shutter_ns', 'default_bitrate', 'default_ref_lines', 'level_enabled',
  'default_iso', 'default_ae_mode', 'default_ev', 'default_wb_preset', 'default_wb_kelvin',
  'default_wb_tint', 'default_zoom', 'default_flash', 'default_stabilize', 'default_af_mode',
  'default_mf_diopter', 'default_size', 'default_frame_effect', 'default_zebra_threshold',
  'default_zebra_density', 'default_peaking_strength', 'default_peaking_color', 'default_lens',
  'default_arc_merge_deadband'
];

/** 排除清单的**上游实际键名**（renderMode/sampleRate/audioEnabled/focusPoint/curve/arc_convert_mode） */
const EXCLUDED_KEYS: Array<string> = [
  'default_render_mode', 'default_sample_rate', 'default_audio_muted', 'focus_point',
  'curve_stack', 'arc_convert_mode'
];

/** 把 24 个键都填成非默认合法值（restore 正例与迁移/会话用例共用） */
function fillAllKeys(p: FakeParamsPrefs): void {
  p.map.set('default_fps', 60);
  p.map.set('default_shutter_ns', 8_333_333);
  p.map.set('default_bitrate', 35_000_000);
  p.map.set('default_ref_lines', 20);
  p.map.set('level_enabled', false);
  p.map.set('default_iso', 800);
  p.map.set('default_ae_mode', 'MANUAL');
  p.map.set('default_ev', -2);
  p.map.set('default_wb_preset', 'DAYLIGHT');
  p.map.set('default_wb_kelvin', 4000);
  p.map.set('default_wb_tint', 12);
  p.map.set('default_zoom', 2.5);
  p.map.set('default_flash', 'AUTO');
  p.map.set('default_stabilize', 'EIS');
  p.map.set('default_af_mode', 'MANUAL');
  p.map.set('default_mf_diopter', 1.5);
  p.map.set('default_size', '3840x2160');
  p.map.set('default_frame_effect', 'PEAKING');
  p.map.set('default_zebra_threshold', 0.95);
  p.map.set('default_zebra_density', 3);
  p.map.set('default_peaking_strength', 0.75);
  p.map.set('default_peaking_color', 'BLUE');
  p.map.set('default_lens', 'TELEPHOTO');
  p.map.set('default_arc_merge_deadband', 25);
}

// ================================================================== 1. 白名单契约

test('白名单 24 条且无重复', (): void => {
  // 多一条 = 排他字段泄入（自动降级会被写成用户默认）；少一条 = 有字段漏档
  assertEq(24, PARAMS_KEYS.length, '白名单条数');
  assertEq(24, new Set<string>(PARAMS_KEYS).size, '去重后条数');
});

test('白名单键名/顺序逐字等于上游拍板清单', (): void => {
  // 键名是持久化契约：手写清单与实现双向核对，顺序也钉（上游 KEYS 顺序）
  assertEq(24, EXPECTED_KEYS.length, '手写清单条数');
  assertEq(EXPECTED_KEYS.join(','), PARAMS_KEYS.join(','), '键名与顺序逐字一致');
});

test('排除清单一个都不许进白名单', (): void => {
  // renderMode/sampleRate 是设置页独占的自动降级写点；audioEnabled 涉及内录授权；
  // focusPoint 是瞬时态；curve/arc_convert_mode 各有专属直写链
  for (const k of EXCLUDED_KEYS) {
    assertFalse(PARAMS_KEYS.indexOf(k) >= 0, `${k} 属排除清单`);
  }
});

// ================================================================== 2. restore 正例

test('restore 24 键逐项写回总线', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  fillAllKeys(prefs);
  const params: WotaParams = new WotaParams();
  ParamsPersist.restore(prefs, params);
  assertEq(60, params.fps.value.value, 'fps');
  assertEq(8_333_333, params.shutter.value.value, '快门 ns');
  assertEq(35_000_000, params.bitrate.value, '码率');
  assertEq(20, params.refLines.value, '参考线掩码');
  assertEq(false, params.levelEnabled.value, '水平仪开关');
  assertEq(800, params.iso.value.value, 'ISO');
  assertEq(AeMode.MANUAL as number, params.aeMode.value as number, 'AE 模式');
  assertEq(-2, params.ev.value.value, 'EV');
  assertEq(WbPreset.DAYLIGHT as number, params.wbMode.value as number, 'WB 预设');
  assertEq(4000, params.kelvin.value.value, '色温');
  assertEq(12, params.tint.value.value, '色调');
  assertEq(2.5, params.zoom.value.value, '变焦');
  assertEq(Flash.AUTO as number, params.flash.value as number, '闪光');
  assertEq(Stabilize.EIS as number, params.stabilize.value as number, '防抖');
  assertEq(AfMode.MANUAL as number, params.afMode.value as number, 'AF 模式');
  assertEq(1.5, params.manualFocusDiopter.value.value, '屈光度');
  assertEq(3840, params.requestedSizeOf().width, '意图画幅宽');
  assertEq(2160, params.requestedSizeOf().height, '意图画幅高');
  assertEq(3840, params.size.value.width, '生效画幅宽（requestSize 同时落）');
  assertEq(FrameEffect.PEAKING as number, params.frameEffect.value as number, '监看效果');
  assertEq(0.95, params.zebraThreshold.value, '斑马阈值');
  assertEq(3, params.zebraDensity.value, '斑马密度');
  assertEq(0.75, params.peakingStrength.value, '峰值强度');
  assertEq(PeakingColor.BLUE as number, params.peakingColor.value as number, '峰值颜色');
  assertEq(LensType.TELEPHOTO as number, params.lens.value as number, '镜头档');
  assertEq(25, params.arcMergeDeadband.value, '补弧差分死区');
});

test('restore 坏值与缺失逐项回落或钳制，绝不抛', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  prefs.map.set('default_fps', 9999);
  prefs.map.set('default_shutter_ns', -1);
  prefs.map.set('default_iso', -5);
  prefs.map.set('default_wb_preset', '???');
  prefs.map.set('default_zoom', -2);
  prefs.map.set('default_flash', 'FLASHBANG');
  prefs.map.set('default_stabilize', '99');
  prefs.map.set('default_af_mode', 'NO_SUCH_MODE');
  prefs.map.set('default_mf_diopter', -0.5);
  prefs.map.set('default_size', 'abc');
  prefs.map.set('default_frame_effect', '');
  prefs.map.set('default_zebra_threshold', 5);
  prefs.map.set('default_zebra_density', 9);
  prefs.map.set('default_peaking_strength', -3);
  prefs.map.set('default_peaking_color', 'LAVA');
  prefs.map.set('default_lens', 'PERISCOPE');
  const params: WotaParams = new WotaParams();
  ParamsPersist.restore(prefs, params);
  assertEq(WotaTiers.FPS[WotaTiers.FPS.length - 1], params.fps.value.value, 'fps 上端钳回档表末档');
  assertEq(40_000_000, params.shutter.value.value, '快门坏值跳过 → 出厂默认 1/25s');
  assertEq(100, params.iso.value.value, 'ISO 坏值跳过 → 出厂默认');
  assertEq(WbPreset.AUTO as number, params.wbMode.value as number, 'WB 坏串回 AUTO');
  assertEq(1, params.zoom.value.value, '变焦坏值跳过 → 1x');
  assertEq(Flash.OFF as number, params.flash.value as number, '闪光坏串回 OFF');
  assertEq(Stabilize.OFF as number, params.stabilize.value as number, '防抖坏串回 OFF');
  assertEq(AfMode.CONTINUOUS_VIDEO as number, params.afMode.value as number, 'AF 坏串回连续视频');
  assertEq(0, params.manualFocusDiopter.value.value, '屈光度负数归 0');
  assertEq(1920, params.requestedSizeOf().width, '尺寸坏串跳过 → 出厂默认宽');
  assertEq(1080, params.requestedSizeOf().height, '尺寸坏串跳过 → 出厂默认高');
  assertEq(FrameEffect.NONE as number, params.frameEffect.value as number, '效果坏串回 NONE');
  assertEq(1, params.zebraThreshold.value, '斑马阈值钳进 0.6..1');
  assertEq(3, params.zebraDensity.value, '斑马密度钳 1..3');
  assertEq(0, params.peakingStrength.value, '峰值强度钳 0..1');
  assertEq(PeakingColor.RED as number, params.peakingColor.value as number, '峰值颜色坏串回 RED');
  assertEq(LensType.WIDE as number, params.lens.value as number, '镜头坏串回 WIDE');
});

test('restore fps 下端同样钳回首档', (): void => {
  // 只测上端会漏掉 coerceIn 的下界；1 → 24 证明两端都有执行力
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  prefs.map.set('default_fps', 1);
  const params: WotaParams = new WotaParams();
  ParamsPersist.restore(prefs, params);
  assertEq(24, params.fps.value.value, 'fps 下端钳回 24');
});

test('restore 快门/ISO/变焦 >0 才套（0 与负数都跳过）', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  prefs.map.set('default_shutter_ns', 0);
  prefs.map.set('default_iso', 0);
  prefs.map.set('default_zoom', 0);
  const params: WotaParams = new WotaParams();
  ParamsPersist.restore(prefs, params);
  assertEq(40_000_000, params.shutter.value.value, '快门 0 跳过');
  assertEq(100, params.iso.value.value, 'ISO 0 跳过');
  assertEq(1, params.zoom.value.value, '变焦 0 跳过');
  assertEq(0, params.manualFocusDiopter.value.value, '屈光度 0 保留（非负口径）');
});

test('restore AE LOCK 与坏串一律归 AUTO', (): void => {
  // 长按锁 AE 是瞬时态，不跨进程：手写进 prefs 的 LOCK 必须被归一
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  prefs.map.set('default_ae_mode', 'LOCK');
  const a: WotaParams = new WotaParams();
  ParamsPersist.restore(prefs, a);
  assertEq(AeMode.AUTO as number, a.aeMode.value as number, 'LOCK → AUTO');
  prefs.map.set('default_ae_mode', '??? ');
  const b: WotaParams = new WotaParams();
  ParamsPersist.restore(prefs, b);
  assertEq(AeMode.AUTO as number, b.aeMode.value as number, '坏串 → AUTO');
  prefs.map.set('default_ae_mode', 'manual');
  const c: WotaParams = new WotaParams();
  ParamsPersist.restore(prefs, c);
  assertEq(AeMode.AUTO as number, c.aeMode.value as number, '大小写敏感：小写不认');
});

test('restore 只改值不碰状态位（range/enabled/exact）', (): void => {
  // range/enabled/exact 是能力换挡的产物，恢复链不许抹掉（最终钳制交给 applyAbility）
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  prefs.map.set('default_iso', 6400);
  prefs.map.set('default_fps', 30);
  const params: WotaParams = new WotaParams();
  params.iso.value = new ParamState(100, { lo: 50, hi: 12800 }, true, false);
  params.fps.value = new ParamState(25, { lo: 24, hi: 30 }, true, true);
  ParamsPersist.restore(prefs, params);
  assertEq(6400, params.iso.value.value, 'ISO 值被恢复');
  assertEq(50, params.iso.value.range === undefined ? -1 : params.iso.value.range.lo, 'ISO range.lo 保留');
  assertEq(12800, params.iso.value.range === undefined ? -1 : params.iso.value.range.hi, 'ISO range.hi 保留');
  assertEq(false, params.iso.value.exact, 'ISO exact 是换挡产物，不许改');
  assertEq(30, params.fps.value.value, 'fps 值被恢复');
  assertEq(24, params.fps.value.range === undefined ? -1 : params.fps.value.range.lo, 'fps range.lo 保留');
  assertEq(true, params.fps.value.enabled, 'fps enabled 保留');
});

// ================================================================== 3. 尺寸/枚举编解码

test('尺寸串解析成功与坏串边界', (): void => {
  const ok: Size | undefined = parseSize('1280x720');
  assertEq(1280, ok === undefined ? -1 : ok.width, '合法串宽');
  assertEq(720, ok === undefined ? -1 : ok.height, '合法串高');
  assertEq('1280x720', ok === undefined ? '' : formatSize(ok), '编码与解析一一对应');
  assertNull(parseSize(''), '空串');
  assertNull(parseSize('abc'), '非数字');
  assertNull(parseSize('1920X1080'), '大写 X 不认（与上游 split("x") 同口径）');
  assertNull(parseSize('0x1080'), '宽 0 不认');
  assertNull(parseSize('1920x-1'), '负号不认');
  assertNull(parseSize('1920x1080x3'), '三段不认');
  assertNull(parseSize('1920x'), '缺高度');
  assertNull(parseSize('19.2x10.8'), '小数不认（对齐 toIntOrNull）');
});

test('枚举编解码 名称往返、大小写敏感、坏值回默认', (): void => {
  // 串名与 Kotlin enum.name 逐字一致；坏串/大小写不符一律回默认档
  assertEq('MANUAL', enumName(PrefEnum.AE_MODE, AeMode.MANUAL as number), 'AE 名');
  assertEq(1, enumFrom(PrefEnum.AE_MODE, 'MANUAL', AeMode.AUTO as number), 'AE 解析 MANUAL=1');
  assertEq(0, enumFrom(PrefEnum.AE_MODE, 'AUTO', AeMode.MANUAL as number), 'AE 解析 AUTO=0');
  assertEq(5, enumFrom(PrefEnum.WB_PRESET, 'DAYLIGHT', WbPreset.AUTO as number), 'DAYLIGHT=5');
  assertEq(1, enumFrom(PrefEnum.WB_PRESET, 'daylight', WbPreset.AUTO as number), '小写不认 → 默认');
  assertEq(8, enumFrom(PrefEnum.WB_PRESET, 'SHADE', WbPreset.AUTO as number), 'SHADE=8');
  assertEq(0, enumFrom(PrefEnum.AF_MODE, 'MANUAL', AfMode.CONTINUOUS_VIDEO as number), 'AfMode.MANUAL=0');
  assertEq(3, enumFrom(PrefEnum.AF_MODE, 'CONTINUOUS_VIDEO', 0), 'AfMode.CONTINUOUS_VIDEO=3');
  assertEq(2, enumFrom(PrefEnum.FLASH, 'AUTO', Flash.OFF as number), 'Flash.AUTO=2');
  assertEq(2, enumFrom(PrefEnum.STABILIZE, 'EIS', Stabilize.OFF as number), 'Stabilize.EIS=2');
  assertEq(2, enumFrom(PrefEnum.FRAME_EFFECT, 'PEAKING', FrameEffect.NONE as number), 'PEAKING=2');
  assertEq(4, enumFrom(PrefEnum.PEAKING_COLOR, 'RED', PeakingColor.WHITE as number), 'RED=4');
  assertEq(2, enumFrom(PrefEnum.LENS, 'TELEPHOTO', LensType.WIDE as number), 'TELEPHOTO=2');
  assertEq('WIDE', enumName(PrefEnum.LENS, LensType.WIDE as number), 'Lens 名');
  assertEq('SUPER_TELEPHOTO', enumName(PrefEnum.LENS, LensType.SUPER_TELEPHOTO as number), 'Lens 长名');
});

// ================================================================== 4. 会话影子语义

/** 进录制页后逐项调出 24 个非默认值（必须在会话影子冻结之后调用） */
function makeBusy(params: WotaParams): void {
  params.fps.value = ParamState.of(60);
  params.shutter.value = ParamState.of(8_333_333);
  params.bitrate.value = 35_000_000;
  params.refLines.value = 20;
  params.levelEnabled.value = false;
  params.iso.value = ParamState.of(800);
  params.aeMode.value = AeMode.MANUAL;
  params.ev.value = ParamState.of(-2);
  params.wbMode.value = WbPreset.DAYLIGHT;
  params.kelvin.value = ParamState.of(4000);
  params.tint.value = ParamState.of(12);
  params.zoom.value = ParamState.of(2.5);
  params.flash.value = Flash.AUTO;
  params.stabilize.value = Stabilize.EIS;
  params.afMode.value = AfMode.MANUAL;
  params.manualFocusDiopter.value = ParamState.of(1.5);
  params.requestSize(new Size(3840, 2160));
  params.frameEffect.value = FrameEffect.PEAKING;
  params.zebraThreshold.value = 0.95;
  params.zebraDensity.value = 3;
  params.peakingStrength.value = 0.75;
  params.peakingColor.value = PeakingColor.BLUE;
  params.lens.value = LensType.TELEPHOTO;
  params.setArcMergeDeadband(25);
}

test('会话 全动过后只写白名单 24 键且顺序一致', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  const params: WotaParams = new WotaParams();
  const session: ParamsPersistSession = new ParamsPersistSession(prefs, params); // 影子先冻结
  makeBusy(params); // 再逐项调出（用户动作）
  assertTrue(session.flush(), '全动过的会话 flush 必须落盘');
  assertEq(EXPECTED_KEYS.join(','), prefs.puts.join(','), '写键集合/顺序 = 白名单');
  assertEq('3840x2160', prefs.map.get('default_size'), '尺寸写意图值串');
  assertEq('MANUAL', prefs.map.get('default_ae_mode'), 'AE 枚举写名');
  assertEq(25, prefs.map.get('default_arc_merge_deadband'), '死区写整数');
});

test('会话 只写变化字段，没动过的字段不写盘也不覆盖设置页', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  const params: WotaParams = new WotaParams();
  const session: ParamsPersistSession = new ParamsPersistSession(prefs, params);
  // 用户在设置页把帧率改到 24（录制页总线还停在 25——陈旧值不许覆盖它）
  prefs.map.set('default_fps', 24);
  params.bitrate.value = 20_000_000;
  assertTrue(session.flush(), '有差异时 flush 成功');
  assertEq('default_bitrate', prefs.puts.join(','), '只该写码率一个键');
  assertEq(24, prefs.map.get('default_fps'), '录制页没动过帧率，24 不被覆盖');
  assertEq(20_000_000, prefs.map.get('default_bitrate'), '码率已落盘');
});

test('会话 无差异 flush 不写任何键', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  const session: ParamsPersistSession = new ParamsPersistSession(prefs, new WotaParams());
  assertTrue(session.flush(), '首次 flush 无差异返回 true');
  assertTrue(session.flush(), '再次 flush 仍无差异');
  assertEq(0, prefs.puts.length, '无差异不许产生 put');
  assertEq(0, prefs.map.size, '无差异不许写任何键');
});

test('会话 影子在 flush 后前进，重复 flush 幂等', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  const params: WotaParams = new WotaParams();
  const session: ParamsPersistSession = new ParamsPersistSession(prefs, params);
  params.iso.value = ParamState.of(1600);
  assertTrue(session.flush(), '首次写 ISO');
  assertEq(1, prefs.puts.length, '只写一次');
  assertTrue(session.flush(), '影子已前进，第二次无差异');
  assertEq(1, prefs.puts.length, '影子没前进就会每次 flush 都写盘');
  assertEq(1600, prefs.map.get('default_iso'), '落盘值');
});

test('会话 写失败不推进影子，下次 flush 重试', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  const params: WotaParams = new WotaParams();
  const session: ParamsPersistSession = new ParamsPersistSession(prefs, params);
  params.iso.value = ParamState.of(1600);
  prefs.failPuts = true;
  assertFalse(session.flush(), '写失败返回 false');
  assertEq(0, prefs.puts.length, '失败不记 put');
  prefs.failPuts = false;
  assertTrue(session.flush(), '恢复后重试成功');
  assertEq(1600, prefs.map.get('default_iso'), '重试把值写进去');
});

test('会话 LOCK 一律存为 AUTO（不跨进程自动锁曝光）', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  const params: WotaParams = new WotaParams();
  const session: ParamsPersistSession = new ParamsPersistSession(prefs, params);
  params.aeMode.value = AeMode.MANUAL;
  assertTrue(session.flush(), 'MANUAL 落盘');
  assertEq('MANUAL', prefs.map.get('default_ae_mode'), 'MANUAL 必须能跨进程');
  params.aeMode.value = AeMode.LOCK; // 长按锁 AE 的终态
  assertTrue(session.flush(), 'LOCK 落盘');
  assertEq('AUTO', prefs.map.get('default_ae_mode'), 'LOCK 是瞬时锁，入档归一为 AUTO');
});

test('会话 resync 后恢复值不算用户动作（启动顺序保护）', (): void => {
  // aboutToAppear 先建会话、bootstrap 再 restore：恢复后 resync，避免第一次 flush 把
  // 恢复出来的 24 个值当成改动又写一遍
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  fillAllKeys(prefs);
  const params: WotaParams = new WotaParams();
  const session: ParamsPersistSession = new ParamsPersistSession(prefs, params);
  ParamsPersist.restore(prefs, params);
  session.resync();
  assertTrue(session.flush(), '恢复后无差异');
  assertEq(0, prefs.puts.length, '恢复值不产生写盘');
});

test('会话 画幅写的是意图值，不是当前生效 size', (): void => {
  const prefs: FakeParamsPrefs = new FakeParamsPrefs();
  const params: WotaParams = new WotaParams();
  const session: ParamsPersistSession = new ParamsPersistSession(prefs, params);
  // 引擎/能力把生效尺寸改小：意图没动，不许入档
  params.size.value = new Size(640, 480);
  assertTrue(session.flush(), '无差异');
  assertEq(undefined, prefs.map.get('default_size'), '生效 size 不是真源，不许写');
  // UI 改档走 requestSize：意图+生效一起动，这次必须落盘
  params.requestSize(new Size(3840, 2160));
  assertTrue(session.flush(), '意图变化必须写');
  assertEq('3840x2160', prefs.map.get('default_size'), '写意图值');
});

// ================================================================== 5. 键名迁移

test('迁移读 只有旧键 fps=24 时读出 24', (): void => {
  // 键名迁移：新键（上游名）优先，缺失回退旧键；旧键不删无害，下次 save 写新键
  assertEq(24, ParamsMigrate.int(undefined, 24, 30), '新键缺失 → 旧键 fps=24');
  assertEq(60, ParamsMigrate.int(60, 24, 30), '新键在册优先于旧键');
  assertEq(30, ParamsMigrate.int(undefined, undefined, 30), '都没有 → 默认 30');
  const legacySize: Size = ParamsMigrate.size(undefined, 3840, 2160, 1920, 1080);
  assertEq(3840, legacySize.width, '旧 sizeW/sizeH 迁移读宽');
  assertEq(2160, legacySize.height, '旧 sizeW/sizeH 迁移读高');
  const newSize: Size = ParamsMigrate.size('1280x720', 3840, 2160, 1920, 1080);
  assertEq(1280, newSize.width, '新键 default_size 优先');
  const badNew: Size = ParamsMigrate.size('abc', 3840, 2160, 1920, 1080);
  assertEq(3840, badNew.width, '新键坏串回退旧键');
  const noneSize: Size = ParamsMigrate.size(undefined, undefined, undefined, 1920, 1080);
  assertEq(1920, noneSize.width, '都没有 → 出厂默认');
});

test('迁移后的旧键名不在白名单（一个字段只有权威键名）', (): void => {
  // 旧键 fps/bitrate/sizeW/sizeH 只用于迁移读，不许混进 24 键白名单（否则出现两套真源）
  assertEq('default_fps', PARAMS_KEYS[0], 'fps 权威键名');
  assertEq('default_bitrate', PARAMS_KEYS[2], '码率权威键名');
  assertEq('default_size', PARAMS_KEYS[16], '尺寸权威键名');
  for (const legacy of ['fps', 'bitrate', 'sizeW', 'sizeH']) {
    assertFalse(PARAMS_KEYS.indexOf(legacy) >= 0, `${legacy} 是旧键，不许进白名单`);
  }
});
