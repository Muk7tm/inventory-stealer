package com.inventorystealer.session;

import com.inventorystealer.integration.BackpackStorage;
import com.inventorystealer.integration.BackpackSupport;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Exposes the storage + tool slots of one specific backpack ItemStack held by a loot session.
 *
 * <p>The tab is bound to the backpack <em>instance</em> (reference identity), not to its item type:
 * it is only valid while that exact ItemStack is still in its victim slot. Once anyone takes the backpack
 * (which splits it, leaving the original empty), the tab stops exposing anything, so its contents can only
 * travel with the stolen backpack. Every change is written straight back into the backpack's components.
 */
public final class BackpackStorageContainer implements Container {
    private final DeathLootSession session;
    private final int victimSlot;
    private final ItemStack backpack;
    private final BackpackStorage storage;

    BackpackStorageContainer(DeathLootSession session, int victimSlot, ItemStack backpack) {
        this.session = session;
        this.victimSlot = victimSlot;
        this.backpack = backpack;
        this.storage = BackpackSupport.readStorage(backpack);
    }

    public boolean isValid() {
        return session.isActive() && !backpack.isEmpty() && session.victimContainer().getItem(victimSlot) == backpack;
    }

    public int victimSlot() {
        return victimSlot;
    }

    public ItemStack backpack() {
        return backpack;
    }

    public int storageSize() {
        return storage.storageSize();
    }

    public int toolSize() {
        return storage.toolSize();
    }

    /** Writes the working lists back into the backpack's data components (no-op when unchanged). */
    public void flush() {
        if (isValid()) {
            BackpackSupport.writeStorage(backpack, storage);
            session.markDirty();
        }
    }

    private NonNullList<ItemStack> listFor(int slot) {
        return slot < storage.storageSize() ? storage.storage() : storage.tools();
    }

    private int localIndex(int slot) {
        return slot < storage.storageSize() ? slot : slot - storage.storageSize();
    }

    private boolean inRange(int slot) {
        return slot >= 0 && slot < getContainerSize();
    }

    @Override
    public int getContainerSize() {
        return storage.storageSize() + storage.toolSize();
    }

    @Override
    public boolean isEmpty() {
        return !isValid() || (storage.storage().stream().allMatch(ItemStack::isEmpty) && storage.tools().stream().allMatch(ItemStack::isEmpty));
    }

    @Override
    public ItemStack getItem(int slot) {
        if (!isValid() || !inRange(slot)) {
            return ItemStack.EMPTY;
        }
        return listFor(slot).get(localIndex(slot));
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (!isValid() || !inRange(slot) || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = listFor(slot).get(localIndex(slot));
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = stack.split(amount);
        if (stack.isEmpty()) {
            listFor(slot).set(localIndex(slot), ItemStack.EMPTY);
        }
        flush();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (!isValid() || !inRange(slot)) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = listFor(slot).set(localIndex(slot), ItemStack.EMPTY);
        flush();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!isValid() || !inRange(slot)) {
            return;
        }
        listFor(slot).set(localIndex(slot), stack);
        flush();
    }

    @Override
    public void setChanged() {
        flush();
    }

    @Override
    public boolean stillValid(Player player) {
        return isValid() && session.canAccess(player);
    }

    @Override
    public void clearContent() {
        // Never wipe backpack storage through the generic Container API.
    }
}
