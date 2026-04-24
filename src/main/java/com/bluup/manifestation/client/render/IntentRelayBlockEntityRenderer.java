package com.bluup.manifestation.client.render;

import com.bluup.manifestation.Manifestation;
import com.bluup.manifestation.server.block.IntentRelayBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

public final class IntentRelayBlockEntityRenderer implements BlockEntityRenderer<IntentRelayBlockEntity> {
    private static final float ICON_SCALE = 0.42f;
    private static final float REDSTONE_ICON_SCALE_MULTIPLIER = 0.8f;
    private static boolean renderErrorLogged;

    private final BlockEntityRendererProvider.Context context;

    public IntentRelayBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
    }

    @Override
    public void render(
        IntentRelayBlockEntity blockEntity,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        int packedOverlay
    ) {
        ItemStack icon = blockEntity.displayedIconStack();
        if (icon.isEmpty()) {
            return;
        }

        BlockState state = blockEntity.getBlockState();
        if (!state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACE)
            || !state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACING)) {
            return;
        }

        AttachFace face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
        Direction facing = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);

        // Start from a floor-facing orientation and rotate like blockstate transforms.
        if (face == AttachFace.CEILING) {
            poseStack.mulPose(Axis.XP.rotationDegrees(180.0f));
        } else if (face == AttachFace.WALL) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        }
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));

        // Keep icon just above the relay plane to avoid z-fighting.
        poseStack.translate(0.0, -0.48, 0.0);
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
        float scale = ICON_SCALE;
        if (blockEntity.isRedstoneMode()) {
            scale *= REDSTONE_ICON_SCALE_MULTIPLIER;
        }
        poseStack.scale(scale, scale, scale);

        try {
            context.getItemRenderer().renderStatic(
                icon,
                ItemDisplayContext.FIXED,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                buffer,
                blockEntity.getLevel(),
                blockEntity.getBlockPos().hashCode()
            );
        } catch (Throwable t) {
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                Manifestation.LOGGER.warn("Manifestation: failed to render relay icon at {}", blockEntity.getBlockPos(), t);
            }
        }

        poseStack.popPose();
    }
}
