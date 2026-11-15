# Gamepad skin (optional)

Drop transparent PNGs here with these exact names. Each missing file falls back
to the built-in drawing for that part. Files are drawn scaled to the button's
diameter, so square 256x256 is plenty.

| File | Used for |
|---|---|
| button_idle.png | every key/mouse/wheel button at rest |
| button_pressed.png | the same while held |
| stick_base.png | the WASD stick's base disc |
| stick_knob.png | the stick's thumb |
| menu.png | menu button glyph |
| wheel_up.png, wheel_down.png | wheel buttons |
| mouse_left.png, mouse_right.png, mouse_middle.png | mouse buttons |

Key labels (Esc, Shift, Q, ...) are drawn by the app on top, so keep the
button art free of text.

## ChatGPT image prompt

Generate one asset per request (or a sprite sheet, then slice):

> Game UI asset for an on-screen touch controller, flat vector style, transparent
> background, centered, 256x256 px, no text, no letters, no drop shadow outside
> the shape. Subject: a [round | hexagonal] translucent button, dark navy glass
> fill (about 40% opacity), thin glowing [silver-white | violet] outline, soft
> inner highlight on the top edge, subtle bevel. State: [idle | pressed: fill
> brighter electric blue, outline white, slight inner glow].

Variants to ask for:

- stick_base: "the same style but a large circular joystick base ring with four
  faint tick marks at top, bottom, left and right, empty center".
- stick_knob: "a small glowing blue glass sphere, top-down, soft highlight".
- mouse_left / mouse_right / mouse_middle: "a white line-art computer mouse icon,
  front view, the [left | right | middle wheel] part filled in glowing blue, no
  button background".
- wheel_up / wheel_down: "a white line-art mouse scroll wheel with a [up | down]
  chevron, no button background".
- menu: "a white three-line menu glyph, no background".

Ask for PNG with a transparent background and check the corners are really
transparent before adding it.
