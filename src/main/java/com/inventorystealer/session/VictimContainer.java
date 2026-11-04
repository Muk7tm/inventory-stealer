package com.inventorystealer.session;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The victim's captured possessions. These are the only live references to those ItemStacks while the session runs.
 * Indices {@code [0, inventorySize)} mirror {@link net.minecraft.world.entity.player.Inventory} slots
 * (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand); later indices are equipment/loose extras.
 *
 * <p>Looters can only take: every mutation is rejected once the session is no longer active.
 */
public final class VictimContainer implements Container {
    private final DeathLootSession session;
    private final NonNullList<ItemStack> items;

    VictimContainer(DeathLootSession session, NonNullList<ItemStack> items) {
        this.session = session;
        this.items = items;
    }

    /** Direct access for capture, persistence and restoration. Not for menu code. */
    NonNullList<ItemStack> items() {
        return items;
    }

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (!session.isActive()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (!session.isActive()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = ContainerHelper.takeItem(items, slot);
        setChanged();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        // Vanilla slot code only writes here to clear a slot after taking from it (insertion is disabled by the slot).
        if (!session.isActive() || slot < 0 || slot >= items.size()) {
            return;
        }
        items.set(slot, stack);
        setChanged();
    }

    @Override
    public void setChanged() {
        session.markDirty();
    }

    @Override
    public boolean stillValid(Player player) {
        return session.canAccess(player);
    }

    @Override
    public void clearContent() {
        // Never wipe a victim's items through the generic Container API.
    }
}
