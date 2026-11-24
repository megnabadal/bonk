"""
Bonk - an anti-doomscroll bot with a personality.

When you stay too long on a blocked site/app, your Bonk character walks onto the
screen, opens a case file, closes the tab/app, flashes a peace sign and leaves.
Go back too soon and it interrogates you first (AI-judged with Gemini, or offline rules).

Windows only. Python 3.9+. Standard library only (tkinter + ctypes).
Characters live in ./characters (rendered by tools/render.js).

    pythonw bonk.py             run silently in the background (or start_bonk.bat)
    python  bonk.py --test      the character comes in 3 s (switch to an Instagram tab!)
    python  bonk.py --ask       preview the interrogation window
    python  bonk.py --watch     preview the "watching or rotting?" check
    python  bonk.py --remind    preview the 30-minute break reminder
    python  bonk.py --game      preview the "2 minutes to finish your match" warning
    python  bonk.py --report    open your weekly investigation report
    python  bonk.py --testai    check that your Gemini key works
    python  bonk.py --characters  list characters (set "character" in config.json)
"""
import ctypes
import ctypes.wintypes as wt
import math
import os
import sys
import threading
import time
import tkinter as tk
import webbrowser

import bonk_core as core

# ---------------------------------------------------------------- Win32 bits
user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

GWL_EXSTYLE = -20
WS_EX_LAYERED = 0x00080000
WS_EX_TRANSPARENT = 0x00000020   # click-through
WS_EX_TOOLWINDOW = 0x00000080    # no taskbar button
WS_EX_NOACTIVATE = 0x08000000    # never steals focus
WM_CLOSE = 0x0010
VK_CONTROL, VK_W = 0x11, 0x57
KEYEVENTF_KEYUP = 0x2
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def foreground(full=False):
    """Return (hwnd, window title, exe name) of the active window (+ exe path if full)."""
    hwnd = user32.GetForegroundWindow()
    n = user32.GetWindowTextLengthW(hwnd)
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    pid = wt.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    exe, path = "", ""
    h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid.value)
    if h:
        size = wt.DWORD(1024)
        pbuf = ctypes.create_unicode_buffer(size.value)
        if kernel32.QueryFullProcessImageNameW(h, 0, pbuf, ctypes.byref(size)):
            path = pbuf.value
            exe = os.path.basename(path).lower()
        kernel32.CloseHandle(h)
    return (hwnd, buf.value, exe, path) if full else (hwnd, buf.value, exe)


def close_target(hwnd, exe, cfg):
    """Browser -> close the current tab (Ctrl+W). Anything else -> close the window."""
    if exe in cfg["browsers"]:
        user32.SetForegroundWindow(hwnd)
        user32.keybd_event(VK_CONTROL, 0, 0, 0)
        user32.keybd_event(VK_W, 0, 0, 0)
        user32.keybd_event(VK_W, 0, KEYEVENTF_KEYUP, 0)
        user32.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, 0)
    else:
        user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)


def make_ghost(tk_window):
    """Click-through, never-focused, no taskbar entry."""
    hwnd = user32.GetParent(tk_window.winfo_id())
    style = user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
    user32.SetWindowLongW(hwnd, GWL_EXSTYLE,
                          style | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE)


# ---------------------------------------------------------------- frames
class Frames:
    def __init__(self, folder):
        self.images = {f[:-4]: tk.PhotoImage(file=os.path.join(folder, f))
                      for f in os.listdir(folder) if f.endswith(".png")}

    def __getitem__(self, name):
        return self.images.get(name) or self.images["idle"]


def pose_at(t, walk_in, talk, reach, bye):
    two_pi = 2 * math.pi
    if t < walk_in:          # side view, walking in from the right
        return f"walkL{int((t * 16 % two_pi) / two_pi * 8) % 8}"
    if t >= bye:             # turns and walks off to the right
        return f"walkR{int((t * 16 % two_pi) / two_pi * 8) % 8}"
    if t < talk:
        return "blink" if (t % 2.2) < 0.15 else "idle"
    if t < reach:
        return f"reach{min(5, int((t - talk) / (reach - talk) * 6))}"
    return f"peace{int((t * 6 % two_pi) / two_pi * 8) % 8}"


def bubble(canvas, s, text, tag="bubble"):
    canvas.delete(tag)
    if not text:
        return
    x0, y0, x1, y1, r = 20 * s, 2 * s, 240 * s, 46 * s, 14 * s
    pts = [x0 + r, y0, x1 - r, y0, x1, y0, x1, y0 + r, x1, y1 - r, x1, y1, x1 - r, y1,
           138 * s, y1, 128 * s, 56 * s, 118 * s, y1,
           x0 + r, y1, x0, y1, x0, y1 - r, x0, y0 + r, x0, y0]
    canvas.create_polygon(pts, smooth=True, fill="white", outline="#3a2a40", width=2, tags=tag)
    canvas.create_text(130 * s, 24 * s, text=text, width=200 * s, justify="center",
                       font=("Segoe UI", 11, "bold"), fill="#3a2a40", tags=tag)


# ---------------------------------------------------------------- the kick
class Bot:
    S = 1.0                  # bubble scale; the character frames are rendered small (0.8x)
    W, H = int(260 * S), int(240 * S)
    KEY = "#ff00fe"
    WALK_IN, TALK, REACH, BYE, WALK_OUT = 1.4, 2.8, 3.3, 5.0, 6.2

    def __init__(self, root, frames, message, done_text, on_grab, on_done):
        # on_grab=None makes it a friendly reminder: walk in, talk, wave, leave
        self.root, self.frames = root, frames
        self.message, self.done_text = message, done_text
        self.on_grab, self.on_done = on_grab, on_done
        self.grabbed = False

        self.top = tk.Toplevel(root)
        self.top.overrideredirect(True)
        self.top.attributes("-topmost", True)
        self.top.config(bg=self.KEY)
        self.top.attributes("-transparentcolor", self.KEY)
        self.c = tk.Canvas(self.top, width=self.W, height=self.H, bg=self.KEY, highlightthickness=0)
        self.c.pack()
        self.img = self.c.create_image(self.W // 2, self.H, anchor="s", image=frames["idle"])

        sw, sh = root.winfo_screenwidth(), root.winfo_screenheight()
        self.y = sh - self.H - 40
        self.x_off = sw + 10
        self.x_mid = sw // 2 - self.W // 2
        self.top.geometry(f"{self.W}x{self.H}+{self.x_off}+{self.y}")
        self.top.update_idletasks()
        make_ghost(self.top)
        self.t0 = time.time()
        self.frame()

    def frame(self):
        t = time.time() - self.t0
        if t < self.WALK_IN:
            p = min(1.0, t / self.WALK_IN)
            x = self.x_off + (self.x_mid - self.x_off) * (1 - (1 - p) ** 3)
        elif t < self.BYE:
            x = self.x_mid
        else:
            p = (t - self.BYE) / (self.WALK_OUT - self.BYE)
            x = self.x_mid + (self.x_off - self.x_mid) * p * p
        self.top.geometry(f"+{int(x)}+{self.y}")

        if t >= self.REACH and not self.grabbed:
            self.grabbed = True
            if self.on_grab:
                self.on_grab()

        self.c.itemconfigure(self.img, image=self.frames[pose_at(t, self.WALK_IN, self.TALK, self.REACH, self.BYE)])
        text = self.message if self.WALK_IN <= t < self.REACH else (self.done_text if self.REACH <= t < self.BYE else None)
        text = text or None
        bubble(self.c, self.S, text)

        if t < self.WALK_OUT:
            self.root.after(16, self.frame)
        else:
            self.top.destroy()
            self.on_done()


# ---------------------------------------------------------------- the interrogation
class Interrogation:
    """A small focused window: the character asks why you're back, you answer."""

    def __init__(self, root, frames, persona, app, cfg, on_verdict, mode="excuse"):
        self.root, self.cfg, self.persona, self.app = root, cfg, persona, app
        self.on_verdict, self.done, self.mode = on_verdict, False, mode
        self.pass_minutes = None
        watch = mode == "watch"

        self.top = tk.Toplevel(root)
        self.top.title("Bonk")
        self.top.attributes("-topmost", True)
        self.top.configure(bg="#fffdf8")
        self.top.resizable(False, False)
        self.top.protocol("WM_DELETE_WINDOW", self.give_up)

        tk.Label(self.top, image=frames["idle"], bg="#fffdf8").pack(side="left", padx=8, pady=8)
        right = tk.Frame(self.top, bg="#fffdf8")
        right.pack(side="left", padx=(0, 16), pady=16, fill="both")
        self.q = tk.Label(right, text=core.line(persona, "watch_ask" if watch else "ask", app=app), bg="#fffdf8", fg="#3a2a40",
                          font=("Segoe UI", 13, "bold"), wraplength=300, justify="left")
        self.q.pack(anchor="w")
        self.entry = tk.Entry(right, font=("Segoe UI", 12), width=32)
        self.entry.pack(anchor="w", pady=10)
        self.entry.bind("<Return>", lambda e: self.submit())
        row = tk.Frame(right, bg="#fffdf8")
        row.pack(anchor="w")
        self.ok = tk.Button(row, text="I'm watching this" if watch else "Plead my case", command=self.submit)
        self.ok.pack(side="left")
        tk.Button(row, text="Honestly? Just rotting" if watch else "Fine, I'll leave",
                  command=self.give_up).pack(side="left", padx=8)
        if watch:
            tk.Label(right, text="Type the title you're watching.", bg="#fffdf8", fg="#7a6380").pack(anchor="w", pady=(6, 0))

        self.top.update_idletasks()
        w, h = self.top.winfo_width(), self.top.winfo_height()
        self.top.geometry(f"+{(self.top.winfo_screenwidth() - w) // 2}+{(self.top.winfo_screenheight() - h) // 2}")
        self.top.lift()
        self.top.focus_force()
        self.entry.focus_set()

    def submit(self):
        reason = self.entry.get().strip()
        if not reason or self.done:
            return
        if self.mode == "watch":
            ok = core.named_something(reason)
            reply = (core.line(self.persona, "watch_ok", title=reason) if ok
                     else core.line(self.persona, "watch_no", app=self.app))
            self.ok.config(state="disabled")
            return self.show(ok, reply, reason)
        self.ok.config(state="disabled", text="Judging...")

        def work():
            allowed, reply, minutes = core.interrogate(reason, self.app, self.persona, self.cfg)
            self.pass_minutes = minutes
            self.root.after(0, lambda: self.show(allowed, reply, reason))

        threading.Thread(target=work, daemon=True).start()

    def show(self, allowed, reply, reason):
        self.q.config(text=reply, fg="#2e7d32" if allowed else "#b3261e")
        self.root.after(1800, lambda: self.finish(allowed, reason))

    def give_up(self):
        self.finish(False, "(gave up)")

    def finish(self, allowed, reason):
        if self.done:
            return
        self.done = True
        self.top.destroy()
        self.on_verdict(allowed, reason, self.pass_minutes)


# ---------------------------------------------------------------- main loop
def main():
    cfg = core.load_config()
    if "--testai" in sys.argv:
        ch = core.load_character(cfg["character"])
        ok, reply, _ = core.interrogate("replying to my friend's message", "Instagram", ch["persona"], cfg)
        print(core.LAST_AI_STATUS)
        print("Sample:", reply)
        return
    if "--characters" in sys.argv:
        for c in core.list_characters():
            print(f"{c['id']:18} {c['name']}")
        return

    sync = core.Sync(cfg)
    cases = core.CaseFile(cfg, sync=sync)
    character = core.load_character(cfg["character"])
    persona = character["persona"]

    if "--report" in sys.argv:
        path = core.write_report(core.weekly_summary(cases.all()), character.get("name", "Bonk"))
        webbrowser.open("file:///" + path.replace("\\", "/"))
        return

    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    root = tk.Tk()
    root.withdraw()
    frames = Frames(character["frames_dir"])
    tracker = core.Tracker(cfg)
    reminder = core.Reminder(cfg)
    games = core.GameTracker(cfg)
    state = {"busy": False, "last": time.time(), "last_key": None}

    def kick(hwnd, title, exe, verdict="kicked", reason=""):
        app = core.app_name(title, cfg)
        minutes = tracker.used / 60
        case = cases.open_case(app, minutes, verdict, reason)
        if verdict == "rotting":
            message = core.line(persona, "watch_no", app=app)
        else:
            message = core.line(persona, "caught", n=case["n"], app=app, mins=round(minutes))

        def grab():
            h2, t2, _ = foreground()
            if h2 == hwnd and core.classify(t2, cfg) in ("rot", "watch"):
                close_target(hwnd, exe, cfg)

        def done():
            state["busy"] = False
            tracker.kicked(time.time())

        state["busy"] = True
        Bot(root, frames, message, core.line(persona, "done"), grab, done)

    def ask(hwnd, title, exe, mode="excuse"):
        app = core.app_name(title, cfg)
        state["busy"] = True

        def verdict(allowed, reason, minutes=None):
            state["busy"] = False
            now = time.time()
            if allowed and mode == "watch":
                tracker.grant_watch_pass(app, now)
                cases.open_case(app, 0, "watching", reason)
            elif allowed:
                tracker.grant_pass(now, minutes)
                cases.open_case(app, 0, "allowed", reason)
            else:
                kick(hwnd, title, exe, "rotting" if mode == "watch" else "denied", reason)

        Interrogation(root, frames, persona, app, cfg, verdict, mode)

    def remind():
        state["busy"] = True

        def done():
            state["busy"] = False

        Bot(root, frames, core.line(persona, "remind", mins=cfg["remind_minutes"]),
            "Take a breather!", None, done)

    def game_event(event, hwnd, title, exe):
        """Gaming: warn (2 minutes to finish), then close the game."""
        state["busy"] = True
        daily = core.daily_label(cfg["game_daily_minutes"])
        app = title[:30] or exe

        def done():
            state["busy"] = False

        if event == "warn":
            Bot(root, frames, core.line(persona, "game_warn"), "", None, done)
            return
        line_key = "game_daily" if games.reason == "daily" else "game_kick"
        cases.open_case(app, games.session / 60, "game over", games.reason)

        def grab():
            h2, _, _ = foreground()
            if h2 == hwnd:
                user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)

        def after():
            games.kicked(time.time(), time.strftime("%Y-%m-%d"))
            done()

        Bot(root, frames, core.line(persona, line_key, daily=daily), core.line(persona, "done"), grab, after)

    def tick():
        now = time.time()
        dt_s, state["last"] = now - state["last"], now
        if not state["busy"]:
            hwnd, title, exe, path = foreground(full=True)
            gaming = core.is_game(exe, path, cfg)
            event = games.add(now, dt_s, gaming, time.strftime("%Y-%m-%d"))
            if event:
                game_event(event, hwnd, title, exe)
                root.after(1000, tick)
                return
            if gaming:
                root.after(1000, tick)
                return
            kind = core.classify(title, cfg)
            if reminder.add(dt_s, kind in ("rot", "watch")):
                remind()
                root.after(1000, tick)
                return
            app = core.app_name(title, cfg)
            just_opened = (kind, app) != state["last_key"]
            state["last_key"] = (kind, app)
            if kind == "watch":
                if just_opened and not tracker.has_watch_pass(app, now):
                    ask(hwnd, title, exe, "watch")
            elif kind == "rot":
                if (just_opened and cfg["interrogate"] and tracker.in_strike(now)
                        and not tracker.has_pass(now)):
                    ask(hwnd, title, exe)
                elif tracker.add(now, dt_s):
                    kick(hwnd, title, exe)
        root.after(1000, tick)

    if "--test" in sys.argv:
        print("Bonk arrives in 3 seconds - switch to a distracting tab now.")
        root.after(3000, lambda: kick(*foreground()))
    if "--ask" in sys.argv:
        root.after(500, lambda: ask(*foreground()))
    if "--game" in sys.argv:
        root.after(500, lambda: game_event("warn", *foreground()))
    if "--remind" in sys.argv:
        root.after(500, remind)
    if "--watch" in sys.argv:
        root.after(500, lambda: ask(*foreground(), mode="watch"))
    root.after(1000, tick)
    root.mainloop()


if __name__ == "__main__":
    main()
