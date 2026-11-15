// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: MIT

package net.pgaskin.remotedesktop.control.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import net.pgaskin.remotedesktop.control.input.Config;
import net.pgaskin.remotedesktop.control.input.GamepadOverlay;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a {@link GamepadOverlay}: a procedural round or hex look by default,
 * or bitmaps where a skin supplies them. Every bitmap is optional and the
 * procedural look fills in for a missing one, so a partial skin is fine.
 *
 * <p>Skin bitmap names the app may supply: {@code button_idle},
 * {@code button_pressed}, {@code stick_base}, {@code stick_knob}, and one per
 * item icon ({@code menu}, {@code wheel_up}, {@code wheel_down},
 * {@code mouse_left}, {@code mouse_right}, {@code mouse_middle}). Bitmaps are
 * drawn scaled to the item's diameter; this class does not own or recycle them.
 */
public final class GamepadPainter {

    public enum Shape {
        ROUND, HEX
    }

    private final Config cfg;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();
    private final Map<String, Bitmap> skin = new HashMap<>();
    private Shape shape = Shape.ROUND;
    private float opacity = 0.85f;

    public GamepadPainter(Config cfg) {
        this.cfg = cfg;
        ring.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setShape(Shape s) {
        shape = s;
    }

    /** 0..1, for the whole overlay. */
    public void setOpacity(float o) {
        opacity = Math.max(0.1f, Math.min(1f, o));
    }

    /** Add or replace one skin bitmap; null removes it. */
    public void setSkin(String name, Bitmap b) {
        if (b == null) {
            skin.remove(name);
        } else {
            skin.put(name, b);
        }
    }

    public void draw(Canvas c, GamepadOverlay g) {
        if (!g.visible()) {
            return;
        }
        final java.util.List<GamepadOverlay.Item> items = g.items();
        for (int i = 0; i < items.size(); i++) {
            final GamepadOverlay.Item it = items.get(i);
            final float x = g.centreX(it), y = g.centreY(it), r = g.radiusPx(it);
            if (it.kind() == GamepadOverlay.Kind.STICK) {
                drawStick(c, g, x, y, r);
            } else {
                drawButton(c, it, g.isPressed(i), x, y, r);
            }
        }
    }

    private void drawButton(Canvas c, GamepadOverlay.Item it, boolean down, float x, float y, float r) {
        // The pressed art is an overlay (a glowing ring), so the resting body
        // is always drawn under it.
        final Bitmap idle = skin.get("button_idle");
        final Bitmap pressed = down ? skin.get("button_pressed") : null;
        if (idle != null) {
            blit(c, idle, x, y, r);
        } else {
            body(c, down && pressed == null, x, y, r);
        }
        if (pressed != null) {
            blit(c, pressed, x, y, r);
        }
        final Bitmap icon = it.icon() == null ? null : skin.get(it.icon());
        if (icon != null) {
            blit(c, icon, x, y, r * 0.72f);
            return;
        }
        final String label = it.label();
        if (!label.isEmpty()) {
            text.setColor(alpha(down ? 0xffffffff : 0xffdfe6ee));
            text.setTextSize(r * (label.length() > 2 ? 0.42f : 0.68f));
            c.drawText(label, x, y - (text.ascent() + text.descent()) / 2f, text);
        } else if (it.icon() != null) {
            glyph(c, it.icon(), down, x, y, r);
        }
    }

    private void drawStick(Canvas c, GamepadOverlay g, float x, float y, float r) {
        final Bitmap base = skin.get("stick_base");
        if (base != null) {
            blit(c, base, x, y, r);
        } else {
            body(c, false, x, y, r);
        }
        // The four keys the stick sends, lit while held.
        final int d = g.stickDirections();
        final String[] names = {"W", "S", "A", "D"};
        final int[] bits = {GamepadOverlay.UP, GamepadOverlay.DOWN, GamepadOverlay.LEFT, GamepadOverlay.RIGHT};
        final float[] ox = {0, 0, -0.74f, 0.74f}, oy = {-0.70f, 0.82f, 0.06f, 0.06f};
        text.setTextSize(r * 0.34f);
        for (int k = 0; k < 4; k++) {
            text.setColor(alpha((d & bits[k]) != 0 ? 0xff7cc4ff : 0xffcfd8e3));
            c.drawText(names[k], x + ox[k] * r, y + oy[k] * r - (text.ascent() + text.descent()) / 2f, text);
        }
        final float kx = x + g.knobDx(), ky = y + g.knobDy();
        final Bitmap knob = skin.get("stick_knob");
        if (knob != null) {
            blit(c, knob, kx, ky, r * 0.38f);
        } else {
            fill.setColor(alpha(0xcc5aa9f0));
            c.drawCircle(kx, ky, r * 0.30f, fill);
            ring.setStrokeWidth(cfg.dp(1.5f));
            ring.setColor(alpha(0xffe8f2ff));
            c.drawCircle(kx, ky, r * 0.30f, ring);
        }
    }

    private void body(Canvas c, boolean down, float x, float y, float r) {
        fill.setColor(alpha(down ? 0xcc3d8fe0 : 0x66161b22));
        ring.setStrokeWidth(cfg.dp(2f));
        ring.setColor(alpha(shape == Shape.HEX ? (down ? 0xffd9a6ff : 0xff9b4be0) : (down ? 0xffeaf4ff : 0xccc9d3de)));
        if (shape == Shape.HEX) {
            path.rewind();
            for (int k = 0; k < 6; k++) {
                final double a = Math.toRadians(60 * k - 30);
                final float px = x + (float) Math.cos(a) * r, py = y + (float) Math.sin(a) * r;
                if (k == 0) {
                    path.moveTo(px, py);
                } else {
                    path.lineTo(px, py);
                }
            }
            path.close();
            c.drawPath(path, fill);
            c.drawPath(path, ring);
        } else {
            c.drawCircle(x, y, r, fill);
            c.drawCircle(x, y, r - ring.getStrokeWidth() / 2f, ring);
        }
    }

    /** The mouse-button, wheel and menu glyphs, when no skin bitmap is given. */
    private void glyph(Canvas c, String id, boolean down, float x, float y, float r) {
        ring.setStrokeWidth(cfg.dp(2f));
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(alpha(down ? 0xffffffff : 0xffdfe6ee));
        fill.setColor(alpha(0xcc5aa9f0));
        final float s = r * 0.5f;
        if (id.startsWith("mouse_")) {
            box.set(x - s * 0.6f, y - s, x + s * 0.6f, y + s);
            c.drawRoundRect(box, s * 0.6f, s * 0.6f, ring);
            c.drawLine(x - s * 0.6f, y - s * 0.1f, x + s * 0.6f, y - s * 0.1f, ring);
            c.drawLine(x, y - s, x, y - s * 0.1f, ring);
            // The pressed half of the button, as in the reference.
            box.set(x - s * 0.55f, y - s * 0.95f, x + s * 0.55f, y - s * 0.12f);
            c.save();
            if (id.equals("mouse_left")) {
                c.clipRect(x - s * 0.6f, y - s, x, y - s * 0.1f);
            } else if (id.equals("mouse_right")) {
                c.clipRect(x, y - s, x + s * 0.6f, y - s * 0.1f);
            } else {
                c.clipRect(x - s * 0.12f, y - s, x + s * 0.12f, y - s * 0.1f);
            }
            c.drawRoundRect(box, s * 0.6f, s * 0.6f, fill);
            c.restore();
        } else if (id.startsWith("wheel_")) {
            final float dir = id.equals("wheel_up") ? -1f : 1f;
            c.drawLine(x - s * 0.6f, y + dir * s * 0.2f, x, y - dir * s * 0.4f, ring);
            c.drawLine(x + s * 0.6f, y + dir * s * 0.2f, x, y - dir * s * 0.4f, ring);
        } else if (id.equals("menu")) {
            for (int k = -1; k <= 1; k++) {
                c.drawLine(x - s * 0.7f, y + k * s * 0.5f, x + s * 0.7f, y + k * s * 0.5f, ring);
            }
        }
    }

    private void blit(Canvas c, Bitmap b, float x, float y, float r) {
        box.set(x - r, y - r, x + r, y + r);
        bitmapPaint.setAlpha(Math.round(255 * opacity));
        c.drawBitmap(b, null, box, bitmapPaint);
    }

    private int alpha(int argb) {
        final int a = Math.round(((argb >>> 24) & 0xff) * opacity);
        return (a << 24) | (argb & 0xffffff);
    }
}
