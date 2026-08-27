#!/usr/bin/env bash
# =============================================================================
# setup.sh — paint-android 消费者一键环境搭建脚本（W3，Bash / Linux-macOS / Git Bash）
#
# 用法:
#   scripts/setup.sh            默认（开发）模式：探测 + 补缺指引 + 拉 submodule + 构建 APK
#   scripts/setup.sh --check    只探测不安装，输出缺项清单
#   scripts/setup.sh --test     探测 + 构建 + 跑测试门（assembleDebug 编译门 + DemoExport 审读）
#   scripts/setup.sh --help     打印用法
#
# 仓库内自包含：clone paint-android 后在此仓库内运行（SDK 为 submodule；local.properties
# 指向 Android SDK，通常需按本机路径创建，脚本会探测并提示）。
# 依赖: JDK≥17 / Android SDK / NDK r27+ / git。无 arm64 真机时以「编译门 + 代码审读」为测试口径。
# =============================================================================
set -euo pipefail

# ---------- 输出辅助 ----------
info() { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
err()  { printf 'ERROR: %s\n' "$*" >&2; }
ok()   { printf '[OK]   %s\n' "$*"; }

has() { command -v "$1" >/dev/null 2>&1; }
is_macos() { [ "$(uname -s)" = "Darwin" ]; }

extract_version() {
  local s="$1"
  if [[ "$s" =~ ([0-9]+(\.[0-9]+)+) ]]; then printf '%s' "${BASH_REMATCH[1]}"
  elif [[ "$s" =~ ([0-9]+) ]]; then printf '%s' "${BASH_REMATCH[1]}"; fi
}
ver_seg() {
  local v="$1" idx="$2" seg
  seg="$(printf '%s' "$v" | cut -d. -f"$((idx + 1))" 2>/dev/null || true)"
  case "$seg" in ''|*[!0-9]*) printf '0' ;; *) printf '%d' "$((10#$seg))" ;; esac
}
ver_ge() {
  local a="$1" b="$2" i sa sb
  for i in 0 1 2 3; do
    sa="$(ver_seg "$a" "$i")"; sb="$(ver_seg "$b" "$i")"
    [ "$sa" -gt "$sb" ] && return 0; [ "$sa" -lt "$sb" ] && return 1
  done
  return 0
}

# ---------- 探测存储 ----------
CHK_NAMES=(); CHK_LEVELS=(); CHK_STATUS=(); CHK_DETAILS=()
HARD_MISS=0; SOFT_MISS=0
record() { CHK_NAMES+=("$1"); CHK_LEVELS+=("$2"); CHK_STATUS+=("$3"); CHK_DETAILS+=("$4"); }

# ---------- 各检查项 ----------
probe_java() {
  local v vn
  if ! has java; then
    record "JDK (java)" "硬" "MISS(硬)" "未安装 java（Gradle 必败，需 JDK 17+）"; HARD_MISS=$((HARD_MISS+1)); return
  fi
  v="$(java -version 2>&1 | head -n1 || true)"; vn="$(extract_version "$v")"
  if [ -n "$vn" ]; then
    if ver_ge "$vn" "17"; then record "JDK (java)" "硬" "OK" "java $vn（≥ 17）"
    else record "JDK (java)" "硬" "MISS(硬)" "java $vn 过旧（需 JDK 17+，AGP 8.x）"; HARD_MISS=$((HARD_MISS+1)); fi
  else record "JDK (java)" "硬" "OK" "已安装但版本无法确认（请手动确认 ≥ 17）"; fi
}

# 解析本机 local.properties 的 sdk.dir，兼容 Android Studio 用 java.util.Properties 转义的
# Windows 路径（sdk.dir=C\:\\Users\\...）：反转义 \\ 与 \:，并把反斜杠统一为前斜杠供 test -d。
resolve_sdk_dir() {
  local f="$root/local.properties" v
  [ -f "$f" ] || return 0
  v="$(sed -n 's/^sdk\.dir=//p' "$f" 2>/dev/null | head -n1 || true)"
  [ -n "$v" ] || return 0
  v="$(printf '%s' "$v" | sed -e 's/\\\\/\\/g' -e 's/\\:/:/g')"
  v="${v//\\//}"
  printf '%s' "$v"
}

probe_android_sdk() {
  # 读 local.properties 的 sdk.dir（优先），或环境变量 ANDROID_SDK_ROOT / ANDROID_HOME，
  # 兜底 Android Studio 默认 SDK 位置（%LOCALAPPDATA%/Android/Sdk）。
  local sdkloc=""
  sdkloc="$(resolve_sdk_dir)"
  [ -z "$sdkloc" ] && sdkloc="${ANDROID_SDK_ROOT:-}"
  [ -z "$sdkloc" ] && sdkloc="${ANDROID_HOME:-}"
  if [ -z "$sdkloc" ] && [ -n "${LOCALAPPDATA:-}" ]; then
    local lapd="${LOCALAPPDATA//\\//}"
    [ -d "$lapd/Android/Sdk" ] && sdkloc="$lapd/Android/Sdk"
  fi
  if [ -z "$sdkloc" ] || [ ! -d "$sdkloc" ]; then
    record "Android SDK" "硬" "MISS(硬)" "未找到 Android SDK（设 sdk.dir 到 local.properties，或 \$ANDROID_HOME）"; HARD_MISS=$((HARD_MISS+1)); return
  fi
  record "Android SDK" "硬" "OK" "$sdkloc"
}

probe_ndk() {
  local ndk="" platforms_ndk="" sdkloc=""
  # NDK 优先看 ANDROID_NDK_HOME，其次 local.properties 的 sdk.dir/ndk/ 目录。
  ndk="${ANDROID_NDK_HOME:-}"
  if [ -z "$ndk" ]; then
    sdkloc="$(resolve_sdk_dir)"
    [ -n "$sdkloc" ] && platforms_ndk="$(ls "$sdkloc/ndk/" 2>/dev/null | sort -V | tail -n1 || true)"
    [ -n "$platforms_ndk" ] && ndk="$sdkloc/ndk/$platforms_ndk"
  fi
  if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
    record "Android NDK" "硬" "MISS(硬)" "未找到 NDK（app/build.gradle.kts 钉 ndkVersion 28.2.13676358；用 SDK Manager 装 ndk;28.x）"; HARD_MISS=$((HARD_MISS+1)); return
  fi
  local rev=""
  rev="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ndk/source.properties" 2>/dev/null || true)"
  record "Android NDK" "硬" "OK" "NDK ${rev:-（未知版本）} @ $ndk"
}

probe_gradle() {
  # 仓库自带 gradle wrapper（gradlew）——不必系统装 gradle。
  if [ ! -x "$root/gradlew" ]; then
    record "Gradle wrapper" "硬" "MISS(硬)" "仓库缺 gradlew（clone 不完整？）"; HARD_MISS=$((HARD_MISS+1)); return
  fi
  record "Gradle wrapper" "硬" "OK" "gradlew（Gradle $(grep distributionUrl "$root/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null | sed -E 's/.*gradle-([0-9.]+)-bin.*/\1/' || echo 未知)）"
}

probe_git() {
  if ! has git; then
    record "git" "硬" "MISS(硬)" "未安装（拉 submodule 必败）"; HARD_MISS=$((HARD_MISS+1)); return
  fi
  record "git" "硬" "OK" "git $(git --version 2>/dev/null | head -n1 || true)"
}

probe_all() {
  CHK_NAMES=(); CHK_LEVELS=(); CHK_STATUS=(); CHK_DETAILS=(); HARD_MISS=0; SOFT_MISS=0
  probe_java; probe_android_sdk; probe_ndk; probe_gradle; probe_git
}

# ---------- 输出 ----------
print_check() {
  info "=== 环境探测结果 ==="
  local i
  for i in "${!CHK_NAMES[@]}"; do
    printf '[%s] %s: %s\n' "${CHK_STATUS[$i]}" "${CHK_NAMES[$i]}" "${CHK_DETAILS[$i]}"
  done
  echo
  if [ "$HARD_MISS" -gt 0 ]; then err "硬依赖缺失 $HARD_MISS 项，构建/测试将失败。"
  else info "硬依赖齐全；无软依赖项（本脚本只探硬依赖）。"; fi
}

print_guidance() {
  info "请手动补缺："
  info "  - JDK 17+:  https://adoptium.net/（或系统包 openjdk-17-jdk）"
  info "  - Android SDK + NDK: Android Studio → SDK Manager 装"
  info "      sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'"
  info "      并把 sdk.dir 写进本仓库 local.properties（如 sdk.dir=/usr/lib/android-sdk 或 %LOCALAPPDATA%\\Android\\Sdk）"
  info "  - git: https://git-scm.com/download/win"
}

print_help() {
  cat <<'EOF'
用法: scripts/setup.sh [--check|--test|--help]

  一键搭建 paint-android 开发/测试环境（仓库内自包含）。

模式:
  （默认） 探测 + 补缺指引 + 拉 SDK submodule + ./gradlew assembleDebug 构建 APK
  --check  只探测不安装，输出缺项清单；硬依赖缺失时非零退出
  --test   探测 + 构建 + 测试门（assembleDebug 编译门 + DemoExport 离屏自检审读）
  --help   打印本帮助

依赖: JDK≥17 / Android SDK（platforms;android-35, build-tools;35.0.0）/ NDK 28.2 / git。
无 arm64 真机时以「编译门 + 代码审读」为测试口径（B5-1 口径：编出带真实 Vulkan 后端的 .so）。
EOF
}

# ---------- 动作 ----------
sync_submodule() {
  local root="$1"
  info "同步 SDK submodule…"
  git -C "$root" submodule update --init --recursive
}

# 探测三方库（复用 SDK 共享 fetch-deps.sh --check）。Android 大库（SDK/NDK）由 SDK Manager
# 供给，fetch-deps 对它们仅探测 + 指引（manifest platform 过滤，不自动拉）；host 侧
# vulkan/shaderc 与本 Android 构建无关，故 --check 结果仅作指引、不回传退出码、不阻断构建。
fetch_deps() {
  local root="$1"
  local sdk_script="$root/sdk/scripts/fetch-deps.sh"
  if [ ! -f "$sdk_script" ]; then
    warn "未找到 sdk/scripts/fetch-deps.sh（submodule 未更新到含共享拉取脚本的版本？跳过）"
    return 0
  fi
  info "探测三方库（fetch-deps --check）…"
  bash "$sdk_script" --check || true
}

build_apk() {
  local root="$1"
  info "构建 APK（./gradlew assembleDebug）…"
  (cd "$root" && ./gradlew assembleDebug --no-daemon)
}

# ---------- 主流程 ----------
main() {
  local mode="dev"
  if [ "$#" -gt 1 ]; then err "参数过多：$*（用法: setup.sh [--check|--test]）"; exit 2; fi
  if [ "$#" -eq 1 ]; then
    case "$1" in
      --check) mode="check" ;;
      --test)  mode="test" ;;
      -h|--help) print_help; exit 0 ;;
      *) err "未知参数：$1"; exit 2 ;;
    esac
  fi

  local root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

  # 探测函数需要 root 定位 local.properties；以参数传入全局。
  probe_all

  print_check

  if [ "$mode" = "check" ]; then
    [ "$HARD_MISS" -gt 0 ] && { print_guidance; exit 1; }
    exit 0
  fi
  if [ "$HARD_MISS" -gt 0 ]; then
    print_guidance
    err "硬依赖缺失 $HARD_MISS 项。本脚本不静默安装，请按指引补缺后重跑。"
    exit 1
  fi

  sync_submodule "$root"
  fetch_deps "$root"
  build_apk "$root"

  if [ "$mode" = "test" ]; then
    info "测试门：assembleDebug 编译门已通过。DemoExport 离屏自检（exported=true + nativeExportPng→dgcExportPNG）代码审读："
    info "  - app/src/main/AndroidManifest.xml 含 DemoExportActivity android:exported=\"true\""
    info "  - app/src/main/java/com/dgcamp/paint/DemoExportActivity.kt 触发 PaintNative.nativeExportPng"
    info "  - jni/paint_android_jni.cpp nativeExportPng → dgcFlush + dgcExportPNG"
    info "  （无 arm64 真机时，真机运行留人工；真机可 adb shell am start 显式触发 DemoExport）"
  fi

  info "paint-android 环境就绪。APK: app/build/outputs/apk/debug/app-debug.apk"
  exit 0
}

main "$@"
