#!/usr/bin/env bash
# ============================================================================
# WotaGeiCam 真机取证脚本
#   覆盖：#74 网格固定摆位 / #70 改名与左右 Dock 实测宽度 / #71 底板长度收拢中间态
#
# 为什么需要它：这一批改动的**功能**证据已经全部拿到（JVM 312 条 + 突变自检 + 持久化的
# 真机跨进程读回），唯一没覆盖的是"屏幕上真的按格子摆、底板到底多宽"。而这些手法每次都要
# 临时想，反复踩同一批坑（screenrecord 不存在、screencap 走管道会坏、uiautomator 的表观
# 右缘是伪值、force-stop 吃落盘）。本脚本把手法固化，并**自己算数字**而不是靠肉眼估。
#
# 用法（项目根目录的 Git Bash 里）：
#   bash WotaGeiCamApp/scripts/verify-on-device.sh            # 只做布局/宽度取证（正式包即可）
#   bash WotaGeiCamApp/scripts/verify-on-device.sh --merge    # 再加 #71 中间态（须装 debugHook 包）
#
# 写死在脚本里的规矩（不许绕过）：
#   1. 绝不尝试解锁。只**检查**你是否亲手解开了；没解开就停下等，不模拟密码、不替你滑。
#   2. 绝不 uninstall —— 那会清空 wota_settings 与 wota_media.db（用户设置、收藏、tag）。
#   3. 绝不用 am force-stop 证明持久化 —— 强杀会吃掉异步落盘，本项目因此误判过一次缺陷。
#   4. 截图一律 screencap 到 /sdcard 再 pull —— 管道重定向在 MSYS 下会损坏 PNG。
#   5. 临时产物只进项目内 .tmp/（本机规矩：C 盘不落临时文件）。
#   6. 只读不改设置：不碰 hud_pills / motion_mode / 文本高度，免得取证完留下污染态。
# ============================================================================

set -u
export MSYS_NO_PATHCONV=1
export PYTHONIOENCODING=utf-8

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="$ROOT/tools/android-sdk/platform-tools/adb.exe"
PKG="com.wotagei.cam"
OUT="$ROOT/.tmp/verify-$(date +%Y%m%d-%H%M)"
DENSITY=2.0          # 本机实测 320dpi ⇒ dp = px / 2
MERGE_STAGE=0
[ "${1:-}" = "--merge" ] && MERGE_STAGE=1

fail() { echo "!! ABORT: $*"; exit 1; }
note() { echo "-- $*"; }
hr()   { echo "------------------------------------------------------------"; }

[ -x "$ADB" ] || fail "adb not found at $ADB"
"$ADB" devices | grep -qw device || fail "no authorized device"
mkdir -p "$OUT" || fail "cannot create $OUT"
# adb 是 Windows 版：给它 `/f/Works/...` 这种 MSYS 绝对路径会**直接 pull 失败**（实测）。本地文件操作用 $OUT，喂给 adb 的那一份换成 cygpath 转出来的 Windows 路径。
OUTW=$(cygpath -w "$OUT" 2>/dev/null) || OUTW="$OUT"

hr
echo "evidence dir: $OUT"

# --- 0. 装机态：确认装的是哪一枚，别把钩子态当正常渲染 ---------------------
hr
FLAGS=$("$ADB" shell dumpsys package "$PKG" | grep -oE "flags=\[[^]]*\]" | head -1)
LAST=$("$ADB" shell dumpsys package "$PKG" | grep -oE "lastUpdateTime=[^ ]* [^ ]*" | head -1)
echo "installed: $FLAGS  $LAST"
DEBUGGABLE=0
case "$FLAGS" in *DEBUGGABLE*) DEBUGGABLE=1;; esac
if [ "$MERGE_STAGE" = 1 ] && [ "$DEBUGGABLE" = 0 ]; then
  fail "merge stage needs the debugHook build (MERGE_HOOK lives there). Build & install it first:
   cd $ROOT/WotaGeiCamApp && ./gradlew --offline :app:assembleDebugHook
   \"$ADB\" install -r app/build/outputs/apk/debugHook/app-debugHook.apk   # install -r，严禁 uninstall"
fi

# --- 1. 唤醒并**等你亲手解锁**（脚本不碰密码）------------------------------
hr
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
LOCKED=$("$ADB" shell dumpsys trust 2>/dev/null | grep -oE "deviceLocked=[01]" | head -1)
echo "lock state: ${LOCKED:-unknown}"
if [ "$LOCKED" != "deviceLocked=0" ]; then
  echo
  echo "  >>> 请现在亲手解锁这台手机（脚本绝不代理解锁）。"
  echo "  >>> 解开后回这里按回车继续；不想跑了就输 q 回车。"
  read -r -p "  ready? " REPLY
  case "${REPLY,,}" in
    q) echo "user quit before unlock"
       "$ADB" shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
       exit 0 ;;
  esac
  LOCKED=$("$ADB" shell dumpsys trust 2>/dev/null | grep -oE "deviceLocked=[01]" | head -1)
  [ "$LOCKED" = "deviceLocked=0" ] || fail "still locked ($LOCKED). Unlock it, then re-run."
fi
# 解了锁不代表屏幕还醒着（WAKEUP 不总粘住；熄屏后 activity 也不 resume、不走 layout）。
# 锁屏下 `am start` 只会把焦点留在 NotificationShade，截出来是空帧——这条是实测过的，见 §15.8。
AWAKE=$("$ADB" shell dumpsys power | grep -oE "mWakefulness=[A-Za-z]*" | head -1)
if [ "$AWAKE" != "mWakefulness=Awake" ]; then
  note "screen not awake ($AWAKE) after unlock; waking once more"
  "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  sleep 1
  AWAKE=$("$ADB" shell dumpsys power | grep -oE "mWakefulness=[A-Za-z]*" | head -1)
  [ "$AWAKE" = "mWakefulness=Awake" ] || fail "屏幕仍是 $AWAKE —— 空帧会被当成证据，先让它亮着再跑"
fi
note "unlocked. focus now:"
"$ADB" shell dumpsys window | grep -E "mCurrentFocus" | head -1

# --- 2. 进取景页（方向由应用自己强制：ui_orientation 默认 landscape）------
hr
"$ADB" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
sleep 3

shot() {  # shot <name> -> $OUT/<name>.png 与 .xml
  local name="$1"
  "$ADB" shell screencap -p "/sdcard/wota_${name}.png" >/dev/null 2>&1 || { echo "   !! screencap failed"; return 1; }
  "$ADB" pull "/sdcard/wota_${name}.png" "$OUTW/${name}.png" >/dev/null 2>&1 || { echo "   !! pull failed"; return 1; }
  "$ADB" shell rm "/sdcard/wota_${name}.png" >/dev/null 2>&1
  "$ADB" shell uiautomator dump /sdcard/wota_${name}.xml >/dev/null 2>&1
  "$ADB" shell cat "/sdcard/wota_${name}.xml" > "$OUT/${name}.xml" 2>/dev/null
  "$ADB" shell rm "/sdcard/wota_${name}.xml" >/dev/null 2>&1
  local sz
  sz=$(stat -c '%s' "$OUT/${name}.png" 2>/dev/null || echo 0)
  # 阈值来自实测：锁屏熄屏时 screencap 仍然"成功"，但只出 7904 B 的空图，
  # 而取景页这种带相机画面的帧是几百 KB 量级。低于 40 KB 一律判为可疑，不当证据用。
  if [ "${sz}" -lt 40000 ]; then
    echo "   !! ${name}.png 只有 ${sz} B —— 像是熄屏/空帧，重试一次"
    "$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; sleep 1
    "$ADB" shell screencap -p "/sdcard/wota_${name}.png" >/dev/null 2>&1
    "$ADB" pull "/sdcard/wota_${name}.png" "$OUTW/${name}.png" >/dev/null 2>&1
    "$ADB" shell rm "/sdcard/wota_${name}.png" >/dev/null 2>&1
    sz=$(stat -c '%s' "$OUT/${name}.png" 2>/dev/null || echo 0)
  fi
  local wk flag
  wk=$("$ADB" shell dumpsys power | grep -oE "mWakefulness=[A-Za-z]*" | head -1)
  if [ "${sz}" -lt 40000 ]; then flag="SUSPECT-BLANK"; else flag="ok"; fi
  printf '%s	%s B	%s	%s
' "$name" "$sz" "$wk" "$flag" >> "$OUT/manifest.tsv"
  echo "   captured ${name}.png (${sz} B, ${wk})"
}

hr
echo "[A] 默认态取景页"
shot A_default

# --- 3. 从 dump 里**算**宽度 ----------------------------------------------
hr
echo "[B] 左右 Dock 与读数块的实测宽度"
if [ ! -s "$OUT/A_default.xml" ]; then
  printf '%s	%s	%s	%s
' "A_default.xml" "0 B" "n/a" "SUSPECT-EMPTY-DUMP" >> "$OUT/manifest.tsv"
  echo "   !! uiautomator 没吐出内容（这台机 dump 有时会报成功但写空），[B] 的数字这一轮不可用"
fi
# ⚠ 这里必须先 cd 进 $OUT 再传**相对文件名**：Windows 版 python 与 Windows 版 adb 一样不认
# `/f/Works/...` 这种 MSYS 绝对路径（dry-run 时它就报了 No such file，而文件确实在）。
( cd "$OUT" && python - "A_default.xml" "$DENSITY" <<'PYEOF' 
import io, re, sys
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass
try:
    xml = io.open(sys.argv[1], encoding="utf-8", errors="replace").read()
except Exception as e:
    print("   dump unreadable:", e); sys.exit(0)
d = float(sys.argv[2])

# 按"这个容器里到底有哪几颗标签"归组，**不按 x 阈值**。
# 自检时踩过这个坑：按 x<260 分区会把设置页/面板标题那枚「屏幕监看」误算进左 Dock，
# 量出 140dp 的假宽度。容器归属只能照登记表查，不能靠坐标猜——与 AGENTS 里
# "控件在哪个容器的唯一真源是 pillAnchorWriters 那张表"是同一条纪律。
ZONE_LABELS = {
    "左 Dock": ["监看", "参考线", "RGB 曲线", "闪光灯"],
    "右 Dock": ["蓝牙", "变焦", "对焦", "防抖"],
    "读数块":  ["快门", "帧率", "码率"],
}
ALL = set(x for v in ZONE_LABELS.values() for x in v)

rows = []
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t = m.group(1)
    x1, y1, x2, y2 = (int(m.group(i)) for i in range(2, 6))
    if t in ALL:
        rows.append((t, x1, y1, x2, y2))

if not rows:
    print("   dump 里没有任何 HUD 文本节点 => Compose 语义没暴露，结论只能靠看图")
    sys.exit(0)

for t, x1, y1, x2, y2 in sorted(rows, key=lambda r: (r[1], r[2])):
    print("   %-9s x=%4d..%-4d y=%4d..%-4d  w=%5.1f dp" % (t, x1, x2, y1, y2, (x2 - x1) / d))
print()
for zone, labels in ZONE_LABELS.items():
    g = [r for r in rows if r[0] in labels]
    if not g:
        print("   %s：一颗都没量到（可能被 hud_pills 关了，或不在这一屏）" % zone)
        continue
    lo = min(r[1] for r in g)
    hi = max(r[3] for r in g)
    print("   %s 条目并集 %.1f dp + 底板内边距 4+4 => 底板约 %.1f dp（量到 %d/%d 颗）"
          % (zone, (hi - lo) / d, (hi - lo) / d + 8.0, len(g), len(labels)))
print()
print("   ⚠ 前提两条，结论要连前提一起读：")
print("     1) 底板那枚 Box 没有语义节点，宽度是「条目并集 + 一侧 4dp 内边距」回推的；")
print("     2) 录制中右 Dock 的姿态仪与音量表是**自绘件、没有文本节点**，那一档只能量到下界，")
print("        与手算的 94dp 对不上不代表回归。")
if any(r[0] == "监看" for r in rows):
    print("   改名已落到取景页那颗（树里出现「监看」）")
else:
    print("   !! 树里没有「监看」：改名没落到取景页那颗，回去查 cam_p_monitor 的消费点")
PYEOF
)

# --- 4. #74 独立性：拖一颗，别颗不许动 -----------------------------------
hr
echo "[C] 独立性取证（脚本只取证，判定由人对着 XML 比同一颗的 bounds）"
echo "    手法：进「编辑控件」-> 按住某一颗 -> 拖到别的格子 -> 松手 -> 保存 -> 回取景页"
# ⚠ 上一版这里 `read` 在非交互（stdin 不是 tty，比如被 nohup/管道跑）时**立刻返回空串**，
# 下面的 case 匹配不到 `q` 就走了 `*)` 那一支，于是"没拖任何东西"也被当成"拖完了"，
# 连拍五张取景页 → 五份 XML 字节数一模一样（13376 B），[C]/[D] 看着有产物其实零证据。
# 现在空输入与 EOF 一律显式跳过，宁可少一张图，也不许拿取景页连拍冒充"拖完一颗"。
[ -t 0 ] || note "stdin 不是 tty：[C]/[D] 要人手拖拽与划最近任务，这三步会被跳过"
shot C_before_drag
read -r -p "    现在手动拖**一颗**，完成后回车（q 或空 = 跳过）: " R1
case "${R1,,}" in ""|q) note "[C] 第一拖跳过（无手动输入，不许拿取景页连拍冒充）" ;; *) shot C_after_drag ;; esac
read -r -p "    保存并回取景页后回车（q 或空 = 跳过）: " R2
case "${R2,,}" in ""|q) note "[C] 回取景页那帧跳过" ;; *) shot C_back_on_camera ;; esac

# --- 5. 持久化：从最近任务划掉再进，**不许 force-stop** -------------------
hr
echo "[D] 持久化：手动从最近任务里划掉本应用再重进，摆位应仍在"
echo "    （不用 am force-stop：强杀吃掉异步落盘，历史上就是这么误判出一条 S1 的）"
read -r -p "    划掉再点开、看到取景页后回车（q 或空 = 跳过）: " R3
case "${R3,,}" in ""|q) note "[D] 跳过（同上：空输入不等于'已划掉重进过'）" ;; *) shot D_after_relaunch; note "对照 C_back_on_camera 里各颗 bounds 是否一致" ;; esac

# --- 6. 可选：#71 融合中间态 ---------------------------------------------
if [ "$MERGE_STAGE" = 1 ]; then
  hr
  echo "[E] #71 底板长度收拢的中间态（钩子钉进度）"
  echo "    每张图必须看得见「调试钉住 p=…」那枚徽标，否则这张不算钩子态证据。"
  for P in 0.25 0.5 0.65 0.7 0.8 0.9 1; do
    "$ADB" shell am start -n "$PKG/.ui.MainActivity" --es wota_merge_hook "pin=$P" >/dev/null 2>&1
    sleep 2
    # ⚠ 必须走 shot()，不许再自己写 pull。上一版这里 pull 的目标是 "$OUT/E_p$P.png"
    # ——那是 MSYS 形式（`/f/Works/...`），Windows 版 adb 认不出来，pull 直接失败；
    # 失败又被 `>/dev/null 2>&1` 吞掉、返回码没人看，下一行还照打 "pinned p=… -> E_p….png"，
    # 于是七张钉档帧一张都没落地，manifest.tsv 里也一行都没有（任务 #71 取证的第 2 个洞）。
    # shot() 用的是 cygpath 转出来的 $OUTW，并且 pull 失败会 return 1 并打 !! pull failed。
    shot "E_p$P"
    # 钩子态的判据落在**像素之外的第二证**：徽标是文本节点，dump 里必须出现「调试钉住 p=…」，
    # 没有它这张图就不能当钩子态证据（光看图容易把"底板本来就窄"当成钉住了）。
    BADGE=$(grep -o 'text="调试钉住[^"]*"' "$OUT/E_p$P.xml" 2>/dev/null | head -1)
    echo "   pinned p=$P -> E_p$P.png  徽标: ${BADGE:-!! dump 里没有「调试钉住」，这张不算钩子态证据}"
  done
  note "钩子不持久化。下面关掉它并冷启动，回到正常态再截一张做对照"
  "$ADB" shell am start -n "$PKG/.ui.MainActivity" --es wota_merge_hook "off" >/dev/null 2>&1
  sleep 2
  "$ADB" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
  sleep 2
  shot E_hook_off_normal
fi

# --- 7. 收尾：熄屏 --------------------------------------------------------
hr
"$ADB" shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1
sleep 1
echo "device now: $("$ADB" shell dumpsys power | grep -oE "mWakefulness=[A-Za-z]*" | head -1)"
hr
echo "evidence: $OUT"
ls -1 "$OUT" | sed 's/^/   /'
echo
echo "怎么读这批图："
echo "  [B] 的数字是算出来的（前提见那两行 ⚠）；"
echo "  [C]/[D] 对着 XML 比：应当**只有一颗**的 bounds 变了，且划掉重进后与保存时一致；"
echo "  manifest.tsv：每帧的大小/当时唤醒态/是否可疑；出现 SUSPECT-BLANK 就说明那帧是空帧，别拿它下结论。"
echo "  [E] 逐档看底板长轴是否**连续变短**、两颗是否真的离开原位、接触颈是否接在飞行中的那颗上。"
