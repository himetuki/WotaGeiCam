/**
 * 媒体批（MS-1/2/3）的纯逻辑面：MimeMap 映射表 + ThumbLru 驱逐序。
 * 平台件（WotaShare/ThumbLoader/AlbumSaver）在 test/pure 里只有惰性桩，接线由源码守卫钉住
 * （见 media-share-wiring.test.ets）。
 */
import { extOf, mimeOf, titleOf, utdOf } from '../../entry/src/main/ets/media/MimeMap';
import { ThumbLru } from '../../entry/src/main/ets/media/ThumbLru';

// ------------------------------------------------------------------ MimeMap（上游手写表逐条）

test('mime表_上游手写表逐条对拍', () => {
  assertEq('video/mp4', mimeOf('VID_20260101_120000.mp4'), 'mp4（本仓产物）');
  assertEq('video/mp4', mimeOf('a.MP4'), '大写扩展名归一');
  assertEq('video/mp4', mimeOf('a.m4v'), 'm4v 同 mp4');
  assertEq('video/quicktime', mimeOf('a.mov'));
  assertEq('video/3gpp', mimeOf('a.3gp'));
  assertEq('video/x-matroska', mimeOf('a.mkv'));
  assertEq('video/webm', mimeOf('a.webm'));
});

test('mime表_未知扩展与无扩展兜底通配', () => {
  assertEq('video/*', mimeOf('a.avi'), '未知视频扩展兜底（上游同款）');
  assertEq('video/*', mimeOf('a.xyz'));
  assertEq('video/*', mimeOf('noext'), '无扩展名');
  assertEq('video/*', mimeOf('a.'), '尾点 = 空扩展');
  assertEq('', extOf('noext'), 'extOf 无点返回空');
  assertEq('', extOf('a.'), 'extOf 尾点返回空');
  assertEq('mp4', extOf('A.MP4'), 'extOf 小写归一');
});

test('utd映射_mp4给精确mpeg4_其余给宽video', () => {
  // systemShare 的类型语言：mp4 一族精确、其余（含未知）放宽，宁可多几个候选应用
  assertEq('general.mpeg-4', utdOf('a.mp4'), 'SDK 核实：UTD 枚举只有 general.mpeg-4，无 general.mp4');
  assertEq('general.mpeg-4', utdOf('a.m4v'));
  assertEq('general.video', utdOf('a.mov'));
  assertEq('general.video', utdOf('a.mkv'));
  assertEq('general.video', utdOf('noext'), '未知放宽');
});

test('title去扩展名_存相册不会拼出双后缀', () => {
  assertEq('VID_20260101_120000', titleOf('VID_20260101_120000.mp4'));
  assertEq('a', titleOf('a.mp4'));
  assertEq('noext', titleOf('noext'), '无扩展名原样');
  assertEq('.hidden', titleOf('.hidden'), '首字符是点不算扩展名（dot>0 判据）');
});

// ------------------------------------------------------------------ ThumbLru（上游 LruCache 语义）

test('lru_命中提升_未命中不动顺序', () => {
  const lru: ThumbLru<string> = new ThumbLru<string>(3);
  lru.put('a', '1');
  lru.put('b', '2');
  lru.put('c', '3');
  assertEq('1', lru.get('a'), '命中 a');
  // a 被提升到队尾 → 最旧变成 b；再 put d 逐的是 b 不是 a
  const evicted: Array<string> = lru.put('d', '4');
  assertEq('b', evicted.join(','), '驱逐最久未用的 b（a 刚被 get 提升）');
  assertEq(undefined, lru.get('b'), 'b 已被逐出');
  assertEq('1', lru.get('a'), 'a 仍在');
});

test('lru_容量满连逐到装得下_重写同键不占新位', () => {
  const lru: ThumbLru<number> = new ThumbLru<number>(2);
  lru.put('a', 1);
  lru.put('b', 2);
  const evicted: Array<string> = lru.put('a', 9);
  assertEq('', evicted.join(','), '重写已有键不驱逐（先删后插，不占新位）');
  assertEq(9, lru.get('a'), '值被更新');
  assertEq(2, lru.size);
  const evicted2: Array<string> = lru.put('c', 3);
  assertEq('b', evicted2.join(','), '满容量逐最旧 b');
});

test('lru_容量1与clear', () => {
  const lru: ThumbLru<string> = new ThumbLru<string>(1);
  lru.put('a', '1');
  const evicted: Array<string> = lru.put('b', '2');
  assertEq('a', evicted.join(','));
  assertEq(1, lru.size);
  lru.clear();
  assertEq(0, lru.size);
  assertEq(undefined, lru.get('b'), 'clear 后全 miss');
});

test('lru_非正容量按1兜底', () => {
  const lru: ThumbLru<string> = new ThumbLru<string>(0);
  lru.put('a', '1');
  lru.put('b', '2');
  assertEq(1, lru.size, '容量 0 折成 1（构造器夹紧，不抛不越界）');
});
