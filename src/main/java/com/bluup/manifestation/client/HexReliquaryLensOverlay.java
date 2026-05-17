package com.bluup.manifestation.client;

import at.petrak.hexcasting.api.client.ScryingLensOverlayRegistry;
import com.bluup.manifestation.server.block.HexReliquaryBlock;
import com.bluup.manifestation.server.block.HexReliquaryBlockEntity;
import com.bluup.manifestation.server.block.ManifestationBlocks;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class HexReliquaryLensOverlay {
    public static void register() {
        ScryingLensOverlayRegistry.addDisplayer(
            ManifestationBlocks.HEX_RELIQUARY_BLOCK,
            (lines, state, pos, observer, world, hitFace) -> {
                if (!(world.getBlockEntity(pos) instanceof HexReliquaryBlockEntity reliquary)) {
                    return;
                }

                int selected = state.getValue(HexReliquaryBlock.CURRENT);
                String label = reliquary.getSlotLabel(selected);
                boolean hasValue = reliquary.hasSlotValue(selected);

                lines.add(new Pair<>(
                    ItemStack.EMPTY,
                    Component.translatable("hexcasting.tooltip.manifestation.hex_reliquary.selected", selected + 1)
                        .withStyle(ChatFormatting.AQUA)
                ));
                lines.add(new Pair<>(
                    ItemStack.EMPTY,
                    Component.literal(label).withStyle(ChatFormatting.WHITE)
                ));
                lines.add(new Pair<>(
                    ItemStack.EMPTY,
                    Component.translatable(
                            hasValue
                                ? "hexcasting.tooltip.manifestation.hex_reliquary.slot_filled"
                                : "hexcasting.tooltip.manifestation.hex_reliquary.slot_empty"
                        )
                        .withStyle(hasValue ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                ));
            }
        );
    }

    private HexReliquaryLensOverlay() {
    }
}
