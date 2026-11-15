// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: MIT

package net.pgaskin.remotedesktop.control.input;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A game-style layer of on-screen buttons over the desktop: round (or hex) key
 * buttons, a WASD stick, mouse-button and wheel buttons, and a menu button, laid
 * out in the fractions of the view so one layout fits any screen.
 *
 * <p>Like {@link MouseOverlay} and {@link ExtensionKeyboard} it is a
 * {@link TouchRouter.Claim}: a finger that lands on a button belongs to the
 * button for its whole life, and every other finger still drives the touchpad,
 * so the right thumb can look around while the left holds W.
 *
 * <p>This class is the model and draws nothing: the view asks for
 * {@link #items()} and the state accessors and paints them.
 *
 * <p>Every key is held for as long as its finger is down and released with it
 * (Shift, Ctrl and Alt included: they are held, not latched), and everything
 * held is released on hide, cancel and {@link #releaseAll()}.
 */
public final class GamepadOverlay implements TouchRouter.Claim {

    public enum Kind {
        KEY, STICK, MOUSE, WHEEL, MENU
    }

    /**
     * One control. Position and size are fractions: {@code cx} of the view's
     * width, {@code cy} of its height, {@code r} (the radius) of its height.
     * {@code keysym} is for {@link Kind#KEY}, {@code mask} (a {@link Button}
     * mask) for {@link Kind#MOUSE} and {@link Kind#WHEEL}.
     */
    public record Item(Kind kind, String label, String icon, int keysym, int mask,
                       float cx, float cy, float r) {
    }

    public interface Listener {
        /** Something to draw changed. */
        void gamepadChanged();

        /** The menu button was released on the button it started on. */
        void gamepadMenu();
    }

    // The stick's four keys, which is also their order in the held[] flags.
    public static final int UP = 1, DOWN = 2, LEFT = 4, RIGHT = 8;
    private static final int[] STICK_KEYSYMS = {'w', 's', 'a', 'd'};
    private static final int STICK_ID = 0x100;

    /** Inside this fraction of the stick's radius nothing is held. */
    static final float DEAD_ZONE = 0.30f;
    /** sin 22.5°: each axis engages within 67.5° of itself, giving eight ways. */
    static final float AXIS_ENGAGE = 0.383f;
    /** Wheel button repeat, ms: the first click is on touch, then this apart. */
    static final long WHEEL_REPEAT_DELAY_MS = 350;
    static final long WHEEL_REPEAT_MS = 90;

    private final KeySink keys;
    private final MouseSink mouse;
    private final Scheduler scheduler;
    private Listener listener;

    private List<Item> items = List.of();
    private boolean[] pressed = new boolean[0];
    // Which pointer holds each item, or -1.
    private int[] holder = new int[0];

    private boolean visible;
    private int viewW, viewH;

    private int stickItem = -1;
    private int stickDirs;      // UP | DOWN | LEFT | RIGHT currently held
    private float knobDx, knobDy; // the thumb's offset from the stick centre, px, clamped to the radius

    private int wheelItem = -1;
    private final Runnable wheelTick = new Runnable() {
        @Override
        public void run() {
            if (wheelItem < 0) {
                return;
            }
            click(items.get(wheelItem).mask());
            scheduler.postDelayed(this, WHEEL_REPEAT_MS);
        }
    };

    public GamepadOverlay(KeySink keys, MouseSink mouse, Scheduler scheduler) {
        this.keys = keys;
        this.mouse = mouse;
        this.scheduler = scheduler;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setItems(List<Item> list) {
        releaseAll();
        items = List.copyOf(list);
        pressed = new boolean[items.size()];
        holder = new int[items.size()];
        Arrays.fill(holder, -1);
        stickItem = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind() == Kind.STICK) {
                stickItem = i;
                break;
            }
        }
        changed();
    }

    public List<Item> items() {
        return items;
    }

    public void setViewSize(int w, int h) {
        viewW = w;
        viewH = h;
    }

    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean show) {
        if (visible == show) {
            return;
        }
        if (!show) {
            releaseAll();
        }
        visible = show;
        changed();
    }

    public boolean isPressed(int index) {
        return index >= 0 && index < pressed.length && pressed[index];
    }

    /** Which of {@link #UP}, {@link #DOWN}, {@link #LEFT}, {@link #RIGHT} are held. */
    public int stickDirections() {
        return stickDirs;
    }

    public float knobDx() {
        return knobDx;
    }

    public float knobDy() {
        return knobDy;
    }

    // ---- geometry ---------------------------------------------------------

    public float centreX(Item it) {
        return it.cx() * viewW;
    }

    public float centreY(Item it) {
        return it.cy() * viewH;
    }

    public float radiusPx(Item it) {
        return it.r() * viewH;
    }

    /** The item under the point, the nearest centre if two overlap, or -1. */
    public int hit(float x, float y) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < items.size(); i++) {
            final Item it = items.get(i);
            final float dx = x - centreX(it), dy = y - centreY(it);
            final float d2 = dx * dx + dy * dy;
            final float r = radiusPx(it);
            if (d2 <= r * r && d2 < bestD) {
                best = i;
                bestD = d2;
            }
        }
        return best;
    }

    /**
     * Which of the four stick keys an offset from the stick's centre holds:
     * none inside the dead zone, otherwise an axis is held when its share of
     * the offset's length exceeds {@link #AXIS_ENGAGE}, so a diagonal holds two.
     * Screen y grows downward, so a negative {@code dy} is {@link #UP}.
     */
    public static int directions(float dx, float dy, float radius) {
        if (!(radius > 0) || Float.isNaN(dx) || Float.isNaN(dy)) {
            return 0;
        }
        final float len = (float) Math.hypot(dx, dy);
        if (len < DEAD_ZONE * radius) {
            return 0;
        }
        final float nx = dx / len, ny = dy / len;
        int d = 0;
        if (ny < -AXIS_ENGAGE) {
            d |= UP;
        }
        if (ny > AXIS_ENGAGE) {
            d |= DOWN;
        }
        if (nx < -AXIS_ENGAGE) {
            d |= LEFT;
        }
        if (nx > AXIS_ENGAGE) {
            d |= RIGHT;
        }
        return d;
    }

    // ---- TouchRouter.Claim ------------------------------------------------

    @Override
    public boolean claimTouch(int id, float x, float y, long t) {
        if (!visible) {
            return false;
        }
        final int i = hit(x, y);
        if (i < 0 || holder[i] >= 0) {
            // Not ours, or the button is already held by another finger and
            // this one is better used on the touchpad.
            return false;
        }
        holder[i] = id;
        press(i);
        if (items.get(i).kind() == Kind.STICK) {
            moveStick(i, x, y);
        }
        changed();
        return true;
    }

    @Override
    public void claimMoved(int id, float x, float y, long t) {
        for (int i = 0; i < holder.length; i++) {
            if (holder[i] == id && items.get(i).kind() == Kind.STICK) {
                moveStick(i, x, y);
                changed();
            }
        }
    }

    @Override
    public void claimEnded(int id, float x, float y, long t) {
        for (int i = 0; i < holder.length; i++) {
            if (holder[i] != id) {
                continue;
            }
            holder[i] = -1;
            final boolean inside = hit(x, y) == i;
            release(i);
            if (items.get(i).kind() == Kind.MENU && inside && listener != null) {
                listener.gamepadMenu();
            }
        }
        changed();
    }

    @Override
    public void claimCancelled(long t) {
        releaseAll();
    }

    /** Release every key and button held, and forget every finger. */
    public void releaseAll() {
        for (int i = 0; i < holder.length; i++) {
            if (pressed[i]) {
                release(i);
            }
            holder[i] = -1;
        }
        setStickDirs(0);
        knobDx = knobDy = 0;
        changed();
    }

    // ---- per-kind behaviour -----------------------------------------------

    private void press(int i) {
        final Item it = items.get(i);
        pressed[i] = true;
        switch (it.kind()) {
            case KEY -> keys.keyDown(it.keysym(), KeySink.ID_GAMEPAD + i);
            case MOUSE -> mouse.mouseDown(it.mask());
            case WHEEL -> {
                click(it.mask());
                wheelItem = i;
                scheduler.removeCallbacks(wheelTick);
                scheduler.postDelayed(wheelTick, WHEEL_REPEAT_DELAY_MS);
            }
            case STICK, MENU -> {
            }
        }
    }

    private void release(int i) {
        final Item it = items.get(i);
        pressed[i] = false;
        switch (it.kind()) {
            case KEY -> keys.keyUp(KeySink.ID_GAMEPAD + i);
            case MOUSE -> mouse.mouseUp(it.mask());
            case WHEEL -> {
                if (wheelItem == i) {
                    wheelItem = -1;
                    scheduler.removeCallbacks(wheelTick);
                }
            }
            case STICK -> {
                setStickDirs(0);
                knobDx = knobDy = 0;
            }
            case MENU -> {
            }
        }
    }

    private void click(int mask) {
        mouse.mouseDown(mask);
        mouse.mouseUp(mask);
    }

    private void moveStick(int i, float x, float y) {
        final Item it = items.get(i);
        final float r = radiusPx(it);
        float dx = x - centreX(it), dy = y - centreY(it);
        setStickDirs(directions(dx, dy, r));
        final float len = (float) Math.hypot(dx, dy);
        if (len > r && len > 0) {
            dx *= r / len;
            dy *= r / len;
        }
        knobDx = dx;
        knobDy = dy;
    }

    /** Press and release only the keys that differ, so a held key never re-fires. */
    private void setStickDirs(int now) {
        final int[] bits = {UP, DOWN, LEFT, RIGHT};
        for (int k = 0; k < 4; k++) {
            final boolean was = (stickDirs & bits[k]) != 0, is = (now & bits[k]) != 0;
            if (was && !is) {
                keys.keyUp(KeySink.ID_GAMEPAD + STICK_ID + k);
            } else if (!was && is) {
                keys.keyDown(STICK_KEYSYMS[k], KeySink.ID_GAMEPAD + STICK_ID + k);
            }
        }
        stickDirs = now;
    }

    private void changed() {
        if (listener != null) {
            listener.gamepadChanged();
        }
    }

    // ---- the layout -------------------------------------------------------

    /**
     * The default layout, landscape. Positions are read off the reference
     * screenshots (centres as fractions of the 1080×536 frame), so they are a
     * starting point to tune on a device, not measured values. The keys on the
     * stick, the bottom row, the letter cluster and the right-hand thumb
     * buttons follow the screenshots; which mouse button each of the five
     * right-hand buttons is, is an assumption (wheel up, wheel down and middle
     * across the top, then left and right).
     */
    public static List<Item> standard() {
        final List<Item> l = new ArrayList<>();
        l.add(new Item(Kind.MENU, "", "menu", 0, 0, 0.046f, 0.093f, 0.085f));
        l.add(key("Esc", Keysym.ESCAPE, 0.083f, 0.097f, 0.060f));
        l.add(key("Tab", Keysym.TAB, 0.153f, 0.177f, 0.050f));
        l.add(key("F1", Keysym.f(1), 0.254f, 0.149f, 0.050f));
        l.add(key("F2", Keysym.f(2), 0.318f, 0.155f, 0.050f));
        l.add(key("F3", Keysym.f(3), 0.380f, 0.155f, 0.050f));
        l.add(key("O", 'o', 0.632f, 0.116f, 0.045f));
        l.add(key("M", 'm', 0.707f, 0.108f, 0.045f));
        l.add(key("I", 'i', 0.670f, 0.215f, 0.045f));
        l.add(key("Enter", Keysym.RETURN, 0.798f, 0.131f, 0.050f));
        l.add(key("Q", 'q', 0.812f, 0.332f, 0.055f));
        l.add(key("F", 'f', 0.856f, 0.479f, 0.065f));
        l.add(key("E", 'e', 0.786f, 0.547f, 0.055f));
        l.add(key("R", 'r', 0.748f, 0.713f, 0.075f));
        l.add(key("C", 'c', 0.693f, 0.860f, 0.085f));
        l.add(key("Ctrl", Keysym.CONTROL_L, 0.793f, 0.871f, 0.085f));
        l.add(key("Space", ' ', 0.943f, 0.815f, 0.100f));
        l.add(key("Shift", Keysym.SHIFT_L, 0.078f, 0.519f, 0.080f));
        l.add(key("Alt", Keysym.ALT_L, 0.189f, 0.526f, 0.060f));
        l.add(key("Z", 'z', 0.253f, 0.871f, 0.060f));
        l.add(key("X", 'x', 0.323f, 0.873f, 0.060f));
        l.add(key("B", 'b', 0.385f, 0.873f, 0.060f));
        l.add(key("1", '1', 0.454f, 0.873f, 0.060f));
        l.add(key("2", '2', 0.518f, 0.880f, 0.060f));
        l.add(key("3", '3', 0.581f, 0.886f, 0.060f));
        l.add(new Item(Kind.STICK, "", "stick", 0, 0, 0.131f, 0.765f, 0.150f));
        l.add(new Item(Kind.WHEEL, "", "wheel_up", 0, Button.WHEEL_UP.mask(), 0.869f, 0.211f, 0.065f));
        l.add(new Item(Kind.WHEEL, "", "wheel_down", 0, Button.WHEEL_DOWN.mask(), 0.951f, 0.211f, 0.065f));
        l.add(new Item(Kind.MOUSE, "", "mouse_middle", 0, Button.MIDDLE.mask(), 0.896f, 0.341f, 0.065f));
        l.add(new Item(Kind.MOUSE, "", "mouse_left", 0, Button.LEFT.mask(), 0.935f, 0.543f, 0.100f));
        l.add(new Item(Kind.MOUSE, "", "mouse_right", 0, Button.RIGHT.mask(), 0.851f, 0.707f, 0.100f));
        return l;
    }

    private static Item key(String label, int keysym, float cx, float cy, float r) {
        return new Item(Kind.KEY, label, null, keysym, 0, cx, cy, r);
    }
}
