# Bonk 🕵️: an anti-doomscroll bot with a personality

Spend too long on Instagram or YouTube and **your Bonk character** walks onto your screen.
It opens a numbered **case file**, closes the app or tab, says its line, flashes a ✌️ and walks off.
Come back too soon and it **interrogates you** first: give a real reason (AI-judged) or get kicked again.
Phone and laptop share one **case file** through optional sync, and every week you get an
**investigation report**: prime suspect app, most dangerous hour, minutes lost.

Works on **Android** (Java, Accessibility Service) and **Windows** (Python, standard library only).

## What makes it different
| Most blockers | Bonk |
|---|---|
| Timers, grey lock screens | A 3D character with a personality that walks in and acts |
| A usage graph | Case files + a weekly investigation report you'd actually screenshot |
| One device | Phone + laptop share cases (optional sync) |
| "Open anyway?" button | An AI interrogation: real reasons earn a 5-min pass, excuses don't |

## What counts as rotting
| Kind | Examples | What Bonk does |
|---|---|---|
| Brain-rot apps | Instagram, Facebook, Snapchat, Reddit, X, Moj, Josh, ShareChat, YouTube Shorts | 2-minute limit on feeds, Reels and Shorts. DMs don't count. |
| YouTube | Long videos, lectures | Left alone. Only Shorts or endless swiping count. |
| Streaming | JioHotstar, Netflix, Prime Video, SonyLIV, ZEE5, MX Player | Asks "watching something, or just rotting?" Name a title and get a 3-hour pass. |
| Never touched | Spotify, YouTube Music, podcasts, Audible, Duolingo, Coursera, Udemy, Kindle | Ignored completely. |
| Games | BGMI, Free Fire, Call of Duty: Mobile, Brawl Stars, Clash of Clans, Clash Royale, eFootball, EA SPORTS FC Mobile, Subway Surfers, Candy Crush, Ludo King, Minecraft, Roblox, Genshin Impact, Among Us, 8 Ball Pool, Pokémon GO, Asphalt, Stumble Guys… plus **any app Android marks as a game** | 45-minute sittings and **2 hours a day**. At the limit Bonk warns you and gives you **2 minutes to finish your match**, then closes it. Reopening within 15 min (or after the daily 2 hours, until midnight) gets closed right away. Games never trigger the swipe detector or the 30-min reminder. |
| Any other app | anything | Optional: fast endless swiping (6+ swipes a minute) counts as rotting. |

Detection reads only which app is open, scroll events and view IDs (to spot Reels/Shorts
screens). It never reads what's on screen. View IDs are best effort: apps change them in updates.
On the laptop, games are recognised by their program (Valorant, Fortnite, Minecraft, Roblox, CoD, EA FC,
eFootball, CS2, GTA V, …) or by being installed in a Steam, Epic, Riot, Xbox, EA or Ubisoft games folder.
Fullscreen games may hide Bonk's window; the game is still closed when time is up.
For everything else, Bonk decides from the window title (e.g. "#shorts - YouTube" is rot, a named video
is "watching", "lecture" or "podcast" is safe).

**Try it without installing:** open `tools/demo.html`, a sped-up simulated phone running these rules.

## Characters
14 originals ship in `characters/`. Animals: Detective Bonk (panda), Ace (fox), Mocha (bunny),
Pops (bear), Oracle (cat), Sarge (puppy), Frost (polar bear), Bonk Classic. People: Juno, Rafe,
Maple, Kit, Indie, Sol.
Each has a personality: detective, sharp-suited closer, fast-talking coffee lover, goofy dad,
smug mind reader, strict cop or cheerleader (lines in `characters/personalities.json`).

**Design your own:** open `tools/studio.html` (Character Studio), pick an animal, outfit, hat,
eyewear, expression and personality, copy the character file into `characters/<name>.json`,
and push. GitHub renders it and builds a new app. Locally: `node tools/render.js`.

Every character is drawn by code (`tools/bonk-render.js`): shaded ellipsoids and capsules with
per-pixel lighting, supersampled. No image files are hand-made.

## Install
**Easiest:** push to `main`. GitHub Actions renders the characters, runs the tests, builds the app
and publishes a Release with `Bonk.apk` (phone) and `Bonk-Windows.zip` (laptop).

### Android
1. Download `Bonk.apk` from Releases on your phone and install it.
2. In Bonk: pick a character, choose apps and a limit, tap **Save**.
3. Settings → Apps → Bonk → ⋮ → **Allow restricted settings** (Android 13+).
4. Tap **Turn Bonk on** → Accessibility → Bonk → On. Then try **Test** and **Test: interrogate me**.

### Windows (Python 3.9+)
```bat
cd windows
python bonk.py --test        :: character arrives in 3 s - switch to an Instagram tab
python bonk.py --ask         :: preview the interrogation
python bonk.py --watch       :: preview the "watching or rotting?" check
python bonk.py --remind      :: preview the 30-minute break reminder
python bonk.py --game        :: preview the "2 minutes to finish your match" warning
python bonk.py --report      :: open your weekly investigation report
python bonk.py --characters  :: list characters (set "character" in config.json)
start_bonk.bat               :: run in the background (put a shortcut in shell:startup)
```

## Optional extras
**AI interrogation:** get a free Gemini API key at aistudio.google.com and paste it into the app
(or `gemini_api_key` in `windows/config.json`). Without a key, Bonk uses simple offline rules.

**Phone ↔ laptop sync:** create a free Firebase project → Realtime Database. Use its URL and the same
secret code on both devices (`sync_url`, `sync_code`). Cases are written to `/bonk/<code>/cases`.
For a quick personal setup, rules can allow read/write on `bonk/$code`; anyone who knows your
code could read your cases, so pick a long random one.

## How it works
- **Android:** an `AccessibilityService` tracks the foreground app (ignoring keyboard, shade and
  screen-off). The character is a `TYPE_ACCESSIBILITY_OVERLAY` view; the "grab" is
  `performGlobalAction(GLOBAL_ACTION_HOME)`. Interrogation is a dialog activity over the app.
- **Windows:** checks the active window title once a second; browsers get Ctrl+W (only that tab),
  apps get `WM_CLOSE`. The character is a click-through Tk window that never takes focus.
- **Rules:** 2-minute limit on rotting; breaks under 60 s don't reset it. After a kick, reopening
  within 10 minutes means an interrogation: a good reason gets a 5-minute pass, a meal ("eating
  lunch") gets a 20-minute pass, an excuse gets you kicked again. Without an interrogation, the
  limit in those 10 minutes is only 30 seconds.
- **Break reminder:** after 30 minutes on the phone in one sitting (music, podcasts and learning
  apps don't count; 2+ minutes with the screen off starts a new sitting), Bonk walks in, says so,
  waves and leaves. It never closes anything. On the laptop it counts entertainment time only.

## Tests
`python tests/test_core.py` covers the tracker, lines, offline judge, case files and report.
