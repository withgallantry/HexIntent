package com.bluup.manifestation.client;

import com.bluup.manifestation.Manifestation;
import com.bluup.manifestation.client.menu.ui.MenuScreen;
import com.bluup.manifestation.client.render.CorridorPortalBlockEntityRenderer;
import com.bluup.manifestation.client.render.EquationSynthBlockEntityRenderer;
import com.bluup.manifestation.client.render.IntentRelayBlockEntityRenderer;
import com.bluup.manifestation.client.render.MindVaultBlockEntityRenderer;
import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.common.menu.MenuPayload;
import com.bluup.manifestation.server.ManifestationConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.InteractionHand;
import com.bluup.manifestation.server.block.ManifestationBlocks;

/**
 * Client entrypoint. Runs on every client, both connected-to-dedicated-server
 * and singleplayer.
 *
 * <p>Sole responsibility: subscribe to the Manifestation S2C packet. When one
 * arrives, read the {@link MenuPayload}, ensure no menu is already open (the
 * "one active menu" rule), mark the new one active, and swap the screen to
 * a fresh {@link MenuScreen}.
 *
 * <p>The packet includes the casting hand used when the menu was created.
 * Reusing that hand on click keeps dispatch bound to the same staff-cast
 * session image instead of accidentally crossing hands.
 */
public final class ManifestationClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Manifestation.LOGGER.info("Manifestation client initializing.");
        ManifestationConfig.INSTANCE.load();

        BlockRenderLayerMap.INSTANCE.putBlock(
            ManifestationBlocks.INTENT_RELAY_BLOCK,
            RenderType.cutout()
        );
        BlockRenderLayerMap.INSTANCE.putBlock(
            ManifestationBlocks.CORRIDOR_PORTAL_BLOCK,
            RenderType.translucent()
        );
        BlockRenderLayerMap.INSTANCE.putBlock(
            ManifestationBlocks.EQUATION_SYNTH_BLOCK,
            RenderType.cutout()
        );
        BlockEntityRenderers.register(
            ManifestationBlocks.CORRIDOR_PORTAL_BLOCK_ENTITY,
            CorridorPortalBlockEntityRenderer::new
        );
        BlockEntityRenderers.register(
            ManifestationBlocks.INTENT_RELAY_BLOCK_ENTITY,
            IntentRelayBlockEntityRenderer::new
        );
        BlockEntityRenderers.register(
            ManifestationBlocks.MIND_VAULT_BLOCK_ENTITY,
            MindVaultBlockEntityRenderer::new
        );
        BlockEntityRenderers.register(
            ManifestationBlocks.EQUATION_SYNTH_BLOCK_ENTITY,
            EquationSynthBlockEntityRenderer::new
        );
        HexReliquaryLensOverlay.register();
        IntentShifterLensOverlay.register();
        MindVaultLensOverlay.register();
        IntentShifterRuneEffects.register();
        SplinterVisuals.register();

        // Register optional visuals only when their feature flags are enabled.
        if (ManifestationConfig.INSTANCE.constellationFeatureEnabled()) {
            ConstellationVisuals.register();
        }
        if (ManifestationConfig.INSTANCE.particleBlobLoaderEnabled()) {
            ParticleBlobVisuals.register();
        }
        EquationCloudVisuals.register();
        SpellCircleVisuals.register();

        ClientPlayNetworking.registerGlobalReceiver(
                ManifestationNetworking.SHOW_MENU_S2C,
                (client, handler, buf, responseSender) -> {
                    // Decode on the networking thread — it's cheap and pure.
                    final MenuPayload payload;
                    try {
                        payload = MenuPayload.read(buf);
                    } catch (Throwable t) {
                        Manifestation.LOGGER.error(
                                "Manifestation: failed to decode menu packet", t);
                        return;
                    }

                    // But open the screen on the render thread.
                    client.execute(() -> openMenu(client, payload));
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                ManifestationNetworking.OPEN_PARTICLE_IMPORTER_S2C,
                (client, handler, buf, responseSender) -> {
                    final var pos = buf.readBlockPos();
                    client.execute(() -> client.setScreen(new ParticleImporterScreen(pos)));
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                ManifestationNetworking.OPEN_EQUATION_SYNTH_S2C,
                (client, handler, buf, responseSender) -> {
                    final var pos = buf.readBlockPos();
                    client.execute(() -> client.setScreen(new EquationSynthScreen(pos)));
                }
        );
    }

    private static void openMenu(Minecraft mc, MenuPayload payload) {
        ActiveMenuState state = ActiveMenuState.get();

        if (state.isReopenSuppressed(payload)) {
            Manifestation.LOGGER.debug(
                    "Manifestation: menu arrived during close-suppression window; dropping.");
            return;
        }

        if (state.isActive()) {
            Manifestation.LOGGER.debug(
                    "Manifestation: menu arrived while another is active; dropping.");
            return;
        }

        InteractionHand hand = payload.hand();
        state.open(payload, hand);
        mc.setScreen(new MenuScreen(payload, hand));
    }
}
