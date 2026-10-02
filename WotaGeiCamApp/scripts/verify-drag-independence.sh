#!/usr/bin/env bash
# ============================================================================
# #87 真机验收：#79（编辑页操作栏三颗点不动）+ #80（拖一颗别颗不许动）
#
# 与 verify-on-device.sh 的分工：那份靠人手拖（[C]/[D] 要 tty），这一份**自己拖**。
# 非 tty 时人手那三步会被显式跳过，于是"没拖"和"拖完了"又混成一件事——正是历史上
# 五份 XML 字节数一模一样的成因。所以这里把拖拽也做成机器可执行的，且判据是**差值**
# 而不是"看起来没动"。
#
# 用法（项目根的 Git Bash）：
#   bash WotaGeiCamApp/scripts/verify-drag-independence.sh
#
# 规矩（与 verify-on-device.sh 同源，不许绕过）：
#   1. 绝不解锁。deviceLocked=1 就直接退出，不模拟密码、不替你滑。
#   2. 绝不 uninstall、绝不 am force-stop —— 持久化的证据是 run-as 读到的**盘上文件**。
#   3. 截图 screencap 到 /sdcard 再 pull，喂 adb 的路径一律 cygpath 转 Windows 形式。
#   4. 临时产物只进 .tmp/。
#   5. 收尾必须复原：编辑页走「恢复默认」把摆位还回去，stay_on 恢复原值。
# ============================================================================

set -u
export MSYS_NO_PATHCONV=1
export PYTHONIOENCODING=utf-8

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="$ROOT/tools/android-sdk/platform-tools/adb.exe"
PKG="com.wotagei.cam"
OUT="$ROOT/.tmp/drag-$(date +%Y%m%d-%H%M)"
OUTW=$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")

fail() { echo "!! ABORT: $*"; exit 1; }
note() { echo "-- $*"; }
hr()   { echo "------------------------------------------------------------"; }

[ -x "$ADB" ] || fail "adb not found at $ADB"
mkdir -p "$OUT" || fail "cannot create $OUT"

hr
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 1
LOCKED=$("$ADB" shell dumpsys trust 2>/dev/null | grep -oE "deviceLocked=[01]" | head -1)
AWAKE=$("$ADB" shell dumpsys power | grep -oE "mWakefulness=[A-Za-z]*" | head -1)
echo "lock=${LOCKED:-unknown} power=${AWAKE:-unknown}"
[ "$LOCKED" = "deviceLocked=0" ] || fail "屏幕仍锁着（${LOCKED:-unknown}）。请亲手解锁后重跑；本脚本绝不代解。"
[ "$AWAKE" = "mWakefulness=Awake" ] || fail "屏幕没醒（$AWAKE）——空帧会被当成证据。"

# 跑完约 90 秒，中途熄屏会让后半批取证变成假帧。改系统设置前先记原值，收尾必复原。
# ⚠ 这机器上它从没被设过，`settings get` 返回的是字面量 "null" 而不是空串——
# 拿 "null" 回填等于写进一个非法值，所以"原值是 null"这一支必须走 settings delete。
STAY_ON=$("$ADB" settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')
echo "stay_on_while_plugged_in 原值 = ${STAY_ON:-空}"
"$ADB" settings put global stay_on_while_plugged_in 2 >/dev/null 2>&1

restore() {
  if [ -z "$STAY_ON" ] || [ "$STAY_ON" = "null" ]; then
    "$ADB" settings delete global stay_on_while_plugged_in >/dev/null 2>&1
  else
    "$ADB" settings put global stay_on_while_plugged_in "$STAY_ON" >/dev/null 2>&1
  fi
  AFTER_STAY=$("$ADB" settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')
  echo "-- 复原核对：stay_on_while_plugged_in = ${AFTER_STAY}（原值 ${STAY_ON:-空}）"
  # ⚠ 用户 2026-09-30 明令：**不要主动熄屏/锁定**。这台机"熄屏"与"锁定"是同一件事
  # —— 一熄屏就进 keyguard，害得每轮都要他亲手解锁。所以这里**不再发 KEYCODE_SLEEP**，
  # 只把 stay_on_while_plugged_in 复原成原值。要真熄屏由他自己来。
  echo "-- 按要求不主动熄屏（这台机熄屏即锁屏）；只复原 stay_on，屏幕交给系统自己超时"
  if [ "${WT_SLEEP_AT_END:-0}" = 1 ]; then
    "$ADB" shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
    echo "-- 显式要求才熄屏：$("$ADB" shell dumpsys power | grep -oE 'mWakefulness=[A-Za-z]*' | head -1)"
  fi
}
trap restore EXIT

# --- 取证原语 --------------------------------------------------------------
dump() {  # dump <name> -> $OUT/<name>.xml（空 dump 直接判失败，不许拿它算差值）
  local name="$1"
  "$ADB" shell uiautomator dump /sdcard/wota_${name}.xml >/dev/null 2>&1
  "$ADB" shell cat "/sdcard/wota_${name}.xml" > "$OUT/${name}.xml" 2>/dev/null
  "$ADB" shell rm "/sdcard/wota_${name}.xml" >/dev/null 2>&1
  local sz; sz=$(stat -c '%s' "$OUT/${name}.xml" 2>/dev/null || echo 0)
  [ "$sz" -gt 800 ] || fail "$name.xml 只有 ${sz} B —— dump 是空的，这一轮不许下结论"
  note "$name.xml ${sz} B"
}
shot() {  # shot <name> -> $OUT/<name>.png（<40KB 判空帧）
  local name="$1"
  "$ADB" shell screencap -p "/sdcard/wota_${name}.png" >/dev/null 2>&1 || note "screencap $name 失败"
  "$ADB" pull "/sdcard/wota_${name}.png" "$OUTW/${name}.png" >/dev/null 2>&1 || note "pull $name 失败"
  "$ADB" shell rm "/sdcard/wota_${name}.png" >/dev/null 2>&1
  local sz; sz=$(stat -c '%s' "$OUT/${name}.png" 2>/dev/null || echo 0)
  [ "$sz" -lt 40000 ] && note "!! ${name}.png 只有 ${sz} B —— 像空帧，别拿它下结论"
  note "${name}.png ${sz} B"
}
# center <xml-file> <label> -> "X Y"（按 text 或 content-desc 精确匹配，找不到就非零退出）
center() {
  ( cd "$OUT" && python - "$1" "$2" <<'PY'
import io,re,sys
sys.stdout.reconfigure(encoding="utf-8")
xml=io.open(sys.argv[1],encoding="utf-8",errors="replace").read()
want=sys.argv[2]; hits=[]
for m in re.finditer(r'<node[^>]*>',xml):
    s=m.group(0)
    t=re.search(r'text="([^"]*)"',s); d=re.search(r'content-desc="([^"]*)"',s)
    lab=(t.group(1) if t and t.group(1) else (d.group(1) if d else ""))
    if lab.strip()==want:
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s)
        if b: hits.append(tuple(int(b.group(i)) for i in range(1,5)))
if not hits: print("NOTFOUND",file=sys.stderr); sys.exit(3)
if len(hits)>1: print("WARN 同名节点 %d 个：%s" % (len(hits),hits),file=sys.stderr)
x1,y1,x2,y2=hits[0]; print((x1+x2)//2,(y1+y2)//2)
PY
)
}
# diffd <before.xml> <after.xml> [忽略的标签] -> 每个标签的 bounds 差值
diffd() {
  ( cd "$OUT" && python - "$1" "$2" "${3:-}" <<'PY'
import io,re,sys
sys.stdout.reconfigure(encoding="utf-8")
def load(f):
    xml=io.open(f,encoding="utf-8",errors="replace").read(); out={}
    for m in re.finditer(r'<node[^>]*>',xml):
        s=m.group(0)
        t=re.search(r'text="([^"]*)"',s); d=re.search(r'content-desc="([^"]*)"',s)
        lab=((t.group(1) if t else "") or (d.group(1) if d else "")).strip()
        if not lab: continue
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s)
        if not b: continue
        v=tuple(int(b.group(i)) for i in range(1,5))
        out.setdefault(lab,[]).append(v)
    return out
A,B=load(sys.argv[1]),load(sys.argv[2])
skip=set(sys.argv[3].split(",")) if sys.argv[3] else set()
moved=0; same=0
for lab in sorted(set(A)|set(B)):
    if lab in skip: continue
    a=A.get(lab); b=B.get(lab)
    if a is None or b is None:
        print("   %-14s 只在%s出现 -> %s" % (lab,"前" if b is None else "后",a or b)); moved+=1; continue
    if len(a)!=len(b):
        print("   %-14s 颗数 %d -> %d" % (lab,len(a),len(b))); moved+=1; continue
    for i,(p,q) in enumerate(zip(a,b)):
        d=[q[k]-p[k] for k in range(4)]
        if any(d):
            tag=lab if len(a)==1 else "%s#%d" % (lab,i)
            print("   %-14s %s -> %s  dx1=%+d dy1=%+d dx2=%+d dy2=%+d" % (tag,p,q,*d)); moved+=1
        else: same+=1
print("   小结：动了 %d 项 / 未动 %d 项" % (moved,same))
print("   ⚠ x2 顶到 1532 的那些是被 app-bounds 右缘夹住的（本机横屏该侧有 68px 挖孔带），")
print("     所以**向右**的位移在 dump 里量不出来；判移动要看 x1/y1/y2。")
PY
)
}

# findscroll <xml名> <标签> [最多滚几次] -> "X Y"；最后一次 dump 落在 $OUT/<xml名>.xml
# 设置页那行入口在长列表底部，不滚根本不在 dump 里——第一版就栽在这儿。
findscroll() {
  local name="$1" label="$2" max="${3:-10}" i=0 C
  while : ; do
    dump "$name" >/dev/null
    if C=$(center "$name.xml" "$label" 2>/dev/null); then echo "$C"; return 0; fi
    i=$((i + 1))
    [ "$i" -ge "$max" ] && { echo "   滚了 $max 次仍没有「$label」" >&2; return 1; }
    # 滚动坐标按屏推导（原 800/600/800/240 是 1600x720 横屏的写死值）；
    # 函数定义早于 SCREEN 赋值，调用时才取值，故带 :- 兜底
    local _sw=${SCREEN_W:-1600} _sh=${SCREEN_H:-720}
    "$ADB" shell input swipe $((_sw / 2)) $((_sh * 2 / 3)) $((_sw / 2)) $((_sh / 4)) 400 >/dev/null 2>&1; sleep 1
  done
}

# --- 0. 原始 prefs 快照：收尾要能证明"一个字节都没多留" ---------------------
hr
"$ADB" shell "run-as $PKG cat shared_prefs/wota_settings.xml" > "$OUT/prefs_original.xml" 2>/dev/null \
  || note "!! 读不到 prefs（装的是 release 包？run-as 只对 debuggable 生效）"
if grep -q 'name="hud_layout"' "$OUT/prefs_original.xml" 2>/dev/null; then
  note "原始态里 hud_layout **已存在** ⇒ 本轮保存会覆盖它，而「恢复默认」是删键，回不到原值。"
  note "  要么先手工把这份原值记牢再跑，要么接受收尾时键消失。原值："
  grep -oE 'name="hud_layout"[^>]*>[^<]*' "$OUT/prefs_original.xml" | head -1 | cut -c1-300
else
  note "原始态没有 hud_layout（缺键 = 用默认表）⇒ 「恢复默认」删键即可完整复原"
fi

# assert_moved <before.xml> <after.xml> <标签> —— 被拖的那颗自己动没动。
# 少了这道判据，"动了 0 项"会被读成"独立性成立"，而真相可能是那一指根本没吃进去
# （实测栽过：读数块那颗向下拖 90px 掉出 720 高的屏幕，diff 报 0 移动，看着像通过）。
assert_moved() {
  ( cd "$OUT" && python - "$1" "$2" "$3" <<'PY'
import io,re,sys
sys.stdout.reconfigure(encoding="utf-8")
def b0(f,want):
    x=io.open(f,encoding="utf-8",errors="replace").read()
    for m in re.finditer(r'<node[^>]*>',x):
        s=m.group(0)
        t=re.search(r'text="([^"]*)"',s); d=re.search(r'content-desc="([^"]*)"',s)
        lab=((t.group(1) if t else "") or (d.group(1) if d else "")).strip()
        if lab==want:
            bb=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s)
            if bb: return tuple(int(bb.group(i)) for i in range(1,5))
    return None
a=b0(sys.argv[1],sys.argv[3]); b=b0(sys.argv[2],sys.argv[3])
if a is None or b is None:
    print("   ?? 「%s」有一头不在树里（%s / %s）"%(sys.argv[3],a,b)); sys.exit(2)
if a==b:
    print("   !! 「%s」自己没动 ⇒ 这一组拖拽未生效，不许当成独立性成立"%(sys.argv[3])); sys.exit(1)
print("   被拖的「%s」确实动了：%s -> %s"%(sys.argv[3],a,b)); sys.exit(0)
PY
)
}

# goto_camera <探针名>：把 app 弄回取景页。
# ⚠ `am start` **不重置导航栈**：上一轮中止时 app 停在设置页，am start 只会把它恢复回设置页，
# 于是"取景页基线"截出来是设置页那组「取景器常驻功能」的清单（实测栽过一次，PNG 只有 192KB）。
# 判据用取景页独有的那枚「设置」content-desc 节点；不在就按 BACK，最多 5 次，仍不在就停手。
goto_camera() {
  local probe="$1" i=0
  "$ADB" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1; sleep 2
  while : ; do
    dump "$probe" >/dev/null
    if center "$probe.xml" "设置" >/dev/null 2>&1; then
      if [ "$i" -gt 0 ]; then note "  第 $i 次 BACK 之后回到取景页"; else note "  本来就在取景页"; fi
      return 0
    fi
    i=$((i + 1))
    if [ "$i" -ge 5 ]; then
      echo "   连按 5 次 BACK 仍回不到取景页；去看 $OUT/$probe.xml 到底停在哪一页" >&2
      return 1
    fi
    note "  不在取景页，按 BACK（第 $i 次）"
    "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1
  done
}

# --- 1. 进取景页，取默认态基线 ---------------------------------------------
hr
goto_camera CAM_0 || fail "回不到取景页，后面的判据全都不成立"
shot CAM_0
# 拖拽目标必须夹在屏内。上一轮栽的就是这个：参考线 已被前一轮拖到 y=552，再 +160 就是 712，
# 那一指被系统直接丢掉，而 diff 报"0 移动"看着像独立性成立 —— assert_moved 就是为堵这个才加的。
# ⚠ 尺寸只能在**到了取景页之后**量：`wm size` 报的是竖屏物理值（本机 720x1600），
# 而脚本刚起来时前台还是竖屏 launcher，在这行之前量会算成 720x1600。
ROT=$("$ADB" shell dumpsys input 2>/dev/null | grep -m1 "SurfaceOrientation" | grep -oE "[0-9]$")
note "SurfaceOrientation=$ROT（取景页应为 1 或 3 的横屏；0 说明方向没生效）"
# 面板尺寸运行时读（10-01 清查：不再按测试机写死 1600x720）。wm size 给的是物理尺寸，
# 注释里的经验是它通常报竖屏值；按取景页实测的 SurfaceOrientation 决定是否交换成长边在前。
WMSIZE=$("$ADB" shell wm size 2>/dev/null)
# Override 行优先（同 wm density 口径：开发者选项改过显示大小后 app 实际用 override）
PHYS=$(echo "$WMSIZE" | grep -i override | grep -oE '[0-9]+x[0-9]+' | head -1)
[ -z "$PHYS" ] && PHYS=$(echo "$WMSIZE" | grep -oE '[0-9]+x[0-9]+' | head -1)
PW=${PHYS%x*}; PH=${PHYS#*x}
if [ -z "$PW" ] || [ -z "$PH" ]; then
    note "!! wm size 读不到（'$WMSIZE'），屏幕夹取退回 1600x720 —— 换机时先修这里"
    PW=720; PH=1600
fi
SCREEN_W=$PW; SCREEN_H=$PH
case "$ROT" in 1|3) SCREEN_W=$PH; SCREEN_H=$PW;; esac
# 密度同轮读出（拖拽位移按 dp 标定，见 DY_DOWN/DY_UP）：wm density → "320" ⇒ 3.20
WMD=$("$ADB" shell wm density 2>/dev/null)
DENSITY_RAW=$(echo "$WMD" | grep -i override | grep -oE '[0-9]+' | head -1)
[ -z "$DENSITY_RAW" ] && DENSITY_RAW=$(echo "$WMD" | grep -oE '[0-9]+' | head -1)
DENSITY_RAW=${DENSITY_RAW:-320}
# 原按 density 2 标定的两个位移（+160/-120 px = 80dp/60dp），换成 dp 标定后换机自动跟随
DY_DOWN=$((80 * DENSITY_RAW / 100))
DY_UP=$((60 * DENSITY_RAW / 100))
clamp() { local v=$1; [ "$v" -lt 24 ] && v=24; [ "$v" -gt "$((SCREEN_H - 24))" ] && v=$((SCREEN_H - 24)); echo "$v"; }
note "可视尺寸取 ${SCREEN_W}x${SCREEN_H}（wm size ${PW}x${PH} + ROT=$ROT），位移 ${DY_DOWN}/${DY_UP}px（density ${DENSITY_RAW}）"

# --- 0M. --reset-only：只把摆位清回出厂，不做取证 ---------------------------
# 基线被上一轮拖脏时先跑一次这个再跑完整取证，否则 ED_0 不是默认态、整批差值不可解释。
# 走的是编辑页那两步「恢复默认」-> 「确认恢复默认？」，它内部是 clearHudLayout（删键），
# 删完就是原始缺键态 —— 与本轮取证之前抓的 prefs_original.xml 可比。
if [ "${1:-}" = "--reset-only" ]; then
  hr
  echo "[--reset-only] 归零摆位，不做取证"
  C=$(center CAM_0.xml "设置") || fail "取景页里没有「设置」那枚"
  "$ADB" shell input tap $C >/dev/null 2>&1; sleep 2
  C=$(findscroll SET_0 "编辑控件位置") || fail "设置页里没有「编辑控件位置」"
  "$ADB" shell input tap $C >/dev/null 2>&1; sleep 2
  dump ED_R
  C=$(center ED_R.xml "恢复默认") || fail "编辑页里没有「恢复默认」那颗"
  note "  第一步（武装）@ $C"
  "$ADB" shell input tap $C >/dev/null 2>&1; sleep 1
  dump ED_R2
  C=$(center ED_R2.xml "确认恢复默认？") || fail "武装后没出现「确认恢复默认？」，别乱点"
  note "  第二步（真删）@ $C"
  "$ADB" shell input tap $C >/dev/null 2>&1; sleep 1
  "$ADB" shell "run-as $PKG cat shared_prefs/wota_settings.xml" > "$OUT/prefs_after_reset.xml" 2>/dev/null
  if grep -q 'name="hud_layout"' "$OUT/prefs_after_reset.xml" 2>/dev/null; then
    fail "hud_layout 仍在盘上，归零没成"
  fi
  note "  hud_layout 已删，回到缺键=默认表态"
  exit 0
fi

# --- 2. 进设置 -> 编辑控件位置 ---------------------------------------------
hr
C=$(center CAM_0.xml "设置") || fail "dump 里找不到「设置」那枚，入口没锚上"
note "点设置 @ $C"
"$ADB" shell input tap $C >/dev/null 2>&1; sleep 2
C=$(findscroll SET_0 "编辑控件位置") || fail "设置页滚到底也没有「编辑控件位置」那行"
note "点编辑控件位置 @ $C"
"$ADB" shell input tap $C >/dev/null 2>&1; sleep 2
dump ED_0; shot ED_0
grep -q "hud_edit\|恢复默认\|保存" "$OUT/ED_0.xml" || note "!! 编辑页 dump 里没看到保存/恢复默认文案，可能没进对页"

# --- 3. #79：编辑页操作栏那三颗必须真能点 ----------------------------------
hr
echo "[#79] 操作栏三颗的锚点与可点性"
for L in 保存 恢复默认 返回; do
  if C=$(center ED_0.xml "$L"); then note "  「$L」@$C"; else note "  「$L」不在 dump 里（可能是图标档，语义没暴露）"; fi
done

# --- 4. #80：拖一颗，别颗不许动 --------------------------------------------
hr
echo "[#80-A] 左 Dock 拖「参考线」向下 2 格"
SRC=$(center ED_0.xml "参考线") || fail "编辑页里没有「参考线」那颗"
set -- $SRC; SX=$1; SY=$2
TY=$(clamp $((SY + DY_DOWN)))
[ "$TY" = "$SY" ] && fail "  「参考线」在 y=$SY，向下已无处可拖（上限 $((SCREEN_H-24))）⇒ 先跑 --reset-only 归零再取证"
note "  源 @ $SX,$SY -> $SX,$TY（想拖 +${DY_DOWN}px，夹后实拖 $((TY-SY))）"
"$ADB" shell input swipe $SX $SY $SX $TY 900 >/dev/null 2>&1; sleep 1
dump ED_1; shot ED_1_after_A
assert_moved ED_0.xml ED_1.xml "参考线"
diffd ED_0.xml ED_1.xml

hr
echo "[#80-B] 读数块拖「快门」向下 1 格（这条是**已知残差**：整块会平移，量出来别当新缺陷）"
SRC=$(center ED_1.xml "快门") || fail "ED_1 里没有「快门」那颗"
set -- $SRC; SX=$1; SY=$2
TY=$(clamp $((SY - DY_UP)))
[ "$TY" = "$SY" ] && fail "  「快门」在 y=$SY，向上已无处可拖 ⇒ 这一组测不了"
note "  源 @ $SX,$SY -> $SX,$TY（想拖 -${DY_UP}px，夹后实拖 $((SY-TY))）"
"$ADB" shell input swipe $SX $SY $SX $TY 900 >/dev/null 2>&1; sleep 1
dump ED_2; shot ED_2_after_B
assert_moved ED_1.xml ED_2.xml "快门"
diffd ED_1.xml ED_2.xml

# --- 5. 落盘证据：run-as 读盘上的 prefs（不是 force-stop 重进） -------------
hr
echo "[持久化] 保存之前 / 之后，盘上 hud_layout 键"
"$ADB" shell "run-as $PKG cat shared_prefs/wota_settings.xml" > "$OUT/prefs_before.xml" 2>/dev/null
note "  保存前：$(grep -c 'hud_layout' "$OUT/prefs_before.xml" 2>/dev/null || echo 0) 处 hud_layout"
if C=$(center ED_2.xml "保存"); then
  note "  点保存 @ $C  ← 这一指同时是 #79 的判据：点不动就没法落盘"
  "$ADB" shell input tap $C >/dev/null 2>&1; sleep 2
else
  fail "ED_2 里没有「保存」那颗 —— #79 的判据不成立，先回去查操作栏"
fi
# 保存只落盘、不返回（`HudLayoutEditor` 里 onClick 只写 prefs + 置 hint）。
# 少了这一步 BACK，CAM_1 截到的会是编辑页，拿它跟 CAM_0 比就是拿两页相比。
if C=$(center ED_2.xml "返回"); then
  note "  点返回 @ $C ← #79 的第二颗"
  "$ADB" shell input tap $C >/dev/null 2>&1; sleep 2
else
  note "  「返回」那颗不在 dump 里，退而按系统 BACK"
  "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 2
fi
goto_camera CAM_1 || fail "保存返回后进不了取景页，CAM_1 不能跟 CAM_0 比"
shot CAM_1_after_save
"$ADB" shell "run-as $PKG cat shared_prefs/wota_settings.xml" > "$OUT/prefs_after.xml" 2>/dev/null
echo "  保存后 hud_layout 那一行的值："
grep -oE 'name="hud_layout"[^>]*>[^<]*' "$OUT/prefs_after.xml" | head -1 | cut -c1-400
diffd CAM_0.xml CAM_1.xml

# --- 6. 复原：恢复默认是**两步**，第二步的标签会变成「确认恢复默认」 ----------
# 看代码：HudLayoutEditor.kt:802 那颗 WotaChip 的 label 随 resetArmed 换文案，
# 点第二下走 clearHudLayout（删键，缺键就是默认表）。所以复原之后**不许再点保存**——
# 保存会把内存里的默认表又写回 prefs，键凭空多出来，等于没复原。
hr
echo "[复原] 恢复默认 -> 确认恢复默认（两步，第二步标签会变）"
"$ADB" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1; sleep 2
goto_camera CAM_2 || fail "复原阶段回不到取景页"
C=$(center CAM_2.xml "设置"); "$ADB" shell input tap $C >/dev/null 2>&1; sleep 2
C=$(findscroll SET_1 "编辑控件位置") || fail "复原阶段设置页里没有「编辑控件位置」"
"$ADB" shell input tap $C >/dev/null 2>&1; sleep 2
dump ED_3
if C=$(center ED_3.xml "恢复默认"); then
  note "  第一步：点「恢复默认」@$C（只是武装，不该删）"
  "$ADB" shell input tap $C >/dev/null 2>&1; sleep 1
  dump ED_4
  if C2=$(center ED_4.xml "确认恢复默认？"); then
    note "  第二步：标签确实变成「确认恢复默认？」@$C2 —— 两步确认这条被 dump 证实了"
    "$ADB" shell input tap $C2 >/dev/null 2>&1; sleep 1
  else
    fail "武装之后 dump 里没出现「确认恢复默认？」，第二步点不了；别乱点，回去看 ED_4.xml"
  fi
  dump ED_5; shot ED_5_reset
  "$ADB" shell "run-as $PKG cat shared_prefs/wota_settings.xml" > "$OUT/prefs_restored.xml" 2>/dev/null
  if grep -q 'name="hud_layout"' "$OUT/prefs_restored.xml" 2>/dev/null; then
    note "  !! 复原失败：hud_layout 仍在 prefs 里"
  else
    note "  hud_layout 已从 prefs 删除（回到原始缺键态）"
  fi
  echo "  与原始 prefs 逐项对比："
  diff <(tr -d '\r' < "$OUT/prefs_original.xml" | sort) <(tr -d '\r' < "$OUT/prefs_restored.xml" | sort) \
    && echo "    prefs 与原始态**完全一致**" || note "    ↑ 上面这些差异就是本轮没复原干净的部分"
else
  note "  !! 没找到「恢复默认」，摆位可能留在草稿里——但草稿不落盘，回录制页不受影响"
fi
goto_camera CAM_3 || fail "复原后回不到取景页"
shot CAM_3_final
diffd CAM_0.xml CAM_3.xml "设置"

hr
echo "evidence: $OUT"
ls -1 "$OUT"
