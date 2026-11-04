package com.inventorystealer.integration;

import net.minecraft.world.item.ItemStack;

/** An item that was removed from an equipment slot outside the vanilla inventory. */
public record CapturedEquipment(EquipmentOrigin origin, ItemStack stack) {
}
