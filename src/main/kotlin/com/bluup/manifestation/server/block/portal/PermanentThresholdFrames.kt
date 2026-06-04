package com.bluup.manifestation.server.block

import com.bluup.manifestation.server.KotlinNbtCompat
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

data class PermanentThresholdFrame(
    val axis: Direction.Axis,
    val plane: Int,
    val minHorizontal: Int,
    val minY: Int
) {
    fun anchorPos(): BlockPos = if (axis == Direction.Axis.Z) {
        BlockPos(minHorizontal + 1, minY + 2, plane)
    } else {
        BlockPos(plane, minY + 2, minHorizontal + 1)
    }

    fun framePos(horizontal: Int, vertical: Int): BlockPos = if (axis == Direction.Axis.Z) {
        BlockPos(minHorizontal + horizontal, minY + vertical, plane)
    } else {
        BlockPos(plane, minY + vertical, minHorizontal + horizontal)
    }

    fun serialize(): CompoundTag = CompoundTag().apply {
        KotlinNbtCompat.putString(this, TAG_AXIS, axis.name)
        KotlinNbtCompat.putInt(this, TAG_PLANE, plane)
        KotlinNbtCompat.putInt(this, TAG_MIN_HORIZONTAL, minHorizontal)
        KotlinNbtCompat.putInt(this, TAG_MIN_Y, minY)
    }

    companion object {
        private const val TAG_AXIS = "Axis"
        private const val TAG_PLANE = "Plane"
        private const val TAG_MIN_HORIZONTAL = "MinHorizontal"
        private const val TAG_MIN_Y = "MinY"

        fun deserialize(tag: CompoundTag): PermanentThresholdFrame? {
            if (!KotlinNbtCompat.contains(tag, TAG_AXIS)
                || !KotlinNbtCompat.contains(tag, TAG_PLANE)
                || !KotlinNbtCompat.contains(tag, TAG_MIN_HORIZONTAL)
                || !KotlinNbtCompat.contains(tag, TAG_MIN_Y)
            ) {
                return null
            }

            val axis = try {
                Direction.Axis.valueOf(KotlinNbtCompat.getString(tag, TAG_AXIS))
            } catch (_: IllegalArgumentException) {
                return null
            }

            return PermanentThresholdFrame(
                axis = axis,
                plane = KotlinNbtCompat.getInt(tag, TAG_PLANE),
                minHorizontal = KotlinNbtCompat.getInt(tag, TAG_MIN_HORIZONTAL),
                minY = KotlinNbtCompat.getInt(tag, TAG_MIN_Y)
            )
        }
    }
}

object PermanentThresholdFrames {
    data class RingStateSnapshot(
        val replacedStates: List<Pair<BlockPos, BlockState>>
    )

    fun findAt(level: BlockGetter, touchedPos: BlockPos): PermanentThresholdFrame? {
        // Is it axes or axises? I could search this but I kinda like going with axes lol.
        val axes = arrayOf(Direction.Axis.X, Direction.Axis.Z)
        for (axis in axes) {
            val plane = if (axis == Direction.Axis.Z) touchedPos.z else touchedPos.x
            val horizontal = if (axis == Direction.Axis.Z) touchedPos.x else touchedPos.z
            for (minHorizontal in (horizontal - 3)..horizontal) {
                for (minY in (touchedPos.y - 4)..touchedPos.y) {
                    val frame = PermanentThresholdFrame(axis, plane, minHorizontal, minY)
                    if (isValid(level, frame, touchedPos)) {
                        return frame
                    }
                }
            }
        }

        return null
    }

    fun findByAnchor(level: BlockGetter, anchorPos: BlockPos, axisHint: Direction.Axis? = null): PermanentThresholdFrame? {
        val axes = axisHint?.let { arrayOf(it) } ?: arrayOf(Direction.Axis.X, Direction.Axis.Z)
        for (axis in axes) {
            val plane = if (axis == Direction.Axis.Z) anchorPos.z else anchorPos.x
            val horizontal = if (axis == Direction.Axis.Z) anchorPos.x else anchorPos.z
            val frame = PermanentThresholdFrame(axis, plane, horizontal - 1, anchorPos.y - 2)
            if (isValid(level, frame)) {
                return frame
            }
        }

        return null
    }

    fun findContaining(level: BlockGetter, pos: BlockPos, axisHint: Direction.Axis? = null): PermanentThresholdFrame? {
        val axes = axisHint?.let { arrayOf(it) } ?: arrayOf(Direction.Axis.X, Direction.Axis.Z)
        for (axis in axes) {
            val plane = if (axis == Direction.Axis.Z) pos.z else pos.x
            val horizontal = if (axis == Direction.Axis.Z) pos.x else pos.z
            for (minHorizontal in (horizontal - 3)..horizontal) {
                for (minY in (pos.y - 4)..pos.y) {
                    val frame = PermanentThresholdFrame(axis, plane, minHorizontal, minY)
                    if (!contains(frame, pos)) {
                        continue
                    }
                    if (isValid(level, frame)) {
                        return frame
                    }
                }
            }
        }

        return null
    }

    fun isValid(level: BlockGetter, frame: PermanentThresholdFrame, touchedPos: BlockPos? = null): Boolean {
        var touchedOnRing = touchedPos == null
        for (horizontal in 0..3) {
            for (vertical in 0..4) {
                val pos = frame.framePos(horizontal, vertical)
                val isRing = horizontal == 0 || horizontal == 3 || vertical == 0 || vertical == 4
                if (!isRing) {
                    continue
                }

                val state = level.getBlockState(pos)
                if (!isValidFrameBlock(state.block)) {
                    return false
                }

                if (pos == touchedPos) {
                    touchedOnRing = true
                }
            }
        }

        return touchedOnRing
    }

    fun aperturePositions(frame: PermanentThresholdFrame): Sequence<BlockPos> = sequence {
        for (horizontal in 1..2) {
            for (vertical in 1..3) {
                yield(frame.framePos(horizontal, vertical))
            }
        }
    }

    fun isAnchorBlock(frame: PermanentThresholdFrame, pos: BlockPos): Boolean = frame.anchorPos() == pos

    fun isPermanentThresholdFrameBlock(block: Block): Boolean = isValidFrameBlock(block)

    fun resetFrameRingToDeepslate(level: Level, frame: PermanentThresholdFrame, skipPos: BlockPos? = null) {
        for (horizontal in 0..3) {
            for (vertical in 0..4) {
                val isRing = horizontal == 0 || horizontal == 3 || vertical == 0 || vertical == 4
                if (!isRing) {
                    continue
                }

                val pos = frame.framePos(horizontal, vertical)
                if (skipPos != null && pos == skipPos) {
                    continue
                }

                val state = level.getBlockState(pos)
                if (!isPermanentThresholdFrameBlock(state.block)) {
                    continue
                }

                level.setBlock(pos, Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL)
            }
        }
    }

    fun applyStyledFrameRing(level: Level, frame: PermanentThresholdFrame): RingStateSnapshot {
        val replacedStates = ArrayList<Pair<BlockPos, BlockState>>()

        for (horizontal in 0..3) {
            for (vertical in 0..4) {
                val isRing = horizontal == 0 || horizontal == 3 || vertical == 0 || vertical == 4
                if (!isRing) {
                    continue
                }

                val pos = frame.framePos(horizontal, vertical)
                val currentState = level.getBlockState(pos)
                val currentBlock = currentState.block
                if (!isValidFrameBlock(currentBlock)) {
                    continue
                }

                val desiredBlock = styledRingBlockFor(horizontal, vertical)
                if (currentBlock == desiredBlock) {
                    continue
                }

                replacedStates.add(pos.immutable() to currentState)
                level.setBlock(pos, desiredBlock.defaultBlockState(), Block.UPDATE_ALL)
            }
        }

        return RingStateSnapshot(replacedStates)
    }

    fun restoreStyledFrameRing(level: Level, snapshot: RingStateSnapshot) {
        for ((pos, state) in snapshot.replacedStates) {
            level.setBlock(pos, state, Block.UPDATE_ALL)
        }
    }

    private fun contains(frame: PermanentThresholdFrame, pos: BlockPos): Boolean {
        if (frame.axis == Direction.Axis.Z) {
            return pos.z == frame.plane
                && pos.x in frame.minHorizontal..(frame.minHorizontal + 3)
                && pos.y in frame.minY..(frame.minY + 4)
        }

        return pos.x == frame.plane
            && pos.z in frame.minHorizontal..(frame.minHorizontal + 3)
            && pos.y in frame.minY..(frame.minY + 4)
    }

    private fun isValidFrameBlock(block: Block): Boolean = when (block) {
        Blocks.DEEPSLATE,
        ManifestationBlocks.PERMANENT_THRESHOLD_FRAME_BLOCK,
        ManifestationBlocks.PERMANENT_THRESHOLD_PLINTH_BLOCK,
        ManifestationBlocks.PERMANENT_THRESHOLD_SIDE_PILLAR_BLOCK,
        ManifestationBlocks.PERMANENT_THRESHOLD_CAPSTONE_BLOCK,
        ManifestationBlocks.PERMANENT_THRESHOLD_INNER_EDGE_BLOCK -> true
        else -> false
    }

    private fun styledRingBlockFor(horizontal: Int, vertical: Int): Block {
        val corner = (horizontal == 0 || horizontal == 3) && (vertical == 0 || vertical == 4)
        if (corner) {
            return ManifestationBlocks.PERMANENT_THRESHOLD_FRAME_BLOCK
        }

        return when {
            vertical == 0 -> ManifestationBlocks.PERMANENT_THRESHOLD_PLINTH_BLOCK
            vertical == 4 -> ManifestationBlocks.PERMANENT_THRESHOLD_CAPSTONE_BLOCK
            vertical == 2 -> ManifestationBlocks.PERMANENT_THRESHOLD_INNER_EDGE_BLOCK
            else -> ManifestationBlocks.PERMANENT_THRESHOLD_SIDE_PILLAR_BLOCK
        }
    }
}