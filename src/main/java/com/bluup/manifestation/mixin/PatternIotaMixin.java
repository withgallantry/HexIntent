package com.bluup.manifestation.mixin;

import at.petrak.hexcasting.api.casting.eval.sideeffects.EvalSound;
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import com.bluup.manifestation.server.CastSoundSuppressor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(PatternIota.class)
public abstract class PatternIotaMixin {
    private static final ThreadLocal<UUID> manifestation$currentCaster = new ThreadLocal<>();

    @Inject(method = "execute", at = @At("HEAD"), remap = false)
    private void manifestation$beginExecute(
        CastingVM vm,
        ServerLevel world,
        SpellContinuation continuation,
        CallbackInfoReturnable<?> cir
    ) {
        var env = vm.getEnv();
        if (env == null) {
            manifestation$currentCaster.remove();
            return;
        }

        Entity caster = env.getCastingEntity();
        if (caster instanceof ServerPlayer player) {
            manifestation$currentCaster.set(player.getUUID());
        } else {
            manifestation$currentCaster.remove();
        }
    }

    @ModifyArg(
        method = "execute",
        at = @At(
            value = "INVOKE",
            target = "Lat/petrak/hexcasting/api/casting/eval/CastResult;<init>(Lat/petrak/hexcasting/api/casting/iota/Iota;Lat/petrak/hexcasting/api/casting/eval/vm/SpellContinuation;Lat/petrak/hexcasting/api/casting/eval/vm/CastingImage;Ljava/util/List;Lat/petrak/hexcasting/api/casting/eval/ResolvedPatternType;Lat/petrak/hexcasting/api/casting/eval/sideeffects/EvalSound;)V"
        ),
        index = 5,
        remap = false
    )
    private EvalSound manifestation$replaceSound(EvalSound original) {
        UUID playerId = manifestation$currentCaster.get();
        if (playerId == null) {
            return original;
        }
        if (!CastSoundSuppressor.INSTANCE.shouldSuppressCurrentExecution(playerId)) {
            return original;
        }
        return HexEvalSounds.MUTE;
    }

    @Inject(method = "execute", at = @At("RETURN"), remap = false)
    private void manifestation$endExecute(
        CastingVM vm,
        ServerLevel world,
        SpellContinuation continuation,
        CallbackInfoReturnable<?> cir
    ) {
        manifestation$currentCaster.remove();
    }
}
