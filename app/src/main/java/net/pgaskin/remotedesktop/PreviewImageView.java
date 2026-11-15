// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgaskin.remotedesktop;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * The desktop preview on a home card: as wide as the card, and a fixed
 * fraction of that tall.
 *
 * <p>The card width depends on the screen and the column count, so the picture
 * cannot have a height in dp: it has a ratio, 16:10, a little taller than a
 * widescreen desktop so that the name and address over its lower edge do not
 * cover much of it.
 *
 * <p>Deliberately not the desktop's own aspect ratio: a 16:10 card beside a 4:3
 * one would look like two sizes of card rather than two desktops.
 */
public final class PreviewImageView extends AppCompatImageView {

    /** Height as a fraction of width. */
    private static final float RATIO = 0.625f;

    public PreviewImageView(Context context) {
        super(context);
    }

    public PreviewImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PreviewImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(getMeasuredWidth(), Math.round(getMeasuredWidth() * RATIO));
    }
}
