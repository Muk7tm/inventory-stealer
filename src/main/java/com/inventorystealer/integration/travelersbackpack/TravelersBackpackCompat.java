package com.inventorystealer.integration.travelersbackpack;

import com.inventorystealer.InventoryStealer;
import com.inventorystealer.integration.BackpackStorage;
import com.inventorystealer.integration.BackpackSupport;
import com.inventorystealer.integration.CapturedEquipment;
import com.inventorystealer.integration.EquipmentOrigin;
import com.inventorystealer.integration.curios.CuriosCompat;
import com.tiviacz.travelersbackpack.TravelersBackpack;
import com.tiviacz.travelersbackpack.capability.AttachmentUtils;
import com.tiviacz.travelersbackpack.capability.ITravelersBackpack;
import com.tiviacz.travelersbackpack.components.BackpackContainerContents;
import com.tiviacz.travelersbackpack.init.ModDataComponents;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.items.TravelersBackpackItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Traveler's Backpack 10.1.x integration, written against the classes of 10.1.35.
 *
 * <p>A Traveler's Backpack keeps its whole state inside its own ItemStack data components
 * ({@code BACKPACK_CONTAINER}, {@code TOOLS_CONTAINER}, {@code UPGRADES}, ...). Moving the ItemStack therefore
 * moves its contents, and the storage is edited by replacing those immutable components.
 */
public final class TravelersBackpackCompat {
    private static boolean warnedAccessories;

    private TravelersBackpackCompat() {
    }

    public static boolean isBackpack(ItemStack stack) {
        return stack.getItem() instanceof TravelersBackpackItem;
    }

    public static List<CapturedEquipment> captureEquipped(ServerPlayer player, boolean keepInventory) {
        List<CapturedEquipment> captured = new ArrayList<>();
        if (TravelersBackpack.enableIntegration()) {
            // Back-slot integration: TB stores the worn backpack in a Curios/Accessories slot instead of its attachment.
            if (TravelersBackpack.enableCurios() && BackpackSupport.isCuriosLoaded()) {
                captured.addAll(CuriosCompat.extract(player, TravelersBackpackCompat::isBackpack,
                        stack -> !keepInventory && isVanishing(stack)));
            } else if (TravelersBackpack.enableAccessories() && !warnedAccessories) {
                warnedAccessories = true;
                InventoryStealer.LOGGER.warn("Traveler's Backpack uses Accessories integration, which Inventory Stealer does not support; "
                        + "backpacks in Accessories slots keep their normal death behaviour.");
            }
            return captured;
        }

        Optional<ITravelersBackpack> attachment = AttachmentUtils.getAttachment(player);
        if (attachment.isPresent() && attachment.get().hasBackpack()) {
            ItemStack worn = attachment.get().getBackpack();
            if (!worn.isEmpty() && !(!keepInventory && isVanishing(worn))) {
                // remove() drops TB's wrapper/reference and syncs the now empty back to tracking clients.
                attachment.get().remove();
                captured.add(new CapturedEquipment(EquipmentOrigin.travelersBackpackAttachment(), worn));
            }
        }
        return captured;
    }

    public static boolean restoreEquipped(ServerPlayer player, EquipmentOrigin origin, ItemStack stack) {
        return switch (origin.kind()) {
            case CURIOS -> BackpackSupport.isCuriosLoaded() && CuriosCompat.restore(player, origin.slotId(), origin.index(), stack);
            case TRAVELERS_BACKPACK_ATTACHMENT -> {
                if (!isBackpack(stack) || TravelersBackpack.enableIntegration() || AttachmentUtils.isWearingBackpack(player)) {
                    yield false;
                }
                AttachmentUtils.equipBackpack(player, stack);
                yield AttachmentUtils.isWearingBackpack(player);
            }
            case LOOSE -> false;
        };
    }

    public static BackpackStorage readStorage(ItemStack backpack) {
        // Same lazy size initialisation TB performs whenever it wraps a backpack.
        if (!BackpackWrapper.isSizeInitialized(backpack)) {
            BackpackWrapper.initializeSize(backpack);
        }
        int storageSize = backpack.getOrDefault(ModDataComponents.STORAGE_SLOTS.get(), 0);
        int toolSize = backpack.getOrDefault(ModDataComponents.TOOL_SLOTS.get(), 0);
        return new BackpackStorage(
                read(backpack, ModDataComponents.BACKPACK_CONTAINER.get(), storageSize),
                read(backpack, ModDataComponents.TOOLS_CONTAINER.get(), toolSize));
    }

    public static void writeStorage(ItemStack backpack, BackpackStorage storage) {
        write(backpack, ModDataComponents.BACKPACK_CONTAINER.get(), storage.storage());
        write(backpack, ModDataComponents.TOOLS_CONTAINER.get(), storage.tools());
    }

    private static NonNullList<ItemStack> read(ItemStack backpack, DataComponentType<BackpackContainerContents> type, int declaredSize) {
        BackpackContainerContents contents = backpack.get(type);
        int existing = contents == null ? 0 : contents.getItems().size();
        // Never shrink: a component longer than the declared size still owns those items.
        NonNullList<ItemStack> items = NonNullList.withSize(Math.max(declaredSize, existing), ItemStack.EMPTY);
        if (contents != null) {
            contents.copyInto(items);
        }
        return items;
    }

    private static void write(ItemStack backpack, DataComponentType<BackpackContainerContents> type, NonNullList<ItemStack> items) {
        if (items.isEmpty() && !backpack.has(type)) {
            return;
        }
        BackpackContainerContents updated = BackpackContainerContents.fromItems(items.size(), items);
        if (!updated.equals(backpack.get(type))) {
            backpack.set(type, updated);
        }
    }

    private static boolean isVanishing(ItemStack stack) {
        return EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP);
    }
}
