package com.bluup.manifestation.client.render;

import com.bluup.manifestation.common.equation.EquationParticleConfig;
import com.bluup.manifestation.common.equation.EquationParticleGenerator;
import com.bluup.manifestation.server.block.EquationSynthBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EquationSynthBlockEntityRenderer implements BlockEntityRenderer<EquationSynthBlockEntity> {
    private static final int PREVIEW_MAX_POINTS = 420;
    private static final int PREVIEW_EVAL_BUDGET = 2800;
    private static final int TARGET_VISIBLE_POINTS = 180;
    private static final double PREVIEW_RADIUS = 0.35;
    private static final float BASE_POINT_HALF = 0.0095f;

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
        EquationParticleConfig config = blockEntity.getPreviewEquation();
        if (config == null) {
            return;
        }

        long cacheKey = blockEntity.getBlockPos().asLong();
        long fingerprint = fingerprint(config);
        CachedPreview preview = CACHE.get(cacheKey);
        if (preview == null || preview.fingerprint != fingerprint) {
            preview = rebuild(config, fingerprint);
            CACHE.put(cacheKey, preview);
            if (CACHE.size() > 512) {
                CACHE.clear();
            }
        }
        if (preview.points.isEmpty()) {
            return;
        }

        long gameTime = blockEntity.getLevel() == null ? 0L : blockEntity.getLevel().getGameTime();
        int step = Math.max(1, (preview.points.size() + TARGET_VISIBLE_POINTS - 1) / TARGET_VISIBLE_POINTS);
        int phase = (int) (gameTime % step);
        float alpha = 0.86f;
        int light = LightTexture.FULL_BRIGHT;

        poseStack.pushPose();
        poseStack.translate(0.5, 0.55, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees((gameTime + partialTick) * 1.9f));

        VertexConsumer lineBuffer = buffer.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        for (int i = 0; i < preview.points.size(); i++) {
            if ((i + phase) % step != 0) {
                continue;
            }

            PreviewPoint point = preview.points.get(i);
            float x = point.x;
            float y = point.y;
            float z = point.z;
            float r = point.r;
            float g = point.g;
            float b = point.b;
            float s = BASE_POINT_HALF * (0.75f + (0.25f * Mth.sin((gameTime + i) * 0.07f)));

            addLine(lineBuffer, mat4, normal, x - s, y, z, x + s, y, z, r, g, b, alpha, light, 1.0f, 0.0f, 0.0f);
            addLine(lineBuffer, mat4, normal, x, y - s, z, x, y + s, z, r, g, b, alpha, light, 0.0f, 1.0f, 0.0f);
            addLine(lineBuffer, mat4, normal, x, y, z - s, x, y, z + s, r, g, b, alpha, light, 0.0f, 0.0f, 1.0f);
        }

        poseStack.popPose();
    }

    private static CachedPreview rebuild(EquationParticleConfig input, long fingerprint) {
        EquationParticleConfig config = input.normalized();
        try {
            List<EquationParticleGenerator.GeneratedPoint> generated = EquationParticleGenerator.generate(
                config,
                PREVIEW_MAX_POINTS,
                PREVIEW_EVAL_BUDGET
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

    private static void addLine(
        VertexConsumer vc,
        Matrix4f mat,
        Matrix3f normal,
        float x0,
        float y0,
        float z0,
        float x1,
        float y1,
        float z1,
        float r,
        float g,
        float b,
        float a,
        int light,
        float nx,
        float ny,
        float nz
    ) {
        vc.vertex(mat, x0, y0, z0)
            .color(r, g, b, a)
            .normal(normal, nx, ny, nz)
            .uv2(light)
            .endVertex();
        vc.vertex(mat, x1, y1, z1)
            .color(r, g, b, a)
            .normal(normal, nx, ny, nz)
            .uv2(light)
            .endVertex();
    }

    private record CachedPreview(long fingerprint, List<PreviewPoint> points) {
    }

    private record PreviewPoint(float x, float y, float z, float r, float g, float b) {
    }
}
