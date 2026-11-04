package com.inventorystealer.integration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * Where a captured item outside the vanilla {@link net.minecraft.world.entity.player.Inventory} came from,
 * so it can be returned to the same place when the victim respawns.
 */
public record EquipmentOrigin(Kind kind, String slotId, int index) {
    public enum Kind {
        /** A Curios slot (Traveler's Backpack uses the "back" slot when back-slot integration is on). */
        CURIOS,
        /** Traveler's Backpack's own player attachment (used when no back-slot integration is active). */
        TRAVELERS_BACKPACK_ATTACHMENT,
        /** The item on the victim's cursor or in their 2x2 crafting grid; returned to the inventory. */
        LOOSE
    }

    public static EquipmentOrigin curios(String slotId, int index) {
        return new EquipmentOrigin(Kind.CURIOS, slotId, index);
    }

    public static EquipmentOrigin travelersBackpackAttachment() {
        return new EquipmentOrigin(Kind.TRAVELERS_BACKPACK_ATTACHMENT, "", 0);
    }

    public static EquipmentOrigin loose(String what) {
        return new EquipmentOrigin(Kind.LOOSE, what, 0);
    }

    public boolean isEquipment() {
        return kind != Kind.LOOSE;
    }

    public Component describe() {
        return switch (kind) {
            case CURIOS -> Component.translatable("inventorystealer.origin.curios", slotId);
            case TRAVELERS_BACKPACK_ATTACHMENT -> Component.translatable("inventorystealer.origin.equipped");
            case LOOSE -> Component.translatable("inventorystealer.origin.loose");
        };
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind.name());
        tag.putString("slot", slotId);
        tag.putInt("index", index);
        return tag;
    }

    public static EquipmentOrigin load(CompoundTag tag) {
        Kind kind;
        try {
            kind = Kind.valueOf(tag.getString("kind"));
        } catch (IllegalArgumentException e) {
            kind = Kind.LOOSE;
        }
        return new EquipmentOrigin(kind, tag.getString("slot"), tag.getInt("index"));
    }
}
