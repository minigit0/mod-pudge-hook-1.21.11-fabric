package com.example.pudgehook;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public class PudgeHookMod implements ModInitializer {
    public static final String MOD_ID = "pudgehook";
    public static Item PUDGE_HOOK;

    @Override
    public void onInitialize() {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "pudge_hook"));
        PUDGE_HOOK = Registry.register(Registries.ITEM, key,
                new PudgeHookItem(new Item.Settings().registryKey(key).maxCount(1)));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> e.add(PUDGE_HOOK));
    }
}
