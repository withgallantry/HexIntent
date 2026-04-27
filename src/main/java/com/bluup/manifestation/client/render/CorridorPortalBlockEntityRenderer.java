package com.bluup.manifestation.client.render;

import com.bluup.manifestation.server.block.CorridorPortalBlock;
import com.bluup.manifestation.server.block.CorridorPortalBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;

public final class CorridorPortalBlockEntityRenderer implements BlockEntityRenderer<CorridorPortalBlockEntity> {
    private static final int STRIPS = 40;
    private static final int OUTLINE_SEGMENTS = 64;
    private static final float HALF_HEIGHT = 0.78f;
    private static final float HALF_WIDTH = 0.50f;
    private static final float THRESHOLD_HALF_SIZE = 0.50f;
    private static final float Z_EPSILON = 0.0025f;
    private static final ResourceLocation THRESHOLD_GLYPH_SPRITE = new ResourceLocation("manifestation", "block/spell_circle");

    public CorridorPortalBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
        CorridorPortalBlockEntity blockEntity,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        int packedOverlay
    ) {
        BlockState state = blockEntity.getBlockState();
        if (!state.hasProperty(CorridorPortalBlock.AXIS)) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-blockEntity.getRenderYawDegrees()));

        float envelope = blockEntity.renderEnvelope(partialTick);
        if (envelope <= 0.01f) {
            poseStack.popPose();
            return;
        }

        VertexConsumer portalVc = buffer.getBuffer(RenderType.endPortal());
        VertexConsumer fxVc = buffer.getBuffer(RenderType.translucent());
        VertexConsumer energyVc = buffer.getBuffer(RenderType.lightning());
        float worldTicks = blockEntity.getLevel() == null ? 0f : (blockEntity.getLevel().getGameTime() + partialTick);
        float time = worldTicks * 0.042f;
        float collapseProgress = blockEntity.collapseProgress(partialTick);
        float scale = Mth.clamp(blockEntity.getRenderScale(), 0.1f, 3.0f);

        if (blockEntity.isThresholdMode()) {
            TextureAtlasSprite glyphSprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(THRESHOLD_GLYPH_SPRITE);
            VertexConsumer glyphVc = buffer.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
            drawThresholdGlyph(poseStack, glyphVc, glyphSprite, packedLight, envelope, scale, time, worldTicks);
            drawCollapseSpark(poseStack, energyVc, packedLight, collapseProgress);
            poseStack.popPose();
            return;
        }

        // Jagged tear around portal. This took a lot to get looking so so lol.
        drawPortalTear(poseStack, portalVc, Z_EPSILON, envelope, scale, time);
        drawPortalTear(poseStack, portalVc, -Z_EPSILON, envelope, scale, time + 1.7f);
        drawEdgeVeil(poseStack, fxVc, packedLight, envelope, scale, time, 0);
        drawInflowTrails(poseStack, energyVc, packedLight, envelope, scale, time);
        drawPurpleGlow(poseStack, energyVc, packedLight, envelope, scale, time, 0);
        drawCollapseSpark(poseStack, energyVc, packedLight, collapseProgress);

        poseStack.popPose();
    }

    private void drawPortalTear(
        PoseStack poseStack,
        VertexConsumer vc,
        float z,
        float envelope,
        float scale,
        float time
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();

        float portalHalfHeight = HALF_HEIGHT * envelope * scale;
        float portalHalfWidth = HALF_WIDTH * envelope * scale;
        if (portalHalfHeight <= 0.0001f || portalHalfWidth <= 0.0001f) {
            return;
        }

        for (int i = 0; i < STRIPS; i++) {
            float v0 = i / (float) STRIPS;
            float v1 = (i + 1) / (float) STRIPS;

            float y0 = Mth.lerp(v0, -portalHalfHeight, portalHalfHeight);
            float y1 = Mth.lerp(v1, -portalHalfHeight, portalHalfHeight);

            float n0 = y0 / portalHalfHeight;
            float n1 = y1 / portalHalfHeight;

            float wobble0 = tearWobbleX(n0, envelope, scale, time);
            float wobble1 = tearWobbleX(n1, envelope, scale, time);

            float left0 = tearLeftX(n0, portalHalfWidth, envelope, scale, time) + wobble0;
            float right0 = tearRightX(n0, portalHalfWidth, envelope, scale, time) + wobble0;
            float left1 = tearLeftX(n1, portalHalfWidth, envelope, scale, time) + wobble1;
            float right1 = tearRightX(n1, portalHalfWidth, envelope, scale, time) + wobble1;

            portalVertex(vc, mat4, left0, y0, z);
            portalVertex(vc, mat4, right0, y0, z);
            portalVertex(vc, mat4, right1, y1, z);
            portalVertex(vc, mat4, left1, y1, z);

            portalVertex(vc, mat4, left1, y1, z);
            portalVertex(vc, mat4, right1, y1, z);
            portalVertex(vc, mat4, right0, y0, z);
            portalVertex(vc, mat4, left0, y0, z);
        }
    }

    private void drawPurpleGlow(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time,
        int shape
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float halfH = HALF_HEIGHT * envelope * scale;
        float halfW = HALF_WIDTH * envelope * scale;
        if (halfH <= 0.0001f || halfW <= 0.0001f) {
            return;
        }

        float zFront = Z_EPSILON + 0.0014f;
        float zBack = -Z_EPSILON - 0.0014f;
        float glowPulse = 0.74f + (0.18f * Mth.sin(time * 2.1f)) + (0.08f * Mth.sin(time * 6.2f));
        int alphaOuter = Mth.clamp((int) (112f * envelope * glowPulse), 0, 255);
        int alphaInner = Mth.clamp((int) (188f * envelope * glowPulse), 0, 255);

        for (int side = 0; side < 2; side++) {
            float z = side == 0 ? zFront : zBack;
            float nz = side == 0 ? 1.0f : -1.0f;

            for (int i = 0; i < OUTLINE_SEGMENTS; i++) {
                float a0 = Mth.TWO_PI * i / OUTLINE_SEGMENTS;
                float a1 = Mth.TWO_PI * (i + 1) / OUTLINE_SEGMENTS;

                PortalPoint inner0 = portalPoint(shape, a0, halfW, halfH, envelope, scale, time);
                PortalPoint inner1 = portalPoint(shape, a1, halfW, halfH, envelope, scale, time);

                float width0 = outlineWidth(shape, inner0, envelope, scale);
                float width1 = outlineWidth(shape, inner1, envelope, scale);

                PortalPoint outer0 = offsetPortalPoint(shape, inner0, width0, halfW, halfH);
                PortalPoint outer1 = offsetPortalPoint(shape, inner1, width1, halfW, halfH);

                quadBidirectional(vc, mat4, normal, outer0.x, outer0.y, inner0.x, inner0.y, inner1.x, inner1.y, outer1.x, outer1.y, z, light, nz,
                    164, 102, 255, alphaOuter,
                    214, 166, 255, alphaInner,
                    214, 166, 255, alphaInner,
                    164, 102, 255, alphaOuter);
            }
        }
    }

    private void drawEdgeVeil(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time,
        int shape
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float halfH = HALF_HEIGHT * envelope * scale;
        float halfW = HALF_WIDTH * envelope * scale;
        if (halfH <= 0.0001f || halfW <= 0.0001f) {
            return;
        }

        float zFront = Z_EPSILON + 0.0011f;
        float zBack = -Z_EPSILON - 0.0011f;
        float pulse = 0.72f + (0.14f * Mth.sin(time * 1.9f)) + (0.06f * Mth.sin(time * 4.7f));
        int edgeAlpha = Mth.clamp((int) (74f * envelope * pulse), 0, 255);

        for (int side = 0; side < 2; side++) {
            float z = side == 0 ? zFront : zBack;
            float nz = side == 0 ? 1.0f : -1.0f;

            for (int i = 0; i < OUTLINE_SEGMENTS; i++) {
                float a0 = Mth.TWO_PI * i / OUTLINE_SEGMENTS;
                float a1 = Mth.TWO_PI * (i + 1) / OUTLINE_SEGMENTS;

                PortalPoint edge0 = portalPoint(shape, a0, halfW, halfH, envelope, scale, time);
                PortalPoint edge1 = portalPoint(shape, a1, halfW, halfH, envelope, scale, time);

                float inset0 = innerVeilWidth(shape, edge0, envelope, scale);
                float inset1 = innerVeilWidth(shape, edge1, envelope, scale);

                PortalPoint inner0 = offsetPortalPoint(shape, edge0, -inset0, halfW, halfH);
                PortalPoint inner1 = offsetPortalPoint(shape, edge1, -inset1, halfW, halfH);

                quadBidirectional(vc, mat4, normal,
                    edge0.x, edge0.y,
                    inner0.x, inner0.y,
                    inner1.x, inner1.y,
                    edge1.x, edge1.y,
                    z, light, nz,
                    138, 94, 255, edgeAlpha,
                    102, 74, 176, 0,
                    102, 74, 176, 0,
                    138, 94, 255, edgeAlpha);
            }
        }
    }

    private void drawInflowTrails(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float halfH = HALF_HEIGHT * envelope * scale;
        float halfW = HALF_WIDTH * envelope * scale;
        if (halfH <= 0.0001f || halfW <= 0.0001f) {
            return;
        }

        int trails = 14;
        for (int i = 0; i < trails; i++) {
            float seed = i * 0.731f;
            float p0 = Mth.frac(time * 0.34f + i * 0.143f);
            float p1 = Mth.clamp(p0 + 0.22f, 0.0f, 1.0f);

            float x0 = trailX(p0, seed, halfW, time);
            float y0 = trailY(p0, seed, halfH, time);
            float x1 = trailX(p1, seed, halfW, time);
            float y1 = trailY(p1, seed, halfH, time);

            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = Mth.sqrt(dx * dx + dy * dy);
            if (len < 0.0001f) {
                continue;
            }

            float nx = -dy / len;
            float ny = dx / len;
            float baseSize = (0.014f + 0.006f * Mth.sin(time * 1.2f + seed * 2.0f)) * (1.0f - p0) * envelope * scale;
            int alphaTail = Mth.clamp((int) (46f * (1.0f - p0) * envelope), 0, 255);
            int alphaHead = Mth.clamp((int) (98f * (1.0f - p0) * envelope), 0, 255);

            float zFront = Z_EPSILON + 0.0019f;
            float zBack = -Z_EPSILON - 0.0019f;
            for (int mote = 0; mote < 4; mote++) {
                float moteT = mote / 3.8f;
                float cx = Mth.lerp(moteT, x0, x1);
                float cy = Mth.lerp(moteT, y0, y1);
                float fade = 1.0f - moteT;
                float size = baseSize * (0.75f + 0.45f * fade);
                float stretch = size * (1.5f + 0.4f * fade);
                int moteTailAlpha = Mth.clamp((int) (alphaTail * fade), 0, 255);
                int moteHeadAlpha = Mth.clamp((int) (alphaHead * fade), 0, 255);

                drawTrailWisp(vc, mat4, normal, cx, cy, nx, ny, size, stretch, zFront, light, 1.0f, moteTailAlpha, moteHeadAlpha);
                drawTrailWisp(vc, mat4, normal, cx, cy, nx, ny, size, stretch, zBack, light, -1.0f, moteTailAlpha, moteHeadAlpha);
            }
        }
    }

    private void drawTrailWisp(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        float cx,
        float cy,
        float nx,
        float ny,
        float size,
        float stretch,
        float z,
        int light,
        float nz,
        int alphaTail,
        int alphaHead
    ) {
        quadBidirectional(vc, mat4, normal,
            cx - nx * size, cy - ny * size,
            cx - nx * stretch * 0.35f, cy - ny * stretch * 0.35f,
            cx + nx * size, cy + ny * size,
            cx + nx * stretch, cy + ny * stretch,
            z, light, nz,
            118, 84, 220, 0,
            150, 106, 255, alphaTail,
            216, 176, 255, alphaHead,
            188, 138, 255, 0);
    }

        // Leaving this in incase I want to add it back but it wasn't great looking and I wasn't sure how to get it right.
    private void drawSideFrame(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float halfH = HALF_HEIGHT * envelope * scale;
        float halfW = HALF_WIDTH * envelope * scale;
        if (halfH <= 0.0001f || halfW <= 0.0001f) {
            return;
        }

        float depth = 0.052f * envelope * scale;
        float sideInset = 0.030f * envelope * scale;
        float topInset = 0.036f * envelope * scale;
        int alpha = Mth.clamp((int) ((86f + 18f * Mth.sin(time * 2.0f)) * envelope), 0, 255);

        // Left side wall
        quad3dBidirectional(vc, mat4, normal,
            -halfW, -halfH + sideInset, -depth,
            -halfW, halfH - sideInset, -depth,
            -halfW, halfH - sideInset, depth,
            -halfW, -halfH + sideInset, depth,
            light,
            -1.0f, 0.0f, 0.0f,
            124, 84, 220, alpha,
            164, 116, 255, alpha,
            164, 116, 255, alpha,
            124, 84, 220, alpha);

        // Right side wall
        quad3dBidirectional(vc, mat4, normal,
            halfW, -halfH + sideInset, depth,
            halfW, halfH - sideInset, depth,
            halfW, halfH - sideInset, -depth,
            halfW, -halfH + sideInset, -depth,
            light,
            1.0f, 0.0f, 0.0f,
            124, 84, 220, alpha,
            164, 116, 255, alpha,
            164, 116, 255, alpha,
            124, 84, 220, alpha);

        // Top cap
        quad3dBidirectional(vc, mat4, normal,
            -halfW + topInset, halfH, -depth,
            halfW - topInset, halfH, -depth,
            halfW - topInset, halfH, depth,
            -halfW + topInset, halfH, depth,
            light,
            0.0f, 1.0f, 0.0f,
            136, 92, 232, alpha,
            176, 128, 255, alpha,
            176, 128, 255, alpha,
            136, 92, 232, alpha);

        // Bottom cap
        quad3dBidirectional(vc, mat4, normal,
            -halfW + topInset, -halfH, depth,
            halfW - topInset, -halfH, depth,
            halfW - topInset, -halfH, -depth,
            -halfW + topInset, -halfH, -depth,
            light,
            0.0f, -1.0f, 0.0f,
            136, 92, 232, alpha,
            176, 128, 255, alpha,
            176, 128, 255, alpha,
            136, 92, 232, alpha);
    }

    private static void quad3dBidirectional(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
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
        int light,
        float nx,
        float ny,
        float nz,
        int r0,
        int g0,
        int b0,
        int a0,
        int r1,
        int g1,
        int b1,
        int a1,
        int r2,
        int g2,
        int b2,
        int a2,
        int r3,
        int g3,
        int b3,
        int a3
    ) {
        vertex(vc, mat4, normal, x0, y0, z0, 0.0f, 0.0f, r0, g0, b0, a0, light, nx, ny, nz);
        vertex(vc, mat4, normal, x1, y1, z1, 1.0f, 0.0f, r1, g1, b1, a1, light, nx, ny, nz);
        vertex(vc, mat4, normal, x2, y2, z2, 1.0f, 1.0f, r2, g2, b2, a2, light, nx, ny, nz);
        vertex(vc, mat4, normal, x3, y3, z3, 0.0f, 1.0f, r3, g3, b3, a3, light, nx, ny, nz);

        vertex(vc, mat4, normal, x3, y3, z3, 0.0f, 1.0f, r3, g3, b3, a3, light, -nx, -ny, -nz);
        vertex(vc, mat4, normal, x2, y2, z2, 1.0f, 1.0f, r2, g2, b2, a2, light, -nx, -ny, -nz);
        vertex(vc, mat4, normal, x1, y1, z1, 1.0f, 0.0f, r1, g1, b1, a1, light, -nx, -ny, -nz);
        vertex(vc, mat4, normal, x0, y0, z0, 0.0f, 0.0f, r0, g0, b0, a0, light, -nx, -ny, -nz);
    }

    private void drawPortalSquare(
        PoseStack poseStack,
        VertexConsumer vc,
        float z,
        float envelope,
        float scale
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();

        float halfH = HALF_HEIGHT * envelope * scale;
        float halfW = HALF_WIDTH * envelope * scale;

        portalVertex(vc, mat4, -halfW, -halfH, z);
        portalVertex(vc, mat4, halfW, -halfH, z);
        portalVertex(vc, mat4, halfW, halfH, z);
        portalVertex(vc, mat4, -halfW, halfH, z);

        portalVertex(vc, mat4, -halfW, halfH, z);
        portalVertex(vc, mat4, halfW, halfH, z);
        portalVertex(vc, mat4, halfW, -halfH, z);
        portalVertex(vc, mat4, -halfW, -halfH, z);
    }

    private void drawCollapseSpark(PoseStack poseStack, VertexConsumer vc, int light, float collapseProgress) {
        // Bright implosion spark only in the final part of collapse.
        // I keep flip flopping on whether I like this or not but it does add a bit of extra punch to the collapse and I don't have a better idea for what to do in that moment, so here we are.
        if (collapseProgress < 0.78f || collapseProgress >= 1.0f) {
            return;
        }

        float phase = (collapseProgress - 0.78f) / 0.22f;
        float pulse = Mth.sin(phase * Mth.PI);
        float size = 0.03f + (0.18f * (1.0f - phase));
        int alpha = Mth.clamp((int) (255f * pulse), 0, 255);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        vertex(vc, mat4, normal, -size, -size, Z_EPSILON + 0.0008f, 0.0f, 0.0f, 138, 84, 255, alpha, light, 1.0f);
        vertex(vc, mat4, normal, size, -size, Z_EPSILON + 0.0008f, 1.0f, 0.0f, 176, 108, 255, alpha, light, 1.0f);
        vertex(vc, mat4, normal, size, size, Z_EPSILON + 0.0008f, 1.0f, 1.0f, 214, 164, 255, alpha, light, 1.0f);
        vertex(vc, mat4, normal, -size, size, Z_EPSILON + 0.0008f, 0.0f, 1.0f, 176, 108, 255, alpha, light, 1.0f);

        vertex(vc, mat4, normal, -size, size, Z_EPSILON + 0.0008f, 0.0f, 1.0f, 176, 108, 255, alpha, light, -1.0f);
        vertex(vc, mat4, normal, size, size, Z_EPSILON + 0.0008f, 1.0f, 1.0f, 214, 164, 255, alpha, light, -1.0f);
        vertex(vc, mat4, normal, size, -size, Z_EPSILON + 0.0008f, 1.0f, 0.0f, 176, 108, 255, alpha, light, -1.0f);
        vertex(vc, mat4, normal, -size, -size, Z_EPSILON + 0.0008f, 0.0f, 0.0f, 138, 84, 255, alpha, light, -1.0f);
    }

    private void drawThresholdGlyph(
        PoseStack poseStack,
        VertexConsumer vc,
        TextureAtlasSprite sprite,
        int light,
        float envelope,
        float scale,
        float time,
        float worldTicks
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float halfSize = THRESHOLD_HALF_SIZE * envelope;
        if (halfSize <= 0.0001f) {
            return;
        }

        float pulse = 0.72f + (0.22f * Mth.sin(time * 2.2f));
        int alphaOuter = Mth.clamp((int) (132f * envelope * pulse), 0, 255);
        int alphaInner = Mth.clamp((int) (186f * envelope * pulse), 0, 255);
        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        float u1 = sprite.getU1();
        float v1 = sprite.getV1();
        glyphQuadBidirectional(vc, mat4, normal,
            -halfSize, -halfSize,
            halfSize, -halfSize,
            halfSize, halfSize,
            -halfSize, halfSize,
            Z_EPSILON + 0.0015f,
            light,
            1.0f,
            u0, v0,
            u1, v0,
            u1, v1,
            u0, v1,
            168, 126, 255, alphaOuter,
            226, 200, 255, alphaInner,
            226, 200, 255, alphaInner,
            168, 126, 255, alphaOuter);
    }

    private static void glyphQuadBidirectional(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        float x0,
        float y0,
        float x1,
        float y1,
        float x2,
        float y2,
        float x3,
        float y3,
        float z,
        int light,
        float normalZ,
        float u0,
        float v0,
        float u1,
        float v1,
        float u2,
        float v2,
        float u3,
        float v3,
        int r0,
        int g0,
        int b0,
        int a0,
        int r1,
        int g1,
        int b1,
        int a1,
        int r2,
        int g2,
        int b2,
        int a2,
        int r3,
        int g3,
        int b3,
        int a3
    ) {
        vertex(vc, mat4, normal, x0, y0, z, u0, v0, r0, g0, b0, a0, light, normalZ);
        vertex(vc, mat4, normal, x1, y1, z, u1, v1, r1, g1, b1, a1, light, normalZ);
        vertex(vc, mat4, normal, x2, y2, z, u2, v2, r2, g2, b2, a2, light, normalZ);
        vertex(vc, mat4, normal, x3, y3, z, u3, v3, r3, g3, b3, a3, light, normalZ);

        vertex(vc, mat4, normal, x3, y3, z, u3, v3, r3, g3, b3, a3, light, -normalZ);
        vertex(vc, mat4, normal, x2, y2, z, u2, v2, r2, g2, b2, a2, light, -normalZ);
        vertex(vc, mat4, normal, x1, y1, z, u1, v1, r1, g1, b1, a1, light, -normalZ);
        vertex(vc, mat4, normal, x0, y0, z, u0, v0, r0, g0, b0, a0, light, -normalZ);
    }

    private static float ellipseWidthFactor(float normalizedY) {
        float clamped = Mth.clamp(1.0f - (normalizedY * normalizedY), 0.0f, 1.0f);
        return Mth.sqrt(clamped);
    }

    private static float tearLeftX(float normalizedY, float halfWidth, float envelope, float scale, float time) {
        float base = -halfWidth * ellipseWidthFactor(normalizedY);
        float jagAmp = (0.038f + 0.034f * Mth.abs(normalizedY)) * envelope * scale;
        float noise = tearNoise(normalizedY, time + 1.7f);
        return base - jagAmp * (0.55f + noise);
    }

    private static float tearRightX(float normalizedY, float halfWidth, float envelope, float scale, float time) {
        float base = halfWidth * ellipseWidthFactor(normalizedY);
        float jagAmp = (0.038f + 0.034f * Mth.abs(normalizedY)) * envelope * scale;
        float noise = tearNoise(normalizedY + 0.31f, time + 3.1f);
        return base + jagAmp * (0.55f + noise);
    }

    private static float tearWobbleX(float normalizedY, float envelope, float scale, float time) {
        float falloff = 1.0f - (normalizedY * normalizedY);
        float amp = 0.03f * envelope * scale * Mth.clamp(falloff, 0.0f, 1.0f);
        float wobble = Mth.sin(time * 2.9f + normalizedY * 7.4f) + (0.55f * Mth.sin(time * 5.6f - normalizedY * 12.0f));
        return amp * wobble;
    }

    private static float tearNoise(float n, float t) {
        float a = Mth.sin((n * 21.0f) + (t * 2.6f));
        float b = Mth.sin((n * 43.0f) - (t * 1.9f));
        float c = Mth.sin((n * 71.0f) + (t * 3.7f));
        return Mth.clamp((a * 0.5f) + (b * 0.32f) + (c * 0.18f), -1.0f, 1.0f);
    }

    private static float trailX(float p, float seed, float halfW, float time) {
        float side = (seed % 2.0f) < 1.0f ? -1.0f : 1.0f;
        float start = side * halfW * (2.15f + (0.36f * Mth.sin(seed * 2.3f)));
        float end = side * halfW * (0.34f + (0.06f * Mth.sin(seed * 4.1f + time * 1.2f)));
        float curve = Mth.sin((p * 7.2f) + (time * 3.1f) + seed * 5.3f) * halfW * 0.2f * (1.0f - p);
        return Mth.lerp(p, start, end) + curve;
    }

    private static float trailY(float p, float seed, float halfH, float time) {
        float start = Mth.sin(seed * 6.7f) * halfH * 1.8f;
        float end = Mth.sin(seed * 11.1f + time * 0.6f) * halfH * 0.38f;
        float drift = Mth.cos((p * 8.2f) + seed * 3.8f + time * 2.0f) * halfH * 0.16f * (1.0f - p);
        return Mth.lerp(p, start, end) + drift;
    }

    private static PortalPoint portalPoint(int shape, float angle, float halfW, float halfH, float envelope, float scale, float time) {
        float cos = Mth.cos(angle);
        float sin = Mth.sin(angle);

        if (shape == 1) {
            float extent = 1.0f / Math.max(Math.abs(cos), Math.abs(sin));
            return new PortalPoint(cos * halfW * extent, sin * halfH * extent);
        }

        float wobble = tearWobbleX(sin, envelope, scale, time);
        return new PortalPoint(
            (cos >= 0.0f ? tearRightX(sin, halfW, envelope, scale, time) : tearLeftX(sin, halfW, envelope, scale, time)) + wobble,
            sin * halfH
        );
    }

    private static float outlineWidth(int shape, PortalPoint point, float envelope, float scale) {
        if (shape == 1) {
            float halfW = HALF_WIDTH * envelope * scale;
            float halfH = HALF_HEIGHT * envelope * scale;
            float xRatio = Math.abs(point.x) / Math.max(0.0001f, halfW);
            float yRatio = Math.abs(point.y) / Math.max(0.0001f, halfH);
            float topBottomWeight = Mth.clamp((yRatio - xRatio + 0.18f) / 0.36f, 0.0f, 1.0f);
            float cornerWeight = 1.0f - Mth.clamp(Math.abs(xRatio - yRatio) / 0.22f, 0.0f, 1.0f);
            float sideWidth = 0.058f;
            float topWidth = 0.086f;
            float cornerWidth = 0.076f;
            float blended = Mth.lerp(topBottomWeight, sideWidth, topWidth);
            blended = Mth.lerp(cornerWeight, blended, cornerWidth);
            return blended * envelope * scale;
        }

        float verticalBias = Math.abs(point.y) / Math.max(0.0001f, HALF_HEIGHT * envelope * scale);
        return (0.052f + 0.034f * verticalBias) * envelope * scale;
    }

    private static float innerVeilWidth(int shape, PortalPoint point, float envelope, float scale) {
        if (shape == 1) {
            float halfW = HALF_WIDTH * envelope * scale;
            float halfH = HALF_HEIGHT * envelope * scale;
            float xRatio = Math.abs(point.x) / Math.max(0.0001f, halfW);
            float yRatio = Math.abs(point.y) / Math.max(0.0001f, halfH);
            float topBottomWeight = Mth.clamp((yRatio - xRatio + 0.18f) / 0.36f, 0.0f, 1.0f);
            return Mth.lerp(topBottomWeight, 0.038f, 0.054f) * envelope * scale;
        }

        float verticalBias = Math.abs(point.y) / Math.max(0.0001f, HALF_HEIGHT * envelope * scale);
        return (0.03f + 0.024f * verticalBias) * envelope * scale;
    }

    private void drawSquareCornerAccents(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time,
        int shape
    ) {
        if (shape != 1) {
            return;
        }

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float halfH = HALF_HEIGHT * envelope * scale;
        float halfW = HALF_WIDTH * envelope * scale;
        float zFront = Z_EPSILON + 0.0022f;
        float zBack = -Z_EPSILON - 0.0022f;
        float pulse = 0.56f + (0.24f * Mth.sin(time * 2.5f)) + (0.12f * Mth.sin(time * 7.0f));
        int alphaOuter = Mth.clamp((int) (122f * envelope * pulse), 0, 255);
        int alphaInner = Mth.clamp((int) (196f * envelope * pulse), 0, 255);
        float inset = 0.09f * envelope * scale;
        float flare = 0.07f * envelope * scale;

        drawCornerAccent(vc, mat4, normal, light, zFront, 1.0f, halfW, halfH, -1.0f, -1.0f, inset, flare, alphaOuter, alphaInner);
        drawCornerAccent(vc, mat4, normal, light, zFront, 1.0f, halfW, halfH, 1.0f, -1.0f, inset, flare, alphaOuter, alphaInner);
        drawCornerAccent(vc, mat4, normal, light, zFront, 1.0f, halfW, halfH, 1.0f, 1.0f, inset, flare, alphaOuter, alphaInner);
        drawCornerAccent(vc, mat4, normal, light, zFront, 1.0f, halfW, halfH, -1.0f, 1.0f, inset, flare, alphaOuter, alphaInner);

        drawCornerAccent(vc, mat4, normal, light, zBack, -1.0f, halfW, halfH, -1.0f, -1.0f, inset, flare, alphaOuter, alphaInner);
        drawCornerAccent(vc, mat4, normal, light, zBack, -1.0f, halfW, halfH, 1.0f, -1.0f, inset, flare, alphaOuter, alphaInner);
        drawCornerAccent(vc, mat4, normal, light, zBack, -1.0f, halfW, halfH, 1.0f, 1.0f, inset, flare, alphaOuter, alphaInner);
        drawCornerAccent(vc, mat4, normal, light, zBack, -1.0f, halfW, halfH, -1.0f, 1.0f, inset, flare, alphaOuter, alphaInner);
    }

    private void drawCornerAccent(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        int light,
        float z,
        float nz,
        float halfW,
        float halfH,
        float sx,
        float sy,
        float inset,
        float flare,
        int alphaOuter,
        int alphaInner
    ) {
        float outerX = sx * halfW;
        float outerY = sy * halfH;
        float edgeX = sx * (halfW - inset);
        float edgeY = sy * (halfH - inset);
        float innerX = sx * (halfW - inset - flare * 0.55f);
        float innerY = sy * (halfH - inset - flare * 0.55f);
        float diagX = sx * (halfW + flare * 0.42f);
        float diagY = sy * (halfH + flare * 0.42f);

        quadBidirectional(vc, mat4, normal,
            edgeX, outerY,
            outerX, outerY,
            diagX, diagY,
            outerX, edgeY,
            z, light, nz,
            190, 128, 255, alphaInner,
            236, 198, 255, alphaOuter,
            184, 116, 255, 0,
            190, 128, 255, alphaInner);

        quadBidirectional(vc, mat4, normal,
            edgeX, edgeY,
            innerX, edgeY,
            diagX, diagY,
            edgeX, innerY,
            z, light, nz,
            184, 116, 255, alphaInner,
            136, 94, 220, 0,
            184, 116, 255, 0,
            136, 94, 220, 0);
    }

    private static PortalPoint offsetPortalPoint(int shape, PortalPoint point, float distance, float halfW, float halfH) {
        float nx;
        float ny;
        if (shape == 1) {
            float xRatio = Math.abs(point.x) / Math.max(0.0001f, halfW);
            float yRatio = Math.abs(point.y) / Math.max(0.0001f, halfH);
            float cornerBlend = 1.0f - Mth.clamp(Math.abs(xRatio - yRatio) / 0.16f, 0.0f, 1.0f);
            if (cornerBlend > 0.0f) {
                nx = Math.signum(point.x);
                ny = Math.signum(point.y);
                float len = Mth.sqrt(nx * nx + ny * ny);
                nx /= len;
                ny /= len;
            } else if (yRatio > xRatio) {
                nx = 0.0f;
                ny = Math.signum(point.y);
            } else {
                nx = Math.signum(point.x);
                ny = 0.0f;
            }
        } else {
            nx = point.x / Math.max(0.0001f, halfW);
            ny = point.y / Math.max(0.0001f, halfH);
            float len = Mth.sqrt(nx * nx + ny * ny);
            if (len <= 0.0001f) {
                nx = 1.0f;
                ny = 0.0f;
            } else {
                nx /= len;
                ny /= len;
            }
        }

        return new PortalPoint(point.x + nx * distance, point.y + ny * distance);
    }

    private static void quadBidirectional(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        float x0,
        float y0,
        float x1,
        float y1,
        float x2,
        float y2,
        float x3,
        float y3,
        float z,
        int light,
        float normalZ,
        int r0,
        int g0,
        int b0,
        int a0,
        int r1,
        int g1,
        int b1,
        int a1,
        int r2,
        int g2,
        int b2,
        int a2,
        int r3,
        int g3,
        int b3,
        int a3
    ) {
        vertex(vc, mat4, normal, x0, y0, z, 0.0f, 0.0f, r0, g0, b0, a0, light, normalZ);
        vertex(vc, mat4, normal, x1, y1, z, 1.0f, 0.0f, r1, g1, b1, a1, light, normalZ);
        vertex(vc, mat4, normal, x2, y2, z, 1.0f, 1.0f, r2, g2, b2, a2, light, normalZ);
        vertex(vc, mat4, normal, x3, y3, z, 0.0f, 1.0f, r3, g3, b3, a3, light, normalZ);

        vertex(vc, mat4, normal, x3, y3, z, 0.0f, 1.0f, r3, g3, b3, a3, light, -normalZ);
        vertex(vc, mat4, normal, x2, y2, z, 1.0f, 1.0f, r2, g2, b2, a2, light, -normalZ);
        vertex(vc, mat4, normal, x1, y1, z, 1.0f, 0.0f, r1, g1, b1, a1, light, -normalZ);
        vertex(vc, mat4, normal, x0, y0, z, 0.0f, 0.0f, r0, g0, b0, a0, light, -normalZ);
    }

    private record PortalPoint(float x, float y) {
    }

    private static void portalVertex(
        VertexConsumer vc,
        Matrix4f mat4,
        float x,
        float y,
        float z
    ) {
        vc.vertex(mat4, x, y, z).endVertex();
    }

    private static void vertex(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        float x,
        float y,
        float z,
        float u,
        float v,
        int r,
        int g,
        int b,
        int a,
        int light,
        float normalZ
    ) {
        vc.vertex(mat4, x, y, z)
            .color(r, g, b, a)
            .uv(u, v)
            .overlayCoords(0)
            .uv2(light)
            .normal(normal, 0.0f, 0.0f, normalZ)
            .endVertex();
    }

    private static void vertex(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        float x,
        float y,
        float z,
        float u,
        float v,
        int r,
        int g,
        int b,
        int a,
        int light,
        float normalX,
        float normalY,
        float normalZ
    ) {
        vc.vertex(mat4, x, y, z)
            .color(r, g, b, a)
            .uv(u, v)
            .overlayCoords(0)
            .uv2(light)
            .normal(normal, normalX, normalY, normalZ)
            .endVertex();
    }
}
