package com.bluup.manifestation.server.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import at.petrak.hexcasting.api.casting.circles.ICircleComponent.ControlFlow
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.server.mishap.MishapSplinterCasterNeedsFocus
import com.bluup.manifestation.server.splinter.SplinterRuntime
import com.mojang.datafixers.util.Pair
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.Containers
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

class SplinterCasterBlock(properties: Properties) : BlockCircleComponent(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(ENERGIZED, false).setValue(ACTIVE, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(ACTIVE)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = Shapes.block()

    override fun normalDir(pos: BlockPos, bs: BlockState, world: Level, recursionLeft: Int): Direction = Direction.UP

    override fun particleHeight(pos: BlockPos, bs: BlockState, world: Level): Float = 0.0f

    override fun canEnterFromDirection(
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel
    ): Boolean = true

    override fun possibleExitDirections(pos: BlockPos, bs: BlockState, world: Level): EnumSet<Direction> {
        val exits = EnumSet.allOf(Direction::class.java)
        exits.remove(Direction.UP)
        return exits
    }

    override fun acceptControlFlow(
        imageIn: CastingImage,
        env: CircleCastEnv,
        enterDir: Direction,
        pos: BlockPos,
        bs: BlockState,
        world: ServerLevel
    ): ControlFlow {
        val be = world.getBlockEntity(pos) as? SplinterCasterBlockEntity ?: return ControlFlow.Stop()
        val active = SplinterRuntime.hasAnchoredSplinterAt(world.server, world.dimension().location().toString(), pos)

        if (world.hasNeighborSignal(pos)) {
            if (active) {
                SplinterRuntime.removeAnchoredAt(world.server, world.dimension().location().toString(), pos)
            }
            if (be.isWaitingForSplinter()) {
                be.setWaitingForSplinter(false)
            }
            return ControlFlow.Continue(imageIn, listOf(exitPositionFromDirection(pos, enterDir)))
        }

        if (be.isWaitingForSplinter()) {
            if (active) {
                return ControlFlow.Continue(imageIn, listOf(Pair.of(pos, enterDir)))
            }

            be.setWaitingForSplinter(false)
            return ControlFlow.Continue(imageIn, listOf(exitPositionFromDirection(pos, enterDir)))
        }

        if (active) {
            return ControlFlow.Continue(imageIn, listOf(Pair.of(pos, enterDir)))
        }

        if (!be.hasFocus()) {
            fakeThrowMishap(pos, bs, imageIn, env, MishapSplinterCasterNeedsFocus())
            return ControlFlow.Stop()
        }

        val payload = readFocusPayload(be, world)
        if (payload.isEmpty()) {
            fakeThrowMishap(pos, bs, imageIn, env, MishapSplinterCasterNeedsFocus())
            return ControlFlow.Stop()
        }

        val summonPos = Vec3.atCenterOf(pos).add(0.0, 1.0, 0.0)
        val ownerId = SplinterRuntime.circleOwnerId(world.dimension().location().toString(), pos)
        val circleCaster = SplinterRuntime.circleCasterFor(world, ownerId)
        val impetusPos = env.impetus?.blockPos ?: pos

        val pending = try {
            SplinterRuntime.prepareAnchoredSummon(
                env,
                circleCaster,
                summonPos,
                0L,
                payload,
                imageIn,
                summonPos,
                Vec3.atCenterOf(impetusPos)
            )
        } catch (mishap: Mishap) {
            throw mishap
        } catch (_: Throwable) {
            return ControlFlow.Stop()
        }

        if (pending.mediaCost > 0L && env.extractMedia(pending.mediaCost, true) > 0L) {
            fakeThrowMishap(pos, bs, imageIn, env, MishapNotEnoughMedia(pending.mediaCost))
            return ControlFlow.Stop()
        }

        SplinterRuntime.commitSummon(world.server, pending)
        be.setWaitingForSplinter(true)
        return ControlFlow.Continue(imageIn, listOf(Pair.of(pos, enterDir)))
    }

    private fun readFocusPayload(be: SplinterCasterBlockEntity, level: ServerLevel): List<Iota> {
        val focus = be.getFocusCopy()
        if (focus.isEmpty) {
            return listOf()
        }

        val holder = IXplatAbstractions.INSTANCE.findDataHolder(focus) ?: return listOf()
        val datum = holder.readIota(level) ?: return listOf()
        if (datum is ListIota) {
            return datum.list.toList()
        }
        return listOf(datum)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return SplinterCasterBlockEntity(pos, state)
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) {
            return null
        }
        if (type != ManifestationBlocks.SPLINTER_CASTER_BLOCK_ENTITY) {
            return null
        }
        return BlockEntityTicker { world, _, _, be ->
            (be as SplinterCasterBlockEntity).tickServer(world as ServerLevel)
        }
    }

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS
        }

        val be = level.getBlockEntity(pos) as? SplinterCasterBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        val existingFocus = be.getFocusCopy()
        val active = SplinterRuntime.hasAnchoredSplinterAt(
            level.server!!,
            level.dimension().location().toString(),
            pos
        )

        if (active && (!existingFocus.isEmpty || !held.isEmpty)) {
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.5f, 0.8f)
            return InteractionResult.CONSUME
        }

        if (!held.isEmpty) {
            if (!isValidFocusItem(held)) {
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.5f, 0.8f)
                return InteractionResult.CONSUME
            }

            be.setFocus(held.copyWithCount(1))
            held.shrink(1)

            if (!existingFocus.isEmpty) {
                if (held.isEmpty) {
                    player.setItemInHand(hand, existingFocus)
                } else if (!player.addItem(existingFocus)) {
                    player.drop(existingFocus, false)
                }
            }

            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8f, 1.0f)

            return InteractionResult.CONSUME
        }

        if (!existingFocus.isEmpty) {
            be.popFocus()
            player.setItemInHand(hand, existingFocus)
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.8f, 1.0f)
            return InteractionResult.CONSUME
        }

        return InteractionResult.PASS
    }

    private fun isValidFocusItem(stack: net.minecraft.world.item.ItemStack): Boolean {
        return IXplatAbstractions.INSTANCE.findDataHolder(stack) != null
    }

    override fun onRemove(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        newState: BlockState,
        isMoving: Boolean
    ) {
        if (state.block != newState.block && !isMoving) {
            if (!level.isClientSide) {
                val server = level.server
                if (server != null) {
                    SplinterRuntime.removeAnchoredAt(server, level.dimension().location().toString(), pos)
                }
            }
            val be = level.getBlockEntity(pos) as? SplinterCasterBlockEntity
            val focus = be?.popFocus()
            if (focus != null && !focus.isEmpty) {
                Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, focus)
            }
        }

        super.onRemove(state, level, pos, newState, isMoving)
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val be = level.getBlockEntity(pos) as? SplinterCasterBlockEntity ?: return
        be.tickServer(level)
    }

    companion object {
        val ACTIVE: BooleanProperty = BooleanProperty.create("active")
    }
}
