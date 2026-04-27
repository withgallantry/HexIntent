package com.bluup.manifestation.client.menu.execution;

import com.bluup.manifestation.Manifestation;
import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.common.menu.MenuEntry;
import com.bluup.manifestation.common.menu.MenuPayload;
import com.bluup.manifestation.common.menu.StoredIota;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;

import java.util.List;

/**
 * Client-side handler for "the player clicked a menu button."
 *
 * <p>Packages the button's stored iotas into a single DISPATCH_ACTION_C2S
 * packet and sends it to the server. The server-side
 * {@code MenuActionDispatcher} does the actual work — feeding the iotas
 * through the player's live CastingVM.
 *
 * <p>This replaces the previous PatternExecutor, which sent one
 * MsgNewSpellPatternC2S per pattern in the button. That approach had two
 * problems:
 * <ul>
 *   <li>It could only send patterns — non-pattern iotas (numbers, strings,
 *       custom addon iotas like View) couldn't be dispatched at all.</li>
 *   <li>It required N server roundtrips for an N-pattern button.</li>
 * </ul>
 *
 * <p>The new path sends one packet per click with the full iota list. The
 * server runs that list through the live CastingVM in one shot, preserving
 * ravenmind and all existing stack state. Execution semantics are the same
 * as normal staff casting: unescaped literals still mishap.
 */
public final class MenuActionSender {

    public enum InputKind {
        STRING,
        DOUBLE
    }

    public record InputDatum(int order, InputKind kind, String stringValue, double doubleValue) {
        public static InputDatum string(int order, String value) {
            return new InputDatum(order, InputKind.STRING, value, 0.0);
        }

        public static InputDatum number(int order, double value) {
            return new InputDatum(order, InputKind.DOUBLE, "", value);
        }
    }

    private MenuActionSender() {
    }

    public static void send(MenuEntry entry, InteractionHand hand, MenuPayload.DispatchSource dispatchSource, List<InputDatum> inputs) {
        if (!entry.isButton()) {
            Manifestation.LOGGER.debug(
                    "MenuActionSender: entry '{}' is not a button, ignoring send",
                    entry.label().getString());
            return;
        }

        List<StoredIota> actions = entry.actions();
        if (actions.isEmpty()) {
            Manifestation.LOGGER.debug(
                    "MenuActionSender: empty entry '{}', nothing to send",
                    entry.label().getString());
            return;
        }

        Manifestation.LOGGER.debug(
                "MenuActionSender: dispatching entry '{}' ({} iotas) via hand {}",
                entry.label().getString(), actions.size(), hand);

        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeEnum(hand);
        buf.writeEnum(dispatchSource);
        buf.writeVarInt(inputs.size());
        for (InputDatum input : inputs) {
            buf.writeVarInt(input.order());
            buf.writeEnum(input.kind());
            if (input.kind() == InputKind.STRING) {
                buf.writeUtf(input.stringValue());
            } else {
                buf.writeDouble(input.doubleValue());
            }
        }
        buf.writeVarInt(actions.size());
        for (StoredIota stored : actions) {
            // StoredIota.write puts a single CompoundTag on the wire.
            stored.write(buf);
        }

        ClientPlayNetworking.send(
                ManifestationNetworking.DISPATCH_ACTION_C2S,
                buf
        );
    }
}
