package com.bluup.manifestation.client;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.client.ClientTickCounter;
import at.petrak.hexcasting.client.render.PatternColors;
import at.petrak.hexcasting.client.render.WorldlyPatternRenderHelpers;
import at.petrak.hexcasting.common.particles.ConjureParticleOptions;
import com.bluup.manifestation.common.ManifestationNetworking;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Comparator;

public final class SpellCircleVisuals {
    private static final Map<CircleKey, CircleState> ACTIVE = new HashMap<>();

    private static final int MAX_PATTERNS = 48;
    private static final int MAX_TICKS = 1200;
    private static final int FADE_TICKS = 10;

    private static final int CIRCLE_SEGMENTS = 64;
    private static final double BASE_RADIUS = 1.1;
    private static final double RADIUS_PER_PATTERN = 0.09;
    private static final double CIRCLE_SPIN_RATE = 0.010;
    private static final double OVERLAP_LAYER_STEP = 0.0018;

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
                Vec3 openingAngle = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                int lifetimeTicks = Mth.clamp(buf.readVarInt(), 1, MAX_TICKS);
                int sizeTier = Mth.clamp(buf.readVarInt(), 1, 6);
                var colorTag = buf.readNbt();
                if (colorTag == null) {
                    return;
                }
                FrozenPigment colorizer = FrozenPigment.fromNBT(colorTag);
                int count = Mth.clamp(buf.readVarInt(), 0, MAX_PATTERNS);

                List<HexPattern> patterns = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    var patternTag = buf.readNbt();
                    if (patternTag == null) {
                        continue;
                    }

                    try {
                        patterns.add(HexPattern.fromNBT(patternTag));
                    } catch (IllegalArgumentException | IllegalStateException ignored) {
                    }
                }

                client.execute(() -> {
                    if (client.level == null || patterns.isEmpty()) {
                        return;
                    }

                    if (!isFinite(origin) || !isFinite(normal) || !isFinite(openingAngle)) {
                        return;
                    }

                    long now = client.level.getGameTime();
                    CircleKey key = new CircleKey(dimensionId, id);
                    CircleState state = new CircleState();
                    state.dimensionId = dimensionId;
                    state.origin = origin;
                    state.normal = safeNormal(normal);
                    state.openingAngle = openingAngle;
                    state.patterns = List.copyOf(patterns);
                    state.initialLifetimeTicks = lifetimeTicks;
                    state.remainingTicks = lifetimeTicks;
                    state.sizeTier = sizeTier;
                    state.scaleMultiplier = scaleForTier(sizeTier);
                    state.colorizer = colorizer;
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

        MultiBufferSource.BufferSource glyphBuffers = mc.renderBuffers().bufferSource();
        MultiBufferSource.BufferSource lineBuffers = MultiBufferSource.immediate(new BufferBuilder(8192));
        VertexConsumer vc = lineBuffers.getBuffer(RenderType.lightning());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        try {
            Matrix4f mat = poseStack.last().pose();
            List<Map.Entry<CircleKey, CircleState>> circles = new ArrayList<>(ACTIVE.entrySet());
            circles.sort(Comparator
                .comparingDouble((Map.Entry<CircleKey, CircleState> entry) -> entry.getValue().origin.distanceToSqr(camera))
                .reversed()
                .thenComparing(entry -> entry.getKey().dimensionId())
                .thenComparingLong(entry -> entry.getKey().id()));

            int overlapLayer = 0;
            for (Map.Entry<CircleKey, CircleState> entry : circles) {
                CircleState state = entry.getValue();
                if (!Objects.equals(currentDimension, state.dimensionId)) {
                    continue;
                }
                if (!isFinite(state.origin) || !isFinite(state.normal) || !isFinite(state.openingAngle)) {
                    continue;
                }

                float alpha = alphaFor(state.remainingTicks, state.initialLifetimeTicks);
                if (alpha <= 0.0f) {
                    continue;
                }

                renderCircleState(vc, mat, poseStack, glyphBuffers, state, now, alpha, overlapLayer++);
            }
        } finally {
            poseStack.popPose();
            lineBuffers.endBatch(RenderType.lightning());
        }
    }

    private static void renderCircleState(
        VertexConsumer vc,
        Matrix4f mat,
        PoseStack poseStack,
        MultiBufferSource buffers,
        CircleState state,
        long now,
        float alpha,
        int overlapLayer
    ) {
        Basis basis = buildBasis(state.normal, state.openingAngle);
        Basis spunBasis = rotateBasis(basis, now * CIRCLE_SPIN_RATE);
        Vec3 lineOrigin = state.origin.add(spunBasis.n().scale(OVERLAP_LAYER_STEP * overlapLayer));
        double pulse = 1.0 + Math.sin(now * 0.14) * 0.035;
        double openingScale = openingScaleFor(state.remainingTicks, state.initialLifetimeTicks);
        double scale = state.scaleMultiplier * openingScale;

        double radius = ((BASE_RADIUS + (state.patterns.size() * RADIUS_PER_PATTERN)) * scale) * pulse;
        double outerRingRadius = radius;
        double innerRingRadius = Math.max(0.24 * scale, radius * 0.58);
        double triangleRadius = innerRingRadius * 0.78;
        double glyphOrbit = (outerRingRadius + innerRingRadius) * 0.5;
        double ringBandWidth = Math.max(0.08 * scale, outerRingRadius - innerRingRadius);
        float outerRingWidth = (float) (0.043 * scale);
        float innerRingWidth = (float) (0.032 * scale);
        float triangleWidth = (float) (0.03 * scale);
        float colorTime = ClientTickCounter.getTotal() / 2.0f;

        int coreColor = state.colorizer.getColorProvider().getColor(colorTime, state.origin);
        float tintR = ((coreColor >> 16) & 0xFF) / 255.0f;
        float tintG = ((coreColor >> 8) & 0xFF) / 255.0f;
        float tintB = (coreColor & 0xFF) / 255.0f;
        float coreR = tintR;
        float coreG = tintG;
        float coreB = tintB;
        float rimR = Mth.clamp(tintR * 0.72f + 0.28f, 0.0f, 1.0f);
        float rimG = Mth.clamp(tintG * 0.72f + 0.28f, 0.0f, 1.0f);
        float rimB = Mth.clamp(tintB * 0.72f + 0.28f, 0.0f, 1.0f);

        drawCircleOutline(vc, mat, lineOrigin, spunBasis, outerRingRadius, outerRingWidth, rimR, rimG, rimB, alpha * 0.92f);
        drawCircleOutline(vc, mat, lineOrigin, spunBasis, innerRingRadius, innerRingWidth, coreR, coreG, coreB, alpha * 0.86f);
        drawTriangleOutline(vc, mat, lineOrigin, spunBasis, triangleRadius, triangleWidth, coreR, coreG, coreB, alpha * 0.82f);

        int count = state.patterns.size();
        if (count <= 0) {
            return;
        }

        for (int i = 0; i < count; i++) {
            double theta = ((Math.PI * 2.0) * i / count);
            Vec3 radial = spunBasis.u().scale(Math.cos(theta)).add(spunBasis.v().scale(Math.sin(theta)));
            Vec3 tangent = spunBasis.u().scale(-Math.sin(theta)).add(spunBasis.v().scale(Math.cos(theta))).normalize();
            Vec3 glyphCenter = state.origin.add(radial.scale(glyphOrbit)).add(spunBasis.n().scale(0.02 * scale));
            float glyphScale = (float) Mth.clamp(
                (ringBandWidth * 1.18) / (1.0 + (count * 0.006)),
                0.30 * scale,
                0.92 * scale
            );
            int patternColor = state.colorizer.getColorProvider().getColor(colorTime, glyphCenter);
            int light = LevelRenderer.getLightColor(Minecraft.getInstance().level, BlockPos.containing(glyphCenter));
            renderPatternGlyph(
                poseStack,
                buffers,
                state.patterns.get(i),
                glyphCenter,
                tangent,
                radial.normalize(),
                glyphScale,
                i,
                light,
                0xFF000000 | (patternColor & 0x00FFFFFF)
            );
        }
    }

    private static void renderPatternGlyph(
        PoseStack poseStack,
        MultiBufferSource buffers,
        HexPattern pattern,
        Vec3 center,
        Vec3 tangent,
        Vec3 radial,
        float glyphScale,
        int glyphIndex,
        int light,
        int patternColor
    ) {
        Vec3 tangentN = tangent.normalize();
        Vec3 radialN = radial.normalize();
        Vec3 normal = tangentN.cross(radialN);
        if (normal.lengthSqr() <= 1.0e-8) {
            return;
        }
        normal = normal.normalize();

        // Keep seed stable across frames so HexCasting render caches can be reused.
        double seed = (pattern.anglesSignature().hashCode() * 31.0) + (glyphIndex * 131.0);

        Matrix3f basis = new Matrix3f(
            (float) tangentN.x,
            (float) radialN.x,
            (float) normal.x,
            (float) tangentN.y,
            (float) radialN.y,
            (float) normal.y,
            (float) tangentN.z,
            (float) radialN.z,
            (float) normal.z
        );
        Quaternionf orientation = new Quaternionf().setFromNormalized(basis);

        poseStack.pushPose();
        poseStack.translate(center.x, center.y, center.z);
        poseStack.mulPose(orientation);
        poseStack.scale(glyphScale, glyphScale, 1.0f);
        poseStack.translate(-0.5, -0.5, 0.0);
        WorldlyPatternRenderHelpers.renderPattern(
            pattern,
            WorldlyPatternRenderHelpers.WORLDLY_SETTINGS_WOBBLY,
            PatternColors.singleStroke(patternColor),
            seed,
            poseStack,
            buffers,
            normal,
            -0.001f,
            light,
            1
        );
        poseStack.popPose();
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
        if (!isFinite(from) || !isFinite(to) || !isFinite(normal)) {
            return;
        }

        Vec3 tangent = to.subtract(from);
        if (tangent.lengthSqr() <= 1.0e-8) {
            return;
        }

        Vec3 side = normal.cross(tangent);
        if (side.lengthSqr() <= 1.0e-8) {
            side = orthogonal(normal);
        } else {
            side = side.normalize();
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
        if (!isFinite(p0) || !isFinite(p1) || !isFinite(p2) || !isFinite(p3)) {
            return;
        }

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

        Basis basis = buildBasis(state.normal, state.openingAngle);
        double radius = (BASE_RADIUS + (state.patterns.size() * RADIUS_PER_PATTERN)) * state.scaleMultiplier;
        Random rng = new Random(id ^ (state.lastUpdateTick * 31L));

        int ringBursts = 92;
        float colorTime = ClientTickCounter.getTotal();
        var colorProvider = state.colorizer.getColorProvider();
        for (int i = 0; i < ringBursts; i++) {
            double t = (Math.PI * 2.0) * i / ringBursts;
            double radialJitter = (rng.nextDouble() - 0.5) * 0.24;
            Vec3 radial = basis.u().scale(Math.cos(t)).add(basis.v().scale(Math.sin(t))).normalize();
            Vec3 spawn = state.origin
                .add(radial.scale(radius + radialJitter))
                .add(state.normal.scale((rng.nextDouble() - 0.5) * 0.08 * state.scaleMultiplier));

            Vec3 vel = radial.scale((0.018 + rng.nextDouble() * 0.03) * state.scaleMultiplier)
                .add(state.normal.scale((rng.nextDouble() - 0.5) * 0.018 * state.scaleMultiplier))
                .add(0.0, -(0.03 + rng.nextDouble() * 0.03) * state.scaleMultiplier, 0.0);

            int baseColor = colorProvider.getColor(colorTime + rng.nextFloat() * 8.0f, vel.normalize());
            float baseR = ((baseColor >> 16) & 0xFF) / 255.0f;
            float baseG = ((baseColor >> 8) & 0xFF) / 255.0f;
            float baseB = (baseColor & 0xFF) / 255.0f;
            float r = Mth.lerp(0.78f + ((float) rng.nextDouble() * 0.18f), baseR, 1.0f);
            float g = Mth.lerp(0.78f + ((float) rng.nextDouble() * 0.18f), baseG, 1.0f);
            float b = Mth.lerp(0.78f + ((float) rng.nextDouble() * 0.18f), baseB, 1.0f);
            mc.level.addParticle(new ConjureParticleOptions(packColor(r, g, b)), spawn.x, spawn.y, spawn.z, vel.x, vel.y, vel.z);
            if ((i & 3) == 0) {
                mc.level.addParticle(new ConjureParticleOptions(packColor(r * 0.92f, g * 0.94f, b)), spawn.x, spawn.y, spawn.z, vel.x * 0.55, vel.y * 0.55, vel.z * 0.55);
            }
        }
    }

    private static int packColor(float r, float g, float b) {
        int rr = Mth.clamp((int) (r * 255.0f), 0, 255);
        int gg = Mth.clamp((int) (g * 255.0f), 0, 255);
        int bb = Mth.clamp((int) (b * 255.0f), 0, 255);
        return (rr << 16) | (gg << 8) | bb;
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

    private static double openingScaleFor(int remainingTicks, int initialTicks) {
        if (remainingTicks <= 0) {
            return 0.0;
        }

        int warmup = Math.min(FADE_TICKS, initialTicks);
        if (warmup <= 0) {
            return 1.0;
        }

        float progress = remainingTicks >= (initialTicks - warmup)
            ? (initialTicks - remainingTicks + 1) / (float) Math.max(1, warmup)
            : 1.0f;
        float eased = progress * progress * (3.0f - (2.0f * progress));
        return Mth.clamp(eased, 0.0f, 1.0f);
    }

    private static Basis buildBasis(Vec3 normal, Vec3 openingAngle) {
        Vec3 n = safeNormal(normal);
        Vec3 u = projectOntoPlane(openingAngle, n);
        if (u.lengthSqr() <= 1.0e-8) {
            Vec3 fallback = Math.abs(n.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
            u = projectOntoPlane(fallback, n);
        }
        if (u.lengthSqr() <= 1.0e-8) {
            u = new Vec3(0.0, 0.0, 1.0);
        }
        u = u.normalize();
        Vec3 v = u.cross(n).normalize();
        return new Basis(u, v, n);
    }

    private static Vec3 projectOntoPlane(Vec3 v, Vec3 normal) {
        return v.subtract(normal.scale(v.dot(normal)));
    }

    private static Vec3 safeNormal(Vec3 normal) {
        if (!isFinite(normal)) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        if (normal.lengthSqr() <= 1.0e-8) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        return normal.normalize();
    }

    private static boolean isFinite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
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
        Vec3 openingAngle = new Vec3(1.0, 0.0, 0.0);
        List<HexPattern> patterns = List.of();
        int initialLifetimeTicks = 1;
        int remainingTicks = 1;
        int sizeTier = 3;
        double scaleMultiplier = 1.0;
        FrozenPigment colorizer = FrozenPigment.fromNBT(new net.minecraft.nbt.CompoundTag());
        long lastUpdateTick;
    }

    private record Basis(Vec3 u, Vec3 v, Vec3 n) {
    }
}
