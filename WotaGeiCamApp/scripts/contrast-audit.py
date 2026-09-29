#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""contrast-audit.py —— 任务 #82 / #84 的验收工具：毛玻璃透光多少才还读得清。

为什么要有它（不许靠目测）：
  #84 要在 HUD 卡片背后做真模糊，而 `ui/design/Tokens.kt:53` 的
  `hudScrim = Color(0xBF14120F)` 不透明度约 74.9%，**只有 25.1% 透光**——
  模糊做完几乎看不见。可一旦把 alpha 降下去，卡片就会跟着身后的实时画面一起变亮，
  浅色场景（天空、白墙）下白字对比度会塌。这两件事是一对矛盾，必须用数来定档位。

算法口径：
  1. 色值**从源文件正则抓**，不抄常数——抄了就会与实现漂移（本项目栽过"注释里的
     宽度账与真源不符"同一族）。
  2. 卡片叠在预览上：合成色 = alpha * scrim + (1 - alpha) * backdrop（sRGB 8bit 直混，
     与 Compose `background(Color)` 的 src-over 同式；预览不额外套 gamma 处理，
     因为这里要的是相对关系与门槛，不是绝对色差）。
  3. 对比度用 WCAG 2.x 的相对亮度式：L = 0.2126R + 0.7152G + 0.0722B（线性化后），
     CR = (L1 + 0.05) / (L2 + 0.05)。
  4. **worst case 只取最亮的背景**：对浅色文字来说 backdrop 越亮越危险，所以扫一组
     从黑到纯白的亮度轴，报"这一档 alpha 下最差的 CR"。这就是"天空/白墙"那一角。

门槛：正文 4.5:1，大字与图形控件 3.0:1（WCAG AA）。
"""

import re
import sys

SRC_DEFAULT = "WotaGeiCamApp/app/src/main/java/com/wotagei/cam/ui/design/Tokens.kt"


def parse_tokens(path):
    """从 Kotlin 源里抓 Color(0xRRGGBB) / Color(0xAARRGGBB) 字面量。"""
    txt = open(path, encoding="utf-8").read()
    out = {}
    for name, hexs in re.findall(r"val\s+(\w+)\s*=\s*Color\((0x[0-9A-Fa-f]{6,8})\)", txt):
        s = hexs[2:]
        if len(s) == 6:
            a, r, g, b = 255, int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16)
        else:
            a, r, g, b = int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), int(s[6:8], 16)
        out[name] = (a, r, g, b)
    return out


def lin(c):
    c /= 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def rel_lum(rgb):
    r, g, b = rgb
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast(fg, bg):
    l1, l2 = rel_lum(fg), rel_lum(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def over(fg, a, bg):
    """src-over：fg 以 alpha=a(0..255) 叠在 bg 上，返回合成 RGB。"""
    al = a / 255.0
    return tuple(round(al * f + (1 - al) * b) for f, b in zip(fg, bg))


BACKDROPS = [
    ("black    ", (0, 0, 0)),
    ("dark 32  ", (32, 32, 32)),
    ("mid 128  ", (128, 128, 128)),
    ("light200 ", (200, 200, 200)),
    ("white255 ", (255, 255, 255)),
    ("sky(245,248,255)", (245, 248, 255)),
]


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else SRC_DEFAULT
    try:
        t = parse_tokens(src)
    except FileNotFoundError:
        print("!! 找不到源文件：%s（请在项目根运行）" % src)
        return 1
    need = ("hudScrim", "textHi", "textMid", "textLo", "accent")
    missing = [k for k in need if k not in t]
    if missing:
        print("!! 源里没抓到这些令牌：%s —— 说明真源改名了，本工具要跟着改，别改成硬编码" % missing)
        return 1

    print("tokens read from %s" % src)
    for k in need:
        a, r, g, b = t[k]
        print("  %-9s = #%02X%02X%02X%02X   (alpha %3d/255 => 不透明 %.1f%% / 透光 %.1f%%)"
              % (k, a, r, g, b, a, a / 255 * 100, (255 - a) / 255 * 100))

    scrim_rgb = t["hudScrim"][1:]
    print()
    print("== 现状这一档（alpha 0x%02X）：文字与卡片合成色直接比，不看身后画面 =="
          % t["hudScrim"][0])
    for name in ("textHi", "textMid", "textLo", "accent"):
        rgb = t[name][1:]
        print("  %-8s CR = %5.2f   %s (4.5)   %s (3.0)"
              % (name, contrast(rgb, scrim_rgb),
                 "PASS" if contrast(rgb, scrim_rgb) >= 4.5 else "FAIL",
                 "PASS" if contrast(rgb, scrim_rgb) >= 3.0 else "FAIL"))

    print()
    print("== 透光扫档：卡片叠在预览上，报每组背景里**最差**的 CR ==")
    print("   worst case 是最亮那一格（天空/白墙），不是黑底")
    hdr = "  %-8s %-7s %-9s %-9s %-9s %-9s" % ("alpha", "透光%", "textHi", "textMid", "textLo", "accent")
    print(hdr)
    print("  " + "-" * (len(hdr) - 2))
    for a in (0xBF, 0xB3, 0xA6, 0x99, 0x8C, 0x80, 0x73, 0x66, 0x59, 0x4D):
        worst = {}
        for name in ("textHi", "textMid", "textLo", "accent"):
            rgb = t[name][1:]
            crs = []
            for _, bd in BACKDROPS:
                comp = over(scrim_rgb, a, bd)
                crs.append(contrast(rgb, comp))
            worst[name] = min(crs)
        cells = []
        for name in ("textHi", "textMid", "textLo", "accent"):
            v = worst[name]
            cells.append("%5.2f%s" % (v, "*" if v >= 4.5 else ("+" if v >= 3.0 else "!")))
        print("  0x%02X    %4.1f%%   %s  %s  %s  %s"
              % (a, (255 - a) / 255 * 100, cells[0], cells[1], cells[2], cells[3]))
    print()
    print("  图例：* >=4.5 (AA 正文)   + 3.0..4.5 (只够大字/图形)   ! <3.0 (不合格)")
    print()
    print("  注：本表按 alpha 直混算，未计 Compose 的 linear-blend 差异与色带压缩；")
    print("  它给的是**档位选择的量级**，最终观感仍须真机截图 + 这张表一起看。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
