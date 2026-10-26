# The prompt

Paste the text below into Claude Code, Codex, Gemini CLI or another coding agent that can run commands, on a
computer on the same Wi-Fi as the TV. Clone [cleanyourtv](https://github.com/Gathin23/cleanyourtv) and this
repo into the same folder first, and start the agent in that folder.

Before you start, on the TV: in Settings → About, press OK on "Build" (or "Android TV OS build") 7 times, then
in Developer options turn on **USB debugging** (and **Network debugging** if your TV lists it). Keep the TV on,
with nobody watching it: the agent will press buttons and take screenshots.

Have your TMDB API key ready (README, "API keys"). You'll save it to a file yourself; don't paste it into the chat.

---

I want to debloat my Android TV / Google TV and replace its home screen with Marquee, following the marquee-tv
repo in this folder. My TV is: <BRAND AND MODEL>. Its IP is <IP, or "find it">. Work on the real TV, step by
step, and check every step with a screenshot from the TV. Ask me before anything that depends on what my
household uses.

Read first
- `marquee-tv/README.md`, `marquee-tv/docs/DEBLOAT.md` and `cleanyourtv/PROMPT.md`. cleanyourtv's safety rules
  apply in full, and win over anything here.
- Use the scripts in `marquee-tv/scripts/` and cleanyourtv's `tools/` (`tv.py`, `b64push.py`, `disable.ps1`).
  Don't write new ADB tooling.

Connect
- Set up cleanyourtv's venv if it isn't there, connect with `tools/tv.py --probe`, and tell me the model, board,
  Android version and firmware build. Only quote those from `getprop`.

Back up
- Before any change: package list, disabled list, `getprop`, and global/secure/system settings into
  `cleanyourtv/backup/<date>/`. Show me the folder.

Debloat
- Look for a tested list for this model or board in `cleanyourtv/examples/` and `marquee-tv/examples/`. If
  there is one, start from a copy of it. If not, build one with me as `marquee-tv/docs/DEBLOAT.md` describes:
  list the packages, read what each unknown one is, and group them into batches with a reason per line.
- Put my list in its own folder (e.g. `marquee-tv/my-tv/debloat.txt`). Ask me in one checklist which of these
  my household uses: casting (Chromecast, AirPlay, Miracast), the voice assistant, music apps, USB drives,
  file senders, the maker's own apps, any memory cleaner. Comment out whatever I tick.
- On Google TV, check `dumpsys wifi | grep -o 'cname=[^ ]*'` before touching setupwraith, and never disable the
  stock launcher: Marquee doesn't need it disabled. Never disable TV input, tuner or remote-key packages.
  Never start a package whose name says factory.
- Apply one batch at a time with `apply-debloat.ps1 -List`, a screenshot after each. Ask me before turning on
  AdGuard DNS.
- Restart the TV and check which packages came back on. If the firmware re-enables some, put them in
  `reenabled-at-boot.txt` next to my list and run `apply-debloat.ps1` again. Tell me about any persistent
  system app that keeps running.

Marquee
- Check JDK 17 and the Android SDK (platform 34). If they're missing, tell me what to install; don't change my
  system settings yourself.
- Marquee ships set to India, Hindi and Indian streaming services. Ask me my country, language and streaming
  apps. If they differ, make the changes in `marquee-tv/docs/CUSTOMISE.md` and show me the diff before building.
- Ask me to save my TMDB key in `marquee-tv/keys/tmdb-key.txt` (and OMDb in `omdb-key.txt` if I have one).
  Never print, log or commit a key, and never put one in a URL you show me.
- Ask me which apps go in the dock, in order. Then run `install-marquee.ps1` and `send-keys.ps1 -Dock ...`.
- If the TV's stock launcher isn't Google TV's `launcherx`, tell me before installing: the Home takeover needs
  that package name changed (README, "Known limits").
- Show me Home, a details page with "More like this", and a trailer opened and closed with Back.

Verify
- Restart the TV, wait for it, and run `check.ps1 -List <my list>`. Every line should be OK, and Marquee should
  be on screen without anyone pressing a button.
- Ask me to press Home on the real remote, from inside an app. An injected key isn't proof the Home-button
  service works.
- Ask me to press and hold Volume up. It must keep repeating.

Finish
- Ask whether I want network ADB closed now. If yes: turn off USB and network debugging, revoke
  authorisations, restart, and confirm with `tools/adb-closed.py <ip>`.
- Tell me what you changed, what you skipped and why, and how to undo it (`restore.ps1 -List <my list>`).
- If I agree, add my TV to `marquee-tv/examples/` (list, boot list, short README) without IPs, Wi-Fi names,
  account names or serial numbers.
