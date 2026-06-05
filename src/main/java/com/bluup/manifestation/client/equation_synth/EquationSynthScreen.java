package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.common.equation.EquationEvaluator;
import com.bluup.manifestation.common.equation.EquationParticleConfig;
import com.bluup.manifestation.common.equation.EquationParticleGenerator;
import com.bluup.manifestation.server.block.EquationSynthBlockEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class EquationSynthScreen extends Screen {
    private static final int LABEL_OFFSET_Y = 12;
    private static final int PANEL_HEIGHT = 388;
    private static final int PREVIEW_DOT_SIZE = 1;
    private static final int MAX_EXPR_CHARS = EquationParticleConfig.MAX_EXPR_CHARS;
    private static final int SHAPE_MODAL_W = 260;
    private static final int SHAPE_MODAL_H = 320;
    private static final int SHAPE_ITEM_H = 20;
    private static final double PREVIEW_CLOUD_RADIUS = 0.45;
    private static final int ACTION_BUTTON_GAP = 10;
    private static final int PANEL_CONTENT_TOP_PADDING = 6;

    private enum PanelState {
        SHAPE,
        COLOR,
        ANIMATION
    }

    private static PanelState lastOpenPanel = PanelState.SHAPE;

    private final BlockPos blockPos;

    private EditBox xExpr;
    private EditBox yExpr;
    private EditBox zExpr;
    private EditBox tMin;
    private EditBox tMax;
    private EditBox uMin;
    private EditBox uMax;
    private EditBox pointCount;
    private EditBox colorA_R;
    private EditBox colorA_G;
    private EditBox colorA_B;
    private EditBox colorB_R;
    private EditBox colorB_G;
    private EditBox colorB_B;
    private EditBox colorExprR;
    private EditBox colorExprG;
    private EditBox colorExprB;

    private Button useUButton;
    private Button colorModeButton;
    private boolean useU = true;
    private String colorMode = "gradient";

    private String status = "";
    private int statusColor = 0xAAAAAA;

    private int previewX;
    private int previewY;
    private int previewW;
    private int previewH;

    private final List<Dot> previewDots = new ArrayList<>();
    private boolean showShapeModal = false;
    private int shapeScroll = 0;
    private boolean formulaPanelCollapsed = false;
    private boolean colorPanelCollapsed = true;
    private boolean animationPanelCollapsed = true;
    private String animationPreset = "rotate";

    private int leftPanelX;
    private int leftPanelTop;
    private int leftPanelWidth;
    private int inputWidth;

    private Button shapePresetButton;
    private Button formulaPanelButton;
    private Button colorPanelButton;
    private Button animationPanelButton;
    private Button animationPresetButton;
    private AnimationSpeedSlider animationSpeedSlider;
    private DensitySlider densitySlider;
    private Button writeButton;
    private Button closeButton;

    private static final String[] ANIMATION_PRESETS = new String[] {
        "rotate",
        "spin_bob",
        "bob",
        "pulse",
        "orbit",
        "static"
    };

    private static final class AnimationSpeedSlider extends AbstractSliderButton {
        AnimationSpeedSlider(int x, int y, int width, int height, double initial) {
            super(x, y, width, height, Component.empty(), normalize(initial));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(String.format(java.util.Locale.ROOT, "Speed: %.2fx", getSpeed())));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }

        double getSpeed() {
            return 0.1 + (this.value * 3.9);
        }

        void setSpeed(double speed) {
            this.value = normalize(speed);
            updateMessage();
        }

        private static double normalize(double raw) {
            double clamped = Math.max(0.1, Math.min(4.0, raw));
            return (clamped - 0.1) / 3.9;
        }
    }

    private static final class DensitySlider extends AbstractSliderButton {
        DensitySlider(int x, int y, int width, int height, double initial) {
            super(x, y, width, height, Component.empty(), normalize(initial));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("Density: " + (int) Math.round(getDensity() * 100.0) + "%"));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }

        double getDensity() {
            return 0.1 + (this.value * 0.9);
        }

        void setDensity(double density) {
            this.value = normalize(density);
            updateMessage();
        }

        private static double normalize(double raw) {
            double clamped = Math.max(0.1, Math.min(1.0, raw));
            return (clamped - 0.1) / 0.9;
        }
    }

    // --- Shape preset modal ---
    private static class ShapePreset {
        final String name, x, y, z, pathMin, pathMax, sweepMin, sweepMax;
        ShapePreset(String name, String x, String y, String z, String pathMin, String pathMax, String sweepMin, String sweepMax) {
            this.name = name; this.x = x; this.y = y; this.z = z;
            this.pathMin = pathMin; this.pathMax = pathMax; this.sweepMin = sweepMin; this.sweepMax = sweepMax;
        }
    }
    private static final ShapePreset[] SHAPES = new ShapePreset[] {
        new ShapePreset("Ring / circle", "cos(path)", "sin(path)", "0", "0", "6.28", "0", "0"),
        new ShapePreset("Helix / spiral", "cos(path)", "sin(path)", "path / 3", "0", "18.84", "0", "0"),
        new ShapePreset("Sphere", "sin(sweep) * cos(path)", "sin(sweep) * sin(path)", "cos(sweep)", "0", "6.28", "0", "3.14"),
        new ShapePreset("Torus / donut", "(2 + cos(sweep)) * cos(path)", "(2 + cos(sweep)) * sin(path)", "sin(sweep)", "0", "6.28", "0", "6.28"),
        new ShapePreset("Wavy sheet", "path", "sweep", "sin(path) * cos(sweep)", "-3.14", "3.14", "-3.14", "3.14"),
        new ShapePreset("Cylinder", "cos(path)", "sin(path)", "sweep", "0", "6.28", "-2", "2"),
        new ShapePreset("Cone", "(1 - sweep) * cos(path)", "(1 - sweep) * sin(path)", "sweep * 3", "0", "6.28", "0", "1"),
        new ShapePreset("Flat disc", "sweep * cos(path)", "sweep * sin(path)", "0", "0", "6.28", "0", "1"),
        new ShapePreset("Bowl / dome", "sweep * cos(path)", "sweep * sin(path)", "sweep * sweep", "0", "6.28", "0", "1.5"),
        new ShapePreset("Ripple disc", "sweep * cos(path)", "sweep * sin(path)", "sin(sweep * 8) / 4", "0", "6.28", "0", "3"),
        new ShapePreset("Flower ring", "(1 + 0.3 * sin(path * 6)) * cos(path)", "(1 + 0.3 * sin(path * 6)) * sin(path)", "0", "0", "6.28", "0", "0"),
        new ShapePreset("Double helix", "cos(path)", "sin(path)", "path / 4 + sin(sweep * 3) * 0.2", "0", "18.84", "0", "6.28"),
        new ShapePreset("Magic vortex", "sweep * cos(path + sweep)", "sweep * sin(path + sweep)", "sweep", "0", "18.84", "0", "3"),
        new ShapePreset("Saddle surface", "path", "sweep", "(path * path - sweep * sweep) / 4", "-2", "2", "-2", "2"),
        new ShapePreset("Twisted ribbon", "cos(path) + sweep * cos(path / 2) * cos(path)", "sin(path) + sweep * cos(path / 2) * sin(path)", "sweep * sin(path / 2)", "0", "12.56", "-0.3", "0.3"),
        new ShapePreset("Ellipse", "2 * cos(path)", "sin(path)", "0", "0", "6.28", "0", "0"),
        new ShapePreset("Spiral ramp", "sweep * cos(path)", "sweep * sin(path)", "path / 12", "0", "18.84", "0", "1.5"),
        new ShapePreset("Ellipsoid", "1.5 * sin(sweep) * cos(path)", "sin(sweep) * sin(path)", "0.6 * cos(sweep)", "0", "6.28", "0", "3.14"),
        new ShapePreset("Thin torus", "(3 + 0.5 * cos(sweep)) * cos(path)", "(3 + 0.5 * cos(sweep)) * sin(path)", "0.5 * sin(sweep)", "0", "6.28", "0", "6.28"),
        new ShapePreset("Ripple sheet", "path", "sweep", "(sin(path * 3) + cos(sweep * 3)) / 3", "-3.14", "3.14", "-3.14", "3.14"),
        new ShapePreset("Paraboloid", "sweep * cos(path)", "sweep * sin(path)", "(sweep * sweep) / 2", "0", "6.28", "0", "2"),
        new ShapePreset("Möbius strip", "(1 + sweep * cos(path / 2)) * cos(path)", "(1 + sweep * cos(path / 2)) * sin(path)", "sweep * sin(path / 2)", "0", "6.28", "-0.3", "0.3"),
        new ShapePreset("Spring tube", "(2 + 0.3 * cos(sweep)) * cos(path)", "(2 + 0.3 * cos(sweep)) * sin(path)", "path / 6 + 0.3 * sin(sweep)", "0", "18.84", "0", "6.28"),
        new ShapePreset("Wave helix", "cos(path)", "sin(path)", "path / 4 + 0.5 * sin(path * 4)", "0", "18.84", "0", "0"),
        new ShapePreset("Lissajous curve", "sin(path * 3)", "sin(path * 4)", "sin(path * 5) / 2", "0", "6.28", "0", "0"),
        new ShapePreset("Orbital ring", "(2 + 0.3 * sin(path * 5)) * cos(path)", "(2 + 0.3 * sin(path * 5)) * sin(path)", "0.4 * cos(path * 5)", "0", "6.28", "0", "0"),
        new ShapePreset("Tornado", "(2 - sweep / 2) * cos(path)", "(2 - sweep / 2) * sin(path)", "sweep", "0", "18.84", "0", "3"),
        new ShapePreset("Hourglass surface", "(0.25 + sweep * sweep) * cos(path)", "(0.25 + sweep * sweep) * sin(path)", "sweep * 2", "0", "6.28", "-1", "1"),
        new ShapePreset("Clover sphere", "(1 + 0.25 * cos(path * 3)) * sin(sweep) * cos(path)", "(1 + 0.25 * cos(path * 3)) * sin(sweep) * sin(path)", "cos(sweep)", "0", "6.28", "0", "3.14"),
        new ShapePreset("Star disc", "(1 + 0.35 * cos(path * 5)) * sweep * cos(path)", "(1 + 0.35 * cos(path * 5)) * sweep * sin(path)", "0", "0", "6.28", "0", "1"),
        new ShapePreset("Arcane sigil ring", "(2 + 0.25 * sin(path * 8) + 0.15 * cos(path * 13)) * cos(path)", "(2 + 0.25 * sin(path * 8) + 0.15 * cos(path * 13)) * sin(path)", "0.15 * sin(path * 16)", "0", "6.28", "0", "0"),
        new ShapePreset("Celestial spiral gate", "(0.2 + sweep) * cos(path + sweep * 2)", "(0.2 + sweep) * sin(path + sweep * 2)", "sin(path * 3) * 0.25", "0", "18.84", "0", "2.5"),
        new ShapePreset("Floating rune veil", "path", "sweep", "sin(path * 4 + sweep * 2) * cos(sweep * 3) * 0.4", "-3.14", "3.14", "-2", "2"),
        new ShapePreset("Astral blossom", "(1 + 0.6 * sin(path * 7) * sin(sweep)) * sin(sweep) * cos(path)", "(1 + 0.6 * sin(path * 7) * sin(sweep)) * sin(sweep) * sin(path)", "cos(sweep) + 0.2 * cos(path * 7)", "0", "6.28", "0", "3.14"),
        new ShapePreset("Witchfire helix", "(1 + 0.2 * sin(path * 9)) * cos(path)", "(1 + 0.2 * sin(path * 9)) * sin(path)", "path / 5 + 0.3 * cos(path * 4)", "0", "25.12", "0", "0"),
        new ShapePreset("Summoning vortex", "(2.5 - sweep) * cos(path + sweep * 3)", "(2.5 - sweep) * sin(path + sweep * 3)", "sweep + 0.25 * sin(path * 6)", "0", "25.12", "0", "2.5"),
        new ShapePreset("Halo of thorns", "(2 + 0.4 * abs(sin(path * 12))) * cos(path)", "(2 + 0.4 * abs(sin(path * 12))) * sin(path)", "0.25 * cos(path * 12)", "0", "6.28", "0", "0"),
        new ShapePreset("Dreamcatcher web", "sweep * cos(path)", "sweep * sin(path)", "0.25 * sin(path * 5 + sweep * 8)", "0", "6.28", "0", "2"),
        new ShapePreset("Void eye", "(1 + 0.5 * cos(sweep)) * cos(path)", "0.45 * sin(sweep)", "(1 + 0.5 * cos(sweep)) * sin(path)", "0", "6.28", "0", "6.28"),
        new ShapePreset("Spell comet trail", "path / 2", "sin(path * 3) * (1 - sweep)", "cos(path * 4) * (1 - sweep)", "0", "6.28", "0", "1")
    };
    public EquationSynthScreen(BlockPos blockPos) {
        super(Component.literal("Equation Synthesizer"));
        this.blockPos = blockPos;
    }

    @Override
    protected void init() {
        int leftW = 342;
        int gap = 10;
        int panelX = Math.max(12, (this.width - (leftW + gap + 420)) / 2);
        this.leftPanelX = panelX;
        this.leftPanelWidth = leftW;
        this.inputWidth = leftW - 16;
        int rightX = this.leftPanelX + leftW + gap;
        int top = Math.max(18, (this.height - PANEL_HEIGHT) / 2);
        this.leftPanelTop = top;

        this.previewX = rightX;
        this.previewY = top;
        this.previewW = Math.min(420, this.width - rightX - 12);
        this.previewH = PANEL_HEIGHT;

        this.shapePresetButton = this.addRenderableWidget(Button.builder(Component.literal("Shape Preset"), b -> showShapeModal = true)
            .bounds(this.leftPanelX + 8, 0, this.inputWidth, 20)
            .build());

        this.formulaPanelButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (formulaPanelCollapsed) {
                formulaPanelCollapsed = false;
                colorPanelCollapsed = true;
                animationPanelCollapsed = true;
                lastOpenPanel = PanelState.SHAPE;
            } else {
                formulaPanelCollapsed = true;
            }
            syncPanelButtons();
            layoutLeftPanel();
        }).bounds(this.leftPanelX + 8, 0, this.inputWidth, 20).build());

        this.xExpr = addExprBox(this.leftPanelX + 8, 0, this.inputWidth, "(2+cos(sweep))*cos(path)");
        this.yExpr = addExprBox(this.leftPanelX + 8, 0, this.inputWidth, "(2+cos(sweep))*sin(path)");
        this.zExpr = addExprBox(this.leftPanelX + 8, 0, this.inputWidth, "sin(sweep)");

        this.tMin = addNumBox(this.leftPanelX + 8, 0, 66, "0");
        this.tMax = addNumBox(this.leftPanelX + 80, 0, 66, "6.283185307");
        this.uMin = addNumBox(this.leftPanelX + 154, 0, 66, "0");
        this.uMax = addNumBox(this.leftPanelX + 228, 0, 66, "6.283185307");

        this.pointCount = addNumBox(this.leftPanelX + 8, 0, this.inputWidth, "2000");

        this.useUButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            useU = !useU;
            syncUseUButton();
        }).bounds(this.leftPanelX + 8, 0, 100, 20).build());
        syncUseUButton();

        this.colorModeButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            colorMode = switch (colorMode) {
                case "single" -> "gradient";
                case "gradient" -> "expression";
                default -> "single";
            };
            syncColorModeButton();
        }).bounds(this.leftPanelX + 114, 0, 110, 20).build());
        syncColorModeButton();

        this.writeButton = this.addRenderableWidget(Button.builder(Component.literal("Write").withStyle(ChatFormatting.LIGHT_PURPLE), b -> writeToFocus())
            .bounds(this.leftPanelX + 8, 0, this.inputWidth, 20)
            .build());

        this.colorPanelButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (colorPanelCollapsed) {
                colorPanelCollapsed = false;
                formulaPanelCollapsed = true;
                animationPanelCollapsed = true;
                lastOpenPanel = PanelState.COLOR;
            } else {
                colorPanelCollapsed = true;
            }
            syncPanelButtons();
            layoutLeftPanel();
        }).bounds(this.leftPanelX + 8, 0, this.inputWidth, 20).build());

        this.animationPanelButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (animationPanelCollapsed) {
                animationPanelCollapsed = false;
                formulaPanelCollapsed = true;
                colorPanelCollapsed = true;
                lastOpenPanel = PanelState.ANIMATION;
            } else {
                animationPanelCollapsed = true;
            }
            syncPanelButtons();
            layoutLeftPanel();
        }).bounds(this.leftPanelX + 8, 0, this.inputWidth, 20).build());

        this.animationPresetButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            cycleAnimationPreset();
            syncAnimationPresetButton();
        }).bounds(this.leftPanelX + 8, 0, this.inputWidth, 20).build());
        syncAnimationPresetButton();

        this.animationSpeedSlider = this.addRenderableWidget(new AnimationSpeedSlider(this.leftPanelX + 8, 0, this.inputWidth, 20, 1.0));

        this.densitySlider = this.addRenderableWidget(new DensitySlider(this.leftPanelX + 8, 0, this.inputWidth, 20, 0.6));

        this.colorA_R = addNumBox(this.leftPanelX + 8, 0, 42, "0.96");
        this.colorA_G = addNumBox(this.leftPanelX + 52, 0, 42, "0.56");
        this.colorA_B = addNumBox(this.leftPanelX + 96, 0, 42, "0.64");
        this.colorB_R = addNumBox(this.leftPanelX + 148, 0, 42, "0.88");
        this.colorB_G = addNumBox(this.leftPanelX + 192, 0, 42, "0.78");
        this.colorB_B = addNumBox(this.leftPanelX + 236, 0, 42, "0.96");

        this.colorExprR = addExprBox(this.leftPanelX + 8, 0, this.inputWidth, "0.85");
        this.colorExprG = addExprBox(this.leftPanelX + 8, 0, this.inputWidth, "0.55 + 0.4*sin(path)");
        this.colorExprB = addExprBox(this.leftPanelX + 8, 0, this.inputWidth, "0.75 + 0.25*cos(sweep)");

        this.closeButton = this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
            .bounds(this.leftPanelX + 8, 0, this.inputWidth, 20)
            .build());

        applyRememberedPanelState();
        loadFromSynthIfAvailable();

        syncPanelButtons();
        layoutLeftPanel();

        setInitialFocus(this.xExpr);
        recomputePreview();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showShapeModal) {
            int mx = (this.width - SHAPE_MODAL_W) / 2;
            int my = (this.height - SHAPE_MODAL_H) / 2;
            int visible = (SHAPE_MODAL_H - 32) / SHAPE_ITEM_H;
            int start = Math.max(0, Math.min(shapeScroll, Math.max(0, SHAPES.length - visible)));
            int end = Math.min(SHAPES.length, start + visible);
            for (int i = start; i < end; i++) {
                int iy = my + 32 + (i - start) * SHAPE_ITEM_H;
                if (mouseX >= mx + 12 && mouseX <= mx + SHAPE_MODAL_W - 12 && mouseY >= iy && mouseY <= iy + SHAPE_ITEM_H) {
                    ShapePreset s = SHAPES[i];
                    this.xExpr.setValue(s.x);
                    this.yExpr.setValue(s.y);
                    this.zExpr.setValue(s.z);
                    this.tMin.setValue(s.pathMin);
                    this.tMax.setValue(s.pathMax);
                    this.uMin.setValue(s.sweepMin);
                    this.uMax.setValue(s.sweepMax);
                    showShapeModal = false;
                    return true;
                }
            }
            // Click outside closes modal
            if (mouseX < mx || mouseX > mx + SHAPE_MODAL_W || mouseY < my || mouseY > my + SHAPE_MODAL_H) {
                showShapeModal = false;
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (showShapeModal) {
            int visible = (SHAPE_MODAL_H - 32) / SHAPE_ITEM_H;
            int maxScroll = Math.max(0, SHAPES.length - visible);
            if (amount < 0.0) {
                shapeScroll = Math.min(maxScroll, shapeScroll + 1);
            } else if (amount > 0.0) {
                shapeScroll = Math.max(0, shapeScroll - 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        if (showShapeModal) {
            int mx = (this.width - SHAPE_MODAL_W) / 2;
            int my = (this.height - SHAPE_MODAL_H) / 2;
            graphics.fill(mx, my, mx + SHAPE_MODAL_W, my + SHAPE_MODAL_H, 0xEE22262C);
            graphics.drawString(this.font, "Select Shape Preset", mx + 16, my + 12, 0xFFFFFF, false);
            int visible = (SHAPE_MODAL_H - 32) / SHAPE_ITEM_H;
            int start = Math.max(0, Math.min(shapeScroll, Math.max(0, SHAPES.length - visible)));
            int end = Math.min(SHAPES.length, start + visible);
            for (int i = start; i < end; i++) {
                int iy = my + 32 + (i - start) * SHAPE_ITEM_H;
                int color = 0xFFCCCCCC;
                if (mouseX >= mx + 12 && mouseX <= mx + SHAPE_MODAL_W - 12 && mouseY >= iy && mouseY <= iy + SHAPE_ITEM_H) {
                    color = 0xFF99B4FF;
                }
                graphics.fill(mx + 12, iy, mx + SHAPE_MODAL_W - 12, iy + SHAPE_ITEM_H, color);
                graphics.drawString(this.font, SHAPES[i].name, mx + 20, iy + 5, 0x222244, false);
            }
            if (SHAPES.length > visible) {
                graphics.drawString(this.font, "Scroll wheel to browse", mx + 16, my + SHAPE_MODAL_H - 14, 0xAFC2D8, false);
            }
            return;
        }

        int leftX = this.leftPanelX;
        int leftY = this.leftPanelTop;
        int leftW = this.leftPanelWidth;
        int leftH = PANEL_HEIGHT;

        graphics.fill(leftX, leftY, leftX + leftW, leftY + leftH, 0xCC111418);
        graphics.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0xCC0D1118);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.title, leftX + 8, leftY - 10, 0xFFFFFF, false);
        if (!formulaPanelCollapsed) {
            graphics.drawString(this.font, "X Equation", this.xExpr.getX(), this.xExpr.getY() - LABEL_OFFSET_Y, 0xC8D7FF, false);
            graphics.drawString(this.font, "Y Equation", this.yExpr.getX(), this.yExpr.getY() - LABEL_OFFSET_Y, 0xC8D7FF, false);
            graphics.drawString(this.font, "Z Equation", this.zExpr.getX(), this.zExpr.getY() - LABEL_OFFSET_Y, 0xC8D7FF, false);

            int rangesY = this.tMin.getY() - LABEL_OFFSET_Y;
            graphics.drawString(this.font, "Path Min", this.tMin.getX(), rangesY, 0x9FB2C6, false);
            graphics.drawString(this.font, "Path Max", this.tMax.getX(), rangesY, 0x9FB2C6, false);
            graphics.drawString(this.font, "Sweep Min", this.uMin.getX(), rangesY, 0x9FB2C6, false);
            graphics.drawString(this.font, "Sweep Max", this.uMax.getX(), rangesY, 0x9FB2C6, false);
            graphics.drawString(this.font, "Point Count", this.pointCount.getX(), this.pointCount.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
        }

        if (!colorPanelCollapsed) {
            graphics.drawString(this.font, "Color A", this.colorA_R.getX(), this.colorA_R.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
            graphics.drawString(this.font, "Color B", this.colorB_R.getX(), this.colorB_R.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
            graphics.drawString(this.font, "Color R Expr", this.colorExprR.getX(), this.colorExprR.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
            graphics.drawString(this.font, "Color G Expr", this.colorExprG.getX(), this.colorExprG.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
            graphics.drawString(this.font, "Color B Expr", this.colorExprB.getX(), this.colorExprB.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
        }

        if (!animationPanelCollapsed) {
            graphics.drawString(this.font, "Animation Preset", this.animationPresetButton.getX(), this.animationPresetButton.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
            graphics.drawString(this.font, "Animation Speed", this.animationSpeedSlider.getX(), this.animationSpeedSlider.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
            graphics.drawString(this.font, "Render Density", this.densitySlider.getX(), this.densitySlider.getY() - LABEL_OFFSET_Y, 0x9FB2C6, false);
        }

        graphics.drawString(this.font, "Preview", previewX + 8, previewY + 8, 0xDDE7FF, false);

        double animTime = (this.minecraft != null && this.minecraft.level != null)
            ? this.minecraft.level.getGameTime() + partialTick
            : (System.currentTimeMillis() * 0.02);

        final double yaw = Math.toRadians(38.0);
        final double pitch = Math.toRadians(26.0);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double pad = 22.0;
        double halfAvailW = Math.max(1.0, (previewW - 2.0 * pad) * 0.5);
        double halfAvailH = Math.max(1.0, (previewH - 2.0 * pad) * 0.5);
        double projectionScale = Math.min(halfAvailW, halfAvailH) / Math.max(1.0e-6, PREVIEW_CLOUD_RADIUS);
        double screenCenterX = previewX + (previewW * 0.5);
        double screenCenterY = previewY + (previewH * 0.5);

        for (int i = 0; i < previewDots.size(); i++) {
            Dot dot = previewDots.get(i);
            Vec3 animated = applyPreviewAnimation(dot.x, dot.y, dot.z, animationPreset, animTime, this.animationSpeedSlider.getSpeed());
            double x1 = animated.x * cy + animated.z * sy;
            double z1 = -animated.x * sy + animated.z * cy;
            double y1 = animated.y * cp - z1 * sp;

            float shimmer = (float) (0.65 + (0.35 * Math.sin((animTime * 0.20) + (i * 0.83))));
            float twinkle = (float) (0.55 + (0.45 * Math.sin((animTime * 0.31) + (i * 1.39))));

            int dx = (int) Math.round(Math.sin((animTime * 0.12) + (i * 0.27)));
            int dy = (int) Math.round(Math.cos((animTime * 0.11) + (i * 0.29)));

            int x = (int) Math.round(screenCenterX + (x1 * projectionScale)) + dx;
            int y = (int) Math.round(screenCenterY - (y1 * projectionScale)) + dy;
            if (x >= previewX + 2 && x < previewX + previewW - 2 && y >= previewY + 2 && y < previewY + previewH - 2) {
                int glow = withAlpha(dot.color, 0.16f * (0.75f + (0.25f * shimmer)));
                int mid = withAlpha(dot.color, 0.42f * shimmer);
                int core = withAlpha(dot.color, 0.80f * twinkle);

                graphics.fill(x - 2, y - 2, x + 3, y + 3, glow);
                graphics.fill(x - 1, y - 1, x + 2, y + 2, mid);
                graphics.fill(x, y, x + PREVIEW_DOT_SIZE, y + PREVIEW_DOT_SIZE, core);
            }
        }

        graphics.drawString(this.font, status, leftX + 8, this.closeButton.getY() - 12, statusColor, false);
        graphics.drawString(this.font, "Write stores a reusable equation iota in the inserted focus.", previewX + 8, previewY + previewH - 12, 0xA7B7CD, false);
    }

    private EditBox addExprBox(int x, int y, int w, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.empty());
        box.setValue(value);
        box.setMaxLength(MAX_EXPR_CHARS);
        box.setResponder(s -> recomputePreview());
        addRenderableWidget(box);
        return box;
    }

    private EditBox addNumBox(int x, int y, int w, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.empty());
        box.setValue(value);
        box.setMaxLength(32);
        box.setResponder(s -> recomputePreview());
        addRenderableWidget(box);
        return box;
    }

    private void syncUseUButton() {
        useUButton.setMessage(Component.literal(useU ? "Sweep Param: On" : "Sweep Param: Off"));
    }

    private void syncColorModeButton() {
        colorModeButton.setMessage(Component.literal("Color: " + colorMode));
    }

    private void syncAnimationPresetButton() {
        animationPresetButton.setMessage(Component.literal("Animation: " + animationPreset));
    }

    private void cycleAnimationPreset() {
        int index = 0;
        for (int i = 0; i < ANIMATION_PRESETS.length; i++) {
            if (ANIMATION_PRESETS[i].equals(animationPreset)) {
                index = i;
                break;
            }
        }
        animationPreset = ANIMATION_PRESETS[(index + 1) % ANIMATION_PRESETS.length];
    }

    private void syncPanelButtons() {
        formulaPanelButton.setMessage(Component.literal(formulaPanelCollapsed ? "[+] Shape" : "[-] Shape"));
        colorPanelButton.setMessage(Component.literal(colorPanelCollapsed ? "[+] Color" : "[-] Color"));
        animationPanelButton.setMessage(Component.literal(animationPanelCollapsed ? "[+] Animation" : "[-] Animation"));
    }

    private void layoutLeftPanel() {
        int y = this.leftPanelTop + 12;

        formulaPanelButton.setY(y);
        y += 26;

        boolean formulaVisible = !formulaPanelCollapsed;
        setWidgetVisible(this.shapePresetButton, formulaVisible);
        setWidgetVisible(this.xExpr, formulaVisible);
        setWidgetVisible(this.yExpr, formulaVisible);
        setWidgetVisible(this.zExpr, formulaVisible);
        setWidgetVisible(this.tMin, formulaVisible);
        setWidgetVisible(this.tMax, formulaVisible);
        setWidgetVisible(this.uMin, formulaVisible);
        setWidgetVisible(this.uMax, formulaVisible);
        setWidgetVisible(this.pointCount, formulaVisible);
        setWidgetVisible(this.useUButton, formulaVisible);
        setWidgetVisible(this.colorModeButton, formulaVisible);
        setWidgetVisible(this.writeButton, true);

        if (formulaVisible) {
            y += PANEL_CONTENT_TOP_PADDING;
            this.shapePresetButton.setY(y);
            y += 26;

            this.xExpr.setY(y);
            y += 36;
            this.yExpr.setY(y);
            y += 36;
            this.zExpr.setY(y);

            y += 36;
            this.tMin.setY(y);
            this.tMax.setY(y);
            this.uMin.setY(y);
            this.uMax.setY(y);

            y += 36;
            this.pointCount.setY(y);

            y += 36;
            this.useUButton.setY(y);
            this.colorModeButton.setY(y);

            y += 32;
        }

        this.colorPanelButton.setY(y);
        y += 32;

        boolean colorVisible = !colorPanelCollapsed;
        setWidgetVisible(this.colorA_R, colorVisible);
        setWidgetVisible(this.colorA_G, colorVisible);
        setWidgetVisible(this.colorA_B, colorVisible);
        setWidgetVisible(this.colorB_R, colorVisible);
        setWidgetVisible(this.colorB_G, colorVisible);
        setWidgetVisible(this.colorB_B, colorVisible);
        setWidgetVisible(this.colorExprR, colorVisible);
        setWidgetVisible(this.colorExprG, colorVisible);
        setWidgetVisible(this.colorExprB, colorVisible);

        if (colorVisible) {
            y += PANEL_CONTENT_TOP_PADDING;
            this.colorA_R.setY(y);
            this.colorA_G.setY(y);
            this.colorA_B.setY(y);
            this.colorB_R.setY(y);
            this.colorB_G.setY(y);
            this.colorB_B.setY(y);

            y += 32;
            this.colorExprR.setY(y);
            y += 36;
            this.colorExprG.setY(y);
            y += 36;
            this.colorExprB.setY(y);

            y += 32;
        }

        this.animationPanelButton.setY(y);
        y += 32;

        boolean animationVisible = !animationPanelCollapsed;
        setWidgetVisible(this.animationPresetButton, animationVisible);
        setWidgetVisible(this.animationSpeedSlider, animationVisible);
        setWidgetVisible(this.densitySlider, animationVisible);
        if (animationVisible) {
            y += PANEL_CONTENT_TOP_PADDING;
            this.animationPresetButton.setY(y);
            y += 36;
            this.animationSpeedSlider.setY(y);
            y += 36;
            this.densitySlider.setY(y);
            y += 36;
        }

        int actionButtonW = (this.inputWidth - ACTION_BUTTON_GAP) / 2;
        int actionY = this.leftPanelTop + PANEL_HEIGHT - 28;
        int actionX = this.leftPanelX + 8;

        this.writeButton.setX(actionX);
        this.writeButton.setY(actionY);
        this.writeButton.setWidth(actionButtonW);

        this.closeButton.setX(actionX + actionButtonW + ACTION_BUTTON_GAP);
        this.closeButton.setY(actionY);
        this.closeButton.setWidth(actionButtonW);
    }

    private void loadFromSynthIfAvailable() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }

        var be = this.minecraft.level.getBlockEntity(this.blockPos);
        if (!(be instanceof EquationSynthBlockEntity synth)) {
            return;
        }

        if (!synth.hasFocus()) {
            lastOpenPanel = PanelState.SHAPE;
            formulaPanelCollapsed = false;
            colorPanelCollapsed = true;
            animationPanelCollapsed = true;
            return;
        }

        EquationParticleConfig cfg = synth.getPreviewEquation();
        if (cfg != null) {
            applyConfig(cfg);
        }

        this.animationPreset = synth.getAnimationPreset();
        syncAnimationPresetButton();
        this.animationSpeedSlider.setSpeed(synth.getAnimationSpeed());
        this.densitySlider.setDensity(synth.getRenderDensity());
    }

    private void applyRememberedPanelState() {
        switch (lastOpenPanel) {
            case COLOR -> {
                formulaPanelCollapsed = true;
                colorPanelCollapsed = false;
                animationPanelCollapsed = true;
            }
            case ANIMATION -> {
                formulaPanelCollapsed = true;
                colorPanelCollapsed = true;
                animationPanelCollapsed = false;
            }
            default -> {
                formulaPanelCollapsed = false;
                colorPanelCollapsed = true;
                animationPanelCollapsed = true;
            }
        }
    }

    private void applyConfig(EquationParticleConfig cfg) {
        this.xExpr.setValue(cfg.xExpr());
        this.yExpr.setValue(cfg.yExpr());
        this.zExpr.setValue(cfg.zExpr());
        this.tMin.setValue(Double.toString(cfg.tMin()));
        this.tMax.setValue(Double.toString(cfg.tMax()));
        this.uMin.setValue(Double.toString(cfg.uMin()));
        this.uMax.setValue(Double.toString(cfg.uMax()));
        this.pointCount.setValue(Integer.toString(cfg.pointCount()));

        this.useU = cfg.useU();
        this.colorMode = cfg.colorMode();
        syncUseUButton();
        syncColorModeButton();

        this.colorA_R.setValue(Double.toString(cfg.gradientStartR()));
        this.colorA_G.setValue(Double.toString(cfg.gradientStartG()));
        this.colorA_B.setValue(Double.toString(cfg.gradientStartB()));
        this.colorB_R.setValue(Double.toString(cfg.gradientEndR()));
        this.colorB_G.setValue(Double.toString(cfg.gradientEndG()));
        this.colorB_B.setValue(Double.toString(cfg.gradientEndB()));
        this.colorExprR.setValue(cfg.colorExprR());
        this.colorExprG.setValue(cfg.colorExprG());
        this.colorExprB.setValue(cfg.colorExprB());
    }

    private static void setWidgetVisible(AbstractWidget widget, boolean visible) {
        widget.visible = visible;
        widget.active = visible;
    }

    private void writeToFocus() {
        EquationParticleConfig config;
        try {
            config = readConfig(true).normalized();
            EquationEvaluator.compile(config.xExpr());
            EquationEvaluator.compile(config.yExpr());
            EquationEvaluator.compile(config.zExpr());
            if ("expression".equals(config.colorMode())) {
                EquationEvaluator.compile(config.colorExprR());
                EquationEvaluator.compile(config.colorExprG());
                EquationEvaluator.compile(config.colorExprB());
            }
        } catch (IllegalArgumentException ex) {
            status = "Invalid equation config: " + friendlyError(ex.getMessage());
            statusColor = 0xFF6666;
            return;
        }

        var buf = PacketByteBufs.create();
        buf.writeBlockPos(blockPos);
        buf.writeNbt(config.serializeToNbt());
        buf.writeUtf(animationPreset, 32);
        buf.writeDouble(this.animationSpeedSlider.getSpeed());
        buf.writeDouble(this.densitySlider.getDensity());
        ClientPlayNetworking.send(ManifestationNetworking.WRITE_EQUATION_PARTICLE_C2S, buf);

        status = "Sent to synthesizer for write.";
        statusColor = 0x80D080;
    }

    private void recomputePreview() {
        EquationParticleConfig config;
        try {
            config = readConfig(false).normalized();
            previewDots.clear();
            sampleIntoPreview(config);

            status = "Preview ready. " + previewDots.size() + " points.";
            statusColor = 0x9CC7FF;
        } catch (IllegalArgumentException ex) {
            previewDots.clear();
            status = "Preview failed: " + friendlyError(ex.getMessage());
            statusColor = 0xFF6666;
        }
    }

    private EquationParticleConfig readConfig(boolean strict) {
        String x = this.xExpr.getValue();
        String y = this.yExpr.getValue();
        String z = this.zExpr.getValue();

        double vTMin = parseDouble(this.tMin.getValue(), 0.0);
        double vTMax = parseDouble(this.tMax.getValue(), Math.PI * 2.0);
        double vUMin = parseDouble(this.uMin.getValue(), 0.0);
        double vUMax = parseDouble(this.uMax.getValue(), Math.PI * 2.0);
        int vCount = parseInt(this.pointCount.getValue(), 2000);
        double vFixedR = parseDouble(this.colorA_R.getValue(), 1.0);
        double vFixedG = parseDouble(this.colorA_G.getValue(), 1.0);
        double vFixedB = parseDouble(this.colorA_B.getValue(), 1.0);
        double vGradStartR = parseDouble(this.colorA_R.getValue(), 0.96);
        double vGradStartG = parseDouble(this.colorA_G.getValue(), 0.56);
        double vGradStartB = parseDouble(this.colorA_B.getValue(), 0.64);
        double vGradEndR = parseDouble(this.colorB_R.getValue(), 0.88);
        double vGradEndG = parseDouble(this.colorB_G.getValue(), 0.78);
        double vGradEndB = parseDouble(this.colorB_B.getValue(), 0.96);
        String vExprR = this.colorExprR.getValue();
        String vExprG = this.colorExprG.getValue();
        String vExprB = this.colorExprB.getValue();

        EquationParticleConfig cfg = new EquationParticleConfig(
            x,
            y,
            z,
            vTMin,
            vTMax,
            vUMin,
            vUMax,
            useU,
            vCount,
            colorMode,
            vFixedR,
            vFixedG,
            vFixedB,
            vGradStartR,
            vGradStartG,
            vGradStartB,
            vGradEndR,
            vGradEndG,
            vGradEndB,
            vExprR,
            vExprG,
            vExprB
        );

        if (strict) {
            cfg.validateStrict();
        }
        return cfg;
    }

    private void sampleIntoPreview(EquationParticleConfig config) {
        List<EquationParticleGenerator.GeneratedPoint> generated = EquationParticleGenerator.generate(
            config,
            ManifestationClientLimits.MAX_EQUATION_POINTS_RENDER,
            ManifestationClientLimits.MAX_EQUATION_EVAL_BUDGET_RENDER
        );
        if (generated.isEmpty()) {
            throw new IllegalArgumentException("no_valid_points");
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < generated.size(); i++) {
            Vec3 p = generated.get(i).offset();
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }

        double spanX = Math.max(1.0e-6, maxX - minX);
        double spanY = Math.max(1.0e-6, maxY - minY);
        double spanZ = Math.max(1.0e-6, maxZ - minZ);
        double spanMax = Math.max(spanX, Math.max(spanY, spanZ));
        double scale = (PREVIEW_CLOUD_RADIUS * 2.0) / spanMax;

        double centerX = (minX + maxX) * 0.5;
        double centerY = (minY + maxY) * 0.5;
        double centerZ = (minZ + maxZ) * 0.5;

        for (EquationParticleGenerator.GeneratedPoint p : generated) {
            Vec3 offset = p.offset();
            double nx = (offset.x - centerX) * scale;
            double ny = (offset.y - centerY) * scale;
            double nz = (offset.z - centerZ) * scale;
            Vec3 c = p.color();
            int color = packColor(c.x, c.y, c.z);
            previewDots.add(new Dot(nx, ny, nz, color));
        }
    }

    private static Vec3 applyPreviewAnimation(double x, double y, double z, String preset, double animTime, double speed) {
        double scaledTime = animTime * Math.max(0.1, speed);
        return switch (preset) {
            case "static" -> new Vec3(x, y, z);
            case "bob" -> new Vec3(x, y + (Math.sin(scaledTime * 0.09) * 0.08), z);
            case "pulse" -> {
                double s = 1.0 + (0.18 * Math.sin(scaledTime * 0.12));
                yield new Vec3(x * s, y * s, z * s);
            }
            case "orbit" -> {
                double tx = x + (Math.cos(scaledTime * 0.05) * 0.08);
                double tz = z + (Math.sin(scaledTime * 0.05) * 0.08);
                yield rotateY(tx, y, tz, Math.toRadians(scaledTime * 1.4));
            }
            case "spin_bob" -> {
                Vec3 spun = rotateY(x, y, z, Math.toRadians(scaledTime * 1.9));
                yield new Vec3(spun.x, spun.y + (Math.sin(scaledTime * 0.09) * 0.08), spun.z);
            }
            default -> rotateY(x, y, z, Math.toRadians(scaledTime * 1.9));
        };
    }

    private static Vec3 rotateY(double x, double y, double z, double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Vec3((x * c) + (z * s), y, (-x * s) + (z * c));
    }

    private static int packColor(double r, double g, double b) {
        int rr = (int) (Math.max(0.0, Math.min(1.0, r)) * 255.0);
        int gg = (int) (Math.max(0.0, Math.min(1.0, g)) * 255.0);
        int bb = (int) (Math.max(0.0, Math.min(1.0, b)) * 255.0);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static int withAlpha(int argb, float alphaScale) {
        float clamped = Math.max(0.0f, Math.min(1.0f, alphaScale));
        int alpha = (int) (((argb >>> 24) & 0xFF) * clamped);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String friendlyError(String code) {
        if (code == null || code.isBlank()) {
            return "unknown error";
        }
        int idx = code.indexOf("_at_");
        return idx >= 0 ? code.substring(0, idx) : code;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Dot(double x, double y, double z, int color) {
    }
}
