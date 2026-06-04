package com.bluup.manifestation.server.item;

import at.petrak.hexcasting.common.items.ItemStaff;
import com.bluup.manifestation.Manifestation;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public final class ManifestationItems {
    public static final Item MEMORY_CRYSTAL = Registry.register(
        BuiltInRegistries.ITEM,
        Manifestation.id("memory_crystal"),
        new ItemMemoryCrystal(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))
    );

    public static final Item BLOSSOM_STAFF = registerStaff("blossom_staff");
    public static final Item DARK_FOREST_STAFF = registerStaff("dark_forest_staff");
    public static final Item ECLIPSE_STAFF = registerStaff("eclipse_staff");
    public static final Item END_STAFF = registerStaff("end_staff");
    public static final Item FERN_STAFF = registerStaff("fern_staff");
    public static final Item GEODE_STAFF = registerStaff("geode_staff");
    public static final Item HYDRA_STAFF = registerStaff("hydra_staff");
    public static final Item LIGHT_STAFF = registerStaff("light_staff");
    public static final Item NETHER_STAFF = registerStaff("nether_staff");
    public static final Item OCEAN_STAFF = registerStaff("ocean_staff");
    public static final Item PENITENCE_STAFF = registerStaff("penitence_staff");
    public static final Item REDSTONE_STAFF = registerStaff("redstone_staff");
    public static final Item SHADOW_STAFF = registerStaff("shadow_staff");
    public static final Item TOTEM_STAFF = registerStaff("totem_staff");
    public static final Item WIND_STAFF = registerStaff("wind_staff");

    private static Item registerStaff(String path) {
        return Registry.register(
            BuiltInRegistries.ITEM,
            Manifestation.id(path),
            new ItemManifestationStaff(new Item.Properties().stacksTo(1).rarity(Rarity.RARE))
        );
    }

    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(MEMORY_CRYSTAL);
            entries.accept(BLOSSOM_STAFF);
            entries.accept(DARK_FOREST_STAFF);
            entries.accept(ECLIPSE_STAFF);
            entries.accept(END_STAFF);
            entries.accept(FERN_STAFF);
            entries.accept(GEODE_STAFF);
            entries.accept(HYDRA_STAFF);
            entries.accept(LIGHT_STAFF);
            entries.accept(NETHER_STAFF);
            entries.accept(OCEAN_STAFF);
            entries.accept(PENITENCE_STAFF);
            entries.accept(REDSTONE_STAFF);
            entries.accept(SHADOW_STAFF);
            entries.accept(TOTEM_STAFF);
            entries.accept(WIND_STAFF);
        });
    }

    private ManifestationItems() {
    }
}
