# Customising Marquee

Marquee ships set up for India: "where to watch" in India, Hindi for the main rows, and six streaming
services (Netflix, Prime Video, YouTube, JioHotstar, ZEE5, Sony LIV). The dock and hidden apps are settings
sent from the PC; region, language and services are small code edits, then a rebuild.

## Dock ("Your apps")

The first row on Home. Send a comma-separated list of package names, in order:

```powershell
.\scripts\send-keys.ps1 -TvHost 192.168.1.50 -Dock com.netflix.ninja,com.amazon.amazonvideo.livingroom,com.google.android.youtube.tv,in.startv.hotstar
```

| App | Package |
|---|---|
| Netflix | `com.netflix.ninja` |
| Prime Video | `com.amazon.amazonvideo.livingroom` |
| YouTube | `com.google.android.youtube.tv` |
| JioHotstar | `in.startv.hotstar` |
| ZEE5 | `com.graymatrix.did` |
| Sony LIV | `com.sonyliv` |
| Live TV | varies by TV: find it with `pm list packages \| grep -i tv` |

To find any other app's package: `tools\tv.py "pm list packages | grep -i <name>"`.

## Hidden apps

Apps hidden from the Apps page are set the same way, through the setup broadcast:

```powershell
$env:TV_HOST = '192.168.1.50'
..\cleanyourtv\tools\venv\Scripts\python.exe ..\cleanyourtv\tools\tv.py "am broadcast -a com.marquee.tvhome.SETUP -n com.marquee.tvhome/.SetupReceiver --es hidden com.apps.scast,com.mk.tv.gallery"
```

This replaces the whole list, so include the defaults you want to keep (they're in `Prefs.java`, `DEFAULT_HIDDEN`).

## Your streaming services

`marquee/app/src/main/java/com/marquee/tvhome/Services.java` lists the services Marquee recommends from. Each
entry is a label, the app's package, and the lower-case fragments of the provider names TMDB uses. To add
one (say, a service called "Example Play"), add a line and put it in `ALL`:

```java
static final Service EXAMPLE = new Service("Example Play", "com.example.play.tv", "example play");
static final Service[] ALL = {NETFLIX, PRIME, HOTSTAR, YOUTUBE, ZEE5, SONYLIV, EXAMPLE};
```

TMDB's provider names for your country are at
`https://api.themoviedb.org/3/watch/providers/movie?watch_region=IN&api_key=...`.

## Region and language

In `Tmdb.java`:

- `REGION = "IN"` is the country for "where to watch". Use your two-letter country code.
- `indianQuery()` (and the rows in `MainActivity.java` that call `discover`) build the main rows with
  `with_origin_country=IN&with_original_language=hi`. Change `IN` to your country and `hi` to your language
  (an ISO 639-1 code: `en` English, `es` Spanish, `ta` Tamil, …), or drop `with_original_language` to get
  every language made in your country.
- `REGIONAL` is the set of languages left out of every row (India's regional languages, since there's no data on
  which have a Hindi dub). Empty it, or set your own.
- Row titles are in `res/values/strings.xml` (`row_trending_india`, `row_new_india`, …).

Rebuild and reinstall with `.\scripts\install-marquee.ps1`, then use Settings → "Refresh posters" in Marquee
(or send `--ez refresh true`) so cached rows are fetched again.

## Rows

`MainActivity.java` builds each page's rows in order (search for `posterRow(`). Each row is a title and a
function that returns titles from `Tmdb`. Reorder, remove or add rows there; every TMDB query goes through
`onYourApps()`, which keeps it to your services.
