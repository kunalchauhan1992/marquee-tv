# Send your API keys to Marquee on the TV over ADB: keys\tmdb-key.txt (posters and rows, required) and
# keys\omdb-key.txt (IMDb ratings for titles newer than IMDb's daily file, optional). Keys are never printed.
# Only the PC's ADB shell can deliver them: Marquee's receiver needs the DUMP permission, which no app has.
#
# Usage:  .\scripts\send-keys.ps1 -TvHost 192.168.1.50
#         .\scripts\send-keys.ps1 -TvHost 192.168.1.50 -Dock com.netflix.ninja,in.startv.hotstar
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$CleanYourTv = (Join-Path $PSScriptRoot '..\..\cleanyourtv'),
    [string]$Dock = ''
)
$ErrorActionPreference = 'Stop'
$keys = Join-Path $PSScriptRoot '..\keys'

# Notepad with hidden extensions saves "x.txt" as "x.txt.txt"; accept either.
function KeyFile([string]$name) {
    @("$name.txt", "$name.txt.txt") | ForEach-Object { Join-Path $keys $_ } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}

$tmdbFile = KeyFile 'tmdb-key'
if (-not $tmdbFile) { throw 'Put your TMDB API key in keys\tmdb-key.txt first (see README, "API keys").' }
$tmdb = (Get-Content $tmdbFile -Raw).Trim()
if ($tmdb -notmatch '^[0-9a-fA-F]{32}$') { throw 'keys\tmdb-key.txt should hold only the 32-character TMDB "API Key" (not the long Read Access Token).' }
$extra = ''
$omdbFile = KeyFile 'omdb-key'
if ($omdbFile) {
    $omdb = (Get-Content $omdbFile -Raw).Trim()
    if ($omdb -notmatch '^[0-9a-zA-Z]{6,16}$') { throw 'keys\omdb-key.txt should hold only the OMDb API key (from the email OMDb sent).' }
    $extra = " --es omdb $omdb"
}
if ($Dock) {
    if ($Dock -notmatch '^[A-Za-z0-9_.,]+$') { throw '-Dock takes package names separated by commas.' }
    $extra += " --es dock $Dock"
}

$env:TV_HOST = $TvHost
$py = Join-Path $CleanYourTv 'tools\venv\Scripts\python.exe'
$ErrorActionPreference = 'Continue'   # tv.py's stderr warnings aren't failures; the "ok" reply below decides
$out = & $py -I (Join-Path $CleanYourTv 'tools\tv.py') "am broadcast -a com.marquee.tvhome.SETUP -n com.marquee.tvhome/.SetupReceiver --es key $tmdb$extra"
if ("$out" -match 'data="ok"') {
    $what = if ($omdbFile) { 'TMDB and OMDb keys' } else { 'TMDB key (no omdb-key.txt: IMDb ratings only from IMDb''s daily file)' }
    Write-Host "Sent the $what to Marquee. Press Home on the TV."
} else { Write-Host 'The TV did not confirm. Is Marquee installed and network debugging on?' }
