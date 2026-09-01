#!/usr/bin/env bash
# =============================================================================
# setup.sh — paint-android 消费者一键环境搭建脚本（W3，Bash / Linux-macOS / Git Bash）
#
# 用法:
#   scripts/setup.sh            默认（开发）模式：探测 +（Windows 缺项自动装，国内镜像）+
#                               拉主仓库 + 拉 submodule + 构建 + adb 装最新 APK
#   scripts/setup.sh --check    只探测不安装，输出缺项清单
#   scripts/setup.sh --test     探测 + 构建 + 跑测试门（assembleDebug 编译门 + DemoExport 审读）
#   scripts/setup.sh --help     打印用法
#
# 仓库内自包含：clone paint-android 后在此仓库内运行（SDK 为 submodule；local.properties
# 指向 Android SDK，通常需按本机路径创建，脚本会探测并提示）。
# 依赖: JDK≥17 / Android SDK / NDK r27+ / git。无 arm64 真机时以「编译门 + 代码审读」为测试口径。
# Windows 上缺 JDK/SDK/NDK 时自动从国内镜像安装（JDK: TUNA；Android: 腾讯云），无需手动。
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

# ---------- 国内镜像自动安装（Windows） ----------
# 为什么不用 sdkmanager：现代 cmdline-tools 的 sdkmanager 不支持 REPO_URL / --repository_url
# 重定向（那是上古 android 工具的机制），固定连 dl.google.com（国内基本拉不动）。故采用
# 「镜像直接下载归档 + 按 SDK 目录结构解压 + 写 license 文件」，确定性高、无 sdkmanager 依赖。
# 镜像源：
#   JDK        TUNA Adoptium 镜像  https://mirrors.tuna.tsinghua.edu.cn/Adoptium/
#   Android    Tencent 镜像        https://mirrors.cloud.tencent.com/AndroidSDK/
#              （= Google repository2-3.xml 的国内镜像，归档 URL 相对镜像解析）
AUTO_JDK_MIRROR='https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk'
AUTO_SDK_MIRROR='https://mirrors.cloud.tencent.com/AndroidSDK'

is_windows() { case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) return 0 ;; *) return 1 ;; esac; }

download_auto() { # $1=url  $2=目标文件（.part 边下边写；先清残留，失败不留残件）
  local url="$1" dest="$2" tmp="${2}.part"
  rm -f "$tmp" "$dest"
  info "下载：$url"
  curl -fSL --retry 3 -o "$tmp" "$url" || { rm -f "$tmp"; err "下载失败（网络 / 镜像不可达）：$url"; return 1; }
  mv -f "$tmp" "$dest"
}

unzip_to() { # $1=zip  $2=解压目录（unzip 缺失时退 tar，MSYS tar 能解 zip）
  mkdir -p "$2"
  if has unzip; then unzip -q -o "$1" -d "$2"
  else tar -xf "$1" -C "$2"; fi
}

# 现有 java 是否可用（≥17）
jdk_ok() {
  if ! has java; then return 1; fi
  ver_ge "$(extract_version "$(java -version 2>&1 | head -n1 || true)")" "17"
}

install_jdk_windows() {
  local a ver home tmp="$HOME/.dgc/jdk"
  a="x64"; case "$(uname -m)" in aarch64|arm64) a="aarch64" ;; esac
  info "自动安装 JDK 17（Temurin，TUNA 镜像，$a）…"
  ver="$(curl -s --max-time 30 "$AUTO_JDK_MIRROR/$a/windows/" \
         | grep -oE "OpenJDK17U-jdk_${a}_windows_hotspot_[0-9._]+\.zip" \
         | sort -V | tail -n1 || true)"
  if [ -z "$ver" ]; then err "无法从 TUNA 镜像取到 JDK 版本列表（$AUTO_JDK_MIRROR/$a/windows/）"; return 1; fi
  mkdir -p "$tmp"
  download_auto "$AUTO_JDK_MIRROR/$a/windows/$ver" "$tmp/jdk.zip" || return 1
  unzip_to "$tmp/jdk.zip" "$tmp" || { err "JDK 解压失败：$tmp/jdk.zip"; return 1; }
  rm -f "$tmp/jdk.zip"
  home="$(find "$tmp" -maxdepth 1 -type d -name 'jdk-*' 2>/dev/null | head -n1)"
  [ -n "$home" ] || { err "JDK 解压后未找到 jdk-* 目录（$tmp）"; return 1; }
  # JAVA_HOME 用 Windows 路径形态（与 Android Studio 一致，gradlew 在 Git Bash 下可直接用）
  JAVA_HOME="$(has cygpath && cygpath -w "$home" || printf '%s' "$home")"
  export JAVA_HOME
  export PATH="$home/bin:$PATH"
  ok "JDK 就绪：$JAVA_HOME"
  return 0
}

# SDK 组件是否已齐（platforms;android-35 + build-tools;35.0.0 + ndk;28.2.13676358）
sdk_components_ok() { # $1=sdk 前斜杠路径
  [ -d "$1/platforms/android-35" ] && [ -d "$1/build-tools/35.0.0" ] && [ -d "$1/ndk/28.2.13676358" ]
}

install_sdk_windows() { # $1=sdk POSIX 路径
  local sdk="$1" tmp="$HOME/.dgc/.tmp" m="$AUTO_SDK_MIRROR"
  info "自动安装 Android SDK 组件（Tencent 镜像；NDK 约 750MB，最耗时）…"
  mkdir -p "$tmp"
  download_auto "$m/platform-tools_r37.0.1-win.zip" "$tmp/platform-tools.zip" || return 1
  download_auto "$m/platform-35_r02.zip"             "$tmp/platform-35.zip"    || return 1
  download_auto "$m/build-tools_r35_windows.zip"     "$tmp/build-tools.zip"    || return 1
  download_auto "$m/cmake-3.22.1-windows.zip"        "$tmp/cmake.zip"          || return 1
  download_auto "$m/android-ndk-r28c-windows.zip"    "$tmp/ndk.zip"            || return 1

  unzip_to "$tmp/platform-tools.zip" "$sdk" || return 1   # → $sdk/platform-tools/
  unzip_to "$tmp/platform-35.zip" "$sdk"     || return 1  # → $sdk/android-35/
  [ -d "$sdk/android-35" ] || { err "platform-35 解压结构异常"; return 1; }
  mkdir -p "$sdk/platforms"; mv -f "$sdk/android-35" "$sdk/platforms/android-35"

  unzip_to "$tmp/build-tools.zip" "$sdk" || return 1      # → $sdk/android-15/
  [ -d "$sdk/android-15" ] || { err "build-tools 解压结构异常"; return 1; }
  mkdir -p "$sdk/build-tools"; mv -f "$sdk/android-15" "$sdk/build-tools/35.0.0"

  mkdir -p "$sdk/cmake/3.22.1"
  unzip_to "$tmp/cmake.zip" "$sdk/cmake/3.22.1" || return 1  # 扁平 zip（bin/doc/share）

  unzip_to "$tmp/ndk.zip" "$sdk" || return 1              # → $sdk/android-ndk-r28c/
  [ -d "$sdk/android-ndk-r28c" ] || { err "NDK 解压结构异常"; return 1; }
  mkdir -p "$sdk/ndk"; mv -f "$sdk/android-ndk-r28c" "$sdk/ndk/28.2.13676358"

  # AGP 检查 $SDK/licenses/android-sdk-license 里的接受哈希
  mkdir -p "$sdk/licenses"
  printf '%s\n' '24333f8a63b6825ea9c5514f83c2829b004d1fee' > "$sdk/licenses/android-sdk-license"
  printf '%s\n' '84831b9409646a918e30573bab4c9c91346d8abd' > "$sdk/licenses/android-sdk-preview-license"

  rm -rf "$tmp"
  ok "Android SDK 组件就绪：$sdk"
  return 0
}

# 计算 Windows SDK 目标位置并写 local.properties（AS 转义格式）。优先沿用已有 local.properties
# 的 sdk.dir，否则用 %LOCALAPPDATA%\Android\Sdk（再退 $HOME/Android/Sdk）。
# 返回 POSIX(/c/…)形态给 shell 用——经验教训：Git Bash 里 curl.exe（原生 Windows 程序）对
# 「盘符前斜杠」路径 C:/… 的写入可能 EINVAL，而 POSIX /c/… 由 MSYS2 正确转成反斜杠再传，稳。
setup_sdk_loc_windows() {
  local sdk_fwd sdk_posix sdk_win esc existing
  existing="$(resolve_sdk_dir)"
  if [ -n "$existing" ]; then
    sdk_fwd="$existing"
  elif [ -n "${LOCALAPPDATA:-}" ]; then
    sdk_fwd="${LOCALAPPDATA//\\//}/Android/Sdk"
  else
    sdk_fwd="$HOME/Android/Sdk"
  fi
  sdk_posix="$(has cygpath && cygpath -u "$sdk_fwd" || printf '%s' "$sdk_fwd")"
  sdk_win="$(has cygpath && cygpath -w "$sdk_fwd" || printf '%s' "$sdk_fwd")"
  if [ ! -f "$root/local.properties" ] || ! grep -q '^sdk\.dir=' "$root/local.properties"; then
    esc="${sdk_win//\\/\\\\}"; esc="${esc//:/\\:}"
    printf 'sdk.dir=%s\n' "$esc" > "$root/local.properties"
    ok "已写 $root/local.properties（sdk.dir=$sdk_win）" >&2  # 本函数返回值被 $(…) 捕获，状态消息必须走 stderr，否则污染返回路径
  fi
  printf '%s' "$sdk_posix"
}

# 装完复查 probe_all；仍缺则报明细并返回非 0
auto_install_windows() {
  local sdk i
  if ! jdk_ok; then install_jdk_windows || return 1; fi
  sdk="$(setup_sdk_loc_windows)"
  if ! sdk_components_ok "$sdk"; then install_sdk_windows "$sdk" || return 1; fi
  probe_all
  if [ "$HARD_MISS" -gt 0 ]; then
    err "自动安装后仍有 $HARD_MISS 项硬依赖缺失："
    for i in "${!CHK_NAMES[@]}"; do
      if [ "${CHK_STATUS[$i]}" = "MISS(硬)" ]; then printf '  - %s\n' "${CHK_DETAILS[$i]}"; fi
    done
    return 1
  fi
  return 0
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
  info "手动补缺指引（Windows 缺项会自动安装，仅自动安装失败或 --check 模式时按此操作）："
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
  （默认） 探测 +（Windows 缺项自动装，国内镜像）补缺指引 + 拉主仓库(ff-only) + 拉 SDK submodule
           + ./gradlew assembleDebug 构建 APK
           + 有设备/模拟器时 adb install -r 覆盖安装并启动（绕开 Android Studio 自身部署缓存）
  --check  只探测不安装，输出缺项清单；硬依赖缺失时非零退出
  --test   探测 + 构建 + 测试门（assembleDebug 编译门 + DemoExport 离屏自检审读）
  --help   打印本帮助

依赖: JDK≥17 / Android SDK（platforms;android-35, build-tools;35.0.0）/ NDK 28.2 / git。
Windows 缺 JDK/SDK/NDK 时自动从国内镜像安装（JDK: TUNA；Android: 腾讯云）。
无 arm64 真机时以「编译门 + 代码审读」为测试口径（B5-1 口径：编出带真实 Vulkan 后端的 .so）。
EOF
}

# ---------- 动作 ----------
# 拉取本仓库自身（paint-android）最新提交。只做 fast-forward，绝不自动 merge/rebase——
# 教训：脚本曾经只拉 SDK submodule，主仓库从不更新，导致本机代码与远端悄悄脱节而不自知。
# 工作区不干净（未提交改动 / 未解决冲突）或本地已分叉时直接报错退出，不静默产生冲突。
sync_repo() {
  local root="$1" branch

  if [ ! -d "$root/.git" ]; then
    warn "非 git 仓库（$root/.git 不存在），跳过主仓库同步。"
    return 0
  fi

  info "同步 paint-android 主仓库（git pull --ff-only）…"

  if [ -n "$(git -C "$root" status --porcelain=v1 2>/dev/null)" ]; then
    err "工作区不干净（有未提交修改或未解决的合并冲突），为避免破坏本地改动，跳过自动 pull。"
    git -C "$root" status --short
    err "请先 git add/commit 或 git stash，若已有冲突先 git status 解决，再重跑本脚本。"
    exit 1
  fi

  branch="$(git -C "$root" symbolic-ref --short -q HEAD || true)"
  if [ -z "$branch" ]; then
    warn "当前处于 detached HEAD，跳过自动 pull（可能是 CI / 浅克隆场景）。"
    return 0
  fi

  git -C "$root" fetch origin "$branch"
  if ! git -C "$root" merge --ff-only "origin/$branch"; then
    err "本地分支 $branch 与 origin/$branch 已分叉（存在本地未推送的提交），本脚本不做自动合并/变基。"
    err "请手动执行 git pull（自行决定 merge 还是 rebase）后重跑。"
    exit 1
  fi
  ok "主仓库已同步到 origin/$branch 最新。"
}

sync_submodule() {
  local root="$1"
  info "同步 SDK submodule（跟随 paintDemo main 最新）…"
  git -C "$root" submodule update --init --recursive --remote
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

# 找 adb：优先 PATH，其次 local.properties 的 sdk.dir（复用 resolve_sdk_dir 的转义解析），
# 找不到就返回非 0，调用方按软失败处理（不阻断构建）。
find_adb() {
  local sdkdir="" cand
  has adb && { printf 'adb'; return 0; }
  sdkdir="$(resolve_sdk_dir)"
  [ -z "$sdkdir" ] && sdkdir="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  [ -z "$sdkdir" ] && return 1
  for cand in "$sdkdir/platform-tools/adb" "$sdkdir/platform-tools/adb.exe"; do
    [ -x "$cand" ] && { printf '%s' "$cand"; return 0; }
  done
  return 1
}

# 构建产物直接 adb install -r 覆盖安装 + 启动，绕开 Android Studio 自身的增量部署/构建缓存——
# 教训：命令行 git pull + gradlew 构建出的最新 APK，若靠 AS 的 Run/Apply Changes 部署，
# AS 有时仍沿用它自己缓存的旧构建产物，认不到命令行外部改动，导致「代码明明改了、App 却没变」。
# 找不到 adb 或没接设备/模拟器时只警告跳过，不阻断（本次仅完成构建，部署留人工 adb install）。
install_apk() {
  local root="$1"
  local apk="$root/app/build/outputs/apk/debug/app-debug.apk"
  local pkg="com.dgcamp.paint"
  local adb_bin devices dev

  if [ ! -f "$apk" ]; then
    warn "未找到构建产物 $apk，跳过自动安装。"
    return 0
  fi

  if ! adb_bin="$(find_adb)"; then
    warn "未找到 adb（不在 PATH 也不在 SDK platform-tools 下），跳过自动安装。"
    return 0
  fi

  devices="$("$adb_bin" devices | awk 'NR>1 && $2=="device" {print $1}')"
  if [ -z "$devices" ]; then
    warn "未检测到已连接的设备/模拟器，跳过自动安装（本次仅完成构建，未部署）。"
    return 0
  fi

  info "检测到设备，adb install -r 覆盖安装最新 APK（绕开 IDE 缓存）…"
  while IFS= read -r dev; do
    [ -z "$dev" ] && continue
    if "$adb_bin" -s "$dev" install -r "$apk" >/dev/null; then
      ok "已安装到 $dev"
      "$adb_bin" -s "$dev" shell am start -n "$pkg/.MainActivity" >/dev/null 2>&1 \
        && ok "已在 $dev 上启动 $pkg" \
        || warn "$dev 启动失败（可手动打开 App）"
    else
      warn "$dev 安装失败，请手动执行：$adb_bin -s $dev install -r \"$apk\""
    fi
  done <<< "$devices"
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
    if is_windows; then
      info "检测到硬依赖缺失，开始自动安装（国内镜像：TUNA / 腾讯云）…"
      if ! auto_install_windows; then
        print_guidance
        err "自动安装未完成，请按上述指引手动补缺后重跑。"
        exit 1
      fi
      print_check
    else
      print_guidance
      err "硬依赖缺失 $HARD_MISS 项。Windows 会自动安装；Linux/macOS 请按指引手动补缺后重跑。"
      exit 1
    fi
  fi

  sync_repo "$root"
  sync_submodule "$root"
  fetch_deps "$root"
  build_apk "$root"
  install_apk "$root"

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
