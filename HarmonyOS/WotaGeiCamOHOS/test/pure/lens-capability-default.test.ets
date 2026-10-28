/**
 * 能力协商的出厂默认镜头（对齐上游 LensSlotTest 的 pickCapabilityDefault 用例组，0525fa2）。
 *
 * 缺陷本体：默认开哪颗镜头按焦距归档的「档位标签」认——个别机型的 HAL 会把小底定焦头
 * 排进「广角」档（上游真机 marble 实测：出厂「广角」最大 1600x1200 且定焦），首启即被
 * 钉在 720p。能力协商 = 开后摄里**最大视频档最大**的那颗，不认档位标签；前置一律排除；
 * 全空/全零返回 undefined 走既有链路；并列取先见者（Kotlin maxByOrNull 语义）。
 */
import { CameraAbility, CameraAbilityArgs, RangeI } from '../../entry/src/main/ets/core/CameraAbility';
import { LensSlot, LensType, pickCapabilityDefault, maxVideoPixels } from '../../entry/src/main/ets/core/LensSlot';
import { Rect, Size } from '../../entry/src/main/ets/core/Constants';

function abilityOf(videoSizes: Array<Size>): CameraAbility {
  const args: CameraAbilityArgs = {
    hardwareLevel: 1,
    sensorOrientation: 90,
    activeArray: new Rect(0, 0, 4000, 3000),
    jpegSizes: [],
    highSpeedSizes: [],
    videoSizes: videoSizes,
    fpsRanges: [new RangeI(24, 24), new RangeI(30, 30)],
    highSpeedFps: new Map<Size, Array<RangeI>>(),
    iso: new RangeI(100, 3200),
    exposureNs: new RangeI(1000, 33_000_000),
    evRange: new RangeI(-4, 4),
    evStep: 1,
    afModes: new Set<number>([1]),
    awbModes: new Set<number>([1]),
    flashAvailable: true,
    maxRegionsAf: 1,
    maxRegionsAe: 1,
    minFocusDistanceDiopter: 1.0,
    zoomRatioRange: undefined,
    maxDigitalZoom: 8,
    oisAvailable: false,
    eisAvailable: true,
    highSpeedCapable: false,
    availableSurfaceFormats: []
  };
  return new CameraAbility(args);
}

/** marble 形态：归档进「广角」档的小底定焦头（最大 1600x1200） */
function marbleWide(): LensSlot {
  return new LensSlot(LensType.WIDE, 'logic-wide', undefined, 5.6,
    abilityOf([new Size(1600, 1200), new Size(1280, 720)]));
}

/** 真主摄：4K */
function main4k(): LensSlot {
  return new LensSlot(LensType.TELEPHOTO, 'logic-main', undefined, 6.8,
    abilityOf([new Size(3840, 2160), new Size(1920, 1080)]));
}

function front(): LensSlot {
  return new LensSlot(LensType.FRONT, 'logic-front', undefined, 3.5,
    abilityOf([new Size(1920, 1080)]));
}

test('marble形态_归档进广角的小底头不当默认_开最大视频档者', () => {
  // 旧口径（pickDefaultLens 认标签）会开 WIDE=1600x1200 定焦；能力协商开 4K 主摄
  const d = pickCapabilityDefault([marbleWide(), main4k(), front()]);
  assertTrue(d !== undefined);
  assertEq('logic-main', (d as LensSlot).logicId, '开后摄里最大视频档最大的那颗（哪怕档位标签是 TELEPHOTO）');
});

test('正常机型_主摄本来就是最大视频档_行为不变', () => {
  const wide = new LensSlot(LensType.WIDE, 'logic-wide', undefined, 5.6,
    abilityOf([new Size(3840, 2160)]));
  const tele = new LensSlot(LensType.TELEPHOTO, 'logic-tele', undefined, 15.0,
    abilityOf([new Size(1920, 1080)]));
  const d = pickCapabilityDefault([wide, tele, front()]);
  assertTrue(d !== undefined);
  assertEq('logic-wide', (d as LensSlot).logicId);
});

test('前置一律排除', () => {
  // 前摄视频档再大也不参选（自拍预览不该成为出厂默认）
  const bigFront = new LensSlot(LensType.FRONT, 'logic-front', undefined, 3.5,
    abilityOf([new Size(7680, 4320)]));
  const small = new LensSlot(LensType.WIDE, 'logic-wide', undefined, 5.6,
    abilityOf([new Size(640, 480)]));
  const d = pickCapabilityDefault([bigFront, small]);
  assertTrue(d !== undefined);
  assertEq('logic-wide', (d as LensSlot).logicId);
  assertEq(undefined, pickCapabilityDefault([bigFront]), '只有前摄 ⇒ undefined 走既有链路');
});

test('并列取先见者_对齐maxByOrNull语义', () => {
  const a = new LensSlot(LensType.WIDE, 'logic-a', undefined, 5.6, abilityOf([new Size(1920, 1080)]));
  const b = new LensSlot(LensType.TELEPHOTO, 'logic-b', undefined, 15.0, abilityOf([new Size(1920, 1080)]));
  assertEq('logic-a', (pickCapabilityDefault([a, b]) as LensSlot).logicId);
  assertEq('logic-b', (pickCapabilityDefault([b, a]) as LensSlot).logicId);
});

test('全空或全零视频档返回undefined', () => {
  assertEq(undefined, pickCapabilityDefault([]));
  const noVideo = new LensSlot(LensType.WIDE, 'logic-wide', undefined, 5.6, abilityOf([]));
  assertEq(undefined, pickCapabilityDefault([noVideo]), '报不出视频档的镜头不能当「最大」');
  const mixed = [noVideo, main4k()];
  assertEq('logic-main', (pickCapabilityDefault(mixed) as LensSlot).logicId,
    '全零镜头在场不挡真主摄');
});

test('maxVideoPixels_无档为0_取最大', () => {
  assertEq(0, maxVideoPixels(abilityOf([])));
  assertEq(3840 * 2160, maxVideoPixels(abilityOf([new Size(1920, 1080), new Size(3840, 2160)])));
});
