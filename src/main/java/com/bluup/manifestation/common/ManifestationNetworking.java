package com.bluup.manifestation.common;

import com.bluup.manifestation.Manifestation;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet identifiers for Manifestation's custom channel.
 *
 * <p>Two packets:
 * <ul>
 *   <li>{@code SHOW_MENU_S2C} — server to client, "open this menu now."
 *       Sent when one of the CREATE_*_MENU operators fires.</li>
 *   <li>{@code DISPATCH_ACTION_C2S} — client to server, "I clicked a button,
 *       here's its payload of iotas to dispatch." The server handler feeds
 *       each iota into the player's live casting session: patterns cast
 *       normally, non-patterns get pushed onto the stack directly.</li>
 * </ul>
 */
public final class ManifestationNetworking {

    public static final ResourceLocation SHOW_MENU_S2C = Manifestation.id("show_menu");
    public static final ResourceLocation DISPATCH_ACTION_C2S = Manifestation.id("dispatch_action");
    public static final ResourceLocation INTENT_SHIFTER_RUNES_S2C = Manifestation.id("intent_shifter_runes");

    private ManifestationNetworking() {
    }
}
