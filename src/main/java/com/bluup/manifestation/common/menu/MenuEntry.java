package com.bluup.manifestation.common.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/**
 * A single menu entry in serializable form.
 *
 * <p>Sent from server to client inside a {@link MenuPayload}. Entries can be
 * either:
 * <ul>
 *   <li>{@link Kind#BUTTON}: dispatches its action list when clicked</li>
 *   <li>{@link Kind#INPUT}: renders a text input field with this label as hint</li>
 *   <li>{@link Kind#SLIDER}: renders a numeric slider with min/max/current</li>
 *   <li>{@link Kind#DROPDOWN}: renders a non-freeform selector over string options</li>
 *   <li>{@link Kind#SECTION}: renders a non-interactive section header</li>
 * </ul>
 *
 * <p>For button entries, the {@code actions} list preserves order: iotas are
 * dispatched in the sequence they were pushed when the menu was defined.
 */
public final class MenuEntry {
    public enum Kind {
        BUTTON,
        INPUT,
        SLIDER,
        DROPDOWN,
        SECTION
    }

    private final Kind kind;
    private final Component label;
    private final List<StoredIota> actions;
    private final double sliderMin;
    private final double sliderMax;
    private final boolean sliderHasCurrent;
    private final double sliderCurrent;
    private final List<String> dropdownOptions;
    private final int dropdownSelected;

    public MenuEntry(
            Kind kind,
            Component label,
            List<StoredIota> actions,
            double sliderMin,
            double sliderMax,
            boolean sliderHasCurrent,
            double sliderCurrent,
            List<String> dropdownOptions,
            int dropdownSelected
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.label = Objects.requireNonNull(label, "label");
        this.actions = List.copyOf(actions);
        this.sliderMin = sliderMin;
        this.sliderMax = sliderMax;
        this.sliderHasCurrent = sliderHasCurrent;
        this.sliderCurrent = sliderCurrent;
        this.dropdownOptions = List.copyOf(dropdownOptions);
        this.dropdownSelected = dropdownSelected;
    }

    public static MenuEntry button(Component label, List<StoredIota> actions) {
        return new MenuEntry(Kind.BUTTON, label, actions, 0.0, 0.0, false, 0.0, List.of(), 0);
    }

    public static MenuEntry input(Component label) {
        return new MenuEntry(Kind.INPUT, label, List.of(), 0.0, 0.0, false, 0.0, List.of(), 0);
    }

    public static MenuEntry slider(Component label, double min, double max, Double current) {
        boolean hasCurrent = current != null;
        double currentValue = hasCurrent ? current : min;
        return new MenuEntry(Kind.SLIDER, label, List.of(), min, max, hasCurrent, currentValue, List.of(), 0);
    }

    public static MenuEntry dropdown(Component label, List<String> options, Integer selected) {
        List<String> copy = List.copyOf(options);
        int fallback = copy.isEmpty() ? 0 : 0;
        int raw = selected == null ? fallback : selected;
        int clamped = copy.isEmpty() ? 0 : Math.max(0, Math.min(raw, copy.size() - 1));
        return new MenuEntry(Kind.DROPDOWN, label, List.of(), 0.0, 0.0, false, 0.0, copy, clamped);
    }

    public static MenuEntry section(Component label) {
        return new MenuEntry(Kind.SECTION, label, List.of(), 0.0, 0.0, false, 0.0, List.of(), 0);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isButton() {
        return kind == Kind.BUTTON;
    }

    public boolean isInput() {
        return kind == Kind.INPUT;
    }

    public boolean isSlider() {
        return kind == Kind.SLIDER;
    }

    public boolean isDropdown() {
        return kind == Kind.DROPDOWN;
    }

    public boolean isSection() {
        return kind == Kind.SECTION;
    }

    public Component label() {
        return label;
    }

    public List<StoredIota> actions() {
        return actions;
    }

    public double sliderMin() {
        return sliderMin;
    }

    public double sliderMax() {
        return sliderMax;
    }

    public boolean sliderHasCurrent() {
        return sliderHasCurrent;
    }

    public double sliderCurrent() {
        return sliderCurrent;
    }

    public List<String> dropdownOptions() {
        return dropdownOptions;
    }

    public int dropdownSelected() {
        return dropdownSelected;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        buf.writeComponent(label);
        if (kind == Kind.BUTTON) {
            buf.writeVarInt(actions.size());
            for (StoredIota a : actions) {
                a.write(buf);
            }
            return;
        }

        if (kind == Kind.SLIDER) {
            buf.writeDouble(sliderMin);
            buf.writeDouble(sliderMax);
            buf.writeBoolean(sliderHasCurrent);
            if (sliderHasCurrent) {
                buf.writeDouble(sliderCurrent);
            }
            return;
        }

        if (kind == Kind.DROPDOWN) {
            buf.writeVarInt(dropdownOptions.size());
            for (String option : dropdownOptions) {
                buf.writeUtf(option);
            }
            buf.writeVarInt(dropdownSelected);
            return;
        }

        if (kind == Kind.SECTION) {
            return;
        }
    }

    public static MenuEntry read(FriendlyByteBuf buf) {
        Kind kind = buf.readEnum(Kind.class);
        Component label = buf.readComponent();
        if (kind == Kind.INPUT) {
            return MenuEntry.input(label);
        }

        if (kind == Kind.SLIDER) {
            double min = buf.readDouble();
            double max = buf.readDouble();
            boolean hasCurrent = buf.readBoolean();
            Double current = hasCurrent ? buf.readDouble() : null;
            return MenuEntry.slider(label, min, max, current);
        }

        if (kind == Kind.DROPDOWN) {
            int n = buf.readVarInt();
            String[] options = new String[n];
            for (int i = 0; i < n; i++) {
                options[i] = buf.readUtf();
            }
            int selected = buf.readVarInt();
            return MenuEntry.dropdown(label, List.of(options), selected);
        }

        if (kind == Kind.SECTION) {
            return MenuEntry.section(label);
        }

        int n = buf.readVarInt();
        StoredIota[] actions = new StoredIota[n];
        for (int i = 0; i < n; i++) {
            actions[i] = StoredIota.read(buf);
        }
        return MenuEntry.button(label, List.of(actions));
    }
}
