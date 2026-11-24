package com.megna.bonk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.Map;

/**
 * The kick animation: the chosen character walks in, says its line, reaches up
 * (onGrab = press Home), flashes a peace sign with its done-line, and walks out.
 * Frames are pre-rendered PNGs in assets/characters/<id>; the speech bubble is drawn
 * here. Everything lives in a 260 x 240 "design box" scaled to screen density.
 */
class BotView extends View {

    private static final float BOX_W = 260, BOX_H = 240;
    private static final float WALK_IN = 1.4f, TALK = 2.8f, REACH = 3.3f, BYE = 5.0f, WALK_OUT = 6.2f;
    /** Where the cropped frames sit inside the design box (see tools/render.js ANDROID_CROP). */
    private static final RectF CROP = new RectF(60, 40, 215, 240);
    /** How big the character is drawn: 0.62 = small, so it doesn't cover your screen. */
    private static final float SIZE = 0.62f;
    /** The crop scaled down around the bottom centre of the box. */
    private static final RectF CHAR_AREA = new RectF(
            130 + (CROP.left - 130) * SIZE, 240 - (240 - CROP.top) * SIZE,
            130 + (CROP.right - 130) * SIZE, 240);
    private static final int INK = Color.parseColor("#3A2A40");

    private final String message, doneText;
    private final Runnable onGrab, onDone;
    private final float d;
    private final Map<String, Bitmap> frames;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bubbleRect = new RectF(15, CHAR_AREA.top - 60, 245, CHAR_AREA.top - 16);
    private final Path tail = new Path();

    private long startMs = -1;
    private boolean grabbed, done;

    static int heightPx(Context c) {
        return (int) (BOX_H * c.getResources().getDisplayMetrics().density);
    }

    BotView(Context c, String characterId, String message, String doneText, Runnable onGrab, Runnable onDone) {
        super(c);
        this.message = message;
        this.doneText = doneText;
        this.onGrab = onGrab;
        this.onDone = onDone;
        this.d = c.getResources().getDisplayMetrics().density;
        this.frames = Characters.frames(c, characterId);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(2);
        line.setColor(INK);
        fill.setColor(Color.WHITE);
        text.setColor(INK);
        text.setFakeBoldText(true);
        text.setTextSize(14 * d);
        tail.moveTo(120, bubbleRect.bottom - 1);
        tail.lineTo(130, CHAR_AREA.top - 4);
        tail.lineTo(140, bubbleRect.bottom - 1);
        tail.close();
    }

    void start() {
        startMs = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    private static float ease(float p) {
        p = Math.max(0, Math.min(1, p));
        return 1 - (1 - p) * (1 - p) * (1 - p);
    }

    static String pose(float t) {
        double twoPi = Math.PI * 2;
        if (t < WALK_IN) return "walkL" + ((int) ((t * 16 % twoPi) / twoPi * 8)) % 8;   // side view, walking in
        if (t >= BYE) return "walkR" + ((int) ((t * 16 % twoPi) / twoPi * 8)) % 8;     // turns and walks off
        if (t < TALK) return (t % 2.2f) < 0.15f ? "blink" : "idle";
        if (t < REACH) return "reach" + Math.min(5, (int) ((t - TALK) / (REACH - TALK) * 6));
        return "peace" + ((int) ((t * 6 % twoPi) / twoPi * 8)) % 8;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (startMs < 0) return;
        float t = (SystemClock.uptimeMillis() - startMs) / 1000f;

        float boxW = BOX_W * d;
        float off = getWidth() + 10 * d;
        float mid = (getWidth() - boxW) / 2f;
        float x;
        if (t < WALK_IN) x = off + (mid - off) * ease(t / WALK_IN);
        else if (t < BYE) x = mid;
        else { float p = (t - BYE) / (WALK_OUT - BYE); x = mid + (off - mid) * p * p; }

        if (t >= REACH && !grabbed) { grabbed = true; if (onGrab != null) onGrab.run(); }   // null = just a reminder

        c.save();
        c.translate(x, 0);
        c.scale(d, d);
        String bubble = (t >= WALK_IN && t < REACH) ? message : (t >= REACH && t < BYE) ? doneText : null;
        if (bubble != null && !bubble.isEmpty()) drawBubble(c, bubble);
        Bitmap frame = frames.get(pose(t));
        if (frame == null) frame = frames.get("idle");
        if (frame != null) c.drawBitmap(frame, null, CHAR_AREA, bitmapPaint);
        c.restore();

        if (t < WALK_OUT) {
            postInvalidateOnAnimation();
        } else if (!done) {
            done = true;
            post(onDone);
        }
    }

    private void drawBubble(Canvas c, String s) {
        c.drawRoundRect(bubbleRect, 12, 12, fill);
        c.drawRoundRect(bubbleRect, 12, 12, line);
        c.drawPath(tail, fill);
        c.drawLine(120, bubbleRect.bottom, 130, CHAR_AREA.top - 4, line);
        c.drawLine(130, CHAR_AREA.top - 4, 140, bubbleRect.bottom, line);
        c.save();
        c.scale(1 / d, 1 / d);
        StaticLayout sl = StaticLayout.Builder.obtain(s, 0, s.length(), text, (int) (210 * d))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).build();
        c.translate(25 * d, bubbleRect.centerY() * d - sl.getHeight() / 2f);
        sl.draw(c);
        c.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (Bitmap b : frames.values()) b.recycle();
        frames.clear();
    }
}
