package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.ActionRegistryEntry
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.hex.HexActions
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.ManifestationNetworking
import com.bluup.manifestation.common.menu.MenuPayload
import com.bluup.manifestation.client.menu.execution.MenuActionSender
import com.bluup.manifestation.server.action.OpCreateGridMenu
import com.bluup.manifestation.server.action.OpCreateListMenu
import com.bluup.manifestation.server.action.OpCreateRadialMenu
import com.bluup.manifestation.server.action.OpDestroyManifestation
import com.bluup.manifestation.server.action.OpOpenCorridorPortal
import com.bluup.manifestation.server.action.OpManifestEcho
import com.bluup.manifestation.server.action.OpPresenceIntent
import com.bluup.manifestation.server.action.OpUiButton
import com.bluup.manifestation.server.action.OpUiDropdown
import com.bluup.manifestation.server.action.OpUiInput
import com.bluup.manifestation.server.action.OpLinkIntentRelay
import com.bluup.manifestation.server.action.OpUnlinkIntentRelay
import com.bluup.manifestation.server.action.OpUiSection
import com.bluup.manifestation.server.action.OpUiSlider
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.echo.EchoRuntime
import com.bluup.manifestation.server.iota.ManifestationUiIotaTypes
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Registry
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand

/**
 * Server entrypoint for Manifestation.
 * Registers menu actions, UI iota types, and the menu dispatch packet handler.
 */
object ManifestationServer : ModInitializer {

    private const val MAX_INPUTS = 80
    private const val MAX_ACTION_IOTAS = 1024
    private const val MAX_INPUT_STRING_CHARS = 256

    private const val LIST_MENU_SIG = "awwaqwedwwd"
    private val LIST_MENU_DIR = HexDir.NORTH_EAST

    private const val GRID_MENU_SIG = "awwaeawwaqwddad"
    private val GRID_MENU_DIR = HexDir.NORTH_EAST

    private const val RADIAL_MENU_SIG = "awwaeawwaqwddade"
    private val RADIAL_MENU_DIR = HexDir.NORTH_EAST

    private const val UI_BUTTON_SIG = "awwaqwedwwdaa"
    private val UI_BUTTON_DIR = HexDir.NORTH_EAST

    private const val UI_INPUT_SIG = "awwaqwedwwdad"
    private val UI_INPUT_DIR = HexDir.NORTH_EAST

    private const val UI_SLIDER_SIG = "awwaqwedwwdaw"
    private val UI_SLIDER_DIR = HexDir.NORTH_EAST

    private const val UI_SECTION_SIG = "awwaqwedwwdawde"
    private val UI_SECTION_DIR = HexDir.NORTH_EAST

    private const val UI_DROPDOWN_SIG = "awwaqwedwwdawaq"
    private val UI_DROPDOWN_DIR = HexDir.NORTH_EAST

    private const val LINK_INTENT_RELAY_SIG = "awwaqwedwwdawdw"
    private val LINK_INTENT_RELAY_DIR = HexDir.NORTH_EAST

    private const val UNLINK_INTENT_RELAY_SIG = "awwaqwedwwdawda"
    private val UNLINK_INTENT_RELAY_DIR = HexDir.NORTH_EAST

    private const val OPEN_CORRIDOR_PORTAL_SIG = "awwaqwedwwdawqwe"
    private val OPEN_CORRIDOR_PORTAL_DIR = HexDir.NORTH_EAST

    private const val PRESENCE_INTENT_SIG = "awwaqwedwwdawqea"
    private val PRESENCE_INTENT_DIR = HexDir.NORTH_EAST

    private const val MANIFEST_ECHO_SIG = "awwaqwedwwdawqwea"
    private val MANIFEST_ECHO_DIR = HexDir.NORTH_EAST

    private const val DESTROY_MANIFESTATION_SIG = "awwaqwedwwdawqwew"
    private val DESTROY_MANIFESTATION_DIR = HexDir.NORTH_EAST

    override fun onInitialize() {
        Manifestation.LOGGER.info("Manifestation server initializing.")

        ManifestationConfig.load()
        ManifestationBlocks.register()

        registerIotaTypes()
        registerActions()
        registerC2SReceivers()
        EchoRuntime.register()

        Manifestation.LOGGER.info(
            "Manifestation: registered menu constructors, menu actions, ui iota types, and dispatch receiver."
        )
    }

    private fun registerIotaTypes() {
        ManifestationUiIotaTypes.register()
    }

    private fun registerActions() {
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("create_list_menu"),
            ActionRegistryEntry(
                HexPattern.fromAngles(LIST_MENU_SIG, LIST_MENU_DIR),
                OpCreateListMenu
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("create_grid_menu"),
            ActionRegistryEntry(
                HexPattern.fromAngles(GRID_MENU_SIG, GRID_MENU_DIR),
                OpCreateGridMenu
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("create_radial_menu"),
            ActionRegistryEntry(
                HexPattern.fromAngles(RADIAL_MENU_SIG, RADIAL_MENU_DIR),
                OpCreateRadialMenu
            )
        )

        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("intent_button"),
            ActionRegistryEntry(
                HexPattern.fromAngles(UI_BUTTON_SIG, UI_BUTTON_DIR),
                OpUiButton
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("intent_input"),
            ActionRegistryEntry(
                HexPattern.fromAngles(UI_INPUT_SIG, UI_INPUT_DIR),
                OpUiInput
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("intent_slider"),
            ActionRegistryEntry(
                HexPattern.fromAngles(UI_SLIDER_SIG, UI_SLIDER_DIR),
                OpUiSlider
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("intent_section"),
            ActionRegistryEntry(
                HexPattern.fromAngles(UI_SECTION_SIG, UI_SECTION_DIR),
                OpUiSection
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("intent_dropdown"),
            ActionRegistryEntry(
                HexPattern.fromAngles(UI_DROPDOWN_SIG, UI_DROPDOWN_DIR),
                OpUiDropdown
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("link_intent_relay"),
            ActionRegistryEntry(
                HexPattern.fromAngles(LINK_INTENT_RELAY_SIG, LINK_INTENT_RELAY_DIR),
                OpLinkIntentRelay
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("unlink_intent_relay"),
            ActionRegistryEntry(
                HexPattern.fromAngles(UNLINK_INTENT_RELAY_SIG, UNLINK_INTENT_RELAY_DIR),
                OpUnlinkIntentRelay
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("open_corridor_portal"),
            ActionRegistryEntry(
                HexPattern.fromAngles(OPEN_CORRIDOR_PORTAL_SIG, OPEN_CORRIDOR_PORTAL_DIR),
                OpOpenCorridorPortal
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("presence_intent"),
            ActionRegistryEntry(
                HexPattern.fromAngles(PRESENCE_INTENT_SIG, PRESENCE_INTENT_DIR),
                OpPresenceIntent
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("manifest_echo"),
            ActionRegistryEntry(
                HexPattern.fromAngles(MANIFEST_ECHO_SIG, MANIFEST_ECHO_DIR),
                OpManifestEcho
            )
        )
        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id("destroy_manifestation"),
            ActionRegistryEntry(
                HexPattern.fromAngles(DESTROY_MANIFESTATION_SIG, DESTROY_MANIFESTATION_DIR),
                OpDestroyManifestation
            )
        )
    }

    /**
     * Wire up the menu-dispatch packet receiver.
     */
    private fun registerC2SReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.DISPATCH_ACTION_C2S
        ) { server, player, _, buf, _ ->
            val hand = buf.readEnum(InteractionHand::class.java)
            val dispatchSource = buf.readEnum(MenuPayload.DispatchSource::class.java)
            if (!MenuDispatchAbuseGuard.shouldAllow(player)) {
                return@registerGlobalReceiver
            }

            val inputCount = buf.readVarInt()
            if (inputCount < 0 || inputCount > MAX_INPUTS) {
                Manifestation.LOGGER.warn(
                    "Manifestation dispatch: rejecting packet from {} due to invalid inputCount {} (max {})",
                    player.name.string,
                    inputCount,
                    MAX_INPUTS
                )
                return@registerGlobalReceiver
            }

            val inputs = mutableListOf<MenuActionDispatcher.InputDatum>()
            repeat(inputCount) {
                val order = buf.readVarInt()
                when (buf.readEnum(MenuActionSender.InputKind::class.java)) {
                    MenuActionSender.InputKind.STRING -> {
                        val value = buf.readUtf(MAX_INPUT_STRING_CHARS)
                        inputs.add(MenuActionDispatcher.InputDatum.string(order, value))
                    }

                    MenuActionSender.InputKind.DOUBLE -> {
                        val value = buf.readDouble()
                        inputs.add(MenuActionDispatcher.InputDatum.number(order, value))
                    }
                }
            }

            val count = buf.readVarInt()
            if (count < 0 || count > MAX_ACTION_IOTAS) {
                Manifestation.LOGGER.warn(
                    "Manifestation dispatch: rejecting packet from {} due to invalid iota count {} (max {})",
                    player.name.string,
                    count,
                    MAX_ACTION_IOTAS
                )
                return@registerGlobalReceiver
            }

            val tags = (0 until count).map { buf.readNbt() }
            // Cast execution and session writes must run on the server thread.
            server.execute {
                val world = player.serverLevel()
                val iotas: List<Iota> = tags.mapNotNull { tag ->
                    tag ?: return@mapNotNull null
                    try {
                        IotaType.deserialize(tag, world)
                    } catch (t: Throwable) {
                        Manifestation.LOGGER.warn(
                            "Manifestation dispatch: skipping iota that failed to deserialize",
                            t
                        )
                        null
                    }
                }
                MenuActionDispatcher.dispatch(player, hand, dispatchSource, inputs, iotas)
            }
        }
    }

    @JvmStatic
    fun sendMenuTo(player: ServerPlayer, payload: MenuPayload) {
        val buf = PacketByteBufs.create()
        payload.write(buf)
        ServerPlayNetworking.send(player, ManifestationNetworking.SHOW_MENU_S2C, buf)
    }

    @JvmStatic
    fun sendIntentShifterRunes(level: ServerLevel, pos: BlockPos, outward: Direction, durationTicks: Int) {
        for (player in level.players()) {
            if (player.blockPosition().distSqr(pos) > 64.0 * 64.0) {
                continue
            }

            val buf = PacketByteBufs.create()
            buf.writeBlockPos(pos)
            buf.writeEnum(outward)
            buf.writeVarInt(durationTicks)
            ServerPlayNetworking.send(player, ManifestationNetworking.INTENT_SHIFTER_RUNES_S2C, buf)
        }
    }
}
