"""
Bonk's brain, without any Windows or GUI code (so it can be tested anywhere):
config, characters + personalities, the usage tracker, case files, the weekly
investigation report, cross-device sync and the AI interrogation.
"""
import collections
import datetime as dt
import html
import json
import os
import random
import re
import threading
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
CASES_PATH = os.path.join(HERE, "cases.jsonl")
CHAR_DIR = os.path.join(HERE, "characters")
REPORT_PATH = os.path.join(HERE, "case_report.html")

DEFAULT_CONFIG = {
    "name": "Megna",
    "character": "detective-panda",
    "limit_minutes": 2,
    "grace_seconds": 60,
    "strike_limit_seconds": 30,
    "strike_window_minutes": 10,
    # brain-rot sites: time limit (YouTube counts only on Shorts or the home feed)
    "rot_keywords": ["instagram", "reels", "shorts", "facebook", "reddit", "twitter", " / x", "snapchat", "moj", "sharechat"],
    # streaming: Bonk asks "watching something, or just rotting?" when you open them
    "watch_keywords": ["youtube", "hotstar", "netflix", "prime video", "disney+", "sonyliv", "zee5", "mx player"],
    # never touched, even if a word above also appears in the title
    "safe_keywords": ["spotify", "podcast", "youtube music", "lecture", "tutorial", "nptel", "course",
                      "coursera", "udemy", "leetcode", "github", "docs", "audible"],
    "watch_pass_hours": 3,
    # games: limits per sitting and per day, with a warning so you can finish your match
    "game_session_minutes": 45,
    "game_daily_minutes": 120,
    "game_grace_minutes": 2,
    "game_cooldown_minutes": 15,
    "game_exes": ["valorant", "fortnite", "minecraft", "robloxplayerbeta", "cod", "modernwarfare", "blackops",
                  "fc24", "fc25", "fc26", "fifa", "efootball", "genshinimpact", "leagueclient", "league of legends",
                  "cs2", "csgo", "gta5", "rocketleague", "apex", "r5apex", "overwatch", "dota2", "pubg",
                  "brawlhalla", "amongus", "eldenring", "cyberpunk2077", "bgmi"],
    "game_folders": ["steamapps\\common", "epic games", "riot games", "xboxgames", "ea games", "ubisoft game launcher\\games"],
    "browsers": ["chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe"],
    # AI interrogation: when you go back to a blocked site soon after a kick, Bonk asks why
    "interrogate": True,
    "pass_minutes": 5,
    "meal_pass_minutes": 20,        # "eating" is a fair reason to watch for a while
    # gentle reminder (no closing) after this much entertainment time in one sitting
    "remind": True,
    "remind_minutes": 30,
    "gemini_api_key": "",
    "gemini_model": "gemini-3.8-flash",
    # cross-device sync (Firebase Realtime Database URL + a secret code you choose)
    "sync_url": "",
    "sync_code": "",
    "device_name": "laptop",
}


# ------------------------------------------------------------------ config
def load_config(path=CONFIG_PATH):
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            cfg.update(json.load(f))
    with open(path, "w", encoding="utf-8") as f:      # writes any new keys back for you
        json.dump(cfg, f, indent=2)
    for k in ("rot_keywords", "watch_keywords", "safe_keywords", "browsers"):
        cfg[k] = [x.lower() for x in cfg[k]]
    return cfg


BROWSER_SUFFIX = re.compile(r"\s+[-\u2013\u2014]\s+(google chrome|microsoft\u200b? edge|mozilla firefox|brave|opera|vivaldi).*$", re.I)


def classify(title, cfg):
    """'safe', 'watch', 'rot' or None for a window title."""
    t = BROWSER_SUFFIX.sub("", title).lower().strip()
    if not t or any(k in t for k in cfg["safe_keywords"]):
        return "safe" if t else None
    if "youtube" in t:
        # Shorts, or just the home feed ("YouTube"), is rotting; a named video is "watching"
        return "rot" if ("shorts" in t or t in ("youtube", "(1) youtube") or re.fullmatch(r"\(\d+\) youtube", t)) else "watch"
    if any(k in t for k in cfg["rot_keywords"]):
        return "rot"
    if any(k in t for k in cfg["watch_keywords"]):
        return "watch"
    return None


def is_game(exe, path, cfg):
    """A game by exe name or by living in a game launcher's install folder."""
    e, p = exe.lower(), path.lower()
    if any(k in e for k in cfg["game_exes"]):
        return True
    return any(f in p for f in cfg["game_folders"])


def is_distracting(title, cfg):
    return classify(title, cfg) == "rot"


NICE_NAMES = {"youtube": "YouTube", "hotstar": "JioHotstar", "netflix": "Netflix", "prime video": "Prime Video",
              "disney+": "Disney+", "sonyliv": "SonyLIV", "zee5": "ZEE5", "mx player": "MX Player",
              "instagram": "Instagram", "reels": "Reels", "shorts": "Shorts", " / x": "X", "twitter": "X"}


def app_name(title, cfg):
    """'Instagram - Google Chrome' -> 'Instagram'."""
    t = title.lower()
    for k in ["youtube"] + cfg["rot_keywords"] + cfg["watch_keywords"]:
        if k in t:
            return NICE_NAMES.get(k, k.strip().capitalize())
    return title[:30]


# ------------------------------------------------------------------ characters
def list_characters(char_dir=CHAR_DIR):
    try:
        with open(os.path.join(char_dir, "index.json"), encoding="utf-8") as f:
            return json.load(f)
    except OSError:
        return []


def load_character(cid, char_dir=CHAR_DIR):
    path = os.path.join(char_dir, cid, "character.json")
    if not os.path.exists(path):
        ids = [c["id"] for c in list_characters(char_dir)]
        if not ids:
            raise FileNotFoundError("No characters found. Run: node tools/render.js")
        cid = ids[0]
        path = os.path.join(char_dir, cid, "character.json")
    with open(path, encoding="utf-8") as f:
        spec = json.load(f)
    with open(os.path.join(char_dir, "personalities.json"), encoding="utf-8") as f:
        personas = json.load(f)
    spec["id"] = cid
    spec["frames_dir"] = os.path.join(char_dir, cid)
    spec["persona"] = personas.get(spec.get("personality", ""), personas["cheerleader"])
    return spec


def line(persona, key, **kw):
    """Pick a personality line and fill {n}, {app}, {mins}, {pass}."""
    text = persona[key]
    if isinstance(text, list):
        text = random.choice(text)
    for k, v in kw.items():
        text = text.replace("{" + k + "}", str(v))
    return re.sub(r"\{\w+\}", "", text)


# ------------------------------------------------------------------ tracker
class Tracker:
    """Counts continuous time on distracting windows. Short breaks (< grace) don't
    reset it. After a kick, 'strike mode' gives a much shorter limit for a while.
    An approved interrogation grants a pass with no counting at all."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.used = 0.0
        self.last_seen = 0.0
        self.strike_until = 0.0
        self.pass_until = 0.0
        self.watch_passes = {}          # app -> time until which "watching" is approved

    def in_strike(self, now):
        return now < self.strike_until

    def has_pass(self, now):
        return now < self.pass_until

    def add(self, now, dt_s):
        if self.has_pass(now):
            self.last_seen = now
            return False
        if now - self.last_seen > self.cfg["grace_seconds"]:
            self.used = 0.0
        self.used += dt_s
        self.last_seen = now
        limit = (self.cfg["strike_limit_seconds"] if self.in_strike(now)
                 else self.cfg["limit_minutes"] * 60)
        return self.used >= limit

    def kicked(self, now):
        self.used = 0.0
        self.strike_until = now + self.cfg["strike_window_minutes"] * 60

    def has_watch_pass(self, app, now):
        return now < self.watch_passes.get(app, 0)

    def grant_watch_pass(self, app, now):
        self.watch_passes[app] = now + self.cfg["watch_pass_hours"] * 3600

    def grant_pass(self, now, minutes=None):
        self.used = 0.0
        self.pass_until = now + (minutes or self.cfg["pass_minutes"]) * 60


class GameTracker:
    """Gaming time per sitting and per day.
    add() returns None, 'warn' (start the 2-minute finish window) or 'kick'.
    A 5-minute break ends a sitting. After a kick there's a cooldown; after the daily
    budget is used, games stay closed until midnight."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.session, self.away = 0.0, 0.0
        self.daily = {}                   # 'YYYY-MM-DD' -> seconds
        self.warned_at = None
        self.cooldown_until = 0.0
        self.reason = "session"

    def today_seconds(self, date):
        return self.daily.get(date, 0.0)

    def add(self, now, dt_s, gaming, date):
        if not gaming:
            self.away += dt_s
            if self.away >= 300:
                self.session, self.warned_at = 0.0, None
            return None
        self.away = 0.0
        if now < self.cooldown_until:
            return "kick"
        self.daily[date] = self.daily.get(date, 0.0) + dt_s
        self.session += dt_s
        if self.warned_at is not None:
            return "kick" if now - self.warned_at >= self.cfg["game_grace_minutes"] * 60 else None
        if self.daily[date] >= self.cfg["game_daily_minutes"] * 60:
            self.reason, self.warned_at = "daily", now
            return "warn"
        if self.session >= self.cfg["game_session_minutes"] * 60:
            self.reason, self.warned_at = "session", now
            return "warn"
        return None

    def kicked(self, now, date):
        self.session, self.warned_at = 0.0, None
        if self.daily.get(date, 0.0) >= self.cfg["game_daily_minutes"] * 60:
            midnight = dt.datetime.combine(dt.date.fromisoformat(date) + dt.timedelta(days=1), dt.time())
            self.cooldown_until = midnight.timestamp()
        else:
            self.cooldown_until = now + self.cfg["game_cooldown_minutes"] * 60


def daily_label(minutes):
    h, m = divmod(int(minutes), 60)
    return (f"{h} hour{'s' if h != 1 else ''}" if h else "") + (f" {m} min" if m else "") or "0 min"


class Reminder:
    """Counts entertainment time in one sitting; nudges every `remind_minutes`.
    A break of 2+ minutes away from entertainment starts a fresh sitting."""

    def __init__(self, cfg):
        self.cfg, self.used, self.away = cfg, 0.0, 0.0

    def add(self, dt_s, entertaining):
        if not self.cfg["remind"]:
            return False
        if not entertaining:
            self.away += dt_s
            if self.away >= 120:
                self.used = 0.0
            return False
        self.away = 0.0
        self.used += dt_s
        if self.used >= self.cfg["remind_minutes"] * 60:
            self.used = 0.0
            return True
        return False


# ------------------------------------------------------------------ case files
class CaseFile:
    """Every kick is a 'case'. Stored as JSON lines; optionally mirrored to sync."""

    def __init__(self, cfg, path=CASES_PATH, sync=None):
        self.cfg, self.path, self.sync = cfg, path, sync

    def local(self):
        if not os.path.exists(self.path):
            return []
        with open(self.path, encoding="utf-8") as f:
            return [json.loads(l) for l in f if l.strip()]

    def all(self):
        """Local cases merged with cases from your other devices."""
        cases = {c["id"]: c for c in self.local()}
        if self.sync and self.sync.enabled:
            for c in self.sync.fetch():
                cases.setdefault(c.get("id"), c)
        return sorted(cases.values(), key=lambda c: c["ts"])

    def next_number(self):
        return len(self.all()) + 1

    def open_case(self, app, minutes, verdict="kicked", reason=""):
        now = dt.datetime.now()
        case = {
            "id": f"{self.cfg['device_name']}-{int(now.timestamp() * 1000)}-{random.randint(100, 999)}",
            "n": self.next_number(),
            "ts": now.isoformat(timespec="seconds"),
            "device": self.cfg["device_name"],
            "app": app,
            "minutes": round(minutes, 1),
            "verdict": verdict,          # kicked | denied | allowed
            "reason": reason,
        }
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps(case) + "\n")
        if self.sync and self.sync.enabled:
            self.sync.push_async(case)
        return case


def weekly_summary(cases, today=None):
    today = today or dt.date.today()
    start = today - dt.timedelta(days=6)
    week = [c for c in cases if dt.date.fromisoformat(c["ts"][:10]) >= start]
    kicks = [c for c in week if c["verdict"] in ("kicked", "denied", "rotting", "game over")]
    by_app = collections.Counter(c["app"] for c in kicks)
    by_hour = collections.Counter(int(c["ts"][11:13]) for c in kicks)
    by_device = collections.Counter(c["device"] for c in kicks)
    by_day = collections.Counter(c["ts"][:10] for c in kicks)
    worst_hour = by_hour.most_common(1)[0][0] if by_hour else None
    return {
        "total": len(kicks),
        "minutes": round(sum(c["minutes"] for c in kicks)),
        "allowed": sum(1 for c in week if c["verdict"] in ("allowed", "watching")),
        "by_app": by_app.most_common(),
        "by_device": by_device.most_common(),
        "worst_app": by_app.most_common(1)[0][0] if by_app else None,
        "worst_hour": worst_hour,
        "days": [((start + dt.timedelta(days=i)).isoformat(),
                  by_day.get((start + dt.timedelta(days=i)).isoformat(), 0)) for i in range(7)],
        "cases": list(reversed(week))[:25],
    }


def hour_label(h):
    if h is None:
        return "-"
    return f"{(h % 12) or 12} {'am' if h < 12 else 'pm'}"


def write_report(summary, character_name, path=REPORT_PATH):
    s = summary
    peak = max([n for _, n in s["days"]] + [1])
    bars = "".join(
        f'<div class="bar"><div style="height:{int(90 * n / peak)}px"></div>'
        f'<span>{dt.date.fromisoformat(d).strftime("%a")}</span><b>{n}</b></div>'
        for d, n in s["days"])
    rows = "".join(
        f"<tr><td>#{c.get('n', '?')}</td><td>{html.escape(c['ts'][:16].replace('T', ' '))}</td>"
        f"<td>{html.escape(c['device'])}</td><td>{html.escape(c['app'])}</td>"
        f"<td>{c['minutes']} min</td><td class='{c['verdict']}'>{c['verdict']}</td>"
        f"<td>{html.escape(c.get('reason', ''))}</td></tr>"
        for c in s["cases"])
    apps = "".join(f"<li>{html.escape(a)} <b>{n}</b></li>" for a, n in s["by_app"]) or "<li>None</li>"
    devs = "".join(f"<li>{html.escape(d)} <b>{n}</b></li>" for d, n in s["by_device"]) or "<li>None</li>"
    page = f"""<!doctype html><meta charset="utf-8"><title>Bonk case report</title>
<style>
body{{font:15px system-ui,sans-serif;background:#f3efe6;color:#2a2320;margin:0;padding:32px}}
.wrap{{max-width:860px;margin:auto}} h1{{font-size:28px;margin:0}} .sub{{color:#7a6a5a;margin:4px 0 24px}}
.stamp{{display:inline-block;border:3px solid #b3261e;color:#b3261e;padding:2px 10px;transform:rotate(-4deg);font-weight:800;letter-spacing:2px}}
.grid{{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:20px}}
.card{{background:#fffdf8;border:1px solid #e2d9c8;border-radius:10px;padding:14px}}
.card b{{display:block;font-size:26px}} .card span{{color:#7a6a5a;font-size:13px}}
.chart{{display:flex;gap:10px;align-items:flex-end;height:130px}} .bar{{flex:1;text-align:center;font-size:12px;color:#7a6a5a}}
.bar div{{background:#8a6d3b;border-radius:4px 4px 0 0;margin:0 auto 4px;width:60%}} .bar b{{display:block;color:#2a2320}}
ul{{margin:0;padding-left:18px}} table{{width:100%;border-collapse:collapse;font-size:13px}}
td{{border-top:1px solid #e2d9c8;padding:6px 4px}} .kicked,.denied{{color:#b3261e}} .allowed{{color:#2e7d32}}
.two{{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:20px}}
</style><div class="wrap">
<span class="stamp">CONFIDENTIAL</span>
<h1>Weekly investigation report</h1>
<p class="sub">Filed by {html.escape(character_name)} on {dt.date.today().strftime("%d %b %Y")}</p>
<div class="grid">
<div class="card"><b>{s['total']}</b><span>cases this week</span></div>
<div class="card"><b>{s['minutes']} min</b><span>scrolled before getting caught</span></div>
<div class="card"><b>{html.escape(s['worst_app'] or '-')}</b><span>prime suspect</span></div>
<div class="card"><b>{hour_label(s['worst_hour'])}</b><span>most dangerous hour</span></div>
</div>
<div class="card" style="margin-bottom:20px"><div class="chart">{bars}</div></div>
<div class="two"><div class="card"><span>By app</span><ul>{apps}</ul></div>
<div class="card"><span>By device</span><ul>{devs}</ul></div></div>
<div class="card"><span>Case log (allowed passes: {s['allowed']})</span>
<table>{rows or '<tr><td>No cases. Clean record.</td></tr>'}</table></div>
</div>"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(page)
    return path


# ------------------------------------------------------------------ sync
class Sync:
    """Cross-device case sync through a Firebase Realtime Database (REST, no SDK).
    Both phone and laptop write to <sync_url>/bonk/<sync_code>/cases."""

    def __init__(self, cfg):
        self.url = cfg.get("sync_url", "").rstrip("/")
        self.code = re.sub(r"[^A-Za-z0-9_-]", "", cfg.get("sync_code", ""))
        self.enabled = bool(self.url and self.code)

    def _endpoint(self, suffix=""):
        return f"{self.url}/bonk/{self.code}/cases{suffix}.json"

    def push(self, case):
        req = urllib.request.Request(self._endpoint("/" + case["id"]), method="PUT",
                                     data=json.dumps(case).encode(),
                                     headers={"Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=6).read()

    def push_async(self, case):
        threading.Thread(target=lambda: self._safe(self.push, case), daemon=True).start()

    def fetch(self):
        try:
            with urllib.request.urlopen(self._endpoint(), timeout=6) as r:
                data = json.loads(r.read() or b"null") or {}
            return list(data.values())
        except Exception:
            return []

    @staticmethod
    def _safe(fn, *a):
        try:
            fn(*a)
        except Exception:
            pass


# ------------------------------------------------------------------ interrogation
GOOD_WORDS = {"message", "messages", "dm", "dms", "reply", "post", "posting", "upload", "class",
              "assignment", "work", "project", "call", "order", "ticket", "lecture", "tutorial",
              "notes", "group", "event", "club", "meeting", "client", "check", "verify", "otp"}
BAD_WORDS = {"bored", "boring", "scroll", "scrolling", "reels", "reel", "shorts", "timepass",
             "nothing", "idk", "dunno", "chill", "relax", "fun", "vibe", "just"}


VAGUE = {"idk", "anything", "something", "nothing", "whatever", "random", "dunno", "bored", "scroll",
         "scrolling", "browse", "browsing", "rot", "rotting", "dont", "know", "just", "looking"}


def named_something(answer):
    """Streaming check: did they name an actual thing to watch?"""
    words = [w for w in re.findall(r"[a-z0-9']+", answer.lower()) if w not in VAGUE]
    return len(answer.strip()) >= 2 and len(words) >= 1


MEAL_WORDS = {"eat", "eating", "lunch", "dinner", "breakfast", "meal", "food", "snack", "snacking"}


def is_meal(reason):
    return bool(MEAL_WORDS & set(re.findall(r"[a-z']+", reason.lower())))


def offline_verdict(reason):
    """Rule-based judge used when there's no API key or the AI call fails."""
    words = re.findall(r"[a-z']+", reason.lower())
    if len(words) < 3 or BAD_WORDS & set(words):
        return False
    return bool(GOOD_WORDS & set(words))


def interrogate(reason, app, persona, cfg):
    """Returns (allowed, reply, pass_minutes). Meals always get a meal pass; otherwise
    Gemini judges if a key is set, else offline rules."""
    if is_meal(reason):
        m = cfg["meal_pass_minutes"]
        return True, line(persona, "meal", **{"pass": m}), m
    pass_min = cfg["pass_minutes"]
    key = cfg.get("gemini_api_key", "").strip()
    if key:
        try:
            prompt = (
                "You are Bonk, an anti-doomscrolling bot with this personality: "
                f"{persona['label']}. The user tried to open {app} shortly after being kicked out "
                f"for scrolling too long. Their reason: \"{reason}\".\n"
                "Allow only specific, purposeful reasons (replying to someone, posting, work, "
                "class, a quick lookup). Deny boredom, vague answers, or 'just one more'.\n"
                f"If you allow, they get {pass_min} minutes. Reply in JSON: "
                '{"allow": true|false, "reply": "one short in-character sentence, max 14 words"}')
            body = {"contents": [{"parts": [{"text": prompt}]}],
                    "generationConfig": {"responseMimeType": "application/json", "temperature": 0.7}}
            req = urllib.request.Request(
                f"https://generativelanguage.googleapis.com/v1beta/models/{cfg['gemini_model']}:generateContent",
                data=json.dumps(body).encode(), method="POST",
                headers={"Content-Type": "application/json", "x-goog-api-key": key})
            with urllib.request.urlopen(req, timeout=12) as r:
                data = json.loads(r.read())
            text = data["candidates"][0]["content"]["parts"][0]["text"]
            verdict = json.loads(text)
            allowed = bool(verdict.get("allow"))
            reply = str(verdict.get("reply", "")).strip()[:120]
            if not reply:
                reply = line(persona, "allow" if allowed else "deny", **{"pass": pass_min})
            return allowed, reply, pass_min
        except Exception:
            pass
    allowed = offline_verdict(reason)
    return allowed, line(persona, "allow" if allowed else "deny", **{"pass": pass_min}), pass_min
