package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.menu.MenuPayload
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import java.lang.reflect.Constructor

/**
 * Server-side handler for button actions.
 *
 * Replays from a stored image, gets rid of the horrible code where I serialized the stack and tried to guess environment from the hand.
 *
 * User inputs are appended to the end of the stack before actioning the button.
 */
object MenuActionDispatcher {

    private const val MAX_INPUTS = 80
    private const val MAX_ACTION_IOTAS = 1024
    private const val HEXICAL_CURIO_ITEM_CLASS = "miyucomics.hexical.features.curios.CurioItem"

    private sealed interface DispatchPlan {
        data object Staff : DispatchPlan
        data class VMBased(
            val env: CastingEnvironment,
            val postCast: ((CastingVM) -> Unit)? = null
        ) : DispatchPlan
    }

    // Data class for menu input
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

    private var warnedMissingStringIota = false

    private val possibleStringIotaClasses = listOf(
        "ram.talia.moreiotas.casting.iota.StringIota",
        "at.petrak.moreiotas.casting.iota.StringIota",
        "ram.talia.moreiotas.api.casting.iota.StringIota",
        "at.petrak.moreiotas.api.casting.iota.StringIota"
    )

    private val stringIotaCtor: Constructor<out Iota>? by lazy {
        for (className in possibleStringIotaClasses) {
            try {
                val cls = Class.forName(className)
                if (!Iota::class.java.isAssignableFrom(cls)) continue
                @Suppress("UNCHECKED_CAST")
                val iotaCls = cls as Class<out Iota>
                return@lazy iotaCls.getConstructor(String::class.java)
            } catch (_: Throwable) {
                // Try next known class name.
            }
        }
        null
    }

    private val stringTypeIds: List<ResourceLocation> by lazy {
        HexIotaTypes.REGISTRY.keySet().filter { it.path.contains("string", ignoreCase = true) }
    }

    /**
     * Dispatch a button's payload through the player's live casting session.
     *
     * @param player the player who clicked the button
     * @param hand   which hand is holding the casting item
     * @param source where this menu originated (staff vs charm context)
     * @param inputs typed input values in menu order
     */
    @JvmStatic
    fun dispatch(
        player: ServerPlayer,
        hand: InteractionHand,
        source: MenuPayload.DispatchSource,
        inputs: List<InputDatum>,
        iotas: List<Iota>,
        sessionImage: CastingImage = CastingImage(),
        circleContext: MenuSessionRegistry.CircleContext? = null
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
            iotas.size, player.name.string, hand, source
        )

        val world = player.serverLevel()
        val inputIotas = toInputIotas(inputs, world)

        when (val plan = resolveDispatchPlan(player, hand, source, circleContext, world)) {
            null -> return
            DispatchPlan.Staff -> dispatchStaff(player, hand, sessionImage, inputIotas, iotas, world)
            is DispatchPlan.VMBased -> dispatchWithEnv(plan, sessionImage, inputIotas, iotas, world)
        }

    }

    private fun resolveDispatchPlan(
        player: ServerPlayer,
        hand: InteractionHand,
        source: MenuPayload.DispatchSource,
        circleContext: MenuSessionRegistry.CircleContext?,
        world: ServerLevel
    ): DispatchPlan? {
        return when (source) {
            MenuPayload.DispatchSource.STAFF -> DispatchPlan.Staff
            MenuPayload.DispatchSource.PACKAGED_ITEM -> DispatchPlan.VMBased(MenuCastEnv(player, hand, source))
            MenuPayload.DispatchSource.CIRCLE -> {
                val context = circleContext
                if (context == null) {
                    Manifestation.LOGGER.warn(
                        "MenuActionDispatcher: rejected CIRCLE dispatch for {} (hand {}) because circle context was missing",
                        player.name.string,
                        hand
                    )
                    null
                } else {
                    DispatchPlan.VMBased(MenuCastEnv(player, hand, source))
                }
            }
            MenuPayload.DispatchSource.HEXICAL_CHARM -> {
                val stackInHand = player.getItemInHand(hand)
                if (!isHexicalCharmedStack(stackInHand)) {
                    Manifestation.LOGGER.warn(
                        "MenuActionDispatcher: rejected HEXICAL_CHARM dispatch for {} (hand {}) because charm context was unavailable",
                        player.name.string,
                        hand
                    )
                    null
                } else {
                    DispatchPlan.VMBased(MenuCastEnv(player, hand, source)) { vm ->
                        tryInvokeHexicalCurioPostCast(player, stackInHand, hand, world, vm.image.stack)
                    }
                }
            }
        }
    }

    private fun dispatchWithEnv(
        plan: DispatchPlan.VMBased,
        capturedImage: CastingImage,
        inputIotas: List<Iota>,
        iotas: List<Iota>,
        world: ServerLevel
    ) {
        val image = buildStartingImage(capturedImage, inputIotas)
        val vm = CastingVM(image, plan.env)
        val clientInfo = vm.queueExecuteAndWrapIotas(iotas, world)
        Manifestation.LOGGER.info(
            "MenuActionDispatcher: {} dispatch complete, stack empty = {}, resolution = {}",
            plan.env.javaClass.simpleName,
            clientInfo.isStackClear,
            clientInfo.resolutionType
        )
        plan.postCast?.invoke(vm)
    }

    private fun dispatchStaff(
        player: ServerPlayer,
        hand: InteractionHand,
        capturedImage: CastingImage,
        inputIotas: List<Iota>,
        iotas: List<Iota>,
        world: ServerLevel
    ) {
        // Pull the player's live casting session. getStaffcastVM constructs a
        // CastingVM wrapping their persisted CastingImage — so any mutations we
        // make to vm.image will be observable once we persist back.
        val vm: CastingVM = IXplatAbstractions.INSTANCE.getStaffcastVM(player, hand)

        // Keep staff menu dispatch consistent with other sources: do not inherit
        // stale runtime staff stack from click-time; restore from captured session state.
        vm.image = buildStartingImage(capturedImage, inputIotas)
        Manifestation.LOGGER.info(
            "MenuActionDispatcher: prepared staff dispatch image with {} preserved stack iotas and {} input iotas",
            capturedImage.stack.size,
            inputIotas.size
        )

        // Run the queued iotas through the VM in one shot. This is the
        // same entrypoint BlockSlate uses for circle spellcasting — proven path.
        val clientInfo = vm.queueExecuteAndWrapIotas(iotas, world)
        Manifestation.LOGGER.info(
            "MenuActionDispatcher: dispatch complete, stack empty = {}, resolution = {}",
            clientInfo.isStackClear, clientInfo.resolutionType
        )

        // Persist the mutated image back to the player's session. Mirror what
        // StaffCastEnv.handleNewPatternOnServer does: if stack is now clear,
        // wipe the session; otherwise save the new image with op count reset
        // so subsequent casts don't inherit our op consumption.
        if (clientInfo.isStackClear) {
            IXplatAbstractions.INSTANCE.setStaffcastImage(player, null)
            IXplatAbstractions.INSTANCE.setPatterns(player, listOf())
        } else {
            IXplatAbstractions.INSTANCE.setStaffcastImage(
                player, vm.image.withOverriddenUsedOps(0)
            )
            // We don't touch setPatterns — the existing drawn-pattern list in
            // the staff UI is the player's, not ours. Leaving it alone.
        }
    }

    private fun isHexicalCharmedStack(stack: ItemStack): Boolean {
        return CharmCastSoundOverrides.isHexicalCharmedStack(stack)
    }

    private fun tryInvokeHexicalCurioPostCast(
        player: ServerPlayer,
        stack: ItemStack,
        hand: InteractionHand,
        world: ServerLevel,
        resultingStack: List<Iota>
    ) {
        if (CharmCastSoundOverrides.handlePostCastSound(player, world, stack)) {
            return
        }

        val item = stack.item
        if (item.javaClass.name != HEXICAL_CURIO_ITEM_CLASS) {
            return
        }

        try {
            val method = item.javaClass.methods.firstOrNull { m ->
                m.name == "postCharmCast" && m.parameterTypes.size == 5
            } ?: return
            method.invoke(item, player, stack, hand, world, resultingStack)
        } catch (t: Throwable) {
            Manifestation.LOGGER.debug("MenuActionDispatcher: failed to invoke Hexical Curio postCharmCast", t)
        }
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
            return listOf()
        }

        val out = mutableListOf<Iota>()
        for (input in rawInputs.sortedBy { it.order }) {
            try {
                when (input.kind) {
                    InputDatum.Kind.STRING -> {
                        if (input.stringValue.isNotEmpty()) {
                            val built = createStringIota(input.stringValue, world)
                            if (built != null) {
                                out.add(built)
                            }
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
                    "MenuActionDispatcher: failed to create StringIota for input text, skipping value",
                    t
                )
            }
        }

        if (out.isEmpty() && !warnedMissingStringIota) {
            warnedMissingStringIota = true
            Manifestation.LOGGER.warn(
                "MenuActionDispatcher: input fields provided, but no compatible string iota type could be built. " +
                    "Ensure a string-iota addon (e.g. MoreIotas) is installed and registered."
            )
        }

        return out
    }

    private fun createStringIota(text: String, world: ServerLevel): Iota? {
        val ctor = stringIotaCtor
        if (ctor != null) {
            return ctor.newInstance(text)
        }

        // Fallback: discover a registered string-like iota type and roundtrip
        // through IotaType.deserialize using a string data tag.
        for (typeId in stringTypeIds) {
            val serialized = CompoundTag()
            serialized.putString(HexIotaTypes.KEY_TYPE, typeId.toString())
            serialized.put(HexIotaTypes.KEY_DATA, StringTag.valueOf(text))

            val iota = IotaType.deserialize(serialized, world)
            val resolved = HexIotaTypes.REGISTRY.getKey(iota.type)
            if (resolved != null && resolved.path.contains("string", ignoreCase = true)) {
                return iota
            }
        }

        return null
    }
}
