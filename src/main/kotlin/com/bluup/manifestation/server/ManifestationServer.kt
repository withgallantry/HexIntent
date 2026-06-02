package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.ActionRegistryEntry
import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.common.lib.hex.HexActions
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.equation.EquationEvaluator
import com.bluup.manifestation.common.equation.EquationParticleConfig
import com.bluup.manifestation.common.equation.EquationParticleGenerator
import com.bluup.manifestation.common.ManifestationNetworking
import com.bluup.manifestation.common.menu.MenuPayload
import com.bluup.manifestation.client.menu.execution.MenuActionSender
import com.bluup.manifestation.server.action.OpCreateGridMenu
import com.bluup.manifestation.server.action.OpCreateListMenu
import com.bluup.manifestation.server.action.OpCreateRadialMenu
import com.bluup.manifestation.server.action.OpDestroyManifestation
import com.bluup.manifestation.server.action.OpDestroySplinters
import com.bluup.manifestation.server.action.OpEquationHexCloud
import com.bluup.manifestation.server.action.OpGetSplinterLocation
import com.bluup.manifestation.server.action.OpHexTrail
import com.bluup.manifestation.server.action.OpManifestSplinter
import com.bluup.manifestation.server.action.OpRenewSplinter
import com.bluup.manifestation.server.action.OpOpenCorridorPortal
import com.bluup.manifestation.server.action.OpOpenCastingScreen
import com.bluup.manifestation.server.action.OpPresenceIntent
import com.bluup.manifestation.server.action.OpClearStack
import com.bluup.manifestation.server.action.OpExitIfInteracting
import com.bluup.manifestation.server.action.OpSetCharmCastSound
import com.bluup.manifestation.server.action.OpSpellCircle
import com.bluup.manifestation.server.action.OpMemoryReflection
import com.bluup.manifestation.server.action.OpReplayMemory
import com.bluup.manifestation.server.action.OpStoreMemory
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
import com.bluup.manifestation.server.block.EquationSynthBlockEntity
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.item.ManifestationItems
import com.bluup.manifestation.server.iota.EquationParticleIota
import com.bluup.manifestation.server.iota.ManifestationUiIotaTypes
import com.bluup.manifestation.server.recipe.ManifestationRecipes
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
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Server entrypoint for Manifestation.
 * Registers menu actions, UI iota types, and the menu dispatch packet handler.
 */
object ManifestationServer : ModInitializer {

    private const val MAX_INPUTS = 80
    private const val MAX_ACTION_IOTAS = 1024
    private const val MAX_INPUT_LIST_ITEMS = 128
    private const val MAX_INPUT_STRING_CHARS = 500
    private const val MAX_EQUATION_CHARS = EquationParticleConfig.MAX_EXPR_CHARS

    const val MAX_EQUATION_EVAL_BUDGET_SERVER: Int = 36_000

    override fun onInitialize() {
        Manifestation.LOGGER.info("Manifestation server initializing.")
        InteropFlags.logDetectedInterop()

        ManifestationConfig.load()
        ManifestationItems.register()
        ManifestationBlocks.register()
        ManifestationRecipes.register()

        registerIotaTypes()
        registerActions()
        registerC2SReceivers()
        registerLifecycleCleanup()
        SplinterRuntime.register()

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
            StaffCastSoundController.clearForPlayer(playerId)
        })

        ServerPlayConnectionEvents.DISCONNECT.register(ServerPlayConnectionEvents.Disconnect { handler, _ ->
            val playerId = handler.player.uuid
            MenuSessionRegistry.clearForPlayer(playerId)
            MenuDispatchAbuseGuard.clearForPlayer(playerId)
            MenuOpenLoopGuard.clearForPlayer(playerId)
            StaffCastSoundController.clearForPlayer(playerId)
        })

        ServerPlayerEvents.AFTER_RESPAWN.register(ServerPlayerEvents.AfterRespawn { oldPlayer, newPlayer, _ ->
            MenuSessionRegistry.clearForPlayer(oldPlayer.uuid)
            MenuDispatchAbuseGuard.clearForPlayer(oldPlayer.uuid)
            MenuOpenLoopGuard.clearForPlayer(oldPlayer.uuid)

            MenuSessionRegistry.clearForPlayer(newPlayer.uuid)
            MenuDispatchAbuseGuard.clearForPlayer(newPlayer.uuid)
            MenuOpenLoopGuard.clearForPlayer(newPlayer.uuid)
            StaffCastSoundController.clearForPlayer(oldPlayer.uuid)
            StaffCastSoundController.clearForPlayer(newPlayer.uuid)
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
        registerAction("intent_checkbox", "awwaqwedwwdaeedd", HexDir.NORTH_EAST, OpUiCheckbox)
        registerAction("intent_select_list", "awwaqwedwwdaaedd", HexDir.NORTH_EAST, OpUiSelectList)
        registerAction("intent_section", "awwaqwedwwdawde", HexDir.NORTH_EAST, OpUiSection)
        registerAction("intent_dropdown", "awwaqwedwwdawaq", HexDir.NORTH_EAST, OpUiDropdown)

        registerAction("link_intent_relay", "edeweqaq", HexDir.SOUTH_WEST, OpLinkIntentRelay)
        registerAction("unlink_intent_relay", "edeweqaqq", HexDir.SOUTH_WEST, OpUnlinkIntentRelay)
        registerAction("open_corridor_portal", "edqqdeew", HexDir.NORTH_WEST, OpOpenCorridorPortal)

        registerAction("presence_intent", "edewqaqdeeeweee", HexDir.NORTH_WEST, OpPresenceIntent)
        registerAction("destroy_manifestation", "edeeedwwaq", HexDir.NORTH_WEST, OpDestroyManifestation)

        registerAction("manifest_splinter", "dedade", HexDir.SOUTH_WEST, OpManifestSplinter)
        registerAction("destroy_splinters", "dedadeaqaww", HexDir.SOUTH_WEST, OpDestroySplinters)
        registerAction("get_splinter_location", "dedadeeweewewewee", HexDir.SOUTH_WEST, OpGetSplinterLocation)
        registerAction("renew_splinter", "dedaded", HexDir.SOUTH_WEST, OpRenewSplinter)
        registerAction("hex_trail", "qaqead", HexDir.NORTH_EAST, OpHexTrail)
        registerAction("equation_hex_cloud", "qaqeaddwe", HexDir.NORTH_EAST, OpEquationHexCloud)
        registerAction("spell_circle", "qqqqqeawqwqwqwqwqw", HexDir.SOUTH_WEST, OpSpellCircle)
        registerAction("set_charm_cast_sound", "wedwwdwee", HexDir.EAST, OpSetCharmCastSound)
        registerAction("memory_reflection", "qwawqwaqw", HexDir.EAST, OpMemoryReflection)
        registerAction("replay_memory", "qwawqwaa", HexDir.EAST, OpReplayMemory)
        registerAction("store_memory", "qwawqwaqa", HexDir.EAST, OpStoreMemory)
        registerAction("exit_if_interacting", "qaqqqqe", HexDir.EAST, OpExitIfInteracting)
        registerAction("open_casting_screen", "aqaeawqqwqwqqw", HexDir.SOUTH_WEST, OpOpenCastingScreen)
        registerAction("clear_stack", "aqaeawqqwa", HexDir.SOUTH_WEST, OpClearStack)
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

                    null -> {
                        Manifestation.LOGGER.warn(
                            "Manifestation dispatch: rejecting packet from {} due to null input kind",
                            player.name.string
                        )
                        return@registerGlobalReceiver
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
                val resolved = MenuSessionRegistry.resolveAndConsume(player, sessionToken)
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
                val preservedImage = CastingImage.loadFromNbt(dispatch.image.copy(), world)
                MenuActionDispatcher.dispatch(
                    player,
                    dispatch.hand,
                    dispatch.source,
                    dispatch.circleContext,
                    inputs,
                    iotas,
                    preservedImage
                )
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.WRITE_EQUATION_PARTICLE_C2S
        ) { server, player, _, buf, _ ->
            val pos = buf.readBlockPos()
            val xExpr = buf.readUtf(MAX_EQUATION_CHARS)
            val yExpr = buf.readUtf(MAX_EQUATION_CHARS)
            val zExpr = buf.readUtf(MAX_EQUATION_CHARS)
            val tMin = buf.readDouble()
            val tMax = buf.readDouble()
            val uMin = buf.readDouble()
            val uMax = buf.readDouble()
            val useU = buf.readBoolean()
            val pointCount = buf.readVarInt()
            val colorMode = buf.readUtf(EquationParticleConfig.MAX_COLOR_MODE_CHARS)
            val fixedR = buf.readDouble()
            val fixedG = buf.readDouble()
            val fixedB = buf.readDouble()
            val gradientStartR = buf.readDouble()
            val gradientStartG = buf.readDouble()
            val gradientStartB = buf.readDouble()
            val gradientEndR = buf.readDouble()
            val gradientEndG = buf.readDouble()
            val gradientEndB = buf.readDouble()
            val colorExprR = buf.readUtf(MAX_EQUATION_CHARS)
            val colorExprG = buf.readUtf(MAX_EQUATION_CHARS)
            val colorExprB = buf.readUtf(MAX_EQUATION_CHARS)
            val animationPreset = buf.readUtf(32)
            val renderDensity = buf.readDouble()

            server.execute {
                val level = player.serverLevel()
                if (!player.isAlive || player.blockPosition().distSqr(pos) > 8.0 * 8.0) {
                    player.displayClientMessage(Component.literal("Equation write failed: too far from synthesizer."), false)
                    return@execute
                }

                val be = level.getBlockEntity(pos) as? EquationSynthBlockEntity
                if (be == null) {
                    player.displayClientMessage(Component.literal("Equation write failed: synthesizer missing."), false)
                    return@execute
                }
                if (!be.hasFocus()) {
                    player.displayClientMessage(Component.literal("Equation write failed: no focus in synthesizer."), false)
                    return@execute
                }

                val config = EquationParticleConfig(
                    xExpr,
                    yExpr,
                    zExpr,
                    tMin,
                    tMax,
                    uMin,
                    uMax,
                    useU,
                    pointCount,
                    colorMode,
                    fixedR,
                    fixedG,
                    fixedB,
                    gradientStartR,
                    gradientStartG,
                    gradientStartB,
                    gradientEndR,
                    gradientEndG,
                    gradientEndB,
                    colorExprR,
                    colorExprG,
                    colorExprB
                )

                val normalized = try {
                    config.validateStrict()
                    val out = config.normalized()
                    EquationEvaluator.compile(out.xExpr())
                    EquationEvaluator.compile(out.yExpr())
                    EquationEvaluator.compile(out.zExpr())
                    if (out.colorMode() == "expression") {
                        EquationEvaluator.compile(out.colorExprR())
                        EquationEvaluator.compile(out.colorExprG())
                        EquationEvaluator.compile(out.colorExprB())
                    }
                    val evalCost = EquationParticleGenerator.estimateEvalCost(out)
                    if (evalCost > MAX_EQUATION_EVAL_BUDGET_SERVER) {
                        throw IllegalArgumentException("equation_budget")
                    }
                    out
                } catch (e: IllegalArgumentException) {
                    player.displayClientMessage(Component.literal("Equation write failed: ${equationError(e.message)}."), false)
                    return@execute
                }

                val writeError = be.writeEquation(normalized)
                if (writeError != null) {
                    player.displayClientMessage(Component.literal("Equation write failed: $writeError"), false)
                    return@execute
                }

                be.setAnimationPreset(animationPreset)
                be.setRenderDensity(renderDensity)

                player.displayClientMessage(
                    Component.literal("Equation write complete: ${normalized.pointCount()} points (${if (normalized.useU()) "surface" else "curve"} mode)."),
                    false
                )
            }
        }
    }

    @JvmStatic
    fun openEquationSynth(player: ServerPlayer, pos: BlockPos) {
        val buf = PacketByteBufs.create()
        buf.writeBlockPos(pos)
        ServerPlayNetworking.send(player, ManifestationNetworking.OPEN_EQUATION_SYNTH_S2C, buf)
    }

    private fun equationError(raw: String?): String {
        if (raw.isNullOrBlank()) {
            return "invalid equation"
        }
        return when {
            raw.startsWith("equation_too_long") -> "equation is too long"
            raw.startsWith("color_expression_too_long") -> "color expression is too long"
            raw.startsWith("invalid_color_mode") -> "invalid color mode"
            raw.startsWith("missing_color_expression") -> "color expressions are required for expression mode"
            raw.startsWith("invalid_point_count") -> "point count must be 1..${EquationParticleConfig.MAX_POINTS}"
            raw.startsWith("invalid_range") -> "ranges must be finite"
            raw.startsWith("equation_budget") -> "equation exceeds evaluation budget"
            raw.startsWith("empty_expression") -> "equations cannot be empty"
            raw.startsWith("unknown_function") -> "equation contains an unknown function"
            raw.startsWith("bad_char") -> "equation contains invalid characters"
            raw.startsWith("invalid_arity") -> "function argument count is invalid"
            else -> "invalid equation"
        }
    }

    @JvmStatic
    fun sendMenuTo(
        player: ServerPlayer,
        payload: MenuPayload,
        circleContext: MenuSessionRegistry.CircleContext?,
        sessionImage: CastingImage
    ) {
        val buf = PacketByteBufs.create()

        val payloadWithSession = MenuSessionRegistry.attachSession(
            player,
            payload,
            circleContext,
            sessionImage.serializeToNbt()
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

    @JvmStatic
    fun sendEquationCloudTo(
        player: ServerPlayer,
        origin: net.minecraft.world.phys.Vec3,
        cloudId: Long,
        equation: EquationParticleIota,
        followEntityId: Int? = null,
        followOffset: net.minecraft.world.phys.Vec3? = null
    ) {
        val level = player.serverLevel()
        val radius = 128.0
        for (other in level.server.playerList.players) {
            if (other.serverLevel() != level || other.position().distanceTo(origin) > radius) {
                continue
            }

            val buf = PacketByteBufs.create()
            buf.writeUtf(level.dimension().location().toString())
            buf.writeUUID(player.uuid)
            buf.writeLong(cloudId)
            buf.writeDouble(origin.x)
            buf.writeDouble(origin.y)
            buf.writeDouble(origin.z)
            buf.writeBoolean(followEntityId != null)
            if (followEntityId != null) {
                val offset = followOffset ?: net.minecraft.world.phys.Vec3.ZERO
                buf.writeVarInt(followEntityId)
                buf.writeDouble(offset.x)
                buf.writeDouble(offset.y)
                buf.writeDouble(offset.z)
            }

            buf.writeUtf(equation.xExpr, EquationParticleConfig.MAX_EXPR_CHARS)
            buf.writeUtf(equation.yExpr, EquationParticleConfig.MAX_EXPR_CHARS)
            buf.writeUtf(equation.zExpr, EquationParticleConfig.MAX_EXPR_CHARS)
            buf.writeDouble(equation.tMin)
            buf.writeDouble(equation.tMax)
            buf.writeDouble(equation.uMin)
            buf.writeDouble(equation.uMax)
            buf.writeBoolean(equation.isUseU)
            buf.writeVarInt(equation.pointCount)

            buf.writeUtf(equation.colorMode, EquationParticleConfig.MAX_COLOR_MODE_CHARS)
            buf.writeDouble(equation.fixedR)
            buf.writeDouble(equation.fixedG)
            buf.writeDouble(equation.fixedB)
            buf.writeDouble(equation.gradientStartR)
            buf.writeDouble(equation.gradientStartG)
            buf.writeDouble(equation.gradientStartB)
            buf.writeDouble(equation.gradientEndR)
            buf.writeDouble(equation.gradientEndG)
            buf.writeDouble(equation.gradientEndB)
            buf.writeUtf(equation.colorExprR, EquationParticleConfig.MAX_EXPR_CHARS)
            buf.writeUtf(equation.colorExprG, EquationParticleConfig.MAX_EXPR_CHARS)
            buf.writeUtf(equation.colorExprB, EquationParticleConfig.MAX_EXPR_CHARS)

            ServerPlayNetworking.send(other, ManifestationNetworking.EQUATION_CLOUD_S2C, buf)
        }
    }

    @JvmStatic
    fun sendSpellCircleTo(
        level: ServerLevel,
        origin: net.minecraft.world.phys.Vec3,
        facing: net.minecraft.world.phys.Vec3,
        openingAngle: net.minecraft.world.phys.Vec3,
        lifetimeTicks: Int,
        sizeTier: Int,
        patterns: List<Pair<String, HexDir>>,
        colorizer: FrozenPigment
    ) {
        if (patterns.isEmpty()) {
            return
        }

        val radius = 128.0
        val circleId = level.random.nextLong()
        val lifetime = lifetimeTicks.coerceIn(1, 1200)
        val clampedTier = sizeTier.coerceIn(1, 6)
        val limitedPatterns = patterns.take(48)

        for (other in level.server.playerList.players) {
            if (other.serverLevel() != level || other.position().distanceTo(origin) > radius) {
                continue
            }

            val buf = PacketByteBufs.create()
            buf.writeUtf(level.dimension().location().toString())
            buf.writeLong(circleId)
            buf.writeDouble(origin.x)
            buf.writeDouble(origin.y)
            buf.writeDouble(origin.z)
            buf.writeDouble(facing.x)
            buf.writeDouble(facing.y)
            buf.writeDouble(facing.z)
            buf.writeDouble(openingAngle.x)
            buf.writeDouble(openingAngle.y)
            buf.writeDouble(openingAngle.z)
            buf.writeVarInt(lifetime)
            buf.writeVarInt(clampedTier)
            buf.writeNbt(colorizer.serializeToNBT())
            buf.writeVarInt(limitedPatterns.size)
            for ((signature, startDir) in limitedPatterns) {
                buf.writeUtf(signature, 128)
                buf.writeVarInt(startDir.ordinal)
            }
            ServerPlayNetworking.send(other, ManifestationNetworking.SPELL_CIRCLE_S2C, buf)
        }
    }
}
