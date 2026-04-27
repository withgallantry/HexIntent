package com.bluup.manifestation.common.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

import java.util.List;
import java.util.Objects;

/**
 * The full menu definition as it travels over the wire from server to client.
 *
 * <p>A payload carries the display title, the ordered list of selectable entries,
 * the layout mode (list vs grid), and for grid layout the column count the
 * player asked for.
 */
public final class MenuPayload {

    public enum Layout {
        LIST,
        GRID,
        RADIAL
    }

    public enum Theme {
        RITUAL,
        SCHOLAR
    }

    public enum DispatchSource {
        STAFF,
        HEXICAL_CHARM
    }

    private final Component title;
    private final List<MenuEntry> entries;
    private final Layout layout;
    private final Theme theme;
    private final int columns;
    private final InteractionHand hand;
    private final DispatchSource dispatchSource;

    public MenuPayload(
            Component title,
            List<MenuEntry> entries,
            Layout layout,
            Theme theme,
            int columns,
            InteractionHand hand,
            DispatchSource dispatchSource
    ) {
        this.title = Objects.requireNonNull(title, "title");
        this.entries = List.copyOf(entries);
        this.layout = Objects.requireNonNull(layout, "layout");
        this.theme = Objects.requireNonNull(theme, "theme");
        this.columns = Math.max(1, columns);
        this.hand = Objects.requireNonNull(hand, "hand");
        this.dispatchSource = Objects.requireNonNull(dispatchSource, "dispatchSource");
    }

    public Component title() {
        return title;
    }

    public List<MenuEntry> entries() {
        return entries;
    }

    public Layout layout() {
        return layout;
    }

    public Theme theme() {
        return theme;
    }

    public int columns() {
        return columns;
    }

    public InteractionHand hand() {
        return hand;
    }

    public DispatchSource dispatchSource() {
        return dispatchSource;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeComponent(title);
        buf.writeEnum(layout);
        buf.writeEnum(theme);
        buf.writeVarInt(columns);
        buf.writeEnum(hand);
        buf.writeEnum(dispatchSource);
        buf.writeVarInt(entries.size());
        for (MenuEntry e : entries) {
            e.write(buf);
        }
    }

    public static MenuPayload read(FriendlyByteBuf buf) {
        Component title = buf.readComponent();
        Layout layout = buf.readEnum(Layout.class);
        Theme theme = buf.readEnum(Theme.class);
        int columns = buf.readVarInt();
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        DispatchSource dispatchSource = buf.readEnum(DispatchSource.class);
        int n = buf.readVarInt();
        MenuEntry[] entries = new MenuEntry[n];
        for (int i = 0; i < n; i++) {
            entries[i] = MenuEntry.read(buf);
        }
        return new MenuPayload(title, List.of(entries), layout, theme, columns, hand, dispatchSource);
    }
}
