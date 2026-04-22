package com.bluup.manifestation.client;

import at.petrak.hexcasting.api.client.ScryingLensOverlayRegistry;
import com.bluup.manifestation.server.block.IntentRelayBlockEntity;
import com.bluup.manifestation.server.block.ManifestationBlocks;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class IntentShifterLensOverlay {
    public static void register() {
        ScryingLensOverlayRegistry.addDisplayer(
            ManifestationBlocks.INTENT_RELAY_BLOCK,
            (lines, state, pos, observer, world, hitFace) -> {
                if (!(world.getBlockEntity(pos) instanceof IntentRelayBlockEntity relay)) {
                    return;
                }

                ItemStack icon = ItemStack.EMPTY;
                if (!relay.hasTarget()) {
                    lines.add(new Pair<>(
                        icon,
                        Component.translatable("hexcasting.tooltip.manifestation.intent_shifter.unlinked")
                            .withStyle(ChatFormatting.GRAY)
                    ));
                    return;
                }

                BlockPos target = relay.linkedTargetPos();
                String dimId = relay.linkedTargetDimensionId();
                if (target == null || dimId == null) {
                    lines.add(new Pair<>(
                        icon,
                        Component.translatable("hexcasting.tooltip.manifestation.intent_shifter.unlinked")
                            .withStyle(ChatFormatting.GRAY)
                    ));
                    return;
                }

                lines.add(new Pair<>(
                    icon,
                    Component.translatable("hexcasting.tooltip.manifestation.intent_shifter.linked")
                        .withStyle(ChatFormatting.GREEN)
                ));

                lines.add(new Pair<>(
                    ItemStack.EMPTY,
                    Component.translatable(
                        "hexcasting.tooltip.manifestation.intent_shifter.coords",
                        target.getX(),
                        target.getY(),
                        target.getZ()
                    ).withStyle(ChatFormatting.AQUA)
                ));

                String lookedDim = world.dimension().location().toString();
                if (!dimId.equals(lookedDim)) {
                    lines.add(new Pair<>(
                        ItemStack.EMPTY,
                        Component.translatable(
                            "hexcasting.tooltip.manifestation.intent_shifter.dimension",
                            ResourceLocation.tryParse(dimId)
                        ).withStyle(ChatFormatting.DARK_AQUA)
                    ));
                }
            }
        );
    }

    private IntentShifterLensOverlay() {
    }
}
