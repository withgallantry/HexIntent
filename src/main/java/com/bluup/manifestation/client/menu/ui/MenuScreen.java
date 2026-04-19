package com.bluup.manifestation.client.menu.ui;

import com.bluup.manifestation.client.ActiveMenuState;
import com.bluup.manifestation.client.menu.execution.MenuActionSender;
import com.bluup.manifestation.common.menu.MenuEntry;
import com.bluup.manifestation.common.menu.MenuPayload;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HUD-style modal that renders the menu described by a {@link MenuPayload}.
 *
 * <p>Layout behavior:
 * <ul>
 *   <li>{@code LIST} — one column, buttons stacked top-to-bottom. Fixed
 *       reasonable width; scales panel height to fit all entries.</li>
 *   <li>{@code GRID} — player-specified column count, buttons laid out
 *       left-to-right top-to-bottom. Panel width scales with column count.</li>
 * </ul>
 *
 * <p>Not a pause screen — world keeps rendering behind the modal, reinforcing
 * the "magical HUD" feel. Any interaction that closes the screen clears
 * {@link ActiveMenuState}; "only one menu at a time" and "single-use" both
 * fall out of that.
 */
public final class MenuScreen extends Screen {

    // Shared layout constants.
    private static final int BUTTON_HEIGHT = 20;
    private static final int SECTION_HEIGHT = 26;
    private static final int BUTTON_SPACING = 4;
    private static final int TITLE_MARGIN = 8;
    private static final int PANEL_PADDING = 8;

    // LIST-specific
    private static final int LIST_BUTTON_WIDTH = 200;

    // GRID-specific
    private static final int GRID_BUTTON_WIDTH = 120;
    private static final int INTRO_TRACE_TICKS = 12;
    private static final int PAGE_BUTTON_WIDTH = 20;
    private static final int PAGE_CONTROLS_HEIGHT = 20;
    private static final int PAGE_CONTROLS_MARGIN = 6;
    private static final int MIN_PANEL_MARGIN = 24;

    private final MenuPayload menu;
    private final InteractionHand hand;
    private final Map<Integer, EditBox> inputBoxes = new LinkedHashMap<>();
    private final Map<Integer, MenuSlider> sliderBoxes = new LinkedHashMap<>();
    private final Map<Integer, MenuDropdown> dropdownBoxes = new LinkedHashMap<>();
    private final Map<Integer, EntryBounds> entryBounds = new LinkedHashMap<>();
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
            // Value is sampled when dispatching the button.
        }

        double getActualValue() {
            return Mth.lerp(this.value, min, max);
        }
    }

    private final class MenuDropdown {
        private final Component label;
        private final List<String> options;
        private int selected;
        private final Button button;

        MenuDropdown(int x, int y, int width, int height, Component label, List<String> options, int selected) {
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
            String selectedText = options.isEmpty() ? "-" : options.get(selected);
            this.button.setMessage(Component.literal(label.getString() + ": " + selectedText));
        }

        String selectedValue() {
            return options.isEmpty() ? "" : options.get(selected);
        }

        Button button() {
            return button;
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
        sliderBoxes.clear();
        dropdownBoxes.clear();
        entryBounds.clear();
        effectiveColumns = menu.layout() == MenuPayload.Layout.GRID
            ? computeEffectiveGridColumns(menu.columns())
            : 1;

        int rowsPerPage = computeRowsPerPage();
        entriesPerPage = Math.max(1, rowsPerPage * effectiveColumns);

        List<MenuEntry> allEntries = menu.entries();
        totalPages = Math.max(1, (allEntries.size() + entriesPerPage - 1) / entriesPerPage);
        currentPage = Mth.clamp(currentPage, 0, totalPages - 1);

        List<MenuEntry> pageEntries = getPageEntries(allEntries);
        switch (menu.layout()) {
            case LIST -> layoutList(pageEntries);
            case GRID -> layoutGrid(pageEntries, effectiveColumns);
        }

        if (totalPages > 1) {
            addPageControls(pageEntries, effectiveColumns);
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

    /**
     * Panel height calculation shared between layouts.
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

    private void addPageControls(List<MenuEntry> visibleEntries, int columns) {
        int panelWidth = panelWidthForColumns(columns);
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

        entryBounds.put(index, new EntryBounds(x, y, width, BUTTON_HEIGHT));

        if (entry.isInput()) {
            EditBox box = new EditBox(this.font, x, y, width, BUTTON_HEIGHT, entry.label());
            box.setHint(entry.label());
            inputBoxes.put(index, box);
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
        this.addRenderableWidget(
                Button.builder(entry.label(), btn -> selectEntry(captured))
                        .bounds(x, y, width, BUTTON_HEIGHT)
                        .build()
        );
    }

    private void selectEntry(MenuEntry entry) {
        if (!entry.isButton()) {
            return;
        }

        spawnClickFlare();

        // Clear state BEFORE dispatching, to be safe if execution ever
        // re-enters the screen loop mid-dispatch.
        ActiveMenuState.get().clear();
        MenuActionSender.send(entry, this.hand, collectInputValues());
        if (this.minecraft != null) {
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
        // Any dismissal path — Esc, screen-replacement, whatever — clears state.
        ActiveMenuState.get().clear();
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

        // Recompute panel bounds for the title overlay. We can't stash these
        // from init() because the screen dimensions could theoretically change
        // mid-life (resize).
        List<MenuEntry> entries = getPageEntries(menu.entries());
        int columns = menu.layout() == MenuPayload.Layout.GRID ? effectiveColumns : 1;
        int panelWidth = panelWidthForColumns(columns);
        int panelHeight = panelHeightForContent(contentHeightForLayout(entries, columns), totalPages > 1);

        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        float pulse = (Mth.sin((animTick + partialTick) / 10.0f) + 1.0f) * 0.5f;
        float introProgress = Mth.clamp((animTick + partialTick) / INTRO_TRACE_TICKS, 0.0f, 1.0f);

        drawArcanePanel(graphics, panelX, panelY, panelWidth, panelHeight, pulse);
        drawOpeningTrace(graphics, panelX, panelY, panelWidth, panelHeight, introProgress, pulse);
        drawEntryBackplates(graphics, panelX, panelY, entries, columns);
        drawSectionLabels(graphics, panelX, panelY, entries, columns);
        drawHoverShimmer(graphics, mouseX, mouseY, pulse);
        drawSigils(graphics, panelX, panelY, panelWidth, panelHeight, pulse);

        // Title centered at the top of the panel.
        Component title = menu.title();
        int titleWidth = this.font.width(title);
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
            String label = "Page " + (currentPage + 1) + "/" + totalPages;
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

    private void drawArcanePanel(GuiGraphics graphics, int panelX, int panelY, int panelWidth, int panelHeight, float pulse) {
        int glowAlpha = 24 + (int) (40 * pulse);
        int glow = (glowAlpha << 24) | themed(0x8C5DFF, 0x4FC7FF);

        graphics.fill(panelX - 5, panelY - 5, panelX + panelWidth + 5, panelY + panelHeight + 5, glow);
        graphics.fill(panelX - 2, panelY - 2, panelX + panelWidth + 2, panelY + panelHeight + 2, themed(0x3F221436, 0x3F122A37));

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, themed(0xD0130D1F, 0xD00D1B2B));
        graphics.fill(panelX + 3, panelY + 3, panelX + panelWidth - 3, panelY + panelHeight - 3, themed(0xCC1E1433, 0xCC142A3D));

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
        List<PositionedEntry> placements = columns == 1
            ? computeListPlacements(entries, panelX, panelY)
            : computeGridPlacements(entries, panelX, panelY, columns);

        for (PositionedEntry placement : placements) {
            MenuEntry entry = placement.entry;
            int x = placement.bounds.x;
            int y = placement.bounds.y;
            int w = placement.bounds.w;

            int base = entry.isSection()
                ? themed(0x442A1A49, 0x44162F45)
                : entry.isDropdown()
                    ? themed(0x6630405F, 0x66223F63)
                : entry.isInput()
                    ? themed(0x663A2A57, 0x66233C57)
                    : themed(0x55331752, 0x55274E66);
            int frame = entry.isSection()
                ? themed(0xFFD8B2FF, 0xFFB7EAFF)
                : entry.isDropdown()
                    ? themed(0xFF92BFFF, 0xFF89DAFF)
                : entry.isInput()
                    ? themed(0xFF7FB8FF, 0xFF94E4FF)
                    : themed(0xFFC59BFF, 0xFF8FCFFF);
            int rune = entry.isSection()
                ? themed(0xAAA97BFF, 0xAA8EDCFF)
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
        List<PositionedEntry> placements = columns == 1
            ? computeListPlacements(entries, panelX, panelY)
            : computeGridPlacements(entries, panelX, panelY, columns);

        for (PositionedEntry placement : placements) {
            MenuEntry entry = placement.entry;
            if (!entry.isSection()) {
                continue;
            }

            int x = placement.bounds.x;
            int y = placement.bounds.y;
            int w = placement.bounds.w;

            String label = entry.label().getString();
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

    private void drawHoverShimmer(GuiGraphics graphics, int mouseX, int mouseY, float pulse) {
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

    private int contentHeightForLayout(List<MenuEntry> entries, int columns) {
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
            if (entry.isSection()) {
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
                height += SECTION_HEIGHT;
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
            if (entry.isSection()) {
                if (col > 0) {
                    y += BUTTON_HEIGHT + BUTTON_SPACING;
                    col = 0;
                }

                placements.add(
                    new PositionedEntry(i, entry, new EntryBounds(panelX + PANEL_PADDING, y, fullWidth, SECTION_HEIGHT))
                );
                y += SECTION_HEIGHT + BUTTON_SPACING;
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

    private int entryHeight(MenuEntry entry) {
        return entry.isSection() ? SECTION_HEIGHT : BUTTON_HEIGHT;
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
