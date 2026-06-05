package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.utils.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class EquationParticleIota extends Iota {
    private static final String ANIM_STATIC = "static";
    private static final String ANIM_ROTATE = "rotate";
    private static final String ANIM_BOB = "bob";
    private static final String ANIM_PULSE = "pulse";
    private static final String ANIM_ORBIT = "orbit";
    private static final String ANIM_SPIN_BOB = "spin_bob";
    private static final double DEFAULT_ANIMATION_SPEED = 1.0;
    private static final int DEFAULT_DURATION_TICKS = 100;

    private final String xExpr;
    private final String yExpr;
    private final String zExpr;
    private final double tMin;
    private final double tMax;
    private final double uMin;
    private final double uMax;
    private final boolean useU;
    private final int pointCount;
    private final String colorMode;
    private final double fixedR;
    private final double fixedG;
    private final double fixedB;
    private final double gradientStartR;
    private final double gradientStartG;
    private final double gradientStartB;
    private final double gradientEndR;
    private final double gradientEndG;
    private final double gradientEndB;
    private final String colorExprR;
    private final String colorExprG;
    private final String colorExprB;
    private final String animationPreset;
    private final double animationSpeed;
    private final int durationTicks;

    public EquationParticleIota(
        String xExpr,
        String yExpr,
        String zExpr,
        double tMin,
        double tMax,
        double uMin,
        double uMax,
        boolean useU,
        int pointCount,
        String colorMode,
        double fixedR,
        double fixedG,
        double fixedB,
        double gradientStartR,
        double gradientStartG,
        double gradientStartB,
        double gradientEndR,
        double gradientEndG,
        double gradientEndB,
        String colorExprR,
        String colorExprG,
        String colorExprB,
        String animationPreset,
        double animationSpeed,
        int durationTicks
    ) {
        super(ManifestationUiIotaTypes.EQUATION_PARTICLE, List.of(pointCount));
        this.xExpr = Objects.requireNonNull(xExpr, "xExpr");
        this.yExpr = Objects.requireNonNull(yExpr, "yExpr");
        this.zExpr = Objects.requireNonNull(zExpr, "zExpr");
        this.tMin = tMin;
        this.tMax = tMax;
        this.uMin = uMin;
        this.uMax = uMax;
        this.useU = useU;
        this.pointCount = Math.max(1, pointCount);
        this.colorMode = Objects.requireNonNull(colorMode, "colorMode");
        this.fixedR = fixedR;
        this.fixedG = fixedG;
        this.fixedB = fixedB;
        this.gradientStartR = gradientStartR;
        this.gradientStartG = gradientStartG;
        this.gradientStartB = gradientStartB;
        this.gradientEndR = gradientEndR;
        this.gradientEndG = gradientEndG;
        this.gradientEndB = gradientEndB;
        this.colorExprR = Objects.requireNonNull(colorExprR, "colorExprR");
        this.colorExprG = Objects.requireNonNull(colorExprG, "colorExprG");
        this.colorExprB = Objects.requireNonNull(colorExprB, "colorExprB");
        this.animationPreset = normalizeAnimationPreset(animationPreset);
        this.animationSpeed = normalizeAnimationSpeed(animationSpeed);
        this.durationTicks = normalizeDurationTicks(durationTicks);
    }

    public EquationParticleIota(
        String xExpr,
        String yExpr,
        String zExpr,
        double tMin,
        double tMax,
        double uMin,
        double uMax,
        boolean useU,
        int pointCount
    ) {
        this(
            xExpr,
            yExpr,
            zExpr,
            tMin,
            tMax,
            uMin,
            uMax,
            useU,
            pointCount,
            "gradient",
            1.0,
            1.0,
            1.0,
            0.96,
            0.56,
            0.64,
            0.88,
            0.78,
            0.96,
            "1",
            "1",
            "1",
            ANIM_ROTATE,
            DEFAULT_ANIMATION_SPEED,
            DEFAULT_DURATION_TICKS
        );
    }

    public String getXExpr() {
        return xExpr;
    }

    public String getYExpr() {
        return yExpr;
    }

    public String getZExpr() {
        return zExpr;
    }

    public double getTMin() {
        return tMin;
    }

    public double getTMax() {
        return tMax;
    }

    public double getUMin() {
        return uMin;
    }

    public double getUMax() {
        return uMax;
    }

    public boolean isUseU() {
        return useU;
    }

    public int getPointCount() {
        return pointCount;
    }

    public String getColorMode() {
        return colorMode;
    }

    public double getFixedR() {
        return fixedR;
    }

    public double getFixedG() {
        return fixedG;
    }

    public double getFixedB() {
        return fixedB;
    }

    public double getGradientStartR() {
        return gradientStartR;
    }

    public double getGradientStartG() {
        return gradientStartG;
    }

    public double getGradientStartB() {
        return gradientStartB;
    }

    public double getGradientEndR() {
        return gradientEndR;
    }

    public double getGradientEndG() {
        return gradientEndG;
    }

    public double getGradientEndB() {
        return gradientEndB;
    }

    public String getColorExprR() {
        return colorExprR;
    }

    public String getColorExprG() {
        return colorExprG;
    }

    public String getColorExprB() {
        return colorExprB;
    }

    public String getAnimationPreset() {
        return animationPreset;
    }

    public double getAnimationSpeed() {
        return animationSpeed;
    }

    public int getDurationTicks() {
        return durationTicks;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        if (!(that instanceof EquationParticleIota other)) {
            return false;
        }
        return xExpr.equals(other.xExpr)
            && yExpr.equals(other.yExpr)
            && zExpr.equals(other.zExpr)
            && Math.abs(tMin - other.tMin) <= 1.0e-9
            && Math.abs(tMax - other.tMax) <= 1.0e-9
            && Math.abs(uMin - other.uMin) <= 1.0e-9
            && Math.abs(uMax - other.uMax) <= 1.0e-9
            && useU == other.useU
                && pointCount == other.pointCount
                && colorMode.equals(other.colorMode)
                && Math.abs(fixedR - other.fixedR) <= 1.0e-9
                && Math.abs(fixedG - other.fixedG) <= 1.0e-9
                && Math.abs(fixedB - other.fixedB) <= 1.0e-9
                && Math.abs(gradientStartR - other.gradientStartR) <= 1.0e-9
                && Math.abs(gradientStartG - other.gradientStartG) <= 1.0e-9
                && Math.abs(gradientStartB - other.gradientStartB) <= 1.0e-9
                && Math.abs(gradientEndR - other.gradientEndR) <= 1.0e-9
                && Math.abs(gradientEndG - other.gradientEndG) <= 1.0e-9
                && Math.abs(gradientEndB - other.gradientEndB) <= 1.0e-9
                && colorExprR.equals(other.colorExprR)
                && colorExprG.equals(other.colorExprG)
                && colorExprB.equals(other.colorExprB)
                && animationPreset.equals(other.animationPreset)
                && Math.abs(animationSpeed - other.animationSpeed) <= 1.0e-9
                && durationTicks == other.durationTicks;
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        NBTHelper.putString(out, "x", xExpr);
        NBTHelper.putString(out, "y", yExpr);
        NBTHelper.putString(out, "z", zExpr);
        NBTHelper.putDouble(out, "t_min", tMin);
        NBTHelper.putDouble(out, "t_max", tMax);
        NBTHelper.putDouble(out, "u_min", uMin);
        NBTHelper.putDouble(out, "u_max", uMax);
        NBTHelper.putBoolean(out, "use_u", useU);
        NBTHelper.putInt(out, "points", pointCount);
        NBTHelper.putString(out, "color_mode", colorMode);
        NBTHelper.putDouble(out, "fixed_r", fixedR);
        NBTHelper.putDouble(out, "fixed_g", fixedG);
        NBTHelper.putDouble(out, "fixed_b", fixedB);
        NBTHelper.putDouble(out, "grad_start_r", gradientStartR);
        NBTHelper.putDouble(out, "grad_start_g", gradientStartG);
        NBTHelper.putDouble(out, "grad_start_b", gradientStartB);
        NBTHelper.putDouble(out, "grad_end_r", gradientEndR);
        NBTHelper.putDouble(out, "grad_end_g", gradientEndG);
        NBTHelper.putDouble(out, "grad_end_b", gradientEndB);
        NBTHelper.putString(out, "color_expr_r", colorExprR);
        NBTHelper.putString(out, "color_expr_g", colorExprG);
        NBTHelper.putString(out, "color_expr_b", colorExprB);
        NBTHelper.putString(out, "anim_preset", animationPreset);
        NBTHelper.putDouble(out, "anim_speed", animationSpeed);
        NBTHelper.putInt(out, "duration_ticks", durationTicks);
        return out;
    }

    public long fingerprint() {
        return Objects.hash(
            xExpr,
            yExpr,
            zExpr,
            tMin,
            tMax,
            uMin,
            uMax,
            useU,
            pointCount,
            colorMode,
            fixedR,
            fixedG,
            fixedB,
            gradientStartR,
            gradientStartG,
            gradientStartB,
            gradientEndR,
            gradientEndG,
            gradientEndB,
            colorExprR,
            colorExprG,
            colorExprB,
            animationPreset,
            animationSpeed,
            durationTicks
        );
    }

    private static String normalizeAnimationPreset(String raw) {
        if (raw == null) {
            return ANIM_ROTATE;
        }

        return switch (raw.toLowerCase()) {
            case ANIM_STATIC -> ANIM_STATIC;
            case ANIM_BOB -> ANIM_BOB;
            case ANIM_PULSE -> ANIM_PULSE;
            case ANIM_ORBIT -> ANIM_ORBIT;
            case ANIM_SPIN_BOB -> ANIM_SPIN_BOB;
            default -> ANIM_ROTATE;
        };
    }

    private static double normalizeAnimationSpeed(double raw) {
        if (!Double.isFinite(raw)) {
            return DEFAULT_ANIMATION_SPEED;
        }
        return Math.max(0.1, Math.min(4.0, raw));
    }

    private static int normalizeDurationTicks(int raw) {
        return Math.max(20, Math.min(20 * 60, raw));
    }

    @Override
    public @Nullable Iterable<Iota> subIotas() {
        return null;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public int depth() {
        return 1;
    }
}
