package com.bluup.manifestation.client;

import at.petrak.hexcasting.common.particles.ConjureParticleOptions;
import com.bluup.manifestation.common.ManifestationNetworking;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.ArrayDeque;
import java.util.Deque;

public final class HexTrailVisuals {
    private static final Map<Long, TrailState> ACTIVE = new HashMap<>();

    private static final double MAX_SEGMENT_GAP_SQ = 10.0 * 10.0;
    private static final int TRAIL_TTL_TICKS = 40;
    private static final int SEGMENT_LIFETIME_TICKS = 2;
    private static final double MIN_SEGMENT_LENGTH = 0.001;
    private static final int LINE_HELIX_STEPS = 24;
    private static final int LINE_LIGHTNING_STEPS = 12;
    private static final int LINE_TRAIL_FADE_TICKS = 10;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.HEX_TRAIL_S2C,
            (client, handler, buf, responseSender) -> {
                String dimensionId = buf.readUtf();
                long id = buf.readLong();
                Vec3 position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                Vec3 colorStart = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
                Vec3 colorEnd = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
                int transitionTicks = Math.max(1, buf.readVarInt());
                int particleType = Mth.clamp(buf.readVarInt(), 0, 14);

                client.execute(() -> {
                    long now = client.level != null ? client.level.getGameTime() : 0L;
                    TrailState state = ACTIVE.computeIfAbsent(id, ignored -> new TrailState());
                    state.applyUpdate(dimensionId, position, colorStart, colorEnd, transitionTicks, particleType, now);
                });
            }
        );

        ClientTickEvents.END_CLIENT_TICK.register(HexTrailVisuals::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> renderLineTrails(Minecraft.getInstance(), context.matrixStack()));
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.isPaused() || ACTIVE.isEmpty()) {
            return;
        }

        long now = mc.level.getGameTime();
        String currentDimension = mc.level.dimension().location().toString();

        Iterator<Map.Entry<Long, TrailState>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, TrailState> entry = it.next();
            long trailId = entry.getKey();
            TrailState state = entry.getValue();

            if (now - state.lastUpdateTick > TRAIL_TTL_TICKS) {
                it.remove();
                continue;
            }

            if (!currentDimension.equals(state.dimensionId)) {
                continue;
            }

            if (state.segmentFrom == null || state.segmentTo == null) {
                continue;
            }

            spawnSegment(mc, trailId, state, now);
            state.segmentAgeTicks++;
            if (state.segmentAgeTicks >= SEGMENT_LIFETIME_TICKS) {
                state.segmentFrom = null;
                state.segmentTo = null;
                state.segmentAgeTicks = 0;
            }
        }
    }

    private static void spawnSegment(Minecraft mc, long trailId, TrailState state, long now) {
        Vec3 from = state.segmentFrom;
        Vec3 to = state.segmentTo;
        double dist = from.distanceTo(to);
        if (dist < MIN_SEGMENT_LENGTH) {
            return;
        }

        if (isLineTrailType(state.particleType)) {
            return;
        }

        Style style = Style.fromType(state.particleType);
        int count = Mth.clamp((int) Math.ceil(dist * style.densityPerBlock) + style.baseCount, style.minCount, style.maxCount);

        Vec3 tangent = to.subtract(from).normalize();
        Vec3 normal = orthogonal(tangent);
        Vec3 binormal = tangent.cross(normal).normalize();

        for (int i = 0; i < count; i++) {
            double t = count <= 1 ? 1.0 : (double) i / (double) (count - 1);
            double x = Mth.lerp(t, from.x, to.x);
            double y = Mth.lerp(t, from.y, to.y);
            double z = Mth.lerp(t, from.z, to.z);

            Random rng = new Random(mixSeed(trailId, now, i));
            double jitterX = (rng.nextDouble() - 0.5) * style.jitter;
            double jitterY = (rng.nextDouble() - 0.5) * style.jitter;
            double jitterZ = (rng.nextDouble() - 0.5) * style.jitter;

            float blend = pingPong(now, state.transitionTicks);
            float r = (float) Mth.lerp(blend, state.colorStart.x, state.colorEnd.x);
            float g = (float) Mth.lerp(blend, state.colorStart.y, state.colorEnd.y);
            float b = (float) Mth.lerp(blend, state.colorStart.z, state.colorEnd.z);
            ParticleOptions untinted = style.particle;

            if (style.mode == RenderMode.SPARSE_SPARK) {
                double gate = Math.max(0.22, 0.52 - (dist * 0.04));
                if (rng.nextDouble() > gate) {
                    continue;
                }
                float sparkScale = style.scale * (0.85f + (float) rng.nextDouble() * 0.85f);
                ParticleOptions particle = makeParticle(style, untinted, r, g, b, sparkScale);
                double tangentPush = style.velocity * (0.35 + rng.nextDouble() * 0.65);
                double spread = style.velocity * 0.9;
                double vx = tangent.x * tangentPush + (rng.nextDouble() - 0.5) * spread;
                double vy = tangent.y * tangentPush + (rng.nextDouble() - 0.5) * spread;
                double vz = tangent.z * tangentPush + (rng.nextDouble() - 0.5) * spread;
                mc.level.addParticle(particle, x + jitterX, y + jitterY, z + jitterZ, vx, vy, vz);
                continue;
            }

            if (style.mode == RenderMode.RIBBON) {
                double phase = t * Math.PI * 2.0 + (now * 0.22);
                Vec3 radial = normal.scale(Math.cos(phase)).add(binormal.scale(Math.sin(phase)));
                double offset = style.ringRadius;
                Vec3 swirlPos = new Vec3(x, y, z).add(radial.scale(offset));
                ParticleOptions particle = makeParticle(style, untinted, r, g, b, style.scale);
                mc.level.addParticle(particle, swirlPos.x, swirlPos.y, swirlPos.z, 0.0, 0.0, 0.0);
                continue;
            }

            if (style.mode == RenderMode.PULSE_BEADS) {
                if (i % 2 != 0) {
                    continue;
                }
                double phase = (now * 0.18) + (i * 0.42);
                double radialSign = ((i / 2) % 2 == 0) ? 1.0 : -1.0;
                Vec3 beadOffset = normal.scale(style.ringRadius * radialSign * Math.sin(phase));
                float pulseScale = (float) (0.85 + 0.45 * (0.5 + 0.5 * Math.sin(phase + t * 4.0)));
                ParticleOptions particle = makeParticle(style, untinted, r, g, b, style.scale * pulseScale);
                Vec3 beadPos = new Vec3(x, y, z).add(beadOffset);
                mc.level.addParticle(particle, beadPos.x, beadPos.y, beadPos.z, 0.0, 0.0, 0.0);
                continue;
            }

            if (style.mode == RenderMode.SOLID_BEAM) {
                // Thick centerline plus a subtle rotating halo so it reads as a beam, not a trail.
                ParticleOptions core = makeParticle(style, untinted, r, g, b, style.scale);
                mc.level.addParticle(core, x, y, z, 0.0, 0.0, 0.0);

                double phase = (now * 0.36) + (t * Math.PI * 6.0);
                Vec3 haloOffset = normal.scale(Math.cos(phase)).add(binormal.scale(Math.sin(phase))).scale(style.ringRadius);
                ParticleOptions halo = makeParticle(style, untinted, r, g, b, style.scale * 0.72f);
                Vec3 haloPos = new Vec3(x, y, z).add(haloOffset);
                mc.level.addParticle(halo, haloPos.x, haloPos.y, haloPos.z, 0.0, 0.0, 0.0);
                continue;
            }

            if (style.mode == RenderMode.DUAL_HELIX) {
                double phase = (t * Math.PI * 14.0) + (now * 0.42);
                Vec3 radialA = normal.scale(Math.cos(phase)).add(binormal.scale(Math.sin(phase))).scale(style.ringRadius);
                Vec3 radialB = radialA.scale(-1.0);

                float r2 = (float) Mth.lerp(0.65f, r, 1.0f);
                float g2 = (float) Mth.lerp(0.65f, g, 1.0f);
                float b2 = (float) Mth.lerp(0.65f, b, 1.0f);
                ParticleOptions particleA = makeParticle(style, untinted, r, g, b, style.scale);
                ParticleOptions particleB = makeParticle(style, untinted, r2, g2, b2, style.scale * 0.9f);
                Vec3 helixA = new Vec3(x, y, z).add(radialA);
                Vec3 helixB = new Vec3(x, y, z).add(radialB);
                mc.level.addParticle(particleA, helixA.x, helixA.y, helixA.z, 0.0, 0.0, 0.0);
                mc.level.addParticle(particleB, helixB.x, helixB.y, helixB.z, 0.0, 0.0, 0.0);

                if (i % 3 == 0) {
                    ParticleOptions spine = makeParticle(style, untinted, r, g, b, style.scale * 0.42f);
                    mc.level.addParticle(spine, x, y, z, 0.0, 0.0, 0.0);
                }
                continue;
            }

            if (style.mode == RenderMode.LIGHTNING_ARC) {
                double arcEnvelope = Math.sin(t * Math.PI);
                Vec3 jagged = normal.scale((rng.nextDouble() - 0.5) * style.ringRadius)
                    .add(binormal.scale((rng.nextDouble() - 0.5) * style.ringRadius))
                    .scale(arcEnvelope * 1.95);
                Vec3 arcPos = new Vec3(x, y, z).add(jagged);

                float flash = (float) (0.88 + 0.35 * rng.nextDouble());
                ParticleOptions particle = makeParticle(style, untinted, r, g, b, style.scale * flash);
                double jitterVel = style.velocity * 0.4;
                mc.level.addParticle(
                    particle,
                    arcPos.x,
                    arcPos.y,
                    arcPos.z,
                    (rng.nextDouble() - 0.5) * jitterVel,
                    (rng.nextDouble() - 0.5) * jitterVel,
                    (rng.nextDouble() - 0.5) * jitterVel
                );

                if (i % 2 == 0) {
                    mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, arcPos.x, arcPos.y, arcPos.z, 0.0, 0.0, 0.0);
                }

                if (i % 3 == 0) {
                    ParticleOptions branch = makeParticle(style, untinted, r, g, b, style.scale * 0.65f);
                    Vec3 branchPos = arcPos.add(normal.scale((rng.nextDouble() - 0.5) * style.ringRadius * 0.65));
                    mc.level.addParticle(branch, branchPos.x, branchPos.y, branchPos.z, 0.0, 0.0, 0.0);
                }
                continue;
            }

            // Keep the existing type-2 look intact.
            ParticleOptions particle = makeParticle(style, untinted, r, g, b, style.scale);
            double vx = (rng.nextDouble() - 0.5) * style.velocity;
            double vy = (rng.nextDouble() - 0.5) * style.velocity;
            double vz = (rng.nextDouble() - 0.5) * style.velocity;
            mc.level.addParticle(particle, x + jitterX, y + jitterY, z + jitterZ, vx, vy, vz);
        }
    }

    private static void renderLineTrails(Minecraft mc, PoseStack poseStack) {
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) {
            return;
        }

        long now = mc.level.getGameTime();
        String currentDimension = mc.level.dimension().location().toString();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lineBuffer = buffers.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        try {
            for (Map.Entry<Long, TrailState> entry : ACTIVE.entrySet()) {
                long trailId = entry.getKey();
                TrailState state = entry.getValue();
                if (now - state.lastUpdateTick > TRAIL_TTL_TICKS) {
                    continue;
                }
                if (!currentDimension.equals(state.dimensionId)) {
                    continue;
                }
                if (!isLineTrailType(state.particleType)) {
                    continue;
                }
                state.pruneLineHistory(now);
                if (state.lineHistory.isEmpty()) {
                    continue;
                }

                float blend = pingPong(now, state.transitionTicks);
                float r = (float) Mth.lerp(blend, state.colorStart.x, state.colorEnd.x);
                float g = (float) Mth.lerp(blend, state.colorStart.y, state.colorEnd.y);
                float b = (float) Mth.lerp(blend, state.colorStart.z, state.colorEnd.z);

                for (LineTrailSegment segment : state.lineHistory) {
                    long age = now - segment.createdTick;
                    if (age < 0L || age > LINE_TRAIL_FADE_TICKS) {
                        continue;
                    }
                    float fade = 1.0f - (age / (float) LINE_TRAIL_FADE_TICKS);
                    drawLineTrailByType(poseStack, lineBuffer, trailId, state.particleType, segment.from, segment.to, now, r, g, b, fade);
                }
            }
        } finally {
            poseStack.popPose();
            buffers.endBatch(RenderType.lines());
        }
    }

    private static boolean isLineTrailType(int particleType) {
        return particleType == 8 || particleType == 9 || particleType == 10 || particleType == 14;
    }

    private static void drawLineTrailByType(
        PoseStack poseStack,
        VertexConsumer lineBuffer,
        long trailId,
        int particleType,
        Vec3 from,
        Vec3 to,
        long now,
        float r,
        float g,
        float b,
        float fade
    ) {
        Vec3 tangent = to.subtract(from);
        if (tangent.lengthSqr() <= 1.0e-8) {
            return;
        }

        Vec3 dir = tangent.normalize();
        Vec3 normal = orthogonal(dir);
        Vec3 binormal = dir.cross(normal).normalize();

        if (particleType == 8) {
            drawLineSegment(poseStack, lineBuffer, from, to, r, g, b, 0.95f * fade);

            // Multi-ring offsets fake a thicker beam despite line-width limits.
            double[] radii = {0.020, 0.040, 0.062};
            float[] alphas = {0.78f, 0.56f, 0.34f};
            for (int ring = 0; ring < radii.length; ring++) {
                double radius = radii[ring];
                float ringAlpha = alphas[ring] * fade;
                int spokes = 8;
                for (int s = 0; s < spokes; s++) {
                    double angle = ((Math.PI * 2.0) / spokes) * s;
                    Vec3 radial = normal.scale(Math.cos(angle)).add(binormal.scale(Math.sin(angle))).scale(radius);
                    drawLineSegment(poseStack, lineBuffer, from.add(radial), to.add(radial), r, g, b, ringAlpha);
                }
            }
            return;
        }

        if (particleType == 9) {
            int rings = 8;
            float ringAlpha = 0.88f * fade;
            for (int i = 0; i <= rings; i++) {
                double t = (double) i / (double) rings;
                Vec3 center = lerpVec(from, to, t);
                double radius = 0.16;
                Vec3 prev = null;
                for (int s = 0; s <= 24; s++) {
                    double a = ((Math.PI * 2.0) / 24.0) * s + (now * 0.03);
                    Vec3 p = center
                        .add(normal.scale(Math.cos(a) * radius))
                        .add(binormal.scale(Math.sin(a) * radius));
                    if (prev != null) {
                        drawLineSegment(poseStack, lineBuffer, prev, p, r, g, b, ringAlpha);
                    }
                    prev = p;
                }
            }
            drawLineSegment(poseStack, lineBuffer, from, to, r * 0.74f + 0.26f, g * 0.74f + 0.26f, b * 0.74f + 0.26f, 0.42f * fade);
            return;
        }

        if (particleType == 14) {
            Vec3 prevA = null;
            Vec3 prevB = null;
            double phaseBase = now * 0.40;
            for (int i = 0; i <= LINE_HELIX_STEPS; i++) {
                double t = (double) i / (double) LINE_HELIX_STEPS;
                Vec3 center = lerpVec(from, to, t);
                double phase = phaseBase + t * Math.PI * 6.0;
                Vec3 radial = normal.scale(Math.cos(phase)).add(binormal.scale(Math.sin(phase))).scale(0.20);
                Vec3 a = center.add(radial);
                Vec3 b2 = center.subtract(radial);
                if (prevA != null) {
                    drawLineSegment(poseStack, lineBuffer, prevA, a, r, g, b, 0.92f * fade);
                    drawLineSegment(poseStack, lineBuffer, prevB, b2, r * 0.78f + 0.22f, g * 0.78f + 0.22f, b * 0.78f + 0.22f, 0.84f * fade);
                }
                if (i % 3 == 0) {
                    drawLineSegment(poseStack, lineBuffer, a, b2, r, g, b, 0.20f * fade);
                }
                prevA = a;
                prevB = b2;
            }
            return;
        }

        if (particleType == 10) {
            Vec3 prev = from;
            for (int i = 1; i <= LINE_LIGHTNING_STEPS; i++) {
                double t = (double) i / (double) LINE_LIGHTNING_STEPS;
                Vec3 center = lerpVec(from, to, t);
                double envelope = Math.sin(t * Math.PI);
                Random rng = new Random(mixSeed(trailId, now / 2L, i));
                Vec3 jag = normal.scale((rng.nextDouble() - 0.5) * 0.42)
                    .add(binormal.scale((rng.nextDouble() - 0.5) * 0.42))
                    .scale(envelope);
                Vec3 point = center.add(jag);

                drawLineSegment(poseStack, lineBuffer, prev, point, r, g, b, 0.96f * fade);

                if (i % 3 == 0) {
                    Vec3 branch = point.add(normal.scale((rng.nextDouble() - 0.5) * 0.33));
                    drawLineSegment(poseStack, lineBuffer, point, branch, r, g, b, 0.62f * fade);
                }
                prev = point;
            }
        }
    }

    private static Vec3 lerpVec(Vec3 a, Vec3 b, double t) {
        return new Vec3(
            Mth.lerp(t, a.x, b.x),
            Mth.lerp(t, a.y, b.y),
            Mth.lerp(t, a.z, b.z)
        );
    }

    private static void drawLineSegment(
        PoseStack poseStack,
        VertexConsumer lineBuffer,
        Vec3 from,
        Vec3 to,
        float r,
        float g,
        float b,
        float alpha
    ) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len <= 1.0e-8) {
            return;
        }

        Vec3 normal = delta.scale(1.0 / len);
        var pose = poseStack.last();
        lineBuffer.vertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z)
            .color(r, g, b, alpha)
            .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z)
            .endVertex();
        lineBuffer.vertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z)
            .color(r, g, b, alpha)
            .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z)
            .endVertex();
    }

    private static ParticleOptions makeParticle(Style style, ParticleOptions untinted, float r, float g, float b, float scale) {
        if (untinted != null) {
            return untinted;
        }
        if (style.useConjureColor) {
            return new ConjureParticleOptions(packColor(r, g, b));
        }
        return new DustParticleOptions(new Vector3f(r, g, b), scale);
    }

    private static int packColor(float r, float g, float b) {
        int rr = Mth.clamp((int) (r * 255.0f), 0, 255);
        int gg = Mth.clamp((int) (g * 255.0f), 0, 255);
        int bb = Mth.clamp((int) (b * 255.0f), 0, 255);
        return (rr << 16) | (gg << 8) | bb;
    }

    private static Vec3 orthogonal(Vec3 v) {
        if (Math.abs(v.y) < 0.95) {
            return v.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
        }
        return v.cross(new Vec3(1.0, 0.0, 0.0)).normalize();
    }

    private static float pingPong(long now, int transitionTicks) {
        int t = Math.max(1, transitionTicks);
        int cycle = t * 2;
        int phase = Math.floorMod((int) (now % cycle), cycle);
        if (phase <= t) {
            return (float) phase / (float) t;
        }
        return (float) (cycle - phase) / (float) t;
    }

    private static long mixSeed(long trailId, long now, int index) {
        long x = trailId * 0x9E3779B97F4A7C15L;
        x ^= now * 0xC2B2AE3D27D4EB4FL;
        x ^= ((long) index + 1L) * 0x165667B19E3779F9L;
        return x;
    }

    private static final class TrailState {
        private String dimensionId = "";
        private Vec3 currentPos = Vec3.ZERO;
        private Vec3 segmentFrom;
        private Vec3 segmentTo;
        private int segmentAgeTicks;

        private Vec3 colorStart = new Vec3(0.48, 0.86, 1.0);
        private Vec3 colorEnd = new Vec3(0.20, 0.64, 1.0);
        private int transitionTicks = 10;
        private int particleType;
        private long lastUpdateTick;
        private boolean initialized;
        private final Deque<LineTrailSegment> lineHistory = new ArrayDeque<>();

        private void applyUpdate(
            String dimensionId,
            Vec3 newPos,
            Vec3 colorStart,
            Vec3 colorEnd,
            int transitionTicks,
            int particleType,
            long now
        ) {
            if (!initialized || !this.dimensionId.equals(dimensionId)) {
                this.segmentFrom = null;
                this.segmentTo = null;
                this.segmentAgeTicks = 0;
                this.currentPos = newPos;
                this.lineHistory.clear();
                this.initialized = true;
            } else {
                if (this.currentPos.distanceToSqr(newPos) <= MAX_SEGMENT_GAP_SQ) {
                    this.segmentFrom = this.currentPos;
                    this.segmentTo = newPos;
                    this.segmentAgeTicks = 0;
                    if (isLineTrailType(particleType)) {
                        this.lineHistory.addLast(new LineTrailSegment(this.segmentFrom, this.segmentTo, now));
                        pruneLineHistory(now);
                    }
                } else {
                    this.segmentFrom = null;
                    this.segmentTo = null;
                    this.segmentAgeTicks = 0;
                }
                this.currentPos = newPos;
            }

            this.dimensionId = dimensionId;
            this.colorStart = colorStart;
            this.colorEnd = colorEnd;
            this.transitionTicks = Math.max(1, transitionTicks);
            this.particleType = Mth.clamp(particleType, 0, 14);
            this.lastUpdateTick = now;
        }

        private void pruneLineHistory(long now) {
            while (!lineHistory.isEmpty()) {
                LineTrailSegment head = lineHistory.peekFirst();
                if (head == null || now - head.createdTick <= LINE_TRAIL_FADE_TICKS) {
                    break;
                }
                lineHistory.removeFirst();
            }
        }
    }

    private record LineTrailSegment(Vec3 from, Vec3 to, long createdTick) {
    }

    private enum RenderMode {
        RIBBON,
        SPARSE_SPARK,
        PULSE_BEADS,
        SOLID_BEAM,
        DUAL_HELIX,
        LIGHTNING_ARC,
        CLASSIC_TRAIL
    }

    private record Style(
        int baseCount,
        int minCount,
        int maxCount,
        double densityPerBlock,
        double jitter,
        double velocity,
        float scale,
        double ringRadius,
        ParticleOptions particle,
        boolean useConjureColor,
        RenderMode mode
    ) {
        private static Style fromType(int type) {
            return switch (type) {
                case 1 -> new Style(4, 3, 48, 7.0, 0.028, 0.040, 0.90f, 0.0, null, false, RenderMode.SPARSE_SPARK); // hex_spark
                // Preserve existing visuals for option 2.
                case 2 -> new Style(7, 4, 64, 9.0, 0.018, 0.001, 1.35f, 0.0, null, false, RenderMode.CLASSIC_TRAIL); // hex_trail
                case 3 -> new Style(6, 6, 72, 10.0, 0.004, 0.000, 1.55f, 0.045, null, false, RenderMode.PULSE_BEADS); // hex_beads
                case 4 -> new Style(4, 4, 50, 8.0, 0.010, 0.002, 1.00f, 0.0, ParticleTypes.END_ROD, false, RenderMode.CLASSIC_TRAIL); // end_rod
                case 5 -> new Style(8, 8, 78, 12.0, 0.022, 0.000, 1.00f, 0.035, ParticleTypes.WITCH, false, RenderMode.RIBBON); // witch plume
                case 6 -> new Style(6, 5, 72, 11.0, 0.012, 0.010, 1.00f, 0.0, ParticleTypes.ENCHANT, false, RenderMode.SPARSE_SPARK); // enchant fleck
                case 7 -> new Style(7, 6, 86, 14.0, 0.006, 0.000, 1.00f, 0.050, ParticleTypes.SOUL_FIRE_FLAME, false, RenderMode.PULSE_BEADS); // soul flame beads
                case 8 -> new Style(18, 16, 144, 28.0, 0.000, 0.000, 1.65f, 0.045, null, false, RenderMode.SOLID_BEAM); // solid beam
                case 9 -> new Style(9, 9, 108, 14.0, 0.000, 0.000, 1.05f, 0.175, null, false, RenderMode.DUAL_HELIX); // ring lattice
                case 10 -> new Style(8, 8, 88, 11.0, 0.004, 0.030, 0.96f, 0.23, null, false, RenderMode.LIGHTNING_ARC); // lightning arc
                case 11 -> new Style(8, 8, 84, 13.0, 0.004, 0.000, 1.05f, 0.030, null, true, RenderMode.RIBBON); // hex conjure ribbon
                case 12 -> new Style(5, 4, 56, 8.0, 0.020, 0.030, 0.95f, 0.0, null, true, RenderMode.SPARSE_SPARK); // hex conjure sparks
                case 13 -> new Style(7, 5, 70, 10.0, 0.014, 0.001, 1.25f, 0.0, null, true, RenderMode.CLASSIC_TRAIL); // hex conjure trail
                case 14 -> new Style(9, 9, 108, 14.0, 0.000, 0.000, 1.05f, 0.20, null, false, RenderMode.DUAL_HELIX); // dual-helix
                default -> new Style(8, 8, 84, 15.0, 0.003, 0.000, 1.15f, 0.030, null, false, RenderMode.RIBBON); // hex_dust
            };
        }
    }

    private HexTrailVisuals() {
    }
}
