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

    public static final HexReliquaryBlock HEX_RELIQUARY_BLOCK = new HexReliquaryBlock(
        FabricBlockSettings.copyOf(Blocks.DEEPSLATE)
            .strength(2.5f, 6.0f)
            .requiresCorrectToolForDrops()
            .lightLevel(state -> state.getValue(HexReliquaryBlock.FRAME) == 0 ? 4 : 9)
    );

    public static final MindVaultBlock MIND_VAULT_BLOCK = new MindVaultBlock(
        FabricBlockSettings.copyOf(Blocks.DEEPSLATE_BRICKS)
            .strength(2.5f, 6.0f)
            .requiresCorrectToolForDrops()
    );

    public static final EquationSynthBlock EQUATION_SYNTH_BLOCK = new EquationSynthBlock(
        FabricBlockSettings.copyOf(Blocks.POLISHED_DEEPSLATE)
            .strength(2.0f, 6.0f)
            .noOcclusion()
            .requiresCorrectToolForDrops()
    );

    public static final Item SPLINTER_CASTER_ITEM = new BlockItem(
        SPLINTER_CASTER_BLOCK,
        new Item.Properties()
    );

    public static final Item HEX_RELIQUARY_ITEM = new BlockItem(
        HEX_RELIQUARY_BLOCK,
        new Item.Properties()
    );

    public static final Item MIND_VAULT_ITEM = new BlockItem(
        MIND_VAULT_BLOCK,
        new Item.Properties()
    );

    public static final Item EQUATION_SYNTH_ITEM = new BlockItem(
        EQUATION_SYNTH_BLOCK,
        new Item.Properties()
    );

    public static BlockEntityType<IntentRelayBlockEntity> INTENT_RELAY_BLOCK_ENTITY;
    public static BlockEntityType<CorridorPortalBlockEntity> CORRIDOR_PORTAL_BLOCK_ENTITY;
    public static BlockEntityType<SplinterCasterBlockEntity> SPLINTER_CASTER_BLOCK_ENTITY;
    public static BlockEntityType<HexReliquaryBlockEntity> HEX_RELIQUARY_BLOCK_ENTITY;
    public static BlockEntityType<MindVaultBlockEntity> MIND_VAULT_BLOCK_ENTITY;
    public static BlockEntityType<EquationSynthBlockEntity> EQUATION_SYNTH_BLOCK_ENTITY;

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("corridor_portal"), CORRIDOR_PORTAL_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("intent_relay"), INTENT_RELAY_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("intent_relay_emitter"), INTENT_RELAY_EMITTER_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("splinter_caster"), SPLINTER_CASTER_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("hex_box"), HEX_RELIQUARY_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("mind_vault"), MIND_VAULT_BLOCK);
        Registry.register(BuiltInRegistries.BLOCK, Manifestation.id("equation_synth"), EQUATION_SYNTH_BLOCK);
        Registry.register(BuiltInRegistries.ITEM, Manifestation.id("splinter_caster"), SPLINTER_CASTER_ITEM);
        Registry.register(BuiltInRegistries.ITEM, Manifestation.id("hex_box"), HEX_RELIQUARY_ITEM);
        Registry.register(BuiltInRegistries.ITEM, Manifestation.id("mind_vault"), MIND_VAULT_ITEM);
        Registry.register(BuiltInRegistries.ITEM, Manifestation.id("equation_synth"), EQUATION_SYNTH_ITEM);

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries ->
            {
                entries.accept(SPLINTER_CASTER_ITEM);
                entries.accept(HEX_RELIQUARY_ITEM);
                entries.accept(MIND_VAULT_ITEM);
                entries.accept(EQUATION_SYNTH_ITEM);
            }
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

        HEX_RELIQUARY_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Manifestation.id("hex_box"),
            FabricBlockEntityTypeBuilder.create(HexReliquaryBlockEntity::new, HEX_RELIQUARY_BLOCK).build()
        );

        MIND_VAULT_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Manifestation.id("mind_vault"),
            FabricBlockEntityTypeBuilder.create(MindVaultBlockEntity::new, MIND_VAULT_BLOCK).build()
        );

        EQUATION_SYNTH_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Manifestation.id("equation_synth"),
            FabricBlockEntityTypeBuilder.create(EquationSynthBlockEntity::new, EQUATION_SYNTH_BLOCK).build()
        );
    }

    private ManifestationBlocks() {
    }
}
