package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.common.equation.EquationParticleConfig;
import com.bluup.manifestation.common.equation.EquationParticleGenerator;
import com.bluup.manifestation.client.ManifestationClientLimits;
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

    private static final double ORIGIN_EPSILON_SQ = 1.0e-6;
    private static final int DEFAULT_DURATION_TICKS = 100;
    private static final float FADE_IN_TICKS = 10.0f;
    private static final float FADE_OUT_TICKS = 10.0f;
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

                var configTag = buf.readNbt();
                if (configTag == null) {
                    return;
                }
                EquationParticleConfig config = EquationParticleConfig.fromNbt(configTag).normalized();
                String animationPreset = buf.readableBytes() > 0 ? buf.readUtf(32) : "rotate";
                double animationSpeed = buf.readableBytes() > 0 ? buf.readDouble() : 1.0;
                int durationTicks = buf.readableBytes() > 0 ? buf.readVarInt() : DEFAULT_DURATION_TICKS;

                client.execute(() -> {
                    if (client.level == null) {
                        return;
                    }
                    long now = client.level.getGameTime();
                    CloudKey key = new CloudKey(sourceId, id);
                    CloudState state = ACTIVE.computeIfAbsent(key, ignored -> new CloudState());
                    state.applyUpdate(dimensionId, origin, config, animationPreset, animationSpeed, durationTicks, resolvedFollowEntityId, resolvedFollowOffset, now);
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
    ACTIVE.entrySet().removeIf(entry -> now - entry.getValue().lastUpdateTick > entry.getValue().durationTicks);
    }

    private static void render(Minecraft mc, PoseStack poseStack) {
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) {
            return;
        }

        String currentDimension = mc.level.dimension().location().toString();
        long now = mc.level.getGameTime();
        float animTime = now + mc.getFrameTime();

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
                if (state.points.isEmpty() && state.config == null) {
                    continue;
                }

                Vec3 origin = state.currentOrigin(now, mc, mc.getFrameTime());
                double dist = origin.distanceTo(camera);
                List<EquationParticleGenerator.GeneratedPoint> points = state.points;
                if (state.timeDependent && state.config != null) {
                    points = EquationParticleGenerator.generate(
                        state.config,
                        ManifestationClientLimits.MAX_EQUATION_POINTS_RENDER,
                        ManifestationClientLimits.MAX_EQUATION_EVAL_BUDGET_RENDER,
                        animTime
                    );
                }

                if (points.isEmpty()) {
                    continue;
                }

                int step = computeSampleStep(points.size(), dist);
                int phase = Math.floorMod(state.sampleSeed, step);
                float baseSize = computePointHalfSize(dist);
                float alpha = computeAlpha(dist) * computeFadeInAlpha(state, animTime) * computeFadeOutAlpha(state, animTime);
                String animationPreset = state.animationPreset;
                float animationSpeed = (float) Math.max(0.1, state.animationSpeed);
                float scaledTime = animTime * animationSpeed;

                float bobOffset = Mth.sin(scaledTime * 0.09f) * 0.08f;
                float pulseScale = 1.0f + (0.18f * Mth.sin(scaledTime * 0.12f));
                float orbitTranslateX = Mth.cos(scaledTime * 0.05f) * 0.08f;
                float orbitTranslateZ = Mth.sin(scaledTime * 0.05f) * 0.08f;
                float rotateAngle = (float) Math.toRadians(scaledTime * 1.9f);
                float rotateCos = Mth.cos(rotateAngle);
                float rotateSin = Mth.sin(rotateAngle);
                float orbitAngle = (float) Math.toRadians(scaledTime * 1.4f);
                float orbitCos = Mth.cos(orbitAngle);
                float orbitSin = Mth.sin(orbitAngle);

                for (int i = 0; i < points.size(); i++) {
                    if ((i + phase) % step != 0) {
                        continue;
                    }
                    EquationParticleGenerator.GeneratedPoint point = points.get(i);
                    Vec3 pointOffset = point.offset();
                    float ox = (float) pointOffset.x;
                    float oy = (float) pointOffset.y;
                    float oz = (float) pointOffset.z;

                    float animatedOx = ox;
                    float animatedOy = oy;
                    float animatedOz = oz;

                    switch (animationPreset) {
                        case "static" -> {
                        }
                        case "bob" -> animatedOy += bobOffset;
                        case "pulse" -> {
                            animatedOx *= pulseScale;
                            animatedOy *= pulseScale;
                            animatedOz *= pulseScale;
                        }
                        case "orbit" -> {
                            float translatedX = ox + orbitTranslateX;
                            float translatedZ = oz + orbitTranslateZ;
                            animatedOx = (translatedX * orbitCos) - (translatedZ * orbitSin);
                            animatedOz = (translatedX * orbitSin) + (translatedZ * orbitCos);
                        }
                        case "spin_bob" -> {
                            animatedOx = (ox * rotateCos) - (oz * rotateSin);
                            animatedOz = (ox * rotateSin) + (oz * rotateCos);
                            animatedOy += bobOffset;
                        }
                        default -> {
                            animatedOx = (ox * rotateCos) - (oz * rotateSin);
                            animatedOz = (ox * rotateSin) + (oz * rotateCos);
                        }
                    }

                    Vec3 p = origin.add(animatedOx, animatedOy, animatedOz);
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

                    float lenSq = (animatedOx * animatedOx) + (animatedOy * animatedOy) + (animatedOz * animatedOz);
                    if (lenSq > 1.0e-6f) {
                        float invLen = Mth.invSqrt(lenSq);
                        float drift = DRIFT_RADIUS * (0.45f + (0.55f * Mth.sin(t + (i * 0.29f))));
                        x += animatedOx * invLen * drift;
                        y += animatedOy * invLen * drift;
                        z += animatedOz * invLen * drift;
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

    private static float computeFadeInAlpha(CloudState state, float animTime) {
        float ageTicks = animTime - state.spawnTick;
        return Mth.clamp(ageTicks / FADE_IN_TICKS, 0.0f, 1.0f);
    }

    private static float computeFadeOutAlpha(CloudState state, float animTime) {
        float ageSinceRefresh = animTime - state.lastUpdateTick;
        float remainingTicks = state.durationTicks - ageSinceRefresh;
        return Mth.clamp(remainingTicks / FADE_OUT_TICKS, 0.0f, 1.0f);
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
        boolean timeDependent;
        List<EquationParticleGenerator.GeneratedPoint> points = List.of();

        Vec3 fromOrigin = Vec3.ZERO;
        Vec3 toOrigin = Vec3.ZERO;
        Vec3 lastResolvedOrigin = Vec3.ZERO;
        long moveStartTick;
        int moveTicks = MOVE_TICKS;
        Integer followEntityId;
        Vec3 followOffset = Vec3.ZERO;
        int sampleSeed;
        String animationPreset = "rotate";
        double animationSpeed = 1.0;
        int durationTicks = DEFAULT_DURATION_TICKS;
        long spawnTick;

        long lastUpdateTick;
        boolean initialized;

        void applyUpdate(
            String dimensionId,
            Vec3 newOrigin,
            EquationParticleConfig newConfig,
            String animationPreset,
            double animationSpeed,
            int durationTicks,
            Integer followEntityId,
            Vec3 followOffset,
            long now
        ) {
            Vec3 resolvedFollowOffset = followOffset == null ? Vec3.ZERO : followOffset;
            long fp = fingerprint(newConfig);
            String normalizedAnimationPreset = normalizeAnimationPreset(animationPreset);
            double normalizedAnimationSpeed = normalizeAnimationSpeed(animationSpeed);
            boolean newTimeDependent = EquationParticleGenerator.usesTime(newConfig);
            boolean sameShape = initialized
                && Objects.equals(this.dimensionId, dimensionId)
                && this.configFingerprint == fp;
            boolean sameFollowBinding = Objects.equals(this.followEntityId, followEntityId)
                && Objects.equals(this.followOffset, resolvedFollowOffset);

            this.followEntityId = followEntityId;
            this.followOffset = resolvedFollowOffset;
            this.animationPreset = normalizedAnimationPreset;
            this.animationSpeed = normalizedAnimationSpeed;
            this.durationTicks = normalizeDurationTicks(durationTicks);
            this.timeDependent = newTimeDependent;

            if (!initialized || !Objects.equals(this.dimensionId, dimensionId)) {
                this.fromOrigin = newOrigin;
                this.toOrigin = newOrigin;
                this.lastResolvedOrigin = newOrigin;
                this.moveStartTick = now;
                this.moveTicks = MOVE_TICKS;
                this.sampleSeed = Objects.hash(dimensionId, fp, newOrigin.x, newOrigin.y, newOrigin.z);
                this.spawnTick = now;
                this.initialized = true;
            } else if (followEntityId != null && sameShape && sameFollowBinding) {
                // Follow-bound clouds should stay entity-anchored, not packet-snapshot-anchored.
                this.fromOrigin = this.lastResolvedOrigin;
                this.toOrigin = this.lastResolvedOrigin;
                this.moveStartTick = now;
                this.moveTicks = 1;
            } else if (sameShape) {
                if (this.toOrigin.distanceToSqr(newOrigin) <= ORIGIN_EPSILON_SQ) {
                    this.fromOrigin = newOrigin;
                    this.toOrigin = newOrigin;
                    this.lastResolvedOrigin = newOrigin;
                    this.moveStartTick = now;
                    this.moveTicks = 1;
                } else {
                    Vec3 start = currentOriginFromSnapshots(now, 0.0f);
                    this.fromOrigin = start;
                    this.toOrigin = newOrigin;
                    this.lastResolvedOrigin = start;
                    this.moveStartTick = now;
                    this.moveTicks = MOVE_TICKS;
                }
            } else {
                this.fromOrigin = newOrigin;
                this.toOrigin = newOrigin;
                this.lastResolvedOrigin = newOrigin;
                this.moveStartTick = now;
                this.moveTicks = 1;
                this.sampleSeed = Objects.hash(dimensionId, fp, newOrigin.x, newOrigin.y, newOrigin.z, now);
                this.spawnTick = now;
            }

            if (!sameShape) {
                try {
                    this.points = EquationParticleGenerator.generate(
                        newConfig,
                        ManifestationClientLimits.MAX_EQUATION_POINTS_RENDER,
                        ManifestationClientLimits.MAX_EQUATION_EVAL_BUDGET_RENDER,
                        0.0
                    );
                } catch (IllegalArgumentException ex) {
                    this.points = List.of();
                }
                this.spawnTick = now;
            }

            this.config = newConfig;
            this.configFingerprint = fp;
            this.dimensionId = dimensionId;
            this.lastUpdateTick = now;
        }

        Vec3 currentOrigin(long now, Minecraft mc, float partialTick) {
            if (followEntityId != null && mc.level != null) {
                Entity followed = mc.level.getEntity(followEntityId);
                if (followed != null) {
                    Vec3 tracked = followed.getPosition(partialTick)
                        .add(0.0, followed.getBbHeight() * 0.5, 0.0)
                        .add(followOffset);
                    lastResolvedOrigin = tracked;
                    fromOrigin = tracked;
                    toOrigin = tracked;
                    moveStartTick = now;
                    moveTicks = 1;
                    return tracked;
                }

                return lastResolvedOrigin;
            }


            return currentOriginFromSnapshots(now, partialTick);
        }

        private Vec3 currentOriginFromSnapshots(long now, float partialTick) {
            if (moveTicks <= 1) {
                return toOrigin;
            }
            float alpha = Mth.clamp(((float) (now - moveStartTick) + partialTick) / (float) moveTicks, 0.0f, 1.0f);
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

        private static String normalizeAnimationPreset(String raw) {
            if (raw == null) {
                return "rotate";
            }

            return switch (raw.toLowerCase()) {
                case "static" -> "static";
                case "bob" -> "bob";
                case "pulse" -> "pulse";
                case "orbit" -> "orbit";
                case "spin_bob" -> "spin_bob";
                default -> "rotate";
            };
        }

        private static double normalizeAnimationSpeed(double raw) {
            if (!Double.isFinite(raw)) {
                return 1.0;
            }
            return Math.max(0.1, Math.min(4.0, raw));
        }

        private static int normalizeDurationTicks(int raw) {
            return Math.max(20, Math.min(20 * 60, raw));
        }
    }

    private record CloudKey(UUID sourceId, long id) {
    }

    private EquationCloudVisuals() {
    }
}
