#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""contrast-audit.py —— 任务 #82 / #84 的验收工具：毛玻璃透光多少才还读得清。

为什么要有它（不许靠目测）：
  #84 要在 HUD 卡片背后做真模糊，而 `ui/design/Tokens.kt` 的
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
  5. **文字自身带 alpha 时（HDS 深色三档 0xDB/0x99/0x66）先 src-over 到底上再比**：
     直接拿白 RGB 比会虚报一倍多。同式适用于强调色对（design-spec §7 的深色文字体系）。

门槛：正文 4.5:1，大字与图形控件 3.0:1（WCAG AA）；HDS 深色模式对正文另荐 ≥5:1
（design-spec §7.1），本工具按项目闸门 4.5 报 PASS/FAIL，5:1 的量级在数字里自己看。
"""

import os
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


def cr_on(fg_rgba, bg_rgb):
    """文字（可带 alpha）压在 bg 上的真实对比度：先合成观感色再比。"""
    a = fg_rgba[0]
    rgb = fg_rgba[1:]
    seen = over(rgb, a, bg_rgb) if a < 255 else rgb
    return contrast(seen, bg_rgb)


BACKDROPS = [
    ("black    ", (0, 0, 0)),
    ("dark 32  ", (32, 32, 32)),
    ("mid 128  ", (128, 128, 128)),
    ("light200 ", (200, 200, 200)),
    ("white255 ", (255, 255, 255)),
    ("sky(245,248,255)", (245, 248, 255)),
]

# 文字要压的实底组：页面四层 + 霜底板实色（hudScrim 的 RGB）
GROUNDS = [("bg", "页面底"), ("surface", "卡"), ("layer", "浮层"), ("hudScrim", "HUD 实底板")]

# 文字三档的门槛：textHi/textMid 承载正文（4.5），textLo 只准非正文/辅助文本（HDS 深色表 ≥3:1）
TEXT_BAR = {"textHi": 4.5, "textMid": 4.5, "textLo": 3.0}

# 强调色对：(前景, 底, 门槛, 用途)。前景/底缺令牌时跳过并提示（旧版 Tokens 没有新令牌）。
# 2026-10-02 审查收口：accent×surface 的 4.5 小字档没有数字能过（实测 4.48），而大字用法
# （SettingGroup 19sp Bold 标题）合法保留，所以这组钉在 3.0——小字残留（BtSpeakerPanel 设备名/
# 动作钮、SmallTextButton、SettingsScreen 去系统设置/权限 granted）已全部清出，再出现即违例。
ACCENT_PAIRS = [
    ("onAccent", "accent", 3.0, "白字 on brand 蓝 #007DFF：只准图形/大字（承载正文小字会跌破 4.5）"),
    ("onAccent", "accentSurface", 4.5, "chip 选中态小字走旧蓝这枚「承载小字的面」"),
    ("onAccent", "accentActive", 3.0, "Switch 激活轨上的白 thumb（P3-4 深色控件激活档，图形 3:1）"),
    ("accent", "bg", 3.0, "强调色作图标 tint / 描边（非文字 3:1 档）"),
    ("accent", "hudScrim", 4.5, "强调色作小字文字色（如 HudLayer valueColor 13sp）"),
    ("accent", "surface", 3.0, "强调色作大字文字色（SettingGroup 19sp Bold 标题）；小字残留 2026-10-02 已清零，再出现即违例"),
    ("accentDim", "bg", 3.0, "深色强调变体：吸附预览描边（非文字 3:1 档）"),
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
    for k in sorted(t):
        a, r, g, b = t[k]
        print("  %-14s = #%02X%02X%02X%02X   (alpha %3d/255 => 不透明 %.1f%% / 透光 %.1f%%)"
              % (k, a, r, g, b, a, a / 255 * 100, (255 - a) / 255 * 100))

    scrim_rgb = t["hudScrim"][1:]
    print()
    print("== 文字三档压四个实底（文字自身的 alpha 先 src-over 进去再比）==")
    print("   门槛：textHi/textMid 正文 4.5；textLo 只用于非正文/辅助文本 3.0（design-spec §7.1）")
    for name in ("textHi", "textMid", "textLo"):
        bar = TEXT_BAR[name]
        cells = []
        for key, _ in GROUNDS:
            cr = cr_on(t[name], t[key][1:])
            cells.append("%s %5.2f %s" % (key.ljust(8), cr, "PASS" if cr >= bar else "FAIL"))
        print("  %-7s(≥%.1f)  %s" % (name, bar, " | ".join(cells)))

    print()
    print("== 强调色对（选中态/激活态/派生色）==")
    skipped = []
    for fg, bg, bar, note in ACCENT_PAIRS:
        if fg not in t or bg not in t:
            skipped.append("%s×%s" % (fg, bg))
            continue
        cr = cr_on(t[fg], t[bg][1:])
        print("  %-11s on %-11s CR = %5.2f   %s (≥%.1f)   —— %s"
              % (fg, bg, cr, "PASS" if cr >= bar else "FAIL", bar, note))
    if skipped:
        print("  （源里没有这些令牌，跳过：%s）" % ", ".join(skipped))

    # ------------------------------------------------------------------
    # 弹层/半透底组（Top 8 审查收口 2026-10-02）：BtSpeakerPanel 一族挂在 WotaPillPopup
    # （底 = surface.copy(alpha=0.97)，alpha 从 PillPopup.kt 现抓、不抄常数）；CameraDialogs
    # 参数面板与 CurveSheet 的动作行压 AcrylicScrim（scrim 令牌 0xB3）。半透底叠在预览上，
    # worst case = 最亮背景，所以逐背景扫取最小 CR——把 BtSpeakerPanel/SmallTextButton
    # 那类「面板文字压弹层底」的组合纳入审计。
    print()
    print("== 弹层/半透底：文字压半透板，扫最亮背景取 worst（文字 alpha 先 src-over 再比）==")
    popup_alpha = None
    popup_src_path = os.path.join(os.path.dirname(src), "PillPopup.kt")
    try:
        m = re.search(r"WotaColor\.surface\.copy\(alpha = ([0-9.]+)f\)",
                      open(popup_src_path, encoding="utf-8").read())
        if m:
            popup_alpha = float(m.group(1))
    except OSError:
        pass
    if popup_alpha is None:
        print("  !! 没从 %s 抓到 surface.copy(alpha = …f)——弹层底组跳过，别改成硬编码" % popup_src_path)

    popup_grounds = []
    if "scrim" in t:
        sa = t["scrim"][0]
        popup_grounds.append(("AcrylicScrim", t["scrim"][1:], sa, [
            ("textHi", 4.5, "面板正文/动作小字（CameraDialogs 副标题、SmallTextButton）——scrim 上唯一合法的文字档"),
        ]))
    if popup_alpha is not None and "surface" in t:
        popup_grounds.append(("pillPopup", t["surface"][1:], round(popup_alpha * 255), [
            ("textHi", 4.5, "面板正文（textHi 档）"),
            ("textMid", 4.5, "面板正文（P2-2 清扫后的正文档，BtSpeakerPanel 提示行）"),
            ("textLo", 3.0, "辅助小字（电量/计数，非正文）"),
            ("accent", 3.0, "图标 tint（播放键/扫描态）"),
        ]))
    for gname, grgb, galpha, rows in popup_grounds:
        print("  %s（alpha 0x%02X，透光 %.1f%%）" % (gname, galpha, (255 - galpha) / 255 * 100))
        for name, bar, note in rows:
            ta, trgb = t[name][0], t[name][1:]
            crs = []
            for _, bd in BACKDROPS:
                plate = over(grgb, galpha, bd)
                seen = over(trgb, ta, plate) if ta < 255 else trgb
                crs.append(contrast(seen, plate))
            v = min(crs)
            print("    %-8s worst %5.2f  %s (≥%.1f)  —— %s"
                  % (name, v, "PASS" if v >= bar else "FAIL", bar, note))
    # 禁用组合：实测数字钉在这里，任何新用法都是债，别因为「看起来还行」放进来
    if "scrim" in t:
        print("  禁用组合（无合法档，仅记录实测；出现即违例）：")
        for name, bar in (("textMid", 4.5), ("textLo", 3.0), ("accent", 3.0)):
            ta, trgb = t[name][0], t[name][1:]
            crs = []
            for _, bd in BACKDROPS:
                plate = over(t["scrim"][1:], t["scrim"][0], bd)
                seen = over(trgb, ta, plate) if ta < 255 else trgb
                crs.append(contrast(seen, plate))
            print("    AcrylicScrim × %-7s worst %5.2f（<%.1f）—— %s"
                  % (name, min(crs), bar,
                     "弹层正文只准 textHi" if name == "textMid"
                     else "textLo 禁上弹层底（CurveSheet 计数行为存量债，已上报）"
                     if name == "textLo" else "accent 图形压亮场读不出，选中态走实底令牌"))

    print()
    print("== 透光扫档：卡片叠在预览上，报每组背景里**最差**的 CR（文字 alpha 已计）==")
    print("   worst case 是最亮那一格（天空/白墙），不是黑底")
    names = ("textHi", "textMid", "textLo", "accent")
    hdr = "  %-8s %-7s " % ("alpha", "透光%") + " ".join("%-9s" % n for n in names)
    print(hdr)
    print("  " + "-" * (len(hdr) - 2))
    for a in (0xBF, 0xB3, 0xA6, 0x99, 0x8C, 0x80, 0x73, 0x66, 0x59, 0x4D):
        cells = []
        for name in names:
            ta, trgb = t[name][0], t[name][1:]
            crs = []
            for _, bd in BACKDROPS:
                plate = over(scrim_rgb, a, bd)
                seen = over(trgb, ta, plate) if ta < 255 else trgb
                crs.append(contrast(seen, plate))
            v = min(crs)
            bar = TEXT_BAR.get(name, 3.0)
            cells.append("%5.2f%s" % (v, "*" if v >= 4.5 else ("+" if v >= bar else "!")))
        print("  0x%02X    %4.1f%%   %s" % (a, (255 - a) / 255 * 100, "  ".join(cells)))
    print()
    print("  图例：* >=4.5 (AA 正文)   + >=本档门槛 (textLo 3.0 / accent 3.0)   ! <门槛 (不合格)")
    print()
    print("  注：本表按 alpha 直混算，未计 Compose 的 linear-blend 差异与色带压缩；")
    print("  它给的是**档位选择的量级**，最终观感仍须真机截图 + 这张表一起看。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
