/**
 * DIRECT 直显承载视图的显示变换矩阵——上游 camera/DirectSink.kt 的纯函数
 * `directDisplayMatrix(...)` 逐行直译（零平台 import）。
 *
 * 语义：承载视图默认把 `bufferWidth x bufferHeight` 的相机帧拉伸铺满整个视图矩形，
 * 本矩阵把它改成「按 [rotationDegrees] 顺时针旋转 + 等比居中（信箱黑边）」的结果；
 * 前置镜像作用在**旋转之后**的屏幕横轴上（自拍预览的左右反）。
 * 与 GL 路径的「纹理坐标旋转 + 等比居中 contentRect」等价，所以两种渲染模式同角度。
 *
 * 推导（列向量 p' = M·p，视图像素坐标、Y 向下）：
 * `px = X·(bw/vw) − bw/2`、`py = Y·(bh/vh) − bh/2`（视图 → 以中心为原点的缓冲像素系）；
 * `rx = c·px − s·py`、`ry = s·px + c·py`（屏幕坐标 Y 向下，正角即视觉顺时针）；
 * 镜像在旋转之后乘，才是「屏幕左右反」而不是「传感器左右反」；最后等比缩放并平移到视图中心。
 *
 * 退化：任一尺寸 <= 0（视图还没布局 / 缓冲还没就绪）返回单位阵。
 * 非 90 倍数的 sensorOrientation（个别机型报 82/97 之类）按**旋转后包围盒**
 * `bw·|c| + bh·|s|` 见方算黑边，不做「只认四个直角」的分支。
 *
 * 精度登记：上游 Kotlin 用 Float 单精度，本侧 ArkTS 只有 number（双精度）。同一推导，
 * 差异在 1e-15 量级，用例容差 1e-3 / 1e-6 下不可见。
 *
 * 不移植（登记）：上游 `DirectSink` 类体的其余部分是 Android 平台胶水——
 * `TextureView`/`SurfaceTexture`/`Surface`/`Matrix`/`Handler` 线程切回与 `PreviewOrientation`
 * 状态机；本侧 ArkUI 只有 XComponent 的 surface（即 GL 路径），没有「相机帧直落承载视图 +
 * 读回 SurfaceTexture 矩阵」这条通道，故无等价物，不移植。
 */
export class DirectDisplay {
  /** 行主序 3x3：`[m0,m1,m2, m3,m4,m5, m6,m7,m8]`，作用于齐次视图坐标 */
  static identity(): Array<number> {
    return [1, 0, 0, 0, 1, 0, 0, 0, 1];
  }

  /**
   * @param viewWidth/viewHeight 承载视图像素宽高
   * @param bufferWidth/bufferHeight 相机帧（缓冲）像素宽高
   * @param rotationDegrees 施加的顺时针旋转角（任意整数，内部归一到 0..359）
   * @param mirrored 前置镜像（作用在旋转之后的屏幕横轴）
   * @return 行主序 3x3，长度 9；尺寸未就绪时为单位阵
   */
  static matrix(
    viewWidth: number,
    viewHeight: number,
    bufferWidth: number,
    bufferHeight: number,
    rotationDegrees: number,
    mirrored: boolean
  ): Array<number> {
    if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) {
      return DirectDisplay.identity();
    }
    const vw: number = viewWidth;
    const vh: number = viewHeight;
    const bw: number = bufferWidth;
    const bh: number = bufferHeight;
    const deg: number = ((rotationDegrees % 360) + 360) % 360;
    const rad: number = deg * Math.PI / 180;
    const c: number = Math.cos(rad);
    const s: number = Math.sin(rad);
    // 旋转后的包围盒：非 90 整倍数的 sensorOrientation 也要能算出黑边
    const contentW: number = bw * Math.abs(c) + bh * Math.abs(s);
    const contentH: number = bw * Math.abs(s) + bh * Math.abs(c);
    if (contentW <= 0 || contentH <= 0) {
      return DirectDisplay.identity();
    }
    const k: number = Math.min(vw / contentW, vh / contentH);
    const mx: number = mirrored ? -1 : 1;
    const a: number = bw / vw;
    const b: number = bh / vh;
    const cx: number = vw / 2;
    const cy: number = vh / 2;
    return [
      k * mx * c * a, -k * mx * s * b, k * mx * (s * bh - c * bw) / 2 + cx,
      k * s * a, k * c * b, -k * (c * bh + s * bw) / 2 + cy,
      0, 0, 1
    ];
  }
}
