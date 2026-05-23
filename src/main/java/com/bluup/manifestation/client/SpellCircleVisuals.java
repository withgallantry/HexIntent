package com.bluup.manifestation.client;

import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
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
import net.minecraft.world.phys.Vec2;
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

                renderCircleState(vc, mat, state, now, alpha);
            }
        } finally {
            poseStack.popPose();
            buffers.endBatch(RenderType.lightning());
        }
    }

    private static void renderCircleState(VertexConsumer vc, Matrix4f mat, CircleState state, long now, float alpha) {
        Basis basis = buildBasis(state.normal);
        double pulse = 1.0 + Math.sin(now * 0.14) * 0.035;

        double radius = (BASE_RADIUS + (state.patterns.size() * RADIUS_PER_PATTERN)) * pulse;
        double rimOuter = radius;
        double rimInner = radius - 0.11;
        double glyphOrbit = Math.max(0.4, radius - 0.32);
        float glyphWidth = 0.042f;

        float coreR = 0.14f;
        float coreG = 0.82f;
        float coreB = 0.78f;
        float rimR = 0.55f;
        float rimG = 1.0f;
        float rimB = 0.96f;

        drawDisc(vc, mat, state.origin, basis, rimInner, coreR, coreG, coreB, alpha * 0.35f);
        drawRing(vc, mat, state.origin, basis, rimInner, rimOuter, rimR, rimG, rimB, alpha * 0.82f);

        int count = state.patterns.size();
        if (count <= 0) {
            return;
        }

        for (int i = 0; i < count; i++) {
            double theta = ((Math.PI * 2.0) * i / count) + (now * 0.015);
            Vec3 radial = basis.u().scale(Math.cos(theta)).add(basis.v().scale(Math.sin(theta)));
            Vec3 glyphCenter = state.origin.add(radial.scale(glyphOrbit)).add(state.normal.scale(0.012));
            float glyphScale = (float) Mth.clamp(0.32 - (count * 0.004), 0.13, 0.3);
            renderPatternGlyph(vc, mat, state.patterns.get(i), glyphCenter, basis, glyphScale, glyphWidth, alpha);
        }
    }

    private static void renderPatternGlyph(
        VertexConsumer vc,
        Matrix4f mat,
        HexPattern pattern,
        Vec3 center,
        Basis basis,
        float glyphScale,
        float width,
        float alpha
    ) {
        List<Vec3> points = patternPointsInPlane(pattern, center, basis, glyphScale);
        if (points.size() < 2) {
            return;
        }

        float r = 0.90f;
        float g = 1.0f;
        float b = 0.96f;
        for (int i = 1; i < points.size(); i++) {
            Vec3 from = points.get(i - 1);
            Vec3 to = points.get(i);
            drawThickSegment(vc, mat, from, to, basis.n(), width, r, g, b, alpha * 0.95f);
        }
    }

    private static List<Vec3> patternPointsInPlane(HexPattern pattern, Vec3 center, Basis basis, float scale) {
        List<?> raw = pattern.toLines(scale, new Vec2(0.0f, 0.0f));
        if (raw.isEmpty()) {
            return List.of();
        }

        List<Vec3> out = new ArrayList<>(raw.size());
        for (Object obj : raw) {
            if (!(obj instanceof Vec2 point)) {
                continue;
            }
            out.add(center.add(basis.u().scale(point.x)).add(basis.v().scale(point.y)));
        }
        return out;
    }

    private static void drawDisc(VertexConsumer vc, Matrix4f mat, Vec3 center, Basis basis, double radius, float r, float g, float b, float a) {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double t0 = (Math.PI * 2.0) * i / CIRCLE_SEGMENTS;
            double t1 = (Math.PI * 2.0) * (i + 1) / CIRCLE_SEGMENTS;

            Vec3 outer0 = center.add(basis.u().scale(Math.cos(t0) * radius)).add(basis.v().scale(Math.sin(t0) * radius));
            Vec3 outer1 = center.add(basis.u().scale(Math.cos(t1) * radius)).add(basis.v().scale(Math.sin(t1) * radius));
            emitQuadDoubleSided(vc, mat, center, outer0, outer1, center, r, g, b, a);
        }
    }

    private static void drawRing(
        VertexConsumer vc,
        Matrix4f mat,
        Vec3 center,
        Basis basis,
        double inner,
        double outer,
        float r,
        float g,
        float b,
        float a
    ) {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double t0 = (Math.PI * 2.0) * i / CIRCLE_SEGMENTS;
            double t1 = (Math.PI * 2.0) * (i + 1) / CIRCLE_SEGMENTS;

            Vec3 in0 = center.add(basis.u().scale(Math.cos(t0) * inner)).add(basis.v().scale(Math.sin(t0) * inner));
            Vec3 out0 = center.add(basis.u().scale(Math.cos(t0) * outer)).add(basis.v().scale(Math.sin(t0) * outer));
            Vec3 out1 = center.add(basis.u().scale(Math.cos(t1) * outer)).add(basis.v().scale(Math.sin(t1) * outer));
            Vec3 in1 = center.add(basis.u().scale(Math.cos(t1) * inner)).add(basis.v().scale(Math.sin(t1) * inner));

            emitQuadDoubleSided(vc, mat, in0, out0, out1, in1, r, g, b, a);
        }
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
        double radius = BASE_RADIUS + (state.patterns.size() * RADIUS_PER_PATTERN);
        Random rng = new Random(id ^ (state.lastUpdateTick * 31L));

        int ringBursts = 92;
        for (int i = 0; i < ringBursts; i++) {
            double t = (Math.PI * 2.0) * i / ringBursts;
            double radialJitter = (rng.nextDouble() - 0.5) * 0.24;
            Vec3 radial = basis.u().scale(Math.cos(t)).add(basis.v().scale(Math.sin(t))).normalize();
            Vec3 spawn = state.origin
                .add(radial.scale(radius + radialJitter))
                .add(state.normal.scale((rng.nextDouble() - 0.5) * 0.08));

            Vec3 vel = radial.scale(0.05 + rng.nextDouble() * 0.06)
                .add(state.normal.scale((rng.nextDouble() - 0.5) * 0.03));

            mc.level.addParticle(ParticleTypes.ENCHANT, spawn.x, spawn.y, spawn.z, vel.x, vel.y, vel.z);
            if ((i & 3) == 0) {
                mc.level.addParticle(ParticleTypes.END_ROD, spawn.x, spawn.y, spawn.z, vel.x * 0.6, vel.y * 0.6, vel.z * 0.6);
            }
        }

        for (HexPattern pattern : state.patterns) {
            List<Vec3> points = patternPointsInPlane(pattern, state.origin, basis, 0.16f);
            for (Vec3 p : points) {
                Vec3 jitter = basis.u().scale((rng.nextDouble() - 0.5) * 0.04)
                    .add(basis.v().scale((rng.nextDouble() - 0.5) * 0.04));
                Vec3 spawn = p.add(jitter);
                mc.level.addParticle(
                    ParticleTypes.ENCHANT,
                    spawn.x,
                    spawn.y,
                    spawn.z,
                    (rng.nextDouble() - 0.5) * 0.03,
                    (rng.nextDouble() - 0.5) * 0.03,
                    (rng.nextDouble() - 0.5) * 0.03
                );
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

    private record CircleKey(String dimensionId, long id) {
    }

    private static final class CircleState {
        String dimensionId = "";
        Vec3 origin = Vec3.ZERO;
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        List<HexPattern> patterns = List.of();
        int initialLifetimeTicks = 1;
        int remainingTicks = 1;
        long lastUpdateTick;
    }

    private record Basis(Vec3 u, Vec3 v, Vec3 n) {
    }
}
