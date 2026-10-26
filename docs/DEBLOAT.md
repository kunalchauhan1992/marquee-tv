# Debloating any Android TV

Every TV maker ships different extras, so there's no single list. This is how to make one for your TV, and how
to deal with firmware that turns apps back on.

## The rules

From [cleanyourtv's PROMPT.md](https://github.com/Gathin23/cleanyourtv/blob/main/PROMPT.md), unchanged:

- **Disable, never uninstall.** `pm disable-user --user 0`, one package per shell call. `restore.ps1` undoes it.
- **Back up first:** package list, disabled list, `getprop` and settings, before the first change.
- **Small batches, a screenshot after each.** If the TV misbehaves, you know which batch did it.
- **Never touch** Android's core packages, the TV's inputs and tuner (HDMI, Live TV), remote and Bluetooth
  pairing, the keyboard, Play Store or Play Services. cleanyourtv's `disable.ps1` refuses a list with core packages.
- **Never start a factory or test menu** (package names with `factory`, `fac`, `production`), not even to look.
- **Ask first** about anything the household might use: casting (Chromecast, AirPlay, Miracast), the voice
  assistant, music apps, USB players, the maker's own apps.

## 1. Start from a list that exists

- cleanyourtv has [tested lists for many TVs](https://github.com/Gathin23/cleanyourtv/tree/main/examples).
- This repo's [examples/](../examples/) has more.

Same board, same firmware family? Copy that list to a folder of your own (`my-tv\debloat.txt`) and edit it.

## 2. Or build one

List what's installed and what's running:

```powershell
$env:TV_HOST = '192.168.1.50'
$tv = '..\cleanyourtv\tools\venv\Scripts\python.exe', '..\cleanyourtv\tools\tv.py'
& $tv[0] $tv[1] "pm list packages -s | sort"          # system apps
& $tv[0] $tv[1] "pm list packages -3 | sort"          # apps you or the maker installed
& $tv[0] $tv[1] "dumpsys meminfo | sed -n '/Total PSS by process/,/^$/p'"   # what uses RAM now
```

For each package you don't recognise, read what it is before deciding:

```powershell
& $tv[0] $tv[1] "dumpsys package com.example.something | grep -E 'versionName|flags=|Activity|Service' | head -20"
```

Good candidates on most TVs:

| Kind | Typical package names |
|---|---|
| Telemetry, remote management, consent agents | `*mdm*`, `*feedback*`, `*gdpr*`, `*analytics*`, `partnersetup`, `autoinstalls` |
| Demo and store-display apps | `*demo*`, `*sticker*`, `*signage*`, `*retail*`, `*showcase*` |
| Things a TV rarely needs | hotspot, karaoke, Health Connect, Play Games, basic screensavers |
| Your choices | casting receivers, music apps, file senders, USB players, extra launchers |

Write it as cleanyourtv's format: one package per line, a `#` comment with the reason, grouped in batches.

```
# Batch 1: telemetry
com.example.mdm              # remote management agent, not enrolled
com.google.android.feedback
```

## 3. Apply it in batches

Comment out every batch but the first, run `apply-debloat.ps1`, take a screenshot, check the TV still works
(Live TV, HDMI, the remote, Wi-Fi), then uncomment the next batch and run again. Running it twice is harmless.

## 4. Restart and look for packages that come back

Some firmware re-enables a fixed set of packages at every cold boot. After the restart:

```powershell
& $tv[0] $tv[1] "pm list packages -d | sort"
```

Anything from your list that's no longer disabled came back. Check who did it:

```powershell
& $tv[0] $tv[1] "dumpsys package com.example.app | grep -E 'User 0:|lastDisabledCaller'"
```

You can't stop the firmware doing that without root. Put those packages in `reenabled-at-boot.txt` next to your
list. `apply-debloat.ps1` gives each one a background restriction, which does survive reboots:

```
cmd appops set <package> RUN_ANY_IN_BACKGROUND ignore
```

The app is enabled, but it can't start services or receive broadcasts in the background, so it doesn't run at
boot. Marquee also hides apps you list (see [CUSTOMISE.md](CUSTOMISE.md), "Hidden apps").

Two things that look like a package came back but didn't:

- **Hidden packages.** Some firmware hides disabled apps (`hidden=true` in `dumpsys package`). `pm list packages`
  leaves hidden packages out of every list, including `-d`. Check `enabled=` in `dumpsys package` instead.
- **Persistent system apps** (`PERSISTENT` in `pkgFlags`) keep running even when restricted; Android restarts
  them. Pass them to `check.ps1 -Persistent` so they don't count as a failure.

## 5. Check

```powershell
.\scripts\check.ps1 -TvHost 192.168.1.50 -List my-tv\debloat.txt
```

## Don't disable the stock launcher on Google TV

On Google TV the launcher (`launcherx`) and setup app (`setupwraith`) are tied into Wi-Fi and boot on many
boards. Check `dumpsys wifi | grep -o 'cname=[^ ]*'`: if setupwraith owns the saved network, disabling it drops
Wi-Fi and ADB with it. Marquee doesn't need either disabled; it takes the Home button instead.

## AdGuard DNS

`-AdGuardDns` sets Private DNS to `dns.adguard-dns.com`, which blocks ad and tracker domains for every app on the
TV. It's the same as the Private DNS setting in network settings. `restore.ps1` sets it back to automatic.
