/*
 * 共享角度归一（gl_render 与 codec_recorder 共用；审查 P3：NormalizeQuarter 只能有一处定义）：
 * - WotaNormalizeDeg：折到 [0,360)；
 * - WotaNormalizeQuarter：按上游 OrientationExport.normalizeQuarter 四舍五入到 0/90/180/270
 *   （45→90、359→0），muxer 的 OH_AVMuxer_SetRotation 只接受这四个角。
 */
#ifndef WOTA_MATH_H
#define WOTA_MATH_H

inline int WotaNormalizeDeg(int deg)
{
    return ((deg % 360) + 360) % 360;
}

inline int WotaNormalizeQuarter(int deg)
{
    return ((WotaNormalizeDeg(deg) + 45) / 90 % 4) * 90;
}

#endif // WOTA_MATH_H
