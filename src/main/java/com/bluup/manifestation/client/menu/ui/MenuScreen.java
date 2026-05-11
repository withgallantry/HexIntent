package com.bluup.manifestation.client.menu.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.bluup.manifestation.client.ActiveMenuState;
import com.bluup.manifestation.client.menu.execution.MenuActionSender;
import com.bluup.manifestation.common.menu.MenuEntry;
import com.bluup.manifestation.common.menu.MenuPayload;
import com.bluup.manifestation.common.menu.StoredIota;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a menu payload as a non-pausing modal screen.
 * LIST uses a single column, GRID uses a computed column count,
 * and RADIAL arranges button entries as wedge sectors around a ring.
 */
public final class MenuScreen extends Screen {

    // Shared layout constants.
    private static final int BUTTON_HEIGHT = 20;
    private static final int SELECT_LIST_ROW_HEIGHT = 20;
    private static final int SECTION_HEIGHT = 26;
    private static final int BUTTON_SPACING = 4;
    private static final int TITLE_MARGIN = 8;
    private static final int PANEL_PADDING = 8;

    // List layout constants.
    private static final int LIST_BUTTON_WIDTH = 200;

    // Grid layout constants.
    private static final int GRID_BUTTON_WIDTH = 120;

    // Radial layout constants.
    private static final int RADIAL_OUTER_RADIUS = 122;
    private static final int RADIAL_INNER_RADIUS = 44;
    private static final int RADIAL_FILL_STEPS = 28;
    private static final double RADIAL_GAP_RADIANS = Math.toRadians(4.0);
    private static final int RADIAL_HOVER_SPARK_COUNT = 12;
    private static final int RADIAL_HOVER_SPARK_TRAIL = 2;

    private static final int INTRO_TRACE_TICKS = 12;
    private static final int INK_REVEAL_TICKS = 18;
    private static final int PAGE_BUTTON_WIDTH = 20;
    private static final int PAGE_CONTROLS_HEIGHT = 20;
    private static final int PAGE_CONTROLS_MARGIN = 6;
    private static final int MIN_PANEL_MARGIN = 24;

    private final MenuPayload menu;
    private final InteractionHand hand;
    private boolean closeAfterSelection;
    private final Map<Integer, EditBox> inputBoxes = new LinkedHashMap<>();
    private final Map<Integer, EditBox> numericInputBoxes = new LinkedHashMap<>();
    private final Map<Integer, MenuSlider> sliderBoxes = new LinkedHashMap<>();
    private final Map<Integer, MenuCheckbox> checkboxBoxes = new LinkedHashMap<>();
    private final Map<Integer, MenuSelectList> selectListBoxes = new LinkedHashMap<>();
    private final Map<Integer, List<StoredIota>> selectListPayloads = new LinkedHashMap<>();
    private final Map<Integer, MenuDropdown> dropdownBoxes = new LinkedHashMap<>();
    private final Map<Integer, EntryBounds> entryBounds = new LinkedHashMap<>();
    private final Map<Integer, Component> radialTruncatedTooltips = new LinkedHashMap<>();
    private int animTick;
    private int currentPage;
    private int totalPages = 1;
    private int entriesPerPage = Integer.MAX_VALUE;
    private int effectiveColumns = 1;

    private static final class EntryBounds {
        final int x;
        final int y;
        final int w;
        final int h;

        EntryBounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private static final class PositionedEntry {
        final int index;
        final MenuEntry entry;
        final EntryBounds bounds;

        PositionedEntry(int index, MenuEntry entry, EntryBounds bounds) {
            this.index = index;
            this.entry = entry;
            this.bounds = bounds;
        }
    }

    private record RadialGeometry(int centerX, int centerY, int outerRadius, int innerRadius) {
    }

    private final class MenuSlider extends AbstractSliderButton {
        private final Component label;
        private final double min;
        private final double max;

        MenuSlider(int x, int y, int width, int height, Component label, double min, double max, double current) {
            super(x, y, width, height, Component.empty(), normalize(min, max, current));
            this.label = label;
            this.min = min;
            this.max = max;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(label.getString() + ": " + formatValue(getActualValue())));
        }

        @Override
        protected void applyValue() {
            // Value is read when a button is dispatched.
        }

        double getActualValue() {
            return Mth.lerp(this.value, min, max);
        }
    }

    private final class MenuDropdown {
        private final Component label;
        private final List<Component> options;
        private int selected;
        private final Button button;

        MenuDropdown(int x, int y, int width, int height, Component label, List<Component> options, int selected) {
            this.label = label;
            this.options = List.copyOf(options);
            this.selected = this.options.isEmpty() ? 0 : Math.max(0, Math.min(selected, this.options.size() - 1));
            this.button = Button.builder(Component.empty(), btn -> {
                if (!this.options.isEmpty()) {
                    this.selected = (this.selected + 1) % this.options.size();
                }
                updateMessage();
            }).bounds(x, y, width, height).build();
            updateMessage();
        }

        private void updateMessage() {
            Component selectedText = options.isEmpty() ? Component.literal("-") : options.get(selected);
            this.button.setMessage(Component.empty().append(label).append(Component.literal(": ")).append(selectedText));
        }

        String selectedValue() {
            return options.isEmpty() ? "" : options.get(selected).getString();
        }

        Button button() {
            return button;
        }
    }

    private final class MenuCheckbox {
        private final Component label;
        private boolean checked;
        private final Button button;

        MenuCheckbox(int x, int y, int width, int height, Component label, boolean checked) {
            this.label = label;
            this.checked = checked;
            this.button = Button.builder(Component.empty(), btn -> {
                this.checked = !this.checked;
                updateMessage();
            }).bounds(x, y, width, height).build();
            updateMessage();
        }

        private void updateMessage() {
            this.button.setMessage(
                Component.empty()
                    .append(label)
                    .append(Component.literal(checked ? ": [x]" : ": [ ]"))
            );
        }

        boolean checked() {
            return checked;
        }

        Button button() {
            return button;
        }
    }

    private final class MenuSelectList extends AbstractWidget {
        private final List<Component> options;
        private final int visibleRows;
        private final boolean multiSelect;
        private final java.util.LinkedHashSet<Integer> selectedIndices = new java.util.LinkedHashSet<>();
        private int scrollOffset;

        MenuSelectList(int x, int y, int width, Component label, List<Component> options, int maxRows, boolean multiSelect) {
            super(x, y, width, Math.max(SELECT_LIST_ROW_HEIGHT, Math.min(maxRows, 12) * SELECT_LIST_ROW_HEIGHT), label);
            this.options = List.copyOf(options);
            this.visibleRows = Math.max(1, Math.min(maxRows, 12));
            this.multiSelect = multiSelect;
            this.scrollOffset = 0;
        }

        private int maxScroll() {
            return Math.max(0, options.size() - visibleRows);
        }

        List<Integer> selectedSorted() {
            List<Integer> out = new ArrayList<>(selectedIndices);
            out.sort(Integer::compareTo);
            return out;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            if (!this.isHoveredOrFocused()) {
                return false;
            }
            if (delta > 0.0) {
                scrollOffset = Math.max(0, scrollOffset - 1);
            } else if (delta < 0.0) {
                scrollOffset = Math.min(maxScroll(), scrollOffset + 1);
            }
            return true;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.isMouseOver(mouseX, mouseY)) {
                return false;
            }

            int localY = (int) mouseY - getY();
            int row = localY / SELECT_LIST_ROW_HEIGHT;
            int optionIndex = scrollOffset + row;
            if (row < 0 || row >= visibleRows || optionIndex < 0 || optionIndex >= options.size()) {
                return false;
            }

            if (!multiSelect) {
                selectedIndices.clear();
                selectedIndices.add(optionIndex);
            } else {
                if (selectedIndices.contains(optionIndex)) {
                    selectedIndices.remove(optionIndex);
                } else {
                    selectedIndices.add(optionIndex);
                }
            }
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = width;
            int h = height;

            graphics.fill(x, y, x + w, y + h, themed(0xAA1B122D, 0xAA11303E));
            graphics.fill(x, y, x + w, y + 1, themed(0xFF9DC8FF, 0xFF8EE8FF));
            graphics.fill(x, y + h - 1, x + w, y + h, themed(0xFF9DC8FF, 0xFF8EE8FF));
            graphics.fill(x, y, x + 1, y + h, themed(0xFF9DC8FF, 0xFF8EE8FF));
            graphics.fill(x + w - 1, y, x + w, y + h, themed(0xFF9DC8FF, 0xFF8EE8FF));

            for (int row = 0; row < visibleRows; row++) {
                int optionIndex = scrollOffset + row;
                if (optionIndex >= options.size()) {
                    break;
                }

                int rowY = y + row * SELECT_LIST_ROW_HEIGHT;
                boolean selected = selectedIndices.contains(optionIndex);
                int rowColor = selected ? themed(0xAA3A2B67, 0xAA245F7E) : themed(0x5530254A, 0x55304B59);
                graphics.fill(x + 1, rowY + 1, x + w - 1, rowY + SELECT_LIST_ROW_HEIGHT - 1, rowColor);

                String prefix = selected ? (multiSelect ? "[x] " : "> ") : (multiSelect ? "[ ] " : "  ");
                String optionText = prefix + options.get(optionIndex).getString();
                String fitted = font.plainSubstrByWidth(optionText, Math.max(10, w - 8));
                graphics.drawString(font, fitted, x + 4, rowY + 6, themed(0xFFE2D6FF, 0xFFD2F3FF), false);
            }

            if (maxScroll() > 0) {
                int railX0 = x + w - 4;
                int railX1 = x + w - 2;
                graphics.fill(railX0, y + 2, railX1, y + h - 2, themed(0x77443062, 0x77407086));

                float ratio = visibleRows / (float) options.size();
                int thumbH = Math.max(8, (int) ((h - 4) * ratio));
                int range = (h - 4) - thumbH;
                int thumbY = y + 2 + (maxScroll() == 0 ? 0 : Math.round(range * (scrollOffset / (float) maxScroll())));
                graphics.fill(railX0, thumbY, railX1, thumbY + thumbH, themed(0xFFC1A2FF, 0xFF9DEBFF));
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            // Intentionally minimal until accessibility strings are designed for menu widgets.
        }
    }

    public MenuScreen(MenuPayload menu, InteractionHand hand) {
        super(menu.title());
        this.menu = menu;
        this.hand = hand;
    }

    @Override
    protected void init() {
        inputBoxes.clear();
        numericInputBoxes.clear();
        sliderBoxes.clear();
        checkboxBoxes.clear();
        selectListBoxes.clear();
        selectListPayloads.clear();
        dropdownBoxes.clear();
        entryBounds.clear();
        radialTruncatedTooltips.clear();
        effectiveColumns = menu.layout() == MenuPayload.Layout.GRID
            ? computeEffectiveGridColumns(menu.columns())
            : 1;

        int rowsPerPage = computeRowsPerPage();
        entriesPerPage = menu.layout() == MenuPayload.Layout.RADIAL
            ? 8
            : Math.max(1, rowsPerPage * effectiveColumns);

        List<MenuEntry> allEntries = entriesForLayout(menu.entries());
        totalPages = Math.max(1, (allEntries.size() + entriesPerPage - 1) / entriesPerPage);
        currentPage = Mth.clamp(currentPage, 0, totalPages - 1);

        List<MenuEntry> pageEntries = getPageEntries(allEntries);
        switch (menu.layout()) {
            case LIST -> layoutList(pageEntries);
            case GRID -> layoutGrid(pageEntries, effectiveColumns);
            case RADIAL -> layoutRadial(pageEntries);
        }

        if (totalPages > 1) {
            addPageControls(pageEntries);
        }
    }

    private void layoutList(List<MenuEntry> entries) {
        int panelWidth = panelWidthForColumns(1);
        int panelHeight = panelHeightForContent(contentHeightForLayout(entries, 1), totalPages > 1);

        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        for (PositionedEntry placement : computeListPlacements(entries, panelX, panelY)) {
            addEntryWidget(
                placement.index,
                placement.entry,
                placement.bounds.x,
                placement.bounds.y,
                placement.bounds.w
            );
        }
    }

    private void layoutGrid(List<MenuEntry> entries, int columns) {
        int panelWidth = panelWidthForColumns(columns);
        int panelHeight = panelHeightForContent(contentHeightForLayout(entries, columns), totalPages > 1);

        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        for (PositionedEntry placement : computeGridPlacements(entries, panelX, panelY, columns)) {
            addEntryWidget(
                placement.index,
                placement.entry,
                placement.bounds.x,
                placement.bounds.y,
                placement.bounds.w
            );
        }
    }

    private void layoutRadial(List<MenuEntry> entries) {
        // Radial entries are rendered and hit-tested as wedge sectors rather than widgets.
    }

    /**
     * Reworked so panel height calculation shared between layouts.
     */
    private int panelHeightForContent(int contentHeight, boolean includePager) {
        int panelHeight = PANEL_PADDING * 2 + this.font.lineHeight + TITLE_MARGIN + Math.max(BUTTON_HEIGHT, contentHeight);
        if (includePager) {
            panelHeight += PAGE_CONTROLS_MARGIN + PAGE_CONTROLS_HEIGHT;
        }
        return panelHeight;
    }

    private int panelWidthForColumns(int columns) {
        if (columns <= 1) {
            return LIST_BUTTON_WIDTH + PANEL_PADDING * 2;
        }
        int innerWidth = columns * GRID_BUTTON_WIDTH + (columns - 1) * BUTTON_SPACING;
        return innerWidth + PANEL_PADDING * 2;
    }

    private int panelWidthForLayout(List<MenuEntry> entries, int columns) {
        if (menu.layout() == MenuPayload.Layout.RADIAL) {
            return Math.max((RADIAL_OUTER_RADIUS * 2) + PANEL_PADDING * 2 + 12, 188);
        }
        return panelWidthForColumns(columns);
    }

    private List<MenuEntry> entriesForLayout(List<MenuEntry> source) {
        if (menu.layout() != MenuPayload.Layout.RADIAL) {
            return source;
        }

        List<MenuEntry> out = new ArrayList<>();
        for (MenuEntry entry : source) {
            if (entry.isButton()) {
                out.add(entry);
            }
        }
        return out;
    }

    private int rowsFor(int entries, int columns) {
        return (entries + columns - 1) / columns;
    }

    private int computeRowsPerPage() {
        int reserved = PANEL_PADDING * 2 + this.font.lineHeight + TITLE_MARGIN + PAGE_CONTROLS_MARGIN + PAGE_CONTROLS_HEIGHT;
        int available = this.height - reserved - (MIN_PANEL_MARGIN * 2);
        int rowUnit = BUTTON_HEIGHT + BUTTON_SPACING;
        return Math.max(1, (available + BUTTON_SPACING) / rowUnit);
    }

    private int computeEffectiveGridColumns(int requestedColumns) {
        int availableInnerWidth = this.width - (MIN_PANEL_MARGIN * 2) - (PANEL_PADDING * 2);
        int maxColumns = Math.max(1, (availableInnerWidth + BUTTON_SPACING) / (GRID_BUTTON_WIDTH + BUTTON_SPACING));
        return Mth.clamp(requestedColumns, 1, maxColumns);
    }

    private List<MenuEntry> getPageEntries(List<MenuEntry> allEntries) {
        if (allEntries.isEmpty()) {
            return List.of();
        }

        int start = currentPage * entriesPerPage;
        if (start >= allEntries.size()) {
            return List.of();
        }

        int end = Math.min(allEntries.size(), start + entriesPerPage);
        return allEntries.subList(start, end);
    }

    private void addPageControls(List<MenuEntry> visibleEntries) {
        int columns = menu.layout() == MenuPayload.Layout.GRID ? effectiveColumns : 1;
        int panelWidth = panelWidthForLayout(visibleEntries, columns);
        int panelHeight = panelHeightForContent(contentHeightForLayout(visibleEntries, columns), true);
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        int navY = panelY + panelHeight - PANEL_PADDING - PAGE_CONTROLS_HEIGHT;
        int leftX = panelX + PANEL_PADDING;
        int rightX = panelX + panelWidth - PANEL_PADDING - PAGE_BUTTON_WIDTH;

        Button prev = Button.builder(Component.literal("<"), btn -> {
                    currentPage = Math.max(0, currentPage - 1);
                    this.init();
                })
                .bounds(leftX, navY, PAGE_BUTTON_WIDTH, PAGE_CONTROLS_HEIGHT)
                .build();
        prev.active = currentPage > 0;
        this.addRenderableWidget(prev);

        Button next = Button.builder(Component.literal(">"), btn -> {
                    currentPage = Math.min(totalPages - 1, currentPage + 1);
                    this.init();
                })
                .bounds(rightX, navY, PAGE_BUTTON_WIDTH, PAGE_CONTROLS_HEIGHT)
                .build();
        next.active = currentPage < totalPages - 1;
        this.addRenderableWidget(next);
    }

    private void addEntryWidget(int index, MenuEntry entry, int x, int y, int width) {
        if (entry.isSection()) {
            return;
        }

        entryBounds.put(index, new EntryBounds(x, y, width, entryHeight(entry)));

        if (entry.isInput()) {
            EditBox box = new EditBox(this.font, x, y, width, BUTTON_HEIGHT, entry.label());
            box.setHint(entry.label());
            inputBoxes.put(index, box);
            this.addRenderableWidget(box);
            return;
        }

        if (entry.isNumericInput()) {
            EditBox box = new EditBox(this.font, x, y, width, BUTTON_HEIGHT, entry.label());
            box.setHint(entry.label());
            box.setFilter(MenuScreen::isPotentialNumericText);
            if (entry.numericHasCurrent()) {
                box.setValue(formatValue(entry.numericCurrent()));
            }
            numericInputBoxes.put(index, box);
            this.addRenderableWidget(box);
            return;
        }

        if (entry.isSlider()) {
            MenuSlider slider = new MenuSlider(
                x,
                y,
                width,
                BUTTON_HEIGHT,
                entry.label(),
                entry.sliderMin(),
                entry.sliderMax(),
                entry.sliderCurrent()
            );
            sliderBoxes.put(index, slider);
            this.addRenderableWidget(slider);
            return;
        }

        if (entry.isCheckbox()) {
            MenuCheckbox checkbox = new MenuCheckbox(
                x,
                y,
                width,
                BUTTON_HEIGHT,
                entry.label(),
                entry.checkboxChecked()
            );
            checkboxBoxes.put(index, checkbox);
            this.addRenderableWidget(checkbox.button());
            return;
        }

        if (entry.isSelectList()) {
            MenuSelectList selectList = new MenuSelectList(
                x,
                y,
                width,
                entry.label(),
                entry.selectOptionLabels(),
                entry.selectMaxRows(),
                entry.selectMulti()
            );
            selectListBoxes.put(index, selectList);
            selectListPayloads.put(index, entry.selectOptions());
            this.addRenderableWidget(selectList);
            return;
        }

        if (entry.isDropdown()) {
            MenuDropdown dropdown = new MenuDropdown(
                x,
                y,
                width,
                BUTTON_HEIGHT,
                entry.label(),
                entry.dropdownOptions(),
                entry.dropdownSelected()
            );
            dropdownBoxes.put(index, dropdown);
            this.addRenderableWidget(dropdown.button());
            return;
        }

        final MenuEntry captured = entry;
        Component buttonLabel = entry.label();
        if (menu.layout() == MenuPayload.Layout.RADIAL) {
            return;
        }
        this.addRenderableWidget(
                Button.builder(buttonLabel, btn -> selectEntry(captured))
                        .bounds(x, y, width, BUTTON_HEIGHT)
                        .build()
        );
    }

    private Component fittedRadialLabel(Component label, int width) {
        int maxTextWidth = Math.max(24, width - 14);
        String raw = label.getString();
        if (this.font.width(raw) <= maxTextWidth) {
            return label;
        }

        String ellipsis = "...";
        int ellipsisWidth = this.font.width(ellipsis);
        int allowed = Math.max(8, maxTextWidth - ellipsisWidth);
        String clipped = this.font.plainSubstrByWidth(raw, allowed);
        return Component.literal(clipped + ellipsis);
    }

    private void selectEntry(MenuEntry entry) {
        if (!entry.isButton()) {
            return;
        }

        spawnClickFlare();

        // Clear first in case dispatch re-enters the screen loop.
        ActiveMenuState.get().clear();
        MenuActionSender.send(entry, this.hand, menu.dispatchSource(), collectInputValues());
        if (this.minecraft != null) {
            closeAfterSelection = true;
            this.minecraft.setScreen(null);
        }
    }

    private List<MenuActionSender.InputDatum> collectInputValues() {
        List<MenuActionSender.InputDatum> values = new ArrayList<>();

        inputBoxes.entrySet().stream()
            .sorted(Comparator.comparingInt(Map.Entry::getKey))
            .forEach(entry -> {
                String value = entry.getValue().getValue();
                if (value != null && !value.isEmpty()) {
                    values.add(MenuActionSender.InputDatum.string(entry.getKey(), value));
                }
            });

        sliderBoxes.entrySet().stream()
            .sorted(Comparator.comparingInt(Map.Entry::getKey))
            .forEach(entry -> values.add(
                MenuActionSender.InputDatum.number(entry.getKey(), entry.getValue().getActualValue())
            ));

        numericInputBoxes.entrySet().stream()
            .sorted(Comparator.comparingInt(Map.Entry::getKey))
            .forEach(entry -> {
                Double parsed = parseStrictDouble(entry.getValue().getValue());
                if (parsed != null) {
                    values.add(MenuActionSender.InputDatum.number(entry.getKey(), parsed));
                }
            });

        checkboxBoxes.entrySet().stream()
            .sorted(Comparator.comparingInt(Map.Entry::getKey))
            .forEach(entry -> values.add(
                MenuActionSender.InputDatum.number(entry.getKey(), entry.getValue().checked() ? 1.0 : 0.0)
            ));

        selectListBoxes.entrySet().stream()
            .sorted(Comparator.comparingInt(Map.Entry::getKey))
            .forEach(entry -> {
                List<StoredIota> options = selectListPayloads.getOrDefault(entry.getKey(), List.of());
                List<StoredIota> selected = new ArrayList<>();
                for (int selectedIndex : entry.getValue().selectedSorted()) {
                    if (selectedIndex >= 0 && selectedIndex < options.size()) {
                        selected.add(options.get(selectedIndex));
                    }
                }
                values.add(MenuActionSender.InputDatum.iotaList(entry.getKey(), selected));
            });

        dropdownBoxes.entrySet().stream()
            .sorted(Comparator.comparingInt(Map.Entry::getKey))
            .forEach(entry -> {
                String value = entry.getValue().selectedValue();
                if (!value.isEmpty()) {
                    values.add(MenuActionSender.InputDatum.string(entry.getKey(), value));
                }
            });

        values.sort(Comparator.comparingInt(MenuActionSender.InputDatum::order));
        return values;
    }

    private static String formatValue(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0e-6) {
            return Integer.toString((int) Math.rint(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static boolean isPotentialNumericText(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        return text.matches("[-+]?((\\d+\\.?\\d*)|(\\.\\d*))([eE][-+]?\\d*)?");
    }

    private static Double parseStrictDouble(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(text);
            if (!Double.isFinite(parsed)) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double normalize(double min, double max, double current) {
        if (max <= min) {
            return 0.0;
        }
        return Mth.clamp((current - min) / (max - min), 0.0, 1.0);
    }

    private void spawnClickFlare() {
        if (this.minecraft == null || this.minecraft.level == null || this.minecraft.player == null) {
            return;
        }

        double px = this.minecraft.player.getX();
        double py = this.minecraft.player.getY() + 1.2;
        double pz = this.minecraft.player.getZ();

        for (int i = 0; i < 18; i++) {
            double vx = (this.minecraft.level.random.nextDouble() - 0.5) * 0.12;
            double vy = 0.02 + this.minecraft.level.random.nextDouble() * 0.08;
            double vz = (this.minecraft.level.random.nextDouble() - 0.5) * 0.12;
            this.minecraft.level.addParticle(ParticleTypes.ENCHANT, px, py, pz, vx, vy, vz);
        }

        for (int i = 0; i < 6; i++) {
            double vx = (this.minecraft.level.random.nextDouble() - 0.5) * 0.06;
            double vy = 0.01 + this.minecraft.level.random.nextDouble() * 0.04;
            double vz = (this.minecraft.level.random.nextDouble() - 0.5) * 0.06;
            this.minecraft.level.addParticle(ParticleTypes.END_ROD, px, py, pz, vx, vy, vz);
        }
    }

    @Override
    public void onClose() {
        // Manual dismisses suppress immediate reopen from repeating casts.
        if (closeAfterSelection) {
            ActiveMenuState.get().clear();
        } else {
            ActiveMenuState.get().clearAndSuppressReopen(menu);
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        animTick++;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // Recompute bounds here because the screen can resize after init().
        List<MenuEntry> entries = getPageEntries(entriesForLayout(menu.entries()));
        int columns = menu.layout() == MenuPayload.Layout.GRID ? effectiveColumns : 1;
        int panelWidth = panelWidthForLayout(entries, columns);
        int panelHeight = panelHeightForContent(contentHeightForLayout(entries, columns), totalPages > 1);

        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        float pulse = (Mth.sin((animTick + partialTick) / 10.0f) + 1.0f) * 0.5f;
        float introProgress = Mth.clamp((animTick + partialTick) / INTRO_TRACE_TICKS, 0.0f, 1.0f);

        drawArcanePanel(graphics, panelX, panelY, panelWidth, panelHeight, pulse);
        drawOpeningTrace(graphics, panelX, panelY, panelWidth, panelHeight, introProgress, pulse);
        drawLivingGlyphPerimeter(graphics, panelX, panelY, panelWidth, panelHeight, pulse);
        if (menu.layout() == MenuPayload.Layout.RADIAL) {
            drawRadialSectors(graphics, panelX, panelY, panelWidth, entries, mouseX, mouseY, pulse);
        } else {
            drawEntryBackplates(graphics, panelX, panelY, entries, columns);
            drawSectionLabels(graphics, panelX, panelY, entries, columns);
            drawRuneLinks(graphics, panelX, panelY, entries, columns, pulse);
            drawHoverShimmer(graphics, mouseX, mouseY, pulse);
        }
        drawSigils(graphics, panelX, panelY, panelWidth, panelHeight, pulse);

        String titleText = revealText(menu.title().getString(), animTick, 2);
        Component title = Component.literal(titleText);
        int titleWidth = this.font.width(titleText);
        int titleY = panelY + PANEL_PADDING;
        int titleX = panelX + (panelWidth - titleWidth) / 2;

        int dividerY = titleY + this.font.lineHeight + 3;
        int glowAlpha = 38 + (int) (30 * pulse);
        int dividerGlow = (glowAlpha << 24) | themed(0xA06BFF, 0x6FC8FF);
        graphics.fill(panelX + PANEL_PADDING + 8, dividerY, panelX + panelWidth - PANEL_PADDING - 8, dividerY + 1, dividerGlow);
        graphics.fill(panelX + panelWidth / 2 - 1, dividerY - 2, panelX + panelWidth / 2 + 2, dividerY + 3, themed(0xCCBDA1FF, 0xCCAEF2FF));

        graphics.drawString(
                this.font,
                title,
                titleX,
                titleY,
                themed(0xFFEBDFFF, 0xFFDFF5FF),
                true
        );

        if (totalPages > 1) {
            String label = revealText("Page " + (currentPage + 1) + "/" + totalPages, animTick, 14);
            int navY = panelY + panelHeight - PANEL_PADDING - PAGE_CONTROLS_HEIGHT;
            int labelWidth = this.font.width(label);
            int labelX = panelX + (panelWidth - labelWidth) / 2;
            graphics.drawString(this.font, label, labelX, navY + 6, themed(0xE7D7FF, 0xD9F4FF), false);
        }

        if (introProgress < 1.0f) {
            int veilAlpha = (int) (88 * (1.0f - introProgress));
            int veil = (veilAlpha << 24) | themed(0x1E1034, 0x0E2A3B);
            graphics.fill(panelX + 2, panelY + 2, panelX + panelWidth - 2, panelY + panelHeight - 2, veil);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        renderRadialLabelTooltip(graphics, mouseX, mouseY);
    }

    private void renderRadialLabelTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (menu.layout() != MenuPayload.Layout.RADIAL || radialTruncatedTooltips.isEmpty()) {
            return;
        }

        int columns = menu.layout() == MenuPayload.Layout.GRID ? effectiveColumns : 1;
        List<MenuEntry> entries = getPageEntries(entriesForLayout(menu.entries()));
        int panelWidth = panelWidthForLayout(entries, columns);
        int panelHeight = panelHeightForContent(contentHeightForLayout(entries, columns), totalPages > 1);
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        int hovered = radialIndexAt(mouseX, mouseY, entries, panelX, panelY, panelWidth);
        if (hovered >= 0) {
            Component tooltip = radialTruncatedTooltips.get(hovered);
            if (tooltip != null) {
                graphics.renderTooltip(this.font, tooltip, mouseX, mouseY);
            }
        }
    }

    private void drawRadialSectors(
        GuiGraphics graphics,
        int panelX,
        int panelY,
        int panelWidth,
        List<MenuEntry> entries,
        int mouseX,
        int mouseY,
        float pulse
    ) {
        radialTruncatedTooltips.clear();
        if (entries.isEmpty()) {
            return;
        }

        RadialGeometry geometry = radialGeometry(panelX, panelY, panelWidth, entries);
        int hovered = radialIndexAt(mouseX, mouseY, entries, panelX, panelY, panelWidth);

        for (int i = 0; i < entries.size(); i++) {
            MenuEntry entry = entries.get(i);
            double start = radialStartAngle(i, entries.size()) + RADIAL_GAP_RADIANS;
            double end = radialEndAngle(i, entries.size()) - RADIAL_GAP_RADIANS;
            boolean isHovered = i == hovered;
            int arcSegments = Math.max(
                RADIAL_FILL_STEPS,
                (int) Math.ceil(Math.abs(end - start) * geometry.outerRadius * 0.30)
            );

            int fillAlpha = isHovered ? 132 + (int) (48 * pulse) : 62 + (int) (18 * pulse);
            int frameAlpha = isHovered ? 218 + (int) (30 * pulse) : 118 + (int) (20 * pulse);
            int fill = (fillAlpha << 24) | themed(0x43235D, 0x15394D);
            int frame = (frameAlpha << 24) | themed(0xE1B7FF, 0x9DE8FF);
            int glow = (((isHovered ? 120 : 46) + (int) (32 * pulse)) << 24) | themed(0xA872FF, 0x5FD3FF);
            int hoverFill = ((Math.min(235, fillAlpha + 52)) << 24) | themed(0x6A338E, 0x1B5D7A);

            drawRadialBand(graphics, geometry, start, end, fill, arcSegments);
            if (isHovered) {
                drawRadialBand(graphics, geometry, start, end, hoverFill, arcSegments);
            }

            drawArcStroke(graphics, geometry.centerX, geometry.centerY, geometry.outerRadius, start, end, frame, isHovered ? 1 : 0);
            drawArcStroke(graphics, geometry.centerX, geometry.centerY, geometry.innerRadius, start, end, isHovered ? frame : (frame & 0x88FFFFFF), 0);
            if (isHovered) {
                drawArcStroke(graphics, geometry.centerX, geometry.centerY, geometry.outerRadius + 1, start, end, glow, 1);
                drawHoveredSegmentSparks(graphics, geometry, start, end, pulse);
            }

            drawPixelLine(
                graphics,
                geometry.centerX + (int) Math.round(Math.cos(start) * geometry.innerRadius),
                geometry.centerY + (int) Math.round(Math.sin(start) * geometry.innerRadius),
                geometry.centerX + (int) Math.round(Math.cos(start) * geometry.outerRadius),
                geometry.centerY + (int) Math.round(Math.sin(start) * geometry.outerRadius),
                frame,
                isHovered ? 1 : 0
            );
            drawPixelLine(
                graphics,
                geometry.centerX + (int) Math.round(Math.cos(end) * geometry.innerRadius),
                geometry.centerY + (int) Math.round(Math.sin(end) * geometry.innerRadius),
                geometry.centerX + (int) Math.round(Math.cos(end) * geometry.outerRadius),
                geometry.centerY + (int) Math.round(Math.sin(end) * geometry.outerRadius),
                frame,
                isHovered ? 1 : 0
            );

            double mid = (start + end) * 0.5;
            int labelRadius = radialLabelRadius(geometry);
            int labelX = geometry.centerX + (int) Math.round(Math.cos(mid) * labelRadius);
            int labelY = geometry.centerY + (int) Math.round(Math.sin(mid) * labelRadius);
            int maxTextWidth = Math.max(36, sectorLabelWidth(geometry, entries.size()));
            Component fitted = fittedRadialLabel(entry.label(), maxTextWidth);
            if (!fitted.getString().equals(entry.label().getString())) {
                radialTruncatedTooltips.put(i, entry.label());
            }

            int textWidth = this.font.width(fitted);
            int textColor = isHovered ? themed(0xFFF6EBFF, 0xFFF1FFFF) : themed(0xFFE3D0FF, 0xFFD7F4FF);
            if (isHovered) {
                int labelPadX = 4;
                int labelPadY = 2;
                int backing = ((86 + (int) (42 * pulse)) << 24) | themed(0x2A1836, 0x103447);
                int backingEdge = ((160 + (int) (40 * pulse)) << 24) | themed(0xE0B9FF, 0x8CE7FF);
                graphics.fill(
                    labelX - (textWidth / 2) - labelPadX,
                    labelY - (this.font.lineHeight / 2) - labelPadY,
                    labelX + (textWidth / 2) + labelPadX,
                    labelY + (this.font.lineHeight / 2) + labelPadY,
                    backing
                );
                graphics.fill(
                    labelX - (textWidth / 2) - labelPadX,
                    labelY - (this.font.lineHeight / 2) - labelPadY,
                    labelX + (textWidth / 2) + labelPadX,
                    labelY - (this.font.lineHeight / 2) - labelPadY + 1,
                    backingEdge
                );
                graphics.fill(
                    labelX - (textWidth / 2) - labelPadX,
                    labelY + (this.font.lineHeight / 2) + labelPadY - 1,
                    labelX + (textWidth / 2) + labelPadX,
                    labelY + (this.font.lineHeight / 2) + labelPadY,
                    backingEdge
                );
            }
            graphics.drawString(
                this.font,
                fitted,
                labelX - (textWidth / 2),
                labelY - (this.font.lineHeight / 2),
                textColor,
                true
            );
        }

        int coreAlpha = 78 + (int) (28 * pulse);
        int core = (coreAlpha << 24) | themed(0x25142E, 0x0F2B34);
        graphics.fill(
            geometry.centerX - geometry.innerRadius + 3,
            geometry.centerY - geometry.innerRadius + 3,
            geometry.centerX + geometry.innerRadius - 3,
            geometry.centerY + geometry.innerRadius - 3,
            core
        );
        if (totalPages > 1) {
            graphics.drawCenteredString(
                this.font,
                Component.literal((currentPage + 1) + "/" + totalPages),
                geometry.centerX,
                geometry.centerY + 8,
                themed(0xDAB8FF, 0xB7ECFF)
            );
        }
    }

    private void drawOpeningTrace(
        GuiGraphics graphics,
        int panelX,
        int panelY,
        int panelWidth,
        int panelHeight,
        float progress,
        float pulse
    ) {
        if (progress <= 0.0f) {
            return;
        }

        int cx = panelX + panelWidth / 2;
        int cy = panelY + panelHeight / 2;
        int radius = Math.min(panelWidth, panelHeight) / 2 + 10;

        int[] xs = new int[6];
        int[] ys = new int[6];
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians((60.0 * i) - 30.0);
            xs[i] = cx + (int) Math.round(Math.cos(angle) * radius);
            ys[i] = cy + (int) Math.round(Math.sin(angle) * radius);
        }

        float tracedSegments = progress * 6.0f;
        int glowAlpha = 35 + (int) (80 * pulse);
        int lineAlpha = 95 + (int) (120 * progress);
        int glowColor = (glowAlpha << 24) | themed(0xC99CFF, 0x94E7FF);
        int lineColor = (lineAlpha << 24) | themed(0xF0DEFF, 0xD8F6FF);

        for (int seg = 0; seg < 6; seg++) {
            float segAmount = Mth.clamp(tracedSegments - seg, 0.0f, 1.0f);
            if (segAmount <= 0.0f) {
                continue;
            }

            int next = (seg + 1) % 6;
            int x2 = (int) Math.round(Mth.lerp(segAmount, xs[seg], xs[next]));
            int y2 = (int) Math.round(Mth.lerp(segAmount, ys[seg], ys[next]));

            drawPixelLine(graphics, xs[seg], ys[seg], x2, y2, glowColor, 1);
            drawPixelLine(graphics, xs[seg], ys[seg], x2, y2, lineColor, 0);
        }

        int coreAlpha = 45 + (int) (120 * progress);
        int coreColor = (coreAlpha << 24) | themed(0xD4BAFF, 0xB8ECFF);
        graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, coreColor);
    }

    private void drawLivingGlyphPerimeter(GuiGraphics graphics, int panelX, int panelY, int panelWidth, int panelHeight, float pulse) {
        int alpha = 55 + (int) (50 * pulse);
        int color = (alpha << 24) | themed(0xD3B6FF, 0xA8EBFF);

        drawPerimeterRunes(graphics, panelX - 3, panelY - 3, panelWidth + 6, panelHeight + 6, color, true);
        drawPerimeterRunes(graphics, panelX - 5, panelY - 5, panelWidth + 10, panelHeight + 10, color & 0x88FFFFFF, false);
    }

    private void drawPerimeterRunes(
        GuiGraphics graphics,
        int x,
        int y,
        int w,
        int h,
        int color,
        boolean clockwise
    ) {
        int stride = 6;
        int perimeter = (w * 2) + (h * 2);
        int offset = clockwise ? animTick * 2 : -animTick * 2;

        for (int d = 0; d < perimeter; d += stride) {
            int moved = Math.floorMod(d + offset, perimeter);
            int px;
            int py;
            if (moved < w) {
                px = x + moved;
                py = y;
            } else if (moved < w + h) {
                px = x + w;
                py = y + (moved - w);
            } else if (moved < (w * 2) + h) {
                px = x + w - (moved - (w + h));
                py = y + h;
            } else {
                px = x;
                py = y + h - (moved - ((w * 2) + h));
            }

            int gate = (px * 17 + py * 13 + animTick * 9) & 7;
            if (gate <= 2) {
                continue;
            }

            graphics.fill(px, py, px + 1, py + 1, color);
            if ((gate & 1) == 0) {
                graphics.fill(px - 1, py, px, py + 1, color & 0x66FFFFFF);
            }
        }
    }

    private void drawArcanePanel(GuiGraphics graphics, int panelX, int panelY, int panelWidth, int panelHeight, float pulse) {
        int glowAlpha = 24 + (int) (40 * pulse);
        int glow = (glowAlpha << 24) | themed(0x8C5DFF, 0x4FC7FF);

        graphics.fill(panelX - 5, panelY - 5, panelX + panelWidth + 5, panelY + panelHeight + 5, glow);
        graphics.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2, panelY + panelHeight + 2, themed(0x3F221436, 0x3F122A37));

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, themed(0xD0130D1F, 0xD00D1B2B));
        graphics.fill(panelX + 3, panelY + 3, panelX + panelWidth - 3, panelY + panelHeight - 3, themed(0xCC1E1433, 0xCC142A3D));

        // TODO: Should probably be a loop with an array of offsets and colors, but this is only two layers so whatever.
        int borderA = themed(0xFF9E79FF, 0xFF7DD8FF);
        int borderB = themed(0xFF5D3AA1, 0xFF316F92);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, borderA);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, borderA);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, borderA);
        graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, borderA);

        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + 2, borderB);
        graphics.fill(panelX + 1, panelY + panelHeight - 2, panelX + panelWidth - 1, panelY + panelHeight - 1, borderB);
        graphics.fill(panelX + 1, panelY + 1, panelX + 2, panelY + panelHeight - 1, borderB);
        graphics.fill(panelX + panelWidth - 2, panelY + 1, panelX + panelWidth - 1, panelY + panelHeight - 1, borderB);

        drawCornerNotch(graphics, panelX, panelY, 6, themed(0xFFBCA6FF, 0xFFB6EDFF));
        drawCornerNotch(graphics, panelX + panelWidth, panelY, 6, themed(0xFFBCA6FF, 0xFFB6EDFF));
        drawCornerNotch(graphics, panelX, panelY + panelHeight, 6, themed(0xFFBCA6FF, 0xFFB6EDFF));
        drawCornerNotch(graphics, panelX + panelWidth, panelY + panelHeight, 6, themed(0xFFBCA6FF, 0xFFB6EDFF));
    }

    private void drawEntryBackplates(GuiGraphics graphics, int panelX, int panelY, List<MenuEntry> entries, int columns) {
        List<PositionedEntry> placements = computePlacements(entries, panelX, panelY, columns);

        for (PositionedEntry placement : placements) {
            MenuEntry entry = placement.entry;
            int x = placement.bounds.x;
            int y = placement.bounds.y;
            int w = placement.bounds.w;

            int base = entry.isSection()
                ? themed(0x442A1A49, 0x44162F45)
                : entry.isSelectList()
                    ? themed(0x66302A5A, 0x661E3D61)
                : entry.isCheckbox()
                    ? themed(0x66334F34, 0x66224A2F)
                : entry.isNumericInput()
                    ? themed(0x66303251, 0x66253A52)
                : entry.isDropdown()
                    ? themed(0x6630405F, 0x66223F63)
                : entry.isInput()
                    ? themed(0x663A2A57, 0x66233C57)
                    : themed(0x55331752, 0x55274E66);
            int frame = entry.isSection()
                ? themed(0xFFD8B2FF, 0xFFB7EAFF)
                : entry.isSelectList()
                    ? themed(0xFF98B6FF, 0xFF8ED8FF)
                : entry.isCheckbox()
                    ? themed(0xFFA4E3A0, 0xFF89DDA3)
                : entry.isNumericInput()
                    ? themed(0xFF9BB6FF, 0xFF9ACFFB)
                : entry.isDropdown()
                    ? themed(0xFF92BFFF, 0xFF89DAFF)
                : entry.isInput()
                    ? themed(0xFF7FB8FF, 0xFF94E4FF)
                    : themed(0xFFC59BFF, 0xFF8FCFFF);
            int rune = entry.isSection()
                ? themed(0xAAA97BFF, 0xAA8EDCFF)
                : entry.isSelectList()
                    ? themed(0xAA9DBBFF, 0xAA8ED9FF)
                : entry.isCheckbox()
                    ? themed(0xAA9FE39A, 0xAA87DEA1)
                : entry.isNumericInput()
                    ? themed(0xAA9FB7FF, 0xAA9AD0FA)
                : entry.isDropdown()
                    ? themed(0xAA9CC5FF, 0xAA9BE7FF)
                : entry.isInput()
                    ? themed(0xAA9BD3FF, 0xAACAF1FF)
                    : themed(0xAADDCCFF, 0xAA9AE8FF);

            graphics.fill(x - 1, y - 1, x + w + 1, y + placement.bounds.h + 1, base);
            graphics.fill(x - 1, y - 1, x + w + 1, y, frame);
            graphics.fill(x - 1, y + placement.bounds.h, x + w + 1, y + placement.bounds.h + 1, frame);
            graphics.fill(x - 1, y - 1, x, y + placement.bounds.h + 1, frame);
            graphics.fill(x + w, y - 1, x + w + 1, y + placement.bounds.h + 1, frame);

            int midY = y + placement.bounds.h / 2;
            if (entry.isSection()) {
                graphics.fill(x + 4, midY, x + w - 4, midY + 1, rune);
            } else {
                graphics.fill(x + 4, midY, x + 8, midY + 1, rune);
                graphics.fill(x + w - 8, midY, x + w - 4, midY + 1, rune);
            }
        }
    }

    private void drawSectionLabels(GuiGraphics graphics, int panelX, int panelY, List<MenuEntry> entries, int columns) {
        List<PositionedEntry> placements = computePlacements(entries, panelX, panelY, columns);

        for (PositionedEntry placement : placements) {
            MenuEntry entry = placement.entry;
            if (!entry.isSection()) {
                continue;
            }

            int x = placement.bounds.x;
            int y = placement.bounds.y;
            int w = placement.bounds.w;

            Component label = entry.label();
            int tx = x + 8;
            int ty = y + (placement.bounds.h - this.font.lineHeight) / 2;
            graphics.drawString(this.font, label, tx, ty, themed(0xFFE8D0FF, 0xFFD9F4FF), true);

            int lineY = y + placement.bounds.h / 2;
            int lineStart = tx + Math.min(this.font.width(label) + 8, w - 16);
            int lineEnd = x + w - 8;
            if (lineEnd > lineStart) {
                int lineColor = themed(0x88D8B8FF, 0x88A8E8FF);
                graphics.fill(lineStart, lineY, lineEnd, lineY + 1, lineColor);
            }
        }
    }

    private void drawRuneLinks(GuiGraphics graphics, int panelX, int panelY, List<MenuEntry> entries, int columns, float pulse) {
        List<PositionedEntry> placements = computePlacements(entries, panelX, panelY, columns);

        EntryBounds activeSection = null;
        int lineAlpha = 40 + (int) (50 * pulse);
        int linkColor = (lineAlpha << 24) | themed(0xCFAAFF, 0xA6E8FF);

        for (PositionedEntry placement : placements) {
            if (placement.entry.isSection()) {
                activeSection = placement.bounds;
                continue;
            }

            if (activeSection == null) {
                continue;
            }

            int sx = activeSection.x + 8;
            int sy = activeSection.y + activeSection.h - 2;
            int ex = placement.bounds.x + 8;
            int ey = placement.bounds.y + (placement.bounds.h / 2);
            drawPixelLine(graphics, sx, sy, ex, ey, linkColor, 0);
            graphics.fill(ex - 1, ey - 1, ex + 1, ey + 1, themed(0x99E0C4FF, 0x99AFE9FF));
        }
    }

    private String revealText(String full, int tick, int delay) {
        if (full.isEmpty()) {
            return full;
        }

        if (tick <= delay) {
            return "";
        }

        float p = Mth.clamp((tick - delay) / (float) INK_REVEAL_TICKS, 0.0f, 1.0f);
        int chars = (int) Math.ceil(full.length() * p);
        if (chars <= 0) {
            return "";
        }
        return full.substring(0, Math.min(chars, full.length()));
    }

    private void drawHoverShimmer(GuiGraphics graphics, int mouseX, int mouseY, float pulse) {
        if (menu.layout() == MenuPayload.Layout.RADIAL) {
            return;
        }

        for (EntryBounds bounds : entryBounds.values()) {
            if (!bounds.contains(mouseX, mouseY)) {
                continue;
            }

            int shimmerAlpha = 35 + (int) (45 * pulse);
            int shimmer = (shimmerAlpha << 24) | themed(0xE4C3FF, 0xA6EAFF);

            int cycle = bounds.w + 12;
            int localTick = animTick % cycle;
            int sweepX = bounds.x - 6 + localTick;
            int x0 = Math.max(bounds.x, sweepX);
            int x1 = Math.min(bounds.x + bounds.w, sweepX + 8);
            if (x1 > x0) {
                graphics.fill(x0, bounds.y - 1, x1, bounds.y + bounds.h + 1, shimmer);
            }

            int frameAlpha = 85 + (int) (80 * pulse);
            int frame = (frameAlpha << 24) | themed(0xB285FF, 0x84DFFF);
            graphics.fill(bounds.x - 2, bounds.y - 2, bounds.x + bounds.w + 2, bounds.y - 1, frame);
            graphics.fill(bounds.x - 2, bounds.y + bounds.h + 1, bounds.x + bounds.w + 2, bounds.y + bounds.h + 2, frame);
            graphics.fill(bounds.x - 2, bounds.y - 2, bounds.x - 1, bounds.y + bounds.h + 2, frame);
            graphics.fill(bounds.x + bounds.w + 1, bounds.y - 2, bounds.x + bounds.w + 2, bounds.y + bounds.h + 2, frame);
        }
    }

    private void drawSigils(GuiGraphics graphics, int panelX, int panelY, int panelWidth, int panelHeight, float pulse) {
        int sigilColor = ((70 + (int) (40 * pulse)) << 24) | themed(0xC7A2FF, 0x98E4FF);
        int crossColor = ((120 + (int) (60 * pulse)) << 24) | themed(0xE8D6FF, 0xCDF5FF);

        drawSigil(graphics, panelX - 10, panelY - 10, sigilColor, crossColor);
        drawSigil(graphics, panelX + panelWidth + 10, panelY - 10, sigilColor, crossColor);
        drawSigil(graphics, panelX - 10, panelY + panelHeight + 10, sigilColor, crossColor);
        drawSigil(graphics, panelX + panelWidth + 10, panelY + panelHeight + 10, sigilColor, crossColor);
    }

    private void drawSigil(GuiGraphics graphics, int cx, int cy, int ringColor, int coreColor) {
        int[] sprite = {
            0b0011100,
            0b0100010,
            0b1001001,
            0b1010101,
            0b1001001,
            0b0100010,
            0b0011100
        };
        drawRuneSprite(graphics, cx - 3, cy - 3, ringColor, sprite);

        graphics.fill(cx - 1, cy - 1, cx + 1, cy + 1, coreColor);
    }

    private void drawRuneSprite(GuiGraphics graphics, int x, int y, int color, int[] rows) {
        for (int row = 0; row < rows.length; row++) {
            int bits = rows[row];
            for (int col = 0; col < 7; col++) {
                int mask = 1 << (6 - col);
                if ((bits & mask) != 0) {
                    graphics.fill(x + col, y + row, x + col + 1, y + row + 1, color);
                }
            }
        }
    }

    private void drawPixelLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color, int thickness) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            graphics.fill(x1 - thickness, y1 - thickness, x1 + thickness + 1, y1 + thickness + 1, color);
            return;
        }

        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int px = Math.round(Mth.lerp(t, x1, x2));
            int py = Math.round(Mth.lerp(t, y1, y2));
            graphics.fill(px - thickness, py - thickness, px + thickness + 1, py + thickness + 1, color);
        }
    }

    private void drawArcStroke(
        GuiGraphics graphics,
        int cx,
        int cy,
        int radius,
        double start,
        double end,
        int color,
        int thickness
    ) {
        int steps = Math.max(14, (int) Math.ceil(Math.abs(end - start) * radius * 0.85));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double angle = Mth.lerp(t, start, end);
            int px = cx + (int) Math.round(Math.cos(angle) * radius);
            int py = cy + (int) Math.round(Math.sin(angle) * radius);
            graphics.fill(px - thickness, py - thickness, px + thickness + 1, py + thickness + 1, color);
        }
    }

    private void drawRadialBand(
        GuiGraphics graphics,
        RadialGeometry geometry,
        double start,
        double end,
        int color,
        int segments
    ) {
        int steps = Math.max(4, segments);
        Matrix4f matrix = graphics.pose().last().pose();
        float a = ((color >>> 24) & 0xFF) / 255.0f;
        float r = ((color >>> 16) & 0xFF) / 255.0f;
        float g = ((color >>> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < steps; i++) {
            double t0 = i / (double) steps;
            double t1 = (i + 1) / (double) steps;
            double a0 = Mth.lerp(t0, start, end);
            double a1 = Mth.lerp(t1, start, end);

            float ix0 = geometry.centerX + (float) (Math.cos(a0) * geometry.innerRadius);
            float iy0 = geometry.centerY + (float) (Math.sin(a0) * geometry.innerRadius);
            float ox0 = geometry.centerX + (float) (Math.cos(a0) * geometry.outerRadius);
            float oy0 = geometry.centerY + (float) (Math.sin(a0) * geometry.outerRadius);
            float ix1 = geometry.centerX + (float) (Math.cos(a1) * geometry.innerRadius);
            float iy1 = geometry.centerY + (float) (Math.sin(a1) * geometry.innerRadius);
            float ox1 = geometry.centerX + (float) (Math.cos(a1) * geometry.outerRadius);
            float oy1 = geometry.centerY + (float) (Math.sin(a1) * geometry.outerRadius);

            builder.vertex(matrix, ix0, iy0, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, ox0, oy0, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, ix1, iy1, 0.0f).color(r, g, b, a).endVertex();

            builder.vertex(matrix, ix1, iy1, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, ox0, oy0, 0.0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, ox1, oy1, 0.0f).color(r, g, b, a).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
    }

    private void drawHoveredSegmentSparks(
        GuiGraphics graphics,
        RadialGeometry geometry,
        double start,
        double end,
        float pulse
    ) {
        double sweep = end - start;
        if (sweep <= 0.0) {
            return;
        }

        int baseAlpha = 128 + (int) (72 * pulse);
        int bright = (Math.min(255, baseAlpha + 50) << 24) | themed(0xF4D6FF, 0xD9F7FF);
        int tail = ((baseAlpha / 2) << 24) | themed(0xB987FF, 0x88DCFF);
        float phase = (animTick & 1023) * 0.067f;

        for (int i = 0; i < RADIAL_HOVER_SPARK_COUNT; i++) {
            float seed = i * 0.731f;
            float drift = (phase + seed) - (float) Math.floor(phase + seed);
            double angle = start + (drift * sweep);
            double radialBias = 0.70 + 0.24 * Math.sin((phase * 2.2f) + seed * 4.1f);
            int radius = geometry.innerRadius + (int) Math.round((geometry.outerRadius - geometry.innerRadius) * radialBias);

            int px = geometry.centerX + (int) Math.round(Math.cos(angle) * radius);
            int py = geometry.centerY + (int) Math.round(Math.sin(angle) * radius);

            graphics.fill(px, py, px + 1, py + 1, bright);

            for (int t = 1; t <= RADIAL_HOVER_SPARK_TRAIL; t++) {
                int tx = geometry.centerX + (int) Math.round(Math.cos(angle - (0.018 * t)) * radius);
                int ty = geometry.centerY + (int) Math.round(Math.sin(angle - (0.018 * t)) * radius);
                graphics.fill(tx, ty, tx + 1, ty + 1, tail);
            }
        }
    }

    private int contentHeightForLayout(List<MenuEntry> entries, int columns) {
        if (menu.layout() == MenuPayload.Layout.RADIAL) {
            return (RADIAL_OUTER_RADIUS * 2) + 12;
        }

        if (entries.isEmpty()) {
            return BUTTON_HEIGHT;
        }

        if (columns <= 1) {
            int height = 0;
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    height += BUTTON_SPACING;
                }
                height += entryHeight(entries.get(i));
            }
            return Math.max(BUTTON_HEIGHT, height);
        }

        int height = 0;
        int col = 0;
        for (MenuEntry entry : entries) {
            if (entry.isSection() || entry.isSelectList()) {
                if (col > 0) {
                    if (height > 0) {
                        height += BUTTON_SPACING;
                    }
                    height += BUTTON_HEIGHT;
                    col = 0;
                }
                if (height > 0) {
                    height += BUTTON_SPACING;
                }
                height += entryHeight(entry);
                continue;
            }

            col++;
            if (col >= columns) {
                if (height > 0) {
                    height += BUTTON_SPACING;
                }
                height += BUTTON_HEIGHT;
                col = 0;
            }
        }

        if (col > 0) {
            if (height > 0) {
                height += BUTTON_SPACING;
            }
            height += BUTTON_HEIGHT;
        }

        return Math.max(BUTTON_HEIGHT, height);
    }

    private List<PositionedEntry> computeListPlacements(List<MenuEntry> entries, int panelX, int panelY) {
        List<PositionedEntry> placements = new ArrayList<>(entries.size());
        int rowStartY = panelY + PANEL_PADDING + this.font.lineHeight + TITLE_MARGIN;
        int y = rowStartY;

        for (int i = 0; i < entries.size(); i++) {
            MenuEntry entry = entries.get(i);
            int height = entryHeight(entry);
            int x = panelX + PANEL_PADDING;
            if (i > 0) {
                y += BUTTON_SPACING;
            }
            placements.add(
                new PositionedEntry(i, entry, new EntryBounds(x, y, LIST_BUTTON_WIDTH, height))
            );
            y += height;
        }
        return placements;
    }

    private List<PositionedEntry> computeGridPlacements(List<MenuEntry> entries, int panelX, int panelY, int columns) {
        List<PositionedEntry> placements = new ArrayList<>(entries.size());
        int rowStartY = panelY + PANEL_PADDING + this.font.lineHeight + TITLE_MARGIN;
        int fullWidth = columns * GRID_BUTTON_WIDTH + (columns - 1) * BUTTON_SPACING;

        int y = rowStartY;
        int col = 0;
        for (int i = 0; i < entries.size(); i++) {
            MenuEntry entry = entries.get(i);
            if (entry.isSection() || entry.isSelectList()) {
                if (col > 0) {
                    y += BUTTON_HEIGHT + BUTTON_SPACING;
                    col = 0;
                }

                placements.add(
                    new PositionedEntry(i, entry, new EntryBounds(panelX + PANEL_PADDING, y, fullWidth, entryHeight(entry)))
                );
                y += entryHeight(entry) + BUTTON_SPACING;
                continue;
            }

            int x = panelX + PANEL_PADDING + col * (GRID_BUTTON_WIDTH + BUTTON_SPACING);
            placements.add(new PositionedEntry(i, entry, new EntryBounds(x, y, GRID_BUTTON_WIDTH, BUTTON_HEIGHT)));

            col++;
            if (col >= columns) {
                col = 0;
                y += BUTTON_HEIGHT + BUTTON_SPACING;
            }
        }

        return placements;
    }

    private List<PositionedEntry> computeRadialPlacements(List<MenuEntry> entries, int panelX, int panelY, int panelWidth) {
        return List.of();
    }

    private RadialGeometry radialGeometry(int panelX, int panelY, int panelWidth, List<MenuEntry> entries) {
        int contentTop = panelY + PANEL_PADDING + this.font.lineHeight + TITLE_MARGIN;
        return new RadialGeometry(
            panelX + (panelWidth / 2),
            contentTop + (contentHeightForLayout(entries, 1) / 2),
            RADIAL_OUTER_RADIUS,
            RADIAL_INNER_RADIUS
        );
    }

    private double radialStartAngle(int index, int count) {
        return (-Math.PI / 2.0) + (Math.PI * 2.0 * index / count);
    }

    private double radialEndAngle(int index, int count) {
        return (-Math.PI / 2.0) + (Math.PI * 2.0 * (index + 1) / count);
    }

    private int radialLabelRadius(RadialGeometry geometry) {
        return geometry.innerRadius + ((geometry.outerRadius - geometry.innerRadius) / 2);
    }

    private int sectorLabelWidth(RadialGeometry geometry, int count) {
        double halfSweep = Math.PI / count;
        double arcHalfWidth = Math.sin(halfSweep) * radialLabelRadius(geometry);
        int radialInset = geometry.outerRadius - geometry.innerRadius - 8;
        return Math.max(42, Math.min((int) Math.floor(arcHalfWidth * 2.0), radialInset * 2));
    }

    private int radialIndexAt(int mouseX, int mouseY, List<MenuEntry> entries, int panelX, int panelY, int panelWidth) {
        if (entries.isEmpty()) {
            return -1;
        }

        RadialGeometry geometry = radialGeometry(panelX, panelY, panelWidth, entries);
        double dx = mouseX - geometry.centerX;
        double dy = mouseY - geometry.centerY;
        double distanceSq = (dx * dx) + (dy * dy);
        if (distanceSq < (geometry.innerRadius * geometry.innerRadius) || distanceSq > (geometry.outerRadius * geometry.outerRadius)) {
            return -1;
        }

        double angle = Math.atan2(dy, dx) + (Math.PI / 2.0);
        if (angle < 0.0) {
            angle += Math.PI * 2.0;
        }
        int index = (int) Math.floor(angle / ((Math.PI * 2.0) / entries.size()));
        return Mth.clamp(index, 0, entries.size() - 1);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menu.layout() == MenuPayload.Layout.RADIAL && button == 0) {
            List<MenuEntry> entries = getPageEntries(entriesForLayout(menu.entries()));
            int panelWidth = panelWidthForLayout(entries, 1);
            int panelHeight = panelHeightForContent(contentHeightForLayout(entries, 1), totalPages > 1);
            int panelX = (this.width - panelWidth) / 2;
            int panelY = (this.height - panelHeight) / 2;
            int hovered = radialIndexAt((int) mouseX, (int) mouseY, entries, panelX, panelY, panelWidth);
            if (hovered >= 0 && hovered < entries.size()) {
                selectEntry(entries.get(hovered));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private List<PositionedEntry> computePlacements(List<MenuEntry> entries, int panelX, int panelY, int columns) {
        return switch (menu.layout()) {
            case LIST -> computeListPlacements(entries, panelX, panelY);
            case GRID -> computeGridPlacements(entries, panelX, panelY, columns);
            case RADIAL -> computeRadialPlacements(entries, panelX, panelY, panelWidthForLayout(entries, columns));
        };
    }

    private int entryHeight(MenuEntry entry) {
        if (entry.isSection()) {
            return SECTION_HEIGHT;
        }
        if (entry.isSelectList()) {
            int rows = Math.max(1, Math.min(entry.selectMaxRows(), 12));
            return rows * SELECT_LIST_ROW_HEIGHT;
        }
        return BUTTON_HEIGHT;
    }

    private int themed(int ritual, int scholar) {
        return menu.theme() == MenuPayload.Theme.RITUAL ? ritual : scholar;
    }

    private void drawCornerNotch(GuiGraphics graphics, int x, int y, int size, int color) {
        int sx = x < this.width / 2 ? 1 : -1;
        int sy = y < this.height / 2 ? 1 : -1;

        for (int i = 0; i < size; i++) {
            int x1 = x + (sx > 0 ? i : -i - 1);
            int y1 = y + (sy > 0 ? i : -i - 1);
            graphics.fill(x1, y, x1 + 1, y + sy, color);
            graphics.fill(x, y1, x + sx, y1 + 1, color);
        }
    }
}
