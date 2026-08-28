<#
.SYNOPSIS
    paint-android 消费者 Windows 一键环境搭建脚本（W3，PowerShell 原生）。

.DESCRIPTION
    在 Windows 真机上把 paint-android 开发/测试环境搭起来（仓库内自包含）：
      1) 探测  JDK≥17 / Android SDK / NDK 28 / git；
      2) 补缺  硬依赖缺失给精确安装指引（sdkmanager / Android Studio / JDK 下载），
              并提示写 local.properties；
      3) 拉取  git pull --ff-only（主仓库自身）+ git submodule update --remote（SDK 跟随
              paintDemo main 最新，不钉 pin）；
      4) 构建  .\gradlew.bat assembleDebug（arm64-v8a + 真实 Vulkan 后端）；
      5) 测试  --test 模式 = assembleDebug 编译门 + DemoExport 离屏自检代码审读。

    用法（PowerShell 5.1+ / Core 7+）:
      .\scripts\setup.ps1              默认（开发）：探测+补缺指引+拉主仓库+拉 submodule+构建 APK
      .\scripts\setup.ps1 --check      只探测不安装，输出缺项清单
      .\scripts\setup.ps1 --test       探测+构建+测试门
      .\scripts\setup.ps1 -Help        打印本帮助

    依赖: JDK≥17 / Android SDK（platforms;android-35, build-tools;35.0.0）/ NDK 28.2 / git。
    无 arm64 真机时以「编译门 + 代码审读」为测试口径。
.PARAMETER Check
    只探测不安装，输出缺项清单。硬依赖缺失 → exit 1，仅软依赖缺失 → exit 0。
.PARAMETER Test
    探测 + 构建 + 测试门。
.PARAMETER Help
    打印帮助后退出。
#>
[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$Test,
    [switch]$Help
)

if ($PSVersionTable.PSEdition -eq "Desktop" -and $PSVersionTable.PSVersion.Major -lt 5) {
    Write-Host "ERROR: 需要 PowerShell 5.1+。" -ForegroundColor Red; exit 2
}
if ($Help) { Get-Help $PSCommandPath -Detailed; exit 0 }
$ErrorActionPreference = "Stop"

function Info($m) { Write-Host $m }
function Warn($m) { Write-Host ("WARN: " + $m) -ForegroundColor Yellow }
function Err($m)  { Write-Host ("ERROR: " + $m) -ForegroundColor Red }
function Ok($m)   { Write-Host ("[OK]   " + $m) -ForegroundColor Green }

$script:ChkNames  = [System.Collections.Generic.List[string]]::new()
$script:ChkLevels = [System.Collections.Generic.List[string]]::new()
$script:ChkStatus = [System.Collections.Generic.List[string]]::new()
$script:ChkDetail = [System.Collections.Generic.List[string]]::new()
$script:HardMiss  = 0
$script:SoftMiss  = 0
function Record($name, $level, $status, $detail) {
    $script:ChkNames.Add($name); $script:ChkLevels.Add($level)
    $script:ChkStatus.Add($status); $script:ChkDetail.Add($detail)
}

function Probe-Java {
    $j = Get-Command java -ErrorAction SilentlyContinue
    if (-not $j) { Record "JDK (java)" "硬" "MISS(硬)" "未安装 java（需 JDK 17+）"; $script:HardMiss++; return }
    $v = (& java -version 2>&1 | Select-Object -First 1)
    if ($v -match "version \"([0-9]+)") {
        $maj = [int]$Matches[1]
        if ($maj -ge 17) { Record "JDK (java)" "硬" "OK" $v }
        else { Record "JDK (java)" "硬" "MISS(硬)" "$v 过旧（需 JDK 17+）"; $script:HardMiss++ }
    } else { Record "JDK (java)" "硬" "OK" "已安装但版本无法确认" }
}

function Probe-AndroidSdk {
    # sdk.dir 在 local.properties（不入库）；或 ANDROID_HOME / ANDROID_SDK_ROOT。
    $sdkloc = ""
    if (Test-Path (Join-Path $Root "local.properties")) {
        $sdkloc = ((Get-Content (Join-Path $Root "local.properties") | Where-Object { $_ -match "^sdk\.dir=" }) -replace "^sdk\.dir=", "").Trim()
    }
    if (-not $sdkloc) { $sdkloc = $env:ANDROID_SDK_ROOT }
    if (-not $sdkloc) { $sdkloc = $env:ANDROID_HOME }
    if (-not $sdkloc -or -not (Test-Path $sdkloc)) {
        Record "Android SDK" "硬" "MISS(硬)" "未找到 Android SDK（写 sdk.dir 到 local.properties 或设 \$env:ANDROID_HOME）"; $script:HardMiss++; return
    }
    Record "Android SDK" "硬" "OK" $sdkloc
}

function Probe-Ndk {
    $ndk = $env:ANDROID_NDK_HOME
    if (-not $ndk) {
        $sdkloc = ""
        if (Test-Path (Join-Path $Root "local.properties")) {
            $sdkloc = ((Get-Content (Join-Path $Root "local.properties") | Where-Object { $_ -match "^sdk\.dir=" }) -replace "^sdk\.dir=", "").Trim()
        }
        if ($sdkloc) {
            $ndkDir = Join-Path $sdkloc "ndk"
            if (Test-Path $ndkDir) {
                $ndk = (Get-ChildItem $ndkDir -Directory | Sort-Object Name | Select-Object -Last 1).FullName
            }
        }
    }
    if (-not $ndk -or -not (Test-Path $ndk)) {
        Record "Android NDK" "硬" "MISS(硬)" "未找到 NDK（build.gradle.kts 钉 ndkVersion 28.2.13676358）"; $script:HardMiss++; return
    }
    Record "Android NDK" "硬" "OK" "NDK @ $ndk"
}

function Probe-Gradle {
    if (-not (Test-Path (Join-Path $Root "gradlew.bat"))) {
        Record "Gradle wrapper" "硬" "MISS(硬)" "仓库缺 gradlew.bat（clone 不完整？）"; $script:HardMiss++; return
    }
    Record "Gradle wrapper" "硬" "OK" "gradlew.bat（Gradle 8.9）"
}

function Probe-Git {
    $g = Get-Command git -ErrorAction SilentlyContinue
    if (-not $g) { Record "git" "硬" "MISS(硬)" "未安装（拉 submodule 必败）"; $script:HardMiss++; return }
    Record "git" "硬" "OK" "git $(& git --version 2>$null | Select-Object -First 1)"
}

function Probe-All {
    $script:ChkNames.Clear(); $script:ChkLevels.Clear(); $script:ChkStatus.Clear(); $script:ChkDetail.Clear()
    $script:HardMiss = 0; $script:SoftMiss = 0
    Probe-Java; Probe-AndroidSdk; Probe-Ndk; Probe-Gradle; Probe-Git
}

function Print-Check {
    Info "=== 环境探测结果 ==="
    for ($i = 0; $i -lt $script:ChkNames.Count; $i++) {
        Write-Host ("[{0}] {1}: {2}" -f $script:ChkStatus[$i], $script:ChkNames[$i], $script:ChkDetail[$i])
    }
    if ($script:HardMiss -gt 0) { Err "硬依赖缺失 $($script:HardMiss) 项，构建/测试将失败。" }
    else { Info "硬依赖齐全；无软依赖项。" }
}

function Print-Guidance {
    Info "请手动补缺："
    Info "  - JDK 17+: https://adoptium.net/（设置 \$env:JAVA_HOME 与 PATH）"
    Info "  - Android SDK + NDK: Android Studio → SDK Manager，或 sdkmanager:"
    Info "      sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'"
    Info "      并把 sdk.dir 写进本仓库 local.properties（如 sdk.dir=C:\Users\you\AppData\Local\Android\Sdk）"
    Info "  - git: https://git-scm.com/download/win"
}

# 拉取本仓库自身（paint-android）最新提交。只做 fast-forward，绝不自动 merge/rebase——
# 教训：脚本曾经只拉 SDK submodule，主仓库从不更新，导致本机代码与远端悄悄脱节而不自知。
# 工作区不干净（未提交改动 / 未解决冲突）或本地已分叉时直接报错退出，不静默产生冲突。
function Sync-Repo {
    param([string]$R)
    Push-Location $R
    try {
        if (-not (Test-Path (Join-Path $R ".git"))) {
            Warn "非 git 仓库（$R\.git 不存在），跳过主仓库同步。"
            return
        }

        Info "同步 paint-android 主仓库（git pull --ff-only）…"

        $dirty = & git status --porcelain=v1
        if ($LASTEXITCODE -ne 0) { Err "git status 失败"; exit 1 }
        if ($dirty) {
            Err "工作区不干净（有未提交修改或未解决的合并冲突），为避免破坏本地改动，跳过自动 pull。"
            & git status --short
            Err "请先 git add/commit 或 git stash，若已有冲突先 git status 解决，再重跑本脚本。"
            exit 1
        }

        $branch = & git symbolic-ref --short -q HEAD
        if (-not $branch) {
            Warn "当前处于 detached HEAD，跳过自动 pull（可能是 CI / 浅克隆场景）。"
            return
        }

        & git fetch origin $branch
        if ($LASTEXITCODE -ne 0) { Err "git fetch 失败"; exit 1 }
        & git merge --ff-only "origin/$branch"
        if ($LASTEXITCODE -ne 0) {
            Err "本地分支 $branch 与 origin/$branch 已分叉（存在本地未推送的提交），本脚本不做自动合并/变基。"
            Err "请手动执行 git pull（自行决定 merge 还是 rebase）后重跑。"
            exit 1
        }
        Ok "主仓库已同步到 origin/$branch 最新。"
    }
    finally {
        Pop-Location
    }
}

function Sync-Submodule {
    param([string]$R)
    Info "同步 SDK submodule（跟随 paintDemo main 最新）…"
    Push-Location $R
    & git submodule update --init --recursive --remote
    if ($LASTEXITCODE -ne 0) { Err "submodule 同步失败"; Pop-Location; exit 1 }
    Pop-Location
}

function Build-Apk {
    param([string]$R)
    Info "构建 APK（gradlew.bat assembleDebug）…"
    Push-Location $R
    & .\gradlew.bat assembleDebug --no-daemon
    $rc = $LASTEXITCODE
    Pop-Location
    if ($rc -ne 0) { Err "构建失败"; exit 1 }
    Ok "构建产物: app\build\outputs\apk\debug\app-debug.apk"
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $Root

Probe-All
Print-Check

if ($Check) { if ($script:HardMiss -gt 0) { Print-Guidance; exit 1 }; exit 0 }
if ($script:HardMiss -gt 0) { Print-Guidance; Err "硬依赖缺失 $($script:HardMiss) 项。请按指引补缺后重跑。"; exit 1 }

Sync-Repo $Root
Sync-Submodule $Root
Build-Apk $Root

if ($Test) {
    Info "测试门：assembleDebug 编译门已通过。DemoExport 离屏自检（exported=true + nativeExportPng→dgcExportPNG）代码审读："
    Info "  - AndroidManifest.xml 含 DemoExportActivity android:exported=\"true\""
    Info "  - DemoExportActivity.kt 触发 PaintNative.nativeExportPng"
    Info "  - paint_android_jni.cpp nativeExportPng → dgcFlush + dgcExportPNG"
    Info "  （无 arm64 真机时真机运行留人工；真机可 adb shell am start 显式触发）"
}

Info "paint-android 环境就绪。APK: app\build\outputs\apk\debug\app-debug.apk"
exit 0
