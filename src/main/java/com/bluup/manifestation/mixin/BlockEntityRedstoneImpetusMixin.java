package com.bluup.manifestation.mixin;

import at.petrak.hexcasting.common.blocks.circles.impetuses.BlockEntityRedstoneImpetus;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(BlockEntityRedstoneImpetus.class)
public abstract class BlockEntityRedstoneImpetusMixin {
    private static final String ECHO_PROFILE_MARKER_KEY = "manifestation_echo";
    private static final String ECHO_PROFILE_MARKER_VALUE = "1";

    @Shadow(remap = false) private UUID storedPlayer;
    @Shadow(remap = false) private GameProfile storedPlayerProfile;

    @Inject(method = "getStoredPlayer", at = @At("HEAD"), cancellable = true)
    private void manifestation$echoBoundAlwaysFake(CallbackInfoReturnable<ServerPlayer> cir) {
        if (!isEchoBoundProfile(this.storedPlayerProfile)) {
            return;
        }

        Level level = ((BlockEntity) (Object) this).getLevel();
        if (!(level instanceof ServerLevel serverLevel) || this.storedPlayer == null) {
            return;
        }

        GameProfile profile = ensureProfileForStoredPlayer(this.storedPlayerProfile, this.storedPlayer);
        cir.setReturnValue(FakePlayer.get(serverLevel, profile));
    }

    @Inject(method = "getStoredPlayer", at = @At("RETURN"), cancellable = true)
    private void manifestation$offlineStoredPlayerAsFake(CallbackInfoReturnable<ServerPlayer> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        if (!isEchoBoundProfile(this.storedPlayerProfile)) {
            return;
        }
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (!(level instanceof ServerLevel serverLevel) || this.storedPlayer == null) {
            return;
        }

        GameProfile profile = ensureProfileForStoredPlayer(this.storedPlayerProfile, this.storedPlayer);

        cir.setReturnValue(FakePlayer.get(serverLevel, profile));
    }

    @Inject(method = "updatePlayerProfile", at = @At("HEAD"), cancellable = true, remap = false)
    private void manifestation$keepEchoBindingProfile(CallbackInfo ci) {
        if (isEchoBoundProfile(this.storedPlayerProfile)) {
            ci.cancel();
        }
    }

    private static boolean isEchoBoundProfile(@Nullable GameProfile profile) {
        if (profile == null) {
            return false;
        }
        if (profile.getProperties().containsKey(ECHO_PROFILE_MARKER_KEY)) {
            for (Property property : profile.getProperties().get(ECHO_PROFILE_MARKER_KEY)) {
                if (ECHO_PROFILE_MARKER_VALUE.equals(property.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static GameProfile ensureProfileForStoredPlayer(@Nullable GameProfile profile, UUID storedPlayer) {
        if (profile != null && profile.getId() != null && storedPlayer.equals(profile.getId())) {
            return profile;
        }

        String baseName = profile != null && profile.getName() != null && !profile.getName().isBlank()
            ? profile.getName()
            : "Player";
        GameProfile rebuilt = new GameProfile(storedPlayer, baseName);
        if (profile != null && profile.getProperties().containsKey(ECHO_PROFILE_MARKER_KEY)) {
            rebuilt.getProperties().put(ECHO_PROFILE_MARKER_KEY,
                new Property(ECHO_PROFILE_MARKER_KEY, ECHO_PROFILE_MARKER_VALUE));
        }
        return rebuilt;
    }

}
