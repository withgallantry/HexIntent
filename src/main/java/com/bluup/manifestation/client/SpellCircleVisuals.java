package com.bluup.manifestation.client;

import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.render.HexPatternLike;
import at.petrak.hexcasting.client.render.HexPatternPoints;
import at.petrak.hexcasting.client.render.PatternSettings;
import com.bluup.manifestation.common.ManifestationNetworking;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class SpellCircleVisuals {
    private static final Map<CircleKey, CircleState> ACTIVE = new HashMap<>();

    private static final int MAX_PATTERNS = 48;
    private static final int MAX_TICKS = 1200;
    private static final int FADE_TICKS = 10;

    private static final int CIRCLE_SEGMENTS = 64;
    private static final double BASE_RADIUS = 1.1;
    private static final double RADIUS_PER_PATTERN = 0.09;
    private static final double CIRCLE_SPIN_RATE = 0.004;
    private static final PatternSettings SPELL_GLYPH_SETTINGS = new PatternSettings(
        "manifestation_circle_spell",
        PatternSettings.PositionSettings.paddedSquare(0.08, 0.22, 0.0),
        PatternSettings.StrokeSettings.fromStroke(0.11),
        PatternSettings.ZappySettings.WOBBLY
    );

    private SpellCircleVisuals() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.SPELL_CIRCLE_S2C,
            (client, handler, buf, responseSender) -> {
                String dimensionId = buf.readUtf();
                long id = buf.readLong();
                Vec3 origin = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                Vec3 normal = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                int lifetimeTicks = Mth.clamp(buf.readVarInt(), 1, MAX_TICKS);
                int sizeTier = Mth.clamp(buf.readVarInt(), 1, 6);
                int count = Mth.clamp(buf.readVarInt(), 0, MAX_PATTERNS);

                List<HexPattern> patterns = new ArrayList<>(count);
                HexDir[] dirs = HexDir.values();
                for (int i = 0; i < count; i++) {
                    String signature = buf.readUtf(128);
                    int startDirOrdinal = buf.readVarInt();
                    if (startDirOrdinal < 0 || startDirOrdinal >= dirs.length) {
                        continue;
                    }

                    try {
                        patterns.add(HexPattern.fromAngles(signature, dirs[startDirOrdinal]));
                    } catch (IllegalArgumentException | IllegalStateException ignored) {
                    }
                }

                client.execute(() -> {
                    if (client.level == null || patterns.isEmpty()) {
                        return;
                    }

                    long now = client.level.getGameTime();
                    CircleKey key = new CircleKey(dimensionId, id);
                    CircleState state = new CircleState();
                    state.dimensionId = dimensionId;
                    state.origin = origin;
                    state.normal = safeNormal(normal);
                    state.patterns = List.copyOf(patterns);
                    state.initialLifetimeTicks = lifetimeTicks;
                    state.remainingTicks = lifetimeTicks;
                    state.sizeTier = sizeTier;
                    state.scaleMultiplier = scaleForTier(sizeTier);
                    state.lastUpdateTick = now;
                    ACTIVE.put(key, state);
                });
            }
        );

        ClientTickEvents.END_CLIENT_TICK.register(SpellCircleVisuals::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> render(Minecraft.getInstance(), context.matrixStack()));
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.isPaused() || ACTIVE.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<CircleKey, CircleState>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<CircleKey, CircleState> entry = it.next();
            CircleState state = entry.getValue();
            state.remainingTicks--;
            state.lastUpdateTick = mc.level.getGameTime();
            if (state.remainingTicks > 0) {
                continue;
            }

            spawnDissolveParticles(mc, state, entry.getKey().id());
            it.remove();
        }
    }

    private static void render(Minecraft mc, PoseStack poseStack) {
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) {
            return;
        }

        String currentDimension = mc.level.dimension().location().toString();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        long now = mc.level.getGameTime();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        try {
            Matrix4f mat = poseStack.last().pose();
            for (CircleState state : ACTIVE.values()) {
                if (!Objects.equals(currentDimension, state.dimensionId)) {
                    continue;
                }

                float alpha = alphaFor(state.remainingTicks, state.initialLifetimeTicks);
                if (alpha <= 0.0f) {
                    continue;
                }

                renderCircleState(vc, mat, state, camera, now, alpha);
            }
        } finally {
            poseStack.popPose();
            buffers.endBatch(RenderType.lightning());
        }
    }

    private static void renderCircleState(VertexConsumer vc, Matrix4f mat, CircleState state, Vec3 camera, long now, float alpha) {
        Basis basis = buildBasis(state.normal);
        Basis spunBasis = rotateBasis(basis, now * CIRCLE_SPIN_RATE);
        double pulse = 1.0 + Math.sin(now * 0.14) * 0.035;
        double scale = state.scaleMultiplier;

        double radius = ((BASE_RADIUS + (state.patterns.size() * RADIUS_PER_PATTERN)) * scale) * pulse;
        double outerRingRadius = radius;
        double innerRingRadius = Math.max(0.28 * scale, radius - (0.24 * scale));
        double triangleRadius = innerRingRadius * 0.78;
        double glyphOrbit = Math.max(0.4 * scale, radius - (0.34 * scale));
        float outerRingWidth = (float) (0.043 * scale);
        float innerRingWidth = (float) (0.032 * scale);
        float triangleWidth = (float) (0.03 * scale);

        float coreR = 0.24f;
        float coreG = 0.90f;
        float coreB = 0.86f;
        float rimR = 0.55f;
        float rimG = 1.0f;
        float rimB = 0.96f;

        drawCircleOutline(vc, mat, state.origin, spunBasis, outerRingRadius, outerRingWidth, rimR, rimG, rimB, alpha * 0.92f);
        drawCircleOutline(vc, mat, state.origin, spunBasis, innerRingRadius, innerRingWidth, coreR, coreG, coreB, alpha * 0.86f);
        drawTriangleOutline(vc, mat, state.origin, spunBasis, triangleRadius, triangleWidth, coreR, coreG, coreB, alpha * 0.82f);

        int count = state.patterns.size();
        if (count <= 0) {
            return;
        }

        for (int i = 0; i < count; i++) {
            double theta = ((Math.PI * 2.0) * i / count);
            Vec3 radial = spunBasis.u().scale(Math.cos(theta)).add(spunBasis.v().scale(Math.sin(theta)));
            Vec3 tangent = spunBasis.u().scale(-Math.sin(theta)).add(spunBasis.v().scale(Math.cos(theta))).normalize();
            double side = Math.signum(camera.subtract(state.origin).dot(spunBasis.n()));
            if (side == 0.0) {
                side = 1.0;
            }
            Vec3 glyphCenter = state.origin.add(radial.scale(glyphOrbit)).add(spunBasis.n().scale(0.02 * scale * side));
            float glyphScaleBase = (float) Mth.clamp(0.32 - (count * 0.004), 0.13, 0.3);
            float glyphScale = glyphScaleBase * (float) scale;
            renderPatternGlyph(vc, mat, state.patterns.get(i), glyphCenter, tangent, radial.normalize(), glyphScale, alpha, i, now);
        }
    }

    private static void renderPatternGlyph(
        VertexConsumer vc,
        Matrix4f mat,
        HexPattern pattern,
        Vec3 center,
        Vec3 tangent,
        Vec3 radial,
        float glyphScale,
        float alpha,
        int glyphIndex,
        long tick
    ) {
        double seed = (pattern.anglesSignature().hashCode() * 31.0) + (glyphIndex * 131.0) + (tick * 0.025);
        List<Vec3> points = patternPointsInPlane(pattern, center, tangent, radial, glyphScale, seed);
        if (points.size() < 2) {
            return;
        }

        float r = 0.90f;
        float g = 1.0f;
        float b = 0.96f;
        float width = (float) SPELL_GLYPH_SETTINGS.getOuterWidth(1.0) * 0.9f * glyphScale;
        Vec3 normal = tangent.cross(radial).normalize();
        for (int i = 1; i < points.size(); i++) {
            Vec3 from = points.get(i - 1);
            Vec3 to = points.get(i);
            drawThickSegment(vc, mat, from, to, normal, width, r, g, b, alpha * 0.95f);
        }
    }

    private static List<Vec3> patternPointsInPlane(
        HexPattern pattern,
        Vec3 center,
        Vec3 tangent,
        Vec3 radial,
        float scale,
        double seed
    ) {
        PatternSettings settings = new PatternSettings(
            SPELL_GLYPH_SETTINGS.getName(),
            SPELL_GLYPH_SETTINGS.posSets,
            new PatternSettings.StrokeSettings(
                SPELL_GLYPH_SETTINGS.strokeSets.innerWidth() * scale,
                SPELL_GLYPH_SETTINGS.strokeSets.outerWidth() * scale,
                SPELL_GLYPH_SETTINGS.strokeSets.startDotRadius() * scale,
                SPELL_GLYPH_SETTINGS.strokeSets.gridDotsRadius() * scale
            ),
            SPELL_GLYPH_SETTINGS.zapSets
        );

        HexPatternPoints points = HexPatternPoints.getStaticPoints(HexPatternLike.of(pattern), settings, seed);
        if (points.zappyPointsScaled.isEmpty()) {
            return List.of();
        }

        double xCenter = points.fullWidth * 0.5;
        double yCenter = points.fullHeight * 0.5;

        List<Vec3> out = new ArrayList<>(points.zappyPointsScaled.size());
        for (var point : points.zappyPointsScaled) {
            double x = point.x - xCenter;
            double y = point.y - yCenter;
            out.add(center.add(tangent.scale(x)).add(radial.scale(y)));
        }
        return out;
    }

    private static void drawCircleOutline(
        VertexConsumer vc,
        Matrix4f mat,
        Vec3 center,
        Basis basis,
        double radius,
        float width,
        float r,
        float g,
        float b,
        float a
    ) {
        Vec3 normal = basis.n();
        Vec3 prev = null;
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double t = (Math.PI * 2.0) * i / CIRCLE_SEGMENTS;
            Vec3 point = center.add(basis.u().scale(Math.cos(t) * radius)).add(basis.v().scale(Math.sin(t) * radius));
            if (prev != null) {
                drawThickSegment(vc, mat, prev, point, normal, width, r, g, b, a);
            }
            prev = point;
        }

        Vec3 first = center.add(basis.u().scale(radius));
        if (prev != null) {
            drawThickSegment(vc, mat, prev, first, normal, width, r, g, b, a);
        }
    }

    private static void drawTriangleOutline(
        VertexConsumer vc,
        Matrix4f mat,
        Vec3 center,
        Basis basis,
        double radius,
        float width,
        float r,
        float g,
        float b,
        float a
    ) {
        Vec3 normal = basis.n();
        double spin = 0.0;

        Vec3[] corners = new Vec3[3];
        for (int i = 0; i < 3; i++) {
            double angle = spin + (Math.PI * 2.0 * i / 3.0) - (Math.PI / 2.0);
            corners[i] = center
                .add(basis.u().scale(Math.cos(angle) * radius))
                .add(basis.v().scale(Math.sin(angle) * radius));
        }

        drawThickSegment(vc, mat, corners[0], corners[1], normal, width, r, g, b, a);
        drawThickSegment(vc, mat, corners[1], corners[2], normal, width, r, g, b, a);
        drawThickSegment(vc, mat, corners[2], corners[0], normal, width, r, g, b, a);
    }

    private static void drawThickSegment(
        VertexConsumer vc,
        Matrix4f mat,
        Vec3 from,
        Vec3 to,
        Vec3 normal,
        float width,
        float r,
        float g,
        float b,
        float a
    ) {
        Vec3 tangent = to.subtract(from);
        if (tangent.lengthSqr() <= 1.0e-8) {
            return;
        }

        Vec3 side = normal.cross(tangent).normalize();
        if (side.lengthSqr() <= 1.0e-8) {
            side = orthogonal(normal);
        }
        Vec3 off = side.scale(width * 0.5);

        Vec3 p0 = from.add(off);
        Vec3 p1 = to.add(off);
        Vec3 p2 = to.subtract(off);
        Vec3 p3 = from.subtract(off);

        emitQuadDoubleSided(vc, mat, p0, p1, p2, p3, r, g, b, a);
    }

    private static void emitQuadDoubleSided(
        VertexConsumer vc,
        Matrix4f mat,
        Vec3 p0,
        Vec3 p1,
        Vec3 p2,
        Vec3 p3,
        float r,
        float g,
        float b,
        float a
    ) {
        vertex(vc, mat, p0, r, g, b, a);
        vertex(vc, mat, p1, r, g, b, a);
        vertex(vc, mat, p2, r, g, b, a);
        vertex(vc, mat, p3, r, g, b, a);

        vertex(vc, mat, p3, r, g, b, a);
        vertex(vc, mat, p2, r, g, b, a);
        vertex(vc, mat, p1, r, g, b, a);
        vertex(vc, mat, p0, r, g, b, a);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, Vec3 p, float r, float g, float b, float a) {
        vc.vertex(mat, (float) p.x, (float) p.y, (float) p.z)
            .color(r, g, b, a)
            .endVertex();
    }

    private static void spawnDissolveParticles(Minecraft mc, CircleState state, long id) {
        if (mc.level == null) {
            return;
        }

        Basis basis = buildBasis(state.normal);
        double radius = (BASE_RADIUS + (state.patterns.size() * RADIUS_PER_PATTERN)) * state.scaleMultiplier;
        Random rng = new Random(id ^ (state.lastUpdateTick * 31L));

        int ringBursts = 92;
        for (int i = 0; i < ringBursts; i++) {
            double t = (Math.PI * 2.0) * i / ringBursts;
            double radialJitter = (rng.nextDouble() - 0.5) * 0.24;
            Vec3 radial = basis.u().scale(Math.cos(t)).add(basis.v().scale(Math.sin(t))).normalize();
            Vec3 spawn = state.origin
                .add(radial.scale(radius + radialJitter))
                .add(state.normal.scale((rng.nextDouble() - 0.5) * 0.08 * state.scaleMultiplier));

            Vec3 vel = radial.scale((0.05 + rng.nextDouble() * 0.06) * state.scaleMultiplier)
                .add(state.normal.scale((rng.nextDouble() - 0.5) * 0.03 * state.scaleMultiplier));

            mc.level.addParticle(ParticleTypes.ENCHANT, spawn.x, spawn.y, spawn.z, vel.x, vel.y, vel.z);
            if ((i & 3) == 0) {
                mc.level.addParticle(ParticleTypes.ENCHANT, spawn.x, spawn.y, spawn.z, vel.x * 0.45, vel.y * 0.45, vel.z * 0.45);
            }
        }
    }

    private static float alphaFor(int remainingTicks, int initialTicks) {
        if (remainingTicks <= 0) {
            return 0.0f;
        }

        float fadeOut = remainingTicks <= FADE_TICKS ? (remainingTicks / (float) FADE_TICKS) : 1.0f;
        int warmup = Math.min(FADE_TICKS, initialTicks);
        float fadeIn = remainingTicks >= (initialTicks - warmup)
            ? (initialTicks - remainingTicks + 1) / (float) Math.max(1, warmup)
            : 1.0f;

        return Mth.clamp(Math.min(fadeIn, fadeOut), 0.0f, 1.0f);
    }

    private static Basis buildBasis(Vec3 normal) {
        Vec3 n = safeNormal(normal);
        Vec3 reference = Math.abs(n.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 u = n.cross(reference);
        if (u.lengthSqr() <= 1.0e-8) {
            u = new Vec3(0.0, 0.0, 1.0);
        }
        u = u.normalize();
        Vec3 v = u.cross(n).normalize();
        return new Basis(u, v, n);
    }

    private static Vec3 safeNormal(Vec3 normal) {
        if (normal.lengthSqr() <= 1.0e-8) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        return normal.normalize();
    }

    private static Vec3 orthogonal(Vec3 v) {
        Vec3 reference = Math.abs(v.y) > 0.8 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 out = v.cross(reference);
        if (out.lengthSqr() <= 1.0e-8) {
            out = new Vec3(0.0, 0.0, 1.0);
        }
        return out.normalize();
    }

    private static Basis rotateBasis(Basis basis, double angleRad) {
        double c = Math.cos(angleRad);
        double s = Math.sin(angleRad);
        Vec3 u = basis.u().scale(c).add(basis.v().scale(s));
        Vec3 v = basis.u().scale(-s).add(basis.v().scale(c));
        return new Basis(u, v, basis.n());
    }

    private static double scaleForTier(int tier) {
        int t = Mth.clamp(tier, 1, 6);
        return 0.25 + ((t - 1) * 0.375);
    }

    private record CircleKey(String dimensionId, long id) {
    }

    private static final class CircleState {
        String dimensionId = "";
        Vec3 origin = Vec3.ZERO;
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        List<HexPattern> patterns = List.of();
        int initialLifetimeTicks = 1;
        int remainingTicks = 1;
        int sizeTier = 3;
        double scaleMultiplier = 1.0;
        long lastUpdateTick;
    }

    private record Basis(Vec3 u, Vec3 v, Vec3 n) {
    }
}
