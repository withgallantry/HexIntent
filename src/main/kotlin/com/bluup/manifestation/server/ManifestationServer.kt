package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.ActionRegistryEntry
import at.petrak.hexcasting.api.casting.castables.Action
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
import com.bluup.manifestation.server.action.OpDestroySplinters
import com.bluup.manifestation.server.action.OpGetSplinterLocation
import com.bluup.manifestation.server.action.OpHexTrail
import com.bluup.manifestation.server.action.OpManifestSplinter
import com.bluup.manifestation.server.action.OpRenewSplinter
import com.bluup.manifestation.server.action.OpOpenCorridorPortal
import com.bluup.manifestation.server.action.OpManifestEcho
import com.bluup.manifestation.server.action.OpPresenceIntent
import com.bluup.manifestation.server.action.OpUiButton
import com.bluup.manifestation.server.action.OpUiCheckbox
import com.bluup.manifestation.server.action.OpUiDropdown
import com.bluup.manifestation.server.action.OpUiInput
import com.bluup.manifestation.server.action.OpUiNumericInput
import com.bluup.manifestation.server.action.OpUiSelectList
import com.bluup.manifestation.server.action.OpLinkIntentRelay
import com.bluup.manifestation.server.action.OpUnlinkIntentRelay
import com.bluup.manifestation.server.action.OpUiSection
import com.bluup.manifestation.server.action.OpUiSlider
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.echo.EchoRuntime
import com.bluup.manifestation.server.iota.ManifestationUiIotaTypes
import com.bluup.manifestation.server.splinter.SplinterRuntime
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
    private const val MAX_INPUT_LIST_ITEMS = 128
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

    private const val UI_NUMERIC_INPUT_SIG = "awwaqwedwwdadq"
    private val UI_NUMERIC_INPUT_DIR = HexDir.NORTH_EAST

    private const val UI_SLIDER_SIG = "awwaqwedwwdaw"
    private val UI_SLIDER_DIR = HexDir.NORTH_EAST

    private const val UI_CHECKBOX_SIG = "awwaqwedwwdadee"
    private val UI_CHECKBOX_DIR = HexDir.NORTH_EAST

    private const val UI_SELECT_LIST_SIG = "awwaqwedwwdaaedd"
    private val UI_SELECT_LIST_DIR = HexDir.NORTH_EAST

    private const val UI_SECTION_SIG = "awwaqwedwwdawde"
    private val UI_SECTION_DIR = HexDir.NORTH_EAST

    private const val UI_DROPDOWN_SIG = "awwaqwedwwdawaq"
    private val UI_DROPDOWN_DIR = HexDir.NORTH_EAST

    private const val LINK_INTENT_RELAY_SIG = "edeweqaq"
    private val LINK_INTENT_RELAY_DIR = HexDir.SOUTH_WEST

    private const val UNLINK_INTENT_RELAY_SIG = "edeweqaqq"
    private val UNLINK_INTENT_RELAY_DIR = HexDir.SOUTH_WEST

    private const val OPEN_CORRIDOR_PORTAL_SIG = "edqqdeew"
    private val OPEN_CORRIDOR_PORTAL_DIR = HexDir.NORTH_WEST

    private const val PRESENCE_INTENT_SIG = "edewqaqdeeeweee"
    private val PRESENCE_INTENT_DIR = HexDir.NORTH_WEST

    private const val MANIFEST_ECHO_SIG = "qqqqqaweeee"
    private val MANIFEST_ECHO_DIR = HexDir.WEST

    private const val DESTROY_MANIFESTATION_SIG = "edeeedwwaq"
    private val DESTROY_MANIFESTATION_DIR = HexDir.NORTH_WEST

    private const val MANIFEST_SPLINTER_SIG = "dedade"
    private val MANIFEST_SPLINTER_DIR = HexDir.SOUTH_WEST

    private const val DESTROY_SPLINTERS_SIG = "dedadeaqaww"
    private val DESTROY_SPLINTERS_DIR = HexDir.SOUTH_WEST

    private const val GET_SPLINTER_LOCATION_SIG = "dedadeeweewewewee"
    private val GET_SPLINTER_LOCATION_DIR = HexDir.SOUTH_WEST

    private const val RENEW_SPLINTER_SIG = "dedaded"
    private val RENEW_SPLINTER_DIR = HexDir.SOUTH_WEST

    private const val HEX_TRAIL_SIG = "qaqead"
    private val HEX_TRAIL_DIR = HexDir.NORTH_EAST

    override fun onInitialize() {
        Manifestation.LOGGER.info("Manifestation server initializing.")

        ManifestationConfig.load()
        ManifestationBlocks.register()

        registerIotaTypes()
        registerActions()
        registerC2SReceivers()
        EchoRuntime.register()
        SplinterRuntime.register()

        Manifestation.LOGGER.info(
            "Manifestation: registered menu constructors, menu actions, ui iota types, and dispatch receiver."
        )
    }

    private fun registerIotaTypes() {
        ManifestationUiIotaTypes.register()
    }

    private fun registerActions() {
        registerAction("create_list_menu", LIST_MENU_SIG, LIST_MENU_DIR, OpCreateListMenu)
        registerAction("create_grid_menu", GRID_MENU_SIG, GRID_MENU_DIR, OpCreateGridMenu)
        registerAction("create_radial_menu", RADIAL_MENU_SIG, RADIAL_MENU_DIR, OpCreateRadialMenu)

        registerAction("intent_button", UI_BUTTON_SIG, UI_BUTTON_DIR, OpUiButton)
        registerAction("intent_input", UI_INPUT_SIG, UI_INPUT_DIR, OpUiInput)
        registerAction("intent_numeric_input", UI_NUMERIC_INPUT_SIG, UI_NUMERIC_INPUT_DIR, OpUiNumericInput)
        registerAction("intent_slider", UI_SLIDER_SIG, UI_SLIDER_DIR, OpUiSlider)
        registerAction("intent_checkbox", UI_CHECKBOX_SIG, UI_CHECKBOX_DIR, OpUiCheckbox)
        registerAction("intent_select_list", UI_SELECT_LIST_SIG, UI_SELECT_LIST_DIR, OpUiSelectList)
        registerAction("intent_section", UI_SECTION_SIG, UI_SECTION_DIR, OpUiSection)
        registerAction("intent_dropdown", UI_DROPDOWN_SIG, UI_DROPDOWN_DIR, OpUiDropdown)

        registerAction("link_intent_relay", LINK_INTENT_RELAY_SIG, LINK_INTENT_RELAY_DIR, OpLinkIntentRelay)
        registerAction("unlink_intent_relay", UNLINK_INTENT_RELAY_SIG, UNLINK_INTENT_RELAY_DIR, OpUnlinkIntentRelay)
        registerAction("open_corridor_portal", OPEN_CORRIDOR_PORTAL_SIG, OPEN_CORRIDOR_PORTAL_DIR, OpOpenCorridorPortal)

        registerAction("presence_intent", PRESENCE_INTENT_SIG, PRESENCE_INTENT_DIR, OpPresenceIntent)
        registerAction("manifest_echo", MANIFEST_ECHO_SIG, MANIFEST_ECHO_DIR, OpManifestEcho)
        registerAction("destroy_manifestation", DESTROY_MANIFESTATION_SIG, DESTROY_MANIFESTATION_DIR, OpDestroyManifestation)

        registerAction("manifest_splinter", MANIFEST_SPLINTER_SIG, MANIFEST_SPLINTER_DIR, OpManifestSplinter)
        registerAction("destroy_splinters", DESTROY_SPLINTERS_SIG, DESTROY_SPLINTERS_DIR, OpDestroySplinters)
        registerAction("get_splinter_location", GET_SPLINTER_LOCATION_SIG, GET_SPLINTER_LOCATION_DIR, OpGetSplinterLocation)
        registerAction("renew_splinter", RENEW_SPLINTER_SIG, RENEW_SPLINTER_DIR, OpRenewSplinter)
        registerAction("hex_trail", HEX_TRAIL_SIG, HEX_TRAIL_DIR, OpHexTrail)
    }

    private fun registerAction(idPath: String, signature: String, startDir: HexDir, action: Action) {
        val pattern = try {
            HexPattern.fromAngles(signature, startDir)
        } catch (e: IllegalStateException) {
            Manifestation.LOGGER.error(
                "Skipping action registration for {} because pattern '{}' from {} is invalid.",
                idPath,
                signature,
                startDir,
                e
            )
            return
        }

        Registry.register(
            HexActions.REGISTRY,
            Manifestation.id(idPath),
            ActionRegistryEntry(pattern, action)
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

                    MenuActionSender.InputKind.IOTA_LIST -> {
                        val selectedCount = buf.readVarInt()
                        if (selectedCount < 0 || selectedCount > MAX_INPUT_LIST_ITEMS) {
                            Manifestation.LOGGER.warn(
                                "Manifestation dispatch: rejecting packet from {} due to invalid selectedCount {} (max {})",
                                player.name.string,
                                selectedCount,
                                MAX_INPUT_LIST_ITEMS
                            )
                            return@registerGlobalReceiver
                        }

                        val tags = mutableListOf<net.minecraft.nbt.CompoundTag>()
                        repeat(selectedCount) {
                            val tag = buf.readNbt()
                            if (tag == null) {
                                Manifestation.LOGGER.warn(
                                    "Manifestation dispatch: rejecting packet from {} due to null selected iota tag",
                                    player.name.string
                                )
                                return@registerGlobalReceiver
                            }
                            tags.add(tag)
                        }
                        inputs.add(MenuActionDispatcher.InputDatum.iotaList(order, tags))
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

    @JvmStatic
    fun sendHexTrailTo(
        player: ServerPlayer?, // nullable for backward compat, but unused
        position: net.minecraft.world.phys.Vec3,
        colorStart: net.minecraft.world.phys.Vec3,
        colorEnd: net.minecraft.world.phys.Vec3,
        transitionTicks: Int,
        trailId: Long,
        particleType: Int
    ) {
        val source = player ?: return
        val level = source.serverLevel()
        // Broadcast to all players within 32 blocks of the trail position
        val radius = 128.0
        for (other in level.server.playerList.players) {
            if (other.serverLevel() == level && other.position().distanceTo(position) <= radius) {
                val buf = PacketByteBufs.create()
                buf.writeUtf(level.dimension().location().toString())
                buf.writeUUID(source.uuid)
                buf.writeLong(trailId)
                buf.writeDouble(position.x)
                buf.writeDouble(position.y)
                buf.writeDouble(position.z)
                buf.writeFloat(colorStart.x.toFloat())
                buf.writeFloat(colorStart.y.toFloat())
                buf.writeFloat(colorStart.z.toFloat())
                buf.writeFloat(colorEnd.x.toFloat())
                buf.writeFloat(colorEnd.y.toFloat())
                buf.writeFloat(colorEnd.z.toFloat())
                buf.writeVarInt(transitionTicks.coerceAtLeast(1))
                buf.writeVarInt(particleType.coerceIn(0, 14))
                ServerPlayNetworking.send(other, ManifestationNetworking.HEX_TRAIL_S2C, buf)
            }
        }
    }
}
