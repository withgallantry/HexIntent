package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.ActionRegistryEntry
import at.petrak.hexcasting.api.HexAPI
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
import com.bluup.manifestation.server.action.OpSilenceNextCastSound
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
import com.bluup.manifestation.server.action.MenuOpenLoopGuard
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.echo.EchoRuntime
import com.bluup.manifestation.server.iota.ManifestationUiIotaTypes
import com.bluup.manifestation.server.splinter.SplinterRuntime
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Registry
import net.minecraft.network.chat.Component
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import java.util.UUID

/**
 * Server entrypoint for Manifestation.
 * Registers menu actions, UI iota types, and the menu dispatch packet handler.
 */
object ManifestationServer : ModInitializer {

    private const val MAX_INPUTS = 80
    private const val MAX_ACTION_IOTAS = 1024
    private const val MAX_INPUT_LIST_ITEMS = 128
    private const val MAX_INPUT_STRING_CHARS = 256

    override fun onInitialize() {
        Manifestation.LOGGER.info("Manifestation server initializing.")

        ManifestationConfig.load()
        ManifestationBlocks.register()

        registerIotaTypes()
        registerActions()
        registerC2SReceivers()
        registerLifecycleCleanup()
        EchoRuntime.register()
        SplinterRuntime.register()

        // Register a listener for all CastingEnvironment creations
        at.petrak.hexcasting.api.casting.eval.CastingEnvironment.addCreateEventListener { env, userData ->
            // Inject Manifestation hooks or perform environment-specific setup here
            // Example: log or register menu actions if needed
            Manifestation.LOGGER.info("Manifestation: Detected new CastingEnvironment of type {} (userData={})", env.javaClass.name, userData)
            // If you need to register per-environment hooks, do it here
        }

        Manifestation.LOGGER.info(
            "Manifestation: registered menu constructors, menu actions, ui iota types, and dispatch receiver."
        )
    }

    private fun registerLifecycleCleanup() {
        ServerPlayConnectionEvents.JOIN.register(ServerPlayConnectionEvents.Join { handler, _, _ ->
            val playerId = handler.player.uuid
            MenuSessionRegistry.clearForPlayer(playerId)
            MenuDispatchAbuseGuard.clearForPlayer(playerId)
            MenuOpenLoopGuard.clearForPlayer(playerId)
            CastSoundSuppressor.clearForPlayer(playerId)
        })

        ServerPlayConnectionEvents.DISCONNECT.register(ServerPlayConnectionEvents.Disconnect { handler, _ ->
            val playerId = handler.player.uuid
            MenuSessionRegistry.clearForPlayer(playerId)
            MenuDispatchAbuseGuard.clearForPlayer(playerId)
            MenuOpenLoopGuard.clearForPlayer(playerId)
            CastSoundSuppressor.clearForPlayer(playerId)
        })

        ServerPlayerEvents.AFTER_RESPAWN.register(ServerPlayerEvents.AfterRespawn { oldPlayer, newPlayer, _ ->
            MenuSessionRegistry.clearForPlayer(oldPlayer.uuid)
            MenuDispatchAbuseGuard.clearForPlayer(oldPlayer.uuid)
            MenuOpenLoopGuard.clearForPlayer(oldPlayer.uuid)

            MenuSessionRegistry.clearForPlayer(newPlayer.uuid)
            MenuDispatchAbuseGuard.clearForPlayer(newPlayer.uuid)
            MenuOpenLoopGuard.clearForPlayer(newPlayer.uuid)
            CastSoundSuppressor.clearForPlayer(oldPlayer.uuid)
            CastSoundSuppressor.clearForPlayer(newPlayer.uuid)
        })
    }

    private fun registerIotaTypes() {
        ManifestationUiIotaTypes.register()
    }

    private fun registerActions() {
        registerAction("create_list_menu", "awwaqwedwwd", HexDir.NORTH_EAST, OpCreateListMenu)
        registerAction("create_grid_menu", "awwaeawwaqwddad", HexDir.NORTH_EAST, OpCreateGridMenu)
        registerAction("create_radial_menu", "awwaeawwaqwddade", HexDir.NORTH_EAST, OpCreateRadialMenu)

        registerAction("intent_button", "awwaqwedwwdaa", HexDir.NORTH_EAST, OpUiButton)
        registerAction("intent_input", "awwaqwedwwdad", HexDir.NORTH_EAST, OpUiInput)
        registerAction("intent_numeric_input", "awwaqwedwwdadq", HexDir.NORTH_EAST, OpUiNumericInput)
        registerAction("intent_slider", "awwaqwedwwdaw", HexDir.NORTH_EAST, OpUiSlider)
        registerAction("intent_checkbox", "awwaqwedwwdadee", HexDir.NORTH_EAST, OpUiCheckbox)
        registerAction("intent_select_list", "awwaqwedwwdaaedd", HexDir.NORTH_EAST, OpUiSelectList)
        registerAction("intent_section", "awwaqwedwwdawde", HexDir.NORTH_EAST, OpUiSection)
        registerAction("intent_dropdown", "awwaqwedwwdawaq", HexDir.NORTH_EAST, OpUiDropdown)

        registerAction("link_intent_relay", "edeweqaq", HexDir.SOUTH_WEST, OpLinkIntentRelay)
        registerAction("unlink_intent_relay", "edeweqaqq", HexDir.SOUTH_WEST, OpUnlinkIntentRelay)
        registerAction("open_corridor_portal", "edqqdeew", HexDir.NORTH_WEST, OpOpenCorridorPortal)

        registerAction("presence_intent", "edewqaqdeeeweee", HexDir.NORTH_WEST, OpPresenceIntent)
        registerAction("manifest_echo", "qqqqqaweeee", HexDir.WEST, OpManifestEcho)
        registerAction("destroy_manifestation", "edeeedwwaq", HexDir.NORTH_WEST, OpDestroyManifestation)

        registerAction("manifest_splinter", "dedade", HexDir.SOUTH_WEST, OpManifestSplinter)
        registerAction("destroy_splinters", "dedadeaqaww", HexDir.SOUTH_WEST, OpDestroySplinters)
        registerAction("get_splinter_location", "dedadeeweewewewee", HexDir.SOUTH_WEST, OpGetSplinterLocation)
        registerAction("renew_splinter", "dedaded", HexDir.SOUTH_WEST, OpRenewSplinter)
        registerAction("hex_trail", "qaqead", HexDir.NORTH_EAST, OpHexTrail)
        registerAction("silence_next_cast", "qaqeadwq", HexDir.NORTH_EAST, OpSilenceNextCastSound)
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
            val sessionToken = buf.readUUID()
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
                        inputs.add(MenuActionDispatcher.InputDatum.double(order, value))
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
                val resolved = MenuSessionRegistry.resolveAndConsume(player, sessionToken, hand, dispatchSource)
                val dispatch = resolved.dispatch
                if (dispatch == null) {
                    val message = resolved.rejectMessage ?: Component.translatable("message.manifestation.menu_expired")
                    player.displayClientMessage(message, true)
                    return@execute
                }

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
                val preservedStack: List<Iota> = dispatch.stack.mapNotNull { stackTag ->
                    try {
                        IotaType.deserialize(stackTag.copy(), world)
                    } catch (t: Throwable) {
                        Manifestation.LOGGER.warn(
                            "Manifestation dispatch: skipping preserved stack iota that failed to deserialize",
                            t
                        )
                        null
                    }
                }
                MenuActionDispatcher.dispatch(
                    player,
                    dispatch.hand,
                    dispatch.source,
                    inputs,
                    iotas,
                    preservedStack,
                    dispatch.ravenmind
                )
            }
        }
    }

    @JvmStatic
    fun sendMenuTo(
        player: ServerPlayer,
        payload: MenuPayload,
        circleContext: MenuSessionRegistry.CircleContext?,
        explicitSessionStack: List<Iota>?,
        explicitSessionRavenmind: CompoundTag?
    ) {
        val buf = PacketByteBufs.create()
        val hand = payload.hand()
        val dispatchSource = payload.dispatchSource()
        val sessionStack: List<CompoundTag> = if (explicitSessionStack != null) {
            explicitSessionStack.map { IotaType.serialize(it) }
        } else if (dispatchSource == MenuPayload.DispatchSource.STAFF) {
            val vm = at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getStaffcastVM(player, hand)
            vm.image.stack.map { IotaType.serialize(it) }
        } else {
            listOf()
        }
        val sessionRavenmind: CompoundTag? = if (explicitSessionRavenmind != null) {
            explicitSessionRavenmind.copy()
        } else if (dispatchSource == MenuPayload.DispatchSource.STAFF) {
            // Legacy fallback for staff callers that did not provide an explicit image ravenmind.
            val vm = at.petrak.hexcasting.xplat.IXplatAbstractions.INSTANCE.getStaffcastVM(player, hand)
            val userData = vm.image.userData
            if (userData.contains(HexAPI.RAVENMIND_USERDATA)) {
                userData.getCompound(HexAPI.RAVENMIND_USERDATA).copy()
            } else {
                null
            }
        } else {
            null
        }

        val payloadWithSession = MenuSessionRegistry.attachSession(
            player,
            payload,
            circleContext,
            sessionStack,
            sessionRavenmind
        )
        payloadWithSession.write(buf)
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
