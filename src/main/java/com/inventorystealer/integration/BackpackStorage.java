package com.inventorystealer.integration;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/**
 * Working copy of the lootable sections of one backpack's storage.
 * The lists are owned by the loot session; they are written back into the backpack ItemStack after every change.
 */
public record BackpackStorage(NonNullList<ItemStack> storage, NonNullList<ItemStack> tools) {
    public int storageSize() {
        return storage.size();
    }

    public int toolSize() {
        return tools.size();
    }
}
