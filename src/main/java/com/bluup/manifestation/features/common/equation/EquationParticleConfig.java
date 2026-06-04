package com.bluup.manifestation.common.equation;

public record EquationParticleConfig(
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
    public static final int MAX_EXPR_CHARS = 256;
    public static final int MIN_POINTS = 1;
    public static final int MAX_POINTS = 10000;
    public static final int MAX_COLOR_MODE_CHARS = 32;

    public EquationParticleConfig(
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
            "0.85",
            "0.55 + 0.4*sin(t)",
            "0.75 + 0.25*cos(u)"
        );
    }

    public EquationParticleConfig normalized() {
        String nx = normalizeExpr(xExpr, "t");
        String ny = normalizeExpr(yExpr, "u");
        String nz = normalizeExpr(zExpr, "0");

        double ntMin = finiteOr(tMin, 0.0);
        double ntMax = finiteOr(tMax, Math.PI * 2.0);
        double nuMin = finiteOr(uMin, 0.0);
        double nuMax = finiteOr(uMax, Math.PI * 2.0);

        int nPoints = Math.max(MIN_POINTS, Math.min(pointCount, MAX_POINTS));
        String nMode = normalizeMode(colorMode);

        return new EquationParticleConfig(
            nx,
            ny,
            nz,
            ntMin,
            ntMax,
            nuMin,
            nuMax,
            useU,
            nPoints,
            nMode,
            clamp01(fixedR),
            clamp01(fixedG),
            clamp01(fixedB),
            clamp01(gradientStartR),
            clamp01(gradientStartG),
            clamp01(gradientStartB),
            clamp01(gradientEndR),
            clamp01(gradientEndG),
            clamp01(gradientEndB),
            normalizeExpr(colorExprR, "1"),
            normalizeExpr(colorExprG, "1"),
            normalizeExpr(colorExprB, "1")
        );
    }

    public void validateStrict() {
        if (xExpr == null || yExpr == null || zExpr == null) {
            throw new IllegalArgumentException("missing_equation");
        }
        if (xExpr.length() > MAX_EXPR_CHARS || yExpr.length() > MAX_EXPR_CHARS || zExpr.length() > MAX_EXPR_CHARS) {
            throw new IllegalArgumentException("equation_too_long");
        }
        if (!Double.isFinite(tMin) || !Double.isFinite(tMax) || !Double.isFinite(uMin) || !Double.isFinite(uMax)) {
            throw new IllegalArgumentException("invalid_range");
        }
        if (pointCount < MIN_POINTS || pointCount > MAX_POINTS) {
            throw new IllegalArgumentException("invalid_point_count");
        }

        String mode = normalizeMode(colorMode);
        if (mode.length() > MAX_COLOR_MODE_CHARS) {
            throw new IllegalArgumentException("invalid_color_mode");
        }
        if ("expression".equals(mode)) {
            if (colorExprR == null || colorExprG == null || colorExprB == null) {
                throw new IllegalArgumentException("missing_color_expression");
            }
            if (colorExprR.length() > MAX_EXPR_CHARS || colorExprG.length() > MAX_EXPR_CHARS || colorExprB.length() > MAX_EXPR_CHARS) {
                throw new IllegalArgumentException("color_expression_too_long");
            }
        }
    }

    private static String normalizeExpr(String in, String fallback) {
        if (in == null) {
            return fallback;
        }
        String out = in.trim();
        if (out.isEmpty()) {
            return fallback;
        }
        if (out.length() > MAX_EXPR_CHARS) {
            return out.substring(0, MAX_EXPR_CHARS);
        }
        return out;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String normalizeMode(String mode) {
        if (mode == null) {
            return "gradient";
        }
        String out = mode.trim().toLowerCase();
        return switch (out) {
            case "single", "gradient", "expression" -> out;
            default -> "gradient";
        };
    }
}
