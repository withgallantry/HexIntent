package com.bluup.manifestation.server.block

import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.common.equation.EquationParticleConfig
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

    fun hasFocus(): Boolean = !focus.isEmpty

    fun hasPreviewEquation(): Boolean = previewEquation != null

    fun getPreviewEquation(): EquationParticleConfig? = previewEquation

    fun getFocusCopy(): ItemStack = if (focus.isEmpty) ItemStack.EMPTY else focus.copy()

    fun setFocus(stack: ItemStack) {
        val normalized = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        if (ItemStack.isSameItemSameTags(focus, normalized)) {
            return
        }
        focus = normalized
        previewEquation = readEquationFromFocus()
        markUpdated()
    }

    fun popFocus(): ItemStack {
        val out = focus
        focus = ItemStack.EMPTY
        previewEquation = null
        markUpdated()
        return out
    }

    fun canAcceptFocus(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return false
        }
        return IXplatAbstractions.INSTANCE.findDataHolder(stack) != null
    }

    fun writeEquation(config: EquationParticleConfig): String? {
        if (focus.isEmpty) {
            return "No focus inserted."
        }

        val holder = IXplatAbstractions.INSTANCE.findDataHolder(focus)
            ?: return "Inserted item is not a writable focus."

        val normalized = config.normalized()
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
            normalized.colorExprB()
        )

        if (!holder.writeIota(iota, true)) {
            return "Focus cannot store equation particle data."
        }

        holder.writeIota(iota, false)
        previewEquation = normalized
        syncVisualState()
        markUpdated()
        return null
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        focus = if (tag.contains(TAG_FOCUS, CompoundTag.TAG_COMPOUND.toInt())) {
            ItemStack.of(tag.getCompound(TAG_FOCUS))
        } else {
            ItemStack.EMPTY
        }
        previewEquation = if (tag.contains(TAG_PREVIEW, CompoundTag.TAG_COMPOUND.toInt())) {
            readConfigTag(tag.getCompound(TAG_PREVIEW))
        } else {
            null
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!focus.isEmpty) {
            tag.put(TAG_FOCUS, focus.save(CompoundTag()))
        }
        val preview = previewEquation
        if (preview != null) {
            tag.put(TAG_PREVIEW, writeConfigTag(preview))
        }
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

    private fun readEquationFromFocus(): EquationParticleConfig? {
        if (focus.isEmpty) {
            return null
        }

        val serverLevel = level as? ServerLevel ?: return null
        val holder = IXplatAbstractions.INSTANCE.findDataHolder(focus) ?: return null
        val iota = holder.readIota(serverLevel) as? EquationParticleIota ?: return null
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
        tag.putString("x", config.xExpr())
        tag.putString("y", config.yExpr())
        tag.putString("z", config.zExpr())
        tag.putDouble("t_min", config.tMin())
        tag.putDouble("t_max", config.tMax())
        tag.putDouble("u_min", config.uMin())
        tag.putDouble("u_max", config.uMax())
        tag.putBoolean("use_u", config.useU())
        tag.putInt("points", config.pointCount())
        tag.putString("color_mode", config.colorMode())
        tag.putDouble("fixed_r", config.fixedR())
        tag.putDouble("fixed_g", config.fixedG())
        tag.putDouble("fixed_b", config.fixedB())
        tag.putDouble("grad_start_r", config.gradientStartR())
        tag.putDouble("grad_start_g", config.gradientStartG())
        tag.putDouble("grad_start_b", config.gradientStartB())
        tag.putDouble("grad_end_r", config.gradientEndR())
        tag.putDouble("grad_end_g", config.gradientEndG())
        tag.putDouble("grad_end_b", config.gradientEndB())
        tag.putString("color_expr_r", config.colorExprR())
        tag.putString("color_expr_g", config.colorExprG())
        tag.putString("color_expr_b", config.colorExprB())
        return tag
    }

    private fun readConfigTag(tag: CompoundTag): EquationParticleConfig {
        return EquationParticleConfig(
            tag.getString("x"),
            tag.getString("y"),
            tag.getString("z"),
            tag.getDouble("t_min"),
            tag.getDouble("t_max"),
            tag.getDouble("u_min"),
            tag.getDouble("u_max"),
            tag.getBoolean("use_u"),
            tag.getInt("points"),
            tag.getString("color_mode"),
            tag.getDouble("fixed_r"),
            tag.getDouble("fixed_g"),
            tag.getDouble("fixed_b"),
            tag.getDouble("grad_start_r"),
            tag.getDouble("grad_start_g"),
            tag.getDouble("grad_start_b"),
            tag.getDouble("grad_end_r"),
            tag.getDouble("grad_end_g"),
            tag.getDouble("grad_end_b"),
            tag.getString("color_expr_r"),
            tag.getString("color_expr_g"),
            tag.getString("color_expr_b")
        ).normalized()
    }

    companion object {
        private const val TAG_FOCUS = "Focus"
        private const val TAG_PREVIEW = "PreviewEquation"
    }
}
