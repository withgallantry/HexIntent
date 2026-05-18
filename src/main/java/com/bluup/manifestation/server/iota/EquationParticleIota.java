package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class EquationParticleIota extends Iota {
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
        String colorExprB
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
            "1"
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
                && colorExprB.equals(other.colorExprB);
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        out.putString("x", xExpr);
        out.putString("y", yExpr);
        out.putString("z", zExpr);
        out.putDouble("t_min", tMin);
        out.putDouble("t_max", tMax);
        out.putDouble("u_min", uMin);
        out.putDouble("u_max", uMax);
        out.putBoolean("use_u", useU);
        out.putInt("points", pointCount);
        out.putString("color_mode", colorMode);
        out.putDouble("fixed_r", fixedR);
        out.putDouble("fixed_g", fixedG);
        out.putDouble("fixed_b", fixedB);
        out.putDouble("grad_start_r", gradientStartR);
        out.putDouble("grad_start_g", gradientStartG);
        out.putDouble("grad_start_b", gradientStartB);
        out.putDouble("grad_end_r", gradientEndR);
        out.putDouble("grad_end_g", gradientEndG);
        out.putDouble("grad_end_b", gradientEndB);
        out.putString("color_expr_r", colorExprR);
        out.putString("color_expr_g", colorExprG);
        out.putString("color_expr_b", colorExprB);
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
            colorExprB
        );
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
