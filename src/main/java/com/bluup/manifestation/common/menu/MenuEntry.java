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
 *   <li>{@link Kind#NUMERIC_INPUT}: renders a numeric text box</li>
 *   <li>{@link Kind#SLIDER}: renders a numeric slider with min/max/current</li>
 *   <li>{@link Kind#CHECKBOX}: renders a boolean toggle and emits 1/0 on dispatch</li>
 *   <li>{@link Kind#SELECT_LIST}: renders a scrollable selectable list</li>
 *   <li>{@link Kind#DROPDOWN}: renders a non-freeform selector over rich-text options</li>
 *   <li>{@link Kind#SECTION}: renders a non-interactive section header</li>
 * </ul>
 *
 * <p>For button entries, the {@code actions} list preserves order: iotas are
 * dispatched in the sequence they were pushed when the menu was defined.
 * Copied from my original documentation that wasn't hexdoc.
 */
public final class MenuEntry {
    public enum Kind {
        BUTTON,
        INPUT,
        NUMERIC_INPUT,
        SLIDER,
        CHECKBOX,
        SELECT_LIST,
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
    private final boolean numericHasCurrent;
    private final double numericCurrent;
    private final boolean checkboxChecked;
    private final List<StoredIota> selectOptions;
    private final List<Component> selectOptionLabels;
    private final int selectMaxRows;
    private final boolean selectMulti;
    private final List<Component> dropdownOptions;
    private final int dropdownSelected;

    public MenuEntry(
            Kind kind,
            Component label,
            List<StoredIota> actions,
            double sliderMin,
            double sliderMax,
            boolean sliderHasCurrent,
            double sliderCurrent,
            boolean numericHasCurrent,
            double numericCurrent,
            boolean checkboxChecked,
                List<StoredIota> selectOptions,
                List<Component> selectOptionLabels,
                int selectMaxRows,
                boolean selectMulti,
            List<Component> dropdownOptions,
            int dropdownSelected
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.label = Objects.requireNonNull(label, "label");
        this.actions = List.copyOf(actions);
        this.sliderMin = sliderMin;
        this.sliderMax = sliderMax;
        this.sliderHasCurrent = sliderHasCurrent;
        this.sliderCurrent = sliderCurrent;
        this.numericHasCurrent = numericHasCurrent;
        this.numericCurrent = numericCurrent;
        this.checkboxChecked = checkboxChecked;
        this.selectOptions = List.copyOf(selectOptions);
        this.selectOptionLabels = List.copyOf(selectOptionLabels);
        this.selectMaxRows = selectMaxRows;
        this.selectMulti = selectMulti;
        this.dropdownOptions = List.copyOf(dropdownOptions);
        this.dropdownSelected = dropdownSelected;
    }

    public static MenuEntry button(Component label, List<StoredIota> actions) {
        return new MenuEntry(Kind.BUTTON, label, actions, 0.0, 0.0, false, 0.0, false, 0.0, false, List.of(), List.of(), 0, false, List.of(), 0);
    }

    public static MenuEntry input(Component label) {
        return new MenuEntry(Kind.INPUT, label, List.of(), 0.0, 0.0, false, 0.0, false, 0.0, false, List.of(), List.of(), 0, false, List.of(), 0);
    }

    public static MenuEntry numericInput(Component label, Double current) {
        boolean hasCurrent = current != null;
        double currentValue = hasCurrent ? current : 0.0;
        return new MenuEntry(Kind.NUMERIC_INPUT, label, List.of(), 0.0, 0.0, false, 0.0, hasCurrent, currentValue, false, List.of(), List.of(), 0, false, List.of(), 0);
    }

    public static MenuEntry slider(Component label, double min, double max, Double current) {
        boolean hasCurrent = current != null;
        double currentValue = hasCurrent ? current : min;
        return new MenuEntry(Kind.SLIDER, label, List.of(), min, max, hasCurrent, currentValue, false, 0.0, false, List.of(), List.of(), 0, false, List.of(), 0);
    }

    public static MenuEntry checkbox(Component label, boolean checked) {
        return new MenuEntry(Kind.CHECKBOX, label, List.of(), 0.0, 0.0, false, 0.0, false, 0.0, checked, List.of(), List.of(), 0, false, List.of(), 0);
    }

    public static MenuEntry selectList(Component label, List<StoredIota> options, List<Component> optionLabels, int maxRows, boolean multiSelect) {
        int count = Math.min(options.size(), optionLabels.size());
        StoredIota[] clippedOptions = new StoredIota[count];
        Component[] clippedLabels = new Component[count];
        for (int i = 0; i < count; i++) {
            clippedOptions[i] = options.get(i);
            clippedLabels[i] = optionLabels.get(i);
        }
        int clampedRows = Math.max(1, Math.min(maxRows, 12));
        return new MenuEntry(
            Kind.SELECT_LIST,
            label,
            List.of(),
            0.0,
            0.0,
            false,
            0.0,
            false,
            0.0,
            false,
            List.of(clippedOptions),
            List.of(clippedLabels),
            clampedRows,
            multiSelect,
            List.of(),
            0
        );
    }

    public static MenuEntry dropdown(Component label, List<Component> options, Integer selected) {
        List<Component> copy = List.copyOf(options);
        int fallback = copy.isEmpty() ? 0 : 0;
        int raw = selected == null ? fallback : selected;
        int clamped = copy.isEmpty() ? 0 : Math.max(0, Math.min(raw, copy.size() - 1));
        return new MenuEntry(Kind.DROPDOWN, label, List.of(), 0.0, 0.0, false, 0.0, false, 0.0, false, List.of(), List.of(), 0, false, copy, clamped);
    }

    public static MenuEntry section(Component label) {
        return new MenuEntry(Kind.SECTION, label, List.of(), 0.0, 0.0, false, 0.0, false, 0.0, false, List.of(), List.of(), 0, false, List.of(), 0);
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

    public boolean isNumericInput() {
        return kind == Kind.NUMERIC_INPUT;
    }

    public boolean isCheckbox() {
        return kind == Kind.CHECKBOX;
    }

    public boolean isSelectList() {
        return kind == Kind.SELECT_LIST;
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

    public boolean numericHasCurrent() {
        return numericHasCurrent;
    }

    public double numericCurrent() {
        return numericCurrent;
    }

    public boolean checkboxChecked() {
        return checkboxChecked;
    }

    public List<StoredIota> selectOptions() {
        return selectOptions;
    }

    public List<Component> selectOptionLabels() {
        return selectOptionLabels;
    }

    public int selectMaxRows() {
        return selectMaxRows;
    }

    public boolean selectMulti() {
        return selectMulti;
    }

    public List<Component> dropdownOptions() {
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

        if (kind == Kind.NUMERIC_INPUT) {
            buf.writeBoolean(numericHasCurrent);
            if (numericHasCurrent) {
                buf.writeDouble(numericCurrent);
            }
            return;
        }

        if (kind == Kind.CHECKBOX) {
            buf.writeBoolean(checkboxChecked);
            return;
        }

        if (kind == Kind.SELECT_LIST) {
            buf.writeVarInt(selectOptions.size());
            for (int i = 0; i < selectOptions.size(); i++) {
                selectOptions.get(i).write(buf);
                buf.writeComponent(selectOptionLabels.get(i));
            }
            buf.writeVarInt(selectMaxRows);
            buf.writeBoolean(selectMulti);
            return;
        }

        if (kind == Kind.DROPDOWN) {
            buf.writeVarInt(dropdownOptions.size());
            for (Component option : dropdownOptions) {
                buf.writeComponent(option);
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

        if (kind == Kind.NUMERIC_INPUT) {
            boolean hasCurrent = buf.readBoolean();
            Double current = hasCurrent ? buf.readDouble() : null;
            return MenuEntry.numericInput(label, current);
        }

        if (kind == Kind.SLIDER) {
            double min = buf.readDouble();
            double max = buf.readDouble();
            boolean hasCurrent = buf.readBoolean();
            Double current = hasCurrent ? buf.readDouble() : null;
            return MenuEntry.slider(label, min, max, current);
        }

        if (kind == Kind.CHECKBOX) {
            return MenuEntry.checkbox(label, buf.readBoolean());
        }

        if (kind == Kind.SELECT_LIST) {
            int n = buf.readVarInt();
            StoredIota[] options = new StoredIota[n];
            Component[] optionLabels = new Component[n];
            for (int i = 0; i < n; i++) {
                options[i] = StoredIota.read(buf);
                optionLabels[i] = buf.readComponent();
            }
            int maxRows = buf.readVarInt();
            boolean multi = buf.readBoolean();
            return MenuEntry.selectList(label, List.of(options), List.of(optionLabels), maxRows, multi);
        }

        if (kind == Kind.DROPDOWN) {
            int n = buf.readVarInt();
            Component[] options = new Component[n];
            for (int i = 0; i < n; i++) {
                options[i] = buf.readComponent();
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
