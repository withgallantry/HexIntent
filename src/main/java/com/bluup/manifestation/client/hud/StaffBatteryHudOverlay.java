package com.bluup.manifestation.client.hud;

import at.petrak.hexcasting.api.misc.MediaConstants;
import at.petrak.hexcasting.common.lib.HexItems;
import com.bluup.manifestation.server.item.ItemManifestationStaff;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class StaffBatteryHudOverlay {
    private static final ItemStack DUST_ICON = new ItemStack(HexItems.AMETHYST_DUST);

    public static void register() {
        HudRenderCallback.EVENT.register(StaffBatteryHudOverlay::render);
    }

    private static void render(GuiGraphics graphics, float tickDelta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.font == null) {
            return;
        }

        ItemStack held = pickHeldStaff(minecraft.player.getMainHandItem(), minecraft.player.getOffhandItem());
        if (held.isEmpty() || !(held.getItem() instanceof ItemManifestationStaff staff)) {
            return;
        }

        long currentDust = staff.getMedia(held) / MediaConstants.DUST_UNIT;
        long maxDust = staff.getMaxMedia(held) / MediaConstants.DUST_UNIT;
        Component text = Component.literal(currentDust + "/" + maxDust);

        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int textWidth = minecraft.font.width(text);

        int hotbarRight = (width / 2) + 91;
        int iconX = hotbarRight + 8;
        int y = height - 35;
        int textX = iconX + 18;

        graphics.renderItem(DUST_ICON, iconX, y);
        graphics.drawString(minecraft.font, text, textX, y + 4, 0xA6F3A6, true);
    }

    private static ItemStack pickHeldStaff(ItemStack mainHand, ItemStack offHand) {
        if (mainHand.getItem() instanceof ItemManifestationStaff) {
            return mainHand;
        }
        if (offHand.getItem() instanceof ItemManifestationStaff) {
            return offHand;
        }
        return ItemStack.EMPTY;
    }

    private StaffBatteryHudOverlay() {
    }
}