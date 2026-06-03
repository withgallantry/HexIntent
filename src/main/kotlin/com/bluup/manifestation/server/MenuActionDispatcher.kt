package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.ListIota
import ram.talia.moreiotas.api.casting.iota.StringIota
import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.menu.MenuPayload
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand

/**
 * Server-side handler for menu button actions.
 *
 * Copies stack and runs in its own environment now! No more messing around with copying the casting environment!
 *
 * User inputs are appended to the end of the copied stack before actioning the button.
 */
object MenuActionDispatcher {

    private const val MAX_INPUTS = 80
    private const val MAX_ACTION_IOTAS = 1024

    // Structured representation for each user-entered menu input.
    data class InputDatum(
        val order: Int,
        val kind: Kind,
        val stringValue: String,
        val doubleValue: Double,
        val iotaTags: List<CompoundTag>
    ) {
        enum class Kind {
            STRING,
            DOUBLE,
            IOTA_LIST
        }

        companion object {
            fun string(order: Int, value: String): InputDatum {
                return InputDatum(order, Kind.STRING, value, 0.0, listOf())
            }
            fun double(order: Int, value: Double): InputDatum {
                return InputDatum(order, Kind.DOUBLE, "", value, listOf())
            }
            fun iotaList(order: Int, tags: List<CompoundTag>): InputDatum {
                return InputDatum(order, Kind.IOTA_LIST, "", 0.0, tags)
            }
        }
    }

    /**
        * Dispatch a button's payload through the player's menu session.
     *
     * @param player = the player who clicked the button
     * @param hand = which hand is holding the casting item
         * @param source = where the menu session originated
     * @param inputs = typed input values in menu order
     */
    @JvmStatic
    fun dispatch(
        player: ServerPlayer,
        hand: InteractionHand,
            source: MenuPayload.DispatchSource,
            circleContext: MenuSessionRegistry.CircleContext?,
        inputs: List<InputDatum>,
        iotas: List<Iota>,
        sessionImage: CastingImage
    ) {
        if (inputs.size > MAX_INPUTS) {
            Manifestation.LOGGER.warn(
                "MenuActionDispatcher: rejecting dispatch for {} due to too many inputs ({})",
                player.name.string,
                inputs.size
            )
            return
        }

        if (iotas.size > MAX_ACTION_IOTAS) {
            Manifestation.LOGGER.warn(
                "MenuActionDispatcher: rejecting dispatch for {} due to too many iotas ({})",
                player.name.string,
                iotas.size
            )
            return
        }

        if (iotas.isEmpty()) {
            Manifestation.LOGGER.info("MenuActionDispatcher: empty action list, nothing to do")
            return
        }

        Manifestation.LOGGER.info(
            "MenuActionDispatcher: dispatching {} iotas for player {} (hand {}, source {})",
            iotas.size,
            player.name.string,
            hand,
            source
        )

        val world = player.serverLevel()
        val inputIotas = toInputIotas(inputs, world)
        when (source) {
            MenuPayload.DispatchSource.STAFF -> {
                dispatchStaff(player, hand, sessionImage, inputIotas, iotas, world)
            }

            MenuPayload.DispatchSource.CIRCLE -> {
                dispatchCircle(player, hand, circleContext, sessionImage, inputIotas, iotas, world)
            }

            // doing this as a fallback
            MenuPayload.DispatchSource.PACKAGED_ITEM -> {
                dispatchWithMenuEnv(player, hand, sessionImage, inputIotas, iotas, world)
            }
        }
    }

    private fun dispatchCircle(
        player: ServerPlayer,
        hand: InteractionHand,
        circleContext: MenuSessionRegistry.CircleContext?,
        capturedImage: CastingImage,
        inputIotas: List<Iota>,
        iotas: List<Iota>,
        world: ServerLevel
    ) {
        if (circleContext == null) {
            Manifestation.LOGGER.warn(
                "MenuActionDispatcher: rejecting circle dispatch for {} due to missing circle context",
                player.name.string
            )
            return
        }

        val playerDimId = world.dimension().location().toString()
        if (playerDimId != circleContext.dimensionId) {
            Manifestation.LOGGER.warn(
                "MenuActionDispatcher: rejecting circle dispatch for {} due to dimension mismatch (player {}, circle {})",
                player.name.string,
                playerDimId,
                circleContext.dimensionId
            )
            return
        }

        val env = CircleMenuCastEnv(player, hand, circleContext.impetusPos)
        val image = buildStartingImage(capturedImage, inputIotas)
        val vm = CastingVM(image, env)
        val clientInfo = vm.queueExecuteAndWrapIotas(iotas, world)
        Manifestation.LOGGER.info(
            "MenuActionDispatcher: {} dispatch complete, stack empty = {}, resolution = {}",
            env.javaClass.simpleName,
            clientInfo.isStackClear,
            clientInfo.resolutionType
        )
    }

    private fun dispatchWithMenuEnv(
        player: ServerPlayer,
        hand: InteractionHand,
        capturedImage: CastingImage,
        inputIotas: List<Iota>,
        iotas: List<Iota>,
        world: ServerLevel
    ) {
        val env = MenuCastEnv(player, hand)
        val image = buildStartingImage(capturedImage, inputIotas)
        val vm = CastingVM(image, env)
        val clientInfo = vm.queueExecuteAndWrapIotas(iotas, world)
        Manifestation.LOGGER.info(
            "MenuActionDispatcher: {} dispatch complete, stack empty = {}, resolution = {}",
            env.javaClass.simpleName,
            clientInfo.isStackClear,
            clientInfo.resolutionType
        )
    }

    private fun dispatchStaff(
        player: ServerPlayer,
        hand: InteractionHand,
        capturedImage: CastingImage,
        inputIotas: List<Iota>,
        iotas: List<Iota>,
        world: ServerLevel
    ) {
        val vm: CastingVM = IXplatAbstractions.INSTANCE.getStaffcastVM(player, hand)
        vm.image = buildStartingImage(capturedImage, inputIotas)
        Manifestation.LOGGER.info(
            "MenuActionDispatcher: prepared staff dispatch image with {} preserved stack iotas and {} input iotas",
            capturedImage.stack.size,
            inputIotas.size
        )
        val clientInfo = vm.queueExecuteAndWrapIotas(iotas, world)
        Manifestation.LOGGER.info(
            "MenuActionDispatcher: dispatch complete, stack empty = {}, resolution = {}",
            clientInfo.isStackClear, clientInfo.resolutionType
        )
        persistStaffSession(player, clientInfo.isStackClear, vm.image)
    }

    /**
     * Mirrors StaffCastEnv post-cast persistence semantics.
     */
    private fun persistStaffSession(player: ServerPlayer, stackClear: Boolean, image: CastingImage) {
        if (stackClear) {
            IXplatAbstractions.INSTANCE.setStaffcastImage(player, null)
            IXplatAbstractions.INSTANCE.setPatterns(player, listOf())
            return
        }

        IXplatAbstractions.INSTANCE.setStaffcastImage(player, image.withOverriddenUsedOps(0))
    }

    private fun buildStartingImage(
        capturedImage: CastingImage,
        inputIotas: List<Iota>
    ): CastingImage {
        val combinedStack = mutableListOf<Iota>()
        combinedStack.addAll(capturedImage.stack)
        combinedStack.addAll(inputIotas)
        return capturedImage.copy(stack = combinedStack)
    }

    private fun toInputIotas(rawInputs: List<InputDatum>, world: ServerLevel): List<Iota> {
        if (rawInputs.isEmpty()) {
            return emptyList()
        }

        val out = mutableListOf<Iota>()
        for (input in rawInputs.sortedBy { it.order }) {
            try {
                when (input.kind) {
                    InputDatum.Kind.STRING -> {
                        if (input.stringValue.isNotEmpty()) {
                            out.add(createStringIota(input.stringValue))
                        }
                    }

                    InputDatum.Kind.DOUBLE -> {
                        out.add(DoubleIota(input.doubleValue))
                    }
                    InputDatum.Kind.IOTA_LIST -> {
                        val selected = mutableListOf<Iota>()
                        for (tag in input.iotaTags) {
                            try {
                                selected.add(IotaType.deserialize(tag.copy(), world))
                            } catch (t: Throwable) {
                                Manifestation.LOGGER.warn(
                                    "MenuActionDispatcher: failed to deserialize selected list iota input, skipping item",
                                    t
                                )
                            }
                        }
                        out.add(ListIota(selected))
                    }
                }
            } catch (t: Throwable) {
                Manifestation.LOGGER.warn(
                    "MenuActionDispatcher: failed to convert menu input into iota, skipping value",
                    t
                )
            }
        }

        return out
    }

    private fun createStringIota(text: String): Iota {
        return StringIota.makeUnchecked(text)
    }
}
