#!/bin/bash
# octopus-mobile 一键安装/调试脚本.
#
# 跑法:
#   ./quickstart.sh install   # 编译 + 装到手机
#   ./quickstart.sh test      # 跑单元测试
#   ./quickstart.sh demo      # 跑 hello world demo
#   ./quickstart.sh all       # 全部
#
# 环境要求:
#   - JDK 17+
#   - Android SDK (build-tools 34+)
#   - adb (在 PATH)
#   - 手机开启 USB 调试

set -e

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 工具函数
log() { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err() { echo -e "${RED}[ERR ]${NC} $1"; }
info() { echo -e "${BLUE}[INFO]${NC} $1"; }

# 检查依赖
check_deps() {
    log "检查依赖..."
    if ! command -v java &> /dev/null; then
        err "未找到 java，请安装 JDK 17+"
        exit 1
    fi
    java_version=$(java -version 2>&1 | head -n1 | cut -d'"' -f2)
    info "Java: $java_version"

    if ! command -v adb &> /dev/null; then
        warn "未找到 adb，仅能编译不能安装"
    fi

    if [ -z "$ANDROID_HOME" ]; then
        warn "ANDROID_HOME 未设置，可能影响编译"
    else
        info "ANDROID_HOME: $ANDROID_HOME"
    fi
}

# 编译并安装
do_install() {
    log "编译 + 安装到手机..."
    ./gradlew :app:assembleDebug
    if command -v adb &> /dev/null; then
        adb devices
        ./gradlew :app:installDebug
        log "✅ 安装完成"
        info "打开 App: adb shell am start -n com.octopus.mobile/.ui.MainActivity"
    else
        warn "无 adb，跳过安装"
        log "✅ 编译完成: app/build/outputs/apk/debug/app-debug.apk"
    fi
}

# 跑测试
do_test() {
    log "跑单元测试..."
    ./gradlew :app:testDebugUnitTest
    log "✅ 测试完成"
    info "测试报告: app/build/reports/tests/testDebugUnitTest/index.html"
}

# 跑 demo
do_demo() {
    log "跑 hello world demo..."
    if command -v python3 &> /dev/null; then
        python3 examples/tentacle_hello_world_demo.py
    elif command -v python &> /dev/null; then
        python examples/tentacle_hello_world_demo.py
    else
        err "未找到 python"
        exit 1
    fi
}

# 主入口
case "${1:-all}" in
    install)
        check_deps
        do_install
        ;;
    test)
        check_deps
        do_test
        ;;
    demo)
        do_demo
        ;;
    all)
        check_deps
        do_install
        do_test
        do_demo
        ;;
    *)
        echo "用法: $0 {install|test|demo|all}"
        exit 1
        ;;
esac

log "🎉 Done"
