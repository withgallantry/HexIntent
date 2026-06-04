package com.bluup.manifestation.client;

import at.petrak.hexcasting.api.client.ScryingLensOverlayRegistry;
import com.bluup.manifestation.server.block.ManifestationBlocks;
import com.bluup.manifestation.server.block.MindVaultBlockEntity;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class MindVaultLensOverlay {
    public static void register() {
        ScryingLensOverlayRegistry.addDisplayer(
            ManifestationBlocks.MIND_VAULT_BLOCK,
            (lines, state, pos, observer, world, hitFace) -> {
                if (!(world.getBlockEntity(pos) instanceof MindVaultBlockEntity vault)) {
                    return;
                }

                String professionId = vault.lockedProfessionIdString();
                int level = vault.lockedVillagerLevel();
                if (professionId == null || level <= 0 || vault.occupiedSlotCount() <= 0) {
                    lines.add(new Pair<>(
                        ItemStack.EMPTY,
                        Component.translatable("hexcasting.tooltip.manifestation.mind_vault.empty").withStyle(ChatFormatting.GRAY)
                    ));
                    return;
                }

                ItemStack typeIcon = vault.displayedIconStack();
                Component profile = Component.translatable(
                    "hexcasting.tooltip.manifestation.mind_vault.profile",
                    levelName(level),
                    formatProfession(professionId)
                );
                if (!typeIcon.isEmpty()) {
                    String jobSiteName = typeIcon.getHoverName().getString();
                    if (!jobSiteName.isBlank()) {
                        profile = profile.copy().append(Component.literal(" (" + jobSiteName + ")"));
                    }
                }

                Component styledProfile = Component.empty().append(profile).withStyle(ChatFormatting.AQUA);
                lines.add(new Pair<>(
                    typeIcon,
                    styledProfile
                ));

                long now = world.getGameTime();
                for (int slot = 0; slot < MindVaultBlockEntity.SLOT_COUNT; slot++) {
                    if (!vault.isSlotOccupied(slot)) {
                        continue;
                    }

                    long cooldownTicks = vault.getSlotCooldownRemainingTicks(slot, now);
                    if (cooldownTicks > 0) {
                        lines.add(new Pair<>(
                            ItemStack.EMPTY,
                            Component.translatable(
                                "hexcasting.tooltip.manifestation.mind_vault.slot_cooldown",
                                slot + 1,
                                formatDuration(cooldownTicks)
                            ).withStyle(ChatFormatting.GOLD)
                        ));
                    } else {
                        lines.add(new Pair<>(
                            ItemStack.EMPTY,
                            Component.translatable(
                                "hexcasting.tooltip.manifestation.mind_vault.slot_ready",
                                slot + 1
                            ).withStyle(ChatFormatting.GREEN)
                        ));
                    }
                }
            }
        );
    }

    private static Component levelName(int level) {
        return switch (level) {
            case 1 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.novice");
            case 2 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.apprentice");
            case 3 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.journeyman");
            case 4 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.expert");
            case 5 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.master");
            default -> Component.literal("Level " + level);
        };
    }

    private static String formatProfession(String professionId) {
        ResourceLocation id = ResourceLocation.tryParse(professionId);
        String path = id == null ? professionId : id.getPath();
        String[] words = path.split("_");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(words[i].charAt(0)));
            if (words[i].length() > 1) {
                out.append(words[i].substring(1));
            }
        }
        return out.length() == 0 ? professionId : out.toString();
    }

    private static String formatDuration(long ticks) {
        long seconds = Math.max(0L, ticks / 20L);
        long mins = seconds / 60L;
        long secs = seconds % 60L;
        if (mins > 0) {
            return mins + "m " + secs + "s";
        }
        return secs + "s";
    }

    private MindVaultLensOverlay() {
    }
}
