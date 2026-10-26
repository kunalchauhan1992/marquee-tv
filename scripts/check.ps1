# Health check, read-only: is Marquee's Home-button service bound, is anything from your debloat list running,
# are boot-re-enabled apps still restricted, Private DNS, and free RAM.
#
# Usage:  .\scripts\check.ps1 -TvHost 192.168.1.50 -List my-tv\debloat.txt
#         .\scripts\check.ps1 -TvHost 192.168.1.50 -List examples\acer-r10g\debloat.txt -Persistent com.mk.tv.meeting.service
#
# -Persistent: packages you know keep running anyway (persistent system apps), so they don't count as a failure.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$List = '',
    [string]$BootList = '',
    [string[]]$Persistent = @(),
    [string]$CleanYourTv = (Join-Path $PSScriptRoot '..\..\cleanyourtv')
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$py = Join-Path $CleanYourTv 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw "No cleanyourtv venv at $py - see README, 'Before you start'." }
$env:TV_HOST = $TvHost
# dumpsys says "Broken pipe" when grep -m1 stops reading early; that's not an error.
function Tv([string]$cmd) {
    $ErrorActionPreference = 'Continue'
    (& $py -I (Join-Path $CleanYourTv 'tools\tv.py') $cmd 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'Broken pipe' }) -join "`n"
}
function Show([string]$label, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { 'OK  ' } else { 'FIX ' }
    Write-Host ("{0} {1,-34} {2}" -f $mark, $label, $detail)
}
function Packages([string]$file) {
    if (-not $file -or -not (Test-Path $file)) { return @() }
    @(Get-Content $file | ForEach-Object { (($_ -replace '#.*$', '').Trim() -split '\s+')[0] } |
        Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' })
}

$bound = (Tv "dumpsys accessibility | grep -c 'label=Marquee Home button'").Trim()
$focus = Tv 'dumpsys window | grep -m1 mCurrentFocus'
Show 'Marquee Home-button service' ($bound -eq '1') "bound=$bound"
Show 'On screen' $true (($focus -replace '.*u0 ', '' -replace '}', '').Trim())

if ($List) {
    $List = (Resolve-Path $List).Path
    if (-not $BootList) { $BootList = Join-Path (Split-Path $List) 'reenabled-at-boot.txt' }
    $pkgs = Packages $List
    $running = @($pkgs | Where-Object { (Tv "pidof $_").Trim() })
    $extra = @($running | Where-Object { $Persistent -notcontains $_ })
    Show 'Debloated apps running' ($extra.Count -eq 0) ($(if ($running) { $running -join ', ' } else { "none of $($pkgs.Count)" }))

    $boot = Packages $BootList
    if ($boot.Count) {
        $blocked = @($boot | Where-Object { (Tv "cmd appops get $_ RUN_ANY_IN_BACKGROUND") -match 'ignore' }).Count
        Show 'Boot-re-enabled apps restricted' ($blocked -eq $boot.Count) "$blocked of $($boot.Count)"
    }
}

$mode = (Tv 'settings get global private_dns_mode').Trim()
$dns = (Tv 'settings get global private_dns_specifier').Trim()
Show 'Private DNS' $true $(if ($mode -eq 'hostname') { $dns } else { "$mode (not set)" })

$mem = Tv "grep -E 'MemTotal|MemAvailable' /proc/meminfo | tr -s ' ' | cut -d' ' -f2"
$kb = @($mem -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { [double]$_ })
if ($kb.Count -eq 2) { Show 'Free RAM' ($kb[1] / $kb[0] -gt 0.25) ("{0} MB of {1} MB" -f [int]($kb[1] / 1024), [int]($kb[0] / 1024)) }

$pss = (Tv "dumpsys meminfo com.marquee.tvhome | grep 'TOTAL PSS' | tr -s ' ' | cut -d' ' -f4").Trim()
if ($pss) { Show 'Marquee memory' $true ("{0} MB" -f [int]([double]$pss / 1024)) }
