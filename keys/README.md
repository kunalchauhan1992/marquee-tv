# keys

Put your API keys here, one per file, nothing else in the file:

- `tmdb-key.txt`: your 32-character TMDB API Key (required)
- `omdb-key.txt`: your OMDb key (optional)

`scripts\send-keys.ps1` reads them and sends them to the TV over ADB. Everything in this folder except this
README is ignored by git. Don't commit, paste or screenshot your keys.
