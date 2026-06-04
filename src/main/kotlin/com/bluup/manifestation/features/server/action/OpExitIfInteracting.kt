package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.BarrelBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.EnderChestBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.phys.BlockHitResult

/**
 * If the caster is aiming at a likely interactable block, stop execution quietly.
 *
 * Useful near the top of charm hexes so right-clicking doors/chests/etc
 */
object OpExitIfInteracting : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val blockHit = aimedBlockHit(caster)
        val shouldExit = blockHit != null && isInteractable(caster, blockHit)

        if (shouldExit && blockHit != null) {
            triggerInteraction(caster, env.castingHand, blockHit)
        }

        val image2 = image.withUsedOp()
        val newContinuation = if (shouldExit) SpellContinuation.Done else continuation
        return OperationResult(image2, listOf(), newContinuation, HexEvalSounds.MUTE)
    }

    private fun aimedBlockHit(player: ServerPlayer): BlockHitResult? {
        val hit = player.pick(5.0, 0.0f, false)
        return hit as? BlockHitResult
    }

    private fun isInteractable(player: ServerPlayer, blockHit: BlockHitResult): Boolean {

        val level = player.serverLevel()
        val pos = blockHit.blockPos
        val state = level.getBlockState(pos)
        if (state.isAir) {
            return false
        }

        // Menu providers (chests, barrels, many blocks) are the strongest signal.
        // Can I find another way to make this more reboust for interactable things, how do items do it?
        if (state.getMenuProvider(level, pos) != null) {
            return true
        }

        val block = state.block
        return block is DoorBlock ||
            block is TrapDoorBlock ||
            block is FenceGateBlock ||
            block is ButtonBlock ||
            block is LeverBlock ||
            block is ChestBlock ||
            block is EnderChestBlock ||
            block is BarrelBlock ||
            block is ShulkerBoxBlock
    }

    private fun triggerInteraction(player: ServerPlayer, hand: net.minecraft.world.InteractionHand, blockHit: BlockHitResult) {
        val level = player.serverLevel()
        val pos = blockHit.blockPos
        val state = level.getBlockState(pos)
        if (state.isAir) {
            return
        }

        // Replay the block's normal right-click behavior so this guard does not swallow interaction.
        state.use(level, player, hand, blockHit)
    }
}
