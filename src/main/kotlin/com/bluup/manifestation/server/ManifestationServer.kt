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
import com.bluup.manifestation.server.action.OpUiButton
import com.bluup.manifestation.server.action.OpUiDropdown
import com.bluup.manifestation.server.action.OpUiInput
import com.bluup.manifestation.server.action.OpUiSection
import com.bluup.manifestation.server.action.OpUiSlider
import com.bluup.manifestation.server.iota.ManifestationUiIotaTypes
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Registry
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand

/**
 * Server entrypoint for Manifestation.
 * Registers menu actions, UI iota types, and the menu dispatch packet handler.
 */
object ManifestationServer : ModInitializer {

    private const val LIST_MENU_SIG = "awwaqwedwwd"
    private val LIST_MENU_DIR = HexDir.NORTH_EAST

    private const val GRID_MENU_SIG = "awwaeawwaqwddad"
    private val GRID_MENU_DIR = HexDir.NORTH_EAST

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

    override fun onInitialize() {
        Manifestation.LOGGER.info("Manifestation server initializing.")

        registerIotaTypes()
        registerActions()
        registerC2SReceivers()

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
    }

    /**
     * Wire up the menu-dispatch packet receiver.
     */
    private fun registerC2SReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.DISPATCH_ACTION_C2S
        ) { server, player, _, buf, _ ->
            val hand = buf.readEnum(InteractionHand::class.java)
            val inputCount = buf.readVarInt()
            val inputs = mutableListOf<MenuActionDispatcher.InputDatum>()
            repeat(inputCount) {
                val order = buf.readVarInt()
                when (buf.readEnum(MenuActionSender.InputKind::class.java)) {
                    MenuActionSender.InputKind.STRING -> {
                        val value = buf.readUtf()
                        inputs.add(MenuActionDispatcher.InputDatum.string(order, value))
                    }

                    MenuActionSender.InputKind.DOUBLE -> {
                        val value = buf.readDouble()
                        inputs.add(MenuActionDispatcher.InputDatum.number(order, value))
                    }
                }
            }
            val count = buf.readVarInt()
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
                MenuActionDispatcher.dispatch(player, hand, inputs, iotas)
            }
        }
    }

    @JvmStatic
    fun sendMenuTo(player: ServerPlayer, payload: MenuPayload) {
        val buf = PacketByteBufs.create()
        payload.write(buf)
        ServerPlayNetworking.send(player, ManifestationNetworking.SHOW_MENU_S2C, buf)
    }
}
