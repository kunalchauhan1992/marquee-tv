# Troubleshooting

Every one of these happened while building this. `scripts\check.ps1` catches most of them.

## Home opens Google TV instead of Marquee

Marquee's Home-button service was switched off, usually by a "close apps" / memory-cleaner button on the TV, or an update.

1. Open Marquee once from the Apps row of Google TV (or press Home again). With `WRITE_SECURE_SETTINGS`
   granted, it switches its service back on when it starts.
2. Still Google TV? The permission is missing. Grant it and start Marquee:
   ```
   tools\tv.py "pm grant com.marquee.tvhome android.permission.WRITE_SECURE_SETTINGS"
   tools\tv.py "am start -n com.marquee.tvhome/.MainActivity"
   ```
3. Check: `tools\tv.py "dumpsys accessibility | grep -c 'label=Marquee Home button'"` prints `1`.

## Posters don't load after the TV starts

At boot the TV often has Wi-Fi before it has internet ("connected, no internet" for a few seconds). Marquee
retries failed rows when the network becomes usable, again every 30 seconds, and whenever you come back to
Home. If rows stay empty:

- Is the key there? Marquee shows "Add your TMDB key" when it isn't. Run `send-keys.ps1`.
- Some ISPs (several in India, for example) reset connections to TMDB now and then. The app retries, but if it's constant, switch the
  TV's Private DNS to AdGuard (`apply-debloat.ps1 -AdGuardDns`) or another resolver.

## "No IMDb rating" on a new release

IMDb's daily file only includes titles with enough votes. Newer titles come from OMDb, which lags behind IMDb
by days. Without an OMDb key they show no rating, never a TMDB score. If OMDb gives `401`, click the activation
link in the email OMDb sent.

## The trailer doesn't play

Some trailers can't be embedded. Marquee falls back to opening the trailer in the YouTube app; Back returns
to Marquee. "No trailer for this title" means TMDB has none.

## "Watch on" opens the app but not the title

Streaming apps don't accept direct links from other apps. Marquee opens the app's search for the title,
which is one OK press away. JioHotstar and ZEE5 take a search intent; Netflix, Prime Video and YouTube take
their own web search links.

## A recommended title isn't on my app

TMDB's where-to-watch data comes from JustWatch and can be a day or two out of date, especially when titles
move between services. Marquee caches it for 24 hours.

## ADB won't connect

- `tools\tv.py --probe` times out: the TV is off, in standby, or has a new IP. Check Settings → Network.
  After a router restart, every device can get a new address.
- "Unauthorized": accept the prompt on the TV, and tick "Always allow from this computer".
- Git Bash turns `/data/local/tmp` into a Windows path. Set `MSYS_NO_PATHCONV=1`, or use PowerShell.

## The build fails on Windows with a long-path error

Gradle and the Android build tools can fail in deep folders (paths over 260 characters). Move the repo to a
short path such as `C:\src\marquee-tv`, or enable long paths in Windows.

## The screenshot is black

Netflix, Live TV and HDMI inputs block screenshots. That's the TV protecting content, not a problem.

## Volume keys only move one step

Something is filtering keys. Marquee's service doesn't (`canRequestFilterKeyEvents` is off). Check
`settings get secure enabled_accessibility_services` for other services, such as an older launcher's helper.
