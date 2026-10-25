/**
 * 取景预览的**画面适配几何**（WLB 批，2026-10-25：补上此前登记为「平台偏离」的 letterbox 面）。
 *
 * 问题（静态代码可证）：native 上屏是**全屏四边形**（`gl_render.cpp` 的 kQuadPositions = −1..1），
 * UV 变换只做旋转/镜像，**整条管线没有任何按宽高比收缩的算式**；而预览档取的是录制尺寸
 * （`Camera.ets` 传 `settings.sizeW/H`，典型 1920×1080）。于是当画面宽高比 ≠ 屏幕宽高比时，
 * 画面被拉伸铺满（竖屏手机上 16:9 内容拉进 ≈9:19.4 的屏）。上游 Compose 侧用 `aspectRatio`
 * 盒做 letterbox，并靠 `PreviewCoords` 处理预览盒与窗口的坐标差——本侧此前没有对应物。
 *
 * 本文件的职责：把「画面矩形」算成纯数据（零平台 import，可 full test），三处消费：
 * ① native 上屏的等比收缩因子（`uFit` uniform）；
 * ② 参考线/网格覆盖层的 `aspect`（`RefLine.inscribedBox` 同一套内接语义）；
 * ③ 点按对焦的坐标映射（屏幕归一化 → 画面归一化）。
 *
 * 一致性红线：三处必须吃同一份 `PreviewFit.rectOf`，不许各算一遍——参考线画在 A 矩形、
 * 触点映射按 B 矩形，是「点哪都对不上」类缺陷的温床。
 */
export class PreviewBox {
  /** 画面矩形（屏幕坐标系，单位与入参一致：vp 或 px 均可） */
  x: number;
  y: number;
  w: number;
  h: number;
  /** 画面宽高比（宽/高，已含旋转后的取向）；<=0 = 不可用（调用方退回铺满） */
  aspect: number;

  constructor(x: number, y: number, w: number, h: number, aspect: number) {
    this.x = x;
    this.y = y;
    this.w = w;
    this.h = h;
    this.aspect = aspect;
  }
}

/** 归一化坐标（画面内 0..1） */
export class ContentPoint {
  x: number;
  y: number;

  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

export class PreviewFit {
  /**
   * 旋转后的画面取向：90/270（含其等价负角）宽高互换。
   * 与 native UV 旋转同源——转的是**取样内容**，所以显示取向随之互换。
   */
  static swapsAxes(rotationDeg: number): boolean {
    let d: number = rotationDeg % 360;
    if (d < 0) {
      d += 360;
    }
    return d === 90 || d === 270;
  }

  /** 画面显示宽高比（宽/高）；宽或高不可用返回 0 */
  static aspectOf(contentW: number, contentH: number, rotationDeg: number): number {
    if (!(contentW > 0) || !(contentH > 0)) {
      return 0;
    }
    return PreviewFit.swapsAxes(rotationDeg) ? contentH / contentW : contentW / contentH;
  }

  /**
   * 画面矩形：在 viewport 内按 aspect **内接**（等比收缩 + 居中），语义与
   * `RefLine.inscribedBox` 一致（后者按画布算内接框，这里按 viewport 算同一个框）。
   * aspect 不可用或 viewport 不可用 → 铺满（= 旧行为，调用方零改动即可兼容）。
   */
  static rectOf(viewportW: number, viewportH: number, contentW: number, contentH: number,
    rotationDeg: number): PreviewBox {
    if (!(viewportW > 0) || !(viewportH > 0)) {
      return new PreviewBox(0, 0, 0, 0, 0);
    }
    const aspect: number = PreviewFit.aspectOf(contentW, contentH, rotationDeg);
    if (aspect <= 0) {
      return new PreviewBox(0, 0, viewportW, viewportH, 0);
    }
    const viewportAspect: number = viewportW / viewportH;
    let boxW: number;
    let boxH: number;
    if (viewportAspect > aspect) {
      // 屏幕比画面更宽：画面顶高，两侧留黑
      boxH = viewportH;
      boxW = boxH * aspect;
    } else {
      // 屏幕比画面更窄：画面顶宽，上下留黑
      boxW = viewportW;
      boxH = boxW / aspect;
    }
    return new PreviewBox((viewportW - boxW) / 2, (viewportH - boxH) / 2, boxW, boxH, aspect);
  }

  /**
   * native 上屏的等比收缩因子（NDC 半域缩放，1 = 铺满）。
   * 四边形在 NDC 里是 −1..1，乘 (sx, sy) 等价于把画面缩进对应的屏幕区域。
   */
  static ndcScaleX(box: PreviewBox, viewportW: number): number {
    if (!(viewportW > 0) || !(box.w > 0)) {
      return 1;
    }
    return box.w / viewportW;
  }

  static ndcScaleY(box: PreviewBox, viewportH: number): number {
    if (!(viewportH > 0) || !(box.h > 0)) {
      return 1;
    }
    return box.h / viewportH;
  }

  /**
   * 点按坐标映射：屏幕归一化 (nx, ny) → **画面内**归一化 (0..1)。
   * 落在黑边上的点夹到最近的画面边缘（与既有「窗口归一化 + 钳制」同款语义：
   * 用户按黑边仍得到一个合理的对焦点，而不是无响应）。
   * 矩形不可用 → 原样返回（= 旧行为）。
   */
  static mapToContent(nx: number, ny: number, box: PreviewBox,
    viewportW: number, viewportH: number): ContentPoint {
    if (!(box.w > 0) || !(box.h > 0) || !(viewportW > 0) || !(viewportH > 0)) {
      return new ContentPoint(nx, ny);
    }
    const px: number = nx * viewportW;
    const py: number = ny * viewportH;
    const cx: number = (px - box.x) / box.w;
    const cy: number = (py - box.y) / box.h;
    return new ContentPoint(Math.min(1, Math.max(0, cx)), Math.min(1, Math.max(0, cy)));
  }
}