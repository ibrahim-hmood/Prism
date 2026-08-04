<#
.SYNOPSIS
    Windows-side wrapper for build-qemu-android.sh.

.DESCRIPTION
    Cross-compiling QEMU against the Android NDK needs a Linux userspace (autotools,
    a real meson/ninja toolchain, etc.) -- there's no practical way to do this natively
    from cmd/PowerShell. This script just drives WSL: it finds your NDK (reusing the copy
    Android Studio already downloaded, if present, so you don't need a second one), runs
    the real build inside WSL, and copies the resulting .so back out to the Windows side.

.PARAMETER NdkPath
    Optional. Windows-style path to your Android NDK (e.g. the one under
    %LOCALAPPDATA%\Android\Sdk\ndk\<version>). If omitted, the script searches the usual
    Android Studio SDK location for you.

.EXAMPLE
    .\build-qemu-android.ps1
    .\build-qemu-android.ps1 -NdkPath "C:\Users\you\AppData\Local\Android\Sdk\ndk\27.0.12077973"
#>
param(
    [string]$NdkPath
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

function Convert-ToWslPath([string]$winPath) {
    # C:\Users\you\thing -> /mnt/c/Users/you/thing
    $p = $winPath -replace '\\', '/'
    if ($p -match '^([A-Za-z]):(.*)$') {
        $drive = $Matches[1].ToLower()
        $rest = $Matches[2]
        return "/mnt/$drive$rest"
    }
    return $p
}

Write-Step "Checking WSL is available"
$wslCheck = wsl.exe -l -q 2>$null
if ($LASTEXITCODE -ne 0 -or -not $wslCheck) {
    Write-Error "WSL doesn't appear to be installed or no distro is set up. Install it with: wsl --install (then restart), and make sure Ubuntu is the default distro."
    exit 1
}
Write-Host "WSL OK"

if (-not $NdkPath) {
    Write-Step "Locating Android NDK (reusing Android Studio's copy)"
    $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk"
    if (Test-Path $sdkRoot) {
        $candidates = Get-ChildItem $sdkRoot -Directory | Sort-Object Name -Descending
        if ($candidates.Count -gt 0) {
            $NdkPath = $candidates[0].FullName
            Write-Host "Found NDK: $NdkPath"
        }
    }
    if (-not $NdkPath) {
        Write-Error "Couldn't auto-detect an NDK under $sdkRoot. Install one via Android Studio (Settings > Languages & Frameworks > Android SDK > SDK Tools > NDK), or pass -NdkPath explicitly."
        exit 1
    }
} else {
    if (-not (Test-Path $NdkPath)) {
        Write-Error "NdkPath '$NdkPath' does not exist."
        exit 1
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$wslScriptDir = Convert-ToWslPath $scriptDir
$wslNdkPath = Convert-ToWslPath $NdkPath

Write-Step "Running build-qemu-android.sh inside WSL"
Write-Host "  Script dir (WSL): $wslScriptDir"
Write-Host "  NDK path (WSL):   $wslNdkPath"
Write-Host ""

# chmod +x defensively -- file permission bits from a Windows filesystem checkout
# don't reliably carry the executable bit into WSL's view of the same file.
wsl.exe bash -lc "chmod +x '$wslScriptDir/build-qemu-android.sh' && ANDROID_NDK_HOME='$wslNdkPath' '$wslScriptDir/build-qemu-android.sh'"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed -- see WSL output above."
    exit 1
}

$outFile = Join-Path $scriptDir "out\libqemu-system-aarch64.so"
if (Test-Path $outFile) {
    Write-Step "Build succeeded"
    Write-Host "Output: $outFile"
    Write-Host ""
    Write-Host "Next: copy this into app\src\main\jniLibs\arm64-v8a\libqemu-system-aarch64.so, rebuild, reinstall."
} else {
    Write-Error "Build script exited successfully but the expected output file wasn't found at $outFile -- check the build log above."
    exit 1
}
