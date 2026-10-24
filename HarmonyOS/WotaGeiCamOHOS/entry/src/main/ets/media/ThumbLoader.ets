/**
 * 视频缩略图加载器（上游 `MediaRepo.ThumbLoader` 的 OHOS 对应物）：
 * `AVImageGenerator.fetchFrameByTime` 取**中点帧**（上游 `getFrameAtTime(OPTION_CLOSEST)` 同义），
 * 模块级 LRU 缓存（`media/ThumbLru.ets`）+ 并发限 2（上游 Semaphore(2)）。
 *
 * 与上游的差异（登记）：
 * - 上游主路径 `ContentResolver.loadThumbnail`（系统缩略图服务）；OHOS 沙箱文件没有该服务，
 *   `PhotoAsset.getThumbnail` 走媒体库资产且 since 22 超 6.0(20) 兼容面——一律走解码器路径；
 * - 缓存键 = `path|sizePx`（path 即媒体 ID 的来源，本仓无系统 uri）；
 * - 驱逐不 release（见 ThumbLru 头注释）；生成失败/超时返回 undefined，UI 退回占位块。
 */
import { image } from '@kit.ImageKit';
import { media } from '@kit.MediaKit';
import { fileIo } from '@kit.CoreFileKit';
import { Diag } from '../diag/Diag';
import { ThumbLru } from './ThumbLru';

const TAG: string = 'WotaThumb';

/** LRU 容量（驱逐不回收，靠 ArkUI 丢引用后 GC，取小控常驻） */
const CACHE_CAP: number = 32;
/** 同屏并发解码上限（上游 Semaphore(2)：缩略图不该抢录制/播放的解码器） */
const MAX_CONCURRENT: number = 2;
/** 单条超时：6s 内没出帧就放弃（占位块兜底），不让卡片一直转 */
const LOAD_TIMEOUT_MS: number = 6000;

export class ThumbLoader {
  private static readonly cache: ThumbLru<image.PixelMap> = new ThumbLru<image.PixelMap>(CACHE_CAP);
  /** 进行中的加载（同 key 复用同一个 Promise，列表滚动不重复解码） */
  private static readonly inflight: Map<string, Promise<image.PixelMap | undefined>> =
    new Map<string, Promise<image.PixelMap | undefined>>();
  private static active: number = 0;
  private static readonly queue: Array<() => void> = [];

  /** 媒体库整体刷新/页面销毁时整表作废（路径已失效，命中也是旧图） */
  static invalidateAll(): void {
    ThumbLoader.cache.clear();
  }

  /**
   * 取一条缩略图：命中缓存立即返回；否则限并发解码（中点帧，CLOSEST 最近帧）。
   * 任何失败（打不开/解码失败/超时）返回 undefined——UI 用占位块，不重试不报错横幅。
   */
  static load(path: string, durationMs: number, sizePx: number): Promise<image.PixelMap | undefined> {
    const key: string = `${path}|${sizePx}`;
    const hit: image.PixelMap | undefined = ThumbLoader.cache.get(key);
    if (hit !== undefined) {
      return Promise.resolve(hit);
    }
    const running: Promise<image.PixelMap | undefined> | undefined = ThumbLoader.inflight.get(key);
    if (running !== undefined) {
      return running;
    }
    const task: Promise<image.PixelMap | undefined> = ThumbLoader.acquire().then(
      (): Promise<image.PixelMap | undefined> => ThumbLoader.decode(key, path, durationMs, sizePx)
    ).finally((): void => {
      ThumbLoader.inflight.delete(key);
      ThumbLoader.release();
    });
    ThumbLoader.inflight.set(key, task);
    return task;
  }

  /** 并发闸：满了排队（FIFO），拿到名额才开解码 */
  private static acquire(): Promise<void> {
    if (ThumbLoader.active < MAX_CONCURRENT) {
      ThumbLoader.active++;
      return Promise.resolve();
    }
    return new Promise<void>((resolve: () => void): void => {
      ThumbLoader.queue.push((): void => {
        ThumbLoader.active++;
        resolve();
      });
    });
  }

  private static release(): void {
    const next: (() => void) | undefined = ThumbLoader.queue.shift();
    if (next !== undefined) {
      next();
      return;
    }
    ThumbLoader.active = Math.max(0, ThumbLoader.active - 1);
  }

  private static async decode(key: string, path: string, durationMs: number,
    sizePx: number): Promise<image.PixelMap | undefined> {
    let fd: number = -1;
    let gen: media.AVImageGenerator | undefined = undefined;
    try {
      const stat: fileIo.Stat = fileIo.statSync(path);
      if (stat.size <= 0) {
        return undefined;
      }
      fd = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY).fd;
      gen = await media.createAVImageGenerator();
      gen.fdSrc = { fd: fd, offset: 0, length: stat.size } as media.AVFileDescriptor;
      // 中点帧（时长未知取 0 = 首帧）；CLOSEST = 离给定时刻最近的帧（上游 OPTION_CLOSEST 同义）
      const midUs: number = durationMs > 0 ? Math.floor(durationMs / 2) * 1000 : 0;
      const timeout: Promise<undefined> = new Promise<undefined>(
        (resolve: (v: undefined) => void): void => {
          setTimeout((): void => resolve(undefined), LOAD_TIMEOUT_MS);
        });
      const pixel: Promise<image.PixelMap> = gen.fetchFrameByTime(midUs,
        media.AVImageQueryOptions.AV_IMAGE_QUERY_CLOSEST,
        { width: sizePx, height: sizePx } as media.PixelMapParams);
      const pm: image.PixelMap | undefined = await Promise.race([pixel, timeout]);
      if (pm === undefined) {
        Diag.w(TAG, `thumb timeout: ${path}`);
        return undefined;
      }
      ThumbLoader.cache.put(key, pm);
      return pm;
    } catch (e) {
      Diag.w(TAG, `thumb failed: ${path}`, e as Error);
      return undefined;
    } finally {
      if (gen !== undefined) {
        try {
          await gen.release();
        } catch (e) {
          // 释放失败只影响解码器实例本身，不影响已返回的帧
        }
      }
      if (fd >= 0) {
        try {
          fileIo.closeSync(fd);
        } catch (e) {
          // 只读句柄关闭失败不影响判据
        }
      }
    }
  }
}
