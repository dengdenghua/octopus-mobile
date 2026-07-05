#!/usr/bin/env bash
# ============================================================================
# 无障碍保活真机验证清单(红米 8A 等目标机)
#
# 用法:手机开 USB 调试插上电脑 → 装上带本次改动的 APK → 开好无障碍 →
#       bash scripts/verify-keepalive.sh
#
# 这条 bug 线连修四次都栽在「没在目标场景真机验证」。本脚本把「肉眼猜」变成
# 「可观测」:每个场景给出 期望结果 + 实际探针命令,自己对照即可判定。
#
# 前置:
#   - adb 能连上(adb devices 有设备)
#   - APK 已装、无障碍已在系统设置里开启过一次
#   - 想验 ① Shizuku 自愈,则设备上 Shizuku 必须在运行(否则自愈那步天然跳过)
# ============================================================================
set -uo pipefail

PKG="com.octopus.mobile"
A11Y_CLS="com.apk.claw.android.service.ClawAccessibilityService"
COMPONENT="${PKG}/${A11Y_CLS}"
GUARD_JOB_ID=10086          # KeepAliveJobService 周期巡检 job
FGS_TAG="ForegroundService"

say() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
probe() { printf '\033[0;33m$ %s\033[0m\n' "$*"; eval "$*"; }

# ---- 0. 连接与基本信息 -----------------------------------------------------
say "0. 设备与安装检查"
adb devices | sed 1d | grep -q device || { echo "❌ 没有已授权的 adb 设备"; exit 1; }
probe "adb shell pm path $PKG" || { echo "❌ 未安装 $PKG"; exit 1; }
echo "组件: $COMPONENT"

# ---- 1. 基线:无障碍 + 前台服务都在 ----------------------------------------
say "1. 基线状态(此刻应:无障碍在启用列表里 + 前台服务在跑)"
echo "期望:enabled_accessibility_services 里含上面的组件;accessibility_enabled=1"
probe "adb shell settings get secure enabled_accessibility_services"
probe "adb shell settings get secure accessibility_enabled"
echo "期望:能看到本 App 的前台服务(isForeground=true)"
probe "adb shell dumpsys activity services $PKG | grep -iE 'ForegroundService|isForeground|A11y' | head"

# ---- 2. 场景 C:模拟 MIUI 把无障碍关掉 → 验 ① Shizuku 自愈 ------------------
# 这是最干净的「单独测自愈」——不用真触发 OEM 杀进程,直接把开关抹掉再让守护 job 跑一次。
say "2. 场景C:抹掉无障碍启用项,强制跑守护 job,看 A11ySelfHeal 是否写回(需 Shizuku 在跑)"
echo ">> 先开一个 logcat 抓我们的 TAG(另开一个终端跑更直观):"
echo "   adb logcat -c && adb logcat -s A11ySelfHeal:V KeepAliveJob:V ${FGS_TAG}:V ClawA11yService:V"
echo
echo ">> 抹掉无障碍(模拟系统关闭):"
probe "adb shell settings put secure enabled_accessibility_services ''"
probe "adb shell settings put secure accessibility_enabled 0"
echo ">> 立即强制跑一次守护 job(不等 15 分钟):"
probe "adb shell cmd jobscheduler run -f $PKG $GUARD_JOB_ID"
sleep 3
echo "期望(Shizuku 在跑时):下面应重新出现本 App 组件、accessibility_enabled 回到 1"
probe "adb shell settings get secure enabled_accessibility_services"
probe "adb shell settings get secure accessibility_enabled"
echo "→ 若组件回来了 = ① 自愈生效;若仍为空 = Shizuku 没在跑 或 自愈没触发(看 logcat 的 A11ySelfHeal 行)"

# ---- 3. 场景 B:软杀(内存回收类)→ 验前台保活让进程/服务活着 --------------
say "3. 场景B:把 App 退到后台再软杀进程,看是否被前台服务拦住/恢复"
echo ">> 把 App 切后台(回桌面):"
probe "adb shell input keyevent KEYCODE_HOME"
sleep 1
echo ">> am kill 只能杀『后台可回收』进程——若前台服务真在跑,进程不该被这条杀掉:"
probe "adb shell am kill $PKG"
sleep 2
echo "期望:前台服务仍在(进程被前台状态保住);无障碍仍绑定"
probe "adb shell dumpsys activity services $PKG | grep -iE 'ForegroundService|isForeground' | head"
probe "adb shell dumpsys accessibility | grep -i claw | head"

# ---- 4. 场景 D:force-stop(最狠,诚实标注:app 层无法自恢复) --------------
say "4. 场景D:force-stop(等价 MIUI 上滑清理白名单外应用)—— 诚实预期"
echo "⚠️ force-stop 会杀进程 + 清 pending 闹钟 + 暂停所有 Job + 关无障碍。"
echo "   四层前台保活全部失效,② 的 expedited 恢复 job 也被取消——app 层代码无解。"
echo "   唯一出路是用户开『自启动 + 省电无限制』白名单(引导页要做的事),不是代码能救的。"
echo "   这条不自动跑,避免误判『保活失败』——它本就该失败,是 OS 设计使然。"
echo "   如需手测:adb shell am force-stop $PKG,然后观察是否要重开 App 才恢复(预期:要)。"

# ---- 5. 收尾:把无障碍恢复回去(场景C 抹过) --------------------------------
say "5. 收尾"
echo "若场景C 后无障碍没自动回来(无 Shizuku),手动到系统设置重新打开,或:"
echo "   adb shell settings put secure enabled_accessibility_services $COMPONENT"
echo "   adb shell settings put secure accessibility_enabled 1"
echo
echo "判定要点:"
echo "  · 场景C 组件写回 = ① Shizuku 自愈生效(本次核心新增)"
echo "  · 场景B 进程/前台服务扛住 = 前台保活生效"
echo "  · 场景D 需重开 App = 已知 OS 限制,靠引导页开白名单,不是 bug"
