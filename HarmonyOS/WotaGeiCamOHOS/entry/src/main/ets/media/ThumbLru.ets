/**
 * 缩略图缓存（上游 `MediaRepo.ThumbLoader` 的 LruCache 策略直译，纯逻辑）。
 *
 * 上游用 androidx `LruCache<String, Bitmap>`：访问即提升（get 也算「最近用」）、
 * 容量满逐最旧。本类同语义：`get` 命中即移到队尾，`put` 超容量逐队头。
 *
 * 回收策略的偏离（登记）：上游 LruCache 驱逐即回收 Bitmap；ArkTS 侧 PixelMap 可能正被
 * `Image` 组件显示，驱逐时显式 `release()` 会让在显图变空甚至崩溃，所以**驱逐不 release**，
 * 交给 ArkUI 丢弃引用后的 GC。容量因此取小（32）控制常驻内存。
 *
 * 零平台 import：驱逐序可在 test/pure 里钉住。
 */
export class ThumbLru<V> {
  private readonly cap: number;
  /** 插入序 = 最近使用序（队头最旧）；Map 保插入序，重插即提升 */
  private readonly map: Map<string, V> = new Map<string, V>();

  constructor(cap: number) {
    this.cap = cap > 0 ? cap : 1;
  }

  get size(): number {
    return this.map.size;
  }

  /** 命中返回值并把键提到队尾；未命中返回 undefined 且不动顺序 */
  get(key: string): V | undefined {
    const hit: V | undefined = this.map.get(key);
    if (hit !== undefined) {
      this.map.delete(key);
      this.map.set(key, hit);
    }
    return hit;
  }

  /**
   * 写入（新键进队尾）；超容量逐队头直到装得下。
   *
   * @return 被驱逐的键列表（调用方记账用；本类不做回收动作，见文件头登记）
   */
  put(key: string, value: V): Array<string> {
    const evicted: Array<string> = [];
    if (this.map.has(key)) {
      this.map.delete(key);
    }
    while (this.map.size >= this.cap) {
      // Map 迭代第一个键 = 最旧（插入序未被 get 提升过的那个）
      const oldest: string = this.map.keys().next().value as string;
      this.map.delete(oldest);
      evicted.push(oldest);
    }
    this.map.set(key, value);
    return evicted;
  }

  /** 清空（页面销毁/媒体库刷新时整表作废；同样不做逐项回收，交 GC） */
  clear(): void {
    this.map.clear();
  }
}
