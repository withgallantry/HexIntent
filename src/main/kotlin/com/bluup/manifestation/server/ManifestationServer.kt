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
import com.bluup.manifestation.server.action.OpManifestEcho
import com.bluup.manifestation.server.action.OpMakeParticleBlob
import com.bluup.manifestation.server.action.OpParticleScatter
import com.bluup.manifestation.server.action.OpParticleBlobScatter
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
import com.bluup.manifestation.server.action.ParticleBlobCodec
import com.bluup.manifestation.server.block.EquationSynthBlockEntity
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.block.ParticleImporterBlockEntity
import com.bluup.manifestation.server.iota.EquationParticleIota
import com.bluup.manifestation.server.echo.EchoRuntime
import com.bluup.manifestation.server.iota.ManifestationUiIotaTypes
import com.bluup.manifestation.server.splinter.SplinterRuntime
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    private const val MAX_INPUT_STRING_CHARS = 256
    private const val MAX_IMPORT_JSON_CHARS = 200_000
    private const val MAX_EQUATION_CHARS = EquationParticleConfig.MAX_EXPR_CHARS

    const val MAX_EQUATION_EVAL_BUDGET_SERVER: Int = 36_000

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

            // Send constellation snapshot on join
            val player = handler.player
            if (ManifestationConfig.constellationFeatureEnabled()) {
                val server = player.server
                if (server != null) {
                    // Add a test constellation if none exist
                    val store = ConstellationStateStore.get(server)
                    if (store.all().isEmpty()) {
                        val testOwner = player.uuid
                        val color = 0xFFAA33 // orange
                        val stars = listOf(
                            ConstellationStateStore.Star(0.0, 0.7, 0.0),
                            ConstellationStateStore.Star(-0.5, 0.0, 0.0),
                            ConstellationStateStore.Star(0.5, 0.0, 0.0)
                        )
                        val edges = listOf(
                            ConstellationStateStore.Edge(0, 1),
                            ConstellationStateStore.Edge(1, 2),
                            ConstellationStateStore.Edge(2, 0)
                        )
                        store.put(ConstellationStateStore.Constellation(testOwner, color, stars, edges, true))
                    }
                    ConstellationSync.sendSnapshotTo(player)
                }
            }
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
        registerAction("particle_scatter", "qaqeaddw", HexDir.NORTH_EAST, OpParticleScatter)
        registerAction("make_particle_blob", "qaqeaddwa", HexDir.NORTH_EAST, OpMakeParticleBlob)
        registerAction("particle_blob_scatter", "qaqeadd", HexDir.NORTH_EAST, OpParticleBlobScatter)
        registerAction("equation_hex_cloud", "qaqeaddwe", HexDir.NORTH_EAST, OpEquationHexCloud)
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

        ServerPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.IMPORT_PARTICLE_BLOB_C2S
        ) { server, player, _, buf, _ ->
            val pos = buf.readBlockPos()
            val rawJson = buf.readUtf(MAX_IMPORT_JSON_CHARS)
            server.execute {
                val level = player.serverLevel()
                if (!player.isAlive || player.blockPosition().distSqr(pos) > 8.0 * 8.0) {
                    player.displayClientMessage(Component.literal("Particle import failed: too far from importer."), false)
                    return@execute
                }

                val be = level.getBlockEntity(pos) as? ParticleImporterBlockEntity
                if (be == null) {
                    player.displayClientMessage(Component.literal("Particle import failed: importer missing."), false)
                    return@execute
                }
                if (!be.hasFocus()) {
                    player.displayClientMessage(Component.literal("Particle import failed: no focus in importer."), false)
                    return@execute
                }

                val parsed = parseParticlesFromJson(rawJson)
                if (parsed.error != null) {
                    player.displayClientMessage(Component.literal("Particle import failed: ${parsed.error}"), false)
                    return@execute
                }

                val points = parsed.points
                if (points.isEmpty()) {
                    player.displayClientMessage(Component.literal("Particle import failed: particles array is empty."), false)
                    return@execute
                }

                val blob = try {
                    ParticleBlobCodec.encode(points, parsed.axisHint, parsed.colorSpec)
                } catch (e: IllegalArgumentException) {
                    val reason = when (e.message) {
                        "too_many_points" -> "too many particles (max ${ParticleBlobCodec.MAX_POINTS})."
                        "blob_too_large" -> "compressed blob exceeds size limit."
                        "gradient_stops" -> "gradient requires at least two color stops."
                        else -> "could not compress particle data."
                    }
                    player.displayClientMessage(Component.literal("Particle import failed: $reason"), false)
                    return@execute
                }

                val writeError = be.writeBlob(blob, points.size)
                if (writeError != null) {
                    player.displayClientMessage(Component.literal("Particle import failed: $writeError"), false)
                    return@execute
                }

                player.displayClientMessage(
                    Component.literal("Particle import complete: ${points.size} points (${blob.size} bytes compressed)."),
                    false
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

                player.displayClientMessage(
                    Component.literal("Equation write complete: ${normalized.pointCount()} points (${if (normalized.useU()) "surface" else "curve"} mode)."),
                    false
                )
            }
        }
    }

    @JvmStatic
    fun openParticleImporter(player: ServerPlayer, pos: BlockPos) {
        val buf = PacketByteBufs.create()
        buf.writeBlockPos(pos)
        ServerPlayNetworking.send(player, ManifestationNetworking.OPEN_PARTICLE_IMPORTER_S2C, buf)
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

    private data class ParsedParticleResult(
        val points: List<ParticleBlobCodec.Point>,
        val axisHint: String?,
        val colorSpec: ParticleBlobCodec.ColorSpec?,
        val error: String?
    )

    private fun parseParticlesFromJson(rawJson: String): ParsedParticleResult {
        val rootObj = try {
            JsonParser.parseString(rawJson).asJsonObject
        } catch (_: Throwable) {
            return ParsedParticleResult(listOf(), null, null, "invalid JSON")
        }

        val particles = rootObj.getAsJsonArray("particles")
            ?: return ParsedParticleResult(listOf(), null, null, "missing particles array")

        val settings = rootObj
            .getAsJsonObject("metadata")
            ?.getAsJsonObject("settings")

        val axisHint = parseAxisHint(settings?.get("coordinateAxis")?.asStringOrNull())

        val out = ArrayList<ParticleBlobCodec.Point>()
        for (elem in particles) {
            if (!elem.isJsonObject) {
                continue
            }

            val obj = elem.asJsonObject
            val x = obj.readFiniteDouble("x") ?: continue
            val y = obj.readFiniteDouble("y") ?: continue
            val z = obj.readFiniteDouble("z") ?: continue
            val r = (obj.readFiniteDouble("r") ?: 1.0).coerceIn(0.0, 1.0)
            val g = (obj.readFiniteDouble("g") ?: 1.0).coerceIn(0.0, 1.0)
            val b = (obj.readFiniteDouble("b") ?: 1.0).coerceIn(0.0, 1.0)

            out.add(ParticleBlobCodec.Point(Vec3(x, y, z), Vec3(r, g, b)))
            if (out.size > ParticleBlobCodec.MAX_POINTS) {
                return ParsedParticleResult(listOf(), null, null, "too many particles (max ${ParticleBlobCodec.MAX_POINTS})")
            }
        }

        val colorSpec = parseColorSpec(rootObj, settings, out)
        return ParsedParticleResult(out, axisHint, colorSpec, null)
    }

    private fun parseColorSpec(
        rootObj: JsonObject,
        settings: JsonObject?,
        points: List<ParticleBlobCodec.Point>
    ): ParticleBlobCodec.ColorSpec? {
        val colorMode = settings?.get("colorMode")?.asStringOrNull()?.lowercase()
        val colorFixed = settings?.get("colorFixed")?.asBooleanOrNull() ?: false

        val fixed = readColorElement(settings?.get("fixedColor"))

        if (colorMode == "gradient") {
            val mode = parseGradientMode(settings)
            val stops = parseGradientStops(rootObj, settings, points, mode)
            if (stops.size >= 2) {
                return ParticleBlobCodec.ColorSpec.Gradient(mode, stops)
            }
            return null
        }

        if (colorMode == "single" || colorMode == "fixed") {
            return ParticleBlobCodec.ColorSpec.Single(fixed ?: points.firstOrNull()?.color ?: Vec3(1.0, 1.0, 1.0))
        }

        if (colorMode == "per_point" || colorMode == "per-point" || colorMode == "particle") {
            return ParticleBlobCodec.ColorSpec.PerPoint
        }

        if (colorMode == null && colorFixed) {
            return ParticleBlobCodec.ColorSpec.Single(fixed ?: points.firstOrNull()?.color ?: Vec3(1.0, 1.0, 1.0))
        }

        return null
    }

    private fun parseGradientMode(settings: JsonObject?): ParticleBlobCodec.GradientMode {
        val explicit = settings?.get("gradientMode")?.asStringOrNull()?.lowercase()
        if (explicit != null) {
            return when (explicit) {
                "x" -> ParticleBlobCodec.GradientMode.X
                "y" -> ParticleBlobCodec.GradientMode.Y
                "z" -> ParticleBlobCodec.GradientMode.Z
                "distance" -> ParticleBlobCodec.GradientMode.DISTANCE
                else -> ParticleBlobCodec.GradientMode.Z
            }
        }

        val direction = settings?.get("gradientDirection")?.asStringOrNull()?.lowercase()
        val axes = parseAxisHint(settings?.get("coordinateAxis")?.asStringOrNull())
        val varying = when {
            axes == null -> listOf('x', 'y', 'z')
            else -> axes.toList().filter { it == 'x' || it == 'y' || it == 'z' }
        }

        val axis = when (direction) {
            "vertical" -> varying.firstOrNull() ?: 'y'
            "horizontal" -> varying.lastOrNull() ?: 'x'
            else -> varying.lastOrNull() ?: 'z'
        }

        return when (axis) {
            'x' -> ParticleBlobCodec.GradientMode.X
            'y' -> ParticleBlobCodec.GradientMode.Y
            'z' -> ParticleBlobCodec.GradientMode.Z
            else -> ParticleBlobCodec.GradientMode.Z
        }
    }

    private fun parseGradientStops(
        rootObj: JsonObject,
        settings: JsonObject?,
        points: List<ParticleBlobCodec.Point>,
        mode: ParticleBlobCodec.GradientMode
    ): List<ParticleBlobCodec.GradientStop> {
        val fromColorObject = rootObj
            .getAsJsonObject("colour")
            ?.getAsJsonArray("stops")
            ?.let { readStopsArray(it) }
        if (!fromColorObject.isNullOrEmpty()) {
            return fromColorObject
        }

        val start = readColorElement(settings?.get("gradientStart"))
        val end = readColorElement(settings?.get("gradientEnd"))
        if (start != null && end != null) {
            return listOf(
                ParticleBlobCodec.GradientStop(0.0, start),
                ParticleBlobCodec.GradientStop(1.0, end)
            )
        }

        return inferGradientStopsFromPoints(points, mode)
    }

    private fun inferGradientStopsFromPoints(
        points: List<ParticleBlobCodec.Point>,
        mode: ParticleBlobCodec.GradientMode
    ): List<ParticleBlobCodec.GradientStop> {
        if (points.size < 2) {
            return listOf()
        }

        val tValues = points.map { gradientT(it.offset, points, mode) }
        val minT = tValues.minOrNull() ?: return listOf()
        val maxT = tValues.maxOrNull() ?: return listOf()
        if (maxT <= minT) {
            val c = points.first().color
            return listOf(
                ParticleBlobCodec.GradientStop(0.0, c),
                ParticleBlobCodec.GradientStop(1.0, c)
            )
        }

        val minIdx = tValues.indices.minByOrNull { tValues[it] } ?: return listOf()
        val maxIdx = tValues.indices.maxByOrNull { tValues[it] } ?: return listOf()

        return listOf(
            ParticleBlobCodec.GradientStop(0.0, points[minIdx].color),
            ParticleBlobCodec.GradientStop(1.0, points[maxIdx].color)
        )
    }

    private fun gradientT(
        p: Vec3,
        points: List<ParticleBlobCodec.Point>,
        mode: ParticleBlobCodec.GradientMode
    ): Double {
        return when (mode) {
            ParticleBlobCodec.GradientMode.X -> {
                val min = points.minOf { it.offset.x }
                val max = points.maxOf { it.offset.x }
                normalize01(p.x, min, max)
            }

            ParticleBlobCodec.GradientMode.Y -> {
                val min = points.minOf { it.offset.y }
                val max = points.maxOf { it.offset.y }
                normalize01(p.y, min, max)
            }

            ParticleBlobCodec.GradientMode.Z -> {
                val min = points.minOf { it.offset.z }
                val max = points.maxOf { it.offset.z }
                normalize01(p.z, min, max)
            }

            ParticleBlobCodec.GradientMode.DISTANCE -> {
                val d = p.length()
                val maxD = points.maxOf { it.offset.length() }
                normalize01(d, 0.0, maxD)
            }
        }
    }

    private fun normalize01(v: Double, min: Double, max: Double): Double {
        if (max <= min) {
            return 0.0
        }
        return ((v - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    private fun readStopsArray(stops: JsonArray): List<ParticleBlobCodec.GradientStop> {
        val out = ArrayList<ParticleBlobCodec.GradientStop>()
        for (entry in stops) {
            val arr = entry.asJsonArrayOrNull() ?: continue
            if (arr.size() < 2) continue
            val t = arr[0].asDoubleOrNull()?.coerceIn(0.0, 1.0) ?: continue
            val color = readColorElement(arr[1]) ?: continue
            out.add(ParticleBlobCodec.GradientStop(t, color))
        }
        return out
    }

    private fun parseAxisHint(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val letters = raw.lowercase().filter { it == 'x' || it == 'y' || it == 'z' }
        return if (letters.isBlank()) null else letters
    }

    private fun readColorElement(elem: JsonElement?): Vec3? {
        if (elem == null || elem.isJsonNull) return null

        elem.asJsonObjectOrNull()?.let { obj ->
            val r = obj.readFiniteDouble("r")
            val g = obj.readFiniteDouble("g")
            val b = obj.readFiniteDouble("b")
            if (r != null && g != null && b != null) {
                return normalizeRgb(r, g, b)
            }
        }

        elem.asJsonArrayOrNull()?.let { arr ->
            if (arr.size() >= 3) {
                val r = arr[0].asDoubleOrNull()
                val g = arr[1].asDoubleOrNull()
                val b = arr[2].asDoubleOrNull()
                if (r != null && g != null && b != null) {
                    return normalizeRgb(r, g, b)
                }
            }
        }

        val hex = elem.asStringOrNull()
        if (hex != null && hex.startsWith("#") && (hex.length == 7 || hex.length == 9)) {
            return try {
                val raw = hex.substring(1)
                val rr = raw.substring(0, 2).toInt(16) / 255.0
                val gg = raw.substring(2, 4).toInt(16) / 255.0
                val bb = raw.substring(4, 6).toInt(16) / 255.0
                Vec3(rr, gg, bb)
            } catch (_: Throwable) {
                null
            }
        }

        return null
    }

    private fun normalizeRgb(r: Double, g: Double, b: Double): Vec3 {
        val looks255 = r > 1.0 || g > 1.0 || b > 1.0
        return if (looks255) {
            Vec3((r / 255.0).coerceIn(0.0, 1.0), (g / 255.0).coerceIn(0.0, 1.0), (b / 255.0).coerceIn(0.0, 1.0))
        } else {
            Vec3(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
        }
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        if (!this.isJsonObject) return null
        return this.asJsonObject
    }

    private fun JsonElement.asJsonArrayOrNull(): JsonArray? {
        if (!this.isJsonArray) return null
        return this.asJsonArray
    }

    private fun JsonElement.asStringOrNull(): String? {
        return try {
            this.asString
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonElement.asBooleanOrNull(): Boolean? {
        return try {
            this.asBoolean
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonElement.asDoubleOrNull(): Double? {
        return try {
            val d = this.asDouble
            if (d.isFinite()) d else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonObject.readFiniteDouble(key: String): Double? {
        if (!this.has(key)) {
            return null
        }
        val v = try {
            this.get(key).asDouble
        } catch (_: Throwable) {
            return null
        }
        return if (v.isFinite()) v else null
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

    @JvmStatic
    fun sendParticleBlobCastTo(
        player: ServerPlayer,
        origin: net.minecraft.world.phys.Vec3,
        blob: ByteArray,
        lifetimeTicks: Int
    ) {
        val level = player.serverLevel()
        val radius = 128.0
        for (other in level.server.playerList.players) {
            if (other.serverLevel() != level || other.position().distanceTo(origin) > radius) {
                continue
            }

            val buf = PacketByteBufs.create()
            buf.writeUtf(level.dimension().location().toString())
            buf.writeDouble(origin.x)
            buf.writeDouble(origin.y)
            buf.writeDouble(origin.z)
            buf.writeByteArray(blob)
            buf.writeVarInt(lifetimeTicks.coerceAtLeast(1))
            ServerPlayNetworking.send(other, ManifestationNetworking.PARTICLE_BLOB_CAST_S2C, buf)
        }
    }

    @JvmStatic
    fun sendEquationCloudTo(
        player: ServerPlayer,
        origin: net.minecraft.world.phys.Vec3,
        cloudId: Long,
        equation: EquationParticleIota
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
}
