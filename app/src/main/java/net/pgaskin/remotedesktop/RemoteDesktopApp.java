// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgaskin.remotedesktop;

import android.app.Application;

import net.pgaskin.remotedesktop.backend.Backends;

/**
 * The application: starts the backends and publishes the launcher shortcuts.
 *
 * <p>Every activity uses the fixed palette in the theme rather than the
 * system's Material You colours. They are not applied here any more: the
 * dynamic palette is a theme overlay set per activity, and it would replace
 * the palette's colour roles.
 */
public final class RemoteDesktopApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Before anything queries the available backends, and here rather than
        // in an activity because the service is an entry point too.
        Backends.discover(this);

        // The launcher's copy of the connection list, which is otherwise only
        // rewritten when the list is. It can also go stale while this app is
        // not running: a restored backup, or an upgrade from a version that
        // published none. Off the main thread, and cheap enough to do on every
        // start rather than tracking whether it is needed.
        Shortcuts.publish(this);
    }
}
