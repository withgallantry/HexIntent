package com.bluup.manifestation.client.render;

import at.petrak.hexcasting.client.ClientTickCounter;
import com.mojang.blaze3d.platform.NativeImage;
import com.bluup.manifestation.server.block.CorridorPortalBlock;
import com.bluup.manifestation.server.block.CorridorPortalBlockEntity;
import com.bluup.manifestation.server.block.PermanentThresholdFrame;
import com.bluup.manifestation.server.block.PermanentThresholdFrames;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CorridorPortalBlockEntityRenderer implements BlockEntityRenderer<CorridorPortalBlockEntity> {
    private static final int STRIPS = 40;
    private static final int OUTLINE_SEGMENTS = 64;
    private static final float HALF_HEIGHT = 0.78f;
    private static final float HALF_WIDTH = 0.50f;
    private static final float THRESHOLD_HALF_SIZE = 0.50f;
    private static final float Z_EPSILON = 0.0025f;
    private static final float PERMANENT_CENTER_OFFSET = 0.50f;
    private static final float PERMANENT_HALF_WIDTH = 1.0f;
    private static final float PERMANENT_HALF_HEIGHT = 1.5f;
    private static final float PERMANENT_FRAME_HALF_WIDTH = 2.0f;
    private static final float PERMANENT_FRAME_HALF_HEIGHT = 2.5f;
    private static final float PERMANENT_FRAME_Z = 0.0125f;
    private static final int PERMANENT_BASE_TINT_COLOR = 0x181A2D;
    private static final float PERMANENT_BASE_ALPHA = 0.90f;
    private static final int PERMANENT_MIST_TINT_COLOR = 0x232846;
    private static final float PERMANENT_MIST_ALPHA = 0.18f;
    private static final int PERMANENT_EDGE_TINT_COLOR = 0x6F7CB3;
    private static final float PERMANENT_EDGE_ALPHA = 0.34f;
    private static final int PERMANENT_ACTIVE_EDGE_COLOR = 0x5A6798;
    private static final float PERMANENT_ACTIVE_EDGE_ALPHA = 0.32f;
    private static final float PERMANENT_BASE_IDLE_U_RATE = 0.002f;
    private static final float PERMANENT_BASE_IDLE_V_RATE = -0.001f;
    private static final float PERMANENT_MIST_IDLE_U_RATE = -0.004f;
    private static final float PERMANENT_MIST_IDLE_V_RATE = 0.003f;
    private static final float PERMANENT_EDGE_PULSE_RATE = 0.08f;
    private static final float PERMANENT_OPEN_STAGE_TICKS = 28.0f;
    private static final float PERMANENT_SEAM_START_TICKS = 4.0f;
    private static final float PERMANENT_SEAM_END_TICKS = 8.0f;
    private static final float PERMANENT_WIDTH_START_TICKS = 8.0f;
    private static final float PERMANENT_WIDTH_END_TICKS = 20.0f;
    private static final float PERMANENT_PATCH_START_TICKS = 4.0f;
    private static final float PERMANENT_PATCH_SOFTNESS = 0.20f;
    private static final float PERMANENT_RIM_REVEAL_TICKS = 8.0f;
    private static final float PERMANENT_SEAM_HALF_WIDTH_U = 0.035f;
    private static final float PERMANENT_SEAM_MIN_HALF_WIDTH_U = 0.006f;
    private static final float PERMANENT_SEAM_CENTER_BIAS = 0.18f;
    private static final int PERMANENT_RIM_SEGMENTS = 18;
    private static final float PERMANENT_RIM_SIDE_WIDTH = 0.058f;
    private static final float PERMANENT_RIM_TOP_WIDTH = 0.086f;
    private static final float PERMANENT_RIM_WOBBLE_AMPLITUDE = 0.008f;
    private static final float PERMANENT_RIM_WOBBLE_RATE = 2.05f;
    private static final int PERMANENT_REVEAL_COLUMNS = 24;
    private static final int PERMANENT_REVEAL_ROWS = 36;
    private static final ResourceLocation THRESHOLD_GLYPH_SPRITE = new ResourceLocation("manifestation", "block/spell_circle");
    private static final ResourceLocation PERMANENT_FRAME_GLOW_LEFT = new ResourceLocation("manifestation", "textures/block/permanent_threshold/inner_threshold_glow_left.png");
    private static final ResourceLocation PERMANENT_FRAME_GLOW_RIGHT = new ResourceLocation("manifestation", "textures/block/permanent_threshold/inner_threshold_glow_right.png");
    private static final ResourceLocation PERMANENT_PORTAL_BASE = new ResourceLocation("manifestation", "textures/block/permanent_threshold/portal_base_dark.png");
    private static final ResourceLocation PERMANENT_PORTAL_MIST = new ResourceLocation("manifestation", "textures/block/permanent_threshold/portal_mist_layer.png");
    private static final ResourceLocation PERMANENT_PORTAL_EDGE = new ResourceLocation("manifestation", "textures/block/permanent_threshold/portal_rect_edge_glow.png");
    private static final ResourceLocation PERMANENT_PATCH_REVEAL_MASK = new ResourceLocation("manifestation", "textures/block/permanent_threshold/portal_patch_reveal_mask.png");
    private static final ResourceLocation PERMANENT_PORTAL_RIPPLE = new ResourceLocation("manifestation", "textures/block/permanent_threshold/portal_ripple_lines.png");
    private static final double TICKS_PER_NANO = 20.0 / 1_000_000_000.0;
    private static final double MAX_CLIENT_TICK_LEAD = 8.0;
    private static final double SERVER_RESYNC_THRESHOLD = 2.5;
    private static final Map<String, AnimationClockState> ANIMATION_CLOCKS = new ConcurrentHashMap<>();
    private static AlphaMask permanentPatchRevealMask;

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

        double worldTicks = resolveAnimationTicks(blockEntity, partialTick);
        float envelope = blockEntity.renderEnvelopeAt(worldTicks);
        if (envelope <= 0.01f) {
            poseStack.popPose();
            return;
        }

        VertexConsumer portalVc = buffer.getBuffer(RenderType.endPortal());
        VertexConsumer fxVc = buffer.getBuffer(RenderType.translucent());
        VertexConsumer energyVc = buffer.getBuffer(RenderType.lightning());
        float time = (float) ((worldTicks % 24000.0) * 0.042);
        float collapseProgress = blockEntity.collapseProgressAt(worldTicks);
        float scale = Mth.clamp(blockEntity.getRenderScale(), 0.1f, 3.0f);
        int stableBasePortalColour = blockEntity.getPortalBackdropColor();
        int midColor = blockEntity.getPortalMidColor();
        int highlightColor = blockEntity.getPortalHighlightColor();
        int frameColor = blockEntity.getPortalFrameColor();
        float tintTime = ClientTickCounter.getTotal() / 2.0f;
        Vec3 tintSamplePos = Vec3.atCenterOf(blockEntity.getBlockPos());
        int resolvedTintColor = blockEntity.samplePortalTintColor(tintTime, tintSamplePos);
        int resolvedAccentTint = makePortalAccentTint(resolvedTintColor);

        // Base structural palette.
        int stableRimOuterColour = mixRgb(frameColor, midColor, 0.28f);
        int stableRimInnerColour = highlightColor;
        int stableEdgeVeilColour = mixRgb(midColor, stableBasePortalColour, 0.30f);
        int stableTrailTailColour = mixRgb(midColor, 0x12020D, 0.42f);
        int stableTrailHeadColour = mixRgb(highlightColor, frameColor, 0.24f);
        int stableGlyphOuterColour = mixRgb(frameColor, midColor, 0.46f);
        int stableGlyphInnerColour = mixRgb(highlightColor, frameColor, 0.30f);
        int stableCollapseColour = mixRgb(highlightColor, frameColor, 0.36f);
        int membraneTintColour = mixRgb(stableBasePortalColour, resolvedTintColor, 0.72f);

        // Pigment should lead the outside shell color; keep only a light structural blend-in.
        int rimOuterColour = mixRgb(stableRimOuterColour, resolvedTintColor, 0.74f);
        int rimInnerColour = mixRgb(stableRimInnerColour, resolvedTintColor, 0.70f);
        int edgeVeilColour = mixRgb(stableEdgeVeilColour, resolvedTintColor, 0.68f);
        int trailTailColour = mixRgb(stableTrailTailColour, resolvedAccentTint, 0.62f);
        int trailHeadColour = mixRgb(stableTrailHeadColour, resolvedTintColor, 0.72f);
        int glyphOuterColour = mixRgb(stableGlyphOuterColour, resolvedTintColor, 0.76f);
        int glyphInnerColour = mixRgb(stableGlyphInnerColour, resolvedTintColor, 0.74f);
        int collapseColour = mixRgb(stableCollapseColour, resolvedTintColor, 0.70f);

        // Internal membrane still receives the strongest tint influence.
        int internalAccentColour = mixRgb(0x180410, resolvedAccentTint, 0.86f);

        if (blockEntity.isPermanentFrameMode()) {
            if (!shouldRenderPermanentPortal(blockEntity, state)) {
                poseStack.popPose();
                return;
            }

            drawPermanentThresholdPortal(
                blockEntity,
                poseStack,
                buffer,
                packedLight,
                scale,
                state.getValue(CorridorPortalBlock.AXIS),
                blockEntity.getRenderYawDegrees(),
                worldTicks,
                time,
                collapseProgress,
                membraneTintColour,
                internalAccentColour,
                edgeVeilColour,
                trailTailColour,
                trailHeadColour,
                rimOuterColour,
                rimInnerColour
            );
            if (!blockEntity.isReplacementCollapseMode()) {
                drawCollapseSpark(poseStack, energyVc, packedLight, collapseProgress, collapseColour);
            }
            poseStack.popPose();
            renderPortalLabel(blockEntity, poseStack, buffer, packedLight, scale);
            return;
        }

        // Jagged tear around portal. This took a lot to get looking so so lol.
        drawPortalTear(poseStack, portalVc, Z_EPSILON, envelope, scale, time);
        drawPortalTear(poseStack, portalVc, -Z_EPSILON, envelope, scale, time + 1.7f);
        drawMembraneTint(poseStack, fxVc, packedLight, envelope, scale, time, 0, membraneTintColour);
        drawInternalPortalAccent(poseStack, energyVc, packedLight, envelope, scale, time, 0, internalAccentColour);
        drawEdgeVeil(poseStack, fxVc, packedLight, envelope, scale, time, 0, edgeVeilColour);
        drawPurpleGlow(poseStack, energyVc, packedLight, envelope, scale, time, 0, rimOuterColour, rimInnerColour);
        drawCollapseSpark(poseStack, energyVc, packedLight, collapseProgress, collapseColour);

        poseStack.popPose();
        renderPortalLabel(blockEntity, poseStack, buffer, packedLight, scale);
    }

    private boolean shouldRenderPermanentPortal(CorridorPortalBlockEntity blockEntity, BlockState state) {
        if (blockEntity.getLevel() == null || !state.hasProperty(CorridorPortalBlock.AXIS)) {
            return true;
        }

        PermanentThresholdFrame frame = PermanentThresholdFrames.INSTANCE.findContaining(
            blockEntity.getLevel(),
            blockEntity.getBlockPos(),
            state.getValue(CorridorPortalBlock.AXIS)
        );
        if (frame == null) {
            BlockPos anchorPos = blockEntity.getCachedPermanentFrameAnchorPos();
            if (anchorPos != null) {
                return anchorPos.equals(blockEntity.getBlockPos());
            }
            return true;
        }

        return PermanentThresholdFrames.INSTANCE.isAnchorBlock(frame, blockEntity.getBlockPos());
    }

    private void drawPermanentThresholdPortal(
        CorridorPortalBlockEntity blockEntity,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int light,
        float scale,
        Direction.Axis axis,
        float renderYawDegrees,
        double worldTicks,
        float time,
        float collapseProgress,
        int membraneTintColour,
        int internalAccentColour,
        int edgeVeilColour,
        int trailTailColour,
        int trailHeadColour,
        int rimOuterColour,
        int rimInnerColour
    ) {
        poseStack.pushPose();
        poseStack.translate(resolvePermanentCenterOffset(axis, renderYawDegrees) * scale, 0.0f, 0.0f);

        float baseHalfW = HALF_WIDTH * scale;
        float baseHalfH = HALF_HEIGHT * scale;
        if (baseHalfW <= 0.0001f || baseHalfH <= 0.0001f) {
            poseStack.popPose();
            return;
        }

        boolean replacementClosing = blockEntity.isReplacementCollapseMode();
        float activationAge = replacementClosing
            ? resolvePermanentReverseActivationAge(collapseProgress)
            : resolvePermanentActivationAge(blockEntity, worldTicks);
        VertexConsumer portalVc = buffer.getBuffer(RenderType.endPortal());
        VertexConsumer fxVc = buffer.getBuffer(RenderType.translucent());
        VertexConsumer energyVc = buffer.getBuffer(RenderType.lightning());
        if (!replacementClosing && activationAge >= PERMANENT_OPEN_STAGE_TICKS) {
            float rimRevealProgress = resolvePermanentRimRevealProgress(blockEntity, activationAge);
            drawPermanentCorridorSquarePortal(
                poseStack,
            portalVc,
                fxVc,
                energyVc,
                light,
                scale,
                time,
                membraneTintColour,
                internalAccentColour,
                edgeVeilColour,
                trailTailColour,
                trailHeadColour,
                rimOuterColour,
                rimInnerColour,
                rimRevealProgress
            );
            poseStack.popPose();
            return;
        }

        if (activationAge < PERMANENT_SEAM_START_TICKS) {
            poseStack.popPose();
            return;
        }

        AlphaMask currentMask = getPermanentPatchRevealMask();
        if (currentMask == null) {
            drawPermanentCorridorSquarePortal(
                poseStack,
                portalVc,
                fxVc,
                energyVc,
                light,
                scale,
                time,
                membraneTintColour,
                internalAccentColour,
                edgeVeilColour,
                trailTailColour,
                trailHeadColour,
                rimOuterColour,
                rimInnerColour,
                1.0f
            );
            poseStack.popPose();
            return;
        }

        float previousAge = replacementClosing
            ? Math.min(activationAge + 1.0f, PERMANENT_OPEN_STAGE_TICKS)
            : Math.max(activationAge - 1.0f, 0.0f);
        AlphaMask previousMask = currentMask;

        float currentHalfWidth = resolvePermanentOpeningHalfWidth(activationAge);
        float previousHalfWidth = resolvePermanentOpeningHalfWidth(previousAge);
        float currentPatchProgress = resolvePermanentPatchProgress(activationAge);
        float previousPatchProgress = resolvePermanentPatchProgress(previousAge);
        // Reintroduce close-phase rim fade, but keep it constrained so replacement collapse
        // does not flash a full-frame outline at oblique angles.
        float transientRimReveal = 0.0f;
        if (replacementClosing) {
            float closingFade = 1.0f - Mth.clamp(collapseProgress, 0.0f, 1.0f);
            float widthFactor = Mth.clamp(currentHalfWidth / 0.5f, 0.0f, 1.0f);
            transientRimReveal = 0.7f * easeOutCubic(closingFade) * widthFactor;
        }

        drawPermanentOpeningCorridorPortal(
            poseStack,
            portalVc,
            fxVc,
            energyVc,
            light,
            scale,
            currentMask,
            previousMask,
            currentHalfWidth,
            previousHalfWidth,
            currentPatchProgress,
            previousPatchProgress,
            worldTicks,
            membraneTintColour,
            internalAccentColour,
            rimInnerColour,
            !replacementClosing,
            transientRimReveal,
            edgeVeilColour,
            rimOuterColour
        );

        poseStack.popPose();
    }

    private void drawPermanentCorridorSquarePortal(
        PoseStack poseStack,
        VertexConsumer portalVc,
        VertexConsumer fxVc,
        VertexConsumer energyVc,
        int light,
        float scale,
        float time,
        int membraneTintColour,
        int internalAccentColour,
        int edgeVeilColour,
        int trailTailColour,
        int trailHeadColour,
        int rimOuterColour,
        int rimInnerColour,
        float rimRevealProgress
    ) {
        poseStack.pushPose();
        poseStack.scale(PERMANENT_HALF_WIDTH / HALF_WIDTH, PERMANENT_HALF_HEIGHT / HALF_HEIGHT, 1.0f);

        drawPortalSquare(poseStack, portalVc, Z_EPSILON, 1.0f, scale);
        drawPortalSquare(poseStack, portalVc, -Z_EPSILON, 1.0f, scale);
        drawMembraneTint(poseStack, fxVc, light, 1.0f, scale, time, 1, membraneTintColour);
        drawInternalPortalAccent(poseStack, energyVc, light, 1.0f, scale, time, 1, internalAccentColour);
        drawPermanentSquareInnerRim(poseStack, fxVc, energyVc, light, scale, time, edgeVeilColour, rimOuterColour, rimInnerColour, rimRevealProgress);

        poseStack.popPose();
    }

    private void drawPermanentOpeningCorridorPortal(
        PoseStack poseStack,
        VertexConsumer portalVc,
        VertexConsumer fxVc,
        VertexConsumer energyVc,
        int light,
        float scale,
        AlphaMask currentMask,
        AlphaMask previousMask,
        float currentHalfWidth,
        float previousHalfWidth,
        float currentPatchProgress,
        float previousPatchProgress,
        double worldTicks,
        int membraneTintColour,
        int internalAccentColour,
        int activeEdgeColour,
        boolean renderActiveBand,
        float transientRimReveal,
        int rimEdgeColour,
        int rimOuterColour
    ) {
        poseStack.pushPose();
        poseStack.scale(PERMANENT_HALF_WIDTH / HALF_WIDTH, PERMANENT_HALF_HEIGHT / HALF_HEIGHT, 1.0f);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();
        float halfW = HALF_WIDTH * scale;
        float halfH = HALF_HEIGHT * scale;

        drawMaskedPermanentPortalSurface(portalVc, mat4, halfW, halfH, Z_EPSILON, currentMask, currentHalfWidth, currentPatchProgress);
        drawMaskedPermanentPortalSurface(portalVc, mat4, halfW, halfH, -Z_EPSILON, currentMask, currentHalfWidth, currentPatchProgress);
        drawMaskedPermanentColorLayer(fxVc, mat4, normal, light, halfW, halfH, 0.0f, membraneTintColour, 0.72f, currentMask, currentHalfWidth, currentPatchProgress);
        drawMaskedPermanentColorLayer(energyVc, mat4, normal, light, halfW, halfH, Z_EPSILON + 0.0012f, internalAccentColour, 0.12f, currentMask, currentHalfWidth, currentPatchProgress);
        if (transientRimReveal > 0.001f) {
            drawPermanentSquareInnerRim(
                poseStack,
                fxVc,
                energyVc,
                light,
                scale,
                (float) (worldTicks * 0.042),
                rimEdgeColour,
                rimOuterColour,
                activeEdgeColour,
                transientRimReveal
            );
        }
        if (renderActiveBand) {
            drawPermanentActiveBand(
                energyVc,
                mat4,
                normal,
                light,
                halfW,
                halfH,
                Z_EPSILON + 0.0025f,
                currentMask,
                previousMask,
                currentHalfWidth,
                previousHalfWidth,
                currentPatchProgress,
                previousPatchProgress,
                worldTicks,
                activeEdgeColour,
                0.42f
            );
        }

        poseStack.popPose();
    }

    private void drawPermanentSquareInnerRim(
        PoseStack poseStack,
        VertexConsumer veilVc,
        VertexConsumer glowVc,
        int light,
        float scale,
        float time,
        int edgeColor,
        int outerColor,
        int innerColor,
        float revealProgress
    ) {
        if (revealProgress <= 0.001f) {
            return;
        }

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();
        float halfW = HALF_WIDTH * scale;
        float halfH = HALF_HEIGHT * scale;
        if (halfW <= 0.0001f || halfH <= 0.0001f) {
            return;
        }

        float glowPulse = 0.74f + (0.18f * Mth.sin(time * 2.1f)) + (0.08f * Mth.sin(time * 6.2f));
        float veilPulse = 0.72f + (0.14f * Mth.sin(time * 1.9f)) + (0.06f * Mth.sin(time * 4.7f));
        int glowOuterAlpha = Mth.clamp((int) (112f * glowPulse * revealProgress), 0, 255);
        int glowInnerAlpha = Mth.clamp((int) (188f * glowPulse * revealProgress), 0, 255);
        int veilOuterAlpha = Mth.clamp((int) (74f * veilPulse * revealProgress), 0, 255);
        int veilInnerColor = mixRgb(0x0B0108, edgeColor, 0.40f);

        drawPermanentSquareInnerRimPass(glowVc, mat4, normal, light, halfW, halfH, scale, Z_EPSILON + 0.0014f, 1.0f, time, revealProgress, outerColor, glowOuterAlpha, innerColor, glowInnerAlpha);
        drawPermanentSquareInnerRimPass(glowVc, mat4, normal, light, halfW, halfH, scale, -Z_EPSILON - 0.0014f, -1.0f, time, revealProgress, outerColor, glowOuterAlpha, innerColor, glowInnerAlpha);
        drawPermanentSquareInnerRimPass(veilVc, mat4, normal, light, halfW, halfH, scale, Z_EPSILON + 0.0011f, 1.0f, time, revealProgress, edgeColor, veilOuterAlpha, veilInnerColor, 0);
        drawPermanentSquareInnerRimPass(veilVc, mat4, normal, light, halfW, halfH, scale, -Z_EPSILON - 0.0011f, -1.0f, time, revealProgress, edgeColor, veilOuterAlpha, veilInnerColor, 0);
    }

    private void drawPermanentSquareInnerRimPass(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        int light,
        float halfW,
        float halfH,
        float scale,
        float z,
        float nz,
        float time,
        float revealProgress,
        int outerColor,
        int outerAlpha,
        int innerColor,
        int innerAlpha
    ) {
        if (outerAlpha <= 0 && innerAlpha <= 0) {
            return;
        }

        int outerR = (outerColor >> 16) & 0xFF;
        int outerG = (outerColor >> 8) & 0xFF;
        int outerB = outerColor & 0xFF;
        int innerR = (innerColor >> 16) & 0xFF;
        int innerG = (innerColor >> 8) & 0xFF;
        int innerB = innerColor & 0xFF;

        float cornerSideInset = PERMANENT_RIM_SIDE_WIDTH * scale * revealProgress;
        float cornerTopInset = PERMANENT_RIM_TOP_WIDTH * scale * revealProgress;

        for (int i = 0; i < PERMANENT_RIM_SEGMENTS; i++) {
            float t0 = i / (float) PERMANENT_RIM_SEGMENTS;
            float t1 = (i + 1) / (float) PERMANENT_RIM_SEGMENTS;

            float y0 = Mth.lerp(t0, -halfH, halfH);
            float y1 = Mth.lerp(t1, -halfH, halfH);
            y0 = Mth.clamp(y0, -halfH + cornerTopInset, halfH - cornerTopInset);
            y1 = Mth.clamp(y1, -halfH + cornerTopInset, halfH - cornerTopInset);
            if (y1 <= y0) {
                continue;
            }
            float yRatio0 = Mth.lerp(t0, -1.0f, 1.0f);
            float yRatio1 = Mth.lerp(t1, -1.0f, 1.0f);
            float leftInset0 = resolvePermanentSquareRimInset(PERMANENT_RIM_SIDE_WIDTH * scale, yRatio0, time, revealProgress, 0.0f);
            float leftInset1 = resolvePermanentSquareRimInset(PERMANENT_RIM_SIDE_WIDTH * scale, yRatio1, time, revealProgress, 0.0f);
            float rightInset0 = resolvePermanentSquareRimInset(PERMANENT_RIM_SIDE_WIDTH * scale, yRatio0, time, revealProgress, 1.7f);
            float rightInset1 = resolvePermanentSquareRimInset(PERMANENT_RIM_SIDE_WIDTH * scale, yRatio1, time, revealProgress, 1.7f);

            quadBidirectional(
                vc,
                mat4,
                normal,
                -halfW, y0,
                -halfW + leftInset0, y0,
                -halfW + leftInset1, y1,
                -halfW, y1,
                z,
                light,
                nz,
                outerR, outerG, outerB, outerAlpha,
                innerR, innerG, innerB, innerAlpha,
                innerR, innerG, innerB, innerAlpha,
                outerR, outerG, outerB, outerAlpha
            );

            quadBidirectional(
                vc,
                mat4,
                normal,
                halfW - rightInset0, y0,
                halfW, y0,
                halfW, y1,
                halfW - rightInset1, y1,
                z,
                light,
                nz,
                innerR, innerG, innerB, innerAlpha,
                outerR, outerG, outerB, outerAlpha,
                outerR, outerG, outerB, outerAlpha,
                innerR, innerG, innerB, innerAlpha
            );

            float x0 = Mth.lerp(t0, -halfW, halfW);
            float x1 = Mth.lerp(t1, -halfW, halfW);
            x0 = Mth.clamp(x0, -halfW + cornerSideInset, halfW - cornerSideInset);
            x1 = Mth.clamp(x1, -halfW + cornerSideInset, halfW - cornerSideInset);
            if (x1 <= x0) {
                continue;
            }
            float xRatio0 = Mth.lerp(t0, -1.0f, 1.0f);
            float xRatio1 = Mth.lerp(t1, -1.0f, 1.0f);
            float topInset0 = resolvePermanentSquareRimInset(PERMANENT_RIM_TOP_WIDTH * scale, xRatio0, time, revealProgress, 3.1f);
            float topInset1 = resolvePermanentSquareRimInset(PERMANENT_RIM_TOP_WIDTH * scale, xRatio1, time, revealProgress, 3.1f);
            float bottomInset0 = resolvePermanentSquareRimInset(PERMANENT_RIM_TOP_WIDTH * scale, xRatio0, time, revealProgress, 4.6f);
            float bottomInset1 = resolvePermanentSquareRimInset(PERMANENT_RIM_TOP_WIDTH * scale, xRatio1, time, revealProgress, 4.6f);

            quadBidirectional(
                vc,
                mat4,
                normal,
                x0, halfH - topInset0,
                x0, halfH,
                x1, halfH,
                x1, halfH - topInset1,
                z,
                light,
                nz,
                innerR, innerG, innerB, innerAlpha,
                outerR, outerG, outerB, outerAlpha,
                outerR, outerG, outerB, outerAlpha,
                innerR, innerG, innerB, innerAlpha
            );

            quadBidirectional(
                vc,
                mat4,
                normal,
                x0, -halfH,
                x0, -halfH + bottomInset0,
                x1, -halfH + bottomInset1,
                x1, -halfH,
                z,
                light,
                nz,
                outerR, outerG, outerB, outerAlpha,
                innerR, innerG, innerB, innerAlpha,
                innerR, innerG, innerB, innerAlpha,
                outerR, outerG, outerB, outerAlpha
            );
        }

        drawPermanentSquareRimCorner(vc, mat4, normal, light, z, nz, -1.0f, 1.0f, halfW, halfH, cornerSideInset, cornerTopInset, outerR, outerG, outerB, outerAlpha, innerR, innerG, innerB, innerAlpha);
        drawPermanentSquareRimCorner(vc, mat4, normal, light, z, nz, 1.0f, 1.0f, halfW, halfH, cornerSideInset, cornerTopInset, outerR, outerG, outerB, outerAlpha, innerR, innerG, innerB, innerAlpha);
        drawPermanentSquareRimCorner(vc, mat4, normal, light, z, nz, -1.0f, -1.0f, halfW, halfH, cornerSideInset, cornerTopInset, outerR, outerG, outerB, outerAlpha, innerR, innerG, innerB, innerAlpha);
        drawPermanentSquareRimCorner(vc, mat4, normal, light, z, nz, 1.0f, -1.0f, halfW, halfH, cornerSideInset, cornerTopInset, outerR, outerG, outerB, outerAlpha, innerR, innerG, innerB, innerAlpha);
    }

    private void drawPermanentSquareRimCorner(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        int light,
        float z,
        float nz,
        float sx,
        float sy,
        float halfW,
        float halfH,
        float sideInset,
        float topInset,
        int outerR,
        int outerG,
        int outerB,
        int outerAlpha,
        int innerR,
        int innerG,
        int innerB,
        int innerAlpha
    ) {
        if (sideInset <= 0.0001f && topInset <= 0.0001f) {
            return;
        }

        float xOuter = sx * halfW;
        float yOuter = sy * halfH;
        float xInner = xOuter - (sx * sideInset);
        float yInner = yOuter - (sy * topInset);

        quadBidirectional(
            vc,
            mat4,
            normal,
            xOuter, yInner,
            xOuter, yOuter,
            xInner, yOuter,
            xInner, yInner,
            z,
            light,
            nz,
            outerR, outerG, outerB, outerAlpha,
            outerR, outerG, outerB, outerAlpha,
            outerR, outerG, outerB, outerAlpha,
            innerR, innerG, innerB, innerAlpha
        );
    }

    private float resolvePermanentSquareRimInset(float baseWidth, float normalizedPosition, float time, float revealProgress, float phase) {
        float cornerFade = 1.0f - Mth.clamp(Math.abs(normalizedPosition), 0.0f, 1.0f);
        float middleWeight = cornerFade * cornerFade;
        float wobble = PERMANENT_RIM_WOBBLE_AMPLITUDE * revealProgress * middleWeight * Mth.sin((time * PERMANENT_RIM_WOBBLE_RATE) + (normalizedPosition * 6.4f) + phase);
        return Math.max(0.0001f, (baseWidth * revealProgress) + wobble);
    }

    private float resolvePermanentCenterOffset(Direction.Axis axis, float renderYawDegrees) {
        double yawRad = Math.toRadians(renderYawDegrees);
        double horizontalAlignment = axis == Direction.Axis.Z ? Math.cos(yawRad) : Math.sin(yawRad);
        return horizontalAlignment >= 0.0 ? PERMANENT_CENTER_OFFSET : -PERMANENT_CENTER_OFFSET;
    }

    private void drawPermanentTexturedLayer(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        int light,
        float halfW,
        float halfH,
        float z,
        int rgb,
        float alpha,
        float uOffset,
        float vOffset,
        float alphaScale
    ) {
        float finalAlpha = alpha * alphaScale;
        if (finalAlpha <= 0.001f) {
            return;
        }

        int a = Mth.clamp((int) (255.0f * finalAlpha), 0, 255);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        texturedQuadBidirectional(
            vc,
            mat4,
            normal,
            -halfW, -halfH,
            halfW, -halfH,
            halfW, halfH,
            -halfW, halfH,
            z,
            light,
            1.0f,
            uOffset, vOffset,
            1.0f + uOffset, vOffset,
            1.0f + uOffset, 1.0f + vOffset,
            uOffset, 1.0f + vOffset,
            r, g, b, a
        );
    }

    private void drawMaskedPermanentTexturedLayer(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        int light,
        float halfW,
        float halfH,
        float z,
        int rgb,
        float alpha,
        AlphaMask mask,
        float visibleHalfWidth,
        float patchProgress
    ) {
        if (alpha <= 0.001f || mask == null || visibleHalfWidth <= 0.0f) {
            return;
        }

        int baseAlpha = Mth.clamp((int) (255.0f * alpha), 0, 255);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float cellHalfWidth = 0.5f / PERMANENT_REVEAL_COLUMNS;

        for (int row = 0; row < PERMANENT_REVEAL_ROWS; row++) {
            float v0 = row / (float) PERMANENT_REVEAL_ROWS;
            float v1 = (row + 1) / (float) PERMANENT_REVEAL_ROWS;
            float y0 = Mth.lerp(v0, -halfH, halfH);
            float y1 = Mth.lerp(v1, -halfH, halfH);
            float sampleV = (v0 + v1) * 0.5f;

            for (int column = 0; column < PERMANENT_REVEAL_COLUMNS; column++) {
                float u0 = column / (float) PERMANENT_REVEAL_COLUMNS;
                float u1 = (column + 1) / (float) PERMANENT_REVEAL_COLUMNS;
                float sampleU = (u0 + u1) * 0.5f;
                float reveal = samplePermanentReveal(mask, sampleU, sampleV, visibleHalfWidth, cellHalfWidth, patchProgress);
                if (reveal <= 0.02f) {
                    continue;
                }

                int cellAlpha = Mth.clamp((int) (baseAlpha * reveal), 0, 255);
                if (cellAlpha <= 0) {
                    continue;
                }

                float x0 = Mth.lerp(u0, -halfW, halfW);
                float x1 = Mth.lerp(u1, -halfW, halfW);
                texturedQuadBidirectional(
                    vc,
                    mat4,
                    normal,
                    x0, y0,
                    x1, y0,
                    x1, y1,
                    x0, y1,
                    z,
                    light,
                    1.0f,
                    u0, v0,
                    u1, v0,
                    u1, v1,
                    u0, v1,
                    r, g, b, cellAlpha
                );
            }
        }
    }

    private void drawMaskedPermanentPortalSurface(
        VertexConsumer vc,
        Matrix4f mat4,
        float halfW,
        float halfH,
        float z,
        AlphaMask mask,
        float visibleHalfWidth,
        float patchProgress
    ) {
        if (mask == null || visibleHalfWidth <= 0.0f) {
            return;
        }

        float cellHalfWidth = 0.5f / PERMANENT_REVEAL_COLUMNS;
        for (int row = 0; row < PERMANENT_REVEAL_ROWS; row++) {
            float v0 = row / (float) PERMANENT_REVEAL_ROWS;
            float v1 = (row + 1) / (float) PERMANENT_REVEAL_ROWS;
            float y0 = Mth.lerp(v0, -halfH, halfH);
            float y1 = Mth.lerp(v1, -halfH, halfH);
            float sampleV = (v0 + v1) * 0.5f;

            for (int column = 0; column < PERMANENT_REVEAL_COLUMNS; column++) {
                float u0 = column / (float) PERMANENT_REVEAL_COLUMNS;
                float u1 = (column + 1) / (float) PERMANENT_REVEAL_COLUMNS;
                float sampleU = (u0 + u1) * 0.5f;
                float reveal = samplePermanentReveal(mask, sampleU, sampleV, visibleHalfWidth, cellHalfWidth, patchProgress);
                if (reveal <= 0.02f) {
                    continue;
                }

                float x0 = Mth.lerp(u0, -halfW, halfW);
                float x1 = Mth.lerp(u1, -halfW, halfW);
                portalQuad(vc, mat4, x0, y0, x1, y1, z);
            }
        }
    }

    private void drawMaskedPermanentColorLayer(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        int light,
        float halfW,
        float halfH,
        float z,
        int rgb,
        float alpha,
        AlphaMask mask,
        float visibleHalfWidth,
        float patchProgress
    ) {
        if (alpha <= 0.001f || mask == null || visibleHalfWidth <= 0.0f) {
            return;
        }

        int baseAlpha = Mth.clamp((int) (255.0f * alpha), 0, 255);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float cellHalfWidth = 0.5f / PERMANENT_REVEAL_COLUMNS;

        for (int row = 0; row < PERMANENT_REVEAL_ROWS; row++) {
            float v0 = row / (float) PERMANENT_REVEAL_ROWS;
            float v1 = (row + 1) / (float) PERMANENT_REVEAL_ROWS;
            float y0 = Mth.lerp(v0, -halfH, halfH);
            float y1 = Mth.lerp(v1, -halfH, halfH);
            float sampleV = (v0 + v1) * 0.5f;

            for (int column = 0; column < PERMANENT_REVEAL_COLUMNS; column++) {
                float u0 = column / (float) PERMANENT_REVEAL_COLUMNS;
                float u1 = (column + 1) / (float) PERMANENT_REVEAL_COLUMNS;
                float sampleU = (u0 + u1) * 0.5f;
                float reveal = samplePermanentReveal(mask, sampleU, sampleV, visibleHalfWidth, cellHalfWidth, patchProgress);
                if (reveal <= 0.02f) {
                    continue;
                }

                int cellAlpha = Mth.clamp((int) (baseAlpha * reveal), 0, 255);
                if (cellAlpha <= 0) {
                    continue;
                }

                float x0 = Mth.lerp(u0, -halfW, halfW);
                float x1 = Mth.lerp(u1, -halfW, halfW);
                quadBidirectional(
                    vc,
                    mat4,
                    normal,
                    x0, y0,
                    x1, y0,
                    x1, y1,
                    x0, y1,
                    z,
                    light,
                    1.0f,
                    r, g, b, cellAlpha,
                    r, g, b, cellAlpha,
                    r, g, b, cellAlpha,
                    r, g, b, cellAlpha
                );
            }
        }
    }

    private void drawPermanentActiveBand(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        int light,
        float halfW,
        float halfH,
        float z,
        AlphaMask currentMask,
        AlphaMask previousMask,
        float currentHalfWidth,
        float previousHalfWidth,
        float currentPatchProgress,
        float previousPatchProgress,
        double worldTicks,
        int activeEdgeColour,
        float activeEdgeAlpha
    ) {
        if (currentMask == null || previousMask == null || currentHalfWidth <= 0.0f) {
            return;
        }

        int r = (activeEdgeColour >> 16) & 0xFF;
        int g = (activeEdgeColour >> 8) & 0xFF;
        int b = activeEdgeColour & 0xFF;
        int baseAlpha = Mth.clamp((int) (255.0f * activeEdgeAlpha * (0.82f + (0.18f * Mth.sin((float) (worldTicks * 0.42))))), 0, 255);
        float cellHalfWidth = 0.5f / PERMANENT_REVEAL_COLUMNS;

        for (int row = 0; row < PERMANENT_REVEAL_ROWS; row++) {
            float v0 = row / (float) PERMANENT_REVEAL_ROWS;
            float v1 = (row + 1) / (float) PERMANENT_REVEAL_ROWS;
            float y0 = Mth.lerp(v0, -halfH, halfH);
            float y1 = Mth.lerp(v1, -halfH, halfH);
            float sampleV = (v0 + v1) * 0.5f;

            for (int column = 0; column < PERMANENT_REVEAL_COLUMNS; column++) {
                float u0 = column / (float) PERMANENT_REVEAL_COLUMNS;
                float u1 = (column + 1) / (float) PERMANENT_REVEAL_COLUMNS;
                float sampleU = (u0 + u1) * 0.5f;

                float currentReveal = samplePermanentReveal(currentMask, sampleU, sampleV, currentHalfWidth, cellHalfWidth, currentPatchProgress);
                float previousReveal = samplePermanentReveal(previousMask, sampleU, sampleV, previousHalfWidth, cellHalfWidth, previousPatchProgress);
                float activeBand = Mth.clamp((currentReveal - previousReveal) * 2.8f, 0.0f, 1.0f);
                if (activeBand <= 0.02f) {
                    continue;
                }

                int alpha = Mth.clamp((int) (baseAlpha * activeBand), 0, 255);
                if (alpha <= 0) {
                    continue;
                }

                float x0 = Mth.lerp(u0, -halfW, halfW);
                float x1 = Mth.lerp(u1, -halfW, halfW);
                quadBidirectional(
                    vc,
                    mat4,
                    normal,
                    x0, y0,
                    x1, y0,
                    x1, y1,
                    x0, y1,
                    z,
                    light,
                    1.0f,
                    r, g, b, 0,
                    r, g, b, alpha,
                    r, g, b, alpha,
                    r, g, b, 0
                );
            }
        }
    }

    private float samplePermanentReveal(AlphaMask mask, float u, float v, float visibleHalfWidth, float cellHalfWidth, float patchProgress) {
        if (mask == null) {
            return 0.0f;
        }

        float seamDistance = Math.abs(u - 0.5f);
        if (seamDistance > visibleHalfWidth + cellHalfWidth) {
            return 0.0f;
        }

        float maskValue = mask.sample(u, v);
        float seamSpan = Math.max(visibleHalfWidth + cellHalfWidth, 0.0001f);
        float centerBias = 1.0f - Mth.clamp(seamDistance / seamSpan, 0.0f, 1.0f);
        float biasedMaskValue = Mth.clamp(maskValue - (PERMANENT_SEAM_CENTER_BIAS * centerBias), 0.0f, 1.0f);
        return Mth.clamp((patchProgress - biasedMaskValue + PERMANENT_PATCH_SOFTNESS) / PERMANENT_PATCH_SOFTNESS, 0.0f, 1.0f);
    }

    private float resolvePermanentActivationAge(CorridorPortalBlockEntity blockEntity, double worldTicks) {
        long openedAt = blockEntity.getOpenedAtGameTime();
        if (openedAt <= 0L) {
            return PERMANENT_OPEN_STAGE_TICKS;
        }

        return (float) Math.max(0.0, worldTicks - openedAt);
    }

    private float resolvePermanentReverseActivationAge(float collapseProgress) {
        float closingProgress = Mth.clamp(collapseProgress, 0.0f, 1.0f);
        return PERMANENT_OPEN_STAGE_TICKS * (1.0f - closingProgress);
    }

    private float resolvePermanentRimRevealProgress(CorridorPortalBlockEntity blockEntity, float activationAge) {
        if (blockEntity.getOpenedAtGameTime() <= 0L) {
            return 1.0f;
        }

        float revealProgress = Mth.clamp(
            (activationAge - PERMANENT_OPEN_STAGE_TICKS) / PERMANENT_RIM_REVEAL_TICKS,
            0.0f,
            1.0f
        );
        return easeOutCubic(revealProgress);
    }

    private float resolvePermanentOpeningHalfWidth(float activationAge) {
        if (activationAge < PERMANENT_SEAM_START_TICKS) {
            return 0.0f;
        }

        if (activationAge < PERMANENT_SEAM_END_TICKS) {
            float seamProgress = easeOutCubic(Mth.clamp(
                (activationAge - PERMANENT_SEAM_START_TICKS) / (PERMANENT_SEAM_END_TICKS - PERMANENT_SEAM_START_TICKS),
                0.0f,
                1.0f
            ));
            return Mth.lerp(seamProgress, PERMANENT_SEAM_MIN_HALF_WIDTH_U, PERMANENT_SEAM_HALF_WIDTH_U);
        }

        float widthProgress = easeOutCubic(Mth.clamp(
            (activationAge - PERMANENT_WIDTH_START_TICKS) / (PERMANENT_WIDTH_END_TICKS - PERMANENT_WIDTH_START_TICKS),
            0.0f,
            1.0f
        ));
        return Math.max(PERMANENT_SEAM_HALF_WIDTH_U, 0.5f * widthProgress);
    }

    private float resolvePermanentPatchProgress(float activationAge) {
        return Mth.clamp(
            (activationAge - PERMANENT_PATCH_START_TICKS) / (PERMANENT_OPEN_STAGE_TICKS - PERMANENT_PATCH_START_TICKS),
            0.0f,
            1.0f
        );
    }

    private float easeOutCubic(float t) {
        float clamped = Mth.clamp(t, 0.0f, 1.0f);
        float inverse = 1.0f - clamped;
        return 1.0f - (inverse * inverse * inverse);
    }

    private AlphaMask getPermanentPatchRevealMask() {
        if (permanentPatchRevealMask != null) {
            return permanentPatchRevealMask;
        }

        permanentPatchRevealMask = loadAlphaMask(PERMANENT_PATCH_REVEAL_MASK);
        return permanentPatchRevealMask;
    }

    private AlphaMask loadAlphaMask(ResourceLocation texture) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }

        var resource = mc.getResourceManager().getResource(texture);
        if (resource.isEmpty()) {
            return null;
        }

        try (var stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            return AlphaMask.from(image);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void drawDissolveLayer(
        VertexConsumer vc,
        Matrix4f mat4,
        Matrix3f normal,
        int light,
        float halfW,
        float halfH,
        float z,
        float time,
        float widthProgress,
        float openProgress,
        int rgb,
        float alpha,
        float scrollU,
        float scrollV,
        float noiseSeed
    ) {
        int strips = 28;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        for (int i = 0; i < strips; i++) {
            float t0 = i / (float) strips;
            float t1 = (i + 1) / (float) strips;
            float x0 = Mth.lerp(t0, -halfW, halfW);
            float x1 = Mth.lerp(t1, -halfW, halfW);
            float center = (x0 + x1) * 0.5f;
            float absNorm = Math.abs(center) / Math.max(0.0001f, halfW);
            if (absNorm > widthProgress) {
                continue;
            }

            float revealNoise = 0.5f + (0.5f * Mth.sin((center * 11.0f) + (time * 0.75f) + (noiseSeed * 9.0f)));
            float revealThreshold = 0.12f + (0.72f * revealNoise);
            float reveal = Mth.clamp((openProgress - revealThreshold + 0.2f) / 0.24f, 0.0f, 1.0f);
            if (reveal <= 0.001f) {
                continue;
            }

            int a = Mth.clamp((int) (255.0f * alpha * reveal), 0, 255);
            float u0 = (t0 + (time * scrollU)) % 1.0f;
            float u1 = (t1 + (time * scrollU)) % 1.0f;
            float v0 = (0.0f + (time * scrollV)) % 1.0f;
            float v1 = (1.0f + (time * scrollV)) % 1.0f;

            texturedQuadBidirectional(vc, mat4, normal,
                x0, -halfH,
                x1, -halfH,
                x1, halfH,
                x0, halfH,
                z,
                light,
                1.0f,
                u0, v0,
                u1, v0,
                u1, v1,
                u0, v1,
                r, g, b, a);
        }
    }

    private static double resolveAnimationTicks(CorridorPortalBlockEntity blockEntity, float partialTick) {
        if (blockEntity.getLevel() != null) {
            double serverTicks = blockEntity.getLevel().getGameTime() + (double) partialTick;
            String key = blockEntity.getLevel().dimension().location() + ":" + blockEntity.getBlockPos().asLong();
            long nowNanos = System.nanoTime();
            AnimationClockState state = ANIMATION_CLOCKS.computeIfAbsent(key, ignored -> new AnimationClockState(serverTicks, nowNanos));

            if (state.needsResync(serverTicks)) {
                state.resync(serverTicks, nowNanos);
                return serverTicks;
            }

            double predictedTicks = state.predictedTicks(nowNanos);
            double clampedLeadTicks = Math.min(predictedTicks, serverTicks + MAX_CLIENT_TICK_LEAD);
            double resolvedTicks = Math.max(clampedLeadTicks, state.lastResolvedTicks);
            if (serverTicks > resolvedTicks + SERVER_RESYNC_THRESHOLD) {
                resolvedTicks = serverTicks;
                state.resync(serverTicks, nowNanos);
            }

            state.lastSeenServerTicks = serverTicks;
            state.lastResolvedTicks = resolvedTicks;
            return resolvedTicks;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return mc.level.getGameTime() + (double) mc.getFrameTime();
        }

        if (mc.player != null) {
            return mc.player.tickCount + (double) partialTick;
        }

        return 0.0;
    }

    private static final class AnimationClockState {
        private double anchorServerTicks;
        private long anchorNanos;
        private double lastSeenServerTicks;
        private double lastResolvedTicks;

        private AnimationClockState(double serverTicks, long nowNanos) {
            this.anchorServerTicks = serverTicks;
            this.anchorNanos = nowNanos;
            this.lastSeenServerTicks = serverTicks;
            this.lastResolvedTicks = serverTicks;
        }

        private boolean needsResync(double serverTicks) {
            return serverTicks + 1.0 < lastSeenServerTicks;
        }

        private void resync(double serverTicks, long nowNanos) {
            this.anchorServerTicks = serverTicks;
            this.anchorNanos = nowNanos;
            this.lastSeenServerTicks = serverTicks;
            this.lastResolvedTicks = serverTicks;
        }

        private double predictedTicks(long nowNanos) {
            double elapsedTicks = Math.max(0.0, (nowNanos - anchorNanos) * TICKS_PER_NANO);
            return anchorServerTicks + elapsedTicks;
        }
    }

    private void renderPortalLabel(
        CorridorPortalBlockEntity blockEntity,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        float scale
    ) {
        if (blockEntity.isPermanentFrameMode()) {
            return;
        }

        String label = blockEntity.getPortalLabel();
        if (label == null || label.isBlank()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5, 1.50 + (0.18 * scale), 0.5);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());

        float textScale = 0.020f * (0.9f + (0.1f * Mth.clamp(scale, 0.1f, 3.0f)));
        poseStack.scale(-textScale, -textScale, textScale);

        Font font = mc.font;
        float x = -font.width(label) / 2.0f;
        font.drawInBatch(
            label,
            x,
            0.0f,
            0xE4FBFF,
            false,
            poseStack.last().pose(),
            buffer,
            Font.DisplayMode.NORMAL,
            0,
            packedLight
        );
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

    private void drawMembraneTint(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time,
        int shape,
        int tintColor
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float halfH = HALF_HEIGHT * envelope * scale;
        float halfW = HALF_WIDTH * envelope * scale;
        if (halfH <= 0.0001f || halfW <= 0.0001f) {
            return;
        }

        int membraneColor = mixRgb(0x020106, tintColor, 0.78f);
        int membraneR = (membraneColor >> 16) & 0xFF;
        int membraneG = (membraneColor >> 8) & 0xFF;
        int membraneB = membraneColor & 0xFF;
        int membraneAlpha = Mth.clamp((int) (168f * envelope), 0, 255);

        if (shape == 1) {
            quadBidirectional(vc, mat4, normal,
                -halfW, -halfH,
                halfW, -halfH,
                halfW, halfH,
                -halfW, halfH,
                0.0f, light, 1.0f,
                membraneR, membraneG, membraneB, membraneAlpha,
                membraneR, membraneG, membraneB, membraneAlpha,
                membraneR, membraneG, membraneB, membraneAlpha,
                membraneR, membraneG, membraneB, membraneAlpha);
            return;
        }

        for (int i = 0; i < STRIPS; i++) {
            float v0 = i / (float) STRIPS;
            float v1 = (i + 1) / (float) STRIPS;

            float y0 = Mth.lerp(v0, -halfH, halfH);
            float y1 = Mth.lerp(v1, -halfH, halfH);

            float n0 = y0 / halfH;
            float n1 = y1 / halfH;

            float wobble0 = tearWobbleX(n0, envelope, scale, time);
            float wobble1 = tearWobbleX(n1, envelope, scale, time);

            float left0 = tearLeftX(n0, halfW, envelope, scale, time) + wobble0;
            float right0 = tearRightX(n0, halfW, envelope, scale, time) + wobble0;
            float left1 = tearLeftX(n1, halfW, envelope, scale, time) + wobble1;
            float right1 = tearRightX(n1, halfW, envelope, scale, time) + wobble1;

            quadBidirectional(vc, mat4, normal,
                left0, y0,
                right0, y0,
                right1, y1,
                left1, y1,
                0.0f, light, 1.0f,
                membraneR, membraneG, membraneB, membraneAlpha,
                membraneR, membraneG, membraneB, membraneAlpha,
                membraneR, membraneG, membraneB, membraneAlpha,
                membraneR, membraneG, membraneB, membraneAlpha);
        }
    }

    private void drawPurpleGlow(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time,
        int shape,
        int outerColor,
        int innerColor
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
        int outerR = (outerColor >> 16) & 0xFF;
        int outerG = (outerColor >> 8) & 0xFF;
        int outerB = outerColor & 0xFF;
        int innerR = (innerColor >> 16) & 0xFF;
        int innerG = (innerColor >> 8) & 0xFF;
        int innerB = innerColor & 0xFF;

        for (int side = 0; side < 2; side++) {
            float z = side == 0 ? zFront : zBack;
            float nz = side == 0 ? 1.0f : -1.0f;

            for (int i = 0; i < OUTLINE_SEGMENTS; i++) {
                float a0 = Mth.TWO_PI * i / OUTLINE_SEGMENTS;
                float a1 = Mth.TWO_PI * (i + 1) / OUTLINE_SEGMENTS;

                PortalPoint edge0 = portalPoint(shape, a0, halfW, halfH, envelope, scale, time);
                PortalPoint edge1 = portalPoint(shape, a1, halfW, halfH, envelope, scale, time);

                float width0 = outlineWidth(shape, edge0, envelope, scale);
                float width1 = outlineWidth(shape, edge1, envelope, scale);

                PortalPoint outer0;
                PortalPoint outer1;
                PortalPoint inner0;
                PortalPoint inner1;
                if (shape == 1) {
                    outer0 = edge0;
                    outer1 = edge1;
                    inner0 = offsetPortalPoint(shape, edge0, -width0, halfW, halfH);
                    inner1 = offsetPortalPoint(shape, edge1, -width1, halfW, halfH);
                } else {
                    inner0 = edge0;
                    inner1 = edge1;
                    outer0 = offsetPortalPoint(shape, edge0, width0, halfW, halfH);
                    outer1 = offsetPortalPoint(shape, edge1, width1, halfW, halfH);
                }

                quadBidirectional(vc, mat4, normal, outer0.x, outer0.y, inner0.x, inner0.y, inner1.x, inner1.y, outer1.x, outer1.y, z, light, nz,
                    outerR, outerG, outerB, alphaOuter,
                    innerR, innerG, innerB, alphaInner,
                    innerR, innerG, innerB, alphaInner,
                    outerR, outerG, outerB, alphaOuter);
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
        int shape,
        int edgeColor
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
        int edgeR = (edgeColor >> 16) & 0xFF;
        int edgeG = (edgeColor >> 8) & 0xFF;
        int edgeB = edgeColor & 0xFF;
        int innerFade = mixRgb(0x0B0108, edgeColor, 0.40f);
        int innerR = (innerFade >> 16) & 0xFF;
        int innerG = (innerFade >> 8) & 0xFF;
        int innerB = innerFade & 0xFF;

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
                    edgeR, edgeG, edgeB, edgeAlpha,
                    innerR, innerG, innerB, 0,
                    innerR, innerG, innerB, 0,
                    edgeR, edgeG, edgeB, edgeAlpha);
            }
        }
    }

    private void drawInflowTrails(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time,
        int tailColor,
        int headColor
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

                drawTrailWisp(vc, mat4, normal, cx, cy, nx, ny, size, stretch, zFront, light, 1.0f, moteTailAlpha, moteHeadAlpha, tailColor, headColor);
                drawTrailWisp(vc, mat4, normal, cx, cy, nx, ny, size, stretch, zBack, light, -1.0f, moteTailAlpha, moteHeadAlpha, tailColor, headColor);
            }
        }
    }

    private void drawInternalPortalAccent(
        PoseStack poseStack,
        VertexConsumer vc,
        int light,
        float envelope,
        float scale,
        float time,
        int shape,
        int accentColor
    ) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        float halfH = HALF_HEIGHT * envelope * scale;
        float halfW = HALF_WIDTH * envelope * scale;
        if (halfH <= 0.0001f || halfW <= 0.0001f) {
            return;
        }

        int accentR = (accentColor >> 16) & 0xFF;
        int accentG = (accentColor >> 8) & 0xFF;
        int accentB = accentColor & 0xFF;
        int coreColor = mixRgb(0x090106, accentColor, 0.34f);
        int coreR = (coreColor >> 16) & 0xFF;
        int coreG = (coreColor >> 8) & 0xFF;
        int coreB = coreColor & 0xFF;

        float zFront = Z_EPSILON + 0.0016f;
        float zBack = -Z_EPSILON - 0.0016f;
        int wispCount = 18;
        for (int i = 0; i < wispCount; i++) {
            float seed = i * 0.617f;
            float angle = Mth.TWO_PI * Mth.frac(seed * 0.221f + time * 0.032f);
            float radial = 0.14f + (0.70f * Mth.frac(seed * 0.379f + time * 0.058f));
            PortalPoint edge = portalPoint(shape, angle, halfW, halfH, envelope, scale, time);
            float cx = edge.x * radial;
            float cy = edge.y * radial;

            float pulse = 0.70f + (0.30f * Mth.sin(time * 2.4f + seed * 5.3f));
            int alphaOuter = Mth.clamp((int) (34f * envelope * pulse), 0, 255);
            int alphaInner = Mth.clamp((int) (76f * envelope * pulse), 0, 255);
            float size = (0.018f + 0.010f * Mth.sin(time * 1.7f + seed * 3.9f)) * envelope * scale;

            float left = cx - size;
            float right = cx + size;
            float bottom = cy - size;
            float top = cy + size;

            quadBidirectional(vc, mat4, normal,
                left, bottom,
                right, bottom,
                right, top,
                left, top,
                zFront, light, 1.0f,
                coreR, coreG, coreB, alphaOuter,
                accentR, accentG, accentB, alphaInner,
                accentR, accentG, accentB, alphaInner,
                coreR, coreG, coreB, alphaOuter);

            quadBidirectional(vc, mat4, normal,
                left, bottom,
                right, bottom,
                right, top,
                left, top,
                zBack, light, -1.0f,
                coreR, coreG, coreB, alphaOuter,
                accentR, accentG, accentB, alphaInner,
                accentR, accentG, accentB, alphaInner,
                coreR, coreG, coreB, alphaOuter);
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
        int alphaHead,
        int tailColor,
        int headColor
    ) {
        int darkColor = mixRgb(0x0A0006, tailColor, 0.45f);
        int darkR = (darkColor >> 16) & 0xFF;
        int darkG = (darkColor >> 8) & 0xFF;
        int darkB = darkColor & 0xFF;
        int tailR = (tailColor >> 16) & 0xFF;
        int tailG = (tailColor >> 8) & 0xFF;
        int tailB = tailColor & 0xFF;
        int headR = (headColor >> 16) & 0xFF;
        int headG = (headColor >> 8) & 0xFF;
        int headB = headColor & 0xFF;

        quadBidirectional(vc, mat4, normal,
            cx - nx * size, cy - ny * size,
            cx - nx * stretch * 0.35f, cy - ny * stretch * 0.35f,
            cx + nx * size, cy + ny * size,
            cx + nx * stretch, cy + ny * stretch,
            z, light, nz,
            darkR, darkG, darkB, 0,
            tailR, tailG, tailB, alphaTail,
            headR, headG, headB, alphaHead,
            tailR, tailG, tailB, 0);
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
        portalQuad(vc, mat4, -halfW, -halfH, halfW, halfH, z);
    }

    private void drawCollapseSpark(PoseStack poseStack, VertexConsumer vc, int light, float collapseProgress, int sparkColor) {
        // Bright implosion spark only in the final part of collapse.
        // I keep flip flopping on whether I like this or not but it does add a bit of extra punch to the collapse and I don't have a better idea for what to do in that moment, so here we are.
        if (collapseProgress < 0.78f || collapseProgress >= 1.0f) {
            return;
        }

        float phase = (collapseProgress - 0.78f) / 0.22f;
        float pulse = Mth.sin(phase * Mth.PI);
        float size = 0.03f + (0.18f * (1.0f - phase));
        int alpha = Mth.clamp((int) (255f * pulse), 0, 255);
        int outer = mixRgb(0x14000B, sparkColor, 0.68f);
        int inner = mixRgb(0x2A0018, sparkColor, 0.92f);
        int outerR = (outer >> 16) & 0xFF;
        int outerG = (outer >> 8) & 0xFF;
        int outerB = outer & 0xFF;
        int innerR = (inner >> 16) & 0xFF;
        int innerG = (inner >> 8) & 0xFF;
        int innerB = inner & 0xFF;

        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat4 = pose.pose();
        Matrix3f normal = pose.normal();

        vertex(vc, mat4, normal, -size, -size, Z_EPSILON + 0.0008f, 0.0f, 0.0f, outerR, outerG, outerB, alpha, light, 1.0f);
        vertex(vc, mat4, normal, size, -size, Z_EPSILON + 0.0008f, 1.0f, 0.0f, innerR, innerG, innerB, alpha, light, 1.0f);
        vertex(vc, mat4, normal, size, size, Z_EPSILON + 0.0008f, 1.0f, 1.0f, innerR, innerG, innerB, alpha, light, 1.0f);
        vertex(vc, mat4, normal, -size, size, Z_EPSILON + 0.0008f, 0.0f, 1.0f, innerR, innerG, innerB, alpha, light, 1.0f);

        vertex(vc, mat4, normal, -size, size, Z_EPSILON + 0.0008f, 0.0f, 1.0f, innerR, innerG, innerB, alpha, light, -1.0f);
        vertex(vc, mat4, normal, size, size, Z_EPSILON + 0.0008f, 1.0f, 1.0f, innerR, innerG, innerB, alpha, light, -1.0f);
        vertex(vc, mat4, normal, size, -size, Z_EPSILON + 0.0008f, 1.0f, 0.0f, innerR, innerG, innerB, alpha, light, -1.0f);
        vertex(vc, mat4, normal, -size, -size, Z_EPSILON + 0.0008f, 0.0f, 0.0f, outerR, outerG, outerB, alpha, light, -1.0f);
    }

    private void drawThresholdGlyph(
        PoseStack poseStack,
        VertexConsumer vc,
        TextureAtlasSprite sprite,
        int light,
        float envelope,
        float scale,
        float time,
        double worldTicks,
        int outerColor,
        int innerColor
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
        int outerR = (outerColor >> 16) & 0xFF;
        int outerG = (outerColor >> 8) & 0xFF;
        int outerB = outerColor & 0xFF;
        int innerR = (innerColor >> 16) & 0xFF;
        int innerG = (innerColor >> 8) & 0xFF;
        int innerB = innerColor & 0xFF;
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
                outerR, outerG, outerB, alphaOuter,
                innerR, innerG, innerB, alphaInner,
                innerR, innerG, innerB, alphaInner,
                outerR, outerG, outerB, alphaOuter);
    }

    private static int mixRgb(int a, int b, float t) {
        float clamped = Mth.clamp(t, 0.0f, 1.0f);
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;

        int r = Mth.clamp((int) Mth.lerp(clamped, ar, br), 0, 255);
        int g = Mth.clamp((int) Mth.lerp(clamped, ag, bg), 0, 255);
        int bCh = Mth.clamp((int) Mth.lerp(clamped, ab, bb), 0, 255);
        return (r << 16) | (g << 8) | bCh;
    }

    private static int makePortalAccentTint(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));

        // Very dark dyes get a stable shadow-purple accent so internal details remain visible.
        if (max < 21) {
            return 0x2E0847;
        }

        float nr = r / (float) max;
        float ng = g / (float) max;
        float nb = b / (float) max;
        float biasR = 115f / 255f;
        float biasG = 13f / 255f;
        float biasB = 31f / 255f;
        float mixedR = Mth.lerp(0.15f, nr, biasR) * 0.75f;
        float mixedG = Mth.lerp(0.15f, ng, biasG) * 0.75f;
        float mixedB = Mth.lerp(0.15f, nb, biasB) * 0.75f;

        int outR = Mth.clamp((int) (mixedR * 255f), 0, 255);
        int outG = Mth.clamp((int) (mixedG * 255f), 0, 255);
        int outB = Mth.clamp((int) (mixedB * 255f), 0, 255);
        return (outR << 16) | (outG << 8) | outB;
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

    private static void texturedQuadBidirectional(
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
        int r,
        int g,
        int b,
        int a
    ) {
        vertex(vc, mat4, normal, x0, y0, z, u0, v0, r, g, b, a, light, normalZ);
        vertex(vc, mat4, normal, x1, y1, z, u1, v1, r, g, b, a, light, normalZ);
        vertex(vc, mat4, normal, x2, y2, z, u2, v2, r, g, b, a, light, normalZ);
        vertex(vc, mat4, normal, x3, y3, z, u3, v3, r, g, b, a, light, normalZ);

        vertex(vc, mat4, normal, x3, y3, z, u3, v3, r, g, b, a, light, -normalZ);
        vertex(vc, mat4, normal, x2, y2, z, u2, v2, r, g, b, a, light, -normalZ);
        vertex(vc, mat4, normal, x1, y1, z, u1, v1, r, g, b, a, light, -normalZ);
        vertex(vc, mat4, normal, x0, y0, z, u0, v0, r, g, b, a, light, -normalZ);
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

    private static void portalQuad(
        VertexConsumer vc,
        Matrix4f mat4,
        float x0,
        float y0,
        float x1,
        float y1,
        float z
    ) {
        portalVertex(vc, mat4, x0, y0, z);
        portalVertex(vc, mat4, x1, y0, z);
        portalVertex(vc, mat4, x1, y1, z);
        portalVertex(vc, mat4, x0, y1, z);

        portalVertex(vc, mat4, x0, y1, z);
        portalVertex(vc, mat4, x1, y1, z);
        portalVertex(vc, mat4, x1, y0, z);
        portalVertex(vc, mat4, x0, y0, z);
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

    private static final class AlphaMask {
        private final int width;
        private final int height;
        private final float[] values;

        private AlphaMask(int width, int height, float[] values) {
            this.width = width;
            this.height = height;
            this.values = values;
        }

        private static AlphaMask from(NativeImage image) {
            int width = image.getWidth();
            int height = image.getHeight();
            float[] alpha = new float[width * height];
            float[] luminance = new float[width * height];
            int minAlpha = 255;
            int maxAlpha = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image.getPixelRGBA(x, y);
                    int index = (y * width) + x;
                    int pixelAlpha = (pixel >>> 24) & 0xFF;
                    int channel0 = pixel & 0xFF;
                    int channel1 = (pixel >>> 8) & 0xFF;
                    int channel2 = (pixel >>> 16) & 0xFF;

                    alpha[index] = pixelAlpha / 255.0f;
                    luminance[index] = (channel0 + channel1 + channel2) / (3.0f * 255.0f);
                    minAlpha = Math.min(minAlpha, pixelAlpha);
                    maxAlpha = Math.max(maxAlpha, pixelAlpha);
                }
            }

            return new AlphaMask(width, height, (maxAlpha - minAlpha) <= 1 ? luminance : alpha);
        }

        private float sample(float u, float v) {
            int x = Mth.clamp((int) (u * (width - 1)), 0, width - 1);
            int y = Mth.clamp((int) (v * (height - 1)), 0, height - 1);
            return values[(y * width) + x];
        }
    }
}
