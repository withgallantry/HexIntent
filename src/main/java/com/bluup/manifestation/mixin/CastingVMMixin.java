package com.bluup.manifestation.mixin;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.ExecutionClientView;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import com.bluup.manifestation.server.CastSoundSuppressor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CastingVM.class)
public abstract class CastingVMMixin {
    @Inject(method = "queueExecuteAndWrapIotas", at = @At("RETURN"), remap = false)
    private void manifestation$clearSuppressionAfterBatch(
        List<?> iotas,
        ServerLevel world,
        CallbackInfoReturnable<ExecutionClientView> cir
    ) {
        CastingEnvironment env = ((CastingVM) (Object) this).getEnv();
        if (env == null) {
            return;
        }

        Entity caster = env.getCastingEntity();
        if (caster instanceof ServerPlayer player) {
            CastSoundSuppressor.INSTANCE.clearForPlayer(player.getUUID());
        }
    }
}
