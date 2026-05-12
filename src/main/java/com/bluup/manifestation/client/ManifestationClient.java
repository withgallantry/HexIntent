package com.bluup.manifestation.client;

import com.bluup.manifestation.Manifestation;
import com.bluup.manifestation.client.menu.ui.MenuScreen;
import com.bluup.manifestation.client.render.CorridorPortalBlockEntityRenderer;
import com.bluup.manifestation.client.render.IntentRelayBlockEntityRenderer;
import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.common.menu.MenuPayload;
import com.bluup.manifestation.server.ManifestationConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
        BlockEntityRenderers.register(
            ManifestationBlocks.CORRIDOR_PORTAL_BLOCK_ENTITY,
            CorridorPortalBlockEntityRenderer::new
        );
        BlockEntityRenderers.register(
            ManifestationBlocks.INTENT_RELAY_BLOCK_ENTITY,
            IntentRelayBlockEntityRenderer::new
        );
        IntentShifterLensOverlay.register();
        IntentShifterRuneEffects.register();
        SplinterVisuals.register();
        HexTrailVisuals.register();

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
                ManifestationNetworking.MENU_INVALIDATE_S2C,
                (client, handler, buf, responseSender) -> {
                    final java.util.UUID token = buf.readUUID();
                    final net.minecraft.network.chat.Component message = buf.readComponent();

                    client.execute(() -> {
                        ActiveMenuState state = ActiveMenuState.get();
                        MenuPayload current = state.current();
                        if (current == null || !current.sessionToken().equals(token)) {
                            return;
                        }

                        state.clear();
                        if (client.screen instanceof MenuScreen) {
                            client.setScreen(null);
                        }
                        if (client.player != null) {
                            client.player.displayClientMessage(message, true);
                        }
                    });
                }
        );

            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> ActiveMenuState.get().clear())
            );

            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(() -> ActiveMenuState.get().clear())
            );
    }

    private static void openMenu(Minecraft mc, MenuPayload payload) {
        ActiveMenuState state = ActiveMenuState.get();

        // Recover from stale state (e.g. dimension travel closed the screen but
        // did not clear ActiveMenuState through normal MenuScreen onClose).
        if (state.isActive() && !(mc.screen instanceof MenuScreen)) {
            Manifestation.LOGGER.debug(
                    "Manifestation: recovered stale active-menu state before opening new menu.");
            state.clear();
        }

        if (state.isReopenSuppressed(payload)) {
            Manifestation.LOGGER.debug(
                    "Manifestation: menu arrived during close-suppression window; dropping.");
            return;
        }

        // "Only one active menu at a time." If one is already live, the
        // incoming one is dropped. The server-side operator already succeeded
        // (stack was consumed, op count spent), so we just don't show it.
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
