// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: MIT

package net.pgaskin.remotedesktop.control.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.pgaskin.remotedesktop.control.harness.FakeScheduler;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The gamepad overlay's model, on a 2000×1000 view. */
public class GamepadOverlayTest {

    private final List<String> log = new ArrayList<>();
    private final FakeScheduler scheduler = new FakeScheduler();
    private GamepadOverlay pad;

    private final KeySink keys = new KeySink() {
        @Override
        public void keyDown(int keysym, int keyId) {
            log.add("down " + Integer.toHexString(keysym));
        }

        @Override
        public void keyUp(int keyId) {
            log.add("up " + Integer.toHexString(keyId));
        }
    };
    private final MouseSink mouse = new MouseSink() {
        @Override
        public void mouseMove(float dx, float dy) {
        }

        @Override
        public void mouseDown(int mask) {
            log.add("mdown " + mask);
        }

        @Override
        public void mouseUp(int mask) {
            log.add("mup " + mask);
        }
    };

    @Before
    public void setUp() {
        pad = new GamepadOverlay(keys, mouse, scheduler);
        pad.setItems(List.of(
                new GamepadOverlay.Item(GamepadOverlay.Kind.KEY, "F", null, 'f', 0, 0.5f, 0.5f, 0.05f),
                new GamepadOverlay.Item(GamepadOverlay.Kind.STICK, "", "stick", 0, 0, 0.2f, 0.7f, 0.15f),
                new GamepadOverlay.Item(GamepadOverlay.Kind.WHEEL, "", "wheel_up", 0, Button.WHEEL_UP.mask(), 0.9f, 0.2f, 0.05f),
                new GamepadOverlay.Item(GamepadOverlay.Kind.MOUSE, "", "mouse_left", 0, Button.LEFT.mask(), 0.9f, 0.5f, 0.05f)));
        pad.setViewSize(2000, 1000);
        pad.setVisible(true);
        log.clear();
    }

    @Test
    public void directionsDeadZoneAndEightWays() {
        assertEquals(0, GamepadOverlay.directions(10, 10, 150));
        assertEquals(GamepadOverlay.UP, GamepadOverlay.directions(0, -100, 150));
        assertEquals(GamepadOverlay.DOWN, GamepadOverlay.directions(0, 100, 150));
        assertEquals(GamepadOverlay.LEFT, GamepadOverlay.directions(-100, 0, 150));
        assertEquals(GamepadOverlay.RIGHT, GamepadOverlay.directions(100, 0, 150));
        assertEquals(GamepadOverlay.UP | GamepadOverlay.RIGHT, GamepadOverlay.directions(70, -70, 150));
        assertEquals(0, GamepadOverlay.directions(Float.NaN, 5, 150));
        assertEquals(0, GamepadOverlay.directions(100, 0, 0));
    }

    @Test
    public void keyIsHeldWhileTheFingerIsDown() {
        assertTrue(pad.claimTouch(0, 1000, 500, 0));
        assertEquals(List.of("down 66"), log);
        pad.claimEnded(0, 1000, 500, 10);
        assertEquals(List.of("down 66", "up 40000"), log);
    }

    @Test
    public void emptySpaceIsLeftToTheTouchpad() {
        assertFalse(pad.claimTouch(0, 100, 100, 0));
        assertTrue(log.isEmpty());
    }

    @Test
    public void hiddenOverlayClaimsNothing() {
        pad.setVisible(false);
        assertFalse(pad.claimTouch(0, 1000, 500, 0));
    }

    @Test
    public void stickHoldsOnlyWhatChanged() {
        // Centre is (400, 700), radius 150.
        assertTrue(pad.claimTouch(0, 400, 700, 0));
        assertTrue(log.isEmpty());
        pad.claimMoved(0, 400, 580, 5);          // up
        assertEquals(List.of("down 77"), log);   // 'w'
        pad.claimMoved(0, 400, 570, 6);          // still up: nothing new
        assertEquals(1, log.size());
        pad.claimMoved(0, 520, 580, 7);          // up and right
        assertEquals("down 64", log.get(1));     // 'd'
        pad.claimEnded(0, 520, 580, 8);
        assertEquals(0, pad.stickDirections());
        assertTrue(log.contains("up 40100"));
        assertTrue(log.contains("up 40103"));
    }

    @Test
    public void wheelClicksThenRepeatsUntilReleased() {
        assertTrue(pad.claimTouch(0, 1800, 200, 0));
        assertEquals(List.of("mdown 8", "mup 8"), log);
        scheduler.advance(GamepadOverlay.WHEEL_REPEAT_DELAY_MS + 1);
        assertEquals(4, log.size());
        pad.claimEnded(0, 1800, 200, 400);
        final int n = log.size();
        scheduler.advance(1000);
        assertEquals(n, log.size());
    }

    @Test
    public void cancelReleasesEverything() {
        pad.claimTouch(0, 1000, 500, 0);
        pad.claimTouch(1, 1800, 500, 0);
        log.clear();
        pad.claimCancelled(1);
        assertTrue(log.contains("up 40000"));
        assertTrue(log.contains("mup 1"));
    }

    @Test
    public void hidingReleasesEverything() {
        pad.claimTouch(0, 1800, 500, 0);
        log.clear();
        pad.setVisible(false);
        assertEquals(List.of("mup 1"), log);
    }

    @Test
    public void standardLayoutIsInsideTheViewAndHasOneStick() {
        int sticks = 0;
        for (GamepadOverlay.Item it : GamepadOverlay.standard()) {
            assertTrue(it.label(), it.cx() > 0 && it.cx() < 1 && it.cy() > 0 && it.cy() < 1);
            if (it.kind() == GamepadOverlay.Kind.STICK) {
                sticks++;
            }
        }
        assertEquals(1, sticks);
    }
}
