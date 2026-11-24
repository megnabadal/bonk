"""Run: python -m pytest tests  (or: python tests/test_core.py)"""
import datetime as dt
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "windows"))
import bonk_core as core  # noqa: E402


def cfg():
    c = dict(core.DEFAULT_CONFIG)
    c["device_name"] = "laptop"
    return c


def test_tracker_limits_grace_strike_and_pass():
    t = core.Tracker(cfg())
    assert not t.add(1000, 100)
    assert t.add(1001, 20)                  # 120 s = 2 min limit
    t.kicked(1001)
    assert t.in_strike(1002)
    assert not t.add(1002, 20) and t.add(1003, 10)   # 30 s strike limit
    t.grant_pass(1004)
    assert not t.add(1005, 999)             # pass: no counting
    t2 = core.Tracker(cfg())
    t2.add(0, 250); t2.add(200, 1)          # gap > 60 s grace resets
    assert t2.used == 1


def test_lines_fill_placeholders():
    p = {"caught": ["Case #{n} on {app}, {mins} min"], "done": "x"}
    assert core.line(p, "caught", n=3, app="Instagram", mins=7) == "Case #3 on Instagram, 7 min"
    assert core.line({"a": "hi {nope}"}, "a") == "hi "


def test_offline_verdict():
    assert core.offline_verdict("need to reply to a dm from my club")
    assert not core.offline_verdict("just bored lol")
    assert not core.offline_verdict("dm")


def test_cases_and_report():
    with tempfile.TemporaryDirectory() as d:
        cf = core.CaseFile(cfg(), path=os.path.join(d, "cases.jsonl"))
        a = cf.open_case("Instagram", 6.2)
        b = cf.open_case("YouTube", 3, "denied", "bored")
        cf.open_case("Instagram", 0, "allowed", "reply to dm")
        assert (a["n"], b["n"]) == (1, 2)
        s = core.weekly_summary(cf.all())
        assert s["total"] == 2 and s["allowed"] == 1 and s["worst_app"] == "Instagram"
        path = core.write_report(s, "Detective Bonk", path=os.path.join(d, "r.html"))
        assert "Weekly investigation report" in open(path, encoding="utf-8").read()


def test_characters_present():
    chars = core.list_characters()
    assert len(chars) >= 8
    for c in chars:
        spec = core.load_character(c["id"])
        assert os.path.exists(os.path.join(spec["frames_dir"], "peace0.png"))
        assert "caught" in spec["persona"]


def test_classify_apps():
    c = cfg()
    assert core.classify("Instagram - Google Chrome", c) == "rot"
    assert core.classify("cats #shorts - YouTube - Google Chrome", c) == "rot"
    assert core.classify("YouTube - Google Chrome", c) == "rot"
    assert core.classify("Some movie trailer - YouTube - Google Chrome", c) == "watch"
    assert core.classify("DSA lecture 3 - YouTube - Google Chrome", c) == "safe"
    assert core.classify("JioHotstar - Google Chrome", c) == "watch"
    assert core.classify("Spotify - Web Player", c) == "safe"
    assert core.classify("Gmail - Google Chrome", c) is None
    assert core.named_something("Panchayat season 3") and not core.named_something("idk anything")


def test_meal_pass_and_reminder():
    p = {"meal": "Eat well. {pass} min", "deny": "no", "allow": "ok", "label": "x"}
    ok, reply, mins = core.interrogate("eating lunch", "YouTube", p, cfg())
    assert ok and mins == 20 and "20" in reply
    ok, _, _ = core.interrogate("just bored", "YouTube", p, cfg())
    assert not ok
    r = core.Reminder(cfg())
    assert not r.add(29 * 60, True) and r.add(60, True)        # 30 min -> nudge
    r.add(10 * 60, True); r.add(130, False)                     # 2+ min break resets
    assert r.used == 0


def test_games():
    c = cfg()
    assert core.is_game("valorant.exe", r"C:\Riot Games\VALORANT\live\VALORANT.exe", c)
    assert core.is_game("somegame.exe", r"D:\SteamLibrary\steamapps\common\Thing\somegame.exe", c)
    assert not core.is_game("chrome.exe", r"C:\Program Files\Google\Chrome\chrome.exe", c)
    g = core.GameTracker(c)
    d = "2026-11-24"
    assert g.add(0, 44 * 60, True, d) is None
    assert g.add(1, 60, True, d) == "warn"            # 45-min sitting -> 2 minutes to finish
    assert g.add(60, 60, True, d) is None
    assert g.add(121, 1, True, d) == "kick"
    g.kicked(121, d)
    assert g.add(200, 1, True, d) == "kick"           # cooldown: reopening gets closed
    g2 = core.GameTracker(c)
    for i in range(3):                                # three 40-min sittings with breaks
        g2.add(i * 10000, 40 * 60, True, d); g2.add(i * 10000 + 1, 400, False, d)
    assert g2.add(30001, 1, True, d) == "warn" and g2.reason == "daily"
    assert core.daily_label(120) == "2 hours"


def test_sync_disabled_without_config():
    assert not core.Sync(cfg()).enabled


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn(); print("ok", name)
