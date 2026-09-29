#!/usr/bin/env bash
# ============================================================================
# WotaGeiCam 预览帧率取证工装（任务 #85）
#
# 为什么需要它：红线「动效全开时录制页预览帧率 ≥ 设定值的 95%」（docs/plan/08:79、
# 13:44、12:46）**至今只存在于文档，从来没被测过**——docs/plan/13:84 与
# deliverable/v0.0.2/RELEASE-NOTES.md:201 都明记"未证"，APK-LOG 的门禁数字栏里
# 一个帧率数都没有。#84 要做 GPU 自绘毛玻璃，成本必须由"模糊关 vs 模糊开"两组的
# **差值**说话，所以先把量法固化下来。
#
# 用法（项目根的 Git Bash 里；一次只测一组，三组各跑一遍）：
#   bash WotaGeiCamApp/scripts/fps-harness.sh --label plain
#   bash WotaGeiCamApp/scripts/fps-harness.sh --label blur-off
#   bash WotaGeiCamApp/scripts/fps-harness.sh --label blur-on
#   可选：--seconds 10  --expect-fps 30  --drive   （--drive 才做动效窗口，默认不做）
#
# 写死在脚本里的规矩（不许绕过）：
#   1. **绝不解锁**。只检查是否已解锁；锁着就停下等人，不模拟密码、不替你滑。
#   2. **绝不 uninstall**（会清空 wota_settings 与 wota_media.db：用户设置、收藏、tag）。
#   3. **绝不用 am force-stop**（强杀吃掉异步落盘，本项目因此误判过一次缺陷）。
#   4. **绝不写 app 的 prefs**：动效档与模糊开关由人在设置页里手动切，脚本只**读回**当时
#      真实生效的那一档并记进清单。自己写死"这轮是 PLAIN"就退化成了自证。
#   5. **从不录影**：录制会在用户相册里留片子（上一轮那 4 条自测片还没处理），所以本工装没有
#      录制的档位；动效窗口由"开合就近浮层 + 编辑页拖一格"制造，不制造素材。
#   6. 临时产物只进项目内 .tmp/（本机规矩：C 盘不落临时文件）。
#   7. adb 是 Windows 版：**喂它 MSYS 绝对路径会直接失败**（实测两次），凡是给 adb 的
#      本地路径一律 cygpath -w 转成 Windows 形式。
#   8. 本设备**没有 screenrecord 二进制**（EMUI 屏蔽），别试图录屏；
#      screencap 单张 464–541ms，抓不到 750ms 的中间态，所以这里只做稳态取样。
# ============================================================================

set -u
export MSYS_NO_PATHCONV=1
export PYTHONIOENCODING=utf-8

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="$ROOT/tools/android-sdk/platform-tools/adb.exe"
PKG="com.wotagei.cam"
ACTIVITY="$PKG/.MainActivity"      # 组件名在 --preflight 里从 dumpsys 复核，不硬信
SEC=10
LABEL=""
DRIVE=0
EXPECT_FPS=0

while [ $# -gt 0 ]; do
  case "$1" in
    --label)   LABEL="${2:-}"; shift 2;;
    --seconds) SEC="${2:-10}"; shift 2;;
    --drive)   DRIVE=1; shift;;
    --expect-fps) EXPECT_FPS="${2:-0}"; shift 2;;
    -h|--help) sed -n '1,40p' "$0"; exit 0;;
    *) echo "!! 未知参数：$1"; exit 1;;
  esac
done

[ -n "$LABEL" ] || { echo "!! 必须给 --label（plain / blur-off / blur-on 之一），否则三组数据分不清归属"; exit 1; }
[ -x "$ADB" ] || { echo "!! adb not found at $ADB"; exit 1; }
"$ADB" devices | grep -qw device || { echo "!! no authorized device"; exit 1; }

TS=$(date +%Y%m%d-%H%M)
OUT="$ROOT/.tmp/fps-$TS-$LABEL"
OUTW=$(cygpath -w "$OUT" 2>/dev/null) || OUTW="$OUT"
mkdir -p "$OUT" || { echo "!! cannot create $OUT"; exit 1; }

say() { echo "-- $*"; }
hr()  { echo "------------------------------------------------------------"; }

hr
echo "fps-harness  label=$LABEL  seconds=$SEC  drive=$DRIVE"
echo "evidence dir: $OUT"

# --- 0. 预检：装的是哪一枚、能不能跑 gfxinfo -------------------------------------
hr
FLAGS=$("$ADB" shell dumpsys package "$PKG" | grep -oE "flags=\[[^]]*\]" | head -1)
VN=$("$ADB" shell dumpsys package "$PKG" | grep -oE "versionName=[^ ]*" | head -1)
RES=$("$ADB" shell wm size | grep -oE "Physical size: [0-9x]+" | head -1)
echo "installed: $VN  $FLAGS"
echo "$RES"
# 组件名复核：猜的 .MainActivity 万一不对，后面拉回前台会静默失败，所以这里硬验一次
COMP=$("$ADB" shell cmd package resolve-activity --brief "$PKG" 2>/dev/null | tail -1 | tr -d '\r')
case "$COMP" in
  *"$PKG"*) ACTIVITY="$COMP";;
  *) echo "!! resolve-activity 没拿到本包组件（拿到的是 '$COMP'）；请人工确认启动组件后再跑";;
esac
say "launch component: $ACTIVITY"

# --- 1. 解锁检查（只检查，不代理解锁）-------------------------------------------
hr
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
LOCKED=$("$ADB" shell dumpsys trust 2>/dev/null | grep -oE "deviceLocked=[01]" | head -1)
echo "lock state: ${LOCKED:-unknown}"
if [ "$LOCKED" != "deviceLocked=0" ]; then
  echo
  echo "  >>> 请现在亲手解锁这台手机（本脚本绝不代理解锁）。解开后回这里按回车继续；不想跑了输 q。"
  read -r ans
  [ "$ans" = "q" ] && { echo "quit"; exit 1; }
  LOCKED=$("$ADB" shell dumpsys trust 2>/dev/null | grep -oE "deviceLocked=[01]" | head -1)
  [ "$LOCKED" = "deviceLocked=0" ] || { echo "!! 仍是锁屏态（$LOCKED），不继续"; exit 1; }
fi

# --- 2. 读回当时真实生效的设置（脚本只读不写）------------------------------------
hr
PREFS=""
if [ -n "$("$ADB" shell run-as "$PKG" ls shared_prefs/ 2>/dev/null | tr -d '\r')" ]; then
  PREFS=$("$ADB" shell run-as "$PKG" cat shared_prefs/wota_settings.xml 2>/dev/null | tr -d '\r')
fi
pick() { echo "$PREFS" | grep -oE "name=\"$1\"[^/]*" | head -1 | sed -E 's/.*value="([^"]*)".*/\1/'; }
MOTION=$(pick motion_mode)
BLUR=$(pick hud_blur)
ORIENT=$(pick ui_orientation)
CAMFPS=$(pick default_fps)
echo "生效设置（从 prefs 读回，不是我猜的）: motion_mode=${MOTION:-<未写>} hud_blur=${BLUR:-<未写未实现>} ui_orientation=${ORIENT:-<未写>} default_fps=${CAMFPS:-<未写>}"
[ -n "$PREFS" ] || say "⚠ run-as 读不到 prefs（装的若不是 DEBUGGABLE 就会这样）——设置归属这一列将标 UNKNOWN，别拿它当证据"
echo "motion_mode=$MOTION  hud_blur=$BLUR  ui_orientation=$ORIENT  default_fps=$CAMFPS" > "$OUT/settings-at-sample.txt"

# --- 3. 起应用到取景页，清计数，采样 ---------------------------------------------
hr
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2
FOCUS=$("$ADB" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')
echo "focus: $FOCUS"
case "$FOCUS" in *"$PKG"*) :;; *) echo "!! 没进到我们应用（$FOCUS），中止——否则量的是系统界面的帧率"; exit 1;; esac

# 相机权限/首帧没到之前 gfxinfo 计数是空的，先等画面真出来
READY=""
for _ in 1 2 3 4 5 6 7 8; do
  PNG="/sdcard/wota_fps_ready.png"
  "$ADB" shell screencap -p "$PNG" >/dev/null 2>&1
  "$ADB" pull "$PNG" "$OUTW/ready_probe.png" >/dev/null 2>&1 && "$ADB" shell rm -f "$PNG" >/dev/null 2>&1
  SZ=$(stat -c%s "$OUT/ready_probe.png" 2>/dev/null || echo 0)
  # 空帧 PNG 约 7,904 B，真帧实测 37 万～130 万 B（本机量过）
  if [ "${SZ:-0}" -gt 40000 ]; then READY=1; break; fi
  say "等相机首帧（第 $_ 次，probe=${SZ}B）"; sleep 1
done
[ "$READY" = 1 ] || say "⚠ 首帧没验到（probe 一直是空帧尺寸），下面的数可能是静止界面的数"

"$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
say "gfxinfo 已清零，开始 ${SEC}s 采样"
T0=$(date +%s)

if [ "$DRIVE" = 1 ]; then
  # 动效窗口：开一次就近浮层再关（走 WotaChip 的弹出/收起，即 #81 那批 AnimatedVisibility 的路径）。
  # 坐标从 dump 里现取，不写死——写死方向的坐标在横竖屏之间必错一次（AGENTS.md 那条教训）。
  DXML="$OUT/driver_dump.xml"
  "$ADB" shell uiautomator dump /sdcard/wota_driver.xml >/dev/null 2>&1
  "$ADB" pull /sdcard/wota_driver.xml "$OUTW/$(basename "$DXML")" >/dev/null 2>&1 && "$ADB" shell rm -f /sdcard/wota_driver.xml >/dev/null 2>&1
  TAP=$(python - "$DXML" <<'PY'
import re,sys
try: t=open(sys.argv[1],encoding='utf-8').read()
except Exception: sys.exit(0)
# 取景页常驻胶囊里挑一颗：文案真源是 cam_p_*（监看/防抖/对焦/参考线/闪光灯/曲线）
for name in ('监看','防抖','对焦','参考线','曲线'):
    m=re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'%name,t)
    if m:
        x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
)
  if [ -n "${TAP:-}" ]; then
    set -- $TAP; say "动效窗口：点 ($1,$2) 开浮层，停 0.6s 再点收起"
    "$ADB" shell input tap "$1" "$2"; sleep 1
    "$ADB" shell input tap "$1" "$2"; sleep 1
  else
    say "⚠ 没在 dump 里找到常驻胶囊（hud_pills 可能把它们都关了），这一组**没有动效窗口**，label 要如实反映"
  fi
fi

sleep "$SEC"
T1=$(date +%s)
say "采样结束（实际 $((T1-T0))s）"

# --- 4. 取 gfxinfo 并算数 ---------------------------------------------------------
hr
"$ADB" shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo_raw.txt" 2>&1
# Windows 版 dump 有时带 \r，统一一下再算
tr -d '\r' < "$OUT/gfxinfo_raw.txt" > "$OUT/gfxinfo.txt"

python - "$OUT/gfxinfo.txt" "$LABEL" "$MOTION" "$BLUR" "$EXPECT_FPS" "$((T1-T0))" <<'PY'
import re,sys
path,label,motion,blur,expect,wall = sys.argv[1:7]
t=open(path,encoding='utf-8',errors='replace').read()
def g(pat, default=None):
    m=re.search(pat,t)
    return m.group(1) if m else default
total = g(r'Total rendered frames:\s*(\d+)')
jank  = g(r'Janky frames:\s*(\d+)\s*\(([\d.]+)%\)')
p50   = g(r'\s*50%:\s*(\d+)ms')
p90   = g(r'\s*90%:\s*(\d+)ms')
p95   = g(r'\s*95%:\s*(\d+)ms')
hist  = re.findall(r'([\d.]+)ms\s*\+(\d+)ms\s*:\s*(\d+)', t)
huf   = g(r'HUI total:\s*(\d+)')
if total is None:
    print("!! gfxinfo 里没解析到 Total rendered frames ——这一组数不可用（常见原因：界面静止、或包名不对、或 reset 后没帧）")
    sys.exit(0)
total=int(total)
jc=int(jank[0]) if jank else None
jp=float(jank[1]) if jank else None
smooth=(total-jc)/total*100 if (jc is not None and total) else None
exp=int(expect or 0); wall=int(wall)
print(f"label={label}  motion_mode={motion or 'UNKNOWN'}  hud_blur={blur or 'UNKNOWN(未实现)'}")
print(f"  Total rendered = {total}   （{wall}s 窗口 ⇒ 平均 {total/max(wall,1):.1f} 帧/s 上屏计数）")
if jc is not None:
    print(f"  Janky = {jc} ({jp:.1f}%)  ⇒  流畅率 {smooth:.1f}%")
    print(f"  红线口径（docs/plan/13:44 ≥95%）: {'PASS' if smooth>=95 else 'FAIL'}")
print(f"  分位 50/90/95 = {p50}/{p90}/{p95} ms   （60fps 预算 16.6ms，红线是别超 95 分位）")
if huf: print(f"  HUI total = {huf}")
if exp:
    print(f"  设定帧率 {exp} ⇒ 需要 GL 侧每帧打点才能算「实际预览帧率 ÷ 设定值」；gfxinfo 数的是**上屏绘制次数**，"
          f"不等于相机预览帧率（MAX_DISPLAY_FPS=60 的节流、WHEN_DIRTY 语义都会让它低于设定值）。#85b 接 onFrameDrawn 之前，这一条标 PARTIAL。")
PY

# --- 5. 收尾：熄屏并回报 ----------------------------------------------------------
hr
"$ADB" shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
echo "device now: $("$ADB" shell dumpsys power | grep -m1 -oE 'mWakefulness=[A-Za-z]+')"
echo
echo "证据目录: $OUT"
ls -1 "$OUT" | sed 's/^/   /'
echo
echo "三组都要跑，然后**只比差值**：blur-on 相对 blur-off 的流畅率降幅与 95 分位涨幅才是 #84 的模糊成本。"
echo "单组绝对数不能用来宣布「够用」——那是把没测的对照组当已知。"
