package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class ParticleImporterScreen extends Screen {
    private static final int MAX_JSON_CHARS = 200000;

    private final BlockPos blockPos;
    private EditBox jsonBox;

    public ParticleImporterScreen(BlockPos blockPos) {
        super(Component.literal("Particle Import"));
        this.blockPos = blockPos;
    }

    @Override
    protected void init() {
        int boxW = Math.min(420, this.width - 40);
        int boxX = (this.width - boxW) / 2;
        int boxY = this.height / 2 - 55;

        this.jsonBox = new EditBox(this.font, boxX, boxY, boxW, 20, Component.literal("Particle JSON"));
        this.jsonBox.setMaxLength(MAX_JSON_CHARS);
        this.jsonBox.setHint(Component.literal("Paste JSON (must contain particles array)"));
        this.addRenderableWidget(this.jsonBox);
        this.setInitialFocus(this.jsonBox);

        this.addRenderableWidget(Button.builder(Component.literal("Import From Text"), button -> {
            submit(this.jsonBox.getValue());
        }).bounds(boxX, boxY + 28, 130, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Import Clipboard"), button -> {
            String text = Minecraft.getInstance().keyboardHandler.getClipboard();
            submit(text == null ? "" : text);
        }).bounds(boxX + 140, boxY + 28, 130, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> {
            onClose();
        }).bounds(boxX + 280, boxY + 28, 130, 20).build());
    }

    private void submit(String jsonText) {
        var buf = PacketByteBufs.create();
        buf.writeBlockPos(blockPos);
        buf.writeUtf(jsonText, MAX_JSON_CHARS);
        ClientPlayNetworking.send(ManifestationNetworking.IMPORT_PARTICLE_BLOB_C2S, buf);
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = this.width / 2 - 210;
        int y = this.height / 2 - 85;
        graphics.drawString(this.font, this.title, x, y, 0xFFFFFF, false);
        graphics.drawString(this.font, "Focus is in block. Import writes a compressed Particle Blob iota to it.", x, y + 12, 0xAAAAAA, false);
        graphics.drawString(this.font, "Only the particles key is used.", x, y + 24, 0xAAAAAA, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}