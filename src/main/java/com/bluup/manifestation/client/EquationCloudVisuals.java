package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.common.equation.EquationParticleConfig;
import com.bluup.manifestation.common.equation.EquationParticleGenerator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class EquationCloudVisuals {
    private static final Map<CloudKey, CloudState> ACTIVE = new HashMap<>();

    private static final int CLOUD_TTL_TICKS = 45;
    private static final int MOVE_TICKS = 8;
    private static final float BASE_POINT_HALF = 0.017f;
    private static final float DRIFT_RADIUS = 0.016f;
    private static final float GLOW_SCALE = 1.9f;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.EQUATION_CLOUD_S2C,
            (client, handler, buf, responseSender) -> {
                String dimensionId = buf.readUtf();
                UUID sourceId = buf.readUUID();
                long id = buf.readLong();
                Vec3 origin = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                boolean hasFollowEntity = buf.readBoolean();
                Integer followEntityId = null;
                Vec3 followOffset = null;
                if (hasFollowEntity) {
                    followEntityId = buf.readVarInt();
                    followOffset = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                }
                final Integer resolvedFollowEntityId = followEntityId;
                final Vec3 resolvedFollowOffset = followOffset;

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
                    state.applyUpdate(dimensionId, origin, config, resolvedFollowEntityId, resolvedFollowOffset, now);
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
        float animTime = now;

        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Camera mainCamera = mc.gameRenderer.getMainCamera();
        Quaternionf cameraRotation = mainCamera.rotation();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer sparkBuffer = buffers.getBuffer(RenderType.lightning());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        try {
            Matrix4f mat = poseStack.last().pose();
            for (CloudState state : ACTIVE.values()) {
                if (!currentDimension.equals(state.dimensionId)) {
                    continue;
                }
                if (state.points.isEmpty()) {
                    continue;
                }

                Vec3 origin = state.currentOrigin(now, mc, mc.getFrameTime());
                double dist = origin.distanceTo(camera);
                int step = computeSampleStep(state.points.size(), dist);
                int phase = (int) (now % step);
                float baseSize = computePointHalfSize(dist);
                float alpha = computeAlpha(dist);

                for (int i = 0; i < state.points.size(); i++) {
                    if ((i + phase) % step != 0) {
                        continue;
                    }
                    EquationParticleGenerator.GeneratedPoint point = state.points.get(i);
                    Vec3 p = origin.add(point.offset());
                    Vec3 c = point.color();

                    float x = (float) p.x;
                    float y = (float) p.y;
                    float z = (float) p.z;
                    float r = Mth.clamp((float) c.x, 0.0f, 1.0f);
                    float g = Mth.clamp((float) c.y, 0.0f, 1.0f);
                    float b = Mth.clamp((float) c.z, 0.0f, 1.0f);

                    float t = (animTime * 0.08f) + (i * 0.019f);
                    float shimmer = 0.65f + (0.35f * Mth.sin((animTime * 0.23f) + (i * 0.81f)));
                    float twinkle = 0.55f + (0.45f * Mth.sin((animTime * 0.34f) + (i * 1.57f)));

                    float ox = (float) point.offset().x;
                    float oy = (float) point.offset().y;
                    float oz = (float) point.offset().z;
                    float lenSq = (ox * ox) + (oy * oy) + (oz * oz);
                    if (lenSq > 1.0e-6f) {
                        float invLen = Mth.invSqrt(lenSq);
                        float drift = DRIFT_RADIUS * (0.45f + (0.55f * Mth.sin(t + (i * 0.29f))));
                        x += ox * invLen * drift;
                        y += oy * invLen * drift;
                        z += oz * invLen * drift;
                    }

                    x += Mth.sin((animTime * 0.11f) + (i * 0.26f)) * 0.005f;
                    z += Mth.cos((animTime * 0.10f) + (i * 0.28f)) * 0.005f;

                    float size = baseSize * (0.65f + (0.35f * shimmer));
                    float coreAlpha = alpha * (0.7f + (0.3f * twinkle));
                    float glowAlpha = alpha * 0.25f * (0.75f + (0.25f * shimmer));

                    addBillboardQuad(sparkBuffer, mat, cameraRotation, x, y, z, size * GLOW_SCALE, r, g, b, glowAlpha);
                    addBillboardQuad(sparkBuffer, mat, cameraRotation, x, y, z, size, r, g, b, coreAlpha);
                }
            }
        } finally {
            poseStack.popPose();
            buffers.endBatch(RenderType.lightning());
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

    private static float computePointHalfSize(double distance) {
        if (distance < 12.0) {
            return BASE_POINT_HALF * 1.45f;
        }
        if (distance < 28.0) {
            return BASE_POINT_HALF * 1.10f;
        }
        return BASE_POINT_HALF * 0.88f;
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

    private static void addBillboardQuad(
        VertexConsumer vc,
        Matrix4f mat,
        Quaternionf cameraRotation,
        float x,
        float y,
        float z,
        float s,
        float r,
        float g,
        float b,
        float alpha
    ) {
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f).rotate(cameraRotation).mul(s);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f).rotate(cameraRotation).mul(s);

        float x0 = x - right.x - up.x;
        float y0 = y - right.y - up.y;
        float z0 = z - right.z - up.z;

        float x1 = x + right.x - up.x;
        float y1 = y + right.y - up.y;
        float z1 = z + right.z - up.z;

        float x2 = x + right.x + up.x;
        float y2 = y + right.y + up.y;
        float z2 = z + right.z + up.z;

        float x3 = x - right.x + up.x;
        float y3 = y - right.y + up.y;
        float z3 = z - right.z + up.z;

        vc.vertex(mat, x0, y0, z0)
            .color(r, g, b, alpha)
            .endVertex();
        vc.vertex(mat, x1, y1, z1)
            .color(r, g, b, alpha)
            .endVertex();
        vc.vertex(mat, x2, y2, z2)
            .color(r, g, b, alpha)
            .endVertex();
        vc.vertex(mat, x3, y3, z3)
            .color(r, g, b, alpha)
            .endVertex();

        vc.vertex(mat, x3, y3, z3)
            .color(r, g, b, alpha)
            .endVertex();
        vc.vertex(mat, x2, y2, z2)
            .color(r, g, b, alpha)
            .endVertex();
        vc.vertex(mat, x1, y1, z1)
            .color(r, g, b, alpha)
            .endVertex();
        vc.vertex(mat, x0, y0, z0)
            .color(r, g, b, alpha)
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
        Integer followEntityId;
        Vec3 followOffset = Vec3.ZERO;

        long lastUpdateTick;
        boolean initialized;

        void applyUpdate(
            String dimensionId,
            Vec3 newOrigin,
            EquationParticleConfig newConfig,
            Integer followEntityId,
            Vec3 followOffset,
            long now
        ) {
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
                Vec3 start = currentOriginFromSnapshots(now);
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
            this.followEntityId = followEntityId;
            this.followOffset = followOffset == null ? Vec3.ZERO : followOffset;
            this.lastUpdateTick = now;
        }

        Vec3 currentOrigin(long now, Minecraft mc, float partialTick) {
            if (followEntityId != null && mc.level != null) {
                Entity followed = mc.level.getEntity(followEntityId);
                if (followed != null) {
                    Vec3 tracked = followed.getPosition(partialTick)
                        .add(0.0, followed.getBbHeight() * 0.5, 0.0)
                        .add(followOffset);
                    fromOrigin = tracked;
                    toOrigin = tracked;
                    moveStartTick = now;
                    moveTicks = MOVE_TICKS;
                    return tracked;
                }
            }


            return currentOriginFromSnapshots(now);
        }

        private Vec3 currentOriginFromSnapshots(long now) {
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
