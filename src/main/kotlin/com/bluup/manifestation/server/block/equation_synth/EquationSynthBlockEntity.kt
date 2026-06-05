package com.bluup.manifestation.server.block

import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.common.equation.EquationParticleConfig
import com.bluup.manifestation.server.KotlinNbtCompat
import com.bluup.manifestation.server.iota.EquationParticleIota
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

class EquationSynthBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ManifestationBlocks.EQUATION_SYNTH_BLOCK_ENTITY, pos, state) {

    private var focus: ItemStack = ItemStack.EMPTY
    private var previewEquation: EquationParticleConfig? = null
    private var animationPreset: String = ANIM_ROTATE
    private var animationSpeed: Double = DEFAULT_ANIMATION_SPEED
    private var renderDensity: Double = DEFAULT_RENDER_DENSITY
    private var refreshTicker = 0

    fun hasFocus(): Boolean = !focus.isEmpty

    fun hasPreviewEquation(): Boolean = previewEquation != null

    fun getPreviewEquation(): EquationParticleConfig? = previewEquation

    fun getAnimationPreset(): String = animationPreset

    fun getAnimationSpeed(): Double = animationSpeed

    fun getRenderDensity(): Double = renderDensity

    fun getFocusCopy(): ItemStack = if (focus.isEmpty) ItemStack.EMPTY else focus.copy()

    fun setFocus(stack: ItemStack) {
        val normalized = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        if (ItemStack.isSameItemSameTags(focus, normalized)) {
            return
        }
        focus = normalized
        previewEquation = readEquationFromFocus()
        syncVisualState()
        markUpdated()
    }

    fun popFocus(): ItemStack {
        val out = focus
        focus = ItemStack.EMPTY
        previewEquation = null
        syncVisualState()
        markUpdated()
        return out
    }

    fun canAcceptFocus(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return false
        }
        return IXplatAbstractions.INSTANCE.findDataHolder(stack) != null
    }

    fun setAnimationPreset(preset: String) {
        val normalized = normalizeAnimationPreset(preset)
        if (animationPreset == normalized) {
            return
        }
        animationPreset = normalized
        markUpdated()
    }

    fun setAnimationSpeed(value: Double) {
        val normalized = normalizeAnimationSpeed(value)
        if (animationSpeed == normalized) {
            return
        }
        animationSpeed = normalized
        markUpdated()
    }

    fun setRenderDensity(value: Double) {
        val normalized = normalizeRenderDensity(value)
        if (renderDensity == normalized) {
            return
        }
        renderDensity = normalized
        markUpdated()
    }

    fun writeEquation(
        config: EquationParticleConfig,
        animationPreset: String = this.animationPreset,
        animationSpeed: Double = this.animationSpeed
    ): String? {
        if (focus.isEmpty) {
            return "No focus inserted."
        }

        val holder = IXplatAbstractions.INSTANCE.findDataHolder(focus)
            ?: return "Inserted item is not a writable focus."

        val normalized = config.normalized()
        val normalizedAnimationPreset = normalizeAnimationPreset(animationPreset)
        val normalizedAnimationSpeed = normalizeAnimationSpeed(animationSpeed)
        val iota = EquationParticleIota(
            normalized.xExpr(),
            normalized.yExpr(),
            normalized.zExpr(),
            normalized.tMin(),
            normalized.tMax(),
            normalized.uMin(),
            normalized.uMax(),
            normalized.useU(),
            normalized.pointCount(),
            normalized.colorMode(),
            normalized.fixedR(),
            normalized.fixedG(),
            normalized.fixedB(),
            normalized.gradientStartR(),
            normalized.gradientStartG(),
            normalized.gradientStartB(),
            normalized.gradientEndR(),
            normalized.gradientEndG(),
            normalized.gradientEndB(),
            normalized.colorExprR(),
            normalized.colorExprG(),
            normalized.colorExprB(),
            normalizedAnimationPreset
            ,normalizedAnimationSpeed
        )

        if (!holder.writeIota(iota, true)) {
            return "Focus cannot store equation particle data."
        }

        holder.writeIota(iota, false)
        this.animationPreset = normalizedAnimationPreset
        this.animationSpeed = normalizedAnimationSpeed
        previewEquation = normalized
        syncVisualState()
        markUpdated()
        return null
    }

    fun serverTick(level: ServerLevel) {
        if (++refreshTicker < 10) {
            return
        }
        refreshTicker = 0
        refreshFromFocus(level)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        focus = if (KotlinNbtCompat.contains(tag, TAG_FOCUS, CompoundTag.TAG_COMPOUND.toInt())) {
            ItemStack.of(KotlinNbtCompat.getCompound(tag, TAG_FOCUS))
        } else {
            ItemStack.EMPTY
        }
        previewEquation = if (KotlinNbtCompat.contains(tag, TAG_PREVIEW, CompoundTag.TAG_COMPOUND.toInt())) {
            readConfigTag(KotlinNbtCompat.getCompound(tag, TAG_PREVIEW))
        } else {
            null
        }
        animationPreset = normalizeAnimationPreset(KotlinNbtCompat.getString(tag, TAG_ANIMATION_PRESET))
        animationSpeed = if (KotlinNbtCompat.contains(tag, TAG_ANIMATION_SPEED, CompoundTag.TAG_DOUBLE.toInt())) {
            normalizeAnimationSpeed(KotlinNbtCompat.getDouble(tag, TAG_ANIMATION_SPEED))
        } else {
            DEFAULT_ANIMATION_SPEED
        }
        renderDensity = if (KotlinNbtCompat.contains(tag, TAG_RENDER_DENSITY, CompoundTag.TAG_DOUBLE.toInt())) {
            normalizeRenderDensity(KotlinNbtCompat.getDouble(tag, TAG_RENDER_DENSITY))
        } else {
            DEFAULT_RENDER_DENSITY
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!focus.isEmpty) {
            KotlinNbtCompat.put(tag, TAG_FOCUS, focus.save(CompoundTag()))
        }
        val preview = previewEquation
        if (preview != null) {
            KotlinNbtCompat.put(tag, TAG_PREVIEW, writeConfigTag(preview))
        }
        KotlinNbtCompat.putString(tag, TAG_ANIMATION_PRESET, animationPreset)
        KotlinNbtCompat.putDouble(tag, TAG_ANIMATION_SPEED, animationSpeed)
        KotlinNbtCompat.putDouble(tag, TAG_RENDER_DENSITY, renderDensity)
    }

    override fun getUpdateTag(): CompoundTag {
        return saveWithoutMetadata()
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }

    private fun markUpdated() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    private fun syncVisualState() {
        val serverLevel = level as? ServerLevel ?: return
        val state = serverLevel.getBlockState(worldPosition)
        if (state.block !is EquationSynthBlock) {
            return
        }

        val desired = state
            .setValue(EquationSynthBlock.FOCUS, hasFocus())
            .setValue(EquationSynthBlock.ACTIVE, hasPreviewEquation())

        if (desired != state) {
            serverLevel.setBlock(worldPosition, desired, Block.UPDATE_CLIENTS)
        }
    }

    private fun refreshFromFocus(serverLevel: ServerLevel) {
        val refreshed = readEquationFromFocus(serverLevel)
        if (previewEquation != refreshed) {
            previewEquation = refreshed
            markUpdated()
        }

        val state = serverLevel.getBlockState(worldPosition)
        if (state.block !is EquationSynthBlock) {
            return
        }

        val desired = state
            .setValue(EquationSynthBlock.FOCUS, hasFocus())
            .setValue(EquationSynthBlock.ACTIVE, refreshed != null)
        if (desired != state) {
            serverLevel.setBlock(worldPosition, desired, Block.UPDATE_CLIENTS)
        }
    }

    private fun readEquationFromFocus(): EquationParticleConfig? {
        val serverLevel = level as? ServerLevel ?: return null
        return readEquationFromFocus(serverLevel)
    }

    private fun readEquationFromFocus(serverLevel: ServerLevel): EquationParticleConfig? {
        if (focus.isEmpty) {
            return null
        }

        val holder = IXplatAbstractions.INSTANCE.findDataHolder(focus) ?: return null
        val iota = holder.readIota(serverLevel) as? EquationParticleIota ?: return null
        animationPreset = normalizeAnimationPreset(iota.animationPreset)
        animationSpeed = normalizeAnimationSpeed(iota.animationSpeed)
        return EquationParticleConfig(
            iota.xExpr,
            iota.yExpr,
            iota.zExpr,
            iota.tMin,
            iota.tMax,
            iota.uMin,
            iota.uMax,
            iota.isUseU,
            iota.pointCount,
            iota.colorMode,
            iota.fixedR,
            iota.fixedG,
            iota.fixedB,
            iota.gradientStartR,
            iota.gradientStartG,
            iota.gradientStartB,
            iota.gradientEndR,
            iota.gradientEndG,
            iota.gradientEndB,
            iota.colorExprR,
            iota.colorExprG,
            iota.colorExprB
        ).normalized()
    }

    private fun writeConfigTag(config: EquationParticleConfig): CompoundTag {
        val tag = CompoundTag()
        KotlinNbtCompat.putString(tag, "x", config.xExpr())
        KotlinNbtCompat.putString(tag, "y", config.yExpr())
        KotlinNbtCompat.putString(tag, "z", config.zExpr())
        KotlinNbtCompat.putDouble(tag, "t_min", config.tMin())
        KotlinNbtCompat.putDouble(tag, "t_max", config.tMax())
        KotlinNbtCompat.putDouble(tag, "u_min", config.uMin())
        KotlinNbtCompat.putDouble(tag, "u_max", config.uMax())
        KotlinNbtCompat.putBoolean(tag, "use_u", config.useU())
        KotlinNbtCompat.putInt(tag, "points", config.pointCount())
        KotlinNbtCompat.putString(tag, "color_mode", config.colorMode())
        KotlinNbtCompat.putDouble(tag, "fixed_r", config.fixedR())
        KotlinNbtCompat.putDouble(tag, "fixed_g", config.fixedG())
        KotlinNbtCompat.putDouble(tag, "fixed_b", config.fixedB())
        KotlinNbtCompat.putDouble(tag, "grad_start_r", config.gradientStartR())
        KotlinNbtCompat.putDouble(tag, "grad_start_g", config.gradientStartG())
        KotlinNbtCompat.putDouble(tag, "grad_start_b", config.gradientStartB())
        KotlinNbtCompat.putDouble(tag, "grad_end_r", config.gradientEndR())
        KotlinNbtCompat.putDouble(tag, "grad_end_g", config.gradientEndG())
        KotlinNbtCompat.putDouble(tag, "grad_end_b", config.gradientEndB())
        KotlinNbtCompat.putString(tag, "color_expr_r", config.colorExprR())
        KotlinNbtCompat.putString(tag, "color_expr_g", config.colorExprG())
        KotlinNbtCompat.putString(tag, "color_expr_b", config.colorExprB())
        return tag
    }

    private fun readConfigTag(tag: CompoundTag): EquationParticleConfig {
        return EquationParticleConfig(
            KotlinNbtCompat.getString(tag, "x"),
            KotlinNbtCompat.getString(tag, "y"),
            KotlinNbtCompat.getString(tag, "z"),
            KotlinNbtCompat.getDouble(tag, "t_min"),
            KotlinNbtCompat.getDouble(tag, "t_max"),
            KotlinNbtCompat.getDouble(tag, "u_min"),
            KotlinNbtCompat.getDouble(tag, "u_max"),
            KotlinNbtCompat.getBoolean(tag, "use_u"),
            KotlinNbtCompat.getInt(tag, "points"),
            KotlinNbtCompat.getString(tag, "color_mode"),
            KotlinNbtCompat.getDouble(tag, "fixed_r"),
            KotlinNbtCompat.getDouble(tag, "fixed_g"),
            KotlinNbtCompat.getDouble(tag, "fixed_b"),
            KotlinNbtCompat.getDouble(tag, "grad_start_r"),
            KotlinNbtCompat.getDouble(tag, "grad_start_g"),
            KotlinNbtCompat.getDouble(tag, "grad_start_b"),
            KotlinNbtCompat.getDouble(tag, "grad_end_r"),
            KotlinNbtCompat.getDouble(tag, "grad_end_g"),
            KotlinNbtCompat.getDouble(tag, "grad_end_b"),
            KotlinNbtCompat.getString(tag, "color_expr_r"),
            KotlinNbtCompat.getString(tag, "color_expr_g"),
            KotlinNbtCompat.getString(tag, "color_expr_b")
        ).normalized()
    }

    companion object {
        private const val TAG_FOCUS = "Focus"
        private const val TAG_PREVIEW = "PreviewEquation"
        private const val TAG_ANIMATION_PRESET = "AnimationPreset"
        private const val TAG_ANIMATION_SPEED = "AnimationSpeed"
        private const val TAG_RENDER_DENSITY = "RenderDensity"

        private const val DEFAULT_RENDER_DENSITY = 0.6
        private const val DEFAULT_ANIMATION_SPEED = 1.0

        private const val ANIM_STATIC = "static"
        private const val ANIM_ROTATE = "rotate"
        private const val ANIM_BOB = "bob"
        private const val ANIM_PULSE = "pulse"
        private const val ANIM_ORBIT = "orbit"
        private const val ANIM_SPIN_BOB = "spin_bob"

        private fun normalizeAnimationPreset(raw: String?): String {
            return when (raw?.lowercase()) {
                ANIM_STATIC -> ANIM_STATIC
                ANIM_BOB -> ANIM_BOB
                ANIM_PULSE -> ANIM_PULSE
                ANIM_ORBIT -> ANIM_ORBIT
                ANIM_SPIN_BOB -> ANIM_SPIN_BOB
                else -> ANIM_ROTATE
            }
        }

        private fun normalizeRenderDensity(value: Double): Double {
            if (!value.isFinite()) {
                return DEFAULT_RENDER_DENSITY
            }
            return value.coerceIn(0.1, 1.0)
        }

        private fun normalizeAnimationSpeed(value: Double): Double {
            if (!value.isFinite()) {
                return DEFAULT_ANIMATION_SPEED
            }
            return value.coerceIn(0.1, 4.0)
        }
    }
}
