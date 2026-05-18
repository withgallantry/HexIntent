package com.bluup.manifestation.client.render;

import com.bluup.manifestation.Manifestation;
import com.bluup.manifestation.server.block.MindVaultBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class MindVaultBlockEntityRenderer implements BlockEntityRenderer<MindVaultBlockEntity> {
    private static final ResourceLocation TEX_SLOT_READY = Manifestation.id("block/mindvault_slot_ready");
    private static final ResourceLocation TEX_SLOT_COOLDOWN = Manifestation.id("block/mindvault_slot_cooldown");
    private static final ResourceLocation TEX_SLOT_EMPTY = Manifestation.id("block/mindvault_slot_empty");
    private static final ResourceLocation TEX_TYPE_NONE = Manifestation.id("block/mindvault_type_icon_none");

    private static final float Z_TYPE = -0.5014f;
    private static final float Z_TYPE_ITEM = -0.5016f;
    private static final float Z_SLOT = -0.5017f;
    private static final float TYPE_SIZE = 0.26f;
    private static final float SLOT_SIZE = 0.2f;

    private static final float[][] SLOT_CENTERS = new float[][] {
        {-0.26f, 0.16f},
        {0.0f, 0.16f},
        {0.26f, 0.16f},
        {-0.26f, -0.16f},
        {0.0f, -0.16f},
        {0.26f, -0.16f}
    };

    private static boolean renderErrorLogged;

    private final BlockEntityRendererProvider.Context context;

    public MindVaultBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
    }

    @Override
    public void render(
        MindVaultBlockEntity blockEntity,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        int packedOverlay
    ) {
        var state = blockEntity.getBlockState();
        if (!state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return;
        }
        var facing = state.getValue(HorizontalDirectionalBlock.FACING);
        int iconLight = LightTexture.FULL_BRIGHT;
        float panelYRot = switch (facing) {
            case NORTH -> 0.0f;
            case EAST -> 90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 270.0f;
            default -> 0.0f;
        };

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        // Match blockstate Y rotations exactly so overlays stay on the true front face.
        poseStack.mulPose(Axis.YP.rotationDegrees(panelYRot));

        try {
            if (blockEntity.lockedProfessionIdString() == null) {
                renderSprite(buffer, poseStack, TEX_TYPE_NONE, 0.0f, 0.36f, TYPE_SIZE, Z_TYPE, iconLight);
            } else {
                renderTypeItemIcon(blockEntity, poseStack, buffer, iconLight);
            }

            long gameTime = blockEntity.getLevel() != null ? blockEntity.getLevel().getGameTime() : 0L;
            for (int slot = 0; slot < MindVaultBlockEntity.SLOT_COUNT; slot++) {
                float[] center = SLOT_CENTERS[slot];
                ResourceLocation slotTex;
                if (!blockEntity.isSlotOccupied(slot)) {
                    slotTex = TEX_SLOT_EMPTY;
                } else if (blockEntity.getSlotCooldownRemainingTicks(slot, gameTime) > 0L) {
                    slotTex = TEX_SLOT_COOLDOWN;
                } else {
                    slotTex = TEX_SLOT_READY;
                }
                // Slot icons are emissive (always FULL_BRIGHT)
                renderSprite(buffer, poseStack, slotTex, center[0], center[1], SLOT_SIZE, Z_SLOT, LightTexture.FULL_BRIGHT);
            }
        } catch (Throwable t) {
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                Manifestation.LOGGER.warn("Manifestation: failed to render mind vault overlay at {}", blockEntity.getBlockPos(), t);
            }
        }

        poseStack.popPose();
    }

    private void renderTypeItemIcon(
        MindVaultBlockEntity blockEntity,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight
    ) {
        ItemStack icon = blockEntity.displayedIconStack();
        if (icon.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.0f, 0.36f, Z_TYPE_ITEM);
        poseStack.scale(0.19f, 0.19f, 0.19f);
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
        poseStack.popPose();
    }

    private static void renderSprite(
        MultiBufferSource buffer,
        PoseStack poseStack,
        ResourceLocation texture,
        float cx,
        float cy,
        float size,
        float z,
        int packedLight
    ) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(texture);

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();
        Matrix3f normal = pose.normal();

        float half = size * 0.5f;
        float x0 = cx - half;
        float x1 = cx + half;
        float y0 = cy - half;
        float y1 = cy + half;

        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        addVertex(vc, mat, normal, x0, y0, z, u0, v1, packedLight);
        addVertex(vc, mat, normal, x1, y0, z, u1, v1, packedLight);
        addVertex(vc, mat, normal, x1, y1, z, u1, v0, packedLight);
        addVertex(vc, mat, normal, x0, y1, z, u0, v0, packedLight);
    }

    private static void addVertex(
        VertexConsumer vc,
        Matrix4f mat,
        Matrix3f normal,
        float x,
        float y,
        float z,
        float u,
        float v,
        int packedLight
    ) {
        vc.vertex(mat, x, y, z)
            .color(255, 255, 255, 255)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(normal, 0.0f, 0.0f, 1.0f)
            .endVertex();
    }
}
