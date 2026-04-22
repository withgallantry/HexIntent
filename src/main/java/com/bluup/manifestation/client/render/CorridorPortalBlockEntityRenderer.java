package com.bluup.manifestation.client.render;

import com.bluup.manifestation.server.block.CorridorPortalBlock;
import com.bluup.manifestation.server.block.CorridorPortalBlockEntity;
import com.bluup.manifestation.server.block.ManifestationBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class CorridorPortalBlockEntityRenderer implements BlockEntityRenderer<CorridorPortalBlockEntity> {
    private static final ResourceLocation PORTAL_SPRITE_ID =
        new ResourceLocation("minecraft", "block/end_portal");

    private static final String[] RUNES = {"ᚠ", "ᚢ", "ᚦ", "ᚨ", "ᚱ", "ᚲ", "ᚹ", "ᛇ", "ᛉ", "ᛟ"};

    private static final int STRIPS = 40;
    private static final float HALF_HEIGHT = 0.78f;
    private static final float HALF_WIDTH = 0.50f;
    private static final float Z_EPSILON = 0.0025f;

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

        if (state.getValue(CorridorPortalBlock.AXIS) == net.minecraft.core.Direction.Axis.X) {
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0f));
        }

        float envelope = blockEntity.renderEnvelope(partialTick);
        if (envelope <= 0.01f) {
            poseStack.popPose();
            return;
        }

        TextureAtlasSprite portalSprite = resolvePortalSprite();
        VertexConsumer vc = buffer.getBuffer(RenderType.translucent());
        float time = (blockEntity.getLevel() == null ? 0f : (blockEntity.getLevel().getGameTime() + partialTick)) * 0.035f;
        float collapseProgress = blockEntity.collapseProgress(partialTick);
        float scale = Mth.clamp(blockEntity.getRenderScale(), 0.1f, 3.0f);

        drawPortalOval(poseStack, vc, packedLight, time, Z_EPSILON, envelope, scale, portalSprite);
        drawPortalOval(poseStack, vc, packedLight, time + 0.11f, -Z_EPSILON, envelope, scale, portalSprite);
        drawRotatingRunes(poseStack, buffer, packedLight, time, envelope, scale);
        drawCollapseSpark(poseStack, vc, packedLight, collapseProgress);

        poseStack.popPose();
    }

    private void drawPortalOval(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float time,
        float z,
        float envelope,
        float scale,
        TextureAtlasSprite sprite
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float portalHalfHeight = HALF_HEIGHT * envelope * scale;
        float portalHalfWidth = HALF_WIDTH * envelope * scale;

        float spriteU0 = sprite.getU0();
        float spriteU1 = sprite.getU1();
        float spriteV0 = sprite.getV0();
        float spriteV1 = sprite.getV1();
        float uInset = (spriteU1 - spriteU0) * 0.03f;
        float vInset = (spriteV1 - spriteV0) * 0.03f;
        float uLeft = spriteU0 + uInset;
        float uRight = spriteU1 - uInset;
        float uMid = (uLeft + uRight) * 0.5f;
        float vBottomBase = spriteV0 + vInset;
        float vTopBase = spriteV1 - vInset;
        float shimmer = (Mth.sin(time * 2.1f) * 0.5f + 0.5f) * (vTopBase - vBottomBase) * 0.10f;

        for (int i = 0; i < STRIPS; i++) {
            float v0 = i / (float) STRIPS;
            float v1 = (i + 1) / (float) STRIPS;

            float y0 = Mth.lerp(v0, -portalHalfHeight, portalHalfHeight);
            float y1 = Mth.lerp(v1, -portalHalfHeight, portalHalfHeight);

            float halfW0 = portalHalfWidth * ellipseWidthFactor(y0 / portalHalfHeight);
            float halfW1 = portalHalfWidth * ellipseWidthFactor(y1 / portalHalfHeight);

            float texV0 = Mth.lerp(v0, vBottomBase, vTopBase) + shimmer;
            float texV1 = Mth.lerp(v1, vBottomBase, vTopBase) + shimmer;

            float edgeRadiusTop = Mth.abs(y0 / portalHalfHeight);
            float edgeRadiusBottom = Mth.abs(y1 / portalHalfHeight);
            float edgeRadius = Math.max(edgeRadiusTop, edgeRadiusBottom);
            // Keep alpha fully solid for most of the oval and fade only near the outer rim.
            float edgeMask = 1.0f - Mth.clamp((edgeRadius - 0.90f) / 0.10f, 0.0f, 1.0f);

            int edgeAlpha = (int) (18 * envelope * edgeMask);
            int coreAlpha = (int) (172 * envelope * edgeMask);

            // Left half strip with fade from edge to center.
            vertex(vc, mat4, normal, -halfW0, y0, z, uLeft, texV0, 80, 92, 128, edgeAlpha, light, 1.0f);
            vertex(vc, mat4, normal, 0.0f, y0, z, uMid, texV0, 188, 214, 255, coreAlpha, light, 1.0f);
            vertex(vc, mat4, normal, 0.0f, y1, z, uMid, texV1, 188, 214, 255, coreAlpha, light, 1.0f);
            vertex(vc, mat4, normal, -halfW1, y1, z, uLeft, texV1, 80, 92, 128, edgeAlpha, light, 1.0f);

            vertex(vc, mat4, normal, -halfW1, y1, z, uLeft, texV1, 80, 92, 128, edgeAlpha, light, -1.0f);
            vertex(vc, mat4, normal, 0.0f, y1, z, uMid, texV1, 188, 214, 255, coreAlpha, light, -1.0f);
            vertex(vc, mat4, normal, 0.0f, y0, z, uMid, texV0, 188, 214, 255, coreAlpha, light, -1.0f);
            vertex(vc, mat4, normal, -halfW0, y0, z, uLeft, texV0, 80, 92, 128, edgeAlpha, light, -1.0f);

            // Right half strip with fade from center to edge.
            vertex(vc, mat4, normal, 0.0f, y0, z, uMid, texV0, 188, 214, 255, coreAlpha, light, 1.0f);
            vertex(vc, mat4, normal, halfW0, y0, z, uRight, texV0, 80, 92, 128, edgeAlpha, light, 1.0f);
            vertex(vc, mat4, normal, halfW1, y1, z, uRight, texV1, 80, 92, 128, edgeAlpha, light, 1.0f);
            vertex(vc, mat4, normal, 0.0f, y1, z, uMid, texV1, 188, 214, 255, coreAlpha, light, 1.0f);

            vertex(vc, mat4, normal, 0.0f, y1, z, uMid, texV1, 188, 214, 255, coreAlpha, light, -1.0f);
            vertex(vc, mat4, normal, halfW1, y1, z, uRight, texV1, 80, 92, 128, edgeAlpha, light, -1.0f);
            vertex(vc, mat4, normal, halfW0, y0, z, uRight, texV0, 80, 92, 128, edgeAlpha, light, -1.0f);
            vertex(vc, mat4, normal, 0.0f, y0, z, uMid, texV0, 188, 214, 255, coreAlpha, light, -1.0f);
        }
    }

    private void drawRotatingRunes(PoseStack poseStack, MultiBufferSource buffer, int light, float time, float envelope, float scale) {
        // Draw the rune ring on both faces so it is readable from either side.
        drawRuneRing(poseStack, buffer, light, time, envelope, scale, Z_EPSILON + 0.0005f, false);
        drawRuneRing(poseStack, buffer, light, time, envelope, scale, -Z_EPSILON - 0.0005f, true);
    }

    private void drawRuneRing(
        PoseStack poseStack,
        MultiBufferSource buffer,
        int light,
        float time,
        float envelope,
        float scale,
        float z,
        boolean flipFacing
    ) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        int runeCount = 16;
        float ringX = (HALF_WIDTH + 0.06f) * envelope * scale;
        float ringY = (HALF_HEIGHT + 0.08f) * envelope * scale;

        for (int i = 0; i < runeCount; i++) {
            float a = time + (Mth.TWO_PI * i / runeCount);
            float x = Mth.cos(a) * ringX;
            float y = Mth.sin(a) * ringY;

            String glyph = RUNES[i % RUNES.length];
            int alpha = Mth.clamp((int) (220 * envelope), 0, 255);
            int color = (alpha << 24) | 0xD8B2FF;

            poseStack.pushPose();
            poseStack.translate(x, y, z);
            if (flipFacing) {
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
            }
            poseStack.scale(0.012f, -0.012f, 0.012f);
            float w = font.width(glyph) / 2.0f;
            Matrix4f mat4 = poseStack.last().pose();
            font.drawInBatch(
                glyph,
                -w,
                0.0f,
                color,
                false,
                mat4,
                buffer,
                Font.DisplayMode.SEE_THROUGH,
                0,
                light
            );
            poseStack.popPose();
        }
    }

    private void drawCollapseSpark(PoseStack poseStack, VertexConsumer vc, int light, float collapseProgress) {
        // Bright implosion spark only in the final part of collapse.
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

        vertex(vc, mat4, normal, -size, -size, Z_EPSILON + 0.0008f, 0.0f, 0.0f, 222, 178, 255, alpha, light, 1.0f);
        vertex(vc, mat4, normal, size, -size, Z_EPSILON + 0.0008f, 1.0f, 0.0f, 236, 203, 255, alpha, light, 1.0f);
        vertex(vc, mat4, normal, size, size, Z_EPSILON + 0.0008f, 1.0f, 1.0f, 255, 230, 255, alpha, light, 1.0f);
        vertex(vc, mat4, normal, -size, size, Z_EPSILON + 0.0008f, 0.0f, 1.0f, 236, 203, 255, alpha, light, 1.0f);

        vertex(vc, mat4, normal, -size, size, Z_EPSILON + 0.0008f, 0.0f, 1.0f, 236, 203, 255, alpha, light, -1.0f);
        vertex(vc, mat4, normal, size, size, Z_EPSILON + 0.0008f, 1.0f, 1.0f, 255, 230, 255, alpha, light, -1.0f);
        vertex(vc, mat4, normal, size, -size, Z_EPSILON + 0.0008f, 1.0f, 0.0f, 236, 203, 255, alpha, light, -1.0f);
        vertex(vc, mat4, normal, -size, -size, Z_EPSILON + 0.0008f, 0.0f, 0.0f, 222, 178, 255, alpha, light, -1.0f);
    }

    private static float ellipseWidthFactor(float normalizedY) {
        float clamped = Mth.clamp(1.0f - (normalizedY * normalizedY), 0.0f, 1.0f);
        return Mth.sqrt(clamped);
    }

    private TextureAtlasSprite resolvePortalSprite() {
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(PORTAL_SPRITE_ID);
        if (sprite == null) {
            return Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getBlockModel(net.minecraft.world.level.block.Blocks.NETHER_PORTAL.defaultBlockState()).getParticleIcon();
        }
        return sprite;
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
}
