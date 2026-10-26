# Undo everything: re-enable every package in your debloat list, lift the background restrictions, set
# Private DNS back to automatic, and hand the Home button back to the stock launcher.
#
# Usage:  .\scripts\restore.ps1 -TvHost 192.168.1.50 -List my-tv\debloat.txt                  all but Marquee
#         .\scripts\restore.ps1 -TvHost 192.168.1.50 -List my-tv\debloat.txt -RemoveMarquee   also uninstall it
#         .\scripts\restore.ps1 -TvHost 192.168.1.50 -Package com.example.app                 bring back one app
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$List = '',
    [string]$BootList = '',
    [string]$CleanYourTv = (Join-Path $PSScriptRoot '..\..\cleanyourtv'),
    [string]$Package = '',
    [switch]$RemoveMarquee
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$py = Join-Path $CleanYourTv 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw "No cleanyourtv venv at $py - see README, 'Before you start'." }
$env:TV_HOST = $TvHost
function Tv([string]$cmd) { $ErrorActionPreference = 'Continue'; & $py -I (Join-Path $CleanYourTv 'tools\tv.py') $cmd }
$pkgPattern = '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'

if ($Package) {
    if ($Package -notmatch $pkgPattern) { throw 'Not a package name.' }
    Tv "pm enable --user 0 $Package"
    Tv "cmd appops set $Package RUN_ANY_IN_BACKGROUND allow"
    exit 0
}
if (-not $List) { throw 'Pass -List with the debloat list you applied (or -Package for one app).' }
$List = (Resolve-Path $List).Path
if (-not $BootList) { $BootList = Join-Path (Split-Path $List) 'reenabled-at-boot.txt' }

# One package per shell call: chained pm commands can stop silently after the first.
$pkgs = Get-Content $List | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ -match $pkgPattern } |
    ForEach-Object { ($_ -split '\s+')[0] }
foreach ($p in $pkgs) { Tv "pm enable --user 0 $p" }
if (Test-Path $BootList) {
    foreach ($p in Get-Content $BootList) {
        $p = ($p -replace '#.*$', '').Trim()
        if ($p -match $pkgPattern) { Tv "cmd appops set $p RUN_ANY_IN_BACKGROUND allow" }
    }
}
Tv 'settings put global private_dns_mode opportunistic'

# Remove Marquee's Home-button service from the enabled list, leaving any others.
Tv "s=`$(settings get secure enabled_accessibility_services); settings put secure enabled_accessibility_services `"`$(echo `$s | tr ':' '\n' | grep -v com.marquee.tvhome | paste -sd: -)`""
if ($RemoveMarquee) { Tv 'pm uninstall com.marquee.tvhome' }

Write-Host 'Restored. Restart the TV once.'
