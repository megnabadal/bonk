package com.megna.bonk;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;

/**
 * Watches the foreground app and decides whether you're "rotting":
 *  - ROT apps (Instagram, YouTube...): time on feeds / Reels / Shorts counts toward the limit.
 *    On YouTube only Shorts or endless swiping count, so long videos and lectures are left alone.
 *  - WATCH apps (JioHotstar, Netflix...): on open, Bonk asks "watching something, or rotting?"
 *  - SAFE apps (Spotify, podcasts, learning apps): never touched.
 *  - Any other app: if "detect anywhere" is on, fast repeated swiping counts as rotting.
 * Only view IDs and event types are read, never on-screen text.
 */
public class BonkService extends AccessibilityService {

    private static final long GRACE_MS = 60_000;          // short breaks don't reset the timer
    private static final long STRIKE_LIMIT_MS = 30_000;   // limit right after a kick
    private static final long SWIPE_WINDOW_MS = 60_000;
    private static final int SWIPES_PER_MIN = 6;           // this many separate swipes a minute = rotting
    private static final long SWIPE_GAP_MS = 700;          // scroll events closer than this are one swipe

    /** View-ID fragments of short-form / endless-feed screens (best effort; apps change them). */
    private static final String[] SHORT_FORM_IDS = {"reel", "shorts", "clips_viewer", "clips_video", "short_video"};
    /** Screens that are not rotting even inside a rot app (e.g. Instagram DMs). */
    private static final String[] CALM_IDS = {"direct_thread", "direct_inbox", "row_thread_composer", "message_list"};

    static BonkService instance;
    static volatile boolean interrogating;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private BotView bot;

    private String currentPkg = "";
    private long usedMs, lastSeen, lastTick, lastScan, lastSwipe;
    private long phoneMs, offMs;                          // for the 30-minute reminder
    private long gameSessionMs, gameAwayMs, gameWarnedAt = -1;
    private boolean gameDailyReason;
    private static final long GAME_GRACE_MS = 2 * 60_000;     // time to finish the match
    private static final long GAME_BREAK_MS = 5 * 60_000;     // a break this long ends a sitting
    private boolean shortFormOnScreen, calmScreen;
    private final ArrayDeque<Long> swipes = new ArrayDeque<>();

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler.post(ticker);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")
                || pkg.contains("inputmethod") || pkg.contains("keyboard")) return;

        switch (e.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                onAppShown(pkg);
                break;
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                if (pkg.equals(currentPkg)) recordSwipe();
                break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                if (pkg.equals(currentPkg)) maybeScanScreen();
                break;
            default:
                break;
        }
    }

    private void onAppShown(String pkg) {
        boolean justOpened = !pkg.equals(currentPkg);
        currentPkg = pkg;
        if (!justOpened) return;
        swipes.clear();
        shortFormOnScreen = calmScreen = false;
        lastScan = 0;
        maybeScanScreen();
        if (bot != null || interrogating) return;

        Prefs.Kind kind = Prefs.kindOf(this, pkg);
        long now = System.currentTimeMillis();
        if (kind == Prefs.Kind.WATCH && now >= Prefs.watchPassUntil(this, pkg)) {
            ask(pkg, "watch");                                   // "watching something, or rotting?"
        } else if (kind == Prefs.Kind.ROT && Prefs.interrogate(this)
                && now < Prefs.strikeUntil(this) && now >= Prefs.passUntil(this)) {
            ask(pkg, "excuse");                                  // back right after a kick
        }
    }

    private void ask(String pkg, String mode) {
        interrogating = true;
        startActivity(new Intent(this, InterrogateActivity.class)
                .putExtra("pkg", pkg).putExtra("mode", mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    // ------------------------------------------------------------ rot detection
    private void recordSwipe() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSwipe > SWIPE_GAP_MS) swipes.addLast(now);
        lastSwipe = now;
        while (!swipes.isEmpty() && now - swipes.peekFirst() > SWIPE_WINDOW_MS) swipes.removeFirst();
    }

    private boolean swipingFast() {
        long now = SystemClock.elapsedRealtime();
        while (!swipes.isEmpty() && now - swipes.peekFirst() > SWIPE_WINDOW_MS) swipes.removeFirst();
        return swipes.size() >= SWIPES_PER_MIN;
    }

    /** Look for Reels/Shorts or DM screens by view ID, at most every 1.5 s. */
    private void maybeScanScreen() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastScan < 1500) return;
        lastScan = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        boolean[] found = new boolean[2];
        scan(root, 0, found);
        shortFormOnScreen = found[0];
        calmScreen = found[1];
    }

    private void scan(AccessibilityNodeInfo n, int depth, boolean[] found) {
        if (n == null || depth > 25 || (found[0] && found[1])) return;
        String id = n.getViewIdResourceName();
        if (id != null) {
            String low = id.toLowerCase();
            for (String s : SHORT_FORM_IDS) if (low.contains(s)) found[0] = true;
            for (String s : CALM_IDS) if (low.contains(s)) found[1] = true;
        }
        for (int i = 0; i < n.getChildCount(); i++) scan(n.getChild(i), depth + 1, found);
    }

    /** Is what's on screen right now rotting? */
    private boolean rotting() {
        Prefs.Kind kind = Prefs.kindOf(this, currentPkg);
        if (kind == Prefs.Kind.SAFE || kind == Prefs.Kind.WATCH || kind == Prefs.Kind.GAME) return false;
        if (kind == Prefs.Kind.ROT) {
            if (calmScreen) return false;                                   // DMs, messages
            if (currentPkg.equals("com.google.android.youtube")) return shortFormOnScreen || swipingFast();
            return true;                                                    // feeds, reels, stories
        }
        return Prefs.detectAnywhere(this) && swipingFast();                // unknown app
    }

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        long dt = lastTick == 0 ? 0 : now - lastTick;
        lastTick = now;

        if (bot != null || interrogating) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOn = pm == null || pm.isInteractive();

        // reminder: phone time in one sitting (music/podcasts/learning apps don't count);
        // 2+ minutes with the screen off starts a fresh sitting
        if (!screenOn) {
            offMs += dt;
            if (offMs >= 120_000) phoneMs = 0;
            return;
        }
        offMs = 0;
        Prefs.Kind kindNow = Prefs.kindOf(this, currentPkg);
        if (kindNow == Prefs.Kind.GAME) { gameTick(dt); return; }   // games have their own limits
        gameAwayMs += dt;
        if (gameAwayMs >= GAME_BREAK_MS) { gameSessionMs = 0; gameWarnedAt = -1; }
        if (kindNow != Prefs.Kind.SAFE) phoneMs += dt;
        if (Prefs.remind(this) && phoneMs >= Prefs.remindMinutes(this) * 60_000L) {
            phoneMs = 0;
            remind();
            return;
        }

        if (System.currentTimeMillis() < Prefs.passUntil(this)) { lastSeen = now; return; }
        if (!rotting()) return;

        if (now - lastSeen > GRACE_MS) usedMs = 0;
        usedMs += dt;
        lastSeen = now;

        boolean strike = System.currentTimeMillis() < Prefs.strikeUntil(this);
        long limit = strike ? STRIKE_LIMIT_MS : Prefs.limitMinutes(this) * 60_000L;
        if (usedMs >= limit) kick("kicked", "");
    }

    // ------------------------------------------------------------ the kick
    /** Walk in, open a case, send the user home. */
    void kick(String verdict, String reason) {
        if (bot != null || wm == null) return;
        Characters.Info ch = Characters.current(this);
        Characters.Persona persona = Characters.persona(this, ch.personality);
        String app = Prefs.appLabel(currentPkg);
        double minutes = usedMs / 60000.0;
        org.json.JSONObject c = Cases.open(this, app, minutes, verdict, reason);
        String msg = verdict.equals("rotting")
                ? persona.line("watch_no", Characters.vars("app", app))
                : persona.line("caught", Characters.vars("n", c.optInt("n", 1), "app", app, "mins", Math.round(minutes)));

        bot = new BotView(this, ch.id, msg, persona.line("done", null),
                () -> performGlobalAction(GLOBAL_ACTION_HOME),
                this::removeBot);
        addBot();
        usedMs = 0;
        Prefs.startStrike(this);
    }

    private void addBot() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                BotView.heightPx(this),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.y = (int) (80 * getResources().getDisplayMetrics().density);
        wm.addView(bot, lp);
        bot.start();
    }

    /** Gaming: after the sitting or daily limit, warn; 2 minutes later, close the game. */
    private void gameTick(long dt) {
        gameAwayMs = 0;
        long wall = System.currentTimeMillis();
        if (wall < Prefs.gameCooldownUntil(this)) { gameKick(); return; }     // reopened too soon
        Prefs.addGameMs(this, dt);
        gameSessionMs += dt;
        long now = SystemClock.elapsedRealtime();
        if (gameWarnedAt >= 0) {
            if (now - gameWarnedAt >= GAME_GRACE_MS) gameKick();
            return;
        }
        boolean daily = Prefs.gameMsToday(this) >= Prefs.gameDailyMinutes(this) * 60_000L;
        boolean session = gameSessionMs >= Prefs.gameSessionMinutes(this) * 60_000L;
        if (daily || session) {
            gameDailyReason = daily;
            gameWarnedAt = now;
            Characters.Info ch = Characters.current(this);
            Characters.Persona persona = Characters.persona(this, ch.personality);
            bot = new BotView(this, ch.id, persona.line("game_warn", null), "", null, this::removeBot);
            addBot();
        }
    }

    private void gameKick() {
        if (bot != null || wm == null) return;
        boolean daily = gameDailyReason || Prefs.gameMsToday(this) >= Prefs.gameDailyMinutes(this) * 60_000L;
        Characters.Info ch = Characters.current(this);
        Characters.Persona persona = Characters.persona(this, ch.personality);
        String app = Prefs.appLabel(currentPkg);
        if (System.currentTimeMillis() >= Prefs.gameCooldownUntil(this)) {
            Cases.open(this, app, gameSessionMs / 60000.0, "game over", daily ? "daily limit" : "session limit");
        }
        int dm = Prefs.gameDailyMinutes(this);
        String dailyText = (dm / 60 > 0 ? (dm / 60) + (dm / 60 == 1 ? " hour" : " hours") : "")
                + (dm % 60 > 0 ? " " + (dm % 60) + " min" : "");
        String msg = persona.line(daily ? "game_daily" : "game_kick", Characters.vars("daily", dailyText.trim()));
        bot = new BotView(this, ch.id, msg, persona.line("done", null),
                () -> performGlobalAction(GLOBAL_ACTION_HOME), this::removeBot);
        addBot();
        Prefs.setGameCooldown(this, daily);
        gameSessionMs = 0;
        gameWarnedAt = -1;
        gameDailyReason = false;
    }

    /** Preview for the settings screen. */
    void gameWarnTest() {
        if (bot != null || wm == null) return;
        Characters.Info ch = Characters.current(this);
        bot = new BotView(this, ch.id, Characters.persona(this, ch.personality).line("game_warn", null), "", null, this::removeBot);
        addBot();
    }

    /** A friendly nudge: walk in, talk, wave, walk out. Nothing gets closed. */
    void remind() {
        if (bot != null || wm == null) return;
        Characters.Info ch = Characters.current(this);
        Characters.Persona persona = Characters.persona(this, ch.personality);
        bot = new BotView(this, ch.id,
                persona.line("remind", Characters.vars("mins", Prefs.remindMinutes(this))),
                "Take a breather!", null, this::removeBot);
        addBot();
    }

    /** Called by the question screen. mode = "excuse" (after a kick) or "watch" (streaming app). */
    void onVerdict(String mode, boolean allowed, String reason, int passMinutes) {
        interrogating = false;
        String app = Prefs.appLabel(currentPkg);
        if (allowed) {
            if (mode.equals("watch")) {
                Prefs.grantWatchPass(this, currentPkg);
                Cases.open(this, app, 0, "watching", reason);
            } else {
                Prefs.grantPass(this, passMinutes > 0 ? passMinutes : Prefs.passMinutes(this));
                usedMs = 0;
                Cases.open(this, app, 0, "allowed", reason);
            }
        } else {
            handler.postDelayed(() -> kick(mode.equals("watch") ? "rotting" : "denied", reason), 300);
        }
    }

    private void removeBot() {
        if (bot != null) {
            try { wm.removeView(bot); } catch (Exception ignored) {}
            bot = null;
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        removeBot();
        instance = null;
        super.onDestroy();
    }
}
