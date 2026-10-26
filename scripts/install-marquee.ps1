# Build Marquee from source and install it on the TV over network ADB, then make it Home.
#
# Usage:  .\scripts\install-marquee.ps1 -TvHost 192.168.1.50
#         .\scripts\install-marquee.ps1 -TvHost 192.168.1.50 -SkipBuild     reinstall the last build
#
# Needs: JDK 17 (JAVA_HOME) and the Android SDK with platform 34 (ANDROID_HOME), and cleanyourtv cloned next
# to this repo with its venv set up (see README, "Before you start"). Pass -CleanYourTv if it lives elsewhere.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$CleanYourTv = (Join-Path $PSScriptRoot '..\..\cleanyourtv'),
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$py = Join-Path $CleanYourTv 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw "No cleanyourtv venv at $py - see README, 'Before you start'." }
$env:TV_HOST = $TvHost
# Native tools print warnings on stderr, which Windows PowerShell 5.1 would turn into a fatal error under 'Stop'.
function Native([scriptblock]$block) { $ErrorActionPreference = 'Continue'; & $block 2>&1 | ForEach-Object { "$_" } }
function Tv([string]$cmd) { Native { & $py -I (Join-Path $CleanYourTv 'tools\tv.py') $cmd } }

$src = Join-Path $PSScriptRoot '..\marquee'
$apk = Join-Path $src 'app\build\outputs\apk\release\app-release.apk'
if (-not $SkipBuild) {
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17.' }
    if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to your Android SDK (platform 34, build-tools 34).' }
    Push-Location $src
    try { Native { & .\gradlew.bat --no-daemon assembleRelease }; if ($LASTEXITCODE) { throw 'Build failed (see above).' } }
    finally { Pop-Location }
}
if (-not (Test-Path $apk)) { throw "No APK at $apk - run without -SkipBuild." }

# adb_shell's push stalls on larger files; b64push sends base64 chunks through the shell and checks the md5.
Native { & $py -I (Join-Path $CleanYourTv 'tools\b64push.py') $apk /data/local/tmp/marquee.apk }
Tv 'pm install -r /data/local/tmp/marquee.apk; rm -f /data/local/tmp/marquee.apk'

# Lets Marquee put its Home-button service back in the enabled list after TV Manager's "close apps" or an update.
Tv 'pm grant com.marquee.tvhome android.permission.WRITE_SECURE_SETTINGS'
Tv 'am start -n com.marquee.tvhome/.MainActivity' | Out-Null
Start-Sleep -Seconds 6

$bound = Tv "dumpsys accessibility | grep -c 'label=Marquee Home button'"
$focus = Tv 'dumpsys window | grep -m1 mCurrentFocus'
if ("$bound".Trim() -eq '1' -and "$focus" -match 'com.marquee.tvhome') {
    Write-Host 'Marquee is installed and Home. Next: .\scripts\send-keys.ps1, then press Home on the real remote.'
} else {
    Write-Host "Installed, but check: Home-button service bound = $bound; on screen: $focus"
    Write-Host 'See docs\TROUBLESHOOTING.md, "Home opens Google TV".'
}
