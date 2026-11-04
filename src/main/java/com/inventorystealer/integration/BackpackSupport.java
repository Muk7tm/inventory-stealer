package com.inventorystealer.integration;

import com.inventorystealer.integration.travelersbackpack.TravelersBackpackCompat;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Entry point for all Traveler's Backpack interaction. Every call is guarded so no Traveler's Backpack
 * or Curios class is loaded when those mods are not installed.
 */
public final class BackpackSupport {
    private static Boolean travelersBackpackLoaded;
    private static Boolean curiosLoaded;

    private BackpackSupport() {
    }

    public static boolean isTravelersBackpackLoaded() {
        if (travelersBackpackLoaded == null) {
            travelersBackpackLoaded = ModList.get().isLoaded("travelersbackpack");
        }
        return travelersBackpackLoaded;
    }

    public static boolean isCuriosLoaded() {
        if (curiosLoaded == null) {
            curiosLoaded = ModList.get().isLoaded("curios");
        }
        return curiosLoaded;
    }

    public static boolean isBackpack(ItemStack stack) {
        return !stack.isEmpty() && isTravelersBackpackLoaded() && TravelersBackpackCompat.isBackpack(stack);
    }

    /** Removes equipped backpacks (Curios back slot or TB attachment) from the player and returns them. */
    public static List<CapturedEquipment> captureEquippedBackpacks(ServerPlayer player, boolean keepInventory) {
        return isTravelersBackpackLoaded() ? TravelersBackpackCompat.captureEquipped(player, keepInventory) : List.of();
    }

    /** Puts an equipped item back where it came from. Returns false if that place is unavailable or occupied. */
    public static boolean restoreEquipped(ServerPlayer player, EquipmentOrigin origin, ItemStack stack) {
        if (!isTravelersBackpackLoaded()) {
            return false;
        }
        return TravelersBackpackCompat.restoreEquipped(player, origin, stack);
    }

    public static BackpackStorage readStorage(ItemStack backpack) {
        return TravelersBackpackCompat.readStorage(backpack);
    }

    public static void writeStorage(ItemStack backpack, BackpackStorage storage) {
        TravelersBackpackCompat.writeStorage(backpack, storage);
    }
}
