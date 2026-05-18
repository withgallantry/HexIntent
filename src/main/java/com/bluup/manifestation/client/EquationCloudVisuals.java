package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.common.equation.EquationParticleConfig;
import com.bluup.manifestation.common.equation.EquationParticleGenerator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class EquationCloudVisuals {
    private static final Map<CloudKey, CloudState> ACTIVE = new HashMap<>();

    private static final int CLOUD_TTL_TICKS = 45;
    private static final int MOVE_TICKS = 8;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.EQUATION_CLOUD_S2C,
            (client, handler, buf, responseSender) -> {
                String dimensionId = buf.readUtf();
                UUID sourceId = buf.readUUID();
                long id = buf.readLong();
                Vec3 origin = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());

                String x = buf.readUtf(EquationParticleConfig.MAX_EXPR_CHARS);
                String y = buf.readUtf(EquationParticleConfig.MAX_EXPR_CHARS);
                String z = buf.readUtf(EquationParticleConfig.MAX_EXPR_CHARS);
                double tMin = buf.readDouble();
                double tMax = buf.readDouble();
                double uMin = buf.readDouble();
                double uMax = buf.readDouble();
                boolean useU = buf.readBoolean();
                int pointCount = buf.readVarInt();

                String colorMode = buf.readUtf(32);
                double fixedR = buf.readDouble();
                double fixedG = buf.readDouble();
                double fixedB = buf.readDouble();
                double gradStartR = buf.readDouble();
                double gradStartG = buf.readDouble();
                double gradStartB = buf.readDouble();
                double gradEndR = buf.readDouble();
                double gradEndG = buf.readDouble();
                double gradEndB = buf.readDouble();
                String colorExprR = buf.readUtf(EquationParticleConfig.MAX_EXPR_CHARS);
                String colorExprG = buf.readUtf(EquationParticleConfig.MAX_EXPR_CHARS);
                String colorExprB = buf.readUtf(EquationParticleConfig.MAX_EXPR_CHARS);

                EquationParticleConfig config = new EquationParticleConfig(
                    x,
                    y,
                    z,
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
                    gradStartR,
                    gradStartG,
                    gradStartB,
                    gradEndR,
                    gradEndG,
                    gradEndB,
                    colorExprR,
                    colorExprG,
                    colorExprB
                ).normalized();

                client.execute(() -> {
                    if (client.level == null) {
                        return;
                    }
                    long now = client.level.getGameTime();
                    CloudKey key = new CloudKey(sourceId, id);
                    CloudState state = ACTIVE.computeIfAbsent(key, ignored -> new CloudState());
                    state.applyUpdate(dimensionId, origin, config, now);
                });
            }
        );

        ClientTickEvents.END_CLIENT_TICK.register(EquationCloudVisuals::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> render(Minecraft.getInstance(), context.matrixStack()));
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.isPaused() || ACTIVE.isEmpty()) {
            return;
        }

        long now = mc.level.getGameTime();
        ACTIVE.entrySet().removeIf(entry -> now - entry.getValue().lastUpdateTick > CLOUD_TTL_TICKS);
    }

    private static void render(Minecraft mc, PoseStack poseStack) {
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) {
            return;
        }

        String currentDimension = mc.level.dimension().location().toString();
        long now = mc.level.getGameTime();

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        var lookF = mc.gameRenderer.getMainCamera().getLookVector();
        Vec3 look = new Vec3(lookF.x(), lookF.y(), lookF.z()).normalize();
        Vec3 right = look.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0e-8) {
            right = new Vec3(1.0, 0.0, 0.0);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(look).normalize();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lineBuffer = buffers.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        try {
            for (CloudState state : ACTIVE.values()) {
                if (!currentDimension.equals(state.dimensionId)) {
                    continue;
                }
                if (state.points.isEmpty()) {
                    continue;
                }

                Vec3 origin = state.currentOrigin(now);
                double dist = origin.distanceTo(camera);
                int step = computeSampleStep(state.points.size(), dist);
                int phase = (int) (now % step);
                double pointHalfSize = computePointHalfSize(dist);
                float alpha = computeAlpha(dist);

                for (int i = 0; i < state.points.size(); i++) {
                    if ((i + phase) % step != 0) {
                        continue;
                    }
                    EquationParticleGenerator.GeneratedPoint point = state.points.get(i);
                    Vec3 p = origin.add(point.offset());
                    Vec3 c = point.color();

                    Vec3 rx = right.scale(pointHalfSize);
                    Vec3 uy = up.scale(pointHalfSize);
                    drawLineSegment(poseStack, lineBuffer, p.subtract(rx), p.add(rx), (float) c.x, (float) c.y, (float) c.z, alpha);
                    drawLineSegment(poseStack, lineBuffer, p.subtract(uy), p.add(uy), (float) c.x, (float) c.y, (float) c.z, alpha);
                }
            }
        } finally {
            poseStack.popPose();
            buffers.endBatch(RenderType.lines());
        }
    }

    private static int computeSampleStep(int pointCount, double distance) {
        int targetVisible = 900;
        int step = Math.max(1, (pointCount + targetVisible - 1) / targetVisible);
        if (distance > 24.0) {
            step *= 2;
        }
        if (distance > 48.0) {
            step *= 2;
        }
        return Math.max(1, step);
    }

    private static double computePointHalfSize(double distance) {
        if (distance < 12.0) {
            return 0.032;
        }
        if (distance < 28.0) {
            return 0.024;
        }
        return 0.018;
    }

    private static float computeAlpha(double distance) {
        if (distance < 20.0) {
            return 0.92f;
        }
        if (distance < 42.0) {
            return 0.75f;
        }
        return 0.58f;
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

    private static final class CloudState {
        String dimensionId = "";
        EquationParticleConfig config;
        long configFingerprint;
        List<EquationParticleGenerator.GeneratedPoint> points = List.of();

        Vec3 fromOrigin = Vec3.ZERO;
        Vec3 toOrigin = Vec3.ZERO;
        long moveStartTick;
        int moveTicks = MOVE_TICKS;

        long lastUpdateTick;
        boolean initialized;

        void applyUpdate(String dimensionId, Vec3 newOrigin, EquationParticleConfig newConfig, long now) {
            long fp = fingerprint(newConfig);
            boolean sameShape = initialized
                && Objects.equals(this.dimensionId, dimensionId)
                && this.configFingerprint == fp;

            if (!initialized || !Objects.equals(this.dimensionId, dimensionId)) {
                this.fromOrigin = newOrigin;
                this.toOrigin = newOrigin;
                this.moveStartTick = now;
                this.moveTicks = MOVE_TICKS;
                this.initialized = true;
            } else if (sameShape) {
                Vec3 start = currentOrigin(now);
                this.fromOrigin = start;
                this.toOrigin = newOrigin;
                this.moveStartTick = now;
                this.moveTicks = MOVE_TICKS;
            } else {
                this.fromOrigin = newOrigin;
                this.toOrigin = newOrigin;
                this.moveStartTick = now;
                this.moveTicks = 1;
            }

            if (!sameShape) {
                try {
                    this.points = EquationParticleGenerator.generate(
                        newConfig,
                        ManifestationClientLimits.MAX_EQUATION_POINTS_RENDER,
                        ManifestationClientLimits.MAX_EQUATION_EVAL_BUDGET_RENDER
                    );
                } catch (IllegalArgumentException ex) {
                    this.points = List.of();
                }
            }

            this.config = newConfig;
            this.configFingerprint = fp;
            this.dimensionId = dimensionId;
            this.lastUpdateTick = now;
        }

        Vec3 currentOrigin(long now) {
            if (moveTicks <= 1) {
                return toOrigin;
            }
            float alpha = Mth.clamp((float) (now - moveStartTick) / (float) moveTicks, 0.0f, 1.0f);
            return new Vec3(
                Mth.lerp(alpha, fromOrigin.x, toOrigin.x),
                Mth.lerp(alpha, fromOrigin.y, toOrigin.y),
                Mth.lerp(alpha, fromOrigin.z, toOrigin.z)
            );
        }

        private static long fingerprint(EquationParticleConfig config) {
            return Objects.hash(
                config.xExpr(),
                config.yExpr(),
                config.zExpr(),
                config.tMin(),
                config.tMax(),
                config.uMin(),
                config.uMax(),
                config.useU(),
                config.pointCount(),
                config.colorMode(),
                config.fixedR(),
                config.fixedG(),
                config.fixedB(),
                config.gradientStartR(),
                config.gradientStartG(),
                config.gradientStartB(),
                config.gradientEndR(),
                config.gradientEndG(),
                config.gradientEndB(),
                config.colorExprR(),
                config.colorExprG(),
                config.colorExprB()
            );
        }
    }

    private record CloudKey(UUID sourceId, long id) {
    }

    private EquationCloudVisuals() {
    }
}
