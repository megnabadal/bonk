package com.megna.bonk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** All settings and small bits of shared state (pass / strike timers). */
final class Prefs {

    /** What Bonk does with an app. */
    enum Kind { ROT, WATCH, SAFE, GAME }

    static final class App {
        final String pkg, label; final Kind kind;
        App(String pkg, String label, Kind kind) { this.pkg = pkg; this.label = label; this.kind = kind; }
    }

    /** Known apps. ROT = time limit on feeds/shorts, WATCH = "watching or rotting?" check, SAFE = never touched. */
    static final Map<String, App> APPS = new LinkedHashMap<>();
    private static void add(String pkg, String label, Kind k) { APPS.put(pkg, new App(pkg, label, k)); }
    static {
        add("com.instagram.android", "Instagram", Kind.ROT);
        add("com.google.android.youtube", "YouTube (Shorts + endless browsing)", Kind.ROT);
        add("com.facebook.katana", "Facebook", Kind.ROT);
        add("com.snapchat.android", "Snapchat", Kind.ROT);
        add("com.reddit.frontpage", "Reddit", Kind.ROT);
        add("com.twitter.android", "X / Twitter", Kind.ROT);
        add("in.mohalla.video", "Moj", Kind.ROT);
        add("com.eterno.shortvideos", "Josh", Kind.ROT);
        add("in.mohalla.sharechat", "ShareChat", Kind.ROT);
        add("in.startv.hotstar", "JioHotstar", Kind.WATCH);
        add("com.netflix.mediaclient", "Netflix", Kind.WATCH);
        add("com.amazon.avod.thirdpartyclient", "Prime Video", Kind.WATCH);
        add("com.sonyliv", "SonyLIV", Kind.WATCH);
        add("com.graymatrix.did", "ZEE5", Kind.WATCH);
        add("com.mxtech.videoplayer.ad", "MX Player", Kind.WATCH);
        add("com.pubg.imobile", "BGMI", Kind.GAME);
        add("com.dts.freefireth", "Free Fire", Kind.GAME);
        add("com.dts.freefiremax", "Free Fire MAX", Kind.GAME);
        add("com.activision.callofduty.shooter", "Call of Duty: Mobile", Kind.GAME);
        add("com.supercell.brawlstars", "Brawl Stars", Kind.GAME);
        add("com.supercell.clashofclans", "Clash of Clans", Kind.GAME);
        add("com.supercell.clashroyale", "Clash Royale", Kind.GAME);
        add("jp.konami.pesam", "eFootball", Kind.GAME);
        add("com.ea.gp.fifamobile", "EA SPORTS FC Mobile", Kind.GAME);
        add("com.kiloo.subwaysurf", "Subway Surfers", Kind.GAME);
        add("com.king.candycrushsaga", "Candy Crush Saga", Kind.GAME);
        add("com.ludo.king", "Ludo King", Kind.GAME);
        add("com.mojang.minecraftpe", "Minecraft", Kind.GAME);
        add("com.roblox.client", "Roblox", Kind.GAME);
        add("com.miHoYo.GenshinImpact", "Genshin Impact", Kind.GAME);
        add("com.innersloth.spacemafia", "Among Us", Kind.GAME);
        add("com.miniclip.eightballpool", "8 Ball Pool", Kind.GAME);
        add("com.nianticlabs.pokemongo", "Pokémon GO", Kind.GAME);
        add("com.gameloft.android.ANMP.GloftA9HM", "Asphalt", Kind.GAME);
        add("com.imangi.templerun2", "Temple Run 2", Kind.GAME);
        add("com.fingersoft.hillclimb", "Hill Climb Racing", Kind.GAME);
        add("com.kitkagames.fallbuddies", "Stumble Guys", Kind.GAME);
        add("com.spotify.music", "Spotify", Kind.SAFE);
        add("com.google.android.apps.youtube.music", "YouTube Music", Kind.SAFE);
        add("au.com.shiftyjelly.pocketcasts", "Pocket Casts", Kind.SAFE);
        add("com.audible.application", "Audible", Kind.SAFE);
        add("com.duolingo", "Duolingo", Kind.SAFE);
        add("org.coursera.android", "Coursera", Kind.SAFE);
        add("com.udemy.android", "Udemy", Kind.SAFE);
        add("com.amazon.kindle", "Kindle", Kind.SAFE);
    }

    static String appLabel(String pkg) {
        App a = APPS.get(pkg);
        return a != null ? a.label.replaceAll(" \\(.*\\)", "") : pkg;
    }

    /** What to do with this package right now, honouring the user's on/off choices. */
    static Kind kindOf(Context c, String pkg) {
        App a = APPS.get(pkg);
        if (a == null) {
            // any app that tells Android it's a game counts as one (covers games not listed above)
            if (gameLimits(c) && isGameByCategory(c, pkg)) return Kind.GAME;
            return null;                                  // unknown app: only the swipe detector applies
        }
        if (a.kind == Kind.GAME) return gameLimits(c) ? Kind.GAME : Kind.SAFE;
        if (a.kind == Kind.SAFE) return Kind.SAFE;
        return enabled(c).contains(pkg) ? a.kind : Kind.SAFE;
    }

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("bonk", Context.MODE_PRIVATE);
    }

    // ---- limits & apps
    static int limitMinutes(Context c) { return sp(c).getInt("limit_minutes", 2); }
    static void setLimitMinutes(Context c, int m) { sp(c).edit().putInt("limit_minutes", Math.max(1, m)).apply(); }

    private static final Map<String, Boolean> GAME_CACHE = new java.util.HashMap<>();

    static boolean isGameByCategory(Context c, String pkg) {
        Boolean cached = GAME_CACHE.get(pkg);
        if (cached != null) return cached;
        boolean game = false;
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            game = ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME;
        } catch (Exception ignored) {}
        GAME_CACHE.put(pkg, game);
        return game;
    }

    // ---- games: per-sitting and daily limits, with 2 minutes to finish the match
    static boolean gameLimits(Context c) { return sp(c).getBoolean("game_limits", true); }
    static void setGameLimits(Context c, boolean on) { sp(c).edit().putBoolean("game_limits", on).apply(); }
    static int gameSessionMinutes(Context c) { return sp(c).getInt("game_session_minutes", 45); }
    static int gameDailyMinutes(Context c) { return sp(c).getInt("game_daily_minutes", 120); }
    static void setGameMinutes(Context c, int session, int daily) {
        sp(c).edit().putInt("game_session_minutes", Math.max(5, session)).putInt("game_daily_minutes", Math.max(10, daily)).apply();
    }
    private static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
    }
    static long gameMsToday(Context c) { return sp(c).getLong("game_ms_" + today(), 0); }
    static void addGameMs(Context c, long ms) { sp(c).edit().putLong("game_ms_" + today(), gameMsToday(c) + ms).apply(); }
    static long gameCooldownUntil(Context c) { return sp(c).getLong("game_cooldown_until", 0); }
    static void setGameCooldown(Context c, boolean dailyUsed) {
        long until;
        if (dailyUsed) {                                   // closed until midnight
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
            until = cal.getTimeInMillis();
        } else {
            until = System.currentTimeMillis() + 15 * 60_000L;
        }
        sp(c).edit().putLong("game_cooldown_until", until).apply();
    }

    /** ROT/WATCH apps the user has switched on (all of them by default). */
    static Set<String> enabled(Context c) {
        Set<String> def = new HashSet<>();
        for (App a : APPS.values()) if (a.kind == Kind.ROT || a.kind == Kind.WATCH) def.add(a.pkg);
        return new HashSet<>(sp(c).getStringSet("enabled_apps", def));
    }
    static void setEnabled(Context c, Set<String> pkgs) { sp(c).edit().putStringSet("enabled_apps", new HashSet<>(pkgs)).apply(); }

    /** Catch rotting in apps Bonk doesn't know, from fast repeated swiping. */
    static boolean detectAnywhere(Context c) { return sp(c).getBoolean("detect_anywhere", true); }
    static void setDetectAnywhere(Context c, boolean on) { sp(c).edit().putBoolean("detect_anywhere", on).apply(); }

    // ---- streaming "watch pass": after naming what you're watching
    static int watchHours(Context c) { return sp(c).getInt("watch_hours", 3); }
    static long watchPassUntil(Context c, String pkg) { return sp(c).getLong("watch_" + pkg, 0); }
    static void grantWatchPass(Context c, String pkg) {
        sp(c).edit().putLong("watch_" + pkg, System.currentTimeMillis() + watchHours(c) * 3_600_000L).apply();
    }

    // ---- character
    static String character(Context c) { return sp(c).getString("character", "detective-panda"); }
    static void setCharacter(Context c, String id) { sp(c).edit().putString("character", id).apply(); }

    // ---- interrogation
    static boolean interrogate(Context c) { return sp(c).getBoolean("interrogate", true); }
    static void setInterrogate(Context c, boolean on) { sp(c).edit().putBoolean("interrogate", on).apply(); }
    static int passMinutes(Context c) { return sp(c).getInt("pass_minutes", 5); }
    static String geminiKey(Context c) { return sp(c).getString("gemini_key", ""); }
    static String geminiModel(Context c) { return sp(c).getString("gemini_model", "gemini-3.8-flash"); }
    static void setGemini(Context c, String key, String model) {
        sp(c).edit().putString("gemini_key", key.trim())
                .putString("gemini_model", model.trim().isEmpty() ? "gemini-3.8-flash" : model.trim()).apply();
    }

    // ---- sync
    static String syncUrl(Context c) { return sp(c).getString("sync_url", ""); }
    static String syncCode(Context c) { return sp(c).getString("sync_code", ""); }
    static void setSync(Context c, String url, String code) {
        sp(c).edit().putString("sync_url", url.trim()).putString("sync_code", code.trim()).apply();
    }

    // ---- timers (wall clock, shared by the service and the interrogation screen)
    static long passUntil(Context c) { return sp(c).getLong("pass_until", 0); }
    static void grantPass(Context c, int minutes) {
        sp(c).edit().putLong("pass_until", System.currentTimeMillis() + minutes * 60_000L).apply();
    }
    static int mealPassMinutes(Context c) { return sp(c).getInt("meal_pass_minutes", 20); }

    // ---- gentle "you've been on your phone a while" reminder (never closes anything)
    static boolean remind(Context c) { return sp(c).getBoolean("remind", true); }
    static void setRemind(Context c, boolean on) { sp(c).edit().putBoolean("remind", on).apply(); }
    static int remindMinutes(Context c) { return sp(c).getInt("remind_minutes", 30); }
    static long strikeUntil(Context c) { return sp(c).getLong("strike_until", 0); }
    static void startStrike(Context c) {
        sp(c).edit().putLong("strike_until", System.currentTimeMillis() + 10 * 60_000L).apply();
    }
}
