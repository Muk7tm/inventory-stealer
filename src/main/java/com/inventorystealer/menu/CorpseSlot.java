package com.inventorystealer.menu;

import com.mojang.datafixers.util.Pair;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A take-only slot of a corpse menu that is only shown and usable while its view (tab/page) is selected. */
public class CorpseSlot extends Slot {
    private final CorpseMenu menu;
    private final int view;
    @Nullable
    private final ResourceLocation emptyIcon;

    public CorpseSlot(CorpseMenu menu, int view, Container container, int index, int x, int y, @Nullable ResourceLocation emptyIcon) {
        super(container, index, x, y);
        this.menu = menu;
        this.view = view;
        this.emptyIcon = emptyIcon;
    }

    public int view() {
        return view;
    }

    @Override
    public boolean isActive() {
        return menu.isViewShown(view);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return isActive() && menu.canLoot(player) && !getItem().isEmpty();
    }

    /** Allows taking part of a stack (vanilla only permits all-or-nothing takes when this is false). */
    @Override
    public boolean allowModification(Player player) {
        return mayPickup(player);
    }

    @Nullable
    @Override
    public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
        return emptyIcon == null ? null : Pair.of(InventoryMenu.BLOCK_ATLAS, emptyIcon);
    }
}
