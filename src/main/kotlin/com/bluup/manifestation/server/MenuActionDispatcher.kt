package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.Manifestation
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
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

    data class InputDatum(
        val order: Int,
        val kind: Kind,
        val stringValue: String,
        val doubleValue: Double
    ) {
        enum class Kind {
            STRING,
            DOUBLE
        }

        companion object {
            fun string(order: Int, value: String): InputDatum {
                return InputDatum(order, Kind.STRING, value, 0.0)
            }

            fun number(order: Int, value: Double): InputDatum {
                return InputDatum(order, Kind.DOUBLE, "", value)
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
     * @param inputs typed input values in menu order
     * @param iotas  the iotas to dispatch in order, as deserialized from the
     *               client-sent packet via [IotaType]
     */
    @JvmStatic
    fun dispatch(player: ServerPlayer, hand: InteractionHand, inputs: List<InputDatum>, iotas: List<Iota>) {
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
            "MenuActionDispatcher: dispatching {} iotas for player {} (hand {})",
            iotas.size, player.name.string, hand
        )

        // Pull the player's live casting session. getStaffcastVM constructs a
        // CastingVM wrapping their persisted CastingImage — so any mutations we
        // make to vm.image will be observable once we persist back.
        val vm: CastingVM = IXplatAbstractions.INSTANCE.getStaffcastVM(player, hand)
        val world = player.serverLevel()

        suppressStaffCastSounds(vm)

        val inputIotas = toInputIotas(inputs, world)
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
