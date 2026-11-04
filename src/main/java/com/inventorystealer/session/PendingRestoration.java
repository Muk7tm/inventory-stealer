package com.inventorystealer.session;

import com.inventorystealer.InventoryStealer;
import com.inventorystealer.integration.EquipmentOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Items owed to a victim, with the slot layout needed to put them back. Used both for live restoration and
 * as the durable on-disk form (interrupted sessions and offline victims).
 */
public record PendingRestoration(UUID sessionId, UUID victimId, String victimName, int inventorySize,
                                 NonNullList<ItemStack> items, List<EquipmentOrigin> extraOrigins) {

    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("session", sessionId);
        tag.putUUID("victim", victimId);
        tag.putString("victimName", victimName);
        tag.putInt("inventorySize", inventorySize);
        tag.putInt("size", items.size());
        ListTag itemList = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("slot", i);
                try {
                    entry.put("item", stack.save(registries));
                } catch (RuntimeException e) {
                    // Never let one unencodable stack stop the rest of the file from saving.
                    InventoryStealer.LOGGER.error("Could not save item {} owed to {} (slot {})", stack, victimName, i, e);
                    continue;
                }
                itemList.add(entry);
            }
        }
        tag.put("items", itemList);
        ListTag originList = new ListTag();
        for (EquipmentOrigin origin : extraOrigins) {
            originList.add(origin.save());
        }
        tag.put("origins", originList);
        return tag;
    }

    public static PendingRestoration load(CompoundTag tag, HolderLookup.Provider registries) {
        int size = tag.getInt("size");
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        ListTag itemList = tag.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < itemList.size(); i++) {
            CompoundTag entry = itemList.getCompound(i);
            int slot = entry.getInt("slot");
            if (slot < 0 || slot >= size) {
                continue;
            }
            ItemStack stack = ItemStack.parse(registries, entry.getCompound("item")).orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
                InventoryStealer.LOGGER.error("Could not load a stored item for {} (slot {}): {}", tag.getString("victimName"), slot, entry);
            }
            items.set(slot, stack);
        }
        List<EquipmentOrigin> origins = new ArrayList<>();
        ListTag originList = tag.getList("origins", Tag.TAG_COMPOUND);
        for (int i = 0; i < originList.size(); i++) {
            origins.add(EquipmentOrigin.load(originList.getCompound(i)));
        }
        return new PendingRestoration(tag.getUUID("session"), tag.getUUID("victim"), tag.getString("victimName"),
                tag.getInt("inventorySize"), items, origins);
    }
}
