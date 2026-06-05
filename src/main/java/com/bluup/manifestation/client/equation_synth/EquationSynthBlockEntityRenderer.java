package com.bluup.manifestation.client.render;

import com.bluup.manifestation.common.equation.EquationParticleConfig;
import com.bluup.manifestation.common.equation.EquationParticleGenerator;
import com.bluup.manifestation.server.block.EquationSynthBlock;
import com.bluup.manifestation.server.block.EquationSynthBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EquationSynthBlockEntityRenderer implements BlockEntityRenderer<EquationSynthBlockEntity> {
    private static final int PREVIEW_MAX_POINTS = 420;
    private static final int PREVIEW_EVAL_BUDGET = 2800;
    private static final int TARGET_VISIBLE_MIN = 48;
    private static final int TARGET_VISIBLE_MAX = 360;
    private static final double PREVIEW_RADIUS = 0.35;
    private static final float BASE_POINT_HALF = 0.0105f;
    private static final float DRIFT_RADIUS = 0.012f;
    private static final float GLOW_SCALE = 1.9f;

    private static final Map<Long, CachedPreview> CACHE = new HashMap<>();

    public EquationSynthBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
        EquationSynthBlockEntity blockEntity,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        int packedOverlay
    ) {
        if (!blockEntity.getBlockState().getValue(EquationSynthBlock.FOCUS)
            || !blockEntity.getBlockState().getValue(EquationSynthBlock.ACTIVE)) {
            return;
        }

        EquationParticleConfig config = blockEntity.getPreviewEquation();
        if (config == null) {
            return;
        }

        long cacheKey = blockEntity.getBlockPos().asLong();
        long fingerprint = fingerprint(config);
        long gameTime = blockEntity.getLevel() == null ? 0L : blockEntity.getLevel().getGameTime();
        float animTime = gameTime + partialTick;
        float animationSpeed = (float) blockEntity.getAnimationSpeed();

        boolean timeDependent = EquationParticleGenerator.usesTime(config);
        CachedPreview preview;
        if (timeDependent) {
            preview = rebuild(config, fingerprint, animTime);
        } else {
            preview = CACHE.get(cacheKey);
            if (preview == null || preview.fingerprint != fingerprint) {
                preview = rebuild(config, fingerprint, 0.0);
                CACHE.put(cacheKey, preview);
                if (CACHE.size() > 512) {
                    CACHE.clear();
                }
            }
        }

        if (preview.points.isEmpty()) {
            return;
        }

        double density = Mth.clamp(blockEntity.getRenderDensity(), 0.1, 1.0);
        int targetVisible = Mth.clamp((int) Math.round(TARGET_VISIBLE_MIN + (density * (TARGET_VISIBLE_MAX - TARGET_VISIBLE_MIN))), TARGET_VISIBLE_MIN, TARGET_VISIBLE_MAX);
        int step = Math.max(1, (preview.points.size() + targetVisible - 1) / targetVisible);
        int phase = (int) (gameTime % step);
        float alpha = 0.86f;

        poseStack.pushPose();
        poseStack.translate(0.5, 0.55, 0.5);
        applyAnimationPreset(poseStack, blockEntity.getAnimationPreset(), animTime, animationSpeed);

        VertexConsumer sparkBuffer = buffer.getBuffer(RenderType.lightning());
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Quaternionf cameraRotation = camera.rotation();

        for (int i = 0; i < preview.points.size(); i++) {
            if ((i + phase) % step != 0) {
                continue;
            }

            PreviewPoint point = preview.points.get(i);
            float x = point.x;
            float y = point.y;
            float z = point.z;
            float r = Mth.clamp(point.r, 0.0f, 1.0f);
            float g = Mth.clamp(point.g, 0.0f, 1.0f);
            float b = Mth.clamp(point.b, 0.0f, 1.0f);

            float t = (animTime * 0.08f) + (i * 0.017f);
            float shimmer = 0.65f + (0.35f * Mth.sin((animTime * 0.24f) + (i * 0.93f)));
            float twinkle = 0.55f + (0.45f * Mth.sin((animTime * 0.35f) + (i * 1.71f)));

            float lenSq = (x * x) + (y * y) + (z * z);
            if (lenSq > 1.0e-6f) {
                float invLen = Mth.invSqrt(lenSq);
                float nx = x * invLen;
                float ny = y * invLen;
                float nz = z * invLen;

                float drift = DRIFT_RADIUS * (0.4f + (0.6f * Mth.sin(t + (i * 0.31f))));
                x += nx * drift;
                y += ny * drift;
                z += nz * drift;
            }

            x += Mth.sin((animTime * 0.11f) + (i * 0.27f)) * 0.004f;
            z += Mth.cos((animTime * 0.10f) + (i * 0.29f)) * 0.004f;

            float s = BASE_POINT_HALF * (0.65f + (0.35f * shimmer));
            float coreAlpha = alpha * (0.7f + (0.3f * twinkle));
            float glowAlpha = alpha * 0.26f * (0.75f + (0.25f * shimmer));

            addBillboardQuad(sparkBuffer, mat4, cameraRotation, x, y, z, s * GLOW_SCALE, r, g, b, glowAlpha);
            addBillboardQuad(sparkBuffer, mat4, cameraRotation, x, y, z, s, r, g, b, coreAlpha);
        }

        poseStack.popPose();
    }

    private static void applyAnimationPreset(PoseStack poseStack, String preset, float animTime, float speed) {
        float scaledTime = animTime * Math.max(0.1f, speed);
        switch (preset) {
            case "static" -> {
                // Keep rendered cloud fixed in-place.
            }
            case "bob" -> poseStack.translate(0.0, Mth.sin(scaledTime * 0.09f) * 0.08f, 0.0);
            case "pulse" -> {
                float scale = 1.0f + (0.18f * Mth.sin(scaledTime * 0.12f));
                poseStack.scale(scale, scale, scale);
            }
            case "orbit" -> {
                poseStack.translate(Mth.cos(scaledTime * 0.05f) * 0.08f, 0.0, Mth.sin(scaledTime * 0.05f) * 0.08f);
                poseStack.mulPose(Axis.YP.rotationDegrees(scaledTime * 1.4f));
            }
            case "spin_bob" -> {
                poseStack.mulPose(Axis.YP.rotationDegrees(scaledTime * 1.9f));
                poseStack.translate(0.0, Mth.sin(scaledTime * 0.09f) * 0.08f, 0.0);
            }
            default -> poseStack.mulPose(Axis.YP.rotationDegrees(scaledTime * 1.9f));
        }
    }

    private static CachedPreview rebuild(EquationParticleConfig input, long fingerprint, double time) {
        EquationParticleConfig config = input.normalized();
        try {
            List<EquationParticleGenerator.GeneratedPoint> generated = EquationParticleGenerator.generate(
                config,
                PREVIEW_MAX_POINTS,
                PREVIEW_EVAL_BUDGET,
                time
            );

            if (generated.isEmpty()) {
                return new CachedPreview(fingerprint, List.of());
            }

            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (EquationParticleGenerator.GeneratedPoint point : generated) {
                Vec3 p = point.offset();
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }

            double spanX = Math.max(1.0e-6, maxX - minX);
            double spanY = Math.max(1.0e-6, maxY - minY);
            double spanZ = Math.max(1.0e-6, maxZ - minZ);
            double spanMax = Math.max(spanX, Math.max(spanY, spanZ));
            double scale = (PREVIEW_RADIUS * 2.0) / spanMax;

            double cx = (minX + maxX) * 0.5;
            double cy = (minY + maxY) * 0.5;
            double cz = (minZ + maxZ) * 0.5;

            ArrayList<PreviewPoint> out = new ArrayList<>(generated.size());
            for (EquationParticleGenerator.GeneratedPoint point : generated) {
                Vec3 p = point.offset();
                Vec3 c = point.color();

                float x = (float) ((p.x - cx) * scale);
                float y = (float) ((p.y - cy) * scale);
                float z = (float) ((p.z - cz) * scale);
                float r = (float) Mth.clamp(c.x, 0.0, 1.0);
                float g = (float) Mth.clamp(c.y, 0.0, 1.0);
                float b = (float) Mth.clamp(c.z, 0.0, 1.0);
                out.add(new PreviewPoint(x, y, z, r, g, b));
            }

            return new CachedPreview(fingerprint, out);
        } catch (Throwable ignored) {
            return new CachedPreview(fingerprint, List.of());
        }
    }

    private static long fingerprint(EquationParticleConfig c) {
        return Objects.hash(
            c.xExpr(),
            c.yExpr(),
            c.zExpr(),
            c.tMin(),
            c.tMax(),
            c.uMin(),
            c.uMax(),
            c.useU(),
            c.pointCount(),
            c.colorMode(),
            c.fixedR(),
            c.fixedG(),
            c.fixedB(),
            c.gradientStartR(),
            c.gradientStartG(),
            c.gradientStartB(),
            c.gradientEndR(),
            c.gradientEndG(),
            c.gradientEndB(),
            c.colorExprR(),
            c.colorExprG(),
            c.colorExprB()
        );
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
        float a
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

        addQuad(vc, mat, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, r, g, b, a);
        addQuad(vc, mat, x3, y3, z3, x2, y2, z2, x1, y1, z1, x0, y0, z0, r, g, b, a);
    }

    private static void addQuad(
        VertexConsumer vc,
        Matrix4f mat,
        float x0,
        float y0,
        float z0,
        float x1,
        float y1,
        float z1,
        float x2,
        float y2,
        float z2,
        float x3,
        float y3,
        float z3,
        float r,
        float g,
        float b,
        float a
    ) {
        vc.vertex(mat, x0, y0, z0)
            .color(r, g, b, a)
            .endVertex();
        vc.vertex(mat, x1, y1, z1)
            .color(r, g, b, a)
            .endVertex();
        vc.vertex(mat, x2, y2, z2)
            .color(r, g, b, a)
            .endVertex();
        vc.vertex(mat, x3, y3, z3)
            .color(r, g, b, a)
            .endVertex();
    }

    private record CachedPreview(long fingerprint, List<PreviewPoint> points) {
    }

    private record PreviewPoint(float x, float y, float z, float r, float g, float b) {
    }
}
