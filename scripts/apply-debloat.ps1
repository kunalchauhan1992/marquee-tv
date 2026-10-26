# Debloat a TV: disable the packages in a list (through cleanyourtv's tools\disable.ps1, which refuses Android's
# core packages and keeps a record for restore), then block the packages your firmware turns back on at every
# boot from running in the background.
#
# Usage:  .\scripts\apply-debloat.ps1 -TvHost 192.168.1.50 -List my-tv\debloat.txt
#         .\scripts\apply-debloat.ps1 -TvHost 192.168.1.50 -List examples\acer-r10g\debloat.txt -AdGuardDns
#
# The list: one package per line, # comments allowed (cleanyourtv's format, so its examples\*\debloat.txt work too).
# If a reenabled-at-boot.txt sits next to the list (or you pass -BootList), those packages also get
# RUN_ANY_IN_BACKGROUND ignore, which survives reboots. Back up first (PROMPT.md, "Back up").
# Undo with .\scripts\restore.ps1 and the same -List.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [Parameter(Mandatory = $true)][string]$List,
    [string]$BootList = '',
    [string]$CleanYourTv = (Join-Path $PSScriptRoot '..\..\cleanyourtv'),
    [switch]$AdGuardDns
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$py = Join-Path $CleanYourTv 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw "No cleanyourtv venv at $py - see README, 'Before you start'." }
$List = (Resolve-Path $List).Path
if (-not $BootList) { $BootList = Join-Path (Split-Path $List) 'reenabled-at-boot.txt' }
$env:TV_HOST = $TvHost
function Tv([string]$cmd) { $ErrorActionPreference = 'Continue'; & $py -I (Join-Path $CleanYourTv 'tools\tv.py') $cmd }

& (Join-Path $CleanYourTv 'tools\disable.ps1') -TvHost $TvHost $List

# pm disable-user doesn't survive a cold boot for these; a background restriction does.
if (Test-Path $BootList) {
    foreach ($p in Get-Content $BootList) {
        $p = ($p -replace '#.*$', '').Trim()
        if ($p -match '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$') { Tv "cmd appops set $p RUN_ANY_IN_BACKGROUND ignore" }
    }
}

if ($AdGuardDns) {
    Tv 'settings put global private_dns_mode hostname'
    Tv 'settings put global private_dns_specifier dns.adguard-dns.com'
}

Write-Host "Done. Restart the TV once, then check with: .\scripts\check.ps1 -TvHost $TvHost -List $List"
