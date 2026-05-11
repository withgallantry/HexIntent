package com.bluup.manifestation.server.block;

import com.bluup.manifestation.Manifestation;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public final class ManifestationBlocks {
    private static final SoundType CORRIDOR_PORTAL_SOUND = new SoundType(
        1.0f,
        1.0f,
        SoundEvents.ENDER_EYE_DEATH,
        SoundType.GLASS.getStepSound(),
        SoundType.GLASS.getPlaceSound(),
        SoundType.GLASS.getHitSound(),
        SoundType.GLASS.getFallSound()
    );

    public static final CorridorPortalBlock CORRIDOR_PORTAL_BLOCK = new CorridorPortalBlock(
        FabricBlockSettings.copyOf(Blocks.END_PORTAL_FRAME)
            .strength(3.0f, 1200.0f)
            .noOcclusion()
            .sound(CORRIDOR_PORTAL_SOUND)
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

    public static final SplinterCasterBlock SPLINTER_CASTER_BLOCK = new SplinterCasterBlock(
        FabricBlockSettings.copyOf(Blocks.DEEPSLATE_TILES)
            .strength(2.0f, 6.0f)
            .noOcclusion()
    );

    public static final Item SPLINTER_CASTER_ITEM = new BlockItem(
        SPLINTER_CASTER_BLOCK,
        new Item.Properties()
    );

    public static BlockEntityType<IntentRelayBlockEntity> INTENT_RELAY_BLOCK_ENTITY;
    public static BlockEntityType<CorridorPortalBlockEntity> CORRIDOR_PORTAL_BLOCK_ENTITY;
    public static BlockEntityType<SplinterCasterBlockEntity> SPLINTER_CASTER_BLOCK_ENTITY;

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("corridor_portal"), CORRIDOR_PORTAL_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("intent_relay"), INTENT_RELAY_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("intent_relay_emitter"), INTENT_RELAY_EMITTER_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("splinter_caster"), SPLINTER_CASTER_BLOCK);
        Registry.register(BuiltInRegistries.ITEM, Manifestation.id("splinter_caster"), SPLINTER_CASTER_ITEM);

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries ->
            entries.accept(SPLINTER_CASTER_ITEM)
        );

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

        SPLINTER_CASTER_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Manifestation.id("splinter_caster"),
            FabricBlockEntityTypeBuilder.create(SplinterCasterBlockEntity::new, SPLINTER_CASTER_BLOCK).build()
        );
    }

    private ManifestationBlocks() {
    }
}
