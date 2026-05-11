package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv
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
 * Server-side handler for "the player clicked a button."
 *
 * Given a list of iotas (the button's stored action payload), feeds them one
 * by one through the player's live CastingVM — the same VM staff casting uses.
 * This preserves everything the player cares about: ravenmind, the existing
 * stack, paren depth, escape state, ops consumed this tick.
 *
 * ## Why we reuse the player's live image, not a fresh one
 *
 * A fresh CastingImage would have empty stack, no ravenmind, no user data.
 * That's useless for menu actions — the player would expect to interact with
 * their current cast state. So we read the live image via
 * [IXplatAbstractions.getStaffcastVM], mutate it in place, and persist the
 * result. Everything the player built up with earlier casts remains.
 */
object MenuActionDispatcher {

    private const val MAX_INPUTS = 80
    private const val MAX_ACTION_IOTAS = 1024
    private const val HEXICAL_CHARM_ENV_CLASS = "miyucomics.hexical.features.charms.CharmCastEnv"
    private const val HEXICAL_CHARM_UTIL_CLASS = "miyucomics.hexical.features.charms.CharmUtilities"
    private const val HEXICAL_CURIO_ITEM_CLASS = "miyucomics.hexical.features.curios.CurioItem"

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

            fun number(order: Int, value: Double): InputDatum {
                return InputDatum(order, Kind.DOUBLE, "", value, listOf())
            }

            fun iotaList(order: Int, tags: List<CompoundTag>): InputDatum {
                return InputDatum(order, Kind.IOTA_LIST, "", 0.0, tags)
            }
        }
    }

    private val possibleStringIotaClasses = listOf(
        "ram.talia.moreiotas.casting.iota.StringIota",
        "at.petrak.moreiotas.casting.iota.StringIota",
        "ram.talia.moreiotas.api.casting.iota.StringIota",
        "at.petrak.moreiotas.api.casting.iota.StringIota"
    )
    private var warnedMissingStringIota = false
    private val stringIotaCtor: Constructor<out Iota>? by lazy {
        for (className in possibleStringIotaClasses) {
            try {
                val cls = Class.forName(className)
                if (!Iota::class.java.isAssignableFrom(cls)) {
                    continue
                }

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
     * @param iotas  the iotas to dispatch in order, as deserialized from the
     *               client-sent packet via [IotaType]
     */
    @JvmStatic
    fun dispatch(
        player: ServerPlayer,
        hand: InteractionHand,
        source: MenuPayload.DispatchSource,
        inputs: List<InputDatum>,
        iotas: List<Iota>
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

        when (source) {
            MenuPayload.DispatchSource.STAFF -> {
                dispatchStaff(player, hand, inputIotas, iotas, world)
            }

            MenuPayload.DispatchSource.HEXICAL_CHARM -> {
                val ok = dispatchHexicalCharm(player, hand, inputIotas, iotas, world)
                if (!ok) {
                    Manifestation.LOGGER.warn(
                        "MenuActionDispatcher: rejected HEXICAL_CHARM dispatch for {} (hand {}) because charm context was unavailable",
                        player.name.string,
                        hand
                    )
                }
            }
        }
    }

    private fun dispatchStaff(
        player: ServerPlayer,
        hand: InteractionHand,
        inputIotas: List<Iota>,
        iotas: List<Iota>,
        world: ServerLevel
    ) {
        // Pull the player's live casting session. getStaffcastVM constructs a
        // CastingVM wrapping their persisted CastingImage — so any mutations we
        // make to vm.image will be observable once we persist back.
        val vm: CastingVM = IXplatAbstractions.INSTANCE.getStaffcastVM(player, hand)

        suppressStaffCastSounds(vm)

        if (inputIotas.isNotEmpty()) {
            val newStack = vm.image.stack.toMutableList()
            newStack.addAll(inputIotas)
            vm.image = vm.image.copy(stack = newStack)
            Manifestation.LOGGER.info(
                "MenuActionDispatcher: pushed {} input iotas onto stack before action",
                inputIotas.size
            )
        }

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

    private fun dispatchHexicalCharm(
        player: ServerPlayer,
        hand: InteractionHand,
        inputIotas: List<Iota>,
        iotas: List<Iota>,
        world: ServerLevel
    ): Boolean {
        val stackInHand = player.getItemInHand(hand)
        if (!isHexicalCharmedStack(stackInHand)) {
            return false
        }

        val env = createHexicalCharmEnv(player, hand, stackInHand) ?: return false
        val image = CastingImage().copy(stack = inputIotas.toMutableList())
        val vm = CastingVM(image, env)
        val clientInfo = vm.queueExecuteAndWrapIotas(iotas, world)
        Manifestation.LOGGER.info(
            "MenuActionDispatcher: hexical charm dispatch complete, stack empty = {}, resolution = {}",
            clientInfo.isStackClear,
            clientInfo.resolutionType
        )

        // Curio charms in Hexical run post-cast hooks after execution.
        tryInvokeHexicalCurioPostCast(player, stackInHand, hand, world, vm.image.stack)
        return true
    }

    private fun isHexicalCharmedStack(stack: ItemStack): Boolean {
        return try {
            val utilClass = Class.forName(HEXICAL_CHARM_UTIL_CLASS)
            val method = utilClass.getMethod("isStackCharmed", ItemStack::class.java)
            (method.invoke(null, stack) as? Boolean) == true
        } catch (_: Throwable) {
            // If Hexical is not present, only trust explicit dispatches when stack has charm tag.
            stack.tag?.contains("charmed") == true
        }
    }

    private fun createHexicalCharmEnv(player: ServerPlayer, hand: InteractionHand, stack: ItemStack): CastingEnvironment? {
        return try {
            val envClass = Class.forName(HEXICAL_CHARM_ENV_CLASS)
            val ctor = envClass.constructors.firstOrNull { ctor ->
                ctor.parameterTypes.size == 3 &&
                    ctor.parameterTypes[0].isAssignableFrom(player.javaClass) &&
                    ctor.parameterTypes[1].isInstance(hand) &&
                    ctor.parameterTypes[2].isAssignableFrom(ItemStack::class.java)
            } ?: return null

            ctor.newInstance(player, hand, stack) as? CastingEnvironment
        } catch (t: Throwable) {
            Manifestation.LOGGER.debug("MenuActionDispatcher: failed to create Hexical CharmCastEnv", t)
            null
        }
    }

    private fun tryInvokeHexicalCurioPostCast(
        player: ServerPlayer,
        stack: ItemStack,
        hand: InteractionHand,
        world: ServerLevel,
        resultingStack: List<Iota>
    ) {
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

    private fun suppressStaffCastSounds(vm: CastingVM) {
        val staffEnv = vm.env as? StaffCastEnv ?: return

        try {
            val soundsPlayedField = StaffCastEnv::class.java.getDeclaredField("soundsPlayed")
            soundsPlayedField.isAccessible = true
            // StaffCastEnv only plays sounds while soundsPlayed < 100.
            soundsPlayedField.setInt(staffEnv, 100)
        } catch (t: Throwable) {
            Manifestation.LOGGER.debug("MenuActionDispatcher: failed to suppress staff cast sounds", t)
        }
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
