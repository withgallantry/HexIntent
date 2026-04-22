package com.bluup.manifestation.server.block;

import com.bluup.manifestation.Manifestation;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ManifestationBlocks {
    public static final CorridorPortalBlock CORRIDOR_PORTAL_BLOCK = new CorridorPortalBlock(
        FabricBlockSettings.copyOf(Blocks.END_PORTAL_FRAME)
            .strength(-1.0f, 3600000.0f)
            .noOcclusion()
            .lightLevel(state -> 8)
    );

    public static final IntentRelayBlock INTENT_RELAY_BLOCK = new IntentRelayBlock(
        FabricBlockSettings.copyOf(Blocks.STONE_BUTTON)
            .strength(0.4f)
            .noOcclusion()
    );

    public static final IntentRelayEmitterBlock INTENT_RELAY_EMITTER_BLOCK = new IntentRelayEmitterBlock(
        FabricBlockSettings.copyOf(Blocks.REDSTONE_WIRE)
            .strength(0.0f)
            .noCollission()
            .noOcclusion()
    );

    public static BlockEntityType<IntentRelayBlockEntity> INTENT_RELAY_BLOCK_ENTITY;
    public static BlockEntityType<CorridorPortalBlockEntity> CORRIDOR_PORTAL_BLOCK_ENTITY;

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("corridor_portal"), CORRIDOR_PORTAL_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("intent_relay"), INTENT_RELAY_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("intent_relay_emitter"), INTENT_RELAY_EMITTER_BLOCK);

        CORRIDOR_PORTAL_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Manifestation.id("corridor_portal"),
            FabricBlockEntityTypeBuilder.create(CorridorPortalBlockEntity::new, CORRIDOR_PORTAL_BLOCK).build()
        );

        INTENT_RELAY_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Manifestation.id("intent_relay"),
            FabricBlockEntityTypeBuilder.create(IntentRelayBlockEntity::new, INTENT_RELAY_BLOCK).build()
        );
    }

    private ManifestationBlocks() {
    }
}
