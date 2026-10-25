/**
 * 取景读数顺序编辑器的**拖拽落位算式**（HDG 批，2026-10-25；纯逻辑，可 full test）。
 *
 * 背景：编辑器（`pages/HudEditor.ets`）此前只有 ↑/↓ 逐格挪，本批把「自由拖拽」的**有落点那半**
 * 落地——把某行按住上下拖，释放时落到目标序号。上游 `HudLayoutEditor` 的完整自由布局
 * （五容器之间换区 + 格网落格）在本侧**没有对应渲染面**：本侧取景 HUD 是 T31 真机取证后的
 * 固定结构（顶栏两行 + Dock 2×4 + 更多面板），只有 READOUT 区是有序列表——跨容器拖拽是
 * 重做取景 HUD 的产品决策，且必须真机验收，故不在本批。本文件只做「同容器内按位移落位」。
 *
 * 与既有 `withZoneOrder` 的关系：本文件只算**目标下标**，顺序改写仍走
 * `HudLayoutTable.withZoneOrder`（同容器、无格子钉扎）——编写/编解码单一真源不变。
 */
export class HudDragOrder {
  /** 行高下限（vp）：行高量不出来时用兜底值，避免除零 */
  static readonly MIN_ROW_H: number = 1;

  /**
   * 按拖动位移算**目标下标**：位移折算成跨越的行数后加到原下标上，再夹进 [0, count-1]。
   *
   * 口径：位移为正 = 向下拖 = 下标增大（与列表自上而下的序号方向一致）。
   * 行高 <= 0（未量到/异常）时返回原下标（不动作，胜过按错误行高乱跳）。
   *
   * @param fromIndex 起始下标
   * @param offsetYVp 相对按下点的纵向位移（vp，向下为正）
   * @param rowHeightVp 单行高（含行距；实测）
   * @param count 当前条目数
   */
  static targetIndex(fromIndex: number, offsetYVp: number, rowHeightVp: number, count: number): number {
    if (count <= 0 || fromIndex < 0 || fromIndex >= count) {
      return fromIndex;
    }
    if (!(rowHeightVp > 0) || !Number.isFinite(offsetYVp)) {
      return fromIndex;
    }
    // 半行高的死区：拖过邻居一半才换位（与常见拖拽手感一致，避免抖动就来回换）
    const slots: number = Math.round(offsetYVp / rowHeightVp);
    const raw: number = fromIndex + slots;
    return raw < 0 ? 0 : (raw > count - 1 ? count - 1 : raw);
  }

  /**
   * 把 fromIndex 的元素移到 toIndex（其余元素整体让位，长度不变）。
   * 下标非法或相等时返回**原数组的副本**（调用方据「是否变化」决定要不要写盘）。
   */
  static moveTo<T>(items: Array<T>, fromIndex: number, toIndex: number): Array<T> {
    const next: Array<T> = items.slice();
    if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0 ||
      fromIndex >= next.length || toIndex >= next.length) {
      return next;
    }
    const moved: T = next[fromIndex];
    next.splice(fromIndex, 1);
    next.splice(toIndex, 0, moved);
    return next;
  }

  /** 是否真的换了位（决定要不要落盘：没动就不写，免无谓的保存提示与 IO） */
  static changed(fromIndex: number, toIndex: number): boolean {
    return fromIndex >= 0 && toIndex >= 0 && fromIndex !== toIndex;
  }
}