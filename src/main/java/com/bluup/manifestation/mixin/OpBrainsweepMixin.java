package com.bluup.manifestation.mixin;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.RenderedSpell;
import at.petrak.hexcasting.api.casting.castables.SpellAction;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.MishapBadBlock;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.common.casting.actions.spells.great.OpBrainsweep;
import com.bluup.manifestation.server.block.ManifestationBlocks;
import com.bluup.manifestation.server.block.MindVaultBlockEntity;
import com.bluup.manifestation.server.mishap.MishapMindVaultFull;
import com.bluup.manifestation.server.mishap.MishapMindVaultTypeMismatch;
import com.bluup.manifestation.server.mishap.MishapMindVaultUnavailable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(OpBrainsweep.class)
public abstract class OpBrainsweepMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
    private void manifestation$brainsweepFromMindVault(
        List<Iota> args,
        CastingEnvironment env,
        CallbackInfoReturnable<SpellAction.Result> cir
    ) {
        if (args == null || args.size() < 2) {
            return;
        }

        var world = env.getWorld();

        // Store Villager
        if (!(args.get(1) instanceof Vec3Iota vaultIota)) {
            return;
        }

        if (args.get(0) instanceof EntityIota entityIota && entityIota.getEntity() instanceof Villager villager) {
            BlockPos vaultPos = BlockPos.containing(vaultIota.getVec3());
            if (world.getBlockState(vaultPos).getBlock() != ManifestationBlocks.MIND_VAULT_BLOCK) {
                return;
            }

            var be = world.getBlockEntity(vaultPos);
            if (!(be instanceof MindVaultBlockEntity mindVault)) {
                throw new MishapBadBlock(vaultPos, Component.translatable("block.manifestation.mind_vault"));
            }

            env.assertEntityInRange(villager);
            env.assertVecInRange(vaultIota.getVec3());

            switch (mindVault.tryStore(villager)) {
                case STORED -> {
                    RenderedSpell noop = new RenderedSpell() {
                        @Override
                        public void cast(CastingEnvironment ignored) {
                        }

                        @Override
                        public CastingImage cast(CastingEnvironment ignored, CastingImage image) {
                            return image;
                        }
                    };
                    SpellAction.Result result = new SpellAction.Result(
                        noop,
                        0L,
                        List.of(ParticleSpray.cloud(villager.position(), 1.0, 20)),
                        0L
                    );

                    villager.discard();
                    world.playSound(
                        null,
                        vaultPos,
                        SoundEvents.AMETHYST_CLUSTER_PLACE,
                        SoundSource.BLOCKS,
                        0.9f,
                        1.1f
                    );
                    cir.setReturnValue(result);
                    return;
                }
                case FULL -> throw new MishapMindVaultFull();
                case TYPE_MISMATCH -> throw new MishapMindVaultTypeMismatch();
                case INVALID_PROFILE -> throw MishapInvalidIota.ofType(args.get(0), 0, "villager with profession and level");
            }
        }

        // Branch 2: flay from a vault profile (vault vec, target vec).
        if (!(args.get(0) instanceof Vec3Iota sourceVaultIota) || !(args.get(1) instanceof Vec3Iota targetIota)) {
            return;
        }

        env.assertVecInRange(sourceVaultIota.getVec3());
        env.assertVecInRange(targetIota.getVec3());

        BlockPos flayVaultPos = BlockPos.containing(sourceVaultIota.getVec3());
        BlockPos targetPos = BlockPos.containing(targetIota.getVec3());
        if (world.getBlockState(flayVaultPos).getBlock() != ManifestationBlocks.MIND_VAULT_BLOCK) {
            return;
        }
        if (world.getBlockState(targetPos).getBlock() == ManifestationBlocks.MIND_VAULT_BLOCK) {
            throw MishapInvalidIota.ofType(args.get(1), 1, "non_mind_vault_target_vector");
        }

        var flayBe = world.getBlockEntity(flayVaultPos);
        if (!(flayBe instanceof MindVaultBlockEntity flayMindVault)) {
            throw new MishapBadBlock(flayVaultPos, Component.translatable("block.manifestation.mind_vault"));
        }

        var synthetic = flayMindVault.createTemplateVillager(world, flayVaultPos);
        if (synthetic == null) {
            throw new MishapMindVaultUnavailable();
        }

        var replacement = new ArrayList<>(args);
        replacement.set(0, new EntityIota(synthetic));

        SpellAction.Result result = OpBrainsweep.INSTANCE.execute(replacement, env);
        if (!flayMindVault.claimForFlay(world.getGameTime())) {
            throw new MishapMindVaultUnavailable();
        }
        cir.setReturnValue(result);
    }
}
