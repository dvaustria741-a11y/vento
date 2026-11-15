// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgaskin.remotedesktop;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.pgaskin.remotedesktop.backend.Backend;
import net.pgaskin.remotedesktop.backend.Backends;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The home screen, in the style of the Windows App: a header (settings, title,
 * add), the view-mode button and a collapsible section title, the saved
 * connections, and a bottom bar that switches between all of them (Devices)
 * and the pinned ones (Favorites).
 *
 * <p>An item is a card or a row, chosen from the header and remembered. Cards let
 * the user recognise a machine by its screen; rows make it easier to find one
 * among twenty, since a name and address on their own line are easier to read.
 * Both use the same bind (the two layouts share their ids), so the mode only
 * decides the picture size and whether the items are joined into a group of
 * rows.
 */
public final class HomeActivity extends AppCompatActivity {

    private RecyclerView list;
    private View empty;
    private ImageButton reorderButton;
    private ImageButton viewModeButton;
    private TextView sectionTitle;
    private View sectionChevron;
    private BottomNavigationView nav;
    private final Adapter adapter = new Adapter();

    /** Rows rather than cards. Persisted, because it is a preference and not a mode. */
    private boolean listView;

    /** The Favorites tab: only the pinned connections. Devices shows them all. */
    private boolean favoritesOnly;

    /** The section's list is folded away, as the Windows App's section header can. */
    private boolean collapsed;

    /** The editor, while one is open; a dialog does not survive a rotation. */
    private ConnectionEditorPanel editor;

    /**
     * The reorder sheet, likewise. Unlike the editor's half-typed form, none of
     * its state needs saving across a rotation: it reads the saved connections
     * and writes each change immediately, so the bundle only records that it
     * was open.
     */
    private PinnedOrderPanel reorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_home);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        reorderButton = findViewById(R.id.reorder);
        viewModeButton = findViewById(R.id.view_mode);
        sectionTitle = findViewById(R.id.section_title);
        sectionChevron = findViewById(R.id.section_chevron);
        nav = findViewById(R.id.nav);
        favoritesOnly = savedInstanceState != null && savedInstanceState.getBoolean("favorites");
        collapsed = savedInstanceState != null && savedInstanceState.getBoolean("collapsed");
        findViewById(R.id.add).setOnClickListener(v -> addConnection());
        empty.findViewById(R.id.playground).setOnClickListener(
                v -> startActivity(new Intent(this, PlaygroundActivity.class)));

        listView = AppSettings.listView(this);
        final GridLayoutManager grid = new GridLayoutManager(this, spanCount());
        // A plugin's card is a notice rather than a list item, so it takes the
        // whole width regardless of the view mode and column count.
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < adapter.cards.size() ? grid.getSpanCount() : 1;
            }
        });
        list.setLayoutManager(grid);
        // Every change here is a full reload, and the only visible one is the
        // switch between cards and rows, where an animation morphing one into
        // the other would only distract.
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        applyInsets();
        setUpHeader();

        // An editor open across a rotation: the dialog was destroyed with the
        // window, and the form's contents are in the bundle.
        final Bundle sheet = savedInstanceState == null
                ? null : savedInstanceState.getBundle("editor");
        if (sheet != null) {
            editor = ConnectionEditorPanel.restore(this, sheet, adapter::reload);
        }
        if (savedInstanceState != null && savedInstanceState.getBoolean("reorder")) {
            reorderPinned();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (editor != null && editor.isShowing()) {
            out.putBundle("editor", editor.saveState());
        }
        out.putBoolean("reorder", reorder != null && reorder.isShowing());
        out.putBoolean("favorites", favoritesOnly);
        out.putBoolean("collapsed", collapsed);
    }

    @Override
    protected void onDestroy() {
        // A dialog outliving its activity is a leaked window, and this screen
        // is recreated on every rotation.
        if (editor != null) {
            editor.dismiss();
            editor = null;
        }
        if (reorder != null) {
            reorder.dismiss();
            reorder = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.reload();
    }

    /**
     * Updates the connected badges while this screen is visible. A session can
     * start or end while the list is shown (in the window beside this one, or
     * from the notification), and the card would otherwise keep showing a
     * connection that ended.
     *
     * <p>Posted rather than applied immediately: this can arrive during a
     * scroll, and a RecyclerView must not be notified of changes during
     * layout.
     */
    private final Sessions.Watcher sessionWatcher =
            () -> list.post(adapter::notifyDataSetChanged);

    @Override
    protected void onStart() {
        super.onStart();
        Sessions.addWatcher(sessionWatcher);
    }

    @Override
    protected void onStop() {
        Sessions.removeWatcher(sessionWatcher);
        super.onStop();
    }

    /**
     * As many columns of about 360 dp as fit, and at least one: a card is wide
     * and shows the desktop at a size that can be recognised, as the Windows
     * App's does, so a phone gets one column and a tablet or an unfolded
     * foldable gets two or three.
     *
     * <p>A row is read left to right, so it needs twice the width and can be
     * the only thing across a phone: 480 dp gives a second column only on a
     * tablet. A single 900 dp row with a name at one end and a badge at the
     * other would no longer read as a list.
     */
    private int spanCount() {
        final float dp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        return listView ? Math.max(1, Math.round(dp / 480f))
                : Math.max(1, Math.round(dp / 360f));
    }

    /** The padding either side of the list, for cards and rows alike. */
    private int listSidePadding() {
        return getResources().getDimensionPixelSize(R.dimen.home_inset);
    }

    /** A little air under the section title; a card's own margin adds the rest. */
    private int listTopPadding() {
        return Math.round(4 * getResources().getDisplayMetrics().density);
    }

    /**
     * The system bars: the root is padded at the top and the sides, so the
     * header is clear of the status bar. The bottom bar is left to the
     * navigation view, which pads itself by the bottom inset and draws its own
     * background under the gesture area; the insets are returned unconsumed so
     * that it receives them.
     */
    private void applyInsets() {
        list.setPadding(listSidePadding(), listTopPadding(), listSidePadding(),
                Math.round(8 * getResources().getDisplayMetrics().density));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return insets;
        });
    }

    // ---- the header and the bottom bar -----------------------------------------

    private void setUpHeader() {
        // The round button is the Windows App's account button; here it opens
        // settings, which the bottom bar also does, so there is no second
        // place to look for them.
        findViewById(R.id.avatar).setOnClickListener(v -> openSettings());
        viewModeButton.setOnClickListener(v -> toggleViewMode());
        reorderButton.setOnClickListener(v -> reorderPinned());
        findViewById(R.id.section).setOnClickListener(v -> {
            collapsed = !collapsed;
            applySection();
        });
        showViewMode();

        nav.setSelectedItemId(favoritesOnly ? R.id.nav_favorites : R.id.nav_devices);
        nav.setOnItemSelectedListener(item -> {
            final int id = item.getItemId();
            if (id == R.id.nav_settings) {
                openSettings();
                // Not selected: settings is another screen, and the bar keeps
                // showing the list that is still behind it.
                return false;
            }
            favoritesOnly = id == R.id.nav_favorites;
            showSection();
            adapter.reload();
            return true;
        });
        showSection();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** The section title and the empty state's wording follow the selected tab. */
    private void showSection() {
        sectionTitle.setText(favoritesOnly
                ? R.string.home_section_favorites : R.string.home_section_devices);
        ((TextView) empty.findViewById(R.id.empty_title)).setText(favoritesOnly
                ? R.string.home_empty_favorites_title : R.string.home_empty_title);
        ((TextView) empty.findViewById(R.id.empty_body)).setText(favoritesOnly
                ? R.string.home_empty_favorites : R.string.home_empty);
        empty.findViewById(R.id.playground).setVisibility(
                favoritesOnly ? View.GONE : View.VISIBLE);
    }

    /** Folds the list away or shows it again, and turns the chevron to match. */
    private void applySection() {
        list.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        empty.setVisibility(!collapsed && adapter.items.isEmpty() ? View.VISIBLE : View.GONE);
        sectionChevron.setRotation(collapsed ? -90f : 0f);
    }

    /** The button shows and is described by the mode it switches to. */
    private void showViewMode() {
        viewModeButton.setImageResource(listView ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
        viewModeButton.setContentDescription(getString(
                listView ? R.string.home_view_grid : R.string.home_view_list));
    }

    /**
     * Reordering is offered once there are at least two pinned connections. One
     * has no order, and with none the sheet would be empty; an item that is
     * always shown but usually does nothing is worse than one that appears when
     * it is useful.
     *
     * <p>Counted from the front of the list, since {@link Connections#all}
     * sorts pinned connections first.
     */
    private void showReorder() {
        int pinned = 0;
        for (Connection c : adapter.items) {
            if (!c.pinned()) {
                break;
            }
            pinned++;
        }
        reorderButton.setVisibility(pinned > 1 ? View.VISIBLE : View.GONE);
    }

    /**
     * Switches between cards and rows, which rebinds everything: the item type
     * and the span count both change.
     *
     * <p>So the first visible item is recorded and scrolled back to afterwards.
     * A full rebind starts the list at the top, which would lose the
     * connection the user was looking at.
     */
    // Nothing more specific applies: every item's view type changes, so no bound
    // view survives the switch.
    @SuppressLint("NotifyDataSetChanged")
    private void toggleViewMode() {
        final GridLayoutManager grid = (GridLayoutManager) list.getLayoutManager();
        final int firstVisible = grid.findFirstVisibleItemPosition();
        listView = !listView;
        AppSettings.setListView(this, listView);
        showViewMode();
        grid.setSpanCount(spanCount());
        adapter.notifyDataSetChanged();
        if (firstVisible != RecyclerView.NO_POSITION) {
            grid.scrollToPosition(firstVisible);
        }
    }

    // ---- actions ------------------------------------------------------------

    private void addConnection() {
        if (Backends.ids().isEmpty()) {
            new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_RemoteDesktop_Dialog)
                    .setMessage(R.string.session_no_backend)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        editConnection(null);
    }

    /**
     * The editor is a dialog over this screen rather than a separate activity,
     * so a save has to be reflected in the list behind it directly rather than
     * on {@link #onResume}, which a dialog does not trigger.
     */
    private void editConnection(String id) {
        editor = ConnectionEditorPanel.show(this, id, adapter::reload);
    }

    /**
     * The pinned connections in a sheet for reordering them by drag (see
     * {@link PinnedOrderPanel} for why the drag is not on the cards
     * themselves). The list behind it reloads on every write, since it is the
     * list being rearranged.
     */
    private void reorderPinned() {
        reorder = PinnedOrderPanel.show(this, adapter::reload);
    }

    /**
     * Opens the session in its own window and closes this one.
     *
     * <p>The list is only an entry point: left open behind the session, the
     * launcher icon would return to whichever window is already open, and
     * choosing a second machine would need a way back out of the first.
     * Finishing it means the icon always opens the list, and since a session's
     * window is a document task, the list opened from it can start a second
     * session beside the first rather than replacing it.
     *
     * <p>The task is removed along with the activity: a finished activity
     * leaves its task in the recents list, where it would remain as an empty
     * window.
     *
     * <p>The intent comes from {@link SessionActivity#intentFor}, shared rather
     * than built here because a launcher shortcut opens the same connection and
     * the two must be the same document.
     */
    private void openConnection(Connection conn) {
        startActivity(SessionActivity.intentFor(this, conn.id()));
        finishAndRemoveTask();
    }

    // ---- the list -----------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<Connection> items = new ArrayList<>();
        /**
         * The plugin notices, above the connections. Recomputed with the list,
         * since any of them can change while the app is in the background, as
         * when a plugin is uninstalled from its card.
         */
        private final List<Plugins.Card> cards = new ArrayList<>();
        /**
         * Decoded previews, by connection id, each with the
         * {@link Connections#previewVersion} it was decoded at.
         *
         * <p>A preview is an encrypted PNG, so each costs a keystore round trip
         * and a decode, done off the main thread and cached, because a grid
         * rebinds whenever it is scrolled and the list survives leaving this
         * screen. The version keeps the cache correct: returning from a session
         * re-decodes only that session's card.
         *
         * <p>A null bitmap is a cached result rather than a miss (many
         * connections have no preview), so this holds the pair rather than the
         * bitmap.
         */
        private final Map<String, Preview> previews = new HashMap<>();

        /** @param bitmap null for a connection with no preview taken */
        private record Preview(int version, Bitmap bitmap) {
        }

        /** Previews being decoded, so each is requested only once. */
        private final Set<String> decoding = new HashSet<>();

        // Deliberately notifyDataSetChanged, twice: a card's content comes from
        // three independent sources (the record, the live session and the
        // preview), and a diff cannot account for that.
        @SuppressLint("NotifyDataSetChanged")
        void reload() {
            items.clear();
            for (Connection c : Connections.all(HomeActivity.this)) {
                if (!favoritesOnly || c.pinned()) {
                    items.add(c);
                }
            }
            // Based on the connections alone: "no connections" is still true
            // with a plugin card above it, and setting up a plugin is often
            // the first thing to do.
            applySection();
            // Whether there is an order to change depends on the list, so it is
            // updated whenever the list is read.
            showReorder();
            notifyDataSetChanged();
            // The plugin cards arrive asynchronously: checking whether a plugin
            // is set up can mean hashing a library, and the connections should
            // not wait for that. Where the answers are cached it is this frame.
            Plugins.cards(HomeActivity.this, found -> {
                cards.clear();
                cards.addAll(found);
                notifyDataSetChanged();
            });
        }

        /**
         * This connection's preview if it is decoded and current, else null,
         * which is also the result for a connection with no preview, so the
         * card draws the placeholder in both cases.
         */
        private Bitmap preview(String id) {
            final int version = Connections.previewVersion(id);
            final Preview have = previews.get(id);
            if (have != null && have.version() == version) {
                return have.bitmap();
            }
            if (decoding.add(id)) {
                Connections.readThumbnail(HomeActivity.this, id, bmp -> {
                    decoding.remove(id);
                    previews.put(id, new Preview(version, bmp));
                    // Only this card rather than the whole list: a decode that
                    // finishes during a scroll should not rebind every other
                    // card.
                    final int at = indexOf(id);
                    if (at >= 0) {
                        notifyItemChanged(cards.size() + at);
                    }
                });
            }
            return null;
        }

        private int indexOf(String id) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * The view mode is a property of the screen rather than of an item, but
         * it is still the view type, so that a recycled card view can never be
         * bound as a row.
         */
        @Override
        public int getItemViewType(int position) {
            if (position < cards.size()) {
                return 2;
            }
            return listView ? 1 : 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == 2) {
                return new PluginHolder(inflater.inflate(R.layout.item_plugin, parent, false));
            }
            return new Holder(inflater.inflate(
                    viewType == 1 ? R.layout.item_connection_row : R.layout.item_connection,
                    parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof PluginHolder p) {
                bindCard(p, position, cards.get(position));
                return;
            }
            final Holder h = (Holder) holder;
            final int index = position - cards.size();
            final Connection c = items.get(index);
            h.title.setText(c.title());
            h.subtitle.setText(c.subtitle());
            // INVISIBLE, not GONE: a connection with no name has one line and
            // the one beside it may have two, and cards of different heights in
            // one grid row would leave a ragged edge.
            h.subtitle.setVisibility(c.subtitle().isEmpty() ? View.INVISIBLE : View.VISIBLE);

            // Hidden when there is only one backend: a label that is the same
            // on every card adds nothing.
            final String backend = Backends.shortName(c.backendId());
            h.badge.setText(backend);
            h.badge.setVisibility(Backends.ids().size() > 1 && !backend.isEmpty()
                    ? View.VISIBLE : View.GONE);

            final Bitmap thumb = preview(c.id());
            if (thumb != null) {
                h.preview.setImageBitmap(thumb);
                h.preview.setImageTintList(null);
                h.preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                // No preview yet: the placeholder gradient (the view's own
                // background) and one faint icon at its natural size in the
                // middle. CENTER, not the CENTER_CROP a preview uses: cropping
                // the glyph to fill the band leaves two bars that are not
                // recognisable as a computer. White in both themes, because the
                // placeholder is the same blue in both.
                h.preview.setImageResource(R.drawable.ic_desktop);
                h.preview.setImageTintList(ColorStateList.valueOf(0x59ffffff));
                h.preview.setScaleType(ImageView.ScaleType.CENTER);
            }

            // The only thing on a card about the current state rather than the
            // saved connection. Tinted here rather than in the drawable so it
            // uses the theme's accent, and so
            // the other two badges keep sharing one drawable.
            final Session live = Sessions.byKey(Sessions.keyFor(c.id()));
            if (live != null && live.alive()) {
                h.connected.setText(live.state() == Backend.State.CONNECTED
                        ? R.string.home_connected : R.string.session_connecting);
                h.connected.setBackgroundTintList(ColorStateList.valueOf(
                        MaterialColors.getColor(h.connected,
                                androidx.appcompat.R.attr.colorPrimary)));
                h.connected.setTextColor(MaterialColors.getColor(h.connected,
                        com.google.android.material.R.attr.colorOnPrimary));
                h.connected.setVisibility(View.VISIBLE);
            } else {
                h.connected.setVisibility(View.GONE);
            }

            h.pin.setVisibility(c.pinned() ? View.VISIBLE : View.GONE);

            if (listView) {
                groupRow(h.card, index);
            }

            h.card.setOnClickListener(v -> openConnection(c));
            // Straight to the editor rather than a selection mode: edit, delete
            // and pin are all in the editor, so such a mode would only be an
            // extra step in front of it.
            h.card.setOnLongClickListener(v -> {
                editConnection(c.id());
                return true;
            });
        }

        /**
         * One plugin's card: the plugin, what is wrong or missing, the error it
         * reported, and the one or two actions for it. Uninstall is on every
         * card about a package; the restart card is about all plugins and has
         * the restart button instead.
         */
        private void bindCard(PluginHolder h, int position, Plugins.Card card) {
            spaceCard(h.itemView, position);
            h.title.setText(card.title());
            h.message.setText(card.message());
            h.detail.setText(card.detail());
            h.detail.setVisibility(card.detail() == null || card.detail().isEmpty()
                    ? View.GONE : View.VISIBLE);
            h.setUp.setVisibility(card.kind() == Plugins.Kind.SETUP ? View.VISIBLE : View.GONE);
            h.restart.setVisibility(card.kind() == Plugins.Kind.RESTART
                    ? View.VISIBLE : View.GONE);
            h.update.setVisibility(card.updateUrl() == null ? View.GONE : View.VISIBLE);
            h.uninstall.setVisibility(card.packageName() == null ? View.GONE : View.VISIBLE);
            h.update.setOnClickListener(v -> Plugins.update(HomeActivity.this, card.updateUrl()));
            h.setUp.setOnClickListener(v -> Plugins.setup(HomeActivity.this, card.backendId()));
            h.restart.setOnClickListener(v -> Plugins.restart(HomeActivity.this));
            h.uninstall.setOnClickListener(
                    v -> Plugins.uninstall(HomeActivity.this, card.packageName()));
        }

        @Override
        public int getItemCount() {
            return cards.size() + items.size();
        }

        /**
         * A plugin card's margins. It spans the full width in either mode and
         * the list's own padding is its side inset, so only the vertical gap is
         * set here: the one between two groups of rows above the first card and
         * below each one, less what the neighbour already provides.
         */
        private void spaceCard(View card, int position) {
            final int spacing = getResources().getDimensionPixelSize(R.dimen.group_spacing);
            final int own = getResources().getDimensionPixelSize(R.dimen.card_margin);
            final ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) card.getLayoutParams();
            lp.setMarginStart(0);
            lp.setMarginEnd(0);
            lp.topMargin = position == 0 ? spacing : 0;
            lp.bottomMargin = position == cards.size() - 1 && !listView
                    ? spacing - own : spacing;
            card.setLayoutParams(lp);
        }

        /**
         * A row's place in the group formed by the whole list: its background,
         * its inset and the gap under it, as for a settings row.
         *
         * <p>The ends are grid rows rather than items, since a wide enough
         * screen puts two rows side by side: the first span's worth are the top
         * of the group, and the items in the last grid row are its bottom.
         */
        private void groupRow(View row, int position) {
            final int span = ((GridLayoutManager) list.getLayoutManager()).getSpanCount();
            final int last = items.size() - 1;
            final boolean bottom = position >= last - last % span;
            RowGroups.row(row, position < span, bottom, bottom ? 0 : RowGroups.gap(row));
        }
    }

    /**
     * One item, in either view mode: the two layouts have the same ids so that
     * one bind fills both, and the live badge, the part most likely to diverge,
     * is written once.
     */
    private static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView preview;
        final ImageView pin;
        final TextView badge;
        final TextView connected;
        final TextView title;
        final TextView subtitle;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            preview = v.findViewById(R.id.preview);
            pin = v.findViewById(R.id.pin);
            badge = v.findViewById(R.id.badge);
            connected = v.findViewById(R.id.connected);
            title = v.findViewById(R.id.title);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }

    /** A plugin's card, which shares only the list with a connection's. */
    private static final class PluginHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView message;
        final TextView detail;
        final View setUp;
        final View restart;
        final View update;
        final View uninstall;

        PluginHolder(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            message = v.findViewById(R.id.message);
            detail = v.findViewById(R.id.detail);
            setUp = v.findViewById(R.id.setup);
            restart = v.findViewById(R.id.restart);
            update = v.findViewById(R.id.update);
            uninstall = v.findViewById(R.id.uninstall);
        }
    }
}
