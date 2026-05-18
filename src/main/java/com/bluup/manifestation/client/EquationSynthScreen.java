package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.common.equation.EquationEvaluator;
import com.bluup.manifestation.common.equation.EquationParticleConfig;
import com.bluup.manifestation.common.equation.EquationParticleGenerator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class EquationSynthScreen extends Screen {
    private static final int MAX_EXPR_CHARS = EquationParticleConfig.MAX_EXPR_CHARS;

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

    public EquationSynthScreen(BlockPos blockPos) {
        super(Component.literal("Equation Synthesizer"));
        this.blockPos = blockPos;
    }

    @Override
    protected void init() {
        int leftW = 318;
        int gap = 10;
        int panelX = Math.max(12, (this.width - (leftW + gap + 460)) / 2);
        int leftX = panelX;
        int rightX = leftX + leftW + gap;
        int top = Math.max(18, (this.height - 330) / 2);

        this.previewX = rightX;
        this.previewY = top;
        this.previewW = Math.min(460, this.width - rightX - 12);
        this.previewH = 330;

        int inputW = leftW - 16;
        int y = top + 12;

        this.xExpr = addExprBox(leftX + 8, y, inputW, "(2+cos(u))*cos(t)");
        y += 24;
        this.yExpr = addExprBox(leftX + 8, y, inputW, "(2+cos(u))*sin(t)");
        y += 24;
        this.zExpr = addExprBox(leftX + 8, y, inputW, "sin(u)");

        y += 28;
        this.tMin = addNumBox(leftX + 8, y, 66, "0");
        this.tMax = addNumBox(leftX + 80, y, 66, "6.283185307");
        this.uMin = addNumBox(leftX + 154, y, 66, "0");
        this.uMax = addNumBox(leftX + 228, y, 66, "6.283185307");

        y += 26;
        this.pointCount = addNumBox(leftX + 8, y, inputW, "2000");

        y += 28;
        this.useUButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            useU = !useU;
            syncUseUButton();
        }).bounds(leftX + 8, y, 100, 20).build());
        syncUseUButton();

        this.colorModeButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
            colorMode = switch (colorMode) {
                case "single" -> "gradient";
                case "gradient" -> "expression";
                default -> "single";
            };
            syncColorModeButton();
        }).bounds(leftX + 114, y, 110, 20).build());
        syncColorModeButton();

        this.addRenderableWidget(Button.builder(Component.literal("Preview"), b -> recomputePreview())
            .bounds(leftX + 228, y, 42, 20)
            .build());
        this.addRenderableWidget(Button.builder(Component.literal("Write"), b -> writeToFocus())
            .bounds(leftX + 272, y, 38, 20)
            .build());

        y += 24;
        this.colorA_R = addNumBox(leftX + 8, y, 42, "0.96");
        this.colorA_G = addNumBox(leftX + 52, y, 42, "0.56");
        this.colorA_B = addNumBox(leftX + 96, y, 42, "0.64");
        this.colorB_R = addNumBox(leftX + 148, y, 42, "0.88");
        this.colorB_G = addNumBox(leftX + 192, y, 42, "0.78");
        this.colorB_B = addNumBox(leftX + 236, y, 42, "0.96");

        y += 24;
        this.colorExprR = addExprBox(leftX + 8, y, inputW, "0.85");
        y += 24;
        this.colorExprG = addExprBox(leftX + 8, y, inputW, "0.55 + 0.4*sin(t)");
        y += 24;
        this.colorExprB = addExprBox(leftX + 8, y, inputW, "0.75 + 0.25*cos(u)");

        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
            .bounds(leftX + 8, y, 302, 20)
            .build());

        setInitialFocus(this.xExpr);
        recomputePreview();
    }

    private EditBox addExprBox(int x, int y, int w, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.empty());
        box.setValue(value);
        box.setMaxLength(MAX_EXPR_CHARS);
        addRenderableWidget(box);
        return box;
    }

    private EditBox addNumBox(int x, int y, int w, String value) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.empty());
        box.setValue(value);
        box.setMaxLength(32);
        addRenderableWidget(box);
        return box;
    }

    private void syncUseUButton() {
        useUButton.setMessage(Component.literal(useU ? "U Param: On" : "U Param: Off"));
    }

    private void syncColorModeButton() {
        colorModeButton.setMessage(Component.literal("Color: " + colorMode));
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
        buf.writeUtf(config.xExpr(), MAX_EXPR_CHARS);
        buf.writeUtf(config.yExpr(), MAX_EXPR_CHARS);
        buf.writeUtf(config.zExpr(), MAX_EXPR_CHARS);
        buf.writeDouble(config.tMin());
        buf.writeDouble(config.tMax());
        buf.writeDouble(config.uMin());
        buf.writeDouble(config.uMax());
        buf.writeBoolean(config.useU());
        buf.writeVarInt(config.pointCount());
        buf.writeUtf(config.colorMode(), EquationParticleConfig.MAX_COLOR_MODE_CHARS);
        buf.writeDouble(config.fixedR());
        buf.writeDouble(config.fixedG());
        buf.writeDouble(config.fixedB());
        buf.writeDouble(config.gradientStartR());
        buf.writeDouble(config.gradientStartG());
        buf.writeDouble(config.gradientStartB());
        buf.writeDouble(config.gradientEndR());
        buf.writeDouble(config.gradientEndG());
        buf.writeDouble(config.gradientEndB());
        buf.writeUtf(config.colorExprR(), MAX_EXPR_CHARS);
        buf.writeUtf(config.colorExprG(), MAX_EXPR_CHARS);
        buf.writeUtf(config.colorExprB(), MAX_EXPR_CHARS);
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

        final double yaw = Math.toRadians(38.0);
        final double pitch = Math.toRadians(26.0);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);

        ArrayList<Projected> projected = new ArrayList<>(generated.size());
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < generated.size(); i++) {
            Vec3 p = generated.get(i).offset();
            double x1 = p.x * cy + p.z * sy;
            double z1 = -p.x * sy + p.z * cy;
            double y1 = p.y * cp - z1 * sp;

            minX = Math.min(minX, x1);
            maxX = Math.max(maxX, x1);
            minY = Math.min(minY, y1);
            maxY = Math.max(maxY, y1);
            projected.add(new Projected(x1, y1, i));
        }

        double spanX = Math.max(1.0e-6, maxX - minX);
        double spanY = Math.max(1.0e-6, maxY - minY);
        double pad = 14.0;
        double scaleX = (previewW - 2.0 * pad) / spanX;
        double scaleY = (previewH - 2.0 * pad) / spanY;
        double scale = Math.max(1.0, Math.min(scaleX, scaleY));

        for (Projected p : projected) {
            int sx = (int) Math.round(previewX + pad + (p.x - minX) * scale);
            int syScreen = (int) Math.round(previewY + previewH - pad - (p.y - minY) * scale);
            Vec3 c = generated.get(p.index).color();
            int color = packColor(c.x, c.y, c.z);
            previewDots.add(new Dot(sx, syScreen, color));
        }
    }

    private static int packColor(double r, double g, double b) {
        int rr = (int) (Math.max(0.0, Math.min(1.0, r)) * 255.0);
        int gg = (int) (Math.max(0.0, Math.min(1.0, g)) * 255.0);
        int bb = (int) (Math.max(0.0, Math.min(1.0, b)) * 255.0);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int leftX = this.xExpr.getX() - 8;
        int leftY = this.xExpr.getY() - 12;
        int leftW = 318;
        int leftH = 330;

        graphics.fill(leftX, leftY, leftX + leftW, leftY + leftH, 0xCC111418);
        graphics.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0xCC0D1118);

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.title, leftX + 8, leftY - 10, 0xFFFFFF, false);

        graphics.drawString(this.font, "X Equation", this.xExpr.getX(), this.xExpr.getY() - 10, 0xC8D7FF, false);
        graphics.drawString(this.font, "Y Equation", this.yExpr.getX(), this.yExpr.getY() - 10, 0xC8D7FF, false);
        graphics.drawString(this.font, "Z Equation", this.zExpr.getX(), this.zExpr.getY() - 10, 0xC8D7FF, false);

        int rangesY = this.tMin.getY() - 10;
        graphics.drawString(this.font, "T Min", this.tMin.getX(), rangesY, 0x9FB2C6, false);
        graphics.drawString(this.font, "T Max", this.tMax.getX(), rangesY, 0x9FB2C6, false);
        graphics.drawString(this.font, "U Min", this.uMin.getX(), rangesY, 0x9FB2C6, false);
        graphics.drawString(this.font, "U Max", this.uMax.getX(), rangesY, 0x9FB2C6, false);
        graphics.drawString(this.font, "Point Count", this.pointCount.getX(), this.pointCount.getY() - 10, 0x9FB2C6, false);
        graphics.drawString(this.font, "Color A (single / gradient start)", this.colorA_R.getX(), this.colorA_R.getY() - 10, 0x9FB2C6, false);
        graphics.drawString(this.font, "Color B (gradient end)", this.colorB_R.getX(), this.colorB_R.getY() - 10, 0x9FB2C6, false);
        graphics.drawString(this.font, "Color Expr R", this.colorExprR.getX(), this.colorExprR.getY() - 10, 0x9FB2C6, false);
        graphics.drawString(this.font, "Color Expr G", this.colorExprG.getX(), this.colorExprG.getY() - 10, 0x9FB2C6, false);
        graphics.drawString(this.font, "Color Expr B", this.colorExprB.getX(), this.colorExprB.getY() - 10, 0x9FB2C6, false);

        graphics.drawString(this.font, "Preview", previewX + 8, previewY + 8, 0xDDE7FF, false);

        for (Dot dot : previewDots) {
            if (dot.x >= previewX + 1 && dot.x < previewX + previewW - 1 && dot.y >= previewY + 1 && dot.y < previewY + previewH - 1) {
                graphics.fill(dot.x, dot.y, dot.x + 2, dot.y + 2, dot.color);
            }
        }

        graphics.drawString(this.font, status, leftX + 8, leftY + leftH - 12, statusColor, false);
        graphics.drawString(this.font, "Write stores a reusable equation iota in the inserted focus.", previewX + 8, previewY + previewH - 12, 0xA7B7CD, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Dot(int x, int y, int color) {
    }

    private record Projected(double x, double y, int index) {
    }
}
