package com.bluup.manifestation.server.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class PermanentThresholdFrameBlock extends Block {
    public PermanentThresholdFrameBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            PermanentThresholdFrame frame = PermanentThresholdFrames.INSTANCE.findContaining(level, pos, null);
            if (frame != null) {
                PermanentThresholdFrames.INSTANCE.resetFrameRingToDeepslate(level, frame, pos);
            }
        }

        super.playerWillDestroy(level, pos, state, player);
    }
}