package com.inventorystealer.integration.curios;

import com.inventorystealer.integration.CapturedEquipment;
import com.inventorystealer.integration.EquipmentOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/** Curios access. Only loaded after {@code ModList.isLoaded("curios")} was checked. */
public final class CuriosCompat {
    private CuriosCompat() {
    }

    public static List<CapturedEquipment> extract(Player player, Predicate<ItemStack> filter, Predicate<ItemStack> keepInPlace) {
        Optional<ICuriosItemHandler> inventory = CuriosApi.getCuriosInventory(player);
        if (inventory.isEmpty()) {
            return List.of();
        }
        List<CapturedEquipment> captured = new ArrayList<>();
        for (SlotResult result : inventory.get().findCurios(filter)) {
            if (result.slotContext().cosmetic()) {
                continue;
            }
            String slotId = result.slotContext().identifier();
            int index = result.slotContext().index();
            Optional<ICurioStacksHandler> handler = inventory.get().getStacksHandler(slotId);
            if (handler.isEmpty()) {
                continue;
            }
            IDynamicStackHandler stacks = handler.get().getStacks();
            if (index < 0 || index >= stacks.getSlots()) {
                continue;
            }
            ItemStack stack = stacks.getStackInSlot(index);
            if (stack.isEmpty() || !filter.test(stack) || keepInPlace.test(stack)) {
                continue;
            }
            stacks.setStackInSlot(index, ItemStack.EMPTY);
            captured.add(new CapturedEquipment(EquipmentOrigin.curios(slotId, index), stack));
        }
        return captured;
    }

    public static boolean restore(Player player, String slotId, int index, ItemStack stack) {
        Optional<ICuriosItemHandler> inventory = CuriosApi.getCuriosInventory(player);
        if (inventory.isEmpty()) {
            return false;
        }
        Optional<ICurioStacksHandler> handler = inventory.get().getStacksHandler(slotId);
        if (handler.isEmpty()) {
            return false;
        }
        IDynamicStackHandler stacks = handler.get().getStacks();
        if (index < 0 || index >= stacks.getSlots() || !stacks.getStackInSlot(index).isEmpty()) {
            return false;
        }
        stacks.setStackInSlot(index, stack);
        return true;
    }
}
