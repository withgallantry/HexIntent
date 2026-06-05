package com.bluup.manifestation.common.equation;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EquationParticleGenerator {
    private EquationParticleGenerator() {
    }

    public record GeneratedPoint(Vec3 offset, Vec3 color) {
    }

    public static List<GeneratedPoint> generate(
        EquationParticleConfig input,
        int maxPoints,
        int evalBudget
    ) {
        return generate(input, maxPoints, evalBudget, 0.0);
    }

    public static List<GeneratedPoint> generate(
        EquationParticleConfig input,
        int maxPoints,
        int evalBudget,
        double time
    ) {
        EquationParticleConfig config = input.normalized();
        int targetPoints = Math.max(1, Math.min(config.pointCount(), maxPoints));
        int budget = Math.max(6, evalBudget);

        EquationEvaluator.CompiledExpression xCompiled = EquationEvaluator.compile(config.xExpr());
        EquationEvaluator.CompiledExpression yCompiled = EquationEvaluator.compile(config.yExpr());
        EquationEvaluator.CompiledExpression zCompiled = EquationEvaluator.compile(config.zExpr());

        EquationEvaluator.CompiledExpression rCompiled = null;
        EquationEvaluator.CompiledExpression gCompiled = null;
        EquationEvaluator.CompiledExpression bCompiled = null;

        String mode = normalizeMode(config.colorMode());
        if ("expression".equals(mode)) {
            rCompiled = EquationEvaluator.compile(config.colorExprR());
            gCompiled = EquationEvaluator.compile(config.colorExprG());
            bCompiled = EquationEvaluator.compile(config.colorExprB());
        }

        final int evalsPerPoint = "expression".equals(mode) ? 6 : 3;
        ArrayList<GeneratedPoint> out = new ArrayList<>(targetPoints);

        if (config.useU()) {
            int tSteps = Math.max(2, (int) Math.ceil(Math.sqrt(targetPoints)));
            int uSteps = Math.max(2, (int) Math.ceil((double) targetPoints / (double) tSteps));
            for (int ui = 0; ui < uSteps; ui++) {
                double u = lerp(config.uMin(), config.uMax(), uSteps <= 1 ? 0.0 : (double) ui / (double) (uSteps - 1));
                for (int ti = 0; ti < tSteps; ti++) {
                    if (out.size() >= targetPoints || budget < evalsPerPoint) {
                        break;
                    }
                    double t = lerp(config.tMin(), config.tMax(), tSteps <= 1 ? 0.0 : (double) ti / (double) (tSteps - 1));
                    addPoint(out, out.size(), targetPoints, mode, config, xCompiled, yCompiled, zCompiled, rCompiled, gCompiled, bCompiled, t, u, time);
                    budget -= evalsPerPoint;
                }
                if (out.size() >= targetPoints || budget < evalsPerPoint) {
                    break;
                }
            }
        } else {
            for (int i = 0; i < targetPoints && budget >= evalsPerPoint; i++) {
                double t = lerp(config.tMin(), config.tMax(), targetPoints <= 1 ? 0.0 : (double) i / (double) (targetPoints - 1));
                addPoint(out, i, targetPoints, mode, config, xCompiled, yCompiled, zCompiled, rCompiled, gCompiled, bCompiled, t, 0.0, time);
                budget -= evalsPerPoint;
            }
        }

        if (out.isEmpty()) {
            throw new IllegalArgumentException("budget_exhausted");
        }
        return out;
    }

    public static int estimateEvalCost(EquationParticleConfig config) {
        String mode = normalizeMode(config.colorMode());
        int evalsPerPoint = "expression".equals(mode) ? 6 : 3;
        long cost = (long) Math.max(1, config.pointCount()) * (long) evalsPerPoint;
        if (cost > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) cost;
    }

    private static void addPoint(
        List<GeneratedPoint> out,
        int index,
        int total,
        String mode,
        EquationParticleConfig config,
        EquationEvaluator.CompiledExpression xCompiled,
        EquationEvaluator.CompiledExpression yCompiled,
        EquationEvaluator.CompiledExpression zCompiled,
        EquationEvaluator.CompiledExpression rCompiled,
        EquationEvaluator.CompiledExpression gCompiled,
        EquationEvaluator.CompiledExpression bCompiled,
        double t,
        double u,
        double time
    ) {
        double x = xCompiled.eval(t, u, time);
        double y = yCompiled.eval(t, u, time);
        double z = zCompiled.eval(t, u, time);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }

        Vec3 color;
        if ("single".equals(mode)) {
            color = clamp(config.fixedR(), config.fixedG(), config.fixedB());
        } else if ("gradient".equals(mode)) {
            float f = total <= 1 ? 0.0f : (float) index / (float) (total - 1);
            color = clamp(
                lerp(config.gradientStartR(), config.gradientEndR(), f),
                lerp(config.gradientStartG(), config.gradientEndG(), f),
                lerp(config.gradientStartB(), config.gradientEndB(), f)
            );
        } else {
            color = clamp(
                rCompiled.eval(t, u, time),
                gCompiled.eval(t, u, time),
                bCompiled.eval(t, u, time)
            );
        }

        out.add(new GeneratedPoint(new Vec3(x, y, z), color));
    }

    private static String normalizeMode(String mode) {
        if (mode == null) {
            return "gradient";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "single", "gradient", "expression" -> normalized;
            default -> "gradient";
        };
    }

    private static Vec3 clamp(double r, double g, double b) {
        return new Vec3(clamp01(r), clamp01(g), clamp01(b));
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static boolean usesTime(EquationParticleConfig config) {
        return containsTime(config.xExpr())
            || containsTime(config.yExpr())
            || containsTime(config.zExpr())
            || containsTime(config.colorExprR())
            || containsTime(config.colorExprG())
            || containsTime(config.colorExprB());
    }

    private static boolean containsTime(String expr) {
        if (expr == null) {
            return false;
        }

        String lower = expr.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("time");
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1)) && lower.charAt(idx - 1) != '_';
            int rightIndex = idx + 4;
            boolean rightOk = rightIndex >= lower.length() || !Character.isLetterOrDigit(lower.charAt(rightIndex)) && lower.charAt(rightIndex) != '_';
            if (leftOk && rightOk) {
                return true;
            }
            idx = lower.indexOf("time", idx + 1);
        }
        return false;
    }
}
